package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 章节级证据束契约。
 * 这个对象负责把“字段证据片段 -> 章节证据聚合 -> 结论级引用”之间的语义边界稳定下来：
 * 1. evidenceFragments 保留字段级片段，继续携带 evidenceId / sourceUrl / coverageStatus；
 * 2. sourceUrls 与 fieldNames 是章节级汇总视图，便于报告接口直接展示“这一段内容主要来自哪里”；
 * 3. missingFields / gapSummary / issueFlags 显式描述证据缺口，避免章节只有文本没有缺口说明。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SectionEvidenceBundle {

    /** 证据束产生阶段，例如 COLLECT / EXTRACT / ANALYZE / WRITE */
    private String stage;

    /** SECTION 表示常规章节，CONCLUSION 表示结论/建议类摘要段落 */
    private String sectionType;

    /** 章节稳定键，用于跨阶段识别同一语义段落 */
    private String sectionKey;

    /** 章节展示标题，供前端和报告接口直接显示 */
    private String sectionTitle;

    /** 当前章节或结论段落的摘要文本 */
    private String summary;

    /** 当前章节的证据缺口说明，如果为空会在规范化时自动生成兜底描述 */
    private String gapSummary;

    /** 章节内涉及到的字段名集合，例如 pricing / recommendations */
    @Builder.Default
    private List<String> fieldNames = List.of();

    /** 当前章节仍缺少稳定证据支撑的字段名集合 */
    @Builder.Default
    private List<String> missingFields = List.of();

    /** 章节级聚合后的来源链接，必要时会从 evidenceFragments 里自动回填 */
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 章节级问题标记，例如 SECTION_EVIDENCE_GAP / NO_USABLE_EVIDENCE */
    @Builder.Default
    private List<String> issueFlags = List.of();

    /** 字段级证据片段明细，作为章节级语义的最小可回溯单元 */
    @Builder.Default
    private List<EvidenceFragment> evidenceFragments = List.of();

    /**
     * 统一规范化章节证据束：
     * 1. 去重并清洗字段、URL、问题标记；
     * 2. 自动从 evidenceFragments 回填字段名与 sourceUrls；
     * 3. 当缺口存在但描述缺失时，生成稳定的 gapSummary；
     * 4. 当整个章节没有任何可用来源时，显式补上 NO_USABLE_EVIDENCE。
     */
    public SectionEvidenceBundle normalized() {
        List<EvidenceFragment> normalizedFragments = normalizeEvidenceFragments(evidenceFragments);
        LinkedHashSet<String> normalizedFieldNames = normalizeTextValues(fieldNames);
        LinkedHashSet<String> normalizedSourceUrls = normalizeTextValues(sourceUrls);
        LinkedHashSet<String> normalizedMissingFields = normalizeTextValues(missingFields);
        LinkedHashSet<String> normalizedIssueFlags = normalizeTextValues(issueFlags);

        for (EvidenceFragment fragment : normalizedFragments) {
            if (fragment.getFieldName() != null && !fragment.getFieldName().isBlank()) {
                normalizedFieldNames.add(fragment.getFieldName().trim());
            }
            if (fragment.getSourceUrl() != null && !fragment.getSourceUrl().isBlank()) {
                normalizedSourceUrls.add(fragment.getSourceUrl().trim());
            }
        }

        if (!normalizedMissingFields.isEmpty()) {
            normalizedIssueFlags.add("SECTION_EVIDENCE_GAP");
        }
        if (normalizedSourceUrls.isEmpty()) {
            normalizedIssueFlags.add("NO_USABLE_EVIDENCE");
        }

        String normalizedGapSummary = normalizeNullableText(gapSummary);
        if ((normalizedGapSummary == null || normalizedGapSummary.isBlank()) && !normalizedMissingFields.isEmpty()) {
            normalizedGapSummary = "缺少字段证据：" + String.join(", ", normalizedMissingFields);
        }
        if ((normalizedGapSummary == null || normalizedGapSummary.isBlank()) && normalizedSourceUrls.isEmpty()) {
            normalizedGapSummary = "当前章节暂无可用证据来源";
        }

        return this.toBuilder()
                .stage(normalizeNullableText(stage))
                .sectionType(normalizeNullableText(sectionType) == null ? "SECTION" : normalizeNullableText(sectionType))
                .sectionKey(normalizeNullableText(sectionKey))
                .sectionTitle(normalizeNullableText(sectionTitle))
                .summary(normalizeNullableText(summary))
                .gapSummary(normalizedGapSummary)
                .fieldNames(new ArrayList<>(normalizedFieldNames))
                .missingFields(new ArrayList<>(normalizedMissingFields))
                .sourceUrls(new ArrayList<>(normalizedSourceUrls))
                .issueFlags(new ArrayList<>(normalizedIssueFlags))
                .evidenceFragments(normalizedFragments)
                .build();
    }

    private List<EvidenceFragment> normalizeEvidenceFragments(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments == null ? List.<EvidenceFragment>of() : fragments) {
            if (fragment != null) {
                normalized.add(fragment.normalized());
            }
        }
        return normalized;
    }

    private LinkedHashSet<String> normalizeTextValues(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String text = normalizeNullableText(value);
            if (text != null && !text.isBlank()) {
                normalized.add(text);
            }
        }
        return normalized;
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
