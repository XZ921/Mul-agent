# 3.3 提取结构化优化点汇总

> 2026-06-21 讨论总结。覆盖 Prompt 层、提取策略层、Schema 驱动、输入消费层、输出治理层、失败诊断层、长期架构边界共 7 个维度。

---

## 一、Prompt 层

### 1.1 输入分层：structuredBlocks / readableContent / qualitySignals 显式分段

**现状**：`buildCollectedContent()` 把 structuredBlocks、qualitySignals、正文混在同一段文本中拼接，模型无法区分"高置信结构证据"和"正文兜底"。

**目标**：prompt 中显式拆为三段——

```
# 结构化证据（优先使用）
{pricing_block / feature_list / api_response}

# 质量信号
{qualitySignals — 翻译为提取指引}

# 正文内容（兜底）
{collectedContent}
```

让模型明确知道：结构块优先，结构块不足时才看正文，不能因为 `structuredBlocks=[]` 就输出空字段。

**改动点**：`SchemaExtractorAgent` Prompt 变量组装 + `PromptTemplateService` extractor 模板。

**2026-06-21 P0 状态**：已实施。正式模板已使用 `structuredEvidence / qualitySignalGuidance / readableContent` 三段输入，`collectedContent` 仅保留为迁移期兼容变量。

### 1.2 每个业务字段增加独立提取指引

**现状**：7 个字段 (`summary`, `positioning`, `targetUsers`, `coreFeatures`, `pricing`, `strengths`, `weaknesses`) 性质不同，但 prompt 没有任何字段级指引。

**目标**：在 prompt 中加字段级约束——

- summary：从所有正文归纳，不超过 200 字
- pricing：优先从 PRICING_BLOCK 提取，需含价格数字和计费周期
- targetUsers：从正文中对用户角色/行业/规模的描述中提取
- coreFeatures：每项需带 evidenceId
- strengths/weaknesses：只提取正文中明确提到的，禁止编造

### 1.3 qualitySignals 翻译为可操作的提取指引

**现状**：qualitySignals（如 `LIGHTWEIGHT_CONTENT_READY`、`PRICING_BLOCK_HIT`、`CONTENT_GAP`）原样传给模型，模型不知道含义。

**目标**：在 evidenceCatalog 或 collectedContent 中把信号翻译为指引——

```
PRICING_BLOCK_HIT  → "该证据包含可信的定价结构块，pricing 字段应优先引用此证据"
CONTENT_GAP        → "该证据正文缺失，相关业务字段请从其他证据中提取"
```

### 1.4 Prompt 输出 Schema 强约束

**现状**：当前 prompt 只说"你必须只返回 JSON"，没有对 JSON 内容做任何约束。

**目标**：增加固定输出协议——

- 顶层必须包含 sourceUrls
- 每个业务字段必须带 evidenceIds 或 sourceUrls
- 不确定就返回空值并写入缺口说明，不能编
- 禁止只返回空对象
- 输出必须包含 evidenceCoverage 或让后端稳定补齐

---

## 二、提取策略层

### 2.1 0 业务字段语义重试

**现状**：重试（最多 3 次）只针对 JSON 格式解析失败。当 JSON 合法但 7 个业务字段全空时，直接 `FAILED`。

**目标**：增加第二类重试——

- 首轮 0 字段 → 用更强的 prompt 重试（明确要求"至少抽出一个可证据支撑的非空业务字段"）
- 重试仍 0 字段 → 才失败
- 正文为空时跳过无意义重试（`hasReadableEvidenceContent` 判断，正文 ≥40 字符）

**2026-06-21 P0 状态**：已实施。extractor 在“首轮 JSON 合法但 0 业务字段、且正文可读”时会追加一次业务语义重试；结构块-only 证据不会触发这次补抽。

**改动点**：`SchemaExtractorAgent.extractAndNormalize()`。

### 2.2 分字段/分字段组提取

**现状**：一次 LLM 调用要求输出全部 7 个字段。某个字段失败拖垮整个竞品。

**目标**：分 2-3 组提取——

- 组 1：summary + positioning + targetUsers（概述类，轻量扫读）
- 组 2：coreFeatures + pricing（事实类，需仔细提取）
- 组 3：strengths + weaknesses（判断类，需推理）

