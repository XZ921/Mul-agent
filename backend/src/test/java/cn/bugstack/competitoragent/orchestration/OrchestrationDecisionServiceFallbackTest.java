package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationDecisionServiceFallbackTest {

    @Test
    void shouldCopyRuleDecisionAsFallbackAndKeepTypedFailure() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        OrchestrationDecision ruleDecision = ruleDecision();
        LlmOrchestratorDecisionFailure failure = parseFailure();
        when(llmBrain.decide(any())).thenThrow(new LlmOrchestratorDecisionException(failure));
        when(ruleBrain.decide(any())).thenReturn(List.of(ruleDecision));

        OrchestrationDecisionOutcome outcome = service(ruleBrain, llmBrain, true)
                .decideWithOutcome(context());

        assertThat(outcome.llmFailure()).isSameAs(failure);
        assertThat(outcome.decisions()).hasSize(1);
        OrchestrationDecision fallback = outcome.decisions().get(0);
        assertThat(fallback).isNotSameAs(ruleDecision);
        assertThat(fallback.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertThat(fallback.getSourceUrls()).isEqualTo(ruleDecision.getSourceUrls());
        assertThat(fallback.getInputRefs()).isEqualTo(ruleDecision.getInputRefs());
        assertThat(fallback.getDecisionMetadata().getModelName()).isNull();
        assertThat(fallback.getDecisionMetadata().getTemperature()).isZero();
        assertThat(fallback.getDecisionMetadata().getPromptHash()).isEqualTo("prompt-2");
        assertThat(fallback.getDecisionMetadata().getLlmResponseHash()).isEqualTo("response-2");
        assertThat(fallback.getDecisionMetadata().getAiAuditTraceId())
                .startsWith("orch-")
                .hasSizeLessThanOrEqualTo(50);
        assertThat(fallback.getDecisionMetadata().getParseRetryCount()).isEqualTo(1);
        assertThat(fallback.getDecisionMetadata().isFallbackUsed()).isTrue();
        assertThat(fallback.getDecisionMetadata().getFallbackReason()).isEqualTo("PARSE_ERROR:UNKNOWN_ACTION");
        assertThat(ruleDecision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
    }

    @Test
    void shouldKeepTypedFailureAndRethrowFromLegacyEntryWhenFallbackDisabled() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionFailure failure = new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.LLM_TIMEOUT, null, 0, List.of());
        when(llmBrain.decide(any())).thenThrow(new LlmOrchestratorDecisionException(failure));
        OrchestrationDecisionService service = service(ruleBrain, llmBrain, false);

        OrchestrationDecisionOutcome outcome = service.decideWithOutcome(context());

        assertThat(outcome.decisions()).isEmpty();
        assertThat(outcome.llmFailure()).isSameAs(failure);
        assertThatThrownBy(() -> service.decide(context()))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class,
                        exception -> assertThat(exception.failure()).isSameAs(failure));
        verify(ruleBrain, never()).decide(any());
    }

    @Test
    void shouldBuildPolicyRejectedFallbackWithoutCallingLlm() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        when(ruleBrain.decide(any())).thenReturn(List.of(ruleDecision()));
        OrchestratorDecisionMetadata rejectedMetadata = OrchestratorDecisionMetadata.builder()
                .modelName("deepseek-chat")
                .temperature(0.0d)
                .promptHash("prompt-policy")
                .llmResponseHash("response-policy")
                .aiAuditTraceId("orch-policy-trace")
                .parseRetryCount(1)
                .build();
        OrchestrationDecision rejected = OrchestrationDecision.builder()
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .decisionMetadata(rejectedMetadata)
                .sourceUrls(List.of("https://example.com/rejected"))
                .build();

        OrchestrationDecisionOutcome outcome = service(ruleBrain, llmBrain, true)
                .fallbackAfterPolicyRejection(context(), rejected);

        OrchestratorDecisionMetadata fallbackMetadata = outcome.decisions().get(0).getDecisionMetadata();
        assertThat(fallbackMetadata.getModelName()).isEqualTo("deepseek-chat");
        assertThat(fallbackMetadata.getPromptHash()).isEqualTo("prompt-policy");
        assertThat(fallbackMetadata.getAiAuditTraceId()).isEqualTo("orch-policy-trace");
        assertThat(fallbackMetadata.getFallbackReason()).isEqualTo("POLICY_REJECTED");
        assertThat(fallbackMetadata.isFallbackUsed()).isTrue();
        assertThat(outcome.sourceUrls()).contains("https://example.com/rejected");
        verify(ruleBrain).decide(any());
        verify(llmBrain, never()).decide(any());
    }

    @Test
    void shouldRejectNonPrimaryPolicyFallbackInput() {
        OrchestrationDecisionService service = service(
                mock(RuleBasedOrchestratorDecisionBrain.class),
                mock(LlmOrchestratorDecisionBrain.class),
                true);
        OrchestrationDecision ruleDecision = OrchestrationDecision.builder()
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .build();

        assertThatThrownBy(() -> service.fallbackAfterPolicyRejection(context(), ruleDecision))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OrchestrationDecisionService service(RuleBasedOrchestratorDecisionBrain ruleBrain,
                                                 LlmOrchestratorDecisionBrain llmBrain,
                                                 boolean fallbackEnabled) {
        OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();
        properties.setMode(OrchestratorDecisionMode.LLM_PRIMARY);
        properties.setFallbackToRule(fallbackEnabled);
        return new OrchestrationDecisionService(
                ruleBrain, llmBrain, properties, new OrchestratorFallbackReasonMapper());
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .taskId(92L)
                .triggerNodeName("analyze_competitors")
                .sourceUrls(List.of("https://example.com/context"))
                .build();
    }

    private OrchestrationDecision ruleDecision() {
        return OrchestrationDecision.builder()
                .decisionId("rule-92")
                .taskId(92L)
                .triggerNodeName("analyze_competitors")
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .reason("补充来源")
                .inputRefs(Map.of("qualityDiagnosisIds", List.of("qd-1")))
                .sourceUrls(List.of("https://example.com/rule"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
    }

    private LlmOrchestratorDecisionFailure parseFailure() {
        return new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.PARSE_ERROR,
                null,
                1,
                List.of(
                        new LlmOrchestratorDecisionFailure.Attempt(
                                1, "prompt-1", "response-1", List.of(), List.of()),
                        new LlmOrchestratorDecisionFailure.Attempt(
                                2,
                                "prompt-2",
                                "response-2",
                                List.of(new OrchestrationDecisionParseResult.ParseIssue(
                                        0, "UNKNOWN_ACTION", "actionType")),
                                List.of())));
    }
}
