package cn.bugstack.competitoragent.task.query;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

/**
 * 任务只读查询应用服务。
 * <p>
 * 这里只承担读列表、详情与节点查询职责，不处理创建、执行、重跑等命令。
 */
@Service
@RequiredArgsConstructor
public class TaskQueryAppService {

    private static final int DEFAULT_TASK_LIST_PAGE_SIZE = 10;
    private static final int MAX_TASK_LIST_PAGE_SIZE = 50;
    private static final int TASK_LIST_ATTENTION_LIMIT = 4;

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskNodeViewAssembler assembler;

    public TaskListPageResponse listTasks(String status, int pageNum, int pageSize) {
        AnalysisTaskStatus taskStatus = parseTaskStatus(status);
        int normalizedPageSize = normalizeTaskListPageSize(pageSize);
        List<AnalysisTask> matchedTasks = listMatchedTasks(taskStatus);
        int total = matchedTasks.size();
        int totalPages = calculateTaskListTotalPages(total, normalizedPageSize);
        int normalizedPageNum = normalizeTaskListPageNum(pageNum, totalPages);

        PageRequest pageRequest = PageRequest.of(normalizedPageNum - 1, normalizedPageSize);
        Page<AnalysisTask> taskPage = listMatchedTaskPage(taskStatus, pageRequest);
        Map<Long, TaskResponse> taskResponseCache = new HashMap<>();
        List<TaskResponse> allTaskResponses = matchedTasks.stream()
                .map(task -> taskResponseCache.computeIfAbsent(task.getId(), ignored -> toTaskResponse(task)))
                .toList();
        List<TaskResponse> pageItems = taskPage.getContent().stream()
                .map(task -> taskResponseCache.computeIfAbsent(task.getId(), ignored -> toTaskResponse(task)))
                .toList();

        /*
         * 列表页除了返回当前分页数据，还需要同时返回 attentionItems 与 summary。
         * 因此这里先构造整批匹配任务的视图缓存，保证分页项、关注项和汇总都基于同一套只读响应数据。
         */
        return TaskListPageResponse.builder()
                .items(pageItems)
                .attentionItems(buildAttentionTaskResponses(allTaskResponses))
                .summary(buildTaskListSummary(allTaskResponses))
                .pageNum(normalizedPageNum)
                .pageSize(normalizedPageSize)
                .total(total)
                .totalPages(totalPages)
                .build();
    }

    public TaskResponse getTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        return assembler.toTaskResponse(task, nodes);
    }

    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        return nodes.stream()
                .map(node -> assembler.toNodeResponse(task, node, nodes))
                .toList();
    }

    private AnalysisTask getTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));
    }

    private AnalysisTaskStatus parseTaskStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnalysisTaskStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "Unsupported task status: " + status);
        }
    }

    private Page<AnalysisTask> listMatchedTaskPage(AnalysisTaskStatus status, PageRequest pageRequest) {
        return status == null
                ? taskRepository.findAllByOrderByCreatedAtDesc(pageRequest)
                : taskRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
    }

    private TaskResponse toTaskResponse(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        return assembler.toTaskResponse(task, nodes);
    }

    private List<AnalysisTask> listMatchedTasks(AnalysisTaskStatus status) {
        return status == null
                ? taskRepository.findAllByOrderByCreatedAtDesc()
                : taskRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    private int normalizeTaskListPageNum(int pageNum, int totalPages) {
        int normalizedPageNum = Math.max(1, pageNum);
        return Math.min(normalizedPageNum, Math.max(1, totalPages));
    }

    private int normalizeTaskListPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_TASK_LIST_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_TASK_LIST_PAGE_SIZE);
    }

    private int calculateTaskListTotalPages(int total, int pageSize) {
        if (total <= 0) {
            return 1;
        }
        return (int) Math.ceil((double) total / pageSize);
    }

    private List<TaskResponse> buildAttentionTaskResponses(List<TaskResponse> taskResponses) {
        return taskResponses.stream()
                .filter(task -> task.getStatus() == AnalysisTaskStatus.FAILED
                        || task.getStatus() == AnalysisTaskStatus.STOPPED
                        || task.getStatus() == AnalysisTaskStatus.RUNNING)
                .sorted(Comparator
                        .comparingInt(this::taskAttentionPriority)
                        .reversed()
                        .thenComparing(TaskResponse::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TaskResponse::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(TASK_LIST_ATTENTION_LIMIT)
                .toList();
    }

    private int taskAttentionPriority(TaskResponse task) {
        if (task.getStatus() == AnalysisTaskStatus.FAILED) {
            return 3;
        }
        if (task.getStatus() == AnalysisTaskStatus.STOPPED) {
            return 2;
        }
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            return 1;
        }
        return 0;
    }

    private cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse buildTaskListSummary(List<TaskResponse> taskResponses) {
        int total = taskResponses.size();
        int running = 0;
        int success = 0;
        int failed = 0;
        int stopped = 0;
        int progressSum = 0;

        /*
         * 列表汇总采用任务视图里的 completedNodes/totalNodes 统一计算，
         * 避免汇总口径和单条任务卡片出现不一致。
         */
        for (TaskResponse taskResponse : taskResponses) {
            if (taskResponse.getStatus() == AnalysisTaskStatus.RUNNING) {
                running++;
            } else if (taskResponse.getStatus() == AnalysisTaskStatus.SUCCESS) {
                success++;
            } else if (taskResponse.getStatus() == AnalysisTaskStatus.FAILED) {
                failed++;
            } else if (taskResponse.getStatus() == AnalysisTaskStatus.STOPPED) {
                stopped++;
            }

            if (taskResponse.getTotalNodes() > 0) {
                progressSum += Math.round((taskResponse.getCompletedNodes() * 100.0f) / taskResponse.getTotalNodes());
            }
        }

        return cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse.builder()
                .total(total)
                .running(running)
                .success(success)
                .failed(failed)
                .stopped(stopped)
                .avgProgress(total == 0 ? 0 : Math.round(progressSum / (float) total))
                .build();
    }
}
