package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.QianfanSearchProperties;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
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
 * 千帆搜索补源适配器。
 * 优先为中文竞品场景提供稳定的 Web Search 候选来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QianfanSearchSourceProvider implements SearchSourceProvider {

    private final QianfanSearchProperties properties;
    private final SearchProviderProperties searchProviderProperties;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey("qianfan")
                .displayName("千帆搜索")
                .capabilities(List.of("WEB_SEARCH", "CHINESE_RESULTS"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return StringUtils.hasText(properties.getApiKey())
                && UrlSecurityUtils.isHttpsUrl(properties.getEndpoint());
    }

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        if (!isAvailable() || !StringUtils.hasText(competitorName)) {
            return List.of();
        }

        Set<String> scopes = new LinkedHashSet<>(requestedScopes == null || requestedScopes.isEmpty()
                ? List.of("OFFICIAL", "DOCS", "PRICING", "NEWS", "REVIEW")
                : requestedScopes);
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String scope : scopes) {
            candidates.addAll(searchScope(competitorName, scope));
        }
        return deduplicateCandidates(candidates);
    }

    private List<SourceCandidate> searchScope(String competitorName, String scope) {
        List<String> queries = promptTemplateService.buildSearchQueries(competitorName, scope, null);
        if (queries.isEmpty()) {
            return List.of();
        }
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String query : queries) {
            candidates.addAll(searchWithRetry(competitorName, scope, query));
        }
        return candidates;
    }

    private List<SourceCandidate> searchWithRetry(String competitorName, String scope, String query) {
        int maxAttempts = Math.max(1, searchProviderProperties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return searchOnce(competitorName, scope, query);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("qianfan search failed, competitor={}, scope={}, query={}, attempt={}/{}",
                        UrlSecurityUtils.maskForLog(competitorName),
                        scope,
                        UrlSecurityUtils.maskForLog(query),
                        attempt,
                        maxAttempts);
            }
        }
        log.warn("qianfan search exhausted retries, competitor={}, scope={}, query={}, error={}",
                UrlSecurityUtils.maskForLog(competitorName),
                scope,
                UrlSecurityUtils.maskForLog(query),
                lastError == null ? "unknown" : lastError.getMessage());
        return List.of();
    }

    private List<SourceCandidate> searchOnce(String competitorName, String scope, String query) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(query), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("qianfan status=" + response.statusCode());
            }
            return parseCandidates(response.body(), competitorName, scope, query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qianfan interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("qianfan request failed: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String query) {
        return "{\"query\":\"" + escapeJson(query) + "\"}";
    }

    private List<SourceCandidate> parseCandidates(String responseBody,
                                                  String competitorName,
                                                  String scope,
                                                  String query) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root == null ? null : root.path("data").path("results");
            if (results == null || !results.isArray()) {
                return List.of();
            }

            int limit = Math.max(1, searchProviderProperties.getResultsPerScope());
            List<SourceCandidate> candidates = new ArrayList<>();
            int rank = 0;
            for (JsonNode item : results) {
                if (rank >= limit) {
                    break;
                }
                String url = text(item, "url");
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                rank++;
                candidates.add(SourceCandidate.builder()
                        .url(url)
                        .sourceUrls(List.of(url))
                        .title(defaultText(text(item, "title"), competitorName + " " + scope))
                        .sourceType(scope)
                        .providerKey("qianfan")
                        .discoveryMethod("QIANFAN_SEARCH")
                        .reason(buildReason(scope, defaultText(text(item, "content"), text(item, "snippet"))))
                        .domain(extractDomain(url))
                        .publishedAt(defaultText(text(item, "publish_time"), text(item, "published_at")))
                        .relevanceScore(inferRelevance(scope, rank))
                        .freshnessScore(StringUtils.hasText(defaultText(text(item, "publish_time"), text(item, "published_at"))) ? 0.80 : 0.58)
                        .qualityScore(inferQuality(url))
                        .searchQuery(query)
                        .searchEngine(defaultText(properties.getDefaultEngine(), "baidu"))
                        .resultRank(rank)
                        .selectionStage("PLANNED")
                        .selectionReason("通过千帆 Web Search 稳定补源")
                        .build());
            }
            return candidates;
        } catch (Exception e) {
            log.warn("parse qianfan response failed, competitor={}, scope={}, error={}",
                    UrlSecurityUtils.maskForLog(competitorName), scope, e.getMessage());
            return List.of();
        }
    }

    private List<SourceCandidate> deduplicateCandidates(List<SourceCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<SourceCandidate> filtered = new ArrayList<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
                continue;
            }
            if (!seen.add(candidate.getUrl())) {
                continue;
            }
            filtered.add(candidate);
        }
        return filtered;
    }

    private String buildReason(String scope, String snippet) {
        String label = switch (scope == null ? "" : scope.toUpperCase(Locale.ROOT)) {
            case "DOCS" -> "千帆搜索命中文档入口";
            case "PRICING" -> "千帆搜索命中定价信息";
            case "NEWS" -> "千帆搜索命中新鲜资讯";
            case "REVIEW" -> "千帆搜索命中第三方评测信息";
            default -> "千帆搜索命中官网或产品入口";
        };
        if (!StringUtils.hasText(snippet)) {
            return label;
        }
        return label + "：" + snippet.substring(0, Math.min(180, snippet.length()));
    }

    private double inferRelevance(String scope, int rank) {
        double base = switch (scope == null ? "" : scope.toUpperCase(Locale.ROOT)) {
            case "PRICING" -> 0.94;
            case "DOCS" -> 0.93;
            case "REVIEW" -> 0.86;
            case "NEWS" -> 0.82;
            default -> 0.89;
        };
        return Math.max(0.55, base - (rank - 1) * 0.04);
    }

    private double inferQuality(String url) {
        String domain = defaultText(extractDomain(url), "").toLowerCase(Locale.ROOT);
        if (domain.contains("g2.com") || domain.contains("capterra.com")) {
            return 0.88;
        }
        if (domain.startsWith("docs.") || domain.contains("help") || domain.contains("support")) {
            return 0.90;
        }
        return 0.84;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
