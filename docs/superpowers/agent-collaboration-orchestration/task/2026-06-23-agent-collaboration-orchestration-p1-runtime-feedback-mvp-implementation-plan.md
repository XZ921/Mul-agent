# Agent Collaboration Orchestration P1 Runtime Feedback MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把终审失败后的质量回流从 `RevisionDirective` 直接驱动动态补图，升级为 `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DynamicTaskGraphService` 的第一条可执行闭环。

**Architecture:** 本计划只实现 3.4 P1 运行期反馈 MVP，不接入 P2 的任务前置 `CollaborationPlan`。实现采用规则优先的 Orchestrator 决策服务，Reviewer 继续输出质量事实和兼容期修订建议，Orchestrator 负责生成编排决策，策略服务负责拦截非法动作，适配器负责把已校验决策翻译成现有动态计划变更。Trace 第一版复用 `TaskWorkflowEvent` outbox/replay，不新建数据库表；Checkpoint 第一版落在决策 trace payload 与动态计划节点配置中，待 P1 实链稳定后再评估是否独立建表。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, ArchUnit, Maven

---

## Scope Guard

### 本计划必须完成

1. 新增 3.4 P1 运行期编排契约：
   - `EvidenceState`
   - `OrchestrationDecision`
   - `DecisionPolicyRuleSet`
   - `DecisionPolicyResult`
   - `DynamicPlanMutation`
   - `DecisionTrace`
   - `OrchestratorCheckpoint`
   - `OrchestrationContext`
2. 复用现有 `QualityDiagnosis`，不创建第二套质量诊断模型。
3. 新增兼容适配器，把现有 `RevisionDirective` 转为 `OrchestrationDecision`。
4. 新增规则优先的 `OrchestrationDecisionService`，只处理 `quality_check_final` 失败后的回流决策。
5. 新增 `DecisionPolicyService`，校验白名单、证据状态、人工确认、循环补图上限和任务状态。
6. 新增 `DecisionExecutorAdapter`，把已校验决策翻译为 `DynamicPlanMutation`。
7. 改造 `CompensationGraphAssembler / DynamicTaskGraphService / DynamicPlanAppender`，支持从 `DynamicPlanMutation` 派生动态计划，同时保留旧 `RevisionDirective` 兼容入口。
8. 新增 `OrchestrationTraceService`，把决策、策略结果、mutation 和 checkpoint 写入 `TaskWorkflowEvent`，让 replay 能看到编排决策过程。
9. Reviewer prompt 和输出边界收口：不再新增编排动作语义，`revisionDirectives` 只作为兼容期展示/适配输入。
10. 全部新契约必须包含 `sourceUrls` 或 `evidenceState`，缺来源时必须显式表达 `MISSING_SOURCE`。

### 本计划明确不做

1. 不接入任务开始前的 `CollaborationGoal / CollaborationPlan / InitialPlanReview` 执行逻辑；这些只在 P0 架构规格中冻结，P2 再执行。
2. 不重写 `DagExecutor` 主循环。
3. 不把 `ExecutionPlanDefinitionBuilder` 改造成智能规划器。
4. 不移除 `RevisionDirective` 字段，也不破坏历史任务报告页。
5. 不做完整 Citation Agent。
6. 不接管对话入口动作执行。
7. 不新增大表承载 trace/checkpoint；P1 第一版复用 outbox/replay，避免为演示版增加迁移风险。
8. 不扩展搜索采集能力；缺证据时只生成补证决策或人工介入。

### P1 与架构规格的有意降级说明

1. `OrchestrationDecision` 必须保留规格 6.7 的 `affectedScope / priority / confidence / inputRefs` 字段。P1 规则优先模式暂不使用 `confidence` 做自动决策，但必须持久化进 trace，避免审计链路缺上下文。
2. `DecisionPolicyRuleSet` 必须保留规格 6.8 的 `riskRules / maxSearchQueriesPerDecision` 字段。P1 不建设通用规则表达式引擎，只解释默认的两条内置风险规则；这属于实现降级，不是协议删减。
3. 架构规格中的 `maxAutoDecisions=5` 是长期上限，稳定演示版默认值使用 `2`。这个值更保守，用于降低终审回流循环补图风险；需要更多自动回流时只能通过显式配置放宽。
4. 架构规格中的 `maxDynamicBranchesPerSection=2` 是长期上限，P1 默认值使用 `1`。稳定演示版优先保证“一处问题只生成一条可解释回流分支”，避免演示任务被多个动态分支稀释。

### 基于评估的修订判定

1. 本计划不偏离 3.4 架构规格的核心方向。规则优先 `OrchestrationDecisionService` 是规格允许的 P1 路径，P1 的价值是把隐式协作显式化、可追溯化、可拦截化；真正调用 LLM 的 AI Orchestrator 留到 P2/P3。
2. `OrchestrationDecision` 字段偏差已在本计划中修正：必须保留 `affectedScope / priority / requiresHumanIntervention / confidence / inputRefs`，其中 `confidence` P1 只入 trace，不参与自动策略。
3. `DecisionPolicyRuleSet` 字段偏差已在本计划中修正：必须保留 `maxSearchQueriesPerDecision / riskRules`，`riskRules` P1 只解释默认内置规则，不建设通用表达式引擎。
4. `OrchestratorCheckpoint.decisionCount` 不能硬编码为 `1`。Task 7 必须从最近一次 checkpoint 事件读取上一轮 `decisionCount` 并递增，防止循环补图保护失效。
5. `OrchestrationDecisionService` 必须覆盖 diagnosis-only 路径：当 `QualityDiagnosis` 存在阻断问题但没有 `RevisionDirective` 时，不能静默 `NO_ACTION`，必须进入 `WAIT_FOR_HUMAN`。
6. `CompensationGraphAssembler.assembleDynamicNodes(DynamicPlanMutation, ...)` 必须有独立测试，不能只靠 `DynamicPlanAppender` 间接覆盖。

---

## Current Stage

当前阶段：3.4 架构规格已经完成，稳定演示版计划已经确定。本计划承接其中 Day 2-5 的 P1 代码任务。

- [x] 3.4 架构规格已存在：`docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- [x] 稳定演示版计划已存在：`docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- [x] 动态补图基础已存在：`CompensationGraphAssembler / DynamicTaskGraphService / DynamicPlanAppender`
- [x] 质量诊断基础已存在：`QualityDiagnosis / RevisionDirective / QualityReviewAgent`
- [x] replay/outbox 基础已存在：`WorkflowEventPublisher / TaskWorkflowEvent / TaskReplayProjectionService`
- [ ] P1 契约与策略红灯测试：待执行
- [ ] P1 Orchestrator 决策服务：待执行
- [ ] P1 动态补图接入：待执行
- [ ] P1 trace/replay 可观测：待执行
- [ ] P1 Reviewer 职责收口：待执行

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase 1 | 契约与红灯测试 | 0.5 天 | 3.4 spec 已冻结 | 待执行 |
| Phase 2 | 决策适配与策略服务 | 0.5-1 天 | Phase 1 红灯测试存在 | 待执行 |
| Phase 3 | 规则优先 Orchestrator 决策服务 | 0.5 天 | Phase 2 契约通过 | 待执行 |
| Phase 4 | DynamicPlanMutation 接入动态补图 | 1 天 | Phase 3 通过 | 待执行 |
| Phase 5 | Trace / checkpoint / replay 可观测 | 0.5-1 天 | Phase 4 通过 | 待执行 |
| Phase 6 | Reviewer 边界收口与回归 | 0.5 天 | Phase 5 通过 | 待执行 |
| Phase 7 | 聚合验证与文档回链 | 0.5 天 | Phase 1-6 通过 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/EvidenceState.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DynamicPlanMutation.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorCheckpoint.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssemblerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java`
- `backend/src/main/java/cn/bugstack/competitoragent/repository/TaskWorkflowEventRepository.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`

### Backend - Test

- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisherTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`

### Docs - Modify

- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- `docs/superpowers/agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md`

---

### Task 1: 锁定 P1 运行期契约红灯

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationContractTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/EvidenceState.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyRuleSet.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyResult.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DynamicPlanMutation.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionTrace.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestratorCheckpoint.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationContext.java`

- [ ] **Step 1: 写 P1 契约红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNormalizeDecisionWithExplicitEvidenceGapWhenSourceUrlsAreEmpty() throws Exception {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("current_node_and_downstream")
                .priority("high")
                .targetSection("pricing")
                .reason("终审发现 pricing 缺少可追溯来源")
                .confidence(0.84d)
                .inputRefs(java.util.Map.of(
                        "qualityDiagnosisIds", List.of("qd-001"),
                        "agentSuggestionIds", List.of(),
                        "triggerNodeName", "quality_check_final"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("Notion AI pricing official"))
                .build()
                .normalized();

        assertThat(decision.getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_AND_DOWNSTREAM");
        assertThat(decision.getPriority()).isEqualTo("HIGH");
        assertThat(decision.getConfidence()).isEqualTo(0.84d);
        assertThat(decision.getInputRefs()).containsEntry("triggerNodeName", "quality_check_final");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(decision.getSourceUrls()).isEmpty();
        assertThat(objectMapper.writeValueAsString(decision))
                .contains("evidenceState")
                .contains("affectedScope")
                .contains("inputRefs");
    }

    @Test
    void shouldCarryDecisionTraceAndCheckpointSeparately() {
        DecisionTrace trace = DecisionTrace.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .policyAllowed(true)
                .executionStatus("APPLIED")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        OrchestratorCheckpoint checkpoint = OrchestratorCheckpoint.builder()
                .checkpointId("oc-001")
                .taskId(50L)
                .lastDecisionId("od-001")
                .lastMutationId("dpm-001")
                .pendingActions(List.of("WAITING_FOR_SUPPLEMENT_RESULT"))
                .decisionCount(1)
                .maxAutoDecisions(2)
                .resumeAfterNodeName("collect_revision_evidence_v2_1")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        assertThat(trace.getExecutionStatus()).isEqualTo("APPLIED");
        assertThat(checkpoint.getPendingActions()).containsExactly("WAITING_FOR_SUPPLEMENT_RESULT");
        assertThat(checkpoint.getDecisionCount()).isEqualTo(1);
        assertThat(checkpoint.getResumeAfterNodeName()).isEqualTo("collect_revision_evidence_v2_1");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest" test
```

Expected:

- FAIL
- `package cn.bugstack.competitoragent.orchestration does not exist`
- `OrchestrationDecision` 等契约不存在

- [ ] **Step 3: 新增 `EvidenceState`**

```java
package cn.bugstack.competitoragent.orchestration;

/**
 * 编排决策的证据状态。
 * 该枚举用于保证 Orchestrator 每次决策都能说明来源是否充足，
 * 避免缺少 sourceUrls 的判断被静默当作可靠事实继续执行。
 */
public enum EvidenceState {
    FULL_SOURCE,
    PARTIAL_SOURCE,
    MISSING_SOURCE,
    NOT_APPLICABLE
}
```

- [ ] **Step 4: 新增 `OrchestrationDecision`**

