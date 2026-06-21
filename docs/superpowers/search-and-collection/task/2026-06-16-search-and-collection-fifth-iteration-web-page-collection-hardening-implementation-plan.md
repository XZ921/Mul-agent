# Search And Collection Fifth Iteration Web Page Collection Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Status Note (2026-06-17):** 本文档保留为第五轮实施期的历史快照。下文“执行中 / 待执行”勾选未随落地结果逐条回写，不代表当前工程现状。第五轮 `Wave 8` 已在父方案中回写为“实现与自动化收口完成”；现状请以 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 为准。

**Goal:** 承接父方案 `Wave 8`，把网页采集从“`PlaywrightPageCollector` 单路径 + HTTP 先行短路”升级为“`JinaReader` 主路径 + `Playwright` `FULL_RENDER` 兜底”，并把网页采集真实暴露出的失败模式、结构块与质量信号收口成正式最小契约。

**Architecture:** 本计划直接继承 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 中 `Wave 8` 的目标，以及 [CollectionExecution.md](/E:/java_study/Mul-agnet/docs/problem/CollectionExecution.md) 对“采集执行层已成为现实 blocker”的专项诊断。实现顺序固定为 `红灯契约 -> 采集契约扩展 -> JinaReader 主路径 -> Playwright FULL_RENDER 兜底 -> 分层提取 / 结构块 / 质量评分 -> Collector 兼容映射与文档回链`。本轮默认第四轮最小采集接缝已存在；若当前工作树尚未完成第四轮，则先以第四轮接缝为前置，不允许把 GitHub/API owner 问题重新塞回第五轮。

**Tech Stack:** Java 17, Spring Boot, Jackson, Java HttpClient, Playwright Java, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. 把 `CollectionTaskPackage`、`CollectionExecutionResult` 从 `Wave 7` 的最小壳扩成 `Wave 8` 可用的网页采集契约，至少补齐：
   - `renderHint`
   - `failureKind`
   - `qualitySignals`
   - `qualityScore`
   - `structuredBlocks`
   - `collectedAt`
   - `durationMillis`
2. 让 `SearchSourceCatalogProperties` 与 `SearchPolicyResolver` 能表达网页采集偏好，而不是继续让 `CollectionTaskPackageBuilder` 硬编码所有网页来源都走同一路径。
3. 正式引入 `JinaReader` 主路径，用于 `official / docs / pricing / review / 普通 news article` 等公开可访问、无需交互的轻量正文采集。
4. 让 `WebPageCollectionExecutor` 升级为双路径执行器：
   - `LIGHTWEIGHT` 优先走 `JinaReader`
   - `FULL_RENDER` 只走 `Playwright`
   - `LIGHTWEIGHT` 内容不足、疑似壳页或策略要求时，允许升级到 `Playwright`
5. 让 `PlaywrightPageCollector` 停止对 `Wave 8` 管控路径继续执行“HTTP 成功即采集完成”的早退语义。
6. 为 `PageContentExtractionSupport` 建立分层提取结果，而不是只返回一个大字符串；至少支持：
   - 主正文
   - 最小结构块集合
   - 质量信号
   - 质量分
7. `CollectorAgent` 必须继续兼容旧的 `EvidenceSource` 与 `SourceCollector.CollectedPage` 持久化路径，但新增网页契约字段不得在兼容映射时丢失 `sourceUrls`。
8. 所有新增对象继续满足 `sourceUrls` 可追溯红线。

### 本轮明确不做

1. 不把 `collectionAudit / collectionReplayTimeline / collectionCheckpoint` 全量正式化；这些仍归父方案 `Wave 9`。
2. 不把 `News API / RSS / GitHub API` 的更多采集 owner 扩展重新混入本轮；这些仍归 `Wave 10`。
3. 不把 `site crawl` 抬成第四类核心执行器。
4. 不做前端采集详情页协议的全量切换。
5. 不做代理池、IP 池、外部 anti-bot SaaS、重度浏览器指纹仿真。
6. 不在本轮内重写 `SchemaExtractorAgent` 的下游 evidence bundle 设计；仅把网页采集输出先改造成更适合后续对接的最小对象。

### 与已存在专项的边界

1. [2026-06-16-playwright-anti-bot-stability-plan.md](/E:/java_study/Mul-agnet/docs/problem/2026-06-16-playwright-anti-bot-stability-plan.md) 已经定义并推进了：
   - 浏览器故障分类
   - 多信号反爬检测
   - 最小 stealth
   - 结构化浏览器诊断日志
2. 第五轮不重复发明这些基础设施语义，而是把它们当作 `Playwright` 兜底路径的现成输入。
3. 如果当前工作树缺失这些能力，则先按该专项回补，再执行本计划；第五轮不接受“无故障分类但先做双路径路由”的倒序施工。

---

## Progress Snapshot

当前阶段：第五轮计划编写中；第四轮最小采集接缝已在当前工作树出现，采集执行专项诊断与 Playwright 反爬专项均已形成可复用输入

