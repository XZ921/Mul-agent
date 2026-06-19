package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 竞品域名发现配置。
 * <p>
 * 这里单独收拢 LLM 发现与域名可达性验证的控制项，避免把辅助能力和主搜索链路配置混在一起。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.discovery.domain")
public class DomainDiscoveryProperties {

    /**
     * 是否启用 LLM 域名发现。
     */
    private boolean llmEnabled = false;

    /**
     * LLM 调用超时时间，单位毫秒。
     */
    private int llmTimeoutMillis = 8000;

    /**
     * LLM 最多返回多少个候选。
     */
    private int maxLlmCandidates = 8;

    /**
     * 域名验证超时时间，单位毫秒。
     */
    private int verificationTimeoutMillis = 3000;

    /**
     * 域名验证最大重试次数。
     */
    private int maxRetries = 1;
}
