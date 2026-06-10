# Backend Modular Monolith Refactor Task Index

## 文档说明

这里收口“后端模块化单体重构”的所有可执行任务文档。

- 总路线图仍保留在：
  - `docs/superpowers/plans/2026-06-09-backend-modular-monolith-refactor-roadmap.md`
- 设计依据仍保留在：
  - `docs/superpowers/specs/2026-06-09-backend-modular-monolith-refactor-design.md`
- `docs/superpowers/task` 只放“可直接执行任务”和与执行绑定的 progress 文档，不再承担设计讨论、样例展示或目标形态说明。

## 统一执行约定

- 首次运行测试允许先失败，目的是暴露“当前代码基线”和“计划约束”之间的真实差异。
- task 文档中的测试代码块不是“目标形态样例”，而是必须落地的最小测试结构。
- 如果当前生产代码的构造器、辅助方法或对象命名不同，只允许做同义适配，不允许删减 `setup / mock / call / assert / expected signal` 五类要素。
- 如果实现现状与 task 文档冲突，先更新 task 文档中的边界说明，再继续落代码；不允许通过弱化测试、扩大白名单或跳过 progress 记录绕过问题。
- 每个 phase task 文档都必须显式包含：
  - 核心目标
  - 预期耗时
  - 前置依赖
  - 进度持久化文件
  - 完成定义
  - `Must Modify / May Modify / Read For Context`
- 每个 phase 都必须维护独立 progress 文档；执行中断后恢复时，先更新 progress，再继续改代码。
- 每次对白名单、facade、cleanup port、共享 contract 的新增或回收，都要同步更新对应台账与 progress 文档。
- task 文档只记录提交标准，不预写死 `git add` / `git commit` 命令。

## 统一进度输出格式

所有 progress 文档都使用以下最小结构：

```markdown
- 当前阶段：Phase X ...
- 当前 Task：Task N ...
- 当前 Step：Step M ...
- 状态：PENDING | SUCCESS | FAILED
- 已完成：A / B
- 剩余步骤：...
- 阻塞项：无 / 具体阻塞说明
- 最后更新：YYYY-MM-DD HH:mm
```

## 任务与进度文档

- `docs/superpowers/task/2026-06-10-phase1-agent-runtime-baseline-task.md`
- `docs/superpowers/task/2026-06-10-phase1-agent-runtime-baseline-progress.md`
- `docs/superpowers/task/2026-06-10-phase2-archunit-boundary-task.md`
- `docs/superpowers/task/2026-06-10-phase2-archunit-boundary-progress.md`
- `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-task.md`
- `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-progress.md`
- `docs/superpowers/task/2026-06-10-phase3b-collection-evidence-task.md`
- `docs/superpowers/task/2026-06-10-phase3b-collection-evidence-progress.md`
- `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-task.md`
- `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`
- `docs/superpowers/task/2026-06-10-phase4b-report-conversation-task.md`
- `docs/superpowers/task/2026-06-10-phase4b-report-conversation-progress.md`
- `docs/superpowers/task/2026-06-10-phase5-modularization-evaluation-task.md`
- `docs/superpowers/task/2026-06-10-phase5-modularization-evaluation-progress.md`

## 强制执行顺序

1. 先确认 `task-index`、当前阶段 task 文档、当前阶段 progress 文档三者一致，再开始改代码。
2. 串行执行 `phase1-agent-runtime-baseline-task`。
3. 只有当 phase1 已合入共享集成线后，才允许串行执行 `phase2-archunit-boundary-task`。
4. 只有当 phase2 已合入共享集成线后，才允许串行执行 `phase3a-task-orchestration-task`。
5. 只有当 phase3a 已合入共享集成线后，才允许串行执行 `phase3b-collection-evidence-task`。
6. 只有当 phase3b 已合入共享集成线后，才允许串行执行 `phase4a-knowledge-intelligence-task`。
7. 只有当 phase4a 已合入共享集成线后，才允许串行执行 `phase4b-report-conversation-task`。
8. 只有当 phase4b 已合入共享集成线后，才允许串行执行 `phase5-modularization-evaluation-task`。
9. 任何人不得跳阶段开工；如果前置阶段未合入共享集成线，只能更新文档和 progress，不能提前落后续阶段代码。

## 阶段入口条件

- 进入 phase3a 前必须同时满足：
  - `phase1` progress 状态为 `SUCCESS`
  - `phase2` progress 状态为 `SUCCESS`
  - `integration/backend-modular-monolith-refactor` 已包含 phase1、phase2 变更
- 进入 phase3b 前必须同时满足：
  - `phase3a` progress 状态为 `SUCCESS`
  - `integration/backend-modular-monolith-refactor` 已包含 phase3a 变更
  - 白名单台账与 progress 已同步更新
- 进入 phase4a 前必须同时满足：
  - `phase3b` progress 状态为 `SUCCESS`
  - `integration/backend-modular-monolith-refactor` 已包含 phase3b 变更
  - 白名单台账与 progress 已同步更新
