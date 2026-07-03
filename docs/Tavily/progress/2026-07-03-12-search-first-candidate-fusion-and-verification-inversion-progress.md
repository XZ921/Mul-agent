# Task12 执行进度记录

## 当前阶段
- 当前阶段：Task 7 / Task 10 已完成，已进入回归验证与收尾确认阶段。

## 执行计划
```json
{
  "taskName": "12-search-first-candidate-fusion-and-verification-inversion",
  "sourcePlan": "docs/Tavily/task/2026-07-03-12-search-first-candidate-fusion-and-verification-inversion-plan.md",
  "updatedAt": "2026-07-03 19:07:40 +08:00",
  "steps": [
    {
      "id": "review-context",
      "name": "方案与代码复核",
      "goal": "确认 plan12 与现有搜索链路、字段投影、query 预算修复之间的边界",
      "eta": "20 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "task1-red",
      "name": "Task 1 红测",
      "goal": "锁定 search-first 目标数、bootstrap、selector、fusion 四类根因",
      "eta": "35 分钟",
      "dependsOn": ["review-context"],
      "status": "completed"
    },
    {
      "id": "task2-policy",
      "name": "Task 2 策略实现",
      "goal": "拆开 direct URL 数量与 search-first 正式证据目标数，补齐验证预算解析",
      "eta": "30 分钟",
      "dependsOn": ["task1-red"],
      "status": "completed"
    },
    {
      "id": "task3-bootstrap",
      "name": "Task 3 Bootstrap 改造",
      "goal": "让 search-first official family 在验证前执行 Tavily trusted expansion 主搜索",
      "eta": "30 分钟",
      "dependsOn": ["task2-policy"],
      "status": "completed"
    },
    {
      "id": "task4-fusion",
      "name": "Task 4 Fusion Planner",
      "goal": "统一生成 preselected / verification / fast lane 决策",
      "eta": "45 分钟",
      "dependsOn": ["task3-bootstrap"],
      "status": "completed"
    },
    {
      "id": "task5-coordinator",
      "name": "Task 5 Coordinator 接线",
      "goal": "把 fusion 输出接入验证前与最终选源数量控制，并补齐 trace 指标",
      "eta": "40 分钟",
      "dependsOn": ["task4-fusion"],
      "status": "completed"
    },
    {
      "id": "task6-selector",
      "name": "Task 6 Selector 质量排序",
      "goal": "让强正文 Tavily 候选压过 verified 薄壳页",
      "eta": "25 分钟",
      "dependsOn": ["task5-coordinator"],
      "status": "completed"
    },
    {
      "id": "phase-a-regression",
      "name": "首批定向回归",
      "goal": "验证 Task 1-6 首批闭环与既有回归兼容",
      "eta": "25 分钟",
      "dependsOn": ["task6-selector"],
      "status": "completed"
    },
    {
      "id": "task7-selected-target-observability",
      "name": "Task 7 审计字段补齐",
      "goal": "补齐 selected target 在 Collector 输出、前端 insight、shared projection 中的 Tavily 元数据",
      "eta": "35 分钟",
      "dependsOn": ["phase-a-regression"],
      "status": "completed"
    },
    {
      "id": "task10-field-deadline",
      "name": "Task 10 deadline bugfix",
      "goal": "让 field evidence request deadline 使用 execute 已放大后的 searchTimeoutMillis",
      "eta": "25 分钟",
      "dependsOn": ["task7-selected-target-observability"],
      "status": "completed"
    },
    {
      "id": "phase-b-regression",
      "name": "第二批定向回归",
      "goal": "覆盖搜索主链、projection/replay 链以及 public recovery 兼容性",
      "eta": "35 分钟",
      "dependsOn": ["task10-field-deadline"],
      "status": "completed"
    },
    {
      "id": "backend-full-regression",
      "name": "backend 全量回归",
      "goal": "执行 backend 全量测试并识别剩余非本轮范围问题",
      "eta": "40 分钟",
      "dependsOn": ["phase-b-regression"],
      "status": "in_progress"
    }
  ]
}
```

## 进度看板
- [x] 读取主计划文档与业务总览规格
- [x] 复核现有搜索链路核心类：`SearchPolicyResolver` / `TavilyBootstrapPlanner` / `SearchExecutionCoordinator` / `CollectionTargetSelector`
- [x] 建立 Task 1 红测
- [x] 实现 Task 2 search-first 目标数与验证预算策略
- [x] 实现 Task 3 Tavily bootstrap 主搜索改造
- [x] 实现 Task 4 Candidate Fusion Planner
- [x] 实现 Task 5 coordinator 验证前 fusion 接线与 trace 指标
- [x] 实现 Task 6 selector 薄壳页降档
- [x] 完成 Task 7 selected target 审计字段补齐
- [x] 完成 Task 10 field query deadline bugfix
- [x] 执行第二批定向回归并补 public recovery 兼容修复
- [ ] 处理 backend 全量回归中剩余非本轮范围失败项

