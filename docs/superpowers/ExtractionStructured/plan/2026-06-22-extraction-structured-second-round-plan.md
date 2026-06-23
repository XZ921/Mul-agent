# 3.3 提取结构化第二轮执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在第一轮 P0/P1 已落地、P2 已起步的基础上，继续收口 `extract_schema` 之后的下游消费诊断、报告侧 coverage 细粒度透出和真实链路样本补证。

**Architecture:** 本轮不重做 P0 extractor 主停点，也不重新设计 P1 Provider 第一版边界。第二轮只沿着第一轮剩余缺口推进：workflow 层把 analyzer / writer / reviewer / delivery 的下游阻断统一归口为 `DOWNSTREAM_CONSUMPTION_GAP`，报告层在保留粗粒度计数的同时暴露细粒度 `evidenceCoverage` 状态分布，live 验证层补齐 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 的真实命中样本。

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, Maven, PowerShell curl

---

## 0. 第一轮衔接基线

本计划直接衔接以下两份文档，不另起一套 3.3 方案：

- 第一轮执行计划：`docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`
- 架构规格：`docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`

第一轮已经完成并冻结为本轮前提的内容：

- P0 extractor 主停点已完成：Prompt 分层、0 业务字段语义重试、结构块型证据入口、Schema 注入、字段来源约束均已落地。
- P1 第一版运行时边界已完成：`ExtractorInputProvider / ExtractorInputPackage`、Provider TopK 与预算、analyzer drafts 优先、extract shared output sanitizer 已落地。
- P2 已完成一半：`evidenceCoverage` 细化已进入 extractor / reviewer / report 粗聚合；`DOWNSTREAM_CONSUMPTION_GAP` 已覆盖终审 `passed=false` 和初审 `requiresHumanIntervention=true` 两类 reviewer 阻断；task `50` 已完成一次真实链路 rerun 成功样本。

第二轮只处理仍未闭合的内容：

- `DOWNSTREAM_CONSUMPTION_GAP` 还没有泛化到更完整的 analyzer / writer / reviewer / delivery 下游失败汇总。
- `ReportService` 当前把 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 折叠成 `MISSING_EVIDENCE / TRACEABLE` 计数，报告侧还缺细粒度状态分布。
- live 样本只有 task `50` 成功链路，还缺新 coverage 状态的真实命中样本。
- Provider 内部仍是 repository-backed 第一版，正式端口 / replay / cache 统一输入源只做设计准备，不在本轮强行迁移。

本轮执行约束：

- 直接在 `master` 工作区修改。
- 不执行 `git commit`，提交由用户完成。
- 不回滚用户已有改动，不清理 unrelated logs。
- 本计划命令默认在 PowerShell 中执行；`rg` 只负责搜索文本，若本机缺少 `rg`，再用 PowerShell `Select-String` 等价替换。
- 所有自动化验证命令都限制路径，避免 `backend/logs/competitor-agent.log` 的历史空白字符影响 `git diff --check` 判断。

## 1. 文件结构

本轮预计修改或检查的文件如下：

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
  - 负责 workflow 终态汇总时的 `DOWNSTREAM_CONSUMPTION_GAP` 归口。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
  - 覆盖 analyzer / writer / reviewer 下游失败归类，保护 extractor 失败不被误归类。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
  - 在 `EvidenceCoverageOverview / SectionEvidenceCoverage / CompetitorEvidenceCoverage` 上增加细粒度状态分布字段。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
  - 保留现有粗粒度计数，同时汇总原始 coverage status。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
  - 验证报告响应同时包含粗计数与细状态分布。
- Create: `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md`
  - 记录 live 样本补证命令、样本判定标准和执行结果位置。
- Modify: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
  - 第二轮完成后刷新当前状态回链。
- Modify: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
  - 第二轮完成后追加下游失败汇总和 live 样本补证进度。
- Modify: `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`
  - 第二轮完成后把 P2 剩余项状态回写到第一轮计划的 P2 草案段落。

如果 `DagExecutor.java` 的分类逻辑超过 80 行，执行时允许新建一个小类：

- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DownstreamConsumptionGapClassifier.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DownstreamConsumptionGapClassifierTest.java`

默认先不拆类，只有当 `DagExecutor` 内部出现三类以上重复 JSON 判断时再拆。

## 2. 本轮执行看板

| Task | 目标 | 状态 |
| --- | --- | --- |
| Task 1 | 校准第一轮完成/剩余边界，保护 P0/P1 不被重复实现 | 已完成 |
| Task 2 | 泛化 `DOWNSTREAM_CONSUMPTION_GAP` workflow 汇总 | 已完成 |
| Task 3 | 报告侧 coverage 细状态分布 | 已完成 |
| Task 4 | live 样本补证方案与执行记录 | 已完成 |
| Task 5 | Provider 数据源治理准备，不做大迁移 | 已完成 |
| Task 6 | 回归验证与文档回链 | 已完成 |

当前执行记录（2026-06-22）：

- Task 2 已记录于 `docs/superpowers/ExtractionStructured/progress/2026-06-22-downstream-consumption-gap-expansion-progress.md`，并已覆盖 analyzer / writer / extractor 保护测试。
- Task 3 已记录于 `docs/superpowers/ExtractionStructured/progress/2026-06-22-report-coverage-status-breakdown-progress.md`，报告三层 `statusBreakdown` 已落地。
- Task 4 已记录于 `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md` 与 `live-coverage-samples-20260622-151324/`，live 样本命中 `DOWNSTREAM_CONSUMPTION_GAP`，未命中 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。
- Task 5 已回写到架构规格 `5.1.1 第二轮后的 Provider 数据源治理边界`。
- Task 6 已完成 fresh 验证命令与第二轮计划自身状态收口。

---

### Task 1: 第一轮状态校准与红线保护

**Files:**

- Read: `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`
- Read: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`
- Read: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Read: `docs/superpowers/ExtractionStructured/progress/2026-06-22-task-50-extract-schema-rerun-success.md`
- Modify only after Task 6: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Modify only after Task 6: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
- Modify only after Task 6: `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`

