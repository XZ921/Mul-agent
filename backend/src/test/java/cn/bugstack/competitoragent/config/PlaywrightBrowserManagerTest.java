package cn.bugstack.competitoragent.config;

import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLog;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightBrowserManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSkipStartupWarmupWhenDisabled() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setStartupWarmupEnabled(false);

        when(playwright.chromium()).thenReturn(chromium);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        manager.initialize();

        verify(chromium, never()).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldSkipScheduledWarmupWhenNoBrowserIsLoaded() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");

        when(playwright.chromium()).thenReturn(chromium);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        manager.maintainBrowserHealth();

        verify(chromium, never()).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldWarmupBrowserOnScheduleWhenEnabled() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setHealthCheckWarmupEnabled(true);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        manager.maintainBrowserHealth();

        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldWarmupBrowserOnStartupWhenEnabled() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setStartupWarmupEnabled(true);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        when(browser.version()).thenReturn("125.0");

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        manager.initialize();

        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
        assertSame(browser, manager.getBrowser());
    }

    @Test
    void shouldNotFallbackToPlainChromiumWhenMsedgeLaunchFailsWithoutExecutablePath() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(false);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Chromium distribution 'msedge' is not found"));

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertNull(launchedBrowser);
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldFallbackToConfiguredEdgeExecutablePathWhenMsedgeChannelLaunchFails() throws Exception {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        Path edgeExecutable = Files.createFile(tempDir.resolve("msedge.exe"));
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(true);
        properties.setExecutablePath(edgeExecutable.toString());
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Chromium distribution 'msedge' is not found"))
                .thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertSame(browser, launchedBrowser);
        ArgumentCaptor<BrowserType.LaunchOptions> optionsCaptor = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(chromium, times(2)).launch(optionsCaptor.capture());
        BrowserType.LaunchOptions primaryOptions = optionsCaptor.getAllValues().get(0);
        BrowserType.LaunchOptions executablePathFallbackOptions = optionsCaptor.getAllValues().get(1);

        assertSame("msedge", primaryOptions.channel);
        assertNull(primaryOptions.executablePath);
        assertNull(executablePathFallbackOptions.channel);
        assertEquals(edgeExecutable, executablePathFallbackOptions.executablePath);
        assertSame(Boolean.TRUE, executablePathFallbackOptions.headless);
    }

    @Test
    void shouldUseExecutablePathAsPrimaryWhenChannelIsBlank() throws Exception {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        Path chromiumExecutable = Files.createFile(tempDir.resolve("chrome.exe"));
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setHeadless(true);
        properties.setExecutablePath(chromiumExecutable.toString());
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertSame(browser, launchedBrowser);
        ArgumentCaptor<BrowserType.LaunchOptions> optionsCaptor = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(chromium, times(1)).launch(optionsCaptor.capture());
        BrowserType.LaunchOptions primaryOptions = optionsCaptor.getValue();

        assertNull(primaryOptions.channel);
        assertEquals(chromiumExecutable, primaryOptions.executablePath);
        assertSame(Boolean.TRUE, primaryOptions.headless);
    }

    @Test
    void shouldRecreateRuntimeAgainBeforeExecutablePathFallbackAfterRepeatedPipeClosedLaunchFailure() throws Exception {
        Playwright initialPlaywright = mock(Playwright.class);
        Playwright recreatedForPrimaryRetry = mock(Playwright.class);
        Playwright recreatedForExecutableFallback = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType initialChromium = mock(BrowserType.class);
        BrowserType recreatedPrimaryChromium = mock(BrowserType.class);
        BrowserType fallbackChromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        Path edgeExecutable = Files.createFile(tempDir.resolve("msedge.exe"));
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(true);
        properties.setExecutablePath(edgeExecutable.toString());
        properties.setTimeoutMillis(30000);

        when(initialPlaywright.chromium()).thenReturn(initialChromium);
        when(recreatedForPrimaryRetry.chromium()).thenReturn(recreatedPrimaryChromium);
        when(recreatedForExecutableFallback.chromium()).thenReturn(fallbackChromium);
        when(runtimeFactory.create()).thenReturn(recreatedForPrimaryRetry, recreatedForExecutableFallback);
        when(initialChromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Failed to read message from driver, pipe closed."));
        when(recreatedPrimaryChromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Failed to read message from driver, pipe closed."));
        when(fallbackChromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(initialPlaywright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertSame(browser, launchedBrowser);
        verify(runtimeFactory, times(2)).create();
        ArgumentCaptor<BrowserType.LaunchOptions> fallbackOptionsCaptor = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(fallbackChromium, times(1)).launch(fallbackOptionsCaptor.capture());
        BrowserType.LaunchOptions fallbackOptions = fallbackOptionsCaptor.getValue();

        assertNull(fallbackOptions.channel);
        assertEquals(edgeExecutable, fallbackOptions.executablePath);
    }

    @Test
    void shouldReturnNullWhenConfiguredLaunchFailsWithoutExecutablePath() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(false);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Chromium distribution 'msedge' is not found"));

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertNull(launchedBrowser);
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldLaunchConfiguredBrowserWithoutFallbackWhenPrimaryLaunchSucceeds() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        when(browser.version()).thenReturn("123.0");

        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(false);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertNotNull(launchedBrowser);
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldRecreatePlaywrightRuntimeWhenLaunchFailsBecauseConnectionClosed() {
        Playwright initialPlaywright = mock(Playwright.class);
        Playwright recreatedPlaywright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserRuntimeDiagnosticLogger diagnosticLogger = mock(BrowserRuntimeDiagnosticLogger.class);
        BrowserType initialChromium = mock(BrowserType.class);
        BrowserType recreatedChromium = mock(BrowserType.class);
        Browser recreatedBrowser = mock(Browser.class);
        when(recreatedBrowser.version()).thenReturn("124.0");

        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setHeadless(true);
        properties.setTimeoutMillis(30000);

        when(initialPlaywright.chromium()).thenReturn(initialChromium);
        when(recreatedPlaywright.chromium()).thenReturn(recreatedChromium);
        when(runtimeFactory.create()).thenReturn(recreatedPlaywright);
        when(initialChromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Playwright connection closed"));
        when(recreatedChromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(recreatedBrowser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(
                initialPlaywright,
                runtimeFactory,
                properties,
                diagnosticLogger
        );

        Browser launchedBrowser = manager.getBrowser();

        assertSame(recreatedBrowser, launchedBrowser);
        verify(runtimeFactory, times(1)).create();
        verify(recreatedChromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
        ArgumentCaptor<BrowserRuntimeDiagnosticLog> launchCaptor =
                ArgumentCaptor.forClass(BrowserRuntimeDiagnosticLog.class);
        verify(diagnosticLogger).log(eq("playwright_launch_failure"), launchCaptor.capture());
        BrowserRuntimeDiagnosticLog launchLog = launchCaptor.getValue();
        assertEquals("PLAYWRIGHT_RUNTIME", launchLog.getSourceType());
        assertEquals("chromium", launchLog.getEngineKey());
        assertEquals("RUNTIME_PIPE_BROKEN", launchLog.getFailureKind());
        assertEquals("RUNTIME_AND_BROWSER", launchLog.getRestartScope());
        assertEquals("RETRY_LAUNCH", launchLog.getFallbackAction());

        ArgumentCaptor<BrowserRuntimeDiagnosticLog> recreateCaptor =
                ArgumentCaptor.forClass(BrowserRuntimeDiagnosticLog.class);
        verify(diagnosticLogger, atLeastOnce()).log(eq("playwright_runtime_recreate"), recreateCaptor.capture());
        BrowserRuntimeDiagnosticLog recreateLog = recreateCaptor.getValue();
        assertEquals("PLAYWRIGHT_RUNTIME", recreateLog.getSourceType());
        assertEquals("chromium", recreateLog.getEngineKey());
        assertEquals("RUNTIME_PIPE_BROKEN", recreateLog.getFailureKind());
        assertEquals("RUNTIME_AND_BROWSER", recreateLog.getRestartScope());
        assertEquals("RETRY_LAUNCH", recreateLog.getFallbackAction());
    }
}
