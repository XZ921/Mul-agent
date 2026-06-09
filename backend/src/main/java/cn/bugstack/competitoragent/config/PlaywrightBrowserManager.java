package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Playwright 浏览器托管器。
 * 统一负责 Browser 的懒加载、健康检查与自动重启，避免单例浏览器失效后只能整服务重启。
 */
@Slf4j
@Component
public class PlaywrightBrowserManager {

    private final Playwright initialPlaywright;
    private final PlaywrightRuntimeFactory runtimeFactory;
    private final PlaywrightConfig.PlaywrightProperties props;
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();
    private final AtomicReference<Browser> browserRef = new AtomicReference<>();
    private final Object monitor = new Object();

    public PlaywrightBrowserManager(Playwright initialPlaywright,
                                    PlaywrightRuntimeFactory runtimeFactory,
                                    PlaywrightConfig.PlaywrightProperties props) {
        this.initialPlaywright = initialPlaywright;
        this.runtimeFactory = runtimeFactory;
        this.props = props;
    }

    @PostConstruct
    public void initialize() {
        currentPlaywright();
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

    /**
     * 只有当“当前托管中的浏览器实例”仍然等于调用方手里的故障实例时，才执行真正的关闭与重建。
     * 这样可以避免多个并发线程同时感知到旧浏览器失活时，后到达的线程把前一个线程刚重建好的新实例再次关掉。
     */
    public Browser restartBrowserIfCurrent(Browser expectedBrowser, String reason) {
        if (expectedBrowser == null) {
            return ensureBrowser(reason);
        }
        synchronized (monitor) {
            Browser current = browserRef.get();
            if (current != null && current != expectedBrowser) {
                if (isHealthy(current)) {
                    log.info("跳过过期浏览器重启请求，当前已有更新实例在服务, reason={}", reason);
                    return current;
                }
            }

            Browser currentToReplace = browserRef.getAndSet(null);
            closeQuietly(currentToReplace, reason);
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
            closePlaywrightQuietly(playwrightRef.getAndSet(null), "application shutdown");
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
        String browserType = normalizeBrowserType(props.getBrowser());
        List<LaunchAttempt> attempts = buildLaunchAttempts(browserType);
        boolean runtimeRecreated = false;

        for (int index = 0; index < attempts.size(); ) {
            LaunchAttempt attempt = attempts.get(index);
            try {
                log.info("启动 Playwright 浏览器: browser={}, channel={}, headless={}, reason={}, attempt={}/{}",
                        browserType,
                        attempt.channel(),
                        attempt.headless(),
                        reason,
                        index + 1,
                        attempts.size());
                Browser browser = doLaunch(currentPlaywright(), browserType, attempt.options());
                if (index > 0) {
                    log.warn("Playwright 浏览器已通过降级参数启动成功, browser={}, strategy={}, reason={}",
                            browserType, attempt.label(), reason);
                }
                return browser;
            } catch (Exception e) {
                if (!runtimeRecreated && shouldRecreateRuntime(e) && recreatePlaywrightRuntime(reason, e)) {
                    runtimeRecreated = true;
                    continue;
                }
                if (index < attempts.size() - 1) {
                    log.warn("启动 Playwright 浏览器失败，准备降级重试, browser={}, strategy={}, reason={}, error={}",
                            browserType, attempt.label(), reason, e.getMessage());
                    index++;
                    continue;
                }
                log.error("启动 Playwright 浏览器失败, browser={}, reason={}, error={}",
                        props.getBrowser(), reason, e.getMessage(), e);
                break;
            }
        }
        return null;
    }

    /**
     * 浏览器启动属于整个采集与搜索链路的基础设施入口。
     * 这里显式构造“主配置 -> 保守降级配置”的候选序列，确保：
     * 1. 优先尊重用户当前配置；
     * 2. 在 Edge channel 或有头模式受限时，自动尝试更稳妥的 Chromium 兜底；
     * 3. 只有所有启动策略都失败时，才把本次启动判定为真正失败。
     */
    private List<LaunchAttempt> buildLaunchAttempts(String browserType) {
        List<LaunchAttempt> attempts = new ArrayList<>();
        attempts.add(new LaunchAttempt(
                "configured",
                buildLaunchOptions(props.isHeadless(), normalizedChannel(browserType)),
                normalizedChannel(browserType),
                props.isHeadless()
        ));

        if ("chromium".equals(browserType)
                && StringUtils.hasText(normalizedChannel(browserType))
                && !props.isHeadless()) {
            attempts.add(new LaunchAttempt(
                    "chromium-safe-fallback",
                    buildLaunchOptions(true, null),
                    null,
                    true
            ));
        }
        return attempts;
    }

    private BrowserType.LaunchOptions buildLaunchOptions(boolean headless, String channel) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setTimeout((double) props.getTimeoutMillis());
        if (StringUtils.hasText(channel)) {
            options.setChannel(channel);
        }
        return options;
    }

    /**
     * 浏览器启动必须显式绑定当前可用的 Playwright runtime。
     * 一旦 runtime 已经断链，这里会配合上层重建新的 runtime 后再重新尝试 launch。
     */
    private Browser doLaunch(Playwright runtime, String browserType, BrowserType.LaunchOptions options) {
        return switch (browserType) {
            case "firefox" -> runtime.firefox().launch(options);
            case "webkit" -> runtime.webkit().launch(options);
            default -> runtime.chromium().launch(options);
        };
    }

    /**
     * 某些 “Playwright connection closed” 场景不是 Browser 实例坏了，
     * 而是底层 Playwright 管道已经断开。此时只重启 Browser 没意义，
     * 必须整套重建 runtime 才能恢复后续 launch/newPage。
     */
    private boolean recreatePlaywrightRuntime(String reason, Exception cause) {
        try {
            Playwright recreated = runtimeFactory.create();
            if (recreated == null) {
                log.error("重建 Playwright runtime 失败, reason={}, triggerError={}, recreateError={}",
                        reason,
                        cause == null ? null : cause.getMessage(),
                        "factory returned null");
                return false;
            }
            Playwright previous = playwrightRef.getAndSet(recreated);
            closePlaywrightQuietly(previous, "recreate runtime before relaunch: " + reason);
            playwrightRef.set(recreated);
            log.warn("检测到 Playwright runtime 连接已断开，已重建运行时后重试, reason={}, error={}",
                    reason, cause == null ? null : cause.getMessage());
            return true;
        } catch (Exception recreateError) {
            log.error("重建 Playwright runtime 失败, reason={}, triggerError={}, recreateError={}",
                    reason,
                    cause == null ? null : cause.getMessage(),
                    recreateError.getMessage(),
                    recreateError);
            return false;
        }
    }

    private Playwright currentPlaywright() {
        Playwright runtime = playwrightRef.get();
        if (runtime != null) {
            return runtime;
        }
        playwrightRef.compareAndSet(null, initialPlaywright);
        return playwrightRef.get();
    }

    private String normalizedChannel(String browserType) {
        if (!"chromium".equals(browserType) || !StringUtils.hasText(props.getChannel())) {
            return null;
        }
        return props.getChannel().trim();
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

    private void closePlaywrightQuietly(Playwright runtime, String reason) {
        if (runtime == null) {
            return;
        }
        try {
            log.info("关闭 Playwright runtime 实例, reason={}", reason);
            runtime.close();
        } catch (Exception e) {
            log.warn("关闭 Playwright runtime 实例失败, reason={}, error={}", reason, e.getMessage());
        }
    }

    private String normalizeBrowserType(String browser) {
        if (!StringUtils.hasText(browser)) {
            return "chromium";
        }
        return browser.trim().toLowerCase();
    }

    private boolean shouldRecreateRuntime(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("playwright connection closed")
                || message.contains("connection closed")
                || message.contains("transport closed")
                || message.contains("pipe closed");
    }

    private record LaunchAttempt(String label,
                                 BrowserType.LaunchOptions options,
                                 String channel,
                                 boolean headless) {
    }
}
