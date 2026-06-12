# Search And Collection First Iteration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按新的搜索与采集优化方案，完成首轮三个 blocking 收口包：执行真相收口、质量止血、连续性事实源。

**Architecture:** 本实施计划只继承 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 的 `P0/P1` 阻塞排序和三类首轮收口包，不复用旧 Task 轴施工结构。实现顺序固定为 `红灯基线 -> 执行真相收口包 -> 质量止血包 -> 连续性事实源包 -> 集成复核`，每一包都以测试先行，并且不扩散到大恢复、大共享上下文或前端全量协议切换。本轮同时把 [CollectorAgent.md:56](/E:/java_study/Mul-agnet/docs/problem/CollectorAgent.md:56) 提出的“私域垂直 API 主力、公网搜索辅助”固化为正式设计方向，并新增 `Source Family Catalog` 配置骨架，把官网、新闻、GitHub 建模为数据源家族，而不是把外部 API / 搜索引擎当成业务来源本身。

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. `SearchPolicyResolver` 成为规划期、预览期、运行期共同使用的统一策略入口。
2. `HEURISTIC` 从正式计划 / fallback 顺序 / 预览配置中清理，或降级为仅历史兼容输入。
3. `PromptTemplateService` 与 `backend/src/main/resources/prompts/search-queries.yml` 的模板治理口径拉齐。
4. `SearchKeywordPolicy` 首轮正式覆盖 `OFFICIAL / PRICING / DOCS / NEWS / REVIEW`，并把营销页 veto 规则正式化。
5. `searchAudit` 最小正式 DTO 进入 runtime / insight / replay / event。
6. 所有新增或收口对象继续满足 `sourceUrls` 可追溯红线。
7. resolver / 配置 / 测试口径不再继续把私域垂直 API 与公网搜索 provider 当成完全对等的串扫对象，必须为后续“主力 / 辅助”路由留出正式语义位置。
8. 新增 `Source Family Catalog` 配置骨架，首轮至少声明 `official / news / github` 三类家族、主辅工具、更新策略和 query template 引用。

### 本轮明确不做

1. 不重写 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总体职责。
2. 不做搜索详情页、回放页、事件 reducer 的前端全量正式切换。
3. 不做 tier/filter/ranker 全链路重构。
4. 不做跨重启 replay 持久化底座。
5. 不在本文件里继续扩写第二轮、第三轮的非 blocking 项。
6. 不在本轮内一次完成主辅 provider 的预算编排、成本平台、全量降级与命中即止产品化。
7. 不在本轮真实接入 Twitter / Reddit、Crunchbase、Patent API；News API 与 GitHub API 只做配置架构承载，不做外部调用闭环。

---

## File Structure

**Backend - Create**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionTruthContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchKeywordPolicyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchEnginePropertiesTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchProviderRole.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- `backend/src/main/resources/prompts/search-queries.yml`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`

---

### Task 1: 锁定首轮三类收口包的红灯基线

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionTruthContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchKeywordPolicyTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchEnginePropertiesTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`

- [x] **Step 1: 新建执行真相红灯测试，锁定 `HEURISTIC` 不再进入正式 fallback 顺序**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchExecutionTruthContractTest {

    private final SearchPolicyResolver resolver = new SearchPolicyResolver();

    @Test
    void shouldRemoveHeuristicFromFormalFallbackOrder() {
        assertEquals(List.of("PLANNED", "BROWSER", "HTTP"), resolver.resolveFallbackOrder("HYBRID", true));
        assertEquals(List.of("PLANNED", "HTTP"), resolver.resolveFallbackOrder("HYBRID", false));
        assertEquals(List.of("PLANNED", "HTTP"), resolver.resolveFallbackOrder("HEURISTIC_ONLY", false));
    }

    @Test
    void shouldResolveSearchEngineByAliasAndEnabledFlag() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.get("duckduckgo").setEnabled(true);

        assertEquals("duckduckgo", resolver.resolveSearchEngineKey("ddg", properties));
        assertEquals("duckduckgo", properties.resolveAvailableEngineKey("ddg"));
        assertFalse(properties.resolveEnabledEngineKeys("ddg", List.of()).isEmpty());
    }
}
```

- [x] **Step 2: 新建关键词策略红灯测试，锁定 `DOCS / NEWS / REVIEW` 中文词表与营销页 veto**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchKeywordPolicyTest {

    private final SearchKeywordPolicy policy = new SearchKeywordPolicy();

    @Test
    void shouldExposeChineseExpectedKeywordsForDocsNewsAndReview() {
        assertTrue(policy.expectedKeywords("DOCS").contains("文档"));
        assertTrue(policy.expectedKeywords("NEWS").contains("发布日志"));
        assertTrue(policy.expectedKeywords("REVIEW").contains("评测"));
    }

    @Test
    void shouldExposeMarketingAndHighValueSignalsSeparately() {
        assertTrue(policy.marketingKeywords("OFFICIAL").contains("立即购买"));
        assertTrue(policy.highValueInformationKeywords("PRICING").contains("计费"));
    }
}
```

