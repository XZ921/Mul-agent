package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationDecisionOutcomeTest {

    @Test
    void shouldDefensivelyCopyListsAndMergeTraceableSources() {
        List<OrchestrationDecision> decisions = new ArrayList<>(List.of(decision(
                OrchestrationDecisionOrigin.RULE_ONLY, "https://example.com/rule")));
        List<OrchestrationDecision> shadows = new ArrayList<>(List.of(decision(
                OrchestrationDecisionOrigin.LLM_SHADOW, "https://example.com/shadow")));

        OrchestrationDecisionOutcome outcome = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_SHADOW,
                decisions,
                shadows,
                OrchestrationShadowExecution.executed(null, List.of("https://example.com/fact")),
                null,
                List.of("https://example.com/context", "https://example.com/rule"));
        decisions.clear();
        shadows.clear();

        assertThat(outcome.decisions()).hasSize(1);
        assertThat(outcome.shadowDecisions()).hasSize(1);
        assertThat(outcome.sourceUrls()).containsExactly(
                "https://example.com/context",
                "https://example.com/rule",
                "https://example.com/shadow",
                "https://example.com/fact");
        assertThatThrownBy(() -> outcome.decisions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldEnforceMainAndShadowOriginBoundaries() {
        assertThatThrownBy(() -> outcomeWith(
                List.of(decision(OrchestrationDecisionOrigin.LLM_SHADOW, null)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主 decisions");
        assertThatThrownBy(() -> outcomeWith(
                List.of(), List.of(decision(OrchestrationDecisionOrigin.LLM_PRIMARY, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shadowDecisions");
    }

    @Test
    void shouldNormalizeNullListsAndKeepFailureBesideFallbackDecision() {
        LlmOrchestratorDecisionFailure failure = new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.LLM_TIMEOUT, null, 0, List.of());

        OrchestrationDecisionOutcome empty = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY, null, null, null, failure, null);
        OrchestrationDecisionOutcome fallback = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                List.of(decision(OrchestrationDecisionOrigin.RULE_FALLBACK, null)),
                List.of(),
                null,
                failure,
                List.of());

        assertThat(empty.decisions()).isEmpty();
        assertThat(empty.shadowDecisions()).isEmpty();
        assertThat(fallback.llmFailure()).isSameAs(failure);
        assertThat(fallback.decisions()).hasSize(1);
    }

    private OrchestrationDecisionOutcome outcomeWith(List<OrchestrationDecision> decisions,
                                                     List<OrchestrationDecision> shadowDecisions) {
        return new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_SHADOW,
                decisions,
                shadowDecisions,
                OrchestrationShadowExecution.notRequested(List.of()),
                null,
                List.of());
    }

    private OrchestrationDecision decision(OrchestrationDecisionOrigin origin, String sourceUrl) {
        return OrchestrationDecision.builder()
                .decisionId("decision-" + origin)
                .decisionOrigin(origin)
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .sourceUrls(sourceUrl == null ? List.of() : List.of(sourceUrl))
                .build();
    }
}
