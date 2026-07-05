package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.common.http.HardTimeoutHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GitHub API 共享客户端。
 * 统一承接 endpoint、认证、重试和 JSON 解析，避免 discovery provider 与 collection executor 重复造轮子。
 */
@Component
public class GithubApiClient {

    private final GithubApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GithubApiClient(GithubApiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    GithubApiClient(GithubApiProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                : httpClient;
    }

    public JsonNode fetchRepository(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo);
    }

    public JsonNode fetchReadme(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo + "/readme");
    }

    public JsonNode fetchLatestRelease(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo + "/releases/latest");
    }

    /**
     * 判断当前客户端是否真正可用。
     * 这里把 GitHub API 允许开关、endpoint 与 token 一并作为 readiness 门槛，
     * 避免 executor 在未配置时仍然继续把它当成正式能力调用。
     */
    public boolean isReady() {
        return properties != null && properties.isReady();
    }

    /**
     * 对外暴露 GitHub API 不可用时的统一原因。
     * 这样启动期 guard、执行期 fail-fast 和日志输出都能复用同一套语义。
     */
    public String resolveReadinessFailureMessage() {
        if (properties == null) {
            return "github api properties unavailable";
        }
        return properties.resolveReadinessFailureMessage();
    }

    /**
     * 所有 GitHub API 请求都必须带异常保护与重试。
     * 当前阶段先统一做固定次数重试，避免 provider / executor 各自复制一份外呼容错。
     */
    private JsonNode exchange(String path) {
        if (properties == null) {
            throw new IllegalStateException("github api properties unavailable");
        }
        String readinessFailureMessage = resolveReadinessFailureMessage();
        if (StringUtils.hasText(readinessFailureMessage)) {
            throw new IllegalStateException(readinessFailureMessage);
        }
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getEndpoint() + path))
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .GET()
                        .header("Accept", "application/vnd.github+json");
                if (StringUtils.hasText(properties.getApiToken())) {
                    builder.header("Authorization", "Bearer " + properties.getApiToken());
                }
                HttpRequest request = builder.build();
                /**
                 * GitHub API 作为共享基础客户端，必须统一具备调用层硬超时，
                 * 否则 discovery provider 和 collection executor 都会被同一类阻塞问题放大。
                 */
                HttpResponse<String> response = HardTimeoutHttpClient.send(
                        httpClient,
                        request,
                        HttpResponse.BodyHandlers.ofString(),
                        request.timeout().orElse(Duration.ofSeconds(properties.getTimeoutSeconds()))
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("github api status=" + response.statusCode());
                }
                return objectMapper.readTree(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("github api interrupted", e);
            } catch (Exception e) {
                lastError = new IllegalStateException("github api request failed: " + e.getMessage(), e);
            }
        }
        throw lastError == null ? new IllegalStateException("github api request failed") : lastError;
    }
}
