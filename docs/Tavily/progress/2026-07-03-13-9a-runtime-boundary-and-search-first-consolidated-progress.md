# Task13 执行进度记录

## 当前阶段
- 当前阶段：Task 1 ~ Task 5 已完成并完成定向验证，准备进入 Task 6 `Tavily audit 多 scope 合并与 scope 扩展可观测性`
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 代码实施：执行中（Task 1 ~ Task 5 已完成，Task 6 待执行）
- [ ] 回归验证：执行中（Task 1 ~ Task 5 定向验证已完成，全量回归与 9a 复跑待执行）

## 执行计划
```json
{
  "taskName": "13-9a-runtime-boundary-and-search-first-consolidated-fix",
  "sourcePlan": "docs/Tavily/task/2026-07-03-13-9a-stuck-root-cause-and-runtime-boundary-fix-plan.md",
  "updatedAt": "2026-07-04 11:08:00 +08:00",
  "steps": [
    {
      "id": "task-1-workflow-topic-contract",
      "name": "Task 1 统一 workflow topic 契约",
      "goal": "topic 合法性前移；协作 trace 复用统一 outbox；历史非法 topic 直接收口到 DLQ",
      "eta": "60 分钟",
      "dependsOn": ["root-cause-design"],
      "status": "completed"
    },
    {
      "id": "task-2-tavily-client-timeout",
      "name": "Task 2 TavilySearchClient 强制超时与取消边界",
      "goal": "把 HTTP 同步阻塞改成可控的显式超时、可取消、可审计失败结果",
      "eta": "90 分钟",
      "dependsOn": ["task-1-workflow-topic-contract"],
      "status": "completed"
    },
    {
      "id": "task-3-field-evidence-first-query-budget",
      "name": "Task 3 field evidence 第一条 query 预算门禁",
      "goal": "让第一条匹配 query 也受 deadline 与 skipped audit 约束，并区分超时与预算耗尽",
      "eta": "45 分钟",
      "dependsOn": ["task-2-tavily-client-timeout"],
      "status": "completed"
    },
    {
      "id": "task-4-stop-through-runtime-boundary",
      "name": "Task 4 stop 贯穿任务、节点、future 与迟到写回",
      "goal": "把 stop 从任务状态扩展到运行句柄取消、RUNNING 节点终态化与迟到结果护栏",
      "eta": "120 分钟",
      "dependsOn": ["task-3-field-evidence-first-query-budget"],
      "status": "completed"
    },
    {
      "id": "task-5-final-fusion-and-domain-cap",
      "name": "Task 5 候选融合 final decision 与 per-domain cap 收口",
      "goal": "补源后重算 final fusion；trace 拆分 pre/final fusion；官方同域多证据路径不再被 host 级 hard cap 误伤",
      "eta": "120 分钟",
      "dependsOn": ["task-4-stop-through-runtime-boundary"],
      "status": "completed"
    },
    {
      "id": "task-6-tavily-audit-multi-scope",
      "name": "Task 6 Tavily audit 多 scope 合并与 scope 扩展可观测性",
      "goal": "多 scope field query audit 统一 merge，并把 scope 扩展来源写入 trace / audit",
      "eta": "90 分钟",
      "dependsOn": ["task-5-final-fusion-and-domain-cap"],
      "status": "completed"
    }
  ]
}
```

