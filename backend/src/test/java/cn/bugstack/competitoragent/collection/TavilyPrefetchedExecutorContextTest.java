package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归锁定 Tavily fast-lane 执行器在 Spring 容器中的装配行为。
 * 当前 live 启动失败直接阻塞 Task 66-04 的实机复验，
 * 因此这里先固定一个最小上下文测试，避免出现“单元测试通过但应用启动时 Bean 装配失败”的回归。
 */
class TavilyPrefetchedExecutorContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateTavilyPrefetchedExecutorBeanWhenDependenciesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(TavilyPrefetchedExecutor.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TavilyPrefetchedExecutor.class)
    static class TestConfiguration {

        @Bean
        TavilyPrefetchedContentRegistry tavilyPrefetchedContentRegistry() {
            return new TavilyPrefetchedContentRegistry();
        }

        @Bean
        TavilyPrefetchedContentBlockClassifier tavilyPrefetchedContentBlockClassifier() {
            return new TavilyPrefetchedContentBlockClassifier();
        }
    }
}
