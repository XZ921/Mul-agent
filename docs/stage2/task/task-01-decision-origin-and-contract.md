# Task 01 决策来源与元数据契约 Implementation Plan

> 本文是阶段二第一个可独立执行的代码任务。执行者必须按 checkbox 更新进度，并把实际测试命令、结果与中断点持续写回本文，不能等任务全部完成后补记。

**Goal:** 在任何 LLM 调用、动作组合矩阵或 Service coordinator 改造之前，先建立唯一、类型安全、可持久化、可向后兼容的决策来源契约。所有新决策必须明确区分 `LLM_PRIMARY`、`LLM_SHADOW`、`RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER`，策略结果、workflow event、report 和 replay 必须消费同一份来源与元数据事实。

**Architecture:** `OrchestrationDecision` 把 `decisionOrigin` 提升为一等字段，并使用独立 `OrchestratorDecisionMetadata` 承载模型与 fallback 审计信息；`DecisionPolicyService` 只在本任务建立 origin-aware 分流入口和结果透传，不提前实现 Task 02 的动作组合矩阵；`OrchestrationTraceService` 在持久化边界统一归一化；`OrchestrationDecisionSummaryProjector` 成为 workflow event 到 report/replay 只读摘要的唯一解析入口，旧事件缺少 origin 时保守归类为 `LEGACY_ADAPTER`，绝不推断成 LLM 决策。

**Tech Stack:** Java 17, Spring Boot, Lombok, Jackson, Maven, JUnit 5, AssertJ, Mockito, existing workflow outbox.

---

## 1. 任务定位

阶段二主计划已经把第一个任务固定为 `task-01-decision-origin-and-contract`。设计文档第 10 节中较早的六步实施草案曾把“规则大脑抽取”列为 Task 1，但主计划随后将阶段拆成九个任务，并明确要求先固定 origin 契约，再实现动作矩阵和规则大脑。因此本任务以主计划为执行依据：

```text
Task 01：决策来源与元数据契约
  -> Task 02：合法动作组合矩阵与 policy 双层校验
  -> Task 03：规则大脑抽取
  -> Task 04-06：Prompt / Parser / LLM brain / Coordinator
  -> Task 07-08：运行时与审计展示贯通
```

本任务不是“先加几个字段，后面再说”。它要一次性固定后续所有阶段共同依赖的写模型、策略分流入口、事件持久化形状和读模型形状，避免 Task 06 接入模式后再反向修改 Task 01，也避免 Task 08 才发现 report/replay 丢失 LLM 来源。

---

## 2. 当前根因与证据

### 2.1 决策对象没有来源事实

当前 `OrchestrationDecision` 只有动作、理由、来源和通用 `inputRefs`，无法区分同一个动作是规则主路径、规则 fallback、LLM 主路径还是历史适配器产生：

```java
public class OrchestrationDecision {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    // 当前没有 decisionOrigin，也没有类型化的模型/fallback 元数据。
    @Builder.Default
    private Map<String, Object> inputRefs = Map.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
}
```

没有 origin 后，Task 02 无法可靠判断应该走 LLM 合法组合矩阵还是 legacy ruleSet；如果直接对所有决策套 LLM 白名单，历史 `RERUN_NODE` 会被误杀；如果继续只走 legacy ruleSet，LLM 脏组合又会进入执行链。

### 2.2 Policy 目前只有单一校验入口

当前 `DecisionPolicyService` 分别校验 `decisionType` 和派生后的 `normalizedAction`，没有来源分流：

```java
if (!rules.getAllowedDecisionTypes().contains(decision.getDecisionType())) {
    blockedReasons.add("decisionType 不在允许列表：" + decision.getDecisionType());
}

String normalizedAction = resolveNormalizedAction(decision);
if (!rules.getAllowedDynamicActions().contains(normalizedAction)) {
    blockedReasons.add("normalizedAction 不在允许列表：" + normalizedAction);
}
```

Task 01 不在这里实现动作组合矩阵，但必须让 policy 明确知道当前决策属于 `LLM_ACTION_MATRIX` 还是 `LEGACY_RULE_SET` 契约族，并把该事实写入 `DecisionPolicyResult`。Task 02 只能补充对应契约族的具体校验，不能重新设计来源识别。

### 2.3 写入链可复用，但持久化前没有统一归一化

当前 trace 会把完整对象写入 workflow event，这是可复用地基：

```java
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("summary", "Orchestrator 已生成运行期编排决策");
payload.put("decision", decision);
payload.put("policyResult", policyResult);
payload.put("mutation", mutation);
```

问题不是要重建 trace 表，而是持久化边界没有强制补齐 origin 和 metadata。只依赖上游 builder 自觉赋值，会让某个旁路构造器生成“无来源决策”，最终在 report/replay 中形成不可解释事实。

### 2.4 读取链存在重复解析接缝

`ReportService` 和正式任务回放已经复用 `OrchestrationDecisionSummaryProjector`，但 SSE replay 与 Conversation 查询仍各自解析 payload：

