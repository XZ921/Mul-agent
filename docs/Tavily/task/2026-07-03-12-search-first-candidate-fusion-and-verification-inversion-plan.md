# Search-First Candidate Fusion and Verification Inversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 plan12 的主路径真正改成“搜索为主、直采为辅”：direct discovery 只生成官方锚点和少量 seed，Tavily 在验证前完成主搜索候选扩展，候选合并排序后只验证入围候选，最终让强正文 Tavily 结果进入 `selectedTargets` 和 `evidence_source`。

**Architecture:** 本计划不删除 direct discovery，也不靠单纯调大 `maxSearchResults`。新增一个候选融合/验证规划层，把 `planned/direct seed`、`TAVILY_PHASE1_BOOTSTRAP`、`TAVILY_FIELD_EVIDENCE_QUERY` 的候选统一放入同一个 pre-verification pool；再按 source family、正文质量、fast lane、域名多样性和验证成本生成“要入选多少、哪些可跳过验证、哪些需要浏览器验证”的执行决策。`SearchExecutionCoordinator` 只编排阶段，具体策略下沉到 `SearchPolicyResolver` 和新的 fusion planner。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven, PostgreSQL e2e 数据核验。

---

## 0. 根因约束

这次计划必须避开“问题三：计划方法偏症状修复，根因能力被反复后移”。

禁止把以下动作当成主修法：

- 只把 `maxSearchResults=1` 改成 `3` 或 `5`。
- 只提高 search timeout、field query timeout 或重试次数。
- 只给 Apifox/about 壳页加黑名单。
- 只在 9a case 上加特殊判断。
- 只让 supplement 阶段多跑几条 Tavily query。
- 直接删除 direct discovery，改成 Tavily-only。

本计划要修的是能力边界：

| 层 | 当前问题 | 根因修法 |
|---|---|---|
| direct discovery | seed 被提前浏览器验证，耗时且压制 Tavily | direct discovery 降级为 anchor/seed，不再主导验证顺序 |
| Tavily bootstrap | 已存在但仍像增强补丁，结果没有稳定进入最终证据 | search-first family 必须在验证前执行 Tavily 主搜索 |
| target count | `competitorUrls` 数量把最终选源压成 1 | 区分“用户显式 URL 数量”和“搜索优先证据目标数” |
| verifier | 对大量 seed/壳页做早期验证 | 只验证融合排序后的入围候选，fast lane 正文跳过网络验证 |
| field query deadline | `execute()` 已把 `searchTimeoutMillis` bump 到 field query 最低预算，但 `resolveFieldEvidenceExecutionDeadlineEpochMillis()` 独立重算 base timeout，导致 request deadline 仍可能只有 15s | deadline 必须使用本轮 execute 已解析并 bump 后的 `searchTimeoutMillis`，不能在 request builder 里二次解析 |
| selector | verified 壳页可能压过 Tavily 强正文 | selector 以正文质量和 fast lane 可用性作为正式入选资格 |
| observability | selected summary 丢失 query mode/quality 元数据 | 让 selected/audit 能看见 Tavily 模式、正文长度、fast lane 决策 |

验收不以“任务最终生成报告”为唯一标准，必须同时看采集层 KPI。

---

## 1. File Map

### Create

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java`
  - 新的候选融合与验证规划器。
  - 输入全部候选、任务配置、基础 target count、运行策略。
  - 输出有效证据目标数、预选候选、需要验证的候选、可跳过验证的 fast lane 候选、审计计数。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionDecision.java`
  - `SearchCandidateFusionPlanner` 的不可变 DTO。
  - 字段包含 `effectiveTargetCount`、`preselectedCandidates`、`verificationCandidates`、`fastLaneCandidates`、`directSeedCandidateCount`、`thirdPartyCandidateCount`、`reason`。

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`
  - 锁定 search-first 下的目标数、fast lane 保留、域名多样性、壳页降权。

- `backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java`
  - 锁定 search-first official family 在验证前必须生成 `TRUSTED_WEB_EXPANSION` bootstrap request。

### Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
  - 增加 search-first 证据目标与验证预算的显式策略字段。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
  - 增加 search-first effective target count / verification budget 解析。
  - 保持非 search-first family 的旧行为。

- `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlanner.java`
  - search-first family 下，Tavily bootstrap 不再只由 weak seed 触发。
  - 默认 query mode 走 `TRUSTED_WEB_EXPANSION`，`includeDomains` 清空，官方域名只作为 preferred/anchor。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
  - 把“验证候选”改为“候选融合后验证入围候选”。
  - 使用 effective target count 进入 `CollectionTargetSelector`。
  - trace 增加 fusion/verification 计数。

- `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
  - 确保 `STRONG + fastLaneUsable + hasPrefetchedContent` 优先于短正文 verified 壳页。
  - 对 verified 但正文极短的 attempted target 做质量降档，而不是让“验证过”覆盖“正文可用”。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
  - 增加 search-first fusion 可观测字段。

- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
  - selected target 摘要保留 `discoveryMethod`、`tavilyQueryMode`、`qualityTier`、`fastLaneUsable`、`prefetchedRawContentLength`。

- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorSelectedTargetSummary.java`
  - 补齐 selected target 前端/审计 DTO 字段。

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
  - 增加 search-first 主路径回归。

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
  - 增加 field query deadline 使用 bumped timeout 的回归，避免 deadline 独立重算回 base timeout。

- `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
  - 增加强 Tavily 正文压过 46 字壳页的回归。

- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskNodeViewAssemblerTest.java`
  - 增加 selected target Tavily 元数据投影回归。

---

## 2. Target Runtime Flow

改完后的 search-first official family 流程应为：

```text
LOAD_CANDIDATES
  direct discovery 展开 competitorUrls，只生成 DIRECT_LOCATOR / FAMILY_TEMPLATE seed
  seed 带 official domain / sourceFamily 信息，不立即浏览器验证全部 seed

TAVILY_BOOTSTRAP_ENRICH
  search-first family 必跑 Tavily 主搜索
  queryMode = TRUSTED_WEB_EXPANSION
  includeDomains = []
  preferredDomains / seedCandidates 只作为官方锚点

CANDIDATE_FUSION_RANK
  合并 planned seed + Tavily bootstrap + 后续 supplement 候选
  计算 effectiveTargetCount
  保留 fastLaneUsable 强正文
  生成 verificationCandidates

VERIFY_TOP_CANDIDATES
  只验证 fusion 入围且不能 skipNetworkVerification 的候选
  fast lane 强正文不走浏览器网络验证

BROWSER_SUPPLEMENT_SEARCH / FIELD_EVIDENCE
  仅在覆盖不足或 field evidence 未满足时继续补
  补源结果回流同一 fusion/selection 规则

SELECT_TARGETS
  使用 effectiveTargetCount
  选择多个高质量来源，避免单个短壳页吞掉候选池
```

---

## 3. Task 1: 先写失败测试，复现 9a 暴露的结构性问题

**Files:**

- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`

- [ ] **Step 1: 写 coordinator 失败测试，证明 `maxSearchResults=1` 不能压扁 search-first 证据目标**

新增测试名：

```java
@Test
void shouldSelectMultipleSearchFirstEvidenceTargetsEvenWhenDirectSeedCountIsOne()
```

测试输入：

- `sourceType="OFFICIAL"`
- `competitorUrls=List.of("https://open.douyin.com")`
- `maxSearchResults=1`
- `verifyCandidates=true`
- `searchRuntimePolicy.searchFirstEvidenceTargetFloor=3`
- provider 在 `SearchRequestPhase.BOOTSTRAP` 返回 3 条候选：
  - `developer.open-douyin.com`，`TRUSTED_WEB_EXPANSION`，`STRONG`，`fastLaneUsable=true`，`prefetchedRawContentLength=19555`
  - 一个第三方长文域名，`TRUSTED_WEB_EXPANSION`，`STRONG`，`fastLaneUsable=true`，`prefetchedRawContentLength=3109`
  - 一个行业分析长文域名，`TRUSTED_WEB_EXPANSION`，`STRONG`，`fastLaneUsable=true`，`prefetchedRawContentLength=5442`

断言：

```java
assertEquals(3, result.getSelectedTargets().size());
assertTrue(result.getSelectedTargets().stream()
        .anyMatch(target -> target.getCandidate().getDomain() != null
                && !target.getCandidate().getDomain().endsWith("open.douyin.com")));
assertEquals(3, result.getExecutionTrace().getEffectiveSearchFirstTargetCount());
assertEquals(0, result.getExecutionTrace().getCandidateVerificationDirectAttemptCount());
```

当前代码预期失败点：

- `selectedTargets.size()` 仍可能是 1。
- `effectiveSearchFirstTargetCount` 字段不存在。
- direct/browser verification 仍可能发生在过多 seed 上。

