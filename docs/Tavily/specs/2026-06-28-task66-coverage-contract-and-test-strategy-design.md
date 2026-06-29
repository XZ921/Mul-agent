# Task 66 覆盖契约与测试策略修改设计

## 1. 背景与结论

Task 66 的失败不是单纯的“搜索失败”，而是“任务目标、采集证据类型、结构化证据能力、下游固定审查要求”之间发生了错位。

本轮任务的主题是“短视频平台开放生态与开发者能力”，分析维度是“开放平台、开发者生态、产品功能”，信息源范围是“官网、产品文档”。这类任务天然更适合验证官方能力介绍、开发者文档、接口能力、生态入口等正向事实，不适合作为定价策略、短板风险、竞品劣势的首轮强测任务。

系统当前的主要问题是：上游任务目标没有把 `pricing` 和 `weaknesses` 设为主采集目标，下游 `SchemaExtractorAgent` 与 `QualityReviewAgent` 却仍按固定字段强制检查 `summary / positioning / targetUsers / coreFeatures / pricing / strengths / weaknesses`。当采集内容主要来自开放平台首页和文档入口页时，正向能力字段可以抽出，但定价与短板风险必然缺证据，最终被 Reviewer 放大为终审失败。

因此，本次修改的核心不是“给搜索多加几个关键词”，而是建立一套覆盖契约：让任务维度、采集计划、抽取字段、写作章节和质量审查共享同一份字段要求，明确哪些字段是必填，哪些字段是可选，哪些字段本轮任务不适用。

### 1.1 工程核验后的设计修订

结合 `docs/Tavily/problem/2026-06-28-task66-self-check-report.md` 的工程核验，本规格需要按以下事实修正设计边界：

- `Tavily Phase 1 bootstrap` 已经在 task66 中触发，当前问题不是“没有接入 Phase 1”，而是 bootstrap 命中的细页是否被选中、预抓正文是否被消费、消费后是否结构化落库。
- Tavily raw_content 不是必然丢在 Registry。当前已有 `TAVILY_PREFETCHED` 执行器路径；设计重点应从“Registry -> CollectedPage 桥必须接通”收紧为“fast-lane 交接必须可审计，且不能静默 fallback”。
- `qualityScore=0.92` 不能继续简单归因给 Direct HTML 长度评分。当前工程里 direct candidate 规划期硬编码分也会给出 `qualityScore=0.92 / relevanceScore=0.95`。设计需要拆分“候选来源分”和“证据正文可用性分”。
- `OFFICIAL` 主路径已不再无条件 `verified=true`。剩余风险是 Tavily fast-lane 的 skip network verification 仍可能缺少正文结构可用性门禁。
- Writer evidence 与 orchestration decision 已有报告/导出投影，不再作为本规格 P0；本规格只保留“动态修复闭环还需 live 验证”的剩余风险。

因此，本规格的 P0 顺序调整为：先用 `CoverageContract` 和 `DimensionEvidencePlan` 修正字段强检与采集驱动方式，再补 Tavily 预抓正文结构化，然后建立证据可用性门禁、评分交叉降权、fast-lane 审计闭环和中文鉴权壳识别。这里的关键转向是：系统不能继续只按 `sourceType` 找页面，再让 extractor 事后碰运气抽字段；必须先按 `analysis field / dimension` 规划证据路径，再把 OFFICIAL、DOCS、PRICING、NEWS、REVIEW 等来源类型作为路径工具。

## 2. 当前系统能力边界

### 2.1 当前效果较好的维度

- 产品概览：适合从官网首页、产品页、开放平台首页抽取。
- 市场定位：适合从官网 slogan、平台介绍、开发者中心介绍抽取。
- 目标用户：适合从面向开发者、创作者、商家、企业客户等页面抽取。
- 核心功能：适合从产品文档、功能页、API 文档、SDK 指南抽取。
- 优势判断：适合基于官方能力、生态入口、工具链完整度做有来源的谨慎总结。
- 官方来源追溯：系统已经能稳定保留 `sourceUrls`，适合先验证无幻觉链路。
- 任务计划与进度：采集阶段已有结构化计划和进度审计，适合继续作为测试观察点。
- Tavily Phase 1 bootstrap：当前工程已经能在弱入口阶段触发 Tavily 增强，适合继续作为候选发现与 fast-lane 消费的测试观察点。

### 2.2 当前效果一般或不适合首轮强测的维度

- 定价策略：开放平台、开发者生态、接口能力任务未必公开定价，官方文档中也可能没有价格信息。
- 短板与风险：官方资料通常不会直接写自身短板，只能从规则、协议、限制、审核要求中谨慎推导。
- 竞品劣势：需要第三方测评、用户反馈、行业报告或更多交叉证据，不适合只用官网和产品文档强行判断。
- 商业化模式：如果没有定价页、招商页、服务协议或公开收费说明，容易变成模型猜测。
- 深层细页证据：当前系统可能发现 `/guide`、`/agreement` 等细页，但没有稳定提升为正式采集目标。
- Tavily 预抓取结构化证据：`TavilyPrefetchedExecutor` 当前会把 `structuredBlocks` 固定置空，导致下游只能消费粗粒度正文和 URL。
- 证据可用性评分：候选分、来源可信度和正文可用性仍容易混用，官方域名或文本长度可能掩盖导航壳、登录壳、验证码壳。
- 中文鉴权/反爬识别：默认 blocked signals 偏英文，中文站点常见的“智能验证、验证码、登录注册、由极验提供技术支持”等信号容易漏检。

## 3. 修改目标

### 3.1 短期目标

先让测试阶段合理、稳定、可复现。对能力介绍型任务，不再用完整标准竞品报告的所有字段强压系统。系统应允许任务只覆盖“官方能力介绍”相关字段，并把定价、短板风险标记为“本轮非主目标”或“证据未覆盖”，而不是直接作为终审 blocker。

### 3.2 中期目标

建立统一的 `CoverageContract`，在任务计划生成时明确字段要求。后续 Collector、Extractor、Analyzer、Writer、Reviewer 都读取同一份覆盖契约，避免各阶段各自理解任务目标。

### 3.3 长期目标

逐步攻坚困难维度。等官方能力、文档细页、结构化证据链路稳定后，再引入定价、风险、第三方评价、用户口碑、商业化模式等高难维度的补采和审查策略。

## 4. 覆盖契约设计

### 4.1 字段状态

每个分析字段应有明确状态：

- `REQUIRED`：本轮任务必须采集、抽取、写作和审查。
- `OPTIONAL`：有证据则输出，没有证据不阻断终审。
- `OUT_OF_SCOPE`：本轮任务不要求，不参与缺口判断。
- `NOT_APPLICABLE`：对当前竞品或任务类型不适用，例如开放平台未公开标准定价。
- `EVIDENCE_NOT_COVERING`：字段本应覆盖，但当前证据不足，需要补采或降级。
- `REPAIR_ONLY`：本轮不作为首轮强检字段，但如果报告生成或 Reviewer 发现该字段被写入，就必须触发补证或阻断无源结论。
- `CONFIRMED_FREE`：多条证据路径确认该能力免费或无独立收费项，可作为定价字段的正向结论。
- `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`：已按字段证据计划检索多条路径，仍未发现公开证据；这是可审计检索结论，不等同于单个采集节点失败。
- `IMPLICIT_IN_DOCS`：字段信息分散在文档、协议或限制说明中，没有独立页面，但可由文档证据谨慎支撑。
- `REQUIRES_SALES_CONTACT`：公开材料只说明需商务、销售或申请开通，不能推断具体价格。

### 4.1.1 字段契约结构

`CoverageContract` 不应只是字段状态枚举，还需要带上采集和审查所需的运行时信息：

