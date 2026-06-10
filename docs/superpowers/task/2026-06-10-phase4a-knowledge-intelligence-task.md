# Phase 4A Knowledge Intelligence Task

## 核心目标

把 `knowledge-intelligence` 收口到 `KnowledgeRetrievalFacade` 和知识域 contract 映射，让检索、RAG 上下文装配与知识侧跨模块输出具备稳定入口。

## 预期耗时

- `1` 人天

## 前置依赖

- `phase1-agent-runtime-baseline-task` 已完成
- `phase2-archunit-boundary-task` 已完成
- `phase3a-task-orchestration-task` 已完成
- `phase3b-collection-evidence-task` 已完成
- `phase4a` 必须在 `phase3b` 合入共享集成线后串行开工，避免 knowledge contract 映射与 evidence 边界收口交叉返工

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`

## 完成定义

- 存在 `KnowledgeRetrievalFacade`
- facade 不直接暴露 `TaskRetrievalService.RetrievalResult`
- `AgentContextAssembler` 明确只回写 `TaskRagContextBundle`
- `workflow.contract` 中知识归属对象已有映射文档
- 不新增第二套 knowledge contract 并行模型

## 文件边界

### Must Modify

- `backend/src/main/java/cn/bugstack/competitoragent/knowledge/application/KnowledgeRetrievalFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/knowledge/application/KnowledgeRetrievalFacadeImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/context/AgentContextAssembler.java`
- `backend/src/test/java/cn/bugstack/competitoragent/knowledge/KnowledgeDocumentQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/rag/TaskRetrievalServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/context/AgentContextAssemblerTest.java`
- `docs/superpowers/task/2026-06-10-knowledge-contract-mapping.md`

### May Modify

- `backend/src/main/java/cn/bugstack/competitoragent/knowledge/KnowledgeDocumentQueryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/rag/TaskRetrievalService.java`

### Read For Context

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`

---

## Task 1: 建立 `KnowledgeRetrievalFacade`

### Task 核心目标

为 knowledge 侧跨模块读取建立稳定 facade，避免其他模块继续依赖检索服务内部结果类型。

### Task 预期耗时

- `3 - 4` 小时

### Task 前置依赖

- phase1、phase2、phase3a、phase3b 完成

### 执行步骤

- [ ] Step 1：定义 `KnowledgeRetrievalFacade` 与 `RetrievalResultView`。
- [ ] Step 2：用 `KnowledgeDocumentQueryService`、`TaskRetrievalService` 包装实现。
- [ ] Step 3：补充 facade 聚焦测试，锁定字段投影。

### 最小接口形状

```java
public interface KnowledgeRetrievalFacade {

    List<KnowledgeDocumentResponse> listTaskKnowledge(Long taskId);

    RetrievalResultView retrieveForTask(Long taskId, String query, String nodeName);

    String summarizeTaskRagContext(Long taskId, String query, String nodeName);
}
```

```java
public record RetrievalResultView(
        List<String> sourceUrls,
        String gapSummary,
        String answer,
        List<String> hitDocumentIds
) {
}
```

### 最小测试结构

```java
@Test
void should_list_task_knowledge_through_facade() {
    KnowledgeDocumentResponse response = KnowledgeDocumentResponse.builder()
            .knowledgeDocumentId(1L)
            .title("治理手册")
            .build();
    when(knowledgeDocumentQueryService.listByTaskId(5L)).thenReturn(List.of(response));

    List<KnowledgeDocumentResponse> actual = facade.listTaskKnowledge(5L);

    assertEquals(1, actual.size());
    assertEquals("治理手册", actual.get(0).getTitle());
}
```

