# Search And Collection Fourth Iteration Vertical Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 中 `Wave 6` 的“私域垂直 API 主力、公网搜索辅助”落成真实运行链路，首个垂直 provider 选择 GitHub API。

**Architecture:** 本轮只承接 `Wave 6`，默认前三轮已经完成或会先完成。实现路径固定为 `红灯契约 -> provider 元数据与配置绑定 -> GitHub API provider -> 主辅路由 -> 审计回放 -> 文档回链`，确保 `github` 作为真实 `PRIMARY_VERTICAL` provider 优先运行，`qianfan / serpapi / browserPreview / http` 继续只作为 `AUXILIARY_PUBLIC` 查漏补缺。

**Tech Stack:** Java 17, Spring Boot, Jackson, Java HttpClient, JUnit 5, Mockito, com.sun.net.httpserver.HttpServer

---

## Scope Guard

### 本轮必须完成

1. 实现第一个真实 `PRIMARY_VERTICAL` provider：`GithubApiSearchSourceProvider`。
2. `github` provider 必须有稳定 descriptor、独立 `github-api` 配置、可用性判断、凭证缺失告警、超时、Max Retries、异常捕获和可复核的降级行为。
3. `Source Family Catalog.github.primaryTools=GITHUB_API` 必须能绑定到 provider key `github`，不能只停留在配置声明。
4. `SearchPolicyResolver.resolveProviderRole(...)` 必须对 `github` / `github-api` 返回 `PRIMARY_VERTICAL`，对 `qianfan / serpapi / browser / browserPreview / http` 返回 `AUXILIARY_PUBLIC`。
5. `RoutingSearchSourceProvider` 必须按主辅关系执行：先跑匹配 source family 的垂直主力 provider；当主力不可用、候选不足、失败且允许 fail-open 时，才进入公网辅助 provider。
6. `searchAudit` / replay / insight 必须能展示 provider role、source family、provider key、query/template、跳过原因、降级原因和候选来源。
7. GitHub API 返回的每个候选必须显式保留 `sourceUrls`，并带上 `providerKey=github`、`providerRole=PRIMARY_VERTICAL`、`sourceFamilyKey=github`。

### 本轮明确不做

1. 不一次实现 News API / RSS、Twitter / Reddit、Crunchbase、Patent API。
2. 不做 provider 成本平台、配额仪表盘、反爬产品化面板。
3. 不重写 `DagExecutor`、`RecoveryEngine` 或跨重启 replay 持久化底座。
4. 不把 `qianfan / serpapi / browserPreview / http` 硬改成垂直 provider。
5. 不要求生产环境必须提供 GitHub token；无 token 时要可禁用、可告警、可通过 Mock HTTP 契约验证。

---

## Progress Snapshot

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase H1 | 锁定垂直 provider 与主辅路由红灯契约 | 0.5 天 | 父计划 Wave 6 已冻结 | 待执行 |
| Phase H2 | 补齐 provider 元数据、配置绑定和工具映射 | 1 天 | Phase H1 红灯测试存在 | 待执行 |
| Phase H3 | 实现 GitHub API provider 与 Mock HTTP 契约 | 1-2 天 | Phase H2 配置对象可绑定 | 待执行 |
| Phase H4 | 改造路由为 PRIMARY -> AUXILIARY 闭环 | 1-2 天 | Phase H3 provider 可返回候选 | 待执行 |
| Phase H5 | 贯通审计、回放、洞察字段 | 1 天 | Phase H4 路由 trace 可获取 | 待执行 |
| Phase H6 | 自动化复核、dev smoke 与文档回链 | 0.5-1 天 | Phase H1-H5 完成 | 待执行 |

---

## File Structure

**Backend - Create**

- `backend/src/main/java/cn/bugstack/competitoragent/search/GithubApiProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderRouteTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderResult.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderAuditProjectionTest.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/resources/application.yml`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java`

**Docs - Modify**

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- `docs/superpowers/task/2026-06-12-search-and-collection-fourth-iteration-vertical-provider-implementation-plan.md`

---

### Task 1: 锁定 Wave 6 红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProviderTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`

