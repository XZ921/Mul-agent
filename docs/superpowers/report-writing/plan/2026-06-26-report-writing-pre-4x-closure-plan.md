# Report Writing Pre-4.x Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入 4.x、不接 Tavily、不扩大 `pendingActions` 投影的前提下，把 ReportWriting 链路收口为“写作证据事实可持久化、可查询、可导出解释、可回归保护”的正式交付资产。

**Architecture:** 本方案只沉淀 Writer 已经产出的运行态事实：`writerEvidenceState / citationGapSeverity / sectionCitationGaps / sourceUrls`。落点按 `Report` 持久化快照、`ReportService` 查询投影、`ReportExportRenderer` 导出解释和 `*SnapshotContractTest` 测试基线推进；不改变 DAG、Orchestrator 决策生成、动态补图、Analyzer 推理逻辑或 Writer LLM 写作策略。

**Tech Stack:** Java 17, Spring Boot 3.3.5, JPA, Flyway, Jackson, JUnit 5, AssertJ, existing `agent.writer / workflow.contract / report / orchestration` modules.

---

## 0. 当前阶段

当前阶段：ReportWriting 正式方案收口 - 4.x 前低风险资产界定

- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 方案边界：已完成
- [ ] 稳定契约：待执行
- [ ] 持久化字段：待执行
- [ ] 查询投影：待执行
- [ ] 导出解释层：待执行
- [ ] 测试基线：待执行

本 plan 的执行进度应在实施时持续更新，格式固定为：

```markdown
当前阶段：[正在进行的阶段]
- [x] 信息采集：已完成
- [ ] 稳定契约：执行中
- [ ] 持久化字段：待执行
- [ ] 查询投影：待执行
- [ ] 导出解释层：待执行
- [ ] 测试基线：待执行
```

## 1. 边界决策

### 1.1 4.x 前可以先做且未来可复用的资产

| 方向 | 本轮可做内容 | 未来复用价值 | 风险等级 |
| --- | --- | --- | --- |
| 稳定契约 | 在 `ReportResponse` 增加 `writerEvidenceSummary`，字段只承载 Writer 已有事实：`writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps / issueFlags / sourceUrls` | 4.x 后仍可作为 ReportWriting 链路事实快照，被 replay、audit、conversation 继续只读消费 | 低 |
| 持久化字段 | 在 `report` 表增加 Writer 证据快照字段，并由现有报告保存路径写入 | 避免只依赖 `TaskNode.outputData` 临时运行态 JSON，支持后续交付、审计和历史报告追溯 | 中低 |
| 查询投影 | `ReportService` 优先读 `Report` 持久化快照，历史数据回退读取 `write_report / rewrite_report` 节点输出 | 保持新老报告兼容，未来迁移 Agent 持久化边界时查询契约不用跟着改 | 低 |
| 导出解释层 | Markdown / HTML / JSON 导出增加“写作证据摘要”，并把 Writer 来源并入正式 `sourceUrls` | 离线交付物可解释“正文为什么不能直接交付、缺口在哪里、已有来源是什么” | 低 |
| 测试基线 | 新增 `ReportWritingSnapshotContractTest`，并补 Writer、ReportService、Export 渲染回归 | 防止后续 prompt、DTO、导出格式调整时悄悄丢失 `sourceUrls` 或 evidence state | 低 |

### 1.2 当前不要做的内容

| 禁做项 | 原因 | 触发条件 |
| --- | --- | --- |
| 不进入 4.x runtime / orchestration 改造 | 3.5 收敛结论确认当前不满足 4.x 触发条件；ReportWriting 主停点是沉淀与交付解释，不是协议断裂 | 至少三条链路共同指向固定 DAG / 动态补图 / 协作 runtime 表达力不足 |
| 不接 Tavily | 本轮诊断没有证明证据来源不足已经同时压到 Writer、Citation、Reviewer 至少两条下游链路 | 后续 live 场景确认来源不足成为跨链路主阻塞 |
| 不继续补 `pendingActions` | 当前主路径已能用 `decisionType / actionType / evidenceState / sourceUrls` 与 `recommendedAction / recoveryAdvice` 解释停点和下一步 | 真实场景再次暴露“等待补证结果”和“等待人工处理”不可区分 |
| 不大改 Writer prompt / Analyzer 聚合逻辑 | 方案目标是沉淀已有 Writer 事实，不改变写作质量策略或上游分析密度 | 单独开启 AnalysisReasoning 或 Writer prompt 优化计划 |
| 不让 Writer 替代 Citation Agent | Writer 只能识别章节引用缺口，不能做逐句真伪核验或可信度裁决 | Citation Agent 专题继续推进时单独收口 |
| 不新增自由编排节点或 LLM 自治调度 | 会直接滑向 4.x runtime 能力层 | 4.x contract 与动态 runtime 计划被正式批准 |

### 1.3 `sourceUrls` 聚合语义

本 plan 增加 Writer 写作证据来源，但不改变现有报告来源语义：

1. `ReportResponse.sourceUrls` 继续作为交付主路径的总入口，只做去重后的来源索引，不承载“来源属于哪个诊断层”的解释。
2. `writerEvidenceSummary.sourceUrls` 表示 Writer 写作证据快照级来源，`sectionCitationGaps[].sourceUrls` 表示具体章节缺口已有来源；解释语义优先保留在这些嵌套对象中。
3. `collectReportSourceUrls()` 合并顺序固定为：`evidenceInfos -> reportDiagnosis -> deliverySummary -> evidenceEntryPoint -> auditSummary -> orchestrationDecision -> writerEvidenceSummary`。
4. 所有合并都必须继续使用 `LinkedHashSet` 或等价去重策略，只保留非空 URL 字符串，不新增推导出来的伪来源。

## 2. 文件结构

### 2.1 计划内文件

- Create: `backend/src/main/resources/db/migration/V28__add_report_writer_evidence_snapshot_columns.sql`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/entity/Report.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportWritingSnapshotContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportExportRendererWriterEvidenceTest.java`

### 2.2 只读参考文件

- Reference: `docs/superpowers/report-writing/problem/ReportWriting.md`
- Reference: `docs/superpowers/plan/2026-06-26-3.5-convergence-decision.md`
- Reference: `docs/superpowers/delivery-audit/plan/2026-06-26-delivery-audit-orchestration-projection-plan.md`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/WriterCitationGap.java`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/SectionEvidenceBundle.java`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssembler.java`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/report/ExportPackageService.java`

## 3. Task 1: 固化 ReportWriting 稳定契约

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportWritingSnapshotContractTest.java`

- [ ] **Step 1: 写 `writerEvidenceSummary` 契约快照测试**

```java
package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWritingSnapshotContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepWriterEvidenceSummaryDeliveryContractStable() {
        ReportResponse.WriterCitationGapInfo gap = ReportResponse.WriterCitationGapInfo.builder()
                .targetSection("report_conclusion")
                .sectionTitle("报告结论")
                .summary("当前章节暂无可用证据来源")
                .severity("ERROR")
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of())
                .missingFields(List.of("recommendations"))
                .suggestedQueries(List.of("report_conclusion recommendations official source"))
                .build();

        ReportResponse response = ReportResponse.builder()
                .taskId(900L)
                .writerEvidenceSummary(ReportResponse.WriterEvidenceSummaryInfo.builder()
                        .writerEvidenceState("MISSING_SOURCE")
                        .citationGapSeverity("ERROR")
                        .missingCitationSections(List.of("report_conclusion"))
                        .sectionCitationGaps(List.of(gap))
                        .issueFlags(List.of("WRITER_CITATION_GAP", "WRITER_MISSING_SOURCE"))
                        .sourceUrls(List.of())
                        .build())
                .sourceUrls(List.of())
                .build();

        JsonNode node = objectMapper.valueToTree(response);

        assertThat(node.at("/writerEvidenceSummary/writerEvidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(node.at("/writerEvidenceSummary/citationGapSeverity").asText()).isEqualTo("ERROR");
        assertThat(node.at("/writerEvidenceSummary/missingCitationSections/0").asText()).isEqualTo("report_conclusion");
        assertThat(node.at("/writerEvidenceSummary/sectionCitationGaps/0/evidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(node.at("/writerEvidenceSummary/sectionCitationGaps/0/sourceUrls").isArray()).isTrue();
        assertThat(node.toString()).contains("sourceUrls");
    }
}
```

- [ ] **Step 2: 跑契约测试确认先失败**

Run:

```powershell
mvn -pl backend "-Dtest=ReportWritingSnapshotContractTest" test
```

Expected: FAIL，提示 `ReportResponse` 尚无 `writerEvidenceSummary`、`WriterEvidenceSummaryInfo` 或 `WriterCitationGapInfo`。

- [ ] **Step 3: 在 `ReportResponse` 增加稳定 DTO**

```java
@Schema(description = "Writer evidence summary projected for report writing delivery")
private WriterEvidenceSummaryInfo writerEvidenceSummary;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Writer evidence summary projected for report writing delivery")
public static class WriterEvidenceSummaryInfo {
    private String writerEvidenceState;
    private String citationGapSeverity;
    private List<String> missingCitationSections;
    private List<WriterCitationGapInfo> sectionCitationGaps;
    private List<String> issueFlags;
    private List<String> sourceUrls;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Writer citation gap projected for report writing delivery")
public static class WriterCitationGapInfo {
    private String targetSection;
    private String sectionTitle;
    private String summary;
    private String severity;
    private String evidenceState;
    private List<String> sourceUrls;
    private List<String> missingFields;
    private List<String> suggestedQueries;
}
```

- [ ] **Step 4: 跑契约测试确认通过**

Run:

```powershell
mvn -pl backend "-Dtest=ReportWritingSnapshotContractTest" test
```

Expected: PASS。

## 4. Task 2: 增加 Report 持久化快照字段

**Files:**

- Create: `backend/src/main/resources/db/migration/V28__add_report_writer_evidence_snapshot_columns.sql`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/entity/Report.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`

- [ ] **Step 1: 写 Writer 持久化字段的失败测试**

```java
@Test
void shouldPersistWriterEvidenceSnapshotWhenCitationGapDetected() throws Exception {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of());
    when(reportRepository.findByTaskId(1L)).thenReturn(Optional.empty());
    when(promptService.render(eq("writer"), any())).thenReturn("writer-prompt");
    when(llmClient.chat(any(), any())).thenReturn("# report");
    when(llmClient.getModelName()).thenReturn("mock-model");
    when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

    AgentContext context = AgentContext.builder()
            .taskId(1L)
            .taskName("task")
            .subjectProduct("Our Product")
            .reportLanguage("中文")
            .currentNodeName("write_report")
            .build();
    context.putSharedOutput("analyze_competitors", """
            {
              "overview": "分析完成",
              "recommendations": []
            }
            """);

    AgentResult result = agent.execute(context);
    JsonNode output = objectMapper.readTree(result.getOutputData());

    ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
    verify(reportRepository, atLeastOnce()).save(captor.capture());
    Report saved = captor.getValue();

    assertEquals("ERROR", output.path("citationGapSeverity").asText());
    assertEquals("MISSING_SOURCE", output.path("writerEvidenceState").asText());
    assertEquals("MISSING_SOURCE", saved.getWriterEvidenceState());
    assertEquals("ERROR", saved.getCitationGapSeverity());
    assertTrue(saved.getSectionCitationGaps().contains("report_conclusion"));
    assertTrue(saved.getWriterIssueFlags().contains("WRITER_CITATION_GAP"));
}
```

- [ ] **Step 2: 跑 Writer 测试确认先失败**

Run:

```powershell
mvn -pl backend "-Dtest=ReportWriterAgentTest#shouldPersistWriterEvidenceSnapshotWhenCitationGapDetected" test
```

Expected: FAIL，提示 `Report` 尚无 Writer 证据快照字段。

- [ ] **Step 3: 增加 Flyway 迁移**

```sql
ALTER TABLE report ADD COLUMN writer_evidence_state VARCHAR(40);
ALTER TABLE report ADD COLUMN citation_gap_severity VARCHAR(40);
ALTER TABLE report ADD COLUMN missing_citation_sections TEXT;
ALTER TABLE report ADD COLUMN section_citation_gaps TEXT;
ALTER TABLE report ADD COLUMN writer_issue_flags TEXT;
ALTER TABLE report ADD COLUMN writer_source_urls TEXT;
```

- [ ] **Step 4: 在 `Report` 实体增加字段**

```java
@Column(length = 40)
@Schema(description = "Writer evidence state: FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE")
private String writerEvidenceState;

@Column(length = 40)
@Schema(description = "Writer citation gap severity: NONE / HIGH / ERROR")
private String citationGapSeverity;

@Column(columnDefinition = "TEXT")
@Schema(description = "Writer missing citation sections JSON array")
private String missingCitationSections;

@Column(columnDefinition = "TEXT")
@Schema(description = "Writer section citation gaps JSON array")
private String sectionCitationGaps;

