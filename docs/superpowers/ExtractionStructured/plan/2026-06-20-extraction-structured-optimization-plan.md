# 3.3 提取结构化实施总计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在继承 3.2 搜索与采集输入契约的前提下，分轮收口 `extract_schema` 的提取稳定性、运行态边界和下游失败诊断，让真实任务能被清楚判定为 extractor 问题、analyzer 消费边界问题，或 writer / reviewer / delivery 问题。

**Architecture:** 本计划按 P0、P1、P2 三轮推进。P0 只修 extractor 当前主停点，围绕 Prompt 分层、0 业务字段语义重试、结构块型证据入口、Schema 注入和字段来源约束做最小闭环；P1 再引入正式输入 Provider、运行态输出优先级和轻量 shared output；P2 将失败分层扩展到 workflow 汇总与真实链路验证。`CompetitorKnowledge TASK` 在本阶段保留为任务快照和恢复 fallback，不再被设计为 analyzer 唯一事实源。

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, Maven

---

## 当前依据

本计划取代旧版 `2026-06-20-extraction-structured-optimization-plan.md`，以 2026-06-21 后的诊断和架构结论为准。

- 总蓝图：[2026-06-11-business-landscape-and-optimization-roadmap-design.md](../../../specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md)
- 诊断基线：[ExtractionStructured.md](../problem/ExtractionStructured.md)
- 3.2 继承前提：[2026-06-20-extraction-structured-3.2-inheritance-baseline.md](../problem/2026-06-20-extraction-structured-3.2-inheritance-baseline.md)
- 架构规格：[2026-06-21-extraction-structured-architecture-spec.md](../specs/2026-06-21-extraction-structured-architecture-spec.md)
- 优化点汇总：[2026-06-21-extraction-structured-optimization-summary.md](../summary/2026-06-21-extraction-structured-optimization-summary.md)

当前工程事实：

1. `ExecutionPlanDefinitionBuilder` 已把 `dimensions / schemaId` 写入 `extract_schema.nodeConfig`，但 extractor 尚未消费。
2. `SchemaExtractorAgent` 已输出 `ExtractResult / drafts / sourceUrls / issueFlags / evidenceFragments / sectionEvidenceBundles / downstreamEvidenceViews`，并显式写入 `CompetitorKnowledge` 的 `TASK` 快照边界。
3. `SchemaExtractorAgent` 已有 0 业务字段阻断保护，但还没有“JSON 合法、业务字段全空、正文可读”时的语义重试。
4. `SchemaExtractorAgent.isUsableEvidence()` 仍只看 `fullContent / contentSnippet`，会漏掉“正文为空但 `structuredBlocks / structuredPayload` 非空”的结构块型证据。
5. `buildCollectedContent()` 仍把 `qualitySignals / issueFlags / structuredBlocks / content` 混在一段文本里，Prompt 还没有稳定表达“结构化证据优先、正文兜底、质量信号转提取指引”。
6. `CompetitorAnalysisAgent` 已能从 `extract_schema` 读取 `DownstreamEvidenceView`；当 repository 存在 `CompetitorKnowledge` 时，仍以 repository 快照为主，再附加匹配的运行态证据视图。

已有测试不要重复建设：

- `SchemaExtractorAgentTest.shouldBuildExtractorInputFromStructuredEvidenceViewInsteadOfRawFullContentOnly`
- `SchemaExtractorAgentTest.shouldFailWhenModelReturnsNoBusinessFieldsDespiteUsableEvidence`
- `CompetitorAnalysisAgentTest.shouldCarryDownstreamEvidenceViewsFromExtractorToAnalyzer`

### P0 覆盖矩阵

这张表用于防止计划再次窄化为“只做 0 字段重试”。执行 P0 时必须逐项对照。

| 来源要求 | P0 要求 | 本计划落点 |
| --- | --- | --- |
| 诊断文档 P0 | Prompt 分层 | Task 1 Step 4 红灯测试；Task 2 Step 1、2、4、5、6 实现 |
| 诊断文档 P0 | 字段级提取指引 | Task 1 Step 4 红灯测试；Task 2 Step 3 默认实现；Task 5 Step 2 最终实现 |
| 诊断文档 P0 | 0 业务字段语义重试 | Task 1 Step 1 红灯测试；Task 3 实现 |
| 诊断文档 P0 | 结构块型证据入口 | Task 1 Step 2 红灯测试；Task 4 Step 1、2 实现 |
| 诊断文档 P0 | Schema 驱动 extractor | Task 1 Step 3 红灯测试；Task 5 Step 1 实现 |
| 优化点汇总 P0 1.1 | `structuredBlocks / readableContent / qualitySignals` 显式分段 | Task 2 |
| 优化点汇总 P0 1.2 | 7 个字段独立提取指引 | Task 2 Step 3；Task 5 Step 2 |
| 优化点汇总 P0 2.1 | 0 业务字段语义重试 | Task 3 |
| 优化点汇总 P0 3.1 | extractor 读取 `dimensions / schemaId` | Task 5 |
| 架构规格 P0 | qualitySignals 翻译、输出强约束 | Task 2 Step 4、6 |

## 分轮边界

| 轮次 | 时间锚点 | 本轮目标 | 退出条件 |
| --- | --- | --- | --- |
| P0 | 本迭代 | 修 extractor 主停点：Prompt 分层、0 字段语义重试、结构块型证据入口、Schema 注入、字段来源约束 | extractor 能在已有可读证据或结构块证据下稳定抽出非空业务字段；抽不出时能解释原因 |
| P1 | 下一迭代 | 收运行态边界：`ExtractorInputProvider / ExtractorInputPackage`、analyzer 优先消费 `ExtractResult.drafts`、`DownstreamEvidenceView` 轻量化 | rerun / resume / replay 能解释 extractor 实际消费输入和 analyzer 实际消费事实源 |
| P2 | P1 通过后的专项 | 拆下游失败：workflow 失败分层、`evidenceCoverage` 细化、task `50` 类实链验证 | 能明确失败已经不在 extractor / analyzer 边界时，把问题移交给写作、质检或交付链路 |
| 后续队列 | P2 后 | Schema 行为中心、Agent 职责拆分、分字段组提取、replay/cache 统一输入源、UI 展示调整 | 独立开新规格和新实施计划 |

P0 未通过时，不提前扩大到 Provider 全量重构或下游质量门禁；P1 未通过时，不把 shared output 长正文和两套事实源问题推给 P2。

## 文件结构

### P0 主要修改

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

Prompt 模板位置说明：当前 `extractor` 默认模板不是独立的 `.txt` 文件，而是在 `PromptTemplateService` 的默认模板列表中注册；`backend/src/main/resources/prompts/` 目前只承载 conversation、task-action 和搜索查询相关模板。因此 P0 的 Prompt 分层改动必须改 `PromptTemplateService`，不能只改 `buildCollectedContent()`。

### P1 计划入口

- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/input/ExtractorInputProvider.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/input/RepositoryExtractorInputProvider.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/input/ExtractorInputPackage.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/input/ExtractorCompetitorInput.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjector.java` 或新增 `extract_schema` 专用 projector
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/analyzer/CompetitorAnalysisAgentTest.java`

### P2 计划入口

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/SectionEvidenceBundle.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/EvidenceFragment.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Test: workflow、report、reviewer 相关契约测试
- Docs: 更新 `ExtractionStructured.md`、架构规格和总蓝图状态回链

## P0 本轮执行任务

### Task 1: 补齐 extractor 当前缺口的红灯测试

**Files:**

- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

- [x] **Step 1: 新增“首轮 0 字段但正文可读时应语义重试”用例**

在 `SchemaExtractorAgentTest` 中新增测试。该测试与已有 `shouldFailWhenModelReturnsNoBusinessFieldsDespiteUsableEvidence` 不重复：已有用例证明 0 字段不能成功，本用例证明可读正文存在时会追加一次业务语义重试，并在第二次返回非空字段后成功。

```java
@Test
void shouldRetryWhenFirstPassReturnsZeroBusinessFieldsButReadableEvidenceExists() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(1L)
                    .competitorName("Notion AI")
                    .evidenceId("E001")
                    .title("Pricing")
                    .url("https://www.notion.so/pricing")
                    .fullContent("Notion AI provides pricing and workspace plan details for teams, docs, search, and project collaboration.")
                    .contentSnippet("workspace plan details for teams")
                    .build()
    ));
    when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
            .thenReturn("""
                    {
                      "officialUrl": "https://www.notion.so",
                      "summary": "",
                      "positioning": "",
                      "targetUsers": [],
                      "coreFeatures": [],
                      "pricing": {},
                      "strengths": [],
                      "weaknesses": [],
                      "sources": [],
                      "sourceUrls": []
                    }
                    """)
            .thenReturn("""
                    {
                      "officialUrl": "https://www.notion.so",
                      "summary": "workspace assistant",
                      "positioning": "workspace ai",
                      "targetUsers": ["teams"],
                      "coreFeatures": [],
                      "pricing": {"model": "team workspace plan", "evidenceIds": ["E001"]},
                      "strengths": [],
                      "weaknesses": [],
                      "sources": [],
                      "sourceUrls": ["https://www.notion.so/pricing"]
                    }
                    """);

    AgentResult result = extractorAgent.execute(AgentContext.builder()
            .taskId(1L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build());

    assertEquals("SUCCESS", result.getStatus().name());
    verify(llmClient, times(2)).chatForJson(any(), any(), eq("ExtractedSchema"));
    verify(knowledgeRepository).save(any(CompetitorKnowledge.class));
}
```

- [x] **Step 2: 新增“正文为空但结构块非空的证据不能被入口过滤”用例**

```java
@Test
void shouldAcceptStructuredOnlyEvidenceWhenContentIsBlank() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(1L)
                    .competitorName("Acme")
                    .evidenceId("E001")
                    .title("Pricing API")
                    .url("https://api.example.com/pricing")
                    .sourceType("API_DATA")
                    .fullContent("")
                    .contentSnippet("")
                    .pageMetadata("""
                            {
                              "sourceUrls": ["https://api.example.com/pricing"],
                              "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                              "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                              "structuredPayload": {"plans": [{"name": "Pro", "price": 199}]}
                            }
                            """)
                    .build()
    ));
    when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
            .thenReturn("""
                    {
                      "officialUrl": "https://example.com",
                      "summary": "pricing api",
                      "positioning": "",
                      "targetUsers": [],
                      "coreFeatures": [],
                      "pricing": {"model": "Pro 199 / 月", "evidenceIds": ["E001"]},
                      "strengths": [],
                      "weaknesses": [],
                      "sources": [],
                      "sourceUrls": ["https://api.example.com/pricing"]
                    }
                    """);

    AgentResult result = extractorAgent.execute(AgentContext.builder()
            .taskId(1L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build());

    assertEquals("SUCCESS", result.getStatus().name());
    verify(promptService).render(eq("extractor"), argThat(variables ->
            variables.toString().contains("PRICING_BLOCK")
                    && variables.toString().contains("STRUCTURED_BLOCK_HIT")
    ));
}
```

- [x] **Step 3: 新增“nodeConfig 的 schemaId / dimensions 应注入 Prompt”用例**

```java
@Test
void shouldInjectSchemaDimensionsFromCurrentNodeConfigIntoExtractorPrompt() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(1L)
                    .competitorName("Notion AI")
                    .evidenceId("E001")
                    .title("Product")
                    .url("https://www.notion.so/product")
                    .fullContent("Notion AI helps teams manage docs, projects, search, and workspace knowledge.")
                    .build()
    ));
    when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
            .thenReturn("""
                    {
                      "officialUrl": "https://www.notion.so",
                      "summary": "workspace assistant",
                      "positioning": "team workspace",
                      "targetUsers": ["teams"],
                      "coreFeatures": [],
                      "pricing": {},
                      "strengths": [],
                      "weaknesses": [],
                      "sources": [],
                      "sourceUrls": ["https://www.notion.so/product"]
                    }
                    """);

    AgentResult result = extractorAgent.execute(AgentContext.builder()
            .taskId(1L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .currentNodeConfig("""
                    {
                      "schemaId": 7,
                      "dimensions": ["产品功能", "价格策略", "目标用户"]
                    }
                    """)
            .build());

    assertEquals("SUCCESS", result.getStatus().name());
    verify(promptService).render(eq("extractor"), argThat(variables ->
            variables.get("schemaGuidance") != null
                    && variables.get("schemaGuidance").contains("schemaId=7")
                    && variables.get("schemaGuidance").contains("产品功能")
                    && variables.get("schemaGuidance").contains("价格策略")
    ));
}
```

- [x] **Step 4: 新增“Prompt 变量必须拆分结构化证据、质量信号和正文兜底”用例**

```java
@Test
void shouldRenderSeparatedPromptInputsForStructuredQualityAndReadableEvidence() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(1L)
                    .competitorName("Acme")
                    .evidenceId("E001")
                    .title("Docs")
                    .url("https://docs.example.com")
                    .fullContent("Acme offers workflow automation, collaboration features, and team billing details.")
                    .pageMetadata("""
                            {
                              "sourceUrls": ["https://docs.example.com"],
                              "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY", "PRICING_BLOCK_HIT"],
                              "structuredBlocks": [{"blockType": "FEATURE_LIST", "summary": "workflow automation"}]
                            }
                            """)
                    .build()
    ));
    when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
            .thenReturn("""
                    {
                      "officialUrl": "https://example.com",
                      "summary": "workflow automation",
                      "positioning": "team workflow platform",
                      "targetUsers": ["teams"],
                      "coreFeatures": [{"name": "automation", "evidenceIds": ["E001"]}],
                      "pricing": {},
                      "strengths": [],
                      "weaknesses": [],
                      "sources": [],
                      "sourceUrls": ["https://docs.example.com"]
                    }
                    """);

    AgentResult result = extractorAgent.execute(AgentContext.builder()
            .taskId(1L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build());

    assertEquals("SUCCESS", result.getStatus().name());
    verify(promptService).render(eq("extractor"), argThat(variables ->
            variables.get("structuredEvidence") != null
                    && variables.get("structuredEvidence").contains("FEATURE_LIST")
                    && variables.get("qualitySignalGuidance") != null
                    && variables.get("qualitySignalGuidance").contains("PRICING_BLOCK_HIT")
                    && variables.get("readableContent") != null
                    && variables.get("readableContent").contains("workflow automation")
    ));
}
```

