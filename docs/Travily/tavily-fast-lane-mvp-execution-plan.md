# Tavily Fast Lane MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有搜索与采集链路中新增 Tavily Fast Lane MVP，让常规高质量页面可通过 Tavily 搜索阶段返回的 `raw_content` 直接形成可追溯 EvidenceSource，同时保留千帆 / SerpApi / DirectHtml / Jina / Playwright 兜底链路。

**Architecture:** Tavily 作为 `SearchSourceProvider` 的一个 fail-open provider 接入现有 `RoutingSearchSourceProvider`，搜索结果先经过 `Prefetched Content Gate` 标注 `pageType / qualityTier / fastLaneUsable / contentCompleteness`。可用正文通过运行时 `TavilyPrefetchedContentRegistry` 暂存，`CollectionTaskPackageBuilder` 将可用候选路由到 `TavilyPrefetchedExecutor`，不可用候选自然回落到原网页采集链路。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Lombok, Jackson, JUnit 5, Mockito, AssertJ, Maven Surefire, existing `source / search / collection / orchestration` packages.

---

## 0. 执行边界

本计划只覆盖 MVP，不实现完整生产版的跨任务缓存、成本仪表盘、运营调参后台和全自动 `DOMAIN_HINT_DISCOVERY` Orchestration 闭环。

建议实施时机：当前 3.4 / Citation / Reviewer 边界收口后，作为 `Wave 10: Tavily Fast Lane Evidence Expansion MVP` 单独分支实施。

所有新增核心类、外部 API 调用、复杂 Gate 判断必须写中文注释；所有外部 HTTP 调用必须有 try-catch、超时、重试和 fail-open 行为；所有进入 EvidenceSource 的内容必须保留 `sourceUrls`。

## 1. 任务总览

| 任务 | 核心目标 | 预计耗时 | 依赖 |
| --- | --- | --- | --- |
| Task 1 | 建立 Tavily 配置、profile、DomainHintSet 基础契约 | 0.5 天 | 当前搜索配置结构稳定 |
| Task 2 | 扩展 SearchSourceRequest、SourceCandidate、CollectionTaskPackage 轻量契约 | 0.5 天 | Task 1 |
| Task 3 | 实现 Tavily HTTP Client 与 Provider | 1 天 | Task 1, Task 2 |
| Task 4 | 实现 Prefetched Content Gate | 1 天 | Task 2, Task 3 fixture |
| Task 5 | 接入 RoutingSearchSourceProvider 与 readiness/security guard | 0.5 天 | Task 3 |
| Task 6 | 实现 TavilyPrefetchedExecutor 与采集路由 | 1 天 | Task 2, Task 4 |
| Task 7 | 调整 CandidateVerifier / Selection 的快速通道短路 | 0.5 天 | Task 4, Task 6 |
| Task 8 | 扩展 SearchAudit / CollectionAudit 的 Tavily 审计摘要 | 1 天 | Task 3-7 |
| Task 9 | 接入 Orchestration 半自动补证据动作 | 1 天 | Task 8, 当前 OrchestrationDecisionService 稳定 |
| Task 10 | 建立 A/B/C/D 验收 fixture 与指标测试 | 1 天 | Task 3-8 |

---

## 2. 文件结构

### 新增文件

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java`  
  Tavily API 配置，prefix 为 `tavily-search`，包含 endpoint、apiKey、searchDepth、includeRawContent、maxResults、timeoutSeconds、maxRetries、minRawContentChars。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyQueryMode.java`  
  枚举：`OPEN_WEB`、`OFFICIAL_DOCS`、`TRUSTED_WEB_EXPANSION`、`EVIDENCE_REPAIR`。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/DomainHint.java`  
  单个域名提示，字段包括 domain、sourceFamily、confidence、source、reason、sourceUrls。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/DomainHintSet.java`  
  竞品维度域名提示集合，MVP 只做运行期对象，不建表。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`  
  单次 Tavily 请求 profile，包含 family、queryMode、query、includeDomains、maxResults、expansionReason。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`  
  按 family / scope 生成 Tavily query 和 `include_domains` 策略。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyDomainHintResolver.java`  
  从用户 preferredDomains、规划期 sourceCandidates、Tavily OPEN_WEB bootstrap 结果中构建 `DomainHintSet`。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContent.java`  
  运行时正文对象，保存 url、title、rawContent、cleanedContent、sourceUrls、requestId、query、quality signals。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentRegistry.java`  
  运行时暂存 registry，使用 `ConcurrentHashMap<String, TavilyPrefetchedContent>`，执行器消费后释放正文。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPageTypeClassifier.java`  
  分类 `SEARCH_PAGE / VIDEO_LIST / VIDEO_PAGE / FORUM_THREAD / ARTICLE / OFFICIAL_DOC / PDF / GENERIC_PAGE`。

- `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGate.java`  
  计算 `qualityTier / fastLaneUsable / skipNetworkVerification / contentCompleteness / rejectReason`。

- `backend/src/main/java/cn/bugstack/competitoragent/source/TavilySearchClient.java`  
  真实 HTTP 客户端，只负责请求 Tavily API 并返回原始响应对象。

- `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`  
  实现 `SearchSourceProvider`，负责 profile 生成、调用 client、注册 prefetched content、构造 `SourceCandidate`。

- `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`  
  实现 `CollectionExecutor`，直接消费 `TavilyPrefetchedContentRegistry` 里的正文形成 `CollectionExecutionResult`。

- `backend/src/test/resources/tavily/recommendation-algorithm-response.json`  
  从 `docs/Travily/tavily-recommendation-algorithm-raw.json` 裁剪出的 OPEN_WEB fixture。

- `backend/src/test/resources/tavily/official-docs-response.json`  
  从 `docs/Travily/tavily-official-docs-test-summary.json` 或 raw fixture 裁剪出的 OFFICIAL_DOCS fixture。

