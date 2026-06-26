# Tavily Fast Lane 融合设计与验收方案

> 版本日期：2026-06-26  
> 范围：本文只落实 Tavily Search API 融合设计与验收口径，不修改后端代码。  
> 命名说明：目录沿用现有 `Travily` 命名，正文统一使用 API 官方名称 `Tavily`。

## 1. 背景判断

`docs/Travily` 下的测试已经证明，Tavily Search API 在 `search_depth=advanced` 且 `include_raw_content=true` 时，可以同时完成两件事：

1. 发现公开网络资料 URL。
2. 返回接近清洗后正文的 `raw_content`。

这和当前系统的搜索与采集架构形成互补：

- 当前系统强在 `Source Family Catalog`、候选验证、三路径网页采集、审计回放与恢复。
- Tavily 强在搜索阶段直接返回正文，能够让大量常规页面绕过 `DirectHtml -> Jina -> Playwright` 的重采集成本。
- Tavily 不是事实审计器，仍必须经过去重、页面类型识别、来源分级、质量门禁和交叉验证。

因此 Tavily 的定位不是替代千帆、SerpApi、Jina 或 Playwright，而是在现有链路中新增一条快速通道：

```text
Tavily Search API
  -> SourceCandidate + prefetched content metadata
  -> Prefetched Content Gate
  -> TavilyPrefetchedExecutor
  -> EvidenceSource / RAG / 下游提取
```

原链路继续作为补充与兜底：

```text
Qianfan / SerpApi / BrowserPreview / HTTP
  -> CandidateVerifier
  -> DirectHtml -> Jina -> Playwright
  -> EvidenceSource / RAG / 下游提取
```

## 2. 设计原则

### 2.1 融合，不替代

Tavily 作为主力快速通道，千帆和 SerpApi 继续承担补充发现能力：

- Tavily：常规公开网页、官方文档、博客、媒体、研报、部分视频页的搜索与预采集。
- 千帆：中文长尾、百度生态和 Tavily 覆盖不足的中文信源补充。
- SerpApi：全球搜索、英文资料和 Google 生态补充。
- Playwright：强动态、强反爬、登录态边缘页面的终极兜底。

### 2.2 一个 Family，一种策略

Tavily 不能用同一个搜索策略覆盖所有来源。`Source Family Catalog` 应决定 Tavily 的 query 模式和是否使用 `include_domains`。

| Family | 需求 | Query 模式 | include_domains | 结果定位 |
| --- | --- | --- | --- | --- |
| `OFFICIAL` | 精准命中官方来源 | `OFFICIAL_DOCS` | 使用可信官方域名 | 官网、协议、规则、官方说明 |
| `DOCS` | 精准命中文档/API | `OFFICIAL_DOCS` | 使用可信官方域名 | 开放平台、API 文档、帮助中心 |
| `PRICING` | 官方定价与商业化入口 | `OFFICIAL_DOCS` 优先，必要时放宽 | 优先使用可信官方域名 | 定价页、套餐、商业化说明 |
| `NEWS` | 全网发散 | `OPEN_WEB` | 不加 | 新闻、公告转载、行业报道 |
| `REVIEW` | 第三方视角 | `OPEN_WEB` | 不加 | 博客、产品经理文章、社区评价 |
| `RESEARCH` | 研报和深度资料 | `OPEN_WEB` 或权威源 profile | 默认不加，可配置研报域名 | 财报、ESG、券商研报、深度分析 |

`VIDEO_PAGE` 暂不建议作为默认 source family。更稳的方式是把它作为 `pageType`，服务于 `REVIEW / NEWS / OFFICIAL` 等 family，并默认降权。

### 2.3 `include_domains` 是策略旋钮

`include_domains` 不是全局开关，而是 family-aware 的搜索参数：

- 官方和文档类 family 使用 `include_domains` 提升命中精度。
- 新闻、评论、研究类 family 不使用 `include_domains`，保留发散性。
- 证据缺口补采时，由 Orchestrator 根据缺口类型决定是否收紧域名。

### 2.4 快速通道不是免检通道

Tavily 返回 `raw_content` 不代表可以直接进入报告。所有 Tavily 结果必须经过 `Prefetched Content Gate`：

