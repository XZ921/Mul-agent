# 阶段二 LLM Orchestrator 主计划

> **For agentic workers:** 本文是阶段二总计划，不是单个可直接执行的代码任务。后续具体执行任务应另行写入 `docs/stage2/task/`，每个 task 文档只覆盖一个可独立开发、测试、验收的任务包。

**Goal:** 按 `docs/stage2/2026-07-13-stage2-llm-orchestrator-design.md` 落地阶段二：让运行期 Orchestrator 的决策由 LLM 主导，同时保留规则回退、确定性策略护栏、全链路 trace/report/replay 可解释能力。

**Architecture:** 阶段二采用“LLM 主决策 + 规则回退 + origin-aware policy + trace 可解释”的分层架构。先固化决策契约和合法组合矩阵，再抽出规则大脑，随后接入 prompt/parser/LLM brain，最后贯通 coordinator、policy、executor、trace/report/replay 和验收样例集。计划刻意不把根因能力后移到最后，避免只做局部症状修补。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Jackson, existing `ModelGateway`, existing workflow outbox, existing report/replay projection.

---

## 1. 计划类型判断

本阶段工程量偏大，不能写成一个单文件或单轮可执行计划。

原因：

- 变更横跨 `orchestration`、`workflow.runtime`、`llm`、`report`、`conversation/replay` 和测试夹具。
- 核心风险不是某个 bug，而是 LLM 决策进入热路径后造成的全链路接缝问题。
- 合法组合矩阵、prompt 注入防护、timeout、shadow 预算、replay 不重调模型都必须在多个组件重复落点，不能只在 parser 或 service 层局部处理。
- 后续执行需要多个独立 task 文档，每个 task 都要能单独交给 worker 开发和验收。

因此本文件只做阶段二主计划：定义顺序、边界、产物、验收门槛和后续 task 文档目录。具体代码步骤后续写入 `docs/stage2/task/`。

---

## 2. 主线原则

阶段二只有一条主线：把 Orchestrator 的“决策大脑”LLM 化，同时保证执行仍由确定性护栏裁决。

执行过程中必须遵守：

- 不继续追 Notion/Airtable、Linear/Jira 或开放平台样例质量调参。
- 不修采集丰富度、站点反爬、报告质量评分口径或模板错配。
- 不把 `sourceUrls` 红线降级。
- 不打开多轮自动补采循环。
- 不让 replay/report/export 重新调用 LLM。
- 不把 `RERUN_NODE` 等 legacy 动作开放给 LLM 输出。
- 不用局部 fallback 掩盖根因契约缺失。

---

## 3. 目标产物

阶段二完成时应有这些产物：

| 产物 | 说明 | 验收方式 |
| --- | --- | --- |
| 决策来源契约 | `LLM_PRIMARY`、`LLM_SHADOW`、`RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` 的来源语义明确 | 单测覆盖 origin-aware policy |
| LLM 输出契约 | JSON schema、合法组合矩阵、`sourceUrls` 策略、非法组合处理固定 | parser 与 policy 双层测试 |
| 规则大脑 | 当前 if-else 逻辑抽成 `RuleBasedOrchestratorDecisionBrain` | 行为与原 `OrchestrationDecisionServiceTest` 一致 |
| LLM 大脑 | prompt builder、response parser、LLM brain、timeout、temperature、fallback | mock LLM 单测覆盖成功和失败 |
| Coordinator | `OrchestrationDecisionService` 只负责模式选择、shadow、fallback、元信息归档 | mode 测试覆盖三种模式 |
| Policy/Executor 贯通 | LLM 矩阵与 legacy ruleSet 不互相误杀 | `RERUN_NODE` fallback 不被误拦 |
| Trace/Report/Replay | origin、reason、fallbackReason、timeout、temperature、hash 可投影；replay 不重调模型 | report/replay 单测 |
| 验收样例集 | 5-10 条人工标注决策样例覆盖关键行为 | fixtures 测试通过 |

---

## 4. 后续 Task 文档拆分

后续在 `docs/stage2/task/` 下创建具体执行文档。建议按下面顺序拆分，不要合并成一个超大 task。

