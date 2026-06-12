# Search And Collection Execution Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `2.1.2 搜索与采集` 收口为正式的“搜索策略 -> 候选验证 -> 目标选择 -> 正式采集 -> 审计回放 -> 恢复续跑”业务链路，直接吸收 `CollectorAgent.md` 中已经定位的问题，并复用 `2.1.1` 已冻结的任务定义、执行计划与 preview/runtime 契约边界。

**Architecture:** 规划期继续以 `TaskDefinition -> ExecutionPlanDefinition -> WorkflowPlan` 为唯一真相，搜索策略不再散落在 `CollectorPlanTemplateFactory` 与 `SearchExecutionCoordinator` 的私有分支里，而是统一交给独立 `SearchPolicyResolver`，把 `fallbackOrder / minVerifiedCandidates / targetCount / searchTimeout / engine fallback` 一并收口到同一策略入口。运行期继续以 `TASK_NODE_RUNTIME_V1` 为主契约，把 `searchAudit`、`selectedTargets`、`checkpointSummary`、`searchReplays` 严格留在 runtime / replay 视图，不回灌 `TaskPlanPreviewResponse`；Collector 仍然是采集 Facade，但搜索质量、回放、恢复和共享投影逻辑要前移到独立搜索子域协作者中。

**Tech Stack:** Java 17, Spring Boot, Spring MVC, Jackson, Redis, Lombok, JUnit 5, Mockito, React 18, TypeScript, Vite, Vitest

---

## File Structure

**Backend - Create**

- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`

**Backend - Modify**

- `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorNodeConfig.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java`
- `backend/src/main/resources/prompts/search-queries.yml`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java`
- `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/event/TaskSseHub.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java`
- `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`

**Backend - Test**

- `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/controller/TaskEventStreamControllerTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java`

**Frontend - Modify**

- `frontend/src/types/index.ts`
- `frontend/src/utils/taskNodeInsights.ts`
- `frontend/src/utils/taskNodeInsights.test.ts`
- `frontend/src/utils/taskEventReducer.ts`
- `frontend/src/utils/taskEventReducer.test.ts`
- `frontend/src/pages/TaskDetailPage.tsx`
- `frontend/src/pages/TaskDetailPage.test.tsx`
- `frontend/src/components/task-detail/SearchActivityPanel.tsx`
- `frontend/src/components/task-detail/NodeTraceDrawer.tsx`
- `frontend/src/components/task-detail/NodeTraceDrawer.test.tsx`
- `frontend/src/components/task-detail/TaskReplayTimeline.tsx`
- `frontend/src/components/task-detail/TaskReplayTimeline.test.tsx`

---

### Task 1: 锁定搜索质量、回放与恢复的红灯基线

**核心目标：** 先把 `spec 4.2` 里的五条红灯断言写成真实测试，并补齐 runtime event / replay / 前端消费面的红灯约束，防止第二阶段在“修问题”的同时继续漂移契约。

**预估耗时：** 45 分钟

**前置依赖：** 已完成 `2.1.1`，仓库中已经存在 `SearchExecutionPlan`、`SearchAuditSnapshot`、`TaskReplayResponse`、`CollectorNodeInsightResponse`、`searchAuditCheckpoint` 基础结构。

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java`
- Modify: `frontend/src/utils/taskEventReducer.test.ts`
- Modify: `frontend/src/components/task-detail/NodeTraceDrawer.test.tsx`
- Modify: `frontend/src/pages/TaskDetailPage.test.tsx`

- [ ] **Step 1: 新建搜索链路 Golden Master 红灯测试**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchAndCollectionGoldenMasterTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);

    @Test
    void shouldRejectOfficialMarketingPageEvenWhenDomainAuthorityIsHigh() {
        when(sourceCollector.collect("https://www.aliyun.com/product/ecs", "阿里云", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("阿里云新客特惠 - 云服务器限时秒杀")
                        .content("立即购买 优惠券 秒杀 活动页 新客专享")
                        .snippet("新客特惠")
                        .competitorName("阿里云")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "阿里云",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("阿里云 ECS")
                        .sourceType("OFFICIAL")
                        .domain("www.aliyun.com")
                        .relevanceScore(0.95)
                        .freshnessScore(0.80)
                        .qualityScore(0.99)
                        .build())
        );

        assertEquals(0, result.getVerifiedTargets().size());
        assertEquals("DISCARDED", result.getUpdatedCandidates().get(0).getSelectionStage());
        assertTrue(result.getUpdatedCandidates().get(0).getVerificationReason().contains("营销"));
    }

    @Test
    void shouldAcceptHighValueChinesePricingDocument() {
        when(sourceCollector.collect("https://cloud.tencent.com/document/product/1234/5678", "腾讯云", "PRICING"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("云数据库价格说明")
                        .content("本页说明计费方式、套餐差异、价格与收费规则。")
                        .snippet("计费方式与价格说明")
                        .competitorName("腾讯云")
                        .sourceType("PRICING")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "腾讯云",
                "PRICING",
                List.of(SourceCandidate.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("云数据库价格说明")
                        .sourceType("PRICING")
                        .domain("cloud.tencent.com")
                        .build())
        );

        assertEquals(1, result.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        assertFalse(result.getUpdatedCandidates().get(0).getMatchedSignals().isEmpty());
    }
}
```

- [ ] **Step 2: 运行 Golden Master 测试，确认当前实现确实不满足**

Run: `mvn -pl backend -Dtest=SearchAndCollectionGoldenMasterTest,CandidateVerifierTest test`

Expected: FAIL，至少应出现以下一种失败：
- `OFFICIAL` 页面仍被直接判定为 `verified=true`
- 中文价格页未命中关键词，`verifiedTargets` 数量仍为 `0`

- [ ] **Step 3: 新建 runtime event / replay 红灯测试，锁定 `searchAudit` 与 `selectedTargets` 契约**

```java
package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RuntimeEventEmitterTest {

    @Test
    void shouldPublishFormalSearchAuditAndSelectedTargetsFromCollectorOutput() {
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        RuntimeEventEmitter emitter = new RuntimeEventEmitter(taskEventPublisher, mock(cn.bugstack.competitoragent.log.AgentLogService.class), new ObjectMapper());
        TaskNode node = TaskNode.builder()
                .taskId(24L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "searchProgress":{"status":"SUCCESS","currentStep":"SELECT_TARGETS"},
                          "searchExecutionTrace":{"recoveryCheckpoint":"SELECT_TARGETS","degraded":false},
                          "searchAudit":{"executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"}},
                          "selectedTargets":[{"url":"https://docs.notion.so/reference","title":"Reference"}],
                          "sourceUrls":["https://docs.notion.so/reference"]
                        }
                        """)
                .build();

        emitter.publishNodeExecutionEvents(24L, node);

        verify(taskEventPublisher).publishSearchProgressEvent(eq(24L), eq("collect_sources_docs"), argThat(payload ->
                payload.containsKey("searchAudit")
                        && payload.containsKey("selectedTargets")
                        && payload.containsKey("sourceUrls")));
    }
}
```

```java
package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskReplayProjectionServiceTest {

    @Test
    void shouldExposeSearchReplaySnapshotFromCollectorAuditOutput() {
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(TaskNode.builder()
                .id(1L)
                .taskId(42L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(31L)
                .branchKey("root")
                .outputData("""
                        {
                          "searchAudit":{
                            "executionTrace":{"traceVersion":"v1","recoveryCheckpoint":"SELECT_TARGETS"},
                            "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}]
                          },
                          "selectedTargets":[{"url":"https://docs.notion.so/reference","title":"Reference"}],
                          "sourceUrls":["https://docs.notion.so/reference"]
                        }
                        """)
                .build()));

        TaskReplayProjectionService service = new TaskReplayProjectionService(
                mock(TaskPlanRepository.class),
                mock(TaskWorkflowEventRepository.class),
                nodeRepository,
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(MemorySnapshotRepository.class),
                mock(AgentExecutionLogRepository.class),
                mock(RecoveryCheckpointService.class),
                mock(TaskRecoveryService.class),
                new ObjectMapper().findAndRegisterModules()
        );

        TaskReplayResponse response = service.getTaskReplay(42L);

        assertNotNull(response.getSearchReplays());
        assertEquals("collect_sources_docs", response.getSearchReplays().get(0).getNodeName());
        assertEquals("SELECT_TARGETS",
                response.getSearchReplays().get(0).getSearchAudit().getExecutionTrace().getRecoveryCheckpoint());
    }
}
```

- [ ] **Step 4: 运行回放与事件红灯测试**

Run: `mvn -pl backend -Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskEventStreamControllerTest test`

Expected: FAIL，原因应集中在：
- `RuntimeEventEmitter` 当前没有发布 `searchAudit` / `selectedTargets` / `sourceUrls`
- `TaskReplayResponse` 还不存在 `searchReplays`

- [ ] **Step 5: 扩展前端测试，锁定 task detail / reducer 对正式搜索审计对象的消费**

```ts
it('merges formal search audit into collector insight when SEARCH_PROGRESS events arrive', () => {
  const updated = reduceTaskEventState(
    {
      task: null,
      nodes: [
        {
          id: 1,
          nodeName: 'collect_sources_docs',
          displayName: 'collect_sources_docs',
          agentType: 'COLLECTOR',
          dependsOn: '[]',
          required: true,
          status: 'RUNNING',
          executionOrder: 0,
          nodeConfig: null,
          errorMessage: null,
          inputSummary: null,
          outputSummary: null,
          startedAt: null,
          completedAt: null,
          collectorInsight: null,
        },
      ],
      logs: [],
      diagnosis: null,
      replay: null,
    },
    {
      taskId: 24,
      eventType: 'SEARCH_PROGRESS',
      nodeName: 'collect_sources_docs',
      payload: {
        nodeName: 'collect_sources_docs',
        searchProgress: { status: 'RUNNING', currentStep: 'SELECT_TARGETS' },
        searchAudit: {
          executionTrace: { recoveryCheckpoint: 'SELECT_TARGETS', degraded: false },
          sourceCandidates: [],
          selectedTargets: [],
        },
        selectedTargets: [{ url: 'https://docs.notion.so/reference', title: 'Reference' }],
        sourceUrls: ['https://docs.notion.so/reference'],
      },
    },
  )

  expect(updated.nodes[0].collectorInsight?.searchAudit?.executionTrace?.recoveryCheckpoint).toBe('SELECT_TARGETS')
  expect(updated.nodes[0].collectorInsight?.selectedTargets[0].url).toBe('https://docs.notion.so/reference')
})
```

