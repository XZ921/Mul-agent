package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Source Family 直达候选规划器。
 * 负责把显式官网 URL、根域、repo URL 或稳定 locator 翻译成 direct candidates，
 * 让 preview 与 runtime 共享同一套 family-first discovery 解释。
 */
@Component
public class SourceFamilyDirectDiscoveryPlanner {

    private final SearchPolicyResolver searchPolicyResolver;

    public SourceFamilyDirectDiscoveryPlanner(SearchPolicyResolver searchPolicyResolver) {
        this.searchPolicyResolver = searchPolicyResolver == null ? new SearchPolicyResolver() : searchPolicyResolver;
    }

    /**
     * 根据 source family 生成首批 direct candidates。
     * 这里输出的是家族级候选池，后续进入具体 node 时需要调用方再按 sourceType 正式过滤。
     */
    public List<SourceCandidate> buildInitialCandidates(String competitorName,
                                                        String sourceType,
                                                        List<String> providedUrls) {
        String familyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(sourceType);
        return switch (familyKey) {
            case "official" -> buildOfficialCandidates(competitorName, sourceType, providedUrls);
            case "github" -> buildGithubCandidates(competitorName, sourceType, providedUrls);
            default -> List.of();
        };
    }

    /**
     * official 家族区分根域与深路径两种输入：
     * 1. 显式根域：保留根域 DIRECT_LOCATOR，并从根域展开 FAMILY_TEMPLATE。
     * 2. 显式深路径：保留原始深路径 DIRECT_LOCATOR，但模板展开严格只基于提取后的根域。
     */
    private List<SourceCandidate> buildOfficialCandidates(String competitorName,
                                                          String requestedSourceType,
                                                          List<String> providedUrls) {
        Map<String, SourceCandidate> candidates = new LinkedHashMap<>();
        for (String rawUrl : safeList(providedUrls)) {
            String normalizedUrl = normalizeHttpUrl(rawUrl);
            if (!searchPolicyResolver.isStableLocatorForSourceFamily("official", normalizedUrl)) {
                continue;
            }
            String rootUrl = toRootUrl(normalizedUrl);
            String rootDomain = extractRootDomain(normalizedUrl);
            if (!StringUtils.hasText(rootUrl) || !StringUtils.hasText(rootDomain)) {
                continue;
            }

            putCandidate(candidates, buildCandidate(
                    normalizedUrl,
                    resolveExplicitOfficialSourceType(normalizedUrl, requestedSourceType),
                    competitorName,
                    "official",
                    "DIRECT_LOCATOR",
                    "explicit official direct candidate",
                    List.of(normalizedUrl)
            ));

            for (String template : searchPolicyResolver.resolveDirectPathTemplates("official")) {
                String expandedUrl = expandOfficialTemplate(rootUrl, template);
                if (!StringUtils.hasText(expandedUrl) || expandedUrl.equals(normalizedUrl)) {
                    continue;
                }
                putCandidate(candidates, buildCandidate(
                        expandedUrl,
                        resolveTemplateSourceType(template),
                        competitorName,
                        "official",
                        "FAMILY_TEMPLATE",
                        "official family template candidate",
                        List.of(expandedUrl)
                ));
            }

            for (String template : searchPolicyResolver.resolveDirectSubdomainTemplates("official")) {
                String expandedUrl = expandOfficialSubdomainTemplate(rootDomain, template);
                if (!StringUtils.hasText(expandedUrl) || expandedUrl.equals(normalizedUrl)) {
                    continue;
                }
                putCandidate(candidates, buildCandidate(
                        expandedUrl,
                        resolveTemplateSourceType(template),
                        competitorName,
                        "official",
                        "FAMILY_SUBDOMAIN_TEMPLATE",
                        "official family subdomain template candidate",
                        List.of(expandedUrl)
                ));
            }
        }
        return new ArrayList<>(candidates.values());
    }

    /**
     * github 家族的完整 repo URL 本身就是稳定 locator。
     * 这里不做“先裁成 github.com 再展开”的逆向推断，避免丢失 owner/repo 语义。
     */
    private List<SourceCandidate> buildGithubCandidates(String competitorName,
                                                        String requestedSourceType,
                                                        List<String> providedUrls) {
        Map<String, SourceCandidate> candidates = new LinkedHashMap<>();
        for (String rawUrl : safeList(providedUrls)) {
            String normalizedUrl = normalizeGithubLocator(rawUrl);
            if (!searchPolicyResolver.isStableLocatorForSourceFamily("github", normalizedUrl)) {
                continue;
            }
            putCandidate(candidates, buildCandidate(
                    normalizedUrl,
                    StringUtils.hasText(requestedSourceType) ? requestedSourceType.trim().toUpperCase(Locale.ROOT) : "GITHUB",
                    competitorName,
                    "github",
                    "DIRECT_LOCATOR",
                    "explicit github repository direct candidate",
                    List.of(normalizedUrl)
            ));
        }
        return new ArrayList<>(candidates.values());
    }

    /**
     * official 模板只允许基于根域展开。
     * 即使 providedUrl 带深路径，也必须先提取根域，再拼接 /pricing、/docs 等稳定路径。
     */
    private String expandOfficialTemplate(String rootUrl, String template) {
        if (!StringUtils.hasText(rootUrl) || !StringUtils.hasText(template)) {
            return null;
        }
        String normalizedTemplate = template.trim();
        if ("/".equals(normalizedTemplate)) {
            return rootUrl;
        }
        return rootUrl + (normalizedTemplate.startsWith("/") ? normalizedTemplate : "/" + normalizedTemplate);
    }

