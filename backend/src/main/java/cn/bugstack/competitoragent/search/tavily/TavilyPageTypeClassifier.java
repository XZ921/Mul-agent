package cn.bugstack.competitoragent.search.tavily;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tavily 预取正文页面类型分类器。
 * 这里把页面分类从 Provider 中抽离出来，统一收口 pageType 语义，
 * 避免后续 Gate、Audit、Executor 各自维护一套分散的启发式规则。
 */
public class TavilyPageTypeClassifier {

    private static final Set<String> FORUM_DOMAINS = Set.of(
            "reddit.com",
            "v2ex.com",
            "okjike.com",
            "tieba.baidu.com",
            "zhihu.com"
    );

    private static final Set<String> VIDEO_DOMAINS = Set.of(
            "bilibili.com",
            "douyin.com",
            "youtube.com"
    );

    private static final List<String> SEARCH_SIGNALS = List.of(
            "/search",
            "?q=",
            "&q=",
            "?query=",
            "&query=",
            "?keyword=",
            "&keyword=",
            "?wd=",
            "&wd="
    );

    private static final List<String> VIDEO_LIST_SIGNALS = List.of(
            "/v/popular",
            "/playlist",
            "/channel",
            "/list",
            "/hot",
            "/popular"
    );

    private static final List<String> OFFICIAL_DOC_SIGNALS = List.of(
            "/doc",
            "/docs",
            "/documentation",
            "/guide",
            "/api",
            "/reference",
            "/help",
            "/support",
            "/agreement",
            "/protocol",
            "/rule",
            "/rules",
            "/policy",
            "/open"
    );

    /**
     * 按固定优先级对页面分类。
     * 顺序必须先识别 SEARCH_PAGE / VIDEO_LIST 这类强噪声页，再识别论坛帖和正文页，
     * 否则长文本搜索页或论坛帖很容易被误判成 ARTICLE，导致后续 Gate 放进 fast lane。
     */
    public String classify(String url, String title, String rawContent, Set<String> officialDomains) {
        String normalizedUrl = normalize(url);
        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(rawContent);
        String host = extractHost(normalizedUrl);
        Set<String> normalizedOfficialDomains = normalizeDomains(officialDomains);

        if (isSearchPage(normalizedUrl)) {
            return "SEARCH_PAGE";
        }
        if (isVideoList(normalizedUrl, host)) {
            return "VIDEO_LIST";
        }
        if (isVideoPage(normalizedUrl, host)) {
            return "VIDEO_PAGE";
        }
        if (isForumThread(host, normalizedUrl)) {
            return "FORUM_THREAD";
        }
        if (isPdf(normalizedUrl, normalizedTitle)) {
            return "PDF";
        }
        if (isOfficialDoc(host, normalizedUrl, normalizedTitle, normalizedOfficialDomains)) {
            return "OFFICIAL_DOC";
        }
        if (isArticle(normalizedUrl, normalizedTitle, normalizedContent)) {
            return "ARTICLE";
        }
        return "GENERIC_PAGE";
    }

    private boolean isSearchPage(String normalizedUrl) {
        if (!StringUtils.hasText(normalizedUrl)) {
            return false;
        }
        return SEARCH_SIGNALS.stream().anyMatch(normalizedUrl::contains);
    }

    private boolean isVideoList(String normalizedUrl, String host) {
        if (!VIDEO_DOMAINS.contains(host)) {
            return false;
        }
        return VIDEO_LIST_SIGNALS.stream().anyMatch(normalizedUrl::contains);
    }

    private boolean isVideoPage(String normalizedUrl, String host) {
        if (!VIDEO_DOMAINS.contains(host)) {
            return false;
        }
        return normalizedUrl.contains("/video/")
                || normalizedUrl.contains("/watch")
                || normalizedUrl.contains("/shorts/")
                || normalizedUrl.contains("/shipin/");
    }

    /**
     * 论坛帖必须优先按站点类型判定。
     * 这样可以防止 Reddit、即刻、V2EX 这类社区讨论帖因为正文较长，
     * 被后面的 ARTICLE 规则误识别成高可信正文来源。
     */
    private boolean isForumThread(String host, String normalizedUrl) {
        if (FORUM_DOMAINS.contains(host)) {
            return true;
        }
        return normalizedUrl.contains("/comments/")
                || normalizedUrl.contains("/thread/")
                || normalizedUrl.contains("/post/")
                || normalizedUrl.contains("/p/");
    }

    private boolean isPdf(String normalizedUrl, String normalizedTitle) {
        return normalizedUrl.endsWith(".pdf") || normalizedTitle.endsWith(".pdf");
    }

    private boolean isOfficialDoc(String host,
                                  String normalizedUrl,
                                  String normalizedTitle,
                                  Set<String> officialDomains) {
        boolean officialDomainMatched = matchesOfficialDomain(host, officialDomains);
        boolean docsSignalMatched = OFFICIAL_DOC_SIGNALS.stream().anyMatch(normalizedUrl::contains)
                || normalizedTitle.contains("api")
                || normalizedTitle.contains("文档")
                || normalizedTitle.contains("协议")
                || normalizedTitle.contains("规则")
                || normalizedTitle.contains("帮助");
        return officialDomainMatched && docsSignalMatched;
    }

    private boolean isArticle(String normalizedUrl, String normalizedTitle, String normalizedContent) {
        if (normalizedContent.length() >= 600) {
            return true;
        }
        if (normalizedContent.length() >= 200
                && (normalizedUrl.contains("/blog/")
                || normalizedUrl.contains("/article/")
                || normalizedUrl.contains("/news/")
                || normalizedUrl.contains("/post/"))) {
            return true;
        }
        return normalizedContent.length() >= 200
                && (normalizedTitle.contains("分析")
                || normalizedTitle.contains("观察")
                || normalizedTitle.contains("解读")
                || normalizedTitle.contains("review"));
    }

    private boolean matchesOfficialDomain(String host, Set<String> officialDomains) {
        if (!StringUtils.hasText(host) || officialDomains.isEmpty()) {
            return false;
        }
        for (String officialDomain : officialDomains) {
            if (host.equals(officialDomain) || host.endsWith("." + officialDomain)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> normalizeDomains(Set<String> officialDomains) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (officialDomains == null || officialDomains.isEmpty()) {
            return normalized;
        }
        for (String domain : officialDomains) {
            String value = normalize(domain);
            if (StringUtils.hasText(value)) {
                normalized.add(stripLeadingWww(value));
            }
        }
        return normalized;
    }

    private String extractHost(String normalizedUrl) {
        if (!StringUtils.hasText(normalizedUrl)) {
            return "";
        }
        try {
            String host = URI.create(normalizedUrl).getHost();
            return stripLeadingWww(normalize(host));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String stripLeadingWww(String value) {
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
