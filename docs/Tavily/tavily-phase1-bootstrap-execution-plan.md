# Tavily Phase 1 Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Tavily 从“验证不足后的补源工具”前移为 Phase 1 候选增强能力，让弱直达候选、根域候选和名称型候选在验证前就能获得 Tavily 的搜索与 `raw_content` 预取收益；同时把单竞品候选池控制在相近预算内，避免两个竞品的信息源规模严重失衡，并通过候选裁剪、短路和并发调优提升搜索与采集速率。

**Architecture:** 在 `SearchExecutionCoordinator` 中把现有 `LOAD_CANDIDATES -> VERIFY -> SUPPLEMENT -> SELECT` 扩展为 `LOAD_CANDIDATES -> TAVILY_BOOTSTRAP_ENRICH -> VERIFY -> BROWSER_SUPPLEMENT_SEARCH(展示语义: 运行期补源) -> SELECT`。新增一个小而清晰的 `TavilyBootstrapPlanner`，专门判断哪些规划期候选值得在 Phase 1 走 Tavily；强直达页面 URL 保持直接验证，不为了“用 Tavily”而强行重新搜索。bootstrap 候选一律先 `addAll` 进候选池，再通过 `SourceCandidateRanker.rankAndDeduplicate()` 按 canonical URL 去重；如果 URL 重叠，不做“planned 必胜”或“bootstrap 必胜”的粗暴替换，而是保留优胜候选的排序语义，同时合并 Tavily fast-lane 元数据和 `sourceUrls`。另一方面，把候选预算与速率控制收口到 `SearchRuntimePolicy + SearchPolicyResolver + SourceCandidateRanker`：同 source family 的各竞品默认使用一致的 bootstrap / supplement / candidate-pool 预算，上限内做多样性裁剪；Collection 侧提高安全并发，并优先执行 `TAVILY_PREFETCHED` 包，减少慢网页任务拖住整批结果。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Lombok, Jackson, JUnit 5, Mockito, AssertJ, Maven Surefire, existing `search / source / collection / integration` packages.

---

## 0. 变更边界

1. 本计划只做 Tavily 前移到 Phase 1 的搜索编排改造，不重做整个搜索架构。
2. 不把所有直达候选都改成“先 Tavily 再说”；只有弱候选、入口候选、域名候选和候选池不足场景才进入 bootstrap。
3. 强直达 URL 继续优先走 `VERIFY_TOP_CANDIDATES`，避免为了使用搜索 API 而牺牲确定性。
4. 保留现有运行期补源和 fail-open 兜底链路；Tavily bootstrap 失败不能阻断原流程。
5. 按当前用户要求，计划中不包含提交步骤，默认直接在 `master` 上修改并由用户自行提交。
6. 所有新增核心逻辑、条件判断和审计聚合都必须补中文注释；所有外部 HTTP 仍沿用现有 `try-catch + timeout + retry + fail-open` 约束。
7. “竞品信息源不要差距太多”在本计划中按“软平衡”落地：不给两个竞品强行凑完全相等的数量，而是用一致预算、统一候选池上限和同样的 per-domain 裁剪规则，避免一个竞品 60+、另一个只有十几个的极端落差。
8. “提速”优先通过减少无效候选、减少重复验证、让 Fast Lane 更早短路和提高安全并发完成，不通过牺牲 `sourceUrls`、审计、重试与 fail-open 语义换速度。
9. bootstrap 与 planned 候选的合并策略必须显式定义：bootstrap 结果只做追加，不直接替换原 planned 列表；统一在 merge 后按 canonical URL 去重，并在重复 URL 冲突时合并 Tavily `prefetchedContentRef / tavilyScore / fastLaneUsable / sourceUrls` 等元数据，避免弱候选残留或 fast-lane 元数据丢失。
10. `BROWSER_SUPPLEMENT_SEARCH` 作为历史 stepCode 已进入执行计划、恢复提示、测试 fixture 和 replay 语义；本轮不直接把它全量重命名为 `RUNTIME_SUPPLEMENT_SEARCH`，而是先保留旧 stepCode，只把 goal / message / 文案解释改成“运行期补源”，并在实施前先全量 `rg` 确认影响面。
11. 本计划按两批执行：第一批只做 `Task 1 -> Task 4 -> Task 7`，先跑通 Tavily Phase 1 主链路、审计和抖音/B站真实联调；第二批再做 `Task 5 -> Task 6`，用第一批 trace / report 结果作为候选规模平衡与提速优化的 baseline。

## 1. 问题复盘

第一次联调失败，不是 Tavily key 不可用，而是当前真实执行顺序决定了 Tavily 只能在 supplement 阶段发挥作用：

```text
Phase 1: LOAD_CANDIDATES
  -> 直接吃规划期 sourceCandidates / competitorUrls
Phase 2: VERIFY
  -> 先验证这些候选是否可用
Phase 3: SUPPLEMENT
  -> 候选不足时才进入 Tavily / qianfan / browser / http
Phase 4: SELECT
```

这会带来两个实际问题：

1. 规划期已有候选时，Tavily 很容易完全不参与真实任务主路径。
2. 即使 Tavily 参与了，也常常是在降级链路里和其他 provider 混合出现，难以证明“主发现能力来自 Tavily”。
3. 单竞品候选池如果完全按“谁补得多就收多少”扩张，会出现一个竞品十几条、另一个六十几条的失衡，后续比较会被信息量偏差放大。
4. 搜索与采集速度当前也不理想，原因通常不是单一 HTTP 慢，而是“候选太多 -> 验证太多 -> 串行采集太多”，导致真实耗时被候选膨胀拖垮。

因此这次改造的核心不是“让 Tavily 替代直达 URL”，而是：

```text
让 Tavily 变成 Phase 1 的候选增强器，
优先增强弱候选，
并把候选池控制在统一预算内，
而不是只在补源阶段被动救火。
```

---

## 2. 文件结构

### 新增文件

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchRequestPhase.java`  
  搜索请求阶段枚举，只承载 `BOOTSTRAP`、`SUPPLEMENT` 两种运行期来源语义，避免继续用裸字符串传递阶段信息。

- `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapDecision.java`  
  Phase 1 bootstrap 的最小决策对象，承载 `shouldExecute / reason / seedCandidates / request`。

- `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlanner.java`  
  负责判断哪些规划期候选属于“弱候选 / 根域候选 / docs 入口候选 / 候选不足场景”，并生成 `SearchSourceRequest`。

- `backend/src/test/java/cn/bugstack/competitoragent/integration/TavilyPhase1BootstrapRealSmokeTest.java`  
  真实外网冒烟，覆盖“抖音开放网推荐算法”和“哔哩哔哩官方文档”两组样本，验证 Tavily 在 bootstrap 阶段而不是 supplement 阶段生效。

### 修改文件

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`  
  增加 `requestPhase`，让路由层和 provider 可以区分 Phase 1 bootstrap 与 Phase 3 supplement。

