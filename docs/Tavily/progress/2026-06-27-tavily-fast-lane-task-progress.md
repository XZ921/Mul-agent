# Tavily Fast Lane MVP 任务进度 - 2026-06-27

当前阶段：Task 10 `A/B/C/D 验收与 EvidenceSource 质量指标` 已完成，`tavily-fast-lane-mvp-execution-plan.md` 中 Task 1-10 已全部落地，当前进入进度归档收口阶段。
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质检复核：已完成

## 执行计划

| 步骤 | 核心目标 | 预期耗时 | 前置依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| 1 | 复核 `tavily-fast-lane-mvp-execution-plan.md` 与当前工程状态，确认 Task 1-7 完成情况 | 15 分钟 | 无 | 已完成 |
| 2 | 完成 Task 8：扩展 `SearchAuditSnapshot` / `SearchExecutionTrace` / `SearchAuditSummary` 的 Tavily 审计摘要 | 45 分钟 | Task 1-7 | 已完成 |
| 3 | 完成 Task 9：打通 orchestration evidence repair 到 Tavily 的动作映射 | 45 分钟 | Task 8 | 已完成 |
| 4 | 完成 Task 10：固化 fixture、补 A/B/C/D 验收测试并做相关回归 | 60 分钟 | Task 9 | 已完成 |
| 5 | 同步进度文档、归档验证结果并标记本轮计划收口 | 15 分钟 | Task 10 | 已完成 |

## 已完成内容

1. 已确认继续直接在 `master` 上修改，不创建分支、不提交代码，并避开用户现有日志与其他未提交改动。
2. 已完成 Task 5：
   - `SearchProviderProperties` 默认路由顺序调整为 `tavily -> qianfan -> serpapi -> browserPreview -> http`
   - `RoutingSearchSourceProvider` 注入 `TavilyFastLaneProvider`
   - `SearchCapabilityReadinessGuard` 纳入 Tavily readiness 摘要
   - `SearchSecurityConfigurationGuard` 增加 `tavily-search.endpoint` HTTPS 校验
   - `application.yml` 增加 Tavily provider 路由与默认关闭配置
3. 已完成 Task 6：
   - 新增 `TavilyPrefetchedExecutor`
   - 支持 `TAVILY_PREFETCHED` primary tool 路由
   - 使用 registry 单次 `remove()` 原子消费正文，避免重复消费
   - 补充最小正文清洗逻辑，裁掉 `相关推荐 / 猜你喜欢 / 热门推荐` 等噪声尾部
4. 已完成 Task 7：
   - `CandidateVerifier` 在 `verifyOneCandidate()` 开头增加强 Tavily 候选短路判断
   - 满足 `providerKey=tavily`、`fastLaneUsable=true`、`skipNetworkVerification=true`、`sourceUrls` 非空、`pageType in [ARTICLE, OFFICIAL_DOC, PDF]` 时直接通过
   - 写回 `verificationReason=TAVILY_FAST_LANE_GATE_VERIFIED`
   - 质量信号补充 `TAVILY_VERIFICATION_SKIPPED`
