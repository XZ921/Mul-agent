# Task88 Tavily 跨节点去重与官方扩展收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改 collector 拓扑、不牺牲第三方搜索质量的前提下，把 task88 暴露的 Tavily 调用放大从“节点数 x 重复字段 query x 默认 expansion”收敛到“竞品级去重 + 官方锚点优先 + 仅必要时扩展”。

**Architecture:** 第一刀在任务运行期为每个竞品维护跨 collector 的字段 query fingerprint claim set，value 使用 `ConcurrentHashMap.newKeySet()`，并用 `Set.add(fingerprint)` 的原子返回值决定该 query 是否允许执行；重复 query 在进入 provider 前跳过并审计为 `SKIPPED_CROSS_NODE_DEDUP`。第二刀把官方类字段 query 的主搜索 mode 从默认 `TRUSTED_WEB_EXPANSION` 拉回 `OFFICIAL_DOCS`，再收紧 `shouldExpand`，只有官方锚点完全无结果或零可用候选时才追加 trusted expansion。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, Mockito, Tavily Search integration.

---

## 1. 结构化执行计划

| Task | 核心目标 | 预期耗时 | 依赖前置条件 |
| --- | --- | ---: | --- |
| Task 1 | 为字段 query gate 增加跨节点已执行 fingerprint 输入与 `SKIPPED_CROSS_NODE_DEDUP` 审计 | 45 分钟 | 当前 `FieldEvidenceQueryExecutionGateTest` 全绿 |
| Task 2 | 在 collector 执行链路中接入竞品级原子 claim set | 75 分钟 | Task 1 通过；`sharedState` 已核实为 `Map<String, String>`，必须新增独立 `Map<String, Set<String>> fieldEvidenceFingerprintClaims` |
| Task 3 | 官方类字段 query 默认回到 `OFFICIAL_DOCS` | 30 分钟 | Task 1-2 不要求完成，但建议先做完避免 audit 口径混乱 |
| Task 4 | 收紧 `shouldExpand`，只有无结果或零可用候选才 expansion | 45 分钟 | Task 3 完成，否则官方字段仍不会进入 `shouldExpand` |
| Task 5 | 回归测试与 task88 指标复验 | 60 分钟 | Task 1-4 全部完成 |

## 2. 进度记录

- [x] Task 1：字段 query gate 支持跨节点去重输入
- [x] Task 2：collector 执行链路接入竞品级原子 claim set
- [x] Task 3：官方类字段 query 默认 `OFFICIAL_DOCS`
- [x] Task 4：保守化 `shouldExpand`
- [ ] Task 5：单测、集成测试、task88 指标复验

执行过程中每完成一个 Task，需要在本节勾选，并在对应 Task 下追加实测命令与结果摘要。

## 3. 根因与边界

task88 的 Tavily 成本放大来自三层叠乘：

1. `source_scope` 为空时默认展开 `OFFICIAL / DOCS / PRICING / NEWS / REVIEW`，每个竞品 5 个 collector。
2. 每个 collector 都携带完整 `dimensionEvidencePlan`，导致高度相同的字段 query 在多个节点重复执行。
3. 官方类字段 query 当前默认被解析成 `TRUSTED_WEB_EXPANSION`，不是严格官方锚点；收紧 `shouldExpand` 前，必须先让官方字段回到 `OFFICIAL_DOCS`。

本计划不处理下面范围：

- 不删除 5 个 source scope 节点。
- 不重构官方/第三方双池。
- 不新增“技术社区”独立 source scope。
- 不修改 Tavily API key、全局 maxResults 或 provider 并发模型。

## 4. 目标指标

| 指标 | 当前现象 | 本轮目标 |
| --- | --- | --- |
| 单竞品多 collector 重复字段 query | 5 个 collector 各跑一批重叠 query | 同竞品同 fingerprint 只允许首个节点 claim/执行 |
| `TRUSTED_WEB_EXPANSION` audit 计数 | 官方字段主搜也被算作 expansion | 官方字段主搜计入 `OFFICIAL_DOCS` |
| 真正第二枪 expansion | 已有可用候选但无 `OFFICIAL_DOC/PDF` 也扩展 | 仅无结果或零可用候选时扩展 |
| 证据质量 | 第三方长文质量较好，但调用重复 | 保留第三方路径，不因去重误杀不同 sourceType fingerprint |
| 降本预期 | task88 多节点叠乘 | 优先期望 Tavily requestId 下降 40%-70% |

## 5. Files

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionPlan.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFieldEvidenceProfileResolverTest.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAcceptanceTest.java`

## 6. Task 1: FieldEvidenceQueryExecutionGate 支持跨节点去重

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGate.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionPlan.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/FieldEvidenceQueryExecutionGateTest.java`

