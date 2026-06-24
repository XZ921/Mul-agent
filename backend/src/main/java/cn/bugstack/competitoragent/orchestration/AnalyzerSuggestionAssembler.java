package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analyzer 输出到 AgentSuggestion 的转换器。
 * 这里只表达“分析缺口事实”，不直接决定补证、重跑或人工介入动作。
 */
@Slf4j
@Component
public class AnalyzerSuggestionAssembler {

    private final ObjectMapper objectMapper;

    public AnalyzerSuggestionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Analyzer 输出中提取分析缺口建议。
     * 解析失败时返回空建议，避免脏输出触发不可解释的下游编排。
     */
    public List<AgentSuggestion> fromAnalyzerOutput(Long taskId, String producerNodeName, Object rawOutput) {
        JsonNode output = toJsonNode(rawOutput);
        if (output == null || output.isNull() || output.isMissingNode()) {
            return List.of();
        }
        List<String> missingDimensions = readStringList(output.path("missingAnalysisDimensions"));
        String severity = upper(output.path("analysisGapSeverity").asText("NONE"));
        if (missingDimensions.isEmpty() || "NONE".equals(severity)) {
            return List.of();
        }
        List<String> sourceUrls = readStringList(output.path("sourceUrls"));
        return List.of(AgentSuggestion.builder()
                .suggestionId("as-task-" + taskId + "-" + producerNodeName + "-1")
                .taskId(taskId)
                .producerNodeName(producerNodeName)
                .producerAgentType("ANALYZER")
                .suggestionType("ANALYSIS_GAP")
                .targetSection("analysis")
                .summary(buildSummary(missingDimensions, severity))
                .severity(resolveSeverity(severity))
                .confidence(resolveConfidence(output.path("analysisConfidence").asText(null)))
                .sourceUrls(sourceUrls)
                .evidenceState(resolveEvidenceState(output.path("analysisEvidenceState").asText(null), sourceUrls))
                .suggestedQueries(buildSuggestedQueries(missingDimensions))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized());
    }

    /**
     * 兼容 String、JsonNode 和普通对象三种输入，方便 DagExecutor 直接复用。
     */
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
            log.warn("failed to parse analyzer output for agent suggestion", e);
            return null;
        }
    }

    /**
     * 只读取非空字符串数组，避免把 null、空串和非数组结构误当成有效维度。
     */
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
     * 摘要里明确说明缺失的是哪些分析维度，避免 Orchestrator 只能看到抽象的 HIGH/LOW。
     */
    private String buildSummary(List<String> missingDimensions, String severity) {
        return "Analyzer 存在 " + severity + " 级分析缺口，需要补充证据或人工确认；缺失维度："
                + String.join("、", missingDimensions);
    }

    /**
     * Analyzer 的 HIGH/ERROR 都归并成高优先级建议，供策略层做放行或阻断判断。
     */
    private String resolveSeverity(String severity) {
        return switch (severity) {
            case "ERROR", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * 把 Analyzer 的离散置信度映射成统一的 0-1 浮点值，便于后续策略排序。
     */
    private Double resolveConfidence(String confidence) {
        String normalized = upper(confidence);
        return switch (normalized) {
            case "HIGH" -> 0.90d;
            case "MEDIUM" -> 0.65d;
            default -> 0.35d;
        };
    }

    /**
     * 优先尊重 Analyzer 明确给出的 evidenceState；缺失时再依据 sourceUrls 兜底。
     */
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

    /**
     * 建议检索词直接围绕缺失维度展开，确保补证动作能追溯到 Analyzer 判定依据。
     */
    private List<String> buildSuggestedQueries(List<String> missingDimensions) {
        List<String> queries = new ArrayList<>();
        for (String dimension : missingDimensions) {
            queries.add(dimension + " official source");
        }
        return queries;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
