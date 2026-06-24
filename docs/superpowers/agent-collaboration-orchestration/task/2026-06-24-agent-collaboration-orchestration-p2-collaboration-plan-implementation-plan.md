# Agent Collaboration Orchestration P2 Collaboration Plan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在任务开始前生成可审计、可校验、可回放的 `CollaborationPlan`，并在 `extract_schema` 后把证据缺口转换为受策略保护的 Orchestrator 决策。

**Architecture:** P2 采用规则优先的 Orchestrator 前置规划，不引入自由 DAG 生成器，也不让 LLM 直接拼 Java 节点。`CollaborationGoal -> CollaborationPlan -> InitialPlanReview -> ExecutionPlanDefinitionBuilder` 只映射到现有标准 DAG 模板，运行期抽取缺口通过 `AgentSuggestion -> OrchestrationDecision -> DecisionPolicyService` 进入 P1 已有决策链路。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, AssertJ, Maven

---

## Scope Guard

### P2 必须完成

1. 新增前置协作规划契约：
   - `CollaborationGoal`
   - `AgentRoleAssignment`
   - `CollaborationPlan`
   - `InitialPlanReview`
   - `CollaborationCheckpoint`
   - `AgentSuggestion`
2. 新增规则优先服务：
   - `CollaborationGoalAssembler`
   - `CollaborationPlanService`
   - `InitialPlanReviewService`
   - `CollaborationTraceService`
   - `ExtractorSuggestionAssembler`
3. `WorkflowFactory / ExecutionPlanDefinitionBuilder` 支持消费已通过 `InitialPlanReview` 的 `CollaborationPlan`，但只能把角色、质量门槛和 checkpoint 投影到现有标准 DAG，不允许生成任意节点。
4. replay 能看到 `COLLABORATION_PLAN_RECORDED` 和 `COLLABORATION_CHECKPOINT_UPDATED`。
5. `extract_schema` 输出后的 `NO_BUSINESS_FIELDS_EXTRACTED / FIELD_MISSING_EVIDENCE / EVIDENCE_NOT_COVERING / LLM_REFUSED / SECTION_EVIDENCE_GAP` 能形成 `AgentSuggestion`，并进入 Orchestrator 决策输入。
6. 所有新契约都必须包含 `sourceUrls` 和 `evidenceState`，缺来源时只能显式表达 `MISSING_SOURCE`，不能当作可靠事实。

### P2 明确不做

1. 不接入 P3 的 Analyzer / Writer / Conversation / Citation Agent 改造。
2. 不重写 `DagExecutor` 主循环。
3. 不把 `ExecutionPlanDefinitionBuilder` 改成 LLM 自由规划器。
4. 不让 Orchestrator 自由生成未知节点类型。
5. 不新增数据库表承载 P2 trace；第一版继续复用 `TaskWorkflowEvent` outbox/replay。
6. 不移除 P1 的 `OrchestrationDecision / DecisionPolicyService / DecisionExecutorAdapter`。
7. 不调用外部 LLM 生成初始计划；P2 第一版用规则优先服务。后续如果加入 LLM，必须带 try-catch、最大重试次数和规则降级计划。
8. backend 全量回归的已知历史阻塞是 `ArchitectureWhitelistTest` ledger 路径问题；P2 执行期可以记录该历史阻塞，但任何新增的 `Collaboration / Orchestration / WorkflowFactory / DagExecutor / TaskReplayProjectionService` 失败必须在 P2 范围内修复。

---

## Current Stage

当前阶段：3.4 P2 前置协作规划执行计划已准备进入 Task 1。

- [x] P0 架构规格冻结：已完成
- [x] P1 终审失败回流 MVP：已完成自动化 smoke 与聚合回归
- [x] Day 6 质量审查、修订重写诊断与方案：已回链总蓝图
- P2 执行进度唯一维护在文末 `2026-06-24 执行进度`，本节只保留阶段摘要和计划顺序。

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 契约红灯测试与模型归一化 | 0.5 天 | P1 orchestration 包已存在 |
| Task 2 | 从 AnalysisTask 组装目标并生成规则计划 | 0.5-1 天 | Task 1 契约通过 |
| Task 3 | 初始计划策略校验 | 0.5 天 | Task 2 计划服务通过 |
| Task 4 | WorkflowFactory 受控消费协作计划 | 1 天 | Task 3 review 通过 |
| Task 5 | 协作计划 trace、checkpoint 和 replay | 0.5-1 天 | Task 4 能生成计划 |
| Task 6 | Extractor 证据缺口建议与决策接入 | 1 天 | P1 决策链路稳定 |
| Task 7 | 聚合验证和文档回链 | 0.5 天 | Task 1-6 通过 |

---

## Risk Register

| 风险 | 影响 | 处理要求 |
| --- | --- | --- |
| P2 改动导致 P1 编排测试回归 | P1 回流链路失效 | Task 7 必须运行 P1+P2 编排聚合测试；凡是 `Orchestration / DynamicPlanAppender / DecisionPolicy / Replay` 相关失败，必须在 P2 范围内修复 |
| `DagExecutor` 接入点侵入主执行循环 | 下游误执行或任务终态错误 | Task 6 只允许在 `executeRunningNode(...)` 成功写回后、节点完成事件发布前后加入受控 hook；不得改写主 while 循环和依赖判定 |
| `WorkflowEventType` 新枚举存储方式 | 历史事件解析错误 | 当前 `TaskWorkflowEvent.eventType` 已使用 `@Enumerated(EnumType.STRING)`；P2 不得改成 ordinal，也不得重排旧枚举名称语义 |
| 新增/修改文件数量较多 | 构造器和测试 helper 漏改 | 按 Task 1-7 顺序推进，每个 Task 运行对应小集合测试后再进入下一步 |
| backend 全量回归存在历史阻塞 | 全量回归无法直接绿色 | 允许记录该历史阻塞；但任何 P2 相关测试失败必须修复，不能借历史问题跳过 |

---

## Code Template Boundary

