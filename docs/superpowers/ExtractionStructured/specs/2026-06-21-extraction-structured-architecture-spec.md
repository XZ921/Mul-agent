# 3.3 提取结构化架构规格

> 2026-06-21 版本。本文基于 3.2 搜索与采集继承基线、提取结构化链路诊断、以及 3.3 优化点汇总，收口 `extract_schema` 的正式输入边界、输出边界、运行态轻量化、Schema 驱动和失败诊断规则。

---

## 1. 文档目标

本文只定义 `3.3 提取结构化` 的架构边界和分阶段落地规格，避免后续实施时继续在以下问题上摇摆：

- extractor 到底消费 DB 快照、上游节点输出，还是正式输入包；
- analyzer 到底信任 `CompetitorKnowledgeRepository`，还是 `ExtractResult / drafts`；
- `DownstreamEvidenceView` 是否可以携带完整正文跨节点流转；
- 总任务中的 `schemaId / dimensions` 从哪里开始成为硬约束；
- Prompt 输入如何避免采集越多、上下文越容易爆；
- 结构化失败如何区分 extractor 失败和下游消费失败。

本文不是 3.2 搜索与采集返工计划，不重新设计搜索发现、采集路由、Playwright 策略、RSS 订阅、跨任务缓存和前端展示。

---

## 2. 继承前提

3.3 直接继承 3.2 已冻结的证据输入契约：

- `sourceUrls` 是无幻觉追溯红线，所有结构化结果、证据片段和诊断对象都必须保留来源回指。
- `searchAudit` 可选参与证据来源解释，用于说明证据从哪里被发现，但不进入 extractor 提取主路径。
- `collectionAudit` 是正式继承对象，其采集路径和失败层级语义在 3.3 中优先通过 `qualitySignals / failureKind / issueFlags` 间接消费，并在输入预检诊断中保留 audit 回指。
- `DownstreamEvidenceView` 是当前运行期统一证据视图，但后续需要拆清“提取输入视图”和“下游轻量视图”。
- `qualitySignals / qualityScore / failureKind / structuredBlocks` 必须继续传递，不能在 extractor 中被吞掉。
- `EvidenceFragment / SectionEvidenceBundle / evidenceCoverage` 是跨采集、提取、分析、写作、质检和报告的共享追溯契约。
- extractor 产物默认是 `TASK` 现场快照，不得默认沉入 `DOMAIN` 长期记忆。

3.3 的主线目标不是证明 3.2 完美，而是在已有证据可追溯的前提下，让结构化提取稳定、可诊断、可恢复。

---

## 3. 当前架构问题

### 3.1 双轨并存导致状态分裂

当前 extractor 同时写入 `CompetitorKnowledgeRepository` 和节点输出 `ExtractResult / drafts / downstreamEvidenceViews`，analyzer 又同时消费两路输入。这会形成两套事实来源：

- DB 写入成功但节点输出失败时，下游难以判断哪条链路可信；
- 节点输出成功但 DB 快照缺失时，恢复、回放和查询语义不一致；
- `ExtractResult` 的契约演进不一定能影响 analyzer 实际消费路径。

### 3.2 sharedOutput 携带长正文

`DownstreamEvidenceView.content` 当前可能携带完整正文。如果它进入 `extract_schema` 节点输出，会继续被写入：

- `TaskNode.outputData`
- `AgentContext.sharedState`
- 快照缓存
- replay / report / analyzer 的反序列化路径

这会放大 JVM 内存、缓存、数据库字段和回放解析压力。

### 3.3 Prompt 总预算缺失

当前 `buildCollectedContent()` 只做单条证据截断，没有控制单个竞品进入 prompt 的总长度。一个竞品采集 30 个页面时，即使每条 4000 字符，也可能撑爆模型上下文窗口。

### 3.4 Agent 直接依赖 Repository

