package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Writer 阶段发现的章节引用缺口。
 * 该对象只描述“哪一章缺引用、缺什么、当前有哪些来源”，不能直接表达补采、重写或人工介入动作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WriterCitationGap {

    /** 稳定章节键，例如 report_conclusion / pricing / recommendations。 */
    private String targetSection;

    /** 给用户和回放展示的章节标题。 */
    private String sectionTitle;

    /** 缺口摘要，说明为什么这一章还不能被视为完整引用。 */
    private String summary;

    /** 缺口严重程度：NONE / HIGH / ERROR。 */
    private String severity;

    /** 当前章节已有来源；为空时必须配合 evidenceState=MISSING_SOURCE。 */
    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 证据状态：FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE。 */
    private String evidenceState;

    /** 当前章节仍缺少引用支撑的字段。 */
    @Builder.Default
    private List<String> missingFields = List.of();

    /** 后续补证可使用的检索提示，不等同执行动作。 */
    @Builder.Default
    private List<String> suggestedQueries = List.of();

    /**
     * 统一规范化 Writer 章节缺口，确保：
     * 1. 文本字段去空白；
     * 2. 列表字段去重；
     * 3. evidenceState / severity 在调用方未显式填写时仍有稳定默认值。
     */
    public WriterCitationGap normalized() {
        List<String> normalizedSourceUrls = normalize(sourceUrls);
        List<String> normalizedMissingFields = normalize(missingFields);
        String resolvedEvidenceState = evidenceState == null || evidenceState.isBlank()
                ? (normalizedSourceUrls.isEmpty() ? "MISSING_SOURCE" : "PARTIAL_SOURCE")
                : evidenceState.trim().toUpperCase();
        String resolvedSeverity = severity == null || severity.isBlank()
                ? (normalizedSourceUrls.isEmpty() ? "ERROR" : "HIGH")
                : severity.trim().toUpperCase();
        return toBuilder()
                .targetSection(blankToDefault(targetSection, "report"))
                .sectionTitle(blankToDefault(sectionTitle, targetSection))
                .summary(blankToDefault(summary, "Writer 发现章节引用缺口，需要补充来源或重写引用。"))
                .severity(resolvedSeverity)
                .sourceUrls(normalizedSourceUrls)
                .evidenceState(resolvedEvidenceState)
                .missingFields(normalizedMissingFields)
                .suggestedQueries(normalize(suggestedQueries))
                .build();
    }

    private List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String blankToDefault(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null || fallback.isBlank() ? "report" : fallback.trim();
    }
}
