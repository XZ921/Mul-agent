# 01 Task 66 覆盖契约实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 新增唯一权威的 `CoverageContract`，并让规划、采集节点配置、Extractor 和 Reviewer 消费同一份契约。

**架构：** 第一版将契约存放在 `WorkflowPlan.coverageContract`，并通过 `TaskPlan.planSnapshot` 持久化。节点配置只携带 `coverageContractRef` 和裁剪后的字段视图。Agent 统一通过 `CoverageContractProvider` 读取契约，不允许各自解析契约 JSON。字段状态推导不得长期依赖零散关键词判断，需要通过结构化的 `AnalysisDimensionMappingCatalog` 管理维度、字段、证据路径和来源范围之间的映射关系。

**技术栈：** Java 17 类/record、Jackson、Spring Component、通过 `TaskPlanRepository` 读取 JPA Repository、JUnit 5、Mockito、AssertJ。

---

## 文件结构

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContract.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageFieldContract.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageEvidencePath.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageFieldStatus.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageBlockingLevel.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMapping.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractProvider.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/task/definition/ExecutionPlanDefinition.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlan.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanAssembler.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolverTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalogTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractProviderTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanCoverageContractTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentCoverageContractTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentCoverageContractTest.java`

---

### Task 1: 覆盖契约模型

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContract.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageFieldContract.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageEvidencePath.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageFieldStatus.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageBlockingLevel.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolverTest.java`

- [ ] **步骤 1：编写模型序列化测试**