```text
TaskWorkflowEvent
  -> OrchestrationDecisionSummaryProjector -> ReportService
  -> OrchestrationDecisionSummaryProjector -> TaskReplayProjectionService
  -> TaskEventReplayService 自己解析 Map
  -> ConversationOrchestrationDecisionQueryService 自己解析 JsonNode
```

如果 Task 01 只修改 projector，SSE replay 和 Conversation 会静默丢掉新增字段。根因修复必须把 workflow event 的字段兼容规则集中到唯一 projector；各消费方可以保留自己的窄 DTO，但不能再解释原始事件格式。

---

## 3. 目标

1. 新增类型安全的 `OrchestrationDecisionOrigin`，固定五种来源语义和 policy 契约族映射。
2. 新增 `OrchestratorDecisionMetadata`，一次性固定阶段二已知的模型、hash、重试、shadow 和 fallback 字段形状。
3. `OrchestrationDecision.normalized()` 保证 origin、metadata、`sourceUrls`、`evidenceState` 同时稳定存在。
4. 当前规则服务直接产生的决策显式标记为 `RULE_ONLY`；`OrchestrationDecisionAdapter` 产生的历史修订决策显式标记为 `LEGACY_ADAPTER`。
5. `DecisionPolicyService` 按 origin 解析契约族，并把 `decisionOrigin`、`decisionContract` 写入 `DecisionPolicyResult`。
6. `OrchestrationTraceService` 在写 workflow event 前统一归一化 decision 和 policy result，保证持久化事件不依赖调用方是否记得归一化。
7. `OrchestrationDecisionSummary` 预埋 report/replay 所需 origin 与 metadata 字段；当前 JSON/report/replay API 能读取这些字段，Markdown/HTML 展示文案留给 Task 08。
8. `OrchestrationDecisionSummaryProjector` 成为嵌套 payload、平铺 payload、数据库事件和 SSE Map 事件的唯一兼容解析入口。
9. 历史事件没有 `decisionOrigin` 时保守归类为 `LEGACY_ADAPTER`；任何兼容逻辑都禁止把缺失来源推断成 `LLM_PRIMARY` 或 `LLM_SHADOW`。
10. `sourceUrls` 继续贯穿 decision、policy result、workflow event、summary、report 和 replay，不因新增元数据发生回退。

---

## 4. 非目标

本任务明确不处理以下能力：

- 不调用任何外部 LLM API，不修改 `ModelGateway`、Provider 配置、timeout 或 retry。
- 不实现 `decisionType -> actionType` 合法组合矩阵；该能力属于 Task 02。
- 不抽取 `RuleBasedOrchestratorDecisionBrain`；该能力属于 Task 03。
- 不实现 prompt builder、response parser 或人工标注 fixtures；该能力属于 Task 04。
- 不实现 `LlmOrchestratorDecisionBrain`、模型异常回退或解析重试；该能力属于 Task 05。
- 不引入 `RULE_ONLY / LLM_SHADOW / LLM_PRIMARY` 配置开关和 shadow 预算；该能力属于 Task 06。
- 不修改 `DynamicPlanAppender`、`DagExecutor` 或 mutation 语义；该能力属于 Task 07。
- 不完成 Markdown/HTML 导出的最终展示文案，不做前端 UI 改造；该能力属于 Task 08。
- 不修改采集、Tavily、Reviewer 评分、报告质量阈值、模板或友好样例数据。

这些非目标不是把根因后移。Task 01 必须交付后续能力所需的完整 origin 写入、policy 分流、事件持久化和只读投影契约；后续任务只在该契约上增加行为，不能再改变字段含义。

---

## 5. 核心契约决策

### 5.1 决策来源枚举

新增 `OrchestrationDecisionOrigin`：

| 来源 | 语义 | 是否允许成为主执行候选 | Policy 契约族 |
| --- | --- | --- | --- |
| `LLM_PRIMARY` | LLM 在主模式产生的正式候选决策 | 是，但仍必须经过 policy | `LLM_ACTION_MATRIX` |
| `LLM_SHADOW` | LLM 在影子模式产生的对比决策 | 否，只允许审计与 diff | `LLM_ACTION_MATRIX` |
| `RULE_ONLY` | 默认规则模式产生的主路径决策 | 是 | `LEGACY_RULE_SET` |
| `RULE_FALLBACK` | LLM 超时、异常、解析失败或被拒后产生的规则兜底决策 | 是 | `LEGACY_RULE_SET` |
| `LEGACY_ADAPTER` | `RevisionDirective` 适配或历史无 origin 事件 | 按现有 legacy policy | `LEGACY_RULE_SET` |

建议接口形状：