- [x] **Step 1: 读取第一轮计划的 P0/P1/P2 状态**

Run:

```powershell
rg -n "P0|P1|P2|DOWNSTREAM_CONSUMPTION_GAP|evidenceCoverage|task `50`|当前状态补充" docs\superpowers\ExtractionStructured\plan\2026-06-20-extraction-structured-optimization-plan.md
```

Expected:

```text
命中 P0/P1/P2 分轮边界、P2 Task A/B/C，以及 2026-06-22 的当前状态补充。
```

- [x] **Step 2: 读取架构规格中仍未闭合的 P2 项**

Run:

```powershell
rg -n "DOWNSTREAM_CONSUMPTION_GAP|LLM_REFUSED|EVIDENCE_NOT_COVERING|STRUCTURED_BLOCK_DIRECT|Provider|replay|cache|workflow 汇总" docs\superpowers\ExtractionStructured\specs\2026-06-21-extraction-structured-architecture-spec.md docs\superpowers\ExtractionStructured\problem\ExtractionStructured.md
```

Expected:

```text
确认本轮只推进 workflow 汇总、coverage 细状态透出、live 样本补证和 Provider 数据源治理准备。
```

- [x] **Step 3: 建立执行前代码事实清单**

Run:

```powershell
rg -n "classifyDownstreamQualityGateFailure|isInitialReviewHumanInterventionGapNode|DOWNSTREAM_CONSUMPTION_GAP|resolveCoverageStatus|EvidenceCoverageOverview|ExtractorInputProvider|RepositoryExtractorInputProvider" backend\src\main\java backend\src\test\java
```

Expected:

```text
确认 workflow 已有 reviewer 两类归口；ReportService 已有粗聚合；Provider 已存在 repository-backed 第一版。
```

- [x] **Step 4: 写下本轮不重做清单**

不修改代码，只在执行记录中写入下面结论：

```markdown
第二轮不重做 P0/P1 已完成内容：
- 不重写 SchemaExtractorAgent 的 Prompt 分层与 0 字段语义重试。
- 不把 isUsableEvidence 从 Provider 搬回 Agent。
- 不重做 analyzer drafts-first 优先级。
- 不扩大到搜索与采集 3.2。
- 不迁移 Provider 内部数据源到全新端口，只准备边界。
```

Expected:

```text
后续 Task 2/3/4/5 都只在剩余缺口上动刀。
```

---

### Task 2: 泛化 `DOWNSTREAM_CONSUMPTION_GAP` workflow 汇总

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Optional create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DownstreamConsumptionGapClassifier.java`
- Optional test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DownstreamConsumptionGapClassifierTest.java`

**Classification rule:**

`DOWNSTREAM_CONSUMPTION_GAP` 只在 extractor 已成功之后由 workflow 汇总层判定。extractor 自身失败、采集失败、JSON 解析失败、0 业务字段失败不允许被改写成下游消费缺口。

第二轮新增归口范围：

- analyzer 节点失败，且 `extract_schema` 已成功，失败信息或输出说明无法消费结构化提取结果。
- writer 节点失败，且 `extract_schema` 与 analyzer 已成功，失败信息或输出说明报告撰写无法消费证据、coverage 或分析结果。
- reviewer 节点失败或阻断，且上游 writer 已成功，失败信息或输出说明证据追溯、质量闭环、人工补证据或改写策略无法自动闭合。
- delivery 类节点如果在当前 DAG 中出现，且上游 reviewer / writer 已成功，失败信息说明交付检查无法通过，也归口为下游消费缺口。

- [x] **Step 1: 确认 DagExecutorTest 现有测试 fixture 签名**

Run:

```powershell
rg -n "private static DagExecutor newDagExecutor|private static TaskExecutionLockService allowingNodeLockService|registryOf\(" backend\src\test\java\cn\bugstack\competitoragent\workflow\DagExecutorTest.java
```

Expected:

```text
命中 `newDagExecutor` helper、`allowingNodeLockService()` 和 `registryOf()`。如果签名已经变化，后续新增测试必须按现有 helper 实际签名调整，不要照抄本计划里的构造参数。
```

- [x] **Step 2: 写 analyzer 下游失败红灯测试**

Add to `DagExecutorTest`:

```java
@Test
void shouldClassifyAnalyzerConsumptionFailureAsDownstreamConsumptionGapWhenExtractorSucceeded() {
    Long taskId = 1004L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();

    TaskNode extractSchema = TaskNode.builder()
            .id(401L)
            .taskId(taskId)
            .nodeName("extract_schema")
            .agentType(AgentType.EXTRACTOR)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(0)
            .build();
    TaskNode analyzer = TaskNode.builder()
            .id(402L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[\"extract_schema\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();
    List<TaskNode> nodes = List.of(extractSchema, analyzer);

    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
    when(nodeRepository.findById(any())).thenAnswer(invocation -> {
        Long nodeId = invocation.getArgument(0);
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
    });
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    DagExecutor executor = newDagExecutor(
            nodeRepository,
            taskRepository,
            List.of(new SuccessfulExtractorAgent(), new AnalyzerConsumptionFailureAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService()
    );

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("analyzer-gap-test").build());

    assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
    assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, analyzer.getFailureCategory());
    assertTrue(analyzer.getInterventionReason().contains("analyzer"));
}
```

