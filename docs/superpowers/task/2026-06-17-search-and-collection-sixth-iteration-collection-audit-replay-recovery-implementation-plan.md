# Search And Collection Sixth Iteration Collection Audit Replay And Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 承接父方案 `Wave 9`，把第五轮已经落地的 `CollectionTaskPackage -> CollectionExecutionCoordinator -> CollectionExecutionResult -> CollectorAgent` 最小采集执行骨架升级为正式的 `collectionAudit / collectionReplayTimeline / collectionAuditCheckpoint / 包级 rerun-resume` 闭环，让采集段像搜索段一样具备可解释、可回放、可恢复能力。

**Architecture:** 本轮不重做 `Wave 8` 的 `JinaReader + Playwright FULL_RENDER` 双路径，也不提前扩展 `Wave 10` 的 `news / feed` 结构化采集 owner。实现顺序固定为 `红灯契约 -> collection 正式对象 -> Collector 输出与事件/洞察对齐 -> collection checkpoint 与包级恢复 -> replay / task view 对齐 -> 自动化与 live 验收`。为避免本轮同时引入前端 topic 切换，运行期事件继续复用现有 `SEARCH_PROGRESS_V1` 通道，但 payload 必须增量补齐 collection 字段。

**Tech Stack:** Java 17, Spring Boot, Jackson, JPA, JUnit 5, Mockito

---

## Scope Guard

### 本轮必须完成

1. 新建 `CollectionAuditSnapshot`、`CollectionReplayTimelineItem`、`CollectionExecutionReport` 与 `CollectionAuditSummary`，把采集段正式事实源从“只有结果列表”升级为“结果 + 时间线 + 恢复锚点 + 状态摘要”。
2. `CollectionTaskPackage` 必须引入稳定的 `packageKey / targetIndex` 身份字段，不再把 `priority` 临时当作包级恢复标识。
3. `CollectionExecutionCoordinator` 必须从返回裸 `List<CollectionExecutionResult>` 演进为返回正式聚合对象，并负责构建 `collectionAudit`、`collectionReplayTimeline`、`collectionStatus` 与 `recoveryCheckpoint`。
4. `CollectorAgent` 输出必须显式包含 `collectionAudit`，并继续保留旧的 `results / documents / issueFlags / sourceUrls`，确保后端下游与前端现有消费路径不被本轮破坏。
5. `CollectorNodeConfig`、`TaskRuntimeCommandAppService` 与 `CollectorAgent` 必须补齐 `collectionAuditCheckpoint`，支持 `rerun / resume` 时复用已成功包、只重跑失败包或等待人工处理包。
6. `RuntimeEventEmitter`、`CollectorNodeInsightResponse`、`TaskNodeViewAssembler`、`TaskReplayProjectionService`、`TaskReplayResponse` 必须对齐 collection 审计与回放字段，形成 `runtime / insight / replay / event` 的统一出口。
7. 采集段必须正式表达 `WAITING_INTERVENTION / DEGRADED / PARTIAL_SUCCESS / SUCCESS / FAILED` 状态，不允许再只通过 `issueFlags` 或字符串摘要暗示。
8. 所有新对象继续显式承接 `sourceUrls`，缺失时返回空列表，不允许静默丢失。

### 本轮明确不做

1. 不重做搜索段已有的 `SearchAuditSnapshot / SearchReplayTimelineItem / searchAuditCheckpoint` 契约。
2. 不新建独立的前端 collection 详情页协议，也不切换 SSE topic；本轮只要求后端字段向后兼容地补齐。
3. 不把 `ReportResponse` / 导出中心的 collection 级审计聚合提前并入本轮；这仍归后续 `Wave 12` 下游证据闭环。
4. 不重写 `RecoveryEngine`、`DagExecutor`、`TaskSnapshotCacheService`、`NodeExecutionRecoveryPolicy` 的总恢复策略。
5. 不提前接入 `News API / RSS / Feed` 的真实 collection owner，也不回头扩大 `Wave 8` 网页采集质量策略范围。
6. 不把跨重启、跨节点的全量 package-level replay 持久化底座一次做完；本轮只把 collector 节点内可复用的 package checkpoint 正式化。

---

## Review Adjustments

1. `CollectionExecutionResult.toBuilder()` 不是默认成立条件；本轮计划显式要求 `CollectionExecutionResult` 使用 `@Builder(toBuilder = true)`。如果实现阶段放弃 `toBuilder()`，必须同步把 `markReused(...)` 改成显式 copy factory，不能保留隐式依赖。
2. `CollectionExecutionResult.success` 与 `CollectionExecutionResult.status` 不能双轨漂移；本轮计划要求新增统一的 `normalize()` 或等价校验入口，所有 executor 返回结果、checkpoint 复用结果、兼容映射结果在落入正式 `results` 前都必须先归一化。
3. `CollectionReplayTimelineItem` 与 `SearchReplayTimelineItem` 保持有意的不对齐。collection timeline 只承载包级语义，search timeline 继续承载步骤语义；`TaskReplayResponse` 只在顶层聚合 `sourceUrls` 与节点级 replay，不做两类 timeline 的字段对齐复用。
4. `aggregateReplaySourceUrls(...)` 不能只停留在实现说明；本轮计划要求在 Task 5 里直接修改其方法签名与调用点，显式把 `collectionReplays[*].sourceUrls` 并入任务级 `sourceUrls`。
5. `CollectionAuditSummary` 继续放在 `model.dto` 包，保持与现有 `SearchAuditSummary` 一致；实现阶段不回退到 `collection` 包内，避免 insight / replay / event 摘要对象再次分叉。
6. 所有本轮新增正式对象至少要有一个构造或序列化契约测试；计划中补充 `CollectionAuditSerializationTest`，并要求 `TaskReplayContractPresenceTest` 对 `CollectionReplaySnapshotResponse` 增加序列化断言。

