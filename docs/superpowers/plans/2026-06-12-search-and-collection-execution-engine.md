# Search And Collection Execution Engine Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `2.1.2 搜索与采集` 从“旧架构上的施工任务单”重写为真正继承 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 诊断主结构的优化方案，并据此裁出首轮 blocking 实施范围。

**Architecture:** 本文档按 `诊断继承 -> 阻塞分级 -> 优化波次 -> 首轮实施裁剪 -> 验收口径` 组织。规划期继续以 `TaskDefinition -> ExecutionPlanDefinition -> WorkflowPlan` 为唯一计划真相，运行期继续遵守 `TASK_NODE_RUNTIME_V1` 与 preview/runtime 分离红线；搜索与采集的优化不得偷换成底座重构、前端全量切换或零散 Task completion。

**Tech Stack:** Java 17, Spring Boot, Jackson, Redis, JUnit 5, Mockito, React 18, TypeScript

---

## Plan Positioning

这份文档替代旧版 `2026-06-12-search-and-collection-execution-engine.md` 的 Task 轴草稿。

旧稿的问题不在于“有没有提到某几个诊断点”，而在于它没有继承诊断主结构：

1. 它仍然从旧架构和施工顺序出发，把内容组织成 Task 1、Task 2、Task 3 的实施清单。
2. 它没有先回答“哪些矛盾会阻断后续全部优化”，再回答“这一轮先收哪一段”。
3. 它把搜索与采集这个大工程压成了若干顺手修补项，结果更像 patch list，而不像优化路线图。

因此本次重写的定性如下：

1. 旧 2.1.2 不再作为正式方案依据，只保留为历史草稿参考。
2. 旧稿里已与诊断对齐的成果只保留为“可复用输入”，不再决定一级结构：
   - `SearchPolicyResolver`
   - `OFFICIAL` 免检移除
   - 中文关键词策略补齐
   - `searchAudit` 正式 DTO 最小形状
3. 新方案必须先继承诊断，再按阻塞程度排优化顺序，最后才裁出本轮实施范围。

---

## Current Stage

当前阶段：搜索与采集首轮 blocking 实施与搜索/采集段实链验证已完成；第二轮 attempted / discarded / replay timeline / preview-runtime homology / ranking hardening 自动化收口与 dev live smoke 已完成；`Wave 5 / Wave 6` 后续独立实施待启动，下游提取/报告闭环另行验收

- [x] 诊断证据归并：已完成
- [x] 旧 Task 轴方案降级：已完成
- [x] 阻塞层级重排：已完成
- [x] 优化波次定义：已完成，已补入 `Wave 6` 垂直 provider 闭环
- [x] 首轮实施裁剪：已完成
- [x] 实施复核：已完成
- [x] 实链验证：已完成搜索与采集段 live 验收
- [ ] 垂直 provider 落地：待执行，归属 `Wave 6`

---

## Planning Rules

1. 方案必须从 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 的诊断结论继承，不能从旧计划反推结构。
2. 实施顺序按“若不先修，会不会阻断其余优化和实链验证”排序，而不是按“哪里顺手、哪里局部好改”排序。
3. `plans` 负责写清楚做什么、不做什么、为什么这样分波次；真正的逐步实施清单应另写实施计划，不再塞回本文件。
4. `sourceUrls` 是全链路可追溯红线；任何新审计对象、回放对象、恢复对象都不得静默丢失来源回指。
5. preview/runtime 分离仍然成立，但“分离”不等于“两套互相矛盾的世界”；预览与运行必须共享同一策略骨架。
6. 若要触碰 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总策略重组，必须另起专题，不得伪装成搜索链路顺手修改。
7. 搜索与采集必须按“数据源家族”建模，provider / API / scraper 只是工具层；配置不能再把 `Qianfan`、`SerpApi`、浏览器、公网搜索引擎当成业务来源本身。

---

## Diagnostic Inheritance And Blocking Hierarchy

下表不是把诊断重新缩写成四个好看的标题，而是先判断哪一类矛盾最先阻断优化，再把分散的诊断项归并进来。