- [ ] **Step 2: 写 bootstrap planner 失败测试，证明 search-first official 必须走 trusted expansion**

新增测试名：

```java
@Test
void shouldBuildTrustedWebExpansionBootstrapForSearchFirstOfficialFamily()
```

断言：

```java
assertTrue(decision.isShouldExecute());
assertEquals(SearchRequestPhase.BOOTSTRAP, decision.getRequest().getRequestPhase());
assertEquals("TRUSTED_WEB_EXPANSION", decision.getRequest().getPreferredQueryMode());
assertTrue(decision.getRequest().getIncludeDomains().isEmpty());
assertTrue(decision.getRequest().getPreferredDomains().contains("open.douyin.com"));
```

当前代码预期失败点：

- `preferredQueryMode` 可能为空或沿用 config。
- `includeDomains` 可能继续透传官方域名锁。

- [ ] **Step 3: 写 selector 失败测试，证明强 Tavily 正文不能输给 46 字 verified 壳页**

新增测试名：

```java
@Test
void shouldPreferStrongPrefetchedContentOverThinVerifiedShell()
```

构造：

- verified shell: `https://bilibili.apifox.cn/about`，`verified=true`，`totalScore=0.99`，attempted page content 长度 46。
- Tavily strong: `https://open-live.bilibili.com/document/...`，`qualityTier=STRONG`，`fastLaneUsable=true`，`hasPrefetchedContent=true`，`prefetchedContentRef` 非空，`prefetchedRawContentLength=7444`，`totalScore=0.86`。
- `targetCount=1`。

断言选中 Tavily strong。

当前代码预期失败点：

- verified shell 和 fast lane 可能同 tier，再按 `totalScore` 让 46 字壳页胜出。

- [ ] **Step 4: 写 fusion planner 失败测试，锁定根因能力**

新增测试名：

```java
@Test
void shouldBuildVerificationPlanFromFusedCandidatesAndKeepFastLaneOutOfBrowserVerification()
```

断言：

- `effectiveTargetCount >= 3`
- `fastLaneCandidates.size() >= 2`
- `verificationCandidates` 不包含 `skipNetworkVerification=true` 的候选
- 至少保留 1 个第三方域名
- direct seed 只作为 anchor，不因为 totalScore 高而挤掉所有 Tavily 强正文

- [ ] **Step 5: 运行失败测试**

Run:

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorTest,CollectionTargetSelectorTest,TavilyBootstrapPlannerTest,SearchCandidateFusionPlannerTest test
```

Expected:

- 新增测试失败。
- 失败原因必须对应本计划列出的根因，不接受只因为 mock 没配好导致的失败。

---

## 4. Task 2: 增加 search-first 证据目标策略，不再用 direct URL 数量决定最终选源数量

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`

- [ ] **Step 1: 在 `SearchRuntimePolicy` 增加策略字段**

新增字段：

```java
/**
 * search-first 家族的证据目标下限。
 * 这个字段不是 maxSearchResults 的替代品，而是用于避免“用户只给 1 个官网 URL，
 * 最终证据也只能选 1 条”的结构性塌缩。
 */
private Integer searchFirstEvidenceTargetFloor;

/**
 * search-first 家族的证据目标上限。
 * 防止 Tavily 主搜索一次返回过多高质量页面时，把正式采集目标膨胀到不可控规模。
 */
private Integer searchFirstEvidenceTargetCeiling;

/**
 * 候选融合后允许进入浏览器验证的候选数量。
 * fast lane 可用正文不占用这个预算。
 */
private Integer preSelectionVerificationLimit;
```

- [ ] **Step 2: 在 `SearchPolicyResolver` 增加解析方法**

新增方法签名：

```java
public int resolveEffectiveTargetCountForSearchFirst(CollectorNodeConfig config,
                                                     int baseTargetCount,
                                                     int fusedCandidateCount)
```

解析规则：

```text
非 search-first family：
  返回 baseTargetCount

search-first family：
  floor = runtimePolicy.searchFirstEvidenceTargetFloor，默认 3
  ceiling = runtimePolicy.searchFirstEvidenceTargetCeiling，默认 5
  candidateBound = max(baseTargetCount, min(fusedCandidateCount, ceiling))
  返回 max(baseTargetCount, min(ceiling, max(floor, candidateBound)))
```

注意：

- `floor=3` 来自 9a KPI 的最低独立来源要求，不是为了某个域名临时调参。
- `ceiling=5` 是正式采集成本护栏，不是搜索候选池上限。
- 如果 `fusedCandidateCount < floor`，返回不能超过实际候选数量。

