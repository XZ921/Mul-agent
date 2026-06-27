# AnalysisReasoning 最小稳固方案 - 2026-06-26

当前阶段：分析推理最小稳固切片自动化收口
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 方案边界确认：已完成
- [x] 实施计划：已完成
- [x] 代码实施：已完成
- [x] 自动化验证：已完成
- [ ] 实链验证：待执行

## 1. 背景结论

本方案承接 3.5 收敛决策：当前不进入 4.x，不接 Tavily，不扩大 `pendingActions`，也不把 `AnalysisReasoning / ReportWriting / ConversationCollaboration / DeliveryAudit` 四条链路做全量实现。

`AnalysisReasoning` 的诊断结论已经明确：Analyzer 不是没有接上 3.4 协议，而是链路内仍存在分析结论密度和章节证据聚合质量问题。`AnalysisResult` 已经具备 `sourceUrls / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState / sectionEvidenceBundles` 等正式字段，`AnalyzerSuggestionAssembler` 也已经能把分析缺口转为 `AgentSuggestion -> OrchestrationDecision`。因此本轮只做 Analyzer 内部最小质量补丁。

## 2. 目标

在不改变协作 runtime 的前提下，让 Analyzer 输出更稳定地满足下游 Writer 和 Citation Agent 的最小证据需求：

1. Analyzer Prompt 明确要求逐维度输出结构化分析，并要求无证据时留空或标记缺口，不能用概览或泛泛判断填充核心字段。
2. Analyzer 章节证据束只聚合与当前章节匹配的 `DownstreamEvidenceView`，避免把同一批 evidence view 粗暴塞进所有章节。
3. 对 views-only 场景，如果证据视图和章节匹配且有来源，则对应章节可以视为可追溯；如果不匹配，则保留 `SECTION_EVIDENCE_GAP`。
4. 保持现有 `AnalysisResult` 契约不扩字段，继续让编排层通过既有 `analysisGapSeverity / analysisEvidenceState / sourceUrls` 消费缺口。

## 3. 非目标

1. 不进入 4.x runtime，不改固定 DAG、不引入自由智能规划器。
2. 不修改 `AgentSuggestion / OrchestrationDecision / DecisionPolicyResult / DynamicPlanMutation` 协议。
3. 不拆 `CompetitorAnalysisAgent` 为新的子域服务，本轮只允许小范围私有方法调整。
4. 不新增数据库字段，不新增 Flyway migration。
5. 不改前端页面，不改 report/export DTO 的公开契约。
6. 不接 Tavily，不改变搜索与采集 owner。
7. 不把 `AnalysisReasoning` 标记为实链验证完成。

## 4. 设计方案

### 4.1 Prompt 最小加固

当前 `analyzer` 默认模板只包含本方产品、分析维度和竞品数据，缺少输出契约约束。本轮优先新增 `backend/src/main/resources/prompts/analyzer.txt`，通过现有 `PromptTemplateService.registerTemplate(...)` 覆盖内联默认模板。

模板需要明确：

1. 只能返回 JSON 对象。
2. 顶层必须包含 `overview / featureComparison / positioningComparison / pricingComparison / targetUserComparison / strengthsSummary / weaknessesSummary / opportunities / risks / recommendations / sourceUrls`。
3. 每个核心分析字段必须基于 `competitorData` 中的 `sourceUrls / evidenceCoverage / downstreamEvidenceViews / structuredBlocks`。
4. 某个维度缺证据时，对应字段返回 `null` 或空字符串，并在 `issueFlags` 中保留缺口，不允许用泛泛结论填充。
5. `sourceUrls` 只放真实输入中出现过的来源，不允许生成新 URL。

### 4.2 章节证据束匹配规则

`CompetitorAnalysisAgent.buildSectionEvidenceBundles(...)` 当前会把同一竞品的 `DownstreamEvidenceView` 同时加入所有章节，造成 Writer 看到过粗的章节证据。本轮增加轻量匹配规则。匹配优先级固定为：

