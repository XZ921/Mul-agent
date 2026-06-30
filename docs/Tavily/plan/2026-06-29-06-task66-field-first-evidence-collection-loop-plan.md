# Task66-06 字段优先证据规划与 Tavily 多 Query 再采集闭环 Implementation Plan

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。本计划在 `master` 上直接修改，用户自行提交，执行过程不要创建提交。

**目标：** 在 05 已完成的 repair 状态、字段答案合成和回归底座上，真正实现字段驱动的多页证据采集与覆盖闭环：字段证据路径生成多条语义互补 Tavily query，Tavily 主链路逐条执行，采集后按字段路径评估覆盖缺口，缺口或 `REPAIR_QUERY_PROPOSED` 自动进入第二轮字段定向再采集。

**架构：** `CoverageContract` 继续作为权威字段契约，不重建。新增 `FieldEvidenceQueryPlanner` 把 `CoverageFieldContract / CoverageEvidencePath` 翻译为字段级查询任务，新增 `DimensionEvidencePlan / FieldEvidenceCoverage` 记录字段路径预算与执行状态。`ExecutionPlanDefinitionBuilder` 把字段计划写入 `CollectorNodeConfig`；`SearchExecutionCoordinator` 与 `TavilyFastLaneProvider` 消费字段 query 并保留逐 query 审计；`CollectorAgent` 聚合采集结果、更新字段覆盖，并在最大 2 轮内触发字段缺口再采集。

**Tech Stack:** Java 17、Spring Boot、Jackson、Lombok、JUnit 5、Mockito、AssertJ、现有 Tavily Fast Lane、现有 CoverageContract、现有 EvidenceQualityGate、现有 PublicEvidenceRecoveryService。

---

## 0. 06 的边界与验收口径

06 直接承接 `docs/Tavily/specs/2026-06-28-task66-coverage-contract-and-test-strategy-design.md` 与 `docs/Tavily/problem/2026-06-29-task66-planning-methodology-risks.md` 中被反复后移的根因能力，不再追加新的外围 gate。

06 必须交付的四块能力：

- `FieldEvidenceQueryPlanner`：输入 `fieldName / evidencePathKey / queryIntent / sourceTypes / competitorName / preferredDomains`，输出多条语义互补 Tavily query。
- `DimensionEvidencePlan`：把 `CoverageContract` 中 required 字段的 `evidencePaths / minimumAttemptedPaths / minDistinctEvidenceCount` 变成运行时采集预算。
- Tavily 多 query 真实执行：`searchQueries` 与字段 query 不能只取第一条，主链路必须逐条调用 Tavily，并记录每条 query 的命中、选中和丢弃原因。
- 覆盖闭环再入：采集后如果字段路径不足，或采集质量门禁产生 `REPAIR_QUERY_PROPOSED`，系统自动对未完成字段路径执行第二轮字段定向搜索、验证、采集、再评估。

06 不承诺“真实互联网 100% 都能找到证据”。06 承诺的是工程闭环可 100% 验收：系统会按字段路径执行多 query、多页采集和再评估；如果公开资料仍不存在，必须输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 或明确失败原因，而不是静默把弱入口页当强证据。

### 0.1 方法论红线自检（对齐 `2026-06-29-task66-planning-methodology-risks.md`）

本计划在动工前必须显式回答 risks 文档第 5 节的五条红线，避免再次出现“症状修完了，根因没碰”：

- **红线一（已有更优策略不能只修症状）**：`docs/Tavily/tavily-search-test-summary.md` 已证明 3 条互补 query 带来更高 URL/域名多样性。06 把该策略纳入交付范围（Task 1 planner + Task 5 主链路逐条执行），不是只做失败治理。
- **红线二（根因能力不能无限后移）**：`FieldEvidenceQueryPlanner / DimensionEvidencePlan` 在 03→04→05 被反复后移，06 把它们作为 Task 1/2 的 P0 交付物，不再后移。
- **红线三（新增复杂度必须配对检查主成功率）**：本计划除审计/状态/再入闭环外，**核心目的就是提成功率**——field-first 多 query 直接改善 query 质量、命中质量和来源多样性，而非仅“更体面地失败”。Task 5 的来源多样性断言与 Task 1 的多角度 query 断言是这一目标的可验收证据。
- **红线四（区分配置层/运行时/主执行链三层）**：每个相关 Task 的验收标签已显式标注属于哪一层，见下表 0.2。
- **红线五（必须回答“为什么会搜到这些东西”）**：见 0.3 对搜索层 recovery 门控的处置。

### 0.2 三层能力交付对照（红线四自检）

| 能力 | 配置层 | 运行时携带 | 主执行链消费 |
|------|--------|-----------|-------------|
| 字段 query | Task 3（写入 CollectorNodeConfig.dimensionEvidencePlan） | Task 4（SearchSourceRequest/SourceCandidate/TavilyProfile 透传字段元数据） | Task 5/6（TavilyFastLane 逐条执行 + Coordinator 生成字段候选） |
| 字段证据预算 | Task 2/3（DimensionEvidencePlan 写入 config） | Task 6（buildSearchSourceRequest 携带） | Task 8（CollectorAgent 按预算判定再入） |
| repair 字段路径完成态 | Task 7（REPAIR_FIELD_PATH_COMPLETED 枚举） | Task 7（FieldEvidenceCoverage 携带） | Task 8（再入闭环消费并落 metadata） |

任何 Task 只完成左侧两列、未完成“主执行链消费”列，都不算该能力交付完成。

### 0.3 搜索层 recovery 门控的显式处置（红线五）

risks 文档 4.2/4.3/4.5 把 `SearchExecutionCoordinator.shouldTriggerPublicEvidenceRecovery` 的 `hasVerifiedCandidate → return false` 短路列为 live bilibili 失败的直接原因之一：下载中心页“验证通过但浅”，搜索层因此不再按字段深挖。

工程现状核实（2026-06-29，行号以当前代码为准，risks 文档引用的 1366 已过期）：

