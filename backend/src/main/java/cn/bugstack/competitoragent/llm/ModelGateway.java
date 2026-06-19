package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.llm.AIAuditLogger.AuditEvent;
import cn.bugstack.competitoragent.llm.BudgetGuard.BudgetCheckRequest;
import cn.bugstack.competitoragent.llm.BudgetGuard.BudgetCheckResult;
import cn.bugstack.competitoragent.llm.ProviderRegistry.RegisteredProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 正式统一模型网关入口。
 * <p>
 * 业务层只依赖它暴露的统一接口，从这里进入后再由路由、预算、熔断和审计策略统一治理。
 */
@Component
public class ModelGateway implements LlmClient, EmbeddingClient, RerankClient {

    private final ProviderRegistry providerRegistry;
    private final RoutingPolicy routingPolicy;
    private final CircuitBreakerPolicy circuitBreakerPolicy;
    private final BudgetGuard budgetGuard;
    private final AIAuditLogger aiAuditLogger;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;
    private final ThreadLocal<InvocationSnapshot> lastInvocation = new ThreadLocal<>();

    public ModelGateway(ProviderRegistry providerRegistry,
                        RoutingPolicy routingPolicy,
                        CircuitBreakerPolicy circuitBreakerPolicy,
                        BudgetGuard budgetGuard,
                        AIAuditLogger aiAuditLogger) {
        this(providerRegistry, routingPolicy, circuitBreakerPolicy, budgetGuard, aiAuditLogger, null);
    }

    /**
     * 统一网关在保留原有预算与熔断治理的同时，额外接入组织级模型额度判断。
     */
    @Autowired
    public ModelGateway(ProviderRegistry providerRegistry,
                        RoutingPolicy routingPolicy,
                        CircuitBreakerPolicy circuitBreakerPolicy,
                        BudgetGuard budgetGuard,
                        AIAuditLogger aiAuditLogger,
                        OrganizationQuotaPolicy organizationQuotaPolicy) {
        this.providerRegistry = providerRegistry;
        this.routingPolicy = routingPolicy;
        this.circuitBreakerPolicy = circuitBreakerPolicy;
        this.budgetGuard = budgetGuard;
        this.aiAuditLogger = aiAuditLogger;
        this.organizationQuotaPolicy = organizationQuotaPolicy;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ProviderInvocationResult<String> result = execute(
                AiCapability.CHAT,
                systemPrompt + "\n" + userPrompt,
                request -> providerRegistry.chat(request.providerKey(), ProviderInvocationRequest.builder()
                        .providerKey(request.providerKey())
                        .providerConfig(request.providerConfig())
                        .capability(AiCapability.CHAT)
                        .modelName(null)
                        .systemPrompt(systemPrompt)
                        .userPrompt(userPrompt)
                        .build())
        );
        return result.getPayload();
    }

    @Override
    public String chatForJson(String systemPrompt, String userPrompt, String responseSchema) {
        String enhancedSystemPrompt = systemPrompt
                + "\n\n【重要】请只输出 JSON，不要包含 markdown 代码块标记或其他解释文字。\n"
                + "期望的 JSON 结构: " + responseSchema;
        return chat(enhancedSystemPrompt, userPrompt);
    }

    @Override
    public String getModelName() {
        InvocationSnapshot snapshot = lastInvocation.get();
        return snapshot == null ? null : snapshot.modelName();
    }

    @Override
    public TokenUsage getLastTokenUsage() {
        InvocationSnapshot snapshot = lastInvocation.get();
        return snapshot == null || snapshot.tokenUsage() == null ? TokenUsage.builder().build() : snapshot.tokenUsage();
    }

    @Override
    public List<Float> embed(String text) {
        ProviderInvocationResult<List<Float>> result = execute(
                AiCapability.EMBEDDING,
                text,
                request -> providerRegistry.embed(request.providerKey(), ProviderInvocationRequest.builder()
                        .providerKey(request.providerKey())
                        .providerConfig(request.providerConfig())
                        .capability(AiCapability.EMBEDDING)
                        .modelName(null)
                        .text(text)
                        .build())
        );
        return result.getPayload();
    }