好处：某个组失败不拖垮全局，更容易判断"是 pricing 证据不足还是全局提取失败"。

**注意**：会增加 LLM 调用次数（当前 ×3），需评估延迟和成本。优先做 2.1（语义重试），2.2 作为后续演进方向。

### 2.3 正文截断策略优化

**现状**：每条证据最多 4000 字符，超出直接 `substring(0, 4000)` 截断。长文档的后半部分（可能包含重要的 pricing 或 features）被静默丢弃。

**目标**：
- 按章节/标题分段，保留每段首尾
- structuredBlocks 不受截断影响
- 对极长内容做分段提取而非直接丢弃

---

## 三、Schema 驱动

### 3.1 让 extractor 读 nodeConfig 中的 dimensions / schemaId

**现状**：`ExecutionPlanDefinitionBuilder` 已将 dimensions 和 schemaId 写入 nodeConfig，但 `SchemaExtractorAgent` 完全不解析 nodeConfig。extractor 对所有任务都抽取相同的 7 个固定字段。

**目标**：extractor 在 `doExecute()` 中读取 nodeConfig 中的 dimensions，注入 prompt——

```
# 本次任务的分析重点
{ schemaName }：{ dimensions }
请优先提取与上述维度直接相关的业务字段。
```

**改动点**：`SchemaExtractorAgent.extractAndNormalize()` + `buildSchemaGuidance(...)`。

**2026-06-21 P0 状态**：已实施。`schemaId / dimensions` 已从 `currentNodeConfig` 注入 extractor Prompt，并作为本次任务分析重点进入字段提取指引。

### 3.2 Schema dimensions 与 extractor 输出字段建立显式映射

**现状**：Schema 的业务维度（"产品功能""目标用户"）和 extractor 的 Java 字段（`coreFeatures``targetUsers`）之间没有显式映射，完全靠人脑默认。

**目标**：Schema dimensions 结构升级——

```json
{
  "businessName": "价格策略",
  "extractorField": "pricing",
  "priority": "REQUIRED",
  "evidencePreference": ["PRICING_BLOCK"],
  "extractionHint": "提取所有价格数字、计费周期、免费额度、企业折扣信息"
}
```

| 新增字段 | 作用 |
|---|---|
| `extractorField` | Schema 维度 → extractor 输出字段的显式映射 |
| `priority` | REQUIRED（缺此字段任务失败）/ OPTIONAL / SUPPLEMENTARY |
| `evidencePreference` | 该字段优先从哪种 structuredBlock 取值 |
| `extractionHint` | 注入 prompt 的字段级提取指引 |

### 3.3 Schema 驱动整条链路（长期）

Schema 从"模板选择器"升级为**行为配置中心**：采集策略 → 提取策略 → 分析策略 → 质量门槛 → 报告结构，全由 Schema 控制。

---

## 四、输入消费层

### 4.1 证据按质量排序进 prompt

**现状**：所有可用证据平等拼接进 prompt，不考虑质量差异。

**目标**：
- 按 `qualityScore` 降序排列（高质量证据放 prompt 前面）
- `structuredBlocks` 非空的证据自动排在前面
- 按 `sourceType` 分组（OFFICIAL 优先，NEWS 补充，RSS 最后）
- `CONTENT_GAP` / `COLLECT_FAILED` 的证据直接不进 prompt

### 4.2 证据去重与冲突标注

**现状**：同一竞品的多条证据（官网 + 定价页 + 新闻），信息可能冲突（定价页 $10，新闻说涨到 $15），但 prompt 没有标注。

**目标**：在 evidenceCatalog 中标注每条证据的采集时间和来源类型，冲突字段让模型显式标注 `conflictingSources`。

### 4.3 Prompt 总预算控制与 TopK 证据选择

**现状**：`buildCollectedContent()` 只对单条证据做 4000 字符截断，没有控制单个竞品进入 prompt 的总长度。若某个竞品采集 30 个页面，即使每条都截断，整体仍可能撑爆 LLM 上下文窗口。

**目标**：在 prompt 组装前增加总预算控制——

