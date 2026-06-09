package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                List.of(new ResumeCollectorAgent()),
                new ObjectMapper(),
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                agentLogService,
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                mock(DynamicTaskGraphService.class),
                mock(TaskPlanRepository.class)
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
                List.of(new AlwaysTimeoutCollectorAgent()),
                new ObjectMapper(),
                snapshotCacheService,
                lockService,
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                attemptRepository,
                deadLetterRepository,
                mock(DynamicTaskGraphService.class),
                mock(TaskPlanRepository.class)
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
        ObjectMapper mapper = new ObjectMapper();
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
                List.of(
                        new FinalReviewFailureAgent(),
                        new AlwaysSuccessCollectorAgent(),
                        new AlwaysSuccessExtractorAgent(),
                        new AlwaysSuccessAnalyzerAgent(),
                        new DynamicRewriteAgent()),
                mapper,
                snapshotCacheService,
                lockService,
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                dynamicTaskGraphService,
                taskPlanRepository
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
        return new DagExecutor(
                nodeRepository,
                taskRepository,
                agents,
                new ObjectMapper(),
                snapshotCacheService,
                lockService,
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                mock(DynamicTaskGraphService.class),
                mock(TaskPlanRepository.class)
        );
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

}
