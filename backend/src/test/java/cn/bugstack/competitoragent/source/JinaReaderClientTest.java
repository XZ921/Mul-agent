package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

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
}
