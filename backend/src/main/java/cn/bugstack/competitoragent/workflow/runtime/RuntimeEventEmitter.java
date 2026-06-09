package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时事件发布协作者。
 * <p>
 * 节点完成后既要发布节点状态事件，又要补发搜索进度、诊断和日志兜底事件。
 * 抽出后 DagExecutor 只需要声明“节点执行完成，需要发一组运行时事件”，不再自己拼装所有 payload。
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
     * Collector 事件优先透传结构化搜索过程；如果缺失，再退化为最小可恢复事件。
     */
    private Map<String, Object> buildSearchProgressEventPayload(TaskNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", node.getNodeName());
        JsonNode output = readJson(node.getOutputData());
        if (output != null) {
            JsonNode searchProgress = output.get("searchProgress");
            JsonNode executionTrace = output.get("searchExecutionTrace");
            JsonNode progressSnapshots = output.get("searchProgressSnapshots");
            if (searchProgress != null && !searchProgress.isNull()) {
                payload.put("searchProgress", objectMapper.convertValue(searchProgress, new TypeReference<Map<String, Object>>() {
                }));
            }
            if (executionTrace != null && !executionTrace.isNull()) {
                payload.put("searchExecutionTrace", objectMapper.convertValue(executionTrace, new TypeReference<Map<String, Object>>() {
                }));
            }
            if (progressSnapshots != null && progressSnapshots.isArray()) {
                payload.put("searchProgressSnapshots", objectMapper.convertValue(progressSnapshots, new TypeReference<List<Map<String, Object>>>() {
                }));
            }
        }

        if (payload.size() > 1) {
            return payload;
        }

        payload.put("searchProgress", Map.of(
                "status", node.getStatus() == TaskNodeStatus.SUCCESS ? "SUCCESS" : "FAILED",
                "currentStep", node.getStatus() == TaskNodeStatus.SUCCESS ? "完成补源" : "补源失败",
                "message", defaultIfBlank(node.getErrorMessage(),
                        node.getStatus() == TaskNodeStatus.SUCCESS ? "采集节点已完成，使用最小事件留痕兜底。" : "采集节点执行失败，请查看节点详情。"),
                "updatedAt", node.getCompletedAt() == null ? LocalDateTime.now() : node.getCompletedAt()
        ));
        return payload;
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
}
