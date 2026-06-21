# Search And Collection Config Readiness Remediation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把“架构已声明、运行时却因配置缺失而成为空架子”的几条关键链路收口成可验证、可观测、可诚实失败的正式能力，避免继续出现“文档说可用、测试能过、真实环境却根本跑不通”的状态分裂。

**Architecture:** 本次不扩新业务能力，只做“配置真相收口”。核心原则是：`PRIMARY owner 诚实失败`、`AUXILIARY provider 明示降级`、`YAML 显式化`、`默认值与绑定行为必须有测试锁定`。实现顺序固定为 `问题纠偏 -> GitHub owner 真相收口 -> 搜索 provider 就绪度显式化 -> Jina Token 配置打通 -> source family 默认值显式化 -> RSS readiness 口径收口 -> 回归验证`。

**Tech Stack:** Java 17, Spring Boot, ConfigurationProperties, ApplicationRunner, JUnit 5, AssertJ, Mockito, Maven

---

## 1. 背景与目标

这份计划只解决一个问题：当前搜索与采集链路里，已经出现多条“代码骨架存在、架构文档已经承诺、但真实配置并没有让它可用”的空架子路径。  
如果不先把这些配置真相收口，后续继续推进 `Wave 11 / Wave 12` 或下游闭环时，会持续把问题误归因到搜索策略、采集质量、extractor 或 reviewer，而不是先承认“这条能力压根没配置好”。

本计划的最终验收标准只有四条：

1. 启用的 `PRIMARY owner` 不能再依赖缺失配置偷偷运行。
2. `AUXILIARY provider` 缺配置时，系统必须能明确说明“为什么不可用”，不能静默装死。
3. `application.yml` 必须能直接体现关键能力需要哪些配置，不能把关键字段藏在 Java 默认值或未暴露字段里。
4. 关键默认值与绑定行为必须由自动化测试锁死，避免“现在看起来没问题，后续一改 YAML 就悄悄失效”。

---

## 2. 先纠偏：这次修复要基于哪些真实结论

下面这些结论，是明天执行前必须统一的事实口径。

### 2.1 已确认成立

1. `GitHub API` 的主配置段在主 `application.yml` 中不存在。
2. `GithubApiProperties` 的 `enabled` 默认值是 `false`，`apiToken` 默认值为空；当前主配置没有显式接入 `github-api.enabled`、`github-api.api-token`。
3. `GithubApiCollectionExecutor` 当前并不检查 `github-api.enabled`，只要 `primaryTool=GITHUB_API` 就会尝试执行。
4. `SerpApi`、`Qianfan`、`HTTP Search` 三个搜索 provider 的鉴权字段在主配置里默认都是空字符串，因此在未注入环境变量时 `isAvailable()` 会返回 `false`。
5. `JinaReaderProperties` 虽然有 `bearerToken` 字段，但主 `application.yml` 里没有显式暴露对应配置项。
6. `official` family 的 `directPathTemplates` 目前主要靠 `SearchSourceCatalogProperties` 的 Java 默认值提供，YAML 里没有直接展示。

### 2.2 需要修正的判断

1. “搜索补源 4 个 provider 全灭”这个说法只对 `prod` profile 更接近真实。  
   默认 profile 里 `source-discovery.search.browser-preview-enabled=true`，而 `prod` profile 明确把 `browserpreview.enabled=false` 且 `browser-preview-enabled=false`。  
   所以更准确的说法是：
   - 默认 profile：`qianfan/serpapi/http` 很可能不可用，但 `BrowserPreview` 规划期预览链路仍可能工作。
   - `prod` profile：如果没配 API key，且保持现有 prod 覆盖项，规划期 provider 栈几乎就是空的。
2. 运行期浏览器搜索 `BrowserSearchRuntimeService` 与规划期 `BrowserPreviewSearchSourceProvider` 不是同一条链。  
   即使规划期 provider 栈全空，运行期浏览器搜索仍可能工作。所以这次修复必须把“规划期 provider readiness”和“运行期 browser readiness”分开表达，不能混成一个结论。