- [ ] **Step 1: 写失败测试，锁定跨节点重复 fingerprint 会被跳过**

在 `FieldEvidenceQueryExecutionGateTest` 增加：

```java
@Test
void shouldSkipQueryWhenFingerprintAlreadyClaimedBySiblingCollector() {
    FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
    List<FieldEvidenceQuery> planned = List.of(
            query("summary", "OFFICIAL", 0, "summary-official-1"),
            query("summary", "DOCS", 1, "summary-docs-1"),
            query("pricing", "OFFICIAL", 2, "pricing-official-1")
    );

    java.util.Set<String> claimSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
    claimSet.add("summary-official-1");

    FieldEvidenceQueryExecutionPlan plan = gate.resolve(
            planned,
            3,
            0,
            24,
            claimSet
    );

    assertThat(plan.executable())
            .extracting(FieldEvidenceQuery::getQueryFingerprint)
            .containsExactly("summary-docs-1", "pricing-official-1");
    assertThat(plan.skipped())
            .extracting(FieldEvidenceQuery::getQueryFingerprint)
            .contains("summary-official-1");
    assertThat(plan.skipReasons())
            .containsEntry("SKIPPED_CROSS_NODE_DEDUP", 1);
    assertThat(plan.claimedFingerprints())
            .containsExactly("summary-docs-1", "pricing-official-1");
    assertThat(claimSet)
            .contains("summary-official-1", "summary-docs-1", "pricing-official-1");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest#shouldSkipQueryWhenFingerprintAlreadyClaimedBySiblingCollector" test
```

Expected: FAIL，原因是 `resolve(..., Set<String>)` 和 `claimedFingerprints()` 尚不存在，或者 `resolve(..., Set<String>)` 还没有调用 `claimSet.add(...)`。

- [ ] **Step 3: 扩展执行计划 DTO**

在 `FieldEvidenceQueryExecutionPlan` 增加 `claimedFingerprints` 字段，保持旧 getter 兼容：

```java
public record FieldEvidenceQueryExecutionPlan(
        List<FieldEvidenceQuery> planned,
        List<FieldEvidenceQuery> executable,
        List<FieldEvidenceQuery> skipped,
        Map<String, Integer> skipReasons,
        Map<String, Integer> fieldDistribution,
        Map<String, Integer> sourceTypeDistribution,
        List<String> claimedFingerprints
) {

    public FieldEvidenceQueryExecutionPlan {
        planned = planned == null ? List.of() : List.copyOf(planned);
        executable = executable == null ? List.of() : List.copyOf(executable);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        skipReasons = skipReasons == null ? Map.of() : Map.copyOf(skipReasons);
        fieldDistribution = fieldDistribution == null ? Map.of() : Map.copyOf(fieldDistribution);
        sourceTypeDistribution = sourceTypeDistribution == null ? Map.of() : Map.copyOf(sourceTypeDistribution);
        claimedFingerprints = claimedFingerprints == null ? List.of() : List.copyOf(claimedFingerprints);
    }

    public static FieldEvidenceQueryExecutionPlan empty() {
        return new FieldEvidenceQueryExecutionPlan(List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public List<String> getClaimedFingerprints() {
        return claimedFingerprints;
    }
}
```

- [ ] **Step 4: 给 Gate 增加重载方法并保留旧入口**

在 `FieldEvidenceQueryExecutionGate` 保留旧签名，内部委托给新签名：

```java
public FieldEvidenceQueryExecutionPlan resolve(List<FieldEvidenceQuery> planned,
                                               int maxPerField,
                                               int minThirdPartyPerField,
                                               int maxPerNode) {
    return resolve(planned, maxPerField, minThirdPartyPerField, maxPerNode, Set.of());
}

public FieldEvidenceQueryExecutionPlan resolve(List<FieldEvidenceQuery> planned,
                                               int maxPerField,
                                               int minThirdPartyPerField,
                                               int maxPerNode,
                                               Set<String> claimSet) {
    List<FieldEvidenceQuery> ordered = sortQueries(planned);
    if (ordered.isEmpty()) {
        return FieldEvidenceQueryExecutionPlan.empty();
    }
    Set<String> effectiveClaimSet = claimSet == null ? ConcurrentHashMap.newKeySet() : claimSet;

    // 后续先沿用原有 byField、per-field quota、round-robin、node cap 逻辑得到 retained。
    // 生成最终 executable 时，对 retained 逐条执行 effectiveClaimSet.add(fingerprint)。
    // add 返回 true 才进入 executable；false 则进入 skipped，并累计 SKIPPED_CROSS_NODE_DEDUP。
}
```

完整实现时要注意：

