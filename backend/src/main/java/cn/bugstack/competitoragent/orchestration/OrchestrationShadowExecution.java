package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * 一次 shadow 尝试的不可变执行事实。
 * 这里只保存稳定状态、类型化失败和来源，不保存原始 Prompt 或模型响应。
 */
public record OrchestrationShadowExecution(
        boolean requested,
        boolean executed,
        String skippedReason,
        LlmOrchestratorDecisionFailure failure,
        List<String> sourceUrls
) {

    public OrchestrationShadowExecution {
        skippedReason = normalizeText(skippedReason);
        sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        if (!requested && executed) {
            throw new IllegalArgumentException("未请求 shadow 时不能标记为已执行");
        }
        if (executed && skippedReason != null) {
            throw new IllegalArgumentException("shadow 已执行时不能同时记录跳过原因");
        }
    }

    public static OrchestrationShadowExecution notRequested(List<String> sourceUrls) {
        return new OrchestrationShadowExecution(false, false, null, null, sourceUrls);
    }

    public static OrchestrationShadowExecution skipped(String reason,
                                                        LlmOrchestratorDecisionFailure failure,
                                                        List<String> sourceUrls) {
        return new OrchestrationShadowExecution(true, false, reason, failure, sourceUrls);
    }

    public static OrchestrationShadowExecution executed(LlmOrchestratorDecisionFailure failure,
                                                         List<String> sourceUrls) {
        return new OrchestrationShadowExecution(true, true, null, failure, sourceUrls);
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
