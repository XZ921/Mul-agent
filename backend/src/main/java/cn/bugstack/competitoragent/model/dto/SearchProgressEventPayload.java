package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.search.SearchReplayTimelineItem;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索进度 SSE 正式事件契约。
 * <p>
 * 统一承载 Collector 运行期的搜索计划、审计和选中目标信息，
 * 避免前端继续从半结构化 outputData 里猜字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchProgressEventPayload {

    private String contractType;
    private String nodeName;
    private SearchProgressSnapshot searchProgress;
    private SearchExecutionTrace searchExecutionTrace;
    private List<SearchProgressSnapshot> searchProgressSnapshots;
    private SearchAuditSnapshot searchAudit;
    /**
     * 顶层透出本轮搜索实际尝试过的采集目标。
     * searchAudit 仍是正式事实源，但 SSE 消费方不需要再从嵌套对象里二次解析。
     */
    private List<SearchCollectionTarget> attemptedTargets;
    /**
     * 顶层透出被选择规则丢弃的候选来源及原因。
     * 用于让实时进度事件直接解释“为什么这些候选没有进入正式采集”。
     */
    private List<SourceCandidate> discardedCandidates;
    /**
     * 顶层透出搜索回放时间线。
     * 字段名保持为 replayTimeline，对齐 SEARCH_PROGRESS_V1 事件契约。
     */
    private List<SearchReplayTimelineItem> replayTimeline;
    private List<CollectorSelectedTargetSummary> selectedTargets;
    private String collectionStatus;
    private CollectionAuditSnapshot collectionAudit;
    private CollectionAuditSummary collectionAuditSummary;
    private List<CollectionReplayTimelineItem> collectionReplayTimeline;
    private List<String> sourceUrls;
}
