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
            return buildCanonicalUrl(host, path, query);
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

    private String buildCanonicalUrl(String host, String path, String query) throws URISyntaxException {
        URI canonical = new URI("https", null, host, -1, path, StringUtils.hasText(query) ? query : null, null);
        return canonical.toString();
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