- [ ] **Step 1: 新建 GitHub API provider 红灯测试**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.GithubApiProperties;
import cn.bugstack.competitoragent.search.SearchProviderRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GithubApiSearchSourceProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldExposePrimaryVerticalDescriptorAndUnavailableWhenTokenMissing() {
        GithubApiSearchSourceProvider provider = new GithubApiSearchSourceProvider(
                githubProperties(false, ""),
                new ObjectMapper()
        );

        SearchSourceProviderDescriptor descriptor = provider.descriptor();

        assertThat(descriptor.getProviderKey()).isEqualTo("github");
        assertThat(descriptor.getProviderRole()).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
        assertThat(descriptor.getSourceFamilyKey()).isEqualTo("github");
        assertThat(descriptor.getPrimaryToolKey()).isEqualTo("GITHUB_API");
        assertThat(descriptor.getSupportedScopes()).contains("GITHUB", "OPEN_SOURCE");
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void shouldParseRepositoryResponseWithTraceableSourceUrls() throws IOException {
        startServer(200, """
                {
                  "items": [
                    {
                      "full_name": "acme/rocket",
                      "html_url": "https://github.com/acme/rocket",
                      "description": "AI agent repository",
                      "stargazers_count": 1280,
                      "updated_at": "2026-06-10T12:00:00Z",
                      "pushed_at": "2026-06-10T10:00:00Z",
                      "fork": false,
                      "archived": false,
                      "owner": {"login": "acme"}
                    }
                  ]
                }
                """);

        GithubApiSearchSourceProvider provider = new GithubApiSearchSourceProvider(
                githubProperties(true, "test-token"),
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search("Acme AI", List.of("GITHUB"));

        assertThat(candidates).hasSize(1);
        SourceCandidate candidate = candidates.get(0);
        assertThat(candidate.getUrl()).isEqualTo("https://github.com/acme/rocket");
        assertThat(candidate.getSourceType()).isEqualTo("GITHUB");
        assertThat(candidate.getDiscoveryMethod()).isEqualTo("GITHUB_API");
        assertThat(candidate.getProviderKey()).isEqualTo("github");
        assertThat(candidate.getProviderRole()).isEqualTo("PRIMARY_VERTICAL");
        assertThat(candidate.getSourceFamilyKey()).isEqualTo("github");
        assertThat(candidate.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
        assertThat(candidate.getReason()).contains("GitHub API");
    }

    @Test
    void shouldRetryServerErrorsAndReturnEmptyAfterExhaustion() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search/repositories", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        GithubApiProperties properties = githubProperties(true, "test-token");
        properties.setMaxRetries(1);

        GithubApiSearchSourceProvider provider = new GithubApiSearchSourceProvider(properties, new ObjectMapper());

        assertThat(provider.search("Acme AI", List.of("GITHUB"))).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void shouldNotRetryAuthorizationOrRateLimitFailures() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search/repositories", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"message\":\"rate limit\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        GithubApiProperties properties = githubProperties(true, "test-token");
        properties.setMaxRetries(2);

        GithubApiSearchSourceProvider provider = new GithubApiSearchSourceProvider(properties, new ObjectMapper());

        assertThat(provider.search("Acme AI", List.of("GITHUB"))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    private void startServer(int statusCode, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search/repositories", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private GithubApiProperties githubProperties(boolean enabled, String token) {
        GithubApiProperties properties = new GithubApiProperties();
        properties.setEnabled(enabled);
        properties.setApiToken(token);
        properties.setEndpoint(server == null
                ? "http://localhost:1/search/repositories"
                : "http://localhost:" + server.getAddress().getPort() + "/search/repositories");
        properties.setResultsPerScope(3);
        properties.setTimeoutSeconds(5);
        properties.setMaxRetries(0);
        return properties;
    }
}
```

- [ ] **Step 2: 新建 provider role 与工具绑定契约测试**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderRoleContractTest {

    private final SearchPolicyResolver resolver = new SearchPolicyResolver();

    @Test
    void shouldResolveGithubProviderAsPrimaryVerticalAndPublicSearchAsAuxiliary() {
        assertThat(resolver.resolveProviderRole("github")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
        assertThat(resolver.resolveProviderRole("github-api")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
        assertThat(resolver.resolveProviderRole("qianfan")).isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
        assertThat(resolver.resolveProviderRole("serpapi")).isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
        assertThat(resolver.resolveProviderRole("browserPreview")).isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
        assertThat(resolver.resolveProviderRole("http")).isEqualTo(SearchProviderRole.AUXILIARY_PUBLIC);
    }

    @Test
    void shouldBindGithubFamilyPrimaryToolToGithubProvider() {
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();

        assertThat(catalog.resolveFamily("github").getPrimaryTools()).contains("GITHUB_API");
        assertThat(catalog.resolveFamily("github").getToolProviderKeys())
                .containsEntry("GITHUB_API", "github");
        assertThat(resolver.resolveProviderKeysForSourceFamily("github", SearchProviderRole.PRIMARY_VERTICAL))
                .containsExactly("github");
    }
}
```

- [ ] **Step 3: 新建主辅路由红灯测试**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProviderRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingSearchSourceProviderPrimaryAuxiliaryTest {

    @Test
    void shouldRunPrimaryVerticalBeforeAuxiliaryEvenWhenConfiguredLater() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("qianfan", "github", "http"));
        properties.setPrimaryCandidateThreshold(1);
        properties.setRunAuxiliaryWhenPrimarySatisfied(false);
        List<String> invocations = new ArrayList<>();

        TestProvider github = provider("github", SearchProviderRole.PRIMARY_VERTICAL, true, List.of(
                SourceCandidate.builder().url("https://github.com/acme/rocket").sourceUrls(List.of("https://github.com/acme/rocket")).build()
        ), invocations);
        TestProvider qianfan = provider("qianfan", SearchProviderRole.AUXILIARY_PUBLIC, true, List.of(
                SourceCandidate.builder().url("https://docs.example.com").sourceUrls(List.of("https://docs.example.com")).build()
        ), invocations);
        TestProvider http = provider("http", SearchProviderRole.AUXILIARY_PUBLIC, true, List.of(), invocations);
        SourceCandidateRanker ranker = mock(SourceCandidateRanker.class);
        when(ranker.rankAndDeduplicate(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        RoutingSearchSourceProvider routing = new RoutingSearchSourceProvider(
                properties,
                List.of(qianfan, github, http),
                ranker,
                new SearchPolicyResolver()
        );

        SearchSourceProviderResult result = routing.searchWithAudit("Acme AI", List.of("GITHUB"));

        assertThat(result.getCandidates()).extracting(SourceCandidate::getUrl)
                .containsExactly("https://github.com/acme/rocket");
        assertThat(invocations).containsExactly("github");
        assertThat(result.getProviderRoutes()).extracting(SearchProviderRouteTrace::getProviderKey)
                .containsExactly("github", "qianfan", "http");
    }

    @Test
    void shouldUseAuxiliaryPublicProvidersWhenPrimaryIsUnavailable() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("github", "qianfan"));
        properties.setPrimaryCandidateThreshold(1);
        List<String> invocations = new ArrayList<>();

        TestProvider github = provider("github", SearchProviderRole.PRIMARY_VERTICAL, false, List.of(), invocations);
        TestProvider qianfan = provider("qianfan", SearchProviderRole.AUXILIARY_PUBLIC, true, List.of(
                SourceCandidate.builder().url("https://docs.example.com").sourceUrls(List.of("https://docs.example.com")).build()
        ), invocations);
        SourceCandidateRanker ranker = mock(SourceCandidateRanker.class);
        when(ranker.rankAndDeduplicate(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        RoutingSearchSourceProvider routing = new RoutingSearchSourceProvider(
                properties,
                List.of(github, qianfan),
                ranker,
                new SearchPolicyResolver()
        );

        SearchSourceProviderResult result = routing.searchWithAudit("Acme AI", List.of("GITHUB"));

        assertThat(result.getCandidates()).extracting(SourceCandidate::getUrl).containsExactly("https://docs.example.com");
        assertThat(invocations).containsExactly("qianfan");
        assertThat(result.getProviderRoutes()).anySatisfy(trace -> {
            assertThat(trace.getProviderKey()).isEqualTo("github");
            assertThat(trace.getStatus()).isEqualTo("SKIPPED_UNAVAILABLE");
            assertThat(trace.getSkippedReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        });
    }

    private TestProvider provider(String providerKey,
                                  SearchProviderRole role,
                                  boolean available,
                                  List<SourceCandidate> candidates,
                                  List<String> invocations) {
        return new TestProvider(providerKey, role, available, candidates, invocations);
    }

    private static final class TestProvider implements SearchSourceProvider {
        private final String providerKey;
        private final SearchProviderRole role;
        private final boolean available;
        private final List<SourceCandidate> candidates;
        private final List<String> invocations;

        private TestProvider(String providerKey,
                             SearchProviderRole role,
                             boolean available,
                             List<SourceCandidate> candidates,
                             List<String> invocations) {
            this.providerKey = providerKey;
            this.role = role;
            this.available = available;
            this.candidates = candidates;
            this.invocations = invocations;
        }

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey(providerKey)
                    .displayName(providerKey)
                    .providerRole(role)
                    .sourceFamilyKey(SearchProviderRole.PRIMARY_VERTICAL.equals(role) ? "github" : "public")
                    .primaryToolKey(SearchProviderRole.PRIMARY_VERTICAL.equals(role) ? "GITHUB_API" : "PUBLIC_SEARCH")
                    .supportedScopes(SearchProviderRole.PRIMARY_VERTICAL.equals(role)
                            ? List.of("GITHUB", "OPEN_SOURCE")
                            : List.of())
                    .defaultEnabled(true)
                    .defaultFailOpen(true)
                    .build();
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            invocations.add(providerKey);
            return candidates;
        }
    }
}
```

- [ ] **Step 4: 扩展配置绑定与审计兼容测试**

Replace the existing `SearchPropertiesBindingTest` provider-order values and add GitHub API binding values:

```java
"source-discovery.search.provider-order[0]=github",
"source-discovery.search.provider-order[1]=serpapi",
"source-discovery.search.provider-order[2]=qianfan",
"source-discovery.search.providers.github.enabled=true",
"source-discovery.search.providers.github.fail-open=true",
"source-discovery.search.primary-candidate-threshold=1",
"source-discovery.search.run-auxiliary-when-primary-satisfied=false",
"github-api.enabled=true",
"github-api.api-token=test-github-token",
"github-api.endpoint=https://api.github.com/search/repositories",
"github-api.results-per-scope=3",
"github-api.max-retries=1",
```

Add assertions:

```java
GithubApiProperties githubApiProperties = context.getBean(GithubApiProperties.class);

assertThat(searchProviderProperties.getProviderOrder()).containsExactly("github", "serpapi", "qianfan");
assertThat(searchProviderProperties.getProviders().get("github").getEnabled()).isTrue();
assertThat(searchProviderProperties.getPrimaryCandidateThreshold()).isEqualTo(1);
assertThat(searchProviderProperties.isRunAuxiliaryWhenPrimarySatisfied()).isFalse();
assertThat(githubApiProperties.getApiToken()).isEqualTo("test-github-token");
assertThat(githubApiProperties.getResultsPerScope()).isEqualTo(3);
assertThat(searchProperties.getSourceCatalog().getFamilies().get("github").getToolProviderKeys())
        .containsEntry("GITHUB_API", "github");
```

Add `GithubApiProperties.class` to the `@EnableConfigurationProperties` list in the same test configuration:

```java
GithubApiProperties.class
```

Add to `SearchAuditSnapshotCompatibilityTest`:

```java
@Test
void shouldSerializeProviderRouteTraceWithSourceUrls() throws Exception {
    SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
            .providerRoutes(List.of(SearchProviderRouteTrace.builder()
                    .providerKey("github")
                    .providerRole("PRIMARY_VERTICAL")
                    .sourceFamilyKey("github")
                    .primaryToolKey("GITHUB_API")
                    .status("SUCCESS")
                    .candidateCount(1)
                    .sourceUrls(List.of("https://github.com/acme/rocket"))
                    .build()))
            .sourceUrls(List.of("https://github.com/acme/rocket"))
            .build();

    String json = objectMapper.writeValueAsString(snapshot);
    SearchAuditSnapshot restored = objectMapper.readValue(json, SearchAuditSnapshot.class);

    assertThat(restored.getProviderRoutes()).hasSize(1);
    assertThat(restored.getProviderRoutes().get(0).getProviderRole()).isEqualTo("PRIMARY_VERTICAL");
    assertThat(restored.getProviderRoutes().get(0).getSourceUrls())
            .containsExactly("https://github.com/acme/rocket");
}
```

- [ ] **Step 5: 运行 Wave 6 红灯测试并确认失败**

Run:
`mvn -pl backend "-Dtest=GithubApiSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchProviderRoleContractTest,SearchPropertiesBindingTest,SearchAuditSnapshotCompatibilityTest" test`

Expected:
- FAIL
- `GithubApiSearchSourceProvider` / `GithubApiProperties` 不存在。
- `SearchSourceProviderDescriptor.providerRole` / `sourceFamilyKey` / `primaryToolKey` / `supportedScopes` 不存在。
- `SearchProviderProperties.primaryCandidateThreshold` 不存在。
- `SearchAuditSnapshot.providerRoutes` 不存在。
- `resolveProviderRole("github")` 仍返回 `AUXILIARY_PUBLIC`。

- [ ] **Step 6: 提交 Wave 6 红灯契约**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProviderTest.java backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java
git commit -m "test(search): lock vertical provider routing contracts"
```

---

### Task 2: 补齐 provider 元数据、配置绑定和工具映射

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/GithubApiProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [ ] **Step 1: 新建 GitHub API 配置对象**

```java
package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 垂直补源配置。
 * 该配置只承接 GitHub API 自身的 endpoint、凭证、超时和重试参数；
 * 是否参与路由仍由 source-discovery.search.providers.github 控制。
 */
@Data
@ConfigurationProperties(prefix = "github-api")
public class GithubApiProperties {

    private boolean enabled = false;
    private String apiToken;
    private boolean allowUnauthenticated = false;
    private String endpoint = "https://api.github.com/search/repositories";
    private String releasesEndpointTemplate = "https://api.github.com/repos/{owner}/{repo}/releases";
    private int resultsPerScope = 5;
    private int timeoutSeconds = 15;
    private int maxRetries = 2;
    private int maxReleaseRepos = 1;
}
```

- [ ] **Step 2: 扩展 provider descriptor 元数据**

Add fields and helper to `SearchSourceProviderDescriptor`:

```java
cn.bugstack.competitoragent.search.SearchProviderRole providerRole;
String sourceFamilyKey;
String primaryToolKey;

@Builder.Default
List<String> supportedScopes = List.of();

public cn.bugstack.competitoragent.search.SearchProviderRole getEffectiveProviderRole() {
    return providerRole == null
            ? cn.bugstack.competitoragent.search.SearchProviderRole.AUXILIARY_PUBLIC
            : providerRole;
}

/**
 * 判断 provider 是否适合本次 source scope。
 * supportedScopes 为空表示公网辅助 provider 可作为通用查漏渠道。
 */
public boolean supportsAnyScope(List<String> requestedScopes) {
    if (supportedScopes == null || supportedScopes.isEmpty()) {
        return true;
    }
    if (requestedScopes == null || requestedScopes.isEmpty()) {
        return true;
    }
    for (String scope : requestedScopes) {
        if (scope != null && supportedScopes.stream().anyMatch(value -> value.equalsIgnoreCase(scope.trim()))) {
            return true;
        }
    }
    return false;
}
```

Update existing provider descriptors:

```java
.providerRole(SearchProviderRole.AUXILIARY_PUBLIC)
.sourceFamilyKey("public")
.primaryToolKey("PUBLIC_SEARCH")
```

- [ ] **Step 3: 确认 SearchProviderProperties 已具备主辅路由开关**

If the third iteration has not already added these generic routing knobs, add them now:

```java
/**
 * 垂直主力 provider 至少返回多少候选才视为已满足本轮补源。
 * 达标后默认跳过公网辅助 provider，避免公网搜索继续覆盖主力链路。
 */
private int primaryCandidateThreshold = 1;

/**
 * true 表示即使垂直主力已达标也继续跑公网辅助；默认 false，保持主辅关系清晰。
 */
private boolean runAuxiliaryWhenPrimarySatisfied = false;
```

Update default order:

```java
private List<String> providerOrder = List.of("github", "qianfan", "serpapi", "browserPreview", "http");
```

- [ ] **Step 4: 给 SourceCandidate 补齐 provider 与 sourceUrls 字段**

Add fields:

```java
private String providerKey;
private String providerRole;
private String sourceFamilyKey;
private String sourceFamilyRole;
private List<String> sourceUrls;
```

- [ ] **Step 5: 给 GitHub Source Family 增加真实 provider 绑定**

The third iteration should already have added this generic field to `SourceFamilyProperties`:

```java
private Map<String, String> toolProviderKeys = new LinkedHashMap<>();
```

In this wave, update only the GitHub default family so `GITHUB_API` points to the real provider key:

```java
private SourceFamilyProperties createGithubFamily() {
    return new SourceFamilyProperties(
            true,
            SearchProviderRole.PRIMARY_VERTICAL.name(),
            List.of("GITHUB", "OPEN_SOURCE"),
            List.of("REPOSITORY", "STAR_TREND", "RELEASE"),
            List.of("GITHUB_API"),
            new LinkedHashMap<>(Map.of("GITHUB_API", "github")),
            List.of("PUBLIC_SEARCH"),
            new LinkedHashMap<>(),
            new UpdatePolicyProperties("DAILY_API_POLLING", "PT24H"),
            List.of("search-github-repository", "search-github-release")
    );
}
```

If the third iteration did not add this method yet, add it before wiring GitHub:

```java
public List<String> resolveProviderKeys(SearchProviderRole role) {
    if (role == SearchProviderRole.PRIMARY_VERTICAL) {
        return primaryTools.stream()
                .map(tool -> toolProviderKeys.getOrDefault(tool, ""))
                .filter(StringUtils::hasText)
                .toList();
    }
    return auxiliaryTools.stream()
            .map(tool -> toolProviderKeys.getOrDefault(tool, ""))
            .filter(StringUtils::hasText)
            .toList();
}
```

- [ ] **Step 6: 让 SearchPolicyResolver 从 Source Family Catalog 推导 provider role**

Replace `resolveProviderRole(...)` so it reads `toolProviderKeys` instead of hardcoding concrete provider names:

```java
public SearchProviderRole resolveProviderRole(String providerKey) {
    if (!StringUtils.hasText(providerKey)) {
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }
    return resolveProviderRoleFromSourceCatalog(providerKey);
}

private SearchProviderRole resolveProviderRoleFromSourceCatalog(String providerKey) {
    String normalizedProviderKey = providerKey.trim().toLowerCase(Locale.ROOT);
    for (SearchSourceCatalogProperties.SourceFamilyProperties family : resolveSourceCatalog().getFamilies().values()) {
        if (family == null) {
            continue;
        }
        if (family.resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL).stream()
                .anyMatch(key -> normalizedProviderKey.equals(key.trim().toLowerCase(Locale.ROOT)))) {
            return SearchProviderRole.PRIMARY_VERTICAL;
        }
        if (family.resolveProviderKeys(SearchProviderRole.AUXILIARY_PUBLIC).stream()
                .anyMatch(key -> normalizedProviderKey.equals(key.trim().toLowerCase(Locale.ROOT)))) {
            return SearchProviderRole.AUXILIARY_PUBLIC;
        }
    }
    return SearchProviderRole.AUXILIARY_PUBLIC;
}
```

Implementation note:
`RoutingSearchSourceProvider` still uses `SearchSourceProviderDescriptor.getEffectiveProviderRole()` as the execution-time source of truth. `SearchPolicyResolver.resolveProviderRole(...)` is the configuration-side resolver and must stay catalog-driven, so adding `NEWS_API -> news` later only changes source family configuration, not resolver code.

Ensure the helper from the third iteration exists:

```java
public List<String> resolveProviderKeysForSourceFamily(String familyKey, SearchProviderRole role) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
    if (family == null) {
        return List.of();
    }
    return family.resolveProviderKeys(role);
}
```

- [ ] **Step 7: 更新 application.yml 注册 github provider**

Update both default and test profile:

```yaml
source-discovery:
  search:
    provider-order:
      - github
      - qianfan
      - serpapi
      - browserPreview
      - http
    providers:
      github:
        enabled: ${GITHUB_API_ENABLED:false}
        fail-open: true
    primary-candidate-threshold: 1
    run-auxiliary-when-primary-satisfied: false

github-api:
  enabled: ${GITHUB_API_ENABLED:false}
  api-token: ${GITHUB_API_TOKEN:}
  allow-unauthenticated: ${GITHUB_API_ALLOW_UNAUTHENTICATED:false}
  endpoint: ${GITHUB_API_ENDPOINT:https://api.github.com/search/repositories}
  releases-endpoint-template: ${GITHUB_API_RELEASES_ENDPOINT_TEMPLATE:https://api.github.com/repos/{owner}/{repo}/releases}
  results-per-scope: ${GITHUB_API_RESULTS_PER_SCOPE:5}
  timeout-seconds: ${GITHUB_API_TIMEOUT_SECONDS:15}
  max-retries: ${GITHUB_API_MAX_RETRIES:2}
  max-release-repos: ${GITHUB_API_MAX_RELEASE_REPOS:1}
```

Update `search.source-catalog.families.github`:

```yaml
tool-provider-keys:
  GITHUB_API: github
```

- [ ] **Step 8: 运行配置与角色测试**

Run:
`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchSourceProviderDescriptorTest" test`

Expected:
- PASS

- [ ] **Step 9: 提交 provider 元数据与配置绑定**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/GithubApiProperties.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptor.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java backend/src/main/resources/application.yml backend/src/test/java/cn/bugstack/competitoragent/source/SearchSourceProviderDescriptorTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPolicyResolverTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderRoleContractTest.java
git commit -m "feat(search): bind github vertical provider metadata"
```

---

### Task 3: 实现 GitHub API provider

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProviderTest.java`

- [ ] **Step 1: 新建 GitHub API provider 骨架与可用性判断**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.GithubApiProperties;
import cn.bugstack.competitoragent.search.SearchProviderRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GitHub API 垂直补源 provider。
 * 该 provider 负责精准抓取 GitHub 仓库类干货，是 github 数据源家族的主力链路；
 * 公网搜索 provider 只能在它不可用、候选不足或降级时补漏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubApiSearchSourceProvider implements SearchSourceProvider {

    private static final Set<String> SUPPORTED_SCOPES = Set.of("GITHUB", "OPEN_SOURCE");

    private final GithubApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey("github")
                .displayName("GitHub API")
                .capabilities(List.of("PRIMARY_VERTICAL", "GITHUB_API", "REPOSITORY", "STAR_TREND", "RELEASE"))
                .providerRole(SearchProviderRole.PRIMARY_VERTICAL)
                .sourceFamilyKey("github")
                .primaryToolKey("GITHUB_API")
                .supportedScopes(List.of("GITHUB", "OPEN_SOURCE"))
                .defaultEnabled(false)
                .defaultFailOpen(true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        boolean credentialReady = StringUtils.hasText(properties.getApiToken()) || properties.isAllowUnauthenticated();
        return properties.isEnabled()
                && StringUtils.hasText(properties.getEndpoint())
                && credentialReady;
    }
}
```

- [ ] **Step 2: 实现 search 入口、scope 过滤和重试**

Add methods:

```java
@Override
public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
    if (!StringUtils.hasText(competitorName)) {
        return List.of();
    }
    if (!descriptor().supportsAnyScope(requestedScopes)) {
        return List.of();
    }
    if (!isAvailable()) {
        log.warn("github api provider unavailable, enabled={}, tokenPresent={}, allowUnauthenticated={}",
                properties.isEnabled(),
                StringUtils.hasText(properties.getApiToken()),
                properties.isAllowUnauthenticated());
        return List.of();
    }

    Set<String> scopes = resolveScopes(requestedScopes);
    List<SourceCandidate> candidates = new ArrayList<>();
    for (String scope : scopes) {
        candidates.addAll(searchScopeWithRetry(competitorName, scope));
    }
    return deduplicateCandidates(candidates);
}

private Set<String> resolveScopes(List<String> requestedScopes) {
    LinkedHashSet<String> scopes = new LinkedHashSet<>();
    if (requestedScopes == null || requestedScopes.isEmpty()) {
        scopes.add("GITHUB");
        return scopes;
    }
    for (String scope : requestedScopes) {
        if (scope != null && SUPPORTED_SCOPES.contains(scope.trim().toUpperCase(Locale.ROOT))) {
            scopes.add(scope.trim().toUpperCase(Locale.ROOT));
        }
    }
    if (scopes.isEmpty()) {
        scopes.add("GITHUB");
    }
    return scopes;
}

private List<SourceCandidate> searchScopeWithRetry(String competitorName, String scope) {
    int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return searchRepositoriesOnce(competitorName, scope);
        } catch (RuntimeException e) {
            lastError = e;
            log.warn("github api search failed, competitor={}, scope={}, attempt={}/{}",
                    competitorName, scope, attempt, maxAttempts);
            if (!isRetryable(e)) {
                break;
            }
        }
    }
    log.warn("github api search exhausted retries, competitor={}, scope={}, error={}",
            competitorName, scope, lastError == null ? "unknown" : lastError.getMessage());
    return List.of();
}
```

- [ ] **Step 3: 实现 HTTP 调用与异常捕获**

Add methods:

```java
private List<SourceCandidate> searchRepositoriesOnce(String competitorName, String scope) {
    String query = buildRepositoryQuery(competitorName);
    HttpRequest request = buildRequest(buildRepositoryUri(query));
    try {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GithubApiException("github api status=" + response.statusCode(), response.statusCode());
        }
        return parseRepositoryCandidates(response.body(), competitorName, scope, query);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("github api interrupted", e);
    } catch (GithubApiException e) {
        throw e;
    } catch (Exception e) {
        throw new IllegalStateException("github api request failed: " + e.getMessage(), e);
    }
}

