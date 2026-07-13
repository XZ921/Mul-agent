# Task 02 LLM 动作矩阵与 Policy 双层护栏 Implementation Plan

> 本文是阶段二第二个可独立执行的代码任务。执行者必须按 checkbox 更新进度，并把红灯、实现、回归、停顿点和暴露问题持续写回本文。本文不要求使用任何 skill。

**Goal:** 基于 Task 01 已固定的 `decisionOrigin -> decisionContract` 契约，建立 LLM 决策动作矩阵唯一事实源，阻断 `decisionType`、`actionType`、`normalizedAction`、`targetNode`、`affectedScope` 之间的非法组合；同时保持 `RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` 的 legacy ruleSet 行为不变，特别是 `RERUN_NODE` 和 `DOMAIN_HINT_DISCOVERY` 不被 LLM 白名单误杀。

**Architecture:** 新增无外部依赖的 `OrchestrationDecisionActionMatrix`，集中定义 LLM 允许的动作规则、默认目标、默认作用域和执行层归一化动作。Task 02 立即把该矩阵接入 `DecisionPolicyService` 作为绕过 parser 时的确定性二次护栏；Task 04 创建 `OrchestrationDecisionResponseParser` 时必须复用同一矩阵完成首次校验和默认值填充，禁止复制规则表。非法 LLM 决策只产生 `allowed=false`、稳定阻断原因和 trace 事实，不生成 mutation；规则与历史来源继续走现有 `DecisionPolicyRuleSet`。

**Tech Stack:** Java 17, Spring Boot, Lombok, Maven, JUnit 5, AssertJ, Mockito, existing orchestration policy and workflow outbox.

---

## 1. 与 Task 01 的衔接

Task 01 已交付以下不可回退契约：

```text
decision.decisionOrigin
  -> OrchestrationDecisionOrigin.decisionContract()
  -> LLM_ACTION_MATRIX 或 LEGACY_RULE_SET
```

当前来源含义：

| `decisionOrigin` | `decisionContract` | Task 02 行为 |
| --- | --- | --- |
| `LLM_PRIMARY` | `LLM_ACTION_MATRIX` | 强制校验 LLM 动作矩阵 |
| `LLM_SHADOW` | `LLM_ACTION_MATRIX` | 同样校验并记录结果，但后续不得驱动 DAG |
| `RULE_ONLY` | `LEGACY_RULE_SET` | 不套用 LLM 矩阵，继续现有 ruleSet |
| `RULE_FALLBACK` | `LEGACY_RULE_SET` | 不套用 LLM 矩阵，保证 fallback 能表达 legacy 动作 |
| `LEGACY_ADAPTER` | `LEGACY_RULE_SET` | 保留 `RevisionDirective` 兼容行为 |

Task 02 禁止通过 `modelName`、`inputRefs`、调用栈或动作名称猜测来源。唯一入口只能是 Task 01 的类型化 `decisionOrigin`。

建议在 `OrchestrationDecisionOrigin` 增加语义方法，并让现有 `decisionContract()` 复用它：

```java
/**
 * 只有 LLM 主决策和 shadow 建议受 LLM 动作矩阵约束，
 * 规则主路径、规则回退和历史适配器继续使用 legacy ruleSet。
 */
public boolean usesLlmActionMatrix() {
    return this == LLM_PRIMARY || this == LLM_SHADOW;
}

public String decisionContract() {
    return usesLlmActionMatrix() ? "LLM_ACTION_MATRIX" : "LEGACY_RULE_SET";
}
```

Task 01 已完成的 origin、metadata、trace、report/replay 投影字段不得在本任务重新设计。

---

## 2. 当前根因与证据

### 2.1 当前 Policy 只分别校验单字段

当前 `DecisionPolicyService` 的核心路径是：

```java
if (!rules.getAllowedDecisionTypes().contains(decision.getDecisionType())) {
    blockedReasons.add("decisionType 不在允许列表：" + decision.getDecisionType());
}

String normalizedAction = resolveNormalizedAction(decision);
if (!rules.getAllowedDynamicActions().contains(normalizedAction)) {
    blockedReasons.add("normalizedAction 不在允许列表：" + normalizedAction);
}
```

这只能证明两个字段分别存在于白名单，不能证明组合语义成立。例如：

```text
REWRITE_ONLY + SUPPLEMENT_EVIDENCE
APPEND_DYNAMIC_BRANCH + REWRITE_SECTION
NO_ACTION + MANUAL_REVIEW
WAIT_FOR_HUMAN + NO_ACTION
```

这些组合可能同时通过单字段检查，并被 `resolveNormalizedAction(...)` 转成一个看似可执行的 mutation 动作。

### 2.2 `normalizedAction` 目前是第二套分散映射

当前执行动作由独立 switch 推导：

