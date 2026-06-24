# 稳定演示版执行计划

> 日期：2026-06-23  
> 目标读者：项目负责人 / 开发执行者 / 演示验收者  
> 计划目标：7 天内交付一个稳定可演示版本，优先证明主链路能跑通、质量回流能解释、关键过程可观测、失败后可恢复。

---

## 1. 一句话结论

7 天内不要试图把 6 条旧链路全部补完。最稳的交付策略是：

```text
先把 3.4 P0/P1 做成可运行的最小闭环，
再选“质量审查 -> 修订重写 -> 交付审计”这条最贴近演示的链路做完整诊断、方案、测试和实链证据。
```

这样演示时看到的不是“代码很多但状态表空着”，而是一个能解释自己如何计划、如何执行、如何审查、如何补救、如何导出的 Agent 协作系统。

---

## 2. 稳定演示版定义

演示版只承诺以下 6 件事稳定可见：

1. **任务可创建**：用户输入竞品分析任务后，系统能生成计划预览和正式 DAG。
2. **主链路可执行**：采集、提取、分析、写作、初审、改写、终审至少有一条代表性任务跑通。
3. **证据可追溯**：关键结构化结果、质量诊断、报告和导出结果必须展示 `sourceUrls` 或明确的 `evidenceState`。
4. **质量回流可解释**：`quality_check_final` 失败或发现缺口时，系统能说明缺什么证据、为什么需要补证 / 改写 / 人工介入。
5. **编排决策可回放**：3.4 P1 的 `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DecisionTrace / OrchestratorCheckpoint` 至少能在一个测试或真实任务中闭环。
6. **交付可审计**：报告页、导出包、审计日志、SSE / replay 能支撑演示者讲清楚过程。

稳定演示版不等于全部业务链路正式毕业。它的验收重点是“主航道能被用户看见、相信、复现”。

---

## 3. 当前现状判断

### 3.1 已经有生产代码

| 链路 | 实际代码状态 | 演示价值 | 当前短板 |
| --- | --- | --- | --- |
| 分析推理 | 已完成，核心代码约 1200+ 行 | 支撑报告结论 | 缺正式诊断/方案文档 |
| 报告写作 | 已完成，核心代码约 600+ 行 | 演示最终产物 | 缺正式诊断/方案文档 |
| 质量审查 | 已完成，核心代码约 1900+ 行 | 最适合展示 Agent 自检能力 | 缺诊断/方案文档，需拆清 Reviewer 与 Orchestrator 职责 |
| 修订与重写 | 已完成，动态补图基础存在 | 直接支撑 3.4 P1 | 缺诊断/方案文档，需从 `RevisionDirective` 迁移到 Orchestrator 决策 |
| 对话协同 | 已完成，入口已存在 | 演示辅助能力 | 7 天内不作为主闭环 |
| 交付与审计 | 已完成，报告/导出/日志/SSE 存在 | 演示收口能力 | 需要打包成稳定演示路径 |

### 3.2 真正缺的不是大面积代码

真正缺的是 4 类收口工作：

1. **3.4 P1 编排决策代码**：把“Reviewer 指令驱动”升级为“Orchestrator 决策驱动”。
2. **关键缺失测试**：补齐 `CompensationGraphAssemblerTest`、`ModeRouterTest`、`ConversationAgentTest`、`TaskSseHubTest`、`ReportExportRendererTest`。
3. **两条链路文档闭环**：优先补“质量审查”和“修订与重写”的诊断/方案文档。
4. **演示证据包**：用一条真实任务或可复现任务固化创建、执行、节点、报告、导出、回放、恢复证据。

---

## 4. 推荐取舍

### 4.1 主演示链路

```text
任务创建
  -> 计划预览 / DAG
  -> 采集 / 提取
  -> 分析 / 写作
  -> 质量审查
  -> Orchestrator 决策
  -> 动态补图 / 改写 / 人工介入
  -> 终审
  -> 报告导出 / 审计 / 回放
```

### 4.2 7 天内优先做

