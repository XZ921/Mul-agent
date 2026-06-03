package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightBrowserManagerTest {

    @Test
    void shouldFallbackToSafeChromiumLaunchOptionsWhenPrimaryLaunchFails() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(false);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Playwright connection closed"))
                .thenReturn(browser);

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertSame(browser, launchedBrowser);
        ArgumentCaptor<BrowserType.LaunchOptions> optionsCaptor = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(chromium, times(2)).launch(optionsCaptor.capture());
        BrowserType.LaunchOptions primaryOptions = optionsCaptor.getAllValues().get(0);
        BrowserType.LaunchOptions fallbackOptions = optionsCaptor.getAllValues().get(1);

        assertSame(Boolean.FALSE, primaryOptions.headless);
        assertSame("msedge", primaryOptions.channel);
        assertSame(Boolean.TRUE, fallbackOptions.headless);
        assertNull(fallbackOptions.channel);
    }

    @Test
    void shouldReturnNullWhenPrimaryAndFallbackLaunchBothFail() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        PlaywrightConfig.PlaywrightProperties properties = new PlaywrightConfig.PlaywrightProperties();
        properties.setBrowser("chromium");
        properties.setChannel("msedge");
        properties.setHeadless(false);
        properties.setTimeoutMillis(30000);

        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new RuntimeException("Playwright connection closed"))
                .thenThrow(new RuntimeException("fallback launch still failed"));

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertNull(launchedBrowser);
        verify(chromium, times(2)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    void shouldLaunchConfiguredBrowserWithoutFallbackWhenPrimaryLaunchSucceeds() {
        Playwright playwright = mock(Playwright.class);
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

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(playwright, properties);

        Browser launchedBrowser = manager.getBrowser();

        assertNotNull(launchedBrowser);
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }
}
