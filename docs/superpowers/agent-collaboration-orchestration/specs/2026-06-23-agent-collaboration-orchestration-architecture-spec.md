# 3.4 Agent 协作编排架构规格

> 2026-06-23 版本。本文定义 `Agent 协作编排层` 的目标架构、契约边界、风险收口和分阶段落地口径。它服务于“AI 驱动的竞品分析 Agent 协作系统”主旨，不把现有系统退化成普通后端流水线，也不提前制造不可控的超级 Agent。

---

## 1. 文档目标

本文回答 3 个问题：

1. 当前系统已经有 Collector / Extractor / Analyzer / Writer / Reviewer 和 DAG 执行器，为什么仍然不像真正的 Agent 协作系统。
2. Orchestrator Agent 应该处在什么位置，如何和现有 DAG、动态补图、质量回流、对话动作入口分工。
3. 3.4 阶段先设计哪些稳定契约，哪些能力只做最小闭环，哪些必须后移。

本文不是下列事项：

1. 不是推倒重做 `DagExecutor` 的计划。
2. 不是把所有节点都交给 LLM 自由规划的自治运行时。
3. 不是 Citation Agent 的完整实现计划。
4. 不是分析、写作、质检、交付链路的替代诊断文档。

---

## 2. 阶段定位

3.4 应放在 `3.3 提取结构化` 证据边界基本稳定之后、分析推理 / 报告写作 / 质量审查 / 修订重写全面固化之前。

原因是：

1. Orchestrator 需要稳定输入。没有 `sourceUrls / evidenceCoverage / DownstreamEvidenceView / ExtractResult`，Orchestrator 只能凭自然语言猜测。
2. Orchestrator 会影响后续链路。如果等 9 条业务链路全部完成后再补，会把 Analyzer、Writer、Reviewer、Conversation、Delivery 中的隐式决策再拆一遍。
3. 3.4 不应一次做完全部自治，而应先把协作协议立稳，让后续链路按同一协议接入。

因此，本阶段策略是：

```text
目标架构一次设计清楚
协议边界一次冻结
执行能力分阶段接入
每一阶段都做真实闭环验证
```

---

## 3. 设计结论

3.4 的目标架构采用 `Supervisor / Manager-Worker + Graph Workflow + Durable Execution + Guardrails / Tracing` 的组合模式，而不是把任意一个外部框架照搬进 Java 后端。

成熟架构给本系统的启发如下：