- 每个竞品设置 `maxPromptEvidenceChars` 或 token 预算；
- structuredBlocks 永远优先保留，不因正文超预算被丢弃；
- readableContent 按 `qualityScore / sourceType / structuredBlock 命中 / 字段相关性` 排序后取 TopK；
- 超预算证据只进入 `evidenceCatalog`，不进入正文区；
- 被截断或跳过的证据必须保留 `evidenceId / sourceUrls / skipReason`，便于后续诊断。

**改动点**：新增 `ExtractorPromptBudgeter` 或先在 `SchemaExtractorAgent` 内部收口预算逻辑，后续再独立成类。

### 4.4 提取输入轻量投影

**现状**：`DownstreamEvidenceView` 同时承担“提取输入”和“下游运行态证据视图”，其中 `content` 可能包含长正文。如果不区分输入投影和输出投影，后续很容易把长正文带入 sharedOutput。

**目标**：把提取输入拆成两层——

- `ExtractorEvidenceInput`：仅在 extractor 内部使用，可携带正文；
- `DownstreamEvidenceView`：传递给 analyzer / reviewer / report 的轻量视图，不携带完整正文，只保留摘要、结构块、质量信号和来源；
- 长正文只允许通过 `evidenceId` 回查，不作为跨节点共享状态常驻内存。

**第三轮落地事实（2026-06-23）**：

- `RepositoryExtractorInputProvider` 已经不再直接读取 `EvidenceSourceRepository`；底层正文来源统一收口到 `ExtractorEvidenceSourcePort`。
- `SchemaExtractorAgent` 已经直接消费 `ExtractorEvidenceInput` 构建 Prompt、coverage 和轻量下游视图，不再回建旧 `EvidenceSource / DownstreamEvidenceView` 作为内部主输入。
- extractor shared projection、shared output envelope 和 Redis runtime cache 已保留 `extractorInput.inputSource=REPOSITORY_BACKED_PORT` 与 `auditRefs`，同时继续裁掉正文和 structuredPayload。

---

## 五、输出治理层

### 5.1 ExtractResult vs CompetitorKnowledge 边界收口

**现状**：analyzer 同时消费两路输入——`CompetitorKnowledgeRepository`（DB 快照）和 `extract_schema` 节点输出（ExtractResult.drafts / downstreamEvidenceViews），形成"两套世界"。

**目标**：
- `ExtractResult / drafts` 是运行时正式边界
- `CompetitorKnowledge` 是任务现场快照（持久化、回放、查询）
- Analyzer 优先消费 extractor 输出，repository 只做 fallback 或恢复场景补充

**状态**：已在 optimization plan Task 3 中规划。

### 5.2 evidenceCoverage 状态细化

**现状**：3 个状态 — `EMPTY` / `MISSING_EVIDENCE` / `TRACEABLE`。

**目标**：扩展为 5 个状态——

| 状态 | 含义 |
|---|---|
| `EMPTY` | 字段为空 |
| `LLM_REFUSED` | 模型主动表示无法判断 |
| `EVIDENCE_NOT_COVERING` | 证据不覆盖此字段 |
| `MISSING_EVIDENCE` | 有值但缺少证据支撑 |
| `TRACEABLE` | 有值有来源 |
| `STRUCTURED_BLOCK_DIRECT` | 直接来自结构块，可信度最高 |

### 5.3 字段级可信度评分

**现状**：只有 TRACEABLE/EMPTY 状态，没有可信度值。

**目标**：pricing 来自 PRICING_BLOCK 且与正文一致 → 高可信度；仅从正文推断 → 中等可信度。

### 5.4 sharedOutput / TaskNode.outputData 轻量化

**现状**：`extract_schema` 节点输出会携带 `downstreamEvidenceViews`，其中可能包含完整正文。`DagExecutor` 会把节点输出写入 `TaskNode.outputData`、`AgentContext.sharedState` 和缓存。并发任务增多时，容易带来 JVM 内存、Redis 缓存、数据库字段和 replay 反序列化压力。

**目标**：

