package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.event.TaskEventReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务事件流控制器。
 * 提供统一的 SSE 订阅出口，前端通过“首次快照拉取 + 持续事件流订阅”完成任务观察。
 */
@Tag(name = "Task Event Stream", description = "任务实时事件流订阅接口")
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskEventStreamController {

    private final TaskEventReplayService taskEventReplayService;

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅任务实时事件流")
    public SseEmitter subscribe(
            @Parameter(description = "任务 ID", example = "1")
            @PathVariable Long taskId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @Parameter(description = "断线恢复游标", example = "1-12")
            @org.springframework.web.bind.annotation.RequestParam(name = "cursor", required = false) String cursor) {
        String resumeCursor = (lastEventId != null && !lastEventId.isBlank()) ? lastEventId : cursor;
        return taskEventReplayService.subscribe(taskId, resumeCursor);
    }
}
