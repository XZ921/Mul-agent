package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 已校验编排决策对应的动态计划变更。
 * 它只描述“计划要怎样变”，不直接执行任何 Agent 或外部工具。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DynamicPlanMutation {

    private String mutationId;
    private String decisionId;
    private String mutationType;
    private Long targetPlanVersionId;
    private String branchReason;
    private String dynamicAction;
    @Builder.Default
    private List<WorkflowPlan.WorkflowPlanNode> nodeTemplates = List.of();
    private String runtimeCommand;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private String expectedResumeNodeName;

    /**
     * 归一化动态计划变更，保证补图入口只接收稳定动作和安全默认值。
     */
    public DynamicPlanMutation normalized() {
        return toBuilder()
                .mutationType(upperOrDefault(mutationType, "NO_MUTATION"))
                .branchReason(upperOrDefault(branchReason, "ORCHESTRATOR_DECISION"))
                .dynamicAction(upperOrDefault(dynamicAction, "NO_ACTION"))
                .nodeTemplates(nodeTemplates == null ? List.of() : nodeTemplates)
                .runtimeCommand(blankToNull(runtimeCommand))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceState(resolveEvidenceState())
                .expectedResumeNodeName(blankToNull(expectedResumeNodeName))
                .build();
    }

    private EvidenceState resolveEvidenceState() {
        if (evidenceState != null) {
            return evidenceState;
        }
        return sourceUrls == null || sourceUrls.isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    private String upperOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
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
