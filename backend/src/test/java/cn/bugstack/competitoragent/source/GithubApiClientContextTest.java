package cn.bugstack.competitoragent.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 GithubApiClient 的 Spring 装配契约。
 * 该客户端为了测试 HTTP 超时与重试行为保留了包级构造器，
 * 因此需要显式验证容器仍会选择生产构造器完成依赖注入，
 * 避免启动阶段再次退回查找无参构造器。
 */
class GithubApiClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateGithubApiClientBeanWhenPropertiesAndObjectMapperExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(GithubApiClient.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GithubApiProperties.class)
    @Import(GithubApiClient.class)
    static class TestConfiguration {
    }
}
