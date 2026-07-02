# Task66-11 执行进度记录

## 当前阶段
- 当前阶段：端到端复验已完成，旧 field query 卡死问题已关闭；进入采集质量与质量闭环问题修复评估

## 执行计划
```json
{
  "taskName": "11-field-query-execution-budget-observability",
  "sourcePlan": "docs/Tavily/task/2026-07-01-11-field-query-execution-budget-observability-plan.md",
  "updatedAt": "2026-07-02 16:20:00 +08:00",
  "steps": [
    {
      "id": "a-tests",
      "name": "档 A 失败测试",
      "goal": "锁定放大前预算 quota、planned/executable/skipped 分层、trace/summary 新字段",
      "eta": "20 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "a-impl",
      "name": "档 A 最小实现",
      "goal": "在 coordinator 层实现预算分层与 request/trace/summary 透传",
      "eta": "30 分钟",
      "dependsOn": ["a-tests"],
      "status": "completed"
    },
    {
      "id": "a-regression",
      "name": "档 A 定向回归",
      "goal": "确认 SearchExecutionCoordinatorFieldEvidenceTest 与 SearchPolicyResolverTest 兼容",
      "eta": "15 分钟",
      "dependsOn": ["a-impl"],
      "status": "completed"
    },
    {
      "id": "b-tests",
      "name": "档 B 失败测试",
      "goal": "锁定 provider 循环预算熔断、budget exhausted skip、per-query timeout/budget 兜底",
      "eta": "25 分钟",
      "dependsOn": ["a-regression"],
      "status": "completed"
    },
    {
      "id": "b-impl",
      "name": "档 B 最小实现",
      "goal": "在 TavilyFastLaneProvider / TavilySearchClient 接入 deadline 与剩余预算约束",
      "eta": "35 分钟",
      "dependsOn": ["b-tests"],
      "status": "completed"
    },
    {
      "id": "o-tests-impl",
      "name": "可观测与审计",
      "goal": "补齐 step message、trace/audit per-query 细节与契约回归",
      "eta": "40 分钟",
      "dependsOn": ["b-impl"],
      "status": "completed"
    },
    {
      "id": "regression-e2e",
      "name": "回归与端到端复跑",
      "goal": "执行定向回归、评估全量测试噪声，并准备 task77/78 复跑",
      "eta": "45 分钟",
      "dependsOn": ["o-tests-impl"],
      "status": "completed"
    }
  ]
}
```

## 进度看板
- [x] 读取任务方案与相关代码落点
- [x] 建立档 A 红测：`SearchExecutionCoordinatorFieldEvidenceBudgetTest`
- [x] 建立档 A 最小实现：`SearchExecutionCoordinator` / `SearchPolicyResolver` / `SearchExecutionTrace` / `SearchAuditSummary` / `SearchSourceRequest`
- [x] 修复 `searchTimeoutMillis=0` 且 pending field query 仍需继续 supplement 的回归
- [x] 定向回归通过：`SearchExecutionCoordinatorFieldEvidenceBudgetTest`、`SearchExecutionCoordinatorFieldEvidenceTest`、`SearchPolicyResolverTest`
- [x] 建立档 B 红测：`TavilyFastLaneProviderTest` / `TavilySearchClientTest`
- [x] 落地档 B 最小实现：`TavilyFastLaneProvider` / `TavilySearchClient`
- [x] 补齐可观测/审计契约：provider per-query audit、coordinator trace/summary 透传、step message 计数摘要
- [x] 执行 O1/O3 定向回归
- [x] 执行端到端复跑
- [x] 记录旧问题关闭证据与新暴露问题

## 当前进展说明
- 档 A 已按 TDD 完成，当前预算分层行为如下：
  - `DimensionEvidencePlan.allPlannedQueries()` 仍保持完整读取口，不做截断。
  - `SearchExecutionCoordinator` 新增 planned / executable / skipped 三层分离逻辑。
  - executable 配额基于放大前 `searchTimeoutMillis` 计算，避免被 `ensureMinimumTimeoutForFieldEvidenceQueries()` 反推回“全量可执行”。
  - `SearchSourceRequest` 只透传 executable 子集，同时保留 planned/executable/skipped 计数快照。
  - `SearchExecutionTrace` 与 `SearchAuditSummary` 已补齐 planned/executed/skipped/skipReasons 结构化字段。
