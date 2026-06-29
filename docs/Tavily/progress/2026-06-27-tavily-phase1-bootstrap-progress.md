# Tavily Phase 1 Bootstrap 进度 - 2026-06-27

当前阶段：Phase 1 bootstrap 两批工程实现已完成，`9093` 真实联调也已完成；当前剩余问题已经从 “citation 缺 sourceUrls 卡死” 后移到 “质量终审未通过”。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [ ] 质检复核：执行中

## 执行计划

| 步骤 | 核心目标 | 预期耗时 | 前置依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| 1 | 复核 `docs/Travily/tavily-phase1-bootstrap-execution-plan.md` 与当前工程差异，确认第一批/第二批剩余任务 | 20 分钟 | 无 | 已完成 |
| 2 | 完成 Task 1-4：补 request-mode、bootstrap planner、Phase 1 主链路、审计兼容与黄金路径 | 2-3 小时 | 步骤 1 | 已完成 |
| 3 | 完成 Task 5-6：补候选预算裁剪、per-domain 软平衡、prefetched-first 与默认并发配置 | 1-2 小时 | 步骤 2 | 已完成 |
| 4 | 补 `TavilyPhase1BootstrapRealSmokeTest` 骨架并准备真实联调入口 | 30 分钟 | 步骤 2 | 已完成 |
| 5 | 运行 `9093` 真实 Tavily API smoke，记录抖音 / B 站基线结果并归档 | 30-60 分钟 | `TAVILY_API_KEY`、本地 live 服务 | 已完成 |
| 6 | 基于真实任务停点继续收口 citation / reviewer / final quality blocker | 1-2 小时 | 步骤 5 | 执行中 |

## 已完成内容

1. 完成 Task 1：
   - 新增 `SearchRequestPhase`
   - `SearchSourceRequest` 增加 `requestPhase`
   - `RoutingSearchSourceProvider.search(SearchSourceRequest)` 复用旧 provider 路由骨架，保留 `primarySatisfied`、fail-open 与 preferred provider 过滤语义
2. 完成 Task 2：
   - 新增 `TavilyBootstrapDecision`、`TavilyBootstrapPlanner`
   - `SearchExecutionCoordinator` 插入 `TAVILY_BOOTSTRAP_ENRICH`
   - 弱入口候选会在 verify 前触发 Tavily bootstrap，失败时按 fail-open 继续
3. 完成 Task 3：
   - `TavilyFastLaneProvider` 根据 `requestPhase` 区分 `TAVILY_PHASE1_BOOTSTRAP` 与 `TAVILY_FAST_LANE`
   - `CandidateOwnershipPolicy` 识别 `BOOTSTRAPPED` 为运行期增强候选
   - `SourceCandidateRanker` 补重复 URL 的 Tavily 元数据与 `sourceUrls` 合并
4. 完成 Task 4：
   - `TavilyFastLaneAudit` 增加 `queryOrigins`、`bootstrapTriggered`
   - `SearchExecutionCoordinator` / `SearchAuditSummary` / 快照兼容测试已打通 bootstrap 审计聚合
   - 保留旧 stepCode `BROWSER_SUPPLEMENT_SEARCH`，但展示语义升级为“运行期补源”
5. 完成 Task 5-6：
   - `SearchRuntimePolicy` / `SearchPolicyResolver` 增加 bootstrap、supplement、candidate-pool、per-domain 预算
   - `SearchExecutionCoordinator` 对 bootstrap / supplement 结果进行预算裁剪与总池上限控制
   - `CollectionExecutionProperties` 默认并发调整为 `3`，增加 `prioritizePrefetchedPackages`
   - `CollectionExecutionCoordinator` 支持 prefetched-first 调度，并保持返回结果顺序稳定
6. 完成 Task 7 的工程收口部分：
   - `SearchAndCollectionGoldenMasterTest` 增加 Phase 1 bootstrap 黄金路径
   - 新增 `backend/src/test/java/cn/bugstack/competitoragent/integration/TavilyPhase1BootstrapRealSmokeTest.java`
