package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Tavily 查询 profile 解析器。
 * 这里把“官方锚点优先、证据不足时受控扩展、证据修复按缺口定向查询”的策略集中收口，
 * 避免后续 provider、orchestration、补证据流程各自拼装 query 导致语义漂移。
 */
@Component
public class TavilySearchProfileResolver {

    private static final double OFFICIAL_DOMAIN_CONFIDENCE_THRESHOLD = 0.60D;

    private final TavilySearchProperties properties;
    private final SearchPolicyResolver searchPolicyResolver;

    @Autowired
    public TavilySearchProfileResolver(TavilySearchProperties properties,
                                       SearchPolicyResolver searchPolicyResolver) {
        this.properties = properties == null ? new TavilySearchProperties() : properties;
        this.searchPolicyResolver = searchPolicyResolver == null ? new SearchPolicyResolver() : searchPolicyResolver;
    }

    public TavilySearchProfileResolver(TavilySearchProperties properties) {
        this(properties, new SearchPolicyResolver());
    }

    /**
     * 解析常规查询 profile。
     * 规则分三层：
     * 1. 只要存在明确的 suggested query，就优先视为证据修复动作，不再走静态模板。
     * 2. 是否搜索优先由 Source Family Catalog 决定；sourceType 只描述证据类型，不再决定检索范围。
     * 3. 非搜索优先的官方类 family 才走严格官方锚点，并带上可信域名。
     * 4. NEWS / REVIEW / RESEARCH 走开放网络模式，不加 include_domains，保留发散性。
     */
    public TavilySearchProfile resolve(String competitorName,
                                       String family,
                                       DomainHintSet domainHintSet,
                                       List<String> suggestedQueries) {
        String normalizedFamily = normalizeFamily(family);
        String repairQuery = firstNonBlank(suggestedQueries);
        if (StringUtils.hasText(repairQuery)) {
            return TavilySearchProfile.builder()
                    .family(normalizedFamily)
                    .queryMode(TavilyQueryMode.EVIDENCE_REPAIR)
                    .query(repairQuery)
                    .includeDomains(List.of())
                    .officialDomains(resolveHighConfidenceDomains(domainHintSet))
                    .searchDepth(properties.getSearchDepth())
                    .includeRawContent(properties.isIncludeRawContent())
                    .maxResults(properties.getMaxResults())
                    .build();
        }

        if (isOfficialEvidenceFamily(normalizedFamily)) {
            if (searchPolicyResolver.isSearchFirstSourceFamilyForSourceType(normalizedFamily)) {
                return resolveTrustedExpansion(
                        competitorName,
                        normalizedFamily,
                        domainHintSet,
                        "searchFirstPrimary=true"
                );
            }
            return resolveOfficialDocsAnchor(competitorName, normalizedFamily, domainHintSet);
        }

        return TavilySearchProfile.builder()
                .family(normalizedFamily)
                .queryMode(TavilyQueryMode.OPEN_WEB)
                .query(buildQuery(competitorName, normalizedFamily, TavilyQueryMode.OPEN_WEB))
                .includeDomains(List.of())
                .officialDomains(List.of())
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .build();
    }

    /**
     * 为字段级证据查询构造 Tavily profile。
     * 这里不再依赖 competitorName + family 模板，而是直接尊重 FieldEvidenceQuery 已经规划好的 query 文本与 includeDomains。
     */
    public TavilySearchProfile resolveFieldEvidence(FieldEvidenceQuery query) {
        if (query == null || !StringUtils.hasText(query.getQuery())) {
            return TavilySearchProfile.builder()
                    .family("OPEN_WEB")
                    .queryMode(TavilyQueryMode.OPEN_WEB)
                    .query("")
                    .includeDomains(List.of())
                    .officialDomains(List.of())
                    .searchDepth("basic")
                    .includeRawContent(false)
                    .maxResults(properties.getMaxResults())
                    .profileStage("FIELD_EVIDENCE_DISCOVERY")
                    .build();
        }
        TavilyQueryMode queryMode = resolveFieldEvidenceMode(query);
        /*
         * field evidence 的第一阶段只负责低成本候选发现。
         * 这里显式固定为 basic/no raw，避免 planned query 一多就把整批 Tavily 请求放大成 advanced+raw。
         */
        return TavilySearchProfile.builder()
                .family(normalizeFamily(query.getSourceType()))
                .queryMode(queryMode)
                .query(query.getQuery())
                .includeDomains(resolveFieldEvidenceIncludeDomains(query, queryMode))
                .officialDomains(resolveFieldEvidenceOfficialDomains(query, queryMode))
                .searchDepth("basic")
                .includeRawContent(false)
                .maxResults(properties.getMaxResults())
                .profileStage("FIELD_EVIDENCE_DISCOVERY")
                .fieldName(query.getFieldName())
                .evidencePathKey(query.getEvidencePathKey())
                .queryIntent(query.getQueryIntent())
                .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
                .fieldEvidenceQueryReason(query.getReason())
                .build();
    }

