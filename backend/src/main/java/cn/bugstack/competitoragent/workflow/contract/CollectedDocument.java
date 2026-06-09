package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单篇采集文档详情。
 */
@Data
@Builder
public class CollectedDocument {

    private String competitor;

    private String url;

    private String evidenceId;

    private boolean success;

    /** 清洗后的页面标题 */
    private String title;

    /** 清洗后的正文文本，不是 HTML */
    private String cleanedText;

    /** 前 500 字符摘要，用于检索和快速预览 */
    private String snippet;

    /** 内容长度 */
    private int contentLength;

    /** 当前采集文档的来源分类 */
    private String sourceCategory;

    /** 失败原因，仅 success=false 时有值 */
    private String errorMessage;

    /** 当前文档可回指的来源链接集合 */
    private List<String> sourceUrls;

    /** 当前文档携带的问题标记，例如采集失败、正文为空、来源回填等 */
    private List<String> issueFlags;

    /** 统一证据片段，供抽取/分析/写作直接沿用 */
    private List<EvidenceFragment> evidenceFragments;

    /** 文档维度聚合后的章节证据束，显式说明该文档对哪个章节提供了什么支撑 */
    private List<SectionEvidenceBundle> sectionEvidenceBundles;

    /** 当前采集文档沉淀出的任务级知识文档 */
    private KnowledgeDocument knowledgeDocument;

    /** 当前采集文档拆分出的检索切片 */
    private List<RetrievalChunk> retrievalChunks;

    /** 当前采集文档对应的任务级索引元数据 */
    private RetrievalIndex retrievalIndex;

    /** 采集时间 */
    private LocalDateTime collectedAt;
}
