package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class BrowserRuntimeDiagnosticLoggerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BrowserRuntimeDiagnosticLogger diagnosticLogger =
            new BrowserRuntimeDiagnosticLogger(new ObjectMapper(), meterRegistry);

    @Test
    void shouldEmitTask7ExpectedCounterMetrics(CapturedOutput output) {
        /*
         * 这里同时校验两层契约：
         * 1. Task 7 已固定的结构化计数日志口径不能回归，方便现有 smoke 和 grep 继续复用。
         * 2. 同一套口径需要真实写入 MeterRegistry，后续监控面板和告警可以直接消费。
         */
        diagnosticLogger.log("runtime_pipe_broken", BrowserRuntimeDiagnosticLog.builder()
                .failureKind("RUNTIME_PIPE_BROKEN")
                .restartScope("RUNTIME_AND_BROWSER")
                .fallbackAction("PLANNED_FALLBACK")
                .build());
        diagnosticLogger.log("anti_bot_blocked", BrowserRuntimeDiagnosticLog.builder()
                .failureKind("ANTI_BOT_BLOCKED")
                .restartScope("NONE")
                .fallbackAction("HTTP_FALLBACK")
                .blockedReasonCode("LOGIN_OR_CHALLENGE_REDIRECT")
                .matchedSignals(List.of("title:Verify you are human", "url:/challenge"))
                .build());
        diagnosticLogger.log("waiting_intervention", BrowserRuntimeDiagnosticLog.builder()
                .failureKind("CONTENT_UNUSABLE")
                .restartScope("NONE")
                .fallbackAction("WAITING_INTERVENTION")
                .blockedReasonCode("WAITING_INTERVENTION")
                .build());

        assertEquals(1.0, meterRegistry.get("browser.runtime.failure.count")
                .tag("kind", "RUNTIME_PIPE_BROKEN")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.runtime.failure.count")
                .tag("kind", "ANTI_BOT_BLOCKED")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.runtime.failure.count")
                .tag("kind", "CONTENT_UNUSABLE")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.runtime.restart.count")
                .tag("scope", "runtime")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.antibot.blocked.count")
                .tag("reasonCode", "LOGIN_OR_CHALLENGE_REDIRECT")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.fallback.count")
                .tag("action", "planned")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.fallback.count")
                .tag("action", "http")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.fallback.count")
                .tag("action", "intervention")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("browser.waiting_intervention.count")
                .tag("reasonCode", "WAITING_INTERVENTION")
                .counter()
                .count());

        String combinedOutput = output.getOut() + output.getErr();
        assertTrue(combinedOutput.contains("metric=browser.runtime.failure.count, kind=RUNTIME_PIPE_BROKEN"));
        assertTrue(combinedOutput.contains("metric=browser.runtime.restart.count, scope=runtime"));
        assertTrue(combinedOutput.contains("metric=browser.antibot.blocked.count, reasonCode=LOGIN_OR_CHALLENGE_REDIRECT"));
        assertTrue(combinedOutput.contains("metric=browser.fallback.count, action=planned"));
        assertTrue(combinedOutput.contains("metric=browser.fallback.count, action=http"));
        assertTrue(combinedOutput.contains("metric=browser.waiting_intervention.count, reasonCode=WAITING_INTERVENTION"));
    }
}
