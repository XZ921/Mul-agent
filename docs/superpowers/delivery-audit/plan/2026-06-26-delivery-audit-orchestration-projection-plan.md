# Delivery Audit Orchestration Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入 4.x runtime 改造的前提下，把最近一次协作决策稳定投影到 `report / export / replay` 主路径，并为 SSE replay 预留轻量解释入口。

**Architecture:** 复用“从最近一次 `ORCHESTRATION_DECISION_RECORDED` 事件提取稳定只读视图”的思路，新建一个独立于 `conversation` 的编排决策查询服务，统一输出可复用摘要 DTO。`ReportService`、`ReportExportRenderer` 与 `TaskReplayProjectionService` 只消费这个只读投影，不重算编排规则，不改写决策生成逻辑。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Jackson, JUnit 5, AssertJ, existing `orchestration / report / task replay / event` modules.

---

### Task 1: 固化协作决策只读投影契约

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionQueryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReplayTimelineEvent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

- [ ] **Step 1: 先写报告主路径缺失协作决策摘要的失败测试**

```java
@Test
void shouldExposeLatestOrchestrationDecisionInReportMainPath() {
    TaskWorkflowEvent decisionEvent = TaskWorkflowEvent.builder()
            .taskId(720L)
            .nodeName("quality_check_final")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .payload("""
                    {
                      "decision": {
                        "decisionId": "od-720-review",
                        "triggerNodeName": "quality_check_final",
                        "decisionType": "WAIT_FOR_HUMAN",
                        "actionType": "MANUAL_REVIEW",
                        "targetNode": "quality_check_final",
                        "reason": "终审发现关键信息缺少来源，需要人工确认",
                        "requiresHumanIntervention": true,
                        "requiresConfirmation": false,
                        "evidenceState": "MISSING_SOURCE",
                        "sourceUrls": ["https://docs.example.com/review-gap"]
                      }
                    }
                    """)
            .sourceUrls("[\"https://docs.example.com/review-gap\"]")
            .build();

    when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(720L))
            .thenReturn(Optional.of(decisionEvent));

    ReportResponse response = reportService.getReport(720L);

    assertNotNull(response.getOrchestrationDecision());
    assertEquals("WAIT_FOR_HUMAN", response.getOrchestrationDecision().getDecisionType());
    assertEquals("MISSING_SOURCE", response.getOrchestrationDecision().getEvidenceState());
    assertTrue(response.getSourceUrls().contains("https://docs.example.com/review-gap"));
}
```

- [ ] **Step 2: 跑报告测试，确认它先因为字段/服务缺失而失败**

Run:

```bash
cd backend
mvn -Dtest=ReportServiceTest#shouldExposeLatestOrchestrationDecisionInReportMainPath test
```

Expected: FAIL，提示 `ReportResponse` 缺少协作决策摘要字段或 `ReportService` 尚未投影该数据。

- [ ] **Step 3: 再写回放主路径缺失协作决策摘要的失败测试**

```java
@Test
void shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath() {
    TaskWorkflowEvent event = TaskWorkflowEvent.builder()
            .taskId(120L)
            .nodeName("quality_check_final")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .payload("""
                    {
                      "decision": {
                        "decisionId": "od-120-review",
                        "triggerNodeName": "quality_check_final",
                        "decisionType": "WAIT_FOR_HUMAN",
                        "actionType": "MANUAL_REVIEW",
                        "reason": "终审阻塞，等待人工补证",
                        "evidenceState": "MISSING_SOURCE",
                        "sourceUrls": ["https://docs.example.com/replay-gap"]
                      }
                    }
                    """)
            .sourceUrls("[\"https://docs.example.com/replay-gap\"]")
            .createdAt(LocalDateTime.of(2026, 6, 26, 18, 0))
            .build();

    when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(event));

    TaskReplayResponse replay = projectionService.getTaskReplay(120L);

    assertNotNull(replay.getLatestOrchestrationDecision());
    assertEquals("WAIT_FOR_HUMAN", replay.getLatestOrchestrationDecision().getDecisionType());
    assertNotNull(replay.getTimeline().get(0).getOrchestrationDecision());
    assertTrue(replay.getTimeline().get(0).getSummary().contains("WAIT_FOR_HUMAN"));
}
```

- [ ] **Step 4: 跑回放测试，确认它先失败**

Run:

```bash
cd backend
mvn -Dtest=TaskReplayProjectionServiceTest#shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath test
```

Expected: FAIL，提示 `TaskReplayResponse` / `ReplayTimelineEvent` 尚未暴露协作决策摘要。

