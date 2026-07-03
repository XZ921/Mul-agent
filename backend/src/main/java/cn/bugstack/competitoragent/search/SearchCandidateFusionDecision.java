package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 候选融合与验证规划结果。
 * 把“最终要留多少条”“哪些直接进预选”“哪些还需要网页验证”显式拆开，
 * 避免 coordinator 再从 allCandidates 临时推断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchCandidateFusionDecision {

    private int baseTargetCount;
    private int effectiveTargetCount;
    private int directSeedCandidateCount;
    private int tavilyCandidateCount;
    private int fastLaneCandidateCount;
    private int thirdPartyCandidateCount;
    private int verificationCandidateCount;
    private List<SourceCandidate> rankedCandidates;
    private List<SourceCandidate> preselectedCandidates;
    private List<SourceCandidate> verificationCandidates;
    private List<SourceCandidate> fastLaneCandidates;
    private String reason;
}
