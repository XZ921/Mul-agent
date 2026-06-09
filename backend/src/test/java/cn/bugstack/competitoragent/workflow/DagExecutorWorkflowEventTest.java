package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.runtime.DynamicPlanAppender;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeEventEmitter;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeStateRefresher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class DagExecutorWorkflowEventTest {

    @Test
    void shouldPublishInternalWorkflowLifecycleEventsWhenNodeTransitions() {
        Long taskId = 808L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode collector = TaskNode.builder()
                .id(1L)
                .taskId(taskId)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(false)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .build();

        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
        TaskSnapshotCacheService snapshotCacheService = mock(TaskSnapshotCacheService.class);
        TaskExecutionLockService lockService = mock(TaskExecutionLockService.class);
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        WorkflowEventPublisher workflowEventPublisher = mock(WorkflowEventPublisher.class);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collector));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockService.tryAcquireNodeExecutionLock(any(), any(), any(), any())).thenReturn(true);
        when(lockService.releaseNodeExecutionLock(any(), any(), any())).thenReturn(true);

        DagExecutor executor = new DagExecutor(
                nodeRepository,
                taskRepository,
                new SpringAgentCapabilityRegistry(List.of(new SuccessfulCollectorAgent())),
                new ObjectMapper(),
                snapshotCacheService,
                lockService,
                taskEventPublisher,
                agentLogService,
                workflowEventPublisher,
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(taskRepository, nodeRepository, snapshotCacheService, taskEventPublisher),
                new RuntimeEventEmitter(taskEventPublisher, agentLogService, new ObjectMapper()),
                new DynamicPlanAppender(
                        taskRepository,
                        nodeRepository,
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        new ObjectMapper()),
                mock(TaskQuotaCoordinator.class)
        );

        executor.execute(taskId, AgentContext.builder().taskId(taskId).taskName("workflow-event-test").build());

        verify(workflowEventPublisher).publishNodeReady(any(TaskNode.class));
        verify(workflowEventPublisher).publishNodeCompleted(any(TaskNode.class), any());
    }

    private static final class SuccessfulCollectorAgent implements Agent {

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
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("{\"sourceUrls\":[\"https://docs.example.com\"]}")
                    .build();
        }
    }
}
