package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * LLM 响应解析结果。
 * 模型协议错误与被过滤来源分别记录，避免调用方把警告误判为整批失败。
 */
public record OrchestrationDecisionParseResult(
        List<OrchestrationDecision> decisions,
        List<ParseIssue> issues,
        List<DiscardedSourceUrl> discardedSourceUrls
) {

    public OrchestrationDecisionParseResult {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        issues = issues == null ? List.of() : List.copyOf(issues);
        discardedSourceUrls = discardedSourceUrls == null ? List.of() : List.copyOf(discardedSourceUrls);
        if (!issues.isEmpty() && !decisions.isEmpty()) {
            throw new IllegalArgumentException("解析失败时不能返回部分 decision");
        }
    }

    public static OrchestrationDecisionParseResult success(
            List<OrchestrationDecision> decisions,
            List<DiscardedSourceUrl> discardedSourceUrls) {
        return new OrchestrationDecisionParseResult(decisions, List.of(), discardedSourceUrls);
    }

    public static OrchestrationDecisionParseResult failure(
            List<ParseIssue> issues,
            List<DiscardedSourceUrl> discardedSourceUrls) {
        return new OrchestrationDecisionParseResult(List.of(), issues, discardedSourceUrls);
    }

    public boolean successful() {
        return issues.isEmpty();
    }

    /**
     * 稳定解析问题。decisionIndex 使用 JSON 数组的 0-based 下标，顶层问题为 null。
     */
    public record ParseIssue(Integer decisionIndex, String code, String fieldName) {
    }

    /**
     * 被来源红线过滤的 URL，不属于结构解析失败。
     */
    public record DiscardedSourceUrl(Integer decisionIndex, String sourceUrl, String code) {
    }
}
