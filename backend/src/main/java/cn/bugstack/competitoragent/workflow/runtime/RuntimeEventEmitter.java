package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import cn.bugstack.competitoragent.model.dto.CollectorSelectedTargetSummary;
import cn.bugstack.competitoragent.model.dto.SearchProgressEventPayload;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.search.SearchReplayTimelineItem;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时事件发布协作者。
 * <p>
 * 节点完成后既要发布节点状态事件，又要补发搜索进度、诊断和日志兜底事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeEventEmitter {

    private final TaskEventPublisher taskEventPublisher;
    private final AgentLogService agentLogService;
    private final ObjectMapper objectMapper;

    /**
     * 节点完成后统一补发节点状态、搜索进度、日志与诊断事件。
     */
    public void publishNodeExecutionEvents(Long taskId, TaskNode node) {
        if (node == null) {
            return;
        }
        String action = switch (node.getStatus()) {
            case SUCCESS -> "NODE_COMPLETED";
            case WAITING_RETRY -> "NODE_WAITING_RETRY";
            case WAITING_INTERVENTION -> "NODE_WAITING_INTERVENTION";
            case COMPENSATED -> "NODE_COMPENSATED";
            default -> "NODE_FAILED";
        };
        taskEventPublisher.publishNodeStatusEvent(taskId, node, action);
        publishSearchProgressEventIfPresent(taskId, node);
        publishDiagnosisEventIfPresent(taskId, node);
        boolean publishedFromLog = agentLogService.publishLatestLogEvent(taskId, node.getNodeName(), node.getAgentType());
        if (!publishedFromLog) {
            publishAgentOutputFallbackEvent(taskId, node);
        }
    }

    private void publishSearchProgressEventIfPresent(Long taskId, TaskNode node) {
        if (node.getAgentType() != AgentType.COLLECTOR) {
            return;
        }
        Map<String, Object> payload = buildSearchProgressEventPayload(node);
        if (payload.isEmpty()) {
            return;
        }
        taskEventPublisher.publishSearchProgressEvent(taskId, node.getNodeName(), payload);
    }

    private void publishDiagnosisEventIfPresent(Long taskId, TaskNode node) {
        if (node.getAgentType() != AgentType.REVIEWER || node.getOutputData() == null || node.getOutputData().isBlank()) {
            return;
        }
        JsonNode output = readJson(node.getOutputData());
        if (output == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", node.getNodeName());
        payload.put("passed", output.path("passed").asBoolean(false));
        payload.put("score", output.path("score").asInt(-1));
        payload.put("summary", output.path("summary").asText(null));
        payload.put("requiresHumanIntervention", output.path("requiresHumanIntervention").asBoolean(false));
        if (output.has("diagnoses")) {
            payload.put("diagnoses", objectMapper.convertValue(output.get("diagnoses"), new TypeReference<List<Map<String, Object>>>() {
            }));
        }
        if (output.has("issues")) {
            payload.put("issues", objectMapper.convertValue(output.get("issues"), new TypeReference<List<Map<String, Object>>>() {
            }));
        }
        taskEventPublisher.publishDiagnosisEvent(taskId, node.getNodeName(), payload);
    }

    private void publishAgentOutputFallbackEvent(Long taskId, TaskNode node) {
        if (node.getAgentType() == null) {
            return;
        }
        AgentLogResponse fallbackLog = AgentLogResponse.builder()
                .taskId(taskId)
                .agentType(node.getAgentType())
                .agentName(node.getDisplayName() == null || node.getDisplayName().isBlank()
                        ? node.getAgentType().name()
                        : node.getDisplayName())
                .status(node.getStatus())
                .reasoningSummary(node.getErrorMessage())
                .outputData(node.getOutputData())
                .errorMessage(node.getErrorMessage())
                .createdAt(node.getCompletedAt() == null ? LocalDateTime.now() : node.getCompletedAt())
                .build();
        taskEventPublisher.publishAgentLogEvent(taskId, node.getNodeName(), fallbackLog);
    }

    /**
     * Collector 事件优先透传正式的搜索契约；
     * 如果历史输出里缺少结构化字段，再退化成最小可恢复事件。
     */
    private Map<String, Object> buildSearchProgressEventPayload(TaskNode node) {
        SearchProgressEventPayload payload = SearchProgressEventPayload.builder()
                .contractType("SEARCH_PROGRESS_V1")
                .nodeName(node.getNodeName())
                .build();
        JsonNode output = readJson(node.getOutputData());
        if (output != null) {
            payload.setSearchProgress(convertValue(output.get("searchProgress"), SearchProgressSnapshot.class));
            payload.setSearchExecutionTrace(convertValue(output.get("searchExecutionTrace"), SearchExecutionTrace.class));
            payload.setSearchProgressSnapshots(convertList(
                    output.get("searchProgressSnapshots"),
                    new TypeReference<List<SearchProgressSnapshot>>() {
                    }));
            SearchAuditSnapshot searchAudit = convertValue(output.get("searchAudit"), SearchAuditSnapshot.class);
            payload.setSearchAudit(searchAudit);
            payload.setAttemptedTargets(resolveSearchFactList(
                    output.get("attemptedTargets"),
                    searchAudit == null ? null : searchAudit.getAttemptedTargets(),
                    new TypeReference<List<SearchCollectionTarget>>() {
                    }));
            payload.setDiscardedCandidates(resolveSearchFactList(
                    output.get("discardedCandidates"),
                    searchAudit == null ? null : searchAudit.getDiscardedCandidates(),
                    new TypeReference<List<SourceCandidate>>() {
                    }));
            payload.setReplayTimeline(resolveSearchFactList(
                    firstPresent(output.get("searchReplayTimeline"), output.get("replayTimeline")),
                    searchAudit == null ? null : searchAudit.getReplayTimeline(),
                    new TypeReference<List<SearchReplayTimelineItem>>() {
                    }));
            payload.setSelectedTargets(convertList(
                    output.get("selectedTargets"),
                    new TypeReference<List<CollectorSelectedTargetSummary>>() {
                    }));
            CollectionAuditSnapshot collectionAudit = convertValue(output.get("collectionAudit"), CollectionAuditSnapshot.class);
            if (collectionAudit != null && (collectionAudit.getSourceUrls() == null || collectionAudit.getSourceUrls().isEmpty())) {
                collectionAudit.setSourceUrls(readStringList(output.get("sourceUrls")));
            }
            payload.setCollectionStatus(textValue(output.get("collectionStatus")));
            payload.setCollectionAudit(collectionAudit);
            payload.setCollectionAuditSummary(resolveCollectionAuditSummary(collectionAudit));
            payload.setCollectionReplayTimeline(resolveCollectionReplayTimeline(output, collectionAudit));
            payload.setSourceUrls(readStringList(output.get("sourceUrls")));
        }

        if (hasStructuredSearchPayload(payload)) {
            return objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
            });
        }

        payload.setSearchProgress(SearchProgressSnapshot.builder()
                .status(node.getStatus() == TaskNodeStatus.SUCCESS ? "SUCCESS" : "FAILED")
                .currentStep(node.getStatus() == TaskNodeStatus.SUCCESS ? "完成补源" : "补源失败")
                .message(defaultIfBlank(node.getErrorMessage(),
                        node.getStatus() == TaskNodeStatus.SUCCESS ? "采集节点已完成，使用最小事件兜底留痕。" : "采集节点执行失败，请查看节点详情。"))
                .updatedAt(node.getCompletedAt() == null ? LocalDateTime.now() : node.getCompletedAt())
                .build());
        return objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
        });
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("failed to parse runtime event json", e);
            return null;
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean hasStructuredSearchPayload(SearchProgressEventPayload payload) {
        return payload.getSearchProgress() != null
                || payload.getSearchExecutionTrace() != null
                || (payload.getSearchProgressSnapshots() != null && !payload.getSearchProgressSnapshots().isEmpty())
                || payload.getSearchAudit() != null
                || (payload.getAttemptedTargets() != null && !payload.getAttemptedTargets().isEmpty())
                || (payload.getDiscardedCandidates() != null && !payload.getDiscardedCandidates().isEmpty())
                || (payload.getReplayTimeline() != null && !payload.getReplayTimeline().isEmpty())
                || (payload.getSelectedTargets() != null && !payload.getSelectedTargets().isEmpty())
                || payload.getCollectionAudit() != null
                || payload.getCollectionAuditSummary() != null
                || hasText(payload.getCollectionStatus())
                || (payload.getCollectionReplayTimeline() != null && !payload.getCollectionReplayTimeline().isEmpty())
                || (payload.getSourceUrls() != null && !payload.getSourceUrls().isEmpty());
    }

    /**
     * 搜索事实字段优先读取节点 output 顶层，兼容历史节点只写入 searchAudit 的情况。
     * 这样新事件保持扁平契约，旧数据也不会因为字段布局升级而丢失回放现场。
     */
    private <T> List<T> resolveSearchFactList(JsonNode directNode, List<T> auditFallback, TypeReference<List<T>> typeReference) {
        if (directNode != null && !directNode.isNull()) {
            return convertList(directNode, typeReference);
        }
        return auditFallback;
    }

    private <T> T convertValue(JsonNode node, Class<T> targetType) {
        if (node == null || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, targetType);
    }

    private <T> List<T> convertList(JsonNode node, TypeReference<List<T>> typeReference) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, typeReference);
    }

    private JsonNode firstPresent(JsonNode primary, JsonNode fallback) {
        return primary == null || primary.isNull() ? fallback : primary;
    }

    private List<CollectionReplayTimelineItem> resolveCollectionReplayTimeline(JsonNode output,
                                                                               CollectionAuditSnapshot collectionAudit) {
        if (collectionAudit != null && collectionAudit.getReplayTimeline() != null) {
            return collectionAudit.getReplayTimeline();
        }
        return convertList(firstPresent(output.get("collectionReplayTimeline"),
                output.get("replayTimeline")), new TypeReference<List<CollectionReplayTimelineItem>>() {
        });
    }

    private CollectionAuditSummary resolveCollectionAuditSummary(CollectionAuditSnapshot collectionAudit) {
        if (collectionAudit == null) {
            return null;
        }
        if (collectionAudit.getSummary() != null) {
            return collectionAudit.getSummary();
        }
        return CollectionAuditSummary.from(collectionAudit);
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
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
}
