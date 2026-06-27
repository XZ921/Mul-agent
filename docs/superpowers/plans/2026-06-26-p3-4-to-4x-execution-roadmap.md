# P3-4 To 4.x Execution Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 固化当前从 P3-4 Citation 验证确认到 4.x 架构修改的执行路线，明确下一步先做什么、每一步产出什么、何时可以执行 Tavily Fast Lane MVP。

**Architecture:** 本路线采用“验证确认 -> 侦察 -> 收敛决策 -> 分档架构修改 -> 回补实现”的节奏。P3-4 已实现，当前只做三条测试命令的验证确认；3.5 只写限幅诊断不实现；4.x 拆为 4.0 最小 runtime contract 和 4.1 动态运行时迁移。4.x 首轮只稳定主业务链路，不把 ConversationCollaboration 作为前置阻塞项；对话协同后置为 runtime contract 的消费端、安全确认网关和受控动作入口。Tavily 可在 3.5 诊断触发后先作为 fail-open 搜索增强接入，后续再注册为正式 capability。

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, ArchUnit, Markdown planning docs, existing `workflow / orchestration / agent / search / collection` modules.

---

## 0. 总路线

```text
现在
  -> P3-4 Citation 验证确认
  -> 3.3 / 3.4 红线稳定
  -> 3.5 四份诊断，只诊断不实现
  -> 找收敛点
      -> 收敛不充分：哪条链路痛感最强，先链路内修复哪条
      -> 证据来源问题明确：可先执行 Tavily fail-open 轻量接入
      -> 协作运行时问题收敛充分：进入 4.0 / 4.1 架构修改
  -> 回补链路实现
      -> 搜索证据补强
      -> Writer / Reviewer / Citation 补证闭环
      -> Conversation 只在主链路 runtime 稳定后作为消费端接入
```

Tavily Fast Lane 不在 P3-4 或红线冻结前执行。若 3.5 诊断确认“证据来源不足 / 搜索采集质量低 / Playwright 兜底过重”已经影响 Writer / Citation / Reviewer，可以先按 fail-open 搜索增强路径执行轻量 Tavily MVP；4.x 完成后再把 Tavily 注册为正式 capability。

4.x 首轮不把对话协同作为必须完成的链路。ConversationCollaboration 诊断只用于确认 runtime 需要暴露哪些可解释状态和安全动作边界；首轮实现仍以 `采集 -> 提取 -> 分析 -> 写作 -> Citation -> 质检 -> 修订/重写 -> 交付/审计` 为主链路。

## 1. 当前不做

- 不在 P3-4 验证确认和 3.3/3.4 红线冻结前执行 `docs/Travily/tavily-fast-lane-mvp-execution-plan.md`。
- 不在 P3-4 验证确认和 3.3/3.4 红线冻结前修改 `SourceCandidate / SearchSourceProvider / SearchExecutionCoordinator / CollectionTaskPackage`。
- 不把 4.x 当成固定排期；4.x 是诊断收敛后的架构决策。
- 不把 ConversationCollaboration 作为 4.x 首轮前置阻塞项；对话协同只在主链路 runtime contract 稳定后接入为消费端和安全确认入口。
- 不在 3.5 诊断阶段实现链路修复。
- 不把 Tavily 当作替代千帆 / SerpApi / Playwright 的方案。

---

## 2. Task 1: P3-4 Citation 验证确认

**Files:**
- Review: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-25-agent-collaboration-orchestration-p3-4-citation-agent-implementation-plan.md`
- Review: `docs/specs/project-evolution-roadmap.md`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CitationSuggestionAssemblerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`

P3-4 已实现完毕，本任务不再逐 step 实施，只做验证确认。三条命令全部 PASS，则 Task 1 直接标记完成；若任一命令失败，只修失败对应的回归，不扩展 Citation 新功能。

- [ ] **Step 1: 确认 DAG 顺序**

确认 `write_report -> citation_check -> quality_check` 与 `rewrite_report -> citation_check_revision -> quality_check_final` 顺序仍稳定。

```bash
cd backend
mvn -Dtest=WorkflowFactoryTest test
```

Expected: PASS。

- [ ] **Step 2: 确认 CitationSuggestionAssembler**

确认 Citation 问题仍能转成标准 `AgentSuggestion`，且保留 `sourceUrls / evidenceState`。