```json
{
  "taskMode": "CAPABILITY_INTRO",
  "contractVersion": "task-66-plan-v1",
  "source": "PLANNER",
  "fields": [
    {
      "field": "coreFeatures",
      "status": "REQUIRED",
      "blockingLevel": "BLOCKER",
      "targetEvidenceTypes": ["OFFICIAL_DOC", "DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"],
      "queryIntents": ["OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE"],
      "evidencePaths": [
        {
          "pathKey": "DOCS_API_GUIDE",
          "sourceTypes": ["DOCS", "OFFICIAL"],
          "queryIntents": ["API_DOCS", "SDK_GUIDE"],
          "expectedSignals": ["DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"],
          "required": true
        }
      ],
      "minDistinctEvidenceCount": 2,
      "allowOfficialOnly": true,
      "overrideReason": "analysis_dimensions 命中 开放平台/开发者生态/产品功能"
    },
    {
      "field": "pricing",
      "status": "OUT_OF_SCOPE",
      "blockingLevel": "NONE",
      "targetEvidenceTypes": ["PRICING_BLOCK"],
      "queryIntents": [],
      "evidencePaths": [],
      "minDistinctEvidenceCount": 0,
      "allowOfficialOnly": true,
      "overrideReason": "taskMode=CAPABILITY_INTRO 且 analysis_dimensions 未显式要求定价"
    }
  ]
}
```

字段契约至少包含：

- `field`：字段名，例如 `summary / pricing / weaknesses`。
- `status`：字段状态。
- `blockingLevel`：`BLOCKER / WARNING / NONE`，Reviewer 只对 `BLOCKER` 生成终审阻断。
- `targetEvidenceTypes`：字段需要的证据类型，例如 `FEATURE_BLOCK / PRICING_BLOCK / LIMITATION_OR_POLICY_BLOCK`。
- `queryIntents`：Collector 必须实际执行的 query 意图，避免“模板存在但真实 query 没跑”。
- `evidencePaths`：字段级证据路径，不等同于 sourceType。每条路径声明可用来源类型、查询意图、预期结构信号、是否必跑和失败后的结论语义。
- `minDistinctEvidenceCount`：最少互补证据数量，避免 task66 这种多个 URL 指向同质入口内容。
- `allowOfficialOnly`：是否允许只用官方来源支撑该字段。`weaknesses` 通常不应只靠官方能力页支撑。
- `overrideReason`：记录该字段状态的来源和覆盖原因，便于解释“为什么 pricing 不是 blocker”或“为什么 source_scope 受限但 pricing 仍 required”。

### 4.1.2 契约传递路径

`CoverageContract` 必须有唯一权威落点，不能让 Collector、Extractor、Analyzer、Writer、Reviewer 各自从 prompt 或 sharedOutput 里解析一份隐式版本。

短期采用“计划快照内嵌 + 节点配置引用”的方式：

- Planner 在生成 `TaskPlan` 或等价计划快照时写入顶层 `coverageContract` 字段，作为本轮任务的权威契约。
- 每个需要消费契约的 `ExecutionPlanDefinition.NodeDefinition.nodeConfig` 中只写入 `coverageContractRef`、`requiredFields`、`blockingFields` 这类轻量引用或裁剪视图，不复制完整契约。
- Orchestrator 创建节点时，把同一份 `coverageContract` 快照持久化到 task plan / plan version 中；Agent 运行时通过统一 `CoverageContractProvider` 读取。
- Agent 不允许各自实现 `parseCoverageContract`。需要新增统一的解析/校验组件，例如 `CoverageContractProvider` 和 `CoverageContractResolver`，返回强类型对象。
- 如果某个节点需要局部覆盖，例如补证节点临时把 `pricing` 升级为 `REQUIRED`，必须写入 `overrideReason`，并产生新的 `contractVersion` 和字段级 `evidencePlanVersion`，不能原地修改历史契约。

后续如果需要查询和审计更强，可以再升级为独立 DB 实体。但首轮不建议直接新建复杂表结构；先保证所有 Agent 读同一份计划快照，避免五套隐式实现发散。

### 4.2 字段默认分层

能力介绍型任务默认要求：

- `summary`：`REQUIRED`
- `positioning`：`REQUIRED`
- `targetUsers`：`REQUIRED`
- `coreFeatures`：`REQUIRED`
- `strengths`：`OPTIONAL`
- `pricing`：`OPTIONAL` 或 `OUT_OF_SCOPE`
- `weaknesses`：`OPTIONAL` 或 `OUT_OF_SCOPE`

标准竞品全量报告默认要求：

- `summary`：`REQUIRED`
- `positioning`：`REQUIRED`
- `targetUsers`：`REQUIRED`
- `coreFeatures`：`REQUIRED`
- `pricing`：`REQUIRED`
- `strengths`：`REQUIRED`
- `weaknesses`：`REQUIRED`

高难攻坚任务默认要求：

- 定价分析任务：`pricing` 为 `REQUIRED`，并必须生成定价字段证据计划；该计划至少覆盖官方定价入口、文档/协议中的收费说明、公告/新闻、第三方确认或无公开证据结论，而不是只生成一个 `PRICING` 采集节点。
- 风险短板任务：`weaknesses` 为 `REQUIRED`，并必须生成规则、协议、限制、第三方资料或用户反馈等字段证据路径；官方能力页不能单独支撑负面结论。
- 口碑测评任务：第三方评价、公开测评、用户反馈为 `REQUIRED`，官方材料不能单独支撑负面结论。

### 4.3 任务维度到字段契约的映射

计划生成阶段根据 `analysis_dimensions` 和 `source_scope` 推导契约：

- 命中“产品功能、开放平台、开发者生态、API、SDK、文档、能力”时，优先要求 `summary / positioning / targetUsers / coreFeatures`。
- 命中“定价、价格、套餐、计费、商业化”时，要求 `pricing`，并追加 pricing 字段证据计划；计划可使用 `PRICING / DOCS / OFFICIAL / NEWS / REVIEW` 等来源路径交叉验证。
- 命中“风险、短板、劣势、限制、合规、协议、审核、规则”时，要求 `weaknesses`，并追加 weaknesses 字段证据计划；计划可使用 `TERMS / POLICY / REVIEW / NEWS` 等路径交叉验证。
- `source_scope` 只有“官网、产品文档”时，不应默认强检第三方口碑和竞品劣势。
- `source_scope` 包含“公开测评、用户反馈、行业报告、新闻”时，才允许生成更强的风险和劣势判断。

冲突时按以下优先级决策：

1. 显式报告模板：例如用户选择“完整标准竞品报告”，则标准字段优先为 `REQUIRED`。
2. 显式分析维度：例如 `analysis_dimensions=["定价策略"]`，即使 `source_scope=["官网"]`，`pricing` 仍为 `REQUIRED`，但 `queryIntents` 应优先使用官方定价/计费/协议来源。
3. 显式用户约束：例如用户说“不要分析风险”，则 `weaknesses` 可降为 `OUT_OF_SCOPE`，并写明 `overrideReason`。
4. 来源范围推导：`source_scope=["官网","产品文档"]` 时，不默认要求第三方口碑或竞品劣势。
5. 系统默认：无法命中任何规则时，使用能力介绍型默认契约，避免一上来按全量标准报告强检。

示例：

- `source_scope=["官网"] + analysis_dimensions=["定价策略"]`：`pricing=REQUIRED`，`overrideReason=显式维度要求优先于来源范围受限`。
- `template=STANDARD_COMPETITOR_REPORT + source_scope=["官网","产品文档"]`：标准字段仍可为 `REQUIRED`，但 Planner 必须提示“来源范围不足以稳定支撑 weaknesses，可能需要补充第三方或协议/规则来源”。
- `taskMode=CAPABILITY_INTRO + analysis_dimensions=["开放平台","开发者生态"]`：`pricing/weaknesses=OUT_OF_SCOPE 或 OPTIONAL`，不作为终审 blocker。

### 4.4 维度优先证据计划

`CoverageContract` 必须从字段强检契约升级为字段证据规划契约。`sourceType` 只能表达“这条候选来源属于什么页面家族”，不能表达“某个分析字段是否已被证明、排除或确认无公开证据”。因此，系统应以 `analysis field / dimension` 为第一层规划单元，再把来源家族作为证据路径工具。

