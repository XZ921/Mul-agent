# 2026-06-18 Search And Collection Site Discovery Deep Collection 进度

## 执行计划

- 任务名称：Search And Collection site discovery deep collection 实施
- 关联计划：[2026-06-18-search-and-collection-site-discovery-deep-collection-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-18-search-and-collection-site-discovery-deep-collection-implementation-plan.md)
- 父计划：[2026-06-18-search-and-collection-site-discovery-deep-collection-master-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-18-search-and-collection-site-discovery-deep-collection-master-plan.md)
- 执行工作区：当前 `master` 工作区
- 执行策略：按 `Round 1 -> Round 4` 顺序推进，优先用测试锁定语义，再做最小实现与定向回归；每次停点记录“做了什么 / 接下来做什么 / 还剩什么”

## 执行进度

| Round | 内容 | 状态 |
| --- | --- | --- |
| Round 1 | 搜索结果回灌 direct discovery + 子域模板扩展 | 已完成 |
| Round 2 | LLM/规则域名发现 + readiness | 已完成 |
| Round 3 | sitemap/robots 发现 + readiness | 已完成 |
| Round 4 | 站内链接发现、限深递归、collector/audit/replay/sourceUrls 收口 | 已完成 |
| Round 4 收口 | 端到端集成测试与文档更新 | 已完成 |

当前：全部 Round 已完成，已进入停点收口

下一步：如需继续，可转入更大范围回归，或按新的上游/下游计划继续推进

## 当前阶段

当前阶段：site discovery deep collection 已完成，正在做停点总结
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成

## 进度明细

- [x] 在 `SearchExecutionCoordinator` 中打通搜索结果根域回灌 direct discovery。
- [x] 在 `SearchSourceCatalogProperties` / `SearchPolicyResolver` / `SourceFamilyDirectDiscoveryPlanner` 中补齐 `open.`、`docs.`、`developer.`、`help.` 子域模板。
- [x] 新增 `CompetitorDomainDiscoveryService`、`DomainVerificationClient`、`DomainDiscoveryProperties`，补齐 LLM/规则域名发现与 readiness 摘要。
- [x] 新增 `SitemapDiscoveryService`、`SitemapDiscoveryProperties`，补齐 sitemap/robots 发现与 readiness 摘要。
- [x] 新增 `InternalLinkDiscoveryService`、`InternalLinkDiscoveryProperties`，补齐同域高价值内部链接发现。
- [x] 扩展 `CollectionExecutionResult`、`CollectionTaskPackage`、`CollectionTaskPackageBuilder`，透传 `discoveredCandidates` 与 `discoveryDepth`。
- [x] 在 `WebPageCollectionExecutor` 中接入内部链接发现，确保轻量抓取与全渲染抓取都能产出递归候选。
- [x] 在 `CollectionExecutionCoordinator` 中实现限深、去重、按入口限量、按节点总量限量的递归采集。
- [x] 在 `CollectorAgent` 中完成递归结果消费收口，确保入口页与内部子页统一进入：
- [x] `documents`
- [x] `collectionAudit.results`
- [x] `collectionAudit.replayTimeline`
- [x] `searchProgressSnapshots`
- [x] `sourceUrls`
- [x] 在 `application.yml` 中补齐 `collection.internal-link-discovery` 相关配置。
- [x] 新增端到端测试 [SearchAndCollectionDeepDiscoveryIntegrationTest](/E:/java_study/Mul-agnet/backend/src/test/java/cn/bugstack/competitoragent/integration/SearchAndCollectionDeepDiscoveryIntegrationTest.java)，验证：
- [x] 只给竞品语义后，运行期补源可命中文档入口
- [x] 入口页可递归发现 `账号授权`、`Android SDK` 等内部子页
- [x] 输出包含 `sourceUrls`
- [x] 输出包含 `collectionAudit.replayTimeline`

## 已执行验证

- [x] `mvn -pl backend "-Dtest=InternalLinkDiscoveryServiceTest,WebPageCollectionExecutorRouteTest,CollectionExecutionCoordinatorTest,CollectorAgentTest,CollectionAuditContractTest" test`
- [x] `mvn -pl backend "-Dtest=CollectionAuditSerializationTest,CollectionExecutionCoordinatorCheckpointReuseTest,WebPageCollectionExecutorContextTest" test`
- [x] `mvn -pl backend "-Dtest=SearchAndCollectionDeepDiscoveryIntegrationTest" test`

## 本次停点总结

### 做了什么

- 完成了 Round 4 剩余收口项：新增端到端深度发现集成测试，并补齐进度文档与 gap-analysis 状态更新。
- 集成测试采用真实 `CollectorAgent + SearchExecutionCoordinator + CollectionExecutionCoordinator + WebPageCollectionExecutor` 组装，只在搜索补源和页面抓取边界做 mock，覆盖“补源 -> 入口页 -> 内部子页 -> audit/sourceUrls”闭环。
- 保持了当前工作区既有改动不回退，不对无关文件做清理式修改。

### 接下来做什么

- 如果继续当前主题，建议执行计划中的更大范围定向回归：
- `mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SourceFamilyDirectDiscoveryPlannerTest,CompetitorDomainDiscoveryServiceTest,SitemapDiscoveryServiceTest,InternalLinkDiscoveryServiceTest,CollectionExecutionCoordinatorTest,CollectorAgentTest,SearchCapabilityReadinessGuardTest,SearchAndCollectionDeepDiscoveryIntegrationTest" test`
- 如果要切换到新计划，可以直接以上述进度文档和当前工作区为起点继续。

### 还剩什么没做

- 本计划文档中明确剩余的最小收口项已完成。
- 尚未执行的是更大范围的最终回归与 `backend` 全量测试；这属于验证增强，不是当前计划的功能缺口。
