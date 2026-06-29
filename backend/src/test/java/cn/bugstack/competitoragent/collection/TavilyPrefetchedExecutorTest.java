package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyPrefetchedExecutorTest {

    @Test
    void shouldConsumePrefetchedContentOnceAndReturnCleanedResult() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url("https://open.example.com/docs/api")
                .title("Open API")
                .rawContent("第一段是正文。\n第二段仍然是正文。\n猜你喜欢：其他产品")
                .cleanedContent("第一段是正文。\n第二段仍然是正文。\n猜你喜欢：其他产品")
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .requestId("req-1")
                .resultRank(1)
                .build();
        String ref = registry.register(content);
        TavilyPrefetchedExecutor executor = new TavilyPrefetchedExecutor(registry);

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .packageKey("pkg-1")
                .targetIndex(0)
                .primaryTool("TAVILY_PREFETCHED")
                .prefetchedContentRef(ref)
                .resourceLocator("https://open.example.com/docs/api")
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .build();

        CollectionExecutionResult result = executor.execute(taskPackage);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExecutorType()).isEqualTo("TAVILY_PREFETCHED");
        assertThat(result.getContent()).isEqualTo("第一段是正文。\n第二段仍然是正文。");
        assertThat(result.getSourceUrls()).containsExactly("https://open.example.com/docs/api");
        assertThat(result.getQualitySignals()).contains("TAVILY_RAW_CONTENT_READY");
        assertThat(registry.size()).isZero();
    }

    @Test
    void shouldReturnContentUnusableWhenPrefetchedContentWasAlreadyConsumed() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url("https://open.example.com/docs/api")
                .rawContent("正文内容")
                .cleanedContent("正文内容")
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .requestId("req-2")
                .resultRank(2)
                .build();
        String ref = registry.register(content);
        TavilyPrefetchedExecutor executor = new TavilyPrefetchedExecutor(registry);

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .packageKey("pkg-2")
                .targetIndex(1)
                .primaryTool("TAVILY_PREFETCHED")
                .prefetchedContentRef(ref)
                .resourceLocator("https://open.example.com/docs/api")
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .build();

        assertThat(executor.execute(taskPackage).isSuccess()).isTrue();

        CollectionExecutionResult secondResult = executor.execute(taskPackage);

        assertThat(secondResult.isSuccess()).isFalse();
        assertThat(secondResult.getFailureKind()).isEqualTo(CollectionFailureKind.CONTENT_UNUSABLE.name());
        assertThat(secondResult.getQualitySignals()).contains("TAVILY_PREFETCHED_CONTENT_MISSING");
        assertThat(secondResult.getSourceUrls()).containsExactly("https://open.example.com/docs/api");
    }
}