Expected before implementation:

```text
FAIL: analyzer.getFailureCategory() is not DOWNSTREAM_CONSUMPTION_GAP
```

- [x] **Step 3: 写 writer 下游失败红灯测试**

Add to `DagExecutorTest`:

```java
@Test
void shouldClassifyWriterConsumptionFailureAsDownstreamConsumptionGapWhenAnalyzerSucceeded() {
    Long taskId = 1005L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();

    TaskNode extractSchema = TaskNode.builder()
            .id(501L)
            .taskId(taskId)
            .nodeName("extract_schema")
            .agentType(AgentType.EXTRACTOR)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(0)
            .build();
    TaskNode analyzer = TaskNode.builder()
            .id(502L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[\"extract_schema\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();
    TaskNode writer = TaskNode.builder()
            .id(503L)
            .taskId(taskId)
            .nodeName("write_report")
            .agentType(AgentType.WRITER)
            .dependsOn("[\"analyze_competitors\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(2)
            .build();
    List<TaskNode> nodes = List.of(extractSchema, analyzer, writer);

    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
    when(nodeRepository.findById(any())).thenAnswer(invocation -> {
        Long nodeId = invocation.getArgument(0);
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
    });
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    DagExecutor executor = newDagExecutor(
            nodeRepository,
            taskRepository,
            List.of(new SuccessfulExtractorAgent(), new SuccessfulAnalyzerAgent(), new WriterConsumptionFailureAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService()
    );

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("writer-gap-test").build());

    assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
    assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, writer.getFailureCategory());
    assertTrue(writer.getInterventionReason().contains("writer"));
}
```

Expected before implementation:

```text
FAIL: writer.getFailureCategory() is not DOWNSTREAM_CONSUMPTION_GAP
```

- [x] **Step 4: 写 extractor 失败保护测试**

Add to `DagExecutorTest`:

```java
@Test
void shouldNotClassifyExtractorFailureAsDownstreamConsumptionGap() {
    Long taskId = 1006L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();

    TaskNode extractSchema = TaskNode.builder()
            .id(601L)
            .taskId(taskId)
            .nodeName("extract_schema")
            .agentType(AgentType.EXTRACTOR)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(0)
            .build();
    TaskNode analyzer = TaskNode.builder()
            .id(602L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[\"extract_schema\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();
    List<TaskNode> nodes = List.of(extractSchema, analyzer);

    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
    when(nodeRepository.findById(any())).thenAnswer(invocation -> {
        Long nodeId = invocation.getArgument(0);
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
    });
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    DagExecutor executor = newDagExecutor(
            nodeRepository,
            taskRepository,
            List.of(new ExtractorBusinessFailureAgent(), new SuccessfulAnalyzerAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService()
    );

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("extractor-protection-test").build());

    assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
    assertNotEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, extractSchema.getFailureCategory());
    assertNull(analyzer.getFailureCategory());
}
```

Expected before implementation:

```text
PASS 或 FAIL 都可接受；该测试用于锁住本轮实现后 extractor 不被误归类。
```

- [x] **Step 5: 添加测试用轻量 Agent**

Add to the helper section of `DagExecutorTest`:

```java
private static final class SuccessfulExtractorAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.EXTRACTOR;
    }

    @Override
    public String getName() {
        return "successful-extractor";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {"contractVersion":"EXTRACT_RESULT_V1","drafts":[{"competitorName":"Notion AI","fieldsExtracted":3}],"sourceUrls":["https://www.notion.so/product/ai"]}
                        """.trim())
                .build();
    }
}

private static final class ExtractorBusinessFailureAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.EXTRACTOR;
    }

    @Override
    public String getName() {
        return "extractor-business-failure";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.failed("未能抽取出任何业务字段，issueFlags=[NO_BUSINESS_FIELDS_EXTRACTED]");
    }
}

private static final class SuccessfulAnalyzerAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.ANALYZER;
    }

    @Override
    public String getName() {
        return "successful-analyzer";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData("{\"analysisComplete\":true,\"sourceUrls\":[\"https://www.notion.so/product/ai\"]}")
                .build();
    }
}

private static final class AnalyzerConsumptionFailureAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.ANALYZER;
    }

    @Override
    public String getName() {
        return "analyzer-consumption-failure";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.failed("analyzer 无法消费 extract_schema 的 drafts / downstreamEvidenceViews，结构化事实源缺失");
    }
}

private static final class WriterConsumptionFailureAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.WRITER;
    }

    @Override
    public String getName() {
        return "writer-consumption-failure";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.failed("writer 无法基于 evidenceCoverage 和 sourceUrls 生成可追溯报告");
    }
}
```

- [x] **Step 6: 运行红灯测试**

Run:

```powershell
mvn -pl backend "-Dtest=DagExecutorTest#shouldClassifyAnalyzerConsumptionFailureAsDownstreamConsumptionGap+shouldClassifyWriterConsumptionFailureAsDownstreamConsumptionGap+shouldNotClassifyExtractorFailureAsDownstreamConsumptionGap" test
```

Expected:

```text
至少 analyzer 和 writer 两个新增归类用例失败，说明当前实现只覆盖 reviewer 阻断。
```

- [x] **Step 7: 实现 workflow 泛化分类**

Modify `DagExecutor.java` by replacing the existing `classifyDownstreamQualityGateFailure` helper with a broader method name and calling it from `updateTaskFinalStatus`:

```java
classifyDownstreamConsumptionGap(nodes, resolution);
```

Implementation shape:

