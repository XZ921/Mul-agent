package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.application.cleanup.TaskArtifactCleanupCoordinator;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 任务运行时命令应用服务。
 * <p>
 * 这一层统一承接“启动 / 重试 / 续跑 / 停止 / 节点人工干预”这类直接改变运行态的命令，
 * 保持 controller 仍然只依赖 AnalysisTaskService 门面，同时把真正的运行时编排逻辑从门面中抽离出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRuntimeCommandAppService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;
    private final AnalysisTaskRunner taskRunner;
    private final WorkflowEventOutboxService workflowEventOutboxService;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final TaskRecoveryService taskRecoveryService;
    private final TaskArtifactCleanupCoordinator taskArtifactCleanupCoordinator;
    private final TaskQuotaCoordinator taskQuotaCoordinator;
    private final ObjectMapper objectMapper;

    @Transactional
    public void executeTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_ALREADY_RUNNING, "taskId=" + taskId);
        }
        taskQuotaCoordinator.ensureTaskQuotaReserved(task);

        /*
         * 对失败或成功任务重新执行时，需要先清空已生成的派生数据与节点执行痕迹，
         * 避免新的运行轮次读取到旧结果，造成断点恢复语义和实际数据不一致。
         */
        if (task.getStatus() == AnalysisTaskStatus.FAILED || task.getStatus() == AnalysisTaskStatus.SUCCESS) {
            resetTaskForExecution(task);
        }

        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
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
        taskQuotaCoordinator.ensureTaskQuotaReserved(task);

        resetTaskForExecution(task);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
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
        taskQuotaCoordinator.ensureTaskQuotaReserved(task);

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task has no workflow nodes");
        }

        TaskNode targetNode = nodes.stream()
                .filter(node -> node.getNodeName().equals(nodeName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_STATUS_INVALID,
                        "Node not found in task: " + nodeName));

        /*
         * 这里必须只重置“目标节点及其真实受影响的下游分支”，
         * 这样既能保留未受影响分支的成功检查点，也不会误删其它分支已经产出的结果。
         */
        List<TaskNode> affectedNodes = collectAffectedNodes(nodes, targetNode.getNodeName());
        if (affectedNodes.isEmpty()) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID,
                    "No downstream nodes affected by rerun: " + nodeName);
        }

        taskArtifactCleanupCoordinator.cleanupNodeArtifacts(taskId, targetNode.getNodeName());
        for (TaskNode node : affectedNodes) {
            reuseSearchCheckpointIfPresent(node);
            resetNodeExecutionState(node, true);
        }
        nodeRepository.saveAll(affectedNodes);

        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
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
        taskQuotaCoordinator.ensureTaskQuotaReserved(task);

        prepareTaskForResume(task);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setErrorMessage(null);
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

        /*
         * 暂停只允许发生在尚未真正开始执行的节点上，
         * 这样可以避免强行打断外部 Agent 或工具执行过程带来的不可控副作用。
         */
        node.setStatus(TaskNodeStatus.PAUSED);
        node.setErrorMessage("节点已由用户暂停，等待恢复");
        node.setInterventionReason("节点已由用户暂停，等待恢复");
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

        markNodeSkippedByUser(node, "节点已由用户手动跳过");
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

        /*
         * 对尚未执行的节点，终止等价于用户确认跳过；
         * 对运行中的节点，只能写入协作式终止标记，交由执行器在安全边界完成收口。
         */
        if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED) {
            markNodeSkippedByUser(node, "节点已由用户强制终止");
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
        node.setInterventionReason("节点已收到终止请求，当前轮执行结束后将停止并丢弃本轮结果");
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
        task.setErrorMessage("任务已由用户主动停止");
        task.setCompletedAt(LocalDateTime.now());
        taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
        taskRepository.save(task);
        taskRecoveryService.markStoppedNodes(taskId);
        refreshTaskSnapshot(taskId);
        taskEventPublisher.publishTaskStatusEvent(
                taskId,
                AnalysisTaskStatus.STOPPED,
                "任务已由用户主动停止",
                task.getErrorMessage());

        log.info("task stop requested, taskId={}", taskId);
    }

    /**
     * 节点命令会改变任务是否还能继续自动推进。
     * 这里统一根据最新节点事实重算任务状态，必要时再触发后续调度，
     * 避免不同命令各自拼接一套不一致的任务状态流转规则。
     */
    private void continueTaskIfNecessary(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy().resolveTaskExecution(task, nodes);
        if (!recoveryPolicy().canAutoContinue(nodes)) {
            /*
             * 这里不仅要比较任务公开状态是否变化，
             * 还要比较“是否仍持有配额占位”这个持久化事实是否变化。
             * 否则任务已经进入终态、内存里也已释放占位，但因为 status 没变而不 save，
             * 下次读库时仍会把 taskQuotaReserved 误判为 true。
             */
            boolean quotaReservedBeforeRelease = task.isTaskQuotaReserved();
            releaseQuotaIfTaskReachedTerminalStatus(task, resolution.getStatus());
            LocalDateTime resolvedCompletedAt = resolution.resolveCompletedAt(task.getCompletedAt());
            if (task.getStatus() != resolution.getStatus()
                    || !Objects.equals(task.getErrorMessage(), resolution.getErrorMessage())
                    || !Objects.equals(task.getCompletedAt(), resolvedCompletedAt)
                    || quotaReservedBeforeRelease != task.isTaskQuotaReserved()) {
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
        refreshTaskSnapshot(task.getId());
        runAfterCommit(task.getId());
    }

    /**
     * 全量重跑前必须把“任务派生数据 + 运行时快照 + 所有节点执行态”一起复位，
     * 否则新的执行轮次会带着旧证据、旧知识、旧报告或者旧日志继续推进。
     */
    private void resetTaskForExecution(AnalysisTask task) {
        Long taskId = task.getId();
        taskArtifactCleanupCoordinator.cleanupTaskArtifacts(taskId);
        taskSnapshotCacheService.evictTaskRuntime(taskId);

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        for (TaskNode node : nodes) {
            resetNodeExecutionState(node, true);
        }
        nodeRepository.saveAll(nodes);
    }

    /**
     * 任务恢复要尽量保留成功检查点，只把未完成、失败、等待介入或需要补偿的节点重新纳入执行范围。
     * 这样既能减少重复工作，也能保证恢复后的节点状态与恢复建议保持一致。
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
            if (node.getStatus() == TaskNodeStatus.SUCCESS || node.getStatus() == TaskNodeStatus.COMPENSATED) {
                if (node.getStatus() == TaskNodeStatus.SUCCESS) {
                    hasSuccessfulCheckpoint = true;
                }
                continue;
            }

            if (taskRecoveryService.applyCompensationIfRequired(node)) {
                continue;
            }

            hasWorkToResume = true;
            reuseSearchCheckpointIfPresent(node);
            markManualResumeApprovalIfNecessary(node);
            resetNodeExecutionState(node, true);
        }

        if (!hasWorkToResume && hasSuccessfulCheckpoint) {
            throw new BusinessException(ResultCode.TASK_STATUS_INVALID, "Task already completed successfully");
        }

        nodeRepository.saveAll(nodes);
    }

    /**
     * 第一阶段先把 rerun / resume 的重置边界锁定在既有 planVersion 快照内。
     * 这里允许清空执行态字段，但不允许顺手抹掉节点已经绑定的计划版本与 nodeConfig 语义，
     * 避免后续恢复、审计或回放时把原本的 fallbackOrder / stageCode / 搜索计划误当成需要重新发明。
     */
    private void resetNodeExecutionState(TaskNode node, boolean clearOutput) {
        if (node == null) {
            return;
        }
        Long preservedPlanVersionId = node.getPlanVersionId();
        String preservedNodeConfig = node.getNodeConfig();
        recoveryPolicy().resetNodeForRerun(node, clearOutput);
        node.setPlanVersionId(preservedPlanVersionId);
        node.setNodeConfig(preservedNodeConfig);
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

    private List<TaskNode> collectAffectedNodes(List<TaskNode> nodes, String startNodeName) {
        TaskNode startNode = nodes.stream()
                .filter(node -> node.getNodeName().equals(startNodeName))
                .findFirst()
                .orElse(null);
        return dynamicTaskGraphService.calculateAffectedNodes(nodes, startNode);
    }

    /**
     * Collector 节点重跑前，尽量把上一次搜索审计快照回写到 nodeConfig，
     * 这样执行器就能从已确认的搜索检查点恢复，减少重复搜索与外部抓取成本。
     */
    private void reuseSearchCheckpointIfPresent(TaskNode node) {
        if (node == null
                || node.getAgentType() != AgentType.COLLECTOR
                || !hasText(node.getOutputData())
                || !hasText(node.getNodeConfig())) {
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
            ((ObjectNode) configNode).set("searchAuditCheckpoint", objectMapper.valueToTree(checkpoint));
            node.setNodeConfig(objectMapper.writeValueAsString(configNode));
        } catch (Exception e) {
            log.warn("reuse search checkpoint failed, nodeName={}", node.getNodeName(), e);
        }
    }

    /**
     * 人工恢复 rewrite_report 节点时，把“已人工确认恢复”标记回写到配置，
     * 让后续 DAG 分支和提示层可以明确识别这次续跑是带人工决策的恢复动作。
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
            ((ObjectNode) configNode).put("manualResumeApproved", true);
            node.setNodeConfig(objectMapper.writeValueAsString(configNode));
        } catch (Exception e) {
            log.warn("mark manual resume approval failed, nodeName={}", node.getNodeName(), e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 当节点级人工操作把任务重新收敛到终态时，也需要同步释放任务并发占位。
     * 这里只在真正进入 SUCCESS / FAILED / STOPPED 时释放，避免 PENDING 状态被误释放。
     */
    private void releaseQuotaIfTaskReachedTerminalStatus(AnalysisTask task, AnalysisTaskStatus resolvedStatus) {
        if (task == null || resolvedStatus == null) {
            return;
        }
        if (resolvedStatus == AnalysisTaskStatus.SUCCESS
                || resolvedStatus == AnalysisTaskStatus.FAILED
                || resolvedStatus == AnalysisTaskStatus.STOPPED) {
            taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
        }
    }

    /**
     * 每次运行时命令落库后都立即刷新快照，
     * 保证详情页、SSE 推送以及中断恢复入口看到的是同一份最新状态。
     */
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

    private TaskNode getNodeOrThrow(Long taskId, String nodeName) {
        return nodeRepository.findByTaskIdAndNodeName(taskId, nodeName)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_STATUS_INVALID,
                        "Node not found in task: " + nodeName));
    }

    private NodeExecutionRecoveryPolicy recoveryPolicy() {
        return new NodeExecutionRecoveryPolicy(objectMapper);
    }
}