- `planned` 仍返回原始排序后的全量 planned，包括跨节点跳过项。
- `skipped` 要包含 `claimSet.add(fingerprint)` 返回 false 的 query。
- `skipReasons` 要累计 `SKIPPED_CROSS_NODE_DEDUP`。
- `claimedFingerprints` 只包含本节点最终 claim 成功且进入 executable 的 fingerprint。
- 不要把 `claimSet` 写入 `FieldEvidenceQueryExecutionGate` 成员变量；该类是 Spring singleton。

- [ ] **Step 5: 跑 Gate 全量测试**

Run:

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest" test
```

Expected: PASS。

## 7. Task 2: Collector 链路接入竞品级原子 claim set

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/AgentContext.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorFieldEvidenceBudgetTest.java`

- [ ] **Step 1: 核实 sharedState 类型契约与 forkNodeContext 引用模式**

开始实现前先读 `AgentContext`，确认 `sharedState` 的声明是：

```java
@Builder.Default
private Map<String, String> sharedState = new ConcurrentHashMap<>();
```

这意味着 `sharedState` 是节点输出字符串通道，不能放 `Set<String>`，也不能把 fingerprint set 序列化成逗号字符串后写回去；那会退回非原子的 check-then-act 老问题。

然后读 `DagExecutor.forkNodeContext(...)`，确认当前共享引用模式：

```java
private AgentContext forkNodeContext(AgentContext sharedContext, TaskNode node) {
    return AgentContext.builder()
            .taskId(sharedContext.getTaskId())
            .taskName(sharedContext.getTaskName())
            .subjectProduct(sharedContext.getSubjectProduct())
            .competitorNames(sharedContext.getCompetitorNames())
            .competitorUrls(sharedContext.getCompetitorUrls())
            .analysisDimensions(sharedContext.getAnalysisDimensions())
            .sourceScope(sharedContext.getSourceScope())
            .reportLanguage(sharedContext.getReportLanguage())
            .reportTemplate(sharedContext.getReportTemplate())
            .currentNodeName(node.getNodeName())
            .currentNodeConfig(node.getNodeConfig())
            .traceId(sharedContext.getTraceId())
            .sharedState(sharedContext.getSharedState())
            .sharedOutputEnvelopes(sharedContext.getSharedOutputEnvelopes())
            .createdAt(sharedContext.getCreatedAt())
            .build();
}
```

验收：

- 必须看到 `AgentContext.sharedState` 是 `Map<String, String>`。
- 必须看到 `.sharedState(sharedContext.getSharedState())`，即 sharedState 当前是引用传递。
- 新增的 `fieldEvidenceFingerprintClaims` 必须按同样方式传引用：`.fieldEvidenceFingerprintClaims(sharedContext.getFieldEvidenceFingerprintClaims())`。
- 如果未来代码变成 `new ConcurrentHashMap<>(...)` 或其他深拷贝，本 Task 的载体不成立，必须改为 workflow 级 registry 或 task 级 Spring bean registry。

- [ ] **Step 2: 为 AgentContext 增加专用运行期 claim registry**

不要把跨节点 claim registry 作为 `CollectorAgent` 成员字段；它会跨 task 长期残留，虽然 key 里有 taskId 也容易污染 rerun 和占用内存。把 registry 放进 `AgentContext`，生命周期跟随本次 DAG 执行。

在 `AgentContext` 增加：

```java
/**
 * 字段证据 query 的任务运行期原子 claim registry。
 * key 使用 fieldEvidence.executedFingerprints::<taskId>::<competitorName>，
 * value 使用 ConcurrentHashMap.newKeySet()，通过 Set.add 的原子返回值避免并发 collector 重复执行同一 query。
 */
@Builder.Default
private Map<String, Set<String>> fieldEvidenceFingerprintClaims = new ConcurrentHashMap<>();
```

需要新增 import：

```java
import java.util.Set;
```

在 `DagExecutor.forkNodeContext(...)` 中追加同引用传递：

```java
.fieldEvidenceFingerprintClaims(sharedContext.getFieldEvidenceFingerprintClaims())
```

验收：

- 同一 DAG 的多个 collector 拿到同一个 `fieldEvidenceFingerprintClaims` 引用。
- 不写入 `sharedState` 字符串输出通道，避免和 node output payload 混在一起。

- [ ] **Step 3: 为 coordinator 增加携带运行期 fingerprint registry 的执行入口**

不要把跨节点 claim 状态存成 `Map<String, String>` 的逗号拼接串，也不要把 `Set<String>` 塞进 `sharedState`。`sharedState` 的 value 类型就是 `String`，复用它只能走序列化字符串，逗号串会导致两个并发问题：

- check-then-act：两个 collector 同时读到空集合后都会执行同一批 query。
- lost update：两个线程同时读 raw string、split、join、写回时会互相覆盖。