```java
private String resolveNormalizedAction(OrchestrationDecision decision) {
    return switch (decision.getActionType()) {
        case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
        case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
        case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
        case "NO_ACTION" -> "NO_ACTION";
        case "MANUAL_REVIEW" -> "MANUAL_ONLY";
        default -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()) ? "MANUAL_ONLY" : "NO_ACTION";
    };
}
```

如果矩阵只校验 pair，但 normalized action 仍由另一套 switch 解释，后续新增动作时仍可能出现“矩阵说合法，执行映射却走错”的接缝。因此 LLM 合法规则必须同时给出唯一 normalized action；legacy switch 可以保留，但要明确改名为 legacy 映射。

### 2.3 目标节点和作用域也属于动作语义

只校验 pair 仍不够：

```text
APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE -> targetNode=rewrite_report
REWRITE_ONLY / REWRITE_SECTION -> affectedScope=CURRENT_NODE_AND_DOWNSTREAM
WAIT_FOR_HUMAN / MANUAL_REVIEW -> targetNode=collect_sources
```

这些决策 pair 合法，但实际执行目标错误。设计文档已经为每个组合定义默认 `targetNode` 和 `affectedScope`，Task 02 必须把它们纳入同一条规则，而不是等 Executor 再猜。

### 2.4 Parser 尚未存在，不能制造假双层校验

主计划要求 parser/policy 双层校验，但 `OrchestrationDecisionResponseParser` 按任务顺序要到 Task 04 才创建。

正确拆分：

```text
Task 02
  -> 创建唯一 ActionMatrix
  -> Policy 立即调用 ActionMatrix
  -> 固定 Parser 必须复用的 resolveRule / default contract API

Task 04
  -> Parser 调用同一个 ActionMatrix
  -> 先解析枚举，再查 rule、填默认值、校验
  -> 禁止复制矩阵表
```

Task 02 不创建空壳 parser，也不把真正矩阵校验推迟到 Task 04。

---

## 3. 目标

1. 新增 `OrchestrationDecisionActionMatrix`，成为 LLM 动作语义唯一事实源。
2. 固定四类 LLM `decisionType` 与五条合法 action rule。
3. 每条规则同时定义 `normalizedAction`、默认/允许的 `targetNode` 和 `affectedScope`。
4. `DecisionPolicyService` 仅对 `LLM_PRIMARY`、`LLM_SHADOW` 应用矩阵。
5. 非法 LLM pair、目标节点或作用域必须返回 `allowed=false`。
6. 非法组合不能被静默改写成合法组合，也不能先派生可执行 normalized action。
7. LLM rewrite 缺少可追溯来源时必须阻断，不能只标记高风险后继续执行。
8. `RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` 继续走 legacy ruleSet。
9. legacy `RERUN_NODE` 继续 allowed 且 requiresConfirmation；`DOMAIN_HINT_DISCOVERY` 继续降级为 `MANUAL_ONLY`。
10. Policy 的稳定阻断码、rule refs 和 origin/contract 一起进入现有 trace payload。
11. `DecisionExecutorAdapter` 不新增第三套矩阵，只消费 `allowed=true` 的 policy result。
12. 为 Task 04 固定 parser 可直接复用的 rule 查询和默认值接口。

---

## 4. 非目标

本任务明确不处理：

- 不创建 `OrchestrationDecisionResponseParser`；该类属于 Task 04。
- 不实现 prompt builder、prompt injection 防护或人工标注 fixtures。
- 不抽取 `RuleBasedOrchestratorDecisionBrain`；该能力属于 Task 03。
- 不调用外部 LLM API，不修改 `ModelGateway`、timeout、temperature 或 retry。
- 不实现 LLM 失败后的规则 fallback 调度；Task 05/06 的 brain/coordinator 负责消费 Policy 阻断原因并回退。
- 不实现 `RULE_ONLY / LLM_SHADOW / LLM_PRIMARY` 配置模式。
- 不实现 `requiresConfirmation=true` 的运行时暂停/确认门；该能力属于 Task 07。
- 不实现 `maxDynamicBranchesPerSection` 的运行时计数和阻断；该能力属于 Task 07。
- 不修改 `DynamicPlanAppender`、`DagExecutor` 或动态节点模板。
- 不修改 report/replay DTO 字段和前端展示；Task 01 已固定投影形状，Task 08 负责展示。
- 不修改 Reviewer 分数、采集策略、Tavily、报告模板或阶段一降级口径。

这里必须区分两个问题：

```text
Task 02：决策语义是否合法，Policy 是否允许进入 Executor
Task 07：已允许的高风险决策是否完成确认、是否超过每章节分支上限
```

Task 02 不能因为确认门尚未实现而放过非法 LLM 动作；Task 07 也不能重新解释动作矩阵。

---

## 5. LLM 动作矩阵

### 5.1 唯一合法规则

