# Task 08 Trace、Report、Export 与 Replay Implementation Plan

> **For agentic workers:** 本文是可直接执行的 Task 08 计划。执行者必须复用 Task 01-07 已固定的 origin/metadata、typed outcome、shadow execution、typed LLM failure、runtime decision batch 和 workflow outbox；不得重新调用 Coordinator、Brain、Policy 或 ModelGateway 来补齐展示字段，不得保存 raw prompt/response/exception message，也不得提前执行 Task 09 的真实模型或 E2E 验收。

**Goal:** 将 `OrchestrationRuntimeDecisionBatch` 的完整可审计事实一次性写入 workflow outbox，并让 report、Markdown/HTML/JSON export、task replay 和既有只读查询稳定展示主决策、Policy、runtime guard、mutation、fallback、shadow 与 typed LLM failure，同时保持历史事件和 Task 01 `OrchestrationDecisionSummary` 兼容；Task 08 收口前必须用生产 TraceService 写入真实 H2/outbox 事件，并贯通验证 report -> export -> replay，而不能只依赖手构 mock。

**Architecture:** 继续使用 `ORCHESTRATION_DECISION_RECORDED`，不新增事件表和事件类型；新增 V2 单周期审计 payload，由 `OrchestrationTraceService.recordDecisionBatch(...)` 将一次 runtime batch 收口成一条事件。payload 顶层保留 `decision/policyResult/mutation` 兼容别名，内部 `audit` 保存 coordinator outcome、全部 attempts、final decision 引用、runtime state、shadow 与 typed failure。`OrchestrationDecisionSummaryProjector` 继续作为原始事件 JSON 的唯一解析 owner，并同时输出兼容的代表决策摘要和新的周期审计摘要。所有展示链只读已持久化事件。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Jackson, existing workflow outbox, existing `OrchestrationTraceService`, existing `OrchestrationDecisionSummaryProjector`, existing report/export/replay projection.

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
  -> Task 08：Trace、Report、Export、Replay
  -> Task 09：阶段二验收
~~~

主计划已固定本任务文件名：

~~~text
task-08-trace-report-replay.md
~~~

Task 08 是阶段二最后一个生产接线任务。它不再改变决策、Policy 或 mutation 行为，只解决“运行时已经知道完整事实，但审计和交付链只看到半截事实”的根因。

---

## 2. 当前基线与根因证据

### 2.1 规划基线

已执行：

~~~powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,ConversationOrchestrationDecisionQueryServiceTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest" test
~~~

结果：

~~~text
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
~~~

既有 Maven `settings.xml` 第 168 行仍有 `Unrecognised tag 'mirrors'` 警告，与 Task 08 无关。

### 2.2 当前写侧只保存逐条 attempt

`DynamicPlanAppender` 和 `DagExecutor` 当前都遍历 `batch.attempts()` 调用：

~~~java
orchestrationTraceService.recordDecision(
        taskId,
        completedNode,
        runtimeDecision.decision(),
        runtimeDecision.policyResult(),
        runtimeDecision.mutation());
~~~

该形状能保存一条 decision/policy/mutation，但不能保存：

1. `coordinatorOutcome.mode`。
2. `shadowExecution` 和 `shadowDecisions`。
3. `llmFailure.type/providerErrorCode/parseRetryCount/attempts`。
4. `policyFallbackUsed`。
5. 同一周期内“原 LLM Policy rejection -> RULE_FALLBACK”的关联。
6. `runtimeStatus` 和 `fallbackAttempt`。
7. checkpoint 读取状态、当前决策次数和 section 分支计数。

### 2.3 当前读模型字段已预埋，但投影不完整

Task 01 已在 `OrchestrationDecisionSummary` 中预埋：

~~~text
decisionOrigin / decisionContract
fallbackReason / fallbackUsed
modelName / temperature
promptHash / llmResponseHash / parseRetryCount
shadowExecuted / shadowSkippedReason
sourceUrls / evidenceState
~~~

但当前 projector 只读取顶层 `decision` 和 `policyResult`，没有周期级 `audit`，也没有 Policy blocked reasons、runtimeStatus、mutation branchReason、shadow decision 列表和 typed failure attempts。

### 2.4 当前导出丢失 Task 01 元数据

`ReportExportRenderSupport.buildOrchestrationDecisionPayload(...)` 当前只输出动作、原因、确认和证据字段。即使 `ReportResponse.orchestrationDecision` 已包含 origin、model、hash、fallback 和 shadow 字段，JSON 导出仍会丢失它们；Markdown/HTML 也没有显示 Policy、runtime 和 fallback 事实。

### 2.5 当前 replay 只能选择代表决策

`TaskReplayResponse` 和 `ReplayTimelineEvent` 只有一个 `OrchestrationDecisionSummary`。当 shadow 因预算跳过且没有 shadow decision 时，仍然应形成审计事件；当前 marker 规则会把这种事件投影为空，用户无法知道 shadow 为什么没有执行。

### 2.6 只读链路缺少禁止模型重调的可执行证明

设计和主计划要求 report/export/replay 不重调 LLM，但当前只有架构约定，没有负向依赖测试。Task 08 必须把该要求变成测试约束，不能只写在注释中。

### 2.7 当前不存在单周期 decisions 硬上限

`DecisionPolicyRuleSet.maxAutoDecisions=2` 只限制跨周期自动 mutation 总数。当前 `OrchestrationDecisionResponseParser` 会遍历模型返回的整个 `decisions` 数组，`OrchestrationRuntimeDecisionService` 也会遍历 outcome 的全部 decisions；两者都没有单周期数量上限。

因此不能把 `maxAutoDecisions` 当成 payload attempts 上界。Task 08 在冻结 V2 schema 前必须新增独立的 `maxDecisionsPerCycle` 契约，并同时约束 LLM Parser 与 runtime 的 Rule/fallback 防御入口。

### 2.8 当前可见性测试主要是 mock repository 单测

现有 `ReportServiceTest` 和 `TaskReplayProjectionServiceTest` 主要向 mock repository 返回手构事件；它们能验证局部投影，却不能证明生产 `OrchestrationTraceService -> WorkflowEventOutboxService -> H2 repository -> report/export/replay` 接缝成立。

仓库已有 `Phase5EnterpriseDeliveryIntegrationTest` 和 `OrchestrationRuntimeFeedbackSmokeTest` 的 Spring Boot + H2 测试模式可复用。Task 08 必须新增一个不调用外部模型、但真实落 outbox 事件的可见性贯通测试。

---

## 3. Task 01-07 输入契约

### 3.1 Task 01：不复制 origin/metadata

继续复用：

~~~text
OrchestrationDecision.decisionOrigin
OrchestrationDecision.decisionMetadata
DecisionPolicyResult.decisionOrigin
DecisionPolicyResult.decisionContract
OrchestrationDecisionSummary
OrchestrationDecisionSummaryProjector
~~~

禁止在 audit 根节点新增平行的 `modelName/fallbackReason/promptHash` map。模型元数据必须来自对应 decision 或 typed failure。

### 3.2 Task 05/06：失败事实已经类型化

只持久化 `LlmOrchestratorDecisionFailure` 的安全字段：

~~~text
type
providerErrorCode
parseRetryCount
attemptNumber
promptHash
llmResponseHash
issues
discardedSourceUrls
~~~

