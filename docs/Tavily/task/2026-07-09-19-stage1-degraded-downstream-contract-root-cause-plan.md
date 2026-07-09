# Task19 阶段1降级下游契约根因收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Task18 live E2E 暴露的下游契约断点，让“核心字段已 TRACEABLE、sourceUrls 红线已满足、增强/自动章节仍有缺口”的阶段1首报不再被 reviewer、citation 或任务状态收口误杀为 `STOPPED / BLOCKED`。

**Architecture:** 不新建第二套降级口径，继续以 `StageOneFirstReportPolicy` 作为唯一事实源，但把它从“字段清单与 quorum 策略”扩展为“下游交付裁决的统一分类能力”。Reviewer structuredBlocks、Writer/Citation 缺口、ReportDiagnosis、ReportService、NodeExecutionRecoveryPolicy 都必须先把章节、字段、claim、diagnosis 映射回统一 stage1 scope，再决定 `BLOCKER / WARNING / AUDIT / REWRITE_ONLY`。先补红灯测试锁住 Task101 失败链路，再逐层替换旁路硬编码，最后用无 live 集成测试和真实 E2E 复测验证。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Jackson, H2/test fixtures, Tavily live E2E.

---

## 1. 背景与主旨

Task18 已经把 collector、coverage contract、quorum、extractor/analyzer/writer/report 的主路径初步收敛到阶段1首报契约。但 2026-07-09 live E2E 证明：主路径过线后，仍有下游旁路把可降级报告重新压成不可交付。

第二轮复测 `taskId=101` 的关键信号：

```text
核心字段：summary / positioning / targetUsers / coreFeatures 全部 TRACEABLE
sourceUrls 红线：14 条来源，10 个归一化独立域名
collector：SUCCESS=3，SUCCESS_DEGRADED=3，无 retry / waiting intervention
writer：PARTIAL_SOURCE，missingCitationSections=pricing,strengths,weaknesses,conclusion,report_conclusion
reviewer：missing_structured_evidence 被硬编码 ERROR，形成 2 个 BLOCKER
任务：canViewReport=true，但 taskStatus=STOPPED，deliveryStatus=BLOCKED
```

这说明失败主因已经不是上游是否给了 URL，而是下游交付裁决没有统一消费 Task18 的契约。Task19 的主旨是补齐“唯一事实源在下游的执行能力”，不是调低分数线、删除诊断、扩大采集预算，也不是把根因继续推给 live E2E。

## 2. 根因链路

当前完整阻断链：

```text
QualityReviewAgent.appendStructuredEvidenceDiagnoses
  -> inferStructuredEvidenceSection
  -> severity("ERROR")
  -> QualityDiagnosis.normalized: ERROR => BLOCKER
  -> QualityReviewAgent.requiresHumanIntervention: blockerCount > 0
  -> NodeExecutionRecoveryPolicy: initialReviewRequiresHumanIntervention && !revisionFlowSucceeded
  -> AnalysisTaskStatus.STOPPED
  -> ReportService deliverySummary: blockerCount > 0 => BLOCKED
```

并行污染链：

```text
ReportWriterAgent / WriterCitationGapInspector
  -> missingCitationSections 包含 pricing / strengths / weaknesses / conclusion / report_conclusion
  -> CitationAgent 按全报告 sensitiveClaims 计算 citationCoverageRate
  -> optional / 未请求 / 自动结论章节 claim 进入交付分母
  -> citationRiskSeverity=ERROR 或 writer citationGapSeverity=HIGH
  -> reviewer / report diagnosis 继续展示为主链路缺口
```

本计划必须修的是这两条“旁路决策链”。如果只把 `severity("ERROR")` 改成 `WARNING`，或者只给 `STOPPED` 加一个例外，就会留下新的接缝问题：下一次 citation、report diagnosis 或 task recovery 仍能从别的入口把增强字段缺口升成 blocker。

## 3. 统一原则

必须保持这些不变量：

```text
1. StageOneFirstReportPolicy 仍是阶段1首报唯一事实源。
2. 下游每个诊断、章节、claim、gap 必须先归一到 stage1 scope，再决定严重度。
3. 核心字段未 TRACEABLE 时仍可阻断：summary / positioning / targetUsers / coreFeatures。
4. 增强字段只能 warning / audit：pricing / strengths / weaknesses / risk。
5. 未请求增强章节不进入用户可见主缺口链。
6. 自动生成章节不制造 blocker：conclusion / report_conclusion 优先保守改写或裁剪。
7. structuredBlocks 可用性不能覆盖字段级 TRACEABLE 事实；只能在核心字段确实无可用 coverage 时升为 blocker。
8. sourceUrls >= 5 且 distinctSourceDomains >= 2 仍是硬红线。
9. `canViewReport=true` 不等于正式通过；但可查看降级报告不能被任务状态统一写成 STOPPED。
10. 不下调 `score>=80` 优秀报告阈值，不把有真实核心 blocker 的报告伪装成成功。
```

