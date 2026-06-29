package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分析维度映射目录。
 * 当前实现仍然基于关键词匹配，但关键词、目标字段、证据路径和来源类型都集中在这里管理，
 * 后续无论迁移到配置化还是更强的分类器，都不需要改 CoverageContractResolver 主流程。
 */
@Component
public class AnalysisDimensionMappingCatalog {

    /**
     * 根据分析维度和来源范围解析映射。
     * 这里先只输出被当前任务显式命中的维度映射，避免把 pricing/weaknesses 这类高难字段
     * 无差别注入到所有 capability-intro 任务里。
     */
    public List<AnalysisDimensionMapping> resolve(List<String> analysisDimensions,
                                                  List<String> sourceScope) {
        List<AnalysisDimensionMapping> mappings = new ArrayList<>();
        if (matches(analysisDimensions, "定价", "价格", "套餐", "计费", "商业化")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("PRICING_ANALYSIS")
                    .matchedTerms(List.of("定价", "价格", "套餐", "计费", "商业化"))
                    .targetFields(List.of("pricing"))
                    .evidencePathKeys(List.of("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS", "TERMS_OR_SERVICE_AGREEMENT"))
                    .sourceTypes(List.of("PRICING", "DOCS", "OFFICIAL"))
                    .queryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING", "TERMS_BILLING"))
                    .requiredByDefault(true)
                    .priority(100)
                    .reason("显式分析维度要求定价字段")
                    .build());
        }
        if (matches(analysisDimensions, "风险", "短板", "劣势", "限制", "合规", "协议", "审核", "规则")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("WEAKNESS_ANALYSIS")
                    .matchedTerms(List.of("风险", "短板", "劣势", "限制", "合规", "协议", "审核", "规则"))
                    .targetFields(List.of("weaknesses"))
                    .evidencePathKeys(List.of("TERMS_OR_SERVICE_AGREEMENT", "POLICY_LIMITATION", "PUBLIC_REVIEW_OR_NEWS"))
                    .sourceTypes(List.of("TERMS", "POLICY", "REVIEW", "NEWS"))
                    .queryIntents(List.of("POLICY", "RISK", "THIRD_PARTY_REVIEW"))
                    .requiredByDefault(true)
                    .priority(90)
                    .reason("显式分析维度要求风险或短板字段")
                    .build());
        }
        if (matches(analysisDimensions, "产品功能", "开放平台", "开发者生态", "API", "SDK", "文档", "能力")) {
            mappings.add(AnalysisDimensionMapping.builder()
                    .dimensionKey("CAPABILITY_INTRO")
                    .matchedTerms(List.of("产品功能", "开放平台", "开发者生态", "API", "SDK", "文档", "能力"))
                    .targetFields(List.of("summary", "positioning", "targetUsers", "coreFeatures"))
                    .evidencePathKeys(List.of("OFFICIAL_PUBLIC_PROFILE", "DOCS_API_GUIDE"))
                    .sourceTypes(List.of("OFFICIAL", "DOCS"))
                    .queryIntents(List.of("OFFICIAL_DOCS", "API_DOCS", "SDK_GUIDE"))
                    .requiredByDefault(true)
                    .priority(80)
                    .reason("能力介绍维度要求产品概述和核心功能字段")
                    .build());
        }
        return mappings;
    }

    /**
     * 关键词匹配统一收口在目录内部。
     * 这样 resolver 不需要知道任何具体词汇，只消费结构化映射结果。
     */
    private boolean matches(List<String> values, String... terms) {
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
}
