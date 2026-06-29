package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyFastLaneAuditContractTest {

    @Test
    void shouldExposePrefetchedAuditMetadataInStructuredPayload() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        String ref = registry.register(TavilyPrefetchedContent.builder()
                .url("https://open.example.com/docs/api")
                .rawContent("API 文档说明。开放平台提供 SDK 和 API 能力，开发者可以调用接口完成授权管理。")
                .tavilyScore(0.88D)
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .requestId("prefetch-audit")
                .resultRank(1)
                .build());

        CollectionExecutionResult result = new TavilyPrefetchedExecutor(
                registry,
                new TavilyPrefetchedContentBlockClassifier())
                .execute(CollectionTaskPackage.builder()
                        .packageKey("collect#001")
                        .targetIndex(1)
                        .primaryTool("TAVILY_PREFETCHED")
                        .resourceLocator("https://open.example.com/docs/api")
                        .prefetchedContentRef(ref)
                        .prefetchedRawContentLength(42)
                        .sourceUrls(List.of("https://open.example.com/docs/api"))
                        .build());

        assertThat(result.getStructuredPayload()).containsEntry("prefetchedContentRef", ref);
        assertThat(result.getStructuredPayload()).containsEntry("primaryTool", "TAVILY_PREFETCHED");
        assertThat(result.getStructuredPayload()).containsKey("structuredBlockCount");
    }

    @Test
    void shouldExposeFailureStageWhenPrefetchedContentMissing() {
        CollectionExecutionResult result = new TavilyPrefetchedExecutor(
                new TavilyPrefetchedContentRegistry(),
                new TavilyPrefetchedContentBlockClassifier())
                .execute(CollectionTaskPackage.builder()
                        .packageKey("collect#002")
                        .targetIndex(2)
                        .primaryTool("TAVILY_PREFETCHED")
                        .resourceLocator("https://open.example.com/docs/api")
                        .prefetchedContentRef("missing-ref")
                        .sourceUrls(List.of("https://open.example.com/docs/api"))
                        .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStructuredPayload()).containsEntry("failureStage", "TAVILY_PREFETCHED_CONSUME");
        assertThat(result.getStructuredPayload()).containsEntry("primaryTool", "TAVILY_PREFETCHED");
    }
}
