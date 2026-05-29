package cn.bugstack.competitoragent.source;

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
 * 启发式信息源发现服务。
 * 当前版本不依赖外部搜索 API，而是根据竞品名称、用户输入 URL、采集范围规则拼装候选来源。
 */
@Component
public class HeuristicSourceDiscoveryService implements SourceDiscoveryService {

    private static final List<String> DEFAULT_SCOPES = List.of("OFFICIAL", "DOCS", "PRICING");

    @Override
    public List<SourcePlan> discover(String competitorName, List<String> providedUrls, List<String> requestedScopes) {
        // 先确定根域名，再基于 scope 派生文档、价格、新闻、评测等不同入口。
        List<String> normalizedRoots = normalizeRoots(competitorName, providedUrls);
        List<String> scopes = normalizeScopes(requestedScopes);
        List<SourcePlan> plans = new ArrayList<>();

        for (String scope : scopes) {
            List<String> urls = discoverUrlsByScope(scope, normalizedRoots, competitorName);
            if (urls.isEmpty()) {
                continue;
            }
            plans.add(SourcePlan.builder()
                    .sourceType(scope)
                    .urls(urls)
                    // notes 会显示在任务节点详情中，帮助用户理解系统为何选择这些入口。
                    .notes(buildNotes(scope, urls.size(), providedUrls))
                    .build());
        }

        // 如果 scope 没有扩展出额外路径，至少保留根域名作为兜底采集入口。
        if (plans.isEmpty() && !normalizedRoots.isEmpty()) {
            plans.add(SourcePlan.builder()
                    .sourceType("OFFICIAL")
                    .urls(normalizedRoots)
                    .notes("Fallback to root-domain collection")
                    .build());
        }

        return plans;
    }

    // 优先使用用户提供的 URL；如果没有，则尝试由竞品名称推导常见域名。
    private List<String> normalizeRoots(String competitorName, List<String> providedUrls) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (providedUrls != null) {
            for (String url : providedUrls) {
                String normalized = toRootUrl(url);
                if (StringUtils.hasText(normalized)) {
                    roots.add(normalized);
                }
            }
        }

        if (!roots.isEmpty()) {
            return new ArrayList<>(roots);
        }

        String slug = toDomainSlug(competitorName);
        if (!StringUtils.hasText(slug)) {
            return List.of();
        }

        roots.add("https://" + slug + ".com");
        roots.add("https://www." + slug + ".com");
        roots.add("https://" + slug + ".ai");
        roots.add("https://" + slug + ".io");
        return new ArrayList<>(roots);
    }

    // 采集范围支持中英文别名输入，最终统一映射成系统内部固定 scope。
    private List<String> normalizeScopes(List<String> requestedScopes) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            return DEFAULT_SCOPES;
        }

        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        for (String rawScope : requestedScopes) {
            String normalized = canonicalScope(rawScope);
            if (StringUtils.hasText(normalized)) {
                scopes.add(normalized);
            }
        }
        return scopes.isEmpty() ? DEFAULT_SCOPES : new ArrayList<>(scopes);
    }

    // 每种 scope 都对应一组固定的候选入口规则，方便后续继续扩展更多来源类型。
    private List<String> discoverUrlsByScope(String scope, List<String> roots, String competitorName) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String root : roots) {
            switch (scope) {
                case "OFFICIAL" -> urls.add(root);
                case "DOCS" -> addPaths(urls, root, List.of("/docs", "/documentation", "/help", "/guide"));
                case "PRICING" -> addPaths(urls, root, List.of("/pricing", "/plans", "/enterprise"));
                case "NEWS" -> addPaths(urls, root, List.of("/blog", "/news", "/changelog", "/resources"));
                case "REVIEW" -> addReviewSources(urls, root, competitorName);
                default -> urls.add(root);
            }
        }
        return new ArrayList<>(urls);
    }

    private void addPaths(Set<String> urls, String root, List<String> paths) {
        for (String path : paths) {
            urls.add(root + path);
        }
    }

    /**
     * 评测来源除了站内案例页，也补充 G2 / Capterra 这类公开评价入口，
     * 避免信息源完全依赖官网首页导致视角单一。
     */
    private void addReviewSources(Set<String> urls, String root, String competitorName) {
        addPaths(urls, root, List.of("/customers", "/case-studies", "/compare"));

        String encodedName = competitorName == null ? "" : competitorName.trim().replace(" ", "+");
        if (!encodedName.isBlank()) {
            urls.add("https://www.g2.com/search?query=" + encodedName);
            urls.add("https://www.capterra.com/search/?query=" + encodedName);
        }
    }

    // notes 面向人类阅读，用于解释该批来源的业务含义和推导来源。
    private String buildNotes(String scope, int urlCount, List<String> providedUrls) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("OFFICIAL", "Official site");
        labels.put("DOCS", "Documentation");
        labels.put("PRICING", "Pricing");
        labels.put("NEWS", "Blog/News");
        labels.put("REVIEW", "Public reviews");

        String origin = providedUrls == null || providedUrls.isEmpty()
                ? "derived from heuristic root-domain inference"
                : "expanded from user-provided URLs";
        return labels.getOrDefault(scope, scope) + ": " + urlCount + " candidates, " + origin;
    }

    // 兼容中文关键词、英文关键词与常见站点名，降低前端传参复杂度。
    private String canonicalScope(String rawScope) {
        if (!StringUtils.hasText(rawScope)) {
            return null;
        }
        String scope = rawScope.toLowerCase(Locale.ROOT);
        if (containsAny(scope, List.of("官网", "official", "home"))) {
            return "OFFICIAL";
        }
        if (containsAny(scope, List.of("文档", "doc", "help", "guide"))) {
            return "DOCS";
        }
        if (containsAny(scope, List.of("价格", "定价", "pricing", "plan"))) {
            return "PRICING";
        }
        if (containsAny(scope, List.of("博客", "新闻", "blog", "news", "changelog"))) {
            return "NEWS";
        }
        if (containsAny(scope, List.of("测评", "review", "g2", "capterra"))) {
            return "REVIEW";
        }
        return "OFFICIAL";
    }

    private boolean containsAny(String value, List<String> candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    // 统一裁剪到协议 + host，避免后续拼接 path 时重复叠加原始路径。
    private String toRootUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }

        String withProtocol = rawUrl.startsWith("http://") || rawUrl.startsWith("https://")
                ? rawUrl
                : "https://" + rawUrl;
        try {
            URI uri = URI.create(withProtocol);
            if (!StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignore) {
            return null;
        }
    }

    // 例如 "Notion AI" 会被推导成 "notionai"，作为候选域名 slug。
    private String toDomainSlug(String competitorName) {
        if (!StringUtils.hasText(competitorName)) {
            return "";
        }
        return competitorName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "")
                .trim();
    }
}
