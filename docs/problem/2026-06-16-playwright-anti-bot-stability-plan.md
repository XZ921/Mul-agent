# Playwright 运行时稳定性与反爬治理实施计划

**目标：** 把“浏览器自身不稳定”和“目标站点反爬拦截”拆开治理，降低采集失败率，并让失败具备稳定分类、结构化留痕、明确降级和可恢复语义。

**难度：** 高

**规模：** 中大型专项，跨浏览器运行时、采集执行、任务编排、日志观测、配置治理和集成测试

**结论：** 这个问题可以直接开干，但必须拆成两条子问题并行推进，不能继续用一个“稳定性”口袋把所有异常混治。

---

## 一、问题分层

### 1. 基础设施稳定性问题

这类问题的核心是“浏览器还能不能继续工作”，不是“目标站点让不让你看”。

典型信号：

1. `playwright connection closed`
2. `transport closed`
3. `pipe closed`
4. `target page, context or browser has been closed`
5. `browser has been closed`
6. `Protocol error (Target.createTarget)` / `newPage` 失败

治理目标：

1. 精确判断故障粒度是 `page`、`context`、`browser` 还是 `runtime`
2. 避免普通页面失败误触发共享 browser 重启
3. 让恢复动作只影响当前故障粒度，不连坐其他并发任务

### 2. 反爬访问控制问题

这类问题的核心是“站点把你识别成异常访问者并拒绝服务”。

典型信号：

1. 页面文本出现 `captcha`、`verify you are human`、`access denied`
2. HTTP 状态码 `403` / `429`
3. 跳转到登录页、验证页、challenge 页
4. DOM 存在但正文为空或只有壳
5. 搜索结果页存在但关键目标元素全部缺失
6. 页面标题、URL、DOM 结构显示为登录/验证/风控中转页

治理目标：

1. 降低被识别概率
2. 尽早识别被拦截，减少无效重试
3. 让被拦截场景快速回退到 HTTP / 规划候选 / 人工介入

---

## 二、直接实施前的设计定稿

### 1. 重启决策树

这是本专项最重要的设计约束，实施时不得再临场发挥。

#### 1.1 决策原则

1. `page` 级错误先关当前 `page`，不动 `context`
2. `context` 级错误先关当前 `context`，不动共享 `browser`
3. 只有确认共享 `browser` 失活时，才允许 `restartBrowserIfCurrent`
4. 只有确认底层 Playwright 管道断开时，才允许 `recreatePlaywrightRuntime`
5. 反爬命中不得触发共享 `browser` 或 `runtime` 重启

#### 1.2 异常到动作映射

| 异常/信号 | 分类 | 动作 | 是否允许重启共享 browser | 是否允许重建 runtime |
| --- | --- | --- | --- | --- |
| `Target page, context or browser has been closed` | `BROWSER_INSTANCE_DEAD` | 当前请求失败，重建共享 browser，再单次重试 | 是 | 否 |
| `browser has been closed` | `BROWSER_INSTANCE_DEAD` | 当前请求失败，重建共享 browser，再单次重试 | 是 | 否 |
| `playwright connection closed` | `RUNTIME_PIPE_BROKEN` | 先重建 runtime，再重建 browser，再单次重试 | 是 | 是 |
| `transport closed` / `pipe closed` | `RUNTIME_PIPE_BROKEN` | 先重建 runtime，再重建 browser，再单次重试 | 是 | 是 |
| `Protocol error (Target.createTarget)` | `PAGE_OR_CONTEXT_RESOURCE_FAILURE` | 只关闭当前 context/page，按普通失败处理，不动共享 browser | 否 | 否 |
| 超时 | `SEARCH_TIMEOUT` / `PAGE_TIMEOUT` | 关闭当前 page/context，按策略回退 | 否 | 否 |
| 403 / 429 / captcha / challenge / login redirect | `ANTI_BOT_BLOCKED` | 立即停止浏览器重试，直接降级 | 否 | 否 |
| 正文为空 / DOM 壳页 / 关键元素缺失 | `CONTENT_UNUSABLE` | 允许一次页面内降级或 HTTP 回退 | 否 | 否 |

#### 1.3 实施落点

必须新增一个统一分类器，禁止 `BrowserSearchRuntimeService` 和 `PlaywrightPageCollector` 继续各自用字符串判断。

建议新增：

