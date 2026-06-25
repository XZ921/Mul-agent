# Agent Collaboration Orchestration P3-3 Conversation Decision Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让统一对话入口的动作预览读取最近一次 `OrchestrationDecision`，把“系统为什么建议补证、重写、等待人工介入”的决策依据、证据状态和来源链接展示给用户，并继续保持预览与人工确认边界。

**Architecture:** P3-3 不新增自治执行入口，也不让 Conversation 重新做全局编排判断。后端新增只读的 `ConversationOrchestrationDecisionQueryService`，从 `TaskWorkflowEvent` 最近的 `ORCHESTRATION_DECISION_RECORDED` 事件提取稳定视图；`TaskActionTranslator` 根据该视图增强 `TaskActionPreview`；`ConversationService` 只负责把决策上下文传入预览链路并继续持久化 `IntentDecision` 审计。前端只扩展类型和 `TaskActionPreviewCard` 展示，不改页面架构。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, AssertJ, Maven, React 18, TypeScript, Vitest, Testing Library

---

## Source Context

1. 最新总结：`docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-2-task5-progress.md` 已确认 P3-2 执行 1 完成自动化实现、smoke、replay、文档回链与全量回归，下一步明确指向 P3-3 Conversation 动作预览接入与 P3-4 Citation Agent。
2. 最新 smoke：`docs/superpowers/agent-collaboration-orchestration/summary/2026-06-24-p2-post-fix-dev-smoke-report.md` 证明真实 dev 链路可以在 Analyzer 门禁处进入 `WAITING_INTERVENTION`，避免 Writer 凭空扩写；P3-3 需要把这类编排阻断原因在统一对话入口解释清楚。
3. 架构规格：`docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md` P3 范围第 3 项要求 Conversation 的动作预览读取 `OrchestrationDecision`，同时明确“不把对话入口变成自治执行入口”。
4. 总蓝图：`docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 已把 `对话协同` 映射到 `对话动作引擎`，并指出动作预览、确认执行、正式命令桥接尚未形成独立契约。
5. 稳定演示计划：`docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md` 要求 `sourceUrls / evidenceState` 在报告、诊断、编排决策中可见；P3-3 的价值是把这些可见性延伸到统一对话入口。

## Approach Decision

| 方案 | 做法 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| A. Conversation 只读最近编排事件 | `ConversationService` 通过查询服务读取最近 `ORCHESTRATION_DECISION_RECORDED`，生成增强预览 | 不改数据库，不破坏 P1/P2/P3-1/P3-2 trace；最符合 P3-3 小步接入 | 只能读取最近一次决策，不解决完整决策列表浏览 | 采用 |
| B. 给任务表新增 latest decision 字段 | 每次 Orchestrator 决策同步写任务主表，再由对话读取 | 查询简单，前端可直接看到摘要 | 需要迁移表结构，增加写路径耦合，和 P1 复用 outbox 的策略相冲突 | 不采用 |
| C. 前端直接读 replay 后自行解释 | 对话页调用 replay API，从 timeline 里找决策 | 后端改动少 | 前端承担业务解释，容易复制 Orchestrator 语义，破坏边界 | 不采用 |

P3-3 采用方案 A：只读最新编排事件，先完成“用户问下一步时能看到编排决策依据”的最小闭环。完整决策列表浏览、Citation Agent 逐句核验和对话动作引擎正式诊断留给后续专题。

## Current Stage

当前阶段：P3-3 具体执行计划已写入；本轮只定义 Conversation 动作预览读取 `OrchestrationDecision` 的最小可运行闭环。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行
- [ ] 质检复核：待执行

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 新增 Conversation 编排决策只读视图 | 0.5 天 | P1/P2/P3-1/P3-2 已把 `ORCHESTRATION_DECISION_RECORDED` 写入 outbox |
| Task 2 | 扩展后端 DTO 与动作预览翻译器 | 0.5 天 | Task 1 能返回稳定决策视图 |
| Task 3 | ConversationService 接入决策上下文和审计持久化 | 0.5 天 | Task 2 预览契约通过 |
| Task 4 | 控制器契约与前端展示 | 0.5 天 | Task 3 HTTP 响应包含决策摘要 |
| Task 5 | 聚合验证、回归和 smoke 建议 | 0.5 天 | Task 1-4 通过 |
| Task 6 | 文档回链与进度持久化 | 0.5 天 | 自动化验证通过 |

## Scope Guard

### P3-3 必须完成

1. 新增 `ConversationOrchestrationDecisionView`，只表达对话预览需要的决策摘要，不替代正式 `OrchestrationDecision`。
2. 新增 `ConversationOrchestrationDecisionQueryService`，从最近 `ORCHESTRATION_DECISION_RECORDED` 事件读取：
   - `decisionId`
   - `triggerNodeName`
   - `decisionType`
   - `actionType`
   - `targetNode`
   - `affectedScope`
   - `reason`
   - `requiresHumanIntervention`
   - `requiresConfirmation`
   - `evidenceState`
   - `sourceUrls`
3. 扩展 `ConversationResponse.TaskActionPreview`，新增 `orchestrationDecision` 摘要字段；扩展 `ConversationActionConfirmationRequest`，保存 `orchestrationDecisionId / orchestrationDecisionType / orchestrationEvidenceState`。
4. `TaskActionTranslator` 在有编排决策时优先生成决策增强预览：
   - `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE` -> `SUPPLEMENT_EVIDENCE` 预览；
   - `REWRITE_ONLY / REWRITE_SECTION` -> `RERUN_NODE` 预览，目标节点为 `rewrite_report` 或决策目标节点；
   - `WAIT_FOR_HUMAN / MANUAL_REVIEW` -> 只展示人工介入预览，不生成可确认执行按钮；
   - `NO_ACTION` -> 回落到既有节点匹配预览。
5. `ConversationSafetyPolicy` 必须尊重“不可确认执行”的人工介入预览，不能因为意图识别命中了 TASK_ACTION 就强行生成确认对象。
6. `ConversationService` 在 TASK_ACTION 与 RESEARCH 两类预览中传入最近编排决策，并把决策 `sourceUrls` 写入响应和 `IntentDecision` 审计。
7. 前端类型和 `TaskActionPreviewCard` 展示 Orchestrator 决策摘要、证据状态和来源链接。
8. 自动化测试覆盖后端 query / translator / safety / service / controller，以及前端展示。

### P3-3 明确不做

1. 不实现完整 Citation Agent；逐句引用核查进入 P3-4。
2. 不让 Conversation 直接创建 `OrchestrationDecision`。
3. 不让 Conversation 绕过 `DecisionPolicyService` 或 `TaskRuntimeFacade` 直接改任务状态。
4. 不新增数据库表，不修改 `TaskWorkflowEvent` 表结构。
5. 不重构 `ConversationService` 的整体编排方式。
6. 不改写 `WorkflowFactory / ExecutionPlanDefinitionBuilder` 固定 DAG 模板。
7. 不做对话动作引擎完整诊断文档；本轮只补 P3-3 实施计划。
8. 不把前端改成 replay 浏览器，只在动作预览卡片展示当前决策摘要。

## Risk Register

| 风险 | 影响 | 处理要求 |
| --- | --- | --- |
| 最近决策不是用户当前关注节点 | 预览可能引用旧决策 | QueryService 第一版只读最近任务级决策；预览标题必须写明触发节点，如 `来自 analyze_competitors 的编排决策`，避免误认为是用户新生成的决策 |
| `WAIT_FOR_HUMAN` 被误生成确认按钮 | 用户点击后得到不支持动作，甚至误以为系统会自动处理人工介入 | `ConversationSafetyPolicyTest` 必须锁定人工介入预览不生成 confirmationRequest |
| 对话层重复解释 Orchestrator 规则 | Conversation 变成第二个 Orchestrator | `TaskActionTranslator` 只能消费已存在的 decision 字段，不得重新计算策略；所有文案以 `decision.reason / evidenceState / sourceUrls` 为准 |
| event payload JSON 历史不兼容 | QueryService 读取旧事件失败导致对话入口报错 | QueryService 读取失败返回 `Optional.empty()` 并记录 warn；Conversation 回落到既有预览逻辑 |
| sourceUrls 丢失 | 统一对话入口无法满足无幻觉红线 | QueryService 同时读取 `payload.decision.sourceUrls` 和事件 `sourceUrls` 兜底，DTO、IntentDecision 和前端卡片都保留来源 |
| 前端确认对象字段扩展破坏旧请求 | 旧确认请求缺少新字段时执行失败 | 新字段全部可空，`TaskActionTranslator.buildExecutionPlan` 不依赖这些字段执行既有动作 |

## File Structure

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionView.java`
  - 对话入口专用的编排决策只读视图。
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java`
  - 从 `TaskWorkflowEvent` 最新编排事件中提取 view。
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicyTest.java`
- `frontend/src/components/conversation/TaskActionPreviewCard.test.tsx`

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationResponse.java`
  - 新增 `OrchestrationDecisionSummary`，并挂到 `TaskActionPreview`。
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationActionConfirmationRequest.java`
  - 新增可空的编排决策审计字段。
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/TaskActionTranslator.java`
  - 增加带 `ConversationOrchestrationDecisionView` 的重载并实现决策到动作预览映射。
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicy.java`
  - 支持不可确认的人工介入预览。
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationService.java`
  - 注入 QueryService，TASK_ACTION / RESEARCH 预览传入最新决策上下文。
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/TaskActionTranslatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/controller/ConversationControllerContractTest.java`
- `frontend/src/types/index.ts`
  - 增加对话编排决策摘要类型。