- `backend/src/test/resources/tavily/noise-search-page-response.json`  
  包含搜索页、视频列表、raw_content 为空等负样本。

### 修改文件

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`  
  如果当前没有该文件则新增；它是搜索 provider 的上下文请求对象，承接 competitorName、requestedScopes、searchQueries、preferredDomains、includeDomains、blockedDomains、seedCandidates、preferredProviderKey、preferredQueryMode。

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java`  
  增加兼容型 `search(SearchSourceRequest request)` 默认方法，旧 provider 继续走原 `search(String, List<String>)`。

- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`  
  增加 Tavily 快速通道轻量元数据字段，不保存完整 `rawContent`。

- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`  
  增加 `prefetchedContentRef`、`pageType`、`qualityTier`、`contentCompleteness`。

- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`  
  当 candidate `fastLaneUsable=true` 且存在 `prefetchedContentRef` 时，`primaryTool=TAVILY_PREFETCHED`。

- `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`  
  构造器纳入 `TavilyFastLaneProvider`，默认 provider order 增加 `tavily`，保持 fail-open。

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`  
  默认 `providerOrder` 变为 `tavily, qianfan, serpapi, browserPreview, http`；MVP 可通过配置关闭 Tavily。

- `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`  
  对 `skipNetworkVerification=true` 的 Tavily candidate 直接构造 verified target，不发起 DirectHtml / Playwright。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`  
  运行期补源调用改为 `SearchSourceRequest`，把 `searchQueries / preferredDomains / blockedDomains / seedCandidates` 传给 provider。

- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`  
  增加 Tavily evidence repair 所需的 `preferredSearchProvider`、`tavilyQueryMode`、`includeDomains` 字段。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`  
  readiness 摘要增加 Tavily provider。

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSecurityConfigurationGuard.java`  
  校验 `tavily-search.endpoint` 必须是 HTTPS。

- `backend/src/main/resources/application.yml`  
  增加 `tavily-search` 配置和 `source-discovery.search.providers.tavily` 路由配置。

- `backend/src/test/java/...`  
  增加对应单元测试、契约测试、fixture 验收测试。

---

## 3. Task 1: Tavily 配置与 Profile 契约

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProperties.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyQueryMode.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/DomainHint.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/DomainHintSet.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolverTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilySearchPropertiesTest.java`

- [ ] **Step 1: 写 profile resolver 红灯测试**

```java
@Test
void shouldUseOfficialDocsAsAnchorAndKeepOpenWebUnrestricted() {
    DomainHintSet hints = DomainHintSet.builder()
            .competitorName("抖音")
            .domains(List.of(DomainHint.builder()
                    .domain("open.douyin.com")
                    .sourceFamily("docs")
                    .confidence(0.88D)
                    .source("INFERRED")
                    .sourceUrls(List.of("https://open.douyin.com"))
                    .build()))
            .build();

    TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());

    TavilySearchProfile docsProfile = resolver.resolve("抖音", "DOCS", hints, List.of());
    TavilySearchProfile newsProfile = resolver.resolve("抖音", "NEWS", hints, List.of());

    assertThat(docsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
    assertThat(docsProfile.getIncludeDomains()).containsExactly("open.douyin.com");
    assertThat(newsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.OPEN_WEB);
    assertThat(newsProfile.getIncludeDomains()).isEmpty();
}

@Test
void shouldAllowTrustedWebExpansionWhenOfficialAnchorIsInsufficient() {
    DomainHintSet hints = DomainHintSet.builder()
            .competitorName("抖音")
            .domains(List.of(DomainHint.builder()
                    .domain("open.douyin.com")
                    .sourceFamily("docs")
                    .confidence(0.88D)
                    .source("INFERRED")
                    .sourceUrls(List.of("https://open.douyin.com"))
                    .build()))
            .build();

    TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());
    TavilySearchProfile expansionProfile = resolver.resolveTrustedExpansion(
            "抖音",
            "DOCS",
            hints,
            "officialDocHitCount=0; usableContentRatio below threshold"
    );

    assertThat(expansionProfile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
    assertThat(expansionProfile.getIncludeDomains()).isEmpty();
    assertThat(expansionProfile.getExpansionReason()).contains("officialDocHitCount=0");
    assertThat(expansionProfile.getQuery()).contains("抖音");
}

@Test
void shouldUseSuggestedQueryForEvidenceRepairMode() {
    DomainHintSet hints = DomainHintSet.builder()
            .competitorName("抖音")
            .domains(List.of(DomainHint.builder()
                    .domain("open.douyin.com")
                    .sourceFamily("docs")
                    .confidence(0.88D)
                    .source("INFERRED")
                    .sourceUrls(List.of("https://open.douyin.com"))
                    .build()))
            .build();

    TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());
    TavilySearchProfile repairProfile = resolver.resolve(
            "抖音",
            "DOCS",
            hints,
            List.of("推荐算法缺少官方文档支撑")
    );

    assertThat(repairProfile.getQueryMode()).isEqualTo(TavilyQueryMode.EVIDENCE_REPAIR);
    assertThat(repairProfile.getQuery()).isEqualTo("推荐算法缺少官方文档支撑");
}
```

Run: `cd backend; mvn -Dtest=TavilySearchProfileResolverTest test`  
Expected: FAIL，原因是 class 不存在。

- [ ] **Step 2: 实现最小配置与 profile 类**

核心字段必须明确：

```java
@Data
@ConfigurationProperties(prefix = "tavily-search")
public class TavilySearchProperties {
    private boolean enabled = false;
    private String endpoint = "https://api.tavily.com/search";
    private String apiKey;
    private String searchDepth = "advanced";
    private boolean includeRawContent = true;
    private int maxResults = 5;
    private int timeoutSeconds = 20;
    private int maxRetries = 2;
    private int minRawContentChars = 500;
    private double minTavilyScore = 0.45D;
}
```

- [ ] **Step 3: 实现 `TavilySearchProfileResolver` 规则**

规则改为“官方锚点优先，证据不足时受控扩展”：

```text
OFFICIAL / DOCS:
  anchor profile -> OFFICIAL_DOCS + includeDomains
  expansion profile -> TRUSTED_WEB_EXPANSION + no includeDomains
  expansion 仅在 officialDocHitCount / usableContentRatio / mediumOrAboveEvidenceCount 不达标时触发

PRICING:
  anchor profile -> OFFICIAL_DOCS + includeDomains
  expansion profile -> TRUSTED_WEB_EXPANSION + no includeDomains
  定价、套餐、收费结论仍必须保留官方锚点；第三方材料只能辅助说明

NEWS / REVIEW / RESEARCH -> OPEN_WEB + no includeDomains
EVIDENCE_REPAIR -> 使用 external suggestedQueries，不从模板生成
```

`includeDomains` 只接受 confidence >= 0.60 且 domain 非空的 DomainHint。

`TRUSTED_WEB_EXPANSION` 不接收 includeDomains，但必须把 `expansionReason` 写入 profile，后续 audit 用它解释为什么从官方锚点放宽到可信开放网。Resolver 只负责生成 expansion profile，不直接判断内容质量；是否可用由 `TavilyPrefetchedContentGate` 根据 `trustTier / pageType / qualityTier / contentCompleteness / sourceUrls` 判定。

- [ ] **Step 4: 跑 profile 相关测试**

Run: `cd backend; mvn -Dtest=TavilySearchProfileResolverTest,TavilySearchPropertiesTest test`  
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/tavily backend/src/test/java/cn/bugstack/competitoragent/search/tavily
git commit -m "feat: add tavily search profile contracts"
```

---

## 4. Task 2: SearchSourceRequest、SourceCandidate 与 CollectionTaskPackage 元数据

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/SearchSourceProviderRequestCompatibilityTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchRuntimeObjectSlimmingContractTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [ ] **Step 1: 写 SearchSourceRequest 兼容测试**

旧 provider 只实现 `search(String, List<String>)` 时，新入口必须自动委托到旧入口：

```java
@Test
void defaultRequestSearchShouldDelegateToLegacyMethod() {
    LegacyProvider provider = new LegacyProvider();
    SearchSourceRequest request = SearchSourceRequest.builder()
            .competitorName("抖音")
            .requestedScopes(List.of("DOCS"))
            .searchQueries(List.of("抖音 开放平台 API 官方文档"))
            .preferredDomains(List.of("open.douyin.com"))
            .build();

    provider.search(request);

    assertThat(provider.lastCompetitorName).isEqualTo("抖音");
    assertThat(provider.lastRequestedScopes).containsExactly("DOCS");
}
```

Run: `cd backend; mvn -Dtest=SearchSourceProviderRequestCompatibilityTest test`  
Expected: FAIL，`SearchSourceRequest` 不存在。

- [ ] **Step 2: 实现 `SearchSourceRequest` 与接口默认方法**

`SearchSourceRequest` 字段：

```java
String competitorName;
List<String> requestedScopes;
List<String> searchQueries;
List<String> preferredDomains;
List<String> includeDomains;
List<String> blockedDomains;
List<SourceCandidate> seedCandidates;
String preferredProviderKey;
String preferredQueryMode;
```

`SearchSourceProvider` 增加默认方法：

```java
default List<SourceCandidate> search(SearchSourceRequest request) {
    if (request == null) {
        return List.of();
    }
    return search(request.getCompetitorName(), request.getRequestedScopes());
}
```

- [ ] **Step 3: 让 SearchExecutionCoordinator 传递运行期上下文**

运行期补源处构造 request：

```java
SearchSourceRequest.builder()
        .competitorName(config.getCompetitorName())
        .requestedScopes(requestedScopes)
        .searchQueries(config.getSearchQueries())
        .preferredDomains(config.getPreferredDomains())
        .includeDomains(config.getIncludeDomains())
        .blockedDomains(config.getBlockedDomains())
        .seedCandidates(allCandidates)
        .preferredProviderKey(config.getPreferredSearchProvider())
        .preferredQueryMode(config.getTavilyQueryMode())
        .build();
```

新增测试断言 `searchQueries`、`preferredDomains`、`includeDomains` 能传到 fake provider。

- [ ] **Step 4: 给 CollectorNodeConfig 增加 Tavily hint 字段**

新增字段放在 search runtime 字段附近，并加入 `@JsonPropertyOrder`：

```java
private String preferredSearchProvider;
private String tavilyQueryMode;
private List<String> includeDomains;
```

这些字段只表达运行期搜索提示，不改变 Agent prompt 输出格式。

- [ ] **Step 5: 写对象瘦身契约测试**

断言 `SourceCandidate` 不出现 `prefetchedRawContent` 字段，只出现 ref 和元数据：

```java
@Test
void sourceCandidateShouldNotStoreFullTavilyRawContent() {
    List<String> fieldNames = Arrays.stream(SourceCandidate.class.getDeclaredFields())
            .map(Field::getName)
            .toList();

    assertThat(fieldNames).contains(
            "hasPrefetchedContent",
            "prefetchedContentRef",
            "prefetchedRawContentLength",
            "tavilyScore",
            "tavilyRequestId",
            "tavilyQuery",
            "tavilyQueryMode",
            "pageType",
            "qualityTier",
            "fastLaneUsable",
            "fastLaneRejectReason",
            "contentCompleteness",
            "skipNetworkVerification"
    );
    assertThat(fieldNames).doesNotContain("prefetchedRawContent", "rawContent");
}
```

Run: `cd backend; mvn -Dtest=SearchRuntimeObjectSlimmingContractTest#sourceCandidateShouldNotStoreFullTavilyRawContent test`  
Expected: FAIL，字段不存在。

