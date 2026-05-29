package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 任务应用服务，负责创建、执行、重试、续跑和删除任务，并维护任务关联产物生命周期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisTaskService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final ReportRepository reportRepository;
    private final AgentExecutionLogRepository logRepository;
    private final WorkflowFactory workflowFactory;
    private final AnalysisTaskRunner taskRunner;
    private final TaskRecoveryService taskRecoveryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        // 创建任务阶段只固化入参与工作流，不直接执行业务节点。
        AnalysisTask task = AnalysisTask.builder()
                .taskName(request.getTaskName())
                .subjectProduct(request.getSubjectProduct())
                .competitorNames(toJson(request.getCompetitorNames()))
                .competitorUrls(toJson(request.getCompetitorUrls()))
                .analysisDimensions(toJson(request.getAnalysisDimensions()))
                .sourceScope(toJson(request.getSourceScope()))
                .reportLanguage(defaultIfBlank(request.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准版"))
                .schemaId(request.getSchemaId())
                .status(AnalysisTaskStatus.PENDING)
                .build();

        task = taskRepository.save(task);
        workflowFactory.createWorkflow(task);

        log.info("create analysis task success, taskId={}, taskName={}", task.getId(), task.getTaskName());
        return toTaskResponse(task);
    }

    public List<TaskResponse> listTasks(String status) {
        List<AnalysisTask> tasks;
        if (status != null && !status.isBlank()) {
            try {
                AnalysisTaskStatus taskStatus = AnalysisTaskStatus.valueOf(status.toUpperCase());
                tasks = taskRepository.findByStatusOrderByCreatedAtDesc(taskStatus);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "Unsupported task status: " + status);
            }
        } else {
            tasks = taskRepository.findAllByOrderByCreatedAtDesc();
        }
        return tasks.stream().map(this::toTaskResponse).toList();
    }

    public TaskResponse getTask(Long taskId) {
        return toTaskResponse(getTaskOrThrow(taskId));
    }

    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        return nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)
                .stream()
                .map(this::toNodeResponse)
                .toList();
    }

    public List<TaskNodeResponse> previewWorkflow(CreateTaskRequest request) {
        AnalysisTask draftTask = AnalysisTask.builder()
                .taskName(request.getTaskName())
                .subjectProduct(request.getSubjectProduct())
                .competitorNames(toJson(request.getCompetitorNames()))
                .competitorUrls(toJson(request.getCompetitorUrls()))
                .analysisDimensions(toJson(request.getAnalysisDimensions()))
                .sourceScope(toJson(request.getSourceScope()))
                .reportLanguage(defaultIfBlank(request.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准版"))
                .schemaId(request.getSchemaId())
                .build();

        return workflowFactory.buildPlan(draftTask).getNodes().stream()
                .map(this::toPreviewNodeResponse)
                .toList();
    }

    @Transactional
    public void executeTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_ALREADY_RUNNING, "taskId=" + taskId);
        }

        // 成功或失败任务重新执行时，采用整任务重置策略，避免旧产物混入新链路。
        if (task.getStatus() == AnalysisTaskStatus.FAILED || task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            resetTaskForExecution(task);
        }

        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setCompletedAt(null);
        taskRepository.save(task);

        runAfterCommit(taskId);
    }

    @Transactional
    public void retryTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() != AnalysisTaskStatus.FAILED) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only failed tasks can be fully reset. Current status: " + task.getStatus());
        }

        resetTaskForExecution(task);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setCompletedAt(null);
        taskRepository.save(task);

        log.info("task reset for full retry, taskId={}", taskId);
    }

    @Transactional
    public void resumeTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_ALREADY_RUNNING, "taskId=" + taskId);
        }
        if (task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Successful task does not need resume. Use execute or retry instead.");
        }

        // resume 与 retry 的区别是尽量保留成功节点成果，只修复未完成链路。
        prepareTaskForResume(task);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setCompletedAt(null);
        taskRepository.save(task);

        log.info("task resume requested, taskId={}", taskId);
        runAfterCommit(taskId);
    }

    @Transactional
    public void deleteTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_DELETE_FAILED, "Running task cannot be deleted");
        }

        deleteGeneratedData(taskId);
        nodeRepository.deleteAll(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
        taskRepository.delete(task);
        log.info("delete task success, taskId={}", taskId);
    }

    private void resetTaskForExecution(AnalysisTask task) {
        Long taskId = task.getId();
        deleteGeneratedData(taskId);

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        for (TaskNode node : nodes) {
            resetNodeExecutionState(node, true);
        }
        nodeRepository.saveAll(nodes);
    }

    /**
     * 续跑只重置未完成节点，保留成功节点的输入输出和下游可复用产物。
     */
    private void prepareTaskForResume(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        if (nodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task has no workflow nodes to resume");
        }

        boolean hasRunningNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.RUNNING);
        if (hasRunningNode) {
            taskRecoveryService.resetInterruptedNodes(task.getId());
            nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        }

        boolean hasSuccessfulCheckpoint = false;
        boolean hasWorkToResume = false;

        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS) {
                // 已成功节点保留现场，供执行器续跑时重新注入共享上下文。
                hasSuccessfulCheckpoint = true;
                continue;
            }

            hasWorkToResume = true;
            resetNodeExecutionState(node, true);
        }

        if (!hasWorkToResume && hasSuccessfulCheckpoint) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task already completed successfully");
        }

        nodeRepository.saveAll(nodes);
    }

    private void resetNodeExecutionState(TaskNode node, boolean clearOutput) {
        node.setStatus(TaskNodeStatus.PENDING);
        node.setInputData(null);
        if (clearOutput) {
            // 对未成功节点清空旧输出，避免 trace 面板和后续执行误读历史失败结果。
            node.setOutputData(null);
        }
        node.setErrorMessage(null);
        node.setStartedAt(null);
        node.setCompletedAt(null);
        node.setRetryCount(0);
    }

    /**
     * 删除整任务重跑所需清空的派生产物，保证任务视角下数据一致。
     */
    private void deleteGeneratedData(Long taskId) {
        reportRepository.deleteByTaskId(taskId);
        knowledgeRepository.deleteByTaskId(taskId);
        evidenceRepository.deleteByTaskId(taskId);
        logRepository.deleteByTaskId(taskId);
    }

    private void runAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交后再异步启动 DAG，避免执行线程读取到未提交状态。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskRunner.runTask(taskId);
                }
            });
        } else {
            taskRunner.runTask(taskId);
        }
    }

    private AnalysisTask getTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));
    }

    private TaskResponse toTaskResponse(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        int totalNodes = nodes.size();
        int completedNodes = (int) nodes.stream()
                .filter(node -> node.getStatus() == TaskNodeStatus.SUCCESS)
                .count();

        return TaskResponse.builder()
                .id(task.getId())
                .taskName(task.getTaskName())
                .subjectProduct(task.getSubjectProduct())
                .competitorNames(task.getCompetitorNames())
                .competitorUrls(task.getCompetitorUrls())
                .analysisDimensions(task.getAnalysisDimensions())
                .status(task.getStatus())
                .errorMessage(task.getErrorMessage())
                .totalNodes(totalNodes)
                .completedNodes(completedNodes)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    private TaskNodeResponse toNodeResponse(TaskNode node) {
        return TaskNodeResponse.builder()
                .id(node.getId())
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(truncate(node.getNodeConfig(), 180))
                .nodeNotes(node.getNodeNotes())
                .agentType(node.getAgentType())
                .dependsOn(node.getDependsOn())
                .required(node.isRequired())
                .retryable(node.isRetryable())
                .maxRetries(node.getMaxRetries())
                .retryCount(node.getRetryCount())
                .status(node.getStatus())
                .errorMessage(node.getErrorMessage())
                .executionOrder(node.getExecutionOrder())
                .inputSummary(truncate(node.getInputData(), 240))
                .outputSummary(truncate(node.getOutputData(), 240))
                .inputData(node.getInputData())
                .outputData(node.getOutputData())
                .allowFailedDependency(node.isAllowFailedDependency())
                .startedAt(node.getStartedAt())
                .completedAt(node.getCompletedAt())
                .build();
    }

    /**
     * 预览节点没有真实执行输入输出，这里只把规划结果转换成前端可展示结构。
     */
    private TaskNodeResponse toPreviewNodeResponse(WorkflowPlan.WorkflowPlanNode node) {
        return TaskNodeResponse.builder()
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(truncate(node.getNodeConfig(), 180))
                .agentType(cn.bugstack.competitoragent.model.enums.AgentType.valueOf(node.getAgentType()))
                .dependsOn(toJson(node.getDependsOn()))
                .required(node.isRequired())
                .retryable(node.isRetryable())
                .maxRetries(node.getMaxRetries())
                .retryCount(0)
                .executionOrder(node.getExecutionOrder())
                .inputSummary(node.getNodeConfig())
                .status(TaskNodeStatus.PENDING)
                .nodeNotes(node.getNotes())
                .allowFailedDependency(node.isAllowFailedDependency())
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("serialize json failed", e);
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
