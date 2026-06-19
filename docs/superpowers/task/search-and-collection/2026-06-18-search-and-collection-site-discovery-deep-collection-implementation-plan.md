# Search And Collection Site Discovery Deep Collection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让搜索与采集链路从“给 URL 后采单页”升级为“只给竞品名也能发现官网/开放平台/文档入口，并对文档站做有限深度采集”。

**Architecture:** 本计划以现有总纲 `docs/superpowers/plans/2026-06-18-search-and-collection-site-discovery-deep-collection-master-plan.md` 为上位设计。执行上不拆成六轮大计划，而是拆成 4 轮实施：第 1 轮合并搜索回灌与子域模板，第 2 轮实现 LLM/规则域名发现与 readiness，第 3 轮实现 sitemap/robots 发现与 readiness，第 4 轮实现站内链接限深递归与端到端验收。

**Tech Stack:** Spring Boot、Java、JUnit 5、Mockito、AssertJ、Jackson、现有 `LlmClient/ModelGateway/OpenAiCompatibleClient`、现有 search/collection audit 契约。

---

## 0. 为什么不是六轮

总纲里有 6 个任务包，但不建议写成 6 轮执行计划。原因：

- 任务 1 和任务 2 都在候选扩展层，文件重叠度高，合并成一轮更省上下文切换。
- 任务 6 是验收任务，不应单独拖成一轮；它应该跟最大的行为改造任务 5 一起收口。
- LLM 域名发现和 sitemap 发现都需要接入 readiness，但外部依赖、配置和测试边界不同，适合分别执行。

推荐执行轮次：

1. **Round 1:** 搜索结果回灌 + 子域模板扩展。
2. **Round 2:** LLM/规则域名发现 + readiness。
3. **Round 3:** Sitemap/robots 发现 + readiness。
4. **Round 4:** 采集后内部链接发现、限深递归、端到端验收。

如果需要更保守，可以把 Round 4 拆成“内部链接递归”和“端到端验收”两轮，变成 5 轮；不建议默认拆成 6 轮。

## Round 1: 搜索结果回灌 + 子域模板扩展

**目标：** 搜索命中根域或入口页后，能继续生成 direct family 候选；显式 URL 也能扩展 `open.`、`docs.`、`developer.` 等子域候选。

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlanner.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SourceFamilyDirectDiscoveryPlannerTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`

### Task 1.1: 为 direct subdomain templates 写失败测试

- [ ] **Step 1: Add tests**

在 `SourceFamilyDirectDiscoveryPlannerTest` 增加覆盖：

```java
@Test
void shouldExpandOfficialSubdomainTemplatesFromRootUrl() {
    SearchPolicyResolver resolver = new SearchPolicyResolver();
    SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(resolver);

    List<SourceCandidate> candidates = planner.buildInitialCandidates(
            "哔哩哔哩",
            "OFFICIAL",
            List.of("https://bilibili.com")
    );

    assertThat(candidates)
            .extracting(SourceCandidate::getUrl)
            .contains("https://open.bilibili.com", "https://docs.bilibili.com");
    assertThat(candidates)
            .filteredOn(candidate -> "https://open.bilibili.com".equals(candidate.getUrl()))
            .first()
            .satisfies(candidate -> {
                assertThat(candidate.getDiscoveryMethod()).isEqualTo("FAMILY_SUBDOMAIN_TEMPLATE");
                assertThat(candidate.getSourceUrls()).contains("https://open.bilibili.com");
            });
}
```

- [ ] **Step 2: Run failing tests**

Run:

```bash
mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest" test
```

Expected: FAIL because subdomain templates do not exist yet.

### Task 1.2: Add subdomain template config and resolver API

- [ ] **Step 1: Add property field**

在 `SearchSourceCatalogProperties.SourceFamilyProperties` 增加：

```java
private List<String> directSubdomainTemplates = new ArrayList<>();
```

默认 official family 增加：

```java
official.setDirectSubdomainTemplates(List.of(
        "docs.{domain}",
        "developer.{domain}",
        "open.{domain}",
        "help.{domain}"
));
```

- [ ] **Step 2: Add resolver method**

在 `SearchPolicyResolver` 增加：

```java
/**
 * 统一解析 source family 的子域直达模板。
 * 子域模板只表达“候选入口”，不在这里做可达性验证，避免 resolver 承担网络职责。
 */
