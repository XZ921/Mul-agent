# Task18 阶段1降级语义统一契约 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把阶段 1 “友好降级”从局部执行补丁提升为全链路统一契约，确保首报只被核心字段和可追溯来源阻塞，`pricing / strengths / weaknesses / risk` 缺失只能延期、审计、降级展示，不能触发重试耗尽、人工介入、`STOPPED` 或报告不可交付。

**Architecture:** 新增一个“阶段 1 首报契约策略”作为唯一事实源。上游默认输入、`CoverageContract`、字段 query、来源 scope、collector hard deadline、collector quorum、Extractor / Analyzer / Writer / Reviewer / ReportService 都只消费这个契约，不再各自硬编码“标准版全字段完整”。先用契约级 characterization tests 锁住主旨，再逐层替换旧判断，最后跑一组无 live Tavily 的降级链路测试与必要单测。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Jackson, React/TypeScript display tests.

---

## 1. 降级真正主旨

阶段 1 降级不是“失败后换个状态名”，也不是“所有字段都要有，只是分数线低一点”。真正主旨是：

首版报告只为核心认知闭环负责：

```text
summary
positioning
targetUsers
coreFeatures
sourceUrls 红线
```

这些内容足以回答“这个竞品是什么、面向谁、核心能力是什么、和本方产品如何定位”，并且每个判断都可回溯到真实来源。

增强字段不阻塞首报：

```text
pricing
strengths
weaknesses
risk
```

增强字段可以采、可以写、可以提示“未能公开验证”，但缺失时只能产生：

```text
OPTIONAL_FIELD_DEFERRED
OPTIONAL_EVIDENCE_GAP
DEGRADED_READY
```

不能产生：

```text
FAILED -> WAITING_RETRY -> WAITING_INTERVENTION
STOPPED
BLOCKER
核心 evidenceGapCount
不可查看报告
```

阶段 1 仍然保留红线：

```text
sourceUrls >= 5
distinctSourceDomains >= 2
不得编造 pricing / risk
不得删除 sourceUrls
不得打开 Gate 1 / Gate 2 多轮无限补采
不得把 live Tavily 预算反向放大
```

## 2. 根因结论

当前失败不是单个 timeout bug，而是契约没有统一：

```text
任务默认值仍在要求标准版 + 价格策略
  -> CoverageContract 又把 pricing / strengths / weaknesses 升成 blocker
  -> 字段 query 和 source scope 继续创建 PRICING 长尾
  -> hard deadline 没正文时返回 FAILED
  -> retry / recovery 把失败升级为人工介入和 STOPPED
  -> quorum 即使存在也被入口挡住，且 quorum 自己仍要求 PRICING
  -> 下游 Analyzer / Reviewer / Report 又把增强字段缺口计入质量与交付阻断
```

修复原则：

```text
先统一契约，再替换消费者
先红灯测试锁主旨，再改实现
每一层只删除自己的旧硬编码，不新建第二套降级口径
```

## 3. 文件结构

### 新增

```text
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicy.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicyTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
```

职责：

```text
StageOneFirstReportPolicy
  唯一维护阶段 1 首报核心字段、增强字段、字段/章节映射、optional gap flags、
  核心 source family、sourceUrls 红线和 quorum 规则。
  ReportDiagnosis / Reviewer / Writer / Task API 不得再维护第二套
  “定价 / 优势 / 短板是否阻断”的判断表。

StageOneFirstReportPolicyTest
  锁住“pricing 不是首报关键字段”“章节映射不绕过策略”
  “www 域名归一后不能伪造独立来源”“标准版不等于首报全字段 blocker”等不变量。

StageOneDegradedContractIntegrationTest
  用无 live Tavily 的节点输出模拟 PRICING 超时 / 缺证据，
  验证任务不进入 WAITING_INTERVENTION / STOPPED，
  extractor 可以被 quorum 放行，报告可降级查看。
```

