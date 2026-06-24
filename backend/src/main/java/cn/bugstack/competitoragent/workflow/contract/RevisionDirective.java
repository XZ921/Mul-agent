package cn.bugstack.competitoragent.workflow.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 修订驱动指令。
 * <p>
 * 3.4 P1 之后，该对象只作为 Reviewer 兼容期展示/修订建议载体。
 * 新的动态补图不得再把 orchestrationAction 当作唯一正式决策来源，
 * 必须先经 OrchestrationDecisionAdapter 转成 OrchestrationDecision，
 * 再由 DecisionPolicyService 校验后交给 DecisionExecutorAdapter。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Quality review revision directive")
public class RevisionDirective {

    /** 指令类别：EVIDENCE_GAP / STRUCTURE_ISSUE / EXPRESSION_ISSUE / SEARCH_QUALITY */
    private String category;

    /** 建议动作类型：SUPPLEMENT_EVIDENCE / RERUN_NODE / REWRITE_SECTION / MANUAL_REVIEW */
    private String actionType;

    /** 编排层动作：CREATE_SUPPLEMENT_BRANCH / CREATE_RERUN_BRANCH / CREATE_REWRITE_BRANCH / MANUAL_ONLY */
    private String orchestrationAction;

    /** 优先级：HIGH / MEDIUM / LOW */
    private String priority;

    /** 建议回流到的静态节点标识 */
    private String targetNode;

    /** 指向需要修订的章节，便于前端与后续节点精确定位 */
    private String targetSection;

    /** 面向人的修订摘要 */
    private String summary;

    /**
     * 搜索质量反馈。
     * 当问题落在搜索覆盖、查询词命中质量或来源类型偏差时，
     * 这里明确告诉补证分支“为什么现有搜索结果还不够好”。
     */
    private String searchFeedback;

    /** 建议补源时使用的搜索词 */
    @Builder.Default
    private List<String> searchQueries = List.of();

    /** 与当前修订直接相关的来源链接 */
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 期望修订结果，用于解释补图闭环目标 */
    private String expectedOutcome;

    /**
     * 对外统一归一化修订指令。
     * 这样无论指令来自 Reviewer、规则兜底还是后续人工回填，
     * 下游拿到的都会是稳定的类别、动作、编排语义与来源线索。
     */
    public RevisionDirective normalized() {
        String normalizedCategory = normalizeCategory(category);
        String normalizedSection = normalizeText(targetSection);
        String normalizedSummary = normalizeText(summary);
        String normalizedFeedback = normalizeText(searchFeedback);
        String normalizedOutcome = normalizeText(expectedOutcome);
        String resolvedActionType = resolveActionType(normalizedCategory, actionType);

        return this.toBuilder()
                .category(normalizedCategory)
                .actionType(resolvedActionType)
                .orchestrationAction(resolveOrchestrationAction(normalizedCategory, orchestrationAction, resolvedActionType))
                .priority(resolvePriority(normalizedCategory, priority))
                .targetNode(resolveTargetNode(normalizedCategory, targetNode))
                .targetSection(normalizedSection)
                .summary(normalizedSummary == null ? buildSummary(normalizedCategory, normalizedSection) : normalizedSummary)
                .searchFeedback(resolveSearchFeedback(normalizedCategory, normalizedSection, normalizedFeedback))
                .searchQueries(normalizeDistinctList(searchQueries))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .expectedOutcome(normalizedOutcome == null ? buildExpectedOutcome(normalizedCategory, normalizedSection) : normalizedOutcome)
                .build();
    }

