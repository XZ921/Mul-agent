# Agent Collaboration Orchestration P3-1 Analyzer Gap Decision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `analyze_competitors` 的分析缺口从“节点失败保护”升级为可审计、可策略校验、可回放的 `AgentSuggestion -> OrchestrationDecision` 协作决策。

**Architecture:** P3-1 复用 P1/P2 已落地的 Orchestrator 运行期决策链路，不引入自由 DAG、不把 Analyzer 改成调度器。Analyzer 继续只输出分析事实和缺口事实，`AnalyzerSuggestionAssembler` 负责把分析置信度、缺失维度和来源状态转换为 `AgentSuggestion`，`DagExecutor` 在 Analyzer 成功落库后执行通用 suggestion gate，由 `OrchestrationDecisionService / DecisionPolicyService` 决定补证、人工介入或放行下游 Writer。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, AssertJ, Maven

---

## Scope Guard

### P3-1 必须完成

1. 扩展 `AnalysisResult`，新增分析质量字段：
   - `analysisConfidence`
   - `missingAnalysisDimensions`
   - `analysisGapSeverity`
   - `analysisEvidenceState`
2. `CompetitorAnalysisAgent` 在核心字段为空或关键维度缺失时输出结构化缺口事实，而不是只返回一段错误字符串。
3. 新增 `AnalyzerSuggestionAssembler`，只负责把 Analyzer 输出转换成 `AgentSuggestion`，不直接创建动态节点。
4. `OrchestrationDecisionService` 支持 `analyze_competitors` 触发节点，复用 `AgentSuggestion` 决策规则：
   - 有 sourceUrls 的分析证据缺口优先生成 `SUPPLEMENT_EVIDENCE`；
   - 无 sourceUrls 或阻塞级分析缺口进入 `WAIT_FOR_HUMAN`；
   - 无缺口返回 `NO_ACTION`。
5. `DagExecutor` 将 P2 的 extractor 专用 gate 抽成通用 AgentSuggestion gate，支持 `extract_schema` 和 `analyze_competitors`。
6. replay / trace 中能看到 Analyzer 触发的 `ORCHESTRATION_DECISION_RECORDED`，并保留 `sourceUrls / evidenceState / suggestedQueries / inputRefs.agentSuggestionIds`。
7. 保持 Writer 防幻觉边界：Analyzer 缺口未被 Orchestrator 放行时，`write_report` 不能继续执行。

### P3-1 放行口径

P3-1 明确采用“可追溯缺口先记录、最终引用充分性后移”的设计：

1. Analyzer 缺核心维度但仍有 `sourceUrls` 时，Orchestrator 可返回 `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE`，本轮视为“受审计放行”，Writer 可以继续执行，但 trace/replay 必须记录该缺口和补证建议。
2. Analyzer 缺核心维度且没有 `sourceUrls` 时，Orchestrator 必须返回 `WAIT_FOR_HUMAN`，`DagExecutor` 必须把 `analyze_competitors` 标记为 `WAITING_INTERVENTION`，Writer 不得执行。
3. P3-1 不承担 Citation Agent 的最终引用充分性判断，只保证 Analyzer 缺口进入可追溯决策轨迹；章节引用充分性由 P3-2/P3-4 收口。

### P3-1 明确不做

1. 不实现 Writer 章节引用缺口；该能力进入 P3-2。
2. 不实现 Conversation 动作预览读取 OrchestrationDecision；该能力进入 P3-3。
3. 不实现 Citation Agent；该能力进入 P3-4。
4. 不改写 `WorkflowFactory / ExecutionPlanDefinitionBuilder` 的固定 DAG 模板。
5. 不把 `OrchestrationDecisionService` 改成外部 LLM 调用；本轮仍采用规则优先策略。
6. 不新增数据库表；继续复用 `TaskWorkflowEvent` 和既有 trace/replay 投影。
7. 不削弱 P2 已有 `ExtractorSuggestionAssembler` 行为。

---

## Current Stage

当前阶段：P2 已完成前置协作规划与抽取后证据缺口决策，post-fix smoke 证明最小单官网样本会在 Analyzer 阶段被结构化门禁阻断，避免 Writer 凭空扩写。P3-1 的第一目标不是让最小样本直接交付成功，而是把这个 Analyzer 阻断升级为标准协作决策轨迹。

- [x] P0 架构规格冻结：已完成
- [x] P1 终审失败回流 MVP：已完成
- [x] P2 前置协作规划与抽取后证据缺口决策：已完成
- [ ] P3-1 Analyzer 分析缺口协作决策：待执行
- [ ] P3-2 Writer 章节引用缺口：后续
- [ ] P3-3 Conversation 动作预览读取 OrchestrationDecision：后续
- [ ] P3-4 Citation Agent 引用核查：后续

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 冻结 Analyzer 输出质量契约 | 0.5 天 | P2 `AnalysisResult` 与 Analyzer 门禁已存在 |
| Task 2 | 新增 AnalyzerSuggestionAssembler | 0.5 天 | Task 1 契约通过 |
| Task 3 | Orchestrator 支持 Analyzer suggestion 决策 | 0.5 天 | Task 2 能输出建议 |
| Task 4 | DagExecutor 通用 suggestion gate | 1 天 | Task 3 决策规则通过 |
| Task 5 | replay / trace / DTO 可观测性补齐 | 0.5 天 | Task 4 能记录 Analyzer 决策 |
| Task 6 | 聚合验证与文档回链 | 0.5 天 | Task 1-5 通过 |

---

## Risk Register