```java
package cn.bugstack.competitoragent.search;

public enum BrowserFailureKind {
    PAGE_TIMEOUT,
    SEARCH_TIMEOUT,
    PAGE_OR_CONTEXT_RESOURCE_FAILURE,
    BROWSER_INSTANCE_DEAD,
    RUNTIME_PIPE_BROKEN,
    ANTI_BOT_BLOCKED,
    CONTENT_UNUSABLE,
    RUNTIME_FAILURE
}
```

并新增：

```java
package cn.bugstack.competitoragent.search;

public record BrowserFailureDecision(
        BrowserFailureKind kind,
        boolean closePageOnly,
        boolean closeContextOnly,
        boolean restartSharedBrowser,
        boolean recreateRuntime,
        boolean allowSingleRetry,
        boolean fallbackToHttp,
        boolean markBlocked
) {
}
```

所有浏览器链路都只能消费 `BrowserFailureDecision`，不得再直接用 `contains("connection closed")` 之类散落判断。

---

### 2. 反爬检测模型

反爬检测必须从“单一文本匹配”升级成“多信号组合判定”。

#### 2.1 检测维度

1. **HTTP 信号**
   - 状态码 `403`
   - 状态码 `429`
   - 响应头里明显的 challenge / deny 特征
2. **URL/跳转信号**
   - 最终 URL 命中 `/login`、`/signin`、`/verify`、`/captcha`、`/challenge`
   - 跳转域名不是原目标域，而是风控中间页
3. **标题信号**
   - `Access Denied`
   - `Verify you are human`
   - `Security Check`
   - 中文登录/验证标题
4. **正文信号**
   - `captcha`
   - `unusual traffic`
   - `robot check`
   - `请验证您是人类`
   - `访问受限`
5. **结构信号**
   - 页面 body 存在但正文长度极短
   - 只有登录表单、验证码组件，没有正文容器
   - 搜索结果页缺失主结果列表和结果项
6. **行为信号**
   - 连续多个 query 在同一引擎同一任务下都返回相同 challenge 页面

#### 2.2 判定规则

不得使用“命中任意一个关键词就 blocked”的单点规则，改为分层判定：

1. 命中 `403/429` 直接判定 `ANTI_BOT_BLOCKED`
2. 命中“验证 URL + 验证标题”判定 `ANTI_BOT_BLOCKED`
3. 命中“验证正文词 + 正文极短/壳页”判定 `ANTI_BOT_BLOCKED`
4. 仅命中文本关键词但结构正常时，标记 `SUSPECTED_BLOCKED`，不直接 blocked
5. 搜索结果页没有正文但也没有验证信号时，归类为 `CONTENT_UNUSABLE`，不能判 blocked

#### 2.3 阈值配置化约束

`missingPrimaryResults` 和 `bodyTooShort` 不允许在代码里写死魔法数字，必须配置化。

建议新增配置：

```yaml
search:
  browser:
    anti-bot:
      short-body-threshold: 120
      minimum-primary-result-count: 1
      suspect-blocked-body-threshold: 40
```

判定口径固定为：

1. `bodyTooShort = true`
   - 当提取后的正文字符数 `< short-body-threshold`
2. `missingPrimaryResults = true`
   - 当搜索结果页主结果容器数 `< minimum-primary-result-count`
3. `suspected blocked`
   - 当正文字符数 `< suspect-blocked-body-threshold` 且命中文本/标题/URL 风险信号之一

默认值解释：

1. `short-body-threshold=120`
   - 低于 120 字通常不足以支撑有效正文抽取
2. `minimum-primary-result-count=1`
   - 连一个主结果块都没有，说明结果页很可能不是正常搜索页
3. `suspect-blocked-body-threshold=40`
   - 低于 40 字时，极容易是 challenge/login 壳页

#### 2.4 需要新增的数据结构

建议新增：

```java
package cn.bugstack.competitoragent.search;

@Data
@Builder
public class AntiBotDetectionResult {
    private boolean blocked;
    private boolean suspected;
    private String reasonCode;
    private Integer httpStatus;
    private String finalUrl;
    private String pageTitle;
    private List<String> matchedSignals;
    private List<String> matchedSelectors;
}
```

---

### 3. 指纹伪装策略

本专项不能只做“被拦后如何降级”，必须包含“如何降低被拦概率”。

#### 3.1 第一阶段必须做的最小 stealth

