package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tavily 预取正文质量门禁。
 * 该门禁统一根据页面类型、正文长度、查询模式和来源可追溯性，
 * 回写 fast lane 所需的轻量元数据，避免下游链路再各自重复判断。
 */
public class TavilyPrefetchedContentGate {

    private static final Set<String> FAST_LANE_PAGE_TYPES = Set.of("ARTICLE", "OFFICIAL_DOC", "PDF", "VIDEO_PAGE");
    private static final Set<String> SKIP_VERIFY_PAGE_TYPES = Set.of("ARTICLE", "OFFICIAL_DOC", "PDF");
    private static final Set<String> REJECT_PAGE_TYPES = Set.of("SEARCH_PAGE", "VIDEO_LIST");

    private final TavilySearchProperties properties;
    private final TavilyPageTypeClassifier classifier;

    public TavilyPrefetchedContentGate(TavilySearchProperties properties, TavilyPageTypeClassifier classifier) {
        this.properties = properties == null ? new TavilySearchProperties() : properties;
        this.classifier = classifier == null ? new TavilyPageTypeClassifier() : classifier;
    }

    /**
     * 对 Tavily 搜索结果执行 Gate 评估，并把结论写回轻量候选对象。
     * 这里不保存完整 raw_content，只保留 pageType / qualityTier / completeness 等结论字段，
     * 从而既满足 SourceCandidate 瘦身约束，也让后续 executor/verification 能直接消费。
     */
    public SourceCandidate apply(SourceCandidate candidate,
                                 TavilyPrefetchedContent content,
                                 Set<String> officialDomains) {
        if (candidate == null) {
            return null;
        }

        String url = firstNonBlank(candidate.getUrl(), content == null ? null : content.getUrl());
        String title = firstNonBlank(candidate.getTitle(), content == null ? null : content.getTitle());
        String rawContent = defaultText(content == null ? null : content.getRawContent());
        String cleanedContent = firstNonBlank(content == null ? null : content.getCleanedContent(), rawContent);
        int rawContentLength = rawContent.length();
        List<String> sourceUrls = mergeSourceUrls(candidate, content, url);
        Set<String> normalizedOfficialDomains = normalizeDomains(officialDomains);
        String host = extractHost(url);
        boolean officialDomainMatched = matchesOfficialDomain(host, normalizedOfficialDomains);

        String pageType = classifier.classify(url, title, cleanedContent, normalizedOfficialDomains);
        String qualityTier = resolveQualityTier(candidate, pageType, rawContentLength, officialDomainMatched);
        String contentCompleteness = resolveContentCompleteness(pageType, qualityTier, rawContentLength);
        boolean fastLaneUsable = resolveFastLaneUsable(pageType, qualityTier, rawContentLength, sourceUrls);
        boolean skipNetworkVerification = resolveSkipNetworkVerification(candidate, pageType, rawContentLength, fastLaneUsable);
        String rejectReason = resolveRejectReason(pageType, qualityTier, rawContentLength, sourceUrls, fastLaneUsable);

        return candidate.toBuilder()
                .sourceUrls(sourceUrls)
                .hasPrefetchedContent(rawContentLength > 0)
                .prefetchedRawContentLength(rawContentLength)
                .pageType(pageType)
                .qualityTier(qualityTier)
                .fastLaneUsable(fastLaneUsable)
                .fastLaneRejectReason(rejectReason)
                .contentCompleteness(contentCompleteness)
                .skipNetworkVerification(skipNetworkVerification)
                .build();
    }

    private String resolveQualityTier(SourceCandidate candidate,
                                      String pageType,
                                      int rawContentLength,
                                      boolean officialDomainMatched) {
        if (rawContentLength <= 0 || REJECT_PAGE_TYPES.contains(pageType)) {
            return "REJECT";
        }

        if (isOfficialAnchoredCandidate(candidate, officialDomainMatched)) {
            if (officialDomainMatched && ("OFFICIAL_DOC".equals(pageType) || "PDF".equals(pageType)) && rawContentLength >= 500) {
                return "STRONG";
            }
            if (officialDomainMatched && rawContentLength >= 500) {
                return "MEDIUM";
            }
            return "WEAK";
        }

        if (rawContentLength >= 2000 && Set.of("ARTICLE", "PDF", "OFFICIAL_DOC").contains(pageType)) {
            return "STRONG";
        }
        if (rawContentLength >= 1500 && Set.of("ARTICLE", "PDF", "OFFICIAL_DOC", "VIDEO_PAGE").contains(pageType)) {
            return "MEDIUM";
        }
        return "WEAK";
    }

    /**
     * completeness 与 qualityTier 需要分开表达。
     * 比如 PDF 经常能拿到较长 raw_content，但仍然可能不是全文，所以即便可用也默认标记为 PARTIAL，
     * 防止下游把它误当成完全可替代原网页采集的全量正文。
     */
    private String resolveContentCompleteness(String pageType, String qualityTier, int rawContentLength) {
        if ("PDF".equals(pageType)) {
            return "PARTIAL";
        }
        if ("REJECT".equals(qualityTier)
                || "WEAK".equals(qualityTier)
                || rawContentLength < Math.max(1, properties.getMinRawContentChars())) {
            return "THIN";
        }
        return "FULL_ENOUGH";
    }

