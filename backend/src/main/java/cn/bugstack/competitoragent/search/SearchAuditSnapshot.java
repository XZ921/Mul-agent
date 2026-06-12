package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索审计快照。
 * <p>
 * 固化当前节点最终可回放的候选、选源与轨迹摘要，方便前端和后续恢复逻辑复用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSnapshot {

    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> selectedTargets;
    private List<String> sourceUrls;
}
