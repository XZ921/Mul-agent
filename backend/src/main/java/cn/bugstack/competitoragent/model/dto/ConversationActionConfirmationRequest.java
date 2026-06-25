package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 高风险动作确认对象。
 * 该对象把“用户到底要确认什么、会影响哪里、为什么需要确认”压缩成稳定结构，
 * 避免前端或审计链路只能依赖自然语言提示二次猜测。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "高风险动作确认对象")
public class ConversationActionConfirmationRequest {

    @Schema(description = "待确认动作类型", example = "RERUN_NODE")
    private String actionType;

    @Schema(description = "待确认目标类型", example = "TASK_NODE")
    private String targetType;

    @Schema(description = "待确认目标标识", example = "rewrite_report")
    private String targetId;

    @Schema(description = "确认标题")
    private String confirmationTitle;

    @Schema(description = "确认说明")
    private String confirmationMessage;

    @Schema(description = "影响范围枚举", example = "CURRENT_NODE_AND_DOWNSTREAM")
    private String impactScope;

    @Schema(description = "影响范围说明")
    private String impactSummary;

    @Schema(description = "风险等级", example = "HIGH")
    private String riskLevel;

    @Schema(description = "关联的编排决策 ID")
    private String orchestrationDecisionId;

    @Schema(description = "关联的编排决策类型", example = "WAIT_FOR_HUMAN")
    private String orchestrationDecisionType;

    @Schema(description = "关联的编排证据状态", example = "MISSING_SOURCE")
    private String orchestrationEvidenceState;
}
