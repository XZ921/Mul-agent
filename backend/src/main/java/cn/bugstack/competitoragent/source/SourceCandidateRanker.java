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
            SourceCandidate merged = preferred == scored
                    ? mergeDuplicateMetadata(annotateDuplicateWinner(preferred), existing)
                    : mergeDuplicateMetadata(preferred, scored);
            deduplicated.put(key, merged);
        }

        List<SourceCandidate> ranked = new ArrayList<>(deduplicated.values());
        ranked.sort(Comparator.comparingDouble(SourceCandidate::getTotalScore).reversed()
                .thenComparing(SourceCandidate::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SourceCandidate::getUrl));
        return ranked;
    }

    /**
     * 预算裁剪必须发生在“先排序去重”之后，
     * 这样才能保证留下来的仍然是同一套排序语义里的最优候选，而不是被输入顺序偶然左右。
     */
    public List<SourceCandidate> rankDeduplicateAndLimit(List<SourceCandidate> candidates,
                                                         int maxCount,
                                                         int perDomainCap) {
        List<SourceCandidate> ranked = rankAndDeduplicate(candidates);
        if (ranked.isEmpty()) {
            return ranked;
        }
        Map<String, SourceCandidate> reservedPrefetchByDomain = reserveUsablePrefetchCandidates(ranked);
        Map<String, Integer> perDomainCounter = new LinkedHashMap<>();
        Map<String, Integer> perDomainNonReservedCounter = new LinkedHashMap<>();
        List<SourceCandidate> limited = new ArrayList<>();
        for (SourceCandidate candidate : ranked) {
            String domain = StringUtils.hasText(candidate.getDomain())
                    ? candidate.getDomain()
                    : extractDomain(candidate.getUrl());
            SourceCandidate reservedPrefetch = reservedPrefetchByDomain.get(domain);
            boolean reservedCandidate = sameCandidate(reservedPrefetch, candidate);
            int current = perDomainCounter.getOrDefault(domain, 0);
            int currentNonReserved = perDomainNonReservedCounter.getOrDefault(domain, 0);
            int nonReservedCap = reservedPrefetch == null || perDomainCap <= 0
                    ? perDomainCap
                    : Math.max(0, perDomainCap - 1);
            /**
             * 当同域里存在“已拿到正文”的 Tavily prefetch 候选时，
             * 普通候选最多只能占用 perDomainCap-1 个名额，把最后一个名额留给 prefetch 真文。
             * 这样既保留域内均衡，也避免 rich content 在进入 selector 之前就被壳页挤出工作集。
             */
            if (!reservedCandidate && nonReservedCap >= 0 && perDomainCap > 0 && currentNonReserved >= nonReservedCap) {
                continue;
            }
            if (perDomainCap > 0 && current >= perDomainCap) {
                continue;
            }
            limited.add(candidate);
            perDomainCounter.put(domain, current + 1);
            if (!reservedCandidate) {
                perDomainNonReservedCounter.put(domain, currentNonReserved + 1);
            }
            if (maxCount > 0 && limited.size() >= maxCount) {
                break;
            }
        }
        return limited;
    }

    /**
     * 同域限额用于抑制壳页洪水，但不能把已经拿到正文的 Tavily fast-lane 候选提前裁掉。
     * 这里为每个域名最多保留一个“已 gate 判可用”的 prefetch 候选名额，避免它在进入 selector 前就消失。
     */
    private Map<String, SourceCandidate> reserveUsablePrefetchCandidates(List<SourceCandidate> ranked) {
        Map<String, SourceCandidate> reserved = new LinkedHashMap<>();
        for (SourceCandidate candidate : ranked) {
            if (!isUsablePrefetchCandidate(candidate)) {
                continue;
            }
            String domain = StringUtils.hasText(candidate.getDomain())
                    ? candidate.getDomain()
                    : extractDomain(candidate.getUrl());
            if (!StringUtils.hasText(domain) || reserved.containsKey(domain)) {
                continue;
            }
            reserved.put(domain, candidate);
        }
        return reserved;
    }

    private boolean isUsablePrefetchCandidate(SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return Boolean.TRUE.equals(candidate.getFastLaneUsable())
                && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
                && (StringUtils.hasText(candidate.getPrefetchedContentRef())
                || candidate.getPrefetchedRawContentLength() != null && candidate.getPrefetchedRawContentLength() > 0);
    }

    private boolean sameCandidate(SourceCandidate left, SourceCandidate right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeUrl(left.getUrl()).equals(normalizeUrl(right.getUrl()));
    }

    /**
     * 如果上游没有显式给出 freshness/domain 等信息，这里补齐兜底值，保证排序稳定。
     */
    public SourceCandidate ensureScores(SourceCandidate candidate) {
        String normalizedDomain = StringUtils.hasText(candidate.getDomain()) ? candidate.getDomain() : extractDomain(candidate.getUrl());
        double relevance = clamp(candidate.getRelevanceScore() > 0 ? candidate.getRelevanceScore() : inferRelevance(candidate));
        double freshness = clamp(candidate.getFreshnessScore() > 0 ? candidate.getFreshnessScore() : inferFreshness(candidate.getPublishedAt()));
        double quality = clamp(candidate.getQualityScore() > 0 ? candidate.getQualityScore() : inferQuality(normalizedDomain, candidate.getDiscoveryMethod()));
        List<String> qualitySignals = resolveQualitySignals(candidate, normalizedDomain);
        double total = applyQualitySignalBoost(round(relevance * 0.5 + freshness * 0.2 + quality * 0.3), qualitySignals);
        SourceSelectionReason decisionReason = resolveDecisionReason(candidate);
        SourceTrustTier trustTier = resolveTrustTier(candidate, normalizedDomain);
        List<String> rankingReasons = buildRankingReasons(candidate, normalizedDomain, trustTier, freshness);
        rankingReasons.addAll(buildQualitySignalRankingReasons(qualitySignals));

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
                .sourceFamilyKey(candidate.getSourceFamilyKey())
                .sourceFamilyRole(candidate.getSourceFamilyRole())
                .providerKey(candidate.getProviderKey())
                .providerRole(candidate.getProviderRole())
                .sourceUrls(resolveSourceUrls(candidate))
                .relevanceScore(round(relevance))
                .freshnessScore(round(freshness))
                .qualityScore(round(quality))
                .totalScore(total)
                .trustTier(trustTier)
                .trustTierLabel(trustTier.getDisplayName())
                .qualitySignals(qualitySignals)
                .rankingReasons(rankingReasons)
                .rankingSummary(buildRankingSummary(trustTier, rankingReasons))
                .fieldName(candidate.getFieldName())
                .evidencePathKey(candidate.getEvidencePathKey())
                .queryIntent(candidate.getQueryIntent())
                .fieldEvidenceQueryFingerprint(candidate.getFieldEvidenceQueryFingerprint())
                .fieldEvidenceQueryReason(candidate.getFieldEvidenceQueryReason())
                .searchQuery(candidate.getSearchQuery())
                .searchEngine(candidate.getSearchEngine())
                .resultRank(candidate.getResultRank())
                .browserTraceId(candidate.getBrowserTraceId())
                /**
                 * Tavily Fast Lane 相关轻量字段必须在运行期排序链路中完整透传。
                 * 否则 provider 刚刚注册好的 prefetchedContentRef、queryMode、fastLaneUsable 等元数据
                 * 会在 ensureScores 重新构建对象时被悄悄丢失，导致后续 coordinator / executor 无法识别快速通道候选。
                 */
                .hasPrefetchedContent(candidate.getHasPrefetchedContent())
                .prefetchedContentRef(candidate.getPrefetchedContentRef())
                .prefetchedRawContentLength(candidate.getPrefetchedRawContentLength())
                .tavilyScore(candidate.getTavilyScore())
                .tavilyRequestId(candidate.getTavilyRequestId())
                .tavilyQuery(candidate.getTavilyQuery())
                .tavilyQueryMode(candidate.getTavilyQueryMode())
                .pageType(candidate.getPageType())
                .qualityTier(candidate.getQualityTier())
                .fastLaneUsable(candidate.getFastLaneUsable())
                .fastLaneRejectReason(candidate.getFastLaneRejectReason())
                .contentCompleteness(candidate.getContentCompleteness())
                .skipNetworkVerification(candidate.getSkipNetworkVerification())
                .verified(candidate.getVerified())
                .verificationReason(candidate.getVerificationReason())
                .matchedSignals(candidate.getMatchedSignals())
                .selectionStage(resolveSelectionStage(candidate, decisionReason))
                .selectionReason(resolveSelectionReason(candidate, decisionReason))
                .selectionSummary(resolveSelectionSummary(candidate, decisionReason))
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
                .selectionSummary(SourceSelectionReason.KEEP_FRESHER_SEARCH_RESULT.getSummary())
                .build();
    }

    /**
     * planned 与 bootstrap/supplement 命中同一 canonical URL 时，
     * 排序胜者负责保留，但 Tavily fast-lane 元数据与 sourceUrls 不能在去重时被吞掉。
     */
    private SourceCandidate mergeDuplicateMetadata(SourceCandidate winner, SourceCandidate loser) {
        LinkedHashSet<String> mergedSourceUrls = new LinkedHashSet<>(resolveSourceUrls(winner));
        mergedSourceUrls.addAll(resolveSourceUrls(loser));
        return winner.toBuilder()
                .sourceUrls(new ArrayList<>(mergedSourceUrls))
                /**
                 * 字段级 evidence metadata 不能只停留在 provider 出口。
                 * 一旦 canonical URL 去重时被更高分候选“吞掉”，后续 coordinator 就会误判
                 * “虽然找到了页面，但没有字段证据命中”，从而错误触发 public recovery。
                 */
                .fieldName(firstText(winner.getFieldName(), loser.getFieldName()))
                .evidencePathKey(firstText(winner.getEvidencePathKey(), loser.getEvidencePathKey()))
                .queryIntent(firstText(winner.getQueryIntent(), loser.getQueryIntent()))
                .fieldEvidenceQueryFingerprint(firstText(
                        winner.getFieldEvidenceQueryFingerprint(),
                        loser.getFieldEvidenceQueryFingerprint()))
                .fieldEvidenceQueryReason(firstText(
                        winner.getFieldEvidenceQueryReason(),
                        loser.getFieldEvidenceQueryReason()))
                .hasPrefetchedContent(orTrue(winner.getHasPrefetchedContent(), loser.getHasPrefetchedContent()))
                .prefetchedContentRef(firstText(winner.getPrefetchedContentRef(), loser.getPrefetchedContentRef()))
                .prefetchedRawContentLength(firstNonNull(winner.getPrefetchedRawContentLength(), loser.getPrefetchedRawContentLength()))
                .tavilyScore(firstNonNull(winner.getTavilyScore(), loser.getTavilyScore()))
                .tavilyRequestId(firstText(winner.getTavilyRequestId(), loser.getTavilyRequestId()))
                .tavilyQuery(firstText(winner.getTavilyQuery(), loser.getTavilyQuery()))
                .tavilyQueryMode(firstText(winner.getTavilyQueryMode(), loser.getTavilyQueryMode()))
                .fastLaneUsable(orTrue(winner.getFastLaneUsable(), loser.getFastLaneUsable()))
                .skipNetworkVerification(orTrue(winner.getSkipNetworkVerification(), loser.getSkipNetworkVerification()))
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

    /**
     * 来源可信度是对“这个候选本身是否像一个可靠来源”的判断，
     * 与相关度分数不同，它更偏向来源治理解释语义，因此单独输出给前端和诊断链路。
     */
    private SourceTrustTier resolveTrustTier(SourceCandidate candidate, String domain) {
        if (isUtilityPage(candidate)) {
            return SourceTrustTier.LOW;
        }
        String normalizedDomain = defaultText(domain).toLowerCase(Locale.ROOT);
        String sourceType = defaultText(candidate.getSourceType()).toUpperCase(Locale.ROOT);
        if (normalizedDomain.startsWith("docs.")
                || normalizedDomain.contains("help")
                || normalizedDomain.contains("support")
                || normalizedDomain.contains("g2.com")
                || normalizedDomain.contains("capterra.com")) {
            return SourceTrustTier.HIGH;
        }
        if ("OFFICIAL".equals(sourceType) || "PRICING".equals(sourceType) || "DOCS".equals(sourceType)) {
            return SourceTrustTier.HIGH;
        }
        if ("REVIEW".equals(sourceType) || "SEARCH".equalsIgnoreCase(candidate.getDiscoveryMethod())) {
            return SourceTrustTier.MEDIUM;
        }
        return SourceTrustTier.LOW;
    }

    /**
     * 质量信号用于识别“同一 sourceType 下更值得优先采集的路径”。
     * 例如 DOCS 家族里，docs 子域名、/api、/reference 比官网首页更适合进入首轮采集。
     */
    private List<String> resolveQualitySignals(SourceCandidate candidate, String normalizedDomain) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        if (candidate.getQualitySignals() != null) {
            for (String signal : candidate.getQualitySignals()) {
                if (StringUtils.hasText(signal)) {
                    signals.add(signal);
                }
            }
        }

        String sourceType = defaultText(candidate.getSourceType()).toUpperCase(Locale.ROOT);
        String url = defaultText(candidate.getUrl()).toLowerCase(Locale.ROOT);
        String domain = defaultText(normalizedDomain).toLowerCase(Locale.ROOT);
        if ("DOCS".equals(sourceType) && (domain.startsWith("docs.")
                || domain.startsWith("developer.")
                || domain.startsWith("open.")
                || domain.startsWith("help.")
                || url.contains("/doc")
                || url.contains("/docs")
                || url.contains("/documentation") || url.contains("/api") || url.contains("/reference"))) {
            signals.add("DOCS_HIGH_VALUE_PATH");
        }
        if ("DOCS".equals(sourceType) && (url.contains("/doc")
                || url.contains("/docs")
                || url.contains("/documentation")
                || url.contains("/api")
                || url.contains("/reference"))) {
            signals.add("DOCS_EXACT_PATH_HIT");
        }
        if ("PRICING".equals(sourceType) && (url.contains("/pricing") || url.contains("/plans")
                || url.contains("价格") || url.contains("定价"))) {
            signals.add("PRICING_HIGH_VALUE_PATH");
        }
        if ("NEWS".equals(sourceType) && (url.contains("/blog") || url.contains("/news")
                || url.contains("/changelog") || url.contains("/release"))) {
            signals.add("NEWS_HIGH_VALUE_PATH");
        }
        return new ArrayList<>(signals);
    }

    private double applyQualitySignalBoost(double total, List<String> qualitySignals) {
        if (qualitySignals == null || qualitySignals.isEmpty()) {
            return total;
        }
        return Math.min(1.0D, round(total + qualitySignals.size() * 0.08D));
    }

    private List<String> buildQualitySignalRankingReasons(List<String> qualitySignals) {
        if (qualitySignals == null || qualitySignals.isEmpty()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (String signal : qualitySignals) {
            switch (signal) {
                case "DOCS_HIGH_VALUE_PATH" -> reasons.add("命中文档高价值路径，优先级高于泛官网首页");
                case "DOCS_EXACT_PATH_HIT" -> reasons.add("命中明确文档路径，优先级高于开放平台根页");
                case "PRICING_HIGH_VALUE_PATH" -> reasons.add("命中定价高价值路径，适合优先采集价格与套餐信息");
                case "NEWS_HIGH_VALUE_PATH" -> reasons.add("命中新闻高价值路径，适合优先采集发布、更新与动态信息");
                default -> reasons.add("命中质量信号：" + signal);
            }
        }
        return reasons;
    }

    private List<String> resolveSourceUrls(SourceCandidate candidate) {
        if (candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()) {
            return candidate.getSourceUrls();
        }
        if (StringUtils.hasText(candidate.getUrl())) {
            return List.of(candidate.getUrl());
        }
        return List.of();
    }

    /**
     * 排序原因要能直接解释“为什么这个来源会排在当前位置”，
     * 因此这里输出的是面向人类可读的原因列表，而不是只有最终总分。
     */
    private List<String> buildRankingReasons(SourceCandidate candidate,
                                             String domain,
                                             SourceTrustTier trustTier,
                                             double freshness) {
        List<String> reasons = new ArrayList<>();
        reasons.add("来源可信度：" + trustTier.getDisplayName());

        String normalizedDomain = defaultText(domain).toLowerCase(Locale.ROOT);
        if (normalizedDomain.startsWith("docs.") || normalizedDomain.contains("help") || normalizedDomain.contains("support")) {
            reasons.add("命中文档域名，适合作为高价值文档来源");
        } else if (normalizedDomain.contains("g2.com") || normalizedDomain.contains("capterra.com")) {
            reasons.add("命中知名第三方测评平台，适合作为公开评价来源");
        } else if ("REVIEW".equalsIgnoreCase(candidate.getSourceType())) {
            reasons.add("第三方评测来源可补充官网之外的外部视角");
        } else if ("OFFICIAL".equalsIgnoreCase(candidate.getSourceType()) || "PRICING".equalsIgnoreCase(candidate.getSourceType())) {
            reasons.add("官网或定价入口通常是高权威的一手来源");
        }

        if (HIGH_PRIORITY_DISCOVERY_METHODS.contains(defaultText(candidate.getDiscoveryMethod()).toUpperCase(Locale.ROOT))) {
            reasons.add("搜索补源命中，优先级高于纯启发式候选");
        } else if ("HEURISTIC".equalsIgnoreCase(candidate.getDiscoveryMethod())) {
            reasons.add("启发式候选作为站内结构补充，优先级低于搜索直命中");
        }

        if (freshness >= 0.80D) {
            reasons.add("发布时间较新，适合优先用于当前阶段分析");
        } else if (freshness <= 0.50D) {
            reasons.add("发布时间较旧，排序时已适度降权");
        }
        return reasons;
    }

    private String buildRankingSummary(SourceTrustTier trustTier, List<String> rankingReasons) {
        if (rankingReasons == null || rankingReasons.isEmpty()) {
            return "来源可信度：" + trustTier.getDisplayName();
        }
        return String.join("；", rankingReasons);
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

    private String resolveSelectionSummary(SourceCandidate candidate, SourceSelectionReason decisionReason) {
        if (decisionReason != null) {
            return decisionReason.getSummary();
        }
        return candidate.getSelectionSummary();
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private Boolean orTrue(Boolean left, Boolean right) {
        return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