- 短路逻辑当前位于 [SearchExecutionCoordinator.java:1422-1426](backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java#L1422-L1426)：`hasVerifiedCandidate` 为 true 时**先于** `recoveryFieldName / recoveryEvidencePathKey / recoveryQueryIntents` 判定直接 return false。
- 这意味着即便 06 已经把字段计划写进 config，只要第一轮主链路命中一个 verified 弱入口候选，搜索层可能在两个位置提前停下：`shouldSupplement(...)` 因 verified 数量达标跳过 HTTP/Tavily supplement，或 `shouldTriggerPublicEvidenceRecovery(...)` 因 verified 候选短路跳过公开证据兜底。字段 query 如果没有进入 `buildSearchSourceRequest(...).fieldEvidenceQueries(...)`，Task 8 的 CollectorAgent 再入只能兜底，不能证明第一轮多 query 能力已经生效。

06 的处置（Task 6 Step 2 落地，不留作隐含）：

- **不删除 `hasVerifiedCandidate` 短路**，因为它对 04“显式候选可安全入选、不被公网噪音补源覆盖”仍是必要保护。
- **先打开字段 supplement 正门**：当 config 携带 `dimensionEvidencePlan` 且存在未达 `minimumAttemptedPaths` 的 required 字段路径时，`shouldSupplement(...)` 不能只因 `verifiedCount >= minVerifiedCount` 跳过 HTTP/Tavily supplement。字段多 query 的主执行器是 `buildSearchSourceRequest(...).fieldEvidenceQueries(...) -> TavilyFastLaneProvider.searchFieldEvidenceQueries(...)`，不是 `PublicEvidenceRecoveryService`。
- **再收紧 public recovery 兜底短路**：`shouldTriggerPublicEvidenceRecovery(...)` 只负责“弱入口/受限页兜底”，不负责执行字段多 query。当已有 verified 候选缺少字段证据元数据、且字段预算仍未完成时，不能无条件 return false；但如果 verified 候选已经带有 `fieldName/evidencePathKey/fieldEvidenceQueryFingerprint/sourceUrls`，则不应继续 public recovery，避免新 plan 天然 unmet 导致无限补采。
- 如果本阶段评估认为改动搜索层主路径风险过高、需独立验收，则必须在此显式声明“第一轮不修门控、字段 recovery 仅由 Task 8 采集层再入兜底”，并把搜索层门控收紧拆到已命名的 07 计划——不允许默认它已被解决。**本计划选择前者（Task 6 收紧门控）**，理由是 risks 红线二禁止把已识别根因再次后移。

---

## 1. 当前 05 后工程事实

- 已存在：`CoverageContract / CoverageFieldContract / CoverageEvidencePath`，字段契约已含 `evidencePaths / minimumAttemptedPaths / minDistinctEvidenceCount / queryIntents / sourceTypes`。
- 已存在：`EvidenceRepairState / EvidenceRepairPlan`，但状态缺少字段路径级完成态 `REPAIR_FIELD_PATH_COMPLETED`。
- 已存在：`FieldAnswerConclusion / FieldAnswerSynthesizer`，可承接字段结论合成。
- 已存在：`CollectorAgent` 采集后质量门禁和 repair metadata 写入，但 repair plan 仍只是 metadata，不会自动再入采集。
- 已存在：`TavilyFastLaneProvider`，但普通模式下 `request.searchQueries` 只取第一条覆盖 primary query；`EVIDENCE_REPAIR` 模式也经由 `TavilySearchProfileResolver.firstNonBlank` 只消费第一条 suggested query。
- 已存在：`ExecutionPlanDefinitionBuilder.queryIntentsForSourcePlan(...)`，但它只扁平化 query intent，没有输出字段路径级执行计划。
- 已存在但需收紧：`SearchExecutionCoordinator.shouldTriggerPublicEvidenceRecovery`（[SearchExecutionCoordinator.java:1419-1440](backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java#L1419-L1440)）在 `hasVerifiedCandidate=true` 时先于字段 recovery 判定直接 return false。弱入口被验证通过即短路，字段 query 无法在第一轮被搜索层消费。06 在 Task 6 收紧此门控，详见 0.3。
- live 样本 `docs/superpowers/ExtractionStructured/progress/live-bilibili-public-evidence-recovery-20260629-174119-rerun/create-request.json` 证明标准版报告 + `https://app.bilibili.com` 弱入口会卡在浅页面，必须字段优先深挖至少 `coreFeatures / pricing`。

---

## 2. 文件结构

- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQuery.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlanner.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlan.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverage.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageStatus.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java`
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregator.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContent.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairState.java`
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairPlan.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlannerTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactoryTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregatorTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanFieldEvidencePlanTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentFieldEvidenceLoopTest.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/integration/Task66FieldFirstEvidenceLoopSystemTest.java`
- 新建测试资源：`backend/src/test/resources/task66/field-first-capability-intro-request.json`
- 新建测试资源：`backend/src/test/resources/task66/field-first-standard-bilibili-shallow-request.json`
- 新建进度记录：`docs/Tavily/progress/2026-06-29-task66-06-field-first-evidence-loop-progress.md`

---

### Task 1: 字段级 Tavily Query 模型与规划器

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQuery.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlanner.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceQueryPlannerTest.java`

- [ ] **Step 1: 编写 failing test，验证 coreFeatures 字段路径生成多条语义互补 query**

创建 `FieldEvidenceQueryPlannerTest.java`，先覆盖 `coreFeatures / DOCS_API_GUIDE`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceQueryPlannerTest {

    private final FieldEvidenceQueryPlanner planner = new FieldEvidenceQueryPlanner();

    @Test
    void shouldPlanMultipleSemanticQueriesForCoreFeatureDocsPath() {
        CoverageFieldContract field = CoverageFieldContract.builder()
                .field("coreFeatures")
                .status(CoverageFieldStatus.REQUIRED)
                .evidencePaths(List.of(CoverageEvidencePath.builder()
                        .pathKey("DOCS_API_GUIDE")
                        .sourceTypes(List.of("DOCS", "OFFICIAL"))
                        .queryIntents(List.of("API_DOCS", "SDK_GUIDE"))
                        .expectedSignals(List.of("DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"))
                        .required(true)
                        .build()))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(2)
                .build();

        List<FieldEvidenceQuery> queries = planner.plan(
                "哔哩哔哩",
                field,
                List.of("open.bilibili.com"));

        assertThat(queries).hasSizeGreaterThanOrEqualTo(4);
        assertThat(queries).extracting(FieldEvidenceQuery::getFieldName).containsOnly("coreFeatures");
        assertThat(queries).extracting(FieldEvidenceQuery::getEvidencePathKey).containsOnly("DOCS_API_GUIDE");
        assertThat(queries).extracting(FieldEvidenceQuery::getQueryIntent)
                .contains("API_DOCS", "SDK_GUIDE");
        assertThat(queries).extracting(FieldEvidenceQuery::getQuery)
                .anySatisfy(query -> assertThat(query).contains("开放平台").contains("API").contains("官方文档"))
                .anySatisfy(query -> assertThat(query).contains("SDK").contains("接入"))
                .anySatisfy(query -> assertThat(query).contains("site:open.bilibili.com").contains("API"));
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.getReason()).isNotBlank();
            assertThat(query.getQueryFingerprint()).isNotBlank();
        });
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerTest test
```

Expected: FAIL，提示 `FieldEvidenceQuery` 或 `FieldEvidenceQueryPlanner` 不存在。

- [ ] **Step 2: 编写 failing test，验证 standard pricing 生成多路径 query**

追加测试：

```java
@Test
void shouldPlanPricingQueriesAcrossOfficialBillingAndTermsPaths() {
    CoverageFieldContract pricing = CoverageFieldContract.builder()
            .field("pricing")
            .status(CoverageFieldStatus.REQUIRED)
            .evidencePaths(List.of(
                    CoverageEvidencePath.builder()
                            .pathKey("OFFICIAL_PRICING_PAGE")
                            .sourceTypes(List.of("PRICING", "OFFICIAL"))
                            .queryIntents(List.of("OFFICIAL_PRICING"))
                            .expectedSignals(List.of("PRICING_BLOCK"))
                            .required(true)
                            .build(),
                    CoverageEvidencePath.builder()
                            .pathKey("DOCS_BILLING_OR_LIMITS")
                            .sourceTypes(List.of("DOCS", "OFFICIAL"))
                            .queryIntents(List.of("DOCS_BILLING"))
                            .expectedSignals(List.of("PRICING_BLOCK", "LIMITATION_OR_POLICY_BLOCK"))
                            .required(true)
                            .build()))
            .minimumAttemptedPaths(2)
            .minDistinctEvidenceCount(2)
            .build();

    List<FieldEvidenceQuery> queries = planner.plan(
            "哔哩哔哩",
            pricing,
            List.of("open.bilibili.com", "www.bilibili.com"));

    assertThat(queries).hasSizeGreaterThanOrEqualTo(5);
    assertThat(queries).extracting(FieldEvidenceQuery::getEvidencePathKey)
            .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
    assertThat(queries).extracting(FieldEvidenceQuery::getQuery)
            .anySatisfy(query -> assertThat(query).contains("定价").contains("套餐").contains("收费"))
            .anySatisfy(query -> assertThat(query).contains("API").contains("调用限制"))
            .anySatisfy(query -> assertThat(query).contains("服务协议").contains("计费"));
}
```

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerTest test
```

Expected: FAIL，同样因为实现不存在。

- [ ] **Step 3: 新增字段 query DTO**

创建 `FieldEvidenceQuery.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段级证据查询任务。
 * 该对象把“为了哪个字段、哪条证据路径、哪个 query intent 发起 Tavily 查询”记录下来，
 * 避免运行期只有一串 query 文本而丢失字段覆盖语义。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldEvidenceQuery {

    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;
    private String sourceType;
    private String query;
    private String reason;
    private String queryFingerprint;
    private Integer priority;

    @Builder.Default
    private List<String> includeDomains = new ArrayList<>();
}
```

- [ ] **Step 4: 新增字段 query planner 最小实现**

创建 `FieldEvidenceQueryPlanner.java`，实现以下规则：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字段证据查询规划器。
 * 它只负责把字段契约翻译成可执行查询任务，不调用 Tavily，也不判断页面是否可用。
 */
@Component
public class FieldEvidenceQueryPlanner {

    public List<FieldEvidenceQuery> plan(String competitorName,
                                         CoverageFieldContract field,
                                         List<String> preferredDomains) {
        if (field == null || !StringUtils.hasText(field.getField())) {
            return List.of();
        }
        List<CoverageEvidencePath> paths = field.getEvidencePaths() == null
                ? List.of()
                : field.getEvidencePaths();
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<String, FieldEvidenceQuery> deduplicated = new LinkedHashMap<>();
        int priority = 0;
        for (CoverageEvidencePath path : paths) {
            if (path == null || !path.isRequired()) {
                continue;
            }
            List<String> queryIntents = defaultIfEmpty(path.getQueryIntents(), field.getQueryIntents());
            List<String> sourceTypes = defaultIfEmpty(path.getSourceTypes(), List.of("OPEN_WEB"));
            for (String queryIntent : queryIntents) {
                for (String sourceType : sourceTypes) {
                    for (String query : buildQueries(competitorName, field.getField(), path.getPathKey(), queryIntent, preferredDomains)) {
                        FieldEvidenceQuery planned = buildQuery(field.getField(), path.getPathKey(), queryIntent, sourceType,
                                query, preferredDomains, priority++);
                        deduplicated.putIfAbsent(planned.getQueryFingerprint(), planned);
                    }
                }
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private FieldEvidenceQuery buildQuery(String fieldName,
                                          String pathKey,
                                          String queryIntent,
                                          String sourceType,
                                          String query,
                                          List<String> preferredDomains,
                                          int priority) {
        String fingerprintInput = fieldName + "|" + pathKey + "|" + queryIntent + "|" + sourceType + "|" + query;
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .evidencePathKey(pathKey)
                .queryIntent(queryIntent)
                .sourceType(sourceType)
                .query(query)
                .reason("字段 " + fieldName + " 的证据路径 " + pathKey + " 需要执行 " + queryIntent + " 查询")
                .queryFingerprint(DigestUtils.md5DigestAsHex(fingerprintInput.getBytes(StandardCharsets.UTF_8)))
                .priority(priority)
                .includeDomains(resolveIncludeDomains(query, preferredDomains))
                .build();
    }

    private List<String> buildQueries(String competitorName,
                                      String fieldName,
                                      String pathKey,
                                      String queryIntent,
                                      List<String> preferredDomains) {
        String name = StringUtils.hasText(competitorName) ? competitorName.trim() : "";
        String normalizedField = normalize(fieldName);
        String normalizedPath = normalize(pathKey);
        String normalizedIntent = normalize(queryIntent);
        LinkedHashSet<String> queries = new LinkedHashSet<>();

        if ("COREFEATURES".equals(normalizedField) || "DOCS_API_GUIDE".equals(normalizedPath)) {
            queries.add(name + " 开放平台 API 官方文档");
            queries.add(name + " 开放平台 SDK 接入指南");
            queries.add(name + " 开发者文档 授权管理 用户管理 API");
            for (String domain : preferredDomains == null ? List.<String>of() : preferredDomains) {
                if (StringUtils.hasText(domain)) {
                    queries.add("site:" + domain.trim() + " API SDK 文档");
                }
            }
            return new ArrayList<>(queries);
        }

        if ("PRICING".equals(normalizedField) || "OFFICIAL_PRICING_PAGE".equals(normalizedPath)) {
            queries.add(name + " 定价 套餐 收费 官方");
            queries.add(name + " 开放平台 收费 免费 计费 官方");
        }
        if ("PRICING".equals(normalizedField) || "DOCS_BILLING_OR_LIMITS".equals(normalizedPath)) {
            queries.add(name + " API 调用限制 计费 免费 文档");
            queries.add(name + " 服务协议 计费 收费 条款");
            for (String domain : preferredDomains == null ? List.<String>of() : preferredDomains) {
                if (StringUtils.hasText(domain)) {
                    queries.add("site:" + domain.trim() + " billing pricing docs API");
                }
            }
        }
        if (!queries.isEmpty()) {
            return new ArrayList<>(queries);
        }

        queries.add(name + " " + fieldName + " " + queryIntent + " 官方资料");
        return new ArrayList<>(queries);
    }

    private List<String> resolveIncludeDomains(String query, List<String> preferredDomains) {
        if (!StringUtils.hasText(query) || preferredDomains == null || preferredDomains.isEmpty()) {
            return List.of();
        }
        if (!query.contains("site:")) {
            return List.of();
        }
        return preferredDomains.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> defaultIfEmpty(List<String> values, List<String> fallback) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        return normalized.isEmpty() ? (fallback == null ? List.of() : fallback) : normalized;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
```

- [ ] **Step 5: 运行 planner 测试**

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceQueryPlannerTest test
```

Expected: PASS。若重复 query 导致数量不足，优先调整 planner 的去重粒度，不能把断言降成只验证 1 条 query。

> **MVP 边界声明（必读，对齐 risks 红线二）：**
>
> 本步骤的 `FieldEvidenceQueryPlanner.buildQueries(...)` 采用 **if-field 硬编码**：仅 `coreFeatures / pricing` 两类字段有真实互补 query，其余字段全部落入兜底分支 `name + field + intent + "官方资料"`，**只产 1 条 query**。
>
> 这是**已声明、已排期的 MVP 占位**，不是最终能力：
>
> - **当前代价**：07 交付前，`coreFeatures / pricing` 之外的任意字段（如 `positioning / targetUsers / strengths`）在 06 里只得到单条兜底 query，等同退回 problem 2 谴责的 1-query 形态。06 的多 query 能力此时只在这两个字段上成立。
> - **不掩盖**：06 的"字段驱动"动的是**执行骨架**（多 query 逐条执行、覆盖闭环、门控收紧）；"根据字段语义生成互补 query 的能力"**未在 06 建成**，仍是模板拼接的搬家 + 两字段打样。
> - **接茬计划（不后移、已命名）**：生成式 query planner 拆至 **`docs/Tavily/plan/2026-06-29-07-task66-generative-field-query-planner-plan.md`**。07 的定位是"Tavily 天花板收口"——让系统自己从 `queryIntents + expectedSignals + sourceTypes + dimension` 组合生成互补 query、自动决策 include_domains、按正文可用性评分，删除本步骤的 if-field 分支。07 达成即 Tavily 目录目的达成，采集链路到此站上天花板、停止深修。
>
> 执行 06 时**不得**因为本占位"看起来能跑两字段"而宣称 field-first query 生成已完成；该能力的完成判据写在 07 的 Definition of Done。

---

### Task 2: 字段证据计划与覆盖状态模型

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlan.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverage.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageStatus.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactory.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/DimensionEvidencePlanFactoryTest.java`

- [ ] **Step 1: 编写字段计划 factory 测试**

创建 `DimensionEvidencePlanFactoryTest.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionEvidencePlanFactoryTest {

    private final DimensionEvidencePlanFactory factory =
            new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner());

    @Test
    void shouldCreatePlanOnlyForRequiredFieldsWithEvidencePaths() {
        CoverageContract contract = new CoverageContractResolver(new AnalysisDimensionMappingCatalog())
                .resolve("标准版", List.of("产品功能"), List.of("官网", "产品文档"), null);

        DimensionEvidencePlan plan = factory.create("哔哩哔哩", contract, List.of("open.bilibili.com"));

        assertThat(plan.getCompetitorName()).isEqualTo("哔哩哔哩");
        assertThat(plan.getContractVersion()).isEqualTo(contract.getContractVersion());
        assertThat(plan.findField("coreFeatures")).isPresent();
        assertThat(plan.findField("pricing")).isPresent();
        assertThat(plan.findField("pricing").orElseThrow().getMinimumAttemptedPaths()).isEqualTo(2);
        assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(plan.getFieldCoverages()).allSatisfy(field -> {
            assertThat(field.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.NOT_STARTED);
            assertThat(field.getAttemptedPaths()).isEmpty();
        });
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=DimensionEvidencePlanFactoryTest test
```

Expected: FAIL，因为计划模型不存在。

- [ ] **Step 2: 新增覆盖状态枚举**

创建 `FieldEvidenceCoverageStatus.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

/**
 * 字段级证据覆盖状态。
 * 状态只描述字段证据路径是否达标，不替代字段答案的自然语言结论。
 */
public enum FieldEvidenceCoverageStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUFFICIENT,
    EVIDENCE_PATH_COVERAGE_NOT_MET,
    NO_PUBLIC_EVIDENCE_AFTER_SEARCH
}
```

- [ ] **Step 3: 新增字段覆盖模型**

创建 `FieldEvidenceCoverage.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个字段的证据路径执行状态。
 * 它记录已尝试路径、已完成路径和字段级 query，作为闭环再入的判断依据。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldEvidenceCoverage {

    private String fieldName;
    private FieldEvidenceCoverageStatus status;
    private Integer minimumAttemptedPaths;
    private Integer minDistinctEvidenceCount;

    @Builder.Default
    private List<CoverageEvidencePath> evidencePaths = new ArrayList<>();

    @Builder.Default
    private List<String> attemptedPaths = new ArrayList<>();

    @Builder.Default
    private List<String> completedPaths = new ArrayList<>();

    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();

    @Builder.Default
    private List<FieldEvidenceQuery> plannedQueries = new ArrayList<>();

    private String lastRepairState;
    private String recommendedNextAction;

    /**
     * 判断当前字段是否已经有字段级候选或采集结果命中过。
     * 这个标记用于区分“fresh plan 还没执行”与“字段路径确实补采后仍不足”，避免 verified 弱入口反复触发兜底。
     */
    public boolean hasFieldEvidenceSignal() {
        return (attemptedPaths != null && !attemptedPaths.isEmpty())
                || (completedPaths != null && !completedPaths.isEmpty())
                || (sourceUrls != null && !sourceUrls.isEmpty());
    }
}
```

- [ ] **Step 4: 新增维度证据计划模型**

创建 `DimensionEvidencePlan.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 维度证据计划。
 * 这是 Collector 节点消费的字段级采集预算快照，来自 CoverageContract，但比契约更偏运行态。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DimensionEvidencePlan {

    private String competitorName;
    private String contractVersion;
    private Integer maxCollectionRounds;

    @Builder.Default
    private List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();

    public Optional<FieldEvidenceCoverage> findField(String fieldName) {
        if (!StringUtils.hasText(fieldName) || fieldCoverages == null) {
            return Optional.empty();
        }
        return fieldCoverages.stream()
                .filter(field -> field != null && fieldName.equalsIgnoreCase(field.getFieldName()))
                .findFirst();
    }

    public List<FieldEvidenceQuery> allPlannedQueries() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return List.of();
        }
        return fieldCoverages.stream()
                .flatMap(field -> field.getPlannedQueries() == null
                        ? java.util.stream.Stream.empty()
                        : field.getPlannedQueries().stream())
                .toList();
    }

    /**
     * 是否仍存在未达 minimumAttemptedPaths 的字段路径。
     * 搜索层用它打开字段 Tavily supplement；public recovery 还必须结合候选是否缺少字段证据信号，不能只看 fresh plan。
     */
    public boolean hasUnmetRequiredFieldPath() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return false;
        }
        return fieldCoverages.stream().anyMatch(field -> {
            if (field == null) {
                return false;
            }
            int minimum = field.getMinimumAttemptedPaths() == null ? 1 : field.getMinimumAttemptedPaths();
            int completed = field.getCompletedPaths() == null ? 0 : field.getCompletedPaths().size();
            return completed < minimum;
        });
    }

    /**
     * 是否存在需要进入 HTTP/Tavily 字段 supplement 的查询。
     * 只要字段路径未满足且仍有 plannedQueries，就允许第一轮补源执行字段多 query。
     */
    public boolean hasPendingFieldEvidenceQueries() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return false;
        }
        return fieldCoverages.stream().anyMatch(field -> field != null
                && field.getPlannedQueries() != null
                && !field.getPlannedQueries().isEmpty()
                && !isFieldCoverageSatisfied(field));
    }

    private boolean isFieldCoverageSatisfied(FieldEvidenceCoverage field) {
        int minimum = field.getMinimumAttemptedPaths() == null ? 1 : field.getMinimumAttemptedPaths();
        int completed = field.getCompletedPaths() == null ? 0 : field.getCompletedPaths().size();
        return completed >= minimum;
    }
}
```

- [ ] **Step 5: 新增计划 factory**

创建 `DimensionEvidencePlanFactory.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段证据计划工厂。
 * 它把 CoverageContract 中的字段契约转换为 Collector 可直接消费的运行态计划。
 */