- 进入 phase4b 前必须同时满足：
  - `phase4a` progress 状态为 `SUCCESS`
  - `integration/backend-modular-monolith-refactor` 已包含 phase4a 变更
- 进入 phase5 前必须同时满足：
  - `phase4b` progress 状态为 `SUCCESS`
  - `integration/backend-modular-monolith-refactor` 已包含 phase4b 变更
  - ArchUnit 无新增违规

## 串行执行主线

- 固定主线：`phase1 -> phase2 -> phase3a -> phase3b -> phase4a -> phase4b -> phase5`
- 每个阶段结束后，必须先把结果合入 `integration/backend-modular-monolith-refactor`，下一阶段才能开工。
- 如果后续重新恢复多人协作，也必须先以当前串行版本 task 文档为准，不允许直接回退到旧的双线并行口径。

## 分支与合并规则

- 主线固定为 `main`
- 本次重构的共享集成线固定为 `integration/backend-modular-monolith-refactor`
- 每个阶段都从最新共享集成线拉出新的阶段分支，完成后只合回共享集成线，不直接把阶段分支合入 `main`

推荐分支形态如下：

- `integration/backend-modular-monolith-refactor`
- `a/phase1-agent-runtime`
- `a/phase2-archunit-boundary`
- `a/phase3a-task-orchestration`
- `a/phase3b-collection-evidence`
- `a/phase4a-knowledge-intelligence`
- `a/phase4b-report-conversation`
- `a/phase5-modularization-evaluation`

强制合并顺序如下：

1. 从 `main` 拉出 `integration/backend-modular-monolith-refactor`
2. 完成 `phase1` 后，把 `a/phase1-agent-runtime` 合入 `integration/backend-modular-monolith-refactor`
3. 完成 `phase2` 后，把 `a/phase2-archunit-boundary` 合入 `integration/backend-modular-monolith-refactor`
4. 完成 `phase3a` 后，把 `a/phase3a-task-orchestration` 合入 `integration/backend-modular-monolith-refactor`
5. 完成 `phase3b` 后，把 `a/phase3b-collection-evidence` 合入 `integration/backend-modular-monolith-refactor`
6. 完成 `phase4a` 后，把 `a/phase4a-knowledge-intelligence` 合入 `integration/backend-modular-monolith-refactor`
7. 完成 `phase4b` 后，把 `a/phase4b-report-conversation` 合入 `integration/backend-modular-monolith-refactor`
8. 完成 `phase5` 后，把 `a/phase5-modularization-evaluation` 合入 `integration/backend-modular-monolith-refactor`
9. 全部阶段完成并通过总体验证后，只做一次最终合并：`integration/backend-modular-monolith-refactor -> main`

## 合并前检查清单

每次把阶段分支合入 `integration/backend-modular-monolith-refactor` 之前，必须同时完成以下检查：

1. 先同步最新 `integration/backend-modular-monolith-refactor` 到本地阶段分支。
2. 解决冲突后重新运行该阶段 task 文档中列出的全部聚焦测试命令。
3. 更新对应 progress 文档，把当前 Task / Step / 状态 / 已完成数写到最新。
4. 如果本阶段涉及白名单、facade、cleanup port、shared contract，必须同步更新对应台账文档。
5. 自检本阶段是否改到了不属于当前阶段范围的热点文件；若改到，必须先收敛并确认边界后再合并。

## 合并纪律

- 所有阶段分支只允许合到 `integration/backend-modular-monolith-refactor`
- `main` 只接受 `integration/backend-modular-monolith-refactor` 的最终汇总合并，不接受任一阶段分支直接进入
- 如果某阶段聚焦测试未全部通过，该阶段禁止合并
- 如果 progress 文档未更新到最新状态，该阶段禁止合并
- 如果共享台账未同步更新，该阶段禁止合并

## 热点文件所有权

- `BaseAgent`：只允许 phase1 或单独小 PR 修改
- `DagExecutor`：不作为 phase3b 可改文件
- `SpringAgentCapabilityRegistry`、`AgentContext`、`AgentResult`：只允许 phase1 修改
- `BackendModuleDependencyTest`：phase2 为主修改入口；phase3 以后如需新增规则，必须先同步 phase2 规则结构
- `TaskArtifactCleanupService`：phase3a 主改，其他阶段只能通过 cleanup port 接入，不直接扩散修改
- `EvidenceQueryService`：phase3b 主改；phase4b 只能消费其稳定投影视图，不扩大为 report 运行时入口

## 协作约束

- 所有跨模块调用默认只允许经过 facade / cleanup port
- `BackendModuleDependencyTest` 是边界规则唯一入口；`ArchitecturePackageMapping`、`ArchitectureWhitelist`、`ArchitectureWhitelistTest` 只是支撑资产
- 如果实现现状与 task 冲突，先改 task 文档，再改代码；不允许边写代码边口头改变规则
- 如果后续重新恢复多人协作并同时需要触碰同一热点文件，必须先停下来，把修改收敛为单独小 PR，再继续后续阶段