## 4. 本次评审采纳的风险决策

### 风险 1：`SUCCESS` 语义争议

本计划接受 `taskStatus=SUCCESS` 作为阶段1“编排执行完成”的任务终态，但必须同时满足：

```text
ReportResponse.deliverySummary.deliveryStatus = DEGRADED_READY
ReportResponse.qualityPassed = false
TaskResponse.statusSummary 明确写出“阶段1降级口径可查看”
TaskResponse.interventionSummary 明确写出“建议人工复核后使用”
```

因此演示语义是双轴：

```text
任务轴：执行闭环成功
交付轴：降级可交付，非质检优秀通过
```

这不是调低 score 阈值假装成功，也不能在前端或汇报中展示成“质检通过”。

### 风险 2：Citation 交付覆盖率不扩展 DTO

本计划不新增 `CitationCheckResult.deliveryCitationCoverageRate`，避免牵连 Report 实体、ReportResponse、导出和前端。`citationCoverageRate` 保持全量审计含义；阶段1交付覆盖率只在 `CitationAgent` 内部作为局部变量参与 `citationRiskSeverity / citationEvidenceState / issueFlags` 的计算。

### 风险 3：`qualityScore >= 60` 必须显式验证

Task101 第二轮实测 `qualityScore=29`，不是“小差一点”。本计划不能假设 blocker 归零后分数自然恢复。Task2 必须修正评分口径中 optional/generated/audit issue 的主扣分路径；Task6 必须用复现型集成测试断言 `qualityScore >= 60`。如果达不到，禁止绕到任务状态或 deliveryStatus，必须回查 reviewer 评分。

### 风险 4：Reviewer 旁路必须先全量清点

Task18 的失败来自漏掉 structuredBlocks 旁路。Task19 执行前必须 grep reviewer 里所有 `severity / level / BLOCKER / ERROR / requiresHumanIntervention` 产生点，并在 progress 文档里逐项标注归属。没有完成这一步，不允许只修 `appendStructuredEvidenceDiagnoses(...)`。

### 风险 5：下游分类入参与请求维度必须单一来源

本计划禁止 reviewer / writer / citation / report diagnosis 各自从章节标题、report 文本或节点 output 临时猜测“用户请求了什么”。阶段1下游裁决只能使用同一份 `requestedDimensions`：

```text
CreateTaskRequest.analysisDimensions
  -> TaskPlan / nodeConfig / AgentContext 中持久化的任务请求维度
  -> StageOneFirstReportPolicy 统一归一化
  -> reviewer / writer / citation / report diagnosis 只消费归一化结果
```

如果某个下游节点当前拿不到 `requestedDimensions`，本计划优先补齐传递链或读取既有任务定义快照；禁止在该节点新增一套默认“标准版章节”判断。否则 `strengths / weaknesses / conclusion` 仍会以不同入口回流成主缺口，形成新的接缝问题。

## 5. 文件结构

### 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicy.java
backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java
backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java
backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationAgent.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java
backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/task/TaskProgressSnapshot.java
```

### 测试

```text
backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicyTest.java
backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java
backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentCoverageContractTest.java
backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java
backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationAgentTest.java
backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java
backend/src/test/java/cn/bugstack/competitoragent/report/ReportDeliverySummaryServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java
backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
```

## 6. 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 扩展 `StageOneFirstReportPolicy` 为下游 issue scope 分类入口 | 45 分钟 | Task18 已完成，live E2E 失败链路已记录 |
| Task 2 | 全量清点 reviewer 阻断产生点，修正 structuredBlocks 旁路与阶段1评分口径 | 90 分钟 | Task 1 |
| Task 3 | 修正 writer/citation 交付分母，只让 stage1 delivery scope claim 参与阻断，不扩展 DTO | 90 分钟 | Task 1 |
| Task 4 | 收敛 report diagnosis / delivery summary，过滤未请求与自动章节噪声 | 60 分钟 | Task 2、Task 3 |
| Task 5 | 收敛任务级状态，区分人工停机与降级可查看终态 | 60 分钟 | Task 4 |
| Task 6 | 新增 Task101 复现型集成测试和 live E2E 复测脚本记录 | 75 分钟 | Task 1-5 |
| Task 7 | 跑分层回归、预算护栏与真实 E2E，记录暴露问题 | 90 分钟 | Task 6 |

## 7. 进度记录

- [ ] Task 1：扩展 `StageOneFirstReportPolicy` issue scope 分类入口
- [ ] Task 2：Reviewer 阻断产生点清点、structuredBlocks 旁路与评分口径收敛
- [ ] Task 3：Writer / Citation 交付分母收敛，不扩展 DTO
- [ ] Task 4：Report diagnosis / delivery summary 收敛
- [ ] Task 5：任务级状态收口收敛
- [ ] Task 6：Task101 复现型集成测试与 E2E 复测记录
- [ ] Task 7：验证与问题记录

执行过程中每次停下必须补充：

```markdown
### 停顿记录：YYYY-MM-DD HH:mm
- 已完成：
- 当前测试：
- 测试结果：
- 暴露问题：
- 下一步：
```

## 8. Task 1：扩展 StageOneFirstReportPolicy issue scope 分类入口

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicy.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/StageOneFirstReportPolicyTest.java
```

