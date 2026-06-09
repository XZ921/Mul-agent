# 后端模块化 Phase 1 实施计划

> **给执行 agent 的要求：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，并按任务逐项实施本计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在保持现有 REST 行为和测试兼容的前提下，从 `AnalysisTaskService` 与 `DagExecutor` 中提取低风险协作者，降低 Phase 1 后端耦合。

**架构：** Phase 1 的新增类先放在现有包或直接子包下，避免大规模 import 变更。先提取聚焦的应用服务、组装器和运行时协作者，后续阶段再逐步迁入目标 `modules/*` 结构。事务所有权继续保留在完整用例的应用服务内。

**技术栈：** Java 17、Spring Boot 3.3.5、JUnit 5、Mockito、Maven Surefire、ArchUnit。

---

## 文件与职责

新增文件：

- `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`：Phase 1 架构依赖测试，显式记录遗留排除项。
- `backend/src/main/java/cn/bugstack/competitoragent/task/query/TaskQueryAppService.java`：只读任务列表、任务详情、节点查询服务。
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`：从 `AnalysisTaskService` 提取任务/节点响应组装逻辑。
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`：任务创建、任务预览与任务删除命令服务。
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`：执行、停止、恢复、重试、重跑、节点控制命令服务。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentCapability.java`：现有 Agent SPI 的包装接口。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentCapabilityRegistry.java`：运行时使用的 Agent 能力注册表。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionRequest.java`：Agent 能力请求对象。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionResponse.java`：Agent 能力响应对象。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistry.java`：围绕当前 `List<Agent>` 的薄适配器。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeStateRefresher.java`：运行时快照刷新协作者。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`：节点、搜索进度、诊断、日志事件发布协作者。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`：动态计划挂载协作者。

修改文件：

- `backend/pom.xml`：增加 ArchUnit 测试依赖。
- `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`：逐步委托查询、命令和视图组装职责。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`：逐步委托 Agent 查找、运行时快照刷新、事件发布和动态计划挂载职责。
- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`：仅在构造器依赖变化时调整 mock。
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`：在引入 `AgentCapabilityRegistry` 和运行时协作者后调整测试辅助构造。
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`：在运行时事件发布器提取后调整构造依赖。

外部保持不变：

- `backend/src/main/java/cn/bugstack/competitoragent/controller/TaskController.java`
- `/api/task` 下的 REST 路径
- 前端先调用 `/api/task/create`，再调用 `/api/task/{id}/execute` 的行为

---

## 任务 1：建立架构测试基线

**文件：**

- 修改：`backend/pom.xml`
- 新增：`backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`

- [ ] **步骤 1：增加 ArchUnit 依赖**

在 `backend/pom.xml` 的 `<dependencies>` 内加入：

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

- [ ] **步骤 2：新增依赖基线测试**

创建 `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`：

第一版架构测试必须保持构建绿色。它的目标是记录当前违规依赖基线，而不是一次性清空所有历史债务。已经存在且暂时不能整改的违规依赖，要放入明确命名的豁免列表或缩小后的规则作用域；后续每个任务完成后，再移除该任务已经整改的豁免。

```java
package cn.bugstack.competitoragent.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "cn.bugstack.competitoragent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class BackendModuleDependencyTest {

    @ArchTest
    static final ArchRule agent_classes_should_not_access_task_repositories =
            noClasses()
                    .that().resideInAPackage("..agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                    .because("Agent implementations should return structured results; task-runtime owns persistence and state transitions.");

    @ArchTest
    static final ArchRule controllers_should_not_depend_on_workflow_internals =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage("..workflow..")
                    .because("REST controllers should call application services or facades, not runtime internals.");

    @ArchTest
    static final ArchRule report_should_not_depend_on_source_provider_implementations =
            noClasses()
                    .that().resideInAPackage("..report..")
                    .should().dependOnClassesThat().resideInAnyPackage("..source..", "..search..")
                    .because("report-delivery consumes report/evidence views, not collection provider internals.");
}
```

- [ ] **步骤 3：运行架构测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

预期：PASS。若测试因为现有遗留依赖失败，要把每条违规依赖转化为明确命名的遗留豁免，或缩小规则作用包，让基线测试保持绿色；不能删除测试，也不能用宽泛规则静默放行所有旧依赖。后续任务完成后，应逐步移除对应豁免。

- [ ] **步骤 4：提交**

```powershell
git add backend/pom.xml backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java
git commit -m "test: add backend module dependency baseline"
```

---

## 任务 2：提取只读任务查询服务

**文件：**

- 新增：`backend/src/main/java/cn/bugstack/competitoragent/task/query/TaskQueryAppService.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`

- [ ] **步骤 1：提取 `TaskNodeViewAssembler` 骨架**

创建 `TaskNodeViewAssembler`，先放置 response 组装依赖：

```java
package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskNodeViewAssembler {

    private final AiCallAuditRecordRepository aiCallAuditRecordRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;

    public TaskResponse toTaskResponse(AnalysisTask task, List<TaskNode> nodes) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.toTaskResponse logic here before wiring callers.");
    }

    public TaskNodeResponse toNodeResponse(TaskNode node, List<TaskNode> allNodes) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.toNodeResponse logic here before wiring callers.");
    }
}
```

- [ ] **步骤 2：移动组装器逻辑**

从 `AnalysisTaskService` 移动以下私有方法到 `TaskNodeViewAssembler`。方法体先保持不变，只调整 `this` 调用和依赖字段：

```text
toTaskResponse
toNodeResponse
buildTaskStageSummary
buildTaskStatusSummary
buildTaskInterventionSummary
buildTaskResumeAdvice
buildTaskReplayEntrySummary
buildRerunImpactMap
buildPlanVersionMap
buildConfigSummary
buildCollectorNodeInsight
buildPreviewCollectorNodeInsight
buildCollectorNodeInsight(JsonNode, JsonNode)
buildConfigSummaryData
buildOutputSummary
buildAiGovernanceSummary
readJson
textOrNull
defaultIfBlank
readStringList
convertValue
convertList
sourceTypeLabel
searchModeLabel
summarizeArray
```

- [ ] **步骤 3：提取 `TaskQueryAppService`**

创建 `backend/src/main/java/cn/bugstack/competitoragent/task/query/TaskQueryAppService.java`：

```java
package cn.bugstack.competitoragent.task.query;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TaskQueryAppService {

    private static final int DEFAULT_TASK_LIST_PAGE_SIZE = 10;
    private static final int MAX_TASK_LIST_PAGE_SIZE = 50;

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskNodeViewAssembler assembler;

    public TaskListPageResponse listTasks(String status, int pageNum, int pageSize) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.listTasks logic here before wiring callers.");
    }

    public TaskResponse getTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        return assembler.toTaskResponse(task, nodes);
    }

    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        return nodes.stream()
                .map(node -> assembler.toNodeResponse(node, nodes))
                .toList();
    }

    private AnalysisTask getTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));
    }

    private AnalysisTaskStatus parseTaskStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnalysisTaskStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "Unsupported task status: " + status);
        }
    }

    private Page<AnalysisTask> listMatchedTaskPage(AnalysisTaskStatus status, PageRequest pageRequest) {
        return status == null
                ? taskRepository.findAllByOrderByCreatedAtDesc(pageRequest)
                : taskRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
    }
}
```

- [ ] **步骤 4：移动列表分页逻辑**

把 `AnalysisTaskService.listTasks` 完整方法体和以下辅助方法移动到 `TaskQueryAppService`：

```text
parseTaskStatus
listMatchedTasks
listMatchedTaskPage
normalizeTaskListPageNum
normalizeTaskListPageSize
calculateTaskListTotalPages
buildAttentionTaskResponses
taskAttentionPriority
buildTaskListSummary
```

将 `toTaskResponse(task)` 改为：

```java
List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
assembler.toTaskResponse(task, nodes);
```

- [ ] **步骤 5：从 `AnalysisTaskService` 委托**

向 `AnalysisTaskService` 注入 `TaskQueryAppService`，并将三个 public 查询方法改为：

```java
public TaskListPageResponse listTasks(String status, int pageNum, int pageSize) {
    return taskQueryAppService.listTasks(status, pageNum, pageSize);
}