| Rule ID | `decisionType` | `actionType` | `normalizedAction` | 目标节点规则 | `affectedScope` |
| --- | --- | --- | --- | --- | --- |
| `LLM_NO_ACTION` | `NO_ACTION` | `NO_ACTION` | `NO_ACTION` | 等于 `triggerNodeName` | `CURRENT_NODE_ONLY` |
| `LLM_SUPPLEMENT_EVIDENCE` | `APPEND_DYNAMIC_BRANCH` | `SUPPLEMENT_EVIDENCE` | `CREATE_SUPPLEMENT_BRANCH` | 固定 `collect_sources` | `CURRENT_NODE_AND_DOWNSTREAM` |
| `LLM_REWRITE_SECTION` | `REWRITE_ONLY` | `REWRITE_SECTION` | `CREATE_REWRITE_BRANCH` | 固定 `rewrite_report` | `CURRENT_NODE_ONLY` |
| `LLM_REWRITE_CLAIM` | `REWRITE_ONLY` | `REWRITE_CLAIM` | `CREATE_REWRITE_BRANCH` | 固定 `rewrite_report` | `CURRENT_NODE_ONLY` |
| `LLM_MANUAL_REVIEW` | `WAIT_FOR_HUMAN` | `MANUAL_REVIEW` | `MANUAL_ONLY` | 等于 `triggerNodeName` | `CURRENT_NODE_ONLY` |

LLM 明确禁止：

```text
RERUN_NODE
DOMAIN_HINT_DISCOVERY
CREATE_RERUN_BRANCH
任意未知 decisionType/actionType
任意跨规则拼接组合
```

`RERUN_NODE`、`DOMAIN_HINT_DISCOVERY` 只能存在于 legacy contract。

### 5.2 Rule 结构

建议矩阵内部使用不可变 rule，不把字段散落在多个 switch：

```java
public record ActionRule(
        String ruleId,
        String decisionType,
        String actionType,
        String normalizedAction,
        TargetNodePolicy targetNodePolicy,
        String fixedTargetNode,
        String affectedScope
) {
}
```

`TargetNodePolicy` 只需要：

```text
TRIGGER_NODE
FIXED_NODE
```

矩阵必须提供：

```java
Optional<ActionRule> findRule(String decisionType, String actionType);

ActionMatrixValidation validate(OrchestrationDecision decision);
```

Task 04 parser 将使用 `findRule(...)` 获取默认 `targetNode`、`affectedScope` 和合法 normalized action；Policy 使用 `validate(...)` 防止任何调用方绕过 parser。

### 5.3 Validation 结果

建议使用不可变结果：

```java
public record ActionMatrixValidation(
        boolean valid,
        String ruleId,
        String normalizedAction,
        List<String> violationCodes
) {
}
```

稳定 violation code：

| Code | 含义 |
| --- | --- |
| `INVALID_DECISION_ACTION_PAIR` | pair 不在矩阵中 |
| `INVALID_LLM_TARGET_NODE` | target 与 rule 不一致或缺失 |
| `INVALID_LLM_AFFECTED_SCOPE` | scope 与 rule 不一致 |

约束：

- validation 不修改原 decision。
- validation 不把非法 pair 自动转为 `WAIT_FOR_HUMAN`。
- validation 不调用 Policy、Executor、LLM 或 repository。
- violation 顺序稳定，便于测试和 trace 对比。
- 合法结果必须携带唯一 normalized action。

### 5.4 默认值与严格校验的分工

Task 02 的 matrix 可以暴露 rule 默认值，但 Policy 不替 LLM 决策偷偷补值：

```text
Parser（Task 04）
  -> findRule
  -> 填充缺失 targetNode / affectedScope
  -> validate

Policy（Task 02）
  -> validate 已构造 decision
  -> 缺失或错误 target/scope 直接阻断
```

这样既允许 parser 按协议补默认值，也能阻止绕过 parser 的调用方提交不完整 LLM decision。

Task 02 的 Matrix/Policy 合法路径测试必须构造“模拟 Parser 已按同一 rule 补值”的完整 decision，不能把缺失字段当作合法输入：

```text
LLM_NO_ACTION
  -> targetNode = triggerNodeName
  -> affectedScope = CURRENT_NODE_ONLY

LLM_SUPPLEMENT_EVIDENCE
  -> targetNode = collect_sources
  -> affectedScope = CURRENT_NODE_AND_DOWNSTREAM

LLM_REWRITE_SECTION / LLM_REWRITE_CLAIM
  -> targetNode = rewrite_report
  -> affectedScope = CURRENT_NODE_ONLY

LLM_MANUAL_REVIEW
  -> targetNode = triggerNodeName
  -> affectedScope = CURRENT_NODE_ONLY
```

不要依赖 `OrchestrationDecision.normalized()` 把测试输入补成完整协议：它对缺失 `affectedScope` 的兼容兜底是 `CURRENT_NODE_ONLY`，但缺失 `targetNode` 仍为 `null`。因此，Task 02 直接向 Policy 提交缺失 target 的 decision 时，预期结果就是 `INVALID_LLM_TARGET_NODE`；这属于绕过 Parser 时的防御行为，不与 Task 04 负责补默认值冲突。

---

## 6. Policy 校验顺序

