# 2026-06-17 Search And Collection Family Discovery Convergence 进度

## 执行计划

- 任务名称：Search And Collection family discovery convergence 实施
- 关联计划：[2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md)
- 父计划：[2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md)
- 执行工作区：当前 `master` 工作区
- 执行策略：按 `Task 1 -> Task 5` 顺序推进，优先用测试锁定语义，再做最小实现与回归验证；每次停点记录“做了什么 / 接下来做什么 / 还剩什么”

## 执行进度

| Task | 内容 | 状态 |
| --- | --- | --- |
| Task 1 | 锁定 family-first discovery 红灯契约 | 已完成 |
| Task 2 | 正式引入 `SourceFamilyDirectDiscoveryPlanner` 与 catalog 边界 | 已完成 |
| Task 3 | 让 preview discovery 与 runtime initial candidates 共用 planner | 已完成 |
| Task 4 | 收口 direct candidate 与 public search supplement 的边界 | 已完成 |
| Task 5 | 回链第八轮执行顺序并完成聚合验证 | 已完成 |

当前：`Task 5`

下一步：可按执行顺序启动第八轮 `Wave 12` 下游证据闭环计划

## 当前阶段

当前阶段：family discovery convergence 已完成，正在等待按顺序启动第八轮 `Wave 12`
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成

## 进度明细

- [x] 新增 `SourceFamilyDirectDiscoveryPlanner`，统一把 `providedUrl / locator` 翻译成 family direct candidates。
- [x] 扩展 `SearchSourceCatalogProperties`，补齐 `directPathTemplates`、`stableLocatorHosts`、`stableLocatorSchemes`。
- [x] 扩展 `SearchPolicyResolver`，补齐 direct path template 与 stable locator 判断入口。
- [x] 在 `HeuristicSourceDiscoveryService` 中接入 planner，让 preview/runtime discovery 共用 direct discovery 语义。
- [x] 在 `SearchExecutionCoordinator` 中接入 planner，让 runtime initial candidates 优先消费 direct candidates。
- [x] 在 `SearchExecutionCoordinator` 中增加 `SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH` 判定，direct discovery 满足最小验真目标时跳过 public search supplement。
- [x] 在 `CandidateVerifier` 中增加对 `sourceUrls` 原始入口的回退抓取，避免 canonical URL 统一后误伤真实可访问入口。
- [x] 补齐并跑通以下测试：
- [x] `SourceFamilyDirectDiscoveryPlannerTest`
- [x] `SearchPolicyResolverTest`
- [x] `HeuristicSourceDiscoveryServiceTest`
- [x] `SearchExecutionCoordinatorTest`
- [x] `CandidateVerifierTest`
- [x] `CollectionTaskPackageBuilderTest`
- [x] 更新第八轮计划文档中的执行顺序说明，明确本计划是其前置条件。
- [x] 修正 `CollectorAgentTest` 中与 `CandidateVerifier` 重试契约不一致的旧断言：
- [x] 验证成功并复用预抓取页面的场景继续保持单次抓取断言。
- [x] 验证失败并落入 collection audit 的场景更新为 3 次重试断言。
- [x] 执行 `mvn -pl backend test` 全量回归并通过。

## 已执行验证

- [x] `mvn -pl backend "-Dtest=CandidateVerifierTest,HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest" test`
- [x] `mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest" test`
- [x] `mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest,HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest,CollectionTaskPackageBuilderTest" test`
- [x] `mvn -pl backend "-Dtest=CollectorAgentTest" test`
- [x] `mvn -pl backend test`

## 本次停点总结

### 做了什么

- 完成了 family-first discovery convergence 计划的全部剩余收口项。
- 确认第八轮 `Wave 12` 文档已补齐“先做 family discovery convergence，再启动第八轮”的执行顺序说明。
- 完成了本轮聚合测试和 `backend` 全量回归。
- 清理了全量回归里唯一暴露的遗留问题：`CollectorAgentTest` 对候选验证失败场景仍按旧的“单次抓取”口径断言，现已改为与 `CandidateVerifier` 的 3 次重试契约一致。

### 接下来做什么

- 按顺序进入 [2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md)。

### 还剩什么没做

- 本计划范围内已无未完成项。
- 后续待执行的是第八轮 `Wave 12`，不属于本计划缺口。
