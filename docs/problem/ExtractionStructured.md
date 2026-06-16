# 提取结构化链路诊断

## 1. 先给结论

1. `extract_schema` 当前不是一个单纯的“结构化提取子域”，而是 `证据读取 -> LLM 抽取 -> JSON 形态修复 -> sourceUrls / evidenceCoverage 补丁 -> CompetitorKnowledge 落库 -> 下游共享契约拼装` 的混合节点。
2. 当前链路真正被下游消费的正式边界不是 `ExtractResult`，而是 `CompetitorKnowledgeRepository`。`SchemaExtractorAgent` 写出的运行时节点输出与 `CompetitorKnowledge` 落库快照并存，`CompetitorAnalysisAgent` 只认后者。
3. `CompetitorKnowledge` 同时承担“当前任务抽取结果”和“跨任务领域记忆”两种语义；`SchemaExtractorAgent` 落库时未显式设置记忆边界，实体默认值会把记录补成 `DOMAIN + UNSPECIFIED`。这会让任务现场产物自动伪装成可跨任务复用的领域知识。
4. `EvidenceFragment` / `SectionEvidenceBundle` 不是提取链路私有对象，而是采集、分析、写作、交付共同消费的共享追溯契约。后续方案如果把它们误判成“提取内部实现”，会直接写偏边界。
5. 当前已有较多单元测试，但提取结构化链路还没有完成“真实采集 -> 真提取 -> 真分析 -> 真报告”的实链验收；现有阶段性集成测试主要通过 mock agent 代填结果。

---

## 2. 诊断范围

### 2.1 纳入本次诊断的主路径

`CollectorAgent -> EvidenceSourceRepository -> SchemaExtractorAgent -> CompetitorKnowledgeRepository -> CompetitorAnalysisAgent -> ReportWriterAgent / ReportService`

本次重点观察以下对象：

- `SchemaExtractorAgent`
- `CompetitorKnowledge`
- `ExtractResult`
- `CompetitorKnowledgeDraft`
- `EvidenceFragment`
- `SectionEvidenceBundle`
- `CompetitorAnalysisAgent`
- `ReportService` / `EvidenceQueryService` 的下游投影

### 2.2 明确不属于本次主路径的问题

`SchemaService` 虽然名字里带 `Schema`，但它处理的是 `AnalysisSchema` 模板 CRUD，不在 `extract_schema` 主链路热路径里。

```java
@Service
@RequiredArgsConstructor
public class SchemaService {

    private final AnalysisSchemaRepository schemaRepository;

    public List<AnalysisSchema> listSchemas() {
        return schemaRepository.findAll();
    }

}
```

这类对象更接近“任务定义 / 分析模板配置”，不是“提取结构化执行链路”本身。

---

## 3. 主路径复盘

当前提取链路的真实执行路径如下：

1. `CollectorAgent` 先把采集结果落到 `EvidenceSourceRepository`。
2. `SchemaExtractorAgent` 按 `taskId` 回库读取证据，按竞品聚合，调用 LLM 做结构抽取。
3. `SchemaExtractorAgent` 在本节点内部同时完成：
   - LLM 返回 JSON 的根节点修复与重试；
   - `sourceUrls` 回填；
   - `evidenceCoverage` 生成；
   - `EvidenceFragment` / `SectionEvidenceBundle` 拼装；
   - `CompetitorKnowledge` 落库；
   - `ExtractResult` / `CompetitorKnowledgeDraft` 节点输出组装。
4. `CompetitorAnalysisAgent` 不读取 `extract_schema` 节点输出，而是再次按 `taskId` 从 `CompetitorKnowledgeRepository` 全量读取知识快照。
5. 后续写作、质检、交付主要继续消费分析阶段和报告阶段重新投影出来的追溯对象。

这意味着当前链路并不存在一个单一、被全链路共同承认的“正式提取边界”。

---

## 4. 核心问题与代码证据

### 4.1 `ExtractResult` 存在，但不是下游正式边界

`SchemaExtractorAgent` 一边落库，一边返回节点输出：

```java
CompetitorKnowledge knowledge = buildKnowledge(context, competitorName, normalizedSchema.schema());
knowledgeRepository.save(knowledge);

ExtractResult extractResult = ExtractResult.builder()
        .totalCompetitors(successCount)
        .drafts(drafts)
        .sourceUrls(new ArrayList<>(aggregatedSourceUrls))
        .issueFlags(new ArrayList<>(aggregatedIssueFlags))
        .evidenceFragments(normalizeEvidenceFragments(aggregatedFragments))
        .sectionEvidenceBundles(normalizeSectionEvidenceBundles(aggregatedSectionBundles))
        .build();
```