3. `directPathTemplates` 当前是“高风险未显式化”，但还不能直接下结论说 Spring 绑定一定会把默认值抹空。  
   这个风险是真的，但目前属于“测试没锁死、配置没显式展示”，不是“已实锤线上逻辑必坏”。  
   因此处理策略应是：`先加显式 YAML + 再补绑定测试`，而不是先假设已有行为一定错误。
4. `RSS` 的核心问题不是 “rss-feed 没配所以 executor 不能用”。  
   当前 `collection.rss-feed.*` 已经存在，`RssFeedCollectionExecutor` 也能跑。真正的问题是：
   - 当前只支持“显式 feed URL -> RSS owner”
   - 不支持 feed seed 管理、订阅监控、主动发现、cursor/replay
   - 这条能力容易被误读成“news family 已有完整 RSS 体系”

---

## 3. 本次修复范围

### 3.1 必须完成

1. `GitHub API` 从“名义 owner”收口为“要么显式启用并带 token，要么诚实失败”。
2. 搜索 provider 的可用性必须能在启动期被明确说明，不能继续靠 `isAvailable=false` 静默跳过。
3. `JinaReader bearerToken` 必须可从 YAML / env 显式配置。
4. `official.directPathTemplates` 必须在 YAML 中显式展示，并由绑定测试锁死。
5. `RSS` 当前 readiness 边界必须被文档化，并在启动期输出清晰口径，避免误读成完整 feed 体系。

### 3.2 明确不做

1. 不实现 `Wave 11` 的订阅监控、cursor、去重窗口、增量回放。
2. 不新建 feed seed 仓库、RSS 订阅中心、站点级抓取调度面板。
3. 不引入新的搜索 provider，也不接入新的 GitHub discovery provider。
4. 不把这次任务扩成 `Wave 12` 下游证据闭环。
5. 不改搜索业务策略本身，只改配置真相、owner readiness 与观测能力。

---

## 4. 设计原则

### 4.1 PRIMARY owner 诚实失败

对 `github` 这种已经被 source family 声明为 `PRIMARY owner` 的能力，不能再允许“family 开着、tool 在计划里、运行时却依赖匿名公共 API 碰运气”。

推荐落地规则：

1. `search.source-catalog.families.github.enabled=true`
2. 且 family 的 `primaryTools` 包含 `GITHUB_API`
3. 则必须同时满足：
   - `github-api.enabled=true`
   - `github-api.api-token` 非空
   - `github-api.endpoint` 为 https
4. 否则启动期直接失败，或者至少在执行显式 GitHub repo 采集时 `TOOL_UNAVAILABLE_FAST_FAIL`

本计划推荐：`启动期失败 + 执行期兜底双保险`。

### 4.2 AUXILIARY provider 明示降级

`SerpApi`、`Qianfan`、`HTTP Search`、`BrowserPreview` 这些 provider 允许缺配置，但缺配置时必须：

1. 启动期输出结构化告警，明确缺的是哪个 key。
2. 区分“规划期 provider 不可用”和“运行期浏览器搜索不可用”。
3. 不能再只靠 `RoutingSearchSourceProvider` 在运行时静默 `continue`。

### 4.3 YAML 显式化优先于 Java 隐式默认

凡是会影响 source family 解释、owner 路由或外部 API readiness 的关键字段，都必须优先在 `application.yml` 中可见。  
Java 默认值只能作为兜底，不应成为主要的“配置说明文档”。

### 4.4 默认值必须有绑定测试锁定

凡是当前依赖 Java 默认值、部分 YAML 覆盖、或 profile 覆盖行为的地方，都必须补绑定测试，不允许只靠肉眼推断。

---

## 5. 文件影响图

### 5.1 Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`

