# Task 07 Policy、Executor 与 Runtime 接入 Implementation Plan

> **For agentic workers:** 本文是可直接执行的 Task 07 计划。执行者必须复用 Task 01-06 已固定的 origin/metadata、LLM ActionMatrix、Rule Brain、typed outcome、shadow budget 和单次 Rule fallback；不得让 shadow decision 驱动 DAG，不得在 runtime 复制第二套 ActionMatrix，也不得提前实现 Task 08 的完整 outcome/trace/report/replay 投影。

**Goal:** 将 Task 06 的 `OrchestrationDecisionOutcome` 正式接入 Policy、Executor、`DynamicPlanAppender` 和 `DagExecutor`，保证主决策经过 origin-aware Policy 后才可能形成 mutation；LLM Policy rejection 每个决策周期最多触发一次 Rule fallback；确认门、总自动决策上限和每 section 动态分支上限在 mutation 前真实生效。

**Architecture:** 新增独立的 `OrchestrationRuntimeDecisionService` 作为 Task 07 runtime coordinator，统一拥有 `outcome -> policy -> optional fallback -> runtime guard -> mutation` 状态机；`OrchestrationDecisionService` 继续只负责 Brain/mode/fallback 构造，不注入 Policy 或 Executor。新增 `OrchestrationRuntimeStateService` 从最近 checkpoint 和当前计划读取已持久化计数，并显式返回 checkpoint 的 `ABSENT/RESTORED/UNREADABLE` 状态，避免两个 runtime caller 各自猜测 `currentDecisionCount`，也避免损坏 checkpoint 重新打开自动补图额度。两个调用方只记录 attempt 并消费 batch 中的 final decisions，永远不评估或执行 `shadowDecisions`。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Spring Boot Test, existing `OrchestrationDecisionService`, `DecisionPolicyService`, `DecisionExecutorAdapter`, `OrchestrationTraceService`, `TaskWorkflowEventRepository`, `DynamicPlanAppender`, `DagExecutor`.

---

## 1. 任务定位与依赖顺序

阶段二数字任务依赖顺序：

~~~text
Task 01：Decision Origin 与 Metadata Contract
  -> Task 02：LLM ActionMatrix 与 origin-aware Policy
  -> Task 03：Rule Brain 抽取
  -> Task 04：Prompt、Parser 与人工 Fixtures
  -> Task 05：LLM Brain、总 Timeout、Parse Retry 与 Typed Failure
  -> Task 06：Service Modes、Shadow Budget、真正 Rule Fallback
  -> Task 07：Policy、Executor、Runtime 接入
  -> Task 08：Trace、Report、Replay
  -> Task 09：阶段二验收
~~~

主计划已固定本任务文件名：

~~~text
task-07-policy-executor-runtime-integration.md
~~~

Task 07 解决的根因：

1. `DynamicPlanAppender` 和 `DagExecutor` 仍调用兼容入口 `decide(...)`，Task 06 的 typed outcome、shadow/failure 事实没有进入 runtime。
2. `DynamicPlanAppender` 每次运行时自行 `DecisionPolicyRuleSet.builder().build()`，没有消费 Task 06 composition root 的唯一 normalized ruleSet bean。
3. `DagExecutor.applyAgentSuggestionGate(...)` 当前不调用 Policy/Executor，raw `WAIT_FOR_HUMAN` decision 可以直接把节点改成 `WAITING_INTERVENTION`。
4. LLM candidate 被 Policy 拒绝后，runtime 尚未调用 `fallbackAfterPolicyRejection(...)`，因此 Task 06 的真正单次 Rule fallback 接口没有生产 owner。
5. `OrchestrationContext.currentDecisionCount` 在两个生产调用点均未填充，实际长期按 0 进入 Prompt 与 Policy。
6. `DecisionPolicyRuleSet.maxDynamicBranchesPerSection` 已存在，但没有任何生产代码读取，不能阻止同一章节重复补图。
7. `DecisionPolicyResult.requiresConfirmation=true` 目前不会阻止 Adapter 生成自动 mutation，确认要求只是审计字段，不是真实暂停门。
8. `DecisionPolicyService` 当前在达到 `maxAutoDecisions` 后阻断所有动作，连 `NO_ACTION` 和 `MANUAL_ONLY` 也会被拒绝，与 Task 04 的 `citation-limit-reached` 人工标签冲突。现有 18 条 `DecisionPolicyServiceTest` 中只有 `shouldBlockWhenAutoDecisionLimitIsReached` 覆盖次数上限，且使用自动动作 `APPEND_DYNAMIC_BRANCH`；不存在“上限后必须拒绝 `NO_ACTION/MANUAL_ONLY`”的已验收断言。
9. 完整 Spring smoke 暴露 Task 06 遗留装配错误：`OrchestrationShadowBudgetGate` 有多个构造器，但生产构造器未显式标记注入，Spring 尝试寻找无参构造器并启动失败。

---

## 2. Task 01-06 输入契约

### 2.1 Task 01：origin/metadata 不新增平行字段

Task 07 只消费现有：

~~~text
LLM_PRIMARY
LLM_SHADOW
RULE_ONLY
RULE_FALLBACK
LEGACY_ADAPTER
~~~

以及现有 `OrchestratorDecisionMetadata`。禁止在 runtime result 中复制 modelName、hash、fallbackReason 等平行字段；runtime fact 必须引用完整 `OrchestrationDecision`。

### 2.2 Task 02：Policy 是动作合法性的唯一 owner

Task 07 不重新解释：

~~~text
decisionType/actionType 合法组合
targetNode
affectedScope
LLM rewrite sourceUrls 红线
legacy normalizedAction
riskRules
~~~

Task 07 只消费：

~~~java
DecisionPolicyResult evaluate(
        OrchestrationDecision decision,
        DecisionPolicyRuleSet ruleSet,
        int currentDecisionCount,
        String taskStatus,
        String triggerNodeStatus);
~~~

### 2.3 Task 06：typed outcome 与单次 fallback

Task 07 必须调用：

~~~java
OrchestrationDecisionOutcome decideWithOutcome(OrchestrationContext context);

OrchestrationDecisionOutcome fallbackAfterPolicyRejection(
        OrchestrationContext context,
        OrchestrationDecision rejectedLlmDecision);
~~~

稳定约束：

- 只评估和执行 `outcome.decisions()`。
- `outcome.shadowDecisions()` 永不进入 Policy、Executor 或 DAG。
- LLM 调用失败后 Task 06 已生成 `RULE_FALLBACK` 时，Task 07 不再调用第二次 fallback。
- 只有 `LLM_PRIMARY` candidate 的 `policyResult.allowed=false` 才能触发 policy-rejected fallback。
- 同一 runtime decision cycle 至多调用一次 `fallbackAfterPolicyRejection(...)`。
- fallback decisions 必须重新走 legacy Policy，禁止复用原 LLM policy result。

---

## 3. 基线实测与 Task 0 前置修复

### 3.1 已执行基线命令

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest,OrchestrationRuntimeFeedbackSmokeTest" test
~~~

实测：

~~~text
Selected tests: 77
Passed: 75
Errors: 2
Only failing class: OrchestrationRuntimeFeedbackSmokeTest
~~~

根因：

~~~text
BeanCreationException: OrchestrationShadowBudgetGate
NoSuchMethodException: OrchestrationShadowBudgetGate.<init>()
~~~

Task 0 必须先以最小改动修复 Spring constructor selection：

~~~java
@Autowired
public OrchestrationShadowBudgetGate(
        BudgetGuard budgetGuard,
        OrganizationQuotaPolicy organizationQuotaPolicy,
        OrchestratorDecisionProperties properties) {
    this(budgetGuard, organizationQuotaPolicy, properties, true);
}
~~~

该修复属于 Task 06 composition root 的迟到回归，不允许借机修改 shadow 预算语义。修复后必须先单独运行 `OrchestrationRuntimeFeedbackSmokeTest`，再开始 Task 07 runtime 类型实现。