- `frontend/src/utils/conversationPresentation.ts`
  - 增加证据状态和 Orchestrator 摘要展示文案。
- `frontend/src/utils/conversationPresentation.test.ts`
  - 覆盖证据状态文案。
- `frontend/src/components/conversation/TaskActionPreviewCard.tsx`
  - 展示编排决策、证据状态和来源。
- `frontend/src/pages/ConversationPage.test.tsx`
  - 覆盖对话页渲染 Orchestrator 决策摘要。
- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
  - 执行完成后回写 P3-3 状态。
- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
  - 执行完成后追加 P3-3 自动化实现记录。
- `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
  - 执行完成后更新稳定演示版当前阶段。
- `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-3-conversation-decision-preview-implementation-plan.md`
  - 执行过程中持续更新进度和验证结果。

### Reference Only

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecision.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationTraceService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/repository/TaskWorkflowEventRepository.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventType.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/entity/TaskWorkflowEvent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskRuntimeFacade.java`

---

## Task 1: 新增 Conversation 编排决策只读视图

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionView.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java`

- [ ] **Step 1: 写无事件降级红灯测试**

Create `ConversationOrchestrationDecisionQueryServiceTest`:

```java
package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationOrchestrationDecisionQueryServiceTest {

    @Test
    void shouldReturnEmptyWhenTaskHasNoRecordedDecision() {
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        when(repository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                88L,
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
        )).thenReturn(Optional.empty());
        ConversationOrchestrationDecisionQueryService service =
                new ConversationOrchestrationDecisionQueryService(repository, new ObjectMapper());

        Optional<ConversationOrchestrationDecisionView> result = service.findLatestDecision(88L);

        assertThat(result).isEmpty();
        verify(repository).findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                88L,
                WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
        );
    }
}
```

Run: `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest" test`

Expected: FAIL with `cannot find symbol: class ConversationOrchestrationDecisionQueryService`.

- [ ] **Step 2: 写解析最近编排决策红灯测试**

Append to `ConversationOrchestrationDecisionQueryServiceTest`:

```java
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;

import java.time.LocalDateTime;

// ...

@Test
void shouldReadLatestDecisionFromWorkflowEventPayload() {
    TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
    TaskWorkflowEvent event = TaskWorkflowEvent.builder()
            .id(501L)
            .eventId("event-p3-3-001")
            .taskId(88L)
            .nodeName("analyze_competitors")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .payload("""
                    {
                      "summary": "Orchestrator 已生成运行期编排决策",
                      "decision": {
                        "decisionId": "od-88-analyze_competitors-human",
                        "taskId": 88,
                        "triggerNodeName": "analyze_competitors",
                        "decisionType": "WAIT_FOR_HUMAN",
                        "actionType": "MANUAL_REVIEW",
                        "targetNode": "analyze_competitors",
                        "affectedScope": "CURRENT_NODE_ONLY",
                        "reason": "Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。",
                        "requiresHumanIntervention": true,
                        "requiresConfirmation": true,
                        "evidenceState": "MISSING_SOURCE",
                        "sourceUrls": []
                      }
                    }
                    """)
            .sourceUrls("[]")
            .createdAt(LocalDateTime.now())
            .build();
    when(repository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
            88L,
            WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
    )).thenReturn(Optional.of(event));
    ConversationOrchestrationDecisionQueryService service =
            new ConversationOrchestrationDecisionQueryService(repository, new ObjectMapper());

    Optional<ConversationOrchestrationDecisionView> result = service.findLatestDecision(88L);

    assertThat(result).isPresent();
    ConversationOrchestrationDecisionView view = result.orElseThrow();
    assertThat(view.getDecisionId()).isEqualTo("od-88-analyze_competitors-human");
    assertThat(view.getTaskId()).isEqualTo(88L);
    assertThat(view.getTriggerNodeName()).isEqualTo("analyze_competitors");
    assertThat(view.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    assertThat(view.getActionType()).isEqualTo("MANUAL_REVIEW");
    assertThat(view.getTargetNode()).isEqualTo("analyze_competitors");
    assertThat(view.getReason()).contains("禁止自动补证");
    assertThat(view.isRequiresHumanIntervention()).isTrue();
    assertThat(view.getEvidenceState()).isEqualTo("MISSING_SOURCE");
    assertThat(view.getSourceUrls()).isEmpty();
}
```

Run: `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest" test`

Expected: FAIL because view and parsing implementation do not exist.

- [ ] **Step 3: 写 sourceUrls 兜底解析红灯测试**

Append to `ConversationOrchestrationDecisionQueryServiceTest`:

```java
@Test
void shouldFallbackToEventSourceUrlsWhenDecisionSourceUrlsAreMissing() {
    TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
    TaskWorkflowEvent event = TaskWorkflowEvent.builder()
            .id(502L)
            .eventId("event-p3-3-002")
            .taskId(99L)
            .nodeName("write_report")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .payload("""
                    {
                      "decision": {
                        "decisionId": "od-99-write_report-writer-suggestion-1",
                        "taskId": 99,
                        "triggerNodeName": "write_report",
                        "decisionType": "REWRITE_ONLY",
                        "actionType": "REWRITE_SECTION",
                        "targetNode": "rewrite_report",
                        "affectedScope": "CURRENT_NODE_ONLY",
                        "reason": "定价章节需要补充逐句引用。",
                        "evidenceState": "PARTIAL_SOURCE"
                      }
                    }
                    """)
            .sourceUrls("[\"https://www.notion.so/pricing\"]")
            .createdAt(LocalDateTime.now())
            .build();
    when(repository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
            99L,
            WorkflowEventType.ORCHESTRATION_DECISION_RECORDED
    )).thenReturn(Optional.of(event));
    ConversationOrchestrationDecisionQueryService service =
            new ConversationOrchestrationDecisionQueryService(repository, new ObjectMapper());

    ConversationOrchestrationDecisionView view = service.findLatestDecision(99L).orElseThrow();

    assertThat(view.getDecisionType()).isEqualTo("REWRITE_ONLY");
    assertThat(view.getActionType()).isEqualTo("REWRITE_SECTION");
    assertThat(view.getTargetNode()).isEqualTo("rewrite_report");
    assertThat(view.getEvidenceState()).isEqualTo("PARTIAL_SOURCE");
    assertThat(view.getSourceUrls()).containsExactly("https://www.notion.so/pricing");
}
```

Run: `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest" test`

Expected: FAIL until source fallback parsing is implemented.

- [ ] **Step 4: 新增 ConversationOrchestrationDecisionView**

Create `ConversationOrchestrationDecisionView.java`:

```java
package cn.bugstack.competitoragent.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 对话入口只读的编排决策摘要。
 * 它只服务于动作预览展示，不替代正式 OrchestrationDecision，也不能直接表达执行授权。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConversationOrchestrationDecisionView {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    private String targetNode;
    private String affectedScope;
    private String reason;
    private boolean requiresHumanIntervention;
    private boolean requiresConfirmation;
    private String evidenceState;
    @Builder.Default
    private List<String> sourceUrls = List.of();

    public ConversationOrchestrationDecisionView normalized() {
        return toBuilder()
                .decisionId(blankToNull(decisionId))
                .triggerNodeName(blankToNull(triggerNodeName))
                .decisionType(upperOrDefault(decisionType, "NO_ACTION"))
                .actionType(upperOrDefault(actionType, "NO_ACTION"))
                .targetNode(blankToNull(targetNode))
                .affectedScope(upperOrDefault(affectedScope, "CURRENT_NODE_ONLY"))
                .reason(blankToDefault(reason, "最近编排决策缺少说明，已按保守预览处理。"))
                .evidenceState(upperOrDefault(evidenceState, sourceUrls == null || sourceUrls.isEmpty()
                        ? "MISSING_SOURCE"
                        : "FULL_SOURCE"))
                .sourceUrls(normalizeDistinct(sourceUrls))
                .build();
    }

    private String upperOrDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> normalizeDistinct(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = blankToNull(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }
}
```

- [ ] **Step 5: 新增 ConversationOrchestrationDecisionQueryService**

Create `ConversationOrchestrationDecisionQueryService.java`:

```java
package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 统一对话入口的编排决策查询服务。
 * 本服务只读取已落库的 Orchestrator trace，不重新推导策略，不触发任何任务动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationOrchestrationDecisionQueryService {

    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final ObjectMapper objectMapper;

    public Optional<ConversationOrchestrationDecisionView> findLatestDecision(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return taskWorkflowEventRepository
                .findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                        taskId,
                        WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .flatMap(this::toView);
    }

    private Optional<ConversationOrchestrationDecisionView> toView(TaskWorkflowEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            JsonNode decision = payload.path("decision");
            if (decision.isMissingNode() || decision.isNull()) {
                return Optional.empty();
            }
            List<String> decisionSourceUrls = readStringList(decision.path("sourceUrls"));
            List<String> eventSourceUrls = readStringList(event.getSourceUrls());
            return Optional.of(ConversationOrchestrationDecisionView.builder()
                    .decisionId(text(decision, "decisionId"))
                    .taskId(longValue(decision, "taskId", event.getTaskId()))
                    .triggerNodeName(firstNonBlank(text(decision, "triggerNodeName"), event.getNodeName()))
                    .decisionType(text(decision, "decisionType"))
                    .actionType(text(decision, "actionType"))
                    .targetNode(text(decision, "targetNode"))
                    .affectedScope(text(decision, "affectedScope"))
                    .reason(text(decision, "reason"))
                    .requiresHumanIntervention(decision.path("requiresHumanIntervention").asBoolean(false))
                    .requiresConfirmation(decision.path("requiresConfirmation").asBoolean(false))
                    .evidenceState(text(decision, "evidenceState"))
                    .sourceUrls(mergeSourceUrls(decisionSourceUrls, eventSourceUrls))
                    .build()
                    .normalized());
        } catch (Exception e) {
            log.warn("read latest orchestration decision for conversation failed, taskId={}",
                    event.getTaskId(), e);
            return Optional.empty();
        }
    }

    private List<String> readStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return readStringList(node);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        });
        return values;
    }

    private List<String> mergeSourceUrls(List<String> primary, List<String> fallback) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (merged.isEmpty() && fallback != null) {
            merged.addAll(fallback);
        }
        return new ArrayList<>(merged);
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private Long longValue(JsonNode node, String fieldName, Long fallback) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || !value.canConvertToLong() ? fallback : value.asLong();
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
```

- [ ] **Step 6: 运行 Task 1 测试**

Run: `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest" test`

Expected: PASS, 3 tests, 0 failures.

- [ ] **Step 7: 记录 Task 1 进度**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task1-progress.md`:

```markdown
# P3-3 Task 1 Progress - 2026-06-24

当前阶段：P3-3 Task 1 已完成 Conversation 编排决策只读视图，准备进入 Task 2 DTO 与动作预览翻译器。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行
- [ ] 质检复核：待执行

| 步骤 | 核心目标 | 预期耗时 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 查询无事件时安全降级 | 10 分钟 | outbox repository 可用 | 已完成 |
| 2 | 解析最近编排决策 payload | 20 分钟 | `ORCHESTRATION_DECISION_RECORDED` 事件结构稳定 | 已完成 |
| 3 | 兜底读取事件 sourceUrls | 15 分钟 | outbox sourceUrls 字段为 JSON 数组 | 已完成 |
| 4 | 运行局部测试 | 10 分钟 | QueryService 完成 | 已完成 |

## 验证结果

`mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest" test` 通过，3 tests, 0 failures。

## 下一步

执行 Task 2：扩展后端 DTO 与 `TaskActionTranslator`，把决策视图转换为动作预览。
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionView.java backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task1-progress.md
git commit -m "feat: add conversation orchestration decision view"
```

---

## Task 2: 扩展 DTO 与动作预览翻译器

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationActionConfirmationRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/conversation/TaskActionTranslator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicy.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/conversation/TaskActionTranslatorTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicyTest.java`

- [ ] **Step 1: 写 SUPPLEMENT_EVIDENCE 决策增强预览红灯测试**

Append to `TaskActionTranslatorTest`:

```java
@Test
void shouldPreferOrchestrationSupplementDecisionWhenBuildingResearchPreview() {
    PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
    when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
    TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);
    ConversationOrchestrationDecisionView decision = ConversationOrchestrationDecisionView.builder()
            .decisionId("od-24-extract_schema-suggestion-1")
            .taskId(24L)
            .triggerNodeName("extract_schema")
            .decisionType("APPEND_DYNAMIC_BRANCH")
            .actionType("SUPPLEMENT_EVIDENCE")
            .targetNode("collect_sources_01_01")
            .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
            .reason("extract_schema 发现定价字段缺少官网来源。")
            .evidenceState("PARTIAL_SOURCE")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .build()
            .normalized();

    ConversationResponse.TaskActionPreview preview = translator.buildResearchPreview(
            "继续补证定价来源",
            24L,
            List.of(),
            List.of(),
            decision);

    assertEquals("SUPPLEMENT_EVIDENCE", preview.getActionType());
    assertEquals("collect_sources_01_01", preview.getTargetNodeName());
    assertTrue(preview.getTitle().contains("Orchestrator"));
    assertTrue(preview.getActionSummary().contains("定价字段"));
    assertTrue(preview.getConfirmationHint().contains("编排决策"));
    assertEquals("PARTIAL_SOURCE", preview.getOrchestrationDecision().getEvidenceState());
    assertEquals("od-24-extract_schema-suggestion-1", preview.getOrchestrationDecision().getDecisionId());
    assertEquals(List.of("https://www.notion.so/pricing"), preview.getSourceUrls());
}
```

Also add imports:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
```

Run: `mvn -pl backend "-Dtest=TaskActionTranslatorTest#shouldPreferOrchestrationSupplementDecisionWhenBuildingResearchPreview" test`

Expected: FAIL because `buildResearchPreview(..., decision)` and `orchestrationDecision` do not exist.

- [ ] **Step 2: 写 WAIT_FOR_HUMAN 不可确认执行红灯测试**

Append to `TaskActionTranslatorTest`:

```java
@Test
void shouldExposeHumanInterventionDecisionWithoutConfirmableExecution() {
    PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
    when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
    TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);
    ConversationOrchestrationDecisionView decision = ConversationOrchestrationDecisionView.builder()
            .decisionId("od-25-analyze_competitors-human")
            .taskId(25L)
            .triggerNodeName("analyze_competitors")
            .decisionType("WAIT_FOR_HUMAN")
            .actionType("MANUAL_REVIEW")
            .targetNode("analyze_competitors")
            .affectedScope("CURRENT_NODE_ONLY")
            .reason("Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。")
            .requiresHumanIntervention(true)
            .requiresConfirmation(true)
            .evidenceState("MISSING_SOURCE")
            .sourceUrls(List.of())
            .build()
            .normalized();

    ConversationResponse.TaskActionPreview preview = translator.buildTaskActionPreview(
            "系统建议我下一步做什么？",
            25L,
            TaskResponse.builder().id(25L).statusSummary("等待人工介入").build(),
            List.of(),
            decision);

    assertEquals("WAIT_FOR_HUMAN", preview.getActionType());
    assertEquals("analyze_competitors", preview.getTargetNodeName());
    assertEquals(Boolean.FALSE, preview.getRequiresConfirmation());
    assertEquals(Boolean.FALSE, preview.getExecutable());
    assertTrue(preview.getImpactSummary().contains("不会直接提交任务控制"));
    assertEquals("MISSING_SOURCE", preview.getOrchestrationDecision().getEvidenceState());
}
```

Run: `mvn -pl backend "-Dtest=TaskActionTranslatorTest#shouldExposeHumanInterventionDecisionWithoutConfirmableExecution" test`

Expected: FAIL until translator supports human-intervention decisions.

- [ ] **Step 3: 写 REWRITE_ONLY 决策映射为重跑预览红灯测试**

Append to `TaskActionTranslatorTest`:

```java
@Test
void shouldMapRewriteOnlyDecisionToRewriteNodePreview() {
    PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
    when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
    TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);
    ConversationOrchestrationDecisionView decision = ConversationOrchestrationDecisionView.builder()
            .decisionId("od-26-write_report-writer-suggestion-1")
            .taskId(26L)
            .triggerNodeName("write_report")
            .decisionType("REWRITE_ONLY")
            .actionType("REWRITE_SECTION")
            .targetNode("rewrite_report")
            .affectedScope("CURRENT_NODE_ONLY")
            .reason("定价章节已有来源但逐句引用不完整，建议重写该章节。")
            .evidenceState("PARTIAL_SOURCE")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .build()
            .normalized();

    ConversationResponse.TaskActionPreview preview = translator.buildTaskActionPreview(
            "从报告改写继续处理",
            26L,
            TaskResponse.builder().id(26L).statusSummary("Writer 决策建议重写章节").build(),
            List.of(),
            decision);

    assertEquals("RERUN_NODE", preview.getActionType());
    assertEquals("rewrite_report", preview.getTargetNodeName());
    assertEquals("HIGH", preview.getRiskLevel());
    assertEquals(Boolean.TRUE, preview.getRequiresConfirmation());
    assertTrue(preview.getActionSummary().contains("逐句引用不完整"));
    assertEquals("REWRITE_ONLY", preview.getOrchestrationDecision().getDecisionType());
}
```

Run: `mvn -pl backend "-Dtest=TaskActionTranslatorTest#shouldMapRewriteOnlyDecisionToRewriteNodePreview" test`

Expected: FAIL until rewrite decision mapping is implemented.

- [ ] **Step 4: 写安全策略红灯测试**

Create `ConversationSafetyPolicyTest.java`:

```java
package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSafetyPolicyTest {

    @Test
    void shouldNotBuildConfirmationRequestForHumanInterventionPreview() {
        IntentRecognitionService.RecognitionResult recognitionResult =
                IntentRecognitionService.RecognitionResult.builder()
                        .mode(ConversationMode.TASK_ACTION)
                        .intentType("RERUN_FROM_NODE")
                        .decisionReason("用户询问下一步动作")
                        .highRiskAction(true)
                        .requiresConfirmation(true)
                        .build();
        ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
                .actionType("WAIT_FOR_HUMAN")
                .targetNodeName("analyze_competitors")
                .title("等待人工介入")
                .riskLevel("HIGH")
                .requiresConfirmation(false)
                .executable(false)
                .build();

        ConversationSafetyPolicy policy =
                ConversationSafetyPolicy.from(ConversationMode.TASK_ACTION, recognitionResult, preview);

        assertThat(policy.isHighRiskAction()).isTrue();
        assertThat(policy.isRequiresConfirmation()).isFalse();
        assertThat(policy.getConfirmationRequest()).isNull();
    }
}
```

Run: `mvn -pl backend "-Dtest=ConversationSafetyPolicyTest" test`

Expected: FAIL because current safety policy keeps recognition-level confirmation.

- [ ] **Step 5: 扩展 ConversationResponse DTO**

In `ConversationResponse.java`, add nested class before `TaskActionPreview`:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对话动作预览中的编排决策摘要")
public static class OrchestrationDecisionSummary {
    private String decisionId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    private String targetNode;
    private String affectedScope;
    private String reason;
    private String evidenceState;
    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();
}
```

