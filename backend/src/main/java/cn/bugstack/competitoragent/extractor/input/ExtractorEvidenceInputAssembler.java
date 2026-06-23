package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
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
 * 统一把 EvidenceSource 转成 extractor 内部输入投影，
 * 避免 Provider 再直接操作 repository entity 和 pageMetadata JSON。
 */
@Component
@RequiredArgsConstructor
public class ExtractorEvidenceInputAssembler {

    private final ObjectMapper objectMapper;

    public ExtractorEvidenceInput fromEvidenceSource(EvidenceSource evidence) {
        JsonNode metadata = readJson(evidence == null ? null : evidence.getPageMetadata());
        List<String> sourceUrls = readStringList(metadata.path("sourceUrls"));
        if (sourceUrls.isEmpty() && evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
            sourceUrls = List.of(evidence.getUrl().trim());
        }
        return ExtractorEvidenceInput.builder()
                .evidenceId(normalizeText(evidence == null ? null : evidence.getEvidenceId()))
                .competitorName(normalizeText(evidence == null ? null : evidence.getCompetitorName()))
                .sourceType(normalizeText(evidence == null ? null : evidence.getSourceType()))
                .title(normalizeText(evidence == null ? null : evidence.getTitle()))
                .content(firstNonBlank(evidence == null ? null : evidence.getFullContent(),
                        evidence == null ? null : evidence.getContentSnippet()))
                .sourceUrls(sourceUrls)
                .issueFlags(readStringList(metadata.path("issueFlags")))
                .qualitySignals(readStringList(metadata.path("qualitySignals")))
                .structuredBlocks(objectMapper.convertValue(metadata.path("structuredBlocks"),
                        new TypeReference<List<DownstreamEvidenceBlock>>() {
                        }))
                .structuredPayload(objectMapper.convertValue(metadata.path("structuredPayload"),
                        new TypeReference<Map<String, Object>>() {
                        }))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(metadata.path("qualityScore").isNumber() ? metadata.path("qualityScore").asDouble() : null)
                        .failureKind(metadata.path("failureKind").asText(null))
                        .durationMillis(metadata.path("durationMillis").isNumber() ? metadata.path("durationMillis").asLong() : null)
                        .build())
                .build()
                .normalized();
    }

    /**
     * pageMetadata 属于采集侧开放 JSON，容错读取避免单条脏数据阻断整个 extractor 输入组装。
     */
    private JsonNode readJson(String rawJson) {
        try {
            return rawJson == null || rawJson.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item == null ? null : item.asText(null);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            });
        }
        return new ArrayList<>(values);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? "" : second.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