```java
/**
 * workflow 层统一识别 extractor 已成功后的下游消费缺口。
 * 这里不重写 extractor 内部失败分类，只在任务终态汇总时把 analyzer / writer / reviewer / delivery 的阻断归口，
 * 避免用户把已经越过提取边界的问题继续误判为采集或提取失败。
 */
private void classifyDownstreamConsumptionGap(List<TaskNode> nodes,
                                              NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution) {
    if (resolution == null || nodes == null || nodes.isEmpty()) {
        return;
    }
    if (!hasSuccessfulExtractorBoundary(nodes)) {
        return;
    }
    if (resolution.getStatus() == AnalysisTaskStatus.FAILED) {
        findDownstreamFailedNode(nodes)
                .ifPresent(node -> markDownstreamConsumptionGap(
                        node,
                        buildDownstreamConsumptionReason(node)));
        nodes.stream()
                .filter(this::isFailedFinalQualityGateNode)
                .findFirst()
                .ifPresent(node -> markDownstreamConsumptionGap(
                        node,
                        "终审执行成功但质量闭环未通过，问题已移交 writer / reviewer / delivery 链路处理"));
        return;
    }
    if (resolution.getStatus() == AnalysisTaskStatus.STOPPED) {
        nodes.stream()
                .filter(this::isInitialReviewHumanInterventionGapNode)
                .findFirst()
                .ifPresent(node -> markDownstreamConsumptionGap(
                        node,
                        "初审已明确要求人工补证据或调整写作策略，当前阻断属于 writer / reviewer 链路的下游消费缺口"));
    }
}

private boolean hasSuccessfulExtractorBoundary(List<TaskNode> nodes) {
    return nodes.stream()
            .filter(node -> "extract_schema".equals(node.getNodeName()) || node.getAgentType() == AgentType.EXTRACTOR)
            .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS);
}

private Optional<TaskNode> findDownstreamFailedNode(List<TaskNode> nodes) {
    return nodes.stream()
            .filter(TaskNode::isRequired)
            .filter(node -> node.getStatus() == TaskNodeStatus.FAILED || node.getStatus() == TaskNodeStatus.SKIPPED)
            .filter(this::isDownstreamConsumptionNode)
            .findFirst();
}

private boolean isDownstreamConsumptionNode(TaskNode node) {
    if (node == null) {
        return false;
    }
    AgentType agentType = node.getAgentType();
    String nodeName = node.getNodeName() == null ? "" : node.getNodeName();
    return agentType == AgentType.ANALYZER
            || agentType == AgentType.WRITER
            || agentType == AgentType.REVIEWER
            || nodeName.startsWith("delivery")
            || nodeName.contains("deliver");
}

private String buildDownstreamConsumptionReason(TaskNode node) {
    String stage = switch (node.getAgentType()) {
        case ANALYZER -> "analyzer";
        case WRITER -> "writer";
        case REVIEWER -> "reviewer";
        default -> node.getNodeName() == null ? "delivery" : node.getNodeName();
    };
    return stage + " 节点在 extractor 成功后仍无法完成消费，问题已归口为下游消费缺口，请检查分析、写作、评审或交付链路";
}
```

If `AgentType` has no delivery enum, use nodeName matching only for delivery-like nodes.

- [x] **Step 8: 保留 reviewer 已有场景**

Run:

```powershell
mvn -pl backend "-Dtest=DagExecutorTest#shouldClassifyFinalQualityGateFailureAsDownstreamConsumptionGap+shouldClassifyInitialReviewHumanInterventionStopAsDownstreamConsumptionGap" test
```

Expected:

```text
BUILD SUCCESS
```

- [x] **Step 9: 运行本任务完整测试**

Run:

```powershell
mvn -pl backend "-Dtest=DagExecutorTest#shouldClassifyAnalyzerConsumptionFailureAsDownstreamConsumptionGap+shouldClassifyWriterConsumptionFailureAsDownstreamConsumptionGap+shouldNotClassifyExtractorFailureAsDownstreamConsumptionGap+shouldClassifyFinalQualityGateFailureAsDownstreamConsumptionGap+shouldClassifyInitialReviewHumanInterventionStopAsDownstreamConsumptionGap" test
```

Expected:

```text
BUILD SUCCESS
```

---

