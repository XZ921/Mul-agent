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

当前阶段：搜索与采集前三轮 blocking 收口、第二轮自动化契约、第四轮 discovery/collection 联合实施已完成；`Wave 5` 对象瘦身、`Wave 7` 最小采集执行接缝、以及首个 GitHub API 结构化采集执行器均已落地并完成定向测试，`Wave 6` 真实 discovery provider 进真实运行链路与下游提取/报告业务质量闭环仍待后续验收

- [x] 诊断证据归并：已完成
- [x] 旧 Task 轴方案降级：已完成
- [x] 阻塞层级重排：已完成
- [x] 优化波次定义：已完成，已补入 `Wave 6` 垂直 provider 闭环
- [x] 首轮实施裁剪：已完成
- [x] 实施复核：已完成
- [x] 实链验证：已完成搜索与采集段 live 验收
- [x] 最小采集执行接缝：已完成，归属 `Wave 7`
- [x] 首个 API 结构化采集执行器：已完成首个 GitHub 闭环，归属 `Wave 10`
- [ ] 垂直 provider discovery 实链落地：待执行，归属 `Wave 6`

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

### Wave 6: 垂直发现 Provider 落地与主辅路由闭环

目标：把 [CollectorAgent.md:56](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:56) 的“私域垂直 API 主力、公网搜索辅助”从配置声明推进到真实运行链路，避免前五个波次完成后 provider 路由仍只有公网搜索引擎在工作。

本波次需要额外明确一条 owner 边界：

1. `Wave 6` 的 vertical provider 归属搜索发现层，职责是返回候选 URL、候选资源或稳定 `resource locator`，让主辅发现路由真正成立。
2. `Wave 6` 不承担最终结构化采集执行 owner，不得把“API 已返回结构化数据”和“collector 已完成正式证据采集”混成一件事。
3. 若某个外部 API 同时在发现层和采集层出现，例如 `GitHub API` 或 `News API`，则 `Wave 6` 只解决 discovery owner，`Wave 10` 才解决 collection owner。

本波次必须解决的内容：

1. 至少实现一个真实 `PRIMARY_VERTICAL` discovery provider，推荐优先从 `News API / RSS` 中选择一个作为首个落地对象；如果选择 `GitHub API`，只允许把它实现为 discovery provider：返回仓库、组织、release 等候选 URL 或稳定 `resource locator`，不得在本波次把结构化 API 响应伪装成网页采集结果，更不得要求 `PlaywrightPageCollector` 成为 GitHub API 的长期主采集路径。如果生产凭证暂不可用，也必须有配置禁用态、凭证缺失告警、Mock HTTP 契约测试和可复核的重试 / 超时 / 降级行为。
2. 新 provider 必须实现稳定 provider 身份、可用性判断、Max Retries、异常捕获、`sourceUrls` 回填和结构化审计字段，不能依赖接口默认 `providerKey`、默认 `isAvailable=true` 或 fail-open 吞错。
3. `Source Family Catalog` 的 `primaryTools` 必须能绑定到真实 provider；`SearchProviderProperties` 负责 provider 启停、凭证、超时、重试与降级；二者通过稳定 key 关联，不能重新把业务家族硬编码进 provider 私有逻辑。
4. `SearchPolicyResolver.resolveProviderRole(...)` 必须真正区分 `PRIMARY_VERTICAL` 与 `AUXILIARY_PUBLIC`：真实垂直 provider 返回 `PRIMARY_VERTICAL`，`qianfan / serpapi / browser / http` 等公网搜索 provider 继续返回 `AUXILIARY_PUBLIC`。
5. `RoutingSearchSourceProvider` 或其后续路由器必须按主辅关系执行：先跑可用的垂直主力 provider；当主力 provider 不可用、候选不足、质量水位不足或预算策略允许时，才进入公网搜索辅助补漏。
6. 审计、回放和 insight 必须能展示 provider role、source family、provider key、query/template、跳过原因、降级原因和最终候选来源，不能只展示混合后的候选列表。
7. 测试必须覆盖配置绑定、provider 可用性、凭证缺失、重试耗尽、主辅路由顺序、`resolveProviderRole` 差异、`sourceUrls` 保留、审计字段和公网辅助降级路径；如果选择 `GitHub API`，还必须覆盖“discovery 输出不会偷渡成 collection 结果”的边界断言。

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
| Phase E | 完成 `Wave 5` 对象瘦身、数据源家族配置平台化与底座化专题 | 1-2 个迭代 | Phase A-D 完成并复核 | 已完成 |
| Phase F | 完成 `Wave 6` 垂直 API provider 落地与主辅路由闭环 | 1 个迭代 | Phase E 已完成，且选定首个垂直 provider 的凭证或 Mock 契约 | 待执行 |

当前允许进入实施的范围，已扩展到 `Phase E` 完成后的 `Phase F` 垂直 provider 专题；`Wave 6` 仍需单独实施计划与验收口径，不能把它隐式回填成前五个波次的既成事实。

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

