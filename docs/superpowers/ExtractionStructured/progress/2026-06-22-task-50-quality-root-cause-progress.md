# Task 50 质量闭环失败根因排查记录

## 当前阶段

当前阶段：[Provider 侧修复后已定位并修复 extractor 字符串数组归一化缺口]
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成本记录并追加二次 rerun 结论
- [x] 质量校验：已完成定向与链路相关回归验证

## 问题现象

- taskId: `50`
- rerun node: `extract_schema`
- rerun 后任务状态：`FAILED`
- 失败信息：`质量闭环未达到通过条件，请检查评审结果`
- 关键质量节点：所有节点执行状态为 `SUCCESS`，但 `quality_check_final.passed=false`
- 质量分类：`DOWNSTREAM_CONSUMPTION_GAP`

## 根因判断

本次失败不是搜索与采集完全失败导致的。

现场证据显示，采集阶段已经拿到 Notion 的官方、文档、定价等来源，其中包括：
- `https://notion.so/pricing`
- `https://notion.so/plans`
- `https://notion.so/enterprise`
- `https://notion.so/help`

真正的问题出现在 extractor 输入 Provider 阶段：`RepositoryExtractorInputProvider.selectPromptEvidence(...)` 先按结构化证据排序，再按来源类型排序。`https://notion.so/help` 因为带有 `JSON_LD_METADATA_HIT` 结构化块，被排到最前，并在 `4000` 字符 prompt 预算内吃满预算，导致定价页被放入 `skippedEvidence`，没有进入 `readableEvidence`。

因此 extractor 只能看到 help/docs 证据，无法稳定抽取 `pricing`、`strengths`、`weaknesses` 等字段，最终报告出现空字段，质量闭环被 reviewer 判定为 `DOWNSTREAM_CONSUMPTION_GAP`。

## 本轮修复

- 在 `RepositoryExtractorInputProvider` 增加核心来源类型的 prompt 多样性预留预算。
- 当前核心来源类型包括：`OFFICIAL`、`DOCS`、`PRICING`、`API_DATA`。
- 当某条超长 structured docs 证据会吃满预算时，Provider 会为尚未进入 prompt 的核心来源类型预留最小正文额度，避免定价、官网或 API 数据完全缺席。
- 同时修正 prompt 预算计算：截断标记也计入预算，保证账面 `usedPromptEvidenceChars` 与实际进入下游的正文长度一致。

## 二次 rerun 新阻断

Provider 多样性修复后，task `50` 从 `extract_schema` rerun 的现场结果发生变化：

- 任务状态：`STOPPED`
- 当前阶段：`竞品结构化提取`
- 任务错误：`存在等待人工处理的节点，请确认后继续`
- `extract_schema` 节点状态：`WAITING_INTERVENTION`
- 节点错误：`未能抽取出可用的竞品知识`

这说明原先“pricing 被 Provider 预算挤出 prompt”的问题不再是本次唯一阻断；链路已经推进到 extractor 自身的输出规整阶段。

日志中的直接异常为：

```text
Cannot construct instance of StrengthWeaknessItem ... no String-argument constructor/factory method to deserialize from String value ('深度集成 AI 协作能力')
```

根因是模型把 `strengths / weaknesses` 返回成字符串数组：

```json
{
  "strengths": ["深度集成 AI 协作能力"],
  "weaknesses": ["企业采购价格透明度有限"]
}
```

而 `SchemaExtractorAgent.buildKnowledgeDraft(...)` 会把这两个字段转换成 `List<StrengthWeaknessItem>`。旧逻辑直接对数组元素执行 `objectMapper.convertValue(item, StrengthWeaknessItem.class)`，Jackson 无法把裸字符串构造成 DTO，导致落库前后的 draft 组装失败，最终 extractor 报告“未能抽取出可用的竞品知识”。

## 二次修复

- 在 `SchemaExtractorAgent.normalizeSchema(...)` 中，统一把 `strengths / weaknesses` 的字符串数组项规整成 `{ "point": "...", "sourceUrls": [...] }`。
- 对对象形态的 `strengths / weaknesses`，继续保留模型返回内容，并归一化 `evidenceIds / sourceUrls` 字符串列表。
- 当字段项缺少 `sourceUrls` 时，回填顶层 `sourceUrls`，确保下游分析、撰写和质检仍可追溯来源。
- `buildCoverage(...)` 改为递归收集字段内部 `sourceUrls`，避免数组项已经有来源但 coverage 只读取字段根节点 `sourceUrls` 时误判为缺证据。

## 三次 live rerun 结果

样本目录：`docs/superpowers/ExtractionStructured/progress/live-string-sw-rerun-20260622-165050/`

