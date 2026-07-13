package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * LLM 可写的最小响应 DTO。
 * 服务端拥有的 task、origin、metadata、inputRefs 和 evidenceState 不允许出现在该模型中。
 */
public record OrchestrationDecisionResponse(
        List<DecisionCandidate> decisions
) {

    public OrchestrationDecisionResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * 单条模型候选，只承载模型可以建议的业务字段。
     */
    public record DecisionCandidate(
            String decisionType,
            String actionType,
            String targetNode,
            String targetSection,
            String affectedScope,
            String priority,
            String reason,
            Double confidence,
            Boolean requiresHumanIntervention,
            Boolean requiresConfirmation,
            List<String> sourceUrls,
            List<String> suggestedQueries
    ) {
        public DecisionCandidate {
            sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
            suggestedQueries = suggestedQueries == null ? List.of() : List.copyOf(suggestedQueries);
        }
    }
}
