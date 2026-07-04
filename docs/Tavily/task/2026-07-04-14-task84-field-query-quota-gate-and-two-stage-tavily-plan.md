# Task84 Field Query Quota Gate and Two-Stage Tavily Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 task 84 暴露的 field evidence query 放大器，让 9a search-first 采集在可控 Tavily 调用量内产出可落库、可追溯的 `sourceUrls` 证据。

**Architecture:** 在 coordinator 前置一个按字段和来源类型分配的执行闸门，避免全量 planned query 串行烧 Tavily；把 field query 改成 basic、无 raw 的候选发现阶段，再只对入选赢家补拉正文；同时补齐覆盖达标即停、stop 后防迟写、质量门和 selected target 数量校准。全局上限只作为 fail-safe，不能替代字段和第三方来源配额。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven, PostgreSQL, Tavily API, RocketMQ runtime event/audit.

---

当前阶段：[task 84 问题已复盘，修复计划已写入，等待代码实施]
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 计划撰写：已完成
- [ ] 代码实施：待执行
- [ ] 9a 复测：待执行

## 1. 执行计划与进度

```json
{
  "taskName": "14-task84-field-query-quota-gate-and-two-stage-tavily",
  "sourceEvidenceDir": "tmp/task12-9a-20260704-131735/",
  "sourceTaskId": 84,
  "updatedAt": "2026-07-04 14:00:00 +08:00",
  "progress": [
    {
      "id": "task84-evidence-review",
      "name": "复核 task 84 现场证据",
      "goal": "区分已修复的运行边界问题和仍存在的 field query 放大器问题",
      "eta": "20 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "quota-gate-design",
      "name": "设计字段和来源分层配额闸门",
      "goal": "把每 collector 71 条 planned query 收敛到每字段最多 3 条、每节点最多 24 条，并保留第三方证据入口",
      "eta": "45 分钟",
      "dependsOn": ["task84-evidence-review"],
      "status": "completed"
    },
    {
      "id": "two-stage-tavily-design",
      "name": "设计 Tavily basic 候选发现和 winner raw 补拉",
      "goal": "把 field query 单次成本从 advanced+raw 的十几秒级降到 basic 无 raw 的秒级，再对入选候选补正文",
      "eta": "45 分钟",
      "dependsOn": ["quota-gate-design"],
      "status": "completed"
    },
    {
      "id": "runtime-quality-design",
      "name": "补齐 stop 防迟写、质量门和 target count 校准",
      "goal": "防止停止后继续落库，避免 AUTH_GATE 误杀大正文官方页，并避免 selectedTargets 被压成 1",
      "eta": "45 分钟",
      "dependsOn": ["two-stage-tavily-design"],
      "status": "completed"
    },
    {
      "id": "implementation",
      "name": "代码实施",
      "goal": "按本计划 Task 1 到 Task 9 完成实现和单元测试",
      "eta": "5-8 小时",
      "dependsOn": ["runtime-quality-design"],
      "status": "pending"
    },
    {
      "id": "verification",
      "name": "9a 回归复测",
      "goal": "确认 task 不再因 71 条串行 Tavily query 跑不完，调用量、证据落库和 report 生成均达标",
      "eta": "1-2 小时",
      "dependsOn": ["implementation"],
      "status": "pending"
    }
  ]
}
```

## 2. Task 84 现场结论

### 2.1 已确认好转

| 维度 | task 84 证据 | 结论 |
| --- | --- | --- |
| RocketMQ topic | `dead_letter_rows=0` | 7.3 暴露的 `task.collaboration` 非法 topic 问题未复现。 |
| Tavily HTTP 边界 | 线程栈进入 `TavilySearchClient.java:125` 的 `sendAsync + timedGet` | 旧的 `HttpClient.send()` 无外层硬超时问题已被替换。 |
| stop 回收 | task 最终 `STOPPED`，collector 不再永久 `RUNNING` | task 83 的永久卡死风险已有改善。 |

### 2.2 仍然失败的主因

| 现象 | task 84 证据 | 判断 |
| --- | --- | --- |
| 每 collector 仍执行全量 field query | `agent-729` 和 `agent-730` 均为 `fieldEvidenceQueryCount=71`，`PlannedCount/ExecutedCount=71/71` | 上游没有数量闸门，planned query 原样透传到 provider。 |
| 71 条不是 5 scope 直接笛卡尔乘积 | field audit 为每 collector 71 条 `queriesSent` | `DEFAULT_SCOPES` 循环增加复杂度和审计风险，但 task 84 的直接 API 调用口径是每 collector 71 条，不应误写成 `71 x 5 scope`。 |
| 失败 audit 应按结构化字段读取 | `fieldEvidenceQueryExecutions` 中 71 条均为 `FAILED`，`failureReason=tavily interrupted` | 全 JSON 字符串里的 `SUCCESS=18/20`、`FAILED=286/284` 只是原始文本计数，不能当 field query audit 统计。 |
| 手动 stop 后出现迟写 | task `13:23:39` STOPPED，但 `m.bilibili-ad.com/about` recovery evidence 在 `13:25:58` 落库 | 运行边界还缺少 stop 后写库护栏。不能简单说最终 evidence 永远为 0，准确口径是 stop 时主链路 0，停止后又迟写 1 条低质量 recovery 证据。 |
| Tavily 候选有可用内容但被质量门挡住 | 抖音/B 站 Tavily raw 大正文页 `persisted=false`，issueFlags 包含 `AUTH_GATE_DETECTED`、`FORMAL_EVIDENCE_DEGRADED` | 质量门对大正文官方页的 auth gate 信号过强，存在误杀风险。 |
| selected target 被压缩 | 两个 collector 均 `selectedTargets=1` | search-first 需要多个高价值候选进入采集，不能被用户显式 URL 数量或旧 target count 压成 1。 |

