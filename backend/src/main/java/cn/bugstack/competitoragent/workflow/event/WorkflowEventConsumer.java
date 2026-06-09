package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 工作流事件消费者。
 * <p>
 * Task 4.1 先让 TASK_EXECUTION_REQUESTED 成为正式编排接管入口，
 * 其它生命周期事件当前以“可消费、可留痕”为主，为后续恢复与补偿任务打底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${rocketmq.workflow.topic}",
        consumerGroup = "${rocketmq.consumer.group}",
        selectorExpression = "*")
public class WorkflowEventConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final WorkflowEventOutboxService workflowEventOutboxService;
    private final AnalysisTaskRunner analysisTaskRunner;

    @Override
    public void onMessage(String message) {
        WorkflowEvent workflowEvent = parseMessage(message);
        if (!workflowEventOutboxService.shouldConsume(workflowEvent.getEventId())) {
            log.debug("skip duplicated workflow event consume, eventId={}", workflowEvent.getEventId());
            return;
        }

        try {
            if (workflowEvent.getEventType() == WorkflowEventType.TASK_EXECUTION_REQUESTED) {
                analysisTaskRunner.consumeTaskExecutionRequested(workflowEvent);
            } else {
                log.debug("workflow lifecycle event consumed for audit only, eventId={}, type={}",
                        workflowEvent.getEventId(), workflowEvent.getEventType());
            }
            workflowEventOutboxService.markConsumed(workflowEvent.getEventId());
        } catch (Exception e) {
            log.error("consume workflow event failed, eventId={}, type={}",
                    workflowEvent.getEventId(), workflowEvent.getEventType(), e);
            throw new IllegalStateException("Failed to consume workflow event " + workflowEvent.getEventId(), e);
        }
    }

    private WorkflowEvent parseMessage(String message) {
        try {
            return objectMapper.readValue(message, WorkflowEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse workflow event payload", e);
        }
    }
}
