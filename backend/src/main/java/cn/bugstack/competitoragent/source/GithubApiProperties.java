package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

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

    /**
     * 判断 GitHub API 是否已经具备最小配置条件。
     * 这里只把 endpoint 与 token 同时存在视为“已配置”，避免系统继续把匿名公共 API 误当成正式 owner 能力。
     */
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(apiToken);
    }

    /**
     * 判断 GitHub API 是否真正 ready。
     * enabled 只表示“允许启用这项能力”，只有显式打开且关键字段完整时，运行期才允许对外宣称可用。
     */
    public boolean isReady() {
        return enabled && isConfigured();
    }

    /**
     * 统一生成 readiness 失败原因，供启动期 guard 与执行期 fail-fast 共用。
     * 这样可以保证用户在不同入口看到的是同一套“为什么不可用”的解释。
     */
    public String resolveReadinessFailureMessage() {
        if (!enabled) {
            return "github api disabled";
        }
        if (!StringUtils.hasText(endpoint)) {
            return "github api endpoint missing";
        }
        if (!StringUtils.hasText(apiToken)) {
            return "github api token missing";
        }
        return null;
    }
}
