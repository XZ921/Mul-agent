package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.CitationSourceTrustFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 来源可信度第一版规则策略。
 * 这里只根据 EvidenceSource 元数据做可复现的规则评分，不调用外部抓取，也不引入模型推断。
 */
@Component
public class CitationSourceTrustPolicy {

    /**
     * 按第一版稳定规则给单条证据来源分层：
     * 1. 官方/文档/定价/GitHub 且没有低质风险，判定为 HIGH_TRUST；
     * 2. 来源类型明确且内容不薄，判定为 MEDIUM_TRUST；
     * 3. 低分、未知类型、缺 URL、内容太薄直接判定为 LOW_TRUST。
     */
    public CitationSourceTrustFinding evaluate(EvidenceSource evidenceSource) {
        EvidenceSource source = evidenceSource == null ? EvidenceSource.builder().build() : evidenceSource;

        String url = normalizeText(source.getUrl());
        String sourceType = normalizeText(source.getSourceType());
        String sourceCategory = normalizeText(source.getSourceCategory());
        String sourceDomain = normalizeText(source.getSourceDomain());
        Double sourceScore = source.getSourceScore();
        boolean hasThinContent = isBlank(source.getContentSnippet()) && isBlank(source.getFullContent());
        boolean hasMissingUrl = isBlank(url);
        boolean hasUnknownType = isBlank(sourceType) || "UNKNOWN".equalsIgnoreCase(sourceType);
        boolean hasLowScore = sourceScore != null && sourceScore < 0.45d;

        List<String> issueFlags = new ArrayList<>();
        if (hasMissingUrl) {
            issueFlags.add("MISSING_SOURCE_URL");
        }
        if (hasUnknownType) {
            issueFlags.add("UNKNOWN_SOURCE_TYPE");
        }
        if (hasLowScore) {
            issueFlags.add("LOW_SOURCE_SCORE");
        }
        if (hasThinContent) {
            issueFlags.add("THIN_SOURCE_CONTENT");
        }

        boolean isOfficialTier = isOfficialType(sourceType) || "OFFICIAL".equalsIgnoreCase(sourceCategory);
        boolean hasLowRisk = hasMissingUrl || hasUnknownType || hasLowScore || hasThinContent;

        String trustTier;
        if (isOfficialTier && !hasLowScore && !hasThinContent && !hasMissingUrl && !hasUnknownType) {
            trustTier = "HIGH_TRUST";
        } else if (hasLowRisk) {
            trustTier = "LOW_TRUST";
        } else {
            trustTier = "MEDIUM_TRUST";
        }

        return CitationSourceTrustFinding.builder()
                .evidenceId(normalizeText(source.getEvidenceId()))
                .url(url)
                .sourceDomain(sourceDomain)
                .sourceType(sourceType)
                .sourceCategory(sourceCategory)
                .sourceScore(sourceScore)
                .trustTier(trustTier)
                .summary(buildSummary(trustTier, sourceType, sourceScore, issueFlags))
                .sourceUrls(url == null ? List.of() : List.of(url))
                .issueFlags(issueFlags)
                .build()
                .normalized();
    }

    private boolean isOfficialType(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        return "OFFICIAL".equals(normalized)
                || "DOCS".equals(normalized)
                || "PRICING".equals(normalized)
                || "GITHUB".equals(normalized);
    }

    private String buildSummary(String trustTier,
                                String sourceType,
                                Double sourceScore,
                                List<String> issueFlags) {
        if ("HIGH_TRUST".equals(trustTier)) {
            return "官方或高可信来源，且当前未见低质风险。";
        }
        if ("LOW_TRUST".equals(trustTier)) {
            return "来源可信度偏低，建议优先补充更稳定的官方证据。";
        }
        return "来源可信度中等";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
