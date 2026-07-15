package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrchestrationRuntimeDecisionServiceTest {

    private OrchestrationDecisionService decisionService;
    private DecisionPolicyService policyService;
    private DecisionExecutorAdapter executorAdapter;
    private OrchestrationRuntimeStateService stateService;
    private DecisionPolicyRuleSet ruleSet;
    private OrchestrationRuntimeDecisionService service;
    private OrchestrationRuntimeState restoredState;

    @BeforeEach
    void setUp() {
        decisionService = mock(OrchestrationDecisionService.class);
        policyService = mock(DecisionPolicyService.class);
        executorAdapter = mock(DecisionExecutorAdapter.class);
        stateService = mock(OrchestrationRuntimeStateService.class);
        ruleSet = DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build().normalized();
        service = new OrchestrationRuntimeDecisionService(
                decisionService, policyService, executorAdapter, ruleSet, stateService);
        restoredState = new OrchestrationRuntimeState(
                1, Map.of(), 10L, 2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of("https://example.com/checkpoint"));
        when(stateService.load(1L)).thenReturn(restoredState);
    }

    @Test
    void shouldEvaluateRuleOnlyAndInjectPersistedCountIntoContextAndPolicy() {
        OrchestrationDecision ruleDecision = decision("od-rule", OrchestrationDecisionOrigin.RULE_ONLY);
        DecisionPolicyResult allowed = policy(ruleDecision, true);
        DynamicPlanMutation mutation = mutation(ruleDecision, "APPEND_NODES");
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.RULE_ONLY, List.of(ruleDecision), List.of(), null));
        when(policyService.evaluate(eq(ruleDecision), eq(ruleSet), eq(1), eq("RUNNING"), eq("SUCCESS")))
                .thenReturn(allowed);
        when(executorAdapter.toMutation(ruleDecision, allowed, 10L, 2)).thenReturn(mutation);

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.attempts()).hasSize(1);
        assertThat(batch.finalDecisions()).hasSize(1);
        assertThat(batch.policyFallbackUsed()).isFalse();
        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(decisionService).decideWithOutcome(contextCaptor.capture());
        assertThat(contextCaptor.getValue().getCurrentDecisionCount()).isEqualTo(1);
        assertThat(contextCaptor.getValue().getTaskStatus()).isEqualTo("RUNNING");
        verify(decisionService, never()).fallbackAfterPolicyRejection(any(), any());
    }

    @Test
    void shouldNeverEvaluateShadowDecisions() {
        OrchestrationDecision ruleDecision = decision("od-rule", OrchestrationDecisionOrigin.RULE_ONLY);
        OrchestrationDecision shadowDecision = decision("od-shadow", OrchestrationDecisionOrigin.LLM_SHADOW);
        DecisionPolicyResult allowed = policy(ruleDecision, true);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.LLM_SHADOW,
                List.of(ruleDecision),
                List.of(shadowDecision),
                null));
        when(policyService.evaluate(eq(ruleDecision), eq(ruleSet), eq(1), anyString(), anyString()))
                .thenReturn(allowed);
        when(executorAdapter.toMutation(eq(ruleDecision), eq(allowed), any(), anyInt()))
                .thenReturn(mutation(ruleDecision, "APPEND_NODES"));

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.attempts()).extracting(result -> result.decision().getDecisionId())
                .containsExactly("od-rule");
        verify(policyService, times(1)).evaluate(any(), any(), anyInt(), anyString(), anyString());
        verify(executorAdapter, times(1)).toMutation(any(), any(), any(), anyInt());
    }

    @Test
    void shouldReturnEmptyBatchWithoutPolicyOrExecutorInteractions() {
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.LLM_PRIMARY, List.of(), List.of(), null));

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.attempts()).isEmpty();
        assertThat(batch.finalDecisions()).isEmpty();
        verifyNoInteractions(policyService, executorAdapter);
    }

    @Test
    void shouldRejectOutcomeBeyondPerCycleLimitBeforePolicyOrExecutor() {
        OrchestrationDecision first = decision("od-limit-1", OrchestrationDecisionOrigin.RULE_ONLY);
        OrchestrationDecision second = decision("od-limit-2", OrchestrationDecisionOrigin.RULE_ONLY);
        OrchestrationDecision third = decision("od-limit-3", OrchestrationDecisionOrigin.RULE_ONLY);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.RULE_ONLY,
                List.of(first, second, third),
                List.of(),
                null));

        assertThatThrownBy(() -> service.decide(context(), "RUNNING", "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDecisionsPerCycle");
        verifyNoInteractions(policyService, executorAdapter);
    }

    @Test
    void shouldUseFailClosedEffectiveCountForUnreadableCheckpoint() {
        OrchestrationRuntimeState unreadable = new OrchestrationRuntimeState(
                0, Map.of(), 10L, 2,
                OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE, List.of());
        when(stateService.load(1L)).thenReturn(unreadable);
        OrchestrationDecision ruleDecision = decision("od-rule", OrchestrationDecisionOrigin.RULE_ONLY);
        DecisionPolicyResult rejected = policy(ruleDecision, false);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.RULE_ONLY, List.of(ruleDecision), List.of(), null));
        when(policyService.evaluate(ruleDecision, ruleSet, 2, "RUNNING", "SUCCESS"))
                .thenReturn(rejected);
        when(executorAdapter.toMutation(ruleDecision, rejected, 10L, 2))
                .thenReturn(mutation(ruleDecision, "NO_MUTATION"));

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.finalDecisions()).singleElement()
                .extracting(OrchestrationRuntimeDecision::runtimeStatus)
                .isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        verify(policyService).evaluate(ruleDecision, ruleSet, 2, "RUNNING", "SUCCESS");
        verify(decisionService, never()).fallbackAfterPolicyRejection(any(), any());
    }

    @Test
    void shouldFallbackExactlyOnceAndReplaceAllPrimaryFinalDecisions() {
        OrchestrationDecision firstAllowed = decision("od-allowed", OrchestrationDecisionOrigin.LLM_PRIMARY);
        OrchestrationDecision firstRejected = decision("od-rejected-1", OrchestrationDecisionOrigin.LLM_PRIMARY);
        OrchestrationDecision fallback = decision("od-fallback", OrchestrationDecisionOrigin.RULE_FALLBACK);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                List.of(firstAllowed, firstRejected),
                List.of(),
                null));
        when(policyService.evaluate(eq(firstAllowed), eq(ruleSet), eq(1), anyString(), anyString()))
                .thenReturn(policy(firstAllowed, true));
        when(policyService.evaluate(eq(firstRejected), eq(ruleSet), eq(1), anyString(), anyString()))
                .thenReturn(policy(firstRejected, false));
        when(executorAdapter.toMutation(eq(firstAllowed), any(), any(), anyInt()))
                .thenReturn(mutation(firstAllowed, "APPEND_NODES"));
        when(executorAdapter.toMutation(eq(firstRejected), any(), any(), anyInt()))
                .thenReturn(mutation(firstRejected, "NO_MUTATION"));
        when(decisionService.fallbackAfterPolicyRejection(any(), eq(firstRejected)))
                .thenReturn(outcome(OrchestratorDecisionMode.LLM_PRIMARY, List.of(fallback), List.of(), null));
        DecisionPolicyResult fallbackPolicy = policy(fallback, true);
        when(policyService.evaluate(eq(fallback), eq(ruleSet), eq(1), anyString(), anyString()))
                .thenReturn(fallbackPolicy);
        when(executorAdapter.toMutation(fallback, fallbackPolicy, 10L, 2))
                .thenReturn(mutation(fallback, "APPEND_NODES"));

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.attempts()).extracting(result -> result.decision().getDecisionId())
                .containsExactly("od-allowed", "od-rejected-1", "od-fallback");
        assertThat(batch.finalDecisions()).extracting(result -> result.decision().getDecisionId())
                .containsExactly("od-fallback");
        assertThat(batch.policyFallbackUsed()).isTrue();
        assertThat(batch.attempts().get(1).runtimeStatus())
                .isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        assertThat(batch.finalDecisions().get(0).fallbackAttempt()).isTrue();
        verify(decisionService, times(1)).fallbackAfterPolicyRejection(any(), eq(firstRejected));
    }

    @Test
    void shouldNotFallbackAgainWhenTaskSixAlreadyReturnedRuleFallback() {
        OrchestrationDecision fallback = decision("od-failure-fallback", OrchestrationDecisionOrigin.RULE_FALLBACK);
        LlmOrchestratorDecisionFailure failure = mock(LlmOrchestratorDecisionFailure.class);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.LLM_PRIMARY, List.of(fallback), List.of(), failure));
        DecisionPolicyResult rejected = policy(fallback, false);
        when(policyService.evaluate(eq(fallback), eq(ruleSet), eq(1), anyString(), anyString()))
                .thenReturn(rejected);
        when(executorAdapter.toMutation(fallback, rejected, 10L, 2))
                .thenReturn(mutation(fallback, "NO_MUTATION"));

        OrchestrationRuntimeDecisionBatch batch = service.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.policyFallbackUsed()).isFalse();
        assertThat(batch.finalDecisions()).hasSize(1);
        verify(decisionService, never()).fallbackAfterPolicyRejection(any(), any());
    }

    @Test
    void shouldBlockAppendAtNormalizedSectionLimitWithoutTriggeringFallback() {
        OrchestrationDecision primary = decision("od-section-limit", OrchestrationDecisionOrigin.LLM_PRIMARY)
                .toBuilder()
                .targetSection(" Pricing ")
                .build()
                .normalized();
        OrchestrationRuntimeState sectionLimited = new OrchestrationRuntimeState(
                0, Map.of("pricing", 1), 10L, 2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED, List.of());
        when(stateService.load(1L)).thenReturn(sectionLimited);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.LLM_PRIMARY, List.of(primary), List.of(), null));
        DecisionPolicyResult allowed = policy(primary, true);
        when(policyService.evaluate(eq(primary), eq(ruleSet), eq(0), anyString(), anyString()))
                .thenReturn(allowed);
        DecisionExecutorAdapter realAdapter = new DecisionExecutorAdapter();
        OrchestrationRuntimeDecisionService guardedService = new OrchestrationRuntimeDecisionService(
                decisionService, policyService, realAdapter, ruleSet, stateService);

        OrchestrationRuntimeDecisionBatch batch = guardedService.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.runtimeStatus())
                    .isEqualTo(OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED);
            assertThat(result.mutation().getMutationType()).isEqualTo("NO_MUTATION");
            assertThat(result.mutation().getBranchReason()).isEqualTo("DYNAMIC_BRANCH_LIMIT_REACHED");
        });
        verify(decisionService, never()).fallbackAfterPolicyRejection(any(), any());
    }

    @Test
    void shouldKeepOtherSectionAvailableAndTreatBlankSectionAsUnscoped() {
        OrchestrationDecision feature = decision("od-feature", OrchestrationDecisionOrigin.RULE_ONLY)
                .toBuilder().targetSection("feature").build().normalized();
        OrchestrationDecision blank = decision("od-unscoped", OrchestrationDecisionOrigin.RULE_ONLY)
                .toBuilder().targetSection(" ").build().normalized();
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                0,
                Map.of("pricing", 1, OrchestrationRuntimeState.UNSCOPED_SECTION, 1),
                10L,
                2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of());
        when(stateService.load(1L)).thenReturn(state);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.RULE_ONLY, List.of(feature, blank), List.of(), null));
        DecisionPolicyResult featurePolicy = policy(feature, true);
        DecisionPolicyResult blankPolicy = policy(blank, true);
        when(policyService.evaluate(eq(feature), eq(ruleSet), eq(0), anyString(), anyString()))
                .thenReturn(featurePolicy);
        when(policyService.evaluate(eq(blank), eq(ruleSet), eq(0), anyString(), anyString()))
                .thenReturn(blankPolicy);
        OrchestrationRuntimeDecisionService guardedService = new OrchestrationRuntimeDecisionService(
                decisionService, policyService, new DecisionExecutorAdapter(), ruleSet, stateService);

        OrchestrationRuntimeDecisionBatch batch = guardedService.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.finalDecisions()).extracting(OrchestrationRuntimeDecision::runtimeStatus)
                .containsExactly(
                        OrchestrationRuntimeDecision.READY,
                        OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED);
    }

    @Test
    void shouldFailClosedAllAppendMutationsWhenSectionLimitIsZero() {
        DecisionPolicyRuleSet zeroLimitRules = DecisionPolicyRuleSet.builder()
                .maxDynamicBranchesPerSection(0)
                .build()
                .normalized();
        OrchestrationDecision primary = decision("od-zero-limit", OrchestrationDecisionOrigin.RULE_ONLY);
        when(decisionService.decideWithOutcome(any())).thenReturn(outcome(
                OrchestratorDecisionMode.RULE_ONLY, List.of(primary), List.of(), null));
        DecisionPolicyResult allowed = policy(primary, true);
        when(policyService.evaluate(eq(primary), eq(zeroLimitRules), eq(1), anyString(), anyString()))
                .thenReturn(allowed);
        OrchestrationRuntimeDecisionService guardedService = new OrchestrationRuntimeDecisionService(
                decisionService, policyService, new DecisionExecutorAdapter(), zeroLimitRules, stateService);

        OrchestrationRuntimeDecisionBatch batch = guardedService.decide(context(), "RUNNING", "SUCCESS");

        assertThat(batch.finalDecisions()).singleElement()
                .extracting(OrchestrationRuntimeDecision::runtimeStatus)
                .isEqualTo(OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED);
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .taskId(1L)
                .planVersionId(10L)
                .triggerNodeName("quality_check_final")
                .sourceUrls(List.of("https://example.com/context"))
                .build();
    }

    private OrchestrationDecision decision(String id, OrchestrationDecisionOrigin origin) {
        return OrchestrationDecision.builder()
                .decisionId(id)
                .taskId(1L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .decisionOrigin(origin)
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .sourceUrls(List.of())
                .build()
                .normalized();
    }

    private DecisionPolicyResult policy(OrchestrationDecision decision, boolean allowed) {
        return DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .decisionOrigin(decision.getDecisionOrigin())
                .allowed(allowed)
                .normalizedAction(allowed ? "CREATE_SUPPLEMENT_BRANCH" : "MANUAL_ONLY")
                .sourceUrls(List.of())
                .build()
                .normalized();
    }

    private DynamicPlanMutation mutation(OrchestrationDecision decision, String type) {
        return DynamicPlanMutation.builder()
                .mutationId("dpm-" + decision.getDecisionId())
                .decisionId(decision.getDecisionId())
                .mutationType(type)
                .sourceUrls(List.of())
                .build()
                .normalized();
    }

    private OrchestrationDecisionOutcome outcome(OrchestratorDecisionMode mode,
                                                 List<OrchestrationDecision> decisions,
                                                 List<OrchestrationDecision> shadowDecisions,
                                                 LlmOrchestratorDecisionFailure failure) {
        return new OrchestrationDecisionOutcome(
                mode,
                decisions,
                shadowDecisions,
                OrchestrationShadowExecution.notRequested(List.of()),
                failure,
                List.of());
    }
}
