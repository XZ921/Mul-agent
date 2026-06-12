# Task Definition And Orchestration Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让任务创建、计划预览、工作流落库、重跑与恢复共用同一套正式的任务定义与执行计划契约，并显式分离预览态与运行态响应。

**Architecture:** 以 `TaskDraft -> TaskDefinition -> ExecutionPlanDefinition -> WorkflowPlan` 为主链路，把 `CreateTaskRequest`、`WorkflowFactory`、`TaskPlan`、`TaskNodeResponse` 之间当前松散的 JSON 传递收口为正式对象。预览接口改为返回独立 `TaskPlanPreviewResponse`，运行态继续使用 `TaskResponse` / `TaskNodeResponse`；`WorkflowPlan` 快照同步保存业务阶段语义，保证 rerun / resume 沿用同一计划版本而不是重新猜测计划。

**Tech Stack:** Java 17, Spring Boot, Spring MVC, Spring Data JPA, Jackson, Lombok, JUnit 5, Mockito, React 18, TypeScript, Vite, Vitest

---

## File Structure

**Backend - Create**

- `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDraft.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinition.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/definition/ExecutionPlanDefinition.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinitionMapper.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinitionValidator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewLaneResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewStageResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewNodeResponse.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CreateTaskRequest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskNodeResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/controller/TaskController.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanValidator.java`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanValidatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/TaskPlanVersionerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase1WorkflowIntegrationTest.java`

**Frontend - Modify**

- `frontend/src/api/client.ts`
- `frontend/src/types/index.ts`
- `frontend/src/pages/TaskCreatePage.tsx`
- `frontend/src/utils/taskNodeInsights.ts`
- `frontend/src/pages/TaskCreatePage.test.tsx`
- `frontend/src/utils/taskNodeInsights.test.ts`

---

### Task 1: 锁定预览契约与运行态分离的红灯测试

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`
- Modify: `frontend/src/pages/TaskCreatePage.test.tsx`
- Modify: `frontend/src/utils/taskNodeInsights.test.ts`

- [ ] **Step 1: 编写任务定义契约红灯测试**

```java
package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskDefinitionContractTest {

    @Test
    void shouldExposeBusinessPlanSemanticsInPreviewResponse() {
        TaskPlanPreviewResponse response = TaskPlanPreviewResponse.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal("围绕企业级 RAG 平台展开竞品研究")
                .competitorCount(1)
                .collectorCount(1)
                .pipelineCount(4)
                .stages(List.of())
                .nodes(List.of(TaskPlanPreviewNodeResponse.builder()
                        .nodeName("collect_sources_01_01")
                        .stageCode("SOURCE_STRATEGY")
                        .goal("优先覆盖官网与产品文档，必要时再补充公网搜索")
                        .fallbackOrder(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"))
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .build();

        assertEquals("TASK_PLAN_PREVIEW_V1", response.getContractType());
        assertEquals("围绕企业级 RAG 平台展开竞品研究", response.getGoal());
        assertEquals(1, response.getCompetitorCount());
        assertEquals(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"),
                response.getNodes().get(0).getFallbackOrder());
        assertNotNull(response.getSourceUrls());
        assertFalse(response.getNodes().isEmpty());
    }
}
```

- [ ] **Step 2: 运行后端契约测试，确认当前实现尚未满足**

Run: `mvn -pl backend -Dtest=TaskDefinitionContractTest test`

Expected: FAIL，报错应集中在 `TaskPlanPreviewResponse` / `TaskPlanPreviewNodeResponse` 不存在，或 `/preview` 仍返回 `List<TaskNodeResponse>`。

- [ ] **Step 3: 扩展应用服务测试，要求 preview 返回正式预览对象而不是运行态节点**

```java
@Test
void shouldReturnFormalPreviewContractInsteadOfRuntimeNodeList() {
    WorkflowPlan previewPlan = WorkflowPlan.builder()
            .contractType("TASK_PLAN_PREVIEW_V1")
            .goal("围绕企业级 RAG 平台展开竞品研究")
            .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                    .nodeName("collect_sources_01_01")
                    .displayName("Notion AI - DOCS采集")
                    .agentType(AgentType.COLLECTOR.name())
                    .dependsOn(List.of())
                    .stageCode("SOURCE_STRATEGY")
                    .goal("优先覆盖官网与产品文档")
                    .fallbackOrder(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"))
                    .nodeConfig("{\"competitorName\":\"Notion AI\",\"sourceType\":\"DOCS\"}")
                    .executionOrder(0)
                    .build()))
            .build();
    when(workflowFactory.buildPreviewPlan(any(AnalysisTask.class))).thenReturn(previewPlan);

    TaskPlanPreviewResponse response = taskDefinitionAppService.previewWorkflow(buildRequest());

    assertEquals("TASK_PLAN_PREVIEW_V1", response.getContractType());
    assertEquals("围绕企业级 RAG 平台展开竞品研究", response.getGoal());
    assertEquals("SOURCE_STRATEGY", response.getNodes().get(0).getStageCode());
    assertEquals(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"),
            response.getNodes().get(0).getFallbackOrder());
}
```

- [ ] **Step 4: 运行应用服务测试，确认红灯**

Run: `mvn -pl backend -Dtest=TaskDefinitionAppServiceTest#shouldReturnFormalPreviewContractInsteadOfRuntimeNodeList test`

Expected: FAIL，当前 `TaskDefinitionAppService.previewWorkflow` 返回 `List<TaskNodeResponse>`。

- [ ] **Step 5: 扩展控制器测试，锁定 `/api/task/preview` 的新 JSON 形状**

