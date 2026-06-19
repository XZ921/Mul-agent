package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.source.GithubApiProperties;
import cn.bugstack.competitoragent.source.SearchProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SearchCapabilityReadinessGuardTest {

    @Test
    void shouldFailFastWhenGithubPrimaryOwnerIsRequiredButDisabled() {
        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(true, true),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                new SitemapDiscoveryProperties(),
                emptyLlmClientProvider()
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github api disabled");
    }

    @Test
    void shouldFailFastWhenGithubPrimaryOwnerIsEnabledButTokenMissing() {
        GithubApiProperties githubApiProperties = new GithubApiProperties();
        githubApiProperties.setEnabled(true);
        githubApiProperties.setEndpoint("https://api.github.com");
        githubApiProperties.setApiToken("");

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(true, true),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                githubApiProperties,
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                new SitemapDiscoveryProperties(),
                emptyLlmClientProvider()
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github api token missing");
    }

    @Test
    void shouldSummarizeUnavailableProvidersWithoutHardFailing() {
        GithubApiProperties githubApiProperties = new GithubApiProperties();
        githubApiProperties.setEnabled(true);
        githubApiProperties.setEndpoint("https://api.github.com");
        githubApiProperties.setApiToken("token");

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(true, true),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                githubApiProperties,
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                new SitemapDiscoveryProperties(),
                emptyLlmClientProvider()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isGithubPrimaryRequired()).isTrue();
        assertThat(summary.isGithubPrimaryReady()).isTrue();
        assertThat(summary.getProviders().get("serpapi").isRouteEnabled()).isTrue();
        assertThat(summary.getProviders().get("serpapi").isAvailable()).isFalse();
        assertThat(summary.getProviders().get("serpapi").getUnavailableReason()).isEqualTo("apiKey missing");
        assertThat(summary.getProviders().get("qianfan").isAvailable()).isFalse();
        assertThat(summary.getProviders().get("http").isAvailable()).isFalse();
    }

    @Test
    void shouldDistinguishPlanningBrowserPreviewFromRuntimeBrowserReadiness() {
        GithubApiProperties githubApiProperties = new GithubApiProperties();
        githubApiProperties.setEnabled(true);
        githubApiProperties.setEndpoint("https://api.github.com");
        githubApiProperties.setApiToken("token");

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(true, true),
                createSearchProviderProperties(true, true, true, true, false),
                createSearchBrowserProperties(true),
                githubApiProperties,
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                new SitemapDiscoveryProperties(),
                emptyLlmClientProvider()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isBrowserPreviewRouteEnabled()).isTrue();
        assertThat(summary.isBrowserPreviewFeatureEnabled()).isFalse();
        assertThat(summary.isRuntimeBrowserEnabled()).isTrue();
        assertThat(summary.getProviders().get("browserpreview").isAvailable()).isFalse();
        assertThat(summary.getProviders().get("browserpreview").getUnavailableReason())
                .isEqualTo("browser-preview-enabled=false");
    }

    @Test
    void shouldExposeRssOwnerBoundaryMessageForStartupReadiness() {
        assertThat(SearchCapabilityReadinessGuard.RSS_OWNER_BOUNDARY_MESSAGE)
                .contains("explicit feed urls only")
                .contains("news article urls still go through webpage collection")
                .contains("feed subscription monitoring")
                .contains("cursor and replay are out of current scope");
    }

    @Test
    void shouldExposeDomainDiscoveryReadinessWhenLlmDisabledOrUnavailable() {
        DomainDiscoveryProperties domainDiscoveryProperties = new DomainDiscoveryProperties();
        domainDiscoveryProperties.setLlmEnabled(true);
        domainDiscoveryProperties.setVerificationTimeoutMillis(3200);
        domainDiscoveryProperties.setMaxRetries(2);

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(false, false),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                domainDiscoveryProperties,
                new SitemapDiscoveryProperties(),
                emptyLlmClientProvider()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isDomainDiscoveryLlmEnabled()).isTrue();
        assertThat(summary.isDomainDiscoveryLlmAvailable()).isFalse();
        assertThat(summary.getDomainDiscoveryLlmUnavailableReason()).isEqualTo("llm client unavailable");
        assertThat(summary.getDomainDiscoveryVerificationTimeoutMillis()).isEqualTo(3200);
        assertThat(summary.getDomainDiscoveryMaxRetries()).isEqualTo(2);
    }

    @Test
    void shouldExposeDomainDiscoveryReadinessWhenLlmClientExists() {
        DomainDiscoveryProperties domainDiscoveryProperties = new DomainDiscoveryProperties();
        domainDiscoveryProperties.setLlmEnabled(true);
        domainDiscoveryProperties.setVerificationTimeoutMillis(2800);
        domainDiscoveryProperties.setMaxRetries(1);

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(false, false),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                domainDiscoveryProperties,
                new SitemapDiscoveryProperties(),
                fixedLlmClientProvider(mock(LlmClient.class))
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isDomainDiscoveryLlmEnabled()).isTrue();
        assertThat(summary.isDomainDiscoveryLlmAvailable()).isTrue();
        assertThat(summary.getDomainDiscoveryLlmUnavailableReason()).isNull();
        assertThat(summary.getDomainDiscoveryVerificationTimeoutMillis()).isEqualTo(2800);
        assertThat(summary.getDomainDiscoveryMaxRetries()).isEqualTo(1);
    }

    @Test
    void shouldExposeSitemapDiscoveryReadinessWhenConfigIsValid() {
        SitemapDiscoveryProperties sitemapDiscoveryProperties = createSitemapDiscoveryProperties(true, 3200, 4, 80, 1);

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(false, false),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                sitemapDiscoveryProperties,
                emptyLlmClientProvider()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isSitemapDiscoveryEnabled()).isTrue();
        assertThat(summary.getSitemapTimeoutMillis()).isEqualTo(3200);
        assertThat(summary.getSitemapMaxSitemapsPerDomain()).isEqualTo(4);
        assertThat(summary.getSitemapMaxUrlsPerSitemap()).isEqualTo(80);
        assertThat(summary.getSitemapReadinessWarning()).isNull();
    }

    @Test
    void shouldWarnWhenSitemapDiscoveryConfigIsInvalid() {
        SitemapDiscoveryProperties sitemapDiscoveryProperties = createSitemapDiscoveryProperties(true, 0, 0, 0, -1);

        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(false, false),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties(),
                new DomainDiscoveryProperties(),
                sitemapDiscoveryProperties,
                emptyLlmClientProvider()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isSitemapDiscoveryEnabled()).isTrue();
        assertThat(summary.getSitemapReadinessWarning())
                .contains("timeout invalid")
                .contains("max sitemaps per domain invalid")
                .contains("max urls per sitemap invalid")
                .contains("max retries invalid");
    }

    private SearchProperties createSearchProperties(boolean githubFamilyEnabled, boolean githubPrimaryToolEnabled) {
        SearchProperties searchProperties = new SearchProperties();
        SearchSourceCatalogProperties.SourceFamilyProperties githubFamily =
                searchProperties.getSourceCatalog().resolveFamily("github");
        githubFamily.setEnabled(githubFamilyEnabled);
        githubFamily.setPrimaryTools(githubPrimaryToolEnabled
                ? java.util.List.of("GITHUB_API")
                : java.util.List.of());
        return searchProperties;
    }

    private SearchProviderProperties createSearchProviderProperties(boolean qianfanEnabled,
                                                                    boolean serpApiEnabled,
                                                                    boolean httpEnabled,
                                                                    boolean browserPreviewRouteEnabled,
                                                                    boolean browserPreviewFeatureEnabled) {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.getProviders().put("qianfan", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(qianfanEnabled)
                .failOpen(true)
                .build());
        properties.getProviders().put("serpapi", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(serpApiEnabled)
                .failOpen(true)
                .build());
        properties.getProviders().put("http", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(httpEnabled)
                .failOpen(true)
                .build());
        properties.getProviders().put("browserpreview", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(browserPreviewRouteEnabled)
                .failOpen(true)
                .build());
        properties.setBrowserPreviewEnabled(browserPreviewFeatureEnabled);
        properties.setEndpoint("https://search.example.com");
        properties.setApiKey("");
        return properties;
    }

    private SearchBrowserProperties createSearchBrowserProperties(boolean enabled) {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setEnabled(enabled);
        properties.setEngine("bing");
        return properties;
    }

    private SitemapDiscoveryProperties createSitemapDiscoveryProperties(boolean enabled,
                                                                        int timeoutMillis,
                                                                        int maxSitemapsPerDomain,
                                                                        int maxUrlsPerSitemap,
                                                                        int maxRetries) {
        SitemapDiscoveryProperties properties = new SitemapDiscoveryProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMillis(timeoutMillis);
        properties.setMaxSitemapsPerDomain(maxSitemapsPerDomain);
        properties.setMaxUrlsPerSitemap(maxUrlsPerSitemap);
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    private ObjectProvider<LlmClient> emptyLlmClientProvider() {
        return new FixedObjectProvider<>(null);
    }

    private ObjectProvider<LlmClient> fixedLlmClientProvider(LlmClient llmClient) {
        return new FixedObjectProvider<>(llmClient);
    }

    private static final class FixedObjectProvider<T> implements ObjectProvider<T> {

        private final T value;

        private FixedObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            if (value == null) {
                throw new IllegalStateException("No object available");
            }
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            if (value == null) {
                throw new IllegalStateException("No object available");
            }
            return value;
        }
    }
}
