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
}