1. `structuredBlocks[*].blockType` 结构块类型。
2. `qualitySignals[*]` 质量信号。
3. `title / content / structuredBlocks[*].summary` 文本关键词兜底。

结构块类型和质量信号需要先归一化为大写 token，再做精确或前缀匹配；正文关键词只作为兜底，不能替代明确结构信号。

| 分析章节 | 结构块类型优先匹配 | 质量信号匹配 | 文本关键词兜底 |
| --- | --- | --- | --- |
| `features` | `FEATURE_LIST / FEATURE_BLOCK / FEATURE_*` | `FEATURE / FEATURE_*` | feature / capability / 功能 / 能力 |
| `pricing` | `PRICING_BLOCK / PRICING_*` | `PRICING / PRICING_*` | pricing / price / plan / 定价 / 价格 / 套餐 |
| `targetUsers` | `TARGET_USER / USER_SEGMENT / USER_*` | `TARGET_USER / USER_SEGMENT / USER_*` | user / customer / audience / 用户 / 客户 / 受众 |
| `positioning` | `POSITIONING / MARKET / SEGMENT` | `POSITIONING / MARKET / SEGMENT` | positioning / market / segment / 定位 / 市场 |
| `strengths` | `STRENGTH / ADVANTAGE / PRO` | `STRENGTH / ADVANTAGE / PRO` | strength / advantage / pros / 优势 / 亮点 |
| `weaknesses` | `WEAKNESS / RISK / LIMITATION / CON` | `WEAKNESS / RISK / LIMITATION / CON` | weakness / risk / limitation / cons / 短板 / 风险 / 限制 |
| `overview` | 不直接消费明确章节结构块 | 不直接消费明确章节质量信号 | 仅接受有来源且无法匹配任一具体章节的通用证据 |

匹配规则只用于减少错误聚合，不用于生成新事实。匹配失败时，该 evidence view 仍保留在 `downstreamEvidenceViews` 顶层审计字段里，但不会进入不相关章节的 `evidenceFragments`。

### 4.3 views-only 可追溯口径

当 `extract_schema` 只提供 `downstreamEvidenceViews`，没有 draft bundle 或 repository coverage 时，Analyzer 当前可能同时出现“有来源片段但章节仍标缺口”的灰区。本轮调整为：

1. “view 与章节匹配”必须按 4.2 的优先级判定：先看结构块类型，再看质量信号，最后才看标题、正文和结构块摘要关键词。
2. 如果 view 与章节匹配，且 view 或片段存在 `sourceUrls`，该章节对当前竞品视为 traceable。
3. 如果 view 只匹配其他具体章节，即使有来源，也不能让当前章节通过。
4. `overview` 只接收无法匹配任一具体章节的通用证据；pricing / features / positioning 等明确证据不能进入 `overview` 作为噪声来源。
5. 如果 view 缺少来源，则保留 `MISSING_SOURCE_URL / SECTION_EVIDENCE_GAP`。

这能减少“pricing 明明有结构化证据，但 pricing bundle 仍被标缺口”的误判，同时避免“pricing 证据污染 feature 章节”。

## 5. 执行计划

| 步骤 | 核心目标 | 预期耗时 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- |
| Step 1 | 为 Analyzer prompt 契约补测试，确认模板包含逐维度、sourceUrls 和缺证据留空规则 | 10 分钟 | 本方案确认 | 已完成 |
| Step 2 | 新增 `prompts/analyzer.txt`，让默认模板具备稳定输出约束 | 10 分钟 | Step 1 红灯 | 已完成 |
| Step 3 | 为章节证据束匹配补 Analyzer 测试，覆盖 pricing view 不污染 feature bundle | 20 分钟 | 现有 `CompetitorAnalysisAgentTest` 可运行 | 已完成 |
| Step 4 | 在 `CompetitorAnalysisAgent` 内实现私有匹配方法和 views-only traceable 判定 | 30 分钟 | Step 3 红灯 | 已完成 |
| Step 5 | 跑 Analyzer、Writer、Orchestrator 相关聚焦回归，确认协议不回归 | 20 分钟 | Step 4 完成 | 已完成 |
| Step 6 | 更新总路线图进度，不把实施和实链验证提前标绿 | 10 分钟 | 自动化通过 | 已完成 |

