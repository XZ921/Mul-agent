package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

/**
 * 单个质检问题。
 */
@Data
@Builder
public class QualityIssue {

    /** 问题类型：MISSING_EVIDENCE | MISSING_SECTION | LOGIC_FLAW | BIAS_DETECTED | EMPTY_CONCLUSION */
    private String type;

    /** 问题所在章节 */
    private String section;

    /** 严重程度：ERROR | WARNING | INFO */
    private String severity;

    /** 修改建议 */
    private String suggestion;
}