以 `pricing` 为例，`pricing=REQUIRED` 时不能只生成 `collect_sources_xx_PRICING` 并在找不到 `/pricing` 页面后结束。Planner 应生成字段级证据计划：

```json
{
  "field": "pricing",
  "status": "REQUIRED",
  "evidencePlanVersion": "pricing-v1",
  "evidencePaths": [
    {
      "pathKey": "OFFICIAL_PRICING_PAGE",
      "sourceTypes": ["PRICING", "OFFICIAL"],
      "queryIntents": ["OFFICIAL_PRICING"],
      "successStatuses": ["TRACEABLE", "REQUIRES_SALES_CONTACT", "CONFIRMED_FREE"]
    },
    {
      "pathKey": "DOCS_BILLING_OR_LIMITS",
      "sourceTypes": ["DOCS"],
      "queryIntents": ["DOCS_BILLING", "API_LIMITS"],
      "successStatuses": ["TRACEABLE", "IMPLICIT_IN_DOCS", "CONFIRMED_FREE"]
    },
    {
      "pathKey": "TERMS_OR_SERVICE_AGREEMENT",
      "sourceTypes": ["OFFICIAL", "DOCS"],
      "queryIntents": ["TERMS_BILLING", "SERVICE_AGREEMENT"],
      "successStatuses": ["IMPLICIT_IN_DOCS", "REQUIRES_SALES_CONTACT"]
    },
    {
      "pathKey": "PUBLIC_WEB_CONFIRMATION",
      "sourceTypes": ["NEWS", "REVIEW"],
      "queryIntents": ["PRICING_NEWS", "THIRD_PARTY_PRICING_CONFIRMATION"],
      "successStatuses": ["TRACEABLE", "CONFIRMED_FREE", "NO_PUBLIC_EVIDENCE_AFTER_SEARCH"]
    }
  ],
  "minimumAttemptedPaths": 3,
  "minimumPositiveEvidenceCount": 1
}
```

字段证据计划的运行结果应写入 `fieldEvidenceCoverage`，至少包含：

- `field`：字段名。
- `coverageStatus`：最终字段覆盖状态，例如 `TRACEABLE / CONFIRMED_FREE / IMPLICIT_IN_DOCS / NO_PUBLIC_EVIDENCE_AFTER_SEARCH / EVIDENCE_NOT_COVERING`。
- `attemptedPaths`：每条路径的执行状态，包含 `pathKey / sourceTypes / queryIntents / attemptedQueries / selectedUrls / rejectedUrls / status / reason`。
- `sourceUrls`：所有被用于结论的来源 URL。
- `negativeEvidenceSummary`：当未找到公开证据时，记录已检索路径和失败原因，避免把“没搜到”误写成“免费”。
- `recommendedNextAction`：`ACCEPT_CONCLUSION / REPAIR_WITH_TAVILY / REQUIRE_HUMAN_REVIEW / DOWNGRADE_FIELD`。

这意味着 Collector 的成功标准从“某个 sourceType 节点采到页面”改为“字段证据计划达到最低路径覆盖”。Extractor 和 Reviewer 不再把 `EVIDENCE_NOT_COVERING` 当作单一黑箱，而要区分“未执行足够路径”“已执行但无公开证据”“多源确认免费”“证据只隐含在文档里”等不同结论。

`SourceFamilyDirectDiscoveryPlanner`、`TavilySearchProfileResolver` 和 `PublicEvidenceRecoveryService` 仍然保留，但职责必须收窄：它们负责为字段证据路径提供候选页面和 raw content，不负责决定字段最终覆盖结论。字段结论由 `DimensionEvidencePlanner / EvidencePathExecutor / FieldCoverageAggregator` 这类新边界统一收口。

### 4.5 结构化维度映射目录

`CoverageContractResolver` 不能长期依赖散落的 `containsAny(analysisDimensions, List.of(...))` 关键词判断。短期保留关键词匹配可以接受，但关键词必须集中在 `AnalysisDimensionMappingCatalog` 这类结构化目录中，由目录输出以下信息：

- `dimensionKey`：命中的标准化维度，例如 `CAPABILITY_INTRO / PRICING_ANALYSIS / WEAKNESS_ANALYSIS`。
- `targetFields`：该维度会影响哪些结构化字段，例如 `coreFeatures / pricing / weaknesses`。
- `evidencePathKeys`：该维度要求哪些字段证据路径，例如 `DOCS_BILLING_OR_LIMITS`。
- `sourceTypes`：可使用哪些来源家族，例如 `OFFICIAL / DOCS / PRICING / NEWS / REVIEW`。
- `queryIntents`：后续可翻译为 Tavily query 的意图。
- `requiredByDefault / priority / reason`：用于冲突决策和审计。

Resolver 的职责应收窄为“合并模板、用户约束、维度目录和来源范围，产出契约”。它不应直接维护业务关键词列表。后续如果引入枚举化维度、配置化 YAML 或模型分类，只替换 `AnalysisDimensionMappingCatalog`，不改 Resolver 主流程。

### 4.6 字段证据路径到 Tavily Query 的翻译层

Tavily 底层仍然按 `OFFICIAL / DOCS / PRICING / NEWS / REVIEW` 等来源家族组织搜索 profile，这是合理的底层能力，但不能让上层字段计划退化成 sourceType-first。需要新增明确的翻译层，例如 `FieldEvidenceQueryPlanner`：

```text
FieldEvidencePath
  -> queryIntent
  -> sourceTypes
  -> TavilySearchProfile
  -> concrete queries
```

翻译层必须满足：

- 输入是 `fieldName / evidencePathKey / queryIntent / sourceTypes / competitorName / locale`，不是单独的 `sourceType`。
- 输出必须记录 `fieldName / evidencePathKey / queryIntent / sourceType / query / reason`。
- 同一个字段路径可以展开多个来源家族查询，例如 pricing 的 `DOCS_BILLING_OR_LIMITS` 可以同时生成文档计费、API 限制和协议查询。
- `TavilySearchProfileResolver` 只负责把来源家族和 query intent 转成具体搜索表达式，不负责决定字段是否覆盖。
- 当字段路径没有执行到 `minimumAttemptedPaths` 时，Reviewer 必须判为 `EVIDENCE_PATH_COVERAGE_NOT_MET`，不能因为某个 sourceType 节点成功就放行。

### 4.7 迁移兼容的禁止退化规则

迁移期可以保留旧接口帮助编译通过，但旧接口必须被标为 `@Deprecated`，并且只能包装成“无字段上下文”的低优先级调用。所有新链路必须调用带 `fieldName / evidencePathKey / queryIntents` 的上下文对象。

禁止退化规则：

- `PublicEvidenceRecoveryService.planRecovery(String competitorName, String sourceType, ...)` 只能用于 legacy fallback，不能被 `SearchExecutionCoordinator`、repair、DimensionEvidencePlan adapter 等新路径直接调用。
- 单元测试必须覆盖新路径调用，并验证旧签名被标记为 deprecated 或被 lint/测试扫描发现。
- 当旧签名被调用时，trace 必须写入 `FIELD_CONTEXT_MISSING_LEGACY_RECOVERY`，避免误以为字段路径补采已执行。
- Phase 4 完成后，Phase 5 不得再新增旧签名调用点。

### 4.8 可审计答案合成层

