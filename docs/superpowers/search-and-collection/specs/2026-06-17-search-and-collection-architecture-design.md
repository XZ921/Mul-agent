# 搜索与采集架构 1 设计（家族驱动的分层采集架构）

## 文档目的

本文档冻结 `搜索与采集` 的最终架构 1，解决一个核心问题：`URL 发现` 不能继续被通用搜索引擎单独垄断，`正文提取` 也不能反过来承担发现职责。

这份设计把当前系统的边界收口为一条可解释、可追溯、可恢复的链路：

1. `source family` 是业务语义根，不是工具名。
2. `PUBLIC_SEARCH` 只负责辅助发现与长尾兜底，不是业务来源本身。
3. `JinaReader` 是网页正文提取主路，不是发现工具。
4. `Playwright` 只做重渲染兜底，不承担默认发现职责。
5. `RSS`、`GitHub API` 等结构化源必须作为正式 owner 存在，不能先绕通用搜索再去抽取。
6. 全链路必须保留 `sourceUrls`，否则发现、采集、审计、回放都不可追溯。

---

## 设计结论

本架构采用 `家族驱动的分层采集架构`：

`Source Family Catalog -> Discovery Router -> Candidate Verification -> Target Selection -> Collection Executors -> Audit / Replay / Recovery`

这一定义与当前代码骨架是对齐的，不是另起炉灶：

1. `SearchSourceCatalogProperties` 已在表达家族目录与工具语义。
2. `SearchPolicyResolver` 已在负责家族解释与 render hint / provider role 翻译。
3. `SearchExecutionCoordinator` 已在负责验证、补源与最终选源。
4. `WebPageCollectionExecutor` 已在负责 `JinaReader -> Playwright` 的网页采集主备路径。

这意味着：

1. 先按业务家族决定“要采什么”。
2. 再决定“用什么工具发现候选 URL”。
3. 再决定“哪些候选值得进入正式采集”。
4. 最后才决定“用哪个执行器采集内容”。

这个顺序不能反过来。尤其不能把 `Qianfan / SerpApi` 的搜索结果当成“精确 URL 真相”，再让 `JinaReader` 去兜底修正发现错误。

---

## 架构分层

### 1. 数据源家族层

`Source Family Catalog` 是全系统唯一的业务语义入口，表达的是“采什么”，不是“怎么采”。

当前至少应稳定支持以下家族：

| 家族 | 业务内容 | 正式 collection owner | 发现辅助工具 | 角色 |
| --- | --- | --- | --- | --- |
| `official` | 产品页、定价页、文档页、关于页 | `WEB_PAGE`（`JinaReader` 主路，`Playwright` 兜底） | `PUBLIC_SEARCH` | `PRIMARY_VERTICAL` |
| `news` | 发布、融资、合作、更新公告 | `RSS`（显式 feed）/ `WEB_PAGE`（普通文章） | `PUBLIC_SEARCH` | `PRIMARY_VERTICAL` |
| `github` | 仓库、Release、Star 趋势、开源动向 | `GITHUB_API` | `PUBLIC_SEARCH` | `PRIMARY_VERTICAL` |
| `long_tail` | 其他难以结构化归类的公开网页 | `WEB_PAGE` | `PUBLIC_SEARCH` | `AUXILIARY_PUBLIC` |

其中 `official / news / github` 是正式业务家族，`long_tail` 只是兜底区，不应反客为主。

### 2. 发现层

发现层负责找到候选 URL，但不负责证明它们就是最终答案。

这一层的正式原则是：

1. `PUBLIC_SEARCH` 只能作为发现工具之一，不能成为业务语义本身。
2. 家族可以有自己的发现路径，例如官方站可直接用路径模板，GitHub 可直接使用 repo locator，新闻可直接使用 feed。
3. 公网搜索只负责覆盖长尾、补漏和权重排序，不负责定义业务 owner。
4. 发现层的输出必须携带来源线索和家族语义，便于后续验证与回放。

### 3. 候选验证与选源层

发现到的 URL 不能直接进入采集，必须先经过验证和选择。

这一层由 `SearchExecutionCoordinator`、`CandidateVerifier`、`CollectionTargetSelector`、`CanonicalUrlResolver` 协同完成：