| 风险 | 影响 | 处理要求 |
| --- | --- | --- |
| Analyzer 失败时没有 outputData | 无法从失败节点生成 `AgentSuggestion` | P3-1 第一版优先处理“Analyzer 成功产出但质量不足”的结构化输出；若现有门禁返回失败，执行时需让失败结果携带最小 JSON outputData，或在 `DagExecutor` 基于 errorMessage 构造 `MISSING_SOURCE` 人工介入建议 |
| 通用 gate 误拦截 Writer/Reviewer | 下游链路被错误阻断 | `DagExecutor` 只允许 `extract_schema` 和 `analyze_competitors` 进入 suggestion gate，其他节点保持原行为 |
| 自动补证循环 | 动态补图无限追加 | 继续使用 `DecisionPolicyService.maxAutoDecisions` 和 `OrchestratorCheckpoint.decisionCount`；本轮不新增绕过策略的自动执行路径 |
| sourceUrls 被内部 JSON 吃掉 | 回放不能证明无幻觉 | 所有 Analyzer suggestion 必须从 `AnalysisResult.sourceUrls / evidenceFragments / sectionEvidenceBundles / downstreamEvidenceViews` 回填来源；无来源必须显式 `MISSING_SOURCE` |
| 与 P2 extractor suggestion 回归冲突 | 抽取后缺口决策失效 | Task 6 必须运行 P2 extractor 聚合测试；失败时优先保持 extractor 行为不变 |

---

## File Structure

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssembler.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssemblerTest.java`

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
  - 增加分析质量和缺口字段。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
  - 归一化 Analyzer 输出时补齐 `analysisConfidence / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState`。
  - 核心字段为空时输出结构化缺口 JSON，供通用 gate 生成 `AgentSuggestion`。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
  - 支持 `analyze_competitors` trigger。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
  - 将 `applyExtractorSuggestionGate(...)` 抽成 `applyAgentSuggestionGate(...)`。
  - 保持 extractor 分支行为不变，新增 analyzer 分支。
- `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`

### Reference Only

- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- `docs/superpowers/agent-collaboration-orchestration/summary/2026-06-24-p2-post-fix-dev-smoke-report.md`
- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

## Task 1: 冻结 Analyzer 输出质量契约

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`

- [ ] **Step 1: 写 Analyzer 契约红灯测试**

在 `CompetitorAnalysisAgentTest` 中新增测试：

```java
@Test
void shouldExposeAnalysisGapMetadataWhenCoreDimensionsMissing() throws Exception {
    when(knowledgeRepository.findByTaskIdOrderByIdAsc(88L)).thenReturn(List.of(
            CompetitorKnowledge.builder()
                    .id(1L)
                    .taskId(88L)
                    .competitorName("Notion AI")
                    .snapshotScope("TASK")
                    .summary("Notion AI 提供工作区 AI 能力")
                    .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                    .evidenceCoverage("{}")
                    .build()
    ));
    when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
            {
              "overview": "只有概览，没有结构化横向分析。",
              "sourceUrls": ["https://www.notion.so/product/ai"]
            }
            """);
    when(llmClient.getModelName()).thenReturn("mock-model");
    when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

    AgentContext context = AgentContext.builder()
            .taskId(88L)
            .taskName("p3-analyzer-gap")
            .subjectProduct("Our Product")
            .analysisDimensions("产品功能,市场定位,价格策略,目标用户,优势判断,短板与风险")
            .currentNodeName("analyze_competitors")
            .build();

    AgentResult result = agent.execute(context);

    assertEquals("SUCCESS", result.getStatus().name());
    JsonNode output = objectMapper.readTree(result.getOutputData());
    assertEquals("LOW", output.path("analysisConfidence").asText());
    assertEquals("HIGH", output.path("analysisGapSeverity").asText());
    assertEquals("PARTIAL_SOURCE", output.path("analysisEvidenceState").asText());
    assertThat(output.path("missingAnalysisDimensions"))
            .extracting(JsonNode::asText)
            .contains("featureComparison", "positioningComparison", "pricingComparison",
                    "targetUserComparison", "strengthsSummary", "weaknessesSummary");
    assertThat(output.path("issueFlags"))
            .extracting(JsonNode::asText)
            .contains("ANALYSIS_CORE_FIELDS_EMPTY");
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest#shouldExposeAnalysisGapMetadataWhenCoreDimensionsMissing" test
```

Expected: FAIL，原因是 `AnalysisResult` 尚无 `analysisConfidence / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState` 字段，或 Analyzer 仍返回失败结果。

- [ ] **Step 3: 扩展 `AnalysisResult`**

在 `AnalysisResult` 中追加字段，注释必须使用中文：

```java
/** 分析置信度，HIGH / MEDIUM / LOW，用于 Orchestrator 判断是否需要补证或人工介入。 */
private String analysisConfidence;

/** Analyzer 未能形成有效结论的核心维度，例如 pricingComparison。 */
private List<String> missingAnalysisDimensions;

/** 分析缺口严重度，NONE / MEDIUM / HIGH / ERROR。 */
private String analysisGapSeverity;

/** 分析阶段证据状态，FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE。 */
private String analysisEvidenceState;
```

- [ ] **Step 4: 实现 Analyzer 质量元数据归一化**

在 `CompetitorAnalysisAgent` 中补充 helper。注意赋值顺序必须是：先由 `normalizeAnalysisResult(...)` 构建基础 `AnalysisResult`，再基于该对象计算缺失维度，最后在 `isCoreAnalysisEmpty(...)` 检查和序列化之前写回质量元数据。

