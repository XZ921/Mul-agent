package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 单条证据来源可信度判定结果。
 * 这里不做任何抓取或二次事实核验，只保存规则层能够稳定复现的可信度判断与风险标记。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CitationSourceTrustFinding {

    private String evidenceId;

    private String url;

    private String sourceDomain;

    private String sourceType;

    private String sourceCategory;

    private Double sourceScore;

    private String trustTier;

    private String summary;

    @Builder.Default
    private List<String> sourceUrls = List.of();

    @Builder.Default
    private List<String> issueFlags = List.of();

    /**
     * 可信度判定结果的标准化要点：
     * 1. 文本字段去空白；
     * 2. 来源链接去重后保留不可变视图；
     * 3. 没有来源时显式标出缺口，避免把空对象误当作可靠证据。
     */
    public CitationSourceTrustFinding normalized() {
        List<String> normalizedSourceUrls = normalizeTextList(sourceUrls);
        List<String> normalizedIssueFlags = normalizeIssueFlags(issueFlags);

        if (normalizedSourceUrls.isEmpty() && !normalizedIssueFlags.contains("MISSING_SOURCE_URL")) {
            normalizedIssueFlags.add("MISSING_SOURCE_URL");
        }
        if (normalizeText(evidenceId) == null && !normalizedIssueFlags.contains("UNKNOWN_EVIDENCE_ID")) {
            normalizedIssueFlags.add("UNKNOWN_EVIDENCE_ID");
        }

        String resolvedTrustTier = blankToDefault(trustTier,
                normalizedSourceUrls.isEmpty() ? "LOW_TRUST" : "MEDIUM_TRUST");
        return this.toBuilder()
                .evidenceId(normalizeText(evidenceId))
                .url(normalizeText(url))
                .sourceDomain(normalizeText(sourceDomain))
                .sourceType(normalizeText(sourceType))
                .sourceCategory(normalizeText(sourceCategory))
                .sourceScore(sourceScore)
                .trustTier(resolvedTrustTier.toUpperCase())
                .summary(blankToDefault(summary, "来源可信度评估完成"))
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