- `extract_schema` 的共享输出不再携带完整 `DownstreamEvidenceView.content`；
- 节点输出只保留 `evidenceId / sourceUrls / qualitySignals / structuredBlock 摘要 / 字段结果 / issueFlags`；
- 如下游需要正文，必须通过 `evidenceId` 从正式证据存储回查；
- 为 `extract_schema` 输出增加大小上限，超限时自动降级为轻量投影；
- replay / report / analyzer 均消费轻量投影，避免把执行现场长正文扩散到全链路。

**建议边界**：

| 对象 | 是否允许携带长正文 | 用途 |
|---|---:|---|
| `EvidenceSource.fullContent` | 是 | 正式证据存储 |
| `ExtractorEvidenceInput` | 是 | extractor 内部一次性输入 |
| `ExtractResult / drafts` | 否 | 运行时正式结构化结果 |
| `DownstreamEvidenceView` | 否 | 下游轻量证据视图 |
| `TaskNode.outputData / sharedOutput` | 否 | 跨节点状态与回放 |

---

## 六、失败诊断层

### 6.1 失败分层更精确

**现状**：基本能挡住 0 业务字段（`NO_BUSINESS_FIELDS_EXTRACTED`），但不够细。

**目标**：6 层失败——

| 失败层 | 含义 | 归属 |
|---|---|---|
| `NO_USABLE_EVIDENCE` | 无任何可读证据 | extractor 入口 |
| `MODEL_OUTPUT_INVALID_JSON` | 模型 JSON 不合法 | extractor 重试 |
| `NO_BUSINESS_FIELDS_EXTRACTED` | 模型未抽出业务字段 | extractor 语义重试 |
| `FIELD_MISSING_EVIDENCE` | 有字段值但无证据 | extractor coverage |
| `LOW_QUALITY_EVIDENCE` | 证据质量不足 | extractor 质量门禁 |
| `DOWNSTREAM_CONSUMPTION_GAP` | extractor 成功但下游失败 | 跨节点诊断（不在 extractor 内） |

第 6 层 `DOWNSTREAM_CONSUMPTION_GAP` 是跨节点诊断层，不应放在 extractor 的失败判断中，而应由 workflow 层汇总各节点结果后统一判定。

**2026-06-22 状态补充**：workflow 第一版汇总已经落地到 `DagExecutor`。当前至少覆盖两类 reviewer 下游阻断：

1. 终审节点执行成功但 `passed=false`；
2. 初审节点执行成功、明确 `requiresHumanIntervention=true`，且任务因此停在 reviewer 人工补证据/改写策略阶段。

这说明 `DOWNSTREAM_CONSUMPTION_GAP` 已经不再只是计划概念，但它仍未完全泛化到所有 analyzer / writer / reviewer / delivery 失败形态。

---

## 七、长期架构方向

### 7.1 SchemaExtractorAgent 职责拆分

**现状**：`SchemaExtractorAgent` 同时承担证据读取、prompt 组装、LLM 调用重试、JSON 修复、coverage 生成、知识落库、输出拼装共 6+ 类职责。

**方向**（长期，不在当前迭代）：

- `ExtractorInputAssembler` — 证据读取与可用性过滤
- `ExtractorPromptBuilder` — prompt 组装
- `ExtractorLlmInvoker` — LLM 调用与重试
- `ExtractedSchemaNormalizer` — JSON 修复与字段归一
- `EvidenceCoverageBuilder` — coverage 生成
- `ExtractResultAssembler` — 输出拼装

### 7.2 ExtractorInputProvider 去 DB 耦合

**现状**：`SchemaExtractorAgent` 作为 DAG 执行节点，直接读取 `EvidenceSourceRepository`，并直接写入 `CompetitorKnowledgeRepository`。这让节点输入不再完全来自上游 State/Context，也让测试、恢复和重放都依赖底层数据库状态。

**与 5.1 的关系**：`5.1 ExtractResult vs CompetitorKnowledge` 收的是输出边界，`7.2 ExtractorInputProvider` 收的是输入边界。两者本质上是同一条链路的两侧：如果 P1 只收输出，不同步定义输入 Provider，extractor 仍然会绕过运行时正式边界直接读 DB，"两套世界"只会从输出侧缩小，但不会真正闭合。

**风险**：

- Agent 不再接近纯函数，单元测试需要 mock 多个 repository；
- 光看 DAG 节点输出无法完整解释 extractor 实际消费了哪些证据；
- rerun / resume 可能受历史 DB 残留影响；
- 存储介质变化会直接影响 Agent 实现。

