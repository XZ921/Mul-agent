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
 * V2 第二阶段开始，统一收口启发式与搜索式补源结果，并在节点配置中保留候选来源的排序与说明。
 */
@Component
public class HeuristicSourceDiscoveryService implements SourceDiscoveryService {

    private static final List<String> DEFAULT_SCOPES = List.of("OFFICIAL", "DOCS", "PRICING", "NEWS", "REVIEW");
    private static final int MAX_CANDIDATES_PER_SCOPE = 5;

    private final SearchSourceProvider searchSourceProvider;
    private final SourceCandidateRanker candidateRanker;

    public HeuristicSourceDiscoveryService(SearchSourceProvider searchSourceProvider,
                                           SourceCandidateRanker candidateRanker) {
        this.searchSourceProvider = searchSourceProvider;
        this.candidateRanker = candidateRanker;
    }

    @Override
    public List<SourcePlan> discover(String competitorName, List<String> providedUrls, List<String> requestedScopes) {
        // 先确定根域名，再把启发式与搜索式候选来源按 scope 合并、去重和排序。
        // 如果用户没有提供 URL，则不要盲猜 notionai.com 这类高误判域名，改为保留搜索驱动的占位计划。
        List<String> normalizedRoots = normalizeRoots(competitorName, providedUrls);
        List<String> scopes = normalizeScopes(requestedScopes);
        List<SourcePlan> plans = new ArrayList<>();
        List<SourceCandidate> searchCandidates = searchSourceProvider.search(competitorName, scopes);

        for (String scope : scopes) {
            List<SourceCandidate> mergedCandidates = mergeCandidates(
                    buildHeuristicCandidates(scope, normalizedRoots, competitorName, providedUrls),
                    filterSearchCandidates(scope, searchCandidates)
            );
            if (mergedCandidates.isEmpty()) {
                if (shouldCreateSearchOnlyPlan(normalizedRoots, scope, searchCandidates)) {
                    plans.add(SourcePlan.builder()
                            .sourceType(scope)
                            .urls(List.of())
                            .candidates(List.of())
                            .notes(buildSearchOnlyNotes(scope))
                            .build());
                }
                continue;
            }

            List<String> urls = mergedCandidates.stream()
                    .map(SourceCandidate::getUrl)
                    .toList();
            plans.add(SourcePlan.builder()
                    .sourceType(scope)
                    .urls(urls)
                    .candidates(mergedCandidates)
                    // notes 会显示在任务节点详情中，帮助用户理解系统为何选择这些入口。
                    .notes(buildNotes(scope, mergedCandidates, providedUrls))
                    .build());
        }

        // 如果 scope 没有扩展出额外路径，至少保留根域名作为兜底采集入口。
        if (plans.isEmpty() && !normalizedRoots.isEmpty()) {
            List<SourceCandidate> fallbackCandidates = buildHeuristicCandidates("OFFICIAL", normalizedRoots, competitorName, providedUrls);
            plans.add(SourcePlan.builder()
                    .sourceType("OFFICIAL")
                    .urls(fallbackCandidates.stream().map(SourceCandidate::getUrl).toList())
                    .candidates(fallbackCandidates)
                    .notes("Fallback to root-domain collection")
                    .build());
        }

        return plans;
    }

    // 优先使用用户提供的 URL；如果没有可靠 URL，则交给后续搜索补源处理，不再盲猜域名。
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
        return List.of();
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

