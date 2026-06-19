package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.CompetitorDomainDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertEquals("official", docsPlan.getSourceFamilyKey());
        assertEquals("PRIMARY_VERTICAL", docsPlan.getSourceFamilyRole());
        assertTrue(docsPlan.getPrimaryTools().contains("WEB_SCRAPER"));
        assertTrue(docsPlan.getAuxiliaryTools().contains("PUBLIC_SEARCH"));
        assertTrue(docsPlan.getQueryTemplates().contains("search-docs-primary"));
        assertEquals(docsPlan.getUrls(), docsPlan.getSourceUrls());
        assertTrue(docsPlan.getCandidates().stream().anyMatch(candidate ->
                "SEARCH".equals(candidate.getDiscoveryMethod())));
        assertTrue(docsPlan.getCandidates().stream().allMatch(candidate ->
                "official".equals(candidate.getSourceFamilyKey())
                        && "PRIMARY_VERTICAL".equals(candidate.getSourceFamilyRole())
                        && candidate.getSourceUrls() != null
                        && candidate.getSourceUrls().contains(candidate.getUrl())));
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
        assertTrue(plans.stream().allMatch(plan -> plan.getSourceUrls().isEmpty()));
        assertTrue(plans.stream().allMatch(plan -> plan.getSourceFamilyKey() != null));
        assertTrue(plans.stream().allMatch(plan -> plan.getPrimaryTools() != null));
        assertTrue(plans.stream().allMatch(plan -> plan.getCandidates().isEmpty()));
        assertTrue(plans.stream().allMatch(plan -> plan.getNotes().contains("跳过域名猜测")));
        assertFalse(plans.stream()
                .flatMap(plan -> plan.getUrls().stream())
                .anyMatch(url -> url.contains("notionai.com") || url.contains("notionai.ai") || url.contains("notionai.io")));
    }

    @Test
    void shouldUseDomainDiscoveryCandidatesBeforeCreatingSearchOnlyPlaceholderPlan() {
        CompetitorDomainDiscoveryService competitorDomainDiscoveryService = mock(CompetitorDomainDiscoveryService.class);
        when(competitorDomainDiscoveryService.discover("Bilibili")).thenReturn(List.of(
                SourceCandidate.builder()
                        .url("https://www.bilibili.com")
                        .title("Bilibili Official")
                        .sourceType("OFFICIAL")
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .reason("LLM 发现官网")
                        .domain("www.bilibili.com")
                        .sourceUrls(List.of("llm://domain-discovery/Bilibili"))
                        .relevanceScore(0.95)
                        .freshnessScore(0.60)
                        .qualityScore(0.93)
                        .build(),
                SourceCandidate.builder()
                        .url("https://open.bilibili.com/doc/")
                        .title("Bilibili Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .reason("LLM 发现开放平台文档")
                        .domain("open.bilibili.com")
                        .sourceUrls(List.of("llm://domain-discovery/Bilibili"))
                        .relevanceScore(0.94)
                        .freshnessScore(0.60)
                        .qualityScore(0.94)
                        .build()
        ));

        HeuristicSourceDiscoveryService discoveryService = new HeuristicSourceDiscoveryService(
                (competitorName, requestedScopes) -> List.of(),
                candidateRanker,
                null,
                competitorDomainDiscoveryService
        );

        List<SourcePlan> plans = discoveryService.discover(
                "Bilibili",
                List.of(),
                List.of("官网", "产品文档")
        );

        assertEquals(2, plans.size());
        assertTrue(plans.stream().noneMatch(plan -> plan.getNotes().contains("跳过域名猜测")));
        assertTrue(plans.stream()
                .flatMap(plan -> plan.getCandidates().stream())
                .anyMatch(candidate -> "DOMAIN_DISCOVERY_LLM".equals(candidate.getDiscoveryMethod())));
        assertTrue(plans.stream()
                .flatMap(plan -> plan.getCandidates().stream())
                .allMatch(candidate -> candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()));
    }

    @Test
    void shouldExposeFamilyTemplateCandidatesInPreviewPlan() {
        HeuristicSourceDiscoveryService previewService = new HeuristicSourceDiscoveryService(
                (competitorName, requestedScopes) -> List.of(),
                candidateRanker
        );

        List<SourcePlan> plans = previewService.discoverForPreview(
                "Acme AI",
                List.of("https://www.acme.ai"),
                List.of("产品文档", "定价页面")
        );

        SourcePlan docsPlan = plans.stream()
                .filter(plan -> "DOCS".equals(plan.getSourceType()))
                .findFirst()
                .orElseThrow();
        SourcePlan pricingPlan = plans.stream()
                .filter(plan -> "PRICING".equals(plan.getSourceType()))
                .findFirst()
                .orElseThrow();

        assertTrue(docsPlan.getCandidates().stream().allMatch(candidate ->
                "DOCS".equals(candidate.getSourceType())));
        assertTrue(pricingPlan.getCandidates().stream().allMatch(candidate ->
                "PRICING".equals(candidate.getSourceType())));
        assertTrue(docsPlan.getCandidates().stream().anyMatch(candidate ->
                "FAMILY_TEMPLATE".equals(candidate.getDiscoveryMethod())
                        && "https://www.acme.ai/docs".equals(candidate.getUrl())));
        assertTrue(pricingPlan.getCandidates().stream().anyMatch(candidate ->
                "FAMILY_TEMPLATE".equals(candidate.getDiscoveryMethod())
                        && "https://www.acme.ai/pricing".equals(candidate.getUrl())));
    }
}
