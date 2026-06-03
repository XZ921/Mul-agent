package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点执行恢复策略。
 * 统一收口三类高风险状态机判断：
 * 1. 当前节点集合应被解释成什么任务状态；
 * 2. 任务恢复 / 续跑时，哪些节点可以保留检查点，哪些节点必须回滚到待执行；
 * 3. 人工干预与评审阻断信号出现后，任务是否应该继续自动执行。
 *
 * 这样可以避免 DagExecutor、任务服务、恢复服务各自维护一套分叉逻辑，
 * 导致“数据库里是一个状态，详情接口展示又是另一个状态”。
 */
public class NodeExecutionRecoveryPolicy {

    private final ObjectMapper objectMapper;

    public NodeExecutionRecoveryPolicy() {
        this(new ObjectMapper());
    }

    public NodeExecutionRecoveryPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 基于节点快照推导任务的真实运行状态。
     * 优先级说明：
     * 1. 只要还有节点在 RUNNING，任务就仍然处于运行中；
     * 2. 如果没有运行中节点，但存在 PAUSED 或质检阻断信号，则任务应视为 STOPPED；
     * 3. 所有必需节点成功且质检通过，才可视为 SUCCESS；
     * 4. 其余存在失败 / 跳过 / 闭环未通过的情况统一视为 FAILED；
     * 5. 如果只是等待后续执行，则保持 PENDING。
     */
    public TaskExecutionResolution resolveTaskExecution(AnalysisTask task, List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return TaskExecutionResolution.builder()
                    .status(task == null ? AnalysisTaskStatus.FAILED : defaultTaskStatus(task.getStatus()))
                    .errorMessage(task == null ? "任务缺少可执行节点" : task.getErrorMessage())
                    .completedNodes(0)
                    .totalNodes(0)
                    .build();
        }

        int totalNodes = nodes.size();
        int completedNodes = (int) nodes.stream().filter(node -> isTerminalStatus(node.getStatus())).count();

