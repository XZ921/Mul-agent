package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 覆盖契约解析器。
 * 它根据报告模板、分析维度和来源范围推导当前任务该强检哪些字段、
 * 哪些字段只是可选或当前不在范围内，并生成统一的字段级证据路径定义。
 */
@Component
public class CoverageContractResolver {

    private final AnalysisDimensionMappingCatalog mappingCatalog;

    public CoverageContractResolver(AnalysisDimensionMappingCatalog mappingCatalog) {
        this.mappingCatalog = mappingCatalog;
    }

    /**
     * 解析当前任务的覆盖契约。
     * 第一版优先满足 task66 的字段契约收口诉求，因此这里先把 taskMode、字段状态、
     * 阻断级别和最小证据路径做稳定输出，后续阶段再继续补充更细粒度的 evidence plan。
     */
    public CoverageContract resolve(String reportTemplate,
                                    List<String> analysisDimensions,
                                    List<String> sourceScope,
                                    String overrideSource) {
        String taskMode = resolveTaskMode(reportTemplate, analysisDimensions);
        List<AnalysisDimensionMapping> mappings = mappingCatalog.resolve(analysisDimensions, sourceScope);
        Map<String, CoverageFieldContract> fields = new LinkedHashMap<>();

        // 先铺默认字段，保证上下游始终能读到完整字段集，而不是只看到局部命中字段。
        seedDefaultContracts(taskMode, fields);

        // 再按显式维度命中的结构化映射覆盖字段状态和证据路径。
        for (AnalysisDimensionMapping mapping : mappings) {
            applyMapping(fields, mapping, taskMode);
        }

        // 标准版报告优先级最高，仍然保留全量核心字段强检语义。
        if (isStandardTemplate(reportTemplate)) {
            upgradeStandardReportFields(fields);
        }

        return CoverageContract.builder()
                .taskMode(taskMode)
                .contractVersion("coverage-" + taskMode.toLowerCase(Locale.ROOT) + "-v1")
                .source(StringUtils.hasText(overrideSource) ? overrideSource : "PLANNER")
                .fields(new ArrayList<>(fields.values()))
                .build();
    }

    /**
     * 根据模板和维度推断任务模式。
     * task66 的关键是把“能力介绍型任务”从“标准全量竞品报告”中分离出来，
     * 避免 pricing/weaknesses 被默认当成所有任务都必须完成的 blocker。
     */
    private String resolveTaskMode(String reportTemplate, List<String> analysisDimensions) {
        if (isStandardTemplate(reportTemplate)) {
            return "STANDARD_COMPETITOR_REPORT";
        }
        if (containsAny(analysisDimensions, "定价", "价格", "套餐", "计费", "商业化")) {
            return "PRICING_ANALYSIS";
        }
        if (containsAny(analysisDimensions, "风险", "短板", "劣势", "限制", "合规", "协议", "审核", "规则")) {
            return "RISK_ANALYSIS";
        }
        return "CAPABILITY_INTRO";
    }

