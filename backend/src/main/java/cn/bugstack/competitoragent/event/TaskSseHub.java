package cn.bugstack.competitoragent.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务 SSE Hub。
 * 负责管理任务级订阅者、分配事件游标、缓存最近事件，并把统一事件广播给对应任务的所有观察者。
 */
@Slf4j
@Component
public class TaskSseHub {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final int MAX_RECENT_EVENTS = 200;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> cursorSequenceByTask = new ConcurrentHashMap<>();
    private final Map<Long, Deque<TaskStreamEvent>> recentEventsByTask = new ConcurrentHashMap<>();

    /**
     * 建立任务级 SSE 订阅。
     * 连接建立后会先发送一条 CONNECTED 事件，帮助前端确认通道已经就绪。
     */
    public SseEmitter subscribe(Long taskId, String lastCursor) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emittersByTask.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(error -> removeEmitter(taskId, emitter));

        replayRecentEvents(taskId, lastCursor, emitter);
        emitDirectly(taskId, emitter, buildConnectedEvent(taskId));
        return emitter;
    }

    /**
     * 发布一条结构化事件。
     * 事件会同时进入最近事件缓冲区，并广播给当前在线订阅者。
     */
    public TaskStreamEvent publish(TaskStreamEvent event) {
        if (event == null || event.getTaskId() == null || event.getEventType() == null) {
            return event;
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }
        if (event.getCursor() == null || event.getCursor().isBlank()) {
            event.setCursor(nextCursor(event.getTaskId()));
        }

        appendRecentEvent(event);
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByTask.getOrDefault(event.getTaskId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                sendEvent(emitter, event);
            } catch (IOException e) {
                removeEmitter(event.getTaskId(), emitter);
                log.warn("send task stream event failed, taskId={}, cursor={}", event.getTaskId(), event.getCursor(), e);
            }
        }
        return event;
    }

    /**
     * 获取任务最近事件。
     * 该方法主要供测试与后续最小事件回放能力复用。
     */
    public List<TaskStreamEvent> getRecentEvents(Long taskId) {
        Deque<TaskStreamEvent> events = recentEventsByTask.get(taskId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(events);
    }

    /**
     * 为指定任务分配下一个单调递增游标。
     */
    public String nextCursor(Long taskId) {
        long sequence = cursorSequenceByTask
                .computeIfAbsent(taskId, ignored -> new AtomicLong(0))
                .incrementAndGet();
        return taskId + "-" + sequence;
    }

    private void replayRecentEvents(Long taskId, String lastCursor, SseEmitter emitter) {
        if (lastCursor == null || lastCursor.isBlank()) {
            return;
        }
        long lastSequence = extractSequence(lastCursor);
        if (lastSequence < 0) {
            return;
        }
        for (TaskStreamEvent event : getRecentEvents(taskId)) {
            if (extractSequence(event.getCursor()) <= lastSequence) {
                continue;
            }
            emitDirectly(taskId, emitter, event);
        }
    }

    private TaskStreamEvent buildConnectedEvent(Long taskId) {
        return TaskStreamEvent.builder()
                .taskId(taskId)
                .eventType(TaskEventType.CONNECTED)
                .occurredAt(LocalDateTime.now())
                .payload(Map.of(
                        "message", "task stream connected",
                        "taskId", taskId))
                .build();
    }

    private void appendRecentEvent(TaskStreamEvent event) {
        Deque<TaskStreamEvent> queue = recentEventsByTask.computeIfAbsent(event.getTaskId(), ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(event);
            while (queue.size() > MAX_RECENT_EVENTS) {
                queue.removeFirst();
            }
        }
    }

    private void emitDirectly(Long taskId, SseEmitter emitter, TaskStreamEvent event) {
        try {
            if (event.getCursor() == null || event.getCursor().isBlank()) {
                event.setCursor(nextCursor(taskId));
            }
            sendEvent(emitter, event);
        } catch (IOException e) {
            removeEmitter(taskId, emitter);
            log.warn("send task stream bootstrap event failed, taskId={}, cursor={}", taskId, event.getCursor(), e);
        }
    }

    private void sendEvent(SseEmitter emitter, TaskStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.getCursor())
                .name(event.getEventType().name())
                .data(event, MediaType.APPLICATION_JSON));
    }

    private void removeEmitter(Long taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByTask.get(taskId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByTask.remove(taskId);
        }
    }

    private long extractSequence(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return -1L;
        }
        int separatorIndex = cursor.lastIndexOf('-');
        if (separatorIndex < 0 || separatorIndex >= cursor.length() - 1) {
            return -1L;
        }
        try {
            return Long.parseLong(cursor.substring(separatorIndex + 1));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
