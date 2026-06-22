# 2026-06-22 reviewer 误判收敛与终审复验跟进

## 当前阶段

当前阶段：P2 终审 reviewer 问题归一化收敛与 live 复验
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质量校验：已完成本轮 reviewer 回归与 `quality_check_final` 局部复验

## 这次做了什么

1. 继续执行 `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`，聚焦 P2 task `50` 终审仍未闭环的问题。
2. 从 live 快照确认 reviewer 新一轮真实根因：
   - 不是 JSON 解析失败；
   - 主要是 reviewer 对两类情况仍然给出阻断级结果：
     - `structuredBlocks=0` 但正文可读、质量分高的页面被误判；
     - 终审 LLM 输出的泛化 issue（`UNKNOWN / missing_structured_evidence`）没有和“显式保守降级”规则对齐。
3. 在 [QualityReviewAgentTest.java](</e:/java_study/Mul-agnet/backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java>) 先补了 2 条回归测试：
   - `shouldNotDiagnoseStructuredEvidenceGapForReadableHighScoreEvidenceWithoutFailureSignals`
   - `shouldSuppressGenericLlmIssueWhenAdviceSectionAlreadyUsesExplicitDowngrade`
4. 在 [QualityReviewAgent.java](</e:/java_study/Mul-agnet/backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java>) 做了两处最小修复：
   - `shouldDiagnoseStructuredEvidenceGap(...)` 改为只在明确失败信号、`failureKind` 或低质量分时才升级成 `missing_structured_evidence`；
   - 新增 LLM issue 过滤逻辑：如果章节里的判断句都已经带 `[证据：EID]` 或显式保守降级，则过滤 `UNKNOWN / unsupported_claim / missing_*` 这类泛化终审 issue，避免把已合规的保守表达再次打回。
5. 完成 reviewer 本地回归：
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest#shouldNotDiagnoseStructuredEvidenceGapForReadableHighScoreEvidenceWithoutFailureSignals" test`
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest#shouldDiagnoseMissingStructuredEvidenceInsteadOfOnlyUnsupportedClaim" test`
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest#shouldSuppressGenericLlmIssueWhenAdviceSectionAlreadyUsesExplicitDowngrade" test`
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest#shouldAcceptExplicitConservativeDowngradeWhenAdviceClaimLacksCitation" test`
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest" test`
6. 启动本地 `9093` backend 做两轮 `quality_check_final` 局部 rerun，并保留快照：
   - 第一轮：`docs/superpowers/ExtractionStructured/progress/live-task-50-final-review-rerun-20260622-114559`
   - 第二轮（包含最新 issue 归一化修复）：`docs/superpowers/ExtractionStructured/progress/live-task-50-final-review-rerun-20260622-115356`

## 本轮结果

第二轮 live 复验后：

1. `quality_check_final` 仍为 `SUCCESS`，task `50` 仍整体 `FAILED`，失败原因仍是“质量闭环未达到通过条件”。
2. 但终审诊断已经收敛：
   - `score` 从上一轮复验中的 `11` 提升到 `18`；
   - 阻断级诊断从 `7` 条降到 `4` 条；
   - 之前那类由后端规则直接产出的“高质量可读页面却被当成 structured evidence gap”的误杀已被去掉；
   - 建议/结论段因为“已显式保守降级”而产生的一部分泛化 LLM issue 也被压掉了。
3. 当前剩余主 blocker 更清晰地集中在两类：
   - `coverage_gap` 仍然存在：`产品概览 / 市场定位 / 目标用户 / 定价策略 / 短板与风险`
   - 终审 LLM 仍对“整体证据引用 / 结构化证据覆盖 / 报告整体”给出 `UNKNOWN` 类 ACTIONABILITY blocker，说明 writer 产物虽然更保守了，但还没有真正把证据闭环写到 reviewer 可接受的程度。

## 接下来要做什么

1. 继续下钻 `coverage_gap` 的上游来源，优先看 `extract_schema` 当前 `evidenceCoverage` 为什么仍把 `summary / positioning / targetUsers` 判成 `MISSING_EVIDENCE`，以及 `pricing / weaknesses` 仍是 `EMPTY`。
2. 评估是优先修 extractor 的字段级证据回填，还是在 analyzer / writer 阶段把已有可追溯证据更稳定地写回对应章节。
3. 如果继续 live 复验，优先顺序建议为：
   - 先改 extractor / analyzer / writer 其中一个明确根因；
   - 再从 `rewrite_report` 或 `quality_check_final` 做节点级 rerun；
   - 避免整条链路全量重跑。

## 还剩什么没做

1. 还没有完成 P2 的 `evidenceCoverage` 新状态扩展：`LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。
2. 还没有把 task `50` 的 `coverage_gap` 真正收口到可通过终审的状态。
3. 还没有把 analyzer / writer 的真实上游证据闭环问题彻底定位清楚，目前只是把 reviewer 误判和一部分 LLM issue 噪音压缩掉了。
4. 还没有提交代码；按协作约定继续保留在 `master` 工作区，由用户自行提交。