| 阻塞级别 | 主矛盾 | 代表诊断证据 | 涉及对象簇 | 若不先修会发生什么 |
| --- | --- | --- | --- | --- |
| `P0` | 执行真相分裂，系统同时存在假计划、假能力、假配置 | [PromptTemplateService:265](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:265)、[RoutingSearchSourceProvider:381](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:381)、[SearchExecutionCoordinator:617](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:617)、[WorkflowFactory:1247](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1247)、[SearchProperties:1356](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1356)、[SearchRuntimePolicy:1447](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1447) | `WorkflowFactory`、`SearchExecutionCoordinator`、`RoutingSearchSourceProvider`、`PromptTemplateService`、`SearchProperties`、`SearchRuntimePolicy`、`SearchProviderProperties`、`SearchSourceProviderDescriptor` | 后续所有质量优化、回放解释、真实验收都会建立在不一致的计划和策略之上，越修越乱 |
| `P0` | 预览与运行不是“轻量差异”，而是两套不同世界，且持续暴露伪能力 | [HeuristicSourceDiscoveryService:330](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:330)、[SearchExecutionCoordinator:617](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:617)、[BrowserPreviewSearchSourceProvider:1567](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1567)、[SourceDiscoveryService:3037](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:3037) | `HeuristicSourceDiscoveryService`、`BrowserPreviewSearchSourceProvider`、`SourceDiscoveryService`、计划预览链路 | 预览看到的策略、运行执行的策略、故障时能解释的策略不是同一件事，实链验证没有可信基线 |
| `P1` | 验证与选源质量存在反向激励，营销页更容易赢，中文干货更容易输 | [CandidateVerifier:147](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:147)、[CandidateVerifier:168](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:168)、[CollectionTargetSelector:62](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:62)、[SourceCandidateRanker:1116](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1116)、[BrowserSearchRuntimeService:707](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:707)、[PageContentExtractionSupport:3195](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:3195) | `CandidateVerifier`、`CollectionTargetSelector`、`SourceCandidateRanker`、`BrowserSearchRuntimeService`、`PageContentExtractionSupport`、`PlaywrightPageCollector`、`SearchEngineProperties`、`SearchBrowserProperties` | 即便策略入口统一了，结果质量仍会被营销落地页、广告首页、英文偏置词库系统性污染 |
| `P1` | 搜索现场没有稳定正式事实源，审计、回放、恢复彼此断层 | [SearchExecutionCoordinator:670](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:670)、[SearchAuditSnapshot:1891](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1891)、[SearchExecutionTrace:2164](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:2164)、[CollectorNodeInsightResponse:3726](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:3726)、[DagExecutor:4461](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:4461)、[TaskEventPublisher:4554](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:4554)、[TaskEventReplayService:4600](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:4600) | `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchExecutionUpdate`、`CollectorAgent`、`CollectorNodeInsightResponse`、`TaskEventPublisher`、`TaskEventReplayService`、`DagExecutor`、`AgentContext`、恢复链路 | 代码能跑不等于能解释，更不等于能恢复；没有正式事实源就无法完成 replay、resume、rerun 的高质量验收 |
| `P2` | 对象模型膨胀且重复事实源过多，链路越往后越难维护 | [CollectorNodeConfig:1665](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1665)、[SearchExecutionPlan:1783](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:1783)、[SourceCandidate:2851](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:2851)、[SourcePlan:2945](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:2945)、[CollectorNodeInsightResponse:3726](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:3726)、[ReportResponse:3971](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:3971) | `CollectorNodeConfig`、`SearchExecutionPlan`、`SourceCandidate`、`SourcePlan`、`SearchExecutionResult`、`ReportService`、`EvidenceQueryService`、报告与洞察 DTO | 即使前面暂时止血，后面继续扩展时仍会因为重复事实源、重对象、弱协议而反复返工 |

上表决定了本方案的总顺序：

1. 先收口 `P0`，把执行真相拉齐。
2. 再处理 `P1`，把质量红线和连续性事实源止血。
3. 最后再推进 `P2`，把膨胀模型和底座依赖做系统收缩。

---

## Optimization Axes

`CollectorAgent.md` 暴露的问题最终仍然会收敛成四条长期优化主线，但这四条主线必须服从上面的阻塞排序，而不是替代阻塞排序。

### 1. 策略与路由统一

这条主线承接的不是单一类 bug，而是整个“计划如何定义、运行如何选择、回放如何解释”的策略真相收口。

需要覆盖的诊断簇包括：

1. `PromptTemplateService` 模板源串线与官网搜索语义漂移。
2. `RoutingSearchSourceProvider` 的 provider 全量串扫和阶段错位。
3. `SearchExecutionCoordinator` 与 `WorkflowFactory` 共同暴露 `HEURISTIC` 假能力。
4. `SearchProperties`、`SearchRuntimePolicy`、`SearchProviderProperties`、`SearchSourceProviderDescriptor` 对模式、开关、预算、能力的语义分裂。
5. `HttpSearchSourceProvider`、`QianfanSearchSourceProvider`、`SerpApiSearchSourceProvider` 的 query、预算、去重、引擎元数据治理不统一。

这条主线的目标不是“抽一个 resolver 就结束”，而是把搜索策略从分散私有逻辑，收口成后续所有质量治理和审计治理都能共享的正式入口。

同时，这条主线必须显式继承 [CollectorAgent.md:56](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:56) 的业务优化原则，而不能只把它隐含在 provider 细节里：