禁止持久化：

~~~text
raw system prompt
raw user prompt
raw model response
API key
exception message / stack trace
~~~

### 3.3 Task 07：batch 是一次决策周期的唯一输入

Task 08 写侧只接收：

~~~java
OrchestrationRuntimeDecisionBatch batch
~~~

其中：

- `coordinatorOutcome` 保存 mode、主结果、shadow 和 typed failure。
- `attempts` 保存全部 Policy/runtime/mutation 评估事实。
- `finalDecisions` 是调用方唯一允许执行的结果集合。
- `policyFallbackUsed` 说明本周期是否因 LLM Policy rejection 触发 Rule fallback。
- `sourceUrls` 是周期级有序去重来源。

Task 08 不重新执行 Policy，不从字符串原因反推 fallback，也不根据 mutation 猜 origin。

---

## 4. 目标

1. 一次 runtime decision cycle 只写一条 V2 `ORCHESTRATION_DECISION_RECORDED` 事件。
2. 在同一 payload 中关联 coordinator outcome、attempts、final decisions、runtime state、shadow 与 typed failure。
3. shadow skipped 且没有 decision 时仍写事件并可被 replay/report/export 看见。
4. 顶层保留代表决策兼容别名，使 Task 01 projector、Conversation 和历史 API 不被破坏。
5. 扩展 `OrchestrationDecisionSummary`，展示 Policy、runtimeStatus、fallbackAttempt 和 mutation 摘要；旧事件缺失字段保持 `null/empty`，禁止伪造事实。
6. 新增周期级只读审计摘要，承载多 attempts、shadow、typed failure 和 runtime state。
7. report、Markdown、HTML、JSON export 与 replay 复用同一投影结果。
8. `sourceUrls` 贯穿 V2 payload、代表决策、attempt、shadow、failure audit、report/export/replay；Parser 拒绝的 `discardedSourceUrls` 只作为警告事实保存，不能升级为可信证据来源。
9. 通过负向依赖测试证明只读链不调用 Runtime Coordinator、Brain、Policy 或 ModelGateway。
10. 保持旧 `ORCHESTRATION_DECISION_RECORDED` 嵌套/平铺/inputRefs 事件兼容。
11. 用生产 TraceService 将 typed batch 写入真实测试数据库，并用同一持久化事件跑通 report、三种 export 和 replay。
12. 固定 `maxDecisionsPerCycle=2` 的单周期硬上限，使 primary/fallback/shadow 和 payload size fixture 具有可证明的数量边界。

---

## 5. 非目标

- 不修改 Brain、ActionMatrix、Policy 或 Executor 的动作语义；仅允许为 payload 安全补充 `maxDecisionsPerCycle`，同步 Prompt 声明、Parser 拒绝和 runtime 防御校验。
- 不改变 `RULE_ONLY/LLM_SHADOW/LLM_PRIMARY` 模式选择。
- 不改变 shadow 预算准入和 quota 扣减。
- 不执行真实外部 LLM、真实 shadow quota 或真实网络 E2E；这些属于 Task 09。本任务必须执行本地 Spring Boot + H2/outbox 可见性贯通测试，这不是可选项。
- 不新增独立 trace 数据库表，不做事件溯源平台重构。
- 不新增 workflow event type 或数据库迁移；继续使用既有 outbox 事件类型。
- 不把 V2 trace 当成 mutation 已成功执行的证明；`READY` 只表示已通过 runtime gate，真实补图成功仍由 checkpoint/plan event 证明。
- 不做前端 UI 大改；本任务只保证后端 API 和正式导出具备完整字段。
- 不改采集、Tavily、Reviewer 评分、报告模板或质量阈值。
- 不关闭 `STAGE2-RULE-001`。

---

## 6. 方案选择

### 6.1 采用：同事件类型的 V2 单周期 payload

继续发布：

~~~text
WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
~~~

原因：

1. 现有 repository、report、conversation 和 replay 已围绕该类型建立稳定查询入口。
2. 新增枚举会要求 MQ tag、查询、历史兼容和测试同步分叉，但没有新增业务语义。
3. 一条事件天然提供同周期 attempts/fallback/shadow 的原子关联，不需要靠时间戳拼接多条事件。
4. V1/V2 可由 payload 的 `traceSchemaVersion` 明确区分。

### 6.2 拒绝：继续逐条写 attempt，再由 replay 拼周期

该方案无法可靠区分并发任务节点、同一节点的多次决策和 Policy fallback 边界；时间窗口关联会制造新的推断逻辑。

### 6.3 拒绝：直接序列化整个 runtime batch

`DynamicPlanMutation.nodeTemplates` 和 `runtimeCommand` 属于执行模型，不是审计展示必需字段；直接 dump 会扩大 `TEXT` payload、泄露不必要执行细节并让持久化格式耦合可变运行时类型。

Task 08 应新增不可变、可序列化的 audit snapshot，只保留契约要求的事实。该 snapshot 是 batch 的规范持久化投影，不是第二套决策模型。

### 6.4 拒绝：report/replay 重新调用 Service 获得最新结果

这会让查看报告产生新模型调用、quota 消耗和非确定性结果，也会破坏 replay 的历史真实性。

---

## 7. V2 持久化契约

### 7.1 Payload 顶层

目标形状：

~~~json
{
  "summary": "Orchestrator 已记录完整运行期决策周期",
  "traceSchemaVersion": "ORCHESTRATION_TRACE_V2",
  "decision": {},
  "policyResult": {},
  "mutation": {},
  "runtimeStatus": "READY",
  "fallbackAttempt": false,
  "audit": {},
  "evidenceState": "FULL_SOURCE",
  "sourceUrls": []
}
~~~

顶层 `decision/policyResult/mutation/runtimeStatus/fallbackAttempt` 是代表 attempt 的兼容别名；完整事实以 `audit` 为准。

### 7.2 代表 attempt 选择规则

只能有一个 owner，固定顺序：

1. `finalDecisions` 非空时，选择列表最后一条作为代表结果。
2. final 为空但 attempts 非空时，选择 attempts 最后一条，展示真实阻断状态。
3. attempts 为空时代表 attempt 为 `null`；shadow-only/skip 仍依靠 `audit` 可见。

禁止选择 shadow decision 作为主路径代表 decision。Conversation 只能消费代表主路径决策，不能把 shadow 建议变成可执行预览。

### 7.3 Audit 根对象

建议新增 `OrchestrationDecisionAuditTrace`，至少包含：

~~~text
traceSchemaVersion
mode
coordinatorDecisions
attempts
finalDecisionIds
policyFallbackUsed
runtimeState
shadowExecution
shadowDecisions
llmFailure
sourceUrls
~~~

所有 list 构造时 `List.copyOf`，文本 trim，`sourceUrls` 有序去重；任何业务对象为 null 时按其既有 record 契约处理，不在 trace 层猜默认业务值。

### 7.4 Attempt snapshot

建议新增 `OrchestrationRuntimeDecisionTrace`：

~~~text
decision
policyResult
mutationSummary
fallbackAttempt
runtimeStatus
sourceUrls
~~~

`mutationSummary` 只保留：

