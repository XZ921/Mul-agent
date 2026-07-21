package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import cn.bugstack.competitoragent.llm.ModelInvocationPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationDecisionServiceLlmModeTest {

    @AfterEach
    void tearDown() {
        ModelInvocationContextHolder.clear();
    }

    @Test
    void shouldUseLlmAsPrimaryWithoutCallingRuleBrain() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        OrchestratorDecisionProperties properties = properties(OrchestratorDecisionMode.LLM_PRIMARY, false);
        AtomicReference<ModelInvocationContextHolder.ModelInvocationContext> observed = new AtomicReference<>();
        OrchestrationDecision llmDecision = decision(OrchestrationDecisionOrigin.LLM_PRIMARY, "llm-primary");
        when(llmBrain.decide(any())).thenAnswer(invocation -> {
            observed.set(ModelInvocationContextHolder.get());
            return List.of(llmDecision);
        });

        OrchestrationDecisionOutcome outcome = service(ruleBrain, llmBrain, properties)
                .decideWithOutcome(context());

        assertThat(outcome.decisions())
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly(llmDecision.getDecisionId());
        assertThat(outcome.decisions().get(0)).isNotSameAs(llmDecision);
        assertThat(outcome.decisions().get(0).getDecisionMetadata().getAiAuditTraceId())
                .startsWith("orch-")
                .hasSizeLessThanOrEqualTo(50);
        assertThat(outcome.llmFailure()).isNull();
        assertThat(observed.get().purpose()).isEqualTo(ModelInvocationPurpose.ORCHESTRATOR_PRIMARY);
        verify(ruleBrain, never()).decide(any());
        verify(llmBrain).decide(any());
    }

    @Test
    void shouldSkipDisabledShadowWithoutCallingLlm() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        OrchestrationDecision ruleDecision = decision(OrchestrationDecisionOrigin.RULE_ONLY, "rule");
        when(ruleBrain.decide(any())).thenReturn(List.of(ruleDecision));

        OrchestrationDecisionOutcome outcome = service(
                ruleBrain,
                llmBrain,
                properties(OrchestratorDecisionMode.LLM_SHADOW, false))
                .decideWithOutcome(context());

        assertThat(outcome.decisions()).containsExactly(ruleDecision);
        assertThat(outcome.shadowDecisions()).isEmpty();
        assertThat(outcome.shadowExecution().requested()).isTrue();
        assertThat(outcome.shadowExecution().executed()).isFalse();
        assertThat(outcome.shadowExecution().skippedReason()).isEqualTo("SHADOW_DISABLED");
        verify(llmBrain, never()).decide(any());
    }

    @Test
    void shouldCopySuccessfulShadowWithoutMutatingPrimaryObjects() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        OrchestratorDecisionProperties properties = properties(OrchestratorDecisionMode.LLM_SHADOW, true);
        OrchestrationDecision ruleDecision = decision(OrchestrationDecisionOrigin.RULE_ONLY, "rule");
        OrchestratorDecisionMetadata originalMetadata = OrchestratorDecisionMetadata.builder()
                .modelName("deepseek-chat")
                .promptHash("prompt-hash")
                .fallbackUsed(false)
                .build();
        OrchestrationDecision originalLlmDecision = decision(
                OrchestrationDecisionOrigin.LLM_PRIMARY, "llm-shadow").toBuilder()
                .decisionMetadata(originalMetadata)
                .build();
        AtomicReference<ModelInvocationContextHolder.ModelInvocationContext> observed = new AtomicReference<>();
        when(ruleBrain.decide(any())).thenReturn(List.of(ruleDecision));
        when(llmBrain.decide(any())).thenAnswer(invocation -> {
            observed.set(ModelInvocationContextHolder.get());
            return List.of(originalLlmDecision);
        });

        OrchestrationDecisionOutcome outcome = service(ruleBrain, llmBrain, properties)
                .decideWithOutcome(context());

        assertThat(outcome.decisions())
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly(ruleDecision.getDecisionId());
        assertThat(outcome.decisions().get(0)).isNotSameAs(ruleDecision);
        assertThat(outcome.shadowDecisions()).hasSize(1);
        OrchestrationDecision shadow = outcome.shadowDecisions().get(0);
        assertThat(shadow).isNotSameAs(originalLlmDecision);
        assertThat(shadow.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_SHADOW);
        assertThat(shadow.getDecisionMetadata()).isNotSameAs(originalMetadata);
        assertThat(shadow.getDecisionMetadata().getShadowExecuted()).isTrue();
        assertThat(shadow.getDecisionMetadata().getAiAuditTraceId())
                .startsWith("orch-")
                .hasSizeLessThanOrEqualTo(50);
        assertThat(outcome.decisions().get(0).getDecisionMetadata().getAiAuditTraceId())
                .isEqualTo(shadow.getDecisionMetadata().getAiAuditTraceId());
        assertThat(ruleDecision.getDecisionMetadata().getAiAuditTraceId()).isNull();
        assertThat(originalLlmDecision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(originalMetadata.getShadowExecuted()).isNull();
        assertThat(observed.get().purpose()).isEqualTo(ModelInvocationPurpose.ORCHESTRATOR_SHADOW);
        assertThat(observed.get().quotaKey()).isEqualTo("ORCHESTRATOR_SHADOW");
        assertThat(observed.get().requireActiveQuota()).isTrue();
    }

    @Test
    void shouldTreatQuotaDenialAsUnexecutedShadowAndKeepRuleResult() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        LlmOrchestratorDecisionBrain llmBrain = mock(LlmOrchestratorDecisionBrain.class);
        OrchestrationDecision ruleDecision = decision(OrchestrationDecisionOrigin.RULE_ONLY, "rule");
        LlmOrchestratorDecisionFailure failure = failure(
                LlmOrchestratorFailureType.LLM_ERROR,
                "SHADOW_BUDGET_EXHAUSTED");
        when(ruleBrain.decide(any())).thenReturn(List.of(ruleDecision));
        when(llmBrain.decide(any())).thenThrow(new LlmOrchestratorDecisionException(failure));

        OrchestrationDecisionService service = service(
                ruleBrain,
                llmBrain,
                properties(OrchestratorDecisionMode.LLM_SHADOW, true));
        OrchestrationDecisionOutcome outcome = service.decideWithOutcome(context());

        assertThat(outcome.decisions())
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly(ruleDecision.getDecisionId());
        assertThat(outcome.decisions().get(0)).isNotSameAs(ruleDecision);
        assertThat(outcome.decisions().get(0).getDecisionMetadata().getAiAuditTraceId())
                .startsWith("orch-");
        assertThat(ruleDecision.getDecisionMetadata().getAiAuditTraceId()).isNull();
        assertThat(outcome.shadowExecution().executed()).isFalse();
        assertThat(outcome.shadowExecution().skippedReason()).isEqualTo("SHADOW_BUDGET_EXHAUSTED");
        assertThat(outcome.shadowExecution().failure()).isSameAs(failure);
        assertThat(service.decide(context()))
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly(ruleDecision.getDecisionId());
        verify(ruleBrain, times(2)).decide(any());
        verify(llmBrain, times(2)).decide(any());
    }

    private OrchestrationDecisionService service(RuleBasedOrchestratorDecisionBrain ruleBrain,
                                                 LlmOrchestratorDecisionBrain llmBrain,
                                                 OrchestratorDecisionProperties properties) {
        return new OrchestrationDecisionService(
                ruleBrain,
                llmBrain,
                properties,
                new OrchestratorFallbackReasonMapper());
    }

    private OrchestratorDecisionProperties properties(OrchestratorDecisionMode mode, boolean shadowEnabled) {
        OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();
        properties.setMode(mode);
        properties.getShadow().setEnabled(shadowEnabled);
        return properties;
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .taskId(91L)
                .triggerNodeName("quality_check_final")
                .sourceUrls(List.of("https://example.com/context"))
                .build();
    }

    private OrchestrationDecision decision(OrchestrationDecisionOrigin origin, String id) {
        return OrchestrationDecision.builder()
                .decisionId(id)
                .taskId(91L)
                .triggerNodeName("quality_check_final")
                .decisionOrigin(origin)
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .reason("test")
                .sourceUrls(List.of("https://example.com/" + id))
                .build();
    }

    private LlmOrchestratorDecisionFailure failure(LlmOrchestratorFailureType type, String code) {
        return new LlmOrchestratorDecisionFailure(type, code, 0, List.of());
    }
}
