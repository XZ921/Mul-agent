package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserSearchRuntimeServiceTest {

    private final PlaywrightBrowserManager browserManager = mock(PlaywrightBrowserManager.class);

    @Test
    void shouldBuildGoogleSearchUrlWhenConfigured() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("google");
        SearchEngineProperties engines = defaultEngines();
        engines.put("google", engine("Google", "https://www.google.com/search", "q", true));
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

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
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        String url = service.buildSearchUrl("Notion AI pricing");

        assertEquals("duckduckgo", service.getSearchEngineName());
        assertTrue(url.startsWith("https://duckduckgo.com/?q="));
    }

    @Test
    void shouldFallbackToBingForUnsupportedEngine() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("custom-engine");
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                defaultEngines(),
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

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

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

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
    void shouldUseFallbackEngineWhenPrimaryDisabled() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEngine("google");
        properties.setFallbackEngines(List.of("baidu"));

        SearchEngineProperties engines = defaultEngines();
        engines.put("google", engine("Google", "https://www.google.com/search", "q", false));
        engines.put("baidu", engine("百度", "https://www.baidu.com/s", "wd", true));

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        String url = service.buildSearchUrl("Notion AI 评测");

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

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

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

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

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
        assertTrue(result.getSummary().contains("超时"));
        // 搜索超时属于页面级失败，不应误伤全局共享浏览器，否则会把其他并发线程一并打断。
        verify(browserManager, times(0)).restartBrowser(anyString());
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
        // 模拟 Playwright 在高并发或系统资源紧张时，尚未进入页面导航就直接报“无法新建标签页”。
        when(context.newPage()).thenThrow(new IllegalStateException(
                "Protocol error (Target.createTarget): Failed to open a new tab"
        ));

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("OFFICIAL")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("哔哩哔哩 官方网站"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        // 这里的关键断言是：单次标签页创建失败不能立刻重启共享浏览器，否则会把其他并发线程一起打断。
        verify(browserManager, times(0)).restartBrowser(anyString());
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
        // 这里模拟真正的浏览器实例失活，此时重启共享浏览器才是合理的恢复动作。
        when(context.newPage()).thenThrow(new IllegalStateException(
                "Target page, context or browser has been closed"
        ));

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("OFFICIAL")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("哔哩哔哩 官方网站"))
                .maxSearchResults(1)
                .build());

        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.isFallbackSuggested());
        // 真正的浏览器实例失活时，需要对“当前仍被托管的故障实例”发起定向重启，
        // 不能退回到无条件 restartBrowser，否则并发线程会把刚拉起的新实例再次关掉。
        verify(browserManager, times(2)).restartBrowserIfCurrent(same(browser), anyString());
        verify(browserManager, never()).restartBrowser(anyString());
    }

    @Test
    void shouldFallbackToPageAnchorsWhenEngineSelectorsReturnNoRows() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setEngine("baidu");
        properties.setFallbackEngines(List.of("bing"));
        properties.setMaxOpenResultPages(0);
        SearchEngineProperties engines = defaultEngines();
        engines.put("baidu", engine("百度", "https://www.baidu.com/s", "wd", true));

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
                        "title", "哔哩哔哩 产品更新日志",
                        "url", "https://www.bilibili.com/read/cv123",
                        "snippet", "这里是更新日志与版本功能说明，包含推荐算法、创作者工具和商业化能力变化。",
                        "resultRank", 1
                ),
                Map.of(
                        "title", "百度搜索",
                        "url", "https://www.baidu.com/link?url=internal",
                        "snippet", "",
                        "resultRank", 2
                )
        ));

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("NEWS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("哔哩哔哩 产品更新 发布日志"))
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
                                这是文档正文的关键内容，详细说明了接口认证、速率限制、回调事件、错误码处理方式，
                                也补充了接入步骤、调试建议以及面向企业团队的治理能力说明。
                                """,
                        "linkTextLength", 12
                )
        ));

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        BrowserSearchRuntimeResult result = service.search(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .browserSearchEnabled(Boolean.TRUE)
                .searchQueries(List.of("Notion AI docs"))
                .maxSearchResults(1)
                .build());

        assertEquals(1, result.getCandidates().size());
        assertEquals("Guide Final", result.getCandidates().get(0).getTitle());
        assertTrue(result.getCandidates().get(0).getReason().contains("接口认证"));
        assertTrue(result.getCandidates().get(0).getReason().length() <= 140);
    }

    @Test
    void shouldUseBaiduAsDefaultPrimaryEngine() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                defaultEngines(),
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(properties)
        );

        assertEquals("baidu", properties.getEngine());
        assertFalse(properties.getFallbackEngines().contains("baidu"));
        assertEquals("baidu", service.getSearchEngineName());
    }

    private SearchEngineProperties defaultEngines() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.put("bing", engine("Bing", "https://www.bing.com/search", "q", true));
        return properties;
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