- [ ] Step 1：写红灯测试 `shouldClassifyStageOneIssueScopesForDownstreamDelivery`
  - 输入 `产品概览 / 市场定位 / 目标用户 / 功能对比`，断言分类为 `CORE`。
  - 输入 `定价策略 / pricing / 优势判断 / strengths / 短板与风险 / weaknesses`，断言分类为 `ENHANCEMENT`。
  - 输入 `conclusion / report_conclusion / 建议结论`，断言分类为 `GENERATED`。
  - 输入 `通用 / report / unknown`，断言分类为 `UNKNOWN_AUDIT`，默认不得阻断。

- [ ] Step 2：写红灯测试 `shouldUseRequestedDimensionsWhenExposingOptionalSections`
  - 请求维度为 `产品概述 / 市场定位 / 目标用户 / 核心功能 / 价格策略`。
  - `pricing` 可进入可见 warning。
  - `strengths / weaknesses` 不进入用户可见主缺口链，只保留 audit flag。
  - `conclusion / report_conclusion` 不进入 missingCitationSections 主列表。

- [ ] Step 3：实现统一分类方法，方法必须落在 `StageOneFirstReportPolicy` 内或其静态内部值对象内，禁止在 reviewer / writer / citation / report 层复制映射表或各自新增 overload 语义。

建议接口形态：

```java
public enum FirstReportIssueScope {
    CORE,
    ENHANCEMENT,
    GENERATED,
    UNKNOWN_AUDIT
}

public record FirstReportIssueContext(
        String sectionKey,
        String sectionTitle,
        String fieldName
) {
}

public static FirstReportIssueScope classifyIssueScope(FirstReportIssueContext context) {
    String fieldName = normalizeFieldName(context == null ? null : context.fieldName());
    if (isFirstReportCriticalField(fieldName)) {
        return FirstReportIssueScope.CORE;
    }
    if (isFirstReportEnhancementField(fieldName)) {
        return FirstReportIssueScope.ENHANCEMENT;
    }
    if (isGeneratedReportSection(context == null ? null : context.sectionKey())
            || isGeneratedReportSection(context == null ? null : context.sectionTitle())) {
        return FirstReportIssueScope.GENERATED;
    }
    String mappedField = normalizeFieldName(firstNonBlank(
            fieldForSection(context == null ? null : context.sectionTitle()),
            fieldForSection(context == null ? null : context.sectionKey())
    ));
    if (isFirstReportCriticalField(mappedField)) {
        return FirstReportIssueScope.CORE;
    }
    if (isFirstReportEnhancementField(mappedField)) {
        return FirstReportIssueScope.ENHANCEMENT;
    }
    return FirstReportIssueScope.UNKNOWN_AUDIT;
}
```

- [ ] Step 4：补充 `isGeneratedReportSection(...)`、`normalizeRequestedDimensions(...)`、`shouldExposeIssueForRequestedDimensions(...)`、`isBlockingDeliveryScope(...)`。

约束：

```text
CORE => 可以进入 blocker，但还要结合 coverage 状态判断。
ENHANCEMENT => 只能 WARNING / audit。
GENERATED => 只能 REWRITE_ONLY / WARNING，不进入 evidenceGapCount。
UNKNOWN_AUDIT => 默认 audit，不得因为“通用”直接 BLOCKER。
```

`shouldExposeIssueForRequestedDimensions(...)` 必须只接受 `StageOneFirstReportPolicy.normalizeRequestedDimensions(task.analysisDimensions)` 的结果，禁止调用方传入自己从 report 章节反推的维度列表。

- [ ] Step 5：运行测试。

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest" test
```

## 9. Task 2：Reviewer 阻断产生点清点、structuredBlocks 旁路与评分口径收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentCoverageContractTest.java
```

- [ ] Step 0：先全量清点 reviewer 阻断产生点，避免 Task18 式漏旁路。

必须运行：

```powershell
rg -n "severity\(|level\(|BLOCKER|ERROR|requiresHumanIntervention" backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java
```

把命中点整理到 progress 文档中，至少覆盖：

```text
SectionRule 默认 ERROR
RevisionPlan item severity
coverage_gap severity
missing_structured_evidence severity
isDiagnosisPassed 的 hasBlocker
isStageOneMvpPassingScore 的 hasBlocker / core dimension
requiresHumanIntervention 的 blockerCount / critical evidence dimension / score<=35
buildDimensions 的 searchIssueCount / claimIssueCount / blockerCount
```

每个命中点必须标注为：

```text
已接入 StageOneFirstReportPolicy
或保留原语义但证明不会处理 optional/generated/audit 缺口
```