- [x] 父方案 `Wave 8` 范围确认：已完成
- [x] 前四轮实施上下文梳理：已完成
- [x] 采集执行专项诊断归并：已完成
- [ ] 第五轮实施计划落稿：执行中
- [ ] 第五轮实现与验证：待执行

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase I1 | 锁定 `Wave 8` 双路径网页采集红灯契约 | 0.5 天 | 第四轮最小采集接缝已存在 | 待执行 |
| Phase I2 | 扩展网页采集契约与 source family 采集偏好 | 1 天 | Phase I1 红灯测试存在 | 待执行 |
| Phase I3 | 引入 `JinaReader` 主路径与 `WebPageCollectionExecutor` 双路由 | 1-1.5 天 | Phase I2 完成 | 待执行 |
| Phase I4 | 收口 `Playwright` 为 `FULL_RENDER` 兜底与页面就绪模型 | 1-2 天 | Phase I2-I3 完成，且浏览器稳定性专项可复用 | 待执行 |
| Phase I5 | 分层正文提取、结构块抽取与质量评分正式化 | 1-1.5 天 | Phase I3-I4 完成 | 待执行 |
| Phase I6 | `CollectorAgent` 兼容映射、文档回链与整体验证 | 0.5-1 天 | Phase I1-I5 完成 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageRenderHint.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionFailureKind.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/StructuredContentBlock.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollectRequest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderClient.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionResult.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageReadinessContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/PageContentExtractionSupportStructuredBlockTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

### Docs - Modify

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

### Task 1: 锁定 `Wave 8` 双路径网页采集红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageReadinessContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/PageContentExtractionSupportStructuredBlockTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`

- [ ] **Step 1: 先锁定 `CollectionTaskPackage` 的网页执行提示契约**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionTaskPackageBuilderTest {

    @Test
    void shouldBuildLightweightWebTaskPackageForDocsFamily() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://docs.example.com/api/reference")
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .providerKey("serpapi")
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build();

        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(null);
        CollectionTaskPackage taskPackage = builder.build(
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                candidate,
                1
        );

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(taskPackage.getRenderHint()).isEqualTo(WebPageRenderHint.LIGHTWEIGHT);
        assertThat(taskPackage.getExpectedBlockTypes())
                .contains("DOCUMENTATION_OUTLINE", "JSON_LD_METADATA");
    }
}
```

- [ ] **Step 2: 锁定 `WebPageCollectionExecutor` 的双路径路由红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.JinaReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPageCollectionExecutorRouteTest {

    @Test
    void shouldUseJinaReaderAsPrimaryPathForLightweightDocsPage() {
        JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("API Reference")
                .mainContent("这是可用的文档正文。")
                .qualityScore(0.92D)
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build());

        WebPageCollectionExecutor executor = new WebPageCollectionExecutor(jinaReaderClient, sourceCollector);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .url("https://docs.example.com/api/reference")
                .resourceLocator("https://docs.example.com/api/reference")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailureKind()).isNull();
        verify(sourceCollector, never()).collect(any(SourceCollectRequest.class));
    }
}
```

- [ ] **Step 3: 锁定 `JinaReader` 内容不足时升级 `Playwright` 的红灯测试**

```java
@Test
void shouldFallbackToPlaywrightWhenJinaReaderReturnsThinContent() {
    JinaReaderClient jinaReaderClient = mock(JinaReaderClient.class);
    SourceCollector sourceCollector = mock(SourceCollector.class);
    when(jinaReaderClient.collect(any())).thenReturn(PageContentExtractionResult.builder()
            .success(false)
            .failureKind("CONTENT_UNUSABLE")
            .qualityScore(0.18D)
            .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_TOO_THIN"))
            .build());
    when(sourceCollector.collect(any(SourceCollectRequest.class))).thenReturn(SourceCollector.CollectedPage.builder()
            .url("https://pricing.example.com")
            .title("Pricing")
            .content("这里是完整定价页正文和套餐信息。")
            .snippet("完整定价页正文")
            .sourceType("PRICING")
            .competitorName("Acme AI")
            .success(true)
            .build());

    WebPageCollectionExecutor executor = new WebPageCollectionExecutor(jinaReaderClient, sourceCollector);
    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .primaryTool("JINA_READER")
            .renderHint(WebPageRenderHint.LIGHTWEIGHT)
            .url("https://pricing.example.com")
            .resourceLocator("https://pricing.example.com")
            .sourceType("PRICING")
            .sourceUrls(List.of("https://pricing.example.com"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getQualitySignals()).contains("UPGRADED_TO_FULL_RENDER");
    verify(sourceCollector).collect(any(SourceCollectRequest.class));
}
```

- [ ] **Step 4: 锁定 `Playwright` 页面就绪与结构块抽取红灯契约**

```java
package cn.bugstack.competitoragent.source;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageContentExtractionSupportStructuredBlockTest {

    @Test
    void shouldExtractPricingAndDocumentationBlocksWithoutRelyingOnLongestArticle() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("""
                <html>
                  <body>
                    <main>
                      <section class="pricing-card">Pro ¥199 / 月</section>
                      <nav class="docs-outline">
                        <a>快速开始</a>
                        <a>API 参考</a>
                      </nav>
                    </main>
                  </body>
                </html>
                """);

        PageContentExtractionResult result = PageContentExtractionSupport.extract(page, "PRICING");

        assertThat(result.getStructuredBlocks())
                .extracting(StructuredContentBlock::getBlockType)
                .contains("PRICING_BLOCK", "DOCUMENTATION_OUTLINE");
        assertThat(result.getQualitySignals()).contains("STRUCTURED_BLOCK_HIT");
    }
}
```

```java
package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JinaReaderClientTest {

    @Test
    void shouldWrapOriginalUrlIntoJinaReaderEndpointAndPreserveSourceUrls() {
        JinaReaderProperties properties = new JinaReaderProperties();
        properties.setEndpoint("https://r.jina.ai/http://");
        JinaReaderClient client = new JinaReaderClient(properties, null);

        String resolved = client.resolveReaderUrl("https://docs.example.com/api/reference");

        assertThat(resolved).isEqualTo("https://r.jina.ai/http://docs.example.com/api/reference");
    }
}
```

- [ ] **Step 5: 运行第五轮首批红灯测试**

Run:
`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest,PlaywrightPageReadinessContractTest,PageContentExtractionSupportStructuredBlockTest" test`

