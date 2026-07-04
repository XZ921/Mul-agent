package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEventOutboxServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "rocketmq.enabled=true",
                    "rocketmq.required=true",
                    "rocketmq.name-server=127.0.0.1:9876",
                    "rocketmq.producer.group=competitor-agent-workflow-producer",
                    "rocketmq.consumer.group=competitor-agent-workflow-consumer",
                    "rocketmq.workflow.topic=task-workflow-events",
                    "rocketmq.workflow.dispatch-tag=TASK_EXECUTION_REQUESTED",
                    "rocketmq.workflow.lifecycle-tag=NODE_LIFECYCLE",
                    "rocketmq.workflow.outbox.scan-interval=5s",
                    "rocketmq.workflow.outbox.max-retries=6",
                    "rocketmq.workflow.outbox.batch-size=20"
            );

    @Test
    void shouldStartOutboxServiceWhenRocketMqPropertiesAreAvailable() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(RocketMqProperties.class);
            assertThat(context).hasSingleBean(WorkflowEventOutboxService.class);
        });
    }

    @Test
    void shouldRejectIllegalWorkflowTopicBeforeStaging() {
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        WorkflowEventOutboxService service = new WorkflowEventOutboxService(
                repository,
                buildProperties("task.collaboration"),
                new ObjectMapper(),
                mock(ObjectProvider.class)
        );

        assertThatThrownBy(() -> service.stage(WorkflowEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .taskId(88L)
                        .eventType(WorkflowEventType.COLLABORATION_PLAN_RECORDED)
                        .occurredAt(LocalDateTime.now())
                        .build()))
                .hasMessageContaining("rocketmq.workflow.topic")
                .hasMessageContaining("task.collaboration");
        verify(repository, never()).save(any(TaskWorkflowEvent.class));
    }

    @Test
    void shouldMoveHistoricalInvalidTopicCandidateDirectlyToDeadLetter() {
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        when(repository.save(any(TaskWorkflowEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskWorkflowEvent candidate = TaskWorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(83L)
                .nodeName("collaboration_plan")
                .eventType(WorkflowEventType.COLLABORATION_PLAN_RECORDED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
                .topic("task.collaboration")
                .tag("collaboration_plan_recorded")
                .retryCount(0)
                .maxRetryCount(6)
                .nextAttemptAt(LocalDateTime.now())
                .build();
        when(repository.findDispatchCandidates(anyCollection(), any(LocalDateTime.class), any()))
                .thenReturn(List.of(candidate));
        WorkflowEventOutboxService service = new WorkflowEventOutboxService(
                repository,
                buildProperties("task_workflow_events"),
                new ObjectMapper(),
                mock(ObjectProvider.class)
        );

        service.flushPendingEvents();

        assertThat(candidate.getDeliveryStatus()).isEqualTo(TaskWorkflowEvent.STATUS_DEAD_LETTER);
        assertThat(candidate.getRetryCount()).isZero();
        assertThat(candidate.getLastError()).isEqualTo("invalid rocketmq topic: task.collaboration");
        verify(repository).save(candidate);
    }

    private RocketMqProperties buildProperties(String topic) {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setEnabled(true);
        properties.setRequired(true);
        properties.setNameServer("127.0.0.1:9876");
        properties.getProducer().setGroup("competitor-agent-workflow-producer");
        properties.getConsumer().setGroup("competitor-agent-workflow-consumer");
        properties.getWorkflow().setTopic(topic);
        properties.getWorkflow().setDispatchTag("TASK_EXECUTION_REQUESTED");
        properties.getWorkflow().setLifecycleTag("NODE_LIFECYCLE");
        properties.getWorkflow().getOutbox().setScanInterval(Duration.ofSeconds(5));
        properties.getWorkflow().getOutbox().setMaxRetries(6);
        properties.getWorkflow().getOutbox().setBatchSize(20);
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConfigurationPropertiesScan(basePackageClasses = RocketMqProperties.class)
    static class TestConfiguration {

        @Bean
        TaskWorkflowEventRepository taskWorkflowEventRepository() {
            return mock(TaskWorkflowEventRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WorkflowEventOutboxService workflowEventOutboxService(TaskWorkflowEventRepository repository,
                                                              RocketMqProperties properties,
                                                              ObjectMapper objectMapper,
                                                              ObjectProvider<org.apache.rocketmq.spring.core.RocketMQTemplate> rocketMQTemplateProvider) {
            return new WorkflowEventOutboxService(repository, properties, objectMapper, rocketMQTemplateProvider);
        }
    }
}