```java
@Test
void shouldCreateTaskAndExposeFormalPreviewContract() throws Exception {
    when(taskService.previewWorkflow(any(CreateTaskRequest.class))).thenReturn(TaskPlanPreviewResponse.builder()
            .contractType("TASK_PLAN_PREVIEW_V1")
            .goal("围绕企业级 RAG 平台展开竞品研究")
            .competitorCount(1)
            .collectorCount(1)
            .pipelineCount(4)
            .stages(List.of(TaskPlanPreviewStageResponse.builder()
                    .stageCode("SOURCE_STRATEGY")
                    .title("规划来源策略")
                    .summary("优先覆盖官网、产品文档")
                    .sourceUrls(List.of())
                    .build()))
            .nodes(List.of(TaskPlanPreviewNodeResponse.builder()
                    .nodeName("collect_sources_01_01")
                    .displayName("Notion AI - DOCS采集")
                    .stageCode("SOURCE_STRATEGY")
                    .goal("优先覆盖官网与产品文档")
                    .summary("必要时补充公网搜索")
                    .fallbackOrder(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"))
                    .sourceUrls(List.of())
                    .build()))
            .sourceUrls(List.of())
            .build());

    mockMvc.perform(post("/api/task/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractType").value("TASK_PLAN_PREVIEW_V1"))
            .andExpect(jsonPath("$.data.goal").value("围绕企业级 RAG 平台展开竞品研究"))
            .andExpect(jsonPath("$.data.nodes[0].stageCode").value("SOURCE_STRATEGY"))
            .andExpect(jsonPath("$.data.nodes[0].fallbackOrder[0]").value("PLANNED"));
}
```

- [ ] **Step 6: 运行控制器测试，确认红灯**

Run: `mvn -pl backend -Dtest=TaskControllerTest#shouldCreateTaskAndExposeFormalPreviewContract test`

Expected: FAIL，当前 controller 方法签名仍是 `ApiResponse<List<TaskNodeResponse>>`。

- [ ] **Step 7: 扩展前端测试，锁定创建页开始消费正式预览契约**

```tsx
it('renders preview board from formal preview contract instead of raw runtime node list', async () => {
  vi.useFakeTimers()
  previewWorkflowMock.mockResolvedValue({
    data: {
      contractType: 'TASK_PLAN_PREVIEW_V1',
      goal: '围绕企业级 RAG 平台展开竞品研究',
      competitorCount: 1,
      collectorCount: 1,
      pipelineCount: 4,
      lanes: [],
      stages: [
        {
          key: 'source-strategy',
          title: '规划来源策略',
          summary: '优先覆盖官网、产品文档',
          detail: '不足时才补充公网搜索',
          sourceUrls: [],
        },
      ],
      nodes: [
        {
          nodeName: 'collect_sources_01_01',
          displayName: 'Notion AI - DOCS采集',
          stageCode: 'SOURCE_STRATEGY',
          goal: '优先覆盖官网与产品文档',
          summary: '不足时才补充公网搜索',
          configSummaryData: {
            competitorName: 'Notion AI',
            sourceType: 'DOCS',
            sourceTypeLabel: '产品文档',
            sourceScope: ['官网', '产品文档'],
            competitorUrls: ['https://www.notion.so/product/ai'],
            candidateCount: 4,
            queryCount: 2,
            browserSearchEnabled: true,
            verificationEnabled: true,
            minVerifiedCandidates: 1,
            preferredDomains: ['notion.so'],
            discoveryNotes: '优先官网，不足时再补源',
          },
          fallbackOrder: ['PLANNED', 'BROWSER', 'HEURISTIC', 'HTTP'],
          sourceUrls: [],
        },
      ],
      sourceUrls: [],
    },
  })

  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <TaskCreatePage />
    </MemoryRouter>,
  )

  await flushPageEffects()

  await act(async () => {
    fireEvent.change(screen.getByLabelText('分析主题'), { target: { value: 'AI 知识库竞品分析' } })
    fireEvent.change(screen.getByLabelText('本方产品'), { target: { value: '企业级 RAG 平台' } })
    fireEvent.change(screen.getByLabelText('竞品名称'), { target: { value: 'Notion AI' } })
    await vi.advanceTimersByTimeAsync(450)
  })

  expect(screen.getByText('规划来源策略')).toBeInTheDocument()
  expect(screen.getByText('优先覆盖官网、产品文档')).toBeInTheDocument()
})
```

- [ ] **Step 8: 运行前端测试，确认红灯**

Run: `npm --prefix frontend test -- src/pages/TaskCreatePage.test.tsx src/utils/taskNodeInsights.test.ts`

Expected: FAIL，当前前端 `previewWorkflow` 仍声明为 `TaskNodeInfo[]`，创建页也仍从节点列表反推计划阶段。

- [ ] **Step 9: 提交红灯基线**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionContractTest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java frontend/src/pages/TaskCreatePage.test.tsx frontend/src/utils/taskNodeInsights.test.ts
git commit -m "test(task): lock task definition preview contract"
```

### Task 2: 引入 TaskDraft / TaskDefinition / ExecutionPlanDefinition 正式契约

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDraft.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinition.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/definition/ExecutionPlanDefinition.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinitionMapper.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/definition/TaskDefinitionValidator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CreateTaskRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`

- [ ] **Step 1: 新增任务草稿与正式任务定义对象**

```java
package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 任务草稿只承载“用户刚刚提交的原始意图”，
 * 这里允许字段尚未补齐，但必须保持字段语义稳定，
 * 避免后续规划阶段直接读取 CreateTaskRequest 这种边界输入对象。
 */
@Value
@Builder
public class TaskDraft {
    String taskName;
    String subjectProduct;
    List<String> competitorNames;
    List<String> competitorUrls;
    List<String> analysisDimensions;
    List<String> sourceScope;
    String reportLanguage;
    String reportTemplate;
    Long schemaId;
    List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 正式任务定义是“任务创建链路的第一真相”，
 * 任何预览、计划生成、落库、恢复都只能消费这一层对象，
 * 不能继续各自从 AnalysisTask / CreateTaskRequest 上重新猜字段。
 */
@Value
@Builder(toBuilder = true)
public class TaskDefinition {
    String taskName;
    String subjectProduct;
    List<CompetitorDefinition> competitors;
    List<String> analysisDimensions;
    List<String> sourceScope;
    String reportLanguage;
    String reportTemplate;
    Long schemaId;
    String qualityPolicy;
    String contractVersion;
    List<String> sourceUrls;

    @Value
    @Builder
    public static class CompetitorDefinition {
        String competitorName;
        String officialUrl;
    }
}
```

- [ ] **Step 2: 新增执行计划定义对象，正式承载阶段语义与 fallback 顺序**