---

## Progress Snapshot

当前阶段：第六轮实施计划编写完成；第五轮 `Wave 8` 网页采集加固已完成自动化收口，工程当前真实缺口已切换为 `Wave 9` 的 collection 审计、回放与恢复闭环。

- [x] 父方案 `Wave 9` 目标复核：已完成
- [x] 前五轮实施与当前工程现状梳理：已完成
- [x] collection 子域现状核对：已完成
- [ ] 第六轮实施计划落稿：执行中
- [ ] 第六轮实现与验证：待执行

| 阶段 | 核心目标 | 预计耗时 | 依赖前置条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| Phase J1 | 锁定 `Wave 9` 红灯契约：`collectionAudit / replay / checkpoint / rerun-resume` | 0.5 天 | 第五轮 `Wave 8` 已完成 | 待执行 |
| Phase J2 | 正式化 collection 审计对象与协调器聚合结果 | 1-1.5 天 | Phase J1 红灯测试存在 | 待执行 |
| Phase J3 | `CollectorAgent` 输出、事件与洞察对齐 | 1 天 | Phase J2 完成 | 待执行 |
| Phase J4 | collection checkpoint 落库与包级 rerun-resume 语义 | 1-1.5 天 | Phase J2-J3 完成 | 待执行 |
| Phase J5 | replay / task view / recovery 出口对齐 | 1 天 | Phase J3-J4 完成 | 待执行 |
| Phase J6 | 自动化复核、文档回链与 dev live 验收 | 0.5-1 天 | Phase J1-J5 完成 | 待执行 |

---

## File Structure

### Backend - Create

- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionReplayTimelineItem.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionCheckpointRecorder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionAuditSummary.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionReplaySnapshotResponse.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionAuditContractTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorCheckpointReuseTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionAuditSerializationTest.java`

### Backend - Modify

- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/RecoveryCheckpointService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`

### Backend - Test

- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayContractPresenceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

### Docs - Modify

- `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

---

### Task 1: 锁定 `Wave 9` 红灯契约

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionAuditContractTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinatorCheckpointReuseTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionAuditSerializationTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayContractPresenceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

- [ ] **Step 1: 写 collection 正式审计契约红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionAuditContractTest {

    @Test
    void shouldExposeFormalCollectionAuditWithReplayTimelineAndSourceUrls() {
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .taskPackageKey("collect_sources_docs#001")
                .targetIndex(1)
                .executorType("WEB_PAGE")
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://docs.example.com/reference")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .build();

        CollectionAuditSnapshot audit = CollectionAuditSnapshot.builder()
                .summary(CollectionAuditSummary.builder()
                        .totalPackages(1)
                        .successCount(1)
                        .status("SUCCESS")
                        .recoveryCheckpoint("collect_sources_docs#001")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .status("SUCCESS")
                .results(List.of(result))
                .replayTimeline(List.of(CollectionReplayTimelineItem.builder()
                        .taskPackageKey("collect_sources_docs#001")
                        .targetIndex(1)
                        .status("SUCCESS")
                        .executorType("WEB_PAGE")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .recoveryCheckpoint("collect_sources_docs#001")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(audit.getSummary().getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getReplayTimeline()).extracting(CollectionReplayTimelineItem::getTaskPackageKey)
                .containsExactly("collect_sources_docs#001");
        assertThat(audit.getSourceUrls()).containsExactly("https://docs.example.com/reference");
    }
}
```

