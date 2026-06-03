package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public CollectedPage collect(String url, String competitorName, String sourceType) {
        try {
            if (!UrlSecurityUtils.isHttpUrl(url)) {
                return failed(url, competitorName, sourceType, "仅允许采集 http/https 页面");
            }
            log.info("开始采集页面, url={}, competitor={}, sourceType={}",
                    UrlSecurityUtils.maskForLog(url),
                    UrlSecurityUtils.maskForLog(competitorName),
                    sourceType);

            CollectedPage httpPage = collectByHttp(url, competitorName, sourceType);
            if (httpPage.isSuccess()) {
                return httpPage;
            }

            log.info("轻量 HTTP 采集未满足要求，回退到 Playwright 渲染, url={}",
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
        for (String url : safelyLimitUrls(urls)) {
            try {
                results.add(collect(url, competitorName, sourceType));
            } catch (Exception e) {
                String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
                results.add(failed(url, competitorName, sourceType,
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
                return failed(url, competitorName, sourceType, "HTTP 状态码异常: " + response.statusCode());
            }

            String html = response.body();
            String title = extractTitle(html);
            String content = cleanContent(htmlToText(html));
            if (content.isBlank()) {
                return failed(url, competitorName, sourceType, "HTTP 页面正文为空");
            }
            if (!isMeaningfulHttpContent(html, content)) {
                return failed(url, competitorName, sourceType, "HTTP 页面疑似前端壳或正文过薄");
            }

            return success(url, competitorName, sourceType, title, content, "http");
        } catch (Exception e) {
            return failed(url, competitorName, sourceType, "HTTP 采集失败: " + e.getMessage());
        }
    }

    private CollectedPage collectByBrowser(String url, String competitorName, String sourceType, String fallbackReason) {
        Browser browser = browserManager.getBrowser();
        if (browser == null) {
            return failed(url, competitorName, sourceType,
                    fallbackPolicy.buildCollectionFailureMessage("browser_unavailable",
                            "请检查浏览器依赖、系统内存或稍后重试"));
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
            if (shouldRestartBrowser(e)) {
                log.warn("检测到 Playwright 浏览器疑似失活，准备自动重启后重试: url={}, error={}",
                        UrlSecurityUtils.maskForLog(url), e.getMessage());
                browserManager.restartBrowser("page collect failure: " + e.getMessage());
                return retryCollectByBrowser(url, competitorName, sourceType, fallbackReason, e);
            }
            log.error("页面采集失败: url={}, error={}", UrlSecurityUtils.maskForLog(url), e.getMessage());
            String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
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
            String failureCode = fallbackPolicy.classifyRuntimeFailure(retryException);
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
     * 对真实站点优先等待 DOMContentLoaded，避免被长期不断开的请求拖死在 NETWORKIDLE 上。
     * 如果页面还能继续稳定加载，再补一个短等待窗口尽量拿到更多正文。
     */
    private void navigateWithFallback(Page page, String url) {
        UrlSecurityUtils.requireHttpOrHttps(url, "collect.url");
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout((double) Math.min(5000, resolveTimeoutMillis())));
        } catch (Exception e) {
            log.warn("页面未在短时间内进入 LOAD 状态，继续尝试提取正文: url={}, error={}",
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
            return failed(url, competitorName, sourceType, "Playwright 页面正文为空");
        }

        return success(url, competitorName, sourceType, title, content,
                fallbackReason == null ? "playwright" : "playwright; fallbackReason=" + fallbackReason);
    }

    /**
     * 某些页面虽然导航超时，但主体已经渲染完成，此时尽量回收已加载内容，
     * 避免把“页面可读但网络未空闲”的情况直接当作硬失败。
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
            log.warn("页面导航异常但已回收部分正文, url={}, contentLength={}",
                    UrlSecurityUtils.maskForLog(url), content.length());
            return success(url, competitorName, sourceType, title, content, collector);
        } catch (Exception recoveryException) {
            log.warn("页面导航异常后正文回收失败: url={}, error={}",
                    UrlSecurityUtils.maskForLog(url), recoveryException.getMessage());
            return null;
        }
    }

    /**
     * 关闭浏览器页面时只记录日志，不把清理阶段的异常再向上抛出。
     * 这样即使单页资源回收失败，也不会把批量采集链路整体拖垮。
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

    private boolean shouldRestartBrowser(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("target page, context or browser has been closed")
                || message.contains("browser has been closed")
                || message.contains("connection closed")
                || message.contains("playwright connection closed");
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
}