- `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`  
  必须显式实现 `search(SearchSourceRequest request)`，真正把完整上下文透传给 delegate providers。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`  
  插入 `TAVILY_BOOTSTRAP_ENRICH` 新阶段，保留 `BROWSER_SUPPLEMENT_SEARCH` 旧 stepCode，但把其目标、提示和解释语义统一升级为“运行期补源”。

- `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`  
  读取 `requestPhase`，把 bootstrap 产出的候选与 supplement 产出的候选打出不同来源语义。

- `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java`  
  让 `BOOTSTRAPPED` 阶段候选和 `SUPPLEMENTED` 一样被视为运行期增强候选，避免 ownership 判定混乱。

- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`  
  给重复 URL 的候选增加字段级合并规则：排序优胜候选保留，但 Tavily fast-lane 元数据和 `sourceUrls` 不能因为去重被丢失。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`  
  增加 Phase 维度聚合，例如 `queryOrigins`、`bootstrapTriggered`。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchAuditSummary.java`  
  透传 Tavily bootstrap 审计摘要，保证 replay / report / delivery 可解释。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`  
  给 bootstrap、supplement、候选池总量和 per-domain 多样性加统一预算，确保不同竞品默认使用同一搜索规模。

- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- `backend/src/main/resources/application.yml`  
  提升安全并发，优先消费 `TAVILY_PREFETCHED` 任务，并把默认执行参数调到更贴近真实可用速度。

- `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`  
  补齐 Phase 1 bootstrap 合同、审计和黄金路径回归。

---

## 2.5 执行批次建议

### 第一批：核心闭环

按 `Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 7` 执行，目标只有一个：让 Tavily 在 Phase 1 主路径真实发生，并且审计、回放、报告和抖音/B站真实冒烟都能证明这件事。

### 第二批：优化收敛

只在第一批通过后再启动 `Task 5 -> Task 6`。这一批不改变“有没有 Tavily Phase 1 bootstrap”这个事实，只优化两个维度：

1. 不同竞品的候选池规模不要严重失衡。
2. 搜索与采集耗时在保持审计、可追溯和 fail-open 语义下进一步下降。

### 批次门槛

第二批开始前，必须先把第一批的真实联调结果记录到 `docs/Travily/progress/...`，至少包含：

1. 抖音样本的 Phase 1 bootstrap 命中情况。
2. 哔哩哔哩样本的 Phase 1 bootstrap 命中情况。
3. 第一批的候选数、验证数、supplement 是否跳过、collection 总耗时，作为第二批优化前 baseline。

---

## 3. Task 1: 修正 SearchSourceRequest 真实透传能力

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchRequestPhase.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`

- [ ] **Step 1: 先写 request-mode 透传 + 路由语义保持 红灯测试**

```java
@Test
void shouldRouteFullSearchSourceRequestToTavilyProvider() {
    SearchProviderProperties properties = new SearchProviderProperties();
    properties.setProviderOrder(List.of("tavily", "qianfan"));

    RecordingRequestProvider tavily = new RecordingRequestProvider("tavily");
    RecordingRequestProvider qianfan = new RecordingRequestProvider("qianfan");

    RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
            properties,
            List.of(tavily, qianfan),
            new SourceCandidateRanker(),
            new SearchPolicyResolver()
    );

    provider.search(SearchSourceRequest.builder()
            .competitorName("抖音")
            .requestedScopes(List.of("DOCS"))
            .searchQueries(List.of("抖音 开放平台 API 官方文档"))
            .preferredProviderKey("tavily")
            .requestPhase(SearchRequestPhase.BOOTSTRAP)
            .build());

    assertThat(tavily.lastRequest).isNotNull();
    assertThat(tavily.lastRequest.getRequestPhase()).isEqualTo(SearchRequestPhase.BOOTSTRAP);
    assertThat(tavily.lastRequest.getPreferredProviderKey()).isEqualTo("tavily");
    assertThat(qianfan.lastRequest).isNull();
}
```

```java
@Test
void shouldKeepPrimarySatisfiedAndFailOpenSemanticsWhenSearchRequestApiIsUsed() {
    SearchProviderProperties properties = new SearchProviderProperties();
    properties.setProviderOrder(List.of("qianfan", "tavily"));
    properties.setRunAuxiliaryWhenPrimarySatisfied(false);
    properties.setPrimaryCandidateThreshold(1);

    RecordingRequestProvider qianfan = new RecordingRequestProvider("qianfan");
    qianfan.response = List.of(SourceCandidate.builder()
            .url("https://docs.example.com/qianfan-hit")
            .title("Qianfan Hit")
            .sourceType("DOCS")
            .build());
    ThrowingRequestProvider tavily = new ThrowingRequestProvider("tavily");

    RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
            properties,
            List.of(qianfan, tavily),
            new SourceCandidateRanker(),
            new SearchPolicyResolver()
    );

    List<SourceCandidate> result = provider.search(SearchSourceRequest.builder()
            .competitorName("抖音")
            .requestedScopes(List.of("DOCS"))
            .requestPhase(SearchRequestPhase.SUPPLEMENT)
            .build());

    assertThat(result).hasSize(1);
    assertThat(qianfan.lastRequest).isNotNull();
    assertThat(tavily.invocationCount).isZero();
}
```

- [ ] **Step 2: 运行红灯测试确认当前 Router 会吃掉上下文**

Run:

```bash
cd backend
mvn "-Dtest=RoutingSearchSourceProviderTest" test
```

Expected: FAIL，原因是 `RoutingSearchSourceProvider` 还没有把 `SearchSourceRequest` 走进和旧 API 相同的 provider 路由骨架，`requestPhase / preferredProviderKey` 还没透传，`primarySatisfied / SearchProviderRole / per-provider fail-open` 也还没有在 request-mode 下复用。

- [ ] **Step 3: 用共享私有循环实现 requestPhase 与 Router 新入口**

```java
public enum SearchRequestPhase {
    BOOTSTRAP,
    SUPPLEMENT
}
```

```java
@Data
@Builder(toBuilder = true)
public class SearchSourceRequest {
    private String competitorName;
    private List<String> requestedScopes = new ArrayList<>();
    private List<String> searchQueries = new ArrayList<>();
    private List<String> preferredDomains = new ArrayList<>();
    private List<String> includeDomains = new ArrayList<>();
    private List<String> blockedDomains = new ArrayList<>();
    private List<SourceCandidate> seedCandidates = new ArrayList<>();
    private String preferredProviderKey;
    private String preferredQueryMode;
    private SearchRequestPhase requestPhase;
}
```