private HttpRequest buildRequest(URI uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "competitor-agent");
    if (StringUtils.hasText(properties.getApiToken())) {
        builder.header("Authorization", "Bearer " + properties.getApiToken());
    }
    return builder.GET().build();
}

private URI buildRepositoryUri(String query) {
    String endpoint = properties.getEndpoint();
    String separator = endpoint.contains("?") ? "&" : "?";
    return URI.create(endpoint + separator
            + "q=" + encode(query)
            + "&sort=stars&order=desc"
            + "&per_page=" + Math.max(1, properties.getResultsPerScope()));
}
```

- [ ] **Step 4: 实现 GitHub 响应解析和 sourceUrls 回填**

Add methods:

```java
private List<SourceCandidate> parseRepositoryCandidates(String responseBody,
                                                        String competitorName,
                                                        String scope,
                                                        String query) {
    try {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        int limit = Math.max(1, properties.getResultsPerScope());
        List<SourceCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (JsonNode item : items) {
            if (rank >= limit) {
                break;
            }
            String url = text(item, "html_url");
            if (!StringUtils.hasText(url)) {
                continue;
            }
            rank++;
            int stars = item.path("stargazers_count").asInt(0);
            candidates.add(SourceCandidate.builder()
                    .url(url)
                    .title(defaultText(text(item, "full_name"), competitorName + " repository"))
                    .sourceType(scope)
                    .discoveryMethod("GITHUB_API")
                    .reason("GitHub API 命中仓库，stars=" + stars + "，描述=" + defaultText(text(item, "description"), ""))
                    .domain("github.com")
                    .publishedAt(defaultText(text(item, "pushed_at"), text(item, "updated_at")))
                    .relevanceScore(inferRelevance(rank, stars))
                    .freshnessScore(StringUtils.hasText(text(item, "pushed_at")) ? 0.82 : 0.62)
                    .qualityScore(inferQuality(item, stars))
                    .searchQuery(query)
                    .searchEngine("github-api")
                    .resultRank(rank)
                    .selectionStage("PLANNED")
                    .selectionReason("通过 GitHub API 垂直主力链路发现仓库")
                    .providerKey("github")
                    .providerRole(SearchProviderRole.PRIMARY_VERTICAL.name())
                    .sourceFamilyKey("github")
                    .sourceFamilyRole(SearchProviderRole.PRIMARY_VERTICAL.name())
                    .sourceUrls(List.of(url))
                    .build());
        }
        return candidates;
    } catch (Exception e) {
        log.warn("parse github api response failed, competitor={}, scope={}, error={}",
                competitorName, scope, e.getMessage());
        return List.of();
    }
}
```

- [ ] **Step 5: 补齐 helper 与非重试状态判断**

```java
private String buildRepositoryQuery(String competitorName) {
    return competitorName.trim() + " in:name,description fork:false archived:false";
}

