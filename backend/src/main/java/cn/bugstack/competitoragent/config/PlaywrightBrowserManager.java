package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Playwright 浏览器托管器。
 * 统一负责 Browser 的懒加载、健康检查与自动重启，避免单例浏览器失效后只能整服务重启。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaywrightBrowserManager {

    private final Playwright playwright;
    private final PlaywrightConfig.PlaywrightProperties props;
    private final AtomicReference<Browser> browserRef = new AtomicReference<>();
    private final Object monitor = new Object();

    @PostConstruct
    public void initialize() {
        Browser browser = ensureBrowser("startup");
        if (browser == null) {
            log.warn("Playwright 浏览器未能在启动期完成初始化，后续会在运行期按需重试");
        }
    }

    public Browser getBrowser() {
        return ensureBrowser("runtime acquire");
    }

    public Browser restartBrowser(String reason) {
        synchronized (monitor) {
            Browser current = browserRef.getAndSet(null);
            closeQuietly(current, reason);
            Browser relaunched = launchBrowser(reason);
            if (relaunched != null) {
                browserRef.set(relaunched);
            }
            return relaunched;
        }
    }

    public boolean isBrowserHealthy() {
        return isHealthy(browserRef.get());
    }

    @Scheduled(
            fixedDelayString = "${playwright.health-check-interval-millis:60000}",
            initialDelayString = "${playwright.health-check-initial-delay-millis:15000}"
    )
    public void maintainBrowserHealth() {
        Browser current = browserRef.get();
        if (current == null) {
            ensureBrowser("scheduled warmup");
            return;
        }
        if (!isHealthy(current)) {
            log.warn("检测到 Playwright 浏览器实例失活，准备自动重建");
            restartBrowser("scheduled health check");
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (monitor) {
            closeQuietly(browserRef.getAndSet(null), "application shutdown");
        }
    }

    private Browser ensureBrowser(String reason) {
        Browser current = browserRef.get();
        if (isHealthy(current)) {
            return current;
        }
        synchronized (monitor) {
            Browser rechecked = browserRef.get();
            if (isHealthy(rechecked)) {
                return rechecked;
            }
            Browser relaunched = launchBrowser(reason);
            if (relaunched != null) {
                browserRef.set(relaunched);
            }
            return relaunched;
        }
    }

    private Browser launchBrowser(String reason) {
        try {
            String browserType = normalizeBrowserType(props.getBrowser());
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(props.isHeadless())
                    .setTimeout((double) props.getTimeoutMillis());
            if ("chromium".equals(browserType) && StringUtils.hasText(props.getChannel())) {
                options.setChannel(props.getChannel().trim());
            }
            log.info("启动 Playwright 浏览器: browser={}, channel={}, headless={}, reason={}",
                    browserType, props.getChannel(), props.isHeadless(), reason);
            return switch (browserType) {
                case "firefox" -> playwright.firefox().launch(options);
                case "webkit" -> playwright.webkit().launch(options);
                default -> playwright.chromium().launch(options);
            };
        } catch (Exception e) {
            log.error("启动 Playwright 浏览器失败, browser={}, reason={}, error={}",
                    props.getBrowser(), reason, e.getMessage(), e);
            return null;
        }
    }

    private boolean isHealthy(Browser browser) {
        if (browser == null) {
            return false;
        }
        try {
            String version = browser.version();
            return StringUtils.hasText(version);
        } catch (Exception e) {
            log.warn("Playwright 浏览器健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private void closeQuietly(Browser browser, String reason) {
        if (browser == null) {
            return;
        }
        try {
            log.info("关闭 Playwright 浏览器实例, reason={}", reason);
            browser.close();
        } catch (Exception e) {
            log.warn("关闭 Playwright 浏览器实例失败, reason={}, error={}", reason, e.getMessage());
        }
    }

    private String normalizeBrowserType(String browser) {
        if (!StringUtils.hasText(browser)) {
            return "chromium";
        }
        return browser.trim().toLowerCase();
    }
}
