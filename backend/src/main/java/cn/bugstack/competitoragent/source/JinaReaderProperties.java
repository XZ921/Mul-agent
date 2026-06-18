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
    /**
     * bearerToken 允许为空。
     * 为空时继续走 Jina Reader 的免费端点；只有显式配置后才追加 Authorization 头，
     * 该字段只影响速率额度与权限，不改变轻量正文采集主逻辑。
     */
    private String bearerToken;
    private int timeoutSeconds = 20;
    private int maxRetries = 2;
    private int minimumContentLength = 160;
}
