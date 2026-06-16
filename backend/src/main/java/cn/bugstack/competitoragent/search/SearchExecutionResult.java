package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 运行期搜索编排结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchExecutionResult {

    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot progressSnapshot;
    private List<SearchProgressSnapshot> progressSnapshots;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> selectedTargets;
    private List<SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> replayTimeline;
    private String reasoningSummary;
    private SearchExecutionTrace executionTrace;
    private SearchAuditSnapshot auditSnapshot;
}