在 `BrowserSearchRuntimeService` 新建 `BrowserContext` 后、打开页面前，统一注入：

1. `navigator.webdriver = undefined`
2. `window.chrome` 基础对象
3. `permissions.query` 对通知权限的正常返回
4. `languages` / `platform` / `hardwareConcurrency` 等基础伪装
5. 合法的 `userAgent`
6. 合法的 `locale`、`timezoneId`、`viewport`

建议新增：

```java
private void applyStealthDefaults(BrowserContext context, CollectorNodeConfig config) {
    context.addInitScript("""
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        window.chrome = window.chrome || { runtime: {} };
    """);
}
```

#### 3.2 本轮明确不做

1. 完整 stealth 插件生态接入
2. WebGL/Canvas/字体的重度指纹仿真
3. 住宅代理 / IP 池 / 外部 anti-bot 服务

这些属于后续能力建设，本轮只做最小可落地伪装。

---

### 4. 可观测性设计

没有结构化观测，这个专项不能算完成。

#### 4.1 结构化日志

在以下位置打统一结构化日志：

1. `BrowserSearchRuntimeService.searchOnce(...)`
2. `PlaywrightPageCollector.collectByBrowser(...)`
3. `PlaywrightBrowserManager.launchBrowser(...)`
4. `PlaywrightBrowserManager.recreatePlaywrightRuntime(...)`

每条日志至少包含：

1. `taskId`
2. `nodeName`
3. `competitorName`
4. `sourceType`
5. `query`
6. `targetUrl`
7. `engineKey`
8. `failureKind`
9. `restartScope`
10. `fallbackAction`
11. `blockedReasonCode`
12. `matchedSignals`

建议新增独立日志 DTO：

```java
@Data
@Builder
public class BrowserRuntimeDiagnosticLog {
    private Long taskId;
    private String nodeName;
    private String query;
    private String targetUrl;
    private String failureKind;
    private String restartScope;
    private String fallbackAction;
    private String blockedReasonCode;
    private List<String> matchedSignals;
}
```

#### 4.2 Metrics

如果项目已有 Micrometer/Actuator，直接接入 `MeterRegistry`；如果当前没有，就先做结构化日志和可聚合计数日志，不强行引入新依赖。

指标口径必须固定：

1. `browser.runtime.restart.count`
   - tag: `scope=browser|runtime`
2. `browser.runtime.failure.count`
   - tag: `kind`
3. `browser.antibot.blocked.count`
   - tag: `reasonCode`
4. `browser.fallback.count`
   - tag: `action=http|planned|intervention`
5. `browser.duplicate_url.skipped.count`
6. `browser.waiting_intervention.count`

---

## 三、实施任务

