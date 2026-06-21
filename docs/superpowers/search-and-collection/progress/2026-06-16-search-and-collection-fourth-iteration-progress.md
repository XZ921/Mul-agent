# 2026-06-16 Search And Collection 第四轮垂直 Provider 实施进度

## 执行计划

- 任务名称：Search And Collection 第四轮垂直 Provider 联合实施
- 关联计划：[2026-06-12-search-and-collection-fourth-iteration-vertical-provider-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-12-search-and-collection-fourth-iteration-vertical-provider-implementation-plan.md)
- 父计划：[2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md)
- 执行工作区：当前 `master` 工作区
- 执行策略：按 `Task 1 -> Task 6` 顺序推进，先红灯测试后最小实现；每次停点都回写“已完成 / 下一步 / 剩余项”总结

## 任务拆解

| 步骤 | 核心目标 | 预计耗时 | 前置依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| Task 1 | 锁定联合红灯契约：GitHub owner 边界、主辅路由、候选元数据标准化、最小采集任务包/执行器注册 | 0.5 天 | 计划文档与现有工程上下文已确认 | 已完成 |
| Task 2 | 收口 Wave 6 discovery 路由与候选标准化 | 1-1.5 天 | Task 1 红灯测试已建立 | 已完成 |
| Task 3 | 落最小采集执行接缝：任务包、构建器、协调器、执行器注册表 | 1-2 天 | Task 1-2 完成 | 已完成 |
| Task 4 | 让 `CollectorAgent` 接入新采集骨架并保留网页兼容路径 | 1-2 天 | Task 3 完成 | 已完成 |
| Task 5 | 实现 GitHub API 共享客户端与首个结构化采集执行器 | 1-2 天 | Task 2-4 完成 | 已完成 |
| Task 6 | 联合复核、文档回填与全量验证收口 | 0.5-1 天 | Task 1-5 完成 | 已完成 |

## 当前阶段

- 当前阶段：第四轮联合实施已完成
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成

## 进度明细

- [x] 已通读第四轮实施计划、父计划与 `CollectorAgent` 诊断文档，并对齐 `SearchPolicyResolver`、`RoutingSearchSourceProvider`、`SearchExecutionCoordinator` 的当前实现。
- [x] 已确认用户授权直接在 `master` 上修改，并结合整个工程从 Task 1 顺序推进。
- [x] 已新增 Task 1 所需 5 个测试文件，并跑出首轮红灯。
- [x] 已完成 Task 2 的最小实现：补齐 GitHub source family provider 绑定、`resolveProviderRole`、主辅路由阈值判断、候选标准化元数据补齐，以及 provider 产出时的 `providerKey / sourceUrls` 回填。
- [x] 已完成 Task 3 的最小实现：新增 `CollectionTaskPackage`、`CollectionTaskPackageBuilder`、`CollectionExecutionResult`、`CollectionExecutor`、`CollectionExecutorRegistry`、`CollectionExecutionCoordinator`、`WebPageCollectionExecutor`、`GithubApiCollectionExecutor`、`GithubApiClient`。
- [x] 已通过第一轮联合定向验证：
  - `mvn -pl backend "-Dtest=SearchProviderRoleContractTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCandidateNormalizationMetadataTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,RoutingSearchSourceProviderTest" test`
  - 结果：PASS
- [x] 已完成 Task 4：
  - `CollectorAgent` 注入 `CollectionExecutionCoordinator`，主采集循环改为“预抓页面复用 + coordinator 执行器路由”的双路径模式。
  - 新增 `CollectionExecutionResult -> SourceCollector.CollectedPage` 兼容映射，保留 `sourceUrls`，并把 `structuredPayload` 固化到 metadata。
  - `CollectorAgentTest` 新增 GitHub API 采集场景，验证 GitHub 目标无需再调用 `sourceCollector.collect(...)` 也能完成证据落库与输出契约。
  - 已运行 `mvn -pl backend "-Dtest=CollectorAgentTest" test`，结果 PASS。
- [x] 已完成 Task 5：
  - 新增 `GithubApiCollectionExecutorTest`，验证 `github://repo/{owner}/{repo}` locator -> 结构化证据路径。
  - 已运行 `mvn -pl backend "-Dtest=GithubApiCollectionExecutorTest" test`，结果 PASS。
- [x] 已完成 Task 6：
  - 回填父计划与总规格文档，明确第四轮不再是单独交付 GitHub vertical search provider，而是 discovery 路由、候选标准化、最小采集执行接缝与首个 GitHub API 结构化采集执行器的联合实施。
  - 已运行联合验证命令与 `mvn -pl backend test`，结果 PASS。

## 本次停点总结

- 已完成什么：
  - 完成 Task 1、Task 2、Task 3 的最小闭环与定向验证。
  - 完成 Task 4 的 `CollectorAgent` 接缝改造，让新的采集协调器正式接入主链路。
  - 保留旧的“已验证页面不重复抓取”契约，同时让 GitHub API 结构化采集结果能够继续进入证据落库、CollectResult 和 Task RAG 链路。
  - 完成 Task 5 的 GitHub API executor 专项测试。
  - 完成 Task 6 的父文档回填与联合验证，全轮任务 1-6 已收口。
- 下一步做什么：
  - 按父计划建议，后续最值得直接进入的是 `Wave 8` 双路径网页采集强化：`JinaReader` 主路径、`Playwright` 重型兜底、`failureKind / renderHints` 正式化，以及网页采集质量评分与结构块抽取。
- 还剩什么没做：
  - 本轮 Task 1-6 已全部完成。
  - 仍未完成的是父计划后续波次内容，不属于本轮范围：`Wave 6` 真实 discovery provider 进实链、`Wave 8` 网页采集强化、`Wave 9` 采集审计/回放/恢复、`Wave 10` 继续扩展到 `news` 家族等。
