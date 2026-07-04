package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

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
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("temporary tavily network failure")))
                .thenReturn(CompletableFuture.completedFuture(response));

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse searchResponse = client.search(profile());

        assertThat(searchResponse.getRequestId()).isEqualTo("req-docs-2");
        assertThat(searchResponse.getResults()).hasSize(1);
        assertThat(searchResponse.getResults().get(0).getUrl())
                .isEqualTo("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction");
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldFailOpenWithEmptyResultsWhenHttpStatusIsError() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("{\"error\":\"temporarily unavailable\"}");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response))
                .thenReturn(CompletableFuture.completedFuture(response));

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse searchResponse = client.search(profile());

        assertThat(searchResponse.getResults()).isEmpty();
        assertThat(searchResponse.getFailureReason()).contains("status=503");
    }

    @Test
    void shouldClampHttpRequestTimeoutByPerCallBudget() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "query": "抖音 开放平台 API 官方文档",
                  "request_id": "req-docs-3",
                  "results": []
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        client.search(profile(), 2_000L);

        HttpRequest request = client.getLastRequestForTest();
        assertThat(request).isNotNull();
        assertThat(request.timeout()).isPresent();
        assertThat(request.timeout().orElseThrow().toMillis()).isBetween(1_000L, 2_000L);
    }

    @Test
    void shouldFailOpenWithinPerCallBudgetWhenHttpFutureNeverCompletes() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse response = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.search(profile(), 1_000L));

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getFailureReason()).isEqualTo("tavily timeout after 1000ms");
        assertThat(httpClient.cancelled.get()).isTrue();
        assertThat(httpClient.attemptCount).isEqualTo(1);
    }

    @Test
    void shouldClampExpiredBudgetToShortFailOpenTimeoutInsteadOfFullDefaultTimeout() {
        ImmediateFailingHttpClient httpClient = new ImmediateFailingHttpClient(new IOException("expired budget"));
        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse response = client.search(profile(), -1L);

        assertThat(response.getResults()).isEmpty();
        assertThat(client.getLastRequestForTest()).isNotNull();
        assertThat(client.getLastRequestForTest().timeout()).hasValue(Duration.ofSeconds(5));
        assertThat(httpClient.attemptCount).isEqualTo(1);
    }

    @Test
    void shouldCancelFutureAndPreserveInterruptFlagWhenSearchThreadIsInterrupted() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        TavilySearchClient client = new TavilySearchClient(properties(), objectMapper, httpClient);

        try {
            Thread.currentThread().interrupt();

            TavilySearchClient.TavilySearchResponse response = client.search(profile(), 1_000L);

            assertThat(response.getResults()).isEmpty();
            assertThat(response.getFailureReason()).isEqualTo("tavily interrupted");
            assertThat(httpClient.cancelled.get()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldNotRetryWhenBudgetIsAlreadyExpired() {
        ImmediateFailingHttpClient httpClient = new ImmediateFailingHttpClient(new IOException("expired budget"));
        TavilySearchProperties properties = properties();
        properties.setMaxRetries(3);
        TavilySearchClient client = new TavilySearchClient(properties, objectMapper, httpClient);

        TavilySearchClient.TavilySearchResponse response = client.search(profile(), -1L);

        assertThat(response.getResults()).isEmpty();
        assertThat(httpClient.attemptCount).isEqualTo(1);
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

    private static final class NeverCompletingHttpClient extends HttpClient {

        private final TrackingFuture future = new TrackingFuture();
        private int attemptCount;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            attemptCount++;
            try {
                future.get();
                throw new IOException("unexpected completion");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            attemptCount++;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) future;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        private final class TrackingFuture extends CompletableFuture<HttpResponse<String>> {

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        }
    }

    private static final class ImmediateFailingHttpClient extends HttpClient {

        private final IOException exception;
        private int attemptCount;

        private ImmediateFailingHttpClient(IOException exception) {
            this.exception = exception;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            attemptCount++;
            throw exception;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            attemptCount++;
            return CompletableFuture.failedFuture(exception);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}
