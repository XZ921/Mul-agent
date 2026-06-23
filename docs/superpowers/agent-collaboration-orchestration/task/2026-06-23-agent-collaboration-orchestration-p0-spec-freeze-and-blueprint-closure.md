# 3.4 Agent 协作编排 P0 规格冻结与蓝图收口

> 本文件是 P0 收口型执行记录，不是 P1 代码实施计划。P0 的目标是把 3.4 的定位、契约、边界、蓝图回链和下一阶段入口冻结，避免在 P1 编码时继续扩大范围。

## 1. 结论

P0 可以收口，原因如下：

1. 3.4 已在总蓝图中登记为 `Agent 协作编排层 / Agent 协作编排引擎`，并明确它不是第十条业务链路，而是横跨任务执行、质量回流和对话动作入口的协作决策层。
2. 架构规格已冻结 Orchestrator-first 双阶段架构：任务开始前输出 `CollaborationPlan`，运行中输出 `OrchestrationDecision`。
3. Reviewer 与 Orchestrator 的职责边界已明确：Reviewer 只产出质量事实和诊断，Orchestrator 才产出编排决策。
4. 隐式编排点已经被列入迁移清单，P1 不再继续扩大诊断范围，而是优先打通终审失败后的运行期回流 MVP。
5. 所有新增协作契约保留 `sourceUrls` 或显式证据缺口状态，继续遵守无幻觉红线。
6. P1 已有第一份可执行计划，可以直接承接 P0 产物进入代码落地。

## 2. P0 执行计划与状态

当前阶段：P0 规格冻结与蓝图收口

| 步骤 | 核心目标 | 依赖前置条件 | 预期耗时 | 状态 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | 新增 3.4 架构规格 | 3.3 证据边界已基本稳定 | 0.5 天 | 已完成 | [架构规格](../specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md) |
| 2 | 总蓝图加入 Agent 协作编排引擎 | 架构规格中已明确横切定位 | 0.5 天 | 已完成 | [总蓝图 3.4](../../../specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md) |
| 3 | 冻结 Reviewer 与 Orchestrator 边界 | `QualityDiagnosis` 与 `OrchestrationDecision` 已定义 | 0.5 天 | 已完成 | 架构规格第 4.1、6.6、6.7 节 |
| 4 | 列出隐式编排点与迁移方向 | 当前代码已有动态补图、恢复、对话动作入口 | 0.5 天 | 已完成 | 架构规格第 4.2、8、9 节 |
| 5 | 明确 `sourceUrls / evidenceState` 红线 | 总蓝图已有无幻觉原则 | 0.5 天 | 已完成 | 架构规格第 4.3、6、12 节 |
| 6 | 冻结前置规划契约 | 任务开始前需要可审计协作计划 | 0.5 天 | 已完成 | `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint` |
| 7 | 冻结运行反馈契约 | P1 以终审失败回流作为 MVP | 0.5 天 | 已完成 | `DecisionPolicyRuleSet / DecisionPolicyResult / DecisionExecutorAdapter / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` |
| 8 | 建立 P1 执行入口 | P0 边界不再变动 | 0.5 天 | 已完成 | [P1 运行期反馈 MVP 实施计划](2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md) |

进度可视化：

- [x] 架构规格：已完成
- [x] 总蓝图回链：已完成
- [x] 职责边界冻结：已完成
- [x] 隐式编排点清单：已完成
- [x] 契约红线：已完成
- [x] P1 执行入口：已完成

## 3. P0 验收口径

P0 验收不是“写完代码”，而是满足以下收口条件：

| 验收项 | 判定 |
| --- | --- |
| 3.4 定位清晰 | 通过。它是横切协作编排层，不是新增业务链路。 |
| P1 / P2 / P3 边界清晰 | 通过。P1 做终审失败运行期回流 MVP，P2 做前置规划，P3 做更多 Agent 接入和 UI 可视化。 |
| Reviewer 不再承担最终编排决策 | 通过。Reviewer 输出 `QualityDiagnosis`，Orchestrator 输出 `OrchestrationDecision`。 |
| 隐式编排点被显式登记 | 通过。`CompensationGraphAssembler`、`RevisionDirective.normalized()`、`DynamicTaskGraphService`、`ClarificationOrchestrator`、`DagExecutor.shouldExecuteNode()`、`RecoveryEngine / NodeExecutionRecoveryPolicy`、`TaskActionTranslator / ConversationService` 已进入迁移清单。 |
| 新契约可追溯 | 通过。新契约必须携带 `sourceUrls` 或 `evidenceState`，不能让动态计划变更成为无来源动作。 |
| P1 可以进入实施 | 通过。P1 可执行计划已把代码文件、测试入口、验收命令和回放路径拆开。 |

## 4. P0 不做范围

P0 不写生产代码，不新增数据库表，不接 UI，不迁移全部旧链路，不把所有 Agent 改成 LLM 自治调度。

这些工作进入后续阶段：

| 阶段 | 目标 |
| --- | --- |
| P1 | 终审失败后生成 `OrchestrationDecision`，经过策略校验，转换为动态补图动作，并落 `DecisionTrace / OrchestratorCheckpoint`。 |
| P2 | 在任务开始前生成 `CollaborationGoal / CollaborationPlan / AgentRoleAssignment`，让固定 DAG 模板升级为可审计协作计划。 |
| P3 | 扩展更多 Agent 建议入口、对话动作预览、UI 轨迹和多轮回放能力。 |

## 5. 与稳定演示版的关系

[稳定演示版执行计划](../../plans/2026-06-23-stable-demo-version-execution-plan.md)把 Day 1 定义为“冻结演示范围与 3.4 P0 收口”。本文件就是 Day 1 中 P0 的收口证据。

稳定演示版下一步不再继续补 P0 文档，而是进入以下工作：

1. 执行 P1 运行期反馈 MVP 计划。
2. 补齐 P1 相关测试，尤其是策略校验、动态补图、trace/checkpoint 与 replay。
3. 选择质量审查链路或修订与重写链路做旧任务诊断与方案文档。
4. 用真实任务或可回放任务验证稳定演示路径。

## 6. P1 接入口

P1 从以下闭环开始：

```text
quality_check_final failed
  -> QualityDiagnosis
  -> OrchestratorAgent / OrchestrationDecisionService
  -> OrchestrationDecision
  -> DecisionPolicyService
  -> DecisionPolicyResult
  -> DecisionExecutorAdapter
  -> DynamicPlanMutation
  -> DynamicTaskGraphService / CompensationGraphAssembler
  -> DecisionTrace / OrchestratorCheckpoint
  -> replay / resume
```

P1 的第一份可执行计划已经放在同目录：

- [2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md](2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)

## 7. 最终状态

当前阶段：P0 规格冻结与蓝图收口

- [x] 信息采集：已完成
- [x] 边界诊断：已完成
- [x] 方案冻结：已完成
- [x] 蓝图回链：已完成
- [x] P1 入口：已完成

P0 状态：可以在总蓝图中标记为完成，并将主线切到 3.4 P1 MVP 代码落地。
