package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.CollectorNodeInsightResponse;
import cn.bugstack.competitoragent.model.dto.CollectorSelectedTargetSummary;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeConfigSummary;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 浠诲姟搴旂敤鏈嶅姟锛岃礋璐ｅ垱寤恒€佹墽琛屻€侀噸璇曘€佺画璺戝拰鍒犻櫎浠诲姟锛屽苟缁存姢浠诲姟鍏宠仈浜х墿鐢熷懡鍛ㄦ湡銆? */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisTaskService {

    private static final int DEFAULT_TASK_LIST_PAGE_SIZE = 10;
    private static final int MAX_TASK_LIST_PAGE_SIZE = 50;
    private static final int TASK_LIST_ATTENTION_LIMIT = 4;

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final ReportRepository reportRepository;
    private final AgentExecutionLogRepository logRepository;
    private final AiCallAuditRecordRepository aiCallAuditRecordRepository;
    private final WorkflowFactory workflowFactory;
    private final AnalysisTaskRunner taskRunner;
    private final TaskRecoveryService taskRecoveryService;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowEventOutboxService workflowEventOutboxService;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        /* 组织级并发治理必须在任务落库前完成，避免把治理阻断误记成普通任务失败。 */
        ensureTaskCreationAllowed(request);
        /* 任务创建阶段只固化入参与工作流，不在这里直接执行业务节点。 */
        AnalysisTask task = AnalysisTask.builder()
                .taskName(request.getTaskName())
                .subjectProduct(request.getSubjectProduct())
                .competitorNames(toJson(request.getCompetitorNames()))
                .competitorUrls(toJson(request.getCompetitorUrls()))
                .analysisDimensions(toJson(request.getAnalysisDimensions()))
                .sourceScope(toJson(request.getSourceScope()))
                .reportLanguage(defaultIfBlank(request.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准模板"))
                .schemaId(request.getSchemaId())
                .status(AnalysisTaskStatus.PENDING)
                .build();

        task = taskRepository.save(task);
        workflowFactory.createWorkflow(task);
        task = taskRepository.save(task);
        refreshTaskSnapshot(task.getId());
        workflowEventPublisher.publishTaskCreated(task);

        log.info("create analysis task success, taskId={}, taskName={}", task.getId(), task.getTaskName());
        return toTaskResponse(task);
    }

    /**
     * 浠诲姟鍒涘缓鍓嶅厛璧扮粺涓€缁勭粐绾ч厤棰濆垽鏂€?     * 鍙湁鏄惧紡鎷垮埌鍏佽缁撴灉鍚庯紝鎵嶇户缁繘鍏ヤ换鍔℃寔涔呭寲涓庡伐浣滄祦鍒涘缓閾捐矾銆?     */
    private void ensureTaskCreationAllowed(CreateTaskRequest request) {
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.TASK_SCOPE,
                GovernanceDefaults.TASK_CONCURRENCY_KEY,
                1,
                request == null ? List.of() : request.getCompetitorUrls()
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
    }

    /**
     * 浠诲姟鍒楄〃姝ｅ紡鍒嗛〉鍝嶅簲銆?     * 杩欓噷鍚屾椂杩斿洖锛?     * 1. 褰撳墠椤?items锛氱粰琛ㄦ牸鐩存帴娑堣垂锛?     * 2. attentionItems锛氱粰鍏ュ彛椤碘€滈渶瑕佸叧娉ㄢ€濆尯缁熶竴娑堣垂锛?     * 3. summary锛氱粰椤堕儴鎬昏鍜岃交閲忓埛鏂扮瓥鐣ユ彁渚涚ǔ瀹氬垽鏂緷鎹€?     *
     * 杩欐牱鍓嶇灏变笉闇€瑕佸啀鐢ㄢ€滃厛鎷夊叏閲忋€佸啀鎴柇銆佸啀鍚勮嚜鎺掑簭鈥濈殑鏂瑰紡鎷艰涓嶅悓鍖哄煙璇箟銆?     */
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
        return toTaskResponse(getTaskOrThrow(taskId));
    }

    private AnalysisTaskStatus parseTaskStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnalysisTaskStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "Unsupported task status: " + status);
        }
    }

    private List<AnalysisTask> listMatchedTasks(AnalysisTaskStatus status) {
        return status == null
                ? taskRepository.findAllByOrderByCreatedAtDesc()
                : taskRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    private Page<AnalysisTask> listMatchedTaskPage(AnalysisTaskStatus status, PageRequest pageRequest) {
        return status == null
                ? taskRepository.findAllByOrderByCreatedAtDesc(pageRequest)
                : taskRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
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

    /**
     * attentionItems 浠ｈ〃榛樿鍏ュ彛椤垫渶鍊煎緱绔嬪嵆鍏虫敞鐨勫璞★細
     * 澶辫触 > 宸插仠姝?> 杩愯涓紝骞跺湪鍚屼紭鍏堢骇鍐呮寜鏈€杩戞洿鏂版椂闂村€掑簭鎺掑垪銆?     * 杩欐牱鍒楄〃椤典笌鍚庣画椤甸潰閮藉洿缁曞悓涓€浠藉叧娉ㄨ涔夋秷璐癸紝涓嶅啀鍚勮嚜瀹炵幇涓€濂楁帓搴忚鍒欍€?     */
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

    private TaskListSummaryResponse buildTaskListSummary(List<TaskResponse> taskResponses) {
        int total = taskResponses.size();
        int running = 0;
        int success = 0;
        int failed = 0;
        int stopped = 0;
        int progressSum = 0;

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

        return TaskListSummaryResponse.builder()
                .total(total)
                .running(running)
                .success(success)
                .failed(failed)
                .stopped(stopped)
                .avgProgress(total == 0 ? 0 : Math.round(progressSum / (float) total))
                .build();
    }

    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        AnalysisTaskStatus resolvedStatus = recoveryPolicy().resolveTaskExecution(task, nodes).getStatus();
        Map<String, List<TaskNode>> rerunImpactMap = buildRerunImpactMap(nodes);
        Map<Long, Integer> planVersionMap = buildPlanVersionMap(taskId);
        return nodes
                .stream()
                .map(node -> toNodeResponse(
                        node,
                        resolvedStatus,
                        rerunImpactMap.getOrDefault(node.getNodeName(), List.of()),
                        planVersionMap))
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
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准模板"))
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

        if (task.getStatus() == AnalysisTaskStatus.FAILED || task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            resetTaskForExecution(task);
        }

        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(null);
        taskRepository.save(task);
        refreshTaskSnapshot(taskId);

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
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(null);
        taskRepository.save(task);
        refreshTaskSnapshot(taskId);

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
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(null);
        taskRepository.save(task);
        refreshTaskSnapshot(taskId);

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

        prepareTaskForResume(task);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(null);
        taskRepository.save(task);
        refreshTaskSnapshot(taskId);

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
        node.setErrorMessage("节点已暂停");
        node.setInterventionReason("节点已暂停");
        node.setControlState(TaskNodeControlState.NONE);
        nodeRepository.save(node);
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishNodeStatusEvent(taskId, node, "NODE_PAUSED");

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
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishNodeStatusEvent(taskId, node, "NODE_RESUMED");

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

        markNodeSkippedByUser(node, "");
        nodeRepository.save(node);
        continueTaskIfNecessary(task);
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishNodeStatusEvent(taskId, node, "NODE_SKIPPED");

        log.info("node skip requested, taskId={}, nodeName={}, taskStatus={}", taskId, nodeName, task.getStatus());
    }

    @Transactional
    public void terminateNode(Long taskId, String nodeName) {
        AnalysisTask task = getTaskOrThrow(taskId);
        TaskNode node = getNodeOrThrow(taskId, nodeName);

        if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED) {
            markNodeSkippedByUser(node, "");
            nodeRepository.save(node);
            continueTaskIfNecessary(task);
            refreshTaskSnapshot(taskId);
            taskEventPublisher.publishNodeStatusEvent(taskId, node, "NODE_TERMINATED");
            log.info("node terminated before execution, taskId={}, nodeName={}, taskStatus={}",
                    taskId, nodeName, task.getStatus());
            return;
        }

        if (node.getStatus() != TaskNodeStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "Only pending, paused or running nodes can be terminated. Current status: " + node.getStatus());
        }

        node.setControlState(TaskNodeControlState.TERMINATE_REQUESTED);
        node.setInterventionReason("节点已暂停");
        nodeRepository.save(node);
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishNodeStatusEvent(taskId, node, "NODE_TERMINATE_REQUESTED");

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
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(java.time.LocalDateTime.now());
        taskRepository.save(task);
        taskRecoveryService.markStoppedNodes(taskId);
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishTaskStatusEvent(taskId, AnalysisTaskStatus.STOPPED, "任务已停止", task.getErrorMessage());

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
        taskSnapshotCacheService.evictTaskRuntime(taskId);
        log.info("delete task success, taskId={}", taskId);
    }

    private void resetTaskForExecution(AnalysisTask task) {
        Long taskId = task.getId();
        deleteGeneratedData(taskId);
        taskSnapshotCacheService.evictTaskRuntime(taskId);

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        for (TaskNode node : nodes) {
            recoveryPolicy().resetNodeForRerun(node, true);
        }
        nodeRepository.saveAll(nodes);
    }

    /**
     * 缁窇鍙噸缃湭瀹屾垚鑺傜偣锛屼繚鐣欐垚鍔熻妭鐐圭殑杈撳叆杈撳嚭鍜屼笅娓稿彲澶嶇敤浜х墿銆?     */
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
                // 宸叉垚鍔熻妭鐐逛繚鐣欑幇鍦猴紝渚涙墽琛屽櫒缁窇鏃堕噸鏂版敞鍏ュ叡浜笂涓嬫枃銆?                hasSuccessfulCheckpoint = true;
                continue;
            }

            if (taskRecoveryService.applyCompensationIfRequired(node)) {
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
        node.setErrorMessage("节点已暂停");
        node.setInterventionReason("节点已暂停");
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
        task.setErrorMessage("任务已停止");
                task.setCompletedAt(resolvedCompletedAt);
                taskRepository.save(task);
            }
            return;
        }
        if (task.getStatus() == AnalysisTaskStatus.RUNNING || task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            return;
        }
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage("任务已停止");
        task.setCompletedAt(null);
        taskRepository.save(task);
        refreshTaskSnapshot(task.getId());
        runAfterCommit(task.getId());
    }

    /**
     * 鍒犻櫎鏁翠换鍔￠噸璺戞墍闇€娓呯┖鐨勬淳鐢熶骇鐗╋紝淇濊瘉浠诲姟瑙嗚涓嬫暟鎹竴鑷淬€?     */
    private List<TaskNode> collectAffectedNodes(List<TaskNode> nodes, String startNodeName) {
        TaskNode startNode = nodes.stream()
                .filter(node -> node.getNodeName().equals(startNodeName))
                .findFirst()
                .orElse(null);
        return dynamicTaskGraphService.calculateAffectedNodes(nodes, startNode);
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
     * 閲囬泦鑺傜偣閲嶈窇鎴栫画璺戞椂锛屾妸涓婃鎼滅储瀹¤蹇収閲嶆柊鍐欏洖鑺傜偣閰嶇疆锛?     * 璁?CollectorAgent 鑳戒紭鍏堝鐢ㄥ€欓€変笌閫夋簮鐜板満锛岃€屼笉鏄瘡娆￠兘閲嶆柊鍋氬畬鏁存悳绱€?     */
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
     * 褰撳垵瀹¤姹備汉宸ヤ粙鍏ュ悗锛岀敤鎴疯Е鍙?resume 浠ｈ〃宸茬粡纭鍙互缁х画闂幆銆?     * 杩欓噷鎶婄‘璁や俊鍙峰啓鍥炲緟鎵ц鑺傜偣閰嶇疆锛屼緵 DAG 鏉′欢鍒ゆ柇鏀捐 rewrite_report銆?     */
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
        workflowEventOutboxService.assertWorkflowIngressReady();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
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
        Optional<TaskProgressSnapshot> snapshotOptional = taskRecoveryService.getTaskSnapshotOrRebuild(task.getId());
        TaskProgressSnapshot snapshot = snapshotOptional.orElse(null);

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
                .statusSummary(snapshot == null ? buildTaskStatusSummary(nodes) : snapshot.getStatusSummary())
                .currentPlanVersionId(task.getCurrentPlanVersionId())
                .currentPlanVersion(task.getCurrentPlanVersion())
                .totalNodes(snapshot == null ? resolution.getTotalNodes() : snapshot.getTotalNodes())
                .completedNodes(snapshot == null ? resolution.getCompletedNodes() : snapshot.getCompletedNodes())
                .waitingRetryNodeCount(snapshot == null ? countNodesByStatus(nodes, TaskNodeStatus.WAITING_RETRY) : snapshot.getWaitingRetryNodeCount())
                .waitingInterventionNodeCount(snapshot == null ? countWaitingInterventionNodes(nodes) : snapshot.getWaitingInterventionNodeCount())
                .compensatedNodeCount(snapshot == null ? countNodesByStatus(nodes, TaskNodeStatus.COMPENSATED) : snapshot.getCompensatedNodeCount())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(resolution.resolveCompletedAt(task.getCompletedAt()))
                .canExecute(canExecuteTask(resolvedStatus))
                .canResume(canResumeTask(resolvedStatus))
                .canRetry(canRetryTask(resolvedStatus))
                .canStop(canStopTask(resolvedStatus))
                .canViewReport(resolvedStatus == AnalysisTaskStatus.SUCCESS)
                .interventionSummary("预览阶段仅展示规划结果。")
                .resumeAdvice(buildTaskResumeAdvice(resolvedStatus))
                .retryAdvice(buildTaskRetryAdvice(resolvedStatus))
                .replayEntrySummary(buildTaskReplayEntrySummary(resolvedStatus))
                .currentStage(snapshot == null ? buildDefaultCurrentStage(nodes, resolvedStatus) : snapshot.getCurrentStage())
                .activeNodeNames(snapshot == null ? buildActiveNodeNames(nodes) : snapshot.getActiveNodeNames())
                .snapshotUpdatedAt(snapshot == null ? null : snapshot.getUpdatedAt())
                .eventStreamPath(buildEventStreamPath(task.getId()))
                .build();
    }

    /**
     * 鎶婃暟鎹簱浠诲姟浜嬪疄鍘嬬缉鎴愪竴浠?Redis 蹇収銆?     * 璇ユ柟娉曞湪浠诲姟鍒涘缓銆佹帶鍒跺姩浣滃拰閲嶈窇閲嶇疆鍚庨兘浼氳皟鐢紝淇濊瘉 Redis 涓庢暟鎹簱涓荤姸鎬佸熀鏈悓姝ャ€?     */
    private void refreshTaskSnapshot(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy().resolveTaskExecution(task, nodes);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    resolution.getStatus(),
                    resolution.getErrorMessage(),
                    nodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }

    private String buildDefaultCurrentStage(List<TaskNode> nodes, AnalysisTaskStatus status) {
        return TaskProgressSnapshot.fromTask(
                AnalysisTask.builder().id(-1L).build(),
                status,
                null,
                nodes).getCurrentStage();
    }

    private List<String> buildActiveNodeNames(List<TaskNode> nodes) {
        List<String> activeNodeNames = new ArrayList<>();
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.RUNNING
                    || node.getStatus() == TaskNodeStatus.PAUSED
                    || node.getStatus() == TaskNodeStatus.WAITING_RETRY
                    || node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                    || node.getStatus() == TaskNodeStatus.READY
                    || node.getStatus() == TaskNodeStatus.DISPATCHED) {
                activeNodeNames.add(node.getNodeName());
            }
        }
        return activeNodeNames;
    }

    private boolean isTerminalStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.FAILED
                || status == TaskNodeStatus.SKIPPED
                || status == TaskNodeStatus.COMPENSATED;
    }

    private Integer countNodesByStatus(List<TaskNode> nodes, TaskNodeStatus targetStatus) {
        if (nodes == null || nodes.isEmpty() || targetStatus == null) {
            return 0;
        }
        int count = 0;
        for (TaskNode node : nodes) {
            if (node.getStatus() == targetStatus) {
                count++;
            }
        }
        return count;
    }

    private Integer countWaitingInterventionNodes(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION || node.getStatus() == TaskNodeStatus.PAUSED) {
                count++;
            }
        }
        return count;
    }

    private String buildTaskStatusSummary(List<TaskNode> nodes) {
        int waitingInterventionCount = countWaitingInterventionNodes(nodes);
        if (waitingInterventionCount > 0) {
            return "存在等待人工处理的节点";
        }
        int waitingRetryCount = countNodesByStatus(nodes, TaskNodeStatus.WAITING_RETRY);
        if (waitingRetryCount > 0) {
            return "等待调度";
        }
        int compensatedCount = countNodesByStatus(nodes, TaskNodeStatus.COMPENSATED);
        if (compensatedCount > 0) {
            return "閮ㄥ垎鑺傜偣宸查€氳繃琛ュ伩鏀跺彛";
        }
        return null;
    }

    private String buildNodeStatusSummary(TaskNode node) {
        if (node == null || node.getStatus() == null) {
            return null;
        }
        return switch (node.getStatus()) {
            case WAITING_RETRY -> "等待重试";
            case WAITING_INTERVENTION -> "";
            case COMPENSATED -> "";
            case READY -> "";
            case DISPATCHED -> "节点已派发";
            case RUNNING -> "";
            case FAILED -> "";
            case SUCCESS -> "";
            case SKIPPED -> "节点已跳过";
            case PAUSED -> "节点已暂停";
            case PENDING -> "待执行";
        };
    }

    private TaskNodeResponse toNodeResponse(TaskNode node,
                                            AnalysisTaskStatus taskStatus,
                                            List<TaskNode> affectedNodes,
                                            Map<Long, Integer> planVersionMap) {
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
                .failureCategory(node.getFailureCategory())
                .status(node.getStatus())
                .controlState(node.getControlState())
                .errorMessage(node.getErrorMessage())
                .interventionReason(node.getInterventionReason())
                .executionOrder(node.getExecutionOrder())
                .planVersionId(node.getPlanVersionId())
                .planVersion(resolvePlanVersion(planVersionMap, node.getPlanVersionId()))
                .branchKey(node.getBranchKey())
                .dynamicNode(node.isDynamicNode())
                .originNodeName(node.getOriginNodeName())
                .inputSummary(truncate(node.getInputData(), 240))
                .outputSummary(buildOutputSummary(node))
                .aiGovernanceSummary(buildAiGovernanceSummary(node))
                .statusSummary(buildNodeStatusSummary(node))
                .inputData(node.getInputData())
                .outputData(node.getOutputData())
                .allowFailedDependency(node.isAllowFailedDependency())
                .startedAt(node.getStartedAt())
                .completedAt(node.getCompletedAt())
                .lastAttemptAt(node.getLastAttemptAt())
                .nextRetryAt(node.getNextRetryAt())
                .canRerun(canRerun)
                .canUpdateConfigAndRerun(canUpdateConfigAndRerun)
                .affectedNodeCount(affectedNodeNames.size())
                .affectedNodeNames(affectedNodeNames)
                .canReuseCheckpoint(canReuseCheckpoint)
                .canPause(canPause)
                .canResumeNode(canResumeNode)
                .canSkip(canSkip)
                .canTerminate(canTerminate)
                .eventKey(node.getNodeName())
                /* 节点干预摘要需要基于当前状态、受影响范围和可执行动作统一生成。 */
                .interventionSummary(buildNodeInterventionSummary(
                        node,
                        taskStatus,
                        affectedNodeNames,
                        canReuseCheckpoint,
                        canPause,
                        canResumeNode,
                        canSkip,
                        canTerminate))
                .rerunActionSummary(buildNodeRerunActionSummary(node, canRerun))
                .configRerunActionSummary(buildNodeConfigRerunActionSummary(node, canUpdateConfigAndRerun))
                .impactSummary(buildNodeImpactSummary(node, affectedNodeNames))
                .checkpointSummary(buildNodeCheckpointSummary(node, canReuseCheckpoint))
                .replayEntrySummary(buildNodeReplayEntrySummary(node))
                .build();
    }

    /**
     * 棰勮鑺傜偣娌℃湁鐪熷疄鎵ц杈撳叆杈撳嚭锛岃繖閲屽彧鎶婅鍒掔粨鏋滆浆鎹㈡垚鍓嶇鍙睍绀虹粨鏋勩€?     */
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
                .planVersionId(null)
                .planVersion(null)
                .branchKey(node.getBranchKey())
                .dynamicNode(node.isDynamicNode())
                .originNodeName(node.getOriginNodeName())
                .inputSummary(node.getNodeConfig())
                .aiGovernanceSummary(null)
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
                .eventKey(node.getNodeName())
                .interventionSummary("预览阶段仅展示规划结果。")
                .build();
    }

    /**
     * 浠诲姟璇︽儏鎺ュ彛鐩存帴缁欏嚭 SSE 璁㈤槄鍦板潃锛屽墠绔棤闇€鍐嶈嚜琛屾嫾瑁呬富瑙傚療閫氶亾銆?     */
    private String buildEventStreamPath(Long taskId) {
        return taskId == null ? null : "/api/task/" + taskId + "/events";
    }

    private Map<String, List<TaskNode>> buildRerunImpactMap(List<TaskNode> nodes) {
        Map<String, List<TaskNode>> rerunImpactMap = new HashMap<>();
        for (TaskNode node : nodes) {
            rerunImpactMap.put(node.getNodeName(), collectAffectedNodes(nodes, node.getNodeName()));
        }
        return rerunImpactMap;
    }

    private Map<Long, Integer> buildPlanVersionMap(Long taskId) {
        if (taskId == null) {
            return Map.of();
        }
        Map<Long, Integer> planVersionMap = new HashMap<>();
        List<cn.bugstack.competitoragent.model.entity.TaskPlan> plans =
                taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(taskId);
        if (plans == null || plans.isEmpty()) {
            return planVersionMap;
        }
        for (cn.bugstack.competitoragent.model.entity.TaskPlan plan : plans) {
            if (plan.getId() != null && plan.getPlanVersion() != null) {
                planVersionMap.put(plan.getId(), plan.getPlanVersion());
            }
        }
        return planVersionMap;
    }

    private Integer resolvePlanVersion(Map<Long, Integer> planVersionMap, Long planVersionId) {
        if (planVersionId == null || planVersionMap == null) {
            return null;
        }
        return planVersionMap.get(planVersionId);
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
            return "";
        }
        if (status == AnalysisTaskStatus.FAILED) {
            return "";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "";
        }
        if (status == AnalysisTaskStatus.SUCCESS) {
            return "";
        }
        return "";
    }

    /**
     * 浠诲姟绾ф仮澶嶅缓璁粯璁ら潰鍚戔€滀繚鐣欏凡鏈夋垚鏋滅户缁蛋鈥濈殑鍦烘櫙銆?     * 杩欓噷鍗曠嫭杈撳嚭涓氬姟璇存槑锛岄伩鍏嶅墠绔妸鈥滄仮澶嶆墽琛屸€濈户缁睍绀烘垚绾妧鏈寜閽€?     */
    private String buildTaskResumeAdvice(AnalysisTaskStatus status) {
        if (status == AnalysisTaskStatus.FAILED) {
            return "";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "";
        }
        return null;
    }

    /**
     * 鏁翠换鍔￠噸缃彧鍦ㄥけ璐ユ€佸紑鏀撅紝鐢ㄤ簬鐢ㄦ埛鏄庣‘甯屾湜浠庡ご閲嶈蛋鍏ㄩ摼璺殑鍦烘櫙銆?     */
    private String buildTaskRetryAdvice(AnalysisTaskStatus status) {
        if (status != AnalysisTaskStatus.FAILED) {
            return null;
        }
        return "";
    }

    /**
     * 杩借釜涓庡洖鏀惧叆鍙ｈ鏄庣粺涓€鐢卞悗绔粰鍑猴紝鍓嶇鍙礋璐ｆ寜涓昏矾寰勫睍绀猴紝
     * 閬垮厤涓嶅悓椤甸潰鍚勮嚜鍙戞槑涓€濂椻€滃幓鍝噷鐪嬪師濮嬭褰曗€濈殑鎻愮ず鏂囨銆?     */
    private String buildTaskReplayEntrySummary(AnalysisTaskStatus status) {
        if (status != AnalysisTaskStatus.FAILED && status != AnalysisTaskStatus.STOPPED && status != AnalysisTaskStatus.RUNNING) {
            return null;
        }
        return "";
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
            return "";
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "";
        }
        if (node.getStatus() == TaskNodeStatus.RUNNING) {
            return canTerminate
                    ? ""
                    : "";
        }
        int downstreamCount = Math.max(affectedNodeNames.size() - 1, 0);
        StringBuilder summary = new StringBuilder();
        if (downstreamCount > 0) {
            summary.append("及 ").append(downstreamCount).append(" 个下游节点");
        }
        summary.append("其余未受影响成果会被保留。");
        if (node.getAgentType() == AgentType.COLLECTOR) {
            summary.append(canReuseCheckpoint
                    ? ""
                    : "");
        }
        if (canPause) {
            summary.append("");
        }
        if (canResumeNode) {
            summary.append("");
        }
        if (canSkip) {
            summary.append("");
        }
        if (canTerminate) {
            summary.append("");
        }
        if (taskStatus == AnalysisTaskStatus.RUNNING) {
            summary.append("");
        }
        return summary.toString();
    }

    /**
     * 鑺傜偣閲嶈窇璇存槑寮鸿皟鈥滀粈涔堟椂鍊欑敤鈥濓紝甯姪鐢ㄦ埛鍖哄垎灞€閮ㄩ噸璺戜笌鏁翠换鍔℃仮澶嶃€?     */
    private String buildNodeRerunActionSummary(TaskNode node, boolean canRerun) {
        if (node == null || !canRerun) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return null;
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return "";
        }
        return "";
    }

    /**
     * 鏀归厤缃悗閲嶈窇鏄洿楂樻垚鏈殑灞€閮ㄩ噸璺戯紝闇€瑕佹槑纭憡璇夌敤鎴峰畠閫傚悎鈥滃厛璋冩暣绛栫暐鍐嶇户缁€濈殑鍦烘櫙銆?     */
    private String buildNodeConfigRerunActionSummary(TaskNode node, boolean canUpdateConfigAndRerun) {
        if (node == null || !canUpdateConfigAndRerun) {
            return null;
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return "";
        }
        return "";
    }

    /**
     * 褰卞搷鑼冨洿璇存槑鐩存帴缁欎笟鍔＄敤鎴疯В閲娾€滆繖涓€鍒€浼氬垏鍒板摢閲屸€濓紝
     * 閬垮厤璁╃敤鎴疯嚜宸辨牴鎹?DAG 渚濊禆鍥炬帹绠楀摢浜涜妭鐐逛細澶辨晥銆?     */
    private String buildNodeImpactSummary(TaskNode node, List<String> affectedNodeNames) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "";
        }
        if (affectedNodeNames == null || affectedNodeNames.isEmpty()) {
            return "";
        }
        /* 影响摘要只返回面向用户的下游影响说明，不暴露内部 DAG 细节。 */
        return "本次操作将影响 " + affectedNodeNames.size() + " 个节点：" + String.join("、", affectedNodeNames) + "。";
    }

    /**
     * 妫€鏌ョ偣璇存槑缁熶竴杈撳嚭鎴愪笟鍔¤瑷€锛屽墠绔棤闇€鍐嶅幓鐚?canReuseCheckpoint 瀵圭敤鎴锋剰鍛崇潃浠€涔堛€?     */
    private String buildNodeCheckpointSummary(TaskNode node, boolean canReuseCheckpoint) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "";
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return canReuseCheckpoint
                    ? ""
                    : "";
        }
        return "";
    }

    /**
     * 鍥炴斁鍏ュ彛涓嶆槸鏂扮殑鎵ц鍔ㄤ綔锛岃€屾槸鈥滃幓鍝噷鐪嬭瘉鎹€濈殑璇存槑銆?     * 杩欓噷缁熶竴鏀跺彛涓鸿妭鐐硅拷韪?-> 楂樼骇璇婃柇锛岄伩鍏嶇敤鎴风洿鎺ユ帀杩涘師濮?JSON銆?     */
    private String buildNodeReplayEntrySummary(TaskNode node) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "";
        }
        return "";
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
                    .summaryText("")
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
                    .summaryText("")
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
                .taskRagContext(textOrNull(output, "taskRagContext"))
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
                    .append(" 路 ")
                    .append(sourceTypeLabel)
                    .append("")
                    .append(" · 搜索模式：")
                    .append(searchModeLabel)
                    .append(" 路 鍊欓€?")
                    .append(candidateCount)
                    .append(" 条");
            if (queryCount > 0) {
                summary.append(" 路 Query ")
                        .append(queryCount)
                        .append(" 条");
            }
            if (stepCount > 0) {
                summary.append(" 路 璁″垝 ")
                        .append(stepCount)
                        .append(" 步");
            }
            summary.append(" 路 娴忚鍣ㄨˉ婧愶細")
                    .append(browserEnabled ? "开启" : "关闭")
                    .append(" · 结果页验证：")
                    .append(verificationEnabled ? "开启" : "关闭");
            return TaskNodeConfigSummary.builder()
                    .summaryText("")
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
                    .summaryText("")
                    .dimensions(dimensions)
                    .build();
        }
        if (agentType == AgentType.ANALYZER) {
            int competitorCount = config.path("competitorCount").asInt(0);
            int dimensionCount = config.path("dimensionCount").asInt(0);
            return TaskNodeConfigSummary.builder()
                    .summaryText("")
                    .competitorCount(competitorCount)
                    .dimensionCount(dimensionCount)
                    .build();
        }
        if (agentType == AgentType.WRITER) {
            boolean revision = "revision".equalsIgnoreCase(config.path("mode").asText(""));
            String reportLanguage = defaultIfBlank(textOrNull(config, "reportLanguage"), "中文");
            String reportTemplate = defaultIfBlank(textOrNull(config, "reportTemplate"), "标准模板");
            if (revision) {
                return TaskNodeConfigSummary.builder()
                        .summaryText("")
                        .mode("revision")
                        .reportLanguage(reportLanguage)
                        .reportTemplate(reportTemplate)
                        .sourceNode(textOrNull(config, "sourceNode"))
                        .build();
            }
            return TaskNodeConfigSummary.builder()
                    .summaryText("")
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
                    .summaryText("")
                    .qualityPolicy(policy)
                    .sourceNode(sourceNode)
                    .build();
        }
        return TaskNodeConfigSummary.builder()
                .summaryText("")
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
            case "BROWSER_ONLY" -> "";
            case "HTTP_ONLY" -> "";
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
            /* 数组摘要超过展示上限时，用“等 N 项”提示仍有剩余内容。 */
            items.add("等" + node.size() + "项");
        }
        return String.join("、", items);
    }

    /**
     * 鑺傜偣鍒楄〃浼樺厛杩斿洖鍙鎽樿锛岄伩鍏嶅墠绔彧鑳界湅鍒拌鎴柇鐨?JSON銆?     */
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

    /**
     * 鑺傜偣璇︽儏涓昏矾寰勫彧杩斿洖鐢ㄦ埛鍙娌荤悊鎽樿锛?     * 涓嶆妸搴曞眰 SDK 鍙傛暟銆佽矾鐢遍敭鎴栧師濮嬪紓甯告爤鐩存帴鏆撮湶缁欏墠绔€?     */
    private String buildAiGovernanceSummary(TaskNode node) {
        if (node == null || node.getTaskId() == null || !StringUtils.hasText(node.getNodeName())) {
            return null;
        }
        Optional<AiCallAuditRecord> latestAudit = aiCallAuditRecordRepository
                .findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(node.getTaskId(), node.getNodeName());
        if (latestAudit.isEmpty()) {
            return null;
        }
        AiCallAuditRecord record = latestAudit.get();
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(record.getSummary())) {
            parts.add(record.getSummary());
        }
        if (record.getTotalTokens() != null && record.getTotalTokens() > 0) {
            parts.add("Token 鎬婚噺=" + record.getTotalTokens());
        }
        return parts.isEmpty() ? null : String.join("；", parts);
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
        summary.append("閫変腑 ").append(selectedCount)
                .append(" 条，采集成功 ").append(successCollected).append("/").append(totalCollected).append(" 条");
        if (supplementMethod != null) {
            summary.append("锛岃ˉ婧愭柟寮?").append(supplementMethod);
        }
        if (progressStatus != null) {
            summary.append("锛岃繘搴︾姸鎬?").append(progressStatus);
        }
        if (degradationReason != null) {
            summary.append("锛岄檷绾у師鍥?").append(degradationReason);
        }
        return summary.toString();
    }

    private String buildReviewerOutputSummary(JsonNode output) {
        boolean passed = output.path("passed").asBoolean(false);
        int score = output.path("score").asInt(-1);
        int issueCount = output.path("issues").isArray() ? output.path("issues").size() : 0;
        String summary = textOrNull(output, "summary");

        StringBuilder readable = new StringBuilder();
        readable.append(passed ? "璇勫閫氳繃" : "璇勫鏈€氳繃");
        if (score >= 0) {
            readable.append("锛岃瘎鍒?").append(score);
        }
        readable.append("锛岄棶棰樻暟 ").append(issueCount);
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