## 进度看板
- [x] Task 1：统一 `WorkflowEventTopicPolicy`，协作 trace 改走统一 outbox，非法 topic 前移校验并快速收口
- [x] Task 2：`TavilySearchClient` 改为 `sendAsync()` + 显式超时 / cancel / interrupt
- [x] Task 3：field evidence 第一条 query 也走预算门禁，并保留 `SKIPPED_BUDGET_EXHAUSTED`
- [x] Task 4：`stop` 已贯穿 `TaskRuntimeCommandAppService`、`TaskRecoveryService`、`DagExecutor` 与 cancellation registry
- [x] Task 4：迟到成功结果不再覆盖 `STOPPED/SKIPPED` 终态，相关定向测试已通过
- [x] Task 5：search-first direct seed 模式下，supplement 不再被补源前 `targetCount` 卡死
- [x] Task 5：supplement / sitemap / public recovery 之后会重跑 final fusion，并把 final `effectiveTargetCount` 用于 `SELECT_TARGETS`
- [x] Task 5：`SearchExecutionTrace` 已拆出 `preSupplement*` 与 final fusion 两套口径
- [x] Task 5：官方同域候选按 `sourceType/evidencePathKey/pageType/queryIntent` 保留不同证据路径各 1 条，不再被 `maxCandidatesPerDomain` 过早裁掉
- [ ] Task 6：开始处理 Tavily audit 多 scope merge 与 scope 扩展审计

## 本次完成
- 修改文件：
  - `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
  - `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
  - `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java`
  - `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
  - `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
  - `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`
- 行为变化：
  - `DagExecutor` 的“stop 后迟到结果丢弃”断言已与当前实现语义对齐，Task 4 定向回归恢复为绿色
  - search-first direct discovery seed 模式下，supplement 候选池预算不再直接受补源前 `targetCount=1` 限死
  - `SearchExecutionCoordinator` 会在 supplement / sitemap / public recovery 改变候选池结构后重跑一次 final fusion
  - `SearchExecutionTrace` 现在同时记录补源前 fusion 口径和最终 fusion 口径，避免 trace 只剩旧数字
  - `SearchCandidateFusionPlanner` 对官方同域候选按证据路径签名做保底保留，避免 docs/pricing/help/reference 被 host 级 cap 误伤

## 定向验证结果
- 已通过：
  - `mvn -pl backend "-Dtest=CollaborationTraceServiceTest,RocketMqPropertiesTest,WorkflowEventOutboxServiceContextTest" test`
  - `mvn -pl backend "-Dtest=WorkflowFactoryTest#shouldEmbedApprovedCollaborationPlanIntoExistingWorkflowTemplate,CollaborationPlanningSmokeTest#shouldRunP2CollaborationPlanningWithoutExternalInfrastructure" test`
  - `mvn -pl backend clean "-Dtest=TavilySearchClientTest" test`
  - `mvn -pl backend clean "-Dtest=TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest" test`
  - `mvn -pl backend "-Dtest=TaskRecoveryServiceTest,TaskRuntimeCommandAppServiceTest,DagExecutorTest" test`
  - `mvn -pl backend "-Dtest=SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest" test`

## 下一步
- 进入 Task 6，先按 TDD 锁定“多 scope field query audit 被覆盖 / 空 audit 覆盖有效 audit / scope 扩展来源不可见”的失败用例
- 然后实现 `TavilyFastLaneAudit.merge(...)` 驱动的外层聚合，并把 scope 扩展来源写入 trace / audit

## 尚未完成
- Task 6 ~ Task 11 仍未执行
- `mvn -pl backend test` 全量回归尚未重新执行
- 9a 真实链路复跑、`stop` 5 秒内终态化验证、evidence KPI 校验尚未开始
## 2026-07-04 11:08 Checkpoint
- Done: Task 6 `Tavily audit` multi-scope merge and scope-expansion observability are implemented. `TavilyFastLaneProvider` now returns per-scope audit fragments and merges them once in `search()` instead of overwriting `request.tavilyFastLaneAudit` inside each scope.
- Done: `TavilyFastLaneAudit` now carries `requestedScopes`, `effectiveScopes`, and `scopeExpansionSources`, so we can see which field query expanded an `OFFICIAL` request into `DOCS` or `OPEN_WEB`.
- Done: Added 3 regression tests for multi-scope audit merge, empty-audit non-overwrite, and scope-expansion source visibility.
- Verified: `mvn -pl backend clean "-Dtest=TavilyFastLaneProviderTest" test`
- Verified: `mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchAuditSnapshotCompatibilityTest" test`
- Next: Start Task 7 with TDD, focusing on weak-content field coverage and preventing early field completion from stopping planned queries too soon.
- Remaining: Task 7 ~ Task 11, full `mvn -pl backend test`, real 9a rerun, stop-within-5-seconds terminalization verification, and evidence KPI validation.