public List<String> resolveDirectSubdomainTemplates(String familyKey) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
    return family == null || family.getDirectSubdomainTemplates() == null
            ? List.of()
            : family.getDirectSubdomainTemplates().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
}
```

- [ ] **Step 3: Add YAML config**

在 `application.yml` 的 official family 下显式增加：

```yaml
direct-subdomain-templates:
  - docs.{domain}
  - developer.{domain}
  - open.{domain}
  - help.{domain}
```

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchPolicyResolverTest,SourceFamilyDirectDiscoveryPlannerTest" test
```

Expected: resolver tests pass or only planner test still fails until next step.

### Task 1.3: Implement subdomain expansion

- [ ] **Step 1: Add planner logic**

在 `SourceFamilyDirectDiscoveryPlanner.buildOfficialCandidates` 中，在 path template expansion 后增加：

```java
String rootDomain = extractRegistrableDomain(rootUrl);
for (String template : searchPolicyResolver.resolveDirectSubdomainTemplates("official")) {
    String expandedUrl = expandOfficialSubdomainTemplate(rootDomain, template);
    if (!StringUtils.hasText(expandedUrl) || expandedUrl.equals(normalizedUrl)) {
        continue;
    }
    putCandidate(candidates, buildCandidate(
            expandedUrl,
            resolveTemplateSourceType(expandedUrl),
            competitorName,
            "official",
            "FAMILY_SUBDOMAIN_TEMPLATE",
            "official family subdomain template candidate",
            List.of(expandedUrl)
    ));
}
```

新增 helper：

```java
/**
 * 从根 URL 中提取 host 作为子域模板的拼接基准。
 * 当前阶段不引入 public suffix 依赖，保持和工程现有 host 级 canonical 策略一致。
 */
private String extractRegistrableDomain(String rootUrl) {
    try {
        URI uri = URI.create(rootUrl);
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return null;
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    } catch (Exception exception) {
        return null;
    }
}

private String expandOfficialSubdomainTemplate(String rootDomain, String template) {
    if (!StringUtils.hasText(rootDomain) || !StringUtils.hasText(template)) {
        return null;
    }
    String host = template.trim().replace("{domain}", rootDomain);
    if (!StringUtils.hasText(host)) {
        return null;
    }
    return "https://" + host;
}
```

- [ ] **Step 2: Run planner tests**

Run:

```bash
mvn -pl backend "-Dtest=SourceFamilyDirectDiscoveryPlannerTest" test
```

Expected: PASS.

### Task 1.4: Add search-result root feedback

- [ ] **Step 1: Write failing coordinator test**

在 `SearchExecutionCoordinatorTest` 增加测试：HTTP 或 browser supplement 返回 `https://www.bilibili.com` 后，最终候选里出现 `SEARCH_ROOT_TEMPLATE` 或 family template 生成的候选。

核心断言：

```java
assertThat(result.getSourceCandidates())
        .extracting(SourceCandidate::getDiscoveryMethod)
        .contains("SEARCH_ROOT_TEMPLATE");
assertThat(result.getSourceCandidates())
        .extracting(SourceCandidate::getSourceUrls)
        .anySatisfy(urls -> assertThat(urls).contains("https://www.bilibili.com"));
```

- [ ] **Step 2: Implement feedback expansion**

在 `SearchExecutionCoordinator` 增加 helper：