禁止只改 `appendStructuredEvidenceDiagnoses(...)` 后直接进入下一任务。

- [ ] Step 1：写红灯测试 `shouldNotBlockWhenStructuredBlocksMissingButCoreCoverageTraceable`
  - 构造 evidence `structuredBlocks=0`、`qualitySignals` 包含 `NO_STRUCTURED_BLOCKS`。
  - 构造 coverageSnapshot 中 `功能对比 / 核心能力` 为 `TRACEABLE`。
  - 断言 `missing_structured_evidence` 最多为 `WARNING`，`level != BLOCKER`。
  - 断言 `requiresHumanIntervention=false`，或至少不因 structuredBlocks 产生人工介入。

- [ ] Step 2：写红灯测试 `shouldTreatGenericStructuredEvidenceGapAsAuditWhenCoreCoveragePassed`
  - section 为 `通用`。
  - 核心四字段全 `TRACEABLE`。
  - 断言诊断进入 `SEARCH_QUALITY` audit / warning，不得成为 `BLOCKER`。

- [ ] Step 3：写红灯测试 `shouldStillBlockWhenStructuredEvidenceGapMapsToMissingCoreCoverage`
  - section 为 `目标用户` 或 `功能对比`。
  - 对应核心字段为 `EVIDENCE_NOT_COVERING / LLM_REFUSED / EMPTY`。
  - 断言仍然允许 `ERROR -> BLOCKER`。

- [ ] Step 4：在 `appendStructuredEvidenceDiagnoses(...)` 中移除无条件 `severity("ERROR")`，改为调用统一裁决。

核心判断必须表达为：

```text
如果 issueScope=CORE 且 coverageSnapshot 对应核心字段不是 TRACEABLE/STRUCTURED_BLOCK_DIRECT，才允许 ERROR。
如果 issueScope=CORE 但 coverageSnapshot 已 TRACEABLE，则 WARNING。
如果 issueScope=ENHANCEMENT，则 WARNING。
如果 issueScope=GENERATED，则 WARNING + rewrite-only repairSuggestion。
如果 issueScope=UNKNOWN_AUDIT，则 WARNING，不进入 blockerCount。
```

- [ ] Step 5：把 `resolveStructuredCoverageHint(...)` 和 `mapReportSectionToCoverageSection(...)` 改为消费 `StageOneFirstReportPolicy` 的统一映射，避免 reviewer 保留第二套章节表。

- [ ] Step 6：新增评分闭环红灯测试 `shouldKeepStageOneMvpScoreAboveFloorWhenOnlyAuditIssuesRemain`
  - LLM 原始分不低于 `STAGE_ONE_MIN_LLM_SCORE_FLOOR`。
  - 核心四字段全部 `TRACEABLE`。
  - 仅存在 optional/generated/unknown audit 的 `SEARCH_QUALITY` warning。
  - 断言最终 `score >= STAGE_ONE_MVP_SCORE_FLOOR`。
  - 断言 `isStageOneMvpPassingScore(...)` 可以通过。
  - 断言 `requiresHumanIntervention=false`。

这个测试不是为了调低分数线，而是验证非交付阻断的 audit issue 不再被 `searchIssueCount / blockerCount / claimIssueCount` 当作主质量失败反复扣分。

- [ ] Step 7：如果 Step 6 失败，修正 `buildDimensions(...)` 的计数边界。

修正规则：

```text
deliveryBlockingSearchIssueCount：只统计 stage1 issueScope=CORE 且核心 coverage 未过线的 SEARCH_QUALITY。
auditSearchIssueCount：统计 ENHANCEMENT / GENERATED / UNKNOWN_AUDIT，仅进入诊断展示，不参与 MVP floor 主扣分。
deliveryClaimIssueCount：只统计核心 claim 支撑缺口。
auditClaimIssueCount：统计 optional/generated claim 缺口，只进入 audit。
```

禁止把 `STAGE_ONE_MVP_SCORE_FLOOR` 从 60 调低，也禁止把 `llmScore` 硬抬高。

- [ ] Step 8：运行 reviewer 测试。

```powershell
mvn -pl backend "-Dtest=QualityReviewAgentTest,QualityReviewAgentCoverageContractTest" test
```

