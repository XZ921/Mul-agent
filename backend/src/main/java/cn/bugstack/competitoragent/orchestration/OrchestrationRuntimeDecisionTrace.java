package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * 一条 runtime attempt 的持久化审计事实。
 * 保留 decision/policy 和 mutation 窄投影，不承担任何重新决策或执行职责。
 */
public record OrchestrationRuntimeDecisionTrace(
        OrchestrationDecision decision,
        DecisionPolicyResult policyResult,
        OrchestrationMutationTrace mutationSummary,
        boolean fallbackAttempt,
        String runtimeStatus,
        List<String> sourceUrls
) {

    public OrchestrationRuntimeDecisionTrace {
        if (decision == null || policyResult == null || mutationSummary == null) {
            throw new IllegalArgumentException("runtime trace 的 decision、policyResult、mutationSummary 不能为空");
        }
        decision = decision.normalized();
        policyResult = policyResult.normalized();
        runtimeStatus = OrchestrationDecisionAuditTrace.normalizeText(runtimeStatus);
        if (runtimeStatus == null) {
            throw new IllegalArgumentException("runtimeStatus 不能为空");
        }
        sourceUrls = OrchestrationDecisionAuditTrace.mergeSourceUrls(
                sourceUrls,
                decision.getSourceUrls(),
                policyResult.getSourceUrls(),
                mutationSummary.sourceUrls());
    }
}