        if (isManuallyStoppedTask(task)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage(task.getErrorMessage())
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasRunningNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.RUNNING);
        if (hasRunningNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.RUNNING)
                    .errorMessage(null)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasPausedNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.PAUSED);
        boolean initialReviewRequiresHumanIntervention = nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && requiresHumanIntervention(node.getOutputData()));
        if (hasPausedNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage("存在已暂停节点，等待人工恢复")
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }
        if (initialReviewRequiresHumanIntervention && !hasRevisionFlowSucceeded(nodes)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage("初审未通过且证据缺口较大，系统已停止自动改写，请先人工补证据或调整采集策略。")
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasFailedOrSkippedRequiredNode = nodes.stream()
                .filter(TaskNode::isRequired)
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.FAILED || node.getStatus() == TaskNodeStatus.SKIPPED);
        boolean allRequiredSuccess = nodes.stream()
                .filter(TaskNode::isRequired)
                .allMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS);
        boolean initialReviewPresent = nodes.stream().anyMatch(node -> "quality_check".equals(node.getNodeName()));
        boolean initialReviewPassed = nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));
        boolean finalReviewPassed = nodes.stream()
                .filter(node -> "quality_check_final".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));

        if (finalReviewPassed || (!initialReviewPresent && allRequiredSuccess) || initialReviewPassed) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.SUCCESS)
                    .errorMessage(null)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasPendingNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.PENDING);
        if (hasPendingNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.PENDING)
                    .errorMessage(task == null ? null : task.getErrorMessage())
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (hasFailedOrSkippedRequiredNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.FAILED)
                    .errorMessage("任务执行失败，请检查节点日志。")
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (initialReviewPresent || hasRevisionFlowSucceeded(nodes)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.FAILED)
                    .errorMessage("质量闭环未达到通过状态，请检查评审反馈。")
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        return TaskExecutionResolution.builder()
                .status(task == null ? AnalysisTaskStatus.PENDING : defaultTaskStatus(task.getStatus()))
                .errorMessage(task == null ? null : task.getErrorMessage())
                .completedNodes(completedNodes)
                .totalNodes(totalNodes)
                .build();
    }

    /**
     * 整任务恢复 / 续跑时的复位策略：
     * 1. SUCCESS 节点保留输入输出，作为断点恢复的共享上下文；
     * 2. 其余节点统一回滚为 PENDING；
     * 3. 是否连 PAUSED 节点一起恢复，由调用方决定。
     */
    public boolean resetNodesForResume(List<TaskNode> nodes, boolean includePausedNodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS) {
                continue;
            }
            if (!includePausedNodes && node.getStatus() == TaskNodeStatus.PAUSED) {
                continue;
            }
            resetNodeForRerun(node, true);
        }
        return true;
    }

    /**
     * 服务重启恢复时只回滚“可能被中断的节点”。
     * 这里保留 SUCCESS 检查点和 PAUSED 人工阻塞态，避免启动恢复时把人工决策冲掉。
     */
    public boolean resetInterruptedNodes(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.RUNNING) {
                resetNodeForInterruptedRestart(node);
                changed = true;
                continue;
            }
            if (node.getStatus() == TaskNodeStatus.PENDING) {
                node.setControlState(TaskNodeControlState.NONE);
                node.setInputData(null);
                node.setStartedAt(null);
                node.setCompletedAt(null);
                node.setRetryCount(0);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 节点级重跑会清空当前节点的执行现场，让其与下游一起按新上下文重新计算。
     */
    public void resetNodeForRerun(TaskNode node, boolean clearOutput) {
        if (node == null) {
            return;
        }
        node.setStatus(TaskNodeStatus.PENDING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(null);
        if (clearOutput) {
            node.setOutputData(null);
        }
        node.setErrorMessage(null);
        node.setInterventionReason(null);
        node.setStartedAt(null);
        node.setCompletedAt(null);
        node.setRetryCount(0);
    }

    /**
     * 人工恢复暂停节点时，只撤掉阻断态，不主动清空配置和历史输入。
     * 这样用户在暂停前调整过的节点配置仍会被后续执行使用。
     */
    public void resetNodeForManualContinue(TaskNode node) {
        if (node == null) {
            return;
        }
        node.setStatus(TaskNodeStatus.PENDING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setErrorMessage(null);
        node.setInterventionReason(null);
        node.setCompletedAt(null);
    }

    /**
     * 判断任务在当前节点快照下是否适合继续自动执行。
     * 只要还存在 PAUSED 或初审阻断信号，就不能自动继续。
     */
    public boolean canAutoContinue(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean hasPausedNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.PAUSED);
        if (hasPausedNode) {
            return false;
        }
        return nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .noneMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && requiresHumanIntervention(node.getOutputData()));
    }

    private void resetNodeForInterruptedRestart(TaskNode node) {
        node.setStatus(TaskNodeStatus.PENDING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(null);
        node.setOutputData(null);
        node.setErrorMessage("Node interrupted by service restart");
        node.setInterventionReason(null);
        node.setStartedAt(null);
        node.setCompletedAt(null);
        node.setRetryCount(0);
    }

    private boolean hasRevisionFlowSucceeded(List<TaskNode> nodes) {
        return nodes.stream()
                .anyMatch(node -> "rewrite_report".equals(node.getNodeName()) && node.getStatus() == TaskNodeStatus.SUCCESS);
    }

    private AnalysisTaskStatus defaultTaskStatus(AnalysisTaskStatus persistedStatus) {
        return persistedStatus == null ? AnalysisTaskStatus.PENDING : persistedStatus;
    }

    /**
     * 用户主动停止任务后，节点层面的运行态可能还没来得及完全收敛。
     * 详情接口此时必须优先保留 STOPPED，避免前端把任务重新渲染成“执行中”，从而把恢复/重置按钮隐藏掉。
     */
    private boolean isManuallyStoppedTask(AnalysisTask task) {
        if (task == null || task.getStatus() != AnalysisTaskStatus.STOPPED) {
            return false;
        }
        return "任务已由用户主动停止".equals(task.getErrorMessage());
    }

    private boolean isTerminalStatus(TaskNodeStatus status) {
        return status == TaskNodeStatus.SUCCESS
                || status == TaskNodeStatus.FAILED
                || status == TaskNodeStatus.SKIPPED;
    }

    private boolean isPassedReview(String outputData) {
        JsonNode json = readJson(outputData);
        return json != null && json.path("passed").asBoolean(false);
    }

    private boolean requiresHumanIntervention(String outputData) {
        JsonNode json = readJson(outputData);
        if (json == null) {
            return false;
        }
        if (json.has("requiresHumanIntervention")) {
            return json.path("requiresHumanIntervention").asBoolean(false);
        }
        if (json.path("passed").asBoolean(false)) {
            return false;
        }
        int score = json.has("score") ? json.path("score").asInt(100) : 100;
        int errorCount = 0;
        JsonNode issues = json.get("issues");
        if (issues != null && issues.isArray()) {
            for (JsonNode issue : issues) {
                if ("ERROR".equalsIgnoreCase(issue.path("severity").asText())) {
                    errorCount++;
                }
            }
        }
        return score <= 20 || errorCount >= 4;
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        try {
            return objectMapper.readTree(cleaned.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    @Builder
    public static class TaskExecutionResolution {
        private AnalysisTaskStatus status;
        private String errorMessage;
        private boolean waitingManualIntervention;
        private int completedNodes;
        private int totalNodes;

        public LocalDateTime resolveCompletedAt(LocalDateTime existingCompletedAt) {
            return status == AnalysisTaskStatus.RUNNING || status == AnalysisTaskStatus.PENDING
                    ? null
                    : existingCompletedAt;
        }

        public LocalDateTime resolveCompletedAtForPersistence(LocalDateTime existingCompletedAt) {
            return status == AnalysisTaskStatus.RUNNING || status == AnalysisTaskStatus.PENDING
                    ? null
                    : (existingCompletedAt == null ? LocalDateTime.now() : existingCompletedAt);
        }
    }
}
