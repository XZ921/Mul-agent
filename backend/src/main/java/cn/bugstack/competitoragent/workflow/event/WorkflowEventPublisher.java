package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部工作流事件发布器。
 * <p>
 * 它只负责把编排语义收口为统一事件对象，
 * 持久化、补发和消费确认都交给 Outbox 服务处理。
 */
@Component
@RequiredArgsConstructor
public class WorkflowEventPublisher {

    private final WorkflowEventOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public void publishTaskCreated(AnalysisTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskName", task.getTaskName());
        payload.put("subjectProduct", task.getSubjectProduct());
        payload.put("status", task.getStatus() == null ? null : task.getStatus().name());
        payload.put("reportLanguage", task.getReportLanguage());
        payload.put("reportTemplate", task.getReportTemplate());
        payload.put("schemaId", task.getSchemaId());
        stage(WorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(task.getId())
                .planVersionId(task.getCurrentPlanVersionId())
                .branchKey(resolveTaskBranchKey(task))
                .eventType(WorkflowEventType.TASK_CREATED)
                .payload(payload)
                .sourceUrls(readStringList(task.getCompetitorUrls()))
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /**
     * 任务执行请求是在 AnalysisTaskService 的事务提交后触发的。
     * 在集成测试里我们使用同步执行器让 @Async 立即回调，此时 Spring 仍可能保留上一个事务资源直到 afterCommit 回调彻底结束。
     * 这里强制开启独立事务，确保 TASK_EXECUTION_REQUESTED 事件一定能够真实提交到 outbox，而不是“加入一个已经完成提交、但不会再次提交”的旧事务上下文。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishTaskExecutionRequested(AnalysisTask task, AgentContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskName", context.getTaskName());
        payload.put("subjectProduct", context.getSubjectProduct());
        payload.put("reportLanguage", context.getReportLanguage());
        payload.put("reportTemplate", context.getReportTemplate());
        payload.put("competitorNames", context.getCompetitorNames());
        payload.put("analysisDimensions", context.getAnalysisDimensions());
        payload.put("sourceScope", context.getSourceScope());
        stage(WorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(task.getId())
                .planVersionId(task.getCurrentPlanVersionId())
                .branchKey(resolveTaskBranchKey(task))
                .eventType(WorkflowEventType.TASK_EXECUTION_REQUESTED)
                .payload(payload)
                .sourceUrls(readStringList(context.getCompetitorUrls()))
                .occurredAt(LocalDateTime.now())
                .build());
    }

    public void publishNodeReady(TaskNode node) {
        Map<String, Object> payload = buildNodePayload(node);
        payload.put("status", "READY");
        stage(WorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(node.getTaskId())
                .nodeName(node.getNodeName())
                .planVersionId(node.getPlanVersionId())
                .branchKey(node.getBranchKey())
                .eventType(WorkflowEventType.NODE_READY)
                .payload(payload)
                .sourceUrls(List.of())
                .occurredAt(LocalDateTime.now())
                .build());
    }

    public void publishNodeCompleted(TaskNode node, List<String> sourceUrls) {
        Map<String, Object> payload = buildNodePayload(node);
        payload.put("status", node.getStatus() == null ? null : node.getStatus().name());
        stage(WorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(node.getTaskId())
                .nodeName(node.getNodeName())
                .planVersionId(node.getPlanVersionId())
                .branchKey(node.getBranchKey())
                .eventType(WorkflowEventType.NODE_COMPLETED)
                .payload(payload)
                .sourceUrls(resolveSourceUrls(node, sourceUrls))
                .occurredAt(LocalDateTime.now())
                .build());
    }

    public void publishNodeFailed(TaskNode node, List<String> sourceUrls) {
        Map<String, Object> payload = buildNodePayload(node);
        payload.put("status", node.getStatus() == null ? null : node.getStatus().name());
        stage(WorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(node.getTaskId())
                .nodeName(node.getNodeName())
                .planVersionId(node.getPlanVersionId())
                .branchKey(node.getBranchKey())
                .eventType(WorkflowEventType.NODE_FAILED)
                .payload(payload)
                .sourceUrls(resolveSourceUrls(node, sourceUrls))
                .occurredAt(LocalDateTime.now())
                .build());
    }

    private void stage(WorkflowEvent workflowEvent) {
        outboxService.stage(workflowEvent);
    }

    private Map<String, Object> buildNodePayload(TaskNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("displayName", node.getDisplayName());
        payload.put("agentType", node.getAgentType() == null ? null : node.getAgentType().name());
        payload.put("retryCount", node.getRetryCount());
        payload.put("executionOrder", node.getExecutionOrder());
        payload.put("planVersionId", node.getPlanVersionId());
        payload.put("branchKey", node.getBranchKey());
        payload.put("errorMessage", node.getErrorMessage());
        payload.put("startedAt", node.getStartedAt());
        payload.put("completedAt", node.getCompletedAt());
        return payload;
    }

    private String resolveTaskBranchKey(AnalysisTask task) {
        return task != null && task.getCurrentPlanVersionId() != null ? "root" : null;
    }

    private List<String> resolveSourceUrls(TaskNode node, List<String> explicitSourceUrls) {
        if (explicitSourceUrls != null && !explicitSourceUrls.isEmpty()) {
            return explicitSourceUrls;
        }
        if (node == null || node.getOutputData() == null || node.getOutputData().isBlank()) {
            return List.of();
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(node.getOutputData());
            JsonNode sourceUrls = jsonNode.get("sourceUrls");
            if (sourceUrls == null || !sourceUrls.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(sourceUrls, new TypeReference<List<String>>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> readStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
