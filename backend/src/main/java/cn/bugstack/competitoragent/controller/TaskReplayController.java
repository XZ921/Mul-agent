package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.task.TaskReplayProjectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务回放控制器。
 * <p>
 * 该接口返回“正式回放视图”，用于任务详情页或独立回放面板。
 * 与 SSE 事件流不同，这里强调可读摘要和稳定结构，不暴露底层游标与原始消息体。
 */
@Tag(name = "Task Replay", description = "任务回放与恢复视图接口")
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskReplayController {

    private final TaskReplayProjectionService taskReplayProjectionService;

    @GetMapping("/{taskId}/replay")
    @Operation(summary = "获取任务回放视图")
    public ApiResponse<TaskReplayResponse> getTaskReplay(
            @Parameter(description = "任务 ID", example = "42")
            @PathVariable Long taskId) {
        return ApiResponse.success(taskReplayProjectionService.getTaskReplay(taskId));
    }
}
