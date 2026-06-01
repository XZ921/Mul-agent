package cn.bugstack.competitoragent.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * DAG 节点执行状态枚举
 * <p>
 * 状态流转：
 * <pre>
 * PENDING → RUNNING → SUCCESS
 *                   → FAILED → (可选) RETRY → RUNNING ...
 * PENDING → PAUSED → PENDING
 * </pre>
 */
@Getter
@Schema(description = "DAG 节点执行状态")
public enum TaskNodeStatus {

    @Schema(description = "等待执行")
    PENDING("等待执行"),

    @Schema(description = "执行中")
    RUNNING("执行中"),

    @Schema(description = "已暂停")
    PAUSED("已暂停"),

    @Schema(description = "执行成功")
    SUCCESS("执行成功"),

    @Schema(description = "执行失败")
    FAILED("执行失败"),

    @Schema(description = "已跳过（上游失败导致）")
    SKIPPED("已跳过");

    private final String description;

    TaskNodeStatus(String description) {
        this.description = description;
    }
}
