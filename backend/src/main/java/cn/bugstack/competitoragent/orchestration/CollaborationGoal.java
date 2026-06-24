package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 前置协作目标契约。
 * 它描述 Orchestrator 要完成的任务事实和预算约束，不代表最终业务报告结论。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CollaborationGoal {

    private String goalId;
    private Long taskId;
    private String subject;
    @Builder.Default
    private List<String> competitors = List.of();
    @Builder.Default
    private List<String> analysisDimensions = List.of();
    private String deliverableType;
    private String depth;
    @Builder.Default
    private Map<String, Object> budget = Map.of();
    @Builder.Default
    private Map<String, Object> constraints = Map.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 归一化协作目标，保证目标进入计划服务前已经具备稳定枚举文本和显式来源状态。
     */
    public CollaborationGoal normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        return toBuilder()
                .goalId(OrchestrationTextNormalizer.blankToNull(goalId))
                .subject(OrchestrationTextNormalizer.blankToNull(subject))
                .competitors(OrchestrationTextNormalizer.normalizeDistinctList(competitors))
                .analysisDimensions(OrchestrationTextNormalizer.normalizeDistinctList(analysisDimensions))
                .deliverableType(OrchestrationTextNormalizer.upperOrDefault(deliverableType, "COMPETITOR_REPORT"))
                .depth(OrchestrationTextNormalizer.upperOrDefault(depth, "STANDARD"))
                .budget(OrchestrationTextNormalizer.normalizeObjectMap(budget))
                .constraints(OrchestrationTextNormalizer.normalizeObjectMap(constraints))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .build();
    }
}
