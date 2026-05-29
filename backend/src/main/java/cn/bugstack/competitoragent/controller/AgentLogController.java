package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Agent 日志", description = "Agent 执行日志查询，追踪每个 Agent 的决策过程")
@RestController
@RequestMapping("/api/agent-log")
@RequiredArgsConstructor
public class AgentLogController {

    private final AgentLogService logService;

    @GetMapping("/task/{taskId}")
    @Operation(summary = "获取任务的所有 Agent 日志", description = "按执行时间升序排列，列表不返回完整 prompt")
    public ApiResponse<List<AgentLogResponse>> getLogsByTask(
            @Parameter(description = "任务 ID", example = "1")
            @PathVariable Long taskId) {
        return ApiResponse.success(logService.getLogsByTask(taskId));
    }

    @GetMapping("/task/{taskId}/agent/{agentType}")
    @Operation(summary = "按 Agent 类型筛选日志", description = "agentType: COLLECTOR, EXTRACTOR, ANALYZER, WRITER, REVIEWER")
    public ApiResponse<List<AgentLogResponse>> getLogsByAgentType(
            @Parameter(description = "任务 ID", example = "1")
            @PathVariable Long taskId,
            @Parameter(description = "Agent 类型", example = "EXTRACTOR")
            @PathVariable String agentType) {
        return ApiResponse.success(logService.getLogsByTaskAndAgentType(taskId, agentType));
    }

    @GetMapping("/{logId}")
    @Operation(summary = "获取单条日志详情", description = "包含完整的 prompt 和 LLM 响应内容")
    public ApiResponse<AgentLogResponse> getLogDetail(
            @Parameter(description = "日志 ID", example = "100")
            @PathVariable Long logId) {
        return ApiResponse.success(logService.getLogDetail(logId));
    }
}