```java
@Override
public List<SourceCandidate> search(SearchSourceRequest request) {
    if (request == null) {
        return List.of();
    }
    return searchAcrossProviders(
            provider -> provider.search(request),
            normalizedProviderKey -> !StringUtils.hasText(request.getPreferredProviderKey())
                    || normalizeProviderKey(request.getPreferredProviderKey()).equals(normalizedProviderKey)
    );
}
```

```java
@Override
public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
    return searchAcrossProviders(
            provider -> provider.search(competitorName, requestedScopes),
            normalizedProviderKey -> true
    );
}
```

```java
private List<SourceCandidate> searchAcrossProviders(Function<SearchSourceProvider, List<SourceCandidate>> invoker,
                                                    Predicate<String> providerFilter) {
    List<SourceCandidate> mergedCandidates = new ArrayList<>();
    Map<String, SearchSourceProvider> providersByKey = indexProvidersByKey();
    int primaryCandidateCount = 0;
    boolean primarySatisfied = false;

    for (String providerKey : resolveProviderOrder()) {
        String normalizedProviderKey = normalizeProviderKey(providerKey);
        SearchSourceProvider provider = providersByKey.get(normalizedProviderKey);
        if (provider == null) {
            continue;
        }
        SearchSourceProviderDescriptor descriptor = provider.descriptor();
        SearchProviderRole providerRole = searchPolicyResolver.resolveProviderRole(descriptor.getProviderKey());
        if (primarySatisfied
                && !properties.isRunAuxiliaryWhenPrimarySatisfied()
                && providerRole != SearchProviderRole.PRIMARY_VERTICAL) {
            continue;
        }
        if (!providerFilter.test(normalizedProviderKey)
                || !descriptor.isEnabled(properties)
                || !provider.isAvailable()) {
            continue;
        }
        try {
            List<SourceCandidate> providerCandidates = invoker.apply(provider);
            if (!providerCandidates.isEmpty()) {
                mergedCandidates.addAll(providerCandidates);
                if (providerRole == SearchProviderRole.PRIMARY_VERTICAL) {
                    primaryCandidateCount += providerCandidates.size();
                    primarySatisfied = primaryCandidateCount >= Math.max(1, properties.getPrimaryCandidateThreshold());
                }
            }
        } catch (RuntimeException e) {
            if (!descriptor.isFailOpen(properties)) {
                throw e;
            }
        }
    }
    return sourceCandidateRanker.rankAndDeduplicate(mergedCandidates);
}
```

- [ ] **Step 4: 跑 Router 相关测试**

Run:

```bash
cd backend
mvn "-Dtest=RoutingSearchSourceProviderTest" test
```

Expected: PASS。

---

## 4. Task 2: 新增 Phase 1 Tavily Bootstrap 决策器与执行步骤

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapDecision.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlanner.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/TavilyBootstrapPlannerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 写“弱候选走 bootstrap，强直达跳过 bootstrap”与最小判定规则红灯测试**

```java
@Test
void shouldTreatRootAndOneLevelEntryPageAsWeakCandidate() {
    TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
    CollectorNodeConfig config = CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .preferredDomains(List.of("open.douyin.com"))
            .includeDomains(List.of("open.douyin.com"))
            .competitorUrls(List.of("https://open.douyin.com/"))
            .build();

    TavilyBootstrapDecision decision = planner.plan(config, List.of(SourceCandidate.builder()
            .url("https://open.douyin.com/docs")
            .title("抖音开放平台")
            .sourceType("DOCS")
            .build()));

    assertThat(decision.isShouldExecute()).isTrue();
}
```

```java
@Test
void shouldSkipBootstrapForDeepExactOfficialDocPage() {
    TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
    CollectorNodeConfig config = CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .preferredDomains(List.of("open.douyin.com"))
            .includeDomains(List.of("open.douyin.com"))
            .competitorUrls(List.of("https://open.douyin.com/"))
            .build();

    TavilyBootstrapDecision decision = planner.plan(config, List.of(SourceCandidate.builder()
            .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
            .title("平台简介")
            .sourceType("DOCS")
            .build()));

    assertThat(decision.isShouldExecute()).isFalse();
}
```

```java
@Test
void shouldRunTavilyBootstrapBeforeVerifyWhenPlannedCandidateIsWeakEntryUrl() {
    SearchSourceProvider provider = mock(SearchSourceProvider.class);
    when(provider.search(argThat(request ->
            request != null
                    && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP
                    && "tavily".equalsIgnoreCase(request.getPreferredProviderKey()))))
            .thenReturn(List.of(SourceCandidate.builder()
                    .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                    .title("平台简介")
                    .sourceType("DOCS")
                    .providerKey("tavily")
                    .build()));

    SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
            mock(CandidateVerifier.class),
            mock(BrowserSearchRuntimeService.class),
            provider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector(),
            new SearchPolicyResolver()
    );

    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://open.douyin.com/")
                    .title("抖音开放平台")
                    .sourceType("DOCS")
                    .build()))
            .verifyCandidates(Boolean.TRUE)
            .build());

    assertThat(result.getExecutionPlan().getSteps()).extracting(SearchExecutionStep::getStepCode)
            .containsSubsequence("LOAD_CANDIDATES", "TAVILY_BOOTSTRAP_ENRICH", "VERIFY_TOP_CANDIDATES");
}
```

```java
@Test
void shouldSkipTavilyBootstrapForStrongExactPlannedPage() {
    SearchSourceProvider provider = mock(SearchSourceProvider.class);
    SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
            new CandidateVerifier(mock(SourceCollector.class)),
            mock(BrowserSearchRuntimeService.class),
            provider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector(),
            new SearchPolicyResolver()
    );

    coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                    .title("平台简介")
                    .sourceType("DOCS")
                    .build()))
            .verifyCandidates(Boolean.FALSE)
            .build());

    verify(provider, never()).search(any(SearchSourceRequest.class));
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
cd backend
mvn "-Dtest=TavilyBootstrapPlannerTest,SearchExecutionCoordinatorTest" test
```

Expected: FAIL，原因是当前还没有 `TAVILY_BOOTSTRAP_ENRICH` 步骤，也没有 bootstrap 决策器，更没有被显式写死的 `isWeakEntryCandidate()` 最小规则。

- [ ] **Step 3: 实现 planner 与新阶段编排**

