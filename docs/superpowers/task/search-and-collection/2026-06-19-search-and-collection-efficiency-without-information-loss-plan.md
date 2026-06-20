# Search And Collection Efficiency Without Information Loss Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不减少搜索候选、正式采集页面和可分析证据量的前提下，降低真实搜索与采集链路的总耗时。

**Architecture:** 本计划不通过“少搜、少采、少递归”提速，而是消除串行等待、重复打开同一 URL、搜索验证与正式采集重复 Playwright 渲染、以及缺少耗时观测导致的盲调。整体策略是先补全耗时指标，再并发化候选验证，再把搜索阶段预抓取页复用到 collection coordinator，最后让 collection 队列在可控并发下执行，同时保持 sourceUrls、audit、replay 和 checkpoint 语义完整。

**Tech Stack:** Spring Boot, Java 17, Java `CompletableFuture` / `ExecutorService`, JUnit 5, Mockito, AssertJ, Jackson, Playwright Java.

---

## Problem Statement

当前真实任务慢，不是因为搜索结果太多本身，而是因为：

- `CandidateVerifier` 对候选 URL 串行验证，每个候选最多 3 次抓取，遇到 Playwright fallback 时耗时会线性叠加。
- 搜索验证阶段已经得到 `SearchCollectionTarget.collectedPage`，但 `CollectionExecutionCoordinator` 只消费 `candidate`，导致正式采集可能再次打开同一个 URL。
- `CollectionExecutionCoordinator` 使用单线程 FIFO 队列执行入口页和内部发现页，多个互不依赖的页面不能并发采集。
- 缺少统一耗时指标，难以确认时间花在 Qianfan/SerpAPI、候选验证、页面采集、Playwright fallback 还是内部链接递归。
- 真实冒烟只能观察最终是否超时，不能稳定比较“信息量不变时是否变快”。

本计划保留信息量边界：

- 不降低 `sourceCandidates` 数量。
- 不降低 `selectedTargets` 数量。
- 不降低默认内部发现深度和数量预算。
- 不删除 pypi、B 站专栏等候选，只避免重复采和串行等。
- 不移除 Playwright fallback，只减少重复 Playwright 和等待叠加。

## Scope Clarification

本计划纳入“单次任务内”的效率优化：

- 候选验证并发化。
- 候选验证 Direct-first 分层验证。
- 搜索验证页复用到正式 collection。
- Collection 同层任务并发执行。
- 搜索与采集耗时指标补全。

本计划不纳入“跨任务缓存”。跨任务缓存需要独立设计缓存存储、TTL、失效策略、隐私边界、sourceUrls 审计留痕和手动刷新入口；把它塞进本计划会扩大风险面。缓存可以作为后续单独计划推进。

## File Structure

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java`
  - 新增候选验证并发配置和验证耗时观测开关。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerificationResult.java`
  - 增加验证批次耗时、并发数、复用数等统计字段。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
  - 增加 search 阶段耗时拆分字段。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
  - 先接入 DirectHtmlReaderClient 做正向捷径验证，再将候选验证从串行改为有界并发，保留原顺序输出和 retry 语义。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/DirectHtmlReaderClient.java`
  - 作为候选验证的第一层轻量读取能力复用，不改变其 collection 主职责。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
  - 支持复用 `SearchCollectionTarget.collectedPage` 生成正式 `CollectionExecutionResult`，并支持同层任务有界并发。
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionProperties.java`
  - 承载 collection 并发、预抓取页复用、耗时观测配置。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java`
  - 增加执行统计对象，便于真实冒烟比较。
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionStats.java`
  - 汇总总耗时、执行包数量、预抓取复用数量、并发配置、平均耗时等。
- Modify: `backend/src/main/resources/application.yml`
  - 增加默认并发和复用配置，默认保持保守。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
  - 增加并发验证、顺序稳定、失败不拖垮批次的单测。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
  - 断言 trace 包含验证耗时与并发统计。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`
  - 增加预抓取页复用和 collection 并发测试。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
  - 增加新配置绑定测试。
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/BilibiliNameOnlyRealSmokeTest.java`
  - 增加耗时与信息量指标输出，证明没有少搜少采。

## Acceptance Criteria

- 候选验证默认保留所有输入候选，不因为并发改造减少候选数量。
- `CandidateVerifier.verify(...)` 输出顺序与输入候选去重后的顺序一致。
- 候选验证并发上限可配置，默认 `3`，可在测试环境调低到 `1` 回退串行。
- 候选验证必须先尝试 DirectHtmlReader 轻量读取；Direct 只允许作为“正向捷径”，即 Direct 命中且验证通过时跳过 Playwright，Direct 失败或 Direct 内容未命中目标信号时必须继续原 `sourceCollector.collect()` 验证路径，避免误伤信息量。
- 每个候选仍保留 try-catch 和最多 3 次有限重试。
- 搜索阶段 `attemptedTargets` 中已有可用 `collectedPage` 时，正式 collection 可以复用该页面并写入正式 collection audit。
- 复用预抓取页时，`sourceUrls`、`resourceLocator`、`qualitySignals`、`reusedFromCheckpoint`、`checkpointSource` 语义不混淆。
- Collection 队列支持同一 depth 的任务并发执行，但下一层 discovered candidates 仍在当前层完成后统一入队，避免递归边界混乱。
- 并发采集下 replay timeline 顺序稳定，按 `targetIndex` 输出。
- 搜索、验证、采集的耗时统计写入 trace/report，真实冒烟可直接比较。
- 真实 B 站冒烟中 `sourceCandidates`、`selectedTargets`、collection result 数量不低于优化前同等配置。
- 优化后不得通过降低 `maxDepth`、`maxLinksPerEntry`、`maxLinksPerNode`、`maxPagesPerCompetitor` 来获得速度收益。

---

## Task 1: Add Efficiency Configuration And Binding

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchBrowserProperties.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [x] **Step 1: Add search verification properties**

In `SearchBrowserProperties`, add:

```java
    /**
     * 候选页面验证的最大并发数。
     * 该配置只改变验证执行方式，不改变候选数量、重试次数和最终选源规则。
     */
    private int verificationConcurrency = 3;

    /**
     * 是否在搜索 trace 中记录候选验证耗时拆分。
     */
    private boolean verificationTimingEnabled = true;

    /**
     * 是否在候选验证阶段优先尝试 DirectHtmlReader。
     * Direct 只做正向捷径，不负责负向淘汰；Direct 不可用或未命中时仍继续原验证链路。
     */
    private boolean verificationDirectFirstEnabled = true;

    /**
     * Direct 验证命中并通过规则判断时，是否直接复用该页面并跳过浏览器验证。
     */
    private boolean verificationDirectPositiveShortcutEnabled = true;
```

- [x] **Step 2: Create collection execution properties**

Create `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionProperties.java`:

```java
package cn.bugstack.competitoragent.collection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Collection 执行效率配置。
 * 这些配置只控制执行方式和复用策略，不改变默认采集深度、页面数量和 sourceUrls 追溯语义。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.execution")
public class CollectionExecutionProperties {

    /**
     * 是否复用搜索验证阶段已经抓到的页面快照。
     * 开启后可以避免同一 URL 在 search verification 与正式 collection 中重复 Playwright 渲染。
     */
    private boolean reusePrefetchedPage = true;

    /**
     * Collection 同一层级任务的最大并发数。
     * 默认 1 表示保持现有串行行为；调大后只并发同一 depth 的独立页面。
     */
    private int concurrency = 1;

    /**
     * 是否在 CollectionExecutionReport 中写入耗时统计。
     */
    private boolean timingEnabled = true;
}
```

- [x] **Step 3: Add YAML defaults**

In `backend/src/main/resources/application.yml`, under `search.browser`, add:

```yaml
    verification-concurrency: 3
    verification-timing-enabled: true
    verification-direct-first-enabled: true
    verification-direct-positive-shortcut-enabled: true
```

Under `collection`, add:

```yaml
  execution:
    reuse-prefetched-page: true
    concurrency: 1
    timing-enabled: true
```

- [x] **Step 4: Add binding assertions**

In `SearchPropertiesBindingTest`, import:

```java
import cn.bugstack.competitoragent.collection.CollectionExecutionProperties;
```

Add test properties to the context runner:

```java
"search.browser.verification-concurrency=4",
"search.browser.verification-timing-enabled=true",
"search.browser.verification-direct-first-enabled=true",
"search.browser.verification-direct-positive-shortcut-enabled=true",
"collection.execution.reuse-prefetched-page=true",
"collection.execution.concurrency=3",
"collection.execution.timing-enabled=true",
```

Add assertions:

```java
SearchBrowserProperties browserProperties = context.getBean(SearchBrowserProperties.class);
CollectionExecutionProperties collectionExecutionProperties = context.getBean(CollectionExecutionProperties.class);

assertThat(browserProperties.getVerificationConcurrency()).isEqualTo(4);
assertThat(browserProperties.isVerificationTimingEnabled()).isTrue();
assertThat(browserProperties.isVerificationDirectFirstEnabled()).isTrue();
assertThat(browserProperties.isVerificationDirectPositiveShortcutEnabled()).isTrue();
assertThat(collectionExecutionProperties.isReusePrefetchedPage()).isTrue();
assertThat(collectionExecutionProperties.getConcurrency()).isEqualTo(3);
assertThat(collectionExecutionProperties.isTimingEnabled()).isTrue();
```

If the test uses `@EnableConfigurationProperties`, add `CollectionExecutionProperties.class`.

- [x] **Step 5: Run binding test**

Run:

```bash
mvn -pl backend "-Dtest=SearchPropertiesBindingTest#shouldBindSearchEngineAndSerpApiProperties" test
```

Expected: PASS.

---

## Task 2: Add Search Verification Timing Stats

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerificationResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [x] **Step 1: Add fields to CandidateVerificationResult**

Add:

```java
    private Integer inputCandidateCount;
    private Integer uniqueCandidateCount;
    private Integer attemptedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer reusedCollectedPageCount;
    private Integer directVerificationAttemptCount;
    private Integer directVerificationUsableCount;
    private Integer directVerificationShortcutCount;
    private Integer verificationConcurrency;
    private Long verificationElapsedMillis;
```

- [x] **Step 2: Add fields to SearchExecutionTrace**

Add:

```java
    private Long candidateVerificationElapsedMillis;
    private Integer candidateVerificationConcurrency;
    private Integer candidateVerificationInputCount;
    private Integer candidateVerificationUniqueCount;
    private Integer candidateVerificationReusedPageCount;
    private Integer candidateVerificationDirectAttemptCount;
    private Integer candidateVerificationDirectUsableCount;
    private Integer candidateVerificationDirectShortcutCount;
```

- [x] **Step 3: Populate serial timing in CandidateVerifier**

Before changing concurrency, update current serial `verify(...)` to measure timing:

```java
long startedAt = System.currentTimeMillis();
List<SourceCandidate> uniqueCandidates = deduplicateCandidates(candidates);
```

When building the result, include:

```java
.inputCandidateCount(candidates == null ? 0 : candidates.size())
.uniqueCandidateCount(uniqueCandidates.size())
.attemptedCandidateCount(attemptedTargets.size())
.verifiedCandidateCount(verifiedTargets.size())
.reusedCollectedPageCount(0)
.directVerificationAttemptCount(0)
.directVerificationUsableCount(0)
.directVerificationShortcutCount(0)
.verificationConcurrency(1)
.verificationElapsedMillis(Math.max(0L, System.currentTimeMillis() - startedAt))
```

- [x] **Step 4: Propagate stats into SearchExecutionTrace**

In `SearchExecutionCoordinator`, after candidate verification, keep the latest aggregate values and write them into `SearchExecutionTrace.builder()`:

```java
.candidateVerificationElapsedMillis(totalVerificationElapsedMillis)
.candidateVerificationConcurrency(maxVerificationConcurrency)
.candidateVerificationInputCount(totalVerificationInputCount)
.candidateVerificationUniqueCount(totalVerificationUniqueCount)
.candidateVerificationReusedPageCount(totalVerificationReusedPageCount)
.candidateVerificationDirectAttemptCount(totalDirectVerificationAttemptCount)
.candidateVerificationDirectUsableCount(totalDirectVerificationUsableCount)
.candidateVerificationDirectShortcutCount(totalDirectVerificationShortcutCount)
```

Use additive totals when both planned verification and supplement verification run.

- [x] **Step 5: Add trace test**

In `SearchExecutionCoordinatorTest`, add or update a test to assert:

```java
SearchExecutionResult result = coordinator.execute(config);

assertThat(result.getExecutionTrace().getCandidateVerificationElapsedMillis()).isNotNull();
assertThat(result.getExecutionTrace().getCandidateVerificationInputCount()).isGreaterThanOrEqualTo(1);
assertThat(result.getExecutionTrace().getCandidateVerificationUniqueCount()).isGreaterThanOrEqualTo(1);
assertThat(result.getExecutionTrace().getCandidateVerificationConcurrency()).isGreaterThanOrEqualTo(1);
```

- [x] **Step 6: Run trace tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest" test
```

Expected: PASS.

---

## Task 3A: Add Direct-First Positive Shortcut For Candidate Verification

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`

- [x] **Step 1: Inject DirectHtmlReaderClient into CandidateVerifier**

Add field:

```java
private final DirectHtmlReaderClient directHtmlReaderClient;
```

Add imports:

```java
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.collection.WebPageRenderHint;
```

Update the Spring constructor from Task 3 to include `ObjectProvider<DirectHtmlReaderClient>`:

```java
@Autowired
public CandidateVerifier(SourceCollector sourceCollector,
                         SearchKeywordPolicy searchKeywordPolicy,
                         CandidateOwnershipPolicy candidateOwnershipPolicy,
                         SearchBrowserProperties searchBrowserProperties,
                         ObjectProvider<DirectHtmlReaderClient> directHtmlReaderClientProvider) {
    this.sourceCollector = sourceCollector;
    this.searchKeywordPolicy = searchKeywordPolicy;
    this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
            ? new CandidateOwnershipPolicy()
            : candidateOwnershipPolicy;
    this.searchBrowserProperties = searchBrowserProperties == null
            ? new SearchBrowserProperties()
            : searchBrowserProperties;
    this.directHtmlReaderClient = directHtmlReaderClientProvider == null
            ? null
            : directHtmlReaderClientProvider.getIfAvailable();
}
```

Import:

```java
import org.springframework.beans.factory.ObjectProvider;
```

Keep test constructors by delegating with `null` provider:

```java
public CandidateVerifier(SourceCollector sourceCollector,
                         SearchKeywordPolicy searchKeywordPolicy,
                         CandidateOwnershipPolicy candidateOwnershipPolicy,
                         SearchBrowserProperties searchBrowserProperties) {
    this(sourceCollector, searchKeywordPolicy, candidateOwnershipPolicy, searchBrowserProperties, null);
}
```

- [x] **Step 2: Convert Direct result to CollectedPage**

Add helper:

```java
private SourceCollector.CollectedPage toCollectedPage(SourceCandidate candidate,
                                                      String competitorName,
                                                      String sourceType,
                                                      PageContentExtractionResult directResult) {
    if (candidate == null || directResult == null || !directResult.isUsable()) {
        return null;
    }
    String content = directResult.getMainContent();
    if (!StringUtils.hasText(content)) {
        return null;
    }
    return SourceCollector.CollectedPage.builder()
            .url(candidate.getUrl())
            .title(StringUtils.hasText(directResult.getTitle()) ? directResult.getTitle() : candidate.getTitle())
            .content(content)
            .snippet(content.length() > 500 ? content.substring(0, 500) : content)
            .competitorName(competitorName)
            .sourceType(sourceType)
            .success(true)
            .metadata("{\"collector\":\"direct-html-verification\",\"qualitySignals\":[\"DIRECT_HTML_VERIFICATION_READY\"]}")
            .build();
}
```

- [x] **Step 3: Add Direct attempt helper**

Add:

```java
private SourceCollector.CollectedPage collectByDirectForPositiveShortcut(SourceCandidate candidate,
                                                                         String competitorName,
                                                                         String sourceType) {
    if (directHtmlReaderClient == null
            || !searchBrowserProperties.isVerificationDirectFirstEnabled()
            || candidate == null
            || !StringUtils.hasText(candidate.getUrl())) {
        return null;
    }
    try {
        PageContentExtractionResult directResult = directHtmlReaderClient.collect(SourceCollectRequest.builder()
                .url(candidate.getUrl())
                .competitorName(competitorName)
                .sourceType(sourceType)
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .sourceUrls(resolveAttemptUrls(candidate))
                .build());
        return toCollectedPage(candidate, competitorName, sourceType, directResult);
    } catch (RuntimeException exception) {
        return null;
    }
}
```

The catch must not fail the candidate. Direct is an optimization layer only.

- [x] **Step 4: Use Direct only as positive shortcut**

At the start of `verifyOneCandidate(...)`, do:

```java
SourceCollector.CollectedPage directPage = collectByDirectForPositiveShortcut(candidate, competitorName, sourceType);
if (directPage != null) {
    List<String> directMatchedSignals = collectMatchedSignals(candidate, directPage, sourceType);
    boolean directMarketingPage = isMarketingLandingPage(directPage, sourceType);
    boolean directRejectedMediator = candidateOwnershipPolicy.isRejectedMediator(candidate, directPage);
    boolean directOwnershipMatched = !candidateOwnershipPolicy.shouldRequireOwnershipValidation(candidate, sourceType)
            || candidateOwnershipPolicy.hasCompetitorOwnershipSignal(competitorName, candidate, directPage);
    boolean directVerified = isVerified(
            directPage,
            directMatchedSignals,
            directMarketingPage,
            directRejectedMediator,
            directOwnershipMatched
    );
    if (directVerified && searchBrowserProperties.isVerificationDirectPositiveShortcutEnabled()) {
        return buildVerificationTarget(
                candidate,
                directPage,
                sourceType,
                directMatchedSignals,
                directMarketingPage,
                directRejectedMediator,
                directOwnershipMatched,
                "DIRECT_HTML_VERIFICATION_SHORTCUT"
        );
    }
}
```

Then continue to the original `collectWithRetry(...)` path. Direct failing, Direct thin content, or Direct content that does not prove relevance must not discard the candidate.

Extract the repeated target-building logic from Task 3 into:

```java
private SearchCollectionTarget buildVerificationTarget(SourceCandidate candidate,
                                                       SourceCollector.CollectedPage page,
                                                       String sourceType,
                                                       List<String> matchedSignals,
                                                       boolean marketingPage,
                                                       boolean rejectedMediator,
                                                       boolean ownershipMatched,
                                                       String extraQualitySignal) {
    boolean verified = isVerified(page, matchedSignals, marketingPage, rejectedMediator, ownershipMatched);
    String verificationReason = buildVerificationReason(
            page,
            sourceType,
            matchedSignals,
            verified,
            marketingPage,
            rejectedMediator,
            ownershipMatched
    );
    List<String> qualitySignals = new ArrayList<>();
    if (candidate.getQualitySignals() != null) {
        qualitySignals.addAll(candidate.getQualitySignals());
    }
    if (StringUtils.hasText(extraQualitySignal)) {
        qualitySignals.add(extraQualitySignal);
    }
    SourceCandidate updatedCandidate = candidate.toBuilder()
            .verified(verified)
            .verificationReason(verificationReason)
            .matchedSignals(matchedSignals)
            .qualitySignals(qualitySignals)
            .selectionStage(verified ? "VERIFIED" : "DISCARDED")
            .selectionReason(verified ? "运行期验证通过，允许直接进入正式采集" : "运行期验证未通过，降级为候选兜底")
            .build();
    return SearchCollectionTarget.builder()
            .candidate(updatedCandidate)
            .collectedPage(page)
            .build();
}
```

- [x] **Step 5: Track Direct counters**

Add local counters in `verify(...)`:

```java
AtomicInteger directAttemptCount = new AtomicInteger();
AtomicInteger directUsableCount = new AtomicInteger();
AtomicInteger directShortcutCount = new AtomicInteger();
```

Increment:

- before calling `directHtmlReaderClient.collect(...)`: `directAttemptCount.incrementAndGet()`
- when `toCollectedPage(...)` returns non-null: `directUsableCount.incrementAndGet()`
- when direct shortcut returns verified target: `directShortcutCount.incrementAndGet()`

Add imports:

```java
import java.util.concurrent.atomic.AtomicInteger;
```

Populate `CandidateVerificationResult`:

```java
.directVerificationAttemptCount(directAttemptCount.get())
.directVerificationUsableCount(directUsableCount.get())
.directVerificationShortcutCount(directShortcutCount.get())
```

- [x] **Step 6: Add positive shortcut test**

In `CandidateVerifierTest`, add:

```java
@Test
void shouldUseDirectHtmlAsPositiveShortcutBeforePlaywrightVerification() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setVerificationDirectFirstEnabled(true);
    properties.setVerificationDirectPositiveShortcutEnabled(true);
    when(directHtmlReaderClient.collect(any(SourceCollectRequest.class))).thenReturn(PageContentExtractionResult.builder()
            .success(true)
            .title("Open API Docs")
            .mainContent("Open API 文档 OAuth SDK guide reference")
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
            .qualityScore(0.86D)
            .build());

    CandidateVerifier verifier = new CandidateVerifier(
            sourceCollector,
            new SearchKeywordPolicy(),
            new CandidateOwnershipPolicy(),
            properties,
            () -> directHtmlReaderClient
    );

    CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
            SourceCandidate.builder().url("https://docs.example.com/a").sourceType("DOCS").domain("docs.example.com").build()
    ));

    assertThat(result.getVerifiedTargets()).hasSize(1);
    assertThat(result.getDirectVerificationAttemptCount()).isEqualTo(1);
    assertThat(result.getDirectVerificationUsableCount()).isEqualTo(1);
    assertThat(result.getDirectVerificationShortcutCount()).isEqualTo(1);
    assertThat(result.getVerifiedTargets().get(0).getCandidate().getQualitySignals())
            .contains("DIRECT_HTML_VERIFICATION_SHORTCUT");
    verify(sourceCollector, never()).collect(anyString(), anyString(), anyString());
}
```

- [x] **Step 7: Add Direct negative-falls-through test**

Add:

```java
@Test
void shouldFallThroughToCollectorWhenDirectHtmlDoesNotProveRelevance() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setVerificationDirectFirstEnabled(true);
    when(directHtmlReaderClient.collect(any(SourceCollectRequest.class))).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("CONTENT_UNUSABLE")
            .errorMessage("direct html content too thin")
            .qualitySignals(List.of("DIRECT_HTML_CONTENT_TOO_THIN"))
            .build());
    when(sourceCollector.collect(anyString(), anyString(), anyString())).thenReturn(SourceCollector.CollectedPage.builder()
            .title("Open API Docs")
            .content("Open API 文档 OAuth SDK guide reference")
            .snippet("Open API 文档")
            .success(true)
            .build());

    CandidateVerifier verifier = new CandidateVerifier(
            sourceCollector,
            new SearchKeywordPolicy(),
            new CandidateOwnershipPolicy(),
            properties,
            () -> directHtmlReaderClient
    );

    CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
            SourceCandidate.builder().url("https://docs.example.com/a").sourceType("DOCS").domain("docs.example.com").build()
    ));

    assertThat(result.getVerifiedTargets()).hasSize(1);
    assertThat(result.getDirectVerificationAttemptCount()).isEqualTo(1);
    assertThat(result.getDirectVerificationShortcutCount()).isEqualTo(0);
    verify(sourceCollector, times(1)).collect(anyString(), anyString(), anyString());
}
```

- [x] **Step 8: Run Direct-first verifier tests**

Run:

```bash
mvn -pl backend "-Dtest=CandidateVerifierTest#shouldUseDirectHtmlAsPositiveShortcutBeforePlaywrightVerification,CandidateVerifierTest#shouldFallThroughToCollectorWhenDirectHtmlDoesNotProveRelevance" test
```

Expected: PASS.

---

## Task 3: Make Candidate Verification Bounded Concurrent Without Losing Results

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`

- [x] **Step 1: Confirm SearchBrowserProperties is available**

Task 3A already introduces `SearchBrowserProperties` into `CandidateVerifier`. Before continuing, confirm the class has:

```java
private final SearchBrowserProperties searchBrowserProperties;
```

If Task 3A was skipped, implement its constructor changes first. Do not add a second competing constructor.

- [x] **Step 2: Reuse one-candidate verification method**

Task 3A already extracts:

```java
private SearchCollectionTarget verifyOneCandidate(SourceCandidate candidate,
                                                  String competitorName,
                                                  String sourceType)