Then add to `TaskActionPreview`:

```java
private OrchestrationDecisionSummary orchestrationDecision;
```

- [ ] **Step 6: 扩展 ConversationActionConfirmationRequest**

In `ConversationActionConfirmationRequest.java`, add nullable audit fields:

```java
@Schema(description = "触发该确认对象的编排决策 ID")
private String orchestrationDecisionId;

@Schema(description = "触发该确认对象的编排决策类型")
private String orchestrationDecisionType;

@Schema(description = "触发该确认对象的编排证据状态")
private String orchestrationEvidenceState;
```

- [ ] **Step 7: 扩展 TaskActionTranslator 方法签名和映射**

In `TaskActionTranslator.java`, keep existing methods and add overloads:

```java
public ConversationResponse.TaskActionPreview buildTaskActionPreview(String message,
                                                                    Long taskId,
                                                                    TaskResponse task,
                                                                    List<TaskNodeResponse> nodes,
                                                                    ConversationOrchestrationDecisionView decisionView) {
    ConversationResponse.TaskActionPreview decisionPreview = buildDecisionPreview(taskId, decisionView);
    if (decisionPreview != null) {
        return decisionPreview;
    }
    return buildTaskActionPreview(message, taskId, task, nodes);
}

public ConversationResponse.TaskActionPreview buildResearchPreview(String message,
                                                                   Long taskId,
                                                                   List<TaskNodeResponse> nodes,
                                                                   List<String> sourceUrls,
                                                                   ConversationOrchestrationDecisionView decisionView) {
    ConversationResponse.TaskActionPreview decisionPreview = buildDecisionPreview(taskId, decisionView);
    if (decisionPreview != null && !"WAIT_FOR_HUMAN".equalsIgnoreCase(safe(decisionPreview.getActionType()))) {
        return decisionPreview;
    }
    return buildResearchPreview(message, taskId, nodes, sourceUrls);
}
```

Add helper methods:

```java
private ConversationResponse.TaskActionPreview buildDecisionPreview(Long taskId,
                                                                    ConversationOrchestrationDecisionView rawDecision) {
    if (rawDecision == null) {
        return null;
    }
    ConversationOrchestrationDecisionView decision = rawDecision.normalized();
    if ("NO_ACTION".equals(decision.getDecisionType())) {
        return null;
    }
    if ("WAIT_FOR_HUMAN".equals(decision.getDecisionType())
            || "MANUAL_REVIEW".equals(decision.getActionType())) {
        return ConversationResponse.TaskActionPreview.builder()
                .actionType("WAIT_FOR_HUMAN")
                .taskId(taskId)
                .targetNodeName(decision.getTargetNode())
                .title("来自 Orchestrator 的人工介入建议")
                .actionSummary(decision.getReason())
                .impactSummary("该决策要求人工确认证据或业务判断，本次对话只展示原因，不会直接提交任务控制。")
                .riskLevel("HIGH")
                .requiresConfirmation(false)
                .confirmationHint("请先补齐缺口或人工复核后，再选择恢复、重跑或补证动作。")
                .executable(false)
                .sourceUrls(decision.getSourceUrls())
                .orchestrationDecision(toDecisionSummary(decision))
                .build();
    }
    if ("REWRITE_ONLY".equals(decision.getDecisionType())
            || "REWRITE_SECTION".equals(decision.getActionType())) {
        String targetNode = firstNonBlank(decision.getTargetNode(), "rewrite_report");
        return ConversationResponse.TaskActionPreview.builder()
                .actionType("RERUN_NODE")
                .taskId(taskId)
                .targetNodeName(targetNode)
                .title("来自 Orchestrator 的章节重写建议")
                .actionSummary(decision.getReason())
                .impactSummary("确认后会从 " + targetNode + " 重新组织报告改写及后续链路。")
                .riskLevel("HIGH")
                .requiresConfirmation(true)
                .confirmationHint("该动作来自编排决策，确认前请先核对影响范围和来源证据。")
                .executable(false)
                .sourceUrls(decision.getSourceUrls())
                .orchestrationDecision(toDecisionSummary(decision))
                .build();
    }
    if ("APPEND_DYNAMIC_BRANCH".equals(decision.getDecisionType())
            || "SUPPLEMENT_EVIDENCE".equals(decision.getActionType())) {
        String targetNode = firstNonBlank(decision.getTargetNode(), "collect_sources_01_01");
        return ConversationResponse.TaskActionPreview.builder()
                .actionType("SUPPLEMENT_EVIDENCE")
                .taskId(taskId)
                .targetNodeName(targetNode)
                .title("来自 Orchestrator 的补证建议")
                .actionSummary(decision.getReason())
                .impactSummary("确认后会围绕 " + targetNode + " 补充证据，并影响当前任务的证据链路。")
                .riskLevel("MEDIUM")
                .requiresConfirmation(true)
                .confirmationHint("该补证动作来自编排决策，确认后才会提交正式任务控制。")
                .executable(false)
                .sourceUrls(decision.getSourceUrls())
                .orchestrationDecision(toDecisionSummary(decision))
                .build();
    }
    return null;
}

private ConversationResponse.OrchestrationDecisionSummary toDecisionSummary(
        ConversationOrchestrationDecisionView decision) {
    return ConversationResponse.OrchestrationDecisionSummary.builder()
            .decisionId(decision.getDecisionId())
            .triggerNodeName(decision.getTriggerNodeName())
            .decisionType(decision.getDecisionType())
            .actionType(decision.getActionType())
            .targetNode(decision.getTargetNode())
            .affectedScope(decision.getAffectedScope())
            .reason(decision.getReason())
            .evidenceState(decision.getEvidenceState())
            .sourceUrls(decision.getSourceUrls())
            .build();
}
```

Update `buildExecutionPlan(...)` so existing actions preserve decision metadata but do not depend on it. No new executable action is added for `WAIT_FOR_HUMAN`.

- [ ] **Step 8: 扩展 ConversationSafetyPolicy**

In `ConversationSafetyPolicy.from(...)`, after reading `preview`, add:

```java
if (preview != null && Boolean.FALSE.equals(preview.getRequiresConfirmation())
        && "WAIT_FOR_HUMAN".equalsIgnoreCase(safe(preview.getActionType()))) {
    requiresConfirmation = false;
}
```

In `buildConfirmationRequest(...)`, add decision fields:

```java
.orchestrationDecisionId(preview.getOrchestrationDecision() == null
        ? null
        : preview.getOrchestrationDecision().getDecisionId())
.orchestrationDecisionType(preview.getOrchestrationDecision() == null
        ? null
        : preview.getOrchestrationDecision().getDecisionType())
.orchestrationEvidenceState(preview.getOrchestrationDecision() == null
        ? null
        : preview.getOrchestrationDecision().getEvidenceState())
```

- [ ] **Step 9: 运行 Task 2 局部测试**

Run:

```bash
mvn -pl backend "-Dtest=TaskActionTranslatorTest,ConversationSafetyPolicyTest" test
```

Expected: PASS, existing TaskActionTranslator tests plus new P3-3 tests pass.

- [ ] **Step 10: 记录 Task 2 进度**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task2-progress.md`:

```markdown
# P3-3 Task 2 Progress - 2026-06-24

当前阶段：P3-3 Task 2 已完成 DTO 与动作预览翻译器扩展，准备进入 Task 3 ConversationService 接入。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行
- [ ] 质检复核：待执行

## 已完成内容

1. `ConversationResponse.TaskActionPreview` 已新增 `orchestrationDecision` 摘要。
2. `ConversationActionConfirmationRequest` 已新增可空编排决策审计字段。
3. `TaskActionTranslator` 已支持 `SUPPLEMENT_EVIDENCE / REWRITE_ONLY / WAIT_FOR_HUMAN` 三类编排决策预览映射。
4. `ConversationSafetyPolicy` 已阻止 `WAIT_FOR_HUMAN` 预览误生成确认执行对象。

## 验证结果