- 搜索页、tag 页、视频列表页、纯导航页必须丢弃或降权。
- 视频页可以作为辅助素材，但不能作为官方算法权重或严肃结论的唯一证据。
- PDF 和长文可以进入快速通道，但需要标记 `contentCompleteness`。
- 所有结果必须保留 `sourceUrls`、query、requestId 和质量信号。

## 3. 目标架构

### 3.1 更新后的分层图

```text
L1 Source Family Catalog
  official / docs / pricing / news / review / research
  -> TavilySearchProfileResolver

L2 Discovery Router
  TavilyFastLaneProvider
  QianfanSearchSourceProvider
  SerpApiSearchSourceProvider
  BrowserPreviewSearchSourceProvider
  HttpSearchSourceProvider
  SourceFamilyDirectDiscoveryPlanner
  SitemapDiscoveryService
  CompetitorDomainDiscoveryService

L2.5 Prefetched Content Gate
  URL 去重
  pageType 分类
  raw_content 质量分级
  schemaCompleteness 初步判断
  fastLaneUsable 判定
  reject / fallback 原因归档

L3 Candidate Verification & Target Selection
  strong fast lane candidate -> 跳过网络重新验证
  weak / reject / unknown -> 原验证链路或丢弃

L4 Collection Executors
  TavilyPrefetchedExecutor
  WebPageCollectionExecutor: DirectHtml -> Jina -> Playwright
  RssFeedCollectionExecutor
  GithubApiCollectionExecutor
  ApiDataCollectionExecutor

L5 Audit / Replay / Recovery
  SearchAuditSnapshot
  CollectionAuditSnapshot
  TavilyFastLaneAudit
  replayTimeline
  checkpoint
```

### 3.2 DomainHintSet

TavilyFastLaneProvider 不应自己负责发现官方域名。更好的边界是：

```text
CompetitorDomainDiscoveryService / SearchPolicyResolver
  -> DomainHintSet
  -> TavilySearchRequest
  -> TavilyFastLaneProvider
```

`DomainHintSet` 示例：

```json
{
  "competitorName": "哔哩哔哩",
  "domains": [
    {
      "domain": "bilibili.com",
      "sourceFamily": "official",
      "confidence": 0.92,
      "reason": "官网命中且页面存在品牌归属信号",
      "sourceUrls": ["https://www.bilibili.com"]
    },
    {
      "domain": "openhome.bilibili.com",
      "sourceFamily": "docs",
      "confidence": 0.88,
      "reason": "命中开放平台文档入口",
      "sourceUrls": ["https://openhome.bilibili.com/doc"]
    }
  ]
}
```

域名来源包括：

- 用户显式 URL seed。
- 宽搜索得到的官网、开放平台、帮助中心。
- 官网页内链接。
- sitemap / robots。
- `docs.{domain}`、`developer.{domain}`、`open.{domain}`、`help.{domain}` 等 deterministic supplements。
- 历史任务中已验证的官方域名缓存。

冷启动时不能只依赖 LLM 对域名的零样本猜测。对于用户只输入竞品名称、没有 URL seed、也没有历史 `DomainHintSet` 的任务，应采用 bootstrap 流：

```text
新竞品，无 DomainHintSet
  -> 第一轮 Tavily OPEN_WEB 搜索，不加 include_domains
  -> 从返回结果 domain 中提取高频官方候选
  -> 构建 DomainHintSet，标记为 INFERRED，confidence 较低
  -> 第二轮 Tavily OFFICIAL_DOCS 搜索，加 include_domains
  -> 命中真实官方文档后，升级对应 domain confidence
```

这样 `DomainHintSet` 的 initial population 来自 LLM 推理、开放网络召回、URL 频次、页面品牌信号和后续官方文档命中，而不是单点依赖 LLM 猜域名。

### 3.3 TavilySearchProfile

Tavily 请求应由 profile 生成，而不是在 provider 中写死 query。

```json
{
  "family": "DOCS",
  "queryMode": "OFFICIAL_DOCS",
  "query": "抖音 开放平台 API 官方文档 数据接口 内容管理",
  "includeDomains": [
    "open.douyin.com",
    "docs.open-douyin.com",
    "creator.douyin.com"
  ],
  "searchDepth": "advanced",
  "includeRawContent": true,
  "maxResults": 5
}
```

