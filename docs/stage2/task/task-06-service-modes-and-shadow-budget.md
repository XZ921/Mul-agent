# Task 06 Service Modes、Shadow Budget 与真正 Rule Fallback Implementation Plan

> **For agentic workers:** 本文是可直接执行的 Task 06 计划。执行者必须复用 Task 01-05 已固定的 origin/metadata、ActionMatrix、Rule Brain、Prompt/Parser、LLM Brain 与 typed failure；不得把 Policy、Executor、Trace 或 runtime 提前内联进 Coordinator，也不得让 shadow decision 驱动 DAG。

**Goal:** 将 `OrchestrationDecisionService` 从仅委托 Rule Brain 的薄服务升级为模式 Coordinator，支持默认安全的 `RULE_ONLY`、只对比不执行的 `LLM_SHADOW` 和失败时可回退规则的 `LLM_PRIMARY`；以 typed `OrchestrationDecisionOutcome` 保留主结果、shadow 结果、shadow 跳过原因和 Task 05 typed failure，并通过独立 quota key 保证 shadow 不占用正式模型预算。

**Architecture:** `OrchestrationDecisionService` 显式持有 Rule Brain、LLM Brain 和配置，不使用 `@Primary` 猜测实现。Service 只负责 context 归一化、模式分支、origin/metadata 改写和单次 Rule fallback；`DecisionPolicyService`、`DecisionExecutorAdapter`、`DynamicPlanAppender`、`DagExecutor` 与 `OrchestrationTraceService` 仍由 Task 07/08 接入。Task 06 新增不可变 coordinator outcome，使 shadow/failure 事实不因旧 `List<OrchestrationDecision>` 返回值而丢失，同时保留旧 `decide(...)` 兼容入口。Shadow 调用通过 `ModelInvocationPurpose.ORCHESTRATOR_SHADOW` 和独立 quota key 进入同一个 `ModelGateway`；`OrchestrationDecisionModelInvoker` 在提交 Future 前调用 `OrchestrationShadowBudgetGate`，以完整 Prompt 做同步配额估算和预留，组织配额未配置或耗尽时不提交 executor、不调用 Provider，并只形成 shadow skipped 事实。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Spring `ApplicationContextRunner`, existing `OrganizationQuotaPolicy`, `ModelInvocationContextHolder`, `ModelGateway`, `RuleBasedOrchestratorDecisionBrain`, `LlmOrchestratorDecisionBrain`, `LlmOrchestratorDecisionFailure`, `OrchestratorDecisionMetadata`, `DecisionPolicyRuleSet`.

---

## 1. 任务定位与依赖顺序

阶段二数字任务依赖顺序：

```text
Task 01：Decision Origin 与 Metadata Contract
  -> Task 02：LLM ActionMatrix 与 origin-aware Policy
  -> Task 03：Rule Brain 抽取
  -> Task 04：Prompt、Parser 与人工 Fixtures
  -> Task 05：LLM Brain、总 Timeout、Parse Retry 与 Typed Failure
  -> Task 06：Service Modes、Shadow Budget、真正 Rule Fallback
  -> Task 07：Policy/Executor/Runtime 接入
  -> Task 08：Trace/Report/Replay
  -> Task 09：阶段二验收
```

主计划已固定本任务文件名：

```text
task-06-service-modes-and-shadow-budget.md
```

Task 06 解决的根因：

1. 当前 `OrchestrationDecisionService` 只注入 `RuleBasedOrchestratorDecisionBrain`，Task 05 的 LLM Brain 尚未进入 Spring composition root。
2. `OrchestratorDecisionBrain` 只有 `List<OrchestrationDecision>` 返回值，无法同时表达主 decisions、shadow decisions、shadow skipped 和 LLM failure。
3. Task 05 的 `LlmOrchestratorDecisionException` 已能区分 timeout/error/parse failure，但当前没有 owner 调用 Rule Brain 并统一写 `RULE_FALLBACK`。
4. 当前 `ModelGateway` 的组织模型配额固定使用 `MODEL_DAILY_BUDGET`；若 shadow 原样调用，会与正式 Agent/LLM 主路径争用同一个配额快照。若只在 worker 内检查 shadow quota，预算已耗尽的请求仍会占用有界 executor，因此准入必须前移到 Future submit 之前。
5. 当前没有统一的 normalized `DecisionPolicyRuleSet` bean；LLM Prompt 与 Task 07 Policy 若各自 `builder().build()`，会形成两套限制事实。
6. `LLM_SHADOW` 既要保留完整对比事实，又不能进入 Policy/Executor/DAG；仅返回主 decisions 会让 Task 08 无从持久化 shadow。
7. Task 07 还需要一个显式、至多调用 Rule Brain 一次的 policy-rejected fallback 入口，不能重新调用 LLM Brain。

---

## 2. 对早期设计的 owner 校正

### 2.1 Task 06 是 fallback owner，Task 05 不是

最终边界：

```text
LlmOrchestratorDecisionBrain
  -> success：LLM_PRIMARY candidate decisions
  -> failure：LlmOrchestratorDecisionException

OrchestrationDecisionService
  -> 捕获 typed failure
  -> 根据 fallback-to-rule 决定是否调用 Rule Brain
  -> 统一把规则结果复制为 RULE_FALLBACK
  -> 写 fallback metadata
```

Task 06 禁止让 LLM Brain 依赖 Rule Brain，否则：

- Brain 无法区分 `LLM_PRIMARY` 与 `LLM_SHADOW`。
- Shadow failure 会错误触发第二次 Rule Brain。
- Coordinator 无法保证同一决策周期最多一次 fallback。
- 最终 origin 会在 Brain/Service 两处竞争 owner。

### 2.2 Task 06 不拥有 Policy、Executor 或 Trace

本任务只生成候选与协调事实：

```text
Task 06：context -> mode coordinator -> outcome
Task 07：outcome.decisions -> policy -> executor/runtime
Task 08：outcome + policy + mutation -> trace/report/replay
```

因此 Task 06 生产代码禁止依赖：

```text
DecisionPolicyService
DecisionExecutorAdapter
DynamicPlanAppender
DagExecutor
OrchestrationTraceService
WorkflowEventPublisher
```

Policy rejection fallback 由 Task 07 触发，但 Rule fallback 的构造规则由 Task 06 提供的显式方法单点拥有。

### 2.3 必须新增 typed Outcome，不能把 shadow/failure 塞进主 decision

旧接口：

```java
List<OrchestrationDecision> decide(OrchestrationContext context);
```

只能表达最终候选列表，不能表达：

- shadow 未启用还是预算耗尽。
- shadow 调用成功、失败还是未执行。
- shadow LLM 候选与规则主结果的并列事实。
- `LLM_PRIMARY` fallback 前的全部 attempt hashes/issues。

Task 06 新增：

```java
OrchestrationDecisionOutcome decideWithOutcome(OrchestrationContext rawContext);
```

旧 `decide(...)` 保留并只投影 `outcome.decisions()`，避免 Task 06 越界修改所有 runtime caller。若 `LLM_PRIMARY` 关闭 fallback 且产生 typed failure，旧入口必须重新抛 typed exception，禁止把失败吞成空列表。

### 2.4 Shadow 采用同步、确定性、短预算执行

本任务选择：

```text
Rule Brain 先生成主 decisions
  -> shadow enabled + budget admitted
  -> 同步调用 LLM Brain（仍受 Task 05 4 秒总 deadline）
  -> outcome 同时返回 rule 主结果与 shadow 事实
```

不在 Task 06 使用 fire-and-forget：

- 异步完成后没有 Task 08 trace owner 接收结果。
- Service 返回后再写共享状态会引入并发污染和恢复问题。
- 测试无法确定 outcome 是否已经完整。

代价是显式启用 shadow 时会增加最多一个 `llm-timeout-ms` 的有界延迟；默认 `shadow.enabled=false`，因此默认生产路径不增加延迟。

### 2.5 `max-daily-tokens` 不在 YAML 与数据库维护两份

早期总设计示例把 `shadow.max-daily-tokens=20000` 写在 YAML，但当前项目已有持久化 `OrganizationQuotaSnapshot.limitValue` 与 `OrganizationQuotaPolicy`。Task 06 校正为：

