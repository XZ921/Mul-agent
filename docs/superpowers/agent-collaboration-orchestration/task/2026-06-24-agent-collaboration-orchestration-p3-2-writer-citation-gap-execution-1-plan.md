# Agent Collaboration Orchestration P3-2 Writer Citation Gap Execution 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `write_report / rewrite_report` 把章节引用缺口输出为可审计的 `AgentSuggestion`，并接入现有 `OrchestrationDecision` 决策轨迹，防止无来源章节继续静默进入质检。

**Architecture:** P3-2 执行 1 复用 P1/P2/P3-1 已稳定的 `AgentSuggestion -> OrchestrationDecision -> trace/replay` 协作链路，不建设完整 Citation Agent。Writer 仍只负责写作和暴露引用缺口事实，`WriterSuggestionAssembler` 负责把 Writer 输出转换为建议，`OrchestrationDecisionService / DagExecutor` 负责记录决策并在无来源缺口时阻断下游 Reviewer。

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JPA, JUnit 5, Mockito, AssertJ, Maven

---

## Source Context

1. 最新总结：`docs/superpowers/agent-collaboration-orchestration/progress/2026-06-24-p3-1-task6-progress.md` 已确认 P3-1 自动化实现、P1+P2+P3-1 编排聚合与 `mvn -pl backend test` 全量回归通过。
2. 最新 smoke：`docs/superpowers/agent-collaboration-orchestration/summary/2026-06-24-p2-post-fix-dev-smoke-report.md` 证明最小单官网样本会在 Analyzer 阶段阻断 Writer，当前 P3-2 的目标不是绕过 Analyzer，而是在 Writer 已执行时显式暴露章节引用缺口。
3. 架构规格：`docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md` P3 明确要求 Writer 输出章节引用缺口，Citation Agent 后移，但 `sourceUrls / evidenceState` 红线不能后移。
4. 总蓝图：`docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 已记录 P3-1 完成，Writer、Conversation、Citation 留在后续 P3 子阶段。

## Current Stage

当前阶段：P3-2 执行 1 计划编写完成后待执行；本轮只定义 Writer 章节引用缺口的最小可运行闭环。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行
- [ ] 质检复核：待执行

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | --- | --- |
| Task 1 | 冻结 Writer 章节引用缺口契约与检测器 | 0.5 天 | P3-1 已完成，`SectionEvidenceBundle` 可用 |
| Task 2 | ReportWriterAgent 输出引用缺口元数据 | 0.5 天 | Task 1 契约通过 |
| Task 3 | 新增 WriterSuggestionAssembler | 0.5 天 | Task 2 输出稳定 |
| Task 4 | Orchestrator 与 DagExecutor 接入 Writer suggestion gate | 1 天 | Task 3 能生成建议 |
| Task 5 | replay / smoke / 文档回链 | 0.5 天 | Task 1-4 通过 |

## Scope Guard

### P3-2 执行 1 必须完成

1. Writer 输出新增以下可追溯字段：
   - `writerEvidenceState`
   - `citationGapSeverity`
   - `missingCitationSections`
   - `sectionCitationGaps`
2. 新增 `WriterCitationGap` 与 `WriterCitationGapInspector`，把 `SectionEvidenceBundle` 中的无来源、缺字段、章节证据缺口转换成稳定缺口对象。
3. 新增 `WriterSuggestionAssembler`，只把 Writer 缺口事实转换为 `AgentSuggestion`，不直接创建动态 DAG。
4. `OrchestrationDecisionService` 支持 `write_report / rewrite_report` 触发：
   - 无 `sourceUrls` 的 `CITATION_GAP` 必须进入 `WAIT_FOR_HUMAN`；
   - 有 `sourceUrls` 的 `CITATION_GAP` 记录 `REWRITE_ONLY / REWRITE_SECTION` 决策轨迹，交给后续 Reviewer / rewrite 链路继续处理。
5. `DagExecutor` 的通用 suggestion gate 支持 Writer 节点，但只允许 `extract_schema / analyze_competitors / write_report / rewrite_report` 进入 gate。
6. replay / trace 能看到 Writer 触发的 `ORCHESTRATION_DECISION_RECORDED`，并保留 `sourceUrls / evidenceState / inputRefs.agentSuggestionIds`。
7. 自动化测试覆盖无来源阻断、带来源放行留痕、P1/P2/P3-1 不回归。

### P3-2 执行 1 明确不做

1. 不实现完整 Citation Agent，不核查每个 `[证据：EID]` 是否真实支撑具体句子；该能力进入 P3-4。
2. 不接入 Conversation 动作预览读取 OrchestrationDecision；该能力进入 P3-3。
3. 不让 Writer 直接决定补采、重写或人工介入动作。
4. 不让 Orchestrator 直接调用 Collector / Writer。
5. 不新增数据库表，继续复用 `TaskWorkflowEvent`、节点 `outputData` 和现有 trace/replay 投影。
6. 不改写 `WorkflowFactory / ExecutionPlanDefinitionBuilder` 固定 DAG 模板。

## Risk Register

| 风险 | 影响 | 处理要求 |
| --- | --- | --- |
| `DagExecutor` 构造器继续膨胀 | Spring 主构造器和兼容构造器参数继续增加，后续 P3-3/P3-4 再加协作者时容易漏传或测试 helper 不一致 | 本轮不重构，Task 4 commit 必须在 commit body 写入 NOTE：后续用 `SuggestionAssemblerRegistry` 收口 Extractor / Analyzer / Writer 及后续 Citation assembler |
| Writer 输出字段追加覆盖既有字段 | `sourceUrls / issueFlags / sectionEvidenceBundles` 已被报告页、审计页和回放消费，覆盖会造成 P2/P3-1 回归 | Task 2 只追加 `writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps`，`issueFlags` 只能合并，`sectionEvidenceBundles` 保持原输出 |
| 章节级来源与全局来源语义混淆 | 某章节没有自己的 `sourceUrls`，但 Writer/Analyzer 有全局来源时，误标 `MISSING_SOURCE` 会过度阻断 | Task 1 增加测试锁定：章节缺来源但全局有来源时是 `PARTIAL_SOURCE` 的引用缺口，不是完全无来源 |
| replay 测试 helper 不匹配 | `TaskReplayProjectionServiceTest` 可能没有 `project(taskId, events)` 直连 helper | Task 5 先检查现有测试组装方式；若没有 helper，沿用 `getTaskReplay(...)` 仓储 mock 方式，不新增生产代码 |

## File Structure

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/WriterCitationGap.java`
  - Writer 章节引用缺口契约，只表达事实，不表达最终编排动作。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java`
  - 读取 Writer 最终报告、`SectionEvidenceBundle` 和来源列表，归一化章节引用缺口。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssembler.java`
  - 把 Writer 输出转换成 `AgentSuggestion`。
- `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssemblerTest.java`

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
  - 注入 `WriterCitationGapInspector`，输出 Writer 缺口元数据。
- `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`
  - 覆盖 Writer 输出 `sectionCitationGaps / writerEvidenceState / citationGapSeverity`。
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
  - 支持 Writer suggestion 决策。