~~~text
mutationId
decisionId
mutationType
targetPlanVersionId
branchReason
dynamicAction
expectedResumeNodeName
evidenceState
sourceUrls
~~~

明确不持久化 `nodeTemplates` 和 `runtimeCommand`。

### 7.5 Runtime state snapshot

持久化：

~~~text
currentDecisionCount
dynamicBranchCountsBySection
currentPlanVersionId
nextPlanVersion
checkpointStateStatus
sourceUrls
~~~

`UNREADABLE` 必须原样保存，不能在投影层改成 `ABSENT`。

### 7.6 Typed failure snapshot

主 `llmFailure` 和 `shadowExecution.failure` 都保存 Task 05 类型化结构。新增的 failure audit wrapper 必须显式包含所属 outcome/shadow execution 的已验证 `sourceUrls`。Parser `issues` 与 `discardedSourceUrls` 原样结构化保存，但 discarded URL 只能展示为“模型输出中已拒绝的来源”，不得合并进可信 `sourceUrls`；任何展示层不得重新解析 exception message。

### 7.7 Payload 大小边界

`TaskWorkflowEvent.payload` 当前为 `TEXT`。Task 08 必须：

- 在 `DecisionPolicyRuleSet` 新增独立字段 `maxDecisionsPerCycle`，默认值固定为 2，归一化后最小为 1；它不能复用可能为 0 的 `maxAutoDecisions`。
- Prompt 明确一次最多输出 2 条 decision；Parser 在构造 decision 前拒绝超过上限的数组并返回稳定 issue code `TOO_MANY_DECISIONS`。
- Runtime Coordinator 对 Rule/legacy/fallback outcome 执行同一上限的防御校验，避免非 LLM 路径绕过 Parser。
- 不保存 raw prompt/response。
- 不保存 mutation node templates/runtime command。
- 不复制同一对象为多份完整 audit；顶层只保留一个代表别名。
- 使用确定性数量上界构造序列化 fixture：primary decisions <= 2、fallback decisions <= 2、runtime attempts <= 4、shadow decisions <= 2；Task 05 已固定每次主/影子 LLM 调用最多 1 次 parse retry，因此每个 typed failure 的 model attempts <= 2。
- 在上述完整数量上界下加入多 issues、discardedSourceUrls 和已验证 sourceUrls，验证 fixture 小于 60 KiB。

若真实契约 fixture 仍超过 60 KiB，必须在 Task 08 文档中记录证据并改用明确的数据库大文本方案；禁止静默截断 `sourceUrls`、issues 或 hashes。

`maxAutoDecisions` 继续表示跨周期自动执行额度，`maxDecisionsPerCycle` 表示单次响应/单次 outcome 的候选数量；两个字段语义不同，测试和 Prompt 中不得混用。

### 7.8 Schema freeze 硬门

Task 0 必须产出 canonical V2 JSON fixture、字段表、数量上限和 V1 降级规则；Task 1 必须证明 assembler 序列化结果与 fixture 一致并完成 round-trip。以下条件全部通过前，禁止开始 Task 2 的生产写侧接入：

1. `maxDecisionsPerCycle` 已在 Prompt、Parser、runtime 防御和测试中生效。
2. canonical fixture 同时覆盖 LLM rejection、Rule fallback、shadow、typed failure、runtime state 和 sourceUrls。
3. V1/V2 projector contract 测试已锁定字段名与 nullable 语义。
4. payload size fixture 在确定性数量上界下通过。
5. 本节 schema freeze checklist 已在进度记录中标记完成。

Task 2 落库后只允许向后兼容地增加 optional 字段；删除、重命名或改变字段语义必须升级 schema version，不能原地修改 `ORCHESTRATION_TRACE_V2`。

---

## 8. Trace Service 写入边界

新增入口：

~~~java
public void recordDecisionBatch(Long taskId,
                                TaskNode completedNode,
                                OrchestrationRuntimeDecisionBatch batch)
~~~

职责：

1. 校验 taskId、batch 和 task 一致性。
2. 通过单一 assembler 把 batch 转成 immutable audit snapshot。
3. 选择代表 attempt 并生成 V1 兼容顶层字段。
4. 聚合 batch、decision、policy、mutation 和 shadow 已验证来源的 `sourceUrls`；failure discarded URLs 保持独立审计字段。
5. 只发布一条 V2 `ORCHESTRATION_DECISION_RECORDED`。

`recordDecision(...)` 保留为历史兼容入口，不删除旧测试和潜在适配调用；Task 08 完成后两个生产 runtime caller 不得继续调用它。

Trace 写入失败不能被 catch 后吞掉。outbox 是审计持久化边界，序列化/保存异常必须沿现有事务语义暴露；本任务不新增“记录失败但继续假装成功”的降级路径。

---

## 9. Runtime Caller 迁移

### 9.1 DynamicPlanAppender

把逐条 trace loop：

~~~java
for (OrchestrationRuntimeDecision attempt : batch.attempts()) {
    orchestrationTraceService.recordDecision(...);
}
~~~

替换为：

~~~java
orchestrationTraceService.recordDecisionBatch(taskId, completedNode, batch);
~~~

后续 `finalDecisions` 执行循环保持不变。V2 trace 的 `READY` 表示“允许执行”，动态计划成功仍以 `recordCheckpoint(...)` 为准。

### 9.2 DagExecutor AgentSuggestion Gate

同样每个 batch 调用一次 `recordDecisionBatch(...)`，随后只消费 `finalDecisions` 中的手工暂停 mutation。不得因完整 trace 接入改变节点成功/失败状态。

现有测试构造允许 `orchestrationTraceService == null`，迁移时必须保留防御：

~~~java
if (orchestrationTraceService != null && batch != null) {
    orchestrationTraceService.recordDecisionBatch(taskId, completedNode, batch);
}
~~~

不能把当前 `recordAgentDecisionTrace(...)` 的 null guard 在方法改写时静默删除；必须增加 trace service 为 null 时不抛 NPE、节点语义不变的回归测试。

### 9.3 防重复要求

生产代码中不得出现同一 batch 同时调用 `recordDecisionBatch` 和逐条 `recordDecision`。测试必须捕获 workflow publisher 调用次数为 1。

---

## 10. 只读模型

### 10.1 扩展 OrchestrationDecisionSummary

Task 01 字段全部保留，新增真正属于 Task 07 runtime 的字段：

~~~text
Boolean policyAllowed
List<String> policyBlockedReasons
String normalizedAction
String riskLevel
String policyVersion
String runtimeStatus
boolean fallbackAttempt
String mutationType
String mutationBranchReason
String mutationDynamicAction
String expectedResumeNodeName
~~~

兼容规则：

- V2 从代表 attempt 读取。
- V1 有 `policyResult/mutation` 时尽量读取。
- 历史事件缺失时 `Boolean/String` 保持 null、list 为空。
- 禁止把缺失的 `policyAllowed` 默认成 false，避免把“未知”展示成“被阻断”。
- `requiresConfirmation` 优先读取 Policy 结果，再回退 decision 旧字段。

### 10.2 新增周期级 OrchestrationDecisionAuditSummary

至少包含：

~~~text
traceSchemaVersion
mode
representativeDecision
attempts
finalDecisionIds
policyFallbackUsed
runtimeState
shadowRequested
shadowExecuted
shadowSkippedReason
shadowDecisions
llmFailure
sourceUrls
~~~