| 成熟模式 | 可借鉴优点 | 本系统取舍 |
| --- | --- | --- |
| [LangChain Multi-agent Supervisor](https://docs.langchain.com/oss/python/langchain/multi-agent) | 主 Agent 统一协调专职 subagents，路由集中经过主 Agent | OrchestratorAgent 作为竞品调研组长，统一生成协作计划和反馈决策 |
| [LangGraph Persistence / Human-in-the-loop](https://docs.langchain.com/oss/python/langgraph/persistence) | 状态图、checkpoint、暂停恢复、人工介入 | 复用现有 DAG / checkpoint / 人工确认能力，不引入 Python 运行时 |
| [OpenAI Agents SDK Handoffs / Tracing](https://openai.github.io/openai-agents-python/handoffs/) | 专职 Agent 间可交接任务，运行过程可追踪 | 用结构化契约表达 handoff，不让 Agent 自由互调 |
| [Temporal Durable Execution](https://docs.temporal.io/) | 长任务可恢复、失败后可从中断点继续 | 由 `DagExecutor / DynamicTaskGraphService / Checkpoint` 承担可恢复执行 |

因此，3.4 的完整目标架构必须是 Orchestrator-first 的双阶段架构：

```text
用户任务 / 竞品分析目标
        |
        v
OrchestratorAgent 前置规划
        |
        v
CollaborationGoal / CollaborationPlan / AgentRoleAssignment
        |
        v
InitialPlanReview 校验计划风险、预算、角色分工和证据红线
        |
        v
ExecutionPlanDefinitionBuilder / WorkflowFactory
        |
        v
WorkflowPlan / DAG / DagExecutor
        |
        v
Collector / Extractor / Analyzer / Writer / Reviewer 专职执行
        |
        v
AgentSuggestion / QualityDiagnosis / EvidenceState
        |
        v
OrchestratorAgent 反馈决策
        |
        v
OrchestrationDecision
        |
        v
DecisionPolicyRuleSet / DecisionPolicyResult
        |
        v
DecisionExecutorAdapter / DynamicPlanMutation
        |
        v
DynamicTaskGraphService / DagExecutor 安全执行、持久化、回放
```

这里的关键不是让 Orchestrator 直接执行任务，而是让它先成为协作规划入口，再成为运行中反馈决策入口。

核心分工：

| 层 | 负责什么 | 不负责什么 |
| --- | --- | --- |
| 业务 Agent | 采集、抽取、分析、写作、质检等专业能力 | 不决定全局编排动作 |
| OrchestratorAgent | 任务开始时生成协作计划，运行中根据上下文决定补采、重跑、改写、跳过、人工介入 | 不直接拼 DAG 节点，不直接写库，不绕过策略校验 |
| CollaborationPlan / InitialPlanReview | 表达调研目标、角色分工、阶段依赖、质量门槛和计划风险 | 不等同可执行 DAG，不直接调度 Agent |
| DecisionPolicyRuleSet / DecisionPolicyResult | 定义并执行 Orchestrator 决策校验，保证动作合法、安全、可追溯 | 不生成业务判断 |
| ExecutionPlanDefinitionBuilder / WorkflowFactory | 把已校验协作计划映射成现有 WorkflowPlan / DAG | 不承担 LLM 推理和业务事实判断 |
| DynamicTaskGraphService | 把已校验决策落成计划版本和动态节点 | 不自行推导业务动作 |
| DagExecutor | 并发调度、依赖校验、重试、状态持久化、恢复 | 不承担 LLM 决策职责 |
| Evidence / Citation 层 | 保证结论可回指来源，后续升级为 Citation Agent | 3.4 不做完整 Citation Agent |

一句话边界：

```text
Orchestrator 负责协作规划和反馈决策，DAG 负责可恢复执行，Evidence 契约负责无幻觉追溯。
```

---

## 4. 当前风险与必须收口的问题

### 4.1 Reviewer 与 Orchestrator 决策权混在一起

当前 `RevisionDirective` 同时表达质量问题和编排动作：

```text
category / targetSection / summary / sourceUrls
+ actionType / orchestrationAction / targetNode
```

其中 `orchestrationAction` 已经是编排层动作，例如：

```text
CREATE_SUPPLEMENT_BRANCH
CREATE_RERUN_BRANCH
CREATE_REWRITE_BRANCH
MANUAL_ONLY
```

这会导致：

1. Reviewer 既判断质量，又间接决定动态补图。
2. `RevisionDirective.normalized()` 会根据 category 自动推导编排动作。
3. Orchestrator 落地后会和 Reviewer / RevisionDirective 争夺决策权。

3.4 必须拆分：

```text
Reviewer 输出 QualityDiagnosis / QualityFinding
Orchestrator 输出 OrchestrationDecision
```

过渡期可以保留 `RevisionDirective`，但它必须降级为兼容对象或展示对象，不再作为长期正式编排协议。

### 4.2 隐式 Orchestrator 分散在多个位置

当前隐式编排逻辑至少分散在以下对象中：

| 位置 | 当前职责 | 3.4 目标归属 |
| --- | --- | --- |
| `CompensationGraphAssembler` | 根据 `RevisionDirective.orchestrationAction` 生成动态 DAG 节点 | 执行 `OrchestrationDecision` 的图模板适配器 |
| `RevisionDirective.normalized()` | 推导 `actionType / orchestrationAction / targetNode` | 只保留诊断归一或迁移为 legacy 兼容 |
| `DynamicTaskGraphService` | 基于修订指令生成动态计划版本 | 基于已校验决策生成动态计划版本 |
| `ClarificationOrchestrator` | 对话层槽位澄清 | 对话动作引擎入口，避免和全局 Orchestrator 混名 |
| `DagExecutor.shouldExecuteNode()` | 根据 trigger 判断节点是否执行 | 保留安全执行判断，不承担高层业务决策 |
| `RecoveryEngine / NodeExecutionRecoveryPolicy` | 根据失败状态决定恢复建议 | 长期只保留恢复策略执行器角色；过渡期提供恢复建议作为 Orchestrator 输入，不再独立决定全局恢复编排 |
| `TaskActionTranslator / ConversationService` | 把用户语言翻译为动作预览 | 后续接入 Orchestrator 决策，不重复做全局编排 |

3.4 第一版不要求一次重构所有对象，但规格必须承认这些隐式编排点，并规定最终收口方向。

### 4.3 Citation Agent 可以后移，但 sourceUrls 红线不能后移

总蓝图已经把 `sourceUrls` 定为跨链路无幻觉红线。Citation Agent 可以在后续阶段成为独立角色，但 3.4 的所有新契约必须从第一天保留来源链路。

因此，以下对象必须携带 `sourceUrls` 或明确的证据缺口状态：

```text
AgentSuggestion
QualityDiagnosis / QualityFinding
OrchestrationDecision
DecisionPolicyRuleSet
DecisionPolicyResult
DecisionExecutorAdapter
DynamicPlanMutation
DecisionTrace
OrchestratorCheckpoint
TaskActionPreview
```

如果没有来源，不能静默省略，必须显式表达：

```json
{
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "reason": "当前结论缺少可追溯来源，只允许触发补证或人工介入。"
}
```

`evidenceState` 第一版只使用以下状态：

| evidenceState | 含义 |
| --- | --- |
| `FULL_SOURCE` | 当前判断已有可回指来源 |
| `PARTIAL_SOURCE` | 当前判断有部分来源，但不足以支撑强结论 |
| `MISSING_SOURCE` | 当前判断缺少来源，只能触发补证、降级或人工介入 |
| `NOT_APPLICABLE` | 非证据产物，例如策略配置本身，不承载业务事实来源 |

### 4.4 LLM Orchestrator 黑盒化风险

Orchestrator 不能每个节点、每个事件都调用 LLM。否则会带来：

1. 成本不可控；
2. 延迟不可控；
3. 决策不可复现；
4. 测试难以稳定；
5. 安全策略容易被自然语言绕过。

3.4 只允许在关键检查点调用 Orchestrator：

| 检查点 | 是否进入 3.4 MVP | 说明 |
| --- | --- | --- |
| 任务开始前协作规划 | 是，P0 契约 / P2 执行 | 先冻结 `CollaborationPlan` 契约，执行接入晚于终审回流 MVP |
| 任务开始后计划复核 | 是，P2 | Orchestrator 复核 `WorkflowPlan` 是否符合协作计划，不直接重写 DAG |
| 采集完成后证据充分性判断 | 可选 | 依赖 3.3 输入质量稳定程度 |
| 抽取完成后字段缺口判断 | 是，P2 | 读取 `ExtractResult / evidenceCoverage` |
| 分析完成后结论可靠性判断 | 后续 | 需先完成分析推理专题 |
| 初审 / 终审失败后回流决策 | 是，MVP 主线 | 复用现有动态补图基础 |
| 用户对话动作确认 | 后续 | 先保持动作预览和人工确认边界 |

---

## 5. 目标架构分层

### 5.1 Agent 专业能力层

现有 Agent 不推倒，继续作为专业能力模块：

| Agent | 当前能力 | 3.4 增量 |
| --- | --- | --- |
| Collector | 多源搜索、采集、审计、回放 | 输出采集充分性、失败原因、补采建议 |
| Extractor | 结构化提取、字段证据、issueFlags | 输出字段缺口、证据覆盖和可重跑信号 |
| Analyzer | 横向对比、SWOT、风险机会 | 输出结论置信度、分析缺口、需要补证的维度 |
| Writer | 报告生成、改写、章节证据 | 输出章节缺口、引用缺口、不可写原因 |
| Reviewer | 质量审核、交叉验证、修订建议 | 只输出质量诊断，不输出最终编排动作 |
| Citation | 来源核实、引用标注 | 3.4 后移，sourceUrls 契约先保留 |

### 5.2 协作决策层

新增 Orchestrator 相关协作者：

| 对象 | 职责 |
| --- | --- |
| `OrchestratorAgent` | 任务开始时输出结构化协作计划，运行中读取任务现场和诊断信号并输出结构化编排决策 |
| `CollaborationContextProvider` | 汇总用户目标、竞品对象、业务维度、预算、组织约束和可用 Agent 能力 |
| `OrchestrationContextProvider` | 汇总任务、节点、证据、诊断、预算、历史决策 |
| `InitialPlanReviewService` | 校验协作计划能否落成现有 DAG，检查角色覆盖、阶段依赖、预算和 sourceUrls 红线 |
| `DecisionPolicyService` | 基于 `DecisionPolicyRuleSet` 校验动作合法性、sourceUrls、预算、风险等级和人工确认要求，并输出 `DecisionPolicyResult` |
| `DecisionExecutorAdapter` | 把已校验决策转换为现有运行时命令或动态计划变更 |
| `OrchestrationTraceService` | 持久化每次决策输入摘要、输出、策略校验结果和 sourceUrls |
| `CollaborationCheckpointStore` | 持久化前置协作规划推进位置和计划复核状态 |
| `OrchestratorCheckpointStore` | 持久化运行期 Orchestrator 当前推进位置和可恢复游标 |

### 5.3 执行运行层

执行层继续保留现有职责：

```text
WorkflowPlan / TaskPlan
        |
        v
DagExecutor
        |
        v
AgentCapabilityRegistry
        |
        v
业务 Agent
```

Orchestrator 不直接绕过 DAG 调 Agent。所有动作必须落回现有执行层，保证：

1. 依赖校验仍有效；
2. 重试机制仍有效；
3. 节点状态仍可恢复；
4. SSE / replay / AgentLog 仍可观察；
5. 任务计划版本仍可追溯。

### 5.4 证据与审计层

3.4 不建设完整 Citation Agent，但必须让所有决策能被追溯：

```text
EvidenceFragment
SectionEvidenceBundle
DownstreamEvidenceView
CollaborationPlan
QualityDiagnosis
OrchestrationDecision
DecisionTrace
ReportResponse / ExportRecord
```

这些对象都必须保留 `sourceUrls` 或显式缺口状态。

---

## 6. 核心契约草案

### 6.1 CollaborationGoal

`CollaborationGoal` 是 Orchestrator 前置规划的输入对象，来自用户任务、对话澄清、组织约束和可用资料。它回答“这次竞品分析到底要完成什么”。

```json
{
  "goalId": "cg-001",
  "taskId": 50,
  "subject": "B 站创作者商业化能力竞品分析",
  "competitors": ["Bilibili", "YouTube", "抖音"],
  "analysisDimensions": ["pricing", "creator_tools", "monetization", "risk"],
  "deliverableType": "COMPETITOR_REPORT",
  "depth": "STANDARD",
  "budget": {
    "maxSearchQueries": 20,
    "maxModelCalls": 12,
    "maxAutoDecisions": 5
  },
  "constraints": {
    "requireSourceUrls": true,
    "allowDynamicBranch": true,
    "requiresHumanConfirmationForRerun": true
  },
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE"
}
```

规则：

1. `CollaborationGoal` 不是最终报告结论，只是协作规划输入。
2. 用户尚未提供来源时可以是 `MISSING_SOURCE`，但后续采集、分析、写作不能把它当成可靠证据。
3. 预算、维度、交付物类型必须进入计划生成上下文，不能散落在 prompt 自然语言里。

### 6.2 CollaborationPlan / AgentRoleAssignment

`CollaborationPlan` 是 Orchestrator 前置规划的输出对象，对标成熟 Supervisor 架构中的 manager plan。它不是 DAG，而是可审计、可校验、可映射到 DAG 的协作计划。

```json
{
  "planId": "cp-001",
  "goalId": "cg-001",
  "taskId": 50,
  "planningMode": "ORCHESTRATOR_FIRST",
  "agentRoleAssignments": [
    {
      "roleId": "role-collector-01",
      "agentType": "COLLECTOR",
      "mission": "收集各竞品 pricing、creator tools、monetization 的公开来源。",
      "expectedOutputs": ["EvidenceFragment", "CollectionAudit"],
      "dependsOn": [],
      "qualityGate": "sourceUrls must not be empty"
    },
    {
      "roleId": "role-extractor-01",
      "agentType": "EXTRACTOR",
      "mission": "把采集内容提取为结构化字段和 sectionEvidenceBundles。",
      "expectedOutputs": ["ExtractResult", "AgentSuggestion"],
      "dependsOn": ["role-collector-01"],
      "qualityGate": "evidenceCoverage must cover requested dimensions"
    },
    {
      "roleId": "role-reviewer-01",
      "agentType": "REVIEWER",
      "mission": "检查报告结论是否有来源、是否存在证据缺口。",
      "expectedOutputs": ["QualityDiagnosis"],
      "dependsOn": ["role-writer-01"],
      "qualityGate": "no ERROR severity without Orchestrator decision"
    }
  ],
  "checkpoints": [
    {
      "checkpointName": "after_extract_schema",
      "purpose": "检查字段证据覆盖是否足以进入分析。",
      "orchestratorTrigger": "OPTIONAL"
    },
    {
      "checkpointName": "quality_check_final",
      "purpose": "终审失败后进入 Orchestrator 反馈决策。",
      "orchestratorTrigger": "REQUIRED_ON_FAILURE"
    }
  ],
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE"
}
```

规则：

1. `CollaborationPlan` 由 Orchestrator 生成，但必须经过 `InitialPlanReview` 才能映射为 `WorkflowPlan`。
2. `AgentRoleAssignment` 表达角色使命、输入依赖、预期产物和质量门槛，不表达具体 Java 节点实现。
3. 计划中必须显式列出 Orchestrator 检查点，避免所有节点都调用 LLM。
4. `ExecutionPlanDefinitionBuilder / WorkflowFactory` 只消费已校验计划，并负责映射到现有 DAG 模板。

### 6.3 InitialPlanReview

`InitialPlanReview` 是前置协作计划的策略校验结果。它借鉴 guardrails 思路，阻止 Orchestrator 生成无法落地、越权或无来源红线的计划。

```json
{
  "reviewId": "ipr-001",
  "planId": "cp-001",
  "allowed": true,
  "blockedReasons": [],
  "requiredAdjustments": [],
  "mappedWorkflowTemplate": "STANDARD_COMPETITOR_ANALYSIS_V1",
  "policyRuleRefs": [
    "agentRoleCoverage",
    "dagTemplateCompatibility",
    "budgetWithinLimit",
    "sourceUrlsOrEvidenceStateRequired"
  ],
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE"
}
```

规则：

1. `allowed=false` 时不得生成正式 `WorkflowPlan`，只能返回澄清、人工确认或降级计划。
2. 校验必须覆盖角色是否完整、依赖是否能映射到 DAG、预算是否超限、checkpoint 是否过密。
3. `InitialPlanReview` 和 `DecisionPolicyResult` 分属不同阶段：前者校验初始协作计划，后者校验运行期反馈决策。

### 6.4 CollaborationCheckpoint

`CollaborationCheckpoint` 记录前置协作规划和计划复核推进到哪里，和运行期的 `OrchestratorCheckpoint` 分开。

```json
{
  "checkpointId": "cc-001",
  "taskId": 50,
  "goalId": "cg-001",
  "planId": "cp-001",
  "lastReviewId": "ipr-001",
  "phase": "PLAN_APPROVED",
  "mappedWorkflowPlanId": 27,
  "pendingActions": [],
  "resumeReason": "协作计划已通过初始校验，等待 WorkflowPlan 执行。",
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "createdAt": "2026-06-23T00:00:00",
  "updatedAt": "2026-06-23T00:00:00"
}
```

规则：

1. `CollaborationCheckpoint` 回答“初始协作规划推进到哪里”，`OrchestratorCheckpoint` 回答“运行期反馈决策推进到哪里”。
2. 前置计划复核失败时，checkpoint 必须保留失败原因和待补充信息。
3. 该对象对标 durable workflow 的恢复游标，但只覆盖计划阶段，不覆盖节点执行阶段。

### 6.5 AgentSuggestion

业务 Agent 输出的建议，不直接等同编排动作。

```json
{
  "producerNodeName": "extract_schema",
  "producerAgentType": "EXTRACTOR",
  "suggestionType": "EVIDENCE_GAP",
  "targetSection": "pricing",
  "summary": "pricing 字段只有来源页但缺少可验证价格信息。",
  "severity": "HIGH",
  "confidence": 0.78,
  "sourceUrls": ["https://example.com/pricing"],
  "evidenceState": "PARTIAL_SOURCE",
  "suggestedQueries": ["Example pricing plans"],
  "suggestedTargetNode": "collect_sources"
}
```

规则：

1. Agent 可以提出建议，但不能决定最终编排动作。
2. 建议必须绑定来源或显式说明缺口。
3. 建议可以作为 Orchestrator 输入，也可以用于前端解释。

### 6.6 QualityDiagnosis / QualityFinding

Reviewer 输出质量事实，不输出最终编排动作。

```json
{
  "diagnosisId": "qd-001",
  "category": "EVIDENCE_TRACEABILITY",
  "targetSection": "pricing",
  "severity": "ERROR",
  "finding": "报告声称存在免费额度，但证据列表中没有价格页或定价表引用。",
  "expectedEvidence": "官方 pricing 页面、文档计费说明或可信公开价格说明。",
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE"
}
```

规则：

1. Reviewer 可以说明问题和期望证据。
2. Reviewer 不输出 `CREATE_SUPPLEMENT_BRANCH` 等编排动作。
3. Reviewer 的诊断必须能进入报告诊断、任务回放和 Orchestrator 上下文。

### 6.7 OrchestrationDecision

Orchestrator 的正式输出。

```json
{
  "decisionId": "od-001",
  "decisionType": "APPEND_DYNAMIC_BRANCH",
  "actionType": "SUPPLEMENT_EVIDENCE",
  "targetNode": "collect_sources",
  "affectedScope": "CURRENT_NODE_AND_DOWNSTREAM",
  "priority": "HIGH",
  "reason": "pricing 章节存在 ERROR 级证据缺口，当前来源不足以支撑报告结论。",
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "suggestedQueries": ["Example pricing plans", "Example enterprise pricing"],
  "requiresHumanIntervention": false,
  "requiresConfirmation": false,
  "confidence": 0.84,
  "inputRefs": {
    "qualityDiagnosisIds": ["qd-001"],
    "agentSuggestionIds": [],
    "triggerNodeName": "quality_check_final"
  }
}
```

允许的 `decisionType` 第一版只保留有限集合：

| decisionType | 含义 |
| --- | --- |
| `APPEND_DYNAMIC_BRANCH` | 追加补采 / 抽取 / 分析 / 改写 / 复核分支 |
| `RERUN_NODE` | 从指定节点重跑影响范围 |
| `REWRITE_ONLY` | 仅改写报告，不补证 |
| `WAIT_FOR_HUMAN` | 进入人工介入 |
| `NO_ACTION` | 不追加动作，继续或结束 |

第一版禁止：

1. 由 Orchestrator 任意生成未知节点类型；
2. 绕过 `DecisionPolicyRuleSet / DecisionPolicyResult` 直接执行；
3. 没有 sourceUrls 或缺口状态却触发自动补证；
4. 在高风险动作上跳过人工确认。

### 6.8 DecisionPolicyRuleSet

策略规则对象定义“哪些 Orchestrator 决策可以被执行”。它可以先以内置配置或 YAML 形式存在，但协议层必须把规则显式化，避免策略继续散落在 Java 私有方法里。

```json
{
  "policyVersion": "ORCHESTRATION_POLICY_V1",
  "allowedDecisionTypes": [
    "APPEND_DYNAMIC_BRANCH",
    "RERUN_NODE",
    "REWRITE_ONLY",
    "WAIT_FOR_HUMAN",
    "NO_ACTION"
  ],
  "allowedDynamicActions": [
    "CREATE_SUPPLEMENT_BRANCH",
    "CREATE_RERUN_BRANCH",
    "CREATE_REWRITE_BRANCH",
    "MANUAL_ONLY"
  ],
  "requireSourceUrlsOrEvidenceGap": true,
  "maxAutoDecisions": 5,
  "maxDynamicBranchesPerSection": 2,
  "maxSearchQueriesPerDecision": 5,
  "confirmationRequiredDecisionTypes": ["RERUN_NODE"],
  "blockedTaskStatuses": ["STOPPED"],
  "blockedNodeStatuses": ["RUNNING"],
  "riskRules": [
    {
      "when": "decisionType == 'RERUN_NODE' && affectedScope == 'CURRENT_NODE_AND_DOWNSTREAM'",
      "riskLevel": "HIGH",
      "requiresConfirmation": true
    },
    {
      "when": "evidenceState == 'MISSING_SOURCE' && actionType != 'SUPPLEMENT_EVIDENCE'",
      "riskLevel": "HIGH",
      "requiresConfirmation": true
    }
  ],
  "sourceUrls": [],
  "evidenceState": "NOT_APPLICABLE"
}
```

规则：

1. `allowedDecisionTypes` 控制 Orchestrator 第一版能输出什么动作。
2. `allowedDynamicActions` 控制动态补图模板能翻译成什么运行时动作。
3. `requireSourceUrlsOrEvidenceGap=true` 表示决策必须携带 `sourceUrls` 或显式证据缺口状态。
4. `maxAutoDecisions` 和 `maxDynamicBranchesPerSection` 是循环补图保护。
5. `confirmationRequiredDecisionTypes` 决定哪些动作默认进入动作确认或人工介入。
6. `riskRules` 第一版可以先由代码解释，不要求立刻建设完整规则引擎。

### 6.9 DecisionPolicyResult

策略校验结果，决定 Orchestrator 输出能否执行。

```json
{
  "decisionId": "od-001",
  "allowed": true,
  "riskLevel": "MEDIUM",
  "requiresConfirmation": false,
  "blockedReasons": [],
  "normalizedAction": "CREATE_SUPPLEMENT_BRANCH",
  "policyRuleRefs": ["allowedDecisionTypes", "requireSourceUrlsOrEvidenceGap"],
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "policyVersion": "ORCHESTRATION_POLICY_V1"
}
```

策略必须检查：

1. 动作是否在白名单内；
2. 目标节点是否存在或可由模板生成；
3. 是否有 sourceUrls 或明确的 `MISSING_SOURCE` 缺口；
4. 是否超过任务预算；
5. 是否需要人工确认；
6. 是否会造成循环补图；
7. 是否符合当前任务状态。

### 6.10 DecisionExecutorAdapter / DynamicPlanMutation

`DecisionExecutorAdapter` 是“已校验决策 -> 现有运行时命令”的翻译层。它不重新做业务判断，也不直接绕过 DAG 执行业务 Agent。

输入：

```json
{
  "decision": {
    "decisionId": "od-001",
    "decisionType": "APPEND_DYNAMIC_BRANCH",
    "actionType": "SUPPLEMENT_EVIDENCE",
    "targetNode": "collect_sources",
    "sourceUrls": [],
    "evidenceState": "MISSING_SOURCE"
  },
  "policyResult": {
    "decisionId": "od-001",
    "allowed": true,
    "normalizedAction": "CREATE_SUPPLEMENT_BRANCH",
    "requiresConfirmation": false,
    "sourceUrls": [],
    "evidenceState": "MISSING_SOURCE",
    "policyVersion": "ORCHESTRATION_POLICY_V1"
  }
}
```

输出 `DynamicPlanMutation`：

```json
{
  "mutationId": "dpm-001",
  "decisionId": "od-001",
  "mutationType": "APPEND_NODES",
  "targetPlanVersionId": 27,
  "branchReason": "ORCHESTRATOR_DECISION",
  "dynamicAction": "CREATE_SUPPLEMENT_BRANCH",
  "nodeTemplates": [
    {
      "agentType": "COLLECTOR",
      "displayName": "补充证据采集",
      "dependsOn": ["quality_check_final"],
      "nodeConfig": {
        "sourceType": "SUPPLEMENTAL",
        "searchQueries": ["Example pricing plans"],
        "sourceUrls": [],
        "evidenceState": "MISSING_SOURCE"
      }
    }
  ],
  "runtimeCommand": null,
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "expectedResumeNodeName": "collect_revision_evidence_v2_1"
}
```

允许的 `mutationType` 第一版只保留：

| mutationType | 含义 |
| --- | --- |
| `APPEND_NODES` | 追加动态分支节点 |
| `RERUN_FROM_NODE` | 生成从指定节点重跑的运行时命令 |
| `MARK_WAITING_INTERVENTION` | 标记任务或节点进入人工介入 |
| `NO_MUTATION` | 策略允许但无需改变 DAG |

规则：

1. Adapter 只消费 `allowed=true` 的 `DecisionPolicyResult`。
2. Adapter 输出的是计划变更或运行时命令，不直接执行外部 API。
3. `DynamicPlanMutation` 必须携带 `decisionId`，方便回放时从节点反查决策。
4. `expectedResumeNodeName` 用于写入 Orchestrator 检查点。
5. `sourceUrls` 为空时必须同步写入 `evidenceState`，不能让动态计划变更变成无来源的隐式动作。
6. `CompensationGraphAssembler` 长期应从消费 `RevisionDirective` 迁移为消费 `DynamicPlanMutation` 或其模板输入。

### 6.11 DecisionTrace

每次决策必须持久化可回放摘要。

```json
{
  "decisionId": "od-001",
  "taskId": 50,
  "triggerNodeName": "quality_check_final",
  "inputSummary": "终审失败，pricing 缺少可追溯来源。",
  "decisionType": "APPEND_DYNAMIC_BRANCH",
  "policyAllowed": true,
  "executionStatus": "APPLIED",
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "createdAt": "2026-06-23T00:00:00"
}
```

`DecisionTrace` 是追加式审计日志，回答“系统做过什么决策”。它不承担恢复游标职责。

### 6.12 OrchestratorCheckpoint

`OrchestratorCheckpoint` 记录 Orchestrator 决策流程当前推进到哪里、下一次恢复应从哪里继续。它对标采集链路中的 `collectionAuditCheckpoint`，但作用域是协作决策层。

```json
{
  "checkpointId": "oc-001",
  "taskId": 50,
  "planVersionId": 27,
  "branchKey": "root/review-2",
  "lastDecisionId": "od-001",
  "lastMutationId": "dpm-001",
  "pendingActions": ["WAITING_FOR_SUPPLEMENT_RESULT"],
  "decisionCount": 2,
  "maxAutoDecisions": 5,
  "resumeAfterNodeName": "collect_revision_evidence_v2_1",
  "resumeReason": "补充证据采集完成后需要重新评估 pricing 证据缺口。",
  "sourceUrls": [],
  "evidenceState": "MISSING_SOURCE",
  "createdAt": "2026-06-23T00:00:00",
  "updatedAt": "2026-06-23T00:00:00"
}
```

规则：

1. `DecisionTrace` 记录历史，`OrchestratorCheckpoint` 记录当前恢复位置，两者不能混用。
2. 每次自动决策后必须更新 `decisionCount`，防止无限自治循环。
3. `pendingActions` 表示 Orchestrator 等待什么事件后恢复，例如补证节点完成、人工确认、重跑完成。
4. `resumeAfterNodeName` 必须指向已有节点或已计划生成的动态节点。
5. 当任务进入 `WAITING_INTERVENTION / STOPPED / FAILED` 时，checkpoint 必须保留最后一次可解释状态，不能被清空。

---

## 7. RevisionDirective 迁移策略

### 7.1 当前问题

`RevisionDirective` 当前承担 3 类职责：

1. 质量问题摘要；
2. 修订建议展示；
3. 动态补图动作。

长期来看，这 3 类职责必须拆开：

```text
QualityDiagnosis / QualityFinding：质量事实
AgentSuggestion：子 Agent 建议
OrchestrationDecision：编排决策
RevisionDirective：兼容期展示 / 修订说明
```

### 7.2 兼容期规则

3.4 MVP 可以暂时保留 `RevisionDirective`，但必须增加边界说明：

1. 新代码不得把 `RevisionDirective.orchestrationAction` 当成唯一正式决策来源。
2. `CompensationGraphAssembler` 的长期输入应从 `List<RevisionDirective>` 迁移为 `List<OrchestrationDecision>` 或 `DynamicPlanMutation`。
3. `RevisionDirective.normalized()` 不应继续新增新的编排推导规则。
4. Reviewer prompt 不再要求模型输出编排动作，只输出质量诊断和证据缺口。

### 7.3 目标接口

长期目标：

```text
QualityReviewAgent
  -> QualityCheckResult
  -> QualityDiagnosis[]
  -> OrchestratorAgent
  -> OrchestrationDecision[]
  -> DecisionPolicyRuleSet / DecisionPolicyResult
  -> DecisionExecutorAdapter
  -> DynamicPlanMutation
  -> DynamicTaskGraphService / DagExecutor
  -> DecisionTrace + OrchestratorCheckpoint
```

---

## 8. MVP 闭环范围

3.4 的目标架构是 Orchestrator-first，但第一条可运行闭环仍建议选择“终审失败后的质量回流决策”。原因是当前系统已有动态补图基础，风险可控；前置 `CollaborationPlan` 先冻结契约，再进入计划生成接入。

完整协作闭环分成两段：

```text
前置规划段：
用户目标 -> CollaborationGoal -> OrchestratorAgent -> CollaborationPlan
        -> InitialPlanReview -> WorkflowPlan / DAG -> CollaborationCheckpoint

运行反馈段：
业务 Agent 产物 -> AgentSuggestion / QualityDiagnosis
        -> OrchestratorAgent -> OrchestrationDecision
        -> DecisionPolicyResult -> DynamicPlanMutation
        -> DynamicTaskGraphService / DagExecutor
        -> DecisionTrace + OrchestratorCheckpoint
```

MVP 流程：

```text
quality_check_final 输出 failed + QualityDiagnosis
        |
        v
OrchestrationContextProvider 汇总报告、证据、诊断、sourceUrls
        |
        v
OrchestratorAgent 输出 OrchestrationDecision
        |
        v
DecisionPolicyRuleSet / DecisionPolicyResult 校验
        |
        v
DecisionExecutorAdapter 输出 DynamicPlanMutation
        |
        v
DynamicTaskGraphService / DagExecutor 安全执行
        |
        v
追加补采 / 抽取 / 分析 / 改写 / 复核动态分支
        |
        v
DecisionTrace 写入回放与前端展示
        |
        v
OrchestratorCheckpoint 记录下次恢复游标
```

MVP 不做：

1. 不接管所有节点的调度；
2. 不立即重写任务初始计划，但会冻结 `CollaborationPlan / InitialPlanReview` 契约；
3. 不让 Orchestrator 直接调用 Collector / Writer；
4. 不做完整 Citation Agent；
5. 不把对话入口变成自治执行入口。

---

## 9. 分阶段落地

### P0：规格与契约冻结

目标：避免后续继续按流水线思维扩散。

任务：

1. 新增本规格文档；
2. 在总蓝图中加入 `Agent 协作编排引擎`；
3. 明确 `QualityDiagnosis` 与 `OrchestrationDecision` 的职责边界；
4. 列出隐式编排点和迁移方向；
5. 明确所有新契约必须携带 `sourceUrls` 或证据缺口状态；
6. 补齐前置规划契约 `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint`；
7. 补齐运行反馈契约 `DecisionPolicyRuleSet / DecisionPolicyResult / DecisionExecutorAdapter / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` 的协议闭环。

### P1：终审失败回流 MVP

目标：把已有动态补图从“Reviewer 指令驱动”升级为“Orchestrator 决策驱动”。

任务：

1. 定义 `AgentSuggestion`、`QualityDiagnosis`、`OrchestrationDecision`、`DecisionPolicyRuleSet`、`DecisionPolicyResult`、`DecisionExecutorAdapter`、`DynamicPlanMutation`、`DecisionTrace`、`OrchestratorCheckpoint`；
2. 新增 `OrchestratorAgent` 或规则优先的 `OrchestrationDecisionService` 第一版；
3. `QualityReviewAgent` 输出质量诊断，不再新增编排动作语义；
4. 增加适配层，把兼容期 `RevisionDirective` 转成 `OrchestrationDecision`；
5. `DecisionPolicyRuleSet` 控制白名单、sourceUrls / 证据缺口、预算、人工确认和循环补图上限；
6. `DecisionExecutorAdapter` 把已校验决策翻译成 `DynamicPlanMutation`；
7. `DynamicPlanAppender / DynamicTaskGraphService` 支持消费已校验的计划变更；
8. 决策过程进入日志、任务事件或 replay；
9. `OrchestratorCheckpoint` 记录决策推进位置，支持 resume 后继续判断。

### P2：前置协作规划接入 + 抽取后证据缺口决策

目标：让 Orchestrator 真正成为任务开始时的协作规划入口，同时能基于 `ExtractResult / evidenceCoverage` 判断是否补采或继续分析。

任务：

1. 新增 `CollaborationGoal` 组装入口，读取用户任务、竞品对象、分析维度、预算和约束；
2. Orchestrator 输出 `CollaborationPlan / AgentRoleAssignment`；
3. `InitialPlanReviewService` 校验计划可落地、预算、sourceUrls 红线和 checkpoint 密度；
4. `ExecutionPlanDefinitionBuilder / WorkflowFactory` 支持消费已校验的 `CollaborationPlan`，映射到现有 DAG 模板；
5. `CollaborationCheckpoint` 记录计划阶段的恢复游标；
6. Extractor 输出 `AgentSuggestion`；
7. Orchestrator 读取字段缺口和 evidenceCoverage；
8. 对 `NO_BUSINESS_FIELDS_EXTRACTED / FIELD_MISSING_EVIDENCE / LOW_QUALITY_EVIDENCE` 建立决策策略；
9. 防止无来源结论继续进入 Analyzer / Writer。

### P3：分析、写作、对话、Citation 扩展

目标：让后续链路按同一协议接入，而不是各自再写隐式编排。

范围：

1. Analyzer 输出分析置信度和需要补证的维度；
2. Writer 输出章节引用缺口；
3. Conversation 的动作预览读取 OrchestrationDecision；
4. Citation Agent 独立核查引用覆盖和来源可信度；
5. Delivery / Audit 展示完整协作决策轨迹。

---

## 10. 失败处理与安全边界

### 10.1 Orchestrator 调用失败

前置规划阶段 Orchestrator 调用 LLM 失败时，必须降级到规则化默认计划或人工确认：

```text
用户目标完整 + 标准竞品分析 -> STANDARD_COMPETITOR_ANALYSIS_V1 默认计划
用户目标缺维度 / 缺竞品对象 -> WAIT_FOR_CLARIFICATION
预算或权限不明确 -> WAIT_FOR_HUMAN
```

运行反馈阶段 Orchestrator 调用 LLM 失败时，必须降级到规则策略：

```text
QualityDiagnosis ERROR + MISSING_SOURCE -> WAIT_FOR_HUMAN 或规则化补证
低风险表达问题 -> REWRITE_ONLY
决策不完整 -> WAIT_FOR_HUMAN
```

不能因为 Orchestrator 失败就让任务伪装成功，也不能生成不可解释的自由 DAG。

### 10.2 决策循环保护

必须防止无限补图：

1. 同一 `targetSection + actionType` 的自动补图次数必须有上限；
2. 连续补证仍无法改善 evidenceCoverage 时进入人工介入；
3. 动态分支必须记录 parent decisionId；
4. 每次自动补图必须更新 `OrchestratorCheckpoint.decisionCount`；
5. 回放中必须能看到每次补图原因。

### 10.3 前置计划安全边界

`CollaborationPlan` 必须受以下边界约束：

1. 只能映射到已登记的 `WorkflowPlan` 模板或受控动态模板；
2. `AgentRoleAssignment.agentType` 必须来自 Agent 能力注册表；
3. 计划中的 checkpoint 不能超过策略上限，避免每个节点都触发 LLM；
4. 初始计划不能跳过 Collector / Evidence 层直接要求 Writer 输出强结论；
5. `InitialPlanReview.allowed=false` 时不得启动正式任务执行。

### 10.4 人工确认边界

以下动作默认需要人工确认或进入 `WAITING_INTERVENTION`：

1. 删除或覆盖已有报告；
2. 跨大范围重跑；
3. 多次自动补图失败后继续补图；
4. 缺少 sourceUrls 却试图生成强结论；
5. 高成本外部搜索或抓取超出预算。

---

## 11. 当前不做

3.4 不做以下事情：

1. 不把 `DagExecutor` 改成 LLM 驱动循环。
2. 不把 `ExecutionPlanDefinitionBuilder` 改成动态智能规划器。
3. 不让 Orchestrator 自由生成任意节点。
4. 不移除现有 `RevisionDirective`，只冻结迁移方向。
5. 不做完整 Citation Agent。
6. 不接管对话入口的所有动作执行。
7. 不重构全部 Analyzer / Writer / Reviewer 输出。
8. 不降低 `sourceUrls` 红线。
9. 不引入新的 Python Agent 运行时或外部 workflow 引擎作为 3.4 前置条件。

---

## 12. 验收标准

### 架构验收

1. 总蓝图能明确说明 `Agent 协作编排引擎` 不是第十条业务链路，而是横跨任务执行、质量回流和对话动作的协作决策层。
2. Orchestrator 双阶段职责清晰：任务开始输出 `CollaborationPlan`，运行中输出 `OrchestrationDecision`。
3. Reviewer 与 Orchestrator 的职责拆清：Reviewer 输出质量事实，Orchestrator 输出编排决策。
4. `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint` 与运行期决策契约共同存在，且不互相替代。
5. 所有新契约都包含 `sourceUrls` 或证据缺口状态。
6. 隐式编排点清单进入文档，不再只盯 `DagExecutor`。
7. Citation Agent 后移，但 Citation 所需的来源契约不后移。
8. `DecisionTrace` 和 `OrchestratorCheckpoint` 职责拆清：前者记录历史审计，后者记录恢复游标。
9. `DecisionExecutorAdapter` 只翻译已校验决策，不重新做业务判断。

### 实现验收

后续实施进入 P1 时，至少满足：

1. `quality_check_final` 失败后能生成可回放的 `OrchestrationDecision`；
2. `DecisionPolicyRuleSet / DecisionPolicyResult` 能阻止非法或高风险动作；
3. `DecisionExecutorAdapter` 能输出 `DynamicPlanMutation`，并把决策翻译成动态补图、重跑或人工介入命令；
4. 动态补图不再直接依赖 Reviewer 输出的 `orchestrationAction` 作为唯一来源；
5. 决策轨迹能在任务日志、事件或 replay 中查看；
6. `OrchestratorCheckpoint` 能记录 `lastDecisionId / pendingActions / resumeAfterNodeName / decisionCount`；
7. 老任务的 `RevisionDirective` 仍可兼容读取。

后续实施进入 P2 时，至少满足：

1. 任务开始时能生成可回放的 `CollaborationPlan`；
2. `InitialPlanReview` 能阻止无法映射到现有 DAG 模板的计划；
3. `ExecutionPlanDefinitionBuilder / WorkflowFactory` 能消费已校验的协作计划，而不是从自然语言直接生成任意节点；
4. `CollaborationCheckpoint` 能记录 `goalId / planId / lastReviewId / mappedWorkflowPlanId / phase`；
5. 前置计划和运行期反馈决策能在同一任务 replay 中串起来。

2026-06-24 P2 自动化实现记录：规则优先的 `CollaborationGoal -> CollaborationPlan -> InitialPlanReview` 已接入 `WorkflowFactory / ExecutionPlanDefinitionBuilder`，只向现有标准 DAG 投影角色、质量门槛和 checkpoint 元数据；`COLLABORATION_PLAN_RECORDED / COLLABORATION_CHECKPOINT_UPDATED` 已进入 replay；`extract_schema` 输出后的 `AgentSuggestion` 已接入 `OrchestrationDecisionService / DagExecutor`，缺业务字段或缺来源证据时会进入受策略保护的补证或人工介入路径。P2 聚合测试、P1+P2 编排聚合测试与 backend 全量回归均已通过；既有 `ArchitectureWhitelistTest` ledger 路径问题已于同日解除。

2026-06-24 P3-1 自动化实现记录：Analyzer 输出新增 `analysisConfidence / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState`，`AnalyzerSuggestionAssembler` 已把分析缺口转换成标准 `AgentSuggestion`；`OrchestrationDecisionService / DagExecutor` 已支持 `analyze_competitors` 触发的受策略保护决策，缺来源时进入 `WAIT_FOR_HUMAN -> WAITING_INTERVENTION`，有来源时保留受审计放行轨迹。`DagExecutorTest`、`CollaborationPlanningSmokeTest`、`TaskReplayProjectionServiceTest`、P3-1 局部聚合、P1+P2+P3-1 编排聚合与 `mvn -pl backend test` 全量回归均已通过；Writer、Conversation 和 Citation 仍未进入本轮范围。

2026-06-24 P3-2 执行 1 自动化实现记录：Writer 输出新增 `writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps`，`WriterSuggestionAssembler` 已把章节引用缺口转换成标准 `AgentSuggestion`；`OrchestrationDecisionService / DagExecutor` 已支持 `write_report / rewrite_report` 触发的受控决策，无来源章节缺口进入 `WAIT_FOR_HUMAN -> WAITING_INTERVENTION`，有来源但引用不完整的章节记录 `REWRITE_ONLY / REWRITE_SECTION` 决策轨迹。Citation Agent 仍未进入本轮范围。

2026-06-24 P3-3 自动化实现记录：Conversation 动作预览已接入最近一次 `ORCHESTRATION_DECISION_RECORDED` 事件，只读提取 `OrchestrationDecision` 摘要并展示到统一对话入口；`TaskActionTranslator` 已支持 `SUPPLEMENT_EVIDENCE / REWRITE_ONLY / WAIT_FOR_HUMAN` 三类决策预览映射，`WAIT_FOR_HUMAN` 不生成确认执行对象；前端 `TaskActionPreviewCard` 已展示 Orchestrator 决策原因、证据状态和来源链接。Conversation 仍不创建编排决策，不直接执行人工介入动作，Citation Agent 仍留在 P3-4。

### live 验收

以真实竞品分析任务验证：

1. 终审失败时，系统能解释缺什么证据、来源在哪里、为什么选择补采 / 改写 / 人工介入；
2. 补图后新分支能正确进入采集、抽取、分析、写作和复核；
3. 中断恢复后，Orchestrator 能依据 checkpoint 判断是否继续等待补证结果、继续决策或进入人工介入；
4. 决策过程全程可追溯；
5. 没有来源的结论不会被自动升级成可靠报告结论。
6. 任务开始时，系统能解释为什么选择这些 Agent 角色、阶段顺序、质量门槛和检查点。

---

## 13. 相关文档

- [AI 竞品分析 Agent 协作系统业务全景与功能优化路线图设计](../../../specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md)
- [3.4 P1 终审失败回流 MVP 可执行计划](../task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)
- [3.4 P2 前置协作规划与抽取后证据缺口决策可执行计划](../task/2026-06-24-agent-collaboration-orchestration-p2-collaboration-plan-implementation-plan.md)
- [3.3 提取结构化架构规格](../../ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md)
- [提取结构化链路诊断](../../ExtractionStructured/problem/ExtractionStructured.md)
- [搜索与采集架构 1 设计](../../search-and-collection/specs/2026-06-17-search-and-collection-architecture-design.md)
