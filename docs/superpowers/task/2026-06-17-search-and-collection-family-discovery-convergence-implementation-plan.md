# Search And Collection Family Discovery Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不启动第八轮 `Wave 12` 下游证据闭环之前，先把 `架构 1` 当前仍未收口的 `official / github` discovery 缺口补齐，让搜索与采集主链路真正落到“家族驱动的分层采集架构”，并把 `PUBLIC_SEARCH` 收回到补漏工具角色。

**Architecture:** 本轮是 `第八轮之前` 的前置收敛计划，不替代第八轮。总体顺序固定为 `红灯契约 -> Source Family Direct Discovery Planner -> preview/runtime 共用 direct discovery -> 验证与补源顺序收口 -> 审计口径补齐 -> 回链第八轮执行顺序`。实现上复用现有 `SearchSourceCatalogProperties / SearchPolicyResolver / HeuristicSourceDiscoveryService / SearchExecutionCoordinator / CollectionTaskPackageBuilder`，新增一个专职 `SourceFamilyDirectDiscoveryPlanner` 承担“稳定 locator / 官方根域模板 -> 直达候选”的解释职责，避免再把这层语义散落进 coordinator 和 discovery service。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito

---

## Plan Positioning

这份计划的定位必须先说清楚：

1. 它不是新一轮“推翻第八轮”的重写稿。
2. 它是插在“第七轮已完成”和“第八轮尚未执行”之间的前置收敛任务。
3. 它的目标不是做 `Wave 12` 下游证据闭环，而是先把上游 discovery 链路调到与 `docs/superpowers/specs/2026-06-17-search-and-collection-architecture-design.md` 一致。

执行顺序固定为：

1. 先执行本计划，补齐 `official / github` 的 family-first discovery 缺口。
2. 再执行 `docs/superpowers/task/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md`。

如果跳过这一步直接做第八轮，会出现两个问题：

1. 下游虽然开始消费更正式的证据视图，但 upstream 仍可能把 `official` 和 `github` 候选过度交给公网搜索排序决定。
2. 第八轮做完后，后续若再回头改 discovery 语义，会让 `sourceUrls / evidenceCoverage / issueFlags` 的验收口径再次漂移。

---

## Scope Guard

### 本轮必须完成

1. `official` 家族在存在显式官网 URL、根域或稳定入口时，必须先走 `DIRECT_LOCATOR / FAMILY_TEMPLATE` 直达候选，不得默认先绕 `PUBLIC_SEARCH`。
2. `github` 家族在存在显式 repo URL 或稳定 locator 时，必须直接进入 `GITHUB_API` owner 路径前的正式候选集，不得把公网搜索当成前置步骤。
3. preview 与 runtime 必须共享同一套 direct discovery 解释，不允许 `HeuristicSourceDiscoveryService` 和 `SearchExecutionCoordinator` 各写一套官方/仓库推断逻辑。
4. `SearchExecutionCoordinator` 必须显式表达“direct discovery 已满足 -> 跳过公网补源”和“direct discovery 不足 -> 才允许 public search supplement”的分界。
5. `sourceUrls`、`discoveryMethod`、`sourceFamilyKey`、`sourceFamilyRole` 必须在 direct candidate、search supplement candidate、audit snapshot 中全程保留。
6. `providedUrl` 带路径时，行为必须统一收口：
   - `official` 家族保留原始完整 URL 作为 `DIRECT_LOCATOR` 候选；
   - `official` 的 `FAMILY_TEMPLATE` 只允许基于归一化后的根域展开，不能拿深路径继续拼 `/pricing`、`/docs`；
   - `github` 稳定 repo URL 保持完整路径，不做“先截根域再展开”。
7. planner 可以按 family 生成 direct candidates，但进入 preview plan / runtime node 前，必须按当前 `sourceType` 做正式过滤：
   - `OFFICIAL` 节点只保留官网入口类候选；
   - `DOCS` 节点只保留文档类候选；
   - `PRICING` 节点只保留定价类候选；
   - 不允许把 `PRICING` 候选混进 `DOCS` 节点，或把 `DOCS` 候选混进 `OFFICIAL` 节点再靠排序兜底。