```tsx
it('renders search replay checkpoint and audit source urls in node trace drawer', () => {
  render(
    <NodeTraceDrawer
      open
      onClose={() => {}}
      node={buildCollectorNode({
        collectorInsight: {
          competitorName: 'Notion AI',
          sourceType: 'DOCS',
          sourceScope: ['官网', '产品文档'],
          competitorUrls: ['https://www.notion.so/product/ai'],
          searchQueries: ['Notion AI documentation'],
          browserSearchEnabled: true,
          verifyResultPage: true,
          minVerifiedCandidates: 1,
          preferredDomains: ['notion.so'],
          candidateCount: 3,
          selectedCount: 1,
          successCollected: 1,
          totalCollected: 1,
          searchProgress: { status: 'SUCCESS', currentStep: 'SELECT_TARGETS' },
          searchExecutionPlan: null,
          searchExecutionTrace: { recoveryCheckpoint: 'SELECT_TARGETS', degraded: false },
          searchAudit: {
            executionTrace: { recoveryCheckpoint: 'SELECT_TARGETS', degraded: false },
            sourceCandidates: [],
            selectedTargets: [],
            sourceUrls: ['https://docs.notion.so/reference'],
          },
          sourceCandidates: [],
          selectedTargets: [{ url: 'https://docs.notion.so/reference', title: 'Reference', rankingReasons: ['已验证'] }],
        },
      })}
      selectedTargets={[{ url: 'https://docs.notion.so/reference', title: 'Reference', rankingReasons: ['已验证'] }]}
    />,
  )

  expect(screen.getByText(/SELECT_TARGETS/)).toBeInTheDocument()
  expect(screen.getByText('https://docs.notion.so/reference')).toBeInTheDocument()
})
```

- [ ] **Step 6: 运行前端红灯测试**

Run: `npm --prefix frontend test -- src/utils/taskEventReducer.test.ts src/components/task-detail/NodeTraceDrawer.test.tsx src/pages/TaskDetailPage.test.tsx`

Expected: FAIL，当前前端类型和 reducer 还没有 `searchAudit` / `searchReplays` 这些正式字段。

- [ ] **Step 7: 提交红灯基线**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/SearchAndCollectionGoldenMasterTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/search/CandidateVerifierTest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/AnalysisTaskServiceTest.java frontend/src/utils/taskEventReducer.test.ts frontend/src/components/task-detail/NodeTraceDrawer.test.tsx frontend/src/pages/TaskDetailPage.test.tsx
git commit -m "test(search): lock search and collection golden master"
```

### Task 2: 收口搜索策略解析器、双语 Query 模板与候选验证规则

**核心目标：** 把 `fallbackOrder / minVerifiedCandidates / targetCount / searchTimeout / browserEnabled / engine fallback` 的默认推导从私有分支抽成独立 `SearchPolicyResolver`，同时修正 `PromptTemplateService` 英文模板串线、搜索引擎别名强制绑死 Bing、`OFFICIAL` 免检、中文关键词缺失与营销词漏检问题。

**预估耗时：** 70 分钟

**前置依赖：** Task 1 的红灯测试已落地；当前 `CollectorPlanTemplateFactory` 和 `SearchExecutionCoordinator` 仍各自维护 fallback 顺序。

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java`
- Modify: `backend/src/main/resources/prompts/search-queries.yml`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java`

- [ ] **Step 1: 先补策略和模板层的红灯测试**

```java
@Test
void shouldKeepEnglishSearchTemplatesIndependentFromChineseYamlOverrides() {
    String rendered = promptTemplateService.buildSearchQueries("Notion AI", "DOCS", "notion.so").get(0);
    assertTrue(rendered.contains("documentation"));
    assertFalse(rendered.contains("文档 API 开发指南"));
}

@Test
void shouldNotForceBrowserAliasesToBingWhenResolvingSearchEngineKeys() {
    SearchEngineProperties properties = new SearchEngineProperties();
    properties.get("duckduckgo").setEnabled(true);
    assertEquals("duckduckgo", properties.resolveAvailableEngineKey("ddg"));
    assertEquals("chrome", properties.normalizeEngineKey("chrome"));
}
```

Run: `mvn -pl backend -Dtest=PromptTemplateServiceTest,SearchPropertiesBindingTest,WorkflowFactoryTest test`

Expected: FAIL，当前英文模板池会被中文 YAML 覆盖，`chrome/chromium/msedge` 仍被强行归一到 `bing`。

- [ ] **Step 2: 新增 `SearchKeywordPolicy` 与 `SearchPolicyResolver`**

```java
package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索关键词策略。
 * 统一维护不同 sourceType 的正向业务信号与营销噪声阻断词，
 * 避免 CandidateVerifier、Selector 和未来搜索审计投影再各自复制词库。
 */
@Component
public class SearchKeywordPolicy {

    private static final Map<String, List<String>> EXPECTED_KEYWORDS = new LinkedHashMap<>();
    private static final List<String> BLOCKED_KEYWORDS = List.of(
            "coupon", "promotion", "activity", "seckill",
            "立即购买", "新客特惠", "优惠券", "促销", "活动", "秒杀", "抽奖", "代金券");

    static {
        EXPECTED_KEYWORDS.put("DOCS", List.of(
                "docs", "documentation", "help", "guide", "api", "reference",
                "文档", "帮助中心", "指南", "手册", "开发指南", "参考手册"));
        EXPECTED_KEYWORDS.put("PRICING", List.of(
                "pricing", "plan", "plans", "billing", "subscription", "enterprise",
                "计费", "价格", "收费", "费用", "定价", "套餐", "报价"));
        EXPECTED_KEYWORDS.put("NEWS", List.of(
                "blog", "news", "changelog", "update", "release", "announcement",
                "发布日志", "更新", "公告", "产品动态"));
        EXPECTED_KEYWORDS.put("REVIEW", List.of(
                "review", "reviews", "rating", "customer", "compare", "g2", "capterra",
                "评测", "评价", "对比", "怎么样"));
        EXPECTED_KEYWORDS.put("OFFICIAL", List.of(
                "official", "product", "platform", "homepage",
                "官网", "产品", "平台", "产品介绍"));
    }

    public List<String> expectedKeywords(String sourceType) {
        String normalizedType = StringUtils.hasText(sourceType)
                ? sourceType.trim().toUpperCase(Locale.ROOT)
                : "OFFICIAL";
        return EXPECTED_KEYWORDS.getOrDefault(normalizedType, EXPECTED_KEYWORDS.get("OFFICIAL"));
    }

    public List<String> blockedKeywords() {
        return BLOCKED_KEYWORDS;
    }
}
```

```java
package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 搜索策略解析器。
 * Phase 2 起所有“默认搜索策略”都从这里产出：
 * fallback 顺序、目标数量、最小验证数量、超时预算、引擎回退。
 * 规划期模板工厂和运行期协调器只能读取，不再各自重写一份判断。
 */
@Component
public class SearchPolicyResolver {

    public List<String> resolveFallbackOrder(String searchMode, boolean browserSearchEnabled) {
        List<String> baseOrder = switch (searchMode == null ? "HYBRID" : searchMode.toUpperCase()) {
            case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
            case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
            case "HEURISTIC_ONLY" -> List.of("PLANNED", "HEURISTIC");
            default -> List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP");
        };
        LinkedHashSet<String> normalized = new LinkedHashSet<>(baseOrder);
        if (!browserSearchEnabled) {
            normalized.remove("BROWSER");
        }
        return new ArrayList<>(normalized);
    }

    public int resolveMinVerifiedCandidates(Integer configuredValue, int plannedUrlCount, int targetCount) {
        if (configuredValue != null && configuredValue > 0) {
            return Math.min(configuredValue, Math.max(1, targetCount));
        }
        return Math.min(2, Math.max(1, Math.min(plannedUrlCount, Math.max(1, targetCount))));
    }

    public int resolveTargetCount(Integer configuredMaxSearchResults,
                                  List<String> plannedUrls,
                                  int candidateCount) {
        int plannedUrlCount = plannedUrls == null ? 0 : plannedUrls.size();
        if (configuredMaxSearchResults != null && configuredMaxSearchResults > 0) {
            if (plannedUrlCount > 0) {
                return Math.min(configuredMaxSearchResults, plannedUrlCount);
            }
            return Math.max(1, configuredMaxSearchResults);
        }
        if (plannedUrlCount > 0) {
            return plannedUrlCount;
        }
        return Math.max(1, candidateCount);
    }

    public long resolveSearchTimeoutMillis(Long configuredValue, SearchExecutionPlan executionPlan) {
        if (configuredValue != null && configuredValue >= 0) {
            return configuredValue;
        }
        long expectedNodeDuration = executionPlan == null || executionPlan.getSteps() == null
                ? 0L
                : executionPlan.getSteps().stream().mapToLong(SearchExecutionStep::getExpectedDurationMs).sum();
        if (expectedNodeDuration <= 0L) {
            return 15000L;
        }
        return Math.max(1000L, Math.round(expectedNodeDuration * 0.6D));
    }

    public String resolveSearchEngineKey(String requestedEngineKey, SearchEngineProperties searchEngineProperties) {
        if (searchEngineProperties == null) {
            return "duckduckgo";
        }
        String normalized = searchEngineProperties.normalizeEngineKey(requestedEngineKey);
        return searchEngineProperties.resolveAvailableEngineKey(normalized);
    }
}
```

- [ ] **Step 3: 修改 `CandidateVerifier`，移除 `OFFICIAL` 免检并补齐 try-catch + retry**

```java
@Component
@RequiredArgsConstructor
public class CandidateVerifier {

    private static final int MAX_COLLECT_RETRIES = 2;

    private final SourceCollector sourceCollector;
    private final SearchKeywordPolicy searchKeywordPolicy;