public TaskResponse getTask(Long taskId) {
    return taskQueryAppService.getTask(taskId);
}

public List<TaskNodeResponse> getTaskNodes(Long taskId) {
    return taskQueryAppService.getTaskNodes(taskId);
}
```

- [ ] **步骤 6：运行聚焦测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskControllerTest test
```

预期：PASS。如果构造器变化导致 `@InjectMocks` 失败，只在非查询测试里补 mock；查询行为优先增加直接测试覆盖。

- [ ] **步骤 7：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/controller
git commit -m "refactor: extract task query app service"
```

---

## 任务 3：提取节点控制命令

**文件：**

- 新增/修改：`backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

- [ ] **步骤 1：创建命令服务**

创建 `TaskRuntimeCommandAppService`：

```java
package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskRuntimeCommandAppService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;

    @Transactional
    public void pauseNode(Long taskId, String nodeName) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.pauseNode logic here before wiring callers.");
    }

    @Transactional
    public void resumeNode(Long taskId, String nodeName) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.resumeNode logic here before wiring callers.");
    }

    @Transactional
    public void skipNode(Long taskId, String nodeName) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.skipNode logic here before wiring callers.");
    }

    @Transactional
    public void terminateNode(Long taskId, String nodeName) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.terminateNode logic here before wiring callers.");
    }

    private AnalysisTask getTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));
    }

    private TaskNode getNodeOrThrow(Long taskId, String nodeName) {
        return nodeRepository.findByTaskIdAndNodeName(taskId, nodeName)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NODE_NOT_FOUND, "nodeName=" + nodeName));
    }
}
```

