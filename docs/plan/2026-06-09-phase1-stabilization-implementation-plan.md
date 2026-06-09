# Phase 1 Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏 `/api/task`、SSE 和任务恢复行为的前提下，完成当前 Phase 1 剩余收尾工作：清理 `DagExecutor` 残留冗余、补强模块边界测试、固化运行时契约并锁定兼容行为。

**Architecture:** 当前代码已经完成主要职责拆分，因此本计划不再重复提取 `TaskQueryAppService`、`TaskDefinitionAppService`、`TaskRuntimeCommandAppService` 等服务，而是围绕现有边界做“清理 + 锁定 + 验证”。实现中以表征测试和 ArchUnit 为先，所有兼容性变更采用增量方式，不做数据库迁移，不删除现有 DTO 字段。

**Tech Stack:** Java 17、Spring Boot 3.3.5、JUnit 5、Mockito、ArchUnit、Maven Surefire

---

### Task 1: 锁定当前门面与创建链路基线

**Files:**
- Create: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`
- Read for context: `backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java`
- Read for context: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java`

- [ ] **Step 1: 为 `createTask` 增加治理链路表征测试**

在 `TaskDefinitionAppServiceTest` 中先写一个回归测试，锁定“创建任务时只触发一次组织配额检查，并把已占位状态写回任务实体”的行为。

```java
@Test
void shouldReserveQuotaOnceAndMarkTaskAsReservedWhenCreatingTask() {
    CreateTaskRequest request = new CreateTaskRequest();
    request.setTaskName("竞品分析任务");
    request.setSubjectProduct("产品A");
    request.setCompetitorUrls(List.of("https://example.com"));

    when(taskRepository.save(any(AnalysisTask.class))).thenAnswer(invocation -> {
        AnalysisTask task = invocation.getArgument(0);
        if (task.getId() == null) {
            task.setId(100L);
        }
        return task;
    });

    taskDefinitionAppService.createTask(request);

    verify(organizationQuotaPolicy, times(1))
            .checkAndReserve(anyString(), anyString(), anyString(), eq(1), anyList());
    verify(taskQuotaCoordinator, times(1)).markTaskQuotaReserved(any(AnalysisTask.class));
    verify(taskQuotaCoordinator, never()).ensureTaskQuotaReserved(any(AnalysisTask.class));
}
```

- [ ] **Step 2: 为门面层增加“只委托不回流业务”的测试**

在 `AnalysisTaskServiceTest` 中补一个轻量测试，防止未来把业务细节重新塞回门面层。

```java
@Test
void shouldDelegateCreateTaskToDefinitionAppService() {
    CreateTaskRequest request = new CreateTaskRequest();
    TaskResponse response = TaskResponse.builder().id(1L).taskName("task").build();

    when(taskDefinitionAppService.createTask(request)).thenReturn(response);

    TaskResponse actual = analysisTaskService.createTask(request);

    assertSame(response, actual);
    verify(taskDefinitionAppService, times(1)).createTask(request);
    verifyNoInteractions(taskQueryAppService, taskRuntimeCommandAppService);
}
```

- [ ] **Step 3: 为控制器补 API 行为不变的回归测试**

在 `TaskControllerTest` 中锁定“创建后仍通过既有 `/api/task` 契约对外返回”的行为，避免后续清理影响接口形状。

```java
@Test
void shouldKeepCreateTaskApiContractStable() throws Exception {
    TaskResponse response = TaskResponse.builder()
            .id(1L)
            .taskName("竞品分析任务")
            .status("PENDING")
            .build();

    when(analysisTaskService.createTask(any(CreateTaskRequest.class))).thenReturn(response);

    mockMvc.perform(post("/api/task/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"taskName":"竞品分析任务","subjectProduct":"产品A"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1L))
            .andExpect(jsonPath("$.data.taskName").value("竞品分析任务"))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
}
```

- [ ] **Step 4: 运行聚焦测试，确认基线现状**

Run:

```powershell
mvn -Dtest=TaskDefinitionAppServiceTest,AnalysisTaskServiceTest,TaskControllerTest test
```

Expected:

```text
Tests run: ...
Failures: 0
Errors: 0
```

- [ ] **Step 5: 如测试暴露回归，再做最小实现修正**

只允许在以下文件中做最小修正，不做额外重构：

```text
backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java
backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java
```

优先修正的代码形态应保持为：

```java
@Transactional
public TaskResponse createTask(CreateTaskRequest request) {
    return taskDefinitionAppService.createTask(request);
}
```

```java
private void ensureTaskCreationAllowed(CreateTaskRequest request) {
    QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
            GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
            GovernanceDefaults.TASK_SCOPE,
            GovernanceDefaults.TASK_CONCURRENCY_KEY,
            1,
            request == null ? List.of() : request.getCompetitorUrls()
    );
    if (decision != null && !decision.isAllowed()) {
        throw new GovernanceBlockException(decision);
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java backend/src/main/java/cn/bugstack/competitoragent/task/AnalysisTaskService.java backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskDefinitionAppService.java
git commit -m "test: lock task facade and creation governance baseline"
```