创建 `CoverageContractResolverTest.java`，先加入以下测试：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoverageContractResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeCoverageContractWithOverrideReason() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("task-66-plan-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .targetEvidenceTypes(List.of("PRICING_BLOCK"))
                        .queryIntents(List.of())
                        .minDistinctEvidenceCount(0)
                        .allowOfficialOnly(true)
                        .overrideReason("taskMode=CAPABILITY_INTRO")
                        .build()))
                .build();

        String json = objectMapper.writeValueAsString(contract);
        CoverageContract restored = objectMapper.readValue(json, CoverageContract.class);

        assertThat(restored.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
        assertThat(restored.findField("pricing")).isPresent();
        assertThat(restored.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(restored.findField("pricing").orElseThrow().getOverrideReason())
                .isEqualTo("taskMode=CAPABILITY_INTRO");
    }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest test
```

预期：编译失败，因为 coverage 相关类尚不存在。

- [ ] **步骤 3：新增枚举类**

创建 `CoverageFieldStatus.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

public enum CoverageFieldStatus {
    REQUIRED,
    OPTIONAL,
    OUT_OF_SCOPE,
    NOT_APPLICABLE,
    EVIDENCE_NOT_COVERING,
    REPAIR_ONLY
}
```

创建 `CoverageBlockingLevel.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

public enum CoverageBlockingLevel {
    BLOCKER,
    WARNING,
    NONE
}
```

- [ ] **步骤 4：新增字段契约模型**

创建 `CoverageEvidencePath.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageEvidencePath {

    private String pathKey;

    @Builder.Default
    private List<String> sourceTypes = new ArrayList<>();

    @Builder.Default
    private List<String> queryIntents = new ArrayList<>();

    @Builder.Default
    private List<String> expectedSignals = new ArrayList<>();

    @Builder.Default
    private boolean required = false;

    private String successCriteria;
    private String failureStatus;
}
```

创建 `CoverageFieldContract.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageFieldContract {

    private String field;
    private CoverageFieldStatus status;
    private CoverageBlockingLevel blockingLevel;

    @Builder.Default
    private List<String> targetEvidenceTypes = new ArrayList<>();

    @Builder.Default
    private List<String> queryIntents = new ArrayList<>();

    @Builder.Default
    private List<CoverageEvidencePath> evidencePaths = new ArrayList<>();

    @Builder.Default
    private int minimumAttemptedPaths = 0;

    @Builder.Default
    private int minDistinctEvidenceCount = 0;

    @Builder.Default
    private boolean allowOfficialOnly = true;

    private String overrideReason;
}
```

- [ ] **步骤 5：新增契约模型**

创建 `CoverageContract.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageContract {

    private String taskMode;
    private String contractVersion;
    private String source;

    @Builder.Default
    private List<CoverageFieldContract> fields = new ArrayList<>();

    public Optional<CoverageFieldContract> findField(String field) {
        if (field == null || fields == null) {
            return Optional.empty();
        }
        return fields.stream()
                .filter(item -> item != null && field.equalsIgnoreCase(item.getField()))
                .findFirst();
    }
}
```

- [ ] **步骤 6：运行模型测试**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest test
```

预期：测试通过。

### Task 2: 分析维度映射目录

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMapping.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalog.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/AnalysisDimensionMappingCatalogTest.java`

**规则：**

`CoverageContractResolver` 不应长期散落使用 `containsAny(analysisDimensions, List.of(...))` 判断字段状态。短期可以保留关键词匹配作为目录实现细节，但关键词必须集中在结构化目录中，并输出“命中的维度类型、目标字段、证据路径、来源类型、优先级、原因”。这样后续可以从关键词迁移到枚举、配置文件或模型分类，而不需要改 Resolver 主流程。

- [ ] **步骤 1：编写映射目录测试**

创建 `AnalysisDimensionMappingCatalogTest.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDimensionMappingCatalogTest {

    private final AnalysisDimensionMappingCatalog catalog = new AnalysisDimensionMappingCatalog();

    @Test
    void shouldMapPricingDimensionToPricingFieldAndEvidencePaths() {
        List<AnalysisDimensionMapping> mappings = catalog.resolve(
                List.of("定价策略", "商业化模式"),
                List.of("官网", "产品文档"));

        assertThat(mappings).anySatisfy(mapping -> {
            assertThat(mapping.getDimensionKey()).isEqualTo("PRICING_ANALYSIS");
            assertThat(mapping.getTargetFields()).contains("pricing");
            assertThat(mapping.getEvidencePathKeys()).contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
            assertThat(mapping.getSourceTypes()).contains("PRICING", "DOCS", "OFFICIAL");
            assertThat(mapping.getReason()).contains("显式分析维度");
        });
    }

    @Test
    void shouldNotRequireWeaknessWhenOnlyOfficialScopeAndCapabilityIntroDimensions() {
        List<AnalysisDimensionMapping> mappings = catalog.resolve(
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"));

        assertThat(mappings)
                .noneMatch(mapping -> mapping.getTargetFields().contains("weaknesses")
                        && mapping.isRequiredByDefault());
    }
}
```

- [ ] **步骤 2：运行测试并确认类不存在**

运行：

```powershell
mvn -pl backend -Dtest=AnalysisDimensionMappingCatalogTest test
```

预期：编译失败，因为 `AnalysisDimensionMapping` 和 `AnalysisDimensionMappingCatalog` 尚不存在。

- [ ] **步骤 3：新增映射模型**

创建 `AnalysisDimensionMapping.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class AnalysisDimensionMapping {

    String dimensionKey;
    List<String> matchedTerms;
    List<String> targetFields;
    List<String> evidencePathKeys;
    List<String> sourceTypes;
    List<String> queryIntents;
    boolean requiredByDefault;
    int priority;
    String reason;
}
```

- [ ] **步骤 4：新增映射目录**

创建 `AnalysisDimensionMappingCatalog.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class AnalysisDimensionMappingCatalog {

    public List<AnalysisDimensionMapping> resolve(List<String> analysisDimensions,
                                                  List<String> sourceScope) {
        List<AnalysisDimensionMapping> mappings = new ArrayList<>();
        if (matches(analysisDimensions, "定价", "价格", "套餐", "计费", "商业化")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("PRICING_ANALYSIS")
                    .matchedTerms(List.of("定价", "价格", "套餐", "计费", "商业化"))
                    .targetFields(List.of("pricing"))
                    .evidencePathKeys(List.of("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS", "TERMS_OR_SERVICE_AGREEMENT"))
                    .sourceTypes(List.of("PRICING", "DOCS", "OFFICIAL"))
                    .queryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING", "TERMS_BILLING"))
                    .requiredByDefault(true)
                    .priority(100)
                    .reason("显式分析维度要求定价字段")
                    .build());
        }
        if (matches(analysisDimensions, "风险", "短板", "劣势", "限制", "合规", "协议", "审核", "规则")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("WEAKNESS_ANALYSIS")
                    .matchedTerms(List.of("风险", "短板", "劣势", "限制", "合规", "协议", "审核", "规则"))
                    .targetFields(List.of("weaknesses"))
                    .evidencePathKeys(List.of("TERMS_OR_SERVICE_AGREEMENT", "POLICY_LIMITATION", "PUBLIC_REVIEW_OR_NEWS"))
                    .sourceTypes(List.of("TERMS", "POLICY", "REVIEW", "NEWS"))
                    .queryIntents(List.of("POLICY", "RISK", "THIRD_PARTY_REVIEW"))
                    .requiredByDefault(true)
                    .priority(90)
                    .reason("显式分析维度要求风险或短板字段")
                    .build());
        }
        if (matches(analysisDimensions, "产品功能", "开放平台", "开发者生态", "API", "SDK", "文档", "能力")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("CAPABILITY_INTRO")
                    .matchedTerms(List.of("产品功能", "开放平台", "开发者生态", "API", "SDK", "文档", "能力"))
                    .targetFields(List.of("summary", "positioning", "targetUsers", "coreFeatures"))
                    .evidencePathKeys(List.of("OFFICIAL_PUBLIC_PROFILE", "DOCS_API_GUIDE"))
                    .sourceTypes(List.of("OFFICIAL", "DOCS"))
                    .queryIntents(List.of("OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE"))
                    .requiredByDefault(true)
                    .priority(80)
                    .reason("能力介绍维度要求产品概述和核心功能字段")
                    .build());
        }
        return mappings;
    }

    private boolean matches(List<String> values, String... terms) {
        if (values == null || terms == null) {
            return false;
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

- [ ] **步骤 5：运行映射目录测试**

运行：

```powershell
mvn -pl backend -Dtest=AnalysisDimensionMappingCatalogTest test
```

预期：测试通过。

### Task 3: 覆盖契约解析器

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolver.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractResolverTest.java`

- [ ] **步骤 1：新增任务模式与优先级测试**

追加以下测试到 `CoverageContractResolverTest.java`：

```java
@Test
void shouldBuildCapabilityIntroContractForTask66Dimensions() {
    CoverageContractResolver resolver = new CoverageContractResolver();

    CoverageContract contract = resolver.resolve(
            null,
            List.of("开放平台", "开发者生态", "产品功能"),
            List.of("官网", "产品文档"),
            null);

    assertThat(contract.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
    assertThat(contract.findField("coreFeatures").orElseThrow().getStatus())
            .isEqualTo(CoverageFieldStatus.REQUIRED);
    assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
            .isEqualTo(CoverageBlockingLevel.NONE);
    assertThat(contract.findField("weaknesses").orElseThrow().getStatus())
            .isIn(CoverageFieldStatus.OUT_OF_SCOPE, CoverageFieldStatus.OPTIONAL);
}

@Test
void explicitPricingDimensionShouldOverrideOfficialOnlyScope() {
    CoverageContractResolver resolver = new CoverageContractResolver();

    CoverageContract contract = resolver.resolve(
            null,
            List.of("定价策略"),
            List.of("官网"),
            null);

    CoverageFieldContract pricing = contract.findField("pricing").orElseThrow();
    assertThat(pricing.getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
    assertThat(pricing.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.BLOCKER);
    assertThat(pricing.getQueryIntents()).contains("OFFICIAL_PRICING");
    assertThat(pricing.getMinimumAttemptedPaths()).isGreaterThanOrEqualTo(2);
    assertThat(pricing.getEvidencePaths()).extracting(CoverageEvidencePath::getPathKey)
            .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
    assertThat(pricing.getOverrideReason()).contains("显式维度");
}

@Test
void explicitStandardTemplateShouldRequireWeaknesses() {
    CoverageContractResolver resolver = new CoverageContractResolver();

    CoverageContract contract = resolver.resolve(
            "STANDARD_COMPETITOR_REPORT",
            List.of("开放平台"),
            List.of("官网", "产品文档"),
            null);

    CoverageFieldContract weaknesses = contract.findField("weaknesses").orElseThrow();
    assertThat(weaknesses.getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
    assertThat(weaknesses.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.BLOCKER);
    assertThat(weaknesses.getOverrideReason()).contains("显式报告模板");
}

@Test
void resolverShouldUseStructuredDimensionCatalogInsteadOfInlineKeywordLists() {
    AnalysisDimensionMappingCatalog catalog = mock(AnalysisDimensionMappingCatalog.class);
    when(catalog.resolve(List.of("收费模式"), List.of("官网")))
            .thenReturn(List.of(AnalysisDimensionMapping.builder()
                    .dimensionKey("PRICING_ANALYSIS")
                    .targetFields(List.of("pricing"))
                    .evidencePathKeys(List.of("OFFICIAL_PRICING_PAGE"))
                    .sourceTypes(List.of("PRICING", "OFFICIAL"))
                    .queryIntents(List.of("OFFICIAL_PRICING"))
                    .requiredByDefault(true)
                    .priority(100)
                    .reason("测试目录命中 pricing")
                    .build()));

    CoverageContractResolver resolver = new CoverageContractResolver(catalog);

    CoverageContract contract = resolver.resolve(
            null,
            List.of("收费模式"),
            List.of("官网"),
            null);

    assertThat(contract.findField("pricing").orElseThrow().getStatus())
            .isEqualTo(CoverageFieldStatus.REQUIRED);
    verify(catalog).resolve(List.of("收费模式"), List.of("官网"));
}
```

- [ ] **步骤 2：运行测试并确认解析器缺失**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest test
```

预期：编译失败，因为 `CoverageContractResolver` 尚不存在，或尚未接入 `AnalysisDimensionMappingCatalog`。

- [ ] **步骤 3：实现解析器**

创建 `CoverageContractResolver.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CoverageContractResolver {

    private final AnalysisDimensionMappingCatalog mappingCatalog;

    public CoverageContractResolver() {
        this(new AnalysisDimensionMappingCatalog());
    }

    public CoverageContractResolver(AnalysisDimensionMappingCatalog mappingCatalog) {
        this.mappingCatalog = mappingCatalog == null ? new AnalysisDimensionMappingCatalog() : mappingCatalog;
    }

    public CoverageContract resolve(String reportTemplate,
                                    List<String> analysisDimensions,
                                    List<String> sourceScope,
                                    String explicitTaskMode) {
        String taskMode = resolveTaskMode(reportTemplate, analysisDimensions, explicitTaskMode);
        List<AnalysisDimensionMapping> mappings = mappingCatalog.resolve(analysisDimensions, sourceScope);
        List<CoverageFieldContract> fields = new ArrayList<>();
        fields.add(field("summary", CoverageFieldStatus.REQUIRED, CoverageBlockingLevel.BLOCKER,
                List.of("OVERVIEW", "OFFICIAL_DOC"), List.of("OFFICIAL_DOCS"), 1, true,
                "系统默认要求产品概览"));
        fields.add(field("positioning", CoverageFieldStatus.REQUIRED, CoverageBlockingLevel.BLOCKER,
                List.of("OVERVIEW", "FEATURE_BLOCK"), List.of("OFFICIAL_DOCS"), 1, true,
                "系统默认要求市场定位"));
        fields.add(field("targetUsers", CoverageFieldStatus.REQUIRED, CoverageBlockingLevel.BLOCKER,
                List.of("OVERVIEW", "FEATURE_BLOCK"), List.of("OFFICIAL_DOCS"), 1, true,
                "系统默认要求目标用户"));
        fields.add(field("coreFeatures", CoverageFieldStatus.REQUIRED, CoverageBlockingLevel.BLOCKER,
                List.of("OFFICIAL_DOC", "DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"),
                List.of("OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE"), 2, true,
                "analysis_dimensions 命中 开放平台/开发者生态/产品功能"));

        boolean standardTemplate = containsIgnoreCase(reportTemplate, "STANDARD")
                || containsIgnoreCase(reportTemplate, "完整")
                || containsIgnoreCase(reportTemplate, "全量");
        boolean pricingDimension = requiresField(mappings, "pricing");
        boolean weaknessDimension = requiresField(mappings, "weaknesses");

        CoverageFieldStatus pricingStatus = standardTemplate || pricingDimension
                ? CoverageFieldStatus.REQUIRED
                : CoverageFieldStatus.OUT_OF_SCOPE;
        CoverageBlockingLevel pricingLevel = pricingStatus == CoverageFieldStatus.REQUIRED
                ? CoverageBlockingLevel.BLOCKER
                : CoverageBlockingLevel.NONE;
        fields.add(field("pricing", pricingStatus, pricingLevel,
                List.of("PRICING_BLOCK"),
                pricingStatus == CoverageFieldStatus.REQUIRED ? queryIntentsFor(mappings, "pricing", List.of("PRICING")) : List.of(),
                pricingStatus == CoverageFieldStatus.REQUIRED ? 1 : 0,
                true,
                standardTemplate ? "显式报告模板要求定价" :
                        pricingDimension ? "显式维度要求优先于来源范围受限" :
                                "taskMode=CAPABILITY_INTRO 且 analysis_dimensions 未显式要求定价"));

        fields.add(field("strengths", standardTemplate ? CoverageFieldStatus.REQUIRED : CoverageFieldStatus.OPTIONAL,
                standardTemplate ? CoverageBlockingLevel.BLOCKER : CoverageBlockingLevel.WARNING,
                List.of("FEATURE_BLOCK", "OFFICIAL_DOC"), List.of("OFFICIAL_DOCS"), 1, true,
                standardTemplate ? "显式报告模板要求优势判断" : "能力介绍任务中优势判断为可选"));

        CoverageFieldStatus weaknessesStatus = standardTemplate || weaknessDimension
                ? CoverageFieldStatus.REQUIRED
                : CoverageFieldStatus.OUT_OF_SCOPE;
        CoverageBlockingLevel weaknessesLevel = weaknessesStatus == CoverageFieldStatus.REQUIRED
                ? CoverageBlockingLevel.BLOCKER
                : CoverageBlockingLevel.NONE;
        fields.add(field("weaknesses", weaknessesStatus, weaknessesLevel,
                List.of("LIMITATION_OR_POLICY_BLOCK", "THIRD_PARTY_REVIEW"),
                weaknessesStatus == CoverageFieldStatus.REQUIRED ? queryIntentsFor(mappings, "weaknesses", List.of("POLICY", "RISK", "THIRD_PARTY_REVIEW")) : List.of(),
                weaknessesStatus == CoverageFieldStatus.REQUIRED ? 2 : 0,
                false,
                standardTemplate ? "显式报告模板要求短板与风险" :
                        weaknessDimension ? "显式维度要求短板与风险" :
                                "taskMode=CAPABILITY_INTRO 且 source_scope 未包含第三方来源"));

        return CoverageContract.builder()
                .taskMode(taskMode)
                .contractVersion("coverage-" + taskMode.toLowerCase(Locale.ROOT) + "-v1")
                .source("PLANNER")
                .fields(fields)
                .build();
    }

    private String resolveTaskMode(String reportTemplate, List<String> dimensions, String explicitTaskMode) {
        if (explicitTaskMode != null && !explicitTaskMode.isBlank()) {
            return explicitTaskMode.trim().toUpperCase(Locale.ROOT);
        }
        if (containsIgnoreCase(reportTemplate, "STANDARD") || containsIgnoreCase(reportTemplate, "完整")) {
            return "STANDARD_COMPETITOR_REPORT";
        }
        List<AnalysisDimensionMapping> mappings = mappingCatalog.resolve(dimensions, List.of());
        if (requiresField(mappings, "pricing") || requiresField(mappings, "weaknesses")) {
            return "FOCUSED_DEEP_DIVE";
        }
        return "CAPABILITY_INTRO";
    }

    private boolean requiresField(List<AnalysisDimensionMapping> mappings, String fieldName) {
        return mappings != null && mappings.stream()
                .anyMatch(mapping -> mapping.isRequiredByDefault()
                        && mapping.getTargetFields() != null
                        && mapping.getTargetFields().contains(fieldName));
    }

    private List<String> queryIntentsFor(List<AnalysisDimensionMapping> mappings,
                                         String fieldName,
                                         List<String> fallback) {
        if (mappings == null) {
            return fallback;
        }
        List<String> intents = mappings.stream()
                .filter(mapping -> mapping.getTargetFields() != null && mapping.getTargetFields().contains(fieldName))
                .flatMap(mapping -> mapping.getQueryIntents() == null ? List.<String>of().stream() : mapping.getQueryIntents().stream())
                .distinct()
                .toList();
        return intents.isEmpty() ? fallback : intents;
    }

    private CoverageFieldContract field(String field,
                                        CoverageFieldStatus status,
                                        CoverageBlockingLevel level,
                                        List<String> evidenceTypes,
                                        List<String> queryIntents,
                                        int minDistinctEvidenceCount,
                                        boolean allowOfficialOnly,
                                        String reason) {
        return CoverageFieldContract.builder()
                .field(field)
                .status(status)
                .blockingLevel(level)
                .targetEvidenceTypes(evidenceTypes)
                .queryIntents(queryIntents)
                .evidencePaths(evidencePathsFor(field, status, queryIntents))
                .minimumAttemptedPaths(minimumAttemptedPathsFor(field, status))
                .minDistinctEvidenceCount(minDistinctEvidenceCount)
                .allowOfficialOnly(allowOfficialOnly)
                .overrideReason(reason)
                .build();
    }

    private List<CoverageEvidencePath> evidencePathsFor(String field,
                                                        CoverageFieldStatus status,
                                                        List<String> queryIntents) {
        if (status != CoverageFieldStatus.REQUIRED) {
            return List.of();
        }
        if ("pricing".equalsIgnoreCase(field)) {
            return List.of(
                    CoverageEvidencePath.builder()
                            .pathKey("OFFICIAL_PRICING_PAGE")
                            .sourceTypes(List.of("PRICING", "OFFICIAL"))
                            .queryIntents(filterIntents(queryIntents, "OFFICIAL_PRICING"))
                            .expectedSignals(List.of("PRICING_BLOCK"))
                            .required(true)
                            .successCriteria("命中官方定价、套餐、计费或商务开通说明")
                            .failureStatus("NO_OFFICIAL_PRICING_PAGE")
                            .build(),
                    CoverageEvidencePath.builder()
                            .pathKey("DOCS_BILLING_OR_LIMITS")
                            .sourceTypes(List.of("DOCS", "OFFICIAL"))
                            .queryIntents(filterIntents(queryIntents, "DOCS_BILLING", "API_LIMITS"))
                            .expectedSignals(List.of("PRICING_BLOCK", "LIMITATION_OR_POLICY_BLOCK"))
                            .required(true)
                            .successCriteria("命中文档中的计费、免费配额、调用限制或开通条件")
                            .failureStatus("DOCS_BILLING_PATH_NOT_COVERED")
                            .build(),
                    CoverageEvidencePath.builder()
                            .pathKey("TERMS_OR_SERVICE_AGREEMENT")
                            .sourceTypes(List.of("OFFICIAL", "DOCS"))
                            .queryIntents(filterIntents(queryIntents, "TERMS_BILLING", "SERVICE_AGREEMENT"))
                            .expectedSignals(List.of("LIMITATION_OR_POLICY_BLOCK"))
                            .required(false)
                            .successCriteria("命中协议或服务条款中的收费、限制或商务说明")
                            .failureStatus("TERMS_PATH_NOT_COVERED")
                            .build());
        }
        if ("coreFeatures".equalsIgnoreCase(field)) {
            return List.of(CoverageEvidencePath.builder()
                    .pathKey("DOCS_API_GUIDE")
                    .sourceTypes(List.of("DOCS", "OFFICIAL"))
                    .queryIntents(filterIntents(queryIntents, "API_DOCS", "SDK_GUIDE", "OFFICIAL_DOCS"))
                    .expectedSignals(List.of("DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"))
                    .required(true)
                    .successCriteria("命中 API、SDK、开发者能力或产品功能正文")
                    .failureStatus("DOCS_API_GUIDE_NOT_COVERED")
                    .build());
        }
        if ("weaknesses".equalsIgnoreCase(field)) {
            return List.of(
                    CoverageEvidencePath.builder()
                            .pathKey("TERMS_OR_SERVICE_AGREEMENT")
                            .sourceTypes(List.of("TERMS", "POLICY", "OFFICIAL"))
                            .queryIntents(filterIntents(queryIntents, "POLICY", "RISK"))
                            .expectedSignals(List.of("LIMITATION_OR_POLICY_BLOCK"))
                            .required(true)
                            .successCriteria("命中协议、规则、审核、限制或合规风险说明")
                            .failureStatus("POLICY_LIMITATION_NOT_COVERED")
                            .build(),
                    CoverageEvidencePath.builder()
                            .pathKey("PUBLIC_REVIEW_OR_NEWS")
                            .sourceTypes(List.of("REVIEW", "NEWS"))
                            .queryIntents(filterIntents(queryIntents, "THIRD_PARTY_REVIEW", "RISK"))
                            .expectedSignals(List.of("THIRD_PARTY_REVIEW", "NEWS_BLOCK"))
                            .required(false)
                            .successCriteria("命中第三方公开评价、新闻或用户反馈")
                            .failureStatus("PUBLIC_REVIEW_NOT_COVERED")
                            .build());
        }
        return List.of();
    }

    private int minimumAttemptedPathsFor(String field, CoverageFieldStatus status) {
        if (status != CoverageFieldStatus.REQUIRED) {
            return 0;
        }
        if ("pricing".equalsIgnoreCase(field)) {
            return 2;
        }
        if ("weaknesses".equalsIgnoreCase(field)) {
            return 1;
        }
        if ("coreFeatures".equalsIgnoreCase(field)) {
            return 1;
        }
        return 0;
    }

    private List<String> filterIntents(List<String> queryIntents, String... fallback) {
        if (queryIntents == null || queryIntents.isEmpty()) {
            return Arrays.asList(fallback);
        }
        List<String> expectedIntents = Arrays.asList(fallback);
        List<String> matched = queryIntents.stream()
                .filter(intent -> intent != null && expectedIntents.stream()
                        .anyMatch(expected -> intent.equalsIgnoreCase(expected)))
                .distinct()
                .toList();
        return matched.isEmpty() ? expectedIntents : matched;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **步骤 4：运行解析器测试**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest test
```

预期：所有解析器测试通过。

### Task 4: 持久化契约到计划快照和节点配置引用

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/task/definition/ExecutionPlanDefinition.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlan.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/WorkflowPlanAssembler.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/integration/CollaborationPlanningSmokeTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanCoverageContractTest.java`

- [ ] **步骤 1：编写 workflow plan 测试**

创建 `WorkflowPlanCoverageContractTest.java`：

```java
package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPlanCoverageContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldProjectCoverageContractFromExecutionPlanToWorkflowPlan() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .overrideReason("能力介绍任务不强检定价")
                        .build()))
                .build();

        ExecutionPlanDefinition definition = ExecutionPlanDefinition.builder()
                .contractType("COMPETITOR_ANALYSIS_EXECUTION_PLAN")
                .goal("test")
                .coverageContract(contract)
                .stages(List.of())
                .nodes(List.of())
                .sourceUrls(List.of())
                .build();

        WorkflowPlan workflowPlan = new WorkflowPlanAssembler().fromExecutionPlan(definition);
        String json = objectMapper.writeValueAsString(workflowPlan);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.at("/coverageContract/taskMode").asText()).isEqualTo("CAPABILITY_INTRO");
        assertThat(node.at("/coverageContract/fields/0/field").asText()).isEqualTo("pricing");
    }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：

```powershell
mvn -pl backend -Dtest=WorkflowPlanCoverageContractTest test
```

预期：编译失败，因为 plan classes 尚未暴露 `coverageContract`。

- [ ] **步骤 3：新增 coverageContract 字段**

修改 `ExecutionPlanDefinition`：

```java
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
```

新增字段：

```java
CoverageContract coverageContract;
```

修改 `WorkflowPlan`：

```java
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
```

新增字段：

```java
@Builder.Default
private CoverageContract coverageContract = null;
```

修改 `WorkflowPlanAssembler.fromExecutionPlan` builder：

```java
.coverageContract(executionPlan == null ? null : executionPlan.getCoverageContract())
```

- [ ] **步骤 4：给 CollectorNodeConfig 增加裁剪后的 coverage 字段**

修改 `CollectorNodeConfig` 字段：

```java
private String coverageContractRef;
private List<String> requiredCoverageFields;
private List<String> blockingCoverageFields;
private List<String> coverageQueryIntents;
```

把这些字段名追加到 `@JsonPropertyOrder` 的 `schemaName` 之后。

- [ ] **步骤 5：把 resolver 接入 ExecutionPlanDefinitionBuilder**

新增构造依赖：

```java
private final cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver coverageContractResolver;
```

在 `build(...)` 中解析 dimensions/source scope 后新增：

```java
CoverageContract coverageContract = coverageContractResolver.resolve(
        task.getReportTemplate(),
        dimensions,
        requestedScopes,
        null
);
```

构造 collector node config 时，在 factory 创建后新增：

```java
collectorNodeConfig.setCoverageContractRef(coverageContract.getContractVersion());
collectorNodeConfig.setRequiredCoverageFields(requiredFields(coverageContract));
collectorNodeConfig.setBlockingCoverageFields(blockingFields(coverageContract));
collectorNodeConfig.setCoverageQueryIntents(queryIntentsForSourcePlan(coverageContract, sourcePlan));
```

在同一个类中新增 helper 方法：

```java
private List<String> requiredFields(CoverageContract contract) {
    if (contract == null || contract.getFields() == null) {
        return List.of();
    }
    return contract.getFields().stream()
            .filter(field -> field != null && field.getStatus() == CoverageFieldStatus.REQUIRED)
            .map(CoverageFieldContract::getField)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
}

private List<String> blockingFields(CoverageContract contract) {
    if (contract == null || contract.getFields() == null) {
        return List.of();
    }
    return contract.getFields().stream()
            .filter(field -> field != null && field.getBlockingLevel() == CoverageBlockingLevel.BLOCKER)
            .map(CoverageFieldContract::getField)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
}

private List<String> queryIntentsForSourcePlan(CoverageContract contract, SourcePlan sourcePlan) {
    if (contract == null || contract.getFields() == null) {
        return List.of();
    }
    return contract.getFields().stream()
            .flatMap(field -> field.getQueryIntents() == null ? java.util.stream.Stream.empty() : field.getQueryIntents().stream())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
}
```

新增 `CoverageContract`、`CoverageFieldContract`、`CoverageFieldStatus` 和 `CoverageBlockingLevel` import。

在 return 前设置顶层 execution plan contract：

```java
.coverageContract(coverageContract)
```

- [ ] **步骤 6：更新测试中的手动 builder 构造**

`WorkflowFactoryTest` 和 `CollaborationPlanningSmokeTest` 中会手动实例化 `ExecutionPlanDefinitionBuilder`。更新这些测试 helper 的构造调用，把 resolver 作为最后一个参数传入：

```java
ExecutionPlanDefinitionBuilder executionPlanDefinitionBuilder = new ExecutionPlanDefinitionBuilder(
        Mockito.mock(AnalysisSchemaRepository.class),
        sourceDiscoveryService,
        new SourceCandidateRanker(),
        objectMapper,
        collectorPlanTemplateFactory,
        new CoverageContractResolver()
);
```

在两个测试文件中新增 import：

```java
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
```

如果还有编译错误指向 `new ExecutionPlanDefinitionBuilder(...)`，用同样方式更新对应调用点。不要创建 no-op resolver，也不要让测试继续使用旧构造函数，因为 Phase 1 必须证明契约能稳定通过规划链路生成。

- [ ] **步骤 7：运行 workflow 和构造覆盖测试**

运行：

```powershell
mvn -pl backend -Dtest=WorkflowPlanCoverageContractTest,WorkflowFactoryTest,CollaborationPlanningSmokeTest test
```

预期：测试通过。

### Task 5: 覆盖契约 Provider

**文件：**
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractProvider.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/CoverageContractProviderTest.java`

- [ ] **步骤 1：编写 Provider 测试**

创建 `CoverageContractProviderTest.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverageContractProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final CoverageContractResolver fallbackResolver = new CoverageContractResolver();
    private final CoverageContractProvider provider =
            new CoverageContractProvider(taskPlanRepository, objectMapper, fallbackResolver);

    @Test
    void shouldLoadContractFromActivePlanSnapshot() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .build()))
                .build();
        WorkflowPlan plan = WorkflowPlan.builder()
                .coverageContract(contract)
                .nodes(List.of())
                .stages(List.of())
                .build();
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(66L))
                .thenReturn(Optional.of(TaskPlan.builder()
                        .taskId(66L)
                        .planVersion(1)
                        .planSnapshot(objectMapper.writeValueAsString(plan))
                        .build()));

        CoverageContract resolved = provider.resolve(AgentContext.builder()
                .taskId(66L)
                .analysisDimensions("[\"开放平台\",\"开发者生态\",\"产品功能\"]")
                .sourceScope("[\"官网\",\"产品文档\"]")
                .build());

        assertThat(resolved.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
        assertThat(resolved.findField("pricing").orElseThrow().getStatus())
                .isEqualTo(CoverageFieldStatus.OUT_OF_SCOPE);
    }

    @Test
    void shouldFallbackToResolverWhenPlanSnapshotMissing() {
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(67L))
                .thenReturn(Optional.empty());

        CoverageContract resolved = provider.resolve(AgentContext.builder()
                .taskId(67L)
                .analysisDimensions("[\"定价策略\"]")
                .sourceScope("[\"官网\"]")
                .build());

        assertThat(resolved.findField("pricing").orElseThrow().getStatus())
                .isEqualTo(CoverageFieldStatus.REQUIRED);
    }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractProviderTest test
