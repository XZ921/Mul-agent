# 2026-06-16 Search And Collection 第五轮网页采集加固实施进度

## 执行计划

- 任务名称：Search And Collection 第五轮网页采集加固联合实施
- 关联计划：[2026-06-16-search-and-collection-fifth-iteration-web-page-collection-hardening-implementation-plan.md](/E:/java_study/Mul-agnet/docs/superpowers/task/2026-06-16-search-and-collection-fifth-iteration-web-page-collection-hardening-implementation-plan.md)
- 父计划：[2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md)
- 执行工作区：当前 `master` 工作区
- 执行策略：按 `Task 1 -> Task 6` 顺序推进，先红灯测试后最小实现；每次停点都回写“已完成 / 下一步 / 剩余项”总结

## 任务拆解

| 步骤 | 核心目标 | 预计耗时 | 前置依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| Task 1 | 锁定 `Wave 8` 双路径网页采集红灯契约 | 0.5 天 | 第四轮最小采集接缝已存在 | 已完成 |
| Task 2 | 扩展网页采集契约与 source family 采集偏好 | 1 天 | Task 1 红灯测试已建立 | 已完成 |
| Task 3 | 引入 `JinaReader` 主路径与 `WebPageCollectionExecutor` 双路径路由 | 1-1.5 天 | Task 2 完成 | 已完成 |
| Task 4 | 收口 `Playwright` 为 `FULL_RENDER` 兜底与页面就绪模型 | 1-2 天 | Task 2-3 完成 | 已完成 |
| Task 5 | 分层正文提取、结构块抽取与质量评分正式化 | 1-1.5 天 | Task 3-4 完成 | 已完成 |
| Task 6 | `CollectorAgent` 兼容映射、文档回填与整体验证 | 0.5-1 天 | Task 1-5 完成 | 已完成 |

## 当前阶段

- 当前阶段：第五轮 Task 6 已完成收口，等待进入下一轮 `Wave 9` 规划或真实任务验收
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行

## 进度明细

- [x] 已通读第五轮实施计划、父计划、第四轮进度文档与当前工程相关实现。
- [x] 已确认在 `master` 上直接执行第五轮任务，并以“结合整个工程、逐任务停点总结”为执行约束。
- [x] 已定位第五轮首批改动涉及的核心类：`CollectionTaskPackage`、`CollectionExecutionResult`、`CollectionTaskPackageBuilder`、`WebPageCollectionExecutor`、`SourceCollector`、`PlaywrightPageCollector`、`SearchSourceCatalogProperties`、`SearchPolicyResolver`。
- [x] 已开始补齐 Task 1 所需红灯测试与第五轮进度持久化文档。
- [x] 已新增 Task 1 所需 4 个测试文件，并补充 `CollectionTaskPackageBuilderTest`、`CollectionExecutorRegistryTest` 的第五轮断言。
- [x] 已运行第五轮首批红灯测试命令：
  - `mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest,PlaywrightPageReadinessContractTest,PageContentExtractionSupportStructuredBlockTest" test`
  - 结果：FAIL，且失败原因符合预期，为第五轮正式契约尚未实现。
- [x] 已确认当前红灯缺口主要收敛在：
  - `JinaReaderClient`、`PageContentExtractionResult`、`SourceCollectRequest`、`StructuredContentBlock`、`WebPageRenderHint` 等第五轮新类尚不存在。
  - `CollectionTaskPackage` 尚无 `renderHint / expectedBlockTypes`。
  - `CollectionExecutionResult` 尚无 `failureKind / qualitySignals / structuredBlocks`。
  - `WebPageCollectionExecutor` 仍是旧的 `SourceCollector.collect(url, competitorName, sourceType)` 单路径语义。
- [x] 已完成 Task 2 的最小实现：
  - 新增 `WebPageRenderHint`、`CollectionFailureKind`、`StructuredContentBlock` 三个正式契约对象。
  - 扩展 `SearchSourceCatalogProperties`，为 `official / news / github` 补齐 `preferredWebRenderHint` 与 `expectedBlockTypes` 默认值与配置绑定能力。
  - 扩展 `SearchPolicyResolver`，新增 `resolveWebRenderHint(...)` 与 `resolveExpectedBlockTypes(...)` 统一解析入口。
  - 扩展 `CollectionTaskPackage` 与 `CollectionExecutionResult`，补齐第五轮最小网页契约字段。
  - 改造 `CollectionTaskPackageBuilder`，让网页任务包基于 source family 解析 `renderHint / expectedBlockTypes / primaryTool`。
  - 在 `application.yml` 中补充 source family 的网页采集偏好示例配置。
  - 为了让后续 Task 3-Task 5 的测试能继续编译推进，补齐了 `SourceCollectRequest`、`PageContentExtractionResult`、`JinaReaderProperties`、`JinaReaderClient` 的最小骨架，并在 `SourceCollector`、`PageContentExtractionSupport`、`WebPageCollectionExecutor` 中补了最小兼容入口。