- 为保持既有“不饿死 pending field query”语义，当放大前预算算出的 quota 为 `0` 时，当前实现保留 `1` 条最高优先级 query 继续执行，后续由档 B 的循环内熔断负责兜底。
- 档 B 已按 TDD 完成，当前执行层行为如下：
  - `SearchSourceRequest` 新增 `fieldEvidenceExecutionDeadlineEpochMillis`，由 coordinator 在进入 supplement 前下传。
  - `TavilyFastLaneProvider.searchFieldEvidenceQueries(...)` 已接入剩余预算判断：
    - 第一条 pending query 允许起跑；
    - 起跑后若剩余预算低于 `1s`，停止启动后续 query。
  - `TavilySearchClient` 新增 `search(profile, queryBudgetMillis)` 重载，并把 per-call budget 收紧到 HTTP request timeout。
  - provider 已补齐 `SKIPPED_BUDGET_EXHAUSTED` 的 per-query 审计明细，不再只靠空结果解释预算耗尽。
- 可观测 O1/O3 已按 TDD 完成：
  - 新增 `FieldEvidenceQueryExecutionAudit`，逐条记录 query 指纹、字段、路径、状态、耗时、结果数、requestId、失败原因与跳过原因。
  - `TavilyFastLaneProvider` 在 `SearchSourceRequest.tavilyFastLaneAudit` 回填 provider 侧真实执行审计。
  - `SearchExecutionCoordinator` 消费 provider audit，把真实 executed/skipped 口径透传到 `SearchExecutionTrace`、`SearchAuditSummary` 与顶层 `TavilyFastLaneAudit`。
  - `BROWSER_SUPPLEMENT_SEARCH` 成功文案追加 `field query 计划/实际执行/跳过/累计耗时/跳过原因`，便于任务详情页直接定位预算跳过。

## 定向验证结果
- 2026-07-01 已执行：
  - `mvn -pl backend -DskipITs -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest test`
    - 首次：按预期编译失败，暴露 trace/summary 新字段缺失
    - 修复后：PASS（2 tests）
  - `mvn -pl backend -DskipITs -Dtest=SearchExecutionCoordinatorFieldEvidenceTest#shouldNotSkipSupplementWhenTimedOutButFieldQueriesStillPending test`
    - 首次回归失败，暴露 quota=0 饿死 pending field query 的兼容问题
    - 修复后：PASS
  - `mvn -pl backend -DskipITs "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchPolicyResolverTest" test`
    - PASS（23 tests）
  - `mvn -pl backend -DskipITs -Dtest=TavilyFastLaneProviderTest test`
    - 首次：按预期失败，暴露 request deadline 接口缺失
    - 修复后：PASS（8 tests）
  - `mvn -pl backend -DskipITs -Dtest=TavilySearchClientTest test`
    - PASS（4 tests）
  - `mvn -pl backend -DskipITs "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchPolicyResolverTest,TavilyFastLaneProviderTest,TavilySearchClientTest" test`
    - PASS（35 tests）
  - `mvn -pl backend -DskipITs -Dtest=TavilyFastLaneProviderTest#shouldRecordPerQueryAuditForExecutedFailedAndBudgetSkippedFieldEvidenceQueries test`
    - 首次：按预期编译失败，暴露 `SearchSourceRequest` 缺少 provider audit 回填通道
    - 修复后：PASS
  - `mvn -pl backend -DskipITs -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest#shouldExposeProviderFieldQueryAuditInTraceSummaryAndSupplementStepMessage test`
    - 首次：按预期失败，暴露 trace 仍把 executable 当 executed，未消费 provider 真实审计
    - 修复后：PASS
  - `mvn -pl backend -DskipITs "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,TavilyFastLaneProviderTest" test`
    - PASS（12 tests）
  - `mvn -pl backend -DskipITs "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchPolicyResolverTest,TavilyFastLaneProviderTest,TavilySearchClientTest,SearchExecutionCoordinatorTest#shouldAggregateTavilyFastLaneAuditIntoTraceSnapshotAndSummary,SearchAuditSnapshotCompatibilityTest" test`
    - PASS（40 tests）