- [ ] **Step 6: 给 `SourceCandidate` 增加轻量字段**

新增字段放在现有搜索运行期字段附近，字段类型使用包装类型，避免旧 JSON 反序列化受影响：

```java
private Boolean hasPrefetchedContent;
private String prefetchedContentRef;
private Integer prefetchedRawContentLength;
private Double tavilyScore;
private String tavilyRequestId;
private String tavilyQuery;
private String tavilyQueryMode;
private String pageType;
private String qualityTier;
private Boolean fastLaneUsable;
private String fastLaneRejectReason;
private String contentCompleteness;
private Boolean skipNetworkVerification;
```

- [ ] **Step 7: 写任务包路由红灯测试**

```java
@Test
void shouldRouteFastLaneUsableCandidateToTavilyPrefetchedTool() {
    CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://open.douyin.com/docs/a")
            .sourceType("DOCS")
            .sourceFamilyKey("official")
            .hasPrefetchedContent(true)
            .prefetchedContentRef("tavily:req-1:0")
            .fastLaneUsable(true)
            .pageType("OFFICIAL_DOC")
            .qualityTier("STRONG")
            .contentCompleteness("FULL_ENOUGH")
            .sourceUrls(List.of("https://open.douyin.com/docs/a"))
            .build();

    CollectionTaskPackage taskPackage = builder.build(1L, "collector", 1L, "抖音", candidate, 0);

    assertThat(taskPackage.getPrimaryTool()).isEqualTo("TAVILY_PREFETCHED");
    assertThat(taskPackage.getPrefetchedContentRef()).isEqualTo("tavily:req-1:0");
    assertThat(taskPackage.getSourceUrls()).contains("https://open.douyin.com/docs/a");
}
```

- [ ] **Step 8: 扩展 `CollectionTaskPackage` 与 builder**

`CollectionTaskPackage` 新增字段：

```java
String prefetchedContentRef;
Integer prefetchedRawContentLength;
String pageType;
String qualityTier;
String contentCompleteness;
```

`CollectionTaskPackageBuilder.resolvePrimaryTool(...)` 前置判断：

```text
candidate.fastLaneUsable == true
&& candidate.hasPrefetchedContent == true
&& prefetchedContentRef 非空
=> primaryTool = TAVILY_PREFETCHED
```

- [ ] **Step 9: 跑对象契约、request 兼容与任务包测试**

Run: `cd backend; mvn -Dtest=SearchSourceProviderRequestCompatibilityTest,SearchExecutionCoordinatorTest,SearchRuntimeObjectSlimmingContractTest,CollectionTaskPackageBuilderTest test`  
Expected: PASS。

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java backend/src/test/java/cn/bugstack/competitoragent/source/SearchSourceProviderRequestCompatibilityTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchRuntimeObjectSlimmingContractTest.java backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java
git commit -m "feat: carry tavily search request context"
```

---

## 5. Task 3: Tavily HTTP Client 与 Provider

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContent.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentRegistry.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyDomainHintResolver.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilySearchClient.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilySearchClientTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyDomainHintResolverTest.java`

- [ ] **Step 1: 写 client 请求契约测试**

使用 mock `HttpClient` 不访问真实网络，断言 request body 包含：

```json
{
  "query": "抖音 开放平台 API 官方文档",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5,
  "include_domains": ["open.douyin.com"]
}
```

Run: `cd backend; mvn -Dtest=TavilySearchClientTest test`  
Expected: FAIL，client 不存在。

- [ ] **Step 2: 实现 Tavily HTTP client**

实现要求：

```text
POST tavily-search.endpoint
Header:
  Content-Type: application/json
  Accept: application/json
  Authorization: Bearer <apiKey>
Timeout:
  tavily-search.timeout-seconds
Retry:
  失败重试 maxRetries 次
Failure:
  用空结果 fail-open，不抛出到 RoutingSearchSourceProvider 之外
```

必须处理 `InterruptedException`：恢复中断标记并返回失败结果。

- [ ] **Step 3: 写 Provider 映射测试**

断言 fixture 中一条 `raw_content` 非空结果会变成：

```text
providerKey=tavily
discoveryMethod=TAVILY_FAST_LANE
hasPrefetchedContent=true
prefetchedContentRef 非空
sourceUrls 包含 Tavily url
tavilyQueryMode in [OFFICIAL_DOCS, OPEN_WEB, TRUSTED_WEB_EXPANSION]
```

如果命中受控扩展，则断言：

```text
tavilyQueryMode=TRUSTED_WEB_EXPANSION
includeDomains 为空
expansionReason 非空
sourceUrls 包含 Tavily url
qualityTier 至少为 MEDIUM 才能 fastLaneUsable=true
```

- [ ] **Step 4: 实现 `TavilyFastLaneProvider`**

`descriptor()` 固定为：

```java
SearchSourceProviderDescriptor.builder()
        .providerKey("tavily")
        .displayName("Tavily Fast Lane")
        .capabilities(List.of("WEB_SEARCH", "PREFETCHED_CONTENT", "GLOBAL_RESULTS"))
        .defaultEnabled(false)
        .defaultFailOpen(true)
        .build();
```

MVP 默认关闭，通过配置显式打开。这样不会影响当前主线。

`TavilyFastLaneProvider` 必须重写 `search(SearchSourceRequest request)`，从 request 中读取 `searchQueries / includeDomains / preferredDomains / seedCandidates / preferredQueryMode`。旧的 `search(String competitorName, List<String> requestedScopes)` 只构造最小 `SearchSourceRequest` 后委托给新入口，避免 Tavily 丢失上下文。

- [ ] **Step 5: 实现 DomainHint bootstrap**

`TavilyDomainHintResolver` 按以下来源构建 hints：

