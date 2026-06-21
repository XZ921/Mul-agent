package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 采集证据给下游节点消费的统一运行期视图。
 * 该视图只负责把 pageMetadata 中的质量信号、结构化块、来源链接与正文兜底统一收口，
 * 不替代 EvidenceFragment / SectionEvidenceBundle 的字段级证据契约。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceView {

    private String evidenceId;

    private String competitorName;

    private String sourceType;

    private String title;

    private String content;

    @Builder.Default
    private List<String> sourceUrls = List.of();

    @Builder.Default
    private List<String> issueFlags = List.of();

    @Builder.Default
    private List<String> qualitySignals = List.of();

    @Builder.Default
    private List<DownstreamEvidenceBlock> structuredBlocks = List.of();

    @Builder.Default
    private Map<String, Object> structuredPayload = Map.of();

    private DownstreamEvidenceQuality quality;

    public DownstreamEvidenceView normalized() {
        LinkedHashSet<String> normalizedSourceUrls = normalizeValues(sourceUrls);
        LinkedHashSet<String> normalizedIssueFlags = normalizeValues(issueFlags);
        LinkedHashSet<String> normalizedQualitySignals = normalizeValues(qualitySignals);
        List<DownstreamEvidenceBlock> normalizedBlocks = new ArrayList<>();
        if (structuredBlocks != null) {
            for (DownstreamEvidenceBlock structuredBlock : structuredBlocks) {
                if (structuredBlock != null) {
                    normalizedBlocks.add(structuredBlock.normalized());
                }
            }
        }
        if (normalizedSourceUrls.isEmpty()) {
            normalizedIssueFlags.add("MISSING_SOURCE_URL");
        }
        return this.toBuilder()
                .evidenceId(normalizeText(evidenceId))
                .competitorName(normalizeText(competitorName))
                .sourceType(normalizeText(sourceType))
                .title(normalizeText(title))
                .content(content == null ? "" : content)
                .sourceUrls(new ArrayList<>(normalizedSourceUrls))
                .issueFlags(new ArrayList<>(normalizedIssueFlags))
                .qualitySignals(new ArrayList<>(normalizedQualitySignals))
                .structuredBlocks(normalizedBlocks)
                .structuredPayload(structuredPayload == null ? Map.of() : structuredPayload)
                .quality(quality == null ? DownstreamEvidenceQuality.builder().build().normalized() : quality.normalized())
                .build();
    }

    private LinkedHashSet<String> normalizeValues(List<String> values) {
        LinkedHashSet<String> normalizedValues = new LinkedHashSet<>();
        if (values == null) {
            return normalizedValues;
        }
        for (String value : values) {
            String normalized = normalizeText(value);
            if (normalized != null) {
                normalizedValues.add(normalized);
            }
        }
        return normalizedValues;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
