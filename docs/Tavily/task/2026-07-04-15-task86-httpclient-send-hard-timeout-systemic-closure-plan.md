# Task86 HttpClient 阻塞式 send 系统性硬超时收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把生产代码中剩余阻塞式 `HttpClient.send()` 统一收口到应用层硬超时，避免 task83 Tavily、task86 sitemap 同类卡死继续在正文抓取、候选验证、HTTP 搜索和 LLM 路径上逐点暴露。

**Architecture:** 新增一个公共 `HardTimeoutHttpClient` 边界工具，内部统一使用 `sendAsync(...).get(protocolTimeout + grace)`、超时/中断时取消 future、`InterruptedException` 恢复中断标记。各业务客户端保留原有 DTO、重试和 fail-open 语义，只替换底层 HTTP 发送方式，避免把 Tavily/sitemap 的硬超时代码复制到 11 个类里。

**Tech Stack:** Java 17, `java.net.http.HttpClient`, JUnit 5, AssertJ, Mockito, Maven Surefire.

---

## 当前阶段

当前阶段：系统性问题已建档，待执行实现  
[x] 信息采集：已完成  
[x] 数据分析：已完成  
[ ] 公共封装：待执行  
[ ] 主链路迁移：待执行  
[ ] 全库验收：待执行  

## 背景与性质

这不是 task86 的回退，而是 task83/task86 连续证明的同类运行时边界问题：

- task83：`TavilySearchClient` 使用阻塞式 `HttpClient.send()`，线程停在 JDK 17.0.3.1 `HttpClientImpl.send() -> CompletableFuture.get()`，后续已改为 `sendAsync(...).get(timeout)`。
- task86：`SitemapDiscoveryService.fetchText()` 同样设置了 `HttpRequest.timeout(3s)`，但现场线程仍 park 数百秒，热修已改为 `sendAsync(...).get(timeout)`。
- 当前结论：`HttpRequest.timeout()` 可以继续保留为协议层 timeout，但不能作为调用线程硬 deadline 的唯一保障。所有生产 HTTP 出口必须有外层硬超时，且外层硬超时要比协议 timeout 略大，避免两个 timeout 赛跑导致异常语义漂移。

## 当前审计口径

task86 sitemap 热修前，生产代码直接 `HttpClient.send()` 为 12 处。热修后，`SitemapDiscoveryService` 已移除阻塞式 `send()`，当前生产剩余 11 处。

审计命令：

```powershell
rg -n "httpClient\.send\(|EXTERNAL_SCRIPT_HTTP_CLIENT\.send\(" backend/src/main/java -g "*.java"
```

当前剩余生产点：

