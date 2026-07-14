package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmOrchestratorDecisionFailureTest {

    @Test
    void shouldExposeImmutableFailureAttemptsWithoutRawText() {
        List<OrchestrationDecisionParseResult.ParseIssue> issues = new ArrayList<>(List.of(
                new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority")));
        List<OrchestrationDecisionParseResult.DiscardedSourceUrl> discarded = new ArrayList<>(List.of(
                new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        0, "https://outside.example", "SOURCE_URL_OUTSIDE_CONTEXT")));
        LlmOrchestratorDecisionFailure.Attempt attempt = new LlmOrchestratorDecisionFailure.Attempt(
                1,
                "sha256:prompt",
                "sha256:response",
                issues,
                discarded);
        List<LlmOrchestratorDecisionFailure.Attempt> attempts = new ArrayList<>(List.of(attempt));

        LlmOrchestratorDecisionFailure failure = new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.PARSE_ERROR,
                " PARSER_REJECTED ",
                1,
                attempts);
        LlmOrchestratorDecisionException exception = new LlmOrchestratorDecisionException(failure);

        attempts.clear();
        issues.clear();
        discarded.clear();
        assertThat(failure.providerErrorCode()).isEqualTo("PARSER_REJECTED");
        assertThat(failure.attempts()).hasSize(1);
        assertThat(failure.attempts().get(0).issues()).hasSize(1);
        assertThat(failure.attempts().get(0).discardedSourceUrls()).hasSize(1);
        assertThatThrownBy(() -> failure.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> failure.attempts().get(0).issues().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(exception.failure()).isSameAs(failure);
        assertThat(exception.getMessage())
                .contains("PARSE_ERROR")
                .doesNotContain("outside.example", "INVALID_PRIORITY", "sha256:response");
    }

    @Test
    void shouldRejectInvalidFailureContracts() {
        assertThatThrownBy(() -> new LlmOrchestratorDecisionFailure(null, null, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.LLM_ERROR, null, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmOrchestratorDecisionFailure.Attempt(
                0, "sha256:p", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmOrchestratorDecisionException(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBuildStableLengthPrefixedSha256Fingerprints() {
        OrchestrationDecisionPrompt first = new OrchestrationDecisionPrompt("ab", "c", "schema");
        OrchestrationDecisionPrompt differentBoundary = new OrchestrationDecisionPrompt("a", "bc", "schema");
        String firstHash = OrchestrationDecisionHashing.hashPrompt(first);

        assertThat(firstHash)
                .isEqualTo(OrchestrationDecisionHashing.hashPrompt(first))
                .matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(OrchestrationDecisionHashing.hashPrompt(differentBoundary));
        assertThat(OrchestrationDecisionHashing.hashPrompt(
                new OrchestrationDecisionPrompt("ab", "c", "schema-2"))).isNotEqualTo(firstHash);
        assertThat(OrchestrationDecisionHashing.hashResponse("{\"ok\":true}"))
                .matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(OrchestrationDecisionHashing.hashResponse(" {\"ok\":true}"));
        assertThat(OrchestrationDecisionHashing.hashResponse(null)).isNull();
    }
}
