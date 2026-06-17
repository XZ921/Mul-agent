package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 共享配置。
 * 当前阶段先把 endpoint、token、timeout 与 retry 收口到单独配置对象，
 * 避免 discovery provider 与 collection executor 后续各自维护一份。
 */
@Data
@ConfigurationProperties(prefix = "github-api")
public class GithubApiProperties {

    /**
     * 是否启用 GitHub API 能力。
     */
    private boolean enabled = false;

    /**
     * GitHub API 基础地址。
     */
    private String endpoint = "https://api.github.com";

    /**
     * 访问令牌。
     */
    private String apiToken;

    /**
     * 超时时间，单位秒。
     */
    private int timeoutSeconds = 15;

    /**
     * 最大重试次数。
     */
    private int maxRetries = 2;
}