### Task 1: 拆出统一故障分类与决策树

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserFailureKind.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserFailureDecision.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserFailureClassifier.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserFailureClassifierTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`

- [ ] **Step 1: 先写统一分类器失败测试**

```java
@Test
void shouldClassifyPipeClosedAsRuntimePipeBroken() {
    BrowserFailureDecision decision = classifier.classify(
            new IllegalStateException("playwright connection closed"),
            null
    );

    assertEquals(BrowserFailureKind.RUNTIME_PIPE_BROKEN, decision.kind());
    assertTrue(decision.recreateRuntime());
    assertTrue(decision.restartSharedBrowser());
}
```

- [ ] **Step 2: 再写“new tab 失败不允许重启共享 browser”的测试**

```java
@Test
void shouldTreatCreateTargetFailureAsPageOrContextFailure() {
    BrowserFailureDecision decision = classifier.classify(
            new IllegalStateException("Protocol error (Target.createTarget): Failed to open a new tab"),
            null
    );

    assertEquals(BrowserFailureKind.PAGE_OR_CONTEXT_RESOURCE_FAILURE, decision.kind());
    assertFalse(decision.restartSharedBrowser());
    assertFalse(decision.recreateRuntime());
}
```

- [ ] **Step 3: 跑测试确认当前能力不满足**

Run: `mvn -pl backend -Dtest=BrowserFailureClassifierTest,BrowserSearchRuntimeServiceTest,PlaywrightPageCollectorTest test`

Expected: FAIL

- [ ] **Step 4: 写最小实现**

```java
public BrowserFailureDecision classify(Throwable error, AntiBotDetectionResult detection) {
    String normalized = normalize(error);
    if (normalized.contains("playwright connection closed")
            || normalized.contains("transport closed")
            || normalized.contains("pipe closed")) {
        return new BrowserFailureDecision(BrowserFailureKind.RUNTIME_PIPE_BROKEN, false, false, true, true, true, true, false);
    }
    if (normalized.contains("target page, context or browser has been closed")
            || normalized.contains("browser has been closed")) {
        return new BrowserFailureDecision(BrowserFailureKind.BROWSER_INSTANCE_DEAD, false, false, true, false, true, true, false);
    }
    if (normalized.contains("target.createtarget")) {
        return new BrowserFailureDecision(BrowserFailureKind.PAGE_OR_CONTEXT_RESOURCE_FAILURE, false, true, false, false, false, true, false);
    }
    return new BrowserFailureDecision(BrowserFailureKind.RUNTIME_FAILURE, false, true, false, false, false, true, false);
}
```

- [ ] **Step 5: 让运行时和采集器统一消费决策树**

```java
BrowserFailureDecision decision = browserFailureClassifier.classify(e, detectionResult);
if (decision.recreateRuntime()) {
    browserManager.recreateRuntimeForFailure(reason, e);
}
if (decision.restartSharedBrowser()) {
    browserManager.restartBrowserIfCurrent(runtimeBrowser, reason);
}
```

- [ ] **Step 6: 回归测试**

Run: `mvn -pl backend -Dtest=BrowserFailureClassifierTest,BrowserSearchRuntimeServiceTest,PlaywrightPageCollectorTest test`

Expected: PASS

### Task 2: 建立多信号反爬检测模型

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/AntiBotDetectionResult.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/AntiBotSignalDetector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/AntiBotSignalDetectorTest.java`

- [ ] **Step 1: 先写 403/429 直接 blocked 的测试**

```java
@Test
void shouldMark403AsBlocked() {
    AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
            .httpStatus(403)
            .finalUrl("https://example.com/captcha")
            .pageTitle("Access Denied")
            .bodyText("Access denied")
            .build());

    assertTrue(result.isBlocked());
    assertEquals("HTTP_STATUS_BLOCKED", result.getReasonCode());
}
```

- [ ] **Step 2: 写“登录跳转 + 验证标题” blocked 测试**

```java
assertTrue(result.isBlocked());
assertEquals("LOGIN_OR_CHALLENGE_REDIRECT", result.getReasonCode());
```

- [ ] **Step 3: 写“只有文本词但结构正常时不直接 blocked”的测试**

```java
assertFalse(result.isBlocked());
assertTrue(result.isSuspected());
```

- [ ] **Step 4: 跑测试确认当前实现不具备该能力**

Run: `mvn -pl backend -Dtest=AntiBotSignalDetectorTest test`

Expected: FAIL

- [ ] **Step 5: 新增信号快照和检测器**

```java
@Data
@Builder
public class BrowserSignalSnapshot {
    private Integer httpStatus;
    private String finalUrl;
    private String pageTitle;
    private String bodyText;
    private boolean missingPrimaryResults;
    private boolean bodyTooShort;
    private int primaryResultCount;
    private int bodyLength;
}
```

- [ ] **Step 6: 为结构信号阈值增加配置字段**

```java
private Integer shortBodyThreshold;
private Integer minimumPrimaryResultCount;
private Integer suspectBlockedBodyThreshold;
```

```yaml
search:
  browser:
    short-body-threshold: 120
    minimum-primary-result-count: 1
    suspect-blocked-body-threshold: 40
```

- [ ] **Step 7: 配置多维 blocked signals**

```yaml
search:
  browser:
    blocked-signals:
      - captcha
      - unusual traffic
      - verify you are human
      - access denied
      - robot check
      - security check
      - 请验证您是人类
      - 访问受限
      - 风险验证
    blocked-url-keywords:
      - /login
      - /signin
      - /verify
      - /captcha
      - /challenge
```

- [ ] **Step 8: 先写阈值驱动的检测测试**

```java
@Test
void shouldMarkBodyTooShortWhenLengthBelowConfiguredThreshold() {
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setShortBodyThreshold(120);

    BrowserSignalSnapshot snapshot = BrowserSignalSnapshot.builder()
            .bodyText("short text")
            .bodyLength(10)
            .primaryResultCount(2)
            .build();

    AntiBotDetectionResult result = detector.detect(snapshot);

    assertTrue(result.isSuspected());
}
```

