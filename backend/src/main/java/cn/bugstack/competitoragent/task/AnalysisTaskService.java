package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.CollectorNodeInsightResponse;
import cn.bugstack.competitoragent.model.dto.CollectorSelectedTargetSummary;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeConfigSummary;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

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
        AnalysisTask task = getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        AnalysisTaskStatus resolvedStatus = recoveryPolicy().resolveTaskExecution(task, nodes).getStatus();
        Map<String, List<TaskNode>> rerunImpactMap = buildRerunImpactMap(nodes);
        return nodes
                .stream()
                .map(node -> toNodeResponse(node, resolvedStatus, rerunImpactMap.getOrDefault(node.getNodeName(), List.of())))
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

        return workflowFactory.buildPreviewPlan(draftTask).getNodes().stream()
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
    public void rerunFromNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_ALREADY_RUNNING, "taskId=" + taskId);
        }

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task has no workflow nodes");
        }

        TaskNode targetNode = nodes.stream()
                .filter(node -> node.getNodeName().equals(nodeName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_STATUS_INVALID,
                        "Node not found in task: " + nodeName));

        List<TaskNode> affectedNodes = collectAffectedNodes(nodes, targetNode.getNodeName());
        if (affectedNodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "No downstream nodes affected by rerun: " + nodeName);
        }

        invalidateDerivedDataForNodeRerun(taskId, targetNode, affectedNodes);
        for (TaskNode node : affectedNodes) {
            reuseSearchCheckpointIfPresent(node);
            resetNodeExecutionState(node, true);
        }
        nodeRepository.saveAll(affectedNodes);

        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setCompletedAt(null);
        taskRepository.save(task);

        log.info("task rerun from node requested, taskId={}, nodeName={}, affectedCount={}",
                taskId, nodeName, affectedNodes.size());
        runAfterCommit(taskId);
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
    public void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_ALREADY_RUNNING, "taskId=" + taskId);
        }
        if (request == null || !hasText(request.getNodeConfig())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "nodeConfig is required");
        }

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task has no workflow nodes");
        }

        TaskNode targetNode = nodes.stream()
                .filter(node -> node.getNodeName().equals(nodeName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_STATUS_INVALID,
                        "Node not found in task: " + nodeName));

        JsonNode configNode;
        try {
            configNode = objectMapper.readTree(request.getNodeConfig());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "nodeConfig must be valid JSON object");
        }
        if (configNode == null || !configNode.isObject()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "nodeConfig must be JSON object");
        }

        targetNode.setNodeConfig(configNode.toString());
        nodeRepository.save(targetNode);

        log.info("node config updated before rerun, taskId={}, nodeName={}", taskId, nodeName);
        rerunFromNode(taskId, nodeName);
    }

    @Transactional
    public void pauseNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        TaskNode node = getNodeOrThrow(taskId, nodeName);
        if (node.getStatus() != TaskNodeStatus.PENDING) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only pending nodes can be paused. Current status: " + node.getStatus());
        }

        node.setStatus(TaskNodeStatus.PAUSED);
        node.setErrorMessage("节点已由用户暂停，等待恢复");
        node.setInterventionReason("节点已由用户暂停，等待恢复");
        node.setControlState(TaskNodeControlState.NONE);
        nodeRepository.save(node);

        log.info("node pause requested, taskId={}, nodeName={}, taskStatus={}", taskId, nodeName, task.getStatus());
    }

    @Transactional
    public void resumeNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        TaskNode node = getNodeOrThrow(taskId, nodeName);
        if (node.getStatus() != TaskNodeStatus.PAUSED) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only paused nodes can be resumed. Current status: " + node.getStatus());
        }

        resetNodeForManualContinue(node);
        nodeRepository.save(node);
        continueTaskIfNecessary(task);

        log.info("node resume requested, taskId={}, nodeName={}, taskStatus={}", taskId, nodeName, task.getStatus());
    }

    @Transactional
    public void skipNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        TaskNode node = getNodeOrThrow(taskId, nodeName);
        if (node.getStatus() != TaskNodeStatus.PENDING && node.getStatus() != TaskNodeStatus.PAUSED) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only pending or paused nodes can be skipped. Current status: " + node.getStatus());
        }

        markNodeSkippedByUser(node, "节点已由用户手动跳过");
        nodeRepository.save(node);
        continueTaskIfNecessary(task);

        log.info("node skip requested, taskId={}, nodeName={}, taskStatus={}", taskId, nodeName, task.getStatus());
    }

    @Transactional
    public void terminateNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        TaskNode node = getNodeOrThrow(taskId, nodeName);

        if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED) {
            markNodeSkippedByUser(node, "节点已由用户强制终止");
            nodeRepository.save(node);
            continueTaskIfNecessary(task);
            log.info("node terminated before execution, taskId={}, nodeName={}, taskStatus={}",
                    taskId, nodeName, task.getStatus());
            return;
        }

        if (node.getStatus() != TaskNodeStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only pending, paused or running nodes can be terminated. Current status: " + node.getStatus());
        }

        node.setControlState(TaskNodeControlState.TERMINATE_REQUESTED);
        node.setInterventionReason("节点已收到终止请求，当前轮执行结束后将停止并丢弃本轮结果");
        nodeRepository.save(node);

        log.info("node terminate requested cooperatively, taskId={}, nodeName={}", taskId, nodeName);
    }

    @Transactional
    public void stopTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() != AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_STOP_FAILED,
                    "Only running tasks can be stopped. Current status: " + task.getStatus());
        }

        task.setStatus(AnalysisTaskStatus.STOPPED);
        task.setErrorMessage("任务已由用户主动停止");
        task.setCompletedAt(java.time.LocalDateTime.now());
        taskRepository.save(task);
        taskRecoveryService.markStoppedNodes(taskId);

        log.info("task stop requested, taskId={}", taskId);
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
            recoveryPolicy().resetNodeForRerun(node, true);
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
            reuseSearchCheckpointIfPresent(node);
            markManualResumeApprovalIfNecessary(node);
        }

        recoveryPolicy().resetNodesForResume(nodes, true);

        if (!hasWorkToResume && hasSuccessfulCheckpoint) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task already completed successfully");
        }

        nodeRepository.saveAll(nodes);
    }

    private void resetNodeExecutionState(TaskNode node, boolean clearOutput) {
        recoveryPolicy().resetNodeForRerun(node, clearOutput);
    }

    private void resetNodeForManualContinue(TaskNode node) {
        recoveryPolicy().resetNodeForManualContinue(node);
    }

    private void markNodeSkippedByUser(TaskNode node, String reason) {
        node.setStatus(TaskNodeStatus.SKIPPED);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(null);
        node.setOutputData(null);
        node.setErrorMessage(reason);
        node.setInterventionReason(reason);
        node.setStartedAt(node.getStartedAt() == null ? LocalDateTime.now() : node.getStartedAt());
        node.setCompletedAt(LocalDateTime.now());
    }

    private void continueTaskIfNecessary(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy().resolveTaskExecution(task, nodes);
        if (!recoveryPolicy().canAutoContinue(nodes)) {
            LocalDateTime resolvedCompletedAt = resolution.resolveCompletedAt(task.getCompletedAt());
            if (task.getStatus() != resolution.getStatus()
                    || !java.util.Objects.equals(task.getErrorMessage(), resolution.getErrorMessage())
                    || !java.util.Objects.equals(task.getCompletedAt(), resolvedCompletedAt)) {
                task.setStatus(resolution.getStatus());
                task.setErrorMessage(resolution.getErrorMessage());
                task.setCompletedAt(resolvedCompletedAt);
                taskRepository.save(task);
            }
            return;
        }
        if (task.getStatus() == AnalysisTaskStatus.RUNNING || task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            return;
        }
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setCompletedAt(null);
        taskRepository.save(task);
        runAfterCommit(task.getId());
    }

    /**
     * 删除整任务重跑所需清空的派生产物，保证任务视角下数据一致。
     */
    private List<TaskNode> collectAffectedNodes(List<TaskNode> nodes, String startNodeName) {
        Map<String, List<String>> dependentsMap = new HashMap<>();
        for (TaskNode node : nodes) {
            dependentsMap.putIfAbsent(node.getNodeName(), new ArrayList<>());
        }
        for (TaskNode node : nodes) {
            for (String dependencyName : parseDependencyNames(node.getDependsOn())) {
                dependentsMap.computeIfAbsent(dependencyName, key -> new ArrayList<>()).add(node.getNodeName());
            }
        }

        Set<String> affectedNames = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(startNodeName);
        affectedNames.add(startNodeName);

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String dependentName : dependentsMap.getOrDefault(current, List.of())) {
                if (affectedNames.add(dependentName)) {
                    queue.add(dependentName);
                }
            }
        }

        return nodes.stream()
                .filter(node -> affectedNames.contains(node.getNodeName()))
                .toList();
    }

    private void invalidateDerivedDataForNodeRerun(Long taskId, TaskNode targetNode, List<TaskNode> affectedNodes) {
        Set<String> affectedNodeNames = new HashSet<>();
        for (TaskNode affectedNode : affectedNodes) {
            affectedNodeNames.add(affectedNode.getNodeName());
        }

        if (targetNode.getNodeName().startsWith("collect_sources")) {
            evidenceRepository.deleteByTaskIdAndEvidenceIdStartingWith(
                    taskId, buildEvidencePrefix(taskId, targetNode.getNodeName()));
        }

        if (affectedNodeNames.contains("extract_schema")) {
            knowledgeRepository.deleteByTaskId(taskId);
            reportRepository.deleteByTaskId(taskId);
            return;
        }

        if (affectedNodeNames.contains("analyze_competitors") || "write_report".equals(targetNode.getNodeName())) {
            reportRepository.deleteByTaskId(taskId);
        }
    }

    private String buildEvidencePrefix(Long taskId, String nodeName) {
        long safeTaskId = taskId == null ? 0L : taskId;
        String safeNodeName = nodeName == null || nodeName.isBlank()
                ? "NODE"
                : nodeName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        return String.format("T%04d-%s-", safeTaskId % 10000, safeNodeName);
    }

    private List<String> parseDependencyNames(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank() || "[]".equals(dependsOn)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    dependsOn,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (Exception e) {
            log.warn("parse dependencies failed: {}", dependsOn, e);
            return List.of();
        }
    }

    private void deleteGeneratedData(Long taskId) {
        reportRepository.deleteByTaskId(taskId);
        knowledgeRepository.deleteByTaskId(taskId);
        evidenceRepository.deleteByTaskId(taskId);
        logRepository.deleteByTaskId(taskId);
    }

    /**
     * 采集节点重跑或续跑时，把上次搜索审计快照重新写回节点配置，
     * 让 CollectorAgent 能优先复用候选与选源现场，而不是每次都重新做完整搜索。
     */
    private void reuseSearchCheckpointIfPresent(TaskNode node) {
        if (node == null || node.getAgentType() != AgentType.COLLECTOR || !hasText(node.getOutputData()) || !hasText(node.getNodeConfig())) {
            return;
        }
        try {
            JsonNode output = objectMapper.readTree(node.getOutputData());
            JsonNode auditNode = output.get("searchAudit");
            if (auditNode == null || auditNode.isNull() || auditNode.isMissingNode()) {
                return;
            }
            SearchAuditSnapshot checkpoint = objectMapper.treeToValue(auditNode, SearchAuditSnapshot.class);
            if (checkpoint == null) {
                return;
            }
            JsonNode configNode = objectMapper.readTree(node.getNodeConfig());
            if (!configNode.isObject()) {
                return;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) configNode).set("searchAuditCheckpoint", objectMapper.valueToTree(checkpoint));
            node.setNodeConfig(objectMapper.writeValueAsString(configNode));
        } catch (Exception e) {
            log.warn("reuse search checkpoint failed, nodeName={}", node.getNodeName(), e);
        }
    }

    /**
     * 当初审要求人工介入后，用户触发 resume 代表已经确认可以继续闭环。
     * 这里把确认信号写回待执行节点配置，供 DAG 条件判断放行 rewrite_report。
     */
    private void markManualResumeApprovalIfNecessary(TaskNode node) {
        if (node == null || !hasText(node.getNodeConfig()) || !"rewrite_report".equals(node.getNodeName())) {
            return;
        }
        try {
            JsonNode configNode = objectMapper.readTree(node.getNodeConfig());
            if (!configNode.isObject()) {
                return;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) configNode).put("manualResumeApproved", true);
            node.setNodeConfig(objectMapper.writeValueAsString(configNode));
        } catch (Exception e) {
            log.warn("mark manual resume approval failed, nodeName={}", node.getNodeName(), e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy().resolveTaskExecution(task, nodes);
        AnalysisTaskStatus resolvedStatus = resolution.getStatus();

        return TaskResponse.builder()
                .id(task.getId())
                .taskName(task.getTaskName())
                .subjectProduct(task.getSubjectProduct())
                .competitorNames(task.getCompetitorNames())
                .competitorUrls(task.getCompetitorUrls())
                .analysisDimensions(task.getAnalysisDimensions())
                .sourceScope(task.getSourceScope())
                .status(resolvedStatus)
                .errorMessage(resolution.getErrorMessage())
                .totalNodes(resolution.getTotalNodes())
                .completedNodes(resolution.getCompletedNodes())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(resolution.resolveCompletedAt(task.getCompletedAt()))
                .canExecute(canExecuteTask(resolvedStatus))
                .canResume(canResumeTask(resolvedStatus))
                .canRetry(canRetryTask(resolvedStatus))
                .canStop(canStopTask(resolvedStatus))
                .canViewReport(resolvedStatus == AnalysisTaskStatus.SUCCESS)
                .interventionSummary(buildTaskInterventionSummary(resolvedStatus))
                .build();
    }

    private boolean isTerminalStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.FAILED
                || status == TaskNodeStatus.SKIPPED;
    }

    private TaskNodeResponse toNodeResponse(TaskNode node, AnalysisTaskStatus taskStatus, List<TaskNode> affectedNodes) {
        List<String> affectedNodeNames = affectedNodes.stream()
                .map(TaskNode::getNodeName)
                .toList();
        TaskNodeConfigSummary configSummaryData = buildNodeConfigSummaryData(node);
        boolean canRerun = canRerunNode(taskStatus);
        boolean canUpdateConfigAndRerun = canUpdateConfigAndRerun(node, taskStatus);
        boolean canReuseCheckpoint = hasReusableCheckpoint(node);
        boolean canPause = canPauseNode(node);
        boolean canResumeNode = canResumeNode(node);
        boolean canSkip = canSkipNode(node);
        boolean canTerminate = canTerminateNode(node);
        CollectorNodeInsightResponse collectorInsight = buildCollectorNodeInsight(node);
        return TaskNodeResponse.builder()
                .id(node.getId())
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(configSummaryData == null ? null : configSummaryData.getSummaryText())
                .configSummaryData(configSummaryData)
                .collectorInsight(collectorInsight)
                .nodeNotes(node.getNodeNotes())
                .agentType(node.getAgentType())
                .dependsOn(node.getDependsOn())
                .required(node.isRequired())
                .retryable(node.isRetryable())
                .maxRetries(node.getMaxRetries())
                .retryCount(node.getRetryCount())
                .status(node.getStatus())
                .controlState(node.getControlState())
                .errorMessage(node.getErrorMessage())
                .interventionReason(node.getInterventionReason())
                .executionOrder(node.getExecutionOrder())
                .inputSummary(truncate(node.getInputData(), 240))
                .outputSummary(buildOutputSummary(node))
                .inputData(node.getInputData())
                .outputData(node.getOutputData())
                .allowFailedDependency(node.isAllowFailedDependency())
                .startedAt(node.getStartedAt())
                .completedAt(node.getCompletedAt())
                .canRerun(canRerun)
                .canUpdateConfigAndRerun(canUpdateConfigAndRerun)
                .affectedNodeCount(affectedNodeNames.size())
                .affectedNodeNames(affectedNodeNames)
                .canReuseCheckpoint(canReuseCheckpoint)
                .canPause(canPause)
                .canResumeNode(canResumeNode)
                .canSkip(canSkip)
                .canTerminate(canTerminate)
                .interventionSummary(buildNodeInterventionSummary(
                        node,
                        taskStatus,
                        affectedNodeNames,
                        canReuseCheckpoint,
                        canPause,
                        canResumeNode,
                        canSkip,
                        canTerminate))
                .build();
    }

    /**
     * 预览节点没有真实执行输入输出，这里只把规划结果转换成前端可展示结构。
     */
    private TaskNodeResponse toPreviewNodeResponse(WorkflowPlan.WorkflowPlanNode node) {
        TaskNodeConfigSummary configSummaryData = buildPreviewNodeConfigSummaryData(node);
        CollectorNodeInsightResponse collectorInsight = buildPreviewCollectorNodeInsight(node);
        return TaskNodeResponse.builder()
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(configSummaryData == null ? null : configSummaryData.getSummaryText())
                .configSummaryData(configSummaryData)
                .collectorInsight(collectorInsight)
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
                .canRerun(false)
                .canUpdateConfigAndRerun(false)
                .affectedNodeCount(0)
                .affectedNodeNames(List.of())
                .canReuseCheckpoint(false)
                .canPause(false)
                .canResumeNode(false)
                .canSkip(false)
                .canTerminate(false)
                .interventionSummary("预览阶段仅展示规划结果，任务创建后才可执行节点级暂停、跳过、终止或重跑。")
                .build();
    }

    private Map<String, List<TaskNode>> buildRerunImpactMap(List<TaskNode> nodes) {
        Map<String, List<TaskNode>> rerunImpactMap = new HashMap<>();
        for (TaskNode node : nodes) {
            rerunImpactMap.put(node.getNodeName(), collectAffectedNodes(nodes, node.getNodeName()));
        }
        return rerunImpactMap;
    }

    private boolean canExecuteTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.PENDING || status == AnalysisTaskStatus.FAILED;
    }

    private boolean canResumeTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.FAILED || status == AnalysisTaskStatus.STOPPED;
    }

    private boolean canRetryTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.FAILED;
    }

    private boolean canStopTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.RUNNING;
    }

    private String buildTaskInterventionSummary(AnalysisTaskStatus status) {
        if (status == AnalysisTaskStatus.RUNNING) {
            return "任务运行中支持停止整任务；单节点可暂停尚未启动的节点、手动跳过未启动节点，或对运行中节点发起协作式终止请求。";
        }
        if (status == AnalysisTaskStatus.FAILED) {
            return "当前支持恢复执行、整任务重置，以及从指定节点重跑；如存在已暂停节点，也可恢复单节点后继续执行。";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "当前支持基于已有检查点恢复执行，以及从指定节点发起局部重跑；若是节点暂停导致收口，也可直接恢复对应节点继续执行。";
        }
        if (status == AnalysisTaskStatus.SUCCESS) {
            return "任务已完成，可查看报告；如需局部修正，支持从指定节点重新发起执行并保留未受影响成果。";
        }
        return "任务尚未开始，可直接启动执行；节点级支持暂停待执行节点、手动跳过待执行节点，以及从指定节点重跑。";
    }

    private boolean canRerunNode(AnalysisTaskStatus taskStatus) {
        return taskStatus != AnalysisTaskStatus.RUNNING;
    }

    private boolean canUpdateConfigAndRerun(TaskNode node, AnalysisTaskStatus taskStatus) {
        return canRerunNode(taskStatus) && hasText(node.getNodeConfig());
    }

    private boolean canPauseNode(TaskNode node) {
        return node != null && node.getStatus() == TaskNodeStatus.PENDING;
    }

    private boolean canResumeNode(TaskNode node) {
        return node != null && node.getStatus() == TaskNodeStatus.PAUSED;
    }

    private boolean canSkipNode(TaskNode node) {
        return node != null && (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED);
    }

    private boolean canTerminateNode(TaskNode node) {
        return node != null
                && (node.getStatus() == TaskNodeStatus.PENDING
                || node.getStatus() == TaskNodeStatus.PAUSED
                || (node.getStatus() == TaskNodeStatus.RUNNING && node.getControlState() != TaskNodeControlState.TERMINATE_REQUESTED));
    }

    private boolean hasReusableCheckpoint(TaskNode node) {
        if (node == null || node.getAgentType() != AgentType.COLLECTOR || !hasText(node.getOutputData())) {
            return false;
        }
        JsonNode output = readJson(node.getOutputData());
        return output != null && output.hasNonNull("searchAudit");
    }

    private String buildNodeInterventionSummary(TaskNode node,
                                                AnalysisTaskStatus taskStatus,
                                                List<String> affectedNodeNames,
                                                boolean canReuseCheckpoint,
                                                boolean canPause,
                                                boolean canResumeNode,
                                                boolean canSkip,
                                                boolean canTerminate) {
        if (node.getControlState() == TaskNodeControlState.TERMINATE_REQUESTED) {
            return "该节点已收到协作式终止请求。系统不会强杀当前线程，会在本轮执行返回后丢弃结果并将节点标记为已跳过。";
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "该节点已暂停，不会继续参与 DAG 调度。可恢复为待执行节点继续流程，也可直接手动跳过。";
        }
        if (node.getStatus() == TaskNodeStatus.RUNNING) {
            return canTerminate
                    ? "该节点正在执行中。当前支持发起协作式终止请求，系统会在本轮执行返回后停止使用本轮结果；暂不支持线程级强杀。"
                    : "该节点正在执行中，当前不支持直接重跑或改配置后继续；如需中断，请先发起终止请求或停止整任务。";
        }
        int downstreamCount = Math.max(affectedNodeNames.size() - 1, 0);
        StringBuilder summary = new StringBuilder("从当前节点重跑会重置当前节点");
        if (downstreamCount > 0) {
            summary.append("及 ").append(downstreamCount).append(" 个下游节点");
        }
        summary.append("，其余未受影响的成功节点成果会被保留。");
        if (node.getAgentType() == AgentType.COLLECTOR) {
            summary.append(canReuseCheckpoint
                    ? " 当前采集节点存在搜索检查点，可优先复用候选与选源现场。"
                    : " 当前采集节点没有可复用的搜索检查点，将按最新配置重新补源与采集。");
        }
        if (canPause) {
            summary.append(" 当前可先暂停该待执行节点，暂停后不会被调度。");
        }
        if (canResumeNode) {
            summary.append(" 当前可恢复该暂停节点并继续后续流程。");
        }
        if (canSkip) {
            summary.append(" 当前可手动跳过该节点，系统会按依赖关系自动处理下游。");
        }
        if (canTerminate) {
            summary.append(" 如需放弃本节点，也可直接终止。");
        }
        if (taskStatus == AnalysisTaskStatus.RUNNING) {
            summary.append(" 任务仍在运行时，节点重跑与改配置后继续仍需等待任务结束。");
        }
        return summary.toString();
    }

    private TaskNode getNodeOrThrow(Long taskId, String nodeName) {
        return nodeRepository.findByTaskIdAndNodeName(taskId, nodeName)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_STATUS_INVALID,
                        "Node not found in task: " + nodeName));
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

    private TaskNodeConfigSummary buildNodeConfigSummaryData(TaskNode node) {
        if (node == null || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(truncate(node.getNodeConfig(), 180))
                    .build();
        }
        return buildConfigSummaryData(node.getAgentType(), config);
    }

    private TaskNodeConfigSummary buildPreviewNodeConfigSummaryData(WorkflowPlan.WorkflowPlanNode node) {
        if (node == null || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(truncate(node.getNodeConfig(), 180))
                    .build();
        }
        AgentType agentType = AgentType.valueOf(node.getAgentType());
        return buildConfigSummaryData(agentType, config);
    }

    private CollectorNodeInsightResponse buildCollectorNodeInsight(TaskNode node) {
        if (node == null || node.getAgentType() != AgentType.COLLECTOR || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return null;
        }
        JsonNode output = hasText(node.getOutputData()) ? readJson(node.getOutputData()) : null;
        return buildCollectorNodeInsight(config, output);
    }

    private CollectorNodeInsightResponse buildPreviewCollectorNodeInsight(WorkflowPlan.WorkflowPlanNode node) {
        if (node == null || !"COLLECTOR".equalsIgnoreCase(node.getAgentType()) || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return null;
        }
        return buildCollectorNodeInsight(config, null);
    }

    private CollectorNodeInsightResponse buildCollectorNodeInsight(JsonNode config, JsonNode output) {
        String competitorName = defaultIfBlank(
                textOrNull(output, "competitor"),
                defaultIfBlank(textOrNull(config, "competitorName"), "未命名竞品"));
        String sourceType = defaultIfBlank(
                textOrNull(output, "sourceType"),
                defaultIfBlank(textOrNull(config, "sourceType"), "OFFICIAL"));
        String searchMode = defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID");
        List<String> searchQueries = readStringList(output == null ? null : output.get("searchQueries"));
        if (searchQueries.isEmpty()) {
            searchQueries = readStringList(config.get("searchQueries"));
        }

        List<SourceCandidate> sourceCandidates = convertList(
                output != null && output.has("sourceCandidates") ? output.get("sourceCandidates") : config.get("sourceCandidates"),
                new TypeReference<List<SourceCandidate>>() {
                });
        List<CollectorSelectedTargetSummary> selectedTargets = convertList(
                output == null ? null : output.get("selectedTargets"),
                new TypeReference<List<CollectorSelectedTargetSummary>>() {
                });

        return CollectorNodeInsightResponse.builder()
                .competitorName(competitorName)
                .sourceType(sourceType)
                .sourceTypeLabel(sourceTypeLabel(sourceType))
                .sourceScope(readStringList(config.get("sourceScope")))
                .competitorUrls(readStringList(config.get("competitorUrls")))
                .searchMode(searchMode)
                .searchModeLabel(searchModeLabel(searchMode))
                .searchQueries(searchQueries)
                .browserSearchEnabled(config.path("browserSearchEnabled").asBoolean(false))
                .verifyResultPage(config.path("verifyResultPage").asBoolean(
                        config.path("verifyCandidates").asBoolean(false)))
                .minVerifiedCandidates(config.path("minVerifiedCandidates").asInt(0) > 0
                        ? config.path("minVerifiedCandidates").asInt(0)
                        : null)
                .preferredDomains(readStringList(config.get("preferredDomains")))
                .candidateCount(sourceCandidates.size())
                .selectedCount(selectedTargets.size())
                .successCollected(output == null ? 0 : output.path("successCollected").asInt(0))
                .totalCollected(output == null ? 0 : output.path("totalCollected").asInt(0))
                .discoveryNotes(defaultIfBlank(textOrNull(output, "discoveryNotes"), textOrNull(config, "discoveryNotes")))
                .searchProgress(convertValue(output == null ? null : output.get("searchProgress"), SearchProgressSnapshot.class))
                .searchExecutionPlan(convertValue(
                        output != null && output.has("searchExecutionPlan") ? output.get("searchExecutionPlan") : config.get("searchExecutionPlan"),
                        SearchExecutionPlan.class))
                .searchExecutionTrace(convertValue(
                        output == null ? null : output.get("searchExecutionTrace"),
                        SearchExecutionTrace.class))
                .searchProgressSnapshots(convertList(
                        output == null ? null : output.get("searchProgressSnapshots"),
                        new TypeReference<List<SearchProgressSnapshot>>() {
                        }))
                .sourceCandidates(sourceCandidates)
                .selectedTargets(selectedTargets)
                .build();
    }

    private TaskNodeConfigSummary buildConfigSummaryData(AgentType agentType, JsonNode config) {
        if (agentType == AgentType.COLLECTOR) {
            String competitor = defaultIfBlank(textOrNull(config, "competitorName"), "未命名竞品");
            String sourceType = defaultIfBlank(textOrNull(config, "sourceType"), "OFFICIAL");
            String sourceTypeLabel = sourceTypeLabel(sourceType);
            int candidateCount = config.path("sourceCandidates").isArray() ? config.path("sourceCandidates").size() : 0;
            int queryCount = config.path("searchQueries").isArray() ? config.path("searchQueries").size() : 0;
            int stepCount = config.path("searchExecutionPlan").path("steps").isArray()
                    ? config.path("searchExecutionPlan").path("steps").size()
                    : 0;
            String searchMode = defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID");
            String searchModeLabel = searchModeLabel(searchMode);
            boolean browserEnabled = config.path("browserSearchEnabled").asBoolean(false);
            boolean verificationEnabled = config.path("verifyResultPage").asBoolean(
                    config.path("verifyCandidates").asBoolean(false));
            int minVerifiedCandidates = config.path("minVerifiedCandidates").asInt(0);
            StringBuilder summary = new StringBuilder();
            summary.append(competitor)
                    .append(" · ")
                    .append(sourceTypeLabel)
                    .append("采集")
                    .append(" · 搜索模式：")
                    .append(searchModeLabel)
                    .append(" · 候选 ")
                    .append(candidateCount)
                    .append(" 条");
            if (queryCount > 0) {
                summary.append(" · Query ")
                        .append(queryCount)
                        .append(" 条");
            }
            if (stepCount > 0) {
                summary.append(" · 计划 ")
                        .append(stepCount)
                        .append(" 步");
            }
            summary.append(" · 浏览器补源：")
                    .append(browserEnabled ? "开启" : "关闭")
                    .append(" · 结果页验证：")
                    .append(verificationEnabled ? "开启" : "关闭");
            return TaskNodeConfigSummary.builder()
                    .summaryText(summary.toString())
                    .competitorName(competitor)
                    .sourceType(sourceType)
                    .sourceTypeLabel(sourceTypeLabel)
                    .searchMode(searchMode)
                    .searchModeLabel(searchModeLabel)
                    .candidateCount(candidateCount)
                    .queryCount(queryCount)
                    .stepCount(stepCount)
                    .browserSearchEnabled(browserEnabled)
                    .verificationEnabled(verificationEnabled)
                    .minVerifiedCandidates(minVerifiedCandidates > 0 ? minVerifiedCandidates : null)
                    .sourceScope(readStringList(config.get("sourceScope")))
                    .preferredDomains(readStringList(config.get("preferredDomains")))
                    .competitorUrls(readStringList(config.get("competitorUrls")))
                    .discoveryNotes(textOrNull(config, "discoveryNotes"))
                    .build();
        }
        if (agentType == AgentType.EXTRACTOR) {
            List<String> dimensions = readStringList(config.get("dimensions"));
            return TaskNodeConfigSummary.builder()
                    .summaryText("分析维度：" + defaultIfBlank(summarizeArray(config.get("dimensions"), 4), "使用默认维度"))
                    .dimensions(dimensions)
                    .build();
        }
        if (agentType == AgentType.ANALYZER) {
            int competitorCount = config.path("competitorCount").asInt(0);
            int dimensionCount = config.path("dimensionCount").asInt(0);
            return TaskNodeConfigSummary.builder()
                    .summaryText("汇总 " + competitorCount + " 个竞品，分析 " + dimensionCount + " 个维度")
                    .competitorCount(competitorCount)
                    .dimensionCount(dimensionCount)
                    .build();
        }
        if (agentType == AgentType.WRITER) {
            boolean revision = "revision".equalsIgnoreCase(config.path("mode").asText(""));
            String reportLanguage = defaultIfBlank(textOrNull(config, "reportLanguage"), "中文");
            String reportTemplate = defaultIfBlank(textOrNull(config, "reportTemplate"), "标准版");
            if (revision) {
                return TaskNodeConfigSummary.builder()
                        .summaryText("根据评审结果修订报告")
                        .mode("revision")
                        .reportLanguage(reportLanguage)
                        .reportTemplate(reportTemplate)
                        .sourceNode(textOrNull(config, "sourceNode"))
                        .build();
            }
            return TaskNodeConfigSummary.builder()
                    .summaryText("输出 " + reportLanguage + " / " + reportTemplate + " 报告")
                    .mode(defaultIfBlank(textOrNull(config, "mode"), "initial"))
                    .reportLanguage(reportLanguage)
                    .reportTemplate(reportTemplate)
                    .sourceNode(textOrNull(config, "sourceNode"))
                    .build();
        }
        if (agentType == AgentType.REVIEWER) {
            String policy = defaultIfBlank(textOrNull(config, "qualityPolicy"), "标准质量评审");
            String sourceNode = textOrNull(config, "sourceNode");
            return TaskNodeConfigSummary.builder()
                    .summaryText(hasText(sourceNode) ? "评审策略：" + policy + "，复核节点：" + sourceNode : "评审策略：" + policy)
                    .qualityPolicy(policy)
                    .sourceNode(sourceNode)
                    .build();
        }
        return TaskNodeConfigSummary.builder()
                .summaryText(truncate(config.toString(), 180))
                .build();
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (hasText(value)) {
                items.add(value);
            }
        }
        return items;
    }

    private <T> T convertValue(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull() || type == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, type);
        } catch (IllegalArgumentException e) {
            log.warn("convert json value failed for type {}", type.getSimpleName(), e);
            return null;
        }
    }

    private <T> List<T> convertList(JsonNode node, TypeReference<List<T>> typeReference) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return Collections.emptyList();
        }
        try {
            List<T> values = objectMapper.convertValue(node, typeReference);
            return values == null ? Collections.emptyList() : values;
        } catch (IllegalArgumentException e) {
            log.warn("convert json list failed", e);
            return Collections.emptyList();
        }
    }

    private String sourceTypeLabel(String sourceType) {
        if (!hasText(sourceType)) {
            return "官网";
        }
        return switch (sourceType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DOCS" -> "文档";
            case "PRICING" -> "定价";
            case "NEWS" -> "资讯";
            case "REVIEW" -> "测评";
            case "OFFICIAL" -> "官网";
            default -> sourceType;
        };
    }

    private String searchModeLabel(String searchMode) {
        if (!hasText(searchMode)) {
            return "混合";
        }
        return switch (searchMode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BROWSER_ONLY" -> "仅浏览器";
            case "HTTP_ONLY" -> "仅 HTTP";
            case "HEURISTIC_ONLY" -> "仅规划候选";
            case "HYBRID" -> "混合";
            default -> searchMode;
        };
    }

    private String summarizeArray(JsonNode node, int limit) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        int count = 0;
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (!hasText(value)) {
                continue;
            }
            items.add(value);
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        if (node.size() > items.size()) {
            items.add("等" + node.size() + "项");
        }
        return String.join("、", items);
    }

    /**
     * 节点列表优先返回可读摘要，避免前端只能看到被截断的 JSON。
     */
    private String buildOutputSummary(TaskNode node) {
        if (node.getOutputData() == null || node.getOutputData().isBlank()) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        if (output == null) {
            return truncate(node.getOutputData(), 240);
        }

        if (node.getAgentType() == AgentType.COLLECTOR) {
            return buildCollectorOutputSummary(output);
        }
        if (node.getAgentType() == AgentType.REVIEWER) {
            return buildReviewerOutputSummary(output);
        }
        return truncate(node.getOutputData(), 240);
    }

    private String buildCollectorOutputSummary(JsonNode output) {
        String competitor = textOrNull(output, "competitor");
        String sourceType = textOrNull(output, "sourceType");
        int selectedCount = output.path("selectedTargets").isArray() ? output.path("selectedTargets").size() : 0;
        int successCollected = output.path("successCollected").asInt(0);
        int totalCollected = output.path("totalCollected").asInt(0);
        String supplementMethod = textOrNull(output.path("searchExecutionTrace"), "supplementMethod");
        String progressStatus = textOrNull(output.path("searchProgress"), "status");
        String degradationReason = textOrNull(output.path("searchExecutionTrace"), "degradationReason");

        StringBuilder summary = new StringBuilder();
        if (competitor != null) {
            summary.append(competitor);
        }
        if (sourceType != null) {
            if (!summary.isEmpty()) {
                summary.append(" / ");
            }
            summary.append(sourceType);
        }
        if (!summary.isEmpty()) {
            summary.append("：");
        }
        summary.append("选中 ").append(selectedCount)
                .append(" 条，采集成功 ").append(successCollected).append("/").append(totalCollected).append(" 条");
        if (supplementMethod != null) {
            summary.append("，补源方式=").append(supplementMethod);
        }
        if (progressStatus != null) {
            summary.append("，进度状态=").append(progressStatus);
        }
        if (degradationReason != null) {
            summary.append("，降级原因=").append(degradationReason);
        }
        return summary.toString();
    }

    private String buildReviewerOutputSummary(JsonNode output) {
        boolean passed = output.path("passed").asBoolean(false);
        int score = output.path("score").asInt(-1);
        int issueCount = output.path("issues").isArray() ? output.path("issues").size() : 0;
        String summary = textOrNull(output, "summary");

        StringBuilder readable = new StringBuilder();
        readable.append(passed ? "评审通过" : "评审未通过");
        if (score >= 0) {
            readable.append("，评分 ").append(score);
        }
        readable.append("，问题数 ").append(issueCount);
        if (summary != null) {
            readable.append("，").append(summary);
        }
        return readable.toString();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private NodeExecutionRecoveryPolicy recoveryPolicy() {
        return new NodeExecutionRecoveryPolicy(objectMapper);
    }
}