```java
/**
 * 根据 Analyzer 结构化字段完整度生成缺失维度列表。
 * 这里不让 Writer 自己猜缺口，否则会把分析责任推迟到写作阶段。
 */
private List<String> collectMissingAnalysisDimensions(AnalysisResult analysisResult) {
    List<String> missing = new ArrayList<>();
    if (!hasText(analysisResult.getFeatureComparison())) {
        missing.add("featureComparison");
    }
    if (!hasText(analysisResult.getPositioningComparison())) {
        missing.add("positioningComparison");
    }
    if (!hasText(analysisResult.getPricingComparison())) {
        missing.add("pricingComparison");
    }
    if (!hasText(analysisResult.getTargetUserComparison())) {
        missing.add("targetUserComparison");
    }
    if (!hasText(analysisResult.getStrengthsSummary())) {
        missing.add("strengthsSummary");
    }
    if (!hasText(analysisResult.getWeaknessesSummary())) {
        missing.add("weaknessesSummary");
    }
    return missing;
}

/**
 * 分析缺口严重度只看核心字段缺失比例，不做业务事实判断。
 * 业务补证动作仍交给 Orchestrator 和策略服务决定。
 */
private String resolveAnalysisGapSeverity(List<String> missingDimensions) {
    if (missingDimensions == null || missingDimensions.isEmpty()) {
        return "NONE";
    }
    if (missingDimensions.size() >= 6) {
        return "HIGH";
    }
    return missingDimensions.size() >= 3 ? "MEDIUM" : "LOW";
}

private String resolveAnalysisConfidence(String gapSeverity) {
    return switch (gapSeverity) {
        case "NONE" -> "HIGH";
        case "LOW", "MEDIUM" -> "MEDIUM";
        default -> "LOW";
    };
}

private String resolveAnalysisEvidenceState(List<String> sourceUrls, List<String> missingDimensions) {
    if (sourceUrls == null || sourceUrls.isEmpty()) {
        return "MISSING_SOURCE";
    }
    if (missingDimensions != null && !missingDimensions.isEmpty()) {
        return "PARTIAL_SOURCE";
    }
    return "FULL_SOURCE";
}
```

在 `doExecute(...)` 中，`normalizeAnalysisResult(...)` 返回后立即补充以下赋值逻辑：

```java
AnalysisResult analysisResult = normalizeAnalysisResult(
        analysisJson,
        knowledges,
        downstreamEvidenceViews,
        extractorOutput.drafts());

List<String> missingDimensions = collectMissingAnalysisDimensions(analysisResult);
String gapSeverity = resolveAnalysisGapSeverity(missingDimensions);
analysisResult.setMissingAnalysisDimensions(missingDimensions);
analysisResult.setAnalysisGapSeverity(gapSeverity);
analysisResult.setAnalysisConfidence(resolveAnalysisConfidence(gapSeverity));
analysisResult.setAnalysisEvidenceState(resolveAnalysisEvidenceState(
        analysisResult.getSourceUrls(),
        missingDimensions));

if (isCoreAnalysisEmpty(analysisResult)) {
    analysisResult.getIssueFlags().add("ANALYSIS_CORE_FIELDS_EMPTY");
    String outputJson = objectMapper.writeValueAsString(analysisResult);
    return AgentResult.success(outputJson,
            "竞品分析存在核心字段缺口，等待 Orchestrator 决策",
            System.currentTimeMillis(),
            llmClient.getModelName(),
            llmClient.getLastTokenUsage().toJson());
}
```

- [ ] **Step 5: 改造核心字段为空门禁**

将当前 `isCoreAnalysisEmpty(...)` 后直接 `AgentResult.failed(...)` 改为输出成功状态的结构化缺口 JSON，并由 Task 4 的 gate 负责决定是否阻断下游。这里必须复用 Step 4 已经写回质量元数据的 `analysisResult`，不要在门禁分支内重新计算一套缺口字段。

```java
if (isCoreAnalysisEmpty(analysisResult)) {
    analysisResult.getIssueFlags().add("ANALYSIS_CORE_FIELDS_EMPTY");
    String outputJson = objectMapper.writeValueAsString(analysisResult);
    return AgentResult.success(outputJson,
            "竞品分析存在核心字段缺口，等待 Orchestrator 决策",
            System.currentTimeMillis(),
            llmClient.getModelName(),
            llmClient.getLastTokenUsage().toJson());
}
```

- [ ] **Step 6: 运行 Analyzer 测试**

Run:

```bash
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest" test
```

Expected: PASS，现有 Analyzer 成功样例不变，新增缺口样例输出结构化质量元数据。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java
git commit -m "feat: expose analyzer gap metadata"
```

---

## Task 2: 新增 AnalyzerSuggestionAssembler

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssembler.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssemblerTest.java`

- [ ] **Step 1: 写 Analyzer suggestion 转换测试**

Create `AnalyzerSuggestionAssemblerTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerSuggestionAssemblerTest {

    private final AnalyzerSuggestionAssembler assembler = new AnalyzerSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldBuildEvidenceGapSuggestionFromAnalyzerMissingDimensions() {
        String output = """
                {
                  "analysisConfidence": "LOW",
                  "analysisGapSeverity": "HIGH",
                  "analysisEvidenceState": "PARTIAL_SOURCE",
                  "missingAnalysisDimensions": ["pricingComparison", "targetUserComparison"],
                  "sourceUrls": ["https://www.notion.so/product/ai"],
                  "issueFlags": ["ANALYSIS_CORE_FIELDS_EMPTY"]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionId()).isEqualTo("as-task-88-analyze_competitors-1");
        assertThat(suggestion.getProducerAgentType()).isEqualTo("ANALYZER");
        assertThat(suggestion.getSuggestionType()).isEqualTo("ANALYSIS_GAP");
        assertThat(suggestion.getTargetSection()).isEqualTo("analysis");
        assertThat(suggestion.getSeverity()).isEqualTo("HIGH");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
        assertThat(suggestion.getSourceUrls()).containsExactly("https://www.notion.so/product/ai");
        assertThat(suggestion.getSuggestedQueries())
                .contains("pricingComparison official source", "targetUserComparison official source");
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }

    @Test
    void shouldReturnEmptyWhenAnalyzerHasNoGap() {
        String output = """
                {
                  "analysisConfidence": "HIGH",
                  "analysisGapSeverity": "NONE",
                  "analysisEvidenceState": "FULL_SOURCE",
                  "missingAnalysisDimensions": [],
                  "sourceUrls": ["https://www.notion.so/product/ai"]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void shouldMarkMissingSourceWhenAnalyzerGapHasNoUrls() {
        String output = """
                {
                  "analysisConfidence": "LOW",
                  "analysisGapSeverity": "HIGH",
                  "analysisEvidenceState": "MISSING_SOURCE",
                  "missingAnalysisDimensions": ["pricingComparison"],
                  "sourceUrls": []
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestions.get(0).getSourceUrls()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=AnalyzerSuggestionAssemblerTest" test
```