    private String normalizeCategory(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return "EVIDENCE_GAP";
        }
        String upper = normalized.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (upper.contains("SEARCH")) {
            return "SEARCH_QUALITY";
        }
        if (upper.contains("STRUCTURE") || upper.contains("SECTION")) {
            return "STRUCTURE_ISSUE";
        }
        if (upper.contains("EXPRESSION") || upper.contains("STYLE")) {
            return "EXPRESSION_ISSUE";
        }
        if (upper.contains("CLAIM") || upper.contains("EVIDENCE") || upper.contains("SUPPORT")) {
            return "EVIDENCE_GAP";
        }
        return upper;
    }

    private String resolveActionType(String normalizedCategory, String currentActionType) {
        String normalized = normalizeText(currentActionType);
        if (normalized != null) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        return switch (normalizedCategory) {
            case "STRUCTURE_ISSUE" -> "RERUN_NODE";
            case "EXPRESSION_ISSUE" -> "REWRITE_SECTION";
            case "SEARCH_QUALITY", "EVIDENCE_GAP" -> "SUPPLEMENT_EVIDENCE";
            default -> "MANUAL_REVIEW";
        };
    }

    private String resolveOrchestrationAction(String normalizedCategory,
                                              String currentOrchestrationAction,
                                              String resolvedActionType) {
        String normalized = normalizeText(currentOrchestrationAction);
        if (normalized != null) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        return switch (resolvedActionType) {
            case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
            case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
            default -> "SEARCH_QUALITY".equals(normalizedCategory) || "EVIDENCE_GAP".equals(normalizedCategory)
                    ? "CREATE_SUPPLEMENT_BRANCH"
                    : "MANUAL_ONLY";
        };
    }

    private String resolvePriority(String normalizedCategory, String currentPriority) {
        String normalized = normalizeText(currentPriority);
        if (normalized != null) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        return switch (normalizedCategory) {
            case "EXPRESSION_ISSUE" -> "MEDIUM";
            case "STRUCTURE_ISSUE", "SEARCH_QUALITY", "EVIDENCE_GAP" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    private String resolveTargetNode(String normalizedCategory, String currentTargetNode) {
        String normalized = normalizeText(currentTargetNode);
        if (normalized != null) {
            return normalized;
        }
        return switch (normalizedCategory) {
            case "STRUCTURE_ISSUE" -> "extract_schema";
            case "EXPRESSION_ISSUE" -> "rewrite_report";
            case "SEARCH_QUALITY", "EVIDENCE_GAP" -> "collect_sources";
            default -> "quality_check_final";
        };
    }

    private String resolveSearchFeedback(String normalizedCategory, String normalizedSection, String currentFeedback) {
        if ("SEARCH_QUALITY".equals(normalizedCategory)) {
            if (currentFeedback != null) {
                if (normalizedSection != null && !currentFeedback.contains(normalizedSection)) {
                    return normalizedSection + "：" + currentFeedback;
                }
                return currentFeedback;
            }
            return buildSearchFeedback(normalizedSection);
        }
        return currentFeedback;
    }

    private String buildSummary(String normalizedCategory, String normalizedSection) {
        String section = normalizedSection == null ? "相关章节" : normalizedSection;
        return switch (normalizedCategory) {
            case "SEARCH_QUALITY" -> "补充" + section + "的搜索证据";
            case "STRUCTURE_ISSUE" -> "重跑" + section + "的结构化抽取";
            case "EXPRESSION_ISSUE" -> "改写" + section + "的表述";
            default -> "补齐" + section + "的证据链路";
        };
    }

    private String buildExpectedOutcome(String normalizedCategory, String normalizedSection) {
        String section = normalizedSection == null ? "相关章节" : normalizedSection;
        return switch (normalizedCategory) {
            case "SEARCH_QUALITY" -> section + "需要补齐官网或高可信来源，并形成稳定可追溯的搜索证据链路。";
            case "STRUCTURE_ISSUE" -> section + "对应的字段抽取结果需要恢复完整，避免章节结构与字段语义继续漂移。";
            case "EXPRESSION_ISSUE" -> section + "需要改写为更克制、更可验证的表达，避免绝对化结论。";
            default -> section + "需要补齐来源引用，并让关键判断可以稳定回指到 evidenceId 或 sourceUrls。";
        };
    }

    private String buildSearchFeedback(String normalizedSection) {
        String section = normalizedSection == null ? "目标章节" : normalizedSection;
        return "当前搜索结果对" + section + "的覆盖不足，缺少稳定官网或高可信来源，请调整搜索查询并补采对应页面。";
    }

    private List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = normalizeText(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