8. 旧节点兼容必须被显式处理：
   - 若 nodeConfig 已有 `sourceCandidates`，且它们已经包含 `DIRECT_LOCATOR / FAMILY_TEMPLATE` 等新语义，则直接复用；
   - 若 nodeConfig 中只有旧版 `CONFIG` root-only 候选，或候选明显缺失 family direct discovery 语义，则允许 runtime 重新经 planner 构建 direct candidates；
   - 不能因为“配置里非空”就永久锁死旧节点，让 SEO 补源继续接管。
9. 测试必须覆盖真实关键差异：
   - `official` 提供根域时，planner 的 family 级 direct discovery 可以生成官网 / pricing / docs 候选，但进入具体 node 后必须只保留与该 `sourceType` 匹配的候选
   - `official` 提供深路径 URL 时，保留原路径 direct candidate，但模板展开只基于根域
   - `github` 显式 repo URL 直达，不触发公网搜索
   - direct candidate 足够时 `searchSourceProvider.search(...)` 不应被调用
   - direct candidate 不足时才允许补源
   - 旧节点只有 `CONFIG` root-only `sourceCandidates` 时，runtime 会自动重建 family direct candidates，而不是直接复用旧候选

### 本轮明确不做

1. 不启动第八轮 `Wave 12` 的下游统一证据视图、`TASK / DOMAIN` 记忆边界与报告投影工作。
2. 不实现新的 `News API`、`Atom`、subscription / cursor / 去重窗口。
3. 不重写 `RoutingSearchSourceProvider` 为多态总线，也不引入需要改动整个 provider SPI 的大重构。
4. 不实现“仅凭 competitorName 自动搜索 GitHub 仓库”的全新远端 GitHub discovery provider；本轮先收口显式 repo URL / locator 和稳定 direct path。
5. 不重做 `DagExecutor`、跨重启 replay 持久化底座、`TaskSnapshotCacheService` 总体恢复模型。
6. 不修改第八轮的业务目标；第八轮只后移顺序，不改目标。

---

## Current Stage

当前阶段：第七轮 `RSS` 专项 owner 已完成；第八轮 `Wave 12` 计划已写完但尚未执行；`架构 1` 已冻结为“家族驱动的分层采集架构”。结合代码现状，真正还卡在上游执行顺序上的，是 `official / github` discovery 侧仍未完全摆脱“公网搜索前置”的默认心智。

- [x] `架构 1` 冻结：已完成
- [x] 第七轮 `RSS` owner 收口：已完成
- [x] 第八轮 `Wave 12` 实施计划落稿：已完成
- [x] 第八轮尚未执行这一事实复核：已完成
- [ ] family discovery convergence 前置计划：待执行
- [ ] 第八轮 `Wave 12`：等待本计划完成后执行

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase M1 | 锁定 family-first discovery 红灯契约 | 0.5 天 | `架构 1` 已冻结，第八轮尚未执行 | 待执行 |
| Phase M2 | 引入 `SourceFamilyDirectDiscoveryPlanner` 与 catalog 边界 | 0.5-1 天 | Phase M1 完成 | 待执行 |
| Phase M3 | 让 preview discovery 与 runtime initial candidates 共用 planner | 1 天 | Phase M2 完成 | 待执行 |
| Phase M4 | 收口 direct candidate 与 public search supplement 的边界 | 0.5-1 天 | Phase M3 完成 | 待执行 |
| Phase M5 | 自动化复核、文档回链与第八轮执行顺序确认 | 0.5 天 | Phase M1-M4 完成 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`

### Backend - Test

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

### Docs - Modify

- `docs/superpowers/task/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md`
- `docs/superpowers/task/2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md`

---

### Task 1: 锁定 family-first discovery 红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [ ] **Step 1: 先锁定 official 家族的 direct discovery 红灯测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFamilyDirectDiscoveryPlannerTest {

    @Test
    void shouldBuildOfficialDirectCandidatesFromProvidedRootBeforePublicSearch() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Acme AI",
                "DOCS",
                List.of("https://www.acme.ai")
        );

        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .contains("https://www.acme.ai", "https://www.acme.ai/pricing", "https://www.acme.ai/docs");
        assertThat(candidates).allMatch(candidate ->
                "official".equals(candidate.getSourceFamilyKey())
                        && "PRIMARY_VERTICAL".equals(candidate.getSourceFamilyRole())
                        && List.of("DIRECT_LOCATOR", "FAMILY_TEMPLATE").contains(candidate.getDiscoveryMethod()));
    }

    @Test
    void shouldKeepProvidedOfficialPathAsDirectLocatorButExpandTemplatesFromRootOnly() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Acme AI",
                "DOCS",
                List.of("https://www.acme.ai/products/ai-platform")
        );

        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .contains("https://www.acme.ai/products/ai-platform")
                .contains("https://www.acme.ai/pricing", "https://www.acme.ai/docs");
        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .doesNotContain("https://www.acme.ai/products/ai-platform/pricing")
                .doesNotContain("https://www.acme.ai/products/ai-platform/docs");
    }
}
```