### Task 3: 报告侧 coverage 细状态分布

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`

**Compatibility rule:**

现有字段不删除：

- `totalFields`
- `traceableFields`
- `missingEvidenceFields`
- `emptyFields`
- `missingFields`
- `missingSections`

新增字段只补细粒度分布，前端旧消费路径不受影响。

- [x] **Step 1: 确认报告 coverage 既有回归测试**

Run:

```powershell
rg -n "shouldAggregateEvidenceCoverageOverview|shouldTreatStructuredDirectAsTraceableAndExpandedGapStatesAsMissingCoverage|shouldExposeFineGrainedCoverageStatusBreakdownInReportOverview" backend\src\test\java\cn\bugstack\competitoragent\report\ReportServiceTest.java
```

Expected:

```text
命中 `shouldAggregateEvidenceCoverageOverview` 和 `shouldTreatStructuredDirectAsTraceableAndExpandedGapStatesAsMissingCoverage`；不应命中 `shouldExposeFineGrainedCoverageStatusBreakdownInReportOverview`，这个新测试由本轮新增。
```

- [x] **Step 2: 写报告细状态红灯测试**

Add to `ReportServiceTest`:

```java
@Test
void shouldExposeFineGrainedCoverageStatusBreakdownInReportOverview() {
    Report report = Report.builder()
            .id(32L)
            .taskId(320L)
            .title("企业级竞品分析")
            .content("# Report")
            .summary("summary")
            .qualityPassed(true)
            .evidenceCount(1)
            .build();

    CompetitorKnowledge knowledge = CompetitorKnowledge.builder()
            .taskId(320L)
            .competitorName("Notion AI")
            .summary("summary")
            .sourceUrls("[\"https://www.notion.so/product/ai\"]")
            .evidenceCoverage("""
                    {
                      "summary": {"status":"LLM_REFUSED","hasValue":false},
                      "positioning": {"status":"TRACEABLE","hasValue":true},
                      "targetUsers": {"status":"STRUCTURED_BLOCK_DIRECT","hasValue":true},
                      "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                      "pricing": {"status":"EVIDENCE_NOT_COVERING","hasValue":false},
                      "strengths": {"status":"MISSING_EVIDENCE","hasValue":true},
                      "weaknesses": {"status":"EMPTY","hasValue":false}
                    }
                    """)
            .build();

    when(reportRepository.findByTaskId(320L)).thenReturn(Optional.of(report));
    when(evidenceQueryService.listTaskEvidence(320L)).thenReturn(List.of());
    when(knowledgeRepository.findByTaskIdOrderByIdAsc(320L)).thenReturn(List.of(knowledge));
    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(320L)).thenReturn(List.of());

    ReportResponse response = reportService.getReport(320L);

    assertNotNull(response.getEvidenceCoverageOverview());
    assertEquals(7, response.getEvidenceCoverageOverview().getTotalFields());
    assertEquals(3, response.getEvidenceCoverageOverview().getTraceableFields());
    assertEquals(3, response.getEvidenceCoverageOverview().getMissingEvidenceFields());
    assertEquals(1, response.getEvidenceCoverageOverview().getEmptyFields());
    assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("LLM_REFUSED"));
    assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("STRUCTURED_BLOCK_DIRECT"));
    assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("EVIDENCE_NOT_COVERING"));
    assertEquals(1, response.getEvidenceCoverageOverview().getSections().stream()
            .filter(section -> "overview".equals(section.getSectionKey()))
            .findFirst()
            .orElseThrow()
            .getStatusBreakdown()
            .get("LLM_REFUSED"));
    assertEquals(1, response.getEvidenceCoverageOverview().getCompetitors().get(0)
            .getStatusBreakdown()
            .get("STRUCTURED_BLOCK_DIRECT"));
}
```

Expected before implementation:

```text
Compilation FAIL: getStatusBreakdown() does not exist
```

- [x] **Step 3: 给 DTO 增加细状态分布字段**

Modify `ReportResponse.java`:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Evidence coverage overview")
public static class EvidenceCoverageOverview {
    private Integer totalFields;
    private Integer traceableFields;
    private Integer missingEvidenceFields;
    private Integer emptyFields;
    private Map<String, Integer> statusBreakdown;
    private List<SectionEvidenceCoverage> sections;
    private List<CompetitorEvidenceCoverage> competitors;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Section-level evidence coverage")
public static class SectionEvidenceCoverage {
    private String sectionKey;
    private String sectionTitle;
    private Integer totalFields;
    private Integer traceableFields;
    private Integer missingEvidenceFields;
    private Integer emptyFields;
    private Map<String, Integer> statusBreakdown;
    private List<String> missingFields;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Competitor-level evidence coverage")
public static class CompetitorEvidenceCoverage {
    private String competitorName;
    private Integer totalFields;
    private Integer traceableFields;
    private Integer missingEvidenceFields;
    private Integer emptyFields;
    private Map<String, Integer> statusBreakdown;
    private List<String> missingSections;
}
```

- [x] **Step 4: 在 ReportService 中保留原始状态**

Modify `ReportService.java` around coverage helpers:

```java
private CoverageState resolveCoverageState(Object rawCoverage) {
    if (!(rawCoverage instanceof Map<?, ?> coverageMap)) {
        return new CoverageState(CoverageStatus.EMPTY, "EMPTY");
    }
    Object status = coverageMap.get("status");
    String rawStatus;
    if (status == null || String.valueOf(status).isBlank()) {
        Object hasValue = coverageMap.get("hasValue");
        rawStatus = Boolean.TRUE.equals(hasValue) ? "MISSING_EVIDENCE" : "EMPTY";
    } else {
        rawStatus = String.valueOf(status).trim().toUpperCase(Locale.ROOT);
    }
    CoverageStatus coarseStatus = switch (rawStatus) {
        case "TRACEABLE", "STRUCTURED_BLOCK_DIRECT" -> CoverageStatus.TRACEABLE;
        case "MISSING_EVIDENCE", "PARTIAL", "LLM_REFUSED", "EVIDENCE_NOT_COVERING" -> CoverageStatus.MISSING_EVIDENCE;
        default -> CoverageStatus.EMPTY;
    };
    return new CoverageState(coarseStatus, rawStatus);
}

private CoverageStatus resolveCoverageStatus(Object rawCoverage) {
    return resolveCoverageState(rawCoverage).coarseStatus();
}

private record CoverageState(CoverageStatus coarseStatus, String rawStatus) {
}
```

Add `import java.util.Locale;` if the file does not already import it. This keeps coarse status and raw status derived from the same parsed coverage map, avoiding duplicate coverage status parsing.

- [x] **Step 5: 给 SectionCoverageAccumulator 增加状态计数**

Modify the accumulator:

```java
private static final class SectionCoverageAccumulator {
    private final String sectionKey;
    private final String sectionTitle;
    private int totalFields;
    private int traceableFields;
    private int missingEvidenceFields;
    private int emptyFields;
    private final Map<String, Integer> statusBreakdown = new LinkedHashMap<>();
    private final List<String> missingFields = new ArrayList<>();

    private SectionCoverageAccumulator(String sectionKey, String sectionTitle) {
        this.sectionKey = sectionKey;
        this.sectionTitle = sectionTitle;
    }

    private void addRawStatus(String rawStatus) {
        String normalizedStatus = rawStatus == null || rawStatus.isBlank() ? "EMPTY" : rawStatus;
        statusBreakdown.merge(normalizedStatus, 1, Integer::sum);
    }
}
```

