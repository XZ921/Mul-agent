# 03 Task 66 Tavily 结构化块与 Fast-Lane 审计实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 让 `TavilyPrefetchedExecutor` 产出可解释的结构化块，并补齐 `prefetchedContentRef -> TAVILY_PREFETCHED -> collection result -> evidence_source metadata` 的可追踪消费审计。

**架构：** 本阶段只做两件事：一是新增轻量 `TavilyPrefetchedContentBlockClassifier`，在正文段落通过防噪门槛时生成 `StructuredContentBlock`；二是让 `TavilyPrefetchedExecutor` 把结构化块数量、预抓正文引用和消费阶段写入 `structuredPayload / qualitySignals`。`02` 已经落地 `EvidenceQualityGate` 和 `evidenceQualityVerdict`，因此 `03` 不再重复实现“导航壳 / 鉴权墙 / 根入口弱正文”的最终门禁，也不允许用结构化块数量直接抬高最终报告级可用性分。

**技术栈：** Java 17、现有 Tavily registry/executor、StructuredContentBlock、JUnit 5、AssertJ、Maven。

---

## 与 02 阶段对齐说明

- `02` 已经把 `EvidenceQualityGate` 挂到 `CollectorAgent` 的统一收口路径，并新增了 `CollectionExecutionResult.evidenceQualityVerdict`。
- 因此 `03` 的 `TavilyPrefetchedExecutor` 只负责输出：
  - `structuredBlocks`
  - `TAVILY_STRUCTURED_BLOCK_COUNT`
  - `structuredPayload.prefetchedContentRef / primaryTool / failureStage`
- `03` 不能自己重新定义最终 `qualityScore` 语义。Tavily executor 仍可保留 Tavily 原始候选分或默认分，但最终“是否可用于报告”的封顶与负信号解释，仍由 `02` 的 `EvidenceQualityGate` 收口。
- `02` 运行结果已经证明：当前 Collector/Tavily fast-lane 路径还没有稳定拿到字段级 `fieldName / evidencePathKey / expectedSignals` 上下文，只拿到了节点级 `requiredCoverageFields / blockingCoverageFields / coverageQueryIntents` 轻量视图。
- 因此原计划中“`FieldEvidenceQueryPlanner` 把字段证据路径翻译成 Tavily query”的部分，当前阶段会变成脱离主链路的前置设计，不能作为 `03` 的交付物。该能力应后移到 `04` 或后续 field-first recovery 阶段，再与 `RecoveryContext`、`PublicEvidenceRecoveryService` 一起实现。

---

## 文件结构

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifier.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifierTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyFastLaneAuditContractTest.java`

---

### Task 1: Tavily 结构化块分类器

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifier.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedContentBlockClassifierTest.java`

**规则：**

`TavilyPrefetchedContentBlockClassifier` 是轻量规则分类器，不承担完整页面理解。它必须优先防误判：当定价、配额或调用次数使用隐含表达时，可以漏判为 `NO_STRUCTURED_BLOCKS`，后续由 `04/05` 的字段证据路径和 repair 承接；不能为了提高召回，把导航壳、登录注册入口或泛化配额文字误标为 `PRICING_BLOCK`。

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
                主站 开放平台 文档中心 管理中心 账号管理 应用管理 授权管理
                登录 注册 立即加入 帮助中心 联系我们 友情链接
                """);

        assertThat(blocks).isEmpty();
    }

    @Test
    void shouldPreferRepairOverPricingBlockForImplicitQuotaWording() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                开发者权益说明
                开放平台为开发者提供接口调用能力。开发者每天享有 10000 次免费配额，
                超出后的使用安排以平台后续通知和控制台展示为准。
                """);

        assertThat(blocks).extracting(StructuredContentBlock::getBlockType)
                .doesNotContain("PRICING_BLOCK");
    }
}
```

- [ ] **步骤 2：运行测试并确认分类器缺失**

运行：

```powershell
mvn -pl backend "-Dtest=TavilyPrefetchedContentBlockClassifierTest" test
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

注意：不要把“免费配额、调用次数、额度”等词直接加入 `PRICING_BLOCK` 第一版关键词列表。它们可能表达定价、限流、开发者权益或普通功能说明，误判风险高；该缺口留给后续 repair 处理。

- [ ] **步骤 4：运行分类器测试**

运行：

```powershell
mvn -pl backend "-Dtest=TavilyPrefetchedContentBlockClassifierTest" test
```

