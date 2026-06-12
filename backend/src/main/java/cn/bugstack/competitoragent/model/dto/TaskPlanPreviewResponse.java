package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务计划预览正式合同。
 * 这个对象只表达“系统打算怎么执行任务”，不混入运行态节点状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览正式合同")
public class TaskPlanPreviewResponse {

    @Schema(description = "预览合同类型", example = "TASK_PLAN_PREVIEW_V1")
    private String contractType;

    @Schema(description = "任务总体目标")
    private String goal;

    @Schema(description = "竞品数量", example = "1")
    private Integer competitorCount;

    @Schema(description = "采集节点数量", example = "1")
    private Integer collectorCount;

    @Schema(description = "后续处理节点数量", example = "4")
    private Integer pipelineCount;

    @Schema(description = "来源泳道预览")
    private List<TaskPlanPreviewLaneResponse> lanes;

    @Schema(description = "阶段预览")
    private List<TaskPlanPreviewStageResponse> stages;

    @Schema(description = "节点预览")
    private List<TaskPlanPreviewNodeResponse> nodes;

    @Schema(description = "预览层级可追溯来源")
    private List<String> sourceUrls;
}
