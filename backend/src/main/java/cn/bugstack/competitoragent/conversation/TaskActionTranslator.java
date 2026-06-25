package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationActionConfirmationRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任务动作翻译器。
 * 它只负责把自然语言动作、任务节点状态和最近一次编排决策翻译成“预览语义”，不直接执行任何任务控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskActionTranslator {

    private final PromptTemplateService promptTemplateService;

    public ConversationResponse.TaskActionPreview buildTaskActionPreview(String message,
                                                                        Long taskId,
                                                                        TaskResponse task,
                                                                        List<TaskNodeResponse> nodes) {
        return buildTaskActionPreview(message, taskId, task, nodes, null);
    }

    /**
     * TASK_ACTION 模式优先消费最近一次编排决策。
     * 如果最近决策已经明确告诉我们“应该补证、改写还是等待人工”，就直接把该决策翻译成统一预览，
     * 避免 Conversation 再次凭字符串猜测下一步动作。
     */
    public ConversationResponse.TaskActionPreview buildTaskActionPreview(String message,
                                                                        Long taskId,
                                                                        TaskResponse task,
                                                                        List<TaskNodeResponse> nodes,
                                                                        ConversationOrchestrationDecisionView decisionView) {
        String promptPreview = safe(promptTemplateService.render("task-action-translator", Map.of(
                "userMessage", safe(message),
                "actionContext", "taskId=" + taskId + ", taskStatus=" + (task == null ? null : task.getStatus())
        )));
        log.debug("task action translator prompt prepared, length={}", promptPreview.length());

        ConversationResponse.TaskActionPreview decisionPreview = buildDecisionPreview(taskId, decisionView);
        if (decisionPreview != null) {
            return decisionPreview;
        }

        TaskNodeResponse targetNode = matchTargetNode(message, nodes);
        if (targetNode != null) {
            return attachDecisionSummary(ConversationResponse.TaskActionPreview.builder()
                    .actionType("RERUN_NODE")
                    .taskId(taskId)
                    .targetNodeName(targetNode.getNodeName())
                    .title("从 " + targetNode.getNodeName() + " 开始重跑")
                    .actionSummary(firstNonBlank(targetNode.getRerunActionSummary(), "系统会从该节点重新组织后续执行链路。"))
                    .impactSummary(firstNonBlank(targetNode.getImpactSummary(), "将影响当前节点及其后续链路。"))
                    .riskLevel("HIGH")
                    .requiresConfirmation(true)
                    .confirmationHint("统一入口会先展示影响范围；确认后可直接在这里提交重跑执行。")
                    .executable(false)
                    .sourceUrls(List.of())
                    .build(), decisionView);
        }

        return attachDecisionSummary(ConversationResponse.TaskActionPreview.builder()
                .actionType("RESUME_TASK")
                .taskId(taskId)
                .title("恢复任务执行")
                .actionSummary(task == null ? "系统会尝试基于已有检查点恢复任务。"
                        : firstNonBlank(task.getResumeAdvice(), "系统会尝试基于已有检查点恢复任务。"))
                .impactSummary("会保留已完成节点成果，只恢复中断或待继续的链路。")
                .riskLevel("HIGH")
                .requiresConfirmation(true)
                .confirmationHint("统一入口会先展示恢复影响范围；确认后可直接在这里提交恢复执行。")
                .executable(false)
                .sourceUrls(List.of())
                .build(), decisionView);
    }

    public ConversationResponse.TaskActionPreview buildResearchPreview(String message,
                                                                       Long taskId,
                                                                       List<TaskNodeResponse> nodes,
                                                                       List<String> sourceUrls) {
        return buildResearchPreview(message, taskId, nodes, sourceUrls, null);
    }

    /**
     * RESEARCH 模式在有编排决策时也要先看最近一次决策。
     * 但 WAIT_FOR_HUMAN 不能把普通补证预览整个吃掉，否则用户会丢失“仍可补充哪些证据”的上下文；
     * 因此这里对 WAIT_FOR_HUMAN 做穿透，并把编排决策摘要挂回最终 preview。
     */
    public ConversationResponse.TaskActionPreview buildResearchPreview(String message,
                                                                       Long taskId,
                                                                       List<TaskNodeResponse> nodes,
                                                                       List<String> sourceUrls,
                                                                       ConversationOrchestrationDecisionView decisionView) {
        String promptPreview = safe(promptTemplateService.render("task-action-translator", Map.of(
                "userMessage", safe(message),
                "actionContext", "research-taskId=" + taskId
        )));
        log.debug("research preview prompt prepared, length={}", promptPreview.length());

        ConversationResponse.TaskActionPreview decisionPreview = buildDecisionPreview(taskId, decisionView);
        if (decisionPreview != null && !"WAIT_FOR_HUMAN".equalsIgnoreCase(safe(decisionPreview.getActionType()))) {
            return decisionPreview;
        }

        TaskNodeResponse collectorNode = nodes == null
                ? null
                : nodes.stream()
                .filter(node -> node.getAgentType() != null && "COLLECTOR".equals(node.getAgentType().name()))
                .findFirst()
                .orElse(null);

        String targetNodeName = collectorNode == null ? null : collectorNode.getNodeName();
        String impactSummary = collectorNode == null
                ? "当前仅返回补证建议，不直接发起任务控制。"
                : firstNonBlank(collectorNode.getImpactSummary(), "建议从采集节点补充证据，但不会直接执行。");

        return attachDecisionSummary(ConversationResponse.TaskActionPreview.builder()
                .actionType("SUPPLEMENT_EVIDENCE")
                .taskId(taskId)
                .targetNodeName(targetNodeName)
                .title("补充证据预览")
                .actionSummary("当前仅提供补证建议与已有证据回指，不直接接管任务执行。")
                .impactSummary(impactSummary)
                .riskLevel("MEDIUM")
                .requiresConfirmation(true)
                .confirmationHint("统一入口会先展示补证建议与证据回指；确认后可直接在这里提交补源动作。")
                .executable(false)
                .sourceUrls(normalizeSourceUrls(sourceUrls))
                .build(), decisionView);
    }

    /**
     * 把确认对象翻译成统一执行计划，避免 ConversationService 直接散落字符串判断。
     * 这样后续新增动作类型时，只需要在翻译器里补齐映射规则即可。
     */
    public TaskActionExecutionPlan buildExecutionPlan(ConversationActionConfirmationRequest confirmationRequest,
                                                      Long taskId) {
        if (confirmationRequest == null) {
            return null;
        }
        String actionType = safe(confirmationRequest.getActionType()).toUpperCase(Locale.ROOT);
        String targetId = safe(confirmationRequest.getTargetId());
        if ("RERUN_NODE".equals(actionType)) {
            return TaskActionExecutionPlan.builder()
                    .actionType(actionType)
                    .taskId(taskId)
                    .targetNodeName(targetId)
                    .executionMessage("系统已提交从 " + targetId + " 开始重跑的执行请求。")
                    .build();
        }
        if ("SUPPLEMENT_EVIDENCE".equals(actionType)) {
            return TaskActionExecutionPlan.builder()
                    .actionType(actionType)
                    .taskId(taskId)
                    .targetNodeName(targetId)
                    .executionMessage("系统已提交补证动作，会从 " + targetId + " 重新组织后续采集链路。")
                    .build();
        }
        if ("RESUME_TASK".equals(actionType)) {
            return TaskActionExecutionPlan.builder()
                    .actionType(actionType)
                    .taskId(taskId)
                    .executionMessage("系统已提交任务恢复执行请求。")
                    .build();
        }
        return TaskActionExecutionPlan.builder()
                .actionType(actionType)
                .taskId(taskId)
                .targetNodeName(targetId)
                .executionMessage("系统暂未支持该确认动作的正式执行。")
                .build();
    }

    /**
     * 决策预览映射必须尽量薄，只消费既有 decision 字段。
     * 这里不重新推理规则，只做“决策类型/动作类型 -> 对话预览”的翻译，并在未命中时记录 debug 便于后续扩展排错。
     */
    private ConversationResponse.TaskActionPreview buildDecisionPreview(Long taskId,
                                                                        ConversationOrchestrationDecisionView rawDecision) {
        if (rawDecision == null) {
            return null;
        }
        ConversationOrchestrationDecisionView decision = rawDecision.normalized();
        String decisionType = safe(decision.getDecisionType()).toUpperCase(Locale.ROOT);
        String actionType = safe(decision.getActionType()).toUpperCase(Locale.ROOT);
        String targetNode = firstNonBlank(decision.getTargetNode(), "rewrite_report");
        String triggerNodeName = firstNonBlank(decision.getTriggerNodeName(), "unknown_node");
        String reason = firstNonBlank(decision.getReason(), "当前编排决策缺少明确原因说明。");
        List<String> sourceUrls = normalizeSourceUrls(decision.getSourceUrls());

        if ("WAIT_FOR_HUMAN".equals(decisionType) || "MANUAL_REVIEW".equals(actionType)) {
            return ConversationResponse.TaskActionPreview.builder()
                    .actionType("WAIT_FOR_HUMAN")
                    .taskId(taskId)
                    .targetNodeName(decision.getTargetNode())
                    .title("等待人工介入")
                    .actionSummary("来自 " + triggerNodeName + " 的编排决策要求人工处理，原因：" + reason)
                    .impactSummary("当前只展示决策摘要与来源，不生成可执行确认按钮。")
                    .riskLevel("HIGH")
                    .requiresConfirmation(false)
                    .confirmationHint("当前缺少足够来源或需要人工判断，请先处理人工介入项。")
                    .executable(false)
                    .orchestrationDecision(toDecisionSummary(decision))
                    .sourceUrls(sourceUrls)
                    .build();
        }
        if ("APPEND_DYNAMIC_BRANCH".equals(decisionType) || "SUPPLEMENT_EVIDENCE".equals(actionType)) {
            return ConversationResponse.TaskActionPreview.builder()
                    .actionType("SUPPLEMENT_EVIDENCE")
                    .taskId(taskId)
                    .targetNodeName(decision.getTargetNode())
                    .title("补充证据预览")
                    .actionSummary("来自 " + triggerNodeName + " 的编排决策建议先补充证据，原因：" + reason)
                    .impactSummary("会影响当前任务的证据链与后续分析/改写判断。")
                    .riskLevel("MEDIUM")
                    .requiresConfirmation(Boolean.TRUE.equals(decision.getRequiresConfirmation()))
                    .confirmationHint("统一入口会先展示补证建议与决策依据；确认后可继续进入补证执行。")
                    .executable(false)
                    .orchestrationDecision(toDecisionSummary(decision))
                    .sourceUrls(sourceUrls)
                    .build();
        }
        if ("REWRITE_ONLY".equals(decisionType)
                || "REWRITE_SECTION".equals(actionType)
                || "REWRITE_CLAIM".equals(actionType)) {
            return ConversationResponse.TaskActionPreview.builder()
                    .actionType("RERUN_NODE")
                    .taskId(taskId)
                    .targetNodeName(targetNode)
                    .title("重写报告预览")
                    .actionSummary("来自 " + triggerNodeName + " 的编排决策建议重写，原因：" + reason)
                    .impactSummary("会影响当前改写节点及其下游质量复核结果。")
                    .riskLevel("HIGH")
                    .requiresConfirmation(Boolean.TRUE.equals(decision.getRequiresConfirmation()))
                    .confirmationHint("统一入口会先展示重写影响范围；确认后可继续进入既有重跑入口。")
                    .executable(false)
                    .orchestrationDecision(toDecisionSummary(decision))
                    .sourceUrls(sourceUrls)
                    .build();
        }
        if ("NO_ACTION".equals(decisionType) || "NO_ACTION".equals(actionType)) {
            return null;
        }

        log.debug("unmatched orchestration decision preview, decisionType={}, actionType={}, decisionId={}",
                decisionType, actionType, decision.getDecisionId());
        return null;
    }

    private ConversationResponse.TaskActionPreview attachDecisionSummary(ConversationResponse.TaskActionPreview preview,
                                                                         ConversationOrchestrationDecisionView decisionView) {
        if (preview == null || decisionView == null) {
            return preview;
        }
        ConversationResponse.OrchestrationDecisionSummary summary = toDecisionSummary(decisionView.normalized());
        return ConversationResponse.TaskActionPreview.builder()
                .actionType(preview.getActionType())
                .taskId(preview.getTaskId())
                .targetNodeName(preview.getTargetNodeName())
                .title(preview.getTitle())
                .actionSummary(preview.getActionSummary())
                .impactSummary(preview.getImpactSummary())
                .riskLevel(preview.getRiskLevel())
                .requiresConfirmation(preview.getRequiresConfirmation())
                .confirmationHint(preview.getConfirmationHint())
                .executable(preview.getExecutable())
                .orchestrationDecision(summary)
                .sourceUrls(mergeSourceUrls(preview.getSourceUrls(), summary.getSourceUrls()))
                .build();
    }

    private ConversationResponse.OrchestrationDecisionSummary toDecisionSummary(ConversationOrchestrationDecisionView decision) {
        if (decision == null) {
            return null;
        }
        ConversationOrchestrationDecisionView normalized = decision.normalized();
        return ConversationResponse.OrchestrationDecisionSummary.builder()
                .decisionId(normalized.getDecisionId())
                .taskId(normalized.getTaskId())
                .triggerNodeName(normalized.getTriggerNodeName())
                .decisionType(normalized.getDecisionType())
                .actionType(normalized.getActionType())
                .targetNode(normalized.getTargetNode())
                .affectedScope(normalized.getAffectedScope())
                .reason(normalized.getReason())
                .requiresHumanIntervention(normalized.isRequiresHumanIntervention())
                .requiresConfirmation(normalized.getRequiresConfirmation())
                .evidenceState(normalized.getEvidenceState())
                .sourceUrls(normalizeSourceUrls(normalized.getSourceUrls()))
                .build();
    }

    private TaskNodeResponse matchTargetNode(String message, List<TaskNodeResponse> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        String normalizedMessage = safe(message).toLowerCase(Locale.ROOT);
        for (TaskNodeResponse node : nodes) {
            if (node == null) {
                continue;
            }
            String nodeName = safe(node.getNodeName()).toLowerCase(Locale.ROOT);
            String displayName = safe(node.getDisplayName()).toLowerCase(Locale.ROOT);
            if ((!nodeName.isBlank() && normalizedMessage.contains(nodeName))
                    || (!displayName.isBlank() && normalizedMessage.contains(displayName))) {
                return node;
            }
        }
        return nodes.stream()
                .filter(node -> node != null && node.getStatus() != null
                        && ("FAILED".equals(node.getStatus().name())
                        || "WAITING_INTERVENTION".equals(node.getStatus().name())
                        || "WAITING_RETRY".equals(node.getStatus().name())))
                .findFirst()
                .orElse(null);
    }

    private List<String> mergeSourceUrls(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return normalizeSourceUrls(merged);
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                String candidate = safe(sourceUrl);
                if (!candidate.isBlank()) {
                    normalized.add(candidate);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskActionExecutionPlan {
        private String actionType;
        private Long taskId;
        private String targetNodeName;
        private String executionMessage;
    }
}
