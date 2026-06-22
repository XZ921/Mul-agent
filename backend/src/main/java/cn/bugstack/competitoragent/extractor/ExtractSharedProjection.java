package cn.bugstack.competitoragent.extractor;

import cn.bugstack.competitoragent.extractor.input.ExtractorInputPackage;
import cn.bugstack.competitoragent.workflow.contract.CompetitorKnowledgeDraft;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.FeatureItem;
import cn.bugstack.competitoragent.workflow.contract.PricingItem;
import cn.bugstack.competitoragent.workflow.contract.StrengthWeaknessItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * extract_schema 对下游共享的轻量事实投影。
 * 这里只保留 analyzer / recovery 真正需要的结构化字段与来源索引，
 * 禁止把完整正文继续塞进 shared output envelope。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractSharedProjection {

    public static final String PROJECTION_TYPE = "EXTRACT_SHARED_PROJECTION_V1";

    private String projectionType;
    private String contractVersion;
    private Integer totalCompetitors;
    private Integer successCount;
    private List<Map<String, Object>> results;
    private List<CompetitorKnowledgeDraft> drafts;
    private List<String> sourceUrls;
    private List<String> issueFlags;
    private List<DownstreamEvidenceView> downstreamEvidenceViews;
    private ExtractorInputPackage extractorInput;

    public static boolean supportsExtractorOutput(ObjectMapper objectMapper, String rawOutput) {
        JsonNode output = readOutput(objectMapper, rawOutput);
        if (output == null || !output.isObject()) {
            return false;
        }
        return output.has("drafts")
                || output.has("extractorInput")
                || output.has("downstreamEvidenceViews")
                || output.has("evidenceFragments");
    }

    public static ExtractSharedProjection fromExtractorOutput(ObjectMapper objectMapper, String rawOutput) {
        try {
            JsonNode output = objectMapper.readTree(rawOutput);
            List<CompetitorKnowledgeDraft> drafts = ExtractSharedOutputSanitizer.slimDrafts(
                    readDrafts(objectMapper, output.path("drafts")));
            List<DownstreamEvidenceView> downstreamEvidenceViews = ExtractSharedOutputSanitizer.slimEvidenceViews(
                    readEvidenceViews(objectMapper, output.path("downstreamEvidenceViews")));
            List<Map<String, Object>> results = ExtractSharedOutputSanitizer.slimResultSummaries(
                    readResultsSummary(objectMapper, output.path("results")),
                    objectMapper);
            ExtractorInputPackage extractorInput = ExtractSharedOutputSanitizer.slimExtractorInputPackage(
                    readExtractorInput(objectMapper, output.path("extractorInput")));
            return ExtractSharedProjection.builder()
                    .projectionType(PROJECTION_TYPE)
                    .contractVersion(textOrNull(output, "contractVersion"))
                    .totalCompetitors(numberOrNull(output, "totalCompetitors"))
                    .successCount(numberOrNull(output, "successCount"))
                    .results(results)
                    .drafts(drafts)
                    .sourceUrls(readStringList(output.path("sourceUrls")))
                    .issueFlags(readStringList(output.path("issueFlags")))
                    .downstreamEvidenceViews(downstreamEvidenceViews)
                    .extractorInput(extractorInput)
                    .build();
        } catch (Exception e) {
            return ExtractSharedProjection.builder()
                    .projectionType(PROJECTION_TYPE)
                    .sourceUrls(List.of())
                    .issueFlags(List.of("PROJECTION_PARSE_FAILED"))
                    .results(List.of())
                    .drafts(List.of())
                    .downstreamEvidenceViews(List.of())
                    .build();
        }
    }

    private static List<Map<String, Object>> readResultsSummary(ObjectMapper objectMapper, JsonNode resultsNode) {
        if (resultsNode == null || !resultsNode.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(resultsNode, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private static List<CompetitorKnowledgeDraft> readDrafts(ObjectMapper objectMapper, JsonNode draftsNode) {
        if (draftsNode == null || !draftsNode.isArray()) {
            return List.of();
        }
        List<CompetitorKnowledgeDraft> drafts = new ArrayList<>();
        for (JsonNode draftNode : draftsNode) {
            drafts.add(CompetitorKnowledgeDraft.builder()
                    .competitorName(textOrNull(draftNode, "competitorName"))
                    .officialUrl(textOrNull(draftNode, "officialUrl"))
                    .summary(textOrNull(draftNode, "summary"))
                    .positioning(textOrNull(draftNode, "positioning"))
                    .targetUsers(readStringList(draftNode.path("targetUsers")))
                    .coreFeatures(readTypedList(objectMapper, draftNode.path("coreFeatures"), FeatureItem.class))
                    .pricing(readTypedObject(objectMapper, draftNode.path("pricing"), PricingItem.class))
                    .strengths(readTypedList(objectMapper, draftNode.path("strengths"), StrengthWeaknessItem.class))
                    .weaknesses(readTypedList(objectMapper, draftNode.path("weaknesses"), StrengthWeaknessItem.class))
                    .sourceUrls(readStringList(draftNode.path("sourceUrls")))
                    .evidenceFragments(List.of())
                    .sectionEvidenceBundles(List.of())
                    .downstreamEvidenceViews(readEvidenceViews(objectMapper, draftNode.path("downstreamEvidenceViews")))
                    .issueFlags(readStringList(draftNode.path("issueFlags")))
                    .evidenceCoverage(readTypedMap(objectMapper, draftNode.path("evidenceCoverage")))
                    .fieldsExtracted(draftNode.path("fieldsExtracted").asInt(0))
                    .status(textOrNull(draftNode, "status"))
                    .build());
        }
        return drafts;
    }

    private static ExtractorInputPackage readExtractorInput(ObjectMapper objectMapper, JsonNode inputNode) {
        if (inputNode == null || inputNode.isMissingNode() || inputNode.isNull()) {
            return null;
        }
        return objectMapper.convertValue(inputNode, ExtractorInputPackage.class);
    }

    private static List<DownstreamEvidenceView> readEvidenceViews(ObjectMapper objectMapper, JsonNode viewsNode) {
        if (viewsNode == null || !viewsNode.isArray()) {
            return List.of();
        }
        List<DownstreamEvidenceView> views = new ArrayList<>();
        for (JsonNode item : viewsNode) {
            DownstreamEvidenceView view = objectMapper.convertValue(item, DownstreamEvidenceView.class);
            if (view != null) {
                views.add(view.normalized());
            }
        }
        return views;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private static <T> List<T> readTypedList(ObjectMapper objectMapper, JsonNode node, Class<T> itemType) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(objectMapper.convertValue(item, itemType));
        }
        return values;
    }

    private static <T> T readTypedObject(ObjectMapper objectMapper, JsonNode node, Class<T> itemType) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, itemType);
    }

    private static Map<String, Object> readTypedMap(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        Map<String, Object> rawMap = objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
        return rawMap == null || rawMap.isEmpty() ? Map.of() : new LinkedHashMap<>(rawMap);
    }

    private static Integer numberOrNull(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode valueNode = node.path(fieldName);
        return valueNode.isNumber() ? valueNode.asInt() : null;
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode valueNode = node.path(fieldName);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        String value = valueNode.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static JsonNode readOutput(ObjectMapper objectMapper, String rawOutput) {
        if (objectMapper == null || rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawOutput);
        } catch (Exception e) {
            return null;
        }
    }
}
