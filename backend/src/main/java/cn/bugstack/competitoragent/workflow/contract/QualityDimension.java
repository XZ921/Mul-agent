package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质检维度定义。
 * 用统一维度承接“评分、状态、评估标准”，
 * 让后续自动改写与前端展示都能直接解释每个分数的来由。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QualityDimension {

    /** 维度编码，供节点输出和前端渲染稳定引用。 */
    private String code;

    /** 维度名称，面向用户直接展示。 */
    private String name;

    /** 维度说明，解释这一类问题到底在检查什么。 */
    private String description;

    /** 评估标准，明确什么情况下可判定为健康。 */
    private String evaluationStandard;

    /** 当前维度得分。 */
    private int score;

    /** 维度满分，默认 100，便于后续做比例展示。 */
    @Builder.Default
    private int maxScore = 100;

    /** 维度状态：HEALTHY / ATTENTION / CRITICAL / NOT_EVALUATED。 */
    private String status;

    /**
     * 统一归一化分值和状态。
     * 这样无论上游按整数还是比例传分，下游都能拿到稳定的状态判定。
     */
    public QualityDimension normalized() {
        int normalizedMaxScore = maxScore <= 0 ? 100 : maxScore;
        int normalizedScore = Math.max(0, Math.min(normalizedMaxScore, score));
        return this.toBuilder()
                .maxScore(normalizedMaxScore)
                .score(normalizedScore)
                .status(resolveStatus(normalizedScore, normalizedMaxScore, status))
                .build();
    }

    private String resolveStatus(int normalizedScore, int normalizedMaxScore, String currentStatus) {
        if (currentStatus != null && !currentStatus.isBlank()) {
            return currentStatus.trim();
        }
        if (normalizedMaxScore <= 0) {
            return "NOT_EVALUATED";
        }
        double ratio = (double) normalizedScore / normalizedMaxScore;
        if (ratio >= 0.80d) {
            return "HEALTHY";
        }
        if (ratio >= 0.60d) {
            return "ATTENTION";
        }
        return "CRITICAL";
    }
}
