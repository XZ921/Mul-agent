# 提取结构化链路诊断

> 2026-06-21 重构版。本文是总蓝图中 `提取结构化` 业务链路的正式诊断基线，负责回答“当前问题在哪里、哪些已经收口、哪些仍是 blocking、下一步验证口径是什么”。架构方案、实施计划和 3.2 继承前提分别由独立文档承接，本文不替代它们。
>
> 生命周期说明：第 4 节代码引用基于第八轮第一阶段后的工程快照，仅作为诊断证据锚点；完成 P0 或 P1 任一阶段后，必须刷新这些代码引用，避免诊断文档继续指向已被改造的旧实现。

---

## 1. 文档定位

按照 [AI 竞品分析 Agent 协作系统业务全景与功能优化路线图设计](../../../specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md)，每条主业务链路都必须按：

`诊断 -> 方案 -> 实施 -> 实链验证`

推进。本文对应其中第三条主业务链路：`提取结构化`。

本文只做诊断基线，不直接展开完整架构设计或实施步骤：

- `3.2 继承前提` 见 [2026-06-20-extraction-structured-3.2-inheritance-baseline.md](./2026-06-20-extraction-structured-3.2-inheritance-baseline.md)。
- `3.3 架构规格` 见 [2026-06-21-extraction-structured-architecture-spec.md](../specs/2026-06-21-extraction-structured-architecture-spec.md)。
- `3.3 实施计划` 见 [2026-06-20-extraction-structured-optimization-plan.md](../plan/2026-06-20-extraction-structured-optimization-plan.md)。
- `3.3 优化点汇总` 见 [2026-06-21-extraction-structured-optimization-summary.md](../summary/2026-06-21-extraction-structured-optimization-summary.md)。

本文的当前结论会回链到总蓝图中的 `提取结构化` 状态看板，但不会把方案文档里的 P0/P1/P2 任务复制成第二份计划。

---

## 2. 先给结论

1. `ExtractionStructured.md` 必须继续存在。它是总蓝图要求的“提取结构化链路诊断文档”，不是 3.3 架构规格的重复文件，也不是实施计划的替代品。
2. 3.2 搜索与采集已经足以支撑 3.3 启动。当前不应再把真实任务未通过质量门禁笼统归因为“搜索与采集还没做好”。
3. 第八轮已经收口两类历史高风险问题：
   - extractor 写入 `CompetitorKnowledge` 时已经显式落到 `TASK` 快照边界，不再默认伪装成 `DOMAIN` 长期记忆；
   - `ExtractResult / CompetitorKnowledgeDraft / DownstreamEvidenceView / EvidenceFragment / SectionEvidenceBundle` 已开始共同承接运行期输出和下游追溯。
4. 当前主停点仍在 3.3 内部，分成三层：
   - 第一层：extractor 在 `structuredBlocks` 不足但正文可读时，是否能稳定抽出非空业务字段；
   - 第二层：analyzer 是否真正优先消费 `extract_schema` 的运行期输出，而不是继续把 repository 快照当主事实源；
   - 第三层：writer / reviewer / delivery 是否仍在 `structuredBlocks / evidenceCoverage / unsupported_claim / actionability` 上不过关。
5. 现有工程已经有较多契约测试，但仍缺少“真实采集 -> 真提取 -> 真分析 -> 真报告”的完整实链验收。自动化测试只能证明实施可复核，不能替代实链验证。

---

## 3. 继承前提

本诊断直接继承 [3.2 继承前提基线](./2026-06-20-extraction-structured-3.2-inheritance-baseline.md) 中已经冻结的输入契约，不在本文重复维护完整表格。

当前只保留三条诊断侧必须反复校验的约束：`sourceUrls` 是无幻觉追溯红线，`qualitySignals / structuredBlocks / evidenceCoverage` 必须继续传递并参与失败诊断，extractor 产物默认属于 `TASK` 现场快照而非 `DOMAIN` 长期记忆。

因此 3.3 的问题不是“等搜索与采集再完美一点”，而是在 3.2 已经提供可追溯输入的前提下，让结构化提取稳定、可诊断、可恢复。

---

## 4. 当前主路径复盘

当前提取结构化主路径仍然是：

