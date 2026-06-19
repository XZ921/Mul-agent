# Search And Collection Lightweight Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让网页采集先走稳定、快速、可追溯的轻量链路，只有入口页补链接或轻量正文全部不可用时才使用 Playwright。

**Architecture:** 本计划把原来的 `JinaReader -> Playwright` 两段式改为 `DirectHtmlReader(HTTP+Jsoup) -> JinaReader -> Playwright` 三段式。DirectHtmlReader 直连目标站点，绕过 `r.jina.ai` 的海外转发与 SPA 空壳风险；JinaReader 保留为第二轻量路径；Playwright 只做入口页链接补充或最终完整渲染兜底。

**Tech Stack:** Spring Boot, Java 17, Java HttpClient, Jsoup, JUnit 5, Mockito, AssertJ, Jackson, Playwright Java。

---

## Review Assessment

这次评估成立：原计划主要解决的是“少用 Playwright”，没有解决“轻量正文成功率低”的根因。

原计划覆盖了：
- 递归详情页不因链接少升级 Playwright。
- 入口页只用 Playwright 补链接，正文保留轻量结果。
- Playwright 调用次数下降的验证。
- 补链接策略配置化。

原计划没有覆盖：
- JinaReader 通过 `r.jina.ai` 访问国内 SPA 页面时，可能超时、拿到空壳，或返回过薄内容。
- 如果 B 站页面对 Jina 全部失败，单纯减少 Playwright 会把“Playwright 成功但慢”变成“轻量失败且正文为空”。
- 轻量链路需要自建 Direct HTTP+Jsoup 采集器，绕过 `r.jina.ai`，先覆盖静态和半静态页面。

新的执行策略：

```text
LIGHTWEIGHT page
  -> DirectHtmlReader(HTTP+Jsoup)
      success: 使用 Direct 正文，并按策略决定是否入口页 Playwright 补链接
      unusable: 继续 JinaReader
  -> JinaReader(r.jina.ai)
      success: 使用 Jina 正文，并按策略决定是否入口页 Playwright 补链接
      unusable: 继续 Playwright 完整渲染兜底
  -> Playwright full render
      success: 返回完整渲染正文
      failed: 返回失败，并保留 Direct/Jina/Playwright 的质量信号
```

## Scope

本计划覆盖：
- 新增 Direct HTTP+Jsoup 轻量采集器。
- Direct 失败后继续 Jina，Jina 失败后继续 Playwright。
- 入口页 Playwright 只补链接，不覆盖轻量正文。
- 递归详情页不因链接少升级 Playwright。
- 配置项从 Jina 专属语义迁移为网页采集路由语义。
- 单测、上下文装配测试、真实 B 站冒烟验证指标。

本计划不覆盖：
- 搜索阶段候选 URL 排名规则的二次重构。
- Playwright 并发化调度。
- 登录态页面、验证码页面、需要用户交互页面的采集成功率保证。

## File Structure

- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderProperties.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientContextTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorContextTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Optional Test: `backend/src/test/java/cn/bugstack/competitoragent/integration/BilibiliNameOnlyRealSmokeTest.java`

## Acceptance Criteria

- DirectHtmlReader 能从静态 HTML 中提取标题、正文和 Markdown 链接。
- DirectHtmlReader 能识别 SPA 空壳并返回 `CONTENT_UNUSABLE`，不误判为空正文成功。
- DirectHtmlReader 遇到 DOM 像 SPA 但正文中可读中文不少于 80 字时，不判定为 SPA 空壳；是否最终成功仍由正文可用性阈值决定。
- DirectHtmlReader 外部 HTTP 调用具备 try-catch、超时和重试。
- LIGHTWEIGHT 页面先调用 Direct，再调用 Jina，最后才调用 Playwright。
- `collection.direct-html-reader.enabled=false` 时必须跳过 Direct，继续保留原 `JinaReader -> Playwright` 链路。
- Direct 或 Jina 成功时，`sourceUrls` 仍从 `CollectionTaskPackage` 进入最终结果。
- 入口页轻量正文成功但站内链接不足时，只调用 Playwright 补 `discoveredCandidates`，正文不被 Playwright 覆盖。
- Playwright 补链接的 `sourceType` 白名单必须配置化，默认覆盖 `DOCS`；真实冒烟发现 `OFFICIAL` 入口页也缺链接时，可只改配置扩展白名单。
- 递归详情页轻量正文成功时，不因为链接少调用 Playwright。
- Playwright 完整兜底仍保留 `UPGRADED_TO_FULL_RENDER`，入口补链接路径不出现该信号。
- 单元测试通过，真实 B 站冒烟中 `UPGRADED_TO_FULL_RENDER` 数量下降，且正文成功率不能低于修改前；冒烟日志需能观察 `DIRECT_HTML_CONTENT_READY` 命中率、Direct 失败原因、正文长度与站内链接数量，便于后续补选择器。

---

## Task 1: Add Direct Reader Dependency And Config

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderProperties.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [ ] **Step 1: Add Jsoup dependency**

In `backend/pom.xml`, add this dependency after the Playwright dependency:

```xml
<!-- Jsoup 负责 DirectHtmlReader 的 HTML 解析和正文/链接提取 -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.17.2</version>
</dependency>
```

- [ ] **Step 2: Create Direct reader properties**

Create `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderProperties.java`:

```java
package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Direct HTML 轻量采集配置。
 * 这一路径直接访问目标页面并用 Jsoup 提取正文，目的是绕过 r.jina.ai 转发链路，
 * 优先覆盖静态页面和服务端渲染页面；如果页面是 SPA 空壳，则明确返回不可用，让外层继续走 Jina 或 Playwright。
 */
@Data
@ConfigurationProperties(prefix = "collection.direct-html-reader")
public class DirectHtmlReaderProperties {

    /**
     * 是否启用 Direct HTML 直连采集。
     * 默认开启，因为它比 JinaReader 少一层外部代理，失败时也不会阻断后续轻量/渲染兜底。
     */
    private boolean enabled = true;

    /**
     * 单次直连目标站点的超时时间。
     * 该值必须短于 Playwright 超时，避免轻量链路拖慢整个采集阶段。
     */
    private int timeoutSeconds = 8;

    /**
     * Direct HTML 直连失败后的最大重试次数。
     * 总尝试次数为 maxRetries + 1。
     */
    private int maxRetries = 1;

    /**
     * 正文最小可用长度。
     * 低于该阈值时视为正文过薄，继续交给 JinaReader 或 Playwright 兜底。
     */
    private int minimumContentLength = 160;

    /**
     * SPA 空壳判断中的可读中文保护阈值。
     * 有些国内文档页 DOM 看起来像 SPA，但服务端 HTML 已经包含一段可读中文；
     * 当正文中的中文字符数达到该值时，不按 SPA 空壳失败处理，避免误伤半静态页面。
     */
    private int readableChineseGuardChars = 80;

    /**
     * 最多从页面中保留多少个链接到正文尾部。
     * InternalLinkDiscoveryService 会消费这些 Markdown 链接继续做站内递归发现。
     */
    private int maxExtractedLinks = 80;

    /**
     * Direct HTTP 请求使用的 User-Agent。
     * 使用普通桌面浏览器 UA，降低被简单反爬规则拦截的概率。
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
}
```

