package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务列表正式分页响应，统一承载当前页数据、关注任务和总览摘要，避免前端再用截断或重复排序代替正式语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务列表分页响应")
public class TaskListPageResponse {

    @Schema(description = "当前页任务")
    private List<TaskResponse> items;

    @Schema(description = "需要优先关注的任务")
    private List<TaskResponse> attentionItems;

    @Schema(description = "列表总览摘要")
    private TaskListSummaryResponse summary;

    @Schema(description = "当前页码，从 1 开始", example = "1")
    private int pageNum;

    @Schema(description = "每页条数", example = "10")
    private int pageSize;

    @Schema(description = "总条数", example = "12")
    private int total;

    @Schema(description = "总页数", example = "2")
    private int totalPages;
}
