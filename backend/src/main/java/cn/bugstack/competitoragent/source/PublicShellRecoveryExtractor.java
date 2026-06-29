package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录、验证码或反爬页面的公开壳信息恢复器。
 * 该类只读取页面已经公开返回的 title/meta/og/canonical/json-ld/少量可见文本，
 * 不提交表单、不绕过验证码、不使用授权态 cookie。
 */
@Component
public class PublicShellRecoveryExtractor {

    private static final int MAX_CONTENT_LENGTH = 1200;
    private static final int MAX_SNIPPET_LENGTH = 500;
    private static final int MAX_BODY_SNIPPET_LENGTH = 240;
    private static final Pattern META_TAG_PATTERN = Pattern.compile(
            "<meta\\s+([^>]+)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile(
            "<link\\s+([^>]+)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\s+[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern JSON_LD_NAME_PATTERN = Pattern.compile(
            "\"(?:name|headline|description|title)\"\\s*:\\s*\"([^\"]{1,200})\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BODY_PATTERN = Pattern.compile(
            "<body[^>]*>(.*?)</body>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final List<String> USEFUL_META_KEYS = List.of(
            "description",
            "keywords",
            "og:title",
            "og:description",
            "twitter:title",
            "twitter:description"
    );
    private static final List<String> UTILITY_URL_KEYWORDS = List.of(
            "/login",
            "/signin",
            "/sign-in",
            "/passport",
            "/account",
            "/captcha",
            "/challenge",
            "/verify"
    );
    private static final List<String> UTILITY_TEXT_KEYWORDS = List.of(
            "login",
            "signin",
            "sign in",
            "verify you are human",
            "captcha",
            "security check",
            "access denied",
            "登录",
            "验证码",
            "安全验证",
            "请先登录"
    );

    /**
     * 只在页面明显属于登录/验证码/反爬壳时尝试恢复，
     * 避免把普通低信号页面误当成公开壳降级处理。
     */
    public boolean shouldAttemptRecovery(String finalUrl,
                                         String title,
                                         String bodyText,
                                         AntiBotDetectionResult detection) {
        if (detection != null && detection.isBlocked()) {
            return true;
        }
        String normalizedUrl = normalize(finalUrl);
        String normalizedText = normalize(title) + "\n" + normalize(bodyText);
        return containsAny(normalizedUrl, UTILITY_URL_KEYWORDS)
                || containsAny(normalizedText, UTILITY_TEXT_KEYWORDS);
    }

    /**
     * 从公开返回的页面壳里恢复最小可用证据。
     * 只有提取到 meta/json-ld/公开片段等描述性信息时，才允许返回 success=true。
     */
    public SourceCollector.CollectedPage recover(Page page,
                                                 String originalUrl,
                                                 String competitorName,
                                                 String sourceType,
                                                 AntiBotDetectionResult detection) {
        if (page == null) {
            return failed(originalUrl, competitorName, sourceType, "公开壳恢复失败：页面为空");
        }
        try {
            String finalUrl = firstNonBlank(page.url(), originalUrl);
            String html = firstNonBlank(page.content(), "");
            String title = firstNonBlank(page.title(), finalUrl);

            List<String> fragments = new ArrayList<>();
            fragments.add("title: " + title);

            List<String> metaFragments = extractMetaFragments(html);
            List<String> canonicalUrls = extractCanonicalUrls(html);
            List<String> jsonLdSummaries = extractJsonLdSummaries(html);
            String visibleBodySnippet = extractVisibleBodySnippet(html);

            fragments.addAll(metaFragments);
            canonicalUrls.forEach(url -> fragments.add("canonical: " + url));
            jsonLdSummaries.forEach(summary -> fragments.add("json-ld: " + summary));
            if (StringUtils.hasText(visibleBodySnippet)) {
                fragments.add("body: " + visibleBodySnippet);
            }

            String content = compactFragments(fragments);
            boolean hasDescriptiveFragment = !metaFragments.isEmpty()
                    || !jsonLdSummaries.isEmpty()
                    || StringUtils.hasText(visibleBodySnippet);
            if (!hasDescriptiveFragment || looksUtilityOnly(content)) {
                return failed(finalUrl, competitorName, sourceType, "公开壳信息不足，不能作为降级证据");
            }

            String normalizedContent = truncate(content, MAX_CONTENT_LENGTH);
            return SourceCollector.CollectedPage.builder()
                    .url(finalUrl)
                    .title(title)
                    .content(normalizedContent)
                    .snippet(truncate(normalizedContent, MAX_SNIPPET_LENGTH))
                    .metadata(buildMetadata(finalUrl, originalUrl, title, html, detection, canonicalUrls, jsonLdSummaries))
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .collectedAt(Instant.now().toString())
                    .success(true)
                    .build();
        } catch (RuntimeException exception) {
            return failed(originalUrl, competitorName, sourceType,
                    "公开壳恢复异常：" + firstNonBlank(exception.getMessage(), exception.getClass().getSimpleName()));
        }
    }

