# Search And Collection Fourth Iteration Joint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不引入 GitHub 搜索层半成品返工的前提下，联合完成 `Wave 6` discovery 路由闭环、`Wave 7` 最小采集执行接缝，以及首个 GitHub API 结构化采集执行器。

**Architecture:** 本计划不再落地 `GithubApiSearchSourceProvider -> repo URL -> Playwright HTML` 这条会被后续推翻的路径，而是把 `Wave 6` 收敛为 discovery 路由与候选元数据标准化，把 `Wave 7` 提前为最小采集任务包、采集协调器与执行器注册表。GitHub 在本轮只允许两类 owner：discovery 层负责返回稳定候选 URL 与 `resourceLocator`，collection 层由 `ApiDataCollectionExecutor` 直接返回结构化证据，不再要求浏览器主采集 GitHub HTML 页面。

**Tech Stack:** Java 17, Spring Boot, Jackson, Java HttpClient, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. 收口 `Wave 6` discovery 路由中和后续不冲突的部分：
   - `SearchProviderRole` 与 `Source Family Catalog` 的 GitHub 主辅语义
   - `SearchProviderProperties.primaryCandidateThreshold`
   - `SearchProviderProperties.runAuxiliaryWhenPrimarySatisfied`
   - discovery 路由跳过原因与审计投影
2. 在候选标准化阶段补齐后续采集路由必需的元数据：
   - `providerKey`
   - `sourceFamilyKey`
   - `providerRole`
   - `sourceUrls`
3. 新建最小 `CollectionTaskPackage`、任务包构建器、`CollectionExecutionCoordinator`、`CollectionExecutorRegistry` 与最小 `CollectionExecutionResult`。
4. 让 `CollectorAgent` 从“直接调用 `SourceCollector.collect(url, ...)`”演进为“搜索发现 -> 任务包构建 -> 执行器路由 -> 结果兼容映射”的最小闭环。
5. 保留当前 `PlaywrightPageCollector` 的兼容职责，但将其归位为网页执行器适配层，而不是采集主语义的唯一承载者。
6. 新建首个 `ApiDataCollectionExecutor`，以 GitHub 作为第一个结构化采集实现，直接基于仓库 locator 拉取 repo / README / release 等结构化证据。
7. GitHub discovery 与 GitHub collection 共享配置与客户端抽象，避免 endpoint / token / timeout / retry / JSON 解析逻辑重复实现。
8. 自动化测试覆盖以下边界：
   - GitHub discovery owner 与 collection owner 分离
   - 主力 discovery 达标后跳过辅助 provider
   - 候选元数据标准化
   - 最小采集任务包构建与执行器注册
   - GitHub API 结构化采集结果
   - `CollectorAgent` 联合主链路最小闭环

### 本轮明确不做

1. 不实现 `GithubApiSearchSourceProvider implements SearchSourceProvider`。
2. 不实现 GitHub URL -> Playwright HTML 作为 GitHub 主采集路径。
3. 不在本轮引入 `JinaReader` 主路径；该内容仍归 `Wave 8`。
4. 不在本轮重写 `SourceCollector` 老接口；先通过新采集接缝与兼容映射并存落地。
5. 不在本轮实现 News API / RSS 的真实采集执行器。
6. 不在本轮实现完整 `collectionAudit / collectionReplayTimeline / package-level rerun`；这些仍归 `Wave 9`。
7. 不在本轮切换前端采集详情页协议。

### 为什么必须重写旧第四轮计划

旧第四轮计划的问题不只是“GitHub 先做 discovery，后做 collection”，而是它在 owner 边界上会产生真实返工：

1. GitHub API 的 HTTP 调用、认证、重试、JSON 解析会在 discovery provider 与 collection executor 两边各写一份。
2. GitHub 候选最终仍会回到网页采集主链路，和父计划中已经确认的 `Wave 10` 采集 owner 方向冲突。
3. 旧计划没有补齐候选元数据标准化，意味着即使后面新增采集执行器，也没有稳定路由依据。

因此，本轮必须改成联合计划，先把 discovery 可交接的信息和 collection 真正消费的信息连起来。

---

