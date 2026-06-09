package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationActionConfirmationRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话入口的安全动作策略。
 * 它负责把模式识别结果与动作预览合并成统一的风险语义，
 * 让“风险等级、影响范围、确认对象”在接口返回和审计落库时都使用同一套结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSafetyPolicy {

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";

    public static final String IMPACT_NONE = "NONE";
    public static final String IMPACT_TASK_EXECUTION = "TASK_EXECUTION";
    public static final String IMPACT_TASK_EVIDENCE_CHAIN = "TASK_EVIDENCE_CHAIN";
    public static final String IMPACT_CURRENT_NODE_AND_DOWNSTREAM = "CURRENT_NODE_AND_DOWNSTREAM";

    private boolean highRiskAction;
    private boolean requiresConfirmation;
    private String riskLevel;
    private String impactScope;
    private ConversationActionConfirmationRequest confirmationRequest;

    /**
     * 统一把识别结果和动作预览折叠成结构化安全策略。
     * 这里优先复用动作预览已经给出的风险提示，避免不同层重复推导后出现口径漂移。
     */
    public static ConversationSafetyPolicy from(ConversationMode mode,
                                                IntentRecognitionService.RecognitionResult recognitionResult,
                                                ConversationResponse.TaskActionPreview preview) {
        boolean highRiskAction = recognitionResult != null && recognitionResult.isHighRiskAction();
        boolean requiresConfirmation = recognitionResult != null && recognitionResult.isRequiresConfirmation();

        if (preview != null && Boolean.TRUE.equals(preview.getRequiresConfirmation())) {
            requiresConfirmation = true;
        }

        String riskLevel = resolveRiskLevel(highRiskAction, preview);
        if (RISK_HIGH.equals(riskLevel) || RISK_MEDIUM.equals(riskLevel)) {
            highRiskAction = true;
        }

        String impactScope = resolveImpactScope(mode, preview);
        ConversationActionConfirmationRequest confirmationRequest = requiresConfirmation
                ? buildConfirmationRequest(preview, impactScope, riskLevel)
                : null;

        return ConversationSafetyPolicy.builder()
                .highRiskAction(highRiskAction)
                .requiresConfirmation(requiresConfirmation)
                .riskLevel(riskLevel)
                .impactScope(impactScope)
                .confirmationRequest(confirmationRequest)
                .build();
    }

    private static String resolveRiskLevel(boolean highRiskAction,
                                           ConversationResponse.TaskActionPreview preview) {
        if (preview != null && preview.getRiskLevel() != null && !preview.getRiskLevel().isBlank()) {
            return preview.getRiskLevel().trim().toUpperCase(java.util.Locale.ROOT);
        }
        return highRiskAction ? RISK_HIGH : RISK_LOW;
    }

    /**
     * 影响范围只表达“会波及哪类业务对象”，不重复承载自然语言说明。
     * 这样前端可以稳定渲染标签，审计链路也能按枚举聚合风险动作。
     */
    private static String resolveImpactScope(ConversationMode mode,
                                             ConversationResponse.TaskActionPreview preview) {
        if (preview != null && "RERUN_NODE".equalsIgnoreCase(safe(preview.getActionType()))) {
            return IMPACT_CURRENT_NODE_AND_DOWNSTREAM;
        }
        if (preview != null && "SUPPLEMENT_EVIDENCE".equalsIgnoreCase(safe(preview.getActionType()))) {
            return IMPACT_TASK_EVIDENCE_CHAIN;
        }
        if (preview != null && "RESUME_TASK".equalsIgnoreCase(safe(preview.getActionType()))) {
            return IMPACT_TASK_EXECUTION;
        }
        if (mode == ConversationMode.RESEARCH) {
            return IMPACT_TASK_EVIDENCE_CHAIN;
        }
        if (mode == ConversationMode.TASK_ACTION) {
            return IMPACT_TASK_EXECUTION;
        }
        return IMPACT_NONE;
    }

    private static ConversationActionConfirmationRequest buildConfirmationRequest(
            ConversationResponse.TaskActionPreview preview,
            String impactScope,
            String riskLevel) {
        if (preview == null) {
            return ConversationActionConfirmationRequest.builder()
                    .actionType("UNKNOWN")
                    .targetType("TASK")
                    .confirmationTitle("请确认高风险动作")
                    .confirmationMessage("系统判断该动作存在风险，请先确认影响范围。")
                    .impactScope(impactScope)
                    .riskLevel(riskLevel)
                    .build();
        }

        String targetNodeName = safe(preview.getTargetNodeName());
        boolean targetNodeExists = !targetNodeName.isBlank();
        return ConversationActionConfirmationRequest.builder()
                .actionType(preview.getActionType())
                .targetType(targetNodeExists ? "TASK_NODE" : "TASK")
                .targetId(targetNodeExists ? targetNodeName : safeTaskId(preview.getTaskId()))
                .confirmationTitle(firstNonBlank(preview.getTitle(), "请确认高风险动作"))
                .confirmationMessage(firstNonBlank(preview.getConfirmationHint(), "系统判断该动作存在风险，请先确认影响范围。"))
                .impactScope(impactScope)
                .impactSummary(preview.getImpactSummary())
                .riskLevel(riskLevel)
                .build();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeTaskId(Long taskId) {
        return taskId == null ? "" : String.valueOf(taskId);
    }
}
