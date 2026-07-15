package cn.bugstack.competitoragent.orchestration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个 Orchestrator 决策周期的 V2 持久化审计快照。
 * 该类型只保存可回放事实，不保存 raw prompt/response、异常消息或可执行节点模板。
 */
public record OrchestrationDecisionAuditTrace(
        String traceSchemaVersion,
        OrchestratorDecisionMode mode,
        List<OrchestrationDecision> coordinatorDecisions,
        List<OrchestrationRuntimeDecisionTrace> attempts,
        List<String> finalDecisionIds,
        boolean policyFallbackUsed,
        RuntimeStateTrace runtimeState,
        ShadowExecutionTrace shadowExecution,
        List<OrchestrationDecision> shadowDecisions,
        FailureTrace llmFailure,
        List<String> sourceUrls
) {

    public static final String SCHEMA_VERSION = "ORCHESTRATION_TRACE_V2";

    public OrchestrationDecisionAuditTrace {
        traceSchemaVersion = normalizeText(traceSchemaVersion);
        if (!SCHEMA_VERSION.equals(traceSchemaVersion)) {
            throw new IllegalArgumentException("traceSchemaVersion 必须为 " + SCHEMA_VERSION);
        }
        if (mode == null || runtimeState == null || shadowExecution == null) {
            throw new IllegalArgumentException("mode、runtimeState、shadowExecution 不能为空");
        }
        coordinatorDecisions = normalizeDecisions(coordinatorDecisions, false);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (attempts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("attempts 不能包含 null");
        }
        finalDecisionIds = normalizeTexts(finalDecisionIds);
        shadowDecisions = normalizeDecisions(shadowDecisions, true);
        sourceUrls = mergeAuditSourceUrls(
                sourceUrls,
                coordinatorDecisions,
                attempts,
                runtimeState,
                shadowExecution,
                shadowDecisions,
                llmFailure);
    }

    /** 决策列表统一归一化，并在 shadow 列表上执行来源防串校验。 */
    private static List<OrchestrationDecision> normalizeDecisions(List<OrchestrationDecision> values,
                                                                  boolean shadowOnly) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<OrchestrationDecision> normalized = new ArrayList<>();
        for (OrchestrationDecision value : values) {
            if (value == null) {
                throw new IllegalArgumentException("decision 列表不能包含 null");
            }
            OrchestrationDecision decision = value.normalized();
            if (shadowOnly && decision.getDecisionOrigin() != OrchestrationDecisionOrigin.LLM_SHADOW) {
                throw new IllegalArgumentException("shadowDecisions 只能包含 LLM_SHADOW");
            }
            if (!shadowOnly && decision.getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW) {
                throw new IllegalArgumentException("coordinatorDecisions 不能包含 LLM_SHADOW");
            }
            normalized.add(decision);
        }
        return List.copyOf(normalized);
    }

    private static List<String> mergeAuditSourceUrls(List<String> explicit,
                                                     List<OrchestrationDecision> decisions,
                                                     List<OrchestrationRuntimeDecisionTrace> attempts,
                                                     RuntimeStateTrace runtimeState,
                                                     ShadowExecutionTrace shadowExecution,
                                                     List<OrchestrationDecision> shadowDecisions,
                                                     FailureTrace failure) {
        List<List<String>> groups = new ArrayList<>();
        groups.add(explicit);
        decisions.forEach(item -> groups.add(item.getSourceUrls()));
        attempts.forEach(item -> groups.add(item.sourceUrls()));
        groups.add(runtimeState.sourceUrls());
        groups.add(shadowExecution.sourceUrls());
        shadowDecisions.forEach(item -> groups.add(item.getSourceUrls()));
        if (failure != null) {
            groups.add(failure.sourceUrls());
        }
        return mergeSourceUrls(groups.toArray(List[]::new));
    }

    static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static List<String> normalizeTexts(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String candidate = normalizeText(value);
                if (candidate != null) {
                    normalized.add(candidate);
                }
            }
        }
        return List.copyOf(normalized);
    }

    @SafeVarargs
    static List<String> mergeSourceUrls(List<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        if (groups != null) {
            for (List<String> group : groups) {
                if (group == null) {
                    continue;
                }
                for (String value : group) {
                    String candidate = normalizeText(value);
                    if (candidate != null) {
                        merged.add(candidate);
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    /** 决策开始前已经恢复出的持久化运行时状态。 */
    public record RuntimeStateTrace(
            int currentDecisionCount,
            Map<String, Integer> dynamicBranchCountsBySection,
            Long currentPlanVersionId,
            int nextPlanVersion,
            OrchestrationRuntimeState.CheckpointStateStatus checkpointStateStatus,
            List<String> sourceUrls
    ) {
        public RuntimeStateTrace {
            currentDecisionCount = Math.max(0, currentDecisionCount);
            nextPlanVersion = Math.max(1, nextPlanVersion);
            if (checkpointStateStatus == null) {
                throw new IllegalArgumentException("checkpointStateStatus 不能为空");
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (dynamicBranchCountsBySection != null) {
                dynamicBranchCountsBySection.forEach((key, value) -> {
                    String normalizedKey = normalizeText(key);
                    if (normalizedKey != null && value != null) {
                        counts.put(normalizedKey, Math.max(0, value));
                    }
                });
            }
            dynamicBranchCountsBySection = Collections.unmodifiableMap(counts);
            sourceUrls = mergeSourceUrls(sourceUrls);
        }
    }

    /** Shadow 是否请求、是否执行以及失败/跳过原因的完整安全快照。 */
    public record ShadowExecutionTrace(
            boolean requested,
            boolean executed,
            String skippedReason,
            FailureTrace failure,
            List<String> sourceUrls
    ) {
        public ShadowExecutionTrace {
            skippedReason = normalizeText(skippedReason);
            sourceUrls = mergeSourceUrls(sourceUrls, failure == null ? List.of() : failure.sourceUrls());
            if (!requested && executed) {
                throw new IllegalArgumentException("未请求 shadow 时不能标记为已执行");
            }
            if (executed && skippedReason != null) {
                throw new IllegalArgumentException("shadow 已执行时不能同时记录 skippedReason");
            }
        }
    }

    /** LLM typed failure 的安全读写形状，显式携带已验证来源。 */
    public record FailureTrace(
            LlmOrchestratorFailureType type,
            String providerErrorCode,
            int parseRetryCount,
            List<FailureAttemptTrace> attempts,
            List<String> sourceUrls
    ) {
        public FailureTrace {
            if (type == null || parseRetryCount < 0) {
                throw new IllegalArgumentException("failure type 不能为空且 parseRetryCount 不能为负数");
            }
            providerErrorCode = normalizeText(providerErrorCode);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            if (attempts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("failure attempts 不能包含 null");
            }
            sourceUrls = mergeSourceUrls(sourceUrls);
        }
    }

    /** 单次模型调用只保存 hash、解析问题和被拒绝 URL，不保存原始文本。 */
    public record FailureAttemptTrace(
            int attemptNumber,
            String promptHash,
            String llmResponseHash,
            List<OrchestrationDecisionParseResult.ParseIssue> issues,
            List<OrchestrationDecisionParseResult.DiscardedSourceUrl> discardedSourceUrls,
            List<String> sourceUrls
    ) {
        public FailureAttemptTrace {
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber 必须从 1 开始");
            }
            promptHash = normalizeText(promptHash);
            llmResponseHash = normalizeText(llmResponseHash);
            issues = issues == null ? List.of() : List.copyOf(issues);
            discardedSourceUrls = discardedSourceUrls == null ? List.of() : List.copyOf(discardedSourceUrls);
            sourceUrls = mergeSourceUrls(sourceUrls);
        }
    }
}
