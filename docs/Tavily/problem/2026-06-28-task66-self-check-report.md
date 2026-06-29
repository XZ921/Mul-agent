# Task 66 全链路自检报告（工程核验版）

> 2026-06-28。本文以 task 66 为样本，结合当前工程代码、Tavily 相关规格/进度文档，以及 superpowers 目录下已沉淀的问题文档，重新判断“一次竞品分析任务想跑成功”还会遇到哪些真实风险。
>
> 结论先行：task 66 的根因不是“完全没搜到”，而是“任务目标、搜索命中、证据结构、下游固定强检”四层错位。原报告方向正确，但部分断言已经被当前代码修复或需要收紧，不能继续按旧口径排 P0。

---

## 0. 工程核验结论

| 判断 | 结论 | 工程证据 |
|---|---|---|
| 确认属实 | task 66 的证据内容偏官方能力入口页，能支撑 summary / positioning / targetUsers / coreFeatures / strengths，但不能稳定支撑 pricing / weaknesses / risk。 | `SchemaExtractorAgent` 和 `QualityReviewAgent` 仍固定检查 `summary/positioning/targetUsers/coreFeatures/pricing/strengths/weaknesses`；当前工程没有 `CoverageContract` 实现。 |
| 确认属实 | Tavily 预抓正文即使进入 collection，仍会落成 `structuredBlocks=[]`，下游只能看到粗正文或 sourceUrls，无法获得细粒度结构证据。 | `TavilyPrefetchedExecutor` 成功路径写入 `TAVILY_RAW_CONTENT_READY`、`TAVILY_PREFETCHED_CONTENT_CONSUMED`，但固定 `.structuredBlocks(List.of())`。 |
| 确认属实 | 中文验证码/鉴权壳识别不足，`智能验证检测中`、`请输入验证码` 等中文信号容易漏检。 | `SearchBrowserProperties.blockedSignals` 默认只有英文关键词：`captcha/unusual traffic/verify you are human/access denied/robot check/security check`。 |
| 确认属实 | 搜索采集加固计划仍未落地，公开替代入口恢复、壳页恢复、证据落库安全化等能力缺失。 | `PublicEvidenceRecoveryService`、`PublicShellRecoveryExtractor`、`EvidenceSourceSanitizer`、`V28__expand_evidence_source_discovery_reason.sql` 在当前代码中不存在，只出现在计划文档。 |
| 部分属实，需要收紧 | `qualityScore=0.92` 不是当前 Direct HTML 按长度必然算出来的分。Direct HTML 的长度加链接路径通常是 0.88，上限才是 0.92；task66 看到的 0.92 更可能来自规划期 direct candidate 的硬编码候选分。 | `DirectHtmlReaderClient.calculateQualityScore()` 长度 >= 800 时为 0.84，链接加成后 0.88；`SourceFamilyDirectDiscoveryPlanner.buildCandidate()` 固定 `.relevanceScore(0.95D)`、`.qualityScore(0.92D)`。 |
| 部分属实，需要收紧 | Tavily raw_content 不是“一定丢在 Registry，没有进入证据链”。当前已有 `TAVILY_PREFETCHED` 路由和执行器；真正要核验的是 task66 具体 execution audit 是否经过该路由，以及为什么最终仍是无结构证据。 | `CollectionTaskPackageBuilder.resolvePrimaryTool()` 会在 `fastLaneUsable + hasPrefetchedContent + prefetchedContentRef` 成立时返回 `TAVILY_PREFETCHED`；`TavilyPrefetchedExecutor` 会消费 registry。 |
| 已过期/当前代码不成立 | `OFFICIAL` 候选无条件 `verified=true` 已不再是当前主路径事实。 | `CandidateVerifier.isVerified()` 现在检查可用正文、ownership、mediator、marketing、matchedSignals；但 Tavily fast-lane 仍存在 `.verified(true)` 的跳过网络重验路径，需要加正文可用性/结构可用性门禁。 |
| 已过期/当前代码不成立 | “Tavily 仍在 Phase 3，Phase 1 bootstrap 没接上”已不成立。 | Tavily Phase 1 progress 文档记录 task66 四个 collector 均出现 `TAVILY_BOOTSTRAP_ENRICH`，且 collection 已支持优先消费 `TAVILY_PREFETCHED`。 |
| 已过期/当前代码不成立 | `ExtractorInputProvider` 未就位、Extractor 默认 DOMAIN 记忆污染、Writer citation gap 不持久化、OrchestrationDecision 不可见，这些已被当前代码部分或完全覆盖。 | `ExtractorInputProvider`/`RepositoryExtractorInputProvider` 已存在；`SchemaExtractorAgent` 写 `memoryLayer("TASK")`；`Report`/`ReportResponse`/`ReportExportRenderer` 已包含 writer evidence 与 orchestration decision。 |

