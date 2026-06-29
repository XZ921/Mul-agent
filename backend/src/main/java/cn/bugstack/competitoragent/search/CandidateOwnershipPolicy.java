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

/**
 * 候选来源归属策略。
 * 这里专门负责判断搜索命中的页面到底是不是“官方公开证据”，
 * 避免把搜索认证页、企业信息页、百科页或登录工具页误当成正式来源。
 */
@Component
public class CandidateOwnershipPolicy {

    private static final List<String> MEDIATOR_DOMAINS = List.of(
            "aiqicha.baidu.com",
            "baike.baidu.com",
            "baike.sogou.com",
            "qcc.com",
            "tianyancha.com",
            "qixin.com"
    );

    private static final List<String> MEDIATOR_PATH_KEYWORDS = List.of(
            "/feedback/official",
            "/official",
            "/certification",
            "/verified",
            "/brandprotect",
            "/siteverify",
            "/firm",
            "/company",
            "/detail",
            "/item"
    );

    private static final List<String> MEDIATOR_TEXT_KEYWORDS = List.of(
            "官网认证",
            "官方标识",
            "增值服务认证",
            "网站认证",
            "企业认证",
            "爱企查",
            "百度认证",
            "企业信息",
            "工商信息",
            "股东信息",
            "风险信息",
            "企查查",
            "天眼查"
    );

    private static final List<String> UTILITY_GATE_PATH_KEYWORDS = List.of(
            "/login",
            "/signin",
            "/sign-in",
            "/passport",
            "/account",
            "/captcha",
            "/challenge",
            "/verify"
    );

    private static final List<String> UTILITY_GATE_TEXT_KEYWORDS = List.of(
            "login",
            "signin",
            "sign in",
            "verify you are human",
            "captcha",
            "security check",
            "登录",
            "验证码",
            "安全验证",
            "请先登录"
    );

    /**
     * 搜索认证页、企业信息页和百科页只能作为诊断线索保留，
     * 不能直接进入正式采集或正式证据。
     */
    public boolean isRejectedMediator(SourceCandidate candidate, SourceCollector.CollectedPage page) {
        String url = firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl());
        String domain = normalizeDomain(firstText(candidate == null ? null : candidate.getDomain(), extractDomain(url)));
        String path = normalizePath(url);
        String text = candidateAndPageText(candidate, page);
        boolean mediatorDomain = containsAnyDomain(domain, MEDIATOR_DOMAINS);
        boolean mediatorPath = containsAny(path, MEDIATOR_PATH_KEYWORDS);
        boolean mediatorText = containsAny(text, MEDIATOR_TEXT_KEYWORDS);
        return mediatorDomain && (mediatorPath || mediatorText);
    }

    /**
     * 登录页、验证码页和人机校验页只允许作为公开壳恢复输入，
     * 不能直接被提升成正式证据页面。
     */
    public boolean isUtilityGatePage(SourceCandidate candidate, SourceCollector.CollectedPage page) {
        String url = firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl());
        String path = normalizePath(url);
        String text = candidateAndPageText(candidate, page);
        return containsAny(path, UTILITY_GATE_PATH_KEYWORDS)
                || containsAny(text, UTILITY_GATE_TEXT_KEYWORDS);
    }

    public boolean isTrustedSearchRoot(String competitorName, SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return false;
        }
        if (isRejectedMediator(candidate, null) || isUtilityGatePage(candidate, null)) {
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
     * 归属校验只对“搜索发现的官网候选”强制开启，
     * 规划期直达候选与文档/定价等页面仍允许继续走后续验证流程。
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
        String text = candidateAndPageText(candidate, page);
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
        // 中文品牌经常使用英文主域名，这里先覆盖当前 live 样本里已经出现的高频别名。
        if ("哔哩哔哩".equals(compactName)) {
            aliases.add("bilibili");
            aliases.add("b站");
        }
        if ("抖音".equals(compactName)) {
            aliases.add("douyin");
        }
        return new ArrayList<>(aliases);
    }

    private String candidateAndPageText(SourceCandidate candidate, SourceCollector.CollectedPage page) {
        return compact(candidateText(candidate) + "\n" + pageText(page));
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
        String normalizedText = compact(text);
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && normalizedText.contains(compact(keyword))) {
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
