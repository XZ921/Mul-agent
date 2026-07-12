package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.collection.CollectionDeadlineContext;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutor;
import cn.bugstack.competitoragent.collection.CollectionExecutorRegistry;
import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.CollectionTaskPackage;
import cn.bugstack.competitoragent.collection.CollectionTaskPackageBuilder;
import cn.bugstack.competitoragent.collection.WebPageCollectionExecutor;
import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGate;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGateProperties;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexingResult;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.CandidateVerifier;
import cn.bugstack.competitoragent.search.CollectionTargetSelector;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceViewAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
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
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/pricing\"]"));

        assertEquals("FAILED", result.getStatus().name());
        assertTrue(result.getErrorMessage().contains("未采集到可用页面内容"),
                "unexpected error: " + result.getErrorMessage() + " / " + result.getOutputSummary());
        assertTrue(result.getErrorMessage().contains("建议："));
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void shouldFallbackToThirdPartyWhenOfficialTargetSelectedButCollectionHasNoUsableEvidence() throws Exception {
        SearchExecutionCoordinator mockedSearchExecutionCoordinator = mock(SearchExecutionCoordinator.class);
        CollectorAgent fallbackCollectorAgent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                mockedSearchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        SearchExecutionResult officialSearchResult = buildSearchExecutionResult(SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://www.notion.so/help")
                        .title("Notion Help")
                        .sourceType("DOCS")
                        .discoveryMethod("DIRECT_LOCATOR")
                        .providerKey("planned")
                        .domain("www.notion.so")
                        .sourceUrls(List.of("https://www.notion.so/help"))
                        .selectionStage("SELECTED")
                        .selectionReason("planned official candidate")
                        .build())
                .build());
        SearchExecutionResult fallbackSearchResult = buildSearchExecutionResult(SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion third-party review")
                        .sourceType("DOCS")
                        .discoveryMethod("HTTP")
                        .providerKey("tavily")
                        .domain("www.getapp.com")
                        .sourceUrls(List.of("https://www.getapp.com/collaboration-software/a/notion/reviews/"))
                        .selectionStage("SELECTED")
                        .selectionReason("third-party evidence")
                        .build())
                .build());
        when(mockedSearchExecutionCoordinator.execute(
                any(CollectorNodeConfig.class),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class)))
                .thenReturn(officialSearchResult, fallbackSearchResult);
        mockCollectedPage("https://www.notion.so/help", "Notion", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://www.notion.so/help")
                .competitorName("Notion")
                .sourceType("DOCS")
                .success(false)
                .errorMessage("official page blocked")
                .build());
        mockCollectedPage("https://www.getapp.com/collaboration-software/a/notion/reviews/", "Notion", "DOCS",
                SourceCollector.CollectedPage.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion reviews")
                        .content("Notion documentation and workspace features are described by third-party review evidence.")
                        .snippet("Notion workspace features")
                        .competitorName("Notion")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        AgentResult result = fallbackCollectorAgent.execute(buildThirdPartyFallbackContext());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("degradationReasons").toString()
                .contains("OFFICIAL_UNREACHABLE_THIRDPARTY_FALLBACK"));
        assertEquals("https://www.getapp.com/collaboration-software/a/notion/reviews/",
                output.path("documents").get(0).path("sourceUrls").get(0).asText());
        assertEquals("THIRD_PARTY_FALLBACK", output.path("results").get(0).path("discoveryMethod").asText());
        assertEquals("MEDIUM", output.path("results").get(0).path("trustTier").asText());

        ArgumentCaptor<CollectorNodeConfig> configCaptor = ArgumentCaptor.forClass(CollectorNodeConfig.class);
        verify(mockedSearchExecutionCoordinator, times(2)).execute(
                configCaptor.capture(),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class));
        CollectorNodeConfig fallbackConfig = configCaptor.getAllValues().get(1);
        assertEquals(List.of("Notion third-party docs evidence"), fallbackConfig.getSearchQueries());
        assertTrue(fallbackConfig.getCompetitorUrls().isEmpty());
        assertTrue(fallbackConfig.getSourceCandidates().isEmpty());
        assertTrue(fallbackConfig.getPreferredDomains().isEmpty());
        assertTrue(fallbackConfig.getIncludeDomains().isEmpty());
        assertEquals("OPEN_WEB", fallbackConfig.getTavilyQueryMode());
        assertEquals(Boolean.TRUE, fallbackConfig.getThirdPartyFallbackActive());
    }

    @Test
    void shouldFallbackToThirdPartyWhenPrefetchedOfficialAttemptFailedAndNoTargetSelected() throws Exception {
        SearchExecutionCoordinator mockedSearchExecutionCoordinator = mock(SearchExecutionCoordinator.class);
        CollectorAgent fallbackCollectorAgent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                mockedSearchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        SearchCollectionTarget failedOfficialAttempt = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://www.notion.so/help")
                        .title("Notion Help")
                        .sourceType("DOCS")
                        .discoveryMethod("DIRECT_LOCATOR")
                        .providerKey("planned")
                        .domain("www.notion.so")
                        .sourceUrls(List.of("https://www.notion.so/help"))
                        .selectionStage("ATTEMPTED")
                        .selectionReason("official docs prefetched during verification")
                        .build())
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://www.notion.so/help")
                        .competitorName("Notion")
                        .sourceType("DOCS")
                        .success(false)
                        .errorMessage("official page blocked during verification")
                        .build())
                .build();
        SearchExecutionResult officialSearchResult = buildSearchExecutionResultWithAttemptedTargets(
                List.of(failedOfficialAttempt));
        SearchExecutionResult fallbackSearchResult = buildSearchExecutionResult(SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion third-party review")
                        .sourceType("DOCS")
                        .discoveryMethod("HTTP")
                        .providerKey("tavily")
                        .domain("www.getapp.com")
                        .sourceUrls(List.of("https://www.getapp.com/collaboration-software/a/notion/reviews/"))
                        .selectionStage("SELECTED")
                        .selectionReason("third-party evidence")
                        .build())
                .build());
        when(mockedSearchExecutionCoordinator.execute(
                any(CollectorNodeConfig.class),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class)))
                .thenReturn(officialSearchResult, fallbackSearchResult);
        mockCollectedPage("https://www.getapp.com/collaboration-software/a/notion/reviews/", "Notion", "DOCS",
                SourceCollector.CollectedPage.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion reviews")
                        .content("Notion docs and workspace capabilities are described by third-party review evidence.")
                        .snippet("Notion workspace docs")
                        .competitorName("Notion")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        AgentResult result = fallbackCollectorAgent.execute(buildThirdPartyFallbackContext());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("degradationReasons").toString()
                .contains("OFFICIAL_UNREACHABLE_THIRDPARTY_FALLBACK"));
        assertEquals("THIRD_PARTY_FALLBACK", output.path("results").get(0).path("discoveryMethod").asText());
        verify(mockedSearchExecutionCoordinator, times(2)).execute(
                any(CollectorNodeConfig.class),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class));
    }

    @Test
    void shouldGenerateThirdPartyFallbackQueriesAtRuntimeWhenPlanOmittedThem() throws Exception {
        SearchExecutionCoordinator mockedSearchExecutionCoordinator = mock(SearchExecutionCoordinator.class);
        CollectorAgent fallbackCollectorAgent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                mockedSearchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        SearchExecutionResult officialSearchResult = buildSearchExecutionResult();
        SearchExecutionResult fallbackSearchResult = buildSearchExecutionResult(SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion third-party review")
                        .sourceType("DOCS")
                        .discoveryMethod("HTTP")
                        .providerKey("tavily")
                        .domain("www.getapp.com")
                        .sourceUrls(List.of("https://www.getapp.com/collaboration-software/a/notion/reviews/"))
                        .selectionStage("SELECTED")
                        .selectionReason("third-party evidence")
                        .build())
                .build());
        when(mockedSearchExecutionCoordinator.execute(
                any(CollectorNodeConfig.class),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class)))
                .thenReturn(officialSearchResult, fallbackSearchResult);
        mockCollectedPage("https://www.getapp.com/collaboration-software/a/notion/reviews/", "Notion", "DOCS",
                SourceCollector.CollectedPage.builder()
                        .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                        .title("Notion reviews")
                        .content("Notion docs and workspace capabilities are described by third-party review evidence.")
                        .snippet("Notion workspace docs")
                        .competitorName("Notion")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        AgentResult result = fallbackCollectorAgent.execute(buildThirdPartyFallbackContextWithoutPreplannedQueries());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        ArgumentCaptor<CollectorNodeConfig> configCaptor = ArgumentCaptor.forClass(CollectorNodeConfig.class);
        verify(mockedSearchExecutionCoordinator, times(2)).execute(
                configCaptor.capture(),
                any(),
                any(),
                any(),
                any(CollectionDeadlineContext.class));
        CollectorNodeConfig fallbackConfig = configCaptor.getAllValues().get(1);
        assertEquals(List.of(
                "Notion overview features guide",
                "Notion tutorial documentation review"
        ), fallbackConfig.getSearchQueries());
        assertEquals(Boolean.TRUE, fallbackConfig.getThirdPartyFallbackActive());
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\",\"https://example.com/help\"]"));

        assertEquals("SUCCESS", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        ArgumentCaptor<cn.bugstack.competitoragent.model.entity.EvidenceSource> evidenceCaptor =
                ArgumentCaptor.forClass(cn.bugstack.competitoragent.model.entity.EvidenceSource.class);
        verify(evidenceRepository, times(1)).save(evidenceCaptor.capture());
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("browserTraceId"));
        assertTrue(result.getOutputData().contains("browserTraceId"));
    }

    @Test
    void shouldSanitizeEvidenceBeforePersistenceWhenDiscoveryReasonIsLong() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("T".repeat(600))
                .content("Example documentation content with enough useful public text.")
                .snippet("Example documentation content")
                .metadata("{\"sourceUrls\":[\"https://example.com/docs\"]}")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildLongReasonContext());

        assertEquals("SUCCESS", result.getStatus().name(),
                result.getErrorMessage() + " / " + result.getOutputSummary());
        ArgumentCaptor<EvidenceSource> evidenceCaptor = ArgumentCaptor.forClass(EvidenceSource.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());
        EvidenceSource saved = evidenceCaptor.getValue();
        assertEquals(500, saved.getTitle().length());
        assertEquals(900, saved.getDiscoveryReason().length());
    }

    @Test
    void shouldRetainPersistenceFailureReasonInCollectionAuditWhenEvidenceSaveFails() throws Exception {
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
                .metadata("{\"sourceUrls\":[\"https://example.com/docs\"]}")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        doThrow(new IllegalStateException("value too long for column discovery_reason"))
                .when(evidenceRepository)
                .save(any());

        AgentResult result = collectorAgent.execute(buildSingleCandidateContext(
                "https://example.com/docs",
                "Docs",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("FAILED", result.getStatus().name());
        assertTrue(result.getErrorMessage().contains("证据落库失败"));
        assertTrue(output.path("documents").get(0).path("issueFlags").toString().contains("EVIDENCE_PERSIST_FAILED"));
        assertTrue(output.path("documents").get(0).path("persistenceFailureReason").asText()
                .contains("value too long for column discovery_reason"));
        assertTrue(output.path("issueFlags").toString().contains("EVIDENCE_PERSIST_FAILED"));
        assertEquals("FAILED", output.path("collectionAudit").path("summary").path("status").asText());
    }

    @Test
    void shouldDiscardLatePersistenceWhenNodeTerminateRequested() {
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
                .metadata("{\"sourceUrls\":[\"https://example.com/docs\"]}")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        when(nodeRepository.findByTaskIdAndNodeName(2L, "collect_sources_01_01")).thenReturn(Optional.of(TaskNode.builder()
                .id(201L)
                .taskId(2L)
                .nodeName("collect_sources_01_01")
                .status(TaskNodeStatus.RUNNING)
                .controlState(TaskNodeControlState.TERMINATE_REQUESTED)
                .build()));

        collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));

        verify(evidenceRepository, never()).save(any());
        verify(taskRetrievalIndexService, never()).indexEvidence(any());
        verify(nodeRepository, never()).save(any(TaskNode.class));
    }

    @Test
    void shouldDiscardLatePersistenceWhenTaskAlreadyStopped() {
        CollectorAgent collectorAgentWithTaskRepository = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                taskRepository,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer()
        );
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
                .metadata("{\"sourceUrls\":[\"https://example.com/docs\"]}")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        when(taskRepository.findById(2L)).thenReturn(Optional.of(AnalysisTask.builder()
                .id(2L)
                .status(AnalysisTaskStatus.STOPPED)
                .build()));
        when(nodeRepository.findByTaskIdAndNodeName(2L, "collect_sources_01_01")).thenReturn(Optional.of(TaskNode.builder()
                .id(202L)
                .taskId(2L)
                .nodeName("collect_sources_01_01")
                .status(TaskNodeStatus.RUNNING)
                .controlState(TaskNodeControlState.NONE)
                .build()));

        collectorAgentWithTaskRepository.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));

        verify(evidenceRepository, never()).save(any());
        verify(taskRetrievalIndexService, never()).indexEvidence(any());
        verify(nodeRepository, never()).save(any(TaskNode.class));
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\",\"https://example.com/help\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("https://example.com/docs", output.path("documents").get(0).path("sourceUrls").get(0).asText());
        assertEquals("https://example.com/help", output.path("documents").get(1).path("sourceUrls").get(0).asText());
        assertTrue(output.path("documents").get(1).path("issueFlags").toString().contains("COLLECT_FAILED"));
        assertTrue(output.path("issueFlags").toString().contains("PARTIAL_COLLECTION_FAILURE"));
        assertEquals("PARTIAL_SUCCESS", output.path("collectionStatus").asText());
        assertEquals("PARTIAL_SUCCESS", output.path("collectionAudit").path("summary").path("status").asText());
        assertTrue(output.path("collectionAudit").path("replayTimeline").isArray());
        assertEquals(2, output.path("collectionAudit").path("replayTimeline").size());
        assertTrue(output.path("collectionAudit").path("sourceUrls").isArray());
        assertTrue(output.path("evidenceFragments").isArray());
        assertTrue(output.path("sectionEvidenceBundles").isArray());
        assertTrue(output.path("downstreamEvidenceViews").isArray());
        assertTrue(output.path("documents").get(0).path("downstreamEvidenceViews").isArray());
    }

    @Test
    void shouldExposeInternallyDiscoveredChildPagesInCollectorOutput() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .title("Docs Home")
                .content("[账户授权](https://example.com/docs/auth)")
                .snippet("docs home")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());
        mockCollectedPage("https://example.com/docs/auth", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs/auth")
                .title("账户授权")
                .content("auth details")
                .snippet("auth details")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildSingleCandidateContext(
                "https://example.com/docs",
                "Docs Home",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertEquals(2, output.path("documents").size());
        assertEquals(2, output.path("collectionAudit").path("results").size());
        assertEquals(2, output.path("collectionAudit").path("replayTimeline").size());
        assertTrue(output.toString().contains("https://example.com/docs/auth"));
        assertTrue(output.toString().contains("内部发现页面"));
    }

    @Test
    void shouldDeduplicatePersistedEvidenceByCanonicalUrlWithinCollectorNode() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        when(searchCoordinator.execute(any(), any(), any(), any(), any())).thenReturn(SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .steps(List.of())
                        .build())
                .sourceCandidates(List.of())
                .selectedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(buildSourceCandidate("http://www.example.com/docs/?utm_source=dup"))
                        .build()))
                .build());
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(
                        buildSuccessfulCollectionResult(
                                "collect_sources_01_05#001",
                                1,
                                "http://www.example.com/docs/?utm_source=dup",
                                "Docs primary"),
                        buildSuccessfulCollectionResult(
                                "collect_sources_01_05#002",
                                2,
                                "https://example.com/docs",
                                "Docs duplicate")))
                .build());
        when(collectionCoordinator.summarize(any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .build());

        AgentResult result = agent.execute(buildSingleCandidateContext(
                "http://www.example.com/docs/?utm_source=dup",
                "Docs primary",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        verify(evidenceRepository, times(1)).save(any(EvidenceSource.class));
        assertEquals(2, output.path("documents").size());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("documents").get(1).path("issueFlags").toString().contains("DUPLICATE_CANONICAL_URL"));
        assertTrue(output.path("results").get(1).path("persisted").isBoolean());
        assertEquals(false, output.path("results").get(1).path("persisted").asBoolean());
    }

    @Test
    void shouldNotPromoteNavigationShellResultAsFormalEvidence() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        when(searchCoordinator.execute(any(), any(), any(), any(), any())).thenReturn(SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .steps(List.of())
                        .build())
                .sourceCandidates(List.of())
                .selectedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(buildSourceCandidate("https://example.com/docs"))
                        .build()))
                .build());
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(CollectionExecutionResult.builder()
                        .taskPackageKey("collect_sources_01_03#001")
                        .targetIndex(1)
                        .executorType("WEB_PAGE")
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://example.com/docs")
                        .title("Docs shell")
                        .content("棣栭〉 鏂囨。涓績 甯姪涓績 鐧诲綍 娉ㄥ唽 鑱旂郴鎴戜滑")
                        .sourceUrls(List.of("https://example.com/docs"))
                        .discoveryDepth(0)
                        .qualitySignals(List.of("NAVIGATION_SHELL_DETECTED", "WEAK_MAIN_CONTENT"))
                        .qualityScore(0.12D)
                        .build()
                        .normalize()))
                .build());
        when(collectionCoordinator.summarize(any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .build());

        AgentResult result = agent.execute(buildSingleCandidateContext(
                "https://example.com/docs",
                "Docs shell",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("FAILED", result.getStatus().name());
        verify(evidenceRepository, never()).save(any(EvidenceSource.class));
        assertEquals(false, output.path("results").get(0).path("persisted").asBoolean());
        assertTrue(output.path("downstreamEvidenceViews").isArray());
        assertEquals(0, output.path("downstreamEvidenceViews").size());
        assertEquals(0, output.path("documents").get(0).path("downstreamEvidenceViews").size());
    }

    @Test
    void shouldPromoteUsefulContentWhenAuthGateSignalIsOnlyWeakAuditSignal() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        when(searchCoordinator.execute(any(), any(), any(), any(), any())).thenReturn(SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .steps(List.of())
                        .build())
                .sourceCandidates(List.of())
                .selectedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(buildSourceCandidate("https://example.com/docs/auth"))
                        .build()))
                .build());
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(CollectionExecutionResult.builder()
                        .taskPackageKey("collect_sources_01_03#001")
                        .targetIndex(1)
                        .executorType("WEB_PAGE")
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://example.com/docs/auth")
                        .title("Docs auth")
                        .content("useful official API docs with OAuth authorization, SDK guide, login callback, "
                                + "token exchange, webhook configuration and enough public details for analysis")
                        .sourceUrls(List.of("https://example.com/docs/auth"))
                        .discoveryDepth(0)
                        .qualitySignals(List.of("OFFICIAL_DOMAIN_MATCHED", "AUTH_GATE_WEAK_SIGNAL"))
                        .qualityScore(0.75D)
                        .build()
                        .normalize()))
                .build());
        when(collectionCoordinator.summarize(any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .build());

        AgentResult result = agent.execute(buildSingleCandidateContext(
                "https://example.com/docs/auth",
                "Docs auth",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        verify(evidenceRepository, times(1)).save(any(EvidenceSource.class));
        assertEquals(true, output.path("results").get(0).path("persisted").asBoolean());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("results").get(0).path("issueFlags").toString().contains("AUTH_GATE_WEAK_SIGNAL"));
        assertTrue(!output.path("results").get(0).path("issueFlags").toString().contains("FORMAL_EVIDENCE_DEGRADED"));
        assertTrue(output.path("downstreamEvidenceViews").isArray());
        assertEquals(1, output.path("downstreamEvidenceViews").size());
    }

    @Test
    void shouldFailWhenCollectedContentExistsButFormalSelectedTargetsAreMissing() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        when(searchCoordinator.execute(any(), any(), any(), any(), any())).thenReturn(SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .steps(List.of(SearchExecutionStep.builder()
                                .stepCode("COLLECT_PAGES")
                                .goal("collect selected pages and persist evidence")
                                .status(SearchExecutionStep.StepStatus.PENDING)
                                .build()))
                        .build())
                .sourceCandidates(List.of())
                .selectedTargets(List.of(SearchCollectionTarget.builder().build()))
                .build());
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(buildSuccessfulCollectionResult(
                        "collect_sources_01_03#001",
                        1,
                        "https://example.com/docs",
                        "Docs without formal target"
                )))
                .build());
        when(collectionCoordinator.summarize(any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .build());

        AgentResult result = agent.execute(buildSingleCandidateContext(
                "https://example.com/docs",
                "Docs without formal target",
                "DOCS"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("FAILED", result.getStatus().name());
        assertEquals(0, output.path("selectedTargets").size());
        assertEquals(1, output.path("successCollected").asInt());
        assertEquals("FAILED",
                output.path("searchExecutionPlan").path("steps").get(0).path("status").asText());
        assertEquals("FAILED",
                output.path("searchProgressSnapshots")
                        .get(output.path("searchProgressSnapshots").size() - 1)
                        .path("status").asText());
        assertTrue(output.path("issueFlags").toString().contains("FORMAL_TARGETS_MISSING"));
        verify(evidenceRepository, times(1)).save(any(EvidenceSource.class));
    }

    @Test
    void shouldIncludeSelectedTargetSearchFirstAuditFieldsInOutput() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent agent = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper
        );
        when(searchCoordinator.execute(any(), any(), any(), any(), any())).thenReturn(SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .steps(List.of())
                        .build())
                .sourceCandidates(List.of())
                .selectedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(SourceCandidate.builder()
                                .url("https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity")
                                .title("开放能力文档")
                                .sourceType("OFFICIAL")
                                .providerKey("tavily")
                                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                                .reason("test")
                                .domain("developer.open-douyin.com")
                                .sourceUrls(List.of("https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity"))
                                .selectionStage("SELECTED")
                                .selectionReason("fusion picked strong tavily candidate")
                                .tavilyQueryMode("TRUSTED_WEB_EXPANSION")
                                .qualityTier("STRONG")
                                .fastLaneUsable(Boolean.TRUE)
                                .prefetchedRawContentLength(19_555)
                                .skipNetworkVerification(Boolean.TRUE)
                                .build())
                        .build()))
                .build());
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(buildSuccessfulCollectionResult(
                        "collect_sources_01_03#001",
                        1,
                        "https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity",
                        "开放能力文档"
                )))
                .build());
        when(collectionCoordinator.summarize(any())).thenReturn(CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .build());

        AgentResult result = agent.execute(buildSingleCandidateContext(
                "https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity",
                "开放能力文档",
                "OFFICIAL"
        ));
        JsonNode output = objectMapper.readTree(result.getOutputData());
        JsonNode selectedTarget = output.path("selectedTargets").get(0);

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertEquals("TAVILY_PHASE1_BOOTSTRAP", selectedTarget.path("discoveryMethod").asText());
        assertEquals("TRUSTED_WEB_EXPANSION", selectedTarget.path("tavilyQueryMode").asText());
        assertEquals("STRONG", selectedTarget.path("qualityTier").asText());
        assertTrue(selectedTarget.path("fastLaneUsable").asBoolean());
        assertEquals(19_555, selectedTarget.path("prefetchedRawContentLength").asInt());
        assertTrue(selectedTarget.path("skipNetworkVerification").asBoolean());
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertTrue(output.path("documents").get(0).path("evidenceId").asText()
                .startsWith("T0002-COLLECT_SOURCES_01_01-"));
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

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));
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
        // 该场景命中验证成功后复用预抓取页面，后续 collection 阶段不应再次重复抓取。
        verify(sourceCollector, times(1)).collect("https://example.com/docs", "Feishu", "DOCS");
    }

    @Test
    void shouldNotDuplicateCollectSelectedTargetWhenPrefetchedPageExists() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser search unused")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://docs.example.com/prefetched", "Acme", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://docs.example.com/prefetched")
                .title("Prefetched Docs")
                .content("Open API 文档 OAuth SDK guide reference")
                .snippet("Open API 文档")
                .competitorName("Acme")
                .sourceType("DOCS")
                .success(true)
                .build());

        AgentResult result = collectorAgent.execute(buildPrefetchedDocsContext());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        verify(sourceCollector, times(1)).collect("https://docs.example.com/prefetched", "Acme", "DOCS");
    }

    @Test
    void shouldReflectFailedPrefetchedPageInsideCollectionAudit() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser search unused")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", SourceCollector.CollectedPage.builder()
                .url("https://example.com/docs")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(false)
                .errorMessage("verification failed")
                .build());

        AgentResult result = collectorAgent.execute(buildContextWithVerification("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("FAILED", result.getStatus().name());
        assertEquals("FAILED", output.path("collectionStatus").asText());
        assertEquals("FAILED", output.path("collectionAudit").path("summary").path("status").asText());
        assertEquals("collect_sources_01_01#001",
                output.path("collectionAudit").path("summary").path("recoveryCheckpoint").asText());
        assertEquals(1, output.path("collectionAudit").path("results").size());
        assertEquals("FAILED", output.path("collectionAudit").path("results").get(0).path("status").asText());
        assertEquals(1, output.path("collectionAudit").path("replayTimeline").size());
        // 候选验证阶段已经统一接入外部抓取重试策略，失败页应按最大重试次数尝试抓取，
        // 这样 collection audit 才能稳定反映“验证失败后仍无法恢复”的真实执行事实。
        verify(sourceCollector, times(3)).collect("https://example.com/docs", "Feishu", "DOCS");
    }

    @Test
    void shouldReturnSuccessDegradedWhenHardDeadlineReachedAfterPartialEvidenceWasCaptured() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        ) {
            long resolveCollectorHardDeadlineMillis(CollectorNodeConfig config, String sourceType) {
                return 50L;
            }

            long resolveCollectorDeadlineDrainGraceMillis(CollectorNodeConfig config, String sourceType) {
                return 0L;
            }
        };
        SearchCollectionTarget prefetchedTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/docs"))
                .collectedPage(successfulCollectedPage("https://example.com/docs", "Docs"))
                .build();
        SearchCollectionTarget pendingTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/help"))
                .build();
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildSearchExecutionResult(prefetchedTarget, pendingTarget));
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200L);
                    return buildCollectionReport(List.of());
                });
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = deadlineAwareCollector.execute(
                buildContextWithVerification("[\"https://example.com/docs\",\"https://example.com/help\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(), result.getErrorMessage());
        assertTrue(output.path("sourceUrls").isArray());
        assertTrue(output.path("sourceUrls").size() > 0);
        assertTrue(output.path("degradationReasons").toString().contains("HARD_DEADLINE_REACHED"));
        assertEquals("HARD_DEADLINE_REACHED",
                output.path("searchExecutionTrace").path("degradationReason").asText());
        assertTrue(output.path("readyForQuorum").asBoolean());
    }

    @Test
    void shouldReturnSuccessDegradedWhenHardDeadlineReachedBeforeAnyUsableEvidenceWasCaptured() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        ) {
            long resolveCollectorHardDeadlineMillis(CollectorNodeConfig config, String sourceType) {
                return 50L;
            }
        };
        SearchCollectionTarget pendingTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/help"))
                .build();
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildSearchExecutionResult(pendingTarget));
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200L);
                    return buildCollectionReport(List.of());
                });
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = deadlineAwareCollector.execute(
                buildContextWithVerification("[\"https://example.com/help\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name());
        assertNull(result.getErrorMessage());
        assertTrue(output.path("degradationReasons").toString().contains("HARD_DEADLINE_REACHED"));
        assertEquals("HARD_DEADLINE_REACHED",
                output.path("searchExecutionTrace").path("degradationReason").asText());
        assertTrue(output.path("sourceUrls").isArray());
        assertFalse(output.path("readyForQuorum").asBoolean());
    }

    @Test
    void shouldDrainSearchResultThatFinishesJustAfterHardDeadline() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        ) {
            long resolveCollectorHardDeadlineMillis(CollectorNodeConfig config, String sourceType) {
                return 50L;
            }
        };
        SearchCollectionTarget prefetchedTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/docs"))
                .collectedPage(successfulCollectedPage("https://example.com/docs", "Docs"))
                .build();
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(80L);
                    return buildSearchExecutionResult(prefetchedTarget);
                });
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = deadlineAwareCollector.execute(
                buildContextWithVerification("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(), result.getErrorMessage());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("readyForQuorum").asBoolean());
        assertEquals("HARD_DEADLINE_REACHED",
                output.path("searchExecutionTrace").path("degradationReason").asText());
    }

    @Test
    void shouldDrainCollectionReportThatFinishesJustAfterHardDeadline() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        ) {
            long resolveCollectorHardDeadlineMillis(CollectorNodeConfig config, String sourceType) {
                return 50L;
            }
        };
        SearchCollectionTarget pendingTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/docs"))
                .build();
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildSearchExecutionResult(pendingTarget));
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(80L);
                    return buildCollectionReport(List.of(buildSuccessfulCollectionResult(
                            "collect_sources_01_01#001",
                            1,
                            "https://example.com/docs",
                            "Docs")));
                });
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = deadlineAwareCollector.execute(
                buildContextWithVerification("[\"https://example.com/docs\"]"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(), result.getErrorMessage());
        assertEquals(1, output.path("successCollected").asInt());
        assertTrue(output.path("readyForQuorum").asBoolean());
        assertEquals("HARD_DEADLINE_REACHED",
                output.path("searchExecutionTrace").path("degradationReason").asText());
    }

    @Test
    void shouldStopCollectorWhenSearchPhaseExceedsHardDeadline() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        ) {
            long resolveCollectorHardDeadlineMillis(CollectorNodeConfig config, String sourceType) {
                return 50L;
            }

            long resolveCollectorDeadlineDrainGraceMillis(CollectorNodeConfig config, String sourceType) {
                return 0L;
            }
        };
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(5_000L);
                    return buildSearchExecutionResult(SearchCollectionTarget.builder()
                            .candidate(buildSourceCandidate("https://example.com/docs"))
                            .build());
                });
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> deadlineAwareCollector.execute(
                        buildSingleCandidateContext("https://example.com/docs", "Docs", "DOCS")));

        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name());
        assertNull(result.getErrorMessage());
        assertTrue(result.getOutputData().contains("HARD_DEADLINE_REACHED"));
        assertFalse(output.path("readyForQuorum").asBoolean());
        verify(collectionCoordinator, never()).execute(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnSuccessDegradedWhenCoordinatorStopsInsideDeadlineLoop() throws Exception {
        SearchExecutionCoordinator searchCoordinator = mock(SearchExecutionCoordinator.class);
        CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
        CollectorAgent deadlineAwareCollector = new CollectorAgent(
                logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchCoordinator,
                collectionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                new EvidenceQualityGate(new EvidenceQualityGateProperties()),
                new EvidenceSourceSanitizer(),
                null
        );
        SearchCollectionTarget pendingTarget = SearchCollectionTarget.builder()
                .candidate(buildSourceCandidate("https://example.com/docs"))
                .build();
        when(searchCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(buildSearchExecutionResult(pendingTarget));
        when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(buildHardDeadlineDegradedCollectionReport(List.of(buildSuccessfulCollectionResult(
                        "collect_sources_01_01#001",
                        1,
                        "https://example.com/docs",
                        "Docs"))));
        when(collectionCoordinator.summarize(any()))
                .thenAnswer(invocation -> buildCollectionReport(invocation.getArgument(0)));

        AgentResult result = deadlineAwareCollector.execute(
                buildSingleCandidateContext("https://example.com/docs", "Docs", "DOCS"));
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS_DEGRADED", result.getStatus().name(), result.getErrorMessage());
        assertEquals("SUCCESS_DEGRADED", output.path("collectionStatus").asText());
        assertEquals("SUCCESS_DEGRADED", output.path("collectionAudit").path("status").asText());
        assertEquals("SUCCESS_DEGRADED", output.path("collectionAudit").path("summary").path("status").asText());
        assertTrue(output.path("degradationReasons").toString().contains("HARD_DEADLINE_REACHED"));
        assertTrue(output.path("collectionAudit").path("summary").path("degradationReasons").toString()
                .contains("HARD_DEADLINE_REACHED"));
    }

    @Test
    void shouldMarkCheckpointReuseForPrefetchedSuccessWhenTargetOrderChanges() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser search unused")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        mockCollectedPage("https://example.com/docs", "Feishu", "DOCS", successfulCollectedPage("https://example.com/docs", "Docs"));
        mockCollectedPage("https://example.com/documentation", "Feishu", "DOCS",
                successfulCollectedPage("https://example.com/documentation", "Documentation"));
        mockCollectedPage("https://example.com/guide", "Feishu", "DOCS", successfulCollectedPage("https://example.com/guide", "Guide"));
        mockCollectedPage("https://example.com/help", "Feishu", "DOCS", successfulCollectedPage("https://example.com/help", "Help"));

        AgentResult result = collectorAgent.execute(buildContextWithVerificationAndCollectionCheckpoint());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertEquals(4, output.path("collectionAudit").path("summary").path("totalPackages").asInt());
        assertEquals(1, output.path("collectionAudit").path("summary").path("reusedCount").asInt());
        JsonNode helpResult = findCollectionAuditResult(output, "https://example.com/help");
        assertEquals("SUCCESS", helpResult.path("status").asText());
        assertTrue(helpResult.path("reusedFromCheckpoint").asBoolean());
        assertEquals("collectionAuditCheckpoint", helpResult.path("checkpointSource").asText());
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

    @Test
    void shouldPersistRssStructuredPayloadEvenWhenContentIsShort() throws Exception {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(githubApiExecutor.supports(any())).thenAnswer(invocation -> {
            CollectionTaskPackage taskPackage = invocation.getArgument(0);
            return taskPackage != null && "RSS".equalsIgnoreCase(taskPackage.getPrimaryTool());
        });
        when(githubApiExecutor.execute(any())).thenReturn(
                CollectionExecutionResult.builder()
                        .executorType("API_DATA")
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("rss://feed/YWNtZQ==")
                        .title("https://blog.example.com/feed.xml")
                        .content("简讯")
                        .sourceUrls(List.of("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch"))
                        .qualitySignals(List.of("FEED_ITEMS_READY"))
                        .qualityScore(0.68D)
                        .structuredPayload(Map.of(
                                "feedUrl", "https://blog.example.com/feed.xml",
                                "items", List.of(Map.of(
                                        "title", "Agent launch",
                                        "link", "https://blog.example.com/agent-launch",
                                        "summary", "简讯"
                                ))
                        ))
                        .durationMillis(12L)
                        .build()
        );

        AgentResult result = collectorAgent.execute(buildRssContext());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name(), result.getErrorMessage());
        assertTrue(output.path("collectionAudit").path("sourceUrls").isArray());
        assertEquals("https://blog.example.com/feed.xml",
                output.path("sourceUrls").get(0).asText());
        assertTrue(output.path("documents").get(0).path("sourceUrls").isArray());
        assertEquals("https://blog.example.com/feed.xml",
                output.path("documents").get(0).path("sourceUrls").get(0).asText());

        ArgumentCaptor<cn.bugstack.competitoragent.model.entity.EvidenceSource> evidenceCaptor =
                ArgumentCaptor.forClass(cn.bugstack.competitoragent.model.entity.EvidenceSource.class);
        verify(evidenceRepository, times(1)).save(evidenceCaptor.capture());
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"items\""));
        assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"sourceUrls\""));
        verify(sourceCollector, never()).collect("https://blog.example.com/feed.xml", "Acme RSS", "NEWS");
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
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/docs"],
                              "browserTraceId": "trace-collector-001",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/help",
                              "title": "Help",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/help"],
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
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/docs"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """.formatted(competitorUrlsJson))
                .build();
    }

    private AgentContext buildThirdPartyFallbackContext() {
        return AgentContext.builder()
                .taskId(22L)
                .taskName("task")
                .currentNodeName("collect_sources_notion_docs")
                .currentNodeConfig("""
                        {
                          "competitorName": "Notion",
                          "competitorUrls": ["https://www.notion.so/help"],
                          "sourceType": "DOCS",
                          "discoveryNotes": "official docs first",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "searchQueries": ["site:notion.so Notion docs"],
                          "thirdPartyFallbackQueries": ["Notion third-party docs evidence"],
                          "tavilyQueryMode": "OFFICIAL_DOCS",
                          "preferredDomains": ["notion.so"],
                          "includeDomains": ["notion.so"],
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 1,
                          "sourceCandidates": [
                            {
                              "url": "https://www.notion.so/help",
                              "title": "Notion Help",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "official docs",
                              "domain": "www.notion.so",
                              "sourceUrls": ["https://www.notion.so/help"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "official docs"
                            }
                          ]
                        }
                        """)
                .build();
    }

    private AgentContext buildThirdPartyFallbackContextWithoutPreplannedQueries() {
        AgentContext context = buildThirdPartyFallbackContext();
        return context.toBuilder()
                .currentNodeConfig(context.getCurrentNodeConfig().replaceAll(
                        "\\s*\"thirdPartyFallbackQueries\"\\s*:\\s*\\[\"Notion third-party docs evidence\"\\],\\R?",
                        ""))
                .build();
    }

    private SearchExecutionResult buildSearchExecutionResult(SearchCollectionTarget... targets) {
        List<SearchCollectionTarget> selectedTargets = targets == null ? List.of() : List.of(targets);
        return SearchExecutionResult.builder()
                .executionPlan(SearchExecutionPlan.builder()
                        .stage("COLLECT")
                        .searchQueries(List.of("Feishu docs"))
                        .steps(List.of(
                                SearchExecutionStep.builder()
                                        .stepCode("DISCOVER_SOURCES")
                                        .goal("发现来源")
                                        .status(SearchExecutionStep.StepStatus.SUCCESS)
                                        .build(),
                                SearchExecutionStep.builder()
                                        .stepCode("COLLECT_PAGES")
                                        .goal("采集页面")
                                        .status(SearchExecutionStep.StepStatus.RUNNING)
                                        .build()
                        ))
                        .build())
                .sourceCandidates(selectedTargets.stream()
                        .map(SearchCollectionTarget::getCandidate)
                        .collect(Collectors.toList()))
                .selectedTargets(selectedTargets)
                .executionTrace(SearchExecutionTrace.builder()
                        .searchMode("HTTP_ONLY")
                        .selectedUrls(selectedTargets.stream()
                                .map(SearchCollectionTarget::getCandidate)
                                .filter(java.util.Objects::nonNull)
                                .map(SourceCandidate::getUrl)
                                .collect(Collectors.toList()))
                        .build())
                .reasoningSummary("test")
                .build();
    }

    private SearchExecutionResult buildSearchExecutionResultWithAttemptedTargets(
            List<SearchCollectionTarget> attemptedTargets) {
        SearchExecutionResult result = buildSearchExecutionResult();
        List<SearchCollectionTarget> safeAttemptedTargets = attemptedTargets == null ? List.of() : attemptedTargets;
        result.setAttemptedTargets(safeAttemptedTargets);
        result.setSourceCandidates(safeAttemptedTargets.stream()
                .map(SearchCollectionTarget::getCandidate)
                .collect(Collectors.toList()));
        return result;
    }

    private CollectionExecutionReport buildCollectionReport(List<CollectionExecutionResult> results) {
        List<CollectionExecutionResult> safeResults = results == null ? List.of() : results;
        int successCount = (int) safeResults.stream().filter(CollectionExecutionResult::isSuccess).count();
        int failedCount = safeResults.size() - successCount;
        String status = safeResults.isEmpty()
                ? "SUCCESS"
                : failedCount == 0 ? "SUCCESS" : successCount == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        List<String> sourceUrls = safeResults.stream()
                .flatMap(result -> result.getSourceUrls() == null ? java.util.stream.Stream.<String>empty() : result.getSourceUrls().stream())
                .distinct()
                .collect(Collectors.toList());
        return CollectionExecutionReport.builder()
                .status(status)
                .results(safeResults)
                .sourceUrls(sourceUrls)
                .auditSnapshot(CollectionAuditSnapshot.builder()
                        .status(status)
                        .results(safeResults)
                        .replayTimeline(List.of())
                        .sourceUrls(sourceUrls)
                        .summary(CollectionAuditSummary.builder()
                                .totalPackages(safeResults.size())
                                .successCount(successCount)
                                .failedCount(failedCount)
                                .reusedCount(0)
                                .status(status)
                                .sourceUrls(sourceUrls)
                                .build())
                        .build())
                .build();
    }

    private CollectionExecutionReport buildHardDeadlineDegradedCollectionReport(List<CollectionExecutionResult> results) {
        CollectionExecutionReport baseReport = buildCollectionReport(results);
        CollectionAuditSnapshot auditSnapshot = baseReport.getAuditSnapshot();
        CollectionAuditSummary summary = auditSnapshot == null ? null : auditSnapshot.getSummary();
        return baseReport.toBuilder()
                .status("SUCCESS_DEGRADED")
                .degraded(true)
                .degradationReasons(List.of("HARD_DEADLINE_REACHED"))
                .auditSnapshot(auditSnapshot == null
                        ? null
                        : auditSnapshot.toBuilder()
                        .status("SUCCESS_DEGRADED")
                        .summary(summary == null
                                ? null
                                : summary.toBuilder()
                                .status("SUCCESS_DEGRADED")
                                .degradationReasons(List.of("HARD_DEADLINE_REACHED"))
                                .build())
                        .build())
                .build();
    }

    private AgentContext buildPrefetchedDocsContext() {
        return AgentContext.builder()
                .taskId(7L)
                .taskName("task")
                .currentNodeName("collect_sources_docs")
                .currentNodeConfig("""
                        {
                          "competitorName": "Acme",
                          "competitorUrls": ["https://docs.example.com/prefetched"],
                          "sourceType": "DOCS",
                          "discoveryNotes": "test",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 4,
                          "sourceCandidates": [
                            {
                              "url": "https://docs.example.com/prefetched",
                              "title": "Prefetched Docs",
                              "sourceType": "DOCS",
                              "sourceFamilyKey": "official",
                              "sourceUrls": ["https://docs.example.com/prefetched"],
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "docs.example.com",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/help",
                              "title": "Help",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/help"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "閰嶇疆鎻愪緵"
                            },
                            {
                              "url": "https://example.com/pricing",
                              "title": "Pricing",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/pricing"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "閰嶇疆鎻愪緵"
                            }
                          ]
                        }
                        """)
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
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 1,
                          "sourceCandidates": [
                            {
                              "url": "https://github.com/acme/rocket",
                              "title": "acme/rocket",
                              "sourceType": "GITHUB",
                              "sourceFamilyKey": "github",
                              "providerKey": "github",
                              "sourceUrls": ["https://github.com/acme/rocket"],
                              "discoveryMethod": "DIRECT_LOCATOR",
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

    private AgentContext buildRssContext() {
        return AgentContext.builder()
                .taskId(5L)
                .taskName("task")
                .planVersionId(9L)
                .currentNodeName("collect_sources_news")
                .currentNodeConfig("""
                        {
                          "competitorName": "Acme RSS",
                          "competitorUrls": ["https://blog.example.com/feed.xml"],
                          "sourceType": "NEWS",
                          "sourceFamilyKey": "news",
                          "discoveryNotes": "test",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 1,
                          "sourceCandidates": [
                            {
                              "url": "https://blog.example.com/feed.xml",
                              "title": "Acme Feed",
                              "sourceType": "NEWS",
                              "sourceFamilyKey": "news",
                              "providerKey": "http",
                              "sourceUrls": [
                                "https://blog.example.com/feed.xml"
                              ],
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "reason": "test",
                              "domain": "blog.example.com",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """)
                .build();
    }

    private AgentContext buildContextWithVerificationAndCollectionCheckpoint() {
        return AgentContext.builder()
                .taskId(4L)
                .taskName("task")
                .currentNodeName("collect_sources_01_02")
                .currentNodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "competitorUrls": [
                            "https://example.com/help",
                            "https://example.com/docs",
                            "https://example.com/documentation",
                            "https://example.com/guide"
                          ],
                          "sourceType": "DOCS",
                          "discoveryNotes": "test",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 4,
                          "searchRuntimePolicy": {
                            "maxCandidatesPerDomain": 4
                          },
                          "sourceCandidates": [
                            {
                              "url": "https://example.com/help",
                              "title": "Help",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/help"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/docs",
                              "title": "Docs",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/docs"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/documentation",
                              "title": "Documentation",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/documentation"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            },
                            {
                              "url": "https://example.com/guide",
                              "title": "Guide",
                              "sourceType": "DOCS",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/guide"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ],
                          "_testNote": "该场景需要让 competitorUrls 数量与 maxSearchResults 一致，并显式放宽 maxCandidatesPerDomain，否则 Phase 1 默认 per-domain 软平衡会先把同域 4 个候选裁成 2 个，无法稳定复现 rerun 后 4 个 prefetched target 的顺序漂移",
                          "collectionAuditCheckpoint": {
                            "summary": {
                              "totalPackages": 4,
                              "successCount": 1,
                              "failedCount": 3,
                              "reusedCount": 0,
                              "status": "PARTIAL_SUCCESS",
                              "recoveryCheckpoint": "collect_sources_01_02#002",
                              "sourceUrls": [
                                "https://example.com/help",
                                "https://example.com/docs",
                                "https://example.com/documentation",
                                "https://example.com/guide"
                              ]
                            },
                            "status": "PARTIAL_SUCCESS",
                            "results": [
                              {
                                "taskPackageKey": "collect_sources_01_02#001",
                                "targetIndex": 1,
                                "executorType": "WEB_PAGE",
                                "success": true,
                                "status": "SUCCESS",
                                "resourceLocator": "https://example.com/help",
                                "sourceUrls": ["https://example.com/help"]
                              },
                              {
                                "taskPackageKey": "collect_sources_01_02#002",
                                "targetIndex": 2,
                                "executorType": "PREFETCHED_PAGE",
                                "success": false,
                                "status": "FAILED",
                                "resourceLocator": "https://example.com/docs",
                                "failureKind": "PREFETCH_FAILED",
                                "sourceUrls": ["https://example.com/docs"]
                              },
                              {
                                "taskPackageKey": "collect_sources_01_02#003",
                                "targetIndex": 3,
                                "executorType": "PREFETCHED_PAGE",
                                "success": false,
                                "status": "FAILED",
                                "resourceLocator": "https://example.com/documentation",
                                "failureKind": "PREFETCH_FAILED",
                                "sourceUrls": ["https://example.com/documentation"]
                              },
                              {
                                "taskPackageKey": "collect_sources_01_02#004",
                                "targetIndex": 4,
                                "executorType": "PREFETCHED_PAGE",
                                "success": false,
                                "status": "FAILED",
                                "resourceLocator": "https://example.com/guide",
                                "failureKind": "PREFETCH_FAILED",
                                "sourceUrls": ["https://example.com/guide"]
                              }
                            ],
                            "replayTimeline": [
                              {
                                "taskPackageKey": "collect_sources_01_02#001",
                                "targetIndex": 1,
                                "status": "SUCCESS",
                                "executorType": "WEB_PAGE",
                                "resourceLocator": "https://example.com/help",
                                "sourceUrls": ["https://example.com/help"]
                              }
                            ],
                            "recoveryCheckpoint": "collect_sources_01_02#002",
                            "sourceUrls": [
                              "https://example.com/help",
                              "https://example.com/docs",
                              "https://example.com/documentation",
                              "https://example.com/guide"
                            ]
                          }
                        }
                        """)
                .build();
    }

    private AgentContext buildSingleCandidateContext(String url, String title, String sourceType) {
        return AgentContext.builder()
                .taskId(6L)
                .taskName("task")
                .currentNodeName("collect_sources_01_03")
                .currentNodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "competitorUrls": ["%s"],
                          "sourceType": "%s",
                          "discoveryNotes": "test",
                          "verifyCandidates": true,
                          "verifyResultPage": true,
                          "browserSearchEnabled": false,
                          "searchMode": "HTTP_ONLY",
                          "minVerifiedCandidates": 1,
                          "maxSearchResults": 1,
                          "sourceCandidates": [
                            {
                              "url": "%s",
                              "title": "%s",
                              "sourceType": "%s",
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "test",
                              "domain": "example.com",
                              "sourceUrls": ["%s"],
                              "browserTraceId": "trace-collector-002",
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """.formatted(url, sourceType, url, title, sourceType, url))
                .build();
    }

    private AgentContext buildLongReasonContext() {
        return AgentContext.builder()
                .taskId(8L)
                .taskName("task")
                .currentNodeName("collect_sources_01_04")
                .currentNodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "competitorUrls": ["https://example.com/docs"],
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
                              "discoveryMethod": "DIRECT_LOCATOR",
                              "providerKey": "planned",
                              "reason": "%s",
                              "domain": "example.com",
                              "sourceUrls": ["https://example.com/docs"],
                              "selectionStage": "PLANNED",
                              "selectionReason": "配置提供"
                            }
                          ]
                        }
                        """.formatted("R".repeat(900)))
                .build();
    }

    private SourceCollector.CollectedPage successfulCollectedPage(String url, String title) {
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .title(title)
                .content("useful docs content for " + title)
                .snippet(title + " snippet")
                .competitorName("Feishu")
                .sourceType("DOCS")
                .success(true)
                .build();
    }

    private SourceCandidate buildSourceCandidate(String url) {
        return SourceCandidate.builder()
                .url(url)
                .title("Docs")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .reason("test")
                .domain("example.com")
                .sourceUrls(List.of(url))
                .selectionStage("PLANNED")
                .selectionReason("test")
                .build();
    }

    private CollectionExecutionResult buildSuccessfulCollectionResult(String taskPackageKey,
                                                                      int targetIndex,
                                                                      String url,
                                                                      String title) {
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackageKey)
                .targetIndex(targetIndex)
                .executorType("WEB_PAGE")
                .success(true)
                .status("SUCCESS")
                .resourceLocator(url)
                .title(title)
                .content("useful docs content with enough body for " + title)
                .sourceUrls(List.of(url))
                .discoveryDepth(targetIndex == 1 ? 0 : 1)
                .qualitySignals(List.of("FULL_RENDER_READY"))
                .qualityScore(0.80D)
                .build()
                .normalize();
    }

    private JsonNode findCollectionAuditResult(JsonNode output, String resourceLocator) {
        for (JsonNode result : output.path("collectionAudit").path("results")) {
            if (resourceLocator.equals(result.path("resourceLocator").asText())) {
                return result;
            }
        }
        throw new AssertionError("missing collectionAudit result for " + resourceLocator);
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