## Phase Plan

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase H1 | 锁定联合红灯契约：GitHub owner 边界、主辅路由、候选元数据标准化 | 0.5 天 | 父计划 `Wave 6 / 7 / 10` 已冻结 | 待执行 |
| Phase H2 | 收口 `Wave 6` discovery 路由与候选标准化 | 1-1.5 天 | Phase H1 红灯测试存在 | 待执行 |
| Phase H3 | 落最小采集执行接缝：任务包、构建器、协调器、执行器注册表 | 1-2 天 | Phase H1 完成 | 待执行 |
| Phase H4 | 让 `CollectorAgent` 接入新采集骨架，并保留现有网页兼容路径 | 1-2 天 | Phase H3 完成 | 待执行 |
| Phase H5 | 实现 GitHub API 共享客户端与结构化采集执行器 | 1-2 天 | Phase H2-H4 完成 | 待执行 |
| Phase H6 | 自动化复核、文档回链与执行收口 | 0.5-1 天 | Phase H1-H5 完成 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistry.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/ApiDataCollectionExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutor.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateNormalizationMetadataTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

### Docs - Modify

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

### Task 1: 锁定联合红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCandidateNormalizationMetadataTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`

- [ ] **Step 1: 写 GitHub owner 边界红灯测试**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderRoleContractTest {

    @Test
    void shouldTreatGithubProviderAsPrimaryVerticalWhenBoundBySourceFamilyCatalog() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();
        catalog.getFamilies().get("github").getToolProviderKeys().put("GITHUB_API", "github");
        SearchProperties properties = new SearchProperties();
        properties.setSourceCatalog(catalog);
        resolver.setSearchProperties(properties);

        assertThat(resolver.resolveProviderRole("github")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
        assertThat(resolver.resolveSourceFamilyRole("github")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
    }
}
```

- [ ] **Step 2: 写主辅 discovery 路由阈值红灯测试**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.search.SearchSourceCatalogProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingSearchSourceProviderPrimaryAuxiliaryTest {

    @Test
    void shouldSkipAuxiliaryWhenPrimaryThresholdSatisfied() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("github", "qianfan"));
        properties.setPrimaryCandidateThreshold(1);
        properties.setRunAuxiliaryWhenPrimarySatisfied(false);

        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();
        catalog.getFamilies().get("github").getToolProviderKeys().put("GITHUB_API", "github");
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.setSourceCatalog(catalog);
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(searchProperties);

        List<String> invocations = new ArrayList<>();
        TestProvider github = new TestProvider("github", List.of(SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .providerKey("github")
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build()), invocations);
        TestProvider qianfan = new TestProvider("qianfan", List.of(), invocations);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(github, qianfan),
                new SourceCandidateRanker(),
                resolver
        );

        provider.search("Acme", List.of("GITHUB"));

        assertThat(invocations).containsExactly("github");
    }

    private record TestProvider(String providerKey,
                                List<SourceCandidate> candidates,
                                List<String> invocations) implements SearchSourceProvider {

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey(providerKey)
                    .displayName(providerKey)
                    .build();
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            invocations.add(providerKey);
            return candidates;
        }
    }
}
```

- [ ] **Step 3: 写候选元数据标准化红灯测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchCandidateNormalizationMetadataTest {

    @Test
    void shouldFillProviderFamilyAndSourceUrlsDuringNormalization() throws Exception {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
                new CandidateVerifier(mock(SourceCollector.class)),
                mock(BrowserSearchRuntimeService.class),
                mock(SearchSourceProvider.class),
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
        Method method = SearchExecutionCoordinator.class.getDeclaredMethod(
                "normalizeCandidates", List.class, String.class, CollectorNodeConfig.class);
        method.setAccessible(true);

        CollectorNodeConfig config = new CollectorNodeConfig();
        config.setSourceType("GITHUB");
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .sourceType("GITHUB")
                .providerKey("github")
                .build();

        @SuppressWarnings("unchecked")
        List<SourceCandidate> normalized = (List<SourceCandidate>) method.invoke(
                coordinator, List.of(candidate), "HTTP", config);

        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).getSourceFamilyKey()).isEqualTo("github");
        assertThat(normalized.get(0).getProviderKey()).isEqualTo("github");
        assertThat(normalized.get(0).getSourceUrls()).containsExactly("https://github.com/acme/rocket");
    }
}
```

- [ ] **Step 4: 写最小采集任务包与执行器注册红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionTaskPackageBuilderTest {

    @Test
    void shouldBuildGithubTaskPackageFromCandidateMetadata() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .sourceType("GITHUB")
                .providerKey("github")
                .sourceFamilyKey("github")
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build();

        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder();
        CollectionTaskPackage taskPackage = builder.build(
                41L,
                "collect_sources_01_01",
                9L,
                "Acme AI",
                candidate,
                1
        );

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("GITHUB_API");
        assertThat(taskPackage.getResourceLocator()).isEqualTo("github://repo/acme/rocket");
        assertThat(taskPackage.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
    }
}
```