@Column(columnDefinition = "TEXT")
@Schema(description = "Writer issue flags JSON array")
private String writerIssueFlags;

@Column(columnDefinition = "TEXT")
@Schema(description = "Writer source URLs JSON array")
private String writerSourceUrls;
```

- [ ] **Step 5: 在 Writer 已有保存路径中写入快照**

```java
// 只持久化 Writer 已经识别出的事实，不在这里生成补证、重写或人工介入决策。
report.setWriterEvidenceState(citationInspection.evidenceState());
report.setCitationGapSeverity(citationInspection.severity());
report.setMissingCitationSections(toJsonArray(citationInspection.missingCitationSections()));
report.setSectionCitationGaps(toJsonArray(citationInspection.gaps()));
report.setWriterIssueFlags(toJsonArray(citationInspection.issueFlags()));
report.setWriterSourceUrls(toJsonArray(normalizedAnalysis.sourceUrls()));
```

- [ ] **Step 6: 跑 Writer 测试确认通过**

Run:

```powershell
mvn -pl backend "-Dtest=ReportWriterAgentTest#shouldPersistWriterEvidenceSnapshotWhenCitationGapDetected" test
```

Expected: PASS。

## 5. Task 3: 建立 ReportService 查询投影

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`

- [ ] **Step 1: 写报告主路径投影失败测试**

```java
@Test
void shouldExposePersistedWriterEvidenceSummaryInReportMainPath() {
    Report report = Report.builder()
            .id(910L)
            .taskId(910L)
            .title("ReportWriting 证据快照")
            .content("# report")
            .summary("summary")
            .evidenceCount(0)
            .writerEvidenceState("MISSING_SOURCE")
            .citationGapSeverity("ERROR")
            .missingCitationSections("[\"report_conclusion\"]")
            .sectionCitationGaps("""
                    [
                      {
                        "targetSection":"report_conclusion",
                        "sectionTitle":"报告结论",
                        "summary":"当前章节暂无可用证据来源",
                        "severity":"ERROR",
                        "evidenceState":"MISSING_SOURCE",
                        "sourceUrls":[],
                        "missingFields":["recommendations"],
                        "suggestedQueries":["report_conclusion recommendations official source"]
                      }
                    ]
                    """)
            .writerIssueFlags("[\"WRITER_CITATION_GAP\",\"WRITER_MISSING_SOURCE\"]")
            .writerSourceUrls("[]")
            .build();
    when(reportRepository.findByTaskId(910L)).thenReturn(Optional.of(report));

    ReportResponse response = reportService.getReport(910L);

    assertNotNull(response.getWriterEvidenceSummary());
    assertEquals("MISSING_SOURCE", response.getWriterEvidenceSummary().getWriterEvidenceState());
    assertEquals("ERROR", response.getWriterEvidenceSummary().getCitationGapSeverity());
    assertEquals("report_conclusion",
            response.getWriterEvidenceSummary().getSectionCitationGaps().get(0).getTargetSection());
}
```

- [ ] **Step 2: 写历史数据回退读取节点输出的失败测试**

```java
@Test
void shouldFallbackToWriterNodeOutputWhenReportHasNoPersistedWriterSnapshot() {
    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(911L))
            .thenReturn(List.of(TaskNode.builder()
                    .taskId(911L)
                    .nodeName("write_report")
                    .outputData("""
                            {
                              "writerEvidenceState":"PARTIAL_SOURCE",
                              "citationGapSeverity":"HIGH",
                              "missingCitationSections":["pricing"],
                              "sectionCitationGaps":[
                                {
                                  "targetSection":"pricing",
                                  "sectionTitle":"定价策略",
                                  "summary":"定价章节已有来源但缺逐句引用",
                                  "severity":"HIGH",
                                  "evidenceState":"PARTIAL_SOURCE",
                                  "sourceUrls":["https://www.notion.so/pricing"],
                                  "missingFields":["pricingComparison"]
                                }
                              ],
                              "issueFlags":["WRITER_CITATION_GAP"],
                              "sourceUrls":["https://www.notion.so/pricing"]
                            }
                            """)
                    .build()));

    ReportResponse response = reportService.getReport(911L);

    assertEquals("PARTIAL_SOURCE", response.getWriterEvidenceSummary().getWriterEvidenceState());
    assertTrue(response.getSourceUrls().contains("https://www.notion.so/pricing"));
}
```

- [ ] **Step 3: 跑报告服务测试确认先失败**

