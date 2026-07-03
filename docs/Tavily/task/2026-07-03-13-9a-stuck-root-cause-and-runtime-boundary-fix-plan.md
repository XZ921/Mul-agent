# Task12 9a 运行边界与 Search-First 收口修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 9a 暴露的 RocketMQ topic 非法、Tavily 卡死、stop 无法回收 RUNNING 节点，以及 14/15 文档中补充的 search-first 高危回归风险，合并成一份唯一修复计划。

**Architecture:** 本方案把运行合同前移到唯一入口：事件 topic 在入库前校验，外部搜索调用在 client/provider/coordinator 三层形成硬超时与取消，stop 贯穿任务、节点、future 与迟到写回护栏，search-first 的候选融合、字段覆盖、scope 扩展和审计完整性一起收口。不要把根因能力继续后移到“下游失败后再补救”。

**Tech Stack:** Java 17、Spring Boot、JPA、RocketMQ outbox、JDK `HttpClient`、CompletableFuture/ExecutorService、JUnit/Maven。

---

> 整合说明：原 `2026-07-03-14-search-first-proactive-regression-risk-audit.md` 与 `2026-07-03-15-review-feedback-validation-and-priority.md` 的有效内容已并入本文。后续修复、评审和回归只以本文件为准。

当前阶段：[根因分析、前置风险与修改方案已整合，等待代码实施]
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 风险核验：已完成
- [x] 修改方案：已完成
- [ ] 代码实施：待执行
- [ ] 回归验证：待执行

## 1. 执行计划与进度

```json
{
  "taskName": "13-9a-runtime-boundary-and-search-first-consolidated-fix",
  "sourceProgress": "docs/Tavily/progress/2026-07-03-12-search-first-9a-retest-progress.md",
  "evidenceDir": "tmp/task12-9a-20260703-200027/",
  "mergedDocs": [
    "docs/Tavily/task/2026-07-03-14-search-first-proactive-regression-risk-audit.md",
    "docs/Tavily/task/2026-07-03-15-review-feedback-validation-and-priority.md"
  ],
  "updatedAt": "2026-07-03 22:45:00 +08:00",
  "steps": [
    {
      "id": "evidence-review",
      "name": "复核 9a 复测证据",
      "goal": "确认 RocketMQ、Tavily 阻塞、停止后节点残留三类现象的真实边界",
      "eta": "20 分钟",
      "dependsOn": [],
      "status": "completed"
    },
    {
      "id": "proactive-risk-review",
      "name": "整合 14/15 前置风险与反馈核验",
      "goal": "把尚未暴露但高危的问题纳入同一修复计划，避免修完主故障后继续踩坑",
      "eta": "30 分钟",
      "dependsOn": ["evidence-review"],
      "status": "completed"
    },
    {
      "id": "root-cause-design",
      "name": "形成统一根因级修改方案",
      "goal": "把 topic、外部调用、stop、search-first 选择与审计边界统一前移",
      "eta": "60 分钟",
      "dependsOn": ["proactive-risk-review"],
      "status": "completed"
    },
    {
      "id": "implementation",
      "name": "代码实施",
      "goal": "按 P0/P1/P2/P3 顺序完成修复，并在每组修复后运行定向测试",
      "eta": "5-8 小时",
      "dependsOn": ["root-cause-design"],
      "status": "pending"
    },
    {
      "id": "verification",
      "name": "回归与 9a 复跑",
      "goal": "证明任务不再卡 RUNNING，事件不再 DLQ，search-first 能产出可审计采集结果",
      "eta": "1-2 小时",
      "dependsOn": ["implementation"],
      "status": "pending"
    }
  ]
}
```

## 2. 已暴露现场结论

| 问题 | 证据 | 结论 |
| --- | --- | --- |
| RocketMQ topic 非法 | `error-log-slice.txt`、`db-event-status.txt` | `COLLABORATION_PLAN_RECORDED` 与 `COLLABORATION_CHECKPOINT_UPDATED` 使用 `task.collaboration`，`.` 违反 RocketMQ topic 规则，连续 6 次重试后进入 `DEAD_LETTER`。 |
| 9a 主链路卡住 | `thread-dump-after-stuck.txt`、`db-evidence-count.txt` | 两个 collector 节点都阻塞在 `TavilySearchClient.search()` 的 `HttpClient.send()`，`evidence_source=0`，阻塞时间已超过 `tavily-search.timeout-seconds=45`。 |
| stop 未回收运行节点 | `db-task-status.txt`、`db-node-status.txt` | `analysis_task.status=STOPPED`，但两个 collector 节点仍是 `RUNNING`，说明 stop 只改任务和未运行节点，没有中断运行中的外部同步调用。 |

