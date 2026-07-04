# Task84 字段 Query 配额闸门与两阶段 Tavily 方案进度

当前阶段：代码实施与定向回归
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 代码实施（Task1-Task9）：已完成
- [ ] 集成复测（Task10）：执行中
- [ ] 9a 真实复测：待执行

## 1. 本次执行范围

```json
{
  "taskName": "14-task84-field-query-quota-gate-and-two-stage-tavily",
  "updatedAt": "2026-07-04 16:03:00 +08:00",
  "completedTasks": ["Task1", "Task2", "Task3", "Task4", "Task5", "Task6", "Task7", "Task8", "Task9"],
  "inProgressTasks": ["Task10"],
  "nextTasks": ["Task10-9a-retest"],
  "remainingTasks": ["Task10"]
}
```

## 2. 已完成内容

- Task1-Task6：已完成字段 query 配额闸门、两阶段 Tavily fast-lane 语义、discovery-only 候选语义拆分，以及相关回归测试。
- Task7：已在 `CollectorAgent` 增加 stop/terminate 运行期写入护栏；当 task 已 `STOPPED`、node 已 `TERMINATE_REQUESTED`、线程被中断，或 node 已不再 `RUNNING` 时，迟到结果会统一按 `DISCARDED_AFTER_STOP` 丢弃，不再写入 evidence、检索索引或 running output。
- Task8：已调整 `EvidenceQualityGate` 的 auth-gate 判定；对于 Tavily 返回的长正文官方/文档页，不再因为弱 auth 信号被一刀切误杀，而是降级为 `AUTH_GATE_WEAK_SIGNAL`，同时保留对真正登录壳页的拦截能力。
- Task9：已补齐 search-first target count 的审计字段与测试；`SearchExecutionTrace` 现在会输出 `requestedTargetCount`、`effectiveTargetCount`、`searchFirstMinimumTargetCount`、`targetCountReason`，并明确记录“为了保住官方/文档/第三方证据多样性而扩容”的原因。

## 3. 关键结果

- field evidence query 现在已经稳定收口到“字段内配额 + 节点 fail-safe + 两阶段 Tavily”这一套主链路上，避免再把膨胀后的 planned query 原样透传给 provider。
- collector 的 stop 语义已经真正下沉到落库边界，不再只依赖 `DagExecutor` 的外层收口。
- Tavily 的长正文官方页不再被弱 auth 信号误判为不可用证据，官方/文档正文保留率提升。
- search-first 场景下，1 个显式 URL 不会再把最终 selected target 压成 1；trace 中也能直接看出请求目标数、实际目标数、最小保留目标数和扩容原因。

## 4. 验证记录

- `mvn clean "-Dtest=DagExecutorTest,CollectorAgentTest" test`：PASS
- `mvn "-Dtest=CollectorAgentEvidenceQualityGateTest" test`：PASS
- `mvn "-Dtest=CollectionTargetSelectorTest,SearchExecutionCoordinatorTest" test`：PASS
- `mvn "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest,TavilyFieldEvidenceProfileResolverTest,TavilyFastLaneProviderTest,CollectionTargetSelectorTest,CollectorAgentEvidenceQualityGateTest" test`：PASS

## 5. 下一步

- Task10：读取 `tmp/task12-9a-<timestamp>/` 的结构化输出，按任务文档要求做 9a 真实复测。
- 复测时只读取结构化字段口径：
  - `searchAudit.tavilyFastLaneAudit.fieldEvidenceQueryExecutions[*].status`
  - `searchAudit.tavilyFastLaneAudit.fieldEvidenceQueryExecutions[*].failureReason`
  - `searchAudit.tavilyFastLaneAudit.queriesSent`
  - `fieldEvidenceQueryPlannedCount`
  - `fieldEvidenceQueryExecutedCount`
  - `fieldEvidenceQuerySkippedCount`
- 根据 9a 结果确认：
  - query 是否从 71 收敛到预期区间
  - collector 是否可自行跑完
  - evidence 是否恢复为大于 0
  - 第三方来源是否未被饿死

## 6. 尚未完成

- Task10 的 9a 真实复测与结果验收尚未执行。
- 真实复测后如出现新回归，还需要继续补代码或补测试。
