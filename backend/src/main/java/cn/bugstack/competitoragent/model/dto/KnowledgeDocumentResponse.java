package cn.bugstack.competitoragent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织级知识文档返回 DTO。
 * <p>
 * 这个对象不暴露底层切片、索引等实现细节，只沉淀前端和 API 真正需要的三类可读信息：
 * 1. 文档本身属于哪个知识域、哪类来源；
 * 2. 文档回指到哪些原始 sourceUrls；
 * 3. 当前是否已经被后续任务消费过，以及消费链路的最小摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentResponse {

    private Long id;
    private Long taskId;
    private String evidenceId;
    private String documentKey;
    private String knowledgeScope;
    private Long knowledgeDomainId;
    private String knowledgeDomainKey;
    private String competitorName;
    private String sourceType;
    private String sourceCategory;
    private String discoveryMethod;
    private String sourceDomain;
    private String sourceLifecycle;
    private String trustLevel;
    private String connectorKey;
    private String title;
    private String url;
    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();
    @Builder.Default
    private List<String> issueFlags = new ArrayList<>();
    @Builder.Default
    private List<Long> consumedTaskIds = new ArrayList<>();
    @Builder.Default
    private List<String> consumedEvidenceIds = new ArrayList<>();
    private String traceSummary;
}