### 5.2 Backend - Modify

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java`

### 5.3 Optional Doc Sync

- `docs/superpowers/specs/2026-06-17-search-and-collection-architecture-design.md`
- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`

这两个文档同步不属于明天必须完成项，但如果代码已经收口，建议在最后补一句口径修正，避免 specs 继续夸大 readiness。

---

## 6. 分任务执行计划

## Task 1: 收口 GitHub API owner 真相

**目标：** 让 `github` family 的 `GITHUB_API` owner 要么真正带 token 工作，要么诚实失败，不再走匿名公共 API 假装是正式能力。

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [ ] **Step 1: 在 `application.yml` 增加 `github-api` 主配置段**

建议新增：

```yaml
github-api:
  enabled: ${GITHUB_API_ENABLED:false}
  endpoint: ${GITHUB_API_ENDPOINT:https://api.github.com}
  api-token: ${GITHUB_API_TOKEN:}
  timeout-seconds: 15
  max-retries: 2
```

要求：

1. 主 profile 必须有这段。
2. 如有必要，`prod` profile 只覆盖 `enabled` 或 `endpoint`，不要再把整段藏起来。
3. 变量名统一使用 `GITHUB_API_TOKEN`，不要混 `KEY/TOKEN` 两种命名。

- [ ] **Step 2: 给 `GithubApiProperties` 增加“已启用/已就绪”语义方法**

建议增加两个方法：

```java
public boolean isConfigured() {
    return StringUtils.hasText(apiToken) && StringUtils.hasText(endpoint);
}

public boolean isReady() {
    return enabled && isConfigured();
}
```

要求：

1. 方法名表达“就绪度”，不是只表达“字段是否有值”。
2. 业务注释使用中文，说明 `enabled=true` 但 token 为空时不算 ready。

- [ ] **Step 3: 在 `GithubApiClient` 入口提前拒绝未就绪调用**

在真正发 HTTP 请求前加显式保护：

```java
if (!properties.isEnabled()) {
    throw new IllegalStateException("github api disabled");
}
if (!StringUtils.hasText(properties.getApiToken())) {
    throw new IllegalStateException("github api token missing");
}
```

要求：

1. 不能继续允许匿名请求作为“正式 GitHub owner”的默认路径。
2. 错误文案必须能被启动期 guard 与执行期错误复用。

- [ ] **Step 4: 修改 `GithubApiCollectionExecutor.supports()`，把 `enabled` 纳入支持判断**

把当前：

```java
return taskPackage != null && "GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
```

改成等价于：

```java
return taskPackage != null
        && githubApiProperties.isEnabled()
        && "GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
```

同时保留 `execute()` 内部的二次防守，避免通过错误构造绕过 `supports()`。

预期结果：

1. 当 `github-api.enabled=false` 时，执行器不再声称自己支持该 owner。
2. `CollectionExecutionCoordinator` 会自然落入 `TOOL_UNAVAILABLE_FAST_FAIL`。

- [ ] **Step 5: 补充测试**

新增或修改断言：

1. `GithubApiCollectionExecutorTest`
   - `enabled=true + token 有值` 时成功返回结构化结果
   - `enabled=false` 时不支持执行
   - `enabled=true + token 为空` 时抛清晰错误或返回失败结果
2. `CollectionExecutorRegistryTest`
   - 当 GitHub executor 未启用时，不应被 registry 解析成有效 owner
3. `SearchPropertiesBindingTest`
   - 断言 `github-api.enabled/api-token/endpoint/max-retries` 能正确绑定

- [ ] **Step 6: 运行定向测试**

Run:

```bash
mvn -pl backend "-Dtest=GithubApiCollectionExecutorTest,CollectionExecutorRegistryTest,SearchPropertiesBindingTest" test
```

Expected:

1. 旧的 GitHub happy path 仍通过
2. 新增 disabled/missing-token case 全部通过