Expected:
- FAIL
- `CollectionTaskPackage` 尚无 `renderHint / expectedBlockTypes`
- `CollectionExecutionResult` 尚无 `failureKind / qualitySignals / structuredBlocks`
- `WebPageCollectionExecutor` 仍然只知道 `SourceCollector.collect(url, ...)`
- `JinaReaderClient`、`PageContentExtractionResult`、`SourceCollectRequest` 尚不存在

---

### Task 2: 扩展网页采集契约与 source family 采集偏好

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageRenderHint.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionFailureKind.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/StructuredContentBlock.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [ ] **Step 1: 新建网页执行提示与失败模式枚举**

```java
package cn.bugstack.competitoragent.collection;

/**
 * 网页采集执行提示。
 * LIGHTWEIGHT 代表优先走轻量正文读取路径；
 * FULL_RENDER 代表必须进入浏览器完整渲染；
 * LOGIN_REQUIRED / ANTI_BOT_RISK_HIGH 先作为预留枚举，避免后续再靠字符串扩散。
 */
public enum WebPageRenderHint {
    LIGHTWEIGHT,
    FULL_RENDER,
    LOGIN_REQUIRED,
    INTERACTION_REQUIRED,
    ANTI_BOT_RISK_HIGH
}
```

```java
package cn.bugstack.competitoragent.collection;

/**
 * 采集失败类型。
 * 本轮先沉淀网页采集最常见的正式失败语义，后续 Wave 9 再接 collectionAudit。
 */
public enum CollectionFailureKind {
    CONTENT_UNUSABLE,
    ANTI_BOT_BLOCKED,
    PAGE_TIMEOUT,
    RUNTIME_FAILURE,
    HTTP_STATUS_ERROR,
    EXTRACTION_EMPTY
}
```

- [ ] **Step 2: 扩展 `SourceFamilyProperties` 的网页采集偏好字段**

```java
/**
 * 网页采集偏好只解释“公开网页应该怎么采”，
 * 不替代 provider route，也不替代 API executor owner。
 */
private String preferredWebRenderHint;
private List<String> expectedBlockTypes = new ArrayList<>();
```

```java
public String resolvePreferredWebRenderHint() {
    return StringUtils.hasText(preferredWebRenderHint)
            ? preferredWebRenderHint.trim().toUpperCase(Locale.ROOT)
            : "LIGHTWEIGHT";
}
```

Implementation note:
- `official / news / review / docs / pricing` 默认给 `LIGHTWEIGHT`
- `github` 仍由第四轮 API owner 承接，不在这里改成网页主路径

- [ ] **Step 3: 在 Java 默认 family 工厂方法中补齐网页采集偏好默认值**

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
            List.of(
                    "search-official",
                    "search-official-domain",
                    "search-pricing-primary",
                    "search-docs-primary"
            )
    );
    family.setPreferredWebRenderHint("LIGHTWEIGHT");
    family.setExpectedBlockTypes(List.of(
            "PRICING_BLOCK",
            "DOCUMENTATION_OUTLINE",
            "JSON_LD_METADATA"
    ));
    return family;
}

private SourceFamilyProperties createNewsFamily() {
    SourceFamilyProperties family = new SourceFamilyProperties(
            true,
            SearchProviderRole.PRIMARY_VERTICAL.name(),
            List.of("NEWS"),
            List.of("PRODUCT_RELEASE", "FUNDING", "PARTNERSHIP"),
            List.of("NEWS_API", "RSS"),
            List.of("PUBLIC_SEARCH"),
            new UpdatePolicyProperties("REALTIME_RSS_AND_SCHEDULED_SWEEP", "PT1H"),
            List.of("search-news-primary", "search-news-secondary")
    );
    family.setPreferredWebRenderHint("LIGHTWEIGHT");
    family.setExpectedBlockTypes(List.of("JSON_LD_METADATA", "ARTICLE_BODY"));
    return family;
}

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
    family.setPreferredWebRenderHint("FULL_RENDER");
    family.setExpectedBlockTypes(List.of("RELEASE_NOTES", "JSON_LD_METADATA"));
    family.getToolProviderKeys().put("GITHUB_API", "github");
    family.getToolProviderKeys().put("PUBLIC_SEARCH", "qianfan");
    return family;
}
```

- [ ] **Step 4: 在 `application.yml` 中补充 source family 覆盖示例，避免只靠 Java 默认值暗含新语义**

```yaml
search:
  source-catalog:
    families:
      official:
        preferred-web-render-hint: LIGHTWEIGHT
        expected-block-types:
          - PRICING_BLOCK
          - DOCUMENTATION_OUTLINE
          - JSON_LD_METADATA
      news:
        preferred-web-render-hint: LIGHTWEIGHT
        expected-block-types:
          - ARTICLE_BODY
          - JSON_LD_METADATA
      github:
        preferred-web-render-hint: FULL_RENDER
        expected-block-types:
          - RELEASE_NOTES
          - JSON_LD_METADATA
```

- [ ] **Step 5: 在 `SearchPolicyResolver` 中补 collection hint 解析入口**

```java
public WebPageRenderHint resolveWebRenderHint(String sourceFamilyKey, String sourceType) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(sourceFamilyKey);
    if (family == null) {
        return WebPageRenderHint.LIGHTWEIGHT;
    }
    return WebPageRenderHint.valueOf(family.resolvePreferredWebRenderHint());
}