```text
CollectorAgent
  -> EvidenceSourceRepository
  -> SchemaExtractorAgent
  -> ExtractResult / CompetitorKnowledgeDraft / DownstreamEvidenceView
  -> CompetitorKnowledge TASK snapshot
  -> CompetitorAnalysisAgent
  -> ReportWriterAgent / QualityReviewAgent / ReportService
```

与旧诊断相比，当前工程已经不是完全的“两套世界失控”状态。第八轮之后，运行期输出和任务快照都存在，但它们的职责正在收口，尚未完全闭合。

### 4.1 当前已经收口的事实

1. `ExecutionPlanDefinitionBuilder` 已经把 `dimensions / schemaId` 写入 `extract_schema.nodeConfig`。

```java
.nodeConfig(toJson(orderedMap(
        "dimensions", dimensions,
        "schemaId", task.getSchemaId()
)))
```

2. `SchemaExtractorAgent` 已经在 `ExtractResult` 中输出 `drafts / sourceUrls / issueFlags / evidenceFragments / sectionEvidenceBundles / downstreamEvidenceViews`。

```java
ExtractResult extractResult = ExtractResult.builder()
        .totalCompetitors(successCount)
        .drafts(drafts)
        .sourceUrls(new ArrayList<>(aggregatedSourceUrls))
        .issueFlags(new ArrayList<>(aggregatedIssueFlags))
        .evidenceFragments(normalizeEvidenceFragments(aggregatedFragments))
        .sectionEvidenceBundles(normalizeSectionEvidenceBundles(aggregatedSectionBundles))
        .downstreamEvidenceViews(normalizeDownstreamEvidenceViews(aggregatedEvidenceViews))
        .build();
```

3. extractor 写入 `CompetitorKnowledge` 时已经显式设置任务快照边界。

```java
return CompetitorKnowledge.builder()
        .taskId(context.getTaskId())
        .competitorName(competitorName)
        .memoryLayer("TASK")
        .snapshotScope("TASK")
        .producerNodeName(firstNonBlank(context.getCurrentNodeName(), "extract_schema"))
        .planVersionId(context.getPlanVersionId())
        .branchKey(context.getBranchKey())
        .versionSource(buildTaskExtractVersionSource(context))
        .invalidationScope("TASK_RERUN")
        .invalidationReason("PLAN_VERSION_CHANGED")
        .build();
```

4. `CompetitorAnalysisAgent` 已经开始读取 `extract_schema` 节点输出中的 `DownstreamEvidenceView`。

```java
List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId());
List<DownstreamEvidenceView> downstreamEvidenceViews =
        readDownstreamEvidenceViews(context.getSharedOutput("extract_schema"));
```

5. extractor 已有 `0` 业务字段阻断保护。`sourceUrls` 回填不再被视为业务字段抽取成功。

```java
int extractedFieldCount = countExtractedFields(normalizedSchema.schema());
if (extractedFieldCount == 0) {
    zeroBusinessFieldFailures++;
    issueFlags.add(ZERO_BUSINESS_FIELDS_ISSUE_FLAG);
    continue;
}
```

这些变化说明：旧诊断中的“任务快照自动污染 DOMAIN 记忆”和“空业务字段继续流向下游”已经被第一阶段治理掉，不能在新诊断里继续当作未处理现状描述。

### 4.2 当前仍未闭合的事实

1. `SchemaExtractorAgent` 仍直接读取 `EvidenceSourceRepository`，尚未通过正式 `ExtractorInputProvider` 接收输入包。

```java
List<EvidenceSource> allEvidences =
        evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
List<EvidenceSource> evidences = allEvidences.stream()
        .filter(this::isUsableEvidence)
        .toList();
```

2. `isUsableEvidence()` 仍只看 `fullContent / contentSnippet`，没有把 `structuredBlocks / structuredPayload` 非空作为可消费证据条件。

```java
private boolean isUsableEvidence(EvidenceSource evidence) {
    if (evidence == null) {
        return false;
    }
    boolean hasContent = evidence.getFullContent() != null && !evidence.getFullContent().isBlank();
    boolean hasSnippet = evidence.getContentSnippet() != null && !evidence.getContentSnippet().isBlank();
    return hasContent || hasSnippet;
}
```

这意味着：采集层允许入库的“正文为空但结构块非空”证据，仍可能在 extractor 入口被静默过滤。

3. `extractAndNormalize()` 当前只对 JSON 解析失败做最多 3 次重试；当 JSON 合法但 7 个业务字段全空时，主流程会阻断下游，但还没有执行“正文可读时的业务语义重试”。

