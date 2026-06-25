package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一对话入口响应。
 * 响应同时承载：
 * 1. 面向用户的主解释文本；
 * 2. 意图审计摘要；
 * 3. 表单草稿或动作预览；
 * 4. 可回指来源链接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一对话入口响应")
public class ConversationResponse {

    @Schema(description = "会话 ID", example = "12")
    private Long sessionId;

    @Schema(description = "本次消息命中的内部模式", example = "EXPLAIN")
    private String mode;

    @Schema(description = "面向用户的主解释文本")
    private String answer;

    @Schema(description = "任务当前阶段说明")
    private String currentStage;

    @Schema(description = "任务级状态摘要")
    private String statusSummary;

    @Schema(description = "任务级记忆复用与当前任务上下文摘要")
    private String taskRagContextSummary;

    @Builder.Default
    @Schema(description = "本次回复可回指的来源链接")
    private List<String> sourceUrls = new ArrayList<>();

    @Schema(description = "意图审计摘要")
    private IntentDecisionSummary intentDecision;

    @Schema(description = "任务创建草稿摘要")
    private FormDraftSummary formDraft;

    @Schema(description = "任务动作预览")
    private TaskActionPreview taskActionPreview;

    @Schema(description = "任务动作执行结果")
    private TaskActionExecutionResult taskActionExecution;

    @Schema(description = "结构化澄清结果")
    private ClarificationSummary clarification;

    @Builder.Default
    @Schema(description = "研究 / 检索模式下的证据回指摘要")
    private List<RetrievalEvidence> retrievalEvidences = new ArrayList<>();

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "意图审计摘要")
    public static class IntentDecisionSummary {
        private Long decisionId;
        private String mode;
        private String intentType;
        private String decisionReason;
        private Boolean highRiskAction;
        private Boolean requiresConfirmation;
        private String riskLevel;
        private String impactScope;
        private ConversationActionConfirmationRequest confirmationRequest;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "任务创建草稿")
    public static class FormDraftSummary {
        private Long draftId;
        private String taskName;
        private String subjectProduct;
        @Builder.Default
        private List<String> competitorNames = new ArrayList<>();
        @Builder.Default
        private List<String> analysisDimensions = new ArrayList<>();
        @Builder.Default
        private List<String> sourceScope = new ArrayList<>();
        private String changeSummary;
        private String previewSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "高风险动作预览")
    public static class TaskActionPreview {
        private String actionType;
        private Long taskId;
        private String targetNodeName;
        private String title;
        private String actionSummary;
        private String impactSummary;
        private String riskLevel;
        private Boolean requiresConfirmation;
        private String confirmationHint;
        private Boolean executable;
        private OrchestrationDecisionSummary orchestrationDecision;
        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "瀵硅瘽棰勮涓殑缂栨帓鍐崇瓥鎽樿")
    public static class OrchestrationDecisionSummary {
        private String decisionId;
        private Long taskId;
        private String triggerNodeName;
        private String decisionType;
        private String actionType;
        private String targetNode;
        private String affectedScope;
        private String reason;
        private Boolean requiresHumanIntervention;
        private Boolean requiresConfirmation;
        private String evidenceState;
        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "任务动作执行结果")
    public static class TaskActionExecutionResult {
        private String actionType;
        private Long taskId;
        private String targetNodeName;
        private String executionStatus;
        private String executionMessage;
        private Long previewDecisionId;
        private Long auditDecisionId;
        private String auditStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "结构化澄清摘要")
    public static class ClarificationSummary {
        private String clarificationType;
        private String question;
        private String reason;
        @Builder.Default
        private List<String> missingSlots = new ArrayList<>();
        @Builder.Default
        private List<ClarificationOption> options = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "澄清选项")
    public static class ClarificationOption {
        private String slotName;
        private String optionValue;
        private String label;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "研究模式返回的证据卡片摘要")
    public static class RetrievalEvidence {
        private String evidenceId;
        private String title;
        private String snippet;
        private String sourceCategory;
        private String sourceUrl;
    }
}