| 文件 | 行号 | 是否在 9a 主链路 | 风险 | 迁移批次 |
| --- | ---: | --- | --- | --- |
| `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java` | 92 | 是，正文抓取 | 高 | P0 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderClient.java` | 84 | 是，正文抓取兜底 | 高 | P0 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java` | 250 | 是，页面采集 HTTP 快路 | 高 | P0 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java` | 297 | 是，外部脚本抽取 | 高 | P0 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java` | 100 | 是，HTTP 搜索兜底 | 中 | P1 |
| `backend/src/main/java/cn/bugstack/competitoragent/search/DomainVerificationClient.java` | 70 | 是，候选验证 | 中 | P1 |
| `backend/src/main/java/cn/bugstack/competitoragent/llm/OpenAiCompatibleClient.java` | 178 | 是，embedding/rerank 非流式 HTTP | 中 | P1 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedClient.java` | 60 | 视源配置 | 低-中 | P2 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java` | 87 | 视源配置 | 低-中 | P2 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java` | 121 | 视源配置 | 低-中 | P2 |
| `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java` | 120 | 视源配置 | 低-中 | P2 |

非生产示例 `docs/Tavily/Main.java:150` 不纳入本计划验收口径。

## 实施提醒与设计约束

- 外层 hard timeout 不能与 `HttpRequest.timeout()` 完全相等。公共 helper 必须把传入的协议 timeout 转换为 `protocolTimeout + grace`，grace 使用 `max(100ms, min(2000ms, protocolTimeout * 10%))`，这样 100ms 单测不会被拖慢，30s 生产请求最多只增加 2s 防抖余量。
- helper 对调用方暴露的参数名必须叫 `protocolTimeout` 或 `requestTimeout`，不要叫 `hardTimeout`，避免执行者误把“协议 timeout”原样传给 `Future.get(timeout)`。
- Playwright 主体渲染仍走浏览器超时，`PlaywrightPageCollector:250` 只覆盖 `collectByHttp` 的 HTTP 快路。迁移时不得把 HTTP 快路 timeout 改得比现有 `collectorProperties.getPageTimeoutSeconds()` 更短；HTTP 快路失败后继续沿用现有失败/降级口径，不新造一个与浏览器渲染预算冲突的 3s 口径。
- `PageContentExtractionSupport` 使用 `static final EXTERNAL_SCRIPT_HTTP_CLIENT`，不能套用 Direct/Jina 的构造器注入方法。必须新增包内静态重载 `fetchExternalScript(String scriptUrl, HttpClient httpClient)`，生产入口继续使用默认静态客户端，测试通过重载注入 `NeverCompletingHttpClient`。

## 结构化执行计划

| 步骤 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 新增公共硬超时 HTTP helper 和通用测试工具 | 45 分钟 | task86 sitemap 热修已通过，确认公共封装不影响既有业务语义 |
| Task 2 | P0 主采集链路迁移：Direct/Jina/Playwright/PageContentExtraction | 120 分钟 | Task 1 helper 测试通过；已确认 Playwright HTTP 快路与浏览器渲染预算口径 |
| Task 3 | P1 搜索、验证、LLM 路径迁移 | 90 分钟 | Task 1 helper 测试通过，P0 无回归 |
| Task 4 | P2 源配置相关 provider 迁移 | 75 分钟 | Task 1 helper 测试通过 |
| Task 5 | 全库 grep、专项测试、9a 类 smoke 验收 | 45 分钟 | Task 2-4 完成 |

## 目标文件结构

- Create: `backend/src/main/java/cn/bugstack/competitoragent/common/http/HardTimeoutHttpClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/common/http/HardTimeoutHttpClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/testsupport/NeverCompletingHttpClient.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/PageContentExtractionSupportTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HttpSearchSourceProviderTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/DomainVerificationClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/DomainVerificationClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/OpenAiCompatibleClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/OpenAiCompatibleClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RssFeedClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProviderTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProviderTest.java`

## Task 1: 公共硬超时 HTTP helper

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/common/http/HardTimeoutHttpClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/common/http/HardTimeoutHttpClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/testsupport/NeverCompletingHttpClient.java`

- [ ] **Step 1: Write the failing helper tests**

`HardTimeoutHttpClientTest` 至少覆盖三类行为：

```java
@Test
void shouldCancelFutureWhenResponseNeverCompletes() {
    NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com"))
            .timeout(Duration.ofMillis(100))
            .GET()
            .build();

    assertThatThrownBy(() -> HardTimeoutHttpClient.send(
            httpClient,
            request,
            HttpResponse.BodyHandlers.ofString(),
            Duration.ofMillis(100)
    )).isInstanceOf(TimeoutException.class);

    assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
    assertThat(httpClient.cancelled()).isTrue();
}

@Test
void shouldAddBoundedGraceToProtocolTimeout() {
    assertThat(HardTimeoutHttpClient.resolveHardTimeout(Duration.ofMillis(100))).isEqualTo(Duration.ofMillis(200));
    assertThat(HardTimeoutHttpClient.resolveHardTimeout(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(32));
}

@Test
void shouldPreserveInterruptFlagWhenCallerThreadIsInterrupted() {
    NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com"))
            .GET()
            .build();

    try {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> HardTimeoutHttpClient.send(
                httpClient,
                request,
                HttpResponse.BodyHandlers.ofString(),
                Duration.ofMillis(100)
        )).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(httpClient.cancelled()).isTrue();
    } finally {
        Thread.interrupted();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -pl backend -Dtest=HardTimeoutHttpClientTest test
```

Expected: FAIL because `HardTimeoutHttpClient` and `NeverCompletingHttpClient` do not exist.

- [ ] **Step 3: Add minimal production helper**

`HardTimeoutHttpClient` 必须集中处理四件事：`sendAsync`、`protocolTimeout + grace`、`get(timeout)`、取消 future/恢复中断。

