package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingSearchSourceProviderTest {

    @Test
    void shouldContinueToFallbackProvidersWhenOneProviderThrowsException() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("qianfan", "serpapi", "http"));

        TestProvider qianfanProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("qianfan")
                        .displayName("千帆搜索")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                true,
                List.of()
        );
        TestProvider serpProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("serpapi")
                        .displayName("SerpApi")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                false,
                List.of(SourceCandidate.builder().url("https://docs.example.com").build())
        );
        TestProvider httpProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("http")
                        .displayName("HTTP Search")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                false,
                List.of(SourceCandidate.builder().url("https://api.example.com").build())
        );
        SourceCandidateRanker ranker = mock(SourceCandidateRanker.class);
        List<SourceCandidate> ranked = List.of(
                SourceCandidate.builder().url("https://docs.example.com").build(),
                SourceCandidate.builder().url("https://api.example.com").build()
        );
        when(ranker.rankAndDeduplicate(anyList())).thenReturn(ranked);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(qianfanProvider, serpProvider, httpProvider),
                ranker,
                new SearchPolicyResolver()
        );

        assertThat(provider.search("Notion AI", List.of("DOCS"))).isEqualTo(ranked);
        assertThat(qianfanProvider.getInvocations()).isEqualTo(1);
        assertThat(serpProvider.getInvocations()).isEqualTo(1);
        assertThat(httpProvider.getInvocations()).isEqualTo(1);
        verify(ranker).rankAndDeduplicate(anyList());
    }

    @Test
    void shouldSkipProviderWhenDisabledOrConfigurationMissing() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("qianfan", "serpapi", "http"));
        properties.getProviders().put("qianfan", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(false)
                .failOpen(true)
                .build());

        TestProvider qianfanProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("qianfan")
                        .displayName("千帆搜索")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                false,
                List.of(SourceCandidate.builder().url("https://www.example.com").build())
        );
        TestProvider serpProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("serpapi")
                        .displayName("SerpApi")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                false,
                false,
                List.of(SourceCandidate.builder().url("https://www.serp.example.com").build())
        );
        TestProvider httpProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("http")
                        .displayName("HTTP Search")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                false,
                List.of(SourceCandidate.builder().url("https://api.example.com").build())
        );
        SourceCandidateRanker ranker = mock(SourceCandidateRanker.class);
        List<SourceCandidate> ranked = List.of(SourceCandidate.builder().url("https://api.example.com").build());
        when(ranker.rankAndDeduplicate(anyList())).thenReturn(ranked);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(qianfanProvider, serpProvider, httpProvider),
                ranker,
                new SearchPolicyResolver()
        );

        assertThat(provider.search("Notion AI", List.of("DOCS"))).isEqualTo(ranked);
        assertThat(qianfanProvider.getInvocations()).isZero();
        assertThat(serpProvider.getInvocations()).isZero();
        assertThat(httpProvider.getInvocations()).isEqualTo(1);
    }

    private static final class TestProvider implements SearchSourceProvider {

        private final SearchSourceProviderDescriptor descriptor;
        private final boolean available;
        private final boolean shouldThrow;
        private final List<SourceCandidate> candidates;
        private int invocations;

        private TestProvider(SearchSourceProviderDescriptor descriptor,
                             boolean available,
                             boolean shouldThrow,
                             List<SourceCandidate> candidates) {
            this.descriptor = descriptor;
            this.available = available;
            this.shouldThrow = shouldThrow;
            this.candidates = new ArrayList<>(candidates);
        }

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            invocations++;
            if (shouldThrow) {
                throw new IllegalStateException("mock provider failure");
            }
            return List.copyOf(candidates);
        }

        private int getInvocations() {
            return invocations;
        }
    }
}