- [ ] **步骤 2：移动节点控制方法体**

从 `AnalysisTaskService` 移动以下方法体到 `TaskRuntimeCommandAppService`：

```text
pauseNode
resumeNode
skipNode
terminateNode
```

同时移动仅这些方法使用的私有辅助方法。若辅助方法后续运行时命令也会使用，则保留在 `TaskRuntimeCommandAppService`。

本任务只创建 `TaskRuntimeCommandAppService` 骨架并放入节点控制方法；任务 5 再补全任务级运行时命令，例如 `executeTask`、`resumeTask`、`retryTask`、`rerunFromNode` 和 `stopTask`。

- [ ] **步骤 3：从 `AnalysisTaskService` 委托**

注入 `TaskRuntimeCommandAppService`，并替换 public 方法：

```java
@Transactional
public void pauseNode(Long taskId, String nodeName) {
    taskRuntimeCommandAppService.pauseNode(taskId, nodeName);
}

@Transactional
public void resumeNode(Long taskId, String nodeName) {
    taskRuntimeCommandAppService.resumeNode(taskId, nodeName);
}

@Transactional
public void skipNode(Long taskId, String nodeName) {
    taskRuntimeCommandAppService.skipNode(taskId, nodeName);
}

@Transactional
public void terminateNode(Long taskId, String nodeName) {
    taskRuntimeCommandAppService.terminateNode(taskId, nodeName);
}
```

- [ ] **步骤 4：运行聚焦测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=AnalysisTaskServiceTest#shouldExposePauseResumeSkipTerminateCapabilitiesByNodeStatus,TaskControllerTest#shouldForwardResumeAndRerunCommands test
```

预期：PASS。如果当前 Surefire 环境不支持方法选择器，改跑：

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskControllerTest test
```

- [ ] **步骤 5：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/controller
git commit -m "refactor: extract task node control commands"
```

---

## 任务 4：提取任务定义命令

**文件：**

- 新增：`backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`

- [ ] **步骤 1：创建任务定义命令服务**

创建 `TaskDefinitionAppService`：

```java
package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskDefinitionAppService {

    private final AnalysisTaskRepository taskRepository;
    private final WorkflowFactory workflowFactory;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;
    private final TaskNodeViewAssembler assembler;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.createTask logic here before wiring callers.");
    }

    public List<TaskNodeResponse> previewWorkflow(CreateTaskRequest request) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.previewWorkflow logic here before wiring callers.");
    }

    @Transactional
    public void deleteTask(Long taskId) {
        throw new UnsupportedOperationException("Move AnalysisTaskService.deleteTask logic here before wiring callers.");
    }
}
```

- [ ] **步骤 2：移动 create/preview/delete 逻辑**

从 `AnalysisTaskService` 移动以下方法体：

```text
createTask
previewWorkflow
deleteTask
```

如果任务 2 后以下辅助方法只服务于创建/预览，则一并移动：

```text
toJson
defaultIfBlank
toPreviewNodeResponse
```

- [ ] **步骤 3：从 `AnalysisTaskService` 委托**

注入 `TaskDefinitionAppService`，并替换方法：

```java
@Transactional
public TaskResponse createTask(CreateTaskRequest request) {
    return taskDefinitionAppService.createTask(request);
}

