# P3-4 To 4.x Execution Roadmap Progress - 2026-06-26

当前阶段：ReportWriting 链路正式方案收口（当前停止点：plan 已完成，未开始实现）
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告 / 回放主路径投影：已完成
- [x] 正式导出投影：已完成
- [x] SSE replay 轻量投影：已完成
- [x] 质检复核：已完成
- [x] ReportWriting 正式方案收口：已完成

## 执行计划

| Task | 核心目标 | 预期耗时 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- |
| Task 1 | 跑通 P3-4 Citation 三条指定回归，确认现状可作为后续基线 | 15 分钟 | 路线图文件、backend 测试环境可用 | 已完成 |
| Task 2 | 冻结 3.3 / 3.4 红线文档与协作协议清单，并完成协议回归验证 | 30 分钟 | Task 1 全部通过 | 已完成 |
| Task 3 | 产出 3.5 四份诊断文档，并回链到总路线图 | 90 分钟 | Task 2 完成 | 已完成 |
| Task 4 | 基于四份诊断形成收敛决策，判断 4.x / Tavily 路径 | 30 分钟 | Task 3 完成 | 已完成 |
| Task 5 | 若收敛结论要求进入 4.0，则补 runtime contract 设计与实施计划 | 60 分钟 | Task 4 结论为进入 4.0 | 条件未满足，暂不执行 |
| Task 6 | 若 4.0 完成且仍需推进，则补 4.1 动态 runtime 迁移计划 | 60 分钟 | Task 5 完成并确认继续 | 条件未满足，暂不执行 |
| Task 7 | 按收敛结论决定是否执行 Tavily Fast Lane 接入路径 | 45 分钟 | Task 4 或 Task 6 产出接入条件 | 条件未满足，暂不执行 |

## 执行进度

| Task | 内容 | 状态 |
| --- | --- | --- |
| Task 1 | P3-4 Citation 验证确认 | ✅ 已完成 |
| Task 2 | 3.3 / 3.4 红线冻结 | ✅ 已完成 |
| Task 3 | 3.5 四份诊断 | ✅ 已完成 |
| Task 4 | 收敛点决策 | ✅ 已完成 |
| Task 5 | 4.0 Runtime Contract | ⏳ 条件未满足 |
| Task 6 | 4.1 动态 Runtime 迁移 | ⏳ 条件未满足 |
| Task 7 | Tavily Fast Lane 路径决策与执行 | ⏳ 条件未满足 |

## 收敛后续执行进度

| 子任务 | 内容 | 状态 |
| --- | --- | --- |
| Delivery Audit 1 | 最近一次协作决策进入 `report / replay / export` 三条主路径 | ✅ 已完成 |
| Delivery Audit 2 | `ReportExportRenderer` 合并协作决策 `sourceUrls` 并补结构化导出 | ✅ 已完成 |
| Delivery Audit 3 | SSE replay 轻量协作决策摘要 | ✅ 已完成 |
| ReportWriting 1 | 新增 4.x 前收口 plan，限定稳定契约、持久化字段、查询投影、导出解释层和测试基线 | ✅ 已完成 |

📍 当前：ReportWriting 链路正式方案收口已完成，尚未开始代码实施
▶️ 下一步：按 `docs/superpowers/report-writing/plan/2026-06-26-report-writing-pre-4x-closure-plan.md` 选择是否进入实现；当前仍不进入 4.x、不做 Tavily、不补 `pendingActions`

## 验证结果

| 命令 | 结果 |
| --- | --- |
| `mvn -Dtest=WorkflowFactoryTest test` | PASS |
| `mvn -Dtest=CitationSuggestionAssemblerTest test` | PASS |
| `mvn -Dtest=OrchestrationDecisionServiceTest test` | PASS |
| `mvn "-Dtest=OrchestrationContractTest,CollaborationContractTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest" test` | PASS |
| `mvn -Dtest=ReportExportRendererOrchestrationDecisionTest test` | PASS |
| `mvn "-Dtest=ReportServiceTest#shouldExposeLatestOrchestrationDecisionInReportMainPath,TaskReplayProjectionServiceTest#shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath,ReportExportRendererOrchestrationDecisionTest" test` | PASS |
| `mvn -Dtest=TaskEventReplayServiceTest#shouldExposeLatestOrchestrationDecisionForReplayFrame test` | PASS |
| `mvn "-Dtest=ReportServiceTest#shouldExposeLatestOrchestrationDecisionInReportMainPath,TaskReplayProjectionServiceTest#shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath,ReportExportRendererOrchestrationDecisionTest,TaskEventReplayServiceTest#shouldExposeLatestOrchestrationDecisionForReplayFrame" test` | PASS |
| `git diff --check -- docs/superpowers/analysis-reasoning/problem/AnalysisReasoning.md docs/superpowers/report-writing/problem/ReportWriting.md docs/superpowers/conversation-collaboration/problem/ConversationCollaboration.md docs/superpowers/delivery-audit/problem/DeliveryAudit.md docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/specs/project-evolution-roadmap.md docs/superpowers/plan/2026-06-26-3.5-convergence-decision.md docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap-progress.md` | PASS（仅有 LF→CRLF warning，无格式错误） |

