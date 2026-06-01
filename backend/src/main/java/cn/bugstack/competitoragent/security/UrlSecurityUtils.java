package cn.bugstack.competitoragent.security;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

/**
 * URL 安全工具。
 * 统一收口协议白名单、HTTPS 校验和日志脱敏。
 */
public final class UrlSecurityUtils {

    private UrlSecurityUtils() {
    }

    public static boolean isHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = normalizeScheme(uri);
            return ("http".equals(scheme) || "https".equals(scheme))
                    && StringUtils.hasText(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHttpsUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "https".equals(normalizeScheme(uri)) && StringUtils.hasText(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public static URI requireHttpOrHttps(String value, String fieldName) {
        if (!isHttpUrl(value)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, fieldName + " 仅支持 http/https URL");
        }
        return URI.create(value.trim());
    }

    public static URI requireHttps(String value, String fieldName) {
        if (!isHttpsUrl(value)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, fieldName + " 必须使用 https URL");
        }
        return URI.create(value.trim());
    }

    public static String maskForLog(String value) {
        if (!StringUtils.hasText(value)) {
            return "[REDACTED]";
        }
        String sanitized = value.replaceAll("\\s+", " ").trim();
        String truncated = sanitized.length() > 100 ? sanitized.substring(0, 100) + "..." : sanitized;
        return "[REDACTED]" + truncated;
    }

    private static String normalizeScheme(URI uri) {
        return uri == null || !StringUtils.hasText(uri.getScheme())
                ? ""
                : uri.getScheme().trim().toLowerCase(Locale.ROOT);
    }
}