```text
application.yml
  -> shadow.isolated-budget-key=ORCHESTRATOR_SHADOW
  -> shadow.require-active-quota=true

organization_quota_snapshot
  -> quota_scope=MODEL
  -> quota_key=ORCHESTRATOR_SHADOW
  -> limit_value=真实每日/阶段预算 owner
```

禁止同时在 YAML 和数据库保存两个 max 值，否则重启、运维调整或多实例运行时会产生 split-brain。YAML 只选择 quota key 与安全策略，配额上限由治理快照单点拥有。

### 2.6 Shadow quota 准入必须早于 executor submit

Task 06 不采用“进入 worker 后再快速检查”的方案。最终顺序：

```text
LLM Brain 构建 typed Prompt
  -> ModelInvoker caller thread
  -> OrchestrationShadowBudgetGate.checkAndReserve(prompt)
       denied -> 立即抛稳定 typed error，Future zero submit
       allowed -> 标记 organizationQuotaReserved=true
  -> executor.submit(...)
  -> ModelGateway 复用已预留标记，不重复扣减组织配额
  -> Provider
```

这样 quota missing/exhausted 只消耗 Prompt 构建与一次本地治理查询，不占 executor worker/queue，也不会等待 Task 05 的 4 秒 `Future.get(...)`。

预留成功但 `executor.submit` 被拒绝时，Invoker 必须立即调用 gate 释放本次 shadow reservation；禁止因有界池饱和永久泄漏配额。Provider 已开始后的成功/失败结算沿用现有模型配额语义，不在 Task 06 发明第二套账务状态机。

---

## 3. Task 01-05 输入输出契约

### 3.1 Task 01：只填写既有 origin/metadata

Task 06 只使用既有：

```text
LLM_PRIMARY
LLM_SHADOW
RULE_ONLY
RULE_FALLBACK
LEGACY_ADAPTER
```

以及：

```text
modelName
temperature
promptHash
llmResponseHash
parseRetryCount
fallbackUsed
fallbackReason
shadowExecuted
shadowSkippedReason
```

不得新增平行 `metadata Map`、`inputRefs.fallback*` 或 shadow 字段。

### 3.2 Task 02：Task 06 不调用 Policy

Task 06 只保证 origin 正确：

```text
LLM_PRIMARY / LLM_SHADOW -> Task 07 使用 LLM ActionMatrix
RULE_ONLY / RULE_FALLBACK / LEGACY_ADAPTER -> Task 07 使用 legacy ruleSet
```

Task 06 不把 parser success 当作 policy allowed，也不处理 `blockedReasons`。

### 3.3 Task 03：显式持有两个 Brain

Coordinator 必须注入具体命名协作者：

```java
RuleBasedOrchestratorDecisionBrain ruleBrain;
LlmOrchestratorDecisionBrain llmBrain;
```

禁止：

```java
@Primary OrchestratorDecisionBrain brain;
List<OrchestratorDecisionBrain> brains;
```

Rule Brain 自身规则与 native origin 不修改；只有 Coordinator 在 fallback 输出副本上覆盖 `RULE_FALLBACK`。

### 3.4 Task 04：shadow 不放宽 Parser

Shadow 仍调用同一个 LLM Brain/Parser：

- unknown field、非法组合、fence、trailing text 仍失败。
- discarded URL success 仍是 success。
- shadow 不使用另一套宽松 schema。
- 9 条人工 fixtures 不因 mode 改写标签。

### 3.5 Task 05：消费 typed failure，不解析 exception message

Task 06 只读取：

```java
LlmOrchestratorDecisionException.failure()
```

禁止从 `getMessage()`、cause 或 raw response 推断 fallback 原因。

---

## 4. 当前代码证据与缺口

### 4.1 Service 仍是 Rule-only 委托

当前：

```java
private final RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain;

public List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
    if (rawContext == null) {
        return List.of();
    }
    return ruleBasedDecisionBrain.decide(rawContext.normalized());
}
```

没有 mode、LLM、shadow 或 fallback。

### 4.2 LLM Brain 不是 Spring bean

Task 05 有意保持 `LlmOrchestratorDecisionBrain` 为纯 POJO。Task 06 必须创建 composition root，同时提供唯一 normalized ruleSet：

```text
DecisionPolicyRuleSet bean
  -> LlmOrchestratorDecisionBrain constructor
  -> Task 07 DecisionPolicyService.evaluate(...)
```

### 4.3 `ModelInvocationContextHolder` 只有 task/node/trace

当前上下文无法表达 `ORCHESTRATOR_SHADOW` 或 quota key；Task 05 invoker 也只传播 traceId。若不扩展，ModelGateway 会继续固定使用：

```java
GovernanceDefaults.MODEL_DAILY_BUDGET_KEY
```

### 4.4 OrganizationQuotaPolicy 缺少 strict mode，Invoker 缺少 pre-submit gate

当前没有 active quota snapshot 时返回 `NO_ACTIVE_QUOTA` 并放行。正式模型调用保持该兼容行为，但 shadow 默认安全语义必须是：

```text
require-active-quota=true + snapshot missing
  -> SHADOW_BUDGET_NOT_CONFIGURED
  -> 不调用 Provider
```

因此需要兼容 overload，不能改变旧调用默认。同时 Task 05 invoker 当前会直接 `executor.submit`，Task 06 必须在该动作之前完成 shadow reservation，而不是等 worker 进入 ModelGateway 后再检查。

### 4.5 runtime 已有两个 Service caller

当前主要调用点：

```text
DynamicPlanAppender -> decide(...) -> Policy/Executor/Trace
DagExecutor AgentSuggestion gate -> decide(...) -> WAIT_FOR_HUMAN/Trace
```

Task 06 保留旧 `decide(...)`，Task 07 再把 runtime 迁移到 `decideWithOutcome(...)`。本任务不得顺手改动 DAG 状态或 mutation 行为。

### 4.6 读模型字段已预埋，但 Task 06 不直接持久化

`OrchestrationDecisionSummaryProjector` 已能读取 fallback/shadow metadata。Task 06 只确保 outcome 不丢事实；Task 08 再把 outcome 写入 workflow event。

---

## 5. 核心类型契约

### 5.1 OrchestratorDecisionMode

新增：

```java
public enum OrchestratorDecisionMode {
    RULE_ONLY,
    LLM_SHADOW,
    LLM_PRIMARY
}
```

配置绑定使用枚举；未知值必须在 Spring binding/validation 阶段失败，不得回退到 `LLM_PRIMARY`。

### 5.2 OrchestratorDecisionProperties 扩展

在 Task 05 既有字段上增加：

```java
private OrchestratorDecisionMode mode = OrchestratorDecisionMode.RULE_ONLY;
private boolean fallbackToRule = true;
private Shadow shadow = new Shadow();

@Data
public static class Shadow {
    private boolean enabled = false;
    private String isolatedBudgetKey = "ORCHESTRATOR_SHADOW";
    private boolean requireActiveQuota = true;
}
```

约束：

- 默认 mode 必须是 `RULE_ONLY`。
- 默认 fallback-to-rule 必须是 true。
- 默认 shadow.enabled 必须是 false。
- isolatedBudgetKey 必须非空、trim 后稳定。
- Task 05 的 temperature/timeout/retry/executor 字段行为不变。
- 不新增 Task 07 runtime gate 或 Task 08 trace 开关。

### 5.3 OrchestrationShadowExecution

新增不可变事实：

```java
public record OrchestrationShadowExecution(
        boolean requested,
        boolean executed,
        String skippedReason,
        LlmOrchestratorDecisionFailure failure,
        List<String> sourceUrls
) {
}
```

稳定语义：

| 场景 | requested | executed | skippedReason | failure |
| --- | --- | --- | --- | --- |
| RULE_ONLY / LLM_PRIMARY | false | false | null | null |
| LLM_SHADOW + enabled=false | true | false | SHADOW_DISABLED | null |
| quota 未配置 | true | false | SHADOW_BUDGET_NOT_CONFIGURED | typed failure |
| quota 耗尽 | true | false | SHADOW_BUDGET_EXHAUSTED | typed failure |
| 单请求估算超限 | true | false | SHADOW_REQUEST_BUDGET_REJECTED | typed failure |
| LLM 调用成功 | true | true | null | null |
| LLM timeout/error/parse failure | true | true | null | typed failure |

