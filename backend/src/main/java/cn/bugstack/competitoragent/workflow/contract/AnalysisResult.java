package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分析节点输出契约，负责把 Analyzer 的结构化结果稳定地传递给 Writer 和编排层。
 */
@Data
@Builder
public class AnalysisResult {

    private final String contractVersion = "1.0";

    /** 总体概述。 */
    private String overview;

    /** 功能对比分析。 */
    private String featureComparison;

    /** 市场定位对比。 */
    private String positioningComparison;

    /** 定价策略对比。 */
    private String pricingComparison;

    /** 目标用户对比。 */
    private String targetUserComparison;

    /** 优势总结。 */
    private String strengthsSummary;

    /** 短板总结。 */
    private String weaknessesSummary;

    /** 市场机会列表。 */
    private List<String> opportunities;

    /** 风险列表。 */
    private List<String> risks;

    /** 建议列表。 */
    private List<String> recommendations;

    /** 当前分析结论可回指的来源链接。 */
    private List<String> sourceUrls;

    /** 分析置信度，供编排层判断是否需要补证或人工介入。 */
    private String analysisConfidence;

    /** Analyzer 未能形成有效结论的核心分析维度。 */
    private List<String> missingAnalysisDimensions;

    /** 分析缺口严重程度，便于编排层区分放行、补证和阻断。 */
    private String analysisGapSeverity;

    /** 分析阶段的证据状态，用于追踪是否存在无来源结论风险。 */
    private String analysisEvidenceState;

    /** 分析节点实际消费的 Task RAG 文本摘要，供运行态审计与报告联合回查。 */
    private String taskRagContext;

    /** 分析阶段的问题标记，例如字段漂移、证据缺口或回填行为。 */
    private List<String> issueFlags;

    /** 提供给 Writer 的统一证据片段。 */
    private List<EvidenceFragment> evidenceFragments;

    /** 章节级证据束，供 Writer 和回放接口直接消费。 */
    private List<SectionEvidenceBundle> sectionEvidenceBundles;

    /** Analyzer 沿用的统一下游证据视图。 */
    private List<DownstreamEvidenceView> downstreamEvidenceViews;
}
