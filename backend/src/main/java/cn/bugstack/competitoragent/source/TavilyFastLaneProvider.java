package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.DomainHintSet;
import cn.bugstack.competitoragent.search.tavily.TavilyDomainHintResolver;
import cn.bugstack.competitoragent.search.tavily.TavilyPageTypeClassifier;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentGate;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfileResolver;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tavily Fast Lane 搜索 Provider。
 * 该 Provider 只负责三件事：
 * 1. 基于 request 上下文构造 Tavily profile。
 * 2. 调用 Tavily client，并把 raw_content 注册到运行时 registry。
 * 3. 生成只包含轻量元数据的 SourceCandidate，再交给 Gate 回写 pageType/qualityTier 等结论。
 */
@Component
public class TavilyFastLaneProvider implements SearchSourceProvider {

    private static final List<String> DEFAULT_SCOPES = List.of("OFFICIAL", "DOCS", "PRICING", "NEWS", "REVIEW");

    private final TavilySearchProperties properties;
    private final TavilySearchClient client;
    private final TavilySearchProfileResolver profileResolver;
    private final TavilyDomainHintResolver domainHintResolver;
    private final TavilyPrefetchedContentRegistry registry;
    private final TavilyPrefetchedContentGate prefetchedContentGate;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    @Autowired
    public TavilyFastLaneProvider(TavilySearchProperties properties,
                                  TavilySearchClient client,
                                  TavilySearchProfileResolver profileResolver,
                                  TavilyPrefetchedContentRegistry registry,
                                  ObjectMapper objectMapper) {
        this(properties, client, profileResolver, new TavilyDomainHintResolver(), registry, objectMapper);
    }