约束：

- `sourceUrls` 必填语义，null 归一为空不可变列表。
- skippedReason 非空时 executed 必须 false。
- requested=false 时 executed 必须 false。
- 不保存 raw prompt/response。

### 5.4 OrchestrationDecisionOutcome

新增：

```java
public record OrchestrationDecisionOutcome(
        OrchestratorDecisionMode mode,
        List<OrchestrationDecision> decisions,
        List<OrchestrationDecision> shadowDecisions,
        OrchestrationShadowExecution shadowExecution,
        LlmOrchestratorDecisionFailure llmFailure,
        List<String> sourceUrls
) {
}
```

约束：

- 所有 list `List.copyOf`。
- `decisions` 不能包含 `LLM_SHADOW`。
- `shadowDecisions` 必须全部是 `LLM_SHADOW`。
- `RULE_ONLY` 的 shadowDecisions 必须为空。
- `LLM_PRIMARY` failure + fallback success 同时保留 final decisions 与 llmFailure。
- `sourceUrls` 从 normalized context 与最终/影子 decisions 去重合并，满足可追溯 Schema 红线。
- outcome 不携带 PolicyResult、mutation 或 workflow event ID。

### 5.5 ModelInvocationPurpose 与 quota context

新增：

```java
public enum ModelInvocationPurpose {
    DEFAULT,
    ORCHESTRATOR_PRIMARY,
    ORCHESTRATOR_SHADOW
}
```

扩展 holder record：

```java
public record ModelInvocationContext(
        Long taskId,
        String nodeName,
        String traceId,
        ModelInvocationPurpose purpose,
        String quotaKey,
        boolean requireActiveQuota,
        boolean organizationQuotaReserved
) {
}
```

必须保留三参数 constructor/set/withContext overload，使所有旧 Agent 调用继续得到 `DEFAULT + MODEL_DAILY_BUDGET + requireActiveQuota=false + organizationQuotaReserved=false`。`organizationQuotaReserved=true` 只能由 ModelInvoker 在 pre-submit gate 成功后传入 worker，Coordinator 不得直接伪造。

### 5.6 Shadow budget admission 与 gate

新增不可变准入事实：

```java
public record OrchestrationShadowBudgetAdmission(
        boolean allowed,
        String decisionCode,
        int reservedUnits,
        List<String> sourceUrls
) {
}
```

新增 `OrchestrationShadowBudgetGate`：

```java
public OrchestrationShadowBudgetAdmission checkAndReserve(
        OrchestrationDecisionPrompt prompt,
        ModelInvocationContextHolder.ModelInvocationContext context);

public void release(OrchestrationShadowBudgetAdmission admission);
```

Gate 单一职责：

- 只处理 `ORCHESTRATOR_SHADOW`；DEFAULT/PRIMARY 直接返回 not-applicable admission。
- 使用 Prompt 三字段计算保守输入摘要，调用既有 `BudgetGuard.check(...)` 获得同一 token 估算 owner。
- 使用 `OrganizationQuotaPolicy.checkAndReserve(..., strict=true)` 在 caller thread 预留独立 quota。
- missing/exhausted 返回 denied admission，不提交 Future。
- 不调用 ModelGateway、Provider、CircuitBreaker 或 Trace。
- 不记录 Prompt 原文；admission 只保留稳定 code、单位数与 sourceUrls。
- executor submit rejection 时按 reservedUnits 释放；denied/not-applicable 不释放。

### 5.7 Fallback reason 单一 mapper

新增无状态 Spring component `OrchestratorFallbackReasonMapper`，由 Service 主 constructor 显式注入；一参数兼容 constructor 可以创建同一无状态实现，但不得在 Service 内复制映射 switch：

```text
LLM_TIMEOUT -> LLM_TIMEOUT
LLM_ERROR -> LLM_ERROR
PARSE_ERROR + final issue -> PARSE_ERROR:<ISSUE_CODE>
policy rejection -> POLICY_REJECTED
```

约束：

- 只读取 typed enum/issue code。
- 不拼 exception message、fieldName、URL 或 Provider response。
- issue 使用最终 attempt 的第一条稳定 code，顺序复用 Parser 输出。
- providerErrorCode 保留在 outcome failure，不写入高基数 fallbackReason。

---

## 6. 三种模式状态机

### 6.1 RULE_ONLY

```text
normalize context
  -> Rule Brain exactly once
  -> preserve native RULE_ONLY/LEGACY_ADAPTER origin
  -> outcome.decisions=rule decisions
  -> no LLM / no quota / no shadow
```

兼容要求：当前 `OrchestrationDecisionServiceTest` 的规则输出字段必须逐项不变。

### 6.2 LLM_PRIMARY success

```text
normalize context
  -> with purpose ORCHESTRATOR_PRIMARY
  -> LLM Brain exactly once
  -> decisions remain LLM_PRIMARY
  -> Rule Brain zero interactions
  -> outcome.llmFailure=null
```

Task 06 不在此处调用 Policy。

### 6.3 LLM_PRIMARY failure + fallback enabled

```text
LLM Brain throws typed failure
  -> Rule Brain exactly once
  -> copy every rule decision
  -> origin=RULE_FALLBACK
  -> fallback metadata from typed failure
  -> outcome.decisions=fallback copies
  -> outcome.llmFailure=original typed failure
```

禁止返回 Rule Brain 原对象后原地修改，避免污染 mock/cache/并发调用。

### 6.4 LLM_PRIMARY failure + fallback disabled

```text
decideWithOutcome(...)
  -> decisions=[]
  -> llmFailure=typed failure

legacy decide(...)
  -> rethrow LlmOrchestratorDecisionException
```

禁止返回空列表让旧 caller 把模型失败误判成“没有候选”。

### 6.5 LLM_SHADOW disabled 或 budget skipped

```text
Rule Brain exactly once -> final decisions
shadow.enabled=false
  -> LLM Brain zero interactions
  -> shadowDecisions=[]
  -> shadowExecution.requested=true/executed=false
  -> final decisions unchanged

quota missing/exhausted
  -> LLM Brain 只构建 Prompt
  -> ModelInvoker caller thread 的 gate 拒绝
  -> Future/executor zero submit
  -> ModelGateway zero interactions
  -> Provider zero interactions
  -> shadowDecisions=[]
  -> shadowExecution.requested=true/executed=false
  -> final decisions unchanged
```

### 6.6 LLM_SHADOW success

```text
Rule Brain -> final decisions
LLM Brain -> copy candidates as LLM_SHADOW
  -> metadata.shadowExecuted=true
  -> metadata.shadowSkippedReason=null
  -> outcome.shadowDecisions only
```

Shadow decisions 不混入 `outcome.decisions`。

Coordinator 必须逐条使用 `toBuilder()` 创建新对象，同时复制并更新 metadata：

```text
decisionOrigin=LLM_SHADOW
decisionMetadata.shadowExecuted=true
decisionMetadata.shadowSkippedReason=null
decisionMetadata.fallbackUsed=false
decisionMetadata.fallbackReason=null
```

禁止原地调用 setter 修改 Task 05 Brain 返回的 `LLM_PRIMARY` decision 或其 metadata；同一个 Brain 还会服务 primary mode，原地修改会污染 mock、并发调用和后续审计。

### 6.7 LLM_SHADOW failure

```text
Rule Brain -> final decisions unchanged
LLM failure -> shadowExecution.failure
  -> no second Rule call
  -> no exception escapes compatibility decide(...)
```

Shadow failure 不能把规则主路径变成 `RULE_FALLBACK`，因为规则本来就是该模式主路径。

---

## 7. Fallback decision metadata 协议

### 7.1 从 Task 05 failure 构造

每条规则 decision 使用 `toBuilder()` 复制并覆盖：