- [ ] **Step 5: 补协作决策查询服务与共享 DTO 的最小实现**

```java
public Optional<OrchestrationDecisionSummary> findLatestDecision(Long taskId) {
    if (taskId == null) {
        return Optional.empty();
    }
    return taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(taskId)
            .flatMap(this::extractSummary);
}

public Optional<OrchestrationDecisionSummary> extractSummary(TaskWorkflowEvent event) {
    JsonNode payloadNode = readJson(event == null ? null : event.getPayload());
    JsonNode decisionNode = payloadNode == null ? null : payloadNode.path("decision");
    if (decisionNode == null || decisionNode.isMissingNode() || decisionNode.isNull() || !decisionNode.isObject()) {
        decisionNode = payloadNode;
    }
    if (decisionNode == null || !decisionNode.isObject()) {
        return Optional.empty();
    }
    return Optional.of(OrchestrationDecisionSummary.builder()
            .decisionId(textValue(decisionNode.get("decisionId")))
            .taskId(longValue(decisionNode.get("taskId"), event.getTaskId()))
            .triggerNodeName(firstNonBlank(textValue(decisionNode.get("triggerNodeName")), event.getNodeName()))
            .decisionType(textValue(decisionNode.get("decisionType")))
            .actionType(textValue(decisionNode.get("actionType")))
            .targetNode(textValue(decisionNode.get("targetNode")))
            .affectedScope(textValue(decisionNode.get("affectedScope")))
            .reason(firstNonBlank(textValue(decisionNode.get("reason")), textValue(payloadNode.get("summary"))))
            .requiresHumanIntervention(booleanValue(decisionNode.get("requiresHumanIntervention"), false))
            .requiresConfirmation(nullableBooleanValue(decisionNode.get("requiresConfirmation")))
            .evidenceState(firstNonBlank(textValue(decisionNode.get("evidenceState")), textValue(payloadNode.get("evidenceState"))))
            .sourceUrls(mergeSourceUrls(readStringList(decisionNode.get("sourceUrls")), parseJsonStringList(event.getSourceUrls())))
            .build()
            .normalized());
}
```

- [ ] **Step 6: 跑两条测试，确认共享契约已可被 report / replay 复用**

Run:

```bash
cd backend
mvn "-Dtest=ReportServiceTest#shouldExposeLatestOrchestrationDecisionInReportMainPath,TaskReplayProjectionServiceTest#shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath" test
```

Expected: PASS。

### Task 2: 把协作决策摘要挂到 report / export 主路径

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ExportPackageService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java`

- [ ] **Step 1: 先写导出包缺失协作决策摘要的失败测试**

```java
assertRenderedTextPackage(
        markdownPackage,
        "text/markdown; charset=UTF-8",
        "competitor-analysis-report-v1.md",
        List.of(
                "## 协作决策摘要",
                "WAIT_FOR_HUMAN",
                "https://docs.example.com/review-gap"));

JsonNode packageNode = objectMapper.readTree(jsonBody);
assertEquals("WAIT_FOR_HUMAN", packageNode.path("orchestrationDecision").path("decisionType").asText());
assertEquals("MISSING_SOURCE", packageNode.path("orchestrationDecision").path("evidenceState").asText());
```

- [ ] **Step 2: 跑导出测试，确认它先失败**

Run:

```bash
cd backend
mvn -Dtest=ExportPackageServiceTest#shouldRenderMarkdownHtmlAndJsonPackagesWithEvidenceAndAuditSummary test
```

Expected: FAIL，导出内容中尚未出现协作决策摘要。

- [ ] **Step 3: 在 ReportService 中投影最近一次协作决策，并并入顶层 sourceUrls**

```java
OrchestrationDecisionSummary orchestrationDecision =
        orchestrationDecisionQueryService.findLatestDecision(taskId).orElse(null);

return ReportResponse.builder()
        // ...
        .orchestrationDecision(orchestrationDecision)
        .sourceUrls(collectReportSourceUrls(
                evidenceInfos,
                reportDiagnosis,
                deliverySummary,
                evidenceEntryPoint,
                auditSummary,
                orchestrationDecision))
        .build();
```

- [ ] **Step 4: 在三种导出格式里增加“协作决策摘要”只读展示**

```java
payload.put("orchestrationDecision", report.getOrchestrationDecision() == null
        ? Map.of()
        : Map.of(
                "decisionId", ReportExportRenderSupport.decisionId(report),
                "decisionType", ReportExportRenderSupport.decisionType(report),
                "actionType", ReportExportRenderSupport.actionType(report),
                "reason", ReportExportRenderSupport.decisionReason(report),
                "evidenceState", ReportExportRenderSupport.decisionEvidenceState(report),
                "sourceUrls", ReportExportRenderSupport.decisionSourceUrls(report)));