```java
package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrator 的正式运行期编排决策。
 * Reviewer 只负责输出质量事实，本对象才表达“接下来由编排层做什么”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationDecision {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    private String targetNode;
    private String affectedScope;
    private String priority;
    private String targetSection;
    private String reason;
    private boolean requiresHumanIntervention;
    private boolean requiresConfirmation;
    private Double confidence;
    @Builder.Default
    private List<String> suggestedQueries = List.of();
    @Builder.Default
    private Map<String, Object> inputRefs = Map.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    public OrchestrationDecision normalized() {
        String normalizedDecisionType = upperOrDefault(decisionType, "WAIT_FOR_HUMAN");
        EvidenceState resolvedEvidenceState = evidenceState;
        double normalizedConfidence = confidence == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, confidence));
        List<String> normalizedSourceUrls = normalizeDistinctList(sourceUrls);
        if (resolvedEvidenceState == null) {
            resolvedEvidenceState = normalizedSourceUrls.isEmpty()
                    ? EvidenceState.MISSING_SOURCE
                    : EvidenceState.FULL_SOURCE;
        }
        return toBuilder()
                .decisionType(normalizedDecisionType)
                .actionType(upperOrDefault(actionType, "MANUAL_REVIEW"))
                .targetNode(blankToNull(targetNode))
                .affectedScope(upperOrDefault(affectedScope, "CURRENT_NODE_ONLY"))
                .priority(upperOrDefault(priority, "MEDIUM"))
                .targetSection(blankToNull(targetSection))
                .reason(blankToDefault(reason, "编排决策缺少明确原因，已降级为人工确认。"))
                .requiresHumanIntervention(requiresHumanIntervention || "WAIT_FOR_HUMAN".equals(normalizedDecisionType))
                .confidence(normalizedConfidence)
                .suggestedQueries(normalizeDistinctList(suggestedQueries))
                .inputRefs(normalizeInputRefs(inputRefs))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(resolvedEvidenceState)
                .build();
    }

    private String upperOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private Map<String, Object> normalizeInputRefs(Map<String, Object> value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value != null && !value.isEmpty()) {
            normalized.putAll(value);
        }
        normalized.putIfAbsent("qualityDiagnosisIds", List.of());
        normalized.putIfAbsent("agentSuggestionIds", List.of());
        normalized.putIfAbsent("triggerNodeName", blankToNull(triggerNodeName));
        return normalized;
    }
}
```

- [ ] **Step 5: 新增策略、结果、mutation、trace、checkpoint、context 契约**

```java
package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Orchestrator 决策策略集。
 * 第一版使用代码解释这些规则，不引入独立规则引擎。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionPolicyRuleSet {

    @Builder.Default
    private String policyVersion = "ORCHESTRATION_POLICY_V1";
    @Builder.Default
    private List<String> allowedDecisionTypes = List.of(
            "APPEND_DYNAMIC_BRANCH",
            "RERUN_NODE",
            "REWRITE_ONLY",
            "WAIT_FOR_HUMAN",
            "NO_ACTION"
    );
    @Builder.Default
    private List<String> allowedDynamicActions = List.of(
            "CREATE_SUPPLEMENT_BRANCH",
            "CREATE_RERUN_BRANCH",
            "CREATE_REWRITE_BRANCH",
            "MANUAL_ONLY"
    );
    @Builder.Default
    private boolean requireSourceUrlsOrEvidenceGap = true;
    /** 稳定演示版使用更保守的默认值 2，低于规格长期目标 5。 */
    @Builder.Default
    private int maxAutoDecisions = 2;
    /** 稳定演示版默认每个 section 只允许 1 条动态分支，避免演示任务发散。 */
    @Builder.Default
    private int maxDynamicBranchesPerSection = 1;
    @Builder.Default
    private int maxSearchQueriesPerDecision = 5;
    @Builder.Default
    private List<String> confirmationRequiredDecisionTypes = List.of("RERUN_NODE");
    @Builder.Default
    private List<String> blockedTaskStatuses = List.of("STOPPED");
    @Builder.Default
    private List<String> blockedNodeStatuses = List.of("RUNNING");
    /**
     * 协议层保留 riskRules，P1 只用固定代码解释默认两条规则，
     * 不引入通用表达式引擎。
     */
    @Builder.Default
    private List<PolicyRiskRule> riskRules = List.of(
            PolicyRiskRule.builder()
                    .ruleId("rerun_downstream_requires_confirmation")
                    .when("decisionType == 'RERUN_NODE' && affectedScope == 'CURRENT_NODE_AND_DOWNSTREAM'")
                    .riskLevel("HIGH")
                    .requiresConfirmation(true)
                    .build(),
            PolicyRiskRule.builder()
                    .ruleId("missing_source_requires_supplement")
                    .when("evidenceState == 'MISSING_SOURCE' && actionType != 'SUPPLEMENT_EVIDENCE'")
                    .riskLevel("HIGH")
                    .requiresConfirmation(true)
                    .build()
    );
    @Builder.Default
    private List<String> sourceUrls = List.of();
    @Builder.Default
    private EvidenceState evidenceState = EvidenceState.NOT_APPLICABLE;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyRiskRule {
        private String ruleId;
        private String when;
        private String riskLevel;
        private boolean requiresConfirmation;
    }
}
```

```java
package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 决策策略校验结果。
 * 只有 allowed=true 的结果才能被 DecisionExecutorAdapter 翻译为动态计划变更。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionPolicyResult {
    private String decisionId;
    private boolean allowed;
    private String riskLevel;
    private boolean requiresConfirmation;
    @Builder.Default
    private List<String> blockedReasons = List.of();
    private String normalizedAction;
    @Builder.Default
    private List<String> policyRuleRefs = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String policyVersion;
}
```

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 已校验编排决策对应的动态计划变更。
 * 它只描述“计划要怎样变”，不直接执行任何 Agent 或外部工具。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DynamicPlanMutation {
    private String mutationId;
    private String decisionId;
    private String mutationType;
    private Long targetPlanVersionId;
    private String branchReason;
    private String dynamicAction;
    @Builder.Default
    private List<WorkflowPlan.WorkflowPlanNode> nodeTemplates = List.of();
    private String runtimeCommand;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String expectedResumeNodeName;
}
```

```java
package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 追加式编排决策审计记录。
 * 它回答“系统做过什么决策”，不承担恢复游标职责。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTrace {
    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String inputSummary;
    private String decisionType;
    private boolean policyAllowed;
    private String executionStatus;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private LocalDateTime createdAt;

    public DecisionTrace normalized() {
        return toBuilder()
                .executionStatus(executionStatus == null || executionStatus.isBlank() ? "CREATED" : executionStatus.trim())
                .evidenceState(evidenceState == null ? EvidenceState.MISSING_SOURCE : evidenceState)
                .createdAt(createdAt == null ? LocalDateTime.now() : createdAt)
                .build();
    }
}
```

```java
package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestrator 运行期恢复游标。
 * 它回答“下次恢复时 Orchestrator 应该从哪里继续判断”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorCheckpoint {
    private String checkpointId;
    private Long taskId;
    private Long planVersionId;
    private String branchKey;
    private String lastDecisionId;
    private String lastMutationId;
    @Builder.Default
    private List<String> pendingActions = List.of();
    private int decisionCount;
    private int maxAutoDecisions;
    private String resumeAfterNodeName;
    private String resumeReason;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public OrchestratorCheckpoint normalized() {
        LocalDateTime now = LocalDateTime.now();
        return toBuilder()
                .evidenceState(evidenceState == null ? EvidenceState.MISSING_SOURCE : evidenceState)
                .createdAt(createdAt == null ? now : createdAt)
                .updatedAt(updatedAt == null ? now : updatedAt)
                .build();
    }
}
```

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 运行期编排上下文。
 * P1 只承载终审失败后的最小输入，不接入 P2 的前置协作规划。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationContext {
    private Long taskId;
    private Long planVersionId;
    private String branchKey;
    private String triggerNodeName;
    private String reviewStage;
    private boolean passed;
    private boolean requiresHumanIntervention;
    private int currentDecisionCount;
    @Builder.Default
    private List<QualityDiagnosis> diagnoses = List.of();
    @Builder.Default
    private List<RevisionDirective> legacyRevisionDirectives = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String inputSummary;
}
```

- [ ] **Step 6: 运行契约测试确认转绿**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest" test
```

Expected:

- PASS
- `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: 提交 Task 1**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationContractTest.java
git commit -m "feat: add orchestration runtime contracts"
```

---

### Task 2: 兼容期 RevisionDirective -> OrchestrationDecision 适配器

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapterTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`