@Component
public class DimensionEvidencePlanFactory {

    private final FieldEvidenceQueryPlanner queryPlanner;

    public DimensionEvidencePlanFactory(FieldEvidenceQueryPlanner queryPlanner) {
        this.queryPlanner = queryPlanner == null ? new FieldEvidenceQueryPlanner() : queryPlanner;
    }

    public DimensionEvidencePlan create(String competitorName,
                                        CoverageContract contract,
                                        List<String> preferredDomains) {
        List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();
        if (contract != null && contract.getFields() != null) {
            for (CoverageFieldContract field : contract.getFields()) {
                if (!shouldPlan(field)) {
                    continue;
                }
                List<FieldEvidenceQuery> queries = queryPlanner.plan(competitorName, field, preferredDomains);
                if (queries.isEmpty()) {
                    continue;
                }
                fieldCoverages.add(FieldEvidenceCoverage.builder()
                        .fieldName(field.getField())
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(field.getMinimumAttemptedPaths())
                        .minDistinctEvidenceCount(field.getMinDistinctEvidenceCount())
                        .evidencePaths(field.getEvidencePaths() == null ? List.of() : field.getEvidencePaths())
                        .attemptedPaths(List.of())
                        .completedPaths(List.of())
                        .sourceUrls(List.of())
                        .plannedQueries(queries)
                        .recommendedNextAction("EXECUTE_FIELD_EVIDENCE_QUERIES")
                        .build());
            }
        }
        return DimensionEvidencePlan.builder()
                .competitorName(competitorName)
                .contractVersion(contract == null ? null : contract.getContractVersion())
                .maxCollectionRounds(2)
                .fieldCoverages(fieldCoverages)
                .build();
    }

    private boolean shouldPlan(CoverageFieldContract field) {
        return field != null
                && StringUtils.hasText(field.getField())
                && field.getStatus() == CoverageFieldStatus.REQUIRED
                && field.getEvidencePaths() != null
                && !field.getEvidencePaths().isEmpty()
                && field.getEvidencePaths().stream().anyMatch(CoverageEvidencePath::isRequired);
    }
}
```

- [ ] **Step 6: 运行字段计划测试**

Run:

```powershell
mvn -pl backend -Dtest=DimensionEvidencePlanFactoryTest,FieldEvidenceQueryPlannerTest test
```

Expected: PASS。

---

### Task 3: 把字段证据计划写入 Collector 节点配置

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/ExecutionPlanDefinitionBuilder.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowPlanFieldEvidencePlanTest.java`

- [ ] **Step 1: 编写 workflow plan 测试**

创建 `WorkflowPlanFieldEvidencePlanTest.java`，验证配置层不只存 query intent，还存字段计划：

```java
package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import cn.bugstack.competitoragent.workflow.coverage.AnalysisDimensionMappingCatalog;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlanFactory;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQueryPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlanFieldEvidencePlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void collectorNodeConfigShouldCarryDimensionEvidencePlan() throws Exception {
        AnalysisTask task = new AnalysisTask();
        task.setCompetitorNames(objectMapper.writeValueAsString(List.of("哔哩哔哩")));
        task.setCompetitorUrls(objectMapper.writeValueAsString(List.of("https://app.bilibili.com")));
        task.setSourceScope(objectMapper.writeValueAsString(List.of("官网", "产品文档")));
        task.setAnalysisDimensions(objectMapper.writeValueAsString(List.of("产品功能", "目标用户", "市场定位", "证据完整性")));
        task.setReportTemplate("标准版");

        SourceDiscoveryService sourceDiscoveryService = mock(SourceDiscoveryService.class);
        when(sourceDiscoveryService.discoverForPreview(any(), any(), any())).thenReturn(List.of(SourcePlan.builder()
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .sourceFamilyRole("PRIMARY_VERTICAL")
                .urls(List.of("https://app.bilibili.com"))
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://app.bilibili.com")
                        .domain("app.bilibili.com")
                        .sourceType("OFFICIAL")
                        .build()))
                .build()));
        CollectorPlanTemplateFactory collectorPlanTemplateFactory = mock(CollectorPlanTemplateFactory.class);
        when(collectorPlanTemplateFactory.createCollectorNodeConfig(any(), any(), any(), any()))
                .thenReturn(CollectorNodeConfig.builder()
                        .competitorName("哔哩哔哩")
                        .competitorUrls(List.of("https://app.bilibili.com"))
                        .sourceType("DOCS")
                        .sourceCandidates(List.of())
                        .build());

        ExecutionPlanDefinitionBuilder builder = new ExecutionPlanDefinitionBuilder(
                null,
                sourceDiscoveryService,
                new cn.bugstack.competitoragent.source.SourceCandidateRanker(),
                objectMapper,
                collectorPlanTemplateFactory,
                new CoverageContractResolver(new AnalysisDimensionMappingCatalog()),
                new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner())
        );

        ExecutionPlanDefinition definition = builder.build(task, true);
        ExecutionPlanDefinition.NodeDefinition collector = definition.getNodes().stream()
                .filter(node -> "COLLECTOR".equals(node.getAgentType()))
                .findFirst()
                .orElseThrow();
        CollectorNodeConfig config = objectMapper.readValue(collector.getNodeConfig(), CollectorNodeConfig.class);

        DimensionEvidencePlan fieldPlan = config.getDimensionEvidencePlan();
        assertThat(fieldPlan).isNotNull();
        assertThat(fieldPlan.findField("coreFeatures")).isPresent();
        assertThat(fieldPlan.findField("pricing")).isPresent();
        assertThat(fieldPlan.findField("pricing").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(5);
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=WorkflowPlanFieldEvidencePlanTest test
```

Expected: FAIL。构造器签名和 `dimensionEvidencePlan` 字段尚未存在。

- [ ] **Step 2: 扩展 CollectorNodeConfig**

