package cn.bugstack.competitoragent.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapDiscoveryServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void shouldDiscoverHighValueDocCandidatesFromRobotsDeclaredSitemaps() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String rootUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        registerText("/robots.txt", 200, "text/plain", """
                User-agent: *
                Sitemap: %s/sitemap-index.xml
                """.formatted(rootUrl));
        registerText("/sitemap-index.xml", 200, "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap>
                    <loc>%s/docs-sitemap.xml</loc>
                  </sitemap>
                </sitemapindex>
                """.formatted(rootUrl));
        registerText("/docs-sitemap.xml", 200, "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%s/docs/getting-started</loc></url>
                  <url><loc>%s/api/reference</loc></url>
                  <url><loc>%s/pricing</loc></url>
                  <url><loc>%s/blog/release</loc></url>
                </urlset>
                """.formatted(rootUrl, rootUrl, rootUrl, rootUrl));
        server.start();

        SitemapDiscoveryService service = new SitemapDiscoveryService(enabledProperties());

        List<SourceCandidate> candidates = service.discover("Acme AI", "DOCS", List.of(rootUrl));

        assertThat(candidates)
                .extracting(SourceCandidate::getUrl)
                .contains(rootUrl + "/docs/getting-started", rootUrl + "/api/reference")
                .doesNotContain(rootUrl + "/pricing", rootUrl + "/blog/release");
        assertThat(candidates)
                .extracting(SourceCandidate::getDiscoveryMethod)
                .containsOnly("SITEMAP_DISCOVERY");
        assertThat(candidates)
                .allSatisfy(candidate -> {
                    assertThat(candidate.getSourceUrls()).contains(rootUrl + "/robots.txt", rootUrl + "/docs-sitemap.xml");
                    assertThat(candidate.getQualitySignals()).contains("SITEMAP_DISCOVERY");
                });
    }

    @Test
    void shouldFallbackToDefaultSitemapWhenRobotsFileDoesNotDeclareAnySitemap() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String rootUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        registerText("/sitemap.xml", 200, "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%s/pricing</loc></url>
                  <url><loc>%s/docs/overview</loc></url>
                </urlset>
                """.formatted(rootUrl, rootUrl));
        server.start();

        SitemapDiscoveryService service = new SitemapDiscoveryService(enabledProperties());

        List<SourceCandidate> candidates = service.discover("Acme AI", "PRICING", List.of(rootUrl));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.getUrl()).isEqualTo(rootUrl + "/pricing");
            assertThat(candidate.getSourceType()).isEqualTo("PRICING");
            assertThat(candidate.getSourceUrls()).containsExactly(rootUrl + "/sitemap.xml");
        });
    }

    @Test
    void shouldCapUrlsPerSitemapAndExposeTruncationSignal() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String rootUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        registerText("/sitemap.xml", 200, "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%s/docs/getting-started</loc></url>
                  <url><loc>%s/help/faq</loc></url>
                </urlset>
                """.formatted(rootUrl, rootUrl));
        server.start();

        SitemapDiscoveryProperties properties = enabledProperties();
        properties.setMaxUrlsPerSitemap(1);
        SitemapDiscoveryService service = new SitemapDiscoveryService(properties);

        List<SourceCandidate> candidates = service.discover("Acme AI", "DOCS", List.of(rootUrl));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getQualitySignals()).contains("SITEMAP_URL_LIMIT_TRUNCATED");
    }

    private SitemapDiscoveryProperties enabledProperties() {
        SitemapDiscoveryProperties properties = new SitemapDiscoveryProperties();
        properties.setEnabled(true);
        properties.setTimeoutMillis(2000);
        properties.setMaxSitemapsPerDomain(3);
        properties.setMaxUrlsPerSitemap(10);
        properties.setMaxRetries(0);
        return properties;
    }

    private void registerText(String path, int statusCode, String contentType, String body) {
        server.createContext(path, new FixedBodyHandler(statusCode, contentType, body));
    }

    private static final class FixedBodyHandler implements HttpHandler {

        private final int statusCode;
        private final String contentType;
        private final byte[] body;

        private FixedBodyHandler(int statusCode, String contentType, String body) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }
}