这三类不是彼此独立的小 bug，而是同一个架构问题在不同层面的暴露：关键运行合同被放在过晚的边界才校验或执行。topic 合法性到 RocketMQ producer 才失败；搜索预算到 provider 循环下一条 query 才检查；停止语义到节点执行结束后才生效。

## 3. 14/15 合并后的补充风险核验

| 序号 | 判断 | 风险 | 代码证据 | 纳入任务 |
| --- | --- | --- | --- | --- |
| 1 | 属实 | `markStoppedNodes` 不处理 RUNNING，stop 后节点状态不一致 | `TaskRecoveryService.java:125-139`、`TaskRuntimeCommandAppService.java:311-324` | Task 4 |
| 2 | 属实但表述需修正 | `HttpClient.send()` 缺外层 `get(timeout)` 防御，依赖 `HttpRequest.timeout()`；`resolveEffectiveTimeoutMillis()` 对负预算回退默认 45s | `TavilySearchClient.java:89-115`、`TavilySearchClient.java:197-203`；JDK 17.0.3.1 `HttpClientImpl.send()` 内部 `cf.get()` 无 timeout | Task 2 |
| 3 | 属实 | field evidence 第一条 query 绕过预算检查，deadline 已过仍可能启动请求 | `TavilyFastLaneProvider.java:168-216`、`TavilySearchClient.java:197-203` | Task 3 |
| 4 | 属实 | `InterruptedException` 恢复 flag 后调用方继续做节点保存、快照刷新、事件发布 | `TavilySearchClient.java:99-103`、`DagExecutor.java:523-580` | Task 2、Task 4 |
| 5 | 属实 | 注释死代码包含不可编译旧实现，维护时容易误恢复 | `SearchExecutionCoordinator.java:1247-1301` | Task 9 |
| 6 | 属实 | 多 scope 下 `setTavilyFastLaneAudit` 覆盖前一次 scope 审计 | `TavilyFastLaneProvider.java:100-106`、`TavilyFastLaneProvider.java:219-254` | Task 6 |
| 7 | 基本属实 | 字段覆盖提前判满，弱正文可能关闭后续 planned queries | `SearchExecutionCoordinator.java:1195-1222`、`FieldEvidenceCoverageAggregator.java:64-128` | Task 7 |
| 8 | 属实 | 显式 URL canonicalize 失败时静默丢弃，缺 WARN/audit | `SearchExecutionCoordinator.java:855-874` | Task 8 |
| 9 | 表述需修正 | `InternalLinkDiscoveryService` 当前不做 HTTP，只同步解析已有内容；未来扩展必须纳入预算/cancel | `CollectionExecutionCoordinator.java:541-563`、`InternalLinkDiscoveryService.java:89-118` | Task 8 |
| 10 | 属实但属于设计风险 | field query `sourceType` 会扩展 effective scopes，错误注入 `OPEN_WEB` 会放大 OFFICIAL-only 语义 | `TavilyFastLaneProvider.java:110-132` | Task 6 |
| 11 | 属实 | Source Family `primaryTools` 新旧语义断层，旧测试仍按网页采集主工具理解 | `application.yml`、`WorkflowFactoryTest`、`SearchSourceCatalog*Test` | Task 10 |
| 12 | 属实 | Task66 第三方 query 域名约束旧契约冲突，REVIEW/NEWS 不应锁官方域 | `FieldEvidenceQueryPlannerTest`、`IncludeDomainPlanner`、`Task66GenerativeQueryPlannerSystemTest` | Task 10 |
| 13 | 属实 | supplement 成功后 fusion decision 陈旧，`effectiveTargetCount` 与 trace 可能不一致 | `SearchExecutionCoordinator.java:304-440`、`SearchExecutionCoordinator.java:626-671` | Task 5 |
| 14 | 属实 | `maxCandidatesPerDomain` 可能误伤同官网 docs/pricing/help 多页面证据 | `SearchCandidateFusionPlanner.java`、`CollectorAgentTest` 备注 | Task 5 |
| 15 | 属实 | 两个已跟踪日志文件污染工作区，正式提交前不能带入 | `backend/logs/competitor-agent.log`、`backend/logs/live-restart-9093-20260703-121251.out.log` | Task 11 |

## 4. 根因分析

### 4.1 RocketMQ topic：协作 trace 绕开统一 outbox 契约

普通 workflow 事件通过 `WorkflowEventOutboxService.stage()` 入库，topic 来自 `rocketmq.workflow.topic`：

