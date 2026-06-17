package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 JinaReaderClient 的 Spring 装配契约。
 * 第五轮把轻量正文采集抽成独立组件后，必须保证容器能稳定注入配置属性并创建客户端。
 */
class JinaReaderClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JinaReaderProperties.class, JinaReaderProperties::new)
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateJinaReaderClientBeanWhenPropertiesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(JinaReaderClient.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(JinaReaderClient.class)
    static class TestConfiguration {
    }
}
