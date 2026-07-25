package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import cn.bugstack.competitoragent.llm.ModelInvocationPurpose;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator 决策模式协调器。
 * 本服务只拥有上下文归一化、Brain 模式选择、shadow/fallback 复制和 typed outcome，
 * 不依赖 Policy、Executor、Trace 或运行时 DAG。
 */
@Service
public class OrchestrationDecisionService {

    public static final String SHADOW_DISABLED = "SHADOW_DISABLED";
    public static final String SHADOW_BUDGET_NOT_CONFIGURED = "SHADOW_BUDGET_NOT_CONFIGURED";
    public static final String SHADOW_BUDGET_EXHAUSTED = "SHADOW_BUDGET_EXHAUSTED";
    public static final String SHADOW_REQUEST_BUDGET_REJECTED = "SHADOW_REQUEST_BUDGET_REJECTED";

    private final RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain;
    private final LlmOrchestratorDecisionBrain llmDecisionBrain;
    private final OrchestratorDecisionProperties properties;
    private final OrchestratorFallbackReasonMapper fallbackReasonMapper;

    /** Spring 主构造器显式区分两个 Brain，禁止通过 @Primary 隐式选择实现。 */
    @Autowired
    public OrchestrationDecisionService(RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain,
                                        LlmOrchestratorDecisionBrain llmDecisionBrain,
                                        OrchestratorDecisionProperties properties,
                                        OrchestratorFallbackReasonMapper fallbackReasonMapper) {
        this.ruleBasedDecisionBrain = requireDependency(ruleBasedDecisionBrain, "ruleBasedDecisionBrain");
        this.llmDecisionBrain = requireDependency(llmDecisionBrain, "llmDecisionBrain");
        this.properties = requireDependency(properties, "properties");
        this.fallbackReasonMapper = requireDependency(fallbackReasonMapper, "fallbackReasonMapper");
        properties.validate();
    }

    /**
     * Task 05 以前的测试和 runtime 手工构造入口。
     * 该入口没有 LLM Brain，因此必须固定为 RULE_ONLY，不能根据外部配置隐式启用模型。
     */
    public OrchestrationDecisionService(RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain) {
        this.ruleBasedDecisionBrain = requireDependency(ruleBasedDecisionBrain, "ruleBasedDecisionBrain");
        this.llmDecisionBrain = null;
        this.properties = new OrchestratorDecisionProperties();
        this.properties.setMode(OrchestratorDecisionMode.RULE_ONLY);
        this.fallbackReasonMapper = new OrchestratorFallbackReasonMapper();
    }