Expected: FAIL，`AnalyzerSuggestionAssembler` 不存在。

- [ ] **Step 3: 实现 `AnalyzerSuggestionAssembler`**

Create `AnalyzerSuggestionAssembler`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analyzer 输出到 AgentSuggestion 的转换器。
 * 它只表达分析维度缺口，不直接决定补证、重跑或人工介入。
 */
@Slf4j
@Component
public class AnalyzerSuggestionAssembler {

    private final ObjectMapper objectMapper;

    public AnalyzerSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Analyzer 输出中提取分析缺口建议。
     * 解析失败时返回空建议，避免脏输出触发不可解释的动态补图。
     */
    public List<AgentSuggestion> fromAnalyzerOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        List<String> missingDimensions = readStringList(output.path("missingAnalysisDimensions"));
        String severity = upper(output.path("analysisGapSeverity").asText("NONE"));
        if (missingDimensions.isEmpty() || "NONE".equals(severity)) {
            return List.of();
        }
        List<String> sourceUrls = readStringList(output.path("sourceUrls"));
        return List.of(AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-1")
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("ANALYZER")
                .suggestionType("ANALYSIS_GAP")
                .targetSection("analysis")
                .summary(buildSummary(missingDimensions, severity))
                .severity(resolveSeverity(severity))
                .confidence(resolveConfidence(output.path("analysisConfidence").asText(null)))
                .sourceUrls(sourceUrls)
                .evidenceState(resolveEvidenceState(output.path("analysisEvidenceState").asText(null), sourceUrls))
                .suggestedQueries(buildSuggestedQueries(missingDimensions))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized());
    }

    private JsonNode toJsonNode(Object rawOutput) {
        if (rawOutput == null) {
            return null;
        }
        if (rawOutput instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            if (rawOutput instanceof String rawString) {
                return rawString.isBlank() ? null : objectMapper.readTree(rawString);
            }
            return objectMapper.valueToTree(rawOutput);
        } catch (Exception e) {
            log.warn("failed to parse analyzer output for agent suggestion", e);
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private String buildSummary(List<String> missingDimensions, String severity) {
        return "Analyzer 存在 " + severity + " 级分析缺口，需要补充证据或人工确认；缺失维度："
                + String.join("、", missingDimensions);
    }

    private String resolveSeverity(String severity) {
        return switch (severity) {
            case "ERROR", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private Double resolveConfidence(String confidence) {
        String normalized = upper(confidence);
        return switch (normalized) {
            case "HIGH" -> 0.90d;
            case "MEDIUM" -> 0.65d;
            default -> 0.35d;
        };
    }

    private EvidenceState resolveEvidenceState(String evidenceState, List<String> sourceUrls) {
        String normalized = upper(evidenceState);
        if ("MISSING_SOURCE".equals(normalized)) {
            return EvidenceState.MISSING_SOURCE;
        }
        if ("PARTIAL_SOURCE".equals(normalized)) {
            return EvidenceState.PARTIAL_SOURCE;
        }
        return sourceUrls == null || sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
    }

    private List<String> buildSuggestedQueries(List<String> missingDimensions) {
        List<String> queries = new ArrayList<>();
        for (String dimension : missingDimensions) {
            queries.add(dimension + " official source");
        }
        return queries;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: 运行转换器测试**

Run:

```bash
mvn -pl backend "-Dtest=AnalyzerSuggestionAssemblerTest" test
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssembler.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssemblerTest.java
git commit -m "feat: assemble analyzer gap suggestions"
```

---

## Task 3: Orchestrator 支持 Analyzer Suggestion 决策

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`

- [ ] **Step 1: 写 Analyzer 决策红灯测试**

在 `OrchestrationDecisionServiceTest` 中新增：

```java
@Test
void shouldCreateSupplementDecisionFromAnalyzerSuggestionWithSources() {
    AgentSuggestion suggestion = AgentSuggestion.builder()
            .suggestionId("as-task-88-analyze_competitors-1")
            .taskId(88L)
            .producerNodeName("analyze_competitors")
            .producerAgentType("ANALYZER")
            .suggestionType("ANALYSIS_GAP")
            .targetSection("analysis")
            .summary("Analyzer 缺少 pricingComparison")
            .severity("HIGH")
            .confidence(0.35d)
            .sourceUrls(List.of("https://www.notion.so/product/ai"))
            .evidenceState(EvidenceState.PARTIAL_SOURCE)
            .suggestedQueries(List.of("pricingComparison official source"))
            .suggestedTargetNode("collect_sources")
            .build()
            .normalized();
    OrchestrationContext context = OrchestrationContext.builder()
            .taskId(88L)
            .triggerNodeName("analyze_competitors")
            .passed(false)
            .agentSuggestions(List.of(suggestion))
            .sourceUrls(List.of("https://www.notion.so/product/ai"))
            .evidenceState(EvidenceState.PARTIAL_SOURCE)
            .build();

    List<OrchestrationDecision> decisions = service.decide(context);

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
    assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
    assertThat(decisions.get(0).getTargetSection()).isEqualTo("analysis");
    assertThat(decisions.get(0).getInputRefs())
            .containsEntry("agentSuggestionIds", List.of("as-task-88-analyze_competitors-1"));
}

@Test
void shouldWaitForHumanFromAnalyzerSuggestionWithoutSources() {
    AgentSuggestion suggestion = AgentSuggestion.builder()
            .suggestionId("as-task-88-analyze_competitors-1")
            .taskId(88L)
            .producerNodeName("analyze_competitors")
            .producerAgentType("ANALYZER")
            .suggestionType("ANALYSIS_GAP")
            .targetSection("analysis")
            .summary("Analyzer 缺少全部核心维度")
            .severity("HIGH")
            .confidence(0.35d)
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .suggestedTargetNode("collect_sources")
            .build()
            .normalized();
    OrchestrationContext context = OrchestrationContext.builder()
            .taskId(88L)
            .triggerNodeName("analyze_competitors")
            .passed(false)
            .agentSuggestions(List.of(suggestion))
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build();

    List<OrchestrationDecision> decisions = service.decide(context);

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest#shouldCreateSupplementDecisionFromAnalyzerSuggestionWithSources,OrchestrationDecisionServiceTest#shouldWaitForHumanFromAnalyzerSuggestionWithoutSources" test
```

Expected: FAIL，当前 `OrchestrationDecisionService` 只处理 `extract_schema` 和 `quality_check_final`。

- [ ] **Step 3: 扩展 `decide(...)` 触发节点分支**

修改 `OrchestrationDecisionService.decide(...)`。新增 `analyze_competitors` 分支必须插入在 `if (!"quality_check_final".equals(context.getTriggerNodeName()))` 兜底判断之前，否则会被 P1 的终审兜底逻辑提前返回 `NO_ACTION`。

```java
if ("extract_schema".equals(context.getTriggerNodeName())) {
    return decideExtractorSuggestions(context);
}
if ("analyze_competitors".equals(context.getTriggerNodeName())) {
    return decideAnalyzerSuggestions(context);
}
if (!"quality_check_final".equals(context.getTriggerNodeName())) {
    return List.of(noAction(context, "P1/P2/P3-1 当前仅处理 extract_schema、analyze_competitors 和 quality_check_final 反馈。"));
}
```

新增方法：

```java
/**
 * Analyzer 只提交分析缺口事实，Orchestrator 在这里决定是否补证或人工介入。
 */
private List<OrchestrationDecision> decideAnalyzerSuggestions(OrchestrationContext context) {
    List<AgentSuggestion> suggestions = context.getAgentSuggestions();
    if (suggestions == null || suggestions.isEmpty()) {
        return List.of(noAction(context, "analyze_competitors 未产生 AgentSuggestion，无需编排动作。"));
    }
    for (AgentSuggestion suggestion : suggestions) {
        if ("ANALYSIS_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                && (suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())) {
            return List.of(waitForHuman(context, "Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补图。"));
        }
    }
    for (AgentSuggestion suggestion : suggestions) {
        if ("ANALYSIS_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
            return List.of(supplementEvidenceFromSuggestion(context, suggestion));
        }
    }
    return List.of(noAction(context, "Analyzer 建议未命中 P3-1 可执行策略。"));
}
```

- [ ] **Step 4: 运行 Orchestrator 决策测试**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest" test
```

Expected: PASS，P1 终审、P2 extractor 和 P3-1 analyzer 分支同时通过。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java
git commit -m "feat: route analyzer suggestions through orchestrator"
```

---

## Task 4: DagExecutor 通用 AgentSuggestion Gate

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 写 DagExecutor 红灯测试**

在 `DagExecutorTest` 中新增：

```java
@Test
void shouldHoldAnalyzerWhenSuccessfulOutputContainsBlockingAnalysisSuggestion() {
    Long taskId = 1008L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();
    TaskNode extractSchema = TaskNode.builder()
            .id(801L)
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
            .id(802L)
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
            .id(803L)
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
            List.of(new AlwaysSuccessExtractorAgent(), new AnalyzerAnalysisGapAgent(), new AlwaysSuccessWriterAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService()
    );

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("analyzer-suggestion-test").build());

    assertEquals(TaskNodeStatus.WAITING_INTERVENTION, analyzer.getStatus());
    assertEquals(TaskNodeStatus.PENDING, writer.getStatus());
    assertTrue(analyzer.getInterventionReason().contains("Analyzer"));
}
```

在测试类底部新增测试 Agent：

```java
private static final class AnalyzerAnalysisGapAgent implements Agent {

    @Override
    public AgentType getType() {
        return AgentType.ANALYZER;
    }

    @Override
    public String getName() {
        return "analyzer-gap-agent";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.success("""
                {
                  "analysisConfidence": "LOW",
                  "analysisGapSeverity": "HIGH",
                  "analysisEvidenceState": "MISSING_SOURCE",
                  "missingAnalysisDimensions": ["featureComparison", "pricingComparison"],
                  "sourceUrls": []
                }
                """, "Analyzer 存在分析缺口");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=DagExecutorTest#shouldHoldAnalyzerWhenSuccessfulOutputContainsBlockingAnalysisSuggestion" test
```

Expected: FAIL，当前 `DagExecutor` 只对 `extract_schema` 调用 suggestion gate。

- [ ] **Step 3: 注入 `AnalyzerSuggestionAssembler`**

在 `DagExecutor` 构造函数中新增依赖：

```java
private final AnalyzerSuggestionAssembler analyzerSuggestionAssembler;
```

构造器参数顺序建议放在 `ExtractorSuggestionAssembler` 后面，测试 helper 同步补 mock 或真实实例。

- [ ] **Step 4: 抽通用 gate**

将 `applyExtractorSuggestionGate(...)` 替换为：

```java
TaskNode gatedNode = applyAgentSuggestionGate(taskId, completedNode, result);
```

新增方法：

```java
/**
 * AgentSuggestion 是运行期协作决策的统一入口。
 * 当前只允许 Extractor 和 Analyzer 进入该 gate，避免误拦截 Writer / Reviewer。
 */
private TaskNode applyAgentSuggestionGate(Long taskId, TaskNode completedNode, AgentResult result) {
    if (result == null
            || result.getStatus() != TaskNodeStatus.SUCCESS
            || completedNode == null
            || completedNode.getOutputData() == null
            || completedNode.getOutputData().isBlank()) {
        return completedNode;
    }
    List<AgentSuggestion> suggestions = buildAgentSuggestions(taskId, completedNode);
    if (suggestions.isEmpty()) {
        return completedNode;
    }
    List<String> sourceUrls = extractSourceUrls(completedNode.getOutputData());
    OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .taskId(taskId)
            .planVersionId(completedNode.getPlanVersionId())
            .branchKey(completedNode.getBranchKey())
            .triggerNodeName(completedNode.getNodeName())
            .passed(false)
            .agentSuggestions(suggestions)
            .sourceUrls(sourceUrls)
            .evidenceState(sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.PARTIAL_SOURCE)
            .inputSummary(completedNode.getNodeName() + " 输出后发现协作建议，进入 Orchestrator 决策。")
            .build()
            .normalized();
    List<OrchestrationDecision> decisions = orchestrationDecisionService.decide(orchestrationContext);
    for (OrchestrationDecision decision : decisions) {
        recordAgentDecisionTrace(taskId, completedNode, decision);
    }
    return decisions.stream()
            .filter(decision -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()))
            .findFirst()
            .map(decision -> markNodeWaitingForIntervention(completedNode, decision))
            .orElse(completedNode);
}
```

新增 suggestion 分派：

```java
private List<AgentSuggestion> buildAgentSuggestions(Long taskId, TaskNode completedNode) {
    if ("extract_schema".equals(completedNode.getNodeName())) {
        return extractorSuggestionAssembler.fromExtractorOutput(
                taskId,
                completedNode.getNodeName(),
                completedNode.getOutputData());
    }
    if ("analyze_competitors".equals(completedNode.getNodeName())) {
        return analyzerSuggestionAssembler.fromAnalyzerOutput(
                taskId,
                completedNode.getNodeName(),
                completedNode.getOutputData());
    }
    return List.of();
}
```

将 `recordExtractorDecisionTrace` 和 `markExtractorWaitingForIntervention` 改名为通用方法：

```java
private void recordAgentDecisionTrace(Long taskId, TaskNode completedNode, OrchestrationDecision decision) {
    if (orchestrationTraceService == null || decision == null) {
        return;
    }
    orchestrationTraceService.recordDecision(taskId, completedNode, decision, null, null);
}

private TaskNode markNodeWaitingForIntervention(TaskNode completedNode, OrchestrationDecision decision) {
    completedNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
    completedNode.setFailureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED);
    completedNode.setInterventionReason(decision.getReason());
    completedNode.setErrorMessage(decision.getReason());
    completedNode.setCompletedAt(LocalDateTime.now());
    return nodeRepository.save(completedNode);
}
```

- [ ] **Step 5: 保持自动补证分支不阻断当前轮**

如果 Orchestrator 返回 `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE`，P3-1 明确将其视为“受审计放行”：当前 Analyzer 节点保持 `SUCCESS`，Writer 可以继续执行，但 trace/replay 必须保留补证建议、缺失维度、`sourceUrls / evidenceState` 和 `agentSuggestionIds`。如果 Orchestrator 返回 `WAIT_FOR_HUMAN`，才将 Analyzer 改为 `WAITING_INTERVENTION` 并阻断 Writer。后续由 P3-2/P3-4 再细化自动补证实际执行、章节引用充分性和 Citation Agent 最终核查。

- [ ] **Step 6: 运行 DagExecutor 局部测试**

Run:

```bash
mvn -pl backend "-Dtest=DagExecutorTest#shouldHoldExtractorWhenSuccessfulOutputContainsBlockingSuggestion,DagExecutorTest#shouldHoldAnalyzerWhenSuccessfulOutputContainsBlockingAnalysisSuggestion,DagExecutorTest#shouldClassifyAnalyzerConsumptionFailureAsDownstreamConsumptionGapWhenExtractorSucceeded" test
```

Expected: PASS，extractor 旧 gate 不回归，Analyzer 缺来源时进入 `WAITING_INTERVENTION`，历史失败分类仍保留。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
git commit -m "feat: gate analyzer suggestions in dag executor"
```

---

## Task 5: Replay / Trace / Smoke 可观测性补齐

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Reference: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`

- [ ] **Step 1: 补充 trace 断言**

如果 `DagExecutorTest` 已有 `orchestrationTraceService` mock 注入，增加 verify：

```java
verify(orchestrationTraceService, atLeastOnce())
        .recordDecision(eq(taskId), eq(analyzer), argThat(decision ->
                "WAIT_FOR_HUMAN".equals(decision.getDecisionType())
                        && "analyze_competitors".equals(decision.getTriggerNodeName())
                        && decision.getInputRefs().containsKey("agentSuggestionIds")), isNull(), isNull());
```

如果当前 helper 没暴露 mock，先扩展 `newDagExecutor(...)` helper，允许传入 `OrchestrationTraceService`。

- [ ] **Step 2: 补充 Orchestrator 直接调用 smoke 断言**

在 `CollaborationPlanningSmokeTest` 中新增或扩展 P3-1 场景，先用直接调用锁定 `AnalyzerSuggestionAssembler -> OrchestrationDecisionService` 的规则行为：

```java
@Test
void shouldRouteAnalyzerGapSuggestionToOrchestratorDecision() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    OrchestrationDecisionService orchestrationDecisionService =
            new OrchestrationDecisionService(new OrchestrationDecisionAdapter());
    AnalyzerSuggestionAssembler assembler = new AnalyzerSuggestionAssembler(objectMapper);
    List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(99L, "analyze_competitors", """
            {
              "analysisConfidence": "LOW",
              "analysisGapSeverity": "HIGH",
              "analysisEvidenceState": "MISSING_SOURCE",
              "missingAnalysisDimensions": ["pricingComparison"],
              "sourceUrls": []
            }
            """);

    List<OrchestrationDecision> decisions = orchestrationDecisionService.decide(OrchestrationContext.builder()
            .taskId(99L)
            .triggerNodeName("analyze_competitors")
            .agentSuggestions(suggestions)
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build());

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    assertThat(decisions.get(0).getInputRefs())
            .containsEntry("agentSuggestionIds", List.of("as-task-99-analyze_competitors-1"));
}
```

- [ ] **Step 3: 补充 DagExecutor 端到端 smoke 断言**

在 `CollaborationPlanningSmokeTest` 中新增一个通过 `DagExecutor` 跑完整路径的场景，覆盖 `Agent 执行 -> AnalyzerSuggestionAssembler -> Orchestrator -> Gate -> Writer 是否继续`。该测试可以复用 `DagExecutorTest` 中的轻量 Agent 思路，但必须放在 smoke 层验证跨组件串联：

```java
@Test
void shouldRouteAnalyzerGapThroughDagExecutorGateBeforeWriter() {
    Long taskId = 1099L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();
    TaskNode extractSchema = TaskNode.builder()
            .id(10991L)
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
            .id(10992L)
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
            .id(10993L)
            .taskId(taskId)
            .nodeName("write_report")
            .agentType(AgentType.WRITER)
            .dependsOn("[\"analyze_competitors\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(2)
            .build();

    DagExecutor executor = newDagExecutorForSmoke(
            task,
            List.of(extractSchema, analyzer, writer),
            List.of(new SmokeExtractorAgent(), new SmokeAnalyzerMissingSourceAgent(), new SmokeWriterAgent()));

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("p3-1-smoke").build());

    assertThat(analyzer.getStatus()).isEqualTo(TaskNodeStatus.WAITING_INTERVENTION);
    assertThat(writer.getStatus()).isEqualTo(TaskNodeStatus.PENDING);
    assertThat(analyzer.getInterventionReason()).contains("Analyzer");
}
```

执行时需要在 `CollaborationPlanningSmokeTest` 中补齐以下测试支撑：

```java
private DagExecutor newDagExecutorForSmoke(AnalysisTask task, List<TaskNode> nodes, List<Agent> agents) {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
    AgentLogService agentLogService = mock(AgentLogService.class);
    TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
    TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);
    when(lockService.tryAcquireNodeExecutionLock(anyLong(), anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
    when(lockService.releaseNodeExecutionLock(anyLong(), anyString(), anyString()))
            .thenReturn(true);
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId())).thenReturn(nodes);
    when(nodeRepository.findById(any())).thenAnswer(invocation -> {
        Long nodeId = invocation.getArgument(0);
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
    });
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new DagExecutor(
            nodeRepository,
            taskRepository,
            new SpringAgentCapabilityRegistry(agents),
            objectMapper,
            snapshotCacheService,
            lockService,
            taskEventPublisher,
            agentLogService,
            mock(WorkflowEventPublisher.class),
            mock(TaskNodeExecutionAttemptRepository.class),
            mock(WorkflowDeadLetterRecordRepository.class),
            new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, taskEventPublisher),
            new RuntimeEventEmitter(taskEventPublisher, agentLogService, objectMapper),
            new DynamicPlanAppender(
                    taskRepository,
                    nodeRepository,
                    mock(DynamicTaskGraphService.class),
                    mock(TaskPlanRepository.class),
                    objectMapper,
                    mock(OrchestrationDecisionService.class),
                    mock(DecisionPolicyService.class),
                    mock(DecisionExecutorAdapter.class),
                    mock(OrchestrationTraceService.class)),
            mock(TaskQuotaCoordinator.class),
            new ExtractorSuggestionAssembler(objectMapper),
            new AnalyzerSuggestionAssembler(objectMapper),
            new OrchestrationDecisionService(new OrchestrationDecisionAdapter()),
            mock(OrchestrationTraceService.class),
            List.of());
}
```

Task 4 会把 `AnalyzerSuggestionAssembler` 加入 `DagExecutor` 主构造器；如果执行时构造器参数顺序和上方代码不一致，必须同步更新 smoke helper，使它显式传入 `ExtractorSuggestionAssembler / AnalyzerSuggestionAssembler / OrchestrationDecisionService / OrchestrationTraceService`，不能退回默认构造器，否则端到端测试会绕过 Analyzer gate。

- [ ] **Step 4: 确认 replay 不需要新增事件类型**

检查 `TaskReplayProjectionService` 是否已经投影 `ORCHESTRATION_DECISION_RECORDED`。如果已有，不新增枚举；只在测试里确认事件源节点可以是 `analyze_competitors`。如果没有覆盖，新增 replay 测试：

```java
@Test
void shouldProjectAnalyzerOrchestrationDecisionTrace() {
    TaskWorkflowEvent event = TaskWorkflowEvent.builder()
            .taskId(99L)
            .nodeName("analyze_competitors")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .eventPayload("""
                    {
                      "decisionId": "od-99-analyze_competitors-human",
                      "triggerNodeName": "analyze_competitors",
                      "decisionType": "WAIT_FOR_HUMAN",
                      "sourceUrls": [],
                      "evidenceState": "MISSING_SOURCE"
                    }
                    """)
            .sourceUrls("[]")
            .build();

    TaskReplayResponse replay = projectionService.project(99L, List.of(event));

    assertThat(replay.getTimeline())
            .anySatisfy(item -> {
                assertThat(item.getNodeName()).isEqualTo("analyze_competitors");
                assertThat(item.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
            });
}
```

- [ ] **Step 5: 运行可观测性测试**

Run:

```bash
mvn -pl backend "-Dtest=DagExecutorTest,CollaborationPlanningSmokeTest,TaskReplayProjectionServiceTest" test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java
git commit -m "test: cover analyzer orchestration trace"
```

---

## Task 6: 聚合验证与文档回链

**Files:**
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-1-analyzer-gap-decision-implementation-plan.md`

- [ ] **Step 1: 运行 P3-1 局部聚合**

Run:

```bash
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest,AnalyzerSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest,CollaborationPlanningSmokeTest,TaskReplayProjectionServiceTest" test
```

Expected: PASS。该命令覆盖 Analyzer 输出契约、Analyzer suggestion、Orchestrator 决策、DagExecutor gate、replay/trace。

- [ ] **Step 2: 运行 P1+P2+P3-1 编排聚合**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest" test
```

Expected: PASS。该命令证明 P1 终审回流、P2 extractor 缺口和 P3-1 analyzer 缺口没有互相回归。

- [ ] **Step 3: 运行 backend 全量回归**

Run:

```bash
mvn -pl backend test
```

Expected: PASS。若失败测试名称包含 `AnalyzerSuggestionAssembler`、`CompetitorAnalysisAgent`、`OrchestrationDecisionService`、`DagExecutor`、`TaskReplayProjectionService`，必须在 P3-1 范围内修复。

- [ ] **Step 4: 更新总蓝图 3.4 状态**

在 `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 的 `3.4 Agent 协作编排层` 段落追加：

```markdown
- P3-1 实施：`✅/🟡` Analyzer 分析缺口已接入标准 `AgentSuggestion -> OrchestrationDecision` 协作决策链路；当 `analyze_competitors` 缺核心分析维度时，不再只依赖节点失败字符串阻断，而是记录可审计的分析缺口建议、来源状态和 Orchestrator 决策。Writer、Conversation、Citation 仍留在后续 P3 子阶段。
```

执行时根据真实测试结果选择 `✅` 或 `🟡`。

- [ ] **Step 5: 更新 3.4 架构规格验收记录**

在 `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md` 的 P3 或实现验收附近追加：

```markdown
2026-06-24 P3-1 自动化实现记录：Analyzer 输出新增 `analysisConfidence / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState`，`AnalyzerSuggestionAssembler` 已把分析缺口转成 `AgentSuggestion`，`OrchestrationDecisionService / DagExecutor` 已支持 `analyze_competitors` 触发的受策略保护决策。Writer、Conversation 和 Citation 仍未进入本轮范围。
```

- [ ] **Step 6: 更新本计划执行进度**

在本文末尾 `2026-06-24 执行进度` 中把完成项改成 `[x]`，写入实际验证命令和结果。

- [ ] **Step 7: Commit**

```bash
git add docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-1-analyzer-gap-decision-implementation-plan.md
git commit -m "docs: record p3-1 analyzer orchestration implementation"
```

---

## P3-1 Live Smoke 建议

本计划的自动化通过后，再执行真实 smoke。P2 post-fix 已证明单官网样本会在 Analyzer 阶段阻断，因此 P3-1 live smoke 应使用两组样本：

| 样本 | 目的 | 预期 |
| --- | --- | --- |
| 单官网弱证据样本 | 验证 Analyzer 缺口进入 Orchestrator trace | `analyze_competitors` 出现 `WAITING_INTERVENTION` 或补证决策，`write_report` 不执行 |
| 官网 + 产品文档 + 定价页 | 验证充足证据下可继续进入 Writer | Analyzer 输出核心字段，若无缺口则 `NO_ACTION` 或不触发 suggestion gate |

推荐请求输入：

```json
{
  "taskName": "P3-1 Analyzer gap smoke",
  "subjectProduct": "Our Product",
  "competitorNames": ["Notion AI"],
  "competitorUrls": [
    "https://www.notion.so/product/ai",
    "https://www.notion.so/help/guides/category/notion-ai",
    "https://www.notion.so/pricing"
  ],
  "analysisDimensions": ["产品功能", "市场定位", "价格策略", "目标用户", "优势判断", "短板与风险"],
  "sourceScope": "OFFICIAL_ONLY",
  "reportTemplate": "STANDARD_COMPETITOR_ANALYSIS_V1"
}
```

验收检查：

1. `GET /api/task/{taskId}/replay` 能看到 Analyzer 触发的 `ORCHESTRATION_DECISION_RECORDED`。
2. `GET /api/task/{taskId}/nodes` 中 `analyze_competitors.sourceUrls` 不为空，或明确显示 `MISSING_SOURCE`。
3. Analyzer 缺口样本中 `write_report` 保持 `PENDING`，不能凭空生成报告。
4. 充足证据样本中 Analyzer 核心字段非空，Writer 可继续执行。

---

## Plan Self-Review

### Spec Coverage

1. 架构规格 P3 第 1 项“Analyzer 输出分析置信度和需要补证的维度”：Task 1 覆盖。
2. 同一协议接入：Task 2-4 将 Analyzer 接入 `AgentSuggestion -> OrchestrationDecision`。
3. `sourceUrls / evidenceState` 红线：Task 1-3 和 Task 5 覆盖。
4. 避免 Writer 幻觉扩写：Task 4 gate 覆盖。
5. Delivery / Audit 决策轨迹的第一步：Task 5 通过 trace/replay 覆盖。

### Placeholder Scan

本文不包含空白待填项；执行结果处只允许在 Task 6 完成后把实际 PASS/FAIL 写回进度区。

### Type Consistency

1. 新字段名统一为 `analysisConfidence / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState`。
2. Analyzer suggestion 类型统一为 `ANALYSIS_GAP`。
3. Analyzer 触发节点统一为 `analyze_competitors`。
4. 缺口决策继续使用现有 `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE / WAIT_FOR_HUMAN / MANUAL_REVIEW`。

---

## 2026-06-24 执行进度

当前阶段：P3-1 计划已完成自查，等待执行。

- [x] 读取 P2 最新总结、P3 架构规格和总蓝图边界
- [x] 确认 P3-1 范围：Analyzer 分析缺口协作决策
- [x] 编写 P3-1 可执行计划
- [ ] Task 1：Analyzer 输出质量契约
- [ ] Task 2：AnalyzerSuggestionAssembler
- [ ] Task 3：Orchestrator Analyzer suggestion 决策
- [ ] Task 4：DagExecutor 通用 suggestion gate
- [ ] Task 5：Replay / trace / smoke 可观测性
- [ ] Task 6：聚合验证与文档回链

当前阶段：P3-1 计划编写
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成
