# 03 Task 66 Tavily 结构化与查询翻译实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 让 Tavily 预抓正文产出可用结构化块，补齐 fast-lane 消费审计，并新增字段证据路径到 Tavily query 的显式翻译层，避免底层来源家族查询把系统拉回 sourceType-first。

**架构：** 新增 `FieldEvidenceQueryPlanner` 把 `fieldName / evidencePathKey / queryIntent / sourceTypes` 翻译成具体 Tavily 查询；`TavilySearchProfileResolver` 只作为来源家族和 query intent 的底层表达式工具。新增 `TavilyPrefetchedContentBlockClassifier`，由 `TavilyPrefetchedExecutor` 调用；分类器只有在段落级证据通过防噪门槛时才输出结构化块。fast-lane 审计元数据通过质量信号和结构化 payload 写入 `CollectionExecutionResult`。

**技术栈：** Java 17、现有 Tavily registry/executor 类、采集结构化块、JUnit 5、AssertJ。

---

## 文件结构

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlan.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlanner.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifier.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlannerTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifierTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyFastLaneAuditContractTest.java`

---

### Task 1: 字段证据路径到 Tavily Query 的翻译层

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlan.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlanner.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryPlannerTest.java`

**规则：**

Tavily 底层仍然按 `OFFICIAL / DOCS / PRICING / NEWS / REVIEW` 等来源家族工作，但上层必须由字段证据路径驱动。任何新链路不得直接把 `sourceType` 传给 Tavily 查询构造器后结束，必须先生成 `FieldEvidenceQueryPlan`。

- [ ] **步骤 1：编写 pricing 字段路径翻译测试**

创建 `FieldEvidenceQueryPlannerTest.java`：

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceQueryPlannerTest {

    private final FieldEvidenceQueryPlanner planner = new FieldEvidenceQueryPlanner();

    @Test
    void shouldTranslatePricingDocsPathIntoFieldAwareTavilyQueries() {
        List<FieldEvidenceQueryPlan> plans = planner.planQueries(
                "抖音开放平台",
                "pricing",
                "DOCS_BILLING_OR_LIMITS",
                List.of("DOCS_BILLING", "API_LIMITS"),
                List.of("DOCS", "OFFICIAL"),
                "zh-CN");

        assertThat(plans).anySatisfy(plan -> {
            assertThat(plan.getFieldName()).isEqualTo("pricing");
            assertThat(plan.getEvidencePathKey()).isEqualTo("DOCS_BILLING_OR_LIMITS");
            assertThat(plan.getQueryIntent()).isEqualTo("DOCS_BILLING");
            assertThat(plan.getSourceType()).isEqualTo("DOCS");
            assertThat(plan.getQuery()).contains("抖音开放平台", "计费");
            assertThat(plan.getReason()).contains("字段证据路径");
        });
    }
}
```

- [ ] **步骤 2：新增查询计划模型**

创建 `FieldEvidenceQueryPlan.java`：

```java
package cn.bugstack.competitoragent.search;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class FieldEvidenceQueryPlan {

    String fieldName;
    String evidencePathKey;
    String queryIntent;
    String sourceType;
    String query;
    String locale;
    String reason;
}
```

- [ ] **步骤 3：新增查询翻译器**

创建 `FieldEvidenceQueryPlanner.java`：

```java
package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FieldEvidenceQueryPlanner {

    public List<FieldEvidenceQueryPlan> planQueries(String competitorName,
                                                    String fieldName,
                                                    String evidencePathKey,
                                                    List<String> queryIntents,
                                                    List<String> sourceTypes,
                                                    String locale) {
        List<FieldEvidenceQueryPlan> plans = new ArrayList<>();
        for (String queryIntent : queryIntents == null ? List.<String>of() : queryIntents) {
            for (String sourceType : sourceTypes == null ? List.<String>of() : sourceTypes) {
                plans.add(FieldEvidenceQueryPlan.builder()
                        .fieldName(fieldName)
                        .evidencePathKey(evidencePathKey)
                        .queryIntent(queryIntent)
                        .sourceType(sourceType)
                        .locale(locale)
                        .query(buildQuery(competitorName, fieldName, evidencePathKey, queryIntent, sourceType))
                        .reason("由字段证据路径翻译为 Tavily 查询，避免直接按 sourceType 搜索")
                        .build());
            }
        }
        return plans;
    }

    private String buildQuery(String competitorName,
                              String fieldName,
                              String evidencePathKey,
                              String queryIntent,
                              String sourceType) {
        String base = competitorName == null ? "" : competitorName;
        if ("pricing".equalsIgnoreCase(fieldName) || contains(queryIntent, "BILLING")) {
            return base + " 计费 收费 定价 套餐 " + sourceType;
        }
        if (contains(queryIntent, "API") || contains(evidencePathKey, "DOCS")) {
            return base + " API SDK 开发文档 " + sourceType;
        }
        return base + " " + fieldName + " " + queryIntent + " " + sourceType;
    }

    private boolean contains(String value, String expected) {
        return value != null && expected != null && value.toUpperCase().contains(expected.toUpperCase());
    }
}
```

- [ ] **步骤 4：运行翻译层测试**

运行：

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerTest test
```