在 `CollectorNodeConfig` 增加字段并放入 `@JsonPropertyOrder`：

```java
private DimensionEvidencePlan dimensionEvidencePlan;
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
```

`@JsonPropertyOrder` 中放在 `"coverageQueryIntents"` 后面：

```java
"dimensionEvidencePlan",
```

- [ ] **Step 3: 修改 ExecutionPlanDefinitionBuilder 注入字段计划工厂**

给 `ExecutionPlanDefinitionBuilder` 增加构造参数和字段：

```java
private final DimensionEvidencePlanFactory dimensionEvidencePlanFactory;
```

在已有 `collectorNodeConfig.setCoverageQueryIntents(...)` 后追加：

```java
collectorNodeConfig.setDimensionEvidencePlan(dimensionEvidencePlanFactory.create(
        competitorName,
        coverageContract,
        preferredDomainsForFieldEvidence(providedUrls, sourcePlan)
));
```

新增 helper：

```java
private List<String> preferredDomainsForFieldEvidence(List<String> providedUrls, SourcePlan sourcePlan) {
    LinkedHashSet<String> domains = new LinkedHashSet<>();
    for (String url : defaultIfEmpty(providedUrls, List.of())) {
        extractHost(url).ifPresent(domains::add);
    }
    if (sourcePlan != null && sourcePlan.getCandidates() != null) {
        for (SourceCandidate candidate : sourcePlan.getCandidates()) {
            extractHost(candidate == null ? null : candidate.getUrl()).ifPresent(domains::add);
        }
    }
    return new ArrayList<>(domains);
}

private Optional<String> extractHost(String url) {
    if (!StringUtils.hasText(url)) {
        return Optional.empty();
    }
    try {
        java.net.URI uri = java.net.URI.create(url.trim());
        return StringUtils.hasText(uri.getHost()) ? Optional.of(uri.getHost()) : Optional.empty();
    } catch (Exception ignored) {
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 修复构造器调用点**

`ExecutionPlanDefinitionBuilder` 使用 Lombok `@RequiredArgsConstructor`，新增 final 字段后 Spring 自动注入。测试中手动构造处必须补上：

```java
new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner())
```

项目里搜索手动构造调用：

```powershell
rg -n "new ExecutionPlanDefinitionBuilder" backend/src/test/java backend/src/main/java
```

逐一补齐新参数，不能把 `dimensionEvidencePlanFactory` 设为 `null`。

- [ ] **Step 5: 运行 workflow plan 测试**

Run:

```powershell
mvn -pl backend -Dtest=WorkflowPlanFieldEvidencePlanTest,WorkflowPlanCoverageContractTest test
```

Expected: PASS。若 mock `SourceDiscoveryService` 导致没有 collector node，就在测试里返回一个最小 `SourcePlan`，不能删除字段计划断言。

---

### Task 4: 搜索请求、候选与 Tavily profile 携带字段元数据

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchSourceRequest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfile.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilyPrefetchedContent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`

- [ ] **Step 1: 编写 metadata 透传测试**

追加到 `TavilyFastLaneProviderTest`：

```java
@Test
void shouldCarryFieldEvidenceMetadataFromRequestToCandidateAndPrefetchedContent() {
    TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
    StubTavilySearchClient client = new StubTavilySearchClient();
    client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
            .query("哔哩哔哩 开放平台 API 官方文档")
            .requestId("req-field-1")
            .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                    .title("用户管理 API")
                    .url("https://open.bilibili.com/doc/4/feb66f99")
                    .content("用户管理 API 文档")
                    .rawContent("用户管理 API 文档 raw content")
                    .score(0.86D)
                    .build()))
            .build());

    TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
            properties(),
            client,
            new TavilySearchProfileResolver(properties()),
            registry,
            new ObjectMapper()
    );

    List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
            .competitorName("哔哩哔哩")
            .requestedScopes(List.of("DOCS"))
            .fieldEvidenceQueries(List.of(FieldEvidenceQuery.builder()
                    .fieldName("coreFeatures")
                    .evidencePathKey("DOCS_API_GUIDE")
                    .queryIntent("API_DOCS")
                    .sourceType("DOCS")
                    .query("哔哩哔哩 开放平台 API 官方文档")
                    .queryFingerprint("field-query-1")
                    .reason("核心功能 API 文档路径")
                    .build()))
            .preferredProviderKey("tavily")
            .build());

    assertThat(candidates).hasSize(1);
    SourceCandidate candidate = candidates.get(0);
    assertThat(candidate.getFieldName()).isEqualTo("coreFeatures");
    assertThat(candidate.getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
    assertThat(candidate.getQueryIntent()).isEqualTo("API_DOCS");
    assertThat(candidate.getFieldEvidenceQueryFingerprint()).isEqualTo("field-query-1");
    assertThat(candidate.getTavilyQuery()).isEqualTo("哔哩哔哩 开放平台 API 官方文档");
}
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
```

Run:

```powershell
mvn -pl backend -Dtest=TavilyFastLaneProviderTest#shouldCarryFieldEvidenceMetadataFromRequestToCandidateAndPrefetchedContent test
```

Expected: FAIL，因为字段元数据尚未透传。

- [ ] **Step 2: 扩展 SearchSourceRequest**

在 `SearchSourceRequest` 添加：

```java
private String fieldName;
private String evidencePathKey;
private String queryIntent;

@Builder.Default
private List<FieldEvidenceQuery> fieldEvidenceQueries = new ArrayList<>();
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
```

- [ ] **Step 3: 扩展 SourceCandidate**

在 `SourceCandidate` 添加字段：

```java
private String fieldName;
private String evidencePathKey;
private String queryIntent;
private String fieldEvidenceQueryFingerprint;
private String fieldEvidenceQueryReason;
```

- [ ] **Step 4: 扩展 TavilySearchProfile 与 TavilyPrefetchedContent**

在 `TavilySearchProfile` 添加：

```java
private String fieldName;
private String evidencePathKey;
private String queryIntent;
private String fieldEvidenceQueryFingerprint;
private String fieldEvidenceQueryReason;
```

在 `TavilyPrefetchedContent` 添加同样字段。字段值从 profile 透传，确保 registry 回放时仍能解释该 raw_content 属于哪个字段路径。

- [ ] **Step 5: 运行 metadata 测试**

Run:

```powershell
mvn -pl backend -Dtest=TavilyFastLaneProviderTest#shouldCarryFieldEvidenceMetadataFromRequestToCandidateAndPrefetchedContent test
```

Expected: FAIL 变为具体断言失败，说明 DTO 已存在但 provider 尚未填充。这个失败留给 Task 5 修复。

---

### Task 5: Tavily Fast Lane 多 query 真实执行

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/source/TavilyFastLaneProviderTest.java`

- [ ] **Step 1: 编写多 query 执行测试**

追加到 `TavilyFastLaneProviderTest`：

```java
@Test
void shouldExecuteEveryFieldEvidenceQueryInsteadOfOnlyFirstSearchQuery() {
    TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
    StubTavilySearchClient client = new StubTavilySearchClient();
    client.responses = List.of(
            TavilySearchClient.TavilySearchResponse.builder()
                    .query("哔哩哔哩 开放平台 API 官方文档")
                    .requestId("req-field-1")
                    .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                            .title("用户管理 API")
                            .url("https://open.bilibili.com/doc/4/feb66f99")
                            .rawContent("用户管理 API raw")
                            .score(0.86D)
                            .build()))
                    .build(),
            TavilySearchClient.TavilySearchResponse.builder()
                    .query("site:open.bilibili.com API SDK 文档")
                    .requestId("req-field-2")
                    .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                            .title("授权管理 API")
                            .url("https://open.bilibili.com/doc/4/authorization")
                            .rawContent("授权管理 API raw")
                            .score(0.82D)
                            .build()))
                    .build());

    TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
            properties(),
            client,
            new TavilySearchProfileResolver(properties()),
            registry,
            new ObjectMapper()
    );

    List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
            .competitorName("哔哩哔哩")
            .requestedScopes(List.of("DOCS"))
            .preferredProviderKey("tavily")
            .fieldEvidenceQueries(List.of(
                    FieldEvidenceQuery.builder()
                            .fieldName("coreFeatures")
                            .evidencePathKey("DOCS_API_GUIDE")
                            .queryIntent("API_DOCS")
                            .sourceType("DOCS")
                            .query("哔哩哔哩 开放平台 API 官方文档")
                            .queryFingerprint("q1")
                            .reason("API 官方文档")
                            .build(),
                    FieldEvidenceQuery.builder()
                            .fieldName("coreFeatures")
                            .evidencePathKey("DOCS_API_GUIDE")
                            .queryIntent("SDK_GUIDE")
                            .sourceType("DOCS")
                            .query("site:open.bilibili.com API SDK 文档")
                            .queryFingerprint("q2")
                            .reason("站内 SDK 文档")
                            .build()))
            .build());

    assertThat(client.executedProfiles).hasSize(2);
    assertThat(client.executedProfiles).extracting(TavilySearchProfile::getQuery)
            .containsExactly("哔哩哔哩 开放平台 API 官方文档", "site:open.bilibili.com API SDK 文档");
    assertThat(candidates).extracting(SourceCandidate::getUrl)
            .containsExactly("https://open.bilibili.com/doc/4/feb66f99", "https://open.bilibili.com/doc/4/authorization");
    assertThat(candidates).extracting(SourceCandidate::getFieldEvidenceQueryFingerprint)
            .containsExactly("q1", "q2");
}
```

Run:

```powershell
mvn -pl backend -Dtest=TavilyFastLaneProviderTest#shouldExecuteEveryFieldEvidenceQueryInsteadOfOnlyFirstSearchQuery test
```

Expected: FAIL，当前 provider 不执行 `fieldEvidenceQueries`。

- [ ] **Step 2: 在 TavilySearchProfileResolver 新增字段 query profile 方法**

新增 public 方法：

```java
public TavilySearchProfile resolveFieldEvidence(FieldEvidenceQuery query) {
    if (query == null || !StringUtils.hasText(query.getQuery())) {
        return TavilySearchProfile.builder()
                .family("OPEN_WEB")
                .queryMode(TavilyQueryMode.OPEN_WEB)
                .query("")
                .includeDomains(List.of())
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .build();
    }
    return TavilySearchProfile.builder()
            .family(normalizeFamily(query.getSourceType()))
            .queryMode(resolveFieldEvidenceMode(query))
            .query(query.getQuery())
            .includeDomains(query.getIncludeDomains() == null ? List.of() : query.getIncludeDomains())
            .searchDepth(properties.getSearchDepth())
            .includeRawContent(properties.isIncludeRawContent())
            .maxResults(properties.getMaxResults())
            .fieldName(query.getFieldName())
            .evidencePathKey(query.getEvidencePathKey())
            .queryIntent(query.getQueryIntent())
            .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
            .fieldEvidenceQueryReason(query.getReason())
            .build();
}