- [ ] **Step 3: 增加验证预算解析**

新增方法签名：

```java
public int resolvePreSelectionVerificationLimit(CollectorNodeConfig config,
                                                int effectiveTargetCount)
```

默认规则：

```text
runtimePolicy.preSelectionVerificationLimit > 0:
  使用显式配置
search-first:
  max(effectiveTargetCount, 3)
其他 family:
  复用现有 verification limit 语义
```

- [ ] **Step 4: 运行 policy/fusion 单测**

Run:

```bash
mvn -pl backend -Dtest=SearchCandidateFusionPlannerTest test
```

Expected:

- search-first 下 `maxSearchResults=1` 不再导致 effective target count 等于 1。
- 非 search-first 场景仍保持旧行为。

---

## 5. Task 3: Tavily bootstrap 成为 search-first 主搜索，不再只是 weak seed 增强

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlanner.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java`

- [ ] **Step 1: 给 `TavilyBootstrapPlanner` 注入 `SearchPolicyResolver`**

保留默认构造兼容测试：

```java
public TavilyBootstrapPlanner() {
    this(new SearchPolicyResolver());
}

public TavilyBootstrapPlanner(SearchPolicyResolver searchPolicyResolver) {
    this.searchPolicyResolver = searchPolicyResolver == null
            ? new SearchPolicyResolver()
            : searchPolicyResolver;
}
```

- [ ] **Step 2: 修改 `plan` 的触发条件**

新规则：

```text
search-first family + 有 competitorUrls：
  必须执行 bootstrap
  weakSeeds 为空时也用 direct seeds 作为 anchor

非 search-first family：
  保持旧的 weak seed 触发逻辑
```

- [ ] **Step 3: 修改 bootstrap request**

search-first official family 下：

```java
.preferredQueryMode("TRUSTED_WEB_EXPANSION")
.includeDomains(List.of())
.preferredDomains(resolveOfficialDomains(config, weakSeeds))
.seedCandidates(weakSeeds)
.requestPhase(SearchRequestPhase.BOOTSTRAP)
```

关键点：

- `includeDomains` 不能继续锁官方域名。
- 官方域名只进入 `preferredDomains` / `seedCandidates`，用于 Gate 和排序锚点。
- 如果 config 显式传入 `tavilyQueryMode=OFFICIAL_DOCS`，只允许非 search-first 或明确 debug 场景使用；search-first 默认必须覆盖成 `TRUSTED_WEB_EXPANSION`。

- [ ] **Step 4: 运行 bootstrap planner 单测**

Run:

```bash
mvn -pl backend -Dtest=TavilyBootstrapPlannerTest test
```

Expected:

- search-first official family 的 bootstrap request 是 `TRUSTED_WEB_EXPANSION`。
- `includeDomains` 为空。
- `preferredDomains` 包含用户输入官方域名。

---

## 6. Task 4: 新增 Candidate Fusion Planner，统一决定入选目标和验证目标

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionDecision.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java`

- [ ] **Step 1: 创建 `SearchCandidateFusionDecision`**

字段：

```java
private int baseTargetCount;
private int effectiveTargetCount;
private int directSeedCandidateCount;
private int tavilyCandidateCount;
private int fastLaneCandidateCount;
private int thirdPartyCandidateCount;
private int verificationCandidateCount;
private List<SourceCandidate> rankedCandidates;
private List<SourceCandidate> preselectedCandidates;
private List<SourceCandidate> verificationCandidates;
private List<SourceCandidate> fastLaneCandidates;
private String reason;
```

- [ ] **Step 2: 创建 `SearchCandidateFusionPlanner`**

核心方法：

```java
public SearchCandidateFusionDecision plan(CollectorNodeConfig config,
                                          List<SourceCandidate> candidates,
                                          int baseTargetCount,
                                          int maxCandidatesPerDomain)
```

排序原则：

1. `fastLaneUsable=true && hasPrefetchedContent=true && qualityTier=STRONG` 优先。
2. search-first family 中，第三方强正文不能因为不是官方域名而被降为不可选。
3. verified 但正文极短的壳页低于 STRONG fast lane。
4. 保留官方锚点，但官方锚点不能挤掉所有第三方强正文。
5. 同一 canonical URL 去重后保留 Tavily 元数据。
6. 同一域名最多保留 `maxCandidatesPerDomain`，但如果候选总数不足，不因此清空候选池。