```java
@Builder
public class TavilyBootstrapDecision {
    private boolean shouldExecute;
    private String reason;
    private List<SourceCandidate> seedCandidates;
    private SearchSourceRequest request;
}
```

```java
public class TavilyBootstrapPlanner {

    public TavilyBootstrapDecision plan(CollectorNodeConfig config, List<SourceCandidate> plannedCandidates) {
        List<SourceCandidate> weakSeeds = (plannedCandidates == null ? List.<SourceCandidate>of() : plannedCandidates)
                .stream()
                .filter(candidate -> isWeakEntryCandidate(candidate, config))
                .toList();
        if (weakSeeds.isEmpty()) {
            return TavilyBootstrapDecision.builder()
                    .shouldExecute(false)
                    .reason("规划期候选已是强直达页面，无需在 Phase 1 走 Tavily bootstrap")
                    .seedCandidates(List.of())
                    .build();
        }
        return TavilyBootstrapDecision.builder()
                .shouldExecute(true)
                .reason("存在根域/入口候选，先用 Tavily 做 Phase 1 候选增强")
                .seedCandidates(weakSeeds)
                .request(SearchSourceRequest.builder()
                        .competitorName(config.getCompetitorName())
                        .requestedScopes(List.of(config.getSourceType()))
                        .searchQueries(config.getSearchQueries() == null ? List.of() : config.getSearchQueries())
                        .preferredDomains(config.getPreferredDomains() == null ? List.of() : config.getPreferredDomains())
                        .includeDomains(config.getIncludeDomains() == null ? List.of() : config.getIncludeDomains())
                        .seedCandidates(weakSeeds)
                        .preferredProviderKey("tavily")
                        .preferredQueryMode(config.getTavilyQueryMode())
                        .requestPhase(SearchRequestPhase.BOOTSTRAP)
                        .build())
                .build();
    }
}
```

```java
private boolean isWeakEntryCandidate(SourceCandidate candidate, CollectorNodeConfig config) {
    String host = extractHost(candidate == null ? null : candidate.getUrl());
    int pathDepth = pathDepth(candidate == null ? null : candidate.getUrl());
    Set<String> officialHosts = resolveOfficialHosts(config);
    boolean rootOrOneLevelEntry = pathDepth <= 1;
    boolean outsideOfficialScope = !officialHosts.isEmpty()
            && officialHosts.stream().noneMatch(officialHost -> isSameOrSubDomain(host, officialHost));
    boolean weakTitle = !StringUtils.hasText(candidate == null ? null : candidate.getTitle())
            || normalizeTitle(candidate.getTitle()).equals(normalizeTitle(config.getCompetitorName()));
    return rootOrOneLevelEntry || outsideOfficialScope || weakTitle;
}
```

```java
private Set<String> resolveOfficialHosts(CollectorNodeConfig config) {
    LinkedHashSet<String> hosts = new LinkedHashSet<>();
    addAllHosts(hosts, config.getPreferredDomains());
    addAllHosts(hosts, config.getIncludeDomains());
    addAllHostsFromUrls(hosts, config.getCompetitorUrls());
    return hosts;
}
```

```java
SearchExecutionStep.builder()
        .stepCode("TAVILY_BOOTSTRAP_ENRICH")
        .goal("对弱规划期候选执行 Tavily Phase 1 候选增强")
        .expectedDurationMs(4000L)
        .dependency("tavily")
        .status(SearchExecutionStep.StepStatus.PENDING)
        .build();
```

- [ ] **Step 4: 跑 coordinator 定向测试**

Run:

```bash
cd backend
mvn "-Dtest=SearchExecutionCoordinatorTest" test
```

Expected: PASS。

---

## 5. Task 3: 区分 BOOTSTRAPPED 与 SUPPLEMENTED 候选语义，并锁定 planned + bootstrap 合并规则

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 写 provenance + duplicate-merge 红灯测试**

```java
@Test
void shouldMarkBootstrapCandidatesDifferentlyFromSupplementCandidates() {
    TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
    StubTavilySearchClient client = new StubTavilySearchClient();
    client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
            .query("抖音 开放平台 API 官方文档")
            .requestId("req-bootstrap-1")
            .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                    .title("平台简介")
                    .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                    .rawContent("raw content")
                    .score(0.71D)
                    .build()))
            .build());

    TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
            properties(),
            client,
            new TavilySearchProfileResolver(properties()),
            registry,
            new ObjectMapper()
    );

    List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
            .competitorName("抖音")
            .requestedScopes(List.of("DOCS"))
            .preferredProviderKey("tavily")
            .requestPhase(SearchRequestPhase.BOOTSTRAP)
            .build());

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).getDiscoveryMethod()).isEqualTo("TAVILY_PHASE1_BOOTSTRAP");
}
```

```java
@Test
void shouldMergeTavilyMetadataAndSourceUrlsWhenBootstrapAndPlannedShareUrl() {
    SourceCandidate planned = SourceCandidate.builder()
            .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
            .title("平台简介")
            .sourceType("DOCS")
            .selectionStage("PLANNED")
            .reason("规划期直达候选")
            .sourceUrls(List.of("https://open.douyin.com/"))
            .relevanceScore(0.92D)
            .freshnessScore(0.70D)
            .qualityScore(0.90D)
            .build();
    SourceCandidate bootstrap = SourceCandidate.builder()
            .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
            .title("平台简介")
            .sourceType("DOCS")
            .selectionStage("BOOTSTRAPPED")
            .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
            .providerKey("tavily")
            .prefetchedContentRef("prefetch-001")
            .tavilyScore(0.82D)
            .fastLaneUsable(Boolean.TRUE)
            .sourceUrls(List.of(
                    "https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction",
                    "https://open.douyin.com/"))
            .relevanceScore(0.93D)
            .freshnessScore(0.72D)
            .qualityScore(0.91D)
            .build();

    List<SourceCandidate> ranked = new SourceCandidateRanker().rankAndDeduplicate(List.of(planned, bootstrap));

    assertThat(ranked).hasSize(1);
    SourceCandidate merged = ranked.get(0);
    assertThat(merged.getPrefetchedContentRef()).isEqualTo("prefetch-001");
    assertThat(merged.getTavilyScore()).isEqualTo(0.82D);
    assertThat(merged.getFastLaneUsable()).isTrue();
    assertThat(merged.getSourceUrls()).contains(
            "https://open.douyin.com/",
            "https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction");
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
cd backend
mvn "-Dtest=TavilyFastLaneProviderTest,SourceCandidateRankerTest" test
```

Expected: FAIL，原因是当前 provider 只会统一打 `TAVILY_FAST_LANE`，而且 `SourceCandidateRanker` 发生重复 URL 冲突时还没有显式做 Tavily fast-lane 元数据与 `sourceUrls` 合并。