### 2.3 结构化审计口径硬约束

task 84 的 field query 统计只能读取结构化字段，不能再用全文 grep 的 `SUCCESS` / `FAILED` 字符串计数。

必须使用：

- `searchAudit.tavilyFastLaneAudit.fieldEvidenceQueryExecutions[*].status`
- `searchAudit.tavilyFastLaneAudit.fieldEvidenceQueryExecutions[*].failureReason`
- `searchAudit.tavilyFastLaneAudit.queriesSent`
- `fieldEvidenceQueryPlannedCount`
- `fieldEvidenceQueryExecutedCount`
- `fieldEvidenceQuerySkippedCount`

禁止使用：

- 对整个 collector JSON 做 `Select-String SUCCESS`、`rg "SUCCESS"`、`grep SUCCESS` 后把命中次数当成 field query 成功数。
- 对整个 collector JSON 做 `Select-String FAILED`、`rg "FAILED"`、`grep FAILED` 后把命中次数当成 field query 失败数。

task 84 的准确口径是：每 collector 的 `fieldEvidenceQueryExecutions` 为 71 条，结构化状态均为 `FAILED`，`failureReason=tavily interrupted`。之前提到的 `SUCCESS=18/20`、`FAILED=286/284` 只是全 JSON 原始文本计数，不能进入实施验收、复测报告或问题归因。

建议用 PowerShell 读取结构化字段：

```powershell
$json = Get-Content -Encoding UTF8 -Raw "tmp/task12-9a-20260704-131735/agent-729-output.json" | ConvertFrom-Json
$executions = $json.searchAudit.tavilyFastLaneAudit.fieldEvidenceQueryExecutions
$executions | Group-Object status | Select-Object Name,Count
$executions | Group-Object failureReason | Select-Object Name,Count
```

一句话根因：task 84 不是新的卡死 bug，而是 field query 数量配额被拆掉后，在新运行边界代码上原样复现的上游放大器。每 collector 71 条 Tavily field query 串行跑，用户 5.5 分钟 stop 后大多数请求被 interrupt，主链路没有形成稳定、及时、可落库的证据。

## 3. 设计约束

- 禁止使用一刀切全局 top-N 作为主闸门。当前 query 已按 priority 排序，官方路径 priority=0 通常排在最前，全局前 20 会饿死 `REVIEW/NEWS/OPEN_WEB`，违反“官方是权重，不是门槛”的证据准入原则。
- 禁止只提高 timeout 或 retry 次数。71 条 advanced+raw 串行的数学成本本身不成立，提高 timeout 只会扩大消耗。
- 禁止为了过 9a 做竞品或域名特判。修复必须作用于通用 field evidence supplement。
- 禁止把 Tavily 改成唯一来源。direct discovery、Fast Lane、field query、winner raw、browser/http collection 要保持职责拆分。
- 所有新增或修改的采集结果、审计 DTO、Agent 输出 schema 必须保留 `sourceUrls`，保证无幻觉和可追溯。
- 所有外部 Tavily/raw/browser 调用仍必须保留 try-catch、硬超时、重试上限和取消检查。
- Agent prompt 层输出仍要遵守统一进度格式，运行时进度必须可持久化。

## 4. 目标架构

```text
FieldEvidenceQueryPlanner
  生成完整 planned queries
        |
        v
FieldEvidenceQueryExecutionGate
  按字段配额：每字段最多 3 条
  来源保护：有 REVIEW/NEWS/OPEN_WEB 时至少保留 1 条
  全局兜底：每 collector 最多 24 条
  输出 executable + skipped + skipReasons
        |
        v
Tavily field query discovery
  search_depth=basic
  include_raw_content=false
  只拿 title/url/snippet/score 形成候选
        |
        v
Candidate fusion and target selection
  不把 search-first selectedTargets 压成 1
  选择少量 winner candidates
        |
        v
Winner raw fetch / browser collection
  只对入选赢家补正文
  stop/cancel 检查贯穿写库前
        |
        v
EvidenceQualityGate
  大正文官方页不被低置信 auth gate 信号直接误杀
  输出 persisted evidence + sourceUrls + structured audit
```

### 4.1 第一层：按字段和来源类型配额

主闸门规则：

- 每个 `fieldName` 最多 3 条 executable query。
- 如果该字段 planned query 中存在第三方 query，3 个名额里至少保留 1 个给第三方。
- 第三方 sourceType 定义为 `REVIEW`、`NEWS`、`OPEN_WEB`。
- `OFFICIAL`、`DOCS`、`PRICING`、`TERMS` 仍按 priority 排序优先，但不能独占字段全部名额。
- 每 collector 全局 fail-safe 为 24 条，超过时按字段轮转裁剪，不能再回到全局 priority top-N。

预期效果：task 84 的 71 条 planned query 收敛为约 21 条 executable query，两个 collector 总 Tavily field query 从约 142 条降到约 42 条，并保留第三方覆盖。

### 4.2 第二层：覆盖达标即停

分两个阶段判断，避免 basic 阶段没有 raw 时误判：

