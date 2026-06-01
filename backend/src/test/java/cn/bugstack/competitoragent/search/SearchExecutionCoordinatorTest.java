package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchExecutionCoordinatorTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
    private final SearchSourceProvider searchSourceProvider = mock(SearchSourceProvider.class);
    private final SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
            new CandidateVerifier(sourceCollector),
            browserSearchRuntimeService,
            searchSourceProvider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector()
    );

    @Test
    void shouldVerifyPlannedCandidateAndSelectItDirectly() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("mock browser search disabled")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(sourceCollector.collect("https://docs.example.com/guide", "Notion AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/guide")
                        .title("Documentation Guide")
                        .content("API reference and help guide for the product.")
                        .snippet("API reference and help guide")
                        .competitorName("Notion AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/guide")
                        .title("Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("规划期候选")
                        .domain("docs.example.com")
                        .relevanceScore(0.9)
                        .freshnessScore(0.7)
                        .qualityScore(0.9)
                        .build()))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals(1, result.getSelectedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getSelectedTargets().get(0).getCandidate().getVerified()));
        assertFalse(result.getExecutionPlan().getSteps().isEmpty());
        assertNotNull(result.getProgressSnapshot());
        assertFalse(result.getProgressSnapshots().isEmpty());
        assertTrue(result.getReasoningSummary().contains("最终选中 1 条"));
        assertNotNull(result.getExecutionTrace());
        assertEquals("v1", result.getExecutionTrace().getTraceVersion());
        assertEquals("SKIP_SUPPLEMENT_ENOUGH_VERIFIED", result.getExecutionTrace().getFallbackDecision());
        assertNotNull(result.getAuditSnapshot());
    }

    @Test
    void shouldUseBrowserRuntimeCandidatesBeforeProviderFallback() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://docs.notion.ai/reference")
                        .title("Notion Browser Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("BROWSER")
                        .reason("浏览器搜索候选")
                        .domain("docs.notion.ai")
                        .searchQuery("Notion AI documentation")
                        .searchEngine("bing")
                        .resultRank(1)
                        .browserTraceId("trace-browser-001")
                        .relevanceScore(0.95)
                        .freshnessScore(0.6)
                        .qualityScore(0.92)
                        .build()))
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("browser search returned one candidate")
                .fallbackSuggested(false)
                .browserTraceId("trace-browser-001")
                .build());
        when(sourceCollector.collect("https://planned.example.com/docs", "Notion AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://planned.example.com/docs")
                        .title("Planned Docs")
                        .content("")
                        .snippet("")
                        .competitorName("Notion AI")
                        .sourceType("DOCS")
                        .success(false)
                        .errorMessage("not usable")
                        .build());
        when(sourceCollector.collect("https://docs.notion.ai/reference", "Notion AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://docs.notion.ai/reference")
                        .title("Reference")
                        .content("documentation api reference guide")
                        .snippet("api reference")
                        .competitorName("Notion AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://planned.example.com/docs")
                        .title("Planned Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("规划期候选")
                        .domain("planned.example.com")
                        .relevanceScore(0.9)
                        .freshnessScore(0.7)
                        .qualityScore(0.9)
                        .build()))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("https://docs.notion.ai/reference", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("BROWSER", result.getExecutionTrace().getSupplementMethod());
        assertEquals("USE_BROWSER_SUPPLEMENT", result.getExecutionTrace().getFallbackDecision());
        assertEquals("trace-browser-001", result.getExecutionTrace().getBrowserTraceId());
        assertEquals("trace-browser-001", result.getSelectedTargets().get(0).getCandidate().getBrowserTraceId());
    }

    @Test
    void shouldSkipSupplementWhenSearchTimeoutExceeded() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(0L)
                .build());

        assertTrue(result.getSelectedTargets().isEmpty());
        assertEquals("TIMEOUT_FALLBACK", result.getExecutionTrace().getSupplementMethod());
        assertEquals(Boolean.TRUE, result.getExecutionTrace().getCircuitBroken());
        assertEquals("DEGRADED", result.getProgressSnapshot().getStatus());
        verify(browserSearchRuntimeService, never()).search(any());
    }

    @Test
    void shouldRecordBlockedReasonAndRecoveryAdviceWhenBrowserSearchBlocked() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("blocked by captcha")
                .fallbackSuggested(true)
                .blockedReason("captcha")
                .blockedCount(1)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("captcha", result.getExecutionTrace().getBrowserBlockedReason());
        assertEquals(1, result.getExecutionTrace().getBrowserBlockedCount());
        assertTrue(result.getExecutionTrace().getRecoveryAdvice().contains("反爬"));
    }

    @Test
    void shouldReuseCheckpointCandidatesWhenAuditCheckpointProvided() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("unused because checkpoint supplied")
                .fallbackSuggested(false)
                .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchAuditCheckpoint(SearchAuditSnapshot.builder()
                        .executionTrace(SearchExecutionTrace.builder()
                                .traceVersion("v1")
                                .recoveryCheckpoint("SELECT_TARGETS")
                                .build())
                        .sourceCandidates(List.of(SourceCandidate.builder()
                                .url("https://checkpoint.example.com/docs")
                                .title("Checkpoint Docs")
                                .sourceType("DOCS")
                                .discoveryMethod("BROWSER")
                                .selectionStage("SELECTED")
                                .selectionReason("来自上次检查点")
                                .relevanceScore(0.9)
                                .freshnessScore(0.7)
                                .qualityScore(0.8)
                                .build()))
                        .selectedTargets(List.of(SearchCollectionTarget.builder()
                                .candidate(SourceCandidate.builder()
                                        .url("https://checkpoint.example.com/docs")
                                        .title("Checkpoint Docs")
                                        .sourceType("DOCS")
                                        .selectionStage("SELECTED")
                                        .selectionReason("来自上次检查点")
                                        .relevanceScore(0.9)
                                        .freshnessScore(0.7)
                                        .qualityScore(0.8)
                                        .build())
                                .build()))
                        .build())
                .build());

        assertEquals("https://checkpoint.example.com/docs", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(Boolean.TRUE, result.getExecutionTrace().getResumedFromCheckpoint());
        assertEquals("NODE_CONFIG_CHECKPOINT", result.getExecutionTrace().getCheckpointSource());
    }

    @Test
    void shouldUseHttpFallbackDirectlyWhenBrowserRuntimeSearchDisabled() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://http.example.com/docs")
                        .title("HTTP Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP 搜索补源")
                        .domain("http.example.com")
                        .relevanceScore(0.91)
                        .freshnessScore(0.62)
                        .qualityScore(0.87)
                        .build()
        ));

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("HTTP_FALLBACK", result.getExecutionTrace().getSupplementMethod());
        assertEquals("BROWSER_DISABLED_USE_HTTP_FALLBACK", result.getExecutionTrace().getFallbackDecision());
        assertEquals("https://http.example.com/docs", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNull(result.getExecutionTrace().getBrowserTraceId());
        verify(browserSearchRuntimeService, never()).search(any());
    }

    @Test
    void shouldSkipVerificationWhenResultPageVerificationDisabled() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://supplement.example.com/docs")
                        .title("Supplement Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP 搜索补源")
                        .domain("supplement.example.com")
                        .relevanceScore(0.88)
                        .freshnessScore(0.60)
                        .qualityScore(0.84)
                        .build()
        ));

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://planned.example.com/docs")
                        .title("Planned Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("规划期候选")
                        .domain("planned.example.com")
                        .relevanceScore(0.90)
                        .freshnessScore(0.70)
                        .qualityScore(0.88)
                        .build()))
                .verifyCandidates(Boolean.TRUE)
                .verifyResultPage(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        SearchExecutionStep verifyStep = result.getExecutionPlan().getSteps().stream()
                .filter(step -> "VERIFY_TOP_CANDIDATES".equals(step.getStepCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(SearchExecutionStep.StepStatus.SKIPPED, verifyStep.getStatus());
        assertTrue(verifyStep.getMessage().contains("关闭结果页验证"));
        assertEquals(0, result.getExecutionTrace().getVerifiedCandidateCount());
        assertEquals("HTTP_FALLBACK", result.getExecutionTrace().getSupplementMethod());
        assertEquals(2, result.getSelectedTargets().size());
        assertTrue(result.getSelectedTargets().stream()
                .anyMatch(target -> "https://supplement.example.com/docs".equals(target.getCandidate().getUrl())));
        verify(browserSearchRuntimeService, never()).search(any());
        verify(sourceCollector, never()).collect(any(), any(), any());
    }

    @Test
    void shouldKeepPlannedCandidatesWhenSearchModeIsBrowserOnlyAndBrowserReturnsEmpty() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("browser search returned empty")
                .fallbackSuggested(true)
                .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://planned.example.com/docs")
                        .title("Planned Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("规划期候选")
                        .domain("planned.example.com")
                        .relevanceScore(0.90)
                        .freshnessScore(0.70)
                        .qualityScore(0.88)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("BROWSER_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("SKIP_SUPPLEMENT_ENOUGH_VERIFIED", result.getExecutionTrace().getFallbackDecision());
        assertEquals("NONE", result.getExecutionTrace().getSupplementMethod());
        verify(searchSourceProvider, never()).search(any(), any());
        verify(browserSearchRuntimeService, never()).search(any());
    }
}