- [x] **Step 6: 在 buildEvidenceCoverageOverview 中填充分布**

Modify the loop that currently calls `resolveCoverageStatus` to:

```java
CoverageState state = resolveCoverageState(evidenceCoverage.get(definition.fieldKey()));
CoverageStatus status = state.coarseStatus();
String rawStatus = state.rawStatus();
section.addRawStatus(rawStatus);
overviewStatusBreakdown.merge(rawStatus, 1, Integer::sum);
competitorStatusBreakdown.merge(rawStatus, 1, Integer::sum);
```

At the start of each competitor loop:

```java
Map<String, Integer> competitorStatusBreakdown = new LinkedHashMap<>();
```

Before the competitor builder:

```java
.statusBreakdown(competitorStatusBreakdown)
```

When building section DTO:

```java
.statusBreakdown(item.statusBreakdown)
```

When building overview DTO:

```java
.statusBreakdown(overviewStatusBreakdown)
```

Expected behavior:

```text
粗粒度计数保持旧逻辑；statusBreakdown 记录 TRACEABLE、MISSING_EVIDENCE、EMPTY、LLM_REFUSED、EVIDENCE_NOT_COVERING、STRUCTURED_BLOCK_DIRECT 等原始状态。
```

- [x] **Step 7: 运行报告测试**

Run:

```powershell
mvn -pl backend "-Dtest=ReportServiceTest#shouldExposeFineGrainedCoverageStatusBreakdownInReportOverview+shouldTreatStructuredDirectAsTraceableAndExpandedGapStatesAsMissingCoverage+shouldAggregateEvidenceCoverageOverview" test
```

Expected:

```text
BUILD SUCCESS
```

---

### Task 4: live 样本补证方案与执行记录

**Files:**

- Create: `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md`
- May create during execution: `docs/superpowers/ExtractionStructured/progress/live-coverage-samples-YYYYMMDD-HHMMSS/`

**Sample targets:**

- `LLM_REFUSED`
- `EVIDENCE_NOT_COVERING`
- `STRUCTURED_BLOCK_DIRECT`
- `DOWNSTREAM_CONSUMPTION_GAP`

- [x] **Step 1: 创建 live 样本计划文档**

Create `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md`:

```markdown
# 2026-06-22 coverage live 样本补证计划

## 目标

补齐第二轮需要的真实链路样本，验证报告与 workflow 能看见以下状态：

- LLM_REFUSED
- EVIDENCE_NOT_COVERING
- STRUCTURED_BLOCK_DIRECT
- DOWNSTREAM_CONSUMPTION_GAP

## 前置条件

- 本地 dev 服务运行在 `http://localhost:9093`。
- 已存在可 rerun 的任务，优先使用 task `50` 作为成功链路基线。
- 如果 task `50` 无法复现新状态，新增一条专门构造的任务，并记录任务 ID。

## 执行目录

每次 live 验证创建一个目录：

`docs/superpowers/ExtractionStructured/progress/live-coverage-samples-YYYYMMDD-HHMMSS/`

目录内保存：

- `health.txt`
- `task-before.json`
- `nodes-before.json`
- `rerun-response.json`
- `nodes-after.json`
- `report-after.json`
- `report-evidences-after.json`
- `sample-summary.md`

## 判定标准

- `LLM_REFUSED`：`report-after.json` 或 `report-evidences-after.json` 中出现字段级 coverage status `LLM_REFUSED`。
- `EVIDENCE_NOT_COVERING`：字段有模型输出或缺口说明，但证据片段无法覆盖结论。
- `STRUCTURED_BLOCK_DIRECT`：字段值由结构块直接支撑，且仍保留 `sourceUrls`。
- `DOWNSTREAM_CONSUMPTION_GAP`：`nodes-after.json` 中 analyzer / writer / reviewer / delivery 节点出现 `failureCategory=DOWNSTREAM_CONSUMPTION_GAP`。

## 当前结论

task `50` 已在 `2026-06-22-task-50-extract-schema-rerun-success.md` 中证明成功链路可通过；本计划只补新状态命中样本，不重复证明 task `50` 基础 rerun。
```

- [x] **Step 2: 检查本地服务**

Run:

```powershell
curl.exe -s -o docs\superpowers\ExtractionStructured\progress\health.txt -w "%{http_code}" http://localhost:9093/api/task/50
```

Expected:

```text
200
```

If not 200:

```markdown
记录为“live 未执行：本地 dev 服务未启动或 task 50 不存在”，不伪造样本结论。
```

- [x] **Step 3: 创建样本目录并采集 rerun 前现场**

Run:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = "docs\superpowers\ExtractionStructured\progress\live-coverage-samples-$stamp"
New-Item -ItemType Directory -Path $dir | Out-Null
curl.exe http://localhost:9093/api/task/50 > "$dir\task-before.json"
curl.exe http://localhost:9093/api/task/50/nodes > "$dir\nodes-before.json"
curl.exe http://localhost:9093/api/report/50 > "$dir\report-before.json"
curl.exe http://localhost:9093/api/report/50/evidences > "$dir\report-evidences-before.json"
```

Expected:

```text
目录创建成功，四个 before 文件可打开。
```

- [x] **Step 4: 执行节点级 rerun**

Run:

```powershell
curl.exe -X POST http://localhost:9093/api/task/50/nodes/extract_schema/rerun > "$dir\rerun-response.json"
Start-Sleep -Seconds 5
curl.exe http://localhost:9093/api/task/50/nodes > "$dir\nodes-after.json"
curl.exe http://localhost:9093/api/task/50 > "$dir\task-after.json"
curl.exe http://localhost:9093/api/report/50 > "$dir\report-after.json"
curl.exe http://localhost:9093/api/report/50/evidences > "$dir\report-evidences-after.json"
```