- [ ] **Step 3: 实现 bootstrap / supplement 区分与重复 URL 合并规则**

```java
private String resolveDiscoveryMethod(SearchSourceRequest request) {
    if (request != null && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP) {
        return "TAVILY_PHASE1_BOOTSTRAP";
    }
    return "TAVILY_FAST_LANE";
}
```

```java
List<SourceCandidate> mergedCandidates = new ArrayList<>(allCandidates);
mergedCandidates.addAll(bootstrapCandidates);
allCandidates = sourceCandidateRanker.rankAndDeduplicate(mergedCandidates);
```

```java
private SourceCandidate mergeDuplicateMetadata(SourceCandidate winner, SourceCandidate loser) {
    LinkedHashSet<String> mergedSourceUrls = new LinkedHashSet<>(resolveSourceUrls(winner));
    mergedSourceUrls.addAll(resolveSourceUrls(loser));
    return winner.toBuilder()
            .sourceUrls(new ArrayList<>(mergedSourceUrls))
            .hasPrefetchedContent(orTrue(winner.getHasPrefetchedContent(), loser.getHasPrefetchedContent()))
            .prefetchedContentRef(firstText(winner.getPrefetchedContentRef(), loser.getPrefetchedContentRef()))
            .prefetchedRawContentLength(firstNonNull(winner.getPrefetchedRawContentLength(), loser.getPrefetchedRawContentLength()))
            .tavilyScore(firstNonNull(winner.getTavilyScore(), loser.getTavilyScore()))
            .tavilyRequestId(firstText(winner.getTavilyRequestId(), loser.getTavilyRequestId()))
            .tavilyQuery(firstText(winner.getTavilyQuery(), loser.getTavilyQuery()))
            .tavilyQueryMode(firstText(winner.getTavilyQueryMode(), loser.getTavilyQueryMode()))
            .fastLaneUsable(orTrue(winner.getFastLaneUsable(), loser.getFastLaneUsable()))
            .skipNetworkVerification(orTrue(winner.getSkipNetworkVerification(), loser.getSkipNetworkVerification()))
            .build();
}
```

```java
SourceCandidate preferred = preferCandidate(existing, scored);
SourceCandidate merged = preferred == scored
        ? mergeDuplicateMetadata(annotateDuplicateWinner(preferred), existing)
        : mergeDuplicateMetadata(preferred, scored);
deduplicated.put(key, merged);
```

```java
return equalsAny(stage, "BROWSER", "SUPPLEMENTED", "BOOTSTRAPPED");
```

- [ ] **Step 4: 跑 provider + ranker + coordinator 相关测试**

Run:

```bash
cd backend
mvn "-Dtest=TavilyFastLaneProviderTest,SourceCandidateRankerTest,SearchExecutionCoordinatorTest" test
```

Expected: PASS。

---

## 6. Task 4: 扩展审计、回放和进度语义，并保留旧 stepCode 兼容

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchAuditSummary.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`

- [ ] **Step 1: 先做旧 stepCode 影响面 grep**

Run:

```bash
cd backend
rg -n "BROWSER_SUPPLEMENT_SEARCH" src/main src/test
```

Expected: 至少命中 `SearchExecutionCoordinator.java`、`CollectorPlanTemplateFactory.java`、`SearchExecutionCoordinatorTest.java`、`ReportServiceTest.java`；本轮只升级 goal / message / recoveryHint / 展示文案，不直接把旧 stepCode 改成 `RUNTIME_SUPPLEMENT_SEARCH`。

- [ ] **Step 2: 写 bootstrap 审计 + 旧 stepCode 兼容红灯测试**

```java
@Test
void shouldExposeBootstrapOriginInTavilyFastLaneAudit() {
    TavilyFastLaneAudit audit = TavilyFastLaneAudit.builder()
            .queryModes(List.of("OFFICIAL_DOCS"))
            .queryOrigins(List.of("BOOTSTRAP"))
            .queriesSent(1)
            .totalResults(1)
            .fastLaneUsableCount(1)
            .bootstrapTriggered(true)
            .build();

    SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
            .tavilyFastLaneAudit(audit)
            .executionTrace(SearchExecutionTrace.builder().tavilyFastLaneAudit(audit).build())
            .build();

    assertThat(SearchAuditSummary.from(snapshot).getTavilyFastLaneAudit().getQueryOrigins())
            .containsExactly("BOOTSTRAP");
}
```

```java
@Test
void shouldKeepLegacyBrowserSupplementStepCodeWhileUpgradingGoalText() {
    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("Notion AI")
            .sourceType("DOCS")
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://planned.example.com/docs")
                    .title("Planned Docs")
                    .sourceType("DOCS")
                    .build()))
            .verifyCandidates(Boolean.FALSE)
            .browserSearchEnabled(Boolean.TRUE)
            .searchMode("HYBRID")
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .build());

    SearchExecutionStep supplementStep = result.getExecutionPlan().getSteps().stream()
            .filter(step -> "BROWSER_SUPPLEMENT_SEARCH".equals(step.getStepCode()))
            .findFirst()
            .orElseThrow();

    assertThat(supplementStep.getGoal()).contains("运行期补源");
}
```

```java
JsonNode payload = new ObjectMapper().valueToTree(response);
assertEquals("BROWSER_SUPPLEMENT_SEARCH",
        payload.at("/searchAuditOverview/collectors/0/recoveryCheckpoint").asText());