**目标**：引入正式输入 Provider，把 DB 访问从 Agent 内部移出去——

```text
ExtractorInputProvider
  -> 根据 taskId / planVersionId / branchKey / nodeName 读取证据
  -> 过滤不可用证据
  -> 做质量排序、TopK、预算控制
  -> 生成 ExtractorInputPackage
  -> 交给 SchemaExtractorAgent 消费
```

**边界原则**：

- `SchemaExtractorAgent` 只消费 `ExtractorInputPackage`，不直接关心底层是 DB、缓存还是 replay 快照；
- Provider 负责证据读取、排序、截断和轻量投影；
- Agent 负责提取策略、LLM 调用、结果归一和输出；
- `CompetitorKnowledge` 写库也应逐步下沉到持久化端口或工作流收口层，避免 Agent 同时承担执行与落库职责。

**分阶段落地**：

- `P1`：先定义 `ExtractorInputProvider` 接口与 `ExtractorInputPackage`，并让 `SchemaExtractorAgent` 通过 Provider 取输入；即使 Provider 第一版内部仍调用 repository，也先把输入边界从 Agent 类中拔出来。
- `P2`：再把 Provider 内部的数据来源逐步从直接 repository 调用收口为正式端口，接入 replay、缓存、轻量投影与预算控制；同时推动 `CompetitorKnowledge` 写入职责继续下沉。

**结论**：`7.2` 不应被理解为纯长期重构。至少“接口定义 + Agent 接线”应该与 `5.1` 同批进入早期优先级，否则输出侧已经开始收口，输入侧仍然停留在旧边界上。

---

## 优先级建议

## 2026-06-21 P0 已完成事实

1. Prompt 已分层为 `structuredEvidence / qualitySignalGuidance / readableContent`，并通过 extractor 定向测试与全量单测回归。
2. 0 业务字段在正文可读时会执行一次业务语义重试；`llmClient.chatForJson` 的 `times(2)` 路径已有回归测试保护。
3. 结构块型证据不会因正文为空被入口过滤，`structuredBlocks / structuredPayload` 非空时可直接进入 extractor。
4. `schemaId / dimensions` 已进入 extractor Prompt，当前用于驱动字段提取重点与 schemaGuidance 文案。
5. `qualitySignals` 已翻译为提取指引，薄正文会显式标记 `THIN_CONTENT_ONLY` 诊断提示，而不是默默混入高质量正文区。

## 2026-06-22 P1 / P2 新进展

1. `ExtractorInputProvider / RepositoryExtractorInputProvider / ExtractorInputPackage` 已落地，extractor 主路径不再直接在 Agent 内部拼读 DB 证据。
2. Provider 第一版已前移结构块优先、来源类型优先、Prompt 总预算与 `PROMPT_BUDGET_SKIPPED` 可追溯跳过视图。
3. analyzer 已切换为 `ExtractResult.drafts` 优先、`CompetitorKnowledge TASK` 仅补空字段 fallback，并显式回写 `EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT`。
4. `ExtractSharedOutputSanitizer / ExtractSharedProjection` 已把 extractor shared output 瘦身为轻量投影，跨节点共享不再透传长正文。
5. `evidenceCoverage` 细化状态已经进入 analyzer / reviewer / report 相关代码与测试；第二轮已在 report overview / section / competitor 三层补出 `statusBreakdown`，保留粗粒度计数的同时暴露 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 等细状态分布。
6. task `50` 的真实链路已在 2026-06-22 通过：`extract_schema` rerun 后整条链路执行到终审通过，`qualityScore=91`、`qualityPassed=true`，说明主阻断已不再停留在 extractor/analyzer 主边界。
7. workflow 第二轮已把 `DOWNSTREAM_CONSUMPTION_GAP` 从 reviewer 两类阻断扩展到 analyzer / writer / reviewer 的 extractor-success 下游消费失败，但这仍不是所有下游失败形态的完全覆盖。

## 2026-06-22 第二轮衔接说明