预期：测试通过，且查询计划中包含 `fieldName / evidencePathKey / queryIntent / sourceType / query / reason`。

### Task 2: Tavily 结构化块分类器

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifier.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifierTest.java`

- [ ] **步骤 1：编写正向和防噪测试**

创建 `TavilyPrefetchedContentBlockClassifierTest.java`：

```java
package cn.bugstack.competitoragent.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyPrefetchedContentBlockClassifierTest {

    private final TavilyPrefetchedContentBlockClassifier classifier =
            new TavilyPrefetchedContentBlockClassifier();

    @Test
    void shouldCreateDeveloperDocsBlockFromParagraphBody() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                用户管理 API
                开放平台提供用户授权、身份识别和用户资料读取能力。开发者可以通过 SDK 调用接口完成授权登录，
                并根据接口返回的 open_id 进行用户管理。
                """);

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.getBlockType()).isEqualTo("DEVELOPER_DOCS_BLOCK");
            assertThat(block.getQualitySignal()).contains("blockConfidence=");
            assertThat(block.getQualitySignal()).contains("paragraph-body");
        });
    }

    @Test
    void shouldNotCreateDeveloperBlockFromNavigationShell() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                主站 开放平台 开平文档中心 开平管理中心 账号管理 应用管理 授权管理
                登录 注册 立即加入 帮助中心 联系我们 友情链接
                """);

        assertThat(blocks).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试并确认分类器缺失**

运行：

```powershell
mvn -pl backend -Dtest=TavilyPrefetchedContentBlockClassifierTest test
```

预期：编译失败，因为分类器尚不存在。

- [ ] **步骤 3：实现分类器**

创建 `TavilyPrefetchedContentBlockClassifier.java`：

