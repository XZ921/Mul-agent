# 提取结构化链路诊断

> 2026-06-21 重构版。本文是总蓝图中 `提取结构化` 业务链路的正式诊断基线，负责回答“当前问题在哪里、哪些已经收口、哪些仍是 blocking、下一步验证口径是什么”。架构方案、实施计划和 3.2 继承前提分别由独立文档承接，本文不替代它们。
>
> 生命周期说明：第 4 节代码引用已在 2026-06-21 P0 收口后刷新到最新工程快照，仅作为诊断证据锚点；进入 P1 或 P2 前，仍需继续刷新这些代码引用，避免诊断文档继续指向过时实现。

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
6. 截至 2026-06-29，Task66-04 搜索采集公开证据补采已完成自动化回归与 live 复验：第一轮 `task 67` 暴露了显式官网候选虽已拿到公开正文却仍被 `CollectionTargetSelector` 拒绝的语义缺口；修复 `TavilyPrefetchedExecutor`、`QualityReviewAgent` 启动依赖与 `CollectionTargetSelector` 入选条件后，第二轮 `task 68` 已成功选中 `https://app.bilibili.com`、持久化证据并推进到报告与质检阶段。因此当前阻塞点已经从 `04` 搜索采集底座前移到下游质量与 coverage 闭环，而不是运行环境或公开证据补采底座未落地。

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

6. `SchemaExtractorAgent` 已开始直接消费 `currentNodeConfig` 中的 `schemaId / dimensions`，并把它们注入 extractor Prompt。

```java
promptVariables.put("schemaGuidance", buildSchemaGuidance(context == null ? null : context.getCurrentNodeConfig()));
promptVariables.put("fieldExtractionGuidance", buildFieldExtractionGuidance());
```

7. extractor Prompt 已按 `structuredEvidence / qualitySignalGuidance / readableContent` 分层，并保留 `collectedContent` 作为迁移期兼容变量。

```java
promptVariables.put("structuredEvidence", buildStructuredEvidence(evidenceViews));
promptVariables.put("qualitySignalGuidance", buildQualitySignalGuidance(evidenceViews));
promptVariables.put("readableContent", buildReadableContent(evidenceViews));
promptVariables.put("collectedContent", buildCollectedContent(evidenceViews));
```

8. 合法 JSON 但 `0` 业务字段、且正文可读时，extractor 现在会追加一次业务语义重试。

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
return invokeExtractorOnce(
        competitorName,
        competitorEvidence,
        evidenceViews,
        prompt,
        true
);
```

### 4.2 当前仍未闭合的事实

1. `SchemaExtractorAgent` 的主执行路径已经通过正式 `ExtractorInputProvider` 接收 `ExtractorInputPackage`，且 `RepositoryExtractorInputProvider` 已经改成只负责筛选、排序、预算和组包；底层 repository 读取已收口到 `ExtractorEvidenceSourcePort`，当前剩余事项是后续把 replay/cache 替换为新的端口实现，而不是再把输入边界摊回 Agent。

```java
ExtractorInputPackage inputPackage = extractorInputProvider.provide(context);
List<ExtractorCompetitorInput> competitorInputs = inputPackage.getCompetitors();
```

2. 结构块型证据、薄正文标记、Prompt 预算和 TopK 已经前移到 `RepositoryExtractorInputProvider`，并且输入载体已正式切换成 `ExtractorEvidenceInput`；Provider 仍是 repository-backed 第一版实现，但 replay/cache 未来只允许通过 `ExtractorEvidenceSourcePort` 替换来源，不再直接复用 `DownstreamEvidenceView` 或在 Agent 内手拼输入。

```java
PromptSelection selection = selectPromptEvidence(enrichedUsableInputs);
List<ExtractorEvidenceInput> evidenceCatalog = selection.selectedEvidence();
List<ExtractorEvidenceInput> traceableSkippedInputs = new ArrayList<>(selection.skippedEvidence());
```

这意味着：P1 的输入 Provider 边界、内部输入投影和预算控制已经落地，后续要继续推进的是 replay/cache 对来源端口的正式替换。

3. `extractAndNormalize()` 已支持“合法 JSON 但 0 业务字段且正文可读”时的业务语义重试，但该策略目前仍是 P0 阶段内联实现，尚未下沉为可配置策略或 Provider/Invoker 层正式职责。

```java
String businessRetryInstruction = strictBusinessRetry
        ? "\n\n【业务字段补抽要求】上一轮 JSON 合法但没有抽出任何业务字段。..."
        : "";
