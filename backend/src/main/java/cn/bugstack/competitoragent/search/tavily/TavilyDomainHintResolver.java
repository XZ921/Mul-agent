package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.TavilySearchClient;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tavily 域名提示解析器。
 * 这里统一把用户输入、规划提示、已验证候选和 OPEN_WEB bootstrap 结果归并成 DomainHintSet，
 * 避免 Provider 内部散落多套域名推断逻辑，确保 include_domains 的来源可解释、可追溯。
 */
public class TavilyDomainHintResolver {

    private static final List<String> ANCHORED_SOURCE_TYPES = List.of("OFFICIAL", "DOCS", "PRICING");

    /**
     * 按固定优先级构建域名提示集合。
     * 优先级顺序为：
     * 1. includeDomains：最强约束，来源于用户或 orchestration
     * 2. preferredDomains：配置或规划偏好
     * 3. verified seedCandidates：已经经过运行期验证的官方/文档候选
     * 4. OPEN_WEB bootstrap：只作为低置信候选官方域名补充
     */
    public DomainHintSet resolve(SearchSourceRequest request,
                                 List<TavilySearchClient.TavilySearchResponse> bootstrapResponses) {
        Map<String, DomainHint> orderedHints = new LinkedHashMap<>();
        String competitorName = request == null ? null : request.getCompetitorName();
        String requestedFamily = resolveRequestedFamily(request);

        addDomainsFromExplicitList(orderedHints,
                request == null ? List.of() : request.getIncludeDomains(),
                requestedFamily,
                0.95D,
                "USER_OR_ORCHESTRATION",
                "由用户或编排层显式指定 includeDomains");
        addDomainsFromExplicitList(orderedHints,
                request == null ? List.of() : request.getPreferredDomains(),
                requestedFamily,
                0.85D,
                "CONFIG_HINT",
                "由配置或规划阶段提供 preferredDomains");
        addDomainsFromVerifiedSeeds(orderedHints,
                request == null ? List.of() : request.getSeedCandidates(),
                requestedFamily);
        addDomainsFromBootstrapResponses(orderedHints,
                bootstrapResponses == null ? List.of() : bootstrapResponses,
                requestedFamily);

        return DomainHintSet.builder()
                .competitorName(competitorName)
                .domains(new ArrayList<>(orderedHints.values()))
                .build();
    }

    private void addDomainsFromExplicitList(Map<String, DomainHint> orderedHints,
                                            List<String> domains,
                                            String requestedFamily,
                                            double confidence,
                                            String source,
                                            String reason) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        for (String domain : domains) {
            String normalizedDomain = normalizeDomain(domain);
            if (!StringUtils.hasText(normalizedDomain) || orderedHints.containsKey(normalizedDomain)) {
                continue;
            }
            orderedHints.put(normalizedDomain, DomainHint.builder()
                    .domain(normalizedDomain)
                    .sourceFamily(requestedFamily)
                    .confidence(confidence)
                    .source(source)
                    .reason(reason)
                    .sourceUrls(List.of("domain://" + normalizedDomain))
                    .build());
        }
    }

    /**
     * 只接受官方/文档/定价家族且已验证通过的候选域名。
     * 这样可以避免把普通开放网页或低信任候选误提炼成 include_domains 提示。
     */
    private void addDomainsFromVerifiedSeeds(Map<String, DomainHint> orderedHints,
                                             List<SourceCandidate> seedCandidates,
                                             String requestedFamily) {
        if (seedCandidates == null || seedCandidates.isEmpty()) {
            return;
        }
        for (SourceCandidate seedCandidate : seedCandidates) {
            if (seedCandidate == null || !Boolean.TRUE.equals(seedCandidate.getVerified())) {
                continue;
            }
            String sourceType = normalizeSourceType(seedCandidate.getSourceType());
            if (!ANCHORED_SOURCE_TYPES.contains(sourceType)) {
                continue;
            }
            String domain = normalizeDomain(seedCandidate.getDomain());
            if (!StringUtils.hasText(domain)) {
                domain = extractDomain(seedCandidate.getUrl());
            }
            if (!StringUtils.hasText(domain) || orderedHints.containsKey(domain)) {
                continue;
            }
            orderedHints.put(domain, DomainHint.builder()
                    .domain(domain)
                    .sourceFamily(requestedFamily)
                    .confidence(0.80D)
                    .source("VERIFIED_CANDIDATE")
                    .reason("来自已验证通过的官方/文档候选域名")
                    .sourceUrls(resolveSourceUrls(seedCandidate))
                    .build());
        }
    }

    /**
     * OPEN_WEB bootstrap 结果只用于补充候选官方域名，不直接升级为高置信来源。
     * 这里统一使用较低置信度 0.60，等待后续官方命中或 Gate 进一步确认。
     */
    private void addDomainsFromBootstrapResponses(Map<String, DomainHint> orderedHints,
                                                  List<TavilySearchClient.TavilySearchResponse> bootstrapResponses,
                                                  String requestedFamily) {
        if (bootstrapResponses == null || bootstrapResponses.isEmpty()) {
            return;
        }
        for (TavilySearchClient.TavilySearchResponse response : bootstrapResponses) {
            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                continue;
            }
            for (TavilySearchClient.TavilySearchResult result : response.getResults()) {
                if (result == null) {
                    continue;
                }
                String domain = extractDomain(result.getUrl());
                if (!StringUtils.hasText(domain) || orderedHints.containsKey(domain)) {
                    continue;
                }
                orderedHints.put(domain, DomainHint.builder()
                        .domain(domain)
                        .sourceFamily(requestedFamily)
                        .confidence(0.60D)
                        .source("OPEN_WEB_BOOTSTRAP")
                        .reason("来自 OPEN_WEB bootstrap 结果提炼的候选品牌域名")
                        .sourceUrls(StringUtils.hasText(result.getUrl()) ? List.of(result.getUrl()) : List.of())
                        .build());
            }
        }
    }

    private String resolveRequestedFamily(SearchSourceRequest request) {
        if (request == null || request.getRequestedScopes() == null || request.getRequestedScopes().isEmpty()) {
            return "OPEN_WEB";
        }
        return normalizeSourceType(request.getRequestedScopes().get(0));
    }

    private List<String> resolveSourceUrls(SourceCandidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        if (candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()) {
            return candidate.getSourceUrls();
        }
        if (StringUtils.hasText(candidate.getUrl())) {
            return List.of(candidate.getUrl());
        }
        return List.of();
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return "OPEN_WEB";
        }
        return sourceType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeDomain(String domain) {
        if (!StringUtils.hasText(domain)) {
            return null;
        }
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    private String extractDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return normalizeDomain(uri.getHost());
        } catch (Exception ignored) {
            return null;
        }
    }
}