核心 query mode：

| Query Mode | 用途 | include_domains |
| --- | --- | --- |
| `OPEN_WEB` | 第三方文章、媒体、研报、社区、博客 | 不加 |
| `OFFICIAL_DOCS` | 官网、文档、规则、协议、帮助中心 | 加可信官方域名 |
| `EVIDENCE_REPAIR` | 按质量缺口定向补证据 | 由 Orchestrator 决定 |

`EVIDENCE_REPAIR` 不应复用静态 query 模板。它的 query 来源应是 `AgentSuggestion.suggestedQueries`、claim 文本或 Reviewer/Citation 产出的缺口描述；`include_domains` 也可以比 `OFFICIAL_DOCS` 更窄，只限定缺口涉及的具体官方域名、研报域名或已验证信源。

## 4. Prefetched Content Gate

这是 Tavily 快速通道的核心组件。它负责决定一条 Tavily 结果能否短路 L4。

### 4.1 pageType 分类

| pageType | 判断示例 | 默认处理 |
| --- | --- | --- |
| `SEARCH_PAGE` | URL 包含 `/search`、`?keyword=`、`?q=` | 丢弃 |
| `VIDEO_LIST` | 视频站搜索列表、合集页、推荐列表 | 丢弃或低权重保留 |
| `VIDEO_PAGE` | `bilibili.com/video/BV...`、`douyin.com/shipin/...` | 辅助素材，降权 |
| `FORUM_THREAD` | reddit、即刻、V2EX 等讨论帖 | 中低可信，需交叉验证 |
| `ARTICLE` | 正文型文章页面 | 可进入快速通道 |
| `OFFICIAL_DOC` | 官方域名 + `/docs`、`/guide`、`/api`、`/agreement` 等路径 | 高优先级 |
| `PDF` | URL 以 `.pdf` 结尾或 title 标记 PDF | 可进入快速通道，标记完整度 |
| `GENERIC_PAGE` | 无法分类 | 走原验证链路 |

分类优先级需要先处理强噪声和强语义域名，再判断正文形态。尤其是 `FORUM_THREAD` 与 `ARTICLE` 容易混淆：应先看 domain 是否属于论坛/社区型清单，例如 `reddit.com`、`okjike.com`、`v2ex.com`，命中后直接归为 `FORUM_THREAD`；未命中论坛域名时，再按正文结构、标题、发布时间、作者、段落密度判断是否为 `ARTICLE`。

### 4.2 qualityTier 分级

`qualityTier` 必须按 family 分规则，不能用一套规则判断所有结果。

官方和文档类：

```text
STRONG:
  officialDomainMatched
  && pageType in [OFFICIAL_DOC, PDF]
  && rawContentLength >= 500
  && 命中文档/API/规则/协议主题

MEDIUM:
  officialDomainMatched
  && rawContentLength >= 500
  && 页面有明确品牌归属

WEAK:
  rawContentLength < 500
  或只有导航、目录、摘要

REJECT:
  rawContentLength == 0
  或 pageType in [SEARCH_PAGE, VIDEO_LIST]
```

公开资料类：

```text
STRONG:
  rawContentLength >= 2000
  && 命中分析主题
  && 覆盖目标竞品或竞品对

MEDIUM:
  rawContentLength >= 1500
  && 覆盖至少一个目标竞品

WEAK:
  rawContentLength < 1500
  或内容稀疏、课程大纲、视频推荐列表

REJECT:
  rawContentLength == 0
  或搜索页、tag 页、纯导航页
```

PDF 需要单独增加可读性检查，因为 PDF 的 `raw_content` 可能很长但包含大量格式噪声：

```text
PDF_USABLE:
  rawContentLength >= 3000
  && readableChineseChars >= 500
  && hasParagraphLikeContent
```

官方短文档可以进入 `STRONG`，但前提是 `officialDomainMatched=true` 且 `pageType=OFFICIAL_DOC`，避免把短摘要、导航页误判为高质量证据。

### 4.3 fastLaneUsable 判定

```text
fastLaneUsable =
  qualityTier in [STRONG, MEDIUM]
  && pageType in [ARTICLE, OFFICIAL_DOC, PDF, VIDEO_PAGE]
  && rawContentLength >= minRawContentChars
  && sourceUrls 不为空
```

