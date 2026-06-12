package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索候选关键词策略。
 * <p>
 * 这里集中维护不同 sourceType 的正向命中词、营销拦截词和高价值信息词，
 * 避免这些规则继续散落在 CandidateVerifier 的条件分支里。
 */
@Component
public class SearchKeywordPolicy {

    private static final Map<String, List<String>> EXPECTED_KEYWORDS = Map.of(
            "DOCS", List.of(
                    "docs", "documentation", "help", "guide", "api", "reference",
                    "文档", "帮助中心", "指南", "手册", "开发指引", "参考手册", "接入说明"
            ),
            "NEWS", List.of(
                    "blog", "news", "changelog", "update", "release", "announcement",
                    "发布日志", "更新", "公告", "产品动态", "版本发布"
            ),
            "REVIEW", List.of(
                    "review", "reviews", "rating", "customer", "compare", "g2", "capterra",
                    "评测", "评价", "对比", "口碑", "评分", "用户反馈"
            ),
            "PRICING", List.of(
                    "pricing", "price", "plan", "plans", "billing", "subscription", "enterprise",
                    "定价", "价格", "计费", "计费方式", "收费", "套餐", "版本"
            ),
            "OFFICIAL", List.of(
                    "official", "homepage", "overview", "features", "feature", "about",
                    "官网", "首页", "产品介绍", "功能", "方案", "能力", "概览"
            )
    );

    private static final Map<String, List<String>> MARKETING_KEYWORDS = Map.of(
            "OFFICIAL", List.of(
                    "立即购买", "立刻购买", "优惠", "特惠", "秒杀", "促销", "活动页", "免费试用", "联系销售",
                    "buy now", "limited time", "discount", "special offer", "free trial", "contact sales"
            ),
            "PRICING", List.of(
                    "限时优惠", "咨询报价", "专属折扣", "促销", "联系销售",
                    "limited time", "discount", "contact sales"
            )
    );

    private static final Map<String, List<String>> HIGH_VALUE_INFORMATION_KEYWORDS = Map.of(
            "DOCS", List.of(
                    "文档", "帮助中心", "指南", "手册", "开发指引", "参考手册",
                    "documentation", "guide", "api", "reference"
            ),
            "NEWS", List.of(
                    "发布日志", "更新", "公告", "版本发布",
                    "release", "announcement", "changelog"
            ),
            "REVIEW", List.of(
                    "评测", "评价", "对比", "口碑", "评分",
                    "review", "compare", "rating"
            ),
            "PRICING", List.of(
                    "定价", "价格", "计费", "计费方式", "收费", "套餐",
                    "pricing", "billing", "plan"
            ),
            "OFFICIAL", List.of(
                    "官网", "产品介绍", "功能", "方案", "能力", "概览",
                    "overview", "features"
            )
    );

    /**
     * 返回给定 sourceType 的正向命中词。
     * 默认会附带 OFFICIAL 通用词，避免未知类型完全失去兜底语义。
     */
    public List<String> expectedKeywords(String sourceType) {
        String normalizedType = normalizeType(sourceType);
        LinkedHashSet<String> keywords = new LinkedHashSet<>(EXPECTED_KEYWORDS.getOrDefault(normalizedType, List.of()));
        keywords.addAll(EXPECTED_KEYWORDS.get("OFFICIAL"));
        return List.copyOf(keywords);
    }

    /**
     * 返回营销落地页拦截词。
     * 当前先统一策略，后续如果不同 sourceType 需要细分，可以继续在这里扩展。
     */
    public List<String> marketingKeywords(String sourceType) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>(MARKETING_KEYWORDS.getOrDefault("OFFICIAL", List.of()));
        keywords.addAll(MARKETING_KEYWORDS.getOrDefault(normalizeType(sourceType), List.of()));
        return List.copyOf(keywords);
    }

    /**
     * 返回能够证明页面具备“信息价值”而非纯营销落地页属性的关键词。
     */
    public List<String> highValueInformationKeywords(String sourceType) {
        String normalizedType = normalizeType(sourceType);
        LinkedHashSet<String> keywords = new LinkedHashSet<>(HIGH_VALUE_INFORMATION_KEYWORDS.getOrDefault("OFFICIAL", List.of()));
        keywords.addAll(HIGH_VALUE_INFORMATION_KEYWORDS.getOrDefault(normalizedType, List.of()));
        keywords.addAll(EXPECTED_KEYWORDS.getOrDefault(normalizedType, List.of()));
        return List.copyOf(keywords);
    }

    private String normalizeType(String sourceType) {
        return sourceType == null ? "OFFICIAL" : sourceType.trim().toUpperCase(Locale.ROOT);
    }
}