```java
package cn.bugstack.competitoragent.common.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK HttpClient 的同步 send() 内部使用无超时的 CompletableFuture.get()。
 * 该工具统一提供调用方硬超时，避免采集、搜索、验证和 LLM 链路被外部 HTTP 长时间拖挂。
 */
public final class HardTimeoutHttpClient {

    private static final long MIN_TIMEOUT_GRACE_MILLIS = 100L;
    private static final long MAX_TIMEOUT_GRACE_MILLIS = 2_000L;

    private HardTimeoutHttpClient() {
    }

    public static <T> HttpResponse<T> send(HttpClient httpClient,
                                           HttpRequest request,
                                           HttpResponse.BodyHandler<T> bodyHandler,
                                           Duration protocolTimeout)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<HttpResponse<T>> responseFuture = null;
        long timeoutMillis = resolveHardTimeout(protocolTimeout).toMillis();
        try {
            responseFuture = httpClient.sendAsync(request, bodyHandler);
            return responseFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            cancelFuture(responseFuture);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (TimeoutException exception) {
            cancelFuture(responseFuture);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("http request failed: " + (cause == null ? exception.getMessage() : cause.getMessage()),
                    cause == null ? exception : cause);
        }
    }

    public static Duration resolveHardTimeout(Duration protocolTimeout) {
        Duration normalizedTimeout = Objects.requireNonNull(protocolTimeout, "protocolTimeout");
        long protocolTimeoutMillis = Math.max(1L, normalizedTimeout.toMillis());
        long tenPercentGraceMillis = Math.max(1L, Math.round(protocolTimeoutMillis * 0.1D));
        long boundedGraceMillis = Math.max(MIN_TIMEOUT_GRACE_MILLIS,
                Math.min(MAX_TIMEOUT_GRACE_MILLIS, tenPercentGraceMillis));
        return Duration.ofMillis(protocolTimeoutMillis + boundedGraceMillis);
    }

    private static void cancelFuture(CompletableFuture<?> responseFuture) {
        if (responseFuture != null) {
            responseFuture.cancel(true);
        }
    }
}
```

- [ ] **Step 4: Add reusable never-completing test client**

`NeverCompletingHttpClient` 放到 `cn.bugstack.competitoragent.testsupport`，供所有迁移测试复用。实现需要覆盖 `sendAsync` 两个重载，并让 `send()` 直接抛 `AssertionError`，确保迁移后测试不会误走阻塞式路径。

- [ ] **Step 5: Run helper tests**

Run:

```powershell
mvn -pl backend -Dtest=HardTimeoutHttpClientTest test
```

Expected: PASS, `Tests run: 3, Failures: 0, Errors: 0`.

## Task 2: P0 主采集链路迁移

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/PageContentExtractionSupportTest.java`

- [ ] **Step 1: Write failing tests for DirectHtmlReaderClient and JinaReaderClient**

两者已有 `HttpClient` 注入构造器，直接复用 `NeverCompletingHttpClient`。断言目标：

```java
CollectionResult result = assertTimeoutPreemptively(Duration.ofSeconds(1),
        () -> client.collect(request));

