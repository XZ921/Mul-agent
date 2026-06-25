package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对话入口编排决策只读查询服务。
 * 它只从最近一次 ORCHESTRATION_DECISION_RECORDED 事件提取稳定视图，不重算任何编排规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationOrchestrationDecisionQueryService {

    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<ConversationOrchestrationDecisionView> findLatestDecision(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Optional<TaskWorkflowEvent> latestEvent = taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(taskId);
        if (latestEvent.isEmpty()) {
            return Optional.empty();
        }
        return extractView(latestEvent.get());
    }

    /**
     * 历史 payload 既可能是 {"decision": {...}}，也可能直接把 decision 字段平铺在根节点。
     * 这里统一兼容两种格式；读取失败时降级为 empty，让 Conversation 回退到既有预览逻辑。
     */
    private Optional<ConversationOrchestrationDecisionView> extractView(TaskWorkflowEvent event) {
        JsonNode payloadNode = readJson(event == null ? null : event.getPayload());
        if (payloadNode == null) {
            return Optional.empty();
        }
        JsonNode decisionNode = payloadNode.path("decision");
        if (decisionNode == null || decisionNode.isMissingNode() || decisionNode.isNull() || !decisionNode.isObject()) {
            decisionNode = payloadNode;
        }
        if (decisionNode == null || !decisionNode.isObject()) {
            return Optional.empty();
        }

        ConversationOrchestrationDecisionView view = ConversationOrchestrationDecisionView.builder()
                .decisionId(textValue(decisionNode.get("decisionId")))
                .taskId(longValue(decisionNode.get("taskId"), event.getTaskId()))
                .triggerNodeName(firstNonBlank(textValue(decisionNode.get("triggerNodeName")), event.getNodeName()))
                .decisionType(textValue(decisionNode.get("decisionType")))
                .actionType(textValue(decisionNode.get("actionType")))
                .targetNode(textValue(decisionNode.get("targetNode")))
                .affectedScope(textValue(decisionNode.get("affectedScope")))
                .reason(textValue(decisionNode.get("reason")))
                .requiresHumanIntervention(booleanValue(decisionNode.get("requiresHumanIntervention"), false))
                .requiresConfirmation(nullableBooleanValue(decisionNode.get("requiresConfirmation")))
                .evidenceState(firstNonBlank(
                        textValue(decisionNode.get("evidenceState")),
                        textValue(payloadNode.get("evidenceState"))))
                .sourceUrls(mergeSourceUrls(
                        readStringList(decisionNode.get("sourceUrls")),
                        parseJsonStringList(event.getSourceUrls())))
                .build()
                .normalized();

        if (view.getDecisionId() == null
                && view.getDecisionType() == null
                && view.getActionType() == null) {
            return Optional.empty();
        }
        return Optional.of(view);
    }

    private JsonNode readJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.warn("conversation orchestration decision payload parse failed, payload={}", rawJson, e);
            return null;
        }
    }

    private List<String> parseJsonStringList(String rawJson) {
        JsonNode node = readJson(rawJson);
        return readStringList(node);
    }

    private List<String> readStringList(JsonNode node) {
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

    private List<String> mergeSourceUrls(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return merged;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long longValue(JsonNode node, Long fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.isNumber() ? node.asLong() : fallback;
    }

    private boolean booleanValue(JsonNode node, boolean fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.asBoolean(fallback);
    }

    private Boolean nullableBooleanValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asBoolean();
    }
}
