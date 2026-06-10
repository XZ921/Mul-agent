# Phase 4B Report Conversation Task

## 核心目标

把 `report-delivery` 与 `conversation-entry` 收口到稳定 facade 和消费视图，避免 report 与 conversation 继续直接依赖 collection、workflow、runtime 内部实现。

## 预期耗时

- `1` 人天

## 前置依赖

- `phase1-agent-runtime-baseline-task` 已完成
- `phase2-archunit-boundary-task` 已完成
- `phase3a-task-orchestration-task` 已完成
- `phase3b-collection-evidence-task` 已完成
- `phase4a-knowledge-intelligence-task` 已完成
- `phase4b` 必须等待 `phase4a` 合入共享集成线后串行开始，避免 `ConversationService` 在 facade 尚未稳定时提前切调用

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase4b-report-conversation-progress.md`

## 完成定义

- 存在真实消费者驱动的 `ReportQueryFacade`
- `ReportService` 只消费稳定证据投影视图，不新增 collection 实现依赖
- `ConversationService` 开始切到 task / knowledge / report facade
- conversation 迁移状态能够区分“加依赖 / 切调用 / 删旧依赖”三步

## 文件边界

### Must Modify

- `backend/src/main/java/cn/bugstack/competitoragent/report/application/ReportQueryFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/application/ReportQueryFacadeImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/ConversationService.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/ConversationServiceTest.java`

### May Modify

- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportDiagnosisAssemblerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/IntentRecognitionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/conversation/TaskActionTranslatorTest.java`

### Read For Context

- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskRuntimeFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/application/TaskQueryFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/knowledge/application/KnowledgeRetrievalFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/application/CollectionEvidenceFacade.java`

---

## Task 1: 建立 `ReportQueryFacade`

### Task 核心目标

在存在真实消费者的前提下，为 report 读路径建立 facade，而不是凭空创建“无人消费的透传层”。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- phase4a 完成并合入共享集成线

### 执行步骤

- [ ] Step 1：定义 `ReportQueryFacade`。
- [ ] Step 2：只有当 `ConversationService` 或其他真实消费者切换到 facade 时，才创建 `ReportQueryFacadeImpl`。
- [ ] Step 3：补 facade 聚焦测试，锁定稳定读路径。

### 最小接口形状

```java
public interface ReportQueryFacade {

    ReportResponse getReport(Long taskId);

    byte[] exportMarkdown(Long taskId);