    public CandidateVerificationResult verify(String competitorName,
                                              String sourceType,
                                              List<SourceCandidate> candidates) {
        List<SourceCandidate> uniqueCandidates = deduplicateCandidates(candidates);
        if (uniqueCandidates.isEmpty()) {
            return CandidateVerificationResult.builder()
                    .updatedCandidates(List.of())
                    .attemptedTargets(List.of())
                    .verifiedTargets(List.of())
                    .build();
        }

        List<SourceCandidate> updatedCandidates = new ArrayList<>();
        List<SearchCollectionTarget> attemptedTargets = new ArrayList<>();
        List<SearchCollectionTarget> verifiedTargets = new ArrayList<>();

        for (SourceCandidate candidate : uniqueCandidates) {
            SourceCollector.CollectedPage page = collectPageWithRetry(candidate.getUrl(), competitorName, sourceType);
            List<String> matchedSignals = collectMatchedSignals(candidate, page, sourceType);
            boolean verified = isVerified(page, matchedSignals);
            String verificationReason = buildVerificationReason(page, sourceType, matchedSignals, verified);

            SourceCandidate updatedCandidate = candidate.toBuilder()
                    .verified(verified)
                    .verificationReason(verificationReason)
                    .matchedSignals(matchedSignals)
                    .selectionStage(verified ? "VERIFIED" : "DISCARDED")
                    .selectionReason(verified
                            ? "运行期验证通过，允许直接进入正式采集"
                            : "命中营销噪声或缺少业务信号，降级为候选兜底")
                    .build();
            SearchCollectionTarget target = SearchCollectionTarget.builder()
                    .candidate(updatedCandidate)
                    .collectedPage(page)
                    .build();
            updatedCandidates.add(updatedCandidate);
            attemptedTargets.add(target);
            if (verified) {
                verifiedTargets.add(target);
            }
        }

        return CandidateVerificationResult.builder()
                .updatedCandidates(updatedCandidates)
                .attemptedTargets(attemptedTargets)
                .verifiedTargets(verifiedTargets)
                .build();
    }

    private SourceCollector.CollectedPage collectPageWithRetry(String url, String competitorName, String sourceType) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_COLLECT_RETRIES; attempt++) {
            try {
                return sourceCollector.collect(url, competitorName, sourceType);
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(false)
                .errorMessage(lastError == null ? "candidate collect failed" : lastError.getMessage())
                .build();
    }

    private List<String> collectMatchedSignals(SourceCandidate candidate,
                                               SourceCollector.CollectedPage page,
                                               String sourceType) {
        Set<String> signals = new LinkedHashSet<>();
        String combined = (safe(candidate.getUrl()) + "\n" + safe(page == null ? null : page.getTitle())
                + "\n" + safe(page == null ? null : page.getContent())).toLowerCase(Locale.ROOT);
        for (String keyword : searchKeywordPolicy.expectedKeywords(sourceType)) {
            if (combined.contains(keyword.toLowerCase(Locale.ROOT))) {
                signals.add(keyword);
            }
        }
        for (String keyword : searchKeywordPolicy.blockedKeywords()) {
            if (combined.contains(keyword.toLowerCase(Locale.ROOT))) {
                signals.add("blocked:" + keyword);
            }
        }
        String domain = StringUtils.hasText(candidate.getDomain()) ? candidate.getDomain() : extractDomain(candidate.getUrl());
        if (StringUtils.hasText(domain)) {
            signals.add("domain:" + domain.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(signals);
    }

    private boolean isVerified(SourceCollector.CollectedPage page, List<String> matchedSignals) {
        if (!isUsableCollectedPage(page)) {
            return false;
        }
        boolean blocked = matchedSignals.stream().anyMatch(signal -> signal.startsWith("blocked:"));
        boolean matchedBusinessSignal = matchedSignals.stream()
                .anyMatch(signal -> !signal.startsWith("domain:") && !signal.startsWith("blocked:"));
        return !blocked && matchedBusinessSignal;
    }
}
```

- [ ] **Step 4: 让规划期与运行期共用 `SearchPolicyResolver`，并显式删除旧私有默认值方法**

```java
@Component
@RequiredArgsConstructor
public class CollectorPlanTemplateFactory {

    private final PromptTemplateService promptTemplateService;
    private final SearchBrowserProperties searchBrowserProperties;
    private final SearchProperties searchProperties;
    private final CollectorProperties collectorProperties;
    private final SearchPolicyResolver searchPolicyResolver;

    public CollectorNodeConfig createCollectorNodeConfig(String competitorName,
                                                         List<String> requestedScopes,
                                                         String schemaName,
                                                         SourcePlan sourcePlan) {
        String searchMode = resolveSearchMode();
        boolean browserEnabled = isBrowserSearchEnabledForMode(searchMode);
        int candidateCount = sourcePlan == null || sourcePlan.getCandidates() == null ? 0 : sourcePlan.getCandidates().size();
        List<String> searchQueries = buildDefaultSearchQueries(
                competitorName,
                sourcePlan == null ? null : sourcePlan.getSourceType(),
                sourcePlan == null ? List.of() : sourcePlan.getCandidates());
        List<String> fallbackOrder = searchPolicyResolver.resolveFallbackOrder(searchMode, browserEnabled);
        int targetCount = searchPolicyResolver.resolveTargetCount(
                collectorProperties == null ? null : collectorProperties.getMaxPagesPerCompetitor(),
                sourcePlan == null ? List.of() : sourcePlan.getUrls(),
                candidateCount
        );
        int plannedUrlCount = sourcePlan == null || sourcePlan.getUrls() == null ? 0 : sourcePlan.getUrls().size();
        int minVerifiedCandidates = searchPolicyResolver.resolveMinVerifiedCandidates(null, plannedUrlCount, targetCount);
        SearchExecutionPlan executionPlan = buildDefaultSearchExecutionPlan(
                searchQueries, fallbackOrder, sourcePlan, targetCount, minVerifiedCandidates);
        long searchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(null, executionPlan);

        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .competitorUrls(sourcePlan == null || sourcePlan.getUrls() == null ? List.of() : sourcePlan.getUrls())
                .sourceType(sourcePlan == null ? null : sourcePlan.getSourceType())
                .sourceScope(requestedScopes)
                .schemaName(schemaName)
                .discoveryNotes(sourcePlan == null ? null : sourcePlan.getNotes())
                .sourceCandidates(sourcePlan == null || sourcePlan.getCandidates() == null ? List.of() : sourcePlan.getCandidates())
                .searchMode(searchMode)
                .searchQueries(searchQueries)
                .searchFallbackOrder(fallbackOrder)
                .verifyCandidates(Boolean.TRUE)
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .minVerifiedCandidates(minVerifiedCandidates)
                .preferredDomains(buildPreferredDomains(sourcePlan == null ? List.of() : sourcePlan.getCandidates()))
                .blockedDomains(List.of())
                .browserSearchEnabled(browserEnabled)
                .maxSearchResults(targetCount)
                .searchTimeoutMillis(searchTimeoutMillis)
                .searchRuntimePolicy(buildDefaultSearchRuntimePolicy())
                .searchExecutionPlan(executionPlan)
                .build();
    }
}
```

```java
long searchStartedAt = System.currentTimeMillis();
SearchExecutionPlan executionPlan = initializePlan(config.getSearchExecutionPlan());
List<SourceCandidate> allCandidates = normalizeCandidates(
        resolveInitialCandidates(config),
        "PLANNED",
        config
);
int targetCount = searchPolicyResolver.resolveTargetCount(
        config.getMaxSearchResults(),
        config.getCompetitorUrls(),
        config.getSourceCandidates() == null ? 0 : config.getSourceCandidates().size()
);
int plannedUrlCount = config.getCompetitorUrls() == null ? 0 : config.getCompetitorUrls().size();
int minVerifiedCount = searchPolicyResolver.resolveMinVerifiedCandidates(
        config.getMinVerifiedCandidates(),
        plannedUrlCount,
        targetCount
);
long searchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(
        config.getSearchTimeoutMillis(),
        executionPlan
);
executionPlan = enrichExecutionPlan(executionPlan, config, targetCount, minVerifiedCount);
```

```java
private void loadSearchQueryTemplates() {
    try {
        ClassPathResource resource = new ClassPathResource("prompts/search-queries.yml");
        if (!resource.exists()) {
            log.warn("search query template file prompts/search-queries.yml not found, keep default templates");
            return;
        }
        Map<String, String> queryTemplates = yamlMapper.readValue(
                resource.getInputStream(),
                new TypeReference<Map<String, String>>() {
                }
        );
        if (queryTemplates == null || queryTemplates.isEmpty()) {
            log.warn("search query template file prompts/search-queries.yml is empty, keep default templates");
            return;
        }
        templates.putAll(queryTemplates);
    } catch (IOException e) {
        log.warn("load search query templates failed, keep default templates", e);
    }
}
```

```yaml
search-official: "{competitorName} 官方网站"
search-official-domain: "site:{domainHint} {competitorName}"
search-docs-primary: "{competitorName} 文档 API 开发指南"
search-docs-secondary: "{competitorName} 文档"
search-pricing-primary: "{competitorName} 定价 价格 套餐"
search-pricing-secondary: "{competitorName} 价格"
search-news-primary: "{competitorName} 产品更新 发布日志"
search-news-secondary: "{competitorName} 最新动态"
search-review-primary: "{competitorName} 评测 评价 对比"
search-review-secondary: "{competitorName} 怎么样 好不好用"
search-review-zhihu: "site:zhihu.com {competitorName} 评测 对比"
```

```java
public String normalizeEngineKey(String engineKey) {
    if (!StringUtils.hasText(engineKey)) {
        return "duckduckgo";
    }
    String normalized = engineKey.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
        case "ddg" -> "duckduckgo";
        default -> normalized;
    };
}
```

Implementation note: `duckduckgo` 如果在部署配置里关闭，`resolveAvailableEngineKey` 仍会通过 `resolveFirstEnabledEngineKey()` 回退到真正启用的引擎；这里的重点不是强制线上默认值，而是先移除“浏览器类型 = bing”这条质量损失最大的硬编码。

Implementation note: 迁移完成后要显式删除 `CollectorPlanTemplateFactory.buildSearchFallbackOrder()`、`resolveMinVerifiedCandidates()`、`resolveMaxSearchResults()` 以及 `SearchExecutionCoordinator` 里同类默认推导私有方法，避免新旧两套策略同时存在。

- [ ] **Step 5: 运行策略层与验证层测试，确认绿灯**

Run: `mvn -pl backend -Dtest=SearchAndCollectionGoldenMasterTest,CandidateVerifierTest,PromptTemplateServiceTest,SearchPropertiesBindingTest,WorkflowFactoryTest,SearchExecutionCoordinatorTest test`

Expected: PASS

- [ ] **Step 6: 提交策略与验证层收口**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchKeywordPolicy.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchPolicyResolver.java backend/src/main/java/cn/bugstack/competitoragent/search/CandidateVerifier.java backend/src/main/java/cn/bugstack/competitoragent/workflow/CollectorPlanTemplateFactory.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/llm/PromptTemplateService.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchEngineProperties.java backend/src/main/resources/prompts/search-queries.yml backend/src/test/java/cn/bugstack/competitoragent/llm/PromptTemplateServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchPropertiesBindingTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/WorkflowFactoryTest.java
git commit -m "feat(search): centralize search policy and verification rules"
```

### Task 3: 原子化目标选择并显式复用验证期页面快照

**核心目标：** 解决 `CollectionTargetSelector` 的广告高权重绑架、`select -> mark -> refresh` 三段式非原子操作、URL 归一化不一致导致的页面快照复用丢失问题，把最终选源结果收口成单个正式决策对象。

**预估耗时：** 60 分钟

**前置依赖：** Task 2 已完成 `SearchKeywordPolicy` 与 `SearchPolicyResolver`；当前 `SearchCollectionTarget` 已具备 `collectedPage` 字段。

**Files:**

- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

- [ ] **Step 1: 先写目标选择器红灯测试，锁定梯队选择与页面快照复用**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CollectionTargetSelectorTest {

    private final CollectionTargetSelector selector = new CollectionTargetSelector();

    @Test
    void shouldPreferVerifiedBusinessDocumentOverDiscardedMarketingHomepage() {
        SourceCandidate discardedOfficial = SourceCandidate.builder()
                .url("https://www.aliyun.com/product/ecs")
                .title("阿里云 ECS 秒杀")
                .selectionStage("DISCARDED")
                .selectionReason("命中营销噪声")
                .totalScore(0.99)
                .build();
        SourceCandidate verifiedDoc = SourceCandidate.builder()
                .url("https://help.aliyun.com/document_detail/12345.html")
                .title("实例规格说明")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.71)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedDoc.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedDoc)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedDoc.getUrl())
                        .title("实例规格说明")
                        .content("规格说明")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(List.of(discardedOfficial, verifiedDoc), attemptedTargets, 1);

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://help.aliyun.com/document_detail/12345.html",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
    }
}
```

Run: `mvn -pl backend -Dtest=CollectionTargetSelectorTest,SearchExecutionCoordinatorTest,CollectorAgentTest test`

Expected: FAIL，当前选择器仍会先受 `totalScore` 影响，且返回值还不是单个原子决策对象。

- [ ] **Step 2: 新建 `SearchSelectionDecision`，把最终选源结果收口为一个对象**

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索目标选择决策。
 * 统一返回“最终选中目标 + 回填后的候选列表 + 已选来源地址”，
 * 避免协调器继续走 select -> mark -> refresh 三段式脆弱写法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSelectionDecision {

    private List<SearchCollectionTarget> selectedTargets;
    private List<SourceCandidate> updatedCandidates;
    private List<String> sourceUrls;
}
```

