# Revision And Rewrite Chain Diagnosis

> 日期：2026-06-24
> 范围：修订与重写链路，只诊断 `RevisionDirective`、`CompensationGraphAssembler`、`DynamicTaskGraphService`、`DynamicPlanAppender`、Writer 改写节点、终审复核，以及它们和 3.4 P1 Orchestrator 的关系。
> 当前结论：修订与重写链路已经具备动态补图底座，P1 已把终审失败后的动态补图入口升级为 Orchestrator 决策驱动；但 legacy `RevisionDirective` 仍保留编排字段，必须持续作为兼容输入而不是长期正式协议。

---

## 1. 当前阶段

当前阶段：修订与重写链路已完成 P1 自动化收口，可支撑稳定演示版的质量回流路径。

- [x] 静态改写节点存在：`rewrite_report`
- [x] 终审节点存在：`quality_check_final`
- [x] 动态补图服务存在：`DynamicTaskGraphService`
- [x] 动态计划挂载协作者存在：`DynamicPlanAppender`
- [x] 动态节点组装器存在：`CompensationGraphAssembler`
- [x] P1 决策链路存在：`OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation`
- [x] P1 smoke 已验证动态分支：`collect_revision_evidence_v2_1 -> extract_revision_patch_v2 -> analyze_revision_patch_v2 -> rewrite_revision_patch_v2 -> quality_check_revision_patch_v2`
- [ ] live dev 真实动态补图证据包仍需后续补齐。

---

## 2. 真实代码证据

### 2.1 静态修订闭环

`ExecutionPlanDefinitionBuilder` 生成标准任务 DAG 时包含：

```text
write_report
quality_check
rewrite_report
quality_check_final
```

其中：

1. `rewrite_report` 是非必需节点，依赖 `quality_check`，配置 `trigger=review_failed`。
2. `quality_check_final` 是非必需节点，依赖 `rewrite_report`，配置 `qualityPolicy=final pass after revision`。
3. 这条静态闭环适合处理初审失败后的普通改写。

### 2.2 legacy 修订指令

`RevisionDirective` 当前字段包含：

```text
category
actionType
orchestrationAction
priority
targetNode
targetSection
searchFeedback
searchQueries
sourceUrls
expectedOutcome
```

P1 后该类已有中文注释：

```text
3.4 P1 之后，该对象只作为 Reviewer 兼容期展示/修订建议载体。
新的动态补图不得再把 orchestrationAction 当作唯一正式决策来源。
```

这说明当前仍保留 `orchestrationAction`，但它的角色已经从正式决策降级为兼容信号。

### 2.3 P1 动态补图链路

`DynamicPlanAppender` 当前流程：

```text
quality_check_final failed
  -> readRevisionDirectives(...)
  -> buildOrchestrationContext(...)
  -> OrchestrationDecisionService.decide(...)
  -> DecisionPolicyService.evaluate(...)
  -> DecisionExecutorAdapter.toMutation(...)
  -> OrchestrationTraceService.recordDecision(...)
  -> DynamicTaskGraphService.createDynamicPlan(...)
  -> OrchestrationTraceService.recordCheckpoint(...)
```

这条路径把动态补图的正式决策点从 Reviewer 输出迁移到了 Orchestrator + policy。

### 2.4 动态分支模板

`DecisionExecutorAdapter` 对 `CREATE_SUPPLEMENT_BRANCH` 输出首个动态节点：

```text
collect_revision_evidence_vN_1
```

`CompensationGraphAssembler` / `DynamicTaskGraphService` 继续负责把动态计划扩展成完整分支节点。P1 smoke 证明确认最终分支包含：

```text
collect_revision_evidence_v2_1
extract_revision_patch_v2
analyze_revision_patch_v2
rewrite_revision_patch_v2
quality_check_revision_patch_v2
```

### 2.5 trace 与 checkpoint

动态补图成功后写入：

```text
ORCHESTRATION_DECISION_RECORDED
ORCHESTRATION_CHECKPOINT_UPDATED
```