```bash
cd backend
mvn -Dtest=CitationSuggestionAssemblerTest test
```

Expected: PASS。

- [ ] **Step 3: 确认 Orchestration 决策链路**

确认 Citation 缺口进入 Orchestrator 后，动作仍受控为 `WAIT_FOR_HUMAN / REWRITE_ONLY / APPEND_DYNAMIC_BRANCH / NO_ACTION` 范围内。

```bash
cd backend
mvn -Dtest=OrchestrationDecisionServiceTest test
```

Expected: PASS。

- [ ] **Step 4: 标记 Task 1 完成**

三条命令全部 PASS 后，在执行记录或路线图备注中记录：

```text
P3-4 Citation Agent:
  状态：验证确认完成
  证据：WorkflowFactoryTest / CitationSuggestionAssemblerTest / OrchestrationDecisionServiceTest
  未完成项：无；若有失败，只列回归修复项
```

完成标准：Citation 已经是 Reviewer 前的稳定门禁，且 Citation 问题可以进入 Orchestrator 决策链。

---

## 3. Task 2: 3.3 / 3.4 红线冻结

**Files:**
- Modify: `docs/specs/project-evolution-roadmap.md`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Review: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentSuggestion.java`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DynamicPlanMutation.java`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CitationCheckResult.java`
- Verify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/QualityDiagnosis.java`

限幅要求：本任务只产出红线冻结段落和协议清单，不写新方案；新增正文不超过 800 字。

- [ ] **Step 1: 冻结协作协议字段**

以下对象必须进入冻结清单：

```text
AgentSuggestion
OrchestrationDecision
DecisionPolicyResult
DynamicPlanMutation
QualityDiagnosis
CitationCheckResult
EvidenceState
sourceUrls
```

冻结含义：

```text
字段名不随意改
缺证据必须显式表达
人工介入必须显式表达
动态补图必须显式表达
所有关键问题都能回指 sourceUrls 或 evidenceState
```

- [ ] **Step 2: 写红线冻结段落**

在路线图文档中补充或确认一段：

```text
3.3 / 3.4 红线冻结：
  sourceUrls 是所有证据、诊断、引用、质量问题的追溯底线。
  evidenceState 是缺证据场景的一等字段。
  Agent 只输出事实和建议，Orchestrator 输出决策，Policy 决定是否执行。
  3.5 诊断不得发明另一套缺口协议。
```

- [ ] **Step 3: 跑协作协议测试**

Run:

```bash
cd backend
mvn -Dtest="OrchestrationContractTest,CollaborationContractTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest" test
```

Expected: PASS。

- [ ] **Step 4: 冻结完成判定**

完成标准：

```text
后续 3.5 诊断只能基于上述协议指出接缝问题；
不能再临时新增一套 missingEvidence / citationGap / unsupportedClaim 表达。
```

---

## 4. Task 3: 3.5 四份诊断

**Files:**
- Create: `docs/superpowers/analysis-reasoning/problem/AnalysisReasoning.md`
- Create: `docs/superpowers/report-writing/problem/ReportWriting.md`
- Create: `docs/superpowers/conversation-collaboration/problem/ConversationCollaboration.md`
- Create: `docs/superpowers/delivery-audit/problem/DeliveryAudit.md`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

限幅要求：每份诊断不超过 2000 字，最多引用 8 个代码级证据点。诊断目标是足够做出收敛决策，不追求完整论文式分析。

- [ ] **Step 1: 写 AnalysisReasoning 诊断**

必须回答：

```text
1. 当前真实停点是什么？
2. 停点是否与 3.4 协作协议有接缝？
3. 不改架构，只改分析链路内部，最多能修到什么程度？
```

代码级证据至少覆盖：

```text
CompetitorAnalysisAgent
AnalysisResult
CompetitorKnowledgeDraft
SectionEvidenceBundle
AgentSuggestion
```

篇幅上限：不超过 2000 字。

- [ ] **Step 2: 写 ReportWriting 诊断**

必须回答：

```text
1. Writer 当前真实停点是什么？
2. Writer 输出的 citationGap / sourceUrls / evidenceState 是否能被 3.4 协议稳定消费？
3. 不改架构，只改 Writer prompt 或章节组织，最多能修到什么程度？
```

代码级证据至少覆盖：

```text
ReportWriterAgent
WriterCitationGap
WriterSuggestionAssembler
SectionEvidenceBundle
Report
```

