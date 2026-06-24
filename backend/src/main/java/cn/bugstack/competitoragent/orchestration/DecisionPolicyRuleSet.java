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
 * Orchestrator 决策策略集。
 * 第一版使用代码解释这些规则，不引入独立规则引擎。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionPolicyRuleSet {

    @Builder.Default
    private String policyVersion = "ORCHESTRATION_POLICY_V1";
    @Builder.Default
    private List<String> allowedDecisionTypes = List.of(
            "APPEND_DYNAMIC_BRANCH",
            "RERUN_NODE",
            "REWRITE_ONLY",
            "WAIT_FOR_HUMAN",
            "NO_ACTION"
    );
    @Builder.Default
    private List<String> allowedDynamicActions = List.of(
            "CREATE_SUPPLEMENT_BRANCH",
            "CREATE_RERUN_BRANCH",
            "CREATE_REWRITE_BRANCH",
            "MANUAL_ONLY",
            "NO_ACTION"
    );
    @Builder.Default
    private boolean requireSourceUrlsOrEvidenceGap = true;
    /** 稳定演示版使用更保守的默认值 2，低于规格长期目标 5。 */
    @Builder.Default
    private int maxAutoDecisions = 2;
    /** 稳定演示版默认每个 section 只允许 1 条动态分支，避免演示任务发散。 */
    @Builder.Default
    private int maxDynamicBranchesPerSection = 1;
    @Builder.Default
    private int maxSearchQueriesPerDecision = 5;
    @Builder.Default
    private List<String> confirmationRequiredDecisionTypes = List.of("RERUN_NODE");
    @Builder.Default
    private List<String> blockedTaskStatuses = List.of("STOPPED");
    @Builder.Default
    private List<String> blockedNodeStatuses = List.of("RUNNING");
    /**
     * 协议层保留 riskRules，P1 只用固定代码解释默认两条规则，
     * 不引入通用表达式引擎。
     */
    @Builder.Default
    private List<PolicyRiskRule> riskRules = List.of(
            PolicyRiskRule.builder()
                    .ruleId("rerun_downstream_requires_confirmation")
                    .when("decisionType == 'RERUN_NODE' && affectedScope == 'CURRENT_NODE_AND_DOWNSTREAM'")
                    .riskLevel("HIGH")
                    .requiresConfirmation(true)
                    .build(),
            PolicyRiskRule.builder()
                    .ruleId("missing_source_requires_supplement")
                    .when("evidenceState == 'MISSING_SOURCE' && actionType != 'SUPPLEMENT_EVIDENCE'")
                    .riskLevel("HIGH")
                    .requiresConfirmation(true)
                    .build()
    );
    @Builder.Default
    private List<String> sourceUrls = List.of();
    @Builder.Default
    private EvidenceState evidenceState = EvidenceState.NOT_APPLICABLE;

    /**
     * 归一化策略集，避免大小写差异或负数上限影响后续策略判断。
     */
    public DecisionPolicyRuleSet normalized() {
        return toBuilder()
                .policyVersion(blankToDefault(policyVersion, "ORCHESTRATION_POLICY_V1"))
                .allowedDecisionTypes(normalizeUpperDistinctList(allowedDecisionTypes))
                .allowedDynamicActions(normalizeUpperDistinctList(allowedDynamicActions))
                .maxAutoDecisions(Math.max(0, maxAutoDecisions))
                .maxDynamicBranchesPerSection(Math.max(0, maxDynamicBranchesPerSection))
                .maxSearchQueriesPerDecision(Math.max(0, maxSearchQueriesPerDecision))
                .confirmationRequiredDecisionTypes(normalizeUpperDistinctList(confirmationRequiredDecisionTypes))
                .blockedTaskStatuses(normalizeUpperDistinctList(blockedTaskStatuses))
                .blockedNodeStatuses(normalizeUpperDistinctList(blockedNodeStatuses))
                .riskRules(riskRules == null ? List.of() : riskRules)
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceState(evidenceState == null ? EvidenceState.NOT_APPLICABLE : evidenceState)
                .build();
    }

    private List<String> normalizeUpperDistinctList(List<String> values) {
        List<String> normalized = normalizeDistinctList(values);
        List<String> upperValues = new ArrayList<>();
        for (String value : normalized) {
            upperValues.add(value.toUpperCase(Locale.ROOT));
        }
        return upperValues;
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

    private String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyRiskRule {
        private String ruleId;
        private String when;
        private String riskLevel;
        private boolean requiresConfirmation;
    }
}
