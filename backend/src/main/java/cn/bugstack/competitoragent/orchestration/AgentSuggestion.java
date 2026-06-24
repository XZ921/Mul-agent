package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 产生的协作建议。
 * 它只能作为 Orchestrator 决策输入，不能直接表达最终执行动作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentSuggestion {

    private String suggestionId;
    private Long taskId;
    private String producerNodeName;
    private String producerAgentType;
    private String suggestionType;
    private String targetSection;
    private String summary;
    private String severity;
    private Double confidence;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    @Builder.Default
    private List<String> suggestedQueries = List.of();
    private String suggestedTargetNode;

    /**
     * 归一化 Agent 建议，裁剪置信度并保留 suggestedTargetNode 作为提示而非执行授权。
     */
    public AgentSuggestion normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        return toBuilder()
                .suggestionId(OrchestrationTextNormalizer.blankToNull(suggestionId))
                .producerNodeName(OrchestrationTextNormalizer.blankToNull(producerNodeName))
                .producerAgentType(OrchestrationTextNormalizer.upperOrDefault(producerAgentType, "UNKNOWN"))
                .suggestionType(OrchestrationTextNormalizer.upperOrDefault(suggestionType, "EVIDENCE_GAP"))
                .targetSection(OrchestrationTextNormalizer.blankToNull(targetSection))
                .summary(OrchestrationTextNormalizer.blankToDefault(summary, "Agent 建议缺少摘要，已降级为人工检查。"))
                .severity(OrchestrationTextNormalizer.upperOrDefault(severity, "MEDIUM"))
                .confidence(OrchestrationTextNormalizer.clampConfidence(confidence, 0.5d))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .suggestedQueries(OrchestrationTextNormalizer.normalizeDistinctList(suggestedQueries))
                .suggestedTargetNode(OrchestrationTextNormalizer.blankToNull(suggestedTargetNode))
                .build();
    }
}