    @Override
    public List<RerankRecord> rerank(String query, List<String> documents) {
        ProviderInvocationResult<List<RerankRecord>> result = execute(
                AiCapability.RERANK,
                query,
                request -> providerRegistry.rerank(request.providerKey(), ProviderInvocationRequest.builder()
                        .providerKey(request.providerKey())
                        .providerConfig(request.providerConfig())
                        .capability(AiCapability.RERANK)
                        .modelName(null)
                        .query(query)
                        .documents(documents == null ? List.of() : documents)
                        .build())
        );
        return result.getPayload();
    }

    /**
     * 当前最小实现只走首选 provider，
     * 但所有治理检查都已经在统一入口就位，后续可以继续叠加重试与故障转移而不改业务调用点。
     */
    private <T> ProviderInvocationResult<T> execute(AiCapability capability,
                                                    String inputSummary,
                                                    ProviderInvoker<T> providerInvoker) {
        RoutingPolicy.RoutingDecision routingDecision = routingPolicy.decide(capability);
        ModelInvocationContextHolder.ModelInvocationContext invocationContext = ModelInvocationContextHolder.get();
        if (routingDecision.candidateProviders().isEmpty()) {
            throw new LlmException("没有可用的 AI Provider 路由结果", "NO_ROUTE_RESULT");
        }

        RuntimeException lastException = null;
        boolean organizationQuotaChecked = false;
        int providerIndex = 0;
        for (String providerKey : routingDecision.candidateProviders()) {
            providerIndex++;
            RegisteredProvider registeredProvider = providerRegistry.resolve(providerKey);
            if (circuitBreakerPolicy.isOpen(providerKey, capability)) {
                lastException = new LlmException("Provider 当前处于熔断状态: " + providerKey, "CIRCUIT_OPEN");
                aiAuditLogger.record(baseAuditEvent(invocationContext, capability, providerKey)
                        .retryCount(0)
                        .fallbackUsed(providerIndex > 1)
                        .success(false)
                        .budgetDecision("CIRCUIT_OPEN")
                        .providerErrorCode(resolveProviderErrorCode(lastException))
                        .degradationCount(Math.max(0, providerIndex - 1))
                        .summary("统一网关跳过当前 Provider：熔断窗口尚未恢复。")
                        .build());
                continue;
            }

            BudgetCheckResult budgetCheckResult = budgetGuard.check(BudgetCheckRequest.builder()
                    .capability(capability)
                    .providerKey(providerKey)
                    .inputSummary(inputSummary)
                    .build());
            if (!budgetCheckResult.isAllowed()) {
                aiAuditLogger.record(baseAuditEvent(invocationContext, capability, providerKey)
                        .retryCount(0)
                        .fallbackUsed(providerIndex > 1)
                        .success(false)
                        .estimatedInputTokens(resolveEstimatedInputTokens(budgetCheckResult))
                        .estimatedCost(resolveEstimatedCost(budgetCheckResult))
                        .budgetDecision(firstNonBlank(budgetCheckResult.getBudgetDecision(), "BUDGET_BLOCKED"))
                        .degradationCount(Math.max(0, providerIndex - 1))
                        .summary(firstNonBlank(budgetCheckResult.getBudgetSummary(), budgetCheckResult.getReason()))
                        .build());
                throw new LlmException(budgetCheckResult.getReason());
            }
            if (!organizationQuotaChecked) {
                organizationQuotaChecked = true;
                ensureOrganizationModelQuotaAllowed(invocationContext, capability, providerKey, providerIndex, budgetCheckResult);
            }

            for (int attempt = 1; attempt <= routingDecision.maxRetries(); attempt++) {
                try {
                    ProviderInvocationResult<T> result = providerInvoker.invoke(new ProviderExecutionRequest(
                            providerKey,
                            registeredProvider.providerConfig()
                    ));
                    circuitBreakerPolicy.recordSuccess(providerKey, capability);
                    rememberInvocation(result);
                    aiAuditLogger.record(baseAuditEvent(invocationContext, capability, providerKey)
                            .modelName(result.getModelName())
                            .retryCount(attempt)
                            .fallbackUsed(providerIndex > 1)
                            .success(true)
                            .tokenUsage(result.getTokenUsage())
                            .estimatedInputTokens(resolveEstimatedInputTokens(budgetCheckResult))
                            .estimatedCost(resolveSuccessfulCostEstimate(result, budgetCheckResult))
                            .budgetDecision(resolveSuccessDecision(providerIndex, attempt, budgetCheckResult))
                            .degradationCount(calculateDegradationCount(providerIndex, attempt))
                            .summary(buildSuccessSummary(providerIndex, attempt))
                            .build());
                    return result;
                } catch (RuntimeException e) {
                    lastException = e;
                    circuitBreakerPolicy.recordFailure(providerKey, capability);
                    aiAuditLogger.record(baseAuditEvent(invocationContext, capability, providerKey)
                            .retryCount(attempt)
                            .fallbackUsed(providerIndex > 1)
                            .success(false)
                            .estimatedInputTokens(resolveEstimatedInputTokens(budgetCheckResult))
                            .estimatedCost(resolveEstimatedCost(budgetCheckResult))
                            .budgetDecision("PROVIDER_INVOCATION_FAILED")
                            .providerErrorCode(resolveProviderErrorCode(e))
                            .degradationCount(calculateDegradationCount(providerIndex, attempt))
                            .summary(buildFailureSummary(providerIndex, attempt, routingDecision.maxRetries(), e))
                            .build());
                }
            }
        }

        throw lastException == null ? new LlmException("所有 AI Provider 都不可用") : lastException;
    }

