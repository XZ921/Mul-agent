package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "search.engines.bing.name=Bing",
                    "search.engines.bing.base-url=https://www.bing.com/search",
                    "search.engines.bing.query-param=q",
                    "search.engines.bing.enabled=true",
                    "search.engines.baidu.name=百度",
                    "search.engines.baidu.base-url=https://www.baidu.com/s",
                    "search.engines.baidu.query-param=wd",
                    "search.engines.baidu.enabled=true",
                    "search.browser.engine=bing",
                    "search.browser.fallback-engines[0]=baidu",
                    "serpapi.api-key=test-serp-key",
                    "serpapi.endpoint=https://serpapi.com/search",
                    "serpapi.default-engine=google"
            );

    @Test
    void shouldBindSearchEngineAndSerpApiProperties() {
        contextRunner.run(context -> {
            SearchEngineProperties searchEngineProperties = context.getBean(SearchEngineProperties.class);
            SearchBrowserProperties searchBrowserProperties = context.getBean(SearchBrowserProperties.class);
            SerpApiProperties serpApiProperties = context.getBean(SerpApiProperties.class);

            assertThat(searchEngineProperties.resolve("bing")).isNotNull();
            assertThat(searchEngineProperties.resolve("bing").getBaseUrl())
                    .isEqualTo("https://www.bing.com/search");
            assertThat(searchEngineProperties.resolve("baidu").getQueryParam()).isEqualTo("wd");
            assertThat(searchBrowserProperties.getFallbackEngines()).containsExactly("baidu");
            assertThat(serpApiProperties.getApiKey()).isEqualTo("test-serp-key");
            assertThat(serpApiProperties.getDefaultEngine()).isEqualTo("google");
        });
    }

    @Test
    void shouldRejectNonHttpsSearchConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class, SearchSecurityConfigurationGuard.class)
                .withPropertyValues(
                        "search.engines.bing.name=Bing",
                        "search.engines.bing.base-url=http://www.bing.com/search",
                        "search.engines.bing.query-param=q",
                        "search.engines.bing.enabled=true",
                        "serpapi.endpoint=https://serpapi.com/search"
                )
                .run(context -> {
                    SearchSecurityConfigurationGuard guard = context.getBean(SearchSecurityConfigurationGuard.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.run(null))
                            .hasMessageContaining("base-url 必须使用 https URL");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SearchEngineProperties.class,
            SearchBrowserProperties.class,
            SerpApiProperties.class
    })
    static class TestConfiguration {
    }
}