- [ ] **Step 2: 锁定 github 显式 repo URL 直达红灯测试**

```java
@Test
void shouldBuildGithubDirectCandidateFromExplicitRepoUrlWithoutSearchProvider() {
    SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

    List<SourceCandidate> candidates = planner.buildInitialCandidates(
            "Acme AI",
            "GITHUB",
            List.of("https://github.com/acme/rocket")
    );

    assertThat(candidates).singleElement().satisfies(candidate -> {
        assertThat(candidate.getUrl()).isEqualTo("https://github.com/acme/rocket");
        assertThat(candidate.getSourceFamilyKey()).isEqualTo("github");
        assertThat(candidate.getDiscoveryMethod()).isEqualTo("DIRECT_LOCATOR");
        assertThat(candidate.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
    });
}
```

- [ ] **Step 3: 扩展 runtime / preview 红灯测试，锁定 direct candidate 足够时不应再触发公网补源**

```java
@Test
void shouldSkipPublicSearchSupplementWhenOfficialDirectCandidatesAlreadyVerified() {
    when(searchSourceProvider.search(any(), any())).thenReturn(List.of());

    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("Acme AI")
            .sourceType("DOCS")
            .competitorUrls(List.of("https://www.acme.ai"))
            .verifyCandidates(Boolean.TRUE)
            .browserSearchEnabled(Boolean.TRUE)
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .build());

    verify(searchSourceProvider, never()).search(any(), any());
    assertThat(result.getExecutionTrace().getFallbackDecision())
            .isEqualTo("SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH");
}
```

```java
@Test
void shouldExposeFamilyTemplateCandidatesInPreviewPlan() {
    List<SourcePlan> plans = service.discover(
            "Acme AI",
            List.of("https://www.acme.ai"),
            List.of("官网", "产品文档", "定价页面")
    );

    assertThat(plans).anyMatch(plan -> "DOCS".equals(plan.getSourceType())
            && plan.getCandidates().stream().allMatch(candidate ->
            "DOCS".equals(candidate.getSourceType())));
    assertThat(plans).anyMatch(plan -> "PRICING".equals(plan.getSourceType())
            && plan.getCandidates().stream().allMatch(candidate ->
            "PRICING".equals(candidate.getSourceType())));
}
```

- [ ] **Step 4: 运行首批红灯测试**

Run:
`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest,CollectionTaskPackageBuilderTest" test`

Expected:

- FAIL
- `SourceFamilyDirectDiscoveryPlanner` 尚不存在
- preview/runtime 还未共享同一套 direct discovery 解释
- `SearchExecutionCoordinator` 仍可能在 direct candidate 足够时继续落到公网补源分支

---

