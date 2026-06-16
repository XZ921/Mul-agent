package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserSearchRuntimeServiceTest {

    private final PlaywrightBrowserManager browserManager = mock(PlaywrightBrowserManager.class);
    private final BrowserFailureClassifier browserFailureClassifier = new BrowserFailureClassifier();
    private final BrowserRuntimeDiagnosticLogger diagnosticLogger = mock(BrowserRuntimeDiagnosticLogger.class);

    @Test
    void shouldBuildGoogleSearchUrlWhenConfigured() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("google");
        SearchEngineProperties engines = defaultEngines();
        engines.put("google", engine("Google", "https://www.google.com/search", "q", true));
        BrowserSearchRuntimeService service = newService(properties, engines);

        String url = service.buildSearchUrl("Notion AI docs");

        assertEquals("google", service.getSearchEngineName());
        assertTrue(url.startsWith("https://www.google.com/search?q="));
    }

    @Test
    void shouldBuildDuckDuckGoSearchUrlWhenConfiguredAliasUsed() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("ddg");
        SearchEngineProperties engines = defaultEngines();
        engines.put("duckduckgo", engine("DuckDuckGo", "https://duckduckgo.com/", "q", true));
        BrowserSearchRuntimeService service = newService(properties, engines);

        String url = service.buildSearchUrl("Notion AI pricing");

        assertEquals("duckduckgo", service.getSearchEngineName());
        assertTrue(url.startsWith("https://duckduckgo.com/?q="));
    }

    @Test
    void shouldFallbackToBingForUnsupportedEngine() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("custom-engine");
        BrowserSearchRuntimeService service = newService(properties, defaultEngines());

        String url = service.buildSearchUrl("Notion AI official site");

        assertEquals("bing", service.getSearchEngineName());
        assertTrue(url.startsWith("https://www.bing.com/search?q="));
    }

    @Test
    void shouldRespectMaxOpenResultPagesWhenPreviewingCandidates() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxOpenResultPages(1);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);
        com.microsoft.playwright.Page previewPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage, previewPage);
        when(searchPage.title()).thenReturn("Search");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of(
                        "title", "Docs One",
                        "url", "https://docs.example.com/guide",
                        "snippet", "guide content",
                        "resultRank", 1
                ),
                Map.of(
                        "title", "Docs Two",
                        "url", "https://docs.example.com/api",
                        "snippet", "api content",
                        "resultRank", 2
                )
        ));
        when(previewPage.url()).thenReturn("https://docs.example.com/guide");
        when(previewPage.title()).thenReturn("Guide Final");
        doThrow(new IllegalStateException("preview page close failed")).when(previewPage).close();

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(2)
                .build());

        assertEquals(2, result.getCandidates().size());
        assertEquals("Guide Final", result.getCandidates().get(0).getTitle());
        assertEquals("Docs Two", result.getCandidates().get(1).getTitle());
        verify(context, times(2)).newPage();
    }

    @Test
    void shouldApplyStealthContextOptionsWhenCreatingBrowserContext() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxOpenResultPages(0);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.url()).thenReturn("https://www.bing.com/search?q=notion");
        when(searchPage.title()).thenReturn("Search");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of(
                        "title", "Docs One",
                        "url", "https://docs.example.com/guide",
                        "snippet", "guide content",
                        "resultRank", 1
                )
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        ArgumentCaptor<Browser.NewContextOptions> captor = ArgumentCaptor.forClass(Browser.NewContextOptions.class);
        verify(browser).newContext(captor.capture());
        Browser.NewContextOptions options = captor.getValue();
        assertEquals("zh-CN", options.locale);
        assertEquals("Asia/Shanghai", options.timezoneId);
        assertTrue(options.viewportSize.isPresent());
        assertEquals(1440, options.viewportSize.get().width);
        assertEquals(900, options.viewportSize.get().height);
    }

    @Test
    void shouldInjectStealthInitScriptAfterCreatingBrowserContext() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxOpenResultPages(0);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.url()).thenReturn("https://www.bing.com/search?q=notion");
        when(searchPage.title()).thenReturn("Search");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of(
                        "title", "Docs One",
                        "url", "https://docs.example.com/guide",
                        "snippet", "guide content",
                        "resultRank", 1
                )
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        verify(context).addInitScript(contains("navigator"));
        verify(context).addInitScript(contains("webdriver"));
        verify(context).addInitScript(contains("window.chrome"));
    }

    @Test
    void shouldUseFallbackEngineWhenPrimaryDisabled() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("google");
        properties.setFallbackEngines(List.of("baidu"));

        SearchEngineProperties engines = defaultEngines();
        engines.put("google", engine("Google", "https://www.google.com/search", "q", false));
        engines.put("baidu", engine("鐧惧害", "https://www.baidu.com/s", "wd", true));

        BrowserSearchRuntimeService service = newService(properties, engines);

        String url = service.buildSearchUrl("Notion AI 璇勬祴");

        assertEquals("baidu", service.getSearchEngineName());
        assertTrue(url.startsWith("https://www.baidu.com/s?wd="));
    }

    @Test
    void shouldSkipUnsafeResultUrlsDuringSearchPreview() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.title()).thenReturn("Search");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of(
                        "title", "Unsafe",
                        "url", "file:///etc/passwd",
                        "snippet", "unsafe content",
                        "resultRank", 1
                )
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    void shouldReturnStructuredFallbackWhenSearchTimeoutExhaustsRetries() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxRetries(1);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new RuntimeException("Timeout 15000ms exceeded"));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        assertEquals("search_timeout", result.getBlockedReason());
        assertTrue(result.getSummary().contains("HTTP"));
        verify(browserManager, times(0)).restartBrowser(anyString());
    }

    @Test
    void shouldMarkChallengeSearchPageAsBlockedWithoutRestartingBrowser() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxOpenResultPages(0);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.url()).thenReturn("https://www.bing.com/challenge");
        when(searchPage.title()).thenReturn("Verify you are human");
        when(searchPage.content()).thenReturn("<html><body>verify you are human</body></html>");
        when(searchPage.evaluate(anyString())).thenReturn(List.of(
                Map.of(
                        "selector", "body",
                        "tagName", "BODY",
                        "className", "",
                        "idName", "",
                        "text", "Verify you are human",
                        "linkTextLength", 0
                )
        ));
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of());

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        assertEquals("LOGIN_OR_CHALLENGE_REDIRECT", result.getBlockedReason());
        assertEquals("ANTI_BOT_BLOCKED", result.getFailureKind());
        assertEquals("HTTP_FALLBACK", result.getFallbackAction());
        assertTrue(result.getMatchedSignals().contains("url:/challenge"));
        ArgumentCaptor<BrowserRuntimeDiagnosticLog> diagnosticCaptor =
                ArgumentCaptor.forClass(BrowserRuntimeDiagnosticLog.class);
        verify(diagnosticLogger, times(1)).log(eq("search_once_blocked"), diagnosticCaptor.capture());
        BrowserRuntimeDiagnosticLog diagnosticLog = diagnosticCaptor.getValue();
        assertEquals("Notion AI", diagnosticLog.getCompetitorName());
        assertEquals("DOCS", diagnosticLog.getSourceType());
        assertEquals("Notion AI docs", diagnosticLog.getQuery());
        assertEquals("https://www.bing.com/challenge", diagnosticLog.getTargetUrl());
        assertEquals("baidu", diagnosticLog.getEngineKey());
        assertEquals("ANTI_BOT_BLOCKED", diagnosticLog.getFailureKind());
        assertEquals("NONE", diagnosticLog.getRestartScope());
        assertEquals("HTTP_FALLBACK", diagnosticLog.getFallbackAction());
        assertEquals("LOGIN_OR_CHALLENGE_REDIRECT", diagnosticLog.getBlockedReasonCode());
        assertTrue(diagnosticLog.getMatchedSignals().contains("url:/challenge"));
        verify(browserManager, never()).restartBrowserIfCurrent(any(), anyString());
        verify(browserManager, never()).recreateRuntimeForFailure(anyString(), any(Exception.class));
    }

    @Test
    void shouldEmitStructuredDiagnosticWhenSearchRetriesExhausted() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxRetries(0);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new RuntimeException("Timeout 15000ms exceeded"));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.isFallbackSuggested());
        ArgumentCaptor<BrowserRuntimeDiagnosticLog> diagnosticCaptor =
                ArgumentCaptor.forClass(BrowserRuntimeDiagnosticLog.class);
        verify(diagnosticLogger).log(eq("search_retry_exhausted"), diagnosticCaptor.capture());
        BrowserRuntimeDiagnosticLog diagnosticLog = diagnosticCaptor.getValue();
        assertEquals("Notion AI", diagnosticLog.getCompetitorName());
        assertEquals("DOCS", diagnosticLog.getSourceType());
        assertEquals("Notion AI docs", diagnosticLog.getQuery());
        assertTrue(diagnosticLog.getTargetUrl().startsWith("https://www.baidu.com/s?wd="));
        assertEquals("baidu", diagnosticLog.getEngineKey());
        assertEquals("SEARCH_TIMEOUT", diagnosticLog.getFailureKind());
        assertEquals("NONE", diagnosticLog.getRestartScope());
        assertEquals("HTTP_FALLBACK", diagnosticLog.getFallbackAction());
        assertEquals("search_timeout", diagnosticLog.getBlockedReasonCode());
        assertTrue(diagnosticLog.getMatchedSignals().isEmpty());
    }

    @Test
    void shouldNotRestartSharedBrowserWhenNewTabCreationFails() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxRetries(1);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenThrow(new IllegalStateException(
                "Protocol error (Target.createTarget): Failed to open a new tab"
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("OFFICIAL")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("鍝斿摡鍝斿摡 瀹樻柟缃戠珯"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        verify(browserManager, times(0)).restartBrowser(anyString());
        verify(browserManager, never()).restartBrowserIfCurrent(any(), anyString());
        verify(browserManager, never()).recreateRuntimeForFailure(anyString(), any(Exception.class));
    }

    @Test
    void shouldRestartSharedBrowserWhenPlaywrightConnectionIsClosed() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxRetries(1);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenThrow(new IllegalStateException(
                "Target page, context or browser has been closed"
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("OFFICIAL")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("鍝斿摡鍝斿摡 瀹樻柟缃戠珯"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        verify(browserManager, times(2)).restartBrowserIfCurrent(same(browser), anyString());
        verify(browserManager, never()).restartBrowser(anyString());
        verify(browserManager, never()).recreateRuntimeForFailure(anyString(), any(Exception.class));
    }

    @Test
    void shouldRecreateRuntimeAndRestartBrowserWhenPlaywrightPipeIsClosed() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxRetries(1);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenThrow(new IllegalStateException("playwright connection closed"));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        verify(browserManager, times(2)).recreateRuntimeForFailure(anyString(), any(Exception.class));
        verify(browserManager, times(2)).restartBrowserIfCurrent(same(browser), anyString());
    }

    @Test
    void shouldFallbackToPageAnchorsWhenEngineSelectorsReturnNoRows() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setEngine("baidu");
        properties.setFallbackEngines(List.of("bing"));
        properties.setMaxOpenResultPages(0);
        SearchEngineProperties engines = defaultEngines();
        engines.put("baidu", engine("鐧惧害", "https://www.baidu.com/s", "wd", true));

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage);
        when(searchPage.title()).thenReturn("Baidu");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(eq("#content_left .result"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("#content_left .c-container"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("#content_left .result, #content_left .c-container, #content_left > div, #content_left > div[data-log], .result-op, .result-op.c-container"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("main article"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("main section"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("article"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evalOnSelectorAll(eq("a[href]"), anyString(), any())).thenReturn(List.of());
        when(searchPage.evaluate(anyString())).thenReturn(List.of(
                Map.of(
                        "title", "鍝斿摡鍝斿摡 浜у搧鏇存柊鏃ュ織",
                        "url", "https://www.bilibili.com/read/cv123",
                        "snippet", "杩欓噷鏄洿鏂版棩蹇椾笌鐗堟湰鍔熻兘璇存槑锛屽寘鍚帹鑽愮畻娉曘€佸垱浣滆€呭伐鍏峰拰鍟嗕笟鍖栬兘鍔涘彉鍖栥€?",
                        "resultRank", 1
                ),
                Map.of(
                        "title", "鐧惧害鎼滅储",
                        "url", "https://www.baidu.com/link?url=internal",
                        "snippet", "",
                        "resultRank", 2
                )
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("NEWS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("鍝斿摡鍝斿摡 浜у搧鏇存柊 鍙戝竷鏃ュ織"))
                .maxSearchResults(3)
                .build());

        assertEquals(1, result.getCandidates().size());
        assertEquals("https://www.bilibili.com/read/cv123", result.getCandidates().get(0).getUrl());
        assertEquals("baidu", result.getCandidates().get(0).getSearchEngine());
    }

    @Test
    void shouldEnrichCandidateReasonWithPreviewContentSnippet() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setMaxOpenResultPages(1);
        properties.setResultPageTimeoutMillis(4000);
        properties.setMaxContentLengthPerPage(80);
        SearchEngineProperties engines = defaultEngines();

        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        com.microsoft.playwright.Page searchPage = mock(com.microsoft.playwright.Page.class);
        com.microsoft.playwright.Page previewPage = mock(com.microsoft.playwright.Page.class);

        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        when(context.newPage()).thenReturn(searchPage, previewPage);
        when(searchPage.title()).thenReturn("Search");
        when(searchPage.content()).thenReturn("<html><body>search results</body></html>");
        when(searchPage.evalOnSelectorAll(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of(
                        "title", "Docs One",
                        "url", "https://docs.example.com/guide",
                        "snippet", "guide content",
                        "resultRank", 1
                )
        ));
        when(previewPage.url()).thenReturn("https://docs.example.com/guide");
        when(previewPage.title()).thenReturn("Guide Final");
        when(previewPage.evaluate(anyString())).thenReturn(List.of(
                Map.of(
                        "selector", ".article-body",
                        "tagName", "DIV",
                        "className", "article-body prose",
                        "idName", "main-content",
                        "text", """
                                杩欐槸鏂囨。姝ｆ枃鐨勫叧閿唴瀹癸紝璇︾粏璇存槑浜嗘帴鍙ｈ璇併€侀€熺巼闄愬埗銆佸洖璋冧簨浠躲€侀敊璇爜澶勭悊鏂瑰紡锛?
                                涔熻ˉ鍏呬簡鎺ュ叆姝ラ銆佽皟璇曞缓璁互鍙婇潰鍚戜紒涓氬洟闃熺殑娌荤悊鑳藉姏璇存槑銆?
                                """,
                        "linkTextLength", 12
                )
        ));

        BrowserSearchRuntimeService service = newService(properties, engines);

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertEquals(1, result.getCandidates().size());
        assertEquals("Guide Final", result.getCandidates().get(0).getTitle());
        assertTrue(result.getCandidates().get(0).getReason().contains("鎺ュ彛") || result.getCandidates().get(0).getReason().contains("姝ｆ枃"));
        assertTrue(result.getCandidates().get(0).getReason().length() <= 220);
    }

    @Test
    void shouldUseBaiduAsDefaultPrimaryEngine() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        BrowserSearchRuntimeService service = newService(properties, defaultEngines());

        assertEquals("baidu", properties.getEngine());
        assertFalse(properties.getFallbackEngines().contains("baidu"));
        assertEquals("baidu", service.getSearchEngineName());
    }

    private SearchEngineProperties defaultEngines() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.put("bing", engine("Bing", "https://www.bing.com/search", "q", true));
        return properties;
    }

    private BrowserSearchRuntimeService newService(SearchBrowserProperties properties, SearchEngineProperties engines) {
        return new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties),
                browserFailureClassifier,
                new AntiBotSignalDetector(properties),
                diagnosticLogger
        );
    }

    private SearchEngineProperties.EngineConfig engine(String name,
                                                       String baseUrl,
                                                       String queryParam,
                                                       boolean enabled) {
        SearchEngineProperties.EngineConfig config = new SearchEngineProperties.EngineConfig();
        config.setName(name);
        config.setBaseUrl(baseUrl);
        config.setQueryParam(queryParam);
        config.setEnabled(enabled);
        return config;
    }
}