| 优先级 | 内容 | 原因 |
| --- | --- | --- |
| P0 | 3.4 总蓝图回链和演示范围冻结 | 先避免范围继续发散 |
| P1 | 3.4 终审失败回流 MVP 代码 | 这是新增价值，且直接增强演示可信度 |
| P1 | 关键缺失测试 | 没测试，稳定演示就是运气 |
| P1 | 质量审查诊断 + 方案 | 代码最成熟，最容易形成可信文档 |
| P1 | 修订与重写诊断 + 方案 | 与 3.4 P1 动态补图直接相关 |
| P2 | 演示证据包和演示脚本 | 保证能讲、能看、能复现 |

### 4.3 7 天内明确不做

1. 不补完 6 条旧链路的全部诊断/方案/实链验证。
2. 不重写 `DagExecutor`。
3. 不把 `ExecutionPlanDefinitionBuilder` 改成自由智能规划器。
4. 不做完整 Citation Agent。
5. 不接管所有对话动作执行。
6. 不扩展搜索采集能力，不重开 Playwright / RSS / news discovery 大专题。
7. 不做大规模前端重设计，只保证演示路径可见、可讲、可点。

---

## 5. 7 天执行节奏

### Day 1：冻结演示范围与 3.4 P0 收口

**核心目标**：把“稳定演示版”范围定死，避免继续被 6 条旧链路拖散。

**任务拆解**：

1. 更新总蓝图 3.4 行，明确 `Agent 协作编排引擎` 是横切引擎，不是第 10 条业务链路。
2. 在蓝图里标明 3.4 P0 已完成、P1 进入实施。
3. 建立演示任务口径：选择 1 个可复现竞品分析样例，固定输入、竞品、分析维度和验收输出。
4. 建立演示证据目录，用于保存 create / execute / nodes / replay / report / export / audit。
5. 跑一次当前基线测试，记录已知失败项，不在当天扩范围修非关键问题。

**预期耗时**：1 天  
**依赖前置条件**：3.4 架构规格已存在；当前主链路代码可编译。  
**当天可验收物**：

- 总蓝图包含 3.4 当前状态。
- 稳定演示版样例任务确定。
- 演示证据目录和检查清单确定。
- 基线测试结果有记录。

---

### Day 2：3.4 P1 契约与策略测试先落地

**核心目标**：先把 Orchestrator 决策协议钉住，防止实现继续散落到 Reviewer、动态补图和恢复策略里。

**任务拆解**：

1. 新增运行期契约类：`AgentSuggestion`、`OrchestrationDecision`、`DecisionPolicyRuleSet`、`DecisionPolicyResult`、`DynamicPlanMutation`、`DecisionTrace`、`OrchestratorCheckpoint`。
2. 视当前 `QualityDiagnosis` 已有实现决定是复用、扩展还是加 adapter，不重复造第二套诊断对象。
3. 所有新契约必须包含 `sourceUrls` 或 `evidenceState`。
4. 写 `DecisionPolicyServiceTest`，覆盖动作白名单、缺来源、人工确认、循环次数上限。
5. 写 `OrchestrationDecisionAdapterTest`，覆盖兼容期 `RevisionDirective -> OrchestrationDecision`。

**预期耗时**：1 天  
**依赖前置条件**：3.4 spec 已冻结；`RevisionDirective` 当前语义已梳理。  
**当天可验收物**：

- 契约类存在且能序列化。
- 策略测试先红后绿。
- 没有新增绕过 `sourceUrls / evidenceState` 的决策对象。

---

### Day 3：OrchestratorAgent / DecisionPolicyService / Trace 第一版

**核心目标**：先做规则优先的 Orchestrator MVP，保证演示稳定，再为后续 LLM Orchestrator 留接口。

**任务拆解**：

1. 新增 `OrchestratorAgent` 或 `OrchestrationDecisionService` 第一版。
2. 输入只吃终审失败后的质量诊断、报告摘要、证据状态、任务上下文。
3. 输出只允许 4 类动作：`APPEND_DYNAMIC_BRANCH`、`RERUN_FROM_NODE`、`WAIT_FOR_HUMAN`、`NO_ACTION`。
4. `DecisionPolicyService` 对 Orchestrator 输出做二次校验。
5. `OrchestrationTraceService` 记录 decision、policy result、sourceUrls、evidenceState。
6. Orchestrator 调用失败时降级为规则策略，不允许任务伪成功。

