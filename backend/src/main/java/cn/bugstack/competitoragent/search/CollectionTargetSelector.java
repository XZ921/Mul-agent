package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 最终采集目标选择器。
 * 负责把验证结果、补源候选和兜底候选统一收束成最终采集目标。
 */
@Component
public class CollectionTargetSelector {

    /**
     * 目标选择一次性完成三件事：
     * 1. 按验证状态和综合分数选出正式采集目标；
     * 2. 把已选候选统一回填为 SELECTED；
     * 3. 用更新后的 candidate 快照刷新 selectedTargets，避免详情页看到两套不一致的说明。
     */
    public SearchSelectionDecision selectTargets(List<SourceCandidate> candidates,
                                                 Map<String, SearchCollectionTarget> attemptedTargets,
                                                 int targetCount) {
        Map<String, SearchCollectionTarget> normalizedAttemptedTargets = normalizeAttemptedTargets(attemptedTargets);
        List<SearchCollectionTarget> selectedTargets = new ArrayList<>();
        Set<String> selectedUrls = new LinkedHashSet<>();

        List<SourceCandidate> rankedCandidates = candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
                .sorted(Comparator.comparingInt(this::resolveSelectionTier)
                        .thenComparing(SourceCandidate::getTotalScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        for (SourceCandidate candidate : rankedCandidates) {
            String normalizedUrl = normalizeUrl(candidate.getUrl());
            if (!StringUtils.hasText(normalizedUrl) || !selectedUrls.add(normalizedUrl)) {
                continue;
            }
            SearchCollectionTarget target = normalizedAttemptedTargets.getOrDefault(
                    normalizedUrl,
                    SearchCollectionTarget.builder().candidate(candidate).build()
            );
            selectedTargets.add(target);
            if (selectedTargets.size() >= targetCount) {
                break;
            }
        }

        List<SourceCandidate> updatedCandidates = candidates.stream()
                .map(candidate -> applySelectionResult(candidate, selectedUrls))
                .toList();
        List<SourceCandidate> discardedCandidates = resolveDiscardedCandidates(updatedCandidates, selectedUrls);
        Map<String, SourceCandidate> updatedCandidatesByUrl = indexCandidatesByNormalizedUrl(updatedCandidates);
        List<SearchCollectionTarget> refreshedTargets = selectedTargets.stream()
                .map(target -> refreshTargetCandidate(target, updatedCandidatesByUrl))
                .toList();

        return SearchSelectionDecision.builder()
                .selectedTargets(refreshedTargets)
                .updatedCandidates(updatedCandidates)
                .discardedCandidates(discardedCandidates)
                .sourceUrls(refreshedTargets.stream()
                        .map(target -> target == null || target.getCandidate() == null ? null : target.getCandidate().getUrl())
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList())
                .build();
    }

    /**
     * 只把已被正式标记为 DISCARDED 的候选写入丢弃事实源。
     * 未被选中但也未明确丢弃的候选仍保留在 sourceCandidates，避免误判。
     */
    private List<SourceCandidate> resolveDiscardedCandidates(List<SourceCandidate> candidates,
                                                             Set<String> selectedUrls) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && "DISCARDED".equalsIgnoreCase(candidate.getSelectionStage()))
                .filter(candidate -> !selectedUrls.contains(normalizeUrl(candidate.getUrl())))
                .toList();
    }

    /**
     * 已验证通过的候选永远优先于未验证候选，明确丢弃的候选即使分高也只能排在最后。
     */
    private int resolveSelectionTier(SourceCandidate candidate) {
        if (candidate == null) {
            return Integer.MAX_VALUE;
        }
        if (Boolean.TRUE.equals(candidate.getVerified())) {
            return 0;
        }
        if ("DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return 2;
        }
        return 1;
    }

    /**
     * 运行期验证阶段保存的 attemptedTargets 可能带 query 参数或旧快照，
     * 这里先按归一化 URL 建索引，后续才能稳定复用 collectedPage。
     */
    private Map<String, SearchCollectionTarget> normalizeAttemptedTargets(Map<String, SearchCollectionTarget> attemptedTargets) {
        Map<String, SearchCollectionTarget> normalizedTargets = new LinkedHashMap<>();
        if (attemptedTargets == null || attemptedTargets.isEmpty()) {
            return normalizedTargets;
        }
        for (SearchCollectionTarget target : attemptedTargets.values()) {
            if (target == null) {
                continue;
            }
            String normalizedUrl = normalizeUrl(target.getCandidate() == null ? null : target.getCandidate().getUrl());
            if (!StringUtils.hasText(normalizedUrl) && target.getCollectedPage() != null) {
                normalizedUrl = normalizeUrl(target.getCollectedPage().getUrl());
            }
            if (StringUtils.hasText(normalizedUrl)) {
                normalizedTargets.put(normalizedUrl, target);
            }
        }
        return normalizedTargets;
    }

    /**
     * 被正式选中的候选统一回填 SELECTED，并补齐前端/审计直接可读的解释文案。
     */
    private SourceCandidate applySelectionResult(SourceCandidate candidate, Set<String> selectedUrls) {
        if (candidate == null) {
            return null;
        }
        String normalizedUrl = normalizeUrl(candidate.getUrl());
        if (!selectedUrls.contains(normalizedUrl)) {
            return candidate;
        }
        String selectedReason = Boolean.TRUE.equals(candidate.getVerified())
                ? "运行期验证通过后被选为正式采集目标"
                : "在验证不足时作为兜底候选被选为正式采集目标";
        String selectedSummary = Boolean.TRUE.equals(candidate.getVerified())
                ? "运行期验证通过后被选为正式采集目标"
                : "在验证不足时作为兜底候选被选为正式采集目标";
        return candidate.toBuilder()
                .selectionStage("SELECTED")
                .selectionReason(selectedReason)
                .selectionSummary(selectedSummary)
                .build();
    }

    private Map<String, SourceCandidate> indexCandidatesByNormalizedUrl(List<SourceCandidate> candidates) {
        Map<String, SourceCandidate> candidatesByUrl = new LinkedHashMap<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String normalizedUrl = normalizeUrl(candidate.getUrl());
            if (StringUtils.hasText(normalizedUrl)) {
                candidatesByUrl.put(normalizedUrl, candidate);
            }
        }
        return candidatesByUrl;
    }

    /**
     * selectedTargets 里保留的是选择当时的 candidate 快照，
     * 所以这里要把回填后的 candidate 重新覆盖进去，确保详情页和审计快照看到的是同一份解释。
     */
    private SearchCollectionTarget refreshTargetCandidate(SearchCollectionTarget target,
                                                          Map<String, SourceCandidate> candidatesByUrl) {
        if (target == null || target.getCandidate() == null) {
            return target;
        }
        String normalizedUrl = normalizeUrl(target.getCandidate().getUrl());
        SourceCandidate refreshedCandidate = candidatesByUrl.getOrDefault(normalizedUrl, target.getCandidate());
        return target.toBuilder().candidate(refreshedCandidate).build();
    }

    /**
     * 目标选择与快照复用只关心“同一页面”，因此会移除 query / fragment 并统一 host 大小写，
     * 避免同一文档因为追踪参数不同被误判成两个独立来源。
     */
    private String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!StringUtils.hasText(uri.getHost())) {
                return url.trim();
            }
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                path = "";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + path;
        } catch (Exception ignored) {
            return url.trim();
        }
    }
}
