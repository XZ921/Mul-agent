package cn.bugstack.competitoragent.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OrchestrationDecisionAuditTestFixtures {

    static final String TRUSTED_URL = "https://docs.example.com/review-gap";
    static final String CHECKPOINT_URL = "https://docs.example.com/checkpoint";
    static final String SHADOW_URL = "https://docs.example.com/shadow";
    static final String DISCARDED_URL = "https://untrusted.example.net/outside";

    private OrchestrationDecisionAuditTestFixtures() {
    }

    static OrchestrationRuntimeDecisionBatch fallbackBatch() {
        OrchestrationDecision primary = decision(
                "od-801-llm-primary", OrchestrationDecisionOrigin.LLM_PRIMARY, TRUSTED_URL);
        OrchestrationDecision fallback = decision(
                "od-801-rule-fallback", OrchestrationDecisionOrigin.RULE_FALLBACK, TRUSTED_URL)
                .toBuilder()
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .decisionMetadata(OrchestratorDecisionMetadata.builder()
                        .modelName("deepseek-chat")
                        .temperature(0.0d)
                        .promptHash("sha256:prompt-2")
                        .llmResponseHash("sha256:response-2")
                        .parseRetryCount(1)
                        .fallbackUsed(true)
                        .fallbackReason("PARSE_ERROR:INVALID_DECISION_ACTION_PAIR")
                        .build())
                .build()
                .normalized();
        OrchestrationDecision shadow = decision(
                "od-801-shadow", OrchestrationDecisionOrigin.LLM_SHADOW, SHADOW_URL);
        LlmOrchestratorDecisionFailure failure = failure();
        OrchestrationDecisionOutcome outcome = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                List.of(primary),
                List.of(shadow),
                OrchestrationShadowExecution.executed(null, List.of(SHADOW_URL)),
                failure,
                List.of(TRUSTED_URL, SHADOW_URL));
        OrchestrationRuntimeDecision primaryAttempt = runtimeDecision(
                primary, false, false, OrchestrationRuntimeDecision.POLICY_REJECTED, "NO_MUTATION");
        OrchestrationRuntimeDecision fallbackAttempt = runtimeDecision(
                fallback, true, true, OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED,
                "MARK_WAITING_INTERVENTION");
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                1,
                Map.of("pricing", 1),
                31L,
                3,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of(CHECKPOINT_URL));
        return new OrchestrationRuntimeDecisionBatch(
                outcome,
                state,
                List.of(primaryAttempt, fallbackAttempt),
                List.of(fallbackAttempt),
                true,
                List.of(TRUSTED_URL, CHECKPOINT_URL, SHADOW_URL));
    }

    static OrchestrationRuntimeDecisionBatch shadowSkippedBatch() {
        OrchestrationDecisionOutcome outcome = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_SHADOW,
                List.of(),
                List.of(),
                OrchestrationShadowExecution.skipped(
                        "SHADOW_BUDGET_EXHAUSTED", null, List.of(SHADOW_URL)),
                null,
                List.of(SHADOW_URL));
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                0,
                Map.of(),
                32L,
                2,
                OrchestrationRuntimeState.CheckpointStateStatus.ABSENT,
                List.of(SHADOW_URL));
        return new OrchestrationRuntimeDecisionBatch(
                outcome, state, List.of(), List.of(), false, List.of(SHADOW_URL));
    }

    static OrchestrationRuntimeDecisionBatch maximumCardinalityBatch() {
        OrchestrationDecision first = decision("od-max-primary-1", OrchestrationDecisionOrigin.LLM_PRIMARY, TRUSTED_URL);
        OrchestrationDecision second = decision("od-max-primary-2", OrchestrationDecisionOrigin.LLM_PRIMARY, TRUSTED_URL);
        OrchestrationDecision shadowOne = decision("od-max-shadow-1", OrchestrationDecisionOrigin.LLM_SHADOW, SHADOW_URL);
        OrchestrationDecision shadowTwo = decision("od-max-shadow-2", OrchestrationDecisionOrigin.LLM_SHADOW, SHADOW_URL);
        OrchestrationDecisionOutcome outcome = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                List.of(first, second),
                List.of(shadowOne, shadowTwo),
                OrchestrationShadowExecution.executed(failure(), List.of(SHADOW_URL)),
                failure(),
                List.of(TRUSTED_URL, SHADOW_URL));

        List<OrchestrationRuntimeDecision> attempts = new ArrayList<>();
        attempts.add(runtimeDecision(first, false, false, OrchestrationRuntimeDecision.POLICY_REJECTED, "NO_MUTATION"));
        attempts.add(runtimeDecision(second, false, false, OrchestrationRuntimeDecision.POLICY_REJECTED, "NO_MUTATION"));
        OrchestrationDecision fallbackOne = decision("od-max-fallback-1", OrchestrationDecisionOrigin.RULE_FALLBACK, TRUSTED_URL);
        OrchestrationDecision fallbackTwo = decision("od-max-fallback-2", OrchestrationDecisionOrigin.RULE_FALLBACK, TRUSTED_URL);
        OrchestrationRuntimeDecision fallbackAttemptOne = runtimeDecision(
                fallbackOne, true, true, OrchestrationRuntimeDecision.READY, "APPEND_NODES");
        OrchestrationRuntimeDecision fallbackAttemptTwo = runtimeDecision(
                fallbackTwo, true, true, OrchestrationRuntimeDecision.READY, "APPEND_NODES");
        attempts.add(fallbackAttemptOne);
        attempts.add(fallbackAttemptTwo);
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                1,
                Map.of("pricing", 1),
                33L,
                3,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of(CHECKPOINT_URL));
        return new OrchestrationRuntimeDecisionBatch(
                outcome,
                state,
                attempts,
                List.of(fallbackAttemptOne, fallbackAttemptTwo),
                true,
                List.of(TRUSTED_URL, CHECKPOINT_URL, SHADOW_URL));
    }

    private static OrchestrationRuntimeDecision runtimeDecision(OrchestrationDecision decision,
                                                                boolean fallbackAttempt,
                                                                boolean allowed,
                                                                String runtimeStatus,
                                                                String mutationType) {
        DecisionPolicyResult policy = DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .decisionOrigin(decision.getDecisionOrigin())
                .allowed(allowed)
                .requiresConfirmation(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED.equals(runtimeStatus))
                .blockedReasons(allowed ? List.of() : List.of("INVALID_DECISION_ACTION_PAIR"))
                .normalizedAction(allowed ? "CREATE_SUPPLEMENT_BRANCH" : "MANUAL_ONLY")
                .sourceUrls(decision.getSourceUrls())
                .evidenceState(decision.getEvidenceState())
                .build()
                .normalized();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-" + decision.getDecisionId())
                .decisionId(decision.getDecisionId())
                .mutationType(mutationType)
                .targetPlanVersionId(33L)
                .branchReason(allowed ? "ORCHESTRATOR_DECISION" : "POLICY_REJECTED")
                .dynamicAction(policy.getNormalizedAction())
                .runtimeCommand("DO_NOT_PERSIST")
                .nodeTemplates(List.of())
                .sourceUrls(decision.getSourceUrls())
                .evidenceState(decision.getEvidenceState())
                .build()
                .normalized();
        return new OrchestrationRuntimeDecision(
                decision, policy, mutation, fallbackAttempt, runtimeStatus, decision.getSourceUrls());
    }

    private static OrchestrationDecision decision(String id,
                                                  OrchestrationDecisionOrigin origin,
                                                  String sourceUrl) {
        return OrchestrationDecision.builder()
                .decisionId(id)
                .taskId(801L)
                .triggerNodeName("quality_check_final")
                .decisionOrigin(origin)
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .reason("根据当前证据状态执行运行期编排。")
                .sourceUrls(List.of(sourceUrl))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build()
                .normalized();
    }

    private static LlmOrchestratorDecisionFailure failure() {
        return new LlmOrchestratorDecisionFailure(
                LlmOrchestratorFailureType.PARSE_ERROR,
                null,
                1,
                List.of(
                        failureAttempt(1, "sha256:prompt-1", "sha256:response-1"),
                        failureAttempt(2, "sha256:prompt-2", "sha256:response-2")));
    }

    private static LlmOrchestratorDecisionFailure.Attempt failureAttempt(int number,
                                                                         String promptHash,
                                                                         String responseHash) {
        return new LlmOrchestratorDecisionFailure.Attempt(
                number,
                promptHash,
                responseHash,
                List.of(new OrchestrationDecisionParseResult.ParseIssue(
                        0, "INVALID_DECISION_ACTION_PAIR", "decisionType/actionType")),
                number == 1
                        ? List.of(new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        0, DISCARDED_URL, "SOURCE_URL_OUTSIDE_CONTEXT"))
                        : List.of());
    }
}
