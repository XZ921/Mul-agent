package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TavilySearchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildJsonPostRequestWithProfilePayload() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "query": "抖音 开放平台 API 官方文档",
                  "request_id": "req-docs-1",
                  "results": []
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        client.search(profile());

        HttpRequest request = client.getLastRequestForTest();
        assertThat(request).isNotNull();
        assertThat(request.uri().toString()).isEqualTo("https://api.tavily.com/search");
        assertThat(request.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(request.headers().firstValue("Accept")).hasValue("application/json");
        assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer tavily-test-key");
        assertThat(request.timeout()).hasValue(java.time.Duration.ofSeconds(12));

        JsonNode root = objectMapper.readTree(client.getLastRequestBodyForTest());
        assertThat(root.path("query").asText()).isEqualTo("抖音 开放平台 API 官方文档");
        assertThat(root.path("search_depth").asText()).isEqualTo("advanced");
        assertThat(root.path("include_raw_content").asBoolean()).isTrue();
        assertThat(root.path("max_results").asInt()).isEqualTo(5);
        assertThat(root.path("include_domains")).hasSize(1);
        assertThat(root.path("include_domains").get(0).asText()).isEqualTo("open.douyin.com");
    }

    @Test
    void shouldRetryRuntimeFailureAndReturnParsedResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "query": "抖音 开放平台 API 官方文档",
                  "request_id": "req-docs-2",
                  "results": [
                    {
                      "title": "平台简介",
                      "url": "https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction",
                      "content": "抖音开放平台概览",
                      "raw_content": "抖音开放平台概览 raw content",
                      "score": 0.68
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("temporary tavily network failure"))
                .thenReturn(response);

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse searchResponse = client.search(profile());

        assertThat(searchResponse.getRequestId()).isEqualTo("req-docs-2");
        assertThat(searchResponse.getResults()).hasSize(1);
        assertThat(searchResponse.getResults().get(0).getUrl())
                .isEqualTo("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldFailOpenWithEmptyResultsWhenHttpStatusIsError() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("{\"error\":\"temporarily unavailable\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse searchResponse = client.search(profile());

        assertThat(searchResponse.getResults()).isEmpty();
        assertThat(searchResponse.getFailureReason()).contains("status=503");
    }

    private TavilySearchProperties properties() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setEnabled(true);
        properties.setApiKey("tavily-test-key");
        properties.setEndpoint("https://api.tavily.com/search");
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        properties.setTimeoutSeconds(12);
        properties.setMaxRetries(1);
        return properties;
    }

    private TavilySearchProfile profile() {
        return TavilySearchProfile.builder()
                .family("DOCS")
                .queryMode(TavilyQueryMode.OFFICIAL_DOCS)
                .query("抖音 开放平台 API 官方文档")
                .includeDomains(List.of("open.douyin.com"))
                .searchDepth("advanced")
                .includeRawContent(true)
                .maxResults(5)
                .build();
    }
}
