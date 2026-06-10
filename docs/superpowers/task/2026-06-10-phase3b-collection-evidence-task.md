# Phase 3B Collection Evidence Task

## 核心目标

把 `collection-intelligence` 收口到 `CollectionEvidenceFacade` 和 `CollectionArtifactCleanupPort`，让证据读取、节点级证据清理和搜索协调边界可以独立演进。

## 预期耗时

- `1 - 1.5` 人天

## 前置依赖

- `phase1-agent-runtime-baseline-task` 已完成
- `phase2-archunit-boundary-task` 已完成
- `phase3a-task-orchestration-task` 已完成
- `phase3b` 必须在 `phase3a` 完成并合入共享集成线后串行开始，且不得修改 `BaseAgent` 与 `DagExecutor`

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase3b-collection-evidence-progress.md`

## 完成定义

- 存在 `CollectionEvidenceFacade`
- 存在 `CollectionArtifactCleanupPort`
- report 新增证据读取入口默认经 facade，不再新增 repository 直连
- `buildEvidencePrefix` 采用“先用 contract test 锁定现有算法，再在 collection 内部复用该算法”的单一路线
- `SearchExecutionCoordinator` 的职责边界有真实测试保护，而不是仅靠注释

## 文件边界

### Must Modify

- `backend/src/main/java/cn/bugstack/competitoragent/report/EvidenceQueryService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/application/CollectionEvidenceFacade.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/application/CollectionEvidenceFacadeImpl.java`
- `backend/src/main/java/cn/bugstack/competitoragent/collection/application/cleanup/CollectionArtifactCleanupPort.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskArtifactCleanupService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/test/java/cn/bugstack/competitoragent/report/EvidenceQueryServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

### May Modify

- `backend/src/main/java/cn/bugstack/competitoragent/repository/EvidenceSourceRepository.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitectureWhitelist.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/source/SourceCandidateRankerTest.java`
- `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`

### Read For Context

- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/BaseAgent.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

## 证据前缀策略

本阶段明确采用第三种路线：

- 先用 contract test 锁定 `TaskArtifactCleanupService.buildEvidencePrefix(...)` 的现有算法
- 再在 collection 内部引入复用同一算法的数据集与断言
- phase3b 不允许新造第三种 evidence 前缀规则
- phase3b 不强求立即抽 shared-kernel；是否抽成 collection 内部工具，等 contract test 稳定后再迁移

---

## Task 1: 建立 `CollectionEvidenceFacade`

### Task 核心目标

为 task / report / conversation 后续读取证据建立稳定 facade，而不是继续把 `EvidenceQueryService` 当跨模块实现细节暴露。

### Task 预期耗时

- `3 - 4` 小时

### Task 前置依赖

- phase1、phase2、phase3a 完成

### 执行步骤

- [ ] Step 1：创建 `CollectionEvidenceFacade` 最小接口。
- [ ] Step 2：用 `EvidenceQueryService` 包一层实现，不改现有 report 行为。
- [ ] Step 3：为节点级证据读取补 `listNodeEvidence(...)`。
- [ ] Step 4：补 `EvidenceQueryServiceTest`，锁定任务级与节点级查询输出。

### 最小接口形状

```java
public interface CollectionEvidenceFacade {

    List<ReportResponse.EvidenceInfo> listTaskEvidence(Long taskId);

    List<ReportResponse.EvidenceInfo> listNodeEvidence(Long taskId, String nodeName);

    ReportResponse.EvidenceEntryPointInfo getEvidenceEntryPoint(Long taskId);
}
```

### 最小测试结构

```java
@Test
void should_filter_evidence_by_node_prefix() {
    EvidenceSource first = EvidenceSource.builder()
            .taskId(12L)
            .evidenceId("T0012-COLLECT_SOURCES-001")
            .competitorName("A")
            .title("A")
            .url("https://a.com")
            .build();
    EvidenceSource second = EvidenceSource.builder()
            .taskId(12L)
            .evidenceId("T0012-WRITE_REPORT-001")
            .competitorName("B")
            .title("B")
            .url("https://b.com")
            .build();
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(12L)).thenReturn(List.of(first, second));

    List<ReportResponse.EvidenceInfo> actual = service.listEvidencesByNode(12L, "collect_sources");

    assertEquals(1, actual.size());
    assertEquals("T0012-COLLECT_SOURCES-001", actual.get(0).getEvidenceId());
}
```

### 验证命令

```powershell
mvn -Dtest=EvidenceQueryServiceTest test
```

---

## Task 2: 建立 collection cleanup port

### Task 核心目标

让 task 侧清理证据时只调用 port，不再直接触碰 `EvidenceSourceRepository`。

### Task 预期耗时

- `2 - 4` 小时

### Task 前置依赖

- `phase3a-task-orchestration-task` 的 cleanup 接口已存在
- Task 1 完成

### 执行步骤

- [ ] Step 1：创建 `CollectionArtifactCleanupPort` 并实现 `cleanupTaskArtifacts(...)` 与 `cleanupNodeArtifacts(...)`。
- [ ] Step 2：把 `TaskArtifactCleanupService` 中的 evidence 删除职责迁出到 collection cleanup port，回收 task 侧对 `EvidenceSourceRepository` 的历史直连。
- [ ] Step 3：保留 `EvidenceSourceRepository.deleteByTaskIdAndEvidenceIdStartingWith(...)`，不新增模糊删除 SQL。
- [ ] Step 4：新增 contract test，锁定 evidenceId 前缀编码与节点级删除使用同一算法，并同步更新白名单台账。

### 最小实现形状