```java
@Test
void shouldMarkMissingPrimaryResultsWhenCountBelowConfiguredMinimum() {
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setMinimumPrimaryResultCount(1);

    BrowserSignalSnapshot snapshot = BrowserSignalSnapshot.builder()
            .bodyText("normal body content with enough length")
            .bodyLength(200)
            .primaryResultCount(0)
            .build();

    AntiBotDetectionResult result = detector.detect(snapshot);

    assertTrue(result.isSuspected());
}
```

- [ ] **Step 9: 在 runtime search 和 page collect 两条链路接入检测器**

```java
AntiBotDetectionResult detection = antiBotSignalDetector.detect(snapshot);
if (detection.isBlocked()) {
    return new SearchAttemptResult(List.of(), "blocked");
}
```

- [ ] **Step 10: 构造 `bodyLength` 和 `primaryResultCount`，禁止在检测器内部写死阈值**

```java
BrowserSignalSnapshot snapshot = BrowserSignalSnapshot.builder()
        .httpStatus(httpStatus)
        .finalUrl(page.url())
        .pageTitle(page.title())
        .bodyText(bodyText)
        .bodyLength(bodyText == null ? 0 : bodyText.trim().length())
        .primaryResultCount(primaryResultCount)
        .missingPrimaryResults(primaryResultCount < properties.getMinimumPrimaryResultCount())
        .bodyTooShort(bodyLength < properties.getShortBodyThreshold())
        .build();
```

- [ ] **Step 11: 回归测试**

Run: `mvn -pl backend -Dtest=AntiBotSignalDetectorTest,BrowserSearchRuntimeServiceTest,PlaywrightPageCollectorTest test`

Expected: PASS

### Task 3: 接入最小 stealth 指纹伪装

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeServiceTest.java`

- [ ] **Step 1: 为 runtime policy 增加 stealth 配置字段**

```java
private Boolean stealthEnabled;
private String locale;
private String timezoneId;
private Integer viewportWidth;
private Integer viewportHeight;
```

- [ ] **Step 2: 在属性文件里补默认值**

```yaml
search:
  browser:
    stealth-enabled: true
    locale: zh-CN
    timezone-id: Asia/Shanghai
    viewport-width: 1440
    viewport-height: 900
```

- [ ] **Step 3: 写 BrowserContext 初始化测试**

```java
verify(browser.newContext(any(Browser.NewContextOptions.class)));
verify(context).addInitScript(contains("navigator"));
```

- [ ] **Step 4: 在创建 context 后注入 stealth**

```java
context.addInitScript("""
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US'] });
    window.chrome = window.chrome || { runtime: {} };
""");
```

- [ ] **Step 5: 回归测试**

Run: `mvn -pl backend -Dtest=BrowserSearchRuntimeServiceTest test`

Expected: PASS

### Task 4: 加入结构化观测和指标

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserRuntimeDiagnosticLog.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/config/PlaywrightBrowserManager.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeServiceTest.java`

- [ ] **Step 1: 扩展 trace 留痕字段**

```java
private String browserFailureKind;
private String browserRestartScope;
private String browserFallbackAction;
private List<String> browserMatchedSignals;
```

- [ ] **Step 2: 写 trace 字段投影测试**

```java
assertEquals("ANTI_BOT_BLOCKED", result.getExecutionTrace().getBrowserFailureKind());
assertEquals("HTTP_FALLBACK", result.getExecutionTrace().getBrowserFallbackAction());
```

- [ ] **Step 3: 在运行时写结构化日志**

```java
log.warn("browser runtime diagnostic, payload={}", objectMapper.writeValueAsString(
        BrowserRuntimeDiagnosticLog.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .query(query)
                .failureKind(decision.kind().name())
                .restartScope(restartScope)
                .fallbackAction(fallbackAction)
                .matchedSignals(detection.getMatchedSignals())
                .build()
));
```

- [ ] **Step 4: 如果项目有 `MeterRegistry`，补指标埋点；没有则记录结构化计数日志**

```java
meterRegistry.counter("browser.runtime.failure.count", "kind", decision.kind().name()).increment();
```

- [ ] **Step 5: 回归测试**

Run: `mvn -pl backend -Dtest=BrowserSearchRuntimeServiceTest test`

Expected: PASS

