package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jina Reader 配置。
 * 这里先沉淀 endpoint、认证、超时、重试和最小正文长度等最小配置骨架，
 * 便于第五轮后续把轻量网页正文读取路径正式接入。
 */
@Data
@ConfigurationProperties(prefix = "collection.jina-reader")
public class JinaReaderProperties {

    private boolean enabled = true;
    private String endpoint = "https://r.jina.ai/http://";
    private String bearerToken;
    private int timeoutSeconds = 20;
    private int maxRetries = 2;
    private int minimumContentLength = 160;
}