- [ ] **Step 3: 生成 verification candidates**

规则：

```text
fastLaneUsable && skipNetworkVerification:
  进入 fastLaneCandidates，不进入 verificationCandidates

verified=true:
  可进入 preselectedCandidates，不重复验证

其他候选：
  按 fusion 排序进入 verificationCandidates，数量由 preSelectionVerificationLimit 控制
```

- [ ] **Step 4: 运行 fusion 单测**

Run:

```bash
mvn -pl backend -Dtest=SearchCandidateFusionPlannerTest test
```

Expected:

- `maxSearchResults=1` 的 search-first 场景仍输出 `effectiveTargetCount>=3`。
- fast lane 候选进入 `preselectedCandidates`，不进入 `verificationCandidates`。
- 至少一个第三方强正文被保留。

---

## 7. Task 5: 改造 SearchExecutionCoordinator 的验证时序

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 注入 `SearchCandidateFusionPlanner`**

构造器保持兼容：

```java
this.searchCandidateFusionPlanner = searchCandidateFusionPlanner == null
        ? new SearchCandidateFusionPlanner(searchPolicyResolver, sourceCandidateRanker)
        : searchCandidateFusionPlanner;
```

- [ ] **Step 2: 在 bootstrap 后、verify 前执行 fusion**

位置：

```text
LOAD_CANDIDATES
TAVILY_BOOTSTRAP_ENRICH
NEW: CANDIDATE_FUSION_RANK
VERIFY_TOP_CANDIDATES
```

替换当前直接从 `allCandidates` 取 verify candidates 的逻辑。

- [ ] **Step 3: `CandidateVerifier.verify` 只接收 fusion 输出的 `verificationCandidates`**

新语义：

```java
List<SourceCandidate> verifyCandidates = fusionDecision.getVerificationCandidates();
CandidateVerificationResult verificationResult = candidateVerifier.verify(
        config.getCompetitorName(),
        config.getSourceType(),
        verifyCandidates
);
```

禁止再用：

```java
allCandidates.stream()
        .sorted(...)
        .limit(resolveVerificationCandidateLimit(...))
```

原因：旧逻辑让高分 direct seed / 壳页在 Tavily 强正文之后仍消耗大量验证预算。

- [ ] **Step 4: `SELECT_TARGETS` 使用 `effectiveTargetCount`**

修改：

```java
SearchSelectionDecision selectionDecision = collectionTargetSelector.selectTargets(
        allCandidates,
        attemptedTargets,
        fusionDecision.getEffectiveTargetCount()
);
```

非 search-first family 下 `effectiveTargetCount == targetCount`，保持旧行为。

- [ ] **Step 5: trace 增加 fusion 字段**

在 `SearchExecutionTrace` 增加：

```java
private Integer baseTargetCount;
private Integer effectiveSearchFirstTargetCount;
private Integer fusionRankedCandidateCount;
private Integer fusionPreselectedCandidateCount;
private Integer fusionFastLaneCandidateCount;
private Integer fusionVerificationCandidateCount;
private Integer fusionThirdPartyCandidateCount;
private Integer directSeedCandidateCount;
```

- [ ] **Step 6: 运行 coordinator 单测**

Run:

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorTest test
```

Expected:

- search-first case 中 Tavily bootstrap 发生在验证前。
- direct verification attempt 明显下降。
- selected targets 不再被 `maxSearchResults=1` 压成 1。

---

## 8. Task 6: 修正 selector 质量排序，避免 verified 短壳页压过强正文

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [ ] **Step 1: 增加 attempted page 正文长度判断**

新增私有方法：

```java
private boolean hasThinAttemptedPage(SearchCollectionTarget attemptedTarget) {
    if (attemptedTarget == null || attemptedTarget.getCollectedPage() == null) {
        return false;
    }
    String content = attemptedTarget.getCollectedPage().getContent();
    return content == null || content.trim().length() < 800;
}
```

说明：

- 这个阈值只用于“已验证但正文极薄”的降档，不是最终正文质量标准。
- Tavily Gate 的 `STRONG` 仍由 `TavilyPrefetchedContentGate` 的 2000 字 ARTICLE 规则负责。

- [ ] **Step 2: 修改 `resolveSelectionTier`**

目标排序：

```text
tier 0:
  usable Tavily prefetched candidate
  verified candidate with non-thin collected page

tier 1:
  verified candidate but root shell / thin shell
  structured executor candidate

tier 2:
  other selectable fallback candidates