`mvn -pl backend "-Dtest=TaskActionTranslatorTest,ConversationSafetyPolicyTest" test` 通过。

## 下一步

执行 Task 3：`ConversationService` 注入决策查询服务，并在 TASK_ACTION / RESEARCH 响应中透传决策上下文。
```

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationActionConfirmationRequest.java backend/src/main/java/cn/bugstack/competitoragent/conversation/TaskActionTranslator.java backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicy.java backend/src/test/java/cn/bugstack/competitoragent/conversation/TaskActionTranslatorTest.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicyTest.java docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task2-progress.md
git commit -m "feat: enrich conversation previews with orchestration decisions"
```

---

## Task 3: ConversationService 接入决策上下文和审计持久化

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationServiceTest.java`

- [ ] **Step 1: 写 TASK_ACTION 服务层红灯测试**

In `ConversationServiceTest`, add mock field:

```java
@Mock
private ConversationOrchestrationDecisionQueryService orchestrationDecisionQueryService;
```

Update `setUp()` constructor call by adding `orchestrationDecisionQueryService` immediately after `taskActionTranslator` and before `conversationAgent`, matching the production field order.

Append test:

```java
@Test
void shouldPassLatestOrchestrationDecisionIntoTaskActionPreviewAndPersistSources() {
    ConversationMessageRequest request = new ConversationMessageRequest();
    request.setTaskId(405L);
    request.setPageType("TASK_DETAIL");
    request.setMessage("系统建议我下一步做什么？");

    IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
            .mode(ConversationMode.TASK_ACTION)
            .intentType("RESUME_TASK")
            .decisionReason("用户询问下一步动作")
            .highRiskAction(true)
            .requiresConfirmation(true)
            .build();
    TaskResponse taskResponse = TaskResponse.builder()
            .id(405L)
            .currentStage("分析推理")
            .statusSummary("Analyzer 等待人工介入")
            .build();
    ConversationOrchestrationDecisionView decisionView = ConversationOrchestrationDecisionView.builder()
            .decisionId("od-405-analyze_competitors-human")
            .taskId(405L)
            .triggerNodeName("analyze_competitors")
            .decisionType("WAIT_FOR_HUMAN")
            .actionType("MANUAL_REVIEW")
            .targetNode("analyze_competitors")
            .reason("Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。")
            .evidenceState("MISSING_SOURCE")
            .sourceUrls(List.of())
            .build()
            .normalized();
    ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
            .actionType("WAIT_FOR_HUMAN")
            .taskId(405L)
            .targetNodeName("analyze_competitors")
            .title("来自 Orchestrator 的人工介入建议")
            .actionSummary(decisionView.getReason())
            .riskLevel("HIGH")
            .requiresConfirmation(false)
            .executable(false)
            .orchestrationDecision(ConversationResponse.OrchestrationDecisionSummary.builder()
                    .decisionId(decisionView.getDecisionId())
                    .decisionType(decisionView.getDecisionType())
                    .actionType(decisionView.getActionType())
                    .triggerNodeName(decisionView.getTriggerNodeName())
                    .reason(decisionView.getReason())
                    .evidenceState(decisionView.getEvidenceState())
                    .sourceUrls(decisionView.getSourceUrls())
                    .build())
            .sourceUrls(decisionView.getSourceUrls())
            .build();

    when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
        ConversationSession session = invocation.getArgument(0);
        if (session.getId() == null) {
            session.setId(4050L);
        }
        return session;
    });
    when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(4050L)).thenReturn(Optional.empty());
    when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
    when(clarificationOrchestrator.resolve(any(), any(), any(), any(), any())).thenReturn(
            ClarificationOrchestrator.ClarificationDecision.none());
    when(modeRouter.route(recognitionResult, 405L)).thenReturn(ConversationMode.TASK_ACTION);
    when(taskQueryFacade.getTask(405L)).thenReturn(taskResponse);
    when(taskQueryFacade.getTaskNodes(405L)).thenReturn(List.of());
    when(orchestrationDecisionQueryService.findLatestDecision(405L)).thenReturn(Optional.of(decisionView));
    when(taskActionTranslator.buildTaskActionPreview(
            request.getMessage(),
            405L,
            taskResponse,
            List.of(),
            decisionView
    )).thenReturn(preview);
    when(conversationAgent.composeActionPreviewAnswer(request.getMessage(), preview))
            .thenReturn("我先展示 Orchestrator 的人工介入建议。");
    when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
        IntentDecision decision = invocation.getArgument(0);
        decision.setId(4051L);
        return decision;
    });

    ConversationResponse response = conversationService.handleMessage(request);

    assertEquals("TASK_ACTION", response.getMode());
    assertNotNull(response.getTaskActionPreview().getOrchestrationDecision());
    assertEquals("od-405-analyze_competitors-human",
            response.getTaskActionPreview().getOrchestrationDecision().getDecisionId());
    assertEquals(Boolean.FALSE, response.getIntentDecision().getRequiresConfirmation());
    ArgumentCaptor<IntentDecision> decisionCaptor = ArgumentCaptor.forClass(IntentDecision.class);
    verify(intentDecisionRepository).save(decisionCaptor.capture());
    assertTrue(decisionCaptor.getValue().getDecisionPayload().contains("od-405-analyze_competitors-human"));
    assertTrue(decisionCaptor.getValue().getDecisionPayload().contains("MISSING_SOURCE"));
}
```

Run: `mvn -pl backend "-Dtest=ConversationServiceTest#shouldPassLatestOrchestrationDecisionIntoTaskActionPreviewAndPersistSources" test`

Expected: FAIL because constructor and service call do not yet support QueryService.

- [ ] **Step 2: 写 RESEARCH 服务层红灯测试**

Append to `ConversationServiceTest`:

```java
@Test
void shouldPassLatestOrchestrationDecisionIntoResearchPreview() {
    ConversationMessageRequest request = new ConversationMessageRequest();
    request.setTaskId(406L);
    request.setPageType("TASK_DETAIL");
    request.setMessage("继续补证定价来源");

    IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
            .mode(ConversationMode.RESEARCH)
            .intentType("SUPPLEMENT_EVIDENCE")
            .decisionReason("用户请求补证")
            .highRiskAction(true)
            .requiresConfirmation(true)
            .build();
    ConversationOrchestrationDecisionView decisionView = ConversationOrchestrationDecisionView.builder()
            .decisionId("od-406-extract_schema-suggestion-1")
            .taskId(406L)
            .triggerNodeName("extract_schema")
            .decisionType("APPEND_DYNAMIC_BRANCH")
            .actionType("SUPPLEMENT_EVIDENCE")
            .targetNode("collect_sources_01_01")
            .reason("extract_schema 发现定价字段缺少官网来源。")
            .evidenceState("PARTIAL_SOURCE")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .build()
            .normalized();
    ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
            .actionType("SUPPLEMENT_EVIDENCE")
            .taskId(406L)
            .targetNodeName("collect_sources_01_01")
            .title("来自 Orchestrator 的补证建议")
            .actionSummary(decisionView.getReason())
            .riskLevel("MEDIUM")
            .requiresConfirmation(true)
            .sourceUrls(decisionView.getSourceUrls())
            .orchestrationDecision(ConversationResponse.OrchestrationDecisionSummary.builder()
                    .decisionId(decisionView.getDecisionId())
                    .decisionType(decisionView.getDecisionType())
                    .actionType(decisionView.getActionType())
                    .evidenceState(decisionView.getEvidenceState())
                    .sourceUrls(decisionView.getSourceUrls())
                    .build())
            .build();

    when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
        ConversationSession session = invocation.getArgument(0);
        if (session.getId() == null) {
            session.setId(4060L);
        }
        return session;
    });
    when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(4060L)).thenReturn(Optional.empty());
    when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
    when(clarificationOrchestrator.resolve(any(), any(), any(), any(), any())).thenReturn(
            ClarificationOrchestrator.ClarificationDecision.none());
    when(modeRouter.route(recognitionResult, 406L)).thenReturn(ConversationMode.RESEARCH);
    when(taskQueryFacade.getTask(406L)).thenReturn(TaskResponse.builder().id(406L).build());
    when(taskQueryFacade.getTaskNodes(406L)).thenReturn(List.of());
    when(orchestrationDecisionQueryService.findLatestDecision(406L)).thenReturn(Optional.of(decisionView));
    when(knowledgeRetrievalFacade.retrieveForTask(406L, request.getMessage(), "conversation"))
            .thenReturn(new KnowledgeRetrievalFacade.RetrievalResultView(
                    List.of("https://www.notion.so/pricing"),
                    "命中定价来源",
                    "建议补定价字段",
                    List.of("E-406"),
                    List.of()
            ));
    when(taskActionTranslator.buildResearchPreview(
            request.getMessage(),
            406L,
            List.of(),
            List.of("https://www.notion.so/pricing"),
            decisionView
    )).thenReturn(preview);
    when(conversationAgent.composeResearchAnswer(
            eq(request.getMessage()),
            eq(preview),
            any(),
            eq("建议补定价字段")
    )).thenReturn("我先展示 Orchestrator 的补证建议。");
    when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
        IntentDecision decision = invocation.getArgument(0);
        decision.setId(4061L);
        return decision;
    });

    ConversationResponse response = conversationService.handleMessage(request);

    assertEquals("RESEARCH", response.getMode());
    assertEquals(List.of("https://www.notion.so/pricing"), response.getSourceUrls());
    assertEquals("od-406-extract_schema-suggestion-1",
            response.getTaskActionPreview().getOrchestrationDecision().getDecisionId());
}
```

Run: `mvn -pl backend "-Dtest=ConversationServiceTest#shouldPassLatestOrchestrationDecisionIntoResearchPreview" test`

Expected: FAIL until service passes decision view into research preview.

- [ ] **Step 3: 修改 ConversationService 构造器依赖**

In `ConversationService.java`, add field:

```java
private final ConversationOrchestrationDecisionQueryService orchestrationDecisionQueryService;
```

Place it after `TaskActionTranslator taskActionTranslator` to keep conversation dependencies grouped.

- [ ] **Step 4: 读取最近编排决策并传入 TASK_ACTION**

In `buildTaskActionResponse(...)`, before translator call:

```java
ConversationOrchestrationDecisionView orchestrationDecision =
        orchestrationDecisionQueryService.findLatestDecision(taskId).orElse(null);