attempt 可以复用扩展后的 `OrchestrationDecisionSummary`；typed failure 使用窄只读 DTO，不暴露运行时 exception 类型。

### 10.3 sourceUrls 红线

`OrchestrationDecisionAuditSummary`、attempt summary、shadow summary、failure summary 均必须显式包含 `sourceUrls`。周期根对象只聚合已验证来源，report/export/replay 最终再与任务级来源去重合并；discarded URL 不进入该聚合。

---

## 11. 唯一 Projector

继续扩展 `OrchestrationDecisionSummaryProjector`，不新建第二个原始 JSON parser。

新增建议入口：

~~~java
Optional<OrchestrationDecisionAuditSummary> auditFromWorkflowEvent(
        TaskWorkflowEvent event,
        ObjectMapper objectMapper);

Optional<OrchestrationDecisionAuditSummary> auditFromEventPayload(
        Map<String, Object> payload,
        Long fallbackTaskId,
        String fallbackNodeName,
        List<String> eventSourceUrls,
        ObjectMapper objectMapper);
~~~

现有 `fromWorkflowEvent/fromEventPayload` 保持签名，内部先解析 audit，再返回 `representativeDecision`；对于 V1 继续走原兼容路径。

### 11.1 V2 规则

- 识别 `traceSchemaVersion=ORCHESTRATION_TRACE_V2` 和对象型 `audit`。
- 完整投影 attempts/final/shadow/failure/runtime state。
- 代表 decision 缺失时 audit 仍然成功。
- malformed audit 返回 empty 或降级到顶层 V1 代表字段，不抛出读取异常。
- V2 的 origin/metadata 仍从 decision 读取，不从 mode 猜 origin。

### 11.2 V1 规则

- 嵌套 `decision`、平铺 payload、legacy `inputRefs` 继续通过。
- 缺 origin 继续默认 `LEGACY_ADAPTER`。
- 可生成 `ORCHESTRATION_TRACE_V1` audit wrapper，但不伪造 attempts、mode、policy fallback 或 shadow execution。

### 11.3 Replay 摘要

`toReplaySummary(...)` 扩展为可包含：

~~~text
mode / origin / decisionType / actionType / policyAllowed / runtimeStatus /
fallbackReason / shadow status / evidenceState / reason
~~~

摘要保持一到两句，不输出完整 hash、issues 或 URL 列表；结构化字段仍保留在 timeline audit 中。

---

## 12. Report 主路径

### 12.1 ReportResponse

保留：

~~~java
private OrchestrationDecisionSummary orchestrationDecision;
~~~

新增：

~~~java
private OrchestrationDecisionAuditSummary orchestrationDecisionAudit;
~~~

旧客户端继续读取代表决策，新客户端可以读取完整周期。

### 12.2 ReportService

继续调用 `findLatestOrchestrationDecisionEvent(taskId)` 一次，从同一事件投影 summary 和 audit；禁止分别查询两条“最新”事件导致时序分叉。

必须合并：

- representative decision sourceUrls。
- audit 根 sourceUrls。
- shadow decision sourceUrls。
- failure audit 所属 outcome/shadow 的已验证 sourceUrls；discarded URLs 只保留在警告明细中。
- 现有 report/evidence/audit sourceUrls。

shadow-only V2 事件允许 `orchestrationDecision=null` 且 `orchestrationDecisionAudit!=null`。

---

## 13. Export 展示契约

### 13.1 Markdown

“协作决策摘要”至少展示：

~~~text
模式、来源、契约、决策/动作、Policy 是否允许、阻断原因、runtime 状态、
mutation 类型/branchReason、是否 Policy fallback、fallbackReason、
shadow 请求/执行/跳过原因、LLM failure type、证据状态、来源链接
~~~

有多 attempts 时按评估顺序列出简短列表，明确标记 original/fallback 和 final；不能只显示最后一条而隐藏 LLM rejection。

### 13.2 HTML

与 Markdown 消费同一 read DTO，字段语义完全一致。HTML 必须继续经过 `escapeHtml`，blocked reason、reason、providerErrorCode 和 URL 都视为不可信文本。

### 13.3 JSON

保留原 `orchestrationDecision` key 并补齐 Task 01 全部元数据和 Task 07 runtime 字段；新增 `orchestrationDecisionAudit`，输出完整结构化周期。

JSON 必须包含：

- origin/contract/model/temperature/hash/retry/fallback/shadow metadata。
- attempts 的 Policy blocked reasons、runtimeStatus 和 mutation summary。
- typed LLM failure attempts/issues/discarded URLs。
- root/attempt/shadow/failure audit 的已验证 `sourceUrls`，以及与之分离的 discarded URL 警告明细。

JSON 禁止包含 raw prompt/response、nodeTemplates、runtimeCommand、API key 或 exception message。

### 13.4 旧导出兼容

既有导出测试中的 decision ID/type/action/reason/evidence/sourceUrls 必须继续存在。新增字段只做向后兼容扩展，不重命名旧 key。

---

## 14. Replay 与 Conversation 边界

### 14.1 ReplayTimelineEvent

保留 `orchestrationDecision`，新增 `orchestrationDecisionAudit`。每个 V2 事件在 timeline 中只产生一项；不把 attempts 展开成伪 workflow events。

### 14.2 TaskReplayResponse

保留 `latestOrchestrationDecision`，新增 `latestOrchestrationDecisionAudit`。最新 audit 按 timeline 的事件顺序选择，不要求 representative decision 非空。

### 14.3 Source aggregation

replay 总 `sourceUrls` 必须合并 audit 根、attempt、shadow 和 failure audit 的已验证来源；discarded URLs 不得进入证据聚合。timeline item 自身也保留事件级来源。

### 14.4 Conversation

`ConversationOrchestrationDecisionQueryService` 继续只返回代表主路径 decision。它可以读取 V2 顶层兼容字段，但不得返回 shadow decision，也不扩展为完整审计 UI。

shadow-only 最新事件没有代表 decision 时返回 empty 是安全行为；禁止回退到 shadow candidate 生成可执行预览。

### 14.5 SSE/Map payload

任何使用 `fromEventPayload(...)` 的内存/SSE replay 路径必须与数据库事件走同一 V2 projector 测试，不能只支持数据库 JSON。

---

## 15. 持久化贯通与“不重调模型”硬约束

### 15.1 真实持久化事件贯通测试

新增 `OrchestrationDecisionVisibilityIntegrationTest`，复用仓库现有 Spring Boot + H2 测试基础设施，但不调用任何外部 LLM、搜索或 MQ 服务。

测试必须使用生产组件完成以下链路：

~~~text
typed OrchestrationRuntimeDecisionBatch fixture
  -> OrchestrationTraceService.recordDecisionBatch(...)
  -> WorkflowEventPublisher
  -> WorkflowEventOutboxService
  -> H2 task_workflow_event 持久化
  -> GET /api/report/{taskId}
  -> ExportPackageService / ReportExportRenderer（MARKDOWN、HTML、JSON）
  -> GET /api/task/{taskId}/replay
~~~

约束：