但分析节点真正消费的是数据库快照，不是 `ExtractResult`：

```java
List<CompetitorKnowledge> knowledges =
        knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId());
```

这会带来三个直接后果：

1. 运行时节点输出和持久化快照形成“两套正式世界”。
2. `ExtractResult` / `CompetitorKnowledgeDraft` 的字段新增、契约修复，不会自动成为下游真实边界。
3. 重跑、恢复、审计如果只看节点输出，会和分析链路真实消费路径产生偏差。

这不是“实现细节”，而是本链路最核心的架构 blocking。

### 4.2 `CompetitorKnowledge` 任务快照与领域记忆混层，且默认自动升为 `DOMAIN`

`SchemaExtractorAgent` 落库时没有显式声明记忆层、版本来源和失效边界：

```java
return CompetitorKnowledge.builder()
        .taskId(context.getTaskId())
        .competitorName(competitorName)
        .officialUrl(schemaJson.path("officialUrl").asText(null))
        .summary(schemaJson.path("summary").asText(null))
        .positioning(schemaJson.path("positioning").asText(null))
        .targetUsers(readJsonField(schemaJson, "targetUsers", "[]"))
        .coreFeatures(readJsonField(schemaJson, "coreFeatures", "[]"))
        .pricing(readJsonField(schemaJson, "pricing", "{}"))
        .strengths(readJsonField(schemaJson, "strengths", "[]"))
        .weaknesses(readJsonField(schemaJson, "weaknesses", "[]"))
        .sources(readJsonField(schemaJson, "sources", "[]"))
        .sourceUrls(readJsonField(schemaJson, "sourceUrls", "[]"))
        .evidenceCoverage(readJsonField(schemaJson, "evidenceCoverage", "{}"))
        .extractedAt(LocalDateTime.now())
        .build();
```

但实体默认值会把这些记录补成领域记忆语义：

```java
private void applyDefaults() {
    if (this.memoryLayer == null || this.memoryLayer.isBlank()) {
        this.memoryLayer = "DOMAIN";
    }
    if (this.versionSource == null || this.versionSource.isBlank()) {
        this.versionSource = "UNSPECIFIED";
    }
    if (this.invalidationScope == null || this.invalidationScope.isBlank()) {
        this.invalidationScope = "MANUAL_REVIEW";
    }
    if (this.invalidationReason == null || this.invalidationReason.isBlank()) {
        this.invalidationReason = "NOT_EVALUATED";
    }
}
```

而记忆融合层会直接把 `memoryLayer=DOMAIN` 的 `CompetitorKnowledge` 当成跨任务可复用领域知识拉入上下文：

```java
List<CompetitorKnowledge> knowledgeList =
        competitorKnowledgeRepository.findByMemoryLayerOrderByIdAsc("DOMAIN");
```

这说明当前系统存在一个高风险语义漂移：

1. “当前任务抽取结果”会被默认伪装成“领域级可复用知识”。
2. 这些知识没有经过 reviewer / writer 的正式治理，也没有稳定的 `versionSource`。
3. 后续任务的 `taskRagContext` 可能提前吃到未治理的抽取产物，形成跨任务污染。

这不是提取链路的局部问题，它同时打穿了 `提取结构化` 和 `知识摄取 / RAG / 记忆` 两层边界。

### 4.3 `SchemaExtractorAgent` 职责明显过载

当前 `SchemaExtractorAgent` 至少同时承担了六类职责：

1. 证据读取与可用性过滤；
2. 竞品聚合；
3. Prompt 组装与 LLM 调用重试；
4. JSON 根节点修复与字段归一；
5. 字段级 / 章节级追溯模型生成；
6. 任务知识落库与节点输出契约拼装。

从 `doExecute` 的主流程就能直接看到这种混层：

```java
List<EvidenceSource> allEvidences =
        evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
List<EvidenceSource> evidences = allEvidences.stream()
        .filter(this::isUsableEvidence)
        .toList();

Map<String, List<EvidenceSource>> evidencesByCompetitor = groupByCompetitor(evidences);

NormalizedSchema normalizedSchema = extractAndNormalize(
        competitorName,
        competitorEvidence,
        context.getTaskRagPromptContext()
);
CompetitorKnowledge knowledge = buildKnowledge(context, competitorName, normalizedSchema.schema());
knowledgeRepository.save(knowledge);
```

这意味着后续如果直接写“拆类方案”，很容易把“职责拆开”误当成“边界已经收口”。当前真正缺的不是类数量，而是先定义：