- [x] **Step 3: 新建搜索引擎配置红灯测试，锁定 alias + enabled 链路**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchEnginePropertiesTest {

    @Test
    void shouldResolveAliasOnlyToEnabledEngine() {
        SearchEngineProperties properties = new SearchEngineProperties();
        properties.get("bing").setEnabled(false);
        properties.get("duckduckgo").setEnabled(true);

        assertEquals("duckduckgo", properties.normalizeEngineKey("ddg"));
        assertEquals("duckduckgo", properties.resolveAvailableEngineKey("ddg"));
        assertEquals(List.of("duckduckgo"), properties.resolveEnabledEngineKeys("ddg", List.of("bing")));
    }
}
```

- [x] **Step 4: 新建数据源家族配置红灯测试，锁定 `official / news / github` 首轮骨架**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceCatalogPropertiesTest {

    @Test
    void shouldExposeFirstIterationSourceFamiliesWithToolsAndUpdatePolicies() {
        SearchSourceCatalogProperties properties = new SearchSourceCatalogProperties();

        assertThat(properties.getFamilies()).containsKeys("official", "news", "github");
        assertThat(properties.getFamilies().get("official").getRole()).isEqualTo("PRIMARY_VERTICAL");
        assertThat(properties.getFamilies().get("official").getPrimaryTools())
                .contains("WEB_SCRAPER", "JINA_READER");
        assertThat(properties.getFamilies().get("official").getAuxiliaryTools())
                .contains("PUBLIC_SEARCH");
        assertThat(properties.getFamilies().get("news").getUpdatePolicy().getMode())
                .isEqualTo("REALTIME_RSS_AND_SCHEDULED_SWEEP");
        assertThat(properties.getFamilies().get("github").getPrimaryTools())
                .contains("GITHUB_API");
    }
}
```

- [x] **Step 5: 扩展 Golden Master 与模板测试，锁定本轮 four guards**

```java
@Test
void shouldAcceptChineseDocsNewsAndReviewSignals() {
    SearchKeywordPolicy policy = new SearchKeywordPolicy();

    assertTrue(policy.expectedKeywords("DOCS").contains("开发指南"));
    assertTrue(policy.expectedKeywords("NEWS").contains("公告"));
    assertTrue(policy.expectedKeywords("REVIEW").contains("对比"));
}
```

```java
@Test
void shouldKeepEnglishSearchTemplatesAfterLoadingYamlQueries() {
    String rendered = promptTemplateService.buildSearchQuery("search-docs-primary", Map.of(
            "competitorName", "Notion AI",
            "domainHint", "docs.notion.so"
    ));

    assertTrue(rendered.contains("Notion AI"));
    assertTrue(rendered.toLowerCase().contains("documentation")
            || rendered.contains("文档"));
}
```

- [x] **Step 6: 运行红灯基线测试，确认当前实现还未满足**

Run:
`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchKeywordPolicyTest,SearchEnginePropertiesTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchAndCollectionGoldenMasterTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest" test`

Expected:
- FAIL 在以下至少一类问题上：
  - `HEURISTIC` 仍留在正式 fallback 顺序里
  - `SearchKeywordPolicy` 词表覆盖与断言不一致
  - `search-official-domain` 仍是文档语义
  - `ddg -> duckduckgo -> enabled=true` 链路未被完整验证
  - `official / news / github` 数据源家族配置还没有正式配置骨架