```java
for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
    String attemptPrompt = attempt == 1
            ? prompt
            : prompt + "\n\n【补充要求】上一次返回的 JSON 解析失败，请重新输出一个完整、闭合、合法的 JSON 对象，不要附加解释。";
    String llmResponse = llmClient.chatForJson(
            "你是一名竞品知识抽取专家，请只返回 JSON。",
            attemptPrompt,
            "ExtractedSchema"
    );
    ...
}
```

4. Prompt 输入仍把 `qualitySignals / issueFlags / structuredBlocks / content` 拼在同一段 `collectedContent` 中，尚未形成清晰的“结构化证据、质量信号、正文兜底”三段输入。

```java
collectedContent.append("qualitySignals: ").append(evidence.getQualitySignals()).append('\n');
collectedContent.append("issueFlags: ").append(evidence.getIssueFlags()).append('\n');
if (evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
    collectedContent.append("structuredBlocks: ").append(evidence.getStructuredBlocks()).append('\n');
}
collectedContent.append(content == null ? "" : content).append("\n\n");
```

5. analyzer 虽然会读取 `DownstreamEvidenceView`，但当 repository 中存在 `CompetitorKnowledge` 时，`buildPromptPayloads()` 仍以 repository 快照为主，再把 matched views 附加进去；它还没有把 `ExtractResult.drafts` 作为运行期正式输入优先级。

```java
if (knowledges != null && !knowledges.isEmpty()) {
    for (CompetitorKnowledge knowledge : knowledges) {
        Map<String, Object> payload = toPromptPayload(knowledge);
        List<DownstreamEvidenceView> matchedViews = downstreamEvidenceViewsByCompetitor(
                downstreamEvidenceViews,
                knowledge.getCompetitorName());
        if (!matchedViews.isEmpty()) {
            payload.put("downstreamEvidenceViews", matchedViews);
        }
        payloads.add(payload);
    }
    return payloads;
}
```

6. `DownstreamEvidenceView.content` 仍可能携带完整正文，并随 `extract_schema` 输出进入 `TaskNode.outputData / sharedOutput / replay` 路径，轻量化边界尚未完成。

```java
return this.toBuilder()
        .content(content == null ? "" : content)
        .sourceUrls(new ArrayList<>(normalizedSourceUrls))
        .issueFlags(new ArrayList<>(normalizedIssueFlags))
        .qualitySignals(new ArrayList<>(normalizedQualitySignals))
        .structuredBlocks(normalizedBlocks)
        .build();
```

7. `schemaId / dimensions` 已经进入节点配置，但 extractor 当前主流程没有读取 `currentNodeConfig` 并注入 Prompt；Schema 仍未真正驱动提取行为。

---

## 5. Blocking 项归类

### 5.1 P0：extractor 主停点

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| 结构块不足但正文可读时，仍可能抽不出非空业务字段 | 当前只有 `0` 字段阻断，没有业务语义重试 | 会让任务停在 extractor，但无法区分是 Prompt 策略问题、正文 fallback 问题，还是输入证据质量问题。 |
| Prompt 输入未显式分层 | `buildCollectedContent()` 混合拼接 qualitySignals、structuredBlocks 和 content | 模型无法稳定理解结构块优先、正文兜底、质量信号作为提取指引的关系。 |
| 结构块型证据可能被入口过滤 | `isUsableEvidence()` 只看正文和摘要 | API / RSS / pricing block 等结构化证据可能已经被采集，但无法进入 extractor。 |
| Schema 未驱动 extractor | `ExecutionPlanDefinitionBuilder` 写入 nodeConfig，extractor 未消费 | 用户选择的分析维度无法改变提取优先级，任务 Schema 仍只是计划展示字段。 |

P0 的验收目标不是“报告最终通过”，而是先证明 extractor 对已有证据具备稳定抽取非空业务字段的能力，并能解释失败原因。