### Task 2: 正式引入 `SourceFamilyDirectDiscoveryPlanner` 与 catalog 边界

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`

- [ ] **Step 1: 给 Source Family Catalog 补正式 direct discovery 配置骨架**

```java
private SourceFamilyProperties createOfficialFamily() {
    SourceFamilyProperties family = new SourceFamilyProperties(
            true,
            SearchProviderRole.PRIMARY_VERTICAL.name(),
            List.of("OFFICIAL", "PRICING", "DOCS"),
            List.of("PRODUCT_PAGE", "PRICING", "DOCUMENTATION"),
            List.of("WEB_SCRAPER", "JINA_READER"),
            List.of("PUBLIC_SEARCH"),
            new UpdatePolicyProperties("DAILY_INCREMENTAL", "PT24H"),
            List.of("search-official", "search-official-domain", "search-pricing-primary", "search-docs-primary")
    );
    family.setDirectPathTemplates(List.of("/", "/pricing", "/docs", "/documentation", "/help"));
    family.setStableLocatorHosts(List.of());
    family.setStableLocatorSchemes(List.of("https"));
    return family;
}

private SourceFamilyProperties createGithubFamily() {
    SourceFamilyProperties family = new SourceFamilyProperties(...);
    family.setDirectPathTemplates(List.of());
    family.setStableLocatorHosts(List.of("github.com"));
    family.setStableLocatorSchemes(List.of("https", "github"));
    return family;
}
```

Implementation note:

1. `directPathTemplates` 只描述“显式官网根域命中后可以优先扩出的稳定路径”，不是站点爬虫规则。
2. `stableLocatorHosts / stableLocatorSchemes` 只用于判断“这个 URL/locator 是否已经足够稳定，允许直接进入 owner 路线”。
3. 这里必须同步定死 `providedUrl` 带路径时的归一化规则：`official` 家族保留完整 URL 作为 `DIRECT_LOCATOR`，但 `directPathTemplates` 一律只对根域展开；不能把深路径拿来继续拼模板，避免生成 `/products/ai-platform/pricing` 这类伪候选。
4. `directPathTemplates` 的解释是 family 级别的；是否进入某个具体 `sourceType` 节点，必须由后续过滤逻辑决定，不能把 family 模板集合原样塞进所有 node。

- [ ] **Step 2: 新建专职 planner，统一把 provided URL / locator 翻译成 direct candidates**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * source family 直达候选规划器。
 * 负责把显式官网 URL、根域、repo URL、稳定 locator 翻译成正式 direct candidates，
 * 让 preview 和 runtime 都复用同一套 family-first discovery 解释。
 */
@Component
public class SourceFamilyDirectDiscoveryPlanner {

    private final SearchPolicyResolver searchPolicyResolver;

    public SourceFamilyDirectDiscoveryPlanner(SearchPolicyResolver searchPolicyResolver) {
        this.searchPolicyResolver = searchPolicyResolver == null ? new SearchPolicyResolver() : searchPolicyResolver;
    }

    public List<SourceCandidate> buildInitialCandidates(String competitorName,
                                                        String sourceType,
                                                        List<String> providedUrls) {
        String familyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(sourceType);
        return switch (familyKey) {
            case "official" -> buildOfficialCandidates(competitorName, sourceType, providedUrls);
            case "github" -> buildGithubCandidates(competitorName, sourceType, providedUrls);
            default -> List.of();
        };
    }
}
```

Implementation note:

1. `official` 家族至少要区分两种输入：
   - 根域 URL：同时生成根域 `DIRECT_LOCATOR` 与根域模板 `FAMILY_TEMPLATE`
   - 深路径 URL：保留原始深路径 `DIRECT_LOCATOR`，再把同源根域提取出来做模板展开
2. `github` 家族则相反：显式 repo URL 的“完整路径”本身就是稳定 locator，不能在 planner 里先归一化成 `https://github.com/` 再丢失仓库语义。
3. planner 产出的结果是 family direct candidate 池；它可以包含同一家族下多个语义入口，但不得直接假定这些候选会被所有 `sourceType` 节点共同消费。

- [ ] **Step 3: 给 resolver 补 planner 所需的正式访问入口**

```java
public List<String> resolveDirectPathTemplates(String familyKey) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
    return family == null || family.getDirectPathTemplates() == null ? List.of() : family.getDirectPathTemplates();
}

public boolean isStableLocatorForSourceFamily(String familyKey, String rawUrl) {
    // 统一处理 github://repo/... 与 https://github.com/{owner}/{repo} 这类稳定 locator。
}
```