```java
@Component
@RequiredArgsConstructor
public class CollectionArtifactCleanupPort implements TaskArtifactCleanupPort {

    private final EvidenceSourceRepository evidenceSourceRepository;

    @Override
    public String moduleName() {
        return "collection";
    }

    @Override
    public void cleanupTaskArtifacts(Long taskId) {
        evidenceSourceRepository.deleteByTaskId(taskId);
    }

    @Override
    public void cleanupNodeArtifacts(Long taskId, String nodeName) {
        evidenceSourceRepository.deleteByTaskIdAndEvidenceIdStartingWith(
                taskId,
                buildEvidencePrefix(taskId, nodeName)
        );
    }
}
```

### 前缀 contract test 最小结构

```java
@Test
void should_keep_same_evidence_prefix_contract_as_task_runtime_cleanup() {
    String prefix = EvidenceIdPrefixContract.build(12L, "collect_sources");

    assertEquals("T0012-COLLECT_SOURCES-", prefix);
}
```

```java
@Test
void should_encode_task_and_node_name_in_evidence_id() throws Exception {
    when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
            .candidates(List.of())
            .executedQueries(List.of())
            .summary("mock")
            .fallbackSuggested(true)
            .build());
    when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
    when(sourceCollector.collect("https://example.com/docs", "Feishu", "DOCS"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://example.com/docs")
                    .title("Docs")
                    .content("content")
                    .snippet("snippet")
                    .competitorName("Feishu")
                    .sourceType("DOCS")
                    .success(true)
                    .build());

    AgentResult result = collectorAgent.execute(buildContext("[\"https://example.com/docs\"]"));
    JsonNode output = objectMapper.readTree(result.getOutputData());

    assertTrue(output.path("documents").get(0).path("evidenceId").asText()
            .startsWith("T0012-COLLECT_SOURCES-"));
}
```

### 验证命令

```powershell
mvn -Dtest=CollectorAgentTest,EvidenceQueryServiceTest test
```

---

## Task 3: 锁定 `SearchExecutionCoordinator` 职责边界

### Task 核心目标

让搜索协调逻辑的“候选验证 / 补源 / 目标选择”至少被测试顺序和结果锁定，不再只靠人工理解维护。

### Task 预期耗时

- `2 - 4` 小时

### Task 前置依赖

- Task 1、Task 2 完成

### 执行步骤

- [ ] Step 1：补充协调器测试，明确候选验证先于目标选择。
- [ ] Step 2：补充“验证结果已足够时不触发补源”的测试。
- [ ] Step 3：补充“补源为空时保留规划候选”的测试。
- [ ] Step 4：在类中补中文职责注释，但不做大规模函数拆分。

### 最小测试结构

```java
@Test
void should_delegate_candidate_verification_before_target_selection() {
    when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
            .candidates(List.of())
            .executedQueries(List.of())
            .summary("unused")
            .fallbackSuggested(true)
            .build());
    when(searchSourceProvider.search(any(), any())).thenReturn(List.of());
    when(sourceCollector.collect("https://docs.example.com/guide", "Notion AI", "DOCS"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://docs.example.com/guide")
                    .title("Documentation Guide")
                    .content("API reference and help guide for the product.")
                    .snippet("API reference and help guide")
                    .competitorName("Notion AI")
                    .sourceType("DOCS")
                    .success(true)
                    .build());

    SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
            .competitorName("Notion AI")
            .sourceType("DOCS")
            .sourceCandidates(List.of(SourceCandidate.builder()
                    .url("https://docs.example.com/guide")
                    .title("Docs")
                    .sourceType("DOCS")
                    .discoveryMethod("HEURISTIC")
                    .reason("规划期候选")
                    .domain("docs.example.com")
                    .relevanceScore(0.9)
                    .freshnessScore(0.7)
                    .qualityScore(0.9)
                    .build()))
            .verifyCandidates(Boolean.TRUE)
            .browserSearchEnabled(Boolean.TRUE)
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .build());

    assertEquals(1, result.getSelectedTargets().size());
    assertEquals("SKIP_SUPPLEMENT_ENOUGH_VERIFIED", result.getExecutionTrace().getFallbackDecision());
}
```

```java
@Test
void should_skip_browser_supplement_when_verified_candidates_are_enough() {
    SearchExecutionResult result = coordinator.execute(validVerifiedConfig());

    assertEquals("SKIP_SUPPLEMENT_ENOUGH_VERIFIED", result.getExecutionTrace().getFallbackDecision());
    verify(browserSearchRuntimeService, never()).search(any());
}
```

### 验证命令

```powershell
mvn -Dtest=SearchExecutionCoordinatorTest,CandidateVerifierTest,SourceCandidateRankerTest,BrowserSearchRuntimeServiceTest test
```

---

## Task 4: 阶段收尾

### Task 核心目标

确认 phase3b 只收口证据边界，不承诺移除 `CollectorAgent` 继承 `BaseAgent` 带来的历史依赖。

### Task 预期耗时

- `1` 小时

### Task 前置依赖

- Task 1 - Task 3 完成

### 执行步骤

- [ ] Step 1：运行 collection 线聚焦测试。
- [ ] Step 2：核对 `BaseAgent`、`DagExecutor` 是否被误改。
- [ ] Step 3：在 progress 中记录当前仍保留的 `CollectorAgent` 历史白名单。

### 验证命令

```powershell
mvn -Dtest=SearchExecutionCoordinatorTest,CandidateVerifierTest,SourceCandidateRankerTest,BrowserSearchRuntimeServiceTest,CollectorAgentTest,EvidenceQueryServiceTest test
```

### 提交标准

- 只包含 evidence facade、cleanup port、前缀 contract、搜索职责边界测试
- 不混入 `BaseAgent` 模板修改
- PR 描述必须明确写出“phase3b 不承诺清除 `CollectorAgent` 的历史 runtime / repository 依赖，只收口 evidence 边界”
