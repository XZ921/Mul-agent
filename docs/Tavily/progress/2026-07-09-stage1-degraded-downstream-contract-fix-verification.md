# 2026-07-09 阶段1降级下游契约修复验证记录

## 验证范围

- 目标计划：`docs/Tavily/task/2026-07-09-19-stage1-degraded-downstream-contract-root-cause-plan.md`
- 当前只覆盖非 live 验证：
  - Task 6：Task101 风格集成夹具
  - Task 7 Step1-3：定向回归、广义回归、预算护栏
- 明确不执行：
  - `Task 7 Step4` 真实 E2E

## 修复前失败链路

来自 live E2E 的核心症状：

```text
核心四字段已 TRACEABLE
sourceUrls 红线已满足
writer 仍报告 pricing/strengths/weaknesses/conclusion/report_conclusion 缺引用
reviewer 将 structuredBlocks 缺口硬编码抬升为 BLOCKER
任务被统一收口为 STOPPED
报告 deliveryStatus 被统一收口为 BLOCKED
```

## 追加更新：真实 E2E 复测（Task 7 Step4-5）

### 当前阶段

当前阶段：真实 E2E 已执行，问题已记录
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检验收：已完成（真实 E2E 已落盘，结论为未通过）

### 本次运行

- 启动方式：`mvn -pl backend spring-boot:run`
- 端口：`9093`
- 运行现场目录：`tmp/stage1-degraded-live-e2e-rerun-20260709-155144`
- 任务 ID：`102`
- 报告 ID：`93`
- 输入沿用主计划 `Task 7 Step4` 的双竞品 payload：

```json
{
  "taskName": "阶段1降级契约双竞品 E2E：Notion 与 Airtable 下游契约复测",
  "subjectProduct": "企业级知识协作与低代码工作台",
  "competitorNames": ["Notion", "Airtable"],
  "competitorUrls": ["https://www.notion.so", "https://www.airtable.com"],
  "analysisDimensions": ["产品概述", "市场定位", "目标用户", "核心功能", "价格策略"],
  "sourceScope": ["官网", "客户案例", "产品文档", "公开测评"],
  "reportLanguage": "中文",
  "reportTemplate": "阶段1首报"
}
```

### 终态结果

```text
taskStatus = FAILED
errorMessage = 质量闭环未达到通过条件，请检查评审结果
canViewReport = true
canViewDraftReport = true
completedNodes = 14/14
nodeGroups = SUCCESS=9; SUCCESS_DEGRADED=5
deliveryStatus = NEEDS_EVIDENCE
readyForDelivery = false
qualityScore = 34
qualityPassed = false
deliverySummary.blockerCount = 0
deliverySummary.evidenceGapCount = 2
```

### 与本轮修复目标相关的正向信号

- 本次终态不再回到 Task18/Task19 修复前的 `STOPPED + BLOCKED` 组合。
- writer/citation 没有再把 `pricing / strengths / weaknesses / report_conclusion` 误提升为主阻断：
  - `missingCitationSections = positioning,targetUsers`
  - `strengths / weaknesses / report_conclusion` 仍保留为 warning/audit 侧可见问题
- reviewer 终审诊断里，结构化覆盖缺口被归为 `WARNING`，没有出现“optional/generated 字段误杀为 blocker”的旧问题。

### 暴露问题 1：上游真实证据能力退化，extract_schema 最终只产出 1 个竞品

关键证据：

```text
collect_sources_01_01 / 01_02 / 01_03（Notion）
  status = SUCCESS_DEGRADED
  sourceCount = 0
  sourceUrlsCount = 1 / 4 / 5
  issueFlags = SOURCE_URLS_BACKFILLED

extract_schema.outputData
  totalCompetitors = 1
  successCount = 1
  results 仅包含 Airtable
```

根因指向：