### 5.2 P1：运行时边界收口

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| `ExtractorInputProvider` 不存在 | extractor 仍直接读 `EvidenceSourceRepository` | DAG 节点输入不透明，rerun / resume / replay 难以解释本次到底消费了哪些证据。 |
| analyzer 优先级仍不彻底 | 有 repository 快照时，payload 仍先由 `CompetitorKnowledge` 构建 | `ExtractResult / drafts` 的契约演进不能稳定成为分析阶段事实来源。 |
| `DownstreamEvidenceView` 仍携带正文 | `content` 字段可能进入节点输出 | 长正文可能扩散到 sharedOutput、任务快照和 replay，增加内存、存储和反序列化压力。 |
| `CompetitorKnowledge` 实体默认仍是 `DOMAIN` | 实体默认值仍会在未显式设置时补成 `DOMAIN` | extractor 已显式设置 `TASK`，但其它写入路径仍必须被治理，避免历史默认值重新污染长期记忆。 |

P1 的验收目标是让运行期正式边界稳定：输入有 Provider，输出以 `ExtractResult / drafts / lightweightEvidenceViews` 为优先，`CompetitorKnowledge TASK` 只作为快照和恢复 fallback。

### 5.3 P2：下游消费与质量门禁

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| writer / reviewer / delivery 可能继续失败 | task `43/50` 类任务已显示 `unsupported_claim / missing_evidence / actionability` | extractor 成功不等于报告质量通过；下游失败必须与 extractor 失败分层。 |
| `evidenceCoverage` 状态仍偏粗 | 当前主要是 `EMPTY / MISSING_EVIDENCE / TRACEABLE` | reviewer 无法充分区分模型拒答、证据不覆盖、结构块直出等更细粒度原因。 |
| 真实链路验收不足 | 现有自动化测试多守局部契约 | 仍需真实采集、真实提取、真实分析、真实报告的端到端验证。 |

P2 不应反向阻塞 P0/P1。只有当 extractor / analyzer 边界已经坐实后，writer / reviewer / delivery 的问题才应升级为下一条链路的正式诊断输入。

### 5.4 里程碑预期

本节只给执行节奏锚点，不替代实施计划中的任务拆分。

| 层级 | 时间锚点 | 预期结果 | 进入下一层的条件 |
| --- | --- | --- | --- |
| P0 extractor 主停点 | 本迭代 | Prompt 分层、正文 fallback、0 字段语义重试、结构块型证据入口和 Schema 注入完成自动化验收 | extractor 能解释“为什么没有抽出业务字段”，且不会把可读证据或结构块证据静默丢掉 |
| P1 运行时边界收口 | 下一迭代 | `ExtractorInputProvider / ExtractorInputPackage` 初版成型，analyzer 明确运行期输出优先，`DownstreamEvidenceView` 下游轻量化 | rerun / resume / replay 能解释 extractor 实际消费输入和 analyzer 实际消费边界 |
| P2 下游消费与质量门禁 | P1 通过后的后续专项 | 将 writer / reviewer / delivery 的 `unsupported_claim / actionability / evidenceCoverage` 问题拆成独立链路诊断或质量回流任务 | task `50` 类 live 链路能明确判定失败已经不在 extractor / analyzer 边界 |

如果 P0 未通过，不应提前把问题升级到 P1/P2；如果 P1 已通过但质量门禁仍失败，应把失败归入下游消费或质量回流专题，而不是回滚 3.2 或重新扩大 extractor 范围。

---

## 6. 当前不应写成方案的内容

1. 不要把“继续扩搜索与采集能力”写成 3.3 前置条件。搜索与采集仍有后续专项，但不再是当前主停点。
2. 不要把 `SchemaService` 纳入 `extract_schema` 热路径主改造范围。它处理的是 `AnalysisSchema` 模板 CRUD，不是结构化提取执行链路。
3. 不要把 `EvidenceFragment / SectionEvidenceBundle` 改成 extractor 私有对象。它们是跨链路共享追溯契约。
4. 不要先改前端或报告 DTO 来“展示更多字段”。正式边界未收口前，展示层扩字段只会扩散两套事实源。
5. 不要把 `CompetitorKnowledge` 一次性重构成全新领域模型。本阶段先守住 `TASK` 快照与治理后 `DOMAIN` writeback 的分界。
6. 不要把 task `50` 的质量门禁失败直接写成搜索采集失败。它必须先按 extractor、analyzer、writer/reviewer/delivery 三层定位。

---

## 7. 后续方案必须回答的问题

后续架构与实施必须围绕下列问题闭环：

