# AnalysisReasoning 诊断

## 结论

1. 当前真实停点已经不再是“Analyzer 没接上 3.4 协议”，而是“Analyzer 已能产出受控缺口信号，但分析结论仍强依赖上游 draft、coverage 与 evidence view 的回填质量”。`CompetitorAnalysisAgent` 会主动补 `sourceUrls`、章节证据束和结论 bundle，这说明主问题已经转成链路内分析密度与证据聚合质量，而不是协议缺席。
2. 这不是 3.4 协议接缝的主阻塞。`AnalysisResult` 已显式携带 `sourceUrls`、`missingAnalysisDimensions`、`analysisGapSeverity`、`analysisEvidenceState` 与 `sectionEvidenceBundles`；后续只要继续沿 `AgentSuggestion / OrchestrationDecision` 走，编排层已经有统一消费口。
3. 不改架构时，最多只能继续收紧 Analyzer prompt、缺口判定口径和章节证据聚合规则，减少“有来源但结论仍空”与“章节 bundle 过粗”的问题；一旦上游 `CompetitorKnowledgeDraft / SectionEvidenceBundle` 自身没有可追溯 coverage，单改分析链路不能把它升级成更强的 runtime 协作表达。

## 代码级证据

1. `AnalysisResult` 已把 `sourceUrls / missingAnalysisDimensions / analysisGapSeverity / analysisEvidenceState / sectionEvidenceBundles` 定义为正式输出字段。
2. `CompetitorKnowledgeDraft` 仍是 Analyzer 的关键输入载体，继续承接 `sourceUrls / evidenceCoverage / sectionEvidenceBundles`，说明分析质量直接受 3.3 输出边界约束。
3. `CompetitorAnalysisAgent` 会把 `knowledge / draft / downstreamEvidenceViews` 三路 `sourceUrls` 汇总，并在模型没给来源时打上 `SOURCE_URLS_BACKFILLED`，证明当前链路仍在靠回填兜底。
4. `CompetitorAnalysisAgent.collectMissingAnalysisDimensions()` 仍主要按核心分析段落是否成形来判缺口，停点已经落在“分析内容是否写出来”，不是“协议能不能传过去”。
5. `CompetitorAnalysisAgent.resolveAnalysisEvidenceState()` 明确把“无来源”判成 `MISSING_SOURCE`、把“有来源但仍缺维度”判成 `PARTIAL_SOURCE`，3.4 所需的证据状态语义已经接上。
6. `CompetitorAnalysisAgent.buildSectionEvidenceBundles()` 会从 draft bundle、知识 coverage 和 evidence view 重新拼章节级证据束，并在缺口场景打 `SECTION_EVIDENCE_GAP`，说明 Analyzer 当前还承担较重的链路内补束职责。
7. `CompetitorAnalysisAgent.buildConclusionBundle()` 在 `sourceUrls` 为空时直接把 `conclusion` 记入缺口，表明总览结论仍受来源可追溯性硬约束。
8. `AgentSuggestion.normalized()` 会稳定保留 `sourceUrls / evidenceState`，说明 3.4 的正式协作协议并不是 Analyzer 当前的主停点。