`SchemaExtractorAgent` 直接读取 `EvidenceSourceRepository`，并直接写入 `CompetitorKnowledgeRepository`。这让 DAG 节点输入不透明，也让测试、恢复和 rerun 依赖底层 DB 状态。

### 3.5 Schema 尚未真正驱动 extractor

`ExecutionPlanDefinitionBuilder` 已经把 `schemaId / dimensions` 写入 `extract_schema.nodeConfig`，但 extractor 当前仍按固定 7 个字段抽取，没有把任务 Schema 转成提取行为约束。

### 3.6 3.2 输入继承存在细小缝隙

3.2 搜索与采集已经能支撑 3.3 启动，但 extractor 输入侧仍有三个需要在本阶段收口的契约缝隙：

- 采集级 `issueFlags` 已进入 `CollectResult` 和 collection result entry，但没有稳定写入 `EvidenceSource.pageMetadata`。`DownstreamEvidenceViewAssembler` 主要从 `pageMetadata` 读取 `issueFlags`，因此 extractor 可能看不到 `COLLECT_FAILED / CONTENT_GAP` 等采集警告。
- `CollectorAgent.isUsableCollectedPage()` 允许“正文为空但 `structuredBlocks / structuredPayload` 非空”的证据入库，而 `SchemaExtractorAgent.isUsableEvidence()` 目前只看 `fullContent / contentSnippet`。这会让 API 型或结构块型证据在 extractor 入口被静默过滤。
- 采集层可以保存正文极薄但非空的页面。此类证据不应被当成高价值正文直接塞进 Prompt，而应被输入预检标为 `THIN_CONTENT_ONLY / LOW_QUALITY_EVIDENCE`，并交给 0 业务字段语义重试或失败诊断处理。

这些问题不推翻 3.2 的阶段结论，但说明 3.3 必须增加正式输入预检，避免把上游质量缺口误诊为 Prompt 或模型问题。

---

## 4. 目标架构

3.3 的目标架构以“输入边界、提取执行、输出边界、持久化快照”四层收口。

```text
EvidenceSource / replay / cache
        |
        v
ExtractorInputProvider
        |
        v
ExtractorInputPackage
        |
        v
SchemaExtractorAgent
        |
        v
ExtractResult / drafts / lightweightEvidenceViews
        |
        +--> analyzer / writer / reviewer
        |
        +--> CompetitorKnowledge TASK snapshot
```

核心规则：

- `ExtractorInputProvider` 负责证据读取、过滤、排序、TopK、预算控制和输入包组装。
- `ExtractorInputPackage` 是 `SchemaExtractorAgent` 的正式输入边界。
- `SchemaExtractorAgent` 负责提取策略、Prompt 构建、LLM 调用、语义重试和结果归一。
- `ExtractResult / drafts` 是运行时正式输出边界。
- `CompetitorKnowledge` 是 `TASK` 快照，用于持久化、回放和查询，不再作为 analyzer 唯一事实来源。
- `DownstreamEvidenceView` 在跨节点传递时必须是轻量视图，不携带完整正文。

---

## 5. 输入边界规格

### 5.1 ExtractorInputProvider

`ExtractorInputProvider` 是 P1 必须定义的输入边界。第一版内部可以继续调用 repository，但 `SchemaExtractorAgent` 不再直接依赖 repository。

```text
ExtractorInputProvider
  -> 根据 taskId / planVersionId / branchKey / nodeName 读取证据
  -> 过滤不可用证据
  -> 按质量、来源类型、结构块命中和字段相关性排序
  -> 执行 TopK 和总预算控制
  -> 生成 ExtractorInputPackage
```

### 5.2 ExtractorInputPackage

`ExtractorInputPackage` 应承载 extractor 本次执行需要的全部输入事实：