### 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/model/dto/CreateTaskRequest.java
backend/src/main/java/cn/bugstack/competitoragent/model/entity/AnalysisTask.java
backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java
backend/src/main/java/cn/bugstack/competitoragent/conversation/FormDraftBuilder.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlan.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java
backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java
backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java
backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java
backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicy.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeRetryDecision.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeFailureCategory.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java
backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java
backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java
backend/src/main/resources/prompts/analyzer.txt
backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java
backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java
```

## 4. 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 新增阶段 1 首报统一契约策略，锁定核心字段与增强字段边界 | 45 分钟 | 当前偏差分析已完成 |
| Task 2 | 修正任务默认值与 CoverageContract，避免默认标准版 / 价格策略重新收紧 | 60 分钟 | Task 1 |
| Task 3 | 统一字段 query 与来源 scope，pricing 默认不创建阻塞长尾 | 60 分钟 | Task 2 |
| Task 4 | 修正 collector hard deadline、retry、recovery，确保硬截止走降级终态 | 75 分钟 | Task 1 |
| Task 5 | 修正 collector quorum，不再要求 PRICING，缺 pricing 只进审计 | 45 分钟 | Task 4 |
| Task 6 | 修正 Extractor / Analyzer / Writer / Reviewer 的核心字段口径 | 90 分钟 | Task 2 |
| Task 7 | 修正 ReportService / Task API 的降级可交付与可查看语义 | 45 分钟 | Task 6 |
| Task 8 | 增加端到端契约测试和旧语义测试替换 | 75 分钟 | Task 1-7 |
| Task 9 | 跑分层测试，更新本文进度记录 | 45 分钟 | Task 8 |

## 5. 进度记录

- [x] Task 1：阶段 1 首报统一契约策略
- [x] Task 2：任务默认值与 CoverageContract 收敛
- [x] Task 3：字段 query 与来源 scope 收敛
- [x] Task 4：collector hard deadline / retry / recovery 收敛
- [x] Task 5：collector quorum 收敛
- [x] Task 6：Extractor / Analyzer / Writer / Reviewer 收敛
- [x] Task 7：ReportService / Task API 收敛
- [x] Task 8：契约测试与旧测试替换
- [x] Task 9：验证与记录

执行过程中每次停下必须补充：

```markdown
### 停顿记录：YYYY-MM-DD HH:mm
- 已完成：
- 当前测试：
- 测试结果：
- 剩余问题：
- 下一步：
```

## 6. Task 1：新增阶段 1 首报统一契约策略

**Files:**

```text
Create: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicy.java
Create: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicyTest.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlan.java
```

- [ ] Step 1：写红灯测试，锁定首报字段边界
  - `summary / positioning / targetUsers / coreFeatures` 必须是首报 critical。
  - `pricing / strengths / weaknesses / risk` 必须不是首报 critical。
  - `fieldForSection(...)` 必须把“产品概览 / 市场定位 / 目标用户 / 核心能力 / 定价策略 / 优势判断 / 短板与风险”映射回统一字段。
  - `isQuorumReady(...)` 必须允许没有 PRICING 也能放行，只要满足主来源与 `sourceUrls` 红线。
  - `hasEnoughTraceableSources(...)` 必须做 `www` 域名归一。
- [ ] Step 2：运行红灯测试

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest" test
```

- [ ] Step 3：实现 `StageOneFirstReportPolicy`
  - 提供 core / enhancement 字段集合。
  - 提供 section alias 与 field alias。
  - 提供 optional gap flag 判定。
  - 提供 source scope 归一、quorum 判定与 `sourceUrls` 红线判定。
- [ ] Step 4：让 `DimensionEvidencePlan` 消费统一策略，不再保留本地 `STAGE_ONE_FIRST_REPORT_FIELDS`。
- [ ] Step 5：运行策略测试

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,DimensionEvidencePlanFactoryTest" test
```

## 7. Task 2：修正任务默认值与 CoverageContract

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/model/dto/CreateTaskRequest.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/model/entity/AnalysisTask.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/conversation/FormDraftBuilder.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssembler.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolverTest.java
```

- [ ] Step 1：写红灯测试，证明标准版不再等于首报全字段 blocker。
- [ ] Step 2：把默认任务 / 表单 / 编排层的首报语义对齐到新契约。
- [ ] Step 3：显式分析维度要求 pricing / weaknesses 时，只能变为 enhancement 审计，不得重新升成首报 blocker。
- [ ] Step 4：`CoverageContractResolver` 只让核心字段产生 `REQUIRED + BLOCKER`，增强字段只能是 `OPTIONAL + WARNING`。
- [ ] Step 5：运行 contract 测试

```powershell
mvn -pl backend "-Dtest=CoverageContractResolverTest" test
```

