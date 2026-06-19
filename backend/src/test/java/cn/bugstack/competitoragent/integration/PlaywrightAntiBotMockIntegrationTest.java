package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.config.PlaywrightConfig;
import cn.bugstack.competitoragent.config.PlaywrightRuntimeFactory;
import cn.bugstack.competitoragent.search.AntiBotSignalDetector;
import cn.bugstack.competitoragent.search.BrowserFailureClassifier;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchEngineProperties;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import cn.bugstack.competitoragent.source.PlaywrightPageCollector;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6 本地真实浏览器集成测试。
 * 这里把“本地 mock 反爬页面”和“运行时断链故障注入”收口在同一个黑盒测试类里，
 * 目标是验证浏览器搜索链路在正常页、反爬页和 runtime 断链三种典型场景下的真实行为。
 */
class PlaywrightAntiBotMockIntegrationTest {

    private static final String LOCAL_CHROMIUM_PATH = "D:/Aplaywright/chromium-1117/chrome-win/chrome.exe";

    private static HttpServer mockServer;
    private static String serverBaseUrl;
    private static String docsBaseUrl;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/search", new HtmlHandler(exchange -> ok(buildSearchResultsHtml())));
        mockServer.createContext("/ok-docs", new HtmlHandler(exchange -> ok(buildOkDocsHtml())));
        mockServer.createContext("/bilibili-docs", new HtmlHandler(exchange -> ok(buildBilibiliDocsHtml())));
        mockServer.createContext("/douyin-docs", new HtmlHandler(exchange -> ok(buildDouyinDocsHtml())));
        mockServer.createContext("/captcha", new HtmlHandler(exchange -> ok(buildCaptchaHtml())));
        mockServer.createContext("/deny", new HtmlHandler(exchange -> html(403, buildDenyHtml())));
        mockServer.createContext("/login-redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/login");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        mockServer.createContext("/login", new HtmlHandler(exchange -> ok(buildLoginHtml())));
        mockServer.setExecutor(Executors.newCachedThreadPool());
        mockServer.start();
        serverBaseUrl = "http://127.0.0.1:" + mockServer.getAddress().getPort();
        docsBaseUrl = "http://localhost:" + mockServer.getAddress().getPort();
    }

    @AfterAll
    static void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void shouldNotMisclassifyNormalSearchPageAsBlocked() {
        LocalRealBrowserFixture fixture = openRealBrowserFixture(serverBaseUrl + "/search");
        try {
            BrowserSearchRuntimeResult result = fixture.service().search(browserSearchConfig("Notion AI docs"));

            assertFalse(result.isFallbackSuggested());
            assertFalse(result.getCandidates().isEmpty());
            assertNull(result.getFailureKind());
            assertNull(result.getRestartScope());
            assertEquals(0, fixture.manager().restartBrowserCount());
            assertEquals(0, fixture.manager().recreateRuntimeCount());
            assertTrue(result.getCandidates().stream()
                    .anyMatch(candidate -> StringUtils.hasText(candidate.getUrl())
                            && candidate.getUrl().startsWith(docsBaseUrl + "/ok-docs")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldMarkCaptchaPageAsAntiBotBlockedWithoutRestartingRuntime() {
        LocalRealBrowserFixture fixture = openRealBrowserFixture(serverBaseUrl + "/captcha");
        try {
            BrowserSearchRuntimeResult result = fixture.service().search(browserSearchConfig("Notion AI docs"));

            assertTrue(result.isFallbackSuggested());
            assertTrue(result.getCandidates().isEmpty());
            assertEquals("ANTI_BOT_BLOCKED", result.getFailureKind());
            assertEquals("NONE", result.getRestartScope());
            assertEquals("HTTP_FALLBACK", result.getFallbackAction());
            assertEquals(0, fixture.manager().restartBrowserCount());
            assertEquals(0, fixture.manager().recreateRuntimeCount());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldExposeRuntimePipeBrokenWhenPipeClosedIsSimulated() {
        Browser failingBrowser = mock(Browser.class);
        BrowserContext failingContext = mock(BrowserContext.class);
        when(failingBrowser.newContext(any(Browser.NewContextOptions.class))).thenReturn(failingContext);
        when(failingContext.newPage()).thenThrow(new IllegalStateException("pipe closed"));

        FaultInjectingBrowserManager manager = new FaultInjectingBrowserManager(failingBrowser);
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                manager,
                browserProperties("bing"),
                localSearchEngines(serverBaseUrl + "/search"),
                new ObjectMapper(),
                new SearchRuntimeFallbackPolicy(browserProperties("bing")),
                new BrowserFailureClassifier(),
                new AntiBotSignalDetector(browserProperties("bing")),
                new BrowserRuntimeDiagnosticLogger(new ObjectMapper())
        );

        BrowserSearchRuntimeResult result = service.search(browserSearchConfig("Notion AI docs"));

        assertTrue(result.isFallbackSuggested());
        assertTrue(result.getCandidates().isEmpty());
        assertEquals("RUNTIME_PIPE_BROKEN", result.getFailureKind());
        assertEquals("RUNTIME_AND_BROWSER", result.getRestartScope());
        assertTrue(manager.recreateRuntimeCount() > 0);
        assertTrue(manager.restartBrowserCount() > 0);
    }

    @Test
    void shouldCollectBilibiliAndDouyinFullRenderPagesConcurrentlyWithSharedBrowser() throws Exception {
        LocalRealBrowserFixture fixture = openRealBrowserFixture(serverBaseUrl + "/search");
        try {
            PlaywrightPageCollector collector = new PlaywrightPageCollector(
                    fixture.manager(),
                    collectorProperties(),
                    new SearchRuntimeFallbackPolicy(browserProperties("bing")),
                    new BrowserFailureClassifier(),
                    new AntiBotSignalDetector(browserProperties("bing")),
                    new BrowserRuntimeDiagnosticLogger(new ObjectMapper())
            );

            CompletableFuture<SourceCollector.CollectedPage> bilibiliFuture = CompletableFuture.supplyAsync(() ->
                    collector.collect(fullRenderRequest(
                            serverBaseUrl + "/bilibili-docs",
                            "哔哩哔哩",
                            "https://open.bilibili.com/doc/"
                    )));
            CompletableFuture<SourceCollector.CollectedPage> douyinFuture = CompletableFuture.supplyAsync(() ->
                    collector.collect(fullRenderRequest(
                            serverBaseUrl + "/douyin-docs",
                            "抖音",
                            "https://open.douyin.com/docs/"
                    )));

            SourceCollector.CollectedPage bilibiliPage = bilibiliFuture.get(30, TimeUnit.SECONDS);
            SourceCollector.CollectedPage douyinPage = douyinFuture.get(30, TimeUnit.SECONDS);

            assertTrue(bilibiliPage.isSuccess(), bilibiliPage.getErrorMessage());
            assertTrue(douyinPage.isSuccess(), douyinPage.getErrorMessage());
            assertTrue(bilibiliPage.getContent().contains("Bilibili open platform"));
            assertTrue(douyinPage.getContent().contains("Douyin open platform"));
            assertMetadataSourceUrl(bilibiliPage, "https://open.bilibili.com/doc/");
            assertMetadataSourceUrl(douyinPage, "https://open.douyin.com/docs/");
            assertEquals(0, fixture.manager().restartBrowserCount());
            assertEquals(0, fixture.manager().recreateRuntimeCount());
        } finally {
            fixture.close();
        }
    }

    /**
     * 真实浏览器场景需要和本地 mock server 一起跑。
     * 这里统一组装 Playwright runtime、浏览器管理器和搜索服务，避免每条测试各自拼装导致配置漂移。
     */
    private LocalRealBrowserFixture openRealBrowserFixture(String searchBaseUrl) {
        SearchBrowserProperties properties = browserProperties("bing");
        SearchEngineProperties engines = localSearchEngines(searchBaseUrl);
        ObjectMapper objectMapper = new ObjectMapper();
        PlaywrightConfig.PlaywrightProperties playwrightProperties = playwrightProperties();
        Playwright playwright = Playwright.create();
        TrackingPlaywrightBrowserManager manager = new TrackingPlaywrightBrowserManager(
                playwright,
                Playwright::create,
                playwrightProperties
        );
        BrowserSearchRuntimeService service = new BrowserSearchRuntimeService(
                manager,
                properties,
                engines,
                objectMapper,
                new SearchRuntimeFallbackPolicy(properties),
                new BrowserFailureClassifier(),
                new AntiBotSignalDetector(properties),
                new BrowserRuntimeDiagnosticLogger(objectMapper)
        );
        return new LocalRealBrowserFixture(playwright, manager, service);
    }

    private CollectorNodeConfig browserSearchConfig(String query) {
        return CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .searchMode("HYBRID")
                .browserSearchEnabled(Boolean.TRUE)
                .verifyCandidates(Boolean.FALSE)
                .verifyResultPage(Boolean.FALSE)
                .searchQueries(List.of(query))
                .maxSearchResults(1)
                .build();
    }

    private SearchBrowserProperties browserProperties(String engineKey) {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(true);
        properties.setEngine(engineKey);
        properties.setFallbackEngines(List.of());
        properties.setMaxRetries(1);
        properties.setMaxResultsPerQuery(1);
        properties.setMaxOpenResultPages(0);
        properties.setPageTimeoutMillis(8000);
        properties.setResultPageTimeoutMillis(4000);
        properties.setMinIntervalMillis(0L);
        properties.setShortBodyThreshold(120);
        properties.setMinimumPrimaryResultCount(1);
        properties.setSuspectBlockedBodyThreshold(40);
        return properties;
    }

    private CollectorProperties collectorProperties() {
        CollectorProperties properties = new CollectorProperties();
        properties.setPageTimeoutSeconds(8);
        return properties;
    }

    /**
     * 并发采集冒烟必须强制走 FULL_RENDER，避免 HTTP-first 路径绕开真实 Playwright。
     * sourceUrls 则显式模拟上游搜索/规划结果，验证采集 metadata 不丢可追溯来源。
     */
    private SourceCollectRequest fullRenderRequest(String url, String competitorName, String sourceUrl) {
        return SourceCollectRequest.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType("DOCS")
                .renderHint(WebPageRenderHint.FULL_RENDER)
                .expectedBlockTypes(List.of("DOCUMENTATION_OUTLINE", "JSON_LD_METADATA"))
                .sourceUrls(List.of(sourceUrl))
                .build();
    }

    private void assertMetadataSourceUrl(SourceCollector.CollectedPage page, String expectedSourceUrl) throws Exception {
        JsonNode metadata = new ObjectMapper().readTree(page.getMetadata());
        assertTrue(metadata.path("sourceUrls").isArray());
        assertEquals(expectedSourceUrl, metadata.path("sourceUrls").get(0).asText());
        assertTrue(metadata.path("qualitySignals").toString().contains("MAIN_CONTENT_READY"));
    }

    /**
     * 集成测试不依赖公网搜索引擎，而是把“搜索结果页”路由到本地 mock server。
     * 这样既能覆盖真实 Playwright DOM 抽取链路，又能保证 Task 6 在本地可重复执行。
     */
    private SearchEngineProperties localSearchEngines(String searchBaseUrl) {
        SearchEngineProperties engines = new SearchEngineProperties();
        engines.clear();
        SearchEngineProperties.EngineConfig engineConfig = new SearchEngineProperties.EngineConfig();
        engineConfig.setName("Local Mock Search");
        engineConfig.setBaseUrl(searchBaseUrl);
        engineConfig.setQueryParam("q");
        engineConfig.setEnabled(true);
        engines.put("bing", engineConfig);
        return engines;
    }

    private PlaywrightConfig.PlaywrightProperties playwrightProperties() {
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setHeadless(true);
        properties.setTimeoutMillis(15000);
        properties.setStartupWarmupEnabled(false);
        properties.setHealthCheckWarmupEnabled(false);
        if (Files.exists(Path.of(LOCAL_CHROMIUM_PATH))) {
            properties.setExecutablePath(LOCAL_CHROMIUM_PATH);
        }
        return properties;
    }

    private static MockHttpResponse ok(String body) {
        return html(200, body);
    }

    private static MockHttpResponse html(int statusCode, String body) {
        return new MockHttpResponse(statusCode, body, "text/html; charset=UTF-8");
    }

    private static String buildSearchResultsHtml() {
        return """
                <html>
                  <head>
                    <title>Mock Search Results</title>
                  </head>
                  <body>
                    <main>
                      <article class="search-result">
                        <h2><a href="%s/ok-docs">Notion AI Documentation</a></h2>
                        <p>
                          Official documentation reference, API guide, deployment notes,
                          governance settings, integration tutorials, troubleshooting steps,
                          and workspace rollout guidance for enterprise teams.
                        </p>
                      </article>
                    </main>
                  </body>
                </html>
                """.formatted(docsBaseUrl);
    }

    private static String buildOkDocsHtml() {
        return """
                <html>
                  <head>
                    <title>Notion AI Docs</title>
                  </head>
                  <body>
                    <main>
                      <article class="docs-content">
                        <h1>Notion AI Documentation</h1>
                        <p>
                          This documentation explains how product teams can configure workspace AI features,
                          manage permissions, review governance controls, connect internal knowledge sources,
                          and troubleshoot assistant behavior across daily research workflows.
                        </p>
                        <p>
                          The guide also covers API access, change management, onboarding plans,
                          rollout checklists, and reference examples so analysts can trace product
                          capabilities back to stable source pages instead of marketing summaries.
                        </p>
                      </article>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String buildBilibiliDocsHtml() {
        return """
                <html>
                  <head>
                    <title>Bilibili Open Platform Docs</title>
                    <script type="application/ld+json">
                      {"@type":"TechArticle","name":"Bilibili Open Platform"}
                    </script>
                  </head>
                  <body>
                    <main>
                      <article class="docs-content">
                        <h1>Bilibili open platform documentation</h1>
                        <nav class="docs-outline">
                          <a href="/auth">Account authorization</a>
                          <a href="/sdk">SDK integration</a>
                        </nav>
                        <p>
                          Bilibili open platform documentation explains account authorization, content API access,
                          client SDK setup, callback verification, application review, and operational guidance
                          for teams building integrations that require traceable product capability evidence.
                        </p>
                        <p>
                          The page includes stable source material for collectors, analysts, and report writers
                          to compare developer ecosystem coverage without relying on generated summaries alone.
                        </p>
                      </article>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String buildDouyinDocsHtml() {
        return """
                <html>
                  <head>
                    <title>Douyin Open Platform Docs</title>
                    <script type="application/ld+json">
                      {"@type":"TechArticle","name":"Douyin Open Platform"}
                    </script>
                  </head>
                  <body>
                    <main>
                      <article class="docs-content">
                        <h1>Douyin open platform documentation</h1>
                        <nav class="docs-outline">
                          <a href="/guide">Developer guide</a>
                          <a href="/data">Data capability</a>
                        </nav>
                        <p>
                          Douyin open platform documentation covers mini app onboarding, account authorization,
                          data capability access, webhook callbacks, security review requirements, and SDK
                          integration steps that help product teams validate available developer surfaces.
                        </p>
                        <p>
                          The content is intentionally rich enough for browser extraction, quality scoring,
                          source URL persistence, and downstream evidence audit checks during concurrent smoke tests.
                        </p>
                      </article>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String buildCaptchaHtml() {
        return """
                <html>
                  <head>
                    <title>Verify you are human</title>
                  </head>
                  <body>
                    <main>
                      <section class="challenge">
                        <h1>Verify you are human</h1>
                        <p>captcha challenge required</p>
                      </section>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String buildDenyHtml() {
        return """
                <html>
                  <head>
                    <title>Access Denied</title>
                  </head>
                  <body>
                    <main>
                      <section class="challenge">
                        <h1>Access denied</h1>
                        <p>Your request has been blocked.</p>
                      </section>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String buildLoginHtml() {
        return """
                <html>
                  <head>
                    <title>Login Required</title>
                  </head>
                  <body>
                    <main>
                      <section class="login">
                        <h1>Login Required</h1>
                        <form action="/login">
                          <input type="text" name="username"/>
                          <input type="password" name="password"/>
                        </form>
                      </section>
                    </main>
                  </body>
                </html>
                """;
    }

    private record MockHttpResponse(int statusCode, String body, String contentType) {
    }

    private record LocalRealBrowserFixture(Playwright playwright,
                                           TrackingPlaywrightBrowserManager manager,
                                           BrowserSearchRuntimeService service) implements AutoCloseable {

        @Override
        public void close() {
            manager.shutdown();
            playwright.close();
        }
    }

    /**
     * 真浏览器场景仍然需要统计“是否真的触发了 browser/runtime 恢复动作”。
     * 这里通过轻量继承在不改生产代码的前提下记录重建次数，确保 blocked 场景不会误触恢复动作。
     */
    private static class TrackingPlaywrightBrowserManager extends PlaywrightBrowserManager {

        private int restartBrowserCount;
        private int recreateRuntimeCount;

        TrackingPlaywrightBrowserManager(Playwright initialPlaywright,
                                         PlaywrightRuntimeFactory runtimeFactory,
                                         PlaywrightConfig.PlaywrightProperties props) {
            super(initialPlaywright, runtimeFactory, props, new BrowserRuntimeDiagnosticLogger(new ObjectMapper()));
        }

        @Override
        public Browser restartBrowserIfCurrent(Browser expectedBrowser, String reason) {
            restartBrowserCount++;
            return super.restartBrowserIfCurrent(expectedBrowser, reason);
        }

        @Override
        public boolean recreateRuntimeForFailure(String reason, Exception cause) {
            recreateRuntimeCount++;
            return super.recreateRuntimeForFailure(reason, cause);
        }

        int restartBrowserCount() {
            return restartBrowserCount;
        }

        int recreateRuntimeCount() {
            return recreateRuntimeCount;
        }
    }

    /**
     * pipe closed 很难靠真实浏览器稳定复现，所以这里做受控故障注入。
     * 目标不是模拟整套浏览器，而是稳定触发 BrowserSearchRuntimeService 的 runtime 断链恢复分支。
     */
    private static class FaultInjectingBrowserManager extends PlaywrightBrowserManager {

        private final Browser failingBrowser;
        private int restartBrowserCount;
        private int recreateRuntimeCount;

        FaultInjectingBrowserManager(Browser failingBrowser) {
            super(mock(Playwright.class),
                    () -> mock(Playwright.class),
                    new PlaywrightConfig.PlaywrightProperties(),
                    new BrowserRuntimeDiagnosticLogger(new ObjectMapper()));
            this.failingBrowser = failingBrowser;
        }

        @Override
        public Browser getBrowser() {
            return failingBrowser;
        }

        @Override
        public Browser restartBrowserIfCurrent(Browser expectedBrowser, String reason) {
            restartBrowserCount++;
            return failingBrowser;
        }

        @Override
        public boolean recreateRuntimeForFailure(String reason, Exception cause) {
            recreateRuntimeCount++;
            return true;
        }

        int restartBrowserCount() {
            return restartBrowserCount;
        }

        int recreateRuntimeCount() {
            return recreateRuntimeCount;
        }
    }

    /**
     * 统一处理 mock 页面响应，避免每个 endpoint 各自复制响应头、编码和关闭逻辑。
     */
    private static class HtmlHandler implements HttpHandler {

        private final ResponseFactory responseFactory;

        HtmlHandler(ResponseFactory responseFactory) {
            this.responseFactory = responseFactory;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MockHttpResponse response = responseFactory.create(exchange);
            assertNotNull(response);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.statusCode(), body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            } finally {
                exchange.close();
            }
        }
    }

    @FunctionalInterface
    private interface ResponseFactory {

        MockHttpResponse create(HttpExchange exchange) throws IOException;
    }
}
