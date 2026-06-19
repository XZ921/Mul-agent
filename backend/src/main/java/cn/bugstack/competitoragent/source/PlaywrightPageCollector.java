package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import cn.bugstack.competitoragent.search.AntiBotSignalDetector;
import cn.bugstack.competitoragent.search.BrowserFailureClassifier;
import cn.bugstack.competitoragent.search.BrowserFailureDecision;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLog;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import cn.bugstack.competitoragent.search.BrowserSignalSnapshot;
import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright 页面采集器。
 * 第五轮起这里承担两件事：
 * 1. 作为 FULL_RENDER 的正式 owner，接收 request 里的 renderHint/expectedBlockTypes/sourceUrls；
 * 2. 对轻量页面保留 HTTP-first，但一旦要求 FULL_RENDER 就必须直接进入浏览器路径。
 */
@Slf4j
@Component
public class PlaywrightPageCollector implements SourceCollector {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LATIN_WORD_PATTERN = Pattern.compile("\\b[a-zA-Z]{3,}\\b");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final int MIN_HTTP_CONTENT_LENGTH = 280;
    private static final int MIN_MEANINGFUL_TEXT_UNITS = 80;
    private static final String RENDERABLE_SELECTOR = "main, article, [role='main'], .pricing-card, .docs-outline";
    private static final List<String> SPA_SHELL_SIGNALS = List.of(
            "id=\"root\"",
            "id='root'",
            "id=\"app\"",
            "id='app'",
            "id=\"__next\"",
            "id='__next'",
            "data-reactroot",
            "ng-version"
    );