- 正式输入是什么；
- 正式输出是什么；
- 持久化快照是否就是链路边界；
- 追溯契约谁拥有；
- 记忆层何时允许接管。

### 4.4 `EvidenceFragment` / `SectionEvidenceBundle` 是共享追溯契约，不是提取私有对象

这两个对象已经在采集、分析、写作、交付多处复用。

采集侧已经产出它们：

```java
output.put("evidenceFragments", collectResult.getEvidenceFragments());
output.put("sectionEvidenceBundles", collectResult.getSectionEvidenceBundles());
```

分析侧继续读写它们：

```java
List<EvidenceFragment> evidenceFragments =
        new ArrayList<>(readEvidenceFragments(analysisJson.path("evidenceFragments")));
List<SectionEvidenceBundle> sectionEvidenceBundles =
        new ArrayList<>(readSectionEvidenceBundles(analysisJson.path("sectionEvidenceBundles")));
```

交付侧也把它们当正式报告投影视图来源：

```java
List<SectionEvidenceBundle> rawBundles = extractSectionEvidenceBundles(nodes);
```

因此这里真正的问题不是“提取对象命名是否好看”，而是：

1. 共享追溯契约的 owner 还没有被正式收口；
2. 提取链路不能单方面改这些对象，否则会同时影响采集、分析、写作、交付；
3. 后续方案必须先明确“共享契约层”与“提取内部模型”的分层关系。

### 4.5 当前测试更多是在守局部契约，不是在做实链验收

当前已经存在的测试主要覆盖：

- `SchemaExtractorAgentTest`：守 JSON 修复、`sourceUrls` 回填、`evidenceCoverage` 缺口标记、`sectionEvidenceBundles` 生成。
- `CompetitorAnalysisAgentTest`：守分析阶段对字段漂移和 traceability flag 的归一。
- `ReportServiceTest` / `EvidenceQueryServiceTest`：守下游投影稳定性。

但现有阶段性 workflow integration test 并没有真正跑提取链路主体；例如 `Phase2WorkflowIntegrationTest`、`Phase4WorkflowIntegrationTest` 里的 `configureExtractorAgent()` 都是直接 mock extractor，然后手动 `save` 一份 `CompetitorKnowledge`。

这意味着：

1. 我们验证了很多“提取后的世界”，但还没有验证“真实提取这一跳”本身。
2. 一旦 `SchemaExtractorAgent`、`CompetitorKnowledge`、`taskRagContext` 之间出现真实联动问题，当前阶段性集成测试未必能第一时间暴露。

---

## 5. Blocking 项归类

| Blocking 项 | 性质 | 为什么是 blocking |
| --- | --- | --- |
| `ExtractResult` 不是分析链路正式输入边界 | 架构 blocking | 不先决定正式边界，任何“提取优化方案”都会在节点输出契约和持久化快照之间来回摇摆 |
| `CompetitorKnowledge` 同时承担任务快照与领域记忆，且默认自动升 `DOMAIN` | 数据治理 + 架构 blocking | 不先拆清，后续提取结果会继续污染记忆层，跨任务复用语义不可信 |
| `EvidenceFragment` / `SectionEvidenceBundle` 的 owner 未收口 | 跨链路 blocking | 这是共享追溯契约，不先明确 owner，提取链路方案会误伤采集、分析、写作、交付 |

---

## 6. 当前不应直接写成方案的内容

1. 不要直接把 `SchemaExtractorAgent` 的“拆类”当成方案本体。当前先要收边界，不是先切文件。
2. 不要把 `SchemaService` 继续算进这条链路的主改造范围。它属于模板配置语义，不属于 `extract_schema` 热路径。
3. 不要把 `EvidenceFragment` 当成字段级真相。它当前更像阶段级追溯摘要，而不是抽取字段的唯一事实源。
4. 不要在未明确正式边界前先改前端或报告 DTO。否则只是把“两套世界”再扩散一层。

---

## 7. 后续方案必须回答的问题

1. `extract_schema` 的正式对外边界到底是什么：`ExtractResult`、知识读取 Facade，还是其它稳定投影？
2. `CompetitorKnowledge` 是否继续同时承载任务快照与领域记忆？如果不继续，分界点在哪里？
3. `EvidenceFragment` / `SectionEvidenceBundle` 应该归属于哪一层：共享追溯契约层，还是各链路私有模型层？
4. 重跑 `extract_schema` 时，应该以什么对象作为“需要被清理、重建、回放”的正式现场？
5. 提取结构化链路的实链验证场景要怎么定义，才能覆盖真实采集、真实提取、真实分析、真实报告，而不是继续只测 mock 世界？