```text
decisionOrigin=RULE_FALLBACK
decisionMetadata.temperature=properties.modelTemperature
decisionMetadata.promptHash=final attempt promptHash
decisionMetadata.llmResponseHash=final attempt responseHash
decisionMetadata.parseRetryCount=failure.parseRetryCount
decisionMetadata.fallbackUsed=true
decisionMetadata.fallbackReason=mapper.map(failure)
decisionMetadata.shadowExecuted=null
decisionMetadata.shadowSkippedReason=null
```

`modelName` 在 Task 05 failure 中没有稳定事实，保持 null，禁止从配置猜测。

### 7.2 Policy rejected fallback 入口

Task 06 提供：

```java
public OrchestrationDecisionOutcome fallbackAfterPolicyRejection(
        OrchestrationContext rawContext,
        OrchestrationDecision rejectedLlmDecision);
```

要求：

- rejected decision 必须是 `LLM_PRIMARY`。
- Rule Brain 每次方法调用至多一次。
- fallback copies 继承 rejected decision 的 model/hash/retry metadata。
- 覆盖 origin=`RULE_FALLBACK`、fallbackUsed=true、fallbackReason=`POLICY_REJECTED`。
- 不调用 LLM Brain 或 Policy。
- Task 07 负责确保同一决策周期只调用该入口一次，并重新评估 legacy policy。

### 7.3 Rule fields 必须保留

复制时保留：

```text
decisionId/taskId/triggerNodeName
decisionType/actionType/target/scope/reason/priority
human flags/confidence/suggestedQueries
inputRefs/sourceUrls/evidenceState
```

只覆盖 origin 与 metadata。

---

## 8. Shadow 独立预算与治理链

### 8.1 配置

```yaml
orchestration:
  decision:
    mode: RULE_ONLY
    fallback-to-rule: true
    model-temperature: 0.0
    llm-timeout-ms: 4000
    max-parse-retries: 1
    executor-threads: 2
    executor-queue-capacity: 16
    shadow:
      enabled: false
      isolated-budget-key: ORCHESTRATOR_SHADOW
      require-active-quota: true
```

不修改全局 `ai.temperature`、`ai.timeout-seconds` 或正式 `MODEL_DAILY_BUDGET`。

### 8.2 配额流

```text
Coordinator
  -> ModelInvocationContext purpose=ORCHESTRATOR_SHADOW
  -> LLM Brain 构建 Prompt
  -> ModelInvoker caller thread
  -> ShadowBudgetGate 调 BudgetGuard 做同源 token 估算
  -> OrganizationQuotaPolicy.checkAndReserve(... shadow key, strict=true)
       missing snapshot -> BLOCKED_QUOTA_NOT_CONFIGURED
       exhausted -> BLOCKED_QUOTA_EXCEEDED
       denied -> ModelInvocationException，Future zero submit
       allowed -> admission(reservedUnits)
  -> worker context organizationQuotaReserved=true
  -> executor.submit
  -> ModelGateway 跳过重复组织配额预留
  -> Provider invocation
```

`BudgetGuard.check(...)` 本身不持久化、不调用外部服务；Gate 只读取其中的 `estimatedInputTokens`。若全局单请求预算已拒绝，Gate 使用稳定 `SHADOW_REQUEST_BUDGET_REJECTED` 返回，不提交 executor。

### 8.3 OrganizationQuotaPolicy 兼容 overload

计划增加：

```java
public QuotaDecision checkAndReserve(
        String organizationKey,
        String quotaScope,
        String quotaKey,
        int requestedUnits,
        List<String> sourceUrls,
        boolean requireActiveQuota);
```

旧五参数方法原样委托 strict=false，保持正式链路“无 snapshot 先放行”的现状。

### 8.4 防止双重预留与 submit rejection 泄漏

ModelGateway 的组织配额逻辑增加：

```text
context.organizationQuotaReserved=true
  -> 不再调用 OrganizationQuotaPolicy.checkAndReserve

false
  -> 按 purpose/quotaKey/strict flag 执行原网关配额检查
```

Gate allowed 后若 `executor.submit(...)` 抛 `RejectedExecutionException`：

```text
ShadowBudgetGate.release(admission)
  -> OrganizationQuotaPolicy.releaseReservation(... reservedUnits ...)
  -> 再抛 ORCHESTRATOR_EXECUTOR_SATURATED/SHUTDOWN
```

释放失败不能吞掉原 executor error，但必须作为安全日志/测试事实暴露；日志禁止包含 Prompt 原文。

Future timeout/interrupted cancel 还要区分 worker 是否已启动：

```text
workerStarted=false + cancel success
  -> 调用 gate.release(admission)

workerStarted=true
  -> 不在 caller 提前释放，沿用已进入模型治理链的现有预留语义
```

Invoker 使用方法局部 `AtomicBoolean workerStarted`，禁止把该标记存为单例字段。submit rejection、queued timeout、caller interrupted 三条控制流必须互斥地至多调用一次 release；不得依赖 `OrganizationQuotaPolicy.releaseReservation` 重复调用后的数值夹取来掩盖双重释放。

### 8.5 GovernanceBlockException 到 typed failure

正常 shadow quota denial 已在 pre-submit gate 转成 `ModelInvocationException(code, false, ...)`。为了覆盖绕过 gate 的直接 ModelGateway 调用和并发防御路径，`OrchestrationDecisionModelInvoker` 的 `ExecutionException` 分支仍识别：

```java
if (cause instanceof GovernanceBlockException blocked) {
    String code = blocked.getDecision() == null
            ? "GOVERNANCE_BLOCKED"
            : blocked.getDecision().getDecisionCode();
    throw new ModelInvocationException(code, false, blocked);
}
```

Coordinator 只映射稳定 code：

```text
BLOCKED_QUOTA_NOT_CONFIGURED -> SHADOW_BUDGET_NOT_CONFIGURED
BLOCKED_QUOTA_EXCEEDED -> SHADOW_BUDGET_EXHAUSTED
SHADOW_REQUEST_BUDGET_REJECTED -> SHADOW_REQUEST_BUDGET_REJECTED
```

不匹配的治理错误按 shadow LLM failure 处理，不伪装成预算耗尽。

### 8.6 不提前扩展 AICallAudit schema

Task 06 不新增数据库列。独立 quota key 已由 quota snapshot 体现，shadow outcome 由 Task 08 写 trace。若 Task 08 需要在 AI 调用审计表直接查询 purpose，必须另立 migration，不在本任务顺手扩表。

---

## 9. Composition Root 与单一 RuleSet owner

新增 `OrchestrationDecisionConfiguration`：

```java
@Configuration
public class OrchestrationDecisionConfiguration {

    /**
     * 阶段二 DecisionPolicyRuleSet 的唯一 owner。
     * 未来改为 YAML/数据库配置时只替换本 Bean 的构造来源，禁止调用方另行 builder().build()。
     */
    @Bean
    public DecisionPolicyRuleSet orchestrationDecisionRuleSet() {
        return DecisionPolicyRuleSet.builder().build().normalized();
    }

    @Bean
    public LlmOrchestratorDecisionBrain llmOrchestratorDecisionBrain(
            OrchestrationDecisionPromptBuilder promptBuilder,
            OrchestrationDecisionModelInvoker modelInvoker,
            OrchestrationDecisionResponseParser parser,
            OrchestrationDecisionRetryPromptBuilder retryPromptBuilder,
            DecisionPolicyRuleSet ruleSet,
            OrchestratorDecisionProperties properties) {
        return new LlmOrchestratorDecisionBrain(
                promptBuilder, modelInvoker, parser, retryPromptBuilder, ruleSet, properties);
    }
}
```

约束：

- LLM Brain 类本身仍不加 stereotype。
- 不给任何 Brain 加 `@Primary`。
- Task 07 必须注入同一个 `DecisionPolicyRuleSet` bean。
- 未来 ruleSet 配置化只能修改该 Bean 的构造来源；不得在 Service、PromptBuilder、Policy 或 runtime 新建第二份默认 ruleSet。
- Service 单参数测试 constructor 保留，强制 `RULE_ONLY` 兼容语义。
- Spring 主 constructor 显式 `@Autowired`，持有 rule/llm/properties。

---

## 10. Service API 与兼容性

计划接口：

