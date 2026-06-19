package cn.bugstack.competitoragent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMqPropertiesTest {

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
    void shouldBindWorkflowTopicAndOutboxRetrySettings() {
        contextRunner.run(context -> {
            RocketMqProperties properties = context.getBean(RocketMqProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isRequired()).isTrue();
            assertThat(properties.getNameServer()).isEqualTo("127.0.0.1:9876");
            assertThat(properties.getProducer().getGroup())
                    .isEqualTo("competitor-agent-workflow-producer");
            assertThat(properties.getConsumer().getGroup())
                    .isEqualTo("competitor-agent-workflow-consumer");
            assertThat(properties.getWorkflow().getTopic()).isEqualTo("task-workflow-events");
            assertThat(properties.getWorkflow().getDispatchTag()).isEqualTo("TASK_EXECUTION_REQUESTED");
            assertThat(properties.getWorkflow().getLifecycleTag()).isEqualTo("NODE_LIFECYCLE");
            assertThat(properties.getWorkflow().getOutbox().getScanInterval()).hasSeconds(5);
            assertThat(properties.getWorkflow().getOutbox().getMaxRetries()).isEqualTo(6);
            assertThat(properties.getWorkflow().getOutbox().getBatchSize()).isEqualTo(20);
        });
    }

    @Test
    void shouldRejectEnabledWorkflowTransportWithoutProducerGroup() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "rocketmq.enabled=true",
                        "rocketmq.required=true",
                        "rocketmq.name-server=127.0.0.1:9876",
                        "rocketmq.workflow.topic=task-workflow-events"
                )
                .run(context -> {
                    RocketMqProperties properties = context.getBean(RocketMqProperties.class);
                    assertThatThrownBy(properties::validateForExecution)
                            .hasMessageContaining("producer.group");
                });
    }

    @Test
    void shouldEnableDefaultProfileAgainstLocalDockerRocketMq() throws IOException {
        RocketMqProperties properties = bindDefaultDocumentProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isRequired()).isTrue();
        assertThat(properties.getNameServer()).isEqualTo("127.0.0.1:9876");
        assertThatCode(properties::validateForExecution).doesNotThrowAnyException();
    }

    @Test
    void shouldProvidePhase4IntegrationProfileAndRocketMqComposeFixture() throws IOException {
        Properties phase4IntegrationProperties = loadYamlProperties(
                new ClassPathResource("application-phase4-integration.yml")
        );
        String rocketMqComposeYaml = new ClassPathResource("compose/rocketmq-compose.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        // Phase 4 鍥炲綊闆嗘垚娴嬭瘯蹇呴』鏈夊彲鐩存帴鍚姩鐨勬樉寮廙ocketMQ profile锛岄伩鍏嶄緷璧栦汉宸ラ殣钘忕幆澧冦€?
        assertThat(phase4IntegrationProperties.getProperty("rocketmq.name-server")).isEqualTo("127.0.0.1:9876");
        assertThat(phase4IntegrationProperties.getProperty("spring.autoconfigure.exclude[0]"))
                .isEqualTo("org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration");

        // 鍚屼竴浠借祫婧愯繕瑕佽兘璁╂湰鍦颁笌 CI 鏄惧紡鎷夎捣 namesrv / broker锛岃€屼笉鏄粎鍦ㄦ枃妗ｉ噷鍙ｅご璇存槑銆?
        assertThat(rocketMqComposeYaml).contains("services:");
        assertThat(rocketMqComposeYaml).contains("namesrv:");
        assertThat(rocketMqComposeYaml).contains("broker:");
        assertThat(rocketMqComposeYaml).contains("9876:9876");
        assertThat(rocketMqComposeYaml).contains("10911:10911");
    }

    @Test
    void shouldExcludeRocketMqAutoConfigurationInTestProfileWhenWorkflowTransportIsDisabled() throws IOException {
        Properties testProfileProperties = loadYamlDocumentProperties(1);

        assertThat(testProfileProperties.getProperty("rocketmq.enabled")).isEqualTo("false");
        assertThat(testProfileProperties.getProperty("spring.autoconfigure.exclude[0]"))
                .isEqualTo("org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration");
    }

    private RocketMqProperties bindDefaultDocumentProperties() throws IOException {
        Properties properties = loadYamlDocumentProperties(0);

        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new PropertiesPropertySource("defaultApplicationYaml", properties));
        Map<String, Object> environmentProperties = new HashMap<>(System.getenv());
        propertySources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", environmentProperties));
        propertySources.addLast(new PropertiesPropertySource("systemProperties", System.getProperties()));

        // 按照 Spring 配置加载时的占位符解析语义，把默认值表达式解析成最终生效值，
        // 这样测试校验的是“项目启动后真正拿到的 RocketMQ 配置”，而不是 YAML 原始文本。
        PropertySourcesPropertyResolver propertyResolver = new PropertySourcesPropertyResolver(propertySources);
        Properties resolvedProperties = new Properties();
        properties.forEach((key, value) -> resolvedProperties.put(
                key,
                value instanceof String text ? propertyResolver.resolvePlaceholders(text) : value
        ));

        MutablePropertySources resolvedPropertySources = new MutablePropertySources();
        resolvedPropertySources.addFirst(new PropertiesPropertySource("resolvedDefaultApplicationYaml", resolvedProperties));

        Binder binder = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(resolvedPropertySources));
        return binder.bind("rocketmq", Bindable.of(RocketMqProperties.class))
                .orElseThrow(() -> new IllegalStateException("rocketmq properties should exist in default application.yml"));
    }

    private Properties loadYamlDocumentProperties(int documentIndex) throws IOException {
        String applicationYaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> documents = List.of(applicationYaml.split("(?m)^---\\s*$"));
        return loadYamlProperties(new ByteArrayResource(documents.get(documentIndex).getBytes(StandardCharsets.UTF_8)));
    }

    private Properties loadYamlProperties(org.springframework.core.io.Resource resource) {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(resource);
        return yamlFactory.getObject();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RocketMqProperties.class)
    static class TestConfiguration {
    }
}
