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
    /**
     * 面向回放接口的稳定时间线投影。
     * 该字段不替代 progressHistory，只把关键步骤、候选数量和来源回指整理成更易消费的结构。
     */
    private List<SearchReplayTimelineItem> replayTimeline;
    private List<cn.bugstack.competitoragent.source.SourceCandidate> sourceCandidates;
    /**
     * 验证阶段实际尝试过的采集目标。
     * 用于恢复和回放说明“系统曾经尝试过哪些页面”，避免只看到最终选中结果。
     */
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> selectedTargets;
    /**
     * 已被正式规则丢弃的候选。
     * 未选中不等于已丢弃，只有带 DISCARDED 语义的候选才进入此字段。
     */
    private List<cn.bugstack.competitoragent.source.SourceCandidate> discardedCandidates;
    private List<String> sourceUrls;
}