    /**
     * winner raw fetch 只允许围绕胜出 URL 回拉正文，不能重新退化成全网散搜。
     * 因此这里会优先抽取 winner host，拼出 site:host 查询，并把 includeDomains 锁到该 host。
     */
    public TavilySearchProfile resolveFieldEvidenceWinnerRawFetch(FieldEvidenceQuery query, String winnerUrl) {
        String winnerHost = resolveWinnerHost(winnerUrl);
        String originalQuery = StringUtils.hasText(query == null ? null : query.getQuery())
                ? query.getQuery().trim()
                : "";
        String winnerQuery = StringUtils.hasText(winnerHost)
                ? "site:" + winnerHost + " " + originalQuery
                : originalQuery;
        return TavilySearchProfile.builder()
                .family(normalizeFamily(query == null ? null : query.getSourceType()))
                .queryMode(TavilyQueryMode.TRUSTED_WEB_EXPANSION)
                .query(winnerQuery)
                .includeDomains(StringUtils.hasText(winnerHost) ? List.of(winnerHost) : List.of())
                .officialDomains(resolveFieldEvidenceOfficialDomains(query, TavilyQueryMode.TRUSTED_WEB_EXPANSION))
                .searchDepth("advanced")
                .includeRawContent(true)
                .maxResults(1)
                .profileStage("FIELD_EVIDENCE_WINNER_RAW_FETCH")
                .fieldName(query == null ? null : query.getFieldName())
                .evidencePathKey(query == null ? null : query.getEvidencePathKey())
                .queryIntent(query == null ? null : query.getQueryIntent())
                .fieldEvidenceQueryFingerprint(query == null ? null : query.getQueryFingerprint())
                .fieldEvidenceQueryReason(query == null ? null : query.getReason())
                .build();
    }

    /**
     * 字段级 query 的查询模式按 sourceType 收口。
     * 官方/文档/定价类字段证据必须先走官方锚点，是否继续扩展到开放网络交给 provider 的 shouldExpand 再判定。
     */
    private TavilyQueryMode resolveFieldEvidenceMode(FieldEvidenceQuery query) {
        String sourceType = query == null ? null : query.getSourceType();
        if ("OFFICIAL".equalsIgnoreCase(sourceType)
                || "DOCS".equalsIgnoreCase(sourceType)
                || "PRICING".equalsIgnoreCase(sourceType)) {
            // 字段级官方证据先验证官方结果，避免默认把每条 query 都扩散到开放网。
            return TavilyQueryMode.OFFICIAL_DOCS;
        }
        return TavilyQueryMode.OPEN_WEB;
    }

    private List<String> resolveFieldEvidenceIncludeDomains(FieldEvidenceQuery query, TavilyQueryMode queryMode) {
        // TRUSTED_WEB_EXPANSION / OPEN_WEB 必须解除 include_domains，否则 Tavily API 仍被官方域名收窄。
        if (queryMode == TavilyQueryMode.TRUSTED_WEB_EXPANSION || queryMode == TavilyQueryMode.OPEN_WEB) {
            return List.of();
        }
        return query == null || query.getIncludeDomains() == null ? List.of() : query.getIncludeDomains();
    }

    private List<String> resolveFieldEvidenceOfficialDomains(FieldEvidenceQuery query, TavilyQueryMode queryMode) {
        /*
         * includeDomains 是 Tavily API 的范围约束；officialDomains 是 Gate 的质量判断提示。
         * search-first 字段 query 会清空 includeDomains，但不能丢掉官方域名提示，否则官方短文会被当成普通薄内容。
         */
        if (queryMode != TavilyQueryMode.TRUSTED_WEB_EXPANSION
                && queryMode != TavilyQueryMode.OFFICIAL_DOCS
                && queryMode != TavilyQueryMode.EVIDENCE_REPAIR) {
            return List.of();
        }
        return query == null || query.getIncludeDomains() == null ? List.of() : query.getIncludeDomains();
    }

    /**
     * 解析“官方锚点不足时的受控扩展” profile。
     * 这里显式不带 include_domains，避免扩展轮仍被单一官方域名限制，失去补广度的意义。
     */
    public TavilySearchProfile resolveTrustedExpansion(String competitorName,
                                                       String family,
                                                       DomainHintSet domainHintSet,
                                                       String expansionReason) {
        String normalizedFamily = normalizeFamily(family);
        return TavilySearchProfile.builder()
                .family(normalizedFamily)
                .queryMode(TavilyQueryMode.TRUSTED_WEB_EXPANSION)
                .query(buildQuery(competitorName, normalizedFamily, TavilyQueryMode.TRUSTED_WEB_EXPANSION))
                .includeDomains(List.of())
                .officialDomains(resolveHighConfidenceDomains(domainHintSet))
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .expansionReason(expansionReason)
                .build();
    }