```java
TaskWorkflowEvent entity = TaskWorkflowEvent.builder()
        .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
        .topic(rocketMqProperties.getWorkflow().getTopic())
        .tag(resolveTag(workflowEvent.getEventType()))
        .sourceUrls(writeJsonSafely(workflowEvent.getSourceUrls() == null ? List.of() : workflowEvent.getSourceUrls()))
        .build();
```

但协作规划 trace 直接写 `TaskWorkflowEventRepository`，并硬编码非法 topic：

```java
private static final String TOPIC = "task.collaboration";

TaskWorkflowEvent event = TaskWorkflowEvent.builder()
        .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
        .topic(TOPIC)
        .tag(tag)
        .sourceUrls(writeJson(sourceUrls == null ? List.of() : sourceUrls))
        .build();
```

下游 outbox 调度直接使用事件行上的 topic：

```java
private String buildDestination(TaskWorkflowEvent candidate) {
    return candidate.getTopic() + ":" + candidate.getTag();
}
```

根因不是 RocketMQ 不稳定，也不是重试次数不够，而是协作 trace 写入绕开统一 topic 来源、合法性校验和 tag 映射。确定性配置错误被当成可重试投递错误，导致 6 次无意义重试后 DLQ。

### 4.2 Tavily 阻塞：deadline 只管下一条 query，管不住当前外部调用

`TavilyFastLaneProvider` 当前只在第二条及后续 query 启动前检查预算：

```java
long remainingBudgetMillis = resolveRemainingFieldEvidenceBudgetMillis(request);
if (startedAnyFieldEvidenceQuery && shouldStopFieldEvidenceExecution(remainingBudgetMillis)) {
    queryAudits.add(buildFieldEvidenceQueryAudit(query, "SKIPPED", 0L, 0,
            null, "SKIPPED_BUDGET_EXHAUSTED", null));
    break;
}
TavilySearchClient.TavilySearchResponse response = client.search(profile, remainingBudgetMillis);
```

如果 deadline 已过，第一条 query 仍会执行；`TavilySearchClient.resolveEffectiveTimeoutMillis(queryBudgetMillis)` 在预算小于等于 0 时回退到默认 45s。真正卡住的是当前已经启动的同步 HTTP 调用：

```java
HttpResponse<String> response = httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
);
```

JDK 17.0.3.1 源码显示 `HttpClientImpl.send()` 内部会创建 `CompletableFuture` 并调用无 timeout 的 `cf.get()`。项目层没有外层 `Future.get(timeout)` 或 `orTimeout`，因此当前运行环境出现了超过 45s 仍不返回的线程阻塞。

### 4.3 stop 残留 RUNNING：任务状态、节点状态、执行线程没有统一取消句柄

停止入口当前只改 task 并调用 recovery：

```java
task.setStatus(AnalysisTaskStatus.STOPPED);
task.setErrorMessage("任务已由用户主动停止");
task.setCompletedAt(LocalDateTime.now());
taskRecoveryService.markStoppedNodes(taskId);
```

`markStoppedNodes()` 只处理 `PENDING` 和 `PAUSED`：

```java
if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED) {
    node.setStatus(TaskNodeStatus.SKIPPED);
    node.setErrorMessage("任务已被用户主动停止");
    node.setCompletedAt(LocalDateTime.now());
}
```

运行线程由 `DagExecutor` 本地 executor 管理，但没有对外暴露可取消句柄；主循环等待完成节点时使用阻塞 `take().get()`。因此 stop 之后 DB 主表已经 STOPPED，但 running collector 不会被取消，也不能及时写成终态。

### 4.4 Search-first 补充风险：修完卡死后还会继续显形

9a 卡在 Tavily supplement，所以没有走到 supplement 成功后的候选选择阶段。但代码已经存在几类后续风险：

1. Source family 语义已经切到 `PUBLIC_SEARCH` primary，旧测试仍把 `primaryTools` 当网页采集主工具。
2. 第三方 evidence path 的 REVIEW/NEWS query 已经应当开放 `includeDomains=[]`，旧 Task66 系统测试还要求锁官方域。
3. fusion planner 只在 bootstrap 后执行一次，supplement merge 后 `effectiveTargetCount` 与 trace 可能陈旧。
4. 全局 per-domain cap 会误伤同官网的 docs/pricing/help/API reference 多页面证据。
5. field coverage 用 `completedPaths >= minimumAttemptedPaths` 提前判满，弱正文可能关闭后续 planned queries。
6. 多 scope Tavily audit 会被覆盖，导致真实执行链路不可审计。

这些不是本次卡死的直接原因，但如果只修 Tavily timeout，下一轮 9a 可能马上暴露这些问题。