```java
public enum OrchestrationDecisionOrigin {
    LLM_PRIMARY,
    LLM_SHADOW,
    RULE_ONLY,
    RULE_FALLBACK,
    LEGACY_ADAPTER;

    /**
     * 历史决策和缺失来源的防御对象统一使用同一个保守默认值，
     * 禁止各调用方重复硬编码 LEGACY_ADAPTER，避免默认语义随演进发生分叉。
     */
    public static OrchestrationDecisionOrigin defaultOrigin() {
        return LEGACY_ADAPTER;
    }

    /**
     * LLM 来源必须走阶段二动作组合矩阵；规则与历史适配路径继续走 legacy ruleSet。
     * 该方法只负责选择契约族，不在 Task 01 内实现具体动作组合校验。
     */
    public String decisionContract() {
        return this == LLM_PRIMARY || this == LLM_SHADOW
                ? "LLM_ACTION_MATRIX"
                : "LEGACY_RULE_SET";
    }
}
```

约束：

- 新代码不得用任意字符串表达 origin。
- 新生产者必须显式赋值，不依赖默认值。
- `normalized()` 遇到 null origin 时必须调用 `OrchestrationDecisionOrigin.defaultOrigin()`，用于历史兼容和防御性兜底。
- `DecisionPolicyService` 的 null-decision 防御对象不得再次硬编码 `LEGACY_ADAPTER`；推荐保持 origin 为空并统一经过 `OrchestrationDecision.normalized()`，或显式调用同一个 `defaultOrigin()`。两条路径最终只能依赖这一处默认事实源。
- 不允许根据 `decisionType`、`actionType`、`modelName` 或 confidence 猜测 LLM 来源。

### 5.2 类型化决策元数据

新增 `OrchestratorDecisionMetadata`，不要继续把阶段二字段散落为 `inputRefs` 魔法字符串。`inputRefs` 继续只承载诊断、Suggestion 和触发节点引用；模型审计信息由独立对象负责。

设计文档第 7 节曾建议优先把元信息放进 `decision.inputRefs` 以避免大改 DTO，同时主计划第 6 节也预留了 `OrchestratorDecisionMetadata` 或等价轻量结构。本任务选择类型化 metadata，是因为 origin-aware policy、trace、report 和 replay 都要长期消费这些字段；若继续使用无类型 Map，每个消费者都必须复制 key、类型转换和默认值，正好会制造本阶段要消除的接缝。projector 仍兼容读取早期实验事件中的 `inputRefs` 元数据，但所有新事件只写 `decisionMetadata`。

| 字段 | 类型 | 语义 | Task 01 默认值 |
| --- | --- | --- | --- |
| `modelName` | `String` | 实际解析出的模型名 | `null` |
| `temperature` | `Double` | 实际决策温度 | `null` |
| `promptHash` | `String` | 输入 prompt hash | `null` |
| `llmResponseHash` | `String` | 原始模型响应 hash | `null` |
| `parseRetryCount` | `Integer` | JSON 解析重试次数 | `0` |
| `fallbackUsed` | `boolean` | 是否使用规则 fallback | `RULE_FALLBACK` 时强制为 `true` |
| `fallbackReason` | `String` | `LLM_TIMEOUT`、`INVALID_JSON` 等稳定原因码 | `null` |
| `shadowExecuted` | `Boolean` | shadow 是否实际调用 | `null` |
| `shadowSkippedReason` | `String` | shadow 未执行原因 | `null` |

建议对象形状：

```java
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorDecisionMetadata {

    private String modelName;
    private Double temperature;
    private String promptHash;
    private String llmResponseHash;
    @Builder.Default
    private Integer parseRetryCount = 0;
    private boolean fallbackUsed;
    private String fallbackReason;
    private Boolean shadowExecuted;
    private String shadowSkippedReason;

    /**
     * 统一清洗审计字段，避免空白原因码、负重试次数或 fallback 来源与标记互相矛盾。
     */
    public OrchestratorDecisionMetadata normalized(OrchestrationDecisionOrigin origin) {
        // 具体实现按测试约束完成，禁止在调用方复制清洗逻辑。
    }
}
```

归一化约束：

- 所有字符串 trim，空白转 `null`。
- `parseRetryCount` 小于 0 时归零。
- `decisionOrigin=RULE_FALLBACK` 时 `fallbackUsed` 必须为 `true`。
- `fallbackReason` 非空时 `fallbackUsed` 必须为 `true`。
- `shadowSkippedReason` 非空时，`shadowExecuted` 不能为 `true`。
- Task 01 不伪造 `modelName`、hash、timeout 或 fallback 原因。

### 5.3 决策写模型

`OrchestrationDecision` 新增两个字段：

```java
private OrchestrationDecisionOrigin decisionOrigin;

@Builder.Default
private OrchestratorDecisionMetadata decisionMetadata = OrchestratorDecisionMetadata.empty();
```

`normalized()` 必须同时完成：

```text
origin 归一化
  -> metadata 按 origin 归一化
  -> decision/action/priority 等现有字段归一化
  -> sourceUrls 去重
  -> evidenceState 显式化
  -> inputRefs 保留现有引用语义
```

