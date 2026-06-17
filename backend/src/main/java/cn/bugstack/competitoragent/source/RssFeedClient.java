package cn.bugstack.competitoragent.source;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * RSS feed 客户端。
 * 当前阶段只支持 RSS 2.0，并且要求对 HTML、空响应、非 feed XML 明确快速失败，
 * 避免把确定性错误吞成无意义的重试。
 */
@Component
public class RssFeedClient {

    private final RssFeedProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public RssFeedClient(RssFeedProperties properties) {
        this(properties, null);
    }

    public RssFeedClient(RssFeedProperties properties, HttpClient httpClient) {
        this.properties = properties == null ? new RssFeedProperties() : properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
    }

    /**
     * 拉取并解析 feed。
     * 只有网络异常允许重试；如果已经明确判断不是 RSS feed，则直接抛错快速失败。
     */
    public List<RssFeedItem> fetch(String feedUrl) {
        if (!StringUtils.hasText(feedUrl)) {
            throw new IllegalStateException("rss feed url is blank");
        }
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<byte[]> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(feedUrl.trim()))
                                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("rss feed status=" + response.statusCode());
                }
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                return parse(response.body(), contentType, feedUrl);
            } catch (IllegalStateException businessException) {
                throw businessException;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("rss feed request interrupted", interruptedException);
            } catch (Exception exception) {
                lastError = new IllegalStateException("rss feed request failed: " + exception.getMessage(), exception);
            }
        }
        throw lastError == null ? new IllegalStateException("rss feed request failed") : lastError;
    }

    /**
     * 解析 RSS 2.0 原始字节。
     * 这里用字节流交给 XML parser，让编码按 XML 声明解析，避免中文 fixture 因字符串预处理产生伪通过。
     */
    public List<RssFeedItem> parse(byte[] body, String contentType, String feedUrl) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("rss feed body is empty");
        }
        if (looksLikeHtml(contentType, body) && properties.isFailFastOnNonFeedContent()) {
            throw new IllegalStateException("rss feed response is not rss feed");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            Element root = document.getDocumentElement();
            String rootName = root == null ? "" : root.getTagName();
            if (!"rss".equalsIgnoreCase(rootName)) {
                if ("feed".equalsIgnoreCase(rootName)) {
                    throw new IllegalStateException("atom feed is not supported in wave 10");
                }
                throw new IllegalStateException("rss feed response is not rss feed");
            }

            String feedTitle = textOfFirst(document.getElementsByTagName("title"));
            NodeList items = document.getElementsByTagName("item");
            List<RssFeedItem> results = new ArrayList<>();
            int maxItems = Math.max(1, properties.getMaxItemsPerFeed());
            for (int index = 0; index < items.getLength() && results.size() < maxItems; index++) {
                Element item = (Element) items.item(index);
                String title = textOfFirst(item.getElementsByTagName("title"));
                String link = textOfFirst(item.getElementsByTagName("link"));
                String publishedAt = textOfFirst(item.getElementsByTagName("pubDate"));
                String summary = textOfFirst(item.getElementsByTagName("description"));

                LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
                if (StringUtils.hasText(feedUrl)) {
                    sourceUrls.add(feedUrl);
                }
                if (StringUtils.hasText(link)) {
                    sourceUrls.add(link);
                }

                results.add(RssFeedItem.builder()
                        .feedTitle(feedTitle)
                        .title(title)
                        .link(link)
                        .publishedAt(publishedAt)
                        .summary(summary)
                        .sourceUrls(new ArrayList<>(sourceUrls))
                        .build());
            }
            return results;
        } catch (IllegalStateException businessException) {
            throw businessException;
        } catch (Exception exception) {
            throw new IllegalStateException("rss feed parse failed: " + exception.getMessage(), exception);
        }
    }

    private boolean looksLikeHtml(String contentType, byte[] body) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("text/html")) {
            return true;
        }
        String prefix = new String(body, 0, Math.min(body.length, 128), java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return prefix.contains("<html");
    }

    private String textOfFirst(NodeList nodes) {
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
