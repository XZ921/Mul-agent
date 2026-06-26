package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 协作决策只读投影工具。
 * 这里专门负责把 workflow 事件翻译成稳定摘要，
 * 让 report / export / replay 只消费“解释后的事实”，而不是各自重复解析原始 payload。
 */
public final class OrchestrationDecisionSummaryProjector {

    private OrchestrationDecisionSummaryProjector() {
    }

    public static Optional<OrchestrationDecisionSummary> fromWorkflowEvent(TaskWorkflowEvent event,
                                                                           ObjectMapper objectMapper) {
        if (event == null || objectMapper == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return Optional.empty();
        }
        JsonNode payloadNode = readJson(event.getPayload(), objectMapper);
        if (payloadNode == null || !payloadNode.isObject()) {
            return Optional.empty();
        }
        JsonNode decisionNode = payloadNode.path("decision");
        if (decisionNode == null || decisionNode.isMissingNode() || decisionNode.isNull() || !decisionNode.isObject()) {
            decisionNode = payloadNode;
        }
        if (decisionNode == null || !decisionNode.isObject()) {
            return Optional.empty();
        }

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummary.builder()
                .decisionId(textValue(decisionNode.get("decisionId")))
                .taskId(longValue(decisionNode.get("taskId"), event.getTaskId()))
                .triggerNodeName(firstNonBlank(textValue(decisionNode.get("triggerNodeName")), event.getNodeName()))
                .decisionType(textValue(decisionNode.get("decisionType")))
                .actionType(textValue(decisionNode.get("actionType")))
                .targetNode(textValue(decisionNode.get("targetNode")))
                .affectedScope(textValue(decisionNode.get("affectedScope")))
                .reason(firstNonBlank(textValue(decisionNode.get("reason")), textValue(payloadNode.get("summary"))))
                .requiresHumanIntervention(booleanValue(decisionNode.get("requiresHumanIntervention"), false))
                .requiresConfirmation(nullableBooleanValue(decisionNode.get("requiresConfirmation")))
                .evidenceState(firstNonBlank(
                        textValue(decisionNode.get("evidenceState")),
                        textValue(payloadNode.get("evidenceState"))))
                .sourceUrls(mergeSourceUrls(
                        readStringList(decisionNode.get("sourceUrls")),
                        parseJsonStringList(event.getSourceUrls(), objectMapper)))
                .build()
                .normalized();

        if (summary.getDecisionId() == null
                && summary.getDecisionType() == null
                && summary.getActionType() == null) {
            return Optional.empty();
        }
        return Optional.of(summary);
    }

    /**
     * replay 时间线不应该只显示通用的 “Orchestrator 已生成决策”；
     * 这里把关键动作、证据状态和原因压缩成一句可读摘要，方便直接理解当前阻塞点。
     */
    public static String toReplaySummary(OrchestrationDecisionSummary rawSummary) {
        OrchestrationDecisionSummary summary = rawSummary == null ? null : rawSummary.normalized();
        if (summary == null) {
            return "协作决策摘要缺失";
        }
        return "%s -> %s / %s，证据状态 %s，原因：%s".formatted(
                firstNonBlank(summary.getTriggerNodeName(), "unknown_node"),
                firstNonBlank(summary.getDecisionType(), "WAIT_FOR_HUMAN"),
                firstNonBlank(summary.getActionType(), "MANUAL_REVIEW"),
                firstNonBlank(summary.getEvidenceState(), "MISSING_SOURCE"),
                firstNonBlank(summary.getReason(), "当前协作决策缺少明确原因说明。"));
    }

    private static JsonNode readJson(String rawJson, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> parseJsonStringList(String rawJson, ObjectMapper objectMapper) {
        JsonNode node = readJson(rawJson, objectMapper);
        return readStringList(node);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = textValue(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<String> mergeSourceUrls(List<String> primary, List<String> secondary) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return new ArrayList<>(merged);
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longValue(JsonNode node, Long fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.isNumber() ? node.asLong() : fallback;
    }

    private static boolean booleanValue(JsonNode node, boolean fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.asBoolean(fallback);
    }

    private static Boolean nullableBooleanValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asBoolean();
    }
}