- 候选发现阶段：某字段已拿到至少 2 个高置信候选，且包含至少 1 个官方或文档候选、至少 1 个第三方候选时，跳过该字段剩余 query，skip reason 记为 `SKIPPED_FIELD_CANDIDATE_COVERAGE_MET`。
- 正文采集阶段：某字段已持久化至少 2 条可用正文证据，正文长度 `>= min-raw-content-chars`，优先满足 `>= 2000` 字符时，跳过该字段后续 repair/supplement，skip reason 记为 `SKIPPED_FIELD_EVIDENCE_COVERAGE_MET`。

### 4.3 第三层：降低单次 Tavily 调用成本

field query 阶段使用轻量 profile：

- `search_depth=basic`
- `include_raw_content=false`
- `max_results` 可沿用现有配置，后续通过候选融合和 winner 限制控制 raw 成本。

raw content 只对 selected winner 补拉：

- winner raw fetch 可以复用 Tavily profile，但必须单独标记为 `FIELD_EVIDENCE_WINNER_RAW_FETCH`。
- 无 raw 的 field query 候选不能被标记为 `fastLaneUsable=true`，只能标记为 `candidateDiscoveryUsable=true`。
- raw fetch 成功后再更新 `prefetchedRawContentLength`、`sourceUrls`、quality signals 和 evidence fragments。

### 4.4 第四层：全局兜底和预算数学

- 预算基于 executable query 数，不再基于 planned query 数。
- basic field query 预算公式改为 `15000ms + executableQueryCount * 3000ms`。
- winner raw fetch 单独计预算，建议 `10000ms + rawWinnerCount * 15000ms`，且 rawWinnerCount 由 selected target 和字段覆盖控制。
- provider 的 per-query deadline 仍保留，但只是下游熔断，不再承担上游数量治理。

### 4.5 stop 和迟写护栏

- stop 后任何 collector、raw fetch、browser/http collection 在写 `evidence_source`、node output、event outbox 前必须重新读取 task/node 权威状态。
- 如果 task 已是 `STOPPED` 或 node 已被标记 `SKIPPED/STOPPED`，丢弃迟到结果，记录 `DISCARDED_AFTER_STOP` audit，不再落库 evidence。
- `InterruptedException` 必须恢复线程中断标记，并向上返回可识别的 cancellation result，不能继续走成功保存分支。

### 4.6 质量门校准

- `AUTH_GATE_DETECTED` 不能仅凭少量关键词直接把大正文官方页压到不可落库。
- 对正文长度较长、URL 来源可信、title/snippet 与竞品或开放平台相关的 Tavily raw 页，auth gate 信号应降为 `AUTH_GATE_WEAK_SIGNAL` 或与内容可用分分离。
- `FORMAL_EVIDENCE_DEGRADED` 应保留为审计信号，但不能覆盖“正文长度足够、sourceUrls 可追溯、字段可引用”的基础可用性。

### 4.7 selected target 数量校准

- search-first 场景不能因为 `competitorUrls` 只有 1 个就把 `selectedTargets` 压成 1。
- 9a 类官方开放平台/商业生态采集至少允许 2 到 3 个高价值 target：官方主页/协议页、开发者文档页、第三方新闻或 review 页。
- selected target summary 必须输出 `effectiveTargetCount`、`requestedTargetCount`、`searchFirstMinimumTargetCount`、被保留和被丢弃的原因。

## 5. File Map

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java`
  - 负责 planned query 到 executable query 的字段配额、来源保护、全局 fail-safe 和 skip reason。
- `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionPlan.java`
  - 不可变 DTO，包含 `planned`、`executable`、`skipped`、`skipReasons`、`fieldDistribution`、`sourceTypeDistribution`。
- `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`
  - 锁定“不用全局 top-N、第三方不饿死、每字段最多 3 条、全局最多 24 条”的核心行为。
- `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFieldEvidenceProfileResolverTest.java`
  - 锁定 field query 使用 basic 且不 include raw，winner raw fetch 使用独立 profile。

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
  - 使用 `FieldEvidenceQueryExecutionGate` 替换当前 planned 原样透传。
  - trace 输出 planned/executable/skipped 分布。
  - 写库、写 node output、写 event 前补 stop/cancel guard。
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
  - 恢复配额语义，但以字段和来源保护形式实现。
  - 预算公式改为按 executable query 和 Tavily mode 计算。
- `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
  - field query 阶段支持 candidate coverage stop。
  - audit 增加 profile mode、raw flag、skip reason 和 winner raw 统计。
- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
  - `resolveFieldEvidence()` 固定 basic/no raw。
  - 新增 winner raw fetch profile 解析方法。
- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`
  - 增加 `profileStage` 或等价字段，用于区分 `FIELD_EVIDENCE_DISCOVERY` 与 `FIELD_EVIDENCE_WINNER_RAW_FETCH`。
- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
  - 增加 mode/raw/winnerRawFetchCount/fieldDistribution/sourceTypeDistribution。
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
  - 区分 `candidateDiscoveryUsable` 和 `fastLaneUsable`，避免无 raw 候选被误当成可直接落库证据。
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
  - 采集前后检查取消状态，quality gate 后保留 `sourceUrls` 和结构化 issue flags。
- `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGate.java`
  - 校准大正文 Tavily 官方页的 auth gate 判定。
- `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
  - search-first 场景保留多个高价值候选，不被显式 URL 数量压成 1。
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
  - 对停止后迟到结果增加统一丢弃路径和事件。
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
  - 改为验证 executable query 预算，不再接受 planned query 膨胀预算。
- `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
  - 验证 coverage stop、audit 和 interrupted 统计。
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentEvidenceQualityGateTest.java`
  - 验证大正文官方页不被弱 auth gate 信号误杀。
