package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutor;
import cn.bugstack.competitoragent.collection.CollectionExecutorRegistry;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.CollectionTaskPackage;
import cn.bugstack.competitoragent.collection.CollectionTaskPackageBuilder;
import cn.bugstack.competitoragent.collection.WebPageCollectionExecutor;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexingResult;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.CandidateVerifier;
import cn.bugstack.competitoragent.search.CollectionTargetSelector;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectorAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
    void shouldExposeUnifiedTaskRagContextInCollectorOutput() throws Exception {
        // Collector 没有 LLM prompt，因此统一上下文需要直接落到输出契约，供后续事件和页面复用。
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> {
            AgentContext originalContext = invocation.getArgument(0);
            return originalContext.toBuilder()
                    .taskRagContextBundle(TaskRagContextBundle.builder()
                            .query("Feishu api docs")
                            .retrievalSummary("命中开放平台文档摘要")
                            .gapSummary("企业权限细节公开资料仍不足")
                            .sourceUrls(List.of("https://example.com/docs"))
                            .build())
                    .build();
        });
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("taskRagContext").asText().contains("检索查询"));
        assertTrue(output.path("taskRagContext").asText().contains("企业权限细节公开资料仍不足"));
    }

    @Test
    void shouldFailWhenAllCollectedPagesAreUnusable() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPageForAnyRequest(SourceCollector.CollectedPage.builder()
                .url("https://example.com/pricing")
                .competitorName("Feishu")
                .sourceType("PRICING")
                .success(false)
                .errorMessage("timeout")
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/pricing\"]"));

        assertEquals("FAILED", result.getStatus().name());
        assertTrue(result.getErrorMessage().contains("未采集到可用页面内容"),
                "unexpected error: " + result.getErrorMessage() + " / " + result.getOutputSummary());
        assertTrue(result.getErrorMessage().contains("建议："));
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void shouldPersistOnlySuccessfulCollectedPages() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        mockCollectedPage("https://example.com/help", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/help")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(false)
                .errorMessage("timeout")
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\",\"https://example.com/help\"]"));

        assertEquals("SUCCESS", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        ArgumentCaptor<cn.bugstack.competitoragent.model.entity.EvidenceSource> evidenceCaptor =
                ArgumentCaptor.forClass(cn.bugstack.competitoragent.model.entity.EvidenceSource.class);
        verify(evidenceRepository, times(1)).save(evidenceCaptor.capture());
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("browserTraceId"));
        assertTrue(result.getOutputData().contains("browserTraceId"));
    }

    @Test
    void shouldEmitTraceableCollectContractWithSourceUrlsAndIssueFlags() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        mockCollectedPage("https://example.com/help", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/help")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(false)
                .errorMessage("timeout")
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\",\"https://example.com/help\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("https://example.com/docs", output.path("documents").get(0).path("sourceUrls").get(0).asText());
        assertEquals("https://example.com/help", output.path("documents").get(1).path("sourceUrls").get(0).asText());
        assertTrue(output.path("documents").get(1).path("issueFlags").toString().contains("COLLECT_FAILED"));
        assertTrue(output.path("issueFlags").toString().contains("PARTIAL_COLLECTION_FAILURE"));
        assertTrue(output.path("evidenceFragments").isArray());
        assertTrue(output.path("sectionEvidenceBundles").isArray());
    }

    @Test
    void shouldExposeTaskKnowledgeDocumentsAndRetrievalChunksAfterSuccessfulCollection() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        when(taskRetrievalIndexService.indexEvidence(any())).thenReturn(TaskRetrievalIndexingResult.success(
                KnowledgeDocument.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT_SOURCES_01_01-001")
                        .documentKey("TASK-1-T0001-COLLECT_SOURCES_01_01-001")
                        .sourceType("DOCS")
                        .sourceCategory("USER_PROVIDED")
                        .title("Docs")
                        .url("https://example.com/docs")
                        .cleanedText("useful docs content with api reference")
                        .snippet("api reference")
                        .sourceUrls(List.of("https://example.com/docs"))
                        .issueFlags(List.of())
                        .documentVersion(1)
                        .status("READY")
                        .build(),
                List.of(RetrievalChunk.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(11L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT_SOURCES_01_01-001")
                        .documentKey("TASK-1-T0001-COLLECT_SOURCES_01_01-001")
                        .chunkKey("TASK-1-T0001-COLLECT_SOURCES_01_01-001#CHUNK-001")
                        .chunkIndex(0)
                        .startOffset(0)
                        .endOffset(32)
                        .sourceCategory("USER_PROVIDED")
                        .documentVersion(1)
                        .content("useful docs content with api reference")
                        .snippet("useful docs content")
                        .sourceUrls(List.of("https://example.com/docs"))
                        .issueFlags(List.of())
                        .build()),
                RetrievalIndex.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(11L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT_SOURCES_01_01-001")
                        .documentKey("TASK-1-T0001-COLLECT_SOURCES_01_01-001")
                        .indexKey("TASK-1-T0001-COLLECT_SOURCES_01_01-001#TASK")
                        .indexScope("TASK")
                        .sourceCategory("USER_PROVIDED")
                        .documentVersion(1)
                        .chunkCount(1)
                        .status("READY")
                        .sourceUrls(List.of("https://example.com/docs"))
                        .issueFlags(List.of())
                        .build(),
                List.of()
        ));

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("https://example.com/docs",
                output.path("knowledgeDocuments").get(0).path("sourceUrls").get(0).asText());
        assertEquals("https://example.com/docs",
                output.path("retrievalChunks").get(0).path("sourceUrls").get(0).asText());
        assertEquals("USER_PROVIDED", output.path("knowledgeDocuments").get(0).path("sourceCategory").asText());
    }

    @Test
    void shouldEncodeTaskAndNodeNameInEvidenceId() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("content")
                .snippet("snippet")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertTrue(output.path("documents").get(0).path("evidenceId").asText()
                .startsWith("T0001-COLLECT_SOURCES_01_01-"));
    }

    @Test
    void shouldEmitSelectedTargetSummaryWithTrustTierRankingReasonAndSelectionSummary() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        JsonNode sourceCandidate = output.path("sourceCandidates").get(0);
        assertEquals("HIGH", sourceCandidate.path("trustTier").asText());
        assertEquals("高可信", sourceCandidate.path("trustTierLabel").asText());
        assertTrue(sourceCandidate.path("rankingSummary").asText().contains("来源可信度"));
        assertTrue(sourceCandidate.path("rankingReasons").isArray());

        JsonNode selectedTarget = output.path("selectedTargets").get(0);
        assertEquals("HIGH", selectedTarget.path("trustTier").asText());
        assertTrue(selectedTarget.path("rankingSummary").asText().contains("来源可信度"));
        assertTrue(selectedTarget.path("targetSelectionSummary").asText().contains("正式采集目标"));
        assertNotNull(selectedTarget.path("totalScore"));
    }

    @Test
    void shouldReuseVerifiedPrefetchedPageInsteadOfRefetchingSelectedTarget() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser search unused")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs")
                .content("useful docs content with api reference")
                .snippet("api reference")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        verify(sourceCollector, times(1)).collect("https://example.com/docs", "Feishu", "DOCS");
    }

    @Test
    void shouldCollectGithubViaApiExecutorWithoutOpeningHtmlPage() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(githubApiExecutor.execute(any())).thenReturn(
                CollectionExecutionResult.builder()
                        .executorType("API_DATA")
                        .success(true)
                        .resourceLocator("github://repo/acme/rocket")
                        .title("acme/rocket")
                        .content("Acme AI agent platform")
                        .sourceUrls(List.of("https://github.com/acme/rocket"))
                        .structuredPayload(Map.of(
                                "repository", "acme/rocket",
                                "latestReleaseTag", "v1.2.3"
                        ))
                        .build()
        );

        AgentResult result = collectorAgent.execute(buildGithubContext());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertEquals("https://github.com/acme/rocket",
                output.path("documents").get(0).path("sourceUrls").get(0).asText());
        assertTrue(output.path("documents").get(0).path("success").asBoolean());
        assertTrue(output.path("documents").get(0).path("evidenceFragments").isArray());

        ArgumentCaptor<cn.bugstack.competitoragent.model.entity.EvidenceSource> evidenceCaptor =
                ArgumentCaptor.forClass(cn.bugstack.competitoragent.model.entity.EvidenceSource.class);
        verify(evidenceRepository, times(1)).save(evidenceCaptor.capture());
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"repository\":\"acme/rocket\""));
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"sourceUrls\":[\"https://github.com/acme/rocket\"]"));
        verify(sourceCollector, never()).collect("https://github.com/acme/rocket", "Acme", "GITHUB");
    }

    private AgentContext buildContext(String competitorUrlsJson) {
        return AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("collect_sources_01_01")
                .currentNodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "competitorUrls": %s,
                          "sourceType": "DOCS",
                          "discoveryNotes": "test",
                          "sourceCandidates": [
                            {
                              "url": "https://example.com/docs",
                              "title": "Docs",
                              "sourceType": "DOCS",
                              "discoveryMethod": "CONFIG",
                              "reason": "test",
                              "domain": "example.com",
                              "browserTraceId": "trace-collector-001",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/help",
                              "title": "Help",
                              "sourceType": "DOCS",
                              "discoveryMethod": "CONFIG",
                              "reason": "test",
                              "domain": "example.com",
                              "browserTraceId": "trace-collector-001",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """.formatted(competitorUrlsJson))
                .build();
    }

    private AgentContext buildContextWithVerification(String competitorUrlsJson) {
        return AgentContext.builder()
                .taskId(2L)
                .taskName("task")
                .currentNodeName("collect_sources_01_01")
                .currentNodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "competitorUrls": %s,
                          "sourceType": "DOCS",
                          "discoveryNotes": "test",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 1,
                          "sourceCandidates": [
                            {
                              "url": "https://example.com/docs",
                              "title": "Docs",
                              "sourceType": "DOCS",
                              "discoveryMethod": "CONFIG",
                              "reason": "test",
                              "domain": "example.com",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """.formatted(competitorUrlsJson))
                .build();
    }

    private AgentContext buildGithubContext() {
        return AgentContext.builder()
                .taskId(3L)
                .taskName("task")
                .planVersionId(9L)
                .currentNodeName("collect_sources_01_01")
                .currentNodeConfig("""
                        {
                          "competitorName": "Acme",
                          "competitorUrls": ["https://github.com/acme/rocket"],
                          "sourceType": "GITHUB",
                          "discoveryNotes": "test",
                          "sourceCandidates": [
                            {
                              "url": "https://github.com/acme/rocket",
                              "title": "acme/rocket",
                              "sourceType": "GITHUB",
                              "sourceFamilyKey": "github",
                              "providerKey": "github",
                              "sourceUrls": ["https://github.com/acme/rocket"],
                              "discoveryMethod": "CONFIG",
                              "reason": "test",
                              "domain": "github.com",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """)
                .build();
    }

    /**
     * 测试需要同时兼容旧的 collect(String,...) 和新的 collect(SourceCollectRequest) 路径，
     * 否则架构升级后很容易只 stub 旧接口，导致执行器路径拿到 null。
     */
    private void mockCollectedPage(String url,
                                   String competitorName,
                                   String sourceType,
                                   SourceCollector.CollectedPage page) {
        when(sourceCollector.collect(argThat((SourceCollectRequest request) ->
                request != null
                        && url.equals(request.getUrl())
                        && competitorName.equals(request.getCompetitorName())
                        && sourceType.equals(request.getSourceType()))))
                .thenReturn(page);
        when(sourceCollector.collect(url, competitorName, sourceType)).thenReturn(page);
    }

    private void mockCollectedPageForAnyRequest(SourceCollector.CollectedPage page) {
        when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(page);
        when(sourceCollector.collect(any(), any(), any())).thenReturn(page);
    }
}
