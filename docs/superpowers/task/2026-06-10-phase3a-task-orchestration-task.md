# Phase 3A Task Orchestration Task

## 核心目标

把 `task-orchestration` 收口到稳定的 `TaskRuntimeFacade`、`TaskQueryFacade` 和 `TaskArtifactCleanupCoordinator`，让任务创建、执行、恢复、回放、清理不再继续新增跨模块直连依赖。

## 预期耗时

- `1 - 1.5` 人天

## 前置依赖

- `phase1-agent-runtime-baseline-task` 已完成
- `phase2-archunit-boundary-task` 已完成

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-progress.md`

## 完成定义

- `AnalysisTaskService` 只保留门面职责，不再持有新的 repository 组装逻辑
- 存在 `TaskRuntimeFacade`、`TaskQueryFacade`
- 存在 `TaskArtifactCleanupPort` / `TaskArtifactCleanupCoordinator`
- task 删除、重跑、节点重跑通过 coordinator 触发清理，不再新增模块 repository 直连
- 事务口径明确为“task 用例持有事务；同步 cleanup 任一失败则当前用例整体回滚”

## 文件边界

### Must Modify

- `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskArtifactCleanupService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/query/TaskQueryAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskRuntimeFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskRuntimeFacadeImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskQueryFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskQueryFacadeImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/cleanup/TaskArtifactCleanupPort.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/cleanup/TaskArtifactCleanupCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/cleanup/TaskArtifactCleanupCoordinatorImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/cleanup/LegacyTaskArtifactCleanupPort.java`

### May Modify

- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/query/TaskQueryAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

### Read For Context

- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/repository/...`
- `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`

## 事务所有权规则

- `task-orchestration` 用例持有事务边界。
- cleanup coordinator 只调度“本模块同步清理”。
- 任一 cleanup port 抛错，当前删除 / 重跑 / 节点重跑用例整体回滚。
- phase3a 不做异步补偿，也不把失败吞掉继续执行后续 port。

## cleanup port 目标清单

phase3a 至少要把清理接入口先设计成可扩展列表，后续模块按同一接口注册：

- `CollectionArtifactCleanupPort`
- `KnowledgeArtifactCleanupPort`
- `ReportArtifactCleanupPort`
- `ConversationArtifactCleanupPort`
- `AgentAuditCleanupPort`
- `WorkflowArtifactCleanupPort`

phase3a 落地时允许先用 `LegacyTaskArtifactCleanupPort` 包裹现有集中清理器，但不能把接口只设计成 collection 专用。

---

## Task 1: 建立 `TaskRuntimeFacade` 与 `TaskQueryFacade`

### Task 核心目标

让 `AnalysisTaskService` 从“混合门面”收口为只暴露稳定读写入口的 facade 消费者。

### Task 预期耗时

- `3 - 4` 小时

### Task 前置依赖

- phase1、phase2 完成

### 执行步骤

- [ ] Step 1：创建 `TaskRuntimeFacade`、`TaskQueryFacade` 接口。
- [ ] Step 2：创建 facade impl，先包装现有 app service，不改业务语义。
- [ ] Step 3：把 `AnalysisTaskService` 改为只依赖 facade。
- [ ] Step 4：补充 `AnalysisTaskServiceTest`，锁定委派关系。

### 最小接口形状

```java
public interface TaskRuntimeFacade {

    void executeTask(Long taskId);

    void retryTask(Long taskId);

    void resumeTask(Long taskId);

    void rerunFromNode(Long taskId, String nodeName);

    void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request);

    void pauseNode(Long taskId, String nodeName);

    void resumeNode(Long taskId, String nodeName);

    void skipNode(Long taskId, String nodeName);

    void terminateNode(Long taskId, String nodeName);

    void stopTask(Long taskId);
}
```

```java
public interface TaskQueryFacade {

    TaskListPageResponse listTasks(String status, int pageNum, int pageSize);

    TaskResponse getTask(Long taskId);

    List<TaskNodeResponse> getTaskNodes(Long taskId);
}
```

