package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfileResolver;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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
    void shouldNotFallbackWhenOfficialDocsQueryAlreadyReturnsUsableOfficialPage() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("抖音 开放平台 API 官方文档")
                        .requestId("req-official-page-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("抖音开发者生态动态")
                                .url("https://static.douyin.com/news/platform-ecosystem-update")
                                .content("抖音开发者生态动态")
                                .rawContent("抖音开发者生态动态 ".repeat(260))
                                .score(0.83D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("抖音 开放平台 API 文档 技术解读 使用说明")
                        .requestId("req-official-page-expand")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("不应触发的扩展结果")
                                .url("https://example.com/should-not-expand")
                                .content("unexpected expansion")
                                .rawContent("unexpected expansion ".repeat(260))
                                .score(0.75D)
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
                .includeDomains(List.of("static.douyin.com"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OFFICIAL_DOCS")
                .build());

        assertThat(client.executedProfiles).hasSize(1);
        assertThat(client.executedProfiles.get(0).getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getDomain()).isEqualTo("static.douyin.com");
        assertThat(candidates.get(0).getPageType()).isEqualTo("ARTICLE");
        assertThat(candidates.get(0).getFastLaneUsable()).isTrue();
    }

    @Test
    void shouldFallbackToTrustedExpansionWhenOfficialDocsOnlyReturnsRootEntryPage() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("Douyin open platform official docs")
                        .requestId("req-root-entry-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Douyin Open Platform")
                                .url("https://open.douyin.com")
                                .content("developer entry shell")
                                .rawContent("Douyin Open Platform developer entry shell ".repeat(120))
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("Douyin open platform docs technical guide")
                        .requestId("req-root-entry-expand")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Douyin open platform API guide")
                                .url("https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission")
                                .content("API guide")
                                .rawContent("API guide ".repeat(260))
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
                .competitorName("Douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("Douyin open platform official docs"))
                .includeDomains(List.of("open.douyin.com"))
                .preferredProviderKey("tavily")
                .preferredQueryMode("OFFICIAL_DOCS")
                .build());

        assertThat(client.executedProfiles).hasSize(2);
        assertThat(client.executedProfiles.get(0).getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
        assertThat(client.executedProfiles.get(1).getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(candidates)
                .extracting(SourceCandidate::getUrl)
                .contains("https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission");
    }

    @Test
    void shouldUseSearchFirstTrustedExpansionForPrimaryOfficialScopeAndKeepQueryOverride() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("custom docs analysis query")
                .requestId("req-search-first-docs")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("Douyin open platform analysis")
                        .url("https://example.com/douyin-open-platform-analysis")
                        .content("third-party analysis")
                        .rawContent("third-party analysis ".repeat(150))
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
                .competitorName("Douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("custom docs analysis query"))
                .includeDomains(List.of("open.douyin.com"))
                .preferredProviderKey("tavily")
                .build());

        assertThat(client.executedProfiles).hasSize(1);
        TavilySearchProfile primaryProfile = client.executedProfiles.get(0);
        assertThat(primaryProfile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(primaryProfile.getQuery()).isEqualTo("custom docs analysis query");
        assertThat(primaryProfile.getIncludeDomains()).isEmpty();
        assertThat(primaryProfile.getOfficialDomains()).containsExactly("open.douyin.com");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getTavilyQueryMode()).isEqualTo("TRUSTED_WEB_EXPANSION");
        assertThat(candidates.get(0).getFastLaneUsable()).isTrue();
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

        assertThat(candidate.getCandidateDiscoveryUsable()).isTrue();
        assertThat(candidate.getHasPrefetchedContent()).isFalse();
        assertThat(candidate.getPrefetchedContentRef()).isNull();
        assertThat(registry.size()).isZero();
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
        assertThat(client.queryBudgetMillisOverrides).isEmpty();
        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .containsExactly("https://open.bilibili.com/doc/4/feb66f99", "https://open.bilibili.com/doc/4/authorization");
        assertThat(candidates).extracting(SourceCandidate::getFieldEvidenceQueryFingerprint)
                .containsExactly("q1", "q2");
    }

    @Test
    void shouldExecuteFieldEvidenceQueriesFromTheirOwnSourceTypesWhenOuterScopeIsOfficial() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili api docs")
                        .requestId("req-docs")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili API docs")
                                .url("https://open.bilibili.com/doc/4/api")
                                .rawContent("docs raw ".repeat(260))
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili developer review")
                        .requestId("req-open-web")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili developer review")
                                .url("https://example.com/bilibili-open-platform-review")
                                .rawContent("review raw ".repeat(260))
                                .score(0.88D)
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
                .requestedScopes(List.of("OFFICIAL"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(
                        FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("bilibili api docs")
                                .queryFingerprint("q-docs")
                                .reason("docs query")
                                .build(),
                        FieldEvidenceQuery.builder()
                                .fieldName("weaknesses")
                                .evidencePathKey("PUBLIC_REVIEW_OR_NEWS")
                                .queryIntent("THIRD_PARTY_REVIEW")
                                .sourceType("OPEN_WEB")
                                .query("bilibili developer review")
                                .queryFingerprint("q-open-web")
                                .reason("open web query")
                                .build()))
                .build());

        assertThat(client.executedProfiles).hasSize(2);
        assertThat(client.executedProfiles).extracting(TavilySearchProfile::getFamily)
                .containsExactly("DOCS", "OPEN_WEB");
        assertThat(candidates).extracting(SourceCandidate::getFieldEvidenceQueryFingerprint)
                .containsExactly("q-docs", "q-open-web");
    }

    @Test
    void shouldMergeFieldEvidenceAuditAcrossExpandedScopes() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili api docs")
                        .requestId("req-docs")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili API docs")
                                .url("https://open.bilibili.com/doc/4/api")
                                .rawContent("docs raw ".repeat(260))
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili developer review")
                        .requestId("req-open-web")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili developer review")
                                .url("https://example.com/bilibili-open-platform-review")
                                .rawContent("review raw ".repeat(260))
                                .score(0.88D)
                                .build()))
                        .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("OFFICIAL"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(
                        FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("bilibili api docs")
                                .queryFingerprint("q-docs")
                                .reason("docs query")
                                .build(),
                        FieldEvidenceQuery.builder()
                                .fieldName("weaknesses")
                                .evidencePathKey("PUBLIC_REVIEW_OR_NEWS")
                                .queryIntent("THIRD_PARTY_REVIEW")
                                .sourceType("OPEN_WEB")
                                .query("bilibili developer review")
                                .queryFingerprint("q-open-web")
                                .reason("open web query")
                                .build()))
                .build();

        provider.search(request);

        assertThat(client.executedProfiles).extracting(TavilySearchProfile::getFamily)
                .containsExactly("DOCS", "OPEN_WEB");
        assertThat(request.getTavilyFastLaneAudit()).isNotNull();
        assertThat(request.getTavilyFastLaneAudit().getQueriesSent()).isEqualTo(2);
        assertThat(request.getTavilyFastLaneAudit().getTavilyRequestIds())
                .containsExactly("req-docs", "req-open-web");
        assertThat(request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint", "queryIntent", "tavilyRequestId")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("q-docs", "API_DOCS", "req-docs"),
                        org.assertj.core.groups.Tuple.tuple("q-open-web", "THIRD_PARTY_REVIEW", "req-open-web")
                );
    }

    @Test
    void shouldNotOverwriteExistingFieldEvidenceAuditWithLaterEmptyScopeAudit() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("bilibili api docs")
                .requestId("req-docs-only")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("Bilibili API docs")
                        .url("https://open.bilibili.com/doc/4/api")
                        .rawContent("docs raw ".repeat(260))
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
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS", "OFFICIAL"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(FieldEvidenceQuery.builder()
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .queryIntent("API_DOCS")
                        .sourceType("DOCS")
                        .query("bilibili api docs")
                        .queryFingerprint("q-docs")
                        .reason("docs query")
                        .build()))
                .build();

        provider.search(request);

        assertThat(client.executedProfiles).extracting(TavilySearchProfile::getFamily)
                .containsExactly("DOCS");
        assertThat(request.getTavilyFastLaneAudit()).isNotNull();
        assertThat(request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint", "tavilyRequestId")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("q-docs", "req-docs-only"));
    }

    @Test
    void shouldRecordScopeExpansionSourcesInsideTavilyAudit() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili api docs")
                        .requestId("req-docs")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili API docs")
                                .url("https://open.bilibili.com/doc/4/api")
                                .rawContent("docs raw ".repeat(260))
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili developer review")
                        .requestId("req-open-web")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili developer review")
                                .url("https://example.com/bilibili-open-platform-review")
                                .rawContent("review raw ".repeat(260))
                                .score(0.88D)
                                .build()))
                        .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("OFFICIAL"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(
                        FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("bilibili api docs")
                                .queryFingerprint("q-docs")
                                .reason("docs query")
                                .build(),
                        FieldEvidenceQuery.builder()
                                .fieldName("weaknesses")
                                .evidencePathKey("PUBLIC_REVIEW_OR_NEWS")
                                .queryIntent("THIRD_PARTY_REVIEW")
                                .sourceType("OPEN_WEB")
                                .query("bilibili developer review")
                                .queryFingerprint("q-open-web")
                                .reason("open web query")
                                .build()))
                .build();

        provider.search(request);

        TavilyFastLaneAudit audit = request.getTavilyFastLaneAudit();
        assertThat(audit).isNotNull();
        Map<String, Object> auditView = new ObjectMapper().convertValue(
                audit,
                new TypeReference<Map<String, Object>>() {
                }
        );
        assertThat(auditView.get("requestedScopes")).isEqualTo(List.of("OFFICIAL"));
        assertThat(auditView.get("effectiveScopes")).isEqualTo(List.of("OFFICIAL", "DOCS", "OPEN_WEB"));
        assertThat(auditView.get("scopeExpansionSources")).isEqualTo(Map.of(
                "DOCS", List.of("fieldQuery:q-docs|field=coreFeatures|path=DOCS_API_GUIDE|intent=API_DOCS"),
                "OPEN_WEB", List.of("fieldQuery:q-open-web|field=weaknesses|path=PUBLIC_REVIEW_OR_NEWS|intent=THIRD_PARTY_REVIEW")
        ));
    }

    @Test
    void shouldUseStageOneDefaultScopesWithoutPricingWhenRequestedScopesAreEmpty() {
        StubTavilySearchClient client = new StubTavilySearchClient();
        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                new TavilyPrefetchedContentRegistry(),
                new ObjectMapper()
        );

        provider.search(SearchSourceRequest.builder()
                .competitorName("Linear")
                .requestedScopes(List.of())
                .preferredProviderKey("tavily")
                .build());

        assertThat(client.executedProfiles)
                .extracting(TavilySearchProfile::getFamily)
                .contains("OFFICIAL", "DOCS", "NEWS", "REVIEW")
                .doesNotContain("PRICING");
    }

    @Test
    void shouldNormalizeChinesePricingScopeToPricingFamily() {
        StubTavilySearchClient client = new StubTavilySearchClient();
        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                new TavilyPrefetchedContentRegistry(),
                new ObjectMapper()
        );

        provider.search(SearchSourceRequest.builder()
                .competitorName("Linear")
                .requestedScopes(List.of("定价页"))
                .preferredProviderKey("tavily")
                .build());

        assertThat(client.executedProfiles)
                .extracting(TavilySearchProfile::getFamily)
                .contains("PRICING")
                .doesNotContain("OFFICIAL", "DOCS", "NEWS", "REVIEW");
    }

    @Test
    void shouldSkipRemainingQueriesAfterFieldCandidateCoverageMet() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili official profile")
                        .requestId("req-coverage-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili open platform profile")
                                .url("https://open.bilibili.com/platform/profile")
                                .content("official profile")
                                .score(0.87D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("bilibili developer review")
                        .requestId("req-coverage-2")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("Bilibili open platform review")
                                .url("https://example.com/bilibili-open-platform-review")
                                .content("third-party review")
                                .score(0.85D)
                                .build()))
                        .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("bilibili")
                .requestedScopes(List.of("OFFICIAL"))
                .preferredProviderKey("tavily")
                .fieldEvidenceQueries(List.of(
                        fieldQuery("summary", "OFFICIAL", "q1", "bilibili official profile"),
                        fieldQuery("summary", "REVIEW", "q2", "bilibili developer review"),
                        fieldQuery("summary", "NEWS", "q3", "bilibili latest news")))
                .build();

        List<SourceCandidate> candidates = provider.search(request);

        assertThat(client.executedProfiles).hasSize(2);
        assertThat(client.executedProfiles).extracting(TavilySearchProfile::getQuery)
                .containsExactly("bilibili official profile", "bilibili developer review");
        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .containsExactly(
                        "https://open.bilibili.com/platform/profile",
                        "https://example.com/bilibili-open-platform-review");
        assertThat(request.getTavilyFastLaneAudit()).isNotNull();
        assertThat(request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint", "status", "skipReason")
                .containsExactly(
                        tuple("q1", "SUCCESS", null),
                        tuple("q2", "SUCCESS", null),
                        tuple("q3", "SKIPPED", "SKIPPED_FIELD_CANDIDATE_COVERAGE_MET"));
    }

    @Test
    void shouldStopLaunchingLaterFieldEvidenceQueriesAfterDeadlineExceeded() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.sleepBeforeReturnMillis = 1_300L;
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("哔哩哔哩 开放平台 API 官方文档")
                        .requestId("req-deadline-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("用户管理 API")
                                .url("https://open.bilibili.com/doc/4/feb66f99")
                                .rawContent("用户管理 API raw")
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("site:open.bilibili.com API SDK 文档")
                        .requestId("req-deadline-2")
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
                .fieldEvidenceExecutionDeadlineEpochMillis(System.currentTimeMillis() + 1_100L)
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

        assertThat(client.executedProfiles).hasSize(1);
        assertThat(candidates).extracting(SourceCandidate::getFieldEvidenceQueryFingerprint)
                .containsExactly("q1");
    }

    @Test
    void shouldSkipFirstFieldEvidenceQueryWhenDeadlineAlreadyExpired() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("expired budget should not execute")
                .requestId("req-expired-first")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("unexpected")
                        .url("https://open.bilibili.com/doc/unexpected")
                        .rawContent("unexpected raw")
                        .score(0.2D)
                        .build()))
                .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .fieldEvidenceExecutionDeadlineEpochMillis(System.currentTimeMillis() - 1L)
                .fieldEvidenceQueries(List.of(fieldQuery("q-expired-first", "哔哩哔哩 开放平台 API 官方文档")))
                .build();

        List<SourceCandidate> candidates = provider.search(request);

        assertThat(candidates).isEmpty();
        assertThat(client.executedProfiles).isEmpty();
        assertThat(client.queryBudgetMillisOverrides).isEmpty();
        assertThat(request.getTavilyFastLaneAudit()).isNotNull();
        assertThat(request.getTavilyFastLaneAudit().getQueriesSent()).isZero();
        assertThat(request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint", "status", "resultCount", "skipReason", "failureReason")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "q-expired-first", "SKIPPED", 0, "SKIPPED_BUDGET_EXHAUSTED", null)
                );
    }

    @Test
    void shouldPassRemainingBudgetToClientForFieldEvidenceQuery() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("哔哩哔哩 开放平台 API 官方文档")
                .requestId("req-budget-1")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("用户管理 API")
                        .url("https://open.bilibili.com/doc/4/feb66f99")
                        .rawContent("用户管理 API raw")
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

        long deadline = System.currentTimeMillis() + 2_000L;
        provider.search(SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .fieldEvidenceExecutionDeadlineEpochMillis(deadline)
                .fieldEvidenceQueries(List.of(FieldEvidenceQuery.builder()
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .queryIntent("API_DOCS")
                        .sourceType("DOCS")
                        .query("哔哩哔哩 开放平台 API 官方文档")
                        .queryFingerprint("q1")
                        .reason("API 官方文档")
                        .build()))
                .build());

        assertThat(client.queryBudgetMillisOverrides).hasSize(1);
        assertThat(client.queryBudgetMillisOverrides.get(0)).isPositive();
        assertThat(client.queryBudgetMillisOverrides.get(0)).isLessThanOrEqualTo(2_000L);
    }

    @Test
    void shouldRecordPerQueryAuditForExecutedFailedAndBudgetSkippedFieldEvidenceQueries() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        StubTavilySearchClient client = new StubTavilySearchClient();
        client.responses = List.of(
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("哔哩哔哩 开放平台 API 官方文档")
                        .requestId("req-audit-1")
                        .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                                .title("用户管理 API")
                                .url("https://open.bilibili.com/doc/4/feb66f99")
                                .rawContent("用户管理 API raw")
                                .score(0.86D)
                                .build()))
                        .build(),
                TavilySearchClient.TavilySearchResponse.builder()
                        .query("site:open.bilibili.com API SDK 文档")
                        .failureReason("HTTP 429")
                        .results(List.of())
                        .build());
        client.sleepBeforeReturnMillisByCall = List.of(0L, 1_100L);

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties(),
                client,
                new TavilySearchProfileResolver(properties()),
                registry,
                new ObjectMapper()
        );
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .preferredProviderKey("tavily")
                .fieldEvidenceExecutionDeadlineEpochMillis(System.currentTimeMillis() + 1_500L)
                .fieldEvidenceQueries(List.of(
                        fieldQuery("q-success", "哔哩哔哩 开放平台 API 官方文档"),
                        fieldQuery("q-failed", "site:open.bilibili.com API SDK 文档"),
                        fieldQuery("q-skipped", "哔哩哔哩 API 常见问题")))
                .build();

        provider.search(request);

        assertThat(request.getTavilyFastLaneAudit()).isNotNull();
        assertThat(request.getTavilyFastLaneAudit().getQueriesSent()).isEqualTo(2);
        assertThat(request.getTavilyFastLaneAudit().getFastLaneUsableCount()).isZero();
        assertThat(request.getTavilyFastLaneAudit().getWinnerRawFetchCount()).isZero();
        assertThat(request.getTavilyFastLaneAudit().getFieldDistribution()).isEqualTo(Map.of("coreFeatures", 3));
        assertThat(request.getTavilyFastLaneAudit().getSourceTypeDistribution()).isEqualTo(Map.of("DOCS", 3));
        assertThat(request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint",
                        "sourceType",
                        "queryMode",
                        "profileStage",
                        "searchDepth",
                        "includeRawContent",
                        "status",
                        "resultCount",
                        "skipReason",
                        "failureReason")
                .containsExactly(
                        tuple("q-success", "DOCS", "OFFICIAL_DOCS", "FIELD_EVIDENCE_DISCOVERY",
                                "basic", false, "SUCCESS", 1, null, null),
                        tuple("q-failed", "DOCS", "OFFICIAL_DOCS", "FIELD_EVIDENCE_DISCOVERY",
                                "basic", false, "FAILED", 0, null, "HTTP 429"),
                        tuple("q-skipped", "DOCS", "OFFICIAL_DOCS", "FIELD_EVIDENCE_DISCOVERY",
                                "basic", false, "SKIPPED", 0, "SKIPPED_BUDGET_EXHAUSTED", null)
                );
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

    private FieldEvidenceQuery fieldQuery(String fingerprint, String queryText) {
        return FieldEvidenceQuery.builder()
                .fieldName("coreFeatures")
                .evidencePathKey("DOCS_API_GUIDE")
                .queryIntent("API_DOCS")
                .sourceType("DOCS")
                .query(queryText)
                .queryFingerprint(fingerprint)
                .reason("字段审计测试")
                .build();
    }

    private FieldEvidenceQuery fieldQuery(String fieldName, String sourceType, String fingerprint, String queryText) {
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .evidencePathKey("OPEN_PLATFORM_PROFILE")
                .queryIntent("SUMMARY")
                .sourceType(sourceType)
                .query(queryText)
                .queryFingerprint(fingerprint)
                .reason("字段候选覆盖测试")
                .build();
    }

    private static final class StubTavilySearchClient extends TavilySearchClient {

        private List<TavilySearchResponse> responses = List.of();
        private final java.util.ArrayList<TavilySearchProfile> executedProfiles = new java.util.ArrayList<>();
        private final java.util.ArrayList<Long> queryBudgetMillisOverrides = new ArrayList<>();
        private int index = 0;
        private long sleepBeforeReturnMillis = 0L;
        private List<Long> sleepBeforeReturnMillisByCall = List.of();

        private StubTavilySearchClient() {
            super(new TavilySearchProperties(), new ObjectMapper(), null);
        }

        @Override
        public TavilySearchResponse search(TavilySearchProfile profile) {
            executedProfiles.add(profile);
            long effectiveSleepMillis = sleepBeforeReturnMillis;
            if (sleepBeforeReturnMillisByCall != null && executedProfiles.size() <= sleepBeforeReturnMillisByCall.size()) {
                Long configuredSleepMillis = sleepBeforeReturnMillisByCall.get(executedProfiles.size() - 1);
                effectiveSleepMillis = configuredSleepMillis == null ? 0L : configuredSleepMillis;
            }
            if (effectiveSleepMillis > 0L) {
                try {
                    Thread.sleep(effectiveSleepMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stub sleep interrupted", exception);
                }
            }
            if (index >= responses.size()) {
                return TavilySearchResponse.builder()
                        .query(profile == null ? null : profile.getQuery())
                        .results(List.of())
                        .failureReason("stub response exhausted")
                        .build();
            }
            return responses.get(index++);
        }

        @Override
        public TavilySearchResponse search(TavilySearchProfile profile, long queryBudgetMillis) {
            queryBudgetMillisOverrides.add(queryBudgetMillis);
            return search(profile);
        }
    }
}
