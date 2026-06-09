package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRecoveryServiceTest {

    @Mock
    private AnalysisTaskRepository taskRepository;

    @Mock
    private TaskNodeRepository nodeRepository;

    @Mock
    private AnalysisTaskRunner taskRunner;

    @Mock
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @Mock
    private TaskEventPublisher taskEventPublisher;

    @Mock
    private WorkflowEventOutboxService workflowEventOutboxService;

    @Mock
    private DynamicTaskGraphService dynamicTaskGraphService;

    @Mock
    private TaskPlanRepository taskPlanRepository;

    @Mock
    private OrganizationQuotaPolicy organizationQuotaPolicy;

    @InjectMocks
    private TaskRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                recoveryService,
                "taskQuotaCoordinator",
                new TaskQuotaCoordinator(organizationQuotaPolicy, new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    void shouldResetInterruptedNodesButKeepSuccessfulCheckpoints() {
        TaskNode successNode = TaskNode.builder()
                .nodeName("collect")
                .status(TaskNodeStatus.SUCCESS)
                .outputData("{\"ok\":true}")
                .retryCount(1)
                .build();
        TaskNode runningNode = TaskNode.builder()
                .nodeName("extract")
                .status(TaskNodeStatus.RUNNING)
                .inputData("{\"partial\":true}")
                .outputData("{\"partial\":true}")
                .retryCount(2)
                .build();

        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(1L)).thenReturn(List.of(successNode, runningNode));

        boolean recoverable = recoveryService.resetInterruptedNodes(1L);

        assertTrue(recoverable);
        assertEquals(TaskNodeStatus.SUCCESS, successNode.getStatus());
        assertEquals(TaskNodeStatus.PENDING, runningNode.getStatus());
        assertNull(runningNode.getInputData());
        assertNull(runningNode.getOutputData());
        assertEquals(0, runningNode.getRetryCount());
        assertEquals("Node interrupted by service restart", runningNode.getErrorMessage());
        verify(nodeRepository).saveAll(any());
    }

    @Test
    void shouldResumeRunningTasksOnStartup() {
        AnalysisTask task = AnalysisTask.builder()
                .id(7L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode runningNode = TaskNode.builder()
                .nodeName("extract")
                .status(TaskNodeStatus.RUNNING)
                .build();

        when(taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING)).thenReturn(List.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(7L)).thenReturn(List.of(runningNode));

        recoveryService.recoverInterruptedTasks();

        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertFalse(task.getErrorMessage() == null || task.getErrorMessage().isBlank());
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(7L);
        verify(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
    }

    @Test
    void shouldFailInterruptedTaskWithoutNodes() {
        AnalysisTask task = AnalysisTask.builder()
                .id(8L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();

        when(taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING)).thenReturn(List.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(8L)).thenReturn(List.of());

        recoveryService.recoverInterruptedTasks();

        assertEquals(AnalysisTaskStatus.FAILED, task.getStatus());
        verify(taskRunner, never()).runTask(8L);
        verify(taskRepository).save(task);
    }

    @Test
    void shouldKeepRecoveredTaskStoppedWhenPausedNodeStillBlocksWorkflow() {
        AnalysisTask task = AnalysisTask.builder()
                .id(9L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode pausedNode = TaskNode.builder()
                .nodeName("extract")
                .status(TaskNodeStatus.PAUSED)
                .interventionReason("节点已被用户暂停，等待恢复")
                .build();
        TaskNode pendingNode = TaskNode.builder()
                .nodeName("analyze")
                .status(TaskNodeStatus.PENDING)
                .build();

        when(taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING)).thenReturn(List.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(9L)).thenReturn(List.of(pausedNode, pendingNode));

        recoveryService.recoverInterruptedTasks();

        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertTrue(task.getErrorMessage().contains("暂停"));
        verify(taskRunner, never()).runTask(9L);
        verify(taskRepository).save(task);
    }

    @Test
    void shouldMarkRecoveredTaskSuccessfulWithoutRerunWhenAllNodesAlreadySucceeded() {
        AnalysisTask task = AnalysisTask.builder()
                .id(10L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode successNode = TaskNode.builder()
                .nodeName("write_report")
                .status(TaskNodeStatus.SUCCESS)
                .outputData("{\"ok\":true}")
                .build();

        when(taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING)).thenReturn(List.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(10L)).thenReturn(List.of(successNode));

        recoveryService.recoverInterruptedTasks();

        assertEquals(AnalysisTaskStatus.SUCCESS, task.getStatus());
        verify(taskRunner, never()).runTask(10L);
        verify(taskRepository).save(task);
        verify(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
    }

    @Test
    void shouldRebuildRecoverySnapshotWhenRedisCacheMisses() {
        AnalysisTask task = AnalysisTask.builder()
                .id(12L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode runningNode = TaskNode.builder()
                .taskId(12L)
                .nodeName("collect_sources_web")
                .displayName("官网补源")
                .status(TaskNodeStatus.RUNNING)
                .build();
        TaskNode successNode = TaskNode.builder()
                .taskId(12L)
                .nodeName("extract_schema")
                .status(TaskNodeStatus.SUCCESS)
                .build();

        when(taskSnapshotCacheService.getTaskSnapshot(12L)).thenReturn(Optional.empty());
        when(taskRepository.findById(12L)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(12L)).thenReturn(List.of(runningNode, successNode));

        TaskProgressSnapshot snapshot = recoveryService.getTaskSnapshotOrRebuild(12L).orElseThrow();

        assertEquals(12L, snapshot.getTaskId());
        assertEquals("RUNNING", snapshot.getTaskStatus());
        assertEquals("官网补源", snapshot.getCurrentStage());
        assertEquals(1, snapshot.getCompletedNodes());
        assertEquals(List.of("collect_sources_web"), snapshot.getActiveNodeNames());
        verify(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
    }

    @Test
    void shouldRecoverAgainstCurrentDynamicPlanScopeInsteadOfInactiveSiblingBranch() {
        AnalysisTask task = AnalysisTask.builder()
                .id(13L)
                .status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(2L)
                .currentPlanVersion(2)
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(13L)
                .nodeName("quality_check_final")
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(1L)
                .branchKey("root")
                .build();
        TaskNode activeBranchNode = TaskNode.builder()
                .taskId(13L)
                .nodeName("collect_revision_evidence_v2_1")
                .status(TaskNodeStatus.RUNNING)
                .planVersionId(2L)
                .branchKey("root/review-2")
                .build();
        TaskNode inactiveSiblingNode = TaskNode.builder()
                .taskId(13L)
                .nodeName("rewrite_revision_patch_v3")
                .status(TaskNodeStatus.PAUSED)
                .interventionReason("inactive branch pause")
                .planVersionId(3L)
                .branchKey("root/review-3")
                .build();

        when(taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING)).thenReturn(List.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(13L))
                .thenReturn(List.of(triggerNode, activeBranchNode, inactiveSiblingNode));
        when(taskPlanRepository.findById(2L)).thenReturn(Optional.of(TaskPlan.builder()
                .id(2L)
                .taskId(13L)
                .planVersion(2)
                .parentPlanId(1L)
                .branchKey("root/review-2")
                .triggerNodeName("quality_check_final")
                .planType("DYNAMIC_BACKFLOW")
                .active(true)
                .planSnapshot("{}")
                .build()));
        when(dynamicTaskGraphService.calculateAffectedNodes(List.of(triggerNode, activeBranchNode, inactiveSiblingNode), triggerNode))
                .thenReturn(List.of(triggerNode, activeBranchNode));

        recoveryService.recoverInterruptedTasks();

        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(taskRunner).runTask(13L);
    }
}
