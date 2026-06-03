package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCandidateRankerTest {

    private final SourceCandidateRanker ranker = new SourceCandidateRanker();

    @Test
    void shouldDemoteUtilityPagesEvenWhenRawScoresLookHigh() {
        // 这里先把“登录页、招聘页不应因为原始分高就排到前面”固化成红灯，
        // 后续实现时需要在排序器里补充页面价值识别与淘汰原因。
        List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
                candidate("https://www.example.com/login", "Example Login", "DOCS", "SEARCH",
                        "2026-05-30", 0.98, 0.95, 0.95),
                candidate("https://www.example.com/careers", "Example Careers", "OFFICIAL", "SEARCH",
                        "2026-05-29", 0.97, 0.94, 0.94),
                candidate("https://docs.example.com/api", "Example API Reference", "DOCS", "SEARCH",
                        "2026-05-18", 0.84, 0.72, 0.90),
                candidate("https://www.example.com/pricing", "Example Pricing", "PRICING", "HEURISTIC",
                        "2026-05-15", 0.82, 0.65, 0.89)
        ));

        int docsIndex = indexOf(ranked, "https://docs.example.com/api");
        int pricingIndex = indexOf(ranked, "https://www.example.com/pricing");
        int loginIndex = indexOf(ranked, "https://www.example.com/login");
        int careersIndex = indexOf(ranked, "https://www.example.com/careers");

        assertTrue(docsIndex < loginIndex, "高价值文档页应该排在登录页之前");
        assertTrue(pricingIndex < careersIndex, "定价页应该排在招聘页之前");

        SourceCandidate loginCandidate = ranked.get(loginIndex);
        SourceCandidate careersCandidate = ranked.get(careersIndex);
        assertEquals("DISCARDED", loginCandidate.getSelectionStage());
        assertEquals("LOW_SIGNAL_UTILITY_PAGE", loginCandidate.getSelectionReason());
        assertEquals("DISCARDED", careersCandidate.getSelectionStage());
        assertEquals("LOW_SIGNAL_UTILITY_PAGE", careersCandidate.getSelectionReason());
    }

    @Test
    void shouldKeepFresherSearchCandidateWhenCanonicalUrlsDuplicate() {
        // 这里把“规范化 URL 重复时，需要稳定保留更新且更可靠的候选”先写成测试，
        // 避免后续补源顺序被输入顺序偶然左右。
        List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
                candidate("https://docs.example.com/reference/", "Reference Overview", "DOCS", "HEURISTIC",
                        "2026-03-01", 0.90, 0.70, 0.90),
                candidate("https://docs.example.com/reference", "Reference API", "DOCS", "SEARCH",
                        "2026-05-25", 0.90, 0.70, 0.90)
        ));

        assertEquals(1, ranked.size());

        SourceCandidate keptCandidate = ranked.get(0);
        assertEquals("SEARCH", keptCandidate.getDiscoveryMethod());
        assertEquals("2026-05-25", keptCandidate.getPublishedAt());
        assertEquals("SELECTED", keptCandidate.getSelectionStage());
        assertEquals("KEEP_FRESHER_SEARCH_RESULT", keptCandidate.getSelectionReason());
        assertNotNull(keptCandidate.getDomain());
    }

    private SourceCandidate candidate(String url,
                                      String title,
                                      String sourceType,
                                      String discoveryMethod,
                                      String publishedAt,
                                      double relevanceScore,
                                      double freshnessScore,
                                      double qualityScore) {
        return SourceCandidate.builder()
                .url(url)
                .title(title)
                .sourceType(sourceType)
                .discoveryMethod(discoveryMethod)
                .publishedAt(publishedAt)
                .relevanceScore(relevanceScore)
                .freshnessScore(freshnessScore)
                .qualityScore(qualityScore)
                .build();
    }

    private int indexOf(List<SourceCandidate> ranked, String url) {
        for (int index = 0; index < ranked.size(); index++) {
            if (url.equals(ranked.get(index).getUrl())) {
                return index;
            }
        }
        throw new IllegalArgumentException("未找到 URL: " + url);
    }
}
