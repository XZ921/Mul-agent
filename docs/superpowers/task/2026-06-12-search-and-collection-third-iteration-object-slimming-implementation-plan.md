# Search And Collection Third Iteration Object Slimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 中 `Wave 5` 的对象瘦身与底座化，落成可执行的第三轮实施计划。

**Architecture:** 本轮只承接 `Wave 5`，默认第一轮 blocking 收口包与第二轮 replay / homology / ranking 计划已完成或会先完成。本轮不改变搜索业务能力，而是把 `CollectorNodeConfig`、`SearchExecutionPlan`、`SourceCandidate`、`SearchCollectionTarget`、`SearchAuditSnapshot`、`AgentContext`、`TaskSnapshotCacheService`、`ReportResponse` 等对象的职责边界收窄，形成“原始现场留在节点 output、共享上下文只传稳定投影、下游 DTO 只消费瘦身视图”的分层。

**Tech Stack:** Java 17, Spring Boot, Jackson, Redis, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. 建立对象瘦身红灯契约，锁定 Collector 原始输出、搜索审计、共享投影、下游投影之间的边界。
2. 明确 `CollectorNodeConfig` 与 `SearchExecutionPlan` 的主从关系：节点配置只保存运行所需开关和计划引用，搜索步骤、预算、fallback 顺序由 `SearchExecutionPlan` 承接。
3. 拆清 `SourceCandidate`、`SourcePlan`、`SearchCollectionTarget` 的职责：候选只表达来源元数据，计划只表达家族/范围/候选池，采集目标只表达最终采集动作和轻量页面摘要。
4. 收窄 `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchExecutionResult`、`SearchExecutionUpdate` 的协议边界，新增正式摘要对象，避免每个对象都重复挂全量现场。
5. 让 `AgentContext`、`DagExecutor`、`TaskSnapshotCacheService` 只共享 `SearchSharedProjection` 或 `SharedNodeOutputEnvelope`，不再回灌 Collector 大 JSON。
6. 统一 `ReportService`、`EvidenceQueryService`、`ReportResponse`、`CollectorNodeInsightResponse` 对搜索现场的下游投影，不再各自解析 outputData。
7. 把数据源家族配置与 provider 路由配置的职责拆分平台化：`SearchSourceCatalogProperties` 只表达业务家族、工具、内容范围和模板引用；`SearchProviderProperties` 只表达 provider 启停、顺序、fail-open 和运行策略，不再混入业务 source type。
8. 所有新对象继续遵守 `sourceUrls` 红线。

### 本轮明确不做

1. 不重构 `NodeExecutionRecoveryPolicy` 和 `RecoveryEngine` 的任务恢复总策略。
2. 不做跨重启 replay 持久化底座。
3. 不接入真实外部垂直 API provider。
4. 不做前端搜索详情页和回放页的全量协议切换。
5. 不删除历史兼容字段；确需收缩时先新增瘦身视图，再用测试证明旧字段不再作为主路径消费。

---

## Progress Snapshot

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase G1 | 锁定对象瘦身红灯契约 | 0.5 天 | 第二轮事实源字段已确定 | 待执行 |
| Phase G2 | 收缩搜索领域对象职责 | 1-2 天 | Phase G1 红灯测试存在 | 待执行 |
| Phase G3 | 分层共享上下文、Redis 缓存和恢复输入 | 1-2 天 | Phase G2 投影对象稳定 | 待执行 |
| Phase G4 | 统一 insight / report / evidence 下游投影 | 1 天 | Phase G2/G3 完成 | 待执行 |
| Phase G5 | 平台化 source family 与 provider 配置职责边界 | 0.5-1 天 | Phase G2 对象字段稳定 | 待执行 |
| Phase G6 | 自动化复核与文档回链 | 0.5 天 | Phase G1-G5 完成 | 待执行 |

---

## File Structure

**Backend - Create**

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSummary.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectedTargetSummary.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelope.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedNodeOutputProjector.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchObjectSlimmingContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelopeTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/SearchProjectionConsumerContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/SearchProviderConfigurationBoundaryTest.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionPlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionUpdate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCollectionTarget.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- `backend/src/main/resources/application.yml`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

**Docs - Modify**

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

### Task 1: 锁定对象瘦身红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchObjectSlimmingContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelopeTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/report/SearchProjectionConsumerContractTest.java`

