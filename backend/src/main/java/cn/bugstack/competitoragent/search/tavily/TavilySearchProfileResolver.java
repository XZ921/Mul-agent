package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    public TavilySearchProfileResolver(TavilySearchProperties properties) {
        this.properties = properties == null ? new TavilySearchProperties() : properties;
    }

    /**
     * 解析常规查询 profile。
     * 规则分三层：
     * 1. 只要存在明确的 suggested query，就优先视为证据修复动作，不再走静态模板。
     * 2. OFFICIAL / DOCS / PRICING 先走官方锚点模式，并带上可信域名。
     * 3. NEWS / REVIEW / RESEARCH 走开放网络模式，不加 include_domains，保留发散性。
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
                    .searchDepth(properties.getSearchDepth())
                    .includeRawContent(properties.isIncludeRawContent())
                    .maxResults(properties.getMaxResults())
                    .build();
        }

        if (requiresOfficialAnchor(normalizedFamily)) {
            return TavilySearchProfile.builder()
                    .family(normalizedFamily)
                    .queryMode(TavilyQueryMode.OFFICIAL_DOCS)
                    .query(buildQuery(competitorName, normalizedFamily, TavilyQueryMode.OFFICIAL_DOCS))
                    .includeDomains(resolveHighConfidenceDomains(domainHintSet))
                    .searchDepth(properties.getSearchDepth())
                    .includeRawContent(properties.isIncludeRawContent())
                    .maxResults(properties.getMaxResults())
                    .build();
        }

        return TavilySearchProfile.builder()
                .family(normalizedFamily)
                .queryMode(TavilyQueryMode.OPEN_WEB)
                .query(buildQuery(competitorName, normalizedFamily, TavilyQueryMode.OPEN_WEB))
                .includeDomains(List.of())
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
                    .searchDepth(properties.getSearchDepth())
                    .includeRawContent(properties.isIncludeRawContent())
                    .maxResults(properties.getMaxResults())
                    .build();
        }
        return TavilySearchProfile.builder()
                .family(normalizeFamily(query.getSourceType()))
                .queryMode(resolveFieldEvidenceMode(query))
                .query(query.getQuery())
                .includeDomains(query.getIncludeDomains() == null ? List.of() : query.getIncludeDomains())
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .fieldName(query.getFieldName())
                .evidencePathKey(query.getEvidencePathKey())
                .queryIntent(query.getQueryIntent())
                .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
                .fieldEvidenceQueryReason(query.getReason())
                .build();
    }

    /**
     * 字段级 query 的查询模式按 sourceType 收口。
     * 官方/文档/定价类路径仍优先走 OFFICIAL_DOCS，其余路径再退回 OPEN_WEB。
     */
    private TavilyQueryMode resolveFieldEvidenceMode(FieldEvidenceQuery query) {
        String sourceType = query == null ? null : query.getSourceType();
        if ("OFFICIAL".equalsIgnoreCase(sourceType)
                || "DOCS".equalsIgnoreCase(sourceType)
                || "PRICING".equalsIgnoreCase(sourceType)) {
            return TavilyQueryMode.OFFICIAL_DOCS;
        }
        return TavilyQueryMode.OPEN_WEB;
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
                .searchDepth(properties.getSearchDepth())
                .includeRawContent(properties.isIncludeRawContent())
                .maxResults(properties.getMaxResults())
                .expansionReason(expansionReason)
                .build();
    }

    /**
     * 判断当前 family 是否必须保留官方锚点。
     * OFFICIAL / DOCS / PRICING 属于强事实或官方资料场景，首轮必须优先命中官方来源。
     */
    private boolean requiresOfficialAnchor(String family) {
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