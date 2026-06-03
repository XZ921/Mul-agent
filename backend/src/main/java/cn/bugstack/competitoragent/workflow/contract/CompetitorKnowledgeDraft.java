package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.List;

/**
 * 单个竞品知识草稿，抽取节点输出的结构化数据。
 */
@Data
@Builder
public class CompetitorKnowledgeDraft {

    private String competitorName;

    private String officialUrl;

    /** 100 字以内产品简介 */
    private String summary;

    /** 市场定位描述 */
    private String positioning;

    /** 目标用户列表 */
    private List<String> targetUsers;

    /** 核心功能列表及 evidenceIds */
    private List<FeatureItem> coreFeatures;

    /** 定价信息及 evidenceIds */
    private PricingItem pricing;

    /** 优势列表及 evidenceIds */
    private List<StrengthWeaknessItem> strengths;

    /** 劣势列表及 evidenceIds */
    private List<StrengthWeaknessItem> weaknesses;

    /** 草稿层面的统一来源链接 */
    private List<String> sourceUrls;

    /** 草稿层面的统一证据片段 */
    private List<EvidenceFragment> evidenceFragments;

    /** 草稿层面的缺口/问题标记 */
    private List<String> issueFlags;

    /** 字段级证据覆盖摘要，供分析/质检阶段继续使用 */
    private Map<String, Object> evidenceCoverage;

    /** 成功抽取的字段数 */
    private int fieldsExtracted;

    /** 抽取状态 */
    private String status;
}
