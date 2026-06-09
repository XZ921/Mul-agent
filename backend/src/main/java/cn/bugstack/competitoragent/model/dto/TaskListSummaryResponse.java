package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务列表顶部总览摘要，供列表页统一消费，不再让前端只基于当前页数据自行猜测总览状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务列表总览摘要")
public class TaskListSummaryResponse {

    @Schema(description = "任务总数", example = "12")
    private int total;

    @Schema(description = "运行中任务数", example = "3")
    private int running;

    @Schema(description = "成功任务数", example = "6")
    private int success;

    @Schema(description = "失败任务数", example = "2")
    private int failed;

    @Schema(description = "已停止任务数", example = "1")
    private int stopped;

    @Schema(description = "平均完成度", example = "54")
    private int avgProgress;
}