### Task 5: 减少重复抓取和放大反爬

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [ ] **Step 1: 写 canonical URL 去重测试**

```java
assertThat(selectedUrls).containsExactly("https://example.com/docs");
```

- [ ] **Step 2: 统一 URL 归一化规则**

```java
private String canonicalizeUrl(String url) {
    // 合并 http/https、折叠 www、移除跟踪参数、去尾斜杠
}
```

- [ ] **Step 3: 在候选合并、选择、采集前分别去重**

```java
if (!seenCanonicalUrls.add(canonicalUrl)) {
    continue;
}
```

- [ ] **Step 4: 当同域请求连续命中 blocked 时，提前停止该域继续浏览器访问**

```java
if (domainBlockedCount >= 2) {
    return new SearchAttemptResult(List.of(), "blocked");
}
```

- [ ] **Step 5: 回归测试**

Run: `mvn -pl backend -Dtest=SearchExecutionCoordinatorTest,CollectionTargetSelectorTest test`

Expected: PASS

### Task 6: 补本地真实浏览器集成测试

**Files:**
- Create: `backend/src/test/java/cn/bugstack/competitoragent/integration/PlaywrightAntiBotMockIntegrationTest.java`

- [ ] **Step 1: 启动本地 mock server，提供 4 类页面**

```java
GET /ok-docs -> 正常正文页
GET /captcha -> 200 + captcha 文本页
GET /deny -> 403 + denied 页面
GET /login-redirect -> 302 到 /login
```

- [ ] **Step 2: 写“captcha 页应 blocked 但不重启 runtime”的集成测试**

```java
assertEquals("ANTI_BOT_BLOCKED", trace.getBrowserFailureKind());
assertEquals("NONE", trace.getBrowserRestartScope());
```

- [ ] **Step 3: 写“pipe closed 模拟应触发 runtime 重建”的集成测试**

```java
assertEquals("RUNTIME_PIPE_BROKEN", trace.getBrowserFailureKind());
assertEquals("RUNTIME_AND_BROWSER", trace.getBrowserRestartScope());
```

- [ ] **Step 4: 写“正常页面不应被误判 blocked”的集成测试**

```java
assertFalse(result.isFallbackSuggested());
assertThat(result.getCandidates()).isNotEmpty();
```

- [ ] **Step 5: 运行集成测试**

Run: `mvn -pl backend -Dtest=PlaywrightAntiBotMockIntegrationTest test`

Expected: PASS

### Task 7: dev smoke 与文档回链

**Files:**
- Modify: `docs/problem/CollectorAgent.md`
- Modify: `docs/problem/2026-06-16-playwright-anti-bot-stability-plan.md`

- [ ] **Step 1: 补 smoke 脚本/命令**

验证端点：

1. `POST /api/task/preview`
2. `POST /api/task/create`
3. `POST /api/task/{id}/execute`
4. `GET /api/task/{id}/nodes`
5. `GET /api/task/{id}/replay`
6. `POST /api/task/{id}/resume`

- [ ] **Step 2: smoke 验收口径固定**

必须检查：

1. `browserFailureKind`
2. `browserRestartScope`
3. `browserFallbackAction`
4. `browserMatchedSignals`
5. `browserBlockedReason`
6. `WAITING_INTERVENTION` 是否只出现在预算耗尽或人工介入场景

- [ ] **Step 3: 复查指标**

必须输出：

1. `browser.runtime.failure.count`
2. `browser.runtime.restart.count`
3. `browser.antibot.blocked.count`
4. `browser.fallback.count`
5. `browser.waiting_intervention.count`

---

## 四、预计周期

1. 决策树与分类器：1 天
2. 反爬检测模型：1 天
3. stealth 最小实现：0.5 天
4. 可观测性：0.5-1 天
5. 去重与流量控制：0.5-1 天
6. 本地真实浏览器集成测试：1 天
7. dev smoke 与回链：0.5 天

合计：`5-6 天`

---

## 五、实施边界

### 本轮必须完成

1. 重启决策树落地
2. 反爬多信号检测
3. 最小 stealth
4. 结构化观测
5. 本地 mock 反爬集成测试

### 本轮不做

1. 代理池 / IP 池
2. 外部 anti-bot SaaS
3. 全量浏览器指纹仿真
4. 反爬控制台产品化界面

---

## 六、自检

### 覆盖检查

