package cn.bugstack.competitoragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AI 提供商配置属性 — 绑定 ai.* 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProviderProperties {

    /** 当前激活的提供商名称（对应 providers 下的 key） */
    private String activeProvider;

    /** 模型名称 */
    private String modelName;

    /** 最大 Token 数 */
    private int maxTokens = 4096;

    /** 温度参数 */
    private double temperature = 0.3;

    /** 超时时间（秒） */
    private int timeoutSeconds = 120;

    private int maxRetries = 2;

    private int embeddingTimeoutSeconds = 20;

    private int rerankTimeoutSeconds = 20;

    /** 是否启用统一预算守卫 */
    private boolean budgetEnabled = false;

    /** 单次调用允许的最大预计输入 Token */
    private int budgetMaxEstimatedInputTokens = 12000;

    /** 每千输入 Token 的估算成本，用于在调用前给出统一预算摘要 */
    private double budgetInputCostPerThousandTokens = 0D;

    /** 每千输出 Token 的估算成本，用于成功后补充调用成本摘要 */
    private double budgetOutputCostPerThousandTokens = 0D;

    /** 单次调用允许的最大估算成本；小于等于 0 表示暂不启用成本上限 */
    private double budgetMaxEstimatedCost = 0D;

    /** 提供商配置映射 */
    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
        /** Provider 适配器类型，用来把配置路由到对应实现 */
        private String adapterType;
        /** 提供商 URL */
        private String url;
        /** API Key（支持 ${ENV:default} 占位符） */
        private String apiKey;
        /** 端点映射（chat, embedding, rerank 等） */
        private Map<String, String> endpoints;
    }

    /**
     * 获取当前激活的提供商配置
     */
    public ProviderConfig getActiveProviderConfig() {
        if (providers == null || activeProvider == null) {
            throw new IllegalStateException("AI 提供商未配置，请检查 ai.providers.* 配置");
        }
        ProviderConfig config = providers.get(activeProvider);
        if (config == null) {
            throw new IllegalStateException("未找到名为 '" + activeProvider + "' 的 AI 提供商配置");
        }
        return config;
    }

    /**
     * 根据提供商配置推导 OpenAI 兼容的 baseUrl
     */
    public String getBaseUrl() {
        ProviderConfig provider = getActiveProviderConfig();
        String chatEndpoint = provider.getUrl() + getEndpoint("chat");
        return chatEndpoint.replace("/chat/completions", "");
    }

    /**
     * 获取 API Key，未配置时返回占位符（ollama 等本地模型不需要）
     */
    public String getApiKey() {
        ProviderConfig provider = getActiveProviderConfig();
        return provider.getApiKey() != null && !provider.getApiKey().isEmpty()
                ? provider.getApiKey()
                : "not-needed";
    }

    public String getEndpoint(String capability) {
        ProviderConfig provider = getActiveProviderConfig();
        if (provider.getEndpoints() == null) {
            return null;
        }
        return provider.getEndpoints().get(capability);
    }

    public boolean supportsCapability(String capability) {
        return StringUtils.hasText(getEndpoint(capability));
    }

    public String getCapabilityUrl(String capability) {
        String endpoint = getEndpoint(capability);
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalStateException("当前 AI 提供商未配置 " + capability + " endpoint");
        }
        return getActiveProviderConfig().getUrl() + endpoint;
    }
}
