package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Orchestrator 决策审计元数据。
 * 该对象只描述模型调用、解析、shadow 和 fallback 事实，不承载诊断或 Suggestion 输入引用。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorDecisionMetadata {

    private String modelName;
    private Double temperature;
    private String promptHash;
    private String llmResponseHash;
    /** 关联同一 Orchestrator cycle 在 AI 调用审计表中的一组 Provider attempts。 */
    private String aiAuditTraceId;
    @Builder.Default
    private Integer parseRetryCount = 0;
    private boolean fallbackUsed;
    private String fallbackReason;
    private Boolean shadowExecuted;
    private String shadowSkippedReason;

    public static OrchestratorDecisionMetadata empty() {
        return OrchestratorDecisionMetadata.builder().build();
    }

    /**
     * 统一清洗审计字段并修复互相矛盾的状态。
     * RULE_FALLBACK 或显式 fallbackReason 都意味着已经发生回退；shadow 被跳过时不能同时标记为已执行。
     */
    public OrchestratorDecisionMetadata normalized(OrchestrationDecisionOrigin rawOrigin) {
        OrchestrationDecisionOrigin origin = rawOrigin == null
                ? OrchestrationDecisionOrigin.defaultOrigin()
                : rawOrigin;
        String normalizedFallbackReason = blankToNull(fallbackReason);
        String normalizedShadowSkippedReason = blankToNull(shadowSkippedReason);
        boolean normalizedFallbackUsed = fallbackUsed
                || origin == OrchestrationDecisionOrigin.RULE_FALLBACK
                || normalizedFallbackReason != null;
        Boolean normalizedShadowExecuted = normalizedShadowSkippedReason == null
                ? shadowExecuted
                : Boolean.FALSE;
        return toBuilder()
                .modelName(blankToNull(modelName))
                .temperature(normalizeTemperature(temperature))
                .promptHash(blankToNull(promptHash))
                .llmResponseHash(blankToNull(llmResponseHash))
                .aiAuditTraceId(blankToNull(aiAuditTraceId))
                .parseRetryCount(Math.max(0, parseRetryCount == null ? 0 : parseRetryCount))
                .fallbackUsed(normalizedFallbackUsed)
                .fallbackReason(normalizedFallbackReason)
                .shadowExecuted(normalizedShadowExecuted)
                .shadowSkippedReason(normalizedShadowSkippedReason)
                .build();
    }

    private Double normalizeTemperature(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return Math.max(0.0d, value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
