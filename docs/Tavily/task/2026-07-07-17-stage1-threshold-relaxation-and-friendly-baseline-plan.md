# Task17 阶段1降门槛与正常竞品基线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不继续追抖音 / 哔哩哔哩开放平台复杂样例的前提下，先降低阶段1采集准入与封版交付门槛，再用一个正常竞品样例稳定产出带 `sourceUrls`、质量分及格的可演示报告。

**Architecture:** 阶段1只动“上游能不能把可用证据放进链路”和“报告能不能稳定交付”的门槛：Tavily 配置先降、selector/ownership 当前宽松行为封板、Reviewer 增加 60 分 MVP 及格线、ReportService 增加降级可交付态。降门槛必须绑定字段 query 配额守卫，确保 `FieldEvidenceQueryExecutionGate` 的每字段配额、节点级 cap、跨节点 dedup 和 provider deadline 仍然生效；`sourceUrls` 红线不下调，不打开 Gate 1 / Gate 2 的多轮自动补采，抖音 / 哔哩哔哩开放平台样例降级为复杂失败案例和压力测试，不再作为阶段1 / 阶段2 before-after 基线。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Tavily Search integration.

---

## 1. 结论先行

这轮顺序是：**先降低门槛，再跑正常竞品基线，最后再做 LLM Orchestrator 架构卖点**。

不要先继续跑抖音 / 哔哩哔哩开放平台 E2E。它们现在混合了开放平台模板错配、官方壳页、第三方 API 站、登录/验证码/中介页等干扰项，会把每一次判断都污染成“也许还差一个修复”。阶段1的目标不是证明复杂案例全绿，而是证明系统已经能稳定完成一个正常竞品分析闭环。

阶段1允许诚实降级：

- 降低 Tavily fast-lane 配置门槛。
- 放宽可追溯第三方长文进入采集链路的准入。
- 质量分 `score >= 60` 作为阶段1 MVP 及格线，低于 80 可以作为降级报告交付。
- `requiresHumanIntervention=true` 不再等同于“没有报告可交付”；阶段1允许把它展示为“需人工复核 / 降级报告”。
- 报告可以标注低置信和缺字段，但必须保留 `sourceUrls`。
- 降门槛前后必须保留字段 query 配额、跨节点 dedup 和 budget exhausted 审计，避免重新回到 Tavily 调用暴涨。

阶段1不允许伪成功：

- 不降低 Reviewer 的 `score >= 80` 语义来假装高质量。
- 不允许低于当前封版线且不满足 55-59 一次性校准条件的报告进入阶段1封版；极低 LLM 原始分或 BLOCKER / 核心 CRITICAL 仍必须阻断。
- 不删除 `sourceUrls` 红线。
- 不把第三方页面授权成官方根域 / sitemap 扩展根。
- 不删除或绕过 `FieldEvidenceQueryExecutionGate`、`fieldEvidenceQueryExecutedCount`、`SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED`、`SKIPPED_CROSS_NODE_DEDUP`、`SKIPPED_BUDGET_EXHAUSTED` 这组预算护栏。
- 不改 `DynamicPlanAppender` Gate 1 / `OrchestrationDecisionService` Gate 2 来放开多轮自动补采，避免无限循环和跨轮 Tavily 预算失控。
- 不把 task66、抖音开放平台、哔哩哔哩开放平台重新提升为毕业基线。

## 2. 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 配置优先降低 Tavily fast-lane 门槛，并确认字段 query 预算护栏仍在 | 45 分钟 | claim 生命周期修复已封板；不跑 E2E |
| Task 2 | 用 characterization test 封板 selector 对“非拒绝型搜索候选”的降级准入 | 45 分钟 | Task 1 可并行；当前 selector 已有相关逻辑，需要测试锁定 |
| Task 3 | 封板 ownership 的“单页证据可接纳、根域扩展仍严格”边界 | 30 分钟 | `CandidateOwnershipPolicyTest` 已存在 |
| Task 4 | 封板缺证策略：自动补证优先，rewrite 缺源仍高风险 | 30 分钟 | `DecisionPolicyServiceTest` 已存在 |
| Task 5 | 增加阶段1 MVP 封版交付线：60 分及格、降级可交付、非阻断 rewrite | 75 分钟 | Task 1-4 单测通过；不打开多轮自动补采 |
| Task 6 | 固定正常竞品友好基线输入，只集中跑一次真实 E2E | 45 分钟 | Task 1-5 单测通过 |
| Task 7 | 记录阶段1验收结果，并把失败样例降级为 future work | 30 分钟 | Task 6 完成 |

## 3. 进度记录

- [x] Task 1：Tavily fast-lane 配置门槛降低，并通过属性绑定与字段 query 预算护栏测试
- [x] Task 2：selector 降级准入行为被 characterization test 封板
- [x] Task 3：ownership 单页证据 / 根域扩展边界被测试封板
- [x] Task 4：DecisionPolicy 缺证补证策略被测试封板
- [x] Task 5：阶段1 MVP 封版交付线被测试封板
- [x] Task 6：正常竞品基线输入固定，已在 9093 串行执行两组友好基线 E2E；本轮未通过阶段1验收
- [x] Task 7：阶段1验收结果已写回本文档；因未产出报告，不更新阶段1收口总文档
- [x] Task 10：Collector 硬截止、`SUCCESS_DEGRADED` 终态和前后端状态展示已补齐并通过单测
- [x] Task 9：采集分支证据充分 quorum 已落地，Extractor 可继承 collector readiness 降级审计
- [x] Task 8：DOCS 真实入口优先与模板 fallback 审计已落地
- [x] Task 11：阶段1字段补采预算与 supplement gate 收敛已落地，非关键 pending 字段不再单独拉起首报补采

执行过程中每完成一个 Task，需要在本节勾选，并在对应 Task 下追加实测命令与结果摘要。

## 4. 当前事实与边界

已经封板的地基：

- claim 生命周期修复已验证：`CollectorAgentFieldEvidenceLoopTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest` 共 14 个相关测试通过。
- `CollectionTargetSelector` 已存在“当前节点未执行结果页验证时，允许非拒绝型候选作为降级采集入口”的逻辑。
- `CandidateOwnershipPolicy` 已区分 `hasCompetitorEvidenceOwnershipSignal` 和 `hasCompetitorOwnershipSignal`。
- `DecisionPolicyService` 已允许 `MISSING_SOURCE + SUPPLEMENT_EVIDENCE` 通过，同时会把缺源 rewrite 升为高风险。
- 当前代码里 `QualityReviewAgent.isDiagnosisPassed(...)` 只看 LLM `passed` 与诊断状态，不把 `score >= 60` 当作阶段1及格线；`ReportService.buildDeliverySummary(...)` 也只把 `qualityPassed && blockerCount == 0 && evidenceGapCount == 0` 视为可交付。Task 5 要补的是“封版交付口径”，不是多轮自动补采能力。
- 字段 query 预算护栏已存在：`FieldEvidenceQueryExecutionGate` 会先按每字段配额收敛，再按节点级 `maxPerNode` fail-safe 裁剪，最后用任务 + 竞品维度的 claim set 做跨节点 dedup；`TavilyFastLaneProvider` 会在 field query deadline 不足时记录 `SKIPPED_BUDGET_EXHAUSTED`，不继续发请求。

本计划只做阶段1收口，不做下面事项：

- 不实现 LLM Orchestrator。那是阶段2，等阶段1正常竞品报告稳定后再做。
- 不做动态开局规划、4.x 动态编排、对话协同、RAG、质量评分口径统一。
- 不打开 `DynamicPlanAppender.shouldCreateDynamicBackflow(...)` 与 `OrchestrationDecisionService.decide(...)` 中的 requiresHumanIntervention 双门禁；当前 `currentDecisionCount` 未从 checkpoint 回灌，贸然放开会带来多轮补采循环风险。
- 不把抖音 / 哔哩哔哩开放平台作为毕业基线。
- 不反复跑 E2E 调参。阶段1只在单测过后集中跑一次友好基线。

## 5. Files

- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchPropertiesTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGateTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Docs: `docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md`

## 6. 阶段1友好基线

默认基线使用：

```json
{
  "taskName": "阶段1友好基线：Notion 与 Airtable 竞品分析",
  "subjectProduct": "Notion",
  "competitorNames": [
    "Airtable"
  ],
  "competitorUrls": [
    "https://www.airtable.com"
  ],
  "analysisDimensions": [
    "产品定位",
    "核心功能",
    "价格策略",
    "目标用户",
    "公开评价"
  ],
  "sourceScope": [
    "官网",
    "产品文档",
    "定价页",
    "公开测评"
  ],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

选择这个基线的原因：

- SaaS / 协作工具公开资料多，Tavily 更容易搜到第三方长文和官网定价。
- 标准竞品分析模板适配，不像开放平台样例天然缺 pricing / weaknesses。
- 面试叙事更清楚：先展示正常业务闭环，再展示复杂失败样例如何被识别为 future work。

风险说明：

- Airtable 官网和定价页可抓性尚未在当前机器上验证。英文 SaaS 站点通常比国内内容平台友好，但仍可能存在反爬、重定向、脚本渲染或地区差异。
- 阶段1不把“必须抓到 Airtable 官网全文”作为唯一通过条件；如果官网不可抓，但 Tavily 能拿到官网 URL、定价页 URL、公开测评和文档类第三方长文，仍可按降级报告验收。
- 若预检显示 Airtable 官网 / 定价页 / 文档页全部不可抓，且 Tavily 第三方来源不足，则只允许切换一次备用友好基线，优先使用 `Linear vs Jira`；需要中文演示时再改为 `语雀 vs 飞书文档`。切换基线后仍只跑一次集中 E2E，不进入“换样例也无限调参”。

验收线：

- 任务能产出报告或可展示的降级报告；“降级报告”必须有真实报告产物、明确缺口说明和可点击来源，不等于“任务没崩”。
- 报告、证据或审计链路中 `sourceUrls >= 5`，且去重后来源域名数 `distinctSourceDomains >= 2`。
- 初始封版线为 `qualityScore >= 60`；60-79 允许标记为“阶段1降级报告 / 需人工复核”，80 及以上才视为优秀报告。
- 若唯一一次友好基线 E2E 实测卡在 55-59，且满足 `sourceUrls / distinctSourceDomains / 预算` 红线、没有 BLOCKER、没有核心证据 CRITICAL，允许一次性把阶段1封版线校准到该次实测整数分（最低不低于 55），并在验收记录中写明原因；校准后不再二次下调。
- 如果 LLM 原始分低于 40、存在 BLOCKER 诊断、或核心证据维度为 CRITICAL，不允许仅凭最终平均分进入封版线。
- 至少有一个来源来自官网 / 文档 / 定价页任一官方或官方相邻路径；如果没有，报告必须显式写明“官方来源不可抓，当前为第三方公开资料降级版”。
- Tavily 预算不反向放大：单个 collector 的 `fieldEvidenceQueryExecutedCount <= 24`；友好基线聚合 `TavilyFastLaneAudit.queriesSent <= 40`，超过则视为预算风险，不进入阶段2。
- 允许质量分不满 80，但不能伪造高分通过；低于当前封版线且不满足 55-59 一次性校准条件时，只记录 root cause，不进入封版。
- 失败时只记录一次 root cause，不进入重复 E2E 调参循环。

## 7. Task 1: Tavily fast-lane 配置优先降门槛

**Files:**

- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchPropertiesTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`

- [x] **Step 1: 写默认阈值测试**

在 `TavilySearchPropertiesTest` 增加：

```java
@Test
void shouldDefaultToStageOneFriendlyThresholds() {
    TavilySearchProperties properties = new TavilySearchProperties();

    assertThat(properties.getMinRawContentChars()).isEqualTo(300);
    assertThat(properties.getMinTavilyScore()).isEqualTo(0.35D);
}
```