本文中的 Java 代码块是红灯测试和关键契约的行为锚点，不是要求逐字照抄的最终实现。执行时可以调整私有 helper 名称、局部变量和构造器参数顺序，但必须保持：

1. 测试断言表达的业务行为不变；
2. `sourceUrls / evidenceState / confidence` 归一化规则不变；
3. Workflow 只消费已校验 `CollaborationPlan`，不生成自由 DAG；
4. DagExecutor hook 不改写主调度循环；
5. Task 7 聚合验证仍能通过。

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoal.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentRoleAssignment.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReview.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationCheckpoint.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentSuggestion.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/ExtractorSuggestionAssembler.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssemblerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/ExtractorSuggestionAssemblerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisherTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitecturePackageMapping.java`

### Docs - Modify

- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p2-collaboration-plan-implementation-plan.md`

---

## Task 1: 锁定 P2 前置协作规划契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationContractTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoal.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentRoleAssignment.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlan.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReview.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationCheckpoint.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentSuggestion.java`

- [ ] **Step 1: 写契约红灯测试**

Create `CollaborationContractTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNormalizeCollaborationGoalWithMissingSourceState() throws Exception {
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-001")
                .taskId(50L)
                .subject("企业级 RAG 知识库竞品分析")
                .competitors(List.of("Notion AI", "Glean"))
                .analysisDimensions(List.of("pricing", "security", "integration"))
                .deliverableType("COMPETITOR_REPORT")
                .depth("standard")
                .budget(Map.of("maxSearchQueries", 20, "maxModelCalls", 12, "maxAutoDecisions", 5))
                .constraints(Map.of("requireSourceUrls", true, "allowDynamicBranch", true))
                .sourceUrls(List.of())
                .build()
                .normalized();

        assertThat(goal.getDepth()).isEqualTo("STANDARD");
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(goal.getSourceUrls()).isEmpty();
        assertThat(objectMapper.writeValueAsString(goal))
                .contains("sourceUrls")
                .contains("evidenceState")
                .contains("analysisDimensions");
    }

    @Test
    void shouldCarryPlanReviewAndCheckpointAsSeparateAuditObjects() {
        CollaborationPlan plan = CollaborationPlan.builder()
                .planId("cp-001")
                .goalId("cg-001")
                .taskId(50L)
                .planningMode("orchestrator_first")
                .agentRoleAssignments(List.of(
                        AgentRoleAssignment.builder()
                                .roleId("role-collector-01")
                                .agentType("collector")
                                .mission("采集竞品官网、文档和定价页证据")
                                .expectedOutputs(List.of("EvidenceFragment", "CollectionAudit"))
                                .dependsOn(List.of())
                                .qualityGate("sourceUrls must not be empty")
                                .build(),
                        AgentRoleAssignment.builder()
                                .roleId("role-extractor-01")
                                .agentType("extractor")
                                .mission("抽取结构化字段并输出 evidenceCoverage")
                                .expectedOutputs(List.of("ExtractResult", "AgentSuggestion"))
                                .dependsOn(List.of("role-collector-01"))
                                .qualityGate("evidenceCoverage must cover requested dimensions")
                                .build()))
                .checkpoints(List.of("after_extract_schema", "quality_check_final"))
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        InitialPlanReview review = InitialPlanReview.builder()
                .reviewId("ipr-001")
                .planId("cp-001")
                .allowed(true)
                .mappedWorkflowTemplate("STANDARD_COMPETITOR_ANALYSIS_V1")
                .policyRuleRefs(List.of("agentRoleCoverage", "dagTemplateCompatibility"))
                .sourceUrls(plan.getSourceUrls())
                .build()
                .normalized();

        CollaborationCheckpoint checkpoint = CollaborationCheckpoint.builder()
                .checkpointId("cc-001")
                .taskId(50L)
                .goalId("cg-001")
                .planId("cp-001")
                .lastReviewId("ipr-001")
                .phase("plan_approved")
                .mappedWorkflowPlanId(27L)
                .pendingActions(List.of())
                .resumeReason("协作计划已通过初始校验，等待 WorkflowPlan 执行。")
                .sourceUrls(plan.getSourceUrls())
                .build()
                .normalized();

        assertThat(plan.getPlanningMode()).isEqualTo("ORCHESTRATOR_FIRST");
        assertThat(plan.getAgentRoleAssignments()).extracting(AgentRoleAssignment::getAgentType)
                .contains("COLLECTOR", "EXTRACTOR");
        assertThat(review.isAllowed()).isTrue();
        assertThat(review.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
        assertThat(checkpoint.getPhase()).isEqualTo("PLAN_APPROVED");
        assertThat(checkpoint.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldNormalizeExtractorSuggestionWithoutGrantingExecutionPower() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-001")
                .taskId(50L)
                .producerNodeName("extract_schema")
                .producerAgentType("extractor")
                .suggestionType("evidence_gap")
                .targetSection("pricing")
                .summary("pricing 字段缺少可验证来源。")
                .severity("high")
                .confidence(1.5d)
                .sourceUrls(List.of())
                .suggestedQueries(List.of("Notion AI pricing official"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();

        assertThat(suggestion.getProducerAgentType()).isEqualTo("EXTRACTOR");
        assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
        assertThat(suggestion.getSeverity()).isEqualTo("HIGH");
        assertThat(suggestion.getConfidence()).isEqualTo(1.0d);
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationContractTest" test
```

Expected:

- FAIL
- `CollaborationGoal`、`CollaborationPlan`、`AgentSuggestion` 等契约类不存在。

- [ ] **Step 3: 新增契约类**

Implementation rules:

- 所有类使用 `@Data`、`@Builder(toBuilder = true)`、`@NoArgsConstructor`、`@AllArgsConstructor`。
- 所有集合字段用 `@Builder.Default` 初始化为 `List.of()` 或 `Map.of()`。
- 每个契约提供 `normalized()` 方法，统一大写枚举文本、去重 `sourceUrls`、把 `confidence` 裁剪到 `[0.0, 1.0]` 闭区间，并在缺少来源时填入 `EvidenceState.MISSING_SOURCE`。
- 每个类顶部写中文注释，说明该对象是协作规划事实、不是报告结论。