---

## Full-Scale Follow-Up Roadmap After Wave 6

本节不是对前文 `First Iteration Cut / Out Of Scope` 的回滚，也不是把当前 `Wave 0-6` 改写成“尚未完成”。

本节的定位是：

1. 承接 `Wave 6` 之后，给出符合当前工程设计的完整后续路线图。
2. 解决“搜索调度已经有骨架，但采集执行体系仍然过于单一”的中长期问题。
3. 明确未来如果继续扩展到网页、API、订阅监控三类采集，不应该靠不断膨胀 `PlaywrightPageCollector` 或让 `CollectorAgent` 自己承担所有差异化执行。
4. 保持现有 `TaskDefinition -> ExecutionPlanDefinition -> WorkflowPlan`、`CollectorAgent`、`SearchPolicyResolver`、`Source Family Catalog`、`searchAudit` 等正式边界不被推翻。

这意味着后续演进的正确方向不是“再拆出一堆平级 Agent”，而是：

1. 任务层继续保留 `CollectorAgent` 作为搜索与采集阶段的唯一工作流节点 owner。
2. 搜索发现继续由 `SearchExecutionCoordinator` 及其协作者负责。
3. 采集执行进入正式的“执行协调器 + 执行器注册表 + 专项执行器”体系。
4. `PlaywrightPageCollector` 从“唯一采集实现”退回到“网页采集执行器”之一。
5. `API` 与 `订阅监控` 进入同一采集子域，但运行模型与恢复语义不再被错误地压扁成一次性网页抓取。

### Why This Roadmap Is Needed

如果只完成 `Wave 6`，系统能够做到“至少一个真实垂直 provider 进入正式路由”，但仍然存在三个结构性缺口：

1. `SearchExecutionCoordinator` 能把 URL 选出来，不等于系统已经具备按不同信源类型稳定采集证据的能力。
2. `SourceCollector -> PlaywrightPageCollector` 仍然默认“所有来源最后都尽量走网页抓取”，这会把 API 型、结构化型、订阅型来源都错误降解为页面抓取问题。
3. 下游 `提取结构化 / 分析推理 / 报告写作 / 质量审查` 真正需要的不是“抓到一个页面”，而是“拿到可追溯、可解释、可评分的证据包”。

因此，`Wave 6` 不是终点，而是“主辅发现路由闭环”的完成点；其后必须继续完成“采集执行体系闭环”，否则搜索与采集链路仍然会卡在“搜得到，但拿不到；拿到了，但拿不准；拿准了，但拿不成可用证据”的阶段。

### Target Architecture

后续目标架构仍然遵守当前工程的执行分层，不另造第二套总线：

1. `TaskDefinition` 继续表达任务意图、竞品范围、来源范围、质量要求。
2. `ExecutionPlanDefinition` 继续表达 `COLLECTOR` 阶段目标、阶段语义和节点顺序。
3. `WorkflowPlan` 继续作为运行时唯一计划快照，`rerun / resume` 不得绕开它重新猜测搜索或采集语义。
4. `CollectorAgent` 继续作为工作流节点的编排入口，对上游任务执行引擎暴露一个稳定节点，对下游协作者分派“搜索发现 -> 采集执行 -> 结果汇总”。
5. `SearchExecutionCoordinator` 继续负责 query、provider 路由、候选验证、候选排序、最终选源与 `searchAudit`。
6. 新增 `CollectionExecutionCoordinator` 负责把 `selectedTargets` 解释成正式采集任务包、路由到合适执行器、汇总执行结果、形成采集审计和恢复检查点。
7. `SourceCollector` 从单一抓取入口演进为采集编排 facade，其内部不再直接假设所有 target 都由 `PlaywrightPageCollector` 解决。
8. `CollectionExecutorRegistry` 负责按数据源家族、资源定位类型、执行模式选择专项执行器。
9. `PlaywrightPageCollector` 归位为 `WebPageCollectionExecutor`。
10. `API Data Executor`、`RSS / Feed Executor`、未来的 `Authenticated Connector Executor` 与网页执行器并列存在，但共享统一的任务包、结果包、审计包和恢复语义。

### Architecture Guardrails

后续路线图必须遵守以下 guardrails：

1. 不新增第二个工作流层级的 `SearchAgent` / `CollectionAgent` 节点，不把当前一个 `COLLECTOR` 节点拆成两个 DAG 节点作为默认形态。
2. 不把网页、API、RSS 的差异继续塞进 `CollectorNodeConfig` 的零散布尔字段里，而是进入正式采集任务包与执行器协议。
3. 不让 `PlaywrightPageCollector` 继续兼任“反爬处理器、正文提取器、表格结构化器、证据持久化协调器、订阅增量摄取器”。
4. 不让 API 型来源再次被错误包装成“先拼 URL，再走页面抓取”的伪网页模式。
5. `sourceUrls` 仍然是全链路红线；对于 API、RSS、Repo、Feed 等非单页面资源，必须同时保留 `resourceLocator` 与可展示的 `sourceUrls`，而不是借口“不是网页”就跳过溯源。
6. `searchAudit` 不得与未来的 `collectionAudit` 混成一个无边界大对象；两者必须主从明确、可独立 replay、可独立定位问题。
7. `订阅监控` 不得伪装成同步一次性执行器。它可以共享采集子域契约，但必须保留自己的增量状态模型。