7. 完成 citation 真链修复收口：
   - `CitationClaimExtractor` 修复为“先抽 evidenceId，再做 claim 文本归一化”，避免 `[证据：E001]` 被提前剥掉
   - `CitationAgent` 在缺失 citation 时会合并 writer fallback `sourceUrls`
   - 有 fallback 源时 `evidenceState` 改为 `PARTIAL_SOURCE`，不再误报 `MISSING_SOURCE`
8. 完成 `9093` 真实联调第一轮基线确认：
   - `task 64`（2026-06-27 19:58 创建，旧进程基线）已经证明 Tavily Phase 1 bootstrap 命中，但任务仍卡在 `citation_check -> WAIT_FOR_HUMAN / MANUAL_REVIEW`
   - 该旧基线在完成证据记录后已从任务列表删除，避免继续混淆新旧进程结果
9. 完成 `9093` 真实联调第二轮修复验证：
   - `task 65`（2026-06-27 20:27 创建）先复现旧停点
   - 重启 `9093` 新进程后，对 `citation_check` 执行 rerun，节点转为 `SUCCESS`
   - 任务停点从 citation 后移到 `quality_check`，说明 citation sourceUrls 修复已在真实链路生效
10. 完成 `9093` 真实联调第三轮全新样本验证：
   - 删除过时 `task 64` 后，新建 `task 66`（2026-06-27 20:42 创建）并从头执行
   - 四个 collector 节点都出现 `TAVILY_BOOTSTRAP_ENRICH`，其中 bootstrap 新增候选数分别为：抖音 OFFICIAL `+2`、抖音 DOCS `+2`、B 站 OFFICIAL `+1`、B 站 DOCS `+2`
   - 四个 collector 最终都以 `补源方式=NONE` 成功完成，说明 Phase 1 bootstrap 已经把候选增强前移到 verify 之前，真实链路不再依赖 runtime supplement 才能起效
   - `task 66` 中 `citation_check -> quality_check -> rewrite_report -> citation_check_revision -> quality_check_final` 全部实际执行完成，任务最终在 `quality_check_final` 后以 `FAILED` 收口，不再停在 citation 环节

## 验证记录

1. 第一批主链路定向回归：
   - `mvn "-Dtest=RoutingSearchSourceProviderTest,TavilyBootstrapPlannerTest,SearchExecutionCoordinatorTest" test`
   - 结果：通过
2. bootstrap 语义与审计定向回归：
   - `mvn "-Dtest=TavilyFastLaneProviderTest,SourceCandidateRankerTest,SearchAuditTimelineContractTest" test`
   - 结果：通过
3. 第二批预算 / 提速定向回归：
   - `mvn "-Dtest=SourceCandidateRankerTest,SearchExecutionCoordinatorTest,CollectionExecutionCoordinatorTest,SearchPropertiesBindingTest" test`
   - 结果：通过
4. 组合回归：
   - `mvn "-Dtest=RoutingSearchSourceProviderTest,TavilyBootstrapPlannerTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorTest,SearchAuditTimelineContractTest,SearchAuditSnapshotCompatibilityTest,SearchAndCollectionGoldenMasterTest,SourceCandidateRankerTest,CollectionExecutionCoordinatorTest,SearchPropertiesBindingTest,ReportServiceTest" test`
   - 结果：`Tests run: 83, Failures: 0, Errors: 0, Skipped: 0`
5. citation 修复回归：
   - `mvn clean "-Dtest=CitationClaimExtractorTest,CitationAgentRepairabilityTest,CitationAgentTest" test`
   - 结果：通过
6. 编排 / 报告相关回归：
   - `mvn "-Dtest=CitationSuggestionAssemblerTest,OrchestrationDecisionServiceTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,ReportServiceTest" test`
   - 结果：通过
