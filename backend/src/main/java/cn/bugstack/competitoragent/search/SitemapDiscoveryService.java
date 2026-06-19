package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sitemap/robots 发现服务。
 * <p>
 * 这一层只负责从已知根域补充公开可达的高价值入口，
 * 不承担搜索排序、候选验真和最终选源职责；任何失败都必须静默降级为空结果。
 */
@Component
public class SitemapDiscoveryService {

    private static final List<String> HIGH_VALUE_PATH_KEYWORDS = List.of(
            "/doc",
            "/docs",
            "/api",
            "/sdk",
            "/pricing",
            "/help"
    );

    private final SitemapDiscoveryProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public SitemapDiscoveryService(SitemapDiscoveryProperties properties) {
        this.properties = properties == null ? new SitemapDiscoveryProperties() : properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(resolveTimeoutMillis()))
                .build();
    }

    /**
     * 从根域的 robots.txt 与 sitemap.xml 中提取高价值入口候选。
     * <p>
     * 只要配置非法、网络失败、XML 不可解析或站点未暴露 sitemap，都统一降级为空列表，
     * 避免影响主搜索链路和后续采集链路。
     */
    public List<SourceCandidate> discover(String competitorName,
                                          String sourceType,
                                          List<String> rootUrls) {
        if (!properties.isEnabled() || hasInvalidConfiguration() || rootUrls == null || rootUrls.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, SourceCandidate> candidates = new LinkedHashMap<>();
            for (String rootUrl : rootUrls) {
                String normalizedRootUrl = normalizeRootUrl(rootUrl);
                if (!StringUtils.hasText(normalizedRootUrl)) {
                    continue;
                }
                discoverFromSingleRoot(competitorName, sourceType, normalizedRootUrl, candidates);
            }
            return new ArrayList<>(candidates.values());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 单个根域的解析顺序固定为：
     * 1. 读取 robots.txt 中显式声明的 Sitemap
     * 2. 如果没有声明，则回退尝试 /sitemap.xml
     * 3. 递归处理 sitemapindex，最终只保留与当前 sourceType 相关的高价值路径
     */
    private void discoverFromSingleRoot(String competitorName,
                                        String sourceType,
                                        String rootUrl,
                                        Map<String, SourceCandidate> candidates) {
        List<PendingSitemap> pendingSitemaps = discoverDeclaredSitemaps(rootUrl);
        if (pendingSitemaps.isEmpty()) {
            String fallbackSitemapUrl = appendPath(rootUrl, "/sitemap.xml");
            pendingSitemaps = List.of(new PendingSitemap(fallbackSitemapUrl, List.of(fallbackSitemapUrl)));
        }

        ArrayDeque<PendingSitemap> queue = new ArrayDeque<>(pendingSitemaps);
        Set<String> visitedSitemaps = new LinkedHashSet<>();
        int processedSitemaps = 0;
        while (!queue.isEmpty() && processedSitemaps < properties.getMaxSitemapsPerDomain()) {
            PendingSitemap pendingSitemap = queue.pollFirst();
            if (pendingSitemap == null || !StringUtils.hasText(pendingSitemap.url())) {
                continue;
            }
            String normalizedSitemapUrl = normalizeHttpUrl(pendingSitemap.url());
            if (!StringUtils.hasText(normalizedSitemapUrl) || !visitedSitemaps.add(normalizedSitemapUrl)) {
                continue;
            }

            SitemapDocument sitemapDocument = fetchSitemap(normalizedSitemapUrl);
            if (sitemapDocument == null) {
                continue;
            }
            processedSitemaps++;

            if (sitemapDocument.index()) {
                for (String childSitemapUrl : limitSitemapChildren(sitemapDocument.entries())) {
                    List<String> sourceUrls = appendSourceUrl(pendingSitemap.sourceUrls(), childSitemapUrl);
                    queue.addLast(new PendingSitemap(childSitemapUrl, sourceUrls));
                }
                continue;
            }

            appendUrlCandidates(
                    competitorName,
                    sourceType,
                    pendingSitemap.sourceUrls(),
                    sitemapDocument.entries(),
                    sitemapDocument.entries().size() > properties.getMaxUrlsPerSitemap(),
                    candidates
            );
        }
    }

    /**
     * robots.txt 中的 Sitemap 声明是最可靠的入口线索；
     * 这里保留 robots.txt 本身作为 sourceUrls 证据，确保后续结果可回溯。
     */
    private List<PendingSitemap> discoverDeclaredSitemaps(String rootUrl) {
        String robotsUrl = appendPath(rootUrl, "/robots.txt");
        String robotsBody = fetchText(robotsUrl);
        if (!StringUtils.hasText(robotsBody)) {
            return List.of();
        }

        LinkedHashSet<String> sitemapUrls = new LinkedHashSet<>();
        for (String line : robotsBody.split("\\R")) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, "Sitemap:", 0, "Sitemap:".length())) {
                continue;
            }
            String declaredUrl = trimmed.substring("Sitemap:".length()).trim();
            String normalizedUrl = normalizeHttpUrl(resolveAgainst(robotsUrl, declaredUrl));
            if (StringUtils.hasText(normalizedUrl)) {
                sitemapUrls.add(normalizedUrl);
            }
        }

        List<PendingSitemap> pendingSitemaps = new ArrayList<>();
        for (String sitemapUrl : sitemapUrls) {
            pendingSitemaps.add(new PendingSitemap(sitemapUrl, appendSourceUrl(List.of(robotsUrl), sitemapUrl)));
        }
        return pendingSitemaps;
    }

    /**
     * 解析单个 urlset 文档时，只保留高价值路径，并按 sourceType 做最后一道语义过滤，
     * 防止 DOCS 请求被 pricing/blog 等噪音页面污染。
     */
    private void appendUrlCandidates(String competitorName,
                                     String requestedSourceType,
                                     List<String> sitemapSourceUrls,
                                     List<String> urlEntries,
                                     boolean truncated,
                                     Map<String, SourceCandidate> candidates) {
        int acceptedCount = 0;
        for (String entry : urlEntries) {
            if (acceptedCount >= properties.getMaxUrlsPerSitemap()) {
                break;
            }

            String normalizedUrl = normalizeHttpUrl(entry);
            if (!StringUtils.hasText(normalizedUrl) || !isHighValuePath(normalizedUrl)) {
                continue;
            }

            String inferredSourceType = inferSourceType(normalizedUrl);
            if (!matchesRequestedSourceType(requestedSourceType, inferredSourceType)) {
                continue;
            }

            List<String> qualitySignals = new ArrayList<>();
            qualitySignals.add("SITEMAP_DISCOVERY");
            if (truncated) {
                qualitySignals.add("SITEMAP_URL_LIMIT_TRUNCATED");
            }

            candidates.putIfAbsent(normalizedUrl, SourceCandidate.builder()
                    .url(normalizedUrl)
                    .title(buildTitle(competitorName, inferredSourceType, normalizedUrl))
                    .sourceType(inferredSourceType)
                    .discoveryMethod("SITEMAP_DISCOVERY")
                    .reason("discovered from robots/sitemap")
                    .domain(extractDomain(normalizedUrl))
                    .sourceUrls(normalizeSourceUrls(sitemapSourceUrls))
                    .relevanceScore(resolveRelevance(inferredSourceType))
                    .freshnessScore(0.55D)
                    .qualityScore(0.88D)
                    .qualitySignals(qualitySignals)
                    .build());
            acceptedCount++;
        }
    }

    private List<String> limitSitemapChildren(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        int maxChildren = Math.max(1, properties.getMaxSitemapsPerDomain());
        return entries.stream().limit(maxChildren).toList();
    }

    /**
     * XML 解析使用 JDK 标准解析器，并显式关闭外部实体，
     * 避免把 sitemap 解析变成一段脆弱的正则处理或引入额外安全风险。
     */
    private SitemapDocument fetchSitemap(String sitemapUrl) {
        String body = fetchText(sitemapUrl);
        if (!StringUtils.hasText(body)) {
            return null;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(body)));
            Element documentElement = document.getDocumentElement();
            if (documentElement == null) {
                return null;
            }

            String rootTag = normalizeTagName(documentElement.getTagName());
            List<String> entries = extractLocEntries(documentElement);
            if ("sitemapindex".equals(rootTag)) {
                return new SitemapDocument(true, entries);
            }
            if ("urlset".equals(rootTag)) {
                return new SitemapDocument(false, entries);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> extractLocEntries(Element documentElement) {
        NodeList locNodes = documentElement.getElementsByTagName("loc");
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < locNodes.getLength(); index++) {
            String value = locNodes.item(index) == null ? null : locNodes.item(index).getTextContent();
            String normalizedValue = normalizeHttpUrl(value);
            if (StringUtils.hasText(normalizedValue)) {
                entries.add(normalizedValue);
            }
        }
        return entries;
    }

    /**
     * 网络请求统一带超时与有限重试；任何异常都必须转为空结果，
     * 防止 sitemap 辅助能力把搜索主链路拖挂。
     */
    private String fetchText(String url) {
        String normalizedUrl = normalizeHttpUrl(url);
        if (!StringUtils.hasText(normalizedUrl)) {
            return null;
        }

        int attempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedUrl))
                        .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                        .header("Accept", "text/plain, application/xml, text/xml;q=0.9, */*;q=0.1")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 400) {
                    return response.body();
                }
                if (attempt >= attempts) {
                    return null;
                }
            } catch (HttpTimeoutException exception) {
                if (attempt >= attempts) {
                    return null;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception exception) {
                if (attempt >= attempts) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean hasInvalidConfiguration() {
        return properties.getTimeoutMillis() <= 0
                || properties.getMaxSitemapsPerDomain() <= 0
                || properties.getMaxUrlsPerSitemap() <= 0
                || properties.getMaxRetries() < 0;
    }

    private int resolveTimeoutMillis() {
        return properties.getTimeoutMillis() > 0 ? properties.getTimeoutMillis() : 3000;
    }

    private boolean isHighValuePath(String url) {
        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        return HIGH_VALUE_PATH_KEYWORDS.stream().anyMatch(normalizedUrl::contains);
    }

    private boolean matchesRequestedSourceType(String requestedSourceType, String inferredSourceType) {
        String normalizedRequested = StringUtils.hasText(requestedSourceType)
                ? requestedSourceType.trim().toUpperCase(Locale.ROOT)
                : "OFFICIAL";
        return normalizedRequested.equals(inferredSourceType);
    }

    private String inferSourceType(String url) {
        String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (normalizedUrl.contains("/pricing") || normalizedUrl.contains("/plans")) {
            return "PRICING";
        }
        if (normalizedUrl.contains("/doc")
                || normalizedUrl.contains("/docs")
                || normalizedUrl.contains("/api")
                || normalizedUrl.contains("/sdk")
                || normalizedUrl.contains("/help")
                || normalizedUrl.contains("/guide")
                || normalizedUrl.contains("/reference")) {
            return "DOCS";
        }
        return "OFFICIAL";
    }

    private double resolveRelevance(String sourceType) {
        return switch (sourceType) {
            case "PRICING" -> 0.92D;
            case "DOCS" -> 0.91D;
            default -> 0.85D;
        };
    }

    private String buildTitle(String competitorName, String sourceType, String url) {
        String safeCompetitorName = StringUtils.hasText(competitorName) ? competitorName.trim() : "competitor";
        return safeCompetitorName + " - " + sourceType + " sitemap entry: " + url;
    }

    private String appendPath(String rootUrl, String path) {
        try {
            return URI.create(rootUrl).resolve(path).toString();
        } catch (Exception exception) {
            return null;
        }
    }

    private String resolveAgainst(String baseUrl, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (UrlSecurityUtils.isHttpUrl(value)) {
            return value.trim();
        }
        try {
            return URI.create(baseUrl).resolve(value.trim()).toString();
        } catch (Exception exception) {
            return value.trim();
        }
    }

    private String normalizeRootUrl(String rootUrl) {
        String normalizedUrl = normalizeHttpUrl(rootUrl);
        if (!StringUtils.hasText(normalizedUrl)) {
            return null;
        }
        try {
            URI uri = URI.create(normalizedUrl);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            StringBuilder builder = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT))
                    .append("://")
                    .append(uri.getHost().toLowerCase(Locale.ROOT));
            if (uri.getPort() >= 0) {
                builder.append(":").append(uri.getPort());
            }
            return builder.toString();
        } catch (Exception exception) {
            return null;
        }
    }

    private String normalizeHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        String candidate = UrlSecurityUtils.isHttpUrl(trimmed) ? trimmed : "https://" + trimmed;
        if (!UrlSecurityUtils.isHttpUrl(candidate)) {
            return null;
        }

        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null || uri.getPath().isBlank()
                    ? ""
                    : uri.getPath().replaceAll("/+$", "");
            StringBuilder builder = new StringBuilder()
                    .append(scheme)
                    .append("://")
                    .append(host);
            if (uri.getPort() >= 0) {
                builder.append(":").append(uri.getPort());
            }
            builder.append(path);
            if (StringUtils.hasText(uri.getQuery())) {
                builder.append("?").append(uri.getQuery());
            }
            return builder.toString();
        } catch (Exception exception) {
            return null;
        }
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> appendSourceUrl(List<String> currentSourceUrls, String newSourceUrl) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (currentSourceUrls != null) {
            sourceUrls.addAll(currentSourceUrls);
        }
        if (StringUtils.hasText(newSourceUrl)) {
            sourceUrls.add(newSourceUrl.trim());
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sourceUrl : sourceUrls == null ? List.<String>of() : sourceUrls) {
            if (StringUtils.hasText(sourceUrl)) {
                normalized.add(sourceUrl.trim());
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeTagName(String tagName) {
        if (!StringUtils.hasText(tagName)) {
            return "";
        }
        String normalized = tagName.trim().toLowerCase(Locale.ROOT);
        int namespaceIndex = normalized.indexOf(':');
        return namespaceIndex >= 0 ? normalized.substring(namespaceIndex + 1) : normalized;
    }

    private record PendingSitemap(String url, List<String> sourceUrls) {
    }

    private record SitemapDocument(boolean index, List<String> entries) {
    }
}
