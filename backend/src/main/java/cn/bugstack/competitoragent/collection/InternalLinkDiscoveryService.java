package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站内链接发现服务。
 * 负责把已采集页面里的 Markdown/HTML 链接转成可继续采集的 SourceCandidate，
 * 但只保留 same-domain 且高价值路径的公开页面，避免把页面导航、外链和噪声资源带入递归链路。
 */
@Component
public class InternalLinkDiscoveryService {

    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern HTML_LINK_PATTERN = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final List<String> HIGH_VALUE_PATH_KEYWORDS = List.of(
            "/doc",
            "/docs",
            "/api",
            "/sdk",
            "/reference",
            "/guide",
            "/help"
    );
    private static final List<String> HIGH_VALUE_LABEL_KEYWORDS = List.of(
            "open api",
            "api",
            "接口",
            "账户授权",
            "账号授权",
            "授权",
            "oauth",
            "sdk",
            "android",
            "ios",
            "用户管理",
            "视频管理",
            "数据开放",
            "直播能力",
            "开发",
            "文档",
            "指南",
            "guide",
            "reference"
    );
    private static final List<String> LOW_VALUE_LINK_KEYWORDS = List.of(
            "联系我们",
            "联系",
            "contact",
            "feedback",
            "邮箱",
            "mail",
            "商务合作",
            "关于",
            "about",
            "隐私",
            "privacy",
            "协议",
            "terms"
    );

    private final InternalLinkDiscoveryProperties properties;
    private final CanonicalUrlResolver canonicalUrlResolver;

    @Autowired
    public InternalLinkDiscoveryService(InternalLinkDiscoveryProperties properties,
                                        CanonicalUrlResolver canonicalUrlResolver) {
        this.properties = properties == null ? new InternalLinkDiscoveryProperties() : properties;
        this.canonicalUrlResolver = canonicalUrlResolver == null ? new CanonicalUrlResolver() : canonicalUrlResolver;
    }

    /**
     * 从父页面内容中提取同站高价值链接。
     * 发现服务本身不做递归调度，只负责把页面内容翻译成标准候选；
     * 真正的限深、去重和总量控制由 CollectionExecutionCoordinator 统一执行。
     */
    public List<SourceCandidate> discover(CollectionTaskPackage sourcePackage,
                                          CollectionExecutionResult result,
                                          int depth) {
        if (!properties.isEnabled()
                || sourcePackage == null
                || result == null
                || !result.isSuccess()
                || depth >= Math.max(0, properties.getMaxDepth())) {
            return List.of();
        }
        String parentUrl = resolveParentUrl(sourcePackage, result);
        String content = result.getContent();
        if (!StringUtils.hasText(parentUrl) || !StringUtils.hasText(content)) {
            return List.of();
        }

        try {
            Map<String, DiscoveredInternalLink> candidates = new LinkedHashMap<>();
            int nextOrder = appendMarkdownCandidates(parentUrl, content, candidates, 0);
            appendHtmlCandidates(parentUrl, content, candidates, nextOrder);
            return candidates.values().stream()
                    .filter(link -> !isLowValueUtilityLink(link))
                    .sorted(Comparator.comparingDouble(DiscoveredInternalLink::score).reversed()
                            .thenComparingInt(DiscoveredInternalLink::order))
                    .limit(Math.max(0, properties.getMaxLinksPerNode()))
                    .map(link -> buildCandidate(sourcePackage, parentUrl, link.canonicalUrl(), link.label()))
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 统一复用同一套过滤规则处理 Markdown 与 HTML 链接。
     * 这样两种来源的解析结果会共享相对路径解析、canonical 去重和 sourceUrls 追溯语义。
     */
    private int appendMarkdownCandidates(String parentUrl,
                                         String content,
                                         Map<String, DiscoveredInternalLink> candidates,
                                         int startOrder) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content);
        int order = startOrder;
        while (matcher.find()) {
            appendCandidate(parentUrl, matcher.group(2), matcher.group(1), candidates, order++);
        }
        return order;
    }

    private int appendHtmlCandidates(String parentUrl,
                                     String content,
                                     Map<String, DiscoveredInternalLink> candidates,
                                     int startOrder) {
        Matcher matcher = HTML_LINK_PATTERN.matcher(content);
        int order = startOrder;
        while (matcher.find()) {
            appendCandidate(parentUrl, matcher.group(1), htmlToPlainText(matcher.group(2)), candidates, order++);
        }
        return order;
    }