1. 私域垂直 API / 结构化垂直渠道负责精准抓取干货，属于主力链路。
2. 公网搜索引擎 / 浏览器搜索负责全网查漏补缺、边界发现与兜底，属于辅助链路。
3. 路由、预算、query 生成、去重、验收都必须围绕这个主辅关系设计，不能继续把所有 provider 当成对等串扫渠道。
4. 官方网站、新闻媒体、技术博客、GitHub、社交媒体、财务数据、专利数据应作为可配置的数据源家族管理；不同家族声明自己的采集内容、主工具、辅助工具、更新策略和质量规则。

### 2. 验证与选源质量纠偏

这条主线处理的是结果质量，而不是执行骨架。

需要覆盖的诊断簇包括：

1. `CandidateVerifier` 的 `OFFICIAL` 免检与英文偏置词库。
2. `CollectionTargetSelector` 盲信既有尝试现场。
3. `SourceCandidateRanker` 先天抬举官网 / 商业域名。
4. `BrowserSearchRuntimeService` 的烂 query 放大问题。
5. `SearchEngineProperties`、`SearchBrowserProperties` 的低质量默认引擎与中文信号失配。
6. `PageContentExtractionSupport`、`PlaywrightPageCollector` 对中文页面、短正文高价值页面的误伤。

这条主线的目标不是“补几个词表”，而是把验证、排序、浏览器补源、正文抽取里的反向激励系统性纠正回来。

### 3. 预览 / 运行同构与伪能力清理

这条主线关注的是“用户看到的计划”和“系统真正执行的策略”是否属于同一骨架。

需要覆盖的诊断簇包括：

1. `HeuristicSourceDiscoveryService` 让预览世界与正式规划世界分家。
2. `BrowserPreviewSearchSourceProvider` 私自造一套预览专用迷你策略。
3. `SourceDiscoveryService` 从接口层就把预览/正式语义混在一起。
4. `WorkflowFactory`、`SearchExecutionCoordinator`、`SearchProperties` 持续把 `HEURISTIC` 伪能力写进配置、计划、回退顺序。

这条主线的目标不是取消 preview/runtime 分离，而是让两者至少共享同一策略骨架与同一能力边界。

### 4. 审计 / 回放 / 恢复连续性

这条主线解决的是“发生过什么、为什么发生、恢复到哪一步、下游怎么看到”。

需要覆盖的诊断簇包括：

1. `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchExecutionResult`、`SearchProgressSnapshot`、`SearchExecutionUpdate` 的职责混层。
2. `CollectorAgent`、`CollectorNodeInsightResponse`、`TaskNodeResponse`、`ReportService`、`ReportResponse` 对搜索现场的投影不一致。
3. `TaskEventPublisher`、`TaskEventReplayService`、`TaskEventStreamController`、`TaskSseHub` 无法稳定承载搜索 replay。
4. `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy`、`AgentContext` 仍依赖整包 output 回灌。

这条主线的目标不是“多加几个字段”，而是为搜索链路建立一个正式事实源，让运行、洞察、回放、恢复可以对齐。

---

## Source Family Catalog And Configuration Architecture

用户提供的参考表应被吸收为“数据源家族目录”，而不是简单变成一串新 provider。正式架构必须区分三层概念：

1. 数据源家族：业务上要采什么，例如官网、新闻、GitHub、专利。
2. 采集工具：用什么拿数据，例如 Web Scraper、Jina Reader、News API、GitHub API、RSS、公网搜索。
3. 更新策略：什么时候、以什么粒度刷新，例如每日增量、实时 RSS、每周全量、季度更新。

### 数据源家族基线

| 数据源家族 | 采集内容 | 主采集工具 | 辅助 / 兜底工具 | 更新策略 | 角色定位 | 首轮状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 官方网站 | 产品页面、定价、文档 | Web Scraper、Jina Reader | 公网搜索引擎用于发现遗漏页面 | 每日增量爬取 | `PRIMARY_VERTICAL` | 首轮必须进入配置骨架 |
| 新闻媒体 | 产品发布、融资、合作 | News API、RSS 监控 | Google / Bing / Baidu 等公网搜索查漏 | 实时 RSS + 定时补扫 | `PRIMARY_VERTICAL` | 首轮进入配置骨架，真实 News API 可后置 |
| 技术博客 | 技术架构、开源贡献 | Blog Crawler | 公网搜索补充站外转载 | 每周全量扫描 | `PRIMARY_VERTICAL` | 后续波次实现，首轮保留 schema |
| GitHub | 代码仓库、Star 趋势、release | GitHub API | 公网搜索补充组织 / 仓库发现 | 每日 API 轮询 | `PRIMARY_VERTICAL` | 首轮进入配置骨架，真实 GitHub API 可后置 |
| 社交媒体 | 用户反馈、舆情 | Twitter / Reddit API | 公网搜索与人工补证 | 关键词实时监控 | `AUXILIARY_PUBLIC` | 后续波次实现，首轮保留 schema |
| 财务数据 | 营收、用户量、估值 | Crunchbase API | 新闻搜索补证 | 季度更新 | `PRIMARY_VERTICAL` | 后续波次实现，首轮保留 schema |
| 专利数据 | 技术专利申请 | Patent API | 公网搜索补证 | 月度扫描 | `PRIMARY_VERTICAL` | 后续波次实现，首轮保留 schema |