    private boolean resolveFastLaneUsable(String pageType,
                                          String qualityTier,
                                          int rawContentLength,
                                          List<String> sourceUrls) {
        return ("STRONG".equals(qualityTier) || "MEDIUM".equals(qualityTier))
                && FAST_LANE_PAGE_TYPES.contains(pageType)
                && rawContentLength >= Math.max(1, properties.getMinRawContentChars())
                && sourceUrls != null
                && !sourceUrls.isEmpty();
    }

    private boolean resolveSkipNetworkVerification(SourceCandidate candidate,
                                                   String pageType,
                                                   int rawContentLength,
                                                   boolean fastLaneUsable) {
        double tavilyScore = candidate == null || candidate.getTavilyScore() == null ? 0.0D : candidate.getTavilyScore();
        return fastLaneUsable
                && SKIP_VERIFY_PAGE_TYPES.contains(pageType)
                && rawContentLength >= 2000
                && tavilyScore >= properties.getMinTavilyScore();
    }

    private String resolveRejectReason(String pageType,
                                       String qualityTier,
                                       int rawContentLength,
                                       List<String> sourceUrls,
                                       boolean fastLaneUsable) {
        if (fastLaneUsable) {
            return null;
        }
        if (REJECT_PAGE_TYPES.contains(pageType)) {
            return pageType;
        }
        if (rawContentLength <= 0) {
            return "EMPTY_RAW_CONTENT";
        }
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return "MISSING_SOURCE_URLS";
        }
        if ("WEAK".equals(qualityTier)) {
            return "WEAK_CONTENT";
        }
        if ("REJECT".equals(qualityTier)) {
            return "REJECTED_BY_GATE";
        }
        if (!FAST_LANE_PAGE_TYPES.contains(pageType)) {
            return pageType;
        }
        return "INSUFFICIENT_PREFETCHED_CONTENT";
    }

    /**
     * 官方锚点类查询要更严格。
     * 对 OFFICIAL / DOCS / PRICING 这类结果，只有命中可信官方域名时才允许进入 STRONG/MEDIUM，
     * 避免第三方转述页面因为正文较长被误判成官方证据。
     */
    private boolean isOfficialAnchoredCandidate(SourceCandidate candidate, boolean officialDomainMatched) {
        String queryMode = normalize(candidate == null ? null : candidate.getTavilyQueryMode());
        String sourceType = normalize(candidate == null ? null : candidate.getSourceType());
        if ("official_docs".equals(queryMode)) {
            return true;
        }
        /**
         * TRUSTED_WEB_EXPANSION 的设计目的就是在官方锚点不足时，引入可控的开放网络补证。
         * 因此这类结果不能再因为 sourceType 仍然是 DOCS / OFFICIAL / PRICING，
         * 就被当成“必须命中官方域名才能给中高质量”的官方锚点候选；
         * 否则受控扩展永远只能得到 WEAK，失去补广度的意义。
         */
        if ("trusted_web_expansion".equals(queryMode)) {
            return officialDomainMatched;
        }
        if ("evidence_repair".equals(queryMode) && officialDomainMatched) {
            return true;
        }
        return officialDomainMatched || Set.of("official", "docs", "pricing").contains(sourceType);
    }

    private List<String> mergeSourceUrls(SourceCandidate candidate, TavilyPrefetchedContent content, String url) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (candidate != null && candidate.getSourceUrls() != null) {
            merged.addAll(normalizeUrls(candidate.getSourceUrls()));
        }
        if (content != null && content.getSourceUrls() != null) {
            merged.addAll(normalizeUrls(content.getSourceUrls()));
        }
        if (StringUtils.hasText(url)) {
            merged.add(url.trim());
        }
        return new ArrayList<>(merged);
    }

    private List<String> normalizeUrls(List<String> sourceUrls) {
        List<String> normalized = new ArrayList<>();
        for (String sourceUrl : sourceUrls) {
            if (StringUtils.hasText(sourceUrl)) {
                normalized.add(sourceUrl.trim());
            }
        }
        return normalized;
    }

    private Set<String> normalizeDomains(Set<String> officialDomains) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (officialDomains == null || officialDomains.isEmpty()) {
            return normalized;
        }
        for (String domain : officialDomains) {
            String value = normalize(domain);
            if (StringUtils.hasText(value)) {
                normalized.add(stripLeadingWww(value));
            }
        }
        return normalized;
    }

    private boolean matchesOfficialDomain(String host, Set<String> officialDomains) {
        if (!StringUtils.hasText(host) || officialDomains.isEmpty()) {
            return false;
        }
        for (String officialDomain : officialDomains) {
            if (host.equals(officialDomain) || host.endsWith("." + officialDomain)) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return stripLeadingWww(normalize(host));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String stripLeadingWww(String value) {
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : defaultText(second);
    }

    private String normalize(String value) {
        return defaultText(value).trim().toLowerCase(Locale.ROOT);
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }
}