```java
package cn.bugstack.competitoragent.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionExecutorRegistryTest {

    @Test
    void shouldResolveGithubApiExecutorByPrimaryTool() {
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(null);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("API_DATA");
    }
}
```

说明：这里用反射调用 `normalizeCandidates(...)` 只是 Task 1 阶段的临时红灯手段，用来先锁死候选元数据补齐规则。等 Task 2 完成、`SearchExecutionCoordinator` 已经能通过公开执行链路返回标准化后的 `sourceCandidates` 后，必须把这个测试替换为公开 API 验证方式，例如断言 `SearchExecutionResult` 中候选的 `providerRole`、`sourceFamilyKey`、`resourceLocator`、`sourceUrls` 已补齐；不保留“反射测私有方法”作为最终验收形态。

- [ ] **Step 5: 运行红灯测试**

Run:
`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCandidateNormalizationMetadataTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`

Expected:
- FAIL
- `RoutingSearchSourceProvider` 还不支持通过 `SearchPolicyResolver` 判断主辅角色
- `normalizeCandidates(...)` 还不会补齐 `sourceFamilyKey / sourceUrls`
- `CollectionTaskPackageBuilder` / `CollectionExecutorRegistry` / `CollectionExecutor` 尚不存在

---

### Task 2: 收口 Wave 6 discovery 路由与候选标准化

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`

- [ ] **Step 1: 新建 GitHub API 共享配置对象**

```java
package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "github-api")
public class GithubApiProperties {

    private boolean enabled = false;
    private String endpoint = "https://api.github.com";
    private String apiToken;
    private int timeoutSeconds = 15;
    private int maxRetries = 2;
}
```

- [ ] **Step 2: 让 Source Family Catalog 为 GitHub 提供稳定 provider 绑定**

```java
private SourceFamilyProperties createGithubFamily() {
    SourceFamilyProperties family = new SourceFamilyProperties(
            true,
            SearchProviderRole.PRIMARY_VERTICAL.name(),
            List.of("GITHUB", "OPEN_SOURCE"),
            List.of("REPOSITORY", "STAR_TREND", "RELEASE"),
            List.of("GITHUB_API"),
            List.of("PUBLIC_SEARCH"),
            new UpdatePolicyProperties("DAILY_API_POLLING", "PT24H"),
            List.of("search-github-repository", "search-github-release")
    );
    family.getToolProviderKeys().put("GITHUB_API", "github");
    family.getToolProviderKeys().put("PUBLIC_SEARCH", "qianfan");
    return family;
}
```

- [ ] **Step 3: 让 `SearchPolicyResolver` 能从 catalog 推导 provider role**

```java
public SearchProviderRole resolveProviderRole(String providerKey) {
    if (!StringUtils.hasText(providerKey)) {
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }
    String normalized = providerKey.trim().toLowerCase(Locale.ROOT);
    for (Map.Entry<String, SearchSourceCatalogProperties.SourceFamilyProperties> entry
            : resolveSourceCatalog().getFamilies().entrySet()) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = entry.getValue();
        if (family == null) {
            continue;
        }
        if (family.resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL).stream()
                .anyMatch(bound -> normalized.equalsIgnoreCase(bound))) {
            return SearchProviderRole.PRIMARY_VERTICAL;
        }
    }
    return SearchProviderRole.AUXILIARY_PUBLIC;
}
```

- [ ] **Step 4: 让 `RoutingSearchSourceProvider` 依赖 resolver 判断主辅，而不是假设 descriptor 自带 role**

```java
private final SearchPolicyResolver searchPolicyResolver;

public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                   List<? extends SearchSourceProvider> delegateProviders,
                                   SourceCandidateRanker sourceCandidateRanker,
                                   SearchPolicyResolver searchPolicyResolver) {
    this.properties = properties;
    this.delegateProviders = List.copyOf(delegateProviders);
    this.sourceCandidateRanker = sourceCandidateRanker;
    this.searchPolicyResolver = searchPolicyResolver;
}
```

实现说明：这里新增 `SearchPolicyResolver` 依赖后，除了 Spring 注入路径，还要逐个检查所有手动 `new RoutingSearchSourceProvider(...)` 的调用点并同步更新，特别是测试代码里的直接构造场景。目标是一次性完成构造器签名收口，避免出现“生产装配已升级、测试构造器还停留在旧签名”的半完成状态。

```java
int primaryCandidateCount = 0;
boolean primarySatisfied = false;