## 8. Task 3：统一字段 query 与来源 scope

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactoryTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceTest.java
```

- [ ] Step 1：只有核心 REQUIRED 字段默认进入字段 query 计划。
- [ ] Step 2：增强字段可以被记录为 deferred / optional，但默认不补采。
- [ ] Step 3：默认 source scope 去掉 PRICING，只保留首报核心认知闭环所需来源。
- [ ] Step 4：当用户显式指定“定价页 / pricing”时，仍允许创建 PRICING 计划，但不阻塞首报。
- [ ] Step 5：字段 supplement gate 只看核心字段，不再因为 pricing pending 单独拉起首报补采。
- [ ] Step 6：运行搜索与来源测试

```powershell
mvn -pl backend "-Dtest=DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceTest" test
```

## 9. Task 4：collector hard deadline / retry / recovery 收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeRetryDecision.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeFailureCategory.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java
Test: backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeRetryDecisionTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java
```

- [ ] Step 1：collector 硬截止必须走 `SUCCESS_DEGRADED`，不能再直接回 `FAILED -> WAITING_RETRY -> WAITING_INTERVENTION`。
- [ ] Step 2：把 deadline exhaustion 识别为可降级终态，而不是可重试错误。
- [ ] Step 3：`NodeRetryDecision` 对 collector hard deadline 不再计划重试，也不要求人工介入。
- [ ] Step 4：`NodeExecutionRecoveryPolicy` 不因为 collector 自身的降级终态提前把任务打成 `STOPPED`。
- [ ] Step 5：运行 collector / retry / recovery 测试

```powershell
mvn -pl backend "-Dtest=CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest" test
```

## 10. Task 5：collector quorum 收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicy.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicyTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
```

- [ ] Step 1：用 `StageOneFirstReportPolicy` 统一计算 quorum。
- [ ] Step 2：quorum 不再要求 PRICING，缺 pricing 只进入 `missingFamilies` 与 `auditFlags`。
- [ ] Step 3：`DagExecutor` 只要 collector 依赖非空且阶段 1 quorum 已就绪，就允许放行 extractor，不再保留“至少 3 个 collector”硬门槛。
- [ ] Step 4：运行 quorum 测试

```powershell
mvn -pl backend "-Dtest=CollectorEvidenceReadinessPolicyTest,DagExecutorTest" test
```

## 11. Task 6：Extractor / Analyzer / Writer / Reviewer 收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java
Modify: backend/src/main/resources/prompts/analyzer.txt
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java
Test: backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java
```

- [ ] Step 1：Extractor 仍保留全字段抽取，但 coverage gap 只让核心字段产生阻断语义；pricing / strengths / weaknesses 只产生 optional gap / deferred audit。
- [ ] Step 2：Analyzer 只把核心维度缺失计入 severity / confidence，增强维度缺失进入 optional 标记。
- [ ] Step 3：更新 `analyzer.txt`，明确增强字段可为空，并要求在 issue flags 中标记 `OPTIONAL_*_DEFERRED`。
- [ ] Step 4：Writer citation gap 必须区分核心缺口与 optional 缺口，optional 缺口只能是 `WARNING`。
- [ ] Step 5：Reviewer fallback 默认只要求核心章节；增强章节保留 warning / 审计展示。
- [ ] Step 6：Reviewer 评分只把核心缺口计入 blocker 与 critical core，不借机下调 `score >= 80` 的优秀报告阈值。
- [ ] Step 7：运行下游单测

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest" test
```

## 12. Task 7：ReportService / Task API 收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssembler.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java
Test: backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/report/ReportDeliverySummaryServiceTest.java
Test: backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
```

- [ ] Step 1：`ReportDiagnosisAssembler` 只把核心缺口计入 `evidenceGapCount`，可选缺口只进 section / audit 展示。
- [ ] Step 2：`ReportService` 的 degraded delivery 只忽略 optional gap，但仍严格保留：

```text
score >= 60
无 blocker
core evidenceGapCount == 0
sourceUrls >= 5 且 distinct domains >= 2
```

- [ ] Step 3：`TaskNodeViewAssembler` 允许“已有可追溯草稿 / 降级报告”时 `canViewReport=true`，但必须来自 Writer / Report 层可追溯输出，不能靠 collector output 误开入口。
- [ ] Step 4：运行报告与 API 测试

```powershell
mvn -pl backend "-Dtest=ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest" test
```

## 13. Task 8：端到端契约测试与旧测试替换

**Files:**

```text
Create: backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolverTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactoryTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicyTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentCoverageContractTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
```