assertThat(result.isSuccess()).isFalse();
assertThat(httpClient.cancelled()).isTrue();
assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
```

Expected before migration: FAIL because当前实现调用 `send()`，测试客户端会抛 `AssertionError`。

- [ ] **Step 2: Replace DirectHtmlReaderClient and JinaReaderClient send()**

替换模式：

```java
HttpRequest httpRequest = buildRequest(request);
HttpResponse<String> response = HardTimeoutHttpClient.send(
        httpClient,
        httpRequest,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        httpRequest.timeout().orElse(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
);
```

这里传入的是协议 timeout，helper 会自动加有界 grace。保留原有 `catch (InterruptedException)` 中断标记恢复和原有 fail-open 结果构造。

- [ ] **Step 3: Make PlaywrightPageCollector HTTP client injectable**

将内联字段：

```java
private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
```

改成构造器注入字段：

```java
private final HttpClient httpClient;

private static HttpClient buildHttpClient() {
    return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
}
```

现有 public 构造器继续传 `buildHttpClient()`，测试新增包内构造器传 `NeverCompletingHttpClient`。新增构造器签名固定为：

```java
PlaywrightPageCollector(PlaywrightBrowserManager browserManager,
                        CollectorProperties collectorProperties,
                        SearchRuntimeFallbackPolicy fallbackPolicy,
                        BrowserFailureClassifier browserFailureClassifier,
                        AntiBotSignalDetector antiBotSignalDetector,
                        BrowserRuntimeDiagnosticLogger diagnosticLogger,
                        CanonicalUrlResolver canonicalUrlResolver,
                        PublicShellRecoveryExtractor publicShellRecoveryExtractor,
                        HttpClient httpClient) {
    this.browserManager = browserManager;
    this.collectorProperties = collectorProperties;
    this.fallbackPolicy = fallbackPolicy;
    this.browserFailureClassifier = browserFailureClassifier;
    this.antiBotSignalDetector = antiBotSignalDetector;
    this.diagnosticLogger = diagnosticLogger;
    this.canonicalUrlResolver = canonicalUrlResolver;
    this.publicShellRecoveryExtractor = publicShellRecoveryExtractor == null
            ? new PublicShellRecoveryExtractor()
            : publicShellRecoveryExtractor;
    this.httpClient = httpClient == null ? buildHttpClient() : httpClient;
}
```

为了只测试 HTTP 快路、不触发浏览器渲染，把 `collectByHttp` 从 `private` 改为包内可见：

```java
CollectedPage collectByHttp(String url, String competitorName, String sourceType) {
    ...
}
```

- [ ] **Step 4: Replace PlaywrightPageCollector collectByHttp send()**

替换代码必须保持 HTTP 快路使用页面采集配置，不引入短固定值：

```java
Duration protocolTimeout = request.timeout()
        .orElse(Duration.ofSeconds(Math.max(1, collectorProperties.getPageTimeoutSeconds())));
HttpResponse<String> response = HardTimeoutHttpClient.send(
        httpClient,
        request,
        HttpResponse.BodyHandlers.ofString(),
        protocolTimeout
);
```

新增单测直接调用包内 `collectByHttp`，避免测试进入 `collectByBrowser`：

```java
@Test
void shouldFailOpenHttpFastPathWhenHttpFutureNeverCompletes() {
    NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
    CollectorProperties properties = new CollectorProperties();
    properties.setPageTimeoutSeconds(1);
    PlaywrightPageCollector collector = new PlaywrightPageCollector(
            browserManager,
            properties,
            fallbackPolicy,
            new BrowserFailureClassifier(),
            new AntiBotSignalDetector(new SearchBrowserProperties()),
            diagnosticLogger,
            new CanonicalUrlResolver(),
            new PublicShellRecoveryExtractor(),
            httpClient
    );

    CollectedPage page = assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> collector.collectByHttp("https://example.com/docs", "Acme AI", "DOCS"));

    assertThat(page.isSuccess()).isFalse();
    assertThat(page.getErrorMessage()).contains("HTTP collect failed");
    assertThat(httpClient.cancelled()).isTrue();
}
```

超时时返回现有 `failed(url, competitorName, sourceType, "...")` 语义，不抛出到外层采集主链路；不得把 HTTP 快路改为固定 3s 或其他短 timeout。

- [ ] **Step 5: Replace PageContentExtractionSupport static send()**

把 `fetchExternalScript` 拆成默认入口和包内可测试入口。不要把 `EXTERNAL_SCRIPT_HTTP_CLIENT` 改成全局可变字段，避免测试污染生产静态状态。

```java
private static String fetchExternalScript(String scriptUrl) {
    return fetchExternalScript(scriptUrl, EXTERNAL_SCRIPT_HTTP_CLIENT);
}