```

This method must keep Direct-first positive shortcut logic and fall through to `collectWithRetry(...)` when Direct is not enough.

- [x] **Step 3: Replace serial loop with bounded executor**

In `verify(...)`, use:

```java
int concurrency = Math.max(1, searchBrowserProperties.getVerificationConcurrency());
ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, uniqueCandidates.size()));
try {
    List<CompletableFuture<SearchCollectionTarget>> futures = uniqueCandidates.stream()
            .map(candidate -> CompletableFuture.supplyAsync(
                    () -> verifyOneCandidate(candidate, competitorName, sourceType),
                    executor
            ))
            .toList();

    for (CompletableFuture<SearchCollectionTarget> future : futures) {
        SearchCollectionTarget target = future.join();
        SourceCandidate updatedCandidate = target.getCandidate();
        updatedCandidates.add(updatedCandidate);
        attemptedTargets.add(target);
        if (updatedCandidate != null && Boolean.TRUE.equals(updatedCandidate.getVerified())) {
            verifiedTargets.add(target);
        }
    }
} finally {
    executor.shutdownNow();
}
```

Add imports:

```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

The loop over `futures` must remain in original candidate order to keep output stable.

When populating `CandidateVerificationResult`, keep the Direct counters introduced in Task 3A.

- [x] **Step 4: Add concurrent timing test**

In `CandidateVerifierTest`, add:

```java
@Test
void shouldVerifyCandidatesConcurrentlyWithoutDroppingResults() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setVerificationConcurrency(3);
    when(sourceCollector.collect(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
        Thread.sleep(200);
        String url = invocation.getArgument(0);
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .title("Open API Docs")
                .content("Open API 文档 OAuth SDK guide reference")
                .snippet("Open API 文档")
                .success(true)
                .build();
    });

    CandidateVerifier verifier = new CandidateVerifier(
            sourceCollector,
            new SearchKeywordPolicy(),
            new CandidateOwnershipPolicy(),
            properties
    );

    long startedAt = System.currentTimeMillis();
    CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
            SourceCandidate.builder().url("https://docs.example.com/a").sourceType("DOCS").domain("docs.example.com").build(),
            SourceCandidate.builder().url("https://docs.example.com/b").sourceType("DOCS").domain("docs.example.com").build(),
            SourceCandidate.builder().url("https://docs.example.com/c").sourceType("DOCS").domain("docs.example.com").build()
    ));
    long elapsedMillis = System.currentTimeMillis() - startedAt;

    assertThat(result.getAttemptedTargets()).hasSize(3);
    assertThat(result.getUpdatedCandidates()).extracting(SourceCandidate::getUrl)
            .containsExactly("https://docs.example.com/a", "https://docs.example.com/b", "https://docs.example.com/c");
    assertThat(result.getVerificationConcurrency()).isEqualTo(3);
    assertThat(elapsedMillis).isLessThan(550L);
}
```

- [x] **Step 5: Add fallback-to-serial test**

Add:

```java
@Test
void shouldUseSerialVerificationWhenConcurrencyIsOne() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    SearchBrowserProperties properties = new SearchBrowserProperties();
    properties.setVerificationConcurrency(1);
    when(sourceCollector.collect(anyString(), anyString(), anyString())).thenReturn(SourceCollector.CollectedPage.builder()
            .title("Open API Docs")
            .content("Open API 文档 OAuth SDK guide reference")
            .snippet("Open API 文档")
            .success(true)
            .build());

    CandidateVerifier verifier = new CandidateVerifier(
            sourceCollector,
            new SearchKeywordPolicy(),
            new CandidateOwnershipPolicy(),
            properties
    );

    CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
            SourceCandidate.builder().url("https://docs.example.com/a").sourceType("DOCS").domain("docs.example.com").build()
    ));

    assertThat(result.getVerificationConcurrency()).isEqualTo(1);
    assertThat(result.getAttemptedTargets()).hasSize(1);
}
```

- [x] **Step 6: Run candidate verifier tests**

Run:

```bash
mvn -pl backend "-Dtest=CandidateVerifierTest" test
```

Expected: PASS.

---

