package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Collector 对下游共享的稳定事实投影。
 * <p>
 * 这里只保留后续抽取/分析/恢复真正需要的高价值字段，
 * 避免把 results/fullContent 这类大体积现场数据继续塞进共享上下文和 Redis。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchSharedProjection {

    private List<String> sourceUrls;
    private List<String> issueFlags;
    private List<String> selectedUrls;
    private String fallbackDecision;
    private String degradationReason;

    /**
     * 只有识别到正式搜索字段时才启用裁剪，
     * 避免把历史测试桩或旧版极简 collector 输出错误投影成空对象。
     */
    public static boolean supportsCollectorOutput(ObjectMapper objectMapper, String rawOutput) {
        JsonNode output = readOutput(objectMapper, rawOutput);
        if (output == null || !output.isObject()) {
            return false;
        }
        return output.has("searchAudit")
                || output.has("searchExecutionTrace")
                || output.has("selectedTargets")
                || output.has("results")
                || output.has("sourceUrls")
                || output.has("issueFlags");
    }

    public static SearchSharedProjection fromCollectorOutput(ObjectMapper objectMapper, String rawOutput) {
        try {
            JsonNode output = objectMapper.readTree(rawOutput);
            JsonNode traceNode = resolveExecutionTraceNode(output);
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            addTextValues(sourceUrls, output.path("sourceUrls"));
            addTextValues(sourceUrls, output.path("searchAudit").path("sourceUrls"));

            LinkedHashSet<String> selectedUrls = new LinkedHashSet<>();
            addTextValues(selectedUrls, traceNode.path("selectedUrls"));
            addTargetUrls(selectedUrls, output.path("selectedTargets"));
            addTargetUrls(selectedUrls, output.path("searchAudit").path("selectedTargets"));

            List<String> issueFlags = objectMapper.convertValue(
                    output.path("issueFlags"),
                    new TypeReference<List<String>>() {
                    });

            return new SearchSharedProjection(
                    new ArrayList<>(sourceUrls),
                    issueFlags == null ? List.of() : issueFlags,
                    new ArrayList<>(selectedUrls),
                    textOrNull(traceNode, "fallbackDecision"),
                    textOrNull(traceNode, "degradationReason")
            );
        } catch (Exception e) {
            return new SearchSharedProjection(List.of(), List.of("PROJECTION_PARSE_FAILED"), List.of(), null, null);
        }
    }

    private static JsonNode resolveExecutionTraceNode(JsonNode output) {
        JsonNode directTrace = output.path("searchExecutionTrace");
        if (!directTrace.isMissingNode() && !directTrace.isNull()) {
            return directTrace;
        }
        JsonNode auditTrace = output.path("searchAudit").path("executionTrace");
        return auditTrace.isMissingNode() ? output.path("searchExecutionTrace") : auditTrace;
    }

    private static void addTextValues(LinkedHashSet<String> values, JsonNode arrayNode) {
        if (values == null || arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode node : arrayNode) {
            if (node != null && node.isValueNode()) {
                String value = node.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
    }

    private static void addTargetUrls(LinkedHashSet<String> urls, JsonNode targetsNode) {
        if (urls == null || targetsNode == null || !targetsNode.isArray()) {
            return;
        }
        for (JsonNode targetNode : targetsNode) {
            String url = textOrNull(targetNode, "url");
            if (url != null && !url.isBlank()) {
                urls.add(url.trim());
            }
        }
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