- `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
  - 验证 search-first 至少保留多个高价值 target。

## 6. Implementation Tasks

### 6.0 实施优先级

首轮复测优先打通“能不能跑完并落库”的三件套：

| 优先级 | 任务 | 对应设计 | 验证目标 |
| --- | --- | --- | --- |
| P0 | Task 1、Task 2、Task 3 | 4.1 字段和来源配额闸门 | 每 collector executable query 从 71 收敛到约 21，最多不超过 24。 |
| P0 | Task 4 | 4.3 二段式 Tavily | field query 使用 `basic/no raw`，不再让 71 条 query 全部 advanced+raw。 |
| P0 | Task 8 | 4.6 质量门校准 | Tavily 大正文官方页不被弱 auth gate 信号误杀，能形成可落库 evidence。 |
| P1 | Task 5、Task 6 | 4.2 覆盖即停和 discovery/fast-lane 语义 | 采够字段候选后继续降成本，无 raw 候选不会被误当成可直接落库证据。 |
| P1 | Task 7 | 4.5 stop 和迟写护栏 | 停止后不再落库迟到 evidence。 |
| P1 | Task 9 | 4.7 selected target 数量校准 | search-first 不再把高价值候选压成 1。 |
| P2 | Task 10 | 集成回归和 9a 复测 | 完成全链路验收。 |

第一次 9a 复测可以在 P0 三件套完成后先跑，用于验证 query 数量、collector 自主跑完和 evidence 落库是否已恢复。P1 项仍需要合入，但不应阻塞对“放大器是否被关住”的第一次验证。

### 6.1 分批验证节奏

本计划虽然覆盖多个层面，但实施验证必须分批推进，避免一次合入 9 个 Task 后复测失败、无法定位因果。

| 批次 | 包含改动 | 因果目标 | 复测观察点 | 失败时优先回看 |
| --- | --- | --- | --- | --- |
| 第一批：主链路跑通 | 4.1 配额闸门、4.3 二段式 Tavily、4.6 质量门校准 | `71 planned -> 约 21 executable -> collector 自己跑完 -> 大正文不过度误杀 -> evidence 落库` | 每节点 executable 约 21 且不超过 24；collector 不手动 stop 也能结束；`evidence_rows > 0`；至少 1 条正文 `>= 2000`；第三方来源可见。 | `FieldEvidenceQueryExecutionGate`、`TavilySearchProfileResolver.resolveFieldEvidence()`、`EvidenceQualityGate`。 |
| 第二批：跑通后干净 | 4.2 覆盖即停、4.5 stop 迟写、4.7 target 校准 | 已能跑通的基础上继续降低浪费、清理边界语义、提升候选丰富度 | 覆盖达标后 skip reason 正确；stop 后无迟写 evidence；search-first selectedTargets 不再被压成 1；report 和审计仍稳定。 | `TavilyFastLaneProvider` coverage stop、`DagExecutor/CollectorAgent` stop guard、`CollectionTargetSelector`。 |

执行规则：

- 第一批没有通过前，不叠加第二批行为优化；否则复测失败时无法区分是主链路仍未跑通，还是覆盖即停、stop guard、target count 新逻辑引入了回归。
- 第一批复测报告只回答四个问题：query 是否从 71 降到约 21、collector 是否自己跑完、evidence 是否大于 0、第三方来源是否没有被饿死。
- 第二批复测必须复用第一批的四个核心指标，确保边界优化没有把已跑通的主链路打断。

### Task 1: 写字段和来源配额闸门的失败测试

**Files:**
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`

- [ ] **Step 1: 新增“不饿死第三方”的测试**

```java
@Test
void shouldReserveThirdPartyQueryWhenOfficialPriorityComesFirst() {
    FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
    List<FieldEvidenceQuery> planned = List.of(
            query("pricing", "OFFICIAL", 0, "official-1"),
            query("pricing", "DOCS", 1, "docs-1"),
            query("pricing", "PRICING", 2, "pricing-1"),
            query("pricing", "REVIEW", 9, "review-1")
    );

    FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 3, 1, 24);

    assertThat(plan.executable()).hasSize(3);
    assertThat(plan.executable()).extracting(FieldEvidenceQuery::getSourceType)
            .contains("REVIEW");
    assertThat(plan.skipped()).hasSize(1);
    assertThat(plan.skipReasons()).containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 1);
}
```

- [ ] **Step 2: 新增“每字段最多 3 条、全局最多 24 条”的测试**

```java
@Test
void shouldApplyPerFieldQuotaBeforeGlobalFailSafe() {
    FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
    List<FieldEvidenceQuery> planned = new ArrayList<>();
    for (int fieldIndex = 0; fieldIndex < 10; fieldIndex++) {
        String fieldName = "field-" + fieldIndex;
        planned.add(query(fieldName, "OFFICIAL", 0, fieldName + "-official"));
        planned.add(query(fieldName, "DOCS", 1, fieldName + "-docs"));
        planned.add(query(fieldName, "NEWS", 2, fieldName + "-news"));
        planned.add(query(fieldName, "REVIEW", 3, fieldName + "-review"));
    }

    FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 3, 1, 24);

    assertThat(plan.executable()).hasSizeLessThanOrEqualTo(24);
    Map<String, Long> byField = plan.executable().stream()
            .collect(Collectors.groupingBy(FieldEvidenceQuery::getFieldName, Collectors.counting()));
    assertThat(byField.values()).allMatch(count -> count <= 3L);
    assertThat(plan.skipReasons()).containsKey("SKIPPED_NODE_QUERY_CAP_EXHAUSTED");
}
```