private boolean isRetryable(RuntimeException exception) {
    if (exception instanceof GithubApiException githubApiException) {
        int statusCode = githubApiException.getStatusCode();
        return statusCode >= 500 || statusCode == 408 || statusCode == 429;
    }
    return true;
}

private double inferRelevance(int rank, int stars) {
    return Math.max(0.60D, Math.min(0.98D, 0.92D - (rank - 1) * 0.03D + Math.min(0.06D, stars / 10000.0D)));
}

private double inferQuality(JsonNode item, int stars) {
    boolean fork = item.path("fork").asBoolean(false);
    boolean archived = item.path("archived").asBoolean(false);
    double base = fork ? 0.72D : 0.88D;
    if (archived) {
        base -= 0.16D;
    }
    return Math.max(0.50D, Math.min(0.96D, base + Math.min(0.08D, stars / 10000.0D)));
}

private String text(JsonNode node, String fieldName) {
    if (node == null || !StringUtils.hasText(fieldName)) {
        return null;
    }
    JsonNode value = node.get(fieldName);
    return value == null || value.isNull() ? null : value.asText();
}

private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
}

private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
}

private List<SourceCandidate> deduplicateCandidates(List<SourceCandidate> candidates) {
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<SourceCandidate> filtered = new ArrayList<>();
    for (SourceCandidate candidate : candidates) {
        if (candidate != null && StringUtils.hasText(candidate.getUrl()) && seen.add(candidate.getUrl())) {
            filtered.add(candidate);
        }
    }
    return filtered;
}