**预期耗时**：1 天  
**依赖前置条件**：Day 2 契约和策略测试通过。  
**当天可验收物**：

- 终审失败输入能生成 `OrchestrationDecision`。
- 非法动作能被 `DecisionPolicyService` 拦截。
- 决策轨迹能被测试读取或在日志 / replay 中定位。

---

### Day 4：接入动态补图与恢复游标

**核心目标**：让 Orchestrator 决策真正能驱动现有动态补图，而不是只停留在对象转换。

**任务拆解**：

1. 新增或改造 `DecisionExecutorAdapter`，把已校验决策翻译成 `DynamicPlanMutation`。
2. 让 `DynamicPlanAppender / DynamicTaskGraphService / CompensationGraphAssembler` 支持消费 `DynamicPlanMutation`，兼容读取旧 `RevisionDirective`。
3. 写入 `OrchestratorCheckpoint`，记录 `lastDecisionId`、`pendingActions`、`resumeAfterNodeName`、`decisionCount`。
4. 对自动补图加上最大次数限制，演示版建议 `maxAutoDecisions=1` 或 `2`。
5. 确保动态分支节点能在 SSE / replay / 节点列表里被看见。

**预期耗时**：1 天  
**依赖前置条件**：Day 3 决策、策略、trace 已可用。  
**当天可验收物**：

- 单元测试能证明 `OrchestrationDecision -> DynamicPlanMutation -> 动态节点`。
- checkpoint 记录可恢复游标。
- 旧 `RevisionDirective` 不被破坏。

---

### Day 5：Reviewer 职责收口 + 缺失测试补齐

**核心目标**：把演示稳定性的硬边界补上，尤其是 Reviewer 不再继续扩展编排动作语义。

**任务拆解**：

1. Reviewer prompt 去掉新增 `orchestrationAction` 的要求，只要求质量事实、证据缺口、修订建议。
2. `RevisionDirective.normalized()` 不再新增新的编排推导规则，保留兼容读取。
3. 补齐 5 个缺失测试：
   - `CompensationGraphAssemblerTest`
   - `ModeRouterTest`
   - `ConversationAgentTest`
   - `TaskSseHubTest`
   - `ReportExportRendererTest`
4. 跑 P1 相关回归测试。
5. 对测试失败做阻塞分级：演示阻塞、演示可降级、非本周范围。

**预期耗时**：1 天  
**依赖前置条件**：Day 4 接入点稳定。  
**当天可验收物**：

- 5 个缺失测试存在并通过。
- Reviewer 与 Orchestrator 职责边界有测试或文档保护。
- P1 相关回归通过。

---

### Day 6：质量审查 + 修订重写两条链路文档闭环

**核心目标**：补最能反哺演示和 P1 的旧任务，不试图全补 6 条。

**任务拆解**：

1. 写 `docs/superpowers/quality-review/problem/QualityReview.md`。
2. 写 `docs/superpowers/quality-review/plan/2026-06-xx-quality-review-closure-plan.md`。
3. 写 `docs/superpowers/revision-and-rewrite/problem/RevisionAndRewrite.md`。
4. 写 `docs/superpowers/revision-and-rewrite/plan/2026-06-xx-revision-and-rewrite-closure-plan.md`。
5. 文档必须包含真实代码证据、边界判断、blocking 项归类、做什么、不做什么、验收口径。
6. 回链总蓝图状态，不把未实链验证的列提前标成完成。

**预期耗时**：1 天  
**依赖前置条件**：Day 1-5 的 P1 代码和测试结果可作为证据。  
**当天可验收物**：

- 质量审查链路诊断 + 方案存在。
- 修订与重写链路诊断 + 方案存在。
- 总蓝图能指向这 4 份文档。

---

### Day 7：实链演示、证据包和降级预案

**核心目标**：把“能跑”变成“能稳定演示”。

**任务拆解**：

