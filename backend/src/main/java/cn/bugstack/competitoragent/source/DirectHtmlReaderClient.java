package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Direct HTML 轻量采集客户端。
 * 它只负责直接请求目标 URL，并使用 Jsoup 从 HTML 中提取正文与站内链接；
 * 失败时返回明确质量信号，由外层采集执行器决定是否继续交给 JinaReader 或 Playwright 兜底。
 */
@Slf4j
@Component
public class DirectHtmlReaderClient {

    private static final List<String> CONTENT_SELECTORS = List.of(
            "main",
            "article",
            "[role=main]",
            ".markdown-body",
            ".docs-content",
            ".doc-content",
            ".documentation",
            ".content",
            "body"
    );

    private final DirectHtmlReaderProperties properties;
    private final HttpClient httpClient;

    /**
     * Spring 容器中的正式入口只依赖配置属性。
     * HttpClient 保留为测试覆盖点，避免把 JDK 客户端错误提升为应用上下文中的必填 Bean。
     */
    @Autowired
    public DirectHtmlReaderClient(DirectHtmlReaderProperties properties) {
        this(properties, null);
    }

    public DirectHtmlReaderClient(DirectHtmlReaderProperties properties, HttpClient httpClient) {
        this.properties = properties == null ? new DirectHtmlReaderProperties() : properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
    }

