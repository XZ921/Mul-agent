# AnalysisReasoning Minimal Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入 4.x、不修改编排协议的前提下，收紧 Analyzer Prompt 与章节证据束聚合，减少无证据或错配证据进入 Writer。

**Architecture:** 本轮只改 Analyzer 链路内行为：用资源文件覆盖 `analyzer` 默认 Prompt，并在 `CompetitorAnalysisAgent` 内增加私有的 evidence view 章节匹配规则。`AnalysisResult`、`AgentSuggestion`、`OrchestrationDecision`、report/export DTO 和数据库 schema 均不扩展。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Jackson, JUnit 5, AssertJ, Mockito, existing prompt resource loading.

---

## File Structure

- Create: `backend/src/main/resources/prompts/analyzer.txt`
  - 负责覆盖内联 Analyzer Prompt，明确逐维度 JSON 输出、来源约束和缺证据留空规则。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
  - 增加 Analyzer Prompt 契约测试。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
  - 增加章节证据束匹配测试。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
  - 增加私有 evidence view 匹配方法，避免不相关 view 污染所有章节。
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
  - 实施完成后补充自动化收口记录，但不标记实链验证完成。
- Modify: `docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md`
  - 实施完成后补充验证结果。

---

### Task 1: Analyzer Prompt Contract

**Files:**
- Create: `backend/src/main/resources/prompts/analyzer.txt`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`

- [ ] **Step 1: Write the failing prompt contract test**

Add this test to `PromptTemplateServiceTest`:

```java
@Test
void analyzerTemplateShouldRequireTraceableDimensionAnalysis() {
    String prompt = promptTemplateService.render("analyzer", Map.of(
            "subjectProduct", "Our Product",
            "analysisDimensions", "功能,定价",
            "competitorData", "[]",
            "taskRagContext", ""));

    assertThat(prompt)
            .contains("只能返回 JSON 对象")
            .contains("featureComparison")
            .contains("pricingComparison")
            .contains("sourceUrls")
            .contains("缺少证据")
            .contains("不要编造")
            .contains("只能使用竞品数据中已经出现的来源链接");
}
```

- [ ] **Step 2: Run the prompt test to verify it fails**

Run:

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest#analyzerTemplateShouldRequireTraceableDimensionAnalysis" test
```

Expected: FAIL because the current inline analyzer template only includes the product, dimensions, and competitor data.

- [ ] **Step 3: Create the resource prompt**

Create `backend/src/main/resources/prompts/analyzer.txt`:

```text
你是一名资深竞品分析专家，请只能返回 JSON 对象。

# 本方产品
{subjectProduct}

# 分析维度
{analysisDimensions}

# 竞品数据
{competitorData}

# 输出字段
顶层必须包含：
- overview
- featureComparison
- positioningComparison
- pricingComparison
- targetUserComparison
- strengthsSummary
- weaknessesSummary
- opportunities
- risks
- recommendations
- sourceUrls
- issueFlags

# 分析规则
1. 每个核心分析字段必须基于竞品数据中的 sourceUrls、evidenceCoverage、downstreamEvidenceViews 或 structuredBlocks。
2. sourceUrls 只能使用竞品数据中已经出现的来源链接，禁止生成新 URL。
3. 如果某个维度缺少证据，对应字段返回 null 或空字符串，并在 issueFlags 中保留缺少证据的说明，不要编造。
4. overview 不能替代 featureComparison、positioningComparison、pricingComparison、targetUserComparison、strengthsSummary、weaknessesSummary。
5. 输出必须是 JSON 对象，不要输出 Markdown、解释文字或模型元话术。
```

- [ ] **Step 4: Run the prompt test to verify it passes**

Run:

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest#analyzerTemplateShouldRequireTraceableDimensionAnalysis" test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- backend/src/main/resources/prompts/analyzer.txt backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java
git commit -m "feat: tighten analyzer prompt contract"
```

### Task 2: Section Evidence View Matching

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`

- [ ] **Step 1: Write the failing section matching test**

Add this test to `CompetitorAnalysisAgentTest`:

```java
@Test
void shouldOnlyAttachMatchedEvidenceViewsToAnalyzerSectionBundles() throws Exception {
    when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(knowledgeRepository.findByTaskIdOrderByIdAsc(77L)).thenReturn(List.of());
    when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
            {
              "overview": "分析完成",
              "featureComparison": "功能信息不足",
              "positioningComparison": "定位信息不足",
              "pricingComparison": "Pro plan is public at 199 per month",
              "targetUserComparison": "团队用户",
              "strengthsSummary": "文档清晰",
              "weaknessesSummary": "价格证据仍需更多来源",
              "recommendations": ["继续观察"]
            }
            """);
    when(llmClient.getModelName()).thenReturn("mock-model");
    when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

    AgentContext context = AgentContext.builder()
            .taskId(77L)
            .taskName("analysis-evidence-match")
            .subjectProduct("Our Product")
            .analysisDimensions("功能,定价")
            .currentNodeName("analyze_competitors")
            .build();
    context.putSharedOutput("extract_schema", """
            {
              "downstreamEvidenceViews": [
                {
                  "evidenceId": "P001",
                  "competitorName": "Acme",
                  "title": "Acme Pricing",
                  "content": "Pricing page lists Pro plan at 199 per month.",
                  "sourceUrls": ["https://acme.example.com/pricing"],
                  "qualitySignals": ["PRICING_BLOCK_HIT"],
                  "structuredBlocks": [
                    {"blockType": "PRICING_BLOCK", "summary": "Pro 199 / month"}
                  ]
                }
              ]
            }
            """);

    AgentResult result = agent.execute(context);
    JsonNode output = objectMapper.readTree(result.getOutputData());
    JsonNode pricingBundle = findBundle(output.path("sectionEvidenceBundles"), "pricing");
    JsonNode featureBundle = findBundle(output.path("sectionEvidenceBundles"), "features");

    assertEquals("SUCCESS", result.getStatus().name());
    assertThat(pricingBundle.path("sourceUrls").toString()).contains("https://acme.example.com/pricing");
    assertThat(pricingBundle.path("missingFields").toString()).doesNotContain("pricingComparison");
    assertThat(featureBundle.path("sourceUrls").toString()).doesNotContain("https://acme.example.com/pricing");
    assertThat(featureBundle.path("issueFlags").toString()).contains("SECTION_EVIDENCE_GAP");
}
```

- [ ] **Step 2: Run the section matching test to verify it fails**

Run:

```powershell
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest#shouldOnlyAttachMatchedEvidenceViewsToAnalyzerSectionBundles" test
```

Expected: FAIL because the current implementation attaches every `DownstreamEvidenceView` for a competitor to every analyzer section.

- [ ] **Step 3: Implement matched view filtering**

In `CompetitorAnalysisAgent.buildSectionEvidenceBundles(...)`, replace:

```java
List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
if (!matchedViews.isEmpty()) {
    sectionFragments.addAll(buildEvidenceViewSectionFragments(mapping, competitorName, matchedViews));
    sourceUrls.addAll(collectEvidenceViewSourceUrls(matchedViews));
}
```

with:

```java
List<DownstreamEvidenceView> matchedViews = filterEvidenceViewsForSection(
        mapping,
        viewsByCompetitor.getOrDefault(competitorName, List.of()));
if (!matchedViews.isEmpty()) {
    sectionFragments.addAll(buildEvidenceViewSectionFragments(mapping, competitorName, matchedViews));
    sourceUrls.addAll(collectEvidenceViewSourceUrls(matchedViews));
    if (!collectEvidenceViewSourceUrls(matchedViews).isEmpty()) {
        traceable = true;
    }
}
```

Add these private methods in `CompetitorAnalysisAgent` near `buildEvidenceViewSectionFragments(...)`:

```java
/**
 * 只把与当前分析章节匹配的运行态证据视图挂入章节证据束。
 * Analyzer 顶层仍保留完整 downstreamEvidenceViews；这里的过滤只影响 Writer 可直接引用的章节证据。
 */
private List<DownstreamEvidenceView> filterEvidenceViewsForSection(SectionMapping mapping,
                                                                   List<DownstreamEvidenceView> views) {
    List<DownstreamEvidenceView> matched = new ArrayList<>();
    for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
        DownstreamEvidenceView normalized = view == null ? null : view.normalized();
        if (normalized != null && matchesSection(mapping, normalized)) {
            matched.add(normalized);
        }
    }
    return matched;
}

/**
 * 匹配逻辑保持保守：优先看结构块和质量信号，再用标题/正文关键词兜底。
 * 这样可以减少 pricing 证据污染 feature 章节，同时避免完全丢失 views-only 场景的可追溯性。
 */
private boolean matchesSection(SectionMapping mapping, DownstreamEvidenceView view) {
    String sectionKey = mapping == null ? "" : firstNonBlank(mapping.sectionKey(), "");
    String haystack = evidenceViewHaystack(view);
    return switch (sectionKey) {
        case "overview" -> hasEvidenceSource(view);
        case "features" -> containsAny(haystack, "feature", "capability", "功能", "能力");
        case "pricing" -> containsAny(haystack, "pricing", "price", "plan", "定价", "价格", "套餐");
        case "targetUsers" -> containsAny(haystack, "user", "customer", "audience", "用户", "客户", "受众");
        case "positioning" -> containsAny(haystack, "positioning", "market", "segment", "定位", "市场");
        case "strengths" -> containsAny(haystack, "strength", "advantage", "pros", "优势", "亮点");
        case "weaknesses" -> containsAny(haystack, "weakness", "risk", "limitation", "cons", "短板", "风险", "限制");
        default -> false;
    };
}

private String evidenceViewHaystack(DownstreamEvidenceView view) {
    StringBuilder builder = new StringBuilder();
    if (view == null) {
        return "";
    }
    appendLower(builder, view.getTitle());
    appendLower(builder, view.getContent());
    for (String signal : view.getQualitySignals() == null ? List.<String>of() : view.getQualitySignals()) {
        appendLower(builder, signal);
    }
    for (var block : view.getStructuredBlocks() == null ? List.<DownstreamEvidenceBlock>of() : view.getStructuredBlocks()) {
        if (block == null) {
            continue;
        }
        appendLower(builder, block.getBlockType());
        appendLower(builder, block.getSummary());
    }
    return builder.toString();
}

private boolean hasEvidenceSource(DownstreamEvidenceView view) {
    return view != null && view.getSourceUrls() != null && !view.getSourceUrls().isEmpty();
}

private void appendLower(StringBuilder builder, String value) {
    if (value != null && !value.isBlank()) {
        builder.append(' ').append(value.toLowerCase(java.util.Locale.ROOT));
    }
}

private boolean containsAny(String haystack, String... needles) {
    if (haystack == null || haystack.isBlank()) {
        return false;
    }
    for (String needle : needles) {
        if (needle != null && !needle.isBlank()
                && haystack.contains(needle.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
    }
    return false;
}
```

