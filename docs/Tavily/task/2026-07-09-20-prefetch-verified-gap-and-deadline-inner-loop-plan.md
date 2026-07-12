# Task20 Tavily Prefetch 验证章与 Deadline 内循环收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Stage1 双竞品 live E2E 中 `verified=None` 的 Tavily prefetch 证据绕过验证章、以及 DOCS/REVIEW 节点越过 hard deadline 仍按 `SUCCESS` 收口的问题。

**Architecture:** 第一刀收敛 Tavily prefetch 入选契约：`skipNetworkVerification=true` 才能免网络验证并盖 `verified=true / VERIFIED`，否则必须先进入 `CandidateVerifier` 完整验证。第二刀把同一个 collector hard deadline token 传入 search、collection、web executor、Playwright/HTTP 调用前检查点，并让 deadline break 后的部分结果统一收口为 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`。两刀必须同做同验，避免“守时但证据饿死”或“证据够但未验证”。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Jackson, Tavily live E2E.

---

## 1. 来源诊断

来源文档：

```text
docs/Tavily/plan/2026-07-09-13-prefetch-verified-gap-and-deadline-inner-loop-plan.md
```

现场：

```text
tmp/stage1-degraded-live-e2e-drainfix-20260709-172938
taskId=103
reportId=94
```

对照现场：

```text
tmp/stage1-degraded-live-e2e-rerun-20260709-155144
taskId=102
reportId=93
```

本 task 只处理两条采集层根因：

```text
1. Tavily prefetch 证据 verified=None，绕过验证章进入报告。
2. hard deadline 只在 CollectorAgent 外层，CollectionExecutionCoordinator / Playwright 内层循环不消费它。
```

暂不处理第三层“来源家族多样性”质量问题。该问题应在验证章和 deadline 语义收口后另起 task，避免一次修复跨多个故障层。

## 2. 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 用红灯测试锁住 Tavily prefetch 入选契约 | 45 分钟 | 已读源诊断与 `CollectionTargetSelector` / `CandidateVerifier` 现状 |
| Task 2 | 修改 selector / verifier，消除 `skipNetworkVerification=false` 直通 | 60 分钟 | Task 1 |
| Task 3 | 新增 collector deadline 上下文值对象并接入任务包 / 采集请求 | 60 分钟 | Task 2 |
| Task 4 | 让 SearchExecutionCoordinator 消费 collector deadline token | 60 分钟 | Task 3 |
| Task 5 | 让 CollectionExecutionCoordinator 内层 queue / batch / executor 调用消费 deadline | 90 分钟 | Task 3 |
| Task 6 | 让 WebPageCollectionExecutor / PlaywrightPageCollector 在外部抓取前检查剩余时间 | 75 分钟 | Task 5 |
| Task 7 | 修正 CollectorAgent 越界收口语义与 audit 输出 | 60 分钟 | Task 4-6 |
| Task 8 | 跑分层测试与 live E2E 复测，记录验收证据 | 90 分钟 | Task 1-7 |

## 3. 进度记录

- [x] Task 1：Tavily prefetch 入选契约红灯测试
- [x] Task 2：Selector / verifier 契约修复
- [x] Task 3：Deadline 上下文值对象与请求链路
- [x] Task 4：SearchExecutionCoordinator 消费 deadline
- [x] Task 5：CollectionExecutionCoordinator 内层循环消费 deadline
- [x] Task 6：WebPageCollectionExecutor / PlaywrightPageCollector 抓取前检查 deadline
- [x] Task 7：CollectorAgent 降级收口语义
- [ ] Task 8：分层测试与 live E2E 复测（live E2E 已执行，验收未完全通过）

执行过程中每次停下必须追加：

```markdown
### 停顿记录：YYYY-MM-DD HH:mm
- 当前阶段：[正在进行的阶段]
- [x] 信息采集：已完成
- [ ] 数据分析：执行中
- [ ] 报告撰写：待执行
- 已完成：
- 当前测试：
- 测试结果：
- 暴露问题：
- 下一步：
```

### 停顿记录：2026-07-11 12:10
- 当前阶段：Task 3 / Task 5 开工前代码事实对齐
- [x] 信息采集：已完成
- [ ] 数据分析：执行中
- [ ] 报告撰写：待执行
- 已完成：
  - Task 1 已完成：补齐 `SearchCandidateFusionPlannerTest`、`SearchExecutionCoordinatorTest`、`CollectionTargetSelectorTest`、`CandidateVerifierTest`、`TavilyPrefetchedContentGateTest` 的 prefetch 契约红灯测试，并修正旧 selector 夹具，让已有用例显式携带 `providerKey=tavily`、`skipNetworkVerification=true`、`pageType`、`sourceUrls`。
  - Task 2 已完成：`CollectionTargetSelector` 现在只消费 Gate 写入的 `skipNetworkVerification=true` 免验授权信号；未获授权且 `verified!=true` 的 Tavily prefetch 会在 selector 末端被拒绝；获授权的 prefetch 会补齐 `verified=true`、`verificationReason=TAVILY_FAST_LANE_GATE_VERIFIED`、`TAVILY_VERIFICATION_SKIPPED`。
- 当前测试：
  - `mvn -pl backend "-Dtest=CollectionTargetSelectorTest,CandidateVerifierTest,SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest,TavilyPrefetchedContentGateTest" test`
- 测试结果：
  - 定向测试集通过：`Tests run: 80, Failures: 0, Errors: 0, Skipped: 0`。
- 暴露问题：
  - 红灯阶段确认 `SearchCandidateFusionPlanner`、`CandidateVerifier`、`SearchExecutionCoordinator` 的主链路本身已符合“skip=false 先入 verifier”的契约，当前真实接缝主要集中在 selector 末端放行与验证章补写。
  - Maven 末尾仍会输出既有 XML 解析噪音日志，但本轮定向测试未因该噪音失败。
- 下一步：
  - 进入 Task 3，新增 `CollectionDeadlineContext` 并把 deadline token 串入 `CollectionExecutionCoordinator -> CollectionTaskPackageBuilder -> WebPageCollectionExecutor -> SourceCollectRequest / PlaywrightPageCollector`。
  - 继续按计划先修内层 deadline 消费，再补 `SearchExecutionCoordinator` 与 `CollectorAgent` 的统一降级收口，最后停在 live E2E 前总结。

### 停顿记录：2026-07-11 15:08
- 当前阶段：Task 8 分层回归完成，停在 live E2E 前
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [ ] 报告撰写：待执行
- 已完成：
  - Task 3-7 已按计划完成，`CollectionDeadlineContext` 已贯穿 `CollectorAgent -> SearchExecutionCoordinator -> CollectionExecutionCoordinator -> WebPageCollectionExecutor -> SourceCollectRequest / PlaywrightPageCollector`，同一轮 collector 执行只消费同一份 hard deadline token。
  - 修复 `CollectorAgent` 字段证据第二轮回补链路没有复用同一份 deadline token 的接缝，避免首轮与二轮各自重开预算。
  - 修复 `AgentRuntimeContractTest` / `AgentContextAssemblerTest` 对 `fieldEvidenceFingerprintClaims` 的 runtime 契约缺口。
  - 修复 `PlaywrightPageReadinessContractTest` 仍断言旧 `waitForLoadState` 签名的测试漂移，只调整测试契约，不改生产逻辑。
  - 已跑通本任务直接相关综合套件：
    - `mvn -pl backend clean "-Dtest=CollectionDeadlineContextTest,SearchExecutionCoordinatorTest,CollectionExecutionCoordinatorTest,WebPageCollectionExecutorContextTest,PlaywrightPageCollectorTest,PlaywrightPageReadinessContractTest,CollectorAgentTest,CollectorAgentFieldEvidenceLoopTest,AgentRuntimeContractTest,AgentContextAssemblerTest" test`
    - 结果：`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0`
  - 已跑通 E2E 前最后一组相关回归：
    - `mvn -pl backend clean "-Dtest=StageOneDegradedContractIntegrationTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,ReportDeliverySummaryServiceTest" test`
    - 结果：`Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`
- 当前测试：
  - `mvn -pl backend clean "-Dtest=StageOneDegradedContractIntegrationTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,ReportDeliverySummaryServiceTest" test`
- 测试结果：
  - 本任务直接相关的 prefetch / deadline / degraded-contract 分层验证已经转绿，可以在不跑 live E2E 的前提下确认主链路闭环成立。
- 暴露问题：
  - `mvn -pl backend test` 仍有剩余失败，当前集中在 `Task66CoverageContractRegressionTest`、`Task66FieldFirstEvidenceLoopSystemTest`、`CoverageContractProviderTest`、`WorkflowFactoryTest`、`SectionEvidenceBundleTest`、`CitationAgentRepairabilityTest`、`ReportWriterAgentTest` 及若干 workflow integration。
  - 这些失败落点主要在 coverage / citation / writer / workflow 领域，现阶段证据不足以证明它们由本任务的 prefetch/deadline 修复引入，因此没有扩散去改 `QualityReviewAgent`、`ReportService`、`NodeExecutionRecoveryPolicy`、citation/writer 生产逻辑。
  - live E2E 尚未启动，严格停在 E2E 前。
- 下一步：
  - 如需继续清 backend 全量失败，应先逐个归因剩余 coverage/workflow 失败，确认是否属于另一条任务主线，再决定是否开新修复。
  - 如按当前任务边界收口，则下一步就是由你接手执行 live E2E 复测。

### 验收记录：2026-07-11 20:15
- 当前阶段：Task 8 live E2E 已执行，流程终态成功但业务验收未完全通过
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- 测试命令：
  - 启动后端：`mvn -pl backend spring-boot:run`
  - 健康检查：`GET http://127.0.0.1:9093/actuator/health`
  - E2E 链路：`POST /api/task/preview -> POST /api/task/create -> POST /api/task/104/execute -> poll task/nodes/report -> replay/evidences/agent logs`
- 单测结果：
  - 本轮 E2E 前已重新跑通 prefetch 契约测试：`Tests run: 81, Failures: 0, Errors: 0, Skipped: 0`
  - 本轮 E2E 前已重新跑通 deadline 契约测试：`Tests run: 111, Failures: 0, Errors: 0, Skipped: 0`
  - 本轮 E2E 前已重新跑通 stage1 degraded 回归：`Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`
- E2E 输出目录：
  - `tmp/stage1-degraded-live-e2e-prefetch-deadlinefix-20260711-200132`
- taskId / reportId：
  - `taskId=104`
  - `reportId=127`
- 终态摘要：
  - `TaskResponse.status=SUCCESS`
  - `completedNodes=14/14`
  - `nodeGroups=SUCCESS=6; SUCCESS_DEGRADED=5; SKIPPED=3`
  - `canViewReport=true`
  - `deliveryStatus=NEEDS_EVIDENCE`
  - `qualityScore=24`
  - `qualityPassed=false`
  - `evidenceCount=9`
  - `sourceUrlCount=11`
- verified 分布：
  - `TRUE=9`
  - `FALSE=0`
  - `NULL=0`
  - 结论：报告证据层未再出现 `verified=None`，本轮 `verified=None` 的 Tavily prefetch 进入报告问题已消除。
- collector 耗时与状态：
  - `collect_sources_01_01 Notion OFFICIAL`: `SUCCESS_DEGRADED`, `collectionStatus=FAILED`, `readyForQuorum=false`, `duration=95.3s`, `HARD_DEADLINE_REACHED`
  - `collect_sources_01_02 Notion DOCS`: `SUCCESS_DEGRADED`, `collectionStatus=FAILED`, `readyForQuorum=false`, `duration=120.3s`, `HARD_DEADLINE_REACHED`
  - `collect_sources_01_03 Notion REVIEW`: `SUCCESS_DEGRADED`, `collectionStatus=FAILED`, `readyForQuorum=false`, `duration=120.4s`, `HARD_DEADLINE_REACHED`
  - `collect_sources_02_01 Airtable OFFICIAL`: `SUCCESS_DEGRADED`, `collectionStatus=FAILED`, `readyForQuorum=false`, `duration=120.4s`, `HARD_DEADLINE_REACHED`
  - `collect_sources_02_02 Airtable DOCS`: `SUCCESS_DEGRADED`, `collectionStatus=SUCCESS_DEGRADED`, `readyForQuorum=true`, `duration=204.7s`, `HARD_DEADLINE_REACHED`, `successCollected=4/8`
  - `collect_sources_02_03 Airtable REVIEW`: `SUCCESS`, `collectionStatus=SUCCESS`, `readyForQuorum=true`, `duration=211.2s`, `SEARCH_TIMEOUT_BEFORE_SUPPLEMENT`, `successCollected=5/10`
- 暴露问题：
  - 问题 1：`collect_sources_02_02` 仍超过计划验收线。计划要求 `150s hard deadline + 30s drain grace` 内收口，实际节点墙钟约 `204.7s`。虽然语义已是 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`，但时间预算仍未压进目标窗口。
  - 问题 2：`collect_sources_02_03` 仍超过计划验收线且语义未降级。计划要求 `120s hard deadline + 30s drain grace` 内收口，实际节点墙钟约 `211.2s`，最终仍是普通 `SUCCESS`，仅携带 `SEARCH_TIMEOUT_BEFORE_SUPPLEMENT`，未标 `HARD_DEADLINE_REACHED`。
  - 问题 3：任务终态成功但报告交付仍是 `NEEDS_EVIDENCE`，`qualityScore=24`、`qualityPassed=false`。这说明流程层已跑通，但报告业务质量仍未达到可交付。
  - 问题 4：`extract_schema.totalCompetitors=1`，只产出 Airtable；Notion 三个 collector 均未形成可用文档，最终证据 `9` 条全部来自 Airtable。Notion 证据仍未进入结构化抽取与报告。
  - 问题 5：无可用采集结果的 collector 节点存在“节点 `SUCCESS_DEGRADED`，但 `collectionStatus=FAILED / readyForQuorum=false / documents=0`”的语义落差；虽然没有把 `readyForQuorum` 伪装成 true，但节点级状态仍容易让 UI/下游误读为降级成功。
  - 问题 6：后端日志显示任务质量检查完成后，仍有 `pool-12-thread-*` 继续执行 Notion DOCS 的 Playwright / external script fetch；说明节点/任务收口与底层并发抓取真实停止之间仍有泄漏。疑似落点包括 `CollectionExecutionCoordinator` 并发 batch 对已启动 `CompletableFuture.join()` 的等待/取消边界，以及 `PageContentExtractionSupport` 外部脚本抓取未消费 collector deadline。
- 验收判断：
  - 通过：`verified=None` 不再进入最终报告证据；`evidenceCount` 从上一轮 `11` 的混乱状态收敛为 `9` 条全 verified 证据；任务流程终态从历史 `STOPPED/FAILED` 推进到 `SUCCESS`。
  - 未通过：deadline 墙钟上限、`02_03` deadline 语义、Notion 证据覆盖、报告可交付质量、后台抓取真实停止仍未达成本 task live E2E 完整验收线。
- 下一步：
  - 先补一个针对“任务/节点收口后仍有 Playwright/PageContentExtraction 后台抓取”的可重复测试，定位已启动 batch、Playwright 内容抽取和 external script fetch 的取消边界。
  - 再单独处理 `02_03` 超过 hard deadline 却仍为 `SUCCESS` 的语义漏标。
  - 最后回到 Notion 证据链路，确认 collector 降级后为何无法形成可被 extractor 消费的可用文档。

## 4. 不变量与红线

```text
1. 不得为 `skipNetworkVerification=false` 的 Tavily prefetch 候选批量盖 `verified=true` 假章。
2. 只有真正经过 CandidateVerifier 网络验证，或 TavilyPrefetchedContentGate 明确授权免验证，才能写 `verified=true / VERIFIED`。
3. `sourceUrls` 必须继续强制存在并向下游透传，不能为了通过测试删除可追溯字段。
4. 新增或修改的外部抓取调用必须保留 try-catch、最大重试或硬超时保护；Playwright 单次 `page.navigate` / render wait 必须显式使用 collector 剩余预算作为 timeout 上界，不能只依赖 `future.cancel(true)`。
5. deadline token 必须是同一个 collector 节点截止时间，search / collection / Playwright 不得各自重新开新预算。
6. 内层循环必须在每个 target、每个 batch、每次 Playwright/HTTP 调用前检查 hard deadline；30s grace 只用于外层 drain 交接，禁止用于启动新工作。
7. selector 只消费 Gate 已写入的免验证授权信号，禁止在 selector 内再实现一套新的免验证判定口径。
8. deadline break 后若已有可用结果，节点状态必须是 `SUCCESS_DEGRADED`，并带 `HARD_DEADLINE_REACHED`；不得继续伪装成普通 `SUCCESS`。
9. deadline break 后若没有任何可用结果，必须保留 `readyForQuorum=false` 与 `sourceUrls=[]`，不得伪装成降级成功。
10. 本 task 只修改采集层 verified/deadline 语义；不得修改 `QualityReviewAgent`、`ReportService`、`NodeExecutionRecoveryPolicy` 生产逻辑来让状态好看。
11. 中文业务注释必须补在新增核心方法、复杂判断和 deadline 消费逻辑上。
12. 本 task 不修改 PromptTemplateService；若执行者触及 Agent prompt，必须保留运行时状态输出格式。
```

### 4.1 开工前代码事实基线

执行 Task 5 / Task 6 前必须先按当前代码确认这些锚点仍成立；若行号漂移，以方法名为准重新定位，不得按旧计划盲写：

```text
CollectionExecutionCoordinator.execute(...) 当前入口：backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java:113
queue 初始化：CollectionExecutionCoordinator.java:128
外层递归循环：CollectionExecutionCoordinator.java:146 的 while (!queue.isEmpty())
同深度 batch 聚合：CollectionExecutionCoordinator.java:147-154
batch 执行入口：CollectionExecutionCoordinator.java:159
顺序 executor 调用：CollectionExecutionCoordinator.java:351-367
并发 CompletableFuture 批量提交：CollectionExecutionCoordinator.java:370-396
单包执行与 packageBuilder.build：CollectionExecutionCoordinator.java:418-455
```

prefetch 验证链路的真实顺序是：

```text
SearchCandidateFusionPlanner.plan(...) 先产出 verificationCandidates：SearchCandidateFusionPlanner.java:49-94
skipNetworkVerification=false 且 verified!=true 的预选候选会进入 verificationCandidates：SearchCandidateFusionPlanner.java:66-71
SearchExecutionCoordinator 调 CandidateVerifier：SearchExecutionCoordinator.java:399-407
CandidateVerifier 结果 merge 回 allCandidates / attemptedTargets：SearchExecutionCoordinator.java:407-409
CollectionTargetSelector.selectTargets 最后选正式采集目标：SearchExecutionCoordinator.java:706-711
```

因此本计划的 prefetch 修复职责分层必须是：

```text
1. SearchCandidateFusionPlanner / SearchExecutionCoordinator：保证未获免验证授权的 prefetch 先进入 CandidateVerifier。
2. CandidateVerifier：真实验证后才盖 verified=true；Gate 授权免验证时才跳过网络验证。
3. CollectionTargetSelector：只做最后防漏，拒绝“验证被跳过或超时后仍未盖章”的 prefetch 进入正式采集目标。
```

## 5. 文件结构

### 新增

```text
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionDeadlineContext.java
backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionDeadlineContextTest.java
```

### 修改

```text
backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java
backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java
backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java
backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java
backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGate.java
backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionStats.java
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java
backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java
backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java
backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionAuditSummary.java
backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollectRequest.java
backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java
```

### 测试

```text
backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java
backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java
backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java
backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java
backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGateTest.java
backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java
backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorContextTest.java
backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java
backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java
```

## 6. Task 1：Tavily Prefetch 入选契约红灯测试

**Files:**

```text
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGateTest.java
```

- [ ] Step 1：在 `SearchCandidateFusionPlannerTest` 新增 `shouldRoutePrefetchThatNeedsVerificationIntoVerificationCandidates`

这一步锁住真实链路：未获免验证授权的 prefetch 候选必须先进入 `CandidateVerifier`，不能等到 selector 才被丢弃。

核心断言：

```java
@Test
void shouldRoutePrefetchThatNeedsVerificationIntoVerificationCandidates() {
    SearchCandidateFusionPlanner planner = new SearchCandidateFusionPlanner(
            new SearchPolicyResolver(),
            new SourceCandidateRanker()
    );
    CollectorNodeConfig config = CollectorNodeConfig.builder()
            .competitorName("Airtable")
            .sourceType("DOCS")
            .competitorUrls(List.of("https://airtable.com"))
            .maxSearchResults(2)
            .searchRuntimePolicy(SearchRuntimePolicy.builder()
                    .searchFirstEvidenceTargetFloor(3)
                    .preSelectionVerificationLimit(3)
                    .build())
            .build();

    SourceCandidate needsVerification = SourceCandidate.builder()
            .url("https://support.airtable.com/docs/automation-guide")
            .title("Airtable automation guide")
            .sourceType("DOCS")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
            .domain("support.airtable.com")
            .qualityTier("STRONG")
            .fastLaneUsable(Boolean.TRUE)
            .hasPrefetchedContent(Boolean.TRUE)
            .prefetchedContentRef("tavily:req-103:02_02")
            .prefetchedRawContentLength(1200)
            .skipNetworkVerification(Boolean.FALSE)
            .pageType("OFFICIAL_DOC")
            .sourceUrls(List.of("https://support.airtable.com/docs/automation-guide"))
            .totalScore(0.95)
            .build();

    SearchCandidateFusionDecision decision = planner.plan(config, List.of(needsVerification), 2, 5);

    assertThat(decision.getVerificationCandidates())
            .extracting(SourceCandidate::getUrl)
            .contains("https://support.airtable.com/docs/automation-guide");
    assertThat(decision.getFastLaneCandidates())
            .noneMatch(candidate -> "https://support.airtable.com/docs/automation-guide".equals(candidate.getUrl())
                    && Boolean.TRUE.equals(candidate.getSkipNetworkVerification()));
}
```

- [ ] Step 2：在 `SearchExecutionCoordinatorTest` 新增 `shouldVerifyPrefetchBeforeSelectorWhenSkipNetworkVerificationFalse`

用 mock `CandidateVerifier` 断言 `skipNetworkVerification=false` 的 Tavily prefetch 在 `SELECT_TARGETS` 前已经进入 verifier，且最终选中的 candidate 是 verifier 回填后的 `verified=true / VERIFIED`。

核心断言：

```java
verify(candidateVerifier).verify(eq("Airtable"), eq("DOCS"), argThat(candidates ->
        candidates.stream().anyMatch(candidate ->
                "https://support.airtable.com/docs/automation-guide".equals(candidate.getUrl())
                        && Boolean.FALSE.equals(candidate.getSkipNetworkVerification()))));
assertThat(result.getSelectedTargets())
        .extracting(target -> target.getCandidate().getVerified())
        .contains(Boolean.TRUE);
```

- [ ] Step 3：在 `CollectionTargetSelectorTest` 新增 `shouldRejectPrefetchedCandidateWhenGateDidNotAuthorizeSkipNetworkVerification`

这个测试只覆盖最后防漏：当上游 verification 被跳过、超时或未把验证结果 merge 回来时，selector 不允许未盖章 prefetch 进入正式采集目标。它不是“送去 verifier”的实现路径。

核心断言：

```java
@Test
void shouldRejectPrefetchedCandidateWhenGateDidNotAuthorizeSkipNetworkVerification() {
    SourceCandidate needsVerification = SourceCandidate.builder()
            .url("https://support.airtable.com/docs/automation-guide")
            .title("Airtable docs")
            .sourceType("DOCS")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
            .selectionStage("BOOTSTRAPPED")
            .verified(null)
            .fastLaneUsable(Boolean.TRUE)
            .hasPrefetchedContent(Boolean.TRUE)
            .prefetchedContentRef("tavily:req-103:02_02")
            .skipNetworkVerification(Boolean.FALSE)
            .pageType("OFFICIAL_DOC")
            .sourceUrls(List.of("https://support.airtable.com/docs/automation-guide"))
            .totalScore(0.91)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(List.of(needsVerification), Map.of(), 1);

    assertThat(decision.getSelectedTargets()).isEmpty();
    assertThat(decision.getDiscardedCandidates())
            .extracting(SourceCandidate::getSelectionReason)
            .contains("Tavily prefetch 未完成验证，拒绝进入正式采集目标");
}
```

- [ ] Step 4：在 `CollectionTargetSelectorTest` 新增 `shouldPromoteAuthorizedPrefetchCandidateAsVerifiedSelection`

核心断言：

```java
@Test
void shouldPromoteAuthorizedPrefetchCandidateAsVerifiedSelection() {
    SourceCandidate authorized = SourceCandidate.builder()
            .url("https://open.douyin.com/docs/api")
            .title("开放平台 API 文档")
            .sourceType("DOCS")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
            .selectionStage("BOOTSTRAPPED")
            .verified(null)
            .fastLaneUsable(Boolean.TRUE)
            .hasPrefetchedContent(Boolean.TRUE)
            .prefetchedContentRef("tavily:req-ok:1")
            .skipNetworkVerification(Boolean.TRUE)
            .pageType("OFFICIAL_DOC")
            .sourceUrls(List.of("https://open.douyin.com/docs/api"))
            .totalScore(0.86)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(List.of(authorized), Map.of(), 1);
    SourceCandidate selected = decision.getSelectedTargets().get(0).getCandidate();

    assertThat(selected.getVerified()).isTrue();
    assertThat(selected.getSelectionStage()).isEqualTo("VERIFIED");
    assertThat(selected.getVerificationReason()).isEqualTo("TAVILY_FAST_LANE_GATE_VERIFIED");
    assertThat(selected.getQualitySignals()).contains("TAVILY_VERIFICATION_SKIPPED");
}
```

- [ ] Step 5：修正既有 prefetch selector 测试数据

把所有“预期 selector 直接入选 Tavily prefetch”的测试样例补齐以下字段，避免旧测试继续表达错误契约：

```java
.providerKey("tavily")
.skipNetworkVerification(Boolean.TRUE)
.pageType("OFFICIAL_DOC")
.sourceUrls(List.of("https://open.douyin.com/docs/api"))
```

重点检查这些测试：

```text
shouldSelectUsableTavilyPrefetchCandidateAheadOfHigherScoredVerifiedRootShell
shouldPreferStrongPrefetchedContentOverThinVerifiedShell
```

- [ ] Step 6：在 `CandidateVerifierTest` 新增 `shouldRunNetworkVerificationForPrefetchWhenSkipNetworkVerificationFalse`

核心断言：

```java
@Test
void shouldRunNetworkVerificationForPrefetchWhenSkipNetworkVerificationFalse() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(sourceCollector.collect("https://support.airtable.com/docs/automation-guide", "Airtable", "DOCS"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://support.airtable.com/docs/automation-guide")
                    .title("Airtable automation guide")
                    .content("Airtable documentation API integration guide automation workspace")
                    .snippet("Airtable documentation")
                    .success(true)
                    .build());
    CandidateVerifier verifier = new CandidateVerifier(sourceCollector);

    CandidateVerificationResult result = verifier.verify("Airtable", "DOCS", List.of(
            SourceCandidate.builder()
                    .url("https://support.airtable.com/docs/automation-guide")
                    .sourceType("DOCS")
                    .providerKey("tavily")
                    .fastLaneUsable(Boolean.TRUE)
                    .hasPrefetchedContent(Boolean.TRUE)
                    .prefetchedContentRef("tavily:req-103:02_02")
                    .skipNetworkVerification(Boolean.FALSE)
                    .pageType("OFFICIAL_DOC")
                    .sourceUrls(List.of("https://support.airtable.com/docs/automation-guide"))
                    .build()
    ));

    assertThat(result.getVerifiedCandidateCount()).isEqualTo(1);
    assertThat(result.getUpdatedCandidates().get(0).getVerified()).isTrue();
    assertThat(result.getUpdatedCandidates().get(0).getSelectionStage()).isEqualTo("VERIFIED");
    assertThat(result.getUpdatedCandidates().get(0).getQualitySignals())
            .doesNotContain("TAVILY_VERIFICATION_SKIPPED");
    verify(sourceCollector).collect("https://support.airtable.com/docs/automation-guide", "Airtable", "DOCS");
}
```

- [ ] Step 7：在 `TavilyPrefetchedContentGateTest` 增加阈值边界测试

新增测试锁住“可用但不免验”和“强到可免验”的边界：

```java
assertThat(gated.getFastLaneUsable()).isTrue();
assertThat(gated.getSkipNetworkVerification()).isFalse(); // rawContentLength < 2000
```

以及：

```java
assertThat(gated.getFastLaneUsable()).isTrue();
assertThat(gated.getSkipNetworkVerification()).isTrue(); // rawContentLength >= 2000 且 score 过线
```

运行：

```bash
mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateVerifierTest,SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest,TavilyPrefetchedContentGateTest test
```

预期：新增红灯测试在实现前失败，失败点集中在两个接缝：fusion planner 没有稳定把 `skipNetworkVerification=false` 的 prefetch 送入 verifier，selector 仍会把未验证 prefetch 选成正式目标。

## 7. Task 2：Selector / Verifier 契约修复

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java
```

- [ ] Step 1：在 `SearchCandidateFusionPlanner` 拆分“可预取正文”与“免验证 fast lane”

当前 `SearchCandidateFusionPlanner.isStrongFastLaneCandidate(...)` 只看 `fastLaneUsable + hasPrefetchedContent + qualityTier=STRONG`，会把 `skipNetworkVerification=false` 的强正文也计入 fast lane。改成两个语义：

```java
private boolean hasStrongPrefetchedContent(SourceCandidate candidate) {
    return candidate != null
            && Boolean.TRUE.equals(candidate.getFastLaneUsable())
            && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
            && "STRONG".equalsIgnoreCase(candidate.getQualityTier());
}

private boolean isAuthorizedFastLaneCandidate(SourceCandidate candidate) {
    return hasStrongPrefetchedContent(candidate)
            && Boolean.TRUE.equals(candidate.getSkipNetworkVerification());
}

private boolean requiresPrefetchVerification(SourceCandidate candidate) {
    return candidate != null
            && Boolean.TRUE.equals(candidate.getFastLaneUsable())
            && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
            && !Boolean.TRUE.equals(candidate.getSkipNetworkVerification())
            && !Boolean.TRUE.equals(candidate.getVerified());
}
```

`fastLaneCandidates` 只统计 `isAuthorizedFastLaneCandidate`；`verificationCandidates` 必须包含 `requiresPrefetchVerification` 的候选，并且不能被普通 verificationLimit 过早裁掉。建议用 `LinkedHashSet` 合并：

```java
List<SourceCandidate> verificationCandidates = Stream.concat(
        preselectedCandidates.stream().filter(this::requiresPrefetchVerification),
        preselectedCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getSkipNetworkVerification()))
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getVerified()))
).distinct().limit(Math.max(0, verificationLimit)).toList();
```

如果 `requiresPrefetchVerification` 数量超过 `verificationLimit`，必须优先保留这些 prefetch 候选，避免再次出现“正文可用但没盖章”的接缝。

- [ ] Step 2：在 `SearchExecutionCoordinator` 保证 verifier 结果在 selector 前 merge

现有主链路是：

```text
initialFusionDecision.getVerificationCandidates()
  -> candidateVerifier.verify(...)
  -> mergeCandidateUpdates(...)
  -> appendAttemptedTargets(...)
  -> collectionTargetSelector.selectTargets(...)
```

执行者需要补一段中文注释，并用 Task 1 的 `SearchExecutionCoordinatorTest` 锁住：`skipNetworkVerification=false` 的 prefetch 进入 verifier，selector 消费的是 verifier 更新后的 `verified=true / VERIFIED` candidate。

- [ ] Step 3：在 `CollectionTargetSelector` 拆分 prefetch 可消费与免验证授权

建议新增方法：

```java
private boolean hasConsumablePrefetchedContent(SourceCandidate candidate) {
    return candidate != null
            && Boolean.TRUE.equals(candidate.getFastLaneUsable())
            && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
            && StringUtils.hasText(candidate.getPrefetchedContentRef());
}

private boolean isTavilyPrefetchSkipVerificationAuthorized(SourceCandidate candidate) {
    if (!hasConsumablePrefetchedContent(candidate)) {
        return false;
    }
    if (!"tavily".equalsIgnoreCase(candidate.getProviderKey())) {
        return false;
    }
    if (!Boolean.TRUE.equals(candidate.getSkipNetworkVerification())) {
        return false;
    }
    if (candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()) {
        return false;
    }
    String pageType = normalizeUpper(candidate.getPageType());
    return "ARTICLE".equals(pageType) || "OFFICIAL_DOC".equals(pageType) || "PDF".equals(pageType);
}

private boolean shouldBlockUnverifiedPrefetchAfterVerifierWindow(SourceCandidate candidate) {
    return hasConsumablePrefetchedContent(candidate)
            && !Boolean.TRUE.equals(candidate.getVerified())
            && !isTavilyPrefetchSkipVerificationAuthorized(candidate);
}
```

- [ ] Step 4：在 `resolveEligibility(...)` 中阻断未验证 prefetch 防漏直通

阻断必须放在旧的 `isUsablePrefetchedCandidate(candidate)` 分支之前，也要早于“当前节点未执行结果页验证，允许非拒绝型候选作为降级采集入口”的兜底分支：

```java
if (shouldBlockUnverifiedPrefetchAfterVerifierWindow(candidate)) {
    return new SelectionEligibility(false,
            "Tavily prefetch 未完成验证，拒绝进入正式采集目标",
            "Tavily prefetch 正文可用但未盖验证章，仅保留为审计候选");
}
```

- [ ] Step 5：同步更新排序层级

把 `resolveSelectionTier(...)` 和 `isOfficialPrimaryEvidenceCandidate(...)` 中对 `isUsablePrefetchedCandidate(candidate)` 的调用替换为“已验证 prefetch 或授权免验证 prefetch”：

```java
private boolean isVerifiedOrAuthorizedPrefetchedCandidate(SourceCandidate candidate) {
    return hasConsumablePrefetchedContent(candidate)
            && (Boolean.TRUE.equals(candidate.getVerified())
            || isTavilyPrefetchSkipVerificationAuthorized(candidate));
}
```

这样 `skipNetworkVerification=false` 的候选不会在排序阶段继续挤掉已验证候选。

- [ ] Step 6：授权免验证时由 selector 回填验证章

在 `applySelectionResult(...)` 的 prefetch 分支中，只有 `isTavilyPrefetchSkipVerificationAuthorized(candidate)` 才允许写：

```java
return candidate.toBuilder()
        .verified(Boolean.TRUE)
        .verificationReason("TAVILY_FAST_LANE_GATE_VERIFIED")
        .qualitySignals(appendQualitySignal(candidate.getQualitySignals(), "TAVILY_VERIFICATION_SKIPPED"))
        .selectionStage("VERIFIED")
        .selectionReason("通过 Tavily Prefetched Content Gate，跳过网络重验")
        .selectionSummary("Tavily prefetch 已获 Gate 免验证授权")
        .build();
```

如果 candidate 已经由 `CandidateVerifier` 真实验证为 `verified=true`，继续走现有 verified 分支，不额外写 `TAVILY_VERIFICATION_SKIPPED`。

这一步只能消费 `TavilyPrefetchedContentGate` 已经写入 candidate 的授权字段：`skipNetworkVerification=true`、`sourceUrls` 非空、`pageType` 属于免验证集合。selector 不得重新根据 rawContent 长度、score 或 pageType 再计算一套“是否免验证”，否则会重新出现 Gate / Verifier / Selector 三套口径不一致的接缝。

- [ ] Step 7：保持 `CandidateVerifier.shouldSkipNetworkVerification(...)` 的五条件口径

`CandidateVerifier` 当前五条件是正确方向：`providerKey=tavily`、`fastLaneUsable=true`、`skipNetworkVerification=true`、`sourceUrls` 非空、`pageType ∈ ARTICLE/OFFICIAL_DOC/PDF`。执行者只补测试和必要的中文注释，不放宽条件。

运行：

```bash
mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateVerifierTest,SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest,TavilyPrefetchedContentGateTest test
```

预期：Task 1 红灯测试转绿，旧测试全部通过。

## 8. Task 3：Deadline 上下文值对象与请求链路

**Files:**

```text
Create: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionDeadlineContext.java
Create: backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionDeadlineContextTest.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollectRequest.java
```

- [ ] Step 1：新增 `CollectionDeadlineContext`

核心接口：

```java
public record CollectionDeadlineContext(
        Long hardDeadlineEpochMillis,
        Long drainGraceMillis,
        String degradationReason
) {
    public static CollectionDeadlineContext none() {
        return new CollectionDeadlineContext(Long.MAX_VALUE, 0L, null);
    }

    public static CollectionDeadlineContext hardDeadline(Long epochMillis, Long graceMillis) {
        return new CollectionDeadlineContext(epochMillis, Math.max(0L, graceMillis == null ? 0L : graceMillis),
                "HARD_DEADLINE_REACHED");
    }

    public long remainingMillis() {
        if (hardDeadlineEpochMillis == null || hardDeadlineEpochMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, hardDeadlineEpochMillis - System.currentTimeMillis());
    }

    public boolean isExpired() {
        return remainingMillis() <= 0L;
    }

    public boolean canStartWork(long minStartBudgetMillis) {
        long remaining = remainingMillis();
        return remaining == Long.MAX_VALUE || remaining >= Math.max(0L, minStartBudgetMillis);
    }
}
```

新增中文注释说明：这是 collector 节点级共享 deadline，不是单个 executor 或单次 HTTP 的私有 timeout。
`canStartWork(...)` 与 `isExpired()` 只看 hard deadline；`drainGraceMillis` 只允许 CollectorAgent 外层 `future.get(graceMillis)` 回收已经启动的结果，任何内层循环不得用 grace 启动新 target / 新 batch / 新 Playwright 页面。

- [ ] Step 2：新增 `CollectionDeadlineContextTest`

覆盖：

```text
none() 永不过期
hardDeadline(当前时间 - 1, 0) 立即过期
hardDeadline(当前时间 + 1000, 0) 可以启动 200ms 工作
hardDeadline(当前时间 + 100, 0) 不能启动 500ms 工作
```

- [ ] Step 3：给 `CollectionTaskPackage` 增加字段

```java
Long collectorHardDeadlineEpochMillis;
Long collectorDeadlineGraceMillis;
String collectorDeadlineReason;
```

- [ ] Step 4：给 `SourceCollectRequest` 增加同名字段

```java
Long collectorHardDeadlineEpochMillis;
Long collectorDeadlineGraceMillis;
String collectorDeadlineReason;
```

字段契约表：

| 对象 | 字段 | 语义 | 消费方 |
| --- | --- | --- | --- |
| `CollectionTaskPackage` | `collectorHardDeadlineEpochMillis` | 节点 hard deadline 绝对时间戳 | `CollectionExecutionCoordinator` / `WebPageCollectionExecutor` |
| `CollectionTaskPackage` | `collectorDeadlineGraceMillis` | 外层 drain 交接窗口，仅审计透传 | `CollectorAgent` / audit |
| `CollectionTaskPackage` | `collectorDeadlineReason` | 固定为 `HARD_DEADLINE_REACHED` | `WebPageCollectionExecutor` |
| `SourceCollectRequest` | `collectorHardDeadlineEpochMillis` | 单次 HTTP / Playwright timeout 上界 | `PlaywrightPageCollector` |
| `SourceCollectRequest` | `collectorDeadlineGraceMillis` | 审计字段，不用于启动新工作 | `PlaywrightPageCollector` |
| `CollectionExecutionReport` | `degraded` / `degradationReasons` | collection 聚合是否因 deadline 降级 | `CollectorAgent` / task view |
| `CollectionExecutionStats` | `deadlineReached` / `deadlineSkippedCount` / `hardDeadlineEpochMillis` | deadline 命中和未启动工作计数 | tests / audit |
| `CollectionAuditSummary` | `degradationReasons` | UI 与 replay 轻量摘要 | `TaskNodeViewAssembler` |

- [ ] Step 5：给 `CollectionTaskPackageBuilder` 增加 deadline overload

保留旧 `build(...)` 方法，旧方法委托到新 overload，避免破坏既有测试：

```java
public CollectionTaskPackage build(Long taskId,
                                   String nodeName,
                                   Long planVersionId,
                                   String competitorName,
                                   SourceCandidate candidate,
                                   int priority,
                                   int discoveryDepth,
                                   CollectionDeadlineContext deadlineContext) {
    CollectionDeadlineContext effectiveDeadline = deadlineContext == null
            ? CollectionDeadlineContext.none()
            : deadlineContext;
    // 原有字段保持不变，并把 effectiveDeadline 写入 package。
}
```

运行：

```bash
mvn -pl backend -Dtest=CollectionDeadlineContextTest,CollectionExecutionCoordinatorTest,WebPageCollectionExecutorContextTest test
```

预期：新增测试通过，旧构造路径不报编译错误。

## 9. Task 4：SearchExecutionCoordinator 消费 Collector Deadline

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java
```

- [ ] Step 1：给 `SearchExecutionCoordinator.execute(...)` 增加 deadline overload

保留旧签名：

```java
public SearchExecutionResult execute(CollectorNodeConfig config,
                                     Long taskId,
                                     Map<String, Set<String>> fieldEvidenceFingerprintClaims,
                                     Consumer<SearchExecutionUpdate> progressListener) {
    return execute(config, taskId, fieldEvidenceFingerprintClaims, progressListener, CollectionDeadlineContext.none());
}
```

新增签名：

```java
public SearchExecutionResult execute(CollectorNodeConfig config,
                                     Long taskId,
                                     Map<String, Set<String>> fieldEvidenceFingerprintClaims,
                                     Consumer<SearchExecutionUpdate> progressListener,
                                     CollectionDeadlineContext collectorDeadlineContext) {
    // 将当前 SearchExecutionCoordinator.java:222-806 的主体迁移到此 overload，
    // 旧四参方法只负责传入 CollectionDeadlineContext.none()。
}
```

- [ ] Step 2：合并 search timeout 与 collector hard deadline

在解析 `fieldEvidenceExecutionDeadlineEpochMillis` 时取更早的截止时间：

```java
Long collectorDeadlineEpochMillis = collectorDeadlineContext == null
        ? null
        : collectorDeadlineContext.hardDeadlineEpochMillis();
Long fieldEvidenceExecutionDeadlineEpochMillis = minPositiveDeadline(
        resolveFieldEvidenceExecutionDeadlineEpochMillis(searchTimeoutMillis, fieldEvidenceQueryPlan),
        collectorDeadlineEpochMillis
);
```

`minPositiveDeadline(...)` 必须有中文注释，解释字段证据预算不得越过 collector 节点总预算。

- [ ] Step 3：每个搜索阶段前检查 deadline

在 `TAVILY_BOOTSTRAP_ENRICH`、补源、候选验证、public recovery 前检查：

```java
if (collectorDeadlineContext != null && collectorDeadlineContext.isExpired()) {
    circuitBroken = true;
    degradationReason = "HARD_DEADLINE_REACHED";
    markStepSkipped(executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
            "collector hard deadline reached before Tavily bootstrap");
    markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES",
            "collector hard deadline reached before candidate verification");
    break;
}
```

外部 Tavily / Browser / verification 调用继续保留现有 try-catch fail-open 策略，不新增裸调用。

- [ ] Step 4：`CollectorAgent.executeSearchWithinHardDeadline(...)` 调用新 overload

构造：

```java
CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.hardDeadline(
        collectorHardDeadlineEpochMillis,
        resolveCollectorDeadlineDrainGraceMillis(config, sourceType)
);
```

传入 `searchExecutionCoordinator.execute(...)`。

运行：

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorTest,CollectorAgentTest test
```

预期：搜索阶段到点后内部步骤能主动停止，`SearchExecutionTrace.degradationReason=HARD_DEADLINE_REACHED`。

## 10. Task 5：CollectionExecutionCoordinator 内层循环消费 Deadline

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionStats.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionAuditSummary.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java
```

当前代码锚点：

```text
execute(...) 主体：CollectionExecutionCoordinator.java:113-183
外层 queue while：CollectionExecutionCoordinator.java:146
同深度 batch：CollectionExecutionCoordinator.java:147-154
顺序执行分支：CollectionExecutionCoordinator.java:351-367
并发 CompletableFuture 分支：CollectionExecutionCoordinator.java:370-396
executeQueuedTask(...)：CollectionExecutionCoordinator.java:418-455
```

- [ ] Step 1：给 `CollectionExecutionCoordinator.execute(...)` 增加 deadline overload

保留旧签名，新增：

```java
public CollectionExecutionReport execute(Long taskId,
                                         String nodeName,
                                         Long planVersionId,
                                         String competitorName,
                                         List<SearchCollectionTarget> targets,
                                         CollectionAuditSnapshot checkpoint,
                                         CollectionDeadlineContext deadlineContext) {
    // 将当前 CollectionExecutionCoordinator.java:119-183 的主体迁移到此 overload，
    // 旧六参方法只负责传入 CollectionDeadlineContext.none()。
}
```

旧签名委托到 `CollectionDeadlineContext.none()`。

- [ ] Step 2：在真实 queue 外层循环前检查 deadline

在 `while (!queue.isEmpty())` 顶部：

```java
if (isDeadlineExpired(deadlineContext)) {
    deadlineReached = true;
    break;
}
```

新增中文注释：这里负责堵住“内部递归发现页一层层续命”的根因。

- [ ] Step 3：在 batch 执行前和每个 task 执行前检查 deadline，并区分顺序与并发语义

顺序执行路径：

```java
for (QueuedCollectionTask task : executionBatch) {
    if (!canStartCollectionTask(deadlineContext)) {
        deadlineReachedRef.set(true);
        break;
    }
    resultByTask.put(task, executeQueuedTask(..., deadlineContext));
}
```

并发执行路径：

```java
List<QueuedCollectionTask> startableTasks = executionBatch.stream()
        .filter(task -> canStartCollectionTask(deadlineContext))
        .toList();
if (startableTasks.size() < executionBatch.size()) {
    deadlineReachedRef.set(true);
}
```

并发分支当前会把同深度 `executionBatch` 一次性提交到线程池。改造后只允许提交 `startableTasks`，但不能承诺“整个 execute 最多调用一次 executor”：同一批次在 deadline 尚未过期时可以同时启动多个 task。正确红线是：

```text
1. deadline 已过时，不启动任何新 task。
2. deadline 到点后，不再启动下一深度 child page 或下一批次 target。
3. 已启动的同批并发 task 允许在外层 grace 内交接结果，但 report 必须标 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`。
```

未启动的 package 可以不进入 `results`，但 `CollectionExecutionStats.deadlineSkippedCount` 必须记录。

- [ ] Step 4：把 deadline 写入 `CollectionTaskPackageBuilder.build(...)`

`executeQueuedTask(...)` 调用 builder 时传入 `deadlineContext`，确保 Web executor / SourceCollectRequest 可继续消费。

- [ ] Step 5：给 `CollectionExecutionReport` 与 `CollectionExecutionStats` 增加 deadline 字段

建议字段：

```java
// CollectionExecutionReport
private Boolean degraded;
private List<String> degradationReasons;

// CollectionExecutionStats
private Boolean deadlineReached;
private Integer deadlineSkippedCount;
private Long hardDeadlineEpochMillis;
```

`CollectionAuditSummary` 增加：

```java
private List<String> degradationReasons;
```

- [ ] Step 6：统一聚合状态

新增 `buildReport(results, stats, deadlineReached)` 或等价 helper：

```java
boolean hasReusableResult = stableResults != null && stableResults.stream()
        .anyMatch(result -> result != null
                && result.isSuccess()
                && result.getSourceUrls() != null
                && !result.getSourceUrls().isEmpty());
String status = deadlineReached && hasReusableResult
        ? "SUCCESS_DEGRADED"
        : resolveAggregateStatus(stableResults, successCount, failedCount);
```

只有存在可交接证据时，deadlineReached 才能把节点收口为 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`。如果 `deadlineReached=true` 但没有任何可用结果，必须保留 `readyForQuorum=false`、`sourceUrls=[]`，并按 `FAILED` 或 quorum 不满足语义收口；不得把“没采到东西”包装成降级成功。

- [ ] Step 7：在 `CollectionExecutionCoordinatorTest` 增加内层 deadline 测试

新增 `shouldStopSchedulingSequentialTargetsWhenCollectionDeadlineExpiredInsideQueue`，该测试必须显式设置 `concurrency=1`，此时才可以断言只调用一次 executor：

```java
CollectionExecutionProperties properties = new CollectionExecutionProperties();
properties.setConcurrency(1);
CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
        new CollectionTaskPackageBuilder(),
        new CollectionExecutorRegistry(List.of(executor)),
        new CanonicalUrlResolver(),
        new InternalLinkDiscoveryProperties(),
        properties
);
CollectionDeadlineContext deadline = CollectionDeadlineContext.hardDeadline(System.currentTimeMillis() + 80L, 0L);
List<SearchCollectionTarget> sequentialTargets = List.of(
        target("https://docs.airtable.com/a"),
        target("https://docs.airtable.com/b")
);
when(executor.execute(any())).thenAnswer(invocation -> {
    Thread.sleep(120L);
    CollectionTaskPackage taskPackage = invocation.getArgument(0);
    return CollectionExecutionResult.builder()
            .executorType("WEB_PAGE")
            .success(true)
            .status("SUCCESS")
            .resourceLocator(taskPackage.getResourceLocator())
            .sourceUrls(taskPackage.getSourceUrls())
            .build();
});

CollectionExecutionReport report = coordinator.execute(
        41L, "collect_sources_docs", 9L, "Airtable", sequentialTargets, null, deadline);

assertThat(report.getStatus()).isEqualTo("SUCCESS_DEGRADED");
assertThat(report.getDegradationReasons()).contains("HARD_DEADLINE_REACHED");
assertThat(report.getStats().getDeadlineReached()).isTrue();
verify(executor, times(1)).execute(any());
```

再新增 `shouldNotScheduleDiscoveredChildBatchAfterConcurrentBatchCrossesDeadline`，该测试覆盖真实并发分支：

```java
CollectionExecutionProperties properties = new CollectionExecutionProperties();
properties.setConcurrency(3);
CollectionDeadlineContext deadline = CollectionDeadlineContext.hardDeadline(System.currentTimeMillis() + 80L, 0L);
List<SearchCollectionTarget> concurrentTopLevelTargets = List.of(
        target("https://docs.airtable.com/a"),
        target("https://docs.airtable.com/b"),
        target("https://docs.airtable.com/c")
);
when(executor.execute(any())).thenAnswer(invocation -> {
    Thread.sleep(120L);
    CollectionTaskPackage taskPackage = invocation.getArgument(0);
    return CollectionExecutionResult.builder()
            .executorType("WEB_PAGE")
            .success(true)
            .status("SUCCESS")
            .resourceLocator(taskPackage.getResourceLocator())
            .sourceUrls(taskPackage.getSourceUrls())
            .discoveredCandidates(List.of(SourceCandidate.builder()
                    .url(taskPackage.getResourceLocator() + "/child")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .build()))
            .build();
});

CollectionExecutionReport report = coordinator.execute(
        41L, "collect_sources_docs", 9L, "Airtable", concurrentTopLevelTargets, null, deadline);

assertThat(report.getStatus()).isEqualTo("SUCCESS_DEGRADED");
assertThat(report.getStats().getDeadlineReached()).isTrue();
verify(executor, atMost(concurrentTopLevelTargets.size())).execute(any());
verify(executor, never()).execute(argThat(pkg ->
        readStringAccessor(pkg, "resourceLocator").endsWith("/child")));
```

运行：

```bash
mvn -pl backend -Dtest=CollectionExecutionCoordinatorTest,CollectionDeadlineContextTest test
```

预期：内层 queue 到点后停止继续调度，report / summary 均标降级。

## 11. Task 6：Web Executor / Playwright 抓取前检查 Deadline

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollectRequest.java
Modify: backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorContextTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java
```

- [ ] Step 1：`WebPageCollectionExecutor.buildCollectRequest(...)` 透传 deadline 字段

```java
.collectorHardDeadlineEpochMillis(taskPackage.getCollectorHardDeadlineEpochMillis())
.collectorDeadlineGraceMillis(taskPackage.getCollectorDeadlineGraceMillis())
.collectorDeadlineReason(taskPackage.getCollectorDeadlineReason())
```

- [ ] Step 2：`WebPageCollectionExecutor` 每条外部采集路径前检查 deadline

在 Direct HTML、Jina、Playwright full render、Playwright link supplement 前都检查：

```java
if (isDeadlineExpired(taskPackage)) {
    return buildFailureResult(taskPackage,
            "HARD_DEADLINE_REACHED",
            "collector hard deadline reached before web page collection",
            List.of("HARD_DEADLINE_REACHED"),
            startedAt);
}
```

不要吞掉已有轻量成功结果：`maybeSupplementLinksWithPlaywright(...)` 如果补链接前 deadline 到点，应返回原 `normalizedLightweight`，只追加 `PLAYWRIGHT_LINK_SUPPLEMENT_SKIPPED_BY_DEADLINE`。
为让 HTTP 快路也拿到 deadline，`PlaywrightPageCollector.collectByHttp(String, String, String)` 需要改为 request 版本或增加 request overload：

```java
CollectedPage collectByHttp(SourceCollectRequest request) {
    // 从 request 读取 url / competitorName / sourceType / collectorHardDeadlineEpochMillis。
}
```

- [ ] Step 3：`PlaywrightPageCollector.collect(SourceCollectRequest request)` 入站检查

在 URL 安全校验后、HTTP 快路和 browser 前增加：

```java
if (isCollectorDeadlineExpired(request)) {
    return failed(url, competitorName, sourceType, "collector hard deadline reached before page collection");
}
```

- [ ] Step 4：Playwright 单次调用必须使用剩余 hard deadline 作为显式 timeout 上界

在 `collectByBrowser(...)` 的首轮 browser 打开前、失败后 retry 前、`navigateWithFallback(...)` 前都检查剩余时间。剩余时间不足时返回失败页，`metadata` 或 `errorMessage` 中必须包含 `HARD_DEADLINE_REACHED`，方便 CollectorAgent 和审计层识别。

同时必须把剩余时间压到 Playwright 自身 timeout，而不是只靠线程中断：

```java
private double resolvePlaywrightActionTimeoutMillis(SourceCollectRequest request, long configuredTimeoutMillis) {
    long remaining = resolveRemainingCollectorDeadlineMillis(request);
    if (remaining == Long.MAX_VALUE) {
        return (double) configuredTimeoutMillis;
    }
    return (double) Math.max(250L, Math.min(configuredTimeoutMillis, remaining));
}
```

在以下位置全部使用该 timeout：

```java
page.setDefaultTimeout(resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis()));
page.setDefaultNavigationTimeout(resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis()));
page.navigate(url, new Page.NavigateOptions()
        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
        .setTimeout(resolvePlaywrightActionTimeoutMillis(request, resolveTimeoutMillis())));
page.waitForLoadState(LoadState.LOAD,
        new Page.WaitForLoadStateOptions().setTimeout(resolvePlaywrightActionTimeoutMillis(request, 5000L)));
page.waitForSelector(resolveRenderableSelector(request),
        new Page.WaitForSelectorOptions().setTimeout(resolvePlaywrightActionTimeoutMillis(request, 4000L)));
```

如果剩余时间小于最小 action timeout，直接返回 deadline failure，并关闭 page；不得启动 retry。

- [ ] Step 5：Playwright 内部重试前再次检查

`retryCollectByBrowserLocked(...)` 进入前、`browser.newPage()` 前和 `navigateWithFallback(...)` 前都必须重新检查 deadline。当前一次 Playwright 调用因 timeout 抛出异常且 deadline 已过时，直接返回 deadline failure，不再 `restartBrowserIfCurrent(...)` 后重试。

- [ ] Step 6：HTTP timeout 使用剩余预算上界

`collectByHttp(...)` 当前使用 `collectorProperties.getPageTimeoutSeconds()`。改造时取：

```java
Duration effectiveTimeout = minDuration(protocolTimeout, remainingCollectorDeadlineDuration(request));
```

如果剩余时间小于 1 秒，直接返回 deadline failure，不发起 HTTP。

- [ ] Step 7：新增测试

`WebPageCollectionExecutorContextTest`：

```text
shouldPassCollectorDeadlineToSourceCollectRequest
shouldSkipPlaywrightLinkSupplementWhenDeadlineExpiredAfterLightweightSuccess
```

`PlaywrightPageCollectorTest`：

```text
shouldFailFastWhenCollectorDeadlineAlreadyExpired
shouldNotStartBatchItemAfterDeadlineExpired
shouldApplyCollectorDeadlineToPlaywrightNavigateTimeout
shouldSkipPlaywrightRetryWhenDeadlineExpiredAfterPrimaryTimeout
```

运行：

```bash
mvn -pl backend -Dtest=WebPageCollectionExecutorContextTest,PlaywrightPageCollectorTest test
```

预期：deadline 已过时不启动 Playwright / HTTP，轻量成功结果不会被补链接 deadline 失败覆盖。

## 12. Task 7：CollectorAgent 降级收口语义

**Files:**

```text
Modify: backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java
Modify: backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
```

- [ ] Step 1：`executeCollectionCoordinatorWithinHardDeadline(...)` 调用 coordinator 新 overload

```java
CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.hardDeadline(
        collectorHardDeadlineEpochMillis,
        resolveCollectorDeadlineDrainGraceMillis(config, config.getSourceType())
);
collectionExecutionCoordinator.execute(
        context.getTaskId(),
        context.getCurrentNodeName(),
        context.getPlanVersionId(),
        config.getCompetitorName(),
        targets,
        config.getCollectionAuditCheckpoint(),
        deadlineContext
);
```

- [ ] Step 2：识别 coordinator 内部主动 deadline break

新增 helper：

```java
private boolean isCollectionHardDeadlineReached(CollectionExecutionReport report) {
    return report != null
            && report.getDegradationReasons() != null
            && report.getDegradationReasons().contains(HARD_DEADLINE_REACHED);
}
```

如果 helper 命中，必须执行现有：

```java
markSearchExecutionResultAsHardDeadlineReached(...)
buildCollectorHardDeadlineResult(...)
```

不要让后续正常 `SUCCESS` 分支吞掉降级语义。

- [ ] Step 3：确保 `collectionAudit.summary.status` 与节点状态一致

当 `AgentResult.status=SUCCESS_DEGRADED` 时，输出 JSON 中这些字段必须存在：

```json
{
  "collectionStatus": "SUCCESS_DEGRADED",
  "degradationReasons": ["HARD_DEADLINE_REACHED"],
  "collectionAudit": {
    "status": "SUCCESS_DEGRADED",
    "summary": {
      "status": "SUCCESS_DEGRADED",
      "degradationReasons": ["HARD_DEADLINE_REACHED"]
    }
  }
}
```

- [ ] Step 4：更新 `CollectorAgentTest`

现有 deadline 测试使用 mock：

```java
when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any()))
```

需要增加新 overload 的 mock，并保留旧 overload 测试兼容：

```java
when(collectionCoordinator.execute(any(), any(), any(), any(), any(), any(), any()))
```

新增 `shouldReturnSuccessDegradedWhenCoordinatorStopsInsideDeadlineLoop`，模拟 coordinator 在外层 `future.get(...)` 未 timeout 的情况下返回 `status=SUCCESS_DEGRADED / degradationReasons=HARD_DEADLINE_REACHED`，断言 CollectorAgent 仍返回 `SUCCESS_DEGRADED`。

运行：

```bash
mvn -pl backend -Dtest=CollectorAgentTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest test
```

预期：外层 timeout、grace drain、内层主动 deadline break 三种路径都收口为同一降级语义。

## 13. Task 8：验证矩阵与 Live E2E 复测

**Files:**

```text
Modify: docs/Tavily/task/2026-07-09-20-prefetch-verified-gap-and-deadline-inner-loop-plan.md
```

- [ ] Step 1：跑 prefetch 契约测试

```bash
mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateVerifierTest,SearchCandidateFusionPlannerTest,SearchExecutionCoordinatorTest,TavilyPrefetchedContentGateTest test
```

验收：

```text
SearchCandidateFusionPlanner 会把 skipNetworkVerification=false 的 prefetch 放入 verificationCandidates。
SearchExecutionCoordinator 会在 SELECT_TARGETS 前调用 CandidateVerifier 并 merge 验证结果。
skipNetworkVerification=false 的 prefetch 不再被 selector 直接 SELECTED。
skipNetworkVerification=true 的 prefetch 才能免验证并写 verified=true / VERIFIED。
CandidateVerifier 会对 fastLaneUsable=true 但 skipNetworkVerification=false 的候选发起真实验证。
```

- [ ] Step 2：跑 deadline 分层测试

```bash
mvn -pl backend -Dtest=CollectionDeadlineContextTest,SearchExecutionCoordinatorTest,CollectionExecutionCoordinatorTest,WebPageCollectionExecutorContextTest,PlaywrightPageCollectorTest,CollectorAgentTest test
```

验收：

```text
SearchExecutionCoordinator 不越过 collector deadline 继续执行补源 / 验证。
CollectionExecutionCoordinator 不越过 collector deadline 继续调度 target / child page。
PlaywrightPageCollector 在 deadline 已过时不启动 HTTP / browser。
CollectorAgent 对内层主动 deadline break 返回 SUCCESS_DEGRADED。
```

- [ ] Step 3：跑相关回归测试

```bash
mvn -pl backend -Dtest=StageOneDegradedContractIntegrationTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,ReportDeliverySummaryServiceTest test
```

验收：

```text
阶段1降级契约仍允许 HARD_DEADLINE_REACHED 进入降级可查看链路。
任务视图展示能看见 HARD_DEADLINE_REACHED，而不是普通 SUCCESS。
Report delivery 不因本 task 新字段解析失败。
本 task 的回归测试只允许验证展示/解析兼容，不得修改 QualityReviewAgent / ReportService / NodeExecutionRecoveryPolicy 生产逻辑。
```

- [ ] Step 4：跑 backend 全量测试

```bash
mvn -pl backend test
```

验收：测试通过；若失败，只记录与本 task 相关的失败并继续修复，不清理用户已有改动。

- [ ] Step 5：使用 taskId 102/103 同款 Notion/Airtable 双竞品 payload 复跑 live E2E

复测输出目录命名建议：

```text
tmp/stage1-degraded-live-e2e-prefetch-deadlinefix-YYYYMMDD-HHMMSS
```

验收核对：

```text
1. 原 9 条 Airtable prefetch 证据不再以 verified=None 进入报告。
2. verified=true 的证据必须来自 CandidateVerifier 真实验证或 Gate 授权免验证。
3. evidenceCount 不回落到 2。
4. collect_sources_02_02 在 150s hard deadline 后不再启动新 target / child page；总时长应压回 180s 内（150s hard deadline + 最多 30s drain grace）。
5. collect_sources_02_03 在 120s hard deadline 后不再启动新 target / child page；总时长应压回 150s 内（120s hard deadline + 最多 30s drain grace）。
6. 02_02 / 02_03 若越界，collectionAudit 与节点状态均标 `SUCCESS_DEGRADED + HARD_DEADLINE_REACHED`。
7. initialReview 不再因为 `verified=None` 触发 EVIDENCE_TRACEABILITY BLOCKER。
8. qualityScore 相比 14 有明显抬升；如果没有抬升，必须继续定位 reviewer 评分口径，不得把状态强行改 SUCCESS。
9. 单次 Playwright timeout 不得超过启动该调用时的 collector 剩余 hard deadline；日志中不能出现 hard deadline 后继续启动新 Playwright 页。
10. 若某个节点 deadline 命中且没有任何可用 sourceUrls，必须显示为 quorum 不满足或失败语义，不能作为 SUCCESS_DEGRADED 释放。
```

- [ ] Step 6：把复测结果写回本文件进度记录

追加格式：

```markdown
### 验收记录：YYYY-MM-DD HH:mm
- 测试命令：
- 单测结果：
- E2E 输出目录：
- taskId / reportId：
- verified 分布：
- evidenceCount：
- 02_02 时长与状态：
- 02_03 时长与状态：
- qualityScore：
- 剩余风险：
```

## 14. 执行顺序建议

优先顺序：

```text
Task 1 -> Task 2 -> 单测转绿
Task 3 -> Task 5 -> Task 6 -> Task 7 -> deadline 单测转绿
Task 4 -> search deadline 回归
Task 8 -> 分层回归与 live E2E
```

不要先跑 live E2E 试运气。这个问题已有现场证据，先用红灯测试锁住接缝，改完再用 live E2E 验收。