字段证据路径解决“为每个字段找证据”的问题，但还需要把多条证据合成为最终结论。这个步骤不能完全隐在 LLM prompt 里，否则 `CONFIRMED_FREE / IMPLICIT_IN_DOCS / NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 等状态缺少可审计推理链路。

需要新增 `FieldAnswerSynthesizer` 或等价边界，输入 `FieldEvidenceCoverage`，输出 `FieldAnswerConclusion`：

- `field`：字段名。
- `coverageStatus`：沿用字段覆盖状态。
- `answerValue`：最终可写入报告的结论；无证据时必须为空或明确降级。
- `supportingEvidenceIds / sourceUrls`：支撑结论的证据。
- `reasoningSteps`：结构化推理步骤，例如“官方定价页未找到”“文档计费路径命中免费说明”“第三方路径未找到冲突证据”。
- `contradictions`：冲突证据摘要。
- `confidence`：只表达证据充足度，不表达模型主观确信。
- `recommendedNextAction`：`ACCEPT_CONCLUSION / REPAIR_WITH_TAVILY / REQUIRE_HUMAN_REVIEW / DOWNGRADE_FIELD`。

LLM 可以参与自然语言摘要，但结构化状态转换必须由 `FieldCoverageAggregator / FieldAnswerSynthesizer` 持久化，Reviewer 审查的是这份结构化结论，而不是只审查最终段落文本。

## 5. Agent 修改方向

### 5.1 Planner

Planner 负责生成本轮 `CoverageContract` 和字段级 `DimensionEvidencePlan`，并写入计划版本或节点配置。它需要说明每个字段的目标、依赖来源、预计耗时、证据路径和是否阻断终审。

对于 task 66 这种能力介绍型任务，Planner 应输出：

- `summary / positioning / targetUsers / coreFeatures` 为 `REQUIRED`
- `strengths` 为 `OPTIONAL`
- `pricing / weaknesses` 为 `OUT_OF_SCOPE` 或 `OPTIONAL`
- 不生成定价和风险强制补采，除非用户明确选择标准全量报告或相关维度
- `queryIntents` 不包含 `PRICING`、`RISK`，避免模板存在但任务目标不要求时误触发强检
- `taskMode` 标为 `CAPABILITY_INTRO`，供 Reviewer 和测试用例区分能力介绍任务与标准全量报告
- 顶层计划快照写入完整 `coverageContract`，节点 `nodeConfig` 只写引用和裁剪视图
- 生成 `contractVersion` 和每个字段的 `overrideReason`，保证后续审计能解释字段状态来源
- 对 `REQUIRED` 字段生成 `evidencePaths`，并明确哪些路径必跑、哪些路径只在 repair 时补跑
- 对 `OPTIONAL / OUT_OF_SCOPE` 字段不得生成强制 evidence path，除非报告中实际写入该字段结论并触发 `REPAIR_ONLY`

### 5.2 Collector

Collector 需要按字段证据计划生成采集目标，而不是只按固定模板或单个 `sourceType` 挂 query。

- 当 `pricing=REQUIRED` 时，必须实际执行 `OFFICIAL_PRICING_PAGE / DOCS_BILLING_OR_LIMITS / TERMS_OR_SERVICE_AGREEMENT / PUBLIC_WEB_CONFIRMATION` 等字段路径中配置的 query 和候选选择。
- 当 `weaknesses=REQUIRED` 时，必须实际执行协议、规则、限制、测评、新闻或第三方材料等字段路径，不能只采官方能力页。
- 当 `pricing/weaknesses=OPTIONAL` 时，可以发现即采，不足不阻断。
- 当多个 URL 内容高度重复时，应触发细页补采或去重降级，而不是把重复入口当作充分证据。
- 当字段 `queryIntents=[]` 时，不应因为全局模板存在而生成对应 query。
- 当 `minimumAttemptedPaths` 或 `minDistinctEvidenceCount` 未满足时，应记录 `EVIDENCE_PATH_COVERAGE_NOT_MET / EVIDENCE_DIVERSITY_NOT_MET`，并优先补采对应字段路径需要的深层页面。
- 当候选是根域入口页且契约需要 `DEVELOPER_DOCS_BLOCK / API_DOCS` 时，应提升深链、Tavily raw_content 完整度和 pageType 权重。
- 当 Tavily fast-lane candidate 被选中时，必须把 `providerKey / fastLaneUsable / hasPrefetchedContent / prefetchedContentRef / prefetchedRawContentLength` 带入 collection package，不能在去重或 selection refresh 时丢失。
- Collector 只能通过统一 `CoverageContractProvider` 读取契约，不允许直接解析 nodeConfig 中的完整 JSON。
- Collector 的审计输出必须记录 `contractVersion`、`evidencePlanVersion`、实际执行的 `queryIntents`、跳过的 intent、每条 `evidencePath` 的状态及原因。

### 5.3 Tavily 预抓取执行器

`TavilyPrefetchedExecutor` 不应永远输出空 `structuredBlocks`。短期可以先做轻量文本结构化：

- 内容命中“价格、定价、套餐、计费、pricing、plan、billing”时生成 `PRICING_BLOCK`。
- 内容命中“限制、风险、审核、规则、协议、条款、limitation、risk、policy、agreement”时生成 `LIMITATION_OR_POLICY_BLOCK`。
- 内容命中“API、SDK、开发文档、guide、reference、open platform”时生成 `DEVELOPER_DOCS_BLOCK`。
- 内容命中“功能、能力、场景、产品介绍”时生成 `FEATURE_BLOCK`。

这些结构块不要求一次做到完美，但要避免下游看到 `MAIN_CONTENT_READY + NO_STRUCTURED_BLOCKS` 后无法判断页面证据类型。

轻量分类必须加防噪门槛，避免导航壳被错误结构化：

- 关键词必须出现在段落正文、列表说明、标题正文或文档正文中，不能只出现在导航菜单、页脚链接、面包屑、登录注册区域。
- 命中某类结构块时，正文段落中该类关键词密度必须超过最小阈值，且至少存在一段长度达到最小正文长度的证据片段。
- 如果 `Evidence Quality Gate` 已判定 `NAVIGATION_SHELL / AUTH_OR_CAPTCHA_GATE / LINK_FARM_WITHOUT_BODY`，则 Tavily 结构化只能输出低可信 block 或不输出 block，不能用关键词命中抵消负信号。
- `structuredBlocks` 需要带 `blockConfidence` 和 `blockEvidenceReason`，例如“段落正文命中 API/SDK 且段落长度达标”，不能只记录命中的关键词。
- 当分类器不确定时，宁可保留 `NO_STRUCTURED_BLOCKS` 并触发 repair，也不要生成虚假的 `PRICING_BLOCK` 或 `DEVELOPER_DOCS_BLOCK`。

同时，Tavily 执行器必须补审计字段：

- `prefetchedContentRef`：消费的 registry key。
- `prefetchedRawContentLength`：Tavily 返回的正文长度。
- `executorType=TAVILY_PREFETCHED`：落地结果必须标明执行器。
- `qualitySignals`：成功时包含 `TAVILY_RAW_CONTENT_READY`、`TAVILY_PREFETCHED_CONTENT_CONSUMED`；失败时包含 `TAVILY_PREFETCHED_CONTENT_MISSING` 或 `TAVILY_PREFETCHED_CONTENT_EMPTY`。
- `structuredBlockCount`：结构块数量，不能只在 metadata 里间接推断。

fast-lane 的验证跳过不能等同于正文可用。即使 `TAVILY_FAST_LANE_GATE_VERIFIED` 成立，落库前仍要经过采集后证据质量门禁；如果 raw_content 是导航壳、验证码壳或重复入口内容，应降级或触发 repair。

### 5.4 Extractor

Extractor 继续遵守无幻觉原则：任何字段有值都必须携带 `sourceUrls` 或 `evidenceIds`。但字段是否必须有值，应由 `CoverageContract` 决定。

- `REQUIRED` 字段为空：输出缺口并触发补采或终审失败。
- `OPTIONAL` 字段为空：记录缺口，但不阻断。
- `OUT_OF_SCOPE` 字段为空：不计入失败。
- `NOT_APPLICABLE` 字段：允许输出明确说明，例如“当前官方资料未公开定价信息，本轮不做定价结论”。
- `CONFIRMED_FREE / IMPLICIT_IN_DOCS / REQUIRES_SALES_CONTACT / NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 字段：必须引用字段证据计划的 `attemptedPaths` 和 `sourceUrls`，不能只由模型自行判断。
- Extractor 通过 `CoverageContractProvider` 获取字段状态，输出中回写 `contractVersion`、`evidencePlanVersion`、字段级 `coverageStatus` 和 `fieldEvidenceCoverage`，供 Analyzer/Reviewer 复用。

### 5.5 Reviewer