Run:

```powershell
mvn -pl backend "-Dtest=ReportServiceTest#shouldExposePersistedWriterEvidenceSummaryInReportMainPath,ReportServiceTest#shouldFallbackToWriterNodeOutputWhenReportHasNoPersistedWriterSnapshot,ReportServiceTest#shouldKeepWriterEvidenceSummaryNullForLegacyWriterOutputWithoutSnapshotFields" test
```

Expected: FAIL，提示 `ReportService` 尚未构建 `writerEvidenceSummary`。

- [ ] **Step 4: 写旧 Writer 输出保持 null 的兼容测试**

```java
@Test
void shouldKeepWriterEvidenceSummaryNullForLegacyWriterOutputWithoutSnapshotFields() {
    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(912L))
            .thenReturn(List.of(TaskNode.builder()
                    .taskId(912L)
                    .nodeName("write_report")
                    .outputData("""
                            {
                              "content":"# legacy report",
                              "summary":"旧 Writer 输出",
                              "sourceUrls":["https://www.notion.so/product/ai"]
                            }
                            """)
                    .build()));

    ReportResponse response = reportService.getReport(912L);

    assertNull(response.getWriterEvidenceSummary());
}
```

- [ ] **Step 5: 在 `ReportService` 中实现稳定投影**

```java
ReportResponse.WriterEvidenceSummaryInfo writerEvidenceSummary =
        resolveWriterEvidenceSummary(report, nodes);

return ReportResponse.builder()
        // ...
        .writerEvidenceSummary(writerEvidenceSummary)
        .sourceUrls(collectReportSourceUrls(
                evidenceInfos,
                reportDiagnosis,
                deliverySummary,
                evidenceEntryPoint,
                auditSummary,
                orchestrationDecision,
                writerEvidenceSummary))
        .build();
```

- [ ] **Step 6: 投影规则固定为“持久化优先，节点输出兜底，不伪造旧摘要”**

```java
private ReportResponse.WriterEvidenceSummaryInfo resolveWriterEvidenceSummary(Report report, List<TaskNode> nodes) {
    ReportResponse.WriterEvidenceSummaryInfo persisted = readWriterEvidenceSummaryFromReport(report);
    if (persisted != null) {
        return persisted;
    }
    return readWriterEvidenceSummaryFromNode(findNode(nodes, "rewrite_report"))
            .or(() -> readWriterEvidenceSummaryFromNode(findNode(nodes, "write_report")))
            .orElse(null);
}
```

`readWriterEvidenceSummaryFromNode()` 只有在节点输出显式包含 `writerEvidenceState`、`citationGapSeverity` 或非空 `sectionCitationGaps` 时才返回摘要。旧 Writer 输出如果只有 `content / summary / sourceUrls`，继续返回 `null`，导出层展示“当前暂无写作证据摘要。”；其中普通 `sourceUrls` 仍只按既有 report/evidence/diagnosis 聚合规则处理，不被伪装成 Writer 证据摘要。

- [ ] **Step 7: 跑报告服务测试确认通过**

Run:

```powershell
mvn -pl backend "-Dtest=ReportServiceTest#shouldExposePersistedWriterEvidenceSummaryInReportMainPath,ReportServiceTest#shouldFallbackToWriterNodeOutputWhenReportHasNoPersistedWriterSnapshot,ReportServiceTest#shouldKeepWriterEvidenceSummaryNullForLegacyWriterOutputWithoutSnapshotFields" test
```

Expected: PASS。