- [ ] **Step 4: 运行 planner / resolver 测试**

Run:
`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest" test`

Expected:

- PASS

---

### Task 3: 让 preview discovery 与 runtime initial candidates 共用 planner

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

- [ ] **Step 1: 在 `HeuristicSourceDiscoveryService` 里先接 planner，再补 heuristic / search**

```java
List<SourceCandidate> directCandidates = directDiscoveryPlanner.buildInitialCandidates(
        competitorName,
        scope,
        providedUrls
);

List<SourceCandidate> mergedCandidates = mergeCandidates(
        filterDirectCandidates(scope, directCandidates),
        buildHeuristicCandidates(scope, normalizedRoots, competitorName, providedUrls),
        filterSearchCandidates(scope, searchCandidates)
);
```

Implementation note:

1. `official` 在存在 provided root 时，`FAMILY_TEMPLATE` 不是 heuristic 附庸，而是正式 primary candidate。
2. `github` 在存在显式 repo URL 时，不必再生成“搜索占位计划优先、repo URL 后补”的倒序行为。
3. preview 若已有 direct candidates，就不再把“跳过域名猜测”误写成主要 notes。
4. `HeuristicSourceDiscoveryService` 在把 planner 结果并入具体 `SourcePlan` 前，必须按当前 scope/sourceType 过滤 direct candidates，避免 `DOCS` plan 混入 `PRICING` 入口。

- [ ] **Step 2: 在 `SearchExecutionCoordinator.resolveInitialCandidates(...)` 里复用同一个 planner**

```java
private List<SourceCandidate> resolveInitialCandidates(CollectorNodeConfig config) {
    List<SourceCandidate> configuredCandidates = config.getSourceCandidates();
    if (configuredCandidates != null
            && !configuredCandidates.isEmpty()
            && !shouldRebuildLegacyConfiguredCandidates(configuredCandidates, config)) {
        return configuredCandidates;
    }

    List<SourceCandidate> directCandidates = directDiscoveryPlanner.buildInitialCandidates(
            config.getCompetitorName(),
            config.getSourceType(),
            config.getCompetitorUrls()
    );
    if (!directCandidates.isEmpty()) {
        return filterDirectCandidatesForNode(config.getSourceType(), directCandidates);
    }

    // 仍保留旧兼容：只有 direct discovery 为空时，才回退到 competitorUrls 直生候选。
}
```

Implementation note:

1. `shouldRebuildLegacyConfiguredCandidates(...)` 需要显式识别“旧版 root-only CONFIG 候选”这类历史快照，而不是只看列表是否非空。
2. runtime 重建 direct candidates 的目标是修复旧节点 discovery 语义，不是覆盖已经包含新语义的正式规划快照。
3. `SearchExecutionCoordinator` 复用 planner 结果时，同样必须按当前 node 的 `sourceType` 过滤，保持 preview/runtime 语义一致。

- [ ] **Step 3: 运行 preview/runtime 共用语义回归**

Run:
`mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest" test`

Expected:

- PASS

---

### Task 4: 收口 direct candidate 与 public search supplement 的边界

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [ ] **Step 1: 在 coordinator 里显式区分“direct discovery 已满足”和“需要公网补源”**

```java
boolean directDiscoverySatisfied = verifiedCount >= minVerifiedCount
        && allCandidates.stream().limit(Math.max(1, targetCount)).allMatch(candidate ->
        List.of("DIRECT_LOCATOR", "FAMILY_TEMPLATE").contains(candidate.getDiscoveryMethod()));

if (directDiscoverySatisfied) {
    fallbackDecision = "SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH";
    markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
            "direct discovery 已满足最小验证目标，跳过公网补源");
}
```

Implementation note:

1. 这里不是要禁掉公网补源，而是把它收回成“只有 direct candidate 不足时才触发”的辅助角色。
2. 若 `github` 提供的是稳定 repo URL，最终 `CollectionTaskPackageBuilder` 仍应继续映射到 `GITHUB_API`。
3. 若 direct candidate 来自旧节点自动重建，审计里也要能看出它们属于 `DIRECT_LOCATOR / FAMILY_TEMPLATE`，而不是继续伪装成旧版 `CONFIG` 候选。