- Notion 三个 collector 节点都只回填了 `sourceUrls`，没有形成可供 extractor 消费的可用 evidence payload。
- `extract_schema` 继续在“collector quorum ready but degraded”的前提下向下执行，但最终只保留了 Airtable 单竞品结果。
- 因此本次失败主因已经不是 Task19 想修的“下游 optional/generated 契约误杀”，而是更前面的真实证据输入不足。

### 暴露问题 2：Airtable DOCS collector 存在明显长尾与状态迟滞

关键证据：

```text
collect_sources_02_02
  startedAt   = 2026-07-09 15:54:27
  completedAt = 2026-07-09 16:05:40
  status      = SUCCESS
```

补充证据：

```text
最后一条 COLLECTOR agent 日志时间 = 2026-07-09 15:57:27
任务在 16:04 轮询时仍停留在 “Airtable - DOCS采集 / RUNNING”
真正推进到 extract_schema 的时间 = 2026-07-09 16:05:41
```

根因指向：

- 该节点不是 retry/人工介入，而是单次执行长时间占用。
- “agent 日志已停，但节点状态很久才完成”的表现说明 collector -> node closeout / workflow event 消费之间还存在迟滞窗口。
- 这会继续拖慢阶段1首报，即使 quorum 已满足，也没有更早收口。

### 暴露问题 3：任务收口与 deliverySummary 语义仍有新的不一致点

关键证据：

```text
finalReview.nodeStatus = SUCCESS
finalReview.passed = false
finalReview.requiresHumanIntervention = true
finalReview.diagnoses 仅有 1 条 WARNING
deliverySummary.blockerCount = 0
deliverySummary.evidenceGapCount = 2
taskStatus = FAILED
```

当前判断：

- 这次 `FAILED` 不是旧的 optional/generated blocker 误杀，而是质量闭环因为真实证据不足没有通过。
- 但从 API 语义上看，`blockerCount = 0`、终审只有 `WARNING`，任务却直接 `FAILED`，需要进一步确认：
  - 这是否是预期的“低分 + requiresHumanIntervention => FAILED”规则；
  - 还是 `deliverySummary / finalReview / taskStatus` 对同一失败原因的表达仍未统一。

### 暴露问题 4：最终报告只剩 Airtable，且核心字段真实缺口仍然存在

关键证据：

```text
competitorKnowledges 仅包含 Airtable
Airtable coverage:
  summary      = TRACEABLE
  positioning  = EMPTY
  targetUsers  = EMPTY
  coreFeatures = TRACEABLE
  pricing      = TRACEABLE
  strengths    = EMPTY
  weaknesses   = EVIDENCE_NOT_COVERING
```

结论：

- 本次 live E2E 未达到“核心四字段全部 TRACEABLE”通过前提。
- 因此这一次不能把失败归咎为 Task19 下游契约回退；真实上游证据能力仍不足以支撑阶段1双竞品首报。

### 下一步建议

1. 先定位 `SOURCE_URLS_BACKFILLED -> extractor only one competitor` 的边界问题，确认 Notion 侧 evidence payload 为什么全部为空。
2. 单独复盘 `collect_sources_02_02` 的 11 分钟长尾与 8 分钟状态迟滞，确认是 collector 内部阻塞，还是 workflow closeout/event 消费延迟。
3. 在确认上游真实缺证后，再判断 `taskStatus=FAILED` 与 `deliverySummary.blockerCount=0` 的收口语义是否需要继续统一。

本轮修复目标不是压低标准，而是让下游各层统一复用 stage1 scope 裁决：

```text
CORE -> 仍可阻断
ENHANCEMENT -> warning/audit
GENERATED -> rewrite-only/warning
UNKNOWN_AUDIT -> audit
```

## 非 live 集成验证

### 正例：核心字段已可追溯，增强字段/自动结论仍有缺口

