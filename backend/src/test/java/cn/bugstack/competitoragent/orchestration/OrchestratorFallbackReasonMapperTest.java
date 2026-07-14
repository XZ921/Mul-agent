package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorFallbackReasonMapperTest {

    private final OrchestratorFallbackReasonMapper mapper = new OrchestratorFallbackReasonMapper();

    @Test
    void shouldMapTimeoutAndProviderErrorToStableLowCardinalityReasons() {
        assertThat(mapper.map(failure(LlmOrchestratorFailureType.LLM_TIMEOUT, List.of())))
                .isEqualTo("LLM_TIMEOUT");
        assertThat(mapper.map(failure(LlmOrchestratorFailureType.LLM_ERROR, List.of())))
                .isEqualTo("LLM_ERROR");
        assertThat(mapper.policyRejected()).isEqualTo("POLICY_REJECTED");
    }

    @Test
    void shouldUseOnlyFinalAttemptFirstIssueCodeForParseFailure() {
        LlmOrchestratorDecisionFailure.Attempt first = attempt(1, "EARLY_CODE", "secret-field");
        LlmOrchestratorDecisionFailure.Attempt last = attempt(2, "FINAL_CODE", "private-url");

        String reason = mapper.map(failure(LlmOrchestratorFailureType.PARSE_ERROR, List.of(first, last)));

        assertThat(reason).isEqualTo("PARSE_ERROR:FINAL_CODE");
        assertThat(reason).doesNotContain("secret-field", "private-url", "https://");
    }

    @Test
    void shouldFallbackToParseErrorWhenNoIssueExists() {
        assertThat(mapper.map(failure(LlmOrchestratorFailureType.PARSE_ERROR, List.of())))
                .isEqualTo("PARSE_ERROR");
    }

    private LlmOrchestratorDecisionFailure failure(
            LlmOrchestratorFailureType type,
            List<LlmOrchestratorDecisionFailure.Attempt> attempts) {
        return new LlmOrchestratorDecisionFailure(type, "provider-secret", 1, attempts);
    }

    private LlmOrchestratorDecisionFailure.Attempt attempt(int number, String code, String fieldName) {
        return new LlmOrchestratorDecisionFailure.Attempt(
                number,
                "prompt-hash",
                "response-hash",
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, code, fieldName)),
                List.of());
    }
}