private TavilyQueryMode resolveFieldEvidenceMode(FieldEvidenceQuery query) {
    String sourceType = query == null ? null : query.getSourceType();
    if ("OFFICIAL".equalsIgnoreCase(sourceType) || "DOCS".equalsIgnoreCase(sourceType) || "PRICING".equalsIgnoreCase(sourceType)) {
        return TavilyQueryMode.OFFICIAL_DOCS;
    }
    return TavilyQueryMode.OPEN_WEB;
}
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
```

- [ ] **Step 3: 修改 TavilyFastLaneProvider 优先执行字段 query**

在 `searchScope(...)` 起始处增加分支：

```java
if (request.getFieldEvidenceQueries() != null && !request.getFieldEvidenceQueries().isEmpty()) {
    return searchFieldEvidenceQueries(request, scope);
}
```

新增 helper，逐条执行并 fail-open：

```java
private List<SourceCandidate> searchFieldEvidenceQueries(SearchSourceRequest request, String scope) {
    List<SourceCandidate> candidates = new ArrayList<>();
    for (FieldEvidenceQuery query : request.getFieldEvidenceQueries()) {
        if (query == null || !StringUtils.hasText(query.getQuery())) {
            continue;
        }
        TavilySearchProfile profile = profileResolver.resolveFieldEvidence(query);
        try {
            TavilySearchClient.TavilySearchResponse response = client.search(profile);
            candidates.addAll(mapResponse(request, response, profile, resolveScopeForQuery(scope, query)));
        } catch (RuntimeException exception) {
            candidates.add(buildFailedFieldEvidenceCandidate(query, exception.getMessage()));
        }
    }
    return deduplicateByUrl(candidates);
}
```

新增失败候选，保证单条 query 失败可审计且不打断其他 query：

```java
private SourceCandidate buildFailedFieldEvidenceCandidate(FieldEvidenceQuery query, String reason) {
    return SourceCandidate.builder()
            .url("field-evidence-query://" + query.getQueryFingerprint())
            .title("字段证据 query 执行失败")
            .sourceType(query.getSourceType())
            .providerKey("tavily")
            .discoveryMethod("TAVILY_FIELD_EVIDENCE_QUERY")
            .fieldName(query.getFieldName())
            .evidencePathKey(query.getEvidencePathKey())
            .queryIntent(query.getQueryIntent())
            .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
            .fieldEvidenceQueryReason(query.getReason())
            .searchQuery(query.getQuery())
            .tavilyQuery(query.getQuery())
            .qualitySignals(List.of("TAVILY_FIELD_QUERY_FAILED"))
            .selectionStage("FAILED")
            .selectionReason(reason == null ? "Tavily 字段 query 执行失败" : reason)
            .sourceUrls(List.of())
            .build();
}
```

`mapResponse(...)` 创建 `TavilyPrefetchedContent` 和 `SourceCandidate` 时透传 profile 字段：

```java
.fieldName(profile == null ? null : profile.getFieldName())
.evidencePathKey(profile == null ? null : profile.getEvidencePathKey())
.queryIntent(profile == null ? null : profile.getQueryIntent())
.fieldEvidenceQueryFingerprint(profile == null ? null : profile.getFieldEvidenceQueryFingerprint())
.fieldEvidenceQueryReason(profile == null ? null : profile.getFieldEvidenceQueryReason())
```

`discoveryMethod` 对字段 query 返回：

```java
if (profile != null && StringUtils.hasText(profile.getFieldEvidenceQueryFingerprint())) {
    return "TAVILY_FIELD_EVIDENCE_QUERY";
}
```

- [ ] **Step 4: 保留旧 searchQueries 兼容但修正多 query 误判**

在 `buildPrimaryProfile(...)` 保持旧逻辑：非字段计划且非 `EVIDENCE_REPAIR` 时可继续用第一条 `searchQueries` 作为 legacy override。不要把此兼容路径伪装成多 query 能力。字段级多 query 必须只由 `fieldEvidenceQueries` 驱动，避免普通 `searchQueries` 语义漂移。

- [ ] **Step 5: 运行 Tavily provider 测试**

Run:

```powershell
mvn -pl backend -Dtest=TavilyFastLaneProviderTest test
```

Expected: PASS。特别确认已有 `shouldFallbackToTrustedExpansionWhenOfficialDocsQueryReturnsNoUsableResults` 仍通过，字段 query 分支不能破坏旧官方锚点扩展。

---

### Task 6: SearchExecutionCoordinator 消费字段证据计划、收紧 verified 短路并生成字段候选

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceTest.java`

- [ ] **Step 1: 编写 coordinator 字段 query 请求测试**

创建 `SearchExecutionCoordinatorFieldEvidenceTest.java`，用 stub `SearchSourceProvider` 捕获 `SearchSourceRequest`：

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProviderDescriptor;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverage;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverageStatus;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchExecutionCoordinatorFieldEvidenceTest {

    @Test
    void shouldPassDimensionEvidenceQueriesToSearchProvider() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider, false);

        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(DimensionEvidencePlan.builder()
                        .competitorName("哔哩哔哩")
                        .maxCollectionRounds(2)
                        .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                                .fieldName("coreFeatures")
                                .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                                .plannedQueries(List.of(FieldEvidenceQuery.builder()
                                        .fieldName("coreFeatures")
                                        .evidencePathKey("DOCS_API_GUIDE")
                                        .queryIntent("API_DOCS")
                                        .sourceType("DOCS")
                                        .query("哔哩哔哩 开放平台 API 官方文档")
                                        .queryFingerprint("q-core-1")
                                        .reason("核心功能 API 文档")
                                        .build()))
                                .build()))
                        .build())
                .build();

        coordinator.execute(config, ignored -> {});

        assertThat(provider.requests).hasSize(1);
        SearchSourceRequest request = provider.requests.get(0);
        assertThat(request.getFieldEvidenceQueries()).hasSize(1);
        assertThat(request.getFieldEvidenceQueries().get(0).getEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
    }

    @Test
    void shouldOpenHttpSupplementWhenVerifiedCandidateExistsButFieldBudgetHasPendingQueries() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider, true);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .competitorUrls(List.of("https://app.bilibili.com"))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build(), ignored -> {});

        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(result.getExecutionTrace().getSupplementMethod()).isEqualTo("HTTP");
    }

    @Test
    void shouldNotTriggerPublicRecoveryWhenVerifiedFieldEvidenceCandidateAlreadyExists() {
        CapturingSearchSourceProvider provider = new CapturingSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider, true);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .competitorUrls(List.of("https://app.bilibili.com"))
                .sourceType("DOCS")
                .verifyCandidates(true)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .dimensionEvidencePlan(fieldPlan())
                .build(), ignored -> {});

        assertThat(result.getExecutionTrace().getPublicEvidenceRecoveryTriggered()).isFalse();
    }

    private SearchExecutionCoordinator newCoordinator(CapturingSearchSourceProvider provider,
                                                      boolean shallowEntryVerifies) {
        BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser disabled")
                .fallbackSuggested(false)
                .build());
        return new SearchExecutionCoordinator(
                new CandidateVerifier(new SourceCollector() {
                    @Override
                    public CollectedPage collect(SourceCollectRequest request) {
                        String url = request == null ? null : request.getUrl();
                        if (shallowEntryVerifies && "https://app.bilibili.com".equals(url)) {
                            return CollectedPage.builder()
                                    .url(url)
                                    .success(true)
                                    .title("哔哩哔哩 App")
                                    .content("哔哩哔哩 官方 App 下载中心")
                                    .sourceUrls(List.of(url))
                                    .build();
                        }
                        return CollectedPage.builder()
                                .url(url)
                                .success(false)
                                .errorMessage("planned candidate unavailable")
                                .build();
                    }

                    @Override
                    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
                        return List.of();
                    }
                }),
                browserSearchRuntimeService,
                provider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
    }

    private DimensionEvidencePlan fieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(FieldEvidenceQuery.builder()
                                .fieldName("coreFeatures")
                                .evidencePathKey("DOCS_API_GUIDE")
                                .queryIntent("API_DOCS")
                                .sourceType("DOCS")
                                .query("哔哩哔哩 开放平台 API 官方文档")
                                .queryFingerprint("q-core-1")
                                .reason("核心功能 API 文档")
                                .build()))
                        .build()))
                .build();
    }

    private static final class CapturingSearchSourceProvider implements SearchSourceProvider {
        private final List<SearchSourceRequest> requests = new ArrayList<>();

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey("tavily")
                    .displayName("test")
                    .capabilities(List.of())
                    .defaultEnabled(true)
                    .defaultFailOpen(true)
                    .build();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<SourceCandidate> search(SearchSourceRequest request) {
            requests.add(request);
            return List.of(SourceCandidate.builder()
                    .url("https://open.bilibili.com/doc/4/feb66f99")
                    .sourceType("DOCS")
                    .providerKey("tavily")
                    .fieldName("coreFeatures")
                    .evidencePathKey("DOCS_API_GUIDE")
                    .queryIntent("API_DOCS")
                    .verified(Boolean.TRUE)
                    .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                    .build());
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            return search(SearchSourceRequest.builder()
                    .competitorName(competitorName)
                    .requestedScopes(requestedScopes)
                    .build());
        }
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=SearchExecutionCoordinatorFieldEvidenceTest test
```

Expected: FAIL，因为 coordinator 尚未把 field plan 写入 `SearchSourceRequest`。

- [ ] **Step 2: 让字段预算打开 HTTP/Tavily supplement**

修改 `SearchExecutionCoordinator.shouldSupplement(...)`，在 runtime search 开启后优先判断字段待执行 query：

```java
private boolean shouldSupplement(CollectorNodeConfig config,
                                 int verifiedCount,
                                 int minVerifiedCount,
                                 int candidateCount,
                                 int targetCount,
                                 boolean resultPageVerificationEnabled) {
    if (shouldSkipSupplementForDirectDiscovery(config, verifiedCount, minVerifiedCount)) {
        return false;
    }
    boolean runtimeSearchEnabled = !"HEURISTIC_ONLY".equalsIgnoreCase(config.getSearchMode());
    if (!runtimeSearchEnabled) {
        return false;
    }
    // 字段证据计划是比“已有 verified 候选数量”更细的采集预算。
    // 只要仍有待执行字段 query，就必须允许 HTTP/Tavily supplement 进入 provider。
    if (hasPendingFieldEvidenceQueries(config)) {
        return true;
    }
    if (resultPageVerificationEnabled && Boolean.TRUE.equals(config.getVerifyCandidates())) {
        return verifiedCount < minVerifiedCount;
    }
    return candidateCount < targetCount;
}

private boolean hasPendingFieldEvidenceQueries(CollectorNodeConfig config) {
    return config != null
            && config.getDimensionEvidencePlan() != null
            && config.getDimensionEvidencePlan().hasPendingFieldEvidenceQueries();
}
```

- [ ] **Step 3: 修改 buildSearchSourceRequest**

在 `SearchExecutionCoordinator.buildSearchSourceRequest(...)` 中增加：

```java
.fieldEvidenceQueries(resolveFieldEvidenceQueries(config))
```

新增 helper：

```java
private List<FieldEvidenceQuery> resolveFieldEvidenceQueries(CollectorNodeConfig config) {
    if (config == null || config.getDimensionEvidencePlan() == null) {
        return List.of();
    }
    return config.getDimensionEvidencePlan().allPlannedQueries();
}
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
```

- [ ] **Step 4: 收紧 `shouldTriggerPublicEvidenceRecovery` 的 verified 短路（落地 0.3）**

修改 [SearchExecutionCoordinator.java:1419-1440](backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java#L1419-L1440)，把 `hasVerifiedCandidate` 的无条件短路收紧为“verified 候选已经带字段证据信号才短路”。不要为了测试暴露私有门控方法；用 Step 1 中的 `coordinator.execute(...)` 行为测试覆盖。

```java
private boolean shouldTriggerPublicEvidenceRecovery(CollectorNodeConfig config,
                                                    List<SourceCandidate> candidates,
                                                    Map<String, SearchCollectionTarget> attemptedTargets) {
    List<SourceCandidate> safeCandidates = candidates == null ? List.of() : candidates;
    boolean hasVerifiedCandidate = safeCandidates.stream()
            .anyMatch(candidate -> candidate != null && Boolean.TRUE.equals(candidate.getVerified()));
    // public recovery 是弱入口兜底，不是字段多 query 主执行器。
    // 如果 verified 候选已经带字段证据元数据，说明 HTTP/Tavily 字段 query 已经命中，不再继续同域兜底。
    if (hasVerifiedCandidate && (!hasUnmetRequiredFieldEvidencePath(config)
            || hasVerifiedFieldEvidenceCandidate(safeCandidates))) {
        return false;
    }
    if (StringUtils.hasText(config.getRecoveryFieldName())
            || StringUtils.hasText(config.getRecoveryEvidencePathKey())
            || (config.getRecoveryQueryIntents() != null && !config.getRecoveryQueryIntents().isEmpty())) {
        return true;
    }
    // 只有“存在 verified 候选但字段预算仍未满足，且没有任何字段证据候选”时才兜底 public recovery。
    if (hasVerifiedCandidate && hasUnmetRequiredFieldEvidencePath(config)) {
        return true;
    }
    if (attemptedTargets == null || attemptedTargets.isEmpty()) {
        return false;
    }
    return attemptedTargets.values().stream().anyMatch(target ->
            target != null && candidateOwnershipPolicy.isUtilityGatePage(
                    target.getCandidate(),
                    target.getCollectedPage()
            ));
}

private boolean hasVerifiedFieldEvidenceCandidate(List<SourceCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
        return false;
    }
    return candidates.stream().anyMatch(candidate -> candidate != null
            && Boolean.TRUE.equals(candidate.getVerified())
            && StringUtils.hasText(candidate.getFieldName())
            && StringUtils.hasText(candidate.getEvidencePathKey())
            && (StringUtils.hasText(candidate.getFieldEvidenceQueryFingerprint())
            || (candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty())));
}

