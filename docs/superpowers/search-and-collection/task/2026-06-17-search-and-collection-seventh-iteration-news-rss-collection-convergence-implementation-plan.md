# Search And Collection Seventh Iteration News Web And RSS Collection Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 承接父方案 `Wave 10`，把“显式 RSS feed URL”正式接入统一采集执行体系，同时明确“普通新闻文章 URL 继续走现有网页采集链路”，避免把 `News API` 误用成“按 URL 找单篇正文”的伪专项 owner。

**Architecture:** 本轮采用保守收敛策略：`CollectionTaskPackageBuilder` 只把“明确是 feed 的 URL”路由到 `RSS`；普通 `NEWS` 候选即使来自公网搜索，也继续走 `JINA_READER / WEB_SCRAPER`。`RssFeedClient` 以字节流 + DOM 方式解析 `RSS 2.0`，对 HTML / 非 feed 内容做快速失败；`News API`、`Atom`、rate limit 与主动新闻发现能力明确后移到后续独立专题。

**Tech Stack:** Java 17, Spring Boot, Java HttpClient, Jackson, JUnit 5, Mockito, W3C DOM XML Parser

---

## Scope Guard

### 本轮必须完成

1. `CollectionTaskPackageBuilder` 只能把“显式 feed URL”路由到 `RSS`；普通新闻文章 URL 即使 `sourceType=NEWS` 也必须继续走网页采集路径。
2. 新建 `RssFeedProperties`、`RssFeedClient`、`RssFeedItem`、`RssFeedCollectionExecutor`，让 `RSS` 成为正式采集 owner。
3. `RssFeedClient` 本轮只支持 `RSS 2.0`，并对 `HTML / 非 XML / 非 feed XML` 做显式快速失败，不得把确定性内容错误吃满重试。
4. 所有 RSS 结果必须继续复用既有 `CollectionExecutionResult / collectionAudit / replay / checkpoint / sourceUrls` 契约。
5. `CollectorAgent` 必须兼容消费“结构化 payload 为主、正文较短”的 RSS 结果，不得因为正文短而丢弃可用证据。
6. `sourceUrls` 红线继续保持：feed 结果至少保留 `feedUrl + itemUrl`；同时正式记录 `qualitySignals / qualityScore / durationMillis`。
7. 测试必须覆盖：保守路由、中文 RSS fixture 解析、HTML 冒充 feed 的失败路径、`maxItemsPerFeed` 截断行为、RSS disabled 的快速失败审计。

### 本轮明确不做

1. 不实现 `NewsApiProperties / NewsApiClient / NewsApiCollectionExecutor`。
2. 不把 `News API` 用作“按 URL 精确查找单篇文章”的正文提取器。
3. 不实现 `News API` 的 rate limit / minIntervalMillis；这与后续主动新闻发现专题绑定，不能孤立塞进当前 Wave。
4. 不宣称支持 `Atom`；若后续需要，必须单开任务做根节点分支解析，而不是在 RSS 2.0 代码里口头兼容。
5. 不实现 `Wave 11` 的 subscription / cursor / 去重窗口 / 增量回放。
6. 不引入新的前端 `collection` 详情协议，也不改 SSE topic。

---

## Current Stage

当前阶段：第四轮 `GitHub API` 结构化采集、第五轮网页双路径采集、以及第六轮 `collectionAudit / replay / checkpoint` 闭环已经落地。第七轮原计划同时引入 `News API + RSS` 两个专项 owner，但经评审确认：`News API` 不适合承接“已知 URL 的单篇正文提取”，因此本轮应收敛为“RSS 正式接入 + 普通新闻 URL 保持网页采集”。

- [x] 前六轮现状复核：已完成
- [x] 第七轮原方案风险复核：已完成
- [x] 新方案范围收敛：已完成
- [x] 第七轮实施计划落稿：已完成
- [x] 第七轮实现与验证：已完成（Task 1-6 已完成；自动化回归、dev live smoke 与文档回链均已收口）

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase K1 | 锁定“显式 feed 才走 RSS”的红灯契约 | 0.5 天 | 第六轮审计闭环已完成 | 已完成 |
| Phase K2 | 收口 RSS 配置、news family 工具绑定与保守路由 | 0.5 天 | Phase K1 完成 | 已完成 |
| Phase K3 | 实现 `RssFeedClient / RssFeedItem` 与 RSS 2.0 解析 | 1 天 | Phase K2 完成 | 已完成 |
| Phase K4 | 落地 `RssFeedCollectionExecutor` 与 registry 接入 | 0.5-1 天 | Phase K3 完成 | 已完成 |
| Phase K5 | 打通 coordinator / collector / replay / disabled 快速失败审计 | 0.5-1 天 | Phase K4 完成 | 已完成 |
| Phase K6 | 聚合验证、文档回链与 dev live 验收 | 0.5 天 | Phase K1-K5 完成 | 已完成 |

