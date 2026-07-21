package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmOrchestratorDecisionBrainTest {

    private final OrchestrationDecisionPromptBuilder promptBuilder = mock(OrchestrationDecisionPromptBuilder.class);
    private final OrchestrationDecisionModelInvoker modelInvoker = mock(OrchestrationDecisionModelInvoker.class);
    private final OrchestrationDecisionResponseParser parser = mock(OrchestrationDecisionResponseParser.class);
    private final OrchestrationDecisionRetryPromptBuilder retryPromptBuilder =
            mock(OrchestrationDecisionRetryPromptBuilder.class);
    private final DecisionPolicyRuleSet ruleSet = DecisionPolicyRuleSet.builder().build().normalized();
    private final OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();

    @Test
    void shouldReturnImmutableLlmPrimaryDecisionsWithActualFinalMetadata() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt prompt = prompt("initial");
        OrchestrationDecision parsedDecision = parsedDecision();
        when(promptBuilder.build(context, ruleSet)).thenReturn(prompt);
        when(modelInvoker.invoke(eq(prompt), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("raw-success", "deepseek-chat"));
        when(parser.parse("raw-success", context, OrchestrationDecisionOrigin.LLM_PRIMARY))
                .thenReturn(OrchestrationDecisionParseResult.success(
                        List.of(parsedDecision),
                        List.of(new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                                0, "https://outside.example", "SOURCE_URL_OUTSIDE_CONTEXT"))));
        LlmOrchestratorDecisionBrain brain = brain(() -> 0L);

        List<OrchestrationDecision> decisions = brain.decide(context);

        assertThat(decisions).hasSize(1);
        assertThatThrownBy(decisions::clear).isInstanceOf(UnsupportedOperationException.class);
        OrchestrationDecision decision = decisions.get(0);
        assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(decision.getSourceUrls()).containsExactly("https://evidence.example/a");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
        assertThat(decision.getInputRefs()).containsEntry("retained", "parser-value");
        assertThat(decision.getDecisionMetadata().getModelName()).isEqualTo("deepseek-chat");
        assertThat(decision.getDecisionMetadata().getTemperature()).isZero();
        assertThat(decision.getDecisionMetadata().getPromptHash())
                .isEqualTo(OrchestrationDecisionHashing.hashPrompt(prompt));
        assertThat(decision.getDecisionMetadata().getLlmResponseHash())
                .isEqualTo(OrchestrationDecisionHashing.hashResponse("raw-success"));
        assertThat(decision.getDecisionMetadata().getParseRetryCount()).isZero();
        assertThat(decision.getDecisionMetadata().isFallbackUsed()).isFalse();
        assertThat(decision.getDecisionMetadata().getFallbackReason()).isNull();
        assertThat(decision.getDecisionMetadata().getShadowExecuted()).isNull();

        ArgumentCaptor<ModelChatOptions> optionsCaptor = ArgumentCaptor.forClass(ModelChatOptions.class);
        ArgumentCaptor<Long> remainingCaptor = ArgumentCaptor.forClass(Long.class);
        verify(modelInvoker).invoke(eq(prompt), eq(context), optionsCaptor.capture(), remainingCaptor.capture());
        assertThat(optionsCaptor.getValue())
                .isEqualTo(new ModelChatOptions(0.0d, 4000L, "deepseek-chat"));
        assertThat(remainingCaptor.getValue()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(4000L));
        verify(retryPromptBuilder, never()).build(any(), anyInt(), any());
    }

    @Test
    void shouldRetryOnlyAfterParseFailureAndUseSecondAttemptHashes() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        OrchestrationDecisionPrompt retry = prompt("retry");
        OrchestrationDecisionParseResult.ParseIssue issue =
                new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority");
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(any(), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("raw-invalid", "model-a"))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("raw-valid", "model-b"));
        when(parser.parse("raw-invalid", context, OrchestrationDecisionOrigin.LLM_PRIMARY))
                .thenReturn(OrchestrationDecisionParseResult.failure(List.of(issue), List.of()));
        when(retryPromptBuilder.build(initial, 1, List.of(issue))).thenReturn(retry);
        when(parser.parse("raw-valid", context, OrchestrationDecisionOrigin.LLM_PRIMARY))
                .thenReturn(OrchestrationDecisionParseResult.success(List.of(parsedDecision()), List.of()));

        OrchestrationDecision decision = brain(() -> 0L).decide(context).get(0);

        verify(modelInvoker, times(2)).invoke(any(), eq(context), any(ModelChatOptions.class), anyLong());
        verify(parser, times(2)).parse(any(), eq(context), eq(OrchestrationDecisionOrigin.LLM_PRIMARY));
        assertThat(decision.getDecisionMetadata().getModelName()).isEqualTo("model-b");
        assertThat(decision.getDecisionMetadata().getParseRetryCount()).isEqualTo(1);
        assertThat(decision.getDecisionMetadata().getPromptHash())
                .isEqualTo(OrchestrationDecisionHashing.hashPrompt(retry));
        assertThat(decision.getDecisionMetadata().getLlmResponseHash())
                .isEqualTo(OrchestrationDecisionHashing.hashResponse("raw-valid"));
    }

    @Test
    void shouldExposeBothParseAttemptsWhenRetryIsExhausted() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        OrchestrationDecisionPrompt retry = prompt("retry");
        OrchestrationDecisionParseResult firstFailure = OrchestrationDecisionParseResult.failure(
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority")),
                List.of());
        OrchestrationDecisionParseResult secondFailure = OrchestrationDecisionParseResult.failure(
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_CONFIDENCE", "confidence")),
                List.of());
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(any(), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("first", "model"))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("second", "model"));
        when(parser.parse("first", context, OrchestrationDecisionOrigin.LLM_PRIMARY)).thenReturn(firstFailure);
        when(retryPromptBuilder.build(initial, 1, firstFailure.issues())).thenReturn(retry);
        when(parser.parse("second", context, OrchestrationDecisionOrigin.LLM_PRIMARY)).thenReturn(secondFailure);

        assertThatThrownBy(() -> brain(() -> 0L).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception -> {
                    assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.PARSE_ERROR);
                    assertThat(exception.failure().parseRetryCount()).isEqualTo(1);
                    assertThat(exception.failure().attempts()).hasSize(2);
                    assertThat(exception.failure().attempts().get(0).issues()).isEqualTo(firstFailure.issues());
                    assertThat(exception.failure().attempts().get(1).issues()).isEqualTo(secondFailure.issues());
                    assertThat(exception.getMessage()).doesNotContain("first", "second", "confidence");
                });
    }

    @Test
    void shouldFailImmediatelyWhenParseRetryIsDisabled() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        OrchestrationDecisionParseResult failure = OrchestrationDecisionParseResult.failure(
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority")),
                List.of());
        properties.setMaxParseRetries(0);
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(eq(initial), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("invalid", "model"));
        when(parser.parse("invalid", context, OrchestrationDecisionOrigin.LLM_PRIMARY)).thenReturn(failure);

        assertThatThrownBy(() -> brain(() -> 0L).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception -> {
                    assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.PARSE_ERROR);
                    assertThat(exception.failure().parseRetryCount()).isZero();
                    assertThat(exception.failure().attempts()).hasSize(1);
                });
        verify(modelInvoker, times(1)).invoke(any(), eq(context), any(), anyLong());
        verify(retryPromptBuilder, never()).build(any(), anyInt(), any());
    }

    @Test
    void shouldRetainFirstParseFactsWhenSecondModelInvocationFails() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        OrchestrationDecisionPrompt retry = prompt("retry");
        OrchestrationDecisionParseResult firstFailure = OrchestrationDecisionParseResult.failure(
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority")),
                List.of(new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        0, "https://outside.example", "SOURCE_URL_OUTSIDE_CONTEXT")));
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(eq(initial), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("invalid", "model"));
        when(modelInvoker.invoke(eq(retry), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenThrow(new OrchestrationDecisionModelInvoker.ModelInvocationException(
                        "HTTP_502", true, new LlmException("provider failed")));
        when(parser.parse("invalid", context, OrchestrationDecisionOrigin.LLM_PRIMARY)).thenReturn(firstFailure);
        when(retryPromptBuilder.build(initial, 1, firstFailure.issues())).thenReturn(retry);

        assertThatThrownBy(() -> brain(() -> 0L).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception -> {
                    assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.LLM_ERROR);
                    assertThat(exception.failure().providerErrorCode()).isEqualTo("HTTP_502");
                    assertThat(exception.failure().parseRetryCount()).isEqualTo(1);
                    assertThat(exception.failure().attempts()).hasSize(2);
                    assertThat(exception.failure().attempts().get(0).issues()).isEqualTo(firstFailure.issues());
                    assertThat(exception.failure().attempts().get(0).discardedSourceUrls())
                            .isEqualTo(firstFailure.discardedSourceUrls());
                    assertThat(exception.failure().attempts().get(1).issues()).isEmpty();
                });
    }

    @Test
    void shouldMapModelErrorsAndTimeoutWithoutInvokingParser() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(eq(initial), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenThrow(new OrchestrationDecisionModelInvoker.ModelInvocationException(
                        "HTTP_503", true, new LlmException("unsafe provider detail")));

        assertThatThrownBy(() -> brain(() -> 0L).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception -> {
                    assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.LLM_ERROR);
                    assertThat(exception.failure().providerErrorCode()).isEqualTo("HTTP_503");
                    assertThat(exception.failure().attempts()).hasSize(1);
                    assertThat(exception.failure().attempts().get(0).llmResponseHash()).isNull();
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.getMessage()).doesNotContain("unsafe provider detail");
                });
        verify(parser, never()).parse(any(), any(), any());

        org.mockito.Mockito.reset(modelInvoker, parser);
        when(modelInvoker.invoke(eq(initial), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenThrow(new OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException());
        assertThatThrownBy(() -> brain(() -> 0L).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception ->
                        assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.LLM_TIMEOUT));
        verify(parser, never()).parse(any(), any(), any());
    }

    @Test
    void shouldNotSubmitSecondAttemptWhenSingleDeadlineIsExhausted() {
        OrchestrationContext context = context();
        OrchestrationDecisionPrompt initial = prompt("initial");
        OrchestrationDecisionPrompt retry = prompt("retry");
        AtomicLong nanoTime = new AtomicLong(0L);
        OrchestrationDecisionParseResult failure = OrchestrationDecisionParseResult.failure(
                List.of(new OrchestrationDecisionParseResult.ParseIssue(0, "INVALID_PRIORITY", "priority")),
                List.of());
        properties.setLlmTimeoutMs(100L);
        when(promptBuilder.build(context, ruleSet)).thenReturn(initial);
        when(modelInvoker.invoke(eq(initial), eq(context), any(ModelChatOptions.class), anyLong()))
                .thenReturn(new OrchestrationDecisionModelInvoker.ModelResponse("invalid", "model"));
        when(parser.parse("invalid", context, OrchestrationDecisionOrigin.LLM_PRIMARY))
                .thenAnswer(invocation -> {
                    nanoTime.set(TimeUnit.MILLISECONDS.toNanos(101L));
                    return failure;
                });
        when(retryPromptBuilder.build(initial, 1, failure.issues())).thenReturn(retry);

        assertThatThrownBy(() -> brain(nanoTime::get).decide(context))
                .isInstanceOfSatisfying(LlmOrchestratorDecisionException.class, exception -> {
                    assertThat(exception.failure().type()).isEqualTo(LlmOrchestratorFailureType.LLM_TIMEOUT);
                    assertThat(exception.failure().parseRetryCount()).isZero();
                    assertThat(exception.failure().attempts()).hasSize(1);
                });
        verify(modelInvoker, times(1)).invoke(any(), eq(context), any(), anyLong());
    }

    @Test
    void shouldRejectCallerContractAndRemainNonSpringPojo() {
        LlmOrchestratorDecisionBrain brain = brain(() -> 0L);

        assertThatThrownBy(() -> brain.decide(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> brain.decide(OrchestrationContext.builder()
                .triggerNodeName("reviewer").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> brain.decide(OrchestrationContext.builder()
                .taskId(7L).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LlmOrchestratorDecisionBrain.class.getAnnotation(Component.class)).isNull();
        assertThat(LlmOrchestratorDecisionBrain.class.getAnnotation(Service.class)).isNull();
        assertThatThrownBy(() -> new LlmOrchestratorDecisionBrain(
                promptBuilder, modelInvoker, parser, retryPromptBuilder, null, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizedRuleSet");
    }

    private LlmOrchestratorDecisionBrain brain(LongSupplier nanoTimeSource) {
        return new LlmOrchestratorDecisionBrain(
                promptBuilder,
                modelInvoker,
                parser,
                retryPromptBuilder,
                ruleSet,
                properties,
                nanoTimeSource);
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .taskId(7L)
                .triggerNodeName("reviewer")
                .sourceUrls(List.of("https://evidence.example/a"))
                .build()
                .normalized();
    }

    private OrchestrationDecisionPrompt prompt(String suffix) {
        return new OrchestrationDecisionPrompt(
                "system-" + suffix,
                "user-" + suffix,
                "schema-" + suffix);
    }

    private OrchestrationDecision parsedDecision() {
        return OrchestrationDecision.builder()
                .decisionId("od-7-reviewer-llm-1")
                .taskId(7L)
                .triggerNodeName("reviewer")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .decisionMetadata(OrchestratorDecisionMetadata.empty())
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("writer")
                .affectedScope("CURRENT_NODE_ONLY")
                .priority("HIGH")
                .reason("rewrite")
                .confidence(0.9d)
                .inputRefs(Map.of(
                        "retained", "parser-value",
                        "qualityDiagnosisIds", List.of(),
                        "agentSuggestionIds", List.of(),
                        "triggerNodeName", "reviewer"))
                .sourceUrls(List.of("https://evidence.example/a"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()
                .normalized();
    }
}