private boolean hasUnmetRequiredFieldEvidencePath(CollectorNodeConfig config) {
    if (config == null || config.getDimensionEvidencePlan() == null) {
        return false;
    }
    return config.getDimensionEvidencePlan().hasUnmetRequiredFieldPath();
}
```

`DimensionEvidencePlan.hasUnmetRequiredFieldPath()` 只表示字段预算仍未满足；它不能单独作为 public recovery 触发条件，必须结合 `hasVerifiedFieldEvidenceCandidate(...)` 判断是否已有字段证据命中。

验收要求（对齐 0.3 与红线五）：

- `hasVerifiedCandidate=true` 且无 `dimensionEvidencePlan` 时，行为与改动前完全一致（保护 04 显式候选不被噪音补源覆盖）。
- `hasVerifiedCandidate=true` 且字段预算有 pending query 时，HTTP/Tavily supplement 在第一轮执行，字段 query 不必等到 Task 8 采集层再入才执行。
- `hasVerifiedCandidate=true` 且字段 query 已返回 verified 字段候选时，`PUBLIC_EVIDENCE_RECOVERY` 不应再触发，避免 fresh plan 天然 unmet 引发重复兜底。
- `SearchExecutionCoordinatorPublicRecoveryTest` 原有用例必须继续通过，证明收紧没有破坏既有 recovery 语义。

- [ ] **Step 5: 在 search audit 中记录字段 query 摘要**

在最终 `SearchAuditSnapshot` 或 search trace metadata 中增加字段：

```java
"fieldEvidenceQueryCount", resolveFieldEvidenceQueries(config).size()
"fieldEvidenceFields", resolveFieldEvidenceQueries(config).stream().map(FieldEvidenceQuery::getFieldName).distinct().toList()
"fieldEvidencePaths", resolveFieldEvidenceQueries(config).stream().map(FieldEvidenceQuery::getEvidencePathKey).distinct().toList()
```

验收要求：前端或日志能看到字段 query 已进入搜索执行，而不是只看到 `coverageQueryIntents`。

- [ ] **Step 6: 运行 coordinator 字段测试**

Run:

```powershell
mvn -pl backend -Dtest=SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorTest,SearchExecutionCoordinatorPublicRecoveryTest test
```

Expected: PASS。

---

### Task 7: 字段覆盖聚合与 repair 字段路径完成态

**Files:**
- Create: `backend/src/main/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairState.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/EvidenceRepairPlan.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/workflow/coverage/FieldEvidenceCoverageAggregatorTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/EvidenceRepairStateTest.java`

- [ ] **Step 1: 编写 coverage aggregator 测试**

创建 `FieldEvidenceCoverageAggregatorTest.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.search.EvidenceRepairPlan;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceCoverageAggregatorTest {

    private final FieldEvidenceCoverageAggregator aggregator = new FieldEvidenceCoverageAggregator();

    @Test
    void shouldMarkFieldPathCompletedWhenEnoughDistinctUrlsCollected() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .minDistinctEvidenceCount(1)
                        .evidencePaths(List.of(CoverageEvidencePath.builder()
                                .pathKey("DOCS_API_GUIDE")
                                .required(true)
                                .build()))
                        .plannedQueries(List.of())
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of(
                CollectionExecutionResult.builder()
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
                        .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                        .publicEvidenceRecoveryFieldName("coreFeatures")
                        .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                        .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                                .build())
                        .build()));

        FieldEvidenceCoverage coreFeatures = updated.findField("coreFeatures").orElseThrow();
        assertThat(coreFeatures.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.SUFFICIENT);
        assertThat(coreFeatures.getAttemptedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getCompletedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getLastRepairState()).isEqualTo("REPAIR_FIELD_PATH_COMPLETED");
    }

    @Test
    void shouldKeepCoverageGapWhenMinimumAttemptedPathsNotMet() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("pricing")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(2)
                        .minDistinctEvidenceCount(2)
                        .evidencePaths(List.of(
                                CoverageEvidencePath.builder().pathKey("OFFICIAL_PRICING_PAGE").required(true).build(),
                                CoverageEvidencePath.builder().pathKey("DOCS_BILLING_OR_LIMITS").required(true).build()))
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of());

        FieldEvidenceCoverage pricing = updated.findField("pricing").orElseThrow();
        assertThat(pricing.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET);
        assertThat(pricing.getRecommendedNextAction()).isEqualTo("RECOLLECT_FIELD_EVIDENCE");
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceCoverageAggregatorTest test
```

Expected: FAIL，因为 aggregator 不存在。

- [ ] **Step 2: 增加字段路径完成状态**

在 `EvidenceRepairState` 添加：

```java
REPAIR_FIELD_PATH_COMPLETED
```

修改 `EvidenceRepairPlan.isComplete()`：

```java
return state == EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED
        || state == EvidenceRepairState.REPAIR_FIELD_PATH_COMPLETED;
```

追加测试到 `EvidenceRepairStateTest`：

```java
@Test
void shouldTreatFieldPathCompletedAsCompleteRepairState() {
    EvidenceRepairPlan plan = EvidenceRepairPlan.builder()
            .state(EvidenceRepairState.REPAIR_FIELD_PATH_COMPLETED)
            .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
            .build();

    assertThat(plan.isComplete()).isTrue();
}
```

- [ ] **Step 3: 新增字段覆盖聚合器**

创建 `FieldEvidenceCoverageAggregator.java`：

```java
package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 字段证据覆盖聚合器。
 * 它只根据采集结果和字段计划更新覆盖状态，不负责搜索、不负责采集执行。
 */
@Component
public class FieldEvidenceCoverageAggregator {

    public DimensionEvidencePlan applyCollectionResults(DimensionEvidencePlan plan,
                                                        List<CollectionExecutionResult> results) {
        if (plan == null || plan.getFieldCoverages() == null || plan.getFieldCoverages().isEmpty()) {
            return plan;
        }
        List<FieldEvidenceCoverage> updatedFields = new ArrayList<>();
        for (FieldEvidenceCoverage field : plan.getFieldCoverages()) {
            updatedFields.add(updateField(field, results));
        }
        return plan.toBuilder()
                .fieldCoverages(updatedFields)
                .build();
    }

    public List<FieldEvidenceCoverage> fieldsNeedingRecollection(DimensionEvidencePlan plan) {
        if (plan == null || plan.getFieldCoverages() == null) {
            return List.of();
        }
        return plan.getFieldCoverages().stream()
                .filter(field -> field != null
                        && field.getStatus() == FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET
                        && "RECOLLECT_FIELD_EVIDENCE".equals(field.getRecommendedNextAction()))
                .toList();
    }

    private FieldEvidenceCoverage updateField(FieldEvidenceCoverage field,
                                              List<CollectionExecutionResult> results) {
        LinkedHashSet<String> attemptedPaths = new LinkedHashSet<>(field.getAttemptedPaths() == null ? List.of() : field.getAttemptedPaths());
        LinkedHashSet<String> completedPaths = new LinkedHashSet<>(field.getCompletedPaths() == null ? List.of() : field.getCompletedPaths());
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(field.getSourceUrls() == null ? List.of() : field.getSourceUrls());

        for (CollectionExecutionResult result : results == null ? List.<CollectionExecutionResult>of() : results) {
            if (result == null || !field.getFieldName().equalsIgnoreCase(result.getPublicEvidenceRecoveryFieldName())) {
                continue;
            }
            String pathKey = result.getPublicEvidenceRecoveryEvidencePathKey();
            if (StringUtils.hasText(pathKey)) {
                attemptedPaths.add(pathKey);
            }
            if (result.isSuccess() && result.getSourceUrls() != null) {
                sourceUrls.addAll(result.getSourceUrls());
            }
            if (result.getEvidenceRepairPlan() != null && result.getEvidenceRepairPlan().isComplete() && StringUtils.hasText(pathKey)) {
                completedPaths.add(pathKey);
            }
        }

        int minimumAttemptedPaths = field.getMinimumAttemptedPaths() == null ? 0 : field.getMinimumAttemptedPaths();
        int minDistinctEvidenceCount = field.getMinDistinctEvidenceCount() == null ? 0 : field.getMinDistinctEvidenceCount();
        boolean attemptedEnough = attemptedPaths.size() >= minimumAttemptedPaths;
        boolean evidenceEnough = sourceUrls.size() >= minDistinctEvidenceCount;
        FieldEvidenceCoverageStatus status = attemptedEnough && evidenceEnough
                ? FieldEvidenceCoverageStatus.SUFFICIENT
                : FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET;

        return field.toBuilder()
                .status(status)
                .attemptedPaths(new ArrayList<>(attemptedPaths))
                .completedPaths(new ArrayList<>(completedPaths))
                .sourceUrls(new ArrayList<>(sourceUrls))
                .lastRepairState(status == FieldEvidenceCoverageStatus.SUFFICIENT
                        ? EvidenceRepairState.REPAIR_FIELD_PATH_COMPLETED.name()
                        : null)
                .recommendedNextAction(status == FieldEvidenceCoverageStatus.SUFFICIENT
                        ? "ACCEPT_FIELD_EVIDENCE"
                        : "RECOLLECT_FIELD_EVIDENCE")
                .build();
    }
}
```

- [ ] **Step 4: 运行 aggregator 与 repair 状态测试**

Run:

```powershell
mvn -pl backend -Dtest=FieldEvidenceCoverageAggregatorTest,EvidenceRepairStateTest test
```

Expected: PASS。

---

### Task 8: CollectorAgent 覆盖 gap 再入闭环

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentFieldEvidenceLoopTest.java`

- [ ] **Step 1: 编写再入闭环测试**