```java
/**
 * 搜索补源命中 URL 后，反向提取根域并交给 direct discovery 做模板扩展。
 * 这一步补齐“搜索找到入口但没有继续生成 docs/pricing/help 候选”的断点。
 */
private List<SourceCandidate> expandSearchCandidatesThroughDirectDiscovery(CollectorNodeConfig config,
                                                                           List<SourceCandidate> searchCandidates) {
    if (searchCandidates == null || searchCandidates.isEmpty()) {
        return List.of();
    }
    LinkedHashSet<String> roots = new LinkedHashSet<>();
    for (SourceCandidate candidate : searchCandidates) {
        String root = toRootUrl(candidate == null ? null : candidate.getUrl());
        if (StringUtils.hasText(root)) {
            roots.add(root);
        }
    }
    if (roots.isEmpty()) {
        return List.of();
    }
    return directDiscoveryPlanner.buildInitialCandidates(
                    config.getCompetitorName(),
                    safeSourceType(config.getSourceType()),
                    new ArrayList<>(roots)
            ).stream()
            .map(candidate -> candidate.toBuilder()
                    .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                    .reason("search result root expanded through direct discovery templates")
                    .sourceUrls(resolveSearchExpansionSourceUrls(candidate, searchCandidates))
                    .build())
            .toList();
}
```

在 browser/http supplement 分支拿到 `browserCandidates` / `httpCandidates` 后，把 expanded candidates 合并进 `supplementedCandidates`，再走既有 rank/deduplicate。

- [ ] **Step 3: Run focused tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest" test
```

Expected: PASS.

## Round 2: LLM/规则域名发现 + readiness

**目标：** 只输入竞品名称时，先通过 LLM JSON 和规则验证生成官网、文档站、开放平台候选；同时在启动期 readiness 中明确 LLM 发现是否可用。

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/CompetitorDomainDiscoveryService.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/DomainVerificationClient.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/DomainDiscoveryProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/CompetitorDomainDiscoveryServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`

### Task 2.1: Add properties and readiness tests

- [ ] **Step 1: Create properties**

```java
package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 域名发现配置。
 * 这里集中控制 LLM 域名发现和 HTTP 验证预算，避免能力启用后因为配置缺失静默不可用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.discovery.domain")
public class DomainDiscoveryProperties {

    private boolean llmEnabled = false;
    private int llmTimeoutMillis = 8000;
    private int maxLlmCandidates = 8;
    private int verificationTimeoutMillis = 3000;
    private int maxRetries = 1;
}
```

- [ ] **Step 2: Add YAML**

```yaml
search:
  discovery:
    domain:
      llm-enabled: false
      llm-timeout-millis: 8000
      max-llm-candidates: 8
      verification-timeout-millis: 3000
      max-retries: 1
```

- [ ] **Step 3: Extend readiness summary**

在 `SearchCapabilityReadinessGuard.ReadinessSummary` 增加字段：

```java
boolean domainDiscoveryLlmEnabled;
boolean domainDiscoveryLlmAvailable;
String domainDiscoveryLlmUnavailableReason;
int domainDiscoveryVerificationTimeoutMillis;
int domainDiscoveryMaxRetries;
```

guard 构造器注入 `DomainDiscoveryProperties` 和 `ObjectProvider<LlmClient>`，构建 summary 时判断：

```java
.domainDiscoveryLlmEnabled(domainDiscoveryProperties != null && domainDiscoveryProperties.isLlmEnabled())
.domainDiscoveryLlmAvailable(llmClientProvider != null && llmClientProvider.getIfAvailable() != null)
.domainDiscoveryLlmUnavailableReason(resolveDomainDiscoveryLlmReason())
.domainDiscoveryVerificationTimeoutMillis(domainDiscoveryProperties == null ? 0 : domainDiscoveryProperties.getVerificationTimeoutMillis())
.domainDiscoveryMaxRetries(domainDiscoveryProperties == null ? 0 : domainDiscoveryProperties.getMaxRetries())
```

