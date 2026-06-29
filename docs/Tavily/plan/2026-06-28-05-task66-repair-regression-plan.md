# 05 Task 66 修复、答案合成与回归实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 在第 4 阶段公开补采底座之上接入 repair 状态、答案合成和 task66 分阶段回归，让弱入口证据能被提升为可验证细页证据，同时能力介绍任务不再因为范围外的 pricing/weaknesses 失败。

**架构：** `EvidenceQualityGate` 标记弱证据。第 4 阶段已有的 `PublicEvidenceRecoveryService` 负责生成公开替代候选，本阶段只复用它，不重新创建 sourceType-first 版本。`EvidenceRepairPlan` 记录 repair 生命周期，`FieldAnswerSynthesizer` 将 `FieldEvidenceCoverage` 合成为可审计字段结论。搜索/采集验证负责提升更强证据或记录明确失败状态；回归测试分别验证能力介绍模式和完整报告模式。

**技术栈：** Java 17、搜索候选模型、采集质量 verdict、现有 Tavily provider 类、JUnit 5、Mockito、AssertJ。

---

## 文件结构

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairState.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairPlan.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerConclusion.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerSynthesizer.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryService.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryServiceTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/EvidenceRepairStateTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerSynthesizerTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/integration/Task66CoverageContractRegressionTest.java`

---

### Task 1: 证据修复状态模型

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairState.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairPlan.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/EvidenceRepairStateTest.java`

- [ ] **步骤 1：编写修复状态测试**

创建 `EvidenceRepairStateTest.java`：

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepairStateTest {

    @Test
    void shouldDistinguishRepairQueryFromPromotedEvidence() {
        EvidenceRepairPlan plan = EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                .repairQueries(List.of("site:open.bilibili.com 用户管理 API"))
                .reason("AUTH_OR_CAPTCHA_GATE")
                .build();

        assertThat(plan.isComplete()).isFalse();

        EvidenceRepairPlan promoted = plan.toBuilder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .build();

        assertThat(promoted.isComplete()).isTrue();
    }
}
```

- [ ] **步骤 2：新增枚举**

创建 `EvidenceRepairState.java`：

```java
package cn.bugstack.competitoragent.search;

public enum EvidenceRepairState {
    REPAIR_NOT_REQUIRED,
    REPAIR_QUERY_PROPOSED,
    REPAIR_CANDIDATE_VERIFIED,
    REPAIR_EVIDENCE_PROMOTED,
    REPAIR_FAILED
}
```

- [ ] **步骤 3：新增修复计划值对象**

创建 `EvidenceRepairPlan.java`：

```java
package cn.bugstack.competitoragent.search;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class EvidenceRepairPlan {

    EvidenceRepairState state;
    String reason;
    String sourceUrl;
    List<String> repairQueries;
    List<String> candidateUrls;
    List<String> promotedUrls;

    public boolean isComplete() {
        return state == EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED;
    }

    public EvidenceRepairPlan verifyCandidates(List<String> verifiedUrls) {
        if (verifiedUrls == null || verifiedUrls.isEmpty()) {
            return this.toBuilder()
                    .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                    .candidateUrls(List.of())
                    .build();
        }
        return this.toBuilder()
                .state(EvidenceRepairState.REPAIR_CANDIDATE_VERIFIED)
                .candidateUrls(verifiedUrls)
                .build();
    }

    public EvidenceRepairPlan promoteEvidence(List<String> promotedUrls) {
        if (promotedUrls == null || promotedUrls.isEmpty()) {
            return this.toBuilder()
                    .state(EvidenceRepairState.REPAIR_FAILED)
                    .promotedUrls(List.of())
                    .build();
        }
        return this.toBuilder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .promotedUrls(promotedUrls)
                .build();
    }
}
```

- [ ] **步骤 4：运行模型测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceRepairStateTest test
```

预期：测试通过。