- [ ] **Step 2: 写包级 checkpoint 复用红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionExecutionCoordinatorCheckpointReuseTest {

    @Test
    void shouldReuseSuccessfulPackageAndOnlyRerunUnfinishedPackage() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(argThat(pkg -> "JINA_READER".equalsIgnoreCase(pkg.getPrimaryTool())))).thenReturn(true);
        when(executor.execute(argThat(pkg -> "collect_sources_docs#002".equals(pkg.getPackageKey()))))
                .thenReturn(CollectionExecutionResult.builder()
                        .taskPackageKey("collect_sources_docs#002")
                        .targetIndex(2)
                        .executorType("WEB_PAGE")
                        .success(true)
                        .status("SUCCESS")
                        .sourceUrls(List.of("https://docs.example.com/pricing"))
                        .build());

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor))
        );

        CollectionAuditSnapshot checkpoint = CollectionAuditSnapshot.builder()
                .results(List.of(CollectionExecutionResult.builder()
                        .taskPackageKey("collect_sources_docs#001")
                        .targetIndex(1)
                        .executorType("WEB_PAGE")
                        .success(true)
                        .status("SUCCESS")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .recoveryCheckpoint("collect_sources_docs#002")
                .build();

        SearchCollectionTarget target1 = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/reference")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();
        SearchCollectionTarget target2 = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/pricing")
                        .sourceType("PRICING")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/pricing"))
                        .build())
                .build();

        CollectionExecutionReport report = coordinator.execute(
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                List.of(target1, target2),
                checkpoint
        );

        assertThat(report.getResults()).hasSize(2);
        assertThat(report.getResults().get(0).getReusedFromCheckpoint()).isTrue();
        assertThat(report.getAuditSnapshot().getRecoveryCheckpoint()).isEqualTo("collect_sources_docs#002");
        verify(executor, times(1)).execute(argThat(pkg -> "collect_sources_docs#002".equals(pkg.getPackageKey())));
    }
}
```

- [ ] **Step 3: 补齐 collection 正式对象的构造与序列化红灯测试**

```java
package cn.bugstack.competitoragent.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionAuditSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldSerializeFormalCollectionAuditObjectsWithoutDroppingSourceUrls() throws Exception {
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .taskPackageKey("collect_sources_docs#001")
                .targetIndex(1)
                .executorType("WEB_PAGE")
                .success(true)
                .status("SUCCESS")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        CollectionReplayTimelineItem timelineItem = CollectionReplayTimelineItem.builder()
                .taskPackageKey("collect_sources_docs#001")
                .targetIndex(1)
                .status("SUCCESS")
                .executorType("WEB_PAGE")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        CollectionAuditSnapshot snapshot = CollectionAuditSnapshot.builder()
                .status("SUCCESS")
                .results(List.of(result))
                .replayTimeline(List.of(timelineItem))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        CollectionExecutionReport report = CollectionExecutionReport.builder()
                .results(List.of(result))
                .auditSnapshot(snapshot)
                .build();

        String json = objectMapper.writeValueAsString(report);
        CollectionExecutionReport restored = objectMapper.readValue(json, CollectionExecutionReport.class);

        assertThat(restored.getAuditSnapshot().getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(restored.getAuditSnapshot().getReplayTimeline())
                .extracting(CollectionReplayTimelineItem::getTaskPackageKey)
                .containsExactly("collect_sources_docs#001");
    }
}
```

- [ ] **Step 4: 把 Collector / runtime / replay / rerun 的红灯一起锁住**

Implementation note:
- `CollectorAgentTest` 追加断言：`output.collectionAudit.summary.status`、`output.collectionAudit.replayTimeline`、`output.collectionAudit.recoveryCheckpoint`、`output.collectionAudit.sourceUrls` 必须存在。
- `RuntimeEventEmitterTest` 追加断言：实时事件 payload 顶层必须带 `collectionAudit`、`collectionReplayTimeline`、`collectionStatus`。
- `TaskReplayProjectionServiceTest` 追加断言：`TaskReplayResponse.collectionReplays` 必须存在，并且回放条目能直接看到 `collectionAuditSummary.status`。
- `TaskRuntimeCommandAppServiceTest` 追加断言：`rerunFromNode(...)` 或 `resumeTask(...)` 后，collector 节点配置里会回填 `collectionAuditCheckpoint`。
- `AnalysisTaskServiceTest` 追加断言：`collectorInsight.collectionAuditSummary` 与 `collectorInsight.collectionReplayTimeline` 可被任务详情页直接消费。
- `TaskReplayContractPresenceTest` 追加 `CollectionReplaySnapshotResponse` 的序列化断言，保证 `timeline / collectionAuditSummary / sourceUrls` 可被稳定消费。

- [ ] **Step 5: 运行第六轮首批红灯测试**

Run:
`mvn -pl backend "-Dtest=CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectorAgentTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest" test`

Expected:
- FAIL
- `CollectionAuditSnapshot / CollectionReplayTimelineItem / CollectionExecutionReport / CollectionAuditSummary / CollectionReplaySnapshotResponse` 尚不存在
- `CollectionExecutionCoordinator` 仍返回 `List<CollectionExecutionResult>`
- `CollectorAgent` 输出里还没有 `collectionAudit`
- `TaskReplayResponse` 尚无 `collectionReplays`
- `TaskRuntimeCommandAppService` 尚不会回填 `collectionAuditCheckpoint`

---

### Task 2: 正式化 collection 审计对象与协调器聚合结果

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionReport.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionReplayTimelineItem.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionAuditSnapshot.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionAuditSummary.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackage.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilder.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionCoordinator.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/collection/CollectionTaskPackageBuilderTest.java`

- [ ] **Step 1: 新建 collection 审计摘要对象**

```java
package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionAuditSummary {

    private Integer totalPackages;
    private Integer successCount;
    private Integer failedCount;
    private Integer degradedCount;
    private Integer waitingInterventionCount;
    private Integer reusedCheckpointCount;
    private String status;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;

    public static CollectionAuditSummary from(CollectionAuditSnapshot snapshot) {
        if (snapshot == null || snapshot.getResults() == null) {
            return CollectionAuditSummary.builder()
                    .totalPackages(0)
                    .successCount(0)
                    .failedCount(0)
                    .degradedCount(0)
                    .waitingInterventionCount(0)
                    .reusedCheckpointCount(0)
                    .status("FAILED")
                    .sourceUrls(List.of())
                    .build();
        }
        int successCount = (int) snapshot.getResults().stream().filter(CollectionExecutionResult::isSuccess).count();
        int waitingInterventionCount = (int) snapshot.getResults().stream()
                .filter(result -> Boolean.TRUE.equals(result.getRequiresIntervention()))
                .count();
        int degradedCount = (int) snapshot.getResults().stream()
                .filter(result -> Boolean.TRUE.equals(result.getDegraded()))
                .count();
        int reusedCount = (int) snapshot.getResults().stream()
                .filter(result -> Boolean.TRUE.equals(result.getReusedFromCheckpoint()))
                .count();
        int totalPackages = snapshot.getResults().size();
        int failedCount = totalPackages - successCount;
        return CollectionAuditSummary.builder()
                .totalPackages(totalPackages)
                .successCount(successCount)
                .failedCount(failedCount)
                .degradedCount(degradedCount)
                .waitingInterventionCount(waitingInterventionCount)
                .reusedCheckpointCount(reusedCount)
                .status(snapshot.getStatus())
                .recoveryCheckpoint(snapshot.getRecoveryCheckpoint())
                .sourceUrls(snapshot.getSourceUrls() == null ? List.of() : snapshot.getSourceUrls())
                .build();
    }
}
```