    /**
     * official 子域模板只允许基于根域展开。
     * 即使 providedUrl 带深路径，也必须先提取根域，再拼接 docs/open/developer/help 等稳定子域。
     */
    private String expandOfficialSubdomainTemplate(String rootDomain, String template) {
        if (!StringUtils.hasText(rootDomain) || !StringUtils.hasText(template)) {
            return null;
        }
        String host = template.trim().replace("{domain}", rootDomain);
        if (!StringUtils.hasText(host)) {
            return null;
        }
        return "https://" + host;
    }

    /**
     * 显式官方 URL 如果命中深路径，仍要保留原始路径作为 direct locator。
     * 同时优先按显式路径推断 sourceType，推断不出来时再回退到当前请求的 sourceType。
     */
    private String resolveExplicitOfficialSourceType(String normalizedUrl, String requestedSourceType) {
        String inferred = inferSourceTypeFromPath(normalizedUrl);
        if (StringUtils.hasText(inferred)) {
            return inferred;
        }
        return StringUtils.hasText(requestedSourceType)
                ? requestedSourceType.trim().toUpperCase(Locale.ROOT)
                : "OFFICIAL";
    }

    /**
     * family template 的 sourceType 由模板路径本身决定。
     * 根路径归入 OFFICIAL，其余 docs/pricing 模板分别映射到对应 sourceType。
     */
    private String resolveTemplateSourceType(String template) {
        if (!StringUtils.hasText(template) || "/".equals(template.trim())) {
            return "OFFICIAL";
        }
        return inferSourceTypeFromPath(template);
    }

    /**
     * 这里统一把路径语义映射成 sourceType，避免 preview 与 runtime 各写一份 /docs、/pricing 推断规则。
     */
    private String inferSourceTypeFromPath(String rawPath) {
        String normalizedPath = rawPath == null ? "" : rawPath.toLowerCase(Locale.ROOT);
        if (normalizedPath.contains("/pricing") || normalizedPath.contains("/plans")) {
            return "PRICING";
        }
        // 中文互联网常把开放平台和开发者文档放在 open/developer 子域，
        // 不能只依赖 /docs 路径判断，否则会把 open.douyin.com 误归类成泛官网。
        if (normalizedPath.contains("/docs")
                || normalizedPath.contains("/documentation")
                || normalizedPath.contains("/help")
                || normalizedPath.contains("/guide")
                || normalizedPath.contains("/api")
                || normalizedPath.contains("/reference")
                || normalizedPath.startsWith("docs.")
                || normalizedPath.startsWith("developer.")
                || normalizedPath.startsWith("open.")
                || normalizedPath.startsWith("help.")) {
            return "DOCS";
        }
        return "OFFICIAL";
    }

    /**
     * planner 只负责翻译 direct candidate 语义，不承担验证与最终选源职责。
     * 这里统一补齐 source family 字段与 sourceUrls，保证后续审计链路可追溯。
     */
    private SourceCandidate buildCandidate(String url,
                                           String sourceType,
                                           String competitorName,
                                           String familyKey,
                                           String discoveryMethod,
                                           String reason,
                                           List<String> sourceUrls) {
        String familyRole = searchPolicyResolver.resolveSourceFamilyRole(familyKey).name();
        return SourceCandidate.builder()
                .url(url)
                .title(buildTitle(competitorName, sourceType, url))
                .sourceType(sourceType)
                .discoveryMethod(discoveryMethod)
                .reason(reason)
                .domain(extractHost(url))
                .sourceFamilyKey(familyKey)
                .sourceFamilyRole(familyRole)
                .sourceUrls(sourceUrls)
                .qualitySignals(buildQualitySignals(url, sourceType))
                .relevanceScore(0.95D)
                .freshnessScore(0.60D)
                .qualityScore(0.92D)
                .build();
    }

    private List<String> buildQualitySignals(String url, String sourceType) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if ("DOCS".equalsIgnoreCase(sourceType)
                && (normalizedUrl.contains("://open.") || normalizedUrl.contains("://developer."))) {
            // 开放平台/开发者平台通常直接承载 API、SDK、接入文档，是 DOCS 场景的高价值入口。
            signals.add("OPEN_PLATFORM_DOCS_ENTRY");
        }
        return new ArrayList<>(signals);
    }

    private void putCandidate(Map<String, SourceCandidate> candidates, SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return;
        }
        candidates.putIfAbsent(candidate.getUrl(), candidate);
    }

    private String normalizeHttpUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String withScheme = rawUrl.startsWith("http://") || rawUrl.startsWith("https://")
                ? rawUrl.trim()
                : "https://" + rawUrl.trim();
        try {
            URI uri = URI.create(withScheme);
            if (!StringUtils.hasText(uri.getHost())) {
                return null;
            }
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath().replaceAll("/+$", "");
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost() + path;
        } catch (Exception exception) {
            return null;
        }
    }

    private String normalizeGithubLocator(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("github://")) {
            return trimmed;
        }
        return normalizeHttpUrl(trimmed);
    }

    private String toRootUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost();
        } catch (Exception exception) {
            return null;
        }
    }

    private String extractRootDomain(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!StringUtils.hasText(uri.getHost())) {
                return null;
            }
            String host = uri.getHost().trim().toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception exception) {
            return null;
        }
    }

    private String buildTitle(String competitorName, String sourceType, String url) {
        String safeName = StringUtils.hasText(competitorName) ? competitorName.trim() : "competitor";
        return safeName + " - " + sourceType + " direct entry: " + url;
    }

    private String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