1. 用固定样例创建真实任务。
2. 保存 create request / response、execute response、nodes poll、task final、report、report evidences、replay、export response。
3. 验证 UI 演示路径：任务列表、任务详情、节点进度、诊断面板、报告页、导出区、审计信息。
4. 验证恢复路径：至少一次 rerun 或 resume 能被调用并留下可解释结果。
5. 准备降级预案：如果外部模型或搜索不稳定，使用最近一次真实证据包做 replay 演示，同时说明 live 入口可用。
6. 跑后端关键测试、前端关键测试和一次 smoke。

**预期耗时**：1 天  
**依赖前置条件**：P1 代码通过回归；演示环境配置可用。  
**当天可验收物**：

- 一份可复现演示证据包。
- 一份 10 分钟演示脚本。
- 一份演示前检查清单。
- 一份失败降级说明。

---

## 6. 进度可视化格式

当前阶段：3.4 P2 前置协作规划与抽取后证据缺口决策已完成自动化收口；稳定演示版可展示任务开始前协作计划、受控 DAG 映射、trace/replay 和缺证据人工介入链路

- [x] Day 1：范围冻结与蓝图回链 - 已完成
- [x] Day 2：P1 契约与策略测试 - 已完成
- [x] Day 3：Orchestrator 决策服务与 trace - 已完成
- [x] Day 4：动态补图与 checkpoint - 已完成
- [x] Day 5：Reviewer 收口与回归测试 - 已完成
- [x] Day 6：两条链路诊断/方案 - 已完成（质量审查、修订与重写）
- [x] Day 7：P1 可复现 smoke 证据包 - 已完成（真实外部中间件/live dev 演示脚本仍可后续增强）
- [x] P2 计划：前置协作规划与抽取后证据缺口决策具体执行计划 - 已完成
- [x] P2 Task 1-7：协作规划契约、受控映射、trace/replay、抽取后决策、聚合 smoke 与文档回链 - 已完成（`ArchitectureWhitelistTest` ledger 路径历史阻塞已解除，backend 全量回归通过）

执行过程中统一用下面结构更新进度，建议保存到 `docs/superpowers/stable-demo/progress/2026-06-23-demo-progress.md`。

```markdown
当前阶段：[例如：3.4 P1 动态补图接入]

- [x] Day 1：范围冻结与蓝图回链 - 已完成
- [ ] Day 2：P1 契约与策略测试 - 执行中
- [ ] Day 3：Orchestrator 决策服务 - 待执行
- [ ] Day 4：动态补图与 checkpoint - 待执行
- [ ] Day 5：Reviewer 收口与缺失测试 - 待执行
- [ ] Day 6：两条链路诊断/方案 - 待执行
- [ ] Day 7：实链演示与证据包 - 待执行

结构化进度：

{
  "currentStage": "3.4 P1 动态补图接入",
  "completedSteps": 1,
  "totalSteps": 7,
  "completionRate": "14%",
  "remainingSteps": [
    "P1 契约与策略测试",
    "Orchestrator 决策服务",
    "动态补图与 checkpoint",
    "Reviewer 收口与缺失测试",
    "两条链路诊断/方案",
    "实链演示与证据包"
  ],
  "blockers": [],
  "lastUpdatedAt": "2026-06-23"
}
```

---

## 7. 演示验收清单

### 7.1 必须通过

- [ ] 后端能启动。
- [ ] 前端能打开任务列表、任务详情和报告页。
- [ ] 固定样例任务能创建。
- [ ] 固定样例任务能执行到报告生成或可解释的质量阻塞。
- [ ] `sourceUrls` 或 `evidenceState` 在报告、诊断、编排决策中可见。
- [ ] `quality_check_final` 后能产生可回放的 Orchestrator 决策。
- [ ] `DecisionPolicyService` 能阻止非法动作。
- [ ] `DecisionExecutorAdapter` 能生成 `DynamicPlanMutation`。
- [ ] 动态分支、rerun 或 resume 至少有一种路径可演示。
- [ ] 报告能导出，审计信息能解释。

### 7.2 建议通过

- [ ] SSE 能显示关键进度。
- [ ] replay 能显示任务时间线。
- [ ] 质量诊断面板能展示失败原因和修订建议。
- [ ] 任务中断后恢复时能读取 checkpoint。
- [x] P1 smoke 证据包能在无外部 API 情况下支撑讲解：`OrchestrationRuntimeFeedbackSmokeTest` 覆盖终审失败、Orchestrator 决策、策略 allowed、动态补图、checkpoint、replay 与缺来源 `MISSING_SOURCE`。

