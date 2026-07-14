package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Provider 调用请求。
 * <p>
 * 网关在这里统一封装路由后的 provider 配置、能力类型和业务输入，
 * 避免不同适配器重复拼装调用上下文。
 */
@Data
@Builder(toBuilder = true)
public class ProviderInvocationRequest {

    private String providerKey;
    private String modelName;
    private AiCapability capability;
    private AiProviderProperties.ProviderConfig providerConfig;
    private String systemPrompt;
    private String userPrompt;
    private String responseSchema;
    /** 单次请求温度覆盖；null 时由 Provider adapter 使用全局配置。 */
    private Double temperature;
    /** 单次 Provider HTTP 硬超时覆盖；null 时由 Provider adapter 使用全局配置。 */
    private Long timeoutMillis;
    private String text;
    private String query;
    private List<String> documents;
}
