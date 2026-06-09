package cn.bugstack.competitoragent.event;

import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务事件发布器。
 * 负责把任务、节点、搜索、日志和诊断变化翻译为统一的 TaskStreamEvent。
 */
@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    private final TaskSseHub taskSseHub;

    /**
     * 发布任务快照事件。
     * 该事件适合作为详情页顶部状态条与阶段进度的主增量来源。
     */
    public TaskStreamEvent publishTaskSnapshot(TaskProgressSnapshot snapshot) {
        if (snapshot == null || snapshot.getTaskId() == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.getTaskStatus());
        payload.put("currentStage", snapshot.getCurrentStage());
        payload.put("statusSummary", snapshot.getStatusSummary());
        payload.put("completedNodes", snapshot.getCompletedNodes());
        payload.put("totalNodes", snapshot.getTotalNodes());
        payload.put("waitingRetryNodeCount", snapshot.getWaitingRetryNodeCount());
        payload.put("waitingInterventionNodeCount", snapshot.getWaitingInterventionNodeCount());
        payload.put("compensatedNodeCount", snapshot.getCompensatedNodeCount());
        payload.put("activeNodeNames", snapshot.getActiveNodeNames());
        payload.put("errorMessage", snapshot.getErrorMessage());
        payload.put("updatedAt", snapshot.getUpdatedAt());
        return publish(TaskEventType.TASK_SNAPSHOT, snapshot.getTaskId(), null, payload);
    }

    /**
     * 发布任务状态摘要事件。
     */
    public TaskStreamEvent publishTaskStatusEvent(Long taskId,
                                                  AnalysisTaskStatus status,
                                                  String currentStage,
                                                  String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status == null ? null : status.name());
        payload.put("currentStage", currentStage);
        payload.put("errorMessage", errorMessage);
        return publish(TaskEventType.TASK_STATUS, taskId, null, payload);
    }

    /**
     * 发布节点状态事件。
     */
    public TaskStreamEvent publishNodeStatusEvent(Long taskId, TaskNode node, String action) {
        if (node == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("nodeName", node.getNodeName());
        payload.put("displayName", node.getDisplayName());
        payload.put("status", node.getStatus() == null ? null : node.getStatus().name());
        payload.put("controlState", node.getControlState() == null ? null : node.getControlState().name());
        payload.put("errorMessage", node.getErrorMessage());
        payload.put("failureCategory", node.getFailureCategory() == null ? null : node.getFailureCategory().name());
        payload.put("retryCount", node.getRetryCount());
        payload.put("executionOrder", node.getExecutionOrder());
        payload.put("startedAt", node.getStartedAt());
        payload.put("completedAt", node.getCompletedAt());
        payload.put("lastAttemptAt", node.getLastAttemptAt());
        payload.put("nextRetryAt", node.getNextRetryAt());
        payload.put("statusSummary", buildNodeStatusSummary(node));
        return publish(TaskEventType.NODE_STATUS, taskId, node.getNodeName(), payload);
    }

    /**
     * 发布搜索进度事件。
     */
    public TaskStreamEvent publishSearchProgressEvent(Long taskId, String nodeName, Map<String, Object> progressPayload) {
        return publish(TaskEventType.SEARCH_PROGRESS, taskId, nodeName, progressPayload);
    }

    /**
     * 发布 Agent 输出事件。
     * 这里复用日志响应结构，让前端日志面板和节点详情抽屉共享同一套消费契约。
     */
    public TaskStreamEvent publishAgentLogEvent(Long taskId, String nodeName, AgentLogResponse logResponse) {
        if (logResponse == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentType", logResponse.getAgentType() == null ? null : logResponse.getAgentType().name());
        payload.put("agentName", logResponse.getAgentName());
        payload.put("status", logResponse.getStatus() == null ? null : logResponse.getStatus().name());
        payload.put("reasoningSummary", logResponse.getReasoningSummary());
        payload.put("outputData", logResponse.getOutputData());
        payload.put("errorMessage", logResponse.getErrorMessage());
        payload.put("durationMs", logResponse.getDurationMs());
        payload.put("createdAt", logResponse.getCreatedAt());
        return publish(TaskEventType.AGENT_OUTPUT, taskId, nodeName, payload);
    }

    /**
     * 发布诊断事件。
     * Reviewer 节点或任务恢复判断可以把结构化诊断直接透传给前端诊断区。
     */
    public TaskStreamEvent publishDiagnosisEvent(Long taskId, String nodeName, Map<String, Object> diagnosisPayload) {
        return publish(TaskEventType.DIAGNOSIS, taskId, nodeName, diagnosisPayload);
    }

    /**
     * 发布任意任务流事件。
     */
    public TaskStreamEvent publish(TaskEventType eventType, Long taskId, String nodeName, Map<String, Object> payload) {
        if (taskId == null || eventType == null) {
            return null;
        }
        TaskStreamEvent event = TaskStreamEvent.builder()
                .taskId(taskId)
                .eventType(eventType)
                .nodeName(nodeName)
                .occurredAt(LocalDateTime.now())
                .payload(payload == null ? Map.of() : payload)
                .build();
        return taskSseHub.publish(event);
    }

    private String buildNodeStatusSummary(TaskNode node) {
        if (node == null || node.getStatus() == null) {
            return null;
        }
        TaskNodeStatus status = node.getStatus();
        return switch (status) {
            case WAITING_RETRY -> "节点等待系统自动重试";
            case WAITING_INTERVENTION -> "节点等待人工处理";
            case COMPENSATED -> "节点已通过补偿收口";
            case READY -> "节点已满足依赖，等待调度";
            case DISPATCHED -> "节点已派发，等待执行器接手";
            case RUNNING -> "节点正在执行";
            case FAILED -> "节点执行失败";
            case SUCCESS -> "节点执行成功";
            case SKIPPED -> "节点已跳过";
            case PAUSED -> "节点已暂停";
            case PENDING -> "节点等待编排";
        };
    }
}