- [ ] **Step 1: 写适配器红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionAdapterTest {

    private final OrchestrationDecisionAdapter adapter = new OrchestrationDecisionAdapter();

    @Test
    void shouldConvertEvidenceGapDirectiveToAppendDynamicBranchDecision() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EVIDENCE_GAP")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetSection("pricing")
                .summary("补充定价页证据")
                .searchQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of())
                .build();

        OrchestrationDecision decision = adapter.fromRevisionDirective(50L, "quality_check_final", directive, 1);

        assertThat(decision.getDecisionId()).isEqualTo("od-50-quality_check_final-1");
        assertThat(decision.getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decision.getTargetNode()).isEqualTo("collect_sources");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_AND_DOWNSTREAM");
        assertThat(decision.getPriority()).isEqualTo("HIGH");
        assertThat(decision.getTargetSection()).isEqualTo("pricing");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(decision.getInputRefs()).containsEntry("triggerNodeName", "quality_check_final");
        assertThat(decision.getSuggestedQueries()).containsExactly("Notion AI pricing official");
    }

    @Test
    void shouldConvertExpressionDirectiveToRewriteOnlyDecision() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EXPRESSION_ISSUE")
                .targetSection("结论")
                .summary("收紧绝对化表述")
                .sourceUrls(List.of("https://example.com/report"))
                .build();

        OrchestrationDecision decision = adapter.fromRevisionDirective(50L, "quality_check_final", directive, 2);

        assertThat(decision.getDecisionType()).isEqualTo("REWRITE_ONLY");
        assertThat(decision.getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(decision.getTargetNode()).isEqualTo("rewrite_report");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionAdapterTest" test
```

Expected:

- FAIL
- `OrchestrationDecisionAdapter` 不存在

- [ ] **Step 3: 实现适配器**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容期修订指令适配器。
 * 它把历史 Reviewer 输出转换为新的 Orchestrator 决策，避免动态补图继续直接消费 orchestrationAction。
 */
@Component
public class OrchestrationDecisionAdapter {

    public OrchestrationDecision fromRevisionDirective(Long taskId,
                                                       String triggerNodeName,
                                                       RevisionDirective directive,
                                                       int index) {
        RevisionDirective normalized = directive == null ? null : directive.normalized();
        if (normalized == null) {
            return manualDecision(taskId, triggerNodeName, index, "空修订指令需要人工确认");
        }
        List<String> sourceUrls = normalized.getSourceUrls() == null ? List.of() : normalized.getSourceUrls();
        EvidenceState evidenceState = sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
        return OrchestrationDecision.builder()
                .decisionId("od-" + taskId + "-" + triggerNodeName + "-" + index)
                .taskId(taskId)
                .triggerNodeName(triggerNodeName)
                .decisionType(resolveDecisionType(normalized))
                .actionType(normalized.getActionType())
                .targetNode(resolveTargetNode(normalized))
                .affectedScope(resolveAffectedScope(normalized))
                .priority(resolvePriority(normalized, evidenceState))
                .targetSection(normalized.getTargetSection())
                .reason(normalized.getSummary())
                .requiresHumanIntervention(false)
                .requiresConfirmation(false)
                .confidence(resolveConfidence(normalized))
                .suggestedQueries(normalized.getSearchQueries())
                .inputRefs(buildInputRefs(triggerNodeName))
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .build()
                .normalized();
    }

    private OrchestrationDecision manualDecision(Long taskId, String triggerNodeName, int index, String reason) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + taskId + "-" + triggerNodeName + "-" + index)
                .taskId(taskId)
                .triggerNodeName(triggerNodeName)
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .reason(reason)
                .priority("HIGH")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .confidence(0.10d)
                .inputRefs(buildInputRefs(triggerNodeName))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }

    private String resolveDecisionType(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "APPEND_DYNAMIC_BRANCH";
            case "RERUN_NODE" -> "RERUN_NODE";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "REWRITE_ONLY";
            case "MANUAL_REVIEW" -> "WAIT_FOR_HUMAN";
            default -> "WAIT_FOR_HUMAN";
        };
    }

    private String resolveTargetNode(RevisionDirective directive) {
        if (directive.getTargetNode() != null && !directive.getTargetNode().isBlank()) {
            return directive.getTargetNode();
        }
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "collect_sources";
            case "RERUN_NODE" -> "extract_schema";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "rewrite_report";
            default -> "quality_check_final";
        };
    }

    private String resolveAffectedScope(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE", "RERUN_NODE" -> "CURRENT_NODE_AND_DOWNSTREAM";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CURRENT_NODE_ONLY";
            default -> "CURRENT_NODE_ONLY";
        };
    }

    private String resolvePriority(RevisionDirective directive, EvidenceState evidenceState) {
        if (EvidenceState.MISSING_SOURCE == evidenceState && !"SUPPLEMENT_EVIDENCE".equals(directive.getActionType())) {
            return "HIGH";
        }
        if ("RERUN_NODE".equals(directive.getActionType())) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private double resolveConfidence(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> 0.85d;
            case "RERUN_NODE" -> 0.70d;
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> 0.78d;
            default -> 0.40d;
        };
    }

    /**
     * P1 不回溯改造 QualityDiagnosis 主键，因此先用触发节点生成稳定 inputRefs，
     * 后续等诊断对象具备正式 ID 后再替换为权威引用。
     */
    private Map<String, Object> buildInputRefs(String triggerNodeName) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("qualityDiagnosisIds", List.of());
        refs.put("agentSuggestionIds", List.of());
        refs.put("triggerNodeName", triggerNodeName);
        return refs;
    }
}
```

- [ ] **Step 4: 更新 `RevisionDirective` 类注释，冻结兼容期边界**

Modify the class comment in `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java`:

```java
/**
 * 修订驱动指令。
 * <p>
 * 3.4 P1 之后，该对象只作为 Reviewer 兼容期展示/修订建议载体。
 * 新的动态补图不得再把 orchestrationAction 当作唯一正式决策来源，
 * 必须先经 OrchestrationDecisionAdapter 转成 OrchestrationDecision，
 * 再由 DecisionPolicyService 校验后交给 DecisionExecutorAdapter。
 */
```

- [ ] **Step 5: 运行适配器测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionAdapterTest,RevisionDirectiveTest" test
```

Expected:

- PASS
- 历史 `RevisionDirectiveTest` 不被破坏

- [ ] **Step 6: 提交 Task 2**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapter.java backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/RevisionDirective.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionAdapterTest.java
git commit -m "feat: adapt revision directives to orchestration decisions"
```

---

### Task 3: DecisionPolicyService 策略校验

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`

- [ ] **Step 1: 写策略红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyServiceTest {

    private final DecisionPolicyService service = new DecisionPolicyService();

    @Test
    void shouldAllowSupplementEvidenceWhenSourceGapIsExplicit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("Notion AI pricing official"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(result.getPolicyRuleRefs()).contains("allowedDecisionTypes", "requireSourceUrlsOrEvidenceGap", "maxSearchQueriesPerDecision");
    }

    @Test
    void shouldBlockUnknownDecisionType() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-002")
                .decisionType("FREE_FORM_DAG")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("decisionType 不在允许列表：FREE_FORM_DAG");
    }

    @Test
    void shouldRequireConfirmationForRerunNode() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-003")
                .decisionType("RERUN_NODE")
                .actionType("RERUN_NODE")
                .targetNode("extract_schema")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("MEDIUM")
                .sourceUrls(List.of("https://example.com/source"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getPolicyRuleRefs()).contains("rerun_downstream_requires_confirmation");
    }

    @Test
    void shouldBlockWhenAutoDecisionLimitIsReached() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-004")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(1).build(),
                1,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("自动编排次数已达到上限：1/1");
    }

    @Test
    void shouldElevateRewriteOnlyDecisionWhenSourceIsMissing() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-005")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .priority("MEDIUM")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
    }

    @Test
    void shouldBlockWhenSuggestedQueriesExceedConfiguredLimit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-006")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("q1", "q2", "q3", "q4", "q5", "q6"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxSearchQueriesPerDecision(5).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("搜索补证 query 数量超过上限：6/5");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest" test
```

Expected:

- FAIL
- `DecisionPolicyService` 不存在

- [ ] **Step 3: 实现策略服务**

```java
package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排决策策略服务。
 * 该服务只负责校验 Orchestrator 决策能否执行，不生成业务判断。
 */
@Service
public class DecisionPolicyService {

    public DecisionPolicyResult evaluate(OrchestrationDecision rawDecision,
                                         DecisionPolicyRuleSet ruleSet,
                                         int currentDecisionCount,
                                         String taskStatus,
                                         String triggerNodeStatus) {
        OrchestrationDecision decision = rawDecision == null
                ? OrchestrationDecision.builder()
                .decisionId("od-invalid")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized()
                : rawDecision.normalized();
        DecisionPolicyRuleSet rules = ruleSet == null ? DecisionPolicyRuleSet.builder().build() : ruleSet;

        List<String> blockedReasons = new ArrayList<>();
        List<String> ruleRefs = new ArrayList<>();
        if (!rules.getAllowedDecisionTypes().contains(decision.getDecisionType())) {
            blockedReasons.add("decisionType 不在允许列表：" + decision.getDecisionType());
        } else {
            ruleRefs.add("allowedDecisionTypes");
        }
        if (rules.isRequireSourceUrlsOrEvidenceGap()
                && decision.getSourceUrls().isEmpty()
                && decision.getEvidenceState() != EvidenceState.MISSING_SOURCE) {
            blockedReasons.add("缺少 sourceUrls 且未显式声明 MISSING_SOURCE");
        } else {
            ruleRefs.add("requireSourceUrlsOrEvidenceGap");
        }
        if (decision.getSuggestedQueries().size() > rules.getMaxSearchQueriesPerDecision()) {
            blockedReasons.add("搜索补证 query 数量超过上限："
                    + decision.getSuggestedQueries().size() + "/" + rules.getMaxSearchQueriesPerDecision());
        } else {
            ruleRefs.add("maxSearchQueriesPerDecision");
        }
        if (currentDecisionCount >= rules.getMaxAutoDecisions()) {
            blockedReasons.add("自动编排次数已达到上限：" + currentDecisionCount + "/" + rules.getMaxAutoDecisions());
        }
        if (rules.getBlockedTaskStatuses().contains(taskStatus)) {
            blockedReasons.add("当前任务状态禁止自动编排：" + taskStatus);
        }
        if (rules.getBlockedNodeStatuses().contains(triggerNodeStatus)) {
            blockedReasons.add("触发节点状态禁止自动编排：" + triggerNodeStatus);
        }

        String normalizedAction = resolveNormalizedAction(decision);
        List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRiskRules = matchRiskRules(decision, rules);
        matchedRiskRules.stream()
                .map(DecisionPolicyRuleSet.PolicyRiskRule::getRuleId)
                .forEach(ruleRefs::add);
        boolean requiresConfirmation = decision.isRequiresHumanIntervention()
                || decision.isRequiresConfirmation()
                || rules.getConfirmationRequiredDecisionTypes().contains(decision.getDecisionType())
                || matchedRiskRules.stream().anyMatch(DecisionPolicyRuleSet.PolicyRiskRule::isRequiresConfirmation);
        String riskLevel = resolveRiskLevel(decision, matchedRiskRules, requiresConfirmation);

        return DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .allowed(blockedReasons.isEmpty())
                .riskLevel(riskLevel)
                .requiresConfirmation(requiresConfirmation)
                .blockedReasons(blockedReasons)
                .normalizedAction(normalizedAction)
                .policyRuleRefs(ruleRefs)
                .sourceUrls(decision.getSourceUrls())
                .evidenceState(decision.getEvidenceState())
                .policyVersion(rules.getPolicyVersion())
                .build();
    }

    private String resolveNormalizedAction(OrchestrationDecision decision) {
        return switch (decision.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
            case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
            case "MANUAL_REVIEW" -> "MANUAL_ONLY";
            default -> "NO_ACTION";
        };
    }

    /**
     * P1 不解析 riskRules.when 表达式，先按 ruleId 执行两条冻结规则，
     * 既保留协议显式化，也避免为演示版引入规则引擎。
     */
    private List<DecisionPolicyRuleSet.PolicyRiskRule> matchRiskRules(OrchestrationDecision decision,
                                                                      DecisionPolicyRuleSet rules) {
        List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRules = new ArrayList<>();
        for (DecisionPolicyRuleSet.PolicyRiskRule rule : rules.getRiskRules()) {
            if ("rerun_downstream_requires_confirmation".equals(rule.getRuleId())
                    && "RERUN_NODE".equals(decision.getDecisionType())
                    && "CURRENT_NODE_AND_DOWNSTREAM".equals(decision.getAffectedScope())) {
                matchedRules.add(rule);
            }
            if ("missing_source_requires_supplement".equals(rule.getRuleId())
                    && decision.getEvidenceState() == EvidenceState.MISSING_SOURCE
                    && !"SUPPLEMENT_EVIDENCE".equals(decision.getActionType())) {
                matchedRules.add(rule);
            }
        }
        return matchedRules;
    }

    private String resolveRiskLevel(OrchestrationDecision decision,
                                    List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRiskRules,
                                    boolean requiresConfirmation) {
        if (!matchedRiskRules.isEmpty()) {
            return matchedRiskRules.stream()
                    .map(DecisionPolicyRuleSet.PolicyRiskRule::getRiskLevel)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("HIGH");
        }
        if (requiresConfirmation) {
            return "HIGH";
        }
        String priority = decision.getPriority();
        return priority == null || priority.isBlank() ? "MEDIUM" : priority;
    }
}
```

- [ ] **Step 4: 运行策略测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest" test
```

Expected:

- PASS
- `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交 Task 3**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
git commit -m "feat: add orchestration decision policy service"
```

---

### Task 4: 规则优先 OrchestrationDecisionService

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`

- [ ] **Step 1: 写 Orchestrator 决策服务红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionServiceTest {

    private final OrchestrationDecisionService service =
            new OrchestrationDecisionService(new OrchestrationDecisionAdapter());

    @Test
    void shouldGenerateSupplementDecisionForFinalReviewEvidenceGap() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("missing_evidence")
                        .section("pricing")
                        .severity("ERROR")
                        .sourceUrls(List.of())
                        .repairSuggestion("补充官方定价页")
                        .build()))
                .legacyRevisionDirectives(List.of(RevisionDirective.builder()
                        .category("EVIDENCE_GAP")
                        .targetSection("pricing")
                        .summary("补充官方定价页")
                        .searchQueries(List.of("Notion AI pricing official"))
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decisions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
    }

    @Test
    void shouldWaitForHumanWhenReviewRequiresHumanIntervention() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(true)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresConfirmation()).isTrue();
    }

    @Test
    void shouldReturnNoActionWhenReviewPassed() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(true)
                .sourceUrls(List.of("https://example.com"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("NO_ACTION");
    }

    @Test
    void shouldWaitForHumanWhenBlockingDiagnosisExistsWithoutDirectives() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("missing_evidence")
                        .section("pricing")
                        .severity("ERROR")
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
    }

    @Test
    void shouldFallbackToNoActionWhenNoDirectiveAndNoBlockingDiagnosis() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("wording_issue")
                        .section("结论")
                        .severity("WARN")
                        .build()))
                .sourceUrls(List.of("https://example.com"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("NO_ACTION");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest" test
```

Expected:

- FAIL
- `OrchestrationDecisionService` 不存在

- [ ] **Step 3: 实现规则优先决策服务**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规则优先的运行期 Orchestrator 决策服务。
 * P1 只处理终审失败后的质量回流，不调用 LLM，保证演示版本稳定可复现。
 */
@Service
@RequiredArgsConstructor
public class OrchestrationDecisionService {

    private final OrchestrationDecisionAdapter decisionAdapter;

    public List<OrchestrationDecision> decide(OrchestrationContext context) {
        if (context == null) {
            return List.of();
        }
        if (context.isPassed()) {
            return List.of(noAction(context));
        }
        if (context.isRequiresHumanIntervention()) {
            return List.of(waitForHuman(context, "终审要求人工介入，禁止自动补图。"));
        }
        if (context.getLegacyRevisionDirectives() != null && !context.getLegacyRevisionDirectives().isEmpty()) {
            AtomicInteger index = new AtomicInteger(1);
            return context.getLegacyRevisionDirectives().stream()
                    .map(directive -> decisionAdapter.fromRevisionDirective(
                            context.getTaskId(),
                            context.getTriggerNodeName(),
                            directive,
                            index.getAndIncrement()))
                    .toList();
        }
        if (hasBlockingDiagnosis(context)) {
            return List.of(OrchestrationDecision.builder()
                    .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-1")
                    .taskId(context.getTaskId())
                    .triggerNodeName(context.getTriggerNodeName())
                    .decisionType("WAIT_FOR_HUMAN")
                    .actionType("MANUAL_REVIEW")
                    .targetNode(context.getTriggerNodeName())
                    .affectedScope("CURRENT_NODE_ONLY")
                    .reason("存在阻塞级质量诊断，但缺少可执行修订指令。")
                    .priority("HIGH")
                    .requiresHumanIntervention(true)
                    .requiresConfirmation(true)
                    .confidence(0.35d)
                    .inputRefs(buildInputRefs(context))
                    .sourceUrls(context.getSourceUrls())
                    .evidenceState(resolveEvidenceState(context))
                    .build()
                    .normalized());
        }
        return List.of(noAction(context));
    }

    private boolean hasBlockingDiagnosis(OrchestrationContext context) {
        return context.getDiagnoses() != null && context.getDiagnoses().stream()
                .anyMatch(diagnosis -> "ERROR".equalsIgnoreCase(diagnosis.getSeverity())
                        || "BLOCKER".equalsIgnoreCase(diagnosis.getLevel()));
    }

    private OrchestrationDecision noAction(OrchestrationContext context) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-noop")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .targetNode(context.getTriggerNodeName())
                .affectedScope("CURRENT_NODE_ONLY")
                .reason("当前终审已通过或无可执行编排动作。")
                .priority("LOW")
                .requiresHumanIntervention(false)
                .confidence(0.95d)
                .inputRefs(buildInputRefs(context))
                .sourceUrls(context.getSourceUrls())
                .evidenceState(resolveEvidenceState(context))
                .build()
                .normalized();
    }

    private OrchestrationDecision waitForHuman(OrchestrationContext context, String reason) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-human")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode(context.getTriggerNodeName())
                .affectedScope("CURRENT_NODE_ONLY")
                .reason(reason)
                .priority("HIGH")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .confidence(0.20d)
                .inputRefs(buildInputRefs(context))
                .sourceUrls(context.getSourceUrls())
                .evidenceState(resolveEvidenceState(context))
                .build()
                .normalized();
    }

    private EvidenceState resolveEvidenceState(OrchestrationContext context) {
        if (context.getEvidenceState() != null) {
            return context.getEvidenceState();
        }
        return context.getSourceUrls() == null || context.getSourceUrls().isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    /**
     * P1 先用触发节点 + 下标生成临时诊断引用，保证审计链路能追溯到本次决策输入；
     * 后续若 QualityDiagnosis 引入正式 ID，再切换到权威引用。
     */
    private Map<String, Object> buildInputRefs(OrchestrationContext context) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("qualityDiagnosisIds", collectDiagnosisRefs(context));
        refs.put("agentSuggestionIds", List.of());
        refs.put("triggerNodeName", context == null ? null : context.getTriggerNodeName());
        return refs;
    }

    private List<String> collectDiagnosisRefs(OrchestrationContext context) {
        List<String> diagnosisRefs = new ArrayList<>();
        if (context == null || context.getDiagnoses() == null) {
            return diagnosisRefs;
        }
        for (int index = 0; index < context.getDiagnoses().size(); index++) {
            diagnosisRefs.add("qd-" + context.getTriggerNodeName() + "-" + (index + 1));
        }
        return diagnosisRefs;
    }
}
```

- [ ] **Step 4: 运行决策服务测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest" test
```

Expected:

- PASS

- [ ] **Step 5: 提交 Task 4**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java
git commit -m "feat: add rule-first orchestration decision service"
```

---

### Task 5: DecisionExecutorAdapter 与 DynamicPlanMutation

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssemblerTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssembler.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphServiceTest.java`

- [ ] **Step 1: 写 adapter 红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionExecutorAdapterTest {

    private final DecisionExecutorAdapter adapter = new DecisionExecutorAdapter();

    @Test
    void shouldTranslateAllowedSupplementDecisionToAppendNodesMutation() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .reason("补充定价证据")
                .suggestedQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .policyVersion("ORCHESTRATION_POLICY_V1")
                .build();

        DynamicPlanMutation mutation = adapter.toMutation(decision, policyResult, 8L, 2);

        assertThat(mutation.getMutationId()).isEqualTo("dpm-od-001");
        assertThat(mutation.getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(mutation.getDynamicAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(mutation.getExpectedResumeNodeName()).isEqualTo("collect_revision_evidence_v2_1");
        assertThat(mutation.getNodeTemplates()).hasSize(1);
        assertThat(mutation.getNodeTemplates().get(0).getNodeConfig()).contains("decisionId");
    }

    @Test
    void shouldReturnNoMutationWhenPolicyBlocksDecision() {
        DynamicPlanMutation mutation = adapter.toMutation(
                OrchestrationDecision.builder()
                        .decisionId("od-blocked")
                        .decisionType("APPEND_DYNAMIC_BRANCH")
                        .actionType("SUPPLEMENT_EVIDENCE")
                        .evidenceState(EvidenceState.MISSING_SOURCE)
                        .build()
                        .normalized(),
                DecisionPolicyResult.builder()
                        .decisionId("od-blocked")
                        .allowed(false)
                        .blockedReasons(List.of("blocked"))
                        .build(),
                8L,
                2);

        assertThat(mutation.getMutationType()).isEqualTo("NO_MUTATION");
        assertThat(mutation.getNodeTemplates()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionExecutorAdapterTest" test
```

Expected:

- FAIL
- `DecisionExecutorAdapter` 不存在

- [ ] **Step 3: 实现 `DecisionExecutorAdapter`**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 已校验编排决策到动态计划变更的翻译器。
 * 它只翻译策略已允许的决策，不重新做质量判断，也不直接执行节点。
 */
@Component
public class DecisionExecutorAdapter {

    private final ObjectMapper objectMapper;

    public DecisionExecutorAdapter() {
        this(new ObjectMapper());
    }

    public DecisionExecutorAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DynamicPlanMutation toMutation(OrchestrationDecision decision,
                                          DecisionPolicyResult policyResult,
                                          Long targetPlanVersionId,
                                          int nextPlanVersion) {
        if (decision == null || policyResult == null || !policyResult.isAllowed()) {
            return noMutation(decision, policyResult);
        }
        String normalizedAction = policyResult.getNormalizedAction();
        if ("CREATE_SUPPLEMENT_BRANCH".equals(normalizedAction)) {
            String expectedNodeName = "collect_revision_evidence_v" + nextPlanVersion + "_1";
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("APPEND_NODES")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                            .nodeName(expectedNodeName)
                            .displayName("补充证据采集")
                            .agentType(AgentType.COLLECTOR.name())
                            .dependsOn(List.of(decision.getTriggerNodeName()))
                            .required(true)
                            .executionOrder(0)
                            .nodeConfig(writeJson(new LinkedHashMap<>() {{
                                put("decisionId", decision.getDecisionId());
                                put("sourceType", "SUPPLEMENTAL");
                                put("searchQueries", decision.getSuggestedQueries());
                                put("sourceUrls", decision.getSourceUrls());
                                put("evidenceState", decision.getEvidenceState().name());
                                put("summary", decision.getReason());
                                put("targetSection", decision.getTargetSection());
                            }}))
                            .notes("Orchestrator 决策触发的动态补证分支")
                            .dynamicNode(true)
                            .originNodeName(decision.getTriggerNodeName())
                            .build()))
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .expectedResumeNodeName(expectedNodeName)
                    .build();
        }
        if ("CREATE_REWRITE_BRANCH".equals(normalizedAction)) {
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("APPEND_NODES")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .nodeTemplates(List.of())
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .expectedResumeNodeName("rewrite_revision_patch_v" + nextPlanVersion)
                    .build();
        }
        if ("MANUAL_ONLY".equals(normalizedAction)) {
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("MARK_WAITING_INTERVENTION")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .build();
        }
        return noMutation(decision, policyResult);
    }

    private DynamicPlanMutation noMutation(OrchestrationDecision decision, DecisionPolicyResult policyResult) {
        String decisionId = decision == null ? "unknown" : decision.getDecisionId();
        return DynamicPlanMutation.builder()
                .mutationId("dpm-" + decisionId)
                .decisionId(decisionId)
                .mutationType("NO_MUTATION")
                .branchReason("POLICY_BLOCKED_OR_NO_ACTION")
                .sourceUrls(policyResult == null ? List.of() : policyResult.getSourceUrls())
                .evidenceState(policyResult == null ? EvidenceState.NOT_APPLICABLE : policyResult.getEvidenceState())
                .build();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize dynamic mutation node config failed", e);
        }
    }
}
```

- [ ] **Step 4: 为 `CompensationGraphAssembler` 增加 mutation 入口**

- [ ] **Step 4: 先写 `CompensationGraphAssembler` mutation 入口测试**

Create `backend/src/test/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssemblerTest.java`:

```java
package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationGraphAssemblerTest {

    private final CompensationGraphAssembler assembler =
            new CompensationGraphAssembler(new ObjectMapper());

    @Test
    void shouldAssembleSupplementMutationIntoCollectorExtractAnalyzeRewriteReviewChain() {
        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(50L)
                .planVersion(1)
                .branchKey("root")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .dynamicAction("CREATE_SUPPLEMENT_BRANCH")
                .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("collect_revision_evidence_v2_1")
                        .displayName("补充证据采集")
                        .agentType(AgentType.COLLECTOR.name())
                        .nodeConfig("{\"decisionId\":\"od-001\"}")
                        .build()))
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                parentPlan, triggerNode, mutation, 10, "root/review-2");

        assertThat(nodes).extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
                .containsExactly(
                        "collect_revision_evidence_v2_1",
                        "extract_revision_patch_v2",
                        "analyze_revision_patch_v2",
                        "rewrite_revision_patch_v2",
                        "quality_check_revision_patch_v2");
    }

    @Test
    void shouldAssembleRewriteMutationIntoRewriteAndReviewOnly() {
        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(50L)
                .planVersion(1)
                .branchKey("root")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-002")
                .decisionId("od-002")
                .mutationType("APPEND_NODES")
                .dynamicAction("CREATE_REWRITE_BRANCH")
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                parentPlan, triggerNode, mutation, 10, "root/review-2");

        assertThat(nodes).extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
                .containsExactly("rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    }

    @Test
    void shouldReturnEmptyWhenMutationIsNotAppendNodes() {
        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                TaskPlan.builder().planVersion(1).build(),
                TaskNode.builder().nodeName("quality_check_final").build(),
                DynamicPlanMutation.builder()
                        .mutationType("NO_MUTATION")
                        .dynamicAction("MANUAL_ONLY")
                        .build(),
                10,
                "root/review-2");

        assertThat(nodes).isEmpty();
    }
}
```

- [ ] **Step 5: 为 `CompensationGraphAssembler` 增加 mutation 入口**

Add a public method to `backend/src/main/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssembler.java`:

```java
/**
 * 基于 Orchestrator 已校验的动态计划变更生成节点。
 * P1 先复用现有补图模板，确保新决策不会绕过 DAG 执行层。
 */