本次从 `extract_schema` rerun 后，阻断点已经继续后移：

- `extract_schema`: `SUCCESS`
- `analyze_competitors`: `SUCCESS`
- `write_report`: `SUCCESS`
- `quality_check`: `SUCCESS`，但 `passed=false`、`requiresHumanIntervention=true`、`autoRewriteAllowed=false`
- 任务状态：`STOPPED`
- 当前停止信息：`初审未通过且需要人工介入，请补充证据或调整策略后继续`

这次不再是 extractor 运行失败，也不是搜索采集完全失败。新的质量阻断来自 extractor 输出仍存在字段级可追溯缺口：

- `summary / positioning / targetUsers` 有业务值，但 coverage 为 `MISSING_EVIDENCE`，原因是模型只给了顶层 `sourceUrls`，没有给字段级 `evidenceIds/sourceUrls`。
- `strengths` 对象中模型把优势正文放在 `description`，而 DTO 只消费 `point`，导致 shared draft 里的 `strengths[].point=null`。
- `weaknesses` 为空并被标为 `EMPTY`，这可能是真实证据不足，也可能需要后续任务补证据；本次不把空短板伪装成有值。

## 三次修复

- `strengths / weaknesses` 对象型条目如果缺少 `point`，从 `description / name / title / value` 中回填 `point`。
- `summary / positioning / targetUsers` 这类标量综合字段在“模型明确提供顶层 `sourceUrls`”时，可继承顶层来源作为字段级 coverage 来源。
- 如果顶层 `sourceUrls` 是系统兜底回填而不是模型返回，则仍保留 `MISSING_EVIDENCE`，避免把无幻觉兜底来源误当字段证据。
- 新增多证据回归测试，覆盖 task `50` live 中的标量字段 coverage 与 `strengths.description -> point` 归一化。

## 验证结果

- 红灯复现：
  - `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest#shouldKeepPricingEvidenceWhenLargeStructuredDocsWouldConsumePromptBudget" test`
  - 修复前失败，表现为 `evidenceCatalog` 只有 help/docs，没有 pricing。
- 二次红灯复现：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldNormalizeStringStrengthsAndWeaknessesInsteadOfFailingDraftConversion" test`
  - 修复前失败，表现为 `StrengthWeaknessItem` 无法从字符串反序列化。
- 二次定向验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldNormalizeStringStrengthsAndWeaknessesInsteadOfFailingDraftConversion" test`
  - 结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- extractor 全量验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test`
  - 结果（二次修复后）：`Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`
- 三次红灯复现：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldBackfillScalarCoverageAndStrengthPointFromDescription" test`
  - 修复前失败，先表现为 `strengths.point=null`；扩展到多证据后表现为 `summary` coverage 仍是 `MISSING_EVIDENCE`。
- 三次定向验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest#shouldBackfillScalarCoverageAndStrengthPointFromDescription" test`
  - 结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- extractor 全量验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest" test`
  - 结果（三次修复后）：`Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`
- 定向 Provider 验证：
  - `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest" test`
  - 结果：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- extractor + Provider 关联验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest,RepositoryExtractorInputProviderTest" test`
  - 结果：`Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`
- 链路相关回归验证：
  - `mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest,ReportServiceTest,DagExecutorTest" test`
  - 结果（二次修复后）：`Tests run: 59, Failures: 0, Errors: 0, Skipped: 0`
- 链路相关回归验证：
  - `mvn -pl backend "-Dtest=SchemaExtractorAgentTest,RepositoryExtractorInputProviderTest,ReportServiceTest,DagExecutorTest" test`
  - 结果（三次修复后）：`Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`
- 空白检查：
  - `git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProvider.java backend/src/test/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProviderTest.java`
  - 结果：无 diff-check 错误，仅有 Git CRLF 提示。
- 二次空白检查：
  - `git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
  - 结果：无 diff-check 错误，仅有 Git CRLF 提示。
- 三次空白检查：
  - `git diff --check -- backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java docs/superpowers/ExtractionStructured/progress/2026-06-22-task-50-quality-root-cause-progress.md`
  - 结果：无 diff-check 错误，仅有 Git CRLF 提示。

## 后续建议

- 下一步可重新启动本地服务后，对 task `50` 再从 `extract_schema` rerun 一次，确认 `summary / positioning / targetUsers` 不再因为顶层来源未传播而被标为 `MISSING_EVIDENCE`，同时确认 `strengths[].point` 不再为空。
- 如果仍失败，应优先看 `quality_check` 是否还因为 `weaknesses=EMPTY` 或报告正文引用问题要求人工介入。
- 如果字段已补齐但质量仍失败，再检查 reporter 与 reviewer 对章节证据覆盖的消费逻辑。