- [ ] **Step 7: 本任务提交**

```bash
git add backend/src/main/resources/application.yml \
  backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiProperties.java \
  backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java \
  backend/src/main/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutor.java \
  backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java \
  backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java
git commit -m "fix: make github api owner fail honestly when unconfigured"
```

---

## Task 2: 让搜索 provider readiness 在启动期可见

**目标：** 把 `qianfan / serpapi / http / browserpreview` 的 availability 从“运行时静默跳过”升级为“启动期显式说明当前哪些 provider 可用、哪些不可用、为什么不可用”。

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [ ] **Step 1: 新建 `SearchCapabilityReadinessGuard`**

职责固定为三件事：

1. 校验 `PRIMARY owner` readiness
2. 汇总 `AUXILIARY provider` readiness
3. 输出明确区分“规划期 provider”和“运行期 browser”的就绪度日志

建议依赖：

```java
SearchProperties
SearchProviderProperties
SearchBrowserProperties
GithubApiProperties
SerpApiProperties
QianfanSearchProperties
```

- [ ] **Step 2: 在 guard 中实现 GitHub primary owner 启动期 hard fail**

推荐逻辑：

```java
if (githubFamilyEnabled && githubPrimaryToolEnabled) {
    if (!githubApiProperties.isEnabled()) {
        throw new IllegalStateException("github family enabled but github-api.enabled=false");
    }
    if (!StringUtils.hasText(githubApiProperties.getApiToken())) {
        throw new IllegalStateException("github family enabled but github api token missing");
    }
}
```

说明：

1. 这是这次计划里唯一建议 `hard fail` 的 owner。
2. 因为 `github` 已经不是“可选草稿能力”，而是 source family 主链路 owner。

- [ ] **Step 3: 在 guard 中实现 provider readiness 摘要**

至少输出以下内容：

1. `qianfan` 是否 route enabled
2. `qianfan` 是否 available
3. 不可用原因是 `apiKey missing` / `endpoint invalid`
4. `serpapi` 同上
5. `http` 同上
6. `browserpreview` 是否 route enabled
7. `browser-preview-enabled` 是否 true
8. `search.browser.enabled` 是否 true

推荐日志语义：

1. `INFO`：输出完整 summary
2. `WARN`：route enabled 但 unavailable
3. `ERROR`：primary owner 硬失败

- [ ] **Step 4: 明确区分“规划期 provider 不可用”和“运行期 browser 仍可用”**

必须在日志中直接写明：

1. `RoutingSearchSourceProvider` 的 planning provider 栈是否为空
2. 即使 planning provider 栈为空，`BrowserSearchRuntimeService` 是否仍可用于运行期补源

建议文案：

```text
planning search providers unavailable, but runtime browser search remains enabled
```

或者

```text
planning search providers unavailable and browser preview disabled; only runtime browser search may still rescue execution
```

- [ ] **Step 5: 给 `prod` profile 加注释，不再隐藏关键行为差异**

`application.yml` 中 `prod` profile 现有：

```yaml
source-discovery:
  search:
    providers:
      browserpreview:
        enabled: false
    browser-preview-enabled: false
```

这里必须增加中文注释，明确这是“规划期浏览器预览关闭”，不是“运行期浏览器搜索关闭”。

- [ ] **Step 6: 补充测试**

新增 `SearchCapabilityReadinessGuardTest` 覆盖：

1. `github family enabled + github-api.enabled=false` -> 启动失败
2. `github-api.enabled=true + token missing` -> 启动失败
3. `serpapi/qianfan/http` route enabled 但无 key -> 启动不失败，但有可断言的日志/状态
4. `prod` 风格配置下 browser preview disabled，但 `search.browser.enabled=true` -> 启动摘要必须区分 planning/runtime

如果不方便断日志，可把 guard 内部拆出一个 package-private summary 方法，测试该 summary 对象。

