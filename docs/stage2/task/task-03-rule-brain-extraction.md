# Task 03 规则大脑抽取与可靠 Fallback 基线 Implementation Plan

> **For agentic workers:** 本文是可直接执行的 Task 03 计划。执行者必须先锁定现有规则行为，再进行类抽取；不得在“重构”名义下改变任何业务判断、动作优先级、来源语义或下游执行行为。

**Goal:** 将当前 `OrchestrationDecisionService` 内约 300 行规则判断、decision 构造和审计引用逻辑完整迁移为可独立注入、独立测试的 `RuleBasedOrchestratorDecisionBrain`，让现有规则路径成为后续 LLM 超时、异常、解析失败和非法输出时可复用的可靠基础；抽取前后所有现有规则结果保持等价。

**Architecture:** 新增最小 `OrchestratorDecisionBrain` 接口和唯一 `RuleBasedOrchestratorDecisionBrain` 实现。`OrchestrationDecisionService` 在本任务结束时只保留 raw null 防御、`OrchestrationContext.normalized()` 和对规则大脑的委托；节点分流、Suggestion 优先级、legacy directive 适配、decision builder、`inputRefs` 与证据状态判断全部只存在于规则大脑。Task 03 不接 LLM、不选择运行模式、不调用 Policy/Executor/Trace，也不产生 `RULE_FALLBACK`；后续 coordinator 在明确发生 LLM fallback 时统一覆盖 origin 与 metadata。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, existing `OrchestrationDecision`/`OrchestrationContext`/`OrchestrationDecisionAdapter`, Task 01 origin contract, Task 02 origin-aware policy.

---

## 1. 任务定位与编号依据

设计文档第 10 节的早期六步草案把“规则大脑抽取”称为 Task 1；阶段二主计划随后按依赖重新拆成九个任务，并将它固定为：

```text
Task 01：决策来源与元数据契约
  -> Task 02：LLM 动作矩阵与 Policy 双层护栏
  -> Task 03：规则大脑抽取
  -> Task 04：Prompt、Parser 与 Fixtures
  -> Task 05：LLM Brain、Timeout 与失败结果
  -> Task 06：Service Modes、Coordinator 与 Fallback Origin
```

因此本文以主计划的数字顺序和 Task 01/02 已落地契约为执行依据，不回退到设计草案旧编号。

Task 03 解决的根因是：当前规则判断被绑定在 `OrchestrationDecisionService` 中，后续若直接增加 LLM 分支，Service 会同时承担规则决策、LLM 调用、模式选择、fallback、metadata 和协调职责，迅速重新变成大类。必须先把规则能力作为独立可调用组件完整抽出，后续 LLM 才有真实而不是名义上的 fallback。

禁止采用以下症状式方案：

- 只新增 `RuleBasedOrchestratorDecisionBrain` 外壳，内部仍回调 `OrchestrationDecisionService.decide(...)`。
- 复制 Service 的 if-else 到 Brain，同时保留 Service 原逻辑形成两份规则。
- 只迁移一两个 Suggestion 分支，把终审、legacy directive 或审计字段留到后续任务。
- 为了让新测试通过顺手修改 action、target、scope、reason、priority、confidence 或优先级。
- 在 Task 03 内加入 LLM、配置模式、timeout、fallbackReason 或 shadow 逻辑。

---

## 2. 与 Task 01/02 的衔接

### 2.1 Task 01 已固定的来源契约

Task 03 必须消费现有 enum，禁止新建来源字符串：

| 规则大脑输出场景 | Task 03 保持的 origin | 原因 |
| --- | --- | --- |
| 原生规则判断产生的 NO_ACTION / WAIT / SUPPLEMENT / REWRITE | `RULE_ONLY` | 当前规则主路径事实 |
| `OrchestrationDecisionAdapter` 从历史 `RevisionDirective` 转换 | `LEGACY_ADAPTER` | 输入本身来自历史兼容协议 |
| LLM 失败后由 coordinator 调用规则大脑 | 本任务不实现；后续统一覆盖为 `RULE_FALLBACK` | Brain 不知道自己为何被调用，不能猜测 fallback |

Task 03 不修改：

```text
OrchestrationDecisionOrigin
OrchestratorDecisionMetadata
OrchestrationDecision.normalized()
OrchestrationDecisionSummaryProjector
Trace / Report / Replay 投影
```

### 2.2 Task 02 已固定的 Policy 分流

Task 02 已实现：

```text
LLM_PRIMARY / LLM_SHADOW
  -> LLM ActionMatrix

RULE_ONLY / RULE_FALLBACK / LEGACY_ADAPTER
  -> legacy normalizedAction mapping
  -> DecisionPolicyRuleSet
```

因此 Task 03 的规则大脑：

- 不调用 `OrchestrationDecisionActionMatrix`。
- 不把 native rule decision 改成 `LLM_PRIMARY` 或 `LLM_SHADOW`。
- 不为复用 LLM 矩阵而删除 legacy `RERUN_NODE`、`DOMAIN_HINT_DISCOVERY` 或 Adapter 行为。
- 不调用 `DecisionPolicyService`；规则大脑只提出决策，Policy 仍负责最终裁决。
- 必须继续携带 `sourceUrls` 和 `evidenceState`，不能因类抽取丢失无幻觉链路字段。

### 2.3 Task 02 交付给 Task 03 的明确约束

Task 02 已明确：