Required fields:

```text
CollaborationGoal:
goalId, taskId, subject, competitors, analysisDimensions, deliverableType, depth,
budget, constraints, sourceUrls, evidenceState

AgentRoleAssignment:
roleId, agentType, mission, expectedOutputs, dependsOn, qualityGate, sourceUrls, evidenceState

CollaborationPlan:
planId, goalId, taskId, planningMode, agentRoleAssignments, checkpoints,
sourceUrls, evidenceState

InitialPlanReview:
reviewId, planId, allowed, blockedReasons, requiredAdjustments,
mappedWorkflowTemplate, policyRuleRefs, sourceUrls, evidenceState

CollaborationCheckpoint:
checkpointId, taskId, goalId, planId, lastReviewId, phase, mappedWorkflowPlanId,
pendingActions, resumeReason, sourceUrls, evidenceState

AgentSuggestion:
suggestionId, taskId, producerNodeName, producerAgentType, suggestionType,
targetSection, summary, severity, confidence, sourceUrls, evidenceState,
suggestedQueries, suggestedTargetNode
```

Normalization helpers may be repeated in each class first, or extracted into a package-private `OrchestrationTextNormalizer` only if duplication becomes harder to read.

- [ ] **Step 4: 运行契约测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationContractTest" test
```

Expected:

- PASS
- JSON 序列化中包含 `sourceUrls` 和 `evidenceState`。

---

## Task 2: 组装 CollaborationGoal 并生成规则优先 CollaborationPlan

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssembler.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanService.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationGoalAssemblerTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationPlanServiceTest.java`

- [ ] **Step 1: 写 Goal 组装测试**

Create `CollaborationGoalAssemblerTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationGoalAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CollaborationGoalAssembler assembler = new CollaborationGoalAssembler(objectMapper);

    @Test
    void shouldAssembleGoalFromAnalysisTaskWithBudgetAndConstraints() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(88L)
                .taskName("AI 知识库竞品分析")
                .subjectProduct("企业级 RAG 知识库")
                .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI", "Glean")))
                .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so", "https://www.glean.com")))
                .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing", "security")))
                .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
                .reportTemplate("标准版")
                .build();

        CollaborationGoal goal = assembler.assemble(task);

        assertThat(goal.getGoalId()).isEqualTo("cg-task-88");
        assertThat(goal.getTaskId()).isEqualTo(88L);
        assertThat(goal.getSubject()).contains("企业级 RAG 知识库");
        assertThat(goal.getCompetitors()).containsExactly("Notion AI", "Glean");
        assertThat(goal.getAnalysisDimensions()).containsExactly("pricing", "security");
        assertThat(goal.getBudget()).containsEntry("maxSearchQueries", 20);
        assertThat(goal.getConstraints()).containsEntry("requireSourceUrls", true);
        assertThat(goal.getSourceUrls()).contains("https://www.notion.so", "https://www.glean.com");
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldKeepMissingSourceStateWhenUserProvidesNoUrls() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(89L)
                .taskName("AI 助手竞品分析")
                .subjectProduct("AI 助手")
                .competitorNames(objectMapper.writeValueAsString(List.of("ChatGPT")))
                .competitorUrls(objectMapper.writeValueAsString(List.of()))
                .analysisDimensions(objectMapper.writeValueAsString(List.of()))
                .build();

        CollaborationGoal goal = assembler.assemble(task);

        assertThat(goal.getAnalysisDimensions()).contains("产品功能", "目标用户", "价格策略");
        assertThat(goal.getSourceUrls()).isEmpty();
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
    }
}
```

- [ ] **Step 2: 写规则计划测试**

Create `CollaborationPlanServiceTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationPlanServiceTest {

    private final CollaborationPlanService service = new CollaborationPlanService();

    @Test
    void shouldCreateStandardRolePlanWithoutGeneratingFreeDagNodes() {
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-task-88")
                .taskId(88L)
                .subject("企业级 RAG 知识库竞品分析")
                .competitors(List.of("Notion AI", "Glean"))
                .analysisDimensions(List.of("pricing", "security"))
                .deliverableType("COMPETITOR_REPORT")
                .depth("STANDARD")
                .budget(Map.of("maxSearchQueries", 20, "maxModelCalls", 12, "maxAutoDecisions", 5))
                .constraints(Map.of("requireSourceUrls", true, "allowDynamicBranch", true))
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        CollaborationPlan plan = service.createPlan(goal);

        assertThat(plan.getPlanId()).isEqualTo("cp-task-88-v1");
        assertThat(plan.getPlanningMode()).isEqualTo("ORCHESTRATOR_FIRST");
        assertThat(plan.getAgentRoleAssignments()).extracting(AgentRoleAssignment::getAgentType)
                .containsExactlyInAnyOrder("COLLECTOR", "EXTRACTOR", "ANALYZER", "WRITER", "REVIEWER");
        assertThat(plan.getCheckpoints()).containsExactly("after_extract_schema", "quality_check_final");
        assertThat(plan.getAgentRoleAssignments())
                .allSatisfy(role -> assertThat(role.getQualityGate()).isNotBlank());
        assertThat(plan.getSourceUrls()).containsExactly("https://www.notion.so");
        assertThat(plan.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }
}
```