for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
    ...
}
```

4. Prompt 输入已经完成分层，`SchemaExtractorAgent` 现在直接消费 `ExtractorEvidenceInput` 的正文、结构块和 structured payload；`collectedContent` 仍保留在 Prompt 变量里作为迁移期兼容层，后续需要继续判断它是否还能完全退出正式模板。

```java
promptVariables.put("structuredEvidence", buildStructuredEvidence(evidenceViews));
promptVariables.put("qualitySignalGuidance", buildQualitySignalGuidance(evidenceViews));
promptVariables.put("readableContent", buildReadableContent(evidenceViews));
promptVariables.put("collectedContent", buildCollectedContent(evidenceViews));
```

5. analyzer 已经把 `ExtractResult.drafts` 作为运行期正式优先输入，并在缺少 drafts 时才 fallback 到 `CompetitorKnowledge TASK` 快照；剩余未闭合点是冲突治理和“views only”极简输入场景仍需继续观察 live 质量。

```java
for (CompetitorKnowledgeDraft draft : extractorOutput.drafts()) {
    ...
    payload.put("inputPriority", "EXTRACT_RESULT_DRAFT");
}
```

6. `DownstreamEvidenceView.content` 已经不再从 extractor 内部输入路径回流出来；shared output envelope、extractor 轻量投影和 Redis cache 都只保留 trace-only 视图，并继续透出 `extractorInput.inputSource / auditRefs`。剩余治理点是 node 原始 output 与 replay 展示的长期边界仍需继续统一。

```java
return DownstreamEvidenceView.builder()
        .evidenceId(view.getEvidenceId())
        .title(view.getTitle())
        .content("")
        .structuredPayload(Map.of())
        .structuredBlocks(slimEvidenceBlocks(view.getStructuredBlocks()))
        .build();
