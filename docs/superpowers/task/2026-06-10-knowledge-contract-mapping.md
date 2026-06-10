# 2026-06-10 Knowledge Contract Mapping

## 背景

- `phase4a` 的目标是把 knowledge 侧跨模块输出收口到稳定入口，而不是继续扩散新的共享 DTO。
- 当前 `workflow.contract` 目录里的部分对象虽然仍挂在 workflow 命名空间下，但从语义上已经承担 knowledge-intelligence 的对外契约职责。
- 本文档用于把这些 legacy contract 的未来归属、阶段边界和强制保留字段写死，避免后续 `phase4b`、`phase5` 再新增一套平行 knowledge contract。

## Contract 归属表

| Legacy Contract | Future Owner | Phase | Notes |
| --- | --- | --- | --- |
| `ExtractResult` | `knowledge-intelligence` | `phase4a` | 由 `SchemaExtractorAgent` 产出，代表抽取阶段聚合后的任务级知识输出；必须保留 `sourceUrls`、`issueFlags`、`evidenceFragments`、`sectionEvidenceBundles`，继续作为分析阶段可追溯输入；不允许再新增并行的 `ExtractKnowledgeResult` / `KnowledgeExtractResult`。 |
| `CompetitorKnowledgeDraft` | `knowledge-intelligence` | `phase4a` | 由 `SchemaExtractorAgent` 产出，代表单竞品知识草稿投影；必须保留 `sourceUrls`、`issueFlags`、`evidenceFragments`、`sectionEvidenceBundles`、`evidenceCoverage`，继续承担字段级证据覆盖语义；不允许再新增第二套 draft contract。 |
| `AnalysisResult` | `knowledge-intelligence` | `phase4a` | 由 `CompetitorAnalysisAgent` 产出，代表写作/报告前的结构化分析结果；必须保留 `taskRagContext`、`sourceUrls`、`issueFlags`、`evidenceFragments`、`sectionEvidenceBundles`，继续承担分析阶段证据聚合与运行态审计语义；不允许再新增平行的 analysis knowledge contract。 |

## 当前代码依据

### `ExtractResult`

- 定义位置：`backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/ExtractResult.java`
- 当前语义：
  - 承接抽取阶段的任务级聚合输出。
  - 已显式包含 `sourceUrls`、`issueFlags`、`evidenceFragments`、`sectionEvidenceBundles`。
- 当前生产者：
  - `SchemaExtractorAgent` 在抽取完成后统一聚合 `drafts`、来源链接、证据片段和章节证据束，再输出 `ExtractResult`。

### `CompetitorKnowledgeDraft`

- 定义位置：`backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/CompetitorKnowledgeDraft.java`
- 当前语义：
  - 承接单竞品结构化知识草稿。
  - 已显式包含 `sourceUrls`、`evidenceFragments`、`sectionEvidenceBundles`、`issueFlags`、`evidenceCoverage`。
- 当前生产者：
  - `SchemaExtractorAgent` 在 `buildKnowledgeDraft(...)` 中把抽取结果规范化后投影成稳定 draft。

### `AnalysisResult`

- 定义位置：`backend/src/main/java/cn/bugstack/competitoragent/workflow/contract/AnalysisResult.java`
- 当前语义：
  - 承接分析阶段对 Writer / 报告接口暴露的结构化结果。
  - 已显式包含 `taskRagContext`、`sourceUrls`、`issueFlags`、`evidenceFragments`、`sectionEvidenceBundles`。
- 当前生产者：
  - `CompetitorAnalysisAgent` 在 `normalizeAnalysisResult(...)` 中统一处理字段漂移、来源回填和证据缺口，再输出 `AnalysisResult`。

## 字段保留约束

- `sourceUrls`：
  - 三个 contract 都视为强制可追溯字段。
  - 后续若新增 knowledge 字段，不能削弱 `sourceUrls` 的存在性或把溯源责任推回调用方。
- `issueFlags`：
  - 三个 contract 都要持续承接缺口、回填、字段漂移等问题标记。
  - 不允许在下游重新发明另一套质量/缺口标记集合。
- `evidenceFragments` 与 `sectionEvidenceBundles`：
  - 三个 contract 都要持续承接证据片段与章节证据束。
  - 下游若需要更多展示字段，应扩展现有结构，而不是新建平行 evidence contract。
- `taskRagContext`：
  - 当前只在 `AnalysisResult` 中作为运行态审计与报告聚合字段保留。
  - 不允许把完整检索对象直接塞进 `workflow.contract` 或 `AgentContext` 替代这个摘要字段。
- `evidenceCoverage`：
  - 当前由 `CompetitorKnowledgeDraft` 承担字段级覆盖摘要职责。
  - 若分析/写作需要字段覆盖信息，应从现有 draft / 落库知识继续传递，不新增平行 coverage DTO。

## Phase 4A 边界

1. `phase4a` 只确认 knowledge contract 的 owner 和稳定字段，不做 `workflow.contract` 到新包路径的大迁移。
2. 在 `phase4a`、`phase4b`、`phase5` 串行主线里，不允许新增 `ExtractKnowledgeResult`、`KnowledgeDraftV2`、`KnowledgeAnalysisResult` 一类平行 contract。
3. 若后续确实需要新增 knowledge 字段，优先扩展本文定义的既有 contract，并同步保持 `sourceUrls` 可追溯语义。
4. `BaseAgent`、`DagExecutor`、analysis-intelligence 拆分不属于本文档范围，避免借 contract mapping 之名提前扩大改造面。

## 后续迁移约束

- 当 future owner 真正落到独立模块/包路径时，应以本文档为唯一映射基线，不再重新讨论 owner 归属。
- 包路径迁移属于后续 modularization 阶段动作；迁移前，`workflow.contract` 仍作为 legacy 宿主存在，但语义 owner 已视为 `knowledge-intelligence`。
- 任何迁移都必须保持：
  - `sourceUrls` 不丢失
  - `issueFlags` 语义不降级
  - `evidenceFragments` / `sectionEvidenceBundles` 不拆散
  - `AnalysisResult.taskRagContext` 继续保留运行态审计用途
