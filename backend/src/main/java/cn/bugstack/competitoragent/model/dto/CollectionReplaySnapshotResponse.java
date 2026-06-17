package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Collector 采集现场回放视图。
 * 这里把 collectionAudit、摘要与 package 级时间线投影成稳定 DTO，
 * 供任务回放与详情页统一消费，避免下游再次从原始 outputData 猜字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollectionReplaySnapshotResponse {

    private String nodeName;
    private Long planVersionId;
    private Integer planVersion;
    private String branchKey;
    private String collectionStatus;
    private CollectionAuditSnapshot collectionAudit;
    private CollectionAuditSummary collectionAuditSummary;
    private List<CollectionReplayTimelineItem> timeline;
    private List<String> sourceUrls;
}