public List<WorkflowPlan.WorkflowPlanNode> assembleDynamicNodes(TaskPlan parentPlan,
                                                                TaskNode triggerNode,
                                                                cn.bugstack.competitoragent.orchestration.DynamicPlanMutation mutation,
                                                                int startOrder,
                                                                String derivedBranchKey) {
    if (mutation == null || !"APPEND_NODES".equals(mutation.getMutationType())
            || parentPlan == null || triggerNode == null) {
        return List.of();
    }
    List<WorkflowPlan.WorkflowPlanNode> dynamicNodes = new ArrayList<>();
    int order = startOrder;
    int planVersion = parentPlan.getPlanVersion() + 1;
    List<String> collectorDependencies = new ArrayList<>();
    if ("CREATE_SUPPLEMENT_BRANCH".equals(mutation.getDynamicAction())) {
        for (int index = 0; index < Math.max(1, mutation.getNodeTemplates().size()); index++) {
            WorkflowPlan.WorkflowPlanNode template = mutation.getNodeTemplates().isEmpty()
                    ? null
                    : mutation.getNodeTemplates().get(index);
            String nodeName = "collect_revision_evidence_v" + planVersion + "_" + (index + 1);
            dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                    .nodeName(nodeName)
                    .displayName(template == null ? "补充证据采集" : template.getDisplayName())
                    .agentType(AgentType.COLLECTOR.name())
                    .dependsOn(List.of(triggerNode.getNodeName()))
                    .required(true)
                    .executionOrder(order++)
                    .nodeConfig(template == null ? "{}" : template.getNodeConfig())
                    .notes("Orchestrator 决策触发的动态补证分支")
                    .branchKey(derivedBranchKey)
                    .dynamicNode(true)
                    .originNodeName(triggerNode.getNodeName())
                    .build());
            collectorDependencies.add(nodeName);
        }
        appendExtractAnalyzeRewriteReviewChain(
                dynamicNodes,
                triggerNode,
                collectorDependencies,
                planVersion,
                order,
                derivedBranchKey);
        return dynamicNodes;
    }
    if ("CREATE_REWRITE_BRANCH".equals(mutation.getDynamicAction())) {
        appendRewriteReviewChain(
                dynamicNodes,
                triggerNode,
                List.of(triggerNode.getNodeName()),
                planVersion,
                order,
                derivedBranchKey);
        return dynamicNodes;
    }
    return dynamicNodes;
}
```

- [ ] **Step 6: 运行 adapter 和图组装测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionExecutorAdapterTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest" test
```

