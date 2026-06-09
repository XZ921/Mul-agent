package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.dto.RecoveryCheckpointResponse;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryAdvice;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryAuditTrail;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryReleasePolicy;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryWindow;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.RecoveryCommand;
import cn.bugstack.competitoragent.workflow.RecoveryEngine;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务恢复服务。
 * <p>
 * 在服务启动时扫描处于 RUNNING 的任务，把中途异常中断的 RUNNING 节点回滚成 PENDING，
 * 再基于当前激活计划版本恢复正确的续跑作用域。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final AnalysisTaskRunner taskRunner;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final WorkflowEventOutboxService workflowEventOutboxService;
    private final RecoveryEngine recoveryEngine;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final TaskPlanRepository taskPlanRepository;
    private final TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository;
    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final RecoveryCheckpointService recoveryCheckpointService;
    private final NodeExecutionRecoveryPolicy recoveryPolicy = new NodeExecutionRecoveryPolicy();

    @Component
    @RequiredArgsConstructor
    static class RecoveryBootstrap implements ApplicationRunner {

        private final TaskRecoveryService recoveryService;

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            recoveryService.recoverInterruptedTasks();
        }
    }

    /**
     * 启动时恢复所有被异常中断的任务。
     */
    @Transactional
    public void recoverInterruptedTasks() {
        List<AnalysisTask> runningTasks = taskRepository.findAllByStatus(AnalysisTaskStatus.RUNNING);
        if (runningTasks.isEmpty()) {
            return;
        }

        for (AnalysisTask task : runningTasks) {
            boolean recoverable = resetInterruptedNodes(task.getId());
            if (!recoverable) {
                task.setStatus(AnalysisTaskStatus.FAILED);
                task.setCompletedAt(LocalDateTime.now());
                task.setErrorMessage("Task interrupted and cannot be resumed because no workflow nodes were found");
                taskRepository.save(task);
                taskSnapshotCacheService.saveTaskSnapshot(TaskProgressSnapshot.fromTask(
                        task,
                        task.getStatus(),
                        task.getErrorMessage(),
                        List.of()));
                continue;
            }

            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
            List<TaskNode> recoveryScopeNodes = resolveRecoveryScopeNodes(task, nodes);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy.resolveTaskExecution(task, recoveryScopeNodes);
            task.setStatus(resolution.getStatus());
            task.setCompletedAt(resolution.resolveCompletedAtForPersistence(task.getCompletedAt()));
            task.setErrorMessage(resolution.getStatus() == AnalysisTaskStatus.PENDING
                    ? "Recovered after service restart, resuming from node checkpoints"
                    : resolution.getErrorMessage());
            taskRepository.save(task);
            taskSnapshotCacheService.saveTaskSnapshot(TaskProgressSnapshot.fromTask(
                    task,
                    resolution.getStatus(),
                    task.getErrorMessage(),
                    recoveryScopeNodes));
            if (resolution.getStatus() == AnalysisTaskStatus.PENDING && recoveryPolicy.canAutoContinue(recoveryScopeNodes)) {
                runAfterCommit(task.getId());
                log.info("schedule interrupted task recovery, taskId={}", task.getId());
            }
        }
    }

    @Transactional
    public void markStoppedNodes(Long taskId) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED) {
                node.setStatus(TaskNodeStatus.SKIPPED);
                node.setControlState(TaskNodeControlState.NONE);
                node.setErrorMessage("任务已被用户主动停止");
                node.setInterventionReason(null);
                node.setCompletedAt(LocalDateTime.now());
            }
        }
        nodeRepository.saveAll(nodes);
        taskRepository.findById(taskId).ifPresent(task -> taskSnapshotCacheService.saveTaskSnapshot(
                TaskProgressSnapshot.fromTask(task, task.getStatus(), task.getErrorMessage(), resolveRecoveryScopeNodes(task, nodes))));
    }

    /**
     * 只回滚中断时仍停留在 RUNNING/PENDING 的节点，保留 SUCCESS 检查点供执行器续跑。
     */
    @Transactional
    public boolean resetInterruptedNodes(Long taskId) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            return false;
        }

        boolean changed = recoveryPolicy.resetInterruptedNodes(nodes);
        if (changed) {
            nodeRepository.saveAll(nodes);
        }
        taskRepository.findById(taskId).ifPresent(task -> taskSnapshotCacheService.saveTaskSnapshot(
                TaskProgressSnapshot.fromTask(task, task.getStatus(), task.getErrorMessage(), resolveRecoveryScopeNodes(task, nodes))));
        return true;
    }

    /**
     * 获取任务恢复快照。
     * 断线恢复时优先读 Redis 热快照；若快照丢失，则根据数据库权威状态按当前计划版本重建。
     */
    @Transactional(readOnly = true)
    public Optional<TaskProgressSnapshot> getTaskSnapshotOrRebuild(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Optional<TaskProgressSnapshot> cachedSnapshot = taskSnapshotCacheService.getTaskSnapshot(taskId);
        if (cachedSnapshot.isPresent()) {
            return cachedSnapshot;
        }
        return taskRepository.findById(taskId).map(task -> {
            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            List<TaskNode> recoveryScopeNodes = resolveRecoveryScopeNodes(task, nodes);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy.resolveTaskExecution(task, recoveryScopeNodes);
            TaskProgressSnapshot rebuiltSnapshot = TaskProgressSnapshot.fromTask(
                    task,
                    resolution.getStatus(),
                    resolution.getErrorMessage(),
                    recoveryScopeNodes);
            taskSnapshotCacheService.saveTaskSnapshot(rebuiltSnapshot);
            return rebuiltSnapshot;
        });
    }

    /**
     * resumeTask 遇到“需要补偿”的节点时，通过恢复引擎统一收口成 COMPENSATED。
     * 这样后续续跑仍然可以保留节点级补偿痕迹，而不是重新把它降回待执行。
     */
    public boolean applyCompensationIfRequired(TaskNode node) {
        if (node == null) {
            return false;
        }
        RecoveryCommand command = recoveryEngine.decideNextAction(node, List.of(), node.getFailureCategory());
        if (command.getActionType() != RecoveryCommand.ActionType.EXECUTE_COMPENSATION) {
            return false;
        }
        node.setStatus(command.getTargetStatus());
        node.setControlState(TaskNodeControlState.NONE);
        node.setNextRetryAt(null);
        node.setErrorMessage(command.getUserReadableSummary());
        node.setInterventionReason(command.getReason());
        node.setCompletedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 构建任务级结构化恢复建议。
     * <p>
     * 该方法把恢复窗口边界、可回放事件、占位释放规则和审计引用统一整理成正式对象，
     * 供回放接口和后续恢复控制接口复用。
     */
    @Transactional(readOnly = true)
    public TaskRecoveryAdvice buildRecoveryAdvice(Long taskId) {
        if (taskId == null) {
            return emptyRecoveryAdvice();
        }
        Optional<AnalysisTask> taskOptional = taskRepository.findById(taskId);
        if (taskOptional.isEmpty()) {
            return emptyRecoveryAdvice();
        }

        AnalysisTask task = taskOptional.get();
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            return emptyRecoveryAdvice();
        }

        TaskPlan activePlan = resolveActivePlan(task);
        List<TaskNode> recoveryScopeNodes = resolveRecoveryScopeNodes(task, nodes);
        TaskNode focusNode = selectFocusNode(recoveryScopeNodes);
        List<TaskNodeExecutionAttempt> attempts = resolveNodeAttempts(taskId, focusNode);
        List<TaskWorkflowEvent> replayableEvents = resolveReplayableEvents(taskId, activePlan);
        List<RecoveryCheckpointResponse> checkpoints = recoveryCheckpointService.listTaskCheckpoints(taskId);
        RecoveryCheckpointResponse preferredCheckpoint = selectPreferredCheckpoint(checkpoints, focusNode, activePlan);

        NodeFailureCategory failureCategory = focusNode == null ? null : focusNode.getFailureCategory();
        RecoveryCommand command = recoveryEngine.decideNextAction(
                focusNode,
                attempts,
                failureCategory,
                activePlan,
                replayableEvents);

        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (preferredCheckpoint != null) {
            sourceUrls.addAll(normalizeSourceUrls(preferredCheckpoint.getSourceUrls()));
        }
        for (TaskWorkflowEvent replayableEvent : replayableEvents) {
            sourceUrls.addAll(parseSourceUrls(replayableEvent.getSourceUrls()));
        }

        return TaskRecoveryAdvice.builder()
                .recommendedAction(resolveRecommendedAction(command))
                .summary(command.getUserReadableSummary())
                .blockingNodeNames(resolveBlockingNodeNames(recoveryScopeNodes))
                .recommendedCheckpointId(preferredCheckpoint == null ? null : preferredCheckpoint.getId())
                .recommendedCheckpointKey(preferredCheckpoint == null ? null : preferredCheckpoint.getCheckpointKey())
                .resumeSupported(preferredCheckpoint != null)
                .recoveryWindow(TaskRecoveryWindow.builder()
                        .windowScope(command.getRecoveryWindowScope())
                        .planVersionId(command.getRecoveryPlanVersionId())
                        .branchKey(command.getRecoveryBranchKey())
                        .boundaryNodeNames(command.getBoundaryNodeNames())
                        .replayableEventIds(command.getReplayableEventIds())
                        .windowStartAt(command.getRecoveryWindowStartAt())
                        .windowEndAt(command.getRecoveryWindowEndAt())
                        .build())
                .releasePolicy(TaskRecoveryReleasePolicy.builder()
                        .releaseTaskExecutionLock(command.isReleaseTaskExecutionLock())
                        .releaseNodeExecutionLocks(command.isReleaseNodeExecutionLocks())
                        .releaseReason(command.getReleaseReason())
                        .build())
                .auditTrail(TaskRecoveryAuditTrail.builder()
                        .decisionSource(command.getDecisionSource())
                        .planVersionId(command.getRecoveryPlanVersionId())
                        .triggerEventId(command.getTriggerEventId())
                        .latestAttemptId(command.getLatestAttemptId())
                        .latestAttemptNo(command.getLatestAttemptNo())
                        .build())
                .sourceUrls(List.copyOf(sourceUrls))
                .build();
    }

    /**
     * 动态任务图恢复时只看“当前激活计划版本真正影响到的执行链路”。
     * 这样老分支上遗留的暂停 / 失败状态不会阻断当前激活分支的自动恢复。
     */
    private List<TaskNode> resolveRecoveryScopeNodes(AnalysisTask task, List<TaskNode> nodes) {
        if (task == null || nodes == null || nodes.isEmpty() || task.getCurrentPlanVersionId() == null) {
            return nodes == null ? List.of() : nodes;
        }

        Optional<TaskPlan> currentPlanOptional = taskPlanRepository.findById(task.getCurrentPlanVersionId());
        if (currentPlanOptional.isEmpty()) {
            return nodes;
        }

        TaskPlan currentPlan = currentPlanOptional.get();
        if (currentPlan.getParentPlanId() == null) {
            return nodes;
        }

        TaskNode triggerNode = findTriggerNode(nodes, currentPlan);
        if (triggerNode != null) {
            List<TaskNode> scopedNodes = dynamicTaskGraphService.calculateAffectedNodes(nodes, triggerNode);
            if (!scopedNodes.isEmpty()) {
                return scopedNodes;
            }
        }

        List<TaskNode> activePlanNodes = nodes.stream()
                .filter(node -> task.getCurrentPlanVersionId().equals(node.getPlanVersionId()))
                .toList();
        return activePlanNodes.isEmpty() ? nodes : activePlanNodes;
    }

    private TaskNode findTriggerNode(List<TaskNode> nodes, TaskPlan currentPlan) {
        if (nodes == null || nodes.isEmpty() || currentPlan == null || currentPlan.getTriggerNodeName() == null) {
            return null;
        }
        for (TaskNode node : nodes) {
            if (currentPlan.getTriggerNodeName().equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }

    private TaskRecoveryAdvice emptyRecoveryAdvice() {
        return TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("当前暂无额外恢复建议")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .recoveryWindow(TaskRecoveryWindow.builder()
                        .windowScope("TASK_WIDE")
                        .boundaryNodeNames(List.of())
                        .replayableEventIds(List.of())
                        .build())
                .releasePolicy(TaskRecoveryReleasePolicy.builder()
                        .releaseTaskExecutionLock(false)
                        .releaseNodeExecutionLocks(false)
                        .releaseReason("当前没有需要释放的执行占位")
                        .build())
                .auditTrail(TaskRecoveryAuditTrail.builder()
                        .decisionSource("RECOVERY_ENGINE")
                        .build())
                .sourceUrls(List.of())
                .build();
    }

    private TaskPlan resolveActivePlan(AnalysisTask task) {
        if (task == null) {
            return null;
        }
        if (task.getCurrentPlanVersionId() != null) {
            Optional<TaskPlan> activePlan = taskPlanRepository.findById(task.getCurrentPlanVersionId());
            if (activePlan.isPresent()) {
                return activePlan.get();
            }
        }
        return taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(task.getId()).orElse(null);
    }

    private TaskNode selectFocusNode(List<TaskNode> recoveryScopeNodes) {
        if (recoveryScopeNodes == null || recoveryScopeNodes.isEmpty()) {
            return null;
        }
        return recoveryScopeNodes.stream()
                .filter(node -> node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION || node.getStatus() == TaskNodeStatus.PAUSED)
                .findFirst()
                .or(() -> recoveryScopeNodes.stream()
                        .filter(node -> node.getStatus() == TaskNodeStatus.WAITING_RETRY)
                        .findFirst())
                .or(() -> recoveryScopeNodes.stream()
                        .filter(node -> node.getStatus() == TaskNodeStatus.FAILED)
                        .findFirst())
                .or(() -> recoveryScopeNodes.stream()
                        .filter(node -> node.getStatus() != TaskNodeStatus.SUCCESS
                                && node.getStatus() != TaskNodeStatus.COMPENSATED
                                && node.getStatus() != TaskNodeStatus.SKIPPED)
                        .findFirst())
                .orElse(null);
    }

    private List<TaskNodeExecutionAttempt> resolveNodeAttempts(Long taskId, TaskNode focusNode) {
        if (focusNode == null || focusNode.getId() == null) {
            return List.of();
        }
        return taskNodeExecutionAttemptRepository.findByTaskIdAndNodeIdOrderByAttemptNoAsc(taskId, focusNode.getId());
    }

    private List<TaskWorkflowEvent> resolveReplayableEvents(Long taskId, TaskPlan activePlan) {
        return taskWorkflowEventRepository.findAll().stream()
                .filter(event -> Objects.equals(taskId, event.getTaskId()))
                .filter(TaskWorkflowEvent::isReplayableInRecoveryWindow)
                .filter(event -> matchesActivePlanWindow(event, activePlan))
                .sorted(Comparator.comparing(TaskWorkflowEvent::getCreatedAt, this::compareNullableTime))
                .toList();
    }

    private boolean matchesActivePlanWindow(TaskWorkflowEvent event, TaskPlan activePlan) {
        if (event == null || activePlan == null) {
            return true;
        }
        if (Objects.equals(activePlan.getId(), event.getPlanVersionId())) {
            return true;
        }
        if (activePlan.getBranchKey() != null && activePlan.getBranchKey().equals(event.getBranchKey())) {
            return true;
        }
        return activePlan.getTriggerNodeName() != null && activePlan.getTriggerNodeName().equals(event.getNodeName());
    }

    private RecoveryCheckpointResponse selectPreferredCheckpoint(List<RecoveryCheckpointResponse> checkpoints,
                                                                TaskNode focusNode,
                                                                TaskPlan activePlan) {
        if (checkpoints == null || checkpoints.isEmpty()) {
            return null;
        }
        if (focusNode != null) {
            for (RecoveryCheckpointResponse checkpoint : checkpoints) {
                if (focusNode.getNodeName().equals(checkpoint.getNodeName())) {
                    return checkpoint;
                }
            }
        }
        if (activePlan != null) {
            for (RecoveryCheckpointResponse checkpoint : checkpoints) {
                if (Objects.equals(activePlan.getId(), checkpoint.getPlanVersionId())) {
                    return checkpoint;
                }
            }
        }
        return checkpoints.get(0);
    }

    private String resolveRecommendedAction(RecoveryCommand command) {
        if (command == null || command.getActionType() == null) {
            return "OBSERVE_ONLY";
        }
        return switch (command.getActionType()) {
            case REQUEUE_NODE -> "WAIT_FOR_RETRY";
            case AWAIT_MANUAL_INTERVENTION -> "MANUAL_INTERVENTION";
            case EXECUTE_COMPENSATION -> "EXECUTE_COMPENSATION";
            case FINALIZE_FAILURE -> "FULL_RETRY";
        };
    }

    private List<String> resolveBlockingNodeNames(List<TaskNode> recoveryScopeNodes) {
        if (recoveryScopeNodes == null || recoveryScopeNodes.isEmpty()) {
            return List.of();
        }
        return recoveryScopeNodes.stream()
                .filter(node -> node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                        || node.getStatus() == TaskNodeStatus.PAUSED
                        || node.getStatus() == TaskNodeStatus.WAITING_RETRY
                        || node.getStatus() == TaskNodeStatus.FAILED)
                .map(TaskNode::getNodeName)
                .toList();
    }

    private List<String> parseSourceUrls(String rawSourceUrls) {
        if (rawSourceUrls == null || rawSourceUrls.isBlank()) {
            return List.of();
        }
        String trimmed = rawSourceUrls.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of(trimmed);
        }
        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isBlank()) {
            return List.of();
        }
        return List.of(content.replace("\"", "").split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return List.of();
        }
        return sourceUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
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
}