```json
{
  "taskId": 50,
  "nodeName": "extract_schema",
  "planVersionId": 27,
  "branchKey": "root",
  "schemaId": 1,
  "dimensions": [],
  "competitors": [
    {
      "competitorName": "Notion AI",
      "evidenceCatalog": [],
      "structuredEvidence": [],
      "readableEvidence": [],
      "skippedEvidence": [],
      "sourceUrls": [],
      "issueFlags": []
    }
  ],
  "budget": {
    "maxPromptEvidenceChars": 30000,
    "usedPromptEvidenceChars": 18420,
    "truncated": true
  },
  "auditRefs": {
    "searchAudit": {
      "available": true,
      "usage": "用于解释证据从哪里被发现，不作为 extractor 主输入正文"
    },
    "collectionAudit": {
      "available": true,
      "usage": "用于解释证据如何被采集、失败发生在哪一层"
    }
  }
}
```

`searchAudit / collectionAudit` 不替代证据正文、结构块和来源链接。它们在 extractor 中承担诊断角色：当证据缺失、质量不足或采集失败时，Provider 应把 audit 中可解释的发现路径、采集路径和失败层级转成 `skippedEvidence / issueFlags / failureDiagnosis`，供后续 replay、报告诊断和人工排查使用。

### 5.3 证据选择规则

进入 Prompt 的证据必须先经过输入层排序和预算控制：

- structuredBlocks 非空的证据优先；
- structuredBlocks 只提升证据优先级，不能被当成唯一证据来源；
- `qualityScore` 高的证据优先；
- `sourceType=OFFICIAL / DOCS / PRICING` 优先于 NEWS / RSS / REVIEW；
- `CONTENT_GAP / COLLECT_FAILED` 不进入正文区，但可进入目录区用于诊断；
- 超预算证据只保留 `evidenceId / sourceUrls / skipReason`；
- 任何截断都必须记录 `truncated=true` 和截断原因。

当 `structuredBlocks` 不足以覆盖字段时，Provider 必须保留可读正文进入 fallback 提取；不能因为某条证据存在结构块，就整体跳过同一竞品的 readable content。

### 5.4 正文使用规则

完整正文只允许存在于以下对象：

| 对象 | 是否允许长正文 | 用途 |
|---|---:|---|
| `EvidenceSource.fullContent` | 是 | 正式证据存储 |
| `ExtractorInputPackage.readableEvidence` | 是 | extractor 内部一次性输入 |
| `ExtractResult / drafts` | 否 | 运行时结构化结果 |
| `DownstreamEvidenceView` | 否 | 下游轻量证据视图 |
| `TaskNode.outputData / sharedOutput` | 否 | 跨节点状态与回放 |

这里的拆分是“输入投影 / 输出投影”拆分，不是语义拆分。`DownstreamEvidenceView / EvidenceFragment / SectionEvidenceBundle` 仍然是跨采集、提取、分析、写作、质检和报告的共享追溯契约；任何轻量化都只能移除长正文或冗余 payload，不能把字段含义重新拆回各节点私有模型。

### 5.5 3.2 -> 3.3 输入预检门禁

`ExtractorInputProvider` 在生成 `ExtractorInputPackage` 前必须执行输入预检。预检目标不是阻塞 3.3，而是把上游证据质量显式分类，避免 extractor 静默丢证据或误把薄内容当成可提取正文。

预检规则：

- 每个竞品至少需要 1 条可消费证据，否则返回 `NO_USABLE_EVIDENCE`。
- 可消费证据不只等于“有正文”。满足以下任一条件即可进入 extractor 输入包：
  - `fullContent` 非空；
  - `contentSnippet` 非空；
  - `structuredBlocks` 非空；
  - `structuredPayload` 非空。
- 只有 `structuredBlocks / structuredPayload`、但没有正文的证据，必须进入 `structuredEvidence`，不能被 `isUsableEvidence()` 静默过滤。
- 正文长度低于最小阈值且没有结构块的证据，不进入 `readableEvidence` 正文区，只进入 `evidenceCatalog / skippedEvidence`，并标记 `THIN_CONTENT_ONLY`。
- `CONTENT_GAP / COLLECT_FAILED / NO_USABLE_CONTENT` 等采集级 `issueFlags` 必须进入 `ExtractorInputPackage`，并继续传给 Prompt 质量信号、`ExtractResult.issueFlags` 和失败诊断。
- `sourceUrls` 缺失时可以从 `EvidenceSource.url` 回填，但必须附加 `SOURCE_URLS_BACKFILLED`，不能把来源回填视为业务字段提取成功。