- [ ] **Step 3: Create webpage collection routing properties**

Create `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionProperties.java`:

```java
package cn.bugstack.competitoragent.collection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网页采集路由配置。
 * 这里放的是 Direct/Jina/Playwright 之间的路由策略，不能放在 JinaReaderProperties 中，
 * 因为补链接策略同时适用于 DirectHtmlReader 与 JinaReader 的轻量正文结果。
 */
@Data
@ConfigurationProperties(prefix = "collection.web-page")
public class WebPageCollectionProperties {

    /**
     * 当轻量正文可用但入口页没有发现足够站内链接时，是否允许 Playwright 只补充链接。
     */
    private boolean playwrightLinkSupplementEnabled = true;

    /**
     * 允许 Playwright 补链接的最大采集深度。
     * 默认 0 表示只允许入口页补链接，递归详情页不因为链接少而升级 Playwright。
     */
    private int playwrightLinkSupplementMaxDepth = 0;

    /**
     * 轻量结果发现的站内链接数量低于该值时，入口页才触发 Playwright 补链接。
     */
    private int playwrightLinkSupplementMinLinks = 1;

    /**
     * 允许 Playwright 执行入口页补链接的来源类型白名单。
     * 默认只覆盖 DOCS，避免官网、新闻、评价页被过度渲染；如果真实冒烟证明 OFFICIAL 入口页缺链接，
     * 可以通过配置追加 OFFICIAL，而不需要改代码。
     */
    private List<String> playwrightLinkSupplementSourceTypes = List.of("DOCS");
}
```

- [ ] **Step 4: Add YAML defaults**

In `backend/src/main/resources/application.yml`, under `collection`, add:

```yaml
  direct-html-reader:
    enabled: true
    timeout-seconds: 8
    max-retries: 1
    minimum-content-length: 160
    readable-chinese-guard-chars: 80
    max-extracted-links: 80
    user-agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
  web-page:
    playwright-link-supplement-enabled: true
    playwright-link-supplement-max-depth: 0
    playwright-link-supplement-min-links: 1
    playwright-link-supplement-source-types:
      - DOCS
```

- [ ] **Step 5: Write binding assertions**

In `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`, add imports:

```java
import cn.bugstack.competitoragent.collection.WebPageCollectionProperties;
import cn.bugstack.competitoragent.source.DirectHtmlReaderProperties;
```

Add property values to the existing `contextRunner`:

```java
"collection.direct-html-reader.enabled=true",
"collection.direct-html-reader.timeout-seconds=9",
"collection.direct-html-reader.max-retries=2",
"collection.direct-html-reader.minimum-content-length=180",
"collection.direct-html-reader.readable-chinese-guard-chars=80",
"collection.direct-html-reader.max-extracted-links=60",
"collection.direct-html-reader.user-agent=test-agent",
"collection.web-page.playwright-link-supplement-enabled=true",
"collection.web-page.playwright-link-supplement-max-depth=0",
"collection.web-page.playwright-link-supplement-min-links=1",
"collection.web-page.playwright-link-supplement-source-types[0]=DOCS",
"collection.web-page.playwright-link-supplement-source-types[1]=OFFICIAL",
```

Add assertions in `shouldBindSearchEngineAndSerpApiProperties`:

```java
DirectHtmlReaderProperties directHtmlReaderProperties = context.getBean(DirectHtmlReaderProperties.class);
WebPageCollectionProperties webPageCollectionProperties = context.getBean(WebPageCollectionProperties.class);

assertThat(directHtmlReaderProperties.isEnabled()).isTrue();
assertThat(directHtmlReaderProperties.getTimeoutSeconds()).isEqualTo(9);
assertThat(directHtmlReaderProperties.getMaxRetries()).isEqualTo(2);
assertThat(directHtmlReaderProperties.getMinimumContentLength()).isEqualTo(180);
assertThat(directHtmlReaderProperties.getReadableChineseGuardChars()).isEqualTo(80);
assertThat(directHtmlReaderProperties.getMaxExtractedLinks()).isEqualTo(60);
assertThat(directHtmlReaderProperties.getUserAgent()).isEqualTo("test-agent");
assertThat(webPageCollectionProperties.isPlaywrightLinkSupplementEnabled()).isTrue();
assertThat(webPageCollectionProperties.getPlaywrightLinkSupplementMaxDepth()).isEqualTo(0);
assertThat(webPageCollectionProperties.getPlaywrightLinkSupplementMinLinks()).isEqualTo(1);
assertThat(webPageCollectionProperties.getPlaywrightLinkSupplementSourceTypes()).containsExactly("DOCS", "OFFICIAL");
```

Add both classes to `@EnableConfigurationProperties`:

```java
DirectHtmlReaderProperties.class,
WebPageCollectionProperties.class,
```

- [ ] **Step 6: Run binding test**

Run:

```bash
mvn -pl backend "-Dtest=SearchPropertiesBindingTest#shouldBindSearchEngineAndSerpApiProperties" test
```

Expected: PASS after the config classes and YAML defaults are in place.

---