```java
package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 执行计划定义是“任务如何运行”的正式业务契约。
 * Collector、Extractor、Writer、Reviewer 的节点只是它的技术投影，
 * 计划预览和运行时都必须沿用这份对象，而不是各自再拼装一遍。
 */
@Value
@Builder(toBuilder = true)
public class ExecutionPlanDefinition {
    String contractType;
    String goal;
    Integer competitorCount;
    Integer collectorCount;
    Integer pipelineCount;
    List<StageDefinition> stages;
    List<NodeDefinition> nodes;
    List<String> sourceUrls;

    @Value
    @Builder
    public static class StageDefinition {
        String stageCode;
        String title;
        String summary;
        String detail;
        List<String> sourceUrls;
    }

    @Value
    @Builder
    public static class NodeDefinition {
        String nodeName;
        String displayName;
        String agentType;
        String stageCode;
        String goal;
        String summary;
        List<String> dependsOn;
        boolean required;
        int executionOrder;
        String nodeConfig;
        List<String> fallbackOrder;
        List<String> sourceUrls;
    }
}
```

- [ ] **Step 3: 新增映射器，把边界输入统一归一化为任务定义**

```java
package cn.bugstack.competitoragent.task.definition;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskDefinitionMapper {

    private final ObjectMapper objectMapper;

    /**
     * 任务创建和计划预览都先走 TaskDraft，
     * 这样“用户输入长什么样”和“系统最终采用什么定义”能被显式区分，
     * 后续再补预算、质量要求、补充资料时也不会污染 controller DTO。
     */
    public TaskDraft toDraft(CreateTaskRequest request) {
        return TaskDraft.builder()
                .taskName(trim(request.getTaskName()))
                .subjectProduct(trim(request.getSubjectProduct()))
                .competitorNames(normalizeList(request.getCompetitorNames()))
                .competitorUrls(normalizeList(request.getCompetitorUrls()))
                .analysisDimensions(normalizeList(request.getAnalysisDimensions()))
                .sourceScope(normalizeList(request.getSourceScope()))
                .reportLanguage(trimOrDefault(request.getReportLanguage(), "中文"))
                .reportTemplate(trimOrDefault(request.getReportTemplate(), "标准版"))
                .schemaId(request.getSchemaId())
                .sourceUrls(List.of())
                .build();
    }

    public TaskDefinition toDefinition(TaskDraft draft) {
        List<TaskDefinition.CompetitorDefinition> competitors = new ArrayList<>();
        for (int index = 0; index < draft.getCompetitorNames().size(); index++) {
            competitors.add(TaskDefinition.CompetitorDefinition.builder()
                    .competitorName(draft.getCompetitorNames().get(index))
                    .officialUrl(index < draft.getCompetitorUrls().size() ? draft.getCompetitorUrls().get(index) : null)
                    .build());
        }
        return TaskDefinition.builder()
                .taskName(draft.getTaskName())
                .subjectProduct(draft.getSubjectProduct())
                .competitors(competitors)
                .analysisDimensions(draft.getAnalysisDimensions())
                .sourceScope(draft.getSourceScope())
                .reportLanguage(draft.getReportLanguage())
                .reportTemplate(draft.getReportTemplate())
                .schemaId(draft.getSchemaId())
                .qualityPolicy("score>=80 and no ERROR issues")
                .contractVersion("TASK_DEFINITION_V1")
                .sourceUrls(draft.getSourceUrls())
                .build();
    }
}
```

- [ ] **Step 4: 新增校验器，在任务定义阶段 fail fast**

```java
package cn.bugstack.competitoragent.task.definition;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import org.springframework.stereotype.Component;

@Component
public class TaskDefinitionValidator {

    /**
     * 第一阶段先把“缺少关键业务字段”拦在定义层，
     * 避免进入 WorkflowFactory 后才因为 null / 空集合触发晚期失败。
     */
    public void validate(TaskDefinition definition) {
        if (definition == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "taskDefinition is required");
        }
        if (isBlank(definition.getTaskName())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "taskName is required");
        }
        if (isBlank(definition.getSubjectProduct())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "subjectProduct is required");
        }
        if (definition.getCompetitors() == null || definition.getCompetitors().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "at least one competitor is required");
        }
        if (definition.getAnalysisDimensions() == null || definition.getAnalysisDimensions().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "analysisDimensions is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 5: 修改 TaskDefinitionAppService，让 create / preview 统一经过 mapper + validator**

```java
private final TaskDefinitionMapper taskDefinitionMapper;
private final TaskDefinitionValidator taskDefinitionValidator;

public TaskPlanPreviewResponse previewWorkflow(CreateTaskRequest request) {
    TaskDraft draft = taskDefinitionMapper.toDraft(request);
    TaskDefinition definition = taskDefinitionMapper.toDefinition(draft);
    taskDefinitionValidator.validate(definition);

    WorkflowPlan previewPlan = workflowFactory.buildPreviewPlan(definition);
    return taskPlanPreviewAssembler.toPreviewResponse(definition, previewPlan);
}

@Transactional
public TaskResponse createTask(CreateTaskRequest request) {
    ensureTaskCreationAllowed(request);
    TaskDraft draft = taskDefinitionMapper.toDraft(request);
    TaskDefinition definition = taskDefinitionMapper.toDefinition(draft);
    taskDefinitionValidator.validate(definition);

    AnalysisTask task = AnalysisTask.builder()
            .taskName(definition.getTaskName())
            .subjectProduct(definition.getSubjectProduct())
            .competitorNames(toJson(definition.getCompetitors().stream().map(TaskDefinition.CompetitorDefinition::getCompetitorName).toList()))
            .competitorUrls(toJson(definition.getCompetitors().stream().map(TaskDefinition.CompetitorDefinition::getOfficialUrl).toList()))
            .analysisDimensions(toJson(definition.getAnalysisDimensions()))
            .sourceScope(toJson(definition.getSourceScope()))
            .reportLanguage(definition.getReportLanguage())
            .reportTemplate(definition.getReportTemplate())
            .schemaId(definition.getSchemaId())
            .status(AnalysisTaskStatus.PENDING)
            .build();
    // 后续 createWorkflow 只接收正式任务定义，不再直接消费松散 AnalysisTask 字段。
    taskQuotaCoordinator.markTaskQuotaReserved(task);
    task = taskRepository.save(task);
    workflowFactory.createWorkflow(task, definition);
    task = taskRepository.save(task);
    refreshTaskSnapshot(task.getId());
    workflowEventPublisher.publishTaskCreated(task);
    return toTaskResponse(task);
}
```

- [ ] **Step 6: 运行任务定义相关测试，确认绿灯**

Run: `mvn -pl backend -Dtest=TaskDefinitionContractTest,TaskDefinitionAppServiceTest,AnalysisTaskServiceTest test`

Expected: PASS

- [ ] **Step 7: 提交任务定义契约层**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/task/definition backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/CreateTaskRequest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionContractTest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java
git commit -m "feat(task): introduce formal task definition contract"
```