- [ ] **Step 3: 修改 `CollectionTargetSelector`，引入梯队排序、URL 归一化与原子返回**

```java
@Component
public class CollectionTargetSelector {

    public SearchSelectionDecision selectTargets(List<SourceCandidate> candidates,
                                                 Map<String, SearchCollectionTarget> attemptedTargets,
                                                 int targetCount) {
        Map<String, SearchCollectionTarget> normalizedAttemptedTargets = normalizeAttemptedTargets(attemptedTargets);
        List<SearchCollectionTarget> selectedTargets = new ArrayList<>();
        Set<String> selectedUrls = new LinkedHashSet<>();

        List<SourceCandidate> rankedCandidates = candidates.stream()
                .sorted(Comparator
                        .comparingInt(this::resolveSelectionTier)
                        .thenComparing(SourceCandidate::getTotalScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        for (SourceCandidate candidate : rankedCandidates) {
            String normalizedUrl = normalizeUrl(candidate == null ? null : candidate.getUrl());
            if (!StringUtils.hasText(normalizedUrl) || !selectedUrls.add(normalizedUrl)) {
                continue;
            }
            SearchCollectionTarget target = normalizedAttemptedTargets.getOrDefault(
                    normalizedUrl,
                    SearchCollectionTarget.builder().candidate(candidate).build());
            selectedTargets.add(target);
            if (selectedTargets.size() >= targetCount) {
                break;
            }
        }

        List<SourceCandidate> updatedCandidates = candidates.stream()
                .map(candidate -> applySelectionResult(candidate, selectedUrls))
                .toList();

        return SearchSelectionDecision.builder()
                .selectedTargets(selectedTargets)
                .updatedCandidates(updatedCandidates)
                .sourceUrls(new ArrayList<>(selectedUrls))
                .build();
    }

    private int resolveSelectionTier(SourceCandidate candidate) {
        if (candidate == null) {
            return 9;
        }
        if (Boolean.TRUE.equals(candidate.getVerified())
                && !"DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return 1;
        }
        if ("CONFIG".equalsIgnoreCase(candidate.getDiscoveryMethod())
                || "MANUAL".equalsIgnoreCase(candidate.getDiscoveryMethod())
                || hasDocLikeDomain(candidate.getDomain())) {
            return 2;
        }
        if ("DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return 4;
        }
        return 3;
    }
}
```

- [ ] **Step 4: 修改 `SearchExecutionCoordinator` 与 `CollectorAgent`，只消费原子决策对象并强制复用 `collectedPage`**

```java
SearchSelectionDecision selectionDecision = collectionTargetSelector.selectTargets(
        allCandidates,
        attemptedTargets,
        targetCount
);
allCandidates = selectionDecision.getUpdatedCandidates();
List<SearchCollectionTarget> selectedTargets = selectionDecision.getSelectedTargets();
markStepSuccess(executionPlan, "SELECT_TARGETS",
        "已选出 " + selectedTargets.size() + " 条正式采集目标");
appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
        "已选出 " + selectedTargets.size() + " 条正式采集目标", circuitBroken, degradationReason,
        progressListener, allCandidates, selectedTargets, null);
```

```java
SourceCollector.CollectedPage page = target.getCollectedPage();
if (!isUsableCollectedPage(page)) {
    page = sourceCollector.collect(url, config.getCompetitorName(), sourceType);
}
```

Implementation note: 这里不再“无条件相信 `target.getCollectedPage() != null` 就一定可用”，而是通过 `isUsableCollectedPage(page)` 再判一次；这样既能复用验证期快照，又不会把空壳页面误当成正式采集结果。

- [ ] **Step 5: 运行目标选择与采集复用测试**

Run: `mvn -pl backend -Dtest=CollectionTargetSelectorTest,SearchExecutionCoordinatorTest,CollectorAgentTest test`

Expected: PASS

- [ ] **Step 6: 提交目标选择器重构**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchSelectionDecision.java backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java
git commit -m "feat(search): atomize target selection and snapshot reuse"
```

### Task 4: 正式化搜索运行态事件与任务回放投影

**核心目标：** 把 `searchAudit`、`selectedTargets`、`sourceUrls` 从“Collector output 里有时存在的大 JSON 片段”升级为正式 runtime / replay 契约，让 SSE、任务详情页和 `/api/task/{taskId}/replay` 共用同一套搜索现场视图。

**预估耗时：** 80 分钟

**前置依赖：** Task 3 已保证 `selectedTargets` 与 `searchAudit` 的结构稳定，`CollectorAgent` 输出中已有 `searchAudit`。

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/search/SearchAuditSnapshotCompatibilityTest.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java`

- [ ] **Step 1: 先定义正式 DTO，并补齐 `SearchAuditSnapshot` 兼容性测试**

```java
package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "搜索进度事件正式 DTO")
public class SearchProgressEventPayload {

    @Schema(description = "事件契约类型", example = "SEARCH_PROGRESS_V1")
    private String contractType;

    @Schema(description = "节点名")
    private String nodeName;

    @Schema(description = "当前搜索进度")
    private SearchProgressSnapshot searchProgress;

    @Schema(description = "搜索轨迹")
    private SearchExecutionTrace searchExecutionTrace;

    @Schema(description = "搜索进度快照历史")
    private List<SearchProgressSnapshot> searchProgressSnapshots;

    @Schema(description = "搜索审计快照")
    private SearchAuditSnapshot searchAudit;

    @Schema(description = "最终选中目标摘要")
    private List<Map<String, Object>> selectedTargets;

    @Schema(description = "当前事件可回指的来源地址")
    private List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索审计快照。
 * Phase 2 起它除了轨迹与候选本身，还要显式携带 `sourceUrls`，
 * 保证 runtime 事件、任务回放和报告审计都能直接回指来源。
 * 这里保持“加法字段全部可选”，让新代码读取历史快照时继续宽容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSnapshot {

    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
    private List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放中的搜索现场快照")
public class SearchReplaySnapshotResponse {

    private String nodeName;
    private Long planVersionId;
    private Integer planVersion;
    private String branchKey;
    private SearchProgressSnapshot latestProgress;
    private SearchAuditSnapshot searchAudit;
    private List<CollectorSelectedTargetSummary> selectedTargets;
    private List<String> sourceUrls;
}
```

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchAuditSnapshotCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldDeserializeHistoricalSnapshotWithoutSourceUrls() throws Exception {
        String historicalJson = """
                {
                  "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"},
                  "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}]
                }
                """;

        SearchAuditSnapshot snapshot = objectMapper.readValue(historicalJson, SearchAuditSnapshot.class);

        assertNotNull(snapshot);
        assertEquals("SELECT_TARGETS", snapshot.getExecutionTrace().getRecoveryCheckpoint());
        assertEquals(1, snapshot.getSelectedTargets().size());
    }

    @Test
    void shouldIgnoreFutureUnknownFieldsWhenReadingSnapshot() throws Exception {
        String futureJson = """
                {
                  "executionTrace":{"recoveryCheckpoint":"VERIFY_TOP_CANDIDATES"},
                  "sourceUrls":["https://docs.notion.so/reference"],
                  "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}],
                  "futureField":{"unexpected":true}
                }
                """;

        SearchAuditSnapshot snapshot = objectMapper.readValue(futureJson, SearchAuditSnapshot.class);

        assertEquals("VERIFY_TOP_CANDIDATES", snapshot.getExecutionTrace().getRecoveryCheckpoint());
        assertTrue(snapshot.getSourceUrls().contains("https://docs.notion.so/reference"));
    }
}
```

- [ ] **Step 2: 修改 `RuntimeEventEmitter` 和 `TaskEventPublisher`，统一走正式搜索事件 DTO**

```java
private void publishSearchProgressEventIfPresent(Long taskId, TaskNode node) {
    if (node.getAgentType() != AgentType.COLLECTOR) {
        return;
    }
    SearchProgressEventPayload payload = buildSearchProgressEventPayload(node);
    if (payload == null) {
        return;
    }
    taskEventPublisher.publishSearchProgressEvent(taskId, node.getNodeName(), payload);
}