    byte[] exportHtml(Long taskId);
}
```

### 最小测试结构

```java
@Test
void should_expose_report_read_through_facade() {
    ReportResponse response = ReportResponse.builder().taskId(1L).build();
    when(reportService.getReport(1L)).thenReturn(response);
    when(exportPackageService.exportMarkdown(1L)).thenReturn("# report".getBytes(StandardCharsets.UTF_8));

    assertSame(response, facade.getReport(1L));
    assertTrue(new String(facade.exportMarkdown(1L), StandardCharsets.UTF_8).contains("report"));
}
```

### 验证命令

```powershell
mvn -Dtest=ReportServiceTest,ExportPackageServiceTest test
```

---

## Task 2: 锁定 report 只消费证据投影视图

### Task 核心目标

防止 report 线继续反向依赖 collection 运行时实现。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- Task 1 完成
- phase3b 完成

### 执行步骤

- [ ] Step 1：为 `ReportService` 增加“只依赖 `EvidenceQueryService` 证据投影视图”的测试。
- [ ] Step 2：在 `EvidenceQueryService` 顶部补边界注释，明确它是投影服务而不是采集运行时入口。

### 最小测试结构

```java
@Test
void report_service_should_rely_on_evidence_projection_not_collection_runtime() {
    ReportResponse.EvidenceInfo evidence = new ReportResponse.EvidenceInfo(
            "E001",
            "Docs",
            "https://docs.example.com",
            "snippet",
            "Notion AI",
            LocalDateTime.now(),
            "DOCS",
            "SEARCH",
            "docs.example.com",
            "reason",
            null,
            0.91,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            Map.of()
    );
    when(evidenceQueryService.listEvidences(1L, null, null, null)).thenReturn(List.of(evidence));

    ReportResponse response = reportService.getReport(1L);

    assertNotNull(response);
    verify(evidenceQueryService).listEvidences(1L, null, null, null);
}
```

### 验证命令

```powershell
mvn -Dtest=EvidenceQueryServiceTest,ReportDiagnosisAssemblerTest,ReportServiceTest test
```

---

## Task 3: 让 `ConversationService` 经 facade 访问任务与知识信息

### Task 核心目标

逐步把 conversation 从 `AnalysisTaskService`、`TaskRetrievalService` 直连中迁出。

### Task 预期耗时

- `3 - 4` 小时

### Task 前置依赖

- Task 1、Task 2 完成
- phase3a、phase4a 完成

### 执行步骤

- [ ] Step 1：给 `ConversationService` 添加 `TaskQueryFacade`、`TaskRuntimeFacade`、`KnowledgeRetrievalFacade`、`ReportQueryFacade` 依赖。
- [ ] Step 2：按“加依赖 -> 切调用 -> 删旧依赖”三步迁移，至少在 progress 文档中记录当前停留在哪一步。
- [ ] Step 3：补 conversation 聚焦测试，锁定任务详情读取和 research 模式读取都经 facade。

### 目标依赖形状

```java
private final TaskQueryFacade taskQueryFacade;
private final TaskRuntimeFacade taskRuntimeFacade;
private final KnowledgeRetrievalFacade knowledgeRetrievalFacade;
private final ReportQueryFacade reportQueryFacade;
```

### 最小测试结构

```java
@Test
void should_read_task_views_through_task_query_facade() {
    when(taskQueryFacade.getTask(88L)).thenReturn(TaskResponse.builder()
            .id(88L)
            .taskName("统一对话任务")
            .currentStage("报告撰写")
            .statusSummary("当前等待系统解释任务卡点")
            .build());
    when(taskQueryFacade.getTaskNodes(88L)).thenReturn(List.of());

    ConversationResponse response = conversationService.handleMessage(buildExplainRequest(88L));

    assertNotNull(response);
    verify(taskQueryFacade).getTask(88L);
    verify(taskQueryFacade).getTaskNodes(88L);
}
```

```java
@Test
void should_build_research_response_through_knowledge_facade() {
    when(knowledgeRetrievalFacade.retrieveForTask(88L, "这个任务有哪些公开证据？", "conversation"))
            .thenReturn(new RetrievalResultView(
                    List.of("https://docs.example.com"),
                    "仍缺少定价证据",
                    "已检索到公开文档",
                    List.of("TASK-DOC-001")
            ));

    ConversationResponse response = conversationService.handleMessage(buildResearchRequest(88L));

    assertNotNull(response);
    verify(knowledgeRetrievalFacade).retrieveForTask(88L, "这个任务有哪些公开证据？", "conversation");
}
```

### 验证命令

```powershell
mvn -Dtest=ConversationServiceTest,IntentRecognitionServiceTest,TaskActionTranslatorTest,ConversationClarificationFlowTest test
```

---

## Task 4: 阶段收尾

### Task 核心目标

确认 phase4b 只做 report / conversation 收口，不扩大成 intent、translator 或 Agent 主逻辑重写。

### Task 预期耗时

- `1` 小时

### Task 前置依赖

- Task 1 - Task 3 完成

### 验证命令

```powershell
mvn -Dtest=ReportServiceTest,EvidenceQueryServiceTest,ReportDiagnosisAssemblerTest,ExportPackageServiceTest test
mvn -Dtest=ConversationServiceTest,IntentRecognitionServiceTest,TaskActionTranslatorTest,ConversationClarificationFlowTest test
```

### 提交标准

- 只包含真实消费者驱动的 report facade、report 投影视图边界与 conversation facade 迁移
- PR 描述必须明确当前停留在“加依赖 / 切调用 / 删旧依赖”的哪一步