tier 3:
  discarded candidates
```

- [ ] **Step 3: 保证 selected reason 可解释**

当 verified thin shell 未入选时，`discardedCandidates` 或 `selectionReason` 中要能看到：

```text
VERIFIED_THIN_SHELL_DEMOTED
```

- [ ] **Step 4: 运行 selector 单测**

Run:

```bash
mvn -pl backend -Dtest=CollectionTargetSelectorTest test
```

Expected:

- `open-live.bilibili.com` 这种 STRONG fast lane 候选压过 `bilibili.apifox.cn/about` 46 字壳页。
- 既有 verified 真正文档仍然能和 fast lane 同档竞争。

---

## 9. Task 7: 补齐 selected target 和 trace 可观测性

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorSelectedTargetSummary.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskNodeViewAssemblerTest.java`

- [ ] **Step 1: selected target summary 增加 Tavily 元数据**

新增字段：

```java
private String discoveryMethod;
private String tavilyQueryMode;
private String qualityTier;
private Boolean fastLaneUsable;
private Integer prefetchedRawContentLength;
private Boolean skipNetworkVerification;
private String selectionReason;
```

- [ ] **Step 2: `TaskNodeViewAssembler` 投影这些字段**

从 `SearchCollectionTarget.candidate` 读取，不从 stripped summary 猜。

- [ ] **Step 3: trace 输出 fusion 指标**

确保 `task_node.output_data.searchAudit.executionTrace` 可以直接看到：

```json
{
  "baseTargetCount": 1,
  "effectiveSearchFirstTargetCount": 3,
  "fusionFastLaneCandidateCount": 3,
  "fusionVerificationCandidateCount": 0,
  "fusionThirdPartyCandidateCount": 1
}
```

- [ ] **Step 4: 运行投影测试**

Run:

```bash
mvn -pl backend -Dtest=TaskNodeViewAssemblerTest test
```

Expected:

- 前端/回放 selected target 不再出现 `discoveryMethod=null`、`tavilyQueryMode=null`、`qualityTier=null` 的审计断层。

---

## 10. Task 8: 修复 field query deadline 独立重算 base timeout 的 bug

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`

这个任务是独立 bugfix，不能只依赖 Task 3/5 的架构改动“降低触发概率”。9a 已经证明 field query 只发 1 条、第二条 `SKIPPED_BUDGET_EXHAUSTED` 时，deadline 仍会影响 Tavily 的实际发挥。

当前问题：

```java
// execute() 入口已把 searchTimeoutMillis bump 到 field query 最低预算
long searchTimeoutMillis = searchPolicyResolver.ensureMinimumTimeoutForFieldEvidenceQueries(
        baseSearchTimeoutMillis,
        fieldEvidenceQueryPlan.getPlanned()
);

// 但 request builder 又调用独立方法重算 deadline
.fieldEvidenceExecutionDeadlineEpochMillis(resolveFieldEvidenceExecutionDeadlineEpochMillis(config))
```

`resolveFieldEvidenceExecutionDeadlineEpochMillis(config)` 内部重新执行：

```java
long baseSearchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(
        config.getSearchTimeoutMillis(),
        executionPlan
);
return System.currentTimeMillis() + baseSearchTimeoutMillis;
```

这会让 provider 看到的 deadline 回落到 base timeout，例如 `15s`，而不是 execute 已经 bump 后的 `24s`。

- [ ] **Step 1: 写 deadline 失败测试**

在 `SearchExecutionCoordinatorFieldEvidenceBudgetTest` 新增：

```java
@Test
void shouldUseBumpedSearchTimeoutWhenBuildingFieldEvidenceDeadline() {
    RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
    SearchExecutionCoordinator coordinator = newCoordinator(provider);

    long startedAt = System.currentTimeMillis();
    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("哔哩哔哩")
            .sourceType("DOCS")
            .verifyCandidates(false)
            .searchMode("HTTP_ONLY")
            .searchFallbackOrder(List.of("HTTP"))
            .preferredSearchProvider("tavily")
            .browserSearchEnabled(false)
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .searchTimeoutMillis(15_000L)
            .dimensionEvidencePlan(twoFieldQueryPlan())
            .build());

    assertThat(provider.requests).hasSize(1);
    SearchSourceRequest request = provider.requests.get(0);
    assertThat(result.getExecutionTrace().getSearchTimeoutMillis()).isEqualTo(24_000L);
    assertThat(request.getFieldEvidenceExecutionDeadlineEpochMillis() - startedAt)
            .isGreaterThanOrEqualTo(23_000L);
}
```

测试辅助方法：

```java
private DimensionEvidencePlan twoFieldQueryPlan() {
    return DimensionEvidencePlan.builder()
            .competitorName("哔哩哔哩")
            .maxCollectionRounds(2)
            .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                    .fieldName("coreFeatures")
                    .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                    .minimumAttemptedPaths(1)
                    .completedPaths(List.of())
                    .plannedQueries(List.of(
                            fieldQuery("q-deadline-1", 10, "官方 API 文档"),
                            fieldQuery("q-deadline-2", 20, "第三方接入分析")
                    ))
                    .build()))
            .build();
}
```

当前代码预期失败：

- `request.getFieldEvidenceExecutionDeadlineEpochMillis() - startedAt` 约等于 `15_000L`，达不到 `23_000L`。

- [ ] **Step 2: 修改 request builder，不再独立重算 deadline**

把 `buildSearchSourceRequest` 的签名从：

```java
private SearchSourceRequest buildSearchSourceRequest(CollectorNodeConfig config,
                                                     List<SourceCandidate> allCandidates,
                                                     ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan)