## 6. Task 4: 把写作证据摘要进入正式导出解释层

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportExportRendererWriterEvidenceTest.java`

说明：`ExportPackageService` 本轮不直接修改。它已经通过 `ReportExportRenderSupport.collectSourceUrls(report)` 生成正式导出记录的 `sourceUrls`，因此只要 support 方法合并 Writer 写作证据来源，导出记录会自然复用新来源集合。

- [ ] **Step 1: 写 Markdown / HTML / JSON 导出失败测试**

```java
@Test
void shouldRenderWriterEvidenceSummaryInMarkdownHtmlAndJsonPackages() throws Exception {
    ReportResponse report = ReportResponse.builder()
            .taskId(920L)
            .title("ReportWriting 证据摘要")
            .summary("summary")
            .content("# report")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .writerEvidenceSummary(ReportResponse.WriterEvidenceSummaryInfo.builder()
                    .writerEvidenceState("PARTIAL_SOURCE")
                    .citationGapSeverity("HIGH")
                    .missingCitationSections(List.of("pricing"))
                    .sourceUrls(List.of("https://www.notion.so/pricing"))
                    .sectionCitationGaps(List.of(ReportResponse.WriterCitationGapInfo.builder()
                            .targetSection("pricing")
                            .sectionTitle("定价策略")
                            .summary("定价章节已有来源但缺逐句引用")
                            .severity("HIGH")
                            .evidenceState("PARTIAL_SOURCE")
                            .sourceUrls(List.of("https://www.notion.so/pricing"))
                            .missingFields(List.of("pricingComparison"))
                            .build()))
                    .issueFlags(List.of("WRITER_CITATION_GAP"))
                    .build())
            .build();
    ReportExportResponse record = ReportExportResponse.builder()
            .taskId(920L)
            .exportVersion(1)
            .exportFormat("JSON")
            .exportSummary("导出含写作证据摘要")
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .build();

    ReportExportRenderer.RenderedExportPackage jsonPackage =
            new JsonEvidencePackageExportRenderer().render(report, record, objectMapper);

    JsonNode jsonNode = objectMapper.readTree(jsonPackage.content());
    assertEquals("PARTIAL_SOURCE", jsonNode.at("/writerEvidenceSummary/writerEvidenceState").asText());
    assertEquals("pricing", jsonNode.at("/writerEvidenceSummary/sectionCitationGaps/0/targetSection").asText());
    assertEquals("https://www.notion.so/pricing",
            jsonNode.at("/writerEvidenceSummary/sectionCitationGaps/0/sourceUrls/0").asText());
    assertEquals(List.of("https://www.notion.so/pricing"),
            ReportExportRenderSupport.collectSourceUrls(report));
}
```

- [ ] **Step 2: 跑导出测试确认先失败**

Run:

```powershell
mvn -pl backend "-Dtest=ReportExportRendererWriterEvidenceTest" test
```

Expected: FAIL，导出 payload 尚无 `writerEvidenceSummary`。

- [ ] **Step 3: Markdown / HTML 增加“写作证据摘要”**

```java
private String buildMarkdownWriterEvidenceSummary(ReportResponse report) {
    ReportResponse.WriterEvidenceSummaryInfo summary = report.getWriterEvidenceSummary();
    if (summary == null) {
        return "当前暂无写作证据摘要。";
    }
    return """
            - 证据状态：%s
            - 引用缺口等级：%s
            - 缺口章节：%s
            - 来源链接：%s
            """.formatted(
            safeText(summary.getWriterEvidenceState()),
            safeText(summary.getCitationGapSeverity()),
            String.join("，", summary.getMissingCitationSections() == null ? List.of() : summary.getMissingCitationSections()),
            String.join("，", summary.getSourceUrls() == null ? List.of() : summary.getSourceUrls()));
}
```

- [ ] **Step 4: JSON 导出增加结构化对象**

```java
payload.put("writerEvidenceSummary", ReportExportRenderSupport.buildWriterEvidencePayload(report));
```

- [ ] **Step 5: 在 `ReportExportRenderSupport.collectSourceUrls()` 聚合写作证据来源**

```java
static List<String> collectSourceUrls(ReportResponse report) {
    LinkedHashSet<String> merged = new LinkedHashSet<>();
    appendSourceUrls(merged, report == null ? null : report.getSourceUrls());
    appendSourceUrls(merged, report == null || report.getReportDiagnosis() == null ? null : report.getReportDiagnosis().getSourceUrls());
    appendSourceUrls(merged, report == null || report.getDeliverySummary() == null ? null : report.getDeliverySummary().getSourceUrls());
    appendSourceUrls(merged, report == null || report.getAuditSummary() == null ? null : report.getAuditSummary().getSourceUrls());
    appendSourceUrls(merged, report == null || report.getEvidenceEntryPoint() == null ? null : report.getEvidenceEntryPoint().getSourceUrls());
    appendSourceUrls(merged, decisionSourceUrls(report));
    if (report != null && report.getWriterEvidenceSummary() != null) {
        appendSourceUrls(merged, report.getWriterEvidenceSummary().getSourceUrls());
        for (ReportResponse.WriterCitationGapInfo gap :
                report.getWriterEvidenceSummary().getSectionCitationGaps() == null
                        ? List.<ReportResponse.WriterCitationGapInfo>of()
                        : report.getWriterEvidenceSummary().getSectionCitationGaps()) {
            appendSourceUrls(merged, gap.getSourceUrls());
        }
    }
    if (report != null && report.getEvidences() != null) {
        for (EvidenceInfo evidence : report.getEvidences()) {
            if (evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                merged.add(evidence.getUrl().trim());
            }
        }
    }
    return new ArrayList<>(merged);
}
```

该步骤只修改 `ReportExportRenderer.java` 内的 support 聚合方法，不修改 `ExportPackageService`。实现时必须保留已有 report / diagnosis / delivery / audit / evidence entry / orchestration / evidence 列表聚合，只追加 Writer 写作证据来源；正式导出记录仍通过既有 `createExportPackage()` 调用链复用该聚合结果。

- [ ] **Step 6: 跑导出测试确认通过**

Run:

```powershell
mvn -pl backend "-Dtest=ReportExportRendererWriterEvidenceTest" test
```

Expected: PASS。

## 7. Task 5: 回归验证与路线图更新

**Files:**

- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap-progress.md`

