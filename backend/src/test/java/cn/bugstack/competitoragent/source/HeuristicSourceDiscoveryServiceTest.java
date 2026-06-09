package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicSourceDiscoveryServiceTest {

    private final SourceCandidateRanker candidateRanker = new SourceCandidateRanker();
    private final SearchSourceProvider searchSourceProvider = (competitorName, requestedScopes) -> List.of(
            SourceCandidate.builder()
                    .url("https://docs.notion.so/getting-started")
                    .title("Notion AI Docs")
                    .sourceType("DOCS")
                    .discoveryMethod("SEARCH")
                    .reason("搜索补源命中文档入口")
                    .domain("docs.notion.so")
                    .publishedAt("2026-05-20")
                    .relevanceScore(0.92)
                    .freshnessScore(0.78)
                    .qualityScore(0.90)
                    .build(),
            SourceCandidate.builder()
                    .url("https://www.notion.so/pricing")
                    .title("Notion AI Pricing")
                    .sourceType("PRICING")
                    .discoveryMethod("SEARCH")
                    .reason("搜索补源命中定价页")
                    .domain("www.notion.so")
                    .publishedAt("2026-05-18")
                    .relevanceScore(0.95)
                    .freshnessScore(0.72)
                    .qualityScore(0.94)
                    .build()
    );
    private final HeuristicSourceDiscoveryService service =
            new HeuristicSourceDiscoveryService(searchSourceProvider, candidateRanker);

    @Test
    void shouldMergeSearchAndHeuristicCandidatesWithRanking() {
        List<SourcePlan> plans = service.discover(
                "Notion AI",
                List.of("https://www.notion.so"),
                List.of("官网", "产品文档", "定价页面", "公开测评")
        );

        assertFalse(plans.isEmpty());
        SourcePlan docsPlan = plans.stream()
                .filter(plan -> "DOCS".equals(plan.getSourceType()))
                .findFirst()
                .orElseThrow();

        assertNotNull(docsPlan.getCandidates());
        assertFalse(docsPlan.getCandidates().isEmpty());
        assertTrue(docsPlan.getCandidates().stream().anyMatch(candidate ->
                "SEARCH".equals(candidate.getDiscoveryMethod())));
        assertEquals(docsPlan.getCandidates().stream().map(SourceCandidate::getUrl).toList(),
                docsPlan.getUrls());
        assertTrue(docsPlan.getCandidates().get(0).getTotalScore() >= docsPlan.getCandidates().get(docsPlan.getCandidates().size() - 1).getTotalScore());
        assertNotNull(docsPlan.getCandidates().get(0).getTrustTier());
        assertNotNull(docsPlan.getCandidates().get(0).getRankingSummary());
        assertTrue(docsPlan.getCandidates().get(0).getRankingReasons().stream().anyMatch(reason -> !reason.isBlank()));
    }

    @Test
    void shouldDeduplicateRepeatedUrlsAndKeepHigherScore() {
        List<SourceCandidate> ranked = candidateRanker.rankAndDeduplicate(List.of(
                SourceCandidate.builder()
                        .url("https://www.example.com/pricing")
                        .sourceType("PRICING")
                        .discoveryMethod("HEURISTIC")
                        .reason("heuristic")
                        .relevanceScore(0.7)
                        .freshnessScore(0.5)
                        .qualityScore(0.8)
                        .build(),
                SourceCandidate.builder()
                        .url("https://www.example.com/pricing/")
                        .sourceType("PRICING")
                        .discoveryMethod("SEARCH")
                        .reason("search")
                        .relevanceScore(0.95)
                        .freshnessScore(0.9)
                        .qualityScore(0.9)
                        .build()
        ));

        assertEquals(1, ranked.size());
        assertEquals("SEARCH", ranked.get(0).getDiscoveryMethod());
        assertTrue(ranked.get(0).getTotalScore() > 0.9);
    }

    @Test
    void shouldCountBrowserPreviewCandidatesAsSearchSupplement() {
        HeuristicSourceDiscoveryService previewService = new HeuristicSourceDiscoveryService(
                (competitorName, requestedScopes) -> List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/preview")
                        .title("Preview Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("BROWSER_PREVIEW")
                        .reason("浏览器预览补源")
                        .domain("docs.example.com")
                        .relevanceScore(0.9)
                        .freshnessScore(0.6)
                        .qualityScore(0.9)
                        .build()),
                candidateRanker
        );

        List<SourcePlan> plans = previewService.discover(
                "Notion AI",
                List.of("https://www.notion.so"),
                List.of("产品文档")
        );

        SourcePlan docsPlan = plans.stream()
                .filter(plan -> "DOCS".equals(plan.getSourceType()))
                .findFirst()
                .orElseThrow();
        assertTrue(docsPlan.getNotes().contains("搜索补源 1 个"));
    }

    @Test
    void shouldCreateSearchOnlyPlaceholderPlanWithoutGuessingFakeDomains() {
        HeuristicSourceDiscoveryService noSearchResultService = new HeuristicSourceDiscoveryService(
                (competitorName, requestedScopes) -> List.of(),
                candidateRanker
        );

        List<SourcePlan> plans = noSearchResultService.discover(
                "Notion AI",
                List.of(),
                List.of("官网", "产品文档")
        );

        assertEquals(2, plans.size());
        assertTrue(plans.stream().allMatch(plan -> plan.getUrls().isEmpty()));
        assertTrue(plans.stream().allMatch(plan -> plan.getCandidates().isEmpty()));
        assertTrue(plans.stream().allMatch(plan -> plan.getNotes().contains("跳过域名猜测")));
        assertFalse(plans.stream()
                .flatMap(plan -> plan.getUrls().stream())
                .anyMatch(url -> url.contains("notionai.com") || url.contains("notionai.ai") || url.contains("notionai.io")));
    }
}
