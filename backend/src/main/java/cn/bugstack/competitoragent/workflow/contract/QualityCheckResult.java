package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
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

    /** 是否通过质检，score >= 80 且无 ERROR */
    private boolean passed;

    /** 质检问题列表 */
    private List<ReportResponse.QualityIssue> issues;

    /** 质检总结 */
    private String summary;
}