```

Replace call with:

```java
ConversationResponse.TaskActionPreview preview =
        taskActionTranslator.buildTaskActionPreview(
                request.getMessage(),
                taskId,
                taskResponse,
                nodeResponses,
                orchestrationDecision);
```

- [ ] **Step 5: 读取最近编排决策并传入 RESEARCH**

In `buildResearchResponse(...)`, before translator call:

```java
ConversationOrchestrationDecisionView orchestrationDecision =
        orchestrationDecisionQueryService.findLatestDecision(taskId).orElse(null);
```

Replace call with:

```java
ConversationResponse.TaskActionPreview preview = taskActionTranslator.buildResearchPreview(
        request.getMessage(),
        taskId,
        nodeResponses,
        retrievalResult.sourceUrls(),
        orchestrationDecision
);
```

- [ ] **Step 6: 确认响应 sourceUrls 采用 preview 来源优先**

In `buildResearchResponse(...)`, set sourceUrls:

```java
.sourceUrls(preview.getSourceUrls() == null || preview.getSourceUrls().isEmpty()
        ? retrievalResult.sourceUrls()
        : preview.getSourceUrls())
```

Keep TASK_ACTION as:

```java
.sourceUrls(preview.getSourceUrls())
```

- [ ] **Step 7: 更新测试构造器**

Update every `new ConversationService(...)` in `ConversationServiceTest` to include `orchestrationDecisionQueryService`. Existing tests that do not care about decisions should use:

```java
when(orchestrationDecisionQueryService.findLatestDecision(any())).thenReturn(Optional.empty());
```

Only add this stub in tests that run TASK_ACTION or RESEARCH branches and have strict Mockito stubbing enabled.

- [ ] **Step 8: 运行 Task 3 局部测试**

Run:

```bash
mvn -pl backend "-Dtest=ConversationServiceTest" test
```

Expected: PASS, all existing ConversationService tests plus new P3-3 tests pass.

- [ ] **Step 9: 记录 Task 3 进度**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task3-progress.md`:

