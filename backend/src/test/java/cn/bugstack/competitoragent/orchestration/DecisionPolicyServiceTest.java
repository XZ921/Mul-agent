package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyServiceTest {

    private final DecisionPolicyService service = new DecisionPolicyService();

    @Test
    void shouldAllowSupplementEvidenceWhenSourceGapIsExplicit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("Notion AI pricing official"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(result.getPolicyRuleRefs()).contains(
                "allowedDecisionTypes",
                "requireSourceUrlsOrEvidenceGap",
                "maxSearchQueriesPerDecision");
    }

    @Test
    void shouldBlockUnknownDecisionType() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-002")
                .decisionType("FREE_FORM_DAG")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("decisionType 不在允许列表：FREE_FORM_DAG");
    }

    @Test
    void shouldRequireConfirmationForRerunNode() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-003")
                .decisionType("RERUN_NODE")
                .actionType("RERUN_NODE")
                .targetNode("extract_schema")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("MEDIUM")
                .sourceUrls(List.of("https://example.com/source"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getPolicyRuleRefs()).contains("rerun_downstream_requires_confirmation");
    }

    @Test
    void shouldBlockWhenAutoDecisionLimitIsReached() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-004")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(1).build(),
                1,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("自动编排次数已达到上限：1/1");
    }

    @Test
    void shouldElevateRewriteOnlyDecisionWhenSourceIsMissing() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-005")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .priority("MEDIUM")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
    }

    @Test
    void shouldBlockWhenSuggestedQueriesExceedConfiguredLimit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-006")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("q1", "q2", "q3", "q4", "q5", "q6"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxSearchQueriesPerDecision(5).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("搜索补证 query 数量超过上限：6/5");
    }
}
