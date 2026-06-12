package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务计划预览中的来源泳道。
 * 第一阶段允许为空，但字段定义必须稳定，便于前后端后续补齐正式聚合逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览来源泳道")
public class TaskPlanPreviewLaneResponse {

    @Schema(description = "竞品名称")
    private String competitorName;

    @Schema(description = "采集分支数量", example = "2")
    private Integer branchCount;

    @Schema(description = "来源标签")
    private List<String> sourceLabels;

    @Schema(description = "来源覆盖范围")
    private List<String> sourceScope;

    @Schema(description = "已知入口 URL 数量", example = "1")
    private Integer entryUrlCount;

    @Schema(description = "候选来源数量", example = "4")
    private Integer candidateCount;

    @Schema(description = "检索 Query 数量", example = "2")
    private Integer queryCount;

    @Schema(description = "是否启用浏览器补源")
    private Boolean browserSupplementEnabled;

    @Schema(description = "是否启用结果页验证")
    private Boolean verificationEnabled;

    @Schema(description = "最少验证候选数")
    private Integer minVerifiedCandidates;

    @Schema(description = "优先域名")
    private List<String> preferredDomains;

    @Schema(description = "业务备注")
    private List<String> notes;
}
