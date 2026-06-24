package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extractor 输出到 AgentSuggestion 的转换器。
 * 它只识别抽取后的证据缺口事实，不直接创建动态计划或执行补源动作。
 */
@Slf4j
@Component
public class ExtractorSuggestionAssembler {

    private static final List<String> BLOCKING_FLAGS = List.of(
            "NO_BUSINESS_FIELDS_EXTRACTED",
            "FIELD_MISSING_EVIDENCE",
            "EVIDENCE_NOT_COVERING",
            "LLM_REFUSED",
            "SECTION_EVIDENCE_GAP"
    );

    private final ObjectMapper objectMapper;

    public ExtractorSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 extractor 输出中生成建议。
     * 支持 Map、JsonNode、JSON 字符串三种输入，解析失败时返回空建议，避免脏输出直接触发错误编排。
     */
    public List<AgentSuggestion> fromExtractorOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        List<String> sourceUrls = readStringList(output.path("sourceUrls"));
        List<String> issueFlags = readStringList(output.path("issueFlags")).stream()
                .map(this::upper)
                .filter(BLOCKING_FLAGS::contains)
                .toList();
        List<AgentSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (String issueFlag : issueFlags) {
            if ("SECTION_EVIDENCE_GAP".equals(issueFlag) && output.path("evidenceCoverage").isObject()) {
                continue;
            }
            suggestions.add(buildSuggestion(taskId, producerNodeName, index++, issueFlag, producerNodeName, sourceUrls,
                    output.path("confidence").asDouble(0.75d), List.of()));
        }
        JsonNode evidenceCoverage = output.path("evidenceCoverage");
        if (evidenceCoverage.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = evidenceCoverage.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode coverage = field.getValue();
                String status = upper(coverage.path("status").asText(""));
                if (BLOCKING_FLAGS.contains(status)) {
                    suggestions.add(buildSuggestion(
                            taskId,
                            producerNodeName,
                            index++,
                            status,
                            field.getKey(),
                            readSectionSourceUrls(coverage, sourceUrls),
                            coverage.path("confidence").asDouble(output.path("confidence").asDouble(0.75d)),
                            readStringList(coverage.path("missingFields"))));
                }
            }
        }
        return suggestions;
    }

    private AgentSuggestion buildSuggestion(Long taskId,
                                            String producerNodeName,
                                            int index,
                                            String issueFlag,
                                            String targetSection,
                                            List<String> sourceUrls,
                                            double confidence,
                                            List<String> missingFields) {
        return AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-" + index)
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("EXTRACTOR")
                .suggestionType("EVIDENCE_GAP")
                .targetSection(targetSection)
                .summary(buildSummary(issueFlag, targetSection, missingFields))
                .severity(resolveSeverity(issueFlag))
                .confidence(confidence)
                .sourceUrls(sourceUrls)
                .suggestedQueries(buildSuggestedQueries(targetSection, missingFields))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();
    }

    private JsonNode toJsonNode(Object rawOutput) {
        if (rawOutput == null) {
            return null;
        }
        if (rawOutput instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            if (rawOutput instanceof String rawString) {
                return rawString.isBlank() ? null : objectMapper.readTree(rawString);
            }
            return objectMapper.valueToTree(rawOutput);
        } catch (Exception e) {
            log.warn("failed to parse extractor output for agent suggestion", e);
            return null;
        }
    }

    private List<String> readSectionSourceUrls(JsonNode coverage, List<String> fallbackSourceUrls) {
        List<String> sectionSourceUrls = readStringList(coverage.path("sourceUrls"));
        return sectionSourceUrls.isEmpty() ? fallbackSourceUrls : sectionSourceUrls;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private String buildSummary(String issueFlag, String targetSection, List<String> missingFields) {
        String section = targetSection == null || targetSection.isBlank() ? "extract_schema" : targetSection;
        return switch (issueFlag) {
            case "NO_BUSINESS_FIELDS_EXTRACTED" -> section + " 没有抽出任何业务字段，需要补充采集或人工确认。";
            case "FIELD_MISSING_EVIDENCE" -> section + " 字段缺少可验证来源，需要补充证据。";
            case "EVIDENCE_NOT_COVERING" -> section + " 现有证据没有覆盖目标字段，需要补充来源；缺失字段："
                    + String.join("、", missingFields);
            case "LLM_REFUSED" -> section + " 模型拒绝或无法完成抽取，需要人工检查或重新采集。";
            case "SECTION_EVIDENCE_GAP" -> section + " 章节存在证据缺口，需要补充来源。";
            default -> section + " 存在抽取证据缺口，需要 Orchestrator 判断下一步。";
        };
    }

    private String resolveSeverity(String issueFlag) {
        return "NO_BUSINESS_FIELDS_EXTRACTED".equals(issueFlag) ? "ERROR" : "HIGH";
    }

    private List<String> buildSuggestedQueries(String targetSection, List<String> missingFields) {
        List<String> queries = new ArrayList<>();
        String section = targetSection == null || targetSection.isBlank() ? "competitor evidence" : targetSection;
        queries.add(section + " official source");
        for (String missingField : missingFields) {
            queries.add(section + " " + missingField + " official");
        }
        return queries;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