## 2026-07-04 11:21 Checkpoint
- Done: Task 7 field-coverage early-close fix is implemented. Weak intro-style success results no longer count as completed field evidence unless they have enough substantive content, structured blocks, or promoted evidence URLs.
- Done: `SearchExecutionCoordinator.isFieldCoverageSatisfied(...)` now uses dual conditions: `completedPaths` and distinct `sourceUrls`, so stale `completedPaths` alone cannot stop later planned queries.
- Done: Added regression tests for thin-content field evidence and for the case where `completedPaths` is met but distinct evidence URLs are still below the field contract.
- Verified: `mvn -pl backend clean "-Dtest=FieldEvidenceCoverageAggregatorTest,SearchExecutionCoordinatorFieldEvidenceTest" test`
- Next: Start Task 8, focusing on explicit URL canonicalize rejection observability and keeping internal-link discovery on the current no-HTTP runtime boundary.
- Remaining: Task 8 ~ Task 11, full `mvn -pl backend test`, real 9a rerun, stop-within-5-seconds terminalization verification, and evidence KPI validation.

## 2026-07-04 11:42 Checkpoint
- Done: Task 8 explicit `competitorUrls` rejection observability is implemented in `SearchExecutionCoordinator`. Canonicalize failures and duplicate canonical URLs are no longer silently dropped; they are persisted as `discardedCandidates` with stable reason codes `EXPLICIT_URL_CANONICALIZE_FAILED` and `EXPLICIT_URL_DUPLICATE_CANONICAL`.
- Done: `LOAD_CANDIDATES` step messaging now includes explicit URL discard diagnostics, so audit replay can explain why a user-provided URL never entered the candidate pool.
- Done: `InternalLinkDiscoveryService` runtime-boundary guard remains intact. The new regression test confirms that when the current result has no content, the service returns empty instead of attempting any child-page HTTP fetch.
- Verified: `mvn -pl backend clean "-Dtest=SearchExecutionCoordinatorTest,InternalLinkDiscoveryServiceTest" test`
- Verified: `mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchAuditSnapshotCompatibilityTest" test`
- Next: Start Task 9, focusing on the dead commented legacy block / maintainability cleanup called out in the plan, while keeping current runtime behavior unchanged.
- Remaining: Task 9 ~ Task 11, full `mvn -pl backend test`, real 9a rerun, stop-within-5-seconds terminalization verification, and evidence KPI validation.

## 2026-07-04 11:55 Checkpoint
- Done: Task 9 runtime observability behavior items are implemented. `DagExecutor.markNodeRunning(...)` now stamps `lastAttemptAt` at the same moment a node enters `RUNNING`, so runtime snapshots can show when the current attempt actually started.
- Done: Added a regression test proving `lastAttemptAt` is persisted on node start, and added a field-evidence audit regression test proving `tavily timeout after xxxms` / `tavily interrupted` failure reasons survive into `SearchExecutionTrace` and audit summary.
- Done: Task 10 old contract tests are updated to the current source-family semantics: official family `primaryTools` now expect `PUBLIC_SEARCH`, `auxiliaryTools` now hold `WEB_SCRAPER` / `JINA_READER`, and Task66 third-party `REVIEW` / `NEWS` queries no longer assert official-domain locking.
- Verified: `mvn -pl backend clean "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,DagExecutorTest" test`
- Verified: `mvn -pl backend "-Dtest=WorkflowFactoryTest#shouldEmbedSourceCandidatesIntoCollectorNodeConfig,SearchSourceCatalogPropertiesTest,SearchPreviewRuntimeHomologyContractTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,Task66GenerativeQueryPlannerSystemTest" test`
- Next: Return to Task 9 Step 1 and finish removing the historical commented dead code in `SearchExecutionCoordinator.java`, then move to Task 11 workspace-log hygiene and broader/full regression.
- Remaining: Task 9 dead-code cleanup, Task 11 log hygiene decision/exclusion, full `mvn -pl backend test`, real 9a rerun, stop-within-5-seconds terminalization verification, and evidence KPI validation.