```java
package cn.bugstack.competitoragent.collection;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TavilyPrefetchedContentBlockClassifier {

    private static final int MIN_PARAGRAPH_LENGTH = 70;

    public List<StructuredContentBlock> classify(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<String> paragraphs = splitParagraphs(content);
        List<StructuredContentBlock> blocks = new ArrayList<>();
        addBlockIfMatched(blocks, paragraphs, "PRICING_BLOCK",
                List.of("价格", "定价", "套餐", "计费", "pricing", "plan", "billing"));
        addBlockIfMatched(blocks, paragraphs, "LIMITATION_OR_POLICY_BLOCK",
                List.of("限制", "风险", "审核", "规则", "协议", "条款", "limitation", "risk", "policy", "agreement"));
        addBlockIfMatched(blocks, paragraphs, "DEVELOPER_DOCS_BLOCK",
                List.of("api", "sdk", "接口", "开发文档", "guide", "reference", "open platform", "授权", "用户管理"));
        addBlockIfMatched(blocks, paragraphs, "FEATURE_BLOCK",
                List.of("功能", "能力", "场景", "产品介绍", "服务支持"));
        return blocks;
    }

    private void addBlockIfMatched(List<StructuredContentBlock> blocks,
                                   List<String> paragraphs,
                                   String blockType,
                                   List<String> keywords) {
        for (String paragraph : paragraphs) {
            if (!isUsefulParagraph(paragraph)) {
                continue;
            }
            int hits = countHits(paragraph, keywords);
            if (hits < 2) {
                continue;
            }
            blocks.add(StructuredContentBlock.builder()
                    .blockType(blockType)
                    .title(blockType)
                    .content(paragraph.trim())
                    .qualitySignal("TAVILY_BLOCK_CLASSIFIED:blockConfidence=0.72;blockEvidenceReason=paragraph-body keywordHits=" + hits)
                    .build());
            return;
        }
    }

    private List<String> splitParagraphs(String content) {
        return List.of(content.split("\\R+")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private boolean isUsefulParagraph(String paragraph) {
        if (paragraph == null || paragraph.length() < MIN_PARAGRAPH_LENGTH) {
            return false;
        }
        int navHits = countHits(paragraph, List.of("主站", "帮助中心", "友情链接", "登录", "注册", "联系我们", "立即加入"));
        return navHits < 3;
    }

    private int countHits(String paragraph, List<String> keywords) {
        String normalized = paragraph == null ? "" : paragraph.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }
}
```

- [ ] **步骤 4：运行分类器测试**

运行：

```powershell
mvn -pl backend -Dtest=TavilyPrefetchedContentBlockClassifierTest test
```

预期：测试通过。

### Task 3: 在 TavilyPrefetchedExecutor 中使用分类器

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`

- [ ] **步骤 1：增加 executor 结构化块测试**

追加以下测试到 `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`：

```java
@Test
void shouldEmitStructuredBlocksForDeveloperDocsRawContent() {
    TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
    String ref = registry.register(TavilyPrefetchedContent.builder()
            .url("https://open.bilibili.com/doc/4/feb66f99")
            .title("用户管理 API")
            .rawContent("""
                    用户管理 API
                    开放平台提供用户授权、身份识别和用户资料读取能力。开发者可以通过 SDK 调用接口完成授权登录，
                    并根据接口返回的 open_id 进行用户管理。
                    """)
            .tavilyScore(0.91D)
            .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
            .requestId("prefetch-doc")
            .resultRank(1)
            .build());
    TavilyPrefetchedExecutor executor = new TavilyPrefetchedExecutor(
            registry,
            new TavilyPrefetchedContentBlockClassifier());

    CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
            .packageKey("collect#001")
            .targetIndex(1)
            .primaryTool("TAVILY_PREFETCHED")
            .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
            .prefetchedContentRef(ref)
            .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
            .build());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getExecutorType()).isEqualTo("TAVILY_PREFETCHED");
    assertThat(result.getQualitySignals()).contains("TAVILY_RAW_CONTENT_READY", "TAVILY_PREFETCHED_CONTENT_CONSUMED");
    assertThat(result.getStructuredBlocks()).extracting(StructuredContentBlock::getBlockType)
            .contains("DEVELOPER_DOCS_BLOCK");
}
```

- [ ] **步骤 2：给 executor 增加分类器依赖**

修改 `TavilyPrefetchedExecutor`：

```java
private final TavilyPrefetchedContentBlockClassifier blockClassifier;

public TavilyPrefetchedExecutor(TavilyPrefetchedContentRegistry registry) {
    this(registry, new TavilyPrefetchedContentBlockClassifier());
}

