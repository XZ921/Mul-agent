package cn.bugstack.competitoragent.event;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionSummaryProjector;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务事件回放服务。
 * 该服务承担两类最小职责：
 * 1. 为 SSE 重连提供基于游标的事件补偿入口。
 * 2. 为恢复观察构建“快照优先、事件补齐其次”的回放计划。
 */
@Service
@RequiredArgsConstructor
public class TaskEventReplayService {

    private final TaskSseHub taskSseHub;
    private final TaskRecoveryService taskRecoveryService;
    private final ObjectMapper objectMapper;

    /**
     * 建立 SSE 订阅。
     * 当前最小实现仍复用 Hub 的最近事件重放能力，
     * 前端会在真正重连前先补拉一次快照，因此这里专注于游标续接。
     */
    public SseEmitter subscribe(Long taskId, String lastCursor) {
        return taskSseHub.subscribe(taskId, lastCursor);
    }

    /**
     * 规划一次恢复观察所需的数据帧。
     * 这里不会直接推送 SSE，而是把“当前快照 + 需要补齐的事件”显式整理出来，
     * 方便测试与后续恢复入口复用。
     */
    public TaskReplayFrame planReplay(Long taskId, String lastCursor) {
        Optional<TaskProgressSnapshot> snapshotOptional = taskRecoveryService.getTaskSnapshotOrRebuild(taskId);
        TaskStreamEvent snapshotEvent = snapshotOptional.map(this::toSnapshotEvent).orElse(null);
        List<TaskStreamEvent> recentEvents = taskSseHub.getRecentEvents(taskId);
        List<TaskStreamEvent> replayEvents = resolveReplayEvents(recentEvents, taskId, lastCursor);
        String resumeCursor = TaskEventCursor.parse(lastCursor)
                .filter(cursor -> cursor.taskId().equals(taskId))
                .map(cursor -> lastCursor)
                .orElse(null);
        return TaskReplayFrame.builder()
                .taskId(taskId)
                .resumeCursor(resumeCursor)
                .snapshotEvent(snapshotEvent)
                .replayEvents(replayEvents)
                .latestOrchestrationDecision(resolveLatestOrchestrationDecision(recentEvents))
                .build();
    }

    private List<TaskStreamEvent> resolveReplayEvents(List<TaskStreamEvent> recentEvents, Long taskId, String lastCursor) {
        Optional<TaskEventCursor> parsedCursor = TaskEventCursor.parse(lastCursor)
                .filter(cursor -> cursor.taskId().equals(taskId));
        if (parsedCursor.isEmpty()) {
            return recentEvents;
        }
        TaskEventCursor baseCursor = parsedCursor.get();
        return recentEvents.stream()
                .filter(event -> TaskEventCursor.parse(event.getCursor())
                        .map(eventCursor -> eventCursor.isAfter(baseCursor))
                        .orElse(false))
                .toList();
    }

    /**
     * SSE replay 当前不重建完整的编排 runtime 语义，
     * 只从最近事件里提取“最后一条可解释的协作决策摘要”，供前端恢复后快速说明当前阻塞点。
     */
    private OrchestrationDecisionSummary resolveLatestOrchestrationDecision(List<TaskStreamEvent> replayEvents) {
        OrchestrationDecisionSummary latestDecision = null;
        for (TaskStreamEvent replayEvent : replayEvents) {
            OrchestrationDecisionSummary decision = extractDecisionSummary(replayEvent);
            if (decision != null) {
                latestDecision = decision;
            }
        }
        return latestDecision;
    }

    /**
     * 轻量回放只消费已经广播出去的结构化事件载荷。
     * 如果事件明确带有 decisionType / actionType / evidenceState，就把它投影为稳定只读摘要；
     * 否则保持为空，避免把普通诊断事件误判成协作决策。
     */
    private OrchestrationDecisionSummary extractDecisionSummary(TaskStreamEvent replayEvent) {
        if (replayEvent == null || replayEvent.getPayload() == null || replayEvent.getPayload().isEmpty()) {
            return null;
        }
        return OrchestrationDecisionSummaryProjector.fromEventPayload(
                        replayEvent.getPayload(),
                        replayEvent.getTaskId(),
                        replayEvent.getNodeName(),
                        List.of(),
                        objectMapper)
                .orElse(null);
    }

    /**
     * 把 Redis / 数据库恢复出的任务快照转换成统一事件模型。
     * 该快照事件只用于恢复语义，不强制占用正式事件游标。
     */
    private TaskStreamEvent toSnapshotEvent(TaskProgressSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.getTaskStatus());
        payload.put("currentStage", snapshot.getCurrentStage());
        payload.put("completedNodes", snapshot.getCompletedNodes());
        payload.put("totalNodes", snapshot.getTotalNodes());
        payload.put("activeNodeNames", snapshot.getActiveNodeNames());
        payload.put("errorMessage", snapshot.getErrorMessage());
        payload.put("updatedAt", snapshot.getUpdatedAt());
        return TaskStreamEvent.builder()
                .taskId(snapshot.getTaskId())
                .eventType(TaskEventType.TASK_SNAPSHOT)
                .payload(payload)
                .build();
    }

    @Data
    @Builder
    public static class TaskReplayFrame {

        private Long taskId;
        private String resumeCursor;
        private TaskStreamEvent snapshotEvent;
        private List<TaskStreamEvent> replayEvents;
        private OrchestrationDecisionSummary latestOrchestrationDecision;
    }
}