    private List<String> extractMetaFragments(String html) {
        List<String> fragments = new ArrayList<>();
        Matcher matcher = META_TAG_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String key = firstNonBlank(extractAttribute(attributes, "name"), extractAttribute(attributes, "property"));
            String value = extractAttribute(attributes, "content");
            if (isUsefulMetaKey(key) && StringUtils.hasText(value)) {
                fragments.add(key.trim().toLowerCase(Locale.ROOT) + ": " + cleanText(value));
            }
        }
        return fragments;
    }

    private boolean isUsefulMetaKey(String key) {
        String normalized = normalize(key);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        for (String candidate : USEFUL_META_KEYS) {
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractCanonicalUrls(String html) {
        List<String> canonicalUrls = new ArrayList<>();
        Matcher matcher = LINK_TAG_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String rel = normalize(extractAttribute(attributes, "rel"));
            String href = cleanText(extractAttribute(attributes, "href"));
            if ("canonical".equals(rel) && StringUtils.hasText(href)) {
                canonicalUrls.add(href);
            }
        }
        return canonicalUrls;
    }

    private List<String> extractJsonLdSummaries(String html) {
        LinkedHashSet<String> summaries = new LinkedHashSet<>();
        Matcher matcher = JSON_LD_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String json = matcher.group(1);
            Matcher nameMatcher = JSON_LD_NAME_PATTERN.matcher(json == null ? "" : json);
            while (nameMatcher.find()) {
                String summary = cleanText(nameMatcher.group(1));
                if (StringUtils.hasText(summary)) {
                    summaries.add(summary);
                }
            }
        }
        return new ArrayList<>(summaries);
    }

    private String extractVisibleBodySnippet(String html) {
        Matcher matcher = BODY_PATTERN.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            return "";
        }
        String text = cleanText(htmlToText(matcher.group(1)));
        if (!StringUtils.hasText(text) || looksUtilityOnly(text) || text.length() < 40) {
            return "";
        }
        return truncate(text, MAX_BODY_SNIPPET_LENGTH);
    }

    /**
     * 只要正文片段仍然主要是“登录/验证码/安全检查”提示，就不能伪装成有效公开证据。
     */
    private boolean looksUtilityOnly(String content) {
        String normalized = normalize(content);
        if (!StringUtils.hasText(normalized)) {
            return true;
        }
        String stripped = normalized;
        for (String keyword : UTILITY_TEXT_KEYWORDS) {
            stripped = stripped.replace(normalize(keyword), "");
        }
        stripped = stripped.replace("title:", "")
                .replace("body:", "")
                .replace("canonical:", "")
                .replace("json-ld:", "")
                .trim();
        return stripped.length() < 20;
    }

    private String buildMetadata(String finalUrl,
                                 String originalUrl,
                                 String title,
                                 String html,
                                 AntiBotDetectionResult detection,
                                 List<String> canonicalUrls,
                                 List<String> jsonLdSummaries) {
        List<String> qualitySignals = new ArrayList<>();
        qualitySignals.add("PUBLIC_SHELL_ONLY");
        qualitySignals.add(resolvePartialSignal(finalUrl, title, html, detection));

        Set<String> sourceUrls = new LinkedHashSet<>();
        if (StringUtils.hasText(originalUrl)) {
            sourceUrls.add(originalUrl);
        }
        if (StringUtils.hasText(finalUrl)) {
            sourceUrls.add(finalUrl);
        }

        StringBuilder metadata = new StringBuilder();
        metadata.append("{");
        metadata.append("\"collector\":\"public-shell-recovery\"");
        metadata.append(",\"sourceUrls\":").append(toJsonArray(new ArrayList<>(sourceUrls)));
        metadata.append(",\"qualitySignals\":").append(toJsonArray(qualitySignals));
        metadata.append(",\"canonicalUrls\":").append(toJsonArray(canonicalUrls));
        metadata.append(",\"jsonLdSummaries\":").append(toJsonArray(jsonLdSummaries));
        metadata.append(",\"structuredBlocksRecovered\":").append(!jsonLdSummaries.isEmpty());
        if (detection != null && StringUtils.hasText(detection.getReasonCode())) {
            metadata.append(",\"blockedReasonCode\":\"").append(escapeJson(detection.getReasonCode())).append("\"");
        }
        if (detection != null && detection.getMatchedSignals() != null) {
            metadata.append(",\"matchedSignals\":").append(toJsonArray(detection.getMatchedSignals()));
        }
        metadata.append(",\"collectedAt\":\"").append(Instant.now()).append("\"");
        metadata.append("}");
        return metadata.toString();
    }

    private String resolvePartialSignal(String finalUrl,
                                        String title,
                                        String html,
                                        AntiBotDetectionResult detection) {
        if (detection != null && StringUtils.hasText(detection.getReasonCode())
                && detection.getReasonCode().toUpperCase(Locale.ROOT).contains("LOGIN")) {
            return "LOGIN_GATE_PARTIAL";
        }
        String combined = normalize(finalUrl) + "\n" + normalize(title) + "\n" + normalize(html);
        return containsAny(combined, List.of("/login", "/signin", "login", "signin", "sign in", "登录", "请先登录"))
                ? "LOGIN_GATE_PARTIAL"
                : "ANTI_BOT_PARTIAL";
    }

    private String compactFragments(List<String> fragments) {
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String fragment : fragments == null ? List.<String>of() : fragments) {
            String cleaned = cleanText(fragment);
            if (StringUtils.hasText(cleaned)) {
                deduplicated.add(cleaned);
            }
        }
        return String.join("\n", deduplicated);
    }

    private String extractAttribute(String attributes, String name) {
        if (!StringUtils.hasText(attributes) || !StringUtils.hasText(name)) {
            return null;
        }
        Pattern pattern = Pattern.compile(
                name + "\\s*=\\s*[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>|</div>|</section>|</article>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String cleanText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
                .replaceAll("\\r\\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .trim();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        int count = 0;
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            if (count > 0) {
                builder.append(",");
            }
            builder.append("\"").append(escapeJson(value)).append("\"");
            count++;
        }
        builder.append("]");
        return builder.toString();
    }

    private SourceCollector.CollectedPage failed(String url,
                                                 String competitorName,
                                                 String sourceType,
                                                 String message) {
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .collectedAt(Instant.now().toString())
                .success(false)
                .errorMessage(message)
                .build();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
