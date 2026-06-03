package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRuntimeFallbackPolicyTest {

    @Test
    void shouldPreferRuntimePolicyOverridesOverBrowserDefaults() {
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setContinueOnBrowserUnavailable(false);
        properties.setContinueOnSearchTimeout(false);
        properties.setContinueOnPageCollectFailure(false);
        properties.setRecoverPartialContentOnTimeout(false);

        SearchRuntimePolicy runtimePolicy = SearchRuntimePolicy.builder()
                .continueOnBrowserUnavailable(true)
                .continueOnSearchTimeout(true)
                .continueOnPageCollectFailure(true)
                .recoverPartialContentOnTimeout(true)
                .build();

        SearchRuntimeFallbackPolicy policy = new SearchRuntimeFallbackPolicy(properties);

        assertTrue(policy.shouldContinueOnBrowserUnavailable(runtimePolicy));
        assertTrue(policy.shouldContinueOnSearchTimeout(runtimePolicy));
        assertTrue(policy.shouldContinueOnPageCollectFailure(runtimePolicy));
        assertTrue(policy.shouldRecoverPartialContentOnTimeout(runtimePolicy));
    }

    @Test
    void shouldClassifyTimeoutAndBrowserCloseFailuresIntoStructuredCodes() {
        SearchRuntimeFallbackPolicy policy = new SearchRuntimeFallbackPolicy(new SearchBrowserProperties());

        assertEquals("search_timeout", policy.classifyRuntimeFailure(new IllegalStateException("Timeout 15000ms exceeded")));
        assertEquals("browser_unavailable", policy.classifyRuntimeFailure(new IllegalStateException("Target page, context or browser has been closed")));
        assertEquals("runtime_failure", policy.classifyRuntimeFailure(new IllegalStateException("unknown failure")));
    }

    @Test
    void shouldBuildTraceableFallbackMessagesForSearchAndCollection() {
        SearchRuntimeFallbackPolicy policy = new SearchRuntimeFallbackPolicy(new SearchBrowserProperties());

        assertTrue(policy.buildSearchFallbackSummary("search_timeout", "Timeout 15000ms exceeded").contains("超时"));
        assertTrue(policy.buildSearchFallbackSummary("browser_unavailable", null).contains("浏览器实例不可用"));
        assertTrue(policy.buildCollectionFailureMessage("browser_unavailable", "browser closed").contains("浏览器不可用"));
    }
}
