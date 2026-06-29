package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 1 Tavily bootstrap 的最小决策对象。
 * coordinator 只消费是否执行、为什么执行，以及应该把什么请求交给 provider。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TavilyBootstrapDecision {

    private boolean shouldExecute;
    private String reason;
    private List<SourceCandidate> seedCandidates;
    private SearchSourceRequest request;
}
