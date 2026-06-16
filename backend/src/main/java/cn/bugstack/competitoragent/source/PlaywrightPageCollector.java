package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import cn.bugstack.competitoragent.search.AntiBotSignalDetector;
import cn.bugstack.competitoragent.search.BrowserSignalSnapshot;
import cn.bugstack.competitoragent.search.BrowserFailureClassifier;
import cn.bugstack.competitoragent.search.BrowserFailureDecision;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLog;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

@Slf4j
@Component
public class PlaywrightPageCollector implements SourceCollector {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LATIN_WORD_PATTERN = Pattern.compile("\\b[a-zA-Z]{3,}\\b");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final int MIN_HTTP_CONTENT_LENGTH = 280;
    private static final int MIN_MEANINGFUL_TEXT_UNITS = 80;
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
    public CollectedPage collect(String url, String competitorName, String sourceType) {
        try {
            if (!UrlSecurityUtils.isHttpUrl(url)) {
                return failed(url, competitorName, sourceType, "浠呭厑璁搁噰闆?http/https 椤甸潰");
            }
            log.info("寮€濮嬮噰闆嗛〉闈? url={}, competitor={}, sourceType={}",
                    UrlSecurityUtils.maskForLog(url),
                    UrlSecurityUtils.maskForLog(competitorName),
                    sourceType);

            CollectedPage httpPage = collectByHttp(url, competitorName, sourceType);
            if (httpPage.isSuccess()) {
                return httpPage;
            }

            log.info("杞婚噺 HTTP 閲囬泦鏈弧瓒宠姹傦紝鍥為€€鍒?Playwright 娓叉煋, url={}",
                    UrlSecurityUtils.maskForLog(url));
            return collectByBrowser(url, competitorName, sourceType, httpPage.getErrorMessage());
        } catch (Exception e) {
            String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage()));
        }
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
                results.add(failed(canonicalUrl, competitorName, sourceType,
                        "同域名已连续命中 blocked，提前停止后续浏览器访问: " + domain));
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

    private CollectedPage collectByHttp(String url, String competitorName, String sourceType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(UrlSecurityUtils.requireHttpOrHttps(url, "collect.url"))
                    .timeout(Duration.ofSeconds(Math.max(1, collectorProperties.getPageTimeoutSeconds())))
                    .header("User-Agent", collectorProperties.getUserAgent())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                return failed(url, competitorName, sourceType, "HTTP 鐘舵€佺爜寮傚父: " + response.statusCode());
            }

            String html = response.body();
            String title = extractTitle(html);
            String content = cleanContent(htmlToText(html));
            if (content.isBlank()) {
                return failed(url, competitorName, sourceType, "HTTP 椤甸潰姝ｆ枃涓虹┖");
            }
            if (!isMeaningfulHttpContent(html, content)) {
                return failed(url, competitorName, sourceType, "HTTP 椤甸潰鐤戜技鍓嶇澹虫垨姝ｆ枃杩囪杽");
            }

            return success(url, competitorName, sourceType, title, content, "http");
        } catch (Exception e) {
            return failed(url, competitorName, sourceType, "HTTP 閲囬泦澶辫触: " + e.getMessage());
        }
    }

    private CollectedPage collectByBrowser(String url, String competitorName, String sourceType, String fallbackReason) {
        Browser browser = browserManager.getBrowser();
        if (browser == null) {
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage("browser_unavailable",
                            "璇锋鏌ユ祻瑙堝櫒渚濊禆銆佺郴缁熷唴瀛樻垨绋嶅悗閲嶈瘯"));
        }
        Page page = null;
        try {
            page = browser.newPage();
            page.setDefaultTimeout((double) resolveTimeoutMillis());
            navigateWithFallback(page, url);

            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, page);
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
                log.warn("妫€娴嬪埌 Playwright 娴忚鍣ㄧ枒浼煎け娲伙紝鍑嗗鑷姩閲嶅惎鍚庨噸璇? url={}, error={}",
                        UrlSecurityUtils.maskForLog(url), e.getMessage());
                browserManager.restartBrowserIfCurrent(browser, "page collect failure: " + e.getMessage());
                return retryCollectByBrowser(url, competitorName, sourceType, fallbackReason, e);
            }
            log.error("椤甸潰閲囬泦澶辫触: url={}, error={}", UrlSecurityUtils.maskForLog(url), e.getMessage());
            String failureCode = resolveFailureCode(decision, e);
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage()));
        } finally {
            closePageQuietly(page, "page collect primary page");
        }
    }

    private CollectedPage retryCollectByBrowser(String url,
                                                String competitorName,
                                                String sourceType,
                                                String fallbackReason,
                                                Exception originalException) {
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
            return extractRenderedPage(url, competitorName, sourceType, fallbackReason, retryPage);
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

        log.info("椤甸潰閲囬泦鎴愬姛: collector={}, title={}, contentLength={}", collector, title, content.length());
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
     * 瀵圭湡瀹炵珯鐐逛紭鍏堢瓑寰?DOMContentLoaded锛岄伩鍏嶈闀挎湡涓嶆柇寮€鐨勮姹傛嫋姝诲湪 NETWORKIDLE 涓娿€?
     * 濡傛灉椤甸潰杩樿兘缁х画绋冲畾鍔犺浇锛屽啀琛ヤ竴涓煭绛夊緟绐楀彛灏介噺鎷垮埌鏇村姝ｆ枃銆?
     */
    private void navigateWithFallback(Page page, String url) {
        UrlSecurityUtils.requireHttpOrHttps(url, "collect.url");
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout((double) Math.min(5000, resolveTimeoutMillis())));
        } catch (Exception e) {
            log.warn("椤甸潰鏈湪鐭椂闂村唴杩涘叆 LOAD 鐘舵€侊紝缁х画灏濊瘯鎻愬彇姝ｆ枃: url={}, error={}",
                    UrlSecurityUtils.maskForLog(url), e.getMessage());
        }
    }

    private CollectedPage extractRenderedPage(String url,
                                              String competitorName,
                                              String sourceType,
                                              String fallbackReason,
                                              Page page) {
        String title = page.title();
        String content = extractMainContent(page);
        if (content == null || content.isBlank()) {
            return failed(url, competitorName, sourceType, "Playwright 椤甸潰姝ｆ枃涓虹┖");
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

        return success(url, competitorName, sourceType, title, content,
                fallbackReason == null ? "playwright" : "playwright; fallbackReason=" + fallbackReason);
    }

    /**
     * 鏌愪簺椤甸潰铏界劧瀵艰埅瓒呮椂锛屼絾涓讳綋宸茬粡娓叉煋瀹屾垚锛屾鏃跺敖閲忓洖鏀跺凡鍔犺浇鍐呭锛?
     * 閬垮厤鎶娾€滈〉闈㈠彲璇讳絾缃戠粶鏈┖闂测€濈殑鎯呭喌鐩存帴褰撲綔纭け璐ャ€?
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
            String content = extractMainContent(page);
            if (content == null || content.isBlank()) {
                return null;
            }
            String title = page.title();
            String collector = "playwright-partial; navigationError=" + originalException.getMessage();
            if (fallbackReason != null && !fallbackReason.isBlank()) {
                collector += "; fallbackReason=" + fallbackReason;
            }
            log.warn("椤甸潰瀵艰埅寮傚父浣嗗凡鍥炴敹閮ㄥ垎姝ｆ枃, url={}, contentLength={}",
                    UrlSecurityUtils.maskForLog(url), content.length());
            return success(url, competitorName, sourceType, title, content, collector);
        } catch (Exception recoveryException) {
            log.warn("椤甸潰瀵艰埅寮傚父鍚庢鏂囧洖鏀跺け璐? url={}, error={}",
                    UrlSecurityUtils.maskForLog(url), recoveryException.getMessage());
            return null;
        }
    }

    /**
     * 鍏抽棴娴忚鍣ㄩ〉闈㈡椂鍙褰曟棩蹇楋紝涓嶆妸娓呯悊闃舵鐨勫紓甯稿啀鍚戜笂鎶涘嚭銆?
     * 杩欐牱鍗充娇鍗曢〉璧勬簮鍥炴敹澶辫触锛屼篃涓嶄細鎶婃壒閲忛噰闆嗛摼璺暣浣撴嫋鍨€?
     */
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

    String selectBestContentBlock(List<Map<String, Object>> blocks) {
        return PageContentExtractionSupport.selectBestContentBlock(blocks);
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
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
        if (looksLikeSpaShell && meaningfulUnits < MIN_MEANINGFUL_TEXT_UNITS * 2 && longLineCount < 3) {
            return false;
        }
        return true;
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
     * 批量采集前先把 canonical URL 收敛掉，避免同一页面仅因协议、www 或追踪参数不同而重复抓取。
     * 这里保留 LinkedHashMap 顺序，保证批量执行顺序对调用方仍然稳定可预期。
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
     * Task 5 只要求对明确 blocked 的同域请求做提前止损，
     * 因此这里故意不把所有失败都计入阈值，避免普通超时或网络抖动误伤同域后续采集。
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
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
     * 页面采集链路的 blocked/failure 日志和搜索链路共用同一 DTO，
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
