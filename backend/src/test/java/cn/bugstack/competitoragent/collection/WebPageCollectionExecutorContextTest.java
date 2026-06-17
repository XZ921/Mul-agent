package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.JinaReaderClient;
import cn.bugstack.competitoragent.source.JinaReaderProperties;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 回归锁定 Spring 上下文中的执行器装配行为。
 * 第五轮把 WebPageCollectionExecutor 升级为 JinaReader + Playwright 双依赖后，
 * 如果 Spring 不能稳定选择正确构造器，集成测试会在容器启动阶段整体失效。
 */
class WebPageCollectionExecutorContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateWebPageCollectionExecutorBeanWhenDependenciesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(WebPageCollectionExecutor.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WebPageCollectionExecutor.class)
    static class TestConfiguration {

        @Bean
        JinaReaderProperties jinaReaderProperties() {
            return new JinaReaderProperties();
        }

        @Bean
        JinaReaderClient jinaReaderClient(JinaReaderProperties properties) {
            return new JinaReaderClient(properties, null);
        }

        @Bean
        SourceCollector sourceCollector() {
            return mock(SourceCollector.class);
        }
    }
}
