package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路由策略。
 * <p>
 * 当前最小实现先提供一个清晰的统一挂载点：
 * 1. 优先走 activeProvider；
 * 2. 若 activeProvider 不支持指定能力，则回退到第一个声明了该能力的 provider。
 */
@Component
public class RoutingPolicy {

    private final AiProviderProperties aiProviderProperties;

    public RoutingPolicy(AiProviderProperties aiProviderProperties) {
        this.aiProviderProperties = aiProviderProperties;
    }

    /**
     * 为指定能力返回按优先级排序的候选 provider。
     */
    public RoutingDecision decide(AiCapability capability) {
        String capabilityKey = capabilityKey(capability);
        Map<String, AiProviderProperties.ProviderConfig> providers = aiProviderProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException("AI Provider 配置为空，无法完成路由");
        }

        List<String> supportedProviders = providers.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().getEndpoints() != null
                        && StringUtils.hasText(entry.getValue().getEndpoints().get(capabilityKey)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (supportedProviders.isEmpty()) {
            throw new IllegalStateException("没有 provider 支持能力: " + capability);
        }

        String activeProvider = aiProviderProperties.getActiveProvider();
        if (StringUtils.hasText(activeProvider) && supportedProviders.remove(activeProvider)) {
            supportedProviders.add(0, activeProvider);
        }
        return new RoutingDecision(capability, supportedProviders, Math.max(1, aiProviderProperties.getMaxRetries()));
    }

    /**
     * 网关成本估算需要读取统一预算单价，但仍然通过 RoutingPolicy 持有同一份配置，
     * 避免再给 ModelGateway 引入第二份 provider 配置依赖。
     */
    AiProviderProperties getAiProviderProperties() {
        return aiProviderProperties;
    }

    private String capabilityKey(AiCapability capability) {
        return switch (capability) {
            case CHAT -> "chat";
            case EMBEDDING -> "embedding";
            case RERANK -> "rerank";
        };
    }

    /**
     * 路由结果保持极简，只暴露网关执行业务真正需要的候选顺序。
     */
    public record RoutingDecision(AiCapability capability,
                                  List<String> candidateProviders,
                                  int maxRetries) {
    }
}
