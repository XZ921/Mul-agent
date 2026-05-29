package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
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
    public ApiResponse<List<TaskResponse>> listTasks(
            @Parameter(description = "Task status filter")
            @RequestParam(required = false) String status) {
        return ApiResponse.success(taskService.listTasks(status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get task detail")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long id) {
        return ApiResponse.success(taskService.getTask(id));
    }

    @GetMapping("/{id}/nodes")
    @Operation(summary = "Get task nodes")
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

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry failed task with full reset")
    public ApiResponse<String> retryTask(@PathVariable Long id) {
        taskService.retryTask(id);
        return ApiResponse.success("Task reset", "Task reset to pending");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete task")
    public ApiResponse<String> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ApiResponse.success("Task deleted");
    }
}
