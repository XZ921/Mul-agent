# DeliveryAudit 诊断

## 结论

1. 交付与审计当前真实停点不是“完全没有审计底座”，而是“搜索/采集审计已经很强，但协作决策审计还没有被稳定投影到报告、导出和回放主路径”。`ReportService`、导出渲染器和 replay/SSE 现在已经能解释证据、质检、search audit、collection audit，却还不能系统性解释 `OrchestrationDecision / evidenceState / pendingActions`。
2. `Report / Export / Replay` 对 `OrchestrationDecision` 和 `evidenceState` 只能算部分可读。它们能聚合 `sourceUrls`，也能回放通用任务事件，但尚未形成类似 `SearchAuditSnapshot / CollectionAuditSnapshot` 那样的协作编排专属稳定视图。
3. 不改架构时，最多只能在 `ReportService / ReportExportRenderer / TaskEventReplayService` 上继续补只读投影，把现有决策事件摘要、`evidenceState` 和来源链接展示出来；但仅靠这些服务改造，仍补不出 collaboration runtime 的 owner、checkpoint 语义和多轮决策恢复口径，这正是后续 4.x 需要处理的底座问题。

## 代码级证据

1. `ReportService.getReport()` 已聚合报告正文、证据、知识快照、`SearchAuditOverview`、`TaskRagAudits`、`SectionEvidenceBundle`、`ReportDiagnosisInfo` 与 `RevisionPlan`，说明交付主路径已有丰富事实层。
2. `ReportService.buildDeliverySummary()` 与 `buildAuditSummary()` 当前只围绕 blocker、evidence gap、search audit、task RAG 生成业务摘要，没有正式承接 `OrchestrationDecision` 或 `evidenceState`。
3. `ReportExportRenderer` 的 Markdown / HTML / JSON 导出都只消费 `deliverySummary / auditSummary / evidenceEntryPoint / evidences / sourceUrls`，尚未导出协作决策轨迹。
4. `TaskEventReplayService.planReplay()` 目前只规划 `snapshotEvent + replayEvents`，并复用最近事件补偿，不提供协作决策专属回放视图。
5. `TaskSseHub` 只缓存和广播通用 `TaskStreamEvent`，它解决的是事件游标与最近事件重放，不负责把编排决策翻译成业务可读审计对象。
6. `SearchAuditSnapshot` 已有 `summary / executionTrace / replayTimeline / attemptedTargets / selectedTargets / sourceUrls`，说明搜索审计已经具备正式“快照 + 时间线”模型。
7. `CollectionAuditSnapshot` 已有 `summary / results / replayTimeline / recoveryCheckpoint / sourceUrls`，说明采集审计也已经具备正式“结果 + 恢复锚点”模型。
8. 正因为 search/collection 都已有稳定快照，而协作决策层还没有对应的 delivery/audit 投影，所以当前停点更像“协作审计底座未成型”，不是单纯的报告页字段不足。