| Task 文档建议名 | 核心能力 | 不能后移的根因 |
| --- | --- | --- |
| `task-01-decision-origin-and-contract.md` | 增加 decision origin、metadata 写入方式、origin-aware 校验入口，并预埋 report/replay 投影字段形状 | 所有后续链路都依赖 origin 区分 LLM 与 legacy，Phase 6 不能反向要求 Phase 1 返工 |
| `task-02-action-matrix-and-policy.md` | 实现 LLM 合法组合矩阵、parser/policy 双层校验、legacy `RERUN_NODE` 放行 | 避免 LLM 脏组合污染执行链路 |
| `task-03-rule-brain-extraction.md` | 抽出 `RuleBasedOrchestratorDecisionBrain`，保持原行为 | fallback 必须可靠，LLM 才能安全接入 |
| `task-04-prompt-parser-fixtures.md` | prompt builder、注入隔离、response parser、人工标注样例集基础版 | 防止 prompt 注入和 JSON 脏数据进入 service |
| `task-05-llm-brain-timeout-fallback.md` | LLM brain、`temperature=0`、短 timeout、parse retry、规则 fallback | 防止热路径延迟和模型失败拖垮 DAG |
| `task-06-service-modes-and-shadow-budget.md` | `RULE_ONLY`、`LLM_SHADOW`、`LLM_PRIMARY`，shadow 独立预算语义 | 防止 shadow 饿死主路径 |
| `task-07-policy-executor-runtime-integration.md` | DynamicPlanAppender/DagExecutor 的 policy、decision、mutation 消费对齐 | 防止 LLM 决策和动态计划挂载之间接缝断裂 |
| `task-08-trace-report-replay.md` | trace/report/export/replay 展示 origin、reason、fallback，且 replay 不重调 LLM | 防止演示和审计只看到半截事实 |
| `task-09-stage2-acceptance-and-regression.md` | 汇总 fixtures、before/after、回归命令和一次性验收记录 | 防止只接线成功但决策质量退化 |

每个 task 文档都必须包含：

- 本 task 的目标和非目标。
- 涉及文件清单。
- 先写哪些失败测试。
- 最小实现范围。
- 本 task 的验收命令。
- 与前后 task 的接口约束。
- 明确不处理哪些后续增强，防止范围漂移。

---

## 5. 阶段顺序

### Phase 0：冻结阶段二入口

目标：确认阶段二从当前降级闭环进入，不再把友好样例质量分作为入口阻塞。

产物：

- 保留设计文档作为唯一阶段二设计来源。
- 保留 `tmp/phase2-readiness-backend-tests-20260713.log` 作为当前测试证据。
- 不新增 E2E 调参任务。

通过条件：

- 阶段二 task 文档只围绕 LLM Orchestrator，不扩散到采集、评分、模板质量。

### Phase 1：先打牢决策契约

目标：在任何 LLM 调用之前，先明确决策来源、合法组合矩阵、legacy 路径边界。

覆盖 task：

- `task-01-decision-origin-and-contract.md`
- `task-02-action-matrix-and-policy.md`

通过条件：

- `LLM_PRIMARY/LLM_SHADOW` 的非法组合会被拦截。
- `RULE_FALLBACK/LEGACY_ADAPTER` 的 `RERUN_NODE` 仍走 legacy ruleSet，不被 LLM 矩阵误杀。
- `DecisionPolicyService` 不再只分别校验 `decisionType` 和 `normalizedAction`。
- origin metadata 的最小投影形状已经固定：至少包含 `decisionOrigin`、`fallbackReason`、`modelName`、`temperature`、`llmResponseHash`、`shadowSkippedReason`，后续 report/replay 只消费这个形状，不反向改变写模型。

### Phase 2：抽出可靠 fallback

目标：把当前 if-else 决策逻辑完整迁出，成为可复用规则大脑。

覆盖 task：

- `task-03-rule-brain-extraction.md`

通过条件：

- 旧 `OrchestrationDecisionServiceTest` 行为不变。
- fallback 产出的 decision 带 `decisionOrigin=RULE_FALLBACK` 或由 coordinator 补齐。
- 没有在抽取过程中顺手改业务策略。

### Phase 3：接入 LLM 输入输出能力

目标：构建 LLM brain 的输入输出边界，但先不让它驱动主路径。

覆盖 task：

- `task-04-prompt-parser-fixtures.md`
- `task-05-llm-brain-timeout-fallback.md`

通过条件：