- [ ] **Step 7: 运行定向测试**

Run:

```bash
mvn -pl backend "-Dtest=SearchCapabilityReadinessGuardTest,SearchPropertiesBindingTest" test
```

Expected:

1. GitHub hard fail case 正常拦住
2. provider summary case 通过

- [ ] **Step 8: 本任务提交**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java \
  backend/src/main/resources/application.yml \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java
git commit -m "fix: surface search provider readiness at startup"
```

---

## Task 3: 打通 Jina Reader bearer token 配置

**目标：** 保留 Jina 免费端点可用的现状，但让 premium token 能被正式配置，而不是只能靠代码字段暗藏能力。

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`

- [ ] **Step 1: 在 `application.yml` 暴露 `bearer-token`**

建议新增：

```yaml
collection:
  jina-reader:
    enabled: true
    endpoint: https://r.jina.ai/http://
    bearer-token: ${JINA_READER_BEARER_TOKEN:}
    timeout-seconds: 20
    max-retries: 2
    minimum-content-length: 160
```

说明：

1. 环境变量命名推荐与字段名一致：`JINA_READER_BEARER_TOKEN`
2. 如果团队更倾向 `JINA_API_KEY`，只能二选一，不要双命名并存

- [ ] **Step 2: 给 `JinaReaderProperties` 补中文注释**

明确说明：

1. `bearerToken` 为空时允许走免费端点
2. 不为空时才发送 `Authorization` header
3. 该字段只影响速率与权限，不改变网页采集主逻辑

- [ ] **Step 3: 补绑定测试**

在 `SearchPropertiesBindingTest` 或单独的 `JinaReaderPropertiesBindingTest` 中断言：

1. `collection.jina-reader.bearer-token` 能正确绑定
2. endpoint、timeout、minimum-content-length 未回归

- [ ] **Step 4: 补客户端测试**

在 `JinaReaderClientTest` 增加 case：

1. token 为空时，不发送 `Authorization`
2. token 有值时，request header 带 `Bearer xxx`

如果当前测试替身不方便直接断 header，至少拆一个构建 request 的内部方法供测试。

- [ ] **Step 5: 运行定向测试**

Run:

```bash
mvn -pl backend "-Dtest=JinaReaderClientTest,SearchPropertiesBindingTest" test
```

Expected:

1. Jina 原有主路径行为不变
2. token 绑定与 header 行为通过

- [ ] **Step 6: 本任务提交**

```bash
git add backend/src/main/resources/application.yml \
  backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java \
  backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java
git commit -m "fix: expose jina reader bearer token configuration"
```

---

## Task 4: 把 official directPathTemplates 从隐式默认收口为显式契约

**目标：** 让 `official` family 的 direct path 模板不再只是写在 Java 工厂默认值里，而是 YAML 可见、绑定有测试、后续可定制。

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java`

- [ ] **Step 1: 在 `application.yml` 显式写出 `direct-path-templates`**

建议新增：

```yaml
search:
  source-catalog:
    families:
      official:
        direct-path-templates:
          - /
          - /pricing
          - /docs
          - /documentation
          - /help
```

要求：

1. 不要继续依赖 Java 默认值作为唯一可见配置来源
2. 如果未来要扩 `/blog`、`/about`，直接在 YAML 改

- [ ] **Step 2: 补绑定测试，锁死“显式配置时按配置值生效”**

在 `SearchPropertiesBindingTest` 增加断言：

```java
assertThat(searchProperties.getSourceCatalog()
        .getFamilies().get("official").getDirectPathTemplates())
        .containsExactly("/", "/pricing", "/docs", "/documentation", "/help");
