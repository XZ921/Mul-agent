package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.JinaReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第五轮 Task 1 的网页双路径路由红灯测试。
 * 这里先锁死 JinaReader 主路径与 Playwright 升级兜底的入口契约，
 * 避免后续实现时继续把网页采集退回到“单一路径 + URL 直传”的旧模式。
 */
class WebPageCollectionExecutorRouteTest {

    @Test
    void shouldUseJinaReaderAsPrimaryPathForLightweightDocsPage() {
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("API Reference")
                .mainContent("这是可用的文档正文。")
                .qualityScore(0.92D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://docs.example.com/api/reference")
                .resourceLocator("https://docs.example.com/api/reference")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailureKind()).isNull();
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldFallbackToPlaywrightWhenJinaReaderReturnsThinContent() {
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("CONTENT_UNUSABLE")
                .qualityScore(0.18D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_TOO_THIN"))
                .build());
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
                .url("https://pricing.example.com")
                .title("Pricing")
                .content("这里是完整定价页正文和套餐信息。")
                .snippet("完整定价页正文")
                .metadata("""
                        {
                          "sourceUrls": ["https://pricing.example.com"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "qualityScore": 0.86,
                          "structuredBlocks": [
                            {
                              "blockType": "PRICING_BLOCK",
                              "title": "pricing",
                              "content": "pricing block",
                              "qualitySignal": "PRICING_BLOCK_HIT"
                            },
                            {
                              "blockType": "DOCUMENTATION_OUTLINE",
                              "title": "docs-outline",
                              "content": "docs outline",
                              "qualitySignal": "DOCUMENTATION_OUTLINE_HIT"
                            }
                          ],
                          "collectedAt": "2026-06-16T12:00:00Z",
                          "durationMillis": 120
                        }
                        """)
                .sourceType("PRICING")
                .competitorName("Acme AI")
                .success(true)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://pricing.example.com")
                .resourceLocator("https://pricing.example.com")
                .sourceType("PRICING")
                .sourceUrls(List.of("https://pricing.example.com"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getQualitySignals()).contains("UPGRADED_TO_FULL_RENDER", "STRUCTURED_BLOCK_HIT");
        assertThat(result.getQualityScore()).isEqualTo(0.86D);
        assertThat(result.getStructuredBlocks())
                .extracting(StructuredContentBlock::getBlockType)
                .contains("PRICING_BLOCK", "DOCUMENTATION_OUTLINE");
        assertThat(result.getDurationMillis()).isEqualTo(120L);
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }
}
