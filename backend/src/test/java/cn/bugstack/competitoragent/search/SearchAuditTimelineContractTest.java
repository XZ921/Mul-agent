package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAuditTimelineContractTest {

    @Test
    void shouldExposeAttemptedDiscardedReplayTimelineAndTavilyAuditInAuditSnapshot() {
        SourceCandidate selected = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .sourceType("DOCS")
                .selectionStage("SELECTED")
                .sourceFamilyKey("official")
                .build();
        SourceCandidate discarded = SourceCandidate.builder()
                .url("https://www.example.com/login")
                .sourceType("DOCS")
                .selectionStage("DISCARDED")
                .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
                .sourceFamilyKey("official")
                .build();
        TavilyFastLaneAudit tavilyFastLaneAudit = TavilyFastLaneAudit.builder()
                .queryModes(List.of("OFFICIAL_DOCS"))
                .queryOrigins(List.of("BOOTSTRAP"))
                .queriesSent(1)
                .totalResults(2)
                .fastLaneUsableCount(1)
                .fastLaneRejectedCount(1)
                .bootstrapTriggered(true)
                .fallbackTriggered(true)
                .playwrightInvocationBaselineHint(1)
                .build();

        SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
                .executionTrace(SearchExecutionTrace.builder()
                        .tavilyFastLaneAudit(tavilyFastLaneAudit)
                        .build())
                .attemptedTargets(List.of(SearchCollectionTarget.builder().candidate(selected).build()))
                .discardedCandidates(List.of(discarded))
                .replayTimeline(List.of(SearchReplayTimelineItem.builder()
                        .stepCode("SELECT_TARGETS")
                        .stepName("合并候选并选出最终采集目标")
                        .status("SUCCESS")
                        .candidateCount(2)
                        .selectedCount(1)
                        .discardedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();
        SearchAuditSummary summary = SearchAuditSummary.from(snapshot);

        assertThat(snapshot.getAttemptedTargets()).hasSize(1);
        assertThat(snapshot.getDiscardedCandidates()).extracting(SourceCandidate::getUrl)
                .containsExactly("https://www.example.com/login");
        assertThat(snapshot.getReplayTimeline()).extracting(SearchReplayTimelineItem::getStepCode)
                .containsExactly("SELECT_TARGETS");
        assertThat(snapshot.getReplayTimeline().get(0).getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
        assertThat(snapshot.getTavilyFastLaneAudit()).isNotNull();
        assertThat(snapshot.getExecutionTrace().getTavilyFastLaneAudit().getQueriesSent()).isEqualTo(1);
        assertThat(summary.getTavilyFastLaneAudit().getFastLaneUsableCount()).isEqualTo(1);
        assertThat(summary.getTavilyFastLaneAudit().getQueryOrigins()).containsExactly("BOOTSTRAP");
        assertThat(summary.getTavilyFastLaneAudit().getBootstrapTriggered()).isTrue();
    }
}