## 5. 修复原则

本次禁止把以下动作当成主修法：

1. 只把 Tavily timeout 从 45 秒调大或调小。
2. 只把 RocketMQ retry 从 6 改成更大。
3. 只给 9a 的抖音/B站任务加特殊分支。
4. 只在 stop 后把前端展示强行改成 STOPPED，而不回收节点和执行线程。
5. 只给 `task.collaboration` 做字符串替换，不统一 outbox 入口和 topic 校验。
6. 只把 field query quota 再调小，不修第一条 query 预算门禁和外部调用硬边界。
7. 为了旧测试变绿，把 official `primaryTools` 改回 `WEB_SCRAPER/JINA_READER`。
8. 为了旧 Task66 测试变绿，把 REVIEW/NEWS 重新锁进官方域名。

根因级修法必须满足：

1. topic 合法性在事件入库前校验，协作 trace 复用统一 outbox。
2. 外部搜索调用本身有硬超时、可取消、可审计失败结果。
3. coordinator/provider 边界有第二道超时隔离，单个 provider 不能吊死 collector。
4. stop 命令能贯穿任务、节点、执行 future、外部调用和快照。
5. 节点开始执行就写 `lastAttemptAt`，超时/取消进入 query audit 和节点错误信息。
6. search-first 的最终候选、目标数、trace 必须来自同一份 final fusion decision。
7. 字段证据闭环不能只看“有一条成功”，还要看内容质量和证据路径覆盖。

## 6. 修复任务

### Task 1：统一 workflow topic 契约，修复协作事件 DLQ

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventTopicPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/config/RocketMqProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventOutboxService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/CollaborationTraceServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/config/RocketMqPropertiesTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/event/WorkflowEventOutboxServiceContextTest.java`

- [ ] **Step 1: 新增 topic policy 失败测试**

覆盖合法 topic、非法 `task.collaboration`、空 topic。

- [ ] **Step 2: 实现 `WorkflowEventTopicPolicy`**

规则：`^[%|a-zA-Z0-9_-]+$`。异常消息必须包含字段名和非法 topic，便于启动/执行入口定位。

- [ ] **Step 3: 配置入口提前校验**

`RocketMqProperties.validateForExecution()` 不只校验非空，还校验 `rocketmq.workflow.topic` 合法。

- [ ] **Step 4: 协作 trace 改走统一 outbox**

`CollaborationTraceService` 不再硬编码 `task.collaboration`，改为构造 `WorkflowEvent` 后交给 `WorkflowEventOutboxService.stage()`。保留 `sourceUrls`，满足无幻觉溯源要求。

- [ ] **Step 5: outbox 历史非法 topic 快速 DLQ**

`WorkflowEventOutboxService.publishCandidate()` 遇到历史非法 topic 时不再普通重试 6 次，直接标记 `DEAD_LETTER`，`lastError=invalid rocketmq topic: <topic>`。

- [ ] **Step 6: 运行测试**

```bash
mvn -pl backend -Dtest=CollaborationTraceServiceTest,RocketMqPropertiesTest,WorkflowEventOutboxServiceContextTest test
```

期望：
- 新任务不再产生 `task.collaboration`。
- topic 含 `.` 的配置在执行入口提前失败。
- `COLLABORATION_*` 不再因 topic 非法进入 DLQ。

### Task 2：TavilySearchClient 增加强制超时、取消与中断边界

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilySearchClient.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilySearchClientTest.java`

- [ ] **Step 1: 新增永不完成 future 的失败测试**

构造 `NeverCompletingHttpClient`，让 `sendAsync()` 返回永不完成的 `CompletableFuture`。用 `assertTimeoutPreemptively(Duration.ofSeconds(2))` 验证 `client.search(profile, 1000L)` 必须在硬边界内返回失败响应。

- [ ] **Step 2: 改为 `sendAsync()` + 显式 timeout**

每次 attempt 都计算 `effectiveTimeoutMillis`，使用 `sendAsync()` 并通过 `get(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)` 或 `orTimeout` 等待。

- [ ] **Step 3: 修正 `resolveEffectiveTimeoutMillis()` 的负预算语义**

`queryBudgetMillis <= 0L` 不能再回退完整默认 timeout。预算已过期时，client 层只允许一个很短的硬上限用于 fail-open 收口，建议：

```java
private static final long EXPIRED_BUDGET_TIMEOUT_MILLIS = 5_000L;

private long resolveEffectiveTimeoutMillis(long queryBudgetMillis) {
    long defaultTimeoutMillis = Math.max(1, properties.getTimeoutSeconds()) * 1000L;
    if (queryBudgetMillis <= 0L) {
        return Math.min(defaultTimeoutMillis, EXPIRED_BUDGET_TIMEOUT_MILLIS);
    }
    return Math.max(1_000L, Math.min(defaultTimeoutMillis, queryBudgetMillis));
}
```

配套测试必须断言负预算不会产生 `timeout after 45000ms` 这类误导审计，而应体现预算已耗尽或最多 5s 硬上限。

- [ ] **Step 4: 超时和中断时 cancel future**

timeout / interrupt / cancellation 时必须执行 `future.cancel(true)`，返回：

```java
emptyResponse(profile.getQuery(), "tavily timeout after " + effectiveTimeoutMillis + "ms")
```

中断时恢复 interrupt flag，但调用方必须在 Task 4 的 DAG 边界把它消费成取消结果，不能继续发布普通成功/失败事件链。

- [ ] **Step 5: 重试受总预算约束**

每次 retry 前重新计算剩余预算；预算耗尽时不再启动下一次 HTTP。不能出现 `maxRetries * 45s` 放大单条 query 的情况。

- [ ] **Step 6: 运行测试**

```bash
mvn -pl backend -Dtest=TavilySearchClientTest test
```

期望：
- 永不返回的 Tavily future 不会占住调用线程超过预算。
- 超时以 fail-open 响应返回，并带 `failureReason`。
- interrupt 不会被吞掉，也不会让后续链路误写普通结果。

### Task 3：field evidence 第一条 query 也必须走预算门禁

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`

- [ ] **Step 1: 新增 deadline 已过的第一条 query 测试**

构造 `SearchSourceRequest.fieldEvidenceExecutionDeadlineEpochMillis=System.currentTimeMillis()-1`，断言第一条 matching field query 也不会调用 `client.search()`。

- [ ] **Step 2: 调整循环预算检查**

在 `profileResolver.resolveFieldEvidence(query)` 或 `client.search()` 前检查：

```java
long remainingBudgetMillis = resolveRemainingFieldEvidenceBudgetMillis(request);
if (shouldStopFieldEvidenceExecution(remainingBudgetMillis)) {
    queryAudits.add(buildFieldEvidenceQueryAudit(query, "SKIPPED", 0L, 0,
            null, "SKIPPED_BUDGET_EXHAUSTED", null));
    continue;
}
```

不要再用 `startedAnyFieldEvidenceQuery` 让第一条 query 绕过预算。

- [ ] **Step 3: audit 保留 skipped query**

deadline 已过时返回空候选，但 `request.tavilyFastLaneAudit.fieldEvidenceQueryExecutions` 必须记录 skipped。

- [ ] **Step 4: 运行测试**

```bash
mvn -pl backend -Dtest=TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest test
```

### Task 4：stop 贯穿任务、节点、future 与迟到写回

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskExecutionCancellationRegistry.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskRecoveryService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskRecoveryServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 新增 stop running 节点状态测试**

验证 `markStoppedNodes()` 对 `RUNNING` 节点不再无动作。期望 task STOPPED 后，库里没有普通 `RUNNING` 残留。

- [ ] **Step 2: 新增迟到结果不覆盖 STOPPED 测试**

模拟节点执行期间 stop，执行线程之后返回 SUCCESS，断言节点保持 `SKIPPED/STOPPED` 语义，不写 SUCCESS。

- [ ] **Step 3: 新增 cancellation registry**

`TaskExecutionCancellationRegistry` 记录：

```text
taskId -> executorService
taskId + nodeName -> Future<NodeExecutionResult>
```

提供 `registerTaskExecutor`、`registerNodeFuture`、`cancelTask`、`clearTask`。

- [ ] **Step 4: DagExecutor 提交 future 后注册句柄**

将 `completionService.submit(...)` 返回的 `Future<NodeExecutionResult>` 注册到 registry。

- [ ] **Step 5: `awaitNextCompletedNode()` 改为短轮询**

不要无限 `take().get()`，改为 `poll(1, TimeUnit.SECONDS)`。主循环每轮重新检查 `isTaskStopped(taskId)`。

- [ ] **Step 6: stopTask 调用 cancel registry**

`TaskRuntimeCommandAppService.stopTask()` 保存 task STOPPED 后调用 `taskExecutionCancellationRegistry.cancelTask(taskId)`，再刷新快照和发布事件。

- [ ] **Step 7: markStoppedNodes 处理 RUNNING**

`PENDING/PAUSED/READY/DISPATCHED` 直接 `SKIPPED`。`RUNNING` 至少写入：

```text
status=SKIPPED
controlState=TERMINATE_REQUESTED 或 NONE
errorMessage=任务已被用户主动停止
completedAt=now
```

核心要求是数据库权威状态不再无限残留 RUNNING。

- [ ] **Step 8: executeRunningNode 写回前加 stop 护栏**

`executeNodeOnce()` 返回后，先消费线程中断信号，再判断 DB 停止状态。中断 flag 与 `STOPPED/TERMINATE_REQUESTED` 是两个独立信号源，都必须进入停止收口：

```java
AgentResult result = executeNodeOnce(capability, nodeContext);
boolean interrupted = Thread.interrupted(); // 读取并清除 interrupt flag，避免污染 JPA/Redis/RocketMQ 等后续调用。
if (interrupted) {
    TaskNode latestNode = nodeRepository.findById(node.getId()).orElse(node);
    markNodeStopped(latestNode);
    syncNodeState(node, latestNode);
    return new NodeExecutionResult(latestNode);
}
```

随后再重读 task/node；若 task 已 STOPPED 或 node 已 TERMINATE_REQUESTED，丢弃本轮结果，只允许走停止/终止收口，不允许进入普通 `applyExecutionResult()`、runtime refresh、普通 node completed/failed event 发布链，也不允许写 SUCCESS/FAILED 覆盖。

- [ ] **Step 9: 运行测试**

```bash
mvn -pl backend -Dtest=TaskRecoveryServiceTest,TaskRuntimeCommandAppServiceTest,DagExecutorTest test
```

### Task 5：候选融合 final decision 与 per-domain cap 收口

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 新增 supplement 后重算 fusion 测试**

bootstrap 只有 1 个弱 seed，supplement 返回 2 个强 third-party/fast-lane 候选。断言 final target count、selected targets、trace 使用同一份 final fusion decision。

- [ ] **Step 2: supplement/public recovery merge 后重跑 planner**

`SearchExecutionCoordinator` 在候选池发生结构性变化后重新调用 `searchCandidateFusionPlanner.plan(...)`，最终 `collectionTargetSelector.selectTargets(...)` 使用 final fusion 的 `effectiveTargetCount`。

- [ ] **Step 3: trace 拆分 pre/final fusion**

保留 bootstrap 后的 pre-supplement 统计，同时新增 final fusion 统计，避免排查时看见旧数字。

- [ ] **Step 4: per-domain cap 按证据路径保底**

`SearchCandidateFusionPlanner` 的同域限制不能只按 host 截断。官方同域 docs/pricing/help/API reference 至少按 `sourceType/evidencePathKey/pageType/queryIntent` 保留不同证据路径各 1 条，再执行总量裁剪。

- [ ] **Step 5: 运行测试**

```bash
mvn -pl backend -Dtest=SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest test
```

### Task 6：Tavily audit 多 scope 合并与 scope 扩展可观测

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`

- [ ] **Step 1: 新增多 scope field query audit 合并测试**

构造 OFFICIAL 与 DOCS/OPEN_WEB 等多个 scope 都有 field query 执行，断言 `request.getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions()` 包含全部 scope 的 query execution。

- [ ] **Step 2: `search()` 层聚合 scope audit**

不要在每次 `searchFieldEvidenceQueries()` 内覆盖 request audit。改为每个 scope 返回候选和 audit，外层用 `TavilyFastLaneAudit.merge(...)` 合并后再写入 request。

- [ ] **Step 3: 空 audit 不参与 merge，不能覆盖有效 audit**

scope 返回空候选时也可能有 skipped audit；但如果某个 scope 没有匹配 query，`buildFieldEvidenceFastLaneAudit(...)` 返回 `null` 或 `fieldEvidenceQueryExecutions` 为空，这个空 audit 不得参与 merge，也不得执行 `request.setTavilyFastLaneAudit(null)` 覆盖前一个有效 scope。外层聚合逻辑必须类似：

```java
List<TavilyFastLaneAudit> scopeAudits = new ArrayList<>();
...
if (audit != null
        && audit.getFieldEvidenceQueryExecutions() != null
        && !audit.getFieldEvidenceQueryExecutions().isEmpty()) {
    scopeAudits.add(audit);
}
...
request.setTavilyFastLaneAudit(TavilyFastLaneAudit.merge(scopeAudits));
```

如果所有 scope 都没有 audit，才允许最终 audit 为 `null`。如果某个 scope 因 deadline 已过返回空候选但记录了 `SKIPPED_BUDGET_EXHAUSTED`，该 audit 必须参与 merge。

- [ ] **Step 4: scope 扩展写入 trace/audit**

`resolveEffectiveScopes()` 因 field query sourceType 注入额外 scope 时，记录扩展来源，便于发现 OFFICIAL-only 被 OPEN_WEB 放大的情况。