Add import:

```java
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
```

- [ ] **Step 4: Run the section matching test to verify it passes**

Run:

```powershell
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest#shouldOnlyAttachMatchedEvidenceViewsToAnalyzerSectionBundles" test
```

Expected: PASS.

- [ ] **Step 5: Run the full analyzer test class**

Run:

```powershell
mvn -pl backend "-Dtest=CompetitorAnalysisAgentTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```powershell
git add -- backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java
git commit -m "feat: match analyzer evidence views by section"
```

### Task 3: Regression and Roadmap Closure

**Files:**
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md`

- [ ] **Step 1: Run focused regression**

Run:

```powershell
mvn -pl backend "-Dtest=PromptTemplateServiceTest,CompetitorAnalysisAgentTest,AnalyzerSuggestionAssemblerTest,ReportWriterAgentTest" test
```

Expected: PASS. This proves prompt loading, Analyzer output normalization, Analyzer suggestion protocol, and Writer consumption remain compatible.

- [ ] **Step 2: Run collaboration protocol guard regression**

Run:

```powershell
mvn -pl backend "-Dtest=AnalyzerSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest" test
```

Expected: PASS. This proves the minimal Analyzer quality patch did not regress P3-1 orchestration behavior.

- [ ] **Step 3: Run diff check**

Run:

```powershell
git diff --check -- backend/src/main/resources/prompts/analyzer.txt backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md
```

Expected: PASS. LF-to-CRLF warnings are acceptable in this Windows workspace; whitespace errors are not.

- [ ] **Step 4: Update roadmap and plan verification notes**

In `docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md`, update the progress block:

```markdown
当前阶段：分析推理最小稳固切片自动化收口
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 方案边界确认：已完成
- [x] 实施计划：已完成
- [x] 代码实施：已完成
- [x] 自动化验证：已完成
- [ ] 实链验证：待执行
```

Add a verification table:

```markdown
## 9. 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -pl backend "-Dtest=PromptTemplateServiceTest,CompetitorAnalysisAgentTest,AnalyzerSuggestionAssemblerTest,ReportWriterAgentTest" test` | PASS |
| `mvn -pl backend "-Dtest=AnalyzerSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DagExecutorTest" test` | PASS |
| `git diff --check -- backend/src/main/resources/prompts/analyzer.txt backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md` | PASS |
```

In `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`, update only the AnalysisReasoning implementation cell to `🟡` with automatic verification evidence. Keep live verification as `⬜`.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/analysis-reasoning/plan/2026-06-26-analysis-reasoning-minimal-stabilization-plan.md
git commit -m "docs: record analysis reasoning stabilization verification"
```

---

## Self-Review

- Spec coverage: Task 1 covers prompt strengthening; Task 2 covers section evidence matching and views-only traceability; Task 3 covers regression and roadmap updates.
- Boundary check: No task changes 4.x runtime, orchestration protocols, report/export DTOs, database schema, Tavily, or frontend.
- Type consistency: All referenced fields already exist in `AnalysisResult`, `SectionEvidenceBundle`, `DownstreamEvidenceView`, and `DownstreamEvidenceBlock`.
- Test-first check: Every code change is preceded by a focused failing test and a pass command.

Plan complete and saved to `docs/superpowers/analysis-reasoning/task/2026-06-26-analysis-reasoning-minimal-stabilization-implementation-plan.md`.
