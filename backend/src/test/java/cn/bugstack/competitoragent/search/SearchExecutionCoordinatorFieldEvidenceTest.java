package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SearchSourceProviderDescriptor;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverage;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverageStatus;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchExecutionCoordinatorFieldEvidenceTest {

    @Test
    void shouldPassDimensionEvidenceQueriesToSearchProvider() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider, false);

        coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        assertThat(provider.requests).hasSize(1);
        SearchSourceRequest request = provider.requests.get(0);
        assertThat(request.getFieldEvidenceQueries()).hasSize(1);
        assertThat(request.getFieldEvidenceQueries().get(0).getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
    }

    @Test
    void shouldOpenHttpSupplementWhenVerifiedCandidateExistsButFieldBudgetHasPendingQueries() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(provider, true);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceCandidates(List.of(weakEntryCandidate()))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        List<SearchSourceRequest> supplementRequests = supplementRequests(provider);
        assertThat(supplementRequests).hasSize(1);
        assertThat(supplementRequests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP_FALLBACK");
    }

    @Test
    void shouldContinueToHttpSupplementWhenHybridBrowserStageAlreadyMeetsTargetButFieldQueriesPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                browserResult(
                        browserCandidate("https://docs.bilibili.com/product/guide"),
                        browserCandidate("https://open.bilibili.com/platform/overview")
                ),
                Set.of()
        );

        coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("BROWSER", "HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(true)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        List<SearchSourceRequest> supplementRequests = supplementRequests(provider);
        assertThat(supplementRequests).hasSize(1);
        assertThat(supplementRequests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
    }

    @Test
    void shouldRunHttpFieldSupplementBeforeBrowserWhenFieldQueriesPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                browserResult(
                        browserCandidate("https://docs.bilibili.com/product/guide"),
                        browserCandidate("https://open.bilibili.com/platform/overview")
                ),
                Set.of()
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("BROWSER", "HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(true)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        assertThat(provider.requests)
                .extracting(SearchSourceRequest::getRequestPhase)
                .containsExactly(SearchRequestPhase.SUPPLEMENT);
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP_FALLBACK");
    }

    @Test
    void shouldSkipBrowserSupplementAfterHttpFieldQueryReturnsCandidates() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                browserResult(
                        browserCandidate("https://docs.bilibili.com/product/guide"),
                        browserCandidate("https://open.bilibili.com/platform/overview")
                ),
                Set.of()
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("BROWSER", "HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(true)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        assertThat(provider.requests)
                .extracting(SearchSourceRequest::getRequestPhase)
                .containsExactly(SearchRequestPhase.SUPPLEMENT);
        assertThat(result.getExecutionTrace().getBrowserExecutedQueries()).isEmpty();
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP_FALLBACK");
    }

    @Test
    void shouldNotSkipSupplementWhenTimedOutButFieldQueriesStillPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                browserResult(
                        browserCandidate("https://docs.bilibili.com/product/guide")
                ),
                Set.of()
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("BROWSER", "HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(true)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(0L)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        List<SearchSourceRequest> supplementRequests = supplementRequests(provider);
        assertThat(supplementRequests).hasSize(1);
        assertThat(supplementRequests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP_FALLBACK");
        assertThat(result.getExecutionTrace().getFallbackDecision())
                .isNotEqualTo("SKIP_SUPPLEMENT_AND_FALLBACK_PLANNED");
    }

    @Test
    void shouldRaiseTraceTimeoutBudgetWhenFieldQueriesArePending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(provider, false);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(18300L)
                .dimensionEvidencePlan(fieldPlanWithTwoQueries())
                .build());

        assertThat(result.getExecutionTrace().getSearchTimeoutMillis()).isEqualTo(24000L);
    }

    @Test
    void shouldKeepHybridBreakWhenBrowserStageAlreadyMeetsTargetAndNoFieldQueriesPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                browserResult(
                        browserCandidate("https://docs.bilibili.com/product/guide"),
                        browserCandidate("https://open.bilibili.com/platform/overview")
                ),
                Set.of()
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HYBRID")
                .searchFallbackOrder(List.of("BROWSER", "HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(true)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(completedFieldPlan())
                .build());

        assertThat(supplementRequests(provider)).isEmpty();
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("BROWSER");
    }

    @Test
    void shouldTriggerPublicRecoveryWhenVerifiedWeakEntryExistsButFieldEvidenceStillMissing() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(List.of(), List.of());
        SearchExecutionCoordinator coordinator = newCoordinator(provider, true);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceCandidates(List.of(weakEntryCandidate()))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .recoveryFieldName("coreFeatures")
                .recoveryEvidencePathKey("DOCS_API_GUIDE")
                .recoveryQueryIntents(List.of("API_DOCS"))
                .build());

        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryTriggered()).isTrue();
    }

    @Test
    void shouldNotTriggerPublicRecoveryWhenVerifiedFieldEvidenceCandidateAlreadyExists() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(provider, true);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceCandidates(List.of(weakEntryCandidate()))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .recoveryFieldName("coreFeatures")
                .recoveryEvidencePathKey("DOCS_API_GUIDE")
                .recoveryQueryIntents(List.of("API_DOCS"))
                .build());

        assertThat(result.getSourceCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.getUrl()).isEqualTo("https://open.bilibili.com/doc/4/feb66f99");
                    assertThat(candidate.getFieldName()).isEqualTo("coreFeatures");
                    assertThat(candidate.getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
                });
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryCount()).isEqualTo(1);
        assertThat(result.getExecutionTrace().getFieldEvidenceFields()).containsExactly("coreFeatures");
        assertThat(result.getExecutionTrace().getFieldEvidencePaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceQueryCount()).isEqualTo(1);
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceFields()).containsExactly("coreFeatures");
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidencePaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryTriggered()).isFalse();
    }

    @Test
    void shouldNotSkipSupplementForDirectDiscoveryWhenFieldQueriesPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                emptyBrowserResult(),
                Set.of("https://www.bilibili.com/docs")
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .competitorUrls(List.of("https://www.bilibili.com"))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build());

        List<SearchSourceRequest> supplementRequests = supplementRequests(provider);
        assertThat(supplementRequests).hasSize(1);
        assertThat(supplementRequests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getFallbackDecision())
                .isNotEqualTo("SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH");
    }

    @Test
    void shouldKeepSkippingSupplementForDirectDiscoveryWhenNoFieldQueriesPending() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider(
                List.of(),
                List.of(fieldEvidenceCandidate())
        );
        SearchExecutionCoordinator coordinator = newCoordinator(
                provider,
                emptyBrowserResult(),
                Set.of("https://www.bilibili.com/docs")
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .competitorUrls(List.of("https://www.bilibili.com"))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(completedFieldPlan())
                .build());

        assertThat(supplementRequests(provider)).isEmpty();
        assertThat(result.getExecutionTrace().getFallbackDecision())
                .isEqualTo("SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH");
    }

    /**
     * 保持旧测试入口不变，让既有场景仍然可以通过布尔开关快速构造“弱入口已验证”的上下文。
     */
    private SearchExecutionCoordinator newCoordinator(CapturingSearchSourceProvider provider,
                                                      boolean shallowEntryVerifies) {
        return newCoordinator(
                provider,
                emptyBrowserResult(),
                shallowEntryVerifies ? Set.of("https://app.bilibili.com") : Set.of()
        );
    }

    /**
     * 通过可注入的浏览器返回值和可验证 URL 集合，精确复现 fallback 顺序里的不同分支。
     */
    private SearchExecutionCoordinator newCoordinator(CapturingSearchSourceProvider provider,
                                                      BrowserSearchRuntimeResult browserResult,
                                                      Set<String> verifiedUrls) {
        BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
        when(browserSearchRuntimeService.search(any())).thenReturn(browserResult);
        return new SearchExecutionCoordinator(
                new CandidateVerifier(new SourceCollector() {
                    @Override
                    public CollectedPage collect(SourceCollectRequest request) {
                        String url = request == null ? null : request.getUrl();
                        if (url != null && verifiedUrls.contains(url)) {
                            return CollectedPage.builder()
                                    .url(url)
                                    .success(true)
                                    .title("哔哩哔哩 验证页面")
                                    .content("哔哩哔哩 官方 API 文档 接入 指南")
                                    .build();
                        }
                        return CollectedPage.builder()
                                .url(url)
                                .success(false)
                                .errorMessage("planned candidate unavailable")
                                .build();
                    }

                    @Override
                    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
                        return List.of();
                    }
                }),
                browserSearchRuntimeService,
                provider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
    }

    /**
     * pending query 场景对应 08 的核心验收前提：字段路径未满足且 planned query 仍待执行。
     */
    private DimensionEvidencePlan fieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(fieldEvidenceQuery()))
                        .build()))
                .build();
    }

    /**
     * 该计划用于证明旧行为不变：字段覆盖已经满足时，补源仍应保留原有 break/skip 语义。
     */
    private DimensionEvidencePlan completedFieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.SUFFICIENT)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of("DOCS_API_GUIDE"))
                        .plannedQueries(List.of(fieldEvidenceQuery()))
                        .build()))
                .build();
    }

    private DimensionEvidencePlan fieldPlanWithTwoQueries() {
        return DimensionEvidencePlan.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(
                                fieldEvidenceQuery(),
                                FieldEvidenceQuery.builder()
                                        .fieldName("coreFeatures")
                                        .evidencePathKey("DOCS_SDK_GUIDE")
                                        .queryIntent("SDK_GUIDE")
                                        .sourceType("DOCS")
                                        .query("site:open.bilibili.com SDK 文档")
                                        .queryFingerprint("q-core-2")
                                        .reason("核心功能 SDK 文档")
                                        .build()
                        ))
                        .build()))
                .build();
    }

    private FieldEvidenceQuery fieldEvidenceQuery() {
        return FieldEvidenceQuery.builder()
                .fieldName("coreFeatures")
                .evidencePathKey("DOCS_API_GUIDE")
                .queryIntent("API_DOCS")
                .sourceType("DOCS")
                .query("哔哩哔哩 开放平台 API 官方文档")
                .queryFingerprint("q-core-1")
                .reason("核心功能 API 文档")
                .build();
    }

    private SourceCandidate weakEntryCandidate() {
        return SourceCandidate.builder()
                .url("https://app.bilibili.com")
                .title("哔哩哔哩 App")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .reason("用户提供的弱入口")
                .domain("app.bilibili.com")
                .sourceUrls(List.of("https://app.bilibili.com"))
                .relevanceScore(0.90D)
                .freshnessScore(0.60D)
                .qualityScore(0.80D)
                .build();
    }

    private SourceCandidate fieldEvidenceCandidate() {
        return fieldEvidenceCandidateStatic();
    }

    private SourceCandidate browserCandidate(String url) {
        return SourceCandidate.builder()
                .url(url)
                .title("哔哩哔哩 浏览器候选")
                .sourceType("DOCS")
                .providerKey("browser")
                .discoveryMethod("BROWSER_SEARCH")
                .reason("浏览器补源候选")
                .domain(extractDomain(url))
                .sourceUrls(List.of(url))
                .relevanceScore(0.91D)
                .freshnessScore(0.62D)
                .qualityScore(0.88D)
                .build();
    }

    private BrowserSearchRuntimeResult emptyBrowserResult() {
        return BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser disabled")
                .fallbackSuggested(false)
                .build();
    }

    private BrowserSearchRuntimeResult browserResult(SourceCandidate... candidates) {
        return BrowserSearchRuntimeResult.builder()
                .candidates(List.of(candidates))
                .executedQueries(List.of("哔哩哔哩 官方网站"))
                .summary("browser found enough candidates")
                .fallbackSuggested(false)
                .build();
    }

    private List<SearchSourceRequest> supplementRequests(CapturingSearchSourceProvider provider) {
        return provider.requests.stream()
                .filter(request -> request.getRequestPhase() == SearchRequestPhase.SUPPLEMENT)
                .toList();
    }

    private String extractDomain(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.replaceFirst("^https?://", "");
        int slashIndex = normalized.indexOf('/');
        return slashIndex >= 0 ? normalized.substring(0, slashIndex) : normalized;
    }

    private static final class CapturingSearchSourceProvider implements SearchSourceProvider {

        private final List<SearchSourceRequest> requests = new ArrayList<>();
        private final List<SourceCandidate> bootstrapResults;
        private final List<SourceCandidate> supplementResults;

        private CapturingSearchSourceProvider() {
            this(List.of(), List.of(fieldEvidenceCandidateStatic()));
        }

        private CapturingSearchSourceProvider(List<SourceCandidate> bootstrapResults,
                                              List<SourceCandidate> supplementResults) {
            this.bootstrapResults = bootstrapResults == null ? List.of() : bootstrapResults;
            this.supplementResults = supplementResults == null ? List.of() : supplementResults;
        }

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey("tavily")
                    .displayName("test")
                    .capabilities(List.of())
                    .defaultEnabled(true)
                    .defaultFailOpen(true)
                    .build();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<SourceCandidate> search(SearchSourceRequest request) {
            requests.add(request);
            if (request != null && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP) {
                return bootstrapResults;
            }
            return supplementResults;
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            return search(SearchSourceRequest.builder()
                    .competitorName(competitorName)
                    .requestedScopes(requestedScopes)
                    .build());
        }
    }

    private static SourceCandidate fieldEvidenceCandidateStatic() {
        return SourceCandidate.builder()
                .url("https://open.bilibili.com/doc/4/feb66f99")
                .sourceType("DOCS")
                .providerKey("tavily")
                .fieldName("coreFeatures")
                .evidencePathKey("DOCS_API_GUIDE")
                .queryIntent("API_DOCS")
                .fieldEvidenceQueryFingerprint("q-core-1")
                .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .pageType("OFFICIAL_DOC")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .skipNetworkVerification(Boolean.TRUE)
                .verified(Boolean.TRUE)
                .build();
    }
}
