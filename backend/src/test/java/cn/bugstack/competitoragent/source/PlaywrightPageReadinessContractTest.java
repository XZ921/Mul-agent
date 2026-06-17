package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.search.AntiBotSignalDetector;
import cn.bugstack.competitoragent.search.BrowserFailureClassifier;
import cn.bugstack.competitoragent.search.BrowserRuntimeDiagnosticLogger;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchRuntimeFallbackPolicy;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第五轮 Task 1 的 Playwright 页面就绪红灯测试。
 * 这里先锁死 FULL_RENDER 正式路径必须等待“可提取页面”而不是只等导航完成，
 * 避免后续继续沿用“只要能打开页面就立刻提取”的脆弱语义。
 */
class PlaywrightPageReadinessContractTest {

    @Test
    void shouldWaitForRenderableContentBeforeExtractingFullRenderPage() {
        PlaywrightBrowserManager browserManager = mock(PlaywrightBrowserManager.class);
        CollectorProperties collectorProperties = new CollectorProperties();
        SearchRuntimeFallbackPolicy fallbackPolicy =
                new SearchRuntimeFallbackPolicy(new SearchBrowserProperties());
        BrowserFailureClassifier browserFailureClassifier = new BrowserFailureClassifier();
        AntiBotSignalDetector antiBotSignalDetector =
                new AntiBotSignalDetector(new SearchBrowserProperties());
        BrowserRuntimeDiagnosticLogger diagnosticLogger = mock(BrowserRuntimeDiagnosticLogger.class);
        PlaywrightPageCollector collector = new PlaywrightPageCollector(
                browserManager,
                collectorProperties,
                fallbackPolicy,
                browserFailureClassifier,
                antiBotSignalDetector,
                diagnosticLogger
        );
        Browser browser = mock(Browser.class);
        Page page = mock(Page.class);
        when(browserManager.getBrowser()).thenReturn(browser);
        when(browser.newPage()).thenReturn(page);
        when(page.title()).thenReturn("Pricing");
        when(page.url()).thenReturn("https://pricing.example.com");
        when(page.evaluate(anyString())).thenReturn(List.of(
                Map.of(
                        "selector", "main",
                        "tagName", "MAIN",
                        "className", "pricing-card",
                        "idName", "",
                        "text", "完整定价内容，包含套餐、配额、计费周期与企业说明。",
                        "linkTextLength", 0
                )
        ));

        collector.collect(SourceCollectRequest.builder()
                .url("https://pricing.example.com")
                .competitorName("Acme AI")
                .sourceType("PRICING")
                .renderHint(WebPageRenderHint.FULL_RENDER)
                .expectedBlockTypes(List.of("PRICING_BLOCK"))
                .sourceUrls(List.of("https://pricing.example.com"))
                .build());

        verify(page).waitForLoadState(LoadState.DOMCONTENTLOADED);
        verify(page).waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class));
    }
}
