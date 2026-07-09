package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阶段1首报统一契约。
 * 这个类是“友好降级”的唯一事实源：哪些字段能阻塞首报、哪些字段只能延期审计、
 * 字段和报告章节如何映射、collector quorum 需要哪些来源，都必须从这里读取，
 * 避免各层再次硬编码 pricing / risk。
 */
public final class StageOneFirstReportPolicy {

    public static final Set<String> FIRST_REPORT_CRITICAL_FIELDS = Set.of(
            "summary",
            "positioning",
            "targetusers",
            "corefeatures"
    );

    public static final Set<String> FIRST_REPORT_ENHANCEMENT_FIELDS = Set.of(
            "pricing",
            "strengths",
            "weaknesses",
            "risk",
            "risks"
    );

    public static final Set<String> DEFAULT_SOURCE_FAMILIES = Set.of(
            "OFFICIAL",
            "DOCS",
            "REVIEW"
    );

    public static final List<String> DEFAULT_SOURCE_SCOPES = List.of(
            "OFFICIAL",
            "DOCS",
            "NEWS",
            "REVIEW"
    );

    private static final Map<String, String> SECTION_FIELD_ALIASES = Map.ofEntries(
            Map.entry("产品概览", "summary"),
            Map.entry("产品简介", "summary"),
            Map.entry("市场定位", "positioning"),
            Map.entry("定位分析", "positioning"),
            Map.entry("目标用户", "targetUsers"),
            Map.entry("用户画像", "targetUsers"),
            Map.entry("目标用户对比", "targetUsers"),
            Map.entry("核心能力", "coreFeatures"),
            Map.entry("核心功能", "coreFeatures"),
            Map.entry("功能对比", "coreFeatures"),
            Map.entry("定价策略", "pricing"),
            Map.entry("价格策略", "pricing"),
            Map.entry("定价对比", "pricing"),
            Map.entry("定位对比", "positioning"),
            Map.entry("优势判断", "strengths"),
            Map.entry("优势分析", "strengths"),
            Map.entry("短板与风险", "weaknesses"),
            Map.entry("短板分析", "weaknesses"),
            Map.entry("风险判断", "risk")
    );

    private static final Map<String, String> FIELD_NAME_ALIASES = Map.ofEntries(
            Map.entry("summary", "summary"),
            Map.entry("overview", "summary"),
            Map.entry("positioning", "positioning"),
            Map.entry("positioningcomparison", "positioning"),
            Map.entry("targetusers", "targetusers"),
            Map.entry("targetusercomparison", "targetusers"),
            Map.entry("targetuserscomparison", "targetusers"),
            Map.entry("corefeatures", "corefeatures"),
            Map.entry("features", "corefeatures"),
            Map.entry("featurecomparison", "corefeatures"),
            Map.entry("pricing", "pricing"),
            Map.entry("pricingcomparison", "pricing"),
            Map.entry("strengths", "strengths"),
            Map.entry("strengthssummary", "strengths"),
            Map.entry("weaknesses", "weaknesses"),
            Map.entry("weaknessessummary", "weaknesses"),
            Map.entry("risk", "risk"),
            Map.entry("risks", "risk"),
            Map.entry("riskssummary", "weaknesses")
    );

    private static final Set<String> OPTIONAL_EVIDENCE_GAP_FLAGS = Set.of(
            "OPTIONAL_FIELD_DEFERRED",
            "OPTIONAL_EVIDENCE_GAP",
            "OPTIONAL_SECTION_EVIDENCE_GAP",
            "OPTIONAL_CITATION_GAP",
            "OPTIONAL_PRICING_NOT_READY",
            "OPTIONAL_PRICING_ANALYSIS_DEFERRED",
            "OPTIONAL_STRENGTHS_ANALYSIS_DEFERRED",
            "OPTIONAL_WEAKNESSES_ANALYSIS_DEFERRED"
    );

    private StageOneFirstReportPolicy() {
    }