```java
public List<OrchestrationDecision> decide(OrchestrationContext rawContext);

public OrchestrationDecisionOutcome decideWithOutcome(OrchestrationContext rawContext);

public OrchestrationDecisionOutcome fallbackAfterPolicyRejection(
        OrchestrationContext rawContext,
        OrchestrationDecision rejectedLlmDecision);
```

兼容要求：

- `new OrchestrationDecisionService(ruleBrain)` 继续可编译，固定 RULE_ONLY。
- null context 的旧 `decide` 继续返回空列表且不调用 Brain。
- RULE_ONLY 下旧测试的 object identity 行为可放宽为值相等，但不能改业务字段；优先仍返回 Rule Brain 原不可变列表，减少回归面。
- `DynamicPlanAppender`、`DagExecutor` 本任务不改调用签名。
- Task 07 再迁移到 outcome API。

---

## 11. 并发、异常与原子性

### 11.1 无共享 attempt 状态

Service 不缓存上次 outcome/decisions/failure。所有模式状态为方法局部变量。

### 11.2 Context scope 必须 finally 清理

Coordinator 使用 `ModelInvocationContextHolder.withContext(...)` 包裹 LLM 调用，不手写 set 后漏 clear。Task 05 worker finally 继续清理 worker ThreadLocal，caller 原上下文不得被覆盖。

### 11.3 Rule fallback 原子性

- Rule Brain 抛出调用方异常时不返回部分 fallback。
- fallback copy 任一 null decision 视为内部契约错误，不生成伪 NO_ACTION。
- LLM failure 与 fallback decisions 在同一个 outcome 中同时可见。

### 11.4 Shadow 不改变主结果

无论 shadow success、timeout、parse error、quota skip，`outcome.decisions` 必须与单独 Rule Brain 输出业务字段一致。

### 11.5 不记录 raw 文本

Outcome、shadow fact、fallback metadata、日志和 exception 不保存 raw prompt/response。只保留 Task 05 hashes/issues/discarded URL。

---

## 12. 结构化执行计划

| 任务拆解步骤 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 固定 mode、properties、outcome、shadow fact 和 fallback mapper 契约 | 1.5-2 小时 | Task 01/05 类型稳定 |
| Task 2 | 建立唯一 ruleSet bean 与 LLM Brain composition root，保持 RULE_ONLY 启动兼容 | 1.5-2 小时 | Task 1 |
| Task 3 | 实现 pre-submit shadow gate、invocation purpose/quota/reserved 传播和 strict quota，证明预算拒绝不占 executor 或正式预算 | 2.5-3.5 小时 | Task 1-2、治理组件可用 |
| Task 4 | 将 Service 改成三模式 Coordinator，完成 shadow outcome | 2.5-3.5 小时 | Task 1-3 |
| Task 5 | 完成 LLM failure fallback、metadata 映射和 policy-rejected fallback 入口 | 2-3 小时 | Task 4 |
| Task 6 | 兼容回归、一次 clean package、实测记录和 Task 07/08 handoff 复核 | 1-1.5 小时 | Task 1-5 |

复杂度表示风险和验证范围，不是绝对耗时承诺。

---

## 13. 进度记录

当前阶段：Task 06 代码实现与验收已完成（6/6）

- [x] 信息采集：阶段二设计/主计划、Task 01-05 handoff、Service/runtime caller、治理配额与 Spring wiring 已核对
- [x] 数据分析：mode owner、typed outcome、同步 shadow、pre-submit strict quota、fallback metadata 与 Task 07/08 边界已固定
- [x] 报告撰写：Task 06 可执行计划已形成
- [x] 质检复核：代码块、类型/属性命名、文件边界、测试命令、owner 接缝与后续 handoff 已完成自审

- [x] Task 1：类型与配置契约，成功（15 tests）
- [x] Task 2：composition root 与 ruleSet owner，成功
- [x] Task 3：shadow quota 隔离，成功（32 tests）
- [x] Task 4：三模式 Coordinator，成功
- [x] Task 5：真正 Rule fallback 与 policy fallback 入口，成功（37 tests，含兼容回归）
- [x] Task 6：回归、打包与记录，成功（184 tests；clean package BUILD SUCCESS）

- 当前执行步骤：Task 6 验收记录收口
- 已完成步骤占比：计划 4/4（100%）；代码 6/6（100%）
- 当前测试：Task 06 分层验收、受控总回归与最终打包
- 测试结果：分层测试 15/42/34/33/91/10 tests 均通过；受控总回归 184 tests / 0 failures / 0 errors；clean package BUILD SUCCESS
- 剩余步骤：无；Task 07/08 按各自任务边界后续实施
- 步骤执行状态：Task 1-6 全部成功

每次暂停必须追加：

```markdown
### 停顿记录：YYYY-MM-DD HH:mm
- 当前阶段：
- 当前执行步骤：
- 已完成步骤：
- 已完成步骤占比：
- 当前测试：
- 测试结果：
- 暴露问题：
- 剩余步骤：
- 下一步：
- 步骤执行状态（成功/失败/待执行）：
```

---

## 14. Task 1：类型、配置与不变式

### Step 1：先写 mode/properties 红灯测试

扩展 `OrchestratorDecisionPropertiesTest`：

- [x] 默认 mode=`RULE_ONLY`。
- [x] 默认 fallbackToRule=true。
- [x] 默认 shadow.enabled=false。
- [x] 默认 isolatedBudgetKey=`ORCHESTRATOR_SHADOW`。
- [x] 空 budget key 启动失败。
- [x] Task 05 既有默认值与非法值测试不变。

新增 `OrchestratorDecisionModeTest`：

- [x] 三种模式完整且无第四个隐式模式。
- [x] 配置大小写/非法值按 Spring binding 规则处理。

### Step 2：Outcome/Shadow immutable contract

新增 `OrchestrationDecisionOutcomeTest`：

- [x] mode 必填。
- [x] decisions/shadowDecisions/sourceUrls 不可变。
- [x] shadowDecisions 只能含 `LLM_SHADOW`。
- [x] 主 decisions 禁止 `LLM_SHADOW`。
- [x] null list 归一为空。
- [x] outcome 合并 sourceUrls 且去重。
- [x] failure 可与 fallback decisions 同时存在。

新增 `OrchestrationShadowExecutionTest`：

- [x] skippedReason 与 executed=true 冲突时失败。
- [x] requested=false 与 executed=true 冲突时失败。
- [x] sourceUrls 不可变。
- [x] raw prompt/response 无字段可承载。

### Step 3：Fallback mapper

新增 `OrchestratorFallbackReasonMapperTest`：

- [x] timeout/error 映射稳定枚举名。
- [x] parse failure 使用最终 attempt 第一条 issue code。
- [x] 无 issue 时返回 `PARSE_ERROR`。
- [x] 不包含 fieldName、URL、provider message。
- [x] policy rejected 固定 `POLICY_REJECTED`。

局部命令：

```powershell
mvn -pl backend "-Dtest=OrchestratorDecisionModeTest,OrchestratorDecisionPropertiesTest,OrchestrationDecisionOutcomeTest,OrchestrationShadowExecutionTest,OrchestratorFallbackReasonMapperTest" test
```

---

## 15. Task 2：Composition Root 与 RuleSet 单一 owner

### Step 1：Spring wiring 红灯

新增 `OrchestrationDecisionConfigurationTest`，使用 `ApplicationContextRunner`：

- [x] 只有一个 `DecisionPolicyRuleSet` bean。
- [x] 只有一个命名 `LlmOrchestratorDecisionBrain` bean。
- [x] Brain 使用同一个 ruleSet bean identity。
- [x] Rule Brain 与 LLM Brain 同时存在时不需要 `@Primary`。
- [x] `OrchestrationDecisionService` 可显式注入两个 Brain。
- [x] 非法 properties context 启动失败。

### Step 2：实现配置类

新增：

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionConfiguration.java
```

显式创建 ruleSet 与 LLM Brain，不修改 LLM Brain stereotype。

### Step 3：Service constructor 兼容

- [x] Spring 主 constructor 注入 rule/llm/properties/mapper。
- [x] 一参数 constructor 保持现有测试和 DagExecutor convenience constructor 可编译。
- [x] 一参数 constructor 强制 RULE_ONLY，不能隐式 new LLM Brain。

局部命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationDecisionServiceTest,LlmOrchestratorDecisionBrainTest,RuleBasedOrchestratorDecisionBrainTest" test
```