禁止把 `decisionOrigin` 同时复制进 `inputRefs`，避免两个来源字段不一致。若 projector 为兼容早期实验事件读取到 `inputRefs.decisionOrigin`，只把它当读取 fallback，不作为新写入格式。

### 5.4 Policy 分流契约

`DecisionPolicyResult` 新增：

```java
private OrchestrationDecisionOrigin decisionOrigin;
private String decisionContract;
```

`DecisionPolicyService.evaluate(...)` 在任何动作校验前先解析 origin：

```java
OrchestrationDecision decision = normalizeDecision(rawDecision);
OrchestrationDecisionOrigin origin = decision.getDecisionOrigin();
String decisionContract = origin.decisionContract();

// Task 01 只固定契约族并写入结果；Task 02 在这里接入对应的动作组合校验。
```

Task 01 验收时必须证明：

- `LLM_PRIMARY`、`LLM_SHADOW` 得到 `LLM_ACTION_MATRIX`。
- `RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` 得到 `LEGACY_RULE_SET`。
- legacy `RERUN_NODE` 继续按现有 ruleSet 通过且需要确认，不因预埋 LLM 分流而回归。
- Task 01 不得添加一个“临时全部放行”的新 policy service，也不得复制现有 `DecisionPolicyRuleSet`。

### 5.5 Workflow event 持久化形状

新事件统一写成：

```json
{
  "summary": "Orchestrator 已生成运行期编排决策",
  "decision": {
    "decisionId": "od-50-quality_check_final-1",
    "decisionOrigin": "RULE_ONLY",
    "decisionMetadata": {
      "modelName": null,
      "temperature": null,
      "promptHash": null,
      "llmResponseHash": null,
      "parseRetryCount": 0,
      "fallbackUsed": false,
      "fallbackReason": null,
      "shadowExecuted": null,
      "shadowSkippedReason": null
    },
    "decisionType": "NO_ACTION",
    "actionType": "NO_ACTION",
    "reason": "当前终审已通过，无需追加编排动作。",
    "sourceUrls": [],
    "evidenceState": "MISSING_SOURCE"
  },
  "policyResult": {
    "decisionOrigin": "RULE_ONLY",
    "decisionContract": "LEGACY_RULE_SET"
  }
}
```

`OrchestrationTraceService.recordDecision(...)` 必须在放入 payload 前调用 `decision.normalized()` 和 `policyResult.normalized()`。不能要求每个调用方都先归一化，也不能在 `WorkflowEventPublisher` 里识别编排业务字段。

### 5.6 Report/Replay 只读形状

`OrchestrationDecisionSummary` 预埋以下字段，并在 `normalized()` 中统一清洗：

```java
private String decisionOrigin;
private String decisionContract;
private String fallbackReason;
private String modelName;
private Double temperature;
private String promptHash;
private String llmResponseHash;
private Integer parseRetryCount;
private boolean fallbackUsed;
private Boolean shadowExecuted;
private String shadowSkippedReason;
```

读模型使用字符串承载 origin/contract，保持 API 与历史 JSON 的宽容读取；写模型和内部策略仍使用 enum。

兼容读取优先级：

```text
decision.decisionOrigin
  -> decision.inputRefs.decisionOrigin（仅兼容早期实验事件）
  -> payload.decisionOrigin（兼容平铺事件）
  -> LEGACY_ADAPTER

decision.decisionMetadata.<field>
  -> decision.inputRefs.<field>（仅兼容早期实验事件）
  -> payload.<field>（兼容平铺事件）
  -> 字段默认值
```

约束：

- 历史事件缺少 origin 时必须输出 `LEGACY_ADAPTER`。
- 非法 origin 文本也必须保守降级为 `LEGACY_ADAPTER`，不能让 report/replay 解析失败。
- `decisionContract` 优先读取 `policyResult.decisionContract`；缺失时由 origin 稳定派生。
- `sourceUrls` 继续合并 decision 内来源与事件列来源，并保持去重顺序。
- Replay 只消费持久化事实，不调用 LLM、不重新执行 policy。

### 5.7 唯一 projector

`OrchestrationDecisionSummaryProjector` 至少提供两类入口：

```java
public static Optional<OrchestrationDecisionSummary> fromWorkflowEvent(
        TaskWorkflowEvent event,
        ObjectMapper objectMapper);

public static Optional<OrchestrationDecisionSummary> fromEventPayload(
        Map<String, Object> payload,
        Long fallbackTaskId,
        String fallbackNodeName,
        List<String> eventSourceUrls,
        ObjectMapper objectMapper);
```

落点要求：

- `ReportService` 继续使用 projector，不新增解析代码。
- `TaskReplayProjectionService` 继续使用 projector，不新增解析代码。
- `TaskEventReplayService` 删除自己的决策字段映射，改用 `fromEventPayload(...)`。
- `ConversationOrchestrationDecisionQueryService` 删除自己的原始 JSON 字段解析，先通过 projector 得到 summary，再映射为窄 `ConversationOrchestrationDecisionView`。
- `ConversationOrchestrationDecisionView` 不必在 Task 01 暴露全部模型 hash 字段；它只消费动作预览所需字段，但不得再解释 workflow event 格式。

