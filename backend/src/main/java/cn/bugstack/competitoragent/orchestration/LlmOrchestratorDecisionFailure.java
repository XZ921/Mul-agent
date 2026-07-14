package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * Coordinator 可消费的 LLM 决策失败事实。
 * 只保留不可逆指纹与结构化解析问题，不保存 raw prompt/response。
 */
public record LlmOrchestratorDecisionFailure(
        LlmOrchestratorFailureType type,
        String providerErrorCode,
        int parseRetryCount,
        List<Attempt> attempts
) {

    public LlmOrchestratorDecisionFailure {
        if (type == null) {
            throw new IllegalArgumentException("failure type 不能为空");
        }
        if (parseRetryCount < 0) {
            throw new IllegalArgumentException("parseRetryCount 不能为负数");
        }
        providerErrorCode = normalizeText(providerErrorCode);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 一次真实模型调用的最小审计事实。attemptNumber 使用 1-based 编号。
     */
    public record Attempt(
            int attemptNumber,
            String promptHash,
            String llmResponseHash,
            List<OrchestrationDecisionParseResult.ParseIssue> issues,
            List<OrchestrationDecisionParseResult.DiscardedSourceUrl> discardedSourceUrls
    ) {

        public Attempt {
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber 必须从 1 开始");
            }
            promptHash = normalizeText(promptHash);
            llmResponseHash = normalizeText(llmResponseHash);
            issues = issues == null ? List.of() : List.copyOf(issues);
            discardedSourceUrls = discardedSourceUrls == null ? List.of() : List.copyOf(discardedSourceUrls);
        }
    }
}