- [ ] **Step 5: 运行测试**

```bash
mvn -pl backend -Dtest=TavilyFastLaneProviderTest test
```

### Task 7：字段覆盖判满增加内容质量门槛

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceTest.java`

- [ ] **Step 1: 新增弱正文不关闭字段测试**

构造一个 success result，带 sourceUrls 和 pathKey，但 content 很短或质量信号显示弱正文。断言该 path 不进入 `completedPaths`，后续 planned queries 仍会下发。

- [ ] **Step 2: 收紧 `canCountAsFieldEvidence`**

在现有 `success + no unusable signal + repair complete` 基础上增加内容质量判断，例如正文长度、结构块数量或明确质量信号。不要让一段简介式弱内容关闭整个字段。

- [ ] **Step 3: `isFieldCoverageSatisfied` 使用 completedPaths 和 sourceUrls 双条件**

字段满足不只看 `completedPaths >= minimumAttemptedPaths`，还要确保 distinct source URL 和内容质量达到字段最低要求。

- [ ] **Step 4: 运行测试**

```bash
mvn -pl backend -Dtest=FieldEvidenceCoverageAggregatorTest,SearchExecutionCoordinatorFieldEvidenceTest test
```

### Task 8：配置 URL 与内部链接发现边界可观测

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryServiceTest.java`

- [ ] **Step 1: canonicalize 失败加 WARN/audit 测试**

显式配置 `competitorUrls` 含不可 canonicalize 的 URL，断言有 warning 或 search trace rejection reason，不再静默丢弃。

- [ ] **Step 2: mergeConfiguredCandidatesWithExplicitUrls 增加可观测拒绝原因**

canonicalize 失败、重复 canonical URL 都要进入日志或 trace，至少能让排查知道某个显式 URL 为什么没生效。

- [ ] **Step 3: InternalLinkDiscoveryService 保持无 HTTP I/O 合同**

新增或补充测试，明确 `discover()` 只解析已有 content，不发网络请求。若未来要抓取子链接，必须另走 collection executor 和 budget/cancel guard。

- [ ] **Step 4: 运行测试**

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorTest,InternalLinkDiscoveryServiceTest test
```

### Task 9：删除死代码与运行态可观测补强

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/FieldEvidenceQueryExecutionAudit.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 删除 `SearchExecutionCoordinator` 注释旧实现**

删除 `resolveExecutableFieldEvidenceQueries` 的大段 `/* */` 旧代码，包括 `if (false)` 和重复 `int quota`。不保留“将来参考”。

- [ ] **Step 2: 节点开始执行即写 lastAttemptAt**

`markNodeRunning()` 或等价入口写入 `lastAttemptAt=startedAt`，让数据库快照能表达节点已开始尝试。

- [ ] **Step 3: Tavily timeout/cancel 进入 query audit**

timeout/cancel 后写入：

```text
status=FAILED
failureReason=tavily timeout after xxxms 或 tavily interrupted
elapsedMillis=xxx
resultCount=0
```

- [ ] **Step 4: 运行测试**

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,DagExecutorTest test
```