---

## Collection Execution Problem Hierarchy

`Wave 0-6` 主要解决的是“搜索真相、搜索质量、搜索审计、搜索主辅路由”。

`Wave 7+` 开始，主矛盾转移为采集执行本身。其阻塞层级建议重排如下：

| 阻塞级别 | 主矛盾 | 代表表现 | 若不先修会发生什么 |
| --- | --- | --- | --- |
| `C0` | 采集任务与执行器之间没有正式契约 | `selectedTargets` 直接喂给单一 collector，执行器自己猜目标、字段、渲染策略 | 后续一加 API / RSS / 登录态页面，就会继续把差异散落到 if/else |
| `C0` | 网页采集链路拿不到稳定页面内容 | 反爬页、空壳页、骨架屏、`__adopt__` / iframe / shadow root、超时和 challenge 页误采集 | 搜索质量再高也无法形成真实证据 |
| `C1` | 即使拿到页面，也拿不到正确正文与结构块 | 价格表、功能对比表、文档目录、发布说明、FAQ、JSON-LD 被遗漏或截断 | 下游提取只能消费残缺文本，最终报告证据稀薄 |
| `C1` | API / 结构化来源没有进入统一采集执行模型 | GitHub、News、工商、应用市场等来源只能曲线走网页抓取 | 垂直 provider 价值被浪费，主辅路由闭环无法真正兑现 |
| `C1` | 采集现场没有正式事实源 | 只有搜索 audit，没有采集审计、采集质量分、执行器决策、局部重跑语义 | 发生空内容、错内容时，系统只能“知道失败”，不能“知道为什么失败” |
| `C2` | 订阅监控缺少正式增量摄取模型 | RSS / Feed / 公众号等只能作为临时补源，而不是长期增量来源 | 搜索与采集永远停留在一次性任务心智，无法形成持续资料池 |
| `C2` | 采集结果和下游证据对象之间边界不稳定 | 一会儿是页面正文，一会儿是 ExtractResult，一会儿是 evidence fragment | 采集质量无法直接传导到提取和报告质量门禁 |

这决定了后续顺序：

1. 先正式化采集任务包与执行器边界。
2. 再强化网页采集执行器，把“拿到内容”与“拿准内容”分开止血。
3. 再把 API / RSS 等专项执行器并入同一执行体系。
4. 最后把采集审计、恢复、质量门禁与下游证据闭环打通。

---

## Runtime Execution Model

### 1. End-To-End Flow

后续搜索与采集完整运行模型建议稳定为以下 8 个阶段：

1. `TaskDefinition` 生成 `WorkflowPlan`，保留 collector 节点的业务目标与来源范围。
2. `CollectorAgent` 调用 `SearchExecutionCoordinator` 完成搜索发现与正式选源。
3. `SearchExecutionCoordinator` 产出 `selectedTargets + searchAudit + recoveryCheckpoint`。
4. `CollectionExecutionCoordinator` 把 `selectedTargets` 转换为正式 `CollectionTaskPackage` 列表。
5. `CollectionExecutorRegistry` 为每个任务包分配执行器，例如网页、API、Feed。
6. 各执行器完成资源获取、正文提取、结构块提取、质量评分、失败分类与局部重试。
7. `CollectionExecutionCoordinator` 汇总 `CollectionResultBundle`，形成 `collectionAudit`、恢复检查点与对下游可消费的证据包。
8. `CollectorAgent` 返回统一 collector output，继续满足现有工作流与事件体系。

### 2. Responsibility Split

责任边界建议固定如下：

1. `SearchExecutionCoordinator` 只负责发现与选择，不负责正式证据提取。
2. `CollectionExecutionCoordinator` 只负责采集执行编排、执行器选择、采集结果汇总，不负责搜索 query、候选验证或候选排序。
3. `WebPageCollectionExecutor` 只负责网页资源的获取与网页证据提取，不负责 API 资源读取或订阅状态维护。
4. `ApiDataCollectionExecutor` 只负责结构化接口资源的请求、映射、审计与质量评分，不负责浏览器反爬。
5. `SubscriptionMonitor` 只负责增量发现与投递采集任务，不直接混入同步 collector 节点的网页抓取流程。

### 3. Why One CollectorAgent Still Holds

这个模型下，`CollectorAgent` 仍然成立，而且是更合理的：