```

- [ ] **Step 3: 补默认值测试，锁死“无 YAML 覆盖时仍保留 Java 默认值”**

在 `SearchSourceCatalogPropertiesTest` 增加断言：

1. `new SearchSourceCatalogProperties().getFamilies().get("official").getDirectPathTemplates()`
2. 默认包含 5 个路径模板

这样可以把风险从“猜 binder 会不会抹掉”变成“无论谁改默认值，测试都会响”。

- [ ] **Step 4: 平台契约测试补一条“YAML 与默认值的业务语义一致”**

在 `SearchSourceCatalogPlatformContractTest` 增加断言：

1. `official` family 默认必须存在 direct path templates
2. `github` family 默认 directPathTemplates 为空是有意设计，不是漏配

- [ ] **Step 5: 运行定向测试**

Run:

```bash
mvn -pl backend "-Dtest=SearchPropertiesBindingTest,SearchSourceCatalogPropertiesTest,SearchSourceCatalogPlatformContractTest" test
```

Expected:

1. direct path 模板显式配置通过
2. Java 默认值契约通过

- [ ] **Step 6: 本任务提交**

```bash
git add backend/src/main/resources/application.yml \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java \
  backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java
git commit -m "fix: make official direct path templates explicit in yaml"
```

---

## Task 5: 收口 RSS 的 readiness 口径，避免继续被误判成完整体系

**目标：** 承认当前 RSS owner 只覆盖“显式 feed URL -> RSS executor”，不是完整 news feed 体系；把这个边界写进启动期 readiness 输出与文档说明。

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- Optional Modify: `docs/superpowers/specs/2026-06-17-search-and-collection-architecture-design.md`
- Optional Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`

- [ ] **Step 1: 在 `application.yml` 的 `news` / `rss-feed` 附近补中文注释**

必须明确写出：

1. 当前 `RSS` 只处理显式 feed URL
2. 普通 news article URL 继续走网页采集主链路
3. feed seed / subscription / cursor / replay 不在当前 scope，归 `Wave 11`

- [ ] **Step 2: 在 readiness guard 输出 `RSS owner boundary`**

建议在启动期输出一条 `INFO`：

```text
rss owner ready for explicit feed urls only; feed subscription monitoring is out of current scope
```

目的：

1. 避免后续排障人员误以为“news family 已经具备完整 RSS 体系”
2. 让 `Wave 10` 与 `Wave 11` 的边界在运行时也能被看见

- [ ] **Step 3: 如果时间允许，同步 specs 口径**

只改一句话，不展开大修：

1. `Wave 10` 的 RSS owner readiness 只覆盖 explicit feed URL
2. 订阅/主动发现仍未完成

- [ ] **Step 4: 手工验收**

检查三件事：

1. YAML 注释是否一眼能看懂当前 RSS readiness 边界
2. 启动日志是否能直观看到 RSS owner scope
3. 文档口径是否不再把当前状态误写成完整 RSS 体系

- [ ] **Step 5: 本任务提交**

```bash
git add backend/src/main/resources/application.yml \
  backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java
git commit -m "docs: clarify rss owner readiness boundary"
```

---

## Task 6: 联合回归与最终验收

**目标：** 证明这次修复没有把搜索与采集主链路打坏，同时把“配置空架子”从静默状态变成显式状态。

**Files:**
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`

- [ ] **Step 1: 跑配置/owner 相关定向回归**

Run:

```bash
mvn -pl backend "-Dtest=GithubApiCollectionExecutorTest,CollectionExecutorRegistryTest,SearchPropertiesBindingTest,SearchCapabilityReadinessGuardTest,SearchSourceCatalogPropertiesTest,SearchSourceCatalogPlatformContractTest,JinaReaderClientTest,RoutingSearchSourceProviderTest" test
```

Expected:

1. 新增配置真相测试全部通过
2. 搜索 provider 路由测试未回归

- [ ] **Step 2: 跑搜索与采集聚合回归**

Run:

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,HeuristicSourceDiscoveryServiceTest,SearchAndCollectionGoldenMasterTest,CollectorAgentTest" test
```

Expected:

