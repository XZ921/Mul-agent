package cn.bugstack.competitoragent.source;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "collector.mock", havingValue = "false")
public class PlaywrightPageCollector implements SourceCollector {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final int MIN_HTTP_CONTENT_LENGTH = 300;

    private final Browser browser;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public CollectedPage collect(String url, String competitorName, String sourceType) {
        log.info("开始采集页面, url={}, competitor={}, sourceType={}", url, competitorName, sourceType);

        CollectedPage httpPage = collectByHttp(url, competitorName, sourceType);
        if (httpPage.isSuccess() && httpPage.getContent() != null
                && httpPage.getContent().length() >= MIN_HTTP_CONTENT_LENGTH) {
            return httpPage;
        }

        log.info("轻量 HTTP 采集未满足要求，回退到 Playwright 渲染, url={}", url);
        return collectByBrowser(url, competitorName, sourceType, httpPage.getErrorMessage());
    }

    @Override
    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
        List<CollectedPage> results = new ArrayList<>();
        for (String url : safelyLimitUrls(urls)) {
            results.add(collect(url, competitorName, sourceType));
        }
        return results;
    }

    private CollectedPage collectByHttp(String url, String competitorName, String sourceType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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

            return success(url, competitorName, sourceType, title, content, "http");
        } catch (Exception e) {
            return failed(url, competitorName, sourceType, "HTTP 采集失败: " + e.getMessage());
        }
    }

    private CollectedPage collectByBrowser(String url, String competitorName, String sourceType, String fallbackReason) {
        Page page = null;
        try {
            page = browser.newPage();
            page.setDefaultTimeout(30000);
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            String title = page.title();
            String content = extractMainContent(page);
            if (content == null || content.isBlank()) {
                return failed(url, competitorName, sourceType, "Playwright 页面正文为空");
            }

            return success(url, competitorName, sourceType, title, content,
                    fallbackReason == null ? "playwright" : "playwright; fallbackReason=" + fallbackReason);
        } catch (Exception e) {
            log.error("页面采集失败: url={}, error={}", url, e.getMessage());
            return failed(url, competitorName, sourceType, "Playwright 采集失败: " + e.getMessage());
        } finally {
            if (page != null) {
                page.close();
            }
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

    private String extractMainContent(Page page) {
        try {
            String content = (String) page.evaluate("() => {\n"
                    + "  const article = document.querySelector('article');\n"
                    + "  if (article) return article.innerText;\n"
                    + "  const main = document.querySelector('main');\n"
                    + "  if (main) return main.innerText;\n"
                    + "  return document.body ? document.body.innerText : '';\n"
                    + "}");
            return cleanContent(content);
        } catch (Exception e) {
            log.warn("提取正文失败，回退到 HTML 文本: {}", e.getMessage());
            return cleanContent(htmlToText(page.content()));
        }
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
}