Expected:

- PASS
- 现有 `RevisionDirective` 路径不受影响

- [ ] **Step 7: 提交 Task 5**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java backend/src/main/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssembler.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/CompensationGraphAssemblerTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphServiceTest.java
git commit -m "feat: translate orchestration decisions to dynamic mutations"
```

---

### Task 6: DynamicPlanAppender 接入 Orchestrator 决策闭环

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java`

- [ ] **Step 1: 给 `DynamicTaskGraphService` 增加 mutation 创建动态计划入口**

```java
public TaskPlan createDynamicPlan(TaskPlan parentPlan,
                                  TaskNode triggerNode,
                                  cn.bugstack.competitoragent.orchestration.DynamicPlanMutation mutation,
                                  WorkflowPlan baseWorkflowPlan) {
    String branchSuffix = "review-" + (parentPlan.getPlanVersion() + 1);
    String parentBranchKey = normalizeBranchKey(parentPlan.getBranchKey());
    String derivedBranchKey = parentBranchKey + "/" + branchSuffix;
    List<WorkflowPlan.WorkflowPlanNode> dynamicNodes = compensationGraphAssembler.assembleDynamicNodes(
            parentPlan,
            triggerNode,
            mutation,
            nextExecutionOrder(baseWorkflowPlan),
            derivedBranchKey);
    List<WorkflowPlan.WorkflowPlanNode> mergedNodes = new ArrayList<>(baseWorkflowPlan.getNodes());
    mergedNodes.addAll(dynamicNodes);

    WorkflowPlan derivedPlan = baseWorkflowPlan.toBuilder()
            .planVersion(parentPlan.getPlanVersion() + 1)
            .parentPlanVersionId(parentPlan.getId())
            .branchKey(derivedBranchKey)
            .dynamicPlan(true)
            .nodes(mergedNodes)
            .build();

    taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(parentPlan.getTaskId())
            .ifPresent(activePlan -> {
                activePlan.setActive(false);
                taskPlanRepository.save(activePlan);
            });

    return taskPlanRepository.save(taskPlanVersioner.createDerivedPlan(
            parentPlan,
            derivedPlan,
            triggerNode == null ? null : triggerNode.getNodeName(),
            mutation == null ? "DYNAMIC_BACKFLOW" : mutation.getBranchReason(),
            branchSuffix));
}
```

- [ ] **Step 2: 修改 `DynamicPlanAppender` 构造依赖**

Add dependencies:

```java
private final cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService orchestrationDecisionService;
private final cn.bugstack.competitoragent.orchestration.DecisionPolicyService decisionPolicyService;
private final cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter decisionExecutorAdapter;
private final cn.bugstack.competitoragent.orchestration.OrchestrationTraceService orchestrationTraceService;
```

受影响测试必须显式更新构造注入，当前直接 `new DynamicPlanAppender(...)` 的文件至少包括：

- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java`

这些测试统一补 `mock(OrchestrationDecisionService.class)`、`mock(DecisionPolicyService.class)`、`mock(DecisionExecutorAdapter.class)`、`mock(OrchestrationTraceService.class)`。

- [ ] **Step 3: 在 `maybeAppendDynamicPlan` 中从 review output 构造 OrchestrationContext**

Add helper:

```java
private cn.bugstack.competitoragent.orchestration.OrchestrationContext buildOrchestrationContext(Long taskId,
                                                                                                 TaskNode completedNode,
                                                                                                 JsonNode reviewOutput,
                                                                                                 List<RevisionDirective> directives) {
    List<String> sourceUrls = readSourceUrls(reviewOutput);
    return cn.bugstack.competitoragent.orchestration.OrchestrationContext.builder()
            .taskId(taskId)
            .planVersionId(completedNode.getPlanVersionId())
            .branchKey(completedNode.getBranchKey())
            .triggerNodeName(completedNode.getNodeName())
            .reviewStage(reviewOutput.path("reviewStage").asText(""))
            .passed(reviewOutput.path("passed").asBoolean(false))
            .requiresHumanIntervention(reviewOutput.path("requiresHumanIntervention").asBoolean(false))
            .diagnoses(readDiagnoses(reviewOutput))
            .legacyRevisionDirectives(directives)
            .sourceUrls(sourceUrls)
            .evidenceState(sourceUrls.isEmpty()
                    ? cn.bugstack.competitoragent.orchestration.EvidenceState.MISSING_SOURCE
                    : cn.bugstack.competitoragent.orchestration.EvidenceState.FULL_SOURCE)
            .inputSummary(reviewOutput.path("summary").asText("终审失败后进入 Orchestrator 反馈决策"))
            .build();
}
```

Add helper:

```java
private List<QualityDiagnosis> readDiagnoses(JsonNode jsonNode) {
    if (jsonNode == null || !jsonNode.has("diagnoses") || !jsonNode.get("diagnoses").isArray()) {
        return List.of();
    }
    try {
        List<QualityDiagnosis> diagnoses = objectMapper.convertValue(
                jsonNode.get("diagnoses"),
                new TypeReference<List<QualityDiagnosis>>() {
                });
        return diagnoses.stream().map(QualityDiagnosis::normalized).toList();
    } catch (IllegalArgumentException e) {
        return List.of();
    }
}
```

Add helper:

```java
private List<String> readSourceUrls(JsonNode jsonNode) {
    if (jsonNode == null || !jsonNode.has("sourceUrls") || !jsonNode.get("sourceUrls").isArray()) {
        return List.of();
    }
    try {
        return objectMapper.convertValue(jsonNode.get("sourceUrls"), new TypeReference<List<String>>() {
        });
    } catch (IllegalArgumentException e) {
        return List.of();
    }
}
```

- [ ] **Step 4: 用 Orchestrator 决策替换直接 directives 补图路径**

Replace the old final append call flow inside `appendDynamicPlan(...)` with:

```java
cn.bugstack.competitoragent.orchestration.OrchestrationContext orchestrationContext =
        buildOrchestrationContext(taskId, completedNode, readJson(completedNode.getOutputData()), directives);
