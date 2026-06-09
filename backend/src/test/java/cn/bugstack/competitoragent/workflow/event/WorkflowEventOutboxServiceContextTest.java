package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