```text
规则大脑的普通输出 = RULE_ONLY
Coordinator 确认发生 fallback 后 = RULE_FALLBACK
两者都走 legacy ruleSet
```

Task 03 不能给 `decide(...)` 增加 `isFallback`、origin 或 mode 参数。调用原因属于 coordinator，不属于规则判断本身；把它传入 Brain 会使规则逻辑与运行模式耦合。

---

## 3. 当前根因与代码证据

### 3.1 Service 同时承担六类职责

当前 `OrchestrationDecisionService.java` 共约 365 行，除入口协调外还直接承担：

1. raw context null 防御与归一化。
2. 按 trigger node 分流。
3. 按 Suggestion 类型、来源状态和严重度决定动作。
4. 将 legacy revision directives 交给 Adapter。
5. 构造全部规则 decision 字段。
6. 生成 `qualityDiagnosisIds`、`agentSuggestionIds` 和 trigger 引用。

如果 LLM 能力继续加在该类中，规则判断和 coordinator 会共享私有方法与状态，后续无法独立验证 fallback 是否仍等价。

### 3.2 当前规则逻辑已经是可迁移整体

当前 Service 只有一个协作者：

```java
private final OrchestrationDecisionAdapter decisionAdapter;
```

所有业务规则都只依赖 `OrchestrationContext`、`AgentSuggestion`、`QualityDiagnosis` 和该 Adapter，没有 repository、LLM、Policy、workflow event 或 Executor 依赖。这意味着可以整体迁移为纯规则协作者，不需要先改 DTO 或运行时。

### 3.3 现有测试只覆盖主要路径，抽取前仍需补特征测试

`OrchestrationDecisionServiceTest` 当前有 14 个测试，覆盖：

- final review 的 legacy supplement、人工介入、通过、阻断诊断、无动作。
- Extractor/Analyzer 的有来源补证与无来源人工阻断。
- Writer 的无来源阻断与有来源 section rewrite。
- Citation 的无来源阻断、claim rewrite 与 evidence repair。

尚未直接锁定但迁移时容易发生变化的行为包括：

- null context 返回空列表。
- 未知 trigger 返回 `RULE_ONLY + NO_ACTION`。
- `rewrite_report`、`citation_check_revision` 别名分流。
- 多条 legacy directive 的顺序、index 和 `LEGACY_ADAPTER` origin。
- 多条 Suggestion 的优先级：阻断项必须先于可执行项。
- decisionId、reason、priority、confidence、scope、inputRefs 和 sourceUrls 的完整形状。

这些只能增加“现状特征测试”，不能借机调整规则。

### 3.4 构造器改动有明确影响范围

实施前全仓共有 9 处直接调用：

```text
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java（1 处）
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java（1 处）
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java（2 处）
backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java（1 处）
backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java（4 处）
```

Spring 生产主路径由容器注入；`DagExecutor.java` 中的直接构造属于兼容构造器的默认依赖装配。Task 03 只允许修改该处构造参数和 import，禁止改 `DagExecutor.execute(...)`、节点状态或调度语义。

### 3.5 当前基线证据

Task 02 已记录：

```text
Task 02 受控回归：127/127 通过
backend clean package：成功
```

为确认 Task 03 受影响的两个集成 fixture 可作为强制门，本计划编写时额外执行：

```powershell
mvn -pl backend "-Dtest=CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test
```

结果：`Tests run: 10, Failures: 0, Errors: 0, BUILD SUCCESS`。

---

## 4. 必须冻结的现有业务行为

### 4.1 trigger 分流矩阵

| Trigger | 当前判断顺序 | 当前结果 | Origin |
| --- | --- | --- | --- |
| `null` context | Service 入口直接返回 | 空列表 | 无 |
| `extract_schema` | 任一 `severity=ERROR` -> 任一无来源 `EVIDENCE_GAP` -> 首个 `EVIDENCE_GAP` -> default | WAIT -> WAIT -> SUPPLEMENT -> NO_ACTION | `RULE_ONLY` |
| `analyze_competitors` | 任一无来源 `ANALYSIS_GAP` -> 首个 `ANALYSIS_GAP` -> default | WAIT -> SUPPLEMENT -> NO_ACTION | `RULE_ONLY` |
| `write_report` / `rewrite_report` | 任一无来源 `CITATION_GAP` -> 首个 `CITATION_GAP` -> default | WAIT -> REWRITE_SECTION -> NO_ACTION | `RULE_ONLY` |
| `citation_check` / `citation_check_revision` | 任一缺 URL 或 `MISSING_SOURCE` 的 verification gap -> 首个 verification gap | WAIT -> target 为 collect 时 SUPPLEMENT，否则 REWRITE_CLAIM | `RULE_ONLY` |
| `quality_check_final` passed | 先于其他 final 条件 | NO_ACTION | `RULE_ONLY` |
| `quality_check_final` requires human | passed=false 后判断 | WAIT_FOR_HUMAN | `RULE_ONLY` |
| `quality_check_final` 有 legacy directives | 按输入顺序全部转换 | Adapter 决定动作 | `LEGACY_ADAPTER` |
| `quality_check_final` 有阻断 diagnosis、无 directive | severity ERROR 或 level BLOCKER | WAIT_FOR_HUMAN | `RULE_ONLY` |
| `quality_check_final` 其他失败 | default | NO_ACTION | `RULE_ONLY` |
| 其他 trigger | default | NO_ACTION | `RULE_ONLY` |

