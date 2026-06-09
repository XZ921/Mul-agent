package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.task.AnalysisTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Task", description = "Create, preview and execute competitor analysis tasks")
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskController {

    private final AnalysisTaskService taskService;

    @PostMapping("/create")
    @Operation(summary = "Create task")
    public ApiResponse<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(taskService.createTask(request), "Task created");
    }

    @PostMapping("/preview")
    @Operation(summary = "Preview workflow")
    public ApiResponse<List<TaskNodeResponse>> previewWorkflow(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(taskService.previewWorkflow(request));
    }

    @GetMapping("/list")
    @Operation(summary = "List tasks")
    public ApiResponse<TaskListPageResponse> listTasks(
            @Parameter(description = "Task status filter")
            @RequestParam(required = false) String status,
            @Parameter(description = "Page number, starting from 1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(taskService.listTasks(status, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get task detail", description = "返回最新任务快照，增量更新请订阅 /api/task/{id}/events")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long id) {
        return ApiResponse.success(taskService.getTask(id));
    }

    @GetMapping("/{id}/nodes")
    @Operation(summary = "Get task nodes", description = "返回节点快照，节点运行中的实时变化会继续通过任务 SSE 主通道推送")
    public ApiResponse<List<TaskNodeResponse>> getTaskNodes(@PathVariable Long id) {
        return ApiResponse.success(taskService.getTaskNodes(id));
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute task")
    public ApiResponse<String> executeTask(@PathVariable Long id) {
        taskService.executeTask(id);
        return ApiResponse.success("Task submitted", "Task execution started");
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume task from successful checkpoints")
    public ApiResponse<String> resumeTask(@PathVariable Long id) {
        taskService.resumeTask(id);
        return ApiResponse.success("Task resumed", "Task resumed from existing checkpoints");
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop running task")
    public ApiResponse<String> stopTask(@PathVariable Long id) {
        taskService.stopTask(id);
        return ApiResponse.success("Task stopped", "Task stop requested");
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry failed task with full reset")
    public ApiResponse<String> retryTask(@PathVariable Long id) {
        taskService.retryTask(id);
        return ApiResponse.success("Task reset", "Task reset to pending");
    }

    @PostMapping("/{id}/nodes/{nodeName}/rerun")
    @Operation(summary = "Rerun task from a specific node")
    public ApiResponse<String> rerunNode(@PathVariable Long id, @PathVariable String nodeName) {
        taskService.rerunFromNode(id, nodeName);
        return ApiResponse.success("Node rerun scheduled", "Task rerun scheduled from node " + nodeName);
    }

    @PostMapping("/{id}/nodes/{nodeName}/config-rerun")
    @Operation(summary = "Update node config and rerun from the node")
    public ApiResponse<String> updateNodeConfigAndRerun(@PathVariable Long id,
                                                        @PathVariable String nodeName,
                                                        @Valid @RequestBody UpdateNodeConfigRequest request) {
        taskService.updateNodeConfigAndRerun(id, nodeName, request);
        return ApiResponse.success("Node config updated", "Task rerun scheduled from node " + nodeName);
    }

    @PostMapping("/{id}/nodes/{nodeName}/pause")
    @Operation(summary = "Pause a pending node")
    public ApiResponse<String> pauseNode(@PathVariable Long id, @PathVariable String nodeName) {
        taskService.pauseNode(id, nodeName);
        return ApiResponse.success("Node paused", "Node paused: " + nodeName);
    }

    @PostMapping("/{id}/nodes/{nodeName}/resume")
    @Operation(summary = "Resume a paused node")
    public ApiResponse<String> resumeNode(@PathVariable Long id, @PathVariable String nodeName) {
        taskService.resumeNode(id, nodeName);
        return ApiResponse.success("Node resumed", "Node resumed: " + nodeName);
    }

    @PostMapping("/{id}/nodes/{nodeName}/skip")
    @Operation(summary = "Skip a pending or paused node")
    public ApiResponse<String> skipNode(@PathVariable Long id, @PathVariable String nodeName) {
        taskService.skipNode(id, nodeName);
        return ApiResponse.success("Node skipped", "Node skipped: " + nodeName);
    }

    @PostMapping("/{id}/nodes/{nodeName}/terminate")
    @Operation(summary = "Terminate or request termination for a node")
    public ApiResponse<String> terminateNode(@PathVariable Long id, @PathVariable String nodeName) {
        taskService.terminateNode(id, nodeName);
        return ApiResponse.success("Node terminate requested", "Node terminate requested: " + nodeName);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete task")
    public ApiResponse<String> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ApiResponse.success("Task deleted");
    }
}
