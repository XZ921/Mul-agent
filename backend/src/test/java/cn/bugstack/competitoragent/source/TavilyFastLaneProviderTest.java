package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfileResolver;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyFastLaneProviderTest {

    @Test
    void shouldMarkBootstrapCandidatesDifferentlyFromSupplementCandidates() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("抖音 开放平台 API 官方文档")
                .requestId("req-bootstrap-1")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("平台简介")
                        .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                        .rawContent("raw content")
                        .score(0.71D)
                        .build()))
                .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .requestPhase(SearchRequestPhase.BOOTSTRAP)
                .build());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getDiscoveryMethod()).isEqualTo("TAVILY_PHASE1_BOOTSTRAP");
    }

    @Test
    void shouldMapPrefetchedOfficialDocsResultToLightweightSourceCandidate() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("抖音 开放平台 API 官方文档")
                .requestId("req-docs-1")
                .results(List.of(
                        TavilySearchClient.TavilySearchResult.builder()
                                .title("平台简介")
                                .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                                .content("抖音开放平台概览")
                                .rawContent("抖音开放平台概览 raw content")
                                .score(0.684D)
                                .build()))
                .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("抖音 开放平台 API 官方文档"))
                .includeDomains(List.of("open.douyin.com"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OFFICIAL_DOCS")
                .build());

        assertThat(candidates).hasSize(1);
        SourceCandidate candidate = candidates.get(0);
        assertThat(candidate.getProviderKey()).isEqualTo("tavily");
        assertThat(candidate.getDiscoveryMethod()).isEqualTo("TAVILY_FAST_LANE");
        assertThat(candidate.getHasPrefetchedContent()).isTrue();
        assertThat(candidate.getPrefetchedContentRef()).startsWith("tavily:req-docs-1:");
        assertThat(candidate.getSourceUrls()).containsExactly("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction");
        assertThat(candidate.getTavilyQueryMode()).isEqualTo("OFFICIAL_DOCS");
        assertThat(candidate.getPrefetchedRawContentLength()).isEqualTo("抖音开放平台概览 raw content".length());
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void shouldFallbackToTrustedExpansionWhenOfficialDocsQueryReturnsNoUsableResults() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("抖音 开放平台 API 官方文档")
                        .requestId("req-empty-1")
                        .results(List.of())
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("抖音 开放平台 API 文档 技术解读 使用说明")
                        .requestId("req-expand-1")
                        .results(List.of(
                                TavilySearchClient.TavilySearchResult.builder()
                                        .title("抖音开放平台接入解读")
                                        .url("https://www.woshipm.com/operate/6205680.html")
                                        .content("第三方技术解读")
                                        .rawContent("第三方技术解读 raw content")
                                        .score(0.79D)
                                        .build()))
                        .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OFFICIAL_DOCS")
                .build());

        assertThat(client.executedProfiles).hasSize(2);
        TavilySearchProfile expansionProfile = client.executedProfiles.get(1);
        assertThat(expansionProfile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(expansionProfile.getIncludeDomains()).isEmpty();
        assertThat(expansionProfile.getExpansionReason()).isNotBlank();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getTavilyQueryMode()).isEqualTo("TRUSTED_WEB_EXPANSION");
        assertThat(candidates.get(0).getSourceUrls()).contains("https://www.woshipm.com/operate/6205680.html");
    }

    @Test
    void shouldCarryFieldEvidenceMetadataFromRequestToCandidateAndPrefetchedContent() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("哔哩哔哩 开放平台 API 官方文档")
                .requestId("req-field-1")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("用户管理 API")
                        .url("https://open.bilibili.com/doc/4/feb66f99")
                        .content("用户管理 API 文档")
                        .rawContent("用户管理 API 文档 raw content")
                        .score(0.86D)
                        .build()))
                .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .fieldEvidenceQueries(List.of(FieldEvidenceQuery.builder()
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .queryIntent("API_DOCS")
                        .sourceType("DOCS")
                        .query("哔哩哔哩 开放平台 API 官方文档")
                        .queryFingerprint("field-query-1")
                        .reason("核心功能 API 文档路径")
                        .build()))
                .preferredProviderKey("tavily")
                .build());

        assertThat(candidates).hasSize(1);
        SourceCandidate candidate = candidates.get(0);
        assertThat(candidate.getFieldName()).isEqualTo("coreFeatures");
        assertThat(candidate.getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
        assertThat(candidate.getQueryIntent()).isEqualTo("API_DOCS");
        assertThat(candidate.getFieldEvidenceQueryFingerprint()).isEqualTo("field-query-1");
        assertThat(candidate.getTavilyQuery()).isEqualTo("哔哩哔哩 开放平台 API 官方文档");
        assertThat(candidate.getDiscoveryMethod()).isEqualTo("TAVILY_FIELD_EVIDENCE_QUERY");

        TavilyPrefetchedContent prefetchedContent = registry.remove(candidate.getPrefetchedContentRef()).orElseThrow();
        assertThat(prefetchedContent.getFieldName()).isEqualTo("coreFeatures");
        assertThat(prefetchedContent.getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
        assertThat(prefetchedContent.getQueryIntent()).isEqualTo("API_DOCS");
        assertThat(prefetchedContent.getFieldEvidenceQueryFingerprint()).isEqualTo("field-query-1");
    }

    @Test
    void shouldExecuteEveryFieldEvidenceQueryInsteadOfOnlyFirstSearchQuery() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("哔哩哔哩 开放平台 API 官方文档")
                        .requestId("req-field-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("用户管理 API")
                                .url("https://open.bilibili.com/doc/4/feb66f99")
                                .rawContent("用户管理 API raw")
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("site:open.bilibili.com API SDK 文档")
                        .requestId("req-field-2")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("授权管理 API")
                                .url("https://open.bilibili.com/doc/4/authorization")
                                .rawContent("授权管理 API raw")
                                .score(0.82D)
                                .build()))
                        .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(
                        FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("哔哩哔哩 开放平台 API 官方文档")
                                .queryFingerprint("q1")
                                .reason("API 官方文档")
                                .build(),
                        FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("SDK_GUIDE")
                                .sourceType("DOCS")
                                .query("site:open.bilibili.com API SDK 文档")
                                .queryFingerprint("q2")
                                .reason("站内 SDK 文档")
                                .build()))
                .build());

        assertThat(client.executedProfiles).hasSize(2);
        assertThat(client.executedProfiles).extracting(TavilySearchProfile::getQuery)
                .containsExactly("哔哩哔哩 开放平台 API 官方文档", "site:open.bilibili.com API SDK 文档");
        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .containsExactly("https://open.bilibili.com/doc/4/feb66f99", "https://open.bilibili.com/doc/4/authorization");
        assertThat(candidates).extracting(SourceCandidate::getFieldEvidenceQueryFingerprint)
                .containsExactly("q1", "q2");
    }
    @Test
    void descriptorShouldRemainFailOpenAndDisabledByDefault() {
        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                new StubTavilySearchClient(),
                new TavilySearchProfileResolver(properties()),
                new TavilyPrefetchedContentRegistry(),
                new ObjectMapper()
        );

        SearchSourceProviderDescriptor descriptor = provider.descriptor();

        assertThat(descriptor.getProviderKey()).isEqualTo("tavily");
        assertThat(descriptor.getDisplayName()).isEqualTo("Tavily Fast Lane");
        assertThat(descriptor.getCapabilities()).contains("PREFETCHED_CONTENT");
        assertThat(descriptor.isEnabled(new SearchProviderProperties())).isFalse();
        assertThat(descriptor.isFailOpen(new SearchProviderProperties())).isTrue();
    }

    private TavilySearchProperties properties() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setEnabled(true);
        properties.setApiKey("tavily-test-key");
        properties.setEndpoint("https://api.tavily.com/search");
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        properties.setTimeoutSeconds(12);
        properties.setMaxRetries(1);
        properties.setMinRawContentChars(500);
        properties.setMinTavilyScore(0.45D);
        return properties;
    }

    private static final class StubTavilySearchClient extends TavilySearchClient {

        private List<TavilySearchResponse> responses = List.of();
        private final java.util.ArrayList<TavilySearchProfile> executedProfiles = new java.util.ArrayList<>();
        private int index = 0;

        private StubTavilySearchClient() {
            super(new TavilySearchProperties(), new ObjectMapper(), null);
        }

        @Override
        public TavilySearchResponse search(TavilySearchProfile profile) {
            executedProfiles.add(profile);
            if (index >= responses.size()) {
                return TavilySearchResponse.builder()
                        .query(profile == null ? null : profile.getQuery())
                        .results(List.of())
                        .failureReason("stub response exhausted")
                        .build();
            }
            return responses.get(index++);
        }
    }
}
