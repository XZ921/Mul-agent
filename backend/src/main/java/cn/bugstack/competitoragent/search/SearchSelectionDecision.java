package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索目标选择决策。
 * 统一返回“最终选中的目标 + 更新后的候选列表 + 正式来源地址”，
 * 避免协调器继续维持 select -> mark -> refresh 的三段式脆弱写法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSelectionDecision {

    private List<SearchCollectionTarget> selectedTargets;
    private List<SourceCandidate> updatedCandidates;
    private List<SourceCandidate> discardedCandidates;
    private List<String> sourceUrls;
}
