package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 协作决策只读摘要 DTO。
 * 它只承载 delivery / export / replay 需要稳定消费的字段，
 * 不反向暴露完整 OrchestrationDecision 实体，避免读模型重新耦合运行时写模型。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "只读协作决策摘要")
public class OrchestrationDecisionSummary {

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
     * 历史事件里的协作决策字段可能存在大小写不一致、空值或来源重复；
     * 这里统一归一化成稳定读模型，保证前端和导出链路不必重复做脆弱清洗。
     */
    public OrchestrationDecisionSummary normalized() {
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
                .reason(blankToDefault(reason, "当前协作决策缺少明确原因说明。"))
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
