package cn.bugstack.competitoragent.search.tavily;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "tavily-search.enabled=true",
                    "tavily-search.endpoint=https://api.tavily.com/search",
                    "tavily-search.api-key=test-tavily-key",
                    "tavily-search.search-depth=advanced",
                    "tavily-search.include-raw-content=true",
                    "tavily-search.max-results=7",
                    "tavily-search.timeout-seconds=18",
                    "tavily-search.max-retries=3",
                    "tavily-search.min-raw-content-chars=650",
                    "tavily-search.min-tavily-score=0.52"
            );

    @Test
    void shouldBindTavilySearchPropertiesAndExposeReadinessHelpers() {
        contextRunner.run(context -> {
            TavilySearchProperties properties = context.getBean(TavilySearchProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getEndpoint()).isEqualTo("https://api.tavily.com/search");
            assertThat(properties.getApiKey()).isEqualTo("test-tavily-key");
            assertThat(properties.getSearchDepth()).isEqualTo("advanced");
            assertThat(properties.isIncludeRawContent()).isTrue();
            assertThat(properties.getMaxResults()).isEqualTo(7);
            assertThat(properties.getTimeoutSeconds()).isEqualTo(18);
            assertThat(properties.getMaxRetries()).isEqualTo(3);
            assertThat(properties.getMinRawContentChars()).isEqualTo(650);
            assertThat(properties.getMinTavilyScore()).isEqualTo(0.52D);
            assertThat(properties.isConfigured()).isTrue();
            assertThat(properties.isReady()).isTrue();
            assertThat(properties.resolveReadinessFailureMessage()).isNull();
        });
    }

    @Test
    void shouldTreatEnabledTavilyWithoutApiKeyAsNotReady() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "tavily-search.enabled=true",
                        "tavily-search.endpoint=https://api.tavily.com/search",
                        "tavily-search.api-key="
                )
                .run(context -> {
                    TavilySearchProperties properties = context.getBean(TavilySearchProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isConfigured()).isFalse();
                    assertThat(properties.isReady()).isFalse();
                    assertThat(properties.resolveReadinessFailureMessage()).isEqualTo("tavily api key missing");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TavilySearchProperties.class)
    static class TestConfiguration {
    }
}