### Task 3: 拆分 WorkflowFactory，并把搜索计划语义上提为正式计划对象

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanAssembler.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowFactory.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlan.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanValidator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanValidatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/TaskPlanVersionerTest.java`

- [ ] **Step 1: 新增 CollectorPlanTemplateFactory，把 fallback order / search stage / search step 统一收口**

```java
package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourcePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collector 计划模板工厂只负责“搜索与采集计划语义”，
 * 让 fallback 顺序、步骤定义、恢复提示这类业务规则不再散落在 WorkflowFactory 内部私有方法里。
 */
@Component
@RequiredArgsConstructor
public class CollectorPlanTemplateFactory {

    public CollectorNodeConfig createCollectorNodeConfig(String competitorName,
                                                         List<String> requestedScopes,
                                                         String schemaName,
                                                         SourcePlan sourcePlan,
                                                         String searchMode,
                                                         SearchRuntimePolicy runtimePolicy,
                                                         List<String> searchQueries,
                                                         List<String> fallbackOrder) {
        SearchExecutionPlan executionPlan = SearchExecutionPlan.builder()
                .stage("COLLECTOR_SEARCH_AND_COLLECT")
                .searchQueries(searchQueries)
                .fallbackOrder(fallbackOrder)
                .targetCount(resolveTargetCount(sourcePlan))
                .minVerifiedCount(resolveMinVerifiedCount(sourcePlan))
                .steps(List.of(
                        step("LOAD_CANDIDATES", "读取规划期候选来源", 500, "nodeConfig"),
                        step("VERIFY_TOP_CANDIDATES", "验证高优先级候选来源是否可用", 5000, "browser"),
                        step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行浏览器补充搜索", 8000, "searchEngine"),
                        step("SELECT_TARGETS", "合并候选并选出最终采集目标", 1000, "ranker"),
                        step("COLLECT_PAGES", "抓取页面正文并持久化证据", 12000, "collector")
                ))
                .build();

        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .competitorUrls(sourcePlan.getUrls())
                .sourceType(sourcePlan.getSourceType())
                .sourceScope(requestedScopes)
                .schemaName(schemaName)
                .discoveryNotes(sourcePlan.getNotes())
                .sourceCandidates(sourcePlan.getCandidates())
                .searchMode(searchMode)
                .searchQueries(searchQueries)
                .searchFallbackOrder(fallbackOrder)
                .verifyCandidates(Boolean.TRUE)
                .searchRuntimePolicy(runtimePolicy)
                .searchExecutionPlan(executionPlan)
                .build();
    }

    private SearchExecutionStep step(String code, String goal, long expectedDurationMs, String dependency) {
        return SearchExecutionStep.builder()
                .stepCode(code)
                .goal(goal)
                .expectedDurationMs(expectedDurationMs)
                .dependency(dependency)
                .status(SearchExecutionStep.StepStatus.PENDING)
                .build();
    }
}
```

Implementation note: 第一阶段里 `fallbackOrder` 的实际来源仍暂时保留在 `WorkflowFactory.buildSearchFallbackOrder()`，`WorkflowFactory` 只负责把它显式传入 `CollectorPlanTemplateFactory.createCollectorNodeConfig`，不再由 Collector 模板工厂自行猜测来源。把这段搜索策略判断彻底抽成独立 `SearchPolicyResolver` 留到第二阶段处理，并在 handoff 中明确记录这个遗留耦合点。

- [ ] **Step 2: 新增 ExecutionPlanDefinitionBuilder，把业务阶段与技术节点统一生成**

```java
package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import cn.bugstack.competitoragent.task.definition.TaskDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExecutionPlanDefinitionBuilder {

    /**
     * 这里先生成正式业务计划，再交给 WorkflowPlanAssembler 做技术投影。
     * 这样预览和落库都从同一份 ExecutionPlanDefinition 出发，不会再出现“预览是假计划、运行才是真计划”。
     */
    public ExecutionPlanDefinition build(TaskDefinition definition,
                                         List<ExecutionPlanDefinition.StageDefinition> stages,
                                         List<ExecutionPlanDefinition.NodeDefinition> nodes) {
        int collectorCount = (int) nodes.stream().filter(node -> "COLLECTOR".equals(node.getAgentType())).count();
        int pipelineCount = nodes.size() - collectorCount;
        return ExecutionPlanDefinition.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal("围绕 " + definition.getSubjectProduct() + " 展开竞品研究")
                .competitorCount(definition.getCompetitors().size())
                .collectorCount(collectorCount)
                .pipelineCount(pipelineCount)
                .stages(new ArrayList<>(stages))
                .nodes(new ArrayList<>(nodes))
                .sourceUrls(List.of())
                .build();
    }
}
```

- [ ] **Step 3: 扩展 WorkflowPlan，让业务阶段语义进入计划快照**

```java
@Builder.Default
private String contractType = "TASK_PLAN_PREVIEW_V1";

private String goal;

@Builder.Default
private List<WorkflowPlanStage> stages = new ArrayList<>();

public boolean hasFormalStageContract() {
    return StringUtils.hasText(contractType) && stages != null && !stages.isEmpty();
}

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public static class WorkflowPlanStage {
    private String stageCode;
    private String title;
    private String summary;
    private String detail;
    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();
}
```

```java
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public static class WorkflowPlanNode {
    private String nodeName;
    private String displayName;
    private String agentType;
    private List<String> dependsOn;
    private boolean required;
    private int executionOrder;
    private String nodeConfig;
    private String notes;
    private String stageCode;
    private String goal;
    private String summary;
    @Builder.Default
    private List<String> fallbackOrder = new ArrayList<>();
    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();
    @Builder.Default
    private boolean allowFailedDependency = false;
    @Builder.Default
    private boolean retryable = true;
    @Builder.Default
    private int maxRetries = 3;
    @Builder.Default
    private String branchKey = "root";
    @Builder.Default
    private boolean dynamicNode = false;
    private String originNodeName;
}
```

Compatibility strategy: `WorkflowPlan` 新增字段采用“新增可选字段 + 默认值兜底”的读兼容方式上线。历史 `TaskPlan.planSnapshot` 反序列化为 `WorkflowPlan` 时，即使没有 `contractType / goal / stages` 也必须继续可读、可 rerun；第一阶段不要求上线前完成离线 backfill，新创建任务开始写入完整新字段即可。

- [ ] **Step 4: 新增 WorkflowPlanAssembler，并重写 WorkflowFactory 为编排器**

```java
public WorkflowPlan buildPreviewPlan(TaskDefinition definition) {
    taskDefinitionValidator.validate(definition);
    ExecutionPlanDefinition executionPlan = executionPlanDefinitionBuilder.build(
            definition,
            buildStages(definition),
            buildNodes(definition, true)
    );
    WorkflowPlan plan = workflowPlanAssembler.fromExecutionPlan(executionPlan);
    workflowPlanValidator.validateForCreation(plan);
    return plan;
}

