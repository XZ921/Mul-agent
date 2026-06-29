package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRuntimeObjectSlimmingContractTest {

    @Test
    void shouldExposeFormalSearchAuditSummaryFromSnapshot() {
        SearchSelectedTargetSummary selectedSummary = SearchSelectedTargetSummary.builder()
                .url("https://docs.example.com/reference")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();
        SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
                .summary(SearchAuditSummary.builder()
                        .candidateCount(2)
                        .selectedCount(1)
                        .discardedCount(1)
                        .attemptedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .executionTrace(SearchExecutionTrace.builder()
                        .degraded(true)
                        .degradationReason("SEARCH_TIMEOUT_AFTER_SUPPLEMENT")
                        .fallbackDecision("SUPPLEMENTED_BUT_SKIP_VERIFY_DUE_TIMEOUT")
                        .recoveryCheckpoint("SELECT_TARGETS")
                        .build())
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/reference")
                        .build()))
                .attemptedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(SourceCandidate.builder().url("https://docs.example.com/reference").build())
                        .build()))
                .selectedTargets(List.of(SearchCollectionTarget.builder()
                        .candidate(SourceCandidate.builder().url("https://docs.example.com/reference").build())
                        .selectedSummary(selectedSummary)
                        .build()))
                .discardedCandidates(List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/legacy")
                        .build()))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(snapshot.getSummary()).isNotNull();
        assertThat(snapshot.getSummary().getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(SearchAuditSummary.from(snapshot).getSelectedCount()).isEqualTo(1);
    }

    @Test
    void shouldExposeSharedProjectionOnSearchExecutionResult() {
        SearchSharedProjection projection = SearchSharedProjection.builder()
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .recoveryCheckpoint("SELECT_TARGETS")
                .searchAuditSummary(SearchAuditSummary.builder()
                        .selectedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .selectedTargets(List.of(SearchSelectedTargetSummary.builder()
                        .url("https://docs.example.com/reference")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();
        SearchExecutionResult result = SearchExecutionResult.builder()
                .sharedProjection(projection)
                .build();

        assertThat(result.getSharedProjection().getProjectionType()).isEqualTo("SEARCH_SHARED_PROJECTION_V1");
        assertThat(result.getSharedProjection().getRecoveryCheckpoint()).isEqualTo("SELECT_TARGETS");
    }

    @Test
    void shouldExposeSelectedTargetSummaryOnCollectionTarget() {
        SearchCollectionTarget target = SearchCollectionTarget.builder()
                .selectedSummary(SearchSelectedTargetSummary.builder()
                        .url("https://docs.example.com/reference")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();

        assertThat(target.getSelectedSummary().getUrl()).isEqualTo("https://docs.example.com/reference");
        assertThat(target.getSelectedSummary().getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
    }

    @Test
    void sourceCandidateShouldNotStoreFullTavilyRawContent() {
        List<String> fieldNames = Arrays.stream(SourceCandidate.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames).contains(
                "hasPrefetchedContent",
                "prefetchedContentRef",
                "prefetchedRawContentLength",
                "tavilyScore",
                "tavilyRequestId",
                "tavilyQuery",
                "tavilyQueryMode",
                "pageType",
                "qualityTier",
                "fastLaneUsable",
                "fastLaneRejectReason",
                "contentCompleteness",
                "skipNetworkVerification"
        );
        assertThat(fieldNames).doesNotContain("prefetchedRawContent", "rawContent");
    }
}