issueFlags 继承规则：

```text
inputIssueFlags =
  pageMetadata.issueFlags
  + CollectResult / result entry issueFlags
  + Provider 预检派生 issueFlags
```

短期 P1 可以先把 `buildCollectionIssueFlags(page)` 的结果写入 `pageMetadata.issueFlags`，并让 `DownstreamEvidenceViewAssembler` 原样读取。中期由 `ExtractorInputProvider` 统一合并 `pageMetadata`、`CollectResult` 和预检派生信号。

薄内容处理规则：

- `fullContent / contentSnippet` 的有效正文长度低于 40 字符，且没有结构化证据时，标记 `THIN_CONTENT_ONLY`。
- `THIN_CONTENT_ONLY` 证据不参与正文拼接，但保留 `evidenceId / sourceUrls / title / issueFlags` 用于诊断。
- 若某竞品只有薄内容证据，extractor 应返回 `LOW_QUALITY_EVIDENCE` 或 `NO_BUSINESS_FIELDS_EXTRACTED`，而不是让模型在无信息正文上反复重试。

---

## 6. 输出边界规格

### 6.1 ExtractResult 是运行时正式边界

`extract_schema` 成功后，运行时正式输出以 `ExtractResult` 为准：

- `contractVersion`
- `totalCompetitors`
- `drafts`
- `sourceUrls`
- `issueFlags`
- `evidenceFragments`
- `sectionEvidenceBundles`
- `downstreamEvidenceViews`

其中 `downstreamEvidenceViews` 必须是轻量视图，不允许携带完整正文。

### 6.2 CompetitorKnowledge 是 TASK 快照

`CompetitorKnowledge` 的职责是持久化当前任务现场：

- `memoryLayer=TASK`
- `snapshotScope=TASK`
- `producerNodeName=extract_schema`
- `versionSource=TASK_EXTRACT@{planVersionId}`
- `invalidationScope=TASK_RERUN`

它用于查询、回放和审计，不再作为 analyzer 的唯一输入边界。只有通过治理后的 writeback 路径才允许生成 `DOMAIN` 长期记忆。

### 6.3 Analyzer 消费优先级

`CompetitorAnalysisAgent` 的消费顺序应为：

1. 优先消费 `extract_schema` 输出中的 `ExtractResult / drafts / lightweightEvidenceViews`。
2. 当运行时输出缺失或处于恢复场景时，再 fallback 到 `CompetitorKnowledge TASK` 快照。
3. fallback 必须记录 `EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT`，避免审计时误以为全程消费的是运行态输出。

---

## 7. Schema 驱动规格

### 7.1 Schema 从 extract_schema 开始生效

总任务中的 `schemaId / dimensions` 从 `extract_schema` 节点开始成为提取硬约束。`SchemaExtractorAgent` 必须读取 `currentNodeConfig` 中的任务维度，并注入 Prompt：

```text
# 本次任务的分析重点
{schemaName}: {dimensions}
请优先提取与上述维度直接相关的业务字段。
```

### 7.2 维度到字段的映射

短期内保留固定 7 个输出字段：

- `summary`
- `positioning`
- `targetUsers`
- `coreFeatures`
- `pricing`
- `strengths`
- `weaknesses`

中期将 `AnalysisSchema.dimensions` 扩展为带提取行为的结构：

```json
{
  "businessName": "价格策略",
  "extractorField": "pricing",
  "priority": "REQUIRED",
  "evidencePreference": ["PRICING_BLOCK"],
  "extractionHint": "提取所有价格数字、计费周期、免费额度、企业折扣信息"
}
```

