package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionTargetSelectorTest {

    private final CollectionTargetSelector selector = new CollectionTargetSelector();

    @Test
    void shouldPreferVerifiedAttemptedTargetOverHigherScoredDiscardedCandidateAndReuseCollectedPage() {
        SourceCandidate discardedOfficial = SourceCandidate.builder()
                .url("https://www.aliyun.com/product/ecs")
                .title("阿里云 ECS 营销页")
                .selectionStage("DISCARDED")
                .verified(Boolean.FALSE)
                .totalScore(0.99)
                .build();
        SourceCandidate verifiedDoc = SourceCandidate.builder()
                .url("https://help.aliyun.com/document_detail/12345.html")
                .title("实例规格说明")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.71)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedDoc.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedDoc)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedDoc.getUrl())
                        .title("实例规格说明")
                        .content("规格说明")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(discardedOfficial, verifiedDoc),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://help.aliyun.com/document_detail/12345.html",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertEquals(List.of("https://help.aliyun.com/document_detail/12345.html"), decision.getSourceUrls());
        assertTrue(decision.getUpdatedCandidates().stream()
                .anyMatch(candidate -> "https://help.aliyun.com/document_detail/12345.html".equals(candidate.getUrl())
                        && "SELECTED".equals(candidate.getSelectionStage())));
    }

    @Test
    void shouldRefreshSelectedTargetCandidateSnapshotByNormalizedUrl() {
        SourceCandidate selectedCandidate = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .title("Reference")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.88)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put("https://docs.example.com/reference?utm_source=test", SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/reference?utm_source=test")
                        .title("Old Reference")
                        .selectionStage("VERIFIED")
                        .verified(Boolean.TRUE)
                        .build())
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/reference")
                        .title("Reference")
                        .content("api reference")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(selectedCandidate),
                attemptedTargets,
                1
        );

        assertEquals("https://docs.example.com/reference",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("SELECTED", decision.getSelectedTargets().get(0).getCandidate().getSelectionStage());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertEquals(List.of("https://docs.example.com/reference"), decision.getSourceUrls());
    }

    @Test
    void shouldExposeDiscardedCandidatesWhenSelectingTargets() {
        SourceCandidate discarded = SourceCandidate.builder()
                .url("https://www.example.com/login")
                .selectionStage("DISCARDED")
                .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
                .totalScore(0.99)
                .build();
        SourceCandidate selected = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.80)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(List.of(discarded, selected), Map.of(), 1);

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://docs.example.com/reference", decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(List.of("https://www.example.com/login"),
                decision.getDiscardedCandidates().stream().map(SourceCandidate::getUrl).toList());
    }
}