顺序本身就是业务语义。举例：同一批 Suggestion 中只要存在无来源阻断项，即使更早位置存在可执行有来源项，也必须先阻断。抽取时禁止把多轮 for-loop 合并成单个“取第一条匹配”流式表达式。

### 4.2 decision 字段形状

抽取前后至少冻结：

| Decision 类型 | `targetNode` | `affectedScope` | 关键字段 |
| --- | --- | --- | --- |
| Supplement from Suggestion | suggestion 的 `suggestedTargetNode` | `CURRENT_NODE_AND_DOWNSTREAM` | severity/confidence/query/source/evidence 原样携带 |
| Writer rewrite | `rewrite_report` | `CURRENT_NODE_ONLY` | `REWRITE_SECTION`、targetSection、sourceUrls |
| Citation rewrite | `rewrite_report` | `CURRENT_NODE_ONLY` | `REWRITE_CLAIM`、targetSection、sourceUrls |
| No action | trigger node | `CURRENT_NODE_ONLY` | priority LOW、confidence 0.95 |
| Wait for human | trigger node | `CURRENT_NODE_ONLY` | priority HIGH、human=true、confirmation=true、confidence 0.20 |
| Blocking final diagnosis | trigger node | `CURRENT_NODE_ONLY` | confidence 0.35、完整 diagnosis refs |
| Legacy directive | Adapter 现有映射 | Adapter 现有映射 | origin 必须保持 `LEGACY_ADAPTER` |

### 4.3 审计字段与顺序

以下细节不能在抽取中“清理”：

- decisionId 的现有格式和 suffix。
- 多条 legacy directive 使用从 1 开始的稳定 index。
- `qualityDiagnosisIds` 继续按上下文 diagnosis 顺序生成。
- `agentSuggestionIds` 继续过滤空 ID 并保持输入顺序。
- `triggerNodeName` 继续写入 `inputRefs`。
- `sourceUrls`、`evidenceState`、reason 不得丢失。
- 所有返回 decision 继续调用 `normalized()`。

---

## 5. 目标架构与职责边界

### 5.1 Brain 接口

新增：

```java
public interface OrchestratorDecisionBrain {
    List<OrchestrationDecision> decide(OrchestrationContext normalizedContext);
}
```

接口契约：

- 输入由调用方完成 `OrchestrationContext.normalized()`。
- 输出必须是非 null list；没有决策时返回 `List.of()`。
- 输出 decision 必须已经 normalized。
- Brain 只生成候选决策，不调用 Policy、Executor、Trace 或 repository。
- 接口不携带 mode、fallback flag、provider、timeout 或 shadow 状态。
- 业务逻辑和复杂条件必须有详细中文注释。

Task 03 现在建立接口不是空泛抽象：主计划已经明确至少存在 RuleBased 与 LLM 两个实现，且 coordinator 必须在二者之间选择。

### 5.2 RuleBased 实现

```java
@Component
@RequiredArgsConstructor
public class RuleBasedOrchestratorDecisionBrain implements OrchestratorDecisionBrain {
    private final OrchestrationDecisionAdapter decisionAdapter;

    @Override
    public List<OrchestrationDecision> decide(OrchestrationContext normalizedContext) {
        // 完整承接当前 Service 的节点分流和规则判断。
    }
}
```

无论使用 Lombok `@RequiredArgsConstructor` 还是显式构造器，RuleBased Brain 对外必须具备以下实际构造形状：

```java
public RuleBasedOrchestratorDecisionBrain(OrchestrationDecisionAdapter decisionAdapter)
```

这样 Spring 可以注入现有 Adapter，`DagExecutor` 兼容构造器和测试 fixture 也能显式完成同一依赖链；禁止让 Brain 在内部 `new OrchestrationDecisionAdapter()`。

约束：

- 只有 `OrchestrationDecisionAdapter` 一个依赖。
- 不依赖 `OrchestrationDecisionService`，防止循环委托。
- 不依赖 `OrchestrationDecisionActionMatrix` 或 `DecisionPolicyRuleSet`。
- 不按每个 trigger 再创建五个策略类；本任务是抽取，不是重建规则框架。
- 不使用 `Map<String, Function<...>>` 改写现有优先级；保留清晰条件顺序。

### 5.3 薄 Service

Task 03 完成后，Service 只允许保留：

```java
@Service
@RequiredArgsConstructor
public class OrchestrationDecisionService {
    private final RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain;

    public List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
        if (rawContext == null) {
            return List.of();
        }
        return ruleBasedDecisionBrain.decide(rawContext.normalized());
    }
}
```

Task 03 暂时注入具体 RuleBased 类型，而不是让 Spring 按接口猜唯一实现。Task 05 增加 LLM 实现后会出现多个 Brain bean；Task 06 coordinator 必须显式持有两个命名协作者，不能依赖 `@Primary` 静默决定主路径。

`DagExecutor.java:286` 的兼容构造器必须使用完整嵌套装配，而不是继续把 Adapter 直接传给 Service：

```java
new OrchestrationDecisionService(
        new RuleBasedOrchestratorDecisionBrain(
                new OrchestrationDecisionAdapter()))
```

该形状要求 Brain 构造器明确接收 Adapter；实现时应在修改其他 8 处测试 fixture 前先编译验证这一条生产兼容构造链。

上下文归一化只有一个 owner：`OrchestrationDecisionService`。RuleBased Brain 不再次调用 `normalized()`，避免 Service 与 Brain 对默认值产生双重解释。

