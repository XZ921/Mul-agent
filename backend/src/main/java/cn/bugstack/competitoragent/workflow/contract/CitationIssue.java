package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Citation Agent 发现的问题契约。
 * 这个对象承接“哪条声明、哪条证据、什么问题、当前来源是否足够”四类信息，
 * 方便后续把问题交给 Orchestrator 做可回放决策。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CitationIssue {

    private String issueId;

    private String issueType;

    private String severity;

    private String targetSection;

    private String claimId;

    private String evidenceId;

    private String summary;

    @Builder.Default
    private List<String> sourceUrls = List.of();

    private String evidenceState;

    @Builder.Default
    private List<String> suggestedQueries = List.of();

    @Builder.Default
    private List<String> issueFlags = List.of();

    /**
     * 问题契约的标准化要点：
     * 1. 文本字段统一裁剪；
     * 2. 列表字段去重并保留输入顺序；
     * 3. 当来源为空时，必须显式保留可追溯的缺口语义。
     */
    public CitationIssue normalized() {
        List<String> normalizedSourceUrls = normalizeTextList(sourceUrls);
        List<String> normalizedSuggestedQueries = normalizeTextList(suggestedQueries);
        List<String> normalizedIssueFlags = normalizeIssueFlags(issueFlags);

        if (normalizedSourceUrls.isEmpty() && !normalizedIssueFlags.contains("MISSING_SOURCE_URL")) {
            normalizedIssueFlags.add("MISSING_SOURCE_URL");
        }
        if (normalizeText(evidenceId) == null && !normalizedIssueFlags.contains("UNKNOWN_EVIDENCE_ID")) {
            normalizedIssueFlags.add("UNKNOWN_EVIDENCE_ID");
        }

        String normalizedEvidenceState = blankToDefault(evidenceState,
                normalizedSourceUrls.isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE");
        String normalizedSeverity = blankToDefault(severity, normalizedSourceUrls.isEmpty() ? "ERROR" : "MEDIUM");
        String normalizedSection = blankToDefault(targetSection, "report");

        return this.toBuilder()
                .issueId(normalizeText(issueId))
                .issueType(blankToDefault(issueType, "UNKNOWN_ISSUE"))
                .severity(normalizedSeverity.toUpperCase())
                .targetSection(normalizedSection)
                .claimId(normalizeText(claimId))
                .evidenceId(normalizeText(evidenceId))
                .summary(blankToDefault(summary, "引用核查发现问题"))
                .sourceUrls(List.copyOf(normalizedSourceUrls))
                .evidenceState(normalizedEvidenceState.toUpperCase())
                .suggestedQueries(List.copyOf(normalizedSuggestedQueries))
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