for (String providerKey : resolveProviderOrder()) {
    SearchSourceProvider provider = providersByKey.get(normalizeProviderKey(providerKey));
    if (provider == null) {
        continue;
    }
    SearchProviderRole role = searchPolicyResolver.resolveProviderRole(provider.descriptor().getProviderKey());
    if (primarySatisfied
            && role == SearchProviderRole.AUXILIARY_PUBLIC
            && !properties.isRunAuxiliaryWhenPrimarySatisfied()) {
        continue;
    }
    List<SourceCandidate> providerCandidates = provider.search(competitorName, requestedScopes);
    if (!providerCandidates.isEmpty()) {
        mergedCandidates.addAll(providerCandidates);
        if (role == SearchProviderRole.PRIMARY_VERTICAL) {
            primaryCandidateCount += providerCandidates.size();
            primarySatisfied = primaryCandidateCount >= Math.max(1, properties.getPrimaryCandidateThreshold());
        }
    }
}
```

- [ ] **Step 5: 在候选标准化阶段补齐后续采集路由需要的元数据**

```java
private List<SourceCandidate> normalizeCandidates(List<SourceCandidate> candidates,
                                                  String stage,
                                                  CollectorNodeConfig config) {
    if (candidates == null || candidates.isEmpty()) {
        return List.of();
    }
    String sourceFamilyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(config.getSourceType());
    return sourceCandidateRanker.rankAndDeduplicate(candidates.stream()
            .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
            .map(candidate -> {
                SourceCandidate base = normalizeCandidateCanonicalUrl(sourceCandidateRanker.ensureScores(candidate));
                if (base == null) {
                    return null;
                }
                String providerKey = StringUtils.hasText(base.getProviderKey())
                        ? base.getProviderKey()
                        : ("HTTP".equals(stage) ? "http" : "browser");
                return base.toBuilder()
                        .sourceFamilyKey(StringUtils.hasText(base.getSourceFamilyKey()) ? base.getSourceFamilyKey() : sourceFamilyKey)
                        .providerKey(providerKey)
                        .providerRole(searchPolicyResolver.resolveProviderRole(providerKey).name())
                        .sourceUrls((base.getSourceUrls() == null || base.getSourceUrls().isEmpty())
                                ? List.of(base.getUrl())
                                : base.getSourceUrls())
                        .selectionStage(StringUtils.hasText(base.getSelectionStage()) ? base.getSelectionStage() : stage)
                        .selectionReason(StringUtils.hasText(base.getSelectionReason())
                                ? base.getSelectionReason()
                                : ("PLANNED".equals(stage) ? "来自规划期补源候选" : "来自运行期补源候选"))
                        .build();
            })
            .filter(java.util.Objects::nonNull)
            .filter(candidate -> !isBlockedDomain(candidate, config.getBlockedDomains()))
            .toList());
}
```

- [ ] **Step 6: 让搜索 provider 在产出候选时主动补齐 `providerKey` 与 `sourceUrls`**

```java
SourceCandidate.builder()
        .url(url)
        .providerKey("qianfan")
        .sourceUrls(List.of(url))
        .sourceType(scope)
        .discoveryMethod("QIANFAN_SEARCH")
        .build();
```

```java
SourceCandidate.builder()
        .url(url)
        .providerKey("serpapi")
        .sourceUrls(List.of(url))
        .sourceType(scope)
        .discoveryMethod("SERPAPI_SEARCH")
        .build();
```

```java
SourceCandidate.builder()
        .url(url)
        .providerKey("http")
        .sourceUrls(List.of(url))
        .sourceType(scope)
        .discoveryMethod("SEARCH")
        .build();
```

- [ ] **Step 7: 运行 discovery 路由与候选标准化测试**

Run:
`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCandidateNormalizationMetadataTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,RoutingSearchSourceProviderTest" test`

Expected:
- PASS

验证备注：如果到这一步 `SearchExecutionCoordinator` 已能通过公开 API 返回标准化候选，这里要顺手把 `SearchCandidateNormalizationMetadataTest` 从反射私有方法的临时写法替换为公开 API 测试，再执行下列命令，确保最终留在仓库里的不是反射测试。

---

