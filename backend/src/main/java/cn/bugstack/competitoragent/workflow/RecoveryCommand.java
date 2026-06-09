package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复动作命令。
 * <p>
 * RecoveryEngine 只负责计算“下一步该做什么”，
 * 真正修改任务、节点和派生产物的动作仍由 TaskRecoveryService 执行。
 */
@Data
@Builder
@Schema(description = "恢复动作命令")
public class RecoveryCommand {

    @Schema(description = "恢复动作类型")
    private ActionType actionType;

    @Schema(description = "动作执行后期望写回的节点状态")
    private TaskNodeStatus targetStatus;

    @Schema(description = "动作原因")
    private String reason;

    @Schema(description = "面向工作台用户的动作摘要")
    private String userReadableSummary;

    @Schema(description = "恢复窗口范围类型", example = "ACTIVE_PLAN_BRANCH")
    private String recoveryWindowScope;

    @Schema(description = "恢复窗口关联计划版本 ID", example = "12")
    private Long recoveryPlanVersionId;

    @Schema(description = "恢复窗口关联分支标识", example = "root/review-3")
    private String recoveryBranchKey;

    @Schema(description = "恢复边界节点名称列表")
    private List<String> boundaryNodeNames;

    @Schema(description = "当前窗口允许回放的事件 ID 列表")
    private List<String> replayableEventIds;

    @Schema(description = "恢复窗口起始时间")
    private LocalDateTime recoveryWindowStartAt;

    @Schema(description = "恢复窗口结束时间")
    private LocalDateTime recoveryWindowEndAt;

    @Schema(description = "是否释放任务执行占位", example = "true")
    private boolean releaseTaskExecutionLock;

    @Schema(description = "是否释放节点执行占位", example = "true")
    private boolean releaseNodeExecutionLocks;

    @Schema(description = "占位释放说明")
    private String releaseReason;

    @Schema(description = "决策来源", example = "RECOVERY_ENGINE")
    private String decisionSource;

    @Schema(description = "触发当前恢复判断的事件 ID", example = "evt-101")
    private String triggerEventId;

    @Schema(description = "最近一次尝试 ID", example = "301")
    private Long latestAttemptId;

    @Schema(description = "最近一次尝试编号", example = "2")
    private Integer latestAttemptNo;

    public enum ActionType {
        REQUEUE_NODE,
        AWAIT_MANUAL_INTERVENTION,
        EXECUTE_COMPENSATION,
        FINALIZE_FAILURE
    }
}