- [x] **Step 5: 运行 extractor 定向测试，确认新增用例先失败**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test
```

Expected: 至少新增用例失败，失败点分别对应“未语义重试”“结构块型证据被过滤”“schemaGuidance 缺失”或“Prompt 分层变量缺失”。

### Task 2: 实现 Prompt 输入分层与字段提取指引

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

- [x] **Step 1: 让 `extractAndNormalize` 接收 `AgentContext`，并注入分层 Prompt 变量**

把调用点从当前三参数改成传入 `context`：

```java
NormalizedSchema normalizedSchema = extractAndNormalize(
        context,
        competitorName,
        competitorEvidence
);
```

将方法签名和变量组装改为：

```java
private NormalizedSchema extractAndNormalize(AgentContext context,
                                             String competitorName,
                                             List<EvidenceSource> competitorEvidence)
        throws LlmException, JsonProcessingException {
    List<DownstreamEvidenceView> evidenceViews =
            downstreamEvidenceViewAssembler.fromEvidenceSources(competitorEvidence);
    Map<String, Object> promptVariables = new LinkedHashMap<>();
    promptVariables.put("competitorName", competitorName);
    promptVariables.put("schemaGuidance", buildSchemaGuidance(context.getCurrentNodeConfig()));
    promptVariables.put("fieldExtractionGuidance", buildFieldExtractionGuidance());
    promptVariables.put("evidenceCatalog", buildEvidenceCatalog(evidenceViews));
    promptVariables.put("structuredEvidence", buildStructuredEvidence(evidenceViews));
    promptVariables.put("qualitySignalGuidance", buildQualitySignalGuidance(evidenceViews));
    promptVariables.put("readableContent", buildReadableContent(evidenceViews));
    promptVariables.put("collectedContent", buildCollectedContent(evidenceViews));
    promptVariables.put("taskRagContext", context.getTaskRagPromptContext());
    String prompt = promptService.render("extractor", promptVariables);

    JsonProcessingException lastParseException = null;
    for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
        String attemptPrompt = attempt == 1
                ? prompt
                : prompt + "\n\n【补充要求】上一次返回的 JSON 解析失败，请重新输出一个完整、闭合、合法的 JSON 对象，不要附加解释。";
        String llmResponse = llmClient.chatForJson(
                "你是一名竞品知识抽取专家，请只返回 JSON。",
                attemptPrompt,
                "ExtractedSchema"
        );
        try {
            ParsedSchemaRoot parsedRoot = parseSchemaRoot(llmResponse);
            if (parsedRoot.recovered()) {
                log.warn("extractor recovered non-object json root, competitor={}, attempt={}/{}",
                        competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS);
            }
            return normalizeSchema(parsedRoot.objectNode(), competitorEvidence, evidenceViews, parsedRoot.issueFlags());
        } catch (JsonProcessingException e) {
            lastParseException = e;
            log.warn("extractor json parse failed, competitor={}, attempt={}/{}",
                    competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS, e);
        }
    }
    throw lastParseException == null
            ? new JsonProcessingException("模型未返回可解析 JSON") { }
            : lastParseException;
}
```

`collectedContent` 暂时保留，目的是兼容现有测试和模板迁移期；新的正式模板使用 `structuredEvidence / qualitySignalGuidance / readableContent`。

- [x] **Step 2: 新增结构化证据区**

```java
private String buildStructuredEvidence(List<DownstreamEvidenceView> evidences) throws JsonProcessingException {
    List<Map<String, Object>> blocks = new ArrayList<>();
    for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
        if (evidence.getStructuredBlocks() == null || evidence.getStructuredBlocks().isEmpty()) {
            continue;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceId", safe(evidence.getEvidenceId()));
        item.put("title", safe(evidence.getTitle()));
        item.put("sourceUrls", evidence.getSourceUrls() == null ? List.of() : evidence.getSourceUrls());
        item.put("structuredBlocks", evidence.getStructuredBlocks());
        blocks.add(item);
    }
    if (blocks.isEmpty()) {
        return "无结构化证据。请转入正文内容兜底提取，不能因为 structuredBlocks 为空就返回空业务字段。";
    }
    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks);
}
```

- [x] **Step 3: 新增默认 Schema 指引和字段级提取指引**

先提供可编译的默认实现，Task 5 再把 `schemaId / dimensions` 从 `currentNodeConfig` 中解析出来。

```java
private String buildSchemaGuidance(String currentNodeConfig) {
    return "未解析 schemaId 和 dimensions。按默认 7 个结构化字段提取。";
}

