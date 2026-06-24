# Revision And Rewrite Closure Plan

> 日期：2026-06-24
> 目标：把修订与重写链路从“动态补图能力存在”收口为“由 Orchestrator 决策驱动、可回放、可恢复、可演示”的正式质量回流链路。
> 前提：3.4 P1 已完成自动化收口，动态补图已从 Reviewer 直接驱动升级为 `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DynamicTaskGraphService`。

---

## 1. 当前阶段

当前阶段：修订与重写链路诊断与收口文档已完成，P1 动态补图已有自动化支撑，live dev 真实动态补图证据仍需后续补齐。

- [x] 诊断文档：`docs/superpowers/revision-and-rewrite/problem/RevisionAndRewrite.md`
- [x] 收口计划：`docs/superpowers/revision-and-rewrite/plan/2026-06-24-revision-and-rewrite-closure-plan.md`
- [x] P1 动态补图：终审失败后由 Orchestrator 决策驱动。
- [x] P1 smoke：动态分支和 replay 已由 `OrchestrationRuntimeFeedbackSmokeTest` 覆盖。
- [ ] live dev 真实动态补图证据包：后续补齐。

---

## 2. 收口目标

1. 总蓝图中修订与重写链路从“待诊断”更新为“诊断 + 方案已完成，实施已有 P1 支撑”。
2. 动态补图长期口径固定为：
   ```text
   Reviewer 发现质量问题 -> Orchestrator 生成决策 -> Policy 校验 -> Adapter 转 mutation -> DynamicTaskGraphService 落图
   ```
3. legacy `RevisionDirective` 的定位固定为：
   ```text
   报告展示 / Writer 修订输入 / Orchestrator 兼容适配输入
   ```
4. 演示口径固定为：
   ```text
   终审失败不是终点，系统能解释为何补证、补哪一段、生成了哪些动态节点、恢复游标在哪里。
   ```

---

## 3. 任务拆解

### Task 1：固定动态补图正式入口

**文件：**

- 已完成：`backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- 已完成：`backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`
- 已完成：`backend/src/main/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphService.java`

**验收命令：**

```powershell
mvn -pl backend "-Dtest=DecisionExecutorAdapterTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest" test
```

期望：

- 只对 `allowed=true` 的策略结果生成可执行 mutation。
- 动态计划能生成新 plan version。
- 动态节点保留 `branchKey`、`dynamicNode`、`originNodeName`。

### Task 2：固定 legacy 兼容边界

**文件：**

- 已完成：`backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`
- 已完成：`backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java`

**验收命令：**

```powershell
mvn -pl backend "-Dtest=RevisionDirectiveTest,OrchestrationDecisionAdapterTest" test
```

期望：

- `RevisionDirective.normalized()` 保持历史兼容。
- `OrchestrationDecisionAdapter` 能把补证、重跑、改写、人工介入语义转成正式决策。
- 新增编排动作不得直接塞回 `RevisionDirective`。

### Task 3：验证实链演示证据与文档闭环

**状态：** smoke 与文档已存在；本 Task 只验证自动化证据和回链，不再把已存在文档标记为待新增。

**文件：**

- 已完成：`backend/src/test/java/cn/bugstack/competitoragent/integration/OrchestrationRuntimeFeedbackSmokeTest.java`
- 已完成：`docs/superpowers/revision-and-rewrite/problem/RevisionAndRewrite.md`
- 已完成：`docs/superpowers/revision-and-rewrite/plan/2026-06-24-revision-and-rewrite-closure-plan.md`

**验收命令：**

```powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeFeedbackSmokeTest" test
```

期望：

- `quality_check_final` 输出 `passed=false` 后触发 Orchestrator 决策。
- 策略结果 `allowed=true`。
- 动态分支为 `root/review-2`。
- 分支节点包含补证、抽取、分析、改写、复核。
- replay 包含 Orchestrator 决策和 checkpoint。
- 缺来源路径显示 `MISSING_SOURCE`。

---

## 4. live 验收脚本

1. 创建固定任务。
2. 执行到终审失败。
3. 查询节点列表，确认 `quality_check_final` 输出质量缺口。
4. 查询 workflow events，确认：
   - `ORCHESTRATION_DECISION_RECORDED`
   - `ORCHESTRATION_CHECKPOINT_UPDATED`
5. 查询任务节点，确认 `root/review-N` 动态分支。
6. 查询 replay，确认决策原因、策略结果、checkpoint 和 `sourceUrls / evidenceState`。
7. 如果动态分支仍失败，确认系统进入人工介入或保留可解释失败，而不是伪成功。

---

## 5. 不纳入本轮

1. 不把所有动态分支模板改成 LLM 自由生成。
2. 不移除 `RevisionDirective` 字段，避免破坏历史报告展示。
3. 不新增独立 checkpoint 表，P1 继续复用 workflow event / replay。
4. 不把前置 `CollaborationPlan` 混入本链路，P2 单独做。

---

## 6. 完成判定

修订与重写链路满足以下条件时，本轮关闭：

- [x] 诊断文档存在。
- [x] 方案文档存在。
- [x] 总蓝图指向诊断和方案。
- [x] 动态补图正式入口已经迁移到 Orchestrator 决策链路。
- [x] legacy `RevisionDirective` 边界有注释和适配测试保护。
- [x] P1 smoke 覆盖动态补图和 replay。
- [x] Task 3 已统一为验证型任务，不再把已存在文档误标为待新增。
- [ ] 后续真实任务证据包补齐后，再把 live 验收升为已完成。