---

## 6. 文件边界

### 6.1 新增

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOrigin.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionMetadata.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionOriginTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestratorDecisionMetadataTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjectorTest.java
```

### 6.2 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjector.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java
backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java
backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationContractTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapterTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java
backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java
```

### 6.3 原则上不修改

```text
backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java
backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java
backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java
backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java
backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java
backend/src/main/java/cn/bugstack/competitoragent/llm/ModelGateway.java
backend/src/main/resources/application.yml
frontend/**
```

如果实现过程中必须修改本节“原则上不修改”的文件，先在本文追加原因、影响链和替代方案比较；不能为了让单测通过随手扩大范围。

---

## 7. 结构化执行计划

| Task | 核心目标 | 预期投入（相对复杂度） | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 写 origin、metadata、历史兼容和 JSON 形状红灯测试 | M | 当前 orchestration 基线测试可运行 |
| Task 2 | 实现 `OrchestrationDecisionOrigin` 与 `OrchestratorDecisionMetadata` | M | Task 1 |
| Task 3 | 扩展 decision 写模型并给现有生产者显式赋 origin | M | Task 2 |
| Task 4 | 建立 origin-aware policy 分流入口并透传策略结果 | M | Task 3 |
| Task 5 | 固化 trace 持久化边界和唯一 summary projector | L | Task 3、Task 4 |
| Task 6 | 接通 report/replay/conversation 兼容读取并补回归 | M | Task 5 |
| Task 7 | 跑分层回归，记录结果与暴露问题 | M | Task 1-6 |

复杂度只用于表达步骤之间的相对工作量和风险，不作为绝对工时承诺或进度压力。

---

## 8. 进度记录

当前阶段：Task 01 已完成

- [x] Task 1：origin、metadata、历史兼容和 JSON 形状红灯测试，成功
- [x] Task 2：来源枚举与元数据对象实现，成功
- [x] Task 3：decision 写模型与现有生产者赋值，成功
- [x] Task 4：origin-aware policy 分流与结果透传，成功
- [x] Task 5：trace 持久化边界与唯一 projector，成功
- [x] Task 6：report/replay/conversation 兼容读取回归，成功
- [x] Task 7：分层回归与结果记录，成功

执行过程中每次暂停必须追加：

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

## 9. Task 1：先写失败测试

### Step 1：来源枚举与契约族

新增 `OrchestrationDecisionOriginTest`：

- [x] `shouldRouteLlmOriginsToLlmActionMatrixContract`
  - `LLM_PRIMARY`、`LLM_SHADOW` 返回 `LLM_ACTION_MATRIX`。
- [x] `shouldRouteRuleAndLegacyOriginsToLegacyRuleSetContract`
  - `RULE_ONLY`、`RULE_FALLBACK`、`LEGACY_ADAPTER` 返回 `LEGACY_RULE_SET`。

### Step 2：元数据归一化

新增 `OrchestratorDecisionMetadataTest`：

- [x] `shouldNormalizeFallbackMetadataWithoutLosingAuditFields`
  - 空白字符串转 null。
  - 负 `parseRetryCount` 归零。
  - `RULE_FALLBACK` 强制 `fallbackUsed=true`。
  - `modelName`、temperature、hash 保持可审计。
- [x] `shouldNormalizeContradictoryShadowExecutionState`
  - 有 `shadowSkippedReason` 时 `shadowExecuted` 归一为 false。

### Step 3：Decision 契约与序列化

修改 `OrchestrationContractTest`：

- [x] `shouldSerializeDecisionOriginMetadataAndSourceUrlsTogether`
  - JSON 同时包含 `decisionOrigin`、`decisionMetadata`、`sourceUrls`、`evidenceState`。
  - `sourceUrls=[]` 仍保留字段。
- [x] `shouldDefaultMissingOriginToLegacyAdapterWithoutPretendingLlm`
  - builder 未赋 origin，`normalized()` 后为 `LEGACY_ADAPTER`。
  - `decisionMetadata` 非 null。

### Step 4：生产者来源

修改现有测试：

- [x] `OrchestrationDecisionAdapterTest`
  - 所有适配结果为 `LEGACY_ADAPTER`。
- [x] `OrchestrationDecisionServiceTest`
  - direct rule 的 `NO_ACTION`、`WAIT_FOR_HUMAN`、Suggestion 补证/改写为 `RULE_ONLY`。
  - legacy revision directive 分支保持 `LEGACY_ADAPTER`。

### Step 5：Policy 来源分流

修改 `DecisionPolicyServiceTest`：

- [x] `shouldExposeLlmDecisionContractWithoutApplyingLegacyOrigin`
  - `LLM_PRIMARY` 的结果包含 `decisionOrigin=LLM_PRIMARY`、`decisionContract=LLM_ACTION_MATRIX`。
