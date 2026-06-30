package cn.bugstack.competitoragent.workflow.coverage;

import lombok.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 字段 query 组合生成规则。
 * 它只根据字段、证据路径、查询意图、期望信号和来源类型生成检索视角，不为单个字段写 if 分支。
 */
@Component
public class FieldQueryComposition {

    private static final double MAX_TOKEN_OVERLAP = 0.7;
    private static final Set<String> THIRD_PARTY_TYPES = Set.of("REVIEW", "NEWS", "OPEN_WEB", "THIRD_PARTY_REVIEW");

    private static final Map<String, List<String>> SIGNAL_TERMS = Map.ofEntries(
            Map.entry("PRICING_BLOCK", List.of("定价", "收费", "套餐", "计费")),
            Map.entry("FEATURE_BLOCK", List.of("功能", "能力", "特性")),
            Map.entry("ECOSYSTEM_BLOCK", List.of("生态", "合作伙伴", "开发者生态")),
            Map.entry("DEVELOPER_DOCS_BLOCK", List.of("开发者文档", "API", "SDK", "接入")),
            Map.entry("POSITIONING_BLOCK", List.of("市场定位", "品牌定位")),
            Map.entry("SLOGAN_BLOCK", List.of("品牌口号", "产品介绍")),
            Map.entry("PROFILE_BLOCK", List.of("产品介绍", "平台简介")),
            Map.entry("PUBLIC_RISK_BLOCK", List.of("用户反馈", "媒体报道", "风险")),
            Map.entry("LIMITATION_OR_POLICY_BLOCK", List.of("限制", "条款", "协议", "规则"))
    );

    private static final Map<String, String> FIELD_TERMS = Map.ofEntries(
            Map.entry("summary", "产品概述"),
            Map.entry("positioning", "市场定位"),
            Map.entry("targetUsers", "目标用户"),
            Map.entry("coreFeatures", "开放平台 核心功能"),
            Map.entry("strengths", "优势"),
            Map.entry("pricing", "定价 收费"),
            Map.entry("weaknesses", "短板 风险")
    );

    private static final Map<String, List<String>> SOURCE_TERMS = Map.ofEntries(
            Map.entry("OFFICIAL", List.of("官方资料", "官网")),
            Map.entry("DOCS", List.of("官方文档", "开发者文档")),
            Map.entry("PRICING", List.of("定价页", "收费说明")),
            Map.entry("TERMS", List.of("服务协议", "条款")),
            Map.entry("POLICY", List.of("规则", "政策")),
            Map.entry("REVIEW", List.of("评测", "实测", "对比")),
            Map.entry("NEWS", List.of("行业分析", "媒体报道", "解读")),
            Map.entry("OPEN_WEB", List.of("教程", "用户反馈", "经验分享")),
            Map.entry("THIRD_PARTY_REVIEW", List.of("评测", "用户反馈", "行业分析"))
    );

    /**
     * 把一条证据路径组合成多个语义互补的检索视角。
     * 第三方视角是全字段通用的补充来源，不依赖 pricing/weaknesses 等特定字段语义。
     */
    public List<QueryVariant> compose(String competitorName,
                                      String fieldName,
                                      CoverageEvidencePath path,
                                      List<String> fallbackQueryIntents,
                                      List<String> fallbackSourceTypes) {
        String name = StringUtils.hasText(competitorName) ? competitorName.trim() : "";
        String fieldTerm = resolveFieldTerm(fieldName);
        String rawFieldName = StringUtils.hasText(fieldName) ? fieldName.trim() : fieldTerm;
        List<String> queryIntents = normalizeList(path == null ? null : path.getQueryIntents(), fallbackQueryIntents, "FIELD_EVIDENCE");
        List<String> sourceTypes = normalizeList(path == null ? null : path.getSourceTypes(), fallbackSourceTypes, "OPEN_WEB");
        List<String> signalTerms = resolveSignalTerms(path == null ? null : path.getExpectedSignals());
        queryIntents = expandIntentBySignals(queryIntents, path == null ? null : path.getExpectedSignals());

        List<QueryVariant> variants = new ArrayList<>();
        for (String sourceType : sourceTypes) {
            for (String queryIntent : queryIntents) {
                variants.add(buildVariant(name, rawFieldName, fieldTerm, sourceType, queryIntent, signalTerms, path));
            }
        }
        ensureThirdPartyVariant(variants, name, rawFieldName, fieldTerm, queryIntents, signalTerms, path);
        return deduplicateComplementary(variants);
    }

    private QueryVariant buildVariant(String competitorName,
                                      String rawFieldName,
                                      String fieldTerm,
                                      String sourceType,
                                      String queryIntent,
                                      List<String> signalTerms,
                                      CoverageEvidencePath path) {
        boolean thirdParty = isThirdParty(sourceType);
        List<String> sourceTerms = resolveSourceTerms(sourceType);
        String intentTerms = resolveIntentTerms(queryIntent);
        String coreSignals = resolveCoreSignals(queryIntent, signalTerms, thirdParty);
        String sourceWords = joinFirstTerms(sourceTerms, 3);
        String query = compact(competitorName + " " + fieldTerm + " " + intentTerms + " " + coreSignals + " " + sourceWords);
        String reason = "根据字段 " + rawFieldName + " 语义、路径 " + safePathKey(path) + "、意图 " + queryIntent
                + " 和来源类型 " + sourceType + " 组合生成检索视角";
        return new QueryVariant(queryIntent, sourceType, query, reason);
    }