- [ ] **Step 7: 提交红灯基线**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionTruthContractTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchKeywordPolicyTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchEnginePropertiesTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java
git commit -m "test(search): lock first iteration truth and quality guards"
```

### Task 2: 实施执行真相收口包

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchProviderRole.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- Modify: `backend/src/main/resources/prompts/search-queries.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`

本任务额外约束：

1. 本轮即使不完整重做 provider 路由，也必须避免新实现继续强化“所有 provider 平级串扫”的旧语义。
2. `SearchPolicyResolver`、配置绑定和测试命名需要为“私域垂直 API 主力、公网搜索辅助”预留正式表达，而不是只保留 engine alias 之类的技术细节。
3. `Source Family Catalog` 只先落配置骨架，不在本任务真实调用 News API / GitHub API。
4. 在当前 provider 集合下，`qianfan / serpapi / browser / http` 都属于公网搜索类 provider，默认应归入 `AUXILIARY_PUBLIC`；`PRIMARY_VERTICAL` 先由 source family 与 tool 语义承接，不在本轮硬编码到现有 provider 名单上。

- [x] **Step 1: 实现正式 fallback 顺序清理，移除 `HEURISTIC` 伪能力**

```java
public List<String> resolveFallbackOrder(String searchMode, boolean browserSearchEnabled) {
    String normalizedMode = searchMode == null ? "HYBRID" : searchMode.trim().toUpperCase(Locale.ROOT);
    List<String> baseOrder = switch (normalizedMode) {
        case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
        case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
        case "HEURISTIC_ONLY" -> List.of("PLANNED", "HTTP");
        default -> List.of("PLANNED", "BROWSER", "HTTP");
    };
    LinkedHashSet<String> resolvedOrder = new LinkedHashSet<>(baseOrder);
    if (!browserSearchEnabled) {
        resolvedOrder.remove("BROWSER");
    }
    return new ArrayList<>(resolvedOrder);
}
```

- [x] **Step 2: 在计划模板、预览补源和运行协调中统一消费 resolver**

```java
List<String> fallbackOrder = searchPolicyResolver.resolveFallbackOrder(searchMode, browserEnabled);
```

```java
CollectorNodeConfig previewConfig = buildPreviewConfig(competitorName, scope);
previewConfig.setSearchFallbackOrder(searchPolicyResolver.resolveFallbackOrder(
        previewConfig.getSearchMode(),
        Boolean.TRUE.equals(previewConfig.getBrowserSearchEnabled())
));
```

- [x] **Step 3: 在策略入口与测试口径中固化 provider 主辅方向，并落 `Source Family Catalog` 配置骨架**

```java
package cn.bugstack.competitoragent.search;

public enum SearchProviderRole {
    PRIMARY_VERTICAL,
    AUXILIARY_PUBLIC
}
```

```java
package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源家族目录。
 * 家族描述“采什么”，provider / API / scraper 描述“怎么采”，两者不能混在一起。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.source-catalog")
public class SearchSourceCatalogProperties {

    private Map<String, SourceFamilyProperties> families = defaultFamilies();