List<cn.bugstack.competitoragent.orchestration.OrchestrationDecision> decisions =
        orchestrationDecisionService.decide(orchestrationContext);
if (decisions.isEmpty()) {
    return false;
}

cn.bugstack.competitoragent.orchestration.DecisionPolicyRuleSet ruleSet =
        cn.bugstack.competitoragent.orchestration.DecisionPolicyRuleSet.builder().build();
for (cn.bugstack.competitoragent.orchestration.OrchestrationDecision decision : decisions) {
    cn.bugstack.competitoragent.orchestration.DecisionPolicyResult policyResult =
            decisionPolicyService.evaluate(
                    decision,
                    ruleSet,
                    orchestrationContext.getCurrentDecisionCount(),
                    task.getStatus() == null ? null : task.getStatus().name(),
                    completedNode.getStatus() == null ? null : completedNode.getStatus().name());
    cn.bugstack.competitoragent.orchestration.DynamicPlanMutation mutation =
            decisionExecutorAdapter.toMutation(decision, policyResult, parentPlan.getId(), parentPlan.getPlanVersion() + 1);
    orchestrationTraceService.recordDecision(taskId, completedNode, decision, policyResult, mutation);
    if (!policyResult.isAllowed() || !"APPEND_NODES".equals(mutation.getMutationType())) {
        continue;
    }
    TaskPlan derivedPlan = dynamicTaskGraphService.createDynamicPlan(parentPlan, completedNode, mutation, baseWorkflowPlan);
    List<TaskNode> dynamicNodes = materializeDynamicNodes(taskId, completedNode, derivedPlan, nodeMap);
    if (dynamicNodes.isEmpty()) {
        continue;
    }
    nodeRepository.saveAll(dynamicNodes);
    nodes.addAll(dynamicNodes);
    nodes.sort(java.util.Comparator.comparingInt(TaskNode::getExecutionOrder));
    for (TaskNode dynamicNode : dynamicNodes) {
        nodeMap.put(dynamicNode.getNodeName(), dynamicNode);
    }
    task.setCurrentPlanVersionId(derivedPlan.getId());
    task.setCurrentPlanVersion(derivedPlan.getPlanVersion());
    task.setErrorMessage(null);
    taskRepository.save(task);
    orchestrationTraceService.recordCheckpoint(taskId, completedNode, derivedPlan, decision, mutation, ruleSet);
    return true;
}
return false;
```

Implementation note:

- Keep a private legacy method only if needed for tests, but production path must call `orchestrationDecisionService -> decisionPolicyService -> decisionExecutorAdapter` before dynamic plan creation.
- `DagExecutorTest`、`DagExecutorWorkflowEventTest`、`DagExecutorRuntimeDependencyTest` 必须同步切换到 9 参构造，并显式补齐 `OrchestrationDecisionService`、`DecisionPolicyService`、`DecisionExecutorAdapter`、`OrchestrationTraceService` 四个 mock；禁止新增空实现构造或 no-op 依赖掩盖真实装配缺口。

- [ ] **Step 5: 同步更新运行时测试并新增 `DynamicPlanAppenderTest`**

1. 在 `DagExecutorTest` 更新构造注入，确保新增四个依赖全部显式 mock。
2. 在 `DagExecutorWorkflowEventTest` 增加一个终审失败场景，断言 `WorkflowEventPublisher.publishOrchestrationEvent(...)` 被调用。
3. 在 `DagExecutorRuntimeDependencyTest` 更新构造注入，锁定运行时依赖图仍可装配。
4. 新增一个更窄的 `DynamicPlanAppenderTest`，专门断言 `quality_check_final failed -> orchestrationDecisionService -> decisionPolicyService -> decisionExecutorAdapter -> nodeRepository.saveAll(...)` 这条调用链。

Add an orchestration event assertion to `DagExecutorWorkflowEventTest`:

```java
verify(workflowEventPublisher, atLeastOnce()).publishOrchestrationEvent(
        eq(taskId),
        eq("quality_check_final"),
        any(),
        any(),
        eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED),
        any(),
        eq(List.of("https://www.notion.so/pricing")));