private static final class GithubApiException extends RuntimeException {
    private final int statusCode;

    private GithubApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    private int getStatusCode() {
        return statusCode;
    }
}
```

- [ ] **Step 6: 运行 GitHub provider 测试**

Run:
`mvn -pl backend "-Dtest=GithubApiSearchSourceProviderTest" test`

Expected:
- PASS

- [ ] **Step 7: 提交 GitHub API provider**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProvider.java backend/src/test/java/cn/bugstack/competitoragent/source/GithubApiSearchSourceProviderTest.java
git commit -m "feat(search): add github api vertical provider"
```

---

### Task 4: 改造主辅路由闭环

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderRouteTrace.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java`

- [ ] **Step 1: 新建 provider 路由 trace DTO**

```java
package cn.bugstack.competitoragent.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Provider 路由审计条目。
 * 每个条目只描述一个 provider 在一次搜索补源中的执行事实，
 * 用于证明垂直主力与公网辅助的真实执行顺序和降级原因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchProviderRouteTrace {

    private String providerKey;
    private String providerRole;
    private String sourceFamilyKey;
    private String primaryToolKey;
    private List<String> requestedScopes;
    private List<String> queryTemplates;
    private String status;
    private String skippedReason;
    private String degradationReason;
    private Integer candidateCount;
    private List<String> sourceUrls;

    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
```

- [ ] **Step 2: 新建 provider 搜索结果对象**

```java
package cn.bugstack.competitoragent.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Provider 搜索结果。
 * 候选和 provider route trace 一起返回，避免通过 ThreadLocal 在异步执行边界丢失审计现场。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSourceProviderResult {

    @Builder.Default
    private List<SourceCandidate> candidates = List.of();

    @Builder.Default
    private List<SearchProviderRouteTrace> providerRoutes = List.of();

    public static SearchSourceProviderResult candidatesOnly(List<SourceCandidate> candidates) {
        return SearchSourceProviderResult.builder()
                .candidates(candidates == null ? List.of() : candidates)
                .providerRoutes(List.of())
                .build();
    }
}
```

- [ ] **Step 3: 给 SearchSourceProvider 增加带审计结果的默认方法**

```java
default SearchSourceProviderResult searchWithAudit(String competitorName, List<String> requestedScopes) {
    return SearchSourceProviderResult.candidatesOnly(search(competitorName, requestedScopes));
}
```

- [ ] **Step 4: 改造 RoutingSearchSourceProvider 构造器注入 provider 列表**

Update Spring constructor so adding a provider no longer changes this signature:

```java
@Autowired
public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                   List<SearchSourceProvider> delegateProviders,
                                   SourceCandidateRanker sourceCandidateRanker,
                                   SearchPolicyResolver searchPolicyResolver) {
    this(properties, delegateProviders, sourceCandidateRanker, searchPolicyResolver);
}