private SearchProgressEventPayload buildSearchProgressEventPayload(TaskNode node) {
    JsonNode output = readJson(node.getOutputData());
    if (output == null) {
        return SearchProgressEventPayload.builder()
                .contractType("SEARCH_PROGRESS_V1")
                .nodeName(node.getNodeName())
                .searchProgress(SearchProgressSnapshot.builder()
                        .status(node.getStatus() == TaskNodeStatus.SUCCESS ? "SUCCESS" : "FAILED")
                        .currentStep(node.getStatus() == TaskNodeStatus.SUCCESS ? "COLLECT_PAGES" : "FAILED")
                        .message(defaultIfBlank(node.getErrorMessage(),
                                node.getStatus() == TaskNodeStatus.SUCCESS ? "采集节点已完成，使用最小事件留痕兜底。" : "采集节点执行失败，请查看节点详情。"))
                        .build())
                .searchExecutionTrace(null)
                .searchProgressSnapshots(List.of())
                .searchAudit(null)
                .selectedTargets(List.of())
                .sourceUrls(List.of())
                .build();
    }
    return SearchProgressEventPayload.builder()
            .contractType("SEARCH_PROGRESS_V1")
            .nodeName(node.getNodeName())
            .searchProgress(convertValue(output.get("searchProgress"), SearchProgressSnapshot.class))
            .searchExecutionTrace(convertValue(output.get("searchExecutionTrace"), SearchExecutionTrace.class))
            .searchProgressSnapshots(convertList(output.get("searchProgressSnapshots"), SearchProgressSnapshot.class))
            .searchAudit(convertValue(output.get("searchAudit"), SearchAuditSnapshot.class))
            .selectedTargets(convertList(output.get("selectedTargets"), Map.class))
            .sourceUrls(convertStringList(output.get("sourceUrls")))
            .build();
}
```

```java
public TaskStreamEvent publishSearchProgressEvent(Long taskId,
                                                  String nodeName,
                                                  SearchProgressEventPayload payload) {
    Map<String, Object> mapPayload = objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
    });
    return publish(TaskEventType.SEARCH_PROGRESS, taskId, nodeName, mapPayload);
}
```

- [ ] **Step 3: 扩展任务回放正式响应，追加 `searchReplays` 视图**

```java
@Schema(description = "搜索链路回放快照")
private List<SearchReplaySnapshotResponse> searchReplays;
```

```java
private List<SearchReplaySnapshotResponse> buildSearchReplays(List<TaskNode> taskNodes,
                                                              Map<Long, TaskPlan> taskPlanMap) {
    List<SearchReplaySnapshotResponse> searchReplays = new ArrayList<>();
    for (TaskNode taskNode : taskNodes) {
        if (taskNode.getAgentType() != AgentType.COLLECTOR || taskNode.getOutputData() == null || taskNode.getOutputData().isBlank()) {
            continue;
        }
        JsonNode output = readJson(taskNode.getOutputData());
        if (output == null || !output.hasNonNull("searchAudit")) {
            continue;
        }
        List<CollectorSelectedTargetSummary> selectedTargets = convertList(
                output.get("selectedTargets"),
                CollectorSelectedTargetSummary.class
        );
        searchReplays.add(SearchReplaySnapshotResponse.builder()
                .nodeName(taskNode.getNodeName())
                .planVersionId(taskNode.getPlanVersionId())
                .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
                .branchKey(taskNode.getBranchKey())
                .latestProgress(convertValue(output.get("searchProgress"), SearchProgressSnapshot.class))
                .searchAudit(convertValue(output.get("searchAudit"), SearchAuditSnapshot.class))
                .selectedTargets(selectedTargets)
                .sourceUrls(normalizeSourceUrls(convertStringList(output.get("sourceUrls"))))
                .build());
    }
    return searchReplays;
}
```

Implementation note: `TaskReplayProjectionService` 仍然保留 `timeline` 作为任务级回放主轴，但 `searchReplays` 负责承载“Collector 内部搜索现场”；这能满足 `CollectorAgent.md` 里“不要只依赖任务级游标回放搜索现场”的要求，同时不必额外新增 controller。

Implementation note: `SearchExecutionCoordinator` 构建 `SearchAuditSnapshot` 时，要把 `selectedTargets[*].candidate.url` 去重后回填到 `sourceUrls`，不要再让前端或 `ReportService` 从大对象里反推来源地址。

Implementation note: 兼容性测试只保障“新代码读取历史/未来快照”宽容；旧二进制读取新增 `sourceUrls` 字段仍属于发布约束，因此 backend 需要采用同版本发布或 reader-first 灰度，不允许旧实例长时间混读新 `searchAudit` payload。

- [ ] **Step 4: 把 `searchAudit` 注入 `CollectorNodeInsightResponse` 与报告聚合层**

```java
@Schema(description = "搜索审计快照")
private SearchAuditSnapshot searchAudit;
```

```java
return CollectorNodeInsightResponse.builder()
        .competitorName(config.path("competitorName").asText(null))
        .sourceType(config.path("sourceType").asText(null))
        .sourceScope(convertStringList(config.get("sourceScope")))
        .competitorUrls(convertStringList(config.get("competitorUrls")))
        .searchQueries(convertStringList(output == null ? config.get("searchQueries") : output.get("searchQueries")))
        .browserSearchEnabled(config.path("browserSearchEnabled").asBoolean(false))
        .verifyResultPage(config.path("verifyResultPage").asBoolean(true))
        .minVerifiedCandidates(config.path("minVerifiedCandidates").isNumber() ? config.path("minVerifiedCandidates").asInt() : null)
        .preferredDomains(convertStringList(config.get("preferredDomains")))
        .candidateCount(sourceCandidates.size())
        .selectedCount(selectedTargets.size())
        .successCollected(output == null ? 0 : output.path("successCollected").asInt(0))
        .totalCollected(output == null ? 0 : output.path("totalCollected").asInt(0))
        .searchProgress(convertValue(output == null ? null : output.get("searchProgress"), SearchProgressSnapshot.class))
        .searchExecutionPlan(convertValue(
                output != null && output.has("searchExecutionPlan") ? output.get("searchExecutionPlan") : config.get("searchExecutionPlan"),
                SearchExecutionPlan.class))
        .searchExecutionTrace(convertValue(output == null ? null : output.get("searchExecutionTrace"), SearchExecutionTrace.class))
        .searchProgressSnapshots(convertList(output == null ? null : output.get("searchProgressSnapshots"), SearchProgressSnapshot.class))
        .searchAudit(convertValue(output == null ? null : output.get("searchAudit"), SearchAuditSnapshot.class))
        .sourceCandidates(sourceCandidates)
        .selectedTargets(selectedTargets)
        .build();
```

- [ ] **Step 5: 运行 runtime / replay / insight / compatibility 测试**

Run: `mvn -pl backend -Dtest=RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskEventStreamControllerTest,SearchAuditSnapshotCompatibilityTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest test`

Expected: PASS

- [ ] **Step 6: 提交正式 runtime / replay 契约**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchProgressEventPayload.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/SearchReplaySnapshotResponse.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchAuditSnapshot.java backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/CollectorNodeInsightResponse.java backend/src/main/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitter.java backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventPublisher.java backend/src/main/java/cn/bugstack/competitoragent/event/TaskEventReplayService.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskReplayProjectionService.java backend/src/main/java/cn/bugstack/competitoragent/model/dto/TaskReplayResponse.java backend/src/main/java/cn/bugstack/competitoragent/task/assembler/TaskNodeViewAssembler.java backend/src/main/java/cn/bugstack/competitoragent/report/ReportService.java backend/src/test/java/cn/bugstack/competitoragent/workflow/runtime/RuntimeEventEmitterTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskReplayProjectionServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/event/TaskEventReplayServiceTest.java
git commit -m "feat(search): formalize runtime event and replay contracts"
```

### Task 5: 裁剪共享上下文、固化搜索检查点并让恢复策略识别正式搜索现场

**核心目标：** 先核验下游 Agent 对 Collector 共享输出的真实依赖，再把 Collector 整包 output 从共享上下文与 Redis 中裁剪为稳定 `SearchSharedProjection`；同时在 rerun / resume / interrupted restart 时优先保留正式 `searchAuditCheckpoint` 与高价值搜索现场。

**预估耗时：** 65 分钟

