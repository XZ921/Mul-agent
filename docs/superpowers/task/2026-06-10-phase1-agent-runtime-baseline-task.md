# Phase 1 Agent Runtime Baseline Task

## 核心目标

在不搬包、不重写业务 Agent 的前提下，冻结 `agent-runtime` 的最小运行时边界，确保后续 `task-orchestration` 与 `collection-intelligence` 的重构都建立在同一套 Agent SPI 基线上。

## 预期耗时

- `0.5 - 1` 人天

## 前置依赖

- 无
- 允许直接承接当前代码基线实施

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase1-agent-runtime-baseline-progress.md`

## 完成定义

- `AgentContext`、`AgentResult`、`AgentExecutionRequest`、`AgentExecutionResponse` 的字段合同被测试锁定
- `SpringAgentCapabilityRegistry.resolve()` 继续保持当前 `null-return` 兼容语义
- `DagExecutor` 缺少 capability 时仍由执行器收口为节点失败，而不是在 registry 层抛异常
- `BackendModuleDependencyTest` 新增 phase1 runtime 基线规则并通过
- 关键回归测试通过，且未改动 `BaseAgent`、业务 Agent 主逻辑和包结构

## 文件边界

### Must Modify

- `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionRequest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/AgentExecutionResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistry.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/AgentRuntimeContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/capability/SpringAgentCapabilityRegistryTest.java`

### May Modify

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorWorkflowEventTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java`

### Read For Context

- `backend/src/main/java/cn/bugstack/competitoragent/agent/BaseAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/Agent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/enums/AgentType.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/context/AgentContextAssemblerTest.java`

## 统一执行要求

- 本阶段不允许引入 `fail-fast resolve()` 行为回归
- 本阶段不允许新增 `AgentContext` 业务字段来规避后续 facade 设计
- 每完成一个 Task，都要先更新 progress 文件，再进入下一个 Task

---

## Task 1: 冻结 runtime 合同

### Task 核心目标

用反射合同测试锁定 `AgentContext`、`AgentResult` 与 capability request/response 的最小字段集和依赖边界。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- 无

### 执行步骤

- [ ] Step 1：创建 `AgentRuntimeContractTest`，锁定字段集合与非法依赖
- [ ] Step 2：给 `AgentContext`、`AgentResult`、`AgentExecutionRequest`、`AgentExecutionResponse` 补充中文边界注释
- [ ] Step 3：运行聚焦测试，记录当前基线

### 最小测试结构

```java
@Test
void agentContext_should_keep_only_runtime_fields() {
    Set<String> actual = Arrays.stream(AgentContext.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());

    assertEquals(Set.of(
            "taskId",
            "taskName",
            "subjectProduct",
            "competitorNames",
            "competitorUrls",
            "analysisDimensions",
            "sourceScope",
            "reportLanguage",
            "reportTemplate",
            "currentNodeName",
            "currentNodeConfig",
            "traceId",
            "planVersionId",
            "branchKey",
            "taskRagContextBundle",
            "sharedState",
            "createdAt"
    ), actual);
}
```

```java
@Test
void agentContext_should_not_reference_repositories_or_entities() {
    for (Field field : AgentContext.class.getDeclaredFields()) {
        Package fieldPackage = field.getType().getPackage();
        String packageName = fieldPackage == null ? "" : fieldPackage.getName();
        assertFalse(packageName.contains(".repository"));
        assertFalse(packageName.contains(".model.entity"));
    }
}
```

### 验证命令

```powershell
mvn -Dtest=AgentRuntimeContractTest test
```

---

## Task 2: 锁定 registry 兼容语义与缺 capability 回归

### Task 核心目标

确认 `SpringAgentCapabilityRegistry` 是唯一运行时入口，但 phase1 不修改它的缺能力处理方式；缺能力仍由 `DagExecutor` 收口成节点失败。

### Task 预期耗时

- `2 - 4` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：为 `SpringAgentCapabilityRegistry` 增加正常路由测试，锁定 `AgentType -> AgentCapability` 的适配路径
- [ ] Step 2：新增 `DagExecutorRuntimeDependencyTest`，验证执行器依赖 registry 而不是 `List<Agent>`
- [ ] Step 3：在 `DagExecutorTest` 或 `DagExecutorWorkflowEventTest` 中新增“缺少 capability 时节点失败”的回归测试
- [ ] Step 4：在 `SpringAgentCapabilityRegistry` 注释中写明 phase1 保持 `null-return` 兼容语义，不做 fail-fast

### 必须保留的兼容实现

```java
@Override
public AgentCapability resolve(AgentType agentType) {
    return capabilities.get(agentType);
}
```

### 缺能力回归测试最小结构

```java
@Test
void should_fail_node_when_capability_is_missing() {
    AnalysisTask task = runningTask(101L);
    TaskNode collectNode = pendingCollectorNode(task.getId(), "collect_sources");
    when(agentCapabilityRegistry.resolve(AgentType.COLLECTOR)).thenReturn(null);
    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId())).thenReturn(List.of(collectNode));

    dagExecutor.execute(task, List.of(collectNode));

    assertEquals(TaskNodeStatus.FAILED, collectNode.getStatus());
    assertTrue(collectNode.getErrorMessage().contains("capability"));
    verify(taskNodeRepository).save(collectNode);
}
```

### Registry 路由测试最小结构

```java
@Test
void should_resolve_agent_type_through_runtime_capability() {
    SpringAgentCapabilityRegistry registry =
            new SpringAgentCapabilityRegistry(List.of(new DemoCollectorAgent()));

    AgentExecutionResponse response = registry.resolve(AgentType.COLLECTOR)
            .execute(new AgentExecutionRequest(AgentContext.builder()
                    .taskId(1L)
                    .taskName("runtime-test")
                    .currentNodeName("collect")
                    .build()));

    assertEquals(TaskNodeStatus.SUCCESS, response.result().getStatus());
    assertEquals("collector-output", response.result().getOutputData());
}
```

### 验证命令

```powershell
mvn "-Dtest=SpringAgentCapabilityRegistryTest,DagExecutorRuntimeDependencyTest,DagExecutorTest,DagExecutorWorkflowEventTest" test
```

---

## Task 3: 为 runtime baseline 增加 ArchUnit 守卫

### Task 核心目标

在不搬包的前提下，让 phase1 runtime 边界先被 `BackendModuleDependencyTest` 守住。

### Task 预期耗时

- `1 - 2` 小时

### Task 前置依赖

- Task 1 完成
- Task 2 完成

### 执行步骤

- [ ] Step 1：在 `BackendModuleDependencyTest` 中新增 runtime baseline 不得依赖 `repository` / `entity` 的规则
- [ ] Step 2：增加 `workflow` 不得直接依赖业务 Agent 实现包的规则
- [ ] Step 3：若发现历史耦合，只允许做“具名类级豁免”，禁止整包豁免

### 最小规则形状

```java
@ArchTest
static final ArchRule runtime_baseline_should_not_depend_on_repositories_or_entities =
        noClasses()
                .that().haveFullyQualifiedName("cn.bugstack.competitoragent.agent.Agent")
                .or().haveFullyQualifiedName("cn.bugstack.competitoragent.agent.AgentContext")
                .or().haveFullyQualifiedName("cn.bugstack.competitoragent.agent.AgentResult")
                .or().haveFullyQualifiedName("cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry")
                .or().haveFullyQualifiedName("cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..repository..", "..model.entity..");
```

### 验证命令

```powershell
mvn "-Dtest=BackendModuleDependencyTest" test
```