- [ ] **Step 1: 新建 Collector 共享投影瘦身红灯测试**

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchObjectSlimmingContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldBuildSmallSharedProjectionWithoutLargeCollectorPayload() throws Exception {
        String rawOutput = """
                {
                  "sourceUrls": ["https://docs.example.com/reference"],
                  "issueFlags": ["SEARCH_AUDIT_READY"],
                  "results": [
                    {
                      "url": "https://docs.example.com/reference",
                      "title": "Reference",
                      "content": "large-body-large-body-large-body"
                    }
                  ],
                  "selectedTargets": [
                    {
                      "url": "https://docs.example.com/reference",
                      "collectedPage": {
                        "content": "large-body-large-body-large-body"
                      }
                    }
                  ],
                  "searchExecutionTrace": {
                    "fallbackDecision": "PRIMARY_THEN_AUXILIARY",
                    "degradationReason": "AUXILIARY_NOT_USED"
                  },
                  "searchAudit": {
                    "sourceUrls": ["https://docs.example.com/reference"]
                  }
                }
                """;

        assertThat(SearchSharedProjection.supportsCollectorOutput(objectMapper, rawOutput)).isTrue();

        SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, rawOutput);
        String serialized = objectMapper.writeValueAsString(projection);

        assertThat(projection.getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(projection.getSelectedUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(serialized).doesNotContain("large-body");
        assertThat(serialized.length()).isLessThan(600);
    }
}
```

- [ ] **Step 2: 新建共享输出信封红灯测试**

```java
package cn.bugstack.competitoragent.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedNodeOutputEnvelopeTest {

    @Test
    void shouldRecordProjectionMetadataAndSourceUrls() {
        SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
                .taskId(33L)
                .nodeName("collect_sources_01_01")
                .planVersionId(7L)
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson("{\"sourceUrls\":[\"https://docs.example.com/reference\"]}")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(envelope.getProjectionType()).isEqualTo("SEARCH_SHARED_PROJECTION_V1");
        assertThat(envelope.getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(envelope.getPayloadJson()).doesNotContain("collectedPage");
    }
}
```

- [ ] **Step 3: 新建下游投影消费红灯测试**

```java
package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.search.SearchAuditSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProjectionConsumerContractTest {

    @Test
    void shouldExposeSearchAuditSummaryWithoutFullSearchAuditSnapshot() {
        ReportResponse.SearchAuditOverview overview = ReportResponse.SearchAuditOverview.builder()
                .collectorNodeCount(1)
                .selectedCandidateCount(1)
                .collectors(List.of(ReportResponse.CollectorSearchAudit.builder()
                        .nodeName("collect_sources_01_01")
                        .selectedUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .searchAuditSummary(SearchAuditSummary.builder()
                        .selectedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();

        assertThat(overview.getSearchAuditSummary().getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
        assertThat(overview.getCollectors()).hasSize(1);
    }
}
```

- [ ] **Step 4: 运行红灯测试并确认失败**

Run:
`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest" test`

Expected:
- FAIL
- `SharedNodeOutputEnvelope` 不存在。
- `SearchAuditSummary` 不存在。
- `ReportResponse.SearchAuditOverview#getSearchAuditSummary` 不存在。

- [ ] **Step 5: 提交红灯契约**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchObjectSlimmingContractTest.java backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelopeTest.java backend/src/test/java/cn/bugstack/competitoragent/report/SearchProjectionConsumerContractTest.java
git commit -m "test(search): lock object slimming contracts"
```

---

### Task 2: 收缩搜索领域对象职责

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSummary.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectedTargetSummary.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchCollectionTarget.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`

- [ ] **Step 1: 新建搜索审计摘要对象**

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索审计轻量摘要。
 * 该对象面向下游报告、洞察和恢复入口，只保留计数、结论和 sourceUrls，
 * 不承载完整候选池、完整执行计划或页面正文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSummary {

    private Integer candidateCount;
    private Integer selectedCount;
    private Integer discardedCount;
    private Integer attemptedCount;
    private Boolean degraded;
    private String degradationReason;
    private String fallbackDecision;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;

    public static SearchAuditSummary from(SearchAuditSnapshot snapshot) {
        if (snapshot == null) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        SearchExecutionTrace trace = snapshot.getExecutionTrace();
        return SearchAuditSummary.builder()
                .candidateCount(size(snapshot.getSourceCandidates()))
                .selectedCount(size(snapshot.getSelectedTargets()))
                .discardedCount(size(snapshot.getDiscardedCandidates()))
                .attemptedCount(size(snapshot.getAttemptedTargets()))
                .degraded(trace == null ? null : trace.getDegraded())
                .degradationReason(trace == null ? null : trace.getDegradationReason())
                .fallbackDecision(trace == null ? null : trace.getFallbackDecision())
                .recoveryCheckpoint(trace == null ? null : trace.getRecoveryCheckpoint())
                .sourceUrls(snapshot.getSourceUrls() == null ? List.of() : snapshot.getSourceUrls())
                .build();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
```

- [ ] **Step 2: 新建最终采集目标轻量摘要对象**

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 最终采集目标轻量摘要。
 * 用于共享上下文、报告投影和前端洞察，避免把 SourceCollector.CollectedPage 正文传到下游。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchSelectedTargetSummary {

    private String url;
    private String title;
    private String sourceType;
    private String sourceFamilyKey;
    private String providerKey;
    private String selectionStage;
    private String selectionReason;
    private Boolean reusedCollectedPage;
    private List<String> sourceUrls;
}
```

- [ ] **Step 3: 给 SearchAuditSnapshot 增加摘要字段并保留兼容字段**

Add fields to `SearchAuditSnapshot`:

```java
/**
 * 轻量审计摘要，供 insight / report / replay 主路径消费。
 * 完整候选、计划和 trace 字段继续保留为兼容字段，但下游新代码优先读取 summary。
 */
private SearchAuditSummary summary;
private List<SearchCollectionTarget> attemptedTargets;
private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
```

- [ ] **Step 4: 给 SearchExecutionResult 增加下游共享投影**

Add fields to `SearchExecutionResult`:

```java
/**
 * Collector 输出给下游共享上下文的稳定投影。
 * 原始 auditSnapshot 仍用于节点详情与回放，sharedProjection 用于下游节点消费。
 */
private SearchSharedProjection sharedProjection;
```

- [ ] **Step 5: 扩展 SearchSharedProjection 为正式共享视图**

Add fields to `SearchSharedProjection`:

```java
private String projectionType;
private String recoveryCheckpoint;
private SearchAuditSummary searchAuditSummary;
private List<SearchSelectedTargetSummary> selectedTargets;
```

Update constructor usages so default `projectionType` is `SEARCH_SHARED_PROJECTION_V1` and sourceUrls never becomes `null`.

- [ ] **Step 6: 给 SourceCandidate / SourcePlan / SearchCollectionTarget 补齐轻量边界字段**

Add to `SourceCandidate`:

```java
private String sourceFamilyKey;
private String sourceFamilyRole;
private String providerKey;
private String providerRole;
private List<String> sourceUrls;
```

Final `SourceCandidate` field ownership after the second, third and fourth iteration:

```java
// 基础来源元数据，第一轮/第二轮已存在或补齐。
private String url;
private String title;
private String sourceType;
private String discoveryMethod;
private String reason;
private String domain;
private String publishedAt;

// 排序、验证与选源字段，第二轮负责稳定语义。
private double relevanceScore;
private double freshnessScore;
private double qualityScore;
private double totalScore;
private SourceTrustTier trustTier;
private String trustTierLabel;
private List<String> rankingReasons;
private String rankingSummary;
private List<String> qualitySignals;
private Boolean verified;
private String verificationReason;
private List<String> matchedSignals;
private String selectionStage;
private String selectionReason;
private String selectionSummary;

// 预览/运行同构字段，第二轮负责从 SourcePlan 传到候选。
private String sourceFamilyKey;
private String sourceFamilyRole;

// provider 路由字段，第三轮先保留对象边界，第四轮由真实 provider 和路由器写入。
private String providerKey;
private String providerRole;

// 可追溯红线字段，所有新增候选都必须显式写入或传递。
private List<String> sourceUrls;
```

Add to `SourcePlan`:

```java
private String sourceFamilyKey;
private String sourceFamilyRole;
private List<String> primaryTools;
private List<String> auxiliaryTools;
private List<String> queryTemplates;
```

Add to `SearchCollectionTarget`:

```java
/**
 * selectedSummary 是正式共享与下游投影入口。
 * collectedPage 只服务当前 Collector 节点内部复用，不进入共享上下文。
 */
private SearchSelectedTargetSummary selectedSummary;
```

- [ ] **Step 7: 运行领域对象测试**

Run:
`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SearchAuditSnapshotCompatibilityTest,SearchExecutionCoordinatorTest" test`

Expected:
- PASS

- [ ] **Step 8: 提交领域对象瘦身**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSummary.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectedTargetSummary.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchCollectionTarget.java backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchObjectSlimmingContractTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java
git commit -m "refactor(search): slim search runtime objects"
```

---

### Task 3: 分层共享上下文与 Redis 缓存

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelope.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjector.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedNodeOutputProjector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`

- [ ] **Step 1: 新建共享节点输出信封**

```java
package cn.bugstack.competitoragent.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点共享输出信封。
 * 该对象只描述下游可消费的稳定投影元数据，不保存节点原始 outputData。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedNodeOutputEnvelope {

    private Long taskId;
    private String nodeName;
    private Long planVersionId;
    private String projectionType;
    private String payloadJson;
    private List<String> sourceUrls;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

- [ ] **Step 2: 新建共享输出投影器接口**

```java
package cn.bugstack.competitoragent.task;

/**
 * 节点共享输出投影器。
 * DagExecutor 只依赖这个通用策略接口，不按 AgentType 写搜索链路分支。
 */
public interface SharedNodeOutputProjector {

    boolean supports(String outputData);

    SharedNodeOutputEnvelope project(Long taskId,
                                     String nodeName,
                                     Long planVersionId,
                                     String outputData);
}
```

- [ ] **Step 3: 新建搜索共享输出投影器**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 搜索 Collector 输出投影器。
 * 该类负责把搜索节点原始 outputData 裁剪为 SearchSharedProjection；
 * DagExecutor 不需要知道当前节点是否是 Collector，也不需要解析搜索字段。
 */
@Component
@RequiredArgsConstructor
public class SearchSharedNodeOutputProjector implements SharedNodeOutputProjector {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String outputData) {
        return SearchSharedProjection.supportsCollectorOutput(objectMapper, outputData);
    }

    @Override
    public SharedNodeOutputEnvelope project(Long taskId,
                                            String nodeName,
                                            Long planVersionId,
                                            String outputData) {
        SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, outputData);
        return SharedNodeOutputEnvelope.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson(writeProjection(projection))
                .sourceUrls(projection.getSourceUrls() == null ? java.util.List.of() : projection.getSourceUrls())
                .build();
    }

    private String writeProjection(SearchSharedProjection projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            throw new IllegalStateException("serialize search shared projection failed", e);
        }
    }
}
```

- [ ] **Step 4: 扩展 AgentContext 保存共享信封**

Add to `AgentContext`:

```java
@Builder.Default
private Map<String, cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope> sharedOutputEnvelopes =
        new ConcurrentHashMap<>();

public cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope getSharedOutputEnvelope(String nodeName) {
    return sharedOutputEnvelopes.get(nodeName);
}

public void putSharedOutputEnvelope(String nodeName,
                                    cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope envelope) {
    if (nodeName != null && !nodeName.isBlank() && envelope != null) {
        sharedOutputEnvelopes.put(nodeName, envelope);
        sharedState.put(nodeName, envelope.getPayloadJson());
    }
}
```

- [ ] **Step 5: 在 TaskSnapshotCacheService 中增加信封缓存方法**

Add methods:

```java
public void cacheSharedOutputEnvelope(Long taskId, SharedNodeOutputEnvelope envelope) {
    if (taskId == null || envelope == null || envelope.getNodeName() == null || envelope.getPayloadJson() == null) {
        return;
    }
    try {
        String runtimeKey = buildRuntimeKey(taskId);
        stringRedisTemplate.opsForHash().put(runtimeKey, envelope.getNodeName(), objectMapper.writeValueAsString(envelope));
        stringRedisTemplate.expire(runtimeKey, redisProperties.getRuntimeTtl());
    } catch (Exception e) {
        log.warn("cache shared output envelope to redis failed, taskId={}, nodeName={}",
                taskId, envelope.getNodeName(), e);
    }
}

public Map<String, SharedNodeOutputEnvelope> getCachedSharedOutputEnvelopes(Long taskId) {
    Map<String, String> rawOutputs = getCachedNodeOutputs(taskId);
    java.util.Map<String, SharedNodeOutputEnvelope> envelopes = new java.util.LinkedHashMap<>();
    rawOutputs.forEach((nodeName, rawValue) -> {
        try {
            SharedNodeOutputEnvelope envelope = objectMapper.readValue(rawValue, SharedNodeOutputEnvelope.class);
            envelopes.put(nodeName, envelope);
        } catch (Exception ignored) {
            envelopes.put(nodeName, SharedNodeOutputEnvelope.builder()
                    .taskId(taskId)
                    .nodeName(nodeName)
                    .projectionType("LEGACY_STRING_OUTPUT")
                    .payloadJson(rawValue)
                    .sourceUrls(List.of())
                    .build());
        }
    });
    return envelopes;
}
```

- [ ] **Step 6: 修改 DagExecutor 续跑种子逻辑优先使用信封**

Change `seedSharedOutputs(...)` so it first loads `getCachedSharedOutputEnvelopes(...)`:

```java
taskSnapshotCacheService.getCachedSharedOutputEnvelopes(context.getTaskId())
        .forEach(context::putSharedOutputEnvelope);
```

When a successful node exists in database, try generic projectors before falling back to raw output:

```java
projectSharedOutput(context.getTaskId(), node.getNodeName(), context.getPlanVersionId(), node.getOutputData())
        .ifPresentOrElse(
                envelope -> context.putSharedOutputEnvelope(node.getNodeName(), envelope),
                () -> context.putSharedOutput(node.getNodeName(), node.getOutputData())
        );
```

- [ ] **Step 7: 修改节点成功后的缓存路径**

In `DagExecutor` node success handling, keep database `outputData` unchanged and let generic projectors decide whether to create an envelope:

```java
projectSharedOutput(taskId, savedNode.getNodeName(), savedNode.getPlanVersionId(), result.getOutputData())
        .ifPresentOrElse(envelope -> {
            sharedContext.putSharedOutputEnvelope(savedNode.getNodeName(), envelope);
            taskSnapshotCacheService.cacheSharedOutputEnvelope(taskId, envelope);
        }, () -> {
            sharedContext.putSharedOutput(savedNode.getNodeName(), result.getOutputData());
            taskSnapshotCacheService.cacheNodeOutput(taskId, savedNode.getNodeName(), result.getOutputData());
        });
```

Add helper:

```java
private Optional<SharedNodeOutputEnvelope> projectSharedOutput(Long taskId,
                                                               String nodeName,
                                                               Long planVersionId,
                                                               String outputData) {
    if (outputData == null || outputData.isBlank()) {
        return Optional.empty();
    }
    return sharedNodeOutputProjectors.stream()
            .filter(projector -> projector.supports(outputData))
            .findFirst()
            .map(projector -> projector.project(taskId, nodeName, planVersionId, outputData));
}
```

- [ ] **Step 8: 运行共享上下文与缓存测试**

Run:
`mvn -pl backend "-Dtest=SharedNodeOutputEnvelopeTest,TaskSnapshotCacheServiceTest,DagExecutorTest" test`

Expected:
- PASS
- Collector 节点原始 outputData 仍在数据库节点详情中保留。
- Redis runtime hash 中 Collector 节点值为 `SharedNodeOutputEnvelope` JSON。
- `DagExecutor` 不包含 `savedNode.getAgentType() == AgentType.COLLECTOR` 这类搜索链路特定分支。

- [ ] **Step 9: 提交共享上下文分层**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelope.java backend/src/main/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjector.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedNodeOutputProjector.java backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputEnvelopeTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java
git commit -m "refactor(task): cache shared output envelopes"
```

---

### Task 4: 统一 insight / report / evidence 下游投影

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`

- [ ] **Step 1: CollectorNodeInsightResponse 增加轻量摘要入口**

Add fields:

```java
@Schema(description = "Lightweight search audit summary for default insight view")
private SearchAuditSummary searchAuditSummary;

@Schema(description = "Lightweight selected target summaries")
private List<SearchSelectedTargetSummary> selectedTargetSummaries;
```

- [ ] **Step 2: ReportResponse.SearchAuditOverview 增加摘要入口**

Add field:

```java
private SearchAuditSummary searchAuditSummary;
```

- [ ] **Step 3: TaskReplayProjectionService 优先使用 summary**

When building replay response, set:

```java
SearchAuditSummary summary = SearchAuditSummary.from(searchAudit);
responseBuilder.searchAuditSummary(summary);
```

Keep existing `searchAudit` field for compatibility, but new tests should assert summary exists and sourceUrls is not empty.

- [ ] **Step 4: ReportService 聚合搜索审计时只读取轻量摘要**

When building `ReportResponse.SearchAuditOverview`, aggregate from `SearchAuditSummary`:

```java
ReportResponse.SearchAuditOverview.SearchAuditOverviewBuilder overviewBuilder =
        ReportResponse.SearchAuditOverview.builder()
                .collectorNodeCount(collectorAudits.size())
                .searchAuditSummary(mergeSearchAuditSummaries(searchAuditSummaries));
```

Add private helper:

```java
private SearchAuditSummary mergeSearchAuditSummaries(List<SearchAuditSummary> summaries) {
    if (summaries == null || summaries.isEmpty()) {
        return SearchAuditSummary.builder()
                .candidateCount(0)
                .selectedCount(0)
                .discardedCount(0)
                .attemptedCount(0)
                .sourceUrls(List.of())
                .build();
    }
    java.util.LinkedHashSet<String> sourceUrls = new java.util.LinkedHashSet<>();
    int candidateCount = 0;
    int selectedCount = 0;
    int discardedCount = 0;
    int attemptedCount = 0;
    for (SearchAuditSummary summary : summaries) {
        candidateCount += summary.getCandidateCount() == null ? 0 : summary.getCandidateCount();
        selectedCount += summary.getSelectedCount() == null ? 0 : summary.getSelectedCount();
        discardedCount += summary.getDiscardedCount() == null ? 0 : summary.getDiscardedCount();
        attemptedCount += summary.getAttemptedCount() == null ? 0 : summary.getAttemptedCount();
        if (summary.getSourceUrls() != null) {
            sourceUrls.addAll(summary.getSourceUrls());
        }
    }
    return SearchAuditSummary.builder()
            .candidateCount(candidateCount)
            .selectedCount(selectedCount)
            .discardedCount(discardedCount)
            .attemptedCount(attemptedCount)
            .sourceUrls(new java.util.ArrayList<>(sourceUrls))
            .build();
}
```

- [ ] **Step 5: EvidenceQueryService 停止从 Collector 大 JSON 解析候选正文**

Before this task, code that needs evidence URLs may parse collector output details directly:

```java
JsonNode output = objectMapper.readTree(node.getOutputData());
JsonNode results = output.path("results");
for (JsonNode result : results) {
    String url = result.path("url").asText();
    String content = result.path("content").asText();
    // 这里会把 Collector 大 JSON 和页面正文重新带入下游 evidence 查询路径。
}
```

After this task, keep evidence source query on stored `EvidenceSource` / selected URL metadata. When a collector node output must be inspected, read `SearchSharedProjection` or `SearchAuditSummary` only:

```java
SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, node.getOutputData());
List<String> sourceUrls = projection.getSourceUrls() == null ? List.of() : projection.getSourceUrls();
```

If selected target summaries are needed, read the lightweight projection:

```java
List<SearchSelectedTargetSummary> selectedTargets = projection.getSelectedTargets() == null
        ? List.of()
        : projection.getSelectedTargets();
List<String> selectedUrls = selectedTargets.stream()
        .map(SearchSelectedTargetSummary::getUrl)
        .filter(org.springframework.util.StringUtils::hasText)
        .distinct()
        .toList();
```

- [ ] **Step 6: 运行下游投影测试**

Run:
`mvn -pl backend "-Dtest=SearchProjectionConsumerContractTest,TaskReplayProjectionServiceTest,ReportServiceTest,EvidenceQueryServiceTest" test`

Expected:
- PASS
- `ReportResponse.searchAuditOverview.searchAuditSummary.sourceUrls` 不为空。
- `CollectorNodeInsightResponse.searchAuditSummary` 存在。

- [ ] **Step 7: 提交下游投影统一**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/ReportResponse.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java backend/src/test/java/cn/bugstack/competitoragent/report/SearchProjectionConsumerContractTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/report/ReportServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java
git commit -m "refactor(search): unify downstream search projections"
```

---

### Task 5: 平台化数据源家族与 provider 配置职责边界

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/source/SearchProviderConfigurationBoundaryTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`

- [ ] **Step 1: 新建 Source Family Catalog 平台化契约测试**

```java
package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceCatalogPlatformContractTest {

    @Test
    void shouldKeepBusinessSourceFamilyFieldsInCatalogInsteadOfProviderRouting() {
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();

        SearchSourceCatalogProperties.SourceFamilyProperties official = catalog.resolveFamily("official");
        SearchSourceCatalogProperties.SourceFamilyProperties github = catalog.resolveFamily("github");

        assertThat(official.getSourceTypes()).contains("OFFICIAL", "PRICING", "DOCS");
        assertThat(official.getContentScopes()).contains("PRODUCT_PAGE", "PRICING", "DOCUMENTATION");
        assertThat(official.getPrimaryTools()).contains("WEB_SCRAPER", "JINA_READER");
        assertThat(official.getAuxiliaryTools()).contains("PUBLIC_SEARCH");
        assertThat(github.getPrimaryTools()).contains("GITHUB_API");
        assertThat(github.getQueryTemplates()).contains("search-github-repository", "search-github-release");
    }

    @Test
    void shouldResolveToolBindingsWithoutRequiringRealProviderImplementation() {
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();
        SearchSourceCatalogProperties.SourceFamilyProperties family = new SearchSourceCatalogProperties.SourceFamilyProperties();
        family.setRole(SearchProviderRole.PRIMARY_VERTICAL.name());
        family.setPrimaryTools(java.util.List.of("GITHUB_API"));
        family.setAuxiliaryTools(java.util.List.of("PUBLIC_SEARCH"));
        family.setToolProviderKeys(java.util.Map.of("GITHUB_API", "github"));
        catalog.getFamilies().put("github", family);

        assertThat(catalog.resolveFamily("github").resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL))
                .containsExactly("github");
        assertThat(catalog.resolveFamily("github").resolveProviderKeys(SearchProviderRole.AUXILIARY_PUBLIC))
                .isEmpty();
    }
}
```

- [ ] **Step 2: 新建 provider 配置边界测试**

```java
package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderConfigurationBoundaryTest {

    @Test
    void shouldKeepProviderPropertiesFocusedOnRoutingAndRuntimeOnly() {
        Set<String> forbiddenBusinessFields = Set.of(
                "sourceTypes",
                "contentScopes",
                "primaryTools",
                "auxiliaryTools",
                "queryTemplates",
                "updatePolicy"
        );

        assertThat(Arrays.stream(SearchProviderProperties.class.getDeclaredFields())
                .map(Field::getName)
                .filter(forbiddenBusinessFields::contains)
                .toList()).isEmpty();
    }

    @Test
    void shouldKeepRoutePropertiesFreeFromBusinessSourceFamilyFields() {
        Set<String> forbiddenBusinessFields = Set.of(
                "sourceFamilyKey",
                "sourceTypes",
                "contentScopes",
                "primaryTools",
                "queryTemplates"
        );

        assertThat(Arrays.stream(SearchProviderProperties.ProviderRouteProperties.class.getDeclaredFields())
                .map(Field::getName)
                .filter(forbiddenBusinessFields::contains)
                .toList()).isEmpty();
    }
}
```

- [ ] **Step 3: 给 SourceFamilyProperties 增加通用 tool -> provider 绑定字段**

Add to `SearchSourceCatalogProperties.SourceFamilyProperties`:

```java
/**
 * 工具到 provider key 的可选绑定。
 * Source Family Catalog 仍然只声明业务家族与工具语义；真实 provider 是否启用、是否 fail-open，
 * 继续由 SearchProviderProperties 管理。
 */
private Map<String, String> toolProviderKeys = new LinkedHashMap<>();
```

Add method:

```java
public List<String> resolveProviderKeys(SearchProviderRole role) {
    List<String> toolKeys = role == SearchProviderRole.PRIMARY_VERTICAL ? primaryTools : auxiliaryTools;
    if (toolKeys == null || toolKeys.isEmpty() || toolProviderKeys == null || toolProviderKeys.isEmpty()) {
        return List.of();
    }
    return toolKeys.stream()
            .map(toolProviderKeys::get)
            .filter(StringUtils::hasText)
            .toList();
}
```

Implementation note:
本轮只建立平台字段和解析方法，不要求默认 `github` family 一定绑定到真实 provider；真实 `GITHUB_API -> github` 默认绑定归第四轮 `Wave 6` 落地。

- [ ] **Step 4: 给 SearchPolicyResolver 增加工具绑定解析入口**

Add method:

```java
public List<String> resolveProviderKeysForSourceFamily(String familyKey, SearchProviderRole role) {
    SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
    if (family == null) {
        return List.of();
    }
    return family.resolveProviderKeys(role);
}
```

- [ ] **Step 5: 确认 SearchProviderProperties 只承接 provider 路由与运行策略**

Keep existing routing fields and add only generic routing knobs if missing:

```java
private int primaryCandidateThreshold = 1;
private boolean runAuxiliaryWhenPrimarySatisfied = false;
```

Do not add these fields to `SearchProviderProperties`:

```java
private List<String> sourceTypes;
private List<String> contentScopes;
private List<String> primaryTools;
private List<String> queryTemplates;
```

- [ ] **Step 6: 更新配置绑定测试覆盖边界字段**

Add property values to `SearchPropertiesBindingTest`:

```java
"search.source-catalog.families.github.tool-provider-keys.GITHUB_API=github",
"source-discovery.search.primary-candidate-threshold=1",
"source-discovery.search.run-auxiliary-when-primary-satisfied=false",
```

Add assertions:

```java
assertThat(searchProperties.getSourceCatalog().getFamilies().get("github").getToolProviderKeys())
        .containsEntry("GITHUB_API", "github");
assertThat(searchProviderProperties.getPrimaryCandidateThreshold()).isEqualTo(1);
assertThat(searchProviderProperties.isRunAuxiliaryWhenPrimarySatisfied()).isFalse();
```

- [ ] **Step 7: 运行配置职责边界测试**

Run:
`mvn -pl backend "-Dtest=SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,SearchPropertiesBindingTest" test`

Expected:
- PASS
- `SearchSourceCatalogProperties` 承接业务家族、工具、模板和可选工具绑定。
- `SearchProviderProperties` 不出现 source type、content scope、query template 等业务字段。

- [ ] **Step 8: 提交配置职责平台化**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchSourceCatalogPlatformContractTest.java backend/src/test/java/cn/bugstack/competitoragent/source/SearchProviderConfigurationBoundaryTest.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSourceCatalogProperties.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java backend/src/main/java/cn/bugstack/competitoragent/source/SearchProviderProperties.java backend/src/main/resources/application.yml backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java
git commit -m "refactor(search): separate source family and provider configuration"
```

---

### Task 6: 第三轮复核与文档回链

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/task/2026-06-12-search-and-collection-third-iteration-object-slimming-implementation-plan.md`

- [ ] **Step 1: 运行第三轮聚合测试**

Run:
`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest,SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,SearchAuditSnapshotCompatibilityTest,SearchExecutionCoordinatorTest,TaskSnapshotCacheServiceTest,DagExecutorTest,TaskReplayProjectionServiceTest,ReportServiceTest,EvidenceQueryServiceTest,SearchPropertiesBindingTest" test`

Expected:
- PASS

- [ ] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [ ] **Step 3: 回写父计划 Wave 5 状态**

Update `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`:

```md
| Phase E | 完成 `Wave 5` 对象瘦身、数据源家族配置平台化与底座化专题 | 1-2 个迭代 | Phase A-D 完成并复核 | 已完成 |
| Phase F | 完成 `Wave 6` 垂直 API provider 落地与主辅路由闭环 | 1 个迭代 | Phase E 已完成，且选定首个垂直 provider 的凭证或 Mock 契约 | 待执行 |
```

- [ ] **Step 4: 回写 specs 状态**

Update `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`:

```md
- 实施：`🟡` 第三轮对象瘦身与共享投影分层已完成，搜索链路不再把 Collector 大 JSON 作为共享上下文主路径；真实垂直 provider 与主辅路由闭环仍归 `Wave 6`。
```

- [ ] **Step 5: 标记本计划执行状态**

Append:

```md
## Execution Status

- 执行方式：待选择。
- 当前状态：计划已写入 `docs/superpowers/task`，尚未开始代码实现。
- 依赖前置条件：第一轮 blocking 收口包与第二轮 replay / homology / ranking 计划已经完成或先于本轮执行。
```

- [ ] **Step 6: 提交第三轮复核文档**

```bash
git add docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/task/2026-06-12-search-and-collection-third-iteration-object-slimming-implementation-plan.md
git commit -m "docs(search): plan third iteration object slimming"
```

---

## Verification

- 红灯契约：`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest" test`
- 搜索对象瘦身：`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SearchAuditSnapshotCompatibilityTest,SearchExecutionCoordinatorTest" test`
- 共享上下文与 Redis 缓存：`mvn -pl backend "-Dtest=SharedNodeOutputEnvelopeTest,TaskSnapshotCacheServiceTest,DagExecutorTest" test`
- 下游投影统一：`mvn -pl backend "-Dtest=SearchProjectionConsumerContractTest,TaskReplayProjectionServiceTest,ReportServiceTest,EvidenceQueryServiceTest" test`
- 配置职责平台化：`mvn -pl backend "-Dtest=SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,SearchPropertiesBindingTest" test`
- 第三轮整体：`mvn -pl backend "-Dtest=SearchObjectSlimmingContractTest,SharedNodeOutputEnvelopeTest,SearchProjectionConsumerContractTest,SearchSourceCatalogPlatformContractTest,SearchProviderConfigurationBoundaryTest,SearchAuditSnapshotCompatibilityTest,SearchExecutionCoordinatorTest,TaskSnapshotCacheServiceTest,DagExecutorTest,TaskReplayProjectionServiceTest,ReportServiceTest,EvidenceQueryServiceTest,SearchPropertiesBindingTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `CollectorNodeConfig` 与 `SearchExecutionPlan` 主从关系由 Task 2 覆盖。
2. `SourceCandidate`、`SourcePlan`、`SearchCollectionTarget` 职责拆分由 Task 2 覆盖。
3. `SearchAuditSnapshot`、`SearchExecutionTrace`、`SearchExecutionResult`、`SearchExecutionUpdate` 协议瘦身由 Task 1、Task 2 覆盖。
4. `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`AgentContext` 的共享上下文 / 恢复 / 缓存分层由 Task 3 覆盖。
5. `ReportService`、`EvidenceQueryService`、`ReportResponse`、`CollectorNodeInsightResponse` 下游投影统一由 Task 4 覆盖。
6. 数据源家族配置与 provider 配置职责拆分平台化由 Task 5 覆盖。
7. 文档回链与状态口径由 Task 6 覆盖。

### Placeholder scan

1. 本计划未使用空洞待补标记。
2. 每个任务都给出明确文件、代码片段、运行命令和预期结果。
3. 本轮没有把 `Wave 6` 真实垂直 provider 混入对象瘦身范围。

### Type consistency

1. `SearchAuditSummary` 始终作为轻量审计摘要。
2. `SearchSelectedTargetSummary` 始终作为最终采集目标轻量摘要。
3. `SharedNodeOutputEnvelope` 始终作为共享上下文与 Redis 缓存的信封对象。
4. `SearchSharedProjection` 始终作为 Collector 对下游共享的稳定事实投影。
5. `sourceUrls` 在新增摘要、投影和信封对象中均为显式字段。

---

## Execution Status

- 执行方式：待选择。
- 当前状态：计划已写入 `docs/superpowers/task`，尚未开始代码实现。
- 依赖前置条件：第一轮 blocking 收口包与第二轮 replay / homology / ranking 计划已经完成或先于本轮执行。