7. `9093` 真实联调二次验证：
   - `POST /api/task/65/nodes/citation_check/rerun`
   - `GET /api/task/65`
   - `GET /api/task/65/nodes`
   - `GET /api/task/65/replay`
   - 结果：`citation_check=SUCCESS`，`citationEvidenceState=PARTIAL_SOURCE`，停点后移到 `quality_check`
8. `9093` 真实联调整轮新跑：
   - `DELETE /api/task/64`
   - `POST /api/task/create`
   - `POST /api/task/66/execute`
   - `GET /api/task/66`
   - `GET /api/task/66/nodes`
   - `GET /api/task/66/replay`
   - 结果：
     - `task 66` 于 2026-06-27 20:42:10 创建，2026-06-27 20:46:57 完成
     - 总状态 `FAILED`，失败原因为 `质量闭环未达到通过条件，请检查评审结果`
     - `citation_check=SUCCESS`
     - `quality_check=SUCCESS`，初审评分 `37`
     - `rewrite_report=SUCCESS`
     - `citation_check_revision=SUCCESS`
     - `quality_check_final=SUCCESS`，终审评分 `72`

## 真实联调结论

1. Tavily Phase 1 bootstrap 已被真实任务证明生效，不再是“只有 supplement 阶段才会动用”的旁路能力。
2. citation 修复已经在新进程 `9093` 上落地，`citation_check` 不再因为 `sourceUrls=[]` 直接卡死。
3. `task 66` 证明新进程下的完整主链路可以从头跑到终审，当前真实 blocker 已经后移到最终质量闭环。
4. 当前质量 blocker 主要表现为：
   - 定价策略、短板与风险等章节仍缺可覆盖证据
   - 多条抖音 / B 站证据虽然 `qualityScore` 高，但 `structuredBlocks=[]`
   - 终审摘要明确指出“关键对比结论和战略建议缺乏充分证据，且信息披露部分存在严重格式错误”
5. 进一步向上追溯发现：`task 66` 四个 collector 的 collection replay 都是 `PREFETCH_REUSED`，而当前 `TavilyPrefetchedExecutor` 固定返回 `structuredBlocks=[]`；这意味着 Tavily Fast Lane 虽然提升了速度，但在当前实现下会天然放大 reviewer 对“缺结构块证据”的诊断概率。

## 我做了什么

1. 继续沿 `tavily-phase1-bootstrap-execution-plan.md` 把真实联调收口推进到 reviewer / final quality 阶段，而不是只停留在单测通过。
2. 修复了 citation 真链路里最关键的 evidenceId 抽取与 fallback sourceUrls 透传问题，并在重启后的 `9093` 上完成了真实 rerun 验证。
3. 删除了过时的 `task 64` 基线任务，避免旧进程结果继续污染后续判断。
4. 新建并执行了 `task 66`，用同一组抖音 / B 站样本重新跑了一轮完整 `9093` 实链。
5. 把当前主停点从 citation 精确收敛到 final quality gate，并补充了可直接追溯到节点 / 回放的基线证据。

## 接下来要做什么

1. 继续针对 `task 66` 的终审失败原因排查代码与链路，重点看：
   - 为什么 `structuredBlocks` 仍然普遍为空
   - 为什么定价 / 短板 / 风险章节仍然拿不到稳定证据覆盖
   - 为什么终审仍提示存在格式错误
2. 结合 `quality_check` 与 `quality_check_final` 的 `revisionDirectives`，决定优先修 `TavilyPrefetchedExecutor` 的结构块提取、extractor 字段覆盖，还是 reviewer 质量判定阈值。
3. 如有必要，再基于修复后的代码创建新任务复跑，确认主停点是否继续后移或彻底通过质量闭环。

## 剩余未做

1. 还没有把 `quality_check_final` 暴露出的结构化证据缺口与格式问题修到通过。
2. 还没有给 “structuredBlocks 为空” 这一新 blocker 补对应的自动化回归测试或代码修复。
3. 还没有补充最终人工验收截图 / 终审通过样本；当前最新真实样本 `task 66` 仍停在质量闭环失败。
