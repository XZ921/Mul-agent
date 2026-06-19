package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCandidateRankerTest {

    private final SourceCandidateRanker ranker = new SourceCandidateRanker();

    @Test
    void shouldPreferHighValueDocsPageOverGenericHomepageForDocsFamily() {
        List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
                SourceCandidate.builder()
                        .url("https://www.example.com")
                        .title("Example Homepage")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .discoveryMethod("SEARCH")
                        .relevanceScore(0.92)
                        .freshnessScore(0.80)
                        .qualityScore(0.88)
                        .build(),
                SourceCandidate.builder()
                        .url("https://docs.example.com/api/reference")
                        .title("Example API Reference")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .discoveryMethod("SEARCH")
                        .relevanceScore(0.86)
                        .freshnessScore(0.65)
                        .qualityScore(0.84)
                        .build()
        ));

        SourceCandidate docsCandidate = ranked.get(0);
        assertEquals("https://docs.example.com/api/reference", docsCandidate.getUrl());
        assertEquals("official", docsCandidate.getSourceFamilyKey());
        assertTrue(docsCandidate.getQualitySignals().contains("DOCS_HIGH_VALUE_PATH"));
        assertTrue(docsCandidate.getRankingSummary().contains("文档高价值路径"));
    }

    @Test
    void shouldTreatSingularDocPathAsHighValueDocsEntry() {
        List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
                SourceCandidate.builder()
                        .url("https://open.bilibili.com")
                        .title("哔哩哔哩开放平台")
                        .sourceType("DOCS")
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .relevanceScore(0.94)
                        .freshnessScore(0.60)
                        .qualityScore(0.94)
                        .build(),
                SourceCandidate.builder()
                        .url("https://open.bilibili.com/wiki/")
                        .title("哔哩哔哩旧文档入口")
                        .sourceType("DOCS")
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .relevanceScore(0.94)
                        .freshnessScore(0.60)
                        .qualityScore(0.94)
                        .build(),
                SourceCandidate.builder()
                        .url("https://open.bilibili.com/doc/")
                        .title("哔哩哔哩开放平台文档")
                        .sourceType("DOCS")
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .relevanceScore(0.90)
                        .freshnessScore(0.60)
                        .qualityScore(0.90)
                        .build()
        ));

        SourceCandidate firstCandidate = ranked.get(0);

        assertEquals("https://open.bilibili.com/doc/", firstCandidate.getUrl());
        assertTrue(firstCandidate.getQualitySignals().contains("DOCS_HIGH_VALUE_PATH"));
    }

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
        assertEquals("优先保留更新且更可靠的搜索候选", keptCandidate.getSelectionSummary());
        assertEquals(SourceTrustTier.HIGH, keptCandidate.getTrustTier());
        assertNotNull(keptCandidate.getRankingSummary());
        assertTrue(keptCandidate.getRankingSummary().contains("来源可信度"));
        assertTrue(keptCandidate.getRankingReasons().stream().anyMatch(reason -> reason.contains("搜索补源")));
        assertNotNull(keptCandidate.getDomain());
    }

    @Test
    void shouldAttachTrustTierAndRankingReasonsToRankedCandidates() {
        List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
                candidate("https://docs.example.com/api", "Example API Reference", "DOCS", "SEARCH",
                        "2026-05-18", 0.84, 0.72, 0.90),
                candidate("https://reviews.example.net/post", "Community Review", "REVIEW", "SEARCH",
                        "2026-04-01", 0.72, 0.58, 0.60)
        ));

        SourceCandidate docsCandidate = ranked.stream()
                .filter(candidate -> "https://docs.example.com/api".equals(candidate.getUrl()))
                .findFirst()
                .orElseThrow();
        SourceCandidate reviewCandidate = ranked.stream()
                .filter(candidate -> "https://reviews.example.net/post".equals(candidate.getUrl()))
                .findFirst()
                .orElseThrow();

        assertEquals(SourceTrustTier.HIGH, docsCandidate.getTrustTier());
        assertEquals("高可信", docsCandidate.getTrustTierLabel());
        assertNotNull(docsCandidate.getRankingSummary());
        assertTrue(docsCandidate.getRankingReasons().stream().anyMatch(reason -> reason.contains("文档域名")));

        assertEquals(SourceTrustTier.MEDIUM, reviewCandidate.getTrustTier());
        assertEquals("中可信", reviewCandidate.getTrustTierLabel());
        assertTrue(reviewCandidate.getRankingReasons().stream().anyMatch(reason -> reason.contains("第三方")));
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
