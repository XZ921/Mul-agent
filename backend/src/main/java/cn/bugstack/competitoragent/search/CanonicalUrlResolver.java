package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一 URL 归一化工具。
 * 这里专门收口“同一页面的不同 URL 形态”，避免搜索候选、验证尝试和正式采集各自维护一套近似规则，
 * 导致 http/https、www、追踪参数和尾斜杠在不同链路里被误判成多个独立来源。
 */
@Component
public class CanonicalUrlResolver {

    private static final List<String> TRACKING_QUERY_PREFIXES = List.of(
            "utm_",
            "spm",
            "gclid",
            "fbclid",
            "msclkid",
            "_hs",
            "mc_",
            "mkt_",
            "igshid"
    );

    /**
     * 把原始 URL 折叠成“同一页面”的稳定 key。
     * 当前规则固定为：
     * 1. http/https 统一提升为 https，避免同站协议差异重复抓取
     * 2. 去掉 host 前缀 www.
     * 3. 去掉 fragment
     * 4. 清理常见追踪参数，仅保留业务参数
     * 5. 去掉非根路径尾斜杠
     */
    public String canonicalize(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!StringUtils.hasText(uri.getHost())) {
                return trimmed;
            }
            String host = normalizeHost(uri.getHost());
            String path = normalizePath(uri.getPath());
            String query = normalizeQuery(uri.getRawQuery());
            String scheme = resolveCanonicalScheme(uri, host);
            int port = resolveCanonicalPort(uri, scheme, host);
            return buildCanonicalUrl(scheme, host, port, path, query);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    /**
     * 对外统一提取归一化后的域名。
     * 这样同域止损也能共享去 www 的规则，避免 example.com 和 www.example.com 被拆成两个域。
     */
    public String canonicalDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return StringUtils.hasText(uri.getHost()) ? normalizeHost(uri.getHost()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * canonical URL 既要承担“同页去重 key”的职责，也不能把真实可访问地址改坏。
     * 因此这里对标准站点仍尽量收敛到 https，但对本地/IP 或显式端口场景要保留网络身份，
     * 避免把 http://127.0.0.1:18080/feed.xml 这类 RSS/开发地址错误改写成 https 默认端口。
     */
    private String buildCanonicalUrl(String scheme,
                                     String host,
                                     int port,
                                     String path,
                                     String query) throws URISyntaxException {
        URI canonical = new URI(scheme, null, host, port, path, StringUtils.hasText(query) ? query : null, null);
        return canonical.toString();
    }

    private String resolveCanonicalScheme(URI uri, String host) {
        String scheme = StringUtils.hasText(uri.getScheme())
                ? uri.getScheme().trim().toLowerCase(Locale.ROOT)
                : "https";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return scheme;
        }
        if (shouldPreserveNetworkIdentity(uri, host)) {
            return scheme;
        }
        return "https";
    }

    private int resolveCanonicalPort(URI uri, String scheme, String host) {
        if (!shouldPreserveNetworkIdentity(uri, host)) {
            return -1;
        }
        int port = uri.getPort();
        if (port < 0) {
            return -1;
        }
        if (("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443)) {
            return -1;
        }
        return port;
    }

    private boolean shouldPreserveNetworkIdentity(URI uri, String host) {
        return uri.getPort() > 0 || isLocalOrIpHost(host);
    }

    private boolean isLocalOrIpHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".local")) {
            return true;
        }
        if (normalizedHost.contains(":")) {
            return true;
        }
        String[] segments = normalizedHost.split("\\.");
        if (segments.length != 4) {
            return false;
        }
        for (String segment : segments) {
            if (!segment.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeHost(String host) {
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("www.")) {
            return normalizedHost.substring(4);
        }
        return normalizedHost;
    }

    private String normalizePath(String path) {
        String normalizedPath = StringUtils.hasText(path) ? path : "";
        if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    private String normalizeQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return null;
        }
        Map<String, List<String>> preservedParameters = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (!StringUtils.hasText(pair)) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            if (!StringUtils.hasText(key) || isTrackingParameter(key)) {
                continue;
            }
            String value = parts.length > 1 ? decode(parts[1]) : "";
            preservedParameters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        if (preservedParameters.isEmpty()) {
            return null;
        }
        List<String> normalizedPairs = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : preservedParameters.entrySet()) {
            for (String value : entry.getValue()) {
                normalizedPairs.add(value.isEmpty() ? entry.getKey() : entry.getKey() + "=" + value);
            }
        }
        return String.join("&", normalizedPairs);
    }

    private boolean isTrackingParameter(String key) {
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        return TRACKING_QUERY_PREFIXES.stream().anyMatch(prefix ->
                normalizedKey.equals(prefix) || normalizedKey.startsWith(prefix));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