```

Create `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java`:

```java
package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyResult;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicPlanAppenderTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final DynamicTaskGraphService dynamicTaskGraphService = mock(DynamicTaskGraphService.class);
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final OrchestrationDecisionService orchestrationDecisionService = mock(OrchestrationDecisionService.class);
    private final DecisionPolicyService decisionPolicyService = mock(DecisionPolicyService.class);
    private final DecisionExecutorAdapter decisionExecutorAdapter = mock(DecisionExecutorAdapter.class);
    private final OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final DynamicPlanAppender appender = new DynamicPlanAppender(
            taskRepository,
            nodeRepository,
            dynamicTaskGraphService,
            taskPlanRepository,
            objectMapper,
            orchestrationDecisionService,
            decisionPolicyService,
            decisionExecutorAdapter,
            orchestrationTraceService
    );

    @Test
    void shouldAppendDynamicPlanThroughOrchestratorDecisionPipeline() {
    AnalysisTask task = AnalysisTask.builder()
            .id(50L)
            .status(AnalysisTaskStatus.STOPPED)
            .currentPlanVersionId(8L)
            .currentPlanVersion(1)
            .build();
    TaskNode completedNode = TaskNode.builder()
            .taskId(50L)
            .nodeName("quality_check_final")
            .displayName("质量终审")
            .agentType(AgentType.REVIEWER)
            .status(TaskNodeStatus.SUCCESS)
            .planVersionId(8L)
            .branchKey("root")
            .outputData("""
                    {
                      "reviewStage":"final",
                      "passed":false,
                      "requiresHumanIntervention":false,
                      "summary":"缺少官网定价证据",
                      "sourceUrls":["https://www.notion.so/pricing"],
                      "revisionDirectives":[
                        {
                          "category":"SEARCH_QUALITY",
                          "actionType":"SUPPLEMENT_EVIDENCE",
                          "summary":"补充官网定价证据",
                          "searchQueries":["Notion AI pricing official"],
                          "sourceUrls":["https://www.notion.so/pricing"]
                        }
                      ]
                    }
                    """)
            .build();
    TaskPlan parentPlan = TaskPlan.builder()
            .id(8L)
            .taskId(50L)
            .planVersion(1)
            .branchKey("root")
            .active(true)
            .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                    .planVersionId(8L)
                    .planVersion(1)
                    .branchKey("root")
                    .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                            .nodeName("quality_check_final")
                            .displayName("质量终审")
                            .agentType(AgentType.REVIEWER.name())
                            .dependsOn(List.of("rewrite_report"))
                            .executionOrder(6)
                            .branchKey("root")
                            .build()))
                    .build()))
            .build();
    OrchestrationDecision decision = OrchestrationDecision.builder()
            .decisionId("od-001")
            .taskId(50L)
            .triggerNodeName("quality_check_final")
            .decisionType("APPEND_DYNAMIC_BRANCH")
            .actionType("SUPPLEMENT_EVIDENCE")
            .targetNode("collect_sources")
            .affectedScope("CURRENT_SECTION_ONLY")
            .priority("HIGH")
            .confidence(0.92d)
            .inputRefs(Map.of("qualityDiagnosisIds", List.of("qd-quality_check_final-1")))
            .suggestedQueries(List.of("Notion AI pricing official"))
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build()
            .normalized();
    DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
            .decisionId("od-001")
            .allowed(true)
            .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
            .riskLevel("HIGH")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build();
    DynamicPlanMutation mutation = DynamicPlanMutation.builder()
            .mutationId("dpm-od-001")
            .decisionId("od-001")
            .mutationType("APPEND_NODES")
            .branchReason("ORCHESTRATOR_DECISION")
            .dynamicAction("CREATE_SUPPLEMENT_BRANCH")
            .expectedResumeNodeName("collect_revision_evidence_v2_1")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build();
    TaskPlan derivedPlan = TaskPlan.builder()
            .id(9L)
            .taskId(50L)
            .planVersion(2)
            .parentPlanId(8L)
            .branchKey("root/review-2")
            .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                    .planVersionId(9L)
                    .planVersion(2)
                    .branchKey("root/review-2")
                    .nodes(List.of(
                            WorkflowPlan.WorkflowPlanNode.builder()
                                    .nodeName("collect_revision_evidence_v2_1")
                                    .displayName("补充证据采集")
                                    .agentType(AgentType.COLLECTOR.name())
                                    .dependsOn(List.of("quality_check_final"))
                                    .executionOrder(7)
                                    .branchKey("root/review-2")
                                    .dynamicNode(true)
                                    .originNodeName("quality_check_final")
                                    .build(),
                            WorkflowPlan.WorkflowPlanNode.builder()
                                    .nodeName("rewrite_revision_patch_v2")
                                    .displayName("修订报告改写")
                                    .agentType(AgentType.WRITER.name())
                                    .dependsOn(List.of("collect_revision_evidence_v2_1"))
                                    .executionOrder(10)
                                    .branchKey("root/review-2")
                                    .dynamicNode(true)
                                    .originNodeName("quality_check_final")
                                    .build(),
                            WorkflowPlan.WorkflowPlanNode.builder()
                                    .nodeName("quality_check_revision_patch_v2")
                                    .displayName("修订终审复核")
                                    .agentType(AgentType.REVIEWER.name())
                                    .dependsOn(List.of("rewrite_revision_patch_v2"))
                                    .executionOrder(11)
                                    .branchKey("root/review-2")
                                    .dynamicNode(true)
                                    .originNodeName("quality_check_final")
                                    .build()))
                    .build()))
            .build();

    when(taskRepository.findById(50L)).thenReturn(Optional.of(task));
    when(taskRepository.save(any(AnalysisTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(taskPlanRepository.findById(8L)).thenReturn(Optional.of(parentPlan));
    when(orchestrationDecisionService.decide(any())).thenReturn(List.of(decision));
    when(decisionPolicyService.evaluate(eq(decision), any(), eq(0), eq(AnalysisTaskStatus.STOPPED.name()), eq(TaskNodeStatus.SUCCESS.name())))
            .thenReturn(policyResult);
    when(decisionExecutorAdapter.toMutation(decision, policyResult, 8L, 2)).thenReturn(mutation);
    when(dynamicTaskGraphService.createDynamicPlan(eq(parentPlan), eq(completedNode), eq(mutation), any()))
            .thenReturn(derivedPlan);
    when(nodeRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<TaskNode> nodes = new ArrayList<>(List.of(completedNode));
    Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
    nodeMap.put(completedNode.getNodeName(), completedNode);

    boolean appended = appender.maybeAppendDynamicPlan(50L, nodes, nodeMap, completedNode);

    assertThat(appended).isTrue();
    verify(orchestrationDecisionService).decide(any());
    verify(decisionPolicyService).evaluate(eq(decision), any(), eq(0), eq(AnalysisTaskStatus.STOPPED.name()), eq(TaskNodeStatus.SUCCESS.name()));
    verify(decisionExecutorAdapter).toMutation(decision, policyResult, 8L, 2);
    verify(orchestrationTraceService).recordDecision(50L, completedNode, decision, policyResult, mutation);
    verify(orchestrationTraceService).recordCheckpoint(50L, completedNode, derivedPlan, decision, mutation, any());

    ArgumentCaptor<List> savedNodesCaptor = ArgumentCaptor.forClass(List.class);
    verify(nodeRepository).saveAll(savedNodesCaptor.capture());
    assertThat(savedNodesCaptor.getValue())
            .extracting(node -> ((TaskNode) node).getNodeName())
            .contains("collect_revision_evidence_v2_1", "rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    assertThat(task.getCurrentPlanVersionId()).isEqualTo(9L);
    assertThat(task.getCurrentPlanVersion()).isEqualTo(2);
    assertThat(nodeMap).containsKeys("collect_revision_evidence_v2_1", "rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    }
}
```

- [ ] **Step 6: 运行动态补图相关测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionExecutorAdapterTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest" test
```

Expected:

- PASS
- 终审失败动态补图仍可创建
- 新路径必须经过 Orchestrator 决策与策略校验

- [ ] **Step 7: 提交 Task 6**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphService.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DynamicTaskGraphServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppenderTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java
git commit -m "feat: route dynamic backflow through orchestration decisions"
```

---

### Task 7: OrchestrationTraceService 与 replay 可观测

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/repository/TaskWorkflowEventRepository.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisherTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

- [ ] **Step 1: 扩展工作流事件类型**

Add to `WorkflowEventType`:

```java
ORCHESTRATION_DECISION_RECORDED,
ORCHESTRATION_CHECKPOINT_UPDATED
```

- [ ] **Step 1.5: 给 `TaskWorkflowEventRepository` 增加最近 checkpoint 查询方法**

Add to `TaskWorkflowEventRepository`:

```java
Optional<TaskWorkflowEvent> findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
        Long taskId,
        WorkflowEventType eventType);
```

Implementation note:

- 该查询只服务 `ORCHESTRATION_CHECKPOINT_UPDATED`，用于读取同一任务最近一次 checkpoint。
- 不能按 `branchKey` 限定查询；动态补图会派生新分支，按分支查会导致第二次补图读不到上一轮计数并重新变成 `1`。
- 如果没有历史 checkpoint，`OrchestrationTraceService` 需要降级为 `decisionCount=1`，不能抛异常阻断 trace 记录。

- [ ] **Step 2: 给 `WorkflowEventPublisher` 增加通用编排事件发布方法**

```java
public void publishOrchestrationEvent(Long taskId,
                                      String nodeName,
                                      Long planVersionId,
                                      String branchKey,
                                      WorkflowEventType eventType,
                                      Map<String, Object> payload,
                                      List<String> sourceUrls) {
    stage(WorkflowEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .taskId(taskId)
            .nodeName(nodeName)
            .planVersionId(planVersionId)
            .branchKey(branchKey)
            .eventType(eventType)
            .payload(payload == null ? Map.of() : payload)
            .sourceUrls(sourceUrls == null ? List.of() : sourceUrls)
            .occurredAt(LocalDateTime.now())
            .build());
}
```

- [ ] **Step 3: 写 trace service 红灯测试**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationTraceServiceTest {

    @Test
    void shouldPublishDecisionTraceAndCheckpointEvents() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OrchestrationTraceService service = new OrchestrationTraceService(publisher, repository, objectMapper);
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .expectedResumeNodeName("collect_revision_evidence_v2_1")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        TaskWorkflowEvent previousCheckpointEvent = TaskWorkflowEvent.builder()
                .taskId(50L)
                .branchKey("root/review-2")
                .eventType(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .payload("{\"checkpoint\":{\"decisionCount\":1}}")
                .sourceUrls("[]")
                .build();
        when(repository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                50L,
                WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(previousCheckpointEvent));

        service.recordDecision(50L, triggerNode, decision, policyResult, mutation);
        service.recordCheckpoint(50L, triggerNode, TaskPlan.builder().id(9L).planVersion(2).branchKey("root/review-2").build(),
                decision, mutation, DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build());

        verify(publisher).publishOrchestrationEvent(eq(50L), eq("quality_check_final"), eq(8L), eq("root"),
                eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED), any(), eq(List.of()));
        ArgumentCaptor<Map<String, Object>> checkpointPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(eq(50L), eq("quality_check_final"), eq(9L), eq("root/review-2"),
                eq(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED), checkpointPayloadCaptor.capture(), eq(List.of()));
        OrchestratorCheckpoint checkpoint = (OrchestratorCheckpoint) checkpointPayloadCaptor.getValue().get("checkpoint");
        assertThat(checkpoint.getDecisionCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 4: 实现 `OrchestrationTraceService`**

```java
package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排决策轨迹服务。
 * P1 第一版复用 workflow outbox 作为可回放审计载体，不新增独立 trace 表。
 */
@Service
@RequiredArgsConstructor
public class OrchestrationTraceService {

    private final WorkflowEventPublisher workflowEventPublisher;
    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final ObjectMapper objectMapper;

    public void recordDecision(Long taskId,
                               TaskNode triggerNode,
                               OrchestrationDecision decision,
                               DecisionPolicyResult policyResult,
                               DynamicPlanMutation mutation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator 已生成运行期编排决策");
        payload.put("decision", decision);
        payload.put("policyResult", policyResult);
        payload.put("mutation", mutation);
        payload.put("evidenceState", decision == null ? null : decision.getEvidenceState());
        workflowEventPublisher.publishOrchestrationEvent(
                taskId,
                triggerNode == null ? null : triggerNode.getNodeName(),
                triggerNode == null ? null : triggerNode.getPlanVersionId(),
                triggerNode == null ? null : triggerNode.getBranchKey(),
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED,
                payload,
                decision == null ? List.of() : decision.getSourceUrls());
    }

    public void recordCheckpoint(Long taskId,
                                 TaskNode triggerNode,
                                 TaskPlan derivedPlan,
                                 OrchestrationDecision decision,
                                 DynamicPlanMutation mutation,
                                 DecisionPolicyRuleSet ruleSet) {
        OrchestratorCheckpoint checkpoint = OrchestratorCheckpoint.builder()
                .checkpointId("oc-" + (decision == null ? "unknown" : decision.getDecisionId()))
                .taskId(taskId)
                .planVersionId(derivedPlan == null ? null : derivedPlan.getId())
                .branchKey(derivedPlan == null ? null : derivedPlan.getBranchKey())
                .lastDecisionId(decision == null ? null : decision.getDecisionId())
                .lastMutationId(mutation == null ? null : mutation.getMutationId())
                .pendingActions(List.of("WAITING_FOR_SUPPLEMENT_RESULT"))
                .decisionCount(resolveNextDecisionCount(taskId))
                .maxAutoDecisions(ruleSet == null ? 2 : ruleSet.getMaxAutoDecisions())
                .resumeAfterNodeName(mutation == null ? null : mutation.getExpectedResumeNodeName())
                .resumeReason("动态补图节点完成后需要继续复核质量诊断是否收敛。")
                .sourceUrls(decision == null ? List.of() : decision.getSourceUrls())
                .evidenceState(decision == null ? EvidenceState.MISSING_SOURCE : decision.getEvidenceState())
                .build()
                .normalized();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator checkpoint 已更新");
        payload.put("checkpoint", checkpoint);
        workflowEventPublisher.publishOrchestrationEvent(
                taskId,
                triggerNode == null ? null : triggerNode.getNodeName(),
                derivedPlan == null ? null : derivedPlan.getId(),
                derivedPlan == null ? null : derivedPlan.getBranchKey(),
                WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED,
                payload,
                checkpoint.getSourceUrls());
    }

    private int resolveNextDecisionCount(Long taskId) {
        if (taskId == null) {
            return 1;
        }
        return taskWorkflowEventRepository
                .findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                        taskId,
                        WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .map(this::extractDecisionCount)
                .orElse(0) + 1;
    }

    /**
     * checkpoint 当前复用事件 payload 持久化，读取失败时按 0 处理，
     * 避免历史脏事件阻断新的 trace 写入。
     */
    private int extractDecisionCount(TaskWorkflowEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return 0;
        }
        try {
            JsonNode countNode = objectMapper.readTree(event.getPayload())
                    .path("checkpoint")
                    .path("decisionCount");
            return Math.max(0, countNode.asInt(0));
        } catch (Exception e) {
            return 0;
        }
    }
}
```

- [ ] **Step 5: 更新 replay 测试，锁定编排事件能进时间线**

Add to `TaskReplayProjectionServiceTest`:

```java
@Test
void shouldExposeOrchestrationDecisionEventsInReplayTimeline() {
    TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
    TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
    TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
    MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
    AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
    RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
    TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

    LocalDateTime baseTime = LocalDateTime.of(2026, 6, 23, 15, 0, 0);
    TaskPlan activePlan = TaskPlan.builder()
            .id(12L)
            .taskId(42L)
            .planVersion(3)
            .branchKey("root/review-3")
            .active(true)
            .createdAt(baseTime.minusMinutes(10))
            .build();
    TaskWorkflowEvent orchestrationEvent = TaskWorkflowEvent.builder()
            .id(101L)
            .eventId("evt-orchestration-101")
            .taskId(42L)
            .nodeName("quality_check_final")
            .planVersionId(12L)
            .branchKey("root/review-3")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .deliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED)
            .topic("task.orchestration")
            .tag("orchestration_decision_recorded")
            .payload("{\"summary\":\"Orchestrator 已生成运行期编排决策\"}")
            .sourceUrls("[\"https://www.notion.so/pricing\"]")
            .createdAt(baseTime.minusMinutes(2))
            .updatedAt(baseTime.minusMinutes(2))
            .build();

    when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of(activePlan));
    when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.of(activePlan));
    when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(orchestrationEvent));
    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of());
    when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
    when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
    when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
    when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
    when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
            .recommendedAction("OBSERVE_ONLY")
            .summary("none")
            .blockingNodeNames(List.of())
            .resumeSupported(false)
            .sourceUrls(List.of())
            .build());

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    TaskReplayProjectionService service = new TaskReplayProjectionService(
            taskPlanRepository,
            taskWorkflowEventRepository,
            taskNodeRepository,
            taskNodeExecutionAttemptRepository,
            memorySnapshotRepository,
            agentExecutionLogRepository,
            recoveryCheckpointService,
            taskRecoveryService,
            objectMapper
    );

    TaskReplayResponse replayResponse = service.getTaskReplay(42L);

    assertThat(replayResponse.getTimeline())
            .anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
                assertThat(event.getSummary()).contains("Orchestrator 已生成运行期编排决策");
            });
    assertThat(replayResponse.getSourceUrls()).contains("https://www.notion.so/pricing");
}
```

Expected assertion:

```java
assertThat(replayResponse.getTimeline())
        .anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
            assertThat(event.getSummary()).contains("Orchestrator");
        });
```

- [ ] **Step 6: 运行 trace/replay 测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationTraceServiceTest,WorkflowEventPublisherTest,TaskReplayProjectionServiceTest" test
```

Expected:

- PASS
- 编排决策事件进入 replay timeline

- [ ] **Step 7: 提交 Task 7**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java backend/src/main/java/cn/bugstack/competitoragent/repository/TaskWorkflowEventRepository.java backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisher.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventPublisherTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java
git commit -m "feat: record orchestration decisions in replay trace"
```

---

### Task 8: Reviewer 边界收口

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`

