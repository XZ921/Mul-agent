package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelGatewayTest {

    private final ModelProvider modelProvider = mock(ModelProvider.class);
    private final BudgetGuard budgetGuard = mock(BudgetGuard.class);
    private final AIAuditLogger aiAuditLogger = mock(AIAuditLogger.class);
    private final OrganizationQuotaPolicy organizationQuotaPolicy = mock(OrganizationQuotaPolicy.class);

    @Test
    void shouldRouteChatEmbeddingAndRerankThroughUnifiedGateway() {
        // Task 5.1.a 的最小闭环要求是：业务层不再直接持有 Provider，
        // 而是统一通过 ModelGateway 收口三类能力调用。
        AiProviderProperties properties = buildProperties();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow());
        when(modelProvider.chat(any())).thenReturn(ProviderInvocationResult.<String>builder()
                .providerKey("deepseek")
                .modelName("deepseek-chat")
                .tokenUsage(new TokenUsage(10, 20, 30))
                .payload("统一聊天结果")
                .build());
        when(modelProvider.embed(any())).thenReturn(ProviderInvocationResult.<List<Float>>builder()
                .providerKey("deepseek")
                .modelName("deepseek-embedding")
                .tokenUsage(new TokenUsage(4, 0, 4))
                .payload(List.of(0.1F, 0.2F))
                .build());
        when(modelProvider.rerank(any())).thenReturn(ProviderInvocationResult.<List<RerankClient.RerankRecord>>builder()
                .providerKey("deepseek")
                .modelName("deepseek-rerank")
                .tokenUsage(new TokenUsage(6, 0, 6))
                .payload(List.of(new RerankClient.RerankRecord(1, 0.98D)))
                .build());

        ModelGateway modelGateway = new ModelGateway(
                new ProviderRegistry(properties, List.of(modelProvider)),
                new RoutingPolicy(properties),
                new CircuitBreakerPolicy(properties),
                budgetGuard,
                aiAuditLogger
        );

        assertEquals("统一聊天结果", modelGateway.chat("system", "user"));
        assertEquals(List.of(0.1F, 0.2F), modelGateway.embed("enterprise governance"));
        assertEquals(1, modelGateway.rerank("governance query", List.of("doc-a", "doc-b")).size());
        assertEquals("deepseek-rerank", modelGateway.getModelName());
        assertEquals(6, modelGateway.getLastTokenUsage().getTotalTokens());

        verify(modelProvider).chat(any());
        verify(modelProvider).embed(any());
        verify(modelProvider).rerank(any());
    }

    @Test
    void shouldRetryPrimaryProviderAndFallbackToSecondaryProviderWhenChatFails() {
        // 网关治理层必须把“重试 + 故障转移”从业务层抽离出来，
        // 否则每个 Agent 都会自己处理供应商异常，形成第二套灰色调用路径。
        AiProviderProperties properties = buildPropertiesWithFallback();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow());
        when(modelProvider.chat(argThat(request -> request != null && "deepseek".equals(request.getProviderKey()))))
                .thenThrow(new LlmException("primary timeout"))
                .thenThrow(new LlmException("primary timeout"));
        when(modelProvider.chat(argThat(request -> request != null && "siliconflow".equals(request.getProviderKey()))))
                .thenReturn(ProviderInvocationResult.<String>builder()
                        .providerKey("siliconflow")
                        .modelName("siliconflow-chat")
                        .tokenUsage(new TokenUsage(12, 18, 30))
                        .payload("备用供应商结果")
                        .build());

        ModelGateway modelGateway = new ModelGateway(
                new ProviderRegistry(properties, List.of(modelProvider)),
                new RoutingPolicy(properties),
                new CircuitBreakerPolicy(properties),
                budgetGuard,
                aiAuditLogger
        );

        assertEquals("备用供应商结果", modelGateway.chat("system", "user"));
        verify(modelProvider, times(2)).chat(argThat(request -> request != null && "deepseek".equals(request.getProviderKey())));
        verify(modelProvider, times(1)).chat(argThat(request -> request != null && "siliconflow".equals(request.getProviderKey())));
    }

    @Test
    void shouldAttachTaskAndNodeContextToAuditEvent() {
        // Task / Node 维度是正式治理查询和回放的最小定位单元，
        // 网关必须自动带上运行上下文，不能要求每个业务调用点手工拼审计字段。
        AiProviderProperties properties = buildProperties();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow());
        when(modelProvider.chat(any())).thenReturn(ProviderInvocationResult.<String>builder()
                .providerKey("deepseek")
                .modelName("deepseek-chat")
                .tokenUsage(new TokenUsage(10, 20, 30))
                .payload("统一聊天结果")
                .build());
        ModelGateway modelGateway = new ModelGateway(
                new ProviderRegistry(properties, List.of(modelProvider)),
                new RoutingPolicy(properties),
                new CircuitBreakerPolicy(properties),
                budgetGuard,
                aiAuditLogger
        );

        ModelInvocationContextHolder.withContext(301L, "write_report", "trace-301", () ->
                modelGateway.chat("system", "user")
        );

        verify(aiAuditLogger).record(argThat(event -> event != null
                && Long.valueOf(301L).equals(event.getTaskId())
                && "write_report".equals(event.getNodeName())
                && "trace-301".equals(event.getTraceId())));
    }

    @Test
    void shouldRecordBudgetBlockAndStopProviderInvocation() {
        // 超预算必须是明确、可回放的治理阻断事件，
        // 不能只抛一个异常然后让调用方自己猜测到底是模型坏了还是预算被拦截了。
        AiProviderProperties properties = buildProperties();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.deny(
                "预算阻断：预计输入 Token=99，已超过限制 5",
                99,
                0.49D,
                "BLOCKED_ESTIMATED_INPUT_TOKENS",
                "预算阻断：预计输入 Token=99，已超过限制 5"
        ));
        ModelGateway modelGateway = new ModelGateway(
                new ProviderRegistry(properties, List.of(modelProvider)),
                new RoutingPolicy(properties),
                new CircuitBreakerPolicy(properties),
                budgetGuard,
                aiAuditLogger
        );

        assertThrows(LlmException.class, () -> ModelInvocationContextHolder.withContext(401L, "write_report", "trace-401", () ->
                modelGateway.chat("system", "user")
        ));

        verify(modelProvider, never()).chat(any());
        verify(aiAuditLogger).record(argThat(event -> event != null
                && !event.isSuccess()
                && Long.valueOf(401L).equals(event.getTaskId())
                && event.getSummary().contains("预算阻断")));
    }

    @Test
    void shouldRecordFailedAttemptBeforeFallingBackToSecondaryProvider() {
        // 主 Provider 失败后，即便备用 Provider 最终成功，
        // 失败尝试本身也必须形成正式审计记录，后续才能解释“为什么发生了降级”。
        AiProviderProperties properties = buildPropertiesWithFallback();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow());
        when(modelProvider.chat(argThat(request -> request != null && "deepseek".equals(request.getProviderKey()))))
                .thenThrow(new LlmException("primary timeout", "HTTP_504"));
        when(modelProvider.chat(argThat(request -> request != null && "siliconflow".equals(request.getProviderKey()))))
                .thenReturn(ProviderInvocationResult.<String>builder()
                        .providerKey("siliconflow")
                        .modelName("siliconflow-chat")
                        .tokenUsage(new TokenUsage(12, 18, 30))
                        .payload("备用供应商结果")
                        .build());

        ModelGateway modelGateway = new ModelGateway(
                new ProviderRegistry(properties, List.of(modelProvider)),
                new RoutingPolicy(properties),
                new CircuitBreakerPolicy(properties),
                budgetGuard,
                aiAuditLogger
        );

        assertEquals("备用供应商结果", ModelInvocationContextHolder.withContext(501L, "write_report", "trace-501", () ->
                modelGateway.chat("system", "user")
        ));

        verify(aiAuditLogger, times(3)).record(any());
        verify(aiAuditLogger).record(argThat(event -> event != null
                && !event.isSuccess()
                && "deepseek".equals(event.getProviderKey())
                && "HTTP_504".equals(event.getProviderErrorCode())
                && Integer.valueOf(1).equals(event.getRetryCount())));
        verify(aiAuditLogger).record(argThat(event -> event != null
                && event.isSuccess()
                && "siliconflow".equals(event.getProviderKey())
                && event.isFallbackUsed()
                && Integer.valueOf(1).equals(event.getDegradationCount())));
    }

    @Test
    void shouldExposeStructuredGovernanceDecisionWhenOrganizationModelQuotaBlocksInvocation() throws Exception {
        // Task 5.8.c 要求模型调用链路在组织级预算不足时返回统一治理结果，
        // 不能只抛一个通用 LLM 异常让上层自己猜测是配额问题还是 Provider 故障。
        AiProviderProperties properties = buildProperties();
        when(modelProvider.getAdapterType()).thenReturn("openai-compatible");
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow(
                32,
                0.16D,
                "ALLOWED",
                "预算守卫允许本次调用"
        ));
        when(modelProvider.chat(any())).thenReturn(ProviderInvocationResult.<String>builder()
                .providerKey("deepseek")
                .modelName("deepseek-chat")
                .tokenUsage(new TokenUsage(10, 12, 22))
                .payload("正常模型返回")
                .build());
        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.deny(
                        "BLOCKED_QUOTA_EXCEEDED",
                        "当前组织模型预算不足，请缩短输入或稍后重试",
                        "default-organization",
                        "MODEL",
                        "MODEL_DAILY_BUDGET",
                        32,
                        0,
                        null,
                        List.of("https://ops.example.com/quota/model-budget")
                ));

        ModelGateway modelGateway = instantiateModelGatewayWithOptionalGovernance(properties);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                ModelInvocationContextHolder.withContext(601L, "write_report", "trace-601", () ->
                        modelGateway.chat("system", "user"))
        );

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("BLOCKED_QUOTA_EXCEEDED", readAccessor(decision, "decisionCode"));
        assertEquals("MODEL_DAILY_BUDGET", readAccessor(decision, "quotaKey"));
        verify(modelProvider, never()).chat(any());
    }

    private AiProviderProperties buildProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setActiveProvider("deepseek");
        properties.setModelName("deepseek-chat");
        properties.setProviders(Map.of(
                "deepseek",
                providerConfig("openai-compatible", "https://api.deepseek.com", Map.of(
                        "chat", "/v1/chat/completions",
                        "embedding", "/v1/embeddings",
                        "rerank", "/v1/rerank"
                ))
        ));
        return properties;
    }

    private AiProviderProperties buildPropertiesWithFallback() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setActiveProvider("deepseek");
        properties.setModelName("deepseek-chat");
        properties.setMaxRetries(2);
        properties.setProviders(Map.of(
                "deepseek",
                providerConfig("openai-compatible", "https://api.deepseek.com", Map.of(
                        "chat", "/v1/chat/completions"
                )),
                "siliconflow",
                providerConfig("openai-compatible", "https://api.siliconflow.cn", Map.of(
                        "chat", "/v1/chat/completions"
                ))
        ));
        return properties;
    }

    private AiProviderProperties.ProviderConfig providerConfig(String adapterType,
                                                               String url,
                                                               Map<String, String> endpoints) {
        AiProviderProperties.ProviderConfig providerConfig = new AiProviderProperties.ProviderConfig();
        providerConfig.setAdapterType(adapterType);
        providerConfig.setUrl(url);
        providerConfig.setApiKey("test-key");
        providerConfig.setEndpoints(endpoints);
        return providerConfig;
    }

    /**
     * 兼容 5.8.c 前后的构造器形态，确保 Red 阶段先暴露“治理接入尚未落地”的真实缺口。
     */
    private ModelGateway instantiateModelGatewayWithOptionalGovernance(AiProviderProperties properties) throws Exception {
        try {
            Constructor<ModelGateway> constructor = ModelGateway.class.getConstructor(
                    ProviderRegistry.class,
                    RoutingPolicy.class,
                    CircuitBreakerPolicy.class,
                    BudgetGuard.class,
                    AIAuditLogger.class,
                    OrganizationQuotaPolicy.class
            );
            return constructor.newInstance(
                    new ProviderRegistry(properties, List.of(modelProvider)),
                    new RoutingPolicy(properties),
                    new CircuitBreakerPolicy(properties),
                    budgetGuard,
                    aiAuditLogger,
                    organizationQuotaPolicy
            );
        } catch (NoSuchMethodException ignored) {
            return new ModelGateway(
                    new ProviderRegistry(properties, List.of(modelProvider)),
                    new RoutingPolicy(properties),
                    new CircuitBreakerPolicy(properties),
                    budgetGuard,
                    aiAuditLogger
            );
        }
    }

    private Object readAccessor(Object target, String accessorName) {
        Method method = ReflectionUtils.findMethod(target.getClass(),
                "get" + Character.toUpperCase(accessorName.charAt(0)) + accessorName.substring(1));
        org.junit.jupiter.api.Assertions.assertNotNull(method,
                () -> "缺少访问器：" + target.getClass().getSimpleName() + "." + accessorName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }
}
