package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.BrowserFailureClassifier;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLog;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightPageCollectorTest {

    private final PlaywrightBrowserManager browserManager = mock(PlaywrightBrowserManager.class);
    private final CollectorProperties collectorProperties = new CollectorProperties();
    private final SearchRuntimeFallbackPolicy fallbackPolicy =
            new SearchRuntimeFallbackPolicy(new SearchBrowserProperties());
    private final BrowserFailureClassifier browserFailureClassifier = new BrowserFailureClassifier();
    private final BrowserRuntimeDiagnosticLogger diagnosticLogger = mock(BrowserRuntimeDiagnosticLogger.class);
    private final cn.bugstack.competitoragent.search.AntiBotSignalDetector antiBotSignalDetector =
            new cn.bugstack.competitoragent.search.AntiBotSignalDetector(new SearchBrowserProperties());
    private final PlaywrightPageCollector collector =
            new PlaywrightPageCollector(browserManager,
                    collectorProperties,
                    fallbackPolicy,
                    browserFailureClassifier,
                    antiBotSignalDetector,
                    diagnosticLogger);

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
        assertEquals("浠呭厑璁搁噰闆?http/https 椤甸潰", page.getErrorMessage());
    }

    @Test
    void shouldContinueBatchWhenSinglePageCollectThrowsUnexpectedError() {
        PlaywrightPageCollector batchCollector = spy(new PlaywrightPageCollector(
                browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector
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
        assertTrue(results.get(1).getErrorMessage().contains("browser has been closed"));
    }

    @Test
    void shouldNotRestartSharedBrowserWhenCreateTargetFailsDuringBrowserCollect() {
        Browser browser = mock(Browser.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newPage()).thenThrow(new IllegalStateException(
                "Protocol error (Target.createTarget): Failed to open a new tab"
        ));

        SourceCollector.CollectedPage page = collector.collect(
                "http://127.0.0.1:1/unreachable",
                "Notion AI",
                "DOCS"
        );

        assertFalse(page.isSuccess());
        verify(browserManager, never()).restartBrowserIfCurrent(any(), anyString());
        verify(browserManager, never()).recreateRuntimeForFailure(anyString(), any(Exception.class));
    }

    @Test
    void shouldRecreateRuntimeAndRestartBrowserWhenPipeClosedDuringBrowserCollect() {
        Browser browser = mock(Browser.class);
        Browser restartedBrowser = mock(Browser.class);
        Page retryPage = mock(Page.class);

        when(browserManager.getBrowser()).thenReturn(browser, restartedBrowser);
        when(browser.newPage()).thenThrow(new IllegalStateException("playwright connection closed"));
        when(restartedBrowser.newPage()).thenReturn(retryPage);
        when(retryPage.title()).thenReturn("Retry Page");

        SourceCollector.CollectedPage page = collector.collect(
                "http://127.0.0.1:1/unreachable",
                "Notion AI",
                "DOCS"
        );

        assertFalse(page.isSuccess());
        verify(browserManager, times(1)).recreateRuntimeForFailure(anyString(), any(Exception.class));
        verify(browserManager, times(1)).restartBrowserIfCurrent(browser, "page collect failure: playwright connection closed");
    }

    @Test
    void shouldFailBlockedChallengePageWithoutRestartingBrowser() {
        Browser browser = mock(Browser.class);
        Page page = mock(Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newPage()).thenReturn(page);
        when(page.title()).thenReturn("Verify you are human");
        when(page.url()).thenReturn("https://example.com/challenge");
        when(page.evaluate(anyString())).thenReturn(List.of(
                Map.of(
                        "selector", "body",
                        "tagName", "BODY",
                        "className", "",
                        "idName", "",
                        "text", "Verify you are human",
                        "linkTextLength", 0
                )
        ));

        SourceCollector.CollectedPage collectedPage = collector.collect(
                "http://127.0.0.1:1/unreachable",
                "Notion AI",
                "DOCS"
        );

        assertFalse(collectedPage.isSuccess());
        assertTrue(collectedPage.getErrorMessage().contains("blocked")
                || collectedPage.getErrorMessage().contains("LOGIN_OR_CHALLENGE_REDIRECT"));
        ArgumentCaptor<BrowserRuntimeDiagnosticLog> diagnosticCaptor =
                ArgumentCaptor.forClass(BrowserRuntimeDiagnosticLog.class);
        verify(diagnosticLogger).log(eq("page_collect_blocked"), diagnosticCaptor.capture());
        BrowserRuntimeDiagnosticLog diagnosticLog = diagnosticCaptor.getValue();
        assertEquals("Notion AI", diagnosticLog.getCompetitorName());
        assertEquals("DOCS", diagnosticLog.getSourceType());
        assertEquals("https://example.com/challenge", diagnosticLog.getTargetUrl());
        assertEquals("ANTI_BOT_BLOCKED", diagnosticLog.getFailureKind());
        assertEquals("PAGE", diagnosticLog.getRestartScope());
        assertEquals("HTTP_FALLBACK", diagnosticLog.getFallbackAction());
        assertEquals("LOGIN_OR_CHALLENGE_REDIRECT", diagnosticLog.getBlockedReasonCode());
        assertTrue(diagnosticLog.getMatchedSignals().contains("url:/challenge"));
        verify(browserManager, never()).restartBrowserIfCurrent(any(), anyString());
        verify(browserManager, never()).recreateRuntimeForFailure(anyString(), any(Exception.class));
    }

    @Test
    void shouldDeduplicateCanonicalUrlVariantsBeforeBatchCollect() {
        PlaywrightPageCollector batchCollector = spy(new PlaywrightPageCollector(
                browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector
        ));
        SourceCollector.CollectedPage canonicalPage = SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Canonical Docs")
                .content("official docs content")
                .snippet("official docs")
                .success(true)
                .build();

        doReturn(canonicalPage).when(batchCollector).collect("https://example.com/docs", "Notion AI", "DOCS");

        List<SourceCollector.CollectedPage> results = batchCollector.collectBatch(
                List.of(
                        "http://www.example.com/docs?utm_source=campaign",
                        "https://example.com/docs"
                ),
                "Notion AI",
                "DOCS"
        );

        assertEquals(1, results.size());
        assertEquals("https://example.com/docs", results.get(0).getUrl());
        verify(batchCollector, times(1)).collect("https://example.com/docs", "Notion AI", "DOCS");
    }

    @Test
    void shouldStopFurtherBatchCollectsAfterRepeatedBlockedResponsesOnSameDomain() {
        PlaywrightPageCollector batchCollector = spy(new PlaywrightPageCollector(
                browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector
        ));
        SourceCollector.CollectedPage blockedPage = SourceCollector.CollectedPage.builder()
                .url("https://example.com/challenge")
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .success(false)
                .errorMessage("页面采集失败，已降级返回失败结果: blocked")
                .build();

        doReturn(blockedPage).when(batchCollector).collect("https://example.com/challenge-a", "Notion AI", "DOCS");
        doReturn(blockedPage).when(batchCollector).collect("https://example.com/challenge-b", "Notion AI", "DOCS");

        List<SourceCollector.CollectedPage> results = batchCollector.collectBatch(
                List.of(
                        "https://example.com/challenge-a",
                        "https://example.com/challenge-b",
                        "https://example.com/docs"
                ),
                "Notion AI",
                "DOCS"
        );

        assertEquals(3, results.size());
        assertFalse(results.get(2).isSuccess());
        assertTrue(results.get(2).getErrorMessage().contains("blocked domain")
                || results.get(2).getErrorMessage().contains("提前停止"));
        verify(batchCollector, times(1)).collect("https://example.com/challenge-a", "Notion AI", "DOCS");
        verify(batchCollector, times(1)).collect("https://example.com/challenge-b", "Notion AI", "DOCS");
        verify(batchCollector, never()).collect("https://example.com/docs", "Notion AI", "DOCS");
    }
}
