package cn.bugstack.competitoragent.source;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 候选来源去重与排序器。
 * 用统一策略把启发式和搜索式补源结果收口，避免重复采集并优先保留更可信、更相关的新鲜来源。
 */
@Component
public class SourceCandidateRanker {

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
            if (existing == null || scored.getTotalScore() > existing.getTotalScore()) {
                deduplicated.put(key, scored);
            }
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
                .selectionStage(candidate.getSelectionStage())
                .selectionReason(candidate.getSelectionReason())
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

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