### 5.4 Fallback origin 的单一 owner

RuleBased Brain 本身无法知道调用原因：

```text
RULE_ONLY 模式调用 -> RULE_ONLY
LLM 失败后调用     -> RULE_FALLBACK
```

因此 Task 03 只保持当前 `RULE_ONLY` / `LEGACY_ADAPTER` 输出。后续职责固定为：

```text
Task 05：识别并结构化暴露 LLM_TIMEOUT / LLM_ERROR / PARSE_ERROR
Task 06：Coordinator 消费模型/解析失败，调用规则大脑并统一复制 decision 为 RULE_FALLBACK、写 metadata
Task 07：Policy 在 Service 之后拒绝 LLM decision 时，调用 Coordinator 的显式单次 fallback 入口
```

`POLICY_REJECTED` 发生在 Service 返回 decision 之后，LLM Brain 本身看不到 Policy 结果，不能由 Task 05 伪装成模型失败。Task 07 必须在运行时接缝中让规则 fallback decision 重新走一次 legacy policy；禁止复用原非法 LLM decision，也禁止形成无限回退循环。

禁止 RuleBased Brain 读取线程变量、配置 mode 或 context 中的临时 fallback flag 来猜 origin。

---

## 6. 本任务目标

1. 新增 `OrchestratorDecisionBrain` 最小接口。
2. 新增 `RuleBasedOrchestratorDecisionBrain`，完整拥有现有规则逻辑。
3. `OrchestrationDecisionService` 缩为 null 防御、context normalization 和单次委托。
4. 现有 14 个 Service 行为测试继续通过。
5. 新增规则大脑直接测试，锁定节点分流、优先级、完整 decision 字段和 origin。
6. 所有直接构造点显式注入真实 RuleBased Brain，不增加隐藏静态单例或兼容构造器。
7. Task 01 的 origin/metadata 与 Task 02 的 Policy/ActionMatrix 行为不变。
8. Executor、Trace、DynamicPlanAppender、DagExecutor 和两个受影响集成 fixture 回归通过。
9. 为 Task 04/05/06 提供稳定、无 LLM 依赖的 fallback 入口。

---

## 7. 非目标

- 不实现 `LlmOrchestratorDecisionBrain`。
- 不实现 Prompt Builder、Response Parser 或 fixtures schema。
- 不调用 `ModelGateway`、外部模型 API 或抓取工具。
- 不实现 timeout、重试、temperature、fallbackReason 或 token metadata。
- 不增加 `RULE_ONLY / LLM_SHADOW / LLM_PRIMARY` 配置。
- 不让 Service 在本任务选择模式或运行 shadow。
- 不在本任务产生 `RULE_FALLBACK`。
- 不修改 `DecisionPolicyService`、ActionMatrix 或 RuleSet。
- 不修改 `DecisionExecutorAdapter`、`DynamicPlanAppender` 或 `DagExecutor.execute(...)` 业务逻辑。
- 不改变 Reviewer、Extractor、Analyzer、Writer、Citation 的 Suggestion 规则。
- 不调整阈值、评分、采集、Tavily、报告模板、前端或 E2E 样例质量。
- 不开启多轮自动补采。

如果特征测试暴露现有规则不合理，只记录为后续独立业务变更；Task 03 必须先等价抽取。

### 7.1 非阻塞业务疑点 Backlog

| Backlog ID | 当前行为 | 业务疑点 | Task 03 处理 | 后续要求 |
| --- | --- | --- | --- | --- |
| `STAGE2-RULE-001` | `quality_check_final` 同时 `passed=true`、`requiresHumanIntervention=true` 时，因为 passed 判断在前，输出 `NO_ACTION` | “评审通过”与“要求人工介入”同时出现时直接无动作，语义可能冲突 | 用 `shouldKeepPassedReviewAheadOfHumanFlagForParity` 锁定现状，不在抽取中修改 | 保持 `OPEN`；Task 03 完成后独立评估，最迟在 Task 09 阶段二验收前明确保留还是修改 |

该 backlog 不阻塞 Task 03，也不得在本任务中被顺手关闭。实现完成回写实测记录时必须继续列出 `STAGE2-RULE-001=OPEN`，避免特征测试全绿后业务疑点被静默遗忘。

---

## 8. 文件边界

### 8.1 新增

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/RuleBasedOrchestratorDecisionBrain.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/RuleBasedOrchestratorDecisionBrainTest.java
```

### 8.2 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
```

`DagExecutor.java` 只允许修改兼容构造器中的默认 `OrchestrationDecisionService` 装配和 import。若出现其他生产逻辑 diff，必须停止并重新检查范围。

