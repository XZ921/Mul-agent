package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * 第五轮 Task 1 的结构块提取红灯测试。
 * 这里先锁定“正文 + 结构块”并行抽取的期望，避免后续实现继续只从最长正文块里盲目挑内容。
 */
class PageContentExtractionSupportStructuredBlockTest {

    @Test
    void shouldExtractPricingAndDocumentationBlocksWithoutRelyingOnLongestArticle() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <main>
                      <section class="pricing-card">Pro 199 / month</section>
                      <nav class="docs-outline">
                        <a>Quick Start</a>
                        <a>API Reference</a>
                      </nav>
                    </main>
                  </body>
                </html>
                """);

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "PRICING");

        assertThat(result.getStructuredBlocks())
                .extracting(StructuredContentBlock::getBlockType)
                .contains("PRICING_BLOCK", "DOCUMENTATION_OUTLINE");
        assertThat(result.getQualitySignals()).contains("STRUCTURED_BLOCK_HIT");
    }

    @Test
    void shouldReturnExtractionFailureWhenBodyAndStructuredBlocksAreBothEmpty() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <div class="layout-shell"></div>
                  </body>
                </html>
                """);

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(CollectionFailureKind.EXTRACTION_EMPTY.name());
        assertThat(result.getQualitySignals()).contains("NO_MAIN_CONTENT", "NO_STRUCTURED_BLOCKS");
        assertThat(result.getQualityScore()).isEqualTo(0.0D);
    }

    @Test
    void shouldPreserveSameDomainDocumentationLinksForInternalDiscovery() {
        Page page = mock(Page.class);
        when(page.title()).thenReturn("哔哩哔哩开放平台");
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <main class="docs-content">
                      <a href="https://open.bilibili.com/doc/auth">账号授权</a>
                      <a href="/doc/android-sdk">Android SDK</a>
                      <a href="https://example.org/outside">外部链接</a>
                    </main>
                  </body>
                </html>
                """);
        when(page.evaluate(anyString())).thenReturn(List.of(Map.of(
                "selector", "main",
                "tagName", "MAIN",
                "className", "docs-content",
                "idName", "docs",
                "text", """
                        OPEN API
                        账号授权 包括B站账号授权能力
                        Android SDK 包括B站账号授权功能
                        """,
                "markdownText", """
                        OPEN API
                        [账号授权](https://open.bilibili.com/doc/auth) 包括B站账号授权能力
                        [Android SDK](https://open.bilibili.com/doc/android-sdk) 包括B站账号授权功能
                        """,
                "linkTextLength", 18
        )));

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

        assertThat(result.getMainContent()).contains("[账号授权](https://open.bilibili.com/doc/auth)");
        assertThat(result.getMainContent()).contains("[Android SDK](https://open.bilibili.com/doc/android-sdk)");
    }

    @Test
    void shouldRecoverSpaDocumentationCardUrlsWhenCardsAreRenderedWithoutAnchorTags() {
        Page page = mock(Page.class);
        when(page.title()).thenReturn("哔哩哔哩开放平台");
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <main class="docs-content">
                      <div class="doc-default-item">
                        <p class="title">账号授权</p>
                        <p class="sub-title">包括B站账号授权、账号绑定的能力</p>
                      </div>
                      <div class="doc-default-item">
                        <p class="title">OPEN API</p>
                        <p class="sub-title">开放接口能力</p>
                      </div>
                    </main>
                    <script>
                      window.__DOC_CARDS__ = {
                        openApi: [
                          {title:"账号授权",content:"包括B站账号授权、账号绑定的能力",url:"http://open.bilibili.com/doc/4/auth-page"},
                          {title:"联系我们",content:"联系支持",url:"https://open.bilibili.com/doc/4/contact-page"}
                        ],
                        appSDK: [
                          {title:"Android SDK",content:"包括B站账号授权功能",url:"https://open.bilibili.com/doc/4/android-sdk-page"}
                        ]
                      };
                    </script>
                  </body>
                </html>
                """);
        when(page.evaluate(anyString())).thenReturn(List.of(Map.of(
                "selector", "main",
                "tagName", "MAIN",
                "className", "docs-content",
                "idName", "docs",
                "text", """
                        OPEN API
                        账号授权 包括B站账号授权、账号绑定的能力
                        Android SDK 包括B站账号授权功能
                        """,
                "markdownText", """
                        OPEN API
                        账号授权 包括B站账号授权、账号绑定的能力
                        Android SDK 包括B站账号授权功能
                        """,
                "linkTextLength", 0
        )));

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

        assertThat(result.getMainContent())
                .contains("[账号授权](http://open.bilibili.com/doc/4/auth-page)")
                .contains("[Android SDK](https://open.bilibili.com/doc/4/android-sdk-page)")
                .doesNotContain("contact-page");
    }

    @Test
    void shouldRecoverSpaDocumentationCardUrlsFromExternalJavaScriptChunks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Doc.js", exchange -> {
            byte[] body = """
                    window.__DOC_CHUNK__ = {
                      openApi: [
                        {title:"账号授权",content:"包括B站账号授权、账号绑定的能力",url:"http://open.bilibili.com/doc/4/auth-page"},
                        {title:"联系我们",content:"联系支持",url:"https://open.bilibili.com/doc/4/contact-page"}
                      ],
                      appSDK: [
                        {title:"Android SDK",content:"包括B站账号授权功能",url:"https://open.bilibili.com/doc/4/android-sdk-page"}
                      ]
                    };
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/javascript; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String chunkUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/Doc.js";
            Page page = mock(Page.class);
            when(page.title()).thenReturn("哔哩哔哩开放平台");
            when(page.url()).thenReturn("https://open.bilibili.com/doc");
            when(page.content()).thenReturn("""
                    <html>
                      <head><link href="%s" rel="prefetch"></head>
                      <body>
                        <main class="docs-content">
                          <div class="doc-default-item">账号授权 包括B站账号授权、账号绑定的能力</div>
                          <div class="doc-default-item">Android SDK 包括B站账号授权功能</div>
                        </main>
                      </body>
                    </html>
                    """.formatted(chunkUrl));
            when(page.evaluate(anyString())).thenReturn(List.of(Map.of(
                    "selector", "main",
                    "tagName", "MAIN",
                    "className", "docs-content",
                    "idName", "docs",
                    "text", "OPEN API\n账号授权 包括B站账号授权、账号绑定的能力\nAndroid SDK 包括B站账号授权功能",
                    "markdownText", "OPEN API\n账号授权 包括B站账号授权、账号绑定的能力\nAndroid SDK 包括B站账号授权功能",
                    "linkTextLength", 0
            )));

            PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

            assertThat(result.getMainContent())
                    .contains("[账号授权](http://open.bilibili.com/doc/4/auth-page)")
                    .contains("[Android SDK](https://open.bilibili.com/doc/4/android-sdk-page)")
                    .doesNotContain("contact-page");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPrioritizeDocChunkWhenGenericArcopenChunksAppearFirst() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int index = 1; index <= 5; index++) {
            int chunkIndex = index;
            server.createContext("/arcopen-fe/js/Company" + index + ".js", exchange -> {
                byte[] body = ("window.companyChunk" + chunkIndex + " = true;")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
        }
        server.createContext("/arcopen-fe/js/Doc.f8fe9621.js", exchange -> {
            byte[] body = """
                    window.__DOC_CHUNK__ = {
                      openApi: [
                        {title:"账号授权",content:"包括B站账号授权、账号绑定的能力",url:"http://open.bilibili.com/doc/4/auth-page"}
                      ]
                    };
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Page page = mock(Page.class);
            when(page.title()).thenReturn("哔哩哔哩开放平台");
            when(page.url()).thenReturn("https://open.bilibili.com/doc");
            when(page.content()).thenReturn("""
                    <html>
                      <head>
                        <link href="%s/arcopen-fe/js/Company1.js" rel="prefetch">
                        <link href="%s/arcopen-fe/js/Company2.js" rel="prefetch">
                        <link href="%s/arcopen-fe/js/Company3.js" rel="prefetch">
                        <link href="%s/arcopen-fe/js/Company4.js" rel="prefetch">
                        <link href="%s/arcopen-fe/js/Company5.js" rel="prefetch">
                        <link href="%s/arcopen-fe/js/Doc.f8fe9621.js" rel="prefetch">
                      </head>
                      <body>
                        <main class="docs-content">OPEN API 账号授权 包括B站账号授权、账号绑定的能力</main>
                      </body>
                    </html>
                    """.formatted(baseUrl, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl));
            when(page.evaluate(anyString())).thenReturn(List.of(Map.of(
                    "selector", "main",
                    "tagName", "MAIN",
                    "className", "docs-content",
                    "idName", "docs",
                    "text", "OPEN API\n账号授权 包括B站账号授权、账号绑定的能力",
                    "markdownText", "OPEN API\n账号授权 包括B站账号授权、账号绑定的能力",
                    "linkTextLength", 0
            )));

            PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "DOCS");

            assertThat(result.getMainContent())
                    .contains("[账号授权](http://open.bilibili.com/doc/4/auth-page)");
        } finally {
            server.stop(0);
        }
    }
}
