package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 恢复引擎。
 * <p>
 * 这里统一收口“下一步恢复动作”的判定逻辑，
 * 避免恢复服务、任务服务和执行器各自维护一套略有差异的分支判断。
 */
@Component
public class RecoveryEngine {

    /**
     * 兼容已有调用方的最小入口。
     * 旧逻辑仍然可以只传节点、尝试记录和失败分类，结构化恢复窗口会退化为任务级默认语义。
     */
    public RecoveryCommand decideNextAction(TaskNode node,
                                            List<TaskNodeExecutionAttempt> attempts,
                                            NodeFailureCategory failureCategory) {
        return decideNextAction(node, attempts, failureCategory, null, List.of());
    }

    /**
     * 根据节点当前状态、失败分类和恢复窗口上下文，推导下一步恢复动作。
     *
     * @param node 当前节点
     * @param attempts 当前节点历史尝试记录
     * @param failureCategory 已知失败分类；若为空，则根据当前状态推导默认动作
     * @param activePlan 当前激活计划版本，用于确定恢复窗口边界
     * @param replayableEvents 当前恢复窗口内允许回放的事件列表
     * @return 恢复命令
     */
    public RecoveryCommand decideNextAction(TaskNode node,
                                            List<TaskNodeExecutionAttempt> attempts,
                                            NodeFailureCategory failureCategory,
                                            TaskPlan activePlan,
                                            List<TaskWorkflowEvent> replayableEvents) {
        List<TaskNodeExecutionAttempt> normalizedAttempts = attempts == null ? List.of() : attempts;
        List<TaskWorkflowEvent> normalizedEvents = replayableEvents == null ? List.of() : replayableEvents.stream()
                .filter(TaskWorkflowEvent::isReplayableInRecoveryWindow)
                .sorted(Comparator.comparing(TaskWorkflowEvent::getCreatedAt, this::compareNullableTime))
                .toList();
        TaskNodeExecutionAttempt latestAttempt = normalizedAttempts.isEmpty()
                ? null
                : normalizedAttempts.get(normalizedAttempts.size() - 1);

        RecoveryCommand.RecoveryCommandBuilder builder = RecoveryCommand.builder()
                .recoveryWindowScope(resolveWindowScope(activePlan))
                .recoveryPlanVersionId(activePlan == null ? null : activePlan.getId())
                .recoveryBranchKey(activePlan == null ? null : activePlan.getBranchKey())
                .boundaryNodeNames(activePlan == null
                        ? resolveFallbackBoundaryNodeNames(node)
                        : activePlan.resolveRecoveryBoundaryNodeNames(node == null ? null : node.getNodeName()))
                .replayableEventIds(normalizedEvents.stream()
                        .map(TaskWorkflowEvent::getEventId)
                        .toList())
                .recoveryWindowStartAt(normalizedEvents.isEmpty() ? null : normalizedEvents.get(0).getCreatedAt())
                .recoveryWindowEndAt(normalizedEvents.isEmpty() ? null : normalizedEvents.get(normalizedEvents.size() - 1).getCreatedAt())
                .decisionSource("RECOVERY_ENGINE")
                .triggerEventId(normalizedEvents.isEmpty() ? null : normalizedEvents.get(0).getEventId())
                .latestAttemptId(latestAttempt == null ? null : latestAttempt.getId())
                .latestAttemptNo(latestAttempt == null ? null : latestAttempt.getAttemptNo());

        if (node == null) {
            return builder
                    .actionType(RecoveryCommand.ActionType.FINALIZE_FAILURE)
                    .targetStatus(TaskNodeStatus.FAILED)
                    .reason("缺少可恢复节点上下文")
                    .userReadableSummary("当前节点上下文缺失，无法继续推进")
                    .releaseTaskExecutionLock(true)
                    .releaseNodeExecutionLocks(true)
                    .releaseReason("缺少恢复上下文时必须释放执行占位，避免僵尸任务长期占位")
                    .build();
        }

        if (node.getStatus() == TaskNodeStatus.WAITING_RETRY) {
            return builder
                    .actionType(RecoveryCommand.ActionType.REQUEUE_NODE)
                    .targetStatus(TaskNodeStatus.READY)
                    .reason("节点满足自动重试条件，重新回到待调度状态")
                    .userReadableSummary("系统将重新调度该节点执行重试")
                    .releaseTaskExecutionLock(false)
                    .releaseNodeExecutionLocks(false)
                    .releaseReason("自动重试仍属于系统自推进链路，不应提前释放执行占位")
                    .build();
        }

        if (failureCategory == NodeFailureCategory.COMPENSATABLE) {
            return builder
                    .actionType(RecoveryCommand.ActionType.EXECUTE_COMPENSATION)
                    .targetStatus(TaskNodeStatus.COMPENSATED)
                    .reason("失败分类要求先执行补偿动作")
                    .userReadableSummary("系统将把节点标记为已补偿并收口后续影响")
                    .releaseTaskExecutionLock(true)
                    .releaseNodeExecutionLocks(true)
                    .releaseReason("补偿动作会把当前失败链路收口，因此应释放已有执行占位")
                    .build();
        }

        if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION) {
            return builder
                    .actionType(RecoveryCommand.ActionType.AWAIT_MANUAL_INTERVENTION)
                    .targetStatus(TaskNodeStatus.WAITING_INTERVENTION)
                    .reason("节点等待人工确认或配置调整")
                    .userReadableSummary("请先处理人工介入事项，再决定是否继续")
                    .releaseTaskExecutionLock(true)
                    .releaseNodeExecutionLocks(true)
                    .releaseReason("人工介入期间应释放自动执行占位，避免阻塞其他恢复动作")
                    .build();
        }

        if (failureCategory != null && failureCategory.isRequiresManualIntervention()) {
            return builder
                    .actionType(RecoveryCommand.ActionType.AWAIT_MANUAL_INTERVENTION)
                    .targetStatus(TaskNodeStatus.WAITING_INTERVENTION)
                    .reason("失败分类要求人工判断")
                    .userReadableSummary("当前节点需要人工处理，系统不会自动继续")
                    .releaseTaskExecutionLock(true)
                    .releaseNodeExecutionLocks(true)
                    .releaseReason("进入人工判断窗口后，系统必须释放执行占位")
                    .build();
        }

        return builder
                .actionType(RecoveryCommand.ActionType.FINALIZE_FAILURE)
                .targetStatus(TaskNodeStatus.FAILED)
                .reason("失败被判定为不可自动恢复")
                .userReadableSummary("当前节点已进入不可自动恢复的失败终态")
                .releaseTaskExecutionLock(true)
                .releaseNodeExecutionLocks(true)
                .releaseReason("失败终态需要释放占位，避免系统误判仍在执行")
                .build();
    }

    private String resolveWindowScope(TaskPlan activePlan) {
        if (activePlan == null) {
            return "TASK_WIDE";
        }
        if (activePlan.getParentPlanId() != null) {
            return "ACTIVE_PLAN_BRANCH";
        }
        return "ACTIVE_PLAN_VERSION";
    }

    private List<String> resolveFallbackBoundaryNodeNames(TaskNode node) {
        if (node == null || node.getNodeName() == null || node.getNodeName().isBlank()) {
            return List.of();
        }
        return List.of(node.getNodeName());
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
}
