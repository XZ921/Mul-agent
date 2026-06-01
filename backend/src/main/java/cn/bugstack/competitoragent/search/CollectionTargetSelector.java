package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 最终采集目标选择器。
 * 负责把验证结果、补源候选和兜底候选统一收束成最终采集目标。
 */
@Component
public class CollectionTargetSelector {

    /**
     * 最终选源优先顺序：
     * 1. 优先使用已验证成功的目标；
     * 2. 不够时按综合分数补齐规划期/补源候选；
     * 3. 即便来源未验证通过，也保留为兜底目标，确保节点不会因为单次验证波动而失去采集机会。
     */
    public List<SearchCollectionTarget> selectTargets(List<SourceCandidate> candidates,
                                                      Map<String, SearchCollectionTarget> attemptedTargets,
                                                      int targetCount) {
        List<SearchCollectionTarget> selected = new ArrayList<>();
        Set<String> selectedUrls = new LinkedHashSet<>();

        for (SearchCollectionTarget attemptedTarget : attemptedTargets.values()) {
            if (attemptedTarget.getCandidate() != null
                    && Boolean.TRUE.equals(attemptedTarget.getCandidate().getVerified())
                    && selectedUrls.add(attemptedTarget.getCandidate().getUrl())) {
                selected.add(attemptedTarget);
                if (selected.size() >= targetCount) {
                    return selected;
                }
            }
        }

        List<SourceCandidate> rankedCandidates = candidates.stream()
                .sorted(Comparator.comparing(
                                (SourceCandidate candidate) -> "DISCARDED".equalsIgnoreCase(candidate.getSelectionStage()))
                        .thenComparing(SourceCandidate::getTotalScore, Comparator.reverseOrder()))
                .toList();

        for (SourceCandidate candidate : rankedCandidates) {
            if (!selectedUrls.add(candidate.getUrl())) {
                continue;
            }
            SearchCollectionTarget target = attemptedTargets.getOrDefault(candidate.getUrl(),
                    SearchCollectionTarget.builder().candidate(candidate).build());
            selected.add(target);
            if (selected.size() >= targetCount) {
                break;
            }
        }
        return selected;
    }

    /**
     * 被最终采纳的候选统一标记为 SELECTED，便于前端与审计日志直接解释选源结果。
     */
    public List<SourceCandidate> markSelectedCandidates(List<SourceCandidate> candidates,
                                                        List<SearchCollectionTarget> selectedTargets) {
        Set<String> selectedUrls = selectedTargets.stream()
                .map(target -> target.getCandidate() == null ? null : target.getCandidate().getUrl())
                .filter(StringUtils::hasText)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return candidates.stream()
                .map(candidate -> selectedUrls.contains(candidate.getUrl())
                        ? candidate.toBuilder()
                        .selectionStage("SELECTED")
                        .selectionReason(Boolean.TRUE.equals(candidate.getVerified())
                                ? "运行期验证通过后被选为正式采集目标"
                                : "作为兜底候选被选为正式采集目标")
                        .build()
                        : candidate)
                .toList();
    }
}