- prompt 中外部内容均处在不可信数据块中。
- 恶意 suggestion 文本不会逃逸成指令。
- parser 能处理合法 JSON、非法 JSON、非法组合、上下文外 URL、空 `sourceUrls`。
- LLM 超时、异常、解析失败都会进入规则 fallback。
- `temperature=0.0` 或 provider 最低温配置可审计。

### Phase 4：切换 Service 为 Coordinator

目标：让 `OrchestrationDecisionService` 从规则承载者变成模式协调器。

覆盖 task：

- `task-06-service-modes-and-shadow-budget.md`

通过条件：

- `RULE_ONLY` 是默认模式。
- `LLM_SHADOW` 只记录对比，不驱动 DAG。
- `LLM_PRIMARY` 执行 LLM 决策，但失败或 policy 阻断时回退。
- shadow 预算耗尽不影响主路径。

### Phase 5：贯通运行时执行链

目标：让 policy、executor、dynamic plan、agent suggestion trace 在 origin-aware 契约下稳定协作。

覆盖 task：

- `task-07-policy-executor-runtime-integration.md`

通过条件：

- `DynamicPlanAppender` 只消费 policy allowed 的主路径 decision。
- agent suggestion gate 记录的 decision 也带 origin/reason/fallback metadata。
- citation 补证样例同时覆盖 `currentDecisionCount < maxAutoDecisions` 和达到上限阻断。
- LLM 超时或非法组合不能把节点误标失败。

### Phase 6：贯通审计展示链

目标：让用户、报告、导出、replay 看到完整决策事实，但不触发二次模型调用。

覆盖 task：

- `task-08-trace-report-replay.md`

通过条件：

- report/export/replay 能展示 origin、reason、policy allowed、fallbackReason、sourceUrls、evidenceState。
- replay/report/export 单测能证明不会调用 LLM。
- 旧报告字段保持兼容。

### Phase 7：阶段二验收

目标：证明 LLM Orchestrator 接线、决策、fallback、policy、trace 全链路成立。

覆盖 task：

- `task-09-stage2-acceptance-and-regression.md`

通过条件：

- 人工标注决策样例集通过。
- `LLM_PRIMARY` 下至少一次决策来自 LLM。
- 非法组合、超时、prompt 注入、上下文外 URL、shadow 预算耗尽均有测试。
- 一次性 E2E 或局部集成验证不比阶段一更差，不进入反复调参循环。

---

## 6. 文件边界

后续 task 应优先在这些文件附近落点，避免横向扩散。

### Orchestration 核心

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjector.java`

### 建议新增 Orchestration 文件

- `OrchestratorDecisionBrain.java`
- `RuleBasedOrchestratorDecisionBrain.java`
- `LlmOrchestratorDecisionBrain.java`
- `OrchestrationDecisionPromptBuilder.java`
- `OrchestrationDecisionResponseParser.java`
- `OrchestrationDecisionActionMatrix.java`
- `OrchestrationDecisionOrigin.java`
- `OrchestratorDecisionProperties.java`
- `OrchestratorDecisionMetadata.java` 或等价轻量结构

### 运行时和展示链路

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java`
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java`

### LLM 接入

- `backend/src/main/java/cn/bugstack/competitoragent/llm/LlmClient.java`
- `backend/src/main/java/cn/bugstack/competitoragent/llm/ModelGateway.java`
- `backend/src/main/java/cn/bugstack/competitoragent/config/AiProviderProperties.java`
- `backend/src/main/resources/application.yml`

后续 task 文件名应优先保持第 4 节编号。若确需调整，必须同步更新 Phase 覆盖表和第 11 节执行节奏，保证数字序等于依赖序。不能把职责重新塞回 `OrchestrationDecisionService` 形成大泥球。

---

## 7. 接缝控制

阶段二最容易失败的不是单测，而是上下游语义错位。后续每个 task 都必须检查这些接缝：

| 接缝 | 必须对齐的语义 |
| --- | --- |
| Prompt -> Parser | LLM 只能输出白名单字段和合法组合；外部内容只是数据 |
| Parser -> Decision | `sourceUrls` 去重、上下文外 URL 过滤、非法组合 fallback metadata |
| Decision -> Policy | LLM origin 走 LLM 矩阵；fallback/legacy 走 legacy ruleSet |
| Policy -> Executor | 只有 `allowed=true` 才能产生 mutation；`MANUAL_ONLY` 不创建采集节点 |
| Coordinator -> Trace | 主路径、shadow、fallback 都必须写入 origin metadata；shadow 不进入 executor 但必须可审计 |
| Executor -> DAG | 超时、shadow、非法组合不改变节点成功/失败语义 |
| Trace -> Report/Replay | 持久化事实可投影；replay 不重调 LLM |
| Fixtures -> E2E | 样例集先证明决策合理，再考虑真实 E2E |

任何 task 如果发现接缝需要新增字段，必须同步更新投影和测试，不允许只改写模型。

---

## 8. 测试总策略

测试顺序必须从契约到集成，不能反过来靠 E2E 暴露问题。

### 必须新增或改造的测试组

- `OrchestrationDecisionActionMatrixTest`
- `RuleBasedOrchestratorDecisionBrainTest`
- `OrchestrationDecisionPromptBuilderTest`
- `OrchestrationDecisionPromptInjectionTest`
- `OrchestrationDecisionResponseParserTest`
- `LlmOrchestratorDecisionBrainTest`
- `OrchestrationDecisionServiceLlmModeTest`
- `OrchestrationTraceServiceTest`
- `OrchestrationDecisionSummaryProjectorTest`
- `ReplayOrchestrationDecisionTest`
- `DecisionPolicyServiceTest`
- `DecisionExecutorAdapterTest`
- `DynamicPlanAppenderTest`
- `ReportServiceTest`
- `ReportExportRendererOrchestrationDecisionTest`

### 分阶段回归命令

契约与 policy 阶段：

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,DecisionExecutorAdapterTest" test
```