在 `SearchExecutionCoordinator` 增加运行期 registry 重载，旧入口不变。`taskId` 必须参与 key，避免同一 JVM 同时跑多个 task 时同名竞品互相去重：

```java
public SearchExecutionResult execute(CollectorNodeConfig config,
                                     Long taskId,
                                     Map<String, Set<String>> fieldEvidenceFingerprintClaims,
                                     Consumer<SearchExecutionUpdate> progressListener) {
    return executeInternal(config, taskId, fieldEvidenceFingerprintClaims, progressListener);
}
```

把原 `execute(config, progressListener)` 主体迁移到私有 `executeInternal(...)`。旧入口委托：

```java
public SearchExecutionResult execute(CollectorNodeConfig config,
                                     Consumer<SearchExecutionUpdate> progressListener) {
    return executeInternal(config, null, null, progressListener);
}
```

如果不想大范围移动方法体，也可以让 `execute(config, progressListener)` 保持原有逻辑，并只在 `resolveExecutableFieldEvidenceQueries(...)` 增加一个 nullable `fieldEvidenceFingerprintClaims` 参数；但最终必须保证旧测试和旧调用继续编译。

- [ ] **Step 4: 写并发失败测试，锁定两个 collector 同时起跑时 union 无重复**

在 `SearchExecutionCoordinatorFieldEvidenceBudgetTest` 增加：

```java
@Test
void shouldAtomicallyDeduplicateFieldEvidenceQueriesAcrossConcurrentCollectors() throws Exception {
    BlockingRecordingBudgetAwareSearchSourceProvider provider = new BlockingRecordingBudgetAwareSearchSourceProvider(2);
    SearchExecutionCoordinator coordinator = newCoordinator(provider);
    Long taskId = 88L;
    java.util.Map<String, java.util.Set<String>> claims = new java.util.concurrent.ConcurrentHashMap<>();

    CollectorNodeConfig firstNode = CollectorNodeConfig.builder()
            .competitorName("哔哩哔哩")
            .sourceType("OFFICIAL")
            .verifyCandidates(false)
            .searchMode("HTTP_ONLY")
            .searchFallbackOrder(List.of("HTTP"))
            .preferredSearchProvider("tavily")
            .browserSearchEnabled(false)
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .searchTimeoutMillis(18_300L)
            .dimensionEvidencePlan(prioritizedFieldPlan())
            .build();

    CollectorNodeConfig secondNode = CollectorNodeConfig.builder()
            .competitorName("哔哩哔哩")
            .sourceType("DOCS")
            .verifyCandidates(false)
            .searchMode("HTTP_ONLY")
            .searchFallbackOrder(List.of("HTTP"))
            .preferredSearchProvider("tavily")
            .browserSearchEnabled(false)
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .searchTimeoutMillis(18_300L)
            .dimensionEvidencePlan(prioritizedFieldPlan())
            .build();

    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.concurrent.Future<SearchExecutionResult> first = executor.submit(() ->
            coordinator.execute(firstNode, taskId, claims, null));
    java.util.concurrent.Future<SearchExecutionResult> second = executor.submit(() ->
            coordinator.execute(secondNode, taskId, claims, null));

    first.get(10, java.util.concurrent.TimeUnit.SECONDS);
    second.get(10, java.util.concurrent.TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(provider.requests).hasSize(2);
    List<String> executedFingerprints = provider.requests.stream()
            .flatMap(request -> request.getFieldEvidenceQueries().stream())
            .map(FieldEvidenceQuery::getQueryFingerprint)
            .toList();
    assertThat(executedFingerprints).doesNotHaveDuplicates();
    assertThat(executedFingerprints)
            .containsExactlyInAnyOrder("q-priority-10", "q-priority-20", "q-priority-30");
    assertThat(provider.requests.stream()
            .mapToInt(SearchSourceRequest::getFieldEvidenceQuerySkippedCount)
            .sum()).isEqualTo(5);
    assertThat(claims.get("fieldEvidence.executedFingerprints::88::哔哩哔哩"))
            .containsExactlyInAnyOrder("q-priority-10", "q-priority-20", "q-priority-30");
}
```

不要为了测试给 `CollectorNodeConfig` 增加 `toBuilder = true`；这里显式创建第二个 builder，避免扩大实体变更面。

同文件增加阻塞 provider，确保两个 coordinator 线程都能同时进入 provider 前后的执行窗口。断言不要写“第二个节点为空”，并发下谁先 claim 不确定，只能断言两个节点合起来无重复：