1. 不允许直接 mock `TaskWorkflowEventRepository` 返回事件。
2. 不允许直接 `repository.save(...)` 绕过生产 TraceService/outbox assembler。
3. 可以手工构造 typed batch fixture，因为 Task 09 才负责真实 Provider；但该 batch 必须经过生产持久化写侧。
4. report、三种 export 和 replay 必须断言同一 decisionId、origin、fallbackReason、runtimeStatus、shadow 状态、failure type 和 sourceUrls。
5. 从 repository 读取实际事件，断言 `traceSchemaVersion=ORCHESTRATION_TRACE_V2` 且每周期只有一条 decision event。
6. `ModelGateway` 可作为 spy/mock bean，但必须 `verifyNoInteractions`，证明可见性链路不会重调模型。

该测试通过后，Task 08 只能声称“手构 rich batch 之后的持久化与只读可见性接缝已打通”。另由
`OrchestrationRuntimeFeedbackSmokeTest` 的真实 API/DAG Rule-only 路径验证
`OrchestrationRuntimeDecisionService -> batch -> TraceService -> outbox -> replay`，并断言 V2 audit 的
coordinatorDecisions、attempts、finalDecisionIds、runtimeStatus、mutation 与空 shadow/failure 状态。该场景的
Coordinator mode 为 `RULE_ONLY`，decision 由旧修订指令适配器产生，因此 origin 保持 `LEGACY_ADAPTER`；测试显式锁定二者不能混写。

上述两条测试仍不能证明真实 Provider 产出的 LLM primary/fallback/shadow batch 正确填满；该生产侧接缝与真实 shadow quota 统一由 Task 09 证明。

### 15.2 只读源码依赖门

新增 `OrchestrationReadPathDependencyTest` 或等价 source-boundary 测试，扫描：

~~~text
ReportService
ReportExportRenderer
TaskReplayProjectionService
ConversationOrchestrationDecisionQueryService
OrchestrationDecisionSummaryProjector
~~~

禁止依赖/引用：

~~~text
ModelGateway
LlmOrchestratorDecisionBrain
OrchestratorDecisionBrain
OrchestrationDecisionService
OrchestrationRuntimeDecisionService
DecisionPolicyService
DecisionExecutorAdapter
~~~

同时增加行为测试：report/replay/export 在只有持久化 event fixture 时可以完整投影，不需要任何上述 bean 或 mock interaction。

该测试是架构门，不允许用反射或 `ApplicationContext.getBean(...)` 绕过字符串扫描。

---

## 16. 异常与安全

1. Trace assembler 接收 null batch 属于调用方契约错误，抛 `IllegalArgumentException`，不写空事件。
2. projector 遇到历史坏 JSON、错误字段类型或未知 enum 时 fail-soft，不让 report/replay 500。
3. 未知 origin 继续归为 `LEGACY_ADAPTER`；未知 runtimeStatus 在 read DTO 中保留安全文本或 null，不影响执行，因为这里只读。
4. providerErrorCode 只作为稳定代码展示，禁止展示 provider exception message。
5. Markdown/HTML 中所有模型 reason、Policy blocked reason、parse issue fieldName、discarded URL 均按不可信文本处理。
6. 不新增外部 API 调用，因此本任务没有新的 Max Retries owner；现有 outbox retry 机制保持不变。
7. `sourceUrls` 不能因 malformed audit 全部丢失，至少回退到事件列的 `sourceUrls`。

---

## 17. 文件边界

### 17.1 建议新增生产文件

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditTrace.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionTrace.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationMutationTrace.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditAssembler.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionAuditSummary.java
~~~

若 nested record 能减少文件且仍保持单一职责，可以把 runtime/mutation/failure read summary 作为 `OrchestrationDecisionAuditSummary` 的静态嵌套类型；禁止把 assembler 塞进 ReportService 或 renderer。

### 17.2 修改生产文件

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjector.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionPromptBuilder.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionResponseParser.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReplayTimelineEvent.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java
backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java
backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java
~~~

Conversation 文件只做 V2 兼容验证；原则上不扩展其 view 字段。

### 17.3 建议新增测试文件

~~~text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditTraceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditAssemblerTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditPayloadSizeTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAuditProjectorTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationReadPathDependencyTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSetTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionVisibilityIntegrationTest.java
~~~

### 17.4 修改测试文件

~~~text
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjectorTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionPromptBuilderTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionResponseParserTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationRuntimeDecisionServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java
backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/report/ReportExportRendererOrchestrationDecisionTest.java
backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayContractPresenceTest.java
~~~

### 17.5 原则上不修改

