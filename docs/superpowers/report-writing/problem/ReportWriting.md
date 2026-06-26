# ReportWriting 诊断

## 结论

1. Writer 当前真实停点不是“不会把缺口交给编排层”，而是“能识别章节引用缺口，但报告的持久化与交付载体还没有完整承接这些运行期事实”。`ReportWriterAgent` 已能产出 `writerEvidenceState / citationGapSeverity / sectionCitationGaps`，说明写作链路的主问题已经从协议接入转成运行态结果如何沉淀。
2. Writer 输出的 `citationGap / sourceUrls / evidenceState` 已经可以被 3.4 稳定消费。当前 `WriterSuggestionAssembler` 会把章节缺口统一转成 `AgentSuggestion`，并根据来源是否为空区分补证还是重写建议，因此 3.4 这里不是缺协议，而是缺更深的持久化与交付解释层。
3. 不改架构时，最多只能继续优化 Writer prompt、章节组织与 `SectionEvidenceBundle` 继承策略，降低误报、漏报和“正文成文但章节引用仍空”的概率；但它无法把弱上游证据写成强结论，也无法只靠 Writer 自己补齐交付侧缺失的 runtime 决策语义。

## 代码级证据

1. `ReportWriterAgent` 在生成正文后立即调用 `WriterCitationGapInspector.inspect(reportContent, sectionEvidenceBundles, sourceUrls)`，说明写作链路已经把引用缺口识别内建到主流程。
2. `ReportWriterAgent` 会向输出写入 `sourceUrls / writerEvidenceState / citationGapSeverity / sectionCitationGaps`，Writer 端的协作信号已经结构化。
3. `ReportWriterAgent.normalizeAnalysisPayload()` 会在 Analyzer 旧格式下回填 `sourceUrls` 并打上 `SOURCE_URLS_BACKFILLED`，说明 Writer 仍在承担一部分上游兼容与补束成本。
4. `ReportWriterAgent.ensureWriterSectionBundles()` 会补 `report_conclusion` 章节证据束，并在缺来源时把 `report` 记入 `missingFields`，表明 Writer 的真实停点仍与章节级证据束质量绑定。
5. `WriterCitationGap.normalized()` 默认把“无来源”收口成 `MISSING_SOURCE + ERROR`，把“有来源但不完整”收口成 `PARTIAL_SOURCE + HIGH`，与 3.4 协议词汇一致。
6. `WriterSuggestionAssembler` 只把章节引用缺口翻译成 `AgentSuggestion`，并按 `sourceUrls` 是否为空切换 `collect_sources / rewrite_report` 提示节点，证明 Writer -> 3.4 已有稳定接缝。
7. `SectionEvidenceBundle` 仍是 Writer 可直接消费的章节级证据边界；只要 bundle 粒度不够，Writer 最多只能报缺口，无法凭空补出强证据。
8. `Report` 实体当前只持久化 `content / summary / qualityIssues / evidenceCount` 等结果字段，没有沉淀 `writerEvidenceState / sectionCitationGaps`，这就是“链路内可识别、交付面难解释”的直接上限。
