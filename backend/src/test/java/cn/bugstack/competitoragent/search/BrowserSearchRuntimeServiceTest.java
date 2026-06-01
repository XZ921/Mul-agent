package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
                new ObjectMapper()
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
                new ObjectMapper()
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
                new ObjectMapper()
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

        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                browserManager,
                properties,
                engines,
                new ObjectMapper()
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
                new ObjectMapper()
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
                new ObjectMapper()
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