- [x] 已运行 Task 2 定向验证命令：
  - `mvn -pl backend "-Dtest=SearchPropertiesBindingTest,SearchPolicyResolverTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`
  - 结果：PASS
- [x] 已完成 Task 3：
  - `JinaReaderClient` 已接入轻量正文主路径，统一封装 reader URL 解析、超时、异常保护与重试。
  - `WebPageCollectionExecutor` 已从旧的单路径采集适配层升级为 `JinaReader` 主路径 + `Playwright FULL_RENDER` 兜底的双路径执行器。
  - `LIGHTWEIGHT` 内容不足时会带着 `qualitySignals` 升级到浏览器渲染，并把轻量路径信号合并进统一执行结果。
- [x] 已完成 Task 4：
  - `SourceCollector` 正式引入 `SourceCollectRequest` 入口，同时保留旧签名兼容桥接。
  - `PlaywrightPageCollector` 已将主逻辑收口到 `collect(SourceCollectRequest)`，并对 `FULL_RENDER` / `LOGIN_REQUIRED` / `INTERACTION_REQUIRED` / `ANTI_BOT_RISK_HIGH` 关闭 HTTP-first 早退。
  - 页面就绪模型已补齐 `DOMContentLoaded -> Load -> 关键选择器` 的最小等待链路，并继续复用既有反爬诊断设施。
- [x] 已完成 Task 5：
  - `PageContentExtractionSupport.extract(...)` 已正式返回 `PageContentExtractionResult`，不再只返回单一正文字符串。
  - 结构块已至少覆盖 `PRICING_BLOCK`、`DOCUMENTATION_OUTLINE`、`JSON_LD_METADATA`，并产出 `qualitySignals / qualityScore / failureKind / collectedAt / durationMillis`。
  - `PlaywrightPageCollector` 与 `WebPageCollectionExecutor` 已统一消费分层提取结果，`CollectorAgent` 兼容消费时不会丢失 `sourceUrls` 与新增采集元数据。
- [x] 已完成 Task 6：
  - `CollectorAgent` 已兼容消费新的 `CollectionExecutionResult` 字段，并把 `qualitySignals / qualityScore / failureKind / structuredBlocks / durationMillis / collectedAt` 固化进兼容 metadata。
  - `isUsableCollectedPage(...)` 已允许 `structuredBlocks` 作为“无正文但仍可用”的证据来源。
  - 已回写父计划与总设计看板，明确第五轮承接的是 `Wave 8` 正式网页采集契约收口，而不只是 anti-bot patch。
- [x] 已补两条 Spring 装配回归测试，锁定第五轮新增组件在应用上下文中的注入行为：
  - `WebPageCollectionExecutorContextTest`
  - `JinaReaderClientContextTest`
- [x] 已修复第五轮引入的新装配回归：
  - `WebPageCollectionExecutor` 显式声明 Spring 使用双依赖构造器，避免集成测试上下文退回无参构造查找。
  - `JinaReaderClient` 显式声明 Spring 使用仅依赖 `JinaReaderProperties` 的正式构造器，保留 `HttpClient` 双参构造器给测试与手工覆盖使用，避免把 `HttpClient` 误提升为容器必填 bean。
- [x] 已运行第五轮 Task 6 聚合验证命令：
  - `mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest,JinaReaderClientContextTest,PlaywrightPageReadinessContractTest,PageContentExtractionSupportStructuredBlockTest,PlaywrightPageCollectorTest,WebPageCollectionExecutorContextTest" test`
  - 结果：PASS
- [x] 已运行 `backend` 全量回归：
  - `mvn -pl backend test`
  - 结果：PASS

## 本次停点总结

- 已完成什么：
  - 完成 Task 3-Task 6 全部实现收口，把第五轮从“骨架与局部通过”推进到“网页采集主链路、兼容映射、文档口径、上下文装配与自动化验证全部闭环”。
  - 补齐并锁定了 `JinaReaderClient`、`WebPageCollectionExecutor` 两个新增组件的 Spring 上下文装配回归，避免双构造器在集成测试里退回无参构造查找。
  - 通过第五轮聚合验证与 `backend` 全量回归，证明本轮改造已经与整个工程当前状态兼容。
- 下一步做什么：
  - 最推荐进入 `Wave 9`：正式设计并实施 `collectionAudit / collectionReplayTimeline / collectionCheckpoint / 包级 rerun-resume / runtime-insight-replay-event 对齐`。
  - 如果要先做业务验收，则补跑真实任务链路，重点验证 `JinaReader` 主路径采到的证据质量、`Playwright FULL_RENDER` 升级率和最终质检分数。
- 还剩什么没做：
  - 采集段的 `collectionAudit / replay / checkpoint / rerun-resume` 正式语义仍属于 `Wave 9`，这轮没有展开。
  - `Wave 6` 真实 discovery provider 进实链、`news` 家族结构化采集与跨重启 replay 底座仍未完成。
  - 虽然自动化已经收口，但真实业务质量闭环仍需继续结合 dev live 任务与最终质检结果验收，当前不能把“自动化通过”直接等同于“实链完全升绿”。