篇幅上限：不超过 2000 字。

- [ ] **Step 3: 写 ConversationCollaboration 诊断**

必须回答：

```text
1. 对话入口当前真实停点是什么？
2. Conversation 是否只是展示 OrchestrationDecision，还是需要触发受控动作？
3. 不改架构，只改 ConversationService / ModeRouter，最多能修到什么程度？
```

代码级证据至少覆盖：

```text
ConversationService
IntentRecognitionService
ModeRouter
TaskActionTranslator
ConversationOrchestrationDecisionQueryService
```

篇幅上限：不超过 2000 字。

- [ ] **Step 4: 写 DeliveryAudit 诊断**

必须回答：

```text
1. 交付与审计当前真实停点是什么？
2. Report / Export / Replay 是否能解释 OrchestrationDecision 和 evidenceState？
3. 不改架构，只改 ReportService / ExportRenderer / ReplayService，最多能修到什么程度？
```

代码级证据至少覆盖：

```text
ReportService
ReportExportRenderer
TaskEventReplayService
TaskSseHub
SearchAuditSnapshot
CollectionAuditSnapshot
```

篇幅上限：不超过 2000 字。

- [ ] **Step 5: 回链更新总路线图**

把 3.5 四份诊断挂回：

```text
docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md
```

完成标准：四份诊断都只诊断，不写实施方案；每份都明确“链路内最多能修到哪里”。

---

## 5. Task 4: 收敛点决策

**Files:**
- Create: `docs/superpowers/plan/2026-06-26-3.5-convergence-decision.md`
- Review: `docs/superpowers/analysis-reasoning/problem/AnalysisReasoning.md`
- Review: `docs/superpowers/report-writing/problem/ReportWriting.md`
- Review: `docs/superpowers/conversation-collaboration/problem/ConversationCollaboration.md`
- Review: `docs/superpowers/delivery-audit/problem/DeliveryAudit.md`

限幅要求：收敛决策文档不超过 1500 字。它只回答“是否进入 4.x / 是否先做 Tavily / 先深挖哪条链路”，不展开实施方案。

- [ ] **Step 1: 建立收敛判断表**

决策文档必须包含：

```text
链路
真实停点
是否撞到 3.4 协作协议
链路内可修复上限
是否指向任务运行时表达力不足
是否指向证据来源不足
是否指向审计/回放底座不足
```

- [ ] **Step 2: 判断是否进入 4.x**

进入 4.x 的触发条件：

```text
至少 3 条链路共同指向固定 DAG / 动态补图 / 协作协议 / runtime 表达力不足；
并且这些问题无法通过单链路内部修改完全解决。
```

不进入 4.x 的条件：

```text
主要问题集中在某个 Agent prompt、某个链路 DTO、某个局部服务；
且链路内部修改可以完整解决。
```

- [ ] **Step 3: 判断 Tavily 是否进入回补候选**

Tavily 进入回补候选的触发条件：

```text
诊断显示证据来源不足、官方资料缺口、第三方资料覆盖不足、Playwright 兜底过重；
且这些问题影响 Writer / Citation / Reviewer 至少两个下游链路。
```

若满足上述条件，不必等待完整 4.x。先选择以下两条路径之一：

```text
路径 A：Tavily fail-open 轻量接入
  适用：证据来源不足已经明确，但 4.x 尚未启动或尚未完成。
  做法：按现有 SearchSourceProvider / CollectionExecutor 链路接入 Tavily，默认关闭、fail-open、保留千帆/SerpApi/Playwright 兜底。
  后续：4.x 完成后再把 Tavily 注册为正式 Capability。

路径 B：Tavily capability 接入
  适用：4.0 已经有 Capability Registry 或等价能力入口。
  做法：把 Tavily 作为 EVIDENCE_EXPANSION / FAST_LANE_COLLECT 能力接入 Orchestrator。
```

完成标准：形成明确结论：

```text
进入 4.x / 不进入 4.x
Tavily fail-open 轻量接入 / Tavily capability 接入 / 暂缓 Tavily
优先深挖哪条链路
```

---

## 6. Task 5: 4.0 Runtime Contract 与最小能力入口

**Files:**
- Create: `docs/superpowers/agent-collaboration-runtime/specs/2026-06-26-contract-first-agent-collaboration-runtime-design.md`
- Create: `docs/superpowers/agent-collaboration-runtime/plan/2026-06-26-agent-collaboration-runtime-4.0-contract-plan.md`
- Modify: `docs/specs/project-evolution-roadmap.md`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