static String fetchExternalScript(String scriptUrl, HttpClient httpClient) {
    try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(scriptUrl))
                .timeout(EXTERNAL_SCRIPT_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = HardTimeoutHttpClient.send(
                httpClient,
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                EXTERNAL_SCRIPT_TIMEOUT
        );
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
    } catch (Exception exception) {
        log.debug("external script fetch failed, url={}, error={}", scriptUrl, exception.getMessage());
    }
    return "";
}
```

新增单测直接调用包内重载：

```java
@Test
void shouldFailOpenExternalScriptFetchWhenHttpFutureNeverCompletes() {
    NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();

    String content = assertTimeoutPreemptively(Duration.ofSeconds(3),
            () -> PageContentExtractionSupport.fetchExternalScript("https://example.com/app.js", httpClient));

    assertThat(content).isEmpty();
    assertThat(httpClient.cancelled()).isTrue();
}
```

这里传入的是 `EXTERNAL_SCRIPT_TIMEOUT` 协议 timeout，helper 会得到约 2.2s 的外层硬超时。新增包内静态重载 `fetchExternalScript(String scriptUrl, HttpClient httpClient)` 供测试注入永不完成客户端，生产入口继续调用默认客户端。

- [ ] **Step 6: Run P0 tests**

Run:

```powershell
mvn -pl backend -Dtest=DirectHtmlReaderClientTest,JinaReaderClientTest,PlaywrightPageCollectorTest,PageContentExtractionSupportTest test
```

Expected: PASS, 所有新增 hard-timeout 测试和既有采集测试通过。

## Task 3: P1 搜索、验证、LLM 路径迁移

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HttpSearchSourceProviderTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/DomainVerificationClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/DomainVerificationClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/OpenAiCompatibleClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/OpenAiCompatibleClientTest.java`

- [ ] **Step 1: Add failing hard-timeout tests**

每个客户端都使用 `NeverCompletingHttpClient`。如果构造器当前不能注入 `HttpClient`，先让测试以 constructor undefined 失败，再补包内构造器。

- [ ] **Step 2: Replace HttpSearchSourceProvider send()**

使用 `HardTimeoutHttpClient.send(...)`，传入协议 timeout `Duration.ofSeconds(properties.getTimeoutSeconds())`，由 helper 自动加 grace。异常继续走现有 `SearchApiException` 或 fail-open 空列表语义。

- [ ] **Step 3: Replace DomainVerificationClient send()**

使用 `HardTimeoutHttpClient.send(...)`，传入 request timeout 或当前配置 timeout 作为协议 timeout，由 helper 自动加 grace。超时必须返回验证失败/不可用，而不是阻断候选验证线程池。

- [ ] **Step 4: Replace OpenAiCompatibleClient postJson send()**

`postJson` 当前集中服务 embedding/rerank，替换一处即可覆盖两个能力：

```java
HttpResponse<String> response = HardTimeoutHttpClient.send(
        httpClient,
        request,
        HttpResponse.BodyHandlers.ofString(),
        timeout == null ? Duration.ofSeconds(aiProps.getTimeoutSeconds()) : timeout
);
```

这里的 `timeout` 是协议 timeout，不是最终 `Future.get()` 的硬 timeout；helper 会自动加 grace。`TimeoutException` 需要被包装为 `LlmException`，避免调用方感知 checked exception。

- [ ] **Step 5: Run P1 tests**

Run:

```powershell
mvn -pl backend -Dtest=HttpSearchSourceProviderTest,DomainVerificationClientTest,OpenAiCompatibleClientTest,CandidateVerifierTest test
```

Expected: PASS。

## Task 4: P2 源配置 provider 迁移

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedClient.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RssFeedClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiClientTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProviderTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProviderTest.java`

- [ ] **Step 1: Add failing hard-timeout tests**

每个 provider 至少新增一个“future 永不完成时在 1 秒内 fail-open/失败返回”的测试。断言：

```java
assertTimeoutPreemptively(Duration.ofSeconds(1), () -> provider.search(request));
assertThat(httpClient.cancelled()).isTrue();
```

- [ ] **Step 2: Replace provider send() calls**

所有 provider 使用 `HardTimeoutHttpClient.send(...)`，传入现有 request timeout 或 properties timeout 作为协议 timeout，由 helper 自动加 grace。保留原有 retry 次数和错误文案。

- [ ] **Step 3: Run P2 tests**

Run:

```powershell
mvn -pl backend -Dtest=RssFeedClientTest,GithubApiClientTest,QianfanSearchSourceProviderTest,SerpApiSearchSourceProviderTest test
```

Expected: PASS。

## Task 5: 全库验收与防回归

**Files:**
- Modify: `docs/Tavily/task/2026-07-04-15-task86-httpclient-send-hard-timeout-systemic-closure-plan.md`

- [ ] **Step 1: Verify no production blocking send remains**

Run:

```powershell
rg -n "httpClient\.send\(|EXTERNAL_SCRIPT_HTTP_CLIENT\.send\(" backend/src/main/java -g "*.java"
```

Expected: no output.

- [ ] **Step 2: Run hard-timeout related test suite**

Run:

```powershell
mvn -pl backend -Dtest=HardTimeoutHttpClientTest,SitemapDiscoveryServiceTest,DirectHtmlReaderClientTest,JinaReaderClientTest,PlaywrightPageCollectorTest,PageContentExtractionSupportTest,HttpSearchSourceProviderTest,DomainVerificationClientTest,OpenAiCompatibleClientTest,RssFeedClientTest,GithubApiClientTest,QianfanSearchSourceProviderTest,SerpApiSearchSourceProviderTest,CandidateVerifierTest test
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Run collector/search regression slice**

