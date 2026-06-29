package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchExecutionCoordinatorPublicRecoveryTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
    private final SearchSourceProvider searchSourceProvider = mock(SearchSourceProvider.class);

    @Test
    void shouldRecoverVerifiedPublicPricingEvidenceBeforeSelectingTarget() {
        CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
                candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
        when(sourceCollector.collect(any(String.class), eq("Example Cloud"), eq("PRICING")))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0, String.class);
                    if ("https://example.com/login".equals(url)) {
                        return SourceCollector.CollectedPage.builder()
                                .url(url)
                                .title("Login")
                                .content("Please login to continue.")
                                .success(true)
                                .metadata("{\"canonicalUrl\":\"https://example.com/about\"}")
                                .build();
                    }
                    if ("https://example.com/pricing".equals(url)) {
                        return SourceCollector.CollectedPage.builder()
                                .url(url)
                                .title("Pricing")
                                .content("Pricing plans and billing information for enterprise subscriptions.")
                                .snippet("Pricing plans and billing information")
                                .success(true)
                                .build();
                    }
                    return SourceCollector.CollectedPage.builder()
                            .url(url)
                            .success(false)
                            .errorMessage("not found")
                            .build();
                });

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("Example Cloud")
                .sourceType("PRICING")
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://example.com/login")
                        .title("Example Cloud Login")
                        .sourceType("PRICING")
                        .discoveryMethod("DIRECT_LOCATOR")
                        .sourceUrls(List.of("https://example.com/login"))
                        .relevanceScore(0.90D)
                        .freshnessScore(0.60D)
                        .qualityScore(0.70D)
                        .build()))
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.FALSE)
                .searchMode("HEURISTIC_ONLY")
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .recoveryFieldName("pricing")
                .recoveryEvidencePathKey("OFFICIAL_PRICING_PAGE")
                .recoveryQueryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING"))
                .build());

        SearchExecutionStep recoveryStep = result.getExecutionPlan().getSteps().stream()
                .filter(step -> "PUBLIC_EVIDENCE_RECOVERY".equals(step.getStepCode()))
                .findFirst()
                .orElseThrow();

        assertThat(result.getExecutionPlan().getSteps())
                .extracting(SearchExecutionStep::getStepCode)
                .containsSubsequence("BROWSER_SUPPLEMENT_SEARCH", "PUBLIC_EVIDENCE_RECOVERY", "SELECT_TARGETS");
        assertThat(recoveryStep.getStatus()).isEqualTo(SearchExecutionStep.StepStatus.SUCCESS);
        assertThat(result.getSelectedTargets()).singleElement().satisfies(target -> {
            assertThat(target.getCandidate().getUrl()).isEqualTo("https://example.com/pricing");
            assertThat(target.getCandidate().getVerified()).isTrue();
        });
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryTriggered()).isTrue();
        assertThat(result.getExecutionTrace().getPublicEvidenceAttemptedUrls())
                .contains("https://example.com/pricing", "https://example.com/billing");
        assertThat(result.getExecutionTrace().getPublicEvidenceAttemptedEvidencePaths())
                .containsExactly("OFFICIAL_PRICING_PAGE");
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryFieldName()).isEqualTo("pricing");
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryEvidencePathKey())
                .isEqualTo("OFFICIAL_PRICING_PAGE");
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryQueryIntents())
                .containsExactly("OFFICIAL_PRICING", "DOCS_BILLING");
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryCandidateCount()).isGreaterThan(0);
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryVerifiedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryStatus()).isEqualTo("RECOVERED_PUBLIC_PAGE");
        assertThat(result.getExecutionTrace().getEvidenceRepairPlan())
                .containsEntry("repairState", "REPAIR_EVIDENCE_PROMOTED");
        assertThat((List<String>) result.getExecutionTrace().getEvidenceRepairPlan().get("promotedUrls"))
                .contains("https://example.com/pricing");
        assertThat((Map<String, Object>) result.getAuditSnapshot().getEvidenceRepairPlan())
                .containsEntry("repairState", "REPAIR_EVIDENCE_PROMOTED");
        verify(searchSourceProvider, never()).search(any(), any());
    }
}
