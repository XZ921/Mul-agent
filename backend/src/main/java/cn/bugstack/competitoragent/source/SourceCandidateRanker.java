package cn.bugstack.competitoragent.source;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 候选来源去重与排序器。
 * 用统一策略把启发式和搜索式补源结果收口，避免重复采集并优先保留更可信、更相关的新鲜来源。
 */
@Component
public class SourceCandidateRanker {

    private static final double UTILITY_PAGE_SCORE_CAP = 0.05D;
    private static final List<String> HIGH_PRIORITY_DISCOVERY_METHODS = List.of("SEARCH", "BROWSER", "BROWSER_PREVIEW");
    private static final List<String> UTILITY_PAGE_SIGNALS = List.of(
            "/login", "signin", "sign-in", "log in",
            "/careers", "/jobs", "career", "job opening"
    );

    /**
     * 先按规范化 URL 去重，再根据综合分数降序排序。
     */
    public List<SourceCandidate> rankAndDeduplicate(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, SourceCandidate> deduplicated = new LinkedHashMap<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
                continue;
            }

            SourceCandidate scored = ensureScores(candidate);
            String key = normalizeUrl(scored.getUrl());
            SourceCandidate existing = deduplicated.get(key);
            if (existing == null) {
                deduplicated.put(key, scored);
                continue;
            }

            SourceCandidate preferred = preferCandidate(existing, scored);
            deduplicated.put(key, preferred);
        }

        List<SourceCandidate> ranked = new ArrayList<>(deduplicated.values());
        ranked.sort(Comparator.comparingDouble(SourceCandidate::getTotalScore).reversed()
                .thenComparing(SourceCandidate::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SourceCandidate::getUrl));
        return ranked;
    }

    /**
     * 如果上游没有显式给出 freshness/domain 等信息，这里补齐兜底值，保证排序稳定。
     */
    public SourceCandidate ensureScores(SourceCandidate candidate) {
        String normalizedDomain = StringUtils.hasText(candidate.getDomain()) ? candidate.getDomain() : extractDomain(candidate.getUrl());
        double relevance = clamp(candidate.getRelevanceScore() > 0 ? candidate.getRelevanceScore() : inferRelevance(candidate));
        double freshness = clamp(candidate.getFreshnessScore() > 0 ? candidate.getFreshnessScore() : inferFreshness(candidate.getPublishedAt()));
        double quality = clamp(candidate.getQualityScore() > 0 ? candidate.getQualityScore() : inferQuality(normalizedDomain, candidate.getDiscoveryMethod()));
        double total = round(relevance * 0.5 + freshness * 0.2 + quality * 0.3);
        SourceSelectionReason decisionReason = resolveDecisionReason(candidate);

        // 登录、招聘等工具页虽然可能有较高文本相关度，但对竞品研究价值低，
        // 这里直接降权并打上淘汰原因，避免它们在排序时挤占真正高价值的文档和定价页。
        if (decisionReason == SourceSelectionReason.LOW_SIGNAL_UTILITY_PAGE) {
            total = Math.min(total, UTILITY_PAGE_SCORE_CAP);
        }

        return SourceCandidate.builder()
                .url(candidate.getUrl())
                .title(candidate.getTitle())
                .sourceType(candidate.getSourceType())
                .discoveryMethod(candidate.getDiscoveryMethod())
                .reason(candidate.getReason())
                .domain(normalizedDomain)
                .publishedAt(candidate.getPublishedAt())
                .relevanceScore(round(relevance))
                .freshnessScore(round(freshness))
                .qualityScore(round(quality))
                .totalScore(total)
                .searchQuery(candidate.getSearchQuery())
                .searchEngine(candidate.getSearchEngine())
                .resultRank(candidate.getResultRank())
                .browserTraceId(candidate.getBrowserTraceId())
                .verified(candidate.getVerified())
                .verificationReason(candidate.getVerificationReason())
                .matchedSignals(candidate.getMatchedSignals())
                .selectionStage(resolveSelectionStage(candidate, decisionReason))
                .selectionReason(resolveSelectionReason(candidate, decisionReason))
                .build();
    }

    /**
     * 规范化 URL 冲突时，不只看综合分，还要看发布时间和发现方式，
     * 避免旧的启发式结果因为先进入 Map 而压过更新的搜索命中。
     */
    private SourceCandidate preferCandidate(SourceCandidate existing, SourceCandidate incoming) {
        int scoreCompare = Double.compare(incoming.getTotalScore(), existing.getTotalScore());
        if (scoreCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        if (scoreCompare < 0) {
            return existing;
        }

        int publishedAtCompare = comparePublishedAt(incoming.getPublishedAt(), existing.getPublishedAt());
        if (publishedAtCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        if (publishedAtCompare < 0) {
            return existing;
        }

        int discoveryCompare = Integer.compare(discoveryPriority(incoming.getDiscoveryMethod()),
                discoveryPriority(existing.getDiscoveryMethod()));
        if (discoveryCompare > 0) {
            return annotateDuplicateWinner(incoming);
        }
        return existing;
    }

    private SourceCandidate annotateDuplicateWinner(SourceCandidate candidate) {
        if (isUtilityPage(candidate) || StringUtils.hasText(candidate.getSelectionReason())) {
            return candidate;
        }
        return candidate.toBuilder()
                .selectionStage(SourceSelectionReason.KEEP_FRESHER_SEARCH_RESULT.getSelectionStage())
                .selectionReason(SourceSelectionReason.KEEP_FRESHER_SEARCH_RESULT.name())
                .build();
    }

    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String host = StringUtils.hasText(uri.getHost()) ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath().replaceAll("/+$", "");
            return scheme + "://" + host + path;
        } catch (Exception e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private double inferRelevance(SourceCandidate candidate) {
        String sourceType = candidate.getSourceType() == null ? "" : candidate.getSourceType();
        return switch (sourceType) {
            case "OFFICIAL" -> 0.95;
            case "DOCS" -> 0.90;
            case "PRICING" -> 0.92;
            case "NEWS" -> 0.78;
            case "REVIEW" -> 0.80;
            default -> 0.70;
        };
    }

    private double inferFreshness(String publishedAt) {
        if (!StringUtils.hasText(publishedAt)) {
            return 0.55;
        }
        try {
            LocalDate date = LocalDate.parse(publishedAt);
            long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now()));
            if (days <= 7) {
                return 0.95;
            }
            if (days <= 30) {
                return 0.80;
            }
            if (days <= 90) {
                return 0.65;
            }
            return 0.45;
        } catch (DateTimeParseException e) {
            return 0.55;
        }
    }

    private double inferQuality(String domain, String discoveryMethod) {
        if (!StringUtils.hasText(domain)) {
            return "SEARCH".equalsIgnoreCase(discoveryMethod) ? 0.72 : 0.68;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        if (normalized.contains("g2.com") || normalized.contains("capterra.com")) {
            return 0.86;
        }
        if (normalized.startsWith("docs.") || normalized.contains("help") || normalized.contains("support")) {
            return 0.90;
        }
        if (normalized.contains(".com") || normalized.contains(".ai") || normalized.contains(".io")) {
            return 0.88;
        }
        return 0.70;
    }

    private int comparePublishedAt(String left, String right) {
        LocalDate leftDate = parseDate(left);
        LocalDate rightDate = parseDate(right);
        if (leftDate == null && rightDate == null) {
            return 0;
        }
        if (leftDate == null) {
            return -1;
        }
        if (rightDate == null) {
            return 1;
        }
        return leftDate.compareTo(rightDate);
    }

    private LocalDate parseDate(String publishedAt) {
        if (!StringUtils.hasText(publishedAt)) {
            return null;
        }
        try {
            return LocalDate.parse(publishedAt);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private int discoveryPriority(String discoveryMethod) {
        if (!StringUtils.hasText(discoveryMethod)) {
            return 0;
        }
        String normalizedMethod = discoveryMethod.toUpperCase(Locale.ROOT);
        if (HIGH_PRIORITY_DISCOVERY_METHODS.contains(normalizedMethod)) {
            return 3;
        }
        if ("HEURISTIC".equals(normalizedMethod)) {
            return 1;
        }
        return 2;
    }

    private boolean isUtilityPage(SourceCandidate candidate) {
        Set<String> signals = new LinkedHashSet<>();
        appendSignal(signals, candidate.getUrl());
        appendSignal(signals, candidate.getTitle());
        appendSignal(signals, candidate.getReason());

        return signals.stream().anyMatch(this::containsUtilitySignal);
    }

    private void appendSignal(Set<String> signals, String value) {
        if (StringUtils.hasText(value)) {
            signals.add(value.toLowerCase(Locale.ROOT));
        }
    }

    private boolean containsUtilitySignal(String signal) {
        return UTILITY_PAGE_SIGNALS.stream().anyMatch(signal::contains);
    }

    private SourceSelectionReason resolveDecisionReason(SourceCandidate candidate) {
        if (isUtilityPage(candidate)) {
            return SourceSelectionReason.LOW_SIGNAL_UTILITY_PAGE;
        }
        return null;
    }

    private String resolveSelectionStage(SourceCandidate candidate, SourceSelectionReason decisionReason) {
        if (decisionReason != null) {
            return decisionReason.getSelectionStage();
        }
        return candidate.getSelectionStage();
    }

    private String resolveSelectionReason(SourceCandidate candidate, SourceSelectionReason decisionReason) {
        if (decisionReason != null) {
            return decisionReason.name();
        }
        return candidate.getSelectionReason();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
