package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicShellRecoveryExtractorTest {

    private final PublicShellRecoveryExtractor extractor = new PublicShellRecoveryExtractor();

    @Test
    void shouldRecoverMetaAndCanonicalFromLoginGate() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://docs.example.com/login");
        when(page.title()).thenReturn("Example Docs Login");
        when(page.content()).thenReturn("""
                <html>
                  <head>
                    <title>Example Docs Login</title>
                    <meta name="description" content="Example Docs provides API guides and product documentation.">
                    <meta property="og:title" content="Example Product Docs">
                    <link rel="canonical" href="https://docs.example.com/">
                    <script type="application/ld+json">{"@type":"WebSite","name":"Example Docs"}</script>
                  </head>
                  <body>Please sign in to continue</body>
                </html>
                """);

        SourceCollector.CollectedPage recovered = extractor.recover(
                page,
                "https://docs.example.com/login",
                "Example",
                "DOCS",
                AntiBotDetectionResult.builder()
                        .blocked(true)
                        .reasonCode("LOGIN_OR_CHALLENGE_REDIRECT")
                        .matchedSignals(List.of("url:/login"))
                        .build()
        );

        assertTrue(recovered.isSuccess());
        assertTrue(recovered.getContent().contains("Example Docs provides API guides"));
        assertTrue(recovered.getMetadata().contains("LOGIN_GATE_PARTIAL"));
        assertTrue(recovered.getMetadata().contains("PUBLIC_SHELL_ONLY"));
        assertTrue(recovered.getMetadata().contains("https://docs.example.com/"));
    }

    @Test
    void shouldRejectPureCaptchaShellWithoutUsefulPublicContent() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com/challenge");
        when(page.title()).thenReturn("Verify you are human");
        when(page.content()).thenReturn("""
                <html><head><title>Verify you are human</title></head>
                <body>captcha security check</body></html>
                """);

        SourceCollector.CollectedPage recovered = extractor.recover(
                page,
                "https://example.com/challenge",
                "Example",
                "OFFICIAL",
                AntiBotDetectionResult.builder()
                        .blocked(true)
                        .reasonCode("TEXT_SIGNAL_SHORT_BODY_BLOCKED")
                        .matchedSignals(List.of("body:captcha"))
                        .build()
        );

        assertFalse(recovered.isSuccess());
        assertTrue(recovered.getErrorMessage().contains("公开壳信息不足"));
    }
}