- [ ] Step 1：新增无 live Tavily 的 `StageOneDegradedContractIntegrationTest`
  - 正例：OFFICIAL / DOCS / REVIEW 满足 `sourceUrls` 红线，PRICING 硬截止且 `readyForQuorum=false`。
  - 断言：
    - pricing collector 是 `SUCCESS_DEGRADED`
    - readiness `ready=true`
    - `missingFamilies` 包含 `PRICING`
    - `TaskResponse.canViewReport == true`
    - `deliveryStatus == DEGRADED_READY`
    - `evidenceGapCount == 0`
    - delivery summary `sourceUrls` 数量至少为 5
  - 反例：sourceUrls 归一后只有单域或不足 5 条，不得放行 degraded ready。
- [ ] Step 2：替换旧语义断言

```text
pricing criticalForFirstReport == true             -> false
standard template pricing REQUIRED + BLOCKER       -> OPTIONAL + WARNING
standard template weaknesses REQUIRED + BLOCKER    -> OPTIONAL + WARNING
quorum must have PRICING                           -> sourceUrls 红线满足时可无 PRICING
canViewReport only SUCCESS                         -> 草稿 / 降级报告可查看
```

- [ ] Step 3：运行契约测试集

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,CollectorEvidenceReadinessPolicyTest,ReportServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

## 14. Task 9：验证命令与停顿记录

**Files:**

```text
Modify: docs/Tavily/task/2026-07-08-18-stage1-degraded-contract-unification-plan.md
```

- [ ] Step 1：运行后端分层回归

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,CollectorEvidenceReadinessPolicyTest,CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,ReportDiagnosisAssemblerTest,ReportServiceTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

- [ ] Step 2：运行预算护栏测试

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest" test
```

- [ ] Step 3：不跑 live E2E，直到无 live 契约测试全部通过。
- [ ] Step 4：把本文件的停顿记录补齐，明确写出“已完成 / 当前测试 / 测试结果 / 剩余问题 / 下一步”。

## 15. 验收标准

必须全部满足：

```text
1. StageOneFirstReportPolicy 是首报关键字段和 quorum 的唯一事实源。
2. StageOneFirstReportPolicy 同时承载字段集合、章节映射、optional gap flags、sourceUrls 域名归一和 quorum。
3. pricing / strengths / weaknesses / risk 不在首报 critical 字段集合里。
4. 默认任务不再自动带“价格策略”，默认模板不再触发全字段 blocker。
5. 标准版只影响输出结构，不把增强字段升级为首报 blocker。
6. 默认 source scope 不创建 PRICING collector；显式定价页可以创建，但不阻塞首报。
7. TavilyFastLaneProvider 与 HeuristicSourceDiscoveryService 使用同一套中英文 scope 归一语义。
8. collector hard deadline 不再返回 FAILED 进入 retry / WAITING_INTERVENTION；但 SUCCESS_DEGRADED 不代表红线已满足。
9. quorum 不要求 PRICING；缺 PRICING 只进入 missingFamilies 和 auditFlags。
10. sourceUrls >= 5 且 distinctSourceDomains >= 2 仍是硬红线，www/root host 归一后不能伪造独立域。
11. Extractor / Analyzer / Writer / Reviewer 只把核心字段缺口当首报阻断。
12. ReportService 的 evidenceGapCount 只统计核心缺口，optional gap 只进审计 / 摘要。
13. 只有 Writer / Report 层存在带 sourceUrls 的草稿或降级报告时 canViewReport=true。
14. 不下调 score>=80 优秀报告阈值，不把低分或有 BLOCKER 的报告伪装成成功。
15. 集成测试贯穿 DagExecutor -> TaskNodeViewAssembler -> ReportService，而不是只测 policy。
16. 旧测试中锁死 pricing blocker 的断言全部替换为阶段 1 契约断言。
```

## 16. 明确不做

```text
不打开 Gate 1 / Gate 2 多轮自动补采
不提高 Tavily live 调用预算
不把 pricing 改成永远不采，只是默认不阻塞首报
不删除 sourceUrls 红线
不把低于 60 且有 BLOCKER 的报告伪装成可交付
不在 ReportDiagnosis / Reviewer / Writer 内新建第二套“章节 -> 字段 -> 是否阻断”映射
不把 collector SUCCESS_DEGRADED 当成下游证据成功
不把抖音 / 哔哩哔哩开放平台重新作为阶段 1 毕业基线
```

## 17. 执行顺序约束

必须按顺序执行：

```text
StageOneFirstReportPolicy
  -> CoverageContract / defaults
  -> source scope / field query
  -> collector hard deadline
  -> quorum
  -> downstream quality
  -> report delivery
  -> integration tests
