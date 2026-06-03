package cn.bugstack.competitoragent.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSearchSourceProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldParseGenericSearchApiResponse() throws IOException {
        startServer(200, """
                {
                  "results": [
                    {
                      "title": "Notion AI Docs",
                      "url": "https://docs.notion.so/getting-started",
                      "snippet": "Official documentation for Notion AI",
                      "publishedAt": "2026-05-20"
                    }
                  ]
                }
                """);

        HttpSearchSourceProvider provider = new HttpSearchSourceProvider(properties(), new ObjectMapper());

        List<SourceCandidate> candidates = provider.search("Notion AI", List.of("DOCS"));

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("https://docs.notion.so/getting-started", candidate.getUrl());
        assertEquals("DOCS", candidate.getSourceType());
        assertEquals("SEARCH", candidate.getDiscoveryMethod());
        assertEquals("docs.notion.so", candidate.getDomain());
        assertEquals("2026-05-20", candidate.getPublishedAt());
        assertTrue(candidate.getReason().contains("搜索补源命中文档入口"));
        assertEquals(1, candidate.getResultRank());
    }

    @Test
    void shouldRetryAndReturnEmptyWhenSearchApiKeepsFailing() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SearchProviderProperties props = properties();
        props.setMaxRetries(1);
        HttpSearchSourceProvider provider = new HttpSearchSourceProvider(props, new ObjectMapper());

        List<SourceCandidate> candidates = provider.search("Notion AI", List.of("DOCS"));

        assertTrue(candidates.isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    void shouldReturnEmptyWhenEndpointOrApiKeyMissing() {
        SearchProviderProperties props = baseProperties();
        props.setEndpoint("");

        HttpSearchSourceProvider provider = new HttpSearchSourceProvider(props, new ObjectMapper());

        assertTrue(provider.search("Notion AI", List.of("DOCS")).isEmpty());
    }

    @Test
    void shouldUsePostWhenConfigured() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            byte[] body = """
                    {
                      "results": [
                        {
                          "title": "Notion AI Docs",
                          "url": "https://docs.notion.so/getting-started",
                          "snippet": "Official documentation for Notion AI"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SearchProviderProperties props = properties();
        props.setRequestMethod("POST");
        HttpSearchSourceProvider provider = new HttpSearchSourceProvider(props, new ObjectMapper());

        List<SourceCandidate> candidates = provider.search("Notion AI", List.of("DOCS"));

        assertEquals(1, candidates.size());
        assertEquals("https://docs.notion.so/getting-started", candidates.get(0).getUrl());
    }

    @Test
    void shouldNotRetryWhenSearchApiReturnsMethodNotAllowed() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SearchProviderProperties props = properties();
        props.setMaxRetries(2);
        HttpSearchSourceProvider provider = new HttpSearchSourceProvider(props, new ObjectMapper());

        List<SourceCandidate> candidates = provider.search("Notion AI", List.of("DOCS"));

        assertTrue(candidates.isEmpty());
        assertEquals(1, calls.get());
    }

    private void startServer(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            assertFalse(exchange.getRequestURI().getQuery().isBlank());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private SearchProviderProperties properties() {
        SearchProviderProperties props = baseProperties();
        props.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        return props;
    }

    private SearchProviderProperties baseProperties() {
        SearchProviderProperties props = new SearchProviderProperties();
        props.setApiKey("test-key");
        props.setResultsPerScope(3);
        props.setTimeoutSeconds(5);
        props.setMaxRetries(0);
        return props;
    }
}
