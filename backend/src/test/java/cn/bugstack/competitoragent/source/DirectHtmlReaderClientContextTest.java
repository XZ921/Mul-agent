package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 DirectHtmlReaderClient 的 Spring 装配契约。
 * 采集链路后续会依赖该 Bean 作为 Direct -> Jina -> Playwright 的第一段入口，
 * 因此需要先保证配置属性存在时容器能够稳定创建客户端。
 */
class DirectHtmlReaderClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DirectHtmlReaderProperties.class, DirectHtmlReaderProperties::new)
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateDirectHtmlReaderClientBeanWhenPropertiesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(DirectHtmlReaderClient.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(DirectHtmlReaderClient.class)
    static class TestConfiguration {
    }
}