## 2026-07-04 12:29 Checkpoint
- Done: Task 9 Step 1 dead-code cleanup is finished in `SearchExecutionCoordinator.java`. The unused `resolveInitialCandidates(...)` / legacy `mergeConfiguredCandidatesWithExplicitUrls(...)` methods are removed, duplicated commented `buildLoadCandidatesMessage(...)` blocks are deleted, and the abandoned `resolveExecutableFieldEvidenceQueries(...)` quota branch is cleared out.
- Done: During cleanup, `SearchExecutionCoordinator.java` hit multiple historical string/comment corruption hotspots. Those user-facing step messages were normalized into stable readable text, and the field-evidence supplement summary is now emitted as a structured English message: `field query plan X, executed Y, skipped Z, elapsed ...`.
- Done: The focused search coordinator tests were re-aligned to those stable runtime messages without changing the functional assertions around fallback decisions, verification flow, or audit propagation.
- Verified: `mvn -pl backend clean "-Dtest=SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest" test`
- Checked: `git status --short -- backend/logs` still reports workspace log pollution in `backend/logs/competitor-agent.log` and `backend/logs/live-restart-9093-20260703-195805.out.log`.
- Next: Decide how to handle Task 11 log hygiene without deleting user/runtime evidence, then expand regression outward from the focused search tests to broader/backend-wide coverage.
- Remaining: Task 11 log hygiene decision/exclusion, broader grouped regression (`mvn -pl backend test` still pending), real 9a rerun, stop-within-5-seconds terminalization verification, and evidence KPI validation.

## 2026-07-04 12:54 Checkpoint
- Done: Fixed a real regression exposed only by the broader collector stack. `CollectorAgent.applyEvidenceQualityGate(...)` no longer overwrites a stronger upstream `EvidenceRepairPlan` with a weaker recomputed state, so second-round promoted evidence can still close the field-coverage loop.
- Done: Added a focused regression test `CollectorAgentEvidenceQualityGateTest#shouldPreservePromotedRepairPlanWhenQualityGateReevaluatesResult` to lock the runtime boundary where a `REPAIR_EVIDENCE_PROMOTED` result previously got downgraded to `REPAIR_NOT_REQUIRED`.
- Done: The broader field loop integration test `CollectorAgentFieldEvidenceLoopTest` is green again, confirming the second collection round now produces `coreFeatures.status=SUFFICIENT` instead of being stuck at `EVIDENCE_PATH_COVERAGE_NOT_MET`.
- Done: Task 11 log hygiene is closed with a non-destructive index cleanup. Ran `git rm --cached -r -- backend/logs`, which removes tracked runtime logs from Git index while leaving local files on disk, and fixed `.gitignore` so `backend/logs/` is now a valid ignore rule instead of an inline-comment false positive.
- Verified: `mvn -pl backend -Dtest=CollectorAgentEvidenceQualityGateTest#shouldPreservePromotedRepairPlanWhenQualityGateReevaluatesResult test`
- Verified: `mvn -pl backend -Dtest=CollectorAgentFieldEvidenceLoopTest test`
- Verified: `mvn -pl backend test`
- Checked: Live rerun is currently blocked by environment readiness, not by code status. Fast TCP checks at `2026-07-04 12:54 +08:00` show `5432=False`, `16379=False`, `9876=False`, `9093=False`, and `http://127.0.0.1:9093/actuator/health` is unreachable.
- Next: If the local dev stack is brought back (`PostgreSQL + Redis + RocketMQ + backend 9093`), reuse `tmp/task12-9a-20260703-121724/create-request.json` to execute a fresh 9a rerun, then capture stop-within-5-seconds terminalization evidence and KPI snapshots.
- Remaining: Real 9a rerun, stop-within-5-seconds runtime terminalization verification, and evidence KPI validation remain pending because the live environment is offline.
