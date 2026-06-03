package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class PlaywrightPageCollectorTest {

    private final PlaywrightBrowserManager browserManager = mock(PlaywrightBrowserManager.class);
    private final CollectorProperties collectorProperties = new CollectorProperties();
    private final SearchRuntimeFallbackPolicy fallbackPolicy =
            new SearchRuntimeFallbackPolicy(new SearchBrowserProperties());
    private final PlaywrightPageCollector collector =
            new PlaywrightPageCollector(browserManager, collectorProperties, fallbackPolicy);

    @Test
    void shouldRejectSpaShellLikeHttpContent() {
        String html = """
                <html>
                  <body>
                    <div id="root"></div>
                  </body>
                </html>
                """;
        String content = String.join(" ", Collections.nCopies(90, "pricing"));

        assertFalse(collector.isMeaningfulHttpContent(html, content));
    }

    @Test
    void shouldAcceptRichHttpContent() {
        String html = """
                <html>
                  <body>
                    <article>
                      <h1>Notion AI Pricing</h1>
                    </article>
                  </body>
                </html>
                """;
        String content = """
                Notion AI pricing includes unlimited blocks, enterprise admin tooling, workspace governance,
                model controls, audit exports, and collaborative knowledge management for product, design,
                support, and operations teams that need repeatable documentation workflows.

                The documentation also explains feature limits, rollout options, migration guidance,
                security commitments, and API access patterns so buyers can compare plan value using
                concrete operational criteria instead of marketing taglines alone.

                Customers can review implementation examples, support coverage, onboarding paths,
                data residency notes, and administrator setup steps before committing to a higher plan.
                """;

        assertTrue(collector.isMeaningfulHttpContent(html, content));
    }

    @Test
    void shouldPreferArticleLikeContentBlockOverNoisyBody() {
        String selected = collector.selectBestContentBlock(List.of(
                Map.of(
                        "selector", "body",
                        "tagName", "BODY",
                        "className", "layout",
                        "idName", "app",
                        "text", "Home Pricing Docs Contact Sign in\n".repeat(30),
                        "linkTextLength", 420
                ),
                Map.of(
                        "selector", ".article-body",
                        "tagName", "DIV",
                        "className", "article-body prose",
                        "idName", "main-content",
                        "text", """
                                Notion AI documentation explains setup, model behavior, workspace permissions,
                                search augmentation, governance controls, rollout workflow, and troubleshooting guidance.

                                Teams can compare enterprise controls, user education flows, assistant entry points,
                                and knowledge-base curation practices using concrete examples instead of navigation noise.
                                """,
                        "linkTextLength", 24
                )
        ));

        assertTrue(selected.contains("documentation explains setup"));
        assertFalse(selected.contains("Sign in"));
    }

    @Test
    void shouldRejectUnsafeUrlBeforeCollecting() {
        SourceCollector.CollectedPage page = collector.collect("file:///etc/passwd", "Notion AI", "DOCS");

        assertFalse(page.isSuccess());
        assertEquals("仅允许采集 http/https 页面", page.getErrorMessage());
    }

    @Test
    void shouldContinueBatchWhenSinglePageCollectThrowsUnexpectedError() {
        PlaywrightPageCollector batchCollector = spy(new PlaywrightPageCollector(
                browserManager,
                collectorProperties,
                fallbackPolicy
        ));
        SourceCollector.CollectedPage successPage = SourceCollector.CollectedPage.builder()
                .url("https://docs.example.com/a")
                .success(true)
                .build();

        doReturn(successPage).when(batchCollector).collect("https://docs.example.com/a", "Notion AI", "DOCS");
        doThrow(new IllegalStateException("browser has been closed"))
                .when(batchCollector)
                .collect("https://docs.example.com/b", "Notion AI", "DOCS");

        List<SourceCollector.CollectedPage> results = batchCollector.collectBatch(
                List.of("https://docs.example.com/a", "https://docs.example.com/b"),
                "Notion AI",
                "DOCS"
        );

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(1).getErrorMessage().contains("浏览器不可用"));
    }
}