```

- [ ] **Step 3: 运行红灯测试**

Run:

```bash
cd backend
mvn "-Dtest=SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest,ReportServiceTest" test
```

Expected: FAIL，原因是 `queryOrigins / bootstrapTriggered` 还不存在，且当前还没有把“旧 stepCode 保持不变、展示语义升级”为显式兼容约束。

- [ ] **Step 4: 实现 Tavily bootstrap 审计聚合与旧 stepCode 兼容**

```java
@Data
@Builder(toBuilder = true)
public class TavilyFastLaneAudit {
    private List<String> queryModes;
    private List<String> queryOrigins;
    private Integer queriesSent;
    private Integer totalResults;
    private Integer fastLaneUsableCount;
    private Integer fastLaneRejectedCount;
    private Map<String, Integer> rejectionReasons;
    private Boolean bootstrapTriggered;
    private Boolean fallbackTriggered;
    private List<String> tavilyRequestIds;
    private Integer playwrightInvocationBaselineHint;
}
```

```java
addDistinctText(queryOrigins, candidate.getSelectionStage());
boolean bootstrapTriggered = queryOrigins.stream().anyMatch("BOOTSTRAPPED"::equalsIgnoreCase);
```

```java
step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行运行期补源", 8000, "searchEngine")
```

```java
.recoveryHint("如搜索中断，优先从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查（展示语义：运行期补源）。")
```

```java
markStepRunning(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementStartMessage);
markStepSuccess(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementMessage);
markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", "现有候选已满足最小验证目标，无需补源");
```

- [ ] **Step 5: 跑审计、回放和兼容测试**

Run:

```bash
cd backend
mvn "-Dtest=SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest,ReportServiceTest" test
```

Expected: PASS。

---

## 7. Task 5（第二批）: 统一候选预算，避免竞品信息源规模严重失衡

**Batch Gate:** 只有在 `Task 7` 完成并记录第一批 baseline 之后才开始。

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchRuntimePolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 先写“统一预算 + per-domain 裁剪”的红灯测试**

```java
@Test
void shouldTrimCandidatePoolWithOverallBudgetAndPerDomainCap() {
    SourceCandidateRanker ranker = new SourceCandidateRanker();
    List<SourceCandidate> candidates = List.of(
            candidate("https://a.example.com/1", "a.example.com", 0.98D),
            candidate("https://a.example.com/2", "a.example.com", 0.97D),
            candidate("https://a.example.com/3", "a.example.com", 0.96D),
            candidate("https://b.example.com/1", "b.example.com", 0.95D),
            candidate("https://b.example.com/2", "b.example.com", 0.94D),
            candidate("https://b.example.com/3", "b.example.com", 0.93D),
            candidate("https://c.example.com/1", "c.example.com", 0.92D),
            candidate("https://c.example.com/2", "c.example.com", 0.91D)
    );

    List<SourceCandidate> limited = ranker.rankDeduplicateAndLimit(candidates, 6, 2);

    assertThat(limited).hasSize(6);
    assertThat(limited.stream().filter(item -> "a.example.com".equals(item.getDomain())).count()).isLessThanOrEqualTo(2);
    assertThat(limited.stream().filter(item -> "b.example.com".equals(item.getDomain())).count()).isLessThanOrEqualTo(2);
}
```

```java
@Test
void shouldCapBootstrapCandidatesToBalancedBudget() {
    SearchSourceProvider provider = mock(SearchSourceProvider.class);
    when(provider.search(argThat(request -> request != null && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP)))
            .thenReturn(IntStream.rangeClosed(1, 20)
                    .mapToObj(index -> SourceCandidate.builder()
                            .url("https://docs.example.com/page-" + index)
                            .domain("docs.example.com")
                            .providerKey("tavily")
                            .sourceType("DOCS")
                            .title("Doc " + index)
                            .relevanceScore(0.99D - index * 0.01D)
                            .qualityScore(0.90D)
                            .freshnessScore(0.70D)
                            .build())
                    .toList());

    SearchRuntimePolicy runtimePolicy = SearchRuntimePolicy.builder()
            .bootstrapCandidateLimit(6)
            .supplementCandidateLimit(6)
            .maxCandidatePoolSize(8)
            .maxCandidatesPerDomain(2)
            .build();

    SearchExecutionResult result = coordinator(provider).execute(CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .searchRuntimePolicy(runtimePolicy)
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://open.douyin.com/")
                    .title("抖音开放平台")
                    .sourceType("DOCS")
                    .build()))
            .verifyCandidates(Boolean.FALSE)
            .build());

    assertThat(result.getSourceCandidates().size()).isLessThanOrEqualTo(8);
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
cd backend
mvn "-Dtest=SourceCandidateRankerTest,SearchExecutionCoordinatorTest" test
```

Expected: FAIL，原因是当前还没有“统一预算 + per-domain 裁剪”能力。

- [ ] **Step 3: 实现候选预算与软平衡策略**

```java
@Data
@Builder(toBuilder = true)
public class SearchRuntimePolicy {
    private Integer bootstrapCandidateLimit;
    private Integer supplementCandidateLimit;
    private Integer maxCandidatePoolSize;
    private Integer maxCandidatesPerDomain;
    private Double competitorCoverageSoftGapRatio;
}
```

```java
public int resolveBootstrapCandidateLimit(SearchRuntimePolicy policy, int targetCount) {
    if (policy != null && policy.getBootstrapCandidateLimit() != null && policy.getBootstrapCandidateLimit() > 0) {
        return policy.getBootstrapCandidateLimit();
    }
    return Math.max(6, targetCount * 2);
}

public int resolveSupplementCandidateLimit(SearchRuntimePolicy policy, int targetCount) {
    if (policy != null && policy.getSupplementCandidateLimit() != null && policy.getSupplementCandidateLimit() > 0) {
        return policy.getSupplementCandidateLimit();
    }
    return Math.max(6, targetCount * 2);
}

public int resolveMaxCandidatePoolSize(SearchRuntimePolicy policy, int targetCount) {
    if (policy != null && policy.getMaxCandidatePoolSize() != null && policy.getMaxCandidatePoolSize() > 0) {
        return policy.getMaxCandidatePoolSize();
    }
    return Math.max(10, targetCount * 3);
}

public int resolveMaxCandidatesPerDomain(SearchRuntimePolicy policy) {
    if (policy != null && policy.getMaxCandidatesPerDomain() != null && policy.getMaxCandidatesPerDomain() > 0) {
        return policy.getMaxCandidatesPerDomain();
    }
    return 2;
}
```

```java
public List<SourceCandidate> rankDeduplicateAndLimit(List<SourceCandidate> candidates,
                                                     int maxCount,
                                                     int perDomainCap) {
    List<SourceCandidate> ranked = rankAndDeduplicate(candidates);
    if (ranked.isEmpty()) {
        return ranked;
    }
    Map<String, Integer> perDomainCounter = new LinkedHashMap<>();
    List<SourceCandidate> limited = new ArrayList<>();
    for (SourceCandidate candidate : ranked) {
        String domain = StringUtils.hasText(candidate.getDomain()) ? candidate.getDomain() : extractDomain(candidate.getUrl());
        int current = perDomainCounter.getOrDefault(domain, 0);
        if (perDomainCap > 0 && current >= perDomainCap) {
            continue;
        }
        limited.add(candidate);
        perDomainCounter.put(domain, current + 1);
        if (limited.size() >= maxCount) {
            break;
        }
    }
    return limited;
}
```

```java
SearchRuntimePolicy runtimePolicy = config.getSearchRuntimePolicy() == null
        ? SearchRuntimePolicy.builder().build()
        : config.getSearchRuntimePolicy();
