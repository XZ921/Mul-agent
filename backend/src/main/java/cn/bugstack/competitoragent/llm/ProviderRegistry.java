package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 注册表。
 * <p>
 * 它只负责把 provider 配置映射到具体适配器，不承担路由策略，
 * 这样后续切换路由规则时不会影响适配器发现逻辑。
 */
@Component
@RequiredArgsConstructor
public class ProviderRegistry {

    private final AiProviderProperties aiProviderProperties;
    private final List<ModelProvider> modelProviders;

    /**
     * 根据 providerKey 解析出真正可调用的适配器和配置。
     */
    public RegisteredProvider resolve(String providerKey) {
        ModelProvider modelProvider = resolveAdapter(providerKey);
        AiProviderProperties.ProviderConfig providerConfig = resolveProviderConfig(providerKey);
        String adapterType = StringUtils.hasText(providerConfig.getAdapterType())
                ? providerConfig.getAdapterType()
                : "openai-compatible";
        return new RegisteredProvider(providerKey, providerConfig, adapterType, modelProvider.getClass().getSimpleName());
    }

    /**
     * 统一承接聊天能力分发。
     * <p>
     * 业务层和网关上层都不需要知道底层到底是哪一个 Provider 适配器，
     * 只需要把请求交给注册表，由注册表完成“配置 -> 适配器”的最后一跳绑定。
     */
    public ProviderInvocationResult<String> chat(String providerKey, ProviderInvocationRequest request) {
        return invoke(providerKey, request, ModelProvider::chat);
    }

    /**
     * 统一承接向量能力分发，避免上层继续感知具体 SDK 入口。
     */
    public ProviderInvocationResult<List<Float>> embed(String providerKey, ProviderInvocationRequest request) {
        return invoke(providerKey, request, ModelProvider::embed);
    }

    /**
     * 统一承接重排能力分发，作为后续治理策略的稳定挂载点。
     */
    public ProviderInvocationResult<List<RerankClient.RerankRecord>> rerank(String providerKey, ProviderInvocationRequest request) {
        return invoke(providerKey, request, ModelProvider::rerank);
    }

    private AiProviderProperties.ProviderConfig resolveProviderConfig(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            throw new IllegalArgumentException("providerKey 不能为空");
        }
        AiProviderProperties.ProviderConfig providerConfig = aiProviderProperties.getProviders() == null
                ? null
                : aiProviderProperties.getProviders().get(providerKey);
        if (providerConfig == null) {
            throw new IllegalStateException("未找到 provider 配置: " + providerKey);
        }
        return providerConfig;
    }

    /**
     * 适配器解析只保留在注册表内部，避免上层重新拿到具体 Provider 后绕开统一治理入口。
     */
    private ModelProvider resolveAdapter(String providerKey) {
        AiProviderProperties.ProviderConfig providerConfig = resolveProviderConfig(providerKey);
        String adapterType = StringUtils.hasText(providerConfig.getAdapterType())
                ? providerConfig.getAdapterType()
                : "openai-compatible";
        for (ModelProvider modelProvider : modelProviders) {
            if (adapterType.equalsIgnoreCase(modelProvider.getAdapterType())) {
                return modelProvider;
            }
        }
        throw new IllegalStateException("未找到 adapterType=" + adapterType + " 的 Provider 适配器");
    }

    /**
     * 三类能力都遵循相同的“先解析适配器、再执行调用”流程，
     * 抽成统一模板后可以避免未来补充新能力时再次把分发逻辑散开。
     */
    private <T> ProviderInvocationResult<T> invoke(String providerKey,
                                                   ProviderInvocationRequest request,
                                                   ProviderInvoker<T> providerInvoker) {
        return providerInvoker.invoke(resolveAdapter(providerKey), request);
    }

    /**
     * 按给定顺序批量解析 provider，保留路由策略生成的优先级。
     */
    public Map<String, RegisteredProvider> resolveInOrder(List<String> providerKeys) {
        Map<String, RegisteredProvider> resolvedProviders = new LinkedHashMap<>();
        if (providerKeys == null) {
            return resolvedProviders;
        }
        for (String providerKey : providerKeys) {
            resolvedProviders.put(providerKey, resolve(providerKey));
        }
        return resolvedProviders;
    }

    public record RegisteredProvider(String providerKey,
                                     AiProviderProperties.ProviderConfig providerConfig,
                                     String adapterType,
                                     String adapterBeanName) {
    }

    @FunctionalInterface
    private interface ProviderInvoker<T> {
        ProviderInvocationResult<T> invoke(ModelProvider modelProvider, ProviderInvocationRequest request);
    }
}