public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                   List<? extends SearchSourceProvider> delegateProviders,
                                   SourceCandidateRanker sourceCandidateRanker) {
    this(properties, delegateProviders, sourceCandidateRanker, new SearchPolicyResolver());
}

public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                   List<? extends SearchSourceProvider> delegateProviders,
                                   SourceCandidateRanker sourceCandidateRanker,
                                   SearchPolicyResolver searchPolicyResolver) {
    this.properties = properties;
    this.delegateProviders = delegateProviders.stream()
            .filter(provider -> provider != this)
            .filter(provider -> !"routing".equalsIgnoreCase(provider.descriptor().getProviderKey()))
            .toList();
    this.sourceCandidateRanker = sourceCandidateRanker;
    this.searchPolicyResolver = searchPolicyResolver;
}
```

- [ ] **Step 5: 实现 PRIMARY -> AUXILIARY 执行顺序**

Override `searchWithAudit(...)` and keep `search(...)` as a compatibility wrapper:

```java
@Override
public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
    return searchWithAudit(competitorName, requestedScopes).getCandidates();
}

@Override
public SearchSourceProviderResult searchWithAudit(String competitorName, List<String> requestedScopes) {
List<SourceCandidate> mergedCandidates = new ArrayList<>();
List<SearchSourceProvider> orderedProviders = resolveOrderedProviders(providersByKey, requestedScopes);
List<SearchProviderRouteTrace> routeTrace = new ArrayList<>();

int primaryCandidateCount = 0;
boolean primarySatisfied = false;
for (SearchSourceProvider provider : orderedProviders) {
    SearchSourceProviderDescriptor descriptor = provider.descriptor();
    SearchProviderRole role = descriptor.getEffectiveProviderRole();
    if (primarySatisfied && role == SearchProviderRole.AUXILIARY_PUBLIC
            && !properties.isRunAuxiliaryWhenPrimarySatisfied()) {
        routeTrace.add(buildTrace(descriptor, requestedScopes, "SKIPPED_PRIMARY_SATISFIED",
                "PRIMARY_CANDIDATE_THRESHOLD_REACHED", null, List.of()));
        continue;
    }
    ProviderRunOutcome outcome = runProvider(provider, descriptor, competitorName, requestedScopes);
    routeTrace.add(outcome.trace());
    if (!outcome.candidates().isEmpty()) {
        mergedCandidates.addAll(outcome.candidates());
        if (role == SearchProviderRole.PRIMARY_VERTICAL) {
            primaryCandidateCount += outcome.candidates().size();
            primarySatisfied = primaryCandidateCount >= Math.max(1, properties.getPrimaryCandidateThreshold());
        }
    }
}
return SearchSourceProviderResult.builder()
        .candidates(sourceCandidateRanker.rankAndDeduplicate(mergedCandidates))
        .providerRoutes(routeTrace)
        .build();
}
```

Implementation notes:

1. `resolveOrderedProviders(...)` must first keep enabled and scope-compatible `PRIMARY_VERTICAL` providers, then append scope-compatible `AUXILIARY_PUBLIC` providers.
2. `runProvider(...)` must preserve existing fail-open behavior; when `failOpen=false` it rethrows.
3. Every trace must include `sourceUrls` collected from returned candidates.

Add a private record for the provider execution result:

```java
private record ProviderRunOutcome(List<SourceCandidate> candidates, SearchProviderRouteTrace trace) {
}
```

- [ ] **Step 6: 给候选补齐 provider 元数据**

Add helper:

```java
private List<SourceCandidate> stampProviderMetadata(SearchSourceProviderDescriptor descriptor,
                                                    List<SourceCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
        return List.of();
    }
    return candidates.stream()
            .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
            .map(candidate -> candidate.toBuilder()
                    .providerKey(defaultText(candidate.getProviderKey(), descriptor.getProviderKey()))
                    .providerRole(defaultText(candidate.getProviderRole(), descriptor.getEffectiveProviderRole().name()))
                    .sourceFamilyKey(defaultText(candidate.getSourceFamilyKey(), descriptor.getSourceFamilyKey()))
                    .sourceFamilyRole(defaultText(candidate.getSourceFamilyRole(), descriptor.getEffectiveProviderRole().name()))
                    .sourceUrls(candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()
                            ? List.of(candidate.getUrl())
                            : candidate.getSourceUrls())
                    .build())
            .toList();
}
```

- [ ] **Step 7: 更新公网 provider descriptor 的 role**

In `QianfanSearchSourceProvider` / `SerpApiSearchSourceProvider` / `BrowserPreviewSearchSourceProvider` / `HttpSearchSourceProvider` descriptors:

```java
.providerRole(SearchProviderRole.AUXILIARY_PUBLIC)
.sourceFamilyKey("public")
.primaryToolKey("PUBLIC_SEARCH")
```

- [ ] **Step 8: 运行主辅路由测试**

Run:
`mvn -pl backend "-Dtest=RoutingSearchSourceProviderPrimaryAuxiliaryTest,RoutingSearchSourceProviderTest" test`

Expected:
- PASS

- [ ] **Step 9: 提交主辅路由闭环**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderRouteTrace.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProviderResult.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/QianfanSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/SerpApiSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/source/HttpSearchSourceProvider.java backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderPrimaryAuxiliaryTest.java backend/src/test/java/cn/bugstack/competitoragent/source/RoutingSearchSourceProviderTest.java
git commit -m "feat(search): route primary vertical before auxiliary search"
```

