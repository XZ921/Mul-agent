package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 候选来源归属策略。
 * 该策略专门处理“搜索命中的页面是否真的属于竞品”这一层判断，避免把搜索引擎认证页、
 * 企业信息页、广告中介页误当成竞品官网或文档入口。
 */
@Component
public class CandidateOwnershipPolicy {

    private static final List<String> MEDIATOR_DOMAINS = List.of(
            "aiqicha.baidu.com",
            "baike.baidu.com",
            "baike.sogou.com",
            "qcc.com",
            "tianyancha.com",
            "企查查",
            "天眼查"
    );
    private static final List<String> MEDIATOR_PATH_KEYWORDS = List.of(
            "/feedback/official",
            "/official",
            "/certification",
            "/verified",
            "/brandprotect",
            "/siteverify"
    );
    private static final List<String> MEDIATOR_TEXT_KEYWORDS = List.of(
            "官网认证",
            "官方标识",
            "增值服务认证",
            "网站认证",
            "企业认证",
            "爱企查",
            "百度认证"
    );

    public boolean isRejectedMediator(SourceCandidate candidate, SourceCollector.CollectedPage page) {
        String url = firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl());
        String domain = normalizeDomain(firstText(candidate == null ? null : candidate.getDomain(), extractDomain(url)));
        String path = normalizePath(url);
        String text = compact(candidateText(candidate) + "\n" + pageText(page));
        boolean mediatorDomain = containsAnyDomain(domain, MEDIATOR_DOMAINS);
        boolean mediatorPath = containsAny(path, MEDIATOR_PATH_KEYWORDS);
        boolean mediatorText = containsAny(text, MEDIATOR_TEXT_KEYWORDS);
        return mediatorDomain && (mediatorPath || mediatorText);
    }

    public boolean isTrustedSearchRoot(String competitorName, SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return false;
        }
        if (isRejectedMediator(candidate, null)) {
            return false;
        }
        if (Boolean.TRUE.equals(candidate.getVerified())) {
            return true;
        }
        if (shouldRequireOwnershipValidation(candidate, candidate.getSourceType())) {
            return hasCompetitorOwnershipSignal(competitorName, candidate, null);
        }
        return true;
    }

    /**
     * 竞品归属校验当前只用于搜索发现的官网候选。
     * 对显式规划的 URL 或 DOCS/PRICING 等内容型来源先不强制要求，
     * 避免把“域名没带品牌词但内容有效”的正常页面误杀。
     */
    public boolean shouldRequireOwnershipValidation(SourceCandidate candidate, String sourceType) {
        return "OFFICIAL".equalsIgnoreCase(sourceType) && isSearchDiscovered(candidate);
    }

    public boolean hasCompetitorOwnershipSignal(String competitorName,
                                                SourceCandidate candidate,
                                                SourceCollector.CollectedPage page) {
        List<String> aliases = buildAliases(competitorName);
        if (aliases.isEmpty()) {
            return true;
        }
        String domain = normalizeDomain(firstText(candidate == null ? null : candidate.getDomain(),
                extractDomain(firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl()))));
        String text = compact(candidateText(candidate) + "\n" + pageText(page));
        for (String alias : aliases) {
            String normalizedAlias = compact(alias);
            if (!StringUtils.hasText(normalizedAlias)) {
                continue;
            }
            if (domain.contains(normalizedAlias) || text.contains(normalizedAlias)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSearchDiscovered(SourceCandidate candidate) {
        String method = candidate.getDiscoveryMethod();
        String stage = candidate.getSelectionStage();
        String provider = candidate.getProviderKey();
        return equalsAny(method, "BROWSER", "SEARCH", "SEARCH_ROOT_TEMPLATE")
                || equalsAny(stage, "BROWSER", "SUPPLEMENTED", "BOOTSTRAPPED")
                || equalsAny(provider, "browser", "http");
    }

    private boolean equalsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildAliases(String competitorName) {
        if (!StringUtils.hasText(competitorName)) {
            return List.of();
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String compactName = compact(competitorName);
        if (StringUtils.hasText(compactName)) {
            aliases.add(compactName);
        }
        // 中文品牌常有英文主域，先覆盖本次冒烟暴露的高频别名，后续可迁移到配置化词典。
        if ("哔哩哔哩".equals(compactName)) {
            aliases.add("bilibili");
            aliases.add("b站");
        }
        if ("抖音".equals(compactName)) {
            aliases.add("douyin");
        }
        return new ArrayList<>(aliases);
    }

    private String candidateText(SourceCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        return firstText(candidate.getTitle(), "") + "\n" + firstText(candidate.getReason(), "");
    }

    private String pageText(SourceCollector.CollectedPage page) {
        if (page == null) {
            return "";
        }
        return firstText(page.getTitle(), "") + "\n"
                + firstText(page.getSnippet(), "") + "\n"
                + firstText(page.getContent(), "");
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private boolean containsAnyDomain(String domain, List<String> domains) {
        if (!StringUtils.hasText(domain)) {
            return false;
        }
        for (String blocked : domains) {
            String normalized = normalizeDomain(blocked);
            if (StringUtils.hasText(normalized)
                    && (domain.equals(normalized) || domain.endsWith("." + normalized) || domain.contains(normalized))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(compact(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", "");
    }

    private String normalizeDomain(String domain) {
        if (!StringUtils.hasText(domain)) {
            return "";
        }
        String normalized = domain.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
    }

    private String normalizePath(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            return URI.create(url).getPath().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return url.toLowerCase(Locale.ROOT);
        }
    }

    private String extractDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