- [x] **Step 2: 跑测试确认当前默认值仍是旧门槛**

Run:

```powershell
mvn -pl backend "-Dtest=TavilySearchPropertiesTest" test
```

Expected before implementation:

```text
expected: 300
 but was: 500
```

或：

```text
expected: 0.35
 but was: 0.45
```

- [x] **Step 3: 修改 `TavilySearchProperties` 默认值**

把 `TavilySearchProperties` 中默认值改为：

```java
/**
 * 进入 Fast Lane 质量门禁前要求的最小 raw_content 长度。
 * 阶段1收口优先保证正常竞品能拿到可追溯正文，因此默认门槛从 500 降到 300；
 * 质量真实性仍由 pageType、qualityTier、sourceUrls 和 Reviewer 继续兜底。
 */
private int minRawContentChars = 300;

/**
 * Tavily 原始得分下限。
 * 阶段1只降低搜索结果可进入 fast-lane 的初筛门槛，不降低最终报告评分红线。
 */
private double minTavilyScore = 0.35D;
```

- [x] **Step 4: 修改 `application.yml` 运行期配置**

保持 `tavily-search` 段落里 `enabled`、`endpoint`、`api-key`、`search-depth`、`include-raw-content`、`max-results`、`timeout-seconds`、`max-retries` 的现有语义不变，只修改下面两行：

```yaml
min-raw-content-chars: 300
min-tavily-score: 0.35
```

注意：不要为了本 Task 顺手调整 Tavily key 的来源；正式收口时优先用环境变量 `TAVILY_API_KEY` 是后续交付卫生问题，不属于阶段1降门槛的必要改动。

- [x] **Step 5: 跑属性绑定测试**

Run:

```powershell
mvn -pl backend "-Dtest=TavilySearchPropertiesTest" test
```

Expected after implementation:

```text
Tests run: 4, Failures: 0, Errors: 0
```

- [x] **Step 6: 跑字段 query 预算护栏测试，确认降门槛没有取消配额**

Run:

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

必须核对的语义：

- `FieldEvidenceQueryExecutionGateTest.shouldApplyPerFieldQuotaBeforeGlobalFailSafe` 仍证明节点级 cap 会触发 `SKIPPED_NODE_QUERY_CAP_EXHAUSTED`。
- `SearchExecutionCoordinatorFieldEvidenceBudgetTest.shouldApplyFieldExecutionGateBeforeBuildingBudget` 仍证明 `71 planned -> 21 executable/executed -> 50 skipped`，不能因为 fast-lane 阈值降低而把全量 planned query 交给 Tavily。
- `SearchExecutionCoordinatorFieldEvidenceBudgetTest.shouldExposeProviderFieldQueryAuditInTraceSummaryAndSupplementStepMessage` 仍证明 provider 层会记录 `SKIPPED_BUDGET_EXHAUSTED`。
- `SearchExecutionCoordinatorFieldEvidenceBudgetTest.shouldAtomicallyDeduplicateFieldEvidenceQueriesAcrossConcurrentCollectors` 仍证明跨 collector fingerprint 不重复执行。

如果这组测试失败，停止本 Task；不要继续跑友好基线 E2E，因为这说明降门槛会重新打开 Tavily 调用放大风险。

- [ ] **Step 7: 提交本 Task（按用户要求不执行提交）**

```powershell
git add backend/src/main/resources/application.yml backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchPropertiesTest.java
git commit -m "chore: relax tavily stage one thresholds"
```

### Task 1 实测记录（2026-07-07）

- 红灯验证：
  - 命令：`mvn -pl backend "-Dtest=TavilySearchPropertiesTest" test`
  - 结果：失败，`shouldDefaultToStageOneFriendlyThresholds` 命中旧默认值，断言输出 `expected: 300 but was: 500`，符合计划预期。
- 代码修改：
  - `TavilySearchProperties` 默认值改为 `minRawContentChars=300`、`minTavilyScore=0.35D`，并补充阶段1降门槛但不放松最终质量红线的中文注释。
  - `application.yml` 中 `tavily-search.min-raw-content-chars`、`tavily-search.min-tavily-score` 同步改为 `300`、`0.35`。
- 绿灯验证：
  - 命令：`mvn -pl backend "-Dtest=TavilySearchPropertiesTest" test`
  - 结果：`Tests run: 4, Failures: 0, Errors: 0`。
- 预算护栏回归：
  - 命令：`mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest" test`
  - 结果：`Tests run: 12, Failures: 0, Errors: 0`。
  - 结论：字段 query 配额、节点级 cap、跨 collector dedup、provider `SKIPPED_BUDGET_EXHAUSTED` 审计仍然成立，未因 fast-lane 阈值下调而放大 Tavily 调用。
- 额外说明：
  - Maven 仍打印 `settings.xml` 的 `mirrors` 结构 warning。
  - 预算护栏测试结束后控制台出现多条 XML `DOCTYPE` fatal 日志，但 Maven 退出码为 `0`，当前未表现为测试失败，本 Task 先按噪声日志记录，不额外扩散处理。

## 8. Task 2: selector 降级准入封板

**Files:**

- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- Modify only if missing: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`

说明：本 Task 是 characterization test，不是新功能红绿测试。当前 `CollectionTargetSelector.resolveEligibility(...)` 已经存在相关宽松逻辑，所以 Step 1 的测试大概率第一次就 PASS；它的价值是把阶段1允许的降级准入边界锁住，防止后续为了修复杂样例又把正常搜索候选误杀。

- [x] **Step 1: 写 characterization test，确认非拒绝型 Tavily 搜索候选可作为降级采集入口**

在 `CollectionTargetSelectorTest` 增加：

```java
@Test
void shouldSelectSearchCandidateWithoutNetworkVerificationWhenNotRejected() {
    CollectorNodeConfig config = CollectorNodeConfig.builder()
            .competitorName("Airtable")
            .competitorUrls(List.of("https://www.airtable.com"))
            .sourceType("REVIEW")
            .build();
    SourceCandidate article = SourceCandidate.builder()
            .url("https://www.g2.com/compare/notion-vs-airtable")
            .title("Notion vs Airtable comparison")
            .domain("www.g2.com")
            .sourceType("REVIEW")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_FAST_LANE")
            .selectionStage("CANDIDATE")
            .verified(Boolean.FALSE)
            .sourceUrls(List.of("https://www.g2.com/compare/notion-vs-airtable"))
            .totalScore(0.74)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(
            config,
            List.of(article),
            Map.of(),
            1
    );

    assertEquals(1, decision.getSelectedTargets().size());
    assertEquals(article.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
    assertTrue(decision.getUpdatedCandidates().stream()
            .anyMatch(candidate -> article.getUrl().equals(candidate.getUrl())
                    && "SELECTED".equals(candidate.getSelectionStage())));
}
```

- [x] **Step 2: 写测试，确认中介页和工具页仍不能被降级放行**

在 `CollectionTargetSelectorTest` 增加：

```java
@Test
void shouldStillRejectMediatorPageWhenVerificationIsSkipped() {
    CollectorNodeConfig config = CollectorNodeConfig.builder()
            .competitorName("Airtable")
            .competitorUrls(List.of("https://www.airtable.com"))
            .sourceType("OFFICIAL")
            .build();
    SourceCandidate mediator = SourceCandidate.builder()
            .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
            .title("官网认证")
            .domain("aiqicha.baidu.com")
            .reason("官网认证是百度对网站在强关联关系触发词下展示官方标识的增值服务认证")
            .sourceType("OFFICIAL")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_FAST_LANE")
            .selectionStage("CANDIDATE")
            .verified(Boolean.FALSE)
            .totalScore(0.96)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(
            config,
            List.of(mediator),
            Map.of(),
            1
    );

    assertEquals(0, decision.getSelectedTargets().size());
    assertTrue(decision.getDiscardedCandidates().stream()
            .anyMatch(candidate -> mediator.getUrl().equals(candidate.getUrl())));
}
```

- [x] **Step 3: 跑 selector 测试**

Run:

```powershell
mvn -pl backend "-Dtest=CollectionTargetSelectorTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

如果 Step 1 第一次就通过，属于预期结果，说明当前行为已存在且被测试封板。

- [x] **Step 4: 只在测试失败时补齐 selector 降级准入逻辑**

如果 Step 3 失败，确认 `CollectionTargetSelector.resolveEligibility(...)` 在显式候选兜底之后包含以下逻辑：

```java
/**
 * 当 coordinator 显式关闭结果页验证、跳过 verifyCandidates，或直接复用 checkpoint 候选时，
 * 这些候选不会进入 attemptedTargets，因此也拿不到 collectedPage。
 * 只要它们没有被明确判定为中介页、工具页或已丢弃候选，就允许作为降级入口继续进入采集，
 * 否则 planned / supplement / checkpoint 三类正常候选会在 SELECT_TARGETS 阶段被全部清空。
 */
if (!Boolean.TRUE.equals(candidate.getVerified())
        && attemptedTarget == null
        && !"DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())
        && !"SELECTED".equalsIgnoreCase(candidate.getSelectionStage())) {
    return new SelectionEligibility(true,
            "当前节点未执行结果页验证，允许非拒绝型候选作为降级采集入口",
            "当前节点未执行结果页验证，按候选排序结果进入降级采集");
}
```

- [ ] **Step 5: 提交本 Task（按用户要求不执行提交）**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java
git commit -m "test: lock stage one collection selector fallback"
```

### Task 2 实测记录（2026-07-07）

- 封板测试新增：
  - `shouldSelectSearchCandidateWithoutNetworkVerificationWhenNotRejected`
  - `shouldStillRejectMediatorPageWhenVerificationIsSkipped`
- 首轮验证：
  - 命令：`mvn -pl backend "-Dtest=CollectionTargetSelectorTest" test`
  - 结果：失败，新增的非拒绝型候选用例显示候选已被选中，但 `updatedCandidates` 快照未同步回填 `SELECTED`。
- 最小修正：
  - 未改动 `resolveEligibility(...)` 的降级准入语义，因为该逻辑本身已存在。
  - 在 `CollectionTargetSelector` 中新增 `selectedSnapshotUrls`，让回填阶段优先以“真实入选快照 URL”判定谁应标记为 `SELECTED`。
  - 这样既保留 canonical URL 去重，又修复单个 `www/utm` 变体候选被选中后状态未回填的问题。
- 回归验证：
  - 命令：`mvn -pl backend "-Dtest=CollectionTargetSelectorTest" test`
  - 结果：`Tests run: 22, Failures: 0, Errors: 0`。
- 结论：
  - 非拒绝型 Tavily 搜索候选在跳过结果页验证时可作为阶段1降级采集入口继续进入链路。
  - 中介页仍会被 selector 拒绝，不会因为阶段1降门槛被误放行成正式证据。

## 9. Task 3: ownership 单页证据 / 根域扩展边界封板

**Files:**

- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java`
- Modify only if missing: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java`

- [x] **Step 1: 确认第三方单页证据可凭正文品牌信号接纳**

`CandidateOwnershipPolicyTest` 中应包含这个测试；若不存在，补入：

```java
@Test
void shouldAllowSearchDiscoveredSinglePageEvidenceByCollectedBrandTextButKeepRootExpansionStrict() {
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://partner.example.com/research/airtable-automation")
            .domain("partner.example.com")
            .title("Airtable automation overview")
            .sourceType("REVIEW")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_FAST_LANE")
            .selectionStage("HTTP")
            .build();
    SourceCollector.CollectedPage page = SourceCollector.CollectedPage.builder()
            .url("https://partner.example.com/research/airtable-automation")
            .title("Airtable automation overview")
            .content("Airtable automation, database, forms, integrations, and workflow examples.")
            .success(true)
            .build();

    assertFalse(policy.hasCompetitorDomainOwnershipSignalForCandidate(
            "Airtable",
            List.of("https://www.airtable.com"),
            candidate
    ));
    assertFalse(policy.isTrustedSearchRoot("Airtable", List.of("https://www.airtable.com"), candidate));
    assertTrue(policy.hasCompetitorEvidenceOwnershipSignal(
            "Airtable",
            List.of("https://www.airtable.com"),
            candidate,
            page
    ));
}
```

- [x] **Step 2: 确认 search-discovered 第三方页面不能被授权为可扩展根域**

`CandidateOwnershipPolicyTest` 中应包含这个测试；若不存在，补入：

```java
@Test
void shouldNotPromoteVerifiedSearchDiscoveredThirdPartyEvidenceToExpandableRoot() {
    SourceCandidate verifiedThirdPartyEvidence = SourceCandidate.builder()
            .url("https://partner.example.com/research/airtable-automation")
            .domain("partner.example.com")
            .title("Airtable automation overview")
            .sourceType("REVIEW")
            .providerKey("tavily")
            .discoveryMethod("TAVILY_FAST_LANE")
            .selectionStage("VERIFIED")
            .verified(true)
            .build();

    assertFalse(policy.isTrustedSearchRoot(
            "Airtable",
            List.of("https://www.airtable.com"),
            verifiedThirdPartyEvidence
    ));
}
```

- [x] **Step 3: 跑 ownership 测试**

Run:

```powershell
mvn -pl backend "-Dtest=CandidateOwnershipPolicyTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 4: 只在测试失败时修正 policy（本轮无需执行）**

根域扩展仍必须使用严格 ownership：

```java
if (candidate != null && isSearchDiscovered(candidate)) {
    // 运行期搜索候选如果域名不归属于竞品，只因标题/正文提到品牌而放行，
    // 会把第三方页面误当成官方根域继续扩展。
    return false;
}
```

单页证据接纳使用 evidence-level ownership：

```java
String text = candidateAndPageText(candidate, page);
for (String alias : aliases) {
    String normalizedAlias = compact(alias);
    if (!StringUtils.hasText(normalizedAlias)) {
        continue;
    }
    if (text.contains(normalizedAlias)) {
        return true;
    }
}
return false;
```

- [ ] **Step 5: 提交本 Task（按用户要求不执行提交）**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java
git commit -m "test: lock evidence-level ownership boundary"
```

### Task 3 实测记录（2026-07-07）

- 现状确认：
  - `CandidateOwnershipPolicyTest` 已存在语义等价覆盖：
    - `shouldAllowSearchDiscoveredSinglePageEvidenceByCollectedBrandTextButKeepRootExpansionStrict`
    - `shouldNotPromoteVerifiedSearchDiscoveredThirdPartyEvidenceToExpandableRoot`
  - 虽然样例品牌使用的是 `Douyin`，但验证的是通用 ownership 边界，不依赖具体竞品。
- 验证命令：
  - `mvn -pl backend "-Dtest=CandidateOwnershipPolicyTest" test`
- 结果：
  - `Tests run: 12, Failures: 0, Errors: 0`
- 结论：
  - 第三方单页证据仍可凭正文品牌信号进入 evidence-level ownership。
  - search-discovered 第三方页面仍不能被提升为可扩展根域。
  - 本 Task 无需修改生产代码或测试代码。

## 10. Task 4: DecisionPolicy 缺证补证策略封板

**Files:**

- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java`
- Modify only if missing: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`

- [x] **Step 1: 确认缺 source 但动作是补证时允许自动分支**

`DecisionPolicyServiceTest` 中应包含这个测试；若不存在，补入：

```java
@Test
void shouldAllowSupplementEvidenceWhenSourceGapIsExplicit() {
    OrchestrationDecision decision = OrchestrationDecision.builder()
            .decisionId("od-001")
            .decisionType("APPEND_DYNAMIC_BRANCH")
            .actionType("SUPPLEMENT_EVIDENCE")
            .targetNode("collect_sources")
            .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
            .priority("HIGH")
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .suggestedQueries(List.of("Notion AI pricing official"))
            .build()
            .normalized();

    DecisionPolicyResult result = service.evaluate(
            decision,
            DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build(),
            0,
            "RUNNING",
            "SUCCESS");

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getNormalizedAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
    assertThat(result.getPolicyRuleRefs()).contains(
            "allowedDecisionTypes",
            "requireSourceUrlsOrEvidenceGap",
            "maxSearchQueriesPerDecision");
}
```

- [x] **Step 2: 确认缺 source 时 rewrite-only 仍是高风险**

`DecisionPolicyServiceTest` 中应包含这个测试；若不存在，补入：

```java
@Test
void shouldElevateRewriteOnlyDecisionWhenSourceIsMissing() {
    OrchestrationDecision decision = OrchestrationDecision.builder()
            .decisionId("od-005")
            .decisionType("REWRITE_ONLY")
            .actionType("REWRITE_SECTION")
            .targetNode("rewrite_report")
            .priority("MEDIUM")
            .sourceUrls(List.of())
            .evidenceState(EvidenceState.MISSING_SOURCE)
            .build()
            .normalized();

    DecisionPolicyResult result = service.evaluate(
            decision,
            DecisionPolicyRuleSet.builder().build(),
            0,
            "RUNNING",
            "SUCCESS");

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.isRequiresConfirmation()).isTrue();
    assertThat(result.getRiskLevel()).isEqualTo("HIGH");
    assertThat(result.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
}
```

- [x] **Step 3: 跑 policy 测试**

Run:

```powershell
mvn -pl backend "-Dtest=DecisionPolicyServiceTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 4: 只在测试失败时修正 policy（本轮无需执行）**

`DecisionPolicyService.findMatchedRiskRules(...)` 必须保留：

```java
if ("missing_source_requires_supplement".equals(rule.getRuleId())
        && decision.getEvidenceState() == EvidenceState.MISSING_SOURCE
        && !"SUPPLEMENT_EVIDENCE".equals(decision.getActionType())) {
    matched.add(rule);
}
```

`resolveNormalizedAction(...)` 必须保留：

```java
return switch (decision.getActionType()) {
    case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
    case "DOMAIN_HINT_DISCOVERY" -> "MANUAL_ONLY";
    case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
    case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
    case "NO_ACTION" -> "NO_ACTION";
    case "MANUAL_REVIEW" -> "MANUAL_ONLY";
    default -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()) ? "MANUAL_ONLY" : "NO_ACTION";
};
```

- [ ] **Step 5: 提交本 Task（按用户要求不执行提交）**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java
git commit -m "test: lock missing-source supplement policy"
```

### Task 4 实测记录（2026-07-07）

- 现状确认：
  - `DecisionPolicyServiceTest` 已存在计划要求的两条核心护栏：
    - `shouldAllowSupplementEvidenceWhenSourceGapIsExplicit`
    - `shouldElevateRewriteOnlyDecisionWhenSourceIsMissing`
- 验证命令：
  - `mvn -pl backend "-Dtest=DecisionPolicyServiceTest" test`
- 结果：
  - `Tests run: 9, Failures: 0, Errors: 0`
- 结论：
  - `MISSING_SOURCE + SUPPLEMENT_EVIDENCE` 仍允许自动补证分支。
  - `MISSING_SOURCE + REWRITE_ONLY` 仍会被抬升为高风险并要求确认。
  - 本 Task 无需修改生产代码或测试代码。

## 11. Task 5: 阶段1 MVP 封版交付线

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`

说明：本 Task 的目标是“稳定封版”，不是“深度自动补采”。因此只调整 60 分及格、一次静态 rewrite、降级交付摘要三处，不修改 `DynamicPlanAppender.shouldCreateDynamicBackflow(...)` 和 `OrchestrationDecisionService.decide(...)` 里的 requiresHumanIntervention 双门禁。

- [ ] **Step 1: 写 Reviewer 及格线测试**

在 `QualityReviewAgentTest` 增加测试，锁定阶段1口径：

测试 1：`shouldPassStageOneMvpWhenScoreIsAtLeastSixtyWithoutBlockingDiagnosis`

- 构造有 `sourceUrls`、只有少量 `coverage_gap`、LLM `passed=false`、LLM 原始 `score>=40` 的报告。
- 让最终诊断分达到 `score>=60`。
- 断言 Reviewer 输出 `passed=true`、`requiresHumanIntervention=false`。

测试 2：`shouldNotPassStageOneMvpWhenLlmScoreIsTooLowEvenIfDimensionAverageIsPulledUp`

- 构造 LLM 原始 `score<40`、维度信息较完整、最终平均分可能被拉高的样例。
- 断言 Reviewer 输出 `passed=false`，避免低质量正文被平均分拉进封版线。

测试 3：`shouldNotPassStageOneMvpWhenBlockingDiagnosisExists`

- 构造 `BLOCKER` 或核心证据维度 `CRITICAL`。
- 断言即使 `score>=60`，仍 `passed=false` 且 `requiresHumanIntervention=true`。

- [ ] **Step 2: 跑 Reviewer 测试，确认当前行为不满足封版线**

Run:

```powershell
mvn -pl backend "-Dtest=QualityReviewAgentTest" test
```

Expected before implementation:

```text
shouldPassStageOneMvpWhenScoreIsAtLeastSixtyWithoutBlockingDiagnosis 失败，passed 仍为 false
```

- [ ] **Step 3: 修改 Reviewer 及格线**

在 `QualityReviewAgent` 中把第 206 行附近改成：

```java
boolean passed = isDiagnosisPassed(llmPassed, dimensions, diagnoses)
        || isStageOneMvpPassingScore(llmScore, score, dimensions, diagnoses);
```

新增 helper：

```java
/**
 * 阶段1封版使用“及格可展示”口径：60 分以上允许作为降级报告进入交付链路。
 * 这不是优秀报告标准；BLOCKER、核心证据维度 CRITICAL、LLM 原始分过低时仍必须阻断。
 */
private boolean isStageOneMvpPassingScore(int llmScore,
                                          int score,
                                          List<QualityDimension> dimensions,
                                          List<QualityDiagnosis> diagnoses) {
    boolean hasBlocker = diagnoses.stream()
            .anyMatch(diagnosis -> "BLOCKER".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")));
    boolean hasCriticalCoreDimension = dimensions.stream()
            .map(QualityDimension::normalized)
            .anyMatch(dimension -> isCoreDimension(dimension.getCode())
                    && "CRITICAL".equalsIgnoreCase(safeValue(dimension.getStatus(), "")));
    return score >= STAGE_ONE_MVP_SCORE_FLOOR
            && normalizeScore(llmScore) >= STAGE_ONE_MIN_LLM_SCORE_FLOOR
            && !hasBlocker
            && !hasCriticalCoreDimension;
}
```

在类字段区增加常量，避免后续一次性校准时到处改魔法数字：

```java
private static final int STAGE_ONE_MVP_SCORE_FLOOR = 60;
private static final int STAGE_ONE_MIN_LLM_SCORE_FLOOR = 40;
```

注意：这里故意不把普通 `coverage_gap` 作为绝对阻断，因为阶段1允许少量缺口被标成降级报告；但 `sourceUrls` 红线和交付摘要仍要兜底。若 Task 6 首轮 E2E 触发 55-59 校准，只允许把 `STAGE_ONE_MVP_SCORE_FLOOR` 改到该次实测整数分，且最低不低于 55。

- [ ] **Step 4: 写 DagExecutor rewrite 门禁测试**

在 `DagExecutorTest` 增加或调整测试：

测试 1：`shouldAllowRewriteWhenInitialReviewRequiresHumanInterventionButHasNoBlockingDiagnosis`

- 初审输出 `passed=false`、`requiresHumanIntervention=true`、`autoRewriteAllowed=false`。
- `diagnoses` 仅为 `missing_evidence`，且没有 `level=BLOCKER`。
- 断言 `rewrite_report` 不再被 `SKIPPED`，允许静态 DAG 给 Writer 一次改写机会。

测试 2：`shouldStillSkipRewriteWhenInitialReviewHasBlockingDiagnosis`

- 初审输出 `passed=false`、`requiresHumanIntervention=true`，`diagnoses` 含 `level=BLOCKER`。
- 断言 `rewrite_report` 仍被 `SKIPPED`，避免结构性坏报告继续包装。

- [ ] **Step 5: 修改 DagExecutor 的 review_failed 判断**

在 `DagExecutor.shouldExecuteNode(...)` 的 `review_failed` 分支中，把“requiresHumanIntervention=true 直接跳过”改成“只有 BLOCKER 诊断才跳过”：

```java
case "review_failed" -> {
    JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
    boolean manualResumeApproved = isManualResumeApproved(node.getNodeConfig());
    yield reviewOutput != null
            && !reviewOutput.path("passed").asBoolean(true)
            && (manualResumeApproved || !hasBlockingReviewDiagnosis(reviewOutput));
}
```

同步更新 `buildConditionalSkipReason(...)`：

```java
if (hasBlockingReviewDiagnosis(reviewOutput) && !isManualResumeApproved(node.getNodeConfig())) {
    return "跳过修订：初审存在阻塞级诊断，需先人工补证据、调整搜索范围或重跑采集链路";
}
```

新增 helper：

```java
/**
 * 初审只有出现明确 BLOCKER 时才阻断静态 rewrite。
 * 阶段1封版允许非阻塞证据缺口先进入一次改写，最终报告再以降级状态交付。
 */
private boolean hasBlockingReviewDiagnosis(JsonNode reviewOutput) {
    if (reviewOutput == null || !reviewOutput.has("diagnoses") || !reviewOutput.get("diagnoses").isArray()) {
        return false;
    }
    for (JsonNode diagnosis : reviewOutput.get("diagnoses")) {
        if ("BLOCKER".equalsIgnoreCase(diagnosis.path("level").asText(""))) {
            return true;
        }
    }
    return false;
}
```

- [x] **Step 6: 写 ReportService 降级可交付测试**

在 `ReportServiceTest` 增加：

测试 1：`shouldMarkStageOneMvpReportAsDegradedReadyWhenScoreIsPassingWithLimitedEvidenceGaps`

- 构造 `report.qualityPassed=false`、`qualityScore=65`、`blockerCount=0`、`evidenceGapCount<=3`。
- 断言 `deliverySummary.readyForDelivery=true`。
- 断言 `deliveryStatus=DEGRADED_READY`。
- 断言 `summary` 明确包含“降级报告”或“需人工复核”。

测试 2：`shouldNotMarkDegradedReadyWhenScoreIsBelowSixtyOrBlockerExists`

- 分别覆盖 `qualityScore=59`、`blockerCount>0`。
- 断言 `readyForDelivery=false`。

- [x] **Step 7: 修改 ReportService 交付摘要**

在 `ReportService.buildDeliverySummary(...)` 中加入降级可交付态：

```java
boolean degradedDelivery = !report.isQualityPassed()
        && report.getQualityScore() >= STAGE_ONE_MVP_SCORE_FLOOR
        && blockerCount == 0
        && evidenceGapCount <= 3;
boolean readyForDelivery = (report.isQualityPassed() && blockerCount == 0 && evidenceGapCount == 0)
        || degradedDelivery;
String deliveryStatus = readyForDelivery
        ? degradedDelivery ? "DEGRADED_READY" : "READY"
        : blockerCount > 0 ? "BLOCKED" : evidenceGapCount > 0 ? "NEEDS_EVIDENCE" : "REVIEW_REQUIRED";
String summary = readyForDelivery
        ? degradedDelivery
        ? "当前报告达到阶段1封版及格线，可作为降级报告交付；建议人工复核后再正式使用。"
        : "当前报告已满足交付条件，可进入正式导出。"
        : "当前报告暂不可交付，存在 %d 个阻塞问题和 %d 个证据缺口。".formatted(blockerCount, evidenceGapCount);
```

在 `ReportService` 类字段区同样增加：

```java
private static final int STAGE_ONE_MVP_SCORE_FLOOR = 60;
```

注意：`DEGRADED_READY` 是交付状态，不是质量高分；前端如果暂未识别该状态，也会因为 `readyForDelivery=true` 显示可交付，后续前端演示页再单独美化。若 Task 6 首轮 E2E 触发 55-59 校准，`ReportService` 与 `QualityReviewAgent` 的 `STAGE_ONE_MVP_SCORE_FLOOR` 必须同步调整，并补充验收记录说明。

- [x] **Step 8: 跑封版线单测**

Run:

```powershell
mvn -pl backend "-Dtest=QualityReviewAgentTest,DagExecutorTest,ReportServiceTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

### Task 5 实测记录（2026-07-07）

- `mvn -pl backend "-Dtest=DagExecutorTest" test`
  - 首次红灯：`shouldAllowRewriteWhenInitialReviewRequiresHumanInterventionButHasNoBlockingDiagnosis` 仍被旧的 `review_failed` 门禁拦成 `STOPPED`
  - 修改后验证通过：允许非 `BLOCKER` 的初审失败先执行一次静态 `rewrite_report`
- `mvn -pl backend "-Dtest=ReportServiceTest" test`
  - 首次红灯：`shouldMarkStageOneMvpReportAsDegradedReadyWhenScoreIsPassingWithLimitedEvidenceGaps` 断言 `readyForDelivery=true` 失败
  - 修改后验证通过：`qualityScore >= 60`、`blockerCount == 0`、`evidenceGapCount <= 3` 时返回 `DEGRADED_READY`
- `mvn -pl backend "-Dtest=QualityReviewAgentTest,DagExecutorTest,ReportServiceTest" test`
  - 结果：`Tests run: 75, Failures: 0, Errors: 0`
- 代码调整摘要：
  - `QualityReviewAgent` 增加阶段1 MVP 及格线，允许 `score >= 60` 且无 `BLOCKER` / 核心 `CRITICAL` 的报告通过阶段1封版口径
  - `DagExecutor` 改为只在初审存在 `BLOCKER` 诊断时阻断 rewrite，并补充阶段1最小 DAG 的收口语义
  - `ReportService` 增加 `STAGE_ONE_MVP_SCORE_FLOOR = 60` 与 `DEGRADED_READY` 交付状态，摘要明确提示“降级报告 / 需人工复核”

- [ ] **Step 9: 提交本 Task（按用户要求不执行提交）**

```powershell
git add backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java
git commit -m "feat: add stage one mvp delivery gate"
```

## 12. Task 6: 集中验证正常竞品基线

**Files:**

- Runtime verification only: no production code changes unless a targeted failure is found.
- Docs: update this plan's progress section with the exact task id and outcome.

- [x] **Step 1: 跑阶段1单测包**

Run:

```powershell
mvn -pl backend "-Dtest=TavilySearchPropertiesTest,TavilyPrefetchedContentGateTest,TavilyFastLaneAcceptanceTest,FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,DecisionPolicyServiceTest,QualityReviewAgentTest,DagExecutorTest,ReportServiceTest" test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [x] **Step 2: 低成本预检 Airtable 官网 / 定价页 / 文档页可抓性**

Run:

```powershell
$baselineUrls = @(
  "https://www.airtable.com",
  "https://www.airtable.com/pricing",
  "https://support.airtable.com/docs"
)
$baselineUrls | ForEach-Object {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $_ -MaximumRedirection 5 -TimeoutSec 15
    [PSCustomObject]@{
      Url = $_
      Status = $response.StatusCode
      Length = if ($response.Content) { $response.Content.Length } else { 0 }
      Error = ""
    }
  } catch {
    [PSCustomObject]@{
      Url = $_
      Status = "FAILED"
      Length = 0
      Error = $_.Exception.Message
    }
  }
} | Format-Table -AutoSize
```

Expected:

```text
至少 1 个 URL 返回 2xx/3xx，且 Length > 1000
```

如果 3 个 URL 全部失败，或者全部返回极短壳页，停止当前 Airtable 基线，不跑 E2E。只允许切换一次备用友好基线：

```json
{
  "taskName": "阶段1友好基线：Linear 与 Jira 竞品分析",
  "subjectProduct": "Linear",
  "competitorNames": [
    "Jira"
  ],
  "competitorUrls": [
    "https://www.atlassian.com/software/jira"
  ],
  "analysisDimensions": [
    "产品定位",
    "核心功能",
    "价格策略",
    "目标用户",
    "公开评价"
  ],
  "sourceScope": [
    "官网",
    "产品文档",
    "定价页",
    "公开测评"
  ],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

- [x] **Step 3: 启动后端**

Run:

```powershell
mvn -pl backend spring-boot:run
```

Expected:

```text
Tomcat started on port 9093
```

- [x] **Step 4: 创建友好基线任务**

Run:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:9093/api/task/create -ContentType "application/json" -Body '{
  "taskName": "阶段1友好基线：Notion 与 Airtable 竞品分析",
  "subjectProduct": "Notion",
  "competitorNames": ["Airtable"],
  "competitorUrls": ["https://www.airtable.com"],
  "analysisDimensions": ["产品定位", "核心功能", "价格策略", "目标用户", "公开评价"],
  "sourceScope": ["官网", "产品文档", "定价页", "公开测评"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}'
```

Expected:

```text
返回体中包含 data.id
```

- [x] **Step 5: 执行任务**

把 Step 4 返回的任务 id 写入 `$taskId`，然后执行：

```powershell
$taskId = 0
Invoke-RestMethod -Method Post -Uri "http://localhost:9093/api/task/$taskId/execute"
```

Expected:

```text
返回成功响应；后端日志开始出现 Collector / Extractor / Analyzer / Writer / Reviewer 阶段进度
```

- [x] **Step 6: 只检查一次结果，不进入循环调参**

验收时记录下面指标：

```markdown
### Task 6 实测记录

- 日期：
- 任务 id：
- 最终状态：
- qualityScore：
- deliveryStatus：
- readyForDelivery：
- sourceUrls 数量：
- distinctSourceDomains 数量：
- 是否包含官网 / 文档 / 定价页任一官方或官方相邻来源：
- TavilyFastLaneAudit.queriesSent：
- 单 collector 最大 fieldEvidenceQueryExecutedCount：
- fieldEvidenceQuerySkipReasons：
- 是否产出报告：
- 若失败，唯一 root cause：
```

通过标准：

- 有报告或可展示的降级报告；降级报告必须有报告正文、缺口说明和可点击来源。
- `qualityScore >= 当前封版线`，且报告交付摘要为 `READY` 或 `DEGRADED_READY`；当前封版线初始为 60，只有首轮 E2E 卡在 55-59 且无 BLOCKER / 核心 CRITICAL 时才允许一次性校准。
- `sourceUrls >= 5`。
- `distinctSourceDomains >= 2`。
- 至少有一个来源来自官网 / 文档 / 定价页任一官方或官方相邻路径；如果没有，报告必须显式写明“官方来源不可抓，当前为第三方公开资料降级版”。
- 单 collector `fieldEvidenceQueryExecutedCount <= 24`。
- 友好基线聚合 `TavilyFastLaneAudit.queriesSent <= 40`。如果审计视图只能按节点查看，则记录各节点 `queriesSent` 并确保没有单节点异常放大。
- 失败 root cause 不是 claim 生命周期自我饥饿。

失败处理：

- 如果失败来自外部 API 超时、网络、Tavily 空结果，记录为运行环境问题，不继续深挖。
- 如果失败来自 `sourceUrls` 丢失，回到 Task 2 / Task 3 的单测补一个最小复现。
- 如果失败来自 Tavily 调用数异常放大，回到 Task 1 的预算护栏，不继续降内容质量门槛。
- 如果失败来自 `qualityScore < 当前封版线`，先判断是否满足 55-59 一次性校准条件；满足则同步调整 `QualityReviewAgent` / `ReportService` 的封版线常量并记录理由，不再追加第二轮 E2E。若低于 55 或存在 BLOCKER / 核心 CRITICAL，记录为阶段1封版未达标，不继续降低质量线。

## 13. Task 7: 阶段1收口记录

**Files:**

- Modify: `docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md`
- Optional modify: `docs/specs/2026-06-30-two-axis-closure-direction.md`

- [x] **Step 1: 把 Task 6 结果写入本文档**

在本节下方追加：

```markdown
## 14. 阶段1验收结果

- 验收日期：
- 友好基线任务：
- 是否产出报告：
- qualityScore：
- 当前封版线：
- 是否触发 55-59 一次性校准：
- 校准理由：
- deliveryStatus：
- readyForDelivery：
- sourceUrls 数量：
- distinctSourceDomains 数量：
- TavilyFastLaneAudit.queriesSent：
- 单 collector 最大 fieldEvidenceQueryExecutedCount：
- 是否触发预算风险：
- 降级说明：
- 不继续深挖的失败样例：
  - 抖音开放平台：复杂失败案例 / 压力测试
  - 哔哩哔哩开放平台：复杂失败案例 / 压力测试
```

- [x] **Step 2: 更新收口总文档**

本轮阶段1友好基线未通过验收，因此不向 `docs/specs/2026-06-30-two-axis-closure-direction.md` 追加“阶段1已收口”结论。

如果阶段1通过，在 `docs/specs/2026-06-30-two-axis-closure-direction.md` 的阶段1下追加一句：

```markdown
- 2026-07-07：阶段1友好基线已按 Task17 收口，后续进入阶段2：`OrchestrationDecisionService.decide()` LLM 化；抖音 / 哔哩哔哩开放平台继续保留为复杂失败案例，不作为毕业基线。
```

- [ ] **Step 3: 提交文档**

```powershell
git add docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md docs/specs/2026-06-30-two-axis-closure-direction.md
git commit -m "docs: record stage one friendly baseline closure"
```

## 14. 阶段1验收结果（2026-07-08 双友好基线实测）

- 验收日期：2026-07-08
- 运行目录：`tmp/task17-two-friendly-baselines-20260708-100706`
- 端口约束：仅使用 `9093`，未新开测试端口；测试结束后已关闭 Maven / Spring Boot / Playwright driver / Chromium 子进程，`9093` 已释放
- 阶段1单测包：
  - 命令：`mvn -pl backend "-Dtest=TavilySearchPropertiesTest,TavilyPrefetchedContentGateTest,TavilyFastLaneAcceptanceTest,FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,DecisionPolicyServiceTest,QualityReviewAgentTest,DagExecutorTest,ReportServiceTest" test`
  - 结果：`Tests run: 144, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
- URL 预检：
  - `Notion vs Airtable`：`https://www.airtable.com`、`https://www.airtable.com/pricing`、`https://support.airtable.com/docs` 均返回 `200`，内容长度均大于 `1000`
  - `Linear vs Jira`：Jira 官网与定价页返回 `200`，Atlassian 支持文档 URL 预检失败；仍作为第二组友好基线执行，用于验证 DOCS 长尾风险
- 友好基线任务 1：`Notion vs Airtable`
  - 任务 id：`96`
  - 最终状态：`STOPPED`
  - 是否产出报告：否
  - qualityScore：无，未进入质检阶段
  - 当前封版线：`60`
  - 是否触发 55-59 一次性校准：否
  - deliveryStatus：无，未进入报告交付阶段
  - readyForDelivery：否
  - 成功采集节点：`collect_sources_01_01`（OFFICIAL）、`collect_sources_01_03`（PRICING）
  - 停止后跳过节点：`collect_sources_01_02`（DOCS）、`collect_sources_01_04`（REVIEW）以及后续 Extractor / Analyzer / Writer / Reviewer
  - replay 去重 `sourceUrls` 数量：`17`
  - replay 去重来源域名数：`6`
  - 是否包含官方或官方相邻来源：是，包含 `airtable.com` / `www.airtable.com` / `support.airtable.com`
  - TavilyFastLaneAudit.queriesSent：未形成最终聚合指标；任务在采集阶段停止，节点快照已保存
  - 单 collector 最大 fieldEvidenceQueryExecutedCount：未形成最终聚合指标；任务在采集阶段停止，节点快照已保存
  - 是否触发预算风险：未观察到可判定的最终预算风险；但因未形成完整审计，不进入阶段2
  - 唯一 root cause：DOCS / REVIEW 采集长尾，日志中可见 Airtable 支持页 Playwright 超时与页面回退；本轮未进入抽取和报告链路
- 友好基线任务 2：`Linear vs Jira`
  - 任务 id：`97`
  - 最终状态：`STOPPED`
  - 是否产出报告：否
  - qualityScore：无，未进入质检阶段
  - 当前封版线：`60`
  - 是否触发 55-59 一次性校准：否
  - deliveryStatus：无，未进入报告交付阶段
  - readyForDelivery：否
  - 成功采集节点：`collect_sources_01_01`（OFFICIAL）、`collect_sources_01_03`（PRICING）、`collect_sources_01_04`（REVIEW）
  - 停止后跳过节点：`collect_sources_01_02`（DOCS）以及后续 Extractor / Analyzer / Writer / Reviewer
  - replay 去重 `sourceUrls` 数量：`33`
  - replay 去重来源域名数：`14`
  - 是否包含官方或官方相邻来源：是，包含 `atlassian.com` / `www.atlassian.com` / `confluence.atlassian.com`
  - TavilyFastLaneAudit.queriesSent：未形成最终聚合指标；任务在采集阶段停止，节点快照已保存
  - 单 collector 最大 fieldEvidenceQueryExecutedCount：未形成最终聚合指标；任务在采集阶段停止，节点快照已保存
  - 是否触发预算风险：未观察到可判定的最终预算风险；但因未形成完整审计，不进入阶段2
  - 唯一 root cause：DOCS 采集长尾；官网、定价页、公开测评均已完成，但未等到 DOCS 节点收口，因此未进入抽取和报告链路
- 阶段1验收结论：
  - 本轮两组友好基线均未产出报告或降级报告，阶段1未通过验收
  - `sourceUrls` 红线在已完成采集节点中成立，但报告链路未闭环，不能进入阶段2
  - 当前最接近可用的后续样例是 `Linear vs Jira`，因为 OFFICIAL / PRICING / REVIEW 已完成，仅 DOCS 长尾阻塞
  - 不继续深挖的失败样例：
    - 抖音开放平台：复杂失败案例 / 压力测试
    - 哔哩哔哩开放平台：复杂失败案例 / 压力测试

## 15. 阶段2入口说明

阶段1通过后才开始阶段2：架构轴一，`OrchestrationDecisionService.decide()` LLM 化。

阶段2目标不是“更会采集”，而是让系统真正具备可讲的 AI 驱动协作：

- 输入：任务目标、当前缺口、已有证据、可选动作。
- 输出：结构化 `OrchestrationDecision`、可读 reason、`DecisionTrace` 可视化。
- 护栏：`DecisionPolicyService` 不动，继续守 `sourceUrls`、动作白名单、风险升级。

简历叙事顺序：

1. 多 Agent 静态协作骨架：Collector / Extractor / Analyzer / Writer / Reviewer。
2. 可追溯证据链：全链路 `sourceUrls`，缺证自动补采。
3. 工程治理：claim 生命周期、跨节点去重、Tavily fast-lane、失败可审计。
4. AI 驱动协作：Orchestrator 从 if-else 升级为 LLM 决策，确定性 policy 兜底。
5. 收尾取舍：复杂开放平台样例作为 future work，MVP 以正常竞品闭环交付。

## 16. 最终验证命令

阶段1代码与文档完成后，至少执行：

```powershell
mvn -pl backend "-Dtest=TavilySearchPropertiesTest,TavilyPrefetchedContentGateTest,TavilyFastLaneAcceptanceTest,FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,DecisionPolicyServiceTest,QualityReviewAgentTest,DagExecutorTest,ReportServiceTest" test
```

可选执行 claim 生命周期回归：

```powershell
mvn -pl backend "-Dtest=CollectorAgentFieldEvidenceLoopTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest" test
```

文档和空白检查：

```powershell
git diff --check -- docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md backend/src/main/resources/application.yml backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchPropertiesTest.java backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentTest.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java
```

## 17. 阶段1失败后的根因修复计划：DOCS 选源与证据充分推进

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this section task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让友好竞品基线在已有足够证据时能够进入抽取、分析、撰写和质检链路，同时修正 DOCS 选源、采集收口、超时交接、字段补采预算四类根因。

**Architecture:** 采集分支从“全量成功门槛”改为“证据充分门槛”；DOCS 节点从“模板 URL 猜测优先”改为“Tavily / 搜索发现且可验证的真实文档入口优先”；长尾采集从“等到所有分支自然结束”改为“硬截止 + 部分证据可交接 + 可审计降级”。这些能力必须在阶段1闭环，不允许再整体后移到阶段2。

**Tech Stack:** Spring Boot / Java、现有 DAG 工作流、Tavily 搜索链路、现有 JUnit 测试套件、9093 E2E 验证约束。

### 17.0 执行顺序与任务依赖

Task 编号保留历史追加顺序，但实现顺序必须按依赖推进：

1. **先做 Task 10：Collector 硬截止与部分证据交接。** 先让 DOCS / REVIEW 这类长尾 collector 能进入终态，否则 Task 9 的 quorum 没有稳定触发点。
2. **再做 Task 9：证据充分 quorum。** quorum 只在所有 collector 都到达终态后评估，终态包括 `SUCCESS`、`SUCCESS_DEGRADED`、`FAILED`、`SKIPPED`、`COMPENSATED`。
3. **再做 Task 8 与 Task 11。** DOCS 选源质量和字段补采预算可以并行推进，但不能替代 Task 10 / Task 9 的收口能力。
4. **最后做 Task 12。** 先让一个友好样本闭环，再跑第二个样本验证泛化。

硬依赖说明：

- [ ] 如果 Task 10 没有让 RUNNING collector 按 hard deadline 进入终态，Task 9 不得宣称完成。
- [ ] 如果 Task 9 在 collector 仍为 `RUNNING` / `READY` / `DISPATCHED` / `WAITING_RETRY` 时就放行下游，视为错误实现。
- [ ] 如果 Task 8 或 Task 11 单独让某个样本过了，但 Task 10 / Task 9 未落地，不能算阶段1根因修复完成。

### 17.1 根因边界与反症状修复门禁

本轮失败不是单点阈值过严，而是四个根因叠加：

| 根因 | 当前表现 | 必须修复的能力 | 不接受的症状修复 |
| --- | --- | --- | --- |
| DOCS 选源偏模板 URL | 系统会反复拼 `/docs`、`/documentation`、`/help`、`/guide`，真实支持站或知识库入口没有稳定压过模板 URL | Tavily / 搜索发现的 verified DOCS URL 成为一等候选，模板 URL 只做低信任 fallback | 只把某几个竞品的 DOCS URL 写死 |
| DAG 依赖是全量成功门槛 | `extract_schema` 依赖所有 collector，任一长尾 DOCS / REVIEW 未收口就不进入下游 | 增加证据充分 quorum，允许保留未完成分支审计后继续下游 | 只把 DOCS 节点设为 optional，缺少证据充分判定 |
| 超时只变成降级状态 | search timeout 不等于节点硬截止，已有 evidence 不能及时交接 | collector 硬截止时输出 `TaskNodeStatus.SUCCESS_DEGRADED` / 可交接快照，保留 `sourceUrls` | 只调大 timeout 或轮询次数 |
| 字段补采扩大长尾 | pending field evidence 会让 collector 继续补采，阶段1首报被深挖字段拖死 | 字段证据补采按关键字段、来源缺口、预算共同触发 | 只降低质量线或删除字段覆盖检查 |

硬门禁：

- [ ] 不允许只通过调大 `searchTimeoutMillis`、后端请求超时、轮询次数来宣称修复。
- [ ] 不允许只跳过 DOCS 节点，必须保留 DOCS 的真实入口发现、降级审计和 `sourceUrls`。
- [ ] 不允许只降低质量分或报告封版线，采集到抽取的闭环必须真正走通。
- [ ] 不允许把“证据充分推进”“DOCS 真实入口优先”“部分证据交接”继续后移到阶段2；阶段2只能接 LLM 化编排，不再替阶段1补采集根因。
- [ ] 不允许在 Task 10 未完成时宣称 Task 9 quorum 完成；quorum 的前置条件是 collector 已进入终态。
- [ ] 只有 DOCS 选源、证据 quorum、硬截止交接、字段补采预算、E2E 记录五项全部落地，才算阶段1根因修复完成。

### 17.2 DOCS URL 原则说明

DOCS 节点不要求必须使用模板 URL。模板 URL 只是启发式兜底，不能压过 Tavily / 搜索发现的真实文档入口。

新的优先级必须是：

1. 已由 Tavily / 搜索结果发现，且标题、摘要、路径或页面类型可判定为 docs / documentation / help center / support / knowledge base 的 URL。
2. 同品牌官方或官方相邻域名下的文档入口，例如 `support.airtable.com/docs`、`support.atlassian.com/...`、`docs.vendor.com`、`help.vendor.com`。
3. 官网页面中明确链接出来的文档、帮助中心、开发者文档或知识库入口。
4. 最后才是模板拼接候选，例如 `/docs`、`/documentation`、`/help`、`/guide`；这类候选必须带 `templateFallback=true` 或等价审计标记。

如果 Tavily / 搜索发现、官网外链、官方相邻域都没有找到 DOCS URL，允许使用模板 URL 作为唯一 DOCS 候选，但必须强制标记：

```json
{
  "templateFallback": true,
  "fallbackReason": "no_discovered_docs_candidate"
}
```

验收口径：

- [x] 当 Tavily / 搜索发现的 DOCS 候选与模板 URL 同时存在时，真实发现候选必须排在模板候选前面。
- [x] 当模板 URL 被选中时，审计信息必须能解释“没有更高可信 DOCS 候选”。
- [x] 当 Tavily / 搜索没有发现 DOCS URL 时，模板 URL 可以被选中，但必须带 `fallbackReason=no_discovered_docs_candidate`。
- [ ] DOCS 候选即使抓取失败，也要保留候选来源、失败原因和原始 URL，不能让报告链路失去可追溯性。

### Task 8: DOCS 真实入口优先，模板 URL 降级为 fallback

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [x] **Step 1: 写 DOCS 排名失败用例**

在 `SourceCandidateRankerTest` 增加用例：同一品牌同时存在 `https://www.airtable.com/docs` 模板候选与 `https://support.airtable.com/docs` 搜索发现候选时，`support.airtable.com/docs` 必须排在前面。

验收断言：

```java
assertThat(ranked.get(0).url()).isEqualTo("https://support.airtable.com/docs");
assertThat(ranked.get(0).sourceType()).isEqualTo("DOCS");
assertThat(ranked.get(0).sourceUrls()).contains("https://support.airtable.com/docs");
```

- [x] **Step 2: 给模板候选加低信任审计标记**

在 `HeuristicSourceDiscoveryService` 生成 `/docs`、`/documentation`、`/help`、`/guide` 时，补充模板 fallback 标记。业务逻辑必须加中文注释，说明这类 URL 只是兜底猜测，不代表真实文档入口。

建议审计字段：

```json
{
  "templateFallback": true,
  "discoveryMethod": "HEURISTIC_TEMPLATE",
  "fallbackReason": "no_discovered_docs_candidate 或 no_verified_docs_candidate",
  "sourceUrls": ["原始官网或触发该模板的候选 URL"]
}
```

- [x] **Step 3: 提升 Tavily / 搜索发现 DOCS 候选权重**

在 `SourceCandidateRanker` 中对满足以下任一条件的 DOCS 候选加权：

- URL host 包含 `docs.`、`help.`、`support.`、`developer.`、`developers.`；
- path 或标题包含 `docs`、`documentation`、`help-center`、`support`、`knowledge-base`、`guide`；
- 候选来自 Tavily / 搜索结果，而不是模板生成；
- 候选域名通过官方域或官方相邻域校验。

模板候选只在没有 verified DOCS 候选时进入前 N 个采集目标。

- [x] **Step 4: 防止 direct planner 覆盖真实发现结果**

在 `SourceFamilyDirectDiscoveryPlanner` 中调整 DOCS direct family 策略：direct planner 可以补候选，但不能把已有搜索发现候选挤出。若必须补模板 URL，审计中写明明确原因：

```text
fallbackReason=no_discovered_docs_candidate  // Tavily / 搜索、官网外链、官方相邻域均没有 DOCS 候选
fallbackReason=no_verified_docs_candidate    // 有 DOCS 候选但未通过官方域、路径或内容可信校验
```

- [x] **Step 5: 覆盖 Tavily 搜不到 DOCS 的 fallback 用例**

在 `SourceFamilyDirectDiscoveryPlannerTest` 或 `CollectionTargetSelectorTest` 增加用例：Tavily / 搜索结果没有任何 DOCS 候选，只有官网根域可以拼模板 URL。断言模板 URL 可以进入 DOCS 目标，但必须带 fallback 审计：

```java
assertThat(selectedDocsTarget.url()).isEqualTo("https://www.vendor.example/docs");
assertThat(selectedDocsTarget.audit().templateFallback()).isTrue();
assertThat(selectedDocsTarget.audit().fallbackReason()).isEqualTo("no_discovered_docs_candidate");
```

- [x] **Step 6: 运行 DOCS 选源单测**

```powershell
mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,SourceFamilyDirectDiscoveryPlannerTest,SourceCandidateRankerTest,CollectionTargetSelectorTest" test
```

Expected:

```text
BUILD SUCCESS
```

### Task 8 实测记录（2026-07-08）

- 红灯验证 1：
  - 命令：`mvn -pl backend "-Dtest=SourceCandidateRankerTest#shouldPreferSearchDiscoveredDocsOverTemplateFallbackCandidate" test`
  - 结果：失败，`expected: <https://support.airtable.com/docs> but was: <https://www.airtable.com/docs>`，确认模板 `/docs` 会压过搜索发现 DOCS。
- 修复后 focused 验证 1：
  - 命令：`mvn -pl backend "-Dtest=SourceCandidateRankerTest#shouldPreferSearchDiscoveredDocsOverTemplateFallbackCandidate" test`
  - 结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 红灯验证 2：
  - 命令：`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest#shouldMarkOfficialTemplateCandidatesAsLowTrustFallbacks,HeuristicSourceDiscoveryServiceTest#shouldPreferSearchDiscoveredDocsAndKeepTemplateFallbackAudit" test`
  - 结果：失败，direct planner 模板候选 `templateFallback` 为空；service 合并后的模板候选缺少 `no_verified_docs_candidate` 审计。
- 修复后 focused 验证 2：
  - 命令：`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest#shouldMarkOfficialTemplateCandidatesAsLowTrustFallbacks,HeuristicSourceDiscoveryServiceTest#shouldPreferSearchDiscoveredDocsAndKeepTemplateFallbackAudit" test`
  - 结果：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- fallback 正向用例：
  - 命令：`mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest#shouldKeepTemplateFallbackCandidateWhenNoSearchDiscoveredDocsExists" test`
  - 结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- Task 8 组合验证：
  - 命令：`mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,SourceFamilyDirectDiscoveryPlannerTest,SourceCandidateRankerTest,CollectionTargetSelectorTest" test`
  - 结果：`Tests run: 46, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 代码修改：
  - `SourceCandidate` 新增 `templateFallback` / `fallbackReason` 审计字段。
  - `SourceCandidateRanker` 对搜索发现且官方相邻的 DOCS 入口增加质量信号，并对 `FAMILY_TEMPLATE` / `FAMILY_SUBDOMAIN_TEMPLATE` / `HEURISTIC_TEMPLATE` / `SEARCH_ROOT_TEMPLATE` 做模板 fallback 降权。
  - `HeuristicSourceDiscoveryService` 将路径拼接候选标记为 `HEURISTIC_TEMPLATE`，无搜索候选时保留 `no_discovered_docs_candidate`，存在搜索 DOCS 时模板候选改写为 `no_verified_docs_candidate`。
  - `SourceFamilyDirectDiscoveryPlanner` 为 official family 模板候选补充低信任 fallback 审计；`CollectionTargetSelector` 同步识别 `HEURISTIC_TEMPLATE` 为显式候选。

### Task 9: 采集分支从全量成功门槛改为证据充分 quorum

**Depends on:** Task 10 必须先让 collector 按 hard deadline 进入终态。Task 9 不负责把 RUNNING 节点强行终止，只负责在 collector 终态集合上判断“证据是否足够进入抽取”。

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Create or modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorRuntimeDependencyTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/CollectorEvidenceReadinessPolicyTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProviderTest.java`

- [x] **Step 1: 写 DAG quorum 失败用例**

构造 OFFICIAL、PRICING、REVIEW 成功，DOCS 已被 Task 10 推进到 `SUCCESS_DEGRADED` / `FAILED` / `SKIPPED` 任一终态的任务。断言 `extract_schema` 在证据充分时可以进入 `RUNNING` / `SUCCESS`，同时 DOCS 的降级或失败状态被写入审计。

同时增加一个反向用例：DOCS 仍为 `RUNNING` 时，quorum 不评估、不放行下游，返回 `WAITING_COLLECTOR_TERMINAL_STATUS` 或等价 reason。

最小 quorum 建议：

```text
requiredFamilies:
- OFFICIAL
- PRICING
oneOf:
- DOCS
- REVIEW
minimumSourceUrls: 5
minimumDistinctDomains: 2
```

OFFICIAL 失败时的降级 quorum：

```text
requiredFamilies:
- PRICING
oneOf:
- DOCS
- REVIEW
minimumSourceUrls: 5
minimumDistinctDomains: 2
requiredAuditFlag:
- OFFICIAL_FAILED_DEGRADED_QUORUM
```

降级 quorum 只用于阶段1友好基线继续产出降级报告，不能把 OFFICIAL 缺失伪装成正常成功。Writer / Reviewer 必须能从审计中看到 `OFFICIAL_FAILED_DEGRADED_QUORUM`，并在报告缺口说明里写明官网采集失败。

- [x] **Step 2: 增加 CollectorEvidenceReadinessPolicy**

该策略只负责判断“现有 collector 输出是否足够进入抽取”，不要混入报告质量判断。核心输入：

- 已成功或可交接的 collector family；
- 去重后的 `sourceUrls` 数量；
- 去重后的 source domain 数量；
- 官方或官方相邻来源是否存在；
- 所有 collector 是否都已进入终态；
- 降级、失败、跳过 collector 的 family、状态和失败原因。

核心输出：

```java
public record CollectorEvidenceReadiness(
        boolean ready,
        boolean degraded,
        String reason,
        List<String> satisfiedFamilies,
        List<String> missingFamilies,
        List<String> auditFlags,
        List<String> sourceUrls) {
}
```

- [x] **Step 3: 在 DAG 层引入证据充分依赖**

`ExecutionPlanDefinitionBuilder` 不再让 `extract_schema` 只依赖“所有 collector 节点成功”。`DagExecutor` 在 collector 依赖全部到达终态后调用 `CollectorEvidenceReadinessPolicy`。若 `ready=true`，允许下游启动，并把缺口写入节点上下文。

如果任一 collector 仍为 `PENDING`、`READY`、`DISPATCHED`、`RUNNING`、`WAITING_RETRY`、`WAITING_INTERVENTION`、`PAUSED`，quorum 必须返回未就绪，reason 为 `WAITING_COLLECTOR_TERMINAL_STATUS` 或等价值。这里依赖 Task 10 的 hard deadline 把长尾 DOCS / REVIEW 推入终态，不能在 Task 9 里绕过 RUNNING 节点。

必须保留中文注释说明：这里不是忽略失败，而是把“下游能否开始”从“所有采集节点完成”改成“证据是否足够交付首版报告”。

- [x] **Step 4: 让 Extractor 能消费部分 collector 输出**

`SchemaExtractorAgent` 读取 collector 输出时，不能因为某个 collector 缺失就丢弃其他 collector 的 `sourceUrls`。缺失分支必须进入 `evidenceGaps` 或等价字段，供 Analyzer / Writer 写入降级说明。

- [x] **Step 5: 运行 DAG 与 extractor 单测**

```powershell
mvn -pl backend "-Dtest=DagExecutorTest,DagExecutorRuntimeDependencyTest,CollectorEvidenceReadinessPolicyTest,SchemaExtractorAgentTest,SchemaExtractorAgentCoverageContractTest" test
```

Expected:

```text
BUILD SUCCESS
```

### Task 9 实测记录（2026-07-08）

- 代码修改：
  - 新增 `CollectorEvidenceReadiness` 与 `CollectorEvidenceReadinessPolicy`，按 `OFFICIAL + PRICING + (DOCS or REVIEW)`、`sourceUrls >= 5`、`distinctSourceDomains >= 2` 判断阶段1首报 quorum。
  - `DagExecutor` 在多 family collector 依赖全部进入终态后评估 readiness；`SUCCESS_DEGRADED` 视为可交接终态，`RUNNING` / `WAITING_RETRY` / `PAUSED` 等非终态继续返回 `WAITING_COLLECTOR_TERMINAL_STATUS`，不绕过长尾节点。
  - quorum 审计写入共享上下文 `collector_evidence_readiness`；Provider 将其投影为 `auditRefs.collectorEvidenceReadiness` 和 `COLLECTOR_FAMILY_MISSING_*` / `COLLECTOR_QUORUM_DEGRADED` 等 `issueFlags`。
  - `SchemaExtractorAgent` 会把 Provider 输入中的 readiness issueFlags 合并到最终输出和 draft，供 Analyzer / Writer 继承降级说明；审计 JSON 不作为正文证据替代 repository 输入。
  - 兼容边界：quorum 只接管阶段1多 family collector；单个 `collect_sources_web -> extract_schema` 的历史轻量 DAG 仍走普通依赖规则。
- 验证：
  - focused 红绿命令：`mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest#shouldExposeCollectorReadinessAuditAsInputIssueFlags,SchemaExtractorAgentTest#shouldPropagateCollectorReadinessIssueFlagsIntoExtractorOutput" test`
  - focused 结果：红测曾按预期失败；实现后 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
  - DAG quorum focused 命令：`mvn -pl backend "-Dtest=CollectorEvidenceReadinessPolicyTest,DagExecutorTest#shouldReleaseExtractorWhenCollectorQuorumIsReadyEvenIfDocsFailed+shouldKeepExtractorPendingWhenCollectorQuorumSeesRunningNode" test`
  - DAG quorum focused 结果：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
  - 兼容性回归命令：`mvn -pl backend "-Dtest=DagExecutorTest#shouldPassSharedOutputEnvelopeToDownstreamNodeContext" test`
  - 兼容性回归结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
  - Task 9 组合命令：`mvn -pl backend "-Dtest=DagExecutorTest,DagExecutorRuntimeDependencyTest,CollectorEvidenceReadinessPolicyTest,SchemaExtractorAgentTest,SchemaExtractorAgentCoverageContractTest,RepositoryExtractorInputProviderTest" test`
  - Task 9 组合结果：`Tests run: 69, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
  - 备注：控制台仍存在 Maven `settings.xml` warning、部分测试故意触发的 JSON 解析 warning 和缺失 agent warning；均未导致本组测试失败。

### Task 10: Collector 硬截止与部分证据交接

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/enums/TaskNodeStatus.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskProgressSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/utils/taskPresentation.ts`
- Modify: `frontend/src/utils/taskNodeInsights.ts`
- Modify: `frontend/src/components/task-detail/NodeAccordionList.tsx`
- Modify: `frontend/src/components/task-detail/DagOverviewBoard.tsx`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorRepairAuditTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java`
- Test: `frontend/src/utils/taskPresentation.test.ts`
- Test: `frontend/src/utils/taskNodeInsights.test.ts`
- Test: `frontend/src/components/task-detail/NodeAccordionList.test.tsx`

- [x] **Step 1: 明确 SUCCESS_DEGRADED 的状态建模**

本计划采用新增节点状态枚举，而不是用 `SUCCESS + degraded=true` 隐式模拟。

在 `TaskNodeStatus.java` 增加：

```java
@Schema(description = "节点已产出可交接结果但存在降级缺口")
SUCCESS_DEGRADED("降级成功"),
```

语义约束：

```text
SUCCESS_DEGRADED 是终态
SUCCESS_DEGRADED 可参与 CollectorEvidenceReadinessPolicy quorum
SUCCESS_DEGRADED 不等于完全成功，必须保留 degradationReasons / auditFlags / sourceUrls
SUCCESS_DEGRADED 在任务恢复时应像 SUCCESS 一样保留检查点
```

- [x] **Step 2: 写硬截止交接失败用例**

模拟 DOCS 节点已采到 3 条有效 evidence 和 `sourceUrls`，但后续页面抓取超时。断言节点最终不是无限 `RUNNING`，而是：

```text
status = TaskNodeStatus.SUCCESS_DEGRADED
outputData.sourceUrls 非空
outputData.degradationReasons 包含 HARD_DEADLINE_REACHED
```

- [x] **Step 3: 在 CollectorAgent.doExecute() 实现 hard deadline**

硬截止的实现位置放在 `CollectorAgent.doExecute()` 或 collector family 执行入口，建议用 `Future.get(timeout)` / `CompletableFuture.orTimeout(...)` 包住单个 collector 的采集闭环。

实现边界：

- `CollectorAgent` 负责停止补采、生成部分 `AgentResult`、写入 `SUCCESS_DEGRADED` 或 `FAILED`。
- `SearchExecutionCoordinator` 负责在 deadline 到达时返回当前已收集 evidence、`sourceUrls`、`degradationReasons`、field evidence 审计。
- `DagExecutor` 不负责直接杀业务采集线程，只负责识别 `SUCCESS_DEGRADED` 为终态，并按 Task 9 的 quorum 规则判断下游是否可启动。

必须加中文注释说明：hard deadline 是节点级收口机制，不是 Tavily 单次请求 timeout。

- [x] **Step 4: 定义 collector hard deadline**

按 source family 设置单节点最大运行时间，阶段1建议先保守：

```text
OFFICIAL: 90s
PRICING: 90s
DOCS: 150s
REVIEW: 120s
```

这些值是防长尾的硬截止，不是 Tavily 单次请求 timeout。超过 hard deadline 时，collector 必须停止补采并生成可审计输出。

- [x] **Step 5: 保留部分 outputData**

`CollectorAgent` / `DagExecutor` 在 stop、timeout、deadline 降级时不能清空已采集 evidence。只要有可用 `sourceUrls`，就应形成部分输出，并标记 `degraded=true`。

- [x] **Step 6: 对无证据超时与有证据超时做区分**

无证据超时：

```text
status = FAILED 或 SKIPPED
readyForQuorum = false
```

有证据超时：

```text
status = TaskNodeStatus.SUCCESS_DEGRADED
readyForQuorum = true
```

- [x] **Step 7: 更新 SUCCESS_DEGRADED 的全链路消费者**

必须同步修改：

- `DagExecutor`：依赖解析、终态判断、跳过逻辑、任务推进逻辑都要把 `SUCCESS_DEGRADED` 视为终态；是否满足下游依赖交给 Task 9 的 readiness policy。
- `NodeExecutionRecoveryPolicy`：任务公开状态聚合、resume / interrupted recovery 保留 `SUCCESS_DEGRADED` 检查点。
- `TaskProgressSnapshot`：completed node count 把 `SUCCESS_DEGRADED` 算作 completed，active count 不应包含它。
- `TaskNodeViewAssembler`：节点摘要能展示“降级成功”，并保留 degradation reason。
- 前端 `TaskNodeStatus` 类型、状态文案、节点洞察、DAG 面板、节点列表：展示 `SUCCESS_DEGRADED` 为“降级成功 / 可交接”，颜色不能和失败混淆。
- E2E 生成或记录 `collector-node-summary.json` 时，把 `SUCCESS_DEGRADED` 聚合为 terminal collector，并单独统计 degraded collector 数量。

- [x] **Step 8: 运行硬截止与状态展示单测**

```powershell
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SearchExecutionCoordinatorRepairAuditTest,CollectorAgentTest,DagExecutorTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest" test
```

```powershell
npm --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts NodeAccordionList.test.tsx
```

Expected:

```text
backend: BUILD SUCCESS
frontend: Test Files 3 passed
```

### Task 10 实测记录（2026-07-08）

- 代码修改：
  - `TaskNodeStatus` 新增 `SUCCESS_DEGRADED`，并在 Swagger 注释中明确“节点已产出可交接结果但存在降级缺口”。
  - `CollectorAgent` 增加节点级 hard deadline：`OFFICIAL=90s`、`PRICING=90s`、`DOCS=150s`、`REVIEW=120s`、默认 `120s`。该截止只用于采集节点收口，不替代 Tavily 单次请求 timeout。
  - hard deadline 到达时，若已有正式采集证据和 `sourceUrls`，输出 `SUCCESS_DEGRADED`、`degraded=true`、`degradationReasons=["HARD_DEADLINE_REACHED"]`、`readyForQuorum=true`；若没有可交接证据，则输出 `FAILED` 且 `readyForQuorum=false`。
  - `DagExecutor`、`NodeExecutionRecoveryPolicy`、`TaskRecoveryService`、`TaskRuntimeCommandAppService`、`TaskProgressSnapshot`、`TaskNodeViewAssembler`、`RuntimeEventEmitter`、`ReportService` 已把 `SUCCESS_DEGRADED` 纳入终态、检查点保留、输出复用、进度统计、事件推送和报告入口判断。
  - 前端 `NodeStatus`、任务详情页、节点状态文案、事件 reducer、DAG 节点样式和节点列表展示已识别 `SUCCESS_DEGRADED` 为“降级成功 / 可交接”，颜色使用 gold/orange 系，不与失败态混淆。
- 后端验证：
  - 命令：`mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SearchExecutionCoordinatorRepairAuditTest,CollectorAgentTest,DagExecutorTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,TaskProgressSnapshotTest" test`
  - 结果：`Tests run: 99, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
  - 备注：控制台仍存在 Maven `settings.xml` warning、部分测试故意触发的 XML/JSON 解析警告和持久化失败 warning；均未导致本组测试失败。
- 前端验证：
  - 命令：`npm.cmd --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts NodeAccordionList.test.tsx`
  - 结果：`Test Files 3 passed`，`Tests 13 passed`。
  - 追加覆盖命令：`npm.cmd --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts shared.test.ts TaskDetailPage.test.tsx taskEventReducer.test.ts display.test.ts`
  - 追加覆盖结果：`Test Files 6 passed`，`Tests 38 passed`。
- 当前边界：
  - Task 10 只解决“长尾 collector 能按硬截止进入终态并交接部分证据”。证据充分 quorum 的正式策略仍归 Task 9；字段补采预算和 supplement gate 收敛仍归 Task 11。

### Task 11: 阶段1字段补采预算与 supplement gate 收敛

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactoryTest.java`

- [x] **Step 1: 写 pending field 不应无条件补采的失败用例**

构造 collector 已满足 source family 和 `sourceUrls` quorum，但仍有非关键字段 pending。断言 `SearchExecutionCoordinator.shouldSupplement(...)` 返回 false，且审计记录：

```text
skipReason = STAGE1_QUORUM_READY_DEFER_FIELD_EVIDENCE
```

- [x] **Step 2: 区分阶段1首报字段与深挖字段**

`DimensionEvidencePlanFactory` 输出字段计划时标注：

```text
criticalForFirstReport=true/false
```

阶段1首报关键字段固定为：

```text
criticalForFirstReport=true:
- summary
- positioning
- targetUsers
- coreFeatures
- pricing

criticalForFirstReport=false:
- strengths
- weaknesses
- 其他后续增强字段
```

字段命名应映射到当前工程已有 schema 字段；如果现有字段名不同，必须在测试里明确映射关系，不能在实现时临时发明同义字段。非关键字段进入后续增强，不阻塞首报。

- [x] **Step 3: 修改 supplement gate**

`hasPendingFieldEvidenceQueries` 不能再单独触发 supplement。新的触发条件必须同时满足：

```text
存在 criticalForFirstReport 字段缺口
且当前 source family quorum 未满足或官方/定价/文档来源缺口未满足
且 field evidence budget 仍可用
且未达到 collector hard deadline
```

当前 E2E 中 `fieldEvidenceQueryExecutedCount=0` 但 pending field evidence 仍推动 supplement，说明 Task 11 的核心是修复“为什么进入 supplement”。如果已经进入 supplement 后采集仍被拉长，由 Task 10 的 hard deadline 负责收口；Task 11 不替代节点级硬截止。

- [x] **Step 4: 写预算审计**

每个 collector 输出中记录：

```json
{
  "fieldEvidenceQueryExecutedCount": 0,
  "fieldEvidenceQuerySkippedCount": 0,
  "fieldEvidenceQuerySkipReasons": [
    "STAGE1_QUORUM_READY_DEFER_FIELD_EVIDENCE"
  ]
}
```

- [x] **Step 5: 运行字段补采预算单测**

```powershell
mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest,DimensionEvidencePlanFactoryTest" test
```

Expected:

```text
BUILD SUCCESS
```

### Task 11 实测记录（2026-07-08）

- 代码修改：
  - `FieldEvidenceCoverage` 与 `FieldEvidenceQuery` 新增 `criticalForFirstReport` 标记，`DimensionEvidencePlan` 统一维护阶段1首报关键字段集合。
  - 当前工程 schema 的首报关键字段映射为 `summary`、`positioning`、`targetUsers`、`coreFeatures`、`pricing`；`weaknesses` 等增强字段保留审计但不阻塞首报。
  - `DimensionEvidencePlanFactory` 在字段 coverage 与 planned query 上同步写入 `criticalForFirstReport`，避免后续 gate 重新猜字段语义。
  - `SearchExecutionCoordinator` 不再让 `hasPendingFieldEvidenceQueries` 单独触发 supplement；当 verified source quorum 已满足且只剩非关键字段 query 时，将 executable query 转为 skipped/deferred，并写入 `STAGE1_QUORUM_READY_DEFER_FIELD_EVIDENCE`。
  - 被延期的字段 query 会从跨节点 field evidence claim 集合释放，避免后续增强轮误判为已占用。
  - `resolveSearchFallbackOrder` 只在存在首报关键字段 pending 时优先 HTTP，再走 browser，避免非关键字段改变首报补采路径。
- 红灯验证：
  - 命令：`mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceTest#shouldDeferNonCriticalPendingFieldEvidenceWhenVerifiedSourceQuorumReady" test`
  - 结果：修复前失败，`weaknesses` 非关键字段仍触发 supplement request，确认旧 gate 会被 pending field evidence 单独拉起。
- focused 验证：
  - 命令：`mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceTest#shouldDeferNonCriticalPendingFieldEvidenceWhenVerifiedSourceQuorumReady,DimensionEvidencePlanFactoryTest#shouldMarkOnlyStageOneFirstReportFieldsAsCritical" test`
  - 结果：`BUILD SUCCESS`。
- Task 11 组合验证：
  - 命令：`mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest,DimensionEvidencePlanFactoryTest" test`
  - 结果：`Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 备注：
  - 控制台仍存在 Maven `settings.xml` mirror warning、XML DOCTYPE warning 与 LF/CRLF 提示；本组测试未受影响。

### Task 12: 单样本优先的友好基线 E2E 复验与记录

**Files:**

- Modify: `docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md`
- Optional modify: `docs/specs/2026-06-30-two-axis-closure-direction.md`
- Runtime output: `tmp/task17-root-cause-friendly-baseline-<yyyyMMdd-HHmmss>/`

- [ ] **Step 1: 仅在 9093 启动后端**

不得新开测试端口。若 9093 已被占用，先确认是否为本项目后端；不是则停止本次 E2E，记录阻塞原因，不切换端口。

```powershell
netstat -ano | findstr ":9093"
mvn -pl backend spring-boot:run "-Dspring-boot.run.arguments=--server.port=9093"
```

- [ ] **Step 2: 按单样本优先策略执行友好竞品**

固定样本仍保留两组，但执行顺序必须一个一个来：

```text
第一轮：Notion vs Airtable
第二轮：Linear vs Jira
```

执行策略：

- 先只跑第一轮样本，目标是让一个友好样本完整产出报告或可交付降级报告。
- 第一轮未通过时，不继续启动第二轮；先回到 Task 8 - Task 11 定位根因并修复，避免两个失败样本混在一起增加噪声。
- 第一轮通过后，再启动第二轮样本；第二轮用于验证修复不是只对单个样本特化。
- 两轮都必须继续遵守 9093 端口约束；可以同一个后端进程串行执行，也可以每轮执行后停止并确认 9093 释放，但不得新开端口。

记录到 `tmp/task17-root-cause-friendly-baseline-<yyyyMMdd-HHmmss>/`：

```text
create-request.json
create-response.json
poll-*-task.json
poll-*-nodes.json
final-task.json
final-nodes.json
report-final.json
agent-log-final.json
replay-final.json
```

- [ ] **Step 3: 记录 DOCS 选源审计**

每组样本必须记录：

```markdown
- DOCS 最终采集 URL：
- 是否来自 Tavily / 搜索发现：
- 是否为模板 fallback：
- 若模板 fallback=true，原因：
- DOCS 节点状态：
- DOCS sourceUrls 数量：
- DOCS degradationReasons：
```

- [ ] **Step 4: 验证 quorum 推进**

每组样本必须记录：

```markdown
- collector 成功或可交接 family：
- SUCCESS_DEGRADED collector 数量：
- 未完成或降级 family：
- CollectorEvidenceReadiness.ready：
- CollectorEvidenceReadiness.degraded：
- quorum reason：
- extract_schema 是否启动：
- report 是否生成：
```

- [ ] **Step 5: 停止 9093 进程并确认释放**

测试结束后必须关闭后端进程。

```powershell
netstat -ano | findstr ":9093"
Stop-Process -Id <PID> -Force
netstat -ano | findstr ":9093"
```

最后一次 `netstat` 不应再出现 9093 监听。

- [ ] **Step 6: 把 E2E 结果写回本节**

写入格式：

```markdown
### Task 12 复验记录

- 日期：
- 运行目录：
- 端口约束：仅使用 9093；测试结束后 9093 已释放
- 执行策略：单样本先过，再执行第二样本；若第一样本失败，则不启动第二样本
- 样本 1：Notion vs Airtable
  - 任务 id：
  - 最终状态：
  - 是否产出报告：
  - qualityScore：
  - sourceUrls 数量：
  - distinctSourceDomains 数量：
  - DOCS URL 是否为 Tavily / 搜索发现优先：
  - 是否触发模板 fallback：
  - SUCCESS_DEGRADED collector 数量：
  - quorum 是否放行下游：
  - 降级说明：
- 样本 2：Linear vs Jira
  - 任务 id：
  - 最终状态：
  - 是否产出报告：
  - qualityScore：
  - sourceUrls 数量：
  - distinctSourceDomains 数量：
  - DOCS URL 是否为 Tavily / 搜索发现优先：
  - 是否触发模板 fallback：
  - SUCCESS_DEGRADED collector 数量：
  - quorum 是否放行下游：
  - 降级说明：
```

通过标准：

- [ ] 第一验收门槛：先有一组友好基线生成完整报告或可交付降级报告。
- [ ] 第二验收门槛：第一组通过后，再跑第二组；第二组若失败，必须记录新的唯一 root cause，不能回退第一组已通过能力。
- [ ] 最终目标仍是两组都生成报告或可交付降级报告，但不要求同一轮一次性双样本全过。
- [ ] `sourceUrls >= 5`，`distinctSourceDomains >= 2`。
- [ ] 报告或降级报告保留可点击来源 URL。
- [ ] DOCS 模板 URL 不得排在 verified Tavily / 搜索发现 DOCS URL 前面。
- [ ] 任一非关键采集分支长尾时，下游可在 quorum 满足后启动。
- [ ] `SUCCESS_DEGRADED` 在后端节点状态、任务快照、前端节点展示、E2E `collector-node-summary.json` 中都被识别为终态且单独标记为降级。
- [ ] 每个超时或跳过分支都有可审计原因。
- [ ] 测试只使用 9093，结束后进程关闭。

### 17.3 最终验证命令

根因修复完成后至少运行：

```powershell
mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,SourceFamilyDirectDiscoveryPlannerTest,SourceCandidateRankerTest,CollectionTargetSelectorTest,DagExecutorTest,DagExecutorRuntimeDependencyTest,CollectorEvidenceReadinessPolicyTest,SchemaExtractorAgentTest,SchemaExtractorAgentCoverageContractTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorRepairAuditTest,CollectorAgentTest,NodeExecutionRecoveryPolicyTest,TaskNodeViewAssemblerTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest,DimensionEvidencePlanFactoryTest" test
```

前端状态展示验证：

```powershell
npm --prefix frontend test -- taskPresentation.test.ts taskNodeInsights.test.ts NodeAccordionList.test.tsx
```

文档和空白检查：

```powershell
git diff --check -- docs/Tavily/task/2026-07-07-17-stage1-threshold-relaxation-and-friendly-baseline-plan.md backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java backend/src/main/java/cn/bugstack/competitoragent/model/enums/TaskNodeStatus.java backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskProgressSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java frontend/src/types/index.ts frontend/src/utils/taskPresentation.ts frontend/src/utils/taskNodeInsights.ts frontend/src/components/task-detail/NodeAccordionList.tsx frontend/src/components/task-detail/DagOverviewBoard.tsx
```
