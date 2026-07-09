# 2026-07-09 阶段1降级下游契约修复执行进度

## 当前阶段

当前阶段：[Task 7 非 live 验证完成，真实 E2E 前停止]
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成（代码与测试已落地）
- [x] 质检验收：非 live 验证已完成，真实 E2E 待执行

## 执行范围

- 主计划文档：`docs/Tavily/task/2026-07-09-19-stage1-degraded-downstream-contract-root-cause-plan.md`
- 执行顺序：`Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6 -> Task 7 Step1-3`
- 停止边界：不执行 `Task 7 Step4` 真实 E2E，只在其前完成代码、单测、集成测试与非 live 回归
- 分支策略：直接在 `master` 修改，不提交，由用户自行提交

## 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | ---: | --- | --- |
| Task 1 | 扩展 `StageOneFirstReportPolicy` 为下游 issue scope 唯一入口 | 45 分钟 | 主计划已确认 | 已完成 |
| Task 2 | 收敛 reviewer 诊断与 structuredBlocks 旁路 | 90 分钟 | Task 1 | 已完成 |
| Task 3 | 收敛 writer/citation 交付分母，仅让 stage1 delivery scope 参与 | 90 分钟 | Task 1 | 已完成 |
| Task 4 | 收敛 report diagnosis / delivery summary | 60 分钟 | Task 2、Task 3 | 已完成 |
| Task 5 | 收敛任务级状态，区分人工停机与降级可查看 | 60 分钟 | Task 4 | 已完成 |
| Task 6 | 新增 Task101 风格非 live 集成夹具与验证记录 | 75 分钟 | Task 1-5 | 已完成 |
| Task 7 | 跑非 live 回归与预算护栏，并在真实 E2E 前停止 | 90 分钟 | Task 6 | 已完成（Step1-3） |

## 关键上下文确认

- 本轮主旨是把 `StageOneFirstReportPolicy` 真正变成下游裁决的唯一事实源，不是靠调低分数线、扩大采集预算或给 `STOPPED` 打例外补丁。
- `sourceUrls` 红线保持不变：仍要求可追溯来源充足，不能因为下游契约放行而放松可溯源约束。
- “降级可交付”采用双轴语义：
  - 任务轴：`TaskResponse.status = SUCCESS`
  - 交付轴：`ReportResponse.deliverySummary.deliveryStatus = DEGRADED_READY`
  - 同时保持 `qualityPassed = false`
- 本轮不新增第二套 scope 映射，不从 report 文本反推 requested dimensions，不扩 DTO 承载额外交付覆盖率。

## reviewer 诊断清点结论

| 诊断入口 | 当前处理方式 | 结论 |
| --- | --- | --- |
| `appendStructuredEvidenceDiagnoses(...)` | 已改为先统一映射 stage1 scope，再决定 `ERROR/WARNING` | 已收口 |
| `isDiagnosisPassed(...)` / `requiresHumanIntervention(...)` | blocker 语义已收敛到真实 core gap | 已收口 |
| writer/citation 可见缺口回流 reviewer | 仅 enhancement/generated/audit 可见，不再主阻断 | 已收口 |
| task/report 下游 blocker 统计 | 统一复用 `StageOneFirstReportPolicy` scope 裁决 | 已收口 |

## 停顿记录

### 停顿记录：2026-07-09 19:00
- 已完成：
  - 复核主计划、执行边界和 `master` 直接修改要求。
  - 建立执行进度文档，固定“真实 E2E 前停止”的边界。
- 当前测试：
  - 暂未开始。
- 测试结果：
  - 暂无。
- 暴露问题：
  - `StageOneFirstReportPolicy` 还缺下游 issue scope、generated section 与 requested dimensions 可见性裁决能力。
- 下一步：
  - 进入 Task 1，先补红灯测试，再最小实现。

### 停顿记录：2026-07-09 19:35
- 已完成：
  - Task 1：补齐 `FirstReportIssueScope / FirstReportIssueContext`、generated section、requested dimensions 统一裁决。
  - Task 2：完成 reviewer 诊断入口清点，收敛 `missing_structured_evidence` 旁路与评分口径。
- 当前测试：
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest" test`
  - `mvn -pl backend "-Dtest=QualityReviewAgentTest,QualityReviewAgentCoverageContractTest" test`
- 测试结果：
  - 全部通过。
- 暴露问题：
  - writer / citation 仍可能把增强字段与自动结论章节重新带回交付主链。
- 下一步：
  - 进入 Task 3，收敛 writer / citation 的交付分母。

### 停顿记录：2026-07-09 15:22
- 已完成：
  - Task 3：收敛 writer/citation delivery denominator，只让 stage1 delivery scope 参与阻断。
  - Task 4：收敛 `ReportDiagnosisAssembler` 与 `ReportService` 的 blocker/evidenceGap 统计和降级摘要。
  - Task 5：完成 `NodeExecutionRecoveryPolicy`、`TaskNodeViewAssembler`、`TaskProgressSnapshot` 的降级可交付收口与展示文案。