## 10. Task 3：Writer / Citation 交付分母收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/citation/CitationAgent.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/citation/CitationAgentTest.java
```

- [ ] Step 1：写红灯测试 `shouldKeepUnrequestedEnhancementGapsOutOfMissingCitationSections`
  - analysisDimensions 不包含 `优势判断 / 短板与风险`。
  - bundle 包含 `strengths / weaknesses` 缺口。
  - 断言 `missingCitationSections` 不包含 `strengths / weaknesses`。
  - 断言 issueFlags 保留 `OPTIONAL_CITATION_GAP` 或 `OPTIONAL_SECTION_EVIDENCE_GAP`，用于内部审计。
  - 断言 requestedDimensions 来自任务请求快照，而不是从 reportContent 的章节标题反推。

- [ ] Step 2：写红灯测试 `shouldTreatGeneratedConclusionGapAsRewriteOnlyWarning`
  - sectionKey 为 `conclusion / report_conclusion`。
  - 有 fallbackSourceUrls。
  - 断言 severity 为 `WARNING`，不返回 `HIGH / ERROR`。
  - 断言 `missingCitationSections` 主列表不包含 `conclusion / report_conclusion`。

- [ ] Step 3：写红灯测试 `shouldExcludeOptionalAndGeneratedClaimsFromInternalDeliveryCoverageRate`
  - reportContent 中包含核心章节 claim、pricing claim、strengths claim、report_conclusion claim。
  - 核心 claim 有合法 evidenceId。
  - optional / generated claim 缺 evidenceId。
  - 断言内部交付覆盖率为 `1.0`，并驱动 `citationRiskSeverity != ERROR`、`citationEvidenceState != MISSING_SOURCE`。
  - 断言对外 `CitationCheckResult.citationCoverageRate` 仍保留历史全量审计覆盖率，不被伪装成 `1.0`。
  - 断言原始 audit 仍记录 optional/generated 缺口，不能静默丢失。

- [ ] Step 4：给 `CitationAgent` 增加 stage1 交付统计边界。
  - `CitationClaim` 已有 `sectionKey / sectionTitle`，不得再按全文统一分母。
  - 逐条 claim 构造 `StageOneFirstReportPolicy.FirstReportIssueContext(sectionKey, sectionTitle, fieldNameOrNull)`，再调用唯一入口 `classifyIssueScope(context)`。
  - 只有 `CORE` 且 traceabilitySensitive 的 claim 进入交付分母。
  - `ENHANCEMENT / GENERATED / UNKNOWN_AUDIT` claim 进入 auditIssues，不进入阻断分母。
  - 若 `CitationAgent` 当前拿不到任务请求维度，先补齐从 task plan/node config/AgentContext 读取 `analysisDimensions` 的链路；禁止把所有 reportContent 章节当成默认请求维度。

- [ ] Step 5：明确不修改 `CitationCheckResult` DTO，交付覆盖率只做内部决策变量。

```text
对外 citationCoverageRate：
  保持当前 DTO 字段，继续表示历史全量审计覆盖率。
  这样前端、ReportResponse、导出和旧测试不用跟着迁移。

内部 deliveryCitationCoverageRate：
  只在 CitationAgent 内部计算，只统计 CORE 且 traceabilitySensitive 的 claim。
  只用于 citationRiskSeverity / citationEvidenceState / issueFlags 的阶段1交付决策。
  不新增 CitationCheckResult 字段，不改 Report 实体，不改 ReportResponse。
```

必须新增或复用 issueFlags 表达内部交付结论：

```text
STAGE1_DELIVERY_CITATION_READY
STAGE1_DELIVERY_CITATION_GAP
OPTIONAL_CITATION_GAP
GENERATED_SECTION_REWRITE_ONLY
```

其中 `STAGE1_DELIVERY_CITATION_GAP` 只能在核心 claim 交付覆盖率不足时出现。

- [ ] Step 6：运行 writer / citation 测试。

```powershell
mvn -pl backend "-Dtest=WriterCitationGapInspectorTest,CitationAgentTest" test
```

## 11. Task 4：Report diagnosis / delivery summary 收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssembler.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/report/ReportDeliverySummaryServiceTest.java
```

- [ ] Step 1：写红灯测试 `shouldNotCountStructuredAuditWarningAsBlocker`
  - 输入 reviewer diagnosis：`missing_structured_evidence / 通用 / WARNING`。
  - 输入核心 coverage 全 TRACEABLE。
  - 断言 `blockerCount=0`，`evidenceGapCount=0`。

- [ ] Step 2：写红灯测试 `shouldKeepOptionalAndGeneratedCitationGapsOutOfDeliveryBlockers`
  - writer missing sections 包含 `pricing,strengths,weaknesses,conclusion,report_conclusion`。
  - analysisDimensions 只请求 `价格策略`，不请求 strengths/weaknesses。
  - 断言 delivery summary 可进入 `DEGRADED_READY` 前提：无核心 blocker、sourceUrls 红线满足、score 达到阶段1 MVP floor。
  - 断言 summary 中可以提示 optional audit，但不得写成“暂不可交付，存在 blocker”。

- [ ] Step 3：`ReportDiagnosisAssembler` 的 blocker 统计只接受：

```text
诊断 level=BLOCKER
且 issueScope=CORE
且对应核心字段不是 TRACEABLE/STRUCTURED_BLOCK_DIRECT
```

`ENHANCEMENT / GENERATED / UNKNOWN_AUDIT` 只进入可展示的 section diagnosis，不增加 `blockerCount / evidenceGapCount`。

- [ ] Step 4：`ReportService` 的 delivery 判定使用统一后的 `blockerCount / evidenceGapCount`。

保留硬红线：