- [ ] **Step 2: 新建 replay 时间线与正式审计快照对象**

```java
package cn.bugstack.competitoragent.collection;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionReplayTimelineItem {

    private String taskPackageKey;
    private Integer targetIndex;
    private String status;
    private String executorType;
    private String primaryTool;
    private String renderHint;
    private String resourceLocator;
    private String failureKind;
    private String message;
    private Boolean reusedFromCheckpoint;
    private Boolean degraded;
    private Boolean requiresIntervention;
    private Long durationMillis;
    private List<String> sourceUrls;
    private LocalDateTime updatedAt;
}
```

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionAuditSnapshot {

    private CollectionAuditSummary summary;
    private String status;
    private List<CollectionTaskPackage> taskPackages;
    private List<CollectionExecutionResult> results;
    private List<CollectionReplayTimelineItem> replayTimeline;
    private String recoveryCheckpoint;
    private String recoveryAdvice;
    private Boolean resumedFromCheckpoint;
    private String checkpointSource;
    private List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.collection;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionExecutionReport {

    private List<CollectionTaskPackage> taskPackages;
    private List<CollectionExecutionResult> results;
    private CollectionAuditSnapshot auditSnapshot;
}
```

- [ ] **Step 3: 扩展 `CollectionExecutionResult` 并收口状态一致性**

```java
package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class CollectionExecutionResult {

    String taskPackageKey;
    Integer targetIndex;
    String executorType;
    boolean success;
    String status;
    boolean reusedFromCheckpoint;
    String checkpointSource;
    Boolean degraded;
    String degradationReason;
    Boolean requiresIntervention;
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

    public CollectionExecutionResult normalize() {
        String normalizedStatus = status == null || status.isBlank()
                ? (success ? "SUCCESS" : "FAILED")
                : status.trim().toUpperCase();
        boolean normalizedSuccess = switch (normalizedStatus) {
            case "SUCCESS", "DEGRADED", "PARTIAL_SUCCESS" -> true;
            default -> false;
        };
        return this.toBuilder()
                .status(normalizedStatus)
                .success(normalizedSuccess)
                .sourceUrls(sourceUrls == null ? List.of() : sourceUrls)
                .build();
    }
}
```

Implementation note:
- `CollectionTaskPackage` 补齐 `packageKey` 与 `targetIndex`。
- `CollectionExecutionResult` 补齐 `taskPackageKey / targetIndex / status / reusedFromCheckpoint / checkpointSource / degraded / degradationReason / requiresIntervention`。
- `CollectionExecutionResult` 使用 `@Builder(toBuilder = true)`，后续 `markReused(...)` 才能显式复用现有结果；如果实现阶段改成显式 copy factory，必须同步替换示例代码与相关测试。
- `CollectionTaskPackageBuilder` 统一生成稳定的 `packageKey`，推荐格式：`<nodeName>#<三位序号>`，例如 `collect_sources_docs#001`。

- [ ] **Step 4: 让 `CollectionExecutionCoordinator` 构建正式 collectionAudit**

```java
public CollectionExecutionReport execute(Long taskId,
                                         String nodeName,
                                         Long planVersionId,
                                         String competitorName,
                                         List<SearchCollectionTarget> targets,
                                         CollectionAuditSnapshot checkpoint) {
    List<CollectionTaskPackage> taskPackages = buildTaskPackages(taskId, nodeName, planVersionId, competitorName, targets);
    Map<String, CollectionExecutionResult> checkpointResults = indexCheckpointResults(checkpoint);
    List<CollectionExecutionResult> results = new ArrayList<>();

    for (CollectionTaskPackage taskPackage : taskPackages) {
        CollectionExecutionResult checkpointResult = checkpointResults.get(taskPackage.getPackageKey());
        if (isReusableCheckpointResult(checkpointResult)) {
            results.add(markReused(checkpointResult, taskPackage).normalize());
            continue;
        }
        CollectionExecutor executor = executorRegistry.resolve(taskPackage);
        CollectionExecutionResult runtimeResult = enrich(executor.execute(taskPackage), taskPackage).normalize();
        results.add(runtimeResult);
    }

    CollectionAuditSnapshot auditSnapshot = buildAuditSnapshot(taskPackages, results, checkpoint);
    return CollectionExecutionReport.builder()
            .taskPackages(taskPackages)
            .results(results)
            .auditSnapshot(auditSnapshot)
            .build();
}
```

Implementation note:
- `WAITING_INTERVENTION`：优先用于 `requiresIntervention=true` 或 `failureKind=ANTI_BOT_BLOCKED` 的包。
- `PARTIAL_SUCCESS`：`successCount > 0 && successCount < totalPackages`。
- `DEGRADED`：所有包都完成，但存在 `degraded=true` 或质量降级信号。
- `recoveryCheckpoint` 指向“下一个尚未稳定完成的 `packageKey`”，而不是 collector 节点名。
- `CollectionReplayTimelineItem` 不追求与 `SearchReplayTimelineItem` 字段一一对齐；collection timeline 保留 `taskPackageKey / targetIndex / executorType / renderHint` 等包级字段，search timeline 继续保留 `stepCode / candidateCount` 等搜索步骤字段，两者仅在 `status / message / sourceUrls / updatedAt` 语义层面对齐。

- [ ] **Step 5: 运行 collection 契约与协调器测试**

Run:
`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectionExecutorRegistryTest" test`

Expected:
- PASS

---

### Task 3: 对齐 Collector 输出、事件与洞察契约

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`

- [ ] **Step 1: 在节点配置中正式预留 collection checkpoint**

```java
@JsonPropertyOrder({
        // ...
        "searchAuditCheckpoint",
        "collectionAuditCheckpoint"
})
public class CollectorNodeConfig {

    private SearchAuditSnapshot searchAuditCheckpoint;
    private cn.bugstack.competitoragent.collection.CollectionAuditSnapshot collectionAuditCheckpoint;
}
```

- [ ] **Step 2: 让 `CollectorAgent` 输出正式 `collectionAudit`**

```java
CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(
        context.getTaskId(),
        context.getCurrentNodeName(),
        context.getPlanVersionId(),
        config.getCompetitorName(),
        executableTargets,
        config.getCollectionAuditCheckpoint()
);
```

```java
output.put("collectionAudit", collectionReport.getAuditSnapshot());
output.put("collectionReplayTimeline", collectionReport.getAuditSnapshot() == null
        ? List.of()
        : collectionReport.getAuditSnapshot().getReplayTimeline());
output.put("collectionStatus", collectionReport.getAuditSnapshot() == null
        ? null
        : collectionReport.getAuditSnapshot().getStatus());
```

Implementation note:
- 旧字段 `results / documents / issueFlags / sourceUrls` 全部继续保留。
- `CollectorAgent` 兼容映射到 `CollectedPage` 时，不得丢失 `taskPackageKey / status / reusedFromCheckpoint / failureKind / qualitySignals / structuredBlocks`。

- [ ] **Step 3: 在洞察 DTO 和节点详情中直接透出 collection 子审计**

```java
public class CollectorNodeInsightResponse {

    private cn.bugstack.competitoragent.collection.CollectionAuditSnapshot collectionAudit;
    private cn.bugstack.competitoragent.model.dto.CollectionAuditSummary collectionAuditSummary;
    private List<cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem> collectionReplayTimeline;
    private String collectionStatus;
}
```

```java
.collectionAudit(collectionAudit)
.collectionAuditSummary(collectionAudit == null ? null : CollectionAuditSummary.from(collectionAudit))
.collectionReplayTimeline(collectionAudit == null || collectionAudit.getReplayTimeline() == null
        ? List.of()
        : collectionAudit.getReplayTimeline())
.collectionStatus(collectionAudit == null ? null : collectionAudit.getStatus())
```

- [ ] **Step 4: 在运行期事件里增量补齐 collection 字段**

Implementation note:
- 本轮仍沿用 `SearchProgressEventPayload` 与 `SEARCH_PROGRESS_V1` 事件通道，不新增 topic。
- DTO 增量补齐：

```java
private cn.bugstack.competitoragent.collection.CollectionAuditSnapshot collectionAudit;
private cn.bugstack.competitoragent.model.dto.CollectionAuditSummary collectionAuditSummary;
private List<cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem> collectionReplayTimeline;
private String collectionStatus;
```

```java
payload.setCollectionAudit(convertValue(output.get("collectionAudit"), CollectionAuditSnapshot.class));
payload.setCollectionAuditSummary(CollectionAuditSummary.from(payload.getCollectionAudit()));
payload.setCollectionReplayTimeline(convertList(
        firstPresent(output.get("collectionReplayTimeline"),
                output.path("collectionAudit").path("replayTimeline")),
        new TypeReference<List<CollectionReplayTimelineItem>>() {
        }));
payload.setCollectionStatus(textOrNull(output, "collectionStatus"));
```

- [ ] **Step 5: 运行 Collector / event / task view 对齐测试**

Run:
`mvn -pl backend "-Dtest=CollectorAgentTest,RuntimeEventEmitterTest,AnalysisTaskServiceTest" test`

Expected:
- PASS

---

### Task 4: 落库 collection checkpoint 并打通包级 rerun-resume

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionCheckpointRecorder.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/RecoveryCheckpointService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`

- [ ] **Step 1: 新建 checkpoint 记录协作者，隔离 Collector 的恢复点落库职责**

```java
package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.model.entity.RecoveryCheckpoint;
import cn.bugstack.competitoragent.task.RecoveryCheckpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectionCheckpointRecorder {

    private final RecoveryCheckpointService recoveryCheckpointService;
    private final ObjectMapper objectMapper;

    public void record(Long taskId,
                       Long planVersionId,
                       String nodeName,
                       CollectionAuditSnapshot auditSnapshot) {
        if (taskId == null || planVersionId == null || auditSnapshot == null) {
            return;
        }
        try {
            recoveryCheckpointService.saveCheckpoint(RecoveryCheckpoint.builder()
                    .taskId(taskId)
                    .planVersionId(planVersionId)
                    .checkpointKey("task-" + taskId + "-" + nodeName + "-collection")
                    .checkpointType("COLLECTION_AUDIT")
                    .nodeName(nodeName)
                    .summary(buildSummary(auditSnapshot))
                    .payloadSnapshot(objectMapper.writeValueAsString(auditSnapshot))
                    .sourceUrls(objectMapper.writeValueAsString(
                            auditSnapshot.getSourceUrls() == null ? java.util.List.of() : auditSnapshot.getSourceUrls()))
                    .build());
        } catch (Exception ignored) {
            // 第六轮要求 checkpoint 失败不能打断采集主链路。
        }
    }
}
```

- [ ] **Step 2: 让 `CollectorAgent` 在输出结果稳定后记录 collection checkpoint**

```java
if (collectionReport.getAuditSnapshot() != null) {
    collectionCheckpointRecorder.record(
            context.getTaskId(),
            context.getPlanVersionId(),
            context.getCurrentNodeName(),
            collectionReport.getAuditSnapshot()
    );
}
```

- [ ] **Step 3: 在运行时命令里回填 `collectionAuditCheckpoint`**

```java
private void reuseCollectionCheckpointIfPresent(TaskNode node) {
    if (node == null
            || node.getAgentType() != AgentType.COLLECTOR
            || !hasText(node.getOutputData())
            || !hasText(node.getNodeConfig())) {
        return;
    }
    try {
        JsonNode output = objectMapper.readTree(node.getOutputData());
        JsonNode auditNode = output.get("collectionAudit");
        if (auditNode == null || auditNode.isNull() || auditNode.isMissingNode()) {
            return;
        }
        CollectionAuditSnapshot checkpoint = objectMapper.treeToValue(auditNode, CollectionAuditSnapshot.class);
        JsonNode configNode = objectMapper.readTree(node.getNodeConfig());
        ((ObjectNode) configNode).set("collectionAuditCheckpoint", objectMapper.valueToTree(checkpoint));
        node.setNodeConfig(objectMapper.writeValueAsString(configNode));
    } catch (Exception e) {
        log.warn("reuse collection checkpoint failed, nodeName={}", node.getNodeName(), e);
    }
}
```

Implementation note:
- `rerunFromNode(...)` 与 `prepareTaskForResume(...)` 都必须调用 `reuseCollectionCheckpointIfPresent(node)`。
- 调用顺序放在 `reuseSearchCheckpointIfPresent(node)` 之后即可，两者并存，不互相覆盖。

- [ ] **Step 4: 用包级 checkpoint 结果驱动“只重跑失败包”**

```java
private boolean isReusableCheckpointResult(CollectionExecutionResult result) {
    return result != null
            && result.isSuccess()
            && "SUCCESS".equalsIgnoreCase(result.getStatus());
}

private CollectionExecutionResult markReused(CollectionExecutionResult result, CollectionTaskPackage taskPackage) {
    return result.toBuilder()
            .taskPackageKey(taskPackage.getPackageKey())
            .targetIndex(taskPackage.getTargetIndex())
            .success(true)
            .status("SUCCESS")
            .reusedFromCheckpoint(true)
            .checkpointSource("collectionAuditCheckpoint")
            .build()
            .normalize();
}
```

Implementation note:
- 这里默认采用 `@Builder(toBuilder = true)`；如果实现阶段改成显式 copy factory，则必须同步替换本段示例与相关测试，不能保留“计划写 `toBuilder()`、实现里没有 copy 能力”的不一致状态。

- [ ] **Step 5: 运行 checkpoint 与 rerun-resume 测试**

Run:
`mvn -pl backend "-Dtest=CollectionExecutionCoordinatorCheckpointReuseTest,TaskRuntimeCommandAppServiceTest,CollectorAgentTest" test`

Expected:
- PASS

---

### Task 5: 对齐 replay / task view / recovery 出口

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectionReplaySnapshotResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayContractPresenceTest.java`

- [ ] **Step 1: 新建 collection 回放 DTO**

```java
package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionReplaySnapshotResponse {

    private String nodeName;
    private Long planVersionId;
    private Integer planVersion;
    private String branchKey;
    private CollectionAuditSnapshot collectionAudit;
    private CollectionAuditSummary collectionAuditSummary;
    private List<CollectionReplayTimelineItem> timeline;
    private List<String> sourceUrls;
}
```

- [ ] **Step 2: 在任务回放主响应中挂出 `collectionReplays`**

```java
public class TaskReplayResponse {

    private List<SearchReplaySnapshotResponse> searchReplays;
    private List<CollectionReplaySnapshotResponse> collectionReplays;
    private List<RecoveryCheckpointResponse> recoveryCheckpoints;
    private List<String> sourceUrls;
}
```

- [ ] **Step 3: 让 `TaskReplayProjectionService` 从 collector output 构建 collectionReplays**

```java
private List<CollectionReplaySnapshotResponse> buildCollectionReplays(List<TaskNode> taskNodes,
                                                                      Map<Long, TaskPlan> taskPlanMap) {
    List<CollectionReplaySnapshotResponse> replays = new ArrayList<>();
    for (TaskNode taskNode : taskNodes) {
        if (taskNode == null || taskNode.getAgentType() != AgentType.COLLECTOR) {
            continue;
        }
        JsonNode output = readJson(taskNode.getOutputData());
        CollectionAuditSnapshot collectionAudit = convertValue(output == null ? null : output.get("collectionAudit"),
                CollectionAuditSnapshot.class);
        if (collectionAudit == null) {
            continue;
        }
        replays.add(CollectionReplaySnapshotResponse.builder()
                .nodeName(taskNode.getNodeName())
                .planVersionId(taskNode.getPlanVersionId())
                .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
                .branchKey(taskNode.getBranchKey())
                .collectionAudit(collectionAudit)
                .collectionAuditSummary(CollectionAuditSummary.from(collectionAudit))
                .timeline(collectionAudit.getReplayTimeline() == null ? List.of() : collectionAudit.getReplayTimeline())
                .sourceUrls(normalizeSourceUrls(collectionAudit.getSourceUrls()))
                .build());
    }
    return replays;
}
```

```java
List<CollectionReplaySnapshotResponse> collectionReplays = buildCollectionReplays(taskNodes, taskPlanMap);
List<String> aggregatedSourceUrls = aggregateReplaySourceUrls(
        timeline,
        nodeSummaries,
        recoveryAdvice,
        checkpoints,
        searchReplays,
        collectionReplays);

return TaskReplayResponse.builder()
        .searchReplays(searchReplays)
        .collectionReplays(collectionReplays)
        .sourceUrls(aggregatedSourceUrls)
        .build();
```

```java
private List<String> aggregateReplaySourceUrls(List<ReplayTimelineEvent> timeline,
                                               List<ReplayNodeSummary> nodeSummaries,
                                               TaskRecoveryAdvice recoveryAdvice,
                                               List<RecoveryCheckpointResponse> checkpoints,
                                               List<SearchReplaySnapshotResponse> searchReplays,
                                               List<CollectionReplaySnapshotResponse> collectionReplays) {
    LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
    // ... existing timeline / node / checkpoint / search replay aggregation
    for (CollectionReplaySnapshotResponse collectionReplay : collectionReplays) {
        sourceUrls.addAll(normalizeSourceUrls(collectionReplay.getSourceUrls()));
    }
    return new ArrayList<>(sourceUrls);
}
```

Implementation note:
- `aggregateReplaySourceUrls(...)` 必须显式扩展方法签名与调用点，把 `collectionReplays[*].sourceUrls` 一并并入任务级 `sourceUrls`，不能只停留在实现备注里。
- `TaskReplayResponse` 同时保留 `searchReplays` 与 `collectionReplays` 两条 typed 列表；不做跨类型 timeline 合并，只做顶层 `sourceUrls` 聚合与节点级并排展示。
- 本轮不要求 `TaskRecoveryAdvice` 改成 collection 专项对象，只要求 collector 节点的回放与恢复锚点可见。

- [ ] **Step 4: 运行 replay 合同测试**

Run:
`mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest" test`

Expected:
- PASS
- `TaskReplayResponse.collectionReplays` 存在
- `collectionReplays[*].collectionAuditSummary.status` 可见
- `collectionReplays[*]` 的序列化结果保留 `timeline / collectionAuditSummary / sourceUrls`
- 顶层 `sourceUrls` 同时聚合 `searchReplays[*].sourceUrls` 与 `collectionReplays[*].sourceUrls`
- collector 回放里 recovery checkpoint 不再只剩 search 段事实

---

### Task 6: 第六轮复核、文档回链与 dev live 验收

**Files:**

- Modify: `docs/superpowers/plans/2026-06-12-search-and-collection-execution-engine.md`
- Modify: `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`

- [x] **Step 1: 运行第六轮聚合测试**

Run:
`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectionExecutorRegistryTest,CollectorAgentTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest" test`

Expected:
- PASS

Actual:
- 2026-06-17 已重新执行第六轮聚合回归，并额外纳入 `SchemaExtractorAgentTest` 覆盖本轮 extractor 兼容性回归：`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectionExecutorRegistryTest,CollectorAgentTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest,SchemaExtractorAgentTest" test` 通过，合计 `85 tests` 全绿。

- [x] **Step 2: 运行 backend 全量测试**

Run:
`mvn -pl backend test`

Expected:
- PASS

Actual:
- 2026-06-17 已执行 `mvn -pl backend test`，全量测试通过。

- [x] **Step 3: 执行第六轮 dev live 验收**

Manual API smoke:

1. `POST /api/task/{id}/execute`
   - 确认 collector output 含 `collectionAudit.summary.status`、`collectionAudit.replayTimeline`、`collectionAudit.recoveryCheckpoint`、`sourceUrls`。
2. `GET /api/task/{id}/nodes`
   - 确认 `collectorInsight.collectionAuditSummary.status`、`collectorInsight.collectionReplayTimeline` 可见。
3. `GET /api/task/{id}/replay`
   - 确认返回 `collectionReplays[*]`，且顶层 `sourceUrls` 聚合了 collection 回放来源。
4. `POST /api/task/{id}/nodes/{nodeName}/rerun`
   - 确认 rerun 前的 collector 节点配置回填了 `collectionAuditCheckpoint`，重新执行时已成功包不会被重复抓取。
5. `POST /api/task/{id}/resume`
   - 确认 collector 若处于 `PARTIAL_SUCCESS` 或 `WAITING_INTERVENTION`，resume 后能从 `collectionAudit.recoveryCheckpoint` 指向的包继续。

Expected:
- 第六轮验收重点不是“最终报告一定通过”，而是“采集段现在能说明清楚哪一个包成功、哪一个包失败、为什么失败、从哪里继续恢复”。
- 如果最终任务仍停在 `extract_schema` 或质量门禁，只记录为下游链路问题，不回退本轮 collection 审计与恢复验收。

Actual:
- 2026-06-17 真实任务 `43` 已完成第六轮 smoke：`GET /api/task/43/replay` 可见 `searchReplays=2`、`collectionReplays=2`、顶层 `sourceUrls=11`，其中 `collect_sources_01_02` 的 `collectionAuditSummary.status=SUCCESS / reusedCount=1`。
- `POST /api/task/43/nodes/collect_sources_01_02/rerun` 与后续 `POST /api/task/43/resume` 已验证 `collectionAuditCheckpoint` 回填和包级复用闭环；`collect_sources_01_02#002` 明确标记 `reusedFromCheckpoint=true` 与 `checkpointSource=collectionAuditCheckpoint`。
- 同一任务后续已真实推进 `extract_schema -> analyze_competitors -> write_report -> quality_check -> rewrite_report -> quality_check_final`；最终失败来自 reviewer 的业务质量判定，不再属于采集审计 / 恢复链路阻塞。

- [x] **Step 4: 回写父计划与 specs 状态**

Update parent plan wording like:

```md
第六轮实施承接 `Wave 9`，把 collection 子域从“最小执行接缝 + 网页采集加固”推进到
`collectionAudit / collectionReplayTimeline / collectionAuditCheckpoint / 包级 rerun-resume`
的正式闭环；本轮仍不扩展 `Wave 10` 的 news/feed 结构化采集 owner。
```

Update specs wording like:

```md
- 实施：搜索段正式审计 / 回放 / 恢复已具备基础，第六轮正在把同等级别的 collection 审计与恢复语义补齐；
  自动化通过后仍需结合真实任务验证“已成功包不重复抓取、失败包可从 package checkpoint 继续”。
```

- [x] **Step 5: 完成后给出下一轮建议**

Completion note should say:

```md
本轮完成后，最推荐下一步进入 `Wave 10` 或 `Wave 12` 之间的择一推进：
1. 若当前真实瓶颈仍在采集来源不足：优先进入 `Wave 10`，把 `news / feed` 结构化采集 owner 接进统一执行体系。
2. 若当前真实瓶颈已转到下游消费：优先进入 `Wave 12`，把 collectionAudit / qualitySignals / structuredBlocks 正式传给 extract / analyze / report / review。
```

Current recommendation:
- 基于任务 `43` 的最终尾证，当前最直接的主瓶颈已经转到下游质量门禁，因此下一手优先级建议切到 `Wave 12`；`Wave 10` 的 `news / feed` 结构化采集 owner 仍然是后续必须完成的工程缺口，但它不再是当前唯一主停点。

---

## Verification

- collection 正式契约：`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectionExecutorRegistryTest" test`
- Collector 输出与事件对齐：`mvn -pl backend "-Dtest=CollectorAgentTest,RuntimeEventEmitterTest,AnalysisTaskServiceTest" test`
- checkpoint 与 rerun-resume：`mvn -pl backend "-Dtest=CollectionExecutionCoordinatorCheckpointReuseTest,TaskRuntimeCommandAppServiceTest,CollectorAgentTest" test`
- replay 主路径：`mvn -pl backend "-Dtest=TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest" test`
- DTO 构造与序列化契约：`mvn -pl backend "-Dtest=CollectionAuditSerializationTest,TaskReplayContractPresenceTest" test`
- 第六轮整体：`mvn -pl backend "-Dtest=CollectionTaskPackageBuilderTest,CollectionAuditContractTest,CollectionExecutionCoordinatorCheckpointReuseTest,CollectionAuditSerializationTest,CollectionExecutorRegistryTest,CollectorAgentTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskReplayContractPresenceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest" test`
- backend 全量：`mvn -pl backend test`

---

## Self-Review

### Spec coverage

1. `collectionAudit`、`collectionReplayTimeline`、正式状态摘要由 Task 1、Task 2、Task 3 覆盖。
2. `collectionAuditCheckpoint` 与包级 rerun-resume 由 Task 4 覆盖。
3. `runtime / insight / replay / event` 对齐由 Task 3、Task 5 覆盖。
4. `sourceUrls` 红线在新建 summary / snapshot / replay DTO / checkpoint 中均显式保留。
5. 文档回链与 dev live 验收由 Task 6 覆盖。

### Placeholder scan

1. 本计划未使用 `TODO / TBD / implement later` 之类空洞占位。
2. 每个任务都给出了明确文件、命令和预期结果。
3. 本轮未把 report/export 聚合、front-end 全量切换或 `Wave 10` 新采集 owner 混入 `Wave 9` 范围。

### Type consistency

1. `CollectionExecutionResult` 始终表达“单个采集包”的执行结果。
2. `CollectionExecutionReport` 始终表达“一次 collector 节点采集执行”的聚合结果。
3. `CollectionAuditSnapshot` 始终作为正式采集审计快照。
4. `CollectionReplayTimelineItem` 始终作为包级采集回放时间线条目。
5. `CollectionAuditSummary` 始终作为 insight / replay / event 主路径优先消费的轻量摘要。
6. `CollectionExecutionResult.normalize()` 始终负责收口 `success / status` 一致性；后续若扩展新状态，必须先更新该归一化规则与对应测试。