```java
private static final class BlockingRecordingBudgetAwareSearchSourceProvider extends RecordingBudgetAwareSearchSourceProvider {

    private final java.util.concurrent.CountDownLatch ready;
    private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

    private BlockingRecordingBudgetAwareSearchSourceProvider(int parties) {
        this.ready = new java.util.concurrent.CountDownLatch(parties);
    }

    @Override
    public List<SourceCandidate> search(SearchSourceRequest request) {
        ready.countDown();
        try {
            if (ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                release.countDown();
            }
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return super.search(request);
    }
}
```

- [ ] **Step 5: 实现竞品级原子 claim set**

在 `SearchExecutionCoordinator` 增加私有方法：

```java
private String fieldEvidenceFingerprintStateKey(Long taskId, CollectorNodeConfig config) {
    String taskKey = taskId == null ? "unknown-task" : String.valueOf(taskId);
    String competitor = config == null || !StringUtils.hasText(config.getCompetitorName())
            ? "unknown"
            : config.getCompetitorName().trim();
    return "fieldEvidence.executedFingerprints::" + taskKey + "::" + competitor;
}

private Set<String> resolveClaimSet(Map<String, Set<String>> claims,
                                    Long taskId,
                                    CollectorNodeConfig config) {
    if (claims == null || config == null) {
        return Set.of();
    }
    return claims.computeIfAbsent(
            fieldEvidenceFingerprintStateKey(taskId, config),
            ignored -> ConcurrentHashMap.newKeySet()
    );
}
```

说明：

- `claims.computeIfAbsent(...)` 原子创建竞品级 set。
- `ConcurrentHashMap.newKeySet()` 的 `add(fingerprint)` 是原子 claim：返回 `true` 才代表本节点抢到执行权。
- 不要再做“先 read set，再 gate 过滤，再 append”的 check-then-act 流程。
- key 必须包含 taskId 和 competitorName，避免同一任务多个竞品、以及同一 JVM 多个任务之间互相去重。

- [ ] **Step 6: 在 resolveExecutableFieldEvidenceQueries 中做原子 claim**

把当前调用：

```java
FieldEvidenceQueryExecutionPlan executionPlan = fieldEvidenceQueryExecutionGate.resolve(
        planned,
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMinThirdPartyQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerNode()
);
```

改成：

```java
Set<String> claimSet = resolveClaimSet(fieldEvidenceFingerprintClaims, taskId, config);
FieldEvidenceQueryExecutionPlan executionPlan = fieldEvidenceQueryExecutionGate.resolve(
        planned,
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMinThirdPartyQueriesPerField(),
        searchPolicyResolver.resolveFieldEvidenceMaxQueriesPerNode(),
        claimSet
);
```

`taskId` 来自 `CollectorAgent` 传入的 `context.getTaskId()`。`FieldEvidenceQueryExecutionGate.resolve(..., Set<String> claimSet)` 内部要对每个原本会进入 executable 的 query 调用 `claimSet.add(fingerprint)`：

- `true`：加入 executable，并记录到 `claimedFingerprints`。
- `false`：加入 skipped，并累计 `SKIPPED_CROSS_NODE_DEDUP`。

也就是说，跨节点去重必须在最终 executable 形成时完成 claim，而不是先拿快照再事后 append。`resolveExecutableFieldEvidenceQueries(...)` 方法签名需要增加 `Map<String, Set<String>> fieldEvidenceFingerprintClaims` 参数。

- [ ] **Step 7: CollectorAgent 传入运行期 claim registry**

说明：

- registry 来自 `context.getFieldEvidenceFingerprintClaims()`，生命周期跟随本次 DAG 执行。
- key 必须使用 `fieldEvidence.executedFingerprints::<taskId>::<competitorName>`。
- 如果后续需要跨进程恢复该 registry，再另做持久化设计；本轮只解决同一 DAG 同一 JVM 并发 collector 的重复请求。

把 `CollectorAgent.doExecute(...)` 中的首次调用：

```java
SearchExecutionResult searchExecutionResult = searchExecutionCoordinator.execute(config, update ->
        persistRunningOutput(context, config, sourceType, update, results, successCounterRef[0]));
```

改成：

```java
SearchExecutionResult searchExecutionResult = searchExecutionCoordinator.execute(config, context.getTaskId(), context.getFieldEvidenceFingerprintClaims(), update ->
        persistRunningOutput(context, config, sourceType, update, results, successCounterRef[0]));
```

同文件第二轮 recollection 调用也要同步传入同一个 `context.getFieldEvidenceFingerprintClaims()`，避免第二轮重复跑第一轮已 claim 的 query。

- [ ] **Step 8: 跑并发跨节点去重测试**

Run:

```powershell
mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest#shouldAtomicallyDeduplicateFieldEvidenceQueriesAcrossConcurrentCollectors" test
```

Expected: PASS。

- [ ] **Step 9: 跑字段预算回归**

Run:

```powershell
mvn -pl backend "-Dtest=SearchExecutionCoordinatorFieldEvidenceBudgetTest,FieldEvidenceQueryExecutionGateTest" test
```