```text
qualityScore >= STAGE_ONE_MVP_SCORE_FLOOR
blockerCount == 0
core evidenceGapCount == 0
StageOneFirstReportPolicy.hasEnoughTraceableSources(reportSourceUrls)
```

- [ ] Step 5：运行报告测试。

```powershell
mvn -pl backend "-Dtest=ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest" test
```

## 12. Task 5：任务级状态收口收敛

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/task/TaskProgressSnapshot.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
```

- [ ] Step 1：先固定双轴语义，不扩展 `AnalysisTaskStatus`。

推荐不先扩展任务枚举，避免牵连查询过滤、配额释放、恢复入口和前端状态筛选。阶段1先保持任务五态：

```text
PENDING / RUNNING / SUCCESS / FAILED / STOPPED
```

但把“降级可查看”表达在：

```text
TaskResponse.status = SUCCESS
TaskResponse.statusSummary = 执行完成，报告以阶段1降级口径可查看
ReportResponse.deliverySummary.deliveryStatus = DEGRADED_READY
ReportResponse.qualityPassed = false
TaskResponse.canViewReport = true
TaskResponse.canViewDraftReport = true
TaskResponse.interventionSummary = 需要复核但报告可查看
```

这里的 `SUCCESS` 只表示“编排执行完成且产出了可追溯交付物”，不表示“质检优秀通过”。演示和前端必须同时展示 `deliveryStatus=DEGRADED_READY` 与 `qualityPassed=false`，避免和 `two-axis-closure-direction.md` 中“调低 score 阈值假装成功”的红线混淆。

若未来要新增任务态，必须另起计划统一 API、前端、查询过滤和恢复入口，不在本计划里半截引入。

- [ ] Step 2：写红灯测试 `shouldNotStopTaskWhenInitialReviewOnlyHasDegradedViewableIssues`
  - quality_check 节点 `requiresHumanIntervention=true`，但 diagnosis 全部为 `WARNING / audit` 或 generated rewrite-only。
  - write_report 已成功且 output 带 sourceUrls。
  - report delivery summary 可判定为 `DEGRADED_READY`。
  - 断言 `resolveTaskExecution(...)` 返回 `SUCCESS`。
  - 断言 TaskResponse 的 `statusSummary / interventionSummary` 明确包含“降级 / 复核 / 可查看”语义。
  - 断言 ReportResponse 的 `deliveryStatus=DEGRADED_READY` 且 `qualityPassed=false`。

- [ ] Step 3：写红灯测试 `shouldStillStopTaskWhenInitialReviewHasRealCoreBlocker`
  - quality_check 有 `BLOCKER / targetUsers`。
  - targetUsers coverage 非 TRACEABLE。
  - 断言仍返回 `STOPPED` 或等待人工介入语义。

- [ ] Step 4：在 `NodeExecutionRecoveryPolicy` 中新增内部判断：

```text
initialReviewRequiresHumanIntervention
  不能直接等同 STOPPED。