- [ ] **Step 1: 运行 ReportWriting 聚焦回归**

Run:

```powershell
mvn -pl backend "-Dtest=ReportWritingSnapshotContractTest,ReportWriterAgentTest,ReportServiceTest,ReportExportRendererWriterEvidenceTest,ReportExportRendererOrchestrationDecisionTest" test
```

Expected: PASS。

- [ ] **Step 2: 运行协作协议保护回归**

Run:

```powershell
mvn -pl backend "-Dtest=WriterSuggestionAssemblerTest,OrchestrationDecisionServiceTest,CitationSuggestionAssemblerTest" test
```

Expected: PASS，确认本轮没有改变 Writer -> 3.4 的协作决策语义。

- [ ] **Step 3: 运行文档和补丁格式检查**

Run:

```powershell
git diff --check -- docs/superpowers/report-writing/plan/2026-06-26-report-writing-pre-4x-closure-plan.md docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap-progress.md backend/src/main/resources/db/migration/V28__add_report_writer_evidence_snapshot_columns.sql backend/src/main/java/cn/bugstack/competitoragent/model/entity/Report.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportExportRenderer.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportWritingSnapshotContractTest.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportExportRendererWriterEvidenceTest.java
```

Expected: PASS。

- [ ] **Step 4: 更新总路线图和进度文档**

总路线图只更新 ReportWriting 索引和状态，不把本 plan 标记为已实施：

```markdown
| 报告写作 | ✅ [ReportWriting.md](...) | ✅ [2026-06-26-report-writing-pre-4x-closure-plan.md](...) | 🟡 Writer -> 3.4 协议已接通；持久化与导出解释层按 plan 待执行 | ⬜ 待真实任务验证 |
```

进度文档只记录：

```markdown
- 2026-06-26：新增 ReportWriting pre-4.x 收口 plan，明确当前只做稳定契约、持久化字段、查询投影、导出解释层和测试基线；不进入 4.x、不做 Tavily、不补 pendingActions。
```

## 8. 完成判定

本 plan 只有在以下条件都满足时，ReportWriting 才能从“方案完成”推进到“实施可复核”：

- [ ] `ReportResponse.writerEvidenceSummary` 契约稳定，序列化字段被 `ReportWritingSnapshotContractTest` 锁定。
- [ ] `Report` 持久化 Writer 证据快照字段，历史报告可通过节点输出兜底投影。
- [ ] `ReportService.getReport()` 顶层返回 `writerEvidenceSummary`，且 `sourceUrls` 聚合包含 Writer 证据来源。
- [ ] Markdown / HTML / JSON 正式导出均能解释写作证据状态和章节引用缺口。
- [ ] ReportWriting 聚焦回归与协作协议保护回归通过。
- [ ] 总路线图只标记“方案完成”，不提前标记“实施完成”或“实链验证完成”。

## 9. 本轮明确不关闭的事项

1. Writer prompt 的报告质量优化不在本 plan 内。
2. Analyzer 的章节证据聚合质量优化不在本 plan 内。
3. Citation Agent 的逐句引用核查不在本 plan 内。
4. `pendingActions` 的只读解释扩展不在本 plan 内。
5. Tavily fail-open 或 capability 接入不在本 plan 内。
6. 4.x runtime contract、动态 runtime 迁移和自由编排能力不在本 plan 内。