### Task 2: 复用公开补采服务生成 repair 候选

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryService.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryServiceTest.java`

**规则：**

本阶段不得重新创建一个只接收 `sourceType` 或弱正文字符串的 `PublicEvidenceRecoveryService`。它必须复用第 4 阶段的 `RecoveryContext` 新签名，并把 `EvidenceQualityVerdict / FieldEvidenceCoverage` 中的字段上下文转换为 `fieldName / evidencePathKey / queryIntents`。

- [ ] **步骤 1：编写基于字段上下文的 repair 候选测试**

追加到 `PublicEvidenceRecoveryServiceTest.java`：

```java
@Test
void shouldGenerateRepairCandidatesWithFieldEvidenceContext() {
    PublicEvidenceRecoveryService.RecoveryContext context = PublicEvidenceRecoveryService.RecoveryContext.builder()
            .competitorName("哔哩哔哩")
            .sourceType("DOCS")
            .fieldName("coreFeatures")
            .evidencePathKey("DOCS_API_GUIDE")
            .queryIntents(List.of("API_DOCS", "SDK_GUIDE"))
            .maxAlternatives(12)
            .build();

    PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
            context,
            List.of(SourceCandidate.builder()
                    .url("https://open.bilibili.com")
                    .sourceType("DOCS")
                    .verified(Boolean.FALSE)
                    .verificationReason("智能验证检测中，正文不足")
                    .qualitySignals(List.of("AUTH_OR_CAPTCHA_GATE", "NAVIGATION_SHELL"))
                    .sourceUrls(List.of("https://open.bilibili.com"))
                    .build()),
            List.of());

    assertThat(plan.triggered()).isTrue();
    assertThat(plan.fieldName()).isEqualTo("coreFeatures");
    assertThat(plan.evidencePathKey()).isEqualTo("DOCS_API_GUIDE");
    assertThat(plan.candidates()).allSatisfy(candidate ->
            assertThat(candidate.getQualitySignals()).contains("FIELD_EVIDENCE_PATH_RECOVERY"));
}
```

- [ ] **步骤 2：增加 verdict 到 RecoveryContext 的适配器**

在 `PublicEvidenceRecoveryService` 中新增 helper，供 repair 链路把证据质量 verdict 转成字段补采上下文：

```java
public RecoveryContext toRecoveryContext(String competitorName,
                                         String sourceType,
                                         String fieldName,
                                         String evidencePathKey,
                                         List<String> queryIntents,
                                         EvidenceQualityVerdict verdict) {
    return RecoveryContext.builder()
            .competitorName(competitorName)
            .sourceType(sourceType)
            .fieldName(fieldName)
            .evidencePathKey(evidencePathKey)
            .queryIntents(queryIntents)
            .maxAlternatives(verdict != null && verdict.isRepairRequired() ? 12 : 0)
            .build();
}
```

- [ ] **步骤 3：运行服务测试**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest test
```

预期：测试通过，且不新增 sourceType-only 的 `PublicEvidenceRecoveryService` 实现。

### Task 3: 接入搜索/采集 repair 审计

**文件：**
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryServiceTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorRepairAuditTest.java`

- [ ] **步骤 1：给弱证据路径增加 repair plan metadata**

在 `CollectorAgent` 中，写入 `EvidenceQualityVerdict` 之后调用：

```java
EvidenceRepairPlan repairPlan = publicEvidenceRecoveryService.planRecovery(
        config.getCompetitorName(),
        collectionResult.getResourceLocator(),
        collectionResult.getContent(),
        collectionResult.getEvidenceQualityVerdict(),
        readDimensions(context.getAnalysisDimensions())
);
```

将 repair plan 写入采集结果 metadata：

```java
metadata.put("evidenceRepairPlan", repairPlan);
```

- [ ] **步骤 2：增加公开补采服务依赖**

注入：

```java
private final PublicEvidenceRecoveryService publicEvidenceRecoveryService;
```

旧构造器中的默认值：

```java
this.publicEvidenceRecoveryService = publicEvidenceRecoveryService == null
        ? new PublicEvidenceRecoveryService()
        : publicEvidenceRecoveryService;
