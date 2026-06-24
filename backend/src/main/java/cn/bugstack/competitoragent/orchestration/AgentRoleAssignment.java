package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协作计划中的 Agent 角色分工。
 * 它只声明角色使命、依赖和质量门槛，不生成自由 DAG 节点。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentRoleAssignment {

    private String roleId;
    private String agentType;
    private String mission;
    @Builder.Default
    private List<String> expectedOutputs = List.of();
    @Builder.Default
    private List<String> dependsOn = List.of();
    private String qualityGate;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 归一化角色分工，确保角色类型可被策略校验稳定识别，并保留来源审计边界。
     */
    public AgentRoleAssignment normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        return toBuilder()
                .roleId(OrchestrationTextNormalizer.blankToNull(roleId))
                .agentType(OrchestrationTextNormalizer.upperOrDefault(agentType, "UNKNOWN"))
                .mission(OrchestrationTextNormalizer.blankToNull(mission))
                .expectedOutputs(OrchestrationTextNormalizer.normalizeDistinctList(expectedOutputs))
                .dependsOn(OrchestrationTextNormalizer.normalizeDistinctList(dependsOn))
                .qualityGate(OrchestrationTextNormalizer.blankToNull(qualityGate))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .build();
    }
}