```

预期：编译失败，因为 provider 尚不存在。

- [ ] **步骤 3：实现 Provider**

创建 `CoverageContractProvider.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CoverageContractProvider {

    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;
    private final CoverageContractResolver fallbackResolver;

    public CoverageContractProvider(TaskPlanRepository taskPlanRepository,
                                    ObjectMapper objectMapper,
                                    CoverageContractResolver fallbackResolver) {
        this.taskPlanRepository = taskPlanRepository;
        this.objectMapper = objectMapper;
        this.fallbackResolver = fallbackResolver;
    }

    public CoverageContract resolve(AgentContext context) {
        if (context != null && context.getTaskId() != null) {
            CoverageContract fromPlan = taskPlanRepository
                    .findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(context.getTaskId())
                    .map(TaskPlan::getPlanSnapshot)
                    .map(this::readFromSnapshot)
                    .orElse(null);
            if (fromPlan != null) {
                return fromPlan;
            }
        }
        return fallbackResolver.resolve(
                context == null ? null : context.getReportTemplate(),
                readStringList(context == null ? null : context.getAnalysisDimensions()),
                readStringList(context == null ? null : context.getSourceScope()),
                null
        );
    }

    private CoverageContract readFromSnapshot(String planSnapshot) {
        if (planSnapshot == null || planSnapshot.isBlank()) {
            return null;
        }
        try {
            WorkflowPlan plan = objectMapper.readValue(planSnapshot, WorkflowPlan.class);
            return plan.getCoverageContract();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of(raw);
        }
    }
}
```

- [ ] **步骤 4：运行 Provider 测试**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractProviderTest test
```

