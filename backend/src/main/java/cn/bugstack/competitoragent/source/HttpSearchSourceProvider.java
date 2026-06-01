package cn.bugstack.competitoragent.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 真实 HTTP 搜索补源适配层。
 * 该实现面向通用 JSON 搜索 API，字段路径通过配置控制，方便接入 SerpAPI/Bing/Tavily/自建搜索服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpSearchSourceProvider implements SearchSourceProvider {

    private final SearchProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        if (!isEnabled()) {
            log.warn("real search provider disabled because endpoint or apiKey is blank");
            return List.of();
        }
        if (!StringUtils.hasText(competitorName)) {
            return List.of();
        }

        Set<String> scopes = new LinkedHashSet<>(requestedScopes == null || requestedScopes.isEmpty()
                ? List.of("OFFICIAL", "DOCS", "PRICING", "NEWS", "REVIEW")
                : requestedScopes);
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String scope : scopes) {
            candidates.addAll(searchScopeWithRetry(competitorName, scope));
        }
        return candidates;
    }

    private boolean isEnabled() {
        return StringUtils.hasText(properties.getEndpoint()) && StringUtils.hasText(properties.getApiKey());
    }

    private List<SourceCandidate> searchScopeWithRetry(String competitorName, String scope) {
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return searchScope(competitorName, scope);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("search source request failed, competitor={}, scope={}, attempt={}/{}",
                        competitorName, scope, attempt, maxAttempts);
            }
        }
        log.error("search source request exhausted retries, competitor={}, scope={}, error={}",
                competitorName, scope, lastError == null ? "unknown" : lastError.getMessage());
        return List.of();
    }

    private List<SourceCandidate> searchScope(String competitorName, String scope) {
        String query = buildQuery(competitorName, scope);
        HttpRequest request = HttpRequest.newBuilder(buildUri(query))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Accept", "application/json")
                .header(properties.getApiKeyHeader(), properties.getApiKeyPrefix() + properties.getApiKey())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("search api status=" + response.statusCode());
            }
            return parseCandidates(response.body(), competitorName, scope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("search api interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("search api request failed: " + e.getMessage(), e);
        }
    }

    private URI buildUri(String query) {
        String separator = properties.getEndpoint().contains("?") ? "&" : "?";
        String encodedQuery = encode(properties.getQueryParam()) + "=" + encode(query);
        String limit = encode(properties.getLimitParam()) + "=" + Math.max(1, properties.getResultsPerScope());
        return URI.create(properties.getEndpoint() + separator + encodedQuery + "&" + limit);
    }

    /**
     * 针对不同 scope 构造更具体的搜索词，让外部搜索服务返回更贴近采集目标的入口。
     */
    private String buildQuery(String competitorName, String scope) {
        return switch (scope) {
            case "DOCS" -> competitorName + " docs documentation help";
            case "PRICING" -> competitorName + " pricing plans";
            case "NEWS" -> competitorName + " blog news changelog product update";
            case "REVIEW" -> competitorName + " review G2 Capterra comparison";
            default -> competitorName + " official website";
        };
    }

    private List<SourceCandidate> parseCandidates(String responseBody,
                                                  String competitorName,
                                                  String scope) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = resolvePath(root, properties.getResultsPath());
            if (results == null || !results.isArray()) {
                return List.of();
            }

            List<SourceCandidate> candidates = new ArrayList<>();
            int index = 0;
            for (JsonNode item : results) {
                if (index >= properties.getResultsPerScope()) {
                    break;
                }
                String url = text(item, properties.getUrlField());
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                index++;
                candidates.add(SourceCandidate.builder()
                        .url(url)
                        .title(defaultText(text(item, properties.getTitleField()), competitorName + " " + scope))
                        .sourceType(scope)
                        .discoveryMethod("SEARCH")
                        .reason(buildReason(scope, text(item, properties.getSnippetField())))
                        .domain(extractDomain(url))
                        .publishedAt(text(item, properties.getPublishedAtField()))
                        .relevanceScore(inferRelevance(scope, index))
                        .freshnessScore(StringUtils.hasText(text(item, properties.getPublishedAtField())) ? 0.78 : 0.55)
                        .qualityScore(inferQuality(url))
                        .searchQuery(buildQuery(competitorName, scope))
                        .searchEngine(properties.getEndpoint())
                        .resultRank(index)
                        .selectionStage("PLANNED")
                        .build());
            }
            return candidates;
        } catch (Exception e) {
            log.warn("parse search source response failed", e);
            return List.of();
        }
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || segment.isBlank()) {
                return current;
            }
            current = current.get(segment);
        }
        return current;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = resolvePath(node, fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String buildReason(String scope, String snippet) {
        String label = switch (scope) {
            case "DOCS" -> "搜索补源命中文档入口";
            case "PRICING" -> "搜索补源命中定价信息";
            case "NEWS" -> "搜索补源命中新闻/更新信息";
            case "REVIEW" -> "搜索补源命中第三方测评信息";
            default -> "搜索补源命中官网或产品入口";
        };
        if (!StringUtils.hasText(snippet)) {
            return label;
        }
        return label + "：" + snippet.substring(0, Math.min(180, snippet.length()));
    }

    private double inferRelevance(String scope, int rank) {
        double base = switch (scope) {
            case "PRICING" -> 0.94;
            case "DOCS" -> 0.92;
            case "REVIEW" -> 0.84;
            case "NEWS" -> 0.80;
            default -> 0.88;
        };
        return Math.max(0.55, base - (rank - 1) * 0.04);
    }

    private double inferQuality(String url) {
        String domain = extractDomain(url).toLowerCase(Locale.ROOT);
        if (domain.contains("g2.com") || domain.contains("capterra.com")) {
            return 0.88;
        }
        if (domain.startsWith("docs.") || domain.contains("help") || domain.contains("support")) {
            return 0.90;
        }
        return 0.82;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