1. 先验证高优先级候选，避免营销页和首页凭 SEO 权重直接胜出。
2. 候选不足时再补源，但补源结果必须回到同一套家族语义里收口。
3. 最终选源必须做 URL 归一化，避免同一页面因参数不同被当成多个来源。
4. 选源阶段必须保留 `attemptedTargets`、`discardedCandidates`、`selectedTargets` 和 `sourceUrls`，方便审计与恢复。

### 4. 采集执行层

采集执行层按资源类型分工，不再用单一执行器吞掉全部世界。

#### 网页资源

`WebPageCollectionExecutor` 继续承接网页类资源，但它内部的顺序固定为：

1. 轻量页面优先走 `JinaReader`。
2. 如果正文太短、不可用或运行异常，再升级到 `Playwright`。
3. `Playwright` 只负责重渲染和兜底，不应成为默认发现工具。

#### 结构化资源

1. `RSS` 类资源应由 RSS 采集执行器直接承接。
2. `GitHub` 类资源应由 API 采集执行器直接承接。
3. 结构化 owner 可以使用公网搜索做发现补充，但不能依赖通用搜索后再回到网页抽取作为主路径。

### 5. 审计、回放与恢复层

所有关键对象都必须保留可回指来源，尤其是：

1. `sourceUrls`
2. `qualitySignals`
3. `failureKind`
4. `collectedAt`
5. `durationMillis`
6. `replayTimeline`

这层的目标不是“多存几个字段”，而是确保同一次采集可以被解释、复盘、恢复和对账。

---

## 核心数据流

```text
输入任务配置
  -> 解析 sourceType 对应的 source family
  -> 按 family 生成发现策略 / 查询模板 / 工具顺序
  -> 运行发现器得到候选 URL
  -> 验证、去重、归一化
  -> 选择正式采集目标
  -> 进入对应采集执行器
  -> 写入审计、回放、恢复上下文
```

更具体地说：

1. `CollectorNodeConfig` 提供任务上下文。
2. `SearchPolicyResolver` 把 `sourceType` 解析为家族语义。
3. `PromptTemplateService` 和 `search-queries.yml` 负责 query 模板，不负责业务边界。
4. `RoutingSearchSourceProvider` 汇聚 `Qianfan / SerpApi / Browser / HTTP` 等发现工具，但它们只在 family 允许时介入 discovery。
5. `SearchExecutionCoordinator` 完成验证、补源和最终选源。
6. `CollectionTaskPackageBuilder` 根据家族决定 `renderHint / expectedBlockTypes / primaryTool`。
7. `WebPageCollectionExecutor`、`RssFeedCollectionExecutor`、`GithubApiCollectionExecutor` 各自承接不同资源类型。
8. `collectionAudit` / replay / checkpoint 统一保存同一份事实源。

---

## 约束与错误处理

### 1. 外部调用必须可恢复

所有外部大模型 API、搜索引擎、网页抓取工具、RSS 源、GitHub API 调用都必须满足：

1. `try-catch` 必须存在。
2. 必须有有限重试，不能无界重试。
3. 必须记录明确的失败语义，而不是直接吞错。
4. 必须区分“可降级”与“不可继续”两类故障。

### 2. 失败只允许在同一语义层内降级

1. `JinaReader` 失败，可以升级到 `Playwright`。
2. 结构化 owner 失败，可以回退到该家族允许的辅助发现路径。
3. 不能把 `GitHub API` 失败直接偷换成“随便找个 GitHub 页面抓正文”。
4. 不能把 `RSS` 失败直接偷换成“用通用搜索搜一篇新闻文章碰碰运气”。

### 3. 公开搜索只允许做辅助

公网搜索在本架构里有两个合法用途：

1. 长尾发现。
2. 结构化 owner 的补漏。

它不允许承担以下职责：

1. 直接定义业务 source family。
2. 直接替代结构化 owner。
3. 直接成为网页正文提取的主路。

---

## 当前代码映射

这份架构不是空想，当前代码已经有对应骨架：

| 代码对象 | 当前职责 |
| --- | --- |
| `SearchSourceCatalogProperties` | 定义 `official / news / github` 家族目录与工具语义 |
| `SearchPolicyResolver` | 把家族配置翻译成 render hint、expected blocks、provider keys、fallback 语义 |
| `RoutingSearchSourceProvider` | 聚合多个搜索发现工具并做路由 |
| `SearchExecutionCoordinator` | 验证候选、补源、最终选源 |
| `CollectionTargetSelector` | URL 归一化、去重、选源结果回填 |
| `WebPageCollectionExecutor` | 网页类资源的 `JinaReader -> Playwright` 双路径采集 |

