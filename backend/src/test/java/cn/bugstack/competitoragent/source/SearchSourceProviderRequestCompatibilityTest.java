package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceProviderRequestCompatibilityTest {

    @Test
    void defaultRequestSearchShouldDelegateToLegacyMethod() {
        LegacyProvider provider = new LegacyProvider();
        SearchSourceRequest request = SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("抖音 开放平台 API 官方文档"))
                .preferredDomains(List.of("open.douyin.com"))
                .build();

        provider.search(request);

        assertThat(provider.lastCompetitorName).isEqualTo("抖音");
        assertThat(provider.lastRequestedScopes).containsExactly("DOCS");
    }

    private static class LegacyProvider implements SearchSourceProvider {

        private String lastCompetitorName;
        private List<String> lastRequestedScopes;

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            this.lastCompetitorName = competitorName;
            this.lastRequestedScopes = requestedScopes;
            return List.of();
        }
    }
}