public List<String> resolveExpectedBlockTypes(String sourceFamilyKey, String sourceType) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(sourceFamilyKey);
    return family == null || family.getExpectedBlockTypes() == null
            ? List.of()
            : family.getExpectedBlockTypes();
}
```

- [ ] **Step 6: 扩展 `CollectionTaskPackage` 与 `CollectionExecutionResult` 的最小网页字段**

```java
package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    WebPageRenderHint renderHint;
    List<String> expectedBlockTypes;
    List<String> targetFields;
    Integer priority;
    List<String> sourceUrls;
}
```

```java
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
    String failureKind;
    List<String> qualitySignals;
    Double qualityScore;
    List<StructuredContentBlock> structuredBlocks;
    Instant collectedAt;
    Long durationMillis;
}
```

- [ ] **Step 7: 让 `CollectionTaskPackageBuilder` 基于 source family 解析主工具与 render hint**

```java
@Component
public class CollectionTaskPackageBuilder {

    private final SearchPolicyResolver searchPolicyResolver;

    public CollectionTaskPackageBuilder(SearchPolicyResolver searchPolicyResolver) {
        this.searchPolicyResolver = searchPolicyResolver;
    }

    public CollectionTaskPackage build(Long taskId,
                                       String nodeName,
                                       Long planVersionId,
                                       String competitorName,
                                       SourceCandidate candidate,
                                       int priority) {
        String sourceFamilyKey = candidate == null ? null : candidate.getSourceFamilyKey();
        String sourceType = candidate == null ? null : candidate.getSourceType();
        String url = candidate == null ? null : candidate.getUrl();
        WebPageRenderHint renderHint = resolveRenderHint(sourceFamilyKey, sourceType);
        String primaryTool = resolvePrimaryTool(sourceFamilyKey, sourceType, renderHint);
        return CollectionTaskPackage.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .competitorName(competitorName)
                .sourceFamilyKey(sourceFamilyKey)
                .sourceType(sourceType)
                .primaryTool(primaryTool)
                .url(url)
                .resourceLocator(resolveResourceLocator(primaryTool, url))
                .renderHint(renderHint)
                .expectedBlockTypes(searchPolicyResolver.resolveExpectedBlockTypes(sourceFamilyKey, sourceType))
                .targetFields(List.of())
                .priority(priority)
                .sourceUrls(resolveSourceUrls(candidate, url))
                .build();
    }
}
```

Implementation note:
- `github` 继续返回 `GITHUB_API`
- `LIGHTWEIGHT` 网页路径返回 `JINA_READER`
- `FULL_RENDER` 网页路径返回 `WEB_SCRAPER`

- [ ] **Step 8: 扩展配置绑定测试**

```java
assertThat(searchProperties.getSourceCatalog().getFamilies().get("official").getPreferredWebRenderHint())
        .isEqualTo("LIGHTWEIGHT");
assertThat(searchProperties.getSourceCatalog().getFamilies().get("official").getExpectedBlockTypes())
        .contains("DOCUMENTATION_OUTLINE", "JSON_LD_METADATA");
```

- [ ] **Step 9: 运行契约扩展测试**

Run:
`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`

Expected:
- PASS

---

### Task 3: 引入 `JinaReader` 主路径与 `WebPageCollectionExecutor` 双路由

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderProperties.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/JinaReaderClient.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistry.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutorRegistryTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutorRouteTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/JinaReaderClientTest.java`

- [ ] **Step 1: 新建 `JinaReader` 配置对象**

```java
package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "collection.jina-reader")
public class JinaReaderProperties {

    private boolean enabled = true;
    private String endpoint = "https://r.jina.ai/http://";
    private String bearerToken;
    private int timeoutSeconds = 20;
    private int maxRetries = 2;
    private int minimumContentLength = 160;
}
```

- [ ] **Step 2: 新建 `JinaReaderClient`，并显式保留重试与异常收口**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class JinaReaderClient {

    private final JinaReaderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public JinaReaderClient(JinaReaderProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PageContentExtractionResult collect(SourceCollectRequest request) {
        Instant startedAt = Instant.now();
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxRetries()); attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolveReaderUrl(request.getUrl())))
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .header("Accept", "text/plain")
                        .GET();
                if (StringUtils.hasText(properties.getBearerToken())) {
                    builder.header("Authorization", "Bearer " + properties.getBearerToken());
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return PageContentExtractionResult.failure(
                            CollectionFailureKind.HTTP_STATUS_ERROR.name(),
                            "jina reader status=" + response.statusCode(),
                            startedAt,
                            List.of("LIGHTWEIGHT_HTTP_STATUS_ERROR")
                    );
                }
                String markdown = response.body();
                if (markdown == null || markdown.trim().length() < properties.getMinimumContentLength()) {
                    return PageContentExtractionResult.failure(
                            CollectionFailureKind.CONTENT_UNUSABLE.name(),
                            "jina reader content too thin",
                            startedAt,
                            List.of("LIGHTWEIGHT_CONTENT_TOO_THIN")
                    );
                }
                return PageContentExtractionResult.builder()
                        .success(true)
                        .title(request.getUrl())
                        .mainContent(markdown.trim())
                        .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                        .qualityScore(0.78D)
                        .structuredBlocks(List.<StructuredContentBlock>of())
                        .collectedAt(Instant.now())
                        .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                        .build();
            } catch (Exception e) {
                lastError = new IllegalStateException("jina reader request failed: " + e.getMessage(), e);
            }
        }
        return PageContentExtractionResult.failure(
                CollectionFailureKind.RUNTIME_FAILURE.name(),
                lastError == null ? "jina reader failed" : lastError.getMessage(),
                startedAt,
                List.of("LIGHTWEIGHT_RUNTIME_FAILURE")
        );
    }

    /**
     * Jina Reader 的路径规则不是简单 endpoint + 完整 URL 字符串拼接，
     * 而是把原始 URL 的协议前缀剥掉后，挂到 reader endpoint 后面。
     * 例如：
     * https://docs.example.com/api/reference
     * -> https://r.jina.ai/http://docs.example.com/api/reference
     */
    String resolveReaderUrl(String originalUrl) {
        if (!StringUtils.hasText(originalUrl)) {
            throw new IllegalArgumentException("originalUrl must not be blank");
        }
        String normalized = originalUrl.trim();
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
            return ensureTrailingSlash(properties.getEndpoint()) + "http://" + normalized;
        }
        if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
            return ensureTrailingSlash(properties.getEndpoint()) + "http://" + normalized;
        }
        return ensureTrailingSlash(properties.getEndpoint()) + normalized;
    }

    private String ensureTrailingSlash(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "https://r.jina.ai/";
        }
        return endpoint.endsWith("/") ? endpoint : endpoint + "/";
    }
}
```

Implementation note:
- `resolveReaderUrl(...)` 必须显式 strip 原始 URL 协议前缀，不能直接做 `endpoint + originalUrl`
- 如果后续确认 Jina Reader 对 `http://` / `https://` 前缀有更严格要求，应统一只改这一处，不允许在调用点重复拼接

