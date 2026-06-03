package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.CandidateVerifier;
import cn.bugstack.competitoragent.search.CollectionTargetSelector;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private final BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
    private final SearchSourceProvider searchSourceProvider = mock(SearchSourceProvider.class);
    private final SearchExecutionCoordinator searchExecutionCoordinator = new SearchExecutionCoordinator(
            new CandidateVerifier(sourceCollector),
            browserSearchRuntimeService,
            searchSourceProvider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector()
    );
    private final CollectorAgent collectorAgent = new CollectorAgent(
            logRepository,
            sourceCollector,
            evidenceRepository,
            nodeRepository,
            searchExecutionCoordinator,
            objectMapper
    );

    @Test
    void shouldFailWhenAllCollectedPagesAreUnusable() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(sourceCollector.collect(any(), any(), any())).thenReturn(SourceCollector.CollectedPage.builder()
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
        when(sourceCollector.collect("https://example.com/docs", "Feishu", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://example.com/docs")
                        .title("Docs")
                        .content("useful docs content with api reference")
                        .snippet("api reference")
                        .competitorName("Feishu")
                        .sourceType("DOCS")
                        .success(true)
                        .build());
        when(sourceCollector.collect("https://example.com/help", "Feishu", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
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
        when(sourceCollector.collect("https://example.com/docs", "Feishu", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://example.com/docs")
                        .title("Docs")
                        .content("useful docs content with api reference")
                        .snippet("api reference")
                        .competitorName("Feishu")
                        .sourceType("DOCS")
                        .success(true)
                        .build());
        when(sourceCollector.collect("https://example.com/help", "Feishu", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
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
        when(sourceCollector.collect("https://example.com/docs", "Feishu", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
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
}