Expected: PASS。

## 8. Task 3: 官方类字段 query 默认 OFFICIAL_DOCS

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/tavily/TavilySearchProfileResolver.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFieldEvidenceProfileResolverTest.java`

- [ ] **Step 0: 记录召回风险和验收口径**

`OFFICIAL_DOCS` 会保留 `includeDomains`，这会让官方字段 query 从开放网收窄到官方域名。它的收益是降噪，但对 B 站这类 SPA 官方站可能让候选正文更薄。因此本 Task 的验收不能只看 mode 变化，还要在 Task 5 复验中记录：

- 官方候选数不低于本轮修改前同输入基线。
- 如果官方候选数下降，但 `SKIPPED_CROSS_NODE_DEDUP` 降本明显、证据质量仍达标，可以先只回滚 Task 4。
- 如果官方候选数明显下降且证据质量下降，再回滚 Task 3。

- [ ] **Step 1: 改测试预期，官方字段 query 使用 OFFICIAL_DOCS**

把 `TavilyFieldEvidenceProfileResolverTest.shouldUseBasicProfileWithoutRawForFieldEvidenceDiscovery` 中：

```java
assertThat(profile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
```

改为：

```java
assertThat(profile.getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
assertThat(profile.getIncludeDomains()).containsExactly("open.douyin.com");
```

再增加一个第三方字段仍走 `OPEN_WEB` 的测试：

```java
@Test
void shouldKeepThirdPartyFieldEvidenceOnOpenWeb() {
    TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());

    TavilySearchProfile profile = resolver.resolveFieldEvidence(FieldEvidenceQuery.builder()
            .fieldName("weaknesses")
            .evidencePathKey("PUBLIC_REVIEW_OR_NEWS")
            .queryIntent("THIRD_PARTY_REVIEW")
            .sourceType("REVIEW")
            .query("bilibili open platform review limitations")
            .queryFingerprint("q-review")
            .build());

    assertThat(profile.getQueryMode()).isEqualTo(TavilyQueryMode.OPEN_WEB);
    assertThat(profile.getIncludeDomains()).isEmpty();
    assertThat(profile.getOfficialDomains()).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
mvn -pl backend "-Dtest=TavilyFieldEvidenceProfileResolverTest" test
```

Expected: FAIL，官方字段当前仍是 `TRUSTED_WEB_EXPANSION`。

- [ ] **Step 3: 修改 resolveFieldEvidenceMode**

把 `TavilySearchProfileResolver.resolveFieldEvidenceMode(...)` 改成：

```java
private TavilyQueryMode resolveFieldEvidenceMode(FieldEvidenceQuery query) {
    String sourceType = query == null ? null : query.getSourceType();
    if ("OFFICIAL".equalsIgnoreCase(sourceType)
            || "DOCS".equalsIgnoreCase(sourceType)
            || "PRICING".equalsIgnoreCase(sourceType)) {
        // 字段级官方证据先走官方锚点主搜；是否扩展到开放网络交给 provider 的 shouldExpand 决策。
        return TavilyQueryMode.OFFICIAL_DOCS;
    }
    return TavilyQueryMode.OPEN_WEB;
}
```

保留 `resolveFieldEvidenceIncludeDomains(...)` 现有逻辑：`OFFICIAL_DOCS` 不清空 includeDomains。

- [ ] **Step 4: 跑 resolver 测试**

Run:

```powershell
mvn -pl backend "-Dtest=TavilyFieldEvidenceProfileResolverTest,TavilySearchProfileResolverTest" test
```

Expected: PASS。若 `TavilySearchProfileResolverTest` 里存在“官方 field query 应为 TRUSTED_WEB_EXPANSION”的旧断言，需要同步改为 `OFFICIAL_DOCS`，并在断言名中写明这是降噪策略。

实测记录：

```powershell
mvn -pl backend "-Dtest=TavilyFieldEvidenceProfileResolverTest,TavilySearchProfileResolverTest" test
```

结果摘要：
- PASS。
- `TavilyFieldEvidenceProfileResolverTest` 补充了第三方字段仍走 `OPEN_WEB` 的断言。
- `TavilySearchProfileResolverTest` 同步把官方 field query 的预期改为 `OFFICIAL_DOCS`，保留普通主搜 search-first 语义不变。

## 9. Task 4: shouldExpand 仅在无结果或零可用候选时触发

**Files:**
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/source/TavilyFastLaneProvider.java`
- Test: `backend/src/test/java/cn/bugstack/competitoragent/search/tavily/TavilyFastLaneAcceptanceTest.java`

- [ ] **Step 1: 写/改测试，已有可用官方候选时不追加 expansion**

在 `TavilyFastLaneAcceptanceTest` 增加一个测试，使用第一轮 fixture 返回可用候选，断言只执行一次 profile：

```java
@Test
void shouldNotFallbackToTrustedExpansionWhenOfficialDocsReturnsUsableCandidate() throws Exception {
    SearchSourceRequest officialDocsRequest = SearchSourceRequest.builder()
            .competitorName("douyin")
            .requestedScopes(List.of("DOCS"))
            .searchQueries(List.of("douyin open platform api docs"))
            .preferredDomains(List.of(OFFICIAL_DOMAIN))
            .includeDomains(List.of(OFFICIAL_DOMAIN))
            .preferredProviderKey("tavily")
            .preferredQueryMode("OFFICIAL_DOCS")
            .build();

    AcceptanceScenario scenario = runScenario(officialDocsRequest,
            List.of(
                    loadFixture("tavily/official-docs-response.json"),
                    loadFixture("tavily/recommendation-algorithm-response.json")
            ));

    assertThat(scenario.executedProfiles).hasSize(1);
    assertThat(scenario.executedProfiles.get(0).getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
    assertThat(scenario.metrics.officialDocHitCount).isGreaterThan(0);
}
```

- [ ] **Step 2: 调整旧测试的语义**

当前 `shouldFallbackToTrustedExpansionWhenOfficialAnchorOnlyReturnsNoisePages` 仍应保留，但它要明确验证“第一轮零可用候选时才扩展”。如果 fixture `noise-search-page-response.json` 里所有候选 `fastLaneUsable=false`，预期保持 `executedProfiles.hasSize(2)`。

- [ ] **Step 3: 修改 shouldExpand**

把 `TavilyFastLaneProvider.shouldExpand(...)` 改为：

```java
private boolean shouldExpand(TavilySearchProfile primaryProfile, List<SourceCandidate> primaryCandidates) {
    if (primaryProfile == null || primaryProfile.getQueryMode() != TavilyQueryMode.OFFICIAL_DOCS) {
        return false;
    }
    if (primaryCandidates == null || primaryCandidates.isEmpty()) {
        return true;
    }
    long usableCount = primaryCandidates.stream()
            .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
            .count();
    return usableCount <= 0L;
}
```

删除 `officialDocHitCount <= 0L` 触发分支。B 站这类 SPA 官网即使不是 `OFFICIAL_DOC/PDF` 页型，只要有可用候选，就不应继续开放网扩散。

- [ ] **Step 4: 跑 fast lane acceptance 测试**

Run:

```powershell
mvn -pl backend "-Dtest=TavilyFastLaneAcceptanceTest" test
```

Expected: PASS。若某个旧测试依赖“有可用候选但无 OFFICIAL_DOC/PDF 也 expansion”，需要改测试名和断言，因为该行为就是本 Task 要移除的成本放大源。

实测记录：

```powershell
mvn -pl backend "-Dtest=TavilyFastLaneProviderTest#shouldRecordPerQueryAuditForExecutedFailedAndBudgetSkippedFieldEvidenceQueries+shouldNotFallbackWhenOfficialDocsQueryAlreadyReturnsUsableOfficialPage,TavilyFastLaneAcceptanceTest#shouldNotFallbackToTrustedExpansionWhenOfficialDocsReturnsUsableCandidate+shouldFallbackToTrustedExpansionWhenOfficialAnchorOnlyReturnsNoisePages" test
```

结果摘要：
- PASS。
- `shouldExpand(...)` 已收紧为“无结果或零可用候选才扩展”。
- 新增了“已有可用官方页但不是 `OFFICIAL_DOC/PDF` 时不再扩展”的保护测试，防止旧 `officialDocHitCount` 逻辑回退。

## 10. Task 5: 回归与 task88 复验

**Files:**
- Test-only command execution.
- Optional: 更新本文件第 2 节勾选状态与实测结果。

- [ ] **Step 1: 跑核心单测集合**

Run:

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,TavilyFieldEvidenceProfileResolverTest,TavilySearchProfileResolverTest,TavilyFastLaneAcceptanceTest" test
```

Expected: PASS。

- [ ] **Step 2: 跑搜索协调器与候选选择回归**

Run:

```powershell
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,CandidateVerifierTest" test
```

Expected: PASS。

- [ ] **Step 3: 复验 audit 计数**

用 task88 同类输入重跑一轮，统计每个 collector 的：

- `fieldEvidenceQueryPlannedCount`
- `fieldEvidenceQueryExecutedCount`
- `fieldEvidenceQuerySkippedCount`
- `fieldEvidenceQuerySkipReasons.SKIPPED_CROSS_NODE_DEDUP`
- `tavilyFastLaneAudit.queryModes`
- `tavilyFastLaneAudit.queriesSent`
- Tavily `requestId` 总数
- 官方候选数、官方可用候选数、第三方候选数
- 真正第二枪 expansion 次数：`executedProfiles` 或 audit 中同一官方锚点搜索后追加的 `TRUSTED_WEB_EXPANSION`

验收口径：

- 首个同竞品 collector 仍可执行字段 query。
- 并发 collector 合并后的 executed fingerprint 无重复；不要断言固定由哪个节点执行。
- 同竞品重复 fingerprint 出现 `SKIPPED_CROSS_NODE_DEDUP`。
- `TRUSTED_WEB_EXPANSION` queryMode 数量下降，`OFFICIAL_DOCS` 数量上升。
- 第二枪 expansion 只出现在无结果或零可用候选的官方锚点场景。
- 官方候选数不低于修改前同输入基线；若低于基线，需要按回滚边界优先评估 Task 4/Task 3。
- 第三方 `REVIEW / NEWS / OPEN_WEB` 仍有候选进入 selected target 或 audit。

降本归因必须分开写：

- 去重收益：`SKIPPED_CROSS_NODE_DEDUP` 数量、并发 union 去重后的 executed fingerprint 数量。
- expansion 收益：真正第二枪 `TRUSTED_WEB_EXPANSION` 次数相对基线的下降量。
- 总收益：Tavily `requestId` 总数下降量。

如果总 requestId 下降但 `SKIPPED_CROSS_NODE_DEDUP=0`，说明第一刀没有生效，不能把收益归因给跨节点去重。

- [ ] **Step 4: 回滚边界**

如果证据质量明显下降，按下面顺序回滚：

1. 先回滚 Task 4 的 `shouldExpand` 保守化，保留 Task 1-3，观察是否只是 expansion 太紧。
2. 如果官方证据召回明显下降，再回滚 Task 3，恢复官方字段 search-first expansion mode。
3. 最后才回滚 Task 1-2；跨节点去重是最小风险降本点，不应优先回滚。

实测记录：

```powershell
mvn -pl backend "-Dtest=FieldEvidenceQueryExecutionGateTest,SearchExecutionCoordinatorFieldEvidenceBudgetTest,TavilyFieldEvidenceProfileResolverTest,TavilySearchProfileResolverTest,TavilyFastLaneAcceptanceTest,TavilyFastLaneProviderTest" test
mvn -pl backend "-Dtest=SearchExecutionCoordinatorTest,CollectionTargetSelectorTest,SourceCandidateRankerTest,CandidateVerifierTest" test
```

结果摘要：
- 两轮回归均 BUILD SUCCESS。
- 第一轮共 42 个测试通过，覆盖 Task 1-4 的核心链路。
- 第二轮共 64 个测试通过，覆盖 coordinator、selector、ranker、verifier 回归。
- Maven 仍输出本机环境已有的 `settings.xml` 配置警告与部分 XML fatal log，但未导致测试失败。
- `Task 5 / Step 3` 的 task88 同类输入实跑指标复验尚未执行，本轮先停在测试回归完成状态。

## 11. 关键注意事项

- 跨节点去重使用当前 `queryFingerprint`，它包含 `fieldName | pathKey | queryIntent | sourceType | query`，不会把同字段但不同 sourceType 的 query 误杀。
- 只能走路 A：新增独立 `Map<String, Set<String>> fieldEvidenceFingerprintClaims`，并在 `forkNodeContext` 中同引用传递；不要复用 `Map<String, String> sharedState`。
- `FieldEvidenceQueryExecutionGate` 不能保存状态；状态必须来自 `AgentContext.fieldEvidenceFingerprintClaims` 传入的并发 set。
- claim key 必须包含 taskId 和 competitorName，避免多竞品、多任务互相去重。
- 并行 collector 下，不能使用“读集合快照 -> 过滤 -> 事后 append”的 check-then-act；必须使用 `ConcurrentHashMap.newKeySet()` 和 `Set.add(fingerprint)` 的原子返回值。
- 单测不能断言“第二个节点为空”；并发下谁先 claim 不确定，只能断言两个节点合并后的 executed fingerprint 无重复。
- `SKIPPED_CROSS_NODE_DEDUP` 必须出现在 execution trace 和 audit summary 的 skipReasons 中，方便 task88 复盘确认降本来自去重而不是 provider 静默跳过。
- Task 5 复验必须分别统计 `SKIPPED_CROSS_NODE_DEDUP` 带来的去重收益和第二枪 expansion 减少带来的收益，避免两刀一起上后归因错误。
- 2a 是 2b 的前提：官方字段不先回到 `OFFICIAL_DOCS`，`shouldExpand` 的第一道 `queryMode != OFFICIAL_DOCS` 判断会直接 return false，2b 管不到官方字段。
