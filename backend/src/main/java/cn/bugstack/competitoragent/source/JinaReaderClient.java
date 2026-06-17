package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Jina Reader 客户端。
 * 这里统一封装轻量正文读取、异常兜底与重试规则，避免执行器层继续直接拼接 reader URL。
 */
@Component
public class JinaReaderClient {

    private final JinaReaderProperties properties;
    private final HttpClient httpClient;

    /**
     * Spring 容器中的正式入口只依赖配置属性。
     * HttpClient 仍保留为测试和手工构造覆盖点，避免把它错误提升为应用上下文中的必填 bean。
     */
    @Autowired
    public JinaReaderClient(JinaReaderProperties properties) {
        this(properties, null);
    }

    public JinaReaderClient(JinaReaderProperties properties, HttpClient httpClient) {
        this.properties = properties == null ? new JinaReaderProperties() : properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
    }

    /**
     * 统一封装 reader URL 的拼接规则，避免调用方自己拼 endpoint。
     */
    public String resolveReaderUrl(String originalUrl) {
        if (!StringUtils.hasText(originalUrl)) {
            return originalUrl;
        }
        String endpoint = properties.getEndpoint();
        if (!StringUtils.hasText(endpoint)) {
            return originalUrl;
        }
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        return normalizedEndpoint + originalUrl.trim().replaceFirst("^https?://", "");
    }

    /**
     * 轻量正文采集必须带异常保护与重试。
     * 这里先把最小结果契约接通，后续 Task 5 再继续细化质量评分与结构块识别。
     */
    public PageContentExtractionResult collect(SourceCollectRequest request) {
        Instant startedAt = Instant.now();
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "source collect request is null or url is blank",
                    startedAt,
                    List.of("LIGHTWEIGHT_REQUEST_INVALID"));
        }
        if (!properties.isEnabled()) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "jina reader disabled",
                    startedAt,
                    List.of("LIGHTWEIGHT_DISABLED"));
        }

        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolveReaderUrl(request.getUrl())))
                        .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                        .header("Accept", "text/plain")
                        .GET();
                if (StringUtils.hasText(properties.getBearerToken())) {
                    builder.header("Authorization", "Bearer " + properties.getBearerToken());
                }

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return buildFailureResult(CollectionFailureKind.HTTP_STATUS_ERROR,
                            "jina reader status=" + response.statusCode(),
                            startedAt,
                            List.of("LIGHTWEIGHT_HTTP_STATUS_ERROR"));
                }

                String markdown = response.body() == null ? "" : response.body().trim();
                if (markdown.length() < Math.max(1, properties.getMinimumContentLength())) {
                    return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                            "jina reader content too thin",
                            startedAt,
                            List.of("LIGHTWEIGHT_CONTENT_TOO_THIN"));
                }

                return PageContentExtractionResult.builder()
                        .success(true)
                        .title(request.getUrl())
                        .mainContent(markdown)
                        .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                        .qualityScore(0.78D)
                        .structuredBlocks(List.<StructuredContentBlock>of())
                        .collectedAt(Instant.now())
                        .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                        .build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastError = new IllegalStateException("jina reader request interrupted", exception);
                break;
            } catch (Exception exception) {
                lastError = new IllegalStateException("jina reader request failed: " + exception.getMessage(), exception);
            }
        }

        return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                lastError == null ? "jina reader failed" : lastError.getMessage(),
                startedAt,
                List.of("LIGHTWEIGHT_RUNTIME_FAILURE"));
    }

    /**
     * 统一构造轻量正文失败结果，确保外层升级到 Playwright 时仍然能拿到稳定的 failureKind 与质量信号。
     */
    private PageContentExtractionResult buildFailureResult(CollectionFailureKind failureKind,
                                                          String errorMessage,
                                                          Instant startedAt,
                                                          List<String> qualitySignals) {
        return PageContentExtractionResult.builder()
                .success(false)
                .failureKind(failureKind == null ? null : failureKind.name())
                .errorMessage(errorMessage)
                .qualitySignals(qualitySignals == null ? List.of() : qualitySignals)
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                .build();
    }
}