网络验证短路条件更严格：

```text
skipNetworkVerification =
  fastLaneUsable
  && pageType in [ARTICLE, OFFICIAL_DOC, PDF]
  && rawContentLength >= 2000
  && tavilyScore >= 0.45
```

`VIDEO_PAGE` 即使可用，也默认不跳过轻量验证或人工可解释降权，因为视频页 raw_content 容易混入“相关推荐 / 猜你喜欢 / 热门推荐”。

### 4.4 contentCompleteness

Tavily 的 `raw_content` 可能不是全文，尤其是 PDF 和长文。因此快速通道结果需要标记完整度：

| completeness | 说明 |
| --- | --- |
| `FULL_ENOUGH` | 足以支撑当前 schema 字段提取 |
| `PARTIAL` | 可用于摘要或辅助证据，但不保证全文完整 |
| `THIN` | 仅保留诊断，不参与正文提取 |

`PDF` 默认应标记为 `PARTIAL`，除非 Tavily 返回的内容明确是短文档且已经覆盖全文；原因是 PDF 解析很容易只拿到局部正文或混入格式化噪声。

只有当 `schemaCompletenessSatisfied=true` 时，才能把路线标记为 `FAST_LANE_ONLY`。否则应使用 `FAST_LANE_PREFERRED`，允许后续 fallback。

## 5. 数据契约建议

### 5.1 SourceCandidate 扩展原则

不建议把完整 `raw_content` 长期挂在 `SourceCandidate` 上。当前系统已有对象瘦身要求，搜索审计、节点输出和 replay 不应携带大正文。

推荐拆法：

```text
SourceCandidate:
  hasPrefetchedContent
  prefetchedRawContentLength
  tavilyScore
  tavilyRequestId
  tavilyQuery
  pageType
  qualityTier
  fastLaneUsable
  fastLaneRejectReason
  contentCompleteness

PrefetchedContent runtime object:
  url
  title
  content
  rawContent
  cleanedContent
  sourceUrls
  qualitySignals
```

MVP 阶段可以不建表，但必须避免把完整 raw_content 写入 searchAudit、节点 output 或大范围 replay。

`PrefetchedContent` 的生命周期建议保持轻量：MVP 阶段只作为运行时对象存在，`TavilyPrefetchedExecutor` 产出 `CollectionExecutionResult` 后，完整 `rawContent` 可以被丢弃或等待 GC；audit / replay 只保留 URL、长度、hash、query、requestId、qualityTier、pageType、rejectReason、contentCompleteness 等元数据。

### 5.2 CollectionExecutionResult 映射

`TavilyPrefetchedExecutor` 负责把预采集正文映射为统一采集结果：

```text
输入：SourceCandidate + PrefetchedContent
处理：
  1. 读取 Tavily raw_content
  2. 做轻量清洗
  3. 删除或截断相关推荐、猜你喜欢、热门推荐等噪声段
  4. 构造 CollectionExecutionResult

输出：
  executorType = TAVILY_PREFETCHED
  success = true / false
  content = cleanedContent
  sourceUrls = Tavily url + 原始 sourceUrls
  qualitySignals = [TAVILY_RAW_CONTENT_READY, ...]
  qualityScore = Gate 给出的评分
  failureKind = 不可用时的明确分类
```

## 6. 与 Orchestration 的配合

Tavily 不应被任何 Agent 自由调用。它应作为 Orchestrator 决策后的受控补证据能力。

```text
Analyzer / Writer / Citation / Reviewer
  -> 发现 missing_evidence / unsupported_claim / citationGap
  -> AgentSuggestion
  -> OrchestrationDecision
  -> DecisionPolicyResult
  -> FAST_LANE_EVIDENCE_EXPANSION
  -> Tavily Fast Lane
  -> 新 EvidenceSource
  -> 相关节点重跑
```

建议动作类型：

| 动作 | 说明 |
| --- | --- |
| `EVIDENCE_EXPANSION` | 普通补证据 |
| `OFFICIAL_EVIDENCE_REPAIR` | 补官方来源 |
| `CLAIM_SUPPORT_SEARCH` | 为某个结论找支撑来源 |
| `SOURCE_DIVERSITY_REPAIR` | 补官方/第三方/研报多来源交叉验证 |
| `DOMAIN_HINT_DISCOVERY` | 先发现可信官方域名 |
| `FAST_LANE_COLLECT` | 使用 Tavily raw_content 直接补采 |