@Transactional
public List<TaskNode> createWorkflow(AnalysisTask task, TaskDefinition definition) {
    WorkflowPlan previewPlan = buildPreviewPlan(definition);
    TaskPlan initialPlan = dynamicTaskGraphService.ensureInitialPlan(task.getId(), previewPlan);
    WorkflowPlan versionedPlan = enrichWorkflowPlan(previewPlan, initialPlan);
    task.setCurrentPlanVersionId(initialPlan.getId());
    task.setCurrentPlanVersion(initialPlan.getPlanVersion());
    List<TaskNode> nodes = versionedPlan.getNodes().stream()
            .map(planNode -> TaskNode.builder()
                    .taskId(task.getId())
                    .nodeName(planNode.getNodeName())
                    .displayName(planNode.getDisplayName())
                    .agentType(AgentType.valueOf(planNode.getAgentType()))
                    .dependsOn(toJson(planNode.getDependsOn()))
                    .nodeConfig(planNode.getNodeConfig())
                    .nodeNotes(planNode.getNotes())
                    .allowFailedDependency(planNode.isAllowFailedDependency())
                    .required(planNode.isRequired())
                    .retryable(planNode.isRetryable())
                    .maxRetries(planNode.getMaxRetries())
                    .retryCount(0)
                    .status(TaskNodeStatus.PENDING)
                    .executionOrder(planNode.getExecutionOrder())
                    .planVersionId(initialPlan.getId())
                    .branchKey(planNode.getBranchKey())
                    .dynamicNode(planNode.isDynamicNode())
                    .originNodeName(planNode.getOriginNodeName())
                    .build())
            .toList();
    return nodeRepository.saveAll(nodes);
}
```

- [ ] **Step 5: 在 WorkflowPlanValidator 中校验 stage 与 node 的正式对应关系**

```java
public void validateForCreation(WorkflowPlan plan) {
    validateGraphBasics(plan);
    validateStageContract(plan, true);
}

public void validateForSnapshotReuse(WorkflowPlan plan) {
    validateGraphBasics(plan);
    if (plan.hasFormalStageContract()) {
        validateStageContract(plan, false);
    }
}

private void validateStageContract(WorkflowPlan plan, boolean requireStages) {
    if (requireStages && (plan.getStages() == null || plan.getStages().isEmpty())) {
        throw new IllegalArgumentException("workflow stages must not be empty");
    }
    if (plan.getStages() == null || plan.getStages().isEmpty()) {
        return;
    }
    Set<String> stageCodes = plan.getStages().stream()
            .map(WorkflowPlan.WorkflowPlanStage::getStageCode)
            .collect(Collectors.toSet());
    for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
        if (!StringUtils.hasText(node.getStageCode()) || !stageCodes.contains(node.getStageCode())) {
            throw new IllegalArgumentException("workflow node stageCode is missing or unknown: " + node.getNodeName());
        }
    }
}
```

Rollout note: 严格 stage 校验只用于 `preview/create` 新链路；历史快照的 `rerun / resume / dynamic append` 读取路径改用 `validateForSnapshotReuse`，从而避免升级后因为旧 JSON 缺少 `stages` 而直接炸掉。

- [ ] **Step 6: 运行工作流相关测试，确认计划语义已稳定落入快照**

Run: `mvn -pl backend -Dtest=WorkflowFactoryTest,WorkflowPlanValidatorTest,TaskPlanVersionerTest test`

Expected: PASS

- [ ] **Step 7: 提交工作流规划拆分**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/workflow
git commit -m "feat(workflow): formalize execution plan semantics"
```

### Task 4: 落地正式预览 DTO，并让创建页消费新契约

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewResponse.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewLaneResponse.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewStageResponse.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewNodeResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/controller/TaskController.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/pages/TaskCreatePage.tsx`
- Modify: `frontend/src/utils/taskNodeInsights.ts`
- Modify: `frontend/src/pages/TaskCreatePage.test.tsx`
- Modify: `frontend/src/utils/taskNodeInsights.test.ts`

- [ ] **Step 1: 新增预览 DTO，显式声明 preview 不是 runtime**

```java
package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览响应")
public class TaskPlanPreviewResponse {

    @Schema(description = "预览契约版本", example = "TASK_PLAN_PREVIEW_V1")
    private String contractType;

    @Schema(description = "当前任务的正式计划目标")
    private String goal;

    @Schema(description = "竞品数量")
    private Integer competitorCount;

    @Schema(description = "采集节点数量")
    private Integer collectorCount;

    @Schema(description = "后续处理节点数量")
    private Integer pipelineCount;

    @Schema(description = "来源策略泳道摘要；第一阶段允许为空，前端需回退到 preview nodes 聚合")
    private List<TaskPlanPreviewLaneResponse> lanes;

    @Schema(description = "阶段预览")
    private List<TaskPlanPreviewStageResponse> stages;

    @Schema(description = "计划节点预览")
    private List<TaskPlanPreviewNodeResponse> nodes;

    @Schema(description = "可追溯来源", example = "[]")
    private List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "来源策略泳道摘要")
public class TaskPlanPreviewLaneResponse {

    private String competitorName;
    private int branchCount;
    private List<String> sourceLabels;
    private List<String> sourceScope;
    private int entryUrlCount;
    private int candidateCount;
    private int queryCount;
    private boolean browserSupplementEnabled;
    private boolean verificationEnabled;
    private Integer minVerifiedCandidates;
    private List<String> preferredDomains;
    private List<String> notes;
}
```

```java
package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览节点")
public class TaskPlanPreviewNodeResponse {

