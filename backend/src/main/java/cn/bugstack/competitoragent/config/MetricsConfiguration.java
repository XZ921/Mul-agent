package cn.bugstack.competitoragent.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 指标注册表配置。
 * 当前项目还没有显式接入 Actuator/Prometheus 导出链路，因此这里先提供一个默认的 SimpleMeterRegistry，
 * 让浏览器稳定性专项里的诊断计数可以先落到真实 MeterRegistry。
 * 后续如果接入更完整的监控基础设施，只需要替换掉这个 Bean，
 * 诊断日志层不需要再次改口径。
 */
@Configuration
public class MetricsConfiguration {

    @Bean
    @Primary
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
