package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
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

        List<SearchSourceRequest> supplementRequests = provider.requests.stream()
                .filter(request -> request.getRequestPhase() == cn.bugstack.competitoragent.source.SearchRequestPhase.SUPPLEMENT)
                .toList();
        assertThat(supplementRequests).hasSize(1);
        assertThat(supplementRequests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP_FALLBACK");
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

    private SearchExecutionCoordinator newCoordinator(CapturingSearchSourceProvider provider,
                                                      boolean shallowEntryVerifies) {
        BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser disabled")
                .fallbackSuggested(false)
                .build());
        return new SearchExecutionCoordinator(
                new CandidateVerifier(new SourceCollector() {
                    @Override
                    public CollectedPage collect(SourceCollectRequest request) {
                        String url = request == null ? null : request.getUrl();
                        if (shallowEntryVerifies && "https://app.bilibili.com".equals(url)) {
                            return CollectedPage.builder()
                                    .url(url)
                                    .success(true)
                                    .title("哔哩哔哩 App")
                                    .content("哔哩哔哩 官方 App 下载中心 API 文档 接入指南")
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

    private DimensionEvidencePlan fieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("哔哩哔哩 开放平台 API 官方文档")
                                .queryFingerprint("q-core-1")
                                .reason("核心功能 API 文档")
                                .build()))
                        .build()))
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
            if (request != null
                    && request.getRequestPhase() == cn.bugstack.competitoragent.source.SearchRequestPhase.BOOTSTRAP) {
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