```

- [ ] **步骤 3：输出 repair 状态信号**

当 repair plan 状态为 `REPAIR_QUERY_PROPOSED` 时，合并质量信号：

```java
qualitySignals.add("REPAIR_QUERY_PROPOSED");
```

当已验证替代 URL 被提升时，合并质量信号：

```java
qualitySignals.add("REPAIR_EVIDENCE_PROMOTED");
```

- [ ] **步骤 4：在 SearchExecutionCoordinator 中增加 repair 审计投影**

给 `SearchExecutionCoordinator` 增加一个小 helper，让搜索阶段 repair 暴露与采集 metadata 一致的状态词汇。该 helper 必须保持纯函数和确定性，不在其中调用 Tavily。

```java
static Map<String, Object> buildRepairAuditProjection(EvidenceRepairPlan repairPlan) {
    if (repairPlan == null) {
        return Map.of(
                "repairState", EvidenceRepairState.REPAIR_NOT_REQUIRED.name(),
                "repairQueries", List.of(),
                "candidateUrls", List.of(),
                "promotedUrls", List.of()
        );
    }
    return Map.of(
            "repairState", repairPlan.getState() == null
                    ? EvidenceRepairState.REPAIR_NOT_REQUIRED.name()
                    : repairPlan.getState().name(),
            "repairReason", repairPlan.getReason() == null ? "" : repairPlan.getReason(),
            "sourceUrl", repairPlan.getSourceUrl() == null ? "" : repairPlan.getSourceUrl(),
            "repairQueries", repairPlan.getRepairQueries() == null ? List.of() : repairPlan.getRepairQueries(),
            "candidateUrls", repairPlan.getCandidateUrls() == null ? List.of() : repairPlan.getCandidateUrls(),
            "promotedUrls", repairPlan.getPromotedUrls() == null ? List.of() : repairPlan.getPromotedUrls()
    );
}
```

导入：

```java
import java.util.Map;
```

在 `SearchExecutionCoordinator` 追加 repair 尝试审计 metadata 的地方使用该 helper。最低可接受接入是把投影放入 search audit payload 的 `evidenceRepairPlan` 字段，确保回放时能区分 `REPAIR_QUERY_PROPOSED`、`REPAIR_CANDIDATE_VERIFIED` 和 `REPAIR_EVIDENCE_PROMOTED`。

- [ ] **步骤 5：增加 repair 审计投影测试**

创建 `SearchExecutionCoordinatorRepairAuditTest.java`：

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchExecutionCoordinatorRepairAuditTest {

    @Test
    void shouldExposePromotedRepairStateInAuditProjection() {
        EvidenceRepairPlan repairPlan = EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .reason("AUTH_OR_CAPTCHA_GATE")
                .sourceUrl("https://open.bilibili.com")
                .repairQueries(List.of("site:open.bilibili.com 用户管理 API"))
                .candidateUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .build();

        Map<String, Object> projection = SearchExecutionCoordinator.buildRepairAuditProjection(repairPlan);

        assertThat(projection).containsEntry("repairState", "REPAIR_EVIDENCE_PROMOTED");
        assertThat((List<String>) projection.get("promotedUrls"))
                .containsExactly("https://open.bilibili.com/doc/4/feb66f99");
    }
}
```

- [ ] **步骤 6：增加确定性证据提升 helper**

在 `PublicEvidenceRecoveryService` 中增加 helper，让 repair 生命周期不依赖 live 网络也能测试：

```java
public EvidenceRepairPlan promoteVerifiedUrls(EvidenceRepairPlan plan, List<String> verifiedUrls) {
    if (plan == null) {
        return EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_FAILED)
                .repairQueries(List.of())
                .candidateUrls(List.of())
                .promotedUrls(List.of())
                .reason("repair plan missing")
                .build();
    }
    EvidenceRepairPlan verified = plan.verifyCandidates(verifiedUrls);
    if (verified.getState() != EvidenceRepairState.REPAIR_CANDIDATE_VERIFIED) {
        return verified;
    }
    return verified.promoteEvidence(verifiedUrls);
}
```