**前置依赖：** Task 4 已冻结 `searchAudit`、`selectedTargets`、`sourceUrls` 的正式形状。

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java`

- [ ] **Step 1: 先加共享投影、下游消费矩阵与恢复红灯测试**

```java
@Test
void shouldCacheStableCollectorProjectionInsteadOfWholeCollectorOutput() {
    cacheService.cacheNodeOutput(19L, "collect_sources_web", """
            {
              "searchExecutionTrace":{"fallbackDecision":"USE_BROWSER_SUPPLEMENT"},
              "searchAudit":{"executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"}},
              "selectedTargets":[{"url":"https://docs.example.com"}],
              "sourceUrls":["https://docs.example.com"],
              "results":[{"url":"https://docs.example.com","fullContent":"very large body"}]
            }
            """);

    when(hashOperations.entries("competitor-agent:task:runtime:19"))
            .thenReturn(Map.of("collect_sources_web", "{\"sourceUrls\":[\"https://docs.example.com\"]}"));

    Map<String, String> cachedOutputs = cacheService.getCachedNodeOutputs(19L);

    assertEquals("{\"sourceUrls\":[\"https://docs.example.com\"]}", cachedOutputs.get("collect_sources_web"));
}
```

```java
@Test
void shouldKeepCollectorSearchAuditCheckpointWhenResettingInterruptedNodes() {
    TaskNode runningCollector = TaskNode.builder()
            .taskId(2L)
            .nodeName("collect_sources_docs")
            .displayName("collect_sources_docs")
            .agentType(AgentType.COLLECTOR)
            .status(TaskNodeStatus.RUNNING)
            .nodeConfig("""
                    {
                      "searchAuditCheckpoint":{
                        "executionTrace":{"recoveryCheckpoint":"VERIFY_TOP_CANDIDATES"}
                      }
                    }
                    """)
            .outputData("""
                    {
                      "searchAudit":{
                        "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS","degraded":true}
                      }
                    }
                    """)
            .build();

    recoveryPolicy.resetInterruptedNodes(List.of(runningCollector));

    assertEquals(TaskNodeStatus.PENDING, runningCollector.getStatus());
    assertTrue(runningCollector.getNodeConfig().contains("searchAuditCheckpoint"));
}
```

```java
@Test
void shouldSeedTrimmedCollectorProjectionInsteadOfWholeCollectorOutput() throws Exception {
    Long taskId = 505L;
    AnalysisTask task = AnalysisTask.builder()
            .id(taskId)
            .status(AnalysisTaskStatus.PENDING)
            .build();

    TaskNode completedCollector = TaskNode.builder()
            .id(41L)
            .taskId(taskId)
            .nodeName("collect_a")
            .displayName("collect_a")
            .agentType(AgentType.COLLECTOR)
            .dependsOn("[]")
            .required(true)
            .retryable(false)
            .maxRetries(0)
            .status(TaskNodeStatus.SUCCESS)
            .outputData("""
                    {
                      "sourceUrls":["https://docs.example.com/reference"],
                      "issueFlags":["SOURCE_URLS_BACKFILLED"],
                      "selectedTargets":[{"url":"https://docs.example.com/reference"}],
                      "searchExecutionTrace":{"fallbackDecision":"USE_BROWSER_SUPPLEMENT","degradationReason":"SEARCH_TIMEOUT_AFTER_SUPPLEMENT"},
                      "results":[{"url":"https://docs.example.com/reference","fullContent":"very large body"}]
                    }
                    """)
            .executionOrder(0)
            .build();

    TaskNode analyzer = TaskNode.builder()
            .id(42L)
            .taskId(taskId)
            .nodeName("analyze_competitors")
            .displayName("analyze_competitors")
            .agentType(AgentType.ANALYZER)
            .dependsOn("[\"collect_a\"]")
            .required(true)
            .retryable(false)
            .maxRetries(0)
            .status(TaskNodeStatus.PENDING)
            .executionOrder(1)
            .build();

    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
            .thenReturn(List.of(completedCollector, analyzer));
    when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    DagExecutor executor = newDagExecutor(
            nodeRepository,
            taskRepository,
            List.of(new AlwaysSuccessAnalyzerAgent()),
            mock(TaskSnapshotCacheService.class),
            allowingNodeLockService()
    );

    AgentContext context = AgentContext.builder().taskId(taskId).taskName("projection-test").build();
    executor.execute(taskId, context);

    JsonNode projection = new ObjectMapper().readTree(context.getSharedOutput("collect_a"));
    assertTrue(projection.has("sourceUrls"));
    assertTrue(projection.has("selectedUrls"));
    assertFalse(projection.has("results"));
    assertFalse(projection.has("fullContent"));
}
```

Run: `mvn -pl backend -Dtest=DagExecutorTest,TaskSnapshotCacheServiceTest,NodeExecutionRecoveryPolicyTest,TaskRuntimeCommandAppServiceTest test`

Expected: FAIL，当前缓存仍按整包 output 写入，`DagExecutor` 也还会把原始 Collector output 直接塞进共享上下文，`resetInterruptedNodes` 也不知道 Collector 的搜索检查点语义。

- [ ] **Step 2: 先核验下游 Agent 对 Collector 输出的真实消费字段**

执行顺序固定如下：

1. 逐个核验 `SchemaExtractorAgent`、`CompetitorAnalysisAgent`、`QualityReviewAgent`、`ReportWriterAgent`、`MemoryFusionService` 当前到底消费了哪些 Collector 产物。
2. 以“直接读 `sharedState.collect_sources_*` 的字段清单”形成裁剪基线，并把结果写进实现 PR 描述或任务注释。
3. 当前代码基线若仍是“抽取/分析/质检主要走 `EvidenceSourceRepository`、`CompetitorKnowledgeRepository` 与 `taskRagContext`，不直接依赖 Collector `fullContent`”，则允许启用裁剪。
4. 如果执行时发现任一下游节点新增依赖 `fullContent`，必须二选一后再继续：
   - 把 `fullContent` 明确保留进 `SearchSharedProjection`
   - 或让该下游改走知识检索层 / 证据仓储层，不再直接吃 Collector 大 JSON

- [ ] **Step 3: 新建稳定共享投影对象**

```java
package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Collector 对下游共享的稳定事实投影。
 * 它只保留 sourceUrls、issueFlags、selectedTargets 与搜索轨迹摘要，
 * 不把大体量 results / fullContent / progressHistory 再次塞回共享上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchSharedProjection {

    private List<String> sourceUrls;
    private List<String> issueFlags;
    private List<String> selectedUrls;
    private String fallbackDecision;
    private String degradationReason;

    public static SearchSharedProjection fromCollectorOutput(ObjectMapper objectMapper, String rawOutput) {
        try {
            JsonNode output = objectMapper.readTree(rawOutput);
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            output.path("sourceUrls").forEach(node -> sourceUrls.add(node.asText()));
            LinkedHashSet<String> selectedUrls = new LinkedHashSet<>();
            output.path("selectedTargets").forEach(node -> {
                if (node.hasNonNull("url")) {
                    selectedUrls.add(node.path("url").asText());
                }
            });
            return new SearchSharedProjection(
                    new ArrayList<>(sourceUrls),
                    objectMapper.convertValue(output.path("issueFlags"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    }),
                    new ArrayList<>(selectedUrls),
                    output.path("searchExecutionTrace").path("fallbackDecision").asText(null),
                    output.path("searchExecutionTrace").path("degradationReason").asText(null)
            );
        } catch (Exception e) {
            return new SearchSharedProjection(List.of(), List.of("PROJECTION_PARSE_FAILED"), List.of(), null, null);
        }
    }
}
```

- [ ] **Step 4: 修改 `DagExecutor` 与 `TaskSnapshotCacheService`，Collector 只缓存共享投影**

```java
private void seedSharedOutputs(AgentContext context, List<TaskNode> nodes) {
    taskSnapshotCacheService.getCachedNodeOutputs(context.getTaskId())
            .forEach(context::putSharedOutput);
    for (TaskNode node : nodes) {
        if (node.getStatus() == TaskNodeStatus.SUCCESS
                && node.getOutputData() != null
                && !node.getOutputData().isBlank()) {
            context.putSharedOutput(node.getNodeName(), toSharedProjection(node));
        }
    }
}

private String toSharedProjection(TaskNode node) {
    if (node.getAgentType() == AgentType.COLLECTOR) {
        try {
            return objectMapper.writeValueAsString(
                    SearchSharedProjection.fromCollectorOutput(objectMapper, node.getOutputData())
            );
        } catch (Exception e) {
            return node.getOutputData();
        }
    }
    return node.getOutputData();
}
```

```java
if (result.getStatus() == TaskNodeStatus.SUCCESS) {
    node.setStatus(TaskNodeStatus.SUCCESS);
    node.setOutputData(result.getOutputData());
    TaskNode savedNode = nodeRepository.save(node);
    String sharedProjection = toSharedProjection(savedNode);
    sharedContext.putSharedOutput(savedNode.getNodeName(), sharedProjection);
    taskSnapshotCacheService.cacheNodeOutput(taskId, node.getNodeName(), sharedProjection);
    return savedNode;
}
```

- [ ] **Step 5: 修改恢复策略与 rerun/resume，优先保留正式搜索检查点**

```java
public boolean resetInterruptedNodes(List<TaskNode> nodes) {
    if (nodes == null || nodes.isEmpty()) {
        return false;
    }
    boolean changed = false;
    for (TaskNode node : nodes) {
        if (node.getStatus() == TaskNodeStatus.RUNNING || node.getStatus() == TaskNodeStatus.DISPATCHED) {
            preserveCollectorSearchCheckpoint(node);
            resetNodeForInterruptedRestart(node);
            changed = true;
            continue;
        }
        if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.READY) {
            preserveCollectorSearchCheckpoint(node);
            node.setControlState(TaskNodeControlState.NONE);
            node.setInputData(null);
            node.setStartedAt(null);
            node.setCompletedAt(null);
            node.setRetryCount(0);
            node.setFailureCategory(null);
            node.setLastAttemptAt(null);
            node.setNextRetryAt(null);
            changed = true;
        }
    }
    return changed;
}

