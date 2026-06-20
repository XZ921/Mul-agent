package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
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
    void shouldUseDirectHtmlBeforeJinaForLightweightDocsPage() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("Direct 直连采集到的开放平台文档正文。[账号授权](https://open.example.com/doc/auth)")
                .qualityScore(0.74D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(1)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Direct 直连采集到的开放平台文档正文");
        assertThat(result.getSourceUrls()).containsExactly("https://open.example.com/doc");
        assertThat(result.getQualitySignals()).contains("DIRECT_HTML_CONTENT_READY");
        verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldFallbackToJinaWhenDirectHtmlReturnsSpaShell() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("CONTENT_UNUSABLE")
                .qualityScore(0.0D)
                .qualitySignals(List.of("DIRECT_HTML_SPA_SHELL"))
                .build());
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("JinaReader 采集到的文档正文，Direct 失败后仍然可以成功。")
                .qualityScore(0.78D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(1)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("JinaReader 采集到的文档正文");
        assertThat(result.getQualitySignals()).contains("LIGHTWEIGHT_CONTENT_READY");
        verify(jinaReaderClient).collect(any(SourceCollectRequest.class));
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldFallbackToPlaywrightWhenDirectAndJinaAreBothUnusable() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("CONTENT_UNUSABLE")
                .qualitySignals(List.of("DIRECT_HTML_SPA_SHELL"))
                .qualityScore(0.0D)
                .build());
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("CONTENT_UNUSABLE")
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_TOO_THIN"))
                .qualityScore(0.0D)
                .build());
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
                .url("https://open.example.com/doc")
                .title("Open Docs")
                .content("Playwright 完整渲染后的正文。")
                .metadata("""
                        {
                          "sourceUrls": ["https://open.example.com/doc"],
                          "qualitySignals": ["FULL_RENDER_READY"],
                          "qualityScore": 0.82,
                          "durationMillis": 3000
                        }
                        """)
                .sourceType("DOCS")
                .competitorName("Acme")
                .success(true)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(1)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Playwright 完整渲染后的正文");
        assertThat(result.getQualitySignals())
                .contains("DIRECT_HTML_SPA_SHELL", "LIGHTWEIGHT_CONTENT_TOO_THIN", "UPGRADED_TO_FULL_RENDER");
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldKeepOriginalJinaThenPlaywrightChainWhenDirectHtmlDisabled() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("RUNTIME_FAILURE")
                .qualitySignals(List.of("DIRECT_HTML_DISABLED"))
                .qualityScore(0.0D)
                .build());
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("Direct 关闭后，JinaReader 仍按原轻量链路采集正文。")
                .qualityScore(0.78D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(1)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("JinaReader 仍按原轻量链路采集正文");
        assertThat(result.getQualitySignals()).contains("LIGHTWEIGHT_CONTENT_READY");
        verify(jinaReaderClient).collect(any(SourceCollectRequest.class));
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldUsePlaywrightOnlyToSupplementLinksWhenEntryLightweightContentHasNoInternalLinks() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("OPEN API documentation center. Account auth, user management, video management and Android SDK are available.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
                .url("https://open.example.com/doc")
                .title("Open Docs")
                .content("""
                        <a href="https://open.example.com/doc/auth">OAuth Account API</a>
                        <a href="https://open.example.com/doc/android-sdk">Android SDK</a>
                        """)
                .metadata("""
                        {
                          "sourceUrls": ["https://open.example.com/doc"],
                          "qualitySignals": ["FULL_RENDER_READY"],
                          "qualityScore": 0.52,
                          "durationMillis": 3000
                        }
                        """)
                .sourceType("DOCS")
                .competitorName("Acme")
                .success(true)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(0)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("OPEN API documentation center");
        assertThat(result.getQualitySignals())
                .contains("DIRECT_HTML_CONTENT_READY", "PLAYWRIGHT_LINK_SUPPLEMENT_READY")
                .doesNotContain("UPGRADED_TO_FULL_RENDER");
        assertThat(result.getDiscoveredCandidates())
                .extracting(candidate -> candidate.getUrl())
                .containsExactly("https://open.example.com/doc/auth", "https://open.example.com/doc/android-sdk");
        verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldSupplementLinksForEntryPageWhenDiscoveryDepthIsMissing() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("OPEN API documentation center. Account auth, user management and Android SDK are available.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
                .url("https://open.example.com/doc")
                .title("Open Docs")
                .content("""
                        <a href="https://open.example.com/doc/auth">OAuth Account API</a>
                        <a href="https://open.example.com/doc/android-sdk">Android SDK</a>
                        """)
                .metadata("""
                        {
                          "sourceUrls": ["https://open.example.com/doc"],
                          "qualitySignals": ["FULL_RENDER_READY"],
                          "qualityScore": 0.52,
                          "durationMillis": 3000
                        }
                        """)
                .sourceType("DOCS")
                .competitorName("Acme")
                .success(true)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("OPEN API documentation center");
        assertThat(result.getDiscoveryDepth()).isZero();
        assertThat(result.getQualitySignals())
                .contains("DIRECT_HTML_CONTENT_READY", "PLAYWRIGHT_LINK_SUPPLEMENT_READY")
                .doesNotContain("UPGRADED_TO_FULL_RENDER");
        assertThat(result.getDiscoveredCandidates())
                .extracting(candidate -> candidate.getUrl())
                .containsExactly("https://open.example.com/doc/auth", "https://open.example.com/doc/android-sdk");
        verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldNotSupplementLinksForRecursiveLightweightSuccess() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Account Auth")
                .mainContent("Account Auth details. Scope: USER_INFO. Request method: GET.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc/auth")
                .resourceLocator("https://open.example.com/doc/auth")
                .sourceType("DOCS")
                .discoveryDepth(1)
                .sourceUrls(List.of("https://open.example.com/doc", "https://open.example.com/doc/auth"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Account Auth details");
        assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldNotSupplementLinksForOfficialEntryPageByDefault() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Official Home")
                .mainContent("Official home content is usable, but OFFICIAL entry pages do not supplement links by default.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://www.example.com")
                .resourceLocator("https://www.example.com")
                .sourceType("OFFICIAL")
                .discoveryDepth(0)
                .sourceUrls(List.of("https://www.example.com"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldNotSupplementLinksWhenPlaywrightLinkSupplementDisabled() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        WebPageCollectionProperties properties = new WebPageCollectionProperties();
        properties.setPlaywrightLinkSupplementEnabled(false);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("OPEN API documentation center content is usable but has no internal links.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(
                directHtmlReaderClient,
                jinaReaderClient,
                sourceCollector,
                new InternalLinkDiscoveryService(new InternalLinkDiscoveryProperties(), new cn.bugstack.competitoragent.search.CanonicalUrlResolver()),
                properties
        );
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(0)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldSupplementLinksForOfficialEntryPageWhenSourceTypeWhitelistIncludesOfficial() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        WebPageCollectionProperties properties = new WebPageCollectionProperties();
        properties.setPlaywrightLinkSupplementSourceTypes(List.of("DOCS", "OFFICIAL"));
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Official Home")
                .mainContent("Official home content is usable, but internal entry links need configurable Playwright supplementation.")
                .qualityScore(0.82D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .build());
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
                .url("https://www.example.com")
                .title("Official Home")
                .content("""
                        <a href="https://www.example.com/docs">Developer Docs</a>
                        <a href="https://www.example.com/pricing">Pricing</a>
                        """)
                .metadata("""
                        {
                          "sourceUrls": ["https://www.example.com"],
                          "qualitySignals": ["FULL_RENDER_READY"],
                          "qualityScore": 0.52,
                          "durationMillis": 2600
                        }
                        """)
                .sourceType("OFFICIAL")
                .competitorName("Acme")
                .success(true)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(
                directHtmlReaderClient,
                jinaReaderClient,
                sourceCollector,
                new InternalLinkDiscoveryService(new InternalLinkDiscoveryProperties(), new cn.bugstack.competitoragent.search.CanonicalUrlResolver()),
                properties
        );
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://www.example.com")
                .resourceLocator("https://www.example.com")
                .sourceType("OFFICIAL")
                .discoveryDepth(0)
                .sourceUrls(List.of("https://www.example.com"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Official home content is usable");
        assertThat(result.getQualitySignals())
                .contains("DIRECT_HTML_CONTENT_READY", "PLAYWRIGHT_LINK_SUPPLEMENT_READY")
                .doesNotContain("UPGRADED_TO_FULL_RENDER");
        assertThat(result.getDiscoveredCandidates())
                .extracting(candidate -> candidate.getUrl())
                .containsExactly("https://example.com/docs");
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldKeepDirectDurationWhenNoPlaywrightSupplementIsNeeded() {
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("API Reference")
                .mainContent("[Account Auth](https://open.example.com/doc/auth) content")
                .qualityScore(0.92D)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .durationMillis(44L)
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://open.example.com/doc")
                .resourceLocator("https://open.example.com/doc")
                .sourceType("DOCS")
                .discoveryDepth(0)
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.getDurationMillis()).isEqualTo(44L);
        verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

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
                .discoveryDepth(1)
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailureKind()).isNull();
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }

    @Test
    void shouldAttachDiscoveredCandidatesWhenCollectedContentContainsInternalDocsLinks() {
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open Docs")
                .mainContent("""
                        [账户授权](/open/doc/auth)
                        [外部帮助](https://outside.example.net/help)
                        """)
                .qualityScore(0.92D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://docs.example.com/open/doc")
                .resourceLocator("https://docs.example.com/open/doc")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build());

        assertThat(readListAccessor(result, "discoveredCandidates")).hasSize(1);
        Object discoveredCandidate = readListAccessor(result, "discoveredCandidates").get(0);
        assertThat(readStringAccessor(discoveredCandidate, "url"))
                .isEqualTo("https://docs.example.com/open/doc/auth");
        assertThat(readStringAccessor(discoveredCandidate, "discoveryMethod"))
                .isEqualTo("INTERNAL_LINK_DISCOVERY");
        assertThat(readListAccessor(discoveredCandidate, "sourceUrls"))
                .contains("https://docs.example.com/open/doc", "https://docs.example.com/open/doc/auth");
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

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(null, jinaReaderClient, sourceCollector);
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
        assertThat(result.getContent()).contains("完整定价页正文");
        assertThat(result.getDiscoveredCandidates()).isNotNull();
        assertThat(result.getQualityScore()).isEqualTo(0.86D);
        assertThat(result.getStructuredBlocks())
                .extracting(StructuredContentBlock::getBlockType)
                .contains("PRICING_BLOCK", "DOCUMENTATION_OUTLINE");
        assertThat(result.getDurationMillis()).isEqualTo(120L);
        verify(sourceCollector).collect(any(SourceCollectRequest.class));
    }

    private Object readAccessor(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                return target.getClass().getMethod("get" + suffix).invoke(target);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("read accessor failed: " + target.getClass().getSimpleName() + "." + fieldName, exception);
        }
    }

    private String readStringAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Object> readListAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
