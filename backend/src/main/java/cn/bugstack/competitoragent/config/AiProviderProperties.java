package cn.bugstack.competitoragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 提供商配置映射 */
    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
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
        String chatEndpoint = provider.getUrl() + provider.getEndpoints().get("chat");
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
}