1. 本轮没有回头重做 P0/P1 主体，而是继续收口剩余的 P2 缺口。
2. workflow 汇总从 reviewer 两类场景继续扩到 analyzer / writer / reviewer，明确 extractor 成功后的部分下游消费失败应归口为 `DOWNSTREAM_CONSUMPTION_GAP`。
3. 报告侧在保留 `TRACEABLE / MISSING_EVIDENCE / EMPTY` 粗粒度计数的同时，新增 `statusBreakdown` 暴露 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT` 等细状态分布。
4. task `50` 的基础 rerun 成功链路不再需要重复证明；当前 live 验证重点是补新状态命中样本。
5. 2026-06-22 本轮已启动本地 `9093` 服务并执行 task `50` 的 `extract_schema` 节点级 rerun；本次样本命中 `DOWNSTREAM_CONSUMPTION_GAP`，但未命中 `LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。

| 优先级 | 项目 | 改动范围 | 理由 |
|---|---|---|---|
| **P0** | 1.1 Prompt 输入分层 | prompt 模板 + buildCollectedContent | 最小改动，直接提升提取质量 |
| **P0** | 1.2 字段级提取指引 | prompt 模板 | 同上，与 1.1 同批做 |
| **P0** | 2.1 0 业务字段语义重试 | extractAndNormalize | 2026-06-21 已实施并通过自动化回归 |
| **P0** | 3.1 extractor 读 dimensions | doExecute + extractAndNormalize | 2026-06-21 已实施，schemaGuidance 已进入 Prompt |
| **P1** | 1.3 qualitySignals 翻译 | buildEvidenceCatalog | prompt 质量提升 |
| **P1** | 1.4 输出 Schema 强约束 | prompt 模板 | 减少模型自由发挥 |
| **P1** | 4.1 证据质量排序 | doExecute 过滤逻辑 | 简单排序，影响模型注意力 |
| **P1** | 4.3 Prompt 总预算控制与 TopK | prompt 输入组装 | 防止采集越多、提取越容易撑爆上下文 |
| **P1** | 5.1 ExtractResult vs CompetitorKnowledge 收口 | extractor + analyzer | 当前最大架构异味，需尽快明确可信边界 |
| **P1** | 5.4 sharedOutput / outputData 轻量化 | extractor + workflow 投影 | 防止长正文进入共享状态造成内存和持久化膨胀 |
| **P1** | 5.2 evidenceCoverage 状态细化 | buildCoverage | 让下游 reviewer 区分失败原因 |
| **P1** | 7.2 ExtractorInputProvider 接口定义与 Agent 接线 | extractor + 输入 Provider | 作为 5.1 的输入侧配套边界，先把 Agent 从直接读 DB 的实现细节中解耦 |
| **P2** | 3.2 Schema dimensions 结构升级 | AnalysisSchema 实体 + API | 需改 DB schema 和前端 |
| **P2** | 4.4 提取输入轻量投影 | extractor 输入/输出契约 | 需要新增输入对象并调整下游消费 |
| **P2** | 6.1 失败分层扩展 | extractor + workflow | 需配合 workflow 层改造 |
| **P2** | 7.2 Provider 内部数据源收口 | 输入 Provider + replay/cache/port | 第三轮已先落地 `ExtractorEvidenceSourcePort` 与 `REPOSITORY_BACKED_PORT`，第二阶段继续把 replay/cache 替换成新的来源端口实现 |
| **后续** | 2.2 分字段组提取 | 提取策略重构 | 增加 LLM 调用次数，先验证单次天花板 |
| **后续** | 2.3 正文智能分段 | buildCollectedContent | 实现成本高，structuredBlocks 已覆盖主要场景 |
| **后续** | 7.1 职责拆分 | 全类重构 | 长期方向，当前先做 prompt 和策略优化 |

---

## 相关文档

- [3.3 提取结构化架构规格](../specs/2026-06-21-extraction-structured-architecture-spec.md)
- [ExtractionStructured 链路诊断](../problem/ExtractionStructured.md)
- [3.2 继承基线](../problem/2026-06-20-extraction-structured-3.2-inheritance-baseline.md)
- [优化实施计划](../plan/2026-06-20-extraction-structured-optimization-plan.md)
- [3.2 搜索与采集阶段总结](../../search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md)
