package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.GithubApiProperties;
import cn.bugstack.competitoragent.source.SearchProviderProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCapabilityReadinessGuardTest {

    @Test
    void shouldFailFastWhenGithubPrimaryOwnerIsRequiredButDisabled() {
        SearchCapabilityReadinessGuard guard = new SearchCapabilityReadinessGuard(
                createSearchProperties(true, true),
                createSearchProviderProperties(true, true, true, true, true),
                createSearchBrowserProperties(true),
                new GithubApiProperties(),
                new SerpApiProperties(),
                new QianfanSearchProperties()
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
                new QianfanSearchProperties()
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
                new QianfanSearchProperties()
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
                new QianfanSearchProperties()
        );

        SearchCapabilityReadinessGuard.ReadinessSummary summary = guard.buildSummary();

        assertThat(summary.isBrowserPreviewRouteEnabled()).isTrue();
        assertThat(summary.isBrowserPreviewFeatureEnabled()).isFalse();
        assertThat(summary.isRuntimeBrowserEnabled()).isTrue();
        assertThat(summary.getProviders().get("browserpreview").isAvailable()).isFalse();
        assertThat(summary.getProviders().get("browserpreview").getUnavailableReason())
                .isEqualTo("browser-preview-enabled=false");
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
}