    /**
     * 判断字段是否属于阶段1首报的核心阻断字段。
     * 这里只允许核心认知闭环字段阻断首报，增强字段必须走延期/审计语义。
     */
    public static boolean isFirstReportCriticalField(String fieldName) {
        String normalizedField = normalizeFieldName(fieldName);
        if (!StringUtils.hasText(normalizedField)) {
            return false;
        }
        return FIRST_REPORT_CRITICAL_FIELDS.contains(normalizedField);
    }

    /**
     * 判断字段是否属于阶段1增强字段。
     * 增强字段可以继续采集和展示，但不能拉起首报阻断链路。
     */
    public static boolean isFirstReportEnhancementField(String fieldName) {
        String normalizedField = normalizeFieldName(fieldName);
        if (!StringUtils.hasText(normalizedField)) {
            return false;
        }
        return FIRST_REPORT_ENHANCEMENT_FIELDS.contains(normalizedField);
    }

    /**
     * 把不同阶段的字段命名统一归一到阶段1基础字段名。
     * 这样 Extractor / Analyzer / Writer 即使传入的是 featureComparison、pricingComparison
     * 这类阶段内字段，也能稳定回落到 coreFeatures / pricing 这套首报契约。
     */
    public static String normalizeFieldName(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return null;
        }
        String normalized = fieldName.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return FIELD_NAME_ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * 统一把报告章节映射回字段名。
     * 这样 Reviewer / Writer / ReportDiagnosis 都能回到同一套字段契约，
     * 而不是各自维护章节到字段的硬编码判断表。
     */
    public static String fieldForSection(String sectionTitle) {
        if (!StringUtils.hasText(sectionTitle)) {
            return null;
        }
        String trimmed = sectionTitle.trim();
        String direct = SECTION_FIELD_ALIASES.get(trimmed);
        if (direct != null) {
            return direct;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : SECTION_FIELD_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 判断章节是否会阻断阶段1首报。
     * 章节阻断规则必须完全委托给字段契约，避免章节层面偷偷长出第二套口径。
     */
    public static boolean isFirstReportBlockingSection(String sectionTitle) {
        return isFirstReportCriticalField(fieldForSection(sectionTitle));
    }

    /**
     * 判断 issue flag 是否属于增强字段缺口。
     * 这些标记可以进入审计和降级摘要，但不能被当作核心证据阻断。
     */
    public static boolean isOptionalEvidenceGapFlag(String issueFlag) {
        if (!StringUtils.hasText(issueFlag)) {
            return false;
        }
        return OPTIONAL_EVIDENCE_GAP_FLAGS.contains(issueFlag.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 判断来源家族是否属于阶段1默认 collector 家族。
     * 这里保留 PRICING 之外的核心入口，后续 quorum 和默认 scope 都应以这套语义为基准。
     */
    public static boolean isStageOneDefaultSourceFamily(String sourceFamily) {
        if (!StringUtils.hasText(sourceFamily)) {
            return false;
        }
        return DEFAULT_SOURCE_FAMILIES.contains(sourceFamily.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 返回阶段1默认来源 scope。
     * 这里和 collector quorum 的 family 语义分开维护：
     * NEWS/REVIEW 允许作为默认补充来源进入计划，但不等于 quorum 必选家族。
     */
    public static List<String> defaultSourceScopes() {
        return DEFAULT_SOURCE_SCOPES;
    }

    /**
     * 统一归一来源 scope。
     * 规划层、来源发现层和 Tavily 执行层都必须走这里，
     * 避免“定价页/公开测评”在不同层被解释成不同 source family。
     */
    public static String normalizeSourceScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "OPEN_WEB";
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if (containsAny(normalized, List.of("官网", "official", "home"))) {
            return "OFFICIAL";
        }
        if (containsAny(normalized, List.of("文档", "doc", "help", "guide"))) {
            return "DOCS";
        }
        if (containsAny(normalized, List.of("价格", "定价", "pricing", "plan"))) {
            return "PRICING";
        }
        if (containsAny(normalized, List.of("博客", "新闻", "blog", "news", "changelog"))) {
            return "NEWS";
        }
        if (containsAny(normalized, List.of("测评", "评价", "review", "g2", "capterra"))) {
            return "REVIEW";
        }
        return scope.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 归一用户请求的来源 scope；当用户未显式填写时，回退到阶段1默认 scope。
     * 这样 SourceDiscovery / Tavily 的默认入口与首报契约保持一致。
     */
    public static List<String> normalizeRequestedSourceScopes(List<String> requestedScopes) {
        return normalizeRequestedSourceScopes(requestedScopes, true);
    }

    /**
     * 只归一用户显式填写的来源 scope，不做默认补全。
     * 字段级增强 query 是否进入计划，需要依赖这个“显式意图”而不是内部默认值。
     */
    public static List<String> normalizeExplicitSourceScopes(List<String> requestedScopes) {
        return normalizeRequestedSourceScopes(requestedScopes, false);
    }

    /**
     * 统一判断阶段1 collector quorum 是否达标。
     * 阶段1只要求具备可交付的官方/文档主来源，加上 sourceUrls 红线，
     * 明确不再把 PRICING 家族当成首报准入条件。
     */
    public static boolean isQuorumReady(List<String> satisfiedFamilies, List<String> sourceUrls) {
        LinkedHashSet<String> families = normalizeFamilies(satisfiedFamilies);
        return hasPrimarySourceFamily(families)
                && hasEnoughTraceableSources(sourceUrls);
    }

    /**
     * 判断是否已经具备首报需要的主来源家族。
     * 阶段1只要 OFFICIAL 或 DOCS 之一已经成形，就可以继续结合 sourceUrls 红线做放行判断。
     */
    public static boolean hasPrimarySourceFamily(Set<String> families) {
        return families != null && (families.contains("OFFICIAL") || families.contains("DOCS"));
    }

    /**
     * 判断是否满足 sourceUrls 红线。
     * 这里会把 www/root host 做归一，防止同一站点的多个 URL 伪装成独立来源域。
     */
    public static boolean hasEnoughTraceableSources(List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.size() < 5) {
            return false;
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String sourceUrl : sourceUrls) {
            try {
                String host = URI.create(sourceUrl).getHost();
                if (StringUtils.hasText(host)) {
                    domains.add(normalizeDomain(host));
                }
            } catch (Exception ignored) {
                // 非法 URL 不能撑过 sourceUrls 红线。
            }
        }
        return domains.size() >= 2;
    }

    /**
     * 归一来源域名。
     * 当前先处理 www/root host 伪分裂问题，后续如果需要扩展 eTLD+1 规则，也仍应集中收口在这里。
     */
    private static String normalizeDomain(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("www.")) {
            normalized = normalized.substring("www.".length());
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 归一 collector 家族名。
     * 避免调用方因为大小写或空白差异，绕过统一的 quorum 契约判断。
     */
    private static LinkedHashSet<String> normalizeFamilies(List<String> families) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (families != null) {
            for (String family : families) {
                if (StringUtils.hasText(family)) {
                    normalized.add(family.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return normalized;
    }

    private static List<String> normalizeRequestedSourceScopes(List<String> requestedScopes,
                                                               boolean defaultWhenEmpty) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requestedScopes != null) {
            for (String requestedScope : requestedScopes) {
                if (StringUtils.hasText(requestedScope)) {
                    normalized.add(normalizeSourceScope(requestedScope));
                }
            }
        }
        if (normalized.isEmpty() && defaultWhenEmpty) {
            normalized.addAll(DEFAULT_SOURCE_SCOPES);
        }
        return normalized.isEmpty() ? List.of() : new ArrayList<>(normalized);
    }

    private static boolean containsAny(String value, List<String> candidates) {
        if (!StringUtils.hasText(value) || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