创建 `CollectorAgentFieldEvidenceLoopTest.java`。测试目标：第一轮采集出弱入口并产生 `REPAIR_QUERY_PROPOSED`，Collector 读取 `DimensionEvidencePlan` 后触发第二轮字段 query，第二轮结果进入 audit，并把字段路径标为完成。

测试不要访问真实网络。用两个 fake 记录调用：

```java
private static final class RecordingSearchExecutionCoordinator extends SearchExecutionCoordinator {
    private final List<CollectorNodeConfig> executedConfigs = new ArrayList<>();

    RecordingSearchExecutionCoordinator() {
        super(new CandidateVerifier(new SourceCollector() {
                    @Override
                    public CollectedPage collect(SourceCollectRequest request) {
                        return CollectedPage.builder()
                                .url(request == null ? null : request.getUrl())
                                .success(false)
                                .build();
                    }

                    @Override
                    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
                        return List.of();
                    }
                }),
                mock(BrowserSearchRuntimeService.class),
                request -> List.of(),
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver());
    }

    @Override
    public SearchExecutionResult execute(CollectorNodeConfig config,
                                         Consumer<SearchExecutionUpdate> progressListener) {
        executedConfigs.add(config);
        SourceCandidate candidate = SourceCandidate.builder()
                .url(executedConfigs.size() == 1
                        ? "https://app.bilibili.com"
                        : "https://open.bilibili.com/doc/4/feb66f99")
                .sourceType("DOCS")
                .verified(Boolean.TRUE)
                .fieldName(executedConfigs.size() == 1 ? null : "coreFeatures")
                .evidencePathKey(executedConfigs.size() == 1 ? null : "DOCS_API_GUIDE")
                .fieldEvidenceQueryFingerprint(executedConfigs.size() == 1 ? null : "q-core-1")
                .sourceUrls(List.of(executedConfigs.size() == 1
                        ? "https://app.bilibili.com"
                        : "https://open.bilibili.com/doc/4/feb66f99"))
                .build();
        return SearchExecutionResult.builder()
                .sourceCandidates(List.of(candidate))
                .selectedTargets(List.of(SearchCollectionTarget.builder().candidate(candidate).build()))
                .executionPlan(SearchExecutionPlan.builder().steps(List.of()).build())
                .progressSnapshots(List.of())
                .executionTrace(SearchExecutionTrace.builder().build())
                .build();
    }
}
```

collection fake 用 Mockito `Answer`，第一轮返回弱入口 repair gap，第二轮返回字段路径完成：

```java
AtomicInteger collectionRounds = new AtomicInteger();
CollectionExecutionCoordinator collectionCoordinator = mock(CollectionExecutionCoordinator.class);
when(collectionCoordinator.execute(any(), any(), any(), any(), anyList(), any()))
        .thenAnswer(invocation -> {
        int rounds = collectionRounds.incrementAndGet();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .success(true)
                .status("SUCCESS")
                .resourceLocator(rounds == 1
                        ? "https://app.bilibili.com"
                        : "https://open.bilibili.com/doc/4/feb66f99")
                .sourceUrls(List.of(rounds == 1
                        ? "https://app.bilibili.com"
                        : "https://open.bilibili.com/doc/4/feb66f99"))
                .publicEvidenceRecoveryFieldName(rounds == 1 ? null : "coreFeatures")
                .publicEvidenceRecoveryEvidencePathKey(rounds == 1 ? null : "DOCS_API_GUIDE")
                .evidenceRepairPlan(EvidenceRepairPlan.builder()
                        .state(rounds == 1
                                ? EvidenceRepairState.REPAIR_QUERY_PROPOSED
                                : EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                        .promotedUrls(rounds == 1 ? List.of() : List.of("https://open.bilibili.com/doc/4/feb66f99"))
                        .build())
                .build();
        return CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of(result))
                .build();
        });
```

主断言解析 `AgentResult.outputData`，不要假设 `AgentResult` 存在独立 metadata getter：

```java
AgentResult agentResult = agent.execute(contextWithDimensionEvidencePlan());
JsonNode output = objectMapper.readTree(agentResult.getOutputData());
assertThat(searchCoordinator.executedConfigs).hasSize(2);
assertThat(searchCoordinator.executedConfigs.get(1).getDimensionEvidencePlan().allPlannedQueries())
        .extracting(FieldEvidenceQuery::getEvidencePathKey)
        .contains("DOCS_API_GUIDE");
assertThat(output.path("dimensionEvidencePlan").path("fieldCoverages").isArray()).isTrue();
assertThat(output.path("fieldEvidenceLoopRounds").asInt()).isEqualTo(2);
assertThat(output.toString()).contains("REPAIR_FIELD_PATH_COMPLETED");
```

Run:

```powershell
mvn -pl backend -Dtest=CollectorAgentFieldEvidenceLoopTest test
```

Expected: FAIL，因为 Collector 还没有字段覆盖再入逻辑。

- [ ] **Step 2: 给 CollectorAgent 注入 FieldEvidenceCoverageAggregator**

新增字段：

```java
private final FieldEvidenceCoverageAggregator fieldEvidenceCoverageAggregator;
```

构造器默认：

```java
this.fieldEvidenceCoverageAggregator = fieldEvidenceCoverageAggregator == null
        ? new FieldEvidenceCoverageAggregator()
        : fieldEvidenceCoverageAggregator;
```

导入：

```java
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverageAggregator;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverage;
```

- [ ] **Step 3: 在第一轮 collection 后聚合字段覆盖**

在 `CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(...)` 后，对 gated/audit results 调用：

```java
DimensionEvidencePlan updatedPlan = fieldEvidenceCoverageAggregator.applyCollectionResults(
        config.getDimensionEvidencePlan(),
        auditResults
);
config.setDimensionEvidencePlan(updatedPlan);
```

`buildCollectorOutput(...)` 顶层写入，保证系统测试和下游节点都能稳定读取闭环状态：

```java
output.put("dimensionEvidencePlan", config.getDimensionEvidencePlan());
output.put("fieldEvidenceCoverageStatus", summarizeFieldCoverage(config.getDimensionEvidencePlan()));
output.put("fieldEvidenceLoopRounds", fieldEvidenceLoopRounds);
output.put("fieldEvidenceRecollectionTriggered", fieldEvidenceLoopRounds > 1);
output.put("fieldEvidenceFinalStatus", summarizeFieldEvidenceFinalStatus(config.getDimensionEvidencePlan()));
```

- [ ] **Step 4: 实现最多 2 轮再采集**

新增 helper：

```java
private boolean shouldRunFieldEvidenceRecollection(CollectorNodeConfig config,
                                                   DimensionEvidencePlan plan,
                                                   int currentRound) {
    int maxRounds = plan == null || plan.getMaxCollectionRounds() == null ? 2 : Math.max(1, plan.getMaxCollectionRounds());
    return currentRound < maxRounds
            && plan != null
            && !fieldEvidenceCoverageAggregator.fieldsNeedingRecollection(plan).isEmpty()
            && config != null
            && config.getDimensionEvidencePlan() != null;
}
```

在第一轮采集后，如果 `shouldRunFieldEvidenceRecollection(config, updatedPlan, 1)` 为 true：

1. 只保留未完成字段的 `plannedQueries`，构造一个新的 `DimensionEvidencePlan`。
2. 调用 `searchExecutionCoordinator.execute(config, listener)` 再跑搜索。
3. 将第二轮 selected targets 送入 `collectionExecutionCoordinator.execute(...)`。
4. 第二轮结果继续经过 `gateCollectionResult(...)`。
5. 再次 `applyCollectionResults(...)` 更新 plan。
6. Collector 输出顶层记录 `fieldEvidenceLoopRounds=2`、`fieldEvidenceRecollectionTriggered=true`。

如果第二轮仍不满足，写入：

```java
metadata.put("fieldEvidenceFinalStatus", "NO_PUBLIC_EVIDENCE_AFTER_SEARCH");
```

不能无限循环，默认最多 2 轮。

- [ ] **Step 5: 再入时只查询未完成字段路径**

新增 helper：

```java
private DimensionEvidencePlan narrowPlanToUnfinishedFields(DimensionEvidencePlan plan) {
    List<FieldEvidenceCoverage> fields = fieldEvidenceCoverageAggregator.fieldsNeedingRecollection(plan);
    return plan == null ? null : plan.toBuilder()
            .fieldCoverages(fields)
            .build();
}
```

验收要求：第二轮不能重新执行所有字段，尤其不能把 capability-intro 中非 blocker 的 `pricing / weaknesses` 突然拉进闭环。

- [ ] **Step 6: 运行 Collector 闭环测试**

Run:

```powershell
mvn -pl backend -Dtest=CollectorAgentFieldEvidenceLoopTest,CollectorAgentEvidenceQualityGateTest test
```

Expected: PASS。

---

### Task 9: 两个系统测试输入样本与端到端验收

**Files:**
- Create: `backend/src/test/resources/task66/field-first-capability-intro-request.json`
- Create: `backend/src/test/resources/task66/field-first-standard-bilibili-shallow-request.json`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/integration/Task66FieldFirstEvidenceLoopSystemTest.java`

- [ ] **Step 1: 新增能力介绍样本输入**

创建 `field-first-capability-intro-request.json`：

```json
{
  "reportTemplate": "能力介绍",
  "competitorNames": ["哔哩哔哩"],
  "competitorUrls": ["https://open.bilibili.com"],
  "reportLanguage": "中文",
  "subjectProduct": "短视频平台开放生态与开发者能力",
  "sourceScope": ["官网", "产品文档"],
  "analysisDimensions": ["开放平台", "开发者生态", "产品功能"],
  "taskName": "Task66-06 字段优先能力介绍样本"
}
```

验收断言：

- `coreFeatures / summary / positioning / targetUsers` 为 required 或 blocker。
- `pricing / weaknesses` 不作为 blocker。
- `coreFeatures` 至少产生 2 条字段级 Tavily query。
- 不允许因为标准报告全字段模板存在而触发 `pricing / weaknesses` 再采集。

- [ ] **Step 2: 新增标准版 B 站弱入口样本输入**

创建 `field-first-standard-bilibili-shallow-request.json`：

```json
{
  "reportTemplate": "标准版",
  "competitorNames": ["哔哩哔哩"],
  "competitorUrls": ["https://app.bilibili.com"],
  "reportLanguage": "中文",
  "subjectProduct": "视频社区与内容平台",
  "sourceScope": ["官网"],
  "analysisDimensions": ["产品功能", "目标用户", "市场定位", "证据完整性"],
  "taskName": "Task66-06 字段优先标准版弱入口样本"
}
```

验收断言：

- 标准版必须把 `pricing` 作为 blocker，并生成 `OFFICIAL_PRICING_PAGE / DOCS_BILLING_OR_LIMITS` 两条 evidence path。
- 即使输入只有 `https://app.bilibili.com`，字段计划也必须为 `coreFeatures` 生成 `DOCS_API_GUIDE` 查询，为 `pricing` 生成定价、计费、协议三类查询。
- 第二轮再采集至少针对 `coreFeatures / pricing` 的未完成路径发起，不允许因为第一轮有 verified 下载中心入口而短路结束。
- 如果 stub 数据没有 pricing 正证据，最终输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 或 `EVIDENCE_PATH_COVERAGE_NOT_MET`，不能把下载中心页当 pricing 证据。

- [ ] **Step 3: 编写系统测试读取两个样本**

创建 `Task66FieldFirstEvidenceLoopSystemTest.java`，测试需要包括两个方法：

