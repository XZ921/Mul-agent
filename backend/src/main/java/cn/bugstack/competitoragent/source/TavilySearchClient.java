package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Tavily Search API 客户端。
 * 该类只负责请求 Tavily 并把返回结构解析成稳定 DTO，不承担家族策略、结果筛选和采集路由职责，
 * 这样可以把 HTTP 失败重试、超时、fail-open 降级行为集中收口。
 */
@Slf4j
@Component
public class TavilySearchClient {

    private final TavilySearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 仅用于测试验证请求契约。
     * 生产逻辑不会依赖这些字段。
     */
    private HttpRequest lastRequestForTest;
    private String lastRequestBodyForTest;

    @Autowired
    public TavilySearchClient(TavilySearchProperties properties, ObjectMapper objectMapper) {
        this(properties,
                objectMapper,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds(properties)))
                        .build());
    }

    public TavilySearchClient(TavilySearchProperties properties,
                              ObjectMapper objectMapper,
                              HttpClient httpClient) {
        this.properties = properties == null ? new TavilySearchProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 执行一次 Tavily 搜索请求。
     * 无论 Tavily 调用失败、超时还是返回异常结构，这里都统一 fail-open 返回空结果，
     * 保证上层 Provider 可以平稳降级，而不是把异常继续抛到路由层之外。
     */
    public TavilySearchResponse search(TavilySearchProfile profile) {
        if (profile == null) {
            return emptyResponse(null, "tavily profile missing");
        }
        if (httpClient == null) {
            return emptyResponse(profile.getQuery(), "tavily httpClient unavailable");
        }
        if (!properties.isConfigured()) {
            return emptyResponse(profile.getQuery(), "tavily not configured");
        }

        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        String requestBody;
        try {
            requestBody = buildRequestBody(profile);
        } catch (Exception e) {
            return emptyResponse(profile.getQuery(), "build tavily request failed: " + e.getMessage());
        }

        RuntimeException lastRuntimeError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = buildRequest(requestBody);
                lastRequestForTest = request;
                lastRequestBodyForTest = requestBody;
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("tavily status=" + response.statusCode());
                }
                return parseResponse(response.body(), profile.getQuery());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("tavily search interrupted, query={}, attempt={}/{}",
                        profile.getQuery(), attempt, maxAttempts);
                return emptyResponse(profile.getQuery(), "tavily interrupted");
            } catch (RuntimeException e) {
                lastRuntimeError = e;
                log.warn("tavily search failed, query={}, attempt={}/{}, error={}",
                        profile.getQuery(), attempt, maxAttempts, e.getMessage());
            } catch (Exception e) {
                lastRuntimeError = new IllegalStateException("tavily request failed: " + e.getMessage(), e);
                log.warn("tavily request failed, query={}, attempt={}/{}, error={}",
                        profile.getQuery(), attempt, maxAttempts, e.getMessage());
            }
        }
        return emptyResponse(profile.getQuery(),
                lastRuntimeError == null ? "tavily request failed" : lastRuntimeError.getMessage());
    }

    HttpRequest getLastRequestForTest() {
        return lastRequestForTest;
    }

    String getLastRequestBodyForTest() {
        return lastRequestBodyForTest;
    }

    private HttpRequest buildRequest(String requestBody) {
        return HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", resolveAuthorizationHeader())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
    }

    private String buildRequestBody(TavilySearchProfile profile) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("query", defaultText(profile.getQuery()));
        payload.put("search_depth", defaultText(profile.getSearchDepth(), defaultText(properties.getSearchDepth(), "advanced")));
        payload.put("include_raw_content", profile.isIncludeRawContent());
        payload.put("max_results", profile.getMaxResults() <= 0 ? Math.max(1, properties.getMaxResults()) : profile.getMaxResults());
        if (profile.getIncludeDomains() != null && !profile.getIncludeDomains().isEmpty()) {
            payload.put("include_domains", profile.getIncludeDomains());
        }
        return objectMapper.writeValueAsString(payload);
    }

    private TavilySearchResponse parseResponse(String responseBody, String originalQuery) {
        try {
            TavilyApiResponse parsed = objectMapper.readValue(responseBody, TavilyApiResponse.class);
            List<TavilySearchResult> results = parsed == null || parsed.getResults() == null
                    ? List.of()
                    : parsed.getResults().stream()
                    .filter(item -> item != null && StringUtils.hasText(item.getUrl()))
                    .map(item -> TavilySearchResult.builder()
                            .title(item.getTitle())
                            .url(item.getUrl())
                            .content(item.getContent())
                            .rawContent(item.getRawContent())
                            .score(item.getScore())
                            .build())
                    .toList();
            return TavilySearchResponse.builder()
                    .query(StringUtils.hasText(parsed == null ? null : parsed.getQuery())
                            ? parsed.getQuery()
                            : originalQuery)
                    .requestId(parsed == null ? null : parsed.getRequestId())
                    .results(results)
                    .build();
        } catch (Exception e) {
            log.warn("parse tavily response failed, query={}, error={}", originalQuery, e.getMessage());
            return emptyResponse(originalQuery, "parse tavily response failed: " + e.getMessage());
        }
    }

    private TavilySearchResponse emptyResponse(String query, String failureReason) {
        return TavilySearchResponse.builder()
                .query(query)
                .results(List.of())
                .failureReason(failureReason)
                .build();
    }

    private String resolveAuthorizationHeader() {
        String apiKey = defaultText(properties.getApiKey()).trim();
        if (apiKey.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return apiKey;
        }
        return "Bearer " + apiKey;
    }

    private static int resolveTimeoutSeconds(TavilySearchProperties properties) {
        return Math.max(1, properties == null ? 20 : properties.getTimeoutSeconds());
    }

    private String defaultText(String value) {
        return defaultText(value, "");
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TavilySearchResponse {
        private String query;
        private String requestId;
        @Builder.Default
        private List<TavilySearchResult> results = List.of();
        private String failureReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TavilySearchResult {
        private String title;
        private String url;
        private String content;
        private String rawContent;
        private Double score;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TavilyApiResponse {
        private String query;
        @JsonProperty("request_id")
        private String requestId;
        private List<TavilyApiResult> results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TavilyApiResult {
        private String title;
        private String url;
        private String content;
        @JsonProperty("raw_content")
        private String rawContent;
        private Double score;
    }
}
