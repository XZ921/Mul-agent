package cn.bugstack.competitoragent.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 分析任务状态枚举
 * <p>
 * 状态流转：
 * <pre>
 * PENDING → RUNNING → SUCCESS
 *                   → FAILED
 * </pre>
 */
@Getter
@Schema(description = "分析任务状态")
public enum AnalysisTaskStatus {

    @Schema(description = "待执行")
    PENDING("待执行"),

    @Schema(description = "执行中")
    RUNNING("执行中"),

    @Schema(description = "执行成功")
    SUCCESS("执行成功"),

    @Schema(description = "执行失败")
    FAILED("执行失败");

    private final String description;

    AnalysisTaskStatus(String description) {
        this.description = description;
    }
}
