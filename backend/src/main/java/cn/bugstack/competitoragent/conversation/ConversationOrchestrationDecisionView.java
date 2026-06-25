package cn.bugstack.competitoragent.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 对话入口只读消费的编排决策摘要。
 * 这里故意只保留动作预览真正需要的字段，避免 Conversation 反向依赖完整编排实体。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConversationOrchestrationDecisionView {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    private String targetNode;
    private String affectedScope;
    private String reason;
    private boolean requiresHumanIntervention;
    private Boolean requiresConfirmation;
    private String evidenceState;
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /**
     * 统一把历史事件里可能缺省、大小写不一致、来源重复的字段收敛成稳定视图，
     * 避免预览层每次都重复做脆弱的字符串防御。
     */
    public ConversationOrchestrationDecisionView normalized() {
        String normalizedDecisionType = upperOrDefault(decisionType, "WAIT_FOR_HUMAN");
        List<String> normalizedSourceUrls = normalizeDistinct(sourceUrls);
        String normalizedEvidenceState = upperOrDefault(
                evidenceState,
                normalizedSourceUrls.isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE");
        Boolean normalizedRequiresConfirmation = requiresConfirmation;
        if (normalizedRequiresConfirmation == null) {
            normalizedRequiresConfirmation = !"WAIT_FOR_HUMAN".equals(normalizedDecisionType);
        }
        return toBuilder()
                .decisionId(blankToNull(decisionId))
                .triggerNodeName(blankToNull(triggerNodeName))
                .decisionType(normalizedDecisionType)
                .actionType(upperOrDefault(actionType, "MANUAL_REVIEW"))
                .targetNode(blankToNull(targetNode))
                .affectedScope(upperOrDefault(affectedScope, "CURRENT_NODE_ONLY"))
                .reason(blankToDefault(reason, "当前编排决策缺少明确原因说明。"))
                .requiresHumanIntervention(requiresHumanIntervention || "WAIT_FOR_HUMAN".equals(normalizedDecisionType))
                .requiresConfirmation(normalizedRequiresConfirmation)
                .evidenceState(normalizedEvidenceState)
                .sourceUrls(normalizedSourceUrls)
                .build();
    }

    private List<String> normalizeDistinct(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String candidate = blankToNull(value);
                if (candidate != null) {
                    normalized.add(candidate);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String upperOrDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