- [ ] **Step 4: Run readiness tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchCapabilityReadinessGuardTest" test
```

Expected: PASS after updating constructor usages in tests.

### Task 2.2: Implement LLM domain discovery service

- [ ] **Step 1: Write service tests**

覆盖：

- LLM 返回 `open.bilibili.com/doc/` 时生成 source candidates。
- LLM 抛异常时返回空列表。
- 缺少 `sourceUrls` 时补 `llm://domain-discovery/{competitorName}`。

- [ ] **Step 2: Implement service**

核心要求：

```java
private static final String SYSTEM_PROMPT =
        "你是一个竞品分析助手。给定公司名称，返回其官方网站、文档站、开放平台、GitHub 仓库的 URL。只返回 JSON，不要解释。";

private static final String USER_PROMPT_TEMPLATE = "公司: %s";
```

业务入口：

```java
public List<SourceCandidate> discover(String competitorName) {
    if (!properties.isLlmEnabled() || !StringUtils.hasText(competitorName) || llmClient == null) {
        return List.of();
    }
    try {
        String json = llmClient.chatForJson(
                SYSTEM_PROMPT,
                USER_PROMPT_TEMPLATE.formatted(competitorName.trim()),
                RESPONSE_SCHEMA
        );
        return parseCandidates(competitorName, json);
    } catch (RuntimeException exception) {
        return List.of();
    }
}
```

候选映射规则：

- `official` -> `OFFICIAL`
- `docs` / `open` -> `DOCS`
- `github` -> `GITHUB`
- `pricing` -> `PRICING`
- `news` -> `NEWS`
- 其他 -> `OFFICIAL`

- [ ] **Step 3: Run service tests**

Run:

```bash
mvn -pl backend "-Dtest=CompetitorDomainDiscoveryServiceTest" test
```

Expected: PASS.

### Task 2.3: Wire into planning discovery

- [ ] **Step 1: Inject service into `HeuristicSourceDiscoveryService`**

在没有 providedUrls 时，先调用 `CompetitorDomainDiscoveryService.discover(competitorName)`，将结果作为 direct/search 前的候选来源。

关键要求：

```java
List<SourceCandidate> domainDiscoveredCandidates = normalizedRoots.isEmpty()
        ? competitorDomainDiscoveryService.discover(competitorName)
        : List.of();
```

然后按 scope 合并：

```java
filterDomainDiscoveredCandidates(scope, domainDiscoveredCandidates)
```

- [ ] **Step 2: Run discovery tests**

Run:

```bash
mvn -pl backend "-Dtest=HeuristicSourceDiscoveryServiceTest,CompetitorDomainDiscoveryServiceTest" test
```

Expected: PASS.

## Round 3: Sitemap/robots 发现 + readiness

**目标：** 对已发现根域或子域读取 robots/sitemap，补充高价值文档入口，并把 sitemap 配置就绪度纳入启动期摘要。

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryService.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuard.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SitemapDiscoveryServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchCapabilityReadinessGuardTest.java`

### Task 3.1: Add sitemap properties and readiness

- [ ] **Step 1: Create properties**

```java
@Data
@Component
@ConfigurationProperties(prefix = "search.discovery.sitemap")
public class SitemapDiscoveryProperties {

    private boolean enabled = true;
    private int timeoutMillis = 3000;
    private int maxSitemapsPerDomain = 3;
    private int maxUrlsPerSitemap = 80;
    private int maxRetries = 1;
}
```

- [ ] **Step 2: Extend YAML**

```yaml
search:
  discovery:
    sitemap:
      enabled: true
      timeout-millis: 3000
      max-sitemaps-per-domain: 3
      max-urls-per-sitemap: 80
      max-retries: 1
```

- [ ] **Step 3: Extend readiness guard**

Add fields:

```java
boolean sitemapDiscoveryEnabled;
int sitemapTimeoutMillis;
int sitemapMaxSitemapsPerDomain;
int sitemapMaxUrlsPerSitemap;
String sitemapReadinessWarning;
```

Warn when timeout <= 0, max counts <= 0, retries < 0.

- [ ] **Step 4: Run readiness tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchCapabilityReadinessGuardTest" test
```

Expected: PASS.