- `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
  - 覆盖 Writer 无来源阻断与带来源重写轨迹。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
  - 接入 `WriterSuggestionAssembler`，把 Writer 纳入受控 suggestion gate。
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
  - 覆盖 Writer 无来源缺口阻断 Reviewer。
- `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
  - 补 P3-2 直连和 DagExecutor smoke。
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
  - 如现有 replay 测试未覆盖 Writer trigger，则新增 Writer 决策事件投影断言。
- `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
  - 回写 P3-2 执行 1 状态。
- `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
  - 回写 P3-2 执行 1 自动化实现记录。
- `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
  - 更新稳定演示版中 `sourceUrls / evidenceState` 在报告侧可见的状态。
- `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-2-writer-citation-gap-execution-1-plan.md`
  - 执行完成后追加进度和验证结果。

### Reference Only

- `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/SectionEvidenceBundle.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AgentSuggestion.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/AnalyzerSuggestionAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/ExtractorSuggestionAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`

---

## Task 1: 冻结 Writer 章节引用缺口契约与检测器

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/WriterCitationGap.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java`

- [ ] **Step 1: 写 Writer 引用缺口检测红灯测试**

Create `WriterCitationGapInspectorTest`:

```java
package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriterCitationGapInspectorTest {

    private final WriterCitationGapInspector inspector = new WriterCitationGapInspector();

    @Test
    void shouldExposeMissingSourceSectionGapFromWriterBundles() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("CONCLUSION")
                .sectionKey("report_conclusion")
                .sectionTitle("报告结论")
                .missingFields(List.of("recommendations"))
                .sourceUrls(List.of())
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 建议\n建议推进连接器生态。",
                List.of(bundle),
                List.of());

        assertThat(result.severity()).isEqualTo("ERROR");
        assertThat(result.evidenceState()).isEqualTo("MISSING_SOURCE");
        assertThat(result.missingCitationSections()).containsExactly("report_conclusion");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP", "WRITER_MISSING_SOURCE");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).getTargetSection()).isEqualTo("report_conclusion");
        assertThat(result.gaps().get(0).getEvidenceState()).isEqualTo("MISSING_SOURCE");
    }

    @Test
    void shouldExposeSourceBackedCitationGapWithoutPretendingCitationAgent() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("pricing")
                .sectionTitle("定价策略")
                .missingFields(List.of("pricingComparison"))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 定价策略\n定价信息需要补充逐句引用。",
                List.of(bundle),
                List.of("https://www.notion.so/pricing"));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.gaps().get(0).getSourceUrls()).containsExactly("https://www.notion.so/pricing");
        assertThat(result.gaps().get(0).getSuggestedQueries()).contains("pricing official citation evidence");
    }

    @Test
    void shouldTreatSectionWithoutOwnUrlsAsPartialSourceWhenWriterHasGlobalSources() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("recommendations")
                .sectionTitle("行动建议")
                .missingFields(List.of("recommendations"))
                .sourceUrls(List.of())
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 行动建议\n建议需要补充逐句引用。",
                List.of(bundle),
                List.of("https://www.notion.so/product/ai"));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.gaps().get(0).getEvidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.gaps().get(0).getSourceUrls()).containsExactly("https://www.notion.so/product/ai");
    }

    @Test
    void shouldReturnNoGapWhenSectionBundlesHaveSourcesAndNoMissingFields() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("features")
                .sectionTitle("产品功能")
                .fieldNames(List.of("featureComparison"))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 产品功能\nNotion AI 提供工作区 AI 能力 [证据：E001]。",
                List.of(bundle),
                List.of("https://www.notion.so/product/ai"));

        assertThat(result.severity()).isEqualTo("NONE");
        assertThat(result.evidenceState()).isEqualTo("FULL_SOURCE");
        assertThat(result.gaps()).isEmpty();
        assertThat(result.missingCitationSections()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=WriterCitationGapInspectorTest" test
```

Expected: FAIL，原因是 `WriterCitationGapInspector` 和 `WriterCitationGap` 尚未创建。

- [ ] **Step 3: 创建 `WriterCitationGap` 契约**

Create `WriterCitationGap.java`:

```java
package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Writer 阶段发现的章节引用缺口。
 * 该对象只描述“哪一章缺引用、缺什么、当前有哪些来源”，不能直接表达补采或重写动作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WriterCitationGap {

    /** 稳定章节键，例如 report_conclusion / pricing / recommendations。 */
    private String targetSection;

    /** 给用户和回放展示的章节标题。 */
    private String sectionTitle;

    /** 缺口摘要，必须说明为什么该章节不能被视为完整引用。 */
    private String summary;

    /** 缺口严重程度：NONE / HIGH / ERROR。 */
    private String severity;

    /** 当前章节已有来源；为空时必须配合 evidenceState=MISSING_SOURCE。 */
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 证据状态：FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE。 */
    private String evidenceState;

    /** 当前章节仍缺少引用支撑的字段。 */
    @Builder.Default
    private List<String> missingFields = List.of();

    /** 后续补证可使用的检索提示，不等同执行动作。 */
    @Builder.Default
    private List<String> suggestedQueries = List.of();

    /**
     * 归一化章节引用缺口，保证 sourceUrls 去重、缺失字段去重，并显式补齐 evidenceState。
     */
    public WriterCitationGap normalized() {
        List<String> normalizedSourceUrls = normalize(sourceUrls);
        List<String> normalizedMissingFields = normalize(missingFields);
        String resolvedEvidenceState = evidenceState == null || evidenceState.isBlank()
                ? (normalizedSourceUrls.isEmpty() ? "MISSING_SOURCE" : "PARTIAL_SOURCE")
                : evidenceState.trim().toUpperCase();
        String resolvedSeverity = severity == null || severity.isBlank()
                ? (normalizedSourceUrls.isEmpty() ? "ERROR" : "HIGH")
                : severity.trim().toUpperCase();
        return toBuilder()
                .targetSection(blankToDefault(targetSection, "report"))
                .sectionTitle(blankToDefault(sectionTitle, targetSection))
                .summary(blankToDefault(summary, "Writer 发现章节引用缺口，需要补充来源或重写引用。"))
                .severity(resolvedSeverity)
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(resolvedEvidenceState)
                .missingFields(normalizedMissingFields)
                .suggestedQueries(normalize(suggestedQueries))
                .build();
    }

    private List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String blankToDefault(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null || fallback.isBlank() ? "report" : fallback.trim();
    }
}
```

- [ ] **Step 4: 创建 `WriterCitationGapInspector`**

Create `WriterCitationGapInspector.java`:

```java
package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import cn.bugstack.competitoragent.workflow.contract.WriterCitationGap;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Writer 章节引用缺口检测器。
 * 它只基于 Writer 可见的章节证据束和来源列表生成缺口事实，不做 Citation Agent 级别的逐句真伪核验。
 */
@Component
public class WriterCitationGapInspector {

    public InspectionResult inspect(String reportContent,
                                    List<SectionEvidenceBundle> sectionEvidenceBundles,
                                    List<String> fallbackSourceUrls) {
        List<WriterCitationGap> gaps = new ArrayList<>();
        for (SectionEvidenceBundle bundle : sectionEvidenceBundles == null
                ? List.<SectionEvidenceBundle>of()
                : sectionEvidenceBundles) {
            SectionEvidenceBundle normalized = bundle == null ? null : bundle.normalized();
            if (normalized == null || !hasCitationGap(normalized)) {
                continue;
            }
            gaps.add(buildGap(normalized, fallbackSourceUrls));
        }
        List<String> issueFlags = buildIssueFlags(gaps);
        return new InspectionResult(
                gaps,
                resolveSeverity(gaps),
                resolveEvidenceState(gaps, fallbackSourceUrls),
                gaps.stream().map(WriterCitationGap::getTargetSection).toList(),
                issueFlags);
    }

    private boolean hasCitationGap(SectionEvidenceBundle bundle) {
        return contains(bundle.getIssueFlags(), "SECTION_EVIDENCE_GAP")
                || contains(bundle.getIssueFlags(), "NO_USABLE_EVIDENCE")
                || (bundle.getMissingFields() != null && !bundle.getMissingFields().isEmpty())
                || bundle.getSourceUrls() == null
                || bundle.getSourceUrls().isEmpty();
    }

    private WriterCitationGap buildGap(SectionEvidenceBundle bundle, List<String> fallbackSourceUrls) {
        List<String> sectionSources = normalize(bundle.getSourceUrls());
        if (sectionSources.isEmpty()) {
            // 章节本身没有来源但 Writer 全局仍有来源时，属于引用缺口而不是完全无来源。
            // 因此这里回填全局来源并标记 PARTIAL_SOURCE，避免过度阻断可修复的写作问题。
            sectionSources = normalize(fallbackSourceUrls);
        }
        String sectionKey = firstNonBlank(bundle.getSectionKey(), "report");
        return WriterCitationGap.builder()
                .targetSection(sectionKey)
                .sectionTitle(firstNonBlank(bundle.getSectionTitle(), sectionKey))
                .summary(firstNonBlank(bundle.getGapSummary(),
                        "Writer 章节缺少可回指引用：" + firstNonBlank(bundle.getSectionTitle(), sectionKey)))
                .severity(sectionSources.isEmpty() ? "ERROR" : "HIGH")
                .sourceUrls(sectionSources)
                .evidenceState(sectionSources.isEmpty() ? "MISSING_SOURCE" : "PARTIAL_SOURCE")
                .missingFields(normalize(bundle.getMissingFields()))
                .suggestedQueries(buildSuggestedQueries(sectionKey, bundle.getMissingFields()))
                .build()
                .normalized();
    }

    private List<String> buildSuggestedQueries(String sectionKey, List<String> missingFields) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(sectionKey + " official citation evidence");
        for (String field : missingFields == null ? List.<String>of() : missingFields) {
            if (field != null && !field.isBlank()) {
                queries.add(sectionKey + " " + field.trim() + " official source");
            }
        }
        return new ArrayList<>(queries);
    }

    private String resolveSeverity(List<WriterCitationGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return "NONE";
        }
        return gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))
                ? "ERROR"
                : "HIGH";
    }

    private String resolveEvidenceState(List<WriterCitationGap> gaps, List<String> fallbackSourceUrls) {
        if (gaps == null || gaps.isEmpty()) {
            return normalize(fallbackSourceUrls).isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE";
        }
        return gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))
                ? "MISSING_SOURCE"
                : "PARTIAL_SOURCE";
    }

    private List<String> buildIssueFlags(List<WriterCitationGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.add("WRITER_CITATION_GAP");
        if (gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))) {
            flags.add("WRITER_MISSING_SOURCE");
        }
        return new ArrayList<>(flags);
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || expected == null) {
            return false;
        }
        return values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
    }

    private List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
    }

    public record InspectionResult(List<WriterCitationGap> gaps,
                                   String severity,
                                   String evidenceState,
                                   List<String> missingCitationSections,
                                   List<String> issueFlags) {
    }
}
```

- [ ] **Step 5: 运行检测器测试**

Run:

```bash
mvn -pl backend "-Dtest=WriterCitationGapInspectorTest" test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/WriterCitationGap.java backend/src/main/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspector.java backend/src/test/java/cn/bugstack/competitoragent/agent/writer/WriterCitationGapInspectorTest.java
git commit -m "feat: add writer citation gap inspector"
```

---

## Task 2: ReportWriterAgent 输出引用缺口元数据

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java`

- [ ] **Step 1: 写 Writer 输出契约红灯测试**

在 `ReportWriterAgentTest` 新增测试：

```java
@Test
void shouldExposeWriterCitationGapMetadataWhenReportConclusionHasNoSources() throws Exception {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of());
    when(reportRepository.findByTaskId(1L)).thenReturn(Optional.empty());
    when(promptService.render(eq("writer"), any())).thenReturn("writer-prompt");
    when(llmClient.chat(any(), any())).thenReturn("""
            # 竞品报告
            ## 建议
            建议推进连接器生态。
            """);
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

    assertEquals("SUCCESS", result.getStatus().name());
    assertEquals("ERROR", output.path("citationGapSeverity").asText());
    assertEquals("MISSING_SOURCE", output.path("writerEvidenceState").asText());
    assertTrue(output.path("missingCitationSections").toString().contains("report_conclusion"));
    assertTrue(output.path("sectionCitationGaps").isArray());
    assertEquals("report_conclusion", output.path("sectionCitationGaps").get(0).path("targetSection").asText());
    assertTrue(output.path("issueFlags").toString().contains("WRITER_CITATION_GAP"));
    assertTrue(output.path("issueFlags").toString().contains("WRITER_MISSING_SOURCE"));
}
```

并把测试类里的 Agent 初始化补上 inspector：

```java
private final WriterCitationGapInspector writerCitationGapInspector = new WriterCitationGapInspector();
private final ReportWriterAgent agent = new ReportWriterAgent(
        logRepository,
        reportRepository,
        evidenceRepository,
        llmClient,
        promptService,
        agentContextAssembler,
        memoryWritebackService,
        objectMapper,
        writerCitationGapInspector
);
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=ReportWriterAgentTest#shouldExposeWriterCitationGapMetadataWhenReportConclusionHasNoSources" test
```

Expected: FAIL，原因是 `ReportWriterAgent` 尚未输出 `citationGapSeverity / writerEvidenceState / sectionCitationGaps`。

- [ ] **Step 3: 注入 `WriterCitationGapInspector`**

在 `ReportWriterAgent` 字段和构造器中新增：

```java
private final WriterCitationGapInspector writerCitationGapInspector;
```

构造器末尾新增参数并赋值：

```java
                             ObjectMapper objectMapper,
                             WriterCitationGapInspector writerCitationGapInspector) {
    super(logRepository, agentContextAssembler);
    this.reportRepository = reportRepository;
    this.evidenceRepository = evidenceRepository;
    this.llmClient = llmClient;
    this.promptService = promptService;
    this.memoryWritebackService = memoryWritebackService;
    this.objectMapper = objectMapper;
    this.writerCitationGapInspector = writerCitationGapInspector;
}
```

- [ ] **Step 4: 在 Writer 输出中写入缺口元数据**

在 `reportContent = enforceEvidenceTraceabilityForKeySections(reportContent);` 之后新增：

```java
WriterCitationGapInspector.InspectionResult citationInspection = writerCitationGapInspector.inspect(
        reportContent,
        normalizedAnalysis.sectionEvidenceBundles(),
        normalizedAnalysis.sourceUrls());