public List<TaskNodeResponse> previewWorkflow(CreateTaskRequest request) {
    return taskDefinitionAppService.previewWorkflow(request);
}

@Transactional
public void deleteTask(Long taskId) {
    taskDefinitionAppService.deleteTask(taskId);
}
```

- [ ] **步骤 4：运行聚焦测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=AnalysisTaskServiceTest#shouldUsePreviewPlanInsteadOfLiveWorkflowBuildWhenPreviewingTask,TaskControllerTest#shouldCreateTaskAndExposeWorkflowPreviewContract test
```

预期：PASS。重点检查 `createTask`、`previewWorkflow`、`deleteTask` 相关测试。如果方法选择器不可用，改跑：

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskControllerTest test
```

- [ ] **步骤 5：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/controller
git commit -m "refactor: extract task definition commands"
```

---

## 任务 5：提取运行时启动与恢复命令

**文件：**

- 修改：`backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

- [ ] **步骤 1：增加运行时命令方法**

在 `TaskRuntimeCommandAppService` 中加入：

```java
@Transactional
public void executeTask(Long taskId) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.executeTask logic here before wiring callers.");
}

@Transactional
public void retryTask(Long taskId) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.retryTask logic here before wiring callers.");
}

@Transactional
public void rerunFromNode(Long taskId, String nodeName) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.rerunFromNode logic here before wiring callers.");
}

@Transactional
public void resumeTask(Long taskId) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.resumeTask logic here before wiring callers.");
}

@Transactional
public void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.updateNodeConfigAndRerun logic here before wiring callers.");
}

@Transactional
public void stopTask(Long taskId) {
    throw new UnsupportedOperationException("Move AnalysisTaskService.stopTask logic here before wiring callers.");
}
```

- [ ] **步骤 2：补齐运行时命令依赖**

为 `TaskRuntimeCommandAppService` 增加这些构造器依赖：

```text
EvidenceSourceRepository
CompetitorKnowledgeRepository
ReportRepository
AgentExecutionLogRepository
WorkflowEventPublisher
WorkflowEventOutboxService
DynamicTaskGraphService
AnalysisTaskRunner
TaskRecoveryService
ObjectMapper
```

- [ ] **步骤 3：移动方法体和辅助方法**

从 `AnalysisTaskService` 移动：

```text
executeTask
retryTask
rerunFromNode
resumeTask
updateNodeConfigAndRerun
stopTask
```

移动这些只被命令方法使用的私有辅助方法：

```text
runAfterCommit
resetTaskForExecution
prepareTaskForResume
collectAffectedNodes
invalidateDerivedDataForNodeRerun
reuseSearchCheckpointIfPresent
resetNodeExecutionState
markDownstreamAffected
mergeNodeConfig
refreshTaskSnapshot
```

Phase 1 保留 `AnalysisTaskService` 作为委托门面，避免 controller 和测试同步大改。

- [ ] **步骤 4：从 `AnalysisTaskService` 委托**

替换 public 方法：

```java
@Transactional
public void executeTask(Long taskId) {
    taskRuntimeCommandAppService.executeTask(taskId);
}

@Transactional
public void retryTask(Long taskId) {
    taskRuntimeCommandAppService.retryTask(taskId);
}

@Transactional
public void rerunFromNode(Long taskId, String nodeName) {
    taskRuntimeCommandAppService.rerunFromNode(taskId, nodeName);
}

@Transactional
public void resumeTask(Long taskId) {
    taskRuntimeCommandAppService.resumeTask(taskId);
}

@Transactional
public void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request) {
    taskRuntimeCommandAppService.updateNodeConfigAndRerun(taskId, nodeName, request);
}