    /**
     * 初始化默认字段契约。
     * capability-intro 任务只强检概述、定位、目标用户和核心功能，
     * pricing/weaknesses 默认降级，strengths 保留为可选。
     */
    private void seedDefaultContracts(String taskMode, Map<String, CoverageFieldContract> fields) {
        fields.put("summary", requiredCapabilityField("summary", "能力介绍任务要求基础概述字段"));
        fields.put("positioning", requiredCapabilityField("positioning", "能力介绍任务要求定位字段"));
        fields.put("targetUsers", requiredCapabilityField("targetUsers", "能力介绍任务要求目标用户字段"));
        fields.put("coreFeatures", CoverageFieldContract.builder()
                .field("coreFeatures")
                .status(CoverageFieldStatus.REQUIRED)
                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                .targetEvidenceTypes(List.of("FEATURE_BLOCK"))
                .queryIntents(List.of("OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE"))
                .evidencePaths(List.of(
                        evidencePath("DOCS_API_GUIDE",
                                List.of("DOCS", "OFFICIAL"),
                                List.of("API_DOCS", "SDK_GUIDE"),
                                List.of("DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"),
                                true),
                        thirdPartyPath("FEATURE_BLOCK")))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(2)
                .allowOfficialOnly(true)
                .overrideReason("能力介绍任务要求核心功能字段")
                .build());
        fields.put("strengths", CoverageFieldContract.builder()
                .field("strengths")
                .status(CoverageFieldStatus.OPTIONAL)
                .blockingLevel(CoverageBlockingLevel.WARNING)
                .targetEvidenceTypes(List.of("FEATURE_BLOCK", "ECOSYSTEM_BLOCK"))
                .queryIntents(List.of("OFFICIAL_DOCS"))
                .minimumAttemptedPaths(0)
                .minDistinctEvidenceCount(0)
                .allowOfficialOnly(true)
                .overrideReason("能力介绍任务可选输出优势字段")
                .build());
        fields.put("pricing", CoverageFieldContract.builder()
                .field("pricing")
                .status(CoverageFieldStatus.OUT_OF_SCOPE)
                .blockingLevel(CoverageBlockingLevel.NONE)
                .targetEvidenceTypes(List.of("PRICING_BLOCK"))
                .queryIntents(List.of())
                .evidencePaths(List.of())
                .minimumAttemptedPaths(0)
                .minDistinctEvidenceCount(0)
                .allowOfficialOnly(true)
                .overrideReason("taskMode=" + taskMode + " 默认不强检定价")
                .build());
        fields.put("weaknesses", CoverageFieldContract.builder()
                .field("weaknesses")
                .status(CoverageFieldStatus.OUT_OF_SCOPE)
                .blockingLevel(CoverageBlockingLevel.NONE)
                .targetEvidenceTypes(List.of("LIMITATION_OR_POLICY_BLOCK"))
                .queryIntents(List.of())
                .evidencePaths(List.of())
                .minimumAttemptedPaths(0)
                .minDistinctEvidenceCount(0)
                .allowOfficialOnly(false)
                .overrideReason("taskMode=" + taskMode + " 默认不强检短板字段")
                .build());
    }