---

## 四、发现的问题（本轮采纳结果）

1. `NewsApiClient` 的搜索语义原本建立在“第三方 News API 可以按 URL 精确定位单篇文章”这个错误前提上。主流供应商通常只支持按关键词 + 时间窗口搜索，不适合接手“已知 URL 的正文提取”。本轮采纳的改动是：**已知新闻 URL 一律继续走网页采集；`News API` 从当前 Wave 移出，后续若重启必须作为独立的主动新闻发现专题设计。**
2. 旧方案口头声称“兼容 RSS / Atom”，但解析代码只基于 `item` 节点，实际只支持 `RSS 2.0`。本轮采纳的改动是：**显式收窄到 RSS 2.0**，在 Scope Guard 和测试里明确写死，不再虚假承诺 Atom。
3. feed URL 启发式如果继续靠 `contains("/feed/")` 或 `.xml` 后缀，会把 `feedback`、`sitemap.xml` 等普通路径误路由到 RSS。本轮采纳的改动是：**改成路径段级匹配，且 `.xml` 只有在文件名本身就是 `feed.xml / rss.xml / atom.xml` 时才算显式 feed 信号。**
4. `sourceType=NEWS` 只能说明“这是新闻结果”，不能说明“这个 URL 适合走 News API”。本轮采纳的改动是：**`sourceType=NEWS` 只影响 `targetFields / renderHint`，不再触发 `NEWS_API` 路由；只有显式 feed URL 才触发 `RSS`。**
5. 原测试过度依赖 mock，无法验证真实 DOM 解析、中文内容和 XML/HTML 分叉。本轮采纳的改动是：**增加完整中文 RSS fixture、HTML 冒充 feed 的失败测试、`maxItemsPerFeed` 截断测试。**
6. `News API` rate limit 确实是后续必须处理的问题，但它和“主动新闻发现”语义绑定。本轮采纳的改动是：**不在当前 Wave 引入孤立的 `minIntervalMillis`，而是在后续 News API 专题统一设计节流、时间窗、provider schema 与集成测试。**

## 五、遗漏项评估（本轮修订）

| 检查项 | 评审结论 | 本计划修订 |
| --- | --- | --- |
| `sourceUrls` 红线 | 继续保留 | Task 3 / Task 4 / Task 5 全程锁定 `feedUrl + itemUrl` |
| `collectionAudit` 承接新结果 | 需要继续验证 | Task 5 增补 disabled 快速失败与 RSS 成功结果审计断言 |
| replay 可见 RSS 结果 | 需要继续验证 | Task 5 / Task 6 通过 `TaskReplayProjectionServiceTest` 锁定 |
| checkpoint 复用不破坏 | 需要继续验证 | Task 5 使用 `CollectionExecutionCoordinatorCheckpointReuseTest` 补充场景 |
| `CollectorAgent` 兼容结构化-only 证据 | 必须锁定 | Task 1 / Task 5 增补结构化 payload 断言 |
| `qualitySignals / qualityScore / durationMillis` 一致性 | 原方案未完全覆盖 | Task 4 显式要求正式记录 |
| 客户端 null safety | 仍需显式处理 | Task 3 要求空 body、缺字段、空节点都走明确分支 |
| `.xml` 实际返回 HTML 的容错 | 必须补齐 | Task 1 / Task 3 增补 fixture 与快速失败 |
| News API rate limit | 当前 Wave 不应硬塞 | 明确后移到独立 News API 专题 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedClient.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedItem.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/RssFeedCollectionExecutor.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/RssFeedClientTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/RssFeedCollectionExecutorTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistry.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorCheckpointReuseTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

### Docs - Modify

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

### Task 1: 锁定保守路由与 RSS 契约红灯测试

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/RssFeedClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/RssFeedCollectionExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorCheckpointReuseTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

