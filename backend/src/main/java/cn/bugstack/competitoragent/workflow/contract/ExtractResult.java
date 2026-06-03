package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 抽取节点输出契约，传递给分析节点。
 */
@Data
@Builder
public class ExtractResult {

    private final String contractVersion = "1.0";

    /** 成功抽取的竞品数量 */
    private int totalCompetitors;

    /** 每个竞品的结构化知识草稿 */
    private List<CompetitorKnowledgeDraft> drafts;

    /** 抽取阶段聚合后的来源链接 */
    private List<String> sourceUrls;

    /** 抽取阶段的问题标记汇总 */
    private List<String> issueFlags;

    /** 抽取阶段对下游公开的统一证据片段 */
    private List<EvidenceFragment> evidenceFragments;
}