## 当前进展说明
- 本轮新增/完善的实现：
  - `CollectorSelectedTargetSummary` 补齐 `discoveryMethod`、`tavilyQueryMode`、`qualityTier`、`fastLaneUsable`、`prefetchedRawContentLength`、`skipNetworkVerification`。
  - `CollectorAgent.buildSelectedTargetSummaries(...)` 正式输出上述 selected target 审计字段。
  - `TaskNodeViewAssembler` 与 `SearchSelectedTargetSummary` 补齐前端 insight 侧轻量投影，避免 selected target 元数据再次被裁薄。
  - `SearchSharedProjection` 补齐 shared projection / replay 链路的字段回退逻辑，优先读顶层 selected target，兼容旧 candidate 快照。
  - `SearchExecutionCoordinator` 现在在 `execute()` 入口基于已放大的 `searchTimeoutMillis` 统一计算 `fieldEvidenceExecutionDeadlineEpochMillis`，并沿 supplement 调用链透传到 `buildSearchSourceRequest(...)`。
  - 为避免误伤显式 `sourceCandidates` 老链路，`SearchPolicyResolver` 与 `TavilyBootstrapPlanner` 新增了“只有 `competitorUrls` 且没有显式 `sourceCandidates` 时才启用 search-first 扩张策略”的守门条件；这也修复了 `SearchExecutionCoordinatorPublicRecoveryTest` 的兼容回归。

## 定向验证结果
- 已通过：
  - `mvn -pl backend "-Dtest=TaskNodeViewAssemblerTest,CollectorAgentTest#shouldIncludeSelectedTargetSearchFirstAuditFieldsInOutput,SearchExecutionCoordinatorFieldEvidenceBudgetTest#shouldUseBumpedSearchTimeoutWhenBuildingFieldEvidenceDeadline" test`
  - `mvn -pl backend "-Dtest=SearchCandidateFusionPlannerTest,TavilyBootstrapPlannerTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,TavilyPrefetchedContentGateTest,TavilySearchProfileResolverTest" test`
  - `mvn -pl backend "-Dtest=TaskNodeViewAssemblerTest,TaskReplayProjectionServiceTest,SearchAuditSnapshotCompatibilityTest,SearchObjectSlimmingContractTest,SharedNodeOutputProjectorContractTest,CollectorAgentTest#shouldIncludeSelectedTargetSearchFirstAuditFieldsInOutput" test`
  - `mvn -pl backend "-Dtest=TavilyBootstrapPlannerTest#shouldKeepLegacyBootstrapSemanticsWhenSearchFirstFamilyAlreadyHasExplicitSourceCandidates,SearchExecutionCoordinatorPublicRecoveryTest" test`
  - `mvn -pl backend "-Dtest=SearchCandidateFusionPlannerTest,TavilyBootstrapPlannerTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,SearchExecutionCoordinatorPublicRecoveryTest,TavilyPrefetchedContentGateTest,TavilySearchProfileResolverTest,TaskNodeViewAssemblerTest,TaskReplayProjectionServiceTest,SearchAuditSnapshotCompatibilityTest,SearchObjectSlimmingContractTest,SharedNodeOutputProjectorContractTest,CollectorAgentTest#shouldIncludeSelectedTargetSearchFirstAuditFieldsInOutput" test`

## 全量回归状态
- 已尝试：
  - `mvn -pl backend test`
- 结果：
  - 在修复 `SearchExecutionCoordinatorPublicRecoveryTest` 之前，backend 全量回归暴露 7 项失败。
  - 其中与本轮修改直接相关的 `SearchExecutionCoordinatorPublicRecoveryTest` 已单独修复并重新验证通过。
  - 剩余失败项集中在下列 catalog / preview / query-planner / workflow 契约测试，尚未在本轮继续处理：
    - `Task66GenerativeQueryPlannerSystemTest`
    - `SearchPreviewRuntimeHomologyContractTest`
    - `SearchSourceCatalogPropertiesTest`
    - `HeuristicSourceDiscoveryServiceTest`
    - `BrowserPreviewSearchSourceProviderTest`
    - `WorkflowFactoryTest`

## 下一步
- 先决定是否继续处理 backend 全量回归里剩余 6 项非本轮主链路失败。
- 如果继续，优先从 `SearchSourceCatalogPropertiesTest` / `SearchPreviewRuntimeHomologyContractTest` 这组 source family catalog 契约差异入手，因为它们看起来共用同一根因。

## 尚未完成
- `mvn -pl backend test` 在最新修复后的全量复跑还没有重新执行。
- plan 里的 9a / 9b 真实链路验收、9093 本地运行验证、数据库 evidence KPI 核验仍未开始。