    private String nodeName;
    private String displayName;
    private String agentType;
    private String stageCode;
    private String goal;
    private String summary;
    private TaskNodeConfigSummary configSummaryData;
    private List<String> dependsOn;
    private boolean required;
    private int executionOrder;
    private List<String> fallbackOrder;
    private List<String> sourceUrls;
}
```

- [ ] **Step 2: 新增预览装配器，彻底移走 `TaskNodeViewAssembler.toPreviewNodeResponse` 的职责**

```java
package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewStageResponse;
import cn.bugstack.competitoragent.task.definition.TaskDefinition;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskPlanPreviewAssembler {

    /**
     * 预览装配器只负责“计划态可消费视图”，
     * 不再混入 status、canPause、checkpoint 这类运行时字段，
     * 从响应层就把 preview / runtime 语义彻底拆开。
     */
    public TaskPlanPreviewResponse toPreviewResponse(TaskDefinition definition, WorkflowPlan plan) {
        return TaskPlanPreviewResponse.builder()
                .contractType(plan.getContractType())
                .goal(plan.getGoal())
                .competitorCount(definition.getCompetitors().size())
                .collectorCount((int) plan.getNodes().stream().filter(node -> "COLLECTOR".equals(node.getAgentType())).count())
                .pipelineCount((int) plan.getNodes().stream().filter(node -> !"COLLECTOR".equals(node.getAgentType())).count())
                .lanes(List.of())
                .stages(plan.getStages().stream()
                        .map(stage -> TaskPlanPreviewStageResponse.builder()
                                .key(stage.getStageCode())
                                .stageCode(stage.getStageCode())
                                .title(stage.getTitle())
                                .summary(stage.getSummary())
                                .detail(stage.getDetail())
                                .sourceUrls(stage.getSourceUrls())
                                .build())
                        .toList())
                .nodes(plan.getNodes().stream()
                        .map(node -> TaskPlanPreviewNodeResponse.builder()
                                .nodeName(node.getNodeName())
                                .displayName(node.getDisplayName())
                                .agentType(node.getAgentType())
                                .stageCode(node.getStageCode())
                                .goal(node.getGoal())
                                .summary(node.getSummary())
                                .configSummaryData(buildPreviewNodeConfigSummaryData(node))
                                .dependsOn(node.getDependsOn())
                                .required(node.isRequired())
                                .executionOrder(node.getExecutionOrder())
                                .fallbackOrder(node.getFallbackOrder())
                                .sourceUrls(node.getSourceUrls())
                                .build())
                        .toList())
                .sourceUrls(plan.getNodes().stream().flatMap(node -> node.getSourceUrls().stream()).distinct().toList())
                .build();
    }
}
```

Implementation note: 第一阶段先把 `lanes` 定义成正式字段，但后端暂不强制组装聚合值；创建页必须优先读取 `preview.lanes`，为空时回退到 `preview.nodes[*].configSummaryData` 做与当前一致的聚合。把“从 `CollectorNodeConfig` 正式投影后端 lanes”的工作明确 deferred 到第二阶段，避免本阶段为了一个首屏摘要把搜索策略解析再次塞回 `WorkflowFactory`。

- [ ] **Step 3: 修改 Controller / Service 签名**

```java
@PostMapping("/preview")
@Operation(summary = "Preview workflow")
public ApiResponse<TaskPlanPreviewResponse> previewWorkflow(@Valid @RequestBody CreateTaskRequest request) {
    return ApiResponse.success(taskService.previewWorkflow(request));
}
```

```java
public TaskPlanPreviewResponse previewWorkflow(CreateTaskRequest request) {
    return taskDefinitionAppService.previewWorkflow(request);
}
```

- [ ] **Step 4: 精简 TaskNodeViewAssembler，只保留运行态组装**

```java
/**
 * 这里不再承担 preview 组装职责。
 * 任务创建页消费的计划预览改由 TaskPlanPreviewAssembler 单独负责，
 * 运行态节点响应仍留在这里，避免 preview/runtime 混用同一 DTO。
 */
public TaskNodeResponse toNodeResponse(AnalysisTask task, TaskNode node, List<TaskNode> allNodes) {
    AnalysisTaskStatus taskStatus = recoveryPolicy().resolveTaskExecution(task, allNodes).getStatus();
    List<TaskNode> affectedNodes = collectAffectedNodes(allNodes, node.getNodeName());
    Map<Long, Integer> planVersionMap = buildPlanVersionMap(task == null ? null : task.getId());
    TaskNodeConfigSummary configSummaryData = buildNodeConfigSummaryData(node);
    return TaskNodeResponse.builder()
            .contractType("TASK_NODE_RUNTIME_V1")
            .id(node.getId())
            .nodeName(node.getNodeName())
            .displayName(node.getDisplayName())
            .nodeConfig(node.getNodeConfig())
            .configSummary(configSummaryData == null ? null : configSummaryData.getSummaryText())
            .configSummaryData(configSummaryData)
            .collectorInsight(buildCollectorNodeInsight(node))
            .agentType(node.getAgentType())
            .dependsOn(node.getDependsOn())
            .required(node.isRequired())
            .status(node.getStatus())
            .executionOrder(node.getExecutionOrder())
            .planVersionId(node.getPlanVersionId())
            .planVersion(resolvePlanVersion(planVersionMap, node.getPlanVersionId()))
            .affectedNodeCount(affectedNodes.size())
            .affectedNodeNames(affectedNodes.stream().map(TaskNode::getNodeName).toList())
            .statusSummary(buildNodeStatusSummary(node))
            .interventionSummary(buildNodeInterventionSummary(
                    node,
                    taskStatus,
                    affectedNodes.stream().map(TaskNode::getNodeName).toList(),
                    hasReusableCheckpoint(node),
                    canPauseNode(node),
                    canResumeNode(node),
                    canSkipNode(node),
                    canTerminate(node)))
            .eventKey(node.getNodeName())
            .build();
}
```

- [ ] **Step 5: 修改前端 API 与类型声明，改为正式预览对象**

```ts
export interface TaskPlanPreviewContract {
  contractType: string
  goal: string
  competitorCount: number
  collectorCount: number
  pipelineCount: number
  lanes: SourceStrategyOverviewInfo['lanes']
  stages: Array<TaskPlanStageInfo & { sourceUrls: string[] }>
  nodes: Array<{
    nodeName: string
    displayName: string
    agentType: AgentType
    stageCode: string
    goal: string
    summary: string
    configSummaryData?: TaskNodeConfigSummary | null
    dependsOn: string[]
    required: boolean
    executionOrder: number
    fallbackOrder: string[]
    sourceUrls: string[]
  }>
  sourceUrls: string[]
}