```markdown
# P3-3 Task 3 Progress - 2026-06-24

当前阶段：P3-3 Task 3 已完成 ConversationService 决策上下文接入，准备进入 Task 4 HTTP 契约与前端展示。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [ ] 质检复核：待执行

## 已完成内容

1. `ConversationService` 已读取最近 `OrchestrationDecision` 只读视图。
2. TASK_ACTION 与 RESEARCH 预览已能消费编排决策上下文。
3. `IntentDecision.decisionPayload` 可回放 `orchestrationDecision` 摘要。
4. 人工介入决策不会被误转成可确认执行对象。

## 验证结果

`mvn -pl backend "-Dtest=ConversationServiceTest" test` 通过。

## 下一步

执行 Task 4：补 HTTP contract 和前端动作预览卡片展示。
```

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationService.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationServiceTest.java docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task3-progress.md
git commit -m "feat: pass orchestration decisions into conversation previews"
```

---

## Task 4: 控制器契约与前端展示

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/controller/ConversationControllerContractTest.java`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/utils/conversationPresentation.ts`
- Modify: `frontend/src/utils/conversationPresentation.test.ts`
- Modify: `frontend/src/components/conversation/TaskActionPreviewCard.tsx`
- Create: `frontend/src/components/conversation/TaskActionPreviewCard.test.tsx`
- Modify: `frontend/src/pages/ConversationPage.test.tsx`

- [ ] **Step 1: 写控制器契约红灯测试**

Append to `ConversationControllerContractTest`:

```java
@Test
void shouldReturnOrchestrationDecisionSummaryInActionPreview() throws Exception {
    when(conversationService.handleMessage(any())).thenReturn(ConversationResponse.builder()
            .sessionId(4050L)
            .mode("TASK_ACTION")
            .answer("我先展示 Orchestrator 的人工介入建议。")
            .sourceUrls(List.of())
            .intentDecision(ConversationResponse.IntentDecisionSummary.builder()
                    .decisionId(4051L)
                    .mode("TASK_ACTION")
                    .intentType("RESUME_TASK")
                    .decisionReason("用户询问下一步动作")
                    .highRiskAction(true)
                    .requiresConfirmation(false)
                    .riskLevel("HIGH")
                    .impactScope("TASK_EXECUTION")
                    .build())
            .taskActionPreview(ConversationResponse.TaskActionPreview.builder()
                    .actionType("WAIT_FOR_HUMAN")
                    .taskId(405L)
                    .targetNodeName("analyze_competitors")
                    .title("来自 Orchestrator 的人工介入建议")
                    .actionSummary("Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。")
                    .riskLevel("HIGH")
                    .requiresConfirmation(false)
                    .executable(false)
                    .orchestrationDecision(ConversationResponse.OrchestrationDecisionSummary.builder()
                            .decisionId("od-405-analyze_competitors-human")
                            .triggerNodeName("analyze_competitors")
                            .decisionType("WAIT_FOR_HUMAN")
                            .actionType("MANUAL_REVIEW")
                            .targetNode("analyze_competitors")
                            .affectedScope("CURRENT_NODE_ONLY")
                            .reason("Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。")
                            .evidenceState("MISSING_SOURCE")
                            .sourceUrls(List.of())
                            .build())
                    .sourceUrls(List.of())
                    .build())
            .build());

    mockMvc.perform(post("/api/conversation/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "taskId": 405,
                              "pageType": "TASK_DETAIL",
                              "message": "系统建议我下一步做什么？"
                            }
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.taskActionPreview.actionType").value("WAIT_FOR_HUMAN"))
            .andExpect(jsonPath("$.data.taskActionPreview.orchestrationDecision.decisionId")
                    .value("od-405-analyze_competitors-human"))
            .andExpect(jsonPath("$.data.taskActionPreview.orchestrationDecision.evidenceState")
                    .value("MISSING_SOURCE"))
            .andExpect(jsonPath("$.data.intentDecision.requiresConfirmation").value(false));
}
```

Add imports:

```java
import java.util.List;
```

Run: `mvn -pl backend "-Dtest=ConversationControllerContractTest#shouldReturnOrchestrationDecisionSummaryInActionPreview" test`

Expected: PASS after Task 2 DTO changes; FAIL if JSON contract is missing.

- [ ] **Step 2: 扩展前端类型**

In `frontend/src/types/index.ts`, add:

```ts
export interface ConversationOrchestrationDecisionSummary {
  decisionId?: string | null
  triggerNodeName?: string | null
  decisionType?: string | null
  actionType?: string | null
  targetNode?: string | null
  affectedScope?: string | null
  reason?: string | null
  evidenceState?: string | null
  sourceUrls?: string[]
}
```

Add to `ConversationActionConfirmationRequest`:

```ts
orchestrationDecisionId?: string | null
orchestrationDecisionType?: string | null
orchestrationEvidenceState?: string | null
```

Add to `ConversationTaskActionPreview`:

```ts
orchestrationDecision?: ConversationOrchestrationDecisionSummary | null
```

- [ ] **Step 3: 扩展展示文案工具**

In `frontend/src/utils/conversationPresentation.ts`, add:

```ts
export function getConversationEvidenceStateText(evidenceState?: string | null) {
  if (evidenceState === 'FULL_SOURCE') return '来源充分'
  if (evidenceState === 'PARTIAL_SOURCE') return '来源部分覆盖'
  if (evidenceState === 'MISSING_SOURCE') return '缺少可回指来源'
  if (evidenceState === 'LOW_QUALITY_EVIDENCE') return '来源质量不足'
  return evidenceState || '证据状态待确认'
}

export function getConversationOrchestrationSummary(
  preview?: ConversationTaskActionPreview | null,
) {
  const decision = preview?.orchestrationDecision
  if (!decision) {
    return null
  }
  const trigger = decision.triggerNodeName || '当前节点'
  const reason = decision.reason || 'Orchestrator 已记录编排决策。'
  return `来自 ${trigger} 的 Orchestrator 决策：${reason}`
}
```

Update import list to include `ConversationTaskActionPreview` if needed.

- [ ] **Step 4: 写 presentation 工具测试**

Append to `frontend/src/utils/conversationPresentation.test.ts`:

```ts
import {
  getConversationEvidenceStateText,
  getConversationOrchestrationSummary,
} from './conversationPresentation'

it('formats conversation orchestration evidence state and summary', () => {
  expect(getConversationEvidenceStateText('MISSING_SOURCE')).toBe('缺少可回指来源')
  expect(
    getConversationOrchestrationSummary({
      orchestrationDecision: {
        decisionId: 'od-1',
        triggerNodeName: 'analyze_competitors',
        reason: 'Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。',
        evidenceState: 'MISSING_SOURCE',
        sourceUrls: [],
      },
    }),
  ).toContain('analyze_competitors')
})
```

Run: `npm --prefix frontend test -- conversationPresentation.test.ts`

Expected: PASS.

- [ ] **Step 5: 更新 TaskActionPreviewCard 展示**

In `TaskActionPreviewCard.tsx`, update imports:

```ts
  getConversationEvidenceStateText,
  getConversationOrchestrationSummary,
```

Add after `confirmationMessage`:

```ts
const orchestrationSummary = getConversationOrchestrationSummary(preview)
const orchestrationDecision = preview.orchestrationDecision
```

Add inside the card before the warning alert:

```tsx
{orchestrationDecision && (
  <Descriptions size="small" column={1} bordered>
    {orchestrationSummary && (
      <Descriptions.Item label="编排决策">{orchestrationSummary}</Descriptions.Item>
    )}
    {orchestrationDecision.decisionType && (
      <Descriptions.Item label="决策类型">{orchestrationDecision.decisionType}</Descriptions.Item>
    )}
    {orchestrationDecision.evidenceState && (
      <Descriptions.Item label="证据状态">
        {getConversationEvidenceStateText(orchestrationDecision.evidenceState)}
      </Descriptions.Item>
    )}
  </Descriptions>
)}
```

- [ ] **Step 6: 新增 TaskActionPreviewCard 前端测试**

Create `frontend/src/components/conversation/TaskActionPreviewCard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import TaskActionPreviewCard from './TaskActionPreviewCard'

describe('TaskActionPreviewCard', () => {
  it('renders orchestration decision summary and evidence state', () => {
    render(
      <TaskActionPreviewCard
        preview={{
          actionType: 'WAIT_FOR_HUMAN',
          taskId: 405,
          targetNodeName: 'analyze_competitors',
          title: '来自 Orchestrator 的人工介入建议',
          actionSummary: 'Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。',
          impactSummary: '本次对话只展示原因，不会直接提交任务控制。',
          riskLevel: 'HIGH',
          requiresConfirmation: false,
          executable: false,
          sourceUrls: [],
          orchestrationDecision: {
            decisionId: 'od-405-analyze_competitors-human',
            triggerNodeName: 'analyze_competitors',
            decisionType: 'WAIT_FOR_HUMAN',
            actionType: 'MANUAL_REVIEW',
            targetNode: 'analyze_competitors',
            affectedScope: 'CURRENT_NODE_ONLY',
            reason: 'Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。',
            evidenceState: 'MISSING_SOURCE',
            sourceUrls: [],
          },
        }}
        confirmationRequest={null}
        onConfirm={vi.fn()}
      />,
    )

    expect(screen.getByText('来自 Orchestrator 的人工介入建议')).toBeInTheDocument()
    expect(screen.getByText(/来自 analyze_competitors 的 Orchestrator 决策/)).toBeInTheDocument()
    expect(screen.getByText('WAIT_FOR_HUMAN')).toBeInTheDocument()
    expect(screen.getByText('缺少可回指来源')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认执行这个动作' })).not.toBeInTheDocument()
  })
})
```

Run: `npm --prefix frontend test -- TaskActionPreviewCard.test.tsx`

Expected: PASS.

- [ ] **Step 7: 更新 ConversationPage 测试**

In `ConversationPage.test.tsx`, update one action preview fixture to include:

```ts
orchestrationDecision: {
  decisionId: 'od-42-extract_schema-suggestion-1',
  triggerNodeName: 'extract_schema',
  decisionType: 'APPEND_DYNAMIC_BRANCH',
  actionType: 'SUPPLEMENT_EVIDENCE',
  targetNode: 'collect_sources_web',
  affectedScope: 'CURRENT_NODE_AND_DOWNSTREAM',
  reason: 'extract_schema 发现安全字段缺少官方来源。',
  evidenceState: 'PARTIAL_SOURCE',
  sourceUrls: ['https://docs.notion.so/security'],
},
```

Add assertions:

```ts
expect(screen.getByText(/来自 extract_schema 的 Orchestrator 决策/)).toBeInTheDocument()
expect(screen.getByText('来源部分覆盖')).toBeInTheDocument()
```

Run: `npm --prefix frontend test -- ConversationPage.test.tsx`

Expected: PASS.

- [ ] **Step 8: 运行 Task 4 聚合测试**

Run:

```bash
mvn -pl backend "-Dtest=ConversationControllerContractTest" test
npm --prefix frontend test -- conversationPresentation.test.ts TaskActionPreviewCard.test.tsx ConversationPage.test.tsx
```

Expected: PASS.

- [ ] **Step 9: 记录 Task 4 进度**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task4-progress.md`:

```markdown
# P3-3 Task 4 Progress - 2026-06-24

当前阶段：P3-3 Task 4 已完成 HTTP 契约与前端动作预览展示，准备进入 Task 5 聚合验证。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [ ] 质检复核：待执行

## 已完成内容

1. HTTP contract 已覆盖 `taskActionPreview.orchestrationDecision`。
2. 前端类型已支持编排决策摘要和确认对象审计字段。
3. `TaskActionPreviewCard` 已展示 Orchestrator 决策、证据状态和来源。
4. 人工介入预览不会显示确认执行按钮。

## 验证结果

- `mvn -pl backend "-Dtest=ConversationControllerContractTest" test` 通过。
- `npm --prefix frontend test -- conversationPresentation.test.ts TaskActionPreviewCard.test.tsx ConversationPage.test.tsx` 通过。

## 下一步

执行 Task 5：运行 P3-3 局部聚合、P1/P2/P3 聚合与前后端回归。
```

- [ ] **Step 10: Commit**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/controller/ConversationControllerContractTest.java frontend/src/types/index.ts frontend/src/utils/conversationPresentation.ts frontend/src/utils/conversationPresentation.test.ts frontend/src/components/conversation/TaskActionPreviewCard.tsx frontend/src/components/conversation/TaskActionPreviewCard.test.tsx frontend/src/pages/ConversationPage.test.tsx docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task4-progress.md
git commit -m "feat: show orchestration decisions in conversation preview"
```

---

## Task 5: 聚合验证、回归和 smoke 建议

**Files:**
- Test only: backend and frontend verification commands
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-3-conversation-decision-preview-implementation-plan.md`
- Create: `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task5-progress.md`

- [x] **Step 1: 运行 P3-3 后端局部聚合**

Run:

```bash
mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationSafetyPolicyTest,ConversationServiceTest,ConversationControllerContractTest" test
```

Expected: PASS, 0 failures.

- [x] **Step 2: 运行 P1+P2+P3-1+P3-2+P3-3 编排聚合**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest,ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationServiceTest,ConversationControllerContractTest" test
```

Expected: PASS, 0 failures.

- [x] **Step 3: 运行前端局部聚合**

Run:

```bash
npm --prefix frontend test -- conversationPresentation.test.ts TaskActionPreviewCard.test.tsx ConversationPage.test.tsx
```

Expected: PASS.

- [x] **Step 4: 运行后端全量回归**

Run:

```bash
mvn -pl backend test
```

Expected: PASS.

- [x] **Step 5: 运行前端构建**

Run:

```bash
npm --prefix frontend run build
```

Expected: PASS.

- [x] **Step 6: 运行 diff 检查**

Run:

```bash
git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionView.java backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ConversationActionConfirmationRequest.java backend/src/main/java/cn/bugstack/competitoragent/conversation/TaskActionTranslator.java backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicy.java backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationService.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationSafetyPolicyTest.java backend/src/test/java/cn/bugstack/competitoragent/conversation/TaskActionTranslatorTest.java backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/controller/ConversationControllerContractTest.java frontend/src/types/index.ts frontend/src/utils/conversationPresentation.ts frontend/src/utils/conversationPresentation.test.ts frontend/src/components/conversation/TaskActionPreviewCard.tsx frontend/src/components/conversation/TaskActionPreviewCard.test.tsx frontend/src/pages/ConversationPage.test.tsx
```

Expected: exit code 0; LF/CRLF warnings are acceptable, whitespace errors are not.

- [x] **Step 7: 记录 Task 5 进度**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task5-progress.md`:

```markdown
# P3-3 Task 5 Progress - 2026-06-24

当前阶段：P3-3 已完成局部聚合、编排聚合、前端验证与回归，准备进入文档回链。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

## 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationSafetyPolicyTest,ConversationServiceTest,ConversationControllerContractTest" test` | PASS |
| `mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest,ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationServiceTest,ConversationControllerContractTest" test` | PASS |
| `npm --prefix frontend test -- conversationPresentation.test.ts TaskActionPreviewCard.test.tsx ConversationPage.test.tsx` | PASS |
| `mvn -pl backend test` | PASS |
| `npm --prefix frontend run build` | PASS |
| `git diff --check -- ...` | PASS |

## 下一步

执行 Task 6：回写总蓝图、3.4 架构规格、稳定演示计划和本执行计划结果。
```

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task5-progress.md
git commit -m "test: verify p3-3 conversation decision preview"
```

---

## Task 6: 文档回链与进度持久化

**Files:**
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-3-conversation-decision-preview-implementation-plan.md`
- Create: `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task6-progress.md`

- [x] **Step 1: 更新总蓝图 3.4 状态**

In `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`, under `3.4 Agent 协作编排层`, append:

```markdown
- P3-3 实施：`✅` Conversation 动作预览已读取最近 `OrchestrationDecision`；统一对话入口在 TASK_ACTION / RESEARCH 场景中能展示 Orchestrator 决策 ID、触发节点、决策类型、动作类型、原因、`evidenceState` 和 `sourceUrls`，并保持人工介入场景不可直接确认执行。对话入口仍不生成新编排决策，也不绕过 `DecisionPolicyService / TaskRuntimeFacade`。
```

- [x] **Step 2: 更新 3.4 架构规格实现记录**

In `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`, append near implementation records:

```markdown
2026-06-24 P3-3 自动化实现记录：Conversation 动作预览已接入最近一次 `ORCHESTRATION_DECISION_RECORDED` 事件，只读提取 `OrchestrationDecision` 摘要并展示到统一对话入口；`TaskActionTranslator` 已支持 `SUPPLEMENT_EVIDENCE / REWRITE_ONLY / WAIT_FOR_HUMAN` 三类决策预览映射，`WAIT_FOR_HUMAN` 不生成确认执行对象；前端 `TaskActionPreviewCard` 已展示 Orchestrator 决策原因、证据状态和来源链接。Conversation 仍不创建编排决策，不直接执行人工介入动作，Citation Agent 仍留在 P3-4。
```

- [x] **Step 3: 更新稳定演示计划**

In `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`, update current stage:

```markdown
当前阶段：3.4 P3-3 已把 Conversation 动作预览接入 Orchestrator 决策摘要；稳定演示版可从统一对话入口解释最近编排决策、证据状态、来源链接和人工确认边界。
```

Add checklist item:

```markdown
- [x] Conversation 动作预览能展示最近 `OrchestrationDecision` 的原因、`evidenceState` 和 `sourceUrls`，且人工介入决策不会被误执行。
```

- [x] **Step 4: 更新本计划执行进度**

Append to the end of this plan:

```markdown
## 2026-06-24 执行进度

当前阶段：P3-3 Conversation 动作预览读取 OrchestrationDecision 已完成自动化实现、前端展示、聚合验证与文档回链。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

- [x] Task 1：Conversation 编排决策只读视图
- [x] Task 2：后端 DTO 与动作预览翻译器
- [x] Task 3：ConversationService 决策上下文接入
- [x] Task 4：HTTP 契约与前端展示
- [x] Task 5：聚合验证、回归和 smoke 建议
- [x] Task 6：文档回链与进度持久化

## 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -pl backend "-Dtest=ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationSafetyPolicyTest,ConversationServiceTest,ConversationControllerContractTest" test` | PASS |
| `mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest,ConversationOrchestrationDecisionQueryServiceTest,TaskActionTranslatorTest,ConversationServiceTest,ConversationControllerContractTest" test` | PASS |
| `npm --prefix frontend test -- conversationPresentation.test.ts TaskActionPreviewCard.test.tsx ConversationPage.test.tsx` | PASS |
| `mvn -pl backend test` | PASS |
| `npm --prefix frontend run build` | PASS |
| `git diff --check -- ...` | PASS |
```

- [x] **Step 5: 创建 Task 6 progress**

Create `docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task6-progress.md`:

```markdown
# P3-3 Task 6 Progress - 2026-06-24

当前阶段：P3-3 已完成文档回链与进度持久化。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

## 已完成内容

1. 总蓝图已记录 P3-3 Conversation 动作预览读取 `OrchestrationDecision`。
2. 3.4 架构规格已追加 P3-3 自动化实现记录。
3. 稳定演示计划已更新当前阶段和检查清单。
4. P3-3 计划文档已写入最终执行进度和验证结果。

## 剩余未做

1. P3-3 范围内无剩余开发任务。
2. 后续阶段：P3-4 Citation Agent 引用核查与来源可信度验证。
```

- [x] **Step 6: 运行文档 diff 检查**

Run:

```bash
git diff --check -- docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-3-conversation-decision-preview-implementation-plan.md docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task6-progress.md
```

Expected: exit code 0; no whitespace errors.

- [ ] **Step 7: Commit**

```bash
git add docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-3-conversation-decision-preview-implementation-plan.md docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-3-task6-progress.md
git commit -m "docs: record p3-3 conversation decision preview"
```

---

## P3-3 Live Smoke Suggestion

自动化通过后再执行真实 smoke。本轮 smoke 不要求生成新的 Orchestrator 决策，只验证统一对话入口能读取已存在的最近决策并解释给用户。

| 样本 | 目的 | 预期 |
| --- | --- | --- |
| Analyzer `WAITING_INTERVENTION` 样本 | 验证 `WAIT_FOR_HUMAN / MISSING_SOURCE` 不出现确认执行按钮 | 对话回答展示 `WAIT_FOR_HUMAN`、`MISSING_SOURCE`、触发节点和原因 |
| Writer `REWRITE_ONLY` 样本 | 验证 Writer 引用缺口能映射为重写预览 | 对话回答展示 `REWRITE_ONLY / REWRITE_SECTION`，确认对象为 `RERUN_NODE`，目标 `rewrite_report` |
| Extractor `APPEND_DYNAMIC_BRANCH` 样本 | 验证补证决策能映射为补证预览 | 对话回答展示 `SUPPLEMENT_EVIDENCE`，来源链接保留 |

推荐对话请求：

```json
{
  "taskId": 58,
  "pageType": "TASK_DETAIL",
  "message": "系统建议我下一步做什么？"
}
```

验收检查：

1. 响应 `taskActionPreview.orchestrationDecision.decisionId` 不为空。
2. 响应 `taskActionPreview.orchestrationDecision.evidenceState` 与最近 replay 决策一致。
3. 响应 `sourceUrls` 与 `taskActionPreview.sourceUrls` 保留可回指来源；无来源时必须显式 `MISSING_SOURCE`。
4. 若决策为 `WAIT_FOR_HUMAN`，`intentDecision.confirmationRequest` 为空，前端不显示“确认执行这个动作”按钮。
5. 若决策为 `REWRITE_ONLY` 或 `APPEND_DYNAMIC_BRANCH`，确认对象仍走统一对话入口，不直接绕过 `TaskRuntimeFacade`。

---

## Plan Self-Review

### Spec Coverage

1. 架构规格 P3 第 3 项 “Conversation 的动作预览读取 OrchestrationDecision”：Task 1-4 覆盖。
2. `sourceUrls / evidenceState` 红线：Task 1 QueryService、Task 2 DTO、Task 3 service 审计、Task 4 UI 全部保留。
3. 不把对话入口变成自治执行入口：Scope Guard、Task 2 SafetyPolicy、Task 3 Service 边界覆盖。
4. P1/P2/P3-1/P3-2 不回归：Task 5 编排聚合命令覆盖。
5. 前端可见性：Task 4 覆盖类型、文案、卡片和页面测试。
6. 文档回链：Task 6 覆盖总蓝图、架构规格、稳定演示计划和本计划进度。

### Placeholder Scan

本文没有未定标记、未命名文件、未指定测试命令或未定义动作类型。执行结果只允许在 Task 5/Task 6 完成后写入实际 PASS/FAIL。

### Type Consistency

1. 对话只读视图统一命名为 `ConversationOrchestrationDecisionView`。
2. 对话响应摘要统一命名为 `ConversationResponse.OrchestrationDecisionSummary`。
3. 预览字段统一为 `taskActionPreview.orchestrationDecision`。
4. 确认对象审计字段统一为 `orchestrationDecisionId / orchestrationDecisionType / orchestrationEvidenceState`。
5. 人工介入预览动作统一为 `WAIT_FOR_HUMAN`，不进入 `buildExecutionPlan` 的可执行动作集合。
6. 可执行确认动作仍只使用既有 `RERUN_NODE / SUPPLEMENT_EVIDENCE / RESUME_TASK`。

---

## 2026-06-24 Plan Writing Progress

当前阶段：P3-3 具体执行计划已完成初稿，待执行实现。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [ ] 质检复核：待执行

- [x] 读取 P2 最新 dev smoke、P3-2 最新进度、3.4 架构规格、总蓝图和稳定演示计划
- [x] 确认 P3-3 范围：Conversation 动作预览读取最近 `OrchestrationDecision`，不做 Citation Agent，不新增自治执行入口
- [x] 梳理现有 `ConversationService / TaskActionTranslator / ConversationSafetyPolicy / TaskActionPreviewCard` 边界
- [x] 写入 P3-3 具体执行计划
- [ ] 执行前自检：由执行者在开始 Task 1 前再次确认工作区未覆盖他人未提交改动