Reviewer 的核心修改是从“固定字段强检”改成“按覆盖契约审查”。

- 只对 `REQUIRED` 字段产生 blocker。
- 对 `OPTIONAL` 字段只产生 warning。
- 对 `OUT_OF_SCOPE` 字段不产生 coverage gap。
- 对 `NOT_APPLICABLE` 字段检查表述是否克制，避免模型编造。
- 对 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 字段检查是否达到 `minimumAttemptedPaths`，未达到时仍应判为 `EVIDENCE_PATH_COVERAGE_NOT_MET`。
- 对 `CONFIRMED_FREE / IMPLICIT_IN_DOCS / REQUIRES_SALES_CONTACT` 字段检查 `sourceUrls` 与 `attemptedPaths` 是否足以支撑该结论。
- 当报告写了定价、风险、劣势等判断，但契约或证据不足时，仍要按无幻觉原则阻断。
- Reviewer 不允许维护独立字段 required 列表；固定字段规则只能作为 fallback，用于缺失契约的历史任务兼容。
- Reviewer 的诊断输出必须包含 `contractVersion`、`evidencePlanVersion` 和每个 blocker 对应的 `field status / blockingLevel / overrideReason / attemptedPaths`。

### 5.6 Evidence Quality Gate

在 `EvidenceSource` 正式落库前增加统一证据可用性门禁。它不替代来源验证，而是回答另一个问题：这段正文能否支撑报告里的可追溯结论。

门禁输出应拆成三类分数：

- `sourceAuthenticityScore`：来源是否可信，例如官方域名、文档页、协议页、定价页。
- `contentUsabilityScore`：正文是否可用，例如是否包含事实段落、是否只是导航/页脚/登录/验证码。
- `taskRelevanceScore`：正文是否覆盖当前 `CoverageContract` 要求的字段和证据类型。

最终下游使用 `evidenceUsabilityScore`，并遵守负信号封顶：

- 命中 `AUTH_OR_CAPTCHA_GATE` 时，`contentUsabilityScore <= 0.20`，不能作为主引用。
- 命中 `NAVIGATION_SHELL` 或 `LINK_FARM_WITHOUT_BODY` 时，`contentUsabilityScore <= 0.30`。
- 命中 `ROOT_ENTRY_PAGE` 且契约需要文档细页时，`evidenceUsabilityScore <= 0.45`。
- 命中 `NO_STRUCTURED_BLOCKS` 且字段需要结构块时，字段 coverage 不得标为 `TRACEABLE`。
- 出现 `content too thin / blocked / captcha / fallbackReason` 与高分高可信同时存在时，输出 `SCORE_CONTRADICTION_DETECTED`。

门禁至少识别以下问题类型：

- `NAVIGATION_SHELL`
- `AUTH_OR_CAPTCHA_GATE`
- `ROOT_ENTRY_PAGE`
- `LINK_FARM_WITHOUT_BODY`
- `DUPLICATED_ENTRY_CONTENT`
- `WEAK_MAIN_CONTENT`
- `LOW_TASK_KEYWORD_DENSITY`
- `HIGH_TRUST_LOW_USABILITY`

这些信号不是为了“多打标签”，而是为了阻止 task66 中的首页壳、导航壳、验证码壳在 `trustTier=HIGH` 或候选高分的掩护下进入报告主证据。

## 6. 测试策略

### 6.1 测试阶段原则

当前阶段不应一开始就用完整标准竞品报告压测系统。应先确认系统最容易稳定闭环的维度，再逐步增加难度。

测试目标顺序：

1. 先测官方能力介绍型任务。
2. 再测文档细页型任务。
3. 再测定价、风险、短板等高难证据任务。
4. 最后测全量标准竞品报告。

### 6.2 第一阶段：容易闭环的能力介绍测试

推荐任务维度：

- 产品功能
- 开放平台
- 开发者生态
- API 能力
- SDK 能力
- 目标用户
- 市场定位

推荐信息源：

- 官网
- 产品文档
- 开发者文档
- 官方帮助中心

验收标准：

- `summary / positioning / targetUsers / coreFeatures` 至少达到 `TRACEABLE`。
- 所有输出字段必须有 `sourceUrls`。
- `pricing / weaknesses` 不作为 blocker。
- 报告不得编造定价、风险、劣势。
- 采集进度和计划信息必须完整持久化。

### 6.3 第二阶段：中等难度的文档细页测试

推荐任务维度：

- 接口能力
- 权限体系
- 审核流程
- 平台规则
- 服务协议
- 开发者工具链

推荐信息源：

- 官方文档细页
- API reference
- SDK guide
- agreement
- policy
- help center

验收标准：

- 细页 URL 必须进入正式采集目标，不能只停留在 BOOTSTRAPPED。
- 重复内容需要被识别，`distinctContentCount=1` 时应提示证据同质化。
- `structuredBlocks` 至少能区分文档、功能、协议、限制类证据。
- 可以谨慎输出限制或规则，但不得直接扩写成竞品短板。

### 6.4 第三阶段：高难定价与风险测试

推荐任务维度：

- 定价策略
- 商业化模式
- 短板与风险
- 竞品劣势
- 用户口碑
- 第三方测评

推荐信息源：

- 定价页
- 计费文档
- 服务协议
- 平台规则
- 公开测评
- 行业报道
- 用户反馈

验收标准：

- `pricing=REQUIRED` 时，必须实际运行定价类 query。
- `weaknesses=REQUIRED` 时，必须有规则、限制、协议、测评或第三方证据支撑。
- 如果没有公开定价，应输出 `NOT_APPLICABLE` 或 `NO_PUBLIC_PRICING`，而不是失败或编造。
- 如果没有风险证据，应输出“证据不足，不做短板结论”，而不是从官方能力页反推负面判断。

### 6.5 第四阶段：全量标准竞品报告测试

只有前三阶段稳定后，再测试完整标准报告。

验收标准：

- 所有标准字段均按 `REQUIRED` 审查。
- 定价、短板、风险必须有专门证据。
- Reviewer blocker 必须与覆盖契约一致。
- 最终报告允许保留“未公开信息”说明，但不能出现无来源判断。

## 7. Task 66 复测建议

Task 66 不建议直接按标准全量报告重跑。建议先作为第一阶段能力介绍测试样本复测。

复测配置：

- subject_product：短视频平台开放生态与开发者能力
- analysis_dimensions：开放平台、开发者生态、产品功能
- source_scope：官网、产品文档
- coverageContract：`summary / positioning / targetUsers / coreFeatures = REQUIRED`，`strengths = OPTIONAL`，`pricing / weaknesses = OUT_OF_SCOPE`

预期结果：

- 抖音开放平台和 B 站开放平台应能产出能力介绍、开发者生态、核心功能。
- 定价策略不应作为 coverage_gap blocker。
- 短板与风险不应作为 missing_citation blocker。
- 如果报告出现定价或风险判断，Reviewer 仍应阻断，因为这超出了证据覆盖范围。

后续如果要攻坚 task 66 的定价和风险，可以单独创建补证任务：

- 定价补证：搜索官方收费、计费、套餐、商业化说明。
- 风险补证：采集开发者协议、平台规则、审核要求、权限限制。
- 第三方补证：在用户明确允许公开测评或行业资料时再引入。

## 8. 采集后 Tavily 补强设计

Task 66 进一步暴露出一个比字段契约更底层的问题：`evidence_source.full_content` 可能已经被导航壳、首页入口、验证码提示、页脚协议链接和登录注册文案污染，但系统仍会把它当作可用证据传给 Extractor、Analyzer 和 Writer。

典型失败样例是 B 站开放平台首页：正文里充满“主站、开放平台、文档中心、管理中心、登录注册、立即加入、智能验证检测中”等导航和鉴权文本，真正的细页链接例如“用户管理”只作为链接出现。这样的内容不应该被视为正式证据，更不应该支撑后续报告撰写。

### 8.1 当前为什么没有触发 Tavily 补强

当前 Tavily 主要在“候选选择前”发挥作用：

