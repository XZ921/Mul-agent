package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 单个 Orchestrator 决策周期的稳定只读摘要。
 * 该 DTO 只承载已持久化审计事实，不暴露可执行 runtime domain object。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Orchestrator 决策周期审计摘要")
public class OrchestrationDecisionAuditSummary {

    private String traceSchemaVersion;
    private String mode;
    private OrchestrationDecisionSummary representativeDecision;
    @Builder.Default
    private List<OrchestrationDecisionSummary> coordinatorDecisions = List.of();
    @Builder.Default
    private List<OrchestrationDecisionSummary> attempts = List.of();
    @Builder.Default
    private List<String> finalDecisionIds = List.of();
    private boolean policyFallbackUsed;
    private RuntimeStateSummary runtimeState;
    private ShadowExecutionSummary shadowExecution;
    @Builder.Default
    private List<OrchestrationDecisionSummary> shadowDecisions = List.of();
    private FailureSummary llmFailure;
    @Builder.Default
    private List<String> sourceUrls = List.of();

    public OrchestrationDecisionAuditSummary normalized() {
        return toBuilder()
                .traceSchemaVersion(blankToNull(traceSchemaVersion))
                .mode(upperOrNull(mode))
                .representativeDecision(representativeDecision == null
                        ? null : representativeDecision.normalized())
                .coordinatorDecisions(normalizeDecisions(coordinatorDecisions))
                .attempts(normalizeDecisions(attempts))
                .finalDecisionIds(normalizeTexts(finalDecisionIds))
                .runtimeState(runtimeState == null ? null : runtimeState.normalized())
                .shadowExecution(shadowExecution == null ? null : shadowExecution.normalized())
                .shadowDecisions(normalizeDecisions(shadowDecisions))
                .llmFailure(llmFailure == null ? null : llmFailure.normalized())
                .sourceUrls(normalizeTexts(sourceUrls))
                .build();
    }

    private List<OrchestrationDecisionSummary> normalizeDecisions(
            List<OrchestrationDecisionSummary> values) {
        if (values == null) {
            return List.of();
        }
        List<OrchestrationDecisionSummary> normalized = new ArrayList<>();
        for (OrchestrationDecisionSummary value : values) {
            if (value != null) {
                normalized.add(value.normalized());
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeTexts(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String candidate = blankToNull(value);
                if (candidate != null) {
                    normalized.add(candidate);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static String upperOrNull(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuntimeStateSummary {
        private Integer currentDecisionCount;
        @Builder.Default
        private Map<String, Integer> dynamicBranchCountsBySection = Map.of();
        private Long currentPlanVersionId;
        private Integer nextPlanVersion;
        private String checkpointStateStatus;
        @Builder.Default
        private List<String> sourceUrls = List.of();

        public RuntimeStateSummary normalized() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (dynamicBranchCountsBySection != null) {
                dynamicBranchCountsBySection.forEach((key, value) -> {
                    String normalizedKey = blankToNull(key);
                    if (normalizedKey != null && value != null) {
                        counts.put(normalizedKey, Math.max(0, value));
                    }
                });
            }
            return toBuilder()
                    .currentDecisionCount(currentDecisionCount == null ? null : Math.max(0, currentDecisionCount))
                    .dynamicBranchCountsBySection(Map.copyOf(counts))
                    .nextPlanVersion(nextPlanVersion == null ? null : Math.max(1, nextPlanVersion))
                    .checkpointStateStatus(upperOrNull(checkpointStateStatus))
                    .sourceUrls(normalizeTexts(sourceUrls))
                    .build();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShadowExecutionSummary {
        private boolean requested;
        private boolean executed;
        private String skippedReason;
        private FailureSummary failure;
        @Builder.Default
        private List<String> sourceUrls = List.of();

        public ShadowExecutionSummary normalized() {
            return toBuilder()
                    .skippedReason(blankToNull(skippedReason))
                    .failure(failure == null ? null : failure.normalized())
                    .sourceUrls(normalizeTexts(sourceUrls))
                    .build();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureSummary {
        private String type;
        private String providerErrorCode;
        private Integer parseRetryCount;
        @Builder.Default
        private List<FailureAttemptSummary> attempts = List.of();
        @Builder.Default
        private List<String> sourceUrls = List.of();

        public FailureSummary normalized() {
            List<FailureAttemptSummary> normalizedAttempts = new ArrayList<>();
            if (attempts != null) {
                for (FailureAttemptSummary attempt : attempts) {
                    if (attempt != null) {
                        normalizedAttempts.add(attempt.normalized());
                    }
                }
            }
            return toBuilder()
                    .type(upperOrNull(type))
                    .providerErrorCode(blankToNull(providerErrorCode))
                    .parseRetryCount(parseRetryCount == null ? null : Math.max(0, parseRetryCount))
                    .attempts(List.copyOf(normalizedAttempts))
                    .sourceUrls(normalizeTexts(sourceUrls))
                    .build();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureAttemptSummary {
        private Integer attemptNumber;
        private String promptHash;
        private String llmResponseHash;
        @Builder.Default
        private List<ParseIssueSummary> issues = List.of();
        @Builder.Default
        private List<DiscardedSourceSummary> discardedSourceUrls = List.of();
        @Builder.Default
        private List<String> sourceUrls = List.of();

        public FailureAttemptSummary normalized() {
            return toBuilder()
                    .attemptNumber(attemptNumber == null ? null : Math.max(1, attemptNumber))
                    .promptHash(blankToNull(promptHash))
                    .llmResponseHash(blankToNull(llmResponseHash))
                    .issues(issues == null ? List.of() : List.copyOf(issues))
                    .discardedSourceUrls(discardedSourceUrls == null
                            ? List.of() : List.copyOf(discardedSourceUrls))
                    .sourceUrls(normalizeTexts(sourceUrls))
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseIssueSummary {
        private Integer decisionIndex;
        private String code;
        private String fieldName;
        @Builder.Default
        private List<String> sourceUrls = List.of();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscardedSourceSummary {
        private Integer decisionIndex;
        private String sourceUrl;
        private String code;
        @Builder.Default
        private List<String> sourceUrls = List.of();
    }
}
