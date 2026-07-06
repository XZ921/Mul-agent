package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 第五轮 Task 1 的 JinaReader 客户端红灯测试。
 * 先锁死 reader URL 包装契约，避免后续实现把原始 URL 拼接规则散落在执行器里。
 */
class JinaReaderClientTest {

    @Test
    void shouldWrapOriginalUrlIntoJinaReaderEndpointAndPreserveSourceUrls() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setEndpoint("https://r.jina.ai/http://");
        JinaReaderClient client = new JinaReaderClient(properties, null);

        String resolved = client.resolveReaderUrl("https://docs.example.com/api/reference");

        assertThat(resolved).isEqualTo("https://r.jina.ai/http://docs.example.com/api/reference");
    }

    @Test
    void shouldNotSendAuthorizationHeaderWhenBearerTokenMissing() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setEndpoint("https://r.jina.ai/http://");
        properties.setBearerToken(" ");
        JinaReaderClient client = new JinaReaderClient(properties, null);

        HttpRequest request = client.buildRequest(SourceCollectRequest.builder()
                .url("https://docs.example.com/api/reference")
                .sourceUrls(java.util.List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(request.headers().firstValue("Accept")).hasValue("text/plain");
    }

    @Test
    void shouldSendBearerAuthorizationHeaderWhenBearerTokenConfigured() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setEndpoint("https://r.jina.ai/http://");
        properties.setBearerToken("premium-token");
        JinaReaderClient client = new JinaReaderClient(properties, null);

        HttpRequest request = client.buildRequest(SourceCollectRequest.builder()
                .url("https://docs.example.com/api/reference")
                .sourceUrls(java.util.List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer premium-token");
        assertThat(request.uri().toString())
                .isEqualTo("https://r.jina.ai/http://docs.example.com/api/reference");
    }

    @Test
    void shouldFailOpenWhenHttpFutureNeverCompletesWithinHardTimeoutBudget() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setEndpoint("https://r.jina.ai/http://");
        properties.setTimeoutSeconds(1);
        properties.setMaxRetries(0);
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        JinaReaderClient client = new JinaReaderClient(properties, httpClient);

        PageContentExtractionResult result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.collect(SourceCollectRequest.builder()
                        .url("https://docs.example.com/api/reference")
                        .sourceType("DOCS")
                        .sourceUrls(List.of("https://docs.example.com/api/reference"))
                        .build()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(CollectionFailureKind.RUNTIME_FAILURE.name());
        assertThat(result.getQualitySignals()).contains("LIGHTWEIGHT_RUNTIME_FAILURE");
        assertThat(httpClient.cancelled()).isTrue();
        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
    }

    @Test
    void shouldTightenAnonymousFreeEndpointBudgetByDefault() {
        JinaReaderProperties properties = new JinaReaderProperties();

        assertThat(properties.getTimeoutSeconds()).isEqualTo(8);
        assertThat(properties.getMaxRetries()).isEqualTo(0);
    }

    @Test
    void shouldKeepConfiguredBudgetWhenBearerTokenPresent() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setBearerToken("premium-token");
        properties.setTimeoutSeconds(20);
        properties.setMaxRetries(2);

        HttpRequest request = new JinaReaderClient(properties, null).buildRequest(SourceCollectRequest.builder()
                .url("https://docs.example.com/api/reference")
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(request.timeout()).hasValue(Duration.ofSeconds(20));
        assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer premium-token");
        assertThat(properties.getMaxRetries()).isEqualTo(2);
    }
}