- `TavilyBootstrapPlanner` 判断规划期候选是否是根域或弱入口，决定是否在 Phase 1 做候选增强。
- `SearchExecutionCoordinator.shouldSupplement` 主要根据 `verifiedCount < minVerifiedCount` 或候选数量不足决定是否补源。
- `CollectionTargetSelector` 只按验证状态和综合分数选择目标，不知道最终抓到的正文是不是导航壳。
- `CollectorAgent.isUsableCollectedPage` 只判断 content、snippet、structuredPayload、structuredBlocks 是否非空，不判断正文是不是高质量内容。

因此，一旦 `open.bilibili.com` 这类根域入口被验证通过，并且采集器拿到了非空正文，系统就会认为“采集成功”。后续不会再问：“这段 full_content 能不能支撑报告？”这就是 Tavily 没有二次补强的直接原因。

### 8.2 Tavily raw_content 预抓取交接审计

Task 66 还暴露了另一个更靠前的链路风险：Tavily API 已经返回 `raw_content`，`TavilyFastLaneProvider` 也已经把正文注册到 `TavilyPrefetchedContentRegistry`，并在 `SourceCandidate` 上写入 `hasPrefetchedContent / prefetchedContentRef / prefetchedRawContentLength` 等元数据，但这些正文未必会稳定进入最终证据链。

当前链路中有两个容易混淆的快速路径：

- `SearchCollectionTarget.collectedPage` 快速复用路径：`CollectorAgent` 只有在 `target.getCollectedPage() != null` 时，才会直接复用已采集页面。
- `TAVILY_PREFETCHED` 执行器路径：`CollectionTaskPackageBuilder` 会根据 candidate 上的 `fastLaneUsable=true / hasPrefetchedContent=true / prefetchedContentRef 非空` 把任务路由给 `TavilyPrefetchedExecutor`。

代码层面的断点是：

- `CollectionTargetSelector` 的 fallback target 只构造 `SearchCollectionTarget.builder().candidate(candidate).build()`，不会从 `TavilyPrefetchedContentRegistry` 取出 `raw_content` 转成 `collectedPage`。
- `CandidateVerifier` 的 Tavily fast-lane 验证目标也显式使用 `collectedPage(null)`，因此 `CollectorAgent` 中基于 `collectedPage` 的快速复用分支不会命中。
- 这不等于 Tavily 正文一定丢失，因为系统还存在 `TAVILY_PREFETCHED` 执行器路径；但它要求 candidate 元数据必须完整保留，并且最终 collection result 必须来自 `TavilyPrefetchedExecutor`。

因此，真正需要验证的不是“`collectedPage` 是否为空”一个点，而是 Tavily 预抓取正文是否最终被消费为正式采集结果。Task 66 的诊断必须补充以下字段：

- selected candidate 是否保留 `providerKey / fastLaneUsable / hasPrefetchedContent / prefetchedContentRef / prefetchedRawContentLength`。
- collection package 的 `primaryTool` 是否为 `TAVILY_PREFETCHED`。
- collection result 的 `executorType` 是否为 `TAVILY_PREFETCHED`。
- collection result 的 `qualitySignals` 是否包含 `TAVILY_RAW_CONTENT_READY` 和 `TAVILY_PREFETCHED_CONTENT_CONSUMED`。
- 落库后的 `evidence_source.full_content` 是否来自 Tavily raw content，而不是 Direct HTML、JinaReader 或 Playwright 抓到的首页鉴权壳。

如果上述任一条件不成立，就说明 Tavily 虽然完成了搜索和预抓取，但正文没有真正进入最终证据链。此时再做采集后 repair 只能兜底，不能替代对 fast-lane 交接链路的修复。

因此，本规格不要求把 Registry 内容强行塞进 `SearchCollectionTarget.collectedPage` 作为唯一方案。更稳妥的设计是：

- 保留 `TAVILY_PREFETCHED` 作为正式 collection executor。
- Selection / dedup / refresh 过程必须保留 fast-lane 元数据。
- Collection package builder 必须稳定把 fast-lane candidate 路由到 `TAVILY_PREFETCHED`。
- 审计必须记录“为什么没有使用 `TAVILY_PREFETCHED`”，避免静默回退到 Direct HTML、JinaReader 或 Playwright。
- 如果未来新增 Registry -> CollectedPage 桥，也只能作为复用优化，不能绕开 `TAVILY_PREFETCHED` 的单次消费和审计边界。

### 8.3 证据评分与可信度失真

Task 66 还暴露了评分体系的失真：导航壳、页脚链接和验证码提示这类低可用正文，可能被打出接近满分的 `qualityScore / relevanceScore`，并被标记为 `verified=true`、`trustTier=HIGH`。这会让后续 Extractor、Analyzer、Writer 和 Reviewer 误以为证据可靠。

当前失真来自三类表面信号被过度放大：

- `DirectHtmlReaderClient.calculateQualityScore` 主要按正文长度打分，正文长度超过 800 字即可获得较高基础分，并因包含链接获得加成；但导航菜单和页脚链接同样能制造大量文本。
- `SourceFamilyDirectDiscoveryPlanner` 会给 direct candidate 固定较高的 `qualityScore=0.92 / relevanceScore=0.95`；这属于候选规划期分数，不能直接当作最终证据正文质量分。
- `SourceCandidateRanker.resolveTrustTier` 会因为 `sourceType=OFFICIAL / DOCS / PRICING` 或官方域名，把来源可信度标为 `HIGH`；但“来源域名可信”不等于“本页正文可用”。
- `CandidateVerifier` 主路径已加入可用正文、归属、中介页、营销页和信号词检查；但 Tavily fast-lane 的 `verified=true` 仍偏向“跳过网络重验”，没有完成正文结构可用性判断。

更危险的是，系统缺少交叉校验。一个页面可以同时出现：

- collector 或 browser 链路提示 `content too thin / captcha / blocked / fallbackReason=HTTP content too thin`。
- candidate 元数据仍显示 `qualityScore=0.92 / relevanceScore=0.95 / trustTier=HIGH / verified=true`。

这类矛盾信号必须被识别为评分异常，而不是让高分覆盖低质事实。后续评分需要拆成至少三层：

- `sourceAuthenticityScore`：来源是否是官方、文档、协议、定价页。
- `contentUsabilityScore`：正文是否包含可用于报告的事实段落，而不是导航、页脚、登录、验证码或链接列表。
- `taskRelevanceScore`：正文是否覆盖当前任务维度，例如开放平台能力、API、SDK、用户管理、授权管理。

最终供下游排序和引用的 `evidenceUsabilityScore` 不能直接等于候选来源分。它应遵守负信号优先的封顶规则：

- 命中 `AUTH_OR_CAPTCHA_GATE` 时，`contentUsabilityScore` 最高只能到 `0.20`，正式引用可信度不得为 `HIGH`。
- 命中 `NAVIGATION_SHELL` 或 `LINK_FARM_WITHOUT_BODY` 时，`contentUsabilityScore` 最高只能到 `0.30`。
- 命中 `ROOT_ENTRY_PAGE` 且任务需要文档细页时，`evidenceUsabilityScore` 最高只能到 `0.45`。
- 出现 `content too thin / blocked / captcha / fallbackReason` 等失败信号时，必须写入 `scoreContradictions`，并阻止 `verified=true + trustTier=HIGH + high qualityScore` 组合静默通过。
- `qualitySignals` 不能一律加分。正向信号可以加分，负向信号必须降分或封顶。

因此，本轮修复不应只问“是不是官方域名”，而要问“这条证据能不能支撑报告中的一个可追溯结论”。官方首页壳可以是发现入口，但不能被当作高质量证据。

### 8.4 新增采集后证据质量门禁

需要在正式落库 `EvidenceSource` 之前增加采集后质量门禁。该门禁只回答一个问题：当前页面正文是否足以作为下游报告证据。

门禁应识别以下低质证据类型：