1. 对工作流而言，搜索与采集仍然属于同一业务阶段，不必强拆成两个 runtime node。
2. 对执行子域而言，内部协作者已经正式分层，不再是一个大类包打天下。
3. 对恢复与回放而言，仍然可以在一个节点内区分 `searchCheckpoint` 与 `collectionCheckpoint`，不必靠多节点拼接现场。

---

## Formal Contracts To Be Added

这部分不是“现在立刻全部实现”，而是后续工程边界必须遵守的正式契约目标。

### 1. CollectionTaskPackage

`CollectionTaskPackage` 是 `searchAudit.selectedTargets` 到专项执行器之间的硬交接协议，但 `Wave 7` 不再一次性把所有猜测字段强塞进去。

`Wave 7` 首轮只强制落最小核心字段：

1. `taskId`
2. `nodeName`
3. `planVersionId`
4. `competitorName`
5. `sourceFamilyKey`
6. `sourceType`
7. `primaryTool`
8. `url`
9. `resourceLocator`
10. `targetFields`
11. `priority`
12. `sourceUrls`

之所以没有进一步缩到 10 个，是因为：

1. `sourceType` 在当前工程里仍然是网页采集规则、质量判断和下游显示的重要稳定语义，不能过早删掉。
2. `sourceUrls` 是现有工程的硬追溯红线，不能等到后续波次再补。

以下字段不再作为 `Wave 7` 强制字段，而是由 `Wave 8` 网页失败模式和执行器真实需求反向收口后，再作为可选扩展字段补入：

1. `canonicalUrl`
2. `renderHints`
3. `antiBotPolicy`
4. `retryPolicy`
5. `contentScopes`
6. `evidenceExpectation`
7. `timeoutBudget`
8. `searchAuditRef`

其中有两个约束仍然必须提前定死：

1. `url` 仍然保留，因为网页型资源需要它，也便于 UI 和审计展示。
2. `resourceLocator` 必须独立存在，因为 API / Repo / Feed / 组织主页等资源并不总能被单个 URL 完整表达。

### 2. CollectionExecutionResult

每个执行器返回的结果必须统一至少包含：

1. `packageId`
2. `executorType`
3. `executionStatus`
4. `failureKind`
5. `retryable`
6. `httpStatusOrProviderCode`
7. `pageTitleOrResourceName`
8. `rawContentSummary`
9. `structuredBlocks`
10. `qualitySignals`
11. `qualityScore`
12. `artifacts`
13. `sourceUrls`
14. `collectedAt`
15. `durationMillis`

### 3. CollectionAuditSnapshot

采集阶段必须拥有自己的正式事实源，至少记录：

1. 采集任务包列表及其执行顺序。
2. 每个任务包被分配给哪个执行器。
3. 是否触发了反爬信号、重试、降级、回退。
4. 正文提取策略命中情况。
5. 表格 / JSON-LD / 文档目录 / 价格区块等结构化块命中情况。
6. 最终成功、失败、部分成功数量。
7. `sourceUrls`、artifact 摘要、质量分与失败原因。

### 4. CollectionCheckpoint

采集恢复检查点至少需要稳定到以下阶段：

1. `PACKAGE_BUILT`
2. `RESOURCE_FETCHED`
3. `CONTENT_EXTRACTED`
4. `STRUCTURED_BLOCKS_EXTRACTED`
5. `EVIDENCE_PERSISTED`
6. `INDEX_UPDATED`

这使得 rerun / resume 具备明确语义：

1. `rerun search` 会废弃旧 `CollectionTaskPackage` 与旧采集结果。
2. `rerun collection` 复用同一 `selectedTargets` 与 `searchAudit`，只重跑采集执行。
3. `resume collector` 默认从最后成功的 `collectionCheckpoint` 继续，而不是重新跑完整搜索。

---

## Executor Topology

### 1. Executor Families

后续采集执行器建议按资源获取方式和状态模型拆分，而不是按“这个类是不是 Agent”拆分：

| 执行器 | 主要资源类型 | 典型来源家族 | 同步 / 异步 | 当前优先级 |
| --- | --- | --- | --- | --- |
| `WebPageCollectionExecutor` | 官网、文档、媒体、帮助中心、定价页 | `official / news / docs / review` | 同步 | `P0` |
| `ApiDataCollectionExecutor` | GitHub、News API、工商、应用市场等结构化接口 | `github / news / finance / enterprise` | 同步 | `P1` |
| `FeedCollectionExecutor` | RSS / Atom / 简单订阅源 | `news / blog / community` | 同步或准异步 | `P2` |
| `SubscriptionMonitor` | 长期监控型增量源 | `news / social / blog / feed` | 异步常驻 | `P2` |

`WebPageCollectionExecutor` 在当前工程里不应再被等同于“Playwright 单实现”。  
后续推荐把它稳定成双路径网页执行器：

