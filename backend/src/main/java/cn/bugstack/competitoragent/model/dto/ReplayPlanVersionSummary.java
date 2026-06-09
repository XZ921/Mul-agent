package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回放视图中的计划版本摘要 DTO。
 * <p>
 * Task 5.6.a 先把“计划版本关联”抽成独立正式对象，
 * 避免后续回放响应继续把计划版本信息散落在事件 JSON 中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放中的计划版本摘要")
public class ReplayPlanVersionSummary {

    @Schema(description = "计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "计划版本号", example = "3")
    private Integer planVersion;

    @Schema(description = "父计划版本 ID", example = "8")
    private Long parentPlanId;

    @Schema(description = "分支标识", example = "root/review-3")
    private String branchKey;

    @Schema(description = "计划类型", example = "DYNAMIC_BACKFLOW")
    private String planType;

    @Schema(description = "触发该版本的节点名", example = "quality_check")
    private String triggerNodeName;

    @Schema(description = "是否为当前激活版本", example = "true")
    private boolean active;

    @Schema(description = "计划版本创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "证据 / 追溯来源地址")
    private List<String> sourceUrls;
}
