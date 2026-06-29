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
    void shouldRouteFullSearchSourceRequestToTavilyProvider() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("tavily", "qianfan"));

        RecordingRequestProvider tavily = new RecordingRequestProvider("tavily");
        RecordingRequestProvider qianfan = new RecordingRequestProvider("qianfan");

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(tavily, qianfan),
                new SourceCandidateRanker(),
                new SearchPolicyResolver()
        );

        provider.search(SearchSourceRequest.builder()
                .competitorName("抖音")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("抖音 开放平台 API 官方文档"))
                .preferredProviderKey("tavily")
                .requestPhase(SearchRequestPhase.BOOTSTRAP)
                .build());

        assertThat(tavily.lastRequest).isNotNull();
        assertThat(tavily.lastRequest.getRequestPhase()).isEqualTo(SearchRequestPhase.BOOTSTRAP);
        assertThat(tavily.lastRequest.getPreferredProviderKey()).isEqualTo("tavily");
        assertThat(qianfan.lastRequest).isNull();
    }

    @Test
    void shouldKeepPrimarySatisfiedAndFailOpenSemanticsWhenSearchRequestApiIsUsed() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("github", "tavily"));
        properties.setRunAuxiliaryWhenPrimarySatisfied(false);
        properties.setPrimaryCandidateThreshold(1);

        RecordingRequestProvider github = new RecordingRequestProvider("github");
        github.response = List.of(SourceCandidate.builder()
                .url("https://github.com/example/repo")
                .title("GitHub Repo")
                .sourceType("GITHUB")
                .build());
        ThrowingRequestProvider tavily = new ThrowingRequestProvider("tavily");

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(github, tavily),
                new SourceCandidateRanker(),
                new SearchPolicyResolver()
        );

        List<SourceCandidate> result = provider.search(SearchSourceRequest.builder()
                .competitorName("Acme")
                .requestedScopes(List.of("GITHUB"))
                .requestPhase(SearchRequestPhase.SUPPLEMENT)
                .build());

        assertThat(result).hasSize(1);
        assertThat(github.lastRequest).isNotNull();
        assertThat(tavily.invocationCount).isZero();
    }

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

    @Test
    void shouldPreferTavilyBeforeOtherProvidersWhenUsingDefaultOrder() {
        SearchProviderProperties properties = new SearchProviderProperties();
        List<String> invocationOrder = new ArrayList<>();

        TestProvider tavilyProvider = new TestProvider(
                SearchSourceProviderDescriptor.builder()
                        .providerKey("tavily")
                        .displayName("Tavily Fast Lane")
                        .capabilities(List.of("WEB_SEARCH"))
                        .defaultEnabled(true)
                        .defaultFailOpen(true)
                        .build(),
                true,
                false,
                List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/tavily")
                        .sourceUrls(List.of("https://docs.example.com/tavily"))
                        .build()),
                invocationOrder
        );
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
                List.of(SourceCandidate.builder()
                        .url("https://docs.example.com/qianfan")
                        .sourceUrls(List.of("https://docs.example.com/qianfan"))
                        .build()),
                invocationOrder
        );

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(tavilyProvider, qianfanProvider),
                new SourceCandidateRanker(),
                new SearchPolicyResolver()
        );

        provider.search("Notion AI", List.of("DOCS"));

        assertThat(invocationOrder).containsExactly("tavily", "qianfan");
    }

    private static final class TestProvider implements SearchSourceProvider {

        private final SearchSourceProviderDescriptor descriptor;
        private final boolean available;
        private final boolean shouldThrow;
        private final List<SourceCandidate> candidates;
        private final List<String> invocationOrder;
        private int invocations;

        private TestProvider(SearchSourceProviderDescriptor descriptor,
                             boolean available,
                             boolean shouldThrow,
                             List<SourceCandidate> candidates) {
            this(descriptor, available, shouldThrow, candidates, null);
        }

        private TestProvider(SearchSourceProviderDescriptor descriptor,
                             boolean available,
                             boolean shouldThrow,
                             List<SourceCandidate> candidates,
                             List<String> invocationOrder) {
            this.descriptor = descriptor;
            this.available = available;
            this.shouldThrow = shouldThrow;
            this.candidates = new ArrayList<>(candidates);
            this.invocationOrder = invocationOrder;
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
            if (invocationOrder != null) {
                invocationOrder.add(descriptor.getProviderKey());
            }
            if (shouldThrow) {
                throw new IllegalStateException("mock provider failure");
            }
            return List.copyOf(candidates);
        }

        private int getInvocations() {
            return invocations;
        }
    }

    private static class RecordingRequestProvider implements SearchSourceProvider {

        private final String providerKey;
        private SearchSourceRequest lastRequest;
        private List<SourceCandidate> response = List.of();

        private RecordingRequestProvider(String providerKey) {
            this.providerKey = providerKey;
        }

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey(providerKey)
                    .displayName(providerKey)
                    .capabilities(List.of("WEB_SEARCH"))
                    .defaultEnabled(true)
                    .defaultFailOpen(true)
                    .build();
        }

        @Override
        public List<SourceCandidate> search(SearchSourceRequest request) {
            this.lastRequest = request;
            return response;
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            return response;
        }
    }

    private static final class ThrowingRequestProvider extends RecordingRequestProvider {

        private int invocationCount;

        private ThrowingRequestProvider(String providerKey) {
            super(providerKey);
        }

        @Override
        public List<SourceCandidate> search(SearchSourceRequest request) {
            invocationCount++;
            throw new IllegalStateException("mock provider failure");
        }
    }
}