List<String> outputIssueFlags = mergeIssueFlags(normalizedAnalysis.issueFlags(), citationInspection.issueFlags());
```

注意：`NormalizedAnalysisPayload` 当前已经提供 `sectionEvidenceBundles()` 和 `issueFlags()` record accessor，执行时直接复用即可。Writer 输出里已有 `sourceUrls / issueFlags / sectionEvidenceBundles`，本步骤只能合并 `issueFlags` 并追加新字段，不能覆盖 `sectionEvidenceBundles` 或删掉现有 `sourceUrls`。

把原输出中的 `issueFlags` 替换为合并后的 `outputIssueFlags`，并新增字段：

```java
output.put("sourceUrls", normalizedAnalysis.sourceUrls());
output.put("writerEvidenceState", citationInspection.evidenceState());
output.put("citationGapSeverity", citationInspection.severity());
output.put("missingCitationSections", citationInspection.missingCitationSections());
output.put("sectionCitationGaps", citationInspection.gaps());
output.put("issueFlags", outputIssueFlags);
output.put("evidenceFragments", normalizedAnalysis.evidenceFragments());
output.put("sectionEvidenceBundles", normalizedAnalysis.sectionEvidenceBundles());
```

在类中新增 helper：

```java
/**
 * Writer 输出的问题标记需要同时保留 Analyzer 上游缺口和本阶段引用缺口，
 * 这样 Orchestrator 能判断缺口来自分析还是写作阶段。
 */
private List<String> mergeIssueFlags(List<String> upstreamIssueFlags, List<String> writerIssueFlags) {
    LinkedHashSet<String> merged = new LinkedHashSet<>();
    if (upstreamIssueFlags != null) {
        merged.addAll(upstreamIssueFlags);
    }
    if (writerIssueFlags != null) {
        merged.addAll(writerIssueFlags);
    }
    return new ArrayList<>(merged);
}
```

- [ ] **Step 5: 运行 Writer 测试**

Run:

```bash
mvn -pl backend "-Dtest=ReportWriterAgentTest,WriterCitationGapInspectorTest" test
```

Expected: PASS。现有 Writer RAG、日期清洗、修订模式、写回记忆测试不回归。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/writer/ReportWriterAgentTest.java
git commit -m "feat: expose writer citation gap metadata"
```

---

## Task 3: 新增 WriterSuggestionAssembler

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssembler.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssemblerTest.java`

- [ ] **Step 1: 写 Writer suggestion 转换红灯测试**

Create `WriterSuggestionAssemblerTest`:

```java
package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriterSuggestionAssemblerTest {

    private final WriterSuggestionAssembler assembler = new WriterSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldBuildCitationGapSuggestionFromWriterOutput() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "write_report", """
                {
                  "citationGapSeverity": "ERROR",
                  "writerEvidenceState": "MISSING_SOURCE",
                  "missingCitationSections": ["report_conclusion"],
                  "sectionCitationGaps": [
                    {
                      "targetSection": "report_conclusion",
                      "sectionTitle": "报告结论",
                      "summary": "当前章节暂无可用证据来源",
                      "severity": "ERROR",
                      "sourceUrls": [],
                      "evidenceState": "MISSING_SOURCE",
                      "missingFields": ["recommendations"],
                      "suggestedQueries": ["report_conclusion recommendations official source"]
                    }
                  ]
                }
                """);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionId()).isEqualTo("as-task-77-write_report-1");
        assertThat(suggestion.getProducerAgentType()).isEqualTo("WRITER");
        assertThat(suggestion.getSuggestionType()).isEqualTo("CITATION_GAP");
        assertThat(suggestion.getTargetSection()).isEqualTo("report_conclusion");
        assertThat(suggestion.getSeverity()).isEqualTo("ERROR");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }

    @Test
    void shouldReturnEmptyWhenWriterHasNoCitationGap() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "write_report", """
                {
                  "citationGapSeverity": "NONE",
                  "writerEvidenceState": "FULL_SOURCE",
                  "sectionCitationGaps": []
                }
                """);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void shouldPreferRewriteTargetWhenGapHasSources() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "rewrite_report", """
                {
                  "citationGapSeverity": "HIGH",
                  "writerEvidenceState": "PARTIAL_SOURCE",
                  "sectionCitationGaps": [
                    {
                      "targetSection": "pricing",
                      "sectionTitle": "定价策略",
                      "summary": "定价章节已有来源但缺逐句引用",
                      "severity": "HIGH",
                      "sourceUrls": ["https://www.notion.so/pricing"],
                      "evidenceState": "PARTIAL_SOURCE",
                      "missingFields": ["pricingComparison"]
                    }
                  ]
                }
                """);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getSuggestedTargetNode()).isEqualTo("rewrite_report");
        assertThat(suggestions.get(0).getSourceUrls()).containsExactly("https://www.notion.so/pricing");
    }
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=WriterSuggestionAssemblerTest" test
```

Expected: FAIL，原因是 `WriterSuggestionAssembler` 尚未创建。

- [ ] **Step 3: 创建 `WriterSuggestionAssembler`**

Create `WriterSuggestionAssembler.java`:

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
 * Writer 输出到 AgentSuggestion 的转换器。
 * 它只识别章节引用缺口事实，不直接决定补证、重写或人工介入动作。
 */
@Slf4j
@Component
public class WriterSuggestionAssembler {

    private final ObjectMapper objectMapper;

    public WriterSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Writer 输出中提取引用缺口建议。
     * 解析失败时返回空建议，避免脏报告输出触发不可解释的编排。
     */
    public List<AgentSuggestion> fromWriterOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        String severity = upper(output.path("citationGapSeverity").asText("NONE"));
        if ("NONE".equals(severity)) {
            return List.of();
        }
        JsonNode gaps = output.path("sectionCitationGaps");
        if (!gaps.isArray() || gaps.isEmpty()) {
            return List.of();
        }
        List<AgentSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (JsonNode gap : gaps) {
            suggestions.add(buildSuggestion(taskId, producerNodeName, index++, gap, severity));
        }
        return suggestions;
    }

    private AgentSuggestion buildSuggestion(Long taskId,
                                            String producerNodeName,
                                            int index,
                                            JsonNode gap,
                                            String fallbackSeverity) {
        List<String> sourceUrls = readStringList(gap.path("sourceUrls"));
        EvidenceState evidenceState = resolveEvidenceState(gap.path("evidenceState").asText(null), sourceUrls);
        return AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-" + index)
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("WRITER")
                .suggestionType("CITATION_GAP")
                .targetSection(gap.path("targetSection").asText("report"))
                .summary(gap.path("summary").asText("Writer 发现章节引用缺口，需要 Orchestrator 判断下一步。"))
                .severity(resolveSeverity(gap.path("severity").asText(fallbackSeverity)))
                .confidence(resolveConfidence(evidenceState))
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .suggestedQueries(resolveSuggestedQueries(gap, sourceUrls))
                .suggestedTargetNode(sourceUrls.isEmpty() ? "collect_sources" : "rewrite_report")
                .build()
                .normalized();
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
            log.warn("failed to parse writer output for agent suggestion", e);
            return null;
        }
    }

    private List<String> resolveSuggestedQueries(JsonNode gap, List<String> sourceUrls) {
        List<String> queries = readStringList(gap.path("suggestedQueries"));
        if (!queries.isEmpty()) {
            return queries;
        }
        String targetSection = gap.path("targetSection").asText("report");
        return sourceUrls.isEmpty()
                ? List.of(targetSection + " official citation evidence")
                : List.of(targetSection + " rewrite with evidence citations");
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

    private String resolveSeverity(String severity) {
        String normalized = upper(severity);
        if ("ERROR".equals(normalized) || "HIGH".equals(normalized)) {
            return normalized;
        }
        return "MEDIUM";
    }

    private Double resolveConfidence(EvidenceState evidenceState) {
        return evidenceState == EvidenceState.MISSING_SOURCE ? 0.25d : 0.70d;
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

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: 运行 Writer suggestion 测试**

Run:

```bash
mvn -pl backend "-Dtest=WriterSuggestionAssemblerTest" test
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssembler.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/WriterSuggestionAssemblerTest.java
git commit -m "feat: convert writer citation gaps to suggestions"
```

---

## Task 4: Orchestrator 与 DagExecutor 接入 Writer suggestion gate

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 写 Orchestrator Writer 决策红灯测试**

在 `OrchestrationDecisionServiceTest` 新增：

```java
@Test
void shouldWaitForHumanFromWriterCitationGapWithoutSources() {
    AgentSuggestion suggestion = AgentSuggestion.builder()
            .suggestionId("as-task-77-write_report-1")
            .taskId(77L)
            .producerNodeName("write_report")
            .producerAgentType("WRITER")
            .suggestionType("CITATION_GAP")
            .targetSection("report_conclusion")
            .summary("报告结论缺少可用来源")
            .severity("ERROR")
            .confidence(0.25d)
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .suggestedQueries(List.of("report_conclusion official citation evidence"))
            .suggestedTargetNode("collect_sources")
            .build()
            .normalized();

    List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
            .taskId(77L)
            .triggerNodeName("write_report")
            .agentSuggestions(List.of(suggestion))
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build());

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
    assertThat(decisions.get(0).getInputRefs())
            .containsEntry("agentSuggestionIds", List.of("as-task-77-write_report-1"));
}