- 用例：`shouldDeliverDegradedReadyWhenCoreTraceableButOptionalAndGeneratedCitationGapsExist`
- 夹具特征：
  - 双竞品：`Notion` / `Airtable`
  - writer 保留 `missingCitationSections = pricing,strengths,weaknesses,conclusion,report_conclusion`
  - reviewer 只保留 generic structured traceable gap + enhancement/generated citation gaps
  - `sourceUrls >= 5` 且 `distinct domains >= 2`
  - `qualityScore = 65`
- 核心断言：
  - `TaskResponse.status = SUCCESS`
  - `TaskResponse.canViewReport = true`
  - `TaskResponse.statusSummary` / `interventionSummary` 明确包含“降级 + 人工复核”
  - `ReportResponse.deliverySummary.deliveryStatus = DEGRADED_READY`
  - `ReportResponse.qualityPassed = false`
  - `ReportResponse.deliverySummary.blockerCount = 0`
  - `ReportResponse.deliverySummary.evidenceGapCount = 0`
  - `ReportResponse.reportDiagnosis.blockerCount = 0`
  - `writerEvidenceSummary` 仍保留 optional/generated 缺口与 issue flags，保证审计可见

### 反例：真实 core 字段 `targetUsers` 仍缺证

- 用例：`shouldStillBlockWhenCoreTargetUsersNotTraceable`
- 夹具特征：
  - writer 仍产出可查看草稿
  - reviewer 明确给出 `targetUsers` core blocker
  - 同时保留 `pricing` optional gap，防止“错误 blocker 来源被 optional 字段掩盖”
  - `qualityScore = 63`，排除“只是分数低于 60”这种假阳性
- 核心断言：
  - `TaskResponse.status = STOPPED`
  - `TaskResponse.interventionSummary` 不带“降级”语义
  - `ReportResponse.deliverySummary.deliveryStatus != DEGRADED_READY`
  - `ReportResponse.deliverySummary.blockerCount > 0`
  - `ReportResponse.reportDiagnosis.sections` 明确包含 `targetUsers`

## 已执行命令与结果

### Task 6 集成验证

```powershell
mvn -pl backend "-Dtest=StageOneDegradedContractIntegrationTest" test
```

结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Task 7 Step1 定向下游测试集

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,WriterCitationGapInspectorTest,CitationAgentTest,ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

结果：

```text
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Task 7 Step2 Task18 相关广义回归

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,CollectorEvidenceReadinessPolicyTest,CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,ReportDiagnosisAssemblerTest,ReportServiceTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

第一次结果：

```text
ReportServiceTest 2 处失败
1. shouldNotMarkDegradedReadyWhenScoreIsBelowSixtyOrBlockerExists
2. shouldUpgradeDeliverySummaryWhenStructuredEvidenceGapsAreDiagnosed
```

根因：

```text
失败来自旧测试夹具仍把 generated/enhancement 场景当成主阻断：
- blocker 夹具仍用 conclusion
- structured evidence 夹具仍用 pricing/定价对比
```

处理：

```text
仅更新测试夹具，不修改生产逻辑：
- conclusion -> targetUsers
- 定价对比 structured gap -> targetUsers structured gap
```

复跑结果：

```text
Tests run: 205, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Task 7 Step3 预算护栏测试集

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest" test
```

结果：

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 真实 E2E 前待验证项

非 live 验证已完成，当前只剩：

1. `Task 7 Step4` 真实 E2E
2. `Task 7 Step5` 将真实 E2E 任务结果回填进度文档

## 预期真实 E2E 通过标准

```text
如果核心四字段 TRACEABLE 且 sourceUrls 红线满足：
  taskStatus = SUCCESS
  canViewReport = true
  deliveryStatus = DEGRADED_READY 或 READY
  qualityScore >= 60
  qualityPassed 可以为 false
  blockerCount = 0
  core evidenceGapCount = 0

如果 targetUsers 或其他核心字段仍缺证：
  允许 BLOCKED / STOPPED
  但 blocker 必须指向真实 core 字段
  不能再由 pricing / strengths / weaknesses / conclusion / report_conclusion 误杀
```