public TavilyPrefetchedExecutor(TavilyPrefetchedContentRegistry registry,
                                TavilyPrefetchedContentBlockClassifier blockClassifier) {
    this.registry = registry == null ? new TavilyPrefetchedContentRegistry() : registry;
    this.blockClassifier = blockClassifier == null ? new TavilyPrefetchedContentBlockClassifier() : blockClassifier;
}
```

- [ ] **步骤 3：在成功结果中使用分类器**

在构造成功结果前新增：

```java
List<StructuredContentBlock> structuredBlocks = blockClassifier.classify(cleanedContent);
List<String> qualitySignals = new ArrayList<>(List.of(
        "TAVILY_RAW_CONTENT_READY",
        "TAVILY_PREFETCHED_CONTENT_CONSUMED"
));
qualitySignals.add("TAVILY_STRUCTURED_BLOCK_COUNT=" + structuredBlocks.size());
```

然后设置：

```java
.qualitySignals(qualitySignals)
.structuredBlocks(structuredBlocks)
```

- [ ] **步骤 4：运行 executor 测试**

运行：

```powershell
mvn -pl backend -Dtest=TavilyPrefetchedExecutorTest,TavilyPrefetchedContentBlockClassifierTest test
```

预期：测试通过。

### Task 4: Fast-Lane 审计契约

**文件：**
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyFastLaneAuditContractTest.java`

- [ ] **步骤 1：编写审计契约测试**

创建 `TavilyFastLaneAuditContractTest.java`：

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyFastLaneAuditContractTest {

    @Test
    void shouldExposePrefetchedAuditMetadataInStructuredPayload() {
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        String ref = registry.register(TavilyPrefetchedContent.builder()
                .url("https://open.example.com/docs/api")
                .rawContent("API 文档说明。开放平台提供 SDK 和 API 能力，开发者可以调用接口完成授权管理。")
                .tavilyScore(0.88D)
                .sourceUrls(List.of("https://open.example.com/docs/api"))
                .requestId("prefetch-audit")
                .resultRank(1)
                .build());

        CollectionExecutionResult result = new TavilyPrefetchedExecutor(
                registry,
                new TavilyPrefetchedContentBlockClassifier())
                .execute(CollectionTaskPackage.builder()
                        .packageKey("collect#001")
                        .targetIndex(1)
                        .primaryTool("TAVILY_PREFETCHED")
                        .resourceLocator("https://open.example.com/docs/api")
                        .prefetchedContentRef(ref)
                        .prefetchedRawContentLength(42)
                        .sourceUrls(List.of("https://open.example.com/docs/api"))
                        .build());

        assertThat(result.getStructuredPayload()).containsEntry("prefetchedContentRef", ref);
        assertThat(result.getStructuredPayload()).containsEntry("primaryTool", "TAVILY_PREFETCHED");
        assertThat(result.getStructuredPayload()).containsKey("structuredBlockCount");
    }
}
```

- [ ] **步骤 2：在 executor 中增加结构化 payload**

在成功结果 builder 中增加：

```java
.structuredPayload(Map.of(
        "prefetchedContentRef", taskPackage.getPrefetchedContentRef(),
        "prefetchedRawContentLength", taskPackage.getPrefetchedRawContentLength(),
        "primaryTool", taskPackage.getPrimaryTool(),
        "structuredBlockCount", structuredBlocks.size()
))
```

引入 `java.util.Map`。

- [ ] **步骤 3：增加失败审计 metadata**

在 `buildFailureResult` 中设置 `structuredPayload`：

```java
.structuredPayload(taskPackage == null ? Map.of() : Map.of(
        "prefetchedContentRef", taskPackage.getPrefetchedContentRef(),
        "primaryTool", taskPackage.getPrimaryTool(),
        "failureStage", "TAVILY_PREFETCHED_CONSUME"
))
```

- [ ] **步骤 4：运行本阶段测试**

运行：

```powershell
mvn -pl backend -Dtest=TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest test
```

预期：测试通过。