- [ ] **Step 3: 让 `WebPageCollectionExecutor` 升级为双路径执行器**

```java
@Component
public class WebPageCollectionExecutor implements CollectionExecutor {

    private final JinaReaderClient jinaReaderClient;
    private final SourceCollector sourceCollector;

    public WebPageCollectionExecutor(JinaReaderClient jinaReaderClient,
                                     SourceCollector sourceCollector) {
        this.jinaReaderClient = jinaReaderClient;
        this.sourceCollector = sourceCollector;
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        long startedAt = System.currentTimeMillis();
        if (taskPackage.getRenderHint() == WebPageRenderHint.FULL_RENDER) {
            return collectByPlaywright(taskPackage, startedAt, List.of("FULL_RENDER_REQUIRED"));
        }

        PageContentExtractionResult lightweightResult = jinaReaderClient.collect(SourceCollectRequest.builder()
                .url(taskPackage.getUrl())
                .competitorName(taskPackage.getCompetitorName())
                .sourceType(taskPackage.getSourceType())
                .renderHint(taskPackage.getRenderHint())
                .expectedBlockTypes(taskPackage.getExpectedBlockTypes())
                .sourceUrls(taskPackage.getSourceUrls())
                .build());
        if (lightweightResult.isSuccess() && lightweightResult.isUsable()) {
            return mapLightweightResult(taskPackage, lightweightResult);
        }
        return collectByPlaywright(taskPackage,
                startedAt,
                mergeSignals(lightweightResult.getQualitySignals(), List.of("UPGRADED_TO_FULL_RENDER")));
    }

    private CollectionExecutionResult collectByPlaywright(CollectionTaskPackage taskPackage,
                                                          long startedAt,
                                                          List<String> additionalSignals) {
        SourceCollector.CollectedPage page = sourceCollector.collect(SourceCollectRequest.builder()
                .url(taskPackage.getUrl())
                .competitorName(taskPackage.getCompetitorName())
                .sourceType(taskPackage.getSourceType())
                .renderHint(WebPageRenderHint.FULL_RENDER)
                .expectedBlockTypes(taskPackage.getExpectedBlockTypes())
                .sourceUrls(taskPackage.getSourceUrls())
                .build());
        if (page == null) {
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(false)
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                    .qualitySignals(additionalSignals)
                    .qualityScore(0.0D)
                    .structuredBlocks(List.of())
                    .errorMessage("playwright collector returned null page")
                    .durationMillis(System.currentTimeMillis() - startedAt)
                    .collectedAt(java.time.Instant.now())
                    .build();
        }
        return CollectionExecutionResult.builder()
                .executorType(executorType())
                .success(page.isSuccess())
                .resourceLocator(taskPackage.getResourceLocator())
                .title(page.getTitle())
                .content(page.getContent())
                .sourceUrls(taskPackage.getSourceUrls())
                .failureKind(page.isSuccess() ? null : CollectionFailureKind.CONTENT_UNUSABLE.name())
                .qualitySignals(additionalSignals)
                .qualityScore(page.isSuccess() ? 0.60D : 0.0D)
                .structuredBlocks(List.of())
                .errorMessage(page.getErrorMessage())
                .durationMillis(System.currentTimeMillis() - startedAt)
                .collectedAt(java.time.Instant.now())
                .build();
    }

    private CollectionExecutionResult mapLightweightResult(CollectionTaskPackage taskPackage,
                                                           PageContentExtractionResult lightweightResult) {
        return CollectionExecutionResult.builder()
                .executorType(executorType())
                .success(true)
                .resourceLocator(taskPackage.getResourceLocator())
                .title(lightweightResult.getTitle())
                .content(lightweightResult.getMainContent())
                .sourceUrls(taskPackage.getSourceUrls())
                .failureKind(null)
                .qualitySignals(lightweightResult.getQualitySignals())
                .qualityScore(lightweightResult.getQualityScore())
                .structuredBlocks(lightweightResult.getStructuredBlocks())
                .errorMessage(null)
                .durationMillis(lightweightResult.getDurationMillis())
                .collectedAt(lightweightResult.getCollectedAt())
                .build();
    }

    private List<String> mergeSignals(List<String> currentSignals, List<String> additionalSignals) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (currentSignals != null) {
            merged.addAll(currentSignals);
        }
        if (additionalSignals != null) {
            merged.addAll(additionalSignals);
        }
        return new java.util.ArrayList<>(merged);
    }
}
```

Implementation note:
- `collectByPlaywright(...)` 不是可选辅助方法，而是第五轮里“`FULL_RENDER` 正式 owner”的最小落点
- 如果 `PlaywrightPageCollector` 在本轮已能直接返回结构块、质量信号或失败分类，`collectByPlaywright(...)` 应优先透传这些字段，而不是重新硬编码默认值