### Task 10：Source family 与 Task66 旧测试契约更新

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/HeuristicSourceDiscoveryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserPreviewSearchSourceProviderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/Task66GenerativeQueryPlannerSystemTest.java`
- Test: same files

- [ ] **Step 1: 更新 official primaryTools 新语义测试**

断言 official family 的 source discovery primary 是 `PUBLIC_SEARCH`；网页正文采集工具应在 auxiliary 或 collection package `primaryTool` 中验证。

- [ ] **Step 2: 更新 Task66 第三方 query includeDomains 契约**

官方 evidence path 保留官方域名；`PUBLIC_REVIEW_OR_NEWS` 的 REVIEW/NEWS/OPEN_WEB 要求 `includeDomains=[]`。

- [ ] **Step 3: 增加同字段官方/第三方 query 策略差异测试**

同一个字段同时产生官方 query 和第三方 query，断言二者 includeDomains 策略不同。

- [ ] **Step 4: 运行红灯回归**

```bash
mvn -pl backend -Dtest=WorkflowFactoryTest#shouldEmbedSourceCandidatesIntoCollectorNodeConfig test
```

```bash
mvn -pl backend "-Dtest=SearchSourceCatalogPropertiesTest,SearchPreviewRuntimeHomologyContractTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,Task66GenerativeQueryPlannerSystemTest" test
```

### Task 11：工作区日志污染隔离

**Files:**
- Do not modify source behavior.
- Check: `backend/logs/competitor-agent.log`
- Check: `backend/logs/live-restart-9093-20260703-121251.out.log`

- [ ] **Step 1: 确认日志 diff 不进入本次修复提交**

```bash
git status --short -- backend/logs
```

期望：正式提交前不包含几十万行日志 diff。

- [ ] **Step 2: 若项目允许，移出历史索引**

仅在用户确认后执行：

```bash
git rm --cached backend/logs/competitor-agent.log backend/logs/live-restart-9093-20260703-121251.out.log
```

如果不移出索引，本次提交必须手动排除这些日志文件。

## 7. 回归命令

按优先级分组执行：

```bash
mvn -pl backend -Dtest=CollaborationTraceServiceTest,RocketMqPropertiesTest,WorkflowEventOutboxServiceContextTest test
```

```bash
mvn -pl backend -Dtest=TavilySearchClientTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest test
```

```bash
mvn -pl backend -Dtest=TaskRecoveryServiceTest,TaskRuntimeCommandAppServiceTest,DagExecutorTest test
```

```bash
mvn -pl backend -Dtest=SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest,FieldEvidenceCoverageAggregatorTest,SearchExecutionCoordinatorFieldEvidenceTest test
```

```bash
mvn -pl backend "-Dtest=SearchSourceCatalogPropertiesTest,SearchPreviewRuntimeHomologyContractTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,Task66GenerativeQueryPlannerSystemTest" test
```

最后再跑：

```bash
mvn -pl backend test
```

## 8. 9a 真实链路复跑

运行期产物目录：

```text
tmp/task12-9a-YYYYMMDD-HHMMSS/
```

复跑步骤：

1. 重启 9093。
2. 用 9a 同款请求创建新任务并执行。
3. 保存 create/execute/stop 响应、任务快照、节点快照、事件状态、证据统计、线程栈。
4. 如果 Tavily 正常返回，按原 9a KPI 验收采集丰富度：
   - `evidence_rows >= 3`
   - `distinct_urls >= 3`
   - 至少 1 个非官方/非用户输入根域
   - `full_content_gt_2000 >= 2`
5. 如果 Tavily 外部不可用，也必须满足降级验收：
   - collector 不得超过预算长期 RUNNING。
   - search audit 必须写明 Tavily timeout 或 provider timeout。
   - stop 后 5 秒内任务和运行中节点都进入终态。
   - 不得再出现 `task.collaboration` topic DLQ。

## 9. 完成标准

代码层完成标准：

1. 协作 trace 不再硬编码非法 topic。
2. topic 合法性在入库或执行入口提前失败，不再把确定性配置错误重试 6 次。
3. Tavily 单次 HTTP 调用有硬超时和 cancel。
4. field evidence 第一条 query 也受 deadline 门禁约束。
5. coordinator/provider 边界有 fail-open 超时保护。
6. stop 能取消运行句柄，数据库中不再残留普通 RUNNING collector。
7. 迟到的节点结果不会覆盖 STOPPED 状态。
8. `lastAttemptAt` 和 field query audit 能解释“正在做什么/为什么失败”。
9. supplement 成功后 final fusion、selected targets、trace 口径一致。
10. official `PUBLIC_SEARCH` primary 与 collection tool 语义不再混淆。
11. 第三方 REVIEW/NEWS query 不再被官方域名锁死。

9a 复跑完成标准：

1. `task_workflow_event` 无 `task.collaboration` 新增行。
2. `COLLABORATION_PLAN_RECORDED`、`COLLABORATION_CHECKPOINT_UPDATED` 不因 topic 非法进入 `DEAD_LETTER`。
3. Tavily 卡住场景下，collector 在预算内失败或降级，不再无限 RUNNING。
4. stop 后 5 秒内 `analysis_task=STOPPED` 且所有运行中节点终态化。
5. 如果 Tavily 服务可用，再看采集 KPI；如果 Tavily 服务不可用，本轮至少证明系统可恢复、可解释、可停止。

## 10. 风险与边界

1. 本方案不解决 Tavily 外部服务可用性本身，只保证外部不可用时主链路不被吊死。
2. provider 级 guard 如果 cancel 后底层线程仍被 JDK/网络栈拖住，Task 2 的 `sendAsync` 硬超时必须作为第一道防线，Task 4 的 registry 作为编排层止损。
3. 历史 DLQ 事件保留审计，不自动改写为可消费，避免重复协作 trace 造成回放混淆。
4. 内部链接发现当前不发 HTTP；若未来要抓取子链接，必须另走 collection executor 和预算/取消边界。
5. 已跟踪日志文件属于工作区卫生问题，不能混入本次修复 diff。