private String buildFieldExtractionGuidance() {
    return """
            summary: 从全部证据归纳产品概述，不超过 200 字，必须有 sourceUrls。
            positioning: 提取市场定位、产品定位或核心价值主张，禁止凭空总结。
            targetUsers: 从用户角色、行业、团队规模和使用场景中提取，返回数组。
            coreFeatures: 每项功能必须带 name、description、evidenceIds 或 sourceUrls。
            pricing: 优先使用 PRICING_BLOCK，提取价格数字、计费周期、免费额度和企业版线索。
            strengths: 只提取证据明确支持的优势判断，不能把营销语直接当事实。
            weaknesses: 只提取证据明确支持的短板、限制或风险，证据不足时返回空数组。
            """;
}
```

- [x] **Step 4: 新增质量信号翻译**

```java
private String buildQualitySignalGuidance(List<DownstreamEvidenceView> evidences) {
    LinkedHashSet<String> signals = new LinkedHashSet<>();
    LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
    for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
        if (evidence.getQualitySignals() != null) {
            signals.addAll(evidence.getQualitySignals());
        }
        if (evidence.getIssueFlags() != null) {
            issueFlags.addAll(evidence.getIssueFlags());
        }
    }
    List<String> guidance = new ArrayList<>();
    for (String signal : signals) {
        switch (signal) {
            case "PRICING_BLOCK_HIT" -> guidance.add("PRICING_BLOCK_HIT: pricing 字段优先引用命中的定价结构块。");
            case "STRUCTURED_BLOCK_HIT" -> guidance.add("STRUCTURED_BLOCK_HIT: 结构块可作为高置信事实，但字段仍必须保留 evidenceIds 或 sourceUrls。");
            case "LIGHTWEIGHT_CONTENT_READY" -> guidance.add("LIGHTWEIGHT_CONTENT_READY: 正文可作为兜底证据参与 summary、positioning、targetUsers 和 coreFeatures 提取。");
            default -> guidance.add(signal + ": 保留该质量信号，并结合 evidenceId 判断字段可信度。");
        }
    }
    for (String issueFlag : issueFlags) {
        switch (issueFlag) {
            case "CONTENT_GAP", "COLLECT_FAILED", "NO_USABLE_CONTENT" ->
                    guidance.add(issueFlag + ": 该证据存在采集缺口，不要从缺失正文中编造字段。");
            default -> guidance.add(issueFlag + ": 将该问题写入字段缺口或 issueFlags，不要静默忽略。");
        }
    }
    return guidance.isEmpty() ? "无显式质量信号。按来源、正文和结构块内容谨慎提取。" : String.join("\n", guidance);
}
```

- [x] **Step 5: 新增正文兜底区**

```java
private String buildReadableContent(List<DownstreamEvidenceView> evidences) {
    final int maxEvidenceContentLength = 4000;
    StringBuilder readableContent = new StringBuilder();
    for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
        String content = evidence.getContent();
        if (content == null || content.isBlank()) {
            continue;
        }
        readableContent.append("--- Source: ")
                .append(safe(evidence.getEvidenceId()))
                .append(" ")
                .append(safe(evidence.getTitle()))
                .append(" ---\n");
        readableContent.append("sourceUrls: ").append(evidence.getSourceUrls()).append('\n');
        readableContent.append(truncate(content, maxEvidenceContentLength)).append("\n\n");
    }
    if (readableContent.isEmpty()) {
        return "无可读正文。只能使用结构化证据提取，不能补造结构化证据未覆盖的字段。";
    }
    return readableContent.toString();
}
```

- [x] **Step 6: 更新 extractor 默认 Prompt 模板**

在 `PromptTemplateService` 的 `extractor` 模板中替换旧的“已采集内容”单段结构：

```java
new AbstractMap.SimpleEntry<>("extractor", """
        你是一名竞品信息结构化抽取专家。
        你必须只返回 JSON。

        # 竞品名称
        {competitorName}

        # 本次任务分析重点
        {schemaGuidance}

        # 字段提取指引
        {fieldExtractionGuidance}

        # 证据目录
        {evidenceCatalog}

        # 结构化证据（优先使用）
        {structuredEvidence}

        # 质量信号提取指引
        {qualitySignalGuidance}

        # 正文内容（兜底）
        {readableContent}

        # 输出要求
        顶层必须包含 sourceUrls。
        summary、positioning、targetUsers、coreFeatures、pricing、strengths、weaknesses 任一字段有值时，必须能回指 evidenceIds 或 sourceUrls。
        如果证据不足，请返回空值并保留 issueFlags，不要编造。
        禁止只返回空对象；如果正文可读，请至少尝试抽取一个可证据支撑的业务字段。
        """)
```

- [x] **Step 7: 运行 Prompt 分层相关测试**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldRenderSeparatedPromptInputsForStructuredQualityAndReadableEvidence" test
```

Expected: PASS，`structuredEvidence / qualitySignalGuidance / readableContent` 三类变量均进入 `promptService.render`。

### Task 3: 实现 0 业务字段语义重试

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

重试关系必须固定为两层：

1. `invokeExtractorOnce(..., strictBusinessRetry=false)` 内部保留原有 JSON 解析失败重试，最多 `EXTRACT_JSON_MAX_ATTEMPTS` 次。
2. 只有当某次调用最终得到合法 JSON，且 7 个业务字段全空，且存在长度不低于 40 字符的可读正文时，才追加一次 `invokeExtractorOnce(..., strictBusinessRetry=true)`。
3. 语义重试不是嵌入每一次 JSON 解析失败后执行；JSON 不合法时只走 JSON 修复提示，JSON 合法但业务字段全空时才走业务补抽提示。
4. `shouldRetryWhenFirstPassReturnsZeroBusinessFieldsButReadableEvidenceExists` 的两次 `thenReturn` 对应两次合法 JSON 调用，因此期望 `times(2)`。如果首轮返回非法 JSON，该测试不覆盖；非法 JSON 的 3 次重试仍由既有解析失败用例覆盖。

- [x] **Step 1: 将 Task 2 中保留的 JSON 解析循环拆成单次业务调用方法**

```java
private NormalizedSchema invokeExtractorOnce(String competitorName,
                                             List<EvidenceSource> competitorEvidence,
                                             List<DownstreamEvidenceView> evidenceViews,
                                             String prompt,
                                             boolean strictBusinessRetry)
        throws LlmException, JsonProcessingException {
    JsonProcessingException lastParseException = null;
    String businessRetryInstruction = strictBusinessRetry
            ? "\n\n【业务字段补抽要求】上一轮 JSON 合法但没有抽出任何业务字段。请优先根据结构化证据和可读正文补出 summary、positioning、targetUsers、coreFeatures、pricing、strengths、weaknesses 中至少一个非空字段。sourceUrls 只能作为追溯信息，不能算作业务字段。"
            : "";
    for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
        String attemptPrompt = attempt == 1
                ? prompt + businessRetryInstruction
                : prompt + businessRetryInstruction
                + "\n\n【补充要求】上一次返回的 JSON 解析失败，请重新输出一个完整、闭合、合法的 JSON 对象，不要附加解释。";
        String llmResponse = llmClient.chatForJson(
                "你是一名竞品知识抽取专家，请只返回 JSON。",
                attemptPrompt,
                "ExtractedSchema"
        );
        try {
            ParsedSchemaRoot parsedRoot = parseSchemaRoot(llmResponse);
            if (parsedRoot.recovered()) {
                log.warn("extractor recovered non-object json root, competitor={}, attempt={}/{}",
                        competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS);
            }
            return normalizeSchema(parsedRoot.objectNode(), competitorEvidence, evidenceViews, parsedRoot.issueFlags());
        } catch (JsonProcessingException e) {
            lastParseException = e;
            log.warn("extractor json parse failed, competitor={}, attempt={}/{}",
                    competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS, e);
        }
    }
    throw lastParseException == null
            ? new JsonProcessingException("模型未返回可解析 JSON") { }
            : lastParseException;
}
```

- [x] **Step 2: 在 `extractAndNormalize` 中追加业务语义重试**

```java
NormalizedSchema firstPass = invokeExtractorOnce(
        competitorName,
        competitorEvidence,
        evidenceViews,
        prompt,
        false
);
if (countExtractedFields(firstPass.schema()) > 0 || !hasReadableEvidenceContent(evidenceViews)) {
    return firstPass;
}
log.warn("extractor produced zero business fields in first pass, retrying with strict business instruction, competitor={}",
        competitorName);
return invokeExtractorOnce(
        competitorName,
        competitorEvidence,
        evidenceViews,
        prompt,
        true
);
```

- [x] **Step 3: 新增可读正文判断**

```java
private boolean hasReadableEvidenceContent(List<DownstreamEvidenceView> evidenceViews) {
    for (DownstreamEvidenceView evidenceView : evidenceViews == null ? List.<DownstreamEvidenceView>of() : evidenceViews) {
        if (evidenceView == null || evidenceView.getContent() == null) {
            continue;
        }
        String normalized = evidenceView.getContent().trim();
        if (normalized.length() >= 40) {
            return true;
        }
    }
    return false;
}
```

结构块型证据没有正文时不触发“正文可读语义重试”。它仍可在首轮直接成功；如果首轮 0 字段且无正文，本轮按 `NO_BUSINESS_FIELDS_EXTRACTED` 阻断，避免在没有可读增量信息时反复调用模型。

- [x] **Step 4: 运行语义重试测试**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldRetryWhenFirstPassReturnsZeroBusinessFieldsButReadableEvidenceExists" test
```

Expected: PASS，`llmClient.chatForJson` 被调用 2 次，第二次返回非空业务字段后写入 `CompetitorKnowledge`。

### Task 4: 对齐结构块型证据入口和薄正文诊断

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

- [x] **Step 1: 更新 `isUsableEvidence`，把结构化证据视为可消费输入**

```java
private boolean isUsableEvidence(EvidenceSource evidence) {
    if (evidence == null) {
        return false;
    }
    boolean hasContent = evidence.getFullContent() != null && !evidence.getFullContent().isBlank();
    boolean hasSnippet = evidence.getContentSnippet() != null && !evidence.getContentSnippet().isBlank();
    boolean hasStructuredEvidence = hasStructuredEvidence(evidence);
    return hasContent || hasSnippet || hasStructuredEvidence;
}
```

- [x] **Step 2: 增加 `pageMetadata` 结构化证据判断**

```java
private boolean hasStructuredEvidence(EvidenceSource evidence) {
    if (evidence.getPageMetadata() == null || evidence.getPageMetadata().isBlank()) {
        return false;
    }
    try {
        JsonNode metadata = objectMapper.readTree(evidence.getPageMetadata());
        JsonNode structuredBlocks = metadata.path("structuredBlocks");
        JsonNode structuredPayload = metadata.path("structuredPayload");
        return (structuredBlocks.isArray() && !structuredBlocks.isEmpty())
                || (structuredPayload.isObject() && !structuredPayload.isEmpty())
                || (structuredPayload.isArray() && !structuredPayload.isEmpty());
    } catch (JsonProcessingException e) {
        log.warn("extractor failed to parse pageMetadata for structured evidence, evidenceId={}",
                evidence.getEvidenceId(), e);
        return false;
    }
}
```

- [x] **Step 3: 保留薄正文但不把它当高质量正文**

P0 先不新建 Provider，因此薄正文只在 Prompt 指引中显式标记。把 `buildReadableContent` 中的正文拼接改为：

```java
String content = evidence.getContent();
if (content == null || content.isBlank()) {
    continue;
}
String normalizedContent = content.trim();
if (normalizedContent.length() < 40
        && (evidence.getStructuredBlocks() == null || evidence.getStructuredBlocks().isEmpty())) {
    readableContent.append("--- Source: ")
            .append(safe(evidence.getEvidenceId()))
            .append(" ")
            .append(safe(evidence.getTitle()))
            .append(" ---\n");
    readableContent.append("sourceUrls: ").append(evidence.getSourceUrls()).append('\n');
    readableContent.append("issueFlags: [THIN_CONTENT_ONLY]\n");
    readableContent.append("该证据正文过薄，仅用于诊断，不应作为业务字段主要依据。\n\n");
    continue;
}
```

- [x] **Step 4: 运行结构块入口测试**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldAcceptStructuredOnlyEvidenceWhenContentIsBlank" test
```

Expected: PASS，正文为空但结构块非空的证据不会被 `isUsableEvidence` 过滤。

### Task 5: 让 `schemaId / dimensions` 从 `extract_schema` 开始驱动 Prompt

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

- [x] **Step 1: 新增 `buildSchemaGuidance`**

```java
private String buildSchemaGuidance(String currentNodeConfig) {
    if (currentNodeConfig == null || currentNodeConfig.isBlank()) {
        return "未提供 schemaId 和 dimensions。按默认 7 个结构化字段提取。";
    }
    try {
        JsonNode config = objectMapper.readTree(currentNodeConfig);
        String schemaId = config.path("schemaId").isMissingNode()
                ? "UNKNOWN"
                : config.path("schemaId").asText("UNKNOWN");
        List<String> dimensions = readStringList(config.path("dimensions"));
        if (dimensions.isEmpty()) {
            return "schemaId=" + schemaId + "；未提供分析维度。按默认 7 个结构化字段提取。";
        }
        return "schemaId=" + schemaId + "；本次任务分析重点：" + String.join("、", dimensions)
                + "。请优先提取与这些维度直接相关的字段，并保留 evidenceIds 或 sourceUrls。";
    } catch (JsonProcessingException e) {
        log.warn("extractor failed to parse currentNodeConfig, currentNodeConfig={}", currentNodeConfig, e);
        return "nodeConfig 解析失败。按默认 7 个结构化字段提取，并保留 sourceUrls。";
    }
}
```

- [x] **Step 2: 新增字段级提取指引**

```java
private String buildFieldExtractionGuidance() {
    return """
            summary: 从全部证据归纳产品概述，不超过 200 字，必须有 sourceUrls。
            positioning: 提取市场定位、产品定位或核心价值主张，禁止凭空总结。
            targetUsers: 从用户角色、行业、团队规模和使用场景中提取，返回数组。
            coreFeatures: 每项功能必须带 name、description、evidenceIds 或 sourceUrls。
            pricing: 优先使用 PRICING_BLOCK，提取价格数字、计费周期、免费额度和企业版线索。
            strengths: 只提取证据明确支持的优势判断，不能把营销语直接当事实。
            weaknesses: 只提取证据明确支持的短板、限制或风险，证据不足时返回空数组。
            """;
}
```

- [x] **Step 3: 运行 Schema 注入测试**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldInjectSchemaDimensionsFromCurrentNodeConfigIntoExtractorPrompt" test
```

Expected: PASS，`schemaGuidance` 中可见 `schemaId` 和 `dimensions`。

### Task 6: P0 回归、文档状态和执行交接

**Files:**

- Modify: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Modify: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
- Modify: `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

- [x] **Step 1: 运行 extractor 全量单测**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test
```

Expected: PASS。

- [x] **Step 2: 运行提取与分析相关回归**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test
```

Expected: PASS。

- [x] **Step 3: 运行 backend 全量测试**

Run:

```powershell
mvn -pl backend test
```

Expected: PASS。

- [x] **Step 4: 回写 P0 状态**

