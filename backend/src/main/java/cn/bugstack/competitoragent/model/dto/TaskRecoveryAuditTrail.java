package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复建议审计引用 DTO。
 * <p>
 * 用于把恢复建议与触发事件、最近尝试和计划版本显式关联，
 * 满足“结构化、可审计”的回放要求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务恢复建议审计引用")
public class TaskRecoveryAuditTrail {

    @Schema(description = "决策来源", example = "RECOVERY_ENGINE")
    private String decisionSource;

    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "触发当前恢复判断的事件 ID", example = "evt-101")
    private String triggerEventId;

    @Schema(description = "最近一次尝试 ID", example = "301")
    private Long latestAttemptId;

    @Schema(description = "最近一次尝试编号", example = "2")
    private Integer latestAttemptNo;
}