### 5.1 方案步骤与实施任务对照

为避免方案 `Step` 和实施计划 `Task` 后续追踪混淆，统一按下表对照：

| 方案步骤 | 对应实施计划任务 | 说明 |
| --- | --- | --- |
| Step 1-2 | Task 1: Analyzer Prompt Contract | 先补 Prompt 契约红灯测试，再新增 `prompts/analyzer.txt` |
| Step 3-4 | Task 2: Section Evidence View Matching | 先补章节证据束匹配红灯测试，再实现私有匹配规则 |
| Step 5-6 | Task 3: Regression and Roadmap Closure | 跑聚焦回归、协议保护回归、格式检查，并回链路线图 |

## 6. 验收口径

自动化验收至少覆盖：

1. `PromptTemplateService` 渲染的 `analyzer` 模板包含明确 JSON 字段、`sourceUrls` 和缺证据留空规则。
2. 只有 pricing 结构化证据时，`pricing` bundle 聚合该来源，`features` bundle 不应包含同一 pricing 片段。
3. views-only pricing 证据有来源时，`pricing.missingFields` 不应继续包含 `pricingComparison`。
4. 无来源或无匹配证据时，章节继续保留 `SECTION_EVIDENCE_GAP`，不能静默放行。
5. `AnalyzerSuggestionAssemblerTest` 继续通过，证明本轮没有改坏 3.4 协议消费。
6. `ReportWriterAgentTest` 继续通过，证明 Writer 对 Analyzer 输出的兼容逻辑没有被破坏。

建议命令：

```powershell
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest,AnalyzerSuggestionAssemblerTest,ReportWriterAgentTest" test
```

如新增 `PromptTemplateServiceTest` 或扩展已有模板测试，应一并加入聚焦命令。

## 7. 风险与回滚

1. Prompt 变严格后，Analyzer 可能更频繁输出缺口。该结果可接受，因为本轮目标是减少无证据分析继续流向 Writer，而不是强行提高通过率。
2. 章节匹配规则可能漏掉少量弱相关证据。规则应保持保守，宁可标缺口，也不把不相关来源塞入章节证据束。
3. 如果真实任务显示匹配规则过窄，后续只能补充匹配词或结构块类型，不应回退到“所有 evidence view 进入所有章节”的粗粒度聚合。
4. `filterEvidenceViewsForSection` 会按竞品和章节重复匹配 evidence view，复杂度约为 `O(competitorCount * sectionCount * viewCount)`；当前任务规模下可接受，后续只有在 evidence view 数量显著增长时才考虑预索引。
5. `evidenceViewHaystack` 或等价匹配辅助方法会在匹配时构造小字符串并做大小写归一化，存在轻微 GC 压力；本轮优先保持实现简单，避免为了微优化扩大改动面。

## 8. 路线图状态

本方案完成后，`AnalysisReasoning` 状态应更新为：

- 诊断：已完成。
- 方案：已完成，但仅代表最小稳固切片，不代表分析推理全量重构。
- 实施：已完成自动化收口，但未完成真实链路验证。
- 实链验证：待执行。

## 9. 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -pl backend "-Dtest=PromptTemplateServiceTest,CompetitorAnalysisAgentTest,AnalyzerSuggestionAssemblerTest,ReportWriterAgentTest" test` | PASS |
| `Get-ChildItem -Recurse -File backend/src/test/java -Filter *.java \| Where-Object { $_.Name -in @('OrchestrationDecisionServiceTest.java','DagExecutorTest.java') } \| Select-Object -ExpandProperty FullName` | PASS |
| `mvn -pl backend "-Dtest=AnalyzerSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest" test` | PASS |
| `git diff --check -- backend/src/main/resources/prompts/analyzer.txt backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md` | PASS（仅有 Windows 工作区 LF/CRLF 提示，无空白错误） |
