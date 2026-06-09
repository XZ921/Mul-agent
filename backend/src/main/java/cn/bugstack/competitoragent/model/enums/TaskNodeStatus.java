package cn.bugstack.competitoragent.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * DAG 节点权威状态。
 * <p>
 * Phase 4 起，节点状态需要同时描述：
 * 1. 编排层是否已经确认可调度；
 * 2. 节点是否已派发、执行中或等待自动重试；
 * 3. 是否正在等待人工处理；
 * 4. 是否已经通过补偿动作完成收口。
 * <p>
 * 任务级对外状态仍维持 `PENDING / RUNNING / SUCCESS / FAILED / STOPPED` 五态，
 * 节点层才承载更细粒度的恢复和补偿语义。
 */
@Getter
@Schema(description = "DAG 节点权威状态")
public enum TaskNodeStatus {

    @Schema(description = "等待编排层判断依赖和推进条件")
    PENDING("等待编排"),

    @Schema(description = "依赖已满足，等待调度器抢占")
    READY("等待调度"),

    @Schema(description = "已完成权威派发，等待执行器真正开始")
    DISPATCHED("已派发"),

    @Schema(description = "节点执行中")
    RUNNING("执行中"),

    @Schema(description = "等待系统自动重试")
    WAITING_RETRY("等待重试"),

    @Schema(description = "等待人工介入或人工批准")
    WAITING_INTERVENTION("等待人工处理"),

    @Schema(description = "人工暂停，暂不参与自动编排")
    PAUSED("已暂停"),

    @Schema(description = "节点执行成功")
    SUCCESS("执行成功"),

    @Schema(description = "失败后已执行补偿动作并完成收口")
    COMPENSATED("已补偿"),

    @Schema(description = "节点进入不可自动恢复的失败终态")
    FAILED("执行失败"),

    @Schema(description = "节点被显式跳过")
    SKIPPED("已跳过");

    private final String description;

    TaskNodeStatus(String description) {
        this.description = description;
    }
}
