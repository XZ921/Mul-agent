package cn.bugstack.competitoragent.config;

import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLog;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
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
    private final BrowserRuntimeDiagnosticLogger diagnosticLogger;
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();
    private final AtomicReference<Browser> browserRef = new AtomicReference<>();
    private final Object monitor = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public PlaywrightBrowserManager(Playwright initialPlaywright,
                                    PlaywrightRuntimeFactory runtimeFactory,
                                    PlaywrightConfig.PlaywrightProperties props,
                                    BrowserRuntimeDiagnosticLogger diagnosticLogger) {
        this.initialPlaywright = initialPlaywright;
        this.runtimeFactory = runtimeFactory;
        this.props = props;
        this.diagnosticLogger = diagnosticLogger;
    }

    public PlaywrightBrowserManager(Playwright initialPlaywright,
                                    PlaywrightRuntimeFactory runtimeFactory,
                                    PlaywrightConfig.PlaywrightProperties props) {
        this(initialPlaywright, runtimeFactory, props, new BrowserRuntimeDiagnosticLogger());
    }

    @PostConstruct
    public void initialize() {
        if (!props.isStartupWarmupEnabled()) {
            log.info("Playwright 启动期浏览器预热已关闭，将在运行期获取或定时健康检查时按需启动");
            return;
        }
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

    /**
     * 对外暴露 runtime 重建入口。
     * 搜索运行时和页面采集在命中“管道断开”类故障时必须先重建 runtime，
     * 不能只做 browser 级重启，否则后续 launch/newPage 仍会继续失败。
     */
    public boolean recreateRuntimeForFailure(String reason, Exception cause) {
        synchronized (monitor) {
            return recreatePlaywrightRuntime(reason, cause);
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
            if (!props.isHealthCheckWarmupEnabled()) {
                log.debug("Playwright 定时健康检查跳过空浏览器预热，将在运行期首次获取时按需启动");
                return;
            }
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

        for (int index = 0; index < attempts.size(); ) {
            LaunchAttempt attempt = attempts.get(index);
            boolean retriedCurrentAttemptAfterRuntimeRecreate = false;
            while (true) {
                try {
                    log.info("启动 Playwright 浏览器: browser={}, channel={}, executablePath={}, headless={}, reason={}, attempt={}/{}",
                            browserType,
                            attempt.channel(),
                            attempt.executablePath(),
                            attempt.headless(),
                            reason,
                            index + 1,
                            attempts.size());
                    Browser browser = doLaunch(currentPlaywright(), browserType, attempt.options());
                    if (index > 0) {
                        log.warn("Playwright 浏览器已通过备用参数启动成功, browser={}, strategy={}, reason={}",
                                browserType, attempt.label(), reason);
                    }
                    return browser;
                } catch (Exception e) {
                    boolean runtimeRecreated = shouldRecreateRuntime(e) && recreatePlaywrightRuntime(reason, e);
                    logLaunchDiagnostic(
                            "playwright_launch_failure",
                            browserType,
                            attempt,
                            reason,
                            e,
                            runtimeRecreated ? "RUNTIME_AND_BROWSER" : (index < attempts.size() - 1 ? "BROWSER" : "NONE"),
                            (runtimeRecreated || index < attempts.size() - 1) ? "RETRY_LAUNCH" : "FAIL_LAUNCH"
                    );
                    if (runtimeRecreated && !retriedCurrentAttemptAfterRuntimeRecreate) {
                        retriedCurrentAttemptAfterRuntimeRecreate = true;
                        continue;
                    }
                    if (index < attempts.size() - 1) {
                        log.warn("启动 Playwright 浏览器失败，准备使用备用参数重试, browser={}, strategy={}, reason={}, error={}",
                                browserType, attempt.label(), reason, e.getMessage());
                        index++;
                        break;
                    }
                    log.error("启动 Playwright 浏览器失败, browser={}, reason={}, error={}",
                            props.getBrowser(), reason, e.getMessage(), e);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 浏览器启动属于整个采集与搜索链路的基础设施入口。
     * 这里显式构造“主配置 -> 真实浏览器路径备用配置”的候选序列，确保：
     * 1. 优先尊重用户当前配置；
     * 2. Edge channel 因运行环境缺少系统路径变量失败时，可继续使用 executablePath 启动真实 Edge；
     * 3. 当用户已经显式配置 executablePath 且没有 channel 时，直接使用真实浏览器路径，避免先尝试 Playwright 默认缓存浏览器导致 driver 管道断开；
     * 4. 只有所有启动策略都失败时，才把本次启动判定为真正失败。
     */
    private List<LaunchAttempt> buildLaunchAttempts(String browserType) {
        List<LaunchAttempt> attempts = new ArrayList<>();
        String channel = normalizedChannel(browserType);
        Path executablePath = normalizedExecutablePath(browserType);

        if (executablePath != null && !StringUtils.hasText(channel)) {
            attempts.add(new LaunchAttempt(
                    "configured-executable-path",
                    buildLaunchOptions(props.isHeadless(), null, executablePath),
                    null,
                    executablePath,
                    props.isHeadless()
            ));
            return attempts;
        }

        attempts.add(new LaunchAttempt(
                "configured",
                buildLaunchOptions(props.isHeadless(), channel, null),
                channel,
                null,
                props.isHeadless()
        ));

        if (executablePath != null) {
            attempts.add(new LaunchAttempt(
                    "configured-executable-path",
                    buildLaunchOptions(props.isHeadless(), null, executablePath),
                    null,
                    executablePath,
                    props.isHeadless()
            ));
        }

        return attempts;
    }

    private BrowserType.LaunchOptions buildLaunchOptions(boolean headless, String channel, Path executablePath) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setTimeout((double) props.getTimeoutMillis());
        if (StringUtils.hasText(channel)) {
            options.setChannel(channel);
        }
        if (executablePath != null) {
            options.setExecutablePath(executablePath);
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
                logRuntimeRecreateDiagnostic(reason, cause, "FAIL_RECREATE");
                log.error("重建 Playwright runtime 失败, reason={}, triggerError={}, recreateError={}",
                        reason,
                        cause == null ? null : cause.getMessage(),
                        "factory returned null");
                return false;
            }
            Playwright previous = playwrightRef.getAndSet(recreated);
            closePlaywrightQuietly(previous, "recreate runtime before relaunch: " + reason);
            playwrightRef.set(recreated);
            logRuntimeRecreateDiagnostic(reason, cause, "RETRY_LAUNCH");
            log.warn("检测到 Playwright runtime 连接已断开，已重建运行时后重试, reason={}, error={}",
                    reason, cause == null ? null : cause.getMessage());
            return true;
        } catch (Exception recreateError) {
            logRuntimeRecreateDiagnostic(reason, cause, "FAIL_RECREATE");
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

    private Path normalizedExecutablePath(String browserType) {
        if (!"chromium".equals(browserType) || !StringUtils.hasText(props.getExecutablePath())) {
            return null;
        }
        // executablePath 只用于显式指定真实浏览器路径，不改变 browser/channel 的主配置语义。
        return Path.of(props.getExecutablePath().trim());
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

    /**
     * 浏览器启动与 runtime 重建属于基础设施层关键观察点，
     * 这里统一补齐失败类型、重启范围和当前策略，便于日志平台按固定口径聚合。
     */
    private void logLaunchDiagnostic(String event,
                                     String browserType,
                                     LaunchAttempt attempt,
                                     String reason,
                                     Exception error,
                                     String restartScope,
                                     String fallbackAction) {
        diagnosticLogger.log(event, BrowserRuntimeDiagnosticLog.builder()
                .sourceType("PLAYWRIGHT_RUNTIME")
                .query(reason)
                .targetUrl(attempt == null || attempt.executablePath() == null ? null : attempt.executablePath().toString())
                .engineKey(browserType)
                .failureKind(shouldRecreateRuntime(error) ? "RUNTIME_PIPE_BROKEN" : "RUNTIME_FAILURE")
                .restartScope(restartScope)
                .fallbackAction(fallbackAction)
                .blockedReasonCode(error == null ? null : error.getMessage())
                .matchedSignals(List.of(
                        "strategy:" + (attempt == null ? "unknown" : attempt.label()),
                        "channel:" + (attempt == null ? "" : String.valueOf(attempt.channel()))
                ))
                .build());
    }

    private void logRuntimeRecreateDiagnostic(String reason, Exception cause, String fallbackAction) {
        diagnosticLogger.log("playwright_runtime_recreate", BrowserRuntimeDiagnosticLog.builder()
                .sourceType("PLAYWRIGHT_RUNTIME")
                .query(reason)
                .engineKey(normalizeBrowserType(props.getBrowser()))
                .failureKind("RUNTIME_PIPE_BROKEN")
                .restartScope("RUNTIME_AND_BROWSER")
                .fallbackAction(fallbackAction)
                .blockedReasonCode(cause == null ? null : cause.getMessage())
                .matchedSignals(cause == null || cause.getMessage() == null
                        ? List.of()
                        : List.of("error:" + cause.getMessage()))
                .build());
    }

    private record LaunchAttempt(String label,
                                 BrowserType.LaunchOptions options,
                                 String channel,
                                 Path executablePath,
                                 boolean headless) {
    }
}