private void preserveCollectorSearchCheckpoint(TaskNode node) {
    if (node == null || node.getAgentType() != AgentType.COLLECTOR || node.getOutputData() == null) {
        return;
    }
    try {
        JsonNode output = objectMapper.readTree(node.getOutputData());
        JsonNode auditNode = output.get("searchAudit");
        if (auditNode == null || auditNode.isNull()) {
            return;
        }
        JsonNode configNode = objectMapper.readTree(node.getNodeConfig());
        if (configNode.isObject()) {
            ((ObjectNode) configNode).set("searchAuditCheckpoint", auditNode);
            node.setNodeConfig(objectMapper.writeValueAsString(configNode));
        }
    } catch (Exception ignored) {
    }
}
```

Implementation note: `TaskRuntimeCommandAppService.reuseSearchCheckpointIfPresent(node)` 仍然保留，但它从“唯一保存检查点的地方”降级为“命令层兜底”；正式规则应内聚到 `NodeExecutionRecoveryPolicy`，避免依赖调用顺序。

- [ ] **Step 6: 运行恢复与缓存测试**

Run: `mvn -pl backend -Dtest=DagExecutorTest,NodeExecutionRecoveryPolicyTest,TaskSnapshotCacheServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest test`

Expected: PASS

- [ ] **Step 7: 提交共享投影与恢复语义收口**

```bash
git add backend/src/main/java/cn/bugstack/competitoragent/search/SearchSharedProjection.java backend/src/main/java/cn/bugstack/competitoragent/workflow/DagExecutor.java backend/src/main/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheService.java backend/src/main/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicy.java backend/src/main/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppService.java backend/src/test/java/cn/bugstack/competitoragent/workflow/DagExecutorTest.java backend/src/test/java/cn/bugstack/competitoragent/workflow/NodeExecutionRecoveryPolicyTest.java backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java backend/src/test/java/cn/bugstack/competitoragent/task/command/TaskRuntimeCommandAppServiceTest.java
git commit -m "feat(search): preserve checkpoints and trim shared collector state"
```

### Task 6: 补齐搜索链路端到端集成烟雾测试

**核心目标：** 扩展现有 `Phase2WorkflowIntegrationTest`，把“创建任务 -> Collector 执行 -> `searchAudit` 落 output -> SSE 推送 `SEARCH_PROGRESS_V1` -> replay 返回 `searchReplays` -> rerun / resume 保留 `searchAuditCheckpoint`”固化成单条真实回归链路，避免只靠单测验证契约拼接。

**预估耗时：** 50 分钟

**前置依赖：** Task 4 已冻结正式 runtime / replay DTO，Task 5 已保证共享投影与恢复策略稳定。

**Files:**

- Modify: `backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java`

- [ ] **Step 1: 扩展 Collector 集成测试桩输出，显式带出正式搜索审计字段**

```java
String output = """
        {
          "competitor": "%s",
          "sourceType": "%s",
          "sourceUrls": ["%s"],
          "searchAudit": {
            "executionTrace": {
              "traceVersion": "v1",
              "recoveryCheckpoint": "SELECT_TARGETS",
              "fallbackDecision": "USE_PLANNED_CANDIDATES"
            },
            "selectedTargets": [
              {"candidate":{"url":"%s"}}
            ],
            "sourceUrls": ["%s"]
          },
          "selectedTargets": [{"url":"%s","title":"%s","verified":true}],
          "searchQueries": ["%s documentation", "%s pricing"],
          "successCollected": 1,
          "totalCollected": 1
        }
        """.formatted(
                competitorName,
                sourceType,
                url,
                url,
                url,
                url,
                "PRICING".equalsIgnoreCase(sourceType) ? "Notion Pricing" : "Notion AI Help",
                competitorName,
                competitorName
        );
```

- [ ] **Step 2: 在现有 Phase 2 闭环用例里追加 Collector output 与 SSE 正式契约断言**

```java
TaskNode collectorNode = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).stream()
        .filter(node -> node.getNodeName().startsWith("collect_sources_"))
        .findFirst()
        .orElseThrow();
JsonNode collectorOutput = objectMapper.readTree(collectorNode.getOutputData());

assertTrue(collectorOutput.hasNonNull("searchAudit"));
assertEquals("SELECT_TARGETS",
        collectorOutput.path("searchAudit").path("executionTrace").path("recoveryCheckpoint").asText());
assertTrue(collectorOutput.path("searchAudit").path("sourceUrls").isArray());

TaskStreamEvent searchEvent = recentEvents.stream()
        .filter(event -> event.getEventType() == TaskEventType.SEARCH_PROGRESS)
        .findFirst()
        .orElseThrow();
assertEquals("SEARCH_PROGRESS_V1", searchEvent.getPayload().get("contractType"));
assertTrue(searchEvent.getPayload().containsKey("searchAudit"));
assertTrue(searchEvent.getPayload().containsKey("selectedTargets"));
assertTrue(searchEvent.getPayload().containsKey("sourceUrls"));
```

- [ ] **Step 3: 追加 replay 与 rerun / resume 检查点保留断言**

```java
ResponseEntity<ApiResponse> replayEntity = restTemplate.getForEntity(
        "http://localhost:" + port + "/api/task/" + taskId + "/replay",
        ApiResponse.class
);
assertEquals(200, replayEntity.getStatusCode().value());
Map<?, ?> replayPayload = (Map<?, ?>) replayEntity.getBody().getData();
List<?> searchReplays = (List<?>) replayPayload.get("searchReplays");
assertFalse(searchReplays.isEmpty());

String collectorNodeName = collectorNode.getNodeName();
assertTrue(collectorNode.getNodeConfig().contains("searchAuditCheckpoint"));

restTemplate.postForEntity(taskUrl("/" + taskId + "/nodes/" + collectorNodeName + "/rerun"), null, ApiResponse.class);
consumeLatestTaskExecutionRequested(taskId);
waitForTaskDetailStatus(taskId, AnalysisTaskStatus.SUCCESS);

TaskNode rerunCollector = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).stream()
        .filter(node -> collectorNodeName.equals(node.getNodeName()))
        .findFirst()
        .orElseThrow();
assertTrue(rerunCollector.getNodeConfig().contains("searchAuditCheckpoint"));
```

- [ ] **Step 4: 运行 Phase 2 端到端烟雾测试**

Run: `mvn -pl backend -Dtest=Phase2WorkflowIntegrationTest test`

Expected: PASS

- [ ] **Step 5: 提交端到端搜索回归烟雾测试**

```bash
git add backend/src/test/java/cn/bugstack/competitoragent/integration/Phase2WorkflowIntegrationTest.java
git commit -m "test(search): extend phase2 integration smoke for audit and replay"
```

### Task 7: 让任务详情页和回放面板切换到正式搜索契约

**核心目标：** 前端不再从半结构化 `outputData` 或“是否有 collectorInsight 某个字段”去猜搜索现场，而是统一消费 `collectorInsight.searchAudit`、`SEARCH_PROGRESS_V1`、`TaskReplayResponse.searchReplays`。

**预估耗时：** 55 分钟

**前置依赖：** Task 4 已冻结搜索 runtime / replay DTO，Task 5 已保证后台恢复链路会持续产出稳定搜索现场。

**Files:**

- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/utils/taskNodeInsights.ts`
- Modify: `frontend/src/utils/taskNodeInsights.test.ts`
- Modify: `frontend/src/utils/taskEventReducer.ts`
- Modify: `frontend/src/utils/taskEventReducer.test.ts`
- Modify: `frontend/src/pages/TaskDetailPage.tsx`
- Modify: `frontend/src/pages/TaskDetailPage.test.tsx`
- Modify: `frontend/src/components/task-detail/SearchActivityPanel.tsx`
- Modify: `frontend/src/components/task-detail/NodeTraceDrawer.tsx`
- Modify: `frontend/src/components/task-detail/NodeTraceDrawer.test.tsx`
- Modify: `frontend/src/components/task-detail/TaskReplayTimeline.tsx`
- Modify: `frontend/src/components/task-detail/TaskReplayTimeline.test.tsx`

- [ ] **Step 1: 扩展前端类型层，承接正式 `searchAudit` / `searchReplays`**

```ts
export interface SearchAuditSnapshotInfo {
  executionTrace?: SearchExecutionTraceInfo | null
  executionPlan?: SearchExecutionPlanInfo | null
  latestProgress?: SearchProgressInfo | null
  progressHistory?: SearchProgressInfo[] | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: CollectorSelectedTargetSummary[]
  sourceUrls?: string[]
}

export interface CollectorNodeInsightData {
  competitorName: string
  sourceType: string
  sourceTypeLabel?: string | null
  sourceScope: string[]
  competitorUrls: string[]
  searchMode?: string
  searchModeLabel?: string | null
  searchQueries: string[]
  browserSearchEnabled: boolean
  verifyResultPage: boolean
  minVerifiedCandidates: number | null
  preferredDomains: string[]
  candidateCount: number
  selectedCount: number
  successCollected: number
  totalCollected: number
  discoveryNotes?: string | null
  searchProgress: SearchProgressInfo | null
  searchExecutionPlan: SearchExecutionPlanInfo | null
  searchExecutionTrace?: SearchExecutionTraceInfo | null
  searchProgressSnapshots?: SearchProgressInfo[] | null
  searchAudit?: SearchAuditSnapshotInfo | null
  sourceCandidates: SourceCandidateInfo[]
  selectedTargets: CollectorSelectedTargetSummary[]
}

export interface SearchReplaySnapshotInfo {
  nodeName: string
  planVersionId?: number | null
  planVersion?: number | null
  branchKey?: string | null
  latestProgress?: SearchProgressInfo | null
  searchAudit: SearchAuditSnapshotInfo | null
  selectedTargets: CollectorSelectedTargetSummary[]
  sourceUrls: string[]
}

export interface TaskReplayResponse {
  taskId: number
  currentPlanVersionId?: number | null
  timeline: ReplayTimelineEvent[]
  nodeSummaries: ReplayNodeSummary[]
  recoveryAdvice?: TaskRecoveryAdvice | null
  recoveryCheckpoints: RecoveryCheckpointInfo[]
  planVersions: ReplayPlanVersionSummary[]
  searchReplays: SearchReplaySnapshotInfo[]
  integrationEntryPoints: ReplayIntegrationEntryPoint[]
  sourceUrls: string[]
}
```

- [ ] **Step 2: 修改 reducer 与 insight 归一化逻辑，优先消费正式搜索契约**

```ts
const collectorInsight = ensureCollectorInsight(node)
const nextSearchAudit = payload.searchAudit ?? collectorInsight.searchAudit ?? null
const nextSelectedTargets = Array.isArray(payload.selectedTargets)
  ? (payload.selectedTargets as CollectorNodeInsightData['selectedTargets'])
  : collectorInsight.selectedTargets

return {
  ...node,
  collectorInsight: {
    ...collectorInsight,
    searchProgress: payload.searchProgress ?? collectorInsight.searchProgress ?? null,
    searchExecutionTrace: payload.searchExecutionTrace ?? collectorInsight.searchExecutionTrace ?? null,
    searchProgressSnapshots: payload.searchProgressSnapshots ?? collectorInsight.searchProgressSnapshots ?? [],
    searchAudit: nextSearchAudit,
    selectedTargets: nextSelectedTargets,
  },
}
```