---

## 4. 目标

1. 两个生产 runtime caller 从 `decide(...)` 迁移到统一 runtime coordinator，间接使用 `decideWithOutcome(...)`。
2. 只对主 `decisions` 执行 Policy；shadow 永不参与执行。
3. Task 06 唯一 normalized `DecisionPolicyRuleSet` bean 同时供 LLM Prompt 和最终 Policy 使用。
4. LLM_PRIMARY Policy rejection 每周期最多一次 Rule fallback。
5. 原 LLM rejection attempt 与最终 Rule fallback attempt 都保留为 typed runtime fact。
6. fallback decision 使用 `RULE_FALLBACK` 和 legacy policy contract 重新评估。
7. Policy blocked、runtime branch limit、confirmation required 三类状态明确区分。
8. `requiresConfirmation=true` 在任何自动 mutation 前转成真实 `MARK_WAITING_INTERVENTION`。
9. `currentDecisionCount` 从最近 checkpoint 恢复，不再固定为 0；checkpoint 不可读时按总自动额度已耗尽处理。
10. `maxDynamicBranchesPerSection` 在 APPEND mutation 前真实生效。
11. 达到 `maxAutoDecisions` 后仍允许 `NO_ACTION` 和 `MANUAL_ONLY`，只阻断自动图变更。
12. AgentSuggestion gate 只有 Policy allowed 的最终 manual mutation 才能把节点改成 `WAITING_INTERVENTION`。
13. LLM timeout/error/parse failure 自身不能把 DAG 节点误标 FAILED。
14. 所有新增结构化 runtime fact 强制包含 `sourceUrls`。

---

## 5. 非目标

Task 07 明确不做：

- 不修改 Prompt、Parser、LLM Brain、ModelGateway、shadow quota 或 Provider retry。
- 不让 `LLM_SHADOW` 进入 Policy/Executor。
- 不把 Policy/Executor 注入 `OrchestrationDecisionService`。
- 不新增第二套 ActionMatrix、legacy mapping 或默认 ruleSet owner。
- 不实现 Task 08 的完整 outcome、shadow、typed failure 持久化。
- 不修改 report/export/replay/conversation DTO 或前端展示。
- 不新增数据库表或 migration；runtime state 继续复用 checkpoint event payload。
- 不实现新的 REST/UI 确认接口；Task 07 只负责 fail-closed 暂停，后续人工处理复用现有 WAITING_INTERVENTION/recovery 边界。
- 不运行真实 LLM、真实 shadow 或 E2E；真实模式验收属于 Task 09。
- 不顺手解决 `STAGE2-RULE-001`，继续保持 OPEN。

---

## 6. 方案选择

### 6.1 采用：独立 Runtime Coordinator

~~~text
DynamicPlanAppender / DagExecutor
  -> OrchestrationRuntimeDecisionService
       -> OrchestrationRuntimeStateService
       -> OrchestrationDecisionService.decideWithOutcome
       -> DecisionPolicyService
       -> optional fallbackAfterPolicyRejection
       -> runtime guards
       -> DecisionExecutorAdapter
  -> caller records attempts
  -> caller consumes final mutation
~~~

选择原因：

- 保持 Task 06 Service 的单一职责。
- Policy rejection fallback 只有一个 runtime owner。
- 两个 caller 不复制 mode/origin/fallback 判断。
- Task 08 可直接消费 runtime batch，而不需要从 caller 控制流反推。

### 6.2 拒绝：把 Policy 塞回 OrchestrationDecisionService

该方案会破坏 Task 06 已验收边界，使 Service 同时依赖 Policy、Executor 和 runtime state，并让 Policy rejection fallback 形成自调用循环。

### 6.3 拒绝：两个 caller 各自实现 fallback

该方案无法保证单周期最多一次 fallback，且会复制 ruleSet、计数和 confirmation 逻辑。

---

## 7. 核心类型契约

### 7.1 OrchestrationRuntimeState

新增不可变事实：

~~~java
public record OrchestrationRuntimeState(
        int currentDecisionCount,
        Map<String, Integer> dynamicBranchCountsBySection,
        Long currentPlanVersionId,
        int nextPlanVersion,
        CheckpointStateStatus checkpointStateStatus,
        List<String> sourceUrls
) {
    public enum CheckpointStateStatus {
        ABSENT,
        RESTORED,
        UNREADABLE
    }
}
~~~

不变式：

- `ABSENT` 的 count 必须为 0；`RESTORED` 的 count 小于 0 属于语义不可用，内部数值可归一为 0，但状态必须收紧为 `UNREADABLE`，使 Coordinator 仍按额度耗尽处理。
- section key 使用 trim 后的小写稳定键。
- 空 `targetSection` 统一使用 `__unscoped__`，禁止用空值绕过分支上限。
- map 和 list 使用不可变副本。
- 没有 checkpoint 是合法初始态：`checkpointStateStatus=ABSENT`、count=0、map empty，不得当作读取故障。
- checkpoint 可正常解析时为 `RESTORED`；旧 payload 即使没有 section map 也属于可读状态，必须保留已存的 `decisionCount/sourceUrls`，只把 map 归一为空。
- 最新 checkpoint 存在但 JSON 损坏、计数语义非法，或 checkpoint repository/反序列化访问抛异常时为 `UNREADABLE`；此时 section map 可降级为空，但 count 不得被解释为 0 并重新开放自动额度。
- `OrchestrationRuntimeStateService` 不注入 `DecisionPolicyRuleSet`。Runtime Coordinator 发现 `UNREADABLE` 后，使用唯一 ruleSet 的 `maxAutoDecisions` 作为 effective count 写入 Prompt 和 Policy，使自动 mutation fail-closed；`NO_ACTION/MANUAL_ONLY` 仍可继续，且读取故障不得让 RULE_ONLY 调用链抛异常。
- `UNREADABLE` 必须记录低敏 warning，不输出完整 payload；状态字段进入 typed batch，便于 Task 08 审计为何自动额度被视为耗尽。
- runtime state 的 `sourceUrls` 只从 checkpoint 恢复；runtime batch 再与当前 context/outcome 的来源合并去重，禁止从不含来源字段的 TaskPlan 猜测证据。

### 7.2 OrchestrationRuntimeDecision

新增单次评估事实：

~~~java
public record OrchestrationRuntimeDecision(
        OrchestrationDecision decision,
        DecisionPolicyResult policyResult,
        DynamicPlanMutation mutation,
        boolean fallbackAttempt,
        String runtimeStatus,
        List<String> sourceUrls
) {
}
~~~

`runtimeStatus` 只允许稳定低基数值：

~~~text
READY
POLICY_REJECTED
CONFIRMATION_REQUIRED
DYNAMIC_BRANCH_LIMIT_REACHED
NO_MUTATION
~~~

不变式：

- decision/policyResult/mutation 必填。
- policy rejected 必须对应 `NO_MUTATION`。
- confirmation required 必须对应 `MARK_WAITING_INTERVENTION`。
- branch limit reached 必须对应 `NO_MUTATION`。
- `sourceUrls` 合并 decision、policy、mutation 来源。
- 不保存 raw prompt、raw response 或 exception message。

### 7.3 OrchestrationRuntimeDecisionBatch

新增一个决策周期事实：

~~~java
public record OrchestrationRuntimeDecisionBatch(
        OrchestrationDecisionOutcome coordinatorOutcome,
        OrchestrationRuntimeState runtimeState,
        List<OrchestrationRuntimeDecision> attempts,
        List<OrchestrationRuntimeDecision> finalDecisions,
        boolean policyFallbackUsed,
        List<String> sourceUrls
) {
}
~~~

语义：