    /**
     * 组织级模型额度判断要放在真正调用 Provider 前，但要晚于本地预算守卫，
     * 这样既能保留原有预算阻断语义，也能避免一次调用在多 Provider 回退时重复占位。
     */
    private void ensureOrganizationModelQuotaAllowed(ModelInvocationContextHolder.ModelInvocationContext invocationContext,
                                                     AiCapability capability,
                                                     String providerKey,
                                                     int providerIndex,
                                                     BudgetCheckResult budgetCheckResult) {
        if (organizationQuotaPolicy == null) {
            return;
        }
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.MODEL_SCOPE,
                GovernanceDefaults.MODEL_DAILY_BUDGET_KEY,
                Math.max(1, resolveEstimatedInputTokens(budgetCheckResult)),
                List.of()
        );
        if (decision == null || decision.isAllowed()) {
            return;
        }
        aiAuditLogger.record(baseAuditEvent(invocationContext, capability, providerKey)
                .retryCount(0)
                .fallbackUsed(providerIndex > 1)
                .success(false)
                .estimatedInputTokens(resolveEstimatedInputTokens(budgetCheckResult))
                .estimatedCost(resolveEstimatedCost(budgetCheckResult))
                .budgetDecision(firstNonBlank(decision.getDecisionCode(), "GOVERNANCE_BLOCKED"))
                .degradationCount(Math.max(0, providerIndex - 1))
                .summary(firstNonBlank(decision.getSummary(), "组织级模型额度阻断当前调用"))
                .build());
        throw new GovernanceBlockException(decision);
    }

    private void rememberInvocation(ProviderInvocationResult<?> result) {
        lastInvocation.set(new InvocationSnapshot(result.getModelName(), result.getTokenUsage()));
    }

    /**
     * 用户与审计面看到的应该是治理结果摘要，而不是底层 SDK 细节。
     */
    private String buildSuccessSummary(int providerIndex, int attempt) {
        if (providerIndex == 1 && attempt == 1) {
            return "统一网关调用成功";
        }
        if (providerIndex == 1) {
            return "统一网关调用成功，主 Provider 经重试后恢复";
        }
        return "统一网关调用成功，已切换到备用 Provider";
    }

    /**
     * 失败摘要明确说明当前处于“重试中”还是“准备故障转移”，
     * 让后续审计与回放可以解释网关为何进入降级路径。
     */
    private String buildFailureSummary(int providerIndex, int attempt, int maxRetries, RuntimeException exception) {
        String errorCode = resolveProviderErrorCode(exception);
        String suffix = errorCode == null || errorCode.isBlank() ? "" : "，错误码=" + errorCode;
        if (attempt < maxRetries) {
            return "统一网关调用失败，第 " + attempt + " 次尝试未成功，系统将继续重试" + suffix;
        }
        if (providerIndex == 1) {
            return "统一网关调用失败，主 Provider 重试耗尽，准备尝试备用 Provider" + suffix;
        }
        return "统一网关调用失败，备用 Provider 也未成功" + suffix;
    }

    private AIAuditLogger.AuditEvent.AuditEventBuilder baseAuditEvent(ModelInvocationContextHolder.ModelInvocationContext invocationContext,
                                                                      AiCapability capability,
                                                                      String providerKey) {
        return AuditEvent.builder()
                .taskId(invocationContext == null ? null : invocationContext.taskId())
                .nodeName(invocationContext == null ? null : invocationContext.nodeName())
                .traceId(invocationContext == null ? null : invocationContext.traceId())
                .capability(capability)
                .providerKey(providerKey);
    }

    private Integer resolveEstimatedInputTokens(BudgetCheckResult budgetCheckResult) {
        return budgetCheckResult == null || budgetCheckResult.getEstimatedInputTokens() == null
                ? 0
                : budgetCheckResult.getEstimatedInputTokens();
    }

    private Double resolveEstimatedCost(BudgetCheckResult budgetCheckResult) {
        return budgetCheckResult == null || budgetCheckResult.getEstimatedCost() == null
                ? 0D
                : budgetCheckResult.getEstimatedCost();
    }

    /**
     * 成功路径优先使用实际 tokenUsage 做成本估算；
     * 如果当前 Provider 未返回 tokenUsage，则回退到预算守卫的调用前估算结果。
     */
    private Double resolveSuccessfulCostEstimate(ProviderInvocationResult<?> result, BudgetCheckResult budgetCheckResult) {
        if (result == null || result.getTokenUsage() == null) {
            return resolveEstimatedCost(budgetCheckResult);
        }
        int inputTokens = result.getTokenUsage().getInputTokens();
        int outputTokens = result.getTokenUsage().getOutputTokens();
        if (inputTokens <= 0 && outputTokens <= 0) {
            return resolveEstimatedCost(budgetCheckResult);
        }
        double rawCost = inputTokens / 1000D * routingPolicy.getAiProviderProperties().getBudgetInputCostPerThousandTokens()
                + outputTokens / 1000D * routingPolicy.getAiProviderProperties().getBudgetOutputCostPerThousandTokens();
        if (rawCost <= 0D) {
            return resolveEstimatedCost(budgetCheckResult);
        }
        return Math.round(rawCost * 10000D) / 10000D;
    }

    private String resolveSuccessDecision(int providerIndex, int attempt, BudgetCheckResult budgetCheckResult) {
        if (providerIndex > 1) {
            return "FALLBACK_RECOVERED";
        }
        if (attempt > 1) {
            return "RETRY_RECOVERED";
        }
        return firstNonBlank(budgetCheckResult == null ? null : budgetCheckResult.getBudgetDecision(), "ALLOWED");
    }

    private int calculateDegradationCount(int providerIndex, int attempt) {
        return Math.max(0, providerIndex - 1) + Math.max(0, attempt - 1);
    }

    private String resolveProviderErrorCode(RuntimeException exception) {
        if (exception instanceof LlmException llmException && llmException.getProviderErrorCode() != null) {
            return llmException.getProviderErrorCode();
        }
        return null;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private record InvocationSnapshot(String modelName, TokenUsage tokenUsage) {
    }

    private record ProviderExecutionRequest(String providerKey,
                                            cn.bugstack.competitoragent.config.AiProviderProperties.ProviderConfig providerConfig) {
    }

    @FunctionalInterface
    private interface ProviderInvoker<T> {
        ProviderInvocationResult<T> invoke(ProviderExecutionRequest request);
    }
}
