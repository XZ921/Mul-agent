# Quality Review Chain Diagnosis

> 日期：2026-06-24
> 范围：质量审查链路，只诊断 `QualityReviewAgent`、Reviewer prompt、质量诊断输出、报告质量状态写回，以及它和 3.4 P1 Orchestrator 的职责边界。
> 当前结论：质量审查代码已经具备可演示能力，但正式链路边界必须从“Reviewer 同时给修订建议和隐式编排动作”收口为“Reviewer 输出质量事实，Orchestrator 决定编排动作”。

---

## 1. 当前阶段

当前阶段：质量审查链路已完成 P1 边界收口，可作为稳定演示版的自检能力。

- [x] 质量审查 Agent 存在：`backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- [x] 质量诊断契约存在：`backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/QualityDiagnosis.java`
- [x] 修订计划 DTO 存在：`backend/src/main/java/cn/bugstack/competitoragent/model/dto/RevisionPlan.java`
- [x] Reviewer prompt 已加入职责边界：`backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- [x] P1 后 `revisionDirectives` 已降级为兼容期修订建议，由 Orchestrator 统一决策动态补图。
- [x] 自动化证据存在：`QualityReviewAgentTest`、`PromptTemplateServiceTest`、`OrchestrationRuntimeFeedbackSmokeTest`
- [ ] live dev - PostgreSQL：真实库下验证 `Report.qualityScore / qualityPassed / qualityIssues` 写回和 DOMAIN 记忆写回。
- [ ] live dev - Redis：真实任务锁、恢复或并发保护不影响质量审查节点推进。
- [ ] live dev - RocketMQ：真实工作流事件发布后，replay 能看到终审失败后的 Orchestrator 决策。
- [ ] live dev - 外部 LLM：真实模型返回质量 JSON、慢响应和坏 JSON 重试行为需要补证据包。

---

## 2. 真实代码证据

### 2.1 Reviewer 的输入边界

`QualityReviewAgent` 当前读取报告、证据源、当前任务知识快照和 RAG 上下文：

```text
ReportRepository
EvidenceSourceRepository
CompetitorKnowledgeRepository
TaskKnowledgeSnapshotResolver
AgentContext.taskRagPromptContext
```

关键事实：

1. 没有报告正文时直接失败，不会伪造质检结果。
2. 证据列表来自 `EvidenceSource`，结构化覆盖摘要来自 `CompetitorKnowledge` 当前任务快照。
3. `TaskKnowledgeSnapshotResolver.resolveCurrentTaskSnapshots(...)` 避免旧 rerun 快照污染当前质检。
4. `reviewMode` 根据 `qualityPolicy=final pass after revision` 区分初审和终审。

### 2.2 Reviewer 的容错机制

`QualityReviewAgent.invokeQualityReview(...)` 对模型 JSON 解析失败做最多 3 次处理：

```text
REVIEW_JSON_MAX_ATTEMPTS = 3
第一次消费当前 LLM 响应
解析失败后补发收紧版 retry prompt
最终仍失败时返回 AgentResult.failed(...)
```

这符合当前工程“外部大模型调用必须有 try-catch 和重试机制”的规范。异常不会被吞掉，最终会进入 Agent 执行失败输出。

需要注意的是，Reviewer 当前只负责“质量 JSON 修复重试”，没有独立的节点级 deadline；真实超时主要由底层 LLM provider 配置承担，例如 `OpenAiCompatibleClient` 使用 `aiProps.timeoutSeconds`。因此 live dev 需要单独记录慢响应、超时失败和 3 次 JSON 修复重试带来的额外时延。

### 2.3 Reviewer 的输出边界

Reviewer 输出内容包括：

```text
reviewStage
score
passed
requiresHumanIntervention
autoRewriteAllowed
dimensions
diagnoses
issues
summary
revisionPlan
revisionDirectives
nextActions
```

其中 `revisionDirectives` 已有明确注释：

```text
3.4 P1 起，revisionDirectives 只作为兼容期修订建议输出；
真实编排动作由 OrchestrationDecisionService 读取质量事实后统一决策。
```

这个注释是当前职责边界的核心事实：Reviewer 可以描述问题和建议，但不能成为长期正式编排决策来源。

### 2.4 质量写回与记忆写回

Reviewer 会同步写回报告主表：

```text
report.qualityScore
report.qualityPassed
report.qualityIssues
```

终审通过后才允许写回 DOMAIN 记忆：

```text
writeVerifiedDomainKnowledgeBack(..., finalPass, passed)
```

这条边界重要，因为它避免把未通过质检的报告结论污染跨任务记忆。写回请求显式携带 `sourceUrls`、`evidenceCoverage`、质量诊断上下文和复用原因。

---

## 3. 已收口能力