- [ ] **Step 3: 运行失败测试**

Run:

```powershell
cd backend
mvn -Dtest=FieldEvidenceQueryExecutionGateTest test
```

Expected: 编译失败或测试失败，因为 `FieldEvidenceQueryExecutionGate` 与 `FieldEvidenceQueryExecutionPlan` 尚未创建。

### Task 2: 实现 FieldEvidenceQueryExecutionGate

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionPlan.java`

- [ ] **Step 1: 创建执行计划 DTO**

```java
public record FieldEvidenceQueryExecutionPlan(
        List<FieldEvidenceQuery> planned,
        List<FieldEvidenceQuery> executable,
        List<FieldEvidenceQuery> skipped,
        Map<String, Integer> skipReasons,
        Map<String, Integer> fieldDistribution,
        Map<String, Integer> sourceTypeDistribution
) {
    public FieldEvidenceQueryExecutionPlan {
        planned = planned == null ? List.of() : List.copyOf(planned);
        executable = executable == null ? List.of() : List.copyOf(executable);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        skipReasons = skipReasons == null ? Map.of() : Map.copyOf(skipReasons);
        fieldDistribution = fieldDistribution == null ? Map.of() : Map.copyOf(fieldDistribution);
        sourceTypeDistribution = sourceTypeDistribution == null ? Map.of() : Map.copyOf(sourceTypeDistribution);
    }
}
```

- [ ] **Step 2: 实现字段配额和第三方保留**

```java
public FieldEvidenceQueryExecutionPlan resolve(List<FieldEvidenceQuery> planned,
                                               int maxPerField,
                                               int minThirdPartyPerField,
                                               int maxPerNode) {
    List<FieldEvidenceQuery> ordered = sortQueries(planned);
    Map<String, List<FieldEvidenceQuery>> byField = ordered.stream()
            .collect(Collectors.groupingBy(
                    query -> defaultText(query.getFieldName(), "unknown"),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<FieldEvidenceQuery> executable = new ArrayList<>();
    List<FieldEvidenceQuery> skipped = new ArrayList<>();
    Map<String, Integer> skipReasons = new LinkedHashMap<>();

    for (List<FieldEvidenceQuery> fieldQueries : byField.values()) {
        FieldSlice slice = selectFieldQueries(fieldQueries, maxPerField, minThirdPartyPerField);
        executable.addAll(slice.executable());
        skipped.addAll(slice.skipped());
        increment(skipReasons, "SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", slice.skipped().size());
    }

    if (executable.size() > maxPerNode) {
        List<FieldEvidenceQuery> retained = retainByFieldRoundRobin(executable, maxPerNode);
        Set<String> retainedFingerprints = retained.stream()
                .map(FieldEvidenceQuery::getQueryFingerprint)
                .collect(Collectors.toSet());
        List<FieldEvidenceQuery> overflow = executable.stream()
                .filter(query -> !retainedFingerprints.contains(query.getQueryFingerprint()))
                .toList();
        executable = retained;
        skipped.addAll(overflow);
        increment(skipReasons, "SKIPPED_NODE_QUERY_CAP_EXHAUSTED", overflow.size());
    }

    return new FieldEvidenceQueryExecutionPlan(
            ordered,
            executable,
            skipped,
            skipReasons,
            countBy(executable, FieldEvidenceQuery::getFieldName),
            countBy(executable, FieldEvidenceQuery::getSourceType));
}
```

- [ ] **Step 3: 运行闸门测试**

Run:

```powershell
cd backend
mvn -Dtest=FieldEvidenceQueryExecutionGateTest test
```

Expected: PASS。

### Task 3: coordinator 接入闸门并改预算口径

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`

- [ ] **Step 1: 修改预算测试**

新增断言：

```java
assertThat(trace.getFieldEvidenceQueryPlannedCount()).isEqualTo(71);
assertThat(trace.getFieldEvidenceQueryExecutedCount()).isLessThanOrEqualTo(24);
assertThat(trace.getFieldEvidenceQuerySkippedCount()).isGreaterThanOrEqualTo(47);
assertThat(trace.getFieldEvidenceQuerySkipReasons())
        .containsKeys("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED");
```

- [ ] **Step 2: 用闸门替换 planned 原样透传**

`SearchExecutionCoordinator.resolveExecutableFieldEvidenceQueries(...)` 的行为改为：

```java
List<FieldEvidenceQuery> planned = resolveFieldEvidenceQueries(config).stream()
        .filter(Objects::nonNull)
        .sorted(FIELD_EVIDENCE_QUERY_COMPARATOR)
        .toList();
FieldEvidenceQueryExecutionPlan plan = fieldEvidenceQueryExecutionGate.resolve(
        planned,
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMinThirdPartyQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerNode());
return ResolvedFieldEvidenceQueryPlan.from(plan);
```

- [ ] **Step 3: 按 executable query 计算 budget**

`SearchPolicyResolver.ensureMinimumTimeoutForFieldEvidenceQueries(...)` 改为接收 executable count 或 executable query list：

```java
public long ensureMinimumTimeoutForExecutableFieldEvidenceQueries(long baseTimeoutMillis,
                                                                  List<FieldEvidenceQuery> executableQueries) {
    int executableCount = executableQueries == null ? 0 : executableQueries.size();
    if (executableCount <= 0) {
        return baseTimeoutMillis;
    }
    long minimumTimeoutMillis = 15_000L + executableCount * 3_000L;
    return Math.max(baseTimeoutMillis, minimumTimeoutMillis);
}
```

- [ ] **Step 4: 运行预算回归**

Run:

```powershell
cd backend
mvn -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest test
```

Expected: PASS，且测试中不再接受 planned query 直接膨胀到 438 秒预算。

### Task 4: Tavily field query 改为 basic/no raw，并增加 winner raw fetch profile

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`
- Create or Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFieldEvidenceProfileResolverTest.java`

- [ ] **Step 1: 写 profile 测试**

```java
@Test
void shouldUseBasicWithoutRawForFieldEvidenceDiscovery() {
    TavilySearchProfile profile = resolver.resolveFieldEvidence(query("summary", "OFFICIAL"));

    assertThat(profile.getSearchDepth()).isEqualTo("basic");
    assertThat(profile.isIncludeRawContent()).isFalse();
    assertThat(profile.getProfileStage()).isEqualTo("FIELD_EVIDENCE_DISCOVERY");
}

@Test
void shouldUseRawForWinnerFetchOnly() {
    TavilySearchProfile profile = resolver.resolveFieldEvidenceWinnerRawFetch(query("summary", "OFFICIAL"), "https://open.example.com/docs");

    assertThat(profile.getSearchDepth()).isEqualTo("advanced");
    assertThat(profile.isIncludeRawContent()).isTrue();
    assertThat(profile.getProfileStage()).isEqualTo("FIELD_EVIDENCE_WINNER_RAW_FETCH");
}
```

- [ ] **Step 2: 实现 discovery profile**

```java
return TavilySearchProfile.builder()
        .family(normalizeFamily(query.getSourceType()))
        .queryMode(queryMode)
        .query(query.getQuery())
        .includeDomains(resolveFieldEvidenceIncludeDomains(query, queryMode))
        .officialDomains(resolveFieldEvidenceOfficialDomains(query, queryMode))
        .searchDepth("basic")
        .includeRawContent(false)
        .maxResults(properties.getMaxResults())
        .profileStage("FIELD_EVIDENCE_DISCOVERY")
        .fieldName(query.getFieldName())
        .evidencePathKey(query.getEvidencePathKey())
        .queryIntent(query.getQueryIntent())
        .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
        .fieldEvidenceQueryReason(query.getReason())
        .build();
```

- [ ] **Step 3: 实现 winner raw fetch profile**

winner raw fetch 必须显式写入 URL 或 domain hint，不能重新全网散搜：

```java
public TavilySearchProfile resolveFieldEvidenceWinnerRawFetch(FieldEvidenceQuery query, String winnerUrl) {
    return TavilySearchProfile.builder()
            .family(normalizeFamily(query.getSourceType()))
            .queryMode(TavilyQueryMode.TRUSTED_WEB_EXPANSION)
            .query("site:" + URI.create(winnerUrl).getHost() + " " + query.getQuery())
            .includeDomains(List.of(URI.create(winnerUrl).getHost()))
            .officialDomains(resolveFieldEvidenceOfficialDomains(query, TavilyQueryMode.TRUSTED_WEB_EXPANSION))
            .searchDepth("advanced")
            .includeRawContent(true)
            .maxResults(1)
            .profileStage("FIELD_EVIDENCE_WINNER_RAW_FETCH")
            .fieldName(query.getFieldName())
            .evidencePathKey(query.getEvidencePathKey())
            .queryIntent(query.getQueryIntent())
            .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
            .fieldEvidenceQueryReason(query.getReason())
            .build();
}
```

- [ ] **Step 4: 运行 profile 测试**

Run:

```powershell
cd backend
mvn -Dtest=TavilyFieldEvidenceProfileResolverTest test
```

Expected: PASS。

### Task 5: provider 增加候选覆盖即停和审计字段

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/FieldEvidenceQueryExecutionAudit.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`

- [ ] **Step 1: 写 coverage stop 测试**

```java
@Test
void shouldSkipRemainingQueriesAfterFieldCandidateCoverageMet() {
    SearchSourceRequest request = requestWithFieldQueries(
            query("summary", "OFFICIAL", 0, "q1"),
            query("summary", "REVIEW", 1, "q2"),
            query("summary", "NEWS", 2, "q3"));
    stubTavilySuccess("q1", candidate("https://open.example.com/about", "OFFICIAL"));
    stubTavilySuccess("q2", candidate("https://news.example.com/open-platform", "REVIEW"));

    SearchSourceResult result = provider.search(request);

    assertThat(result.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
            .extracting(FieldEvidenceQueryExecutionAudit::getSkipReason)
            .contains("SKIPPED_FIELD_CANDIDATE_COVERAGE_MET");
}
```

- [ ] **Step 2: 增加结构化 audit 字段**

每条 query audit 至少输出：

```json
{
  "queryFingerprint": "q-summary-official",
  "fieldName": "summary",
  "sourceType": "OFFICIAL",
  "profileStage": "FIELD_EVIDENCE_DISCOVERY",
  "searchDepth": "basic",
  "includeRawContent": false,
  "status": "SUCCESS",
  "resultCount": 2,
  "skipReason": null,
  "failureReason": null
}
```

- [ ] **Step 3: 运行 provider 测试**

Run:

```powershell
cd backend
mvn -Dtest=TavilyFastLaneProviderTest test
```

Expected: PASS。

### Task 6: 区分 discovery candidate 和 fast lane usable evidence

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [ ] **Step 1: 增加测试，basic 无 raw 候选不能直接当可落库证据**

```java
@Test
void shouldNotTreatBasicDiscoveryCandidateAsFastLaneEvidence() {
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://open.example.com/docs")
            .sourceType("DOCS")
            .candidateDiscoveryUsable(true)
            .fastLaneUsable(false)
            .prefetchedRawContentLength(0)
            .build();

    CollectionTargetSelection selection = selector.select(List.of(candidate), searchFirstPolicy(3));

    assertThat(selection.getSelectedTargets()).hasSize(1);
    assertThat(selection.getSelectedTargets().get(0).isRequiresRawFetch()).isTrue();
}
```

- [ ] **Step 2: 修改 selector 语义**

selector 排序可以继续提升 discovery candidate，但只有满足正文条件时才能标记 `fastLaneUsable`：

```java
boolean canSkipNetworkCollection = candidate.isFastLaneUsable()
        && candidate.getPrefetchedRawContentLength() >= minRawContentChars
        && candidate.getSourceUrls() != null
        && !candidate.getSourceUrls().isEmpty();
```

- [ ] **Step 3: 运行 selector 测试**

Run:

```powershell
cd backend
mvn -Dtest=CollectionTargetSelectorTest test
```

Expected: PASS。

### Task 7: stop 后防迟写和 interrupt 语义收口

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

- [ ] **Step 1: 写迟到结果被丢弃测试**

```java
@Test
void shouldDiscardEvidenceWriteWhenTaskStoppedBeforePersist() {
    markTaskStopped(taskId);
    CollectionExecutionResult result = successfulResultWithSourceUrls("https://open.example.com/about");

    CollectorOutput output = collectorAgent.persistIfRuntimeActive(taskId, nodeId, result);

    assertThat(output.getResults()).isEmpty();
    assertThat(output.getDiscardReason()).isEqualTo("DISCARDED_AFTER_STOP");
    verify(evidenceSourceRepository, never()).save(any());
}
```

- [ ] **Step 2: 在所有写库前加权威状态检查**

统一使用类似方法：

```java
private boolean canPersistRuntimeResult(Long taskId, Long nodeId) {
    if (Thread.currentThread().isInterrupted()) {
        return false;
    }
    AnalysisTask task = taskRepository.findById(taskId).orElse(null);
    if (task == null || task.getStatus() == AnalysisTaskStatus.STOPPED) {
        return false;
    }
    TaskNode node = nodeRepository.findById(nodeId).orElse(null);
    return node != null
            && node.getStatus() != TaskNodeStatus.SKIPPED
            && node.getStatus() != TaskNodeStatus.STOPPED;
}
```

- [ ] **Step 3: 运行 runtime 测试**

Run:

```powershell
cd backend
mvn -Dtest=DagExecutorTest,CollectorAgentTest test
```

Expected: PASS，停止后不再出现 evidence 晚于 task stoppedAt 落库。

### Task 8: 质量门校准 Tavily 大正文官方页

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateProperties.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentEvidenceQualityGateTest.java`

- [ ] **Step 1: 写大正文官方页不被弱 auth gate 误杀的测试**

```java
@Test
void shouldKeepLongOfficialTavilyContentWhenAuthGateSignalIsWeak() {
    EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());
    CollectionExecutionResult result = tavilyResult(
            "https://open.example.com/protocol",
            repeat("开放平台 服务协议 API 接入 商家 权益 ", 400));

    CollectionExecutionResult gated = CollectorAgent.applyEvidenceQualityGateForTest(gate, config, candidate, result);

    assertThat(gated.isPersisted()).isTrue();
    assertThat(gated.getQualitySignals()).contains("AUTH_GATE_WEAK_SIGNAL");
    assertThat(gated.getQualitySignals()).doesNotContain("EVIDENCE_REPAIR_REQUIRED");
    assertThat(gated.getSourceUrls()).contains("https://open.example.com/protocol");
}
```

- [ ] **Step 2: auth gate 从硬拦截改为置信度判断**

规则：

```java
boolean longUsefulContent = safeContent.length() >= properties.getLongOfficialContentChars()
        && context.isOfficialOrDocsSource()
        && hasTopicSignals(safeContent, context);
if (isAuthGateContent(safeContent) && !longUsefulContent) {
    issues.add(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
    signals.add("AUTH_GATE_DETECTED");
    signals.add("EVIDENCE_REPAIR_REQUIRED");
    contentScore = Math.min(contentScore, properties.getAuthGateScoreCap());
} else if (isAuthGateContent(safeContent)) {
    signals.add("AUTH_GATE_WEAK_SIGNAL");
}
```

- [ ] **Step 3: 运行质量门测试**

Run:

```powershell
cd backend
mvn -Dtest=CollectorAgentEvidenceQualityGateTest test
```

Expected: PASS。

### Task 9: search-first selected target count 不再压成 1

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [ ] **Step 1: 写 search-first 至少保留多个目标的测试**

```java
@Test
void shouldKeepMultipleHighValueTargetsForSearchFirstFamilyEvenWhenInputUrlCountIsOne() {
    SearchRuntimePolicy policy = searchFirstPolicyWithMinimumTargets(3);
    List<SourceCandidate> candidates = List.of(
            candidate("https://open.example.com/protocol", "OFFICIAL", 9000),
            candidate("https://developer.example.com/docs", "DOCS", 12000),
            candidate("https://news.example.com/platform-review", "NEWS", 2000));

    CollectionTargetSelection selection = selector.select(candidates, policy);

    assertThat(selection.getSelectedTargets()).hasSize(3);
    assertThat(selection.getAudit().getEffectiveTargetCount()).isEqualTo(3);
}
```

- [ ] **Step 2: trace 输出 target count 决策**

trace 至少包含：

```json
{
  "requestedTargetCount": 1,
  "effectiveTargetCount": 3,
  "searchFirstMinimumTargetCount": 3,
  "targetCountReason": "search-first minimum targets keep official/docs/third-party evidence diversity"
}
```

- [ ] **Step 3: 运行 target selection 测试**

Run:

```powershell
cd backend
mvn -Dtest=CollectionTargetSelectorTest,SearchExecutionCoordinatorTest test
```

Expected: PASS。

### Task 10: 集成回归和 9a 复测

**Files:**
- Modify as needed: `docs/Tavily/progress/<new-progress-file>.md`
- Read only evidence output: `tmp/task12-9a-<timestamp>/`

- [ ] **Step 1: 运行定向测试集**

Run:

```powershell
cd backend
mvn -Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,SearchExecutionCoordinatorFieldEvidenceTest,TavilyFieldEvidenceProfileResolverTest,TavilyFastLaneProviderTest,CollectionTargetSelectorTest,CollectorAgentEvidenceQualityGateTest test
```

Expected: PASS。

- [ ] **Step 2: 重启 9093 并新建 9a 任务**

Run:

```powershell
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9093"
```

然后按 `docs/Tavily/plan/2026-07-02-12-search-first-routing-inversion-and-quota-calibration-plan.md` 的 9a 用例新建任务。

- [ ] **Step 3: 验收运行信息**

验收必须同时满足：

第一批主链路复测：

- 每 collector `fieldEvidenceQueryPlannedCount` 可继续为 71 左右，但 `fieldEvidenceQueryExecutedCount <= 24`。
- P0 首轮复测目标是每 collector executable query 约 `21`，即 `7 fields x 3 queries`；如果触发全局兜底，也不能超过 `24`。
- `fieldEvidenceQuerySkippedCount >= planned - executable`，skip reasons 包含字段配额或全局兜底原因。
- Tavily field query audit 中 `searchDepth=basic` 且 `includeRawContent=false`。
- field query audit 必须按 `fieldEvidenceQueryExecutions` 结构化字段读取；禁止用全文 `SUCCESS/FAILED` 字符串计数。
- collector 必须自己跑完，第一次复测不能依赖手动 stop 得到结果。
- `evidence_rows > 0`，且至少有 1 条正文长度 `>= 2000` 字符。
- 落库证据中必须能看到第三方 `sourceType` 或第三方 URL 贡献，证明 `REVIEW/NEWS/OPEN_WEB` 没有被官方 priority 饿死。

第二批边界和质量复测：

- winner raw fetch 数量受 selected target 控制，不能接近 planned query 数。
- 两个 collector 合计 Tavily API 调用量显著低于 task 84，目标不超过 60 次。
- stop 后不允许出现 `evidence_source.created_at > analysis_task.updated_at/stoppedAt` 的迟写证据。
- search-first selectedTargets 不再被用户显式 URL 数量压成 1，至少保留官方/文档/第三方中的多个高价值候选。
- report 不再返回 `50001 REPORT_NOT_FOUND`。
- collector 输出中的 `sourceUrls` 非空，且证据包含官方/文档和第三方来源的可追溯 URL。

## 7. 验收标准

| 类别 | 必须满足 |
| --- | --- |
| 配额 | 每字段 executable query `<= 3`；存在第三方 planned query 时每字段至少保留 1 条第三方；每 collector executable query `<= 24`。 |
| 成本 | field query 使用 `basic/no raw`；预算基于 executable query；task 84 类 71 planned 不再得到 438 秒级预算。 |
| 产出 | 9a collector 至少产出可引用 `sourceUrls`；证据正文长度满足 `min-raw-content-chars=500`，优先达到 2000 字符。 |
| 第三方覆盖 | 不因官方 priority 靠前而饿死 `REVIEW/NEWS/OPEN_WEB`。 |
| 停止语义 | 用户 stop 后不再落库迟到 evidence，不再把 interrupted result 当成功结果保存。 |
| 质量门 | 大正文 Tavily 官方页不被弱 auth gate 信号直接判为不可落库。 |
| 可观测性 | trace/audit 可看到 planned/executable/skipped、skip reasons、field/source 分布、profileStage、searchDepth、includeRawContent、winner raw fetch 数。 |

## 8. 风险和回滚

- 风险：配额过紧导致某些字段召回下降。缓解：第三方保留和 coverage stop 只跳过已满足字段；全局 cap 先设 24，可通过配置调整。
- 风险：basic/no raw 召回候选但缺正文。缓解：winner raw fetch 作为单独阶段，只对入选候选补 raw。
- 风险：质量门放松导致壳页误入库。缓解：只对长正文、官方/文档来源、主题信号充足的页面降低 auth gate 权重，短内容仍按原规则拦截。
- 风险：stop guard 过严导致正常完成结果被丢弃。缓解：只在写库前读取权威 task/node 状态，运行中状态仍允许保存。

回滚方式：

- 若配额闸门引入回归，可临时把 `maxPerField` 提高到 5、`maxPerNode` 提高到 35，但不能关闭第三方保留规则。
- 若 winner raw fetch 不稳定，可保留 basic/no raw field query，同时让 selected target 走现有 browser/http 正文采集，不回退到 71 条 advanced+raw。