### 7.3 测试命令建议

后端关键回归：

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,OrchestrationDecisionAdapterTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,RecoveryEngineTest,TaskRuntimeCommandAppServiceTest,ReportControllerTest,TaskEventStreamControllerTest" test
```

后端全量回归：

```powershell
mvn -pl backend test
```

前端关键回归：

```powershell
cd frontend
npm test -- --run
```

演示 smoke：

```text
1. POST /api/tasks 创建固定样例任务
2. POST /api/tasks/{taskId}/execute 执行任务
3. GET /api/tasks/{taskId}/nodes 轮询节点
4. GET /api/reports/{taskId} 查看报告
5. GET /api/tasks/{taskId}/replay 查看回放
6. POST /api/reports/{taskId}/export 导出报告
7. 触发 rerun / resume 验证恢复入口
```

---

## 8. 风险与降级

| 风险 | 表现 | 降级方案 |
| --- | --- | --- |
| 外部 LLM 不稳定 | 任务停在分析、写作或审查 | Orchestrator P1 规则优先，演示时使用最近真实证据包 replay |
| 搜索/抓取不稳定 | 采集不足或耗时过长 | 固定 providedUrls，避免演示依赖开放搜索波动 |
| 质量门禁过严 | 任务最终 FAILED | 把 FAILED 作为演示点，展示诊断、补救、人工介入，而不是伪装成功 |
| 动态补图循环 | 自动追加过多分支 | 演示版设置 `maxAutoDecisions=1` 或 `2` |
| 文档补旧任务拖慢 | 6 条链路补不完 | 本周只补质量审查、修订与重写两条 |
| 工作区历史改动复杂 | 回归失败原因混杂 | 对失败做阻塞分级，只修演示阻塞项 |

---

## 9. 本周完成后的状态

7 天结束时，状态应当是：

| 模块 | 目标状态 |
| --- | --- |
| 3.4 P0 | 完成，蓝图回链 |
| 3.4 P1 | MVP 代码落地，有测试，有 trace/checkpoint |
| 3.4 P2 | 前置 `CollaborationPlan`、受控 DAG 映射、协作 trace/replay 和抽取后证据缺口拦截已完成自动化收口 |
| 质量审查链路 | 诊断 + 方案完成，代码和测试支撑演示 |
| 修订与重写链路 | 诊断 + 方案完成，动态补图接入 Orchestrator 决策 |
| 缺失测试 | 5 个关键测试补齐 |
| 演示证据包 | P1+P2 可复现 Spring Boot/H2 smoke 证据完整；真实外部中间件/live dev 演示脚本可后续增强 |
| 其他 4 条旧链路 | 保持现状，下周每 1-2 天补 1 条 |

---

## 10. 最小演示话术

演示时按下面顺序讲：

1. 这不是单个 Agent，而是竞品分析协作系统。
2. 任务开始前，系统先生成可审计 `CollaborationPlan`，再受控映射到正式 DAG，每个 Agent 只负责自己的专业产物。
3. 所有关键结论必须带来源，没来源就显式标记证据缺口。
4. Reviewer 只判断质量事实，不再直接决定怎么补图。
5. Orchestrator 根据质量诊断生成编排决策。
6. 决策先过策略校验，再被翻译成动态计划变更。
7. 抽取后如果出现业务字段或证据缺口，AgentSuggestion 会进入 Orchestrator 决策输入，不能绕过策略直接执行。
8. 所有动作进入 trace、checkpoint、replay 和导出审计。
9. 所以系统不是“生成一份报告就结束”，而是能计划、能自检、能解释、能补救、能回放。

---

## 11. 下周顺延计划

本周稳定演示版完成后，下周按下面顺序补旧任务：

1. 分析推理链路：诊断 + 方案。
2. 报告写作链路：诊断 + 方案。
3. 对话协同链路：诊断 + 方案。
4. 交付与审计链路：诊断 + 方案。
5. 逐条安排实链验证，不把自动化测试等同于实链验证。
