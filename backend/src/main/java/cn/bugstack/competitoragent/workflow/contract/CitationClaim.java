package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 引用核查中的单条声明契约。
 * 这个对象只保存“声明本身、它引用了哪些证据、它是否属于敏感判断”这几类稳定字段，
 * 不承载任何编排决策语义，避免后续 Citation Agent 和 Orchestrator 互相耦合。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CitationClaim {

    private String claimId;

    private String sectionKey;

    private String sectionTitle;

    private String claimText;

    @Builder.Default
    private List<String> evidenceIds = List.of();

    @Builder.Default
    private List<String> sourceUrls = List.of();

    private boolean traceabilitySensitive;

    private boolean explicitlyDowngraded;

    @Builder.Default
    private List<String> issueFlags = List.of();

    /**
     * 声明契约的标准化要点：
     * 1. 文本字段去空白，章节缺省回落到 report；
     * 2. 列表字段去重后转成不可变视图；
     * 3. 当没有直接来源时，显式打上缺少来源标记，避免静默丢失溯源信息。
     */
    public CitationClaim normalized() {
        List<String> normalizedEvidenceIds = normalizeTextList(evidenceIds);
        List<String> normalizedSourceUrls = normalizeTextList(sourceUrls);
        List<String> normalizedIssueFlags = normalizeIssueFlags(issueFlags);

        if (normalizedSourceUrls.isEmpty() && !normalizedIssueFlags.contains("MISSING_SOURCE_URL")) {
            normalizedIssueFlags.add("MISSING_SOURCE_URL");
        }
        if (normalizedEvidenceIds.isEmpty()
                && traceabilitySensitive
                && !normalizedIssueFlags.contains("UNKNOWN_EVIDENCE_ID")) {
            normalizedIssueFlags.add("UNKNOWN_EVIDENCE_ID");
        }

        String normalizedSectionKey = blankToDefault(sectionKey, "report");
        return this.toBuilder()
                .claimId(normalizeText(claimId))
                .sectionKey(normalizedSectionKey)
                .sectionTitle(blankToDefault(sectionTitle, normalizedSectionKey))
                .claimText(normalizeText(claimText))
                .evidenceIds(List.copyOf(normalizedEvidenceIds))
                .sourceUrls(List.copyOf(normalizedSourceUrls))
                .issueFlags(List.copyOf(normalizedIssueFlags))
                .build();
    }

    private List<String> normalizeTextList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String text = normalizeText(value);
                if (text != null) {
                    normalized.add(text);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> normalizeIssueFlags(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String flag = normalizeText(value);
                if (flag != null) {
                    normalized.add(flag.toUpperCase());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = normalizeText(value);
        return normalized == null ? fallback : normalized;
    }
}
