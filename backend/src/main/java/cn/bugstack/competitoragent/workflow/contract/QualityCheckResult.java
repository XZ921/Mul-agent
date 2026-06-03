package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 质检节点输出契约，传递给用户。
 */
@Data
@Builder
public class QualityCheckResult {

    private final String contractVersion = "1.0";

    /** 综合评分，范围 0-100 */
    private int score;

    /** 是否通过质检，由维度状态和诊断等级共同决定，而不是只看单一总分。 */
    private boolean passed;

    /** 维度化评分结果，解释总分是如何被拆出来的。 */
    private List<QualityDimension> dimensions;

    /** 结构化诊断结果，供自动改写和前端问题展示共用。 */
    private List<QualityDiagnosis> diagnoses;

    /** 质检问题列表 */
    private List<QualityIssue> issues;

    /** 是否需要人工介入 */
    private boolean requiresHumanIntervention;

    /** 是否允许自动改写 */
    private boolean autoRewriteAllowed;

    /** 质检总结 */
    private String summary;
}
