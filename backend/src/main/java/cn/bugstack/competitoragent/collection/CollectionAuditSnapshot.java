package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * collection 正式审计快照。
 * 统一承接采集阶段的聚合结果、回放时间线、恢复锚点与可追溯来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionAuditSnapshot {

    private CollectionAuditSummary summary;
    private String status;
    private List<CollectionExecutionResult> results;
    private List<CollectionReplayTimelineItem> replayTimeline;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;
}