这说明架构 1 不是推翻重做，而是把现有分层的真实边界正式化。

执行入口仍以 `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md` 为准，但该计划文档必须服从这份冻结稿的边界，不得再把 `PUBLIC_SEARCH`、`JinaReader`、`Playwright` 误写成业务来源层。

---

## 非目标

这版设计明确不做以下事情：

1. 不把通用搜索引擎当成业务来源本身。
2. 不把 `JinaReader` 当成 URL discovery 工具。
3. 不把 `Playwright` 变成默认发现路径。
4. 不让每个 provider 各自定义一套业务语义。
5. 不取消 preview / runtime 分离。
6. 不在本架构里重做 `DagExecutor`、恢复引擎或任务底座。

---

## 验收口径

以下 6 条定义的是 `架构 1` 的最终验收目标，不等于“截至当前代码状态已经全部满足的事实”。

按 2026-06-17 当前代码骨架与已落地实现，应这样理解：

| 条目 | 当前状态 | 说明 |
| --- | --- | --- |
| `official` 家族优先围绕官网 / 定价 / 文档发现 | `🟡 部分成立` | 已有 `source family` 语义、official query template、provided URL/root/path heuristic，但默认 discovery 主链路仍以公网搜索 / 浏览器搜索为主，尚不能宣称已经完全不被搜索引擎排名牵着走。 |
| `news` feed / article 分流 | `✅ 已成立` | 显式 feed URL 已进入 `RSS` owner；普通新闻文章 URL 继续走网页采集主链路。 |
| `github` API owner，公网搜索仅补漏 | `🟡 部分成立` | collection owner 已由 `GITHUB_API` 正式承接；当 `github` family 启用且 `GITHUB_API` 仍是 primary owner 时，启动期已强制要求 `github-api.enabled=true` 且 token / HTTPS endpoint 就绪，不再把匿名公共 API 误算为 ready；discovery 层当前仍更多依赖公网搜索、显式 URL 或稳定 locator。 |
| `JinaReader` 主路，`Playwright` 兜底 | `✅ 已成立` | 网页采集已切到 `JinaReader -> Playwright` 双路径。 |
| 搜索 provider readiness 启动期可观测 | `✅ 已成立` | 启动期已显式输出 `qianfan / serpapi / http / browserpreview` 的 readiness 摘要，并区分规划期 provider readiness 与运行期 `search.browser.enabled`。 |
| 关键配置真相在 YAML 中显式可见 | `✅ 已成立` | `github-api`、`collection.jina-reader.bearer-token`、`official.direct-path-templates` 已显式写入 `application.yml`；RSS scope 也已明确收口为“仅显式 feed URL 进入 RSS owner”。 |
| 失败结果可回指 `sourceUrls` + 原因 | `✅ 已成立` | 采集结果、审计与回放已保留来源回指和失败语义。 |
| 发现 / 采集 / 审计 / 回放共享同一套家族语义 | `✅ 已成立` | `sourceFamilyKey / sourceFamilyRole / providerRole` 已进入预览、运行、采集与回放链路。 |

因此，如果架构 1 最终正确收口，系统应满足以下验收条件：

1. 对 `official` 家族，系统最终应能优先围绕官网、定价、文档等页面发现候选，而不是被搜索引擎排名牵着走。
2. 对 `news` 家族，显式 feed 走 RSS 主路，普通新闻文章 URL 仍走网页采集，不混成一条假统一路径。
3. 对 `github` 家族，系统最终应由结构化 API 担任正式 owner，公网搜索只承担补漏和发现。
4. 对网页资源，`JinaReader` 是主路，`Playwright` 是兜底，不再互换职责。
5. 任一失败结果都能回指到具体 `sourceUrls` 和失败原因。
6. 发现、采集、审计、回放看到的是同一套家族语义，而不是四套互相打架的解释。

---

## 后续衔接

这份设计冻结后，下一步实施计划应围绕“把家族语义真正接入执行链路”展开，而不是回头争论 `Qianfan / SerpApi` 的 query 字符串怎么写得更像精确 URL。

对应的实现工作应继续以 `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md` 为执行入口，但执行内容必须服从这份架构冻结稿。