@Transactional
public void stopTask(Long taskId) {
    taskRuntimeCommandAppService.stopTask(taskId);
}
```

- [ ] **步骤 5：运行运行时命令测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=AnalysisTaskServiceTest test
```

预期：PASS。重点检查 `rerunFromNode`、`resumeTask`、`updateNodeConfigAndRerun`、`stopTask` 相关测试。`deleteTask` 已在任务 4 归入任务定义命令。

- [ ] **步骤 6：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/task backend/src/test/java/cn/bugstack/competitoragent/task
git commit -m "refactor: extract task runtime commands"
```

---

## 任务 6：引入 AgentCapabilityRegistry 包装层

**文件：**

- 新增：`backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentCapability.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentCapabilityRegistry.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionRequest.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionResponse.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistry.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`

- [ ] **步骤 1：增加能力接口和 record**

创建以下文件：

```java
package cn.bugstack.competitoragent.agent.capability;

public interface AgentCapability {
    AgentExecutionResponse execute(AgentExecutionRequest request);
}
```

```java
package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.model.enums.AgentType;

public interface AgentCapabilityRegistry {
    AgentCapability resolve(AgentType agentType);
}
```

```java
package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.AgentContext;

public record AgentExecutionRequest(AgentContext context) {
}
```

```java
package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.AgentResult;

public record AgentExecutionResponse(AgentResult result) {
}
```

- [ ] **步骤 2：增加 Spring 适配器**

创建 `SpringAgentCapabilityRegistry`：

```java
package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.enums.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SpringAgentCapabilityRegistry implements AgentCapabilityRegistry {

    private final Map<AgentType, AgentCapability> capabilities;

    public SpringAgentCapabilityRegistry(List<Agent> agents) {
        this.capabilities = buildCapabilities(agents);
    }

    @Override
    public AgentCapability resolve(AgentType agentType) {
        return capabilities.get(agentType);
    }

    private Map<AgentType, AgentCapability> buildCapabilities(List<Agent> agents) {
        Map<AgentType, AgentCapability> registry = new EnumMap<>(AgentType.class);
        for (Agent agent : agents) {
            AgentCapability previous = registry.put(agent.getType(), request -> {
                AgentResult result = agent.execute(request.context());
                return new AgentExecutionResponse(result);
            });
            if (previous != null) {
                log.warn("duplicate agent capability registered, agentType={}", agent.getType());
            }
        }
        return registry;
    }
}
```

- [ ] **步骤 3：更新 `DagExecutor` 构造器**

将 `DagExecutor` 构造器参数从 `List<Agent> agents` 改为 `AgentCapabilityRegistry agentCapabilityRegistry`，并保存为字段：

```java
private final AgentCapabilityRegistry agentCapabilityRegistry;
```

删除字段：

```java
private final Map<AgentType, Agent> agentRegistry;
```

- [ ] **步骤 4：更新节点执行查找逻辑**

将：

```java
Agent agent = agentRegistry.get(node.getAgentType());
if (agent == null) {
    failNode(node, "Missing agent implementation: " + node.getAgentType());
    return new NodeExecutionResult(node);
}

AgentResult result = executeNodeOnce(agent, nodeContext);
```

替换为：

```java
AgentCapability capability = agentCapabilityRegistry.resolve(node.getAgentType());
if (capability == null) {
    failNode(node, "Missing agent implementation: " + node.getAgentType());
    return new NodeExecutionResult(node);
}