    // 每种 scope 都先构建启发式候选项，再由统一排序器做去重和优先级计算。
    private List<SourceCandidate> buildHeuristicCandidates(String scope,
                                                           List<String> roots,
                                                           String competitorName,
                                                           List<String> providedUrls) {
        List<SourceCandidate> candidates = new ArrayList<>();
        for (String root : roots) {
            switch (scope) {
                case "OFFICIAL" -> candidates.add(buildCandidate(root, scope, competitorName, "HEURISTIC",
                        "根据官网根域名推导官网入口", null, providedUrls));
                case "DOCS" -> addPathCandidates(candidates, root, scope, competitorName,
                        List.of("/docs", "/documentation", "/help", "/guide"), providedUrls);
                case "PRICING" -> addPathCandidates(candidates, root, scope, competitorName,
                        List.of("/pricing", "/plans", "/enterprise"), providedUrls);
                case "NEWS" -> addPathCandidates(candidates, root, scope, competitorName,
                        List.of("/blog", "/news", "/changelog", "/resources"), providedUrls);
                case "REVIEW" -> addReviewSources(candidates, root, competitorName, providedUrls);
                default -> candidates.add(buildCandidate(root, "OFFICIAL", competitorName, "HEURISTIC",
                        "未识别范围，回退到官网根域名", null, providedUrls));
            }
        }
        return mergeCandidates(candidates, List.of());
    }

    private void addPathCandidates(List<SourceCandidate> candidates,
                                   String root,
                                   String scope,
                                   String competitorName,
                                   List<String> paths,
                                   List<String> providedUrls) {
        for (String path : paths) {
            candidates.add(buildCandidate(
                    root + path,
                    scope,
                    competitorName,
                    "HEURISTIC",
                    "根据根域名自动拼接 " + scope + " 入口",
                    null,
                    providedUrls
            ));
        }
    }

    /**
     * 评测来源除了站内案例页，也补充 G2 / Capterra 这类公开评价入口，
     * 避免信息源完全依赖官网首页导致视角单一。
     */
    private void addReviewSources(List<SourceCandidate> candidates,
                                  String root,
                                  String competitorName,
                                  List<String> providedUrls) {
        addPathCandidates(candidates, root, "REVIEW", competitorName,
                List.of("/customers", "/case-studies", "/compare"), providedUrls);

        String encodedName = competitorName == null ? "" : competitorName.trim().replace(" ", "+");
        if (!encodedName.isBlank()) {
            candidates.add(SourceCandidate.builder()
                    .url("https://www.g2.com/search?query=" + encodedName)
                    .title(competitorName + " G2 Search")
                    .sourceType("REVIEW")
                    .discoveryMethod("HEURISTIC")
                    .reason("启发式补充第三方测评入口")
                    .domain("www.g2.com")
                    .relevanceScore(0.78)
                    .freshnessScore(0.55)
                    .qualityScore(0.88)
                    .build());
            candidates.add(SourceCandidate.builder()
                    .url("https://www.capterra.com/search/?query=" + encodedName)
                    .title(competitorName + " Capterra Search")
                    .sourceType("REVIEW")
                    .discoveryMethod("HEURISTIC")
                    .reason("启发式补充第三方测评入口")
                    .domain("www.capterra.com")
                    .relevanceScore(0.76)
                    .freshnessScore(0.55)
                    .qualityScore(0.86)
                    .build());
        }
    }

    // notes 面向人类阅读，用于解释该批来源的业务含义和推导来源。
    private String buildNotes(String scope, List<SourceCandidate> candidates, List<String> providedUrls) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("OFFICIAL", "Official site");
        labels.put("DOCS", "Documentation");
        labels.put("PRICING", "Pricing");
        labels.put("NEWS", "Blog/News");
        labels.put("REVIEW", "Public reviews");

