package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点执行恢复策略。
 * <p>
 * 统一收口三类问题：
 * 1. 当前节点集合应被聚合成什么任务公开状态；
 * 2. 任务恢复 / 续跑时，哪些节点可以保留检查点；
 * 3. 出现人工阻断或重试等待时，任务是否还能继续自动推进。
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
     * 基于节点权威状态推导任务公开状态。
     * <p>
     * 任务级状态继续保持五态：
     * `PENDING / RUNNING / SUCCESS / FAILED / STOPPED`。
     * 更细粒度的恢复语义通过节点状态和快照摘要表达。
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
        int completedNodes = (int) nodes.stream()
                .filter(node -> isTerminalStatus(node.getStatus()))
                .count();

        if (isManuallyStoppedTask(task)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage(task.getErrorMessage())
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasWaitingInterventionNode = nodes.stream().anyMatch(this::isWaitingInterventionStatus);
        boolean hasPausedNode = nodes.stream().anyMatch(node -> node.getStatus() == TaskNodeStatus.PAUSED);
        boolean initialReviewRequiresHumanIntervention = nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && requiresHumanIntervention(node.getOutputData()));
        boolean hasRunningLikeNode = nodes.stream().anyMatch(this::isRunningLikeStatus);
        boolean hasActiveExecutionNode = nodes.stream().anyMatch(node ->
                node.getStatus() == TaskNodeStatus.READY
                        || node.getStatus() == TaskNodeStatus.DISPATCHED
                        || node.getStatus() == TaskNodeStatus.RUNNING);

        if (hasPausedNode && !hasRunningLikeNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage("存在已暂停节点，等待人工恢复")
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (hasWaitingInterventionNode && !hasActiveExecutionNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage("存在等待人工处理的节点，请确认后继续")
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (initialReviewRequiresHumanIntervention && !hasRevisionFlowSucceeded(nodes)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.STOPPED)
                    .errorMessage("初审未通过且需要人工介入，请补充证据或调整策略后继续")
                    .waitingManualIntervention(true)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (hasRunningLikeNode) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.RUNNING)
                    .errorMessage(null)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasFailedOrSkippedRequiredNode = nodes.stream()
                .filter(TaskNode::isRequired)
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.FAILED || node.getStatus() == TaskNodeStatus.SKIPPED);
        boolean allRequiredSucceeded = nodes.stream()
                .filter(TaskNode::isRequired)
                .allMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS || node.getStatus() == TaskNodeStatus.COMPENSATED);
        boolean initialReviewPresent = nodes.stream().anyMatch(node -> "quality_check".equals(node.getNodeName()));
        boolean initialReviewPassed = nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));
        boolean finalReviewPassed = nodes.stream()
                .filter(node -> "quality_check_final".equals(node.getNodeName()))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));
        // Phase 4 的动态补图闭环会派生 quality_check_revision_patch_v* 节点。
        // 这些节点本质上承担“终审补图复核”职责，若它们已经通过，就应该和固定终审节点一样把任务判定为 SUCCESS。
        boolean dynamicPatchReviewPassed = nodes.stream()
                .filter(node -> node.getNodeName() != null && node.getNodeName().startsWith("quality_check_revision_patch_v"))
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));

        if (finalReviewPassed || dynamicPatchReviewPassed || (!initialReviewPresent && allRequiredSucceeded) || initialReviewPassed) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.SUCCESS)
                    .errorMessage(null)
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        boolean hasPendingNode = nodes.stream().anyMatch(node ->
                node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.READY);
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
                    .errorMessage("任务存在未恢复的失败节点，请检查节点详情")
                    .completedNodes(completedNodes)
                    .totalNodes(totalNodes)
                    .build();
        }

        if (initialReviewPresent || hasRevisionFlowSucceeded(nodes)) {
            return TaskExecutionResolution.builder()
                    .status(AnalysisTaskStatus.FAILED)
                    .errorMessage("质量闭环未达到通过条件，请检查评审结果")
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
     * 任务恢复 / 续跑时的节点复位策略。
     * SUCCESS / COMPENSATED 节点保留检查点，其余节点回到待编排状态。
     */
    public boolean resetNodesForResume(List<TaskNode> nodes, boolean includePausedNodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS || node.getStatus() == TaskNodeStatus.COMPENSATED) {
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
     * 服务重启恢复时，只回滚可能被中断的活跃节点。
     * READY / DISPATCHED / RUNNING 会回到待编排状态，
     * WAITING_INTERVENTION 和 SUCCESS / COMPENSATED 会被保留。
     */
    public boolean resetInterruptedNodes(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.RUNNING || node.getStatus() == TaskNodeStatus.DISPATCHED) {
                preserveCollectorSearchCheckpoint(node);
                resetNodeForInterruptedRestart(node);
                changed = true;
                continue;
            }
            if (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.READY) {
                preserveCollectorSearchCheckpoint(node);
                node.setControlState(TaskNodeControlState.NONE);
                node.setInputData(null);
                node.setStartedAt(null);
                node.setCompletedAt(null);
                node.setRetryCount(0);
                node.setFailureCategory(null);
                node.setLastAttemptAt(null);
                node.setNextRetryAt(null);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 节点重跑会清空当前节点执行现场，让它与下游一起重新计算。
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
        node.setFailureCategory(null);
        node.setStartedAt(null);
        node.setCompletedAt(null);
        node.setLastAttemptAt(null);
        node.setNextRetryAt(null);
        node.setLastEventId(null);
        node.setRetryCount(0);
    }

    /**
     * 人工继续时，只撤掉阻断态，不主动清空配置和历史输入。
     */
    public void resetNodeForManualContinue(TaskNode node) {
        if (node == null) {
            return;
        }
        node.setStatus(TaskNodeStatus.PENDING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setErrorMessage(null);
        node.setInterventionReason(null);
        node.setFailureCategory(null);
        node.setNextRetryAt(null);
        node.setCompletedAt(null);
    }

    /**
     * 只要还存在等待人工处理的节点，就不允许继续自动推进。
     */
    public boolean canAutoContinue(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean hasManualBlock = nodes.stream().anyMatch(this::isWaitingInterventionStatus);
        if (hasManualBlock) {
            return false;
        }
        return nodes.stream()
                .filter(node -> "quality_check".equals(node.getNodeName()))
                .noneMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && requiresHumanIntervention(node.getOutputData()));
    }

    private boolean isRunningLikeStatus(TaskNode node) {
        if (node == null) {
            return false;
        }
        return node.getStatus() == TaskNodeStatus.READY
                || node.getStatus() == TaskNodeStatus.DISPATCHED
                || node.getStatus() == TaskNodeStatus.RUNNING
                || node.getStatus() == TaskNodeStatus.WAITING_RETRY;
    }

    private boolean isWaitingInterventionStatus(TaskNode node) {
        if (node == null) {
            return false;
        }
        return node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                || node.getStatus() == TaskNodeStatus.PAUSED;
    }

    private void resetNodeForInterruptedRestart(TaskNode node) {
        node.setStatus(TaskNodeStatus.PENDING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(null);
        node.setOutputData(null);
        node.setErrorMessage("Node interrupted by service restart");
        node.setInterventionReason(null);
        node.setFailureCategory(null);
        node.setStartedAt(null);
        node.setCompletedAt(null);
        node.setLastAttemptAt(null);
        node.setNextRetryAt(null);
        node.setRetryCount(0);
    }

    /**
     * Collector 中断恢复时优先保留正式 searchAudit，
     * 让 resume/rerun 可以直接从最新一次已确认的搜索现场继续，而不是退回到更旧的 checkpoint。
     */
    private void preserveCollectorSearchCheckpoint(TaskNode node) {
        if (node == null
                || node.getAgentType() != AgentType.COLLECTOR
                || node.getOutputData() == null
                || node.getOutputData().isBlank()) {
            return;
        }
        JsonNode outputNode = readJson(node.getOutputData());
        if (outputNode == null) {
            return;
        }
        JsonNode auditNode = outputNode.get("searchAudit");
        if (auditNode == null || auditNode.isNull() || auditNode.isMissingNode()) {
            return;
        }
        try {
            JsonNode configNode = readJson(node.getNodeConfig());
            ObjectNode configObject = configNode != null && configNode.isObject()
                    ? (ObjectNode) configNode.deepCopy()
                    : objectMapper.createObjectNode();
            configObject.set("searchAuditCheckpoint", auditNode.deepCopy());
            node.setNodeConfig(objectMapper.writeValueAsString(configObject));
        } catch (Exception ignored) {
            // 检查点保留属于恢复优化，失败时不要反向阻断节点状态回滚。
        }
    }

    private boolean hasRevisionFlowSucceeded(List<TaskNode> nodes) {
        return nodes.stream()
                .anyMatch(node -> ("rewrite_report".equals(node.getNodeName())
                        || (node.getNodeName() != null && node.getNodeName().startsWith("rewrite_revision_patch_v")))
                        && node.getStatus() == TaskNodeStatus.SUCCESS);
    }

    private AnalysisTaskStatus defaultTaskStatus(AnalysisTaskStatus persistedStatus) {
        return persistedStatus == null ? AnalysisTaskStatus.PENDING : persistedStatus;
    }

    /**
     * 用户主动停止任务后，详情接口必须优先保留 STOPPED，
     * 避免前端被残留的节点活跃态重新渲染成“执行中”。
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
                || status == TaskNodeStatus.SKIPPED
                || status == TaskNodeStatus.COMPENSATED;
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
