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

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任务动作翻译器。
 * 它只负责把自然语言动作翻成“预览语义”，不直接执行任何任务控制。
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
        // Prompt 预览只用于调试和审计，不能因为模板返回 null 就中断动作预览主流程。
        String promptPreview = safe(promptTemplateService.render("task-action-translator", Map.of(
                "userMessage", safe(message),
                "actionContext", "taskId=" + taskId + ", taskStatus=" + (task == null ? null : task.getStatus())
        )));
        log.debug("task action translator prompt prepared, length={}", promptPreview.length());

        TaskNodeResponse targetNode = matchTargetNode(message, nodes);
        if (targetNode != null) {
            return ConversationResponse.TaskActionPreview.builder()
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
                    .build();
        }

        return ConversationResponse.TaskActionPreview.builder()
                .actionType("RESUME_TASK")
                .taskId(taskId)
                .title("恢复任务执行")
                .actionSummary(task == null ? "系统会尝试基于已有检查点恢复任务。" : firstNonBlank(task.getResumeAdvice(), "系统会尝试基于已有检查点恢复任务。"))
                .impactSummary("会保留已完成节点成果，只恢复中断或待继续的链路。")
                .riskLevel("HIGH")
                .requiresConfirmation(true)
                .confirmationHint("统一入口会先展示恢复影响范围；确认后可直接在这里提交恢复执行。")
                .executable(false)
                .build();
    }

    public ConversationResponse.TaskActionPreview buildResearchPreview(String message,
                                                                       Long taskId,
                                                                       List<TaskNodeResponse> nodes,
                                                                       List<String> sourceUrls) {
        // 研究预览与动作预览同理，模板预览为空时应降级为空串日志，而不是抛出空指针。
        String promptPreview = safe(promptTemplateService.render("task-action-translator", Map.of(
                "userMessage", safe(message),
                "actionContext", "research-taskId=" + taskId
        )));
        log.debug("research preview prompt prepared, length={}", promptPreview.length());

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

        return ConversationResponse.TaskActionPreview.builder()
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
                .sourceUrls(sourceUrls)
                .build();
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