## 端到端复验结果
- 2026-07-02 已执行 9093 端到端复验：
  - 已清理旧运行痕迹：`backend/logs`、根目录 `logs`、旧 `tmp/task*` 任务目录；Redis `competitor-agent:task:*` 扫描为空；通过接口删除旧任务 `69..78`。
  - 9093 已重启：`Tomcat started on port 9093`，应用启动完成时间为 2026-07-02 15:44:23。
  - 新建任务 `79`：`抖音开放平台 vs B站开放平台竞品分析-11端到端复验-20260702-1550`。
  - 任务最终状态：`STOPPED`，`completedNodes=10/10`，原因是初审未通过且需要人工介入。
- 旧问题关闭证据：
  - 两个采集节点均推进到 `COLLECT_PAGES` 并完成，不再停在 field query 补源阶段。
  - `collect_sources_01_01`：`SUCCESS`，耗时约 589 秒，最终 `页面采集完成，可用来源 1/2 条`。
  - `collect_sources_02_01`：`SUCCESS`，耗时约 594 秒，最终 `页面采集完成，可用来源 2/2 条`。
  - O1/O3 可观测字段已进入节点快照：
    - 抖音：field query `planned=53`、`executed=1`、`skipped=50`、`skipReasons={SKIPPED_OVER_BUDGET=50}`、field query 累计耗时约 `2077ms`。
    - B站：field query `planned=53`、`executed=2`、`skipped=50`、`skipReasons={SKIPPED_OVER_BUDGET=50}`、field query 累计耗时约 `5580ms`。
  - `BROWSER_SUPPLEMENT_SEARCH` step message 已展示计划/实际执行/跳过/累计耗时/跳过原因。
  - 09 遗留正样本已出现：最终证据中有 2 条 `selectionReason=Tavily prefetch 正文可用`。
- 新暴露问题：
  - 质量闭环未通过：`qualityScore=34`、`qualityPassed=false`、`citationEvidenceState=PARTIAL_SOURCE`、`citationRiskSeverity=ERROR`，报告被收口到人工介入。
  - 证据覆盖不足：最终报告证据只有 3 条，诊断中 `blockerCount=1`、`evidenceGapCount=2`，缺口集中在“定价策略”“短板与风险”等结构化字段。
  - 采集质量不足：抖音节点为 `PARTIAL_SUCCESS`，公开补采目标 `/about` 出现一次预取成功、一次正式采集失败；B站同一个 Tavily prefetch URL 被采集两次，存在重复证据。
  - 事件总线配置异常：RocketMQ 日志出现 `topic[task.collaboration] contains illegal characters` 36 次、`sendDefaultImpl call timeout` 3 次、DLQ 2 次；该问题未阻断 DAG 主链路，但会影响协作事件可靠投递。

## 风险与边界
- 端到端复跑已证明 field query 阶段不再吊死，但两个采集节点总耗时仍接近 10 分钟。field query 本身累计耗时只有秒级，剩余耗时主要来自公开补采、候选验证、页面抓取与外部站点超时，需要另行拆分优化。
- 当前不是“报告可交付完成”状态：任务 `79` 因质量评审未通过进入 `STOPPED`，需要补齐证据链或重跑采集链路后再进入自动修订。
- RocketMQ topic 配置存在独立异常，建议单独修复，避免协作事件进入 DLQ。
- `backend` 全量测试当前不是干净基线，存在仓库既有噪声与无关失败；后续需继续采用“定向回归 + 明确记录未处理基线问题”的策略。

## 下一步
- 优先修复新暴露问题：
  - 给采集目标做 URL 去重与复用策略修正，避免同一 Tavily prefetch URL 或 public recovery URL 被重复采集。
  - 针对“定价策略”“短板与风险”等缺证据字段补 query/补采策略，避免质量检查因结构化字段缺口直接阻断。
  - 修复 RocketMQ topic 命名配置，把 `task.collaboration` 改为 RocketMQ 合法 topic 名，保留 tag 分隔语义。
  - 拆分采集节点 10 分钟耗时，单独统计公开补采、候选验证、页面抓取的耗时分布，再决定是否推进档 C（受限并行）。
