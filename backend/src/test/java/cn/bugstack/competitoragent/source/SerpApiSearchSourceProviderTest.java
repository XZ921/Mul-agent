package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.SerpApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerpApiSearchSourceProviderTest {

    @Test
    void shouldParseOrganicResultsFromSerpApi() throws Exception {
        SerpApiSearchSourceProvider provider = new SerpApiSearchSourceProvider(
                serpApiProperties("https://serpapi.com/search"),
                searchProviderProperties(),
                promptTemplateService(),
                new ObjectMapper()
        );
        Method parseMethod = SerpApiSearchSourceProvider.class.getDeclaredMethod(
                "parseCandidates",
                String.class,
                String.class,
                String.class,
                String.class
        );
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<SourceCandidate> candidates = (List<SourceCandidate>) parseMethod.invoke(
                provider,
                """
                {
                  "organic_results": [
                    {
                      "title": "Notion AI Docs",
                      "link": "https://docs.notion.so/ai",
                      "snippet": "Official documentation for Notion AI",
                      "date": "2026-05-20"
                    }
                  ]
                }
                """,
                "Notion AI",
                "DOCS",
                "Notion AI documentation"
        );

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("SERP_API", candidate.getDiscoveryMethod());
        assertEquals("google", candidate.getSearchEngine());
        assertEquals("https://docs.notion.so/ai", candidate.getUrl());
        assertEquals("docs.notion.so", candidate.getDomain());
        assertEquals("2026-05-20", candidate.getPublishedAt());
        assertTrue(candidate.getReason().contains("SerpAPI 命中文档入口"));
    }

    @Test
    void shouldReturnEmptyWhenApiKeyMissing() {
        SerpApiProperties serpApiProperties = serpApiProperties("https://serpapi.com/search");
        serpApiProperties.setApiKey("");

        SerpApiSearchSourceProvider provider = new SerpApiSearchSourceProvider(
                serpApiProperties,
                searchProviderProperties(),
                promptTemplateService(),
                new ObjectMapper()
        );

        assertTrue(provider.search("Notion AI", List.of("DOCS")).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenEndpointIsNotHttps() {
        SerpApiSearchSourceProvider provider = new SerpApiSearchSourceProvider(
                serpApiProperties("http://serpapi.com/search"),
                searchProviderProperties(),
                promptTemplateService(),
                new ObjectMapper()
        );

        assertTrue(provider.search("Notion AI", List.of("DOCS")).isEmpty());
    }

    private PromptTemplateService promptTemplateService() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();
        return service;
    }

    private SearchProviderProperties searchProviderProperties() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setResultsPerScope(3);
        properties.setMaxRetries(0);
        return properties;
    }

    private SerpApiProperties serpApiProperties(String endpoint) {
        SerpApiProperties properties = new SerpApiProperties();
        properties.setApiKey("test-serp-key");
        properties.setDefaultEngine("google");
        properties.setEndpoint(endpoint);
        return properties;
    }
}