### 8.3 原则上不修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOrigin.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionMetadata.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionActionMatrix.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/llm/**
backend/src/main/resources/application.yml
frontend/**
```

测试文件可以增加回归断言，但不得通过修改下游生产逻辑迁就抽取结果。

---

## 9. 结构化执行计划

| Task | 核心目标 | 预期投入（相对复杂度） | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 补规则行为、优先级和完整字段的特征测试 | M | Task 01/02 已完成，现有 Service 测试通过 |
| Task 2 | 新增 Brain 接口与 RuleBased 组件骨架 | S | Task 1 红灯成立 |
| Task 3 | 将所有 if-else、builders 和审计 helper 完整迁移 | L | Task 2 |
| Task 4 | 将 Service 缩为 normalization + delegate，并更新 9 处构造 | M | Task 3 |
| Task 5 | 验证 origin、Policy、Executor、Trace 与运行时兼容 | M | Task 4 |
| Task 6 | 跑受控回归、打包并回写实测记录 | M | Task 1-5 |

复杂度表示相对风险，不是绝对工时承诺。

---

## 10. 进度记录

当前阶段：Task 03 已完成并通过受控验收

- [x] Task 1：规则行为特征测试，已完成
- [x] Task 2：Brain 接口与组件骨架，已完成
- [x] Task 3：规则逻辑完整迁移，已完成
- [x] Task 4：薄 Service 与构造器更新，已完成
- [x] Task 5：上下游兼容回归，已完成
- [x] Task 6：受控验收与记录，已完成

每次暂停必须追加：

```markdown
### 停顿记录：YYYY-MM-DD HH:mm
- 当前阶段：
- 已完成步骤：
- 已完成占比：
- 当前测试：
- 测试结果：
- 暴露问题：
- 剩余步骤：
- 下一步：
```

---

## 11. Task 1：先写失败与特征测试

### Step 1：先在旧 Service 上建立绿色特征基线

在修改任何生产代码、创建 Brain 类之前，先扩充现有 `OrchestrationDecisionServiceTest`。原有 14 个场景保持不变，并增加以下迁移高风险断言：

- [x] `shouldReturnEmptyFromServiceWhenRawContextIsNull`
  - 锁定旧 Service 的 null 防御。
- [x] `shouldReturnRuleOnlyNoActionForUnknownTrigger`
  - 锁定 decisionId、reason、target、scope、origin 和 sourceUrls。
- [x] `shouldPreserveLegacyDirectiveOrderIdsAndLegacyOrigin`
  - 至少两条 directives，断言列表顺序、index suffix 与 `LEGACY_ADAPTER`。
- [x] `shouldPrioritizeBlockingExtractorSuggestionOverExecutableGap`
  - 锁定多轮扫描优先级，禁止改成 first-match。
- [x] `shouldPrioritizeMissingSourceGapAcrossSuggestionList`
  - 证明无来源阻断项优先于有来源可执行项。
- [x] `shouldSupportWriterAndCitationRevisionTriggerAliases`
  - 覆盖 `rewrite_report` 与 `citation_check_revision`。
- [x] `shouldKeepPassedReviewAheadOfHumanFlagForParity`
  - 同时 passed=true、requiresHuman=true 时保持当前 no action。
  - 该断言对应 `STAGE2-RULE-001`，只冻结现状，不代表业务疑点已关闭。
- [x] `shouldPreserveCompleteDecisionAuditShape`
  - 断言 reason、priority、confidence、query、inputRefs、sourceUrls、evidenceState。

绿色基线命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest" test
```

预期结果必须是 `BUILD SUCCESS`。执行者要在本文实测记录中写下测试数；如果新增断言在旧 Service 上失败，说明断言描述的是臆想行为，必须先修正测试预期，不能修改旧 Service 迎合测试。

只有这一步跑绿后，才允许开始创建 Brain 类或移动生产代码。

### Step 2：把已验证断言移植为 RuleBased Brain 红灯测试

新增 `RuleBasedOrchestratorDecisionBrainTest`，复用已经在旧 Service 上跑绿的相同输入和断言。至少覆盖原有 14 个场景：

- [x] final review legacy evidence gap -> supplement。
- [x] final review requires human -> manual review。
- [x] final review passed -> no action。
- [x] blocking diagnosis without directive -> manual review。
- [x] non-blocking failed review -> no action。
- [x] Extractor source-backed gap -> supplement。
- [x] Extractor missing-source gap -> manual review。
- [x] Analyzer source-backed gap -> supplement。
- [x] Analyzer missing-source gap -> manual review。
- [x] Writer missing-source citation gap -> manual review。
- [x] Writer source-backed citation gap -> rewrite section。
- [x] Citation missing-source gap -> manual review。
- [x] Citation source-backed weak support -> rewrite claim。
- [x] Citation explicit evidence repair -> supplement。

Brain 测试必须显式调用：

```java
brain.decide(rawContext.normalized())
```

借此固定“Service 拥有 normalization，Brain 消费 normalized context”的边界。

同时把 Step 1 的非 Service 边界场景移植到 Brain 测试；`shouldReturnEmptyFromServiceWhenRawContextIsNull` 只保留在 Service 测试，因为 Brain 的契约输入是非 null normalized context。

禁止先根据文档手写一套 Brain 期望值、再跳过旧 Service 验证。Brain 红灯断言必须来自 Step 1 已经证明为绿色的现状。

### Step 3：Service 委托边界测试

修改 `OrchestrationDecisionServiceTest`，除保留原 14 个行为测试外增加：

- [x] raw context 会在传给 Brain 前归一化。
- [x] null context 不调用 Brain。
- [x] Service 原样返回 Brain 的 decision 列表，不二次改 origin 或字段。

原有 14 个测试在 Task 03 内不得删除或弱化。它们作为外部 Service 行为护栏，新 Brain 测试作为内部规则能力护栏；Task 06 coordinator 改造时再调整 Service 测试职责。

红灯命令：

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest" test
```

预期红灯：旧 Service 特征测试已经绿色，但新 Brain 测试因缺少 `OrchestratorDecisionBrain` / `RuleBasedOrchestratorDecisionBrain` 而失败，或委托测试证明 Service 尚未接入新组件。禁止通过只创建空类让测试变绿。

---

## 12. Task 2：新增 Brain 契约与组件骨架

- [x] 新增 `OrchestratorDecisionBrain` 接口，只声明一个 `decide(...)` 方法。
- [x] 接口 Javadoc 用中文写明 normalized input、非 null list、无执行副作用。
- [x] 新增 `RuleBasedOrchestratorDecisionBrain implements OrchestratorDecisionBrain`。
- [x] 使用 `@Component` 和构造器注入 `OrchestrationDecisionAdapter`。
- [x] 不添加默认实现、静态单例、Service locator 或 Spring context lookup。
- [x] 不在接口中泄漏 `DecisionPolicyResult`、`DynamicPlanMutation` 或 LLM DTO。

局部编译：

```powershell
mvn -pl backend -DskipTests compile
```

---

## 13. Task 3：完整迁移规则逻辑

### Step 1：迁移 trigger 分流

从 Service 移入 RuleBased Brain：

```text
extract_schema
analyze_competitors
write_report / rewrite_report
citation_check / citation_check_revision
quality_check_final
unknown trigger
```

- [x] 保持判断顺序和 reason 文本原样。
- [x] 不把字符串常量重命名或统一枚举化；此类改进不属于等价抽取。
- [x] 不改变 passed 与 requiresHumanIntervention 的先后顺序。

### Step 2：迁移 Suggestion 分支

- [x] `decideExtractorSuggestions(...)`
- [x] `decideAnalyzerSuggestions(...)`
- [x] `decideWriterSuggestions(...)`
- [x] `decideCitationSuggestions(...)`
- [x] `isWriterTrigger(...)`
- [x] `isCitationTrigger(...)`
- [x] `shouldRepairEvidenceFromCitationSuggestion(...)`

复杂条件保留中文注释，特别说明为何先扫描全量阻断项、再选择可执行项。

### Step 3：迁移 decision builders

- [x] `supplementEvidenceFromSuggestion(...)`
- [x] `rewriteSectionFromSuggestion(...)`
- [x] `rewriteClaimFromSuggestion(...)`
- [x] `noAction(...)`
- [x] `waitForHuman(...)`
- [x] final blocking diagnosis builder

所有字段逐项对照特征测试，不抽象成通用大 builder。字段相似不等于业务语义相同，过早合并容易丢失不同 decisionId、confidence 或 reason。

### Step 4：迁移证据与审计 helper

- [x] `hasBlockingDiagnosis(...)`
- [x] `resolveEvidenceState(...)`
- [x] `buildInputRefs(...)`
- [x] `collectDiagnosisRefs(...)`
- [x] `collectSuggestionRefs(...)`
- [x] legacy directive 的 `AtomicInteger` 顺序映射

迁移完成后执行：

```powershell
rg -n "decideExtractorSuggestions|decideAnalyzerSuggestions|decideWriterSuggestions|decideCitationSuggestions|supplementEvidenceFromSuggestion|rewriteSectionFromSuggestion|rewriteClaimFromSuggestion|hasBlockingDiagnosis|buildInputRefs" backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
```

预期无匹配。若 Service 仍保留任一规则方法，说明根因能力尚未完整抽出。

局部测试：

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest" test
```

---

## 14. Task 4：薄 Service 与构造器更新

### Step 1：固定 normalization owner

`OrchestrationDecisionService.decide(...)` 只执行：

```text
rawContext == null -> List.of()
rawContext.normalized()
ruleBasedDecisionBrain.decide(normalizedContext)
return 原列表
```

- [x] 不捕获或重写规则大脑异常；当前 RuleBased Brain 无外部调用，不应产生需要 fallback 的异常协议。
- [x] 不在 Service 再遍历 decision 补 origin。
- [x] 不在 Brain 再调用 context.normalized()。
- [x] 不改变 public 方法签名，保证调用方 API 稳定。

### Step 2：更新直接构造点

所有直接构造改为显式装配：

```java
new OrchestrationDecisionService(
        new RuleBasedOrchestratorDecisionBrain(
                new OrchestrationDecisionAdapter()))
```

测试文件可以增加私有 factory 减少重复，但 factory 只能做依赖装配，不得生成业务输入或隐藏断言。

必须更新：

- [x] `DagExecutor.java` 兼容构造器 1 处。
- [x] `OrchestrationDecisionServiceTest` 1 处。
- [x] `DagExecutorTest` 2 处。
- [x] `StageOneDegradedContractIntegrationTest` 1 处。
- [x] `CollaborationPlanningSmokeTest` 4 处。

不得为了保留旧调用新增：

```java
OrchestrationDecisionService(OrchestrationDecisionAdapter adapter)
```

该兼容构造器会让 Service 继续知道 Adapter，并在 Spring 多构造器选择和未来 Brain 注入处制造接缝。

完成后检查：

```powershell
rg -n "new OrchestrationDecisionService\s*\(new OrchestrationDecisionAdapter" backend/src/main/java backend/src/test/java
```

预期无匹配。

### Step 3：Spring 装配检查

- [x] `OrchestrationDecisionAdapter` 是现有 `@Component`。
- [x] `RuleBasedOrchestratorDecisionBrain` 是唯一新增 `@Component`。
- [x] RuleBased Brain 的构造器实际接收 `OrchestrationDecisionAdapter`，嵌套手工装配和 Spring 注入使用同一依赖形状。
- [x] `OrchestrationDecisionService` 通过构造器注入具体 RuleBased Brain。
- [x] 不新增 `@Primary`、字符串 qualifier 或循环依赖。

---

## 15. Task 5：上下游兼容验证

### 15.1 Origin 与 Adapter

- [x] native rule 输出保持 `RULE_ONLY`。
- [x] legacy directive 输出保持 `LEGACY_ADAPTER`。
- [x] 本任务没有任何 `RULE_FALLBACK` 新生产点。
- [x] metadata normalization 和 sourceUrls 不变。

### 15.2 Policy 与 ActionMatrix

- [x] `RULE_ONLY` / `LEGACY_ADAPTER` 继续进入 legacy policy。
- [x] 规则输出不调用 LLM ActionMatrix。
- [x] Task 02 的合法/非法 LLM 矩阵测试保持全绿。
- [x] legacy rerun 和 domain hint 行为不变。

### 15.3 Executor、Trace 与 DynamicPlanAppender

- [x] Policy allowed 的现有 supplement/rewrite 仍能转 mutation。
- [x] Trace 继续看到相同 origin、reason、inputRefs、sourceUrls。
- [x] DynamicPlanAppender 仍只消费 Service public API，不感知 Brain 类型。
- [x] 不修改 DynamicPlanAppender 生产逻辑。

### 15.4 DagExecutor 与集成 fixtures

- [x] `DagExecutorTest` 构造与运行回归通过。
- [x] `CollaborationPlanningSmokeTest` 的 Extractor/Analyzer/Writer Suggestion 路由保持不变。
- [x] `StageOneDegradedContractIntegrationTest` 保持当前降级契约。
- [x] `DagExecutor.java` 除默认装配外无业务 diff。

---

## 16. 接缝检查表

| 接缝 | Task 03 必须锁定的语义 | 验证方式 |
| --- | --- | --- |
| Raw Context -> Service | null 返回 empty；非 null 只归一化一次 | Service mock Brain 测试 |
| Service -> Rule Brain | 只委托，不保留第二份规则 | 源码搜索 + Service 测试 |
| Rule Brain -> Decision | list 顺序、完整字段、reason、origin 不变 | Brain 特征测试 |
| Rule Brain -> Adapter | legacy directives 继续唯一通过 Adapter 转换 | 多 directive 测试 |
| Rule Brain -> Task 01 Origin | native=`RULE_ONLY`，legacy=`LEGACY_ADAPTER` | Origin 断言 |
| Rule Brain -> Task 02 Policy | 继续走 legacy ruleSet，不进入 LLM matrix | Policy 回归 |
| Decision -> Executor | action/target/scope 未因抽取改变 | Executor 回归 |
| Decision -> Trace | sourceUrls/inputRefs/origin 可审计 | Trace 回归 |
| Service -> DynamicPlanAppender | public API 和决策结果不变 | DynamicPlanAppenderTest |
| Constructor -> DagExecutor | 只改变装配，不改变调度 | diff 检查 + DagExecutorTest |
| Rule Brain -> Task 05/06 | Brain 不猜 fallback；Coordinator 单点覆盖 origin/metadata | 文件边界检查 |

任一行未通过，Task 03 不能完成。

---

## 17. 分层验收命令

### 17.1 规则大脑与 Service 等价层

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest" test
```

### 17.2 Task 01/02 契约兼容层

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationContractTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest" test
```

### 17.3 运行时与构造器层

```powershell
mvn -pl backend "-Dtest=DagExecutorTest,DynamicPlanAppenderTest,CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test
```

`CollaborationPlanningSmokeTest` 与 `StageOneDegradedContractIntegrationTest` 在计划编写时合计 10/10 通过，因此本任务将其列为强制构造器和业务路由回归，不作为“已知失败”跳过。

### 17.4 Task 03 受控回归

```powershell
mvn -pl backend "-Dtest=RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionOriginTest,OrchestrationContractTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DagExecutorTest,DynamicPlanAppenderTest,CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test
```

### 17.5 全量编译与打包

```powershell
mvn -pl backend clean package -DskipTests
```

### 17.6 可选完整 backend 回归

```powershell
mvn -pl backend test
```

Task 01 已记录完整 backend 基线为 `1188 tests / 13 failures / 5 skipped`，失败集中在阶段一 Citation/Collector/Writer、旧集成、coverage 与 WorkflowFactory 断言。Task 03 不通过修改这些模块制造全绿；若运行完整回归，必须对比失败集合，确认没有新增 orchestration/Policy/runtime 失败。

---

## 18. 完成标准

- [x] `OrchestratorDecisionBrain` 接口职责最小且不泄漏执行/LLM 细节。
- [x] `RuleBasedOrchestratorDecisionBrain` 是现有规则判断的唯一事实源。
- [x] `OrchestrationDecisionService` 不再包含 trigger、Suggestion 或 builder 业务规则。
- [x] raw context normalization 只有 Service 一个 owner。
- [x] 新增特征断言在抽取前已对旧 Service 跑绿，并记录基线测试数。
- [x] 原 14 个 Service 行为测试不删除、不弱化并全部通过。
- [x] 新 Brain 特征测试覆盖优先级、别名、多 directive、完整字段和 origin。
- [x] native 规则输出保持 `RULE_ONLY`。
- [x] legacy directive 输出保持 `LEGACY_ADAPTER`。
- [x] 本任务没有产生或伪造 `RULE_FALLBACK`。
- [x] Task 02 LLM ActionMatrix 未复制进规则大脑。
- [x] `sourceUrls`、`evidenceState`、reason、inputRefs、decisionId 和列表顺序保持不变。
- [x] 9 处直接构造均显式注入 RuleBased Brain，未新增 Adapter 兼容构造器。
- [x] `DagExecutor.java` 只有兼容构造装配变化。
- [x] Policy、Executor、Trace、DynamicPlanAppender 和两个集成 fixture 回归通过。
- [x] Task 03 受控回归和 clean package 通过，实际结果已写回本文。
- [x] 未修改 LLM、采集、评分、报告模板、前端或运行时确认门。
- [x] `STAGE2-RULE-001` 仍以 `OPEN` 状态记录，没有被特征测试通过静默关闭。

---

## 19. 与后续任务的接口约束

### 交给 Task 04：Prompt、Parser 与 Fixtures

- Task 04 不修改 RuleBased Brain。
- Parser 只构造 LLM decision，并复用 Task 02 ActionMatrix。
- Prompt/Parser 测试可以使用与 RuleBased Brain 相同的 `OrchestrationContext` fixture，但禁止复用规则输出作为 LLM 期望值的唯一来源；人工标注仍需独立。

### 交给 Task 05：LLM Brain 与失败结果

- `LlmOrchestratorDecisionBrain` 实现同一个 `OrchestratorDecisionBrain` 接口。
- 外部 LLM 调用必须有 try-catch、短 timeout、Max Retries 和解析失败处理。
- Task 05 负责把 timeout/error/parse failure 表达为结构化失败事实，不把异常或失败吞成空列表、伪 `NO_ACTION`。如 `List<OrchestrationDecision>` 无法携带失败信息，应增加 coordinator 可消费的 typed exception 或 attempt result，但不得修改 RuleBased Brain 的签名与规则。
- Task 05 不修改 RuleBased Brain 的规则或 origin。

### 交给 Task 06：Coordinator、Modes 与 Fallback Origin

- Coordinator 显式持有 RuleBased 与 LLM 两个 Brain，不使用 `@Primary` 猜主路径。
- `RULE_ONLY` 模式直接消费 RuleBased 输出。
- LLM 失败后 Coordinator 调用 RuleBased Brain，并统一将最终 decision origin 覆盖为 `RULE_FALLBACK`、写入 Task 01 metadata。
- 一旦调用原因是 LLM fallback，无论 RuleBased Brain 内部候选原本是 `RULE_ONLY` 还是 `LEGACY_ADAPTER`，Coordinator 输出给下游的最终 origin 都必须统一覆盖为 `RULE_FALLBACK`；禁止 Brain 自行判断或保留混合 origin。
- Service coordinator 改造不得把规则 if-else 搬回 Service。

### 交给 Task 07/08：运行时与审计

- DynamicPlanAppender 继续只调用 Service public API。
- Policy/Executor 只消费最终 coordinator decision，不知道具体 Brain 实现。
- Policy 拒绝 LLM decision 时，由 Task 07 调用 Task 06 提供的显式单次 fallback 入口；fallback decision 必须重新走 legacy policy，且同一决策周期最多回退一次。
- Trace/Report/Replay 展示最终 origin/metadata，不重新运行 Brain。

---

## 20. 实测记录

Task 03 已于 2026-07-13 完成代码实现与受控验收。

```markdown
当前阶段：Task 03 规则大脑抽取已完成
- [x] 信息采集：设计文档、主计划、Task 01/02 交付、Service/Adapter/Context 和 9 处直接构造点已核对
- [x] 数据分析：旧 Service 行为、优先级、origin 边界、normalization owner 和后续 fallback owner 已冻结
- [x] 报告撰写：Brain 契约、唯一规则实现、薄 Service 与实测记录已完成
- [x] 质检复核：受控回归 122/122、干净编译与 Spring Boot JAR 打包均通过

计划编写期实际命令：
mvn -pl backend "-Dtest=CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test

计划编写期测试结果：Tests run: 10, Failures: 0, Errors: 0, BUILD SUCCESS

实现期关键验证：
1. 抽取前旧 Service 特征基线：OrchestrationDecisionServiceTest，22/22 通过
2. 抽取后规则等价层：RuleBasedOrchestratorDecisionBrainTest、OrchestrationDecisionServiceTest、OrchestrationDecisionAdapterTest，35/35 通过
3. Task 01/02 兼容层：Origin、Contract、ActionMatrix、Policy、Executor、Trace，44/44 通过
4. 运行时与构造器层：DagExecutor、DynamicPlanAppender、两个集成 fixture，43/43 通过
5. Task 03 受控总回归：13 个测试类，122/122 通过
6. mvn -pl backend clean package -DskipTests：515 个主源码和 282 个测试源码重新编译通过，JAR 打包成功

结构检查结果：旧 Adapter 构造形状 0 处；Service 规则方法 0 处；9 处手工装配全部显式注入 RuleBased Brain；Rule Brain 无 Policy、ActionMatrix、context 二次归一化或 RULE_FALLBACK 生产点。

暴露问题：`STAGE2-RULE-001=OPEN`（passed 与 requiresHuman 同时为 true 时当前输出 NO_ACTION，待独立评估）；Maven settings.xml 仍有既有 mirrors 标签警告。可选完整 backend 回归未执行，本任务按计划以 122 个受控测试和 clean package 为完成门槛。
```