Expected:

```text
after 文件包含最新节点状态和报告响应。
```

- [x] **Step 5: 搜索 coverage 状态命中**

Run:

```powershell
rg -n "LLM_REFUSED|EVIDENCE_NOT_COVERING|STRUCTURED_BLOCK_DIRECT|DOWNSTREAM_CONSUMPTION_GAP|failureCategory|evidenceCoverage|statusBreakdown" "$dir"
```

Expected:

```text
至少命中 coverage 或 failureCategory 相关字段；若没有命中新状态，记录“task 50 成功链路未覆盖新状态”。
```

- [x] **Step 6: 写样本总结**

Create `$dir\sample-summary.md`:

```markdown
# coverage live 样本执行记录

## 任务

- taskId: 50
- rerun node: extract_schema
- 执行时间: 由目录时间戳记录

## 命中状态

- LLM_REFUSED: 未命中
- EVIDENCE_NOT_COVERING: 未命中
- STRUCTURED_BLOCK_DIRECT: 未命中
- DOWNSTREAM_CONSUMPTION_GAP: 未命中

## 结论

本次样本用于记录真实链路输出，不把未命中状态伪造成已验证。若 task `50` 仍保持成功链路，应另建专门样本任务触发 coverage 缺口或 reviewer 阻断。
```

根据 `rg` 结果把 `未命中` 改为 `已命中`，并粘贴对应文件名和字段路径。

---

### Task 5: Provider 数据源治理准备

**Files:**

- Read: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorInputProvider.java`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProvider.java`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorInputPackage.java`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/DownstreamEvidenceView.java`
- Modify: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`

本任务只做第二轮后的架构准备，不迁移生产数据源。

- [x] **Step 1: 读取 Provider 当前职责**

Run:

```powershell
rg -n "class RepositoryExtractorInputProvider|interface ExtractorInputProvider|ExtractorInputPackage|PromptSelection|selectPromptEvidence|slimExtractorInputPackage" backend\src\main\java\cn\bugstack\competitoragent\extractor backend\src\test\java\cn\bugstack\competitoragent\extractor backend\src\main\java\cn\bugstack\competitoragent\workflow\contract
```

Expected:

```text
确认 Provider 已承担输入筛选、排序、预算、薄正文和 skippedEvidence 追溯。
```

- [x] **Step 2: 在架构规格追加 Provider 下一阶段边界**

Append under the Provider / P2 section in `2026-06-21-extraction-structured-architecture-spec.md`:

```markdown
### 第二轮后的 Provider 数据源治理边界

第二轮不迁移 `RepositoryExtractorInputProvider` 的底层数据源，但冻结下一阶段接口边界：

- `ExtractorInputProvider` 继续作为 `SchemaExtractorAgent` 的唯一输入入口。
- repository-backed 实现只保留为第一版适配器。
- 下一阶段新增正式端口时，端口输出必须先组装为 `ExtractorInputPackage`，不能让 Agent 重新读取 repository。
- replay/cache 输入源只允许替换 Provider 内部数据源，不允许绕过 Provider 直接写入 Prompt。
- `DownstreamEvidenceView` 继续作为下游轻量视图；如需承载 extractor 内部长正文，应新建输入投影视图，不能把长正文重新塞回 shared output。
```

- [x] **Step 3: 扫描规格中是否出现反向设计**

Run:

```powershell
rg -n "SchemaExtractorAgent.*EvidenceSourceRepository|直接读取 repository|DownstreamEvidenceView.*完整正文|绕过 Provider" docs\superpowers\ExtractionStructured\specs\2026-06-21-extraction-structured-architecture-spec.md backend\src\main\java\cn\bugstack\competitoragent
```

Expected:

```text
如果命中生产代码中的历史兼容构造器，需要确认它只是 fallback 构造，不是主执行路径；如果命中文档中的反向设计，需要改成 Provider-only 叙述。
```

---

### Task 6: 回归验证与文档回链

**Files:**

- Modify: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Modify: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
- Modify: `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`
- Modify: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`
- Read: `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md`

- [x] **Step 1: 运行第二轮核心测试**

Run:

```powershell
mvn -pl backend "-Dtest=DagExecutorTest,ReportServiceTest" test
```

Expected:

```text
BUILD SUCCESS
```

- [x] **Step 2: 运行第一轮防回归测试组**

Run:

```powershell
mvn -pl backend "-Dtest=DagExecutorTest,QualityReviewAgentTest,ReportWriterAgentTest,ReportServiceTest,CompetitorAnalysisAgentTest,RepositoryExtractorInputProviderTest,SharedNodeOutputProjectorContractTest" test
```

Expected:

```text
BUILD SUCCESS
```

- [x] **Step 3: 运行全量 backend 测试**

Run:

```powershell
mvn -pl backend test
```

Expected:

```text
BUILD SUCCESS
```

If this command fails due to unrelated pre-existing tests, record the failing class and rerun the scoped tests from Step 1 and Step 2. Do not claim full backend pass unless Maven prints `BUILD SUCCESS`.

- [x] **Step 4: 运行限定路径 diff 空白检查**

Run:

```powershell
git diff --check -- backend/src/main/java backend/src/test/java docs/superpowers/ExtractionStructured docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md
```

Expected:

```text
无输出
```

Do not run raw `git diff --check` as the only judgment because `backend/logs/competitor-agent.log` has historical trailing whitespace.

- [x] **Step 5: 回写 `ExtractionStructured.md` 当前状态**