```

禁止先改 hard deadline 后不改 contract。那会再次变成“局部状态名修复”，pricing 仍会从上游和下游重新把任务拉回阻断链。

## 18. 自检清单

- [ ] 本计划没有把根因后移到后续阶段再统一质量口径。
- [ ] 本计划没有只修 collector hard deadline。
- [ ] 本计划没有保留第二套 pricing critical 判断。
- [ ] 本计划没有保留第二套章节映射或 optional gap 判断。
- [ ] 本计划没有让标准版继续等于全字段 blocker。
- [ ] 本计划没有让 optional gap 占用核心 evidenceGapCount。
- [ ] 本计划没有把 SUCCESS_DEGRADED 误用成证据成功。
- [ ] 本计划没有绕过 sourceUrls 红线。
- [ ] 本计划没有调低 `score>=80` 阈值来假装成功。
- [ ] 本计划每一层都有对应测试或断言。

### 停顿记录：2026-07-08 20:37
- 已完成：
  - Task 5：collector quorum 已收敛到 `StageOneFirstReportPolicy`，移除了旧的 3 collector 硬门槛，并把 pricing 缺失降级为审计语义。
  - Task 6：打通 `Extractor / Analyzer / Writer / Reviewer` 的 stage1 首报降级契约，核心字段只认 `summary / positioning / targetUsers / coreFeatures`，`pricing / strengths / weaknesses` 统一转为 optional gap / deferred audit。
  - 统一补齐 `StageOneFirstReportPolicy.normalizeFieldName(...)`，消除 `featureComparison / pricingComparison / strengthsSummary / weaknessesSummary` 等跨阶段字段名漂移带来的缝隙。
- 当前测试：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldEmitSectionEvidenceBundlesAndGapMarkersForPartialCoverage,CompetitorAnalysisAgentTest#shouldExposeAnalysisGapMetadataWhenCoreDimensionsMissing,WriterCitationGapInspectorTest#shouldExposeSourceBackedCitationGapWithoutPretendingCitationAgent,QualityReviewAgentTest#shouldFallbackToCoreCoverageSectionsWhenAnalysisDimensionsMissing" test`
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest" test`
- 测试结果：
  - 上述两组测试均已通过；第二组类级验证共 `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0`。
- 剩余问题：
  - Task 7 `ReportService / Task API` 尚未开始，`evidenceGapCount / canViewReport / degraded summary` 还没有按新契约收敛。
  - Task 8 / Task 9 尚未执行，未跑计划中的后端分层大回归、预算护栏测试，也还没有进入 live E2E。
  - 早先 `DagExecutorTest` 中有 2 个与本轮 Task 6 无关的旧语义漂移用例失败，当前未重新处理。
- 下一步：
  - 进入 Task 7，收敛 `ReportService / ReportDeliverySummaryService / TaskNodeViewAssembler / Task API` 的降级可交付与可查看语义。
  - 完成 Task 7 后，再按计划执行 Task 8 / Task 9 的非 live 验证，并继续在 live E2E 前停下复盘。

### 停顿记录：2026-07-09 10:35
- 已完成：
  - Task 7 已完成：`ReportDiagnosisAssembler / ReportService / TaskNodeViewAssembler` 已对齐阶段 1 降级契约。
  - `ReportDiagnosisAssembler` 现在只把核心 coverage / evidence gap 计入 `evidenceGapCount`，optional gap 只保留在诊断和审计视图里。
  - `ReportService` 已把 degraded-ready 的放行条件收敛为：`score >= 60`、无 blocker、核心 evidence gap 为 0、且满足 `sourceUrls` 红线；同时把 optional section deferred 信息写入 delivery summary。
  - `TaskNodeViewAssembler` 已支持“任务虽未 SUCCESS，但 Writer/Report 已产出可追溯草稿或可恢复证据摘要时允许查看报告”，并且不再依赖 collector 配置 URL 误开入口。
  - 本文档已从乱码状态恢复为正常 UTF-8 可读文本，并同步到当前实际执行进度。
