# Search And Collection Second Iteration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在首轮 blocking 收口包之后，补齐搜索与采集链路仍保持 `🟡` 的恢复现场、完整 replay 时间线、preview/runtime 家族同构和排序质量硬化缺口。

**Architecture:** 本实施计划继承 [2026-06-12-search-and-collection-execution-engine.md](/E:/java_study/Mul-agnet/docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md) 的 `Wave 2 / Wave 3 / Wave 4` 剩余缺口，但不重做首轮已完成的 `SearchPolicyResolver`、`SearchKeywordPolicy`、`searchAudit` 最小 DTO 和 `Source Family Catalog` 骨架。实现顺序固定为 `红灯契约 -> 搜索事实源扩展 -> runtime/insight/replay 贯通 -> preview/runtime 家族同构 -> 排序质量硬化 -> 自动化与 live 复核`，不得扩散到 `DagExecutor` 总恢复策略、跨重启 replay 持久化或真实外部垂直 API 接入。

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. `attemptedTargets` 不得只停留在 `SearchExecutionCoordinator` 的局部变量里，必须进入 `SearchAuditSnapshot`、runtime event、节点洞察和 replay 响应。
2. `discardedCandidates` 必须作为正式事实源输出，解释为什么候选被丢弃，避免 replay 只能看到“最后选中了谁”。
3. `SearchProgressSnapshot` 的历史列表必须升级为稳定搜索 replay timeline，能展示步骤顺序、状态、候选数量、选中数量、丢弃数量和 `sourceUrls`。
4. preview 与 runtime 必须共享数据源家族解释字段，至少覆盖 `sourceFamilyKey / sourceFamilyRole / primaryTools / auxiliaryTools / queryTemplates`。
5. `SourceCandidateRanker` 与 `CollectionTargetSelector` 必须开始按数据源家族和 source type 输出质量解释，避免官网首页继续挤压文档、定价、新闻、GitHub 等高价值来源。
6. 新增对象继续强制包含或传递 `sourceUrls`，缺少来源时使用空列表，不允许静默省略字段语义。

### 本轮明确不做

1. 不重写 `DagExecutor`、`TaskSnapshotCacheService`、`TaskRecoveryService`、`NodeExecutionRecoveryPolicy` 的总恢复策略。
2. 不做跨重启 replay 持久化底座。
3. 不真实接入 News API、GitHub API、Twitter / Reddit、Crunchbase、Patent API。
4. 不做前端搜索详情页全量重设，只保证后端 DTO 和现有投影可被前端兼容消费。
5. 不把全部 `SourceCandidate / SourcePlan / SearchCollectionTarget` 对象瘦身一次做完；本轮只补正式事实源字段和测试。

---

## Progress Snapshot

当前阶段：第二轮契约自动化收口与 dev live smoke 已完成

- [x] 首轮执行计划：已完成
- [x] 恢复现场事实源扩展：已完成
- [x] 完整 replay 时间线贯通：已完成
- [x] 预览 / 运行数据源家族同构：已完成
- [x] 候选排序与选源质量硬化：已完成
- [x] 第二轮自动化回归：已完成
- [x] 第二轮 dev live 验收：已完成

| 阶段 | 核心目标 | 预期耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase F1 | 红灯锁定 attempted / discarded / timeline / homology 缺口 | 0.5 天 | 首轮 blocking 包已完成 | 已完成 |
| Phase F2 | 扩展搜索事实源模型并贯通 coordinator 输出 | 1-2 天 | Phase F1 红灯测试存在 | 已完成 |
| Phase F3 | 贯通 runtime event、insight、replay 响应 | 1 天 | Phase F2 输出对象稳定 | 已完成 |
| Phase F4 | 补齐 preview/runtime 数据源家族同构字段 | 1-2 天 | `Source Family Catalog` 骨架已存在 | 已完成 |
| Phase F5 | 硬化排序、选源与 discarded 解释 | 1-2 天 | Phase F2/F4 字段可用 | 已完成 |
| Phase F6 | 自动化回归与第二轮 live 验收 | 0.5-1 天 | Phase F2-F5 完成 | 已完成；dev live 任务 `39` 已补证 |

---

## File Structure

**Backend - Create**

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchReplayTimelineItem.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionUpdate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`

---

### Task 1: 锁定第二轮红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`

