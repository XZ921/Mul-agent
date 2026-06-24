package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排决策 trace 服务。
 * P1 第一版复用 workflow outbox 作为可回放审计载体，不新增独立 trace 表。
 */
@Service
@RequiredArgsConstructor
public class OrchestrationTraceService {

    private final WorkflowEventPublisher workflowEventPublisher;
    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 记录一次编排决策、策略结果与计划变更。
     * 决策 trace 只表达“系统为什么要补图”，不承担真实补图执行职责。
     */
    public void recordDecision(Long taskId,
                               TaskNode completedNode,
                               OrchestrationDecision decision,
                               DecisionPolicyResult policyResult,
                               DynamicPlanMutation mutation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator 已生成运行期编排决策");
        payload.put("decision", decision);
        payload.put("policyResult", policyResult);
        payload.put("mutation", mutation);
        payload.put("evidenceState", decision == null ? null : decision.getEvidenceState());
        workflowEventPublisher.publishOrchestrationEvent(
                taskId,
                completedNode == null ? null : completedNode.getNodeName(),
                completedNode == null ? null : completedNode.getPlanVersionId(),
                completedNode == null ? null : completedNode.getBranchKey(),
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED,
                payload,
                decision == null ? List.of() : decision.getSourceUrls());
    }

    /**
     * 记录 Orchestrator 的恢复游标。
     * decisionCount 必须基于最近 checkpoint 递增，避免自动补图循环保护被固定值绕开。
     */
    public void recordCheckpoint(Long taskId,
                                 TaskNode completedNode,
                                 TaskPlan derivedPlan,
                                 OrchestrationDecision decision,
                                 DynamicPlanMutation mutation,
                                 DecisionPolicyRuleSet ruleSet) {
        OrchestratorCheckpoint checkpoint = OrchestratorCheckpoint.builder()
                .checkpointId("oc-" + (decision == null ? "unknown" : decision.getDecisionId()))
                .taskId(taskId)
                .planVersionId(derivedPlan == null ? null : derivedPlan.getId())
                .branchKey(derivedPlan == null ? null : derivedPlan.getBranchKey())
                .lastDecisionId(decision == null ? null : decision.getDecisionId())
                .lastMutationId(mutation == null ? null : mutation.getMutationId())
                .pendingActions(List.of("WAITING_FOR_SUPPLEMENT_RESULT"))
                .decisionCount(resolveNextDecisionCount(taskId))
                .maxAutoDecisions(ruleSet == null ? 2 : ruleSet.getMaxAutoDecisions())
                .resumeAfterNodeName(mutation == null ? null : mutation.getExpectedResumeNodeName())
                .resumeReason("动态补图节点完成后需要继续复核质量诊断是否收敛。")
                .sourceUrls(decision == null ? List.of() : decision.getSourceUrls())
                .evidenceState(decision == null ? EvidenceState.MISSING_SOURCE : decision.getEvidenceState())
                .build()
                .normalized();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator checkpoint 已更新");
        payload.put("checkpoint", checkpoint);
        workflowEventPublisher.publishOrchestrationEvent(
                taskId,
                completedNode == null ? null : completedNode.getNodeName(),
                derivedPlan == null ? null : derivedPlan.getId(),
                derivedPlan == null ? null : derivedPlan.getBranchKey(),
                WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED,
                payload,
                checkpoint.getSourceUrls());
    }

    private int resolveNextDecisionCount(Long taskId) {
        if (taskId == null) {
            return 1;
        }
        return taskWorkflowEventRepository
                .findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                        taskId,
                        WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .map(this::extractDecisionCount)
                .orElse(0) + 1;
    }

    /**
     * checkpoint 当前复用事件 payload 持久化，读取失败时按 0 处理，
     * 避免历史脏事件阻断新的 trace 写入。
     */
    private int extractDecisionCount(TaskWorkflowEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return 0;
        }
        try {
            JsonNode countNode = objectMapper.readTree(event.getPayload())
                    .path("checkpoint")
                    .path("decisionCount");
            return Math.max(0, countNode.asInt(0));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