- `attempts` 保留原始 LLM rejection 与 fallback attempts。
- `finalDecisions` 是 caller 唯一允许消费的列表。
- 没有 Policy fallback 时 finalDecisions 与 attempts 值相等。
- 发生 Policy fallback 时原 LLM attempts 不进入 finalDecisions。
- shadow decisions 不得出现在任一 runtime decision 列表。
- Task 08 可持久化完整 batch，但 Task 07 只沿用现有逐条 `recordDecision`。

### 7.4 OrchestratorCheckpoint 扩展

在现有 checkpoint 中新增：

~~~java
@Builder.Default
private Map<String, Integer> dynamicBranchCountsBySection = Map.of();
~~~

保持现有 `sourceUrls` 字段。历史 checkpoint 的兼容契约必须由显式测试固定：payload 含 `checkpoint.decisionCount`、不含 `dynamicBranchCountsBySection` 时反序列化成功，原 decision count 和 `sourceUrls` 保持不变，section map 为空，runtime state 标记为 `RESTORED` 而不是 `UNREADABLE`。

---

## 8. Runtime State 单一 owner

新增 `OrchestrationRuntimeStateService`：

~~~java
@Service
public class OrchestrationRuntimeStateService {

    public OrchestrationRuntimeState load(Long taskId) {
        // 从最新 checkpoint 恢复已成功执行的自动决策次数和 section 分支计数。
        // 必须区分无 checkpoint、历史 checkpoint 可读和 checkpoint 不可读，禁止把损坏记录当成首次执行。
        // 当前计划 ID/版本从 active TaskPlan 读取，避免 caller 根据节点 ID 猜 planVersion。
    }

    public OrchestrationRuntimeState afterSuccessfulBranch(
            OrchestrationRuntimeState current,
            OrchestrationDecision decision) {
        // 只有 APPEND_NODES 真正落库成功后才递增；Policy rejected、confirmation 和 NO_MUTATION 均不计数。
    }
}
~~~

依赖：

~~~text
TaskWorkflowEventRepository
TaskPlanRepository
ObjectMapper
~~~

禁止：

- 根据 `task.currentPlanVersion - 1` 猜决策次数。
- 统计所有 `ORCHESTRATION_DECISION_RECORDED` 事件；被拒绝 attempt 不是已执行自动决策。
- 使用 JVM 单例 map 保存计数；中断恢复后必须仍然正确。
- 在 PromptBuilder、Policy、DynamicPlanAppender 各自解析 checkpoint。

---

## 9. Runtime Coordinator 状态机

计划接口：

~~~java
public OrchestrationRuntimeDecisionBatch decide(
        OrchestrationContext rawContext,
        String taskStatus,
        String triggerNodeStatus);
~~~

稳定流程：

~~~text
load persisted runtime state
  -> resolve effectiveDecisionCount from checkpoint state + normalized ruleSet
  -> enrich context.currentDecisionCount/taskStatus
  -> OrchestrationDecisionService.decideWithOutcome
  -> evaluate outcome.decisions only
  -> any LLM_PRIMARY policy rejected?
       no  -> apply runtime guards -> adapter -> final
       yes -> choose first rejection in decision order
            -> fallbackAfterPolicyRejection exactly once
            -> evaluate RULE_FALLBACK decisions with legacy policy
            -> never fallback again
  -> return immutable batch
~~~

关键实现骨架：

~~~java
public OrchestrationRuntimeDecisionBatch decide(
        OrchestrationContext rawContext,
        String taskStatus,
        String triggerNodeStatus) {
    OrchestrationRuntimeState state = runtimeStateService.load(rawContext.getTaskId());
    // checkpoint 不可读代表历史自动执行次数未知，按额度已耗尽处理，防止损坏事件重新打开补图预算。
    int effectiveDecisionCount = state.checkpointStateStatus()
            == OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE
            ? ruleSet.getMaxAutoDecisions()
            : state.currentDecisionCount();
    OrchestrationContext context = rawContext.normalized().toBuilder()
            .taskStatus(taskStatus)
            .currentDecisionCount(effectiveDecisionCount)
            .build()
            .normalized();

    OrchestrationDecisionOutcome outcome = decisionService.decideWithOutcome(context);
    List<OrchestrationRuntimeDecision> primaryAttempts =
            evaluate(outcome.decisions(), context, state, taskStatus, triggerNodeStatus, false);

    OrchestrationRuntimeDecision rejectedLlm = firstRejectedLlm(primaryAttempts);
    if (rejectedLlm == null) {
        return batch(outcome, state, primaryAttempts, primaryAttempts, false);
    }

    // Policy rejection fallback 是整个决策周期的一次性替换，原 LLM mutation 永不进入 finalDecisions。
    OrchestrationDecisionOutcome fallbackOutcome =
            decisionService.fallbackAfterPolicyRejection(context, rejectedLlm.decision());
    List<OrchestrationRuntimeDecision> fallbackAttempts =
            evaluate(fallbackOutcome.decisions(), context, state, taskStatus, triggerNodeStatus, true);
    return batch(outcome, state, concat(primaryAttempts, fallbackAttempts), fallbackAttempts, true);
}
~~~

复杂条件：

- 先评估全部 LLM candidates，再决定 fallback；禁止先执行第一个 allowed mutation 后才发现后续 candidate 被拒绝。
- 多个 LLM candidates 同时被拒绝时，以输入顺序第一条作为 fallback metadata 来源。
- `RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` Policy rejection 不触发二次 fallback。
- Task 06 已因 LLM failure 返回 `RULE_FALLBACK` 时，`policyFallbackUsed=false`。
- 空 decisions 返回空 batch，不构造伪造的 WAIT decision。

---

## 10. Runtime Guards

### 10.1 maxAutoDecisions

`DecisionPolicyService` 将总次数判断收窄为自动 mutation：

~~~java
boolean automaticMutation = Set.of(
        "CREATE_SUPPLEMENT_BRANCH",
        "CREATE_RERUN_BRANCH",
        "CREATE_REWRITE_BRANCH"
).contains(normalizedAction);

if (automaticMutation && currentDecisionCount >= rules.getMaxAutoDecisions()) {
    blockedReasons.add("自动编排次数已达到上限："
            + currentDecisionCount + "/" + rules.getMaxAutoDecisions());
}
~~~

`NO_ACTION` 和 `MANUAL_ONLY` 达到上限后仍可 allowed，使模型能够安全选择停止或转人工。

这是对 Task 02 过宽次数判断的定向修正，不是任意放宽已验收 Policy 契约。实施前已核对 `DecisionPolicyServiceTest` 的 18 条测试：唯一的次数上限用例 `shouldBlockWhenAutoDecisionLimitIsReached` 使用 `APPEND_DYNAMIC_BRANCH -> CREATE_SUPPLEMENT_BRANCH`，其旧断言必须原样保留并继续通过。实施时还必须逐条 review 其余 17 条，分类规则如下：

- 与根因 8 无关的旧断言属于真实回归护栏，禁止为通过新实现而修改。
- 若后续代码变化暴露出旧断言确实依赖“上限阻断所有动作”，只能修改该精确断言，并在本计划实测记录中写明测试名、旧/新语义及“修正 Task 02 过严行为”的原因。
- 新增“上限后 `NO_ACTION/MANUAL_ONLY` 仍 allowed”用例不能替代既有自动动作上限用例。
- 自定义 `allowedDynamicActions`、sourceUrls/evidenceState、query、task/node status、risk/confirmation 等现有规则保持不变。

### 10.2 requiresConfirmation

`DecisionExecutorAdapter.toMutation(...)` 在任何自动 action 分支之前处理确认：

~~~java
if (policyResult.isRequiresConfirmation()) {
    return DynamicPlanMutation.builder()
            .mutationId("dpm-" + decision.getDecisionId())
            .decisionId(decision.getDecisionId())
            .mutationType("MARK_WAITING_INTERVENTION")
            .branchReason("POLICY_CONFIRMATION_REQUIRED")
            .dynamicAction("MANUAL_ONLY")
            .runtimeCommand("AWAIT_CONFIRMATION")
            .sourceUrls(policyResult.getSourceUrls())
            .evidenceState(policyResult.getEvidenceState())
            .build()
            .normalized();
}
~~~

