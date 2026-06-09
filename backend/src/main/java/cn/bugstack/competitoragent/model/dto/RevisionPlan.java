package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 质检失败后的修订计划
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "质检失败后的修订计划")
public class RevisionPlan {

    @Schema(description = "是否建议重写", example = "true")
    private boolean rewriteRequired;

    @Schema(description = "修订优先级摘要", example = "优先补齐证据，再修正文档结构")
    private String summary;

    @Schema(description = "需要修订的章节/问题清单")
    private List<RevisionItem> items;

    /**
     * 结构化修订指令。
     * 与传统 items 的区别在于，这里不仅说明“哪里有问题”，
     * 还会显式指出应该补源、重跑还是重写，从而驱动工作流闭环。
     */
    @Schema(description = "Machine-readable revision directives")
    private List<RevisionDirective> directives;

    @Schema(description = "下一轮写作要点")
    private List<String> rewriteGuidelines;

    /**
     * 统一把修订计划收敛为前端稳定可消费的结构。
     * 报告页主路径会直接渲染这里的 items / directives / rewriteGuidelines，
     * 因此需要在服务层兜底前先把空集合、重复文案和半结构化指令收口干净。
     */
    public RevisionPlan normalized() {
        return this.toBuilder()
                .summary(normalizeText(summary))
                .items(normalizeItems(items))
                .directives(normalizeDirectives(directives))
                .rewriteGuidelines(normalizeDistinctTexts(rewriteGuidelines))
                .build();
    }

    /**
     * 交付中心主路径需要一句可直接落到按钮或卡片里的下一步动作摘要，
     * 因此这里优先从 summary / directives / items / rewriteGuidelines 中选择最可读的一条。
     */
    public String primaryActionSummary() {
        if (normalizeText(summary) != null) {
            return normalizeText(summary);
        }
        if (directives != null) {
            for (RevisionDirective directive : directives) {
                if (directive == null) {
                    continue;
                }
                if (normalizeText(directive.getSummary()) != null) {
                    return normalizeText(directive.getSummary());
                }
                if (normalizeText(directive.getExpectedOutcome()) != null) {
                    return normalizeText(directive.getExpectedOutcome());
                }
            }
        }
        if (items != null) {
            for (RevisionItem item : items) {
                if (item != null && normalizeText(item.getSuggestion()) != null) {
                    return normalizeText(item.getSuggestion());
                }
            }
        }
        if (rewriteGuidelines != null) {
            for (String guideline : rewriteGuidelines) {
                if (normalizeText(guideline) != null) {
                    return normalizeText(guideline);
                }
            }
        }
        return null;
    }

    /**
     * 主路径摘要需要保留修订建议关联的来源链接，
     * 这里统一从 directives 中收口 sourceUrls，避免后续调用方重复遍历指令列表。
     */
    public List<String> primarySourceUrls() {
        if (directives == null || directives.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (RevisionDirective directive : directives) {
            if (directive == null || directive.getSourceUrls() == null) {
                continue;
            }
            for (String sourceUrl : directive.getSourceUrls()) {
                String normalized = normalizeText(sourceUrl);
                if (normalized != null) {
                    sourceUrls.add(normalized);
                }
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单条修订项")
    public static class RevisionItem {
        @Schema(description = "问题类型", example = "MISSING_EVIDENCE")
        private String type;

        @Schema(description = "对应章节", example = "功能对比")
        private String section;

        @Schema(description = "严重程度", example = "WARNING")
        private String severity;

        @Schema(description = "修订建议")
        private String suggestion;
    }

    private List<RevisionItem> normalizeItems(List<RevisionItem> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<RevisionItem> normalized = new ArrayList<>();
        for (RevisionItem value : values) {
            if (value == null) {
                continue;
            }
            normalized.add(new RevisionItem(
                    normalizeText(value.getType()),
                    normalizeText(value.getSection()),
                    normalizeText(value.getSeverity()),
                    normalizeText(value.getSuggestion())
            ));
        }
        return normalized;
    }

    private List<RevisionDirective> normalizeDirectives(List<RevisionDirective> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<RevisionDirective> normalized = new ArrayList<>();
        for (RevisionDirective value : values) {
            if (value != null) {
                normalized.add(value.normalized());
            }
        }
        return normalized;
    }

    private List<String> normalizeDistinctTexts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = normalizeText(value);
            if (text != null) {
                normalized.add(text);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
