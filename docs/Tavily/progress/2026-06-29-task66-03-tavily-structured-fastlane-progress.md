# Task66-03 Tavily 结构化块与 Fast-Lane 审计执行进度

## 执行计划

- 任务名称：03 Tavily 结构化块与 Fast-Lane 审计
- 核心目标：让 `TavilyPrefetchedExecutor` 输出可解释的 `structuredBlocks` 与可追踪的 `structuredPayload`，同时保持 `02` 已落地的 `EvidenceQualityGate` 作为最终证据可用性门禁。
- 预期耗时：1 个实现轮次
- 前置依赖：
  - `01` 覆盖契约指定测试已通过
  - `02` 证据质量门禁指定测试已通过
  - 当前工程已具备 `TavilyPrefetchedContentRegistry`、`TavilyPrefetchedExecutor`、`StructuredContentBlock` 与 Collector metadata 持久化通路

## 步骤拆解

- [x] 步骤 1：阅读 `specs`、`roadmap` 与 `03` 计划，确认本阶段只做结构化块与 fast-lane 审计，不重做质量门禁
- [x] 步骤 2：核对现有 `TavilyPrefetchedExecutor`、`CollectionExecutionResult`、`StructuredContentBlock` 与相关测试现状
- [x] 步骤 3：补 `TavilyPrefetchedContentBlockClassifierTest`、`TavilyPrefetchedExecutorTest` 新增断言与 `TavilyFastLaneAuditContractTest` 红灯测试
- [x] 步骤 4：实现 `TavilyPrefetchedContentBlockClassifier` 与 `TavilyPrefetchedExecutor` 结构化块/审计输出
- [x] 步骤 5：运行 `03` 指定测试并记录结果

## 实时进度

- 当前执行步骤：步骤 5
- 已完成步骤占比：100%
- 剩余步骤：
  - 无，本轮按要求在 `03` 结束点停止
- 当前状态：已完成，等待进入 `04`

## 已确认实现边界

- 本轮执行到 `03` 结束后停止，不进入 `04`
- 直接在 `master` 工作区修改，不提交 commit
- `03` 只负责：
  - `structuredBlocks`
  - `TAVILY_STRUCTURED_BLOCK_COUNT`
  - `structuredPayload.prefetchedContentRef / prefetchedRawContentLength / primaryTool / structuredBlockCount / failureStage`
- `03` 不负责：
  - 重写 `EvidenceQualityGate`
  - 用结构化块数量抬高最终 `qualityScore`
  - 下发字段级 `fieldName / evidencePathKey / expectedSignals` 到 fast-lane

## 当前观察

- `02` 指定验证已通过，可作为 `03` 的稳定基线

## 本轮完成内容

- [x] 新增 `TavilyPrefetchedContentBlockClassifier`
- [x] 新增并通过 `TavilyPrefetchedContentBlockClassifierTest`
- [x] 为 `TavilyPrefetchedExecutor` 接入结构化块分类能力
- [x] 为 `TavilyPrefetchedExecutor` 成功结果补充：
  - `TAVILY_RAW_CONTENT_READY`
  - `TAVILY_PREFETCHED_CONTENT_CONSUMED`
  - `TAVILY_STRUCTURED_BLOCK_COUNT=N`
  - `structuredPayload.prefetchedContentRef / prefetchedRawContentLength / primaryTool / structuredBlockCount`
- [x] 为 `TavilyPrefetchedExecutor` 失败结果补充：
  - `structuredPayload.prefetchedContentRef / primaryTool / failureStage`
- [x] 新增并通过 `TavilyFastLaneAuditContractTest`
- [x] 扩展并通过 `TavilyPrefetchedExecutorTest`

## 验证结果

- `mvn -pl backend "-Dtest=TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest" test`
  - 结果：通过

## 下一步建议

- 下一任务进入 `04`：
  - 公开证据补采底座
  - 中介页/认证页/工具页拦截
  - `RecoveryContext` 与 `PublicEvidenceRecoveryService`