1. 搜索与采集主链路不回归
2. family discovery convergence 相关既有行为不被破坏

- [ ] **Step 3: 跑全量 backend 测试**

Run:

```bash
mvn -pl backend test
```

Expected:

1. 全量通过
2. 若失败，优先排查是否有旧测试把“匿名 GitHub API 请求也算正式能力”写死了

- [ ] **Step 4: 做一次无配置启动验收**

目标环境：

1. 不设置 `GITHUB_API_TOKEN`
2. 不设置 `SERPAPI_API_KEY`
3. 不设置 `QIANFAN_API_KEY`
4. 不设置 `SEARCH_API_KEY`

期望行为：

1. 若 `github` family 仍启用，则启动应明确失败，错误直指 `github api token missing`
2. provider readiness 输出明确显示哪些 provider route enabled 但 unavailable
3. 不会再出现“应用启动成功，但 GitHub owner 实际不可用”的伪成功

- [ ] **Step 5: 做一次最小可用配置启动验收**

目标环境：

1. 设置 `GITHUB_API_ENABLED=true`
2. 设置 `GITHUB_API_TOKEN=...`
3. 可选设置一个搜索 provider key

期望行为：

1. 启动通过
2. readiness 摘要正确反映 GitHub owner ready
3. 显式 GitHub repo 走正式 owner，不再匿名碰运气

- [ ] **Step 6: 最终提交**

```bash
git add .
git commit -m "fix: make search and collection configuration readiness explicit"
```

---

## 7. 明天执行时的推荐顺序

如果明天只做一天，建议严格按下面顺序推进，不要并行乱改：

1. 先做 `Task 1`
2. 再做 `Task 2`
3. 再做 `Task 3`
4. 再做 `Task 4`
5. `Task 5` 作为口径补强收尾
6. 最后统一跑 `Task 6`

原因：

1. `GitHub API` 是唯一确定的 `P0` 空架子
2. provider readiness 是全链路观测底座
3. `Jina` 与 `directPathTemplates` 都属于“显式化补齐”，风险更小
4. `RSS` 主要是边界纠偏，不是核心 blocker

---

## 8. 风险提示

1. 一旦落实 `GitHub primary owner hard fail`，当前未配置 token 的环境会启动失败。  
   这是预期行为，不是副作用。它的本质是在消灭“假可用”。
2. 如果团队短期内不想因为 GitHub token 缺失阻断启动，那就必须同步把 `search.source-catalog.families.github.enabled=false`。  
   不能继续保持 `family enabled=true` 却允许 owner 不可用。
3. `provider readiness` 只能解决“为什么不可用”的可观测性，不能凭空补出第三方搜索 API 能力。  
   这次任务不是去接新 provider，而是先让系统停止撒谎。
4. `RSS` 的后续专题必须单列，不要把 feed seed / subscription / cursor 回塞进这次修复。

---

## 9. 完成定义

当且仅当以下条件同时满足，这份计划才算真正完成：

1. 主 `application.yml` 中存在 `github-api` 配置段与 `JinaReader bearer-token` 配置项。
2. `github` family 启用但 owner 未就绪时，系统启动期会明确失败。
3. 搜索 provider 的 unavailable 原因能在启动期看到，不再只是路由器里静默跳过。
4. `official.directPathTemplates` 在 YAML 中可见，且默认值/绑定值有测试锁死。
5. `RSS` 只支持 explicit feed URL 的边界在配置与启动日志中都可见。
6. 相关定向测试与 `mvn -pl backend test` 全部通过。

---

## 10. 执行备注

1. 所有新增或修改的业务注释必须使用中文。
2. 先写失败测试，再改实现，不要直接上手改生产代码。
3. 不要把这次修复扩展成 `Wave 11` 或 `Wave 12` 的实现。
4. 如果执行过程中发现 `SearchCapabilityReadinessGuard` 变得过大，可以拆出 package-private helper，但不要提前做无关重构。

