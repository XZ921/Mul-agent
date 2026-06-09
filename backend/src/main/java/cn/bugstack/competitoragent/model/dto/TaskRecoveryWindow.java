package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务恢复窗口 DTO。
 * <p>
 * 该对象用于结构化表达“当前恢复判断是基于哪一段计划 / 事件窗口得出的”，
 * 避免回放接口只给出一句模糊建议而无法解释恢复边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务恢复窗口")
public class TaskRecoveryWindow {

    @Schema(description = "恢复窗口范围类型", example = "ACTIVE_PLAN_BRANCH")
    private String windowScope;

    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "关联分支标识", example = "root/review-3")
    private String branchKey;

    @Schema(description = "恢复边界节点名称列表")
    private List<String> boundaryNodeNames;

    @Schema(description = "当前窗口内允许回放的事件 ID 列表")
    private List<String> replayableEventIds;

    @Schema(description = "窗口起始时间")
    private LocalDateTime windowStartAt;

    @Schema(description = "窗口结束时间")
    private LocalDateTime windowEndAt;
}