~~~text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/LlmOrchestratorDecisionBrain.java
backend/src/main/java/cn/bugstack/competitoragent/llm/ModelGateway.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java
backend/src/main/java/cn/bugstack/competitoragent/model/entity/TaskWorkflowEvent.java
backend/src/main/resources/application.yml
frontend/**
~~~

若实现必须修改原则上不修改文件，先在本文记录无法通过既有 batch/outbox 契约完成的证据，再动代码。

---

## 18. 结构化执行计划

| 任务拆解步骤 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 0 | 固定 V2 audit schema、`maxDecisionsPerCycle`、历史兼容和 46-test 绿灯基线 | 1.5-2.5 小时 | Task 07 已完成 |
| Task 1 | TDD 实现 immutable audit trace、assembler、安全字段和 payload size 边界 | 2-3 小时 | Task 0 |
| Task 2 | 通过 schema freeze gate 后新增 `recordDecisionBatch`，迁移两个 runtime caller，并保证每周期单事件/null guard | 2-3 小时 | Task 1 freeze checklist 全绿 |
| Task 3 | 扩展 Summary/Audit DTO 与唯一 projector，覆盖 V1/V2/shadow-only/malformed | 3-4 小时 | Task 2 |
| Task 4 | 接入 ReportService 和 Markdown/HTML/JSON export，补齐完整审计展示 | 2.5-3.5 小时 | Task 3 |
| Task 5 | 接入 task replay/SSE/Conversation，增加 H2/outbox 贯通与禁止模型重调测试 | 3.5-5 小时 | Task 3-4 |
| Task 6 | 分层回归、边界扫描、一次 clean package、回写实测记录并交接 Task 09 | 1.5-2 小时 | Task 0-5 |

总量按 18-24 小时预估。复杂度表示风险和验证范围，不是绝对耗时承诺。Task 3、Task 4、Task 5 是风险集中区，必须串行设置检查点：Task 3 的 V1/V2 projector 全绿后才能进入 Task 4；Task 4 三种 export 全绿后才能进入 Task 5。不得为了并行进度在多个消费方同时猜测未冻结 schema。

---

## 19. 进度记录

当前阶段：Task 08 已完成，等待 Task 09 真实 Provider/shadow 验收

- [x] 信息采集：阶段二设计/主计划、Task 01/05/06/07 handoff、trace/outbox/report/export/replay/conversation 已核对
- [x] 数据分析：V2 单周期事件、代表 attempt、typed failure、`maxDecisionsPerCycle`、真实持久化贯通、DagExecutor null guard、历史兼容和只读依赖边界已固定
- [x] 报告撰写：Task 08 可执行计划、schema freeze 硬门、TDD 分解、文件边界和验收命令已形成
- [x] 质检复核：手构 rich batch 的 H2/outbox 只读贯通与真实 Rule-only runtime batch 写侧接缝全绿；Task 09 真实 Provider/shadow/network E2E 未提前实施

- [x] Task 0：完成（cardinality、canonical V2 fixture、93 tests passed）
- [x] Task 1：完成（immutable trace、assembler、round-trip、payload < 60 KiB；13 tests passed）
- [x] Task 2：完成（单周期 V2 事件、两个 caller、null guard、Spring smoke；45 tests passed）
- [x] Task 3：完成（V1/V2/shadow-only/malformed/Map projector；14 tests passed）
- [x] Task 4：完成（Report 双投影、shadow-only、Markdown/HTML/JSON；28 tests passed）
- [x] Task 5：完成（Replay/SSE/Conversation、只读依赖门、rich fixture 的 H2/outbox 只读贯通、真实 Rule-only runtime 写侧 smoke；22+2 tests passed）
- [x] Task 6：完成（边界扫描、127 tests Task 08 回归、143 tests Task 01-07 回归、一次 clean package）

- 当前执行步骤：Task 08 收口完成
- 已完成步骤占比：计划 4/4（100%）；代码 7/7（100%）
- 当前测试：Task 08 总回归、Task 01-07 兼容回归、shadow-only export 补充回归与 clean package
- 测试结果：127 + 143 + 2 tests / 0 failures / 0 errors；clean package BUILD SUCCESS；H2/outbox integration 与 ModelGateway 0 interactions 均通过
- 剩余步骤：Task 09 阶段二真实 Provider/shadow quota/network E2E 验收
- 步骤执行状态：Task 0-6 成功；Task 09 待执行

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

## 20. Task 0：Schema 与基线冻结

### Step 1：写 V2 JSON fixture

fixture 必须覆盖同一周期：

1. `mode=LLM_PRIMARY`。
2. 原 LLM decision 被 Policy 拒绝。
3. `policyFallbackUsed=true`。
4. RULE_FALLBACK final decision。
5. 主 llmFailure 为 parse/timeout 任一 typed failure。
6. 两次模型 attempt hashes/issues/discarded URLs。
7. runtimeStatus、blockedReasons、branchReason、confirmation。
8. root/attempt/shadow/failure audit 的已验证 `sourceUrls`，以及独立的 discarded URL 警告明细。

另写 shadow-only skipped fixture，attempts/final 为空但 audit 可投影。

### Step 2：锁定历史 fixture

保留现有 current nested、flat/inputRefs、missing origin、invalid origin、non-decision payload 五类测试，禁止为 V2 删除旧断言。

### Step 3：先写并实现单周期数量硬上限

先写红灯：

- `DecisionPolicyRuleSetTest`：默认/归一化 `maxDecisionsPerCycle=2`，0/负数归一为 1。
- `OrchestrationDecisionPromptBuilderTest`：Prompt 和 response schema 明确最多 2 条。
- `OrchestrationDecisionResponseParserTest`：3 条 LLM decisions 整批返回 `TOO_MANY_DECISIONS`，不产生部分 success。
- `OrchestrationRuntimeDecisionServiceTest`：Rule/fallback outcome 超过 2 条时 fail-closed，不进入 Policy/Executor，也不构造无界 batch。

再做最小实现。该上限只约束单周期候选数量，不改变 `maxAutoDecisions` 的跨周期额度语义。

### Step 4：执行 schema freeze gate

先验证 cardinality、canonical fixture 和 V1/V2 nullable 规则，把第 7.8 节 checklist 的 Task 0 项写回进度记录。Task 1 完成 assembler round-trip 与 payload size 后关闭剩余项；五项未全部完成不得进入 Task 2。

规划基线命令继续保留：

~~~powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,ConversationOrchestrationDecisionQueryServiceTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest" test
~~~

cardinality 契约命令：

~~~powershell
mvn -pl backend "-Dtest=DecisionPolicyRuleSetTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionResponseParserTest,OrchestrationRuntimeDecisionServiceTest" test
~~~

---

## 21. Task 1：Audit Trace 与 Assembler

### Step 1：先写 immutable contract 红灯测试

覆盖：

- null 必填项拒绝。
- list/map 防御复制。
- sourceUrls trim、去空、稳定去重。
- shadow 和主 decision 不混淆。
- typed failure 不包含 raw 字段。
- mutation trace 不包含 nodeTemplates/runtimeCommand。

### Step 2：写代表 attempt 选择测试

覆盖 final last、attempt last、shadow-only null 三条规则。

### Step 3：实现最小 audit 类型与 assembler

Assembler 只做结构投影，不依赖 publisher、repository、report 或 ObjectMapper。

### Step 4：payload size 测试

按硬上限构造 2 primary + 2 fallback runtime attempts、2 shadow decisions、主/影子各 2 model attempts 的 fixture，经 Jackson 序列化后断言 UTF-8 bytes `< 60 * 1024`，并断言文本中不存在 raw prompt/response marker、nodeTemplates、runtimeCommand 和 exception message。

### Step 5：验证

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionAuditTraceTest,OrchestrationDecisionAuditAssemblerTest,OrchestrationDecisionAuditPayloadSizeTest,LlmOrchestratorDecisionFailureTest,OrchestrationRuntimeDecisionBatchTest" test
~~~

通过后回写第 7.8 节完整 freeze checklist；只有此处明确标记 5/5 后，Task 2 才能开始写真实 outbox V2 event。

---

## 22. Task 2：Batch Trace 与 Caller 迁移

### Step 1：TraceService 红灯测试

断言：

- `recordDecisionBatch` 每次只 publish 一次。
- event type 仍为 `ORCHESTRATION_DECISION_RECORDED`。
- payload 有 V2 schema、兼容顶层和完整 audit。
- shadow skipped 无 decision 时仍 publish。
- sourceUrls 为完整聚合。
- null batch 不 publish 且抛契约错误。

### Step 2：实现 recordDecisionBatch

复用 assembler；`recordCheckpoint` 不变。

### Step 3：迁移 DynamicPlanAppender

删除逐 attempt trace loop，只保留单次 batch trace；执行 final decisions 的语义和 checkpoint 时机不变。

### Step 4：迁移 DagExecutor

删除 `recordAgentDecisionTrace(...)` 的逐条写法或改为 batch helper；节点暂停逻辑不变。明确保留 `orchestrationTraceService == null || batch == null` 防御，并新增 trace service 为 null 的构造兼容/NPE 回归。

### Step 5：调用次数与节点状态回归

覆盖 LLM rejection + Rule fallback 同周期只有一个 event，以及 timeout/parse failure 不把节点改为 FAILED。

### Step 6：验证

~~~powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,OrchestrationRuntimeFeedbackSmokeTest" test
~~~

---

## 23. Task 3：Summary/Audit Projector

### Step 1：扩展 Summary 归一化红灯测试

重点验证 nullable `policyAllowed` 不被默认 false，blockedReasons/sourceUrls 去重，Policy confirmation 优先级正确。

### Step 2：V2 projector 红灯测试

覆盖：

- LLM rejection + Rule fallback。
- multiple final decisions 的代表选择和完整列表。
- shadow executed success。
- shadow skipped without decision。
- main typed failure + fallback。
- parser issues/discarded URLs。
- UNREADABLE runtime state。
- malformed audit 回退顶层代表字段。

### Step 3：V1 兼容回归

旧 5 类 projector tests 保持通过；V1 不伪造 mode/attempt/runtimeStatus。

### Step 4：Map/SSE 入口

同一 V2 fixture 分别走 `fromWorkflowEvent` 和 `fromEventPayload`，断言结果等价。

### Step 5：验证

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionSummaryProjectorTest,OrchestrationDecisionAuditProjectorTest,OrchestrationContractTest" test
~~~

---

## 24. Task 4：Report 与 Export

### Step 1：ReportService 红灯测试

断言同一最新 event 同时产生 representative summary 和 audit；shadow-only 时 representative null、audit 非 null；sourceUrls 包含 shadow 已验证来源但不包含 discarded URL。

### Step 2：接入 ReportResponse

只增加向后兼容字段，不重命名旧字段。

### Step 3：Markdown/HTML 红灯测试

断言可读展示 origin、contract、policy blocked、runtime、mutation、fallback、shadow、typed failure 和来源；HTML 对不可信文本转义。

### Step 4：JSON 红灯测试

断言旧 `orchestrationDecision` key 保留并补齐 metadata，新 audit 包含全部周期事实；反向断言禁止字段不存在。

### Step 5：实现 renderer support

Markdown/HTML/JSON 共用 normalized read DTO helper，禁止三套字段解释。

### Step 6：验证

~~~powershell
mvn -pl backend "-Dtest=ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,ReportExportContractPresenceTest" test
~~~

---

## 25. Task 5：Replay、Conversation 与只读硬边界

### Step 1：Replay timeline 红灯测试

一个 V2 batch event 只生成一个 timeline item；item 包含代表 summary 和完整 audit。

### Step 2：Latest audit 红灯测试

shadow-only 最新事件也能成为 `latestOrchestrationDecisionAudit`，不能因为没有 representative decision 回退到旧事件并掩盖最新 shadow 事实。

### Step 3：Replay sourceUrls

聚合 attempt/shadow/failure audit 的已验证 URLs，并保持旧 task/node/checkpoint 来源；反向断言 discarded URLs 不进入证据聚合。

### Step 4：Conversation 安全测试

V2 正常主路径能返回代表 decision；shadow-only 返回 empty；任何情况都不返回 LLM_SHADOW decision。

### Step 5：真实 H2/outbox 可见性贯通

实现第 15.1 节 `OrchestrationDecisionVisibilityIntegrationTest`：通过生产 TraceService 写事件，确认 repository 真实落盘后，依次调用 report API、MARKDOWN/HTML/JSON export 和 replay API。所有消费方必须看到同一周期字段。

### Step 6：禁止模型重调测试

实现第 15 节 source dependency scan，并用 event-only fixture 验证 report/export/replay/conversation 无需运行时决策 bean。

### Step 7：验证

~~~powershell
mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskReplayControllerTest,TaskEventReplayServiceTest,ConversationOrchestrationDecisionQueryServiceTest,OrchestrationReadPathDependencyTest,OrchestrationDecisionVisibilityIntegrationTest" test
~~~

---

## 26. Task 6：回归、打包与记录

### Step 1：边界扫描

- 两个 runtime caller 生产路径只调用一次 `recordDecisionBatch`。
- DagExecutor 在 trace service/batch 为 null 时保持原防御语义。
- `maxDecisionsPerCycle=2` 同时存在于 ruleSet、Prompt、Parser 和 runtime 防御。
- read paths 无 Brain/Policy/Executor/ModelGateway 依赖。
- V2 payload 无 raw prompt/response/message/nodeTemplates/runtimeCommand。
- `WorkflowEventType` 未新增枚举。
- Task 09 真实模式/E2E 未提前执行。

### Step 2：Task 08 受控总回归

~~~powershell
mvn -pl backend "-Dtest=DecisionPolicyRuleSetTest,OrchestrationDecisionAuditTraceTest,OrchestrationDecisionAuditAssemblerTest,OrchestrationDecisionAuditPayloadSizeTest,OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,OrchestrationDecisionAuditProjectorTest,OrchestrationReadPathDependencyTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,OrchestrationRuntimeDecisionBatchTest,OrchestrationRuntimeDecisionServiceTest,ConversationOrchestrationDecisionQueryServiceTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,ReportExportContractPresenceTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskReplayControllerTest,TaskEventReplayServiceTest,OrchestrationRuntimeFeedbackSmokeTest,OrchestrationDecisionVisibilityIntegrationTest" test
~~~

### Step 3：Task 01-07 兼容回归

~~~powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationDecisionActionMatrixTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,RuleBasedOrchestratorDecisionBrainTest,OrchestrationDecisionPromptBuilderTest,OrchestrationDecisionPromptInjectionTest,OrchestrationDecisionResponseParserTest,LlmOrchestratorDecisionFailureTest,LlmOrchestratorDecisionBrainTest,OrchestrationDecisionServiceTest,OrchestrationDecisionServiceLlmModeTest,OrchestrationDecisionServiceFallbackTest,OrchestrationShadowBudgetGateTest,OrchestrationRuntimeStateServiceTest,OrchestrationRuntimeDecisionServiceTest" test
~~~

### Step 4：最终一次干净打包

全部测试通过后只执行一次：

~~~powershell
mvn -pl backend clean package -DskipTests
~~~

### Step 5：回写实测记录

记录每组 tests/failures/errors、payload fixture bytes、clean package 结果、Maven warning、未执行真实 LLM/E2E 和 Task 09 handoff。

---

## 27. 完成标准

- [x] 每个 runtime decision cycle 只写一条 V2 decision event。
- [x] `maxDecisionsPerCycle=2` 在 ruleSet、Prompt、Parser 和 runtime 防御中由同一 normalized 配置生效。
- [x] primary/fallback/shadow/model attempts 的 payload 数量上界可计算，`maxAutoDecisions` 未被误用为单周期上限。
- [x] canonical V2 fixture、nullable 语义、assembler round-trip 和 payload size 的 schema freeze gate 在 Task 2 前 5/5 通过。
- [x] V2 event 持久化 coordinator main decisions、shadow execution、shadow decisions 和 typed llmFailure。
- [x] 同一 event 关联原 LLM Policy rejection 与 Rule fallback final。
- [x] runtimeStatus、fallbackAttempt、Policy blocked reasons、mutation branchReason 和 confirmation 可审计。
- [x] shadow skipped 且无 decision 仍可被 report/export/replay 展示。
- [x] 顶层代表字段保持 Task 01/Conversation 兼容。
- [x] 历史 nested/flat/inputRefs/missing-origin 事件继续兼容。
- [x] 旧事件缺失 Policy/runtime 字段时不伪造 false/READY 等事实。
- [x] report 同时返回代表 decision 和完整 audit。
- [x] Markdown/HTML/JSON 展示 origin、policy、runtime、fallback、shadow、failure、sourceUrls。
- [x] JSON 保留旧 key，新增字段为向后兼容扩展。
- [x] replay timeline 每个 batch 只有一项并含完整 audit。
- [x] latest replay audit 能表示 shadow-only 最新事件。
- [x] Conversation 永不把 LLM_SHADOW 作为主路径预览。
- [x] DagExecutor 在 trace service 或 batch 为 null 时不抛 NPE，保持原节点状态语义。
- [x] report/export/replay/conversation 不调用 Coordinator、Brain、Policy、Executor 或 ModelGateway。
- [x] Spring Boot + H2 测试将手构 rich batch 通过生产 TraceService/outbox 持久化为 V2 event，并贯通 report、MARKDOWN/HTML/JSON export 与 replay。
- [x] 只读贯通测试证明同一持久化周期字段一致且 ModelGateway interactions 为 0；Rule-only runtime smoke 另行证明真实 RuntimeDecisionService batch 可落成 V2 audit。
- [x] 不保存 raw prompt/response/API key/exception message/nodeTemplates/runtimeCommand。
- [x] payload 典型最坏 fixture 小于 60 KiB，或已有明确大文本修复证据。
- [x] 所有 structured schema 强制包含 `sourceUrls`。
- [x] malformed audit 不导致 report/replay 500，并至少保留事件列 sourceUrls。
- [x] Task 01-07 受控回归通过。
- [x] Task 08 受控总回归通过。
- [x] clean package 通过且只执行一次。
- [x] 所有业务逻辑、核心方法和复杂条件有详细中文注释。
- [x] Task 09 真实 LLM/shadow/E2E 未提前执行。
- [x] 完成声明明确区分“持久化事件可见性已打通”和“真实 Provider/shadow 已验证”，后者不得在 Task 08 宣称完成。
- [x] `STAGE2-RULE-001` 保持 OPEN。

---

## 28. 与 Task 09 的接口约束

Task 09 必须直接使用 Task 08 已持久化的 V2 audit 证明：

1. 至少一次 `LLM_PRIMARY` Policy allowed 并形成 `READY` mutation。
2. 一次 LLM Policy rejection 与 `RULE_FALLBACK` 在同一周期关联。
3. timeout/parse failure 的 typed attempts、hashes 和 fallbackReason 可见。
4. shadow 执行或预算跳过事实可见，shadow decision 未进入 attempts/final。
5. citation 未达/达到 `maxAutoDecisions` 两条路径的 runtimeStatus 可见，并确认它没有被误当成 `maxDecisionsPerCycle`。
6. section 分支上限与 confirmation gate 可见。
7. report/export/replay 读取同一持久化事实，查看过程不增加模型调用或 quota 记录。

Task 09 不得要求 Task 08 再改变 event schema；如果真实验收暴露模型决策质量问题，应记录为 fixture/模型行为问题，不能通过 replay 重调或 projector 推断掩盖。

---

## 29. 规划实测记录

~~~markdown
当前阶段：Task 08 Trace、Report、Export 与 Replay 实施完成
- [x] 信息采集：阶段二设计/主计划、Task 01/05/06/07 handoff、trace/outbox/report/export/replay/conversation 已核对
- [x] 数据分析：V2 单周期事件、代表 attempt、typed failure、单周期硬上限、真实持久化贯通、null guard、payload size 与禁止模型重调边界已固定
- [x] 报告撰写：ReportResponse、Markdown/HTML/JSON export、Replay timeline/latest audit 与 Conversation 安全边界已实现
- [x] 质检复核：Task 08 总回归、Task 01-07 兼容回归、rich fixture 的 H2/outbox 只读贯通、真实 Rule-only runtime 写侧 smoke 与 clean package 已通过

Task 08 受控总回归结果：
127 tests / 0 failures / 0 errors / BUILD SUCCESS

Task 01-07 兼容回归结果：
143 tests / 0 failures / 0 errors / BUILD SUCCESS

补充回归结果：
shadow-only Markdown/HTML/JSON export 2 tests / 0 failures / 0 errors / BUILD SUCCESS

持久化与生产侧接缝结果：
手构 rich batch -> 生产 OrchestrationTraceService -> WorkflowEventPublisher -> WorkflowEventOutboxService -> H2 task_workflow_event -> report -> MARKDOWN/HTML/JSON export -> replay 全链通过；同周期只产生 1 条 V2 event；ModelGateway interactions = 0。

真实 API/DAG Rule-only 路径已覆盖 OrchestrationRuntimeDecisionService -> batch -> TraceService -> outbox -> replay，并断言 V2 audit 的 coordinatorDecisions、attempts、finalDecisionIds、runtimeStatus、mutation 及 shadow/failure 空状态；该路径的真实组合是 mode=`RULE_ONLY`、origin=`LEGACY_ADAPTER`。

打包结果：
mvn -pl backend clean package -DskipTests / BUILD SUCCESS；本任务只执行一次 clean package。

已知环境警告：
Maven settings.xml 第 168 行仍有既有 `Unrecognised tag 'mirrors'` 警告，不影响本次编译、测试或打包。

结论边界：
Task 08 已证明手构 rich batch 之后的本地持久化/只读可见性接缝，以及真实 Rule-only RuntimeDecisionService batch 的写侧接缝。真实 Provider -> LLM primary/fallback/shadow batch -> TraceService 尚未验证，真实 shadow quota 和网络 E2E 也未执行；这些属于 Task 09，STAGE2-RULE-001 继续保持 OPEN。
~~~

### 计划自审记录：2026-07-14

- 已确认：沿用 `ORCHESTRATION_DECISION_RECORDED`，不新增事件枚举和数据库表。
- 已确认：一条 V2 event 是同周期 attempts/fallback/shadow 的原子关联边界。
- 已确认：当前代码没有单周期 decisions 硬上限；`maxAutoDecisions` 只控制跨周期自动执行，Task 08 必须新增并统一使用 `maxDecisionsPerCycle=2`。
- 已确认：schema freeze 是 Task 2 的硬进入门，不是建议顺序；V2 落库后破坏性变化必须升级版本。
- 已确认：顶层代表 attempt 只服务历史兼容，完整事实以 audit 为准，shadow 永不成为主路径代表。
- 已确认：Task 01 origin/metadata 不复制；Task 07 runtime fields 才扩展 Summary。
- 已确认：typed failure 只保存 hashes/issues/discarded URLs/provider error code，不保存 raw 内容或 exception message。
- 已确认：mutation trace 排除 nodeTemplates/runtimeCommand，避免执行模型和 outbox payload 膨胀。
- 已确认：V1 缺失字段保持 unknown/null，不把未知 Policy 状态伪造为 rejected。
- 已确认：Report、Export、Replay、Conversation 只消费 event projector，不依赖任何决策或模型 bean。
- 已确认：Task 08 必须通过生产 TraceService/outbox 写入 H2 的贯通测试，不能只凭 mock repository 单测宣称可见性修复。
- 已确认：DagExecutor 当前 null guard 是测试构造兼容边界，batch trace 迁移必须保留并单测。
- 已确认：`READY` 不等价于 mutation 已执行，真实动态计划成功仍由 checkpoint/plan event 证明。
- 已确认：sourceUrls 在 write trace、read summary、report/export/replay 持续存在。
- 已确认：本计划执行本地 Spring Boot + H2/outbox 可见性贯通，但不执行真实外部模型、真实 shadow quota 或真实网络 E2E，Task 09 边界清晰。
- 已确认：总量调整为 18-24 小时，Task 3/4/5 串行设门，不在未冻结 schema 上并行实现多个消费者。
