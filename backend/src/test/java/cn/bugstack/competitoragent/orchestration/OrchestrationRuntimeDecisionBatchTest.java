package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRuntimeDecisionBatchTest {

    @Test
    void shouldKeepRejectedPrimaryAndFallbackAttemptsWithOnlyFallbackFinal() {
        OrchestrationRuntimeDecision rejected = runtimeDecision(
                OrchestrationDecisionOrigin.LLM_PRIMARY,
                OrchestrationRuntimeDecision.POLICY_REJECTED,
                "NO_MUTATION",
                false,
                false);
        OrchestrationRuntimeDecision fallback = runtimeDecision(
                OrchestrationDecisionOrigin.RULE_FALLBACK,
                OrchestrationRuntimeDecision.READY,
                "APPEND_NODES",
                true,
                true);

        OrchestrationRuntimeDecisionBatch batch = new OrchestrationRuntimeDecisionBatch(
                outcome(List.of(rejected.decision())),
                state(OrchestrationRuntimeState.CheckpointStateStatus.RESTORED),
                List.of(rejected, fallback),
                List.of(fallback),
                true,
                List.of("https://example.com/batch"));

        assertThat(batch.attempts()).containsExactly(rejected, fallback);
        assertThat(batch.finalDecisions()).containsExactly(fallback);
        assertThat(batch.policyFallbackUsed()).isTrue();
        assertThat(batch.sourceUrls()).contains("https://example.com/batch");
    }

    @Test
    void shouldRejectShadowAndFinalResultOutsideAttempts() {
        OrchestrationRuntimeDecision shadow = runtimeDecision(
                OrchestrationDecisionOrigin.LLM_SHADOW,
                OrchestrationRuntimeDecision.NO_MUTATION,
                "NO_MUTATION",
                false,
                true);
        assertThatThrownBy(() -> new OrchestrationRuntimeDecisionBatch(
                outcome(List.of()), state(OrchestrationRuntimeState.CheckpointStateStatus.ABSENT),
                List.of(shadow), List.of(), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_SHADOW");

        OrchestrationRuntimeDecision primary = runtimeDecision(
                OrchestrationDecisionOrigin.LLM_PRIMARY,
                OrchestrationRuntimeDecision.READY,
                "APPEND_NODES",
                false,
                true);
        OrchestrationRuntimeDecision other = runtimeDecision(
                OrchestrationDecisionOrigin.RULE_FALLBACK,
                OrchestrationRuntimeDecision.READY,
                "APPEND_NODES",
                true,
                true);
        assertThatThrownBy(() -> new OrchestrationRuntimeDecisionBatch(
                outcome(List.of(primary.decision())), state(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE),
                List.of(primary), List.of(other), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
    }

    @Test
    void shouldCarryAllCheckpointStatusesAndNormalizeNullLists() {
        for (OrchestrationRuntimeState.CheckpointStateStatus status
                : OrchestrationRuntimeState.CheckpointStateStatus.values()) {
            OrchestrationRuntimeDecisionBatch batch = new OrchestrationRuntimeDecisionBatch(
                    outcome(List.of()), state(status), null, null, false, null);
            assertThat(batch.runtimeState().checkpointStateStatus()).isEqualTo(status);
            assertThat(batch.attempts()).isEmpty();
            assertThat(batch.finalDecisions()).isEmpty();
            assertThat(batch.sourceUrls()).contains("https://example.com/state");
        }
    }

    private OrchestrationRuntimeDecision runtimeDecision(OrchestrationDecisionOrigin origin,
                                                         String status,
                                                         String mutationType,
                                                         boolean fallback,
                                                         boolean allowed) {
        return new OrchestrationRuntimeDecision(
                OrchestrationRuntimeDecisionTest.decision(origin, List.of()),
                OrchestrationRuntimeDecisionTest.policy(allowed, false, List.of()),
                OrchestrationRuntimeDecisionTest.mutation(mutationType, List.of()),
                fallback,
                status,
                List.of());
    }

    private OrchestrationDecisionOutcome outcome(List<OrchestrationDecision> decisions) {
        return new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                decisions,
                List.of(),
                OrchestrationShadowExecution.notRequested(List.of()),
                null,
                List.of());
    }

    private OrchestrationRuntimeState state(OrchestrationRuntimeState.CheckpointStateStatus status) {
        return new OrchestrationRuntimeState(0, null, null, 1, status, List.of("https://example.com/state"));
    }
}
