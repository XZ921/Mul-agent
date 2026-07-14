package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.llm.AiCapability;
import cn.bugstack.competitoragent.llm.BudgetGuard;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import cn.bugstack.competitoragent.llm.ModelInvocationPurpose;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shadow 模型调用的提交前预算守卫。
 * 本组件只做本地 token 估算和组织额度预留，绝不调用 ModelGateway、Provider 或 Trace。
 */
@Component
public class OrchestrationShadowBudgetGate {

    public static final String SHADOW_REQUEST_BUDGET_REJECTED = "SHADOW_REQUEST_BUDGET_REJECTED";
    private static final String ALLOWED_RESERVED = "ALLOWED_RESERVED";

    private final BudgetGuard budgetGuard;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;
    private final OrchestratorDecisionProperties properties;
    private final boolean enabled;

    public OrchestrationShadowBudgetGate(BudgetGuard budgetGuard,
                                         OrganizationQuotaPolicy organizationQuotaPolicy,
                                         OrchestratorDecisionProperties properties) {
        this(budgetGuard, organizationQuotaPolicy, properties, true);
    }

    private OrchestrationShadowBudgetGate(BudgetGuard budgetGuard,
                                          OrganizationQuotaPolicy organizationQuotaPolicy,
                                          OrchestratorDecisionProperties properties,
                                          boolean enabled) {
        this.budgetGuard = budgetGuard;
        this.organizationQuotaPolicy = organizationQuotaPolicy;
        this.properties = properties;
        this.enabled = enabled;
    }

    /** 仅供旧 Invoker 构造器保持 Task 05 行为，不注册为 Spring Bean。 */
    static OrchestrationShadowBudgetGate noop() {
        return new OrchestrationShadowBudgetGate(null, null, null, false);
    }

    /**
     * Gate 必须在 caller thread 和 executor.submit 之前运行。
     * 非 shadow purpose 不做任何预算调用，确保现有 Agent/primary 链路完全兼容。
     */
    public OrchestrationShadowBudgetAdmission checkAndReserve(
            OrchestrationDecisionPrompt prompt,
            ModelInvocationContextHolder.ModelInvocationContext context) {
        if (!enabled || context == null || context.purpose() != ModelInvocationPurpose.ORCHESTRATOR_SHADOW) {
            return OrchestrationShadowBudgetAdmission.notApplicable();
        }
        if (prompt == null) {
            throw new IllegalArgumentException("shadow prompt 不能为空");
        }

        BudgetGuard.BudgetCheckResult budgetDecision = budgetGuard.check(
                BudgetGuard.BudgetCheckRequest.builder()
                        .capability(AiCapability.CHAT)
                        .providerKey("orchestrator-shadow")
                        .inputSummary(buildInputSummary(prompt))
                        .build());
        if (budgetDecision == null || !budgetDecision.isAllowed()) {
            return new OrchestrationShadowBudgetAdmission(
                    false,
                    SHADOW_REQUEST_BUDGET_REJECTED,
                    0,
                    List.of());
        }

        int requestedUnits = Math.max(1,
                budgetDecision.getEstimatedInputTokens() == null
                        ? 0
                        : budgetDecision.getEstimatedInputTokens());
        QuotaDecision quotaDecision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.MODEL_SCOPE,
                context.quotaKey(),
                requestedUnits,
                List.of(),
                context.requireActiveQuota());
        if (quotaDecision == null) {
            return new OrchestrationShadowBudgetAdmission(
                    false,
                    "BLOCKED_QUOTA_NOT_CONFIGURED",
                    0,
                    List.of());
        }
        List<String> sourceUrls = quotaDecision.getSourceUrls() == null
                ? List.of()
                : quotaDecision.getSourceUrls();
        if (!quotaDecision.isAllowed()) {
            return new OrchestrationShadowBudgetAdmission(
                    false,
                    quotaDecision.getDecisionCode(),
                    0,
                    sourceUrls);
        }
        int reservedUnits = ALLOWED_RESERVED.equals(quotaDecision.getDecisionCode()) ? requestedUnits : 0;
        return new OrchestrationShadowBudgetAdmission(
                true,
                quotaDecision.getDecisionCode(),
                reservedUnits,
                sourceUrls);
    }

    /**
     * 只补偿尚未进入 worker 的预留；release 的幂等 owner 在 Invoker 的 reservation handle。
     */
    public void release(OrchestrationShadowBudgetAdmission admission) {
        if (!enabled || admission == null || !admission.reserved()) {
            return;
        }
        properties.validate();
        organizationQuotaPolicy.releaseReservation(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.MODEL_SCOPE,
                properties.getShadow().getIsolatedBudgetKey(),
                admission.reservedUnits(),
                admission.sourceUrls());
    }

    private String buildInputSummary(OrchestrationDecisionPrompt prompt) {
        return safe(prompt.systemPrompt()) + "\n" + safe(prompt.userPrompt()) + "\n" + safe(prompt.responseSchema());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