---

## 16. Task 3：Shadow Quota 隔离

### Step 1：Invocation context contract

新增 `ModelInvocationContextHolderTest`：

- [x] 旧三参数 API 得到 DEFAULT purpose。
- [x] shadow scope 带 quota key 与 strict flag。
- [x] `organizationQuotaReserved` 默认 false，只有 pre-submit gate success 后 worker 为 true。
- [x] nested scope 恢复 caller 原上下文。
- [x] exception 后 clear。

扩展 `OrchestrationDecisionModelInvokerTest`：

- [x] worker 获得 purpose/quotaKey/strict flag。
- [x] denied admission 时 Future zero submit、executor active/queue 均为 0。
- [x] worker finally 清理扩展上下文。
- [x] caller 上下文不被覆盖。

### Step 2：OrganizationQuotaPolicy strict overload

扩展 `OrganizationQuotaPolicyTest`：

- [x] 旧 overload 无 snapshot 仍 `NO_ACTIVE_QUOTA` allow。
- [x] strict=true 无 snapshot 返回 `BLOCKED_QUOTA_NOT_CONFIGURED` deny。
- [x] strict=true 配额不足返回既有 `BLOCKED_QUOTA_EXCEEDED`。
- [x] strict=true allowed 只预留一次。
- [x] sourceUrls 保持可追溯。

### Step 3：Pre-submit ShadowBudgetGate

新增 `OrchestrationShadowBudgetGateTest`：

- [x] 使用 Prompt 三字段调用同一 `BudgetGuard` token 估算 owner。
- [x] missing snapshot 返回 `BLOCKED_QUOTA_NOT_CONFIGURED` denied admission。
- [x] exhausted 返回 `BLOCKED_QUOTA_EXCEEDED` denied admission。
- [x] BudgetGuard 单请求拒绝映射 `SHADOW_REQUEST_BUDGET_REJECTED`。
- [x] denied admission 不调用 ModelGateway、不提交 executor。
- [x] denied 路径使用 `assertTimeoutPreemptively` 证明不等待 4 秒 deadline。
- [x] allowed admission 保存 reservedUnits/sourceUrls，不保存 Prompt 原文。
- [x] submit rejection 精确释放一次 reservation。
- [x] queued Future 在 worker 启动前 cancel 精确释放一次 reservation。
- [x] worker 已启动后 caller cancel 不提前释放 reservation。
- [x] submit rejection/timeout/interrupted 控制流不会双重 release。
- [x] not-applicable purpose 不检查或预留 shadow quota。

### Step 4：ModelGateway quota key 与 reserved marker

扩展 `ModelGatewayTest`：

- [x] DEFAULT/PRIMARY 使用 `MODEL_DAILY_BUDGET`。
- [x] SHADOW 使用 `ORCHESTRATOR_SHADOW`。
- [x] shadow strict flag 传到 OrganizationQuotaPolicy。
- [x] `organizationQuotaReserved=true` 时不重复 checkAndReserve。
- [x] quota deny 时 Provider zero interactions。
- [x] quota deny 不记录 circuit failure。
- [x] audit 仍记录治理阻断。

### Step 5：Invoker 保留治理原因码

- [x] Gate denial 在 caller thread 转成稳定 ModelInvocationException。
- [x] `GovernanceBlockException` decision code 进入 typed providerErrorCode。
- [x] 不把治理 summary/message 写入最终 typed exception。
- [x] Provider 未调用时不伪造 response hash。

局部命令：

```powershell
mvn -pl backend "-Dtest=ModelInvocationContextHolderTest,OrchestrationDecisionModelInvokerTest,OrchestrationShadowBudgetGateTest,OrganizationQuotaPolicyTest,ModelGatewayTest" test
```

---

## 17. Task 4：三模式 Coordinator

新增 `OrchestrationDecisionServiceLlmModeTest`。

### Step 1：RULE_ONLY

- [x] null context 返回 empty outcome/empty legacy list。
- [x] context 只归一化一次。
- [x] Rule Brain exactly once。
- [x] LLM Brain zero interactions。
- [x] rule decision 业务字段与 origin 不被重写。
- [x] shadow not requested。

### Step 2：LLM_PRIMARY success

- [x] LLM Brain exactly once。
- [x] Rule Brain zero interactions。
- [x] final decisions 为 LLM_PRIMARY。
- [x] outcome failure=null。
- [x] invocation purpose=ORCHESTRATOR_PRIMARY。

### Step 3：LLM_SHADOW disabled

- [x] Rule Brain exactly once。
- [x] LLM Brain zero interactions。
- [x] final decisions 与 Rule output 一致。
- [x] requested=true/executed=false/skippedReason=SHADOW_DISABLED。

### Step 4：LLM_SHADOW success

- [x] Rule Brain 与 LLM Brain 各一次。
- [x] final decisions 只含规则主结果。
- [x] shadow decisions 全部复制为 LLM_SHADOW。
- [x] shadow metadata executed=true。
- [x] LLM_PRIMARY 原对象不被原地修改。
- [x] 每条 shadow copy 使用独立 metadata copy，不与 Brain 原 decision 共享可变 metadata。
- [x] invocation purpose/quota key 正确。

### Step 5：LLM_SHADOW failure/skip

- [x] timeout/error/parse failure 不影响规则结果。
- [x] 非预算 failure 标记 executed=true 并保留 typed failure。
- [x] quota missing/exhausted 标记 executed=false 和稳定 skippedReason。
- [x] Service 单测中 quota typed failure 只调用 LLM Brain 一次，规则主结果不变。
- [x] Gate/Invoker 联合测试证明真实 quota denied 路径 Future/executor/ModelGateway/Provider zero interactions，且不等待完整 `llm-timeout-ms`。
- [x] shadow failure 不调用第二次 Rule Brain。
- [x] legacy `decide(...)` 不抛 shadow failure。

局部命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceTest" test
```

---

## 18. Task 5：真正 Rule Fallback 与 Metadata

新增 `OrchestrationDecisionServiceFallbackTest`。

### Step 1：LLM failure fallback

- [x] timeout/error/parse exhausted 分别触发一次 Rule Brain。
- [x] fallback disabled 不调用 Rule Brain。
- [x] fallback disabled 的 legacy decide 重新抛 typed exception。
- [x] fallback decisions 全部为 RULE_FALLBACK。
- [x] Rule Brain 原对象不变。
- [x] sourceUrls/evidence/inputRefs 全部保留。

### Step 2：Metadata 映射

- [x] final attempt prompt/response hash 写入 fallback metadata。
- [x] parseRetryCount 保留。
- [x] fallbackUsed=true。
- [x] modelName 不猜测。
- [x] parser issue 映射稳定 reason。
- [x] full typed failure 仍在 outcome。
- [x] raw prompt/response/message 不进入 decision/outcome exception。

### Step 3：Policy rejected 显式入口

- [x] 只接受 LLM_PRIMARY rejected decision。
- [x] Rule Brain exactly once。
- [x] LLM Brain zero interactions。
- [x] 继承 rejected decision model/hash/retry metadata。
- [x] fallbackReason=POLICY_REJECTED。
- [x] 返回 outcome 供 Task 07 重新走 legacy policy。
- [x] 非 LLM_PRIMARY 输入 `IllegalArgumentException`。

局部命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceFallbackTest,OrchestratorFallbackReasonMapperTest,LlmOrchestratorDecisionFailureTest,OrchestratorDecisionMetadataTest" test
```

---

## 19. Task 6：兼容回归、打包与记录

### Step 1：边界扫描

- [x] Service 无 Policy/Executor/Trace/runtime import。
- [x] LLM Brain 仍无 Rule Brain 依赖和 stereotype。
- [x] Rule Brain 规则文件无业务逻辑修改。
- [x] Task 06 不修改 DynamicPlanAppender/DagExecutor 生产代码。
- [x] 全局 AI temperature/timeout 不变。
- [x] `STAGE2-RULE-001` 保持 OPEN。