`DecisionPolicyService.evaluate(...)` 调整后必须保持以下顺序：

```text
1. 归一化 decision 和 ruleSet
2. 读取 decisionOrigin / decisionContract
3. 校验 decisionType 单字段白名单
4. 如果是 LLM contract，执行 ActionMatrix.validate
5. 只有矩阵合法时，读取矩阵给出的 normalizedAction
6. 如果是 legacy contract，执行原 legacy normalizedAction 映射
7. 校验 normalizedAction 是否在 ruleSet 允许列表
8. 校验 sourceUrls/evidenceState、query、次数、任务/节点状态
9. 计算 risk、requiresConfirmation、routing hints
10. 输出 DecisionPolicyResult
```

已核对当前 `DecisionPolicyRuleSet` 默认 `allowedDynamicActions`，其中明确包含：

```text
CREATE_SUPPLEMENT_BRANCH
CREATE_RERUN_BRANCH
CREATE_REWRITE_BRANCH
MANUAL_ONLY
NO_ACTION
```

因此，`LLM_MANUAL_REVIEW -> MANUAL_ONLY` 与 `LLM_NO_ACTION -> NO_ACTION` 在默认 ruleSet 下不会被步骤 7 误拦。步骤 7 对 LLM 与 legacy 都必须保留：ActionMatrix 固定协议语义，`allowedDynamicActions` 作为部署/运营策略仍可进一步收紧可执行动作，不能因为矩阵已判合法就跳过二次校验。若自定义 ruleSet 移除 `MANUAL_ONLY`，语义合法的 `LLM_MANUAL_REVIEW` 仍必须被 Policy 阻断。

核心伪代码：

```java
String normalizedAction;
if (decision.getDecisionOrigin().usesLlmActionMatrix()) {
    ActionMatrixValidation matrixResult = actionMatrix.validate(decision);
    ruleRefs.add("llmDecisionActionMatrix");
    if (!matrixResult.valid()) {
        blockedReasons.addAll(toBlockedReasons(matrixResult.violationCodes()));
        normalizedAction = "MANUAL_ONLY";
    } else {
        ruleRefs.add(matrixResult.ruleId());
        normalizedAction = matrixResult.normalizedAction();
    }
} else {
    normalizedAction = resolveLegacyNormalizedAction(decision);
}
```

非法 LLM 决策即使 `normalizedAction=MANUAL_ONLY` 也必须保持 `allowed=false`；该值只用于稳定审计，Executor 必须因 `allowed=false` 返回 `NO_MUTATION`。

### 6.1 LLM rewrite 来源护栏

现有 legacy 行为允许缺来源 rewrite 以 `allowed=true + requiresConfirmation=true` 存在。Task 02 不改变 legacy 行为，但 LLM contract 必须更严格：

```text
decisionOrigin = LLM_PRIMARY 或 LLM_SHADOW
decisionType = REWRITE_ONLY
sourceUrls = [] 或 evidenceState = MISSING_SOURCE
  -> blockedReasons 包含 MISSING_SOURCE_FOR_LLM_REWRITE
  -> allowed = false
```

原因：LLM 不能在没有来源的情况下通过“改写”掩盖证据缺口。补证或人工判断才是合法路径。

该规则属于 Policy 证据护栏，不塞进 ActionMatrix；ActionMatrix 只描述动作语义，避免把证据状态和动作表耦合成大类。

### 6.2 Legacy 行为保持

以下现有测试语义必须保留：

```text
LEGACY_ADAPTER + RERUN_NODE / RERUN_NODE
  -> allowed=true
  -> normalizedAction=CREATE_RERUN_BRANCH
  -> requiresConfirmation=true

LEGACY_ADAPTER + APPEND_DYNAMIC_BRANCH / DOMAIN_HINT_DISCOVERY
  -> allowed=true
  -> normalizedAction=MANUAL_ONLY
```

`RULE_ONLY` 和 `RULE_FALLBACK` 同样走 legacy 分支。

---

## 7. 稳定错误与 Trace 契约

Task 02 不新增 trace 表。`OrchestrationTraceService` 已把完整 `DecisionPolicyResult` 写入 workflow event，因此只要 Policy 输出稳定原因即可形成可审计事实。

建议阻断文本：

```text
INVALID_DECISION_ACTION_PAIR: REWRITE_ONLY 不允许搭配 SUPPLEMENT_EVIDENCE
INVALID_LLM_TARGET_NODE: APPEND_DYNAMIC_BRANCH/SUPPLEMENT_EVIDENCE 必须指向 collect_sources
INVALID_LLM_AFFECTED_SCOPE: REWRITE_ONLY/REWRITE_SECTION 必须使用 CURRENT_NODE_ONLY
MISSING_SOURCE_FOR_LLM_REWRITE: LLM rewrite 缺少可追溯 sourceUrls
```

`policyRuleRefs` 至少包含：

