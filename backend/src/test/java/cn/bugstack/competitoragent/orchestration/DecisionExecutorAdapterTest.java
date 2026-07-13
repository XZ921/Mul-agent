package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionExecutorAdapterTest {

    private final DecisionExecutorAdapter adapter = new DecisionExecutorAdapter();

    @Test
    void shouldTranslateAllowedSupplementDecisionToAppendNodesMutation() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .reason("补充定价证据")
                .suggestedQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .policyVersion("ORCHESTRATION_POLICY_V1")
                .build();

        DynamicPlanMutation mutation = adapter.toMutation(decision, policyResult, 8L, 2);

        assertThat(mutation.getMutationId()).isEqualTo("dpm-od-001");
        assertThat(mutation.getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(mutation.getDynamicAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(mutation.getExpectedResumeNodeName()).isEqualTo("collect_revision_evidence_v2_1");
        assertThat(mutation.getNodeTemplates()).hasSize(1);
        assertThat(mutation.getNodeTemplates().get(0).getNodeConfig()).contains("decisionId");
    }

    @Test
    void shouldReturnNoMutationWhenPolicyBlocksDecision() {
        DynamicPlanMutation mutation = adapter.toMutation(
                OrchestrationDecision.builder()
                        .decisionId("od-blocked")
                        .decisionType("APPEND_DYNAMIC_BRANCH")
                        .actionType("SUPPLEMENT_EVIDENCE")
                        .evidenceState(EvidenceState.MISSING_SOURCE)
                        .build()
                        .normalized(),
                DecisionPolicyResult.builder()
                        .decisionId("od-blocked")
                        .allowed(false)
                        .blockedReasons(List.of("blocked"))
                        .build(),
                8L,
                2);

        assertThat(mutation.getMutationType()).isEqualTo("NO_MUTATION");
        assertThat(mutation.getNodeTemplates()).isEmpty();
    }

    @Test
    void shouldReturnNoMutationForInvalidLlmPairBlockedByRealPolicy() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-invalid-llm-pair")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
        DecisionPolicyResult policyResult = new DecisionPolicyService(new OrchestrationDecisionActionMatrix())
                .evaluate(decision, DecisionPolicyRuleSet.builder().build(), 0, "RUNNING", "SUCCESS");

        DynamicPlanMutation mutation = adapter.toMutation(decision, policyResult, 8L, 2);

        assertThat(policyResult.isAllowed()).isFalse();
        assertThat(policyResult.getBlockedReasons())
                .contains("INVALID_DECISION_ACTION_PAIR: REWRITE_ONLY 不允许搭配 SUPPLEMENT_EVIDENCE");
        assertThat(mutation.getMutationType()).isEqualTo("NO_MUTATION");
        assertThat(mutation.getNodeTemplates()).isEmpty();
    }

    @Test
    void shouldTranslateValidLlmSupplementAndRewriteAfterMatrixPolicyValidation() {
        DecisionPolicyService policyService = new DecisionPolicyService(new OrchestrationDecisionActionMatrix());
        OrchestrationDecision supplementDecision = OrchestrationDecision.builder()
                .decisionId("od-valid-llm-supplement")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        OrchestrationDecision rewriteDecision = OrchestrationDecision.builder()
                .decisionId("od-valid-llm-rewrite")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_CLAIM")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult supplementPolicy = policyService.evaluate(
                supplementDecision, DecisionPolicyRuleSet.builder().build(), 0, "RUNNING", "SUCCESS");
        DecisionPolicyResult rewritePolicy = policyService.evaluate(
                rewriteDecision, DecisionPolicyRuleSet.builder().build(), 0, "RUNNING", "SUCCESS");

        assertThat(adapter.toMutation(supplementDecision, supplementPolicy, 8L, 2).getDynamicAction())
                .isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(adapter.toMutation(rewriteDecision, rewritePolicy, 8L, 2).getDynamicAction())
                .isEqualTo("CREATE_REWRITE_BRANCH");
    }

    @Test
    void shouldWriteTavilyHintsIntoSupplementCollectorNodeConfig() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-010")
                .taskId(80L)
                .triggerNodeName("citation_check")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .reason("补充抖音开放平台官方定价证据")
                .suggestedQueries(List.of("抖音 开放平台 定价 官方"))
                .sourceUrls(List.of("https://open.douyin.com/platform/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-010")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of("https://open.douyin.com/platform/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .preferredSearchProvider("tavily")
                .tavilyQueryMode("EVIDENCE_REPAIR")
                .suggestedQueries(List.of("抖音 开放平台 定价 官方"))
                .includeDomainPolicy("NARROW_OFFICIAL")
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .policyVersion("ORCHESTRATION_POLICY_V1")
                .build();

        DynamicPlanMutation mutation = adapter.toMutation(decision, policyResult, 10L, 3);

        assertThat(mutation.getNodeTemplates()).hasSize(1);
        assertThat(mutation.getNodeTemplates().get(0).getNodeConfig())
                .contains("\"preferredSearchProvider\":\"tavily\"")
                .contains("\"tavilyQueryMode\":\"EVIDENCE_REPAIR\"")
                .contains("\"searchQueries\":[\"抖音 开放平台 定价 官方\"]")
                .contains("\"preferredDomains\":[\"open.douyin.com\"]")
                .contains("\"includeDomains\":[\"open.douyin.com\"]");
    }
}