- 当前测试：
  - `mvn -pl backend "-Dtest=WriterCitationGapInspectorTest,CitationAgentTest" test`
  - `mvn -pl backend "-Dtest=ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest" test`
  - `mvn -pl backend "-Dtest=NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,TaskProgressSnapshotTest" test`
- 测试结果：
  - 全部通过。
- 暴露问题：
  - 需要一个 Task101 风格的非 live 集成夹具，把“降级可交付正例”和“真实 core blocker 反例”同时锁住。
- 下一步：
  - 进入 Task 6，补齐集成测试与验证文档。

### 停顿记录：2026-07-09 15:31
- 已完成：
  - Task 6：在 `StageOneDegradedContractIntegrationTest` 中补齐 Task101 风格正反例。
  - 正例锁定：核心字段可追溯、增强字段/自动结论仍有引用缺口时，任务收口为 `SUCCESS`，交付收口为 `DEGRADED_READY`，`qualityPassed=false`。
  - 反例锁定：`targetUsers` 等真实 core 字段缺证时，任务继续 `STOPPED`，报告继续阻断。
- 当前测试：
  - `mvn -pl backend "-Dtest=StageOneDegradedContractIntegrationTest" test`
- 测试结果：
  - 通过。
- 暴露问题：
  - 暂未发现新的根因级回退；接下来需要跑 Task 7 的定向回归、广义回归与预算护栏验证。
- 下一步：
  - 执行 Task 7 Step1-3，完成非 live 验证后在真实 E2E 前停止总结。

### 停顿记录：2026-07-09 15:41
- 已完成：
  - Task 7 Step1：完成下游定向测试集验证。
  - Task 7 Step2：完成 Task18 相关广义回归集验证，并把 `ReportServiceTest` 中两处旧契约夹具改为真实 core blocker 场景：
    - `conclusion -> targetUsers`
    - `定价对比 structured gap -> targetUsers structured gap`
  - Task 7 Step3：完成预算护栏测试集验证，确认没有通过扩大 field evidence/Tavily 查询来掩盖下游契约问题。
- 当前测试：
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,WriterCitationGapInspectorTest,CitationAgentTest,ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test`
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,CollectorEvidenceReadinessPolicyTest,CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,ReportDiagnosisAssemblerTest,ReportServiceTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test`
  - `mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest" test`
- 测试结果：
  - 定向集：`77` 个测试全部通过。
  - 广义回归集：`205` 个测试全部通过。
  - 预算护栏集：`28` 个测试全部通过。
- 暴露问题：
  - 未发现新的根因级回退。
  - 广义回归中暴露的两处失败来自旧测试夹具仍使用 enhancement/generated blocker 假设，已改为 core blocker 夹具并重新通过。
- 下一步：
  - 按用户要求在真实 E2E 前停止，总结已完成工作、下一步动作和剩余未做项。

### 停顿记录：2026-07-09 16:15
- 已完成：
  - 重启 `backend`，在 `9093` 上完成真实 E2E。
  - 复用主计划 `Task 7 Step4` 的既定双竞品输入，完整执行 `preview -> create -> execute -> poll -> report`。
  - 将现场证据落盘到 `tmp/stage1-degraded-live-e2e-rerun-20260709-155144`，包括 task / nodes / report / replay / agent logs。
- 当前测试：
  - 真实 E2E，`taskId=102`
- 测试结果：
  - 终态为 `FAILED`
  - `completedNodes = 14/14`
  - `nodeGroups = SUCCESS=9; SUCCESS_DEGRADED=5`
  - `canViewReport = true`
  - `deliveryStatus = NEEDS_EVIDENCE`
  - `qualityScore = 34`
- 暴露问题：
  - Notion 三个 collector 都是 `SUCCESS_DEGRADED + SOURCE_URLS_BACKFILLED`，但 `sourceCount=0`，extractor 最终只产出 1 个竞品（Airtable）。
  - `collect_sources_02_02` 从 `15:54:27` 跑到 `16:05:40`，存在明显长尾；最后一条 collector agent 日志停在 `15:57:27`，但节点状态直到 `16:05:40` 才真正完成，说明 collector closeout / workflow event 存在迟滞。
  - `finalReview` 只有 `WARNING`、`deliverySummary.blockerCount=0`，但任务仍直接 `FAILED`，需要继续核对 taskStatus / finalReview / deliverySummary 的收口语义是否完全一致。
  - 最终报告只剩 Airtable，`positioning / targetUsers / strengths` 为空，`weaknesses = EVIDENCE_NOT_COVERING`，说明这次失败已回到真实证据缺口，而不是 optional/generated 契约误杀。
- 下一步：
  - 优先定位 `SOURCE_URLS_BACKFILLED -> extractor only one competitor` 的边界问题，确认 Notion evidence payload 为何全部为空。
  - 单独复盘 `collect_sources_02_02` 的 11 分钟长尾与状态迟滞。
  - 在确认上游真实缺证后，再决定是否继续统一 `FAILED / NEEDS_EVIDENCE / blockerCount=0` 的收口语义。