```java
@Test
void capabilityIntroSampleShouldCollectFieldFirstWithoutPricingWeaknessBlockers() {
    Task66Request request = readRequest("task66/field-first-capability-intro-request.json");
    CoverageContract contract = coverageContractResolver.resolve(
            request.reportTemplate(),
            request.analysisDimensions(),
            request.sourceScope(),
            null);
    DimensionEvidencePlan plan = dimensionEvidencePlanFactory.create(
            request.competitorNames().get(0),
            contract,
            List.of("open.bilibili.com"));

    assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
            .isEqualTo(CoverageBlockingLevel.NONE);
    assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
            .isEqualTo(CoverageBlockingLevel.NONE);
    assertThat(plan.findField("coreFeatures").orElseThrow().getPlannedQueries())
            .hasSizeGreaterThanOrEqualTo(2);
    assertThat(plan.findField("pricing")).isEmpty();
}

@Test
void standardBilibiliShallowEntryShouldDeepenCoreFeaturesAndPricing() {
    Task66Request request = readRequest("task66/field-first-standard-bilibili-shallow-request.json");
    CoverageContract contract = coverageContractResolver.resolve(
            request.reportTemplate(),
            request.analysisDimensions(),
            request.sourceScope(),
            null);
    DimensionEvidencePlan plan = dimensionEvidencePlanFactory.create(
            request.competitorNames().get(0),
            contract,
            List.of("app.bilibili.com", "open.bilibili.com"));

    assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
            .isEqualTo(CoverageBlockingLevel.BLOCKER);
    assertThat(plan.findField("coreFeatures").orElseThrow().getPlannedQueries())
            .extracting(FieldEvidenceQuery::getEvidencePathKey)
            .contains("DOCS_API_GUIDE");
    assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
            .extracting(FieldEvidenceQuery::getQuery)
            .anySatisfy(query -> assertThat(query).contains("定价"))
            .anySatisfy(query -> assertThat(query).contains("计费"))
            .anySatisfy(query -> assertThat(query).contains("服务协议"));
}
```

测试内定义最小 record：

```java
private record Task66Request(String reportTemplate,
                             List<String> competitorNames,
                             List<String> competitorUrls,
                             String reportLanguage,
                             String subjectProduct,
                             List<String> sourceScope,
                             List<String> analysisDimensions,
                             String taskName) {
}

private Task66Request readRequest(String classpathResource) throws IOException {
    try (InputStream input = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(classpathResource)) {
        assertThat(input).as(classpathResource).isNotNull();
        return objectMapper.readValue(input, Task66Request.class);
    }
}
```

Run:

```powershell
mvn -pl backend -Dtest=Task66FieldFirstEvidenceLoopSystemTest test
```

Expected: PASS。

- [ ] **Step 4: 系统测试增加闭环 stub 场景**

在同一测试类追加一个不访问网络的闭环测试，复用 Task 8 的 fake search/collection。测试通过 Collector 输出 JSON 验收闭环，不引入额外结果包装类型：

```java
@Test
void stubLoopShouldCollectSecondRoundFieldEvidenceAndCloseCoverage() throws Exception {
    AgentResult result = collectorAgent.execute(contextWithRequest(
            readRequest("task66/field-first-standard-bilibili-shallow-request.json")));

    JsonNode output = objectMapper.readTree(result.getOutputData());
    JsonNode coreFeatures = findField(output.path("dimensionEvidencePlan"), "coreFeatures");

    assertThat(output.path("fieldEvidenceLoopRounds").asInt()).isEqualTo(2);
    assertThat(coreFeatures.path("status").asText()).isEqualTo(FieldEvidenceCoverageStatus.SUFFICIENT.name());
    assertThat(coreFeatures.path("lastRepairState").asText()).isEqualTo("REPAIR_FIELD_PATH_COMPLETED");
    assertThat(output.path("sourceUrls").toString()).contains("https://open.bilibili.com/doc/4/feb66f99");
}

private JsonNode findField(JsonNode dimensionEvidencePlan, String fieldName) {
    for (JsonNode field : dimensionEvidencePlan.path("fieldCoverages")) {
        if (fieldName.equals(field.path("fieldName").asText())) {
            return field;
        }
    }
    throw new AssertionError("field not found: " + fieldName);
}
```

该测试是 06 是否真正实现“字段驱动多页证据采集 + 覆盖闭环”的主验收，不可只验证 contract 或 query 生成。

---

### Task 10: 审计、进度文档与阶段验证

**Files:**
- Modify/Create: `docs/Tavily/progress/2026-06-29-task66-06-field-first-evidence-loop-progress.md`
- No production file changes beyond previous tasks.

- [ ] **Step 1: 运行 06 单元测试集合**

Run:

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryPlannerTest,DimensionEvidencePlanFactoryTest,FieldEvidenceCoverageAggregatorTest,WorkflowPlanFieldEvidencePlanTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceTest,CollectorAgentFieldEvidenceLoopTest,Task66FieldFirstEvidenceLoopSystemTest" test
```

Expected: PASS。

- [ ] **Step 2: 运行 task66/Tavily 阶段回归集合**

Run:

```powershell
mvn -pl backend "-Dtest=CoverageContractResolverTest,AnalysisDimensionMappingCatalogTest,CoverageContractProviderTest,WorkflowPlanCoverageContractTest,WorkflowPlanFieldEvidencePlanTest,SchemaExtractorAgentCoverageContractTest,QualityReviewAgentCoverageContractTest,EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest,CollectorAgentFieldEvidenceLoopTest,FieldEvidenceQueryPlannerTest,DimensionEvidencePlanFactoryTest,FieldEvidenceCoverageAggregatorTest,TavilyPrefetchedContentBlockClassifierTest,TavilyPrefetchedExecutorTest,TavilyFastLaneAuditContractTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceTest,PublicEvidenceRecoveryServiceTest,EvidenceRepairStateTest,SearchExecutionCoordinatorRepairAuditTest,SearchExecutionCoordinatorPublicRecoveryTest,FieldAnswerSynthesizerTest,Task66CoverageContractRegressionTest,Task66FieldFirstEvidenceLoopSystemTest" test
```

Expected: PASS。

- [ ] **Step 3: 写入 06 进度记录**

创建或更新 `docs/Tavily/progress/2026-06-29-task66-06-field-first-evidence-loop-progress.md`，格式：

```markdown
# Task66-06 字段优先证据闭环进度记录

更新时间：2026-06-29 Asia/Shanghai

## 当前状态
- 当前执行计划：`docs/Tavily/plan/2026-06-29-06-task66-field-first-evidence-collection-loop-plan.md`
- 当前阶段：Task 10 阶段验证完成
- 总体进度：10/10
- 执行状态：成功

## 结构化进度
- [x] Task 1：字段级 Tavily Query 模型与规划器
- [x] Task 2：字段证据计划与覆盖状态模型
- [x] Task 3：字段证据计划写入 Collector 节点配置
- [x] Task 4：搜索请求、候选与 Tavily profile 携带字段元数据
- [x] Task 5：Tavily Fast Lane 多 query 真实执行
- [x] Task 6：SearchExecutionCoordinator 消费字段证据计划
- [x] Task 7：字段覆盖聚合与 repair 字段路径完成态
- [x] Task 8：CollectorAgent 覆盖 gap 再入闭环
- [x] Task 9：两个系统测试输入样本与端到端验收
- [x] Task 10：审计、进度文档与阶段验证

## 验证记录
- 通过：`mvn -pl backend "-Dtest=..." test`
- 结果：记录 Maven tests/failures/errors/skipped 数字。

## 每次停止总结
- 这次做了什么：实现字段驱动多页证据采集与覆盖闭环，并完成两个系统样本验收。
- 接下来要做什么：根据真实 live smoke 结果决定是否进入 07，扩展更多字段路径或跨字段 evidence graph。
- 还剩什么没做：不在 06 中实现跨字段推理图；真实公网命中率不作为 06 的确定性承诺。
```

- [ ] **Step 4: 可选 live smoke**

如果本地 `application.yml` 配置了 Tavily API key，并且用户允许真实网络验证，可以运行一次标准版弱入口 live smoke。该 smoke 只用于观察真实命中率，不作为 06 单元验收的必要条件。

验收输出必须包含：

- `fieldEvidenceQueryCount > 1`
- `fieldEvidenceFields` 至少包含 `coreFeatures` 和 `pricing`
- `fieldEvidenceLoopRounds` 为 1 或 2
- `attemptedPaths` 不为空
- `sourceUrls` 在每个可用字段结论中存在

---

## Definition of Done

- 两个系统输入样本均已加入测试资源，并被 `Task66FieldFirstEvidenceLoopSystemTest` 消费。
- `FieldEvidenceQueryPlanner` 对同一字段路径至少生成 2 条语义互补 query，`coreFeatures / pricing` 均有覆盖。
- `DimensionEvidencePlan` 写入 collector node config，并在运行时被 `SearchExecutionCoordinator` 消费。
- `SearchExecutionCoordinator.shouldTriggerPublicEvidenceRecovery` 的 verified 短路已收紧：存在未达 `minimumAttemptedPaths` 的 required 字段路径时，verified 弱入口候选不再短路 recovery；无 `dimensionEvidencePlan` 时行为与改动前一致。`SearchExecutionCoordinatorPublicRecoveryTest` 全部通过。
- `TavilyFastLaneProvider` 对字段 query 逐条调用 Tavily，不再只消费第一条 query。
- 每条 Tavily 字段 query 的 `fieldName / evidencePathKey / queryIntent / queryFingerprint / requestId` 可审计。
- 采集结果能更新 `attemptedPaths / completedPaths / sourceUrls / lastRepairState`。
- `REPAIR_QUERY_PROPOSED` 或字段路径未达标会触发最多第二轮字段定向再采集。
- 第二轮后仍无公开证据时输出 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 或 `EVIDENCE_PATH_COVERAGE_NOT_MET`，不能把弱入口页静默升级为强证据。
- `pricing / weaknesses` 在能力介绍样本中不作为 blocker；`pricing` 在标准版弱入口样本中必须进入字段路径采集。
- 所有输出字段保持 `sourceUrls` 可追溯。

## 不在 06 中做

- 不重建 `CoverageContract / EvidenceQualityGate / PublicEvidenceRecoveryService`。
- 不实现跨字段 `EvidenceGraphBuilder` 或 `CrossFieldEvidenceLinker`。
- 不把第三方口碑、新闻、研究报告默认加入所有任务。
- 不承诺真实公网每次都能命中定价或短板证据；06 只承诺搜索、采集、覆盖评估和失败结论可审计闭环。

## 验收口径声明：stub 闭环 ≠ live 修复

06 的 `Task66FieldFirstEvidenceLoopSystemTest` 全部使用 stub provider、不访问真实网络，证明的是**字段优先闭环机制在桩数据下成立**（多 query 规划、逐条执行、覆盖聚合、门控收紧、再入采集、失败结论）。它**不**等同于 `live-bilibili-public-evidence-recovery-20260629-174119-rerun` 那条 live 场景已被真正修复。

- 06 单元/系统测试 PASS = 工程闭环可验收，是 06 的 Definition of Done。
- live bilibili 标准版弱入口的真实命中复验属于**后续 live 复验事项**，由 06 的“可选 live smoke”（Task 10 Step 4）观察真实命中率，但不作为 06 单元验收的必要条件。
- 若 live smoke 显示真实公网仍无 pricing/coreFeatures 深页证据，那是“公开资料不存在”，应表现为 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH`，与 06 闭环是否正确无关，不回滚 06。
