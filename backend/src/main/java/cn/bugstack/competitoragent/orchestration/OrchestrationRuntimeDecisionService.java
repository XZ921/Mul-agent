package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator 决策进入生产 runtime 的统一协调器。
 * 本服务单一拥有 outcome -> policy -> optional fallback -> runtime guard -> mutation 状态机。
 */
@Service
public class OrchestrationRuntimeDecisionService {

    private final OrchestrationDecisionService decisionService;
    private final DecisionPolicyService policyService;
    private final DecisionExecutorAdapter executorAdapter;
    private final DecisionPolicyRuleSet ruleSet;
    private final OrchestrationRuntimeStateService runtimeStateService;

    public OrchestrationRuntimeDecisionService(OrchestrationDecisionService decisionService,
                                               DecisionPolicyService policyService,
                                               DecisionExecutorAdapter executorAdapter,
                                               DecisionPolicyRuleSet ruleSet,
                                               OrchestrationRuntimeStateService runtimeStateService) {
        this.decisionService = requireDependency(decisionService, "decisionService");
        this.policyService = requireDependency(policyService, "policyService");
        this.executorAdapter = requireDependency(executorAdapter, "executorAdapter");
        // composition root 已提供唯一 normalized ruleSet；这里必须保留同一对象身份，禁止另建默认规则集。
        this.ruleSet = requireDependency(ruleSet, "ruleSet");
        this.runtimeStateService = requireDependency(runtimeStateService, "runtimeStateService");
    }

    /**
     * 执行一个完整运行期决策周期。Shadow 始终只保留在原 outcome，绝不进入 evaluate 或 final decisions。
     */
    public OrchestrationRuntimeDecisionBatch decide(OrchestrationContext rawContext,
                                                     String taskStatus,
                                                     String triggerNodeStatus) {
        if (rawContext == null || rawContext.getTaskId() == null) {
            throw new IllegalArgumentException("rawContext 与 taskId 不能为空");
        }
        OrchestrationRuntimeState state = runtimeStateService.load(rawContext.getTaskId());
        // checkpoint 不可读代表历史自动执行次数未知，按总额度已耗尽处理，防止损坏事件重新开放补图预算。
        int effectiveDecisionCount = state.checkpointStateStatus()
                == OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE
                ? ruleSet.getMaxAutoDecisions()
                : state.currentDecisionCount();
        OrchestrationContext context = rawContext.normalized().toBuilder()
                .taskStatus(taskStatus)
                .currentDecisionCount(effectiveDecisionCount)
                .build()
                .normalized();

        OrchestrationDecisionOutcome outcome = decisionService.decideWithOutcome(context);
        List<OrchestrationRuntimeDecision> primaryAttempts = evaluate(
                outcome.decisions(),
                context,
                state,
                triggerNodeStatus,
                false);
        OrchestrationRuntimeDecision rejectedLlm = firstRejectedLlm(primaryAttempts);
        if (rejectedLlm == null) {
            return batch(outcome, state, primaryAttempts, primaryAttempts, false, context.getSourceUrls());
        }

        // Policy rejection fallback 是整个周期的一次性替换：即使前面的 LLM candidate allowed，
        // 只要后续候选被拒，所有主 mutation 都不得进入 final，避免产生半批次副作用。
        OrchestrationDecisionOutcome fallbackOutcome = decisionService.fallbackAfterPolicyRejection(
                context,
                rejectedLlm.decision());
        List<OrchestrationRuntimeDecision> fallbackAttempts = evaluate(
                fallbackOutcome.decisions(),
                context,
                state,
                triggerNodeStatus,
                true);
        List<OrchestrationRuntimeDecision> allAttempts = new ArrayList<>(primaryAttempts);
        allAttempts.addAll(fallbackAttempts);
        return batch(outcome, state, allAttempts, fallbackAttempts, true, context.getSourceUrls());
    }

