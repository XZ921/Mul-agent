package cn.bugstack.competitoragent.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime Coordinator 一次决策周期的不可变输出。
 * attempts 用于审计全部评估，finalDecisions 是调用方唯一允许消费的结果集合。
 */
public record OrchestrationRuntimeDecisionBatch(
        OrchestrationDecisionOutcome coordinatorOutcome,
        OrchestrationRuntimeState runtimeState,
        List<OrchestrationRuntimeDecision> attempts,
        List<OrchestrationRuntimeDecision> finalDecisions,
        boolean policyFallbackUsed,
        List<String> sourceUrls
) {

    public OrchestrationRuntimeDecisionBatch {
        if (coordinatorOutcome == null || runtimeState == null) {
            throw new IllegalArgumentException("coordinatorOutcome 与 runtimeState 不能为空");
        }
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        finalDecisions = finalDecisions == null ? List.of() : List.copyOf(finalDecisions);

        for (OrchestrationRuntimeDecision attempt : attempts) {
            requireRuntimeDecision(attempt, "attempts");
        }
        for (OrchestrationRuntimeDecision result : finalDecisions) {
            requireRuntimeDecision(result, "finalDecisions");
            // final 只能引用已评估 attempt 的值相等结果，防止 caller 绕过 Policy 临时构造 mutation。
            if (!attempts.contains(result)) {
                throw new IllegalArgumentException("finalDecisions 必须来自 attempts");
            }
        }
        sourceUrls = mergeSourceUrls(sourceUrls, coordinatorOutcome, runtimeState, attempts, finalDecisions);
    }

    private static void requireRuntimeDecision(OrchestrationRuntimeDecision result, String owner) {
        if (result == null) {
            throw new IllegalArgumentException(owner + " 不能包含 null");
        }
        // shadow 候选只保留在 coordinatorOutcome 中，永远不能进入 Policy/Executor 事实列表。
        if (result.decision().getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW) {
            throw new IllegalArgumentException(owner + " 不能包含 LLM_SHADOW");
        }
    }

    private static List<String> mergeSourceUrls(List<String> explicit,
                                                OrchestrationDecisionOutcome outcome,
                                                OrchestrationRuntimeState state,
                                                List<OrchestrationRuntimeDecision> attempts,
                                                List<OrchestrationRuntimeDecision> finalDecisions) {
        Set<String> merged = new LinkedHashSet<>();
        addSourceUrls(merged, explicit);
        addSourceUrls(merged, outcome.sourceUrls());
        addSourceUrls(merged, state.sourceUrls());
        for (OrchestrationRuntimeDecision attempt : attempts) {
            addSourceUrls(merged, attempt.sourceUrls());
        }
        for (OrchestrationRuntimeDecision result : finalDecisions) {
            addSourceUrls(merged, result.sourceUrls());
        }
        return List.copyOf(merged);
    }

    private static void addSourceUrls(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value.trim());
            }
        }
    }
}
