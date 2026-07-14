package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
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
    private final OrchestrationRuntimeStateService runtimeStateService;
    private final DecisionPolicyRuleSet ruleSet;

    /**
     * 记录一次编排决策、策略结果与计划变更。
     * 决策 trace 只表达“系统为什么要补图”，不承担真实补图执行职责。
     */
    public void recordDecision(Long taskId,
                               TaskNode completedNode,
                               OrchestrationDecision decision,
                               DecisionPolicyResult policyResult,
                               DynamicPlanMutation mutation) {
        // Trace 是决策事实的持久化边界，必须在这里统一归一化，不能依赖每个调用方自行补齐来源和元数据。
        OrchestrationDecision normalizedDecision = decision == null ? null : decision.normalized();
        // 旧调用方可能尚未给 policy result 写 origin；同一事件中的 policy 必须继承对应 decision 的来源，避免审计事实分叉。
        DecisionPolicyResult normalizedPolicyResult = policyResult == null
                ? null
                : policyResult.toBuilder()
                .decisionOrigin(normalizedDecision == null
                        ? policyResult.getDecisionOrigin()
                        : normalizedDecision.getDecisionOrigin())
                .build()
                .normalized();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator 已生成运行期编排决策");
        payload.put("decision", normalizedDecision);
        payload.put("policyResult", normalizedPolicyResult);
        payload.put("mutation", mutation);
        payload.put("evidenceState", normalizedDecision == null ? null : normalizedDecision.getEvidenceState());
        workflowEventPublisher.publishOrchestrationEvent(
                taskId,
                completedNode == null ? null : completedNode.getNodeName(),
                completedNode == null ? null : completedNode.getPlanVersionId(),
                completedNode == null ? null : completedNode.getBranchKey(),
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED,
                payload,
                normalizedDecision == null ? List.of() : normalizedDecision.getSourceUrls());
    }

    /**
     * 记录 Orchestrator 的恢复游标。
     * decisionCount 必须基于最近 checkpoint 递增，避免自动补图循环保护被固定值绕开。
     */
    public void recordCheckpoint(Long taskId,
                                 TaskNode completedNode,
                                 TaskPlan derivedPlan,
                                 OrchestrationDecision decision,
                                 DynamicPlanMutation mutation) {
        // 只有动态计划已经成功落库后调用该入口，因此计数递增统一委托给 Runtime State owner。
        // Trace 不再自行解析事件 JSON，避免损坏 checkpoint 被错误当成 count=0 重新开放额度。
        OrchestrationRuntimeState nextState = runtimeStateService.afterSuccessfulBranch(
                runtimeStateService.load(taskId),
                decision);
        OrchestratorCheckpoint checkpoint = OrchestratorCheckpoint.builder()
                .checkpointId("oc-" + (decision == null ? "unknown" : decision.getDecisionId()))
                .taskId(taskId)
                .planVersionId(derivedPlan == null ? null : derivedPlan.getId())
                .branchKey(derivedPlan == null ? null : derivedPlan.getBranchKey())
                .lastDecisionId(decision == null ? null : decision.getDecisionId())
                .lastMutationId(mutation == null ? null : mutation.getMutationId())
                .pendingActions(List.of("WAITING_FOR_SUPPLEMENT_RESULT"))
                .decisionCount(nextState.currentDecisionCount())
                .maxAutoDecisions(ruleSet.getMaxAutoDecisions())
                .dynamicBranchCountsBySection(nextState.dynamicBranchCountsBySection())
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
}