### Step 2：兼容调用方

- [x] `OrchestrationDecisionServiceTest` 全绿。
- [x] `DynamicPlanAppenderTest` 全绿。
- [x] `DagExecutorTest` 相关构造器编译通过。
- [x] Collaboration smoke tests 的手工 constructor 可编译。
- [x] RULE_ONLY 是 application.yml 默认值。

### Step 3：分层回归

先运行受控测试，不在每层调用 clean。全部通过后只做一次：

```powershell
mvn -pl backend clean package -DskipTests
```

### Step 4：回写记录

- [x] 更新本文进度为 6/6。
- [x] 写实际测试数与命令。
- [x] 记录 Maven settings 既有警告。
- [x] 记录 shadow quota snapshot 的运维前置条件。
- [x] 明确 Task 07/08 未实施。

---

## 20. 文件边界

### 20.1 新增生产文件

```text
backend/src/main/java/cn/bugstack/competitoragent/llm/ModelInvocationPurpose.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionMode.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowExecution.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowBudgetAdmission.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowBudgetGate.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOutcome.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorFallbackReasonMapper.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionConfiguration.java
```

### 20.2 修改生产文件

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionProperties.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionModelInvoker.java
backend/src/main/java/cn/bugstack/competitoragent/llm/ModelInvocationContextHolder.java
backend/src/main/java/cn/bugstack/competitoragent/llm/ModelGateway.java
backend/src/main/java/cn/bugstack/competitoragent/governance/OrganizationQuotaPolicy.java
backend/src/main/java/cn/bugstack/competitoragent/governance/GovernanceDefaults.java
backend/src/main/resources/application.yml
```

`GovernanceDefaults` 只增加 `ORCHESTRATOR_SHADOW_BUDGET_KEY` 常量，默认字符串仍为 `ORCHESTRATOR_SHADOW`；正式 `MODEL_DAILY_BUDGET_KEY` 不变。

### 20.3 新增测试文件

```text
backend/src/test/java/cn/bugstack/competitoragent/llm/ModelInvocationContextHolderTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionModeTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOutcomeTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowExecutionTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowBudgetGateTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestratorFallbackReasonMapperTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionConfigurationTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceLlmModeTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceFallbackTest.java
```

### 20.4 修改测试文件

```text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionPropertiesTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionModelInvokerTest.java
backend/src/test/java/cn/bugstack/competitoragent/llm/ModelGatewayTest.java
backend/src/test/java/cn/bugstack/competitoragent/governance/OrganizationQuotaPolicyTest.java
```

### 20.5 原则上不修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/RuleBasedOrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/LlmOrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
backend/src/main/java/cn/bugstack/competitoragent/report/**
backend/src/main/java/cn/bugstack/competitoragent/conversation/**
frontend/**
```

若实现时发现必须修改上述文件，先证明 Task 06 无法通过兼容 API 完成，再更新本文 owner 边界。

---

## 21. 接缝检查表

| 接缝 | Task 06 必须锁定的语义 | 验证方式 |
| --- | --- | --- |
| Properties -> Service | RULE_ONLY 默认，shadow 默认关闭 | binding/properties test |
| Configuration -> LLM Brain | 同一 normalized ruleSet bean | context identity test |
| Service -> Rule Brain | 正常模式一次；shadow failure 不重复；fallback 至多一次 | interaction count |
| Service -> LLM Brain | RULE_ONLY/disabled zero interactions；budget denied 只构建 Prompt | mode test |
| Service -> Outcome | 主、影子、failure、sourceUrls 不丢失 | immutable contract test |
| LLM success -> Shadow | 复制为 LLM_SHADOW，不修改原对象 | identity/origin test |
| LLM failure -> Rule fallback | 复制为 RULE_FALLBACK，写 metadata | fallback test |
| Typed failure -> Reason | 只读 enum/issues，不读 message | mapper security test |
| Context -> ModelInvoker | purpose/quota key/strict/reserved flag 跨线程传播 | holder/invoker test |
| ModelInvoker -> Shadow Gate | quota deny 发生在 Future submit 前；submit reject 释放 reservation | gate/invoker test |
| ModelInvoker -> Gateway | reserved marker 防双扣；GovernanceBlock 原因码保留 | invoker/gateway test |
| Gateway -> Quota | shadow key 与正式 key 隔离，已预留时不重复扣减 | gateway argument test |
| Quota -> Provider | missing/exhausted 时 Provider zero interactions | quota/gateway test |
| Task 06 -> Policy | 不调用 Policy，success 仍只是 candidate | import/interaction scan |
| Task 06 -> Runtime | 保留 decide 兼容，不改 DAG | compile/regression |
| Outcome -> Task 08 | shadow/failure facts 足够持久化 | contract review |

任一接缝未通过，Task 06 不能标记完成。

---

## 22. 分层验收命令

### 22.1 类型与配置

```powershell
mvn -pl backend "-Dtest=OrchestratorDecisionModeTest,OrchestratorDecisionPropertiesTest,OrchestrationDecisionOutcomeTest,OrchestrationShadowExecutionTest,OrchestratorFallbackReasonMapperTest" test
```

### 22.2 Composition root

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationDecisionServiceTest,LlmOrchestratorDecisionBrainTest,RuleBasedOrchestratorDecisionBrainTest" test
```

### 22.3 Shadow quota

```powershell
mvn -pl backend "-Dtest=ModelInvocationContextHolderTest,OrchestrationDecisionModelInvokerTest,OrchestrationShadowBudgetGateTest,ModelGatewayTest,OrganizationQuotaPolicyTest" test
```

### 22.4 Modes 与 fallback

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationDecisionServiceTest" test
```