    private void appendCandidate(String parentUrl,
                                 String rawLink,
                                 String rawLabel,
                                 Map<String, DiscoveredInternalLink> candidates,
                                 int order) {
        String sanitizedLink = sanitizeExtractedUrl(rawLink);
        String resolvedUrl = resolveChildUrl(parentUrl, sanitizedLink);
        if (!StringUtils.hasText(resolvedUrl) || !isEligibleInternalLink(parentUrl, resolvedUrl)) {
            return;
        }
        String canonicalUrl = canonicalUrlResolver.canonicalize(resolvedUrl);
        if (!StringUtils.hasText(canonicalUrl) || candidates.containsKey(canonicalUrl)) {
            return;
        }
        String label = cleanLabel(rawLabel);
        candidates.put(canonicalUrl, new DiscoveredInternalLink(canonicalUrl, label, scoreLink(canonicalUrl, label), order));
    }

    /**
     * child candidate 必须显式保留父页 URL 与子页 URL，
     * 这样 audit / replay / report 在只看 sourceUrls 时就能回溯“这个子页是从哪里被发现的”。
     */
    private SourceCandidate buildCandidate(CollectionTaskPackage sourcePackage,
                                           String parentUrl,
                                           String childUrl,
                                           String label) {
        return SourceCandidate.builder()
                .url(childUrl)
                .title(buildTitle(sourcePackage.getCompetitorName(), childUrl, label))
                .sourceType(resolveChildSourceType(sourcePackage.getSourceType(), childUrl))
                .discoveryMethod("INTERNAL_LINK_DISCOVERY")
                .reason(StringUtils.hasText(label)
                        ? "discovered from internal same-domain high-value link: " + label
                        : "discovered from internal same-domain high-value link")
                .domain(canonicalUrlResolver.canonicalDomain(childUrl))
                .sourceFamilyKey(sourcePackage.getSourceFamilyKey())
                .providerKey("internal-link-discovery")
                .sourceUrls(normalizeSourceUrls(List.of(parentUrl, childUrl)))
                .relevanceScore(0.78D)
                .freshnessScore(0.50D)
                .qualityScore(0.82D)
                .qualitySignals(List.of("INTERNAL_LINK_DISCOVERY"))
                .build();
    }

    private String resolveParentUrl(CollectionTaskPackage sourcePackage, CollectionExecutionResult result) {
        if (sourcePackage != null && StringUtils.hasText(sourcePackage.getUrl())) {
            return sourcePackage.getUrl();
        }
        if (result != null && StringUtils.hasText(result.getResourceLocator())) {
            return result.getResourceLocator();
        }
        return null;
    }