        String origin = providedUrls == null || providedUrls.isEmpty()
                ? "由根域名推导并结合搜索式补源"
                : "基于用户提供 URL 扩展并结合搜索式补源";
        long searchCount = candidates.stream()
                .filter(candidate -> isSearchLikeDiscoveryMethod(candidate.getDiscoveryMethod()))
                .count();
        return labels.getOrDefault(scope, scope)
                + "：保留 " + candidates.size() + " 个候选来源，其中搜索补源 " + searchCount + " 个，" + origin;
    }

    private boolean isSearchLikeDiscoveryMethod(String discoveryMethod) {
        return "SEARCH".equalsIgnoreCase(discoveryMethod)
                || "BROWSER_PREVIEW".equalsIgnoreCase(discoveryMethod);
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

    private List<SourceCandidate> filterSearchCandidates(String scope, List<SourceCandidate> searchCandidates) {
        if (searchCandidates == null || searchCandidates.isEmpty()) {
            return List.of();
        }
        return searchCandidates.stream()
                .filter(candidate -> scope.equals(candidate.getSourceType()))
                .toList();
    }

    private List<SourceCandidate> mergeCandidates(List<SourceCandidate> heuristicCandidates,
                                                  List<SourceCandidate> searchCandidates) {
        List<SourceCandidate> merged = new ArrayList<>();
        if (heuristicCandidates != null) {
            merged.addAll(heuristicCandidates);
        }
        if (searchCandidates != null) {
            merged.addAll(searchCandidates);
        }
        List<SourceCandidate> ranked = candidateRanker.rankAndDeduplicate(merged);
        return ranked.size() > MAX_CANDIDATES_PER_SCOPE ? ranked.subList(0, MAX_CANDIDATES_PER_SCOPE) : ranked;
    }

    private SourceCandidate buildCandidate(String url,
                                           String scope,
                                           String competitorName,
                                           String discoveryMethod,
                                           String reason,
                                           String publishedAt,
                                           List<String> providedUrls) {
        String domain = extractDomain(url);
        double relevanceScore = switch (scope) {
            case "OFFICIAL" -> 0.95;
            case "DOCS" -> 0.91;
            case "PRICING" -> 0.93;
            case "NEWS" -> 0.74;
            case "REVIEW" -> 0.77;
            default -> 0.70;
        };
        double freshnessScore = "NEWS".equals(scope) ? 0.72 : 0.60;
        double qualityScore = (providedUrls != null && !providedUrls.isEmpty()) ? 0.90 : 0.84;

        return SourceCandidate.builder()
                .url(url)
                .title(buildTitle(url, scope, competitorName))
                .sourceType(scope)
                .discoveryMethod(discoveryMethod)
                .reason(reason)
                .domain(domain)
                .publishedAt(publishedAt)
                .relevanceScore(relevanceScore)
                .freshnessScore(freshnessScore)
                .qualityScore(qualityScore)
                .build();
    }

    private String buildTitle(String url, String scope, String competitorName) {
        String label = switch (scope) {
            case "OFFICIAL" -> "官网";
            case "DOCS" -> "文档";
            case "PRICING" -> "定价";
            case "NEWS" -> "新闻";
            case "REVIEW" -> "测评";
            default -> "来源";
        };
        return (StringUtils.hasText(competitorName) ? competitorName : "竞品") + " - " + label + "入口（" + url + "）";
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldCreateSearchOnlyPlan(List<String> normalizedRoots,
                                               String scope,
                                               List<SourceCandidate> searchCandidates) {
        if (normalizedRoots != null && !normalizedRoots.isEmpty()) {
            return false;
        }
        return filterSearchCandidates(scope, searchCandidates).isEmpty();
    }

    private String buildSearchOnlyNotes(String scope) {
        return switch (scope) {
            case "DOCS" -> "未提供可靠官网 URL，跳过域名猜测；执行阶段将优先通过浏览器或 HTTP 搜索补源定位文档入口";
            case "PRICING" -> "未提供可靠官网 URL，跳过域名猜测；执行阶段将优先通过浏览器或 HTTP 搜索补源定位定价入口";
            case "NEWS" -> "未提供可靠官网 URL，跳过域名猜测；执行阶段将优先通过浏览器或 HTTP 搜索补源定位新闻与更新入口";
            case "REVIEW" -> "未提供可靠官网 URL，跳过域名猜测；执行阶段将优先通过浏览器或 HTTP 搜索补源定位测评入口";
            default -> "未提供可靠官网 URL，跳过域名猜测；执行阶段将优先通过浏览器或 HTTP 搜索补源定位官网入口";
        };
    }
}