- `NAVIGATION_SHELL`：正文主要是导航、页头、页脚、站点入口和菜单。
- `AUTH_OR_CAPTCHA_GATE`：正文出现登录、注册、智能验证、验证码、网络超时、请重试等内容。
- `ROOT_ENTRY_PAGE`：URL 是根域或一层入口页，但任务目标需要文档细页。
- `LINK_FARM_WITHOUT_BODY`：正文主要是链接列表，缺少具体说明正文。
- `DUPLICATED_ENTRY_CONTENT`：同一竞品多个 URL 的正文高度重复。
- `WEAK_MAIN_CONTENT`：正文长度够，但命中任务关键词的有效段落很少。

这些状态不等同于采集失败，但必须标记为“不足以支撑报告”。它们应进入 `qualitySignals` 和 `issueFlags`，例如：

- `EVIDENCE_REPAIR_REQUIRED`
- `NAVIGATION_SHELL_DETECTED`
- `AUTH_GATE_DETECTED`
- `ROOT_ENTRY_WEAK_CONTENT`
- `LOW_TASK_KEYWORD_DENSITY`
- `DUPLICATED_ENTRY_CONTENT`
- `SCORE_CONTRADICTION_DETECTED`
- `HIGH_TRUST_LOW_USABILITY`

中文站点需要补充默认 blocked/auth signals，并且这些信号必须配置化，不能硬编码在单个类里。建议放在 `application.yml` 或独立 YAML 配置中，由 `SearchBrowserProperties` 或 `EvidenceQualityGateProperties` 绑定读取。

默认配置至少包括：

- `验证码`
- `智能验证`
- `检测中`
- `登录|注册`
- `请点击此处重试`
- `网络超时`
- `由极验提供技术支持`
- `完成身份信息填写`
- `去填写|去接受邀请`

其中 `由极验提供技术支持` 只是 task66/B 站样本命中的默认项，不应被当作唯一验证码供应商特征。配置需要允许继续追加顶象、网易易盾、腾讯防水墙等供应商信号。

这些信号命中时不应简单判为页面采集成功。若正文同时包含少量“产品服务、业务开放、能力”等泛词，也不能抵消鉴权壳信号。

### 8.5 Tavily Evidence Repair 触发规则

当采集后质量门禁判定 `EVIDENCE_REPAIR_REQUIRED` 时，应触发 Tavily repair，而不是直接把弱正文写入正式证据链。

Repair 不是独立孤岛，它依赖搜索采集加固链路先具备三段能力：

1. `Evidence Quality Gate` 识别受限页、导航壳、弱入口页，并生成可解释的 repair reason。
2. `PublicEvidenceRecoveryService` 根据 repair reason、同域锚点、字段证据路径和 CoverageContract 生成替代入口候选。
3. 现有验证/排序/采集链路对替代入口进行验证，并把强细页提升为正式采集目标。

如果 `PublicEvidenceRecoveryService` 未落地，Tavily repair 只能生成 query 建议，不能算完整修复。验收时必须区分：

- `REPAIR_QUERY_PROPOSED`：只生成了补强 query，还没有候选验证。
- `REPAIR_CANDIDATE_VERIFIED`：替代入口通过验证。
- `REPAIR_EVIDENCE_PROMOTED`：强细页已进入正式 EvidenceSource，并替换或降级原弱入口证据。
- `REPAIR_FIELD_PATH_COMPLETED`：某个字段的指定 evidence path 已经达到可用证据或可审计无公开证据结论。

触发条件：

- 当前目标是根域或入口页，并且正文命中导航壳特征。
- 正文包含“登录、注册、智能验证、验证码、网络超时”等鉴权或反爬提示。
- 正文出现大量文档链接，但缺少实际文档正文。
- 当前任务维度需要 API、SDK、开发者能力、用户管理、授权管理等细页证据。
- 当前字段证据计划存在未完成的 required evidence path，例如 `DOCS_API_GUIDE`、`DOCS_BILLING_OR_LIMITS` 或 `TERMS_OR_SERVICE_AGREEMENT`。
- 同一竞品多个采集结果正文高度重复，`distinctContentCount` 过低。

推荐 repair query：

- `{competitorName} {analysisDimension} 官方文档`
- `{competitorName} {fieldName} {evidencePath.queryIntent}`
- `{competitorName} 开放平台 用户管理 API`
- `{competitorName} 开放平台 授权管理 API`
- `site:{officialDomain} 用户管理 API`
- `site:{officialDomain} 开放平台 技术文档`
- `site:{officialDomain} doc guide API SDK`

对 B 站开放平台样例，系统应从首页正文中抽取“用户管理、授权管理、应用管理、开发者服务协议、管理规范”等可疑细页锚点，然后优先用 Tavily 搜：

- `B站开放平台 用户管理 API`
- `site:open.bilibili.com 用户管理 API`
- `site:open.bilibili.com/doc 用户管理`

预期目标是把 `https://open.bilibili.com/doc/4/feb66f99-...` 这类真实文档细页提升为正式采集目标，而不是继续使用 `https://open.bilibili.com` 首页壳。

### 8.6 Repair 后的证据替换规则

Tavily repair 成功后，系统应按以下规则处理原证据：

- 如果 repair 找到更强细页，原首页证据降级为 `BOOTSTRAP_CONTEXT` 或 `WEAK_ENTRY_PAGE`，不作为报告主证据。
- 新细页证据写入 `EvidenceSource`，并携带 `sourceUrls`、`tavilyQuery`、`repairReason`、`repairedFromEvidenceId`。
- 如果 repair 没找到更强证据，原证据可以保留在审计链路，但应标记 `EVIDENCE_NOT_COVERING`，不得支撑强结论。
- Writer 和 Reviewer 必须优先引用 repair 后的强证据，而不是入口页弱证据。

### 8.7 验收标准

- 首页导航壳不能再被静默当作高质量正式证据。
- Tavily fast-lane 候选进入采集阶段后，必须能在审计中看到 `primaryTool=TAVILY_PREFETCHED` 或明确的未使用原因。
- 成功消费 Tavily 预抓取正文时，collection result 必须包含 `executorType=TAVILY_PREFETCHED`、`TAVILY_RAW_CONTENT_READY` 和 `TAVILY_PREFETCHED_CONTENT_CONSUMED`。
- 如果 `prefetchedContentRef` 丢失、已消费或未路由到 `TAVILY_PREFETCHED`，必须输出可追踪质量信号，不能静默 fallback 到普通网页采集。
- 导航壳、鉴权墙、链接农场和 thin content 不能获得接近满分的 `qualityScore / relevanceScore`。
- 当采集失败信号与高分高可信元数据同时出现时，必须输出 `SCORE_CONTRADICTION_DETECTED`，并对最终 `evidenceUsabilityScore` 做封顶。
- `trustTier=HIGH` 只能说明来源域名或来源类型可信，不能单独让低可用正文进入报告主引用。
- `full_content` 命中验证码、登录注册、导航菜单时，必须出现明确质量信号。
- Tavily repair 至少能针对 B 站开放平台首页生成“用户管理 API”类补强查询。
- repair 命中的文档细页应进入正式采集目标。
- 原入口页可以保留在审计中，但不能作为报告核心功能、接口能力、风险判断的主引用。
- 如果 repair 失败，系统必须显式告诉下游“证据未覆盖”，而不是让 Writer 硬写报告。
- repair 成功或失败都必须回写对应字段的 `attemptedPaths`，避免下游只看到抽象的 `EVIDENCE_NOT_COVERING`。

## 9. 验收清单