## Task 4: Reuse Search Verification Pages In Collection Coordinator

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`

- [x] **Step 1: Extend queued task to carry prefetched page**

Change the record:

```java
private record QueuedCollectionTask(SourceCandidate candidate,
                                    SourceCollector.CollectedPage prefetchedPage,
                                    int targetIndex,
                                    int discoveryDepth,
                                    String entryKey,
                                    boolean discovered) {
}
```

Import:

```java
import cn.bugstack.competitoragent.source.SourceCollector;
```

When enqueueing original selected targets, pass `target.getCollectedPage()`.

When enqueueing discovered child candidates, pass `null`.

- [x] **Step 2: Inject CollectionExecutionProperties**

Add field:

```java
private final CollectionExecutionProperties collectionExecutionProperties;
```

Update constructors to accept it, defaulting to `new CollectionExecutionProperties()` when null.

- [x] **Step 3: Convert prefetched page to CollectionExecutionResult**

Add helper:

```java
private CollectionExecutionResult buildPrefetchedResult(CollectionTaskPackage taskPackage,
                                                        SourceCollector.CollectedPage page) {
    if (taskPackage == null || page == null || !page.isSuccess()) {
        return null;
    }
    String content = StringUtils.hasText(page.getContent()) ? page.getContent() : page.getSnippet();
    if (!StringUtils.hasText(content)) {
        return null;
    }
    return CollectionExecutionResult.builder()
            .taskPackageKey(taskPackage.getPackageKey())
            .targetIndex(taskPackage.getTargetIndex())
            .executorType("WEB_PAGE")
            .success(true)
            .status("SUCCESS")
            .resourceLocator(StringUtils.hasText(page.getUrl()) ? page.getUrl() : taskPackage.getResourceLocator())
            .title(page.getTitle())
            .content(content)
            .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
            .qualitySignals(List.of("SEARCH_VERIFICATION_PAGE_REUSED"))
            .qualityScore(0.72D)
            .structuredBlocks(List.of())
            .collectedAt(Instant.now())
            .durationMillis(0L)
            .checkpointSource("searchVerification")
            .reusedFromCheckpoint(false)
            .build()
            .normalize();
}
```

- [x] **Step 4: Use prefetched result before executor**

In the queue loop:

```java
if (reusedResult != null) {
    executionResult = markReused(reusedResult, taskPackage);
} else if (collectionExecutionProperties.isReusePrefetchedPage()) {
    CollectionExecutionResult prefetchedResult = buildPrefetchedResult(taskPackage, queuedTask.prefetchedPage());
    executionResult = prefetchedResult == null ? executeTaskPackage(taskPackage) : prefetchedResult;
} else {
    executionResult = executeTaskPackage(taskPackage);
}
```

- [x] **Step 5: Add reuse test**

In `CollectionExecutionCoordinatorTest`, add:

```java
@Test
void shouldReusePrefetchedSearchVerificationPageWithoutCallingExecutor() {
    CollectionExecutor executor = mock(CollectionExecutor.class);
    when(executor.supports(any())).thenReturn(true);
    CollectionExecutionProperties properties = new CollectionExecutionProperties();
    properties.setReusePrefetchedPage(true);

    CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
            new CollectionTaskPackageBuilder(),
            new CollectionExecutorRegistry(List.of(executor)),
            new CanonicalUrlResolver(),
            new InternalLinkDiscoveryProperties(),
            properties
    );

    SearchCollectionTarget target = SearchCollectionTarget.builder()
            .candidate(SourceCandidate.builder()
                    .url("https://docs.example.com/open/doc")
                    .title("Open Docs")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .sourceUrls(List.of("https://docs.example.com/open/doc"))
                    .build())
            .collectedPage(SourceCollector.CollectedPage.builder()
                    .url("https://docs.example.com/open/doc")
                    .title("Open Docs")
                    .content("Open API 文档 OAuth SDK guide reference")
                    .success(true)
                    .build())
            .build();

    CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", List.of(target));

    assertThat(report.getResults()).hasSize(1);
    assertThat(report.getResults().get(0).getQualitySignals()).contains("SEARCH_VERIFICATION_PAGE_REUSED");
    assertThat(report.getResults().get(0).getContent()).contains("Open API 文档");
    verify(executor, never()).execute(any());
}
```

- [x] **Step 6: Add opt-out test**

Add:

```java
@Test
void shouldCallExecutorWhenPrefetchedReuseIsDisabled() {
    CollectionExecutor executor = mock(CollectionExecutor.class);
    when(executor.supports(any())).thenReturn(true);
    when(executor.execute(any())).thenReturn(CollectionExecutionResult.builder()
            .executorType("WEB_PAGE")
            .success(true)
            .status("SUCCESS")
            .resourceLocator("https://docs.example.com/open/doc")
            .content("fresh collection result")
            .sourceUrls(List.of("https://docs.example.com/open/doc"))
            .build());
    CollectionExecutionProperties properties = new CollectionExecutionProperties();
    properties.setReusePrefetchedPage(false);

    CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
            new CollectionTaskPackageBuilder(),
            new CollectionExecutorRegistry(List.of(executor)),
            new CanonicalUrlResolver(),
            new InternalLinkDiscoveryProperties(),
            properties
    );

    SearchCollectionTarget target = SearchCollectionTarget.builder()
            .candidate(SourceCandidate.builder()
                    .url("https://docs.example.com/open/doc")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .sourceUrls(List.of("https://docs.example.com/open/doc"))
                    .build())
            .collectedPage(SourceCollector.CollectedPage.builder()
                    .url("https://docs.example.com/open/doc")
                    .content("prefetched")
                    .success(true)
                    .build())
            .build();

    CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", List.of(target));

    assertThat(report.getResults().get(0).getContent()).isEqualTo("fresh collection result");
    verify(executor, times(1)).execute(any());
}
```

- [x] **Step 7: Run coordinator tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectionExecutionCoordinatorTest" test
```

Expected: PASS.

---

## Task 5: Add Collection Execution Stats

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionStats.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`

- [x] **Step 1: Create stats DTO**

Create:

```java
package cn.bugstack.competitoragent.collection;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Collection 执行耗时统计。
 * 用于验证效率优化是否来自复用与并发，而不是减少页面数量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionExecutionStats {

    private Integer totalPackageCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer prefetchedReuseCount;
    private Integer checkpointReuseCount;
    private Integer executorCallCount;
    private Integer configuredConcurrency;
    private Long elapsedMillis;
}
```

- [x] **Step 2: Add stats to report**

In `CollectionExecutionReport`, add:

```java
private CollectionExecutionStats stats;
```

- [x] **Step 3: Track stats in coordinator**

In `execute(...)`, initialize counters:

```java
long startedAt = System.currentTimeMillis();
int prefetchedReuseCount = 0;
int checkpointReuseCount = 0;
int executorCallCount = 0;
```

Increment counters when:

- `markReused(...)` is used: `checkpointReuseCount++`
- `buildPrefetchedResult(...)` returns non-null: `prefetchedReuseCount++`
- `executeTaskPackage(...)` is called: `executorCallCount++`

Pass stats into `buildReport(results, stats)`.

- [x] **Step 4: Build stats in report**

Overload `buildReport`:

```java
private CollectionExecutionReport buildReport(List<CollectionExecutionResult> results,
                                              CollectionExecutionStats stats) {
    CollectionExecutionReport report = buildReport(results);
    return report.toBuilder().stats(stats).build();
}
```

If `CollectionExecutionReport` does not currently use `@Builder(toBuilder = true)`, change it to:

```java
@Builder(toBuilder = true)
```

- [x] **Step 5: Add stats assertion**

In `CollectionExecutionCoordinatorTest`, extend the prefetched reuse test:

```java
assertThat(report.getStats().getTotalPackageCount()).isEqualTo(1);
assertThat(report.getStats().getPrefetchedReuseCount()).isEqualTo(1);
assertThat(report.getStats().getExecutorCallCount()).isEqualTo(0);
assertThat(report.getStats().getElapsedMillis()).isNotNull();
```

- [x] **Step 6: Run stats tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectionExecutionCoordinatorTest,CollectionAuditSerializationTest" test
```

Expected: PASS.

---

