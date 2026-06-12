package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务计划预览中的阶段视图。
 * 阶段是面向业务的计划说明，不承担运行态节点控制语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览阶段")
public class TaskPlanPreviewStageResponse {

    @Schema(description = "阶段展示键", example = "source-strategy")
    private String key;

    @Schema(description = "阶段代码", example = "SOURCE_STRATEGY")
    private String stageCode;

    @Schema(description = "阶段标题")
    private String title;

    @Schema(description = "阶段摘要")
    private String summary;

    @Schema(description = "阶段详情")
    private String detail;

    @Schema(description = "阶段来源")
    private List<String> sourceUrls;
}