```text
1. request.includeDomains -> source=USER_OR_ORCHESTRATION, confidence=0.95
2. request.preferredDomains -> source=CONFIG_HINT, confidence=0.85
3. request.seedCandidates 中 official/docs 且 verified=true 的 domain -> source=VERIFIED_CANDIDATE, confidence=0.80
4. Tavily OPEN_WEB bootstrap 返回结果中的高频品牌域名 -> source=OPEN_WEB_BOOTSTRAP, confidence=0.60
```

当 `OFFICIAL_DOCS` profile 没有 includeDomains 时，Provider 先执行一次 `OPEN_WEB` bootstrap，不加 `include_domains`；提取候选官方域名后，再执行 `OFFICIAL_DOCS`。bootstrap 结果只用于构建 DomainHintSet，不直接标记 HIGH trust。

bootstrap 失败、超时或返回空结果时，`OFFICIAL_DOCS` profile 降级为不加 `include_domains` 的 Tavily 调用；不能因为 bootstrap 失败而跳过整个 `OFFICIAL_DOCS` 搜索。

当 `OFFICIAL_DOCS` 首轮结果经过 Gate 后仍不满足证据阈值时，Provider 可以追加一次 `TRUSTED_WEB_EXPANSION` profile。该扩展不使用 `include_domains`，但必须：

```text
1. 记录 expansionReason
2. 对同一 domain 设置可用结果数量上限
3. 只允许 Gate 后 qualityTier in [STRONG, MEDIUM] 的结果进入 fast lane
4. 对 PRICING / OFFICIAL / DOCS 强事实结论保留官方锚点要求
5. Tavily 扩展失败时 fail-open，不阻塞原采集链路
```

- [ ] **Step 6: 实现 registry**

Registry 行为：

```text
register(content) -> ref
remove(ref) -> Optional<TavilyPrefetchedContent>
size() -> 测试和诊断使用
```

`remove(ref)` 必须使用 `ConcurrentHashMap.remove` 做原子消费，避免 `get + evict` 在并发场景下重复消费同一份正文。ref 格式：`tavily:{requestId}:{rank}`。如果响应没有 requestId，用本地 UUID。

- [ ] **Step 7: 跑 provider 测试**

Run: `cd backend; mvn -Dtest=TavilySearchClientTest,TavilyFastLaneProviderTest,TavilyDomainHintResolverTest test`  
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/tavily backend/src/main/java/cn/bugstack/competitoragent/source/TavilySearchClient.java backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java backend/src/test/java/cn/bugstack/competitoragent/source/TavilySearchClientTest.java backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java backend/src/test/java/cn/bugstack/competitoragent/search/tavily
git commit -m "feat: add tavily fast lane provider"
```

---

## 6. Task 4: Prefetched Content Gate

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPageTypeClassifier.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGate.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyPageTypeClassifierTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContentGateTest.java`

- [ ] **Step 1: 写 pageType 分类测试**

覆盖负样本：

```java
assertThat(classifier.classify("https://www.douyin.com/search/抖音推荐算法机制", "抖音推荐算法机制", "", Set.of()))
        .isEqualTo("SEARCH_PAGE");
assertThat(classifier.classify("https://www.bilibili.com/video/BV123", "推荐算法分析", "正文", Set.of()))
        .isEqualTo("VIDEO_PAGE");
assertThat(classifier.classify("https://www.reddit.com/r/abc/comments/1", "thread", "long text", Set.of()))
        .isEqualTo("FORUM_THREAD");
assertThat(classifier.classify("https://open.douyin.com/docs/api", "API 文档", "接口说明", Set.of("open.douyin.com")))
        .isEqualTo("OFFICIAL_DOC");
```

- [ ] **Step 2: 实现分类优先级**

优先级固定：

```text
SEARCH_PAGE
VIDEO_LIST
VIDEO_PAGE
FORUM_THREAD
PDF
OFFICIAL_DOC
ARTICLE
GENERIC_PAGE
```

`FORUM_THREAD` 必须先按 domain 清单判断，再进入正文型文章判断。

- [ ] **Step 3: 写 qualityTier 与 usable 测试**

断言：

```text
官方文档 rawContentLength=557 + officialDomainMatched + OFFICIAL_DOC => STRONG
东方财富 PDF rawContentLength=76846 + readableChineseChars>500 => MEDIUM 或 STRONG，但 completeness=PARTIAL
douyin search page + rawContentLength=0 => REJECT
课程大纲 rawContentLength<1500 => WEAK
```

- [ ] **Step 4: 实现 Gate**

Gate 输出写回 `SourceCandidate.toBuilder()`，规则固定为：

```text
fastLaneUsable =
  qualityTier in [STRONG, MEDIUM]
  && pageType in [ARTICLE, OFFICIAL_DOC, PDF, VIDEO_PAGE]
  && rawContentLength >= tavilyProperties.minRawContentChars
  && sourceUrls 非空

skipNetworkVerification =
  fastLaneUsable
  && pageType in [ARTICLE, OFFICIAL_DOC, PDF]
  && rawContentLength >= 2000
  && tavilyScore >= tavilyProperties.minTavilyScore
```

PDF 默认 `contentCompleteness=PARTIAL`。`OFFICIAL_DOC` 短文档在 `officialDomainMatched=true` 且 `rawContentLength>=500` 时可以是 `STRONG`。

- [ ] **Step 5: 跑 Gate 测试**

Run: `cd backend; mvn -Dtest=TavilyPageTypeClassifierTest,TavilyPrefetchedContentGateTest test`  
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/tavily backend/src/test/java/cn/bugstack/competitoragent/search/tavily
git commit -m "feat: add tavily prefetched content gate"
```

---

## 7. Task 5: Provider 路由、配置与 readiness

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSecurityConfigurationGuard.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSecurityConfigurationGuardTest.java`

- [ ] **Step 1: 写路由顺序测试**