```

- [ ] **Step 5: 更新导出记录聚合 sourceUrls，确保协作决策来源也能离线追溯**

```java
if (report.getOrchestrationDecision() != null) {
    merged.addAll(report.getOrchestrationDecision().getSourceUrls());
}
```

- [ ] **Step 6: 跑报告与导出测试，确认 report / export 主路径都拿到协作决策摘要**

Run:

```bash
cd backend
mvn "-Dtest=ReportServiceTest,ExportPackageServiceTest" test
```

Expected: PASS。

### Task 3: 把协作决策摘要挂到 replay 主路径，并为 SSE replay 补轻量解释

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`

- [ ] **Step 1: 先写 SSE replay 缺失协作决策摘要的失败测试**

```java
TaskStreamEvent orchestrationReplayEvent = taskSseHub.publish(TaskStreamEvent.builder()
        .taskId(24L)
        .eventType(TaskEventType.DIAGNOSIS)
        .nodeName("quality_check_final")
        .payload(Map.of(
                "decisionId", "od-24-review",
                "decisionType", "WAIT_FOR_HUMAN",
                "actionType", "MANUAL_REVIEW",
                "reason", "终审发现缺少来源，需要人工确认",
                "evidenceState", "MISSING_SOURCE",
                "sourceUrls", List.of("https://docs.example.com/replay-gap")))
        .build());

TaskReplayFrame frame = replayService.planReplay(24L, null);

assertNotNull(frame.getLatestOrchestrationDecision());
assertEquals("WAIT_FOR_HUMAN", frame.getLatestOrchestrationDecision().getDecisionType());
```

- [ ] **Step 2: 跑 SSE replay 测试，确认它先失败**

Run:

```bash
cd backend
mvn -Dtest=TaskEventReplayServiceTest#shouldExposeLatestOrchestrationDecisionForReplayFrame test
```

Expected: FAIL，`TaskReplayFrame` 尚未携带轻量协作决策摘要。

- [ ] **Step 3: 在数据库 replay 主路径中挂上 top-level latest decision 与 timeline 决策详情**

```java
OrchestrationDecisionSummary latestOrchestrationDecision = timeline.stream()
        .map(ReplayTimelineEvent::getOrchestrationDecision)
        .filter(Objects::nonNull)
        .reduce((first, second) -> second)
        .orElse(null);

return TaskReplayResponse.builder()
        // ...
        .latestOrchestrationDecision(latestOrchestrationDecision)
        .build();
```

- [ ] **Step 4: 仅为 SSE replay 做轻量增强，不引入新的运行时事件类型**

```java
private OrchestrationDecisionSummary resolveLatestOrchestrationDecision(List<TaskStreamEvent> replayEvents) {
    return replayEvents.stream()
            .map(this::extractDecisionSummary)
            .filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);
}
```

- [ ] **Step 5: 跑 replay 测试，确认数据库回放和 SSE replay 都能解释协作决策**

Run:

```bash
cd backend
mvn "-Dtest=TaskReplayProjectionServiceTest,TaskEventReplayServiceTest" test
```

Expected: PASS。

### Task 4: 回归验证与文档进度更新

**Files:**
- Modify: `docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap-progress.md`

- [ ] **Step 1: 运行本轮最小回归集合**

Run:

```bash
cd backend
mvn "-Dtest=ReportServiceTest,ExportPackageServiceTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest" test
```

Expected: PASS。

- [ ] **Step 2: 跑格式检查，确认没有补丁错误**

Run:

```bash
git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/model/dto/OrchestrationDecisionSummary.java backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionQueryService.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReplayTimelineEvent.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java backend/src/main/java/cn/bugstack/competitoragent/report/ExportPackageService.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/report/ExportPackageServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java docs/superpowers/delivery-audit/plan/2026-06-26-delivery-audit-orchestration-projection-plan.md docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap-progress.md
```

Expected: PASS。

- [ ] **Step 3: 更新路线图进度**

```text
当前阶段：交付与审计链路协作决策只读投影
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告 / 导出投影：已完成
- [x] 回放 / SSE 投影：已完成
```

Plan complete and saved to `docs/superpowers/delivery-audit/plan/2026-06-26-delivery-audit-orchestration-projection-plan.md`.  
本轮已明确选择 Inline Execution，我将继续在当前会话里直接按这个计划执行。