    /**
     * 应用显式维度映射。
     * 这里将“显式维度要求”提升为高优先级字段契约，覆盖默认的 capability-intro 降级结果。
     */
    private void applyMapping(Map<String, CoverageFieldContract> fields,
                              AnalysisDimensionMapping mapping,
                              String taskMode) {
        if (mapping == null || mapping.getTargetFields() == null) {
            return;
        }
        for (String fieldName : mapping.getTargetFields()) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            fields.put(fieldName, buildFieldFromMapping(fieldName, mapping, taskMode));
        }
    }

    /**
     * 从结构化映射构造字段契约。
     * 当前先把查询意图和常用证据路径写入字段契约，保证 Planner/Collector/Reviewer 使用的是同一份来源语义。
     */
    private CoverageFieldContract buildFieldFromMapping(String fieldName,
                                                        AnalysisDimensionMapping mapping,
                                                        String taskMode) {
        List<CoverageEvidencePath> evidencePaths = buildEvidencePaths(fieldName, mapping);
        return CoverageFieldContract.builder()
                .field(fieldName)
                .status(mapping.isRequiredByDefault() ? CoverageFieldStatus.REQUIRED : CoverageFieldStatus.OPTIONAL)
                .blockingLevel(mapping.isRequiredByDefault() ? CoverageBlockingLevel.BLOCKER : CoverageBlockingLevel.WARNING)
                .targetEvidenceTypes(new ArrayList<>(defaultIfNull(mapping.getEvidencePathKeys())))
                .queryIntents(new ArrayList<>(defaultIfNull(mapping.getQueryIntents())))
                .evidencePaths(evidencePaths)
                .minimumAttemptedPaths(Math.max(1, evidencePaths.size()))
                .minDistinctEvidenceCount(Math.min(2, Math.max(1, evidencePaths.size())))
                .allowOfficialOnly(!"weaknesses".equalsIgnoreCase(fieldName))
                .overrideReason("显式维度命中 " + mapping.getDimensionKey() + "，覆盖默认 taskMode=" + taskMode + " 契约")
                .build();
    }

    /**
     * 标准报告模式直接提升定价、优势和短板为强检字段。
     * 这是规格里“显式模板优先级最高”的落地实现。
     */
    private void upgradeStandardReportFields(Map<String, CoverageFieldContract> fields) {
        fields.put("pricing", CoverageFieldContract.builder()
                .field("pricing")
                .status(CoverageFieldStatus.REQUIRED)
                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                .targetEvidenceTypes(List.of("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS"))
                .queryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING"))
                .evidencePaths(List.of(
                        evidencePath("OFFICIAL_PRICING_PAGE",
                                List.of("PRICING", "OFFICIAL"),
                                List.of("OFFICIAL_PRICING"),
                                List.of("PRICING_BLOCK"),
                                true),
                        evidencePath("DOCS_BILLING_OR_LIMITS",
                                List.of("DOCS", "OFFICIAL"),
                                List.of("DOCS_BILLING"),
                                List.of("PRICING_BLOCK", "LIMITATION_OR_POLICY_BLOCK"),
                                true),
                        thirdPartyPath("PRICING_BLOCK")))
                .minimumAttemptedPaths(2)
                .minDistinctEvidenceCount(2)
                .allowOfficialOnly(true)
                .overrideReason("显式模板要求标准版报告必须覆盖定价字段")
                .build());
        fields.put("strengths", CoverageFieldContract.builder()
                .field("strengths")
                .status(CoverageFieldStatus.REQUIRED)
                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                .targetEvidenceTypes(List.of("FEATURE_BLOCK", "ECOSYSTEM_BLOCK"))
                .queryIntents(List.of("OFFICIAL_DOCS"))
                .evidencePaths(List.of(
                        evidencePath("OFFICIAL_PUBLIC_PROFILE",
                                List.of("OFFICIAL", "DOCS"),
                                List.of("OFFICIAL_DOCS"),
                                List.of("FEATURE_BLOCK", "ECOSYSTEM_BLOCK"),
                                true),
                        thirdPartyPath("FEATURE_BLOCK", "ECOSYSTEM_BLOCK")))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(1)
                .allowOfficialOnly(true)
                .overrideReason("显式模板要求标准版报告必须覆盖优势字段")
                .build());
        fields.put("weaknesses", CoverageFieldContract.builder()
                .field("weaknesses")
                .status(CoverageFieldStatus.REQUIRED)
                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                .targetEvidenceTypes(List.of("TERMS_OR_SERVICE_AGREEMENT", "POLICY_LIMITATION", "PUBLIC_REVIEW_OR_NEWS"))
                .queryIntents(List.of("POLICY", "RISK", "THIRD_PARTY_REVIEW"))
                .evidencePaths(List.of(
                        evidencePath("TERMS_OR_SERVICE_AGREEMENT",
                                List.of("TERMS", "POLICY"),
                                List.of("POLICY"),
                                List.of("LIMITATION_OR_POLICY_BLOCK"),
                                true),
                        evidencePath("PUBLIC_REVIEW_OR_NEWS",
                                List.of("REVIEW", "NEWS"),
                                List.of("THIRD_PARTY_REVIEW", "RISK"),
                                List.of("PUBLIC_RISK_BLOCK"),
                                false)))
                .minimumAttemptedPaths(2)
                .minDistinctEvidenceCount(2)
                .allowOfficialOnly(false)
                .overrideReason("显式模板要求标准版报告必须覆盖短板字段")
                .build());
    }

    /**
     * 构造字段的最小证据路径。
     * 这里做轻量映射，优先输出 task66 规格中明确点名的 pathKey。
     */
    private List<CoverageEvidencePath> buildEvidencePaths(String fieldName, AnalysisDimensionMapping mapping) {
        if ("pricing".equalsIgnoreCase(fieldName)) {
            return List.of(
                    evidencePath("OFFICIAL_PRICING_PAGE",
                            List.of("PRICING", "OFFICIAL"),
                            List.of("OFFICIAL_PRICING"),
                            List.of("PRICING_BLOCK"),
                            true),
                    evidencePath("DOCS_BILLING_OR_LIMITS",
                            List.of("DOCS", "OFFICIAL"),
                            List.of("DOCS_BILLING"),
                            List.of("PRICING_BLOCK", "LIMITATION_OR_POLICY_BLOCK"),
                            true),
                    thirdPartyPath("PRICING_BLOCK"));
        }
        if ("weaknesses".equalsIgnoreCase(fieldName)) {
            return List.of(
                    evidencePath("TERMS_OR_SERVICE_AGREEMENT",
                            List.of("TERMS", "POLICY"),
                            List.of("POLICY"),
                            List.of("LIMITATION_OR_POLICY_BLOCK"),
                            true),
                    evidencePath("PUBLIC_REVIEW_OR_NEWS",
                            List.of("REVIEW", "NEWS"),
                            List.of("THIRD_PARTY_REVIEW", "RISK"),
                            List.of("PUBLIC_RISK_BLOCK"),
                            false));
        }
        if ("coreFeatures".equalsIgnoreCase(fieldName)) {
            return List.of(
                    evidencePath("DOCS_API_GUIDE",
                            List.of("DOCS", "OFFICIAL"),
                            List.of("API_DOCS", "SDK_GUIDE"),
                            List.of("DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"),
                            true),
                    thirdPartyPath("FEATURE_BLOCK"));
        }
        return List.of(
                evidencePath("OFFICIAL_PUBLIC_PROFILE",
                        officialSourceTypes(mapping.getSourceTypes()),
                        officialQueryIntents(mapping.getQueryIntents()),
                        List.of("FEATURE_BLOCK", "PROFILE_BLOCK"),
                        true),
                thirdPartyPath("FEATURE_BLOCK", "PROFILE_BLOCK"));
    }

    /**
     * 构造单条证据路径对象。
     * 统一在这里设置 required 和 success/failure 描述，避免不同字段手写时风格不一致。
     */
    private CoverageEvidencePath evidencePath(String pathKey,
                                              List<String> sourceTypes,
                                              List<String> queryIntents,
                                              List<String> expectedSignals,
                                              boolean required) {
        return CoverageEvidencePath.builder()
                .pathKey(pathKey)
                .sourceTypes(new ArrayList<>(defaultIfNull(sourceTypes)))
                .queryIntents(new ArrayList<>(defaultIfNull(queryIntents)))
                .expectedSignals(new ArrayList<>(defaultIfNull(expectedSignals)))
                .required(required)
                .successCriteria("命中可追溯公开证据")
                .failureStatus(required ? "EVIDENCE_NOT_COVERING" : "OPTIONAL_MISSING")
                .build();
    }

    /**
     * 构造全字段通用的第三方补充路径。
     * 该路径不阻断交付，但允许测评、新闻、教程等高质量第三方材料进入字段证据候选。
     */
    private CoverageEvidencePath thirdPartyPath(String... expectedSignals) {
        return evidencePath("PUBLIC_REVIEW_OR_NEWS",
                List.of("REVIEW", "NEWS", "OPEN_WEB"),
                List.of("THIRD_PARTY_REVIEW"),
                expectedSignals == null ? List.of("FEATURE_BLOCK") : List.of(expectedSignals),
                false);
    }

    /**
     * 构造能力介绍默认必填字段。
     * 这些字段是 capability-intro 任务当前阶段稳定交付的核心字段。
     */
    private CoverageFieldContract requiredCapabilityField(String field, String reason) {
        return CoverageFieldContract.builder()
                .field(field)
                .status(CoverageFieldStatus.REQUIRED)
                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                .targetEvidenceTypes(List.of("OFFICIAL_PUBLIC_PROFILE"))
                .queryIntents(List.of("OFFICIAL_DOCS"))
                .evidencePaths(List.of(
                        evidencePath("OFFICIAL_PUBLIC_PROFILE",
                                List.of("OFFICIAL", "DOCS"),
                                List.of("OFFICIAL_DOCS"),
                                List.of("FEATURE_BLOCK", "PROFILE_BLOCK"),
                                true),
                        thirdPartyPath("FEATURE_BLOCK", "PROFILE_BLOCK")))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(1)
                .allowOfficialOnly(true)
                .overrideReason(reason)
                .build();
    }

    /**
     * 标准版模板判定。
     * 当前工程里默认模板值是“标准版”，这里兼容中英文常见写法，降低模板字符串变动带来的耦合。
     */
    private boolean isStandardTemplate(String reportTemplate) {
        if (!StringUtils.hasText(reportTemplate)) {
            return false;
        }
        String normalized = reportTemplate.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("标准")
                || normalized.contains("standard")
                || normalized.contains("competitor_report");
    }

    /**
     * 判断分析维度中是否命中任一关键词。
     * 用于任务模式的快速推断，真正的字段映射仍然交给 catalog 统一收口。
     */
    private boolean containsAny(List<String> values, String... terms) {
        if (values == null || terms == null) {
            return false;
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> defaultIfNull(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> officialSourceTypes(List<String> sourceTypes) {
        List<String> official = defaultIfNull(sourceTypes).stream()
                .filter(type -> !"REVIEW".equalsIgnoreCase(type)
                        && !"NEWS".equalsIgnoreCase(type)
                        && !"OPEN_WEB".equalsIgnoreCase(type)
                        && !"THIRD_PARTY_REVIEW".equalsIgnoreCase(type))
                .toList();
        return official.isEmpty() ? List.of("OFFICIAL", "DOCS") : official;
    }

    private List<String> officialQueryIntents(List<String> queryIntents) {
        List<String> official = defaultIfNull(queryIntents).stream()
                .filter(intent -> !"THIRD_PARTY_REVIEW".equalsIgnoreCase(intent))
                .toList();
        return official.isEmpty() ? List.of("OFFICIAL_DOCS") : official;
    }
}