## Task 2: Implement Direct HTTP+Jsoup Reader

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientContextTest.java`

- [ ] **Step 1: Write Direct reader tests**

Create `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientTest.java`:

```java
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
                + "开发者可以按照步骤完成接入，并在控制台查看调用状态。";
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
```

- [ ] **Step 2: Run Direct reader tests to verify failure**

Run:

```bash
mvn -pl backend "-Dtest=DirectHtmlReaderClientTest" test
```

Expected: FAIL because `DirectHtmlReaderClient` does not exist yet.

- [ ] **Step 3: Implement Direct reader**

Create `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`:

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Direct HTML 轻量采集客户端。
 * 它直接请求目标 URL，并用 Jsoup 从 HTML 中提取正文与链接；失败时只返回质量信号，
 * 不在这里调用 JinaReader 或 Playwright，避免采集路由职责混在客户端内部。
 */
@Slf4j
@Component
public class DirectHtmlReaderClient {

    private static final List<String> CONTENT_SELECTORS = List.of(
            "main",
            "article",
            "[role=main]",
            ".markdown-body",
            ".docs",
            ".documentation",
            ".doc-content",
            ".content",
            "body"
    );

    private final DirectHtmlReaderProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public DirectHtmlReaderClient(DirectHtmlReaderProperties properties) {
        this(properties, null);
    }

    public DirectHtmlReaderClient(DirectHtmlReaderProperties properties, HttpClient httpClient) {
        this.properties = properties == null ? new DirectHtmlReaderProperties() : properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, this.properties.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
    }

    /**
     * 直连目标页面并提取正文。
     * 外部 HTTP 调用必须有超时、try-catch 和重试；这里失败后返回结构化失败结果，
     * 由 WebPageCollectionExecutor 决定是否继续走 JinaReader 或 Playwright。
     */
    public PageContentExtractionResult collect(SourceCollectRequest request) {
        Instant startedAt = Instant.now();
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "direct html request is null or url is blank",
                    startedAt,
                    List.of("DIRECT_HTML_REQUEST_INVALID"));
        }
        if (!properties.isEnabled()) {
            return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                    "direct html reader disabled",
                    startedAt,
                    List.of("DIRECT_HTML_DISABLED"));
        }

        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(buildRequest(request), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 500 && attempt < maxAttempts) {
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return buildFailureResult(CollectionFailureKind.HTTP_STATUS_ERROR,
                            "direct html status=" + response.statusCode(),
                            startedAt,
                            List.of("DIRECT_HTML_HTTP_STATUS_ERROR"));
                }
                return extractReadableContent(request, response.body(), startedAt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastError = new IllegalStateException("direct html request interrupted", exception);
                break;
            } catch (Exception exception) {
                lastError = new IllegalStateException("direct html request failed: " + exception.getMessage(), exception);
            }
        }

        return buildFailureResult(CollectionFailureKind.RUNTIME_FAILURE,
                lastError == null ? "direct html reader failed" : lastError.getMessage(),
                startedAt,
                List.of("DIRECT_HTML_RUNTIME_FAILURE"));
    }

    HttpRequest buildRequest(SourceCollectRequest request) {
        return HttpRequest.newBuilder(URI.create(request.getUrl()))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
    }

    private PageContentExtractionResult extractReadableContent(SourceCollectRequest request, String html, Instant startedAt) {
        String rawHtml = html == null ? "" : html;
        Document rawDocument = Jsoup.parse(rawHtml, request.getUrl());
        int scriptTextLength = rawDocument.select("script").stream()
                .mapToInt(script -> script.data() == null ? 0 : script.data().length())
                .sum();

        Document document = rawDocument.clone();
        document.select("script,style,noscript,svg,canvas").remove();
        Element contentRoot = resolveContentRoot(document);
        String text = cleanText(contentRoot == null ? document.text() : contentRoot.text());
        String contentSelector = contentRoot == null ? "document" : contentRoot.cssSelector();

        if (looksLikeSpaShell(rawHtml, text, scriptTextLength)) {
            return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                    "direct html reader detected spa shell",
                    startedAt,
                    List.of("DIRECT_HTML_SPA_SHELL"));
        }
        if (text.length() < Math.max(1, properties.getMinimumContentLength())) {
            return buildFailureResult(CollectionFailureKind.CONTENT_UNUSABLE,
                    "direct html content too thin",
                    startedAt,
                    List.of("DIRECT_HTML_CONTENT_TOO_THIN"));
        }

        String markdownLinks = extractMarkdownLinks(document);
        String mainContent = StringUtils.hasText(markdownLinks)
                ? text + "\n\nLinks:\n" + markdownLinks
                : text;
        int extractedLinkCount = StringUtils.hasText(markdownLinks) ? markdownLinks.split("\\n").length : 0;
        log.info("Direct HTML 采集命中: url={}, selector={}, textLength={}, readableChineseChars={}, extractedLinks={}",
                request.getUrl(),
                contentSelector,
                text.length(),
                countReadableChineseChars(text),
                extractedLinkCount);

        return PageContentExtractionResult.builder()
                .success(true)
                .title(StringUtils.hasText(document.title()) ? document.title() : request.getUrl())
                .mainContent(mainContent)
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .qualityScore(0.74D)
                .structuredBlocks(List.<StructuredContentBlock>of())
                .collectedAt(Instant.now())
                .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                .build();
    }

    private Element resolveContentRoot(Document document) {
        for (String selector : CONTENT_SELECTORS) {
            Element element = document.selectFirst(selector);
            if (element != null && StringUtils.hasText(element.text())) {
                return element;
            }
        }
        return document.body();
    }

    private boolean looksLikeSpaShell(String html, String text, int scriptTextLength) {
        String normalizedHtml = html == null ? "" : html.toLowerCase(Locale.ROOT);
        boolean hasAppMount = normalizedHtml.contains("id=\"app\"")
                || normalizedHtml.contains("id=\"root\"")
                || normalizedHtml.contains("window.__initial_state__")
                || normalizedHtml.contains("__webpack");
        int textLength = text == null ? 0 : text.length();
        int readableChineseChars = countReadableChineseChars(text);
        if (readableChineseChars >= Math.max(1, properties.getReadableChineseGuardChars())) {
            return false;
        }
        return textLength < Math.max(1, properties.getMinimumContentLength())
                && hasAppMount
                && scriptTextLength > Math.max(200, textLength * 6);
    }

    private int countReadableChineseChars(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= '\u4E00' && current <= '\u9FFF') {
                count++;
            }
        }
        return count;
    }

    private String extractMarkdownLinks(Document document) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (Element link : document.select("a[href]")) {
            String url = link.absUrl("href");
            String label = cleanText(link.text());
            if (!StringUtils.hasText(url) || !url.startsWith("http") || !StringUtils.hasText(label)) {
                continue;
            }
            links.add("[" + escapeMarkdown(label) + "](" + url + ")");
            if (links.size() >= Math.max(0, properties.getMaxExtractedLinks())) {
                break;
            }
        }
        return String.join("\n", new ArrayList<>(links));
    }

    private String cleanText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String escapeMarkdown(String value) {
        return value.replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private PageContentExtractionResult buildFailureResult(CollectionFailureKind failureKind,
                                                          String errorMessage,
                                                          Instant startedAt,
                                                          List<String> qualitySignals) {
        return PageContentExtractionResult.builder()
                .success(false)
                .failureKind(failureKind == null ? null : failureKind.name())
                .errorMessage(errorMessage)
                .qualitySignals(qualitySignals == null ? List.of() : qualitySignals)
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                .build();
    }
}
```