    /**
     * 兼容入口只投影主 decisions。LLM_PRIMARY 关闭 fallback 时必须重新抛 typed failure，
     * 避免旧调用方把模型失败误判为“没有候选”。Shadow failure 则始终保留规则主结果并正常返回。
     */
    public List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
        OrchestrationDecisionOutcome outcome = decideWithOutcome(rawContext);
        if (outcome.mode() == OrchestratorDecisionMode.LLM_PRIMARY
                && outcome.llmFailure() != null
                && outcome.decisions().isEmpty()
                && !properties.isFallbackToRule()) {
            throw new LlmOrchestratorDecisionException(outcome.llmFailure());
        }
        return outcome.decisions();
    }

    /**
     * 每次调用只归一化一次上下文，并按显式 mode 进入唯一分支。
     * 所有运行状态均为方法局部变量，Service 不缓存上一次 outcome 或失败对象。
     */
    public OrchestrationDecisionOutcome decideWithOutcome(OrchestrationContext rawContext) {
        OrchestratorDecisionMode mode = properties.getMode();
        if (rawContext == null) {
            return new OrchestrationDecisionOutcome(
                    mode,
                    List.of(),
                    List.of(),
                    OrchestrationShadowExecution.notRequested(List.of()),
                    null,
                    List.of());
        }
        OrchestrationContext context = rawContext.normalized();
        if (isReviewerContext(context)) {
            // Reviewer 质量事实必须先由 Java 整轮归一为唯一 candidate。
            // 即使配置切换到 LLM_PRIMARY/SHADOW，也禁止模型再次生成另一组运行时动作。
            return ruleOnly(context);
        }
        return switch (mode) {
            case RULE_ONLY -> ruleOnly(context);
            case LLM_PRIMARY -> llmPrimary(context);
            case LLM_SHADOW -> llmShadow(context);
        };
    }

    private boolean isReviewerContext(OrchestrationContext context) {
        String nodeName = context == null ? null : context.getTriggerNodeName();
        return "quality_check".equals(nodeName)
                || "quality_check_final".equals(nodeName)
                || (nodeName != null && nodeName.startsWith("quality_check_revision"));
    }

    /**
     * Task 07 在 LLM_PRIMARY candidate 被 Policy 拒绝后可调用该入口一次。
     * 这里不重新调用 LLM 或 Policy，只复制 Rule Brain 结果并继承被拒绝候选的模型审计事实。
     */
    public OrchestrationDecisionOutcome fallbackAfterPolicyRejection(
            OrchestrationContext rawContext,
            OrchestrationDecision rejectedLlmDecision) {
        if (rawContext == null) {
            throw new IllegalArgumentException("rawContext 不能为空");
        }
        if (rejectedLlmDecision == null
                || rejectedLlmDecision.getDecisionOrigin() != OrchestrationDecisionOrigin.LLM_PRIMARY) {
            throw new IllegalArgumentException("policy rejected fallback 只接受 LLM_PRIMARY decision");
        }
        OrchestrationContext context = rawContext.normalized();
        List<OrchestrationDecision> ruleDecisions = safeDecisions(ruleBasedDecisionBrain.decide(context));
        List<OrchestrationDecision> fallbackDecisions = copyPolicyRejectedFallback(
                ruleDecisions,
                rejectedLlmDecision.getDecisionMetadata());
        return new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                fallbackDecisions,
                List.of(),
                OrchestrationShadowExecution.notRequested(context.getSourceUrls()),
                null,
                mergeSources(context.getSourceUrls(), rejectedLlmDecision.getSourceUrls()));
    }

    private OrchestrationDecisionOutcome ruleOnly(OrchestrationContext context) {
        List<OrchestrationDecision> decisions = safeDecisions(ruleBasedDecisionBrain.decide(context));
        return outcome(
                OrchestratorDecisionMode.RULE_ONLY,
                decisions,
                List.of(),
                OrchestrationShadowExecution.notRequested(context.getSourceUrls()),
                null,
                context.getSourceUrls());
    }

    private OrchestrationDecisionOutcome llmPrimary(OrchestrationContext context) {
        requireLlmBrain();
        String aiAuditTraceId = newAiAuditTraceId();
        try {
            List<OrchestrationDecision> decisions = invokeLlm(
                    context,
                    ModelInvocationPurpose.ORCHESTRATOR_PRIMARY,
                    GovernanceDefaults.MODEL_DAILY_BUDGET_KEY,
                    false,
                    aiAuditTraceId);
            return outcome(
                    OrchestratorDecisionMode.LLM_PRIMARY,
                    decisions,
                    List.of(),
                    OrchestrationShadowExecution.notRequested(context.getSourceUrls()),
                    null,
                    context.getSourceUrls());
        } catch (LlmOrchestratorDecisionException exception) {
            LlmOrchestratorDecisionFailure failure = exception.failure();
            if (!properties.isFallbackToRule()) {
                return outcome(
                        OrchestratorDecisionMode.LLM_PRIMARY,
                        List.of(),
                        List.of(),
                        OrchestrationShadowExecution.notRequested(context.getSourceUrls()),
                        failure,
                        context.getSourceUrls());
            }
            List<OrchestrationDecision> ruleDecisions = safeDecisions(ruleBasedDecisionBrain.decide(context));
            return outcome(
                    OrchestratorDecisionMode.LLM_PRIMARY,
                    copyLlmFailureFallback(ruleDecisions, failure, aiAuditTraceId),
                    List.of(),
                    OrchestrationShadowExecution.notRequested(context.getSourceUrls()),
                    failure,
                    context.getSourceUrls());
        }
    }

    private OrchestrationDecisionOutcome llmShadow(OrchestrationContext context) {
        List<OrchestrationDecision> ruleDecisions = safeDecisions(ruleBasedDecisionBrain.decide(context));
        if (!properties.getShadow().isEnabled()) {
            return outcome(
                    OrchestratorDecisionMode.LLM_SHADOW,
                    ruleDecisions,
                    List.of(),
                    OrchestrationShadowExecution.skipped(
                            SHADOW_DISABLED,
                            null,
                            context.getSourceUrls()),
                    null,
                    context.getSourceUrls());
        }

        requireLlmBrain();
        String aiAuditTraceId = newAiAuditTraceId();
        try {
            List<OrchestrationDecision> llmCandidates = invokeLlm(
                    context,
                    ModelInvocationPurpose.ORCHESTRATOR_SHADOW,
                    properties.getShadow().getIsolatedBudgetKey(),
                    properties.getShadow().isRequireActiveQuota(),
                    aiAuditTraceId);
            List<OrchestrationDecision> shadowCopies = copyShadowDecisions(llmCandidates);
            return outcome(
                    OrchestratorDecisionMode.LLM_SHADOW,
                    attachAiAuditTraceId(ruleDecisions, aiAuditTraceId),
                    shadowCopies,
                    OrchestrationShadowExecution.executed(null, context.getSourceUrls()),
                    null,
                    context.getSourceUrls());
        } catch (LlmOrchestratorDecisionException exception) {
            LlmOrchestratorDecisionFailure failure = exception.failure();
            String skippedReason = shadowBudgetSkippedReason(failure);
            OrchestrationShadowExecution shadowExecution = skippedReason == null
                    ? OrchestrationShadowExecution.executed(failure, context.getSourceUrls())
                    : OrchestrationShadowExecution.skipped(skippedReason, failure, context.getSourceUrls());
            return outcome(
                    OrchestratorDecisionMode.LLM_SHADOW,
                    attachAiAuditTraceId(ruleDecisions, aiAuditTraceId),
                    List.of(),
                    shadowExecution,
                    failure,
                    context.getSourceUrls());
        }
    }

    private List<OrchestrationDecision> invokeLlm(OrchestrationContext context,
                                                  ModelInvocationPurpose purpose,
                                                  String quotaKey,
                                                  boolean requireActiveQuota,
                                                  String aiAuditTraceId) {
        return ModelInvocationContextHolder.withContext(
                context.getTaskId(),
                context.getTriggerNodeName(),
                aiAuditTraceId,
                purpose,
                quotaKey,
                requireActiveQuota,
                false,
                () -> attachAiAuditTraceId(
                        safeDecisions(llmDecisionBrain.decide(context)),
                        aiAuditTraceId));
    }

    /**
     * Shadow copy 同时复制 decision 与 metadata，禁止修改 Task 05 Brain 返回的 LLM_PRIMARY 原对象。
     */
    private List<OrchestrationDecision> copyShadowDecisions(List<OrchestrationDecision> candidates) {
        List<OrchestrationDecision> copies = new ArrayList<>();
        for (OrchestrationDecision candidate : candidates) {
            requireDecision(candidate, "LLM shadow candidate");
            OrchestratorDecisionMetadata originalMetadata = candidate.getDecisionMetadata() == null
                    ? OrchestratorDecisionMetadata.empty()
                    : candidate.getDecisionMetadata();
            OrchestratorDecisionMetadata shadowMetadata = originalMetadata.toBuilder()
                    .shadowExecuted(true)
                    .shadowSkippedReason(null)
                    .fallbackUsed(false)
                    .fallbackReason(null)
                    .build();
            copies.add(candidate.toBuilder()
                    .decisionOrigin(OrchestrationDecisionOrigin.LLM_SHADOW)
                    .decisionMetadata(shadowMetadata)
                    .build());
        }
        return List.copyOf(copies);
    }

    /**
     * LLM 失败回退只覆盖 origin 和模型审计 metadata；规则产生的来源、证据、inputRefs 和业务动作保持不变。
     */
    private List<OrchestrationDecision> copyLlmFailureFallback(
            List<OrchestrationDecision> ruleDecisions,
            LlmOrchestratorDecisionFailure failure,
            String aiAuditTraceId) {
        LlmOrchestratorDecisionFailure.Attempt finalAttempt = finalAttempt(failure);
        List<OrchestrationDecision> copies = new ArrayList<>();
        for (OrchestrationDecision ruleDecision : ruleDecisions) {
            requireDecision(ruleDecision, "Rule fallback decision");
            OrchestratorDecisionMetadata metadata = OrchestratorDecisionMetadata.builder()
                    .temperature(properties.getModelTemperature())
                    .promptHash(finalAttempt == null ? null : finalAttempt.promptHash())
                    .llmResponseHash(finalAttempt == null ? null : finalAttempt.llmResponseHash())
                    .aiAuditTraceId(aiAuditTraceId)
                    .parseRetryCount(failure.parseRetryCount())
                    .fallbackUsed(true)
                    .fallbackReason(fallbackReasonMapper.map(failure))
                    .shadowExecuted(null)
                    .shadowSkippedReason(null)
                    .build();
            copies.add(ruleDecision.toBuilder()
                    .decisionOrigin(OrchestrationDecisionOrigin.RULE_FALLBACK)
                    .decisionMetadata(metadata)
                    .build());
        }
        return List.copyOf(copies);
    }

    /**
     * Coordinator 单一拥有 cycle 与 AI audit 的关联 ID；Brain、Policy 和 Trace 只透传该不可执行审计事实。
     */
    private List<OrchestrationDecision> attachAiAuditTraceId(
            List<OrchestrationDecision> decisions,
            String aiAuditTraceId) {
        List<OrchestrationDecision> copies = new ArrayList<>();
        for (OrchestrationDecision decision : safeDecisions(decisions)) {
            requireDecision(decision, "LLM cycle decision");
            OrchestratorDecisionMetadata metadata = decision.getDecisionMetadata() == null
                    ? OrchestratorDecisionMetadata.empty()
                    : decision.getDecisionMetadata();
            copies.add(decision.toBuilder()
                    .decisionMetadata(metadata.toBuilder()
                            .aiAuditTraceId(aiAuditTraceId)
                            .build())
                    .build()
                    .normalized());
        }
        return List.copyOf(copies);
    }

    /** `orch-` 加标准 UUID 共 41 字符，稳定低于 ai_call_audit_record.trace_id 的 50 字符上限。 */
    private String newAiAuditTraceId() {
        return "orch-" + UUID.randomUUID();
    }

    private List<OrchestrationDecision> copyPolicyRejectedFallback(
            List<OrchestrationDecision> ruleDecisions,
            OrchestratorDecisionMetadata rejectedMetadata) {
        OrchestratorDecisionMetadata sourceMetadata = rejectedMetadata == null
                ? OrchestratorDecisionMetadata.empty()
                : rejectedMetadata;
        List<OrchestrationDecision> copies = new ArrayList<>();
        for (OrchestrationDecision ruleDecision : ruleDecisions) {
            requireDecision(ruleDecision, "Policy fallback decision");
            OrchestratorDecisionMetadata metadata = sourceMetadata.toBuilder()
                    .fallbackUsed(true)
                    .fallbackReason(fallbackReasonMapper.policyRejected())
                    .shadowExecuted(null)
                    .shadowSkippedReason(null)
                    .build();
            copies.add(ruleDecision.toBuilder()
                    .decisionOrigin(OrchestrationDecisionOrigin.RULE_FALLBACK)
                    .decisionMetadata(metadata)
                    .build());
        }
        return List.copyOf(copies);
    }

    private String shadowBudgetSkippedReason(LlmOrchestratorDecisionFailure failure) {
        if (failure == null || failure.type() != LlmOrchestratorFailureType.LLM_ERROR) {
            return null;
        }
        String providerErrorCode = failure.providerErrorCode();
        if (providerErrorCode == null) {
            return null;
        }
        return switch (providerErrorCode) {
            case SHADOW_BUDGET_NOT_CONFIGURED, "BLOCKED_QUOTA_NOT_CONFIGURED" ->
                    SHADOW_BUDGET_NOT_CONFIGURED;
            case SHADOW_BUDGET_EXHAUSTED, "BLOCKED_QUOTA_EXCEEDED" ->
                    SHADOW_BUDGET_EXHAUSTED;
            case SHADOW_REQUEST_BUDGET_REJECTED -> SHADOW_REQUEST_BUDGET_REJECTED;
            default -> null;
        };
    }

    private LlmOrchestratorDecisionFailure.Attempt finalAttempt(LlmOrchestratorDecisionFailure failure) {
        List<LlmOrchestratorDecisionFailure.Attempt> attempts = failure.attempts();
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    private OrchestrationDecisionOutcome outcome(OrchestratorDecisionMode mode,
                                                 List<OrchestrationDecision> decisions,
                                                 List<OrchestrationDecision> shadowDecisions,
                                                 OrchestrationShadowExecution shadowExecution,
                                                 LlmOrchestratorDecisionFailure failure,
                                                 List<String> sourceUrls) {
        return new OrchestrationDecisionOutcome(
                mode,
                decisions,
                shadowDecisions,
                shadowExecution,
                failure,
                sourceUrls);
    }

    private List<OrchestrationDecision> safeDecisions(List<OrchestrationDecision> decisions) {
        return decisions == null ? List.of() : decisions;
    }

    private List<String> mergeSources(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        addSources(merged, first);
        addSources(merged, second);
        return List.copyOf(merged);
    }

    private void addSources(Set<String> merged, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                merged.add(value.trim());
            }
        }
    }

    private void requireLlmBrain() {
        if (llmDecisionBrain == null) {
            throw new IllegalStateException("当前 Service 未装配 LLM Brain，只能使用 RULE_ONLY");
        }
    }

    private void requireDecision(OrchestrationDecision decision, String owner) {
        if (decision == null) {
            throw new IllegalStateException(owner + " 不能为 null");
        }
    }

    private static <T> T requireDependency(T dependency, String name) {
        if (dependency == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return dependency;
    }
}
