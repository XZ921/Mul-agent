package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RssFeedClientTest {

    @Test
    void shouldParseChineseRssFixtureAndRespectMaxItems() {
        RssFeedProperties properties = new RssFeedProperties();
        properties.setMaxItemsPerFeed(1);
        RssFeedClient client = new RssFeedClient(properties, null);

        List<RssFeedItem> items = client.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Acme 中文动态</title>
                    <item>
                      <title>智能体平台发布</title>
                      <link>https://blog.example.com/agent-launch</link>
                      <pubDate>Tue, 17 Jun 2026 08:00:00 GMT</pubDate>
                      <description>Acme 发布企业级智能体平台，强调治理与自动化能力。</description>
                    </item>
                    <item>
                      <title>第二条更新</title>
                      <link>https://blog.example.com/second-update</link>
                      <pubDate>Tue, 17 Jun 2026 09:00:00 GMT</pubDate>
                      <description>这条记录用于验证 maxItemsPerFeed 截断。</description>
                    </item>
                  </channel>
                </rss>
                """.getBytes(StandardCharsets.UTF_8), "application/rss+xml; charset=UTF-8", "https://blog.example.com/feed.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTitle()).isEqualTo("智能体平台发布");
        assertThat(items.get(0).getSourceUrls())
                .containsExactly("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch");
    }

    @Test
    void shouldRejectHtmlPayloadEvenWhenUrlLooksLikeFeed() {
        RssFeedClient client = new RssFeedClient(new RssFeedProperties(), null);

        assertThatThrownBy(() -> client.parse("""
                <html><body>not a feed</body></html>
                """.getBytes(StandardCharsets.UTF_8), "text/html", "https://blog.example.com/feed.xml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not rss feed");
    }
}