确认未完成时绝不生成 `APPEND_NODES` 或未来的 `RERUN_FROM_NODE`。

### 10.3 maxDynamicBranchesPerSection

Runtime Coordinator 只在 Policy allowed 且 Adapter 原本会生成 `APPEND_NODES` 时检查：

~~~text
currentCount(sectionKey) >= ruleSet.maxDynamicBranchesPerSection
  -> mutationType=NO_MUTATION
  -> branchReason=DYNAMIC_BRANCH_LIMIT_REACHED
  -> runtimeStatus=DYNAMIC_BRANCH_LIMIT_REACHED
~~~

增加 Adapter 显式入口：

~~~java
public DynamicPlanMutation toNoMutation(
        OrchestrationDecision decision,
        DecisionPolicyResult policyResult,
        String branchReason);
~~~

禁止 Runtime Coordinator 自己复制 `DynamicPlanMutation.builder()` 的 source/evidence 归一化逻辑。

---

## 11. DynamicPlanAppender 接入

生产依赖从：

~~~text
OrchestrationDecisionService
DecisionPolicyService
DecisionExecutorAdapter
OrchestrationTraceService
~~~

收敛为：

~~~text
OrchestrationRuntimeDecisionService
OrchestrationTraceService
~~~

执行流程：

1. 保留终审失败触发条件、parent plan/current plan version 校验。
2. 构造 `OrchestrationContext` 时写入真实 `taskStatus`，不写固定 count。
3. 调用 runtime coordinator。
4. 对 `batch.attempts()` 逐条调用现有 `recordDecision`。
5. 只遍历 `batch.finalDecisions()`。
6. 只有 `policy.allowed=true` 且 mutation=`APPEND_NODES` 才创建动态计划。
7. mutation=`MARK_WAITING_INTERVENTION` 时 fail-closed 标记终审节点等待人工，不创建动态计划。
8. mutation=`NO_MUTATION` 时返回未追加，不修改 task 当前计划版本。
9. 动态节点成功落库和 task plan 切换完成后，才记录 checkpoint 并递增 runtime state。
10. stale parent plan、空 materialized nodes 或持久化失败不得递增计数。

Task 07 不改变 `DynamicTaskGraphService` 和 `CompensationGraphAssembler` 的业务模板；它们继续只消费已校验 mutation。

---

## 12. DagExecutor AgentSuggestion Gate 接入

`DagExecutor` 用同一个 `OrchestrationRuntimeDecisionService` 替换直接依赖 `OrchestrationDecisionService`。

流程：

~~~text
AgentResult SUCCESS
  -> assemble AgentSuggestion
  -> build context
  -> runtimeDecisionService.decide
  -> trace all attempts
  -> inspect final mutations only
       MARK_WAITING_INTERVENTION -> WAITING_INTERVENTION
       APPEND_NODES/NO_MUTATION  -> 当前 gate 不直接改图
~~~

边界：

- AgentSuggestion gate 仍只拥有“是否暂停当前节点”，不在 worker thread 直接追加 DAG。
- APPEND_NODES 的真实图变更仍由 `DynamicPlanAppender` owner 执行；Task 07 不在 DagExecutor 复制补图代码。
- raw `WAIT_FOR_HUMAN` 不再直接改状态，必须先 Policy allowed 并翻译为 `MARK_WAITING_INTERVENTION`。
- Policy rejection 原 LLM decision 只记录 attempt；若 fallback 最终不是 manual mutation，节点保持原成功状态。
- LLM timeout/error/parse failure 只会通过 Task 06 fallback 形成规则 decision，不得把节点 failureCategory 改为模型错误。
- `recordAgentDecisionTrace` 改为接收 runtime decision，写入真实 policyResult/mutation，不再传两个 null。

---

## 13. Trace 与 Task 08 边界

Task 07 继续调用现有：

~~~java
orchestrationTraceService.recordDecision(
        taskId,
        completedNode,
        runtimeDecision.decision(),
        runtimeDecision.policyResult(),
        runtimeDecision.mutation());
~~~

Task 07 负责：

- 原 LLM Policy rejection attempt 有事件。
- Rule fallback attempt 有事件。
- AgentSuggestion gate 不再写 null policy/mutation。
- checkpoint 的 decisionCount、section count 与读取状态可恢复/审计。

Task 07 不负责：

- 持久化 `coordinatorOutcome.shadowExecution`。
- 持久化 `shadowDecisions`。
- 持久化 `llmFailure.attempts` 全量 typed facts。
- report/export/replay 投影 runtime batch。

上述内容由 Task 08 使用本任务的 typed batch 完成。

---

## 14. 异常、并发与原子性

1. Runtime Coordinator 不捕获并吞掉 `IllegalArgumentException` 等内部契约错误。
2. 外部 LLM 异常仍由 Task 05/06 typed failure 处理，Task 07 不解析 exception message。
3. State JSON 读取异常使用中文 warning 并返回 `UNREADABLE` 状态，不包含原始敏感 payload；Coordinator 随后把 effective count 收紧到总额度上限。
4. fallback 调用使用方法局部 boolean/控制流，不使用 singleton `fallbackUsed` 字段。
5. 同一 batch 的 attempts/final decisions 使用不可变列表。
6. section count 只在动态计划和节点均成功持久化后递增。
7. `DynamicPlanAppender` 在创建计划前再次校验 task.currentPlanVersionId 与 parentPlan.id，过期 batch 不执行。
8. 当前单任务执行模型下不新增数据库锁；若未来允许同一 task 多 executor 并发补图，必须另立带版本条件更新或悲观锁任务。
9. 所有复杂条件和核心方法必须有详细中文注释。

---

## 15. 文件边界

### 15.1 新增生产文件

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeState.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecision.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionBatch.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeStateService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionService.java
~~~

### 15.2 修改生产文件

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationShadowBudgetGate.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorCheckpoint.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
~~~

### 15.3 新增测试文件

~~~text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeStateTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeStateServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionBatchTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionServiceTest.java
~~~

### 15.4 修改测试文件

~~~text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionConfigurationTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/StageOneDegradedContractIntegrationTest.java
backend/src/test/java/cn/bugstack/competitoragent/integration/OrchestrationRuntimeFeedbackSmokeTest.java
~~~