- [ ] **步骤 7：增加证据提升测试**

追加到 `PublicEvidenceRecoveryServiceTest.java`：

```java
@Test
void shouldPromoteVerifiedRepairUrls() {
    EvidenceRepairPlan proposed = EvidenceRepairPlan.builder()
            .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
            .repairQueries(List.of("site:open.bilibili.com 用户管理 API"))
            .sourceUrl("https://open.bilibili.com")
            .build();

    EvidenceRepairPlan promoted = recoveryService.promoteVerifiedUrls(
            proposed,
            List.of("https://open.bilibili.com/doc/4/feb66f99"));

    assertThat(promoted.getState()).isEqualTo(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED);
    assertThat(promoted.isComplete()).isTrue();
    assertThat(promoted.getPromotedUrls()).containsExactly("https://open.bilibili.com/doc/4/feb66f99");
}
```

- [ ] **步骤 8：运行 recovery 测试**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest,SearchExecutionCoordinatorRepairAuditTest,CollectorAgentEvidenceQualityGateTest test
```

预期：测试通过。

### Task 4: 可审计字段答案合成

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerConclusion.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerSynthesizer.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldAnswerSynthesizerTest.java`

**规则：**

`FieldEvidenceCoverage` 解决“找到了哪些证据路径”的问题；`FieldAnswerSynthesizer` 负责把这些路径合成为可审计结论。LLM 可以参与自然语言表达，但字段状态、支撑 URL、冲突和下一步动作必须结构化输出，不能只藏在最终报告段落里。

- [ ] **步骤 1：编写定价免费结论合成测试**

创建 `FieldAnswerSynthesizerTest.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldAnswerSynthesizerTest {

    private final FieldAnswerSynthesizer synthesizer = new FieldAnswerSynthesizer();

    @Test
    void shouldSynthesizeConfirmedFreeConclusionWithReasoningSteps() {
        FieldAnswerConclusion conclusion = synthesizer.synthesize(
                "pricing",
                "CONFIRMED_FREE",
                List.of("https://open.example.com/docs/billing"),
                List.of(
                        "OFFICIAL_PRICING_PAGE 未发现独立定价页",
                        "DOCS_BILLING_OR_LIMITS 命中文档说明免费开放",
                        "PUBLIC_WEB_CONFIRMATION 未发现冲突证据"),
                List.of());

        assertThat(conclusion.getField()).isEqualTo("pricing");
        assertThat(conclusion.getCoverageStatus()).isEqualTo("CONFIRMED_FREE");
        assertThat(conclusion.getAnswerValue()).contains("免费");
        assertThat(conclusion.getSourceUrls()).containsExactly("https://open.example.com/docs/billing");
        assertThat(conclusion.getReasoningSteps()).hasSize(3);
        assertThat(conclusion.getRecommendedNextAction()).isEqualTo("ACCEPT_CONCLUSION");
    }
}
```

- [ ] **步骤 2：新增结论模型**

创建 `FieldAnswerConclusion.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class FieldAnswerConclusion {

    String field;
    String coverageStatus;
    String answerValue;
    List<String> sourceUrls;
    List<String> reasoningSteps;
    List<String> contradictions;
    double confidence;
    String recommendedNextAction;
}
```

- [ ] **步骤 3：新增答案合成器**