预期：测试通过。

### Task 6: Extractor 和 Reviewer 消费契约

**文件：**
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgent.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentCoverageContractTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/agent/reviewer/QualityReviewAgentCoverageContractTest.java`

- [ ] **步骤 1：编写 Reviewer 契约测试**

创建 `QualityReviewAgentCoverageContractTest.java`：

```java
package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityReviewAgentCoverageContractTest {

    @Test
    void shouldOnlyBlockRequiredFieldsFromCoverageContract() {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .fields(List.of(
                        CoverageFieldContract.builder()
                                .field("coreFeatures")
                                .status(CoverageFieldStatus.REQUIRED)
                                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                                .build(),
                        CoverageFieldContract.builder()
                                .field("pricing")
                                .status(CoverageFieldStatus.OUT_OF_SCOPE)
                                .blockingLevel(CoverageBlockingLevel.NONE)
                                .build()))
                .build();

        List<String> blockers = QualityReviewAgent.resolveCoverageBlockerFields(contract);

        assertThat(blockers).containsExactly("coreFeatures");
        assertThat(blockers).doesNotContain("pricing");
    }
}
```

- [ ] **步骤 2：给 Reviewer 增加静态 helper**

在 `QualityReviewAgent` 中新增：

```java
public static List<String> resolveCoverageBlockerFields(CoverageContract contract) {
    if (contract == null || contract.getFields() == null) {
        return COVERAGE_FIELD_RULES.stream().map(CoverageFieldRule::fieldName).toList();
    }
    return contract.getFields().stream()
            .filter(field -> field != null && field.getBlockingLevel() == CoverageBlockingLevel.BLOCKER)
            .map(CoverageFieldContract::getField)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
}
```

新增 `CoverageContract`、`CoverageFieldContract` 和 `CoverageBlockingLevel` import。

- [ ] **步骤 3：运行 Reviewer 契约 helper 测试**

运行：

```powershell
mvn -pl backend -Dtest=QualityReviewAgentCoverageContractTest test
```

预期：测试通过。

- [ ] **步骤 4：注入 CoverageContractProvider**

给 `SchemaExtractorAgent` 和 `QualityReviewAgent` 增加可选构造依赖：

```java
private final CoverageContractProvider coverageContractProvider;
```

生产构造函数必须通过 Spring 接收 `CoverageContractProvider`。测试专用构造函数可以显式传入 mock provider；不要在 Agent 内部隐藏创建 JSON parser 或 resolver fallback。

- [ ] **步骤 5：在 Extractor prompt guidance 中使用契约**

在 `SchemaExtractorAgent` 组装字段 guidance 的位置解析：

```java
CoverageContract contract = coverageContractProvider == null ? null : coverageContractProvider.resolve(context);
```

对每个字段，在 prompt guidance 中加入状态：

```java
guidance.add("coverageContract: " + field.getField()
        + "=" + field.getStatus()
        + ", blockingLevel=" + field.getBlockingLevel()
        + ", reason=" + field.getOverrideReason());