1. `JinaReader` 作为公开可访问页面的主路径，优先处理官方文档页、产品介绍页、定价页、公开资讯页、普通博客和 PDF 等轻量正文采集场景。
2. `Playwright` 作为重型兜底路径，只处理 `JinaReader` 无法稳定覆盖的页面，例如登录态页面、强动态页面、需要交互展开的页面、明确存在 challenge 或强反爬的页面。
3. 这意味着 `PlaywrightPageCollector` 的优化目标不再是“重新成为所有网页的默认主力”，而是成为 `FULL_RENDER` 路径下可解释、可恢复、可审计的重型执行器。

### 2. Source Family To Executor Matrix

`Source Family Catalog` 后续不能只负责“发现阶段的模板与工具”；它还要能解释采集执行偏好：

| 数据源家族 | 发现主工具 | 采集主执行器 | 采集兜底执行器 | 预期证据形态 |
| --- | --- | --- | --- | --- |
| 官方网站 | `WEB_SCRAPER / JINA / PUBLIC_SEARCH` | `WebPageCollectionExecutor` | `FeedCollectionExecutor` 仅限站点 feed | 正文、价格块、功能表、文档目录、更新时间 |
| 新闻媒体 | `NEWS_API / RSS / PUBLIC_SEARCH` | `ApiDataCollectionExecutor` 或 `WebPageCollectionExecutor` | `FeedCollectionExecutor` | 标题、发布日期、事件类型、正文、出处 |
| GitHub | `GITHUB_API / PUBLIC_SEARCH` | `ApiDataCollectionExecutor` | `WebPageCollectionExecutor` 仅作 release / repo 页兜底 | 仓库、release、star、commit、组织归属 |
| 技术博客 | `BLOG_CRAWLER / RSS / PUBLIC_SEARCH` | `WebPageCollectionExecutor` | `FeedCollectionExecutor` | 正文、发布时间、作者、技术主题 |
| 社交媒体 | `API / PUBLIC_SEARCH` | `SubscriptionMonitor` | `WebPageCollectionExecutor` 仅限公开页面 | 帖文、时间、互动量、情绪信号 |

对网页型来源，还应额外声明默认执行策略：

1. `official / docs / review / 普通 news article` 若页面公开可访问、无登录态、无强交互要求，默认优先走 `JinaReader`。
2. 只有当页面被标记为 `FULL_RENDER`、`LOGIN_REQUIRED`、`INTERACTION_REQUIRED`、`ANTI_BOT_RISK_HIGH` 或 `JinaReader` 返回内容质量不足时，才升级到 `Playwright`。
3. `site crawl` 不作为与上述执行器平级的第四类核心执行器，而应后置为站内批量编排模式，复用 `JinaReader / Playwright` 两条已有网页采集路径。

---

## Web Page Collection Hardening Program

网页采集仍然是最先要打穿的主战场，因此 `PlaywrightPageCollector` 的后续演进不能只靠补几个 selector。

### 1. Page Readiness Model

网页执行器必须从“单次 `goto + content()`”升级为多阶段页面就绪模型：

1. 导航完成判定：区分 `domcontentloaded`、`load`、`network idle`。
2. 内容稳定判定：监测主要容器是否已出现、骨架屏是否消失、关键文本是否加载。
3. 交互展开判定：必要时自动点击 `Accept Cookies`、`Read More`、`Expand`、价格切换页签。
4. 资源边界判定：识别 iframe、shadow root、延迟加载区块、需要滚动触发的正文。

### 2. Anti-Bot And Challenge Handling

网页执行器必须把反爬当成正式执行结果，而不是异常噪声：

1. 识别 challenge / captcha / access denied / waiting room / JS challenge 等典型信号。
2. 把“被反爬拦截”与“页面正常但内容为空”分开记为不同 `failureKind`。
3. 引入正式重试策略、等待策略、浏览器上下文重建策略。
4. 把触发反爬的 URL、重试次数、命中信号、最终决策写入 `collectionAudit`。
5. 对已确认高反爬来源提供显式降级路径，例如回退到 `Jina Reader`、站点 feed、搜索摘要或人工接管提示。

### 3. Main Content Extraction Chain

网页正文提取不能只依赖单一 DOM 裁切逻辑，而应建立分层提取链：

1. 原始 DOM 抽取。
2. 可读性正文抽取。
3. 结构区域抽取，例如 `article / main / section / table / dl / faq`。
4. 结构化元数据抽取，例如 `JSON-LD / OpenGraph / meta publish time`。
5. 面向定价 / 文档 / release note 的专项 block extractor。
6. 在以上策略全部不足时，才允许进入轻量 LLM rescue，并明确标记为降级结果。

### 4. Structured Block Extraction

网页执行器必须对高价值结构块建立正式抽取器，而不是希望下游 extractor 再猜：

1. `PricingBlockExtractor`
2. `FeatureComparisonTableExtractor`
3. `DocumentationOutlineExtractor`
4. `ReleaseNotesExtractor`
5. `FaqBlockExtractor`
6. `JsonLdMetadataExtractor`

### 5. Quality Scoring

每次网页采集必须生成正式质量信号，至少包括：

