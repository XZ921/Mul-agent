package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

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

    /** 成功抽取的字段数 */
    private int fieldsExtracted;

    /** 抽取状态 */
    private String status;
}
