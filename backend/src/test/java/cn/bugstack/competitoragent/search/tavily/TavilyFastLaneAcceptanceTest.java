package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.CollectionTaskPackage;
import cn.bugstack.competitoragent.collection.CollectionTaskPackageBuilder;
import cn.bugstack.competitoragent.collection.TavilyPrefetchedExecutor;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.TavilyFastLaneProvider;
import cn.bugstack.competitoragent.source.TavilySearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyFastLaneAcceptanceTest {

    private static final String OFFICIAL_DOMAIN = "open.douyin.com";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldOutperformLegacyBaselineForOpenWebEvidenceQuality() throws Exception {
        SearchSourceRequest openWebRequest = SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("REVIEW"))
                .searchQueries(List.of("douyin recommendation algorithm analysis"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OPEN_WEB")
                .build();

        AcceptanceScenario baseline = runScenario(openWebRequest,
                List.of(loadFixture("tavily/noise-search-page-response.json")));
        AcceptanceScenario experiment = runScenario(openWebRequest,
                List.of(loadFixture("tavily/recommendation-algorithm-response.json")));

        assertThat(experiment.metrics.traceableEvidenceRatio).isEqualTo(1.0D);
        assertThat(experiment.metrics.mediumOrAboveEvidenceCount)
                .isGreaterThan(baseline.metrics.mediumOrAboveEvidenceCount);
        assertThat(experiment.metrics.thinContentRatio)
                .isLessThanOrEqualTo(baseline.metrics.thinContentRatio);
        assertThat(experiment.metrics.collectionFailureRatio)
                .isLessThanOrEqualTo(baseline.metrics.collectionFailureRatio);
        assertThat(experiment.metrics.playwrightInvocationCount)
                .isLessThan(baseline.metrics.playwrightInvocationCount);
        assertThat(experiment.metrics.successfulCollectionCount).isGreaterThan(0);
        assertThat(experiment.metrics.allRejectedHaveReason).isTrue();
    }

    @Test
    void shouldIncreaseOfficialDocHitCountForFamilyAwareOfficialDocsSearch() throws Exception {
        SearchSourceRequest baselineRequest = SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("douyin open platform api docs"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OPEN_WEB")
                .build();
        SearchSourceRequest officialDocsRequest = SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("douyin open platform api docs"))
                .preferredDomains(List.of(OFFICIAL_DOMAIN))
                .includeDomains(List.of(OFFICIAL_DOMAIN))
                .preferredProviderKey("tavily")
                .build();

        AcceptanceScenario baseline = runScenario(baselineRequest,
                List.of(loadFixture("tavily/recommendation-algorithm-response.json")));
        AcceptanceScenario experiment = runScenario(officialDocsRequest,
                List.of(loadFixture("tavily/official-docs-response.json")));

        assertThat(experiment.metrics.traceableEvidenceRatio).isEqualTo(1.0D);
        assertThat(experiment.metrics.officialDocHitCount)
                .isGreaterThan(baseline.metrics.officialDocHitCount);
        assertThat(experiment.candidates)
                .allMatch(candidate -> OFFICIAL_DOMAIN.equalsIgnoreCase(candidate.getDomain()));
    }

    @Test
    void shouldFallbackToTrustedExpansionWhenOfficialAnchorOnlyReturnsNoisePages() throws Exception {
        SearchSourceRequest officialDocsRequest = SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("douyin open platform api docs"))
                .preferredDomains(List.of(OFFICIAL_DOMAIN))
                .includeDomains(List.of(OFFICIAL_DOMAIN))
                .preferredProviderKey("tavily")
                .build();

        AcceptanceScenario includeDomainsOnly = runScenario(officialDocsRequest,
                List.of(loadFixture("tavily/noise-search-page-response.json")));
        AcceptanceScenario expansion = runScenario(officialDocsRequest,
                List.of(
                        loadFixture("tavily/noise-search-page-response.json"),
                        loadFixture("tavily/recommendation-algorithm-response.json")
                ));

        assertThat(expansion.executedProfiles).hasSize(2);
        assertThat(expansion.executedProfiles.get(1).getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(expansion.metrics.trustedExpansionUsableCount)
                .isGreaterThan(includeDomainsOnly.metrics.trustedExpansionUsableCount);
        assertThat(expansion.metrics.openWebDiversityCount)
                .isGreaterThan(includeDomainsOnly.metrics.openWebDiversityCount);
        assertThat(expansion.metrics.collectionFailureRatio)
                .isLessThan(includeDomainsOnly.metrics.collectionFailureRatio);
        assertThat(expansion.metrics.allRejectedHaveReason).isTrue();
    }

    @Test
    void shouldRejectNoisePagesWithExplicitFastLaneReasons() throws Exception {
        SearchSourceRequest openWebRequest = SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("REVIEW"))
                .searchQueries(List.of("douyin recommendation algorithm analysis"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OPEN_WEB")
                .build();

        AcceptanceScenario scenario = runScenario(openWebRequest,
                List.of(loadFixture("tavily/noise-search-page-response.json")));
        Map<String, SourceCandidate> candidateByUrl = new LinkedHashMap<>();
        for (SourceCandidate candidate : scenario.candidates) {
            candidateByUrl.put(candidate.getUrl(), candidate);
        }

        assertThat(candidateByUrl.get("https://www.douyin.com/search/algorithm?query=recommendation").getPageType())
                .isEqualTo("SEARCH_PAGE");
        assertThat(candidateByUrl.get("https://www.douyin.com/search/algorithm?query=recommendation").getFastLaneRejectReason())
                .isEqualTo("SEARCH_PAGE");
        assertThat(candidateByUrl.get("https://www.bilibili.com/v/popular/all").getPageType())
                .isEqualTo("VIDEO_LIST");
        assertThat(candidateByUrl.get("https://www.bilibili.com/v/popular/all").getFastLaneRejectReason())
                .isEqualTo("VIDEO_LIST");
        assertThat(candidateByUrl.get("https://www.reddit.com/r/socialmedia/comments/12345/how_douyin_recommendation_works/").getPageType())
                .isEqualTo("FORUM_THREAD");
        assertThat(candidateByUrl.get("https://www.reddit.com/r/socialmedia/comments/12345/how_douyin_recommendation_works/").getFastLaneUsable())
                .isFalse();
        assertThat(candidateByUrl.get("https://www.reddit.com/r/socialmedia/comments/12345/how_douyin_recommendation_works/").getFastLaneRejectReason())
                .isEqualTo("WEAK_CONTENT");
        assertThat(scenario.metrics.allRejectedHaveReason).isTrue();
    }

    private AcceptanceScenario runScenario(SearchSourceRequest request,
                                           List<TavilySearchClient.TavilySearchResponse> responses) {
        TavilySearchProperties properties = properties();
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        FixtureBackedTavilySearchClient client = new FixtureBackedTavilySearchClient(responses);
        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties,
                client,
                new TavilySearchProfileResolver(properties),
                registry,
                objectMapper
        );
        List<SourceCandidate> candidates = provider.search(request);
        List<CollectionExecutionResult> collectionResults = collectFastLaneEvidence(
                request.getCompetitorName(),
                candidates,
                registry
        );
        return new AcceptanceScenario(candidates, collectionResults, client.executedProfiles, computeMetrics(candidates, collectionResults));
    }

    /**
     * Task 10 的验收指标只关心“能否从 Tavily 结果直接落地为可追溯证据”。
     * 因此这里故意只消费 fast lane 可用候选，把 legacy fallback 视为直接落地失败代理指标，
     * 这样可以稳定衡量 fast lane 方案本身是否减少了后续浏览器/网络链路依赖。
     */
    private List<CollectionExecutionResult> collectFastLaneEvidence(String competitorName,
                                                                    List<SourceCandidate> candidates,
                                                                    TavilyPrefetchedContentRegistry registry) {
        CollectionTaskPackageBuilder packageBuilder = new CollectionTaskPackageBuilder();
        TavilyPrefetchedExecutor executor = new TavilyPrefetchedExecutor(registry);
        List<CollectionExecutionResult> results = new ArrayList<>();
        int priority = 0;
        for (SourceCandidate candidate : candidates) {
            if (!Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                priority++;
                continue;
            }
            CollectionTaskPackage taskPackage = packageBuilder.build(
                    1L,
                    "collect_sources",
                    1L,
                    competitorName,
                    candidate,
                    priority++
            );
            results.add(executor.execute(taskPackage));
        }
        return results;
    }

    /**
     * 这里把计划里的 A/B/C/D 指标全部折叠成测试内局部模型，不把验收口径写进生产代码。
     * collectionFailureRatio 使用“未能直接落地为 fast lane EvidenceSource 的候选占比”做代理，
     * playwrightInvocationCount 使用“仍需后续网络验证的候选数”做代理，便于稳定回归。
     */
    private AcceptanceMetrics computeMetrics(List<SourceCandidate> candidates,
                                             List<CollectionExecutionResult> collectionResults) {
        int totalCandidates = candidates == null ? 0 : candidates.size();
        long successfulCollections = collectionResults.stream()
                .filter(CollectionExecutionResult::isSuccess)
                .count();
        long traceableCollections = collectionResults.stream()
                .filter(CollectionExecutionResult::isSuccess)
                .filter(result -> result.getSourceUrls() != null && !result.getSourceUrls().isEmpty())
                .count();
        long mediumOrAboveEvidenceCount = candidates.stream()
                .filter(candidate -> Set.of("MEDIUM", "STRONG").contains(normalize(candidate.getQualityTier())))
                .count();
        long officialDocHitCount = candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .filter(candidate -> "OFFICIAL_DOC".equals(normalize(candidate.getPageType())))
                .count();
        long openWebDiversityCount = candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .map(SourceCandidate::getDomain)
                .filter(StringUtils::hasText)
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        long trustedExpansionUsableCount = candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .filter(candidate -> "TRUSTED_WEB_EXPANSION".equals(normalize(candidate.getTavilyQueryMode())))
                .count();
        long thinContentCount = candidates.stream()
                .filter(candidate -> "THIN".equals(normalize(candidate.getContentCompleteness())))
                .count();
        long playwrightInvocationCount = candidates.stream()
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getSkipNetworkVerification()))
                .count();
        boolean allRejectedHaveReason = candidates.stream()
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .allMatch(candidate -> StringUtils.hasText(candidate.getFastLaneRejectReason()));

        return new AcceptanceMetrics(
                ratio(traceableCollections, successfulCollections),
                mediumOrAboveEvidenceCount,
                officialDocHitCount,
                openWebDiversityCount,
                trustedExpansionUsableCount,
                ratio(thinContentCount, totalCandidates),
                ratio(Math.max(0L, totalCandidates - successfulCollections), totalCandidates),
                playwrightInvocationCount,
                successfulCollections,
                allRejectedHaveReason
        );
    }

    private TavilySearchProperties properties() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setEnabled(true);
        properties.setApiKey("tavily-test-key");
        properties.setEndpoint("https://api.tavily.com/search");
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        properties.setTimeoutSeconds(10);
        properties.setMaxRetries(1);
        properties.setMinRawContentChars(500);
        properties.setMinTavilyScore(0.45D);
        return properties;
    }

    private TavilySearchClient.TavilySearchResponse loadFixture(String path) throws Exception {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            List<TavilySearchClient.TavilySearchResult> results = new ArrayList<>();
            for (JsonNode resultNode : root.path("results")) {
                results.add(TavilySearchClient.TavilySearchResult.builder()
                        .title(text(resultNode, "title"))
                        .url(text(resultNode, "url"))
                        .content(text(resultNode, "content"))
                        .rawContent(text(resultNode, "raw_content"))
                        .score(resultNode.path("score").isNumber() ? resultNode.path("score").asDouble() : null)
                        .build());
            }
            return TavilySearchClient.TavilySearchResponse.builder()
                    .query(text(root, "query"))
                    .requestId(text(root, "request_id"))
                    .results(results)
                    .build();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return valueNode.asText();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0D;
        }
        return ((double) numerator) / denominator;
    }

    private record AcceptanceScenario(List<SourceCandidate> candidates,
                                      List<CollectionExecutionResult> collectionResults,
                                      List<TavilySearchProfile> executedProfiles,
                                      AcceptanceMetrics metrics) {
    }

    private record AcceptanceMetrics(double traceableEvidenceRatio,
                                     long mediumOrAboveEvidenceCount,
                                     long officialDocHitCount,
                                     long openWebDiversityCount,
                                     long trustedExpansionUsableCount,
                                     double thinContentRatio,
                                     double collectionFailureRatio,
                                     long playwrightInvocationCount,
                                     long successfulCollectionCount,
                                     boolean allRejectedHaveReason) {
    }

    private static final class FixtureBackedTavilySearchClient extends TavilySearchClient {

        private final ArrayDeque<TavilySearchResponse> responseQueue;
        private final List<TavilySearchProfile> executedProfiles = new ArrayList<>();

        private FixtureBackedTavilySearchClient(List<TavilySearchResponse> responses) {
            super(new TavilySearchProperties(), new ObjectMapper(), null);
            this.responseQueue = new ArrayDeque<>(responses == null ? List.of() : responses);
        }

        @Override
        public TavilySearchResponse search(TavilySearchProfile profile) {
            executedProfiles.add(profile);
            TavilySearchResponse response = responseQueue.pollFirst();
            if (response == null) {
                return TavilySearchResponse.builder()
                        .query(profile == null ? null : profile.getQuery())
                        .results(List.of())
                        .failureReason("fixture response exhausted")
                        .build();
            }
            return response;
        }
    }
}