```ts
return {
  competitorName: insight.competitorName || summary.competitorName || '',
  sourceType: insight.sourceType || summary.sourceType || 'OFFICIAL',
  sourceScope: normalizeArray(insight.sourceScope, summary.sourceScope),
  competitorUrls: normalizeArray(insight.competitorUrls, summary.competitorUrls),
  searchQueries: normalizeArray(insight.searchQueries, summary.searchQueries),
  browserSearchEnabled: Boolean(insight.browserSearchEnabled ?? summary.browserSearchEnabled),
  verifyResultPage: Boolean(insight.verifyResultPage ?? summary.verifyResultPage),
  minVerifiedCandidates: coalesceNumber(insight.minVerifiedCandidates, summary.minVerifiedCandidates),
  preferredDomains: normalizeArray(insight.preferredDomains, summary.preferredDomains),
  candidateCount: coalesceNumber(insight.candidateCount, 0),
  selectedCount: coalesceNumber(insight.selectedCount, 0),
  successCollected: coalesceNumber(insight.successCollected, 0),
  totalCollected: coalesceNumber(insight.totalCollected, 0),
  discoveryNotes: insight.discoveryNotes ?? summary.discoveryNotes ?? null,
  searchProgress: insight.searchProgress ?? null,
  searchExecutionPlan: insight.searchExecutionPlan ?? null,
  searchExecutionTrace: insight.searchExecutionTrace ?? null,
  searchProgressSnapshots: normalizeRecordArray<SearchProgressInfo>(insight.searchProgressSnapshots),
  searchAudit: insight.searchAudit ?? null,
  sourceCandidates: normalizeRecordArray<SourceCandidateInfo>(insight.sourceCandidates),
  selectedTargets: normalizeRecordArray<CollectorSelectedTargetSummary>(insight.selectedTargets),
}
```

- [ ] **Step 3: 修改任务详情与节点追踪组件，只从正式字段读取搜索现场**

```tsx
const selectedCollectorReplay = useMemo(
  () => taskReplay?.searchReplays.find((item) => item.nodeName === selectedNode?.nodeName) ?? null,
  [selectedNode?.nodeName, taskReplay],
)

const selectedSearchAudit = useMemo(
  () => selectedCollectorInsight?.searchAudit ?? selectedCollectorReplay?.searchAudit ?? null,
  [selectedCollectorInsight, selectedCollectorReplay],
)
```

```tsx
{selectedSearchAudit?.executionTrace?.recoveryCheckpoint ? (
  <Descriptions.Item label="恢复检查点">
    {selectedSearchAudit.executionTrace.recoveryCheckpoint}
  </Descriptions.Item>
) : null}
{selectedSearchAudit?.sourceUrls?.length ? (
  <Descriptions.Item label="审计来源">
    {selectedSearchAudit.sourceUrls.join('\n')}
  </Descriptions.Item>
) : null}
```

```tsx
{replay.searchReplays.length > 0 ? (
  <Card size="small" title="搜索现场回放">
    {replay.searchReplays.slice(0, 3).map((item) => (
      <Paragraph key={item.nodeName}>
        {`${item.nodeName} · 检查点 ${item.searchAudit?.executionTrace?.recoveryCheckpoint ?? 'UNKNOWN'} · 来源 ${item.sourceUrls.length} 条`}
      </Paragraph>
    ))}
  </Card>
) : null}
```

- [ ] **Step 4: 运行前端详情与回放测试**

Run: `npm --prefix frontend test -- src/utils/taskNodeInsights.test.ts src/utils/taskEventReducer.test.ts src/components/task-detail/NodeTraceDrawer.test.tsx src/components/task-detail/TaskReplayTimeline.test.tsx src/pages/TaskDetailPage.test.tsx`

Expected: PASS

- [ ] **Step 5: 提交前端正式搜索契约切换**

```bash
git add frontend/src/types/index.ts frontend/src/utils/taskNodeInsights.ts frontend/src/utils/taskNodeInsights.test.ts frontend/src/utils/taskEventReducer.ts frontend/src/utils/taskEventReducer.test.ts frontend/src/pages/TaskDetailPage.tsx frontend/src/pages/TaskDetailPage.test.tsx frontend/src/components/task-detail/SearchActivityPanel.tsx frontend/src/components/task-detail/NodeTraceDrawer.tsx frontend/src/components/task-detail/NodeTraceDrawer.test.tsx frontend/src/components/task-detail/TaskReplayTimeline.tsx frontend/src/components/task-detail/TaskReplayTimeline.test.tsx
git commit -m "feat(search-ui): consume formal search audit and replay contracts"
```

---

## Verification

- Backend targeted: `mvn -pl backend -Dtest=SearchAndCollectionGoldenMasterTest,CandidateVerifierTest,CollectionTargetSelectorTest,SearchExecutionCoordinatorTest,CollectorAgentTest,PromptTemplateServiceTest,SearchPropertiesBindingTest,RuntimeEventEmitterTest,TaskReplayProjectionServiceTest,TaskEventReplayServiceTest,TaskEventStreamControllerTest,SearchAuditSnapshotCompatibilityTest,DagExecutorTest,NodeExecutionRecoveryPolicyTest,TaskSnapshotCacheServiceTest,TaskRuntimeCommandAppServiceTest,AnalysisTaskServiceTest test`
- Backend integration smoke: `mvn -pl backend -Dtest=Phase2WorkflowIntegrationTest test`
- Backend full module: `mvn -pl backend test`
- Frontend targeted: `npm --prefix frontend test -- src/utils/taskNodeInsights.test.ts src/utils/taskEventReducer.test.ts src/components/task-detail/NodeTraceDrawer.test.tsx src/components/task-detail/TaskReplayTimeline.test.tsx src/pages/TaskDetailPage.test.tsx`
- Frontend full verification: `npm --prefix frontend run verify`
- Manual API smoke:
  1. `POST /api/task/preview`，确认返回仍然是 `TaskPlanPreviewResponse`，没有混入任何 `searchAudit`、`selectedTargets` 或运行态字段。
  2. 创建包含 `COLLECTOR` 节点的任务，确认节点运行中 SSE `SEARCH_PROGRESS` 事件带有 `SEARCH_PROGRESS_V1`、`searchAudit`、`selectedTargets`、`sourceUrls`。
  3. `GET /api/task/{taskId}/replay`，确认 `searchReplays[*].searchAudit.executionTrace.recoveryCheckpoint`、`searchReplays[*].selectedTargets`、`searchReplays[*].sourceUrls` 可用。
  4. 从 `collect_sources_*` 节点执行 `rerun` / `resume`，确认 `nodeConfig.searchAuditCheckpoint` 被保留，且 `selectedTargets` 与 `searchExecutionTrace.fallbackDecision` 没有丢失。
  5. 打开任务详情页 `NodeTraceDrawer`，确认“恢复检查点 / 审计来源 / 选中目标 / 降级原因”都来自正式字段，不再需要人工展开原始 `outputData` 才能定位问题。

## Rollback

- 后端回滚路径：
  1. 若 `SearchProgressEventPayload` 或 `TaskReplayResponse.searchReplays` 与前端联调存在问题，先保留新增 DTO 类，但让 `RuntimeEventEmitter` 回退到只发 `searchProgress/searchExecutionTrace/searchProgressSnapshots` 的旧形状。
  2. 若 `SearchSharedProjection` 导致下游依赖缺字段，回退 `DagExecutor.toSharedProjection(node)` 到旧的 `node.getOutputData()`，同时保留 `searchAuditCheckpoint` 保留逻辑不动。
  3. 若 `SearchPolicyResolver` 造成线上 fallback 顺序异常，回退 `CollectorPlanTemplateFactory` 与 `SearchExecutionCoordinator` 到当前内置顺序，但不要删除新类，以免后续再次分叉。
  4. 若灰度期间发现旧实例无法读取新增 `searchAudit.sourceUrls` 字段，先停止新字段写入或回退到旧快照形状，并采用 backend 同版本发布，不要让旧新实例长时间混读同一批 `searchAudit` 数据。
- 前端回滚路径：
  1. 若 `searchAudit` 或 `searchReplays` 尚未完全可用，`taskEventReducer` 和 `TaskDetailPage` 继续优先读正式字段，但保留从 `collectorInsight.searchExecutionTrace` 和 `outputData` 做只读 fallback 的兼容分支。
  2. `TaskReplayTimeline` 对 `searchReplays` 采取“字段缺失则不渲染”策略，允许后端灰度发布。

## Phase 3 Handoff

第三阶段 `2.1.3 提取结构化` 必须直接复用本阶段产出的以下边界：

1. `CollectorNodeInsightResponse.searchAudit`：抽取阶段如果需要理解采集质量、降级路径、选源理由，只能读取正式搜索审计对象，不能重新从 `outputData` 解析散乱字段。
2. `TaskReplayResponse.searchReplays`：后续报告审计或回放增强必须在这个正式回放视图上扩展，而不是为搜索链路再单独开一套半结构化查询接口。
3. `SearchSharedProjection`：下游抽取、分析若需要共享上游事实，只能消费裁剪后的稳定投影，不能重新依赖 Collector 整包 output；若后续新增正文依赖，必须先显式转向检索/知识层，或在 projection 中正式建模，不允许私下回退到整包 output。
4. `SearchPolicyResolver`：后续如果要引入预算闸门、Provider 降级轨迹或搜索缓存命中规则，统一加在策略解析器与正式 runtime policy 上，不要回到 `WorkflowFactory` 或 UI 层猜默认值。
5. `sourceUrls` 红线：`searchAudit`、`searchReplays`、`SearchSharedProjection`、`CollectorNodeInsightResponse`、`TaskReplayResponse` 都已经带出来源回指；提取阶段新增任何结构化对象都必须沿用这条可追溯链路。

## Out Of Scope

- `SchemaExtractorAgent`、`EvidenceFragment`、`SectionEvidenceBundle` 的字段级抽取契约重构
- `ReportWriterAgent`、`QualityReviewAgent` 的正式输入协议调整
- 搜索结果缓存落数据库 / outbox 的持久化底座建设
- 预算闸门、Provider 成本面板、反爬熔断仪表盘的产品化展示
- 任何把 preview DTO 和 runtime DTO 再次混用的页面级快捷修补