### Task 3: 落最小采集执行接缝

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutor.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistry.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`

- [ ] **Step 1: 新建最小采集任务包**

```java
package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CollectionTaskPackage {
    Long taskId;
    String nodeName;
    Long planVersionId;
    String competitorName;
    String sourceFamilyKey;
    String sourceType;
    String primaryTool;
    String url;
    String resourceLocator;
    List<String> targetFields;
    Integer priority;
    List<String> sourceUrls;
}
```

- [ ] **Step 2: 把任务包构建职责从协调器里拆出**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
public class CollectionTaskPackageBuilder {

    public CollectionTaskPackage build(Long taskId,
                                       String nodeName,
                                       Long planVersionId,
                                       String competitorName,
                                       SourceCandidate candidate,
                                       int priority) {
        String primaryTool = resolvePrimaryTool(candidate);
        return CollectionTaskPackage.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .competitorName(competitorName)
                .sourceFamilyKey(candidate == null ? null : candidate.getSourceFamilyKey())
                .sourceType(candidate == null ? null : candidate.getSourceType())
                .primaryTool(primaryTool)
                .url(candidate == null ? null : candidate.getUrl())
                .resourceLocator(resolveResourceLocator(candidate, primaryTool))
                .targetFields(resolveTargetFields(primaryTool))
                .priority(priority)
                .sourceUrls(candidate == null ? List.of() : candidate.getSourceUrls())
                .build();
    }

    private String resolvePrimaryTool(SourceCandidate candidate) {
        if (candidate != null && "github".equalsIgnoreCase(candidate.getProviderKey())) {
            return "GITHUB_API";
        }
        return "WEB_SCRAPER";
    }

    private String resolveResourceLocator(SourceCandidate candidate, String primaryTool) {
        if (candidate == null || candidate.getUrl() == null) {
            return null;
        }
        if (!"GITHUB_API".equals(primaryTool)) {
            return candidate.getUrl();
        }
        URI uri = URI.create(candidate.getUrl());
        String[] segments = uri.getPath().split("/");
        if (segments.length >= 3) {
            return "github://repo/" + segments[1] + "/" + segments[2];
        }
        return candidate.getUrl();
    }

    private List<String> resolveTargetFields(String primaryTool) {
        if ("GITHUB_API".equals(primaryTool)) {
            return List.of("repository", "stars", "readme", "latestRelease");
        }
        return List.of("content");
    }
}
```

实现说明：当前只有 GitHub 一个 API 执行器，这个 `github -> GITHUB_API` 硬编码本轮可以接受，但必须在实现时加一个显式 TODO，说明后续新增 News API、工商 API、专利 API 等执行器后，这里要改为根据 `SourceFamilyCatalog.toolProviderKeys` 做反向查找，不能继续扩散 provider key 硬编码。

- [ ] **Step 3: 新建最小执行结果、执行器接口与注册表**

```java
package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class CollectionExecutionResult {
    String executorType;
    boolean success;
    String resourceLocator;
    String title;
    String content;
    List<String> sourceUrls;
    Map<String, Object> structuredPayload;
    String errorMessage;
}
```

```java
package cn.bugstack.competitoragent.collection;

public interface CollectionExecutor {

    String executorType();

    boolean supports(CollectionTaskPackage taskPackage);

    CollectionExecutionResult execute(CollectionTaskPackage taskPackage);
}
```

```java
package cn.bugstack.competitoragent.collection;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CollectionExecutorRegistry {

    private final List<CollectionExecutor> executors;

    public CollectionExecutorRegistry(List<CollectionExecutor> executors) {
        this.executors = executors;
    }

    public CollectionExecutor resolve(CollectionTaskPackage taskPackage) {
        return executors.stream()
                .filter(executor -> executor.supports(taskPackage))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no collection executor for " + taskPackage.getPrimaryTool()));
    }
}
```