    /**
     * 显式构造严格官方锚点 profile。
     * 该模式只服务于调用方明确要求 OFFICIAL_DOCS 的路径，不能再作为官方类 sourceType 的默认主搜索策略。
     */
    public TavilySearchProfile resolveOfficialDocsAnchor(String competitorName,
                                                         String family,
                                                         DomainHintSet domainHintSet) {
        String normalizedFamily = normalizeFamily(family);
        List<String> officialDomains = resolveHighConfidenceDomains(domainHintSet);
        return TavilySearchProfile.builder()
                .family(normalizedFamily)
                .queryMode(TavilyQueryMode.OFFICIAL_DOCS)
                .query(buildQuery(competitorName, normalizedFamily, TavilyQueryMode.OFFICIAL_DOCS))
                .includeDomains(officialDomains)
                .officialDomains(officialDomains)
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .build();
    }

    /**
     * 判断当前 family 是否描述官方类证据。
     * 这只回答“要找什么证据”，不回答“是否只能搜官方域名”；检索范围必须交给 family 策略路由决定。
     */
    private boolean isOfficialEvidenceFamily(String family) {
        return "OFFICIAL".equals(family) || "DOCS".equals(family) || "PRICING".equals(family);
    }

    /**
     * 从 DomainHintSet 中提取可用于 include_domains 的高置信域名。
     * 这里故意做了三层过滤：
     * 1. 域名不能为空；
     * 2. 置信度必须达到阈值；
     * 3. 结果去重并保留原始顺序；
     * 这样可以避免把低质量推测域名带进官方锚点搜索，影响命中精度。
     */
    private List<String> resolveHighConfidenceDomains(DomainHintSet domainHintSet) {
        if (domainHintSet == null || domainHintSet.getDomains() == null || domainHintSet.getDomains().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (DomainHint hint : domainHintSet.getDomains()) {
            if (hint == null) {
                continue;
            }
            if (!StringUtils.hasText(hint.getDomain())) {
                continue;
            }
            if (hint.getConfidence() < OFFICIAL_DOMAIN_CONFIDENCE_THRESHOLD) {
                continue;
            }
            domains.add(hint.getDomain().trim());
        }
        return new ArrayList<>(domains);
    }

    /**
     * 基于 family 和 query mode 生成最小查询模板。
     * 模板保持克制：只表达检索意图，不在这里提前混入质量判断逻辑，质量门禁后续由 Gate 负责。
     */
    private String buildQuery(String competitorName, String family, TavilyQueryMode queryMode) {
        String normalizedCompetitor = StringUtils.hasText(competitorName) ? competitorName.trim() : "";
        if (queryMode == TavilyQueryMode.TRUSTED_WEB_EXPANSION) {
            return normalizedCompetitor + " " + resolveExpansionKeywords(family);
        }
        if ("OFFICIAL".equals(family)) {
            return normalizedCompetitor + " 官网 官方 规则 协议 帮助中心";
        }
        if ("DOCS".equals(family)) {
            return normalizedCompetitor + " 开放平台 API 官方文档 开发者文档";
        }
        if ("PRICING".equals(family)) {
            return normalizedCompetitor + " 定价 套餐 收费 官方";
        }
        if ("NEWS".equals(family)) {
            return normalizedCompetitor + " 最新动态 新闻 公告";
        }
        if ("REVIEW".equals(family)) {
            return normalizedCompetitor + " 产品评测 用户评价 行业观点";
        }
        if ("RESEARCH".equals(family)) {
            return normalizedCompetitor + " 研究报告 深度分析 行业报告";
        }
        return normalizedCompetitor + " 产品信息 官方资料";
    }

    private String resolveExpansionKeywords(String family) {
        if ("DOCS".equals(family)) {
            return "开放平台 API 文档 技术解读 使用说明";
        }
        if ("PRICING".equals(family)) {
            return "定价 套餐 收费 解读 商业化";
        }
        if ("OFFICIAL".equals(family)) {
            return "官网 规则 协议 帮助中心 解读";
        }
        return "可信资料 技术文章 行业解读";
    }

    private String normalizeFamily(String family) {
        if (!StringUtils.hasText(family)) {
            return "OPEN_WEB";
        }
        return family.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveWinnerHost(String winnerUrl) {
        if (!StringUtils.hasText(winnerUrl)) {
            return null;
        }
        try {
            URI uri = URI.create(winnerUrl.trim());
            return uri.getHost() == null ? null : uri.getHost().trim();
        } catch (Exception ignored) {
            return null;
        }
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
}