4.0 只建立架构接缝，不迁移现有 Writer / Citation / Reviewer 链路，不改变当前固定 DAG 主路径。

- [ ] **Step 1: 定义 4.x 总体目标架构**

4.x 只在 Task 4 判定“收敛充分”后启动。目标架构名称：

```text
Contract-first Agent Collaboration Runtime
契约优先的 Agent 协作运行时
```

架构层次：

```text
Task Contract Layer
Collaboration Planning Layer
Decision Layer
Dynamic Graph Runtime
Capability Registry
Audit / Replay / Recovery Layer
```

- [ ] **Step 2: 冻结 runtime contract**

4.0 要把以下协议提升为 runtime contract：

```text
AgentSuggestion
OrchestrationDecision
DecisionPolicyResult
DynamicPlanMutation
DecisionTrace
OrchestratorCheckpoint
sourceUrls
evidenceState
```

完成标准：这些协议有正式字段说明、owner、允许变更规则和向后兼容要求。

- [ ] **Step 3: 设计 Capability Registry 最小入口**

4.0 只设计最小入口，不迁移所有能力：

```text
CapabilityKey
CapabilityRequest
CapabilityResult
CapabilityPolicy
CapabilityAudit
```

首批只要求能表达：

```text
SUPPLEMENT_EVIDENCE
REWRITE_CLAIM
MANUAL_REVIEW
NO_ACTION
```

- [ ] **Step 4: 明确 4.0 不改什么**

4.0 不做：

```text
不迁移 Writer / Citation / Reviewer
不迁移 ConversationCollaboration
不替换 DagExecutor
不把 DynamicPlanMutation runtime 化
不接 Tavily 为正式 capability
不改搜索采集链路
```

- [ ] **Step 5: 写 4.0 实施计划**

实施计划只覆盖：

```text
1. runtime contract 文档化
2. capability registry 接口设计
3. audit 字段预留
4. 与现有 OrchestrationDecision / DecisionPolicyResult 的适配关系
```

完成标准：4.0 结束后，系统仍按现有链路运行，但已有正式能力入口可承接后续 4.1 和 Tavily capability。

---

## 7. Task 6: 4.1 动态补图 Runtime 化与核心链路迁移

**Files:**
- Create: `docs/superpowers/agent-collaboration-runtime/plan/2026-06-26-agent-collaboration-runtime-4.1-dynamic-runtime-plan.md`
- Modify: `docs/specs/project-evolution-roadmap.md`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

4.1 才是真正的架构修改，只有在 4.0 contract 稳定后启动。

- [ ] **Step 1: 明确 4.1 改什么**

4.1 改造目标：

```text
DAG 从主角降级为执行载体
协作协议和决策层成为任务推进主角
动态补图从局部补丁升级为受控 runtime 能力
能力调用从硬编码类调用升级为 Capability Registry
审计/回放能解释每次建议、决策、补图、人工门禁和重跑
```

- [ ] **Step 2: DynamicPlanMutation runtime 化**

把当前“局部补丁式动态补图”升级为正式 runtime 能力：

```text
计划变更请求
策略审批
动态图应用
执行恢复点
审计回放
失败降级
```

- [ ] **Step 3: 迁移 Writer / Citation / Reviewer**

首批只迁移三类动作：

```text
Writer citationGap -> SUPPLEMENT_EVIDENCE / REWRITE_SECTION
Citation missing citation / weak support -> SUPPLEMENT_EVIDENCE / REWRITE_CLAIM / MANUAL_REVIEW
Reviewer unsupported_claim -> REWRITE_CLAIM / MANUAL_REVIEW
```

不迁移 Collector / Extractor / Analyzer 全量行为。

- [ ] **Step 4: 明确 4.1 不改什么**

4.1 不做：

```text
不推倒所有已有 Agent
不删除 DagExecutor
不引入自由 Agent 任意生成节点
不让 Orchestrator 绕过 DecisionPolicy
不把 Conversation 变成新的 Orchestrator
不把对话协同作为首批 runtime 迁移对象
不让 Tavily 替代已有搜索采集链路
```

- [ ] **Step 5: 写 4.1 实施计划**

实施计划必须按以下顺序拆：