Run:

```powershell
mvn -pl backend -Dtest=CollectorAgentTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest test
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Record final status**

在本文件 `## 执行记录` 下追加：

```markdown
### YYYY-MM-DD HH:mm

当前阶段：全库 HTTP 硬超时收口完成
[x] 信息采集：已完成
[x] 公共封装：已完成
[x] 主链路迁移：已完成
[x] 全库验收：已完成

验证：
- `rg -n "httpClient\.send\(|EXTERNAL_SCRIPT_HTTP_CLIENT\.send\(" backend/src/main/java -g "*.java"`：无输出
- `mvn -pl backend -Dtest=... test`：BUILD SUCCESS
```

## 验收标准

- 生产代码中 `httpClient.send(` 和 `EXTERNAL_SCRIPT_HTTP_CLIENT.send(` 均为 0。
- 每个迁移客户端至少有一个永不完成 future 的 hard-timeout 测试。
- 所有新增 hard-timeout 测试都证明 future 被取消。
- 所有 `InterruptedException` 路径都恢复中断标记。
- 原有 retry/fail-open/错误 DTO 语义不被公共 helper 改写。
- `SitemapDiscoveryService` 已完成的 task86 热修不回退。

## 执行记录

### 2026-07-04 17:20

当前阶段：系统性问题已建档，待执行实现  
[x] 信息采集：已完成  
[x] 数据分析：已完成  
[ ] 公共封装：待执行  
[ ] 主链路迁移：待执行  
[ ] 全库验收：待执行  

记录：
- 当前生产剩余 11 个阻塞式 `HttpClient.send()` 点。
- task86 sitemap 热修已移除 `SitemapDiscoveryService` 的阻塞式 `send()`。
- 本文档只建档和拆解计划，尚未执行 11 个剩余点迁移。

### 2026-07-05 14:08

当前阶段：全库 HTTP 硬超时收口完成  
[x] 信息采集：已完成  
[x] 数据分析：已完成  
[x] 公共封装：已完成  
[x] 主链路迁移：已完成  
[x] 全库验收：已完成  

记录：
- 已新增公共 `HardTimeoutHttpClient`，统一通过 `sendAsync(...).get(protocolTimeout + grace)` 实现调用层硬超时，并在超时/中断时取消 future。
- 已新增可复用测试替身 `NeverCompletingHttpClient`，覆盖 Direct/Jina/Playwright/PageContentExtraction、HTTP Search、Domain Verification、OpenAI Compatible、RSS、GitHub、Qianfan、SerpApi 等所有迁移点。
- 已将生产代码中剩余 11 处阻塞式 `HttpClient.send()` / `EXTERNAL_SCRIPT_HTTP_CLIENT.send()` 全部迁移完成，原有 retry、fail-open、错误 DTO 与中断恢复语义保持不变。
- `DomainVerificationClient` 额外修正为仅在 HEAD 返回 `403/405` 时才降级 GET，避免 timeout/IO 失败再额外阻塞一轮探测预算。

验证：
- `rg -n "httpClient\.send\(|EXTERNAL_SCRIPT_HTTP_CLIENT\.send\(" backend/src/main/java -g "*.java"`：无输出
- `mvn -pl backend "-Dtest=HardTimeoutHttpClientTest,SitemapDiscoveryServiceTest,DirectHtmlReaderClientTest,JinaReaderClientTest,PlaywrightPageCollectorTest,PageContentExtractionSupportTest,HttpSearchSourceProviderTest,DomainVerificationClientTest,OpenAiCompatibleClientTest,RssFeedClientTest,GithubApiClientTest,QianfanSearchSourceProviderTest,SerpApiSearchSourceProviderTest,CandidateVerifierTest" test`：BUILD SUCCESS
- `mvn -pl backend "-Dtest=CollectorAgentTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest" test`：BUILD SUCCESS
