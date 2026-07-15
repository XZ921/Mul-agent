package cn.bugstack.competitoragent.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runtime batch 到 V2 审计快照的唯一装配器。
 * 这里只做不可变结构投影，不发布事件、不查询仓储，也不重新执行 Policy。
 */
public class OrchestrationDecisionAuditAssembler {

    public OrchestrationDecisionAuditTrace assemble(OrchestrationRuntimeDecisionBatch batch) {
        requireBatch(batch);
        OrchestrationDecisionOutcome outcome = batch.coordinatorOutcome();
        List<OrchestrationRuntimeDecisionTrace> attempts = batch.attempts().stream()
                .map(this::toRuntimeTrace)
                .toList();
        List<String> finalDecisionIds = batch.finalDecisions().stream()
                .map(item -> item.decision().getDecisionId())
                .toList();
        OrchestrationDecisionAuditTrace.RuntimeStateTrace runtimeState = toRuntimeState(batch.runtimeState());
        OrchestrationDecisionAuditTrace.ShadowExecutionTrace shadowExecution =
                toShadowExecution(outcome.shadowExecution());
        List<String> mainFailureSourceUrls = mergeDecisionSourceUrls(outcome.decisions());
        OrchestrationDecisionAuditTrace.FailureTrace llmFailure =
                toFailure(outcome.llmFailure(), mainFailureSourceUrls);
        return new OrchestrationDecisionAuditTrace(
                OrchestrationDecisionAuditTrace.SCHEMA_VERSION,
                outcome.mode(),
                outcome.decisions(),
                attempts,
                finalDecisionIds,
                batch.policyFallbackUsed(),
                runtimeState,
                shadowExecution,
                outcome.shadowDecisions(),
                llmFailure,
                batch.sourceUrls());
    }

    /**
     * 兼容顶层字段只选择一个代表 attempt：优先最后一条 final，否则最后一条已评估 attempt。
     * Shadow 永远不在这两个列表中，因此不会被误投影为主路径决策。
     */
    public Optional<OrchestrationRuntimeDecisionTrace> selectRepresentativeAttempt(
            OrchestrationRuntimeDecisionBatch batch) {
        requireBatch(batch);
        List<OrchestrationRuntimeDecision> candidates = batch.finalDecisions().isEmpty()
                ? batch.attempts()
                : batch.finalDecisions();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toRuntimeTrace(candidates.get(candidates.size() - 1)));
    }

    private OrchestrationRuntimeDecisionTrace toRuntimeTrace(OrchestrationRuntimeDecision runtimeDecision) {
        DynamicPlanMutation mutation = runtimeDecision.mutation().normalized();
        OrchestrationMutationTrace mutationTrace = new OrchestrationMutationTrace(
                mutation.getMutationId(),
                mutation.getDecisionId(),
                mutation.getMutationType(),
                mutation.getTargetPlanVersionId(),
                mutation.getBranchReason(),
                mutation.getDynamicAction(),
                mutation.getExpectedResumeNodeName(),
                mutation.getEvidenceState(),
                mutation.getSourceUrls());
        return new OrchestrationRuntimeDecisionTrace(
                runtimeDecision.decision(),
                runtimeDecision.policyResult(),
                mutationTrace,
                runtimeDecision.fallbackAttempt(),
                runtimeDecision.runtimeStatus(),
                runtimeDecision.sourceUrls());
    }

    private OrchestrationDecisionAuditTrace.RuntimeStateTrace toRuntimeState(
            OrchestrationRuntimeState state) {
        return new OrchestrationDecisionAuditTrace.RuntimeStateTrace(
                state.currentDecisionCount(),
                state.dynamicBranchCountsBySection(),
                state.currentPlanVersionId(),
                state.nextPlanVersion(),
                state.checkpointStateStatus(),
                state.sourceUrls());
    }

    private OrchestrationDecisionAuditTrace.ShadowExecutionTrace toShadowExecution(
            OrchestrationShadowExecution shadowExecution) {
        OrchestrationDecisionAuditTrace.FailureTrace failure =
                toFailure(shadowExecution.failure(), shadowExecution.sourceUrls());
        return new OrchestrationDecisionAuditTrace.ShadowExecutionTrace(
                shadowExecution.requested(),
                shadowExecution.executed(),
                shadowExecution.skippedReason(),
                failure,
                shadowExecution.sourceUrls());
    }

    private OrchestrationDecisionAuditTrace.FailureTrace toFailure(
            LlmOrchestratorDecisionFailure failure,
            List<String> trustedSourceUrls) {
        if (failure == null) {
            return null;
        }
        List<OrchestrationDecisionAuditTrace.FailureAttemptTrace> attempts = failure.attempts().stream()
                .map(attempt -> new OrchestrationDecisionAuditTrace.FailureAttemptTrace(
                        attempt.attemptNumber(),
                        attempt.promptHash(),
                        attempt.llmResponseHash(),
                        attempt.issues(),
                        attempt.discardedSourceUrls(),
                        trustedSourceUrls))
                .toList();
        return new OrchestrationDecisionAuditTrace.FailureTrace(
                failure.type(),
                failure.providerErrorCode(),
                failure.parseRetryCount(),
                attempts,
                trustedSourceUrls);
    }

    private List<String> mergeDecisionSourceUrls(List<OrchestrationDecision> decisions) {
        List<List<String>> groups = new ArrayList<>();
        if (decisions != null) {
            decisions.forEach(item -> groups.add(item == null ? List.of() : item.getSourceUrls()));
        }
        return OrchestrationDecisionAuditTrace.mergeSourceUrls(groups.toArray(List[]::new));
    }

    private void requireBatch(OrchestrationRuntimeDecisionBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch 不能为空");
        }
    }
}