1. `extract_schema` 的正式输入边界是什么：直接 DB、上游节点输出，还是 `ExtractorInputPackage`。
2. `structuredBlocks` 与 readable content 如何同时参与提取，且不会互相覆盖。
3. 0 业务字段时，什么时候重试、什么时候失败、什么时候人工介入。
4. `ExtractResult / drafts / lightweightEvidenceViews` 如何成为 analyzer 的正式优先输入。
5. `CompetitorKnowledge TASK` 快照何时作为恢复 fallback，何时禁止被当成领域记忆。
6. `DownstreamEvidenceView` 如何拆成“extractor 内部可携带正文的输入视图”和“下游轻量证据视图”。
7. `schemaId / dimensions` 如何从 `extract_schema` 开始真正驱动提取字段、字段优先级和 Prompt 指引。
8. 实链验证如何定义，才能覆盖真实采集、真实提取、真实分析、真实报告，而不是继续只测 mock 世界。

---

## 8. 验收口径

### 8.1 自动化验收

自动化测试通过只能证明 `实施` 可复核，不等于 `实链验证` 完成。当前至少需要覆盖：

1. extractor 在 `structuredBlocks=[]` 但正文可读时，会触发 0 字段语义重试。
2. extractor 不会过滤“正文为空但 `structuredBlocks / structuredPayload` 非空”的结构块型证据。
3. `structuredBlocks` 存在但字段覆盖不足时，extractor 仍会消费 readable content 做 fallback 提取。
4. `sourceUrls` 回填不会让 0 业务字段被判为成功。
5. analyzer 能优先消费 `extract_schema` 输出中的 `drafts / lightweightEvidenceViews`。
6. `TaskNode.outputData / sharedOutput` 不再携带完整正文。
7. `schemaId / dimensions` 能进入 extractor Prompt，并影响提取优先级。

### 8.2 live 验收

以 task `50` 类真实任务验证：

1. 如果 extractor 失败，必须能明确区分：
   - 无可用证据；
   - 结构块不足；
   - 正文过薄；
   - JSON 解析失败；
   - 0 业务字段；
   - 字段有值但缺证据。
2. 如果 extractor / analyzer 成功但质量门禁失败，失败应归为 writer / reviewer / delivery 的下游消费问题，而不是重新归咎于搜索采集。
3. `/api/report/{taskId}`、`/api/report/{taskId}/evidences`、`/api/task/{taskId}/replay` 必须继续展示 `sourceUrls / evidenceFragments / sectionEvidenceBundles / issueFlags`。
4. rerun / resume 后，提取现场与 analyzer 消费现场必须可解释，不能出现节点输出成功但分析实际消费另一套事实源的情况。

---

## 9. 当前状态回链

在总蓝图中，`提取结构化` 当前应保持：

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 诊断 | `✅` | 本文作为正式诊断基线存在，并已按当前工程重构。 |
| 方案 | `✅` | 已有 [3.3 架构规格](../specs/2026-06-21-extraction-structured-architecture-spec.md) 与 [实施计划](../plan/2026-06-20-extraction-structured-optimization-plan.md)。 |
| 实施 | `🟡` | 第八轮第一阶段已落地 `TASK` 快照、共享追溯契约和 0 字段阻断；P0/P1 仍未完全收口。 |
| 实链验证 | `⬜` | 尚未完成真实采集 -> 真提取 -> 真分析 -> 真报告的闭环验收。 |

当前执行入口应是：

1. 先坐实 extractor 主停点：Prompt 分层、正文 fallback、0 字段语义重试、结构块型证据入口。
2. 再收口 analyzer 优先级：`ExtractResult / drafts / lightweightEvidenceViews` 优先，`CompetitorKnowledge TASK` 只做 fallback。
3. 最后用 task `50` 类 live 链路判断问题是否已经转移到 writer / reviewer / delivery。

---

## 10. 相关文档

- [总蓝图：AI 竞品分析 Agent 协作系统业务全景与功能优化路线图设计](../../../specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md)
- [3.2 继承前提基线](./2026-06-20-extraction-structured-3.2-inheritance-baseline.md)
- [3.3 提取结构化架构规格](../specs/2026-06-21-extraction-structured-architecture-spec.md)
- [3.3 优化实施计划](../plan/2026-06-20-extraction-structured-optimization-plan.md)
- [3.3 优化点汇总](../summary/2026-06-21-extraction-structured-optimization-summary.md)
- [3.2 搜索与采集阶段总结](../../search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md)