export async function previewWorkflow(data: CreateTaskRequest) {
  return api.post('/task/preview', data) as Promise<ApiResponse<TaskPlanPreviewContract>>
}
```

- [ ] **Step 6: 修改 TaskCreatePage，不再从 `TaskNodeInfo[]` 反推计划**

```tsx
const [previewContract, setPreviewContract] = useState<TaskPlanPreviewContract | null>(null)

const sourceStrategyOverview = useMemo(
  () => buildSourceStrategyOverviewFromPreview(previewContract),
  [previewContract],
)

const taskPlanPreview = useMemo(
  () => buildTaskPlanPreviewFromContract(previewContract),
  [previewContract],
)

const refreshPreview = async () => {
  const values = form.getFieldsValue(true)
  try {
    const request = buildCreateTaskRequest(values)
    if (!request.taskName || !request.subjectProduct || !request.competitorNames?.length) {
      setPreviewContract(null)
      return
    }
    setPreviewLoading(true)
    const res = await previewWorkflow(request)
    setPreviewContract(res.data || null)
  } catch {
    setPreviewContract(null)
  } finally {
    setPreviewLoading(false)
  }
}
```

- [ ] **Step 7: 在前端工具层保留 UI 投影，但输入改为正式预览契约**

```ts
export function buildSourceStrategyOverviewFromPreview(
  preview: TaskPlanPreviewContract | null,
): SourceStrategyOverviewInfo {
  if (!preview) {
    return {
      competitorCount: 0,
      collectorCount: 0,
      browserSupplementCount: 0,
      verificationCount: 0,
      lanes: [],
    }
  }
  if (preview.lanes.length > 0) {
    return {
      competitorCount: preview.competitorCount,
      collectorCount: preview.collectorCount,
      browserSupplementCount: preview.lanes.filter((lane) => lane.browserSupplementEnabled).length,
      verificationCount: preview.lanes.filter((lane) => lane.verificationEnabled).length,
      lanes: preview.lanes,
    }
  }
  const derived = buildSourceStrategyOverviewFromPreviewNodes(preview.nodes)
  return {
    competitorCount: derived.competitorCount,
    collectorCount: derived.collectorCount,
    browserSupplementCount: derived.browserSupplementCount,
    verificationCount: derived.verificationCount,
    lanes: derived.lanes,
  }
}

export function buildTaskPlanPreviewFromContract(
  preview: TaskPlanPreviewContract | null,
): TaskPlanPreviewInfo {
  if (!preview) {
    return { competitorCount: 0, collectorCount: 0, pipelineCount: 0, stages: [] }
  }
  return {
    competitorCount: preview.competitorCount,
    collectorCount: preview.collectorCount,
    pipelineCount: preview.pipelineCount,
    stages: preview.stages.map((stage) => ({
      key: stage.key,
      title: stage.title,
      summary: stage.summary,
      detail: stage.detail,
    })),
  }
}
```

Implementation note: `buildSourceStrategyOverviewFromPreviewNodes` 直接复用当前 `TaskNodeInfo[] -> SourceStrategyOverviewInfo` 的分组逻辑，只是把输入类型替换成 `TaskPlanPreviewContract['nodes']`。这样第一阶段首屏不会因为 `lanes` 后端 deferred 而丢信息，第二阶段后端开始填充 `lanes` 后也能无缝切走 fallback。

- [ ] **Step 8: 运行后端 + 前端预览链路测试，确认绿灯**

Run: `mvn -pl backend -Dtest=TaskDefinitionContractTest,TaskDefinitionAppServiceTest,TaskControllerTest,AnalysisTaskServiceTest test`

Expected: PASS

Run: `npm --prefix frontend test -- src/pages/TaskCreatePage.test.tsx src/utils/taskNodeInsights.test.ts`

Expected: PASS

- [ ] **Step 9: 提交预览契约落地**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewStageResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskPlanPreviewNodeResponse.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskPlanPreviewAssembler.java backend/src/main/java/cn/bugstack/competitoragent/controller/TaskController.java backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java frontend/src/api/client.ts frontend/src/types/index.ts frontend/src/pages/TaskCreatePage.tsx frontend/src/utils/taskNodeInsights.ts frontend/src/pages/TaskCreatePage.test.tsx frontend/src/utils/taskNodeInsights.test.ts
git commit -m "feat(task): separate preview contract from runtime nodes"
```

### Task 5: 锁定 rerun / resume 只沿用当前计划版本，不重新发明计划语义

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskNodeResponse.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

- [ ] **Step 1: 新增运行态保护断言，禁止 rerun / resume 清空计划版本语义**

```java
@Test
void shouldPreservePlanVersionWhenRerunOrResumeTask() throws Exception {
    Long taskId = 1205L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.FAILED)
            .currentPlanVersionId(31L)
            .currentPlanVersion(2)
            .build();
    TaskNode collectorNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
    collectorNode.setPlanVersionId(31L);
    collectorNode.setNodeConfig("""
            {
              "competitorName":"Notion AI",
              "searchFallbackOrder":["PLANNED","BROWSER","HEURISTIC","HTTP"],
              "searchExecutionPlan":{"stage":"COLLECTOR_SEARCH_AND_COLLECT","fallbackOrder":["PLANNED","BROWSER","HEURISTIC","HTTP"],"steps":[]}
            }
            """);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectorNode));

    taskRuntimeCommandAppService.rerunFromNode(taskId, "collect_sources_web");

    assertEquals(31L, task.getCurrentPlanVersionId());
    assertEquals(2, task.getCurrentPlanVersion());
    assertEquals(31L, collectorNode.getPlanVersionId());
    JsonNode nodeConfig = objectMapper.readTree(collectorNode.getNodeConfig());
    assertEquals("COLLECTOR_SEARCH_AND_COLLECT",
            nodeConfig.path("searchExecutionPlan").path("stage").asText());
    assertEquals("PLANNED",
            nodeConfig.path("searchExecutionPlan").path("fallbackOrder").get(0).asText());
}
```

