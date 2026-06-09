package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 回放视图中的节点摘要 DTO。
 * <p>
 * 该对象用于把节点当前状态、最近尝试结果和恢复提示整合成
 * “非实现者也能快速理解”的摘要，而不是要求调用方自行拼接多个底层对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放中的节点摘要")
public class ReplayNodeSummary {

    @Schema(description = "节点 ID", example = "202")
    private Long nodeId;

    @Schema(description = "节点名称", example = "quality_check")
    private String nodeName;

    @Schema(description = "节点展示名称", example = "质量复核")
    private String displayName;

    @Schema(description = "节点状态", example = "WAITING_INTERVENTION")
    private String status;

    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "分支标识", example = "root/review-3")
    private String branchKey;

    @Schema(description = "最近一次尝试编号", example = "2")
    private Integer latestAttemptNo;

    @Schema(description = "失败分类", example = "MANUAL_INTERVENTION_REQUIRED")
    private String failureCategory;

    @Schema(description = "节点问题摘要")
    private String issueSummary;

    @Schema(description = "节点恢复提示")
    private String recoveryHint;

    @Schema(description = "关联恢复点摘要")
    private String checkpointSummary;

    @Schema(description = "节点相关追溯来源")
    private List<String> sourceUrls;
}
