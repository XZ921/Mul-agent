package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRuntimeDecisionTest {

    @Test
    void shouldMergeSourcesAndKeepStructuredFactFreeOfRawModelFields() {
        OrchestrationRuntimeDecision result = new OrchestrationRuntimeDecision(
                decision(OrchestrationDecisionOrigin.LLM_PRIMARY, List.of("https://example.com/decision")),
                policy(true, false, List.of("https://example.com/policy")),
                mutation("APPEND_NODES", List.of("https://example.com/mutation")),
                false,
                OrchestrationRuntimeDecision.READY,
                List.of("https://example.com/explicit"));

        assertThat(result.sourceUrls()).containsExactly(
                "https://example.com/explicit",
                "https://example.com/decision",
                "https://example.com/policy",
                "https://example.com/mutation");
        assertThat(Arrays.stream(OrchestrationRuntimeDecision.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("rawPrompt", "rawResponse", "message", "exceptionMessage");
    }

    @Test
    void shouldRejectPolicyAndConfirmationMutationConflicts() {
        assertThatThrownBy(() -> new OrchestrationRuntimeDecision(
                decision(OrchestrationDecisionOrigin.LLM_PRIMARY, List.of()),
                policy(false, false, List.of()),
                mutation("APPEND_NODES", List.of()),
                false,
                OrchestrationRuntimeDecision.POLICY_REJECTED,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POLICY_REJECTED");

        assertThatThrownBy(() -> new OrchestrationRuntimeDecision(
                decision(OrchestrationDecisionOrigin.LLM_PRIMARY, List.of()),
                policy(true, true, List.of()),
                mutation("APPEND_NODES", List.of()),
                false,
                OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMATION_REQUIRED");
    }

    static OrchestrationDecision decision(OrchestrationDecisionOrigin origin, List<String> sourceUrls) {
        return OrchestrationDecision.builder()
                .decisionId("od-1")
                .taskId(1L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .decisionOrigin(origin)
                .sourceUrls(sourceUrls)
                .build();
    }

    static DecisionPolicyResult policy(boolean allowed, boolean confirmation, List<String> sourceUrls) {
        return DecisionPolicyResult.builder()
                .decisionId("od-1")
                .allowed(allowed)
                .requiresConfirmation(confirmation)
                .normalizedAction(allowed ? "CREATE_SUPPLEMENT_BRANCH" : "MANUAL_ONLY")
                .sourceUrls(sourceUrls)
                .build();
    }

    static DynamicPlanMutation mutation(String mutationType, List<String> sourceUrls) {
        return DynamicPlanMutation.builder()
                .mutationId("dpm-1")
                .decisionId("od-1")
                .mutationType(mutationType)
                .sourceUrls(sourceUrls)
                .build();
    }
}
