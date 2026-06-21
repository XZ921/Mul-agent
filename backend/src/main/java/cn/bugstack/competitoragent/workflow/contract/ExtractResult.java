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

    /** 抽取阶段聚合出的章节证据束，承接字段覆盖与证据缺口语义 */
    private List<SectionEvidenceBundle> sectionEvidenceBundles;

    /** 抽取阶段实际消费并传递给下游的统一证据运行期视图 */
    private List<DownstreamEvidenceView> downstreamEvidenceViews;
}