    private List<OrchestrationRuntimeDecision> evaluate(List<OrchestrationDecision> decisions,
                                                        OrchestrationContext context,
                                                        OrchestrationRuntimeState state,
                                                        String triggerNodeStatus,
                                                        boolean fallbackAttempt) {
        List<OrchestrationRuntimeDecision> results = new ArrayList<>();
        Map<String, Integer> effectiveSectionCounts =
                new LinkedHashMap<>(state.dynamicBranchCountsBySection());
        if (decisions == null) {
            return List.of();
        }
        for (OrchestrationDecision decision : decisions) {
            // outcome 契约已经禁止 shadow 混入主列表，这里仍保留防御校验，避免未来错误构造绕过执行边界。
            if (decision == null || decision.getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW) {
                throw new IllegalArgumentException("runtime evaluate 不能处理 null 或 LLM_SHADOW decision");
            }
            DecisionPolicyResult policyResult = policyService.evaluate(
                    decision,
                    ruleSet,
                    context.getCurrentDecisionCount(),
                    context.getTaskStatus(),
                    triggerNodeStatus);
            DynamicPlanMutation mutation = executorAdapter.toMutation(
                    decision,
                    policyResult,
                    state.currentPlanVersionId(),
                    state.nextPlanVersion());
            String runtimeStatus;
            if (policyResult.isAllowed() && "APPEND_NODES".equals(mutation.getMutationType())) {
                String sectionKey = OrchestrationRuntimeState.normalizeSectionKey(decision.getTargetSection());
                int currentSectionCount = effectiveSectionCounts.getOrDefault(sectionKey, 0);
                if (currentSectionCount >= ruleSet.getMaxDynamicBranchesPerSection()) {
                    // section guard 不是 Policy rejection，不触发 Rule fallback；它只把已允许的 APPEND
                    // 在真正执行前降级为可审计 NO_MUTATION。
                    mutation = executorAdapter.toNoMutation(
                            decision,
                            policyResult,
                            OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED);
                    runtimeStatus = OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED;
                } else {
                    // 同一 batch 的多个候选也要共享临时计数，防止一次返回多条同 section APPEND 绕过上限。
                    effectiveSectionCounts.put(sectionKey, incrementSafely(currentSectionCount));
                    runtimeStatus = OrchestrationRuntimeDecision.READY;
                }
            } else {
                runtimeStatus = resolveRuntimeStatus(policyResult, mutation);
            }
            results.add(new OrchestrationRuntimeDecision(
                    decision,
                    policyResult,
                    mutation,
                    fallbackAttempt,
                    runtimeStatus,
                    List.of()));
        }
        return List.copyOf(results);
    }

    private OrchestrationRuntimeDecision firstRejectedLlm(List<OrchestrationRuntimeDecision> attempts) {
        for (OrchestrationRuntimeDecision attempt : attempts) {
            if (attempt.decision().getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_PRIMARY
                    && !attempt.policyResult().isAllowed()) {
                return attempt;
            }
        }
        return null;
    }

    private String resolveRuntimeStatus(DecisionPolicyResult policyResult, DynamicPlanMutation mutation) {
        if (!policyResult.isAllowed()) {
            return OrchestrationRuntimeDecision.POLICY_REJECTED;
        }
        if (policyResult.isRequiresConfirmation()
                && "MARK_WAITING_INTERVENTION".equals(mutation.getMutationType())) {
            return OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED;
        }
        if ("NO_MUTATION".equals(mutation.getMutationType())) {
            return OrchestrationRuntimeDecision.NO_MUTATION;
        }
        return OrchestrationRuntimeDecision.READY;
    }

    private OrchestrationRuntimeDecisionBatch batch(OrchestrationDecisionOutcome outcome,
                                                    OrchestrationRuntimeState state,
                                                    List<OrchestrationRuntimeDecision> attempts,
                                                    List<OrchestrationRuntimeDecision> finalDecisions,
                                                    boolean fallbackUsed,
                                                    List<String> sourceUrls) {
        return new OrchestrationRuntimeDecisionBatch(
                outcome,
                state,
                attempts,
                finalDecisions,
                fallbackUsed,
                sourceUrls);
    }

    private int incrementSafely(int value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, value) + 1;
    }

    private <T> T requireDependency(T dependency, String name) {
        if (dependency == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return dependency;
    }
}