    private final PlaywrightBrowserManager browserManager;
    private final CollectorProperties collectorProperties;
    private final SearchRuntimeFallbackPolicy fallbackPolicy;
    private final BrowserFailureClassifier browserFailureClassifier;
    private final AntiBotSignalDetector antiBotSignalDetector;
    private final BrowserRuntimeDiagnosticLogger diagnosticLogger;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                                   CollectorProperties collectorProperties,
                                   SearchRuntimeFallbackPolicy fallbackPolicy,
                                   BrowserFailureClassifier browserFailureClassifier,
                                   AntiBotSignalDetector antiBotSignalDetector,
                                   BrowserRuntimeDiagnosticLogger diagnosticLogger) {
        this(browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector,
                diagnosticLogger,
                new CanonicalUrlResolver());
    }

    public PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                                   CollectorProperties collectorProperties,
                                   SearchRuntimeFallbackPolicy fallbackPolicy,
                                   BrowserFailureClassifier browserFailureClassifier,
                                   AntiBotSignalDetector antiBotSignalDetector,
                                   BrowserRuntimeDiagnosticLogger diagnosticLogger,
                                   CanonicalUrlResolver canonicalUrlResolver) {
        this.browserManager = browserManager;
        this.collectorProperties = collectorProperties;
        this.fallbackPolicy = fallbackPolicy;
        this.browserFailureClassifier = browserFailureClassifier;
        this.antiBotSignalDetector = antiBotSignalDetector;
        this.diagnosticLogger = diagnosticLogger;
        this.canonicalUrlResolver = canonicalUrlResolver;
    }

    public PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                                   CollectorProperties collectorProperties,
                                   SearchRuntimeFallbackPolicy fallbackPolicy,
                                   BrowserFailureClassifier browserFailureClassifier,
                                   AntiBotSignalDetector antiBotSignalDetector) {
        this(browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector,
                new BrowserRuntimeDiagnosticLogger());
    }

    PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                            CollectorProperties collectorProperties,
                            SearchRuntimeFallbackPolicy fallbackPolicy) {
        this(browserManager,
                collectorProperties,
                fallbackPolicy,
                new BrowserFailureClassifier(),
                new AntiBotSignalDetector(new SearchBrowserProperties()));
    }

    @Override
    public CollectedPage collect(SourceCollectRequest request) {
        if (request == null) {
            return CollectedPage.builder()
                    .success(false)
                    .errorMessage("source collect request is null")
                    .build();
        }
        String url = request.getUrl();
        String competitorName = request.getCompetitorName();
        String sourceType = request.getSourceType();
        try {
            if (!UrlSecurityUtils.isHttpUrl(url)) {
                return failed(url, competitorName, sourceType, "仅允许采集 http/https 页面");
            }
            log.info("开始采集页面: url={}, competitor={}, sourceType={}",
                    UrlSecurityUtils.maskForLog(url),
                    UrlSecurityUtils.maskForLog(competitorName),
                    sourceType);

            boolean forceFullRender = requiresFullRender(request.getRenderHint());
            if (!forceFullRender) {
                CollectedPage httpPage = collectByHttp(url, competitorName, sourceType);
                if (httpPage.isSuccess()) {
                    return httpPage;
                }
                log.info("轻量 HTTP 采集未满足要求，回退到 Playwright 渲染, url={}",
                        UrlSecurityUtils.maskForLog(url));
                return collectByBrowser(request, httpPage.getErrorMessage());
            }

            return collectByBrowser(request, "FULL_RENDER_REQUIRED");
        } catch (Exception e) {
            String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage()));
        }
    }

    @Override
    public CollectedPage collect(String url, String competitorName, String sourceType) {
        return SourceCollector.super.collect(url, competitorName, sourceType);
    }

    @Override
    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
        List<CollectedPage> results = new ArrayList<>();
        Map<String, String> plannedUrls = deduplicateBatchUrls(urls);
        Map<String, Integer> blockedCountByDomain = new LinkedHashMap<>();
        Set<String> blockedDomains = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : plannedUrls.entrySet()) {
            String canonicalUrl = entry.getKey();
            String domain = canonicalUrlResolver.canonicalDomain(canonicalUrl);
            if (StringUtils.hasText(domain) && blockedDomains.contains(domain)) {
                results.add(failed(canonicalUrl, competitorName, sourceType, "blocked domain skip: " + domain));
                continue;
            }
            try {
                CollectedPage page = collect(canonicalUrl, competitorName, sourceType);
                results.add(page);
                if (isBlockedCollectedPage(page)) {
                    int blockedCount = blockedCountByDomain.getOrDefault(domain, 0) + 1;
                    blockedCountByDomain.put(domain, blockedCount);
                    if (StringUtils.hasText(domain) && blockedCount >= 2) {
                        blockedDomains.add(domain);
                    }
                }
            } catch (Exception e) {
                String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
                results.add(failed(canonicalUrl, competitorName, sourceType,
                        fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage())));
            }
        }
        return results;
    }

    /**
     * 对公开静态页面保留轻量 HTTP 路径。
     * 这里仍然需要内容质量判断，避免把 SPA 壳页误当成采集成功。
     */
    private CollectedPage collectByHttp(String url, String competitorName, String sourceType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(UrlSecurityUtils.requireHttpOrHttps(url, "collect.url"))
                    .timeout(Duration.ofSeconds(Math.max(1, collectorProperties.getPageTimeoutSeconds())))
                    .header("User-Agent", collectorProperties.getUserAgent())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                return failed(url, competitorName, sourceType, "HTTP status error: " + response.statusCode());
            }

            String html = response.body();
            String title = extractTitle(html);
            String content = cleanContent(htmlToText(html));
            if (content.isBlank()) {
                return failed(url, competitorName, sourceType, "HTTP content empty");
            }
            if (!isMeaningfulHttpContent(html, content)) {
                return failed(url, competitorName, sourceType, "HTTP content too thin");
            }

            return success(url, competitorName, sourceType, title, content, "http");
        } catch (Exception e) {
            return failed(url, competitorName, sourceType, "HTTP collect failed: " + e.getMessage());
        }
    }

    /**
     * 浏览器路径统一消费 request，保证 FULL_RENDER 相关的执行提示都不会丢。
     */
    private CollectedPage collectByBrowser(SourceCollectRequest request, String fallbackReason) {
        synchronized (browserManager) {
            return collectByBrowserLocked(request, fallbackReason);
        }
    }

    /**
     * Playwright Java API 不是线程安全的。
     * 这里把一次完整的页面采集放在浏览器管理器的独占执行区里，避免和搜索补源线程并发操作同一个 Browser。
     */
    private CollectedPage collectByBrowserLocked(SourceCollectRequest request, String fallbackReason) {
        String url = request.getUrl();
        String competitorName = request.getCompetitorName();
        String sourceType = request.getSourceType();
        Browser browser = browserManager.getBrowser();
        if (browser == null) {
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage("browser_unavailable", "browser unavailable"));
        }
        Page page = null;
        try {
            page = browser.newPage();
            page.setDefaultTimeout((double) resolveTimeoutMillis());
            navigateWithFallback(page, url);
            waitForRenderableContent(page, request);
            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, page);
        } catch (Exception e) {
            CollectedPage recoveredPage = tryRecoverPartiallyLoadedPage(url, competitorName, sourceType, fallbackReason, page, e);
            if (recoveredPage != null) {
                return recoveredPage;
            }
            BrowserFailureDecision decision = browserFailureClassifier.classify(e, null);
            if (decision.recreateRuntime()) {
                browserManager.recreateRuntimeForFailure("page collect failure: " + e.getMessage(), e);
            }
            if (decision.restartSharedBrowser()) {
                log.warn("检测到 Playwright 浏览器疑似失活，准备自动重启后重试: url={}, error={}",
                        UrlSecurityUtils.maskForLog(url), e.getMessage());
                browserManager.restartBrowserIfCurrent(browser, "page collect failure: " + e.getMessage());
                return retryCollectByBrowserLocked(request, fallbackReason, e);
            }
            log.error("页面采集失败: url={}, error={}", UrlSecurityUtils.maskForLog(url), e.getMessage());
            String failureCode = resolveFailureCode(decision, e);
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage()));
        } finally {
            closePageQuietly(page, "page collect primary page");
        }
    }

    private CollectedPage retryCollectByBrowser(SourceCollectRequest request,
                                                String fallbackReason,
                                                Exception originalException) {
        synchronized (browserManager) {
            return retryCollectByBrowserLocked(request, fallbackReason, originalException);
        }
    }

    private CollectedPage retryCollectByBrowserLocked(SourceCollectRequest request,
                                                      String fallbackReason,
                                                      Exception originalException) {
        String url = request.getUrl();
        String competitorName = request.getCompetitorName();
        String sourceType = request.getSourceType();
        Browser restartedBrowser = browserManager.getBrowser();
        if (restartedBrowser == null) {
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage("browser_unavailable", originalException.getMessage()));
        }
        Page retryPage = null;
        try {
            retryPage = restartedBrowser.newPage();
            retryPage.setDefaultTimeout((double) resolveTimeoutMillis());
            navigateWithFallback(retryPage, url);
            waitForRenderableContent(retryPage, request);
            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, retryPage);
        } catch (Exception retryException) {
            CollectedPage recoveredPage = tryRecoverPartiallyLoadedPage(
                    url, competitorName, sourceType, fallbackReason, retryPage, retryException);
            if (recoveredPage != null) {
                return recoveredPage;
            }
            String failureCode = resolveFailureCode(browserFailureClassifier.classify(retryException, null), retryException);
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, retryException.getMessage()));
        } finally {
            closePageQuietly(retryPage, "page collect retry page");
        }
    }

    private CollectedPage success(String url, String competitorName, String sourceType,
                                  String title, String content, String collector) {
        String snippet = content.length() > 500 ? content.substring(0, 500) + "..." : content;
        String metadata = "{\"collector\":\"" + escapeJson(collector) + "\",\"collectedAt\":\""
                + LocalDateTime.now().format(DTF) + "\"}";

        log.info("页面采集成功: collector={}, title={}, contentLength={}", collector, title, content.length());
        return CollectedPage.builder()
                .url(url)
                .title(title == null || title.isBlank() ? url : title)
                .content(content)
                .snippet(snippet)
                .metadata(metadata)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .collectedAt(LocalDateTime.now().format(DTF))
                .success(true)
                .build();
    }

    /**
     * FULL_RENDER 成功时需要把结构化抽取结果一并写进 metadata，
     * 这样后续执行器和 CollectorAgent 仍然消费 CollectedPage 时也不会丢失新增契约字段。
     */
    private CollectedPage successWithExtraction(String url,
                                                String competitorName,
                                                String sourceType,
                                                String title,
                                                String content,
                                                String collector,
                                                SourceCollectRequest request,
                                                PageContentExtractionResult extractionResult) {
        String snippet = content.length() > 500 ? content.substring(0, 500) + "..." : content;
        String metadata = buildStructuredMetadata(collector, request, extractionResult);
        String collectedAt = extractionResult != null && extractionResult.getCollectedAt() != null
                ? extractionResult.getCollectedAt().toString()
                : LocalDateTime.now().format(DTF);

        log.info("页面采集成功: collector={}, title={}, contentLength={}", collector, title, content.length());
        return CollectedPage.builder()
                .url(url)
                .title(title == null || title.isBlank() ? url : title)
                .content(content)
                .snippet(snippet)
                .metadata(metadata)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .collectedAt(collectedAt)
                .success(true)
                .build();
    }

    private CollectedPage failed(String url, String competitorName, String sourceType, String errorMessage) {
        return CollectedPage.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .collectedAt(LocalDateTime.now().format(DTF))
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 导航阶段只负责把页面打开到可继续判断的状态，不在这里强行等待所有资源彻底完成。
     */
    private void navigateWithFallback(Page page, String url) {
        UrlSecurityUtils.requireHttpOrHttps(url, "collect.url");
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout((double) Math.min(5000, resolveTimeoutMillis())));
        } catch (Exception e) {
            log.warn("页面 LOAD 等待超时，继续尝试后续提取: url={}, error={}",
                    UrlSecurityUtils.maskForLog(url), e.getMessage());
        }
    }

    /**
     * FULL_RENDER 路径必须等待“可提取页面”而不是只看导航是否结束。
     * readiness 失败时不直接抛弃页面，后续反爬识别和正文提取仍然可以继续判断是否可用。
     */
    private void waitForRenderableContent(Page page, SourceCollectRequest request) {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout((double) Math.min(5000, resolveTimeoutMillis())));
        } catch (Exception ignored) {
            // 页面可能仍在加载第三方资源，这里不直接中断采集。
        }
        try {
            page.waitForSelector(resolveRenderableSelector(request),
                    new Page.WaitForSelectorOptions().setTimeout((double) Math.min(4000, resolveTimeoutMillis())));
        } catch (Exception ignored) {
            // 页面可能没有明确 main/article 结构，继续交给正文提取与反爬识别判断。
        }
    }

    private String resolveRenderableSelector(SourceCollectRequest request) {
        if (request == null || request.getExpectedBlockTypes() == null || request.getExpectedBlockTypes().isEmpty()) {
            return RENDERABLE_SELECTOR;
        }
        return RENDERABLE_SELECTOR;
    }

    private CollectedPage extractRenderedPage(String url,
                                              String competitorName,
                                              String sourceType,
                                              String fallbackReason,
                                              Page page) {
        return extractRenderedPage(url, competitorName, sourceType, fallbackReason, null, page);
    }

    private CollectedPage extractRenderedPage(String url,
                                              String competitorName,
                                              String sourceType,
                                              String fallbackReason,
                                              SourceCollectRequest request,
                                              Page page) {
        String title = page.title();
        PageContentExtractionResult extractionResult = PageContentExtractionSupport.extract(page, sourceType);
        String content = extractionResult == null ? null : extractionResult.getMainContent();
        if (content == null || content.isBlank()) {
            return failed(url, competitorName, sourceType, "Playwright content empty");
        }
        AntiBotDetectionResult detection = antiBotSignalDetector.detect(BrowserSignalSnapshot.builder()
                .finalUrl(page.url())
                .pageTitle(title)
                .bodyText(content)
                .bodyLength(content.trim().length())
                .primaryResultCount(1)
                .missingPrimaryResults(false)
                .bodyTooShort(false)
                .build());
        if (detection.isBlocked()) {
            BrowserFailureDecision decision = browserFailureClassifier.classify(null, detection);
            String failureCode = resolveFailureCode(decision, null);
            logCollectionDiagnostic("page_collect_blocked",
                    competitorName,
                    sourceType,
                    page.url(),
                    decision.kind().name(),
                    resolveRestartScope(decision),
                    "HTTP_FALLBACK",
                    detection.getReasonCode(),
                    detection.getMatchedSignals());
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, detection.getReasonCode()));
        }

        return successWithExtraction(url,
                competitorName,
                sourceType,
                title,
                content,
                fallbackReason == null ? "playwright" : "playwright; fallbackReason=" + fallbackReason,
                request,
                extractionResult);
    }

    /**
     * 浏览器失败后尽量尝试恢复已经加载出来的正文，减少因晚到的脚本/资源超时而白白丢掉可用内容。
     */
    private CollectedPage tryRecoverPartiallyLoadedPage(String url,
                                                        String competitorName,
                                                        String sourceType,
                                                        String fallbackReason,
                                                        Page page,
                                                        Exception originalException) {
        if (page == null) {
            return null;
        }
        if (!fallbackPolicy.shouldRecoverPartialContentOnTimeout(null)
                && "search_timeout".equals(fallbackPolicy.classifyRuntimeFailure(originalException))) {
            return null;
        }
        try {
            PageContentExtractionResult extractionResult = PageContentExtractionSupport.extract(page, sourceType);
            String content = extractionResult == null ? null : extractionResult.getMainContent();
            if (content == null || content.isBlank()) {
                return null;
            }
            String title = page.title();
            String collector = "playwright-partial; navigationError=" + originalException.getMessage();
            if (fallbackReason != null && !fallbackReason.isBlank()) {
                collector += "; fallbackReason=" + fallbackReason;
            }
            log.warn("页面部分加载失败后恢复正文成功: url={}, contentLength={}",
                    UrlSecurityUtils.maskForLog(url), content.length());
            return successWithExtraction(url,
                    competitorName,
                    sourceType,
                    title,
                    content,
                    collector,
                    SourceCollectRequest.builder()
                            .url(url)
                            .competitorName(competitorName)
                            .sourceType(sourceType)
                            .sourceUrls(List.of(url))
                            .build(),
                    extractionResult);
        } catch (Exception recoveryException) {
            log.warn("页面部分加载恢复失败: url={}, error={}",
                    UrlSecurityUtils.maskForLog(url), recoveryException.getMessage());
            return null;
        }
    }

    private void closePageQuietly(Page page, String scene) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception e) {
            log.debug("close playwright collect page failed, scene={}, error={}", scene, e.getMessage());
        }
    }

    private String extractMainContent(Page page) {
        return PageContentExtractionSupport.extractMainContent(page);
    }

    /**
     * 浏览器路径需要把结构化抽取结果写进 metadata，供执行器与 CollectorAgent 回读。
     */
    private String buildStructuredMetadata(String collector,
                                           SourceCollectRequest request,
                                           PageContentExtractionResult extractionResult) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("{");
        metadata.append("\"collector\":\"").append(escapeJson(collector)).append("\"");
        metadata.append(",\"sourceUrls\":").append(toJsonStringArray(request == null ? null : request.getSourceUrls()));
        metadata.append(",\"qualitySignals\":").append(toJsonStringArray(extractionResult == null ? null : extractionResult.getQualitySignals()));
        metadata.append(",\"qualityScore\":").append(extractionResult == null || extractionResult.getQualityScore() == null
                ? "0.0"
                : extractionResult.getQualityScore());
        metadata.append(",\"structuredBlocks\":").append(toStructuredBlocksJson(extractionResult == null ? null : extractionResult.getStructuredBlocks()));
        if (extractionResult != null && extractionResult.getFailureKind() != null && !extractionResult.getFailureKind().isBlank()) {
            metadata.append(",\"failureKind\":\"").append(escapeJson(extractionResult.getFailureKind())).append("\"");
        }
        String collectedAt = extractionResult != null && extractionResult.getCollectedAt() != null
                ? extractionResult.getCollectedAt().toString()
                : Instant.now().toString();
        metadata.append(",\"collectedAt\":\"").append(escapeJson(collectedAt)).append("\"");
        metadata.append(",\"durationMillis\":").append(extractionResult == null || extractionResult.getDurationMillis() == null
                ? 0L
                : extractionResult.getDurationMillis());
        metadata.append("}");
        return metadata.toString();
    }

    private String toJsonStringArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(",");
            }
            builder.append("\"").append(escapeJson(values.get(index))).append("\"");
        }
        builder.append("]");
        return builder.toString();
    }

    private String toStructuredBlocksJson(List<cn.bugstack.competitoragent.collection.StructuredContentBlock> structuredBlocks) {
        if (structuredBlocks == null || structuredBlocks.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < structuredBlocks.size(); index++) {
            cn.bugstack.competitoragent.collection.StructuredContentBlock block = structuredBlocks.get(index);
            if (index > 0) {
                builder.append(",");
            }
            builder.append("{")
                    .append("\"blockType\":\"").append(escapeJson(block.getBlockType())).append("\"")
                    .append(",\"title\":\"").append(escapeJson(block.getTitle())).append("\"")
                    .append(",\"content\":\"").append(escapeJson(block.getContent())).append("\"")
                    .append(",\"qualitySignal\":\"").append(escapeJson(block.getQualitySignal())).append("\"")
                    .append("}");
        }
        builder.append("]");
        return builder.toString();
    }

    String selectBestContentBlock(List<Map<String, Object>> blocks) {
        return PageContentExtractionSupport.selectBestContentBlock(blocks);
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html == null ? "" : html);
        return matcher.find() ? cleanContent(matcher.group(1)) : "";
    }

    private String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>|</div>|</section>|</article>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
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

    private String removeNoiseLines(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> cleanedLines = new ArrayList<>();
        for (String rawLine : content.split("\\n+")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("skip to content")
                    || normalized.startsWith("cookie")
                    || normalized.contains("accept cookies")
                    || normalized.contains("privacy preference")
                    || normalized.contains("subscribe")
                    || normalized.contains("breadcrumb")) {
                continue;
            }
            cleanedLines.add(line);
        }
        return String.join("\n", cleanedLines);
    }

    boolean isMeaningfulHttpContent(String html, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (content.length() < MIN_HTTP_CONTENT_LENGTH) {
            return false;
        }

        String normalizedHtml = html == null ? "" : html.toLowerCase(Locale.ROOT);
        boolean looksLikeSpaShell = SPA_SHELL_SIGNALS.stream().anyMatch(normalizedHtml::contains);
        int meaningfulUnits = countLatinWords(content) + countCjkChars(content);
        int longLineCount = countLongLines(content);

        if (meaningfulUnits < MIN_MEANINGFUL_TEXT_UNITS && longLineCount < 2) {
            return false;
        }
        return !looksLikeSpaShell || meaningfulUnits >= MIN_MEANINGFUL_TEXT_UNITS * 2 || longLineCount >= 3;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<String> safelyLimitUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        return urls.size() > 5 ? urls.subList(0, 5) : urls;
    }

    /**
     * 批量采集前先按 canonical URL 去重，避免同一页面因为协议、www 或追踪参数不同而重复抓取。
     */
    private Map<String, String> deduplicateBatchUrls(List<String> urls) {
        Map<String, String> deduplicated = new LinkedHashMap<>();
        for (String url : safelyLimitUrls(urls)) {
            String canonicalUrl = canonicalUrlResolver.canonicalize(url);
            if (StringUtils.hasText(canonicalUrl)) {
                deduplicated.putIfAbsent(canonicalUrl, url);
            }
        }
        return deduplicated;
    }

    /**
     * 只有明确 blocked 的失败才会计入同域熔断，避免普通超时误伤后续页面。
     */
    private boolean isBlockedCollectedPage(CollectedPage page) {
        if (page == null || page.isSuccess() || !StringUtils.hasText(page.getErrorMessage())) {
            return false;
        }
        String normalized = page.getErrorMessage().toLowerCase(Locale.ROOT);
        return normalized.contains("blocked")
                || normalized.contains("captcha")
                || normalized.contains("challenge")
                || normalized.contains("access denied");
    }

    private int resolveTimeoutMillis() {
        return Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000);
    }

    private boolean requiresFullRender(WebPageRenderHint renderHint) {
        return renderHint == WebPageRenderHint.FULL_RENDER
                || renderHint == WebPageRenderHint.LOGIN_REQUIRED
                || renderHint == WebPageRenderHint.INTERACTION_REQUIRED
                || renderHint == WebPageRenderHint.ANTI_BOT_RISK_HIGH;
    }

    private int countLatinWords(String text) {
        Matcher matcher = LATIN_WORD_PATTERN.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countCjkChars(String text) {
        Matcher matcher = CJK_PATTERN.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countLongLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\n+")) {
            if (line.trim().length() >= 60) {
                count++;
            }
        }
        return count;
    }

    /**
     * 页面采集链路沿用现有有限失败码，避免一次性改动所有失败文案；
     * 但真正的恢复动作已经完全由 BrowserFailureDecision 决定。
     */
    private String resolveFailureCode(BrowserFailureDecision decision, Throwable error) {
        if (decision == null) {
            return fallbackPolicy.classifyRuntimeFailure(error);
        }
        return switch (decision.kind()) {
            case PAGE_TIMEOUT, SEARCH_TIMEOUT -> "search_timeout";
            case BROWSER_INSTANCE_DEAD, RUNTIME_PIPE_BROKEN -> "browser_unavailable";
            case ANTI_BOT_BLOCKED -> "blocked";
            default -> fallbackPolicy.classifyRuntimeFailure(error);
        };
    }

    /**
     * 页面采集链路的 blocked/failure 日志与搜索链路共用同一 DTO，
     * 便于后续按 competitor、sourceType、failureKind 聚合排查问题。
     */
    private void logCollectionDiagnostic(String event,
                                         String competitorName,
                                         String sourceType,
                                         String targetUrl,
                                         String failureKind,
                                         String restartScope,
                                         String fallbackAction,
                                         String blockedReasonCode,
                                         List<String> matchedSignals) {
        diagnosticLogger.log(event, BrowserRuntimeDiagnosticLog.builder()
                .competitorName(competitorName)
                .sourceType(sourceType)
                .targetUrl(targetUrl)
                .failureKind(failureKind)
                .restartScope(restartScope)
                .fallbackAction(fallbackAction)
                .blockedReasonCode(blockedReasonCode)
                .matchedSignals(matchedSignals)
                .build());
    }

    private String resolveRestartScope(BrowserFailureDecision decision) {
        if (decision == null) {
            return "NONE";
        }
        if (decision.recreateRuntime()) {
            return "RUNTIME_AND_BROWSER";
        }
        if (decision.restartSharedBrowser()) {
            return "BROWSER";
        }
        if (decision.closeContextOnly()) {
            return "CONTEXT";
        }
        if (decision.closePageOnly()) {
            return "PAGE";
        }
        return "NONE";
    }
}