### Task 3.2: Implement sitemap discovery

- [ ] **Step 1: Write tests**

Cover:

- `robots.txt` containing `Sitemap: https://open.bilibili.com/sitemap.xml`
- default `/sitemap.xml`
- filtering `/doc/`, `/docs/`, `/api`, `/sdk`, `/pricing`, `/help`
- request failure returns empty list

- [ ] **Step 2: Implement service**

Service contract:

```java
public List<SourceCandidate> discover(String competitorName, String sourceType, List<String> rootUrls)
```

Implementation requirements:

- Use `java.net.http.HttpClient`.
- Use configured timeout and max retries.
- Parse XML with JDK XML parser, not string-only regex.
- Build `SourceCandidate` with `discoveryMethod=SITEMAP_DISCOVERY`.
- `sourceUrls` must point to the sitemap or robots URL that yielded the candidate.

- [ ] **Step 3: Run sitemap tests**

Run:

```bash
mvn -pl backend "-Dtest=SitemapDiscoveryServiceTest" test
```

Expected: PASS.

### Task 3.3: Wire sitemap into search coordinator

- [ ] **Step 1: Inject `SitemapDiscoveryService` into `SearchExecutionCoordinator`**

After initial candidates and supplement candidates are known, derive roots and append sitemap candidates before final target selection.

- [ ] **Step 2: Run focused search tests**

Run:

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SitemapDiscoveryServiceTest" test
```

Expected: PASS.

## Round 4: 内部链接发现、限深递归、端到端验收

**目标：** 采集 `https://open.bilibili.com/doc/` 后，能发现页面内文档卡片链接并采集有限深度子页，所有结果进入 collection audit / replay / sourceUrls。

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryService.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/InternalLinkDiscoveryServiceTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/integration/SearchAndCollectionDeepDiscoveryIntegrationTest.java`
- Modify: `docs/future/2026-06-18-search-and-collection-discovery-gap-analysis.md`
- Create: `docs/progress/2026-06-18-search-and-collection-site-discovery-deep-collection-progress.md`

### Task 4.1: Extract internal links from content

- [ ] **Step 1: Create properties**

```java
@Data
@Component
@ConfigurationProperties(prefix = "collection.internal-link-discovery")
public class InternalLinkDiscoveryProperties {

    private boolean enabled = true;
    private int maxDepth = 2;
    private int maxLinksPerEntry = 10;
    private int maxLinksPerNode = 30;
}
```

- [ ] **Step 2: Implement `InternalLinkDiscoveryService`**

Contract:

```java
public List<SourceCandidate> discover(CollectionTaskPackage sourcePackage,
                                      CollectionExecutionResult result,
                                      int depth)
```

Rules:

- Extract Markdown links `[text](url)` and HTML links `href="url"`.
- Resolve relative URLs against `sourcePackage.getUrl()`.
- Keep same host or same root domain.
- Prefer paths containing `/doc`, `/docs`, `/api`, `/sdk`, `/reference`, `/guide`, `/help`.
- Deduplicate by canonical URL.
- Stamp `discoveryMethod=INTERNAL_LINK_DISCOVERY`.
- `sourceUrls` should include parent page URL and discovered child URL.

- [ ] **Step 3: Run tests**

Run:

```bash
mvn -pl backend "-Dtest=InternalLinkDiscoveryServiceTest" test
```

Expected: PASS.

### Task 4.2: Return discovered links from executor/coordinator

- [ ] **Step 1: Extend `CollectionExecutionResult`**

Add:

```java
List<SourceCandidate> discoveredCandidates;
Integer discoveryDepth;
```

Normalize nulls to empty list.

- [ ] **Step 2: Wire discovery in `WebPageCollectionExecutor`**

After `mapLightweightResult` and `collectByPlaywright`, attach internal link candidates when enabled.

- [ ] **Step 3: Add coordinator recursion**

In `CollectionExecutionCoordinator.execute(...)`, process entry targets first, then append discovered candidates while:

- depth <= maxDepth
- node appended count <= maxLinksPerNode
- per entry count <= maxLinksPerEntry
- canonical URL not already collected

- [ ] **Step 4: Run collection tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectionExecutionCoordinatorTest,WebPageCollectionExecutorRouteTest,InternalLinkDiscoveryServiceTest" test
```