### 15.5 原则上不修改

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/LlmOrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/RuleBasedOrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionActionMatrix.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionModelInvoker.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/report/**
backend/src/main/java/cn/bugstack/competitoragent/conversation/**
frontend/**
~~~

若实现时必须修改原则上不修改文件，先在本文补充无法通过兼容 API 完成的证据和 owner 校正，再开始改代码。

---

## 16. 结构化执行计划

| 任务拆解步骤 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 0 | 修复 Task 06 Spring constructor baseline，恢复完整应用上下文 | 0.5 小时 | 当前 75/77 基线证据 |
| Task 1 | 固定 runtime state/decision/batch 不可变契约和 checkpoint 读取 | 2-3 小时 | Task 0 |
| Task 2 | 实现 outcome→Policy→单次 fallback→final batch 协调器 | 3-4 小时 | Task 1、Task 06 API |
| Task 3 | 落地 maxAuto、confirmation、per-section branch limit 三个运行时门 | 2-3 小时 | Task 2 |
| Task 4 | DynamicPlanAppender 迁移到 runtime batch 和唯一 ruleSet owner | 2.5-3.5 小时 | Task 1-3 |
| Task 5 | DagExecutor AgentSuggestion gate 迁移并更新构造 fixture | 2.5-3.5 小时 | Task 2-4 |
| Task 6 | 分层回归、一次干净打包、实测记录与 Task 08 handoff | 1.5-2 小时 | Task 0-5 |

复杂度表示风险和验证范围，不是绝对耗时承诺。

---

## 17. 进度记录

当前阶段：Task 07 Policy、Executor 与 Runtime 接入已完成

- [x] 信息采集：阶段二设计/主计划、Task 02/05/06 handoff、Policy/Executor、checkpoint、DynamicPlanAppender、DagExecutor 与测试构造点已核对
- [x] 数据分析：runtime coordinator、single fallback、confirmation、total/section limits、typed batch 与 Task 08 边界已固定
- [x] 报告撰写：Task 07 计划主体和 TDD 分解已形成
- [x] 质检复核：占位符、类型一致性、文件边界、命令和 baseline blocker 已完成自审

- [x] Task 0：Spring composition baseline 修复，已完成（configuration 2/2、smoke 2/2）
- [x] Task 1：runtime state 与 immutable contracts，已完成（18 tests passed）
- [x] Task 2：runtime coordinator 与 single fallback，已完成（35 tests passed）
- [x] Task 3：confirmation/limits runtime guards，已完成（40 tests passed）
- [x] Task 4：DynamicPlanAppender 接入，已完成（14 tests passed）
- [x] Task 5：DagExecutor AgentSuggestion gate 接入，已完成（46 tests passed）
- [x] Task 6：回归、打包与记录，已完成（233 tests passed；clean package passed）

- 当前执行步骤：Task 6 已完成，Task 07 收口
- 已完成步骤占比：计划 4/4（100%）；代码 7/7（100%）
- 当前测试：25.1-25.7 分层回归与 25.8 一次干净打包
- 测试结果：25.7 共 233 tests passed；0 failures；0 errors；clean package passed
- 剩余步骤：Task 08 outcome/trace/report/replay 与 Task 09 真实模式验收（不属于 Task 07）
- 步骤执行状态：Task 0-6 全部成功

每次暂停必须追加：

~~~markdown
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
~~~

---

## 18. Task 0：恢复 Spring Composition Baseline

### Step 1：保留当前红灯证据

- [x] 确认 `OrchestrationRuntimeFeedbackSmokeTest` 两个 case 均因 ApplicationContext 启动失败报错。
- [x] 确认根因链最终为 `OrchestrationShadowBudgetGate.<init>()` 不存在。
- [x] 确认其他 75 个 selected tests 通过，避免误判为 Task 07 业务回归。

命令：

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeFeedbackSmokeTest" test
~~~

预期：FAIL，错误包含：

~~~text
Failed to instantiate OrchestrationShadowBudgetGate
No default constructor found
~~~

### Step 2：最小修复生产构造器

- [x] 在三参数生产构造器增加 `@Autowired`。
- [x] 不开放四参数私有构造器。
- [x] 不增加无参构造器。
- [x] 不修改 `noop()`、预算估算、strict quota 或 release 语义。

### Step 3：补 composition regression

扩展 `OrchestrationDecisionConfigurationTest`：

- [x] Spring 能创建唯一 `OrchestrationShadowBudgetGate`。
- [x] Gate 使用真实三依赖构造器。
- [x] `OrchestrationDecisionModelInvoker` 与 LLM Brain 能完成装配。
- [x] 不需要 `@Primary` 或无参构造器。

### Step 4：验证绿灯

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationRuntimeFeedbackSmokeTest" test
~~~

预期：PASS；smoke 2 tests / 0 failures / 0 errors。

Task 0 未通过时禁止开始 runtime 实现。

---

## 19. Task 1：Runtime State 与不可变契约

### Step 1：先写 record contract 红灯测试

新增：

~~~text
OrchestrationRuntimeStateTest
OrchestrationRuntimeDecisionTest
OrchestrationRuntimeDecisionBatchTest
~~~

必须覆盖：

- [ ] null list/map 归一为空不可变集合。
- [ ] null checkpoint status fail-closed 归一为 UNREADABLE。
- [ ] negative restored count 归一为非负内部值且状态收紧为 UNREADABLE；negative next version 归一为安全值。
- [ ] ABSENT/RESTORED/UNREADABLE 三种状态均可进入 batch 并保持 sourceUrls。
- [ ] section key trim、小写、去重，空 key 归一为 `__unscoped__`。
- [ ] runtime decision 合并 decision/policy/mutation sourceUrls。
- [ ] policy rejected 与非 NO_MUTATION 冲突时构造失败。
- [ ] confirmation required 与非 MARK_WAITING_INTERVENTION 冲突时构造失败。
- [ ] batch 主列表禁止 `LLM_SHADOW`。
- [ ] attempts 可以同时携带 LLM rejection 与 RULE_FALLBACK。
- [ ] finalDecisions 只能引用 attempts 中的值相等 decision result。
- [ ] 所有类型均无 raw prompt/response/message 字段。

运行：

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeStateTest,OrchestrationRuntimeDecisionTest,OrchestrationRuntimeDecisionBatchTest" test
~~~

预期：FAIL，类型尚不存在。

### Step 2：实现最小不可变类型

要求：

- [ ] 使用 record compact constructor 做校验与不可变复制。
- [ ] 复杂不变式使用详细中文注释。
- [ ] 不使用 Lombok 可变 DTO。
- [ ] 每个结构化事实都有 `sourceUrls`。

### Step 3：StateService 红灯测试

新增 `OrchestrationRuntimeStateServiceTest`：

- [ ] 无 checkpoint + active plan v1 -> count=0、map empty、nextPlanVersion=2。
- [ ] checkpoint decisionCount=1 -> 恢复 1。
- [ ] checkpoint section map -> trim/lower 后恢复。
- [ ] 旧 checkpoint payload 含 decisionCount、sourceUrls 但缺 section map -> 解析成功、count/sourceUrls 保持、map empty、状态 RESTORED。
- [ ] checkpoint decisionCount 为负数 -> 状态 UNREADABLE，后续不得获得自动额度。
- [ ] 无法读取最新 checkpoint repository -> 状态 UNREADABLE，不抛异常。
- [ ] payload malformed -> 状态 UNREADABLE、map empty，不抛异常；不得断言自动额度重新从 0 开始。
- [ ] active plan 缺失 -> plan id null、next version 使用安全值 1。
- [ ] checkpoint sourceUrls 去重保留。
- [ ] `afterSuccessfulBranch` 只递增总 count 和目标 section。
- [ ] blank targetSection 累加到 `__unscoped__`。

### Step 4：实现 StateService

读取顺序：

~~~text
find latest ORCHESTRATION_CHECKPOINT_UPDATED
  -> parse checkpoint
find active TaskPlan
  -> currentPlanVersionId / nextPlanVersion
normalize
~~~

所有 repository/JSON 访问使用 try-catch；checkpoint 不存在返回 `ABSENT` 初始 state，可读历史 payload 返回 `RESTORED`，读取或解析异常返回 `UNREADABLE`。warning 不输出完整 payload。Runtime State Service 只报告可靠性，不读取 ruleSet；“不可读即自动额度耗尽”的换算由 Runtime Coordinator 完成。

### Step 5：扩展 checkpoint 与 trace

- [ ] `OrchestratorCheckpoint` 新增不可变归一化 section map。
- [ ] `OrchestrationTraceService` 注入 `OrchestrationRuntimeStateService`。
- [ ] 删除 Trace 内部重复的 `resolveNextDecisionCount/extractDecisionCount` owner。
- [ ] `recordCheckpoint` 使用 `afterSuccessfulBranch` 生成 next state。
- [ ] 旧 trace payload 字段保持兼容。
- [ ] checkpoint 继续携带 sourceUrls/evidenceState。

### Step 6：分层验证

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeStateTest,OrchestrationRuntimeStateServiceTest,OrchestrationRuntimeDecisionTest,OrchestrationRuntimeDecisionBatchTest,OrchestrationTraceServiceTest,OrchestrationContractTest" test
~~~

预期：PASS。

---

## 20. Task 2：Runtime Coordinator 与单次 Policy Fallback

### Step 1：写 mode/outcome 红灯测试

新增 `OrchestrationRuntimeDecisionServiceTest`，先覆盖：

- [ ] RULE_ONLY：只评估规则主 decisions。
- [ ] LLM_SHADOW：只评估规则主 decisions，shadow zero Policy/Executor interactions。
- [ ] LLM_PRIMARY success：Policy allowed 后成为 final。
- [ ] Task 06 LLM failure fallback：RULE_FALLBACK 只评估一次，不调用 policy-rejected fallback。
- [ ] 空 outcome decisions：返回空 attempts/final。
- [ ] context 在调用 decision service 前写入 persisted currentDecisionCount。
- [ ] Policy 使用注入的同一个 normalized ruleSet bean identity。

### Step 2：写 Policy rejection fallback 红灯测试

- [ ] 单个 LLM_PRIMARY rejected -> fallback exactly once。
- [ ] 原 LLM result 在 attempts 中，runtimeStatus=POLICY_REJECTED。
- [ ] 原 LLM result 不在 finalDecisions。
- [ ] fallback decisions 全部重新 evaluate。
- [ ] fallback Policy 收到 RULE_FALLBACK origin/LEGACY_RULE_SET contract。
- [ ] fallback Policy allowed -> final decisions 为 fallback。
- [ ] fallback Policy rejected -> NO_MUTATION，且不再 fallback。
- [ ] 两条 LLM candidates 同时 rejected -> fallback exactly once，使用第一条 rejected metadata。
- [ ] 前一条 LLM allowed、后一条 rejected -> 前一条 mutation 也不进入 final。
- [ ] RULE_ONLY/RULE_FALLBACK rejected -> zero fallback interactions。
- [ ] policyFallbackUsed 只表示 Policy rejection fallback，不混淆 Task 06 LLM failure fallback。

运行：

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeDecisionServiceTest" test
~~~

预期：FAIL，Runtime service 尚不存在。

### Step 3：实现 Coordinator

生产构造器显式注入：

~~~java
public OrchestrationRuntimeDecisionService(
        OrchestrationDecisionService decisionService,
        DecisionPolicyService policyService,
        DecisionExecutorAdapter executorAdapter,
        DecisionPolicyRuleSet ruleSet,
        OrchestrationRuntimeStateService runtimeStateService) {
}
~~~

要求：

- [ ] 不使用 `@Primary`。
- [ ] 不在构造器或方法内 `DecisionPolicyRuleSet.builder()`。
- [ ] 不依赖 Trace、DagExecutor 或 DynamicPlanAppender。
- [ ] 不保存跨调用 mutable state。
- [ ] fallback owner 只有 `decide(...)` 内单点。

### Step 4：Composition identity 测试

扩展 `OrchestrationDecisionConfigurationTest`：

- [ ] 只有一个 `DecisionPolicyRuleSet` bean。
- [ ] LLM Brain 与 Runtime service 引用同一个 ruleSet bean。
- [ ] Runtime service 能与两个 Brain 同时装配。

### Step 5：验证

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeDecisionServiceTest,OrchestrationDecisionConfigurationTest,OrchestrationDecisionServiceFallbackTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest" test
~~~

预期：PASS。

---

## 21. Task 3：确认门、总次数与 Section 分支上限

### Step 1：修正 maxAutoDecisions 红灯

扩展 `DecisionPolicyServiceTest`：

- [ ] 先逐条 review 现有 18 条测试并记录结果；既有 `shouldBlockWhenAutoDecisionLimitIsReached` 断言原样保留。
- [ ] count 未达上限，自动 APPEND allowed。
- [ ] count 达上限，CREATE_SUPPLEMENT_BRANCH blocked。
- [ ] count 达上限，CREATE_REWRITE_BRANCH blocked。
- [ ] count 达上限，NO_ACTION 仍 allowed。
- [ ] count 达上限，MANUAL_ONLY 仍 allowed。
- [ ] blocked reason 保持稳定中文前缀和 count/max。
- [ ] legacy/LLM 都按 normalized automatic action 判断，不复制 origin 特例。
- [ ] 与根因 8 无关的旧断言全部不改；若确需修正旧断言，在 §28 写出测试名、旧/新语义和修正理由。

扩展 `OrchestrationRuntimeDecisionServiceTest`：

- [ ] checkpoint 状态 UNREADABLE 时，传给 Prompt/Policy 的 effective count 等于 normalized ruleSet.maxAutoDecisions。
- [ ] UNREADABLE + 自动动作 -> Policy blocked，不生成 APPEND mutation。
- [ ] UNREADABLE + NO_ACTION/MANUAL_ONLY -> 仍可安全停止或转人工，不因状态读取失败抛异常。

### Step 2：确认门红灯

扩展 `DecisionExecutorAdapterTest`：

- [ ] policy allowed + requiresConfirmation=true -> MARK_WAITING_INTERVENTION。
- [ ] runtimeCommand=AWAIT_CONFIRMATION。
- [ ] branchReason=POLICY_CONFIRMATION_REQUIRED。
- [ ] zero nodeTemplates。
- [ ] 即使 normalizedAction 是 CREATE_SUPPLEMENT_BRANCH 也不 APPEND。
- [ ] 普通 MANUAL_ONLY 使用 MANUAL_REVIEW command，与 confirmation 可区分。
- [ ] sourceUrls/evidenceState 保留。

### Step 3：Section limit 红灯

扩展 `OrchestrationRuntimeDecisionServiceTest`：

- [ ] section count < max -> READY/APPEND_NODES。
- [ ] section count == max -> DYNAMIC_BRANCH_LIMIT_REACHED/NO_MUTATION。
- [ ] pricing 达上限不阻断 feature section。
- [ ] 大小写和空格不能绕过同 section。
- [ ] blank section 使用 `__unscoped__`。
- [ ] NO_ACTION/MANUAL_ONLY 不检查 section limit。
- [ ] section limit 不触发 Rule fallback，因为它不是 Policy rejection。
- [ ] limit=0 时所有 APPEND fail-closed。

### Step 4：实现并验证

~~~powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationRuntimeDecisionServiceTest,OrchestrationRuntimeStateServiceTest" test
~~~

预期：PASS。

---

## 22. Task 4：DynamicPlanAppender 接入 Runtime Batch

### Step 1：更新构造依赖红灯

`DynamicPlanAppenderTest` 将 fixture 改为：

~~~java
new DynamicPlanAppender(
        taskRepository,
        nodeRepository,
        dynamicTaskGraphService,
        taskPlanRepository,
        objectMapper,
        runtimeDecisionService,
        orchestrationTraceService);
~~~

禁止保留接收 decisionService/policyService/adapter 的第二个生产构造器。

### Step 2：主链测试

- [ ] 调用 runtime service exactly once。
- [ ] runtime request/context 包含真实 taskStatus/nodeStatus/sourceUrls。
- [ ] 每条 attempts 都记录 trace。
- [ ] 只消费 finalDecisions。
- [ ] 原 rejected LLM mutation 即使 APPEND 也不执行。
- [ ] fallback final APPEND 才创建动态计划。
- [ ] successful APPEND 后保存节点、切换 task plan、记录 checkpoint。
- [ ] checkpoint 在 node/task plan 持久化成功之后调用。

### Step 3：运行时门测试

- [ ] POLICY_REJECTED/NO_MUTATION 不调用 DynamicTaskGraphService。
- [ ] DYNAMIC_BRANCH_LIMIT_REACHED 不保存节点、不切 plan、不记 checkpoint。
- [ ] CONFIRMATION_REQUIRED 标记 completed reviewer node 为 WAITING_INTERVENTION。
- [ ] confirmation 不创建动态计划。
- [ ] stale parent plan 不执行 final APPEND。
- [ ] empty materialized nodes 不记 checkpoint。
- [ ] Task 06 llmFailure 本身不把节点设 FAILED。

### Step 4：移除本地 ruleSet owner

- [ ] 生产文件不再出现 `DecisionPolicyRuleSet.builder()`。
- [ ] 不再直接调用 `DecisionPolicyService.evaluate`。
- [ ] 不再直接调用 `DecisionExecutorAdapter.toMutation`。

### Step 5：同步构造 fixture

更新所有直接 `new DynamicPlanAppender(...)`：

~~~text
DynamicPlanAppenderTest
DagExecutorTest
DagExecutorWorkflowEventTest
DagExecutorRuntimeDependencyTest
CollaborationPlanningSmokeTest
StageOneDegradedContractIntegrationTest
~~~

### Step 6：验证

~~~powershell
mvn -pl backend "-Dtest=DynamicPlanAppenderTest,DynamicTaskGraphServiceTest,CompensationGraphAssemblerTest,OrchestrationTraceServiceTest,DagExecutorRuntimeDependencyTest" test
~~~

预期：PASS。

---

## 23. Task 5：DagExecutor AgentSuggestion Gate 接入

### Step 1：替换依赖

`DagExecutor` 主构造器将：

~~~text
OrchestrationDecisionService
~~~

替换为：

~~~text
OrchestrationRuntimeDecisionService
~~~

保留 `OrchestrationTraceService`，因为 caller 仍负责把逐条 attempt 写入现有 trace。

所有 convenience constructors 必须显式接收或委托 runtime service；禁止在构造器中手工 new 第二套 ruleSet/runtime service。

### Step 2：AgentSuggestion gate 红灯测试

扩展 `DagExecutorTest`：

- [ ] suggestions empty -> runtime service zero interactions。
- [ ] runtime batch attempts 全部记录 trace。
- [ ] trace 使用真实 policyResult/mutation，不再传 null。
- [ ] final MARK_WAITING_INTERVENTION -> 节点 WAITING_INTERVENTION。
- [ ] 原 LLM WAIT 被 Policy rejected、fallback NO_ACTION -> 节点保持 SUCCESS。
- [ ] final NO_MUTATION -> 节点保持 SUCCESS。
- [ ] final APPEND_NODES -> gate 不在 worker thread 直接写 DAG。
- [ ] shadow decision 不可能出现在 runtime batch；防御性输入出现时忽略并告警。
- [ ] Task 06 timeout fallback 的 RULE_FALLBACK NO_ACTION 不设置 FAILED。
- [ ] confirmation reason 使用 decision reason，failureCategory=MANUAL_INTERVENTION_REQUIRED。

### Step 3：更新 trace helper

计划签名：

~~~java
private void recordAgentDecisionTrace(
        Long taskId,
        TaskNode completedNode,
        OrchestrationRuntimeDecision runtimeDecision) {
    // AgentSuggestion gate 必须记录经过 Policy/Executor 后的完整事实，禁止继续写 null policy/mutation。
}
~~~

### Step 4：构造兼容测试

扩展 `DagExecutorRuntimeDependencyTest`：

- [ ] field 包含 `orchestrationRuntimeDecisionService`。
- [ ] field 不再包含 `orchestrationDecisionService`。
- [ ] 主 constructor 可显式注入 runtime service。
- [ ] 不允许 convenience constructor 隐式创建第二套 runtime owner。

同步：

~~~text
DagExecutorWorkflowEventTest
CollaborationPlanningSmokeTest
StageOneDegradedContractIntegrationTest
~~~

### Step 5：验证

~~~powershell
mvn -pl backend "-Dtest=DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test
~~~

预期：PASS。

---

## 24. Task 6：回归、打包与记录

### Step 1：边界扫描

- [x] `OrchestrationDecisionService` 无 Policy/Executor/Trace/runtime import。
- [x] `OrchestrationRuntimeDecisionService` 无 DagExecutor/DynamicPlanAppender/Trace import。
- [x] DynamicPlanAppender 无 `DecisionPolicyRuleSet.builder()`。
- [x] DagExecutor 不直接调用 `decide/decideWithOutcome/fallbackAfterPolicyRejection`。
- [x] shadowDecisions 只在 outcome/batch contract test 中出现，不在执行循环中出现。
- [x] ActionMatrix 文件无业务逻辑修改。
- [x] Prompt/Parser/LLM Brain/ModelGateway 无 Task 07 修改。
- [x] report/conversation/frontend 无修改。
- [x] `STAGE2-RULE-001` 保持 OPEN。

### Step 2：分层回归

先运行本文 25.1-25.6 命令。日常执行均不使用 `clean`。

### Step 3：最终一次干净打包

所有受控测试通过后只运行一次：

~~~powershell
mvn -pl backend clean package -DskipTests
~~~

不得在每个测试层重复执行 clean。

### Step 4：回写实测记录

- [x] 更新本文进度为 7/7。
- [x] 写入各层实际测试数、失败数和命令。
- [x] 记录 Task 0 Spring blocker 修复结果。
- [x] 记录 Maven settings.xml 第 168 行既有 mirrors 警告。
- [x] 记录 Task 08 尚未持久化完整 outcome/shadow/failure。
- [x] 记录真实 LLM/shadow/E2E 未执行。

---

## 25. 分层验收命令

### 25.1 Spring composition baseline

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationRuntimeFeedbackSmokeTest" test
~~~

### 25.2 Runtime immutable contracts/state

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeStateTest,OrchestrationRuntimeStateServiceTest,OrchestrationRuntimeDecisionTest,OrchestrationRuntimeDecisionBatchTest,OrchestrationTraceServiceTest,OrchestrationContractTest" test
~~~

### 25.3 Coordinator、Policy fallback 与 guards

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeDecisionServiceTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceFallbackTest,OrchestrationDecisionServiceLlmModeTest" test
~~~

### 25.4 Dynamic plan runtime

~~~powershell
mvn -pl backend "-Dtest=DynamicPlanAppenderTest,DynamicTaskGraphServiceTest,CompensationGraphAssemblerTest,OrchestrationTraceServiceTest" test
~~~

### 25.5 DagExecutor 与 caller compatibility

~~~powershell
mvn -pl backend "-Dtest=DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest" test
~~~

### 25.6 Task 01-06 compatibility

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationDecisionActionMatrixTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionPromptInjectionTest,OrchestrationDecisionResponseParserTest,OrchestrationDecisionFixtureContractTest,LlmOrchestratorDecisionFailureTest,LlmOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationShadowBudgetGateTest,OrchestrationDecisionModelInvokerTest,ModelGatewayTest,OrganizationQuotaPolicyTest" test
~~~

### 25.7 Task 07 受控总回归

~~~powershell
mvn -pl backend "-Dtest=OrchestrationRuntimeStateTest,OrchestrationRuntimeStateServiceTest,OrchestrationRuntimeDecisionTest,OrchestrationRuntimeDecisionBatchTest,OrchestrationRuntimeDecisionServiceTest,OrchestrationDecisionConfigurationTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest,DynamicTaskGraphServiceTest,CompensationGraphAssemblerTest,DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest,StageOneDegradedContractIntegrationTest,OrchestrationRuntimeFeedbackSmokeTest,OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationDecisionActionMatrixTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionPromptInjectionTest,OrchestrationDecisionResponseParserTest,OrchestrationDecisionFixtureContractTest,LlmOrchestratorDecisionFailureTest,LlmOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationShadowBudgetGateTest,OrchestrationDecisionModelInvokerTest,ModelGatewayTest,OrganizationQuotaPolicyTest" test
~~~

### 25.8 最终一次打包

~~~powershell
mvn -pl backend clean package -DskipTests
~~~

Task 07 不调用真实模型，不启用真实 shadow，不执行 E2E。

---

## 26. 完成标准

- [x] Task 0 完整 Spring context baseline 恢复。
- [x] 两个 runtime caller 不再调用兼容 `decide(...)`。
- [x] runtime 只消费 `outcome.decisions()`。
- [x] shadow decisions 的 Policy/Executor/DAG interactions 为 0。
- [x] Runtime Coordinator 使用唯一 normalized ruleSet bean。
- [x] LLM_PRIMARY Policy rejection 每周期最多一次 Rule fallback。
- [x] 原 LLM rejected attempt 保留但不进入 final decisions。
- [x] fallback decisions 重新走 legacy Policy。
- [x] fallback Policy rejection 不形成循环。
- [x] Task 06 LLM failure fallback 不触发第二次 fallback。
- [x] persisted checkpoint count 进入 Prompt 和 Policy。
- [x] 无 checkpoint 以 count=0 的 `ABSENT` 初始态运行。
- [x] 不含 section map 的旧 checkpoint 保留 decisionCount/sourceUrls，map empty 且状态为 `RESTORED`。
- [x] 损坏或不可读 checkpoint 标记为 `UNREADABLE`，effective count 按 maxAutoDecisions 处理，不重新开放自动补图额度。
- [x] maxAutoDecisions 只阻断自动 mutation。
- [x] 达上限后 NO_ACTION/MANUAL_ONLY 仍可安全停止或转人工。
- [x] 现有 18 条 DecisionPolicyService 测试已逐条 review，自动动作上限断言保持，任何旧断言修正均有精确留痕。
- [x] maxDynamicBranchesPerSection 按稳定 section key 生效。
- [x] 空 section 不能绕过分支上限。
- [x] requiresConfirmation 在 mutation 前转为真实暂停。
- [x] confirmation 不生成 APPEND_NODES。
- [x] DynamicPlanAppender 只执行 final Policy allowed APPEND mutation。
- [x] 动态计划成功落库后才递增 checkpoint counters。
- [x] stale/empty/failed append 不递增 counters。
- [x] AgentSuggestion gate 只对 final MARK_WAITING_INTERVENTION 改节点状态。
- [x] AgentSuggestion trace 带真实 policyResult/mutation。
- [x] LLM timeout/error/parse failure 不把节点误标 FAILED。
- [x] 所有新增 structured facts 包含 sourceUrls。
- [x] 无 raw prompt/response/message 持久化。
- [x] Task 01-06 受控回归通过。
- [x] DynamicPlanAppender/DagExecutor/两类 smoke 回归通过。
- [x] clean package 通过且只执行一次。
- [x] 所有核心方法和复杂条件有详细中文注释。
- [x] Task 08/09 未提前实施。
- [x] `STAGE2-RULE-001` 保持 OPEN。

---

## 27. 与后续任务的接口约束

### 27.1 交给 Task 08：Trace/Report/Replay

Task 08 必须：

1. 持久化 `OrchestrationRuntimeDecisionBatch.coordinatorOutcome`。
2. 持久化 shadow execution、shadow decisions 和 llmFailure typed facts。
3. 在同一决策周期关联原 LLM Policy rejection attempt 与 RULE_FALLBACK final attempt。
4. 展示 runtimeStatus、policy blocked reasons、mutation branchReason 和 confirmation 状态。
5. replay/report/export 只读事件，不重新调用 Runtime Coordinator、Brain、Policy 或 ModelGateway。
6. 保持 Task 01 Summary projector 字段兼容。

### 27.2 交给 Task 09：阶段二验收

Task 09 必须：

1. 使用 Task 04 的 9 条人工 fixtures。
2. 验证 citation 未达/达到 maxAutoDecisions 两条路径。
3. 验证同 section 分支上限。
4. 验证至少一次 LLM_PRIMARY Policy allowed 并形成真实 mutation。
5. 验证一次 Policy rejection Rule fallback。
6. 验证 timeout/parse failure 不污染节点状态。
7. 真实模式只执行一次，不进入反复调参循环。

---

## 28. 计划实测记录

~~~markdown
当前阶段：Task 07 代码实施与分层验收已完成
- [x] 信息采集：阶段二设计/主计划、Task 01-06 handoff、Policy/Executor、checkpoint、DynamicPlanAppender、DagExecutor 与构造 fixture 已核对
- [x] 数据分析：runtime coordinator、single fallback、persisted counters、confirmation、section limit 与 Task 08 handoff 已固定
- [x] 报告撰写：Task 07 计划、实施结果、边界与 Task 08/09 handoff 已回写
- [x] 质检复核：Task 0-6 已完成，代码进度 7/7；分层回归与一次干净打包均通过

规划基线命令：
mvn -pl backend "-Dtest=OrchestrationDecisionConfigurationTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorRuntimeDependencyTest,CollaborationPlanningSmokeTest,OrchestrationRuntimeFeedbackSmokeTest" test

规划基线结果：
77 selected / 75 passed / 2 errors；唯一错误类 OrchestrationRuntimeFeedbackSmokeTest；根因 OrchestrationShadowBudgetGate Spring constructor selection。

实现命令：已依次执行本文 25.1-25.7；全部通过后，仅执行一次 `mvn -pl backend clean package -DskipTests`。
实现测试结果：25.1-25.7 分层测试数依次为 4 / 18 / 44 / 12 / 46 / 124 / 233，全部为 0 failures / 0 errors；最终 clean package 为 BUILD SUCCESS，产物为 backend/target/backend-1.0-SNAPSHOT.jar（290688492 bytes）。
Task 0 结果：已通过在 OrchestrationShadowBudgetGate 生产构造器上显式标注 `@Autowired` 修复 Spring constructor selection blocker，完整 Spring context baseline 已恢复。
中断记录：2026-07-14 18:17，Task 0-6 代码、分层回归和一次 clean package 已完成；中断点仅剩本节实施记录与最终只读检查。
恢复记录：2026-07-14 18:20，从上述中断点恢复，未重复执行测试或 clean package，继续补齐持久化进度记录。
剩余边界：Maven settings.xml 第 168 行仍有既有 `Unrecognised tag 'mirrors'` 警告；Task 08 的完整 outcome/shadow/failure 持久化与 trace/report/replay 尚未实施；Task 09 真实 LLM/shadow/E2E 验收未执行；STAGE2-RULE-001=OPEN。
~~~

### 计划自审记录：2026-07-14

- 已确认：Runtime Coordinator 是 outcome、Policy、单次 policy-rejected fallback、runtime guards 与 mutation 编排的唯一 owner。
- 已确认：OrchestrationDecisionService 继续不依赖 Policy/Executor/Trace/runtime，Task 06 边界不反向坍缩。
- 已确认：所有新增 runtime state/decision/batch 类型均包含 sourceUrls，且不保存 raw prompt/response/message。
- 已确认：maxAutoDecisions、maxDynamicBranchesPerSection 和 requiresConfirmation 分属 Policy/Runtime Guard/Adapter 的单一 owner，没有复制 ActionMatrix。
- 已确认：现有 18 条 DecisionPolicyService 测试只有自动 `APPEND_DYNAMIC_BRANCH` 覆盖 maxAutoDecisions，不存在要求上限后拒绝 NO_ACTION/MANUAL_ONLY 的已验收断言；Task 07 将保留该自动动作旧断言，并把行为变化记录为 Task 02 过宽判断的定向修正。
- 已确认：checkpoint 状态明确区分 ABSENT、RESTORED、UNREADABLE；旧 payload 缺 section map 仍可读并保留 decisionCount/sourceUrls，真正损坏时由 Runtime Coordinator 将有效次数按 maxAutoDecisions 处理，禁止重新获得补图额度。
- 已确认：DynamicPlanAppender 只执行 final APPEND mutation；DagExecutor AgentSuggestion gate 只执行 final manual pause，不在 worker thread 复制动态补图。
- 已确认：Task 08 完整 outcome/trace/report/replay 投影与 Task 09 真实模式验收未提前进入 Task 07。
- 已确认：128 个 Markdown fence 标记成对闭合；无 TBD/TODO/待定/“同上”式占位步骤。
- 已记录：规划基线 77 selected / 75 passed / 2 context errors，Task 0 修复前不得宣称 Task 07 baseline 全绿。