只有当 review output 存在真实核心 blocker，或没有可追溯 writer/report 输出时，才 STOPPED。
如果 report/draft 可查看、核心字段过线、sourceUrls 红线满足、qualityScore >= 60，且阻断项均为 optional/generated/audit，则任务收口为 SUCCESS，同时错误信息不得写“初审未通过且需要人工介入”。
```

由于当前任务枚举没有 `DEGRADED_READY`，本计划明确先把任务公开状态收口为 `SUCCESS`，交付细粒度状态由 `ReportService.deliveryStatus=DEGRADED_READY` 表达。这样不会破坏既有终态释放逻辑，也不会把可查看报告留在 `STOPPED`。但 `TaskNodeViewAssembler` 必须把 `SUCCESS + DEGRADED_READY + qualityPassed=false` 展示为“降级可交付”，不能展示成“质检通过”。

- [ ] Step 5：`TaskNodeViewAssembler` 和 `TaskProgressSnapshot` 的文案必须区分：

```text
真实人工阻断：STOPPED，需补证或调整策略后继续。
降级可查看：SUCCESS + deliveryStatus=DEGRADED_READY，建议人工复核后使用。
```

- [ ] Step 6：运行任务状态测试。

```powershell
mvn -pl backend "-Dtest=NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest" test
```

## 13. Task 6：Task101 复现型集成测试与 E2E 记录

**Files:**

```text
Modify: backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
Create: docs/Tavily/progress/2026-07-09-stage1-degraded-downstream-contract-fix-verification.md
```

- [ ] Step 1：新增无 live 复现用例 `shouldDeliverDegradedReadyWhenCoreTraceableButOptionalAndGeneratedCitationGapsExist`

夹具必须模拟 Task101：

```text
两个竞品
核心四字段全部 TRACEABLE
pricing 一个 EMPTY 或 TRACEABLE 均可
writer missingCitationSections 包含 pricing,strengths,weaknesses,conclusion,report_conclusion
reviewer structuredBlocks gap 包含 通用 / 功能对比
sourceUrls >= 5 且 distinct domains >= 2
```

断言：

```text
taskStatus != STOPPED
canViewReport == true
deliveryStatus == DEGRADED_READY
qualityScore >= 60
qualityPassed == false
blockerCount == 0
evidenceGapCount == 0
writer/citation audit flags 仍可见
```

- [ ] Step 2：如果 Step 1 中 `qualityScore < 60`，不得继续修改任务状态绕过。

必须回到 Task 2 检查评分口径：

```text
是否仍把 optional/generated/unknown audit 的 SEARCH_QUALITY 计入主扣分；
是否仍把 optional/generated claim 计入 CLAIM_SUPPORT 主扣分；
是否 LLM 原始分低于 STAGE_ONE_MIN_LLM_SCORE_FLOOR；
是否存在未归类的第三条 BLOCKER 旁路。
```

只有在 `qualityScore >= 60`、`blockerCount=0`、`core evidenceGapCount=0` 同时成立时，Task101 正例才算通过。

- [ ] Step 3：新增反例 `shouldStillBlockWhenCoreTargetUsersNotTraceable`

夹具模拟 Task100 第一轮：

```text
Airtable targetUsers = EVIDENCE_NOT_COVERING
sourceUrls 红线满足
pricing optional gap 存在
```

断言：

```text
deliveryStatus != DEGRADED_READY
core evidenceGapCount > 0
blocker 指向 targetUsers / 目标用户
```

- [ ] Step 4：新增 progress 文档记录：

```text
修复前失败链路
修复后非 live 集成结果
qualityScore 从 29 恢复到 >=60 的证据；若未恢复，记录评分扣分来源
真实 E2E 待测输入
预期输出
暴露问题记录表
```

- [ ] Step 5：运行集成测试。

```powershell
mvn -pl backend "-Dtest=StageOneDegradedContractIntegrationTest" test
```

## 14. Task 7：验证命令与 live E2E

**Files:**

```text
Modify: docs/Tavily/task/2026-07-09-19-stage1-degraded-downstream-contract-root-cause-plan.md
Modify: docs/Tavily/progress/2026-07-09-stage1-degraded-downstream-contract-fix-verification.md
```

- [ ] Step 1：运行下游定向测试。

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,WriterCitationGapInspectorTest,CitationAgentTest,ReportDiagnosisAssemblerTest,ReportDeliverySummaryServiceTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

- [ ] Step 2：运行 Task18 已有非 live 分层回归。

```powershell
mvn -pl backend "-Dtest=StageOneFirstReportPolicyTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest,HeuristicSourceDiscoveryServiceTest,TavilyFastLaneProviderTest,CollectorEvidenceReadinessPolicyTest,CollectorAgentTest,NodeRetryDecisionTest,NodeExecutionRecoveryPolicyTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,WriterCitationGapInspectorTest,QualityReviewAgentTest,QualityReviewAgentCoverageContractTest,ReportDiagnosisAssemblerTest,ReportServiceTest,ReportDeliverySummaryServiceTest,TaskNodeViewAssemblerTest,StageOneDegradedContractIntegrationTest" test
```

- [ ] Step 3：运行预算护栏测试，确保没有通过扩大 Tavily 调用来掩盖下游问题。

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest" test
```

- [ ] Step 4：启动后端，使用第二轮友好输入做真实 E2E。

测试输入必须保持两竞品：

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

预期通过标准：

```text
如果核心四字段 TRACEABLE 且 sourceUrls 红线满足：
  taskStatus = SUCCESS
  canViewReport == true
  deliveryStatus == DEGRADED_READY 或 READY
  qualityScore >= 60
  qualityPassed 可以为 false，但必须通过 deliveryStatus/statusSummary 明确展示为降级可交付
  blockerCount == 0
  core evidenceGapCount == 0
  pricing/strengths/weaknesses/conclusion/report_conclusion 只作为 warning/audit/rewrite-only

如果 targetUsers 或其他核心字段仍 EVIDENCE_NOT_COVERING：
  可以 BLOCKED，但 blocker 必须指向真实核心字段
  不能把 pricing/strengths/weaknesses/conclusion 作为主阻断原因
```

- [ ] Step 5：把真实 E2E 的任务 ID、节点状态、报告 delivery summary、reviewer diagnosis、citation summary 写入 progress 文档。

## 15. 验收标准

必须全部满足：

