package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.collector.CollectorAgent;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutor;
import cn.bugstack.competitoragent.collection.CollectionExecutorRegistry;
import cn.bugstack.competitoragent.collection.CollectionTaskPackage;
import cn.bugstack.competitoragent.collection.CollectionTaskPackageBuilder;
import cn.bugstack.competitoragent.collection.WebPageCollectionExecutor;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.search.CandidateVerifier;
import cn.bugstack.competitoragent.search.CollectionTargetSelector;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 深度采集端到端集成测试。
 * 这里不启动 Spring 容器，而是直接组装真实的 search/collection/collector 协调链路，
 * 只在边界处 mock 搜索补源与页面抓取，验证“只给竞争品名 -> 补源 -> 入口页 -> 内部子页 -> audit/sourceUrls”闭环。
 */
class SearchAndCollectionDeepDiscoveryIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CanonicalUrlResolver canonicalUrlResolver = new CanonicalUrlResolver();
    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
    private final SearchSourceProvider searchSourceProvider = mock(SearchSourceProvider.class);
    private final TaskRetrievalIndexService taskRetrievalIndexService = mock(TaskRetrievalIndexService.class);
    private final CollectionExecutor githubApiExecutor = mock(CollectionExecutor.class);
    private final CollectionExecutionCoordinator collectionExecutionCoordinator = new CollectionExecutionCoordinator(
            new CollectionTaskPackageBuilder(),
            new CollectionExecutorRegistry(List.of(
                    githubApiExecutor,
                    new WebPageCollectionExecutor(sourceCollector)
            ))
    );
    private final SearchExecutionCoordinator searchExecutionCoordinator = new SearchExecutionCoordinator(
            new CandidateVerifier(sourceCollector),
            browserSearchRuntimeService,
            searchSourceProvider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector(),
            new SearchPolicyResolver()
    );
    private final CollectorAgent collectorAgent = new CollectorAgent(
            logRepository,
            sourceCollector,
            evidenceRepository,
            nodeRepository,
            agentContextAssembler,
            searchExecutionCoordinator,
            collectionExecutionCoordinator,
            taskRetrievalIndexService,
            objectMapper
    );

    {
        when(githubApiExecutor.supports(any())).thenAnswer(invocation -> {
            CollectionTaskPackage taskPackage = invocation.getArgument(0);
            return taskPackage != null && "GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
        });
        when(githubApiExecutor.executorType()).thenReturn("API_DATA");
    }

    @Test
    void shouldCloseDeepDiscoveryLoopFromCompetitorNameToChildPagesAndAuditEvidence() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser supplement disabled in test")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://www.bilibili.com")
                        .title("bilibili official")
                        .sourceType("OFFICIAL")
                        .discoveryMethod("HTTP_SEARCH")
                        .reason("http search result")
                        .domain("www.bilibili.com")
                        .sourceUrls(List.of("https://www.bilibili.com"))
                        .relevanceScore(0.72D)
                        .freshnessScore(0.50D)
                        .qualityScore(0.70D)
                        .build(),
                SourceCandidate.builder()
                        .url("https://open.bilibili.com/doc/")
                        .title("bilibili open docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HTTP_SEARCH")
                        .reason("http search result")
                        .domain("open.bilibili.com")
                        .sourceUrls(List.of("https://open.bilibili.com/doc/"))
                        .relevanceScore(0.96D)
                        .freshnessScore(0.60D)
                        .qualityScore(0.92D)
                        .build()
        ));

        mockDeepDiscoverySitePages();

        AgentResult result = collectorAgent.execute(buildDeepDiscoveryContext());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertTrue(output.path("documents").isArray());
        assertTrue(output.path("documents").size() >= 3);
        assertTrue(output.toString().contains("https://open.bilibili.com/doc"));
        assertTrue(output.toString().contains("https://open.bilibili.com/doc/auth"));
        assertTrue(output.toString().contains("https://open.bilibili.com/doc/android-sdk"));
        assertTrue(output.toString().contains("sourceUrls"));
        assertTrue(output.path("documents").get(0).path("sourceUrls").isArray());
        assertTrue(output.path("searchProgressSnapshots").isArray());
        assertTrue(output.path("collectionAudit").path("replayTimeline").isArray());
        assertTrue(output.path("collectionAudit").path("replayTimeline").size() >= 3);
        assertTrue(output.path("collectionAudit").path("sourceUrls").isArray());
    }

    @Test
    void shouldRunBilibiliAndDouyinNameOnlyCollectionSmokeConcurrently() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser supplement disabled in concurrent smoke")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenAnswer(invocation -> {
            String competitorName = invocation.getArgument(0);
            if ("哔哩哔哩".equals(competitorName)) {
                return List.of(rootSearchCandidate(
                        "https://www.bilibili.com",
                        "哔哩哔哩",
                        "HTTP 搜索补源命中 B 站根域"
                ));
            }
            if ("抖音".equals(competitorName)) {
                return List.of(rootSearchCandidate(
                        "https://www.douyin.com",
                        "抖音",
                        "HTTP 搜索补源命中抖音根域"
                ));
            }
            return List.of();
        });
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenAnswer(invocation -> {
            SourceCollectRequest request = invocation.getArgument(0);
            return buildConcurrentSmokePage(
                    request == null ? null : request.getUrl(),
                    request == null ? null : request.getCompetitorName(),
                    request == null ? null : request.getSourceType()
            );
        });
        when(sourceCollector.collect(any(), any(), any())).thenAnswer(invocation -> buildConcurrentSmokePage(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2)
        ));

        CompletableFuture<JsonNode> bilibiliFuture = CompletableFuture.supplyAsync(() ->
                executeCollectorAsJson(buildNameOnlyContext(19L, "哔哩哔哩")));
        CompletableFuture<JsonNode> douyinFuture = CompletableFuture.supplyAsync(() ->
                executeCollectorAsJson(buildNameOnlyContext(20L, "抖音")));

        JsonNode bilibiliOutput = bilibiliFuture.get(30, TimeUnit.SECONDS);
        JsonNode douyinOutput = douyinFuture.get(30, TimeUnit.SECONDS);

        assertCollectedOpenPlatform(bilibiliOutput, "https://open.bilibili.com");
        assertCollectedOpenPlatform(douyinOutput, "https://open.douyin.com");
        assertTrue(bilibiliOutput.toString().contains("sourceUrls"));
        assertTrue(douyinOutput.toString().contains("sourceUrls"));
        assertTrue(bilibiliOutput.path("collectionAudit").path("sourceUrls").isArray());
        assertTrue(douyinOutput.path("collectionAudit").path("sourceUrls").isArray());
    }

    /**
     * 这里显式不给 sourceCandidates，只给 competitorName 与 competitorUrls 占位，
     * 迫使链路走运行期 HTTP supplement，再由 search coordinator 选中 docs 入口页。
     */
    private AgentContext buildDeepDiscoveryContext() {
        return AgentContext.builder()
                .taskId(18L)
                .taskName("deep-discovery-task")
                .currentNodeName("collect_sources_deep_discovery")
                .currentNodeConfig("""
                        {
                          "competitorName": "哔哩哔哩",
                          "competitorUrls": [],
                          "sourceType": "DOCS",
                          "discoveryNotes": "deep discovery integration",
                          "verifyCandidates": false,
                          "verifyResultPage": false,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "maxSearchResults": 1,
                          "minVerifiedCandidates": 1
                        }
                        """)
                .build();
    }

    private AgentContext buildNameOnlyContext(Long taskId, String competitorName) {
        return AgentContext.builder()
                .taskId(taskId)
                .taskName("name-only-concurrent-smoke")
                .currentNodeName("collect_sources_" + taskId)
                .currentNodeConfig("""
                        {
                          "competitorName": "%s",
                          "competitorUrls": [],
                          "sourceType": "DOCS",
                          "discoveryNotes": "concurrent name-only smoke",
                          "verifyCandidates": false,
                          "verifyResultPage": false,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "maxSearchResults": 5,
                          "minVerifiedCandidates": 1
                        }
                        """.formatted(competitorName))
                .build();
    }

    private JsonNode executeCollectorAsJson(AgentContext context) {
        try {
            AgentResult result = collectorAgent.execute(context);
            assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
            return objectMapper.readTree(result.getOutputData());
        } catch (Exception exception) {
            throw new IllegalStateException("collector smoke failed", exception);
        }
    }

    private SourceCandidate rootSearchCandidate(String url, String title, String reason) {
        return SourceCandidate.builder()
                .url(url)
                .title(title)
                .sourceType("OFFICIAL")
                .discoveryMethod("HTTP_SEARCH")
                .reason(reason)
                .domain(canonicalUrlResolver.canonicalDomain(url))
                .sourceUrls(List.of(url))
                .relevanceScore(0.88D)
                .freshnessScore(0.55D)
                .qualityScore(0.84D)
                .build();
    }

    private SourceCollector.CollectedPage buildConcurrentSmokePage(String url,
                                                                   String competitorName,
                                                                   String sourceType) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (canonicalUrl == null || !"DOCS".equals(sourceType)) {
            return null;
        }
        if ("哔哩哔哩".equals(competitorName) && canonicalUrl.contains("bilibili.com")) {
            return concurrentSmokeSuccess(
                    canonicalUrl,
                    competitorName,
                    sourceType,
                    "Bilibili 开放平台文档",
                    """
                            [账号授权](https://open.bilibili.com/doc/auth)
                            B 站开放平台提供账号授权、内容接入、SDK 和 API 文档。
                            """
            );
        }
        if ("抖音".equals(competitorName) && canonicalUrl.contains("douyin.com")) {
            return concurrentSmokeSuccess(
                    canonicalUrl,
                    competitorName,
                    sourceType,
                    "抖音开放平台文档",
                    """
                            [开发指南](https://open.douyin.com/docs/guide)
                            抖音开放平台提供小程序、账号授权、数据能力、SDK 和 API 接入文档。
                            """
            );
        }
        return null;
    }

    private SourceCollector.CollectedPage concurrentSmokeSuccess(String url,
                                                                 String competitorName,
                                                                 String sourceType,
                                                                 String title,
                                                                 String content) {
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .title(title)
                .content(content)
                .snippet(content)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(true)
                .build();
    }

    private void assertCollectedOpenPlatform(JsonNode output, String expectedUrlPrefix) {
        assertTrue(output.path("documents").isArray());
        assertTrue(output.path("documents").size() >= 1);
        assertTrue(output.toString().contains(expectedUrlPrefix));
        assertTrue(output.path("searchAudit").path("sourceCandidates").toString().contains(expectedUrlPrefix));
    }

    /**
     * 端到端测试要允许 search supplement 与 direct-discovery 自由选择同域入口页，
     * 因此这里把“任意 bilibili 同域 docs 入口”统一映射成开放平台文档首页语义，
     * 同时保留 auth / android-sdk 两个递归子页，稳定验证深度采集契约而不是绑定某个精确入口 URL。
     */
    private void mockDeepDiscoverySitePages() {
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenAnswer(invocation -> {
            SourceCollectRequest request = invocation.getArgument(0);
            return buildMockCollectedPage(
                    request == null ? null : request.getUrl(),
                    request == null ? null : request.getCompetitorName(),
                    request == null ? null : request.getSourceType()
            );
        });
        when(sourceCollector.collect(any(), any(), any())).thenAnswer(invocation -> buildMockCollectedPage(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2)
        ));
    }

    private SourceCollector.CollectedPage buildMockCollectedPage(String url,
                                                                 String competitorName,
                                                                 String sourceType) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (canonicalUrl == null || !"哔哩哔哩".equals(competitorName) || !"DOCS".equals(sourceType)) {
            return null;
        }
        if (canonicalUrl.endsWith("/doc/auth")) {
            return SourceCollector.CollectedPage.builder()
                    .url("https://open.bilibili.com/doc/auth")
                    .title("账号授权")
                    .content("账号授权说明")
                    .snippet("账号授权说明")
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .success(true)
                    .build();
        }
        if (canonicalUrl.endsWith("/doc/android-sdk")) {
            return SourceCollector.CollectedPage.builder()
                    .url("https://open.bilibili.com/doc/android-sdk")
                    .title("Android SDK")
                    .content("Android SDK 接入说明")
                    .snippet("Android SDK 接入说明")
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .success(true)
                    .build();
        }
        if (canonicalUrl.contains("bilibili.com")) {
            return SourceCollector.CollectedPage.builder()
                    .url("https://open.bilibili.com/doc/")
                    .title("开放平台文档")
                    .content("""
                            [账号授权](https://open.bilibili.com/doc/auth)
                            [Android SDK](https://open.bilibili.com/doc/android-sdk)
                            [外部链接](https://example.org/outside)
                            """)
                    .snippet("开放平台文档首页")
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .success(true)
                    .build();
        }
        return null;
    }
}
