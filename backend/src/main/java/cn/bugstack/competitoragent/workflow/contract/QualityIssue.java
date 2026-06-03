package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个质检问题。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QualityIssue {

    /** 问题类型：MISSING_EVIDENCE | MISSING_SECTION | LOGIC_FLAW | BIAS_DETECTED | EMPTY_CONCLUSION */
    private String type;

    /** 问题所在章节 */
    private String section;

    /** 严重程度：ERROR | WARNING | INFO */
    private String severity;

    /** 所属诊断维度，兼容前端直接按维度分组展示。 */
    private String dimensionCode;

    /** 维度名称，便于无需二次映射就能直出 UI。 */
    private String dimensionName;

    /** 问题等级：BLOCKER | MAJOR | MINOR。 */
    private String level;

    /** 证据依据，解释为什么判定为该问题。 */
    private String evidenceBasis;

    /** 命中的证据编号列表。 */
    @Builder.Default
    private List<String> evidenceIds = List.of();

    /** 可回查的来源链接列表。 */
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 修改建议 */
    private String suggestion;
}
