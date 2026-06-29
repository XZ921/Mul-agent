package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 决策策略校验结果。
 * 只有 allowed=true 的结果才能被 DecisionExecutorAdapter 翻译为动态计划变更。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionPolicyResult {

    private String decisionId;
    private boolean allowed;
    private String riskLevel;
    private boolean requiresConfirmation;
    @Builder.Default
    private List<String> blockedReasons = List.of();
    private String normalizedAction;
    @Builder.Default
    private List<String> policyRuleRefs = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String policyVersion;
    private String preferredSearchProvider;
    private String tavilyQueryMode;
    @Builder.Default
    private List<String> suggestedQueries = List.of();
    private String includeDomainPolicy;
    @Builder.Default
    private List<String> preferredDomains = List.of();
    @Builder.Default
    private List<String> includeDomains = List.of();

    /**
     * 归一化策略结果，保证执行适配器只消费稳定动作和显式证据状态。
     */
    public DecisionPolicyResult normalized() {
        return toBuilder()
                .riskLevel(upperOrDefault(riskLevel, allowed ? "LOW" : "HIGH"))
                .normalizedAction(upperOrDefault(normalizedAction, allowed ? "NO_ACTION" : "MANUAL_ONLY"))
                .blockedReasons(normalizeDistinctList(blockedReasons))
                .policyRuleRefs(normalizeDistinctList(policyRuleRefs))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceState(resolveEvidenceState())
                .policyVersion(blankToDefault(policyVersion, "ORCHESTRATION_POLICY_V1"))
                .preferredSearchProvider(lowerOrNull(preferredSearchProvider))
                .tavilyQueryMode(upperOrNull(tavilyQueryMode))
                .suggestedQueries(normalizeDistinctList(suggestedQueries))
                .includeDomainPolicy(upperOrNull(includeDomainPolicy))
                .preferredDomains(normalizeDistinctList(preferredDomains))
                .includeDomains(normalizeDistinctList(includeDomains))
                .build();
    }

    private EvidenceState resolveEvidenceState() {
        if (evidenceState != null) {
            return evidenceState;
        }
        return sourceUrls == null || sourceUrls.isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    private String upperOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String upperOrNull(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String lowerOrNull(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = blankToNull(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
