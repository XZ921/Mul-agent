package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 OpenAiCompatibleClient 的 Spring 装配契约。
 * 当前类为了测试超时与序列化逻辑额外暴露了包级构造器，
 * 因此必须显式验证 Spring 仍然能够选择业务构造器完成注入，
 * 避免出现“单测能 new，应用启动却找不到默认构造器”的回归。
 */
class OpenAiCompatibleClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AiProviderProperties.class, AiProviderProperties::new)
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateOpenAiCompatibleClientBeanWhenAiProviderPropertiesExists() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(OpenAiCompatibleClient.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(OpenAiCompatibleClient.class)
    static class TestConfiguration {
    }
}