预期：测试通过，并且隐含配额表达不会被误标为 `PRICING_BLOCK`。

### Task 2: 在 TavilyPrefetchedExecutor 中产出结构化块

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutorTest.java`

**规则：**

`TavilyPrefetchedExecutor` 的职责是消费 registry 中的已缓存正文，并把正文、结构化块和消费审计元数据落到统一 `CollectionExecutionResult`。它不负责最终报告级质量封顶；`02` 的 `EvidenceQualityGate` 会在 Collector 收口时继续处理。

- [ ] **步骤 1：增加 executor 结构化块测试**

追加以下测试到 `TavilyPrefetchedExecutorTest.java`：

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
    assertThat(result.getQualitySignals()).contains("TAVILY_STRUCTURED_BLOCK_COUNT=1");
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

- [ ] **步骤 3：在成功结果中使用分类器与审计信号**

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
.qualityScore(resolveQualityScore(content))
.structuredBlocks(structuredBlocks)
```

要求：

- 不要在这里根据 `structuredBlocks.size()` 直接提高 `qualityScore`
- 不要在这里重复写 `AUTH_GATE_DETECTED / ROOT_ENTRY_WEAK_CONTENT` 这类最终门禁信号
- 这些最终解释应继续由 `CollectorAgent -> EvidenceQualityGate` 负责

- [ ] **步骤 4：运行 executor 测试**

运行：

```powershell
mvn -pl backend "-Dtest=TavilyPrefetchedExecutorTest,TavilyPrefetchedContentBlockClassifierTest" test
```

预期：测试通过。

### Task 3: Fast-Lane 消费审计契约

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/TavilyPrefetchedExecutor.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/TavilyFastLaneAuditContractTest.java`

**规则：**

本阶段的 fast-lane 审计关注“预抓正文是否被消费，以及消费后是否能沿 `structuredPayload / metadata` 继续追溯”，不是重新实现搜索阶段的 `TavilyFastLaneAudit` 汇总对象。

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

    @Test
    void shouldExposeFailureStageWhenPrefetchedContentMissing() {
        CollectionExecutionResult result = new TavilyPrefetchedExecutor(
                new TavilyPrefetchedContentRegistry(),
                new TavilyPrefetchedContentBlockClassifier())
                .execute(CollectionTaskPackage.builder()
                        .packageKey("collect#002")
                        .targetIndex(2)
                        .primaryTool("TAVILY_PREFETCHED")
                        .resourceLocator("https://open.example.com/docs/api")
                        .prefetchedContentRef("missing-ref")
                        .sourceUrls(List.of("https://open.example.com/docs/api"))
                        .build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStructuredPayload()).containsEntry("failureStage", "TAVILY_PREFETCHED_CONSUME");
        assertThat(result.getStructuredPayload()).containsEntry("primaryTool", "TAVILY_PREFETCHED");
    }
}
```

- [ ] **步骤 2：在成功结果中增加结构化 payload**

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

- [ ] **步骤 3：在失败结果中增加失败审计 payload**

在 `buildFailureResult` 中设置：

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
mvn -pl backend "-Dtest=TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest" test
```

预期：测试通过。

---

## 本阶段暂不做

- 暂不在 `03` 引入 `FieldEvidenceQueryPlanner / FieldEvidenceQueryPlan`
- 暂不把 `fieldName / evidencePathKey / expectedSignals` 直接下发到 Tavily fast-lane
- 暂不让 `TavilyPrefetchedExecutor` 自己做最终证据质量封顶
- 暂不把 Tavily 结构化块扩展成复杂页面理解器；第一版只做轻量规则分类

---

## 03 完成标准（修订后）

- `TavilyPrefetchedExecutor` 不再固定输出 `structuredBlocks=[]`
- 结构化块分类具备防噪规则，导航壳与隐含配额表达不会被轻易误标
- 成功消费的预抓正文会输出：
  - `TAVILY_RAW_CONTENT_READY`
  - `TAVILY_PREFETCHED_CONTENT_CONSUMED`
  - `TAVILY_STRUCTURED_BLOCK_COUNT=N`
- `structuredPayload` 能追踪：
  - `prefetchedContentRef`
  - `prefetchedRawContentLength`
  - `primaryTool`
  - `structuredBlockCount` 或 `failureStage`
- `03` 不覆盖 `02` 的质量门禁职责；最终 evidence usability 仍由 `EvidenceQualityGate` 决定