创建 `FieldAnswerSynthesizer.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FieldAnswerSynthesizer {

    public FieldAnswerConclusion synthesize(String field,
                                            String coverageStatus,
                                            List<String> sourceUrls,
                                            List<String> reasoningSteps,
                                            List<String> contradictions) {
        boolean hasContradiction = contradictions != null && !contradictions.isEmpty();
        String nextAction = hasContradiction ? "REQUIRE_HUMAN_REVIEW" : nextActionFor(coverageStatus);
        return FieldAnswerConclusion.builder()
                .field(field)
                .coverageStatus(coverageStatus)
                .answerValue(answerValueFor(field, coverageStatus))
                .sourceUrls(sourceUrls == null ? List.of() : List.copyOf(sourceUrls))
                .reasoningSteps(reasoningSteps == null ? List.of() : List.copyOf(reasoningSteps))
                .contradictions(contradictions == null ? List.of() : List.copyOf(contradictions))
                .confidence(hasContradiction ? 0.45D : confidenceFor(coverageStatus))
                .recommendedNextAction(nextAction)
                .build();
    }

    private String answerValueFor(String field, String coverageStatus) {
        if ("pricing".equalsIgnoreCase(field) && "CONFIRMED_FREE".equals(coverageStatus)) {
            return "公开证据支持该字段结论：当前能力免费或无独立收费项。";
        }
        if ("NO_PUBLIC_EVIDENCE_AFTER_SEARCH".equals(coverageStatus)) {
            return "";
        }
        return "公开证据支持该字段存在可追溯结论。";
    }

    private String nextActionFor(String coverageStatus) {
        if ("NO_PUBLIC_EVIDENCE_AFTER_SEARCH".equals(coverageStatus)) {
            return "DOWNGRADE_FIELD";
        }
        if ("EVIDENCE_PATH_COVERAGE_NOT_MET".equals(coverageStatus)) {
            return "REPAIR_WITH_TAVILY";
        }
        return "ACCEPT_CONCLUSION";
    }

    private double confidenceFor(String coverageStatus) {
        if ("CONFIRMED_FREE".equals(coverageStatus) || "TRACEABLE".equals(coverageStatus)) {
            return 0.82D;
        }
        if ("IMPLICIT_IN_DOCS".equals(coverageStatus)) {
            return 0.68D;
        }
        return 0.50D;
    }
}
```

- [ ] **步骤 4：运行答案合成测试**

运行：

```powershell
mvn -pl backend -Dtest=FieldAnswerSynthesizerTest test
```

预期：测试通过，并且结论包含 `sourceUrls / reasoningSteps / recommendedNextAction`。

### Task 5: Task66 分阶段回归

**文件：**
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/integration/Task66CoverageContractRegressionTest.java`

- [ ] **步骤 1：编写能力介绍模式回归**

创建 `Task66CoverageContractRegressionTest.java`：

```java
package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Task66CoverageContractRegressionTest {

    private final CoverageContractResolver resolver = new CoverageContractResolver();

    @Test
    void task66CapabilityIntroShouldNotBlockOnPricingOrWeaknesses() {
        CoverageContract contract = resolver.resolve(
                null,
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
                null);

        assertThat(contract.findField("coreFeatures").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
    }

    @Test
    void standardReportShouldStillBlockOnPricingAndWeaknesses() {
        CoverageContract contract = resolver.resolve(
                "STANDARD_COMPETITOR_REPORT",
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
                null);

        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
    }
}
```

- [ ] **步骤 2：运行回归测试**

运行：

```powershell
mvn -pl backend -Dtest=Task66CoverageContractRegressionTest test
```

预期：测试通过。

### Task 6: 阶段验证

**文件：**
- 不新增文件。

- [ ] **步骤 1：运行第 5 阶段测试**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest,SearchExecutionCoordinatorRepairAuditTest,FieldAnswerSynthesizerTest,Task66CoverageContractRegressionTest test
```

预期：所有测试通过。

- [ ] **步骤 2：运行 Tavily 分阶段回归集合**

运行：

```powershell
mvn -pl backend -Dtest=CoverageContractResolverTest,AnalysisDimensionMappingCatalogTest,CoverageContractProviderTest,WorkflowPlanCoverageContractTest,SchemaExtractorAgentCoverageContractTest,QualityReviewAgentCoverageContractTest,EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest,FieldEvidenceQueryPlannerTest,TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest,PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest,SearchExecutionCoordinatorRepairAuditTest,FieldAnswerSynthesizerTest,Task66CoverageContractRegressionTest test
```

预期：所有测试通过。
