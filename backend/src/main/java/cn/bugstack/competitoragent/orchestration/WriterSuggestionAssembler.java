package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writer 输出到 AgentSuggestion 的转换器。
 * 它只识别章节引用缺口事实，不直接决定补证、重写或人工介入动作。
 */
@Slf4j
@Component
public class WriterSuggestionAssembler {

    private final ObjectMapper objectMapper;

    public WriterSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Writer 输出中提取引用缺口建议。
     * 解析失败时返回空建议，避免脏报告输出触发不可解释的编排。
     */
    public List<AgentSuggestion> fromWriterOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        String severity = upper(output.path("citationGapSeverity").asText("NONE"));
        if ("NONE".equals(severity)) {
            return List.of();
        }
        JsonNode gaps = output.path("sectionCitationGaps");
        if (!gaps.isArray() || gaps.isEmpty()) {
            return List.of();
        }
        List<AgentSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (JsonNode gap : gaps) {
            suggestions.add(buildSuggestion(taskId, producerNodeName, index++, gap, severity));
        }
        return suggestions;
    }

    private AgentSuggestion buildSuggestion(Long taskId,
                                            String producerNodeName,
                                            int index,
                                            JsonNode gap,
                                            String fallbackSeverity) {
        List<String> sourceUrls = readStringList(gap.path("sourceUrls"));
        EvidenceState evidenceState = resolveEvidenceState(gap.path("evidenceState").asText(null), sourceUrls);
        return AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-" + index)
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("WRITER")
                .suggestionType("CITATION_GAP")
                .targetSection(gap.path("targetSection").asText("report"))
                .summary(gap.path("summary").asText("Writer 发现章节引用缺口，需要 Orchestrator 判断下一步。"))
                .severity(resolveSeverity(gap.path("severity").asText(fallbackSeverity)))
                .confidence(resolveConfidence(evidenceState))
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .suggestedQueries(resolveSuggestedQueries(gap, sourceUrls))
                .suggestedTargetNode(sourceUrls.isEmpty() ? "collect_sources" : "rewrite_report")
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
            log.warn("failed to parse writer output for agent suggestion", e);
            return null;
        }
    }

    /**
     * 优先复用 Writer 已经给出的补证查询；没有时再按是否缺来源生成默认查询。
     */
    private List<String> resolveSuggestedQueries(JsonNode gap, List<String> sourceUrls) {
        List<String> queries = readStringList(gap.path("suggestedQueries"));
        if (!queries.isEmpty()) {
            return queries;
        }
        String targetSection = gap.path("targetSection").asText("report");
        return sourceUrls.isEmpty()
                ? List.of(targetSection + " official citation evidence")
                : List.of(targetSection + " rewrite with evidence citations");
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

    /**
     * Writer 的 ERROR/HIGH 继续透传，其余缺口统一降到 MEDIUM，避免误把普通提示当阻断级告警。
     */
    private String resolveSeverity(String severity) {
        String normalized = upper(severity);
        if ("ERROR".equals(normalized) || "HIGH".equals(normalized)) {
            return normalized;
        }
        return "MEDIUM";
    }

    /**
     * 完全无来源的 Writer gap 置信度最低；有来源但引用不完整时保持中等置信度，供策略层排序。
     */
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