```java
@Test
void should_expose_retrieval_result_through_knowledge_facade() {
    TaskRetrievalService.RetrievalResult result = TaskRetrievalService.RetrievalResult.builder()
            .sourceUrls(List.of("https://docs.example.com/guide"))
            .gapSummary("仍缺少 SLA 细节")
            .answer("检索到治理与发布规范")
            .hitDocumentIds(List.of("TASK-DOC-001"))
            .build();
    when(taskRetrievalService.retrieve(5L, "治理规范", "analyze_competitors")).thenReturn(result);

    RetrievalResultView actual = facade.retrieveForTask(5L, "治理规范", "analyze_competitors");

    assertEquals(List.of("https://docs.example.com/guide"), actual.sourceUrls());
    assertEquals("仍缺少 SLA 细节", actual.gapSummary());
    assertEquals(List.of("TASK-DOC-001"), actual.hitDocumentIds());
}
```

### 验证命令

```powershell
mvn -Dtest=KnowledgeDocumentQueryServiceTest,TaskRetrievalServiceTest test
```

---

## Task 2: 锁定 `AgentContextAssembler` 只输出运行时摘要

### Task 核心目标

防止 `AgentContext` 继续膨胀成跨模块共享大对象。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：在 `AgentContextAssemblerTest` 中补充“只回写 `TaskRagContextBundle`”的测试。
- [ ] Step 2：在 `AgentContextAssembler` 类注释中明确禁止把 `KnowledgeDocument`、`RetrievalChunk`、`MemorySnapshot` 之类业务集合直接塞进 `AgentContext`。

### 最小测试结构

```java
@Test
void should_only_write_task_rag_context_bundle_back_to_agent_context() {
    AgentContext context = AgentContext.builder()
            .taskId(1L)
            .currentNodeName("analyze_competitors")
            .build();

    AgentContext assembled = newAssembler().assemble(context);

    assertNotNull(assembled.getTaskRagContextBundle());
    assertEquals(
            Set.of(
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
            ),
            Arrays.stream(AgentContext.class.getDeclaredFields())
                    .map(Field::getName)
                    .collect(Collectors.toSet())
    );
}
```

### 验证命令

```powershell
mvn -Dtest=AgentContextAssemblerTest test
```

---

## Task 3: 固化 knowledge contract 归属表

### Task 核心目标

先把 `workflow.contract` 中属于 knowledge 的对象归属写清楚，避免 phase4 后继续新增模糊共享类型。

### Task 预期耗时

- `1 - 2` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：创建 `2026-06-10-knowledge-contract-mapping.md`。
- [ ] Step 2：记录 `ExtractResult`、`CompetitorKnowledgeDraft`、`AnalysisResult` 的 future owner 和阶段约束。
- [ ] Step 3：写明 phase4a 不允许再新增新的 knowledge 平行 contract。

### 文档最小形状

```markdown
| Legacy Contract | Future Owner | Phase | Notes |
| --- | --- | --- | --- |
| ExtractResult | knowledge-intelligence | phase4a | 保留 sourceUrls / issueFlags / evidenceFragments / sectionEvidenceBundles |
| CompetitorKnowledgeDraft | knowledge-intelligence | phase4a | 保留 evidenceCoverage 与章节证据束 |
| AnalysisResult | knowledge-intelligence | phase4a | 保留 taskRagContext / sourceUrls / issueFlags |
```

### 验证命令

```powershell
mvn -Dtest=KnowledgeIngestionServiceTest,KnowledgeDocumentQueryServiceTest,TaskRetrievalServiceTest,TaskRetrievalIndexServiceTest,TaskRerankServiceTest,AgentContextAssemblerTest test
```

---

## Task 4: 阶段收尾

### Task 核心目标

确认 phase4a 只收口 knowledge 读接口与 contract 归属，不提前拆 analysis-intelligence 或修改 BaseAgent。

### Task 预期耗时

- `1` 小时

### Task 前置依赖

- Task 1 - Task 3 完成

### 验证命令

```powershell
mvn -Dtest=KnowledgeIngestionServiceTest,KnowledgeDocumentQueryServiceTest,TaskRetrievalServiceTest,TaskRetrievalIndexServiceTest,TaskRerankServiceTest,AgentContextAssemblerTest test
```

### 提交标准

- 只包含 knowledge facade、AgentContextAssembler 边界测试与 knowledge contract mapping
- 不混入 `BaseAgent` 重构、analysis-intelligence 再拆分或 workflow 实现改造
