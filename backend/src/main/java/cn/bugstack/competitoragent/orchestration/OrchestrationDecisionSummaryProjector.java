package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协作决策只读投影工具。
 * 这里是 workflow event 到 report、replay、conversation 摘要的唯一解析入口，
 * 所有历史格式兼容和安全默认值都必须集中在本类中。
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
        return project(
                payloadNode,
                event.getTaskId(),
                event.getNodeName(),
                parseJsonStringList(event.getSourceUrls(), objectMapper));
    }

    /**
     * SSE replay 持有的是已经结构化的 Map payload，仍必须复用与数据库事件相同的投影规则。
     * 使用 Jackson 转成树模型可以保留嵌套对象、枚举和数字类型，避免手写 Map 强转产生第二套协议。
     */
    public static Optional<OrchestrationDecisionSummary> fromEventPayload(Map<String, Object> payload,
                                                                          Long fallbackTaskId,
                                                                          String fallbackNodeName,
                                                                          List<String> eventSourceUrls,
                                                                          ObjectMapper objectMapper) {
        if (payload == null || payload.isEmpty() || objectMapper == null) {
            return Optional.empty();
        }
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        if (payloadNode == null || !payloadNode.isObject()) {
            return Optional.empty();
        }
        return project(payloadNode, fallbackTaskId, fallbackNodeName, eventSourceUrls);
    }

    private static Optional<OrchestrationDecisionSummary> project(JsonNode payloadNode,
                                                                  Long fallbackTaskId,
                                                                  String fallbackNodeName,
                                                                  List<String> eventSourceUrls) {
        JsonNode decisionNode = resolveDecisionNode(payloadNode);
        // 必须在 DTO 默认值归一化之前检查原始 marker，避免把普通 workflow event 误投影成 WAIT_FOR_HUMAN。
        if (!hasDecisionMarker(decisionNode)) {
            return Optional.empty();
        }

        JsonNode metadataNode = objectChild(decisionNode, "decisionMetadata");
        JsonNode inputRefsNode = objectChild(decisionNode, "inputRefs");
        JsonNode policyNode = objectChild(payloadNode, "policyResult");

        String decisionOrigin = firstNonBlank(
                textValue(decisionNode.get("decisionOrigin")),
                textValue(policyNode.get("decisionOrigin")),
                textValue(inputRefsNode.get("decisionOrigin")),
                textValue(payloadNode.get("decisionOrigin")));
        String decisionContract = firstNonBlank(
                textValue(policyNode.get("decisionContract")),
                textValue(decisionNode.get("decisionContract")),
                textValue(inputRefsNode.get("decisionContract")),
                textValue(payloadNode.get("decisionContract")));

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummary.builder()
                .decisionId(textValue(decisionNode.get("decisionId")))
                .taskId(longValue(decisionNode.get("taskId"), fallbackTaskId))
                .triggerNodeName(firstNonBlank(
                        textValue(decisionNode.get("triggerNodeName")),
                        fallbackNodeName))
                .decisionType(textValue(decisionNode.get("decisionType")))
                .actionType(textValue(decisionNode.get("actionType")))
                .decisionOrigin(decisionOrigin)
                .decisionContract(decisionContract)
                .fallbackReason(metadataText(metadataNode, decisionNode, inputRefsNode, payloadNode, "fallbackReason"))
                .modelName(metadataText(metadataNode, decisionNode, inputRefsNode, payloadNode, "modelName"))
                .temperature(firstNonNull(
                        doubleValue(metadataNode.get("temperature")),
                        doubleValue(decisionNode.get("temperature")),
                        doubleValue(inputRefsNode.get("temperature")),
                        doubleValue(payloadNode.get("temperature"))))
                .promptHash(metadataText(metadataNode, decisionNode, inputRefsNode, payloadNode, "promptHash"))
                .llmResponseHash(metadataText(
                        metadataNode, decisionNode, inputRefsNode, payloadNode, "llmResponseHash"))
                .parseRetryCount(firstNonNull(
                        integerValue(metadataNode.get("parseRetryCount")),
                        integerValue(decisionNode.get("parseRetryCount")),
                        integerValue(inputRefsNode.get("parseRetryCount")),
                        integerValue(payloadNode.get("parseRetryCount"))))
                .fallbackUsed(Boolean.TRUE.equals(firstNonNull(
                        nullableBooleanValue(metadataNode.get("fallbackUsed")),
                        nullableBooleanValue(decisionNode.get("fallbackUsed")),
                        nullableBooleanValue(inputRefsNode.get("fallbackUsed")),
                        nullableBooleanValue(payloadNode.get("fallbackUsed")))))
                .shadowExecuted(firstNonNull(
                        nullableBooleanValue(metadataNode.get("shadowExecuted")),
                        nullableBooleanValue(decisionNode.get("shadowExecuted")),
                        nullableBooleanValue(inputRefsNode.get("shadowExecuted")),
                        nullableBooleanValue(payloadNode.get("shadowExecuted"))))
                .shadowSkippedReason(metadataText(
                        metadataNode, decisionNode, inputRefsNode, payloadNode, "shadowSkippedReason"))
                .targetNode(textValue(decisionNode.get("targetNode")))
                .affectedScope(textValue(decisionNode.get("affectedScope")))
                .reason(firstNonBlank(
                        textValue(decisionNode.get("reason")),
                        textValue(payloadNode.get("summary"))))
                .requiresHumanIntervention(booleanValue(
                        decisionNode.get("requiresHumanIntervention"), false))
                .requiresConfirmation(nullableBooleanValue(decisionNode.get("requiresConfirmation")))
                .evidenceState(firstNonBlank(
                        textValue(decisionNode.get("evidenceState")),
                        textValue(payloadNode.get("evidenceState"))))
                .sourceUrls(mergeSourceUrls(
                        readStringList(decisionNode.get("sourceUrls")),
                        readStringList(payloadNode.get("sourceUrls")),
                        eventSourceUrls))
                .build()
                .normalized();
        return Optional.of(summary);
    }

    /**
     * replay 时间线不应该只显示通用的 “Orchestrator 已生成决策”；
     * 这里把关键动作、证据状态和原因压缩成一句可读摘要，最终 origin 展示样式由 Task 08 处理。
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

    private static JsonNode resolveDecisionNode(JsonNode payloadNode) {
        JsonNode nestedDecision = objectChild(payloadNode, "decision");
        return nestedDecision.isObject() && nestedDecision.size() > 0 ? nestedDecision : payloadNode;
    }

    private static JsonNode objectChild(JsonNode parent, String fieldName) {
        if (parent == null || !parent.isObject()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        JsonNode child = parent.get(fieldName);
        return child != null && child.isObject()
                ? child
                : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static boolean hasDecisionMarker(JsonNode decisionNode) {
        return decisionNode != null
                && decisionNode.isObject()
                && (textValue(decisionNode.get("decisionId")) != null
                || textValue(decisionNode.get("decisionType")) != null
                || textValue(decisionNode.get("actionType")) != null);
    }

    private static String metadataText(JsonNode metadataNode,
                                       JsonNode decisionNode,
                                       JsonNode inputRefsNode,
                                       JsonNode payloadNode,
                                       String fieldName) {
        return firstNonBlank(
                textValue(metadataNode.get(fieldName)),
                textValue(decisionNode.get(fieldName)),
                textValue(inputRefsNode.get(fieldName)),
                textValue(payloadNode.get(fieldName)));
    }

    private static JsonNode readJson(String rawJson, ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> parseJsonStringList(String rawJson, ObjectMapper objectMapper) {
        return readStringList(readJson(rawJson, objectMapper));
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

    @SafeVarargs
    private static List<String> mergeSourceUrls(List<String>... sourceGroups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (sourceGroups != null) {
            for (List<String> sourceGroup : sourceGroups) {
                if (sourceGroup != null) {
                    merged.addAll(sourceGroup);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values != null) {
            for (T value : values) {
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longValue(JsonNode node, Long fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        return node.isNumber() ? node.asLong() : fallback;
    }

    private static Integer integerValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isNumber() ? node.asInt() : null;
    }

    private static Double doubleValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isNumber() ? node.asDouble() : null;
    }

    private static boolean booleanValue(JsonNode node, boolean fallback) {
        Boolean value = nullableBooleanValue(node);
        return value == null ? fallback : value;
    }

    private static Boolean nullableBooleanValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isBoolean()) {
            return null;
        }
        return node.asBoolean();
    }
}
