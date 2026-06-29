package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
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

    /**
     * 轻量审计摘要，供 insight / report / replay 主路径优先消费。
     * 完整候选、计划和 trace 继续保留为兼容字段。
     */
    private SearchAuditSummary summary;
    private SearchExecutionTrace executionTrace;
    private SearchExecutionPlan executionPlan;
    private SearchProgressSnapshot latestProgress;
    private List<SearchProgressSnapshot> progressHistory;
    /**
     * Tavily 快速通道的聚合审计摘要。
     * 顶层单独保留一份，方便 replay / checkpoint / report 不必深入 trace 再解析。
     */
    private TavilyFastLaneAudit tavilyFastLaneAudit;
    /**
     * 面向回放接口的稳定时间线投影。
     * 该字段不替代 progressHistory，只把关键步骤、候选数量和来源回指整理成更易消费的结构。
     */
    private List<SearchReplayTimelineItem> replayTimeline;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> sourceCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> selectedTargets;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<String> sourceUrls;
}
