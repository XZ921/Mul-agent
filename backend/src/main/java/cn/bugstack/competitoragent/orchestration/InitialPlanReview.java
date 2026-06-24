package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 初始协作计划校验结果。
 * 它记录计划是否能落到现有 DAG 模板和哪些策略已执行，不输出业务分析结论。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InitialPlanReview {

    private String reviewId;
    private String planId;
    private boolean allowed;
    @Builder.Default
    private List<String> blockedReasons = List.of();
    @Builder.Default
    private List<String> requiredAdjustments = List.of();
    private String mappedWorkflowTemplate;
    @Builder.Default
    private List<String> policyRuleRefs = List.of();
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;

    /**
     * 归一化计划校验结果，保证拒绝态不会误带可执行模板，并补齐来源状态。
     */
    public InitialPlanReview normalized() {
        List<String> normalizedSourceUrls = OrchestrationTextNormalizer.normalizeDistinctList(sourceUrls);
        String normalizedTemplate = allowed
                ? OrchestrationTextNormalizer.upperOrDefault(mappedWorkflowTemplate, "STANDARD_COMPETITOR_ANALYSIS_V1")
                : "UNMAPPED";
        return toBuilder()
                .reviewId(OrchestrationTextNormalizer.blankToNull(reviewId))
                .planId(OrchestrationTextNormalizer.blankToNull(planId))
                .blockedReasons(OrchestrationTextNormalizer.normalizeDistinctList(blockedReasons))
                .requiredAdjustments(OrchestrationTextNormalizer.normalizeDistinctList(requiredAdjustments))
                .mappedWorkflowTemplate(normalizedTemplate)
                .policyRuleRefs(OrchestrationTextNormalizer.normalizeDistinctList(policyRuleRefs))
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(OrchestrationTextNormalizer.resolveEvidenceState(evidenceState, normalizedSourceUrls))
                .build();
    }
}