---

## 1. task 66 暴露出的真实根因

task 66 的任务定义天然偏“能力介绍”：`subject_product=短视频平台开放生态与开发者能力`，`analysis_dimensions=["开放平台","开发者生态","产品功能"]`，`source_scope=["官网","产品文档"]`。这类任务本身没有把定价、短板、风险设成主采集目标。

但下游 Extractor/Analyzer/Reviewer 仍使用固定字段契约：

- Extractor 固定抽取 `summary`、`positioning`、`targetUsers`、`coreFeatures`、`pricing`、`strengths`、`weaknesses`。
- Analyzer 固定生成 `pricingComparison`、`weaknessesSummary` 等对比章节。
- Reviewer 固定检查 `pricing` 和 `weaknesses` 的 coverage。

因此，task66 不是“搜索失败”，而是：

> 任务目标和搜索命中都偏官方能力入口页，导致正向能力章节可写；但定价/短板/风险缺少细页与结构化证据，再被固定 schema + reviewer 放大成终审失败。

这也是当前最重要的系统性问题：字段是否必须有值，不能继续由固定 schema 决定，而应该由任务维度生成的 `CoverageContract` 决定。

---

## 2. task 66 直接暴露的问题（核验后）

| # | 问题 | 当前判断 | 严重度 | 修正后的说明 |
|---|---|---|---|---|
| 1 | B 站开放平台首页/根入口返回导航壳、登录/验证壳，证据正文质量弱。 | 属实 | P0 | `evidence_source.full_content` 中大量是导航、页脚、登录/注册、智能验证文本，不能支撑后续报告撰写。 |
| 2 | Direct HTML 把壳页判成高质量。 | 部分属实 | P0 | 风险属实，但原报告把 0.92 直接归因给 Direct HTML 不准确。当前更准确的问题是“规划期候选分、采集正文质量分、最终证据可用性分混用，且无交叉降权”。 |
| 3 | `qualityScore/relevanceScore/trustTier` 都偏表面信号。 | 属实 | P0 | 官方域名可信不等于本页正文可用；命中“功能/能力/方案”不等于命中目标字段证据。需要引入正文可用性、导航壳占比、blocked signal、structuredBlocks 覆盖的交叉门禁。 |
| 4 | 采集信号和评分自相矛盾未被统一处理。 | 属实 | P0 | 如果 collector 已出现 `content too thin`、blocked、shell、no structured blocks 等信号，最终 quality/trust 不应保持高分。 |
| 5 | Tavily raw_content 交接链路需要审计闭环。 | 需要收紧 | P0 | 当前已有 `TAVILY_PREFETCHED` 路由，不应再写成“永远不会进入证据链”。但 `CollectionTargetSelector` fallback target 仍可能只有 candidate，没有 collectedPage；需要用 audit 验证 task66 是否走到 `TAVILY_PREFETCHED`、是否消费了 `prefetchedContentRef`。 |
| 6 | Tavily Phase 1 bootstrap 已触发，但仍需验收命中质量。 | 已过期 | 已修/待验收 | task66 进度文档显示四个 collector 都出现 `TAVILY_BOOTSTRAP_ENRICH`。剩余问题不是 bootstrap 没跑，而是 query intent、目标选择、证据结构化和质量门禁仍不够。 |
| 7 | Playwright 无法解决鉴权墙。 | 属实 | P0 | 浏览器能渲染页面不代表能绕过登录/验证码。系统必须转向公开替代入口、Tavily raw_content、站内深链、协议页/开发者文档页，而不是反复打开同一壳页。 |
| 8 | 下游 fixed schema 与任务维度不匹配。 | 属实 | P0 | task66 任务未要求 pricing/risk 为主目标，但 reviewer 仍强检，导致“能力介绍任务”被“完整商业竞品报告标准”卡死。 |
| 9 | Tavily 预取正文缺结构块。 | 属实 | P0 | `TavilyPrefetchedExecutor` 固定 `structuredBlocks=[]`，导致 downstreamEvidenceViews 出现 `NO_STRUCTURED_BLOCKS`。这是 task66 能抽出正向字段但定价/短板覆盖失败的重要原因。 |

---

## 3. superpowers 问题清单的当前状态

