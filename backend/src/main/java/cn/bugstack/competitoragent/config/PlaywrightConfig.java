package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Playwright 浏览器实例配置
 * <p>
 * 仅在 collector.mock=false 时启用（需要预先执行 `mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install chromium"`）
 * <p>
 * 使用单例 Browser 实例复用，应用关闭时通过 {@link Playwright#close()} 释放资源。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "collector.mock", havingValue = "false")
public class PlaywrightConfig {

    @Data
    @ConfigurationProperties(prefix = "playwright")
    public static class PlaywrightProperties {
        private String browser = "chromium";
        private boolean headless = true;
        private int timeoutMillis = 30000;
        private boolean screenshotOnCollect = false;
    }

    @Bean
    public PlaywrightProperties playwrightProperties() {
        return new PlaywrightProperties();
    }

    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        log.info("初始化 Playwright 实例...");
        return Playwright.create();
    }

    @Bean(destroyMethod = "close")
    public Browser browser(Playwright playwright, PlaywrightProperties props) {
        log.info("启动浏览器: browser={}, headless={}", props.getBrowser(), props.isHeadless());

        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(props.isHeadless())
                .setTimeout((double) props.getTimeoutMillis());

        return switch (props.getBrowser().toLowerCase()) {
            case "firefox" -> playwright.firefox().launch(options);
            case "webkit" -> playwright.webkit().launch(options);
            default -> playwright.chromium().launch(options);
        };
    }
}