- [ ] **Step 1: 在 `PromptTemplateServiceTest` 写 Reviewer 模板边界测试**

Add to `PromptTemplateServiceTest`:

```java
@Test
void reviewerDefaultTemplateShouldNotAskForOrchestrationAction() {
    String template = promptTemplateService.getTemplate("reviewer");

    assertFalse(template.contains("orchestrationAction"));
    assertFalse(template.contains("CREATE_SUPPLEMENT_BRANCH"));
    assertFalse(template.contains("CREATE_RERUN_BRANCH"));
    assertFalse(template.contains("CREATE_REWRITE_BRANCH"));
    assertTrue(template.contains("# 职责边界"));
    assertTrue(template.contains("你只输出质量事实、证据缺口、修订建议"));
}
```

- [ ] **Step 2: 更新 reviewer 默认模板**

Modify `PromptTemplateService` reviewer template by appending explicit role boundary:

```text
# 职责边界
你只输出质量事实、证据缺口、修订建议。
不要输出 orchestrationAction、CREATE_SUPPLEMENT_BRANCH、CREATE_RERUN_BRANCH、CREATE_REWRITE_BRANCH。
最终是否补证、重跑、改写或人工介入，由 Orchestrator 根据质量诊断另行决策。
```

- [ ] **Step 3: 保持 `QualityReviewAgent` 兼容输出**

Implementation rule:

- Keep `revisionDirectives` for report display and compatibility.
- Do not add any new `orchestrationAction` prompt variable.
- Do not add any new `RevisionDirective.normalized()` orchestration mapping.
- 保留 `buildRevisionDirectives(...)` 现有 `category/actionType` 输出，因为 `OrchestrationDecisionAdapter` 仍读取这一兼容字段；P1 不在 Reviewer 内部删除该语义。

Add a code comment near `revisionDirectives` output:

```java
// 3.4 P1 起 revisionDirectives 只作为兼容期修订建议输出，
// 真实编排动作由 OrchestrationDecisionService 读取后统一决策。
```

- [ ] **Step 4: 运行 Reviewer 测试**

Run:

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest,QualityReviewAgentTest" test
```

Expected:

- PASS
- Reviewer prompt 不要求模型输出编排动作

- [ ] **Step 5: 提交 Task 8**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java
git commit -m "refactor: keep reviewer output as quality facts"
```

---

### Task 9: 架构规则与聚合回归

**Files:**

- Modify: `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitecturePackageMapping.java`

- [ ] **Step 1: 把 orchestration package 纳入现有 task-orchestration 边界映射**

Modify `ArchitecturePackageMapping.TASK_PACKAGES`:

```java
static final List<String> TASK_PACKAGES = List.of(
        "cn.bugstack.competitoragent.task..",
        "cn.bugstack.competitoragent.workflow..",
        "cn.bugstack.competitoragent.orchestration..",
        "cn.bugstack.competitoragent.event.."
);
```

Implementation note:

- P1 不新增 `ORCHESTRATION_PACKAGES` 常量，直接把 `..orchestration..` 归入现有 task-orchestration 视角，避免再开一套并行边界定义。
- P1 不修改 `ArchitectureWhitelist.java`；新的 orchestration 类必须以零豁免通过架构规则。

- [ ] **Step 2: 运行 ArchUnit，确认 orchestration 新包在现有边界下通过**

Run:

```powershell
mvn -pl backend "-Dtest=BackendModuleDependencyTest" test
```

Expected:

- PASS
- 不新增任何 architecture whitelist 例外

- [ ] **Step 3: 运行 P1 聚合测试**

Run:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest,PromptTemplateServiceTest,QualityReviewAgentTest,TaskReplayProjectionServiceTest,WorkflowEventPublisherTest,BackendModuleDependencyTest" test
```

Expected:

- PASS

- [ ] **Step 4: 运行 backend 全量测试**

Run:

```powershell
mvn -pl backend test
```

Expected:

- PASS
- 若全量回归出现失败，只修复名称包含 `Orchestration`、`DynamicPlanAppender`、`DagExecutor`、`PromptTemplateService`、`QualityReviewAgent`、`TaskReplayProjectionService`、`WorkflowEventPublisher`、`BackendModuleDependencyTest` 的 P1 直接回归；
- 其他既有历史失败记录到执行进度，保持本轮范围不外扩。

- [ ] **Step 5: 提交 Task 9**

```powershell
git add backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitecturePackageMapping.java
git commit -m "test: verify orchestration module boundaries"
```

---

### Task 10: 文档回链与演示证据口径

**Files:**

- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md`

- [ ] **Step 1: 回链总蓝图 3.4 P1 状态**

Update wording:

```md
- 3.4 P1 终审失败回流 MVP 已进入可执行计划：
  [2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md](../superpowers/agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)
  本轮目标是把动态补图从 `Reviewer revisionDirectives` 直接驱动，升级为
  `OrchestrationDecision -> DecisionPolicyResult -> DynamicPlanMutation -> DynamicTaskGraphService`。
```

- [ ] **Step 2: 更新稳定演示版计划 Day 2-5 进度**

Add progress entry:

```md
当前阶段：3.4 P1 运行期反馈 MVP

- [x] 第一份可执行计划：已完成
- [ ] P1 契约与策略测试：待执行
- [ ] Orchestrator 决策服务：待执行
- [ ] 动态补图与 checkpoint：待执行
- [ ] Reviewer 收口与缺失测试：待执行
```

- [ ] **Step 3: 在 3.4 架构规格相关文档加实施计划链接**

Add under related docs:

```md
- [3.4 P1 终审失败回流 MVP 可执行计划](../task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md)
```

- [ ] **Step 4: 更新本文执行进度**

At the bottom of this file add:

```md
## 2026-06-23 执行进度

当前阶段：P1 运行期反馈 MVP 可执行计划已落稿

- [x] 架构规格阅读：已完成
- [x] 稳定演示版计划阅读：已完成
- [x] 当前代码边界核对：已完成
- [x] 第一份可执行计划：已完成
- [x] P1 评估反馈修订：已完成
- [ ] P1 代码实施：待执行
```

- [ ] **Step 5: 提交 Task 10**

```powershell
git add docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-23-agent-collaboration-orchestration-p1-runtime-feedback-mvp-implementation-plan.md
git commit -m "docs: add orchestration p1 implementation plan"
```

---

## Verification

Run P1 focused contract tests:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest" test
```

Run dynamic graph integration tests:

```powershell
mvn -pl backend "-Dtest=CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest" test
```

Run reviewer/replay tests:

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest,QualityReviewAgentTest,TaskReplayProjectionServiceTest,WorkflowEventPublisherTest" test
```

Run architecture boundary tests:

```powershell
mvn -pl backend "-Dtest=BackendModuleDependencyTest" test
```

Run P1 aggregate:

```powershell
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,DecisionExecutorAdapterTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,DagExecutorTest,DagExecutorWorkflowEventTest,DagExecutorRuntimeDependencyTest,PromptTemplateServiceTest,QualityReviewAgentTest,TaskReplayProjectionServiceTest,WorkflowEventPublisherTest,BackendModuleDependencyTest" test
```

Run backend full regression:

```powershell
mvn -pl backend test
```

Manual smoke after implementation:

```text
1. 准备一个 quality_check_final 输出 passed=false 且 revisionDirectives 包含 EVIDENCE_GAP 的任务。
2. 执行或 rerun 到 quality_check_final。
3. 确认系统生成 ORCHESTRATION_DECISION_RECORDED 事件。
4. 确认策略结果 allowed=true。
5. 确认生成 DynamicPlanMutation，派生 root/review-N 分支。
6. 确认新分支包含 collect_revision_evidence_vN_1、extract_revision_patch_vN、analyze_revision_patch_vN、rewrite_revision_patch_vN、quality_check_revision_patch_vN。
7. GET /api/task/{taskId}/replay 能看到 Orchestrator 决策和 checkpoint。
8. 缺 sourceUrls 的决策必须显示 evidenceState=MISSING_SOURCE。
```

---

## Self-Review

### Spec coverage

1. `Reviewer 与 Orchestrator 职责拆分` 由 Task 2、Task 4、Task 8 覆盖。
2. `sourceUrls / evidenceState 红线` 由 Task 1、Task 3、Task 5、Task 7 覆盖。
3. `终审失败后质量回流 MVP` 由 Task 4、Task 5、Task 6 覆盖。
4. `DecisionPolicyRuleSet / DecisionPolicyResult` 由 Task 3 覆盖。
5. `DecisionExecutorAdapter / DynamicPlanMutation` 由 Task 5 覆盖。
6. `动态补图不再直接依赖 Reviewer orchestrationAction` 由 Task 6 覆盖。
7. `DecisionTrace / OrchestratorCheckpoint 可回放` 由 Task 7 覆盖。
8. `老任务 RevisionDirective 兼容读取` 由 Task 2、Task 6 覆盖。
9. `decisionCount 递增防循环` 由 Task 7 的最近 checkpoint 查询、trace 测试和 `resolveNextDecisionCount(...)` 覆盖。
10. `diagnosis-only 阻断路径` 由 Task 4 的 `shouldWaitForHumanWhenBlockingDiagnosisExistsWithoutDirectives` 覆盖。
11. `DynamicPlanMutation 独立组图入口` 由 Task 5 的 `CompensationGraphAssemblerTest` 覆盖。
12. `不重写 DagExecutor / 不接入 P2 前置规划 / 不做 Citation Agent` 已在 Scope Guard 明确排除。

### Placeholder scan

1. 本计划没有 `TBD`、`TODO` 或 `implement later` 占位。
2. 每个任务都有明确文件、测试、实现代码片段、命令和预期结果。
3. 需要根据现有长测试 fixture 补充的测试，已给出可执行断言和降级到更窄测试的路径，不影响任务边界。

### Type consistency

1. `EvidenceState` 在 `OrchestrationDecision / DecisionPolicyResult / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` 中保持同一枚举。
2. `OrchestrationDecision.decisionId` 被 `DecisionPolicyResult / DynamicPlanMutation / DecisionTrace / OrchestratorCheckpoint` 共享。
3. `DynamicPlanMutation.expectedResumeNodeName` 与 `OrchestratorCheckpoint.resumeAfterNodeName` 对齐。
4. `RevisionDirective` 只作为兼容输入，由 `OrchestrationDecisionAdapter` 转为正式决策。
5. `DecisionExecutorAdapter` 只消费 `allowed=true` 的 `DecisionPolicyResult`，不重复做质量判断。
6. `OrchestratorCheckpoint.decisionCount` 由上一条 `ORCHESTRATION_CHECKPOINT_UPDATED` 事件递增，不再在 trace service 中硬编码为固定值。

## 2026-06-23 执行进度

当前阶段：P1 运行期反馈 MVP 可执行计划已落稿

- [x] 架构规格阅读：已完成
- [x] 稳定演示版计划阅读：已完成
- [x] 当前代码边界核对：已完成
- [x] 第一份可执行计划：已完成
- [x] P1 评估反馈修订：已完成
- [ ] P1 代码实施：待执行