- [ ] **Step 4: 新建采集协调器骨架**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CollectionExecutionCoordinator {

    private final CollectionTaskPackageBuilder packageBuilder;
    private final CollectionExecutorRegistry executorRegistry;

    public CollectionExecutionCoordinator(CollectionTaskPackageBuilder packageBuilder,
                                          CollectionExecutorRegistry executorRegistry) {
        this.packageBuilder = packageBuilder;
        this.executorRegistry = executorRegistry;
    }

    public List<CollectionExecutionResult> execute(Long taskId,
                                                   String nodeName,
                                                   Long planVersionId,
                                                   String competitorName,
                                                   List<SearchCollectionTarget> targets) {
        List<CollectionExecutionResult> results = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            SearchCollectionTarget target = targets.get(index);
            SourceCandidate candidate = target == null ? null : target.getCandidate();
            CollectionTaskPackage taskPackage = packageBuilder.build(
                    taskId,
                    nodeName,
                    planVersionId,
                    competitorName,
                    candidate,
                    index + 1
            );
            CollectionExecutor executor = executorRegistry.resolve(taskPackage);
            results.add(executor.execute(taskPackage));
        }
        return results;
    }
}
```

- [ ] **Step 5: 新建网页执行器兼容适配层**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.SourceCollector;
import org.springframework.stereotype.Component;

@Component
public class WebPageCollectionExecutor implements CollectionExecutor {

    private final SourceCollector sourceCollector;

    public WebPageCollectionExecutor(SourceCollector sourceCollector) {
        this.sourceCollector = sourceCollector;
    }

    @Override
    public String executorType() {
        return "WEB_PAGE";
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null && !"GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        SourceCollector.CollectedPage page = sourceCollector.collect(
                taskPackage.getUrl(),
                taskPackage.getCompetitorName(),
                taskPackage.getSourceType()
        );
        return CollectionExecutionResult.builder()
                .executorType(executorType())
                .success(page.isSuccess())
                .resourceLocator(taskPackage.getResourceLocator())
                .title(page.getTitle())
                .content(page.getContent())
                .sourceUrls(taskPackage.getSourceUrls())
                .errorMessage(page.getErrorMessage())
                .build();
    }
}
```

- [ ] **Step 6: 运行采集接缝测试**

Run:
`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`

Expected:
- PASS

---

### Task 4: 让 CollectorAgent 接入新采集骨架

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

- [ ] **Step 1: 给 `CollectorAgent` 注入采集协调器**

```java
private final CollectionExecutionCoordinator collectionExecutionCoordinator;
```

```java
public CollectorAgent(AgentExecutionLogRepository logRepository,
                      SourceCollector sourceCollector,
                      EvidenceSourceRepository evidenceRepository,
                      TaskNodeRepository nodeRepository,
                      AgentContextAssembler agentContextAssembler,
                      SearchExecutionCoordinator searchExecutionCoordinator,
                      CollectionExecutionCoordinator collectionExecutionCoordinator,
                      TaskRetrievalIndexService taskRetrievalIndexService,
                      ObjectMapper objectMapper) {
    super(logRepository, agentContextAssembler);
    this.sourceCollector = sourceCollector;
    this.evidenceRepository = evidenceRepository;
    this.nodeRepository = nodeRepository;
    this.searchExecutionCoordinator = searchExecutionCoordinator;
    this.collectionExecutionCoordinator = collectionExecutionCoordinator;
    this.taskRetrievalIndexService = taskRetrievalIndexService;
    this.objectMapper = objectMapper;
}
```

- [ ] **Step 2: 把采集主循环切到协调器结果驱动**

实现提示：本轮只增加一个 `CollectionExecutionCoordinator` 依赖仍可接受，但 `CollectorAgent` 构造器已经偏长。如果这一步执行时发现测试 mock 或装配复杂度继续上升，优先把更多采集编排细节继续收口到协调器门面，而不是再向 `CollectorAgent` 追加新的细粒度依赖。

```java
List<CollectionExecutionResult> collectionResults = collectionExecutionCoordinator.execute(
        context.getTaskId(),
        context.getCurrentNodeName(),
        context.getPlanVersionId(),
        config.getCompetitorName(),
        targets
);
```

Implementation note:
- 本轮不要求完全删除原始 `sourceCollector.collect(...)` 兼容逻辑。
- 允许先通过 `CollectionExecutionResult -> SourceCollector.CollectedPage / resultEntry / EvidenceSource` 做兼容映射。
- 但 GitHub 这类 API executor 的结果不允许再回退到“没有正文 HTML 就无法持久化”的旧约束。

- [ ] **Step 3: 给结构化采集结果增加最小兼容映射**

