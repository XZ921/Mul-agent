package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 运行期编排上下文。
 * P1 只承载终审失败后的最小输入，不接入 P2 的前置协作规划。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationContext {

    private Long taskId;
    private Long planVersionId;
    private String branchKey;
    private String triggerNodeName;
    private String reviewStage;
    private String taskStatus;
    private boolean passed;
    private boolean requiresHumanIntervention;
    private int currentDecisionCount;
    @Builder.Default
    private List<QualityDiagnosis> diagnoses = List.of();
    @Builder.Default
    private List<RevisionDirective> legacyRevisionDirectives = List.of();
    @Builder.Default
    private List<AgentSuggestion> agentSuggestions = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String inputSummary;

    /**
     * 归一化运行期上下文，保证后续决策服务不会因为空列表或缺失证据状态产生隐式判断。
     */
    public OrchestrationContext normalized() {
        List<String> normalizedSourceUrls = normalizeDistinctList(sourceUrls);
        return toBuilder()
                .triggerNodeName(blankToNull(triggerNodeName))
                .reviewStage(blankToNull(reviewStage))
                .taskStatus(blankToNull(taskStatus))
                .currentDecisionCount(Math.max(0, currentDecisionCount))
                .diagnoses(diagnoses == null ? List.of() : diagnoses)
                .legacyRevisionDirectives(legacyRevisionDirectives == null ? List.of() : legacyRevisionDirectives)
                // P2 起运行期上下文可以携带业务 Agent 的建议，但建议只作为 Orchestrator 输入，
                // 不能绕过 DecisionPolicyService 直接执行。
                .agentSuggestions(agentSuggestions == null ? List.of() : agentSuggestions.stream()
                        .map(AgentSuggestion::normalized)
                        .toList())
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(evidenceState == null ? resolveEvidenceState(normalizedSourceUrls) : evidenceState)
                .inputSummary(blankToNull(inputSummary))
                .build();
    }

    private EvidenceState resolveEvidenceState(List<String> normalizedSourceUrls) {
        return normalizedSourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
