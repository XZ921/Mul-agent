package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 正文可用性评分器。
 * 它把“正文是否能支撑分析”从“来源域名是否可信”中拆出来，避免官方壳页被误升为强证据。
 */
@Component
public class ContentUsabilityScorer {

    private static final List<String> NAVIGATION_TERMS = List.of(
            "首页", "下载", "安卓", "iOS", "TV", "PC", "车机", "扫码下载",
            "登录", "注册", "帮助中心", "联系我们", "友情链接"
    );
    private static final List<String> REAL_CONTENT_TERMS = List.of(
            "计费", "定价", "免费", "额度", "调用", "接口", "API", "SDK",
            "功能", "能力", "认证", "教程", "对比", "用户反馈", "官方文档"
    );

    /**
     * 评分以正文质量为主导，sourceTrust 只作为轻微调节项，不会把第三方好正文压到不可用。
     */
    public ContentUsabilityScore score(CollectedPageView page) {
        if (page == null || !StringUtils.hasText(page.getBodyText())) {
            return ContentUsabilityScore.builder()
                    .usability(0.0D)
                    .sourceTier(resolveSourceTier(page))
                    .reasons(List.of("EMPTY_BODY"))
                    .build();
        }

        String bodyText = page.getBodyText();
        List<String> reasons = new ArrayList<>();
        double score = 0.35D;

        int usefulLength = normalizeBody(bodyText).length();
        int navigationHits = countContains(bodyText, NAVIGATION_TERMS);
        int realContentHits = countContains(bodyText, REAL_CONTENT_TERMS);
        boolean navShell = navigationHits >= 5 && realContentHits <= 1 && usefulLength < 120;
        if (navShell) {
            reasons.add("NAV_SHELL_DETECTED");
            score = Math.min(score, 0.30D);
        }

        if (usefulLength >= 80) {
            reasons.add("RICH_BODY_TEXT");
            score = Math.max(score, 0.62D);
        }
        if (realContentHits >= 3) {
            reasons.add("FIELD_SIGNAL_DENSE_BODY");
            score = Math.max(score, 0.72D);
        }
        if (page.getStructuredBlocks() != null && !page.getStructuredBlocks().isEmpty()) {
            reasons.add("STRUCTURED_BLOCK_MATCHED");
            score = Math.max(score, 0.76D);
        }

        double sourceAdjustment = resolveSourceAdjustment(page);
        double adjusted = clamp(score + sourceAdjustment);
        if (navShell) {
            adjusted = Math.min(adjusted, 0.35D);
        }

        return ContentUsabilityScore.builder()
                .usability(round(adjusted))
                .sourceTier(resolveSourceTier(page))
                .reasons(reasons.stream().distinct().toList())
                .build();
    }

    private String resolveSourceTier(CollectedPageView page) {
        if (page == null) {
            return "THIRD_PARTY";
        }
        String sourceType = page.getSourceType() == null ? "" : page.getSourceType().toUpperCase(Locale.ROOT);
        if (sourceType.contains("OFFICIAL") || sourceType.contains("DOCS") || sourceType.contains("PRICING")) {
            return "OFFICIAL";
        }
        String host = resolveHost(page.getUrl());
        if (host.startsWith("docs.") || host.startsWith("open.") || host.contains("support") || host.contains("help")) {
            return "OFFICIAL";
        }
        return "THIRD_PARTY";
    }

    private double resolveSourceAdjustment(CollectedPageView page) {
        if (page == null) {
            return 0;
        }
        if ("OFFICIAL".equals(resolveSourceTier(page))) {
            return Math.min(0.06D, Math.max(0, page.getSourceTrust() - 0.75D) * 0.2D);
        }
        return Math.max(-0.08D, Math.min(0.02D, (page.getSourceTrust() - 0.5D) * 0.1D));
    }

    private int countContains(String content, List<String> terms) {
        int count = 0;
        for (String term : terms) {
            if (StringUtils.hasText(term) && content.contains(term)) {
                count++;
            }
        }
        return count;
    }

    private String normalizeBody(String bodyText) {
        return bodyText == null ? "" : bodyText.replaceAll("\\s+", "");
    }

    private String resolveHost(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