### 配置分层原则

1. `SearchProperties` 承接全局搜索模式和数据源家族目录，例如 `search.source-catalog.families.official`。
2. `SearchProviderProperties` 只承接 provider 路由、启停、降级，不再表达“采什么业务数据”。
3. `SearchRuntimePolicy` 承接重试、超时、预算、并发、失败继续等运行期策略。
4. `SearchEngineProperties` 只承接公网搜索引擎配置，例如 `bing / baidu / duckduckgo`，不参与私域垂直 API 的业务分类。
5. `search-queries.yml` 只承接 query 模板；数据源家族配置通过 template key 引用模板，不把 query 字符串散落到 resolver 或 provider 私有方法里。

### 推荐配置形状

```yaml
search:
  source-catalog:
    families:
      official:
        enabled: true
        role: PRIMARY_VERTICAL
        sourceTypes: [OFFICIAL, PRICING, DOCS]
        contentScopes: [PRODUCT_PAGE, PRICING, DOCUMENTATION]
        primaryTools: [WEB_SCRAPER, JINA_READER]
        auxiliaryTools: [PUBLIC_SEARCH]
        updatePolicy:
          mode: DAILY_INCREMENTAL
          interval: PT24H
        queryTemplates:
          - search-official
          - search-official-domain
          - search-pricing-primary
          - search-docs-primary
      news:
        enabled: true
        role: PRIMARY_VERTICAL
        sourceTypes: [NEWS]
        contentScopes: [PRODUCT_RELEASE, FUNDING, PARTNERSHIP]
        primaryTools: [NEWS_API, RSS]
        auxiliaryTools: [PUBLIC_SEARCH]
        updatePolicy:
          mode: REALTIME_RSS_AND_SCHEDULED_SWEEP
          interval: PT1H
        queryTemplates:
          - search-news-primary
          - search-news-secondary
      github:
        enabled: true
        role: PRIMARY_VERTICAL
        sourceTypes: [GITHUB, OPEN_SOURCE]
        contentScopes: [REPOSITORY, STAR_TREND, RELEASE]
        primaryTools: [GITHUB_API]
        auxiliaryTools: [PUBLIC_SEARCH]
        updatePolicy:
          mode: DAILY_API_POLLING
          interval: PT24H
        queryTemplates:
          # 以下模板仅用于公网搜索辅助兜底，不替代 GITHUB_API 主采集。
          - search-github-repository
          - search-github-release
```

首轮实施只要求建立上述配置骨架，并让 `official / news / github` 三个家族能被绑定、解析和审计展示；不要求一次真实接入 News API、GitHub API、Twitter / Reddit API、Crunchbase API、Patent API。

但这只是首轮收口边界，不是长期方案完成口径。`Source Family Catalog` 只解决“业务上采什么、主辅工具如何声明”的配置骨架问题；它不能替代真实垂直 API provider。后续必须用独立波次把至少一个 `PRIMARY_VERTICAL` provider 做成可运行实现，并注册进路由、审计与角色解析链路，否则系统仍会退回 `qianfan -> serpapi -> browser -> http` 这组公网引擎串扫。

---

## Optimization Waves

主线说明“长期要修什么”，波次说明“为什么先修这部分”。

### Wave 0: 方案基线重置

目标：停止继续消费旧 Task 轴结构，把 [CollectorAgent.md](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md) 作为唯一诊断源。

本波次完成标准：

1. 旧 2.1.2 草稿降级为历史参考，不再作为正式方案依据。
2. 本文档改为按 `诊断继承 -> 阻塞分级 -> 优化波次` 组织。
3. `specs` 的方案状态口径同步更新，不再写成“旧方案继续推进”。

### Wave 1: 执行真相收口

目标：先解决会阻断一切后续优化的 `P0` 问题。

本波次必须解决的内容：

