package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 采集节点输出契约，传递给抽取节点。
 */
@Data
@Builder
public class CollectResult {

    private final String contractVersion = "1.0";

    /** 本次采集的文档总数 */
    private int totalCollected;

    /** 生成的证据编号总数 */
    private int totalEvidenceIds;

    /** 采集结果明细 */
    private List<CollectedDocument> documents;

    /** 采集阶段聚合后的全部可追溯来源链接 */
    private List<String> sourceUrls;

    /** 采集阶段的问题标记汇总 */
    private List<String> issueFlags;

    /** 采集阶段对下游公开的统一证据片段 */
    private List<EvidenceFragment> evidenceFragments;

    /** 采集阶段聚合出的章节证据束，供后续抽取/分析直接复用 */
    private List<SectionEvidenceBundle> sectionEvidenceBundles;

    /** 采集阶段面向下游的统一证据运行期视图 */
    private List<DownstreamEvidenceView> downstreamEvidenceViews;

    /** 任务级知识文档集合 */
    private List<KnowledgeDocument> knowledgeDocuments;

    /** 任务级检索切片集合 */
    private List<RetrievalChunk> retrievalChunks;

    /** 任务级索引元数据集合 */
    private List<RetrievalIndex> retrievalIndexes;
}