- [x] **Step 1: 新建搜索审计时间线红灯测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAuditTimelineContractTest {

    @Test
    void shouldExposeAttemptedDiscardedAndReplayTimelineInAuditSnapshot() {
        SourceCandidate selected = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .sourceType("DOCS")
                .selectionStage("SELECTED")
                .sourceFamilyKey("official")
                .build();
        SourceCandidate discarded = SourceCandidate.builder()
                .url("https://www.example.com/login")
                .sourceType("DOCS")
                .selectionStage("DISCARDED")
                .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
                .sourceFamilyKey("official")
                .build();

        SearchAuditSnapshot snapshot = SearchAuditSnapshot.builder()
                .attemptedTargets(List.of(SearchCollectionTarget.builder().candidate(selected).build()))
                .discardedCandidates(List.of(discarded))
                .replayTimeline(List.of(SearchReplayTimelineItem.builder()
                        .stepCode("SELECT_TARGETS")
                        .stepName("合并候选并选出最终采集目标")
                        .status("SUCCESS")
                        .candidateCount(2)
                        .selectedCount(1)
                        .discardedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(snapshot.getAttemptedTargets()).hasSize(1);
        assertThat(snapshot.getDiscardedCandidates()).extracting(SourceCandidate::getUrl)
                .containsExactly("https://www.example.com/login");
        assertThat(snapshot.getReplayTimeline()).extracting(SearchReplayTimelineItem::getStepCode)
                .containsExactly("SELECT_TARGETS");
        assertThat(snapshot.getReplayTimeline().get(0).getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
    }
}
```

- [x] **Step 2: 新建预览 / 运行同构红灯测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourcePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPreviewRuntimeHomologyContractTest {

    @Test
    void shouldCarrySourceFamilyContextFromPreviewPlanToRuntimeConfig() {
        SourcePlan plan = SourcePlan.builder()
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .sourceFamilyRole("PRIMARY_VERTICAL")
                .primaryTools(List.of("WEB_SCRAPER", "JINA_READER"))
                .auxiliaryTools(List.of("PUBLIC_SEARCH"))
                .queryTemplates(List.of("search-docs-primary"))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(plan.getSourceFamilyKey()).isEqualTo("official");
        assertThat(plan.getPrimaryTools()).contains("WEB_SCRAPER", "JINA_READER");
        assertThat(plan.getAuxiliaryTools()).contains("PUBLIC_SEARCH");
        assertThat(plan.getSourceUrls()).containsExactly("https://docs.example.com/reference");
    }
}
```

- [x] **Step 3: 扩展 replay 投影红灯测试**

```java
@Test
void shouldExposeSearchReplayTimelineAndDiscardedCandidates() {
    TaskNode collectNode = TaskNode.builder()
            .id(1L)
            .taskId(42L)
            .nodeName("collect_sources_docs")
            .agentType(AgentType.COLLECTOR)
            .status(TaskNodeStatus.SUCCESS)
            .outputData("""
                    {
                      "searchAudit":{
                        "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"},
                        "attemptedTargets":[{"candidate":{"url":"https://docs.example.com/reference"}}],
                        "discardedCandidates":[{"url":"https://www.example.com/login","selectionReason":"LOW_SIGNAL_UTILITY_PAGE"}],
                        "replayTimeline":[{"stepCode":"SELECT_TARGETS","status":"SUCCESS","sourceUrls":["https://docs.example.com/reference"]}],
                        "sourceUrls":["https://docs.example.com/reference"]
                      },
                      "sourceUrls":["https://docs.example.com/reference"]
                    }
                    """)
            .build();

    when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(collectNode));

    TaskReplayResponse response = service.getTaskReplay(42L);
    JsonNode payload = objectMapper.valueToTree(response);

    assertThat(payload.at("/searchReplays/0/searchAudit/attemptedTargets")).hasSize(1);
    assertThat(payload.at("/searchReplays/0/searchAudit/discardedCandidates")).hasSize(1);
    assertThat(payload.at("/searchReplays/0/timeline/0/stepCode").asText()).isEqualTo("SELECT_TARGETS");
}
```

- [x] **Step 4: 运行第二轮红灯测试集合**

Run:
`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest" test`

Expected:
- FAIL 在 `SearchReplayTimelineItem`、`SearchAuditSnapshot.attemptedTargets`、`SearchAuditSnapshot.discardedCandidates`、`SourcePlan.sourceFamilyKey` 或 replay timeline 字段不存在。

- [ ] **Step 5: 提交红灯基线**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditTimelineContractTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java
git commit -m "test(search): lock second iteration replay and homology contracts"
```

### Task 2: 扩展搜索事实源模型与 coordinator 输出

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchReplayTimelineItem.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionUpdate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

- [x] **Step 1: 新增搜索 replay 时间线对象**

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索阶段回放时间线条目。
 * 每个条目只描述一个搜索步骤的可解释现场，不能替代任务级 timeline。
 * 本对象是 SearchProgressSnapshot 的稳定 replay 投影，不能成为独立事实源；
 * 步骤顺序、状态、进度百分比必须继续以 progressHistory 为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchReplayTimelineItem {

    private String stepCode;
    private String stepName;
    private String status;
    private String message;
    private Integer completedSteps;
    private Integer totalSteps;
    private Integer progressPercent;
    private Integer candidateCount;
    private Integer attemptedCount;
    private Integer selectedCount;
    private Integer discardedCount;
    private Boolean degraded;
    private String degradationReason;
    private List<String> sourceUrls;
    private LocalDateTime updatedAt;
}
```

- [x] **Step 2: 扩展正式事实源 DTO**

```java
public class SearchAuditSnapshot {

    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    private List<SearchReplayTimelineItem> replayTimeline;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> selectedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<String> sourceUrls;
}
```

```java
public class SearchExecutionTrace {

    private Integer attemptedCandidateCount;
    private Integer discardedCandidateCount;
}
```

```java
public class SearchExecutionResult {

    private List<SearchCollectionTarget> attemptedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> replayTimeline;
}
```

```java
public class SearchExecutionUpdate {

    private List<SearchCollectionTarget> attemptedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> replayTimeline;
}
```

- [x] **Step 3: 让 `CollectionTargetSelector` 返回 discarded 候选**

```java
public class SearchSelectionDecision {

    private List<SearchCollectionTarget> selectedTargets;
    private List<SourceCandidate> updatedCandidates;
    private List<SourceCandidate> discardedCandidates;
    private List<String> sourceUrls;
}
```

```java
private List<SourceCandidate> resolveDiscardedCandidates(List<SourceCandidate> candidates,
                                                         Set<String> selectedUrls) {
    if (candidates == null || candidates.isEmpty()) {
        return List.of();
    }
    // selectionStage 的正式赋值在排序/选源硬化任务中完成。
    // 当前阶段只把已经被上游打为 DISCARDED 的候选透传到事实源；
    // 未打标但未选中的候选仍保留在 sourceCandidates，避免把“未选择”误判为“已丢弃”。
    return candidates.stream()
            .filter(candidate -> candidate != null && "DISCARDED".equalsIgnoreCase(candidate.getSelectionStage()))
            .filter(candidate -> !selectedUrls.contains(normalizeUrl(candidate.getUrl())))
            .toList();
}
```

- [x] **Step 4: 在 coordinator 中构造 attempted / discarded / timeline**

```java
List<SearchCollectionTarget> attemptedTargetList = new ArrayList<>(attemptedTargets.values());
List<SourceCandidate> discardedCandidates = selectionDecision.getDiscardedCandidates() == null
        ? resolveDiscardedCandidates(allCandidates)
        : selectionDecision.getDiscardedCandidates();
List<SearchReplayTimelineItem> replayTimeline = buildReplayTimeline(
        progressSnapshots,
        allCandidates,
        attemptedTargetList,
        selectedTargets,
        discardedCandidates
);
```

```java
private List<SearchReplayTimelineItem> buildReplayTimeline(List<SearchProgressSnapshot> progressSnapshots,
                                                           List<SourceCandidate> sourceCandidates,
                                                           List<SearchCollectionTarget> attemptedTargets,
                                                           List<SearchCollectionTarget> selectedTargets,
                                                           List<SourceCandidate> discardedCandidates) {
    if (progressSnapshots == null || progressSnapshots.isEmpty()) {
        return List.of();
    }
    List<String> selectedUrls = selectedTargets == null ? List.of() : selectedTargets.stream()
            .map(target -> target == null || target.getCandidate() == null ? null : target.getCandidate().getUrl())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    List<String> discoveredUrls = sourceCandidates == null ? List.of() : sourceCandidates.stream()
            .map(SourceCandidate::getUrl)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    return progressSnapshots.stream()
            .map(snapshot -> SearchReplayTimelineItem.builder()
                    .stepCode(snapshot.getCurrentStepCode())
                    .stepName(snapshot.getCurrentStep())
                    .status(snapshot.getStatus())
                    .message(snapshot.getMessage())
                    .completedSteps(snapshot.getCompletedSteps())
                    .totalSteps(snapshot.getTotalSteps())
                    .progressPercent(snapshot.getProgressPercent())
                    .candidateCount(sourceCandidates == null ? 0 : sourceCandidates.size())
                    .attemptedCount(attemptedTargets == null ? 0 : attemptedTargets.size())
                    .selectedCount(selectedTargets == null ? 0 : selectedTargets.size())
                    .discardedCount(discardedCandidates == null ? 0 : discardedCandidates.size())
                    .degraded(snapshot.getDegraded())
                    .degradationReason(snapshot.getDegradationReason())
                    .sourceUrls(resolveTimelineSourceUrls(snapshot.getCurrentStepCode(), discoveredUrls, selectedUrls))
                    .updatedAt(snapshot.getUpdatedAt())
                    .build())
            .toList();
}

private List<String> resolveTimelineSourceUrls(String stepCode,
                                               List<String> discoveredUrls,
                                               List<String> selectedUrls) {
    if ("DISCOVER_CANDIDATES".equals(stepCode) || "DISCOVER".equals(stepCode)) {
        return discoveredUrls == null ? List.of() : discoveredUrls;
    }
    if ("SELECT_TARGETS".equals(stepCode)) {
        return selectedUrls == null ? List.of() : selectedUrls;
    }
    return List.of();
}
```

- [x] **Step 5: 运行事实源模型测试**

Run:
`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest" test`

Expected:
- PASS

- [ ] **Step 6: 提交事实源模型扩展**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchReplayTimelineItem.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionResult.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionUpdate.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java
git commit -m "feat(search): expose attempted discarded and replay timeline facts"
```

### Task 3: 贯通 runtime event、insight 与 replay 响应

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`

- [x] **Step 1: 扩展事件、洞察与回放 DTO**

```java
public class SearchProgressEventPayload {

    private List<SearchCollectionTarget> attemptedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> replayTimeline;
}
```

```java
public class SearchReplaySnapshotResponse {

    private List<SearchReplayTimelineItem> timeline;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
}
```

```java
public class CollectorNodeInsightResponse {

    private List<SearchCollectionTarget> attemptedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> searchReplayTimeline;
}
```

- [x] **Step 2: 在 runtime event 中透传新事实源**

```java
payload.setAttemptedTargets(convertList(
        output.get("attemptedTargets"),
        new TypeReference<List<SearchCollectionTarget>>() {
        }));
payload.setDiscardedCandidates(convertList(
        output.get("discardedCandidates"),
        new TypeReference<List<SourceCandidate>>() {
        }));
payload.setReplayTimeline(convertList(
        output.get("searchReplayTimeline"),
        new TypeReference<List<SearchReplayTimelineItem>>() {
        }));
```

- [x] **Step 3: 在 replay projection 中优先读取 `searchAudit` 的正式字段**

```java
SearchAuditSnapshot searchAudit = convertValue(output.get("searchAudit"), SearchAuditSnapshot.class);
List<SearchReplayTimelineItem> timeline = searchAudit == null || searchAudit.getReplayTimeline() == null
        ? convertList(output.get("searchReplayTimeline"), new TypeReference<List<SearchReplayTimelineItem>>() {
        })
        : searchAudit.getReplayTimeline();

searchReplays.add(SearchReplaySnapshotResponse.builder()
        .nodeName(taskNode.getNodeName())
        .planVersionId(taskNode.getPlanVersionId())
        .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
        .branchKey(taskNode.getBranchKey())
        .latestProgress(latestProgress)
        .timeline(timeline == null ? List.of() : timeline)
        .searchAudit(searchAudit)
        .attemptedTargets(searchAudit == null || searchAudit.getAttemptedTargets() == null ? List.of() : searchAudit.getAttemptedTargets())
        .discardedCandidates(searchAudit == null || searchAudit.getDiscardedCandidates() == null ? List.of() : searchAudit.getDiscardedCandidates())
        .selectedTargets(selectedTargets)
        .sourceUrls(sourceUrls)
        .build());
```

- [x] **Step 4: 在节点洞察中透传 attempted / discarded / timeline**

```java
SearchAuditSnapshot searchAudit = convertValue(output == null ? null : output.get("searchAudit"), SearchAuditSnapshot.class);

return CollectorNodeInsightResponse.builder()
        .searchAudit(searchAudit)
        .attemptedTargets(searchAudit == null || searchAudit.getAttemptedTargets() == null ? List.of() : searchAudit.getAttemptedTargets())
        .discardedCandidates(searchAudit == null || searchAudit.getDiscardedCandidates() == null ? List.of() : searchAudit.getDiscardedCandidates())
        .searchReplayTimeline(searchAudit == null || searchAudit.getReplayTimeline() == null ? List.of() : searchAudit.getReplayTimeline())
        .build();
```

- [x] **Step 5: 运行事件 / 洞察 / replay 测试**

Run:
`mvn -pl backend "-Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest" test`

Expected:
- PASS

- [ ] **Step 6: 提交投影贯通**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java backend/src/test/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssemblerTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java
git commit -m "feat(search): project replay facts through runtime insight and replay"
```

### Task 4: 补齐 preview/runtime 数据源家族同构

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java`（新增私有 `buildSourcePlan(...)` helper，并替换当前 `SourcePlan.builder()` 调用）
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`

- [x] **Step 1: 在策略解析器中按 source type 解析数据源家族**

```java
public String resolveSourceFamilyKeyForSourceType(String sourceType) {
    if (!StringUtils.hasText(sourceType)) {
        return "official";
    }
    String normalizedSourceType = sourceType.trim().toUpperCase(Locale.ROOT);
    for (Map.Entry<String, SearchSourceCatalogProperties.SourceFamilyProperties> entry
            : resolveSourceCatalog().getFamilies().entrySet()) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = entry.getValue();
        if (family != null && family.getSourceTypes() != null
                && family.getSourceTypes().stream().anyMatch(type -> normalizedSourceType.equalsIgnoreCase(type))) {
            return entry.getKey();
        }
    }
    return "official";
}

public SearchSourceCatalogProperties.SourceFamilyProperties resolveSourceFamilyForSourceType(String sourceType) {
    return resolveSourceCatalog().resolveFamily(resolveSourceFamilyKeyForSourceType(sourceType));
}
```

Implementation note:
需要为 `SearchPolicyResolver` 补 `java.util.Map` import。

- [x] **Step 2: 扩展 `SourceCandidate / SourcePlan / CollectorNodeConfig` 的家族字段**

```java
public class SourceCandidate {

    private String sourceFamilyKey;
    private String sourceFamilyRole;
    private List<String> qualitySignals;
}
```

```java
public class SourcePlan {

    private String sourceFamilyKey;
    private String sourceFamilyRole;
    private List<String> primaryTools;
    private List<String> auxiliaryTools;
    private List<String> queryTemplates;
    private List<String> sourceUrls;
}
```

```java
public class CollectorNodeConfig {

    private String sourceFamilyKey;
    private String sourceFamilyRole;
    private List<String> primaryTools;
    private List<String> auxiliaryTools;
    private List<String> queryTemplates;
}
```

- [x] **Step 3: 在发现服务中新增 `buildSourcePlan(...)` 私有方法，并写入同一套家族语义**

Implementation note:
`HeuristicSourceDiscoveryService` 当前没有 `buildSourcePlan(...)` 方法，且直接在 3 处调用 `SourcePlan.builder()`。本步骤需要新建下面的私有 helper，并把现有 `SourcePlan.builder()` 调用统一替换成 `buildSourcePlan(...)`，避免 source family 字段只在部分分支写入。

```java
private SourcePlan buildSourcePlan(String scope,
                                   List<String> urls,
                                   List<SourceCandidate> candidates,
                                   String notes) {
    String familyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(scope);
    SearchSourceCatalogProperties.SourceFamilyProperties family =
            searchPolicyResolver.resolveSourceFamilyForSourceType(scope);
    return SourcePlan.builder()
            .sourceType(scope)
            .sourceFamilyKey(familyKey)
            .sourceFamilyRole(family == null ? SearchProviderRole.AUXILIARY_PUBLIC.name() : family.getRole())
            .primaryTools(family == null ? List.of() : family.getPrimaryTools())
            .auxiliaryTools(family == null ? List.of() : family.getAuxiliaryTools())
            .queryTemplates(family == null ? List.of() : family.getQueryTemplates())
            .urls(urls == null ? List.of() : urls)
            .sourceUrls(urls == null ? List.of() : urls)
            .candidates(candidates == null ? List.of() : candidates.stream()
                    .map(candidate -> candidate.toBuilder()
                            .sourceFamilyKey(familyKey)
                            .sourceFamilyRole(family == null ? SearchProviderRole.AUXILIARY_PUBLIC.name() : family.getRole())
                            .build())
                    .toList())
            .notes(notes)
            .build();
}
```

Implementation note:
`BrowserPreviewSearchSourceProvider` 不直接构造 `SourcePlan`，但它生成的 `SourceCandidate` 也要补齐 `sourceFamilyKey / sourceFamilyRole`，否则预览候选进入 `HeuristicSourceDiscoveryService` 合并后会出现同一 scope 下家族字段不一致。

- [x] **Step 4: 在 Collector 节点配置中持久化家族字段**

```java
return CollectorNodeConfig.builder()
        .competitorName(competitorName)
        .competitorUrls(sourcePlan == null || sourcePlan.getUrls() == null ? List.of() : sourcePlan.getUrls())
        .sourceType(sourcePlan == null ? null : sourcePlan.getSourceType())
        .sourceFamilyKey(sourcePlan == null ? null : sourcePlan.getSourceFamilyKey())
        .sourceFamilyRole(sourcePlan == null ? null : sourcePlan.getSourceFamilyRole())
        .primaryTools(sourcePlan == null ? List.of() : sourcePlan.getPrimaryTools())
        .auxiliaryTools(sourcePlan == null ? List.of() : sourcePlan.getAuxiliaryTools())
        .queryTemplates(sourcePlan == null ? List.of() : sourcePlan.getQueryTemplates())
        .build();
```

- [x] **Step 5: 运行同构测试**

Run:
`mvn -pl backend "-Dtest=SearchPreviewRuntimeHomologyContractTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest" test`

Expected:
- PASS

- [ ] **Step 6: 提交同构字段落地**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidate.java backend/src/main/java/cn/bugstack/competitoragent/source/SourcePlan.java backend/src/main/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryService.java backend/src/main/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProvider.java backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPreviewRuntimeHomologyContractTest.java backend/src/test/java/cn/bugstack/competitoragent/source/HeuristicSourceDiscoveryServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/source/BrowserPreviewSearchSourceProviderTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java
git commit -m "feat(search): carry source family context across preview and runtime"
```

### Task 5: 硬化排序、选源和 discarded 解释

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`

- [x] **Step 1: 增加数据源家族质量排序测试**

```java
@Test
void shouldPreferHighValueDocsPageOverGenericHomepageForDocsFamily() {
    List<SourceCandidate> ranked = ranker.rankAndDeduplicate(List.of(
            SourceCandidate.builder()
                    .url("https://www.example.com")
                    .title("Example Homepage")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .discoveryMethod("SEARCH")
                    .relevanceScore(0.92)
                    .freshnessScore(0.80)
                    .qualityScore(0.88)
                    .build(),
            SourceCandidate.builder()
                    .url("https://docs.example.com/api/reference")
                    .title("Example API Reference")
                    .sourceType("DOCS")
                    .sourceFamilyKey("official")
                    .discoveryMethod("SEARCH")
                    .relevanceScore(0.86)
                    .freshnessScore(0.65)
                    .qualityScore(0.84)
                    .build()
    ));

    assertThat(ranked.get(0).getUrl()).isEqualTo("https://docs.example.com/api/reference");
    assertThat(ranked.get(0).getQualitySignals()).contains("DOCS_HIGH_VALUE_PATH");
    assertThat(ranked.get(0).getRankingSummary()).contains("文档高价值路径");
}
```

- [x] **Step 2: 在 ranker 中加入 source type 高价值路径信号**

```java
private List<String> resolveQualitySignals(SourceCandidate candidate, String normalizedDomain) {
    List<String> signals = new ArrayList<>();
    String sourceType = defaultText(candidate.getSourceType()).toUpperCase(Locale.ROOT);
    String url = defaultText(candidate.getUrl()).toLowerCase(Locale.ROOT);
    if ("DOCS".equals(sourceType) && (normalizedDomain.startsWith("docs.") || url.contains("/docs")
            || url.contains("/documentation") || url.contains("/api") || url.contains("/reference"))) {
        signals.add("DOCS_HIGH_VALUE_PATH");
    }
    if ("PRICING".equals(sourceType) && (url.contains("/pricing") || url.contains("/plans")
            || url.contains("价格") || url.contains("定价"))) {
        signals.add("PRICING_HIGH_VALUE_PATH");
    }
    if ("NEWS".equals(sourceType) && (url.contains("/blog") || url.contains("/news")
            || url.contains("/changelog") || url.contains("/release"))) {
        signals.add("NEWS_HIGH_VALUE_PATH");
    }
    return signals;
}
```

```java
private double applyQualitySignalBoost(double total, List<String> qualitySignals) {
    if (qualitySignals == null || qualitySignals.isEmpty()) {
        return total;
    }
    return Math.min(1.0D, total + qualitySignals.size() * 0.08D);
}
```

- [x] **Step 3: 把 discarded 解释纳入选源决策测试**

```java
@Test
void shouldExposeDiscardedCandidatesWhenSelectingTargets() {
    SourceCandidate discarded = SourceCandidate.builder()
            .url("https://www.example.com/login")
            .selectionStage("DISCARDED")
            .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
            .totalScore(0.99)
            .build();
    SourceCandidate selected = SourceCandidate.builder()
            .url("https://docs.example.com/reference")
            .selectionStage("VERIFIED")
            .verified(Boolean.TRUE)
            .totalScore(0.80)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(List.of(discarded, selected), Map.of(), 1);

    assertThat(decision.getSelectedTargets()).hasSize(1);
    assertThat(decision.getDiscardedCandidates()).extracting(SourceCandidate::getUrl)
            .containsExactly("https://www.example.com/login");
}
```

- [x] **Step 4: 运行质量硬化测试**

Run:
`mvn -pl backend "-Dtest=SourceCandidateRankerTest,CollectionTargetSelectorTest,SearchAndCollectionGoldenMasterTest" test`

Expected:
- PASS

- [ ] **Step 5: 提交质量硬化**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/source/SourceCandidateRanker.java backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java
git commit -m "feat(search): harden source family ranking and discarded explanations"
```

### Task 6: 第二轮复核与文档回链

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
- Modify: `docs/superpowers/task/2026-06-12-search-and-collection-second-iteration-implementation-plan.md`

- [x] **Step 1: 运行第二轮目标测试集合**

Run:
`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest,SearchAndCollectionGoldenMasterTest" test`

Expected:
- PASS

- [x] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

- [x] **Step 3: 执行第二轮 dev live 验收**

Manual API smoke:

1. `POST /api/task/preview`，确认 `collector` 预览节点配置或 `configSummaryData` 可追溯到 `sourceFamilyKey / sourceFamilyRole / primaryTools / auxiliaryTools / queryTemplates`。
2. `POST /api/task/create`，确认入库 `CollectorNodeConfig` 保留同一组 source family 字段。
3. `POST /api/task/{id}/execute`，确认 `COLLECTOR` 节点输出包含 `searchAudit.attemptedTargets`、`searchAudit.discardedCandidates`、`searchAudit.replayTimeline`、`sourceUrls`。
4. `GET /api/task/{id}/replay`，确认 `searchReplays[*].timeline` 与 `searchReplays[*].searchAudit.replayTimeline` 都可见，且 sourceUrls 不为空。
5. `POST /api/task/{id}/nodes/{nodeName}/rerun`，确认重跑后 `attemptedTargets / discardedCandidates / replayTimeline` 不丢失。
6. `POST /api/task/{id}/resume`，确认恢复入口仍返回 200，且 `searchAudit.executionTrace.recoveryCheckpoint` 与 timeline 末尾步骤一致。

Expected:
- 搜索段满足 `specs` 实链验证 4 条硬条件中的过程可观测与恢复 / 回放 / 重跑要求。
- 若完整任务仍停在 `extract_schema`，只记录为提取结构化链路阻塞，不回退本轮搜索段事实源验收。

- [x] **Step 4: 回写方案与 specs 状态**

```md
- 实施：`🟡` 第二轮自动化契约收口已完成后，搜索执行引擎仍保留 Wave 5 对象瘦身与 Wave 6 真实垂直 provider / 主辅路由闭环待推进；不得仅因本轮自动化通过就升为全链路 `✅`。
- 实链验证：`🟡` 搜索段 2026-06-12 dev live 验收仍作为首轮证据；第二轮 dev live API smoke 已于 2026-06-15 通过任务 `39` 补证：`/api/task/preview` 与 `/api/task/create` 均可见 `sourceFamilyKey=official`、`sourceFamilyRole=PRIMARY_VERTICAL`、`primaryTools`、`auxiliaryTools`、`queryTemplates`；`/api/task/{id}/execute` 后 Collector output 保留 `searchAudit.attemptedTargets`、`searchAudit.replayTimeline`、`sourceUrls`；`/api/task/{id}/replay` 返回顶层 `timeline/attemptedTargets/discardedCandidates` 与嵌套 `searchAudit.replayTimeline`；`/nodes/collect_sources_01_01/rerun` 返回 200 后 `attemptedTargets=2`、`discardedCandidates=1`、`timeline=8` 未丢失；`/resume` 返回 200，`recoveryCheckpoint=SELECT_TARGETS` 与 timeline 末尾步骤一致。初次 execute 中两个 Collector 因 Playwright `__adopt__` / 反爬信号进入 `WAITING_INTERVENTION`，但搜索事实源、回放、重跑和恢复入口验收通过；完整任务闭环仍取决于提取结构化链路与采集质量。
```

- [ ] **Step 5: 提交第二轮复核文档**

```bash
git add docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md docs/superpowers/task/2026-06-12-search-and-collection-second-iteration-implementation-plan.md
git commit -m "docs(search): record second iteration replay closure verification"
```

---

## Verification

- 红灯契约：`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchAuditSnapshotCompatibilityTest,TaskReplayProjectionServiceTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest" test`
- 搜索事实源模型：`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest" test`
- 事件 / 洞察 / replay 投影：`mvn -pl backend "-Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest" test`
- preview/runtime 同构：`mvn -pl backend "-Dtest=SearchPreviewRuntimeHomologyContractTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest" test`
- 排序质量硬化：`mvn -pl backend "-Dtest=SourceCandidateRankerTest,CollectionTargetSelectorTest,SearchAndCollectionGoldenMasterTest" test`
- 第二轮整体：`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest,SearchAndCollectionGoldenMasterTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `attemptedTargets` 恢复现场事实源由 Task 1、Task 2、Task 3 覆盖。
2. `discardedCandidates` 丢弃解释由 Task 1、Task 2、Task 5 覆盖。
3. 完整搜索 replay timeline 由 Task 1、Task 2、Task 3 覆盖。
4. preview/runtime 数据源家族同构由 Task 1、Task 4 覆盖。
5. 排序与选源质量硬化由 Task 5 覆盖。
6. 自动化复核与第二轮 dev live smoke 均由 Task 6 覆盖；live 任务 `39` 已验证 preview/create/execute/replay/rerun/resume 六个端点。

### Placeholder scan

1. 本计划未使用空洞待补写法。
2. 每个任务都给出明确文件、命令和预期结果。
3. 本轮未把跨重启 replay、真实外部 API 接入或总恢复策略重构塞入 scope。

### Type consistency

1. `searchAudit` 始终对应 `SearchAuditSnapshot`。
2. `replayTimeline` 始终对应 `SearchReplayTimelineItem`。
3. `attemptedTargets` 始终对应 `List<SearchCollectionTarget>`。
4. `discardedCandidates` 始终对应 `List<SourceCandidate>`。
5. `sourceFamilyKey / sourceFamilyRole / primaryTools / auxiliaryTools / queryTemplates` 从 `SourcePlan` 投影到 `CollectorNodeConfig`。

---

## Execution Status

- 执行方式：2026-06-15 按用户要求直接在 `master` 工作树执行，未创建独立 worktree，未提交 commit。
- 当前状态：Task 1-5 与 Task 6 自动化复核已完成；`mvn -pl backend "-Dtest=SearchAuditTimelineContractTest,SearchPreviewRuntimeHomologyContractTest,SearchExecutionCoordinatorTest,SearchAuditSnapshotCompatibilityTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,HeuristicSourceDiscoveryServiceTest,BrowserPreviewSearchSourceProviderTest,WorkflowFactoryTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskNodeViewAssemblerTest,SearchAndCollectionGoldenMasterTest" test` 通过 49 tests；`mvn -pl backend test` 通过 438 tests。第二轮 dev live API smoke 已于 2026-06-15 使用任务 `39` 完成，不把本次搜索段 smoke 等同于完整业务闭环升绿；`Wave 5` 对象瘦身、`Wave 6` 垂直 provider 与最终报告质量闭环仍需后续独立验收。
- 依赖前置条件：首轮执行计划已完成，并且父方案文档仍作为方案基线保留；`Wave 5` 对象瘦身与 `Wave 6` 垂直 provider 仍需后续独立实施。
