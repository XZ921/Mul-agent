package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 下游证据视图装配器。
 * 所有下游节点都应通过这里把 EvidenceSource 转成 DownstreamEvidenceView，
 * 避免 extractor、analyzer、report 各自解析 pageMetadata 导致字段漂移。
 */
@Component
@RequiredArgsConstructor
public class DownstreamEvidenceViewAssembler {

    private final ObjectMapper objectMapper;

    public List<DownstreamEvidenceView> fromEvidenceSources(List<EvidenceSource> evidences) {
        List<DownstreamEvidenceView> views = new ArrayList<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence != null) {
                views.add(fromEvidenceSource(evidence));
            }
        }
        return views;
    }

    public DownstreamEvidenceView fromEvidenceSource(EvidenceSource evidence) {
        JsonNode metadata = readJson(evidence == null ? null : evidence.getPageMetadata());
        List<String> sourceUrls = readStringList(metadata.path("sourceUrls"));
        if (sourceUrls.isEmpty() && evidence != null && hasText(evidence.getUrl())) {
            sourceUrls = List.of(evidence.getUrl().trim());
        }
        return DownstreamEvidenceView.builder()
                .evidenceId(evidence == null ? null : evidence.getEvidenceId())
                .competitorName(evidence == null ? null : evidence.getCompetitorName())
                .sourceType(firstNonBlank(evidence == null ? null : evidence.getSourceType(), readText(metadata.path("sourceType"))))
                .title(evidence == null ? null : evidence.getTitle())
                .content(firstNonBlank(evidence == null ? null : evidence.getFullContent(),
                        evidence == null ? null : evidence.getContentSnippet()))
                .sourceUrls(sourceUrls)
                .issueFlags(readStringList(metadata.path("issueFlags")))
                .qualitySignals(readStringList(metadata.path("qualitySignals")))
                .structuredBlocks(readStructuredBlocks(metadata.path("structuredBlocks"), sourceUrls))
                .structuredPayload(readObject(metadata.path("structuredPayload")))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(readDouble(metadata.path("qualityScore")))
                        .failureKind(readText(metadata.path("failureKind")))
                        .durationMillis(readLong(metadata.path("durationMillis")))
                        .build())
                .build()
                .normalized();
    }

    private JsonNode readJson(String json) {
        if (!hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item == null ? null : item.asText(null);
                if (hasText(value)) {
                    values.add(value.trim());
                }
            }
        }
        return new ArrayList<>(values);
    }

    private List<DownstreamEvidenceBlock> readStructuredBlocks(JsonNode node, List<String> fallbackSourceUrls) {
        List<DownstreamEvidenceBlock> blocks = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return blocks;
        }
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            List<String> blockSourceUrls = readStringList(item.path("sourceUrls"));
            blocks.add(DownstreamEvidenceBlock.builder()
                    .blockType(readText(item.path("blockType")))
                    .title(readText(item.path("title")))
                    .summary(firstNonBlank(readText(item.path("summary")), readText(item.path("content"))))
                    .content(readText(item.path("content")))
                    .qualitySignal(readText(item.path("qualitySignal")))
                    .sourceUrls(blockSourceUrls.isEmpty() ? fallbackSourceUrls : blockSourceUrls)
                    .build()
                    .normalized());
        }
        return blocks;
    }

    private Map<String, Object> readObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }

    private String firstNonBlank(String first, String second) {
        if (hasText(first)) {
            return first.trim();
        }
        return hasText(second) ? second.trim() : "";
    }

    private String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private Double readDouble(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asDouble();
    }

    private Long readLong(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asLong();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
