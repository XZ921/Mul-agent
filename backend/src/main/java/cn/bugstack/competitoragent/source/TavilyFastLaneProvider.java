package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.tavily.DomainHintSet;
import cn.bugstack.competitoragent.search.tavily.FieldEvidenceQueryExecutionAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyDomainHintResolver;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyPageTypeClassifier;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentGate;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.search.tavily.TavilyQueryMode;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfileResolver;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
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
    private static final long FIELD_QUERY_MIN_START_BUDGET_MILLIS = 1_000L;

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

        ScopeResolution scopeResolution = resolveScopeResolution(request);
        if (hasFieldEvidenceQueries(request)) {
            ScopeSearchResult fieldEvidenceSearchResult = searchFieldEvidenceQueries(request, scopeResolution);
            request.setTavilyFastLaneAudit(buildMergedFieldEvidenceAudit(
                    scopeResolution,
                    fieldEvidenceSearchResult.audit() == null ? List.of() : List.of(fieldEvidenceSearchResult.audit())
            ));
            return fieldEvidenceSearchResult.candidates();
        }

        DomainHintSet domainHintSet = domainHintResolver.resolve(request, List.of());
        Map<String, SourceCandidate> merged = new LinkedHashMap<>();
        for (String scope : scopeResolution.effectiveScopes()) {
            ScopeSearchResult scopeSearchResult = searchScope(request, scope, domainHintSet);
            for (SourceCandidate candidate : scopeSearchResult.candidates()) {
                if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
                    merged.putIfAbsent(candidate.getUrl(), candidate);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private ScopeResolution resolveScopeResolution(SearchSourceRequest request) {
        LinkedHashSet<String> requestedScopeSet = new LinkedHashSet<>();
        List<String> rawRequestedScopes = request == null ? null : request.getRequestedScopes();
        if (rawRequestedScopes == null || rawRequestedScopes.isEmpty()) {
            requestedScopeSet.addAll(DEFAULT_SCOPES);
        } else {
            for (String scope : rawRequestedScopes) {
                if (StringUtils.hasText(scope)) {
                    requestedScopeSet.add(normalizeScope(scope));
                }
            }
        }
        /*
         * 字段级 query 本身已经声明了要找的证据类型，外层节点 scope 不能把它过滤掉。
         * 例如 OFFICIAL 节点里规划出的 DOCS / OPEN_WEB 变体，必须进入 Tavily 执行队列。
         */
        if (requestedScopeSet.isEmpty()) {
            requestedScopeSet.addAll(DEFAULT_SCOPES);
        }
        LinkedHashSet<String> effectiveScopeSet = new LinkedHashSet<>(requestedScopeSet);
        LinkedHashMap<String, LinkedHashSet<String>> scopeExpansionSources = new LinkedHashMap<>();
        if (request != null && request.getFieldEvidenceQueries() != null) {
            for (FieldEvidenceQuery query : request.getFieldEvidenceQueries()) {
                if (query == null || !StringUtils.hasText(query.getSourceType())) {
                    continue;
                }
                String expandedScope = normalizeScope(query.getSourceType());
                effectiveScopeSet.add(expandedScope);
                if (!requestedScopeSet.contains(expandedScope)) {
                    scopeExpansionSources
                            .computeIfAbsent(expandedScope, ignored -> new LinkedHashSet<>())
                            .add(describeScopeExpansionSource(query));
                }
            }
        }
        return new ScopeResolution(
                new ArrayList<>(requestedScopeSet),
                new ArrayList<>(effectiveScopeSet),
                copyScopeExpansionSources(scopeExpansionSources)
        );
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

    private ScopeSearchResult searchScope(SearchSourceRequest request,
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
            return new ScopeSearchResult(
                    deduplicateByUrl(concat(primaryCandidates, mapResponse(request, expansionResponse, expansionProfile, scope))),
                    null
            );
        }
        return new ScopeSearchResult(primaryCandidates, null);
    }

    /**
     * 字段级证据 query 必须逐条执行，不能退化成只消费第一条 searchQueries。
     * 这里按当前 scope 过滤匹配的字段 query，并对单条 Tavily 调用做 fail-open。
     */
    private ScopeSearchResult searchFieldEvidenceQueries(SearchSourceRequest request, ScopeResolution scopeResolution) {
        List<SourceCandidate> candidates = new ArrayList<>();
        List<FieldEvidenceQueryExecutionAudit> queryAudits = new ArrayList<>();
        LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        Map<String, List<SourceCandidate>> discoveredCandidatesByField = new LinkedHashMap<>();
        LinkedHashSet<String> effectiveScopes = new LinkedHashSet<>();
        if (scopeResolution != null && scopeResolution.effectiveScopes() != null) {
            for (String effectiveScope : scopeResolution.effectiveScopes()) {
                effectiveScopes.add(normalizeScope(effectiveScope));
            }
        }
        for (FieldEvidenceQuery query : request.getFieldEvidenceQueries()) {
            if (query == null || !StringUtils.hasText(query.getQuery())) {
                continue;
            }
            String queryScope = resolveScopeForQuery(query);
            if (!effectiveScopes.isEmpty() && !effectiveScopes.contains(normalizeScope(queryScope))) {
                continue;
            }
            TavilySearchProfile profile = profileResolver.resolveFieldEvidence(query);
            String fieldCoverageKey = resolveFieldCoverageKey(query);
            if (isFieldCandidateCoverageMet(discoveredCandidatesByField.get(fieldCoverageKey))) {
                queryAudits.add(buildFieldEvidenceQueryAudit(query, profile, "SKIPPED", 0L, 0,
                        null, "SKIPPED_FIELD_CANDIDATE_COVERAGE_MET", null));
                continue;
            }
            Long remainingBudgetMillis = resolveRemainingFieldEvidenceBudgetMillis(request);
            /*
             * field evidence 的预算门禁必须作用于每一条 query，而不是只限制“第二条及以后”。
             * 一旦 deadline 已经过期，或者剩余预算连最小启动窗口都不够，就只记录 skipped audit，
             * 不再继续构造 profile，更不能继续发 Tavily 请求。
             */
            if (shouldStopFieldEvidenceExecution(remainingBudgetMillis)) {
                queryAudits.add(buildFieldEvidenceQueryAudit(query, profile, "SKIPPED", 0L, 0,
                        null, "SKIPPED_BUDGET_EXHAUSTED", null));
                continue;
            }
            long startedAt = System.currentTimeMillis();
            try {
                TavilySearchClient.TavilySearchResponse response = remainingBudgetMillis == null
                        ? client.search(profile)
                        : client.search(profile, remainingBudgetMillis);
                int resultCount = response == null || response.getResults() == null ? 0 : response.getResults().size();
                String requestId = response == null ? null : response.getRequestId();
                if (StringUtils.hasText(requestId)) {
                    requestIds.add(requestId.trim());
                }
                if (response != null && StringUtils.hasText(response.getFailureReason())) {
                    queryAudits.add(buildFieldEvidenceQueryAudit(query, profile, "FAILED",
                            System.currentTimeMillis() - startedAt, resultCount, requestId,
                            null, response.getFailureReason()));
                } else {
                    queryAudits.add(buildFieldEvidenceQueryAudit(query, profile, "SUCCESS",
                            System.currentTimeMillis() - startedAt, resultCount, requestId,
                            null, null));
                }
                List<SourceCandidate> mappedCandidates = mapResponse(request, response, profile, queryScope);
                candidates.addAll(mappedCandidates);
                if (!mappedCandidates.isEmpty()) {
                    discoveredCandidatesByField
                            .computeIfAbsent(fieldCoverageKey, ignored -> new ArrayList<>())
                            .addAll(mappedCandidates);
                }
            } catch (RuntimeException exception) {
                queryAudits.add(buildFieldEvidenceQueryAudit(query, profile, "FAILED",
                        System.currentTimeMillis() - startedAt, 0, null,
                        null, exception.getMessage()));
                candidates.add(buildFailedFieldEvidenceCandidate(query, queryScope, exception.getMessage()));
            }
        }
        List<SourceCandidate> deduplicatedCandidates = deduplicateByUrl(candidates);
        return new ScopeSearchResult(
                deduplicatedCandidates,
                buildFieldEvidenceFastLaneAudit(queryAudits, requestIds, deduplicatedCandidates)
        );
    }

    private TavilyFastLaneAudit buildFieldEvidenceFastLaneAudit(List<FieldEvidenceQueryExecutionAudit> queryAudits,
                                                               Set<String> requestIds,
                                                               List<SourceCandidate> candidates) {
        if (queryAudits == null || queryAudits.isEmpty()) {
            return null;
        }
        int queriesSent = 0;
        int totalResults = 0;
        int fastLaneUsableCount = 0;
        LinkedHashMap<String, Integer> rejectionReasons = new LinkedHashMap<>();
        LinkedHashSet<String> queryModes = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> fieldDistribution = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sourceTypeDistribution = new LinkedHashMap<>();
        if (candidates != null) {
            for (SourceCandidate candidate : candidates) {
                if (candidate != null && Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                    fastLaneUsableCount++;
                }
            }
        }
        for (FieldEvidenceQueryExecutionAudit audit : queryAudits) {
            if (audit == null) {
                continue;
            }
            if (!"SKIPPED".equalsIgnoreCase(audit.getStatus())) {
                queriesSent++;
            }
            totalResults += audit.getResultCount() == null ? 0 : audit.getResultCount();
            if (StringUtils.hasText(audit.getQueryMode())) {
                queryModes.add(audit.getQueryMode().trim());
            }
            if (StringUtils.hasText(audit.getFieldName())) {
                fieldDistribution.merge(audit.getFieldName().trim(), 1, Integer::sum);
            }
            if (StringUtils.hasText(audit.getSourceType())) {
                sourceTypeDistribution.merge(audit.getSourceType().trim(), 1, Integer::sum);
            }
            if (StringUtils.hasText(audit.getFailureReason())) {
                rejectionReasons.merge(audit.getFailureReason().trim(), 1, Integer::sum);
            }
            if (StringUtils.hasText(audit.getSkipReason())) {
                rejectionReasons.merge(audit.getSkipReason().trim(), 1, Integer::sum);
            }
        }
        return TavilyFastLaneAudit.builder()
                .queryModes(queryModes.isEmpty() ? List.of("FIELD_EVIDENCE") : new ArrayList<>(queryModes))
                .queryOrigins(List.of("SUPPLEMENT"))
                .queriesSent(queriesSent)
                .totalResults(totalResults)
                .fastLaneUsableCount(fastLaneUsableCount)
                .fastLaneRejectedCount(Math.max(0, totalResults - fastLaneUsableCount))
                .rejectionReasons(rejectionReasons.isEmpty() ? Map.of() : rejectionReasons)
                .bootstrapTriggered(false)
                .fallbackTriggered(false)
                .tavilyRequestIds(requestIds == null ? List.of() : new ArrayList<>(requestIds))
                .winnerRawFetchCount(0)
                .fieldEvidenceQueryExecutions(queryAudits)
                .fieldDistribution(fieldDistribution.isEmpty() ? Map.of() : fieldDistribution)
                .sourceTypeDistribution(sourceTypeDistribution.isEmpty() ? Map.of() : sourceTypeDistribution)
                .build();
    }

    /**
     * 多 scope field query 的审计必须在 search() 外层统一合并，
     * 这样后一个 scope 的空 audit 才不会把前一个 scope 的有效审计冲掉。
     */
    private TavilyFastLaneAudit buildMergedFieldEvidenceAudit(ScopeResolution scopeResolution,
                                                              List<TavilyFastLaneAudit> scopeAudits) {
        TavilyFastLaneAudit mergedAudit = TavilyFastLaneAudit.merge(scopeAudits);
        if (mergedAudit == null) {
            return null;
        }
        return mergedAudit.toBuilder()
                .requestedScopes(scopeResolution == null ? List.of() : scopeResolution.requestedScopes())
                .effectiveScopes(scopeResolution == null ? List.of() : scopeResolution.effectiveScopes())
                .scopeExpansionSources(scopeResolution == null ? Map.of() : scopeResolution.scopeExpansionSources())
                .build();
    }

    private FieldEvidenceQueryExecutionAudit buildFieldEvidenceQueryAudit(FieldEvidenceQuery query,
                                                                          TavilySearchProfile profile,
                                                                          String status,
                                                                          long elapsedMillis,
                                                                          int resultCount,
                                                                          String requestId,
                                                                          String skipReason,
                                                                          String failureReason) {
        return FieldEvidenceQueryExecutionAudit.builder()
                .queryFingerprint(query == null ? null : query.getQueryFingerprint())
                .fieldName(query == null ? null : query.getFieldName())
                .sourceType(normalizeScope(query == null ? null : query.getSourceType()))
                .evidencePathKey(query == null ? null : query.getEvidencePathKey())
                .queryIntent(query == null ? null : query.getQueryIntent())
                .query(query == null ? null : query.getQuery())
                .queryMode(profile == null || profile.getQueryMode() == null ? null : profile.getQueryMode().name())
                .profileStage(profile == null ? null : profile.getProfileStage())
                .searchDepth(profile == null ? null : profile.getSearchDepth())
                .includeRawContent(profile == null ? null : profile.isIncludeRawContent())
                .status(status)
                .elapsedMillis(elapsedMillis)
                .resultCount(resultCount)
                .tavilyRequestId(requestId)
                .skipReason(skipReason)
                .failureReason(failureReason)
                .build();
    }

    /**
     * 执行层预算闸门的职责是“没预算就别再启动下一条长请求”，
     * 这里先实现最小兜底：只要 deadline 已经耗尽，或连 1 秒启动预算都不够，就直接停止后续 query。
     */
    private boolean shouldStopFieldEvidenceExecution(Long remainingBudgetMillis) {
        return remainingBudgetMillis != null && remainingBudgetMillis < FIELD_QUERY_MIN_START_BUDGET_MILLIS;
    }

    private boolean shouldMergeFieldEvidenceAudit(TavilyFastLaneAudit audit) {
        return audit != null
                && audit.getFieldEvidenceQueryExecutions() != null
                && !audit.getFieldEvidenceQueryExecutions().isEmpty();
    }

    private boolean hasFieldEvidenceQueries(SearchSourceRequest request) {
        return request != null
                && request.getFieldEvidenceQueries() != null
                && !request.getFieldEvidenceQueries().isEmpty();
    }

    private Long resolveRemainingFieldEvidenceBudgetMillis(SearchSourceRequest request) {
        if (request == null || request.getFieldEvidenceExecutionDeadlineEpochMillis() == null) {
            return null;
        }
        return request.getFieldEvidenceExecutionDeadlineEpochMillis() - System.currentTimeMillis();
    }

    /**
     * 字段 query 的 sourceType 就是它希望命中的证据范围。
     * 若规划层没有显式给出 sourceType，则回退到当前外层 scope，兼容旧调用方。
     */
    /**
     * 字段覆盖即停只关心“这个字段是否已经拿到足够多的发现候选”，
     * 因此这里按字段维度累计 discovery candidate，而不是按 raw 正文是否齐全来判断。
     */
    private String resolveFieldCoverageKey(FieldEvidenceQuery query) {
        if (query != null && StringUtils.hasText(query.getFieldName())) {
            return query.getFieldName().trim();
        }
        if (query != null && StringUtils.hasText(query.getEvidencePathKey())) {
            return query.getEvidencePathKey().trim();
        }
        return "unknown";
    }

    private boolean isFieldCandidateCoverageMet(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        LinkedHashMap<String, SourceCandidate> distinctCandidates = new LinkedHashMap<>();
        for (SourceCandidate candidate : candidates) {
            if (!isDiscoveryCoverageCandidate(candidate)) {
                continue;
            }
            distinctCandidates.putIfAbsent(candidate.getUrl(), candidate);
        }
        if (distinctCandidates.size() < 2) {
            return false;
        }
        boolean hasOfficialOrDocs = distinctCandidates.values().stream()
                .anyMatch(this::isOfficialOrDocsDiscoveryCandidate);
        boolean hasThirdParty = distinctCandidates.values().stream()
                .anyMatch(this::isThirdPartyDiscoveryCandidate);
        return hasOfficialOrDocs && hasThirdParty;
    }

    private boolean isDiscoveryCoverageCandidate(SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return false;
        }
        if (!Boolean.TRUE.equals(candidate.getCandidateDiscoveryUsable())) {
            return false;
        }
        String pageType = defaultText(candidate.getPageType()).trim().toUpperCase(Locale.ROOT);
        return !"SEARCH_PAGE".equals(pageType) && !"VIDEO_LIST".equals(pageType);
    }

    private boolean isOfficialOrDocsDiscoveryCandidate(SourceCandidate candidate) {
        String sourceType = normalizeScope(candidate == null ? null : candidate.getSourceType());
        return "OFFICIAL".equals(sourceType)
                || "DOCS".equals(sourceType)
                || "PRICING".equals(sourceType)
                || "TERMS".equals(sourceType);
    }

    private boolean isThirdPartyDiscoveryCandidate(SourceCandidate candidate) {
        String sourceType = normalizeScope(candidate == null ? null : candidate.getSourceType());
        return "REVIEW".equals(sourceType)
                || "NEWS".equals(sourceType)
                || "OPEN_WEB".equals(sourceType);
    }

    private String resolveScopeForQuery(FieldEvidenceQuery query) {
        if (query != null && StringUtils.hasText(query.getSourceType())) {
            return normalizeScope(query.getSourceType());
        }
        return "OPEN_WEB";
    }

    /**
     * 单条字段 query 失败时也要留下可审计候选，避免调用方只看到空结果却无法追溯失败原因。
     */
    private SourceCandidate buildFailedFieldEvidenceCandidate(FieldEvidenceQuery query, String scope, String reason) {
        String fingerprint = query == null || !StringUtils.hasText(query.getQueryFingerprint())
                ? \u0022unknown\u0022
                : query.getQueryFingerprint().trim();
        return SourceCandidate.builder()
                .url(\u0022field-evidence-query://\u0022 + fingerprint)
                .title(\u0022字段证据 query 执行失败\u0022)
                .sourceType(resolveScopeForQuery(query))
                .providerKey(\u0022tavily\u0022)
                .discoveryMethod(\u0022TAVILY_FIELD_EVIDENCE_QUERY\u0022)
                .reason(query == null ? \u0022字段证据 query 执行失败\u0022 : query.getReason())
                .sourceUrls(List.of())
                .fieldName(query == null ? null : query.getFieldName())
                .evidencePathKey(query == null ? null : query.getEvidencePathKey())
                .queryIntent(query == null ? null : query.getQueryIntent())
                .fieldEvidenceQueryFingerprint(query == null ? null : query.getQueryFingerprint())
                .fieldEvidenceQueryReason(query == null ? null : query.getReason())
                .searchQuery(query == null ? null : query.getQuery())
                .searchEngine(\u0022tavily\u0022)
                .tavilyQuery(query == null ? null : query.getQuery())
                .qualitySignals(List.of(\u0022TAVILY_FIELD_QUERY_FAILED\u0022))
                .selectionStage(\u0022FAILED\u0022)
                .selectionReason(StringUtils.hasText(reason) ? reason : \u0022Tavily 字段 query 执行失败\u0022)
                .build();
    }

    /**
     * 统一解释 request.searchQueries 与 preferredQueryMode 的关系：
     * 1. 只有显式 EVIDENCE_REPAIR 才把 searchQueries 当作 suggestedQueries 交给 resolver。
     * 2. 显式 OFFICIAL_DOCS 才进入严格官方锚点，默认官方类主搜索仍交给 search-first family 路由。
     * 3. 其它模式下 searchQueries 只作为 query override，避免普通搜索被误判成 evidence repair。
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
        } else if (preferredMode == TavilyQueryMode.OFFICIAL_DOCS) {
            profile = profileResolver.resolveOfficialDocsAnchor(
                    request.getCompetitorName(),
                    scope,
                    domainHintSet
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
                && profile.getQueryMode() != TavilyQueryMode.EVIDENCE_REPAIR) {
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

        /*
         * 官方锚点扩展只处理“第一枪完全不可用”的场景。
         * 只要 OFFICIAL_DOCS 首轮已经拿到可用候选，即使页面类型不是 OFFICIAL_DOC/PDF，
         * 也先接受这次官方命中，避免因为类型不够像文档而继续扩散到开放网，重新放大 Tavily 请求量。
         */
        long usableCount = primaryCandidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getFastLaneUsable()))
                .count();
        return usableCount <= 0L;
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

            /*
             * FIELD_EVIDENCE_DISCOVERY 阶段显式禁止消费 raw_content。
             * 即使测试桩或上游意外回了 raw，也只能按“发现候选”处理，避免误判成可直接落库的 fast lane evidence。
             */
            String rawContent = shouldConsumeRawContent(profile) ? defaultText(result.getRawContent()) : "";
            boolean hasPrefetchedContent = StringUtils.hasText(rawContent);
            double tavilyScore = result.getScore() == null ? 0.0D : result.getScore();
            TavilyPrefetchedContent prefetchedContent = TavilyPrefetchedContent.builder()
                    .url(result.getUrl())
                    .title(result.getTitle())
                    .content(result.getContent())
                    .rawContent(rawContent)
                    .cleanedContent(StringUtils.hasText(rawContent) ? rawContent : defaultText(result.getContent()))
                    .sourceUrls(List.of(result.getUrl()))
                    .requestId(response.getRequestId())
                    .query(profile == null ? null : profile.getQuery())
                    .queryMode(profile == null || profile.getQueryMode() == null ? null : profile.getQueryMode().name())
                    .resultRank(rank)
                    .tavilyScore(tavilyScore)
                    .fieldName(profile == null ? null : profile.getFieldName())
                    .evidencePathKey(profile == null ? null : profile.getEvidencePathKey())
                    .queryIntent(profile == null ? null : profile.getQueryIntent())
                    .fieldEvidenceQueryFingerprint(profile == null ? null : profile.getFieldEvidenceQueryFingerprint())
                    .fieldEvidenceQueryReason(profile == null ? null : profile.getFieldEvidenceQueryReason())
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
                    .discoveryMethod(resolveDiscoveryMethod(request, profile))
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
                    .fieldName(profile == null ? null : profile.getFieldName())
                    .evidencePathKey(profile == null ? null : profile.getEvidencePathKey())
                    .queryIntent(profile == null ? null : profile.getQueryIntent())
                    .fieldEvidenceQueryFingerprint(profile == null ? null : profile.getFieldEvidenceQueryFingerprint())
                    .fieldEvidenceQueryReason(profile == null ? null : profile.getFieldEvidenceQueryReason())
                    .build();

            SourceCandidate gatedCandidate = prefetchedContentGate.apply(
                    baseCandidate,
                    hasPrefetchedContent ? prefetchedContent : null,
                    resolveOfficialDomains(profile)
            );
            candidates.add(applyFieldEvidenceDiscoverySemantics(gatedCandidate, profile));
        }
        return deduplicateByUrl(candidates);
    }

    private boolean shouldConsumeRawContent(TavilySearchProfile profile) {
        return profile == null || profile.isIncludeRawContent();
    }

    /**
     * discovery 阶段的候选只承担“后续是否值得继续抓正文”的语义，
     * 因此这里单独打上 candidateDiscoveryUsable，和 fastLaneUsable 明确区分。
     */
    private SourceCandidate applyFieldEvidenceDiscoverySemantics(SourceCandidate candidate, TavilySearchProfile profile) {
        if (candidate == null) {
            return null;
        }
        if (profile == null || !"FIELD_EVIDENCE_DISCOVERY".equalsIgnoreCase(profile.getProfileStage())) {
            return candidate;
        }
        return candidate.toBuilder()
                .candidateDiscoveryUsable(resolveCandidateDiscoveryUsable(candidate))
                .build();
    }

    private boolean resolveCandidateDiscoveryUsable(SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return false;
        }
        if (candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()) {
            return false;
        }
        String pageType = defaultText(candidate.getPageType()).trim().toUpperCase(Locale.ROOT);
        return !"SEARCH_PAGE".equals(pageType) && !"VIDEO_LIST".equals(pageType);
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
    private String resolveDiscoveryMethod(SearchSourceRequest request, TavilySearchProfile profile) {
        if (profile != null && StringUtils.hasText(profile.getFieldEvidenceQueryFingerprint())) {
            return "TAVILY_FIELD_EVIDENCE_QUERY";
        }
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
        if (profile == null) {
            return Set.of();
        }
        LinkedHashSet<String> officialDomains = new LinkedHashSet<>();
        /*
         * officialDomains 是 Gate 的质量锚点，includeDomains 是 Tavily API 的检索范围。
         * 搜索优先模式会主动清空 includeDomains，但 Gate 仍需要知道哪些域名可视为官方命中。
         */
        if (profile.getOfficialDomains() != null) {
            officialDomains.addAll(profile.getOfficialDomains());
        }
        if (profile.getIncludeDomains() != null) {
            officialDomains.addAll(profile.getIncludeDomains());
        }
        return officialDomains;
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

    private String describeScopeExpansionSource(FieldEvidenceQuery query) {
        if (query == null) {
            return "fieldQuery:unknown";
        }
        String fingerprint = StringUtils.hasText(query.getQueryFingerprint())
                ? query.getQueryFingerprint().trim()
                : "unknown";
        String fieldName = StringUtils.hasText(query.getFieldName()) ? query.getFieldName().trim() : "unknown";
        String evidencePathKey = StringUtils.hasText(query.getEvidencePathKey())
                ? query.getEvidencePathKey().trim()
                : "unknown";
        String queryIntent = StringUtils.hasText(query.getQueryIntent()) ? query.getQueryIntent().trim() : "unknown";
        return "fieldQuery:" + fingerprint
                + "|field=" + fieldName
                + "|path=" + evidencePathKey
                + "|intent=" + queryIntent;
    }

    private Map<String, List<String>> copyScopeExpansionSources(Map<String, LinkedHashSet<String>> scopeExpansionSources) {
        if (scopeExpansionSources == null || scopeExpansionSources.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : scopeExpansionSources.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copied.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copied.isEmpty() ? Map.of() : copied;
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

    private record ScopeResolution(List<String> requestedScopes,
                                   List<String> effectiveScopes,
                                   Map<String, List<String>> scopeExpansionSources) {
    }

    private record ScopeSearchResult(List<SourceCandidate> candidates,
                                     TavilyFastLaneAudit audit) {
    }
}