更新 `ExtractionStructured.md` 第 4 节生命周期说明和第 5.4 里程碑状态，将 P0 已完成项写成可验证事实，仍未完成项保留在 P1 或 P2。

更新 `2026-06-21-extraction-structured-optimization-summary.md` 的 P0 条目，记录：

- Prompt 已分层为 `structuredEvidence / qualitySignalGuidance / readableContent`。
- 0 业务字段在正文可读时会执行一次业务语义重试。
- 结构块型证据不会因正文为空被入口过滤。
- `schemaId / dimensions` 已进入 extractor Prompt。

总蓝图只回写状态，不复制实施细节。

- [ ] **Step 5: 按本轮改动提交**

说明：本步骤在计划中保留为标准收尾动作，但当前任务已获得用户明确指示“直接在 master 上修改，不需要提交，我自己提交就好”，因此本轮执行到 Step 4 即停止，不在此处自动提交。

Run:

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java `
        backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java `
        backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java `
        docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md `
        docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md `
        docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md
git commit -m "fix: stabilize extraction structured p0"
```

Expected: commit 成功；如果工作区存在用户未提交的无关改动，只提交本任务触碰的文件。

## P1 计划草案

P1 必须单独展开为下一份可执行计划，不与 P0 混在同一次实现中。P1 的验收口径是“运行态边界可解释”，不是“模型提取质量继续调 Prompt”。

### P1 Task A: 定义 `ExtractorInputProvider` 和 `ExtractorInputPackage`

第一版 Provider 内部可以继续读取 `EvidenceSourceRepository`，但 `SchemaExtractorAgent` 不再直接读 repository。输入包必须包含：

- `taskId / nodeName / planVersionId / branchKey`
- `schemaId / dimensions`
- `competitors[].evidenceCatalog`
- `competitors[].structuredEvidence`
- `competitors[].readableEvidence`
- `competitors[].skippedEvidence`
- `competitors[].sourceUrls`
- `competitors[].issueFlags`
- `budget.maxPromptEvidenceChars / usedPromptEvidenceChars / truncated`

退出条件：单测能证明 Agent 只消费 Provider 产物，rerun 时可以从输入包解释“本次实际用了哪些证据”。

### P1 Task B: Prompt 总预算、TopK 和薄内容预检前移到 Provider

规则：

- structuredBlocks 非空的证据优先。
- OFFICIAL / DOCS / PRICING 优先于 NEWS / RSS / REVIEW。
- `CONTENT_GAP / COLLECT_FAILED / NO_USABLE_CONTENT` 不进入正文区，只进入目录区和诊断区。
- 正文低于 40 字符且无结构块时标记 `THIN_CONTENT_ONLY`。
- 超预算证据只保留 `evidenceId / sourceUrls / skipReason`。

退出条件：测试能证明一个竞品 30 条证据时不会把所有正文塞入 Prompt，且被跳过证据可追溯。

### P1 Task C: analyzer 优先消费 `ExtractResult.drafts`

当前 analyzer 已能消费 `DownstreamEvidenceView`，但 repository 有快照时仍以 `CompetitorKnowledge` 为主。P1 要把正式优先级改成：

1. `extract_schema` 输出中的 `drafts + lightweightEvidenceViews`
2. 缺少运行态输出时 fallback 到 `CompetitorKnowledge TASK`
3. fallback 时写入 `EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT`

退出条件：测试覆盖“repository 有旧快照、extract_schema 有新 drafts”时，Prompt 使用新 drafts。

实现算法应按下面的具体规则写，不使用注释级伪代码：

```java
private List<Map<String, Object>> buildPromptPayloads(List<CompetitorKnowledge> knowledges,
                                                      ExtractRuntimeOutput extractorOutput) {
    Map<String, CompetitorKnowledge> snapshotsByCompetitor = indexKnowledgeByCompetitor(knowledges);
    Map<String, List<DownstreamEvidenceView>> viewsByCompetitor =
            groupViewsByCompetitor(extractorOutput.downstreamEvidenceViews());
    List<Map<String, Object>> payloads = new ArrayList<>();
    Set<String> emittedCompetitors = new LinkedHashSet<>();

    for (CompetitorKnowledgeDraft draft : extractorOutput.drafts()) {
        String competitorName = firstNonBlank(draft.getCompetitorName(), "UNKNOWN");
        Map<String, Object> payload = toPromptPayload(draft);
        CompetitorKnowledge snapshot = snapshotsByCompetitor.get(competitorName);
        if (snapshot != null) {
            mergeMissingFieldsFromTaskSnapshot(payload, snapshot);
        }
        List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
        if (!matchedViews.isEmpty()) {
            payload.put("downstreamEvidenceViews", normalizeDownstreamEvidenceViews(matchedViews));
        }
        payload.put("inputPriority", "EXTRACT_RESULT_DRAFT");
        payloads.add(payload);
        emittedCompetitors.add(competitorName);
    }

    for (CompetitorKnowledge snapshot : knowledges == null ? List.<CompetitorKnowledge>of() : knowledges) {
        String competitorName = firstNonBlank(snapshot.getCompetitorName(), "UNKNOWN");
        if (emittedCompetitors.contains(competitorName)) {
            continue;
        }
        Map<String, Object> payload = toPromptPayload(snapshot);
        payload.put("inputPriority", "TASK_SNAPSHOT_FALLBACK");
        payload.put("issueFlags", appendIssueFlag(payload.get("issueFlags"), "EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT"));
        List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
        if (!matchedViews.isEmpty()) {
            payload.put("downstreamEvidenceViews", normalizeDownstreamEvidenceViews(matchedViews));
        }
        payloads.add(payload);
    }
    return payloads;
}
```

合并规则：

- 同一竞品同时存在 `draft` 和 repository snapshot 时，`draft` 的非空字段优先。
- repository snapshot 只能补 `draft` 中为空的字段，不允许覆盖 `draft` 已经给出的 `summary / positioning / targetUsers / coreFeatures / pricing / strengths / weaknesses / sourceUrls / evidenceCoverage`。
- `matchedViews` 逻辑保留，但数据来源从“只给 repository payload 附加 views”改成“先给 draft payload 附加 views；只有无 draft 的 fallback payload 再附加 views”。
- 冲突时不自动合并为一个字段值；在 payload 中追加 `inputConflicts`，记录字段名、`draftValue`、`snapshotValue` 和优先级 `EXTRACT_RESULT_DRAFT_WINS`，交给 analyzer Prompt 明确处理。
- `normalizeAnalysisResult()` 中的 `sourceUrls / issueFlags / evidenceFragments / sectionEvidenceBundles` 回填逻辑保留，但必须同时从 draft 和 matchedViews 收集，不能只从 repository snapshot 收集。

### P1 Task D: shared output 和 `DownstreamEvidenceView` 轻量化

跨节点输出不再携带完整正文：

- 允许：`evidenceId / title / sourceType / sourceUrls / qualitySignals / issueFlags / structuredBlock 摘要`
- 禁止：完整 `content`
- 如下游需要正文，通过 `evidenceId` 回查正式证据存储