@Test
void shouldRecordRewriteDecisionFromWriterCitationGapWithSources() {
    AgentSuggestion suggestion = AgentSuggestion.builder()
            .suggestionId("as-task-77-write_report-1")
            .taskId(77L)
            .producerNodeName("write_report")
            .producerAgentType("WRITER")
            .suggestionType("CITATION_GAP")
            .targetSection("pricing")
            .summary("定价章节已有来源但缺逐句引用")
            .severity("HIGH")
            .confidence(0.70d)
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .evidenceState(EvidenceState.PARTIAL_SOURCE)
            .suggestedTargetNode("rewrite_report")
            .build()
            .normalized();

    List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
            .taskId(77L)
            .triggerNodeName("write_report")
            .agentSuggestions(List.of(suggestion))
            .sourceUrls(List.of("https://www.notion.so/pricing"))
            .evidenceState(EvidenceState.PARTIAL_SOURCE)
            .build());

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("REWRITE_ONLY");
    assertThat(decisions.get(0).getActionType()).isEqualTo("REWRITE_SECTION");
    assertThat(decisions.get(0).getTargetNode()).isEqualTo("rewrite_report");
    assertThat(decisions.get(0).getTargetSection()).isEqualTo("pricing");
}
```

- [ ] **Step 2: 运行 Orchestrator 红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest#shouldWaitForHumanFromWriterCitationGapWithoutSources,OrchestrationDecisionServiceTest#shouldRecordRewriteDecisionFromWriterCitationGapWithSources" test
```

Expected: FAIL，原因是 `OrchestrationDecisionService` 尚未处理 Writer trigger。

- [ ] **Step 3: 实现 Writer 决策分支**

在 `decide(...)` 中追加 Writer trigger：

```java
if (isWriterTrigger(context.getTriggerNodeName())) {
    return decideWriterSuggestions(context);
}
```

新增 helper：

```java
private boolean isWriterTrigger(String triggerNodeName) {
    return "write_report".equals(triggerNodeName) || "rewrite_report".equals(triggerNodeName);
}

/**
 * Writer 只提交章节引用缺口事实，编排层根据来源状态决定阻断还是留痕。
 * 无来源缺口必须阻断，已有来源但引用不完整时先记录重写决策，继续交给 Reviewer 做质量收口。
 * 决策顺序必须保持“无来源优先阻断 -> 有来源留痕”，与 Analyzer 缺口策略保持一致。
 */
private List<OrchestrationDecision> decideWriterSuggestions(OrchestrationContext context) {
    List<AgentSuggestion> suggestions = context.getAgentSuggestions();
    if (suggestions == null || suggestions.isEmpty()) {
        return List.of(noAction(context, context.getTriggerNodeName() + " 未产生 AgentSuggestion，无需编排动作。"));
    }
    for (AgentSuggestion suggestion : suggestions) {
        if ("CITATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                && (suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())) {
            return List.of(waitForHuman(context, "Writer 发现章节引用缺口但缺少 sourceUrls，禁止继续质检。"));
        }
    }
    for (AgentSuggestion suggestion : suggestions) {
        if ("CITATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
            return List.of(rewriteSectionFromSuggestion(context, suggestion));
        }
    }
    return List.of(noAction(context, "Writer 建议未命中 P3-2 可执行策略。"));
}

private OrchestrationDecision rewriteSectionFromSuggestion(OrchestrationContext context,
                                                           AgentSuggestion suggestion) {
    return OrchestrationDecision.builder()
            .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-writer-suggestion-1")
            .taskId(context.getTaskId())
            .triggerNodeName(context.getTriggerNodeName())
            .decisionType("REWRITE_ONLY")
            .actionType("REWRITE_SECTION")
            .targetNode("rewrite_report")
            .affectedScope("CURRENT_NODE_ONLY")
            .priority(suggestion.getSeverity())
            .targetSection(suggestion.getTargetSection())
            .reason(suggestion.getSummary())
            .requiresHumanIntervention(false)
            .requiresConfirmation(false)
            .confidence(suggestion.getConfidence())
            .suggestedQueries(suggestion.getSuggestedQueries())
            .inputRefs(buildInputRefs(context))
            .sourceUrls(suggestion.getSourceUrls())
            .evidenceState(suggestion.getEvidenceState())
            .build()
            .normalized();
}
```