Update the P2 section to this shape:

```markdown
| P2 下游消费与质量门禁 | 2026-06-22 第二轮进行中 | `evidenceCoverage` 细化已落地；workflow 正在从 reviewer 两类阻断扩展到 analyzer / writer / reviewer / delivery 统一汇总；task `50` 成功链路已验证 | 剩余为更多 live coverage 状态样本与 Provider 内部数据源治理 |
```

- [x] **Step 6: 回写优化汇总**

Append a second-round progress note:

```markdown
## 2026-06-22 第二轮执行衔接

- 本轮不重复 P0/P1 基建，继续从第一轮剩余 P2 缺口推进。
- workflow 汇总目标从 reviewer 两类阻断扩展到 analyzer / writer / reviewer / delivery 下游消费缺口。
- 报告侧保留 `TRACEABLE / MISSING_EVIDENCE / EMPTY` 粗计数，同时补充 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 等细状态分布。
- live 验证不再重复证明 task `50` 基础链路可通过，重点补 coverage 新状态命中样本。
```

- [x] **Step 7: 回写第一轮计划 P2 状态**

Update the P2 section in `2026-06-20-extraction-structured-optimization-plan.md`:

```markdown
第二轮衔接状态（2026-06-22）：

- `DOWNSTREAM_CONSUMPTION_GAP` 从 reviewer 两类场景继续泛化到 analyzer / writer / reviewer / delivery。
- `evidenceCoverage` 细状态已在 extractor / reviewer 中落地，第二轮补报告侧 statusBreakdown 和 live 样本。
- task `50` 基础 rerun 成功链路已完成，第二轮只补新状态命中样本。
```

- [x] **Step 8: 文档红旗词扫描**

Run:

```powershell
rg -n "TBD|TODO|implement later|fill in details|appropriate|待定|占位|类似" docs\superpowers\ExtractionStructured\plan\2026-06-22-extraction-structured-second-round-plan.md docs\superpowers\ExtractionStructured\progress\2026-06-22-coverage-live-sample-plan.md docs\superpowers\ExtractionStructured\problem\ExtractionStructured.md docs\superpowers\ExtractionStructured\summary\2026-06-21-extraction-structured-optimization-summary.md | Select-String -NotMatch "rg -n"
```

Expected:

```text
无输出
```

本轮 fresh 验证记录（2026-06-22）：

- `mvn -pl backend "-Dtest=DagExecutorTest,ReportServiceTest" test`
  - 结果：`BUILD SUCCESS`，`Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`
- `mvn -pl backend "-Dtest=DagExecutorTest,QualityReviewAgentTest,ReportWriterAgentTest,ReportServiceTest,CompetitorAnalysisAgentTest,RepositoryExtractorInputProviderTest,SharedNodeOutputProjectorContractTest" test`
  - 结果：`BUILD SUCCESS`，`Tests run: 64, Failures: 0, Errors: 0, Skipped: 0`
- `mvn -pl backend test`
  - 结果：`BUILD SUCCESS`；本轮 surefire 汇总为 `Tests run: 658, Failures: 0, Errors: 0, Skipped: 3`
- `git diff --check -- backend/src/main/java backend/src/test/java docs/superpowers/ExtractionStructured docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
  - 结果：无 diff-check 错误，仅有工作区 `LF -> CRLF` 提示。
- 文档红旗词扫描：
  - 结果：`NO_RED_FLAG_TERMS`
- 服务状态：
  - 本轮没有启动 `9093`，最终确认 `PORT_9093_NOT_LISTENING`。

---

## 验收标准

第二轮完成时必须同时满足：

- `DagExecutorTest` 覆盖 analyzer / writer / reviewer 的 `DOWNSTREAM_CONSUMPTION_GAP`，并证明 extractor 失败不会被误归类。
- `ReportServiceTest` 证明报告响应同时保留粗粒度计数和细粒度 `statusBreakdown`。
- live 样本计划存在，并记录 task `50` 或新增任务对 coverage 新状态的真实命中结果；如果本地服务不可用，文档必须明确写“未执行原因”，不能写成已验证。
- `ExtractionStructured.md`、优化汇总、第一轮计划都回链第二轮状态。
- Scoped Maven 回归通过；全量 backend 测试如果未通过，必须写明失败类和是否与本轮改动相关。

## 当前不做

- 不重新设计搜索与采集 3.2。
- 不把 Provider 内部 repository-backed 实现一次性迁移到 replay/cache/正式端口。
- 不把 `DownstreamEvidenceView` 重新扩成携带完整正文的跨节点对象。
- 不改变 `CompetitorKnowledge TASK` 快照定位。
- 不提交 git commit。
- 不为了制造 live 样本而手工改数据库伪造 coverage 状态。

## 停止时必须总结

每次停止都按以下格式总结：

```markdown
## 本次做了什么

- 写清本轮已完成的代码、文档或验证事项。

## 接下来要做什么

- 写清下一步第一项可执行动作和建议入口文件。

## 还剩什么没做

- 写清未完成任务、阻塞条件和是否需要本地服务。

## 验证结果

- 写清已运行命令、是否通过、失败是否与本轮改动相关。
```

## Execution Handoff

计划已保存到 `docs/superpowers/ExtractionStructured/plan/2026-06-22-extraction-structured-second-round-plan.md`。

推荐执行顺序：

1. 先执行 Task 1，确认第一轮边界。
2. 再执行 Task 2 和 Task 3，完成代码侧第二轮核心闭环。
3. 然后执行 Task 4，补 live 样本记录。
4. 最后执行 Task 5 和 Task 6，完成 Provider 下一阶段边界与文档回链。

本计划不包含 commit 步骤，执行完成后由用户自行提交。
