package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
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

class DirectHtmlReaderClientTest {

    @Test
    void shouldExtractReadableTextAndMarkdownLinksFromStaticHtml() throws Exception {
        DirectHtmlReaderProperties properties = new DirectHtmlReaderProperties();
        properties.setMinimumContentLength(20);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                <html>
                  <head><title>开放平台文档</title></head>
                  <body>
                    <main>
                      <h1>账号授权</h1>
                      <p>这里是开放平台账号授权 API 正文，包含 scope、token、回调地址配置。</p>
                      <a href="/doc/auth">账号授权详情</a>
                    </main>
                  </body>
                </html>
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DirectHtmlReaderClient client = new DirectHtmlReaderClient(properties, httpClient);
        PageContentExtractionResult result = client.collect(SourceCollectRequest.builder()
                .url("https://open.example.com/doc")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://open.example.com/doc"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTitle()).isEqualTo("开放平台文档");
        assertThat(result.getMainContent()).contains("账号授权 API 正文");
        assertThat(result.getMainContent()).contains("[账号授权详情](https://open.example.com/doc/auth)");
        assertThat(result.getQualitySignals()).contains("DIRECT_HTML_CONTENT_READY");
    }

    @Test
    void shouldReturnUnusableWhenHtmlLooksLikeSpaShell() throws Exception {
        DirectHtmlReaderProperties properties = new DirectHtmlReaderProperties();
        properties.setMinimumContentLength(160);
        properties.setReadableChineseGuardChars(80);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                <html>
                  <head><title>Open Platform</title></head>
                  <body>
                    <div id="app"></div>
                    <script>window.__INITIAL_STATE__ = "%s";</script>
                  </body>
                </html>
                """.formatted("x".repeat(1000)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DirectHtmlReaderClient client = new DirectHtmlReaderClient(properties, httpClient);
        PageContentExtractionResult result = client.collect(SourceCollectRequest.builder()
                .url("https://open.example.com/doc")
                .sourceType("DOCS")
                .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(CollectionFailureKind.CONTENT_UNUSABLE.name());
        assertThat(result.getQualitySignals()).contains("DIRECT_HTML_SPA_SHELL");
    }

    @Test
    void shouldNotTreatSpaLikeDomAsShellWhenReadableChineseTextIsEnough() throws Exception {
        DirectHtmlReaderProperties properties = new DirectHtmlReaderProperties();
        properties.setMinimumContentLength(60);
        properties.setReadableChineseGuardChars(80);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        String readableChinese = "开放平台文档提供账号授权能力，包含应用创建、授权回调、令牌刷新、权限范围、接口调用、错误码说明、上线审核和安全配置。"
                + "开发者可以按照步骤完成接入，并在控制台查看调用状态、审计记录、告警订阅和版本变更说明。";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                <html>
                  <head><title>Open Platform</title></head>
                  <body>
                    <div id="app">
                      <main><p>%s</p></main>
                    </div>
                    <script>window.__INITIAL_STATE__ = "%s";</script>
                  </body>
                </html>
                """.formatted(readableChinese, "x".repeat(3000)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DirectHtmlReaderClient client = new DirectHtmlReaderClient(properties, httpClient);
        PageContentExtractionResult result = client.collect(SourceCollectRequest.builder()
                .url("https://open.example.com/doc")
                .sourceType("DOCS")
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMainContent()).contains("开放平台文档提供账号授权能力");
        assertThat(result.getQualitySignals()).contains("DIRECT_HTML_CONTENT_READY");
        assertThat(result.getQualitySignals()).doesNotContain("DIRECT_HTML_SPA_SHELL");
    }

    @Test
    void shouldRetryRuntimeFailureBeforeReturningSuccess() throws Exception {
        DirectHtmlReaderProperties properties = new DirectHtmlReaderProperties();
        properties.setMinimumContentLength(10);
        properties.setMaxRetries(1);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("<html><body><main>可用文档正文，重试后成功。</main></body></html>");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("temporary network error"))
                .thenReturn(response);

        DirectHtmlReaderClient client = new DirectHtmlReaderClient(properties, httpClient);
        PageContentExtractionResult result = client.collect(SourceCollectRequest.builder()
                .url("https://open.example.com/doc")
                .sourceType("DOCS")
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMainContent()).contains("重试后成功");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldReturnHttpStatusFailureWhenTargetReturnsNonSuccessStatus() throws Exception {
        DirectHtmlReaderProperties properties = new DirectHtmlReaderProperties();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("forbidden");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DirectHtmlReaderClient client = new DirectHtmlReaderClient(properties, httpClient);
        PageContentExtractionResult result = client.collect(SourceCollectRequest.builder()
                .url("https://open.example.com/doc")
                .sourceType("DOCS")
                .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(CollectionFailureKind.HTTP_STATUS_ERROR.name());
        assertThat(result.getQualitySignals()).contains("DIRECT_HTML_HTTP_STATUS_ERROR");
    }
}