```

确保 `OUT_OF_SCOPE` 字段被描述为非阻断字段，并明确不能幻觉补写。

- [ ] **步骤 6：在 Reviewer coverage collection 中使用契约**

在 `QualityReviewAgent` 的 review 流程附近解析契约：

```java
CoverageContract contract = coverageContractProvider == null ? null : coverageContractProvider.resolve(context);
List<String> blockerFields = resolveCoverageBlockerFields(contract);
```

将 coverage gap 转换为 blocker issue 时，只有 `blockerFields` 中的字段可以成为阻断问题。非阻断缺失字段只能成为 warning 或诊断 metadata。

- [ ] **步骤 7：编写 Extractor 最小契约测试**

创建 `SchemaExtractorAgentCoverageContractTest.java`：

```java
package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExtractorAgentCoverageContractTest {

    @Test
    void shouldRenderOutOfScopePricingGuidance() {
        CoverageContract contract = CoverageContract.builder()
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .overrideReason("能力介绍任务不强检定价")
                        .build()))
                .build();

        String guidance = SchemaExtractorAgent.renderCoverageContractGuidance(contract);

        assertThat(guidance).contains("pricing=OUT_OF_SCOPE");
        assertThat(guidance).contains("能力介绍任务不强检定价");
    }
}
```

在 `SchemaExtractorAgent` 中增加静态 helper：

```java
public static String renderCoverageContractGuidance(CoverageContract contract) {
    if (contract == null || contract.getFields() == null || contract.getFields().isEmpty()) {
        return "coverageContract: unavailable; fallback to legacy fields.";
    }
    return contract.getFields().stream()
            .map(field -> field.getField() + "=" + field.getStatus()
                    + ", blockingLevel=" + field.getBlockingLevel()
                    + ", reason=" + field.getOverrideReason())
            .collect(java.util.stream.Collectors.joining("\n"));
}
```

- [ ] **步骤 8：运行本阶段测试**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest,CoverageContractProviderTest,WorkflowPlanCoverageContractTest,SchemaExtractorAgentCoverageContractTest,QualityReviewAgentCoverageContractTest test
```

预期：全部测试通过。
