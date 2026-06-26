package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Citation Agent 的总输出契约。
 * 这个对象负责把声明、问题、可信度结果与整体证据状态收口成一个可回放、可审计的结果视图。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CitationCheckResult {

    @Builder.Default
    private String contractVersion = "1.0";

    private String checkedSourceNode;

    private String citationEvidenceState;

    private String citationRiskSeverity;

    private Double citationCoverageRate;

    @Builder.Default
    private List<CitationClaim> claims = List.of();

    @Builder.Default
    private List<CitationIssue> citationIssues = List.of();

    @Builder.Default
    private List<CitationSourceTrustFinding> sourceCredibilityFindings = List.of();

    @Builder.Default
    private List<String> sourceUrls = List.of();

    private String evidenceState;

    @Builder.Default
    private List<String> issueFlags = List.of();

    /**
     * 总结果的标准化要点：
     * 1. 嵌套契约先各自归一化，再汇总成不可变列表；
     * 2. sourceUrls 为空时，整体结果必须显式标出缺来源；
     * 3. 证据状态和风险等级要给出稳定默认值，避免空对象进入后续决策链。
     */
    public CitationCheckResult normalized() {
        List<CitationClaim> normalizedClaims = normalizeClaims(claims);
        List<CitationIssue> normalizedIssues = normalizeIssues(citationIssues);
        List<CitationSourceTrustFinding> normalizedFindings = normalizeFindings(sourceCredibilityFindings);
        List<String> normalizedSourceUrls = normalizeTextList(sourceUrls);
        List<String> normalizedIssueFlags = normalizeIssueFlags(issueFlags);

        if (normalizedSourceUrls.isEmpty() && !normalizedIssueFlags.contains("MISSING_SOURCE_URL")) {
            normalizedIssueFlags.add("MISSING_SOURCE_URL");
        }

        String normalizedEvidenceState = blankToDefault(evidenceState,
                normalizedSourceUrls.isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE");
        String normalizedCitationEvidenceState = blankToDefault(citationEvidenceState, normalizedEvidenceState);
        String normalizedRiskSeverity = blankToDefault(citationRiskSeverity,
                normalizedSourceUrls.isEmpty() ? "ERROR" : "NONE");

        Double normalizedCoverageRate = citationCoverageRate;
        if (normalizedCoverageRate == null && normalizedSourceUrls.isEmpty()) {
            normalizedCoverageRate = 0.0d;
        }

        return this.toBuilder()
                .contractVersion(blankToDefault(contractVersion, "1.0"))
                .checkedSourceNode(normalizeText(checkedSourceNode))
                .citationEvidenceState(normalizedCitationEvidenceState.toUpperCase())
                .citationRiskSeverity(normalizedRiskSeverity.toUpperCase())
                .citationCoverageRate(normalizedCoverageRate)
                .claims(List.copyOf(normalizedClaims))
                .citationIssues(List.copyOf(normalizedIssues))
                .sourceCredibilityFindings(List.copyOf(normalizedFindings))
                .sourceUrls(List.copyOf(normalizedSourceUrls))
                .evidenceState(normalizedEvidenceState.toUpperCase())
                .issueFlags(List.copyOf(normalizedIssueFlags))
                .build();
    }

    private List<CitationClaim> normalizeClaims(List<CitationClaim> values) {
        List<CitationClaim> normalized = new ArrayList<>();
        if (values != null) {
            for (CitationClaim value : values) {
                if (value != null) {
                    normalized.add(value.normalized());
                }
            }
        }
        return normalized;
    }

    private List<CitationIssue> normalizeIssues(List<CitationIssue> values) {
        List<CitationIssue> normalized = new ArrayList<>();
        if (values != null) {
            for (CitationIssue value : values) {
                if (value != null) {
                    normalized.add(value.normalized());
                }
            }
        }
        return normalized;
    }

    private List<CitationSourceTrustFinding> normalizeFindings(List<CitationSourceTrustFinding> values) {
        List<CitationSourceTrustFinding> normalized = new ArrayList<>();
        if (values != null) {
            for (CitationSourceTrustFinding value : values) {
                if (value != null) {
                    normalized.add(value.normalized());
                }
            }
        }
        return normalized;
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