```text
1. DynamicPlanMutation / DynamicTaskGraphService runtime 化
2. Audit / Replay / Recovery 统一解释协作决策
3. 迁移 Writer 的补证和重写动作
4. 迁移 Citation 的补引用和补证动作
5. 迁移 Reviewer 的 unsupported_claim 修复动作
6. 接入 Tavily 为正式 capability，前提是轻量路径未提前执行或需要升级
7. 主链路稳定后，再把 Conversation 接为 runtime contract 消费端和安全确认入口
```

完成标准：4.1 能解释 3.5 诊断里的 runtime 根因，并让 Writer / Citation / Reviewer 的补证或重写动作从局部 if-else 迁移到受控 runtime。

---

## 8. Task 7: Tavily Fast Lane 接入路径

**Files:**
- Review: `docs/Travily/tavily-fast-lane-integration-design.md`
- Review: `docs/Travily/tavily-fast-lane-mvp-execution-plan.md`
- Optional Modify: `docs/superpowers/agent-collaboration-runtime/plan/2026-06-26-agent-collaboration-runtime-4.0-contract-plan.md`
- Optional Modify: `docs/superpowers/agent-collaboration-runtime/plan/2026-06-26-agent-collaboration-runtime-4.1-dynamic-runtime-plan.md`

- [ ] **Step 1: 确认 Tavily 触发条件**

满足以下条件时，Tavily 可以进入执行候选：

```text
3.5 诊断确认证据来源不足 / 官方资料缺口 / 第三方资料覆盖不足 / Playwright 兜底过重；
这些问题影响 Writer / Citation / Reviewer 至少两个下游链路；
sourceUrls / evidenceState 红线已冻结；
Search / Collection 旧链路仍能保留 fallback。
```

- [ ] **Step 2: 选择 Tavily 接入路径**

路径 A：4.x 前 fail-open 轻量接入。

```text
适用条件：
  证据来源问题已经明确；
  4.0 / 4.1 尚未启动或尚未完成；
  需要尽快验证 EvidenceSource 质量是否提升。

执行方式：
  执行 docs/Travily/tavily-fast-lane-mvp-execution-plan.md；
  Tavily 只作为 SearchSourceProvider / CollectionExecutor 增强；
  默认关闭、fail-open、保留千帆 / SerpApi / Playwright 兜底；
  不依赖 Capability Registry。
```

路径 B：4.x 后 capability 接入。

```text
适用条件：
  4.0 已经有 Capability Registry 或等价能力入口；
  Orchestrator 可以表达 SUPPLEMENT_EVIDENCE / EVIDENCE_REPAIR；
  需要让 Tavily 作为受控协作能力参与补证闭环。
```

- [ ] **Step 3: 若选择路径 B，把 Tavily 放入能力注册层**

Tavily 在 4.x 中的定位：

```text
Capability: EVIDENCE_EXPANSION / FAST_LANE_COLLECT
Provider: TavilyFastLaneProvider
Executor: TavilyPrefetchedExecutor
Trigger: OrchestrationDecision
Policy: DecisionPolicyResult
Audit: TavilyFastLaneAudit
```

- [ ] **Step 4: 执行 Tavily MVP 计划**

执行文件：

```text
docs/Travily/tavily-fast-lane-mvp-execution-plan.md
```

完成标准：

```text
EvidenceSource 质量指标高于 baseline
playwrightInvocationCount 低于 baseline
Tavily 失败可 fail-open 回落原链路
所有 Tavily EvidenceSource 保留 sourceUrls
```

---

## 9. 当前第一步

现在立刻开始的第一件事：

```text
执行 Task 1: P3-4 Citation 验证确认
```

不要先写 4.x，不要先接 Tavily。

P3-4 三条验证命令通过后，再做 Task 2 红线冻结。只有红线冻结后，3.5 诊断才有统一判断标准。

## 10. 完成标准

本路线图完成时必须满足：

```text
1. P3-4 Citation 已成为 Reviewer 前稳定门禁。
2. 3.3 / 3.4 红线协议已经冻结。
3. 3.5 四份诊断都回答了真实停点、协议接缝、链路内修复上限。
4. 有一份明确的收敛点决策文档。
5. 只有在 runtime 问题收敛充分时才启动 4.0 / 4.1。
6. Tavily Fast Lane 根据收敛结论选择 fail-open 轻量接入或 4.x capability 接入。
```