```java
private SourceCollector.CollectedPage mapCollectionResultToCollectedPage(CollectionExecutionResult result,
                                                                         String competitorName,
                                                                         String sourceType) {
    String effectiveUrl = result.getSourceUrls() != null && !result.getSourceUrls().isEmpty()
            ? result.getSourceUrls().get(0)
            : result.getResourceLocator();
    String snippet = result.getContent() == null
            ? null
            : result.getContent().substring(0, Math.min(500, result.getContent().length()));
    String metadata = result.getStructuredPayload() == null
            ? null
            : objectMapper.writeValueAsString(result.getStructuredPayload());
    return SourceCollector.CollectedPage.builder()
            .url(effectiveUrl)
            .title(result.getTitle())
            .content(result.getContent())
            .snippet(snippet)
            .metadata(metadata)
            .competitorName(competitorName)
            .sourceType(sourceType)
            .success(result.isSuccess())
            .errorMessage(result.getErrorMessage())
            .build();
}
```

Implementation note:
- `objectMapper.writeValueAsString(...)` 失败时应按当前工程约定记录日志并降级为 `null`，不要让兼容映射打断主流程。

- [ ] **Step 4: 写最小联合集成测试**

```java
@Test
void shouldCollectGithubViaApiExecutorWithoutOpeningHtmlPage() {
    // arrange:
    // - selected target carries providerKey=github and sourceFamilyKey=github
    // - collection coordinator returns API_DATA success result with structuredPayload
    // assert:
    // - collector output still contains sourceUrls
    // - evidence metadata contains structured payload
    // - sourceCollector.collect(...) is not required for this GitHub target
}
```

- [ ] **Step 5: 运行 CollectorAgent 测试**

Run:
`mvn -pl backend "-Dtest=CollectorAgentTest" test`

Expected:
- PASS

---