Expected: PASS.

### Task 4.3: Preserve audit, replay, and collector output

- [ ] **Step 1: Ensure replay timeline includes recursive packages**

`CollectionExecutionCoordinator.buildReport(...)` should include all entry and discovered child results in `replayTimeline`.

- [ ] **Step 2: Update `CollectorAgent` progress messages**

During collection phase, messages should distinguish:

```text
正在抓取入口页面 1/3
正在抓取内部发现页面 4/12
```

- [ ] **Step 3: Run agent tests**

Run:

```bash
mvn -pl backend "-Dtest=CollectorAgentTest,CollectionAuditSerializationTest,CollectionAuditContractTest" test
```

Expected: PASS.

### Task 4.4: End-to-end verification and docs

- [ ] **Step 1: Add integration test**

Create `SearchAndCollectionDeepDiscoveryIntegrationTest` that mocks:

- competitor name: `哔哩哔哩`
- LLM returns `https://open.bilibili.com/doc/`
- entry page contains links for account auth, video management, Android SDK
- child pages return usable content

Assertions:

```java
assertThat(outputJson).contains("https://open.bilibili.com/doc/");
assertThat(outputJson).contains("账号授权");
assertThat(outputJson).contains("Android SDK");
assertThat(outputJson).contains("sourceUrls");
assertThat(outputJson).contains("replayTimeline");
```

- [ ] **Step 2: Update docs**

Add progress doc:

```markdown
# 搜索发现与站点深采集进度

- [x] 搜索结果回灌 direct discovery
- [x] 子域模板扩展
- [x] LLM/规则域名发现 readiness
- [x] Sitemap readiness
- [x] 内部链接限深递归
- [x] 端到端 mock 验收
```

- [ ] **Step 3: Run final regression**

Run:

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SourceFamilyDirectDiscoveryPlannerTest,CompetitorDomainDiscoveryServiceTest,SitemapDiscoveryServiceTest,InternalLinkDiscoveryServiceTest,CollectionExecutionCoordinatorTest,CollectorAgentTest,SearchCapabilityReadinessGuardTest,SearchAndCollectionDeepDiscoveryIntegrationTest" test
```

Expected: PASS.

## Final Verification

- [ ] **Step 1: Run backend focused test suite**

```bash
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,SourceFamilyDirectDiscoveryPlannerTest,SearchPolicyResolverTest,CompetitorDomainDiscoveryServiceTest,SitemapDiscoveryServiceTest,InternalLinkDiscoveryServiceTest,CollectionExecutionCoordinatorTest,CollectorAgentTest,SearchCapabilityReadinessGuardTest" test
```

- [ ] **Step 2: Run broader backend tests if time permits**

```bash
mvn -pl backend test
```

- [ ] **Step 3: Inspect git diff**

```bash
git diff --stat
git diff -- backend/src/main/java/cn/bugstack/competitoragent/search backend/src/main/java/cn/bugstack/competitoragent/collection backend/src/main/java/cn/bugstack/competitoragent/source backend/src/main/resources/application.yml
```

Expected:

- No unrelated refactors.
- All new discovery candidates include `sourceUrls`.
- Readiness guard summarizes LLM/domain/sitemap without hard failing auxiliary discovery.
- Collection recursion is bounded by depth and count.

## Execution Notes

- Keep commits per round, not per tiny step.
- Round 1 can be delivered independently and should already improve “搜到根域但不扩展”的问题。
- Round 2 can be delivered independently and should already improve“只给竞品名找入口”的问题。
- Round 3 is additive; sitemap failure must never block existing search/collection.
- Round 4 has the largest blast radius. Do not start it until Round 1-3 tests are green.
