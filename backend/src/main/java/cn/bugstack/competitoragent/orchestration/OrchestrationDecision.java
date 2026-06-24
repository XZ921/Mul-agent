package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrator 的正式运行期编排决策。
 * Reviewer 只负责输出质量事实，本对象才表达“接下来由编排层做什么”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationDecision {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String decisionType;
    private String actionType;
    private String targetNode;
    private String affectedScope;
    private String priority;
    private String targetSection;
    private String reason;
    private boolean requiresHumanIntervention;
    private boolean requiresConfirmation;
    private Double confidence;
    @Builder.Default
    private List<String> suggestedQueries = List.of();
    @Builder.Default
    private Map<String, Object> inputRefs = Map.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 统一归一化编排决策，确保策略服务拿到的是稳定枚举文本、去重来源和显式证据状态。
     * 缺少 sourceUrls 时不会被默认为可靠事实，而是自动落到 MISSING_SOURCE。
     */
    public OrchestrationDecision normalized() {
        String normalizedDecisionType = upperOrDefault(decisionType, "WAIT_FOR_HUMAN");
        List<String> normalizedSourceUrls = normalizeDistinctList(sourceUrls);
        EvidenceState resolvedEvidenceState = evidenceState == null
                ? resolveEvidenceState(normalizedSourceUrls)
                : evidenceState;
        double normalizedConfidence = confidence == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, confidence));
        return toBuilder()
                .decisionType(normalizedDecisionType)
                .actionType(upperOrDefault(actionType, "MANUAL_REVIEW"))
                .targetNode(blankToNull(targetNode))
                .affectedScope(upperOrDefault(affectedScope, "CURRENT_NODE_ONLY"))
                .priority(upperOrDefault(priority, "MEDIUM"))
                .targetSection(blankToNull(targetSection))
                .reason(blankToDefault(reason, "编排决策缺少明确原因，已降级为人工确认。"))
                .requiresHumanIntervention(requiresHumanIntervention || "WAIT_FOR_HUMAN".equals(normalizedDecisionType))
                .confidence(normalizedConfidence)
                .suggestedQueries(normalizeDistinctList(suggestedQueries))
                .inputRefs(normalizeInputRefs(inputRefs))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(resolvedEvidenceState)
                .build();
    }

    private EvidenceState resolveEvidenceState(List<String> normalizedSourceUrls) {
        return normalizedSourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
    }

    private String upperOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = blankToNull(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private Map<String, Object> normalizeInputRefs(Map<String, Object> value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value != null && !value.isEmpty()) {
            normalized.putAll(value);
        }
        normalized.putIfAbsent("qualityDiagnosisIds", List.of());
        normalized.putIfAbsent("agentSuggestionIds", List.of());
        normalized.putIfAbsent("triggerNodeName", blankToNull(triggerNodeName));
        return normalized;
    }
}
