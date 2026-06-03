package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent(), new TestAnalyzerAgent()),
                new ObjectMapper()
        );

        AgentContext context = AgentContext.builder()
                .taskId(taskId)
                .taskName("parallel-test")
                .build();

        executor.execute(taskId, context);

        assertEquals(TaskNodeStatus.FAILED, failedCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, successfulCollector.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, analyzer.getStatus());
        assertNotNull(context.getSharedOutput("collect_b"));
        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
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

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                List.of(collectorAgent, analyzerAgent),
                new ObjectMapper()
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

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent(), new TestAnalyzerAgent()),
                new ObjectMapper()
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

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new TestCollectorAgent()),
                new ObjectMapper()
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

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                List.of(new ResumeCollectorAgent(), new ResumeAnalyzerAgent()),
                new ObjectMapper()
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
}