- [ ] **Step 3: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationGoalAssemblerTest,CollaborationPlanServiceTest" test
```

Expected:

- FAIL
- `CollaborationGoalAssembler` 和 `CollaborationPlanService` 不存在。

- [ ] **Step 4: 实现 `CollaborationGoalAssembler`**

Implementation rules:

- 从 `AnalysisTask` 读取 `taskName / subjectProduct / competitorNames / competitorUrls / analysisDimensions / sourceScope / reportTemplate`。
- JSON 数组解析失败时记录 warn，并返回空列表，不抛出任务创建异常。
- 默认维度使用 `产品功能 / 目标用户 / 价格策略 / 技术能力 / 市场定位`。
- 默认预算：
  - `maxSearchQueries=20`
  - `maxModelCalls=12`
  - `maxAutoDecisions=5`
- 默认约束：
  - `requireSourceUrls=true`
  - `allowDynamicBranch=true`
  - `requiresHumanConfirmationForRerun=true`

The class constructor:

```java
public CollaborationGoalAssembler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
}
```

- [ ] **Step 5: 实现 `CollaborationPlanService`**

Implementation rules:

- P2 第一版不调用外部 LLM。
- 根据固定角色顺序生成 5 个角色：`COLLECTOR / EXTRACTOR / ANALYZER / WRITER / REVIEWER`。
- `EXTRACTOR.expectedOutputs` 必须包含 `AgentSuggestion`，因为 P2 要在抽取后识别证据缺口。
- checkpoint 固定为：
  - `after_extract_schema`
  - `quality_check_final`
- `planId` 使用 `cp-task-{taskId}-v1`。
- `planningMode` 使用 `ORCHESTRATOR_FIRST`。
- `sourceUrls/evidenceState` 从 `CollaborationGoal` 继承。

- [ ] **Step 6: 运行服务测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationGoalAssemblerTest,CollaborationPlanServiceTest" test
```

Expected:

- PASS
- 规则计划只表达角色和 checkpoint，不生成 Java 节点名。

---