LLM brain 与 coordinator 阶段：

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionResponseParserTest,LlmOrchestratorDecisionBrainTest,OrchestrationDecisionServiceLlmModeTest" test
```

trace/report/replay 阶段：

```powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,ConversationOrchestrationDecisionQueryServiceTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest" test
```

最终阶段二回归：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DagExecutorTest,DynamicPlanAppenderTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,ConversationOrchestrationDecisionQueryServiceTest" test
```

前端状态回归保持：

```powershell
npm.cmd --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts NodeAccordionList.test.tsx
```

---

## 9. 验收门槛

阶段二不以质量分为验收门槛。验收只看 Orchestrator LLM 化是否可控、可回退、可解释。

必须满足：

- `RULE_ONLY` 默认可用，行为等价原规则路径。
- `LLM_SHADOW` 可记录 LLM 建议，但不驱动 DAG。
- `LLM_PRIMARY` 至少一次主路径决策来自 LLM。
- LLM 非法 JSON、非法组合、超时、上下文外 URL、prompt 注入样本均进入可审计 fallback。
- `sourceUrls` 在 LLM 输出、decision、policy、trace、report/replay 中持续存在。
- `RERUN_NODE` legacy 决策不会被 LLM 矩阵误杀。
- citation 补证样例同时验证 `currentDecisionCount < maxAutoDecisions` 和达到上限阻断。
- replay/report/export 不调用 LLM。
- 一次性 E2E 或局部集成结果不比阶段一降级闭环更差。

---

## 10. 不做事项

这些事项不进入阶段二 task：

- 不继续调 Notion/Airtable 或 Linear/Jira 的质量分。
- 不改 Tavily 搜索准入和站点反爬策略。
- 不改 Reviewer 评分阈值来制造通过。
- 不做动态开局规划。
- 不做 RAG。
- 不做对话协同增强。
- 不做 UI 大改。
- 不把 `DecisionPolicyService` 替换成规则引擎。

---

## 11. 后续执行方式

建议下一步先创建 `docs/stage2/task/`，然后按本计划第 4 节顺序写 task 文档。第 4 节编号已经按依赖顺序排列，执行时以数字序为准。

推荐执行节奏：

1. 先写并执行 `task-01` 到 `task-03`，让契约和规则 fallback 稳住。
2. 再写并执行 `task-04` 到 `task-06`，让 LLM brain 接入但不破坏主路径。
3. 再写并执行 `task-07` 到 `task-08`，打通运行时和审计展示链路。
4. 最后写并执行 `task-09`，完成阶段二验收记录。

每完成一个 task，都要把实测命令和结果写回对应 task 文档。不要等全部完成后再补记录。