## Task 6: Parallelize Same-Depth Collection Tasks

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`

- [x] **Step 1: Split collection queue into same-depth batches**

Replace the single `while (!queue.isEmpty())` poll loop with a loop that drains tasks of the current depth:

```java
while (!queue.isEmpty()) {
    int currentDepth = queue.peekFirst().discoveryDepth();
    List<QueuedCollectionTask> currentBatch = new ArrayList<>();
    while (!queue.isEmpty() && queue.peekFirst().discoveryDepth() == currentDepth) {
        currentBatch.add(queue.pollFirst());
    }

    List<CollectionExecutionResult> batchResults = executeBatch(...);
    results.addAll(batchResults);

    for (int index = 0; index < currentBatch.size(); index++) {
        QueuedCollectionTask queuedTask = currentBatch.get(index);
        CollectionExecutionResult executionResult = batchResults.get(index);
        enqueueDiscoveredCandidates(...);
    }
}
```

Keep discovered child enqueueing after the batch finishes so next-depth scheduling remains deterministic.

- [x] **Step 2: Add executeBatch helper**

Add:

```java
private List<CollectionExecutionResult> executeBatch(List<QueuedCollectionTask> batch,
                                                     Long taskId,
                                                     String nodeName,
                                                     Long planVersionId,
                                                     String competitorName,
                                                     Map<String, CollectionExecutionResult> checkpointResultMap,
                                                     Map<String, CollectionExecutionResult> checkpointIdentityMap,
                                                     Set<String> consumedCheckpointKeys,
                                                     MutableCollectionCounters counters) {
    int concurrency = Math.max(1, collectionExecutionProperties.getConcurrency());
    if (concurrency == 1 || batch.size() <= 1) {
        List<CollectionExecutionResult> batchResults = new ArrayList<>();
        for (QueuedCollectionTask task : batch) {
            batchResults.add(executeQueuedTask(...));
        }
        return batchResults;
    }
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, batch.size()));
    try {
        List<CompletableFuture<CollectionExecutionResult>> futures = batch.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> executeQueuedTask(...),
                        executor
                ))
                .toList();
        List<CollectionExecutionResult> batchResults = new ArrayList<>();
        for (CompletableFuture<CollectionExecutionResult> future : futures) {
            batchResults.add(future.join());
        }
        return batchResults;
    } finally {
        executor.shutdownNow();
    }
}
```

Use a small mutable counter class instead of `AtomicInteger` if all counter increments happen inside synchronized helper code. If increments happen inside concurrent lambdas, use `AtomicInteger`.

- [x] **Step 3: Keep replay order stable**

After `results.addAll(batchResults)`, sort only if necessary by target index:

```java
results.sort(Comparator.comparing(result -> result.getTargetIndex() == null ? Integer.MAX_VALUE : result.getTargetIndex()));
```

Do not sort before enqueueing discovered children; enqueue children using the original `currentBatch` order and matching `batchResults` index.

- [x] **Step 4: Add parallel collection test**

In `CollectionExecutionCoordinatorTest`, add:

```java
@Test
void shouldCollectSameDepthTargetsConcurrentlyWithoutChangingResultOrder() {
    CollectionExecutor executor = mock(CollectionExecutor.class);
    when(executor.supports(any())).thenReturn(true);
    when(executor.execute(any())).thenAnswer(invocation -> {
        CollectionTaskPackage taskPackage = invocation.getArgument(0);
        Thread.sleep(200);
        return CollectionExecutionResult.builder()
                .executorType("WEB_PAGE")
                .success(true)
                .status("SUCCESS")
                .resourceLocator(taskPackage.getResourceLocator())
                .content("content " + taskPackage.getTargetIndex())
                .sourceUrls(taskPackage.getSourceUrls())
                .build();
    });
    CollectionExecutionProperties properties = new CollectionExecutionProperties();
    properties.setConcurrency(3);

    CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
            new CollectionTaskPackageBuilder(),
            new CollectionExecutorRegistry(List.of(executor)),
            new CanonicalUrlResolver(),
            new InternalLinkDiscoveryProperties(),
            properties
    );

    List<SearchCollectionTarget> targets = List.of(
            target("https://docs.example.com/a"),
            target("https://docs.example.com/b"),
            target("https://docs.example.com/c")
    );

    long startedAt = System.currentTimeMillis();
    CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", targets);
    long elapsedMillis = System.currentTimeMillis() - startedAt;

    assertThat(report.getResults()).extracting(CollectionExecutionResult::getResourceLocator)
            .containsExactly("https://docs.example.com/a", "https://docs.example.com/b", "https://docs.example.com/c");
    assertThat(elapsedMillis).isLessThan(550L);
}
```

Add helper:

```java
private SearchCollectionTarget target(String url) {
    return SearchCollectionTarget.builder()
            .candidate(SourceCandidate.builder()
                    .url(url)
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .sourceUrls(List.of(url))
                    .build())
            .build();
}
```

- [x] **Step 5: Run collection coordinator tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectionExecutionCoordinatorTest,CollectionExecutionCoordinatorCheckpointReuseTest" test
```

Expected: PASS.

---

## Task 7: Preserve CollectorAgent Prefetch Reuse And Audit Semantics

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`

- [x] **Step 1: Add integration-style unit test for no duplicate collection**

In `CollectorAgentTest`, add a test around the existing path that receives `selectedTargets` with `collectedPage`. Assert that:

```java
verify(sourceCollector, never()).collect(eq("https://docs.example.com/prefetched"), anyString(), anyString());
```

The test should build a selected target:

```java
SearchCollectionTarget.builder()
        .candidate(SourceCandidate.builder()
                .url("https://docs.example.com/prefetched")
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .sourceUrls(List.of("https://docs.example.com/prefetched"))
                .build())
        .collectedPage(SourceCollector.CollectedPage.builder()
                .url("https://docs.example.com/prefetched")
                .title("Prefetched Docs")
                .content("Open API 文档 OAuth SDK guide reference")
                .success(true)
                .build())
        .build()
```

- [x] **Step 2: Ensure CollectorAgent does not duplicate coordinator results**

If `CollectorAgent` currently handles prefetched pages separately and then also sends the same target to `CollectionExecutionCoordinator`, keep the existing filter:

```java
.filter(target -> target != null && target.getCollectedPage() == null)
```

or equivalent logic. If it has diverged, restore the split:

```java
List<SearchCollectionTarget> prefetchedTargets = selectedTargets.stream()
        .filter(target -> target != null && target.getCollectedPage() != null)
        .toList();
List<SearchCollectionTarget> coordinatorTargets = selectedTargets.stream()
        .filter(target -> target != null && target.getCollectedPage() == null)
        .toList();
```

This task is a guardrail: after Task 4, collection coordinator can reuse prefetched pages, but CollectorAgent should not double count if it already handles them.

- [x] **Step 3: Run CollectorAgent tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectorAgentTest" test
```

Expected: PASS.

---