1. 内容长度。
2. 标题命中。
3. 正文密度。
4. 结构块命中数。
5. 时间字段命中。
6. 营销噪声比例。
7. 反爬风险等级。
8. 是否达到 `evidenceExpectation`。

### 6. Artifact Retention

网页执行器产物建议至少保留：

1. 标准化 URL 与页面标题。
2. 原始 HTML 摘要或 hash。
3. 结构化正文文本。
4. 命中的结构化区块。
5. 可选截图或 DOM 片段摘要。
6. `sourceUrls` 与采集时间。

---

## API And Structured Data Collection Program

网页不是所有采集问题的中心，结构化接口必须进入正式采集子域。

### 0. Discovery Owner vs Collection Owner

同一外部 API 可以在两个层面出现，但 owner 不能混层：

1. 搜索发现层 provider 负责返回候选 URL、候选资源或稳定 `resource locator`。
2. 采集执行层 executor 负责返回结构化字段、证据块、质量分和审计结果。
3. `Wave 6` 解决 discovery owner。
4. `Wave 10` 解决 collection owner。

对于 `GitHub API`，这条边界必须强制成立：  
`Wave 6` 的 GitHub provider 允许做发现，不允许把 API 响应伪装成网页采集完成；`Wave 10` 的 `ApiDataCollectionExecutor` 才是结构化采集 owner。

### 1. API Executor Responsibilities

`ApiDataCollectionExecutor` 需要覆盖：

1. 凭证读取与可用性校验。
2. 限流与重试。
3. 结构化响应映射。
4. 字段级 `sourceUrls` / `resourceLocator` 保留。
5. provider 特定失败分类。
6. 统一质量评分与审计投影。

### 2. First-Class API Families

在现有工程语义下，API 类来源的首批正式对象建议为：

1. `GitHub API`
2. `News API`
3. `RSS / Feed` 读取器

原因不是它们最容易，而是它们和当前 `Source Family Catalog` 的 `github / news` 最直接对齐，能够最早把“垂直发现路由”兑现为“垂直采集执行”。

### 3. Web Fallback Still Exists

API 执行器进入后，并不意味着网页兜底消失：

1. GitHub release 页、组织主页、README 展示页仍可能走网页执行器补证。
2. News API 只给摘要或字段时，仍可能回到新闻正文网页补全文本。
3. 关键不是取消网页，而是避免默认让网页承担全部职责。

---

## Subscription And Incremental Monitoring Program

订阅监控应被视为采集子域中的“长期增量能力”，而不是 collector 节点内部的一次性抓取分支。

### 1. Positioning

`SubscriptionMonitor` 的目标不是替代搜索，而是：

1. 为后续同类任务沉淀持续资料。
2. 为热点来源提供定时或实时的更新触发。
3. 让 `news / blog / community / social` 不必每次都从头搜索。

### 2. Boundaries

它应与同步 collector 流程共享：

1. `Source Family Catalog`
2. `CollectionTaskPackage`
3. `CollectionExecutionResult`
4. `sourceUrls`
5. 统一审计与质量信号

但它必须保留独立模型：

1. 订阅状态
2. 增量 cursor
3. 上次拉取时间
4. 去重窗口
5. 新增证据投递策略

### 3. Recommendation

这个能力建议在 `Wave 11` 之后单独专题化，不回塞到当前 collector 的同步主链路里。

---

## Audit, Replay, Recovery, And Quality Gates

### 1. Dual Audit Model

后续 collector 节点必须稳定输出“双审计模型”：

1. `searchAudit` 解释“怎么找到这些目标”。
2. `collectionAudit` 解释“怎么拿到这些内容，以及为什么没拿到”。

### 2. Replay Semantics

回放模型建议细分为：

1. `searchReplayTimeline`
2. `collectionReplayTimeline`
3. `collectorSummaryTimeline`

这样前端与恢复链路才能区分：

1. 搜索已经成功，但采集失败。
2. 搜索与采集都成功，但结构块不足。
3. API 成功，但网页补证失败。

### 3. Recovery Semantics

恢复语义必须从“整包 output 回灌”继续向显式检查点迁移：

1. 搜索恢复以 `searchCheckpoint` 为准。
2. 采集恢复以 `collectionCheckpoint` 为准。
3. 单个采集任务包失败时，允许包级重跑，不强迫整个 collector 节点全量重跑。
4. 若重新执行搜索导致 `selectedTargets` 变化，则旧采集结果必须显式失效，而不是静默复用。

### 4. Quality Gate

后续 collector 完成条件不能只看“采集成功条数”，还应至少引入：

1. `contentAvailableRate`
2. `mainBodyQualifiedRate`
3. `structuredBlockHitRate`
4. `sourceFamilyCoverage`
5. `evidenceExpectationHitRate`
6. `antiBotFailureRate`

这些指标未来应成为 `质量审查` 与 `修订重写` 的输入，而不是只留在 collector 内部自说自话。

---

## Extended Waves

