package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.AnalyzerSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.ExtractorSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionAdapter;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.orchestration.WriterSuggestionAssembler;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import cn.bugstack.competitoragent.workflow.runtime.DynamicPlanAppender;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeEventEmitter;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeStateRefresher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DagExecutorTest {

    @Test
    void shouldContinueExecutingIndependentNodeAfterPeerFailure() {
        Long taskId = 101L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode failedCollector = TaskNode.builder()
                .id(1L)
                .taskId(taskId)
                .nodeName("collect_a")
                .displayName("collect_a")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        TaskNode successfulCollector = TaskNode.builder()
                .id(2L)
                .taskId(taskId)
                .nodeName("collect_b")
                .displayName("collect_b")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();

        TaskNode analyzer = TaskNode.builder()
                .id(3L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .displayName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"collect_b\"]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(failedCollector, successfulCollector, analyzer));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent(), new TestAnalyzerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        AgentContext context = AgentContext.builder()
                .taskId(taskId)
                .taskName("parallel-test")
                .build();

        executor.execute(taskId, context);

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, failedCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, successfulCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, analyzer.getStatus());
        assertNotNull(context.getSharedOutput("collect_b"));
        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
    }

    @Test
    void shouldReleaseDependentNodeImmediatelyAfterDependencyCompletes() throws Exception {
        Long taskId = 202L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode fastCollector = TaskNode.builder()
                .id(11L)
                .taskId(taskId)
                .nodeName("collect_fast")
                .displayName("collect_fast")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        TaskNode slowCollector = TaskNode.builder()
                .id(12L)
                .taskId(taskId)
                .nodeName("collect_slow")
                .displayName("collect_slow")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();

        TaskNode analyzer = TaskNode.builder()
                .id(13L)
                .taskId(taskId)
                .nodeName("analyze_after_fast")
                .displayName("analyze_after_fast")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"collect_fast\"]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(fastCollector, slowCollector, analyzer));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch allowFastCollector = new CountDownLatch(1);
        CountDownLatch allowSlowCollector = new CountDownLatch(1);
        CountDownLatch analyzerStarted = new CountDownLatch(1);
        CoordinationCollectorAgent collectorAgent = new CoordinationCollectorAgent(
                allowFastCollector, allowSlowCollector
        );
        CoordinationAnalyzerAgent analyzerAgent = new CoordinationAnalyzerAgent(analyzerStarted);

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(collectorAgent, analyzerAgent),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        AgentContext context = AgentContext.builder()
                .taskId(taskId)
                .taskName("smart-dag-test")
                .build();

        CompletableFuture<Void> executionFuture = CompletableFuture.runAsync(() -> executor.execute(taskId, context));

        allowFastCollector.countDown();
        assertTrue(analyzerStarted.await(2, TimeUnit.SECONDS),
                "analyzer should start once collect_fast completes, instead of waiting collect_slow");

        allowSlowCollector.countDown();
        executionFuture.get(3, TimeUnit.SECONDS);

        assertEquals(TaskNodeStatus.SUCCESS, fastCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, slowCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, analyzer.getStatus());
        assertEquals(AnalysisTaskStatus.SUCCESS, task.getStatus());
    }

    @Test
    void shouldStopWorkflowWhenPausedNodeBlocksExecution() {
        Long taskId = 303L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode pausedCollector = TaskNode.builder()
                .id(21L)
                .taskId(taskId)
                .nodeName("collect_paused")
                .displayName("collect_paused")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PAUSED)
                .executionOrder(0)
                .interventionReason("节点已由用户暂停，等待恢复")
                .build();

        TaskNode analyzer = TaskNode.builder()
                .id(22L)
                .taskId(taskId)
                .nodeName("analyze_after_pause")
                .displayName("analyze_after_pause")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"collect_paused\"]")
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
                .thenReturn(List.of(pausedCollector, analyzer));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent(), new TestAnalyzerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("paused-test").build());

        assertEquals(TaskNodeStatus.PAUSED, pausedCollector.getStatus());
        assertEquals(TaskNodeStatus.PENDING, analyzer.getStatus());
        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertEquals("存在已暂停节点，等待人工恢复", task.getErrorMessage());
    }

    @Test
    void shouldDiscardRunningNodeResultAfterTerminateRequest() {
        Long taskId = 404L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode runnableCollector = TaskNode.builder()
                .id(31L)
                .taskId(taskId)
                .nodeName("collect_killable")
                .displayName("collect_killable")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        TaskNode persistedCollector = TaskNode.builder()
                .id(31L)
                .taskId(taskId)
                .nodeName("collect_killable")
                .displayName("collect_killable")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.RUNNING)
                .controlState(TaskNodeControlState.TERMINATE_REQUESTED)
                .interventionReason("节点已收到终止请求，当前轮执行结束后将停止并丢弃本轮结果")
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(runnableCollector));
        when(nodeRepository.findById(31L)).thenReturn(Optional.of(persistedCollector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("terminate-test").build());

        assertEquals(TaskNodeStatus.SKIPPED, runnableCollector.getStatus());
        assertEquals(TaskNodeControlState.NONE, runnableCollector.getControlState());
        assertTrue(runnableCollector.getErrorMessage().contains("终止请求"));
        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
    }

    @Test
    void shouldSeedHistoricalCheckpointOutputWhenContinuingFromPendingBranch() {
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
                .outputData("{\"node\":\"collect_a\"}")
                .executionOrder(0)
                .build();

        TaskNode pendingCollector = TaskNode.builder()
                .id(42L)
                .taskId(taskId)
                .nodeName("collect_b")
                .displayName("collect_b")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();

        TaskNode analyzer = TaskNode.builder()
                .id(43L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .displayName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"collect_a\",\"collect_b\"]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(completedCollector, pendingCollector, analyzer));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ResumeCollectorAgent(), new ResumeAnalyzerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        AgentContext context = AgentContext.builder()
                .taskId(taskId)
                .taskName("resume-context-test")
                .build();

        executor.execute(taskId, context);

        assertEquals(TaskNodeStatus.SUCCESS, completedCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, pendingCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, analyzer.getStatus());
        assertTrue(analyzer.getOutputData().contains("collect_a"));
        assertTrue(analyzer.getOutputData().contains("collect_b"));
        assertEquals(AnalysisTaskStatus.SUCCESS, task.getStatus());
    }

    @Test
    void shouldSeedTrimmedCollectorProjectionInsteadOfWholeCollectorOutput() throws Exception {
        Long taskId = 506L;
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
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
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
                allowingNodeLockService(),
                List.of(new SharedNodeOutputProjector() {
                    @Override
                    public boolean supports(String outputData) {
                        return outputData != null && outputData.contains("\"sourceUrls\"");
                    }

                    @Override
                    public SharedNodeOutputEnvelope project(Long taskId,
                                                            String nodeName,
                                                            Long planVersionId,
                                                            String outputData) {
                        return SharedNodeOutputEnvelope.builder()
                                .taskId(taskId)
                                .nodeName(nodeName)
                                .planVersionId(planVersionId)
                                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                                .payloadJson("""
                                        {
                                          "sourceUrls":["https://docs.example.com/reference"],
                                          "selectedUrls":["https://docs.example.com/reference"]
                                        }
                                        """)
                                .sourceUrls(List.of("https://docs.example.com/reference"))
                                .build();
                    }
                })
        );

        AgentContext context = AgentContext.builder().taskId(taskId).taskName("projection-test").build();
        executor.execute(taskId, context);

        JsonNode projection = new ObjectMapper().readTree(context.getSharedOutput("collect_a"));
        assertTrue(projection.has("sourceUrls"));
        assertTrue(projection.has("selectedUrls"));
        assertFalse(projection.has("results"));
        assertFalse(projection.toString().contains("fullContent"));
    }

    @Test
    void should_fail_node_when_capability_is_missing() {
        Long taskId = 909L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collectNode = TaskNode.builder()
                .id(61L)
                .taskId(taskId)
                .nodeName("collect_sources")
                .displayName("collect_sources")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = allowingNodeLockService();
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        WorkflowEventPublisher workflowEventPublisher = mock(WorkflowEventPublisher.class);
        AnalysisTaskRepository refresherTaskRepository = taskRepository;
        TaskNodeRepository refresherNodeRepository = nodeRepository;

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectNode));
        when(nodeRepository.findById(61L)).thenReturn(Optional.of(collectNode));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                agentType -> null,
                new ObjectMapper().findAndRegisterModules(),
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                mock(AgentLogService.class),
                workflowEventPublisher,
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(refresherTaskRepository, refresherNodeRepository, snapshotCacheService, taskEventPublisher),
                new RuntimeEventEmitter(taskEventPublisher, mock(AgentLogService.class),
                        new ObjectMapper().findAndRegisterModules()),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class)
        );

        executor.execute(taskId, AgentContext.builder()
                .taskId(taskId)
                .taskName("missing-capability-test")
                .build());

        assertEquals(TaskNodeStatus.FAILED, collectNode.getStatus());
        assertTrue(collectNode.getErrorMessage().contains("Missing agent implementation"));
        verify(nodeRepository, atLeastOnce()).save(collectNode);
    }

    @Test
    void shouldPersistSnapshotAndCacheNodeOutputDuringExecution() {
        Long taskId = 606L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(51L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ResumeCollectorAgent()),
                snapshotCacheService,
                lockService
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("snapshot-test").build());

        verify(snapshotCacheService, atLeastOnce()).saveTaskSnapshot(any());
        verify(snapshotCacheService).cacheNodeOutput(taskId, "collect_sources_web", "{\"node\":\"collect_sources_web\"}");
        verify(lockService).releaseNodeExecutionLock(any(), any(), any());
    }

    @Test
    void shouldCacheSharedOutputEnvelopeWhenProjectorSupportsCollectorOutput() {
        Long taskId = 1606L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(151L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(snapshotCacheService.getCachedSharedOutputEnvelopes(taskId)).thenReturn(Map.of());

        SharedNodeOutputProjector projector = new SharedNodeOutputProjector() {
            @Override
            public boolean supports(String outputData) {
                return outputData != null && outputData.contains("\"sourceUrls\"");
            }

            @Override
            public SharedNodeOutputEnvelope project(Long taskId,
                                                    String nodeName,
                                                    Long planVersionId,
                                                    String outputData) {
                return SharedNodeOutputEnvelope.builder()
                        .taskId(taskId)
                        .nodeName(nodeName)
                        .planVersionId(planVersionId)
                        .projectionType("SEARCH_SHARED_PROJECTION_V1")
                        .payloadJson("{\"sourceUrls\":[\"https://docs.example.com\"]}")
                        .sourceUrls(List.of("https://docs.example.com"))
                        .build();
            }
        };

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new CollectorWithStructuredOutputAgent()),
                snapshotCacheService,
                lockService,
                List.of(projector)
        );

        AgentContext context = AgentContext.builder().taskId(taskId).taskName("envelope-cache-test").build();
        executor.execute(taskId, context);

        verify(snapshotCacheService).cacheSharedOutputEnvelope(eq(taskId), any(SharedNodeOutputEnvelope.class));
        assertEquals("SEARCH_SHARED_PROJECTION_V1",
                context.getSharedOutputEnvelope("collect_sources_web").getProjectionType());
        assertTrue(context.getSharedOutput("collect_sources_web").contains("sourceUrls"));
    }

    @Test
    void shouldIgnoreNullPayloadWhenSeedingLegacySharedEnvelopeDuringResume() {
        Long taskId = 1607L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(152L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(snapshotCacheService.getCachedSharedOutputEnvelopes(taskId)).thenReturn(Map.of(
                "collect_sources_web",
                SharedNodeOutputEnvelope.builder()
                        .taskId(taskId)
                        .nodeName("collect_sources_web")
                        .projectionType("SEARCH_SHARED_PROJECTION_V1")
                        .payloadJson(null)
                        .sourceUrls(List.of("https://docs.example.com"))
                        .build()
        ));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ResumeCollectorAgent()),
                snapshotCacheService,
                lockService
        );

        AgentContext context = AgentContext.builder().taskId(taskId).taskName("legacy-envelope-resume").build();

        assertDoesNotThrow(() -> executor.execute(taskId, context));
        assertEquals("SEARCH_SHARED_PROJECTION_V1",
                context.getSharedOutputEnvelope("collect_sources_web").getProjectionType());
        assertEquals("{\"node\":\"collect_sources_web\"}", context.getSharedOutput("collect_sources_web"));
        verify(snapshotCacheService).cacheNodeOutput(taskId, "collect_sources_web", "{\"node\":\"collect_sources_web\"}");
    }

    @Test
    void shouldPassSharedOutputEnvelopeToDownstreamNodeContext() {
        Long taskId = 1608L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(161L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        TaskNode extractor = TaskNode.builder()
                .id(162L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[\"collect_sources_web\"]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector, extractor));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(snapshotCacheService.getCachedSharedOutputEnvelopes(taskId)).thenReturn(Map.of());

        SharedNodeOutputProjector projector = new SharedNodeOutputProjector() {
            @Override
            public boolean supports(String outputData) {
                return outputData != null && outputData.contains("\"sourceUrls\"");
            }

            @Override
            public SharedNodeOutputEnvelope project(Long taskId,
                                                    String nodeName,
                                                    Long planVersionId,
                                                    String outputData) {
                return SharedNodeOutputEnvelope.builder()
                        .taskId(taskId)
                        .nodeName(nodeName)
                        .planVersionId(planVersionId)
                        .projectionType("SEARCH_SHARED_PROJECTION_V1")
                        .payloadJson("{\"projectionType\":\"SEARCH_SHARED_PROJECTION_V1\",\"sourceUrls\":[\"https://docs.example.com\"]}")
                        .sourceUrls(List.of("https://docs.example.com"))
                        .build();
            }
        };

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new CollectorWithStructuredOutputAgent(), new EnvelopeAwareExtractorAgent()),
                snapshotCacheService,
                lockService,
                List.of(projector)
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("shared-envelope-downstream").build());

        assertEquals(TaskNodeStatus.SUCCESS, collector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, extractor.getStatus());
        assertTrue(extractor.getOutputData().contains("SEARCH_SHARED_PROJECTION_V1"));
    }

    @Test
    void shouldPublishMinimalSearchProgressAndFallbackAgentOutputWhenCollectorCompletesWithoutStructuredLog() {
        Long taskId = 707L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(61L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .maxRetries(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = allowingNodeLockService();
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        AgentLogService agentLogService = mock(AgentLogService.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentLogService.publishLatestLogEvent(taskId, "collect_sources_web", AgentType.COLLECTOR)).thenReturn(false);

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                registryOf(List.of(new ResumeCollectorAgent())),
                new ObjectMapper().findAndRegisterModules(),
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                agentLogService,
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, taskEventPublisher),
                new RuntimeEventEmitter(taskEventPublisher, agentLogService,
                        new ObjectMapper().findAndRegisterModules()),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class)
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("event-fallback-test").build());

        verify(taskEventPublisher).publishSearchProgressEvent(eq(taskId), eq("collect_sources_web"), any());
        verify(taskEventPublisher).publishAgentLogEvent(eq(taskId), eq("collect_sources_web"), any());
        verify(agentLogService).publishLatestLogEvent(taskId, "collect_sources_web", AgentType.COLLECTOR);
        verify(taskEventPublisher, never()).publishDiagnosisEvent(eq(taskId), eq("collect_sources_web"), any());
    }

    @Test
    void shouldRecordExecutionAttemptsAndDeadLetterWhenRetryBudgetExhausted() {
        Long taskId = 909L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(91L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(true)
                .maxRetries(1)
                .retryCount(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = allowingNodeLockService();
        TaskNodeExecutionAttemptRepository attemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        WorkflowDeadLetterRecordRepository deadLetterRepository = mock(WorkflowDeadLetterRecordRepository.class);

        List<cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt> savedAttempts = new ArrayList<>();
        List<cn.bugstack.competitoragent.model.entity.WorkflowDeadLetterRecord> savedDeadLetters = new ArrayList<>();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.findById(91L)).thenReturn(Optional.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attemptRepository.save(any())).thenAnswer(invocation -> {
            cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt attempt = invocation.getArgument(0);
            savedAttempts.add(attempt);
            return attempt;
        });
        when(attemptRepository.findByTaskIdAndNodeIdOrderByAttemptNoAsc(taskId, 91L))
                .thenAnswer(invocation -> List.copyOf(savedAttempts));
        when(deadLetterRepository.save(any())).thenAnswer(invocation -> {
            cn.bugstack.competitoragent.model.entity.WorkflowDeadLetterRecord record = invocation.getArgument(0);
            savedDeadLetters.add(record);
            return record;
        });

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                registryOf(List.of(new AlwaysTimeoutCollectorAgent())),
                new ObjectMapper().findAndRegisterModules(),
                snapshotCacheService,
                lockService,
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                attemptRepository,
                deadLetterRepository,
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, mock(TaskEventPublisher.class)),
                new RuntimeEventEmitter(mock(TaskEventPublisher.class), mock(AgentLogService.class),
                        new ObjectMapper().findAndRegisterModules()),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class)
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("retry-dlq-test").build());

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, collector.getStatus());
        assertEquals(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE, collector.getFailureCategory());
        assertEquals(1, collector.getRetryCount());
        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertTrue(task.getErrorMessage().contains("人工处理"));
        assertEquals(2, savedAttempts.size());
        assertEquals(TaskNodeStatus.WAITING_RETRY, savedAttempts.get(0).getResultStatus());
        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, savedAttempts.get(1).getResultStatus());
        assertEquals(1, savedDeadLetters.size());
        assertEquals(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE, savedDeadLetters.get(0).getFailureCategory());
        assertTrue(savedDeadLetters.get(0).getRetryHistory().contains("\"attemptNo\":1"));
        assertTrue(savedDeadLetters.get(0).getRetryHistory().contains("\"attemptNo\":2"));
    }

    @Test
    void shouldWaitForScheduledRetryWindowBeforeStoppingExecution() {
        Long taskId = 910L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode collector = TaskNode.builder()
                .id(92L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(true)
                .maxRetries(2)
                .retryCount(1)
                .status(TaskNodeStatus.WAITING_RETRY)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        AtomicLong nodeListReadCount = new AtomicLong();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenAnswer(invocation -> {
            if (nodeListReadCount.incrementAndGet() == 1) {
                collector.setNextRetryAt(java.time.LocalDateTime.now().plus(java.time.Duration.ofSeconds(1)));
            }
            return List.of(collector);
        });
        when(nodeRepository.findById(92L)).thenReturn(Optional.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessCollectorAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("scheduled-retry-test").build());

        assertEquals(TaskNodeStatus.SUCCESS, collector.getStatus());
        assertEquals(AnalysisTaskStatus.SUCCESS, task.getStatus());
    }

    @Test
    void shouldCreateDynamicBackflowPlanAndAppendDynamicNodesWhenFinalReviewFails() throws Exception {
        Long taskId = 1001L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .currentPlanVersionId(1L)
                .currentPlanVersion(1)
                .build();

        TaskNode finalReview = TaskNode.builder()
                .id(101L)
                .taskId(taskId)
                .nodeName("quality_check_final")
                .displayName("报告终审")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .planVersionId(1L)
                .branchKey("root")
                .nodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build();

        List<TaskNode> storedNodes = new ArrayList<>();
        storedNodes.add(finalReview);
        AtomicLong nodeIdSequence = new AtomicLong(500L);
        AtomicLong planIdSequence = new AtomicLong(1L);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = allowingNodeLockService();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String rootPlanSnapshot = mapper.writeValueAsString(WorkflowPlan.builder()
                .planVersionId(1L)
                .planVersion(1)
                .branchKey("root")
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("quality_check_final")
                        .displayName("报告终审")
                        .agentType(AgentType.REVIEWER.name())
                        .dependsOn(List.of())
                        .executionOrder(0)
                        .branchKey("root")
                        .build()))
                .build());
        TaskPlan rootPlan = TaskPlan.builder()
                .id(1L)
                .taskId(taskId)
                .planVersion(1)
                .branchKey("root")
                .planType("INITIAL")
                .active(true)
                .planSnapshot(rootPlanSnapshot)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenAnswer(invocation -> List.copyOf(storedNodes));
        when(nodeRepository.findById(101L)).thenReturn(Optional.of(finalReview));
        when(nodeRepository.save(any(TaskNode.class))).thenAnswer(invocation -> {
            TaskNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId(nodeIdSequence.incrementAndGet());
                storedNodes.add(node);
            }
            return node;
        });
        when(nodeRepository.saveAll(any())).thenAnswer(invocation -> {
            List<TaskNode> nodes = invocation.getArgument(0);
            for (TaskNode node : nodes) {
                if (node.getId() == null) {
                    node.setId(nodeIdSequence.incrementAndGet());
                    storedNodes.add(node);
                }
            }
            return nodes;
        });
        when(taskPlanRepository.findById(1L)).thenReturn(Optional.of(rootPlan));
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(taskId))
                .thenAnswer(invocation -> {
                    if (Long.valueOf(1L).equals(task.getCurrentPlanVersionId())) {
                        return Optional.of(rootPlan);
                    }
                    return Optional.of(TaskPlan.builder()
                            .id(task.getCurrentPlanVersionId())
                            .taskId(taskId)
                            .planVersion(task.getCurrentPlanVersion())
                            .parentPlanId(1L)
                            .branchKey("root/review-2")
                            .triggerNodeName("quality_check_final")
                            .planType("DYNAMIC_BACKFLOW")
                            .active(true)
                            .planSnapshot("{}")
                            .build());
                });
        when(taskPlanRepository.save(any(TaskPlan.class))).thenAnswer(invocation -> {
            TaskPlan plan = invocation.getArgument(0);
            if (plan.getId() == null) {
                plan.setId(planIdSequence.incrementAndGet());
            }
            return plan;
        });

        DynamicTaskGraphService dynamicTaskGraphService = new DynamicTaskGraphService(
                taskPlanRepository,
                new TaskPlanVersioner(mapper),
                new CompensationGraphAssembler(mapper));

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                registryOf(List.of(
                        new FinalReviewFailureAgent(),
                        new AlwaysSuccessCollectorAgent(),
                        new AlwaysSuccessExtractorAgent(),
                        new AlwaysSuccessAnalyzerAgent(),
                        new DynamicRewriteAgent())),
                mapper,
                snapshotCacheService,
                lockService,
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, mock(TaskEventPublisher.class)),
                new RuntimeEventEmitter(mock(TaskEventPublisher.class), mock(AgentLogService.class), mapper),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        dynamicTaskGraphService,
                        taskPlanRepository,
                        mapper,
                        new OrchestrationDecisionService(new OrchestrationDecisionAdapter()),
                        new DecisionPolicyService(),
                        new DecisionExecutorAdapter(mapper),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class)
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("dynamic-graph-test").build());

        assertEquals(2, task.getCurrentPlanVersion());
        assertEquals(2L, task.getCurrentPlanVersionId());
        assertEquals(AnalysisTaskStatus.SUCCESS, task.getStatus());
        assertTrue(storedNodes.stream().anyMatch(node -> "collect_revision_evidence_v2_1".equals(node.getNodeName())
                && Long.valueOf(2L).equals(node.getPlanVersionId())));
        assertTrue(storedNodes.stream().anyMatch(node -> "quality_check_revision_patch_v2".equals(node.getNodeName())
                && "root/review-2".equals(node.getBranchKey())));
    }

    @Test
    void shouldClassifyFinalQualityGateFailureAsDownstreamConsumptionGap() {
        Long taskId = 1002L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode writeReport = TaskNode.builder()
                .id(201L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode initialReview = TaskNode.builder()
                .id(202L)
                .taskId(taskId)
                .nodeName("quality_check")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[\"write_report\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode rewriteReport = TaskNode.builder()
                .id(203L)
                .taskId(taskId)
                .nodeName("rewrite_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"quality_check\"]")
                .nodeConfig("{\"trigger\":\"review_failed\"}")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        TaskNode finalReview = TaskNode.builder()
                .id(204L)
                .taskId(taskId)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[\"rewrite_report\"]")
                .nodeConfig("{\"trigger\":\"rewrite_executed\",\"qualityPolicy\":\"final pass after revision\"}")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(3)
                .build();
        List<TaskNode> nodes = List.of(writeReport, initialReview, rewriteReport, finalReview);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new DynamicRewriteAgent(), new QualityGateFailureReviewerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("quality-gate-test").build());

        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
        assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, finalReview.getFailureCategory());
        assertTrue(task.getErrorMessage().contains("质量闭环"));
    }

    @Test
    void shouldClassifyInitialReviewHumanInterventionStopAsDownstreamConsumptionGap() {
        Long taskId = 1003L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode writeReport = TaskNode.builder()
                .id(301L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode initialReview = TaskNode.builder()
                .id(302L)
                .taskId(taskId)
                .nodeName("quality_check")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[\"write_report\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode rewriteReport = TaskNode.builder()
                .id(303L)
                .taskId(taskId)
                .nodeName("rewrite_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"quality_check\"]")
                .nodeConfig("{\"trigger\":\"review_failed\"}")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        List<TaskNode> nodes = List.of(writeReport, initialReview, rewriteReport);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new DynamicRewriteAgent(), new InitialReviewHumanInterventionReviewerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("initial-review-human-test").build());

        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, initialReview.getFailureCategory());
        assertTrue(initialReview.getInterventionReason().contains("下游消费"));
        assertTrue(task.getErrorMessage().contains("人工介入"));
    }

    @Test
    void shouldClassifyAnalyzerConsumptionFailureAsDownstreamConsumptionGapWhenExtractorSucceeded() {
        Long taskId = 1004L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode extractSchema = TaskNode.builder()
                .id(401L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(402L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"extract_schema\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        List<TaskNode> nodes = List.of(extractSchema, analyzer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessExtractorAgent(), new AnalyzerConsumptionFailureAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("analyzer-gap-test").build());

        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
        assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, analyzer.getFailureCategory());
        assertTrue(analyzer.getInterventionReason().contains("analyzer"));
    }

    @Test
    void shouldHoldExtractorWhenSuccessfulOutputContainsBlockingSuggestion() {
        Long taskId = 1007L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode extractSchema = TaskNode.builder()
                .id(701L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(702L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"extract_schema\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        List<TaskNode> nodes = List.of(extractSchema, analyzer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ExtractorNoBusinessFieldsAgent(), new AlwaysSuccessAnalyzerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("extractor-suggestion-test").build());

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, extractSchema.getStatus());
        assertTrue(extractSchema.getInterventionReason().contains("extract_schema"));
        assertEquals(TaskNodeStatus.PENDING, analyzer.getStatus());
    }

    @Test
    void shouldHoldAnalyzerWhenSuccessfulOutputContainsBlockingAnalysisSuggestion() {
        Long taskId = 1008L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode extractSchema = TaskNode.builder()
                .id(801L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(802L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"extract_schema\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode writer = TaskNode.builder()
                .id(803L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"analyze_competitors\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        List<TaskNode> nodes = List.of(extractSchema, analyzer, writer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessExtractorAgent(), new AnalyzerAnalysisGapAgent(), new AlwaysSuccessWriterAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService(),
                List.of(),
                orchestrationTraceService
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("analyzer-suggestion-test").build());

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, analyzer.getStatus());
        assertEquals(TaskNodeStatus.PENDING, writer.getStatus());
        assertTrue(analyzer.getInterventionReason().contains("Analyzer"));
        verify(orchestrationTraceService, atLeastOnce())
                .recordDecision(eq(taskId), eq(analyzer), argThat(decision ->
                                "WAIT_FOR_HUMAN".equals(decision.getDecisionType())
                                        && "analyze_competitors".equals(decision.getTriggerNodeName())
                                        && decision.getInputRefs().containsKey("agentSuggestionIds")),
                        isNull(), isNull());
    }

    @Test
    void shouldHoldWriterWhenSuccessfulOutputContainsMissingSourceCitationGap() {
        Long taskId = 1012L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(1201L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode writer = TaskNode.builder()
                .id(1202L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"analyze_competitors\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode reviewer = TaskNode.builder()
                .id(1203L)
                .taskId(taskId)
                .nodeName("quality_check")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[\"write_report\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        List<TaskNode> nodes = List.of(analyzer, writer, reviewer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessAnalyzerAgent(), new WriterCitationGapAgent(), new AlwaysSuccessReviewerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService(),
                List.of(),
                orchestrationTraceService
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("writer-citation-gap-test").build());

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, writer.getStatus());
        assertEquals(TaskNodeStatus.PENDING, reviewer.getStatus());
        assertTrue(writer.getInterventionReason().contains("Writer"));
        verify(orchestrationTraceService, atLeastOnce())
                .recordDecision(eq(taskId), eq(writer), argThat(decision ->
                                "WAIT_FOR_HUMAN".equals(decision.getDecisionType())
                                        && "write_report".equals(decision.getTriggerNodeName())
                                        && decision.getInputRefs().containsKey("agentSuggestionIds")),
                        isNull(), isNull());
    }

    @Test
    void shouldHoldCitationNodeWhenSuccessfulOutputContainsBlockingCitationSuggestion() {
        Long taskId = 1013L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode writer = TaskNode.builder()
                .id(1301L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode citation = TaskNode.builder()
                .id(1302L)
                .taskId(taskId)
                .nodeName("citation_check")
                .agentType(AgentType.CITATION)
                .dependsOn("[\"write_report\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode reviewer = TaskNode.builder()
                .id(1303L)
                .taskId(taskId)
                .nodeName("quality_check")
                .agentType(AgentType.REVIEWER)
                .dependsOn("[\"citation_check\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        List<TaskNode> nodes = List.of(writer, citation, reviewer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessWriterAgent(), new CitationBlockingAgent(), new AlwaysSuccessReviewerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService(),
                List.of(),
                orchestrationTraceService
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("citation-gate-test").build());

        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, citation.getStatus());
        assertEquals(TaskNodeStatus.PENDING, reviewer.getStatus());
        verify(orchestrationTraceService, atLeastOnce())
                .recordDecision(eq(taskId), eq(citation), argThat(decision ->
                                "WAIT_FOR_HUMAN".equals(decision.getDecisionType())
                                        && "citation_check".equals(decision.getTriggerNodeName())
                                        && decision.getInputRefs().containsKey("agentSuggestionIds")),
                        isNull(), isNull());
    }

    @Test
    void shouldClassifyWriterConsumptionFailureAsDownstreamConsumptionGapWhenAnalyzerSucceeded() {
        Long taskId = 1005L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode extractSchema = TaskNode.builder()
                .id(501L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(502L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"extract_schema\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        TaskNode writer = TaskNode.builder()
                .id(503L)
                .taskId(taskId)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"analyze_competitors\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(2)
                .build();
        List<TaskNode> nodes = List.of(extractSchema, analyzer, writer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new AlwaysSuccessExtractorAgent(), new AlwaysSuccessAnalyzerAgent(), new WriterConsumptionFailureAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("writer-gap-test").build());

        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
        assertEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, writer.getFailureCategory());
        assertTrue(writer.getInterventionReason().contains("writer"));
    }

    @Test
    void shouldNotClassifyExtractorFailureAsDownstreamConsumptionGap() {
        Long taskId = 1006L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();

        TaskNode extractSchema = TaskNode.builder()
                .id(601L)
                .taskId(taskId)
                .nodeName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();
        TaskNode analyzer = TaskNode.builder()
                .id(602L)
                .taskId(taskId)
                .nodeName("analyze_competitors")
                .agentType(AgentType.ANALYZER)
                .dependsOn("[\"extract_schema\"]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(1)
                .build();
        List<TaskNode> nodes = List.of(extractSchema, analyzer);

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);
        when(nodeRepository.findById(any())).thenAnswer(invocation -> {
            Long nodeId = invocation.getArgument(0);
            return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst();
        });
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DagExecutor executor = newDagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ExtractorBusinessFailureAgent(), new AlwaysSuccessAnalyzerAgent()),
                mock(TaskSnapshotCacheService.class),
                allowingNodeLockService()
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("extractor-protection-test").build());

        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
        assertNotEquals(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP, extractSchema.getFailureCategory());
        assertNull(analyzer.getFailureCategory());
    }

    private static TaskExecutionLockService allowingNodeLockService() {
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(lockService.releaseNodeExecutionLock(any(), any(), any())).thenReturn(Boolean.TRUE);
        return lockService;
    }

    private static DagExecutor newDagExecutor(TaskNodeRepository nodeRepository,
                                              AnalysisTaskRepository taskRepository,
                                              List<Agent> agents,
                                              TaskSnapshotCacheService snapshotCacheService,
                                              TaskExecutionLockService lockService) {
        return newDagExecutor(nodeRepository, taskRepository, agents, snapshotCacheService, lockService, List.of());
    }

    private static DagExecutor newDagExecutor(TaskNodeRepository nodeRepository,
                                              AnalysisTaskRepository taskRepository,
                                              List<Agent> agents,
                                              TaskSnapshotCacheService snapshotCacheService,
                                              TaskExecutionLockService lockService,
                                              List<SharedNodeOutputProjector> sharedNodeOutputProjectors) {
        return newDagExecutor(
                nodeRepository,
                taskRepository,
                agents,
                snapshotCacheService,
                lockService,
                sharedNodeOutputProjectors,
                mock(OrchestrationTraceService.class));
    }

    private static DagExecutor newDagExecutor(TaskNodeRepository nodeRepository,
                                              AnalysisTaskRepository taskRepository,
                                              List<Agent> agents,
                                              TaskSnapshotCacheService snapshotCacheService,
                                              TaskExecutionLockService lockService,
                                              List<SharedNodeOutputProjector> sharedNodeOutputProjectors,
                                              OrchestrationTraceService orchestrationTraceService) {
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new DagExecutor(
                nodeRepository,
                taskRepository,
                registryOf(agents),
                objectMapper,
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                agentLogService,
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, taskEventPublisher),
                new RuntimeEventEmitter(taskEventPublisher, agentLogService, objectMapper),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        objectMapper,
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class),
                new ExtractorSuggestionAssembler(objectMapper),
                new AnalyzerSuggestionAssembler(objectMapper),
                new WriterSuggestionAssembler(objectMapper),
                new OrchestrationDecisionService(new OrchestrationDecisionAdapter()),
                orchestrationTraceService,
                sharedNodeOutputProjectors
        );
    }

    /**
     * 测试仍然沿用轻量 Agent 假实现，但 DagExecutor 的构造依赖已经切换为能力注册表。
     * 这里统一封装测试注册方式，避免每个测试手工拼装 registry，保持断言关注点只落在编排行为本身。
     */
    private static AgentCapabilityRegistry registryOf(List<Agent> agents) {
        return new SpringAgentCapabilityRegistry(agents);
    }

    private static final class TestCollectorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            if ("collect_a".equals(context.getCurrentNodeName())) {
                return AgentResult.failed("collector-a failed");
            }
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"node\":\"collect_b\"}")
                    .build();
        }
    }

    private static final class TestAnalyzerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "analyzer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            String upstream = context.getSharedOutput("collect_b");
            return AgentResult.builder()
                    .status(upstream == null || upstream.isBlank() ? TaskNodeStatus.FAILED : TaskNodeStatus.SUCCESS)
                    .outputData("{\"analyzed\":true}")
                    .errorMessage(upstream == null || upstream.isBlank() ? "missing collect_b output" : null)
                    .build();
        }
    }

    private static final class CoordinationCollectorAgent implements Agent {

        private final CountDownLatch allowFastCollector;
        private final CountDownLatch allowSlowCollector;

        private CoordinationCollectorAgent(CountDownLatch allowFastCollector, CountDownLatch allowSlowCollector) {
            this.allowFastCollector = allowFastCollector;
            this.allowSlowCollector = allowSlowCollector;
        }

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "coordinated-collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            try {
                if ("collect_fast".equals(context.getCurrentNodeName())) {
                    allowFastCollector.await(2, TimeUnit.SECONDS);
                    return AgentResult.builder()
                            .status(TaskNodeStatus.SUCCESS)
                            .outputData("{\"node\":\"collect_fast\"}")
                            .build();
                }
                if ("collect_slow".equals(context.getCurrentNodeName())) {
                    allowSlowCollector.await(2, TimeUnit.SECONDS);
                    return AgentResult.builder()
                            .status(TaskNodeStatus.SUCCESS)
                            .outputData("{\"node\":\"collect_slow\"}")
                            .build();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentResult.failed("collector interrupted");
            }
            return AgentResult.failed("unknown collector node");
        }
    }

    private static final class CoordinationAnalyzerAgent implements Agent {

        private final CountDownLatch analyzerStarted;

        private CoordinationAnalyzerAgent(CountDownLatch analyzerStarted) {
            this.analyzerStarted = analyzerStarted;
        }

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "coordinated-analyzer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            analyzerStarted.countDown();
            String upstream = context.getSharedOutput("collect_fast");
            return AgentResult.builder()
                    .status(upstream == null || upstream.isBlank() ? TaskNodeStatus.FAILED : TaskNodeStatus.SUCCESS)
                    .outputData("{\"analyzed\":true}")
                    .errorMessage(upstream == null || upstream.isBlank() ? "missing collect_fast output" : null)
                    .build();
        }
    }

    private static final class ResumeCollectorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "resume-collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"node\":\"" + context.getCurrentNodeName() + "\"}")
                    .build();
        }
    }

    private static final class ResumeAnalyzerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "resume-analyzer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            String collectA = context.getSharedOutput("collect_a");
            String collectB = context.getSharedOutput("collect_b");
            boolean allReady = collectA != null && collectA.contains("collect_a")
                    && collectB != null && collectB.contains("collect_b");
            return AgentResult.builder()
                    .status(allReady ? TaskNodeStatus.SUCCESS : TaskNodeStatus.FAILED)
                    .outputData("{\"upstreams\":[\"" + collectA + "\",\"" + collectB + "\"]}")
                    .errorMessage(allReady ? null : "missing historical checkpoint output")
                    .build();
        }
    }

    private static final class AlwaysTimeoutCollectorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "always-timeout-collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.failed("HTTP timeout while collecting source page");
        }
    }

    private static final class FinalReviewFailureAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.REVIEWER;
        }

        @Override
        public String getName() {
            return "final-review-failure";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            if ("quality_check_final".equals(context.getCurrentNodeName())) {
                return AgentResult.builder()
                        .status(TaskNodeStatus.SUCCESS)
                        .outputData("""
                                {"reviewStage":"final","passed":false,"requiresHumanIntervention":false,"revisionDirectives":[{"category":"SEARCH_QUALITY","actionType":"SUPPLEMENT_EVIDENCE","summary":"补充官网定价证据","searchQueries":["Notion AI pricing official"],"sourceUrls":["https://www.notion.so/pricing"]}]}
                                """.trim())
                        .build();
            }
            if (context.getCurrentNodeName() != null
                    && context.getCurrentNodeName().startsWith("quality_check_revision_patch_")) {
                // 动态补图派生的复核节点复用同一个 Reviewer，实现终审失败后的自动回流闭环。
                return AgentResult.builder()
                        .status(TaskNodeStatus.SUCCESS)
                        .outputData("{\"reviewStage\":\"final\",\"passed\":true,\"requiresHumanIntervention\":false}")
                        .build();
            }
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"reviewStage\":\"final\",\"passed\":true,\"requiresHumanIntervention\":false}")
                    .build();
        }
    }

    private static final class QualityGateFailureReviewerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.REVIEWER;
        }

        @Override
        public String getName() {
            return "quality-gate-failure-reviewer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            if ("quality_check".equals(context.getCurrentNodeName())) {
                return AgentResult.builder()
                        .status(TaskNodeStatus.SUCCESS)
                        .outputData("""
                                {"reviewStage":"initial","passed":false,"requiresHumanIntervention":false,"autoRewriteAllowed":true}
                                """.trim())
                        .build();
            }
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {"reviewStage":"final","passed":false,"requiresHumanIntervention":true,"diagnoses":[{"dimensionCode":"EVIDENCE_TRACEABILITY","type":"missing_evidence"}]}
                            """.trim())
                    .build();
        }
    }

    private static final class InitialReviewHumanInterventionReviewerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.REVIEWER;
        }

        @Override
        public String getName() {
            return "initial-review-human-intervention-reviewer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {"reviewStage":"initial","passed":false,"requiresHumanIntervention":true,"autoRewriteAllowed":false,"diagnoses":[{"dimensionCode":"EVIDENCE_TRACEABILITY","type":"missing_evidence"}]}
                            """.trim())
                    .build();
        }
    }

    private static final class AlwaysSuccessCollectorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "always-success-collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"sourceUrls\":[\"https://www.notion.so/pricing\"]}")
                    .build();
        }
    }

    private static final class AlwaysSuccessExtractorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.EXTRACTOR;
        }

        @Override
        public String getName() {
            return "always-success-extractor";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"extracted\":true}")
                    .build();
        }
    }

    private static final class AlwaysSuccessAnalyzerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "always-success-analyzer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"analyzed\":true}")
                    .build();
        }
    }

    private static final class AnalyzerAnalysisGapAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "analyzer-gap-agent";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.success("""
                    {
                      "analysisConfidence": "LOW",
                      "analysisGapSeverity": "HIGH",
                      "analysisEvidenceState": "MISSING_SOURCE",
                      "missingAnalysisDimensions": ["featureComparison", "pricingComparison"],
                      "sourceUrls": []
                    }
                    """, "Analyzer 存在分析缺口");
        }
    }

    private static final class AlwaysSuccessWriterAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.WRITER;
        }

        @Override
        public String getName() {
            return "always-success-writer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"written\":true}")
                    .build();
        }
    }

    private static final class WriterCitationGapAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.WRITER;
        }

        @Override
        public String getName() {
            return "writer-citation-gap";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {
                              "content": "# 竞品报告",
                              "writerEvidenceState": "MISSING_SOURCE",
                              "citationGapSeverity": "ERROR",
                              "sourceUrls": [],
                              "sectionCitationGaps": [
                                {
                                  "targetSection": "report_conclusion",
                                  "sectionTitle": "报告结论",
                                  "summary": "报告结论缺少可用来源",
                                  "severity": "ERROR",
                                  "sourceUrls": [],
                                  "evidenceState": "MISSING_SOURCE",
                                  "missingFields": ["recommendations"]
                                }
                              ]
                            }
                            """)
                    .build();
        }
    }

    private static final class CitationBlockingAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.CITATION;
        }

        @Override
        public String getName() {
            return "citation-blocking-agent";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {
                              "citationRiskSeverity": "ERROR",
                              "citationEvidenceState": "MISSING_SOURCE",
                              "citationIssues": [
                                {
                                  "issueId": "ci-1",
                                  "issueType": "MISSING_CITATION",
                                  "severity": "ERROR",
                                  "targetSection": "action_suggestion",
                                  "claimId": "claim-1",
                                  "summary": "行动建议缺少引用",
                                  "sourceUrls": [],
                                  "evidenceState": "MISSING_SOURCE",
                                  "suggestedQueries": ["action_suggestion official evidence"]
                                }
                              ]
                            }
                            """)
                    .build();
        }
    }

    private static final class AlwaysSuccessReviewerAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.REVIEWER;
        }

        @Override
        public String getName() {
            return "always-success-reviewer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"reviewed\":true}")
                    .build();
        }
    }

    private static final class AnalyzerConsumptionFailureAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.ANALYZER;
        }

        @Override
        public String getName() {
            return "analyzer-consumption-failure";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.failed("analyzer invalid downstream consumption of extract result drafts and evidence coverage");
        }
    }

    private static final class WriterConsumptionFailureAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.WRITER;
        }

        @Override
        public String getName() {
            return "writer-consumption-failure";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.failed("writer invalid downstream consumption of analyzer result and evidence bundle");
        }
    }

    private static final class ExtractorBusinessFailureAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.EXTRACTOR;
        }

        @Override
        public String getName() {
            return "extractor-business-failure";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.failed("missing business fields, issueFlags=[NO_BUSINESS_FIELDS_EXTRACTED]");
        }
    }

    private static final class ExtractorNoBusinessFieldsAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.EXTRACTOR;
        }

        @Override
        public String getName() {
            return "extractor-no-business-fields";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {
                              "sourceUrls": [],
                              "issueFlags": ["NO_BUSINESS_FIELDS_EXTRACTED"],
                              "evidenceCoverage": {}
                            }
                            """)
                    .build();
        }
    }

    private static final class DynamicRewriteAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.WRITER;
        }

        @Override
        public String getName() {
            return "dynamic-rewrite";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"rewritten\":true}")
                    .build();
        }
    }

    private static final class CollectorWithStructuredOutputAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "collector-with-structured-output";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("""
                            {
                              "sourceUrls":["https://docs.example.com"],
                              "selectedTargets":[{"url":"https://docs.example.com"}]
                            }
                            """)
                    .build();
        }
    }

    private static final class EnvelopeAwareExtractorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.EXTRACTOR;
        }

        @Override
        public String getName() {
            return "envelope-aware-extractor";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            SharedNodeOutputEnvelope envelope = context.getSharedOutputEnvelope("collect_sources_web");
            if (envelope == null || envelope.getProjectionType() == null) {
                return AgentResult.failed("missing shared output envelope");
            }
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"projectionType\":\"" + envelope.getProjectionType() + "\"}")
                    .build();
        }
    }

}