### 7.3 Schema 的长期职责

长期目标是让 Schema 从“模板选择器”升级为行为配置中心，逐步控制：

- 采集来源偏好；
- 提取字段优先级；
- 字段证据偏好；
- 分析维度；
- 质量门槛；
- 报告结构。

本轮不一次性改造完整 Schema 表结构。

---

## 8. Prompt 与提取策略规格

### 8.1 Prompt 输入分层

Prompt 必须显式拆分：

```text
# 结构化证据（优先使用）
{structuredBlocks}

# 质量信号
{qualitySignals translated to extraction hints}

# 正文内容（兜底）
{readableContent}
```

模型必须理解：结构块优先，结构块不足时回退正文，不能因为 `structuredBlocks=[]` 就输出空字段。

### 8.2 输出 Schema 强约束

Prompt 必须要求模型只返回 JSON，并遵守：

- 顶层必须包含 `sourceUrls`；
- 每个业务字段必须携带 `evidenceIds` 或 `sourceUrls`；
- 不确定时返回空值并输出缺口说明；
- 禁止编造无法从证据回指的结论；
- 禁止只返回空对象。

### 8.3 0 业务字段语义重试

重试分两类：

- JSON 解析失败重试：模型输出不是合法 JSON 时触发。
- 业务语义重试：JSON 合法但 7 个业务字段全空，且存在可读正文时触发。

业务语义重试规则：

- 首轮 0 字段时，用更强 prompt 重试一次；
- 如果正文不足 40 字符，跳过无意义重试；
- 重试仍 0 字段时，返回 `NO_BUSINESS_FIELDS_EXTRACTED`；
- `sourceUrls` 回填不能被视为业务字段提取成功。

### 8.4 后续分字段组提取

分字段组提取作为后续演进，不进入第一阶段：

- 概述组：`summary / positioning / targetUsers`
- 事实组：`coreFeatures / pricing`
- 判断组：`strengths / weaknesses`

该策略会增加 LLM 调用次数，需在单次提取稳定后再评估成本与延迟。

---

## 9. evidenceCoverage 与失败诊断

### 9.1 evidenceCoverage 状态

短期保留现有状态：

- `EMPTY`
- `MISSING_EVIDENCE`
- `TRACEABLE`

中期扩展为：

- `LLM_REFUSED`
- `EVIDENCE_NOT_COVERING`
- `STRUCTURED_BLOCK_DIRECT`

### 9.2 失败分层

提取结构化失败必须分层记录：

| 失败层 | 含义 | 归属 |
|---|---|---|
| `NO_USABLE_EVIDENCE` | 无任何可读证据 | extractor 入口 |
| `MODEL_OUTPUT_INVALID_JSON` | 模型 JSON 不合法 | extractor 重试 |
| `NO_BUSINESS_FIELDS_EXTRACTED` | 模型未抽出业务字段 | extractor 语义重试 |
| `FIELD_MISSING_EVIDENCE` | 有字段值但无证据 | extractor coverage |
| `LOW_QUALITY_EVIDENCE` | 证据质量不足 | extractor 质量门禁 |
| `DOWNSTREAM_CONSUMPTION_GAP` | extractor 成功但下游失败 | workflow 汇总 |

`DOWNSTREAM_CONSUMPTION_GAP` 不属于 extractor 内部失败，应由 workflow 层汇总 analyzer / writer / reviewer / delivery 后判定。

---

## 10. 分阶段落地

### P0：提取主停点修复

- Prompt 输入分层；
- 字段级提取指引；
- 0 业务字段语义重试；
- 输入预检诊断，避免薄内容被当成高价值正文；
- extractor 读取 `dimensions / schemaId`；
- qualitySignals 翻译；
- 输出 Schema 强约束。

### P1：运行时边界收口