以下波次是在现有 `Wave 0-6` 之后的完整路线图。它们不要求一次性全部进入直接实施，但后续若继续推进搜索与采集，建议按这个顺序收口。

### Wave 7: Collection Seam And Minimal Contract Formalization

目标：先把采集执行缝、最小任务包和协调器骨架正式化，而不是预判一整套可能没人真正使用的执行策略字段。

第四轮联合实施已完成本波次的最小闭环：`CollectionTaskPackage`、`CollectionExecutionCoordinator`、`CollectionExecutorRegistry`、`CollectionExecutionResult` 已落地，`CollectorAgent` 已从“直接调用 `SourceCollector.collect(...)`”演进为“搜索发现 -> 任务包构建 -> 执行器路由 -> 兼容映射”的最小主链路，并保留了网页预抓页面复用契约。

必须覆盖：

1. `CollectionTaskPackage` 最小核心字段
2. `CollectionExecutionCoordinator`
3. `CollectionExecutorRegistry`
4. `CollectionExecutionResult` 最小骨架
5. `collectionCheckpoint` 最小骨架
6. `collectionAudit` 最小正式 DTO

明确不要求一次完成：

1. 所有专项执行器全量落地。
2. `renderHints / antiBotPolicy / retryPolicy` 等扩展字段全集。
3. 订阅监控产品化。
4. 前端全量采集详情页切换。

### Wave 8: Web Page Collection Hardening

目标：把网页采集从“Playwright 单路径”升级为“JinaReader 主路径 + Playwright 重型兜底路径”，并把网页执行器暴露出的真实失败模式反向收口成正式字段与状态分类。

第五轮联合实施已完成本波次的实现收口：`CollectionTaskPackage / CollectionExecutionResult` 已补齐 `renderHint / failureKind / qualitySignals / qualityScore / structuredBlocks / collectedAt / durationMillis`；`SearchSourceCatalogProperties` 与 `SearchPolicyResolver` 已能表达 source family 级网页采集偏好；`WebPageCollectionExecutor` 已切换为 `JinaReader` 主路径 + `Playwright FULL_RENDER` 兜底；`PageContentExtractionSupport` 已正式返回分层提取结果；`CollectorAgent` 兼容映射已保留 `sourceUrls` 与新增采集元数据。自动化验证方面，第五轮聚合命令与 `mvn -pl backend test` 已于 2026-06-16 通过。本波次仍未宣称真实业务质量闭环升绿，后续还需结合任务实链继续验证采集证据质量与最终质检表现。

必须覆盖：

1. 正式引入 `JinaReader` 路径，作为公开文档页、产品页、定价页、公开资讯页的优先网页采集方案。
2. 明确 `renderHints` 或等价执行提示的首轮枚举语义，至少覆盖 `LIGHTWEIGHT` 与 `FULL_RENDER`，用于把 `JinaReader` 与 `Playwright` 路由分开。
3. `PlaywrightPageCollector` 只承接 `FULL_RENDER` 兜底场景，并补齐页面就绪判定模型。
4. 反爬识别、重试与降级。
5. DOM / iframe / shadow root / `__adopt__` 等异常现场治理。
6. 分层正文提取链。
7. 定价、文档、release note 等结构块抽取器。
8. 网页采集质量评分。
9. 为 `failureKind`、扩展 `CollectionTaskPackage` 字段和执行器策略对象沉淀正式失败模式词表。

本波次有一条明确取舍：

1. 不把 `Playwright` 再设计成公开文档页的默认主路径。
2. 不把 `SiteCrawl` 提前抬成与 `JinaReader / Playwright / API` 平级的第四类核心执行器。
3. 先用 `JinaReader` 解决“URL 正确但拿不到正文”的主矛盾，再让 `Playwright` 专注兜底场景。

### Wave 9: Collection Audit / Replay / Recovery Closure

目标：让采集段和搜索段一样具备可解释性与局部重跑能力。

必须覆盖：

1. `collectionAudit` 正式化。
2. `collectionReplayTimeline` 正式化。
3. 包级重试 / 重跑语义。
4. 采集段 `WAITING_INTERVENTION`、`DEGRADED`、`PARTIAL_SUCCESS` 的正式状态。
5. runtime / insight / event / replay 对齐。

### Wave 10: API Collection Convergence

目标：让至少两类结构化来源进入统一采集执行体系，优先兑现 `github / news`。

第四轮联合实施已提前完成本波次的首个最小落点：`ApiDataCollectionExecutor` 与 `GithubApiCollectionExecutor` 已接入统一采集执行体系，并通过 `github://repo/{owner}/{repo}` locator 直接返回结构化证据；但 `news` 家族的 API / feed 执行器、API-Web 补证策略与更完整的实链验收仍留待后续波次。

必须覆盖：

1. `ApiDataCollectionExecutor`
2. `GitHub API` 正式采集执行闭环
3. `News API` 或 `RSS` 正式采集执行闭环
4. API 结果到统一 evidence bundle 的映射
5. API / Web 互补补证路径