    private String sanitizeExtractedUrl(String rawLink) {
        if (!StringUtils.hasText(rawLink)) {
            return null;
        }
        String trimmed = rawLink.trim();
        int fragmentIndex = trimmed.indexOf('#');
        if (fragmentIndex >= 0) {
            trimmed = trimmed.substring(0, fragmentIndex);
        }
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0 && trimmed.startsWith("#")) {
            return null;
        }
        if (!StringUtils.hasText(trimmed)
                || trimmed.startsWith("javascript:")
                || trimmed.startsWith("mailto:")
                || trimmed.startsWith("tel:")) {
            return null;
        }
        return trimmed;
    }

    private String cleanLabel(String rawLabel) {
        if (!StringUtils.hasText(rawLabel)) {
            return "";
        }
        return htmlToPlainText(rawLabel)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String htmlToPlainText(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private String resolveChildUrl(String parentUrl, String rawLink) {
        if (!StringUtils.hasText(parentUrl) || !StringUtils.hasText(rawLink)) {
            return null;
        }
        try {
            URI parentUri = URI.create(parentUrl);
            URI resolved = parentUri.resolve(rawLink.trim());
            String resolvedUrl = resolved.toString();
            return UrlSecurityUtils.isHttpUrl(resolvedUrl) ? resolvedUrl : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 这里按 host 或 root-domain 做同站判定。
     * 不引入 public suffix 依赖的前提下，先用“最后两个域段”近似 root-domain，
     * 既能覆盖 docs.example.com -> api.example.com，也能避免跨站链接误入采集队列。
     */
    private boolean isEligibleInternalLink(String parentUrl, String childUrl) {
        if (!UrlSecurityUtils.isHttpUrl(parentUrl) || !UrlSecurityUtils.isHttpUrl(childUrl)) {
            return false;
        }
        String parentDomain = canonicalUrlResolver.canonicalDomain(parentUrl);
        String childDomain = canonicalUrlResolver.canonicalDomain(childUrl);
        if (!StringUtils.hasText(parentDomain) || !StringUtils.hasText(childDomain)) {
            return false;
        }
        if (!(parentDomain.equalsIgnoreCase(childDomain)
                || sameRootDomain(parentDomain, childDomain))) {
            return false;
        }
        return isHighValuePath(childUrl);
    }

    private boolean sameRootDomain(String left, String right) {
        return extractRootDomain(left).equalsIgnoreCase(extractRootDomain(right));
    }

    private String extractRootDomain(String domain) {
        if (!StringUtils.hasText(domain)) {
            return "";
        }
        String[] segments = domain.trim().toLowerCase(Locale.ROOT).split("\\.");
        if (segments.length < 2) {
            return domain.trim().toLowerCase(Locale.ROOT);
        }
        return segments[segments.length - 2] + "." + segments[segments.length - 1];
    }

    private boolean isHighValuePath(String url) {
        try {
            String path = URI.create(url).getPath();
            if (!StringUtils.hasText(path)) {
                return false;
            }
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            return HIGH_VALUE_PATH_KEYWORDS.stream().anyMatch(normalizedPath::contains);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * B 站这类开放平台的文档详情 URL 往往都是 /doc/{category}/{uuid}，
     * 仅靠路径无法区分“OPEN API”和“联系我们”，因此必须把锚文本纳入排序。
     */
    private double scoreLink(String url, String label) {
        String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        double score = 10.0D;
        if (normalizedUrl.contains("/api")) {
            score += 36.0D;
        }
        if (normalizedUrl.contains("/sdk")) {
            score += 34.0D;
        }
        if (normalizedUrl.contains("/reference")) {
            score += 30.0D;
        }
        if (normalizedUrl.contains("/doc") || normalizedUrl.contains("/docs")) {
            score += 20.0D;
        }
        if (normalizedUrl.contains("/guide")) {
            score += 16.0D;
        }
        if (containsAny(normalizedLabel, List.of("open api", "api", "接口"))) {
            score += 160.0D;
        }
        if (containsAny(normalizedLabel, List.of("账户授权", "账号授权", "授权", "oauth"))) {
            score += 132.0D;
        }
        if (containsAny(normalizedLabel, List.of("sdk", "android", "ios"))) {
            score += 88.0D;
        }
        if (containsAny(normalizedLabel, List.of("用户管理", "视频管理", "数据开放", "直播能力"))) {
            score += 72.0D;
        }
        if (containsAny(normalizedLabel, HIGH_VALUE_LABEL_KEYWORDS)) {
            score += 28.0D;
        }
        if (containsAny(normalizedLabel + " " + normalizedUrl, LOW_VALUE_LINK_KEYWORDS)) {
            score -= 1000.0D;
        }
        return score;
    }

    private boolean isLowValueUtilityLink(DiscoveredInternalLink link) {
        if (link == null) {
            return true;
        }
        String signal = (link.label() + " " + link.canonicalUrl()).toLowerCase(Locale.ROOT);
        return containsAny(signal, LOW_VALUE_LINK_KEYWORDS);
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (!StringUtils.hasText(value) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(StringUtils::hasText)
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private String resolveChildSourceType(String parentSourceType, String childUrl) {
        try {
            String path = URI.create(childUrl).getPath();
            String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (normalizedPath.contains("/pricing") || normalizedPath.contains("/plans")) {
                return "PRICING";
            }
        } catch (Exception ignored) {
            // 路径解析失败时回退为父级 sourceType，避免发现链路因为单个 URL 异常中断。
        }
        return StringUtils.hasText(parentSourceType) ? parentSourceType : "OFFICIAL";
    }

    private String buildTitle(String competitorName, String childUrl, String label) {
        String domain = canonicalUrlResolver.canonicalDomain(childUrl);
        if (StringUtils.hasText(label)) {
            return StringUtils.hasText(competitorName)
                    ? competitorName + " internal page - " + label
                    : label;
        }
        return StringUtils.hasText(competitorName)
                ? competitorName + " internal page - " + domain
                : childUrl;
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                if (StringUtils.hasText(sourceUrl)) {
                    normalized.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private record DiscoveredInternalLink(String canonicalUrl, String label, double score, int order) {
    }
}