```

7. `schemaId / dimensions` 已经驱动 extractor Prompt，但它们当前只影响 Prompt 指引，还没有形成字段优先级、输入预算和 analyzer 优先级的统一运行态边界。

---

## 5. Blocking 项归类

### 5.1 P0：extractor 主停点

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| 结构块不足但正文可读时，仍可能抽不出非空业务字段 | P0 已增加一次业务语义重试，但仍是内联策略 | 需要在 P1 判断是否上升为可配置策略，并继续验证 live 成本与收益。 |
| Prompt 输入虽然已分层，但兼容层仍在 | `structuredEvidence / qualitySignalGuidance / readableContent` 已进入正式模板，同时保留 `collectedContent` | P1 需要决定兼容层何时退出，以及输入预算、TopK、跳过证据追溯如何前移。 |
| 结构块型证据入口已修复，但未形成正式输入 Provider | `isUsableEvidence()` 已接受 structured-only 证据 | 输入契约仍耦合在 Agent 内，rerun / replay 仍难直接解释“本次实际用了哪些证据”。 |
| Schema 已驱动 extractor Prompt，但未驱动更完整运行态边界 | `schemaGuidance` 已进入 Prompt | 还没有把 schema 优先级、字段策略和 analyzer 消费优先级统一成正式行为配置。 |

P0 的验收目标不是“报告最终通过”，而是先证明 extractor 对已有证据具备稳定抽取非空业务字段的能力，并能解释失败原因。

### 5.2 P1：运行时边界收口

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| `ExtractorInputProvider` 底层数据源仍未完全收口 | 来源端口已落地，但当前仍是 `REPOSITORY_BACKED_PORT` 第一版实现 | 输入边界和内部来源端口已经收口，后续只剩 replay/cache/正式端口继续替换来源适配器。 |
| analyzer 优先级已切换但冲突治理仍需继续观察 | drafts 已优先，snapshot 仅补空字段 | `ExtractResult / drafts` 已成为正式输入，但 live 下的冲突面和 views-only 极简场景仍需继续验证。 |
| `DownstreamEvidenceView` 的原始节点输出与长期回放边界仍需继续治理 | shared output 已瘦身，但节点原始 output 仍保留完整执行现场 | 长正文已不再跨节点扩散，但 node 原始结果与 replay 展示的长期治理仍未完全结束。 |
| `CompetitorKnowledge` 实体默认仍是 `DOMAIN` | 实体默认值仍会在未显式设置时补成 `DOMAIN` | extractor 已显式设置 `TASK`，但其它写入路径仍必须被治理，避免历史默认值重新污染长期记忆。 |

P1 的验收目标是让运行期正式边界稳定：输入有 Provider，输出以 `ExtractResult / drafts / lightweightEvidenceViews` 为优先，`CompetitorKnowledge TASK` 只作为快照和恢复 fallback。

### 5.3 P2：下游消费与质量门禁

| Blocking 项 | 当前证据 | 为什么阻塞 |
| --- | --- | --- |
| writer / reviewer / delivery 可能继续失败 | task `43/50` 类任务已显示 `unsupported_claim / missing_evidence / actionability` | extractor 成功不等于报告质量通过；下游失败必须与 extractor 失败分层。 |
| `evidenceCoverage` live 覆盖仍不完整 | 第二轮代码已把 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 细状态透出到 report `statusBreakdown`；task `50` 本轮 live 样本只出现 `TRACEABLE / EMPTY` | 报告侧聚合能力已补上，但仍需要真实命中样本验证三类细状态会怎样出现在 live 结果里。 |
| workflow 下游汇总还未完全泛化到所有场景 | 第二轮已把 `DOWNSTREAM_CONSUMPTION_GAP` 从 reviewer 两类阻断扩展到 analyzer / writer / reviewer 的 extractor-success 下游消费失败；task `50` 本轮 live 样本已命中 reviewer 终审质量闭环失败归口 | 仍未覆盖 delivery 和更广义下游失败形态，也还需要继续观察 analyzer / writer 场景的 live 命中分布。 |
| 真实链路验收已取得 task `50` 通过与失败样本 | 2026-06-22 task `50` 已有一次成功通过终审样本；本轮 `extract_schema` rerun 后主链路和动态回流节点均成功，但 task 因质量闭环未通过变为 `FAILED` | 基础成功链路与 `DOWNSTREAM_CONSUMPTION_GAP` live 命中已验证，剩余是补 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 三类 coverage live 样本。 |

P2 不应反向阻塞 P0/P1。只有当 extractor / analyzer 边界已经坐实后，writer / reviewer / delivery 的问题才应升级为下一条链路的正式诊断输入。

### 5.4 里程碑预期

本节只给执行节奏锚点，不替代实施计划中的任务拆分。

| 层级 | 时间锚点 | 预期结果 | 进入下一层的条件 |
| --- | --- | --- | --- |
| P0 extractor 主停点 | 2026-06-21 已完成自动化验收 | Prompt 分层、正文 fallback、0 字段语义重试、结构块型证据入口和 Schema 注入已落地，并通过 `SchemaExtractorAgentTest` 与 `CompetitorAnalysisAgentTest` 回归 | 下一步转入 P1，继续收运行态输入 Provider、analyzer 优先级与 shared output 轻量化 |
| P1 运行时边界收口 | 2026-06-22 已完成第一版实现 | `ExtractorInputProvider / ExtractorInputPackage`、analyzer drafts 优先、extract shared output sanitizer 与轻量投影已落地 | 下一步继续验证 Provider 数据源统一和 replay/原始 output 长期边界 |
| P2 下游消费与质量门禁 | 2026-06-22 进行中 | `evidenceCoverage` 细化已落地到 report `statusBreakdown`；workflow 已把 analyzer / writer / reviewer 的部分下游消费失败归口为 `DOWNSTREAM_CONSUMPTION_GAP`；task `50` 已完成基础成功样本与 `DOWNSTREAM_CONSUMPTION_GAP` live 命中样本 | 仍需继续扩 workflow 场景，并补 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` live 状态样本 |

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
| 实施 | `🟡` | 第八轮第一阶段已落地 `TASK` 快照、共享追溯契约和 0 字段阻断；2026-06-21 已完成 P0 自动化收口；2026-06-22 已完成 P1 第一版实现，并开始落地 P2 的 workflow 下游失败汇总。 |
| 实链验证 | `🟡` | 2026-06-22 task `50` 已完成真实 `extract_schema -> quality_check_final` 链路复验并通过终审；2026-06-29 task `68` 又补到一条“04 搜索采集底座已通过、但质量与 coverage 在下游阻断”的 live 样本。当前缺的已经不是 04 底座是否可跑通，而是更多下游失败形态与 coverage 细状态的实链样本。 |

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