- [x] **Step 1: 先锁定“显式 feed 才走 RSS”的任务包路由红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionTaskPackageBuilderTest {

    @Test
    void shouldBuildRssTaskPackageForExplicitFeedUrl() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://blog.example.com/feed.xml")
                .title("Acme feed")
                .sourceType("NEWS")
                .providerKey("http")
                .sourceUrls(List.of("https://blog.example.com/feed.xml"))
                .build();

        CollectionTaskPackage taskPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI", candidate, 1);

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("RSS");
        assertThat(taskPackage.getResourceLocator()).startsWith("rss://feed/");
    }

    @Test
    void shouldKeepNewsArticleUrlOnWebPathEvenWhenSourceTypeIsNews() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://news.example.com/releases/acme-launches-agent")
                .title("Acme launches agent")
                .sourceType("NEWS")
                .providerKey("serpapi")
                .sourceUrls(List.of("https://news.example.com/releases/acme-launches-agent"))
                .build();

        CollectionTaskPackage taskPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI", candidate, 2);

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(taskPackage.getResourceLocator()).isEqualTo("https://news.example.com/releases/acme-launches-agent");
    }

    @Test
    void shouldNotTreatFeedbackOrSitemapXmlAsFeed() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());

        CollectionTaskPackage feedbackPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI",
                SourceCandidate.builder()
                        .url("https://example.com/blog/feedback/2026/agent-launch")
                        .sourceType("NEWS")
                        .sourceUrls(List.of("https://example.com/blog/feedback/2026/agent-launch"))
                        .build(),
                3);

        CollectionTaskPackage sitemapPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI",
                SourceCandidate.builder()
                        .url("https://example.com/sitemap.xml")
                        .sourceType("NEWS")
                        .sourceUrls(List.of("https://example.com/sitemap.xml"))
                        .build(),
                4);

        assertThat(feedbackPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(sitemapPackage.getPrimaryTool()).isEqualTo("JINA_READER");
    }
}
```

- [x] **Step 2: 锁定 RSS 2.0 解析、中文 fixture、截断与 HTML 快速失败红灯测试**

```java
package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RssFeedClientTest {

    @Test
    void shouldParseChineseRssFixtureAndRespectMaxItems() {
        RssFeedProperties properties = new RssFeedProperties();
        properties.setMaxItemsPerFeed(1);
        RssFeedClient client = new RssFeedClient(properties, null);

        List<RssFeedItem> items = client.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Acme 中文动态</title>
                    <item>
                      <title>智能体平台发布</title>
                      <link>https://blog.example.com/agent-launch</link>
                      <pubDate>Tue, 17 Jun 2026 08:00:00 GMT</pubDate>
                      <description>Acme 发布企业级智能体平台，强调治理与自动化能力。</description>
                    </item>
                    <item>
                      <title>第二条更新</title>
                      <link>https://blog.example.com/second-update</link>
                      <pubDate>Tue, 17 Jun 2026 09:00:00 GMT</pubDate>
                      <description>这条记录用于验证 maxItemsPerFeed 截断。</description>
                    </item>
                  </channel>
                </rss>
                """.getBytes(StandardCharsets.UTF_8), "application/rss+xml; charset=UTF-8", "https://blog.example.com/feed.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTitle()).isEqualTo("智能体平台发布");
        assertThat(items.get(0).getSourceUrls())
                .containsExactly("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch");
    }

    @Test
    void shouldRejectHtmlPayloadEvenWhenUrlLooksLikeFeed() {
        RssFeedClient client = new RssFeedClient(new RssFeedProperties(), null);

        assertThatThrownBy(() -> client.parse("""
                <html><body>not a feed</body></html>
                """.getBytes(StandardCharsets.UTF_8), "text/html", "https://blog.example.com/feed.xml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not rss feed");
    }
}
```

- [x] **Step 3: 锁定 RSS 执行器、disabled 门禁与 coordinator 快速失败红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.RssFeedClient;
import cn.bugstack.competitoragent.source.RssFeedItem;
import cn.bugstack.competitoragent.source.RssFeedProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RssFeedCollectionExecutorTest {

    @Test
    void shouldReturnStructuredFeedEvidenceAndRealDuration() {
        RssFeedClient client = mock(RssFeedClient.class);
        when(client.fetch("https://blog.example.com/feed.xml")).thenReturn(List.of(
                RssFeedItem.builder()
                        .feedTitle("Acme Blog")
                        .title("Agent launch")
                        .link("https://blog.example.com/agent-launch")
                        .publishedAt("2026-06-17T08:00:00Z")
                        .summary("Acme 发布 Agent。")
                        .sourceUrls(List.of("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch"))
                        .build()
        ));

        RssFeedProperties properties = new RssFeedProperties();
        properties.setEnabled(true);
        RssFeedCollectionExecutor executor = new RssFeedCollectionExecutor(client, properties);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .packageKey("collect_sources_news#001")
                .targetIndex(1)
                .primaryTool("RSS")
                .url("https://blog.example.com/feed.xml")
                .resourceLocator("rss://feed/aGVsbG8")
                .sourceUrls(List.of("https://blog.example.com/feed.xml"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredPayload()).containsKey("items");
        assertThat(result.getSourceUrls()).contains("https://blog.example.com/feed.xml", "https://blog.example.com/agent-launch");
        assertThat(result.getDurationMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void shouldRefuseWhenRssToolDisabled() {
        RssFeedProperties properties = new RssFeedProperties();
        properties.setEnabled(false);
        RssFeedCollectionExecutor executor = new RssFeedCollectionExecutor(mock(RssFeedClient.class), properties);

        assertThat(executor.supports(CollectionTaskPackage.builder().primaryTool("RSS").build())).isFalse();
    }
}
```

Implementation note:
- `CollectionExecutionCoordinatorCheckpointReuseTest` 追加断言：当 `primaryTool=RSS` 且 `rss-feed.enabled=false` 时，结果必须快速失败并留下 `TOOL_UNAVAILABLE_FAST_FAIL` 审计标记，而不是进入无意义重试。
- `CollectorAgentTest` 追加断言：RSS 结果即使正文较短，只要存在 `structuredPayload.items` 和 `sourceUrls`，仍应进入证据链。

- [x] **Step 4: 运行首批红灯测试**

Run:
`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,RssFeedClientTest,RssFeedCollectionExecutorTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectorAgentTest" test`

Expected:
- FAIL
- `CollectionTaskPackageBuilder` 还不会把 feed URL 单独路由到 `RSS`
- `RssFeedProperties / RssFeedClient / RssFeedCollectionExecutor` 尚不存在
- coordinator 对 RSS disabled 的快速失败语义尚未落地

---

### Task 2: 收口 RSS 配置、news family 工具绑定与保守路由

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [x] **Step 1: 新建 `RssFeedProperties`**

```java
package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RSS 2.0 一次性采集配置。
 * 这里显式收口超时、重试、最大 item 数与快速失败策略，
 * 避免 executor 和 client 散落解析细节。
 */
@Data
@ConfigurationProperties(prefix = "rss-feed")
public class RssFeedProperties {

    private boolean enabled = true;
    private int timeoutSeconds = 12;
    private int maxRetries = 2;
    private int maxItemsPerFeed = 5;
    private boolean failFastOnNonFeedContent = true;
}
```

- [x] **Step 2: 在 `SearchSourceCatalogProperties` 中把 `news` 家族收敛为 `RSS + WEB`**

```java
private SourceFamilyProperties createNewsFamily() {
    SourceFamilyProperties family = new SourceFamilyProperties(
            true,
            SearchProviderRole.PRIMARY_VERTICAL.name(),
            List.of("NEWS"),
            List.of("PRODUCT_RELEASE", "FUNDING", "PARTNERSHIP"),
            List.of("RSS"),
            List.of("PUBLIC_SEARCH"),
            new UpdatePolicyProperties("REALTIME_RSS_AND_SCHEDULED_SWEEP", "PT1H"),
            List.of("search-news-primary", "search-news-secondary")
    );
    family.getToolProviderKeys().put("RSS", "rss");
    family.getToolProviderKeys().put("PUBLIC_SEARCH", "qianfan");
    family.setPreferredWebRenderHint("LIGHTWEIGHT");
    family.setExpectedBlockTypes(List.of("ARTICLE_BODY", "JSON_LD_METADATA"));
    return family;
}
```

- [x] **Step 3: 让 `CollectionTaskPackageBuilder` 只为显式 feed URL 选择 `RSS`**

```java
private String resolvePrimaryTool(String sourceFamilyKey,
                                  String sourceType,
                                  String providerKey,
                                  String url,
                                  WebPageRenderHint renderHint) {
    if ("github".equalsIgnoreCase(sourceFamilyKey)
            || "GITHUB".equalsIgnoreCase(sourceType)) {
        return "GITHUB_API";
    }
    if (looksLikeFeedUrl(url)) {
        return "RSS";
    }
    return renderHint == WebPageRenderHint.FULL_RENDER ? "WEB_SCRAPER" : "JINA_READER";
}
```

```java
private List<String> resolveTargetFields(String sourceFamilyKey, String sourceType, String url) {
    if (looksLikeFeedUrl(url)) {
        return List.of("feedTitle", "items", "publishedAt", "itemUrl");
    }
    if ("news".equalsIgnoreCase(sourceFamilyKey) || "NEWS".equalsIgnoreCase(sourceType)) {
        return List.of("headline", "summary", "publishedAt", "articleUrl", "sourceName");
    }
    if ("github".equalsIgnoreCase(sourceFamilyKey) || "GITHUB".equalsIgnoreCase(sourceType)) {
        return List.of("repository", "readme", "latestReleaseTag", "stars");
    }
    return List.of();
}
```

```java
private boolean looksLikeFeedUrl(String url) {
    if (!StringUtils.hasText(url)) {
        return false;
    }
    try {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> segments = Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .toList();
        if (segments.isEmpty()) {
            return false;
        }
        String last = segments.get(segments.size() - 1);
        return segments.stream().anyMatch(segment ->
                        segment.equals("feed") || segment.equals("rss") || segment.equals("atom"))
                || last.equals("feed.xml")
                || last.equals("rss.xml")
                || last.equals("atom.xml");
    } catch (Exception ignored) {
        return false;
    }
}
```

```java
private String resolveResourceLocator(String primaryTool, String url) {
    if (!StringUtils.hasText(url)) {
        return url;
    }
    if ("GITHUB_API".equalsIgnoreCase(primaryTool)) {
        return resolveGithubLocator(url);
    }
    if ("RSS".equalsIgnoreCase(primaryTool)) {
        return "rss://feed/" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }
    return url;
}
```

Implementation note:
- `sourceType=NEWS` 在本轮只用于 `targetFields / renderHint`，不再触发 `NEWS_API` 路由。
- `.xml` 后缀本身不构成 feed 证据；只有文件名本身就是 `feed.xml / rss.xml / atom.xml` 才允许进入 RSS。

- [x] **Step 4: 在 `application.yml` 中补齐 `rss-feed` 配置**

```yaml
rss-feed:
  enabled: true
  timeout-seconds: 12
  max-retries: 2
  max-items-per-feed: 5
  fail-fast-on-non-feed-content: true
```

- [x] **Step 5: 运行路由与绑定测试**

Run:
`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest" test`

Expected:
- PASS

---

### Task 3: 实现 `RssFeedClient` 与 `RssFeedItem`

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedClient.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/RssFeedItem.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/RssFeedClientTest.java`

- [x] **Step 1: 新建 `RssFeedItem` DTO**

```java
package cn.bugstack.competitoragent.source;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 单条 RSS item。
 * 显式保留 feedUrl + itemUrl，避免后续聚合时丢失回溯路径。
 */
@Value
@Builder
public class RssFeedItem {

    String feedTitle;
    String title;
    String link;
    String publishedAt;
    String summary;
    List<String> sourceUrls;
}
```

- [x] **Step 2: 实现字节流驱动的 `RssFeedClient`**

```java
package cn.bugstack.competitoragent.source;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class RssFeedClient {

    private final RssFeedProperties properties;
    private final HttpClient httpClient;

    public RssFeedClient(RssFeedProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
    }

    public List<RssFeedItem> fetch(String feedUrl) {
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(feedUrl))
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("rss feed status=" + response.statusCode());
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                return parse(response.body(), contentType, feedUrl);
            } catch (IllegalStateException deterministicFailure) {
                throw deterministicFailure;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("rss feed interrupted", interruptedException);
            } catch (Exception exception) {
                lastError = new IllegalStateException("rss feed request failed: " + exception.getMessage(), exception);
            }
        }
        throw lastError == null ? new IllegalStateException("rss feed request failed") : lastError;
    }

    List<RssFeedItem> parse(byte[] body, String contentType, String feedUrl) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("rss feed body is empty");
        }
        if (looksLikeHtml(contentType, body) && properties.isFailFastOnNonFeedContent()) {
            throw new IllegalStateException("rss feed response is not rss feed");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            Element root = document.getDocumentElement();
            String rootName = root == null ? "" : root.getTagName();
            if (!"rss".equalsIgnoreCase(rootName)) {
                if ("feed".equalsIgnoreCase(rootName)) {
                    throw new IllegalStateException("atom feed is not supported in wave 10");
                }
                throw new IllegalStateException("rss feed response is not rss feed");
            }

            String feedTitle = textOfFirst(document.getElementsByTagName("title"));
            NodeList items = document.getElementsByTagName("item");
            List<RssFeedItem> results = new ArrayList<>();
            for (int index = 0; index < items.getLength() && results.size() < Math.max(1, properties.getMaxItemsPerFeed()); index++) {
                Element item = (Element) items.item(index);
                String title = textOfFirst(item.getElementsByTagName("title"));
                String link = textOfFirst(item.getElementsByTagName("link"));
                String publishedAt = textOfFirst(item.getElementsByTagName("pubDate"));
                String summary = textOfFirst(item.getElementsByTagName("description"));

                LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
                if (StringUtils.hasText(feedUrl)) {
                    sourceUrls.add(feedUrl);
                }
                if (StringUtils.hasText(link)) {
                    sourceUrls.add(link);
                }

                results.add(RssFeedItem.builder()
                        .feedTitle(feedTitle)
                        .title(title)
                        .link(link)
                        .publishedAt(publishedAt)
                        .summary(summary)
                        .sourceUrls(new ArrayList<>(sourceUrls))
                        .build());
            }
            return results;
        } catch (IllegalStateException businessException) {
            throw businessException;
        } catch (Exception exception) {
            throw new IllegalStateException("rss feed parse failed: " + exception.getMessage(), exception);
        }
    }

    private boolean looksLikeHtml(String contentType, byte[] body) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("text/html")) {
            return true;
        }
        String prefix = new String(body, 0, Math.min(body.length, 128), java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return prefix.contains("<html");
    }

    private String textOfFirst(NodeList nodes) {
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
```

Implementation note:
- 这里故意使用 `BodyHandlers.ofByteArray()`，让 XML parser 按声明编码解析，避免把“中文 RSS fixture 看起来能过”误当成“远端编码真的稳健”。
- 本轮不做 live 第三方 feed 集成测试，但 fixture 至少要覆盖中文内容、截断和 HTML 伪装 feed。

- [x] **Step 3: 运行 RSS 客户端测试**

Run:
`mvn -pl backend "-Dtest=RssFeedClientTest" test`

Expected:
- PASS

---

### Task 4: 落地 `RssFeedCollectionExecutor`

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/RssFeedCollectionExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistry.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/RssFeedCollectionExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`

- [x] **Step 1: 实现 `RssFeedCollectionExecutor`**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.RssFeedClient;
import cn.bugstack.competitoragent.source.RssFeedItem;
import cn.bugstack.competitoragent.source.RssFeedProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class RssFeedCollectionExecutor extends ApiDataCollectionExecutor {

    private final RssFeedClient rssFeedClient;
    private final RssFeedProperties rssFeedProperties;

    public RssFeedCollectionExecutor(RssFeedClient rssFeedClient, RssFeedProperties rssFeedProperties) {
        this.rssFeedClient = rssFeedClient;
        this.rssFeedProperties = rssFeedProperties;
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null
                && rssFeedProperties != null
                && rssFeedProperties.isEnabled()
                && "RSS".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        long startedAt = System.nanoTime();
        try {
            List<RssFeedItem> items = rssFeedClient.fetch(taskPackage.getUrl());
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            List<Map<String, Object>> payloadItems = new ArrayList<>();
            StringBuilder content = new StringBuilder();

            for (RssFeedItem item : items) {
                if (item == null) {
                    continue;
                }
                if (item.getSourceUrls() != null) {
                    sourceUrls.addAll(item.getSourceUrls());
                }
                payloadItems.add(Map.of(
                        "title", item.getTitle(),
                        "link", item.getLink(),
                        "publishedAt", item.getPublishedAt(),
                        "summary", item.getSummary()
                ));
                if (item.getTitle() != null) {
                    content.append(item.getTitle()).append('\n');
                }
                if (item.getSummary() != null) {
                    content.append(item.getSummary()).append("\n\n");
                }
            }
            if (sourceUrls.isEmpty() && taskPackage.getSourceUrls() != null) {
                sourceUrls.addAll(taskPackage.getSourceUrls());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("feedUrl", taskPackage.getUrl());
            payload.put("items", payloadItems);

            return CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(true)
                    .status("SUCCESS")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .title(taskPackage.getUrl())
                    .content(content.toString().trim())
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .structuredPayload(payload)
                    .qualitySignals(payloadItems.isEmpty()
                            ? List.of("FEED_EMPTY")
                            : List.of("FEED_ITEMS_READY"))
                    .qualityScore(payloadItems.isEmpty() ? 0.30D : 0.68D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis())
                    .build()
                    .normalize();
        } catch (RuntimeException exception) {
            return CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(false)
                    .status("FAILED")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
                    .errorMessage(exception.getMessage())
                    .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                    .qualitySignals(List.of("FEED_COLLECTION_FAILED"))
                    .qualityScore(0.0D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis())
                    .build()
                    .normalize();
        }
    }
}
```

- [x] **Step 2: 更新 registry 测试，确保只注册 `RSS` 专项 owner**

```java
assertThat(registry.resolve(CollectionTaskPackage.builder()
        .primaryTool("RSS")
        .resourceLocator("rss://feed/aGVsbG8")
        .build()).executorType()).isEqualTo("API_DATA");

assertThat(registry.resolve(CollectionTaskPackage.builder()
        .primaryTool("JINA_READER")
        .resourceLocator("https://news.example.com/article")
        .build()).executorType()).isNotEqualTo("API_DATA");
```

- [x] **Step 3: 运行执行器测试**

Run:
`mvn -pl backend "-Dtest=CollectionExecutorRegistryTest,RssFeedCollectionExecutorTest" test`

Expected:
- PASS

---

### Task 5: 打通 coordinator / collector / replay / disabled 快速失败审计

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorCheckpointReuseTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`

- [x] **Step 1: 让 coordinator 对 RSS unavailable 走快速失败而不是伪重试**

```java
private CollectionExecutionResult buildToolUnavailableResult(CollectionTaskPackage taskPackage, String reason) {
    return CollectionExecutionResult.builder()
            .taskPackageKey(taskPackage.getPackageKey())
            .targetIndex(taskPackage.getTargetIndex())
            .executorType("API_DATA")
            .success(false)
            .status("FAILED")
            .resourceLocator(taskPackage.getResourceLocator())
            .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
            .errorMessage(reason)
            .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
            .qualitySignals(List.of("TOOL_UNAVAILABLE_FAST_FAIL"))
            .qualityScore(0.0D)
            .structuredBlocks(List.of())
            .collectedAt(Instant.now())
            .durationMillis(0L)
            .build()
            .normalize();
}
```

Implementation note:
- 这里**不做网页降级**。原因很简单：显式 feed URL 是 XML 资源，不等价于普通网页正文；对 RSS disabled 最合理的行为是“快速失败 + 清晰审计”，而不是伪装成网页采集成功。

- [x] **Step 2: 在 Coordinator / Collector / Replay 测试里锁定 RSS 成功与 disabled 失败审计**

```java
assertThat(audit.getReplayTimeline())
        .anyMatch(item -> item.getSummary() != null
                && item.getSummary().contains("TOOL_UNAVAILABLE_FAST_FAIL"));

assertTrue(output.path("collectionAudit").path("sourceUrls").isArray());
assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"items\""));
assertTrue(evidenceCaptor.getValue().getPageMetadata().contains("\"sourceUrls\""));
```

Implementation note:
- `CollectorAgentTest` 追加断言：
  - RSS 成功结果会持久化 `structuredPayload.items`
  - feed URL 与 item URL 都会进入 `output.sourceUrls / collectionAudit.sourceUrls / pageMetadata`
- `TaskReplayProjectionServiceTest` 追加断言：
  - `collectionReplays[*].collectionAudit.sourceUrls` 可见
  - disabled 快速失败场景的 `qualitySignals` 包含 `TOOL_UNAVAILABLE_FAST_FAIL`

- [x] **Step 3: 运行 coordinator / collector / replay 测试**

Run:
`mvn -pl backend "-Dtest=CollectionExecutionCoordinatorCheckpointReuseTest,CollectorAgentTest,TaskReplayProjectionServiceTest" test`

Expected:
- PASS

---

### Task 6: 第七轮聚合验证、文档回链与 dev live 验收

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

- [x] **Step 1: 运行第七轮聚合测试**

Run:
`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,RssFeedClientTest,RssFeedCollectionExecutorTest,CollectionExecutorRegistryTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectorAgentTest,TaskReplayProjectionServiceTest" test`

Expected:
- PASS

- [x] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [x] **Step 3: 执行第七轮 dev live 验收**

Manual API smoke:

1. `POST /api/task/{id}/execute`
   - 输入一个显式 feed URL：`https://blog.example.com/feed.xml`
   - 输入一个普通新闻文章 URL：`https://news.example.com/releases/acme-launches-agent`
2. 验证 feed URL
   - 确认结果走 `RSS` owner
   - 确认 `structuredPayload.items` 非空
   - 确认 `sourceUrls` 同时包含 `feedUrl + itemUrl`
3. 验证普通新闻文章 URL
   - 确认结果继续走网页采集路径，而不是 `RSS`
   - 确认 `resourceLocator` 仍为原始 URL
4. 验证 disabled 快速失败
   - 将 `rss-feed.enabled=false`
   - 再次触发 feed URL 采集
   - 确认结果为快速失败，`qualitySignals` 含 `TOOL_UNAVAILABLE_FAST_FAIL`
5. `GET /api/task/{id}/replay`
   - 确认 `collectionReplays[*].collectionAudit.sourceUrls` 与顶层 `sourceUrls` 可见

Expected:
- 第七轮验收重点不再是“News API 是否也接上”，而是“RSS 现在已经是正式专项 owner；普通新闻 URL 继续留在成熟的网页采集主链路；审计、回放、失败语义全部一致”。

Live result:
- `collect_sources_01_01` 在正常配置下已验证显式 feed URL 走 `RSS` owner，`searchExecutionTrace.selectedUrls=["http://127.0.0.1:18080/feed.xml"]`，`collectionAudit.summary.status="SUCCESS"`，`collectionAudit.results[0].executorType="API_DATA"`，`structuredPayload.feedUrl="http://127.0.0.1:18080/feed.xml"`，`structuredPayload.items` 含 2 条 RSS item，`sourceUrls` 同时保留 `feedUrl + itemUrl`。
- 针对“普通 news article URL 不应误走 RSS”的 live smoke，`collect_sources_01_02` 改配为 `https://openai.com/index/introducing-gpt-4o/` 后，`selectedTargets[0].url` 与 `collectionAudit.results[0].resourceLocator` 均保持 article URL，`collectionAudit.results[0].executorType="WEB_PAGE"`，证明路由留在网页采集主链路；该 smoke 因 `Playwright content empty` 进入 `WAITING_INTERVENTION`，但不影响 owner 路由结论。
- 针对 `rss-feed.enabled=false` 的 live smoke，使用正确覆盖键 `--collection.rss-feed.enabled=false` 重启 backend 后再次执行 `collect_sources_01_01`，结果为 `executorType="API_DATA"`、`resourceLocator="rss://feed/http%3A%2F%2F127.0.0.1%3A18080%2Ffeed.xml"`、`qualitySignals=["TOOL_UNAVAILABLE_FAST_FAIL"]`，确认显式 feed URL 在 RSS owner 不可用时会快速失败，而不是降级到网页采集。
- 额外 live 修复确认：`config-rerun` 显式传入 `searchAuditCheckpoint=null` / `collectionAuditCheckpoint=null` 后，不再被旧 `outputData` 回填；`CanonicalUrlResolver` 对 `http://127.0.0.1:18080/feed.xml` 这类本地显式端口 feed URL 不再错误改写为 `https://127.0.0.1/feed.xml`。

- [x] **Step 4: 回写父计划与 specs 状态**

Update parent plan wording like:

```md
第七轮实施收敛为“显式 RSS feed 正式接入 + 普通 news article URL 保持网页采集”；
News API 未并入当前 collector wave，
后续若要引入主动新闻发现，应单开专题处理 provider 语义、time window、rate limit 与 schema 差异。
```

Update specs wording like:

```md
- 实施：第七轮优先把 RSS 作为正式采集 owner 接入 `collectionAudit / replay / checkpoint`；
  已知新闻文章 URL 继续走网页采集；
  News API 因不适合按 URL 精确提取正文，被后移到后续独立 news discovery 专题。
```

- [x] **Step 5: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，下一步建议二选一：
1. 若当前瓶颈在“持续 feed 增量摄取”：进入 `Wave 11`，为 `RssFeedCollectionExecutor` 增加 subscription / cursor / dedupe，并在那一轮决定是否补 Atom。
2. 若当前瓶颈在“主动新闻发现能力缺失”：单开新的 `news discovery` 专题，为 News API / Bing News Search 设计关键词 + 时间窗查询、rate limit、provider schema 适配与集成测试。
```

Completion note:
- 建议 1：若当前瓶颈在“持续 feed 增量摄取”，进入 `Wave 11`，为 `RssFeedCollectionExecutor` 增加 subscription / cursor / dedupe，并在同轮决定是否补 `Atom`。
- 建议 2：若当前瓶颈在“主动新闻发现能力缺失”，单开新的 `news discovery` 专题，为 `News API / Bing News Search` 设计关键词 + 时间窗查询、rate limit、provider schema 适配与集成测试。

---

## Verification

- 路由与配置：`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest" test`
- RSS 客户端：`mvn -pl backend "-Dtest=RssFeedClientTest" test`
- RSS 执行器与 registry：`mvn -pl backend "-Dtest=CollectionExecutorRegistryTest,RssFeedCollectionExecutorTest" test`
- Coordinator / Collector / replay：`mvn -pl backend "-Dtest=CollectionExecutionCoordinatorCheckpointReuseTest,CollectorAgentTest,TaskReplayProjectionServiceTest" test`
- 第七轮整体：`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,RssFeedClientTest,RssFeedCollectionExecutorTest,CollectionExecutorRegistryTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectorAgentTest,TaskReplayProjectionServiceTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. “显式 feed URL 才走 RSS、普通新闻 URL 留在网页链路”由 Task 1、Task 2 覆盖。
2. `RSS 2.0` 客户端、中文 fixture、HTML 快速失败和截断行为由 Task 1、Task 3 覆盖。
3. `RssFeedCollectionExecutor` 的正式结果协议由 Task 4 覆盖。
4. `collectionAudit / replay / checkpoint / CollectorAgent` 对 RSS 结果与 disabled 快速失败语义的承接由 Task 5 覆盖。
5. 文档回链与 dev live 验收由 Task 6 覆盖。

### Placeholder scan

1. 本计划没有保留“稍后补 News API”式空占位；`News API` 已被明确移出本轮 scope。
2. `Atom` 未再以“口头兼容”方式悬空，而是明确写成不做项。
3. 每个任务都给出了具体文件、代码片段、测试命令与预期结果。

### Type consistency

1. `RssFeedProperties / RssFeedClient / RssFeedItem / RssFeedCollectionExecutor` 始终只承接 RSS 2.0 一次性采集。
2. `CollectionTaskPackageBuilder` 在本轮只新增 `RSS` 专项路由，不再生成 `NEWS_API` 包。
3. 普通新闻文章 URL 继续留在现有网页采集主链路，不与 RSS executor 混淆。
4. `CollectionExecutionResult` 继续是唯一正式结果协议；本轮不再引入任何 `News API` 并行旁路结果。