- [x] `shouldRequireConfirmationForRerunNode` 同时验证 legacy contract
  - `LEGACY_ADAPTER + RERUN_NODE` 仍 allowed、requiresConfirmation=true。
  - 结果包含 `decisionContract=LEGACY_RULE_SET`。
- [x] `shouldDefaultNullDecisionToLegacyAdapterContract`
  - null decision 不抛异常，不伪造 LLM 来源。

### Step 6：事件投影与历史兼容

新增 `OrchestrationDecisionSummaryProjectorTest`：

- [x] `shouldProjectOriginMetadataPolicyContractAndSourceUrlsFromCurrentEventShape`，同时验证来源合并去重
- [x] `shouldReadFlatPayloadAndLegacyInputRefsMetadata`
- [x] `shouldDefaultHistoricalEventWithoutOriginToLegacyAdapter`
- [x] `shouldDefaultInvalidOriginToLegacyAdapter`

修改：

- [x] `OrchestrationTraceServiceTest`：持久化对象已经归一化，decision 与 policyResult origin 一致。
- [x] `TaskEventReplayServiceTest`：SSE replay 能看到 origin、contract、fallbackReason。
- [x] `TaskReplayProjectionServiceTest`：正式回放 timeline 和 latest summary 能看到相同字段。
- [x] `ReportServiceTest`：报告主路径 JSON summary 能看到相同字段。
- [x] `ConversationOrchestrationDecisionQueryServiceTest`：改用统一 projector 后旧的动作预览字段和 `sourceUrls` 不回归。

红灯命令：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test
```

预期：新增测试因类、字段或 projector 入口尚不存在而失败；必须记录具体失败，不允许先写实现后补测试。

---

## 10. Task 2：实现来源与元数据对象

- [x] 新增 `OrchestrationDecisionOrigin`，只承担来源枚举和契约族映射。
- [x] 新增 `OrchestratorDecisionMetadata`，只承担审计元数据归一化。
- [x] 所有核心方法和非显然条件判断添加详细中文注释，特别说明：
  - 为什么 LLM shadow 仍属于 LLM 契约族。
  - 为什么历史缺失 origin 回退为 `LEGACY_ADAPTER`。
  - 为什么 `RULE_FALLBACK` 必须强制 `fallbackUsed=true`。
- [x] 不在枚举或 metadata 中依赖 Spring、repository、LLM client 或 workflow 对象。

局部验证：

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest" test
```

---

## 11. Task 3：扩展 Decision 并标记现有生产者

### Step 1：扩展写模型

- [x] `OrchestrationDecision` 新增 `decisionOrigin` 和 `decisionMetadata`。
- [x] `normalized()` 使用单一入口完成 origin 与 metadata 归一化。
- [x] 保留现有 `inputRefs`、`sourceUrls`、`evidenceState` 行为。
- [x] 不将 metadata 复制到 `inputRefs`。

### Step 2：显式标记当前生产者

- [x] `OrchestrationDecisionService` 的所有 direct builder 统一赋 `RULE_ONLY`。
- [x] `OrchestrationDecisionAdapter` 的正常和 manual builder 统一赋 `LEGACY_ADAPTER`。
- [x] `DecisionPolicyService` 构造的 null-decision 防御对象不重复硬编码来源，统一通过 `OrchestrationDecision.normalized()` 调用 `OrchestrationDecisionOrigin.defaultOrigin()` 得到 `LEGACY_ADAPTER`。

null-decision 与普通缺失 origin 决策必须走同一默认逻辑。对应测试既要断言最终来源为 `LEGACY_ADAPTER`，也要避免在 `DecisionPolicyService` 留下第二个默认常量；未来如果默认策略发生调整，只允许修改 `OrchestrationDecisionOrigin.defaultOrigin()` 这一处。

建议在 `OrchestrationDecisionService` 使用一个私有建造辅助方法或统一的 `withRuleOnlyOrigin(...)`，避免六处 builder 漏标；但不能趁机抽取规则大脑或改变现有决策分支。

### Step 3：保持业务行为不变

本步骤只能新增 origin/metadata，不得修改：

```text
decisionType
actionType
targetNode
affectedScope
reason
confidence
sourceUrls
evidenceState
suggestedQueries
```

局部验证：

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest" test
```

---

## 12. Task 4：建立 Origin-aware Policy 入口

- [x] `DecisionPolicyResult` 新增并归一化 `decisionOrigin`、`decisionContract`。
- [x] `DecisionPolicyService.evaluate(...)` 在现有动作校验前解析契约族。
- [x] 结果始终携带 origin 和 contract，供 trace/report/replay 审计。
- [x] `LLM_ACTION_MATRIX` 在本任务只作为稳定分流标识；未在这里实现半套组合矩阵。
- [x] legacy 规则、风险规则、自动次数、任务状态、节点状态、query 上限和 Tavily routing 行为全部保持不变。
- [x] `RERUN_NODE` 回归测试继续通过。

局部验证：

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest" test
```