## 本次执行记录

- 2026-06-26 13:48：定位路线图文件实际位于 `docs/superpowers/plans/2026-06-26-p3-4-to-4x-execution-roadmap.md`，并完成与 `docs/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md` 的初步对照。
- 2026-06-26 13:49：完成 `WorkflowFactoryTest`、`CitationSuggestionAssemblerTest`、`OrchestrationDecisionServiceTest` 三条指定回归，确认 P3-4 Citation 当前实现可作为 Task 2 之后的稳定基线。
- 2026-06-26 13:51：完成 `project-evolution-roadmap.md` 与总蓝图的 3.3 / 3.4 红线冻结补记，统一 `AgentSuggestion / OrchestrationDecision / DecisionPolicyResult / DynamicPlanMutation / QualityDiagnosis / CitationCheckResult / EvidenceState / sourceUrls` 为正式协作协议词汇。
- 2026-06-26 13:51：完成 `OrchestrationContractTest`、`CollaborationContractTest`、`DecisionPolicyServiceTest`、`DecisionExecutorAdapterTest`、`OrchestrationDecisionServiceTest` 协议回归，确认 Task 2 冻结口径与现有实现一致。
- 2026-06-26 13:52：完成 `AnalysisReasoning.md`、`ReportWriting.md`、`ConversationCollaboration.md`、`DeliveryAudit.md` 四份 3.5 诊断文档，并回链更新总蓝图 3.5 索引。
- 2026-06-26 13:53：完成 `2026-06-26-3.5-convergence-decision.md`，结论为“暂不进入 4.x、暂缓 Tavily、优先深挖交付与审计链路”。
- 2026-06-26 18:10：新增 `docs/superpowers/delivery-audit/plan/2026-06-26-delivery-audit-orchestration-projection-plan.md`，把下一段工作收敛为“协作决策进入 report / export / replay / SSE 的只读投影”，明确不改动决策生成逻辑、不进入 4.x runtime。
- 2026-06-26 14:34：完成 `ReportExportRenderer` 的协作决策只读投影补齐，Markdown / HTML 新增“协作决策摘要”，JSON 导出新增 `orchestrationDecision` 结构化对象，并把协作决策 `sourceUrls` 合并进正式导出追溯链。
- 2026-06-26 14:34：跑通 `ReportExportRendererOrchestrationDecisionTest`，确认 export 主路径已闭环展示 `WAIT_FOR_HUMAN / MISSING_SOURCE / sourceUrls`。
- 2026-06-26 14:34：跑通 `ReportServiceTest#shouldExposeLatestOrchestrationDecisionInReportMainPath`、`TaskReplayProjectionServiceTest#shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath` 与 `ReportExportRendererOrchestrationDecisionTest` 三条聚焦回归，确认 report / replay / export 三条主路径协作决策投影一致。
- 2026-06-26 14:50：完成 `TaskEventReplayService` 的 SSE replay 协作决策轻量投影，`TaskReplayFrame` 新增 `latestOrchestrationDecision`，并从最近 `DIAGNOSIS` 事件中提取 `decisionType / actionType / evidenceState / sourceUrls` 稳定摘要。
- 2026-06-26 14:50：跑通 `TaskEventReplayServiceTest#shouldExposeLatestOrchestrationDecisionForReplayFrame` 与四条聚焦回归，确认 report / replay / export / SSE 四条主路径的协作决策只读投影已闭环。
- 2026-06-26 15:02：回到总路线图复核 `pendingActions` 是否仍属当前必补缺口。结论为：现有主路径已经能通过 `decisionType / actionType / evidenceState / sourceUrls` 与既有 `recommendedAction / recoveryAdvice` 解释当前停点和下一步动作，`pendingActions` 现阶段仍保留为 checkpoint 内部恢复语义，暂不继续扩大只读投影范围；若后续 live 场景再次暴露“等待补证结果”和“等待人工处理”不可区分，再单开补丁处理。
- 2026-06-26：新增 `docs/superpowers/report-writing/plan/2026-06-26-report-writing-pre-4x-closure-plan.md`，正式收口 ReportWriting 的 4.x 前可复用资产边界：稳定契约、持久化字段、查询投影、导出解释层和测试基线；明确当前不进入 4.x、不做 Tavily、不补 `pendingActions`、不大改 Writer 或 Analyzer。