- [ ] **Step 4: 运行 Orchestrator 测试**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest" test
```

Expected: PASS，Extractor 和 Analyzer suggestion 决策不回归。

- [ ] **Step 5: 写 DagExecutor Writer gate 红灯测试**

在 `DagExecutorTest` 新增：

```java
@Test
void shouldHoldWriterWhenSuccessfulOutputContainsMissingSourceCitationGap() {
    Long taskId = 1012L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();
    TaskNode analyzer = TaskNode.builder()
            .id(1201L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(0)
            .build();
    TaskNode writer = TaskNode.builder()
            .id(1202L)
            .taskId(taskId)
            .nodeName("write_report")
            .agentType(AgentType.WRITER)
            .dependsOn("[\"analyze_competitors\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();
    TaskNode reviewer = TaskNode.builder()
            .id(1203L)
            .taskId(taskId)
            .nodeName("quality_check")
            .agentType(AgentType.REVIEWER)
            .dependsOn("[\"write_report\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(2)
            .build();
    List<TaskNode> nodes = List.of(analyzer, writer, reviewer);

    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
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
            List.of(new AlwaysSuccessAnalyzerAgent(), new WriterCitationGapAgent(), new AlwaysSuccessReviewerAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService(),
            List.of(),
            orchestrationTraceService
    );

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("writer-citation-gap-test").build());

    assertEquals(TaskNodeStatus.WAITING_INTERVENTION, writer.getStatus());
    assertEquals(TaskNodeStatus.PENDING, reviewer.getStatus());
    assertTrue(writer.getInterventionReason().contains("Writer"));
    verify(orchestrationTraceService, atLeastOnce())
            .recordDecision(eq(taskId), eq(writer), argThat(decision ->
                            "WAIT_FOR_HUMAN".equals(decision.getDecisionType())
                                    && "write_report".equals(decision.getTriggerNodeName())
                                    && decision.getInputRefs().containsKey("agentSuggestionIds")),
                    isNull(), isNull());
}
```

测试支撑 Agent：

```java
private static final class WriterCitationGapAgent implements Agent {
    @Override
    public AgentType getType() {
        return AgentType.WRITER;
    }

    @Override
    public String getName() {
        return "writer-citation-gap";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "content": "# 竞品报告",
                          "writerEvidenceState": "MISSING_SOURCE",
                          "citationGapSeverity": "ERROR",
                          "sourceUrls": [],
                          "sectionCitationGaps": [
                            {
                              "targetSection": "report_conclusion",
                              "sectionTitle": "报告结论",
                              "summary": "报告结论缺少可用来源",
                              "severity": "ERROR",
                              "sourceUrls": [],
                              "evidenceState": "MISSING_SOURCE",
                              "missingFields": ["recommendations"]
                            }
                          ]
                        }
                        """)
                .build();
    }
}
```

- [ ] **Step 6: 运行 DagExecutor 红灯测试**

Run:

```bash
mvn -pl backend "-Dtest=DagExecutorTest#shouldHoldWriterWhenSuccessfulOutputContainsMissingSourceCitationGap" test
```

Expected: FAIL，原因是 `DagExecutor` 尚未接入 `WriterSuggestionAssembler`。

- [ ] **Step 7: 接入 `WriterSuggestionAssembler`**

在 `DagExecutor` 中新增字段：

```java
private final WriterSuggestionAssembler writerSuggestionAssembler;
```

主构造器新增参数并赋值：

```java
                       ExtractorSuggestionAssembler extractorSuggestionAssembler,
                       AnalyzerSuggestionAssembler analyzerSuggestionAssembler,
                       WriterSuggestionAssembler writerSuggestionAssembler,
                       OrchestrationDecisionService orchestrationDecisionService,
                       OrchestrationTraceService orchestrationTraceService,
                       List<DagExecutionObserver> observers) {
    ...
    this.extractorSuggestionAssembler = extractorSuggestionAssembler;
    this.analyzerSuggestionAssembler = analyzerSuggestionAssembler;
    this.writerSuggestionAssembler = writerSuggestionAssembler;
    this.orchestrationDecisionService = orchestrationDecisionService;
    this.orchestrationTraceService = orchestrationTraceService;
    ...
}
```

所有兼容构造器中传入：

```java
new WriterSuggestionAssembler(objectMapper)
```

执行注意：`DagExecutor` 当前已有 Spring 主构造器和多层兼容构造器，本轮为了控制范围继续沿用委托链，不在 P3-2 执行 1 中重构构造器。修改时必须逐个更新测试 helper，避免某些测试路径因为走旧构造器而绕过 Writer gate。

更新 `buildAgentSuggestions(...)`：

```java
/**
 * AgentSuggestion 是运行期协作决策的统一入口。
 * 当前只允许 Extractor、Analyzer 和 Writer 进入该 gate，避免误拦截 Reviewer 或动态恢复节点。
 */
private List<AgentSuggestion> buildAgentSuggestions(Long taskId, TaskNode completedNode) {
    if (completedNode == null || completedNode.getNodeName() == null) {
        return List.of();
    }
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
    if ("write_report".equals(completedNode.getNodeName()) || "rewrite_report".equals(completedNode.getNodeName())) {
        return writerSuggestionAssembler.fromWriterOutput(
                taskId,
                completedNode.getNodeName(),
                completedNode.getOutputData());
    }
    return List.of();
}
```

更新所有测试 helper 中的 `new DagExecutor(...)` 调用，在 `AnalyzerSuggestionAssembler` 后加入：

```java
new WriterSuggestionAssembler(objectMapper),
```

- [ ] **Step 8: 运行 DagExecutor 与编排测试**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationDecisionServiceTest,DagExecutorTest,WriterSuggestionAssemblerTest" test
```

Expected: PASS。

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
git commit -m "feat: route writer citation gaps through orchestrator" -m "NOTE: DagExecutor constructor chain now wires Extractor/Analyzer/Writer suggestion assemblers; P3-3/P3-4 should introduce SuggestionAssemblerRegistry before adding more assembler parameters."
```

---

## Task 5: replay / smoke / 文档回链

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md`
- Modify: `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md`
- Modify: `docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-2-writer-citation-gap-execution-1-plan.md`

- [ ] **Step 1: 补 Writer 直连 smoke**

在 `CollaborationPlanningSmokeTest` 新增：

```java
@Test
void shouldRouteWriterCitationGapSuggestionToOrchestratorDecision() {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    WriterSuggestionAssembler assembler = new WriterSuggestionAssembler(objectMapper);
    OrchestrationDecisionService orchestrationDecisionService =
            new OrchestrationDecisionService(new OrchestrationDecisionAdapter());

    List<AgentSuggestion> suggestions = assembler.fromWriterOutput(99L, "write_report", """
            {
              "citationGapSeverity": "ERROR",
              "writerEvidenceState": "MISSING_SOURCE",
              "sectionCitationGaps": [
                {
                  "targetSection": "report_conclusion",
                  "summary": "报告结论缺少可用来源",
                  "severity": "ERROR",
                  "sourceUrls": [],
                  "evidenceState": "MISSING_SOURCE"
                }
              ]
            }
            """);

    List<OrchestrationDecision> decisions = orchestrationDecisionService.decide(OrchestrationContext.builder()
            .taskId(99L)
            .triggerNodeName("write_report")
            .agentSuggestions(suggestions)
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build());

    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    assertThat(decisions.get(0).getInputRefs())
            .containsEntry("agentSuggestionIds", List.of("as-task-99-write_report-1"));
}
```

- [ ] **Step 2: 补 DagExecutor smoke**

在 `CollaborationPlanningSmokeTest` 复用 P3-1 的 `newDagExecutorForSmoke(...)`，在构造器参数中加入 `new WriterSuggestionAssembler(objectMapper)`，并新增 writer 缺口链路测试：

```java
@Test
void shouldRouteWriterCitationGapThroughDagExecutorGateBeforeReviewer() {
    Long taskId = 1199L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();
    TaskNode analyzer = TaskNode.builder()
            .id(11991L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(0)
            .build();
    TaskNode writer = TaskNode.builder()
            .id(11992L)
            .taskId(taskId)
            .nodeName("write_report")
            .agentType(AgentType.WRITER)
            .dependsOn("[\"analyze_competitors\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();
    TaskNode reviewer = TaskNode.builder()
            .id(11993L)
            .taskId(taskId)
            .nodeName("quality_check")
            .agentType(AgentType.REVIEWER)
            .dependsOn("[\"write_report\"]")
            .required(true)
            .retryable(false)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(2)
            .build();

    DagExecutor executor = newDagExecutorForSmoke(
            task,
            List.of(analyzer, writer, reviewer),
            List.of(new SmokeAnalyzerReadyAgent(), new SmokeWriterMissingSourceCitationAgent(), new SmokeReviewerAgent()));

    executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("p3-2-smoke").build());

    assertThat(writer.getStatus()).isEqualTo(TaskNodeStatus.WAITING_INTERVENTION);
    assertThat(reviewer.getStatus()).isEqualTo(TaskNodeStatus.PENDING);
    assertThat(writer.getInterventionReason()).contains("Writer");
}
```

- [ ] **Step 3: 补 replay Writer 决策投影断言**

如果 `TaskReplayProjectionServiceTest` 已经用通用 `ORCHESTRATION_DECISION_RECORDED` 覆盖任意节点，只追加 Writer trigger 断言；否则新增：

```java
@Test
void shouldProjectWriterOrchestrationDecisionTrace() {
    TaskWorkflowEvent event = TaskWorkflowEvent.builder()
            .taskId(99L)
            .nodeName("write_report")
            .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
            .eventPayload("""
                    {
                      "decisionId": "od-99-write_report-human",
                      "triggerNodeName": "write_report",
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
                assertThat(item.getNodeName()).isEqualTo("write_report");
                assertThat(item.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
            });
}
```

执行时如果当前测试类没有 `project(...)` helper，使用已有 `getTaskReplay(...)` 组装方式，不新增生产代码。

- [ ] **Step 4: 运行 P3-2 局部聚合验证**

Run:

```bash
mvn -pl backend "-Dtest=ReportWriterAgentTest,WriterCitationGapInspectorTest,WriterSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest,CollaborationPlanningSmokeTest,TaskReplayProjectionServiceTest" test
```

Expected: PASS。该命令覆盖 Writer 输出契约、Writer suggestion、Orchestrator 决策、DagExecutor gate、replay/smoke。

- [ ] **Step 5: 运行 P1+P2+P3-1+P3-2 编排聚合**

Run:

```bash
mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest" test
```

Expected: PASS。该命令证明 P1 终审回流、P2 extractor 缺口、P3-1 analyzer 缺口与 P3-2 writer 引用缺口没有互相回归。

- [ ] **Step 6: 运行 backend 全量回归**

Run:

```bash
mvn -pl backend test
```

Expected: PASS。若失败测试名称包含 `ReportWriterAgent`、`WriterCitationGapInspector`、`WriterSuggestionAssembler`、`OrchestrationDecisionService`、`DagExecutor`、`TaskReplayProjectionService`，必须在 P3-2 执行 1 范围内修复。

- [ ] **Step 7: 运行 diff 检查**

Run:

```bash
git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/agent/writer backend/src/main/java/cn/bugstack/competitoragent/orchestration backend/src/main/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/agent/writer backend/src/test/java/cn/bugstack/competitoragent/orchestration backend/src/test/java/cn/bugstack/competitoragent/workflow backend/src/test/java/cn/bugstack/competitoragent/integration backend/src/test/java/cn/bugstack/competitoragent/task docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md
```

Expected: no output。

- [ ] **Step 8: 更新总蓝图 3.4 状态**

在 `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 的 `3.4 Agent 协作编排层` 段落追加：

```markdown
- P3-2 执行 1：`✅` Writer 章节引用缺口已输出为标准 `AgentSuggestion -> OrchestrationDecision` 协作决策轨迹；`write_report / rewrite_report` 能暴露 `writerEvidenceState / citationGapSeverity / sectionCitationGaps`，无来源章节会进入 `WAIT_FOR_HUMAN` 阻断后续 Reviewer，有来源但引用不完整的章节会记录 `REWRITE_ONLY / REWRITE_SECTION` 决策供质检和后续重写链路消费。完整 Citation Agent 仍留在 P3-4。
```

- [ ] **Step 9: 更新 3.4 架构规格验收记录**

在 `docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md` 的实现验收记录末尾追加：

```markdown
2026-06-24 P3-2 执行 1 自动化实现记录：Writer 输出新增 `writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps`，`WriterSuggestionAssembler` 已把章节引用缺口转换成标准 `AgentSuggestion`；`OrchestrationDecisionService / DagExecutor` 已支持 `write_report / rewrite_report` 触发的受控决策，无来源章节缺口进入 `WAIT_FOR_HUMAN -> WAITING_INTERVENTION`，有来源但引用不完整的章节记录 `REWRITE_ONLY / REWRITE_SECTION` 决策轨迹。Citation Agent 仍未进入本轮范围。
```

- [ ] **Step 10: 更新稳定演示计划**

在 `docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md` 的当前阶段和检查清单中回写：

```markdown
当前阶段：3.4 P3-2 执行 1 已把 Writer 章节引用缺口纳入协作决策轨迹；稳定演示版可展示报告侧 `sourceUrls / evidenceState`、Writer 缺口建议、Orchestrator 决策和 replay 留痕。
```

并把检查项：

```markdown
- [ ] `sourceUrls` 或 `evidenceState` 在报告、诊断、编排决策中可见。
```

更新为：

```markdown
- [x] `sourceUrls` 或 `evidenceState` 在报告、诊断、编排决策中可见；P3-2 执行 1 已补 Writer 章节引用缺口轨迹。
```

- [ ] **Step 11: 更新本计划执行进度**

在本文末尾 `2026-06-24 执行进度` 中把完成项改为 `[x]`，写入实际验证命令和结果。

- [ ] **Step 12: Commit**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/agent-collaboration-orchestration/specs/2026-06-23-agent-collaboration-orchestration-architecture-spec.md docs/superpowers/plans/2026-06-23-stable-demo-version-execution-plan.md docs/superpowers/agent-collaboration-orchestration/task/2026-06-24-agent-collaboration-orchestration-p3-2-writer-citation-gap-execution-1-plan.md
git commit -m "docs: record p3-2 writer citation gap execution"
```

---

## P3-2 Execution 1 Live Smoke Suggestion

自动化通过后再执行真实 smoke。本轮不要求完整 Citation Agent，因此 smoke 只验 Writer 缺口轨迹是否可见、无来源章节是否被阻断。

| 样本 | 目的 | 预期 |
| --- | --- | --- |
| Analyzer 已放行但 Writer 无证据样本 | 验证 Writer `MISSING_SOURCE` 引用缺口进入 Orchestrator trace | `write_report` 进入 `WAITING_INTERVENTION`，`quality_check` 不执行 |
| 官网 + 文档 + 定价页样本 | 验证 Writer 有来源但引用不完整时留痕，不伪装 Citation 完成 | `write_report` 输出 `sectionCitationGaps` 或 `citationGapSeverity=NONE`；若有 gap，replay 有 `REWRITE_ONLY` 决策 |

推荐请求输入：

```json
{
  "taskName": "P3-2 Writer citation gap smoke",
  "subjectProduct": "企业级 RAG 知识库平台",
  "competitorNames": ["Notion AI"],
  "competitorUrls": [
    "https://www.notion.so/product/ai",
    "https://www.notion.so/help/guides/category/notion-ai",
    "https://www.notion.so/pricing"
  ],
  "analysisDimensions": ["产品功能", "市场定位", "价格策略", "目标用户", "优势判断", "短板与风险"],
  "sourceScope": "OFFICIAL_ONLY",
  "reportLanguage": "中文",
  "reportTemplate": "STANDARD_COMPETITOR_ANALYSIS_V1"
}
```

验收检查：

1. `GET /api/task/{taskId}/nodes` 中 `write_report.outputData` 包含 `writerEvidenceState / citationGapSeverity / sectionCitationGaps`。
2. `GET /api/task/{taskId}/replay` 中能看到 `write_report` 触发的 `ORCHESTRATION_DECISION_RECORDED`。
3. 无来源章节缺口样本中 `quality_check` 保持 `PENDING`，不能让 Reviewer 审一个无来源报告。
4. 有来源样本中 Writer 的 `sourceUrls` 不为空，且 Citation Agent 未完成前不宣称逐句引用完全正确。

---

## Plan Self-Review

### Spec Coverage

1. 架构规格 P3 第 2 项 “Writer 输出章节引用缺口”：Task 1-3 覆盖。
2. 同一协议接入：Task 3-4 使用 `AgentSuggestion -> OrchestrationDecision`，不新增隐式编排。
3. `sourceUrls / evidenceState` 红线：Task 1-5 均要求输出或显式缺口状态。
4. Citation Agent 后移：Scope Guard 与 Live Smoke 明确不做逐句核查。
5. replay / trace 可观察：Task 4-5 覆盖。
6. P1/P2/P3-1 不回归：Task 5 聚合命令覆盖。

### Placeholder Scan

本文所有执行项均已写明具体文件、测试命令和预期结果；执行结果只允许在 Task 5 完成后写入实际 PASS/FAIL。

### Type Consistency

1. Writer 输出字段统一为 `writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps`。
2. Writer suggestion 类型统一为 `CITATION_GAP`。
3. Writer 触发节点统一为 `write_report / rewrite_report`。
4. 无来源缺口统一进入 `WAIT_FOR_HUMAN / MANUAL_REVIEW`。
5. 有来源引用缺口统一记录 `REWRITE_ONLY / REWRITE_SECTION`。

---

## 2026-06-24 执行进度

当前阶段：P3-2 执行 1 已完成自动化实现、smoke、replay 与文档回链。
- [x] 读取 P2 最新真实 smoke、P3-1 Task 6 进度、3.4 架构规格和总蓝图
- [x] 确认 P3-2 执行 1 范围：Writer 章节引用缺口，不做 Citation Agent
- [x] 编写 P3-2 执行 1 可执行计划
- [x] Task 1：WriterCitationGap 契约与检测器
- [x] Task 2：ReportWriterAgent 输出引用缺口元数据
- [x] Task 3：WriterSuggestionAssembler
- [x] Task 4：Orchestrator / DagExecutor Writer suggestion gate
- [x] Task 5：replay / smoke / 文档回链

当前阶段：P3-2 执行 1 自动化收口完成
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

- [x] Task 1：已完成，`WriterCitationGap / WriterCitationGapInspector` 已冻结 Writer 章节引用缺口事实，`WriterCitationGapInspectorTest` 红灯转绿
- [x] Task 2：已完成，`ReportWriterAgent` 已输出 `writerEvidenceState / citationGapSeverity / missingCitationSections / sectionCitationGaps`
- [x] Task 3：已完成，`WriterSuggestionAssembler` 已把 Writer 引用缺口转换成标准 `AgentSuggestion`
- [x] Task 4：已完成，`OrchestrationDecisionService / DagExecutor` 已支持 `write_report / rewrite_report` 触发的 Writer suggestion gate
- [x] Task 5：已完成，`CollaborationPlanningSmokeTest / TaskReplayProjectionServiceTest`、总蓝图、架构规格、稳定演示计划与本文执行进度均已回链
- [x] P3-2 局部聚合验证：`mvn -pl backend "-Dtest=ReportWriterAgentTest,WriterCitationGapInspectorTest,WriterSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest,CollaborationPlanningSmokeTest,TaskReplayProjectionServiceTest" test` 通过，63 tests, 0 failures
- [x] P1+P2+P3-1+P3-2 编排聚合验证：`mvn -pl backend "-Dtest=OrchestrationContractTest,OrchestrationDecisionAdapterTest,DecisionPolicyServiceTest,OrchestrationDecisionServiceTest,OrchestrationTraceServiceTest,CompensationGraphAssemblerTest,DynamicTaskGraphServiceTest,DynamicPlanAppenderTest,OrchestrationRuntimeFeedbackSmokeTest,CollaborationContractTest,CollaborationGoalAssemblerTest,CollaborationPlanServiceTest,InitialPlanReviewServiceTest,CollaborationTraceServiceTest,ExtractorSuggestionAssemblerTest,AnalyzerSuggestionAssemblerTest,WriterSuggestionAssemblerTest,CollaborationPlanningSmokeTest,DagExecutorTest" test` 通过，80 tests, 0 failures
- [x] backend 全量回归：`mvn -pl backend test` 已执行并通过
- [ ] 后续剩余：不属于 P3-2 执行 1 本轮；P3-3 再把 Conversation 动作预览接到 `OrchestrationDecision`，P3-4 再补完整 Citation Agent 与逐句引用核查
