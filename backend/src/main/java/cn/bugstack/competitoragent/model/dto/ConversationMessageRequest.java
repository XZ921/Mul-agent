package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 统一对话入口请求。
 * 这里允许同一个入口承接任务创建、任务详情和报告页的上下文化消息。
 */
@Data
@Schema(description = "统一对话入口请求")
public class ConversationMessageRequest {

    @Schema(description = "会话 ID。首次对话可为空，服务端会自动创建。", example = "12")
    private Long sessionId;

    @Schema(description = "关联任务 ID。任务详情页和报告页场景下通常会传入。", example = "88")
    private Long taskId;

    @Schema(description = "关联报告 ID。报告页场景下可选传入。", example = "3")
    private Long reportId;

    @NotBlank(message = "pageType 不能为空")
    @Schema(description = "当前页面上下文，例如 TASK_CREATE / TASK_DETAIL / REPORT。", example = "TASK_DETAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pageType;

    @NotBlank(message = "message 不能为空")
    @Schema(description = "用户自然语言消息。", example = "这个任务为什么停在这里了？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "是否确认执行上一轮高风险动作预览", example = "true")
    private Boolean executeConfirmedAction;

    /**
     * 前端在确认阶段直接回传上一轮预览里生成的结构化确认对象，
     * 避免服务端再次依赖自然语言猜测到底要执行哪个动作。
     */
    @Schema(description = "结构化高风险动作确认对象")
    private ConversationActionConfirmationRequest confirmationRequest;
}