1. `SearchPolicyResolver` 成为规划期与运行期的统一策略入口。
2. `HEURISTIC` 要么做成真实能力，要么从计划、配置、回退顺序里彻底移除。
3. `PromptTemplateService` 的模板治理必须同时覆盖装载逻辑和模板源内容，不能只修装载不处理模板池内容漂移。
4. `SearchProperties`、`SearchRuntimePolicy`、`SearchProviderProperties` 对模式、预算、开关、生效值的语义必须开始收口为一条真实链。
5. `SearchEngineProperties`、provider default engine 相关归一化与可用性检查需要进入正式校验与测试口径。
6. provider 角色语义必须开始正式化，但首轮只要求完成“私域垂直家族主力、公网搜索辅助”的方向性声明与 `Source Family Catalog` 配置骨架；不要求对现有 `qianfan / serpapi / browser / http` provider 做错误的垂直化硬编码映射。
7. 建立 `Source Family Catalog` 配置骨架，首轮至少覆盖 `official / news / github` 三个家族，让策略入口能按数据源家族而不是 provider 名称解释采集计划。

本波次明确不要求一次完成：

1. provider 成本平台化。
2. 多 query 命中即止的完整预算治理。
3. 所有 provider 的统一降级平台。
4. 主辅 provider 的完整预算编排、配额平台与成本面板。
5. News API、GitHub API、Twitter / Reddit API、Crunchbase API、Patent API 的真实连接器与凭证治理。

### Wave 2: 质量红线止血

目标：把最致命的误放行、误杀、误排序问题先止住。

本波次必须解决的内容：

1. 移除 `OFFICIAL` 免检。
2. 建立正式 `SearchKeywordPolicy`，至少覆盖 `OFFICIAL`、`PRICING`、`DOCS`、`NEWS`、`REVIEW` 五类 source type 的中英文正向信号。
3. 建立正式营销落地页 veto 规则，不能只留下 `isMarketingLandingPage(...)` 的调用痕迹而没有规则定义。
4. 修正浏览器兜底 query 与正文抽取里最明显的中文信号缺失问题，避免“烂 query + 重型链路”被放大。
5. 纠正 `CollectionTargetSelector` / `SourceCandidateRanker` 对“官网 / 商业域名天然高分”的最小偏置。
6. 质量规则需要开始识别数据源家族差异，例如官网重视产品 / 定价 / 文档干货，新闻重视发布日期 / 事件类型，GitHub 重视仓库归属 / release / star 趋势。

本波次明确不要求一次完成：

1. 完整 tier/filter/ranker 重构。
2. 大模型语义裁决兜底。
3. 全链路 URL 归一化体系重做。

### Wave 3: 预览 / 运行同骨架

目标：让 preview/runtime 继续分离，但不再彼此矛盾。

本波次必须解决的内容：

1. preview 与 runtime 使用同一套已解析策略入口，而不是两套私有默认值。
2. 预览占位现场必须与正式执行骨架同构，不能再把搜索补源现场整体清零。
3. `BrowserPreviewSearchSourceProvider` 的迷你策略、`domainHint` 丢失、结果语义混淆都要开始收口。

本波次明确不要求一次完成：

1. 预览链路真实跑完所有 provider。
2. `MAX_CANDIDATES_PER_SCOPE` 等候选池策略的完整重做。
3. 预览结果与运行结果逐项等价。

### Wave 4: 连续性事实源建立

目标：让搜索链路第一次拥有正式可追溯的事实源。

本波次必须解决的内容：

1. 正式化 `searchAudit`，明确它与 `SearchExecutionTrace`、`SearchExecutionResult`、`Collector output` 的主从关系。
2. 在 runtime / insight / replay / SSE event 之间贯通最小正式视图。
3. 把最小恢复检查点语义正式化，不再只靠临时回灌与字符串 output 猜状态。
4. 所有新对象遵守 `sourceUrls` 红线。

本波次明确不要求一次完成：

1. 完整 `attemptedTargets` / `discardedTargets` 重恢复模型。
2. 跨重启 replay 持久化底座。
3. 任务恢复总策略重构。

### Wave 5: 对象瘦身与底座化

目标：把前几波止血后暴露出的结构性重复事实源收缩掉。

本波次应覆盖的内容：