5. 已完成 Task 8：
   - 新增 `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
   - `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchAuditSummary` 均已扩展 `tavilyFastLaneAudit`
   - `SearchExecutionCoordinator` 在搜索链路末端基于 Tavily `SourceCandidate` 聚合审计元数据
   - `SearchAuditSummary.from(...)` 已支持从 snapshot / trace / 既有 summary 三处兜底读取 Tavily 审计
   - `SearchSharedProjection`、`ReportService` 已打通 Tavily 审计透传与聚合，避免报告/回放层丢字段
6. 已完成 Task 9：
   - `DecisionPolicyResult` 扩展 Tavily evidence repair hint 字段：`preferredSearchProvider`、`tavilyQueryMode`、`suggestedQueries`、`includeDomainPolicy`、`preferredDomains`、`includeDomains`
   - `DecisionPolicyService` 已补充 Tavily evidence repair 策略映射，`SUPPLEMENT_EVIDENCE` 可自动落到 `preferredSearchProvider=tavily` 与 `tavilyQueryMode=EVIDENCE_REPAIR`
   - 官方 / 文档 / 定价类缺口使用 `includeDomainPolicy=NARROW_OFFICIAL`
   - `DOMAIN_HINT_DISCOVERY` 在 MVP 中显式降级为 `MANUAL_ONLY`
   - `DecisionExecutorAdapter` 已把 Tavily hint 写入动态 collector branch 的 node config
   - `OrchestrationDecisionService` 已支持把显式要求回到 `collect_sources` 的 citation suggestion 映射为 `SUPPLEMENT_EVIDENCE`
7. 本轮已完成 Task 10：
   - 新增 `backend/src/test/resources/tavily/recommendation-algorithm-response.json`、`official-docs-response.json`、`noise-search-page-response.json` 三类 fixture，分别覆盖 OPEN_WEB 正样本、OFFICIAL_DOCS 正样本、噪声负样本
   - 新增 `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAcceptanceTest.java`，用测试内局部 `AcceptanceMetrics` 固化 A/B/C/D 验收指标，不把试验口径写进生产代码
   - 修正 `TavilyFastLaneProvider` 的受控扩展触发条件：官方文档首轮“结果为空”之外，只要“无可用结果”或“没有命中官方文档”也会触发 `TRUSTED_WEB_EXPANSION`
   - 修正 `TavilyPrefetchedContentGate` 的官方锚点判定：`TRUSTED_WEB_EXPANSION` 结果只有在真实命中官方域名时才算官方锚点，避免 DOCS / OFFICIAL / PRICING 场景把非官方扩展结果一律打成 `WEAK`
   - 重写 `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`，保留 golden master 意图并补上 Tavily 搜索 -> 验证 -> 收集的黄金路径
   - 为了通过全量测试暴露出的模块边界校验，补充 `SearchAuditSummary.fromTrace(...)` 与 `SearchAuditSummary.merge(...)`，并让 `ReportService` 只消费稳定 DTO，不再直接依赖 `search.tavily.TavilyFastLaneAudit`

## 验证记录

1. Task 8 红灯验证：
   - `mvn "-Dtest=SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest" test`
   - 首次失败点符合预期：`TavilyFastLaneAudit` 类不存在，`SearchAuditSnapshot` / `SearchExecutionTrace` / `SearchAuditSummary` 缺少对应字段
2. Task 8 绿灯验证：
   - `mvn "-Dtest=SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest" test`
   - 结果：`Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`
3. Task 8 关联回归：
   - `mvn "-Dtest=SearchRuntimeObjectSlimmingContractTest,SearchObjectSlimmingContractTest,TaskReplayProjectionServiceTest,SearchProjectionConsumerContractTest,ReportServiceTest" test`
   - 结果：`Tests run: 39, Failures: 0, Errors: 0, Skipped: 0`
4. Task 5-8 组合回归：
   - `mvn "-Dtest=RoutingSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCapabilityReadinessGuardTest,SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,TavilyPrefetchedExecutorTest,CandidateVerifierTest,SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest" test`
   - 结果：`Tests run: 68, Failures: 0, Errors: 0, Skipped: 0`
5. Task 9 红灯验证：
   - `mvn "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest" test`
   - 首次失败点符合预期：`DecisionPolicyResult` 缺少 Tavily repair hint 字段，citation suggestion 仍然落到 `REWRITE_ONLY`
6. Task 9 绿灯验证：
   - `mvn "-Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest" test`
   - 结果：`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`
7. Task 5-9 组合回归：
   - `mvn "-Dtest=RoutingSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCapabilityReadinessGuardTest,SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,TavilyPrefetchedExecutorTest,CandidateVerifierTest,SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest" test`
   - 结果：`Tests run: 94, Failures: 0, Errors: 0, Skipped: 0`
8. Task 10 定向验收：
   - `mvn "-Dtest=TavilyFastLaneAcceptanceTest,SearchAndCollectionGoldenMasterTest" test`
   - 结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
9. Task 10 Tavily 相关组合回归：
   - `mvn "-Dtest=*Tavily*,SearchAndCollectionGoldenMasterTest,RoutingSearchSourceProviderTest,CandidateVerifierTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,SearchAuditSnapshotCompatibilityTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest" test`
   - 结果：`Tests run: 68, Failures: 0, Errors: 0, Skipped: 0`
10. 架构边界与报告链路回归：
   - `mvn "-Dtest=BackendModuleDependencyTest,ReportServiceTest,SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest,TavilyFastLaneAcceptanceTest,SearchAndCollectionGoldenMasterTest" test`
   - 结果：`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`
11. 全量后端回归：
   - 首次 `mvn test` 暴露 `ReportService` 直接依赖 `search.tavily.TavilyFastLaneAudit` 的模块边界问题
   - 调整为 `SearchAuditSummary.fromTrace(...)` + `SearchAuditSummary.merge(...)` 后重新执行 `mvn test`
   - 结果：全量后端测试通过
12. 本次停点收口复核：
   - `mvn test`
   - 结果：退出码 `0`，聚合统计 `tests=835 failures=0 errors=0 skipped=3 suites=218`

## 我做了什么

1. 完成了 Task 10 所需的三类 Tavily fixture、A/B/C/D 验收测试，以及 golden master 补强。
2. 修正了受控扩展的两个真实行为缺口，让“官方锚点不足时扩展、但不伪造官方锚点”这条策略真正落地。
3. 在全量测试阶段顺手清掉了报告层直接依赖搜索实现细节的架构问题，把审计聚合收口到 `SearchAuditSummary`。
4. 本次停点前重新执行了 `mvn test`，用 fresh evidence 确认当前 `backend` 全量测试仍然通过。
5. 把以上实现、验证记录和停点总结同步回本进度文档，保证计划、代码、测试、进度四处一致。

## 接下来要做什么

1. 当前执行计划范围内的开发任务已经收口；如果继续推进，优先做真实 Tavily API 联调与灰度开关验证。
2. 联调阶段重点观察 `TRUSTED_WEB_EXPANSION` 的真实命中质量、失败重试日志和 `sourceUrls` 可追溯性是否稳定。
3. 如需对外验收，再补一轮基于真实样本任务的端到端运行记录与截图。

## 剩余未做

1. `tavily-fast-lane-mvp-execution-plan.md` 中 Task 1-10 当前已全部完成，无剩余计划内开发任务。
2. 计划外尚未开展的事项包括：真实 Tavily API 联调、灰度开启、线上样本观察与指标复核。
