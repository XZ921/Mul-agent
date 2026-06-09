package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Playwright;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Playwright 浏览器实例配置。
 * <p>
 * 真实采集与浏览器搜索链路都依赖统一的 Browser 单例，因此这里直接常驻装配。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PlaywrightConfig.PlaywrightProperties.class)
public class PlaywrightConfig {

    @Data
    @ConfigurationProperties(prefix = "playwright")
    public static class PlaywrightProperties {
        private String browser = "chromium";
        private String channel;
        private boolean headless = true;
        private int timeoutMillis = 30000;
        private boolean screenshotOnCollect = false;
        private long healthCheckIntervalMillis = 60000L;
        private long healthCheckInitialDelayMillis = 15000L;
    }

    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        log.info("初始化 Playwright 实例...");
        return Playwright.create();
    }

    @Bean
    public PlaywrightRuntimeFactory playwrightRuntimeFactory() {
        return () -> {
            log.info("重建 Playwright 运行时实例...");
            return Playwright.create();
        };
    }
}