---

## 13. Task 5：固化持久化边界与唯一 Projector

### Step 1：Trace 写入边界

- [x] `OrchestrationTraceService.recordDecision(...)` 写入前归一化非 null decision 和 policy result。
- [x] payload 中的 `decision`、`policyResult` 使用归一化对象。
- [x] 顶层 `evidenceState` 和 outbox `sourceUrls` 从归一化 decision 获取。
- [x] 未新增 trace 表，未修改 outbox 事务语义。

### Step 2：扩展 Summary

- [x] `OrchestrationDecisionSummary` 增加第 5.6 节字段。
- [x] `normalized()` 对 origin、contract、fallback 与 shadow 状态做宽容清洗。
- [x] 非法或缺失 origin 保守输出 `LEGACY_ADAPTER`。
- [x] `sourceUrls` 保持去重和顺序稳定。

### Step 3：统一 Projector

- [x] 抽取嵌套/平铺 decision 节点解析。
- [x] 抽取 typed metadata、legacy `inputRefs`、平铺 payload 的兼容读取。
- [x] 从 `policyResult` 读取 `decisionContract`，缺失时由 origin 派生。
- [x] 新增 Map payload 入口供 SSE replay 使用。
- [x] 所有 JSON/Map 类型转换使用 Jackson 或结构化 API，未通过字符串截取解析 JSON。
- [x] 所有兼容分支加中文注释，说明对应历史格式和安全默认值。

局部验证：

```powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest" test
```

---

## 14. Task 6：接通所有只读消费者

### Step 1：SSE Replay

- [x] `TaskEventReplayService.extractDecisionSummary(...)` 改用 projector。
- [x] 删除该类中只为 decision payload 服务的重复字段映射。
- [x] replay frame 继续只读事件，未增加任何 LLM 或 policy 依赖。

### Step 2：Conversation

- [x] `ConversationOrchestrationDecisionQueryService` 先调用 projector，再映射到 `ConversationOrchestrationDecisionView`。
- [x] 删除重复的嵌套/平铺 JSON 决策解析。
- [x] 保持“解析失败返回 empty，Conversation 回退既有预览”的容错语义。
- [x] 未扩展前端 Conversation DTO，未做展示改造。

### Step 3：正式 Report/Replay 回归

- [x] `ReportService` 未新增解析逻辑，测试已断言 summary 包含 origin/contract/metadata。
- [x] `TaskReplayProjectionService` 未新增解析逻辑，timeline 与 latest summary 保持一致。
- [x] 旧事件测试证明历史报告和 replay 不因新增字段而消失。

局部验证：

```powershell
mvn -pl backend "-Dtest=TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test
```

---

## 15. 接缝检查表

| 接缝 | Task 01 必须锁定的事实 | 验证方式 |
| --- | --- | --- |
| Producer -> Decision | 新生产者显式 origin；历史适配器不伪装规则或 LLM | Service/Adapter 单测 |
| Decision -> Policy | origin 决定 contract 族；Task 02 无需重新识别来源 | Policy 单测 |
| Decision -> Trace | 持久化前统一归一化；metadata 和 `sourceUrls` 同事件存在 | Trace payload captor |
| Trace -> Projector | 嵌套、平铺、legacy inputRefs 都走一个 parser | Projector 参数化测试 |
| Projector -> Report | summary 字段完整，旧报告兼容 | ReportServiceTest |
| Projector -> Formal Replay | timeline 与 latest decision 字段一致 | TaskReplayProjectionServiceTest |
| Projector -> SSE Replay | Map payload 不再复制字段映射 | TaskEventReplayServiceTest |
| Projector -> Conversation | Conversation 只做窄视图映射，不解释原始事件 | QueryServiceTest |
| Origin -> Task 02 | LLM 与 legacy contract 族已经固定 | Origin/Policy 测试 |
| `sourceUrls` 全链路 | 新增 metadata 不覆盖、不丢失来源 | Contract/Projector/Report/Replay 测试 |

任何一行未通过，都不能把 Task 01 标记完成。

---

## 16. 分层验收命令

### 16.1 契约层

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest" test
```

### 16.2 Policy 与执行兼容层

`DynamicPlanAppenderTest` 仅作为下游回归护栏，用于证明新增 origin/contract 字段没有破坏既有 policy -> executor -> dynamic plan 消费链；Task 01 不修改 `DynamicPlanAppender` 的业务逻辑。若该测试失败，应先定位契约透传是否回归，不能直接改 `DynamicPlanAppender` 迁就新字段。

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,DynamicPlanAppenderTest" test
```

### 16.3 Trace 与投影层

```powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test
```

