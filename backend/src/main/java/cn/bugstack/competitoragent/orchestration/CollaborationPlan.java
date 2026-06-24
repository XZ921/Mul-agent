package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 前置协作计划契约。
 * 它承载角色、检查点和来源边界，后续只能映射到受控 Workflow 模板。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CollaborationPlan {

    private String planId;
    private String goalId;
    private Long taskId;
    private String planningMode;
    @Builder.Default
    private List<AgentRoleAssignment> agentRoleAssignments = List.of();
    @Builder.Default
    private List<String> checkpoints = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 归一化协作计划，逐个清洗角色分工，并保持 checkpoint 作为受控模板锚点。
     */
    public CollaborationPlan normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        List<AgentRoleAssignment> normalizedRoles = new ArrayList<>();
        if (agentRoleAssignments != null) {
            for (AgentRoleAssignment role : agentRoleAssignments) {
                if (role != null) {
                    normalizedRoles.add(role.normalized());
                }
            }
        }
        return toBuilder()
                .planId(OrchestrationTextNormalizer.blankToNull(planId))
                .goalId(OrchestrationTextNormalizer.blankToNull(goalId))
                .planningMode(OrchestrationTextNormalizer.upperOrDefault(planningMode, "ORCHESTRATOR_FIRST"))
                .agentRoleAssignments(normalizedRoles)
                .checkpoints(OrchestrationTextNormalizer.normalizeDistinctList(checkpoints))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .build();
    }
}