- Prompt 总预算控制与 TopK；
- 采集级 `issueFlags` 写入或合并到 extractor 输入包；
- `isUsableEvidence` 与采集层可用性口径对齐，允许结构块型证据进入 extractor；
- `ExtractResult / drafts` 成为 analyzer 优先输入；
- `sharedOutput / TaskNode.outputData` 轻量化；
- 定义 `ExtractorInputProvider` 接口与 `ExtractorInputPackage`；
- `SchemaExtractorAgent` 通过 Provider 取输入；
- `evidenceCoverage` 状态初步细化。

### P2：输入来源与治理能力增强

- Provider 内部数据源从直接 repository 调用迁移到正式端口；
- `ExtractorEvidenceInput` 与 `DownstreamEvidenceView` 拆分；
- Provider 接入 replay / cache / 预算控制；
- 失败分层扩展到 workflow 汇总；
- Schema dimensions 结构升级。

### 后续：长期架构演进

- `SchemaExtractorAgent` 职责拆分；
- 分字段组提取；
- Schema 驱动采集、提取、分析、质检和报告结构；
- `CompetitorKnowledge` 写入职责继续下沉到持久化端口或工作流收口层。

---

## 11. 当前不做

本规格明确不把以下内容放入当前阶段：

- 不回头扩展搜索发现、采集路由、Playwright、RSS、news discovery；
- 不一次性重构完整 `SchemaExtractorAgent`；
- 不一次性重构 `CompetitorKnowledge` 为全新领域模型；
- 不把 Provider 内部 repository 访问一次性迁移完；
- 不把 Schema 立刻升级为完整全链路行为配置中心；
- 不建设跨重启 replay 持久化底座，本阶段只消费已有 replay / audit / checkpoint 诊断信息；
- 不改前端页面和报告展示 UI。

---

## 12. 验收标准

### 自动化验收

- extractor 在 `structuredBlocks=[]` 但正文可读时，会触发 0 字段语义重试；
- extractor 不会过滤掉“正文为空但 `structuredBlocks / structuredPayload` 非空”的结构块型证据；
- `structuredBlocks` 存在但字段覆盖不足时，extractor 仍会消费 readable content 做 fallback 提取；
- `searchAudit / collectionAudit` 能作为输入诊断来源进入 skippedEvidence、issueFlags 或 failureDiagnosis，而不是被 extractor 丢弃；
- `COLLECT_FAILED / CONTENT_GAP / NO_USABLE_CONTENT` 等采集级 `issueFlags` 能进入 extractor 输入包和 `DownstreamEvidenceView.issueFlags`；
- 正文极薄且无结构块的证据会被标记 `THIN_CONTENT_ONLY / LOW_QUALITY_EVIDENCE`，不会直接进入正文 Prompt 区；
- extractor 输出不再因 `sourceUrls` 回填而把 0 业务字段判为成功；
- analyzer 能优先消费 `extract_schema` 的 `drafts / lightweightEvidenceViews`；
- `TaskNode.outputData` 中不再持有完整正文；
- Provider 接口存在后，`SchemaExtractorAgent` 不再直接读取 `EvidenceSourceRepository`。

### live 验收

以 task `50` 类任务验证：

- 若 extractor 失败，能明确区分是无可用证据、JSON 失败、0 业务字段，还是字段缺少证据；
- 若 extractor / analyzer 成功但质量门禁失败，失败应归为 writer / reviewer / delivery 的下游消费问题；
- report evidences 能继续展示 `sourceUrls / evidenceFragments / sectionEvidenceBundles`。

---

## 13. 相关文档

- [3.3 提取结构化优化点汇总](../summary/2026-06-21-extraction-structured-optimization-summary.md)
- [提取结构化链路诊断](../problem/ExtractionStructured.md)
- [3.2 继承前提基线](../problem/2026-06-20-extraction-structured-3.2-inheritance-baseline.md)
- [3.3 优化实施计划](../plan/2026-06-20-extraction-structured-optimization-plan.md)
- [3.2 搜索与采集阶段总结](../../search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md)
