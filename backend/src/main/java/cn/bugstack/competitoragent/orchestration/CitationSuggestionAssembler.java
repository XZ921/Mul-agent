package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Citation 输出到 AgentSuggestion 的转换器。
 * 它只把引用核查缺口翻译成统一建议输入，不直接决定阻断、重写或人工介入动作。
 */
@Slf4j
@Component
public class CitationSuggestionAssembler {

    private final ObjectMapper objectMapper;

    public CitationSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Citation 输出中提取引用核查建议。
     * 解析失败时返回空建议，避免脏输出触发不可解释的编排动作。
     */
    public List<AgentSuggestion> fromCitationOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        String riskSeverity = upper(output.path("citationRiskSeverity").asText("NONE"));
        if ("NONE".equals(riskSeverity)) {
            return List.of();
        }
        JsonNode issues = output.path("citationIssues");
        if (!issues.isArray() || issues.isEmpty()) {
            return List.of();
        }
        List<AgentSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (JsonNode issue : issues) {
            suggestions.add(buildSuggestion(taskId, producerNodeName, index++, issue, riskSeverity));
        }
        return suggestions;
    }

    private AgentSuggestion buildSuggestion(Long taskId,
                                            String producerNodeName,
                                            int index,
                                            JsonNode issue,
                                            String fallbackSeverity) {
        List<String> sourceUrls = readStringList(issue.path("sourceUrls"));
        return AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-citation-" + index)
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("CITATION")
                .suggestionType("CITATION_VERIFICATION_GAP")
                .targetSection(issue.path("targetSection").asText("report"))
                .summary(issue.path("summary").asText("Citation Agent 发现引用核查缺口，需要 Orchestrator 判断下一步。"))
                .severity(resolveSeverity(issue.path("severity").asText(fallbackSeverity)))
                .confidence(resolveConfidence(resolveEvidenceState(issue.path("evidenceState").asText(null), sourceUrls)))
                .sourceUrls(sourceUrls)
                .evidenceState(resolveEvidenceState(issue.path("evidenceState").asText(null), sourceUrls))
                .suggestedQueries(resolveSuggestedQueries(issue))
                .suggestedTargetNode("rewrite_report")
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
            log.warn("failed to parse citation output for agent suggestion", e);
            return null;
        }
    }

    private List<String> resolveSuggestedQueries(JsonNode issue) {
        List<String> queries = readStringList(issue.path("suggestedQueries"));
        if (!queries.isEmpty()) {
            return queries;
        }
        return List.of(issue.path("targetSection").asText("report") + " official evidence");
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

    private String resolveSeverity(String severity) {
        String normalized = upper(severity);
        if ("ERROR".equals(normalized) || "HIGH".equals(normalized)) {
            return normalized;
        }
        return "MEDIUM";
    }

    private Double resolveConfidence(EvidenceState evidenceState) {
        return evidenceState == EvidenceState.MISSING_SOURCE ? 0.25d : 0.70d;
    }

    private EvidenceState resolveEvidenceState(String evidenceState, List<String> sourceUrls) {
        String normalized = upper(evidenceState);
        if ("MISSING_SOURCE".equals(normalized)) {
            return EvidenceState.MISSING_SOURCE;
        }
        if ("PARTIAL_SOURCE".equals(normalized)) {
            return EvidenceState.PARTIAL_SOURCE;
        }
        return sourceUrls == null || sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