```text
llmDecisionActionMatrix
LLM_NO_ACTION / LLM_SUPPLEMENT_EVIDENCE / LLM_REWRITE_SECTION / LLM_REWRITE_CLAIM / LLM_MANUAL_REVIEW
```

非法组合的事件必须同时保留：

```text
decision.decisionOrigin
decision.decisionType
decision.actionType
policyResult.allowed=false
policyResult.blockedReasons
policyResult.decisionContract=LLM_ACTION_MATRIX
```

Task 05/06 后续根据稳定原因码决定 `RULE_FALLBACK` 或人工路径；Task 02 不直接改写 `decisionMetadata.fallbackReason`，因为 Policy 不拥有 fallback 调度职责。

---

## 8. 文件边界

### 8.1 新增

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionActionMatrix.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionActionMatrixTest.java
```

`ActionRule`、`ActionMatrixValidation`、`TargetNodePolicy` 优先作为 `OrchestrationDecisionActionMatrix` 的静态内部类型，除非实现后证明独立文件能明显降低复杂度。禁止为五条规则建立五个策略类。

### 8.2 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOrigin.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOriginTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java
```

实施前基线已通过全仓检索确认，只有以下两处直接调用 `new DecisionPolicyService()`；改为构造器注入 matrix 时必须先同步修改这两处：

```text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
```

生产代码中的 `DecisionPolicyService` 由 Spring 容器注入；实施前未发现 `DynamicPlanAppender` 或其测试直接实例化该服务。Task 02 为验证真实 Policy 接缝，在 `DecisionExecutorAdapterTest`、`OrchestrationTraceServiceTest`、`DynamicPlanAppenderTest` 中新增了显式传入 matrix 的测试实例；这些仅是测试 fixture，`DynamicPlanAppenderTest` 仍只承担行为回归，没有扩大生产代码修改范围。