### 3.1 仍然有效，且会影响“跑成功一次任务”

| 问题 | 状态 | 影响 |
|---|---|---|
| HTTP/Direct 轻量采集对 SPA/导航壳/登录壳存在假阳性。 | 仍有效 | 容易把无价值页面落成高分证据。 |
| 中文反爬、中文鉴权、中文页面质量关键词不足。 | 仍有效 | 中文站点的“智能验证/验证码/登录后查看”容易漏判。 |
| 搜索采集加固 7 Task 未落地。 | 仍有效 | 没有公开替代入口恢复、壳页恢复、证据落库安全化。 |
| `CoverageContract` 不存在。 | 仍有效 | 任务目标和下游字段强检继续错位。 |
| Tavily 预取正文无结构化切块。 | 仍有效 | 下游证据视图无法给 pricing/weaknesses 等字段提供细粒度证据。 |
| 证据可用性门禁不足。 | 仍有效 | sourceUrls 齐全、域名可信、文本够长时，垃圾内容仍可能被当成可用证据。 |
| 真实 live 成功样本不足。 | 仍有效 | 目前只能证明某些链路被触发，不能证明“稳定产出合格报告”。 |

### 3.2 已修或需要改写为“剩余风险”

| 原问题 | 当前判断 | 应改写为 |
|---|---|---|
| OFFICIAL 无条件 verified=true。 | 主路径已修，Tavily fast-lane 仍有跳过网络重验。 | “官方来源验证已加入可用性/归属/营销页检查；剩余风险是 fast-lane verified shortcut 缺正文结构可用性门禁。” |
| Tavily 只在 Phase 3 补源。 | 已过期。 | “Phase 1 bootstrap 已触发；剩余风险是 bootstrap 命中的细页是否被选中、预取正文是否被消费、是否结构化落库。” |
| Registry → CollectedPage 桥完全未接通。 | 表述过强。 | “存在 selector fallback target 无 collectedPage 的路径，但当前另有 `TAVILY_PREFETCHED` 执行器；必须以 audit 验证具体任务走哪条路。” |
| `ExtractorInputProvider` 未就位。 | 已修。 | “Extractor 输入边界已有 provider；剩余风险是 CoverageContract 未驱动 runtime。” |
| `CompetitorKnowledge` 默认 DOMAIN 记忆污染任务快照。 | 当前 task 写入已是 TASK。 | “继续关注历史数据/兼容路径，不作为当前 task66 P0。” |
| Writer citation gap 不持久化。 | 已修。 | “Writer evidence 已持久化并投影；剩余风险是多轮协作语义和动态修复是否稳定。” |
| OrchestrationDecision 在报告/导出不可见。 | 已修。 | “报告与导出已有投影；剩余风险是对话侧多轮状态解释是否充分。” |

---

## 4. 当前想跑成功一次任务，还可能遇到的断点

下面按一次任务执行顺序列出“仍可能导致失败或低质通过”的断点。

### 4.1 任务定义阶段

| 风险 | 说明 | 需要的处理 |
|---|---|---|
| 任务维度偏能力介绍，但系统强行要求完整商业分析字段。 | task66 就是典型样本。 | Planner 生成 `CoverageContract`，把字段分成 `required / optional / notApplicable / repairOnly`。 |
| 测试标准过宽。 | 一开始就测定价、风险、竞品弱点，会把系统带到最难维度。 | 当前测试阶段先跑“官网/文档/功能能力/目标用户/产品定位”类任务，再逐步攻坚 pricing/risk。 |

### 4.2 搜索与候选阶段

| 风险 | 说明 | 需要的处理 |
|---|---|---|
| query intent 与字段目标不匹配。 | task66 的模板里有 pricing query，但真实执行没有价格类 query。 | query 生成必须读取 CoverageContract；只有 required 字段才生成强检 query。 |
| 高层入口 URL 重复入选。 | `open.bilibili.com`、`/docs`、`/documentation` 可能得到同质内容。 | selectedTargets 加入 distinct content / path depth / page type diversity 约束。 |
| Tavily 命中细页但未稳定入选。 | 例如 B 站 developer-service、用户管理 API 这类页面更有价值。 | 选择阶段不能只看域名和 candidate 分，要提升深链、文档页、raw_content 完整度权重。 |

### 4.3 采集阶段

