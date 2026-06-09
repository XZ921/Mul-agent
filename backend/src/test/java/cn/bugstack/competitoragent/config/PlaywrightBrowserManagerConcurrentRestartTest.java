package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightBrowserManagerConcurrentRestartTest {

    @Test
    void shouldNotCloseFreshBrowserWhenRestartTriggeredByStaleBrowserReference() {
        Playwright playwright = mock(Playwright.class);
        PlaywrightRuntimeFactory runtimeFactory = mock(PlaywrightRuntimeFactory.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser staleBrowser = mock(Browser.class);
        Browser freshBrowser = mock(Browser.class);

        when(staleBrowser.version()).thenReturn("123.0");
        when(freshBrowser.version()).thenReturn("124.0");

        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setHeadless(true);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(staleBrowser, freshBrowser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, runtimeFactory, properties);
        Browser originalBrowser = manager.getBrowser();
        Browser relaunchedBrowser = manager.restartBrowser("first restart");

        Browser browserAfterStaleRestartSignal = manager.restartBrowserIfCurrent(
                originalBrowser,
                "stale thread retry after browser already replaced"
        );

        assertSame(staleBrowser, originalBrowser);
        assertSame(freshBrowser, relaunchedBrowser);
        assertSame(freshBrowser, browserAfterStaleRestartSignal);
        verify(chromium, times(2)).launch(any(BrowserType.LaunchOptions.class));
        verify(freshBrowser, never()).close();
    }
}