1. `CollectorNodeConfig` 与 `SearchExecutionPlan` 的主从关系重建。
2. `SourceCandidate`、`SourcePlan`、`SearchCollectionTarget` 的职责拆分。
3. `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchExecutionResult`、`SearchExecutionUpdate` 的协议瘦身。
4. `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`AgentContext` 的共享上下文 / 恢复 / 缓存分层。
5. `ReportService`、`EvidenceQueryService`、`ReportResponse`、`CollectorNodeInsightResponse` 的下游投影统一。
6. 数据源家族配置与 provider 配置的职责拆分彻底平台化，避免后续扩展社交、财务、专利数据时再次回到硬编码分支。

这不是“以后有空再看”的尾项，而是搜索执行引擎要从“问题样板”走向“稳定子域”必须完成的收口波次。

### Wave 6: 垂直 API Provider 落地与主辅路由闭环

目标：把 [CollectorAgent.md:56](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:56) 的“私域垂直 API 主力、公网搜索辅助”从配置声明推进到真实运行链路，避免前五个波次完成后 provider 路由仍只有公网搜索引擎在工作。

本波次必须解决的内容：

1. 至少实现一个真实 `PRIMARY_VERTICAL` provider，优先从 `GitHub API` 或 `News API / RSS` 中选择一个作为首个落地对象；如果生产凭证暂不可用，也必须有配置禁用态、凭证缺失告警、Mock HTTP 契约测试和可复核的重试 / 超时 / 降级行为。
2. 新 provider 必须实现稳定 provider 身份、可用性判断、Max Retries、异常捕获、`sourceUrls` 回填和结构化审计字段，不能依赖接口默认 `providerKey`、默认 `isAvailable=true` 或 fail-open 吞错。
3. `Source Family Catalog` 的 `primaryTools` 必须能绑定到真实 provider；`SearchProviderProperties` 负责 provider 启停、凭证、超时、重试与降级；二者通过稳定 key 关联，不能重新把业务家族硬编码进 provider 私有逻辑。
4. `SearchPolicyResolver.resolveProviderRole(...)` 必须真正区分 `PRIMARY_VERTICAL` 与 `AUXILIARY_PUBLIC`：真实垂直 provider 返回 `PRIMARY_VERTICAL`，`qianfan / serpapi / browser / http` 等公网搜索 provider 继续返回 `AUXILIARY_PUBLIC`。
5. `RoutingSearchSourceProvider` 或其后续路由器必须按主辅关系执行：先跑可用的垂直主力 provider；当主力 provider 不可用、候选不足、质量水位不足或预算策略允许时，才进入公网搜索辅助补漏。
6. 审计、回放和 insight 必须能展示 provider role、source family、provider key、query/template、跳过原因、降级原因和最终候选来源，不能只展示混合后的候选列表。
7. 测试必须覆盖配置绑定、provider 可用性、凭证缺失、重试耗尽、主辅路由顺序、`resolveProviderRole` 差异、`sourceUrls` 保留、审计字段和公网辅助降级路径。

本波次明确不要求一次完成：

1. 社交媒体、财务数据、专利数据的真实外部 API 全量接入。
2. provider 成本平台、配额仪表盘和反爬产品化面板。
3. 所有数据源家族的垂直 provider 全覆盖。
4. 跨重启 replay 持久化底座和任务恢复总策略重构。

---

## Mandatory Corrections From The Previous Draft

前稿里已经暴露出的四个遗漏，本次重写后直接升级为实施 guard，不再作为可选建议：

1. `SearchKeywordPolicy` 不得只覆盖 `PRICING` 和 `OFFICIAL`。首轮必须至少覆盖 `DOCS`、`NEWS`、`REVIEW` 的中文词表，否则“中文关键词补齐”不成立。
2. `isMarketingLandingPage(...)` 不得只出现在调用点。它必须作为正式规则落地，明确依赖哪些标题/正文/路径/按钮词信号，并由测试覆盖。
3. `PromptTemplateService` 若调整模板装载逻辑，必须同时明确 `backend/src/main/resources/prompts/search-queries.yml` 是否需要同步修改。默认接受的方案是“装载逻辑与模板源内容一起治理”，不接受隐式造成英文模板池空转。
4. `SearchEngineProperties` 相关测试不得只拿裸 `Map` 走通 happy path。必须同时验证 alias 归一化与 enabled 可用性解析链路，例如 `ddg -> duckduckgo -> enabled=true`。

本次复核新增一个方案级 guard：`Source Family Catalog` 配置骨架不得被解释为“垂直 API provider 已完成”。真实 provider 实现、注册进路由、`resolveProviderRole` 主辅区分和审计可解释性必须进入 `Wave 6`，否则 `搜索与采集` 的整体实施状态不得升为 `✅`。

---

## First Iteration Cut

首轮实施不是“从父计划里挑几个最好做的 Task”，而是只截取那些不收掉就无法继续做后续优化和实链验证的前置收口包。

### 收口包 A: 执行真相收口包

来源波次：`Wave 1 + Wave 3`

必须覆盖：

1. `SearchPolicyResolver` 统一策略入口。
2. `HEURISTIC` 假计划 / 假能力清理。
3. preview 路径改为消费同一策略骨架。
4. `PromptTemplateService` 与 `search-queries.yml` 的模板治理口径拉齐。
5. `SearchEngineProperties` 与 provider default engine 的归一化 / 可用性测试补齐。
6. `Source Family Catalog` 首轮配置骨架落地，至少能解析 `official / news / github` 三类家族、主辅工具、更新策略和 query template 引用。

不先做这一包，会直接拦住后续所有质量验证与实链验收。

### 收口包 B: 质量止血包

来源波次：`Wave 2`

必须覆盖：

1. `OFFICIAL` 免检移除。
2. `SearchKeywordPolicy` 首轮正式覆盖 `OFFICIAL`、`PRICING`、`DOCS`、`NEWS`、`REVIEW`。
3. 正式营销页 veto 规则落地并补测试。
4. 浏览器兜底 query 与正文抽取中最明显的中文误伤点开始止血。

不先做这一包，后面的 replay、resume、报告洞察即使可见，也仍然是在解释错误结果。

### 收口包 C: 连续性事实源包

来源波次：`Wave 4`

必须覆盖：

1. `searchAudit` 最小正式 DTO 落地。
2. runtime / insight / replay / event 最小贯通。
3. 最小恢复检查点语义正式化。
4. 所有新增对象带 `sourceUrls`。

不先做这一包，就无法完成搜索链路的 replay / rerun / resume 高质量验收。

### 首轮明确不做

1. 不展开完整 tier/filter/ranker 重构。
2. 不展开 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总策略改造。
3. 不做前端搜索详情页和回放面板的全量正式切换，只允许最小兼容透传。
4. 不做跨重启 replay 持久化底座。
5. 不把对象瘦身与底座化波次偷偷塞进首轮 blocking 实施。
6. 不真实接入 Twitter / Reddit、Crunchbase、Patent API；News API 与 GitHub API 在首轮只要求配置架构可承载，真实 provider 实现转入 `Wave 6`，不得再用“后置”模糊处理。

---

## Progress Plan

为满足 `specs` 与 AGENTS 约束，后续推进顺序必须显式可视化如下：

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase A | 完成 `Wave 1` 执行真相收口 | 2-4 天 | 本文档冻结为正式方案 | 已完成 |
| Phase B | 完成 `Wave 2` 质量止血 | 2-4 天 | Phase A 至少收口策略入口与模板治理 | 已完成 |
| Phase C | 完成 `Wave 3` 预览 / 运行同骨架 | 1-3 天 | Phase A 完成 | 已完成 |
| Phase D | 完成 `Wave 4` 连续性事实源最小贯通 | 2-4 天 | Phase A 完成，Phase B 基本止血 | 已完成 |
| Phase E | 启动 `Wave 5` 对象瘦身、数据源家族配置平台化与底座化专题 | 1-2 个迭代 | Phase A-D 完成并复核 | 待执行 |
| Phase F | 完成 `Wave 6` 垂直 API provider 落地与主辅路由闭环 | 1 个迭代 | Phase E 至少完成 provider / source family 职责拆分，且选定首个垂直 provider 的凭证或 Mock 契约 | 待执行 |

当前允许进入实施的范围，仅限 `Phase A + Phase B + Phase C + Phase D` 里的首轮 blocking 收口包；`Phase E / Phase F` 不得提前偷跑。若团队决定优先补垂直 provider，也必须先单独改写实施计划，不能把它隐式塞进首轮完成口径。

---

## Acceptance

只有同时满足以下条件，`方案` 才能视为完成，`实施` 才允许继续推进：

1. 文档一级结构已从旧 Task 轴改为诊断继承和阻塞分级。
2. 文档明确写出为什么旧稿不算优化方案，而只是历史施工清单。
3. 文档明确给出 `P0 / P1 / P2` 阻塞排序，而不是只给四个好看的主题词。
4. 文档同时给出长期优化波次与首轮实施裁剪，避免再把大工程压成几个顺手 patch。
5. 文档把前稿遗漏的四个 guard 升级为正式实施要求。
6. 文档明确把官网、新闻、GitHub 等建模为数据源家族，把 Web Scraper、Jina Reader、News API、GitHub API、公网搜索建模为工具层或 provider 层。
7. 文档显式给出 `Wave 6`，把“至少一个垂直 provider + 注册进路由 + `resolveProviderRole` 主辅区分”列为独立交付项。
8. `specs` 中 `搜索与采集` 的状态口径与本文件一致。

首轮 blocking 收口包要标记为完成，必须同时满足以下条件：

1. `SearchPolicyResolver` 已成为统一策略入口。
2. `HEURISTIC` 假能力已被清理或真实实现。
3. 模板治理已同时覆盖装载逻辑与模板源口径。
4. `SearchKeywordPolicy` 首轮正式覆盖与营销页 veto 规则已落地。
5. `searchAudit` 最小正式 DTO 已进入 runtime / insight / replay / event。
6. `official / news / github` 数据源家族配置骨架已能绑定、解析并进入审计说明。
7. 相关单元测试、集成测试、架构测试 PASS。

整体 `搜索与采集` 实施状态要从 `🟡` 升为 `✅`，除首轮 blocking 收口包之外，还必须额外满足以下条件：

1. 至少一个真实垂直 provider 已完成实现，并能在配置启用时返回可追溯候选。
2. 该 provider 已通过 `Source Family Catalog.primaryTools` 与 `SearchProviderProperties` 注册进正式路由。
3. `resolveProviderRole` 对真实垂直 provider 返回 `PRIMARY_VERTICAL`，对 `qianfan / serpapi / browser / http` 等公网搜索 provider 返回 `AUXILIARY_PUBLIC`。
4. 路由审计能证明主力垂直 provider 优先执行，公网搜索只作为查漏补缺、候选不足或降级兜底路径。
5. 新 provider 的 Max Retries、异常捕获、凭证缺失、`sourceUrls`、审计字段和降级路径均有自动化测试覆盖。

`实链验证` 已于 2026-06-12 通过 dev live app 完成搜索与采集段验收：真实任务 `33` 通过 `/api/task/preview`、`/api/task/create`、`/api/task/{id}/execute` 跑出 4 个成功的 `COLLECTOR` 节点，累计 14 个 `sourceUrls`，每个采集节点均包含 `searchAudit`，回放接口返回 4 条 `searchReplays`；随后 `/api/task/{id}/resume` 与 `/api/task/{id}/nodes/collect_sources_01_01/rerun` 均返回 200，重跑后的采集节点仍保持 `searchAuditCheckpoint=SELECT_TARGETS`。这次验收只证明首轮公网补源 / 采集 / 回放链路可工作，不代表 `Wave 6` 垂直 provider 已完成。

`LLM token 复验` 已于 2026-06-15 通过 dev live app 补证：User 级 `DEEPSEEK_API_KEY`（后缀 `e66d2c7b`）对 DeepSeek `/v1/models` 直连返回 200；真实任务 `37` 通过 `/api/task/37/resume` 从 `extract_schema` 检查点恢复后，`extract_schema`、`analyze_competitors`、`write_report`、`quality_check`、`rewrite_report`、`quality_check_final` 均执行到 `SUCCESS`，原先“下游 LLM provider token 无效”的 blocker 已解除。该任务最终总状态仍为 `FAILED`，原因是最终质量门禁未通过（`qualityScore=61`、`qualityPassed=false`），报告证据接口仅返回 1 条有效证据且为 `https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw`；回放侧仍保留 `sourceUrls=2`、`searchReplays=1`、`timeline=8`、`attemptedTargets=1`、`discardedCandidates=1`、`recoveryCheckpoint=SELECT_TARGETS`。因此当前剩余 blocker 已从 token 鉴权转为采集证据质量 / 业务质量闭环问题，不回退首轮搜索与采集段实链验收结论。

`第二轮自动化复核与 dev live smoke` 已于 2026-06-15 完成：attemptedTargets、discardedCandidates、稳定 replay timeline、collector insight 直出、preview/runtime source family 同构、质量信号排序硬化均已由契约测试覆盖；聚合命令 `mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest,SearchAndCollectionGoldenMasterTest" test` 通过 49 tests，`mvn -pl backend test` 通过 438 tests。真实任务 `39` 已补跑 `/api/task/preview`、`/api/task/create`、`/api/task/{id}/execute`、`/api/task/{id}/replay`、`/api/task/{id}/nodes/collect_sources_01_01/rerun`、`/api/task/{id}/resume`：preview/create 可见 source family 字段；execute/replay/rerun/resume 保留 `attemptedTargets / discardedCandidates / replayTimeline`，且 `recoveryCheckpoint=SELECT_TARGETS` 与 timeline 末尾一致。初次 execute 因 Playwright `__adopt__` / 反爬信号进入 `WAITING_INTERVENTION`，但 rerun 成功补证事实源不丢；`Wave 6` 垂直 provider、主辅路由闭环和跨重启 replay 持久化仍保持待实施。

---

## Rollback

如果团队决定暂缓 `Wave 5 / Wave 6` 后续实施，允许回退到以下保守状态：

1. 保留本文档作为搜索与采集的正式优化方案基线。
2. 旧 Task 轴草稿继续保留为历史参考，但不得重新升格为正式方案。
3. `specs` 中 `搜索与采集` 维持：
   - 诊断 `✅`
   - 方案 `✅`
   - 实施 `🟡`
   - 实链验证 `🟡`

---

## Out Of Scope

1. `提取结构化`、`分析推理`、`报告写作` 链路的正式契约收口。
2. 搜索 provider 成本平台、预算仪表盘、反爬产品化面板。
3. 搜索详情页、回放页、事件 reducer 的前端全量协议切换。
4. 搜索链路的跨重启 replay 持久化底座。
5. 共享上下文、热快照缓存、任务恢复服务的整体系重构。
6. 社交媒体、财务数据、专利数据的真实外部 API 接入、凭证治理、配额治理和产品化调度面板；但 `GitHub API` 或 `News API / RSS` 中至少一个真实垂直 provider 不在本 Out Of Scope，归 `Wave 6`。