### 22.5 Task 01-05 兼容

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionPromptInjectionTest,OrchestrationDecisionResponseParserTest,OrchestrationDecisionFixtureContractTest,LlmOrchestratorDecisionFailureTest,LlmOrchestratorDecisionBrainTest" test
```

### 22.6 Runtime 构造兼容

```powershell
mvn -pl backend "-Dtest=DynamicPlanAppenderTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest" test
```

### 22.7 Task 06 受控总回归

```powershell
mvn -pl backend "-Dtest=OrchestratorDecisionModeTest,OrchestratorDecisionPropertiesTest,OrchestrationDecisionOutcomeTest,OrchestrationShadowExecutionTest,OrchestratorFallbackReasonMapperTest,OrchestrationDecisionConfigurationTest,ModelInvocationContextHolderTest,OrchestrationDecisionModelInvokerTest,OrchestrationShadowBudgetGateTest,ModelGatewayTest,OrganizationQuotaPolicyTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationDecisionServiceTest,OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionPromptInjectionTest,OrchestrationDecisionResponseParserTest,OrchestrationDecisionFixtureContractTest,LlmOrchestratorDecisionFailureTest,LlmOrchestratorDecisionBrainTest,DynamicPlanAppenderTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest" test
```

### 22.8 最终一次干净打包

日常执行不使用 clean；所有受控测试通过后只运行一次：

```powershell
mvn -pl backend clean package -DskipTests
```

Task 06 不运行真实模型、不启用真实 shadow、不执行 E2E。真实模式切换与一次性验收属于 Task 09。

---

## 23. 完成标准

- [x] `RULE_ONLY` 为默认模式且旧规则行为不变。
- [x] `LLM_PRIMARY` success 不调用 Rule Brain。
- [x] `LLM_PRIMARY` typed failure 根据配置执行至多一次 Rule fallback。
- [x] fallback disabled 不吞失败。
- [x] fallback decisions 全部为 RULE_FALLBACK。
- [x] fallback metadata 保留 hash/retry/failure reason，不猜 modelName。
- [x] `LLM_SHADOW` 最终 decisions 只包含规则主结果。
- [x] shadow candidates 全部为 LLM_SHADOW 且不进入主列表。
- [x] shadow copy 不原地修改 LLM_PRIMARY decision 或共享 metadata。
- [x] shadow disabled 不调用 LLM Brain。
- [x] shadow budget denied 只允许 LLM Brain 构建 Prompt，Future/executor/ModelGateway/Provider 零调用。
- [x] shadow budget denied 不等待完整 4 秒 deadline。
- [x] shadow admission 成功后不在 ModelGateway 重复预留。
- [x] executor submit rejection 会释放已预留 shadow quota。
- [x] queued Future 在 worker 启动前取消会释放已预留 shadow quota。
- [x] shadow timeout/error/parse failure 不影响规则主结果。
- [x] shadow failure 不触发第二次 Rule Brain。
- [x] typed outcome 可同时携带主 decisions、shadow decisions、failure 与 sourceUrls。
- [x] outcome/shadow fact 不保存 raw prompt/response。
- [x] Service 保留 legacy `decide(...)` 兼容入口。
- [x] Policy rejected fallback 入口只调用 Rule Brain，不调用 LLM/Policy。
- [x] Coordinator 不依赖 Policy、Executor、Trace 或 runtime。
- [x] LLM Brain 仍为非 Spring stereotype POJO，由 configuration 显式创建。
- [x] 全项目只有一个 normalized DecisionPolicyRuleSet bean owner。
- [x] Task 07 可注入同一 ruleSet 给 Policy。
- [x] invocation purpose/quota key/strict/reserved flag 传播到 worker。
- [x] shadow 使用 ORCHESTRATOR_SHADOW quota key。
- [x] primary/default 继续使用 MODEL_DAILY_BUDGET。
- [x] shadow 无 active quota 时安全跳过，不调用 Provider。
- [x] shadow quota exhausted 不调用 Provider、不触发 circuit failure。
- [x] OrganizationQuotaPolicy 旧 overload 行为不变。
- [x] GovernanceBlock 原因码安全进入 typed failure。
- [x] 全局 ai.temperature/timeout 不变。
- [x] Rule Brain、ActionMatrix、Parser、LLM Brain 回归通过。
- [x] DynamicPlanAppender/DagExecutor 构造兼容测试通过。
- [x] clean package 通过且只执行一次。
- [x] 所有新增业务逻辑、核心方法和复杂条件均有详细中文注释。
- [x] 所有新结构化事实包含或继承 sourceUrls，可追溯。
- [x] `STAGE2-RULE-001` 保持 OPEN。

---

## 24. 与后续任务的接口约束

### 24.1 交给 Task 07：Policy/Executor/Runtime

Task 07 必须：

1. runtime 从 `decide(...)` 迁移到 `decideWithOutcome(...)`。
2. 只评估/执行 `outcome.decisions()`，永不执行 shadowDecisions。
3. 注入 Task 06 同一 `DecisionPolicyRuleSet` bean。
4. 每条 LLM_PRIMARY decision 再走 Task 02 ActionMatrix/Policy。
5. Policy rejection 时每个决策周期至多调用一次 `fallbackAfterPolicyRejection(...)`。
6. fallback decisions 必须重新走 legacy policy，不能复用被拒绝的 LLM policy result。
7. timeout/failure 本身不能把 DAG 节点标成 FAILED。
8. confirmation、decision count 与 mutation gate 仍由 runtime owner 执行。

### 24.2 交给 Task 08：Trace/Report/Replay

Task 08 必须：

1. 持久化 outcome 主结果、shadow execution、shadow decisions 与 llmFailure typed facts。
2. shadow skipped 没有 decision 时也要形成可审计事件。
3. fallback trace 同时展示最终 RULE_FALLBACK 与原始 typed failure。
4. 只持久化 hashes/issues/discarded URL，不保存 raw prompt/response。
5. replay/report/export 只读事件，不重新调用 Coordinator/Brain/ModelGateway。
6. 复用 Task 01 Summary Projector 字段，不新增平行 origin/metadata。

### 24.3 交给 Task 09：真实模式验收

- 默认生产配置仍为 RULE_ONLY。
- 真实 shadow 前必须配置 active `ORCHESTRATOR_SHADOW` quota snapshot。
- 使用 Task 04 的 9 条 fixtures 做 rule/LLM pair 对比。
- 至少一次 LLM_PRIMARY 成功候选通过 Policy 并形成可审计 origin。
- 至少一次 timeout 或 parse failure 证明 Rule fallback。
- 不因真实模型措辞差异放宽 Parser 或 ActionMatrix。

---

## 25. 计划实测记录

```markdown
当前阶段：Task 06 代码实现与验收已完成（6/6）
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

计划基线命令：
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest,LlmOrchestratorDecisionBrainTest,OrchestratorDecisionPropertiesTest,ModelGatewayTest,OrganizationQuotaPolicyTest,RuleBasedOrchestratorDecisionBrainTest" test

计划基线结果：58 tests / 0 failures / 0 errors / BUILD SUCCESS

实现命令：依次执行本文 22.1-22.7 的增量定向测试命令；全部通过后仅执行一次 `mvn -pl backend clean package -DskipTests`。
实现测试结果：22.1=15、22.2=42、22.3=34、22.4=33、22.5=91、22.6=10，均为 0 failures / 0 errors；22.7 受控总回归 184 tests / 0 failures / 0 errors / BUILD SUCCESS；最终 clean package BUILD SUCCESS（55.007s）。
暴露问题：`STAGE2-RULE-001=OPEN`；Maven settings.xml 第 168 行仍有既有 malformed mirrors 标签警告；真实 shadow 前必须配置 active `ORCHESTRATOR_SHADOW` quota snapshot；Task 07 的 Policy/Executor/Runtime 接入与 Task 08 的 Trace/Report/Replay 均未实施。
```

### 实现验收记录：2026-07-14

- 已完成：`RULE_ONLY`、`LLM_SHADOW`、`LLM_PRIMARY` 三模式 Coordinator，以及 typed outcome、shadow execution 和真正的 Rule fallback。
- 已完成：shadow 使用独立 `ORCHESTRATOR_SHADOW` quota key，并在 Future 提交前执行严格预算准入；缺失或耗尽时不占 executor、不调用 ModelGateway/Provider。
- 已完成：ModelInvocation purpose/quota/strict/reserved 上下文跨线程传播，网关识别预留标记并避免重复扣减。
- 已完成：composition root 单点创建 normalized `DecisionPolicyRuleSet` 和非 stereotype `LlmOrchestratorDecisionBrain`。
- 已确认：默认 `RULE_ONLY`、`shadow.enabled=false`、全局 AI temperature/timeout、旧 `OrganizationQuotaPolicy` overload 和 runtime 构造行为均保持不变。
- 已确认：本次验收未调用真实模型、未启用真实 shadow、未执行 E2E；上述真实环境验收仍归 Task 09。

### 计划复核记录：2026-07-14

- 已确认：默认 `RULE_ONLY` 和 `shadow.enabled=false` 保证 Task 06 接入后默认生产行为不变。
- 已确认：Task 06 使用 typed outcome 保留主结果、shadow 与 failure 事实，但不提前持久化 Trace。
- 已修正：shadow quota missing/exhausted 在 LLM Brain 构建 Prompt 后、ModelInvoker 提交 Future 前同步阻断；executor/ModelGateway/Provider 均 zero-interaction，也不等待 4 秒 deadline。
- 已强化：shadow success 必须逐条复制 LLM_PRIMARY candidate 与 metadata，再覆盖为 LLM_SHADOW/shadowExecuted=true，禁止原地修改 Brain 原对象。
- 已强化：`orchestrationDecisionRuleSet` Bean 的中文注释明确其为唯一 owner，未来 YAML/数据库配置化只替换该 Bean 的构造来源。
- 已修正：早期 YAML `max-daily-tokens` 不落地，预算上限由 `OrganizationQuotaSnapshot.limitValue` 单点拥有，避免配置与数据库双事实源。
- 已确认：Service 提供 policy-rejected fallback 入口但不调用 Policy，Task 07 负责单周期最多一次回退和 legacy policy 重评估。
- 已确认：Task 06 不修改 Rule Brain、LLM Brain、Policy、Executor、Trace、DynamicPlanAppender、DagExecutor、Report、Conversation 或 frontend 生产逻辑。
- 基线结果：58 tests / 0 failures / 0 errors / BUILD SUCCESS；未执行 clean。
