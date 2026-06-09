package cn.bugstack.competitoragent.task.query;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskQueryAppServiceTest {

    @Mock
    private AnalysisTaskRepository taskRepository;

    @Mock
    private TaskNodeRepository nodeRepository;

    @Mock
    private TaskNodeViewAssembler assembler;

    @Test
    void shouldListTasksWithPaginationSummaryAndAttentionItems() {
        TaskQueryAppService taskQueryAppService = new TaskQueryAppService(taskRepository, nodeRepository, assembler);

        AnalysisTask failedTask = listTask(201L, "Failed task", AnalysisTaskStatus.FAILED,
                LocalDateTime.of(2026, 6, 4, 10, 0),
                LocalDateTime.of(2026, 6, 4, 10, 4));
        AnalysisTask stoppedTask = listTask(202L, "Stopped task", AnalysisTaskStatus.STOPPED,
                LocalDateTime.of(2026, 6, 4, 9, 50),
                LocalDateTime.of(2026, 6, 4, 10, 3));
        AnalysisTask runningTask = listTask(203L, "Running task", AnalysisTaskStatus.RUNNING,
                LocalDateTime.of(2026, 6, 4, 9, 40),
                LocalDateTime.of(2026, 6, 4, 10, 2));
        AnalysisTask successTask = listTask(204L, "Success task", AnalysisTaskStatus.SUCCESS,
                LocalDateTime.of(2026, 6, 4, 9, 30),
                LocalDateTime.of(2026, 6, 4, 10, 1));

        List<AnalysisTask> matchedTasks = List.of(failedTask, stoppedTask, runningTask, successTask);
        when(taskRepository.findAllByOrderByCreatedAtDesc()).thenReturn(matchedTasks);
        when(taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2)))
                .thenReturn(new PageImpl<>(List.of(failedTask, stoppedTask), PageRequest.of(0, 2), matchedTasks.size()));

        /*
         * 查询服务只负责分页、关注项与汇总编排，
         * 具体任务响应细节统一交给组装器，避免查询层重复维护视图字段规则。
         */
        when(assembler.toTaskResponse(failedTask, List.of())).thenReturn(taskResponse("Failed task", AnalysisTaskStatus.FAILED, 1, 0));
        when(assembler.toTaskResponse(stoppedTask, List.of())).thenReturn(taskResponse("Stopped task", AnalysisTaskStatus.STOPPED, 2, 1));
        when(assembler.toTaskResponse(runningTask, List.of())).thenReturn(taskResponse("Running task", AnalysisTaskStatus.RUNNING, 4, 2));
        when(assembler.toTaskResponse(successTask, List.of())).thenReturn(taskResponse("Success task", AnalysisTaskStatus.SUCCESS, 1, 1));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(201L)).thenReturn(List.of());
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(202L)).thenReturn(List.of());
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(203L)).thenReturn(List.of());
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(204L)).thenReturn(List.of());

        TaskListPageResponse response = taskQueryAppService.listTasks(null, 1, 2);

        assertEquals(1, response.getPageNum());
        assertEquals(2, response.getPageSize());
        assertEquals(4, response.getTotal());
        assertEquals(2, response.getTotalPages());
        assertEquals(List.of("Failed task", "Stopped task"),
                response.getItems().stream().map(TaskResponse::getTaskName).toList());
        assertEquals(List.of("Failed task", "Stopped task", "Running task"),
                response.getAttentionItems().stream().map(TaskResponse::getTaskName).toList());

        TaskListSummaryResponse summary = response.getSummary();
        assertEquals(4, summary.getTotal());
        assertEquals(1, summary.getRunning());
        assertEquals(1, summary.getSuccess());
        assertEquals(1, summary.getFailed());
        assertEquals(1, summary.getStopped());
        assertEquals(50, summary.getAvgProgress());
    }

    @Test
    void shouldLoadTaskDetailAndNodesThroughAssembler() {
        TaskQueryAppService taskQueryAppService = new TaskQueryAppService(taskRepository, nodeRepository, assembler);

        AnalysisTask task = AnalysisTask.builder()
                .id(301L)
                .taskName("Phase 1 query task")
                .status(AnalysisTaskStatus.PENDING)
                .build();
        TaskNode node = TaskNode.builder()
                .taskId(301L)
                .nodeName("collect_sources_web")
                .executionOrder(0)
                .build();
        TaskResponse expectedTaskResponse = TaskResponse.builder().id(301L).taskName("Phase 1 query task").build();
        TaskNodeResponse expectedNodeResponse = TaskNodeResponse.builder().nodeName("collect_sources_web").build();

        when(taskRepository.findById(301L)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(301L)).thenReturn(List.of(node));
        when(assembler.toTaskResponse(task, List.of(node))).thenReturn(expectedTaskResponse);
        when(assembler.toNodeResponse(task, node, List.of(node))).thenReturn(expectedNodeResponse);

        TaskResponse taskResponse = taskQueryAppService.getTask(301L);
        List<TaskNodeResponse> nodeResponses = taskQueryAppService.getTaskNodes(301L);

        assertSame(expectedTaskResponse, taskResponse);
        assertEquals(1, nodeResponses.size());
        assertSame(expectedNodeResponse, nodeResponses.get(0));
        verify(assembler).toTaskResponse(task, List.of(node));
        verify(assembler).toNodeResponse(task, node, List.of(node));
    }

    @Test
    void shouldRejectUnsupportedTaskStatus() {
        TaskQueryAppService taskQueryAppService = new TaskQueryAppService(taskRepository, nodeRepository, assembler);

        assertThrows(BusinessException.class, () -> taskQueryAppService.listTasks("unsupported", 1, 10));
    }

    private static AnalysisTask listTask(Long id,
                                         String taskName,
                                         AnalysisTaskStatus status,
                                         LocalDateTime createdAt,
                                         LocalDateTime updatedAt) {
        return AnalysisTask.builder()
                .id(id)
                .taskName(taskName)
                .subjectProduct("Workspace")
                .competitorNames("[\"Notion AI\"]")
                .status(status)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static TaskResponse taskResponse(String taskName,
                                             AnalysisTaskStatus status,
                                             int totalNodes,
                                             int completedNodes) {
        return TaskResponse.builder()
                .taskName(taskName)
                .status(status)
                .totalNodes(totalNodes)
                .completedNodes(completedNodes)
                .updatedAt(LocalDateTime.of(2026, 6, 4, 10, 0))
                .createdAt(LocalDateTime.of(2026, 6, 4, 9, 0))
                .build();
    }
}