### 最小测试结构

```java
@Test
void should_delegate_runtime_operations_to_task_runtime_facade() {
    taskService.executeTask(1L);

    verify(taskRuntimeFacade).executeTask(1L);
    verifyNoInteractions(taskQueryFacade);
}
```

```java
@Test
void should_delegate_queries_to_task_query_facade() {
    TaskResponse response = TaskResponse.builder().id(1L).taskName("task").build();
    when(taskQueryFacade.getTask(1L)).thenReturn(response);

    TaskResponse actual = taskService.getTask(1L);

    assertSame(response, actual);
    verify(taskQueryFacade).getTask(1L);
}
```

### 验证命令

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskQueryAppServiceTest test
```

---

## Task 2: 建立完整 cleanup 协调入口

### Task 核心目标

先把 task 侧清理触点统一到 coordinator，而不是继续在命令服务里散落 repository 删除逻辑。

### Task 预期耗时

- `4 - 6` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：定义 `TaskArtifactCleanupPort` 与 `TaskArtifactCleanupCoordinator`。
- [ ] Step 2：先用 `LegacyTaskArtifactCleanupPort` 包裹 `TaskArtifactCleanupService`。
- [ ] Step 3：让 `TaskRuntimeCommandAppService`、`TaskDefinitionAppService` 改为依赖 coordinator。
- [ ] Step 4：在测试中锁定“同步 cleanup 失败即当前用例失败并回滚”的事务口径。

### 最小接口形状

```java
public interface TaskArtifactCleanupPort {

    String moduleName();

    void cleanupTaskArtifacts(Long taskId);

    default void cleanupNodeArtifacts(Long taskId, String nodeName) {
    }
}
```

```java
public interface TaskArtifactCleanupCoordinator {

    void cleanupTaskArtifacts(Long taskId);

    void cleanupNodeArtifacts(Long taskId, String nodeName);
}
```

### 最小测试结构

```java
@Test
void should_delegate_task_cleanup_to_cleanup_coordinator() {
    Long taskId = 1L;
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(failedTask(taskId)));

    taskRuntimeCommandAppService.retryTask(taskId);

    verify(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);
}
```

```java
@Test
void should_propagate_cleanup_failure_in_same_use_case() {
    Long taskId = 1L;
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(failedTask(taskId)));
    doThrow(new IllegalStateException("cleanup failed"))
            .when(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);

    assertThrows(IllegalStateException.class, () -> taskRuntimeCommandAppService.retryTask(taskId));
}
```

### 验证命令

```powershell
mvn -Dtest=TaskDefinitionAppServiceTest,TaskRuntimeCommandAppServiceTest test
```

---

## Task 3: 锁定阶段收尾条件

### Task 核心目标

确认 phase3a 没有扩散成 collection / report / knowledge 的内部改造。

### Task 预期耗时

- `1 - 2` 小时

### Task 前置依赖

- Task 1、Task 2 完成

### 执行步骤

- [ ] Step 1：运行 task 线聚焦测试。
- [ ] Step 2：核对 `AnalysisTaskService` 不再新增 repository 依赖。
- [ ] Step 3：在 progress 中记录尚未拆出的 cleanup 模块实现和后续 owner。

### 验证命令

```powershell
mvn -Dtest=AnalysisTaskServiceTest,TaskDefinitionAppServiceTest,TaskRuntimeCommandAppServiceTest,TaskQueryAppServiceTest,TaskRecoveryServiceTest,TaskReplayProjectionServiceTest test
```

### 提交标准

- 只包含 task facade、cleanup 协调器与事务边界测试
- 不混入 `DagExecutor`、`BaseAgent`、collection 主逻辑重写
- PR 描述必须说明“当前仅建立可扩展 cleanup 入口，具体模块 port 后续逐步替换 legacy adapter”
