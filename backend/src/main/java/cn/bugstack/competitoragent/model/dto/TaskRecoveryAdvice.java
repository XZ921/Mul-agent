package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务级恢复建议 DTO。
 * <p>
 * 5.6.b 先把“推荐动作 + 阻塞节点 + 建议恢复点”固化成正式对象，
 * 这样前端和后续恢复控制接口可以共享同一份解释语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务恢复建议")
public class TaskRecoveryAdvice {

    @Schema(description = "推荐动作", example = "MANUAL_INTERVENTION")
    private String recommendedAction;

    @Schema(description = "用户可读恢复建议")
    private String summary;

    @Schema(description = "当前阻塞恢复的节点名称列表")
    private List<String> blockingNodeNames;

    @Schema(description = "建议优先使用的恢复点 ID", example = "601")
    private Long recommendedCheckpointId;

    @Schema(description = "建议优先使用的恢复点键", example = "checkpoint-collect-1")
    private String recommendedCheckpointKey;

    @Schema(description = "当前是否支持基于恢复点继续执行", example = "true")
    private boolean resumeSupported;

    @Schema(description = "恢复窗口边界说明")
    private TaskRecoveryWindow recoveryWindow;

    @Schema(description = "恢复后的占位释放规则")
    private TaskRecoveryReleasePolicy releasePolicy;

    @Schema(description = "恢复建议审计引用")
    private TaskRecoveryAuditTrail auditTrail;

    @Schema(description = "建议相关追溯来源")
    private List<String> sourceUrls;
}