- [ ] **Step 2: 在运行时命令中显式禁止重跑时重建 nodeConfig 里的计划语义**

```java
/**
 * 第一阶段只允许在既有 planVersion 边界内重放执行，
 * 因此 rerun / resume 只能复用当前 TaskPlan 快照与节点配置中的计划语义，
 * 不能在这里重新计算 fallback 顺序、搜索阶段或节点目标。
 */
private void resetNodeExecutionState(TaskNode node, boolean clearOutput) {
    Long preservedPlanVersionId = node.getPlanVersionId();
    String preservedNodeConfig = node.getNodeConfig();
    recoveryPolicy().resetNodeForRerun(node, clearOutput);
    node.setPlanVersionId(preservedPlanVersionId);
    node.setNodeConfig(preservedNodeConfig);
}
```

- [ ] **Step 3: 在节点响应里补充预览/运行区分提示，避免前端继续误读**

```java
@Schema(description = "节点响应契约类型", example = "TASK_NODE_RUNTIME_V1")
private String contractType;
```

```java
return TaskNodeResponse.builder()
        .contractType("TASK_NODE_RUNTIME_V1")
        .id(node.getId())
        .nodeName(node.getNodeName())
        .displayName(node.getDisplayName())
        .nodeConfig(node.getNodeConfig())
        .agentType(node.getAgentType())
        .dependsOn(node.getDependsOn())
        .required(node.isRequired())
        .status(node.getStatus())
        .executionOrder(node.getExecutionOrder())
        .planVersionId(node.getPlanVersionId())
        .branchKey(node.getBranchKey())
        .eventKey(node.getNodeName())
        .build();
```

- [ ] **Step 4: 运行重跑/恢复测试，确认计划版本未丢失**

Run: `mvn -pl backend -Dtest=TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest test`

Expected: PASS

- [ ] **Step 5: 提交计划版本保护**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskNodeResponse.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java
git commit -m "feat(task): preserve plan version semantics on rerun and resume"
```

---

## Verification

- Backend targeted: `mvn -pl backend -Dtest=TaskDefinitionContractTest,TaskDefinitionAppServiceTest,TaskControllerTest,WorkflowFactoryTest,WorkflowPlanValidatorTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest test`
- Backend full module: `mvn -pl backend test`
- Backend integration smoke: `mvn -pl backend -Dtest=Phase1WorkflowIntegrationTest test`
- Frontend targeted: `npm --prefix frontend test -- src/pages/TaskCreatePage.test.tsx src/utils/taskNodeInsights.test.ts`
- Frontend full verification: `npm --prefix frontend run verify`
- Manual API smoke:
  1. `POST /api/task/preview`，确认返回 `TASK_PLAN_PREVIEW_V1`，且 `nodes[*].stageCode`、`nodes[*].fallbackOrder`、`nodes[*].configSummaryData` 可用；`lanes` 允许为空但首屏摘要必须正常展示。
  2. `POST /api/task/create` 创建任务后，确认 `AnalysisTask.currentPlanVersionId/currentPlanVersion` 已写入，且 `TaskPlan.planSnapshot` 包含 `contractType / goal / stages`。
  3. 对同一任务执行 `rerun` 或 `resume`，确认 `currentPlanVersionId` 未变化，历史快照即使缺少新字段也不会因严格 stage 校验失败。
  4. 打开任务详情接口或节点列表接口，确认节点响应携带 `TASK_NODE_RUNTIME_V1`。

## Rollback

- 后端回滚前提：第一阶段继续保留旧 `AnalysisTask` 字段与 `TaskPlan.planSnapshot` 写入，新增 `WorkflowPlan` 字段只做增量写入，不删除旧事实来源。
- 后端回滚路径：恢复 `TaskDefinitionAppService.createTask / previewWorkflow` 和 `WorkflowFactory.createWorkflow` 的旧调用路径即可；历史已写入的新 `WorkflowPlan` 字段不影响旧代码按既有节点拓扑读取。
- 前端回滚路径：`/api/task/preview` 新旧契约需要与前端页面一起回滚；如果采用分批发布，保留一版“预览节点列表 -> 首屏摘要”的兼容适配器直到切流完成。

## Phase 2 Handoff

第二阶段 `搜索与采集执行引擎收口` 必须直接复用本阶段产出的以下边界，不再重新定义：

1. `TaskDefinition`：搜索计划生成时必须从正式任务定义读取竞品、来源范围、质量要求，而不是再回读 `CreateTaskRequest` 或松散 JSON。
2. `ExecutionPlanDefinition`：Collector 搜索阶段码、fallback order、计划目标、阶段摘要必须从这里继承，不能在 `SearchExecutionCoordinator` 内另起一套术语。第一阶段暂时仍由 `WorkflowFactory.buildSearchFallbackOrder()` 提供 `fallbackOrder`，第二阶段必须把这段逻辑抽成独立搜索策略解析器。
3. `WorkflowPlan.contractType / goal / stages / stageCode / fallbackOrder`：搜索回放、审计快照、恢复建议都必须引用当前 `planVersionId` 对应的正式计划快照。
4. `TaskPlanPreviewResponse`：创建页和后续搜索预演页只能消费正式预览契约，不能再把运行态节点或半结构化 collector insight 当预览数据源。`lanes` 的后端正式投影在第一阶段明确 deferred，第二阶段需要基于 `CollectorNodeConfig` 把它补成稳定契约，而不是长期依赖前端 fallback 聚合。
5. `TaskNodeResponse.contractType = TASK_NODE_RUNTIME_V1`：第二阶段若新增 `searchAudit`、`selectedTargets`、`checkpointSummary` 字段，只能加在运行态契约上，不能回灌到 preview DTO。

## Out Of Scope

- `CollectorAgent.md` 中候选验证、双重抓取、搜索审计回放等执行细节修复
- Extractor / Analyzer / Writer / Reviewer 的正式契约收口
- `TaskPlanPreviewResponse.lanes` 的后端正式聚合实现；第一阶段仅保证字段定义稳定，并由前端基于 preview nodes 做兼容 fallback
- 计划预览缓存、复杂度评分、预算闸门的提效项