replay 能看到决策与 checkpoint，是演示“系统为何补图、补到哪里、如何恢复”的关键证据。

---

## 3. 已收口能力

| 能力 | 当前状态 | 证据 |
| --- | --- | --- |
| 初审失败后静态改写 | 已有 | `rewrite_report` |
| 改写后终审 | 已有 | `quality_check_final` |
| 终审失败后补证动态分支 | 已完成 P1 | `DynamicPlanAppender` + `OrchestrationRuntimeFeedbackSmokeTest` |
| 决策策略校验 | 已完成 P1 | `DecisionPolicyService` |
| 动态计划版本 | 已有 | `TaskPlanVersioner / DynamicTaskGraphService` |
| 分支可回放 | 已完成 P1 | `OrchestrationTraceService / TaskReplayProjectionService` |
| legacy 兼容 | 已完成 P1 | `RevisionDirective` 注释 + `OrchestrationDecisionAdapter` |

---

## 4. Blocking 分类

### P0：必须保持的硬边界

1. 动态补图不得绕过 `DecisionPolicyService`。
2. `RevisionDirective.orchestrationAction` 不得重新成为唯一正式决策来源。
3. 动态分支必须记录 `decisionId`、`branchKey`、`planVersion` 和 `evidenceState`。
4. 自动补图必须受 `maxAutoDecisions` 和分支数量限制保护。
5. replay 必须能解释补图原因和 checkpoint。

### P1：演示稳定性需要继续观察

1. live dev 下动态补证分支是否能真正补齐外部证据，需要真实任务验证。
2. 动态补图后 Writer 改写是否能把补证结果稳定写入报告，需要报告页证据视图验证。
3. 多次自动补图失败后的人工介入路径需要后续 live 样本。

### P2：后续架构演进

1. `DynamicPlanMutation` 需要继续支持 `RERUN_FROM_NODE` 和 `MARK_WAITING_INTERVENTION` 的执行路径。
2. `CompensationGraphAssembler` 长期应完全迁移到 mutation/template 输入，而不是 legacy directive 输入。
3. P2 前置协作规划接入后，初始 `CollaborationPlan` 应能声明质量检查点和回流策略。

---

## 5. 做什么

1. 保留静态 `quality_check -> rewrite_report -> quality_check_final` 作为普通修订闭环。
2. 保留动态补图作为终审失败后的质量回流能力。
3. 让动态补图只消费已校验的 `DynamicPlanMutation`。
4. 把 legacy `RevisionDirective` 作为历史任务、报告展示和过渡适配对象。
5. 持续把动态分支、决策、策略结果和 checkpoint 纳入 replay。

---

## 6. 不做什么

1. 不重写 `DagExecutor` 主循环。
2. 不让 Orchestrator 自由生成任意节点。
3. 不在本链路直接做完整 Citation Agent。
4. 不把动态补图扩成无限自治循环。
5. 不把静态改写和动态补证混成一个不可解释的大节点。

---

## 7. 验收口径

### 自动化验收

```powershell
mvn -pl backend "-Dtest=CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,DagExecutorTest,OrchestrationRuntimeFeedbackSmokeTest" test
```

期望：

- `DynamicPlanMutation` 可以生成动态分支。
- `quality_check_final` 失败后能生成 Orchestrator 决策。
- 策略 `allowed=true` 后可以挂载动态计划。
- replay 能看到决策和 checkpoint。
- 缺来源能显示 `MISSING_SOURCE`。

### live 验收

1. 真实任务执行到终审失败。
2. 检查 `ORCHESTRATION_DECISION_RECORDED`。
3. 检查 `ORCHESTRATION_CHECKPOINT_UPDATED`。
4. 检查任务节点出现 `root/review-N` 动态分支。
5. 检查动态补证后报告是否被改写。
6. 检查最终复核结果和失败时人工介入建议。

---

## 8. 相关文档

- [质量审查链路诊断](../../quality-review/problem/QualityReview.md)
- [3.4 Agent 协作编排架构规格](../../agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md)
- [3.4 P1 终审失败回流 MVP 实施计划](../../agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)