断言默认 provider order 包含 `tavily` 且在 `qianfan` 前面；当 Tavily 抛异常且 failOpen=true 时，继续调用 qianfan。

- [ ] **Step 2: 修改 Spring 构造器**

`RoutingSearchSourceProvider` 正式构造器改为注入：

```text
TavilyFastLaneProvider
QianfanSearchSourceProvider
SerpApiSearchSourceProvider
BrowserPreviewSearchSourceProvider
HttpSearchSourceProvider
```

委托列表顺序与 `SearchProviderProperties.providerOrder` 保持一致。

- [ ] **Step 3: 修改默认配置**

`SearchProviderProperties.providerOrder` 默认值：

```java
private List<String> providerOrder = List.of("tavily", "qianfan", "serpapi", "browserPreview", "http");
```

`application.yml` 增加：

```yaml
tavily-search:
  enabled: false
  endpoint: https://api.tavily.com/search
  api-key: ${TAVILY_API_KEY:}
  search-depth: advanced
  include-raw-content: true
  max-results: 5
  timeout-seconds: 20
  max-retries: 2
  min-raw-content-chars: 500
  min-tavily-score: 0.45
  trusted-expansion-enabled: true
  max-usable-results-per-domain: 2

source-discovery:
  search:
    providers:
      tavily:
        enabled: false
        fail-open: true
```

- [ ] **Step 4: readiness 与 security guard 纳入 Tavily**

Readiness provider map 增加 `tavily`。Security guard 校验 `tavily-search.endpoint` 非空时必须是 HTTPS。

- [ ] **Step 5: 跑路由与配置测试**

Run: `cd backend; mvn -Dtest=RoutingSearchSourceProviderTest,SearchCapabilityReadinessGuardTest,SearchSecurityConfigurationGuardTest test`  
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSecurityConfigurationGuard.java backend/src/main/resources/application.yml backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java backend/src/test/java/cn/bugstack/competitoragent/search
git commit -m "feat: route tavily as fail-open search provider"
```

---

## 8. Task 6: TavilyPrefetchedExecutor 与采集路由

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`

- [ ] **Step 1: 写 executor registry 测试**

```java
@Test
void shouldResolveTavilyPrefetchedExecutorByPrimaryTool() {
    TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
    CollectionExecutor tavilyExecutor = new TavilyPrefetchedExecutor(registry);
    CollectionExecutor webExecutor = new WebPageCollectionExecutor(null, null);
    CollectionExecutorRegistry executorRegistry = new CollectionExecutorRegistry(List.of(tavilyExecutor, webExecutor));

    CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
            .primaryTool("TAVILY_PREFETCHED")
            .prefetchedContentRef("tavily:req-1:0")
            .build();

    assertThat(executorRegistry.resolve(taskPackage).executorType()).isEqualTo("TAVILY_PREFETCHED");
}
```

- [ ] **Step 2: 写 executor 成功测试**

注册一条 content，执行后断言：

```text
success=true
executorType=TAVILY_PREFETCHED
content=cleanedContent
sourceUrls 包含原 URL
qualitySignals 包含 TAVILY_RAW_CONTENT_READY
执行后 registry.size() 为 0
再次执行同一个 ref 返回 CONTENT_UNUSABLE，避免重复消费
```

- [ ] **Step 3: 实现 executor**

`supports()`：

```java
return taskPackage != null && "TAVILY_PREFETCHED".equalsIgnoreCase(taskPackage.getPrimaryTool());
```

`execute()`：

```text
1. 校验 taskPackage 和 prefetchedContentRef
2. 通过 registry.remove(ref) 原子消费 TavilyPrefetchedContent
3. 做轻量清洗，截断“相关推荐 / 猜你喜欢 / 热门推荐”之后的噪声段
4. 构造 CollectionExecutionResult
```

消费代码必须是单步 remove，不允许 `get + evict`：

```java
TavilyPrefetchedContent content = registry.remove(ref)
        .orElseThrow(() -> new IllegalStateException("prefetched content already consumed: " + ref));
```

失败时：

```text
success=false
failureKind=CONTENT_UNUSABLE 或 RUNTIME_FAILURE
qualitySignals=[TAVILY_PREFETCHED_CONTENT_MISSING]
sourceUrls 仍来自 taskPackage
```

- [ ] **Step 4: 跑采集 executor 测试**

Run: `cd backend; mvn -Dtest=TavilyPrefetchedExecutorTest,CollectionExecutorRegistryTest test`  
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java backend/src/test/java/cn/bugstack/competitoragent/collection
git commit -m "feat: collect tavily prefetched content"
```

---

## 9. Task 7: CandidateVerifier 快速通道短路

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`

- [ ] **Step 1: 写验证短路测试**

构造 `skipNetworkVerification=true` 的 Tavily candidate，mock `SourceCollector`，断言 `sourceCollector.collect()` 不被调用：

```java
@Test
void shouldSkipNetworkVerificationForStrongTavilyFastLaneCandidate() {
    SourceCollector sourceCollector = mock(SourceCollector.class);
    CandidateVerifier verifier = new CandidateVerifier(sourceCollector);
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://open.douyin.com/docs/api")
            .sourceType("DOCS")
            .providerKey("tavily")
            .hasPrefetchedContent(true)
            .fastLaneUsable(true)
            .skipNetworkVerification(true)
            .pageType("OFFICIAL_DOC")
            .qualityTier("STRONG")
            .sourceUrls(List.of("https://open.douyin.com/docs/api"))
            .build();

    CandidateVerificationResult result = verifier.verify("抖音", "DOCS", List.of(candidate));

    assertThat(result.getVerifiedCandidateCount()).isEqualTo(1);
    verifyNoInteractions(sourceCollector);
}
```

- [ ] **Step 2: 在 `verifyOneCandidate` 开头加短路判断**

短路只接受：