- [ ] **Step 4: 扩展注册表测试，确认网页 executor 仍能被稳定解析**

```java
assertThat(registry.resolve(CollectionTaskPackage.builder()
        .primaryTool("JINA_READER")
        .renderHint(WebPageRenderHint.LIGHTWEIGHT)
        .resourceLocator("https://docs.example.com/api/reference")
        .build()).executorType()).isEqualTo("WEB_PAGE");
```

- [ ] **Step 5: 运行双路径路由测试**

Run:
`mvn -pl backend "-Dtest=CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest" test`

Expected:
- PASS

---

### Task 4: 收口 `Playwright` 为 `FULL_RENDER` 兜底，并引入页面就绪模型

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollectRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCollector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageReadinessContractTest.java`

- [ ] **Step 1: 新建 `SourceCollectRequest`，避免 `Playwright` 无法收到执行提示**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 网页采集请求。
 * 旧的 collect(url, competitorName, sourceType) 继续保留为兼容入口，
 * 但 Wave 8 开始正式路径必须通过 request 传递 renderHint 和结构块预期。
 */
@Value
@Builder
public class SourceCollectRequest {
    String url;
    String competitorName;
    String sourceType;
    WebPageRenderHint renderHint;
    List<String> expectedBlockTypes;
    List<String> sourceUrls;
}
```

- [ ] **Step 2: 扩展 `SourceCollector` 接口，但保留旧调用兼容**

```java
public interface SourceCollector {

    CollectedPage collect(SourceCollectRequest request);

    default CollectedPage collect(String url, String competitorName, String sourceType) {
        return collect(SourceCollectRequest.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .build());
    }
}
```

Implementation note:
- 这里的兼容路径依赖 Java `default` 方法把旧签名桥接到新签名，因此 `PlaywrightPageCollector` 需要把原先 `collect(String, String, String)` 中的主逻辑整体迁移到 `collect(SourceCollectRequest)`，而不是保留两份分叉实现
- 旧签名不再要求被实现类显式 `@Override`，统一通过接口默认方法转发

- [ ] **Step 3: 让 `PlaywrightPageCollector` 对 `FULL_RENDER` 关闭 HTTP-first 早退**

```java
@Override
public CollectedPage collect(SourceCollectRequest request) {
    try {
        if (!UrlSecurityUtils.isHttpUrl(request.getUrl())) {
            return failed(request.getUrl(), request.getCompetitorName(), request.getSourceType(), "仅允许采集 http/https 页面");
        }

        boolean forceFullRender = request.getRenderHint() == WebPageRenderHint.FULL_RENDER
                || request.getRenderHint() == WebPageRenderHint.LOGIN_REQUIRED
                || request.getRenderHint() == WebPageRenderHint.INTERACTION_REQUIRED
                || request.getRenderHint() == WebPageRenderHint.ANTI_BOT_RISK_HIGH;
        if (!forceFullRender) {
            CollectedPage httpPage = collectByHttp(request.getUrl(), request.getCompetitorName(), request.getSourceType());
            if (httpPage.isSuccess()) {
                return httpPage;
            }
        }
        return collectByBrowser(request, forceFullRender ? "FULL_RENDER_REQUIRED" : "LIGHTWEIGHT_FALLBACK");
    } catch (Exception e) {
        String failureCode = fallbackPolicy.classifyRuntimeFailure(e);
        return failed(request.getUrl(), request.getCompetitorName(), request.getSourceType(),
                fallbackPolicy.buildCollectionFailureMessage(failureCode, e.getMessage()));
    }
}
```

- [ ] **Step 4: 为浏览器兜底建立最小页面就绪模型**

```java
private void waitForRenderableContent(Page page, SourceCollectRequest request) {
    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    try {
        page.waitForLoadState(LoadState.LOAD,
                new Page.WaitForLoadStateOptions().setTimeout((double) Math.min(5000, resolveTimeoutMillis())));
    } catch (Exception ignored) {
        // 页面可能仍在加载第三方资源，但不因此直接失败。
    }
    try {
        page.waitForSelector("main, article, [role='main'], .pricing-card, .docs-outline",
                new Page.WaitForSelectorOptions().setTimeout((double) Math.min(4000, resolveTimeoutMillis())));
    } catch (Exception ignored) {
        // 这里不直接失败，后续反爬检测与正文提取会决定是否可用。
    }
}
```

Implementation note:
- 当前浏览器故障分类、多信号反爬、最小 stealth 继续复用既有 `BrowserFailureClassifier`、`AntiBotSignalDetector`、`BrowserRuntimeDiagnosticLogger`
- 第五轮只补齐“页面什么时候算真的 ready to extract”

- [ ] **Step 5: 核对旧签名调用点已迁移或由默认方法接管**

Run:
`rg -n "collect\\([^\\)]*String url, String competitorName, String sourceType|sourceCollector\\.collect\\(" backend/src/main/java backend/src/test/java`

Expected:
- `SourceCollector` 旧签名调用仍然允许存在，但都会经由接口 `default` 方法转发
- `PlaywrightPageCollector` 主逻辑只保留在 `collect(SourceCollectRequest)` 一处
- 不保留两套分叉采集主流程

- [ ] **Step 6: 运行 `Playwright` 兜底契约与间接调用回归测试**

Run:
`mvn -pl backend "-Dtest=PlaywrightPageCollectorTest,PlaywrightPageReadinessContractTest,BrowserSearchRuntimeServiceTest,SearchExecutionCoordinatorTest,RoutingSearchSourceProviderTest" test`

Expected:
- PASS

---