- [ ] **Step 4: Add Spring context test**

Create `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientContextTest.java`:

```java
package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class DirectHtmlReaderClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DirectHtmlReaderProperties.class, DirectHtmlReaderProperties::new)
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateDirectHtmlReaderClientBeanWhenPropertiesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(DirectHtmlReaderClient.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(DirectHtmlReaderClient.class)
    static class TestConfiguration {
    }
}
```

- [ ] **Step 5: Run Direct reader tests**

Run:

```bash
mvn -pl backend "-Dtest=DirectHtmlReaderClientTest,DirectHtmlReaderClientContextTest" test
```

Expected: PASS.

---

## Task 3: Integrate Direct Reader Before JinaReader

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorContextTest.java`

- [ ] **Step 1: Add route tests**

In `WebPageCollectionExecutorRouteTest`, add imports:

```java
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
```

Add this test:

```java
@Test
void shouldUseDirectHtmlBeforeJinaForLightweightDocsPage() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open Docs")
            .mainContent("Direct 直连采集到的开放平台文档正文。[账号授权](https://open.example.com/doc/auth)")
            .qualityScore(0.74D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("Direct 直连采集到的开放平台文档正文");
    assertThat(result.getSourceUrls()).containsExactly("https://open.example.com/doc");
    assertThat(result.getQualitySignals()).contains("DIRECT_HTML_CONTENT_READY");
    verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

Add this test:

```java
@Test
void shouldFallbackToJinaWhenDirectHtmlReturnsSpaShell() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("CONTENT_UNUSABLE")
            .qualityScore(0.0D)
            .qualitySignals(List.of("DIRECT_HTML_SPA_SHELL"))
            .build());
    when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open Docs")
            .mainContent("JinaReader 采集到的文档正文，Direct 失败后仍然可以成功。")
            .qualityScore(0.78D)
            .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("JinaReader 采集到的文档正文");
    assertThat(result.getQualitySignals()).contains("LIGHTWEIGHT_CONTENT_READY");
    verify(jinaReaderClient).collect(any(SourceCollectRequest.class));
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

Add this test:

```java
@Test
void shouldFallbackToPlaywrightWhenDirectAndJinaAreBothUnusable() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("CONTENT_UNUSABLE")
            .qualitySignals(List.of("DIRECT_HTML_SPA_SHELL"))
            .qualityScore(0.0D)
            .build());
    when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("CONTENT_UNUSABLE")
            .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_TOO_THIN"))
            .qualityScore(0.0D)
            .build());
    when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
            .url("https://open.example.com/doc")
            .title("Open Docs")
            .content("Playwright 完整渲染后的正文。")
            .metadata("""
                    {
                      "sourceUrls": ["https://open.example.com/doc"],
                      "qualitySignals": ["FULL_RENDER_READY"],
                      "qualityScore": 0.82,
                      "durationMillis": 3000
                    }
                    """)
            .sourceType("DOCS")
            .competitorName("Acme")
            .success(true)
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("Playwright 完整渲染后的正文");
    assertThat(result.getQualitySignals())
            .contains("DIRECT_HTML_SPA_SHELL", "LIGHTWEIGHT_CONTENT_TOO_THIN", "UPGRADED_TO_FULL_RENDER");
    verify(sourceCollector).collect(any(SourceCollectRequest.class));
}
```

Add this test:

```java
@Test
void shouldKeepOriginalJinaThenPlaywrightChainWhenDirectHtmlDisabled() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("RUNTIME_FAILURE")
            .qualitySignals(List.of("DIRECT_HTML_DISABLED"))
            .qualityScore(0.0D)
            .build());
    when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open Docs")
            .mainContent("Direct 关闭后，JinaReader 仍按原轻量链路采集正文。")
            .qualityScore(0.78D)
            .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("JinaReader 仍按原轻量链路采集正文");
    assertThat(result.getQualitySignals()).contains("LIGHTWEIGHT_CONTENT_READY");
    verify(jinaReaderClient).collect(any(SourceCollectRequest.class));
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 2: Run route tests to verify failure**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest" test
```

Expected: FAIL because `WebPageCollectionExecutor` does not yet know `DirectHtmlReaderClient`.

- [ ] **Step 3: Update executor fields and constructors**

In `WebPageCollectionExecutor`, add imports:

```java
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
```

Add fields:

```java
private final DirectHtmlReaderClient directHtmlReaderClient;
private final WebPageCollectionProperties webPageCollectionProperties;
```

Replace constructors with this set while preserving existing test call sites:

```java
public WebPageCollectionExecutor(SourceCollector sourceCollector) {
    this(null, null, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
}

public WebPageCollectionExecutor(JinaReaderClient jinaReaderClient, SourceCollector sourceCollector) {
    this(null, jinaReaderClient, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
}

public WebPageCollectionExecutor(DirectHtmlReaderClient directHtmlReaderClient,
                                 JinaReaderClient jinaReaderClient,
                                 SourceCollector sourceCollector) {
    this(directHtmlReaderClient, jinaReaderClient, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
}

@Autowired
public WebPageCollectionExecutor(ObjectProvider<DirectHtmlReaderClient> directHtmlReaderClientProvider,
                                 ObjectProvider<JinaReaderClient> jinaReaderClientProvider,
                                 SourceCollector sourceCollector,
                                 ObjectProvider<InternalLinkDiscoveryService> internalLinkDiscoveryServiceProvider,
                                 ObjectProvider<WebPageCollectionProperties> webPageCollectionPropertiesProvider) {
    this(directHtmlReaderClientProvider == null ? null : directHtmlReaderClientProvider.getIfAvailable(),
            jinaReaderClientProvider == null ? null : jinaReaderClientProvider.getIfAvailable(),
            sourceCollector,
            internalLinkDiscoveryServiceProvider == null ? null : internalLinkDiscoveryServiceProvider.getIfAvailable(),
            webPageCollectionPropertiesProvider == null ? null : webPageCollectionPropertiesProvider.getIfAvailable());
}

WebPageCollectionExecutor(DirectHtmlReaderClient directHtmlReaderClient,
                          JinaReaderClient jinaReaderClient,
                          SourceCollector sourceCollector,
                          InternalLinkDiscoveryService internalLinkDiscoveryService,
                          WebPageCollectionProperties webPageCollectionProperties) {
    this.directHtmlReaderClient = directHtmlReaderClient;
    this.jinaReaderClient = jinaReaderClient;
    this.sourceCollector = sourceCollector;
    this.internalLinkDiscoveryService = internalLinkDiscoveryService == null
            ? defaultInternalLinkDiscoveryService()
            : internalLinkDiscoveryService;
    this.webPageCollectionProperties = webPageCollectionProperties == null
            ? new WebPageCollectionProperties()
            : webPageCollectionProperties;
}
```

- [ ] **Step 4: Add Direct collection helper**

Add this method above `collectByJinaReader`:

```java
/**
 * 第一轻量路径：Direct HTTP+Jsoup。
 * 该路径直接访问目标站点，成功时能避开 r.jina.ai 的跨境转发与页面空壳问题；
 * 失败时只返回质量信号，不在这里做 Playwright 兜底，避免轻量客户端承担路由职责。
 */
private PageContentExtractionResult collectByDirectHtmlReader(CollectionTaskPackage taskPackage) {
    if (directHtmlReaderClient == null) {
        return null;
    }
    try {
        return directHtmlReaderClient.collect(buildCollectRequest(taskPackage, WebPageRenderHint.LIGHTWEIGHT));
    } catch (RuntimeException exception) {
        return PageContentExtractionResult.builder()
                .success(false)
                .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                .errorMessage(exception.getMessage())
                .qualitySignals(List.of("DIRECT_HTML_RUNTIME_FAILURE"))
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(0L)
                .build();
    }
}
```

- [ ] **Step 5: Replace execute lightweight routing**

Replace the current Jina-only lightweight block:

```java
PageContentExtractionResult lightweightResult = collectByJinaReader(taskPackage);
if (lightweightResult != null && lightweightResult.isUsable()) {
    return mapLightweightResult(taskPackage, lightweightResult, startedAt);
}

return collectByPlaywright(taskPackage,
        mergeSignals(lightweightResult == null ? List.of("LIGHTWEIGHT_RUNTIME_FAILURE") : lightweightResult.getQualitySignals(),
                List.of("UPGRADED_TO_FULL_RENDER")),
        lightweightResult,
        startedAt);
```

with:

```java
PageContentExtractionResult directResult = collectByDirectHtmlReader(taskPackage);
if (directResult != null && directResult.isUsable()) {
    CollectionExecutionResult mappedDirect = mapLightweightResult(taskPackage, directResult, startedAt);
    return maybeSupplementLinksWithPlaywright(taskPackage, mappedDirect, startedAt);
}

PageContentExtractionResult jinaResult = collectByJinaReader(taskPackage);
if (jinaResult != null && jinaResult.isUsable()) {
    CollectionExecutionResult mappedJina = mapLightweightResult(taskPackage, jinaResult, startedAt);
    return maybeSupplementLinksWithPlaywright(taskPackage, mappedJina, startedAt);
}

return collectByPlaywright(taskPackage,
        mergeSignals(mergeLightweightFailureSignals(directResult, jinaResult), List.of("UPGRADED_TO_FULL_RENDER")),
        resolveFallbackFailureSource(directResult, jinaResult),
        startedAt);
```

Add helpers:

```java
private List<String> mergeLightweightFailureSignals(PageContentExtractionResult directResult,
                                                    PageContentExtractionResult jinaResult) {
    List<String> directSignals = directResult == null ? List.of("DIRECT_HTML_UNAVAILABLE") : directResult.getQualitySignals();
    List<String> jinaSignals = jinaResult == null ? List.of("LIGHTWEIGHT_RUNTIME_FAILURE") : jinaResult.getQualitySignals();
    return mergeSignals(directSignals, jinaSignals);
}

private PageContentExtractionResult resolveFallbackFailureSource(PageContentExtractionResult directResult,
                                                                 PageContentExtractionResult jinaResult) {
    if (jinaResult != null) {
        return jinaResult;
    }
    return directResult;
}
```

- [ ] **Step 6: Update context test**

In `WebPageCollectionExecutorContextTest`, add imports:

```java
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
import cn.bugstack.competitoragent.source.DirectHtmlReaderProperties;
```

Add beans to `TestConfiguration`:

```java
@Bean
DirectHtmlReaderProperties directHtmlReaderProperties() {
    return new DirectHtmlReaderProperties();
}

@Bean
DirectHtmlReaderClient directHtmlReaderClient(DirectHtmlReaderProperties properties) {
    return new DirectHtmlReaderClient(properties, null);
}

@Bean
WebPageCollectionProperties webPageCollectionProperties() {
    return new WebPageCollectionProperties();
}
```

- [ ] **Step 7: Run route and context tests**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest,WebPageCollectionExecutorContextTest" test
```

Expected: PASS.

---

## Task 4: Keep Playwright As Link Supplement, Not Content Replacement

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`

- [ ] **Step 1: Add entry-page supplement test**

Add this test to `WebPageCollectionExecutorRouteTest`:

```java
@Test
void shouldUsePlaywrightOnlyToSupplementLinksWhenEntryLightweightContentHasNoInternalLinks() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open Docs")
            .mainContent("OPEN API 文档中心。账号授权、用户管理、视频管理、Android SDK。")
            .qualityScore(0.82D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());
    when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
            .url("https://open.example.com/doc")
            .title("Open Docs")
            .content("""
                    <a href="https://open.example.com/doc/auth">账号授权</a>
                    <a href="https://open.example.com/doc/android-sdk">Android SDK</a>
                    """)
            .metadata("""
                    {
                      "sourceUrls": ["https://open.example.com/doc"],
                      "qualitySignals": ["FULL_RENDER_READY"],
                      "qualityScore": 0.52,
                      "durationMillis": 3000
                    }
                    """)
            .sourceType("DOCS")
            .competitorName("Acme")
            .success(true)
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .discoveryDepth(0)
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("OPEN API 文档中心");
    assertThat(result.getQualitySignals())
            .contains("DIRECT_HTML_CONTENT_READY", "PLAYWRIGHT_LINK_SUPPLEMENT_READY")
            .doesNotContain("UPGRADED_TO_FULL_RENDER");
    assertThat(result.getDiscoveredCandidates())
            .extracting(candidate -> candidate.getUrl())
            .containsExactly("https://open.example.com/doc/auth", "https://open.example.com/doc/android-sdk");
    verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
    verify(sourceCollector).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 2: Add recursive no-upgrade test**

Add this test:

```java
@Test
void shouldNotSupplementLinksForRecursiveLightweightSuccess() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Account Auth")
            .mainContent("账号授权正文。scope: USER_INFO。请求方式 GET。")
            .qualityScore(0.82D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc/auth")
            .resourceLocator("https://open.example.com/doc/auth")
            .sourceType("DOCS")
            .discoveryDepth(1)
            .sourceUrls(List.of("https://open.example.com/doc", "https://open.example.com/doc/auth"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("账号授权正文");
    assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 3: Add default sourceType whitelist test**

Add this test:

```java
@Test
void shouldNotSupplementLinksForOfficialEntryPageByDefault() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Official Home")
            .mainContent("官网首页正文可用，但默认不为了 OFFICIAL 入口页补链接。")
            .qualityScore(0.82D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://www.example.com")
            .resourceLocator("https://www.example.com")
            .sourceType("OFFICIAL")
            .discoveryDepth(0)
            .sourceUrls(List.of("https://www.example.com"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 4: Run tests to verify failure**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest#shouldUsePlaywrightOnlyToSupplementLinksWhenEntryLightweightContentHasNoInternalLinks,WebPageCollectionExecutorRouteTest#shouldNotSupplementLinksForRecursiveLightweightSuccess,WebPageCollectionExecutorRouteTest#shouldNotSupplementLinksForOfficialEntryPageByDefault" test
```

Expected: FAIL because link supplement logic does not exist yet.

- [ ] **Step 5: Add link supplement helper**

In `WebPageCollectionExecutor`, add:

```java
private CollectionExecutionResult maybeSupplementLinksWithPlaywright(CollectionTaskPackage taskPackage,
                                                                     CollectionExecutionResult lightweightResult,
                                                                     long startedAt) {
    CollectionExecutionResult normalizedLightweight = lightweightResult == null ? null : lightweightResult.normalize();
    if (!shouldSupplementLinks(taskPackage, normalizedLightweight)) {
        return normalizedLightweight;
    }
    try {
        SourceCollector.CollectedPage page = sourceCollector.collect(buildCollectRequest(taskPackage, WebPageRenderHint.FULL_RENDER));
        if (page == null || !page.isSuccess()) {
            return normalizedLightweight.toBuilder()
                    .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_FAILED")))
                    .build()
                    .normalize();
        }
        JsonNode metadata = readMetadata(page.getMetadata());
        CollectionExecutionResult renderedLinkResult = CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .executorType(executorType())
                .success(true)
                .status("SUCCESS")
                .resourceLocator(taskPackage.getResourceLocator())
                .title(page.getTitle())
                .content(page.getContent())
                .sourceUrls(resolveCollectedSourceUrls(taskPackage, metadata))
                .qualitySignals(resolveQualitySignals(List.of("PLAYWRIGHT_LINK_SUPPLEMENT_RENDERED"), true, metadata))
                .qualityScore(resolveQualityScore(true, metadata))
                .structuredBlocks(resolveStructuredBlocks(metadata))
                .collectedAt(resolveCollectedAt(metadata, startedAt))
                .durationMillis(resolveDurationMillis(metadata, startedAt))
                .build()
                .normalize();
        CollectionExecutionResult renderedWithLinks = attachInternalDiscovery(taskPackage, renderedLinkResult);
        return normalizedLightweight.toBuilder()
                .discoveredCandidates(renderedWithLinks.getDiscoveredCandidates())
                .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_READY")))
                .build()
                .normalize();
    } catch (RuntimeException exception) {
        return normalizedLightweight.toBuilder()
                .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_FAILED")))
                .build()
                .normalize();
    }
}

private boolean shouldSupplementLinks(CollectionTaskPackage taskPackage, CollectionExecutionResult lightweightResult) {
    if (taskPackage == null || lightweightResult == null || !lightweightResult.isSuccess()) {
        return false;
    }
    if (sourceCollector == null || !webPageCollectionProperties.isPlaywrightLinkSupplementEnabled()) {
        return false;
    }
    int depth = taskPackage.getDiscoveryDepth() == null ? 0 : Math.max(0, taskPackage.getDiscoveryDepth());
    if (depth > Math.max(0, webPageCollectionProperties.getPlaywrightLinkSupplementMaxDepth())) {
        return false;
    }
    if (!isSupplementSourceTypeAllowed(taskPackage.getSourceType())) {
        return false;
    }
    int discoveredCount = lightweightResult.getDiscoveredCandidates() == null
            ? 0
            : lightweightResult.getDiscoveredCandidates().size();
    return discoveredCount < Math.max(0, webPageCollectionProperties.getPlaywrightLinkSupplementMinLinks());
}

private boolean isSupplementSourceTypeAllowed(String sourceType) {
    if (!StringUtils.hasText(sourceType)) {
        return false;
    }
    List<String> allowedSourceTypes = webPageCollectionProperties.getPlaywrightLinkSupplementSourceTypes();
    if (allowedSourceTypes == null || allowedSourceTypes.isEmpty()) {
        allowedSourceTypes = List.of("DOCS");
    }
    String normalizedSourceType = sourceType.trim();
    return allowedSourceTypes.stream()
            .filter(StringUtils::hasText)
            .anyMatch(allowed -> allowed.trim().equalsIgnoreCase(normalizedSourceType));
}
```

- [ ] **Step 6: Run route tests**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest" test
```

Expected: PASS.

---

## Task 5: Make Link Supplement Fully Config-Driven

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`

- [ ] **Step 1: Add disabled-config test**

Add this test:

```java
@Test
void shouldNotSupplementLinksWhenPlaywrightLinkSupplementDisabled() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    WebPageCollectionProperties properties = new WebPageCollectionProperties();
    properties.setPlaywrightLinkSupplementEnabled(false);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open Docs")
            .mainContent("OPEN API 文档中心，正文可用但没有链接。")
            .qualityScore(0.82D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(
            directHtmlReaderClient,
            jinaReaderClient,
            sourceCollector,
            new InternalLinkDiscoveryService(new InternalLinkDiscoveryProperties(), new cn.bugstack.competitoragent.search.CanonicalUrlResolver()),
            properties
    );
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .discoveryDepth(0)
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getQualitySignals()).doesNotContain("PLAYWRIGHT_LINK_SUPPLEMENT_READY");
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 2: Add sourceType whitelist extension test**

Add this test:

```java
@Test
void shouldSupplementLinksForOfficialEntryPageWhenSourceTypeWhitelistIncludesOfficial() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    WebPageCollectionProperties properties = new WebPageCollectionProperties();
    properties.setPlaywrightLinkSupplementSourceTypes(List.of("DOCS", "OFFICIAL"));
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Official Home")
            .mainContent("官网首页正文可用，但站内入口链接不足，需要通过配置允许 Playwright 补链接。")
            .qualityScore(0.82D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .build());
    when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
            .url("https://www.example.com")
            .title("Official Home")
            .content("""
                    <a href="https://www.example.com/docs">开发者文档</a>
                    <a href="https://www.example.com/pricing">价格</a>
                    """)
            .metadata("""
                    {
                      "sourceUrls": ["https://www.example.com"],
                      "qualitySignals": ["FULL_RENDER_READY"],
                      "qualityScore": 0.52,
                      "durationMillis": 2600
                    }
                    """)
            .sourceType("OFFICIAL")
            .competitorName("Acme")
            .success(true)
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(
            directHtmlReaderClient,
            jinaReaderClient,
            sourceCollector,
            new InternalLinkDiscoveryService(new InternalLinkDiscoveryProperties(), new cn.bugstack.competitoragent.search.CanonicalUrlResolver()),
            properties
    );
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://www.example.com")
            .resourceLocator("https://www.example.com")
            .sourceType("OFFICIAL")
            .discoveryDepth(0)
            .sourceUrls(List.of("https://www.example.com"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getContent()).contains("官网首页正文可用");
    assertThat(result.getQualitySignals())
            .contains("DIRECT_HTML_CONTENT_READY", "PLAYWRIGHT_LINK_SUPPLEMENT_READY")
            .doesNotContain("UPGRADED_TO_FULL_RENDER");
    assertThat(result.getDiscoveredCandidates())
            .extracting(candidate -> candidate.getUrl())
            .containsExactly("https://www.example.com/docs", "https://www.example.com/pricing");
    verify(sourceCollector).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 3: Run config-driven tests**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest#shouldNotSupplementLinksWhenPlaywrightLinkSupplementDisabled,WebPageCollectionExecutorRouteTest#shouldSupplementLinksForOfficialEntryPageWhenSourceTypeWhitelistIncludesOfficial" test
```

Expected: PASS if Task 4 already uses `WebPageCollectionProperties`.

- [ ] **Step 4: Confirm no Jina-specific supplement fields**

Run:

```bash
rg -n "playwrightLinkSupplement|playwright-link-supplement" backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java backend/src/main/java backend/src/main/resources/application.yml
```

Expected:

```text
No matches in JinaReaderProperties.java.
Matches only in WebPageCollectionProperties.java, WebPageCollectionExecutor.java, application.yml, and tests.
```

---

## Task 6: Preserve Existing Playwright Full Fallback Semantics

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`

- [ ] **Step 1: Strengthen existing fallback assertion**

In `shouldFallbackToPlaywrightWhenJinaReaderReturnsThinContent`, update the setup so Direct is unavailable by constructing:

```java
WebPageCollectionExecutor executor = new WebPageCollectionExecutor(null, jinaReaderClient, sourceCollector);
```

Keep these assertions:

```java
assertThat(result.getQualitySignals()).contains("UPGRADED_TO_FULL_RENDER", "STRUCTURED_BLOCK_HIT");
verify(sourceCollector).collect(any(SourceCollectRequest.class));
```

Add:

```java
assertThat(result.getContent()).contains("完整定价页正文");
assertThat(result.getDiscoveredCandidates()).isNotNull();
```

- [ ] **Step 2: Ensure signal split is intentional**

In `maybeSupplementLinksWithPlaywright`, never add `UPGRADED_TO_FULL_RENDER`.

Expected signal split:

```text
Direct/Jina success + Playwright link supplement:
DIRECT_HTML_CONTENT_READY or LIGHTWEIGHT_CONTENT_READY
PLAYWRIGHT_LINK_SUPPLEMENT_READY

Direct/Jina unusable + Playwright content fallback:
DIRECT_HTML_SPA_SHELL or DIRECT_HTML_CONTENT_TOO_THIN
LIGHTWEIGHT_CONTENT_TOO_THIN or LIGHTWEIGHT_RUNTIME_FAILURE
UPGRADED_TO_FULL_RENDER
FULL_RENDER_READY
```

- [ ] **Step 3: Run fallback tests**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest#shouldFallbackToPlaywrightWhenJinaReaderReturnsThinContent,WebPageCollectionExecutorRouteTest#shouldFallbackToPlaywrightWhenDirectAndJinaAreBothUnusable" test
```

Expected: PASS.

---

## Task 7: Add Lightweight Chain Observability

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`

- [ ] **Step 1: Add duration test for Direct-only success**

Add:

```java
@Test
void shouldKeepDirectDurationWhenNoPlaywrightSupplementIsNeeded() {
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(directHtmlReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("API Reference")
            .mainContent("[账号授权](https://open.example.com/doc/auth) 正文")
            .qualityScore(0.92D)
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .durationMillis(44L)
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(directHtmlReaderClient, jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://open.example.com/doc")
            .resourceLocator("https://open.example.com/doc")
            .sourceType("DOCS")
            .discoveryDepth(0)
            .sourceUrls(List.of("https://open.example.com/doc"))
            .build());

    assertThat(result.getDurationMillis()).isEqualTo(44L);
    verify(jinaReaderClient, never()).collect(any(SourceCollectRequest.class));
    verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 2: Preserve existing mapper duration logic**

In `mapLightweightResult`, keep:

```java
.durationMillis(lightweightResult.getDurationMillis() == null
        ? Math.max(0L, System.currentTimeMillis() - startedAt)
        : lightweightResult.getDurationMillis())
```

- [ ] **Step 3: Preserve Direct hit observability log**

In `DirectHtmlReaderClient.extractReadableContent`, keep the Direct hit log added in Task 2:

```java
log.info("Direct HTML 采集命中: url={}, selector={}, textLength={}, readableChineseChars={}, extractedLinks={}",
        request.getUrl(),
        contentSelector,
        text.length(),
        countReadableChineseChars(text),
        extractedLinkCount);
```

This log is intentionally structured enough for smoke triage:
- `selector` identifies whether `main/article/.docs/body` actually matched useful content.
- `textLength` and `readableChineseChars` separate selector misses from SPA shell false positives.
- `extractedLinks` shows whether Playwright link supplement is needed because Direct found正文 but no internal links.

- [ ] **Step 4: Run route tests**

Run:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest" test
```

Expected: PASS.

---

## Task 8: Verification Matrix

**Files:**
- No production files.
- Optional real smoke file: `backend/src/test/java/cn/bugstack/competitoragent/integration/BilibiliNameOnlyRealSmokeTest.java`

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
mvn -pl backend "-Dtest=DirectHtmlReaderClientTest,DirectHtmlReaderClientContextTest,WebPageCollectionExecutorRouteTest,WebPageCollectionExecutorContextTest,InternalLinkDiscoveryServiceTest,JinaReaderClientTest" test
```

Expected:

```text
BUILD SUCCESS
Failures: 0
Errors: 0
```

- [ ] **Step 2: Run search and collection regression tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CompetitorDomainDiscoveryServiceTest,HeuristicSourceDiscoveryServiceTest,SourceCandidateRankerTest,PageContentExtractionSupportStructuredBlockTest,InternalLinkDiscoveryServiceTest,WebPageCollectionExecutorRouteTest,PlaywrightPageCollectorTest" test
```

Expected:

```text
BUILD SUCCESS
Failures: 0
Errors: 0
```

- [ ] **Step 3: Run real Bilibili name-only smoke**

Run only when real network and Playwright are available:

```bash
$env:RUN_REAL_SMOKE='true'; mvn -pl backend "-Dtest=BilibiliNameOnlyRealSmokeTest" test
```

Expected test outcome:

```text
Tests run: 1
Failures: 0
Errors: 0
BILIBILI_REAL_SMOKE collectionReport ... "status":"SUCCESS"
```

Evidence to inspect in the Surefire report or smoke log:

```text
DIRECT_HTML_CONTENT_READY count should be greater than 0 when pages expose readable HTML.
DIRECT_HTML_DISABLED should not appear unless collection.direct-html-reader.enabled=false is set for rollback validation.
Direct HTML hit logs should include selector, textLength, readableChineseChars, and extractedLinks.
If selector=body dominates successful Direct hits while textLength is noisy, add narrower selectors before raising thresholds.
DIRECT_HTML_SPA_SHELL pages with readableChineseChars >= 80 indicate a guard regression and must fail review.
LIGHTWEIGHT_CONTENT_READY may still appear when Jina succeeds after Direct fails.
UPGRADED_TO_FULL_RENDER count should decrease compared with the previous all-page Playwright fallback pattern.
PLAYWRIGHT_LINK_SUPPLEMENT_READY should appear only for entry pages.
PLAYWRIGHT_LINK_SUPPLEMENT_READY should appear for DOCS by default; if OFFICIAL entry pages show extractedLinks=0 and useful child pages only appear after full render, add OFFICIAL to collection.web-page.playwright-link-supplement-source-types and rerun smoke.
Final useful正文 count must not be lower than the previous successful smoke.
Total runtime should be lower than the previous ~967s deep smoke only if fewer pages need full render.
```

Do not claim runtime improvement unless this real smoke has been run and the new Surefire report confirms elapsed time.

---

## Rollback Plan

- If DirectHtmlReader causes false positives, set:

```yaml
collection:
  direct-html-reader:
    enabled: false
```

  Then rerun:

```bash
mvn -pl backend "-Dtest=WebPageCollectionExecutorRouteTest#shouldKeepOriginalJinaThenPlaywrightChainWhenDirectHtmlDisabled" test
```

  Expected: PASS. This confirms Direct rollback keeps the original Jina + Playwright chain intact.

- If Playwright link supplement causes instability, set:

```yaml
collection:
  web-page:
    playwright-link-supplement-enabled: false
```

- If real smoke shows `OFFICIAL` entry pages have useful正文 but no internal links, expand only the sourceType whitelist:

```yaml
collection:
  web-page:
    playwright-link-supplement-source-types:
      - DOCS
      - OFFICIAL
```

- If the new routing breaks production bean creation, revert only `WebPageCollectionExecutor` constructor changes and keep DirectHtmlReader tests for later rework.
- Do not reintroduce product-specific hardcoded supplements.

## Self-Review

- Spec coverage: Covers the review gap by adding Direct HTTP+Jsoup before Jina, while preserving the original Playwright minimization workstream.
- Path check: This plan is saved under `docs/superpowers/task/search-and-collection/`, matching the user's moved location.
- Placeholder scan: No forbidden placeholder markers or open-ended implementation steps remain.
- Type consistency: Uses `DirectHtmlReaderClient`, `DirectHtmlReaderProperties`, `WebPageCollectionProperties`, `JinaReaderClient`, `WebPageCollectionExecutor`, `CollectionTaskPackage`, `CollectionExecutionResult`, `SourceCollector`, and `InternalLinkDiscoveryService`.
- Risk note: This plan improves lightweight-chain success rate; it does not guarantee every SPA can be read without Playwright. Pages that only render meaningful content after JavaScript still require Playwright fallback.
