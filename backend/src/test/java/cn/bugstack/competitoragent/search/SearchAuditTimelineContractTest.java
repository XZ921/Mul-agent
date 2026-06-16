package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAuditTimelineContractTest {

    @Test
    void shouldExposeAttemptedDiscardedAndReplayTimelineInAuditSnapshot() {
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

        SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
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
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(snapshot.getAttemptedTargets()).hasSize(1);
        assertThat(snapshot.getDiscardedCandidates()).extracting(SourceCandidate::getUrl)
                .containsExactly("https://www.example.com/login");
        assertThat(snapshot.getReplayTimeline()).extracting(SearchReplayTimelineItem::getStepCode)
                .containsExactly("SELECT_TARGETS");
        assertThat(snapshot.getReplayTimeline().get(0).getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
    }
}
