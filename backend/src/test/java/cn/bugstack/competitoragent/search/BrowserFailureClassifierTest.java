package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器故障分类器测试。
 * 这里先用最小红灯测试锁定故障分类与恢复动作边界，避免后续运行时和采集器各自散落字符串判断。
 */
class BrowserFailureClassifierTest {

    private final BrowserFailureClassifier classifier = new BrowserFailureClassifier();

    @Test
    void shouldClassifyPipeClosedAsRuntimePipeBroken() {
        BrowserFailureDecision decision = classifier.classify(
                new IllegalStateException("playwright connection closed"),
                null
        );

        assertEquals(BrowserFailureKind.RUNTIME_PIPE_BROKEN, decision.kind());
        assertTrue(decision.recreateRuntime());
        assertTrue(decision.restartSharedBrowser());
        assertTrue(decision.allowSingleRetry());
    }

    @Test
    void shouldTreatCreateTargetFailureAsPageOrContextFailure() {
        BrowserFailureDecision decision = classifier.classify(
                new IllegalStateException("Protocol error (Target.createTarget): Failed to open a new tab"),
                null
        );

        assertEquals(BrowserFailureKind.PAGE_OR_CONTEXT_RESOURCE_FAILURE, decision.kind());
        assertFalse(decision.restartSharedBrowser());
        assertFalse(decision.recreateRuntime());
        assertFalse(decision.allowSingleRetry());
    }
}
