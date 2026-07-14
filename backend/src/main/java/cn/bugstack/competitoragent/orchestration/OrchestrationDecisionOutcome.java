package cn.bugstack.competitoragent.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Coordinator 的不可变输出，同时承载主结果、shadow 对比结果和原始 LLM 失败事实。
 */
public record OrchestrationDecisionOutcome(
        OrchestratorDecisionMode mode,
        List<OrchestrationDecision> decisions,
        List<OrchestrationDecision> shadowDecisions,
        OrchestrationShadowExecution shadowExecution,
        LlmOrchestratorDecisionFailure llmFailure,
        List<String> sourceUrls
) {

    public OrchestrationDecisionOutcome {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        shadowDecisions = shadowDecisions == null ? List.of() : List.copyOf(shadowDecisions);
        shadowExecution = shadowExecution == null
                ? OrchestrationShadowExecution.notRequested(List.of())
                : shadowExecution;

        // 主列表永远不能混入 shadow 候选，否则 Task 07 可能把只读对比结果误送入执行链。
        for (OrchestrationDecision decision : decisions) {
            requireDecision(decision, "decisions");
            if (decision.getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW) {
                throw new IllegalArgumentException("主 decisions 不能包含 LLM_SHADOW");
            }
        }
        for (OrchestrationDecision decision : shadowDecisions) {
            requireDecision(decision, "shadowDecisions");
            if (decision.getDecisionOrigin() != OrchestrationDecisionOrigin.LLM_SHADOW) {
                throw new IllegalArgumentException("shadowDecisions 只能包含 LLM_SHADOW");
            }
        }
        if (mode == OrchestratorDecisionMode.RULE_ONLY && !shadowDecisions.isEmpty()) {
            throw new IllegalArgumentException("RULE_ONLY 不能携带 shadow decisions");
        }
        sourceUrls = mergeSourceUrls(sourceUrls, decisions, shadowDecisions, shadowExecution.sourceUrls());
    }

    private static void requireDecision(OrchestrationDecision decision, String owner) {
        if (decision == null) {
            throw new IllegalArgumentException(owner + " 不能包含 null decision");
        }
    }

    /**
     * Outcome 自己完成来源去重，保证调用方即使只传上下文来源，也不会遗漏 decision/shadow 中的证据链接。
     */
    private static List<String> mergeSourceUrls(List<String> explicitSourceUrls,
                                                List<OrchestrationDecision> decisions,
                                                List<OrchestrationDecision> shadowDecisions,
                                                List<String> shadowSourceUrls) {
        Set<String> merged = new LinkedHashSet<>();
        addSourceUrls(merged, explicitSourceUrls);
        for (OrchestrationDecision decision : decisions) {
            addSourceUrls(merged, decision.getSourceUrls());
        }
        for (OrchestrationDecision decision : shadowDecisions) {
            addSourceUrls(merged, decision.getSourceUrls());
        }
        addSourceUrls(merged, shadowSourceUrls);
        return List.copyOf(new ArrayList<>(merged));
    }

    private static void addSourceUrls(Set<String> target, List<String> sourceUrls) {
        if (sourceUrls == null) {
            return;
        }
        for (String sourceUrl : sourceUrls) {
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                target.add(sourceUrl.trim());
            }
        }
    }
}