    public TavilyFastLaneProvider(TavilySearchProperties properties,
                                  TavilySearchClient client,
                                  TavilySearchProfileResolver profileResolver,
                                  TavilyDomainHintResolver domainHintResolver,
                                  TavilyPrefetchedContentRegistry registry,
                                  ObjectMapper objectMapper) {
        this.properties = properties == null ? new TavilySearchProperties() : properties;
        this.client = client == null ? new TavilySearchClient(this.properties, objectMapper) : client;
        this.profileResolver = profileResolver == null ? new TavilySearchProfileResolver(this.properties) : profileResolver;
        this.domainHintResolver = domainHintResolver == null ? new TavilyDomainHintResolver() : domainHintResolver;
        this.registry = registry == null ? new TavilyPrefetchedContentRegistry() : registry;
        this.prefetchedContentGate = new TavilyPrefetchedContentGate(this.properties, new TavilyPageTypeClassifier());
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey("tavily")
                .displayName("Tavily Fast Lane")
                .capabilities(List.of("WEB_SEARCH", "PREFETCHED_CONTENT", "GLOBAL_RESULTS"))
                .defaultEnabled(false)
                .defaultFailOpen(true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return properties.isReady();
    }

    @Override
    public List<SourceCandidate> search(SearchSourceRequest request) {
        if (!isAvailable() || request == null || !StringUtils.hasText(request.getCompetitorName())) {
            return List.of();
        }
        if (StringUtils.hasText(request.getPreferredProviderKey())
                && !"tavily".equalsIgnoreCase(request.getPreferredProviderKey())) {
            return List.of();
        }

        DomainHintSet domainHintSet = domainHintResolver.resolve(request, List.of());
        List<String> scopes = request.getRequestedScopes() == null || request.getRequestedScopes().isEmpty()
                ? DEFAULT_SCOPES
                : request.getRequestedScopes();
        Map<String, SourceCandidate> merged = new LinkedHashMap<>();
        for (String scope : scopes) {
            for (SourceCandidate candidate : searchScope(request, scope, domainHintSet)) {
                if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
                    merged.putIfAbsent(candidate.getUrl(), candidate);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        return search(SearchSourceRequest.builder()
                .competitorName(competitorName)
                .requestedScopes(requestedScopes == null ? List.of() : requestedScopes)
                .preferredProviderKey("tavily")
                .requestPhase(SearchRequestPhase.SUPPLEMENT)
                .build());
    }

    private List<SourceCandidate> searchScope(SearchSourceRequest request,
                                              String scope,
                                              DomainHintSet domainHintSet) {
        TavilySearchProfile primaryProfile = buildPrimaryProfile(request, scope, domainHintSet);
        TavilySearchClient.TavilySearchResponse primaryResponse = client.search(primaryProfile);
        List<SourceCandidate> primaryCandidates = mapResponse(request, primaryResponse, primaryProfile, scope);
        if (shouldExpand(primaryProfile, primaryCandidates)) {
            TavilySearchProfile expansionProfile = profileResolver.resolveTrustedExpansion(
                    request.getCompetitorName(),
                    scope,
                    domainHintSet,
                    "officialDocHitCount=0; usableContentRatio below threshold"
            );
            TavilySearchClient.TavilySearchResponse expansionResponse = client.search(expansionProfile);
            return deduplicateByUrl(concat(primaryCandidates, mapResponse(request, expansionResponse, expansionProfile, scope)));
        }
        return primaryCandidates;
    }

    /**
     * 统一解释 request.searchQueries 与 preferredQueryMode 的关系：
     * 1. 只有显式 EVIDENCE_REPAIR 才把 searchQueries 当作 suggestedQueries 交给 resolver。
     * 2. 其它模式下 searchQueries 只作为 query override，避免普通搜索被误判成 evidence repair。
     */
    private TavilySearchProfile buildPrimaryProfile(SearchSourceRequest request,
                                                    String scope,
                                                    DomainHintSet domainHintSet) {
        TavilyQueryMode preferredMode = resolvePreferredQueryMode(request);
        TavilySearchProfile profile;
        if (preferredMode == TavilyQueryMode.EVIDENCE_REPAIR) {
            profile = profileResolver.resolve(
                    request.getCompetitorName(),
                    scope,
                    domainHintSet,
                    request.getSearchQueries()
            );
        } else if (preferredMode == TavilyQueryMode.TRUSTED_WEB_EXPANSION) {
            profile = profileResolver.resolveTrustedExpansion(
                    request.getCompetitorName(),
                    scope,
                    domainHintSet,
                    "preferredQueryMode=TRUSTED_WEB_EXPANSION"
            );
        } else if (preferredMode == TavilyQueryMode.OPEN_WEB) {
            profile = buildOpenWebProfile(request, scope);
        } else {
            profile = profileResolver.resolve(
                    request.getCompetitorName(),
                    scope,
                    domainHintSet,
                    List.of()
            );
        }

        String overrideQuery = firstNonBlank(request.getSearchQueries());
        if (StringUtils.hasText(overrideQuery)
                && profile.getQueryMode() != TavilyQueryMode.EVIDENCE_REPAIR
                && profile.getQueryMode() != TavilyQueryMode.TRUSTED_WEB_EXPANSION) {
            profile = profile.toBuilder().query(overrideQuery).build();
        }
        return profile;
    }

    private TavilySearchProfile buildOpenWebProfile(SearchSourceRequest request, String scope) {
        String query = firstNonBlank(request.getSearchQueries());
        if (!StringUtils.hasText(query)) {
            query = request.getCompetitorName() + " " + normalizeScope(scope).toLowerCase(Locale.ROOT);
        }
        return TavilySearchProfile.builder()
                .family(normalizeScope(scope))
                .queryMode(TavilyQueryMode.OPEN_WEB)
                .query(query)
                .includeDomains(List.of())
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .build();
    }

    private boolean shouldExpand(TavilySearchProfile primaryProfile, List<SourceCandidate> primaryCandidates) {
        if (primaryProfile == null || primaryProfile.getQueryMode() != TavilyQueryMode.OFFICIAL_DOCS) {
            return false;
        }
        if (primaryCandidates == null || primaryCandidates.isEmpty()) {
            return true;
        }

        /**
         * trusted expansion 不应只在“完全没结果”时触发。
         * 对 OFFICIAL_DOCS 首轮来说，只要出现下面任一情况，就说明官方锚点不足以直接支撑 fast lane：
         * 1. 命中了结果，但全部被 Gate 判为不可直接使用；
         * 2. 存在可用结果，但没有形成真正的 OFFICIAL_DOC / PDF 官方文档命中；
         * 这样可以避免首轮只返回搜索页、视频列表页或论坛噪声时，provider 误以为已经“有结果”而放弃受控扩展。
         */
        long usableCount = primaryCandidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .count();
        if (usableCount <= 0L) {
            return true;
        }
        long officialDocHitCount = primaryCandidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .filter(candidate -> {
                    String pageType = candidate.getPageType();
                    return "OFFICIAL_DOC".equalsIgnoreCase(pageType) || "PDF".equalsIgnoreCase(pageType);
                })
                .count();
        return officialDocHitCount <= 0L;
    }

    /**
     * 搜索结果先生成基础候选，再交给 Gate 做最终质量评估。
     * 这样可以把 pageType、qualityTier、fastLaneUsable、skipNetworkVerification、contentCompleteness
     * 全部统一收口在 Gate 中，避免 Provider 继续维护一套临时判断逻辑。
     */
    private List<SourceCandidate> mapResponse(SearchSourceRequest request,
                                              TavilySearchClient.TavilySearchResponse response,
                                              TavilySearchProfile profile,
                                              String scope) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }

        List<SourceCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (TavilySearchClient.TavilySearchResult result : response.getResults()) {
            if (result == null || !StringUtils.hasText(result.getUrl())) {
                continue;
            }
            rank++;

            String rawContent = defaultText(result.getRawContent());
            boolean hasPrefetchedContent = StringUtils.hasText(rawContent);
            double tavilyScore = result.getScore() == null ? 0.0D : result.getScore();
            TavilyPrefetchedContent prefetchedContent = TavilyPrefetchedContent.builder()
                    .url(result.getUrl())
                    .title(result.getTitle())
                    .content(result.getContent())
                    .rawContent(rawContent)
                    .cleanedContent(rawContent)
                    .sourceUrls(List.of(result.getUrl()))
                    .requestId(response.getRequestId())
                    .query(profile == null ? null : profile.getQuery())
                    .queryMode(profile == null || profile.getQueryMode() == null ? null : profile.getQueryMode().name())
                    .resultRank(rank)
                    .tavilyScore(tavilyScore)
                    .build();

            String prefetchedContentRef = null;
            if (hasPrefetchedContent) {
                prefetchedContentRef = registry.register(prefetchedContent);
            }

            SourceCandidate baseCandidate = SourceCandidate.builder()
                    .url(result.getUrl())
                    .title(StringUtils.hasText(result.getTitle()) ? result.getTitle() : result.getUrl())
                    .sourceType(normalizeScope(scope))
                    .providerKey("tavily")
                    .discoveryMethod(resolveDiscoveryMethod(request))
                    .reason(buildReason(profile, result))
                    .domain(extractDomain(result.getUrl()))
                    .sourceUrls(List.of(result.getUrl()))
                    .relevanceScore(Math.max(0.55D, tavilyScore))
                    .freshnessScore(0.60D)
                    .qualityScore(Math.max(0.55D, tavilyScore))
                    .searchQuery(profile == null ? null : profile.getQuery())
                    .searchEngine("tavily")
                    .resultRank(rank)
                    .selectionStage("PLANNED")
                    .selectionReason("通过 Tavily Fast Lane 搜索命中候选来源")
                    .hasPrefetchedContent(hasPrefetchedContent)
                    .prefetchedContentRef(prefetchedContentRef)
                    .prefetchedRawContentLength(hasPrefetchedContent ? rawContent.length() : null)
                    .tavilyScore(tavilyScore)
                    .tavilyRequestId(response.getRequestId())
                    .tavilyQuery(profile == null ? null : profile.getQuery())
                    .tavilyQueryMode(profile == null || profile.getQueryMode() == null ? null : profile.getQueryMode().name())
                    .build();

            candidates.add(prefetchedContentGate.apply(
                    baseCandidate,
                    hasPrefetchedContent ? prefetchedContent : null,
                    resolveOfficialDomains(profile)
            ));
        }
        return deduplicateByUrl(candidates);
    }

    private String buildReason(TavilySearchProfile profile, TavilySearchClient.TavilySearchResult result) {
        String mode = profile == null || profile.getQueryMode() == null ? "UNKNOWN" : profile.getQueryMode().name();
        String title = StringUtils.hasText(result == null ? null : result.getTitle())
                ? result.getTitle()
                : "未命名结果";
        return "Tavily Fast Lane 命中 " + mode + " 结果: " + title;
    }

    /**
     * bootstrap 与 supplement 必须在候选层保留不同的 discoveryMethod，
     * 这样后续排序、审计和黄金路径回放才能解释“这条 Tavily 候选是在 Phase 1 还是补源阶段出现的”。
     */
    private String resolveDiscoveryMethod(SearchSourceRequest request) {
        if (request != null && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP) {
            return "TAVILY_PHASE1_BOOTSTRAP";
        }
        return "TAVILY_FAST_LANE";
    }

    private TavilyQueryMode resolvePreferredQueryMode(SearchSourceRequest request) {
        if (request == null || !StringUtils.hasText(request.getPreferredQueryMode())) {
            return null;
        }
        try {
            return TavilyQueryMode.valueOf(request.getPreferredQueryMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "OPEN_WEB";
        }
        return scope.trim().toUpperCase(Locale.ROOT);
    }

    private Set<String> resolveOfficialDomains(TavilySearchProfile profile) {
        if (profile == null || profile.getIncludeDomains() == null || profile.getIncludeDomains().isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(profile.getIncludeDomains());
    }

    private String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private List<SourceCandidate> deduplicateByUrl(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, SourceCandidate> deduplicated = new LinkedHashMap<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
                deduplicated.putIfAbsent(candidate.getUrl(), candidate);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private List<SourceCandidate> concat(List<SourceCandidate> left, List<SourceCandidate> right) {
        LinkedHashSet<SourceCandidate> merged = new LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return new ArrayList<>(merged);
    }

    private String extractDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }
}