| 风险 | 说明 | 需要的处理 |
|---|---|---|
| 壳页落库。 | 登录/注册/页脚/导航/验证码文本被当成 full_content。 | 壳页检测、中文 blocked signals、导航链接密度、正文段落密度、重复内容检查。 |
| Tavily 预抓正文无结构化。 | 即使 raw_content 可用，下游仍看到 `NO_STRUCTURED_BLOCKS`。 | TavilyPrefetchedExecutor 接入 `PageContentExtractionSupport` 或独立 Markdown/正文切块器。 |
| 验证与采集重复访问同一 URL。 | 增加反爬风险，也可能得到两份不同质量页面。 | 优先复用 verified collectedPage；fast-lane 优先消费 registry；fallback 必须记录原因。 |
| 公开替代入口恢复缺失。 | 鉴权墙出现后没有主动搜同域文档/API/协议页。 | 落地 `PublicEvidenceRecoveryService`、`PublicShellRecoveryExtractor`。 |

### 4.4 提取、分析、写作、质检阶段

| 风险 | 说明 | 需要的处理 |
|---|---|---|
| Extractor 对无结构证据只能粗抽。 | 正向能力字段能抽，pricing/weaknesses 常为空。 | 字段级证据视图必须包含 block type、source offset、evidenceIds。 |
| Analyzer 被迫补上游证据缺口。 | garbage in, garbage out。 | Analyzer 只能消费已标注 coverage 的字段，不应伪补事实。 |
| Reviewer 固定强检不看任务目标。 | task66 失败点就是 pricing/weaknesses。 | Reviewer 改读 CoverageContract，`optional/notApplicable` 不应成为 blocker。 |
| Writer/Delivery 虽已有审计投影，但动态修复稳定性仍未证明。 | 当前能展示，不等于能自动修好。 | live smoke 需要覆盖“失败诊断 -> 补采 -> 重抽 -> 复审”的完整闭环。 |

---

## 5. 当前 P0 修复顺序

| 顺序 | 修复项 | 解决问题 | 验收标准 |
|---|---|---|---|
| 1 | 引入 `CoverageContract` 或先做最小字段契约。 | 任务目标与固定 schema/reviewer 错位。 | task66 这类“开放生态/开发者能力”任务中，pricing/weaknesses 可被标为 optional 或 repairOnly；终审不再因非目标字段直接 blocker。 |
| 2 | Tavily 预抓正文结构化。 | `structuredBlocks=[]`、`NO_STRUCTURED_BLOCKS`。 | `TAVILY_PREFETCHED` 结果至少产生 `OVERVIEW/FEATURE/API/DOC/POLICY/PRICING/RISK` 等可用块，downstreamEvidenceViews 不再全空。 |
| 3 | 证据可用性门禁与评分交叉降权。 | 壳页高分、官方域名高可信但正文垃圾。 | blocked/shell/no-structured/repeated-content/content-too-thin 任一强信号出现时，quality/trust/verified 必须降级或进入 repair。 |
| 4 | Tavily fast-lane 交接审计。 | raw_content 是否被消费不可解释。 | audit 中能看到 `prefetchedContentRef -> primaryTool=TAVILY_PREFETCHED -> executorType=TAVILY_PREFETCHED -> evidence_source`，未消费时有明确 reason。 |
| 5 | 中文鉴权/验证码/壳页检测。 | B 站“智能验证检测中”等漏检。 | 中文 blocked signals 覆盖 `验证码`、`智能验证`、`登录/注册`、`网络超时请重试`、`由极验提供技术支持` 等信号。 |
| 6 | 搜索采集加固计划前半段落地。 | 鉴权墙后无法主动转向公开深链。 | `PublicEvidenceRecoveryService` 能在根入口弱证据时补同域文档/API/协议页；`PublicShellRecoveryExtractor` 只作为安全兜底，不伪装成完整正文。 |

---

## 6. 测试策略调整

当前测试阶段不应一次性加入“完整商业竞品报告”的所有标准。应该先确认系统擅长的维度，再攻坚困难维度。

### 6.1 先测容易成功的维度

| 维度 | 原因 | 推荐样本 |
|---|---|---|
| 产品概览 | 官网/文档入口通常能覆盖。 | 官网首页、开放平台首页、产品介绍页。 |
| 产品定位 | 官网和关于页常见。 | `about`、`platform`、`solution` 页面。 |
| 目标用户 | 文档、服务商页、入驻流程常见。 | 开发者中心、服务商中心、入驻说明。 |
| 核心功能/开发者能力 | task66 已证明这类字段较容易抽到。 | API 文档、SDK 指南、能力列表。 |
| 优势/亮点 | 官方页面通常有正向能力描述。 | 产品服务、解决方案、能力介绍。 |

