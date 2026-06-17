package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第五轮 Task 1 的结构块提取红灯测试。
 * 这里先锁定“正文 + 结构块”并行抽取的期望，避免后续实现继续只从最长正文块里盲目挑内容。
 */
class PageContentExtractionSupportStructuredBlockTest {

    @Test
    void shouldExtractPricingAndDocumentationBlocksWithoutRelyingOnLongestArticle() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <main>
                      <section class="pricing-card">Pro 199 / month</section>
                      <nav class="docs-outline">
                        <a>Quick Start</a>
                        <a>API Reference</a>
                      </nav>
                    </main>
                  </body>
                </html>
                """);

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "PRICING");

        assertThat(result.getStructuredBlocks())
                .extracting(StructuredContentBlock::getBlockType)
                .contains("PRICING_BLOCK", "DOCUMENTATION_OUTLINE");
        assertThat(result.getQualitySignals()).contains("STRUCTURED_BLOCK_HIT");
    }

    @Test
    void shouldReturnExtractionFailureWhenBodyAndStructuredBlocksAreBothEmpty() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <div class="layout-shell"></div>
                  </body>
                </html>
                """);

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(CollectionFailureKind.EXTRACTION_EMPTY.name());
        assertThat(result.getQualitySignals()).contains("NO_MAIN_CONTENT", "NO_STRUCTURED_BLOCKS");
        assertThat(result.getQualityScore()).isEqualTo(0.0D);
    }
}