---

### Task 5: 贯通审计、回放和洞察字段

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderAuditProjectionTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java`

- [ ] **Step 1: 给 searchAudit 增加 providerRoutes**

Add to `SearchAuditSnapshot`:

```java
/**
 * Provider 路由审计。
 * 用于证明 PRIMARY_VERTICAL 与 AUXILIARY_PUBLIC 的执行顺序、跳过原因和降级原因。
 */
private List<cn.bugstack.competitoragent.source.SearchProviderRouteTrace> providerRoutes;
```

Add to `SearchExecutionTrace`:

```java
private String primaryProviderKey;
private String primaryProviderStatus;
private String auxiliaryProviderStatus;
```

- [ ] **Step 2: 在 SearchExecutionCoordinator 中读取带审计的 provider 结果**

Replace direct `searchSourceProvider.search(...)` calls in supplement path with `searchWithAudit(...)`:

```java
SearchSourceProviderResult routedResult = searchSourceProvider.searchWithAudit(
        config.getCompetitorName(),
        List.of(config.getSourceType())
);
List<SourceCandidate> routedCandidates = routedResult.getCandidates();
List<SearchProviderRouteTrace> providerRoutes = routedResult.getProviderRoutes();
```

If a non-routing provider is injected in a test, the default `SearchSourceProvider.searchWithAudit(...)` still returns candidates with an empty route list, so no ThreadLocal or same-thread assumption is needed.

Carry `providerRoutes` into `SearchExecutionResult` and `SearchAuditSnapshot.builder()`:

```java
.providerRoutes(providerRoutes)
```

- [ ] **Step 3: 生成主辅状态摘要**

Add helper:

```java
private void enrichProviderTrace(SearchExecutionTrace.SearchExecutionTraceBuilder builder,
                                 List<SearchProviderRouteTrace> providerRoutes) {
    if (providerRoutes == null || providerRoutes.isEmpty()) {
        return;
    }
    providerRoutes.stream()
            .filter(trace -> "PRIMARY_VERTICAL".equals(trace.getProviderRole()))
            .findFirst()
            .ifPresent(trace -> {
                builder.primaryProviderKey(trace.getProviderKey());
                builder.primaryProviderStatus(trace.getStatus());
            });
    boolean auxiliaryExecuted = providerRoutes.stream()
            .anyMatch(trace -> "AUXILIARY_PUBLIC".equals(trace.getProviderRole())
                    && "SUCCESS".equals(trace.getStatus()));
    builder.auxiliaryProviderStatus(auxiliaryExecuted ? "EXECUTED" : "SKIPPED_OR_NOT_NEEDED");
}
```

- [ ] **Step 4: 扩展 replay 与 insight DTO**

Add to `SearchReplaySnapshotResponse`:

```java
private List<SearchProviderRouteTrace> providerRoutes;
```

Add to `CollectorNodeInsightResponse`:

```java
@Schema(description = "Provider routing audit, showing primary vertical and auxiliary public search decisions")
private List<SearchProviderRouteTrace> providerRoutes;
```

Projection rule:

```java
responseBuilder.providerRoutes(searchAudit == null ? List.of() : searchAudit.getProviderRoutes());
```

- [ ] **Step 5: 新建审计投影测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SearchProviderRouteTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderAuditProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldKeepProviderRouteTraceInSearchAuditJson() throws Exception {
        SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
                .providerRoutes(List.of(SearchProviderRouteTrace.builder()
                        .providerKey("github")
                        .providerRole("PRIMARY_VERTICAL")
                        .sourceFamilyKey("github")
                        .primaryToolKey("GITHUB_API")
                        .status("SUCCESS")
                        .candidateCount(1)
                        .sourceUrls(List.of("https://github.com/acme/rocket"))
                        .build()))
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build();

        String json = objectMapper.writeValueAsString(snapshot);

        assertThat(json).contains("\"providerKey\":\"github\"");
        assertThat(json).contains("\"providerRole\":\"PRIMARY_VERTICAL\"");
        assertThat(json).contains("https://github.com/acme/rocket");
    }
}
```

- [ ] **Step 6: 运行审计投影测试**

Run:
`mvn -pl backend "-Dtest=SearchProviderAuditProjectionTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,TaskNodeViewAssemblerTest" test`

Expected:
- PASS

- [ ] **Step 7: 提交审计回放闭环**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchProviderAuditProjectionTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java
git commit -m "feat(search): expose provider routing audit"
```

---

### Task 6: 第四轮复核、dev smoke 与文档回链

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/task/2026-06-12-search-and-collection-fourth-iteration-vertical-provider-implementation-plan.md`

- [ ] **Step 1: 运行第四轮聚合测试**