```

改成：

```java
private SearchSourceRequest buildSearchSourceRequest(CollectorNodeConfig config,
                                                     List<SourceCandidate> allCandidates,
                                                     ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan,
                                                     Long fieldEvidenceExecutionDeadlineEpochMillis)
```

request builder 改成：

```java
.fieldEvidenceExecutionDeadlineEpochMillis(fieldEvidenceExecutionDeadlineEpochMillis)
```

- [ ] **Step 3: 在 execute 入口统一计算 bumped deadline**

在 `searchTimeoutMillis` bump 完成后增加：

```java
Long fieldEvidenceExecutionDeadlineEpochMillis = resolveFieldEvidenceExecutionDeadlineEpochMillis(
        searchTimeoutMillis,
        fieldEvidenceQueryPlan
);
```

新方法只消费已经解析好的值：

```java
private Long resolveFieldEvidenceExecutionDeadlineEpochMillis(long searchTimeoutMillis,
                                                              ResolvedFieldEvidenceQueryPlan fieldEvidenceQueryPlan) {
    if (fieldEvidenceQueryPlan == null || fieldEvidenceQueryPlan.getExecutable().isEmpty()) {
        return null;
    }
    if (searchTimeoutMillis < 0L) {
        return null;
    }
    return System.currentTimeMillis() + searchTimeoutMillis;
}
```

删除或废弃旧的：

```java
private Long resolveFieldEvidenceExecutionDeadlineEpochMillis(CollectorNodeConfig config)
```

关键约束：

- 不在 deadline 方法里重新 `initializePlan(config.getSearchExecutionPlan())`。
- 不在 deadline 方法里重新 `resolveSearchTimeoutMillis(config.getSearchTimeoutMillis(), executionPlan)`。
- deadline 口径必须和 `SearchExecutionTrace.searchTimeoutMillis` 一致。

- [ ] **Step 4: 更新调用点**

在 `executeSupplementByFallbackOrder` 或其上游调用链中传入 `fieldEvidenceExecutionDeadlineEpochMillis`。

如果当前调用链过深，优先选择最小但清晰的改法：

```text
execute()
  -> executeSupplementByFallbackOrder(..., fieldEvidenceQueryPlan, fieldEvidenceExecutionDeadlineEpochMillis)
  -> buildSearchSourceRequest(..., fieldEvidenceQueryPlan, fieldEvidenceExecutionDeadlineEpochMillis)
```

不要把 deadline 临时塞进 `CollectorNodeConfig`，避免污染节点配置语义。

- [ ] **Step 5: 运行 deadline 定向测试**

Run:

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest#shouldUseBumpedSearchTimeoutWhenBuildingFieldEvidenceDeadline test
```

Expected:

- 测试通过。
- request deadline 至少接近 bumped timeout。

- [ ] **Step 6: 运行 field evidence budget 全文件测试**

Run:

```bash
mvn -pl backend -Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest test
```

Expected:

- All tests pass.
- 既有“quota 按放大前预算计算”的测试仍通过。
- 新增“deadline 按放大后 timeout 计算”的测试通过。

注意这里的双口径不是矛盾：

```text
executable quota:
  用 base timeout，避免 query 数量被 bumped timeout 反推成无限放行

execution deadline:
  用 bumped timeout，确保已经被选中的 executable query 真有执行窗口
```