### Task 5: 实现 GitHub API 共享客户端与首个结构化采集执行器

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiClient.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/ApiDataCollectionExecutor.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutor.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/GithubApiCollectionExecutorTest.java`

- [ ] **Step 1: 新建 GitHub API 共享客户端**

```java
package cn.bugstack.competitoragent.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class GithubApiClient {

    private final GithubApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GithubApiClient(GithubApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public JsonNode fetchRepository(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo);
    }

    public JsonNode fetchReadme(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo + "/readme");
    }

    public JsonNode fetchLatestRelease(String owner, String repo) {
        return exchange("/repos/" + owner + "/" + repo + "/releases/latest");
    }

    private JsonNode exchange(String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getEndpoint() + path))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET()
                    .header("Accept", "application/vnd.github+json");
            if (StringUtils.hasText(properties.getApiToken())) {
                builder.header("Authorization", "Bearer " + properties.getApiToken());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("github api status=" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("github api request failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: 新建 API executor 抽象**

```java
package cn.bugstack.competitoragent.collection;

public abstract class ApiDataCollectionExecutor implements CollectionExecutor {

    @Override
    public String executorType() {
        return "API_DATA";
    }
}
```

- [ ] **Step 3: 让 GitHub executor 按 locator 直接采 repo，而不是再次 search**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.GithubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GithubApiCollectionExecutor extends ApiDataCollectionExecutor {

    private final GithubApiClient githubApiClient;

    public GithubApiCollectionExecutor(GithubApiClient githubApiClient) {
        this.githubApiClient = githubApiClient;
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null && "GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        String[] repoRef = parseRepoLocator(taskPackage.getResourceLocator());
        if (repoRef == null) {
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(false)
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .errorMessage("invalid github resource locator")
                    .build();
        }

        String owner = repoRef[0];
        String repo = repoRef[1];
        JsonNode repository = githubApiClient.fetchRepository(owner, repo);
        JsonNode readme = githubApiClient.fetchReadme(owner, repo);
        JsonNode latestRelease = tryFetchLatestRelease(owner, repo);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repository", repository.path("full_name").asText());
        payload.put("stars", repository.path("stargazers_count").asInt());
        payload.put("defaultBranch", repository.path("default_branch").asText());
        payload.put("htmlUrl", repository.path("html_url").asText());
        payload.put("readme", decodeBase64(readme.path("content").asText()));
        payload.put("latestReleaseTag", latestRelease == null ? null : latestRelease.path("tag_name").asText());

        String sourceUrl = repository.path("html_url").asText();
        return CollectionExecutionResult.builder()
                .executorType(executorType())
                .success(true)
                .resourceLocator(taskPackage.getResourceLocator())
                .title(repository.path("full_name").asText())
                .content(repository.path("description").asText())
                .sourceUrls(sourceUrl == null || sourceUrl.isBlank() ? taskPackage.getSourceUrls() : List.of(sourceUrl))
                .structuredPayload(payload)
                .build();
    }

    private String[] parseRepoLocator(String locator) {
        if (locator == null || !locator.startsWith("github://repo/")) {
            return null;
        }
        String[] segments = locator.substring("github://repo/".length()).split("/");
        if (segments.length != 2) {
            return null;
        }
        return segments;
    }

    private JsonNode tryFetchLatestRelease(String owner, String repo) {
        try {
            return githubApiClient.fetchLatestRelease(owner, repo);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String decodeBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: 写 GitHub API executor 测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.GithubApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubApiCollectionExecutorTest {

    @Test
    void shouldReturnStructuredGithubEvidenceFromRepoLocator() throws Exception {
        GithubApiClient client = mock(GithubApiClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(client.fetchRepository("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "full_name": "acme/rocket",
                  "stargazers_count": 42,
                  "description": "Acme AI agent platform",
                  "html_url": "https://github.com/acme/rocket",
                  "default_branch": "main"
                }
                """));
        when(client.fetchReadme("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "content": "%s"
                }
                """.formatted(Base64.getEncoder().encodeToString("Acme README".getBytes(StandardCharsets.UTF_8)))));
        when(client.fetchLatestRelease("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "tag_name": "v1.2.3"
                }
                """));

        GithubApiCollectionExecutor executor = new GithubApiCollectionExecutor(client);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .sourceUrls(java.util.List.of("https://github.com/acme/rocket"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredPayload()).containsEntry("repository", "acme/rocket");
        assertThat(result.getStructuredPayload()).containsEntry("latestReleaseTag", "v1.2.3");
        assertThat(result.getStructuredPayload().get("readme")).isEqualTo("Acme README");
    }
}
```

- [ ] **Step 5: 运行 GitHub API executor 测试**

Run:
`mvn -pl backend "-Dtest=GithubApiCollectionExecutorTest" test`

Expected:
- PASS

---

### Task 6: 联合复核、文档回链与执行收口

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

- [ ] **Step 1: 回写父计划的 Wave 6 / Wave 7 状态**

Update parent plan with wording like:

```md
第四轮实施已改写为 `Wave 6` discovery 路由收口与 `Wave 7` 最小采集执行接缝的联合计划。
GitHub 不再作为 `SearchSourceProvider -> Playwright HTML` 半成品路径落地，
而是在候选标准化阶段补齐可交接元数据，并由 `ApiDataCollectionExecutor` 承接首个结构化采集 owner。
```

- [ ] **Step 2: 回写 specs 状态**

Update `搜索与采集` 实施说明，强调：

```md
第四轮实施不再单独交付 GitHub vertical search provider，
而是联合交付 discovery 路由闭环、候选元数据标准化和最小采集执行接缝，
以避免后续 `Wave 10` 对 GitHub API 产生明显返工。
```

- [ ] **Step 3: 运行联合验证**

Run:
`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCandidateNormalizationMetadataTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,GithubApiCollectionExecutorTest,CollectorAgentTest,SearchPropertiesBindingTest,RoutingSearchSourceProviderTest,SearchPolicyResolverTest" test`

Expected:
- PASS

Run:
`mvn -pl backend test`

Expected:
- PASS

- [ ] **Step 4: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，最推荐下一步直接进入 `Wave 8` 双路径网页采集：
1. `JinaReader` 主路径
2. `Playwright` 重型兜底
3. `renderHints / failureKind` 首轮正式化
4. 网页采集质量评分与结构块抽取
```

---

## Verification

- discovery 路由与候选标准化：`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchCandidateNormalizationMetadataTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,RoutingSearchSourceProviderTest" test`
- 最小采集接缝：`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`
- GitHub API executor：`mvn -pl backend "-Dtest=GithubApiCollectionExecutorTest" test`
- Collector 联合主链路：`mvn -pl backend "-Dtest=CollectorAgentTest" test`
- backend 全量硬性 gate：`mvn -pl backend test`

---

## Self-Review

1. 本计划明确放弃 `GithubApiSearchSourceProvider`，避免和父计划中已经冻结的 discovery owner / collection owner 边界冲突。
2. 本计划先修 discovery 可交接信息，再修 collection 执行接缝，避免“新增执行器却没有稳定路由依据”的伪完成。
3. 本计划把 GitHub executor 改成按 `resourceLocator` 直接采 repo，而不是再次按竞品名执行搜索，避免 discovery 与 collection 重新耦合。
4. 本计划没有提前实现 `JinaReader`、`collectionAudit`、包级 rerun、前端协议切换，这些仍按父计划归属后续波次，避免 scope 膨胀。
