# Phase 5 Modularization Evaluation Task

## 核心目标

在前四个阶段完成后，基于真实边界稳定性、白名单规模、测试守卫、共享热点和阶段接力冲突情况，形成是否继续演进为 Maven 多模块的工程结论。

## 预期耗时

- `0.5 - 1` 人天

## 前置依赖

- `phase1` 至 `phase4b` 全部完成

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase5-modularization-evaluation-progress.md`

## 完成定义

- 已创建 `2026-06-10-modularization-evaluation-report.md`
- 评估报告包含数据采集步骤、指标来源、结论判定步骤、最终建议输出格式
- 结论不是模板空壳，能够明确回答“现在要不要拆 Maven 模块”
- 评估使用真实 ArchUnit、白名单和共享热点数据，而不是主观偏好

## 文件边界

### Must Modify

- `docs/superpowers/task/2026-06-10-modularization-evaluation-report.md`
- `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`

### May Modify

- `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`

### Read For Context

- `docs/superpowers/task/2026-06-10-phase1-agent-runtime-baseline-progress.md`
- `docs/superpowers/task/2026-06-10-phase2-archunit-boundary-progress.md`
- `docs/superpowers/task/2026-06-10-phase3a-task-orchestration-progress.md`
- `docs/superpowers/task/2026-06-10-phase3b-collection-evidence-progress.md`
- `docs/superpowers/task/2026-06-10-phase4a-knowledge-intelligence-progress.md`
- `docs/superpowers/task/2026-06-10-phase4b-report-conversation-progress.md`

## 评估输入

- ArchUnit 当前规则通过情况
- 白名单总数与集中热点
- 剩余跨模块 repository 直连数
- 剩余共享 contract 热点
- 最近 3 次阶段合入是否仍反复修改同一热点文件

---

## Task 1: 采集结构化评估数据

### Task 核心目标

把前四阶段留下的结构信号统一收集到一份可复查的报告中。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- phase1 - phase4b 完成

### 执行步骤

- [ ] Step 1：创建 `2026-06-10-modularization-evaluation-report.md`。
- [ ] Step 2：填充模块边界状态、ArchUnit 状态、共享热点、协作信号。
- [ ] Step 3：记录每项指标的数据来源。

### 报告最小结构

```markdown
## 1. Current Boundary Status

- agent-runtime baseline status:
- task-orchestration facade status:
- collection-intelligence facade status:
- knowledge-intelligence facade status:
- report/conversation facade status:

## 2. Metrics And Sources

- ArchUnit rule count:
  - source: `BackendModuleDependencyTest`
- whitelist item count:
  - source: `2026-06-10-architecture-whitelist-ledger.md`
- remaining cross-module repository direct dependencies:
  - source: code scan + phase progress
- shared hotspots:
  - source: whitelist ledger + progress docs + recent stage merges
```

### 验证命令

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

---

## Task 2: 应用判定规则并写出结论

### Task 核心目标

基于客观阈值给出“继续维持模块化单体 / 部分拆分 / 暂缓物理拆分”的建议，而不是停在模板。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：在报告中补齐 Decision Rules。
- [ ] Step 2：对三种结论模板都给出适用条件。
- [ ] Step 3：写出最终摘要，明确为什么不是另外两种选项。

### 判定规则最小形状

```markdown
## Decision Rules

- If whitelist item count > 5, do not split Maven modules yet.
- If remaining cross-module repository direct dependencies > 0, do not split Maven modules yet.
- If `BaseAgent` and `workflow.contract` still hold more than 2 shared hotspots, delay physical split.
- If recent stage merges still repeatedly touch the same hotspot files, delay physical split.
- If task / collection / knowledge / report / conversation all expose stable facades and ArchUnit shows no new violations, partial split can be considered.
```

### 最终摘要最小结构

```markdown
## Final Summary

- decision:
- why:
- what blocked a different decision:
- next re-evaluation checkpoint:
```

### 验证命令

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

---

## Task 3: 阶段收尾

### Task 核心目标

确认 phase5 是工程评估任务，不是再开启一轮代码重构。

### Task 预期耗时

- `1` 小时

### Task 前置依赖

- Task 1、Task 2 完成

### 执行步骤

- [ ] Step 1：检查报告是否包含数据采集、指标来源、判定步骤、最终建议。
- [ ] Step 2：更新 progress，记录下次复评触发条件。

### 提交标准

- 只包含评估报告与评估所需台账更新
- 不直接开启 Maven 多模块改造
- PR 描述必须写明“当前结论为什么不是另外两个选项”