退出条件：`extract_schema` 节点输出、`TaskNode.outputData`、shared output envelope 中不再出现长正文。

## P2 计划草案

P2 在 P1 通过后启动，目标是把 extractor/analyzer 成功后的失败继续拆到下游链路。

### P2 Task A: workflow 失败分层汇总

新增或扩展跨节点诊断，让最终任务能区分：

- `NO_USABLE_EVIDENCE`
- `MODEL_OUTPUT_INVALID_JSON`
- `NO_BUSINESS_FIELDS_EXTRACTED`
- `FIELD_MISSING_EVIDENCE`
- `LOW_QUALITY_EVIDENCE`
- `DOWNSTREAM_CONSUMPTION_GAP`

`DOWNSTREAM_CONSUMPTION_GAP` 只能由 workflow 汇总 analyzer、writer、reviewer、delivery 后判定，不写进 extractor 内部判断。

当前状态补充（2026-06-22）：

- 已完成第一版 workflow 汇总：
  - 终审节点执行成功但 `passed=false` 时，`DagExecutor` 会把 reviewer 节点标记为 `DOWNSTREAM_CONSUMPTION_GAP`；
  - 初审节点执行成功、明确 `requiresHumanIntervention=true` 且任务因此停在 reviewer 人工补证据阶段时，也会归口为 `DOWNSTREAM_CONSUMPTION_GAP`。
- 第二轮已继续扩展：
  - analyzer 节点在 `extract_schema` 成功后发生消费失败时，也会被 workflow 归口为 `DOWNSTREAM_CONSUMPTION_GAP`；
  - writer 节点在 `extract_schema + analyze_competitors` 成功后发生消费失败时，也会被 workflow 归口为 `DOWNSTREAM_CONSUMPTION_GAP`。
- 仍未完全完成：
  - 这还不是所有 analyzer / writer / reviewer / delivery 失败形态的统一汇总；
  - 当前仍未覆盖 delivery 和更广义 reviewer 失败场景，后续仍需继续泛化。

### P2 Task B: `evidenceCoverage` 状态细化

在保留现有 `EMPTY / MISSING_EVIDENCE / TRACEABLE` 的基础上，扩展：

- `LLM_REFUSED`
- `EVIDENCE_NOT_COVERING`
- `STRUCTURED_BLOCK_DIRECT`

退出条件：reviewer 能区分字段为空、模型拒答、证据不覆盖、结构块直出的不同原因。

当前状态补充（2026-06-22 第二轮后）：

- `evidenceCoverage` 细状态已经在 extractor / reviewer / report 侧落地；
- `ReportService` 已在 overview / section / competitor 三层响应中补充 `statusBreakdown`；
- 粗粒度 `TRACEABLE / MISSING_EVIDENCE / EMPTY` 统计继续保留，用于兼容既有报告概览。

### P2 Task C: task `50` 类真实链路验收

验收前置检查：

1. 确认端点存在：`TaskController` 当前包含 `@PostMapping("/{id}/nodes/{nodeName}/rerun")`，对应 `POST /api/task/{id}/nodes/{nodeName}/rerun`。
2. 确认本地 dev 服务已启动，且 `GET /api/task/50` 返回 200。
3. 记录重跑前现场，避免把历史输出误判为本轮结果：

```powershell
curl.exe http://localhost:9093/api/task/50 > before-task-50.json
curl.exe http://localhost:9093/api/task/50/nodes > before-task-50-nodes.json
curl.exe http://localhost:9093/api/task/50/replay > before-task-50-replay.json
```

4. 不默认重置 task `50`。优先使用节点级 rerun，因为它会从指定节点起重置下游节点并保留上游采集现场；只有当 `GET /api/task/50/nodes` 显示 `extract_schema` 不存在、任务计划版本缺失，或 rerun 返回“无受影响下游节点”时，才改用重新创建任务或全任务 retry，并在验证记录里说明原因。

执行命令：

```powershell
curl.exe -X POST http://localhost:9093/api/task/50/nodes/extract_schema/rerun
curl.exe http://localhost:9093/api/task/50/nodes
curl.exe http://localhost:9093/api/task/50
curl.exe http://localhost:9093/api/report/50
curl.exe http://localhost:9093/api/report/50/evidences
```

判定字段：

- 在 `GET /api/task/50/nodes` 中定位 `nodeName=extract_schema`：
  - `status=FAILED` 且 `errorMessage` 包含 `未能抽取出任何业务字段`，归类为 `NO_BUSINESS_FIELDS_EXTRACTED`。
  - `status=FAILED` 且 `errorMessage` 包含 `暂无可用于抽取的证据来源`，归类为 `NO_USABLE_EVIDENCE`。
  - `status=FAILED` 且 `errorMessage` 包含 `JSON`、`解析`、`模型未返回`，归类为 `MODEL_OUTPUT_INVALID_JSON`。
  - `status=SUCCESS` 时，继续检查 `outputData.issueFlags`、`outputData.drafts[].fieldsExtracted`、`outputData.sourceUrls`、`outputData.evidenceFragments` 和 `outputData.sectionEvidenceBundles`。
- 在 `GET /api/task/50` 中检查 `status / statusSummary / errorMessage / currentStage`，确认任务停点是否已经越过 extractor。
- 在 `GET /api/report/50/evidences` 中检查 `sourceUrls / evidenceFragments / sectionEvidenceBundles / issueFlags` 是否仍可追溯。

预期输出形态示例：

```json
{
  "nodeName": "extract_schema",
  "status": "FAILED",
  "errorMessage": "未能抽取出任何业务字段",
  "outputData": "{\"issueFlags\":[\"NO_BUSINESS_FIELDS_EXTRACTED\"],\"results\":[...]}"
}
```

或：

```json
{
  "nodeName": "extract_schema",
  "status": "SUCCESS",
  "outputData": "{\"drafts\":[{\"competitorName\":\"...\",\"fieldsExtracted\":3}],\"sourceUrls\":[\"...\"],\"issueFlags\":[]}"
}
```

退出条件：

- extractor 失败时能明确失败层。
- extractor / analyzer 成功但质量门禁失败时，失败归入 writer / reviewer / delivery。
- report evidences 继续展示 `sourceUrls / evidenceFragments / sectionEvidenceBundles / issueFlags`。

当前状态补充（2026-06-22）：

- 该任务已完成真实链路验收，详见 `docs/superpowers/ExtractionStructured/progress/2026-06-22-task-50-extract-schema-rerun-success.md`。
- 本轮已确认 task `50` 在 `extract_schema` 节点级 rerun 后整条链路执行通过：
  - task 总状态由 `FAILED` 变为 `SUCCESS`
  - `/api/report/50` 中 `qualityPassed=true`
  - `qualityScore=91`
