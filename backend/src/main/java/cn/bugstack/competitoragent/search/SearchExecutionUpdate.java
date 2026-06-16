package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索执行过程中的结构化进度更新。
 * 供采集节点在运行中持续写回前端可见的计划、进度与候选来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchExecutionUpdate {

    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressSnapshots;
    private List<SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> selectedTargets;
    private List<SourceCandidate> discardedCandidates;
    private List<SearchReplayTimelineItem> replayTimeline;
    private SearchExecutionTrace executionTrace;
}