- 当前测试：
  - `mvn -pl backend "-Dtest=ReportDiagnosisAssemblerTest#shouldNotCountOptionalCoverageGapIntoEvidenceGapCount+shouldTreatMissingStructuredEvidenceAsEvidenceInsufficientDiagnosis,ReportDeliverySummaryServiceTest#shouldExposeDegradedReadyForOptionalCoverageGapWithEnoughTraceableSources+shouldNotExposeDegradedReadyWhenTraceableSourceRedlineIsNotMet,TaskNodeViewAssemblerTest#shouldExposeDraftReportCapabilityWhenWriterProducedDraftButReviewStoppedTask+shouldNotExposeDraftReportWhenWriterHasNoTraceableDraftEvidence+shouldExposeDraftReportWhenWriterKeepsRecoverableEvidenceSummary" test`
  - `mvn -pl backend "-Dtest=ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest" test`
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,CollectorEvidenceReadinessPolicyTest,ReportServiceTest,TaskNodeViewAssemblerTest" test`
- 测试结果：
  - Task 7 目标用例 7 个全部通过：`Tests run: 7, Failures: 0, Errors: 0`。
  - Task 7 类级验证 15 个全部通过：`Tests run: 15, Failures: 0, Errors: 0`。
  - Task 8 契约测试集预跑时发现 1 个旧语义失败：`ReportServiceTest.shouldMarkStageOneMvpReportAsDegradedReadyWhenScoreIsPassingWithLimitedEvidenceGaps` 仍使用旧夹具，未满足新的 `sourceUrls` 红线前提。
- 剩余问题：
  - Task 8 还未完成：需要先修正 `ReportServiceTest` 的旧 degraded-ready 断言夹具，再新增 `StageOneDegradedContractIntegrationTest`。
  - Task 9 尚未开始：后端分层回归与预算护栏测试还没跑。
  - 仍未进入 live E2E；必须坚持停在无 live 契约验证全部通过之后。
- 下一步：
  - 先修正 `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java` 中的旧断言，让用例显式提供满足红线的 5 条可追溯来源和至少 2 个独立域。
  - 新增 `backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java`，覆盖 pricing collector `SUCCESS_DEGRADED` 放行与 sourceUrls 红线失败反例。
  - 运行 Task 8 计划内测试集，修复暴露问题后再进入 Task 9 的非 live 回归与预算护栏测试，并继续在 live E2E 前停下总结。

### 停顿记录：2026-07-09 11:10
- 已完成：
  - Task 8 已完成：修正 `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java` 中旧的 degraded-ready 夹具，让它显式满足 `sourceUrls >= 5` 且归一化域名数 `>= 2` 的红线。
  - Task 8 已完成：新增 `backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java`，把 `DagExecutor -> ReportService -> TaskNodeViewAssembler` 串成同一条 stage1 degraded contract 集成链路。
  - 新增集成测试覆盖了两条关键语义：`PRICING` 缺口只进入 optional deferred audit、不会阻断首报；若可追溯来源在域名归一后不满足红线，下游 extractor 会被编排器收口为 `SKIPPED`，任务保持不可查看、报告不可 degraded-ready 交付。
  - Task 9 已完成：已跑完计划中的非 live 分层回归与预算护栏测试，并停在 live Tavily E2E 之前。
- 当前测试：
  - `mvn -pl backend "-Dtest=ReportServiceTest#shouldMarkStageOneMvpReportAsDegradedReadyWhenScoreIsPassingWithLimitedEvidenceGaps" test`
  - `mvn -pl backend "-Dtest=StageOneDegradedContractIntegrationTest" test`
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,CollectorEvidenceReadinessPolicyTest,ReportServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test`
  - `mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,CollectorEvidenceReadinessPolicyTest,CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,ReportDiagnosisAssemblerTest,ReportServiceTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test`
  - `mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest" test`
- 测试结果：
  - `StageOneDegradedContractIntegrationTest` 通过：`Tests run: 2, Failures: 0, Errors: 0`。
  - Task 8 定向测试集通过：`Tests run: 47, Failures: 0, Errors: 0, Skipped: 0`。
  - Task 9 非 live 分层回归通过：`Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`。
  - Task 9 预算护栏测试通过：`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`。
- 剩余问题：
  - 本计划下的代码与非 live 验证已完成，当前只剩 live Tavily E2E 尚未执行。
  - Maven 运行时仍会打印既有环境噪音：`settings.xml` 中的 `mirrors` 警告，以及预算护栏测试结束后的 XML `DOCTYPE` fatal 日志；两者都未导致本轮测试失败，但后续如果要清理 CI 观感，可以另立任务收敛。
- 下一步：
  - 按用户要求停在这里，不进入 live Tavily E2E。
  - 后续若继续，只需要基于当前 master 直接接上 live E2E 验证与结果复盘。
