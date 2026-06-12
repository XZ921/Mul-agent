package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SearchProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "source-discovery.search.provider-order[0]=serpapi",
                    "source-discovery.search.provider-order[1]=qianfan",
                    "source-discovery.search.providers.serpapi.enabled=true",
                    "source-discovery.search.providers.serpapi.fail-open=true",
                    "source-discovery.search.providers.qianfan.enabled=false",
                    "source-discovery.search.providers.qianfan.fail-open=false",
                    "search.engines.bing.name=Bing",
                    "search.engines.bing.base-url=https://www.bing.com/search",
                    "search.engines.bing.query-param=q",
                    "search.engines.bing.enabled=true",
                    "search.engines.baidu.name=百度",
                    "search.engines.baidu.base-url=https://www.baidu.com/s",
                    "search.engines.baidu.query-param=wd",
                    "search.engines.baidu.enabled=true",
                    "search.browser.engine=baidu",
                    "search.browser.fallback-engines[0]=bing",
                    "search.browser.result-page-timeout-millis=9000",
                    "search.browser.max-content-length-per-page=600",
                    "search.browser.continue-on-browser-unavailable=false",
                    "search.browser.continue-on-search-timeout=true",
                    "search.browser.continue-on-page-collect-failure=false",
                    "search.browser.recover-partial-content-on-timeout=true",
                    "search.source-catalog.families.official.role=PRIMARY_VERTICAL",
                    "search.source-catalog.families.official.primary-tools[0]=WEB_SCRAPER",
                    "search.source-catalog.families.official.primary-tools[1]=JINA_READER",
                    "search.source-catalog.families.news.update-policy.mode=REALTIME_RSS_AND_SCHEDULED_SWEEP",
                    "search.source-catalog.families.github.query-templates[0]=search-github-repository",
                    "search.source-catalog.families.github.query-templates[1]=search-github-release",
                    "serpapi.api-key=test-serp-key",
                    "serpapi.endpoint=https://serpapi.com/search",
                    "serpapi.default-engine=google",
                    "qianfan-search.api-key=test-qianfan-key",
                    "qianfan-search.endpoint=https://qianfan.baidubce.com/v2/ai_search/web_search",
                    "qianfan-search.default-engine=baidu"
            );

    @Test
    void shouldBindSearchEngineAndSerpApiProperties() {
        contextRunner.run(context -> {
            SearchProviderProperties searchProviderProperties = context.getBean(SearchProviderProperties.class);
            SearchEngineProperties searchEngineProperties = context.getBean(SearchEngineProperties.class);
            SearchBrowserProperties searchBrowserProperties = context.getBean(SearchBrowserProperties.class);
            SerpApiProperties serpApiProperties = context.getBean(SerpApiProperties.class);
            QianfanSearchProperties qianfanSearchProperties = context.getBean(QianfanSearchProperties.class);
            SearchProperties searchProperties = context.getBean(SearchProperties.class);

            assertThat(searchProviderProperties.getProviderOrder()).containsExactly("serpapi", "qianfan");
            assertThat(searchProviderProperties.getProviders().get("serpapi").getEnabled()).isTrue();
            assertThat(searchProviderProperties.getProviders().get("serpapi").getFailOpen()).isTrue();
            assertThat(searchProviderProperties.getProviders().get("qianfan").getEnabled()).isFalse();
            assertThat(searchProviderProperties.getProviders().get("qianfan").getFailOpen()).isFalse();
            assertThat(searchEngineProperties.resolve("bing")).isNotNull();
            assertThat(searchEngineProperties.resolve("bing").getBaseUrl())
                    .isEqualTo("https://www.bing.com/search");
            assertThat(searchEngineProperties.resolve("baidu").getQueryParam()).isEqualTo("wd");
            assertThat(searchEngineProperties.resolveAvailableEngineKey("msedge")).isEqualTo("bing");
            assertThat(searchBrowserProperties.getEngine()).isEqualTo("baidu");
            assertThat(searchBrowserProperties.getFallbackEngines()).containsExactly("bing");
            assertThat(searchBrowserProperties.getResultPageTimeoutMillis()).isEqualTo(9000);
            assertThat(searchBrowserProperties.getMaxContentLengthPerPage()).isEqualTo(600);
            assertThat(searchBrowserProperties.isContinueOnBrowserUnavailable()).isFalse();
            assertThat(searchBrowserProperties.isContinueOnSearchTimeout()).isTrue();
            assertThat(searchBrowserProperties.isContinueOnPageCollectFailure()).isFalse();
            assertThat(searchBrowserProperties.isRecoverPartialContentOnTimeout()).isTrue();
            assertThat(serpApiProperties.getApiKey()).isEqualTo("test-serp-key");
            assertThat(serpApiProperties.getDefaultEngine()).isEqualTo("google");
            assertThat(qianfanSearchProperties.getApiKey()).isEqualTo("test-qianfan-key");
            assertThat(qianfanSearchProperties.getDefaultEngine()).isEqualTo("baidu");
            assertThat(qianfanSearchProperties.resolveDefaultEngineKey(searchEngineProperties)).isEqualTo("baidu");
            assertThat(searchProperties.getSourceCatalog().getFamilies()).containsKeys("official", "news", "github");
            assertThat(searchProperties.getSourceCatalog().getFamilies().get("official").getRole())
                    .isEqualTo("PRIMARY_VERTICAL");
            assertThat(searchProperties.getSourceCatalog().getFamilies().get("official").getPrimaryTools())
                    .containsExactly("WEB_SCRAPER", "JINA_READER");
            assertThat(searchProperties.getSourceCatalog().getFamilies().get("news").getUpdatePolicy().getMode())
                    .isEqualTo("REALTIME_RSS_AND_SCHEDULED_SWEEP");
            assertThat(searchProperties.getSourceCatalog().getFamilies().get("github").getQueryTemplates())
                    .containsExactly("search-github-repository", "search-github-release");
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
            SearchProviderProperties.class,
            SearchEngineProperties.class,
            SearchBrowserProperties.class,
            SearchProperties.class,
            SerpApiProperties.class,
            QianfanSearchProperties.class
    })
    static class TestConfiguration {
    }
}
