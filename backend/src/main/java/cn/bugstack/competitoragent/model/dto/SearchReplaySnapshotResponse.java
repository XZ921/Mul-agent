package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
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
 * Collector 搜索现场回放视图。
 * <p>
 * 这里专门把 searchAudit、selectedTargets 和 sourceUrls 投影成稳定 DTO，
 * 供任务回放接口和详情页统一消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchReplaySnapshotResponse {

    private String nodeName;
    private Long planVersionId;
    private Integer planVersion;
    private String branchKey;
    private SearchProgressSnapshot latestProgress;
    private List<SearchReplayTimelineItem> timeline;
    private SearchAuditSnapshot searchAudit;
    private SearchAuditSummary searchAuditSummary;
    /**
     * 回放响应的扁平化尝试目标列表。
     * 保留 searchAudit 的完整现场，同时给调用方一个稳定的顶层消费入口。
     */
    private List<SearchCollectionTarget> attemptedTargets;
    /**
     * 回放响应的扁平化丢弃候选列表。
     * 用于解释选源裁剪结果，避免调用方必须理解 searchAudit 内部结构。
     */
    private List<SourceCandidate> discardedCandidates;
    private List<CollectorSelectedTargetSummary> selectedTargets;
    private List<String> sourceUrls;
}
