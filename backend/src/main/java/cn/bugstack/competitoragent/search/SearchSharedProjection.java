package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
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
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Collector 对下游共享的稳定事实投影。
 * <p>
 * 这里只保留后续抽取/分析/恢复真正需要的高价值字段，
 * 避免把 results/fullContent 这类大体积现场数据继续塞进共享上下文和 Redis。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchSharedProjection {

    private String projectionType;
    private String recoveryCheckpoint;
    private SearchAuditSummary searchAuditSummary;
    private List<SearchSelectedTargetSummary> selectedTargets;
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
                || output.has("searchExecutionPlan")
                || output.has("searchProgress")
                || output.has("searchQueries")
                || output.has("sourceCandidates")
                || output.has("collectionAudit");
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
            List<String> stableSourceUrls = new ArrayList<>(sourceUrls);
            List<SearchSelectedTargetSummary> selectedTargetSummaries = buildSelectedTargetSummaries(
                    output.path("selectedTargets"),
                    stableSourceUrls
            );
            String recoveryCheckpoint = textOrNull(traceNode, "recoveryCheckpoint");
            SearchAuditSummary summary = SearchAuditSummary.builder()
                    .selectedCount(selectedUrls.size())
                    .degradationReason(textOrNull(traceNode, "degradationReason"))
                    .fallbackDecision(textOrNull(traceNode, "fallbackDecision"))
                    .recoveryCheckpoint(recoveryCheckpoint)
                    .sourceUrls(stableSourceUrls)
                    .build();

            return SearchSharedProjection.builder()
                    .projectionType("SEARCH_SHARED_PROJECTION_V1")
                    .recoveryCheckpoint(recoveryCheckpoint)
                    .searchAuditSummary(summary)
                    .selectedTargets(selectedTargetSummaries)
                    .sourceUrls(stableSourceUrls)
                    .issueFlags(issueFlags == null ? List.of() : issueFlags)
                    .selectedUrls(new ArrayList<>(selectedUrls))
                    .fallbackDecision(textOrNull(traceNode, "fallbackDecision"))
                    .degradationReason(textOrNull(traceNode, "degradationReason"))
                    .build();
        } catch (Exception e) {
            return SearchSharedProjection.builder()
                    .projectionType("SEARCH_SHARED_PROJECTION_V1")
                    .sourceUrls(List.of())
                    .issueFlags(List.of("PROJECTION_PARSE_FAILED"))
                    .selectedUrls(List.of())
                    .selectedTargets(List.of())
                    .build();
        }
    }

    private static List<SearchSelectedTargetSummary> buildSelectedTargetSummaries(JsonNode targetsNode,
                                                                                  List<String> fallbackSourceUrls) {
        if (targetsNode == null || !targetsNode.isArray()) {
            return List.of();
        }
        List<SearchSelectedTargetSummary> summaries = new ArrayList<>();
        for (JsonNode targetNode : targetsNode) {
            String url = textOrNull(targetNode, "url");
            JsonNode candidateNode = targetNode.path("candidate");
            if (url == null) {
                url = textOrNull(candidateNode, "url");
            }
            summaries.add(SearchSelectedTargetSummary.builder()
                    .url(url)
                    .title(textOrNull(targetNode, "title") == null ? textOrNull(candidateNode, "title") : textOrNull(targetNode, "title"))
                    .sourceType(textOrNull(candidateNode, "sourceType"))
                    .sourceFamilyKey(textOrNull(candidateNode, "sourceFamilyKey"))
                    .providerKey(textOrNull(candidateNode, "providerKey"))
                    .selectionStage(textOrNull(candidateNode, "selectionStage"))
                    .selectionReason(textOrNull(candidateNode, "selectionReason"))
                    .reusedCollectedPage(targetNode.has("collectedPage") && !targetNode.path("collectedPage").isMissingNode())
                    .sourceUrls(fallbackSourceUrls == null ? List.of() : fallbackSourceUrls)
                    .build());
        }
        return summaries;
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