这些动作不一定都要新增为底层 `OrchestrationDecisionService` 类型。更稳妥的做法是把 Tavily 动作映射到现有决策类型：

| Tavily 动作 | 现有动作映射 | 执行含义 |
| --- | --- | --- |
| `EVIDENCE_EXPANSION` | `APPEND_DYNAMIC_BRANCH` / `SUPPLEMENT_EVIDENCE` | 追加一个 Tavily 证据补充分支 |
| `OFFICIAL_EVIDENCE_REPAIR` | `APPEND_DYNAMIC_BRANCH` / `SUPPLEMENT_EVIDENCE` | 使用 `EVIDENCE_REPAIR` query mode，并带上官方 `include_domains` |
| `CLAIM_SUPPORT_SEARCH` | `APPEND_DYNAMIC_BRANCH` / `SUPPLEMENT_EVIDENCE` | query 来自 claim 文本或 claim 的缺口描述 |
| `SOURCE_DIVERSITY_REPAIR` | `APPEND_DYNAMIC_BRANCH` / `SUPPLEMENT_EVIDENCE` | 可能触发多次 Tavily 调用，例如一次 `OPEN_WEB` 加一次 `OFFICIAL_DOCS` |
| `FAST_LANE_COLLECT` | `APPEND_DYNAMIC_BRANCH` + `TavilyPrefetchedExecutor` | 使用 Tavily 已返回的 `raw_content` 直接形成采集结果 |
| `DOMAIN_HINT_DISCOVERY` | 不直接映射为 append branch | 这是元操作，先更新 `DomainHintSet`，再触发后续 evidence action |

`DOMAIN_HINT_DISCOVERY` 是唯一不能直接等价为“追加证据分支”的动作。MVP 阶段不建议把它做成全自动 Orchestration 动作，只保留为搜索前置流程或人工/测试桩触发能力。

## 7. MVP 与完整生产版边界

### 7.1 快速通道 MVP

MVP 只回答一个问题：Tavily 是否能稳定提升真实任务的 EvidenceSource 质量。

MVP 包含：

- TavilyFastLaneProvider。
- family-aware query profile。
- `OPEN_WEB / OFFICIAL_DOCS` 两种 query mode。
- 基础 DomainHintSet 输入。
- Prefetched Content Gate 第一版。
- TavilyPrefetchedExecutor。
- 基础 TavilyFastLaneAudit。
- A/B 验收脚本和固定样本。

MVP 不包含：

- 完整域名缓存生命周期。
- 跨任务 Tavily 结果缓存。
- 成本仪表盘。
- 复杂运营调参后台。
- 全量 replay 持久化优化。
- 所有 Orchestrator 补证动作的自动化闭环。
- `DOMAIN_HINT_DISCOVERY` 作为 Orchestration 自动动作的完整闭环。

### 7.2 完整生产版

完整生产版在 MVP 跑通真实任务后再做。

生产版包含：

- DomainHintSet 发现、验证、缓存、过期和复用。
- query profile 预算控制和灰度开关。
- Tavily 限流、熔断、失败隔离。
- 完整 TavilyFastLaneAudit 与 replay。
- 趋势诊断：usable/rejected/fallback 变化。
- 与 Orchestration 的自动补证闭环。
- 多任务、多竞品、多 family 的 live 验收。

## 8. 测试验收方案

本方案的验收重点不是“能不能调通 Tavily API”，而是“能否让 EvidenceSource 质量明显提升，并让下游质量门禁更容易通过”。

### 8.1 基线对照

每次验收必须保留 baseline：

```text
Baseline A:
  当前系统，不启用 Tavily。

Experiment B:
  启用 Tavily OPEN_WEB，但不启用 include_domains。

Experiment C:
  启用 Tavily family-aware 策略：
    OFFICIAL / DOCS / PRICING 使用 include_domains
    NEWS / REVIEW / RESEARCH 不使用 include_domains

Experiment D:
  启用 Tavily family-aware + fallback 保留。
  Tavily 失败、超时或 Gate 判定 REJECT 时，自动降级到 DirectHtml / Jina / Playwright。
```