## Task 3: InitialPlanReviewService 策略校验

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewService.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/InitialPlanReviewServiceTest.java`

- [ ] **Step 1: 写计划校验测试**

Create `InitialPlanReviewServiceTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InitialPlanReviewServiceTest {

    private final InitialPlanReviewService service = new InitialPlanReviewService();

    @Test
    void shouldApproveStandardPlanThatCanMapToExistingDagTemplate() {
        InitialPlanReview review = service.review(standardPlan());

        assertThat(review.isAllowed()).isTrue();
        assertThat(review.getMappedWorkflowTemplate()).isEqualTo("STANDARD_COMPETITOR_ANALYSIS_V1");
        assertThat(review.getBlockedReasons()).isEmpty();
        assertThat(review.getPolicyRuleRefs())
                .contains("agentRoleCoverage", "dagTemplateCompatibility", "checkpointDensity", "sourceUrlsOrEvidenceStateRequired");
    }

    @Test
    void shouldBlockPlanWithoutCollectorRole() {
        CollaborationPlan plan = standardPlan().toBuilder()
                .agentRoleAssignments(standardPlan().getAgentRoleAssignments().stream()
                        .filter(role -> !"COLLECTOR".equals(role.getAgentType()))
                        .toList())
                .build();

        InitialPlanReview review = service.review(plan);

        assertThat(review.isAllowed()).isFalse();
        assertThat(review.getBlockedReasons()).contains("缺少必需角色 COLLECTOR");
        assertThat(review.getMappedWorkflowTemplate()).isEqualTo("UNMAPPED");
    }

    @Test
    void shouldBlockUnknownAgentTypeAndTooManyCheckpoints() {
        CollaborationPlan plan = standardPlan().toBuilder()
                .agentRoleAssignments(List.of(AgentRoleAssignment.builder()
                        .roleId("role-unknown")
                        .agentType("PLANNER")
                        .mission("未知角色")
                        .expectedOutputs(List.of("UnknownOutput"))
                        .qualityGate("unknown")
                        .build().normalized()))
                .checkpoints(List.of("a", "b", "c", "d"))
                .build();

        InitialPlanReview review = service.review(plan);

        assertThat(review.isAllowed()).isFalse();
        assertThat(review.getBlockedReasons()).contains("存在未登记 Agent 类型 PLANNER", "checkpoint 数量超过 P2 上限 2");
    }

    private CollaborationPlan standardPlan() {
        return CollaborationPlan.builder()
                .planId("cp-task-88-v1")
                .goalId("cg-task-88")
                .taskId(88L)
                .planningMode("ORCHESTRATOR_FIRST")
                .agentRoleAssignments(List.of(
                        role("role-collector-01", "COLLECTOR", List.of()),
                        role("role-extractor-01", "EXTRACTOR", List.of("role-collector-01")),
                        role("role-analyzer-01", "ANALYZER", List.of("role-extractor-01")),
                        role("role-writer-01", "WRITER", List.of("role-analyzer-01")),
                        role("role-reviewer-01", "REVIEWER", List.of("role-writer-01"))))
                .checkpoints(List.of("after_extract_schema", "quality_check_final"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }

    private AgentRoleAssignment role(String roleId, String agentType, List<String> dependsOn) {
        return AgentRoleAssignment.builder()
                .roleId(roleId)
                .agentType(agentType)
                .mission(agentType + " mission")
                .expectedOutputs(List.of(agentType + "_OUTPUT"))
                .dependsOn(dependsOn)
                .qualityGate("sourceUrls or explicit evidence gap")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=InitialPlanReviewServiceTest" test
```

Expected:

- FAIL
- `InitialPlanReviewService` 不存在。

- [ ] **Step 3: 实现 `InitialPlanReviewService`**

Implementation rules:

- 允许的 Agent 类型固定为 `COLLECTOR / EXTRACTOR / ANALYZER / WRITER / REVIEWER`。
- 必需角色必须全部存在。
- checkpoint 上限为 `2`。
- `sourceUrls` 为空时允许 `EvidenceState.MISSING_SOURCE`，但不能允许 `evidenceState=null`。
- `mappedWorkflowTemplate` 只允许：
  - `STANDARD_COMPETITOR_ANALYSIS_V1`
  - `UNMAPPED`
- `allowed=false` 时 `mappedWorkflowTemplate=UNMAPPED`。
- `policyRuleRefs` 至少包含实际执行过的规则名。

- [ ] **Step 4: 运行校验测试**

Run:

```powershell
mvn -pl backend "-Dtest=InitialPlanReviewServiceTest" test
```

Expected:

- PASS
- 无法落地到现有 DAG 模板的计划被阻断。

---

## Task 4: WorkflowFactory 受控消费 CollaborationPlan

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowFactory.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`

- [ ] **Step 1: 写 Workflow 映射测试**

Add to `WorkflowFactoryTest`:

```java
@Test
void shouldEmbedApprovedCollaborationPlanIntoExistingWorkflowTemplate() throws Exception {
    WorkflowFactory workflowFactory = buildWorkflowFactory(buildBrowserProperties());
    AnalysisTask task = AnalysisTask.builder()
            .id(88L)
            .taskName("协作规划任务")
            .subjectProduct("企业级 RAG 知识库")
            .competitorNames(objectMapper.writeValueAsString(List.of("Notion AI")))
            .competitorUrls(objectMapper.writeValueAsString(List.of("https://www.notion.so")))
            .analysisDimensions(objectMapper.writeValueAsString(List.of("pricing")))
            .sourceScope(objectMapper.writeValueAsString(List.of("官网", "定价页")))
            .build();

    WorkflowPlan plan = workflowFactory.buildPlan(task);

    assertThat(plan.getNodes()).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("extract_schema"));
    assertThat(plan.getNodes()).anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("quality_check_final"));
    assertThat(plan.getNodes()).noneSatisfy(node -> assertThat(node.getNodeName()).startsWith("role-"));

    WorkflowPlan.WorkflowPlanNode extractNode = plan.getNodes().stream()
            .filter(node -> "extract_schema".equals(node.getNodeName()))
            .findFirst()
            .orElseThrow();
    JsonNode extractConfig = objectMapper.readTree(extractNode.getNodeConfig());
    assertThat(extractConfig.path("collaborationPlanId").asText()).isEqualTo("cp-task-88-v1");
    assertThat(extractConfig.path("collaborationRoleId").asText()).isEqualTo("role-extractor-01");
    assertThat(extractConfig.path("orchestratorCheckpoints").toString()).contains("after_extract_schema");
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=WorkflowFactoryTest#shouldEmbedApprovedCollaborationPlanIntoExistingWorkflowTemplate" test
```

Expected:

- FAIL
- `nodeConfig` 还没有 `collaborationPlanId / collaborationRoleId / orchestratorCheckpoints`。

- [ ] **Step 3: 修改 `ExecutionPlanDefinitionBuilder`**

Implementation rules:

- 保留现有 `build(AnalysisTask task, boolean previewOnly)` 方法签名，并让它调用新的重载：

```java
public ExecutionPlanDefinition build(AnalysisTask task, boolean previewOnly) {
    return build(task, previewOnly, null, null);
}
```

- 新增重载：

```java
public ExecutionPlanDefinition build(AnalysisTask task,
                                     boolean previewOnly,
                                     CollaborationPlan collaborationPlan,
                                     InitialPlanReview initialPlanReview) {
    // 只有通过 InitialPlanReview 的协作计划才能投影进 WorkflowPlan。
    // 未通过时继续生成原有固定模板，避免把不安全计划落成执行节点。
}
```

- 在 `extract_schema / analyze_competitors / write_report / quality_check / rewrite_report / quality_check_final` 的 `nodeConfig` 中追加：
  - `collaborationGoalId`
  - `collaborationPlanId`
  - `collaborationReviewId`
  - `collaborationRoleId`
  - `collaborationQualityGate`
  - `orchestratorCheckpoints`
- 不改变现有节点名、依赖、必选/可选属性和执行顺序。
- 不给 collector 分支生成 `role-*` 节点。

- [ ] **Step 4: 修改 `WorkflowFactory`**

Implementation rules:

- 注入：
  - `CollaborationGoalAssembler`
  - `CollaborationPlanService`
  - `InitialPlanReviewService`
- `assembleFormalWorkflowPlan(...)` 中先组装 goal、创建 plan、review，再把通过 review 的计划传给 builder。
- `InitialPlanReview.allowed=false` 时仍可生成旧固定计划预览，但不能带 `collaborationPlanId`，并在日志中记录 review blocked reasons。
- 第一版不在 `WorkflowFactory` 调用外部 LLM 或外部搜索。

- [ ] **Step 5: 更新测试构造器**

Modify `WorkflowFactoryTest.buildWorkflowFactory(...)` so it constructs the new collaborators:

```java
CollaborationGoalAssembler collaborationGoalAssembler = new CollaborationGoalAssembler(objectMapper);
CollaborationPlanService collaborationPlanService = new CollaborationPlanService();
InitialPlanReviewService initialPlanReviewService = new InitialPlanReviewService();
```

Keep every existing dependency in the helper exactly as it is today:

- `TaskNodeRepository`
- `WorkflowPlanValidator`
- `ObjectMapper`
- `DynamicTaskGraphService`
- `ExecutionPlanDefinitionBuilder`
- `WorkflowPlanAssembler`

Then append the three new collaborators to the `WorkflowFactory` constructor call in the same order used by the production constructor. Do not replace existing source discovery, collector template, browser search, dynamic graph or validator setup with mocks unless the current helper already does so.

- [ ] **Step 6: 运行 Workflow 测试**

Run:

```powershell
mvn -pl backend "-Dtest=WorkflowFactoryTest" test
```

Expected:

- PASS
- 现有 workflow 节点仍是标准模板。
- 已审批协作计划只作为审计和质量门槛元数据进入 `nodeConfig`。

---

## Task 5: CollaborationTraceService 与 replay 回放

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceService.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceServiceTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisherTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

- [ ] **Step 1: 写 trace 服务测试**

Create `CollaborationTraceServiceTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationTraceServiceTest {

    private final TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
    private final CollaborationTraceService service = new CollaborationTraceService(repository, new ObjectMapper());

    @Test
    void shouldRecordPlanAndCheckpointEventsWithSourceUrls() {
        when(repository.save(any(TaskWorkflowEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-task-88")
                .taskId(88L)
                .subject("企业级 RAG 知识库竞品分析")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();
        CollaborationPlan plan = CollaborationPlan.builder()
                .planId("cp-task-88-v1")
                .goalId("cg-task-88")
                .taskId(88L)
                .planningMode("ORCHESTRATOR_FIRST")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();
        InitialPlanReview review = InitialPlanReview.builder()
                .reviewId("ipr-cp-task-88-v1")
                .planId("cp-task-88-v1")
                .allowed(true)
                .mappedWorkflowTemplate("STANDARD_COMPETITOR_ANALYSIS_V1")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        TaskWorkflowEvent event = service.recordPlan(goal, plan, review, 31L, 1, "root");

        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.COLLABORATION_PLAN_RECORDED);
        assertThat(event.getPayload()).contains("cp-task-88-v1").contains("STANDARD_COMPETITOR_ANALYSIS_V1");
        assertThat(event.getSourceUrls()).contains("https://www.notion.so");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationTraceServiceTest" test
```

Expected:

- FAIL
- `CollaborationTraceService` 和新 workflow event type 不存在。

- [ ] **Step 3: 增加 workflow event type**

Modify `WorkflowEventType`:

```java
COLLABORATION_PLAN_RECORDED,
COLLABORATION_CHECKPOINT_UPDATED,
```

Keep existing P1 events unchanged:

```java
ORCHESTRATION_DECISION_RECORDED,
ORCHESTRATION_CHECKPOINT_UPDATED
```

- [ ] **Step 4: 实现 `CollaborationTraceService`**

Implementation rules:

- 构造函数依赖 `TaskWorkflowEventRepository` 和 `ObjectMapper`。
- `recordPlan(...)` 写入 `COLLABORATION_PLAN_RECORDED`。
- `recordCheckpoint(...)` 写入 `COLLABORATION_CHECKPOINT_UPDATED`。
- payload 必须包含 `goalId / planId / reviewId / allowed / mappedWorkflowTemplate / evidenceState`。
- `sourceUrls` 写入 `TaskWorkflowEvent.sourceUrls`，空列表写成 `[]`。
- 序列化失败时抛出 `IllegalStateException("serialize collaboration trace failed", e)`，因为这是内部对象序列化异常，不是外部 API 不稳定。

- [ ] **Step 5: 接入 WorkflowFactory 创建初始计划后的 trace**

Implementation rules:

- 在 `WorkflowFactory.createWorkflow(...)` 中，`ensureInitialPlan(...)` 成功后记录 plan trace 和 checkpoint。
- 如果 collaboration review 没有通过，则不写 `COLLABORATION_PLAN_RECORDED` 成功事件，可以写 checkpoint phase `PLAN_BLOCKED`。
- `createWorkflow` 不因 trace 写入失败而伪装成功；trace 是审计红线，失败应阻断任务创建并暴露异常。

- [ ] **Step 6: 更新 replay 测试**

Add assertions to `TaskReplayProjectionServiceTest`:

```java
assertThat(replayResponse.getTimeline())
        .anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("COLLABORATION_PLAN_RECORDED");
            assertThat(event.getSummary()).contains("协作计划");
        });
assertThat(replayResponse.getSourceUrls()).contains("https://www.notion.so");
```

- [ ] **Step 7: 运行 trace/replay 测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationTraceServiceTest,WorkflowEventPublisherTest,TaskReplayProjectionServiceTest" test
```

Expected:

- PASS
- replay timeline 同时能展示 P2 协作计划事件和 P1 编排决策事件。

---

## Task 6: 抽取后 AgentSuggestion 与证据缺口决策

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/ExtractorSuggestionAssembler.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/ExtractorSuggestionAssemblerTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 写 suggestion assembler 测试**

Create `ExtractorSuggestionAssemblerTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorSuggestionAssemblerTest {

    private final ExtractorSuggestionAssembler assembler = new ExtractorSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldCreateBlockingSuggestionForNoBusinessFields() {
        Map<String, Object> output = Map.of(
                "sourceUrls", List.of("https://www.notion.so/pricing"),
                "issueFlags", List.of("NO_BUSINESS_FIELDS_EXTRACTED"),
                "evidenceCoverage", Map.of()
        );

        List<AgentSuggestion> suggestions = assembler.fromExtractorOutput(88L, "extract_schema", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
        assertThat(suggestion.getSeverity()).isEqualTo("ERROR");
        assertThat(suggestion.getSummary()).contains("没有抽出任何业务字段");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
        assertThat(suggestion.getSourceUrls()).contains("https://www.notion.so/pricing");
    }

    @Test
    void shouldCreateSectionSuggestionFromEvidenceCoverageGap() {
        Map<String, Object> pricingCoverage = Map.of(
                "status", "EVIDENCE_NOT_COVERING",
                "sourceUrls", List.of(),
                "missingFields", List.of("price", "plan")
        );
        Map<String, Object> output = Map.of(
                "sourceUrls", List.of(),
                "issueFlags", List.of("SECTION_EVIDENCE_GAP"),
                "evidenceCoverage", Map.of("pricing", pricingCoverage)
        );

        List<AgentSuggestion> suggestions = assembler.fromExtractorOutput(88L, "extract_schema", output);

        assertThat(suggestions).anySatisfy(suggestion -> {
            assertThat(suggestion.getTargetSection()).isEqualTo("pricing");
            assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
            assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
            assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
        });
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=ExtractorSuggestionAssemblerTest" test
```

Expected:

- FAIL
- `ExtractorSuggestionAssembler` 不存在。

- [ ] **Step 3: 实现 `ExtractorSuggestionAssembler`**

Implementation rules:

- 输入支持 `Map<String, Object>`、`JsonNode` 和 JSON 字符串三种形式，内部统一转为 `JsonNode`。
- 读取顶层：
  - `sourceUrls`
  - `issueFlags`
  - `evidenceCoverage`
- 触发规则：
  - `NO_BUSINESS_FIELDS_EXTRACTED` -> `severity=ERROR`，`targetSection=extract_schema`
  - `FIELD_MISSING_EVIDENCE` -> `severity=HIGH`
  - `EVIDENCE_NOT_COVERING` -> `severity=HIGH`
  - `LLM_REFUSED` -> `severity=HIGH`
  - `SECTION_EVIDENCE_GAP` -> `severity=HIGH`
- 每条 suggestion 的 `suggestionId` 使用 `as-task-{taskId}-{producerNodeName}-{index}`。
- 缺少来源时设置 `EvidenceState.MISSING_SOURCE`。
- `suggestedTargetNode` 固定为 `collect_sources`，只表达建议，不直接执行。
- `AgentSuggestion.normalized()` 必须把 `confidence` 裁剪到 `[0.0, 1.0]` 闭区间；`ExtractorSuggestionAssembler` 如果无法从输出中读取置信度，默认使用 `0.75d`，不得保留 `null`。

- [ ] **Step 4: 扩展 `OrchestrationContext`**

Add field:

```java
@Builder.Default
private List<AgentSuggestion> agentSuggestions = List.of();
```

Update `normalized()`:

```java
.agentSuggestions(agentSuggestions == null ? List.of() : agentSuggestions.stream()
        .map(AgentSuggestion::normalized)
        .toList())
```

Chinese comment:

```java
// P2 起运行期上下文可以携带业务 Agent 的建议，但建议只作为 Orchestrator 输入，
// 不能绕过 DecisionPolicyService 直接执行。
```

- [ ] **Step 5: 扩展 `OrchestrationDecisionService`**

Implementation rules:

- 当 `triggerNodeName=extract_schema` 且存在 `AgentSuggestion`：
  - `NO_BUSINESS_FIELDS_EXTRACTED` 或 `severity=ERROR` -> `WAIT_FOR_HUMAN`
  - `EVIDENCE_GAP` 且 `sourceUrls` 为空 -> `WAIT_FOR_HUMAN`
  - `EVIDENCE_GAP` 且 `sourceUrls` 不为空 -> `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE`
  - 无建议 -> `NO_ACTION`
- `inputRefs.agentSuggestionIds` 必须包含 suggestion ids。
- 不直接创建 `DynamicPlanMutation`，仍交给 P1 的 `DecisionPolicyService / DecisionExecutorAdapter`。

- [ ] **Step 6: 接入 `DagExecutor` 的 extract_schema 后检查**

Implementation rules:

- 在 `DagExecutor.executeRunningNode(...)` 中接入，而不是改写主 `while` 调度循环。
- 接入位置：`TaskNode completedNode = applyExecutionResult(...)` 之后、`workflowEventPublisher.publishNodeCompleted(...)` 之前。这样 hook 只处理已经成功落库的 extractor 输出，且能在 analyzer/writer 被下一轮调度前阻断。
- 只在以下条件同时满足时运行 hook：
  - `result.getStatus() == TaskNodeStatus.SUCCESS`
  - `completedNode.getNodeName().equals("extract_schema")`
  - `completedNode.getOutputData()` 非空
- 从 `completedNode.getOutputData()` 解析 suggestions，不从未落库的临时 `AgentResult` 重新读，避免终止请求或乐观锁重试导致脏输出。
- 如果 suggestions 非空，构造 `OrchestrationContext` 调用 `OrchestrationDecisionService`；决策仍写入 `OrchestrationTraceService`，复用 P1 trace。
- `WAIT_FOR_HUMAN` 处理必须使用现有节点状态语义：
  - 把 `completedNode.status` 从 `SUCCESS` 改为 `WAITING_INTERVENTION` 或新增一个受控 helper `markExtractorWaitingForIntervention(...)`；
  - 设置 `interventionReason` 为 Orchestrator 决策原因；
  - 刷新 `runtimeStateRefresher`；
  - 发布失败/等待类节点状态事件，避免 UI 和 replay 仍把 extractor 当作成功；
  - 不抛自由异常来停止主循环。
- analyzer/writer 不应继续执行：现有依赖判定会把 `WAITING_INTERVENTION` 视为 waiting，因此下一轮 `dispatchExecutableNodes(...)` 不会派发依赖 `extract_schema` 的 `analyze_competitors`。
- `NO_ACTION` 时不改节点状态，继续原有 `publishNodeCompleted(...)` 和 shared output 语义。
- 外部 Agent 调用异常不在这里吞掉；本步骤只处理已返回的结构化输出。

- [ ] **Step 7: 运行抽取后决策测试**

Run:

```powershell
mvn -pl backend "-Dtest=ExtractorSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest" test
```

Expected:

- PASS
- 无业务字段或缺证据的抽取结果不会静默进入 analyzer / writer。

---

## Task 7: P2 聚合 smoke、架构边界和文档回链

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitecturePackageMapping.java`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p2-collaboration-plan-implementation-plan.md`

- [ ] **Step 1: 写 P2 smoke 测试**

Smoke scenario:

1. 创建任务。
2. `WorkflowFactory.createWorkflow(...)` 生成标准 DAG。
3. replay 中出现 `COLLABORATION_PLAN_RECORDED`。
4. `extract_schema` 输出 `NO_BUSINESS_FIELDS_EXTRACTED` 时生成 `AgentSuggestion`。
5. Orchestrator 给出 `WAIT_FOR_HUMAN` 或受策略保护的补证决策。
6. replay 中保留 `sourceUrls/evidenceState`。

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationPlanningSmokeTest" test
```

Expected:

- PASS
- 不依赖外部 PostgreSQL、Redis、RocketMQ、搜索浏览器或 LLM。

- [ ] **Step 2: 确认架构包边界**

Implementation rule:

- `cn.bugstack.competitoragent.orchestration..` 已在 P1 纳入 task-orchestration 边界时，不新增架构白名单例外。
- 如果 `BackendModuleDependencyTest` 报 P2 新类依赖越界，优先移动职责或通过已有 facade，而不是扩白名单。

Run:

```powershell
mvn -pl backend "-Dtest=BackendModuleDependencyTest" test
```

Expected:

- PASS
- 不新增 architecture whitelist 例外。

- [ ] **Step 3: 运行 P2 聚合测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,WorkflowFactoryTest,WorkflowEventPublisherTest,TaskReplayProjectionServiceTest,OrchestrationDecisionServiceTest,DagExecutorTest,CollaborationPlanningSmokeTest,BackendModuleDependencyTest" test
```

Expected:

- PASS
- P2 前置计划、受控 workflow 投影、trace/replay、抽取后缺口决策都在聚合测试中通过。

- [ ] **Step 4: 运行 P1+P2 编排聚合测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,CollaborationPlanningSmokeTest" test
```

Expected:

- PASS
- P1 终审回流和 P2 前置规划可以共存。

- [ ] **Step 5: 尝试 backend 全量回归**

Run:

```powershell
mvn -pl backend test
```

Expected:

- 优先目标是 PASS。
- 如果仍阻塞于既有 `ArchitectureWhitelistTest` ledger 路径问题，按 Risk Register 记录为历史阻塞，不在 P2 范围内扩修。
- 如果失败测试名称包含 `Collaboration`、`Orchestration`、`WorkflowFactory`、`DagExecutor`、`TaskReplayProjectionService`，必须在 P2 范围内修复。

- [ ] **Step 6: 文档回链**

Update docs:

- 总蓝图 3.4：P2 从“计划编写中”更新为“具体执行计划已落地，下一步执行 Task 1”。
- 稳定演示计划：当前阶段更新为“可开始 P2 Task 1”。
- 3.4 架构规格 `相关文档`：加入 P2 计划链接。
- P2 计划底部 `2026-06-24 执行进度` 是唯一任务状态源；执行时每完成一个 task 只更新该区域。

- [ ] **Step 7: 差异检查**

Run:

```powershell
git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/orchestration backend/src/main/java/cn/bugstack/competitoragent/workflow backend/src/main/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/orchestration backend/src/test/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/integration docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration
```

Expected:

- PASS
- 不因 `backend/logs/competitor-agent.log` 的历史测试日志尾随空白影响 P2 范围检查。

---

## Verification Index

Task 7 是 P2 的唯一聚合验证命令来源。执行者如果需要查总体验证顺序，按 Task 7 Step 1-7 运行，不在本节维护第二份命令清单。

---

## Manual Smoke Evidence

| 步骤 | 操作 | 期望结果 |
| --- | --- | --- |
| 1 | 创建一个竞品分析任务，竞品包含官网 URL | 任务创建成功，初始 `WorkflowPlan` 仍是标准 DAG |
| 2 | 查看 replay timeline | 出现 `COLLABORATION_PLAN_RECORDED`，并携带 `sourceUrls` 或 `evidenceState` |
| 3 | 查看 `extract_schema` 节点配置 | 包含 `collaborationPlanId / collaborationRoleId / orchestratorCheckpoints` |
| 4 | 让 extractor 返回 `NO_BUSINESS_FIELDS_EXTRACTED` | `ExtractorSuggestionAssembler` 生成 `AgentSuggestion`，`severity` 为 `ERROR` |
| 5 | 查看 Orchestrator 决策 | `inputRefs.agentSuggestionIds` 包含 suggestion id，决策为 `WAIT_FOR_HUMAN` 或受策略保护的补证决策 |
| 6 | 检查 analyzer / writer 是否继续推进 | 缺业务字段时不进入 analyzer / writer，`extract_schema` 进入等待人工介入或受控阻断状态 |
| 7 | 查看 replay / source evidence | 新增事件或决策均包含 `sourceUrls` 或 `evidenceState=MISSING_SOURCE` |

---

## Self-Review

### Spec coverage

1. `CollaborationGoal / CollaborationPlan / AgentRoleAssignment / InitialPlanReview / CollaborationCheckpoint` 由 Task 1-5 覆盖。
2. `ExecutionPlanDefinitionBuilder / WorkflowFactory` 消费已校验协作计划由 Task 4 覆盖。
3. `CollaborationCheckpoint` 和 replay 追溯由 Task 5 覆盖。
4. `Extractor 输出 AgentSuggestion` 由 Task 6 覆盖。
5. `NO_BUSINESS_FIELDS_EXTRACTED / FIELD_MISSING_EVIDENCE / LOW_QUALITY_EVIDENCE` 类证据缺口策略由 Task 6 覆盖，其中 `LOW_QUALITY_EVIDENCE` 第一版映射到 `EVIDENCE_NOT_COVERING / LLM_REFUSED / SECTION_EVIDENCE_GAP`。
6. `不重写 DagExecutor / 不生成自由 DAG / 不扩 P3` 已在 Scope Guard 排除。

### Placeholder scan

1. 本计划每个步骤均给出明确文件、命令和预期结果。
2. 每个任务都列出明确文件、测试入口、实现规则、命令和预期结果。
3. 提交由用户自行完成，本计划不包含 `git commit` 步骤。

### Type consistency

1. `EvidenceState` 复用 P1 已有枚举。
2. `AgentSuggestion` 只作为 Orchestrator 输入，不替代 `OrchestrationDecision`。
3. `InitialPlanReview` 只校验初始协作计划，不替代 P1 `DecisionPolicyResult`。
4. `CollaborationCheckpoint` 只记录计划阶段，和 P1 `OrchestratorCheckpoint` 分开。
5. Workflow 映射只追加协作元数据，不改变现有标准 DAG 节点。

## 2026-06-24 执行进度

当前阶段：P2 可执行计划已落地，等待开始 Task 1 契约红灯测试。

- [x] P2 范围边界确认：已完成
- [x] P2 文件结构确认：已完成
- [x] P2 任务拆解：已完成
- [x] P2 验证命令：已完成
- [ ] Task 1：协作规划契约与红灯测试
- [ ] Task 2：目标组装与规则前置计划
- [ ] Task 3：初始计划校验
- [ ] Task 4：Workflow 受控映射
- [ ] Task 5：trace / checkpoint / replay
- [ ] Task 6：抽取后 AgentSuggestion 与证据缺口决策
- [ ] Task 7：P2 聚合 smoke、文档回链和回归记录
