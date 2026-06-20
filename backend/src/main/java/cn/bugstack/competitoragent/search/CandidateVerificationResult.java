package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 候选验证批次结果。
 * 同时返回更新后的候选元数据和验证阶段已经采到的页面，供后续选源与正式采集复用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateVerificationResult {

    private List<SourceCandidate> updatedCandidates;
    private List<SearchCollectionTarget> attemptedTargets;
    private List<SearchCollectionTarget> verifiedTargets;
    private Integer inputCandidateCount;
    private Integer uniqueCandidateCount;
    private Integer attemptedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer reusedCollectedPageCount;
    private Integer directVerificationAttemptCount;
    private Integer directVerificationUsableCount;
    private Integer directVerificationShortcutCount;
    private Integer verificationConcurrency;
    private Long verificationElapsedMillis;
}
