package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * extractor 内部专用输入投影。
 * 这里只有 extractor 可以持有正文和 structuredPayload；
 * 一旦进入 shared output 或下游节点，必须再投影为轻量视图。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ExtractorEvidenceInput {

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

    public ExtractorEvidenceInput normalized() {
        return this.toBuilder()
                .evidenceId(normalizeText(evidenceId))
                .competitorName(normalizeText(competitorName))
                .sourceType(normalizeText(sourceType))
                .title(normalizeText(title))
                .content(content == null ? "" : content)
                .sourceUrls(new ArrayList<>(new LinkedHashSet<>(sourceUrls == null ? List.of() : sourceUrls)))
                .issueFlags(new ArrayList<>(new LinkedHashSet<>(issueFlags == null ? List.of() : issueFlags)))
                .qualitySignals(new ArrayList<>(new LinkedHashSet<>(qualitySignals == null ? List.of() : qualitySignals)))
                .structuredBlocks(structuredBlocks == null ? List.of() : structuredBlocks)
                .structuredPayload(structuredPayload == null ? Map.of() : structuredPayload)
                .quality(quality == null ? DownstreamEvidenceQuality.builder().build().normalized() : quality.normalized())
                .build();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