int bootstrapCandidateLimit = searchPolicyResolver.resolveBootstrapCandidateLimit(runtimePolicy, targetCount);
int supplementCandidateLimit = searchPolicyResolver.resolveSupplementCandidateLimit(runtimePolicy, targetCount);
int maxCandidatePoolSize = searchPolicyResolver.resolveMaxCandidatePoolSize(runtimePolicy, targetCount);
int maxCandidatesPerDomain = searchPolicyResolver.resolveMaxCandidatesPerDomain(runtimePolicy);
```

```java
bootstrapCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
        bootstrapCandidates,
        bootstrapCandidateLimit,
        maxCandidatesPerDomain
);
allCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
        concat(allCandidates, bootstrapCandidates),
        maxCandidatePoolSize,
        maxCandidatesPerDomain
);
```

```java
return SearchRuntimePolicy.builder()
        .bootstrapCandidateLimit(Math.max(6, targetCount * 2))
        .supplementCandidateLimit(Math.max(6, targetCount * 2))
        .maxCandidatePoolSize(Math.max(10, targetCount * 3))
        .maxCandidatesPerDomain(2)
        .competitorCoverageSoftGapRatio(2.0D)
        .build();
```

- [ ] **Step 4: 跑候选预算与平衡回归**

Run:

```bash
cd backend
mvn "-Dtest=SourceCandidateRankerTest,SearchExecutionCoordinatorTest" test
```

Expected: PASS。

---

## 8. Task 6（第二批）: 提升搜索与采集速率

**Batch Gate:** 只有在 `Task 7` 完成并记录第一批 baseline 之后才开始。

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 写“prefetched 优先 + 并发提升”红灯测试**

```java
@Test
void shouldPrioritizePrefetchedPackagesBeforeSlowWebPackages() {
    CollectionExecutionProperties properties = new CollectionExecutionProperties();
    properties.setConcurrency(3);
    properties.setPrioritizePrefetchedPackages(true);

    List<String> invocationOrder = new CopyOnWriteArrayList<>();
    CollectionExecutionCoordinator coordinator = coordinatorWithOrderRecorder(properties, invocationOrder);

    coordinator.execute(1L, "collect_test", null, "抖音", List.of(
            target("https://open.douyin.com/docs/1", "TAVILY_PREFETCHED"),
            target("https://www.douyin.com/slow-page", "WEB_PAGE")
    ));

    assertThat(invocationOrder.get(0)).isEqualTo("TAVILY_PREFETCHED");
}
```

```java
@Test
void shouldExposeFasterExecutionDefaultsThroughBinding() {
    contextRunner
            .withPropertyValues(
                    "search.browser.verification-concurrency=4",
                    "collection.execution.concurrency=3"
            )
            .run(context -> {
                SearchBrowserProperties searchBrowserProperties = context.getBean(SearchBrowserProperties.class);
                CollectionExecutionProperties collectionExecutionProperties = context.getBean(CollectionExecutionProperties.class);
                assertThat(searchBrowserProperties.getVerificationConcurrency()).isEqualTo(4);
                assertThat(collectionExecutionProperties.getConcurrency()).isEqualTo(3);
            });
}
```

- [ ] **Step 2: 运行红灯测试**

Run:

```bash
cd backend
mvn "-Dtest=CollectionExecutionCoordinatorTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest" test
```

Expected: FAIL，原因是当前 Collection 默认并发仍偏保守，也没有 prefetched-first 执行策略。

- [ ] **Step 3: 实现速率优化**

```java
@Data
@ConfigurationProperties(prefix = "collection.execution")
public class CollectionExecutionProperties {
    private boolean reusePrefetchedPage = true;
    private int concurrency = 3;
    private boolean prioritizePrefetchedPackages = true;
    private boolean timingEnabled = true;
}
```

```java
private List<QueuedCollectionTask> sortBatchForExecution(List<QueuedCollectionTask> batch) {
    return batch.stream()
            .sorted(Comparator.comparing(task ->
                    !("TAVILY_PREFETCHED".equalsIgnoreCase(task.taskPackage().getPrimaryTool()))))
            .toList();
}
```

```java
List<QueuedCollectionTask> executionBatch = collectionExecutionProperties.isPrioritizePrefetchedPackages()
        ? sortBatchForExecution(batch)
        : batch;
```

```yaml
search:
  browser:
    verification-concurrency: 4

collection:
  execution:
    concurrency: 3
    prioritize-prefetched-packages: true
```

```java
if (bootstrapCandidates.size() >= minVerifiedCount && bootstrapCandidates.stream()
        .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
        .count() >= minVerifiedCount) {
    fallbackDecision = "SKIP_SUPPLEMENT_BOOTSTRAP_ENOUGH";
}
```

- [ ] **Step 4: 跑速率相关回归**

Run:

```bash
cd backend
mvn "-Dtest=CollectionExecutionCoordinatorTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest,SearchAndCollectionGoldenMasterTest" test
```

Expected: PASS。

---

## 9. Task 7（第一批收口）: 黄金路径回归与抖音/B站真实冒烟

**Files:**
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/integration/TavilyPhase1BootstrapRealSmokeTest.java`
- Update: `docs/Travily/progress/2026-06-27-tavily-phase1-bootstrap-progress.md`

- [ ] **Step 1: 先写 Phase 1 黄金路径测试**

```java
@Test
void shouldUseBootstrapCandidatesBeforeSupplementWhenWeakPlannedDocsEntryIsProvided() {
    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("抖音")
            .sourceType("DOCS")
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://open.douyin.com/")
                    .title("抖音开放平台")
                    .sourceType("DOCS")
                    .build()))
            .verifyCandidates(Boolean.TRUE)
            .build());

    assertThat(result.getSourceCandidates())
            .anySatisfy(candidate -> assertThat(candidate.getSelectionStage()).isEqualTo("BOOTSTRAPPED"));
    assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit().getQueryOrigins())
            .contains("BOOTSTRAP");
}
```

- [ ] **Step 2: 新增真实外网 smoke test 骨架**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "search.mode=HYBRID",
        "logging.level.cn.bugstack.competitoragent=INFO"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_REAL_SMOKE", matches = "true")
class TavilyPhase1BootstrapRealSmokeTest {