### Task 5: 分层正文提取、结构块抽取与质量评分正式化

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PageContentExtractionSupport.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/WebPageCollectionExecutor.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PageContentExtractionSupportStructuredBlockTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`

- [ ] **Step 1: 用正式对象替代“只返回正文字符串”**

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class PageContentExtractionResult {
    boolean success;
    String title;
    String mainContent;
    String failureKind;
    String errorMessage;
    List<String> qualitySignals;
    Double qualityScore;
    List<StructuredContentBlock> structuredBlocks;
    Instant collectedAt;
    Long durationMillis;

    public boolean isUsable() {
        return success
                && mainContent != null
                && !mainContent.isBlank()
                && qualityScore != null
                && qualityScore >= 0.45D;
    }

    public static PageContentExtractionResult failure(String failureKind,
                                                      String errorMessage,
                                                      Instant startedAt,
                                                      List<String> qualitySignals) {
        return PageContentExtractionResult.builder()
                .success(false)
                .failureKind(failureKind)
                .errorMessage(errorMessage)
                .qualitySignals(qualitySignals)
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(java.time.Duration.between(startedAt, Instant.now()).toMillis())
                .build();
    }
}
```

- [ ] **Step 2: 把 `PageContentExtractionSupport` 升级成分层提取器**

```java
public static PageContentExtractionResult extract(Page page, String sourceType) {
    Instant startedAt = Instant.now();
    String html = page.content();
    String bodyText = extractMainContent(page);
    List<StructuredContentBlock> structuredBlocks = new ArrayList<>();
    structuredBlocks.addAll(extractPricingBlocks(html, sourceType));
    structuredBlocks.addAll(extractDocumentationOutline(html, sourceType));
    structuredBlocks.addAll(extractFaqBlocks(html));
    structuredBlocks.addAll(extractJsonLdMetadata(html));

    List<String> qualitySignals = resolveQualitySignals(bodyText, structuredBlocks);
    double qualityScore = computeQualityScore(bodyText, structuredBlocks, qualitySignals);
    if ((bodyText == null || bodyText.isBlank()) && structuredBlocks.isEmpty()) {
        return PageContentExtractionResult.failure(
                CollectionFailureKind.EXTRACTION_EMPTY.name(),
                "page extraction returned empty body and empty structured blocks",
                startedAt,
                List.of("NO_MAIN_CONTENT", "NO_STRUCTURED_BLOCKS")
        );
    }
    return PageContentExtractionResult.builder()
            .success(true)
            .title(page.title())
            .mainContent(bodyText)
            .qualitySignals(qualitySignals)
            .qualityScore(qualityScore)
            .structuredBlocks(structuredBlocks)
            .collectedAt(Instant.now())
            .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
            .build();
}
```

- [ ] **Step 3: 至少落三类最小结构块抽取器**

```java
private static List<StructuredContentBlock> extractPricingBlocks(String html, String sourceType) {
    if (!"PRICING".equalsIgnoreCase(sourceType) && !"OFFICIAL".equalsIgnoreCase(sourceType)) {
        return List.of();
    }
    if (html == null || !(html.contains("pricing") || html.contains("价格") || html.contains("¥"))) {
        return List.of();
    }
    return List.of(StructuredContentBlock.builder()
            .blockType("PRICING_BLOCK")
            .title("pricing")
            .content("命中定价相关结构块")
            .qualitySignal("PRICING_BLOCK_HIT")
            .build());
}

private static List<StructuredContentBlock> extractDocumentationOutline(String html, String sourceType) {
    if (!"DOCS".equalsIgnoreCase(sourceType)) {
        return List.of();
    }
    if (html == null || !(html.contains("docs-outline") || html.contains("目录") || html.contains("API 参考"))) {
        return List.of();
    }
    return List.of(StructuredContentBlock.builder()
            .blockType("DOCUMENTATION_OUTLINE")
            .title("docs-outline")
            .content("命中文档目录结构")
            .qualitySignal("DOCUMENTATION_OUTLINE_HIT")
            .build());
}

private static List<StructuredContentBlock> extractJsonLdMetadata(String html) {
    if (html == null || !html.contains("application/ld+json")) {
        return List.of();
    }
    return List.of(StructuredContentBlock.builder()
            .blockType("JSON_LD_METADATA")
            .title("json-ld")
            .content("命中 JSON-LD 元数据")
            .qualitySignal("JSON_LD_METADATA_HIT")
            .build());
}
```

- [ ] **Step 4: 在 `PlaywrightPageCollector` 与 `WebPageCollectionExecutor` 里统一消费提取结果**

```java
PageContentExtractionResult extractionResult = PageContentExtractionSupport.extract(page, request.getSourceType());
if (!extractionResult.isUsable()) {
    return failed(request.getUrl(), request.getCompetitorName(), request.getSourceType(),
            extractionResult.getErrorMessage() == null ? "Playwright 页面内容不可用" : extractionResult.getErrorMessage());
}
```

```java
return CollectionExecutionResult.builder()
        .executorType(executorType())
        .success(true)
        .resourceLocator(taskPackage.getResourceLocator())
        .title(extractionResult.getTitle())
        .content(extractionResult.getMainContent())
        .sourceUrls(taskPackage.getSourceUrls())
        .qualitySignals(extractionResult.getQualitySignals())
        .qualityScore(extractionResult.getQualityScore())
        .structuredBlocks(extractionResult.getStructuredBlocks())
        .collectedAt(extractionResult.getCollectedAt())
        .durationMillis(extractionResult.getDurationMillis())
        .build();
```

- [ ] **Step 5: 运行正文提取与结构块测试**

