package cn.bugstack.competitoragent.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 节点失败分类。
 * <p>
 * 该分类只描述“失败为什么发生、接下来应该进入哪条恢复语义”，
 * 不直接替代节点状态本身。这样可以避免把“失败类型”和“状态推进”
 * 混写在同一个枚举里，导致恢复链路越来越难维护。
 */
@Getter
@Schema(description = "节点失败分类")
public enum NodeFailureCategory {

    @Schema(description = "瞬时基础设施失败，可由系统自动重试")
    TRANSIENT_INFRASTRUCTURE(true, false, false, "系统将自动重试"),

    @Schema(description = "业务前置条件或输入永久不满足，不能自动重试")
    PERMANENT_BUSINESS(false, false, false, "需要修正输入或配置后再继续"),

    @Schema(description = "需要人工判断或批准后才能继续")
    MANUAL_INTERVENTION_REQUIRED(false, true, false, "等待人工处理"),

    @Schema(description = "需要走补偿动作而不是直接重试")
    COMPENSATABLE(false, true, true, "需要先执行补偿"),

    @Schema(description = "未知失败，默认按人工介入处理以避免误重试")
    UNKNOWN(false, true, false, "需要人工确认");

    private final boolean retryable;
    private final boolean requiresManualIntervention;
    private final boolean compensatable;
    private final String defaultUserSummary;

    NodeFailureCategory(boolean retryable,
                        boolean requiresManualIntervention,
                        boolean compensatable,
                        String defaultUserSummary) {
        this.retryable = retryable;
        this.requiresManualIntervention = requiresManualIntervention;
        this.compensatable = compensatable;
        this.defaultUserSummary = defaultUserSummary;
    }
}
