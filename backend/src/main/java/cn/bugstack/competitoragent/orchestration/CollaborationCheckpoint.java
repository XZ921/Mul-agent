package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协作计划恢复检查点。
 * 它保存 Orchestrator 从哪里恢复、还剩哪些动作，不保存报告结论。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CollaborationCheckpoint {

    private String checkpointId;
    private Long taskId;
    private String goalId;
    private String planId;
    private String lastReviewId;
    private String phase;
    private Long mappedWorkflowPlanId;
    @Builder.Default
    private List<String> pendingActions = List.of();
    private String resumeReason;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 归一化恢复检查点，阶段使用稳定大写文本，来源缺失时显式标记为 MISSING_SOURCE。
     */
    public CollaborationCheckpoint normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        return toBuilder()
                .checkpointId(OrchestrationTextNormalizer.blankToNull(checkpointId))
                .goalId(OrchestrationTextNormalizer.blankToNull(goalId))
                .planId(OrchestrationTextNormalizer.blankToNull(planId))
                .lastReviewId(OrchestrationTextNormalizer.blankToNull(lastReviewId))
                .phase(OrchestrationTextNormalizer.upperOrDefault(phase, "PLAN_PENDING"))
                .pendingActions(OrchestrationTextNormalizer.normalizeDistinctList(pendingActions))
                .resumeReason(OrchestrationTextNormalizer.blankToNull(resumeReason))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .build();
    }
}