### 8.3 原则上不修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjector.java
backend/src/main/java/cn/bugstack/competitoragent/llm/**
backend/src/main/resources/application.yml
frontend/**
```

测试文件可以增加断言，但生产逻辑边界不得扩散。如果实现需要修改本节生产文件，必须先在本文记录原因和与 Task 03/04/07 的边界影响。

---

## 9. 结构化执行计划

| Task | 核心目标 | 预期投入（相对复杂度） | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 写矩阵合法/非法组合和 legacy 隔离红灯测试 | M | Task 01 已完成，受控回归通过 |
| Task 2 | 实现唯一 `OrchestrationDecisionActionMatrix` | M | Task 1 |
| Task 3 | 将 matrix 接入 `DecisionPolicyService` 并固定校验顺序 | L | Task 2 |
| Task 4 | 增加 LLM rewrite 来源护栏和稳定阻断原因 | M | Task 3 |
| Task 5 | 验证 Policy -> Executor -> Trace 不产生非法 mutation | M | Task 3、Task 4 |
| Task 6 | 跑 legacy、DAG、DynamicPlanAppender 回归并记录结果 | M | Task 1-5 |

复杂度表示相对工作量和风险，不是绝对工时承诺。

---

## 10. 进度记录

当前阶段：Task 02 已完成实现与受控验收

- [x] Task 1：矩阵与 legacy 隔离红灯测试，已完成
- [x] Task 2：ActionMatrix 实现，已完成
- [x] Task 3：Policy origin-aware 矩阵接入，已完成
- [x] Task 4：LLM rewrite 来源护栏，已完成
- [x] Task 5：Executor/Trace 接缝回归，已完成
- [x] Task 6：受控回归与结果记录，已完成

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

## 11. Task 1：先写失败测试

### Step 1：矩阵合法组合

新增 `OrchestrationDecisionActionMatrixTest`：

- [x] `shouldResolveAllAllowedLlmDecisionActionRules`
  - 精确断言五条 rule 的 ruleId、normalizedAction、目标节点策略和 scope。
- [x] `shouldValidateAllowedLlmDecisionShapes`
  - 覆盖 `NO_ACTION`、补证、section rewrite、claim rewrite、人工复核。
  - 每个合法 decision 必须显式携带第 5.4 节规定的正确 `targetNode` 和 `affectedScope`，模拟 Task 04 Parser 已使用同一 rule 补值后的输入。
  - rewrite 合法样例必须显式使用 `targetNode=rewrite_report`、`affectedScope=CURRENT_NODE_ONLY`；不得依赖 `normalized()` 的 scope 兼容兜底。
- [x] `shouldExposeDefaultsForFutureParserWithoutMutatingDecision`
  - `findRule(...)` 能返回默认 target/scope。
  - 原 decision 不被修改。

### Step 2：非法 pair

- [x] `shouldRejectCrossedDecisionActionPairs`
  - `REWRITE_ONLY + SUPPLEMENT_EVIDENCE`
  - `APPEND_DYNAMIC_BRANCH + REWRITE_SECTION`
  - `NO_ACTION + MANUAL_REVIEW`
  - `WAIT_FOR_HUMAN + NO_ACTION`
- [x] `shouldRejectLegacyOnlyActionsForLlmContract`
  - `RERUN_NODE`
  - `DOMAIN_HINT_DISCOVERY`
- [x] `shouldRejectUnknownDecisionOrActionWithoutNormalizingToNoAction`

### Step 3：target 和 scope

- [x] `shouldRejectLlmDecisionWithWrongTargetNode`
- [x] `shouldRejectLlmDecisionWithWrongAffectedScope`
- [x] `shouldRejectLlmDecisionWithMissingTargetNode`
  - 该用例明确模拟绕过 Parser 的不完整输入，预期被 Policy/Matrix 防御性阻断，不代表 Policy 应代替 Parser 补值。

### Step 4：Policy 二次校验

修改 `DecisionPolicyServiceTest`：

- [x] `shouldAllowValidLlmSupplementDecisionThroughMatrix`
- [x] `shouldAllowValidLlmManualReviewThroughDefaultDynamicActionWhitelist`
- [x] `shouldAllowValidLlmNoActionThroughDefaultDynamicActionWhitelist`
- [x] `shouldBlockValidLlmManualReviewWhenCustomRuleSetDisallowsManualOnly`
  - 前两项证明默认 ruleSet 的 `MANUAL_ONLY`、`NO_ACTION` 与矩阵合法规则一致。
  - 第三项证明矩阵合法不绕过 `allowedDynamicActions`，自定义部署策略仍可收紧动作。
- [x] `shouldBlockInvalidLlmPairBeforeDerivingExecutableAction`
- [x] `shouldBlockLlmRewriteWhenSourceIsMissing`
- [x] `shouldApplySameMatrixToLlmShadow`
- [x] `shouldKeepLegacyRerunOutsideLlmMatrix`
- [x] `shouldKeepLegacyDomainHintDiscoveryManualOnly`
- [x] `shouldExposeStableMatrixRuleRefsAndBlockedReasons`

### Step 5：Executor 与 Trace

修改：

- [x] `DecisionExecutorAdapterTest`：Policy 阻断非法 LLM pair 后返回 `NO_MUTATION`。
- [x] `OrchestrationTraceServiceTest`：workflow payload 保留非法 pair、`allowed=false` 和稳定错误码。
- [x] `DynamicPlanAppenderTest`：仅作回归护栏，证明非法 LLM pair 不会追加节点；不得修改生产逻辑。

红灯命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest" test
```

预期红灯：缺少 `OrchestrationDecisionActionMatrix`、Policy 尚未阻断非法 LLM pair、LLM rewrite 缺来源仍为 allowed。

---

## 12. Task 2：实现唯一 ActionMatrix

- [x] 新增不可变 rule 表，精确包含第 5.1 节五条规则。
- [x] rule key 使用 `decisionType + actionType` 结构化键，不使用字符串拼接后再 split。
- [x] `findRule(...)` 对输入做 trim/uppercase，但不把未知值改成默认动作。
- [x] `validate(...)` 校验 pair、target 和 scope，返回稳定 violation code。
- [x] LLM matrix 不读取 `DecisionPolicyRuleSet`，不依赖 Spring repository、LLM、workflow 或 report。
- [x] 所有规则解释和复杂条件添加中文注释。
- [x] rule 集合构造后不可变，测试证明调用方不能修改。

局部验证：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionActionMatrixTest" test
```

---

## 13. Task 3：接入 DecisionPolicyService

### Step 1：依赖方式

推荐将 matrix 作为 stateless collaborator 注入 `DecisionPolicyService`：

```java
@Service
@RequiredArgsConstructor
public class DecisionPolicyService {
    private final OrchestrationDecisionActionMatrix actionMatrix;
}
```

同步修改已确认的两个测试直接构造点：`DecisionPolicyServiceTest` 与 `DagExecutorTest`；不增加隐藏的全局静态单例，也不扩散到 `DynamicPlanAppender`。`OrchestrationDecisionActionMatrix` 可以是 `@Component`，但内部规则必须不可变。

### Step 2：按 origin 分流

- [x] `LLM_PRIMARY/LLM_SHADOW` 调用 matrix。
- [x] `RULE_ONLY/RULE_FALLBACK/LEGACY_ADAPTER` 调用 legacy normalized action 映射。
- [x] 将原 `resolveNormalizedAction(...)` 重命名为 `resolveLegacyNormalizedAction(...)`，注释明确只服务 legacy。
- [x] 禁止比较 `decisionContract` 字符串来猜 origin，使用 `usesLlmActionMatrix()`。

### Step 3：非法结果

- [x] violation code 转成稳定 blocked reason。
- [x] 任一 matrix violation 都使 `allowed=false`。
- [x] 非法 pair 不进入 Tavily routing hints。
- [x] 非法 pair 的 normalizedAction 固定为审计用 `MANUAL_ONLY`，但不生成 mutation。
- [x] `policyRuleRefs` 记录矩阵已执行及命中的合法 ruleId。

### Step 4：保留动态动作白名单二次约束

- [x] 矩阵合法结果仍进入 `allowedDynamicActions` 校验，不为 LLM contract 建立跳过分支。
- [x] 默认 ruleSet 下 `LLM_MANUAL_REVIEW -> MANUAL_ONLY`、`LLM_NO_ACTION -> NO_ACTION` 均通过。
- [x] 自定义 ruleSet 移除 `MANUAL_ONLY` 时，合法 `LLM_MANUAL_REVIEW` 返回 `allowed=false`，证明运行策略可以收紧矩阵允许动作。
- [x] 复用当前稳定的动态动作白名单阻断原因，不新增第二套同义错误码。

局部验证：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest" test
```

---

## 14. Task 4：LLM Rewrite 来源护栏

- [x] 只对 `usesLlmActionMatrix()==true` 的 rewrite 决策应用严格来源阻断。
- [x] `sourceUrls=[]` 或 `evidenceState=MISSING_SOURCE` 时添加 `MISSING_SOURCE_FOR_LLM_REWRITE`。
- [x] 不修改原 decision，不把 rewrite 静默改成 supplement。
- [x] legacy 缺来源 rewrite 继续保持当前 `allowed=true + requiresConfirmation=true`，避免阶段一行为回归。
- [x] `confidence`、priority 不能覆盖来源阻断。

局部验证：

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest" test
```

---

## 15. Task 5：贯通 Policy、Executor 与 Trace

### Step 1：Executor

- [x] 使用真实 `DecisionPolicyService.evaluate(...)` 生成非法 LLM pair 的 policy result。
- [x] 交给 `DecisionExecutorAdapter.toMutation(...)` 后断言 `NO_MUTATION`。
- [x] 合法补证和 rewrite 仍产生既有 mutation，证明没有破坏正常链路。
- [x] 不在 Executor 复制 matrix。

### Step 2：Trace

- [x] 把非法 LLM decision 和 blocked policy result 交给 `OrchestrationTraceService.recordDecision(...)`。
- [x] 捕获 workflow payload，断言 origin、contract、原始 pair、`allowed=false`、稳定 blocked reason 同时存在。
- [x] trace 不修改 decision，也不负责调用 fallback。

### Step 3：DynamicPlanAppender

- [x] 仅通过回归测试证明 blocked policy 不追加节点。
- [x] 不修改 `DynamicPlanAppender` 的业务逻辑。
- [x] 不在本任务实现 `requiresConfirmation` 或每章节分支计数。

---

## 16. 接缝检查表

| 接缝 | Task 02 必须锁定的语义 | 验证方式 |
| --- | --- | --- |
| Task 01 Origin -> Matrix | 只有 LLM origins 使用矩阵 | Origin/Matrix/Policy 测试 |
| Matrix -> Future Parser | parser 可查询同一 rule 的默认 target/scope | Matrix API 测试 |
| Matrix -> Policy | pair、target、scope 和 normalized action 来自同一 rule | Policy 测试 |
| Matrix -> RuleSet | 矩阵合法动作仍受 `allowedDynamicActions` 二次约束；默认 `MANUAL_ONLY/NO_ACTION` 可通过 | Policy 默认/自定义 ruleSet 测试 |
| Policy -> Executor | `allowed=false` 永不产生 mutation | Executor 集成测试 |
| Policy -> Trace | 原始非法 pair 和稳定原因可审计 | Trace payload 测试 |
| Legacy -> Policy | `RERUN_NODE`、`DOMAIN_HINT_DISCOVERY` 不被 LLM 矩阵误杀 | Legacy 回归测试 |
| Evidence -> Rewrite | LLM 无来源 rewrite 阻断；legacy 保持兼容 | Policy 双路径测试 |
| Policy -> Tavily hints | 非法 LLM pair 不产生 evidence repair routing | Policy 测试 |
| Task 02 -> Task 07 | confirmation 和分支上限仍是显式待办，不由矩阵代替 | 文件边界检查 |

任一行未通过，Task 02 不能完成。

---

## 17. 分层验收命令

### 17.1 Matrix 契约层

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest" test
```

### 17.2 Policy 与 Legacy 隔离层

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest" test
```

### 17.3 Executor 与 Trace 层

```powershell
mvn -pl backend "-Dtest=DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest" test
```

`DynamicPlanAppenderTest` 仅作回归护栏，本任务不修改其生产逻辑。

### 17.4 Task 02 受控回归

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,OrchestrationContractTest,OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DagExecutorTest,DynamicPlanAppenderTest,TaskReplayProjectionServiceTest,ReportServiceTest" test
```

### 17.5 编译与打包

```powershell
mvn -pl backend -DskipTests package
```

完整 backend 回归当前存在 Task 01 已记录的 13 个阶段一/旧集成基线失败。Task 02 必须运行受控回归并可选运行完整回归；禁止通过修改 Citation/Collector/Writer、coverage 或旧集成断言来制造全绿。

---

## 18. 完成标准

- [x] 五条 LLM action rule 由唯一 matrix 固定。
- [x] pair、target、scope、normalized action 不再分散解释。
- [x] Task 02 合法路径测试使用 Parser 已补齐 target/scope 的完整 decision；缺 target 的直达 Policy 输入稳定阻断。
- [x] `LLM_PRIMARY`、`LLM_SHADOW` 非法组合均 `allowed=false`。
- [x] 非法组合不会被静默改成 `NO_ACTION`、`MANUAL_REVIEW` 或其他合法动作。
- [x] LLM 无来源 rewrite 被阻断。
- [x] legacy `RERUN_NODE` 继续 allowed 且 requiresConfirmation。
- [x] legacy `DOMAIN_HINT_DISCOVERY` 继续映射 `MANUAL_ONLY`。
- [x] 默认 `allowedDynamicActions` 允许合法 LLM `MANUAL_ONLY/NO_ACTION`，自定义白名单仍可进一步阻断矩阵合法动作。
- [x] Policy 阻断结果不会产生 Executor mutation 或 DynamicPlanAppender 节点。
- [x] Trace 能看到原始非法 pair、origin、contract 和稳定阻断原因。
- [x] Task 04 parser 的 rule 查询/default API 已固定，后续不需要复制矩阵。
- [x] 未实现或绕过 Task 07 的 confirmation gate 和分支上限职责。
- [x] 未修改 LLM、采集、评分、报告模板、DAG 和前端逻辑。
- [x] Task 02 受控回归和打包通过，实际结果已写回本文。

---

## 19. 与后续任务的接口约束

### 交给 Task 03：规则大脑抽取

- 规则大脑产生 `RULE_ONLY`；coordinator fallback 时覆盖为 `RULE_FALLBACK`。
- 两种来源都走 legacy ruleSet，不得为了复用 matrix 改写现有规则动作。
- 抽取前后 `RERUN_NODE`、`DOMAIN_HINT_DISCOVERY` 和现有 Suggestion 行为保持一致。

### 交给 Task 04：Prompt、Parser 与 Fixtures

Parser 必须按以下顺序复用 matrix：

```text
解析 decisionType/actionType 枚举
  -> actionMatrix.findRule(...)
  -> pair 不存在则 INVALID_DECISION_ACTION_PAIR
  -> 使用同一 rule 填默认 targetNode/affectedScope
  -> actionMatrix.validate(...)
  -> 形成 OrchestrationDecision
```

禁止在 parser 内新建 `Map<decisionType, actionType>`、switch 或第二份默认 target/scope 表。

### 交给 Task 05/06：LLM Brain 与 Coordinator

- Parser 失败或 Policy 返回 matrix violation 时，保留 LLM decision/response trace。
- Coordinator 根据稳定原因码选择 `RULE_FALLBACK` 或人工路径。
- Fallback decision 必须改为 `RULE_FALLBACK`，重新走 legacy policy；禁止把原 LLM origin 改字段后继续执行同一个非法 decision。

### 交给 Task 07：运行时执行整合

- `requiresConfirmation=true` 必须成为 mutation 前的真实暂停门。
- `maxDynamicBranchesPerSection` 必须进入实际计数与阻断。
- Task 07 只消费 Task 02 的 `allowed`、`normalizedAction`、blocked reasons，不重新实现矩阵。

### 交给 Task 08：Trace/Report/Replay

- 展示 Task 01 已固定的 origin/metadata。
- 展示 Task 02 的 `policyResult.allowed`、matrix rule refs 和 blocked reasons。
- replay 只读持久化结果，不重新运行 matrix 或 Policy。

---

## 20. 实测记录

Task 02 已于 2026-07-13 完成实现与受控验收。

```markdown
当前阶段：Task 02 实现完成
- [x] 信息采集：Task 01 产物、主计划、设计矩阵和当前 Policy 已核对
- [x] 数据分析：LLM/legacy 分流、normalized action、target/scope 和来源护栏已实现
- [x] 报告撰写：Task 02 计划与实测结果已回写
- [x] 质检复核：矩阵、Policy、Executor、Trace、DynamicPlanAppender 接缝和构造器注入范围已验证

实际命令：
1. mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest" test
2. mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest" test
3. mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestrationDecisionActionMatrixTest,OrchestrationContractTest,OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DagExecutorTest,DynamicPlanAppenderTest,TaskReplayProjectionServiceTest,ReportServiceTest" test
4. mvn -pl backend -DskipTests package
测试结果：首次红灯因缺少 OrchestrationDecisionActionMatrix 按预期失败；核心与接缝测试 41/41 通过；Task 02 受控回归 127/127 通过；backend 打包成功
暴露问题：DynamicPlanAppenderTest 首次验证因重载方法参数匹配不明确失败，显式限定 DynamicPlanMutation 后通过；Maven settings.xml 仍有既有 mirrors 标签警告；未运行已知存在 13 个阶段一/旧集成基线失败的完整 backend 测试
```