```text
providerKey=tavily
fastLaneUsable=true
skipNetworkVerification=true
sourceUrls 非空
pageType in [ARTICLE, OFFICIAL_DOC, PDF]
```

短路 candidate 写回：

```text
verified=true
verificationReason=TAVILY_FAST_LANE_GATE_VERIFIED
selectionStage=VERIFIED
selectionReason=通过 Tavily Prefetched Content Gate，跳过网络重验
qualitySignals += TAVILY_VERIFICATION_SKIPPED
```

- [ ] **Step 3: 确认弱候选仍走原验证链路**

新增测试：`fastLaneUsable=false` 或 `pageType=SEARCH_PAGE` 时仍会尝试 DirectHtml / Playwright 验证或被标记丢弃。

- [ ] **Step 4: 跑验证测试**

Run: `cd backend; mvn -Dtest=CandidateVerifierTest test`  
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java
git commit -m "feat: skip verification for strong tavily candidates"
```

---

## 10. Task 8: Tavily 审计与 replay 摘要

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchAuditSummary.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java`

- [ ] **Step 1: 写审计序列化测试**

目标 JSON 字段：

```json
{
  "tavilyFastLaneAudit": {
    "queriesSent": 3,
    "totalResults": 15,
    "fastLaneUsableCount": 9,
    "fastLaneRejectedCount": 6,
    "fallbackTriggered": true,
    "rejectionReasons": {
      "SEARCH_PAGE": 2,
      "WEAK_CONTENT": 3,
      "LOW_SCORE": 1
    }
  }
}
```

断言旧 JSON 反序列化时没有该字段也能成功。

- [ ] **Step 2: 实现 `TavilyFastLaneAudit`**

字段固定：

```text
queryModes
queriesSent
totalResults
fastLaneUsableCount
fastLaneRejectedCount
rejectionReasons
fallbackTriggered
tavilyRequestIds
playwrightInvocationBaselineHint
```

- [ ] **Step 3: 在 provider / gate 汇总审计元数据**

MVP 不建表，审计跟随 `SearchAuditSnapshot` 和 `SearchExecutionTrace`。`rawContent` 不进入 audit。

- [ ] **Step 4: 跑审计测试**

Run: `cd backend; mvn -Dtest=SearchAuditSnapshotCompatibilityTest,SearchAuditTimelineContractTest test`  
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAudit.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchAuditSummary.java backend/src/test/java/cn/bugstack/competitoragent/search
git commit -m "feat: audit tavily fast lane outcomes"
```

---

## 11. Task 9: Orchestration 半自动补证据接入

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapter.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionService.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionPolicyServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/DecisionExecutorAdapterTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/orchestration/OrchestrationDecisionServiceTest.java`

执行前提：Task 9 以当前包含 `DecisionPolicyService` 和 `DecisionExecutorAdapter` 的 3.4 基线为前提。若实际执行分支尚未完成 3.4，则先完成 3.4；或者把本任务中的 `DecisionPolicyService / DecisionExecutorAdapter` 改为 Create，并同步评估是否需要由既有 `OrchestrationDecisionService / OrchestrationDecisionAdapter` 先承接最小映射。

- [ ] **Step 1: 写动作映射测试**

断言：

```text
missing_evidence + official gap -> APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE / tavilyQueryMode=EVIDENCE_REPAIR
unsupported_claim -> APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE / query 来自 claim text
DOMAIN_HINT_DISCOVERY -> MVP 不自动执行，返回 MANUAL_REVIEW 或 NO_ACTION with reason
```

- [ ] **Step 2: 扩展 DecisionPolicyResult 元数据**

增加可选字段：

```text
preferredSearchProvider=tavily
tavilyQueryMode=EVIDENCE_REPAIR
suggestedQueries=[...]
includeDomainPolicy=NARROW_OFFICIAL | OPEN_WEB
```

不要新增完全独立的底层 action enum，保持映射到现有 `APPEND_DYNAMIC_BRANCH / SUPPLEMENT_EVIDENCE`。

- [ ] **Step 3: `DecisionExecutorAdapter` 生成动态 collector branch**

生成 collector 节点时把 Tavily hint 写入 nodeConfig map，字段名直接对齐 `CollectorNodeConfig`：

```java
config.put("preferredSearchProvider", "tavily");
config.put("tavilyQueryMode", "EVIDENCE_REPAIR");
config.put("searchQueries", decision.getSuggestedQueries());
config.put("includeDomains", resolveIncludeDomains(decision));
config.put("preferredDomains", resolvePreferredDomains(decision));
```

`DOMAIN_HINT_DISCOVERY` 在 MVP 中不生成 collector branch，只写入 `MANUAL_ONLY` 或 `NO_MUTATION`，reason 必须说明“DomainHint discovery is a search bootstrap operation in MVP”。

- [ ] **Step 4: 跑 Orchestration 测试**

Run: `cd backend; mvn -Dtest=DecisionPolicyServiceTest,DecisionExecutorAdapterTest,OrchestrationDecisionServiceTest test`  
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java backend/src/main/java/cn/bugstack/competitoragent/orchestration backend/src/test/java/cn/bugstack/competitoragent/orchestration
git commit -m "feat: map orchestration evidence repair to tavily"
```

---

## 12. Task 10: A/B/C/D 验收与 EvidenceSource 质量指标

**Files:**
- Create: `backend/src/test/resources/tavily/recommendation-algorithm-response.json`
- Create: `backend/src/test/resources/tavily/official-docs-response.json`
- Create: `backend/src/test/resources/tavily/noise-search-page-response.json`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAcceptanceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`

- [ ] **Step 1: 固化 fixture**

从 `docs/Travily` 测试产物裁剪三类 fixture：

```text
OPEN_WEB 正样本：第三方文章、研报、博客，raw_content >= 2000
OFFICIAL_DOCS 正样本：官方文档、规则、API，raw_content >= 500
负样本：搜索页、视频列表页、raw_content 为空、课程大纲
```

