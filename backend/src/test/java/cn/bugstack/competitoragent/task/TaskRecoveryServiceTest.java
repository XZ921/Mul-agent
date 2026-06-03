package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

    @InjectMocks
    private TaskRecoveryService recoveryService;

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
                .interventionReason("节点已由用户暂停，等待恢复")
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
    }
}