D 组最接近真实生产行为，用来验证“快速通道优先，但原链路兜底”是否能同时提升质量和稳定性。

推荐第一批固定场景：

| 场景 | 目标 |
| --- | --- |
| 抖音 vs 哔哩哔哩 推荐算法/内容分发 | 验证 OPEN_WEB 能否补足第三方分析、研报、博客 |
| 抖音开放平台/API 文档 | 验证 OFFICIAL_DOCS + include_domains 能否精准命中文档 |
| B站开放平台/API 文档 | 验证官方文档域名发现与 Tavily 命中 |
| 创作者规则/流量规则 | 验证官方规则、协议、帮助中心召回 |

### 8.2 EvidenceSource 质量指标

以下指标用于判断 Tavily 是否真的提升 EvidenceSource 质量。

| 指标 | 说明 | MVP 目标 |
| --- | --- | --- |
| `validEvidenceSourceCount` | 成功落库且可被下游读取的 EvidenceSource 数量 | 高于 baseline |
| `traceableEvidenceRatio` | `sourceUrls` 非空比例 | 必须 100% |
| `usableContentRatio` | 正文长度与 qualitySignals 达到可用门槛的比例 | 高于 baseline |
| `thinContentRatio` | `THIN_CONTENT_ONLY` 或弱正文证据比例 | 低于 baseline |
| `officialDocHitCount` | 官方文档/规则/协议命中数 | OFFICIAL/DOCS 场景明显高于 baseline |
| `openWebDiversityCount` | 第三方域名数量 | OPEN_WEB 场景高于 include_domains-only 策略 |
| `highTrustEvidenceCount` | HIGH trustTier 证据数量 | 高于 baseline |
| `mediumOrAboveEvidenceCount` | MEDIUM 及以上证据数量 | 高于 baseline |
| `collectionFailureRatio` | 采集失败比例 | 低于 baseline |
| `playwrightInvocationCount` | Playwright 调用次数 | 低于 baseline |

MVP 的最低通过条件：

```text
1. traceableEvidenceRatio = 100%
2. Tavily 场景 mediumOrAboveEvidenceCount 高于 baseline
3. OFFICIAL/DOCS 场景 officialDocHitCount 高于 baseline
4. OPEN_WEB 场景 openWebDiversityCount 高于 include_domains-only 策略
5. thinContentRatio 不高于 baseline
6. collectionFailureRatio 不高于 baseline
7. 所有 REJECT 结果有明确 fastLaneRejectReason
8. playwrightInvocationCount 低于 baseline，用于证明快速通道确实减少了全浏览器兜底调用
```

### 8.3 下游质量指标

EvidenceSource 质量提升还必须被下游消费到。

| 指标 | 说明 | 目标 |
| --- | --- | --- |
| `extractorFieldsExtracted` | 提取出的业务字段数 | 不低于 baseline，理想情况提升 |
| `evidenceCoverage.traceable` | 可追溯字段数量 | 提升 |
| `evidenceCoverage.empty` | 缺证据字段数量 | 下降 |
| `missingEvidenceIssueCount` | Reviewer / Citation 报告的缺证据问题数 | 下降 |
| `unsupportedClaimCount` | 无证据支撑结论数 | 下降 |
| `citationGapCount` | 章节或 claim 引用缺口数 | 下降 |
| `qualityScore` | 终审质量分 | 不低于 baseline，理想情况提升 |

MVP 不要求一次性让最终报告通过质量门禁，但必须能证明：

```text
Tavily 增加的 EvidenceSource 被 extractor / analyzer / writer / reviewer 至少一个下游环节消费到。
```

### 8.4 Gate 负样本验收

必须使用 Tavily 测试中已经暴露的噪声类型做负样本：

| 噪声类型 | 验收要求 |
| --- | --- |
| `douyin.com/search/...` 搜索页 | `pageType=SEARCH_PAGE`，`qualityTier=REJECT` |
| `bilibili.com/search?...` 搜索列表 | `pageType=SEARCH_PAGE` 或 `VIDEO_LIST`，不能快速通道 |
| 视频推荐列表页 | 不能作为高可信证据 |
| Reddit / 即刻讨论帖 | 可保留为 `FORUM_THREAD`，trustTier 不得为 HIGH |
| 泛推荐系统课程视频 | 不得被判为竞品分析强证据 |
| raw_content 为空但 content 有一句摘要 | 不得进入 TavilyPrefetchedExecutor |

