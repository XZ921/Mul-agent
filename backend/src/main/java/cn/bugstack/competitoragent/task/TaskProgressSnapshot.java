package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务运行快照。
 * <p>
 * 该对象只保留工作台观察、恢复判断和断线补偿所需的热数据，
 * 不替代数据库中的完整任务事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressSnapshot {

    private Long taskId;
    private String taskStatus;
    private String currentStage;
    private String errorMessage;
    private String statusSummary;
    private int totalNodes;
    private int completedNodes;
    private int waitingRetryNodeCount;
    private int waitingInterventionNodeCount;
    private int compensatedNodeCount;

    @Builder.Default
    private List<String> activeNodeNames = new ArrayList<>();

    private LocalDateTime updatedAt;

    /**
     * 基于任务和节点权威状态生成轻量快照。
     * 这里把“等待重试 / 等待人工处理 / 已补偿”显式折叠成可展示摘要，
     * 让任务级公开状态仍维持五态，但用户仍能理解当前处于哪类恢复语义。
     */
    public static TaskProgressSnapshot fromTask(AnalysisTask task,
                                                AnalysisTaskStatus status,
                                                String errorMessage,
                                                List<TaskNode> nodes) {
        List<String> activeNodeNames = new ArrayList<>();
        int completedNodes = 0;
        int waitingRetryNodeCount = 0;
        int waitingInterventionNodeCount = 0;
        int compensatedNodeCount = 0;
        String currentStage = null;

        for (TaskNode node : nodes) {
            if (isActiveStatus(node.getStatus())) {
                activeNodeNames.add(node.getNodeName());
                if (currentStage == null) {
                    currentStage = readableStageName(node);
                }
            }
            if (node.getStatus() == TaskNodeStatus.WAITING_RETRY) {
                waitingRetryNodeCount++;
            }
            if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                    || node.getStatus() == TaskNodeStatus.PAUSED) {
                waitingInterventionNodeCount++;
            }
            if (node.getStatus() == TaskNodeStatus.COMPENSATED) {
                compensatedNodeCount++;
            }
            if (isCompletedStatus(node.getStatus())) {
                completedNodes++;
            }
        }

        if (currentStage == null) {
            currentStage = deriveDefaultStage(status, nodes);
        }

        return TaskProgressSnapshot.builder()
                .taskId(task.getId())
                .taskStatus(status.name())
                .currentStage(currentStage)
                .errorMessage(errorMessage)
                .statusSummary(buildStatusSummary(waitingRetryNodeCount, waitingInterventionNodeCount, compensatedNodeCount))
                .totalNodes(nodes.size())
                .completedNodes(completedNodes)
                .waitingRetryNodeCount(waitingRetryNodeCount)
                .waitingInterventionNodeCount(waitingInterventionNodeCount)
                .compensatedNodeCount(compensatedNodeCount)
                .activeNodeNames(activeNodeNames)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static boolean isActiveStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.READY
                || status == TaskNodeStatus.DISPATCHED
                || status == TaskNodeStatus.RUNNING
                || status == TaskNodeStatus.WAITING_RETRY
                || status == TaskNodeStatus.WAITING_INTERVENTION
                || status == TaskNodeStatus.PAUSED;
    }

    private static boolean isCompletedStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.FAILED
                || status == TaskNodeStatus.SKIPPED
                || status == TaskNodeStatus.COMPENSATED;
    }

    private static String deriveDefaultStage(AnalysisTaskStatus status, List<TaskNode> nodes) {
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION || node.getStatus() == TaskNodeStatus.PAUSED) {
                return readableStageName(node) + "：等待人工处理";
            }
            if (node.getStatus() == TaskNodeStatus.WAITING_RETRY) {
                return readableStageName(node) + "：等待自动重试";
            }
        }
        if (status == AnalysisTaskStatus.SUCCESS) {
            return "执行完成";
        }
        if (status == AnalysisTaskStatus.FAILED) {
            return "执行失败";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "等待人工处理";
        }
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.READY) {
                return readableStageName(node);
            }
        }
        return "待执行";
    }

    private static String buildStatusSummary(int waitingRetryNodeCount,
                                             int waitingInterventionNodeCount,
                                             int compensatedNodeCount) {
        if (waitingInterventionNodeCount > 0) {
            return "存在等待人工处理的节点";
        }
        if (waitingRetryNodeCount > 0) {
            return "系统正在等待自动重试";
        }
        if (compensatedNodeCount > 0) {
            return "部分节点已通过补偿收口";
        }
        return null;
    }

    private static String readableStageName(TaskNode node) {
        if (node == null) {
            return "待执行";
        }
        return node.getDisplayName() == null || node.getDisplayName().isBlank()
                ? node.getNodeName()
                : node.getDisplayName();
    }
}
