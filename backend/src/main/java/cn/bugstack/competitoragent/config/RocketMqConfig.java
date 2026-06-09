package cn.bugstack.competitoragent.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 工作流基础配置。
 * <p>
 * 这里的守卫只负责在“显式启用且显式要求必须可用”的前提下，
 * 尽早把缺配置的问题暴露出来，避免主链路运行到一半才发现基础设施未就绪。
 */
@Configuration
public class RocketMqConfig {

    @Bean
    public ApplicationRunner rocketMqConfigurationGuard(RocketMqProperties properties) {
        return args -> {
            if (properties.isEnabled() && properties.isRequired()) {
                properties.validateForExecution();
            }
        };
    }
}