## Task 8: Add Real Smoke Efficiency Evidence Without Reducing Information

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/BilibiliNameOnlyRealSmokeTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/BilibiliOfficialDocsEntryCollectionRealSmokeTest.java`

- [x] **Step 1: Print search efficiency stats**

In both smoke tests, extend printed search output:

```java
"candidateVerificationElapsedMillis", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationElapsedMillis(),
"candidateVerificationConcurrency", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationConcurrency(),
"candidateVerificationInputCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationInputCount(),
"candidateVerificationUniqueCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationUniqueCount(),
"candidateVerificationReusedPageCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationReusedPageCount(),
"candidateVerificationDirectAttemptCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationDirectAttemptCount(),
"candidateVerificationDirectUsableCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationDirectUsableCount(),
"candidateVerificationDirectShortcutCount", searchResult.getExecutionTrace() == null ? null : searchResult.getExecutionTrace().getCandidateVerificationDirectShortcutCount(),
```

- [x] **Step 2: Print collection efficiency stats**

Extend collection output:

```java
"stats", collectionReport == null ? null : collectionReport.getStats(),
```

- [x] **Step 3: Assert information volume is preserved**

In full smoke, add assertions:

```java
assertThat(searchResult.getSourceCandidates())
        .as("efficiency changes must not reduce discovered candidate volume")
        .hasSizeGreaterThanOrEqualTo(5);
assertThat(selectedTargets)
        .as("efficiency changes must not reduce selected target volume")
        .hasSizeGreaterThanOrEqualTo(1);
```

Do not assert a fixed runtime in unit tests. Runtime is external-network dependent.

- [x] **Step 4: Run search-only smoke**

Run:

```powershell
$env:RUN_REAL_SMOKE='true'
$env:QIANFAN_API_KEY=[Environment]::GetEnvironmentVariable('QIANFAN_API_KEY','User')
$env:SERPAPI_API_KEY=[Environment]::GetEnvironmentVariable('SERPAPI_API_KEY','User')
mvn -pl backend "-Dtest=BilibiliNameOnlyRealSmokeTest#shouldLocateBilibiliDocsTargetsWithQianfanBeforeCollection" test
```

Expected:

```text
BUILD SUCCESS
selectedTargetCount >= 1
candidateVerificationConcurrency >= 1
candidateVerificationElapsedMillis is present
```

- [x] **Step 5: Run limited collection smoke**

Run:

```powershell
$env:RUN_REAL_SMOKE='true'
$env:QIANFAN_API_KEY=[Environment]::GetEnvironmentVariable('QIANFAN_API_KEY','User')
$env:SERPAPI_API_KEY=[Environment]::GetEnvironmentVariable('SERPAPI_API_KEY','User')
mvn -pl backend "-Dtest=BilibiliOfficialDocsEntryCollectionRealSmokeTest" test
```

Expected:

```text
BUILD SUCCESS
collectionReport.status=SUCCESS
collectionReport.stats is present
sourceUrls include the real open.bilibili.com URL
```

---

## Task 9: Verification Matrix

**Files:**
- No new production files beyond earlier tasks.

- [x] **Step 1: Run search unit/regression tests**

Run:

```bash
mvn -pl backend "-Dtest=CandidateVerifierTest,SearchExecutionCoordinatorTest,CollectionTargetSelectorTest,SearchPropertiesBindingTest" test
```

Expected: PASS.

- [x] **Step 2: Run collection unit/regression tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectionExecutionCoordinatorTest,CollectionExecutionCoordinatorCheckpointReuseTest,WebPageCollectionExecutorRouteTest,CollectionAuditSerializationTest" test
```

Expected: PASS.

- [x] **Step 3: Run focused search and collection matrix**

Run:

```bash
mvn -pl backend "-Dtest=DirectHtmlReaderClientTest,DirectHtmlReaderClientContextTest,WebPageCollectionExecutorContextTest,InternalLinkDiscoveryServiceTest,JinaReaderClientTest,QianfanSearchSourceProviderTest,SerpApiSearchSourceProviderTest" test
```

Expected: PASS.

- [x] **Step 4: Compare real-smoke evidence**

Run the search-only and limited collection smoke commands from Task 8.

Record these fields in the stop summary:

```text
sourceCandidateCount
selectedTargetCount
candidateVerificationElapsedMillis
candidateVerificationConcurrency
candidateVerificationDirectAttemptCount
candidateVerificationDirectUsableCount
candidateVerificationDirectShortcutCount
collectionStats.totalPackageCount
collectionStats.prefetchedReuseCount
collectionStats.executorCallCount
collectionStats.elapsedMillis
```

Do not claim runtime improvement unless current and previous runs used the same smoke method and similar external-network conditions.

---

## Rollback Plan

- To disable concurrent search verification:

```yaml
search:
  browser:
    verification-concurrency: 1
```

- To disable prefetched page reuse:

```yaml
collection:
  execution:
    reuse-prefetched-page: false
```

- To disable concurrent collection execution:

```yaml
collection:
  execution:
    concurrency: 1
```

- If stats serialization causes downstream compatibility issues, keep fields nullable and ensure Jackson ignores unknown fields on consumers.

## Self-Review

- Spec coverage: The plan addresses the user's requirement to improve speed without reducing information by focusing on concurrency, reuse, de-duplication, and timing observability.
- Review coverage: The plan now covers Direct-first layered verification as a formal task before bounded concurrency, so concurrency is not wasted waiting on the synchronized Playwright path when Direct can positively prove relevance.
- Cache scope: Cross-task caching remains intentionally excluded because it needs storage, TTL, invalidation, privacy and manual-refresh semantics; it should be planned separately instead of being mixed into single-task execution efficiency.
- Placeholder scan: No TBD/TODO placeholders remain; each task has concrete files, code snippets, commands, and expected outcomes.
- Type consistency: Uses existing `SearchCollectionTarget.collectedPage`, `CandidateVerificationResult`, `SearchExecutionTrace`, `CollectionExecutionCoordinator`, `CollectionExecutionReport`, and Spring configuration patterns.
- Risk note: Playwright Java is not generally thread-safe around shared browser operations. The plan keeps `PlaywrightPageCollector.collectByBrowser(...)` synchronized, so collection concurrency improves Direct/Jina/API and non-browser work first; browser-bound pages may still serialize unless a later dedicated browser-context pool is implemented.