- 结论：Task C 不再是“未执行”，当前剩余的是补更多 coverage 状态的 live 命中样本，而不是补 task `50` 的基本验收。
- 第二轮 live 补样本现状：
  - 已写出 `docs/superpowers/ExtractionStructured/progress/2026-06-22-coverage-live-sample-plan.md` 作为补证计划；
  - 已完成 `docs/superpowers/ExtractionStructured/progress/live-coverage-samples-20260622-151324/` 本轮 live 样本；
  - 本次 task `50` 从 `extract_schema` rerun 后命中 `DOWNSTREAM_CONSUMPTION_GAP`，最终 task 状态为 `FAILED`，错误信息为 `质量闭环未达到通过条件，请检查评审结果`；
  - 本次 report `statusBreakdown` 为 `TRACEABLE / EMPTY`，尚未命中 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。

## 当前不做

1. 不回头扩大搜索发现、候选排序、采集路由、RSS owner、Playwright 并发池。
2. 不把 `SchemaService` 拉入 extractor 热路径，它负责 `AnalysisSchema` 模板 CRUD，不是结构化提取执行主链路。
3. 不一次性重构完整 `SchemaExtractorAgent`，P0 只做当前主停点所需的最小修改。
4. 不一次性把 `CompetitorKnowledge` 改成全新领域模型，当前先守住 `TASK` 快照和后续治理 writeback 分界。
5. 不先改前端或报告 DTO 展示更多字段，运行态事实源未收口前不扩大展示面。
6. 不把 task `50` 的最终质量门禁失败直接归因到搜索采集，必须先按 extractor、analyzer、writer/reviewer/delivery 三层定位。

## 风险与回滚

### P0 语义重试调用次数上升

风险：当首轮合法 JSON 但 7 个业务字段全空，且正文长度不低于 40 字符时，会额外调用一次 LLM。最坏情况下，单竞品调用次数从原来的最多 `EXTRACT_JSON_MAX_ATTEMPTS` 次，变成首轮解析重试最多 `EXTRACT_JSON_MAX_ATTEMPTS` 次，加一次业务补抽的解析重试最多 `EXTRACT_JSON_MAX_ATTEMPTS` 次。

控制标准：

- 只有合法 JSON 且 0 业务字段才触发业务补抽。
- 正文低于 40 字符不触发业务补抽。
- 结构块型证据无正文时不触发正文语义重试。
- 自动化测试必须覆盖 `times(2)` 的正常语义重试路径，避免每个 JSON retry 都叠加业务 retry。

回滚策略：

- 如果 live 任务出现明显 token 成本翻倍且成功率没有提升，先将业务语义重试降级为配置开关，例如 `extractor.semantic-business-retry.enabled=false`。
- 回滚时保留 Prompt 分层、字段级指引、结构块入口和 Schema 注入；不要把整个 P0 改动一起撤回。

### P0 Prompt 分层导致模型输出漂移

风险：模板变长后，模型可能改变字段名或遗漏历史字段。

控制标准：

- `normalizeSchema()` 继续负责字段归一和 `sourceUrls / evidenceCoverage / issueFlags` 回填。
- Prompt 必须保留“只返回 JSON”和固定 7 字段要求。
- `SchemaExtractorAgentTest` 全量通过，尤其是字段漂移、sourceUrls 回填和 evidenceCoverage 相关既有用例。

回滚策略：

- 如果输出漂移增加，先回滚 `PromptTemplateService` 中的模板文案强度，保留 Java 侧分层变量。
- 不回滚 `isUsableEvidence()` 的结构块入口修复，因为它是输入契约对齐，不是 Prompt 风险来源。

### P1 analyzer 优先级调整导致分析质量倒退

风险：repository snapshot 中可能有历史上更完整的字段，改为 draft 优先后，analyzer 使用了更新但更薄的运行态现场。

控制标准：

- 同竞品冲突时 draft 非空字段优先，snapshot 只补空字段。
- payload 中记录 `inputPriority` 和 `inputConflicts`。
- fallback 到 snapshot 时追加 `EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT`。

回滚策略：

- 若 P1 live 验证显示 draft 优先导致报告质量明显倒退，临时把 analyzer 优先级切回“snapshot 主、draft 作为 views 附加”，但必须保留 `inputPriority` 诊断字段，避免事实源再次变成黑盒。
- 回滚不影响 P0，因为 P0 不修改 analyzer 正式优先级。

## 验证命令

计划文档验证：

```powershell
git diff --check -- docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md
```

P0 自动化验证：

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test
mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest" test
mvn -pl backend test
```

P2 live 验证入口：

```powershell
curl.exe -X POST http://localhost:9093/api/task/50/nodes/extract_schema/rerun
curl.exe http://localhost:9093/api/task/50
curl.exe http://localhost:9093/api/report/50
curl.exe http://localhost:9093/api/report/50/evidences
```

## Self-Review

1. Spec coverage: 本计划覆盖诊断文档中的 P0/P1/P2 三层 blocking；P0 有可执行测试和代码修改步骤，P1/P2 明确作为后续计划入口。
2. P0 coverage check: Prompt 分层、字段指引、0 字段重试、结构块证据入口、Schema 驱动、qualitySignals 翻译和输出强约束均已映射到具体 Task。
3. Prompt location check: 已明确 extractor 默认模板位于 `PromptTemplateService`，不是当前 `resources/prompts` 下的独立文件。
4. Retry consistency check: 已说明 JSON 解析重试与业务语义重试的两层关系，Task 1 的 `times(2)` 与 Task 3 实现路径一致。
5. Analyzer detail check: P1 的 analyzer 优先级调整已有具体合并算法、冲突规则和 matchedViews 保留策略，不再只有注释级伪代码。
6. Live validation check: P2 task `50` 验证已包含端点确认、前置快照、节点字段判定、失败层分类和示例输出。
7. Risk check: 已补 LLM 调用次数、Prompt 漂移和 analyzer 优先级倒退的控制标准与回滚策略。
8. Boundary check: 本计划没有把搜索与采集继续写成 3.3 前置条件，也没有把 writer / reviewer / delivery 的质量问题提前混进 P0。
9. Existing coverage check: 已存在的结构化输入、0 字段阻断和 analyzer 携带证据视图测试没有重复写成同名任务，新任务只补当前缺口。
10. Type consistency: P0 使用当前真实类名 `SchemaExtractorAgent`、`PromptTemplateService`、`AgentContext`、`DownstreamEvidenceView`、`EvidenceSource`、`CompetitorKnowledge`。
11. Placeholder scan: 已检查计划失败词，正文没有留下占位任务或空实现说明。

## Execution Handoff

计划已保存到 `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`。

推荐执行顺序：

1. 先执行 P0 `Task 1 -> Task 5`，把 extractor 主停点坐实。
2. P0 自动化通过后执行 `Task 6`，回写诊断、优化汇总和总蓝图状态。
3. P0 完成后再单独展开 P1 计划，不把 Provider、analyzer 优先级和 shared output 轻量化塞回 P0。