- [ ] **Step 2: 锁定 `github` direct candidate 到 `GITHUB_API` 的回归测试**

```java
@Test
void shouldKeepGithubDirectCandidateOnApiOwnerPath() {
    CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://github.com/acme/rocket")
            .sourceType("GITHUB")
            .sourceFamilyKey("github")
            .sourceUrls(List.of("https://github.com/acme/rocket"))
            .build();

    CollectionTaskPackage taskPackage = builder.build(41L, "collect_sources_github", 9L, "Acme AI", candidate, 1);

    assertThat(taskPackage.getPrimaryTool()).isEqualTo("GITHUB_API");
    assertThat(taskPackage.getResourceLocator()).isEqualTo("github://repo/acme/rocket");
}
```

- [ ] **Step 3: 运行 direct vs supplement 边界回归**

Run:
`mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,CollectionTaskPackageBuilderTest" test`

Expected:

- PASS

---

### Task 5: 回链第八轮执行顺序并完成聚合验证

**Files:**

- Modify: `docs/superpowers/task/2026-06-17-search-and-collection-eighth-iteration-downstream-evidence-closure-implementation-plan.md`
- Modify: `docs/superpowers/task/2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md`

- [ ] **Step 1: 给第八轮文档补执行顺序说明**

Update wording like:

```md
本计划仍是未执行的正式第八轮计划，不是历史快照；
但执行前新增前置条件：应先完成
`2026-06-17-search-and-collection-family-discovery-convergence-implementation-plan.md`
中的 `official / github discovery` 收敛，
再启动本轮 `Wave 12` 下游证据闭环。
```

- [ ] **Step 2: 运行本轮聚合测试**

Run:
`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest,HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest,CollectionTaskPackageBuilderTest" test`

Expected:

- PASS

- [ ] **Step 3: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:

- PASS

- [ ] **Step 4: 完成后给出执行顺序结论**

Completion note should say:

```md
当前可执行顺序已明确：
1. 先执行 family discovery convergence；
2. 再执行第八轮 Wave 12；
第八轮不作废，但不再是当前最先启动的任务。
```

---

## Verification

- planner / resolver：`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest" test`
- preview/runtime discovery：`mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest" test`
- github owner 路由回归：`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest" test`
- 本轮整体：`mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest,HeuristicSourceDiscoveryServiceTest,SearchExecutionCoordinatorTest,CollectionTaskPackageBuilderTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `official` 不再默认先绕公网搜索，由 Task 1-4 覆盖。
2. `github` 显式 repo URL / locator 直达 owner 路径，由 Task 1、Task 2、Task 4 覆盖。
3. preview/runtime 共用同一套家族 discovery 解释，由 Task 2、Task 3 覆盖。
4. 旧节点 `CONFIG` root-only 候选不会永久锁死旧 discovery 语义，由 Task 1、Task 3、Task 4 覆盖。
5. 第八轮不作废但顺序后移，由 Task 5 覆盖。

### Placeholder scan

1. 本计划没有把“以后再看第八轮”写成空口说明，而是直接定义了执行顺序。
2. 本计划没有引入新的 GitHub 远端搜索 provider 大重构，只做当前代码骨架可直接承接的收敛。
3. 本计划没有把 `Wave 12` 证据闭环内容偷塞进 scope。

### Type consistency

1. `SourceFamilyDirectDiscoveryPlanner` 只负责 direct candidate 解释，不承担候选验证与最终选源。
2. planner 输出的是 family 级候选池；具体 node 的 scope/sourceType 过滤由 `HeuristicSourceDiscoveryService` 与 `SearchExecutionCoordinator` 负责，各自复用同一规则。
3. `HeuristicSourceDiscoveryService` 继续负责 preview/planning 视角的 source plan 组装。
4. `SearchExecutionCoordinator` 继续负责验证、补源、选源，不重复实现家族 direct discovery 规则，但需要负责旧节点候选重建判定。
5. `CollectionTaskPackageBuilder` 继续只做 owner 路由，不反向决定 discovery 语义。
