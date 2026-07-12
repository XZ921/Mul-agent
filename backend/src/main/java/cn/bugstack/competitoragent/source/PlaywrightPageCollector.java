package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.common.http.HardTimeoutHttpClient;
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
    private static final long MIN_HTTP_START_BUDGET_MILLIS = 1000L;
    private static final long MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS = 250L;
    private static final String DEFAULT_COLLECTOR_DEADLINE_REASON = "HARD_DEADLINE_REACHED";
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
    private final PublicShellRecoveryExtractor publicShellRecoveryExtractor;
    private final HttpClient httpClient;

    /**
     * HTTP 快路的客户端单独抽成工厂方法，既便于测试注入，也避免各构造器重复拼接默认配置。
     */
    private static HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

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
                new CanonicalUrlResolver(),
                new PublicShellRecoveryExtractor(),
                null);
    }

    public PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                                   CollectorProperties collectorProperties,
                                   SearchRuntimeFallbackPolicy fallbackPolicy,
                                   BrowserFailureClassifier browserFailureClassifier,
                                   AntiBotSignalDetector antiBotSignalDetector,
                                   BrowserRuntimeDiagnosticLogger diagnosticLogger,
                                   CanonicalUrlResolver canonicalUrlResolver) {
        this(browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector,
                diagnosticLogger,
                canonicalUrlResolver,
                new PublicShellRecoveryExtractor(),
                null);
    }

    public PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                                   CollectorProperties collectorProperties,
                                   SearchRuntimeFallbackPolicy fallbackPolicy,
                                   BrowserFailureClassifier browserFailureClassifier,
                                   AntiBotSignalDetector antiBotSignalDetector,
                                   BrowserRuntimeDiagnosticLogger diagnosticLogger,
                                   CanonicalUrlResolver canonicalUrlResolver,
                                   PublicShellRecoveryExtractor publicShellRecoveryExtractor) {
        this(browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector,
                diagnosticLogger,
                canonicalUrlResolver,
                publicShellRecoveryExtractor,
                null);
    }

    PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                            CollectorProperties collectorProperties,
                            SearchRuntimeFallbackPolicy fallbackPolicy,
                            BrowserFailureClassifier browserFailureClassifier,
                            AntiBotSignalDetector antiBotSignalDetector,
                            BrowserRuntimeDiagnosticLogger diagnosticLogger,
                            CanonicalUrlResolver canonicalUrlResolver,
                            PublicShellRecoveryExtractor publicShellRecoveryExtractor,
                            HttpClient httpClient) {
        this.browserManager = browserManager;
        this.collectorProperties = collectorProperties;
        this.fallbackPolicy = fallbackPolicy;
        this.browserFailureClassifier = browserFailureClassifier;
        this.antiBotSignalDetector = antiBotSignalDetector;
        this.diagnosticLogger = diagnosticLogger;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.publicShellRecoveryExtractor = publicShellRecoveryExtractor == null
                ? new PublicShellRecoveryExtractor()
                : publicShellRecoveryExtractor;
        this.httpClient = httpClient == null ? buildHttpClient() : httpClient;
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

            CollectedPage deadlineFailure = failIfCollectorDeadlineExpired(
                    request,
                    "collector hard deadline reached before page collection");
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            boolean forceFullRender = requiresFullRender(request.getRenderHint());
            if (!forceFullRender) {
                CollectedPage httpPage = collectByHttp(request);
                if (httpPage.isSuccess() || isHardDeadlineFailure(httpPage)) {
                    return httpPage;
                }
                CollectedPage fallbackDeadlineFailure = failIfCollectorDeadlineExpired(
                        request,
                        "collector hard deadline reached before browser fallback");
                if (fallbackDeadlineFailure != null) {
                    return fallbackDeadlineFailure;
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
    /**
     * 公开静态页面优先走 HTTP 快路。
     * 这里改成包内可见，便于测试只覆盖 HTTP 超时语义，而不被浏览器兜底路径掩盖。
     */
    CollectedPage collectByHttp(String url, String competitorName, String sourceType) {
        return collectByHttp(SourceCollectRequest.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .sourceUrls(StringUtils.hasText(url) ? List.of(url) : List.of())
                .build());
    }

    CollectedPage collectByHttp(SourceCollectRequest request) {
        String url = request == null ? null : request.getUrl();
        String competitorName = request == null ? null : request.getCompetitorName();
        String sourceType = request == null ? null : request.getSourceType();
        try {
            CollectedPage deadlineFailure = failIfCollectorDeadlineExpired(
                    request,
                    "collector hard deadline reached before HTTP page collection");
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            Duration effectiveTimeout = resolveEffectiveHttpTimeout(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(UrlSecurityUtils.requireHttpOrHttps(url, "collect.url"))
                    .timeout(effectiveTimeout)
                    .header("User-Agent", collectorProperties.getUserAgent())
                    .GET()
                    .build();

            /**
             * HTTP 快路必须沿用页面采集的既有 timeout 预算，
             * 不能为了解决卡死问题引入更短的固定超时，否则会和浏览器渲染预算口径冲突。
             */
            HttpResponse<String> response = HardTimeoutHttpClient.send(
                    httpClient,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(),
                    effectiveTimeout
            );
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
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            return deadlineReachedPage(request, deadlineReachedException.getMessage());
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
        Browser browser = null;
        Page page = null;
        try {
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before browser page collection");
            browser = browserManager.getBrowser();
            if (browser == null) {
                return failed(url, competitorName, sourceType,
                        fallbackPolicy.buildCollectionFailureMessage("browser_unavailable", "browser unavailable"));
            }
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before browser page creation");
            page = browser.newPage();
            double actionTimeoutMillis = resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis());
            page.setDefaultTimeout(actionTimeoutMillis);
            page.setDefaultNavigationTimeout(actionTimeoutMillis);
            navigateWithFallback(page, url, request);
            waitForRenderableContent(page, request);
            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, page);
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            return deadlineReachedPage(request, deadlineReachedException.getMessage());
        } catch (Exception e) {
            CollectedPage recoveredPage = tryRecoverPartiallyLoadedPage(url, competitorName, sourceType, fallbackReason, page, e);
            if (recoveredPage != null) {
                return recoveredPage;
            }
            CollectedPage deadlineFailure = failIfCollectorDeadlineExpired(
                    request,
                    "collector hard deadline reached before browser retry");
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            BrowserFailureDecision decision = browserFailureClassifier.classify(e, null);
            if (decision.recreateRuntime()) {
                browserManager.recreateRuntimeForFailure("page collect failure: " + e.getMessage(), e);
            }
            if (decision.restartSharedBrowser()) {
                log.warn("检测到 Playwright 浏览器疑似失活，准备自动重启后重试: url={}, error={}",
                        UrlSecurityUtils.maskForLog(url), e.getMessage());
                CollectedPage retryDeadlineFailure = failIfCollectorDeadlineExpired(
                        request,
                        "collector hard deadline reached before browser retry");
                if (retryDeadlineFailure != null) {
                    return retryDeadlineFailure;
                }
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
        Browser restartedBrowser = null;
        Page retryPage = null;
        try {
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before browser retry");
            restartedBrowser = browserManager.getBrowser();
            if (restartedBrowser == null) {
                return failed(url, competitorName, sourceType,
                        fallbackPolicy.buildCollectionFailureMessage("browser_unavailable", originalException.getMessage()));
            }
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before retry page creation");
            retryPage = restartedBrowser.newPage();
            double actionTimeoutMillis = resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis());
            retryPage.setDefaultTimeout(actionTimeoutMillis);
            retryPage.setDefaultNavigationTimeout(actionTimeoutMillis);
            navigateWithFallback(retryPage, url, request);
            waitForRenderableContent(retryPage, request);
            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, retryPage);
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            return deadlineReachedPage(request, deadlineReachedException.getMessage());
        } catch (Exception retryException) {
            CollectedPage recoveredPage = tryRecoverPartiallyLoadedPage(
                    url, competitorName, sourceType, fallbackReason, retryPage, retryException);
            if (recoveredPage != null) {
                return recoveredPage;
            }
            CollectedPage deadlineFailure = failIfCollectorDeadlineExpired(
                    request,
                    "collector hard deadline reached before retry page collection");
            if (deadlineFailure != null) {
                return deadlineFailure;
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
     * deadline 失败也要把 sourceUrls / failureKind 写回 metadata，
     * 否则上层只能看到一个模糊的 runtime failure，无法识别这是 hard deadline 主动停机。
     */
    private CollectedPage deadlineReachedPage(SourceCollectRequest request, String scene) {
        String url = request == null ? null : request.getUrl();
        String competitorName = request == null ? null : request.getCompetitorName();
        String sourceType = request == null ? null : request.getSourceType();
        String failureReason = resolveCollectorDeadlineReason(request);
        return CollectedPage.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .metadata(buildFailureMetadata("collector-deadline", request, failureReason, List.of(failureReason)))
                .collectedAt(LocalDateTime.now().format(DTF))
                .success(false)
                .errorMessage(scene + " [" + failureReason + "]")
                .build();
    }

    private String buildFailureMetadata(String collector,
                                        SourceCollectRequest request,
                                        String failureKind,
                                        List<String> qualitySignals) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("{");
        metadata.append("\"collector\":\"").append(escapeJson(collector)).append("\"");
        metadata.append(",\"sourceUrls\":").append(toJsonStringArray(resolveRequestSourceUrls(request)));
        metadata.append(",\"qualitySignals\":").append(toJsonStringArray(qualitySignals));
        metadata.append(",\"qualityScore\":0.0");
        metadata.append(",\"structuredBlocks\":[]");
        metadata.append(",\"failureKind\":\"").append(escapeJson(failureKind)).append("\"");
        metadata.append(",\"collectedAt\":\"").append(escapeJson(Instant.now().toString())).append("\"");
        metadata.append(",\"durationMillis\":0");
        metadata.append("}");
        return metadata.toString();
    }

    private List<String> resolveRequestSourceUrls(SourceCollectRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.getSourceUrls() != null && !request.getSourceUrls().isEmpty()) {
            return request.getSourceUrls();
        }
        return StringUtils.hasText(request.getUrl()) ? List.of(request.getUrl()) : List.of();
    }

    /**
     * 导航阶段只负责把页面打开到可继续判断的状态，不在这里强行等待所有资源彻底完成。
     */
    private void navigateWithFallback(Page page, String url, SourceCollectRequest request) {
        UrlSecurityUtils.requireHttpOrHttps(url, "collect.url");
        ensureCollectorBudgetOrThrow(
                request,
                MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                "collector hard deadline reached before browser navigation");
        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis())));
        try {
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before page load wait");
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout(resolvePlaywrightActionTimeoutMillis(request, 5000L)));
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            throw deadlineReachedException;
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
        ensureCollectorBudgetOrThrow(
                request,
                MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                "collector hard deadline reached before renderable-content wait");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions()
                        .setTimeout(resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis())));
        try {
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before page load wait");
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout(resolvePlaywrightActionTimeoutMillis(request, 5000L)));
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            throw deadlineReachedException;
        } catch (Exception ignored) {
            // 页面可能仍在加载第三方资源，这里不直接中断采集。
        }
        try {
            ensureCollectorBudgetOrThrow(
                    request,
                    MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                    "collector hard deadline reached before selector wait");
            page.waitForSelector(resolveRenderableSelector(request),
                    new Page.WaitForSelectorOptions()
                            .setTimeout(resolvePlaywrightActionTimeoutMillis(request, 4000L)));
        } catch (CollectorDeadlineReachedException deadlineReachedException) {
            throw deadlineReachedException;
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
            CollectedPage recoveredShell = tryRecoverPublicShell(
                    url,
                    competitorName,
                    sourceType,
                    page,
                    title,
                    content,
                    null
            );
            if (recoveredShell != null) {
                return recoveredShell;
            }
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
        CollectedPage recoveredShell = tryRecoverPublicShell(
                url,
                competitorName,
                sourceType,
                page,
                title,
                content,
                detection
        );
        if (recoveredShell != null) {
            return recoveredShell;
        }
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
     * 公开壳恢复只在 utility gate / blocked 页面触发，
     * 成功时返回降级证据，失败时对真正 blocked 页面继续沿用原失败契约。
     */
    private CollectedPage tryRecoverPublicShell(String url,
                                                String competitorName,
                                                String sourceType,
                                                Page page,
                                                String title,
                                                String content,
                                                AntiBotDetectionResult detection) {
        String finalUrl = page == null ? url : page.url();
        if (!publicShellRecoveryExtractor.shouldAttemptRecovery(finalUrl, title, content, detection)) {
            return null;
        }
        CollectedPage recoveredShell = publicShellRecoveryExtractor.recover(
                page,
                url,
                competitorName,
                sourceType,
                detection
        );
        if (recoveredShell != null && recoveredShell.isSuccess()) {
            log.warn("页面命中登录/验证码/反爬信号，但公开壳信息恢复成功: url={}, title={}",
                    UrlSecurityUtils.maskForLog(url), recoveredShell.getTitle());
            return recoveredShell;
        }
        if (detection != null && detection.isBlocked()) {
            return null;
        }
        return failed(url,
                competitorName,
                sourceType,
                recoveredShell == null ? "公开壳信息不足，不能作为降级证据" : recoveredShell.getErrorMessage());
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

    CollectedPage extractRenderedPageForTest(String url,
                                             String competitorName,
                                             String sourceType,
                                             String fallbackReason,
                                             SourceCollectRequest request,
                                             Page page) {
        return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, page);
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

    private CollectedPage failIfCollectorDeadlineExpired(SourceCollectRequest request, String scene) {
        return isCollectorDeadlineExpired(request) ? deadlineReachedPage(request, scene) : null;
    }

    private boolean isCollectorDeadlineExpired(SourceCollectRequest request) {
        long remainingMillis = resolveRemainingCollectorDeadlineMillis(request);
        return remainingMillis != Long.MAX_VALUE && remainingMillis <= 0L;
    }

    private long resolveRemainingCollectorDeadlineMillis(SourceCollectRequest request) {
        if (request == null || request.getCollectorHardDeadlineEpochMillis() == null) {
            return Long.MAX_VALUE;
        }
        long hardDeadlineEpochMillis = request.getCollectorHardDeadlineEpochMillis();
        if (hardDeadlineEpochMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return hardDeadlineEpochMillis - System.currentTimeMillis();
    }

    /**
     * Playwright/HTTP 都属于“启动新外部工作”，
     * 所以在真正发起动作前必须确认剩余 hard deadline 预算足够覆盖最小动作窗口。
     */
    private void ensureCollectorBudgetOrThrow(SourceCollectRequest request,
                                              long minBudgetMillis,
                                              String scene) {
        long remainingMillis = resolveRemainingCollectorDeadlineMillis(request);
        if (remainingMillis == Long.MAX_VALUE) {
            return;
        }
        if (remainingMillis < Math.max(1L, minBudgetMillis)) {
            throw new CollectorDeadlineReachedException(scene);
        }
    }

    private Duration resolveEffectiveHttpTimeout(SourceCollectRequest request) {
        Duration configuredTimeout = Duration.ofSeconds(Math.max(1, collectorProperties.getPageTimeoutSeconds()));
        long remainingMillis = resolveRemainingCollectorDeadlineMillis(request);
        if (remainingMillis == Long.MAX_VALUE) {
            return configuredTimeout;
        }
        ensureCollectorBudgetOrThrow(
                request,
                MIN_HTTP_START_BUDGET_MILLIS,
                "collector hard deadline reached before HTTP page collection");
        return Duration.ofMillis(Math.min(configuredTimeout.toMillis(), remainingMillis));
    }

    private double resolvePlaywrightActionTimeoutMillis(SourceCollectRequest request, long configuredTimeoutMillis) {
        long remainingMillis = resolveRemainingCollectorDeadlineMillis(request);
        if (remainingMillis == Long.MAX_VALUE) {
            return (double) configuredTimeoutMillis;
        }
        return (double) Math.max(
                MIN_PLAYWRIGHT_ACTION_TIMEOUT_MILLIS,
                Math.min(configuredTimeoutMillis, remainingMillis));
    }

    private boolean isHardDeadlineFailure(CollectedPage page) {
        if (page == null) {
            return false;
        }
        return (StringUtils.hasText(page.getErrorMessage())
                && page.getErrorMessage().contains(DEFAULT_COLLECTOR_DEADLINE_REASON))
                || (StringUtils.hasText(page.getMetadata())
                && page.getMetadata().contains(DEFAULT_COLLECTOR_DEADLINE_REASON));
    }

    private String resolveCollectorDeadlineReason(SourceCollectRequest request) {
        if (request == null || !StringUtils.hasText(request.getCollectorDeadlineReason())) {
            return DEFAULT_COLLECTOR_DEADLINE_REASON;
        }
        return request.getCollectorDeadlineReason();
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

    private static class CollectorDeadlineReachedException extends RuntimeException {

        private CollectorDeadlineReachedException(String message) {
            super(message);
        }
    }
}