AgentResult result = executeNodeOnce(capability, nodeContext);
```

将辅助方法改为：

```java
private AgentResult executeNodeOnce(AgentCapability capability, AgentContext context) {
    try {
        return capability.execute(new AgentExecutionRequest(context)).result();
    } catch (Exception e) {
        return AgentResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
```

- [ ] **步骤 5：删除内部注册表构建方法**

从 `DagExecutor` 删除：

```java
buildAgentRegistry(List<Agent> agents)
```

- [ ] **步骤 6：更新测试**

在 `DagExecutorTest` 和 `DagExecutorWorkflowEventTest` 中，将测试辅助方法从 `List<Agent>` 参数改为本地注册表：

```java
private static AgentCapabilityRegistry registryOf(List<Agent> agents) {
    return new SpringAgentCapabilityRegistry(agents);
}
```

然后将 `registryOf(List.of(...))` 传给 `new DagExecutor(...)`。

- [ ] **步骤 7：运行测试**

在 `backend` 目录运行：

```powershell
mvn -Dtest=DagExecutorTest,DagExecutorWorkflowEventTest test
```

预期：PASS。

- [ ] **步骤 8：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/agent/capability backend/src/main/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/workflow
git commit -m "refactor: add agent capability registry"
```

---

## 任务 7：提取 DagExecutor 运行时协作者

**文件：**

- 新增：`backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeStateRefresher.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- 新增：`backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/DynamicPlanAppender.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`

- [ ] **步骤 1：提取 `RuntimeStateRefresher`**

创建：

```java
package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RuntimeStateRefresher {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;

    public void refreshRuntimeSnapshot(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> latestNodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    task.getStatus(),
                    task.getErrorMessage(),
                    latestNodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }
}
```

将 `DagExecutor.refreshRuntimeSnapshot(taskId)` 调用替换为：

```java
runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
```

- [ ] **步骤 2：运行 DagExecutor 测试**

```powershell
mvn -Dtest=DagExecutorTest,DagExecutorWorkflowEventTest test
```

预期：PASS。

- [ ] **步骤 3：提取 `RuntimeEventEmitter`**

创建 `RuntimeEventEmitter`，从 `DagExecutor` 移动以下方法：

```text
publishNodeExecutionEvents
publishSearchProgressEventIfPresent
publishDiagnosisEventIfPresent
publishAgentOutputFallbackEvent
buildSearchProgressEventPayload
```

构造器依赖：

```text
TaskEventPublisher
AgentLogService
ObjectMapper
```

公开方法：

```java
public void publishNodeExecutionEvents(Long taskId, TaskNode node) {
    // moved body from DagExecutor.publishNodeExecutionEvents
}
```

将 `DagExecutor.publishNodeExecutionEvents(taskId, completedNode)` 替换为：

```java
runtimeEventEmitter.publishNodeExecutionEvents(taskId, completedNode);
```

- [ ] **步骤 4：运行事件测试**

```powershell
mvn -Dtest=DagExecutorWorkflowEventTest,DagExecutorTest test
```

预期：PASS。

- [ ] **步骤 5：提取 `DynamicPlanAppender`**

创建 `DynamicPlanAppender`，从 `DagExecutor` 移动 `maybeAppendDynamicPlan` 以及它使用的私有辅助方法。

构造器依赖：

```text
AnalysisTaskRepository
TaskNodeRepository
DynamicTaskGraphService
TaskPlanRepository
ObjectMapper
```

公开方法：

```java
public boolean maybeAppendDynamicPlan(Long taskId,
                                      List<TaskNode> nodes,
                                      Map<String, TaskNode> nodeMap,
                                      TaskNode completedNode) {
    // moved body from DagExecutor.maybeAppendDynamicPlan
}
```

将 `DagExecutor` 中的调用替换为：

```java
if (dynamicPlanAppender.maybeAppendDynamicPlan(taskId, nodes, nodeMap, completedResult.getNode())) {
    runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
}
```

- [ ] **步骤 6：运行动态图测试**

```powershell
mvn -Dtest=DagExecutorTest#shouldCreateDynamicBackflowPlanAndAppendDynamicNodesWhenFinalReviewFails test
```

预期：PASS。如果方法选择器不可用，改跑：

```powershell
mvn -Dtest=DagExecutorTest test
```

- [ ] **步骤 7：提交**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/workflow
git commit -m "refactor: extract dag executor runtime collaborators"
```

---

## 最终验证

- [ ] **运行后端聚焦回归**

在 `backend` 目录运行：

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskControllerTest,DagExecutorTest,DagExecutorWorkflowEventTest test
```

预期：PASS。

- [ ] **有时间时运行更广的后端测试**

在 `backend` 目录运行：

```powershell
mvn test
```

预期：PASS。如存在已知无关失败，需要在实施记录中说明。

- [ ] **如果实施发现边界变化，同步更新设计文档**

修改：

```text
docs/superpowers/specs/2026-06-08-backend-modularization-design.md
```

只有当实现迫使模块边界发生实质变化时，才更新设计文档。例如 Phase 1 类落位改变，或 Agent 包装层迁移方案改变。