Fixture 只保留测试所需字段：`title / url / content / raw_content / score / request_id`。

- [ ] **Step 2: 写 A/B/C/D 指标测试**

指标对象可在测试内定义，不进入生产代码：

```text
Baseline A: 不启用 Tavily
Experiment B: Tavily OPEN_WEB，不加 include_domains
Experiment C: Tavily family-aware，官方类首轮加 include_domains
Experiment D: Tavily family-aware + TRUSTED_WEB_EXPANSION + fallback 保留
```

最低断言：

```text
traceableEvidenceRatio = 100%
mediumOrAboveEvidenceCount 高于 baseline fixture
officialDocHitCount 在 OFFICIAL/DOCS 场景高于 baseline fixture
openWebDiversityCount 高于 include_domains-only fixture
trustedExpansionUsableCount 高于 include_domains-only fixture
OFFICIAL/DOCS/PRICING 强事实结论保留官方锚点来源
thinContentRatio 不高于 baseline fixture
collectionFailureRatio 不高于 baseline fixture
playwrightInvocationCount 低于 baseline fixture
所有 REJECT 有 fastLaneRejectReason
```

- [ ] **Step 3: 写 Gate 负样本回归测试**

断言：

```text
douyin.com/search/... => SEARCH_PAGE + REJECT
bilibili.com/search?... => SEARCH_PAGE 或 VIDEO_LIST，不进入 executor
reddit.com/... => FORUM_THREAD，trustTier 不得为 HIGH
raw_content 为空但 content 有一句摘要 => REJECT
```

- [ ] **Step 4: 跑验收测试**

Run: `cd backend; mvn -Dtest=TavilyFastLaneAcceptanceTest,SearchAndCollectionGoldenMasterTest test`  
Expected: PASS。

- [ ] **Step 5: 跑搜索、采集、编排相关回归**

Run:

```bash
cd backend
mvn -Dtest="*Tavily*,RoutingSearchSourceProviderTest,CandidateVerifierTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,SearchAuditSnapshotCompatibilityTest,DecisionPolicyServiceTest,DecisionExecutorAdapterTest" test
```

Expected: PASS。

- [ ] **Step 6: 跑完整后端测试**

Run: `cd backend; mvn test`  
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/resources/tavily backend/src/test/java/cn/bugstack/competitoragent/search/tavily backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java
git commit -m "test: verify tavily fast lane evidence quality"
```

---

## 13. 上线开关与回滚策略

MVP 合入后默认关闭 Tavily：

```yaml
tavily-search:
  enabled: false

source-discovery:
  search:
    providers:
      tavily:
        enabled: false
        fail-open: true
```

灰度开启步骤：

```text
1. 在测试环境设置 TAVILY_API_KEY。
2. 打开 tavily-search.enabled=true。
3. 打开 source-discovery.search.providers.tavily.enabled=true。
4. 保持 qianfan / serpapi / http / browserPreview 原顺序兜底。
5. 首批只跑 DOCS / OFFICIAL / REVIEW 三类样本任务。
6. 观察 TavilyFastLaneAudit 与 EvidenceSource 质量指标。
```

回滚方式：

```text
1. 设置 source-discovery.search.providers.tavily.enabled=false。
2. 保留 tavily-search.enabled=false。
3. 不需要回滚数据库，因为 MVP 不建表、不迁移 schema。
4. 原千帆 / SerpApi / DirectHtml / Jina / Playwright 链路继续可用。
```

---

## 14. 完成标准

代码完成标准：

```text
1. Tavily Provider 默认关闭，显式配置后可参与 RoutingSearchSourceProvider。
2. Tavily API 失败、超时、额度异常时 fail-open，不中断原搜索链路。
3. SourceCandidate 不保存完整 raw_content。
4. Gate 能拒绝搜索页、视频列表、空 raw_content、弱正文。
5. Strong Tavily candidate 可跳过网络重验。
6. TavilyPrefetchedExecutor 能直接产出 CollectionExecutionResult。
7. 每个成功 CollectionExecutionResult 都有 sourceUrls。
8. Audit 能看到 usable / rejected / fallback / rejectionReasons。
9. Orchestration 的补证据动作可映射到 Tavily evidence repair，但不自动执行 DOMAIN_HINT_DISCOVERY。
```

验收完成标准：

```text
1. traceableEvidenceRatio = 100%。
2. mediumOrAboveEvidenceCount 高于 baseline。
3. OFFICIAL/DOCS 场景 officialDocHitCount 高于 baseline。
4. OPEN_WEB 场景 openWebDiversityCount 高于 include_domains-only 策略。
5. 受控扩展场景 trustedExpansionUsableCount 高于 include_domains-only 策略。
6. OFFICIAL/DOCS/PRICING 强事实结论保留官方锚点来源，第三方材料不能作为唯一依据。
7. thinContentRatio 不高于 baseline。
8. collectionFailureRatio 不高于 baseline。
9. playwrightInvocationCount 低于 baseline。
10. 所有 REJECT 都有 fastLaneRejectReason。
11. 至少一个下游环节能消费 Tavily 产生的 EvidenceSource。
```

---

## 15. 自检清单

- [ ] 计划没有要求替换千帆 / SerpApi / Playwright。
- [ ] `include_domains` 只作为 family-aware 旋钮使用。
- [ ] `VIDEO_PAGE` 是 pageType，不是默认 Source Family。
- [ ] DomainHintSet bootstrap 不单点依赖 LLM 猜域名。
- [ ] `raw_content` 不进入 `SourceCandidate`、searchAudit 大对象或 replay 大对象。
- [ ] Tavily API 不可用时可透明降级。
- [ ] 验收指标能证明 EvidenceSource 质量提升，而不是只证明 API 调通。