Run:
`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest,SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,GithubApiSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchProviderRoleContractTest,SearchPropertiesBindingTest,SearchProviderAuditProjectionTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,TaskNodeViewAssemblerTest" test`

Expected:
- PASS

- [ ] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [ ] **Step 3: 执行 GitHub provider dev smoke**

Manual smoke with Mock endpoint or real token:

1. Set `GITHUB_API_ENABLED=true`.
2. For Mock smoke, set `GITHUB_API_ENDPOINT=http://localhost:<port>/search/repositories` and run a mock server returning the same JSON used in `GithubApiSearchSourceProviderTest`.
3. For real smoke, set `GITHUB_API_TOKEN=<token>` and keep endpoint as `https://api.github.com/search/repositories`.
4. Run `POST /api/task/preview` with a source family or source type that includes `GITHUB`.
5. Run `POST /api/task/create` and `POST /api/task/{id}/execute`.
6. Verify collector output contains `searchAudit.providerRoutes[*].providerKey=github` and `providerRole=PRIMARY_VERTICAL`.
7. Verify returned candidates or selected targets contain `sourceUrls` with `https://github.com/...`.
8. If GitHub provider is disabled or token is missing, verify `qianfan / serpapi / browserPreview / http` auxiliary path still runs and `providerRoutes` records the skip reason.

Expected:
- PRIMARY_VERTICAL provider runs before AUXILIARY_PUBLIC providers when enabled and scope-compatible.
- AUXILIARY_PUBLIC providers do not run when GitHub provider reaches `primaryCandidateThreshold` and `runAuxiliaryWhenPrimarySatisfied=false`.
- Missing token does not break the task; audit records unavailable or skipped status.

- [ ] **Step 4: 回写父计划 Wave 6 状态**

Update `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`:

```md
| Phase F | 完成 `Wave 6` 垂直 API provider 落地与主辅路由闭环 | 1 个迭代 | Phase E 已完成，且选定首个垂直 provider 的凭证或 Mock 契约 | 已完成 |
```

Add completion note:

```md
`Wave 6` 已以 GitHub API 作为首个真实 `PRIMARY_VERTICAL` provider 落地；`github` provider 已注册进路由，`resolveProviderRole` 能区分 `PRIMARY_VERTICAL / AUXILIARY_PUBLIC`，路由审计可展示 provider key、role、source family、跳过原因、降级原因和 sourceUrls。
```

- [ ] **Step 5: 回写 specs 状态**

Update `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`:

```md
- 实施：`✅` 搜索与采集执行引擎已完成首个真实垂直 provider 闭环，GitHub API 作为 `PRIMARY_VERTICAL` 主力链路，公网搜索 provider 作为候选不足、不可用或降级时的辅助链路。
- 验收：`✅` 已覆盖 GitHub API Mock HTTP 契约、凭证缺失、重试耗尽、主辅路由顺序、`resolveProviderRole` 差异、`sourceUrls` 保留和 provider route audit 投影。
```

- [ ] **Step 6: 标记本计划执行状态**

Append:

```md
## Execution Status

- 执行方式：待选择。
- 当前状态：计划已写入 `docs/superpowers/task`，尚未开始代码实现。
- 依赖前置条件：第一到第三轮计划先完成，或至少已具备 Source Family Catalog、provider descriptor、searchAudit 和 replay 基础字段。
```

- [ ] **Step 7: 提交第四轮复核文档**

```bash
git add docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/task/2026-06-12-search-and-collection-fourth-iteration-vertical-provider-implementation-plan.md
git commit -m "docs(search): plan vertical provider routing closure"
```

---

## Verification

- 红灯契约：`mvn -pl backend "-Dtest=GithubApiSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchProviderRoleContractTest,SearchPropertiesBindingTest,SearchAuditSnapshotCompatibilityTest" test`
- 配置与角色：`mvn -pl backend "-Dtest=SearchProviderRoleContractTest,SearchPolicyResolverTest,SearchPropertiesBindingTest,SearchSourceProviderDescriptorTest" test`
- GitHub provider：`mvn -pl backend "-Dtest=GithubApiSearchSourceProviderTest" test`
- 主辅路由：`mvn -pl backend "-Dtest=RoutingSearchSourceProviderPrimaryAuxiliaryTest,RoutingSearchSourceProviderTest" test`
- 审计投影：`mvn -pl backend "-Dtest=SearchProviderAuditProjectionTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,TaskNodeViewAssemblerTest" test`
- 第四轮整体：`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest,SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,GithubApiSearchSourceProviderTest,RoutingSearchSourceProviderPrimaryAuxiliaryTest,SearchProviderRoleContractTest,SearchPropertiesBindingTest,SearchProviderAuditProjectionTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,TaskNodeViewAssemblerTest" test`
- backend 全量硬性 gate：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. 首个真实 `PRIMARY_VERTICAL` provider 由 Task 3 覆盖。
2. `Source Family Catalog.primaryTools` 绑定真实 provider key 由 Task 2 覆盖。
3. `resolveProviderRole` 主辅区分由 Task 1、Task 2 覆盖。
4. `RoutingSearchSourceProvider` 主辅执行顺序、候选不足和不可用降级由 Task 4 覆盖。
5. Max Retries、异常捕获、凭证缺失和非重试状态由 Task 1、Task 3 覆盖。
6. `sourceUrls`、provider key、provider role、source family 审计字段由 Task 3、Task 5 覆盖。
7. 父计划与 specs 回链由 Task 6 覆盖。

### Placeholder Scan

1. 本计划每个任务都给出明确文件、代码片段、运行命令和预期结果。
2. 本计划没有把 News、社交、财务、专利 provider 混入本轮交付。
3. 本计划没有把公网搜索 provider 误标为垂直主力。

### Type Consistency

1. `github` 始终作为 provider key，`github-api` 始终作为配置前缀和别名。
2. `GITHUB_API` 始终作为 Source Family Catalog 的 primary tool key。
3. `SearchProviderRole.PRIMARY_VERTICAL` 只给真实垂直 provider 使用。
4. `SearchProviderRouteTrace` 始终作为 provider 路由审计条目。
5. `SearchSourceProviderResult` 始终作为候选与 provider route trace 的同步返回对象，不使用 ThreadLocal 保存审计现场。
6. `RoutingSearchSourceProvider` 通过 `List<SearchSourceProvider>` 收集 provider，并按 descriptor 建索引；新增 provider 不需要修改构造器签名。
7. `sourceUrls` 在 GitHub candidate、route trace、searchAudit、replay 和 insight 中均为显式字段。

---

## Execution Status

- 执行方式：待选择。
- 当前状态：计划已写入 `docs/superpowers/task`，尚未开始代码实现。
- 依赖前置条件：第一到第三轮计划先完成，或至少已具备 Source Family Catalog、provider descriptor、searchAudit 和 replay 基础字段。
