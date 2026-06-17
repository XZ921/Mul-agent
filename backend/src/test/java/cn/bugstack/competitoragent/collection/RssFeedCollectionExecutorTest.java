package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.RssFeedClient;
import cn.bugstack.competitoragent.source.RssFeedItem;
import cn.bugstack.competitoragent.source.RssFeedProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RssFeedCollectionExecutorTest {

    @Test
    void shouldReturnStructuredFeedEvidenceAndRealDuration() {
        RssFeedClient client = mock(RssFeedClient.class);
        when(client.fetch("https://blog.example.com/feed.xml")).thenReturn(List.of(
                RssFeedItem.builder()
                        .feedTitle("Acme Blog")
                        .title("Agent launch")
                        .link("https://blog.example.com/agent-launch")
                        .publishedAt("2026-06-17T08:00:00Z")
                        .summary("Acme 发布 Agent。")
                        .sourceUrls(List.of("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch"))
                        .build()
        ));

        RssFeedProperties properties = new RssFeedProperties();
        properties.setEnabled(true);
        RssFeedCollectionExecutor executor = new RssFeedCollectionExecutor(client, properties);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .packageKey("collect_sources_news#001")
                .targetIndex(1)
                .primaryTool("RSS")
                .url("https://blog.example.com/feed.xml")
                .resourceLocator("rss://feed/aGVsbG8")
                .sourceUrls(List.of("https://blog.example.com/feed.xml"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredPayload()).containsKey("items");
        assertThat(result.getSourceUrls()).contains("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch");
        assertThat(result.getDurationMillis()).isNotNull();
        assertThat(result.getDurationMillis()).isGreaterThanOrEqualTo(0L);
    }
}