1. `重启决策树` 已通过 Task 1 明确到异常级别和动作级别。
2. `反爬不止文本匹配` 已通过 Task 2 扩成 HTTP/URL/标题/正文/结构/行为六维检测。
3. `运行时崩溃 vs 反爬拦截` 已在问题分层和 Task 1/2 中分开治理。
4. `可观测性` 已通过 Task 4 增加 trace、结构化日志和 metrics。
5. `指纹伪装` 已通过 Task 3 加入最小 stealth。
6. `集成测试` 已通过 Task 6 增加本地 mock 反爬页面真实浏览器测试。

### 占位符检查

1. 没有使用 `TODO` / `TBD`。
2. 每个关键设计点都落到了明确文件。
3. 每个任务都有可执行测试命令。

### 风险检查

1. 如果项目当前没有 `MeterRegistry`，Task 4 先退化为结构化日志计数，不阻塞主线。
2. 如果真实浏览器环境在 CI 不稳定，Task 6 先要求本地可重复，CI 后续再接。

---

## 七、Task 7 执行回填

### 1. 已落地的 smoke 验收

- 任务流 smoke：
  - `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java`
  - `shouldExposeTask7SmokeContractsAcrossPreviewNodesReplayAndResume`
- 指标口径 smoke：
  - `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserRuntimeDiagnosticLoggerTest.java`
  - `shouldEmitTask7ExpectedCounterMetrics`

### 2. 执行期最小修复

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 为 7 参构造器补 `@Autowired`，避免 Spring 在存在 6 参/7 参双构造器时误判为无默认构造器

### 3. Task 7 smoke 命令

1. 任务流 smoke：
   - `mvn -pl backend "-Dtest=Phase2WorkflowIntegrationTest#shouldExposeTask7SmokeContractsAcrossPreviewNodesReplayAndResume" test`
2. 指标口径 smoke：
   - `mvn -pl backend "-Dtest=BrowserRuntimeDiagnosticLoggerTest" test`
3. 合并验证：
   - `mvn -pl backend "-Dtest=Phase2WorkflowIntegrationTest#shouldExposeTask7SmokeContractsAcrossPreviewNodesReplayAndResume,BrowserRuntimeDiagnosticLoggerTest" test`

### 4. Task 7 smoke 验收口径

1. `POST /api/task/preview`
   - 返回 `contractType=TASK_PLAN_PREVIEW_V1`
   - `nodes` 非空
2. `POST /api/task/create`
   - 返回任务 `id`
   - 任务初始化成功
3. `POST /api/task/{id}/execute`
   - 任务进入执行链路
   - 当前 smoke 用例会走到可恢复停点
4. `GET /api/task/{id}/nodes`
   - `collectorInsight.searchExecutionTrace.browserFailureKind = ANTI_BOT_BLOCKED`
   - `collectorInsight.searchExecutionTrace.browserRestartScope = NONE`
   - `collectorInsight.searchExecutionTrace.browserFallbackAction = HTTP_FALLBACK`
   - `collectorInsight.searchExecutionTrace.browserMatchedSignals` 非空
   - `collectorInsight.searchExecutionTrace.browserBlockedReason = LOGIN_OR_CHALLENGE_REDIRECT`
5. `GET /api/task/{id}/replay`
   - `searchReplays[].searchAudit.executionTrace` 中同样能看到上述 5 个浏览器诊断字段
6. `POST /api/task/{id}/resume`
   - 任务可从停点恢复并最终成功
7. `WAITING_INTERVENTION`
   - 只允许出现在预算耗尽或人工介入场景
   - 当前 smoke 用例用“节点文案必须显式带人工语义”做守护断言

### 5. Task 7 指标复查口径

当前项目尚未接入真实 `MeterRegistry`，因此先以 `BrowserRuntimeDiagnosticLogger` 输出的结构化计数日志作为统一口径：

1. `browser.runtime.failure.count`
   - 例：`kind=RUNTIME_PIPE_BROKEN`
2. `browser.runtime.restart.count`
   - 例：`scope=runtime`
3. `browser.antibot.blocked.count`
   - 例：`reasonCode=LOGIN_OR_CHALLENGE_REDIRECT`
4. `browser.fallback.count`
   - 例：`action=planned|http|intervention`
5. `browser.waiting_intervention.count`
   - 例：`reasonCode=WAITING_INTERVENTION`