| 能力 | 当前状态 | 代码证据 | 测试证据 |
| --- | --- | --- | --- |
| 报告质量评分 | 已有 | `QualityReviewAgent.calculateDiagnosisDrivenScore(...)` | `QualityReviewAgentTest.shouldProduceExplainableDimensionsAndDiagnosesForUnsupportedClaims` |
| 证据引用审查 | 已有 | `[证据：EID]` 检测、章节级 claim audit、coverage snapshot | `QualityReviewAgentTest.shouldAppendUnsupportedClaimIssueWhenConclusionLacksEvidenceCitation`、`shouldDetectClaimLevelGapWithinFeatureComparisonSection`、`shouldDetectSentenceLevelGapEvenWhenSameParagraphContainsAnotherEvidenceCitation` |
| 质量诊断输出 | 已有 | `QualityDiagnosis / QualityIssue / QualityDimension` | `QualityReviewAgentTest.shouldProduceExplainableDimensionsAndDiagnosesForUnsupportedClaims`、`shouldRequireHumanInterventionFromDiagnosisSeverityInsteadOfHardcodedScoreOnly` |
| 初审与终审区分 | 已有 | `isFinalReview(...)` 读取 `qualityPolicy` | `QualityReviewAgentTest.shouldAllowAutoRewriteForInitialReviewWhenCoverageGapIsOnlyMissingEvidenceAndEmptySections`、`shouldAllowAutoRewriteForFinalReviewWhenCoverageGapIsOnlyMissingEvidenceAndEmptySections` |
| JSON 失败重试 | 已有 | `invokeQualityReview(...)` | `QualityReviewAgentTest.shouldRetryWhenReviewerReturnsBrokenJsonBeforeSuccessfulQualityReview` |
| 修订计划输出 | 已有 | `RevisionPlan` 与 `RevisionPlan.RevisionItem` | `QualityReviewAgentTest.shouldEmitRevisionDirectivesAndSearchQualityFeedbackForSearchIssues` |
| Orchestrator 职责隔离 | 已完成 P1 | Reviewer prompt 和 `revisionDirectives` 兼容注释 | `PromptTemplateServiceTest.reviewerDefaultTemplateShouldNotAskForOrchestrationAction`、`OrchestrationDecisionServiceTest` |
| 终审失败回流 | 已完成 P1 smoke | `DynamicPlanAppender` 调用 Orchestrator 决策链 | `OrchestrationRuntimeFeedbackSmokeTest.shouldProduceReplayableOrchestrationDecisionCheckpointAndDynamicBranchThroughApiSmoke`、`shouldExposeMissingSourceEvidenceStateWhenReviewerDirectiveHasNoSourceUrls` |

---

## 4. Blocking 分类

### P0：必须保持的硬边界

1. Reviewer 不再新增 `orchestrationAction` prompt 要求。
2. Reviewer 输出的 `revisionDirectives` 只作为展示 / 兼容输入。
3. 终审失败后的补证、重跑、改写、人工介入必须经 `OrchestrationDecisionService -> DecisionPolicyService`。
4. 缺 `sourceUrls` 时必须显式保留 `evidenceState=MISSING_SOURCE` 或进入补证 / 人工介入解释。
5. 终审未通过不得写入 DOMAIN 记忆。

### P1：演示稳定性需要继续观察

1. live dev 下外部 LLM 返回的质量 JSON 是否稳定，仍需真实任务证据包补齐。
2. 报告页是否完整展示 `diagnoses / revisionPlan / revisionDirectives / nextActions`，需要前端或 API 验收。
3. 质量诊断到 replay timeline 的展示依赖 `TaskReplayProjectionService`，需要和 Orchestrator trace 一起验证。
4. Reviewer 最多 3 次 JSON 修复重试会放大外部 LLM 慢响应时延；底层 provider 超时配置和 Agent 节点失败输出需要在 live dev 证据包中单独记录。

### P2：后续架构演进

1. `QualityDiagnosis` 缺正式 `diagnosisId`，P1 目前用触发节点 + 下标生成临时引用。
2. Reviewer 的诊断事实可以进一步拆为更稳定的 `QualityFinding` 列表。
3. Citation Agent 后续接入后，需要把证据引用核查从 Reviewer 中拆出一部分。

---

## 5. 做什么

1. 保持 Reviewer 作为质量事实生产者。
2. 保持 `RevisionPlan` 和 `revisionDirectives` 用于报告页、Writer 改写输入和历史兼容。
3. 让 Orchestrator 消费 `diagnoses / revisionDirectives / sourceUrls / evidenceState` 后决定编排动作。
4. 在文档、测试和 prompt 中持续保护 Reviewer / Orchestrator 职责边界。
5. 继续把终审通过后的领域记忆写回绑定 `sourceUrls` 和质量诊断上下文。

---

## 6. 不做什么

1. 不让 Reviewer 直接创建动态分支。
2. 不在 Reviewer prompt 中新增 `CREATE_SUPPLEMENT_BRANCH / CREATE_RERUN_BRANCH / CREATE_REWRITE_BRANCH`。
3. 不把 `revisionDirectives.orchestrationAction` 作为长期正式协议。
4. 不因为质量审查失败就把任务伪装成成功。
5. 不在本链路内补完整 Citation Agent。

---

## 7. 验收口径

### 自动化验收

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest,QualityReviewAgentTest,OrchestrationRuntimeFeedbackSmokeTest" test
```

期望：

- Reviewer prompt 不要求模型输出编排动作。
- Reviewer JSON 解析失败后会重试。
- 终审失败后 Orchestrator 可以接管动态补图。
- 缺来源时决策链路保留 `MISSING_SOURCE`。

### live 验收

1. 创建固定竞品分析任务。
2. 执行到 `quality_check` 或 `quality_check_final`。
3. 报告页能看到质量评分、诊断、修订计划和下一步建议。
4. 终审失败时，replay 能看到 Orchestrator 决策。
5. 终审通过后，DOMAIN 记忆写回必须包含 `sourceUrls`。

---

## 8. 相关文档

- [稳定演示版执行计划](../../plans/2026-06-23-stable-demo-version-execution-plan.md)
- [3.4 Agent 协作编排架构规格](../../agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md)
- [3.4 P1 终审失败回流 MVP 实施计划](../../agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)