### 8.5 审计验收

每次 Tavily 执行必须产出最小审计摘要：

```json
{
  "queryMode": "OPEN_WEB",
  "queriesSent": 3,
  "totalResults": 15,
  "fastLaneUsableCount": 9,
  "fastLaneRejectedCount": 6,
  "fallbackTriggered": true,
  "rejectionReasons": {
    "SEARCH_PAGE": 2,
    "WEAK_CONTENT": 3,
    "LOW_SCORE": 1
  },
  "tavilyRequestIds": ["..."]
}
```

验收要求：

- `fastLaneUsableCount + fastLaneRejectedCount` 与 Tavily 唯一 URL 数量一致。
- 每个 rejected candidate 必须能在 replay 或 audit 中看到 reject reason。
- 每个 fallback candidate 必须能看到 fallback 到原链路的原因。
- 每个进入 EvidenceSource 的 Tavily 结果必须保留 `sourceUrls`。

### 8.6 Orchestration 配合验收

MVP 阶段可先做半自动验收：

```text
1. Reviewer / Citation 产生 missing_evidence 或 citationGap。
2. 手动或测试桩生成 OrchestrationDecision。
3. 决策动作类型为 OFFICIAL_EVIDENCE_REPAIR 或 EVIDENCE_EXPANSION。
4. Tavily family profile 根据决策执行补证据。
5. 新 EvidenceSource 落库，并能被后续 extractor / writer / citation 节点读取。
```

生产版再要求自动闭环：

```text
质量问题 -> Orchestrator 决策 -> Tavily 补证据 -> 动态补图 -> 下游重跑 -> 质量问题减少
```

## 9. 推荐实施时机

当前工程主线仍在 3.3 / 3.4 / Citation / Reviewer 边界收口阶段。Tavily 不应插入当前未完成分支中直接改代码。

推荐节奏：

```text
现在：
  固化设计文档和验收口径。

当前 3.4 / Citation 改动收口后：
  启动 Wave 10: Tavily Fast Lane Evidence Expansion MVP。

MVP 跑过 2-3 个真实任务后：
  根据 EvidenceSource 质量指标和下游质量指标决定是否升级完整生产版。
```

升级完整生产版的触发条件：

```text
1. MVP 证明 mediumOrAboveEvidenceCount 明显高于 baseline。
2. OFFICIAL/DOCS 场景 officialDocHitCount 明显高于 baseline。
3. Reviewer / Citation 的 missing_evidence 或 citationGap 有下降趋势。
4. TavilyPrefetchedExecutor 产生的 EvidenceSource 能被下游稳定消费。
5. fastLaneRejectedCount、fallbackTriggered、rejectionReasons 能稳定审计。
```

## 10. 风险与边界

| 风险 | 控制方式 |
| --- | --- |
| Tavily 高分但页面低价值 | Gate 识别 `SEARCH_PAGE / VIDEO_LIST / THIN` |
| include_domains 限制发散性 | 按 family 决定是否使用 |
| 官方子域名遗漏 | DomainHintSet + deterministic supplements |
| raw_content 不是全文 | `contentCompleteness` 标记 |
| SourceCandidate 对象膨胀 | 不在 SourceCandidate 长期保存完整 raw_content |
| 下游误用低可信素材 | trustTier + pageType + qualitySignals 传递 |
| Orchestrator 越权调用工具 | 通过 DecisionPolicy 和受控 action 类型执行 |
| Tavily API 不可用、额度耗尽或响应超时 | Tavily Provider 内部重试，RoutingSearchSourceProvider fail-open 降级到千帆/SerpApi + 原采集链路 |

## 11. 一句话结论

Tavily Fast Lane 的价值不是多一个搜索渠道，而是让系统具备“快速补高质量证据”的能力。  
MVP 的验收重点必须放在 EvidenceSource 质量是否提升、下游是否真实消费、Reviewer/Citation 缺证据问题是否下降，而不是只验证 Tavily API 是否能调用成功。
