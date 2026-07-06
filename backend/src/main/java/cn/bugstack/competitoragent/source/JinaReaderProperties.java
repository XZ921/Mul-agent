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

    /**
     * 匿名免费端点的默认预算必须比正式 token 链路更保守。
     * 否则 r.jina.ai 在慢响应时会把补源阶段的时间预算按“20s * 2 retries”持续放大，
     * 最终表现成线程会回收，但任务长时间收不敛。
     */
    public static final int DEFAULT_FREE_TIMEOUT_SECONDS = 8;
    public static final int DEFAULT_FREE_MAX_RETRIES = 0;

    private boolean enabled = true;
    private String endpoint = "https://r.jina.ai/http://";
    /**
     * bearerToken 允许为空。
     * 为空时继续走 Jina Reader 的免费端点；只有显式配置后才追加 Authorization 头，
     * 该字段只影响速率额度与权限，不改变轻量正文采集主逻辑。
     */
    private String bearerToken;
    private int timeoutSeconds = DEFAULT_FREE_TIMEOUT_SECONDS;
    private int maxRetries = DEFAULT_FREE_MAX_RETRIES;
    private int minimumContentLength = 160;
}
