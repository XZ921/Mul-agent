package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 下游证据结构块。
 * 结构块保留采集阶段识别到的价格、文档、发布说明等高价值片段，
 * 让抽取、分析和质检能区分“正文存在”与“结构化证据足够”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceBlock {

    private String blockType;

    private String title;

    private String summary;

    private String content;

    private String qualitySignal;

    @Builder.Default
    private List<String> sourceUrls = List.of();

    public DownstreamEvidenceBlock normalized() {
        LinkedHashSet<String> normalizedSourceUrls = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    normalizedSourceUrls.add(sourceUrl.trim());
                }
            }
        }
        return this.toBuilder()
                .blockType(normalizeText(blockType))
                .title(normalizeText(title))
                .summary(normalizeText(summary))
                .content(normalizeText(content))
                .qualitySignal(normalizeText(qualitySignal))
                .sourceUrls(new ArrayList<>(normalizedSourceUrls))
                .build();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
