package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流事件 Outbox 服务。
 * <p>
 * 这里承载 Task 4.1 的核心语义：
 * 1. 业务事务内只负责落事件
 * 2. 事务提交后异步补发到 RocketMQ
 * 3. 发布成功与消费确认都要留下可审计记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEventOutboxService {

    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final RocketMqProperties rocketMqProperties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;

    @Transactional
    public void stage(WorkflowEvent workflowEvent) {
        if (workflowEvent == null || workflowEvent.getTaskId() == null || workflowEvent.getEventType() == null) {
            return;
        }
        TaskWorkflowEvent entity = TaskWorkflowEvent.builder()
                .eventId(workflowEvent.getEventId())
                .taskId(workflowEvent.getTaskId())
                .nodeName(workflowEvent.getNodeName())
                .planVersionId(workflowEvent.getPlanVersionId())
                .branchKey(workflowEvent.getBranchKey())
                .eventType(workflowEvent.getEventType())
                .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
                .topic(rocketMqProperties.getWorkflow().getTopic())
                .tag(resolveTag(workflowEvent.getEventType()))
                .payload(writeJsonSafely(workflowEvent.getPayload() == null ? Map.of() : workflowEvent.getPayload()))
                .sourceUrls(writeJsonSafely(workflowEvent.getSourceUrls() == null ? List.of() : workflowEvent.getSourceUrls()))
                .retryCount(0)
                .maxRetryCount(rocketMqProperties.getWorkflow().getOutbox().getMaxRetries())
                .nextAttemptAt(LocalDateTime.now())
                .build();
        TaskWorkflowEvent savedEntity = taskWorkflowEventRepository.save(entity);
        log.info("workflow event staged into outbox, eventType={}, taskId={}, entityId={}",
                workflowEvent.getEventType(),
                workflowEvent.getTaskId(),
                savedEntity.getId());
    }

    public void assertWorkflowIngressReady() {
        try {
            rocketMqProperties.validateForExecution();
        } catch (IllegalStateException e) {
            throw new BusinessException(ResultCode.WORKFLOW_DISPATCH_UNAVAILABLE, e.getMessage(), e);
        }
    }

    /**
     * 这里直接读取配置属性而不是引用特定 bean 名，避免 @ConfigurationProperties 扫描得到的实际 bean 命名
     * 与 SpEL 中的硬编码名称不一致时，调度器在容器启动阶段就提前失败。
     * 同时通过 Spring Boot 的 DurationStyle 解析 5s / 1m 这类时长写法，
     * 避免 @Scheduled 只能把 fixedDelayString 当成纯数字解析而导致启动报错。
     */
    @Scheduled(fixedDelayString =
            "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${rocketmq.workflow.outbox.scan-interval:5s}').toMillis()}")
    public void flushPendingEvents() {
        if (!rocketMqProperties.isEnabled()) {
            return;
        }
        List<TaskWorkflowEvent> candidates = taskWorkflowEventRepository.findDispatchCandidates(
                List.of(TaskWorkflowEvent.STATUS_PENDING),
                LocalDateTime.now(),
                PageRequest.of(0, rocketMqProperties.getWorkflow().getOutbox().getBatchSize()));
        for (TaskWorkflowEvent candidate : candidates) {
            publishCandidate(candidate);
        }
    }

    public boolean shouldConsume(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        return taskWorkflowEventRepository.findByEventId(eventId)
                .map(event -> !TaskWorkflowEvent.STATUS_CONSUMED.equals(event.getDeliveryStatus()))
                .orElse(false);
    }

    @Transactional
    public void markConsumed(String eventId) {
        taskWorkflowEventRepository.findByEventId(eventId).ifPresent(event -> {
            event.setDeliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED);
            event.setConsumedAt(LocalDateTime.now());
            event.setLastError(null);
            taskWorkflowEventRepository.save(event);
        });
    }

    private void publishCandidate(TaskWorkflowEvent candidate) {
        try {
            assertWorkflowIngressReady();
            RocketMQTemplate template = rocketMQTemplateProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("RocketMQTemplate is not available");
            }
            template.syncSend(buildDestination(candidate), buildMessageBody(candidate),
                    rocketMqProperties.getProducer().getSendMessageTimeoutMillis());
            candidate.setDeliveryStatus(TaskWorkflowEvent.STATUS_PUBLISHED);
            candidate.setPublishedAt(LocalDateTime.now());
            candidate.setLastError(null);
            taskWorkflowEventRepository.save(candidate);
        } catch (Exception e) {
            handlePublishFailure(candidate, e);
        }
    }

    private void handlePublishFailure(TaskWorkflowEvent candidate, Exception e) {
        int nextRetryCount = candidate.getRetryCount() + 1;
        candidate.setRetryCount(nextRetryCount);
        candidate.setLastError(e.getMessage());
        if (nextRetryCount >= candidate.getMaxRetryCount()) {
            candidate.setDeliveryStatus(TaskWorkflowEvent.STATUS_DEAD_LETTER);
            candidate.setNextAttemptAt(LocalDateTime.now());
            log.error("workflow event moved to DLQ trace, eventId={}, taskId={}, eventType={}",
                    candidate.getEventId(), candidate.getTaskId(), candidate.getEventType(), e);
        } else {
            candidate.setDeliveryStatus(TaskWorkflowEvent.STATUS_PENDING);
            candidate.setNextAttemptAt(LocalDateTime.now().plus(rocketMqProperties.getWorkflow().getOutbox().getScanInterval()));
            log.warn("workflow event publish failed, will retry. eventId={}, taskId={}, retry={}/{}",
                    candidate.getEventId(),
                    candidate.getTaskId(),
                    nextRetryCount,
                    candidate.getMaxRetryCount(),
                    e);
        }
        taskWorkflowEventRepository.save(candidate);
    }

    private String buildDestination(TaskWorkflowEvent candidate) {
        return candidate.getTopic() + ":" + candidate.getTag();
    }

    private String buildMessageBody(TaskWorkflowEvent candidate) throws JsonProcessingException {
        WorkflowEvent workflowEvent = WorkflowEvent.builder()
                .eventId(candidate.getEventId())
                .taskId(candidate.getTaskId())
                .nodeName(candidate.getNodeName())
                .planVersionId(candidate.getPlanVersionId())
                .branchKey(candidate.getBranchKey())
                .eventType(candidate.getEventType())
                .payload(readPayload(candidate.getPayload()))
                .sourceUrls(readSourceUrls(candidate.getSourceUrls()))
                .occurredAt(candidate.getCreatedAt())
                .build();
        return objectMapper.writeValueAsString(workflowEvent);
    }

    private String resolveTag(WorkflowEventType eventType) {
        if (eventType == WorkflowEventType.TASK_EXECUTION_REQUESTED) {
            return rocketMqProperties.getWorkflow().getDispatchTag();
        }
        return rocketMqProperties.getWorkflow().getLifecycleTag();
    }

    private String writeJsonSafely(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow event payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String rawPayload) throws JsonProcessingException {
        if (rawPayload == null || rawPayload.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(rawPayload, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> readSourceUrls(String rawSourceUrls) throws JsonProcessingException {
        if (rawSourceUrls == null || rawSourceUrls.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(rawSourceUrls, List.class);
    }
}