    private void ensureThirdPartyVariant(List<QueryVariant> variants,
                                         String competitorName,
                                         String rawFieldName,
                                         String fieldTerm,
                                         List<String> queryIntents,
                                         List<String> signalTerms,
                                         CoverageEvidencePath path) {
        boolean hasThirdParty = variants.stream().anyMatch(variant -> isThirdParty(variant.getSourceType()));
        if (hasThirdParty) {
            return;
        }
        String queryIntent = queryIntents.isEmpty() ? "THIRD_PARTY_REVIEW" : queryIntents.get(0);
        String query = compact(competitorName + " " + fieldTerm + " " + joinFirstTerms(signalTerms, 2)
                + " 评测 实测 对比 教程 用户反馈 解读 行业分析");
        String reason = "为字段 " + rawFieldName + "（" + fieldTerm + "）补充第三方视角，避免官方来源成为排他门槛";
        variants.add(new QueryVariant(queryIntent, "OPEN_WEB", query, reason + "，路径 " + safePathKey(path)));
    }

    private List<QueryVariant> deduplicateComplementary(List<QueryVariant> variants) {
        List<QueryVariant> result = new ArrayList<>();
        LinkedHashSet<String> seenQueries = new LinkedHashSet<>();
        for (QueryVariant variant : variants) {
            if (!StringUtils.hasText(variant.getQuery()) || seenQueries.contains(variant.getQuery())) {
                continue;
            }
            boolean tooSimilar = false;
            for (QueryVariant accepted : result) {
                if (tokenOverlap(variant.getQuery(), accepted.getQuery()) >= MAX_TOKEN_OVERLAP) {
                    tooSimilar = true;
                    break;
                }
            }
            if (!tooSimilar || isThirdParty(variant.getSourceType())) {
                result.add(variant);
                seenQueries.add(variant.getQuery());
            }
        }
        return result;
    }

    private List<String> resolveSignalTerms(List<String> expectedSignals) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (expectedSignals != null) {
            for (String signal : expectedSignals) {
                if (!StringUtils.hasText(signal)) {
                    continue;
                }
                List<String> mapped = SIGNAL_TERMS.get(signal.trim().toUpperCase(Locale.ROOT));
                if (mapped != null) {
                    terms.addAll(mapped);
                }
            }
        }
        if (terms.isEmpty()) {
            terms.add("公开资料");
            terms.add("说明");
        }
        return new ArrayList<>(terms);
    }

    private List<String> resolveSourceTerms(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return List.of("公开资料");
        }
        return SOURCE_TERMS.getOrDefault(sourceType.trim().toUpperCase(Locale.ROOT), List.of("公开资料"));
    }

    private String resolveIntentTerms(String queryIntent) {
        if (!StringUtils.hasText(queryIntent)) {
            return "";
        }
        String normalized = queryIntent.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("SDK")) {
            return "SDK 接入指南";
        }
        if (normalized.contains("API")) {
            return "API 文档";
        }
        if (normalized.contains("TERMS")) {
            return "服务协议 条款";
        }
        if (normalized.contains("BILLING")) {
            return "API 调用限制 计费";
        }
        if (normalized.contains("PRICING")) {
            return "定价 套餐";
        }
        if (normalized.contains("POLICY") || normalized.contains("RISK")) {
            return "规则 风险";
        }
        if (normalized.contains("POSITION") || normalized.contains("MARKET")) {
            return "市场定位";
        }
        if (normalized.contains("THIRD")) {
            return "第三方评测";
        }
        return queryIntent.replace('_', ' ');
    }

    private String resolveCoreSignals(String queryIntent, List<String> signalTerms, boolean thirdParty) {
        if (!StringUtils.hasText(queryIntent)) {
            return joinFirstTerms(signalTerms, thirdParty ? 2 : 3);
        }
        String normalized = queryIntent.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("SDK")) {
            return "接入指南 示例";
        }
        if (normalized.contains("API")) {
            return "接口 文档 能力";
        }
        if (normalized.contains("TERMS")) {
            return "协议 条款 规则";
        }
        if (normalized.contains("BILLING")) {
            return "额度 限制 费用";
        }
        return joinFirstTerms(signalTerms, thirdParty ? 2 : 3);
    }

    private String resolveFieldTerm(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return "字段证据";
        }
        return FIELD_TERMS.getOrDefault(fieldName.trim(), fieldName.trim());
    }

    private List<String> normalizeList(List<String> values, List<String> fallback, String defaultValue) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addAll(normalized, values);
        if (normalized.isEmpty()) {
            addAll(normalized, fallback);
        }
        if (normalized.isEmpty()) {
            normalized.add(defaultValue);
        }
        return new ArrayList<>(normalized);
    }

    private List<String> expandIntentBySignals(List<String> queryIntents, List<String> expectedSignals) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(queryIntents == null ? List.of() : queryIntents);
        if (expectedSignals != null) {
            for (String signal : expectedSignals) {
                if (StringUtils.hasText(signal)
                        && signal.trim().toUpperCase(Locale.ROOT).contains("LIMITATION_OR_POLICY_BLOCK")) {
                    expanded.add("TERMS_BILLING");
                }
            }
        }
        return new ArrayList<>(expanded);
    }

    private void addAll(LinkedHashSet<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                target.add(value.trim());
            }
        }
    }

    private String joinFirstTerms(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        return String.join(" ", terms.stream().limit(limit).toList());
    }

    private boolean isThirdParty(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return false;
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        return THIRD_PARTY_TYPES.stream().anyMatch(normalized::contains);
    }

    private String safePathKey(CoverageEvidencePath path) {
        return path == null || !StringUtils.hasText(path.getPathKey()) ? "UNKNOWN_PATH" : path.getPathKey();
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private double tokenOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String query) {
        Set<String> tokens = new HashSet<>();
        if (query == null) {
            return tokens;
        }
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s，,]+")) {
            if (StringUtils.hasText(token)) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    @Value
    public static class QueryVariant {
        String queryIntent;
        String sourceType;
        String query;
        String reason;
    }
}