### 16.4 Task 01 总回归

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test
```

本任务不要求前端改动。为确认 JSON DTO 扩展没有意外破坏既有阶段一展示，可选跑：

```powershell
npm.cmd --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts NodeAccordionList.test.tsx
```

---

## 17. 完成标准

只有同时满足以下条件，Task 01 才能完成：

- [x] 五种 origin 的语义和契约族映射由 enum 固定，不存在散落字符串判断。
- [x] 当前规则生产者为 `RULE_ONLY`，历史 directive 适配器为 `LEGACY_ADAPTER`。
- [x] null/历史/非法 origin 只会保守降级为 `LEGACY_ADAPTER`，不会伪装 LLM。
- [x] `OrchestratorDecisionMetadata` 已包含后续 LLM、fallback、shadow 所需稳定字段形状。
- [x] `DecisionPolicyResult` 明确输出 `decisionOrigin` 与 `decisionContract`。
- [x] legacy `RERUN_NODE` 行为未被 LLM contract 预埋逻辑误杀。
- [x] workflow event 中 decision、policy result、metadata、`sourceUrls` 同时可审计。
- [x] report、正式 replay、SSE replay 使用同一 projector 兼容规则。
- [x] Conversation 不再自行解析原始 decision payload。
- [x] 历史无 origin 事件仍能生成 report/replay 摘要。
- [x] Task 01 分层回归全部通过，命令和结果已写回本文。
- [x] 没有修改采集、质量评分、模板、LLM Provider、DAG mutation 或前端 UI。

---

## 18. 与后续任务的接口约束

### 交给 Task 02

Task 02 只能基于：

```text
decision.decisionOrigin
  -> decisionOrigin.decisionContract()
  -> LLM_ACTION_MATRIX 或 LEGACY_RULE_SET
```

实现组合矩阵。禁止 Task 02 再通过 action、modelName、inputRefs 或调用路径推断来源。

### 交给 Task 03

规则大脑抽取后：

- `RULE_ONLY` 模式保留 `RULE_ONLY`。
- coordinator 触发 fallback 时覆盖为 `RULE_FALLBACK`，并补充 fallback metadata。
- 规则业务判断本身不负责推断运行模式。

### 交给 Task 05/06

LLM brain 和 coordinator 只能填充既有 `decisionMetadata`，不得新增平行的 model/fallback map。`LLM_SHADOW` 只能写 trace，是否进入 executor 由后续模式任务控制。

### 交给 Task 08

Task 08 负责 Markdown/HTML/前端可视化文案，但只消费 Task 01 已固定的 `OrchestrationDecisionSummary` 字段。若 Task 08 需要新增字段，必须证明该字段不是本任务已知的 origin、fallback、model、hash、retry 或 shadow 元数据，避免反向返工写模型。

---

## 19. 实测记录

### 完成记录：2026-07-13 15:05

```markdown
当前阶段：Task 01 决策来源与元数据契约已完成
- [x] 信息采集：现有 decision、policy、trace、report/replay/conversation 链路已核对
- [x] 数据分析：origin 默认值、契约族和历史事件兼容边界已固定
- [x] 报告撰写：不适用；本任务未修改报告正文生成逻辑
- [x] 质检复核：受控回归与打包编译已通过

红灯命令：
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test

红灯结果：测试编译失败，失败点均为计划新增的 origin、metadata、summary 字段和 projector 入口不存在；主代码编译成功。

Policy/执行兼容命令：
mvn -pl backend "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,DynamicPlanAppenderTest" test

结果：Tests run: 15, Failures: 0, Errors: 0, BUILD SUCCESS。

Trace/投影命令：
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,ReportServiceTest,ConversationOrchestrationDecisionQueryServiceTest" test

结果：Tests run: 45, Failures: 0, Errors: 0, BUILD SUCCESS。

最终 Task 01 受控回归：
mvn -pl backend "-Dtest=OrchestrationDecisionOriginTest,OrchestratorDecisionMetadataTest,OrchestrationContractTest,OrchestrationDecisionAdapterTest,OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,OrchestrationDecisionSummaryProjectorTest,TaskEventReplayServiceTest,TaskReplayProjectionServiceTest,DagExecutorTest,DynamicPlanAppenderTest,ReportServiceTest,ReportExportRendererOrchestrationDecisionTest,ConversationOrchestrationDecisionQueryServiceTest" test

结果：Tests run: 119, Failures: 0, Errors: 0, BUILD SUCCESS。

完整 backend 回归：
mvn -pl backend test

结果：Surefire 共记录 Tests run: 1188, Failures: 13, Errors: 0, Skipped: 5。13 个失败集中在阶段一 Citation/Collector/Writer、旧集成基线、coverage 与 WorkflowFactory 断言，没有 Task 01 涉及的 orchestration、policy、trace、report/replay/conversation 测试失败；本任务不越界修改这些既有失败。

打包命令：
mvn -pl backend -DskipTests package

结果：BUILD SUCCESS，生成 backend/target/backend-1.0-SNAPSHOT.jar。

暴露问题：Maven 全程提示全局 settings.xml 第 168 行存在未识别 mirrors 标签；不影响本次编译、受控测试和打包，本任务未修改全局 Maven 配置。
```