### 6.2 后测困难维度

| 维度 | 难点 | 前置条件 |
|---|---|---|
| 定价策略 | 国内开放平台可能没有公开价格，或需商务咨询。 | CoverageContract 支持 optional/notApplicable；pricing query 确实执行。 |
| 短板与风险 | 官方来源通常不会主动写短板。 | 支持第三方可信来源、开发者协议、限制条款、工单/公告等证据。 |
| 竞品劣势对比 | 需要跨来源推理，不能只靠官网。 | Analyzer 只基于已标注证据，不允许凭空补。 |
| 政策/合规风险 | 证据分散在协议、隐私政策、公告。 | Tavily/搜索必须命中协议页并结构化切块。 |

### 6.3 task66 回归用例建议

| 用例 | 期望 |
|---|---|
| 能力介绍模式：抖音开放平台 vs B 站开放平台，维度只含开放平台/开发者生态/产品功能。 | pricing/weaknesses 不作为 blocker；报告应通过正向能力字段。 |
| 完整商业分析模式：显式加入定价策略/风险短板。 | 系统必须执行 pricing/risk query，缺证据时进入 repair，不允许用首页壳页硬写。 |
| Tavily fast-lane 模式：使用 Tavily raw_content 命中文档细页。 | collection result 为 `TAVILY_PREFETCHED`，有结构块，有 evidenceIds。 |
| 鉴权墙模式：根入口只返回登录/验证壳。 | 根入口降级，不落为高分证据；触发公开替代入口恢复。 |

---

## 7. 关键代码锚点

| 主题 | 代码锚点 | 结论 |
|---|---|---|
| 规划期候选高分 | `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java` | direct candidate 固定 `relevanceScore=0.95`、`qualityScore=0.92`。 |
| Direct HTML 质量分 | `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java` | 按长度和链接算分，正文实质判断不足。 |
| 当前验证逻辑 | `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java` | 主路径已不再 OFFICIAL 无条件通过，但 Tavily fast-lane 仍跳过网络重验。 |
| 目标选择 fallback | `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java` | fallback target 仍可能只有 candidate，没有 collectedPage。 |
| Tavily 预取路由 | `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java` | 满足 fast-lane 条件时 primaryTool 为 `TAVILY_PREFETCHED`。 |
| Tavily 预取执行器 | `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java` | 会消费 registry，但成功结果 `structuredBlocks=[]`。 |
| 固定抽取字段 | `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java` | 固定七字段，尚未由 CoverageContract 驱动。 |
| 固定质检字段 | `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java` | 固定检查 pricing/weaknesses 等字段。 |
| 中文 blocked signals | `backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java` | 默认 blocked 信号仍偏英文。 |
| Writer/Delivery 投影 | `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`、`ReportExportRenderer.java` | writer evidence 与 orchestration decision 已投影，不应再列为当前 P0。 |

---

## 8. 关键文档索引

| 文档 | 用途 |
|---|---|
| [2026-06-28-task66-coverage-contract-and-test-strategy-design.md](../specs/2026-06-28-task66-coverage-contract-and-test-strategy-design.md) | task66 后续修复设计：CoverageContract、Tavily 结构化、测试策略。 |
| [2026-06-27-tavily-phase1-bootstrap-progress.md](../progress/2026-06-27-tavily-phase1-bootstrap-progress.md) | Tavily Phase 1 bootstrap 已在 task66 中触发的进度证据。 |
| [tavily-phase1-bootstrap-execution-plan.md](../tavily-phase1-bootstrap-execution-plan.md) | Tavily Phase 1 Bootstrap 计划。 |
| [2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md](../plan/2026-06-28-04-task66-search-collection-public-evidence-recovery-plan.md) | 搜索采集加固 7 Task，当前作为 Phase 4 公开证据补采底座执行。 |
| [CollectionExecution.md](../../superpowers/search-and-collection/problem/CollectionExecution.md) | 采集执行层断点诊断。 |
| [CollectorAgent.md](../../superpowers/search-and-collection/problem/CollectorAgent.md) | 搜索采集全链路代码级审计。 |
| [AnalysisReasoning.md](../../superpowers/analysis-reasoning/problem/AnalysisReasoning.md) | 分析推理链路问题。 |
| [ReportWriting.md](../../superpowers/report-writing/problem/ReportWriting.md) | 报告写作链路历史问题与当前剩余风险。 |
| [DeliveryAudit.md](../../superpowers/delivery-audit/problem/DeliveryAudit.md) | 交付审计链路历史问题与当前剩余风险。 |