Run:
`mvn -pl backend "-Dtest=PageContentExtractionSupportStructuredBlockTest,PlaywrightPageCollectorTest,WebPageCollectionExecutorRouteTest" test`

Expected:
- PASS

---

### Task 6: `CollectorAgent` 兼容映射、文档回链与第五轮整体验证

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

- [ ] **Step 1: 让 `CollectorAgent` 兼容消费新的 `CollectionExecutionResult` 字段**

```java
private SourceCollector.CollectedPage mapCollectionResultToCollectedPage(CollectionExecutionResult result,
                                                                         String competitorName,
                                                                         String sourceType) {
    String effectiveUrl = result.getSourceUrls() != null && !result.getSourceUrls().isEmpty()
            ? result.getSourceUrls().get(0)
            : result.getResourceLocator();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("qualitySignals", result.getQualitySignals());
    metadata.put("qualityScore", result.getQualityScore());
    metadata.put("failureKind", result.getFailureKind());
    metadata.put("structuredBlocks", result.getStructuredBlocks());
    metadata.put("durationMillis", result.getDurationMillis());
    String serializedMetadata;
    try {
        serializedMetadata = objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
        serializedMetadata = null;
    }
    return SourceCollector.CollectedPage.builder()
            .url(effectiveUrl)
            .title(result.getTitle())
            .content(result.getContent())
            .snippet(result.getContent() == null ? null : result.getContent().substring(0, Math.min(500, result.getContent().length())))
            .metadata(serializedMetadata)
            .competitorName(competitorName)
            .sourceType(sourceType)
            .success(result.isSuccess())
            .errorMessage(result.getErrorMessage())
            .build();
}
```

Implementation note:
- 本轮不要求 `CollectorAgent` 立刻正式输出 `collectionAudit`
- 但兼容映射阶段绝不能把 `qualitySignals / qualityScore / structuredBlocks / failureKind` 静默丢掉

- [ ] **Step 2: 回写父计划 `Wave 8` 状态口径**

Update `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md` with wording like:

```md
第五轮实施计划已承接 `Wave 8`，明确把网页采集从 `Playwright` 单路径升级为
`JinaReader` 主路径 + `Playwright FULL_RENDER` 兜底路径。
本轮不再把浏览器反爬 patch 当成全部答案，而是把 `renderHint`、结构块、质量信号、
失败模式与 `CollectorAgent` 兼容映射统一收口到正式网页采集契约。
```

- [ ] **Step 3: 回写总设计看板状态**

Update `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`:

```md
- 实施：`🟡` 第四轮 `Wave 6 / Wave 7` 最小采集接缝完成后，第五轮已进入 `Wave 8`
  双路径网页采集实施计划；浏览器故障分类与反爬治理已形成专项输入，
  但 `JinaReader` 主路径、`FULL_RENDER` 正式兜底与网页结构块契约仍待完成后才能继续向绿灯推进。
```

- [ ] **Step 4: 运行第五轮聚合验证**

Run:
`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest,PlaywrightPageReadinessContractTest,PageContentExtractionSupportStructuredBlockTest,PlaywrightPageCollectorTest" test`

Expected:
- PASS

Run:
`mvn -pl backend test`

Expected:
- PASS

- [ ] **Step 5: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，最推荐下一步进入 `Wave 9`：
1. `collectionAudit` 正式化
2. `collectionReplayTimeline`
3. `collectionCheckpoint`
4. 包级 rerun / resume 语义
5. runtime / insight / replay / event 对齐
```

---

## Verification

- 网页采集契约扩展：`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest" test`
- `JinaReader` 主路径：`mvn -pl backend "-Dtest=JinaReaderClientTest,WebPageCollectionExecutorRouteTest" test`
- `Playwright FULL_RENDER` 兜底：`mvn -pl backend "-Dtest=PlaywrightPageCollectorTest,PlaywrightPageReadinessContractTest" test`
- 分层提取与结构块：`mvn -pl backend "-Dtest=PageContentExtractionSupportStructuredBlockTest,WebPageCollectionExecutorRouteTest" test`
- 第五轮整体：`mvn -pl backend "-Dtest=SearchPropertiesBindingTest,CollectionTaskPackageBuilderTest,CollectionExecutorRegistryTest,WebPageCollectionExecutorRouteTest,JinaReaderClientTest,PlaywrightPageReadinessContractTest,PageContentExtractionSupportStructuredBlockTest,PlaywrightPageCollectorTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `Wave 8` 的双路径网页采集主目标由 Task 2、Task 3、Task 4 覆盖。
2. `renderHint / failureKind / structuredBlocks / qualitySignals / qualityScore` 的正式契约由 Task 1、Task 2、Task 5 覆盖。
3. `JinaReader` 主路径由 Task 1、Task 3 覆盖。
4. `Playwright` 退回 `FULL_RENDER` 兜底以及页面就绪模型由 Task 4 覆盖。
5. `CollectorAgent` 兼容映射与父方案/总设计回链由 Task 6 覆盖。

### Placeholder scan

1. 本计划没有使用 `TODO / TBD / implement later`。
2. 每个任务都给出明确文件、命令和预期结果。
3. 本轮没有把 `collectionAudit / replay / checkpoint` 误提前塞回 `Wave 8`。

### Type consistency

1. `WebPageRenderHint` 始终表达网页执行提示，而不是 provider role。
2. `CollectionFailureKind` 始终表达采集失败语义，而不是浏览器内部异常文本。
3. `SourceCollectRequest` 始终承担 `Wave 8` 正式网页请求入口。
4. `PageContentExtractionResult` 始终承担正文、结构块与质量信号的分层提取结果。
5. `sourceUrls` 在 `CollectionTaskPackage`、`CollectionExecutionResult`、`CollectorAgent` 兼容映射中均保持显式存在。
