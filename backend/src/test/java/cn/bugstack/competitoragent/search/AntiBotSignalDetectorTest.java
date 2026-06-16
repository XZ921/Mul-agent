package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiBotSignalDetectorTest {

    @Test
    void shouldMark403AsBlocked() {
        AntiBotSignalDetector detector = new AntiBotSignalDetector(defaultProperties());

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .httpStatus(403)
                .finalUrl("https://example.com/captcha")
                .pageTitle("Access Denied")
                .bodyText("Access denied")
                .build());

        assertTrue(result.isBlocked());
        assertEquals("HTTP_STATUS_BLOCKED", result.getReasonCode());
    }

    @Test
    void shouldMarkLoginOrChallengeRedirectAsBlocked() {
        AntiBotSignalDetector detector = new AntiBotSignalDetector(defaultProperties());

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .finalUrl("https://example.com/challenge")
                .pageTitle("Verify you are human")
                .bodyText("Please complete the security check before continuing.")
                .bodyLength(52)
                .build());

        assertTrue(result.isBlocked());
        assertEquals("LOGIN_OR_CHALLENGE_REDIRECT", result.getReasonCode());
    }

    @Test
    void shouldOnlyMarkAsSuspectedWhenTextSignalExistsButStructureLooksNormal() {
        AntiBotSignalDetector detector = new AntiBotSignalDetector(defaultProperties());

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .finalUrl("https://example.com/docs")
                .pageTitle("Product Docs")
                .bodyText("""
                        This article explains how to resolve unusual traffic warnings when developers
                        automate nightly verification jobs. The page is a normal documentation page
                        with full setup steps, remediation guidance, examples, and troubleshooting notes.
                        """)
                .bodyLength(228)
                .primaryResultCount(3)
                .build());

        assertFalse(result.isBlocked());
        assertTrue(result.isSuspected());
        assertEquals("TEXT_SIGNAL_ONLY", result.getReasonCode());
    }

    @Test
    void shouldMarkShortBodyWithTextSignalAsBlocked() {
        AntiBotSignalDetector detector = new AntiBotSignalDetector(defaultProperties());

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .finalUrl("https://example.com/help/article")
                .pageTitle("Automation Help")
                .bodyText("Verify you are human")
                .bodyLength(21)
                .primaryResultCount(0)
                .build());

        assertTrue(result.isBlocked());
        assertEquals("TEXT_SIGNAL_SHORT_BODY_BLOCKED", result.getReasonCode());
    }

    @Test
    void shouldMarkBodyTooShortAsSuspectedWhenBelowConfiguredThreshold() {
        SearchBrowserProperties properties = defaultProperties();
        properties.setShortBodyThreshold(120);
        AntiBotSignalDetector detector = new AntiBotSignalDetector(properties);

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .finalUrl("https://example.com/docs")
                .pageTitle("Docs")
                .bodyText("short text")
                .bodyLength(10)
                .primaryResultCount(2)
                .build());

        assertFalse(result.isBlocked());
        assertTrue(result.isSuspected());
        assertEquals("BODY_TOO_SHORT", result.getReasonCode());
    }

    @Test
    void shouldMarkMissingPrimaryResultsAsSuspectedWhenCountBelowConfiguredMinimum() {
        SearchBrowserProperties properties = defaultProperties();
        properties.setMinimumPrimaryResultCount(1);
        AntiBotSignalDetector detector = new AntiBotSignalDetector(properties);

        AntiBotDetectionResult result = detector.detect(BrowserSignalSnapshot.builder()
                .finalUrl("https://example.com/search?q=test")
                .pageTitle("Search")
                .bodyText("normal body content with enough length to avoid short body detection")
                .bodyLength(160)
                .primaryResultCount(0)
                .build());

        assertFalse(result.isBlocked());
        assertTrue(result.isSuspected());
        assertEquals("MISSING_PRIMARY_RESULTS", result.getReasonCode());
    }

    private SearchBrowserProperties defaultProperties() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setBlockedSignals(java.util.List.of(
                "captcha",
                "unusual traffic",
                "verify you are human",
                "access denied",
                "robot check",
                "security check"
        ));
        properties.setBlockedUrlKeywords(java.util.List.of(
                "/login",
                "/signin",
                "/verify",
                "/captcha",
                "/challenge"
        ));
        properties.setShortBodyThreshold(120);
        properties.setMinimumPrimaryResultCount(1);
        properties.setSuspectBlockedBodyThreshold(40);
        return properties;
    }
}
