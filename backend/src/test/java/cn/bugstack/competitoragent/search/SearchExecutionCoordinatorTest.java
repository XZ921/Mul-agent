package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
            new CollectionTargetSelector(),
            new SearchPolicyResolver()
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
    void shouldTriggerBrowserSupplementInBrowserOnlyModeWhenPlannedCandidatesFailVerification() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://docs.notion.ai/reference")
                        .title("Notion Browser Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("BROWSER")
                        .reason("浏览器补源候选")
                        .domain("docs.notion.ai")
                        .searchQuery("Notion AI documentation")
                        .searchEngine("bing")
                        .resultRank(1)
                        .browserTraceId("trace-browser-002")
                        .relevanceScore(0.95)
                        .freshnessScore(0.63)
                        .qualityScore(0.92)
                        .build()))
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("browser search returned one candidate")
                .fallbackSuggested(false)
                .browserTraceId("trace-browser-002")
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
                        .errorMessage("planned page unavailable")
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
                        .relevanceScore(0.90)
                        .freshnessScore(0.70)
                        .qualityScore(0.88)
                        .build()))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("BROWSER_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("https://docs.notion.ai/reference", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("BROWSER", result.getExecutionTrace().getSupplementMethod());
        assertEquals("USE_BROWSER_SUPPLEMENT", result.getExecutionTrace().getFallbackDecision());
        verify(browserSearchRuntimeService).search(any());
        verify(searchSourceProvider, never()).search(any(), any());
    }

    @Test
    void shouldVerifyCandidatesBeforeSelectingTargetsAndSkipSupplementWhenVerifiedCandidatesAreEnough() {
        CandidateVerifier candidateVerifier = mock(CandidateVerifier.class);
        BrowserSearchRuntimeService browserRuntimeService = mock(BrowserSearchRuntimeService.class);
        SearchSourceProvider sourceProvider = mock(SearchSourceProvider.class);
        CollectionTargetSelector targetSelector = spy(new CollectionTargetSelector());
        SearchExecutionCoordinator searchCoordinator = new SearchExecutionCoordinator(
                candidateVerifier,
                browserRuntimeService,
                sourceProvider,
                new SourceCandidateRanker(),
                targetSelector,
                new SearchPolicyResolver()
        );
        SourceCandidate plannedCandidate = SourceCandidate.builder()
                .url("https://planned.example.com/docs")
                .title("Planned Docs")
                .sourceType("DOCS")
                .discoveryMethod("HEURISTIC")
                .reason("规划期候选")
                .domain("planned.example.com")
                .relevanceScore(0.90)
                .freshnessScore(0.70)
                .qualityScore(0.88)
                .build();
        SourceCandidate verifiedCandidate = plannedCandidate.toBuilder()
                .verified(Boolean.TRUE)
                .selectionStage("VERIFIED")
                .selectionReason("运行期验证通过，允许直接进入正式采集")
                .build();
        SearchCollectionTarget verifiedTarget = SearchCollectionTarget.builder()
                .candidate(verifiedCandidate)
                .build();
        when(candidateVerifier.verify(eq("Notion AI"), eq("DOCS"), any()))
                .thenReturn(CandidateVerificationResult.builder()
                        .updatedCandidates(List.of(verifiedCandidate))
                        .attemptedTargets(List.of(verifiedTarget))
                        .verifiedTargets(List.of(verifiedTarget))
                        .build());

        SearchExecutionResult result = searchCoordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(plannedCandidate))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        InOrder inOrder = inOrder(candidateVerifier, targetSelector);
        inOrder.verify(candidateVerifier).verify(eq("Notion AI"), eq("DOCS"), any());
        inOrder.verify(targetSelector).selectTargets(any(), any(), anyInt());
        verify(browserRuntimeService, never()).search(any());
        assertEquals("SKIP_SUPPLEMENT_ENOUGH_VERIFIED", result.getExecutionTrace().getFallbackDecision());
        assertEquals("https://planned.example.com/docs", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(Boolean.TRUE, result.getSelectedTargets().get(0).getCandidate().getVerified());
    }

    @Test
    void shouldSkipPublicSearchSupplementWhenOfficialDirectCandidatesAlreadyVerified() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(sourceCollector.collect("https://www.acme.ai/docs", "Acme AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.acme.ai/docs")
                        .title("Acme Docs")
                        .content("documentation api reference guide")
                        .snippet("api reference guide")
                        .competitorName("Acme AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Acme AI")
                .sourceType("DOCS")
                .competitorUrls(List.of("https://www.acme.ai"))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        verify(searchSourceProvider, never()).search(any(), any());
        assertEquals("SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH",
                result.getExecutionTrace().getFallbackDecision());
    }

    @Test
    void shouldCarryAttemptedDiscardedAndTimelineIntoExecutionResultAndAudit() {
        CandidateVerifier candidateVerifier = mock(CandidateVerifier.class);
        BrowserSearchRuntimeService browserRuntimeService = mock(BrowserSearchRuntimeService.class);
        SearchSourceProvider sourceProvider = mock(SearchSourceProvider.class);
        SearchExecutionCoordinator searchCoordinator = new SearchExecutionCoordinator(
                candidateVerifier,
                browserRuntimeService,
                sourceProvider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
        SourceCandidate plannedDoc = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .title("Reference")
                .sourceType("DOCS")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .relevanceScore(0.9)
                .freshnessScore(0.8)
                .qualityScore(0.9)
                .build();
        SourceCandidate utilityPage = SourceCandidate.builder()
                .url("https://www.example.com/login")
                .title("Login")
                .sourceType("DOCS")
                .selectionStage("DISCARDED")
                .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
                .relevanceScore(0.7)
                .freshnessScore(0.6)
                .qualityScore(0.1)
                .build();
        SearchCollectionTarget attemptedDoc = SearchCollectionTarget.builder()
                .candidate(plannedDoc)
                .build();

        when(candidateVerifier.verify(eq("Example"), eq("DOCS"), any()))
                .thenReturn(CandidateVerificationResult.builder()
                        .updatedCandidates(List.of(plannedDoc, utilityPage))
                        .attemptedTargets(List.of(attemptedDoc))
                        .verifiedTargets(List.of(attemptedDoc))
                        .build());

        SearchExecutionResult result = searchCoordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Example")
                .sourceType("DOCS")
                .sourceCandidates(List.of(plannedDoc, utilityPage))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getDiscardedCandidates().size());
        assertEquals("https://example.com/login", result.getDiscardedCandidates().get(0).getUrl());
        assertFalse(result.getReplayTimeline().isEmpty());
        assertEquals("SELECT_TARGETS", result.getReplayTimeline().get(result.getReplayTimeline().size() - 1).getStepCode());
        assertEquals(1, result.getExecutionTrace().getAttemptedCandidateCount());
        assertEquals(1, result.getExecutionTrace().getDiscardedCandidateCount());
        assertEquals(1, result.getAuditSnapshot().getAttemptedTargets().size());
        assertEquals(1, result.getAuditSnapshot().getDiscardedCandidates().size());
        assertFalse(result.getAuditSnapshot().getReplayTimeline().isEmpty());
    }

    @Test
    void shouldKeepPlannedCandidatesWhenRuntimeSupplementReturnsEmpty() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("browser search returned empty")
                .fallbackSuggested(true)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());

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
                .searchMode("HYBRID")
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        SearchExecutionStep supplementStep = result.getExecutionPlan().getSteps().stream()
                .filter(step -> "BROWSER_SUPPLEMENT_SEARCH".equals(step.getStepCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(SearchExecutionStep.StepStatus.SUCCESS, supplementStep.getStatus());
        assertTrue(supplementStep.getMessage().contains("回退到规划期候选"));
        assertEquals(1, result.getSelectedTargets().size());
        assertEquals("https://planned.example.com/docs", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("NO_NEW_CANDIDATES_KEEP_PLANNED", result.getExecutionTrace().getFallbackDecision());
        assertEquals("NONE", result.getExecutionTrace().getSupplementMethod());
        verify(browserSearchRuntimeService).search(any());
        verify(searchSourceProvider).search(any(), any());
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
    void shouldExpandSearchResultRootThroughDirectDiscoveryTemplates() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://www.bilibili.com")
                        .title("Bilibili")
                        .sourceType("OFFICIAL")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP 搜索补源")
                        .domain("www.bilibili.com")
                        .sourceUrls(List.of("https://www.bilibili.com"))
                        .relevanceScore(0.90)
                        .freshnessScore(0.60)
                        .qualityScore(0.86)
                        .build()
        ));

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(5)
                .minVerifiedCandidates(1)
                .build());

        assertTrue(result.getSourceCandidates().stream()
                .anyMatch(candidate -> "SEARCH_ROOT_TEMPLATE".equals(candidate.getDiscoveryMethod())));
        assertTrue(result.getSourceCandidates().stream()
                .anyMatch(candidate -> "https://open.bilibili.com".equals(candidate.getUrl())
                        && candidate.getSourceUrls() != null
                        && candidate.getSourceUrls().contains("https://www.bilibili.com")));
    }

    @Test
    void shouldExpandDouyinSearchResultRootToOpenPlatformDocsEntry() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://www.douyin.com")
                        .title("抖音")
                        .sourceType("OFFICIAL")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP 搜索补源命中抖音根域")
                        .domain("www.douyin.com")
                        .sourceUrls(List.of("https://www.douyin.com"))
                        .relevanceScore(0.90)
                        .freshnessScore(0.60)
                        .qualityScore(0.86)
                        .build()
        ));

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("DOCS")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(5)
                .minVerifiedCandidates(1)
                .build());

        assertTrue(result.getSourceCandidates().stream()
                .anyMatch(candidate -> "https://open.douyin.com".equals(candidate.getUrl())
                        && "SEARCH_ROOT_TEMPLATE".equals(candidate.getDiscoveryMethod())
                        && candidate.getSourceUrls() != null
                        && candidate.getSourceUrls().contains("https://www.douyin.com")));
        assertTrue(result.getSelectedTargets().stream()
                .anyMatch(target -> target.getCandidate() != null
                        && "https://open.douyin.com".equals(target.getCandidate().getUrl())));
    }

    @Test
    void shouldAppendSitemapDiscoveredCandidatesBeforeFinalSelection() {
        SitemapDiscoveryService sitemapDiscoveryService = mock(SitemapDiscoveryService.class);
        SearchExecutionCoordinator searchCoordinator = new SearchExecutionCoordinator(
                new CandidateVerifier(sourceCollector),
                browserSearchRuntimeService,
                searchSourceProvider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver(),
                new CanonicalUrlResolver(),
                sitemapDiscoveryService
        );
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(sitemapDiscoveryService.discover(eq("Acme AI"), eq("DOCS"), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://acme.ai/docs/getting-started")
                        .title("Acme Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SITEMAP_DISCOVERY")
                        .reason("sitemap discovered docs entry")
                        .domain("acme.ai")
                        .sourceUrls(List.of("https://acme.ai/sitemap.xml"))
                        .relevanceScore(0.93)
                        .freshnessScore(0.60)
                        .qualityScore(0.90)
                        .build()
        ));

        SearchExecutionResult result = searchCoordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Acme AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://acme.ai/platform")
                        .title("Acme Platform")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("planned entry")
                        .domain("acme.ai")
                        .relevanceScore(0.84)
                        .freshnessScore(0.62)
                        .qualityScore(0.82)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        assertTrue(result.getSourceCandidates().stream()
                .anyMatch(candidate -> "https://acme.ai/docs/getting-started".equals(candidate.getUrl())
                        && "SITEMAP_DISCOVERY".equals(candidate.getDiscoveryMethod())
                        && candidate.getSourceUrls() != null
                        && candidate.getSourceUrls().contains("https://acme.ai/sitemap.xml")));
        verify(sitemapDiscoveryService).discover(eq("Acme AI"), eq("DOCS"),
                argThat(rootUrls -> rootUrls != null && rootUrls.contains("https://acme.ai")));
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
                .failureKind("ANTI_BOT_BLOCKED")
                .fallbackAction("HTTP_FALLBACK")
                .matchedSignals(List.of("body:captcha", "title:access denied"))
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
        assertTrue(result.getSelectedTargets().stream()
                .anyMatch(target -> target.getCandidate() != null
                        && List.of("https://http.example.com/docs", "https://http.example.com/documentation")
                        .contains(target.getCandidate().getUrl())));
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

    @Test
    void shouldRespectConfiguredFallbackOrderBeforeTriggeringBrowserSupplement() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://docs.notion.so/api")
                        .title("HTTP Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP 搜索补源")
                        .domain("docs.notion.so")
                        .relevanceScore(0.93)
                        .freshnessScore(0.66)
                        .qualityScore(0.91)
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
                        .relevanceScore(0.83)
                        .freshnessScore(0.61)
                        .qualityScore(0.80)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("PLANNED", "HTTP", "BROWSER"))
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        assertEquals("HTTP_FALLBACK", result.getExecutionTrace().getSupplementMethod());
        assertEquals("USE_HTTP_FALLBACK", result.getExecutionTrace().getFallbackDecision());
        assertTrue(result.getSelectedTargets().stream()
                .anyMatch(target -> "https://docs.notion.so/api".equals(target.getCandidate().getUrl())));
        verify(browserSearchRuntimeService, never()).search(any());
    }

    @Test
    void shouldExposeSearchQueriesAndFallbackOrderInExecutionPlan() {
        List<String> searchQueries = List.of(
                "Notion AI documentation api reference",
                "site:docs.notion.so Notion AI"
        );
        List<String> fallbackOrder = List.of("PLANNED", "HTTP");

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://docs.notion.so/guide")
                        .title("Planned Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("规划期候选")
                        .domain("docs.notion.so")
                        .relevanceScore(0.90)
                        .freshnessScore(0.68)
                        .qualityScore(0.90)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .searchQueries(searchQueries)
                .searchFallbackOrder(fallbackOrder)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertIterableEquals(searchQueries, result.getExecutionPlan().getSearchQueries());
        assertIterableEquals(fallbackOrder, result.getExecutionPlan().getFallbackOrder());
        assertEquals(1, result.getExecutionPlan().getTargetCount());
        assertEquals(1, result.getExecutionPlan().getMinVerifiedCount());
    }

    @Test
    void shouldProjectBrowserDiagnosticFieldsIntoExecutionTrace() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("blocked by captcha")
                .fallbackSuggested(true)
                .failureKind("ANTI_BOT_BLOCKED")
                .fallbackAction("HTTP_FALLBACK")
                .matchedSignals(List.of("body:captcha", "title:access denied"))
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

        assertEquals("ANTI_BOT_BLOCKED", result.getExecutionTrace().getBrowserFailureKind());
        assertEquals("HTTP_FALLBACK", result.getExecutionTrace().getBrowserFallbackAction());
        assertIterableEquals(List.of("body:captcha", "title:access denied"),
                result.getExecutionTrace().getBrowserMatchedSignals());
    }

    @Test
    void shouldDeduplicateCanonicalCandidatesAcrossPlannedAndSupplementSources() {
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://example.com/docs")
                        .title("Canonical Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("SEARCH")
                        .reason("HTTP supplement")
                        .domain("example.com")
                        .relevanceScore(0.93)
                        .freshnessScore(0.70)
                        .qualityScore(0.90)
                        .build()
        ));

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Example")
                .sourceType("DOCS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("http://www.example.com/docs?utm_source=campaign")
                        .title("Planned Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("HEURISTIC")
                        .reason("planned")
                        .domain("www.example.com")
                        .relevanceScore(0.89)
                        .freshnessScore(0.60)
                        .qualityScore(0.82)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HTTP_ONLY")
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());

        assertEquals(1, result.getSelectedTargets().size());
        assertEquals("https://example.com/docs", result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(List.of("https://example.com/docs"), result.getExecutionTrace().getSelectedUrls());
        assertEquals(1, result.getSourceCandidates().stream()
                .map(SourceCandidate::getUrl)
                .distinct()
                .count());
    }

    @Test
    void shouldPreserveExplicitRssFeedUrlWhenSelectingNewsCandidate() {
        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("RSS Smoke")
                .sourceType("NEWS")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("http://127.0.0.1:18080/feed.xml")
                        .title("Mul-agent RSS Smoke Feed")
                        .sourceType("NEWS")
                        .discoveryMethod("CONFIG")
                        .reason("RSS live smoke")
                        .domain("127.0.0.1")
                        .sourceUrls(List.of("http://127.0.0.1:18080/feed.xml"))
                        .relevanceScore(0.95)
                        .freshnessScore(0.80)
                        .qualityScore(0.90)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .verifyResultPage(Boolean.FALSE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HEURISTIC_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .build());

        assertEquals(1, result.getSelectedTargets().size());
        assertEquals("http://127.0.0.1:18080/feed.xml",
                result.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(List.of("http://127.0.0.1:18080/feed.xml"),
                result.getExecutionTrace().getSelectedUrls());
        assertTrue(result.getSourceCandidates().stream()
                .anyMatch(candidate -> "http://127.0.0.1:18080/feed.xml".equals(candidate.getUrl())));
        verify(browserSearchRuntimeService, never()).search(any());
        verify(searchSourceProvider, never()).search(any(), any());
    }

    @Test
    void shouldRejectMediatorSearchCandidateAndAvoidDirectExpansionSelection() {
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .sourceType("OFFICIAL")
                        .discoveryMethod("BROWSER")
                        .reason("浏览器搜索命中官网认证页")
                        .domain("aiqicha.baidu.com")
                        .relevanceScore(0.95)
                        .freshnessScore(0.55)
                        .qualityScore(0.88)
                        .build()))
                .executedQueries(List.of("哔哩哔哩 官方网站"))
                .searchEngine("baidu")
                .summary("browser returned mediator page")
                .fallbackSuggested(false)
                .build());
        when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
        when(sourceCollector.collect("https://aiqicha.baidu.com/feedback/official", "哔哩哔哩", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .content("官网认证是百度对网站展示官方标识的增值服务认证。产品介绍 官网认证可以帮助网民识别官方站点。")
                        .snippet("官网认证 产品介绍")
                        .competitorName("哔哩哔哩")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("OFFICIAL")
                .sourceCandidates(List.of())
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .searchMode("HYBRID")
                .searchQueries(List.of("哔哩哔哩 官方网站"))
                .searchFallbackOrder(List.of("PLANNED", "BROWSER", "HTTP"))
                .maxSearchResults(3)
                .minVerifiedCandidates(1)
                .build());

        assertEquals(0, result.getSelectedTargets().size());
        assertTrue(result.getSourceCandidates().stream()
                .noneMatch(candidate -> candidate.getUrl() != null
                        && candidate.getUrl().contains("aiqicha.baidu.com/docs")));
        assertTrue(result.getDiscardedCandidates().stream()
                .anyMatch(candidate -> candidate.getUrl() != null
                        && candidate.getUrl().contains("aiqicha.baidu.com/feedback/official")));
    }
}
