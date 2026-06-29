package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.TavilySearchClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyDomainHintResolverTest {

    @Test
    void shouldPreferIncludeDomainsThenPreferredDomainsThenVerifiedSeeds() {
        TavilyDomainHintResolver resolver = new TavilyDomainHintResolver();
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .includeDomains(List.of("open.douyin.com"))
                .preferredDomains(List.of("developer.douyin.com"))
                .seedCandidates(List.of(
                        SourceCandidate.builder()
                                .url("https://www.douyin.com/rule/policy")
                                .domain("www.douyin.com")
                                .sourceType("OFFICIAL")
                                .verified(Boolean.TRUE)
                                .sourceUrls(List.of("https://www.douyin.com/rule/policy"))
                                .build(),
                        SourceCandidate.builder()
                                .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                                .domain("open.douyin.com")
                                .sourceType("DOCS")
                                .verified(Boolean.TRUE)
                                .sourceUrls(List.of("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction"))
                                .build()))
                .build();

        DomainHintSet hintSet = resolver.resolve(request, List.of());

        assertThat(hintSet.getCompetitorName()).isEqualTo("抖音");
        assertThat(hintSet.getDomains()).extracting(DomainHint::getDomain)
                .containsExactly("open.douyin.com", "developer.douyin.com", "www.douyin.com");
        assertThat(hintSet.getDomains().get(0).getSource()).isEqualTo("USER_OR_ORCHESTRATION");
        assertThat(hintSet.getDomains().get(0).getConfidence()).isEqualTo(0.95D);
        assertThat(hintSet.getDomains().get(1).getSource()).isEqualTo("CONFIG_HINT");
        assertThat(hintSet.getDomains().get(1).getConfidence()).isEqualTo(0.85D);
        assertThat(hintSet.getDomains().get(2).getSource()).isEqualTo("VERIFIED_CANDIDATE");
        assertThat(hintSet.getDomains().get(2).getConfidence()).isEqualTo(0.80D);
    }

    @Test
    void shouldAppendOpenWebBootstrapDomainsWhenNoExplicitOfficialAnchorExists() {
        TavilyDomainHintResolver resolver = new TavilyDomainHintResolver();
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("哔哩哔哩")
                .requestedScopes(List.of("DOCS"))
                .build();
        TavilySearchClient.TavilySearchResponse bootstrapResponse = TavilySearchClient.TavilySearchResponse.builder()
                .query("哔哩哔哩 开放平台 官方文档")
                .requestId("bootstrap-1")
                .results(List.of(
                        TavilySearchClient.TavilySearchResult.builder()
                                .title("哔哩哔哩开放平台")
                                .url("https://openhome.bilibili.com/doc")
                                .content("开放平台文档")
                                .rawContent("开放平台文档 raw content")
                                .score(0.80D)
                                .build(),
                        TavilySearchClient.TavilySearchResult.builder()
                                .title("哔哩哔哩开放平台 - 第二入口")
                                .url("https://open.bilibili.com/doc/4/8673959e-f7bb-56e6-6e68-d225f971b81b")
                                .content("开放平台文档 second")
                                .rawContent("开放平台文档 second raw")
                                .score(0.73D)
                                .build(),
                        TavilySearchClient.TavilySearchResult.builder()
                                .title("B站创作者中心")
                                .url("https://creator.bilibili.com")
                                .content("创作者中心")
                                .rawContent("")
                                .score(0.55D)
                                .build()))
                .build();

        DomainHintSet hintSet = resolver.resolve(request, List.of(bootstrapResponse));

        assertThat(hintSet.getDomains()).extracting(DomainHint::getDomain)
                .contains("openhome.bilibili.com", "open.bilibili.com", "creator.bilibili.com");
        assertThat(hintSet.getDomains()).allSatisfy(hint -> {
            assertThat(hint.getSource()).isEqualTo("OPEN_WEB_BOOTSTRAP");
            assertThat(hint.getConfidence()).isEqualTo(0.60D);
            assertThat(hint.getSourceUrls()).isNotEmpty();
        });
    }
}