- 计划阶段能输出每个字段的覆盖状态和阻断级别。
- `CoverageContract` 能输出 `taskMode / blockingLevel / targetEvidenceTypes / queryIntents / evidencePaths / minDistinctEvidenceCount / minimumAttemptedPaths`。
- `CoverageContract` 有唯一权威落点：顶层计划快照保存完整契约，节点 `nodeConfig` 只保存引用或裁剪视图；Agent 通过统一 Provider 读取。
- 冲突场景能按“显式模板 > 显式维度 > 显式用户约束 > 来源范围推导 > 系统默认”解释字段状态，并保留 `overrideReason`。
- 采集阶段能根据字段证据计划决定是否执行定价、协议、测评等 evidence path，而不是只按 sourceType 追加节点。
- 采集阶段能证明 required 字段对应 evidence path 和 query 确实执行；非 required 字段不会因为全局模板存在而被 reviewer 强检。
- `pricing=REQUIRED` 时，系统必须能区分 `CONFIRMED_FREE / IMPLICIT_IN_DOCS / REQUIRES_SALES_CONTACT / NO_PUBLIC_EVIDENCE_AFTER_SEARCH / EVIDENCE_NOT_COVERING`，不能只输出泛化缺证据。
- 抽取阶段只对本轮 required 字段要求非空。
- Reviewer 不再固定把 `pricing / weaknesses` 当作所有任务的 blocker。
- `sourceUrls` 仍是所有输出字段的强制追溯要求。
- 能力介绍型任务可以稳定通过。
- 标准全量报告仍能严格检查定价和风险。
- 没有公开证据的字段能被显式标记为 `NOT_APPLICABLE` 或 `EVIDENCE_NOT_COVERING`。
- Tavily 预抓取正文能通过 `TAVILY_PREFETCHED` 执行器进入正式 collection result，而不是只停留在 registry 和 candidate 元数据中。
- 当 Tavily fast-lane 候选未被消费时，审计中能看到 `prefetchedContentRef`、`primaryTool`、`executorType` 和未消费原因。
- 导航壳、鉴权墙、链接农场和 thin content 的最终证据可用性分不得超过低分封顶，不能因为官方域名或文本长度获得高可信主引用资格。
- 当 `fallbackReason / qualitySignals / failureKind` 与 `qualityScore / trustTier / verified` 自相矛盾时，系统能输出 `SCORE_CONTRADICTION_DETECTED`。
- `SourceFamilyDirectDiscoveryPlanner` 的规划期候选分不得直接等同于最终 `evidenceUsabilityScore`。
- `TAVILY_FAST_LANE_GATE_VERIFIED` 不得绕过采集后证据质量门禁。
- 中文鉴权/验证码壳命中时必须输出 `AUTH_OR_CAPTCHA_GATE` 或等价 issue flag。
- 中文 blocked/auth signals 必须配置化，默认包含 task66 命中项，但允许扩展不同验证码供应商。
- Tavily 轻量 `structuredBlocks` 分类必须有防噪门槛，导航壳不能因为出现“API/文档/定价”等导航词就生成高可信结构块。
- 采集后发现首页导航壳、验证码或链接堆叠内容时，能触发 Tavily repair 补强。
- Repair 验收必须区分 `REPAIR_QUERY_PROPOSED / REPAIR_CANDIDATE_VERIFIED / REPAIR_EVIDENCE_PROMOTED / REPAIR_FIELD_PATH_COMPLETED`，不能把只生成 query 误判为修复成功。
- repair 失败时弱证据不会被静默传给报告撰写作为强证据。

## 10. 暂不做事项

- 暂不一次性重构全部 Agent 协议。
- 暂不把第三方评价默认加入所有任务。
- 暂不要求能力介绍型任务输出定价和短板风险。
- 暂不允许模型基于官方能力介绍自行推断竞品劣势。
- 暂不把 Tavily 结构化块设计成复杂页面理解系统，先做轻量规则分类即可。

## 11. 推荐实施顺序

1. 新增 `CoverageContract` 数据结构、传递落点和统一 Provider。Planner 在顶层计划快照写完整契约，节点 `nodeConfig` 只保存引用或裁剪视图。
2. 新增 `AnalysisDimensionMappingCatalog`，把 `analysis_dimensions` 到字段、证据路径、来源家族、query intent 的映射集中管理，避免 `CoverageContractResolver` 长期依赖散落关键词。
3. 新增 `DimensionEvidencePlan` / `FieldEvidencePath` / `FieldEvidenceCoverage` 基础结构。先为 `coreFeatures` 和 `pricing` 建立最小字段路径模型，证明系统能从 source-first 切到 dimension-first。
4. 新增 `FieldEvidenceQueryPlanner`，把字段证据路径翻译成 Tavily query。Tavily 底层仍可使用来源家族，但上层调用必须带 `fieldName / evidencePathKey / queryIntent`。
5. 修改 Planner，让 `analysis_dimensions` 先生成字段状态，再为 `REQUIRED` 字段生成 evidence paths；没有 evidence path 的字段不得触发强制补采。
6. 修改 Extractor 和 Reviewer，让字段强检读取 `CoverageContract` 和 `FieldEvidenceCoverage`，先消除 task66 的 pricing/weaknesses 错位 blocker。没有这一步，后续修复上游证据仍会被旧 reviewer 固定强检误杀。
7. 修改 Collector，让 required 字段 evidence path 驱动真实 query 和采集目标；没有 required intent 的字段不得因为模板存在而被强检。
8. 增加采集后证据质量门禁，识别导航壳、鉴权墙、弱入口页、重复入口内容和低任务关键词密度。该门禁是 Tavily 结构化和 repair 的共同前置。
9. 给 Tavily 预抓取内容补轻量 `structuredBlocks`，并加入防噪门槛。该步骤可与第 6 步并行开发，但必须等第 6 步完成后再作为终审通过依据。
10. 重构评分语义，把来源可信度、正文可用性和任务相关性拆开，并加入负信号封顶与矛盾信号审计。
11. 验证 Tavily fast-lane 交接链路，确保 `raw_content -> registry -> candidate -> TAVILY_PREFETCHED -> collection result -> evidence_source` 全链路可追踪。
12. 补充配置化中文鉴权/验证码/壳页信号，覆盖 `智能验证`、`验证码`、`登录注册`、`由极验提供技术支持` 等 task66 命中问题，并预留供应商扩展。
13. 先落地搜索采集加固 Task 3：`PublicEvidenceRecoveryService`。它负责根据弱证据、CoverageContract 和字段 evidence path 生成替代入口候选，是 Tavily repair 从 query 建议走向正式候选验证的前置。
14. 禁止新链路调用 sourceType-only 旧签名；旧签名必须 `@Deprecated` 并写入 `FIELD_CONTEXT_MISSING_LEGACY_RECOVERY`。
15. 接入 `PublicShellRecoveryExtractor` 和证据替换/降级规则，在弱证据落库前搜索并提升真实细页；repair 验收必须达到 `REPAIR_EVIDENCE_PROMOTED` 或字段路径级 `REPAIR_FIELD_PATH_COMPLETED` 才算闭环。
16. 新增 `FieldAnswerSynthesizer`，把多条字段证据路径合成为可审计 `FieldAnswerConclusion`，记录 reasoning steps、sourceUrls、contradictions 和 recommendedNextAction。
17. 加入重复证据识别和细页提升规则，避免多个入口 URL 指向同一正文仍被当作互补证据。
18. 按分阶段测试策略逐步复测，先通过能力介绍型任务，再攻坚定价和风险。

实施依赖说明：

- 第 1-5 步是从 source-first 切到 dimension-first 的底座，优先级最高。没有字段证据计划和 query 翻译层，后续补源仍会退化成“按 sourceType 找页面”。
- 第 6 步是下游强检收口，必须同时读取字段状态和字段路径结论，否则会把 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`、`CONFIRMED_FREE` 等语义重新压扁成 `EVIDENCE_NOT_COVERING`。
- 第 8 步是证据质量基础设施，是第 9 步结构化防噪和第 13-15 步 repair 的前置。
- 第 9 步可以提前开发，但不能在 Reviewer 仍固定强检时单独作为 task66 通过标准。
- 第 13 步必须早于完整 Tavily repair 验收，否则系统只能产生 `REPAIR_QUERY_PROPOSED`，无法保证细页候选进入正式采集。
- 第 16 步是答案合成的审计边界，防止多源证据到最终结论的过程完全隐入 LLM prompt。

这套方案的目标是让系统先在擅长的领域稳定交付，再逐步扩大边界。测试阶段要避免用最高难度标准误判系统整体能力，也要避免为了通过测试而放松无幻觉和来源追溯红线。