---

### Task 2: 清理 `DagExecutor` 残留运行时冗余

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeStateRefresher.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`

- [ ] **Step 1: 先写失败测试，锁定节点完成与任务终态的协作者边界**

补充测试，锁定两个关键行为：

1. 节点完成后的事件组装统一委托给 `RuntimeEventEmitter`
2. 任务状态变化后的快照刷新统一经 `RuntimeStateRefresher`

```java
@Test
void shouldDelegateCompletedNodeEventsToRuntimeEventEmitter() {
    dagExecutor.execute(taskId, agentContext);

    verify(runtimeEventEmitter, atLeastOnce())
            .publishNodeExecutionEvents(eq(taskId), any(TaskNode.class));
}
```

```java
@Test
void shouldRefreshRuntimeSnapshotWhenTaskStateChanges() {
    dagExecutor.execute(taskId, agentContext);

    verify(runtimeStateRefresher, atLeastOnce()).refreshRuntimeSnapshot(taskId);
}
```

- [ ] **Step 2: 运行执行器聚焦测试，确认当前失败点**

Run:

```powershell
mvn -Dtest=DagExecutorTest,DagExecutorWorkflowEventTest test
```

Expected:

```text
如果当前仍有内联快照/事件逻辑，新增断言至少会暴露一个失败点。
```

- [ ] **Step 3: 删除 `DagExecutor` 中重复快照构建与重复发布逻辑**

把仍然在 `DagExecutor` 内部直接构建 `TaskProgressSnapshot`、直接 `saveTaskSnapshot`、直接 `publishTaskSnapshot` 的 helper 收敛到 `RuntimeStateRefresher`。

目标代码形态应接近：

```java
if (dynamicPlanAppender.maybeAppendDynamicPlan(taskId, nodes, nodeMap, completedResult.getNode())) {
    runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
}
```

```java
runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
runtimeEventEmitter.publishNodeExecutionEvents(taskId, completedNode);
```

Phase 1 允许 `DagExecutor` 继续直接触发少量“状态切换型”事件，但不允许继续在内部拼装重复 payload。

- [ ] **Step 4: 把节点完成后 payload 组装继续收口到 `RuntimeEventEmitter`**

若 `DagExecutor` 仍有节点完成后的搜索进度、诊断、日志兜底 payload 拼装逻辑，继续向 `RuntimeEventEmitter` 收口，保持 `DagExecutor` 只声明“节点完成后发事件”。

优先保留的 `RuntimeEventEmitter` 公开入口应为：

```java
public void publishNodeExecutionEvents(Long taskId, TaskNode node) {
    taskEventPublisher.publishNodeStatusEvent(taskId, node, action);
    publishSearchProgressEventIfPresent(taskId, node);
    publishDiagnosisEventIfPresent(taskId, node);
    boolean publishedFromLog = agentLogService.publishLatestLogEvent(taskId, node.getNodeName(), node.getAgentType());
    if (!publishedFromLog) {
        publishAgentOutputFallbackEvent(taskId, node);
    }
}
```

- [ ] **Step 5: 重新运行执行器测试，确认行为未变**

Run:

```powershell
mvn -Dtest=DagExecutorTest,DagExecutorWorkflowEventTest test
```

Expected:

```text
Tests run: ...
Failures: 0
Errors: 0
```

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeStateRefresher.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java
git commit -m "refactor: remove duplicated dag runtime logic"
```

---

### Task 3: 补强 ArchUnit 模块边界

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`

- [ ] **Step 1: 先新增失败规则，覆盖当前缺失边界**

在现有基线上补以下规则：

```java
@ArchTest
static final ArchRule controllers_should_not_access_repositories =
        noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                .because("controllers should go through application services or facades.");
```

```java
@ArchTest
static final ArchRule workflow_should_not_depend_on_controller =
        noClasses()
                .that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("workflow runtime must stay below delivery adapters.");
```

```java
@ArchTest
static final ArchRule repositories_should_not_depend_on_delivery_layers =
        noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..workflow..")
                .because("repositories should not depend on delivery or orchestration layers.");
```

- [ ] **Step 2: 运行架构测试，识别真实违规项**

Run:

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

Expected:

```text
如果存在历史跨层依赖，测试会精确列出违规类。
```

- [ ] **Step 3: 仅做具名豁免或收窄作用域，不放宽整条规则**

若发现历史耦合暂时无法在 Phase 1 处理，则采用具名豁免写法，而不是删除规则。

```java
private static final String LEGACY_AGENT_REPOSITORY_EXEMPTIONS =
        BaseAgent.class.getSimpleName()
                + ", " + CompetitorAnalysisAgent.class.getSimpleName();
```

```java
.and().doNotHaveFullyQualifiedName(BaseAgent.class.getName())
.and().doNotHaveFullyQualifiedName(CompetitorAnalysisAgent.class.getName())
```

- [ ] **Step 4: 重新运行架构测试，确认边界基线固定**

Run:

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

Expected:

```text
Tests run: ...
Failures: 0
Errors: 0
```

- [ ] **Step 5: 提交**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java
git commit -m "test: harden backend module boundaries"
```