### Wave 11: Feed And Subscription Monitoring

目标：让增量来源从“一次性任务辅助来源”升级为“长期资料摄取能力”。

必须覆盖：

1. `FeedCollectionExecutor`
2. `SubscriptionMonitor`
3. cursor / 去重窗口 / 增量回放语义
4. 与 collector 主流程共享 evidence / audit 契约

### Wave 12: Downstream Evidence Closure

目标：让采集质量正式传导到 `提取结构化 / 分析推理 / 报告写作 / 质量审查`。

必须覆盖：

1. collector 输出的 evidence bundle 与下游正式契约对齐。
2. 下游能够感知采集质量信号，而不只看到裸正文。
3. 最终质量门禁能够区分“搜索问题、采集问题、提取问题、分析问题、写作问题”。

这部分与 [ExtractionStructured.md](/E:/java_study/Mul-agnet/docs/problem/ExtractionStructured.md) 直接相连，应在对应链路方案启动后联合推进，不建议由搜索与采集专题单边硬推。

---

## Extended Progress Plan

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase G | 完成 `Wave 7` 最小采集契约、执行协调器、执行器注册表与采集检查点骨架 | 1 个迭代 | `Wave 6` 至少完成一个真实垂直发现 provider | 已完成最小闭环 |
| Phase H | 完成 `Wave 8` 双路径网页采集执行器落地：`JinaReader` 主路径、`Playwright` 兜底路径，以及失败模式收口与契约扩展字段补齐 | 1-2 个迭代 | Phase G 启动后可并行推进 | 已完成实现与自动化收口，实链验收待后续补跑 |
| Phase I | 完成 `Wave 9` 采集审计、采集回放、包级重跑与恢复语义 | 1 个迭代 | Phase G-H 完成 | 待执行 |
| Phase J | 完成 `Wave 10` API 型采集执行器与 `github / news` 统一 evidence 闭环 | 1-2 个迭代 | Phase G 完成，且具备目标 provider 凭证或 Mock 契约 | 已启动，GitHub 首个闭环完成 |
| Phase K | 完成 `Wave 11` feed / subscription 增量监控体系 | 1 个迭代以上 | Phase G、Phase J 至少一项完成 | 待执行 |
| Phase L | 完成 `Wave 12` 采集结果到下游提取 / 报告质量门禁的正式联动 | 跨专题协同 | Extraction 方案正式启动 | 待执行 |

---

## Extended Acceptance

本节用于界定“后续完整路线图”的完成口径，不改变前文对 `Wave 0-6` 的验收结论。

### Wave 7-9 Collector Execution Closure

只有同时满足以下条件，才能认为“采集执行体系”已经从单实现升级为正式子域：

1. `CollectorAgent` 仍保持单节点编排入口，但内部已存在正式 `CollectionExecutionCoordinator`。
2. `selectedTargets` 不再直接喂给单一 collector，而是先转成正式 `CollectionTaskPackage`。
3. 网页采集、API 采集至少已由不同执行器承接，且共享统一结果协议。
4. `collectionAudit`、`collectionReplayTimeline`、`collectionCheckpoint` 已进入 runtime / insight / replay / event。
5. 包级重试、包级重跑、collector 级恢复语义已稳定。
6. 公开网页采集默认不再只有浏览器单路径，`JinaReader` 已承担公开页面主路径，`Playwright` 已退回重型兜底路径。

### Wave 10-11 Specialized Collection Closure

只有同时满足以下条件，才能认为“专项采集执行器体系”已经成立：

1. 至少一个网页执行器与一个 API 执行器在真实链路中同时工作。
2. `github / news / official` 三类家族在发现与采集两个层面都已拥有正式主工具。
3. 网页补证、API 主采集、Feed 增量采集三者的责任边界可被审计说明。
4. `sourceUrls`、`resourceLocator`、质量分、失败原因均可回放。

### Full Search-And-Collection Maturity

只有同时满足以下条件，搜索与采集链路才允许从“搜索闭环 + 初级采集”升级为“完整信息获取引擎”：

1. `Wave 6` 垂直 provider 已完成并进入真实路由。
2. `Wave 7-9` 采集执行体系已完成正式化。
3. `Wave 10-11` 专项采集执行器已至少覆盖 `web + api + feed` 三种模式中的两种以上。
4. 最终任务质量门禁能够把失败根因区分到搜索、采集、提取、分析、写作中的至少一类，而不是统一归因为“结果不好”。

---

## Relationship To Current Out Of Scope

为避免误读，本节最后再强调一次：

1. 前文 `First Iteration Cut` 与 `Out Of Scope` 仍然只约束当前 blocking 实施阶段。
2. 本节描述的是 `Wave 6` 之后的完整工程蓝图，不代表这些内容现在就应全部并入当前实施范围。
3. 后续若启动 `Wave 7+`，应基于本节另写分阶段实施计划，而不是直接把本节当作一次性开发清单。
