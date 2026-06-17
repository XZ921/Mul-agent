package cn.bugstack.competitoragent.source;

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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GithubApiClient(GithubApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
     * 所有 GitHub API 请求都必须带异常保护与重试。
     * 当前阶段先统一做固定次数重试，避免 provider / executor 各自复制一份外呼容错。
     */
    private JsonNode exchange(String path) {
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
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