    public SourceFamilyProperties resolveFamily(String familyKey) {
        if (!StringUtils.hasText(familyKey)) {
            return null;
        }
        return families.get(familyKey.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, SourceFamilyProperties> defaultFamilies() {
        Map<String, SourceFamilyProperties> defaults = new LinkedHashMap<>();
        defaults.put("official", SourceFamilyProperties.builder()
                .role("PRIMARY_VERTICAL")
                .sourceTypes(List.of("OFFICIAL", "PRICING", "DOCS"))
                .contentScopes(List.of("PRODUCT_PAGE", "PRICING", "DOCUMENTATION"))
                .primaryTools(List.of("WEB_SCRAPER", "JINA_READER"))
                .auxiliaryTools(List.of("PUBLIC_SEARCH"))
                .updatePolicy(UpdatePolicy.builder()
                        .mode("DAILY_INCREMENTAL")
                        .interval("PT24H")
                        .build())
                .queryTemplates(List.of("search-official", "search-official-domain", "search-pricing-primary", "search-docs-primary"))
                .build());
        defaults.put("news", SourceFamilyProperties.builder()
                .role("PRIMARY_VERTICAL")
                .sourceTypes(List.of("NEWS"))
                .contentScopes(List.of("PRODUCT_RELEASE", "FUNDING", "PARTNERSHIP"))
                .primaryTools(List.of("NEWS_API", "RSS"))
                .auxiliaryTools(List.of("PUBLIC_SEARCH"))
                .updatePolicy(UpdatePolicy.builder()
                        .mode("REALTIME_RSS_AND_SCHEDULED_SWEEP")
                        .interval("PT1H")
                        .build())
                .queryTemplates(List.of("search-news-primary", "search-news-secondary"))
                .build());
        defaults.put("github", SourceFamilyProperties.builder()
                .role("PRIMARY_VERTICAL")
                .sourceTypes(List.of("GITHUB", "OPEN_SOURCE"))
                .contentScopes(List.of("REPOSITORY", "STAR_TREND", "RELEASE"))
                .primaryTools(List.of("GITHUB_API"))
                .auxiliaryTools(List.of("PUBLIC_SEARCH"))
                .updatePolicy(UpdatePolicy.builder()
                        .mode("DAILY_API_POLLING")
                        .interval("PT24H")
                        .build())
                .queryTemplates(List.of("search-github-repository", "search-github-release"))
                .build());
        return defaults;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceFamilyProperties {
        private Boolean enabled;
        private String role;
        private List<String> sourceTypes;
        private List<String> contentScopes;
        private List<String> primaryTools;
        private List<String> auxiliaryTools;
        private UpdatePolicy updatePolicy;
        private List<String> queryTemplates;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePolicy {
        private String mode;
        private String interval;
    }
}
```

```java
/**
 * 当前 provider 集合默认按工具层解释：
 * qianfan / serpapi / browser / http 都属于公网搜索类 provider，
 * 统一归为 AUXILIARY_PUBLIC。
 * PRIMARY_VERTICAL 先由 source family role 与 primaryTools 承接。
 */
public SearchProviderRole resolveProviderRole(String providerKey) {
    if (!StringUtils.hasText(providerKey)) {
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }
    return SearchProviderRole.AUXILIARY_PUBLIC;
}

public SearchProviderRole resolveSourceFamilyRole(String familyKey) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = searchSourceCatalogProperties.resolveFamily(familyKey);
    if (family == null || !StringUtils.hasText(family.getRole())) {
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }
    return SearchProviderRole.valueOf(family.getRole());
}
```

```java
@Test
void shouldKeepPrimaryVerticalAndAuxiliaryPublicRolesDistinct() {
    assertThat(searchPolicyResolver.resolveProviderRole("qianfan"))
            .isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
    assertThat(searchPolicyResolver.resolveProviderRole("serpapi"))
            .isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
    assertThat(searchPolicyResolver.resolveProviderRole("browser"))
            .isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
    assertThat(searchPolicyResolver.resolveProviderRole("http"))
            .isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
    assertThat(searchPolicyResolver.resolveSourceFamilyRole("official"))
            .isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
    assertThat(searchPolicyResolver.resolveSourceFamilyRole("news"))
            .isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
    assertThat(searchPolicyResolver.resolveSourceFamilyRole("github"))
            .isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
}
```

- [x] **Step 4: 修正 `search-queries.yml` 的官网域内模板语义**

```yaml
search-official: "{competitorName} 官方网站"
search-official-domain: "site:{domainHint} {competitorName} 官方网站"
search-docs-primary: "{competitorName} 文档 API 开发指南"
search-docs-secondary: "{competitorName} 帮助中心 开发文档"
search-pricing-primary: "{competitorName} 定价 价格 套餐"
search-pricing-secondary: "{competitorName} 计费 版本 收费"
search-news-primary: "{competitorName} 产品更新 发布日志"
search-news-secondary: "{competitorName} 公告 最新动态"
search-review-primary: "{competitorName} 评测 评价 对比"
search-review-secondary: "{competitorName} 怎么样 用户反馈"
search-review-zhihu: "site:zhihu.com {competitorName} 评测 对比"
# 以下 GitHub 模板只用于公网搜索辅助兜底，不替代 GITHUB_API 主采集。
search-github-repository: "site:github.com {competitorName} repository open source"
search-github-release: "site:github.com {competitorName} releases changelog"
```

- [x] **Step 5: 补齐搜索引擎 alias / enabled 测试链路**

```java
@Test
void shouldResolveAvailableEngineFromAliasWhenRequestedEngineEnabled() {
    SearchEngineProperties properties = new SearchEngineProperties();
    properties.get("bing").setEnabled(false);
    properties.get("duckduckgo").setEnabled(true);

    assertThat(properties.resolveAvailableEngineKey("ddg")).isEqualTo("duckduckgo");
    assertThat(properties.resolveEnabledEngineKeys("ddg", List.of("bing"))).containsExactly("duckduckgo");
}
```

- [x] **Step 6: 运行执行真相收口包测试**

Run:
`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest" test`

Expected:
- PASS

- [ ] **Step 7: 提交执行真相收口包**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchProviderRole.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchProperties.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java backend/src/main/resources/prompts/search-queries.yml backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPropertiesTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java
git commit -m "feat(search): unify formal execution truth and template routing"
```

### Task 3: 实施质量止血包

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchKeywordPolicyTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeServiceTest.java`

- [x] **Step 1: 扩展 `SearchKeywordPolicy` 覆盖 `DOCS / NEWS / REVIEW` 中文词表**

```java
private static final Map<String, List<String>> EXPECTED_KEYWORDS = Map.of(
        "DOCS", List.of(
                "docs", "documentation", "help", "guide", "api", "reference",
                "文档", "帮助中心", "指南", "手册", "开发指南", "参考手册"
        ),
        "NEWS", List.of(
                "blog", "news", "changelog", "update", "release", "announcement",
                "发布日志", "更新", "公告", "产品动态"
        ),
        "REVIEW", List.of(
                "review", "reviews", "rating", "customer", "compare", "g2", "capterra",
                "评测", "评价", "对比", "怎么样"
        ),
        "PRICING", List.of(
                "pricing", "price", "plan", "plans", "billing", "subscription", "enterprise",
                "定价", "价格", "计费", "计费方式", "收费", "套餐", "版本"
        ),
        "OFFICIAL", List.of(
                "official", "homepage", "overview", "features", "feature", "about",
                "官网", "首页", "产品介绍", "功能", "方案", "能力", "概览"
        )
);
```

- [x] **Step 2: 把营销页 veto 规则从“调用点”升级成正式实现**

```java
private boolean isMarketingLandingPage(SourceCollector.CollectedPage page, String sourceType) {
    if (!isUsableCollectedPage(page)) {
        return false;
    }
    String textualContent = (safe(page.getTitle()) + "\n" + safe(page.getSnippet()) + "\n" + safe(page.getContent()))
            .toLowerCase(Locale.ROOT);
    boolean containsMarketingSignal = searchKeywordPolicy.marketingKeywords(sourceType).stream()
            .anyMatch(textualContent::contains);
    if (!containsMarketingSignal) {
        return false;
    }
    boolean containsHighValueInformation = searchKeywordPolicy.highValueInformationKeywords(sourceType).stream()
            .anyMatch(textualContent::contains);
    return !containsHighValueInformation;
}
```

- [x] **Step 3: 让浏览器 query 兜底回归模板体系，避免“竞品名 + 内部枚举值”**

```java
private List<String> resolveQueries(CollectorNodeConfig config) {
    LinkedHashSet<String> queries = new LinkedHashSet<>();
    if (config.getSearchQueries() != null) {
        config.getSearchQueries().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(queries::add);
    }
    return queries.stream()
            .limit(Math.max(1, resolveMaxSearchesPerTask(config)))
            .toList();
}
```

Implementation note:
如果这里需要兜底 query，必须通过 `PromptTemplateService.buildSearchQueries(...)` 生成，而不是拼接 `competitorName + sourceType`。

- [x] **Step 4: 扩展 Golden Master 与单测**

```java
@Test
void shouldRejectOfficialMarketingPageEvenWhenDomainAuthorityIsHigh() {
    // 继续沿用现有红灯测试，要求营销页 veto 生效。
}

@Test
void shouldAcceptHighValueChinesePricingDocument() {
    // 继续沿用现有红灯测试，要求中文 pricing 命中。
}

@Test
void shouldAcceptChineseDocsSignals() {
    when(sourceCollector.collect("https://cloud.tencent.com/document/product/1000/2000", "腾讯云", "DOCS"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://cloud.tencent.com/document/product/1000/2000")
                    .title("对象存储开发指南")
                    .content("本页提供开发指南、API 参考与接入说明。")
                    .snippet("开发指南与 API 参考")
                    .competitorName("腾讯云")
                    .sourceType("DOCS")
                    .success(true)
                    .build());

    CandidateVerificationResult result = candidateVerifier.verify(
            "腾讯云",
            "DOCS",
            List.of(SourceCandidate.builder()
                    .url("https://cloud.tencent.com/document/product/1000/2000")
                    .title("对象存储开发指南")
                    .sourceType("DOCS")
                    .domain("cloud.tencent.com")
                    .build())
    );

    assertEquals(1, result.getVerifiedTargets().size());
}
```

- [x] **Step 5: 运行质量止血包测试**

Run:
`mvn -pl backend "-Dtest=SearchKeywordPolicyTest,CandidateVerifierTest,SearchAndCollectionGoldenMasterTest,BrowserSearchRuntimeServiceTest" test`

Expected:
- PASS

- [ ] **Step 6: 提交质量止血包**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java backend/src/main/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeService.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchKeywordPolicyTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java backend/src/test/java/cn/bugstack/competitoragent/search/BrowserSearchRuntimeServiceTest.java
git commit -m "feat(search): stop quality regressions in verification and query fallback"
```

### Task 4: 实施连续性事实源包

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`

- [x] **Step 1: 保持 `searchAudit` 最小正式 DTO 只承载正式事实源**

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSnapshot {

    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
    private List<String> sourceUrls;
}
```

Implementation note:
本轮不额外往这里塞 `attemptedTargets / discardedTargets`，避免提前跨入第二阶段恢复模型重构。

- [x] **Step 2: 在 Collector output、runtime event、replay projection 中统一透传正式对象**

```java
output.put("searchExecutionTrace", searchExecutionResult.getExecutionTrace());
output.put("searchProgress", progressSnapshots.isEmpty()
        ? searchExecutionResult.getProgressSnapshot()
        : progressSnapshots.get(progressSnapshots.size() - 1));
output.put("searchProgressSnapshots", progressSnapshots);
output.put("searchAudit", searchExecutionResult.getAuditSnapshot());
output.put("selectedTargets", buildSelectedTargetSummaries(targets));
output.put("sourceUrls", collectResult.getSourceUrls());
```

```java
payload.setSearchAudit(convertValue(output.get("searchAudit"), SearchAuditSnapshot.class));
payload.setSelectedTargets(convertList(
        output.get("selectedTargets"),
        new TypeReference<List<CollectorSelectedTargetSummary>>() {
        }));
payload.setSourceUrls(readStringList(output.get("sourceUrls")));
```

```java
searchReplays.add(SearchReplaySnapshotResponse.builder()
        .nodeName(taskNode.getNodeName())
        .planVersionId(taskNode.getPlanVersionId())
        .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
        .branchKey(taskNode.getBranchKey())
        .latestProgress(latestProgress)
        .searchAudit(searchAudit)
        .selectedTargets(selectedTargets)
        .sourceUrls(sourceUrls)
        .build());
```

- [x] **Step 3: 补齐 runtime / replay / integration smoke 测试**

```java
verify(taskEventPublisher).publishSearchProgressEvent(eq(24L), eq("collect_sources_docs"), argThat(payload ->
        payload.containsKey("searchAudit")
                && payload.containsKey("selectedTargets")
                && payload.containsKey("sourceUrls")));
```

```java
TaskReplayResponse response = service.getTaskReplay(42L);

assertTrue(objectMapper.valueToTree(response).has("searchReplays"));
assertEquals("collect_sources_docs", objectMapper.valueToTree(response).at("/searchReplays/0/nodeName").asText());
assertEquals("SELECT_TARGETS",
        objectMapper.valueToTree(response).at("/searchReplays/0/searchAudit/executionTrace/recoveryCheckpoint").asText());
```

```java
assertTrue(collectorOutput.hasNonNull("searchAudit"));
assertEquals("SELECT_TARGETS",
        collectorOutput.path("searchAudit").path("executionTrace").path("recoveryCheckpoint").asText());
assertTrue(searchEvent.getPayload().containsKey("searchAudit"));
assertTrue(searchEvent.getPayload().containsKey("selectedTargets"));
assertTrue(searchEvent.getPayload().containsKey("sourceUrls"));
```

- [x] **Step 4: 运行连续性事实源包测试**

Run:
`mvn -pl backend "-Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest,Phase2WorkflowIntegrationTest" test`

Expected:
- PASS

- [ ] **Step 5: 提交连续性事实源包**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java
git commit -m "feat(search): formalize audit projection across runtime and replay"
```

### Task 5: 首轮整体复核与专题文档回链

**Files:**

- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`

- [x] **Step 1: 跑首轮目标测试集合**

Run:
`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchKeywordPolicyTest,SearchEnginePropertiesTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest,CandidateVerifierTest,SearchAndCollectionGoldenMasterTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest,Phase2WorkflowIntegrationTest" test`

Expected:
- PASS

- [x] **Step 2: 跑 backend 模块完整测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [x] **Step 3: 回链更新方案与 specs 状态**

```md
- 实施：`✅` 首轮三类收口包已落地，相关单元测试、集成测试、架构测试 PASS。
- 实链验证：`✅` 2026-06-12 dev live 已完成搜索与采集段真实补源、正式采集、回放、rerun / resume 场景验收。
```

- [x] **Step 4: 提交首轮实施复核**

```bash
git add docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md
git commit -m "docs(search): mark first iteration implementation ready for verification"
```

---

## Verification

- 红灯基线：`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchKeywordPolicyTest,SearchEnginePropertiesTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchAndCollectionGoldenMasterTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest" test`
- 执行真相收口包：`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest" test`
- 质量止血包：`mvn -pl backend "-Dtest=SearchKeywordPolicyTest,CandidateVerifierTest,SearchAndCollectionGoldenMasterTest,BrowserSearchRuntimeServiceTest" test`
- 连续性事实源包：`mvn -pl backend "-Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest,Phase2WorkflowIntegrationTest" test`
- 首轮整体：`mvn -pl backend "-Dtest=SearchExecutionTruthContractTest,SearchKeywordPolicyTest,SearchEnginePropertiesTest,SearchSourceCatalogPropertiesTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchExecutionCoordinatorTest,CandidateVerifierTest,SearchAndCollectionGoldenMasterTest,PromptTemplateServiceTest,WorkflowFactoryTest,BrowserPreviewSearchSourceProviderTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest,Phase2WorkflowIntegrationTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `执行真相收口包` 已由 Task 2 覆盖。
2. `质量止血包` 已由 Task 3 覆盖。
3. `连续性事实源包` 已由 Task 4 覆盖。
4. `四个前稿 guard` 已在 Task 1 与 Task 2/3 中分别落测试与实现。
5. `Source Family Catalog` 首轮配置骨架已由 Task 1 与 Task 2 覆盖。
6. `specs` 回链状态更新已由 Task 5 覆盖。

### Placeholder scan

1. 本计划未使用 `TODO / TBD / implement later`。
2. 每个任务都给出明确文件、命令和预期。
3. 所有命令均为当前仓库已存在的 backend 测试入口。

### Type consistency

1. `searchAudit` 始终对应 `SearchAuditSnapshot`。
2. `searchReplays` 始终对应 `SearchReplaySnapshotResponse`。
3. `SEARCH_PROGRESS_V1` 事件始终对应 `SearchProgressEventPayload`。
4. `SearchKeywordPolicy`、`SearchPolicyResolver`、`SearchEngineProperties` 的名字与当前代码保持一致。
5. `SearchSourceCatalogProperties` 只表达数据源家族目录，不替代 `SearchProviderProperties` 的 provider 路由职责。
6. `SearchProviderRole` 当前先承接 `PRIMARY_VERTICAL / AUXILIARY_PUBLIC` 两类角色，其中现有 `qianfan / serpapi / browser / http` 默认归 `AUXILIARY_PUBLIC`。

---

## Execution Status

- 执行方式：Inline Execution，已按当前会话实际落地并完成代码与文档回写。
- 已完成步骤：Task 1 Step 1-6、Task 2 Step 1-6、Task 3 Step 1-5、Task 4 Step 1-4、Task 5 Step 1-3。
- 提交方式：用户要求跳过分包提交，改为最终一次性提交全部相关代码、测试与文档改动。
- 最近验证结果：首轮目标测试集 PASS（102 tests, 0 failures, 0 errors）；`mvn -pl backend test` PASS（417 tests, 0 failures, 0 errors, 0 skipped）。
- 实链验证结果：dev live 任务 `33` 已完成搜索与采集段真实补源、正式采集、回放、rerun / resume 验收；4 个 `COLLECTOR` 节点成功，累计 14 个 `sourceUrls`，回放接口返回 4 条 `searchReplays`，重跑后的采集节点保留 `searchAuditCheckpoint=SELECT_TARGETS`。
- 当前收口状态：搜索与采集段已完成；完整任务仍停在 `extract_schema` 人工介入点，日志显示下游 LLM provider token 无效，归入提取结构化链路后续处理。