---

## 11. Task 9: 回归测试与 9a/9b 验收

**Files:**

- No code files beyond previous tasks.
- Runtime artifacts: `tmp/task12-9a-*`, `tmp/task12-9b-*`

- [ ] **Step 1: 跑搜索链路定向单测**

Run:

```bash
mvn -pl backend -Dtest=SearchCandidateFusionPlannerTest,TavilyBootstrapPlannerTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,CollectionTargetSelectorTest,TavilyPrefetchedContentGateTest,TavilySearchProfileResolverTest test
```

Expected:

- All tests pass.

- [ ] **Step 2: 跑任务/投影定向单测**

Run:

```bash
mvn -pl backend -Dtest=TaskNodeViewAssemblerTest,TaskReplayProjectionServiceTest,SearchAuditSnapshotCompatibilityTest test
```

Expected:

- All tests pass.

- [ ] **Step 3: 跑 backend 全量测试**

Run:

```bash
mvn -pl backend test
```

Expected:

- All tests pass.

- [ ] **Step 4: 重启 9093 并跑 plan12 e2e 9a**

Run:

```bash
tmp\run-backend-9093.cmd
```

用 9a 请求重新创建任务，保存产物到：

```text
tmp/task12-9a-YYYYMMDD-HHMMSS
```

数据库核验：

```sql
select count(*) evidence_rows,
       count(distinct url) distinct_urls,
       count(distinct source_domain) distinct_domains,
       count(*) filter (where length(coalesce(full_content,'')) > 2000) full_content_gt_2000
from evidence_source
where task_id = :taskId;
```

9a 采集层通过标准：

- `evidence_rows >= 3`
- `distinct_urls >= 3`
- `distinct_domains >= 3`
- 至少 1 个非官方/非用户输入根域名
- `full_content_gt_2000 >= 2`
- field query executed > 1，或者 trace 明确显示 bootstrap 已满足多个强正文候选且 field query 因覆盖足够而跳过
- 无重复 canonical URL
- `candidateVerificationDirectAttemptCount` 明显低于本轮 task80 的 20/21 次

- [ ] **Step 5: 跑 plan12 e2e 9b**

9b 重点看：

- search-first family 不退化回 `OFFICIAL_DOCS + includeDomains`。
- 第三方 STRONG 候选可进入 `evidence_source`。
- 质量闭环失败时，失败原因不再是“只有 1 条/正文太短/来源太薄”。

- [ ] **Step 6: 保存问题总结**

只总结真实暴露问题，不把未解决项藏到“后续优化”：

```text
1. Tavily 是否在主搜索阶段执行？
2. direct seed 是否只作为 anchor？
3. selectedTargets 是否保留多个强正文？
4. verification attempt 是否下降？
5. evidence_source 是否达到 9a/9b 采集指标？
6. 如果仍失败，失败是在采集、分析、撰写还是质检？
```

---

## 12. Commit Plan

建议分 4 个提交，便于回滚和审查：

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlannerTest.java \
        backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java \
        backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java \
        backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java \
        backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java
git commit -m "test: cover search-first candidate fusion before verification"
```

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionPlanner.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/SearchCandidateFusionDecision.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlanner.java
git commit -m "feat: add search-first candidate fusion policy"
```

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java \
        backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java
git commit -m "feat: verify fused search candidates before collection selection"
```

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java \
        backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java
git commit -m "fix: use bumped timeout for field evidence execution deadline"
```

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorSelectedTargetSummary.java \
        backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java \
        backend/src/test/java/cn/bugstack/competitoragent/task/TaskNodeViewAssemblerTest.java
git commit -m "chore: expose selected target search-first audit fields"
```

---

## 13. Self-Review Checklist

- [ ] 计划没有把“调大 `maxSearchResults`”当成主修法。
- [ ] direct discovery 被保留为 anchor/seed，而不是被删除。
- [ ] Tavily 主搜索发生在验证前，不再只等 supplement。
- [ ] effective target count 和 configured `maxSearchResults` 被拆开。
- [ ] fast lane 正文不占浏览器验证预算。
- [ ] field query executable quota 仍按 base timeout 控制，但 execution deadline 使用 bumped timeout。
- [ ] verified 短壳页不会压过 STRONG prefetched 正文。
- [ ] selected target 审计能看见 Tavily query mode、quality tier、正文长度。
- [ ] 9a/9b 验收包含采集层 KPI，而不是只看最终报告状态。