```text
1. `QualityReviewAgent.appendStructuredEvidenceDiagnoses` 不再硬编码 ERROR。
2. structuredBlocks 缺口只有映射到真实核心字段且核心 coverage 未过线时才能 BLOCKER。
3. `通用` structured evidence gap 默认 audit/warning，不得单独触发 STOPPED。
4. `功能对比` structured evidence gap 在 coreFeatures 已 TRACEABLE 时不得 BLOCKER。
5. `pricing / strengths / weaknesses` citation gap 不进入首报交付 blocker。
6. 未请求的 `strengths / weaknesses` 不进入 missingCitationSections 主列表。
7. `conclusion / report_conclusion` 不进入首报交付分母，只触发保守改写或 warning。
8. Citation 保留全量审计信息，但交付风险使用 stage1 delivery scope。
9. 不新增 `CitationCheckResult.deliveryCitationCoverageRate`，内部交付率只影响 risk/evidenceState/issueFlags。
10. 下游所有 issue scope 分类只调用 `StageOneFirstReportPolicy.classifyIssueScope(FirstReportIssueContext)`，不得在各层新增第二入口或第二映射表。
11. `requestedDimensions` 只来自任务请求/任务计划快照的 `analysisDimensions`，并由 `StageOneFirstReportPolicy` 归一化后传递给 writer / citation / report diagnosis。
12. ReportDiagnosis 的 blockerCount / evidenceGapCount 只统计核心交付范围。
13. ReportService 在核心字段、sourceUrls 红线、`qualityScore >= 60` 同时过线时可给出 `DEGRADED_READY`。
14. TaskResponse 若使用 `SUCCESS` 表示降级可查看，必须同时通过 statusSummary / interventionSummary / deliveryStatus / qualityPassed=false 区分“执行成功”和“质检通过”。
15. NodeExecutionRecoveryPolicy 不把降级可查看报告统一收口成 `STOPPED`。
16. Task101 复现型无 live 集成测试必须证明 `qualityScore >= 60`，不能只证明 blockerCount 归零。
17. 真实核心字段缺证仍能阻断，例如 targetUsers 未覆盖时不得伪装成功。
18. 不提高 Tavily 预算，不打开 Gate 1 / Gate 2 多轮补采。
19. Task101 复现型无 live 集成测试通过。
20. 真实 E2E 结果能证明失败边界被收敛到“核心字段缺证 / sourceUrls 红线 / 外部采集不可用”，而不是增强字段或自动章节旁路误杀。
```

## 16. 明确不做

```text
不只改 `severity("ERROR")` 为 `WARNING` 后结束。
不只在 NodeExecutionRecoveryPolicy 给 STOPPED 加一个特殊豁免。
不调低 citationCoverageRate 阈值来掩盖分母污染。
不删除 citation / structuredBlocks 诊断。
不把所有 reviewer 问题都降级。
不新增第二套章节到字段映射。
不让各层各自实现 `classifyIssueScope` overload；统一入口只能在 `StageOneFirstReportPolicy`。
不从 reportContent 章节、LLM 输出文本或节点展示文案反推 requestedDimensions。
不新增 CitationCheckResult / ReportResponse 字段来承载 deliveryCitationCoverageRate；本轮只做内部决策变量。
不把 sourceUrls 红线放松。
不把 `taskStatus=SUCCESS` 展示成“质检通过”；必须同时展示 `deliveryStatus=DEGRADED_READY` 和 `qualityPassed=false`。
不让单竞品用例作为本轮毕业基线；继续使用两个竞品规避当前已知系统限制。
不在本计划中半截新增 `AnalysisTaskStatus.DEGRADED_READY`；若要扩展任务枚举，另立 API/前端/恢复链路计划。
```

## 17. 执行顺序约束

必须按顺序执行：

```text
StageOneFirstReportPolicy issue scope
  -> Reviewer structuredBlocks
  -> Writer / Citation delivery denominator
  -> Report diagnosis / delivery summary
  -> NodeExecutionRecoveryPolicy task closeout
  -> Task101 integration fixture
  -> non-live regression
  -> live E2E
```

禁止先改任务状态。任务状态只是最后收口，根因在前面的 diagnosis / citation / report delivery 是否把同一个缺口分类一致。

## 18. 自检清单

- [ ] 本计划没有把根因后移到 live E2E 之后再分析。
- [ ] 本计划没有把问题简化成上游搜索失败。
- [ ] 本计划没有只修 STOPPED 状态。
- [ ] 本计划没有只修 structuredBlocks 的 severity 字符串。
- [ ] 本计划没有通过调低 citation 阈值解决问题。
- [ ] 本计划没有通过调低 qualityScore 门槛解决问题。
- [ ] 本计划没有假设 blocker 归零后分数自然过 60，而是要求测试显式证明。
- [ ] 本计划没有在 reviewer / writer / report 层复制第二套字段映射。
- [ ] 本计划没有让 citation 层用另一套 issue scope 入口或 overload。
- [ ] 本计划没有从 reportContent 章节反推 requestedDimensions。
- [ ] 本计划没有扩展 CitationCheckResult DTO。
- [ ] 本计划没有把 `SUCCESS + DEGRADED_READY` 伪装成质检通过。
- [ ] 本计划保留了真实核心字段缺证的阻断能力。
- [ ] 本计划保留了 sourceUrls 红线。
- [ ] 本计划保留了 optional/generated 缺口的审计可见性。
- [ ] 本计划有 Task101 正例和 Task100 反例，能防止“全放行”回归。