    @Test
    void shouldBootstrapDouyinOpenWebBeforeSupplement() {
        SearchExecutionResult result = executeSearch("抖音", "REVIEW", List.of("https://www.douyin.com/"));
        assertThat(result.getSourceCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.getProviderKey()).isEqualToIgnoringCase("tavily");
                    assertThat(candidate.getSelectionStage()).isEqualTo("BOOTSTRAPPED");
                });
        assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit().getQueryOrigins())
                .contains("BOOTSTRAP");
    }

    @Test
    void shouldBootstrapBilibiliDocsBeforeSupplement() {
        SearchExecutionResult result = executeSearch("哔哩哔哩", "DOCS", List.of("https://open.bilibili.com/"));
        assertThat(result.getSourceCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.getProviderKey()).isEqualToIgnoringCase("tavily");
                    assertThat(candidate.getSelectionStage()).isEqualTo("BOOTSTRAPPED");
                });
        assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit().getQueryOrigins())
                .contains("BOOTSTRAP");
    }
}
```

- [ ] **Step 3: 跑代码回归**

Run:

```bash
cd backend
mvn "-Dtest=RoutingSearchSourceProviderTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorTest,SearchAuditTimelineContractTest,SearchAndCollectionGoldenMasterTest" test
```

Expected: PASS。

- [ ] **Step 4: 跑真实联调验证**

Run:

```bash
cd backend
$env:RUN_REAL_SMOKE='true'
$env:TAVILY_API_KEY='真实 key'
mvn "-Dtest=TavilyPhase1BootstrapRealSmokeTest" test
```

Expected:

```text
1. 抖音开放网样本在 Phase 1 出现 BOOTSTRAPPED Tavily 候选
2. 哔哩哔哩官方文档样本在 Phase 1 出现 BOOTSTRAPPED Tavily 候选
3. TavilyFastLaneAudit.queryOrigins 包含 BOOTSTRAP
4. 若 bootstrap 足够，supplement 应被跳过或降为次要兜底
```

---

## 10. 验收标准

验收按两批进行：第一批先验主链路与兼容性，第二批再验预算平衡与提速优化。

代码层面必须同时满足：

1. `RoutingSearchSourceProvider.search(SearchSourceRequest)` 真实透传上下文，不再丢失 `requestPhase / preferredProviderKey / preferredQueryMode / seedCandidates`。
2. `SearchExecutionCoordinator` 的默认步骤顺序变为：

```text
LOAD_CANDIDATES
TAVILY_BOOTSTRAP_ENRICH
VERIFY_TOP_CANDIDATES
BROWSER_SUPPLEMENT_SEARCH（展示语义：运行期补源）
SELECT_TARGETS
COLLECT_PAGES
```

3. 强直达页面 URL 会跳过 bootstrap，弱候选/入口候选才会在 Phase 1 走 Tavily。
4. bootstrap 失败必须 fail-open，不能阻断 verify / supplement / select。
5. Tavily bootstrap 产出的候选必须保留 `sourceUrls`，并能继续走 Fast Lane gate、verification shortcut 和 collection routing。
6. planned + bootstrap 候选合并必须采用 `addAll -> canonical URL 去重`，重复 URL 冲突时保留优胜候选的排序语义，同时合并 Tavily `prefetchedContentRef / tavilyScore / fastLaneUsable / sourceUrls` 元数据。
7. Tavily 审计必须能区分 `BOOTSTRAP` 与 `SUPPLEMENT` 来源。
8. `BROWSER_SUPPLEMENT_SEARCH` 旧 stepCode 必须继续在执行计划、恢复提示、报告聚合和 replay 数据中可读；本轮只升级 goal / message / 展示文案，不做破坏兼容性的全量改名。
9. 同 source family 下不同竞品默认使用一致的 bootstrap / supplement / candidate-pool 预算，单竞品候选池不会无限膨胀；这一条属于第二批优化验收。
10. 候选池裁剪后，若两个竞品都能找到足量候选，则它们的候选池规模差距应收敛到软上限以内；推荐默认约束为 `较大侧 <= 较小侧 * 2.0`，若弱侧本身无足量候选，则允许弱侧自然更少；这一条属于第二批优化验收。
11. Collection 默认并发高于 1，并优先执行 `TAVILY_PREFETCHED` 任务；搜索验证并发与候选池裁剪结合后，真实耗时应明显低于“无 budget、串行 collection”的旧路径；这一条属于第二批优化验收。

联调验收必须同时满足：

1. 抖音开放网样本能看到 Tavily 在 Phase 1 而不是仅在 supplement 阶段被调用。
2. 哔哩哔哩文档样本能看到 Phase 1 Tavily bootstrap 命中官方文档候选。
3. 第一次失败的根因被修复：即使规划期已有弱直达候选，Tavily 仍能在主路径上参与，而不是只能等补源。
4. 两个竞品的候选池与最终选中目标数量不会出现“一个十几条、一个六十几条”的明显失衡；若真实可用资料差异确实存在，也必须是预算裁剪后的有限差距，而不是无限扩张后的原始差距；这一条属于第二批优化联调验收。
5. 搜索与采集速率优化可从 trace / report 中直接观察到：候选验证并发 > 1、collection 并发 > 1、supplement 被更频繁地跳过、prefetched 任务优先完成；这一条属于第二批优化联调验收。

---

## 11. 自检清单

- [ ] 没有把所有直达候选一刀切送回 Tavily 搜索。
- [ ] Router 真正实现了 `search(SearchSourceRequest)`，不是只在单测里 mock 通过。
- [ ] Router 的 `search(SearchSourceRequest)` 与旧 `search(String, List<String>)` 复用了同一套 provider 循环骨架，没有丢掉 `primarySatisfied`、`SearchProviderRole` 和 per-provider fail-open。
- [ ] `isWeakEntryCandidate()` 不是黑盒，至少明确实现了“路径深度 <= 1 / 超出官方 host 范围 / title 过弱”这三条最小规则。
- [ ] `BOOTSTRAPPED` 与 `SUPPLEMENTED` 在进度、审计、选择和 replay 里语义清晰。
- [ ] 重复 URL 合并后，`prefetchedContentRef / tavilyScore / fastLaneUsable / sourceUrls` 没有因为去重被悄悄丢失。
- [ ] 没有直接把 `BROWSER_SUPPLEMENT_SEARCH` 全量改名；旧 stepCode 仍能被 report / replay / recovery fixture 正常读取。
- [ ] 没有把 `raw_content` 重新塞进大对象或审计快照。
- [ ] 抖音/B站真实 smoke test 的断言针对“Phase 1 发生了什么”，而不是只验证任务最后成功。
- [ ] 第一批真实联调 baseline 已写入 progress 文档，再开始第二批的预算平衡和提速优化。
- [ ] 候选池预算和 per-domain 裁剪是按“软平衡”收敛差距，而不是把弱竞品硬凑出不存在的来源。
- [ ] 提速策略优先减少无效候选和重复采集，没有靠删除审计、关闭重试或放弃 `sourceUrls` 可追溯性换速度。