---

### Task 4: 固化运行时契约与兼容策略

**Files:**
- Create: `docs/specs/13-runtime-snapshot-contract.md`
- Create: `docs/specs/14-workflow-event-contract.md`
- Create: `docs/specs/15-search-progress-event-contract.md`
- Create: `docs/specs/16-node-attempt-and-log-contract.md`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`
- Read for context: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskProgressSnapshot.java`
- Read for context: `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java`
- Read for context: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`

- [ ] **Step 1: 先写事件与快照契约测试，锁定必需字段**

在 `DagExecutorWorkflowEventTest` 中补断言，确保当前前端已消费的关键字段保持稳定。

```java
assertThat(snapshotEvent.getPayload()).containsKeys(
        "status",
        "currentStage",
        "statusSummary",
        "completedNodes",
        "totalNodes",
        "updatedAt"
);
```

```java
assertThat(searchProgressEvent.getPayload()).containsKey("searchProgress");
assertThat(nodeStatusEvent.getPayload()).containsKeys("action", "nodeName", "status", "statusSummary");
```

- [ ] **Step 2: 运行契约相关测试，确认缺口**

Run:

```powershell
mvn -Dtest=DagExecutorWorkflowEventTest,TaskControllerTest test
```

Expected:

```text
若当前 payload 缺少必需字段或测试未覆盖，会先出现失败或覆盖缺口。
```

- [ ] **Step 3: 编写四份运行时契约文档**

四份文档都使用相同结构：`生产者`、`消费者`、`字段定义`、`必填字段`、`可选字段`、`兼容策略`、`不兼容变更禁止项`。

```markdown
## 兼容策略

- Phase 1 只允许加字段，不允许删字段
- 旧任务数据允许字段缺失
- 前端按渐进方式消费新增字段
- 不在 Phase 1 进行数据库迁移
```

`13-runtime-snapshot-contract.md` 必须覆盖：

```markdown
- taskStatus
- currentStage
- statusSummary
- completedNodes
- totalNodes
- waitingRetryNodeCount
- waitingInterventionNodeCount
- compensatedNodeCount
- activeNodeNames
- updatedAt
```

`14-workflow-event-contract.md` 必须覆盖事件类型：

```markdown
- TASK_SNAPSHOT
- TASK_STATUS
- NODE_STATUS
- SEARCH_PROGRESS
- AGENT_OUTPUT
- DIAGNOSIS
```

- [ ] **Step 4: 如测试失败，仅做兼容性修正**

只允许做以下类型修正：

```text
补字段
补空值兼容
补测试覆盖
补文档说明
```

不允许在本任务中做以下破坏性动作：

```text
删除 DTO 字段
重命名 SSE 事件类型
改变既有 payload 字段语义
引入数据库 migration
```

- [ ] **Step 5: 再跑一次契约测试**

Run:

```powershell
mvn -Dtest=DagExecutorWorkflowEventTest,TaskControllerTest test
```

Expected:

```text
Tests run: ...
Failures: 0
Errors: 0
```

- [ ] **Step 6: 提交**

```bash
git add docs/specs/13-runtime-snapshot-contract.md docs/specs/14-workflow-event-contract.md docs/specs/15-search-progress-event-contract.md docs/specs/16-node-attempt-and-log-contract.md backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java
git commit -m "docs: formalize runtime contracts and compatibility"
```

---

### Task 5: 全量回归并形成 Phase 1 收尾基线

**Files:**
- Read: `docs/plan/2026-06-09-engineering-optimization-strategy.md`
- Read: `docs/plan/2026-06-09-phase1-stabilization-implementation-plan.md`
- Verify: `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`
- Verify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- Verify: `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskControllerTest.java`
- Verify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Verify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`

- [ ] **Step 1: 先运行聚焦回归**

Run:

```powershell
mvn -Dtest=BackendModuleDependencyTest,AnalysisTaskServiceTest,TaskControllerTest,DagExecutorTest,DagExecutorWorkflowEventTest test
```

Expected:

```text
Tests run: ...
Failures: 0
Errors: 0
```

- [ ] **Step 2: 再运行后端完整测试**

Run:

```powershell
mvn test
```

Expected:

```text
BUILD SUCCESS
```

如果存在已知无关失败，必须把失败项、原因和是否阻塞 Phase 1 收尾写入实施记录。

- [ ] **Step 3: 对照验收标准做人工检查**

逐项检查以下结论，并写到实施说明或 PR 描述中：

```markdown
- AnalysisTaskService 仍为 facade
- DagExecutor 不再保留重复快照构建与重复 payload 组装
- ArchUnit 规则已覆盖目标边界
- /api/task 契约未破坏
- SSE 事件类型未变
- 恢复 / 重试 / 重跑行为保持兼容
- Phase 1 未引入数据库 migration
```

- [ ] **Step 4: 提交最终收尾结果**

```bash
git add .
git commit -m "chore: finalize phase1 stabilization baseline"
```