    /**
     * 直接访问目标页面并提取可读正文。
     * 外部 HTTP 调用必须带 try-catch、超时和重试；这里的重试只覆盖运行时/网络异常，
     * 对明确的 HTTP 状态错误立即返回，避免对反爬或权限拒绝做无意义重复请求。
     */
    public PageContentExtractionResult collect(SourceCollectRequest request) {
        Instant startedAt = Instant.now();
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "direct html request is null or url is blank",
                    startedAt,
                    List.of("DIRECT_HTML_REQUEST_INVALID"));
        }
        if (!properties.isEnabled()) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "direct html reader disabled",
                    startedAt,
                    List.of("DIRECT_HTML_DISABLED"));
        }

        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(buildRequest(request), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return buildFailureResult(CollectionFailureKind.HTTP_STATUS_ERROR,
                            "direct html status=" + response.statusCode(),
                            startedAt,
                            List.of("DIRECT_HTML_HTTP_STATUS_ERROR"));
                }
                return extractReadableContent(request, response.body(), startedAt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastError = new IllegalStateException("direct html request interrupted", exception);
                break;
            } catch (Exception exception) {
                lastError = new IllegalStateException("direct html request failed: " + exception.getMessage(), exception);
                log.warn("Direct HTML 采集失败，准备按重试策略处理: url={}, attempt={}/{}, error={}",
                        request.getUrl(),
                        attempt,
                        maxAttempts,
                        exception.getMessage());
            }
        }

        return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                lastError == null ? "direct html request failed" : lastError.getMessage(),
                startedAt,
                List.of("DIRECT_HTML_RUNTIME_FAILURE"));
    }

    /**
     * 构造 Direct HTTP 请求。
     * 这里集中设置 User-Agent、Accept 与超时，避免调用方绕过统一的抓取行为约束。
     */
    HttpRequest buildRequest(SourceCollectRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.getUrl().trim()))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET();
        if (StringUtils.hasText(properties.getUserAgent())) {
            builder.header("User-Agent", properties.getUserAgent().trim());
        }
        return builder.build();
    }

    /**
     * 从 HTML 中提取标题、正文和 Markdown 链接，并判断结果是否可用。
     * SPA 空壳判定放在正文可用性判断之前，但会先看可读中文保护阈值，避免误伤半静态中文文档页。
     */
    private PageContentExtractionResult extractReadableContent(SourceCollectRequest request, String html, Instant startedAt) {
        if (!StringUtils.hasText(html)) {
            return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                    "direct html body is empty",
                    startedAt,
                    List.of("DIRECT_HTML_CONTENT_TOO_THIN"));
        }

        Document document = Jsoup.parse(html, request.getUrl());
        ExtractedContent extractedContent = extractBestContent(document, request.getUrl());
        String text = cleanContent(extractedContent.content());
        int readableChineseChars = countReadableChineseChars(text);
        if (looksLikeSpaShell(document, text, readableChineseChars)) {
            return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                    "direct html looks like spa shell",
                    startedAt,
                    List.of("DIRECT_HTML_SPA_SHELL"));
        }
        if (text.length() < Math.max(1, properties.getMinimumContentLength())) {
            return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                    "direct html content too thin",
                    startedAt,
                    List.of("DIRECT_HTML_CONTENT_TOO_THIN"));
        }

        log.info("Direct HTML 采集命中: url={}, selector={}, textLength={}, readableChineseChars={}, extractedLinks={}",
                request.getUrl(),
                extractedContent.selector(),
                text.length(),
                readableChineseChars,
                extractedContent.linkCount());

        return PageContentExtractionResult.builder()
                .success(true)
                .title(resolveTitle(document, request))
                .mainContent(text)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .qualityScore(calculateQualityScore(text, extractedContent.linkCount()))
                .structuredBlocks(List.<StructuredContentBlock>of())
                .collectedAt(Instant.now())
                .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                .build();
    }

    /**
     * 按常见正文选择器打分，优先选择 main/article/docs 等语义块，最后才退回 body。
     * 每个候选块都会把锚点转换成 Markdown 链接，保证后续站内链接发现能继续消费轻量结果。
     */
    private ExtractedContent extractBestContent(Document document, String pageUrl) {
        ExtractedContent best = new ExtractedContent("body", "", 0, Double.NEGATIVE_INFINITY);
        for (String selector : CONTENT_SELECTORS) {
            for (Element element : document.select(selector)) {
                ExtractedContent candidate = extractContentFromElement(selector, element, pageUrl);
                if (candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }
        return best.score() == Double.NEGATIVE_INFINITY
                ? new ExtractedContent("body", cleanContent(document.text()), 0, 0D)
                : best;
    }

    private ExtractedContent extractContentFromElement(String selector, Element element, String pageUrl) {
        Element cloned = element.clone();
        cloned.select("script, style, noscript, svg, canvas").remove();
        LinkedHashSet<String> markdownLinks = new LinkedHashSet<>();
        for (Element anchor : cloned.select("a[href]")) {
            String label = cleanContent(anchor.text());
            String href = resolveHref(pageUrl, anchor.attr("href"));
            String replacement = label;
            if (StringUtils.hasText(label) && StringUtils.hasText(href)) {
                replacement = "[" + label + "](" + href + ")";
                markdownLinks.add(replacement);
            }
            anchor.text(replacement == null ? "" : replacement);
        }

        String text = cleanContent(cloned.text());
        String content = appendMissingLinks(text, markdownLinks);
        int linkCount = markdownLinks.size();
        double score = scoreContent(selector, text, linkCount);
        return new ExtractedContent(selector, content, linkCount, score);
    }

    private String appendMissingLinks(String text, LinkedHashSet<String> markdownLinks) {
        if (markdownLinks == null || markdownLinks.isEmpty()) {
            return text;
        }
        List<String> linksToAppend = new ArrayList<>();
        int maxLinks = Math.max(0, properties.getMaxExtractedLinks());
        for (String markdownLink : markdownLinks) {
            if (linksToAppend.size() >= maxLinks) {
                break;
            }
            if (!text.contains(markdownLink)) {
                linksToAppend.add(markdownLink);
            }
        }
        if (linksToAppend.isEmpty()) {
            return text;
        }
        return cleanContent(text + "\n\n" + String.join("\n", linksToAppend));
    }

    /**
     * SPA 空壳的核心特征是 root/app 容器存在、脚本很重、可读正文很薄。
     * 如果可读中文达到保护阈值，则说明 HTML 已经包含业务正文，不应因为 DOM 形态像 SPA 而误判失败。
     */
    private boolean looksLikeSpaShell(Document document, String text, int readableChineseChars) {
        if (readableChineseChars >= Math.max(1, properties.getReadableChineseGuardChars())) {
            return false;
        }
        boolean hasAppRoot = document.select("#app, #root, [data-reactroot], [data-v-app]").size() > 0;
        int scriptTextLength = document.select("script").stream()
                .map(Element::html)
                .mapToInt(String::length)
                .sum();
        int bodyTextLength = text == null ? 0 : text.length();
        return hasAppRoot
                && scriptTextLength >= Math.max(500, bodyTextLength * 4)
                && bodyTextLength < Math.max(120, properties.getMinimumContentLength());
    }

    private double scoreContent(String selector, String text, int linkCount) {
        int length = text == null ? 0 : text.length();
        double score = length;
        String normalizedSelector = selector == null ? "" : selector.toLowerCase(Locale.ROOT);
        if (normalizedSelector.contains("main")) {
            score += 180;
        }
        if (normalizedSelector.contains("article")) {
            score += 220;
        }
        if (normalizedSelector.contains("docs") || normalizedSelector.contains("documentation")) {
            score += 140;
        }
        if ("body".equals(normalizedSelector)) {
            score -= 220;
        }
        score -= Math.min(160, linkCount * 8D);
        return score;
    }

    private double calculateQualityScore(String text, int linkCount) {
        int length = text == null ? 0 : text.length();
        double score;
        if (length >= 800) {
            score = 0.84D;
        } else if (length >= 400) {
            score = 0.78D;
        } else if (length >= 160) {
            score = 0.72D;
        } else {
            score = 0.64D;
        }
        if (linkCount > 0) {
            score += 0.04D;
        }
        return Math.min(0.92D, Math.round(score * 100.0D) / 100.0D);
    }

    private String resolveTitle(Document document, SourceCollectRequest request) {
        String title = document == null ? null : cleanContent(document.title());
        if (StringUtils.hasText(title)) {
            return title;
        }
        return request == null ? null : request.getUrl();
    }

    private String resolveHref(String pageUrl, String rawHref) {
        if (!StringUtils.hasText(rawHref)) {
            return null;
        }
        try {
            String trimmed = rawHref.trim();
            if (trimmed.startsWith("#")
                    || trimmed.toLowerCase(Locale.ROOT).startsWith("javascript:")
                    || trimmed.toLowerCase(Locale.ROOT).startsWith("mailto:")
                    || trimmed.toLowerCase(Locale.ROOT).startsWith("tel:")) {
                return null;
            }
            return URI.create(pageUrl).resolve(trimmed).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int countReadableChineseChars(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char item = text.charAt(index);
            if (item >= '\u4E00' && item <= '\u9FFF') {
                count++;
            }
        }
        return count;
    }

    private String cleanContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        return rawContent
                .replaceAll("\\r\\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .replaceAll("[\\x00-\\x08\\x0E-\\x1F]", "")
                .trim();
    }

    /**
     * 统一构造 Direct 失败结果，确保外层路由可以依据 failureKind 与质量信号继续向后兜底。
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

    private record ExtractedContent(String selector, String content, int linkCount, double score) {
    }
}
