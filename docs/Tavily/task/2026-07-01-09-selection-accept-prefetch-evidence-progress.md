# Task66-09 执行进度记录

## 当前阶段
- 当前阶段：定向实现与单测验证完成，等待端到端复验

## 执行计划
```json
{
  "taskName": "09-selection-accept-prefetch-evidence-task",
  "sourcePlan": "docs/Tavily/task/2026-06-30-09-selection-accept-prefetch-evidence-task.md",
  "updatedAt": "2026-07-01",
  "steps": [
    {
      "id": "task0",
      "name": "记录 registry 运行期引用边界",
      "goal": "明确 prefetchedContentRef 仅在同进程运行期有效，且选源阶段不能消费 registry",
      "eta": "5 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "task6",
      "name": "激活第三方 evidence 路径 query 生成",
      "goal": "让 required=false 的 PUBLIC_REVIEW_OR_NEWS 也能按受控数量生成 query",
      "eta": "20 分钟",
      "dependsOn": ["task0"],
      "status": "completed"
    },
    {
      "id": "task1to3",
      "name": "放行 prefetch 候选并修正排序与审计",
      "goal": "让可用 prefetch 真文候选入选、严格优先于 verified 根域壳页、并保留专属审计文案",
      "eta": "30 分钟",
      "dependsOn": ["task6"],
      "status": "completed"
    },
    {
      "id": "task4",
      "name": "运行定向单测验证",
      "goal": "验证 FieldEvidenceQueryPlannerTest 与 CollectionTargetSelectorTest 通过",
      "eta": "10 分钟",
      "dependsOn": ["task6", "task1to3"],
      "status": "completed"
    }
  ]
}
```

## 进度看板
- [x] 读取任务文档与相关 skill
- [x] 定位 `FieldEvidenceQueryPlanner`、`CollectionTargetSelector`、对应测试与 `TavilyPrefetchedContentRegistry`
- [x] 为第三方路径 query 生成补失败测试
- [x] 为 prefetch 入选/排序/审计补失败测试
- [x] 实现生产代码
- [x] 运行定向单测
- [ ] 重跑 task69/task75 同类任务做端到端复验

## 运行期边界记录
- `TavilyPrefetchedContentRegistry` 当前只有 `register/remove/size`，没有 `contains/peek`。
- `remove(ref)` 是消费式读取；选源阶段不能调用，否则会提前消耗正文引用。
- 本次修复只覆盖同进程运行期的 `选源 -> 打包 -> 执行` 链路；跨进程恢复或仅靠持久化快照恢复导致的 `prefetchedContentRef` 失效，不在本次范围内。

## 已完成实现
- `FieldEvidenceQueryPlanner`
  - 非 required 路径不再一律跳过。
  - 仅对第三方/开放网络语义的 optional path 开启 best-effort query 生成。
  - 每条 optional 第三方路径最多保留 2 个 query variant，避免数量膨胀。
- `CollectionTargetSelector`
  - 新增 Tavily prefetch 可用候选入选分支，条件严格对齐 `fastLaneUsable + hasPrefetchedContent + prefetchedContentRef`。
  - 新增排序规则：prefetch 真文仅严格优先于 verified 根域壳页，不误伤 verified 真内容候选。
  - 新增 prefetch 选中后的专属审计文案：`Tavily prefetch 正文可用`。
  - 调整 discarded 汇总，避免把本轮未参与 attemptedTarget 的旧显式直达丢弃项重复写入诊断输出。

## 单测结果
- 2026-07-01 已执行：
  - `mvn -Dtest=FieldEvidenceQueryPlannerTest#shouldPlanBestEffortThirdPartyQueriesForNonRequiredReviewPath test`：先 FAIL 后 PASS
  - `mvn -Dtest=FieldEvidenceQueryPlannerTest test`：PASS
  - `mvn -Dtest=CollectionTargetSelectorTest#shouldSelectUsableTavilyPrefetchCandidateAheadOfHigherScoredVerifiedRootShell test`：先 FAIL 后 PASS
  - `mvn -Dtest=CollectionTargetSelectorTest test`：PASS
  - `mvn "-Dtest=FieldEvidenceQueryPlannerTest,CollectionTargetSelectorTest" test`：PASS（14 tests）

## 下一步
- 按原任务文档执行 Task 5 / §5.6 端到端复验：
  - 重跑 task69 或 task75 同类任务。
  - 核对 `fieldEvidencePaths` 是否出现 `PUBLIC_REVIEW_OR_NEWS`。
  - 核对第三方长文 prefetch 候选是否进入 `selectedTargets` 并落库 `evidence_source`。
  - 核对 `selectionReason` 是否为 `Tavily prefetch 正文可用`。
