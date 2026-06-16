package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 浏览器运行时诊断日志记录器。
 * 统一负责输出结构化诊断日志，并把同一套固定口径同步写入 MeterRegistry，
 * 这样现有 smoke/grep 检查、后续监控面板和告警都能复用同一份语义。
 */
@Slf4j
@Component
public class BrowserRuntimeDiagnosticLogger {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Autowired
    public BrowserRuntimeDiagnosticLogger(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public BrowserRuntimeDiagnosticLogger(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public BrowserRuntimeDiagnosticLogger() {
        this(new ObjectMapper().findAndRegisterModules(), null);
    }

    /**
     * 统一输出浏览器链路诊断日志，避免各调用方自行拼 JSON 导致字段口径不一致。
     */
    public void log(String event, BrowserRuntimeDiagnosticLog payload) {
        if (payload == null) {
            return;
        }
        BrowserRuntimeDiagnosticLog normalized = normalize(payload);
        try {
            log.warn("browser runtime diagnostic, event={}, payload={}",
                    safe(event),
                    objectMapper.writeValueAsString(normalized));
        } catch (Exception e) {
            log.warn("browser runtime diagnostic serialization failed, event={}, failureKind={}, blockedReasonCode={}, error={}",
                    safe(event),
                    safe(normalized.getFailureKind()),
                    safe(normalized.getBlockedReasonCode()),
                    e.getMessage());
        }
        emitCounters(normalized);
    }

    private BrowserRuntimeDiagnosticLog normalize(BrowserRuntimeDiagnosticLog payload) {
        return BrowserRuntimeDiagnosticLog.builder()
                .taskId(payload.getTaskId())
                .nodeName(payload.getNodeName())
                .competitorName(payload.getCompetitorName())
                .sourceType(payload.getSourceType())
                .query(payload.getQuery())
                .targetUrl(payload.getTargetUrl())
                .engineKey(payload.getEngineKey())
                .failureKind(payload.getFailureKind())
                .restartScope(payload.getRestartScope())
                .fallbackAction(payload.getFallbackAction())
                .blockedReasonCode(payload.getBlockedReasonCode())
                .matchedSignals(payload.getMatchedSignals() == null ? List.of() : payload.getMatchedSignals())
                .build();
    }

    /**
     * 诊断计数目前同时保留两种落点：
     * 1. 结构化计数日志，兼容 Task 7 已有 smoke 口径和日志平台检索。
     * 2. MeterRegistry 真实指标，供后续监控面板、告警和聚合查询直接消费。
     */
    private void emitCounters(BrowserRuntimeDiagnosticLog payload) {
        emitFailureCounter(payload);
        emitRestartCounter(payload);
        emitFallbackCounter(payload);
        emitAntiBotCounter(payload);
        emitWaitingInterventionCounter(payload);
    }

    /**
     * 每一次诊断只要带上 failureKind，就统一累计到 failure 指标。
     * 这里保留 kind tag，方便区分 runtime 断链、反爬拦截、内容不可用等不同故障类别。
     */
    private void emitFailureCounter(BrowserRuntimeDiagnosticLog payload) {
        if (!StringUtils.hasText(payload.getFailureKind())) {
            return;
        }
        String failureKind = payload.getFailureKind();
        log.info("browser runtime counter, metric=browser.runtime.failure.count, kind={}", failureKind);
        incrementCounter("browser.runtime.failure.count", "kind", failureKind);
    }

    /**
     * restartScope 需要先规整成 runtime/browser 两类标签，避免上游枚举扩展后把监控维度打散。
     */
    private void emitRestartCounter(BrowserRuntimeDiagnosticLog payload) {
        String restartScope = normalizeRestartScope(payload.getRestartScope());
        if (restartScope == null) {
            return;
        }
        log.info("browser runtime counter, metric=browser.runtime.restart.count, scope={}", restartScope);
        incrementCounter("browser.runtime.restart.count", "scope", restartScope);
    }

    /**
     * fallbackAction 同样需要规整标签，保证 planned/http/intervention 三类动作在日志与指标中完全一致。
     */
    private void emitFallbackCounter(BrowserRuntimeDiagnosticLog payload) {
        String fallbackAction = normalizeFallbackAction(payload.getFallbackAction());
        if (fallbackAction == null) {
            return;
        }
        log.info("browser runtime counter, metric=browser.fallback.count, action={}", fallbackAction);
        incrementCounter("browser.fallback.count", "action", fallbackAction);
    }

    /**
     * 只有明确分类成 ANTI_BOT_BLOCKED 时才累计反爬拦截指标，避免 suspected/content_unusable 污染 blocked 口径。
     */
    private void emitAntiBotCounter(BrowserRuntimeDiagnosticLog payload) {
        if (!"ANTI_BOT_BLOCKED".equalsIgnoreCase(payload.getFailureKind())) {
            return;
        }
        String reasonCode = safe(payload.getBlockedReasonCode());
        log.info("browser runtime counter, metric=browser.antibot.blocked.count, reasonCode={}", reasonCode);
        incrementCounter("browser.antibot.blocked.count", "reasonCode", reasonCode);
    }

    /**
     * waiting_intervention 既可能来自 fallbackAction，也可能来自 blockedReasonCode。
     * 这里保持原有兼容语义，只要任一条件命中就累计人工介入计数。
     */
    private void emitWaitingInterventionCounter(BrowserRuntimeDiagnosticLog payload) {
        String fallbackAction = normalizeFallbackAction(payload.getFallbackAction());
        if (!"intervention".equals(fallbackAction)
                && !"WAITING_INTERVENTION".equalsIgnoreCase(payload.getBlockedReasonCode())) {
            return;
        }
        String reasonCode = safe(payload.getBlockedReasonCode());
        log.info("browser runtime counter, metric=browser.waiting_intervention.count, reasonCode={}", reasonCode);
        incrementCounter("browser.waiting_intervention.count", "reasonCode", reasonCode);
    }

    /**
     * 指标注册表允许为空，是为了兼容单元测试里只关心日志输出的老调用方式。
     * 一旦 Spring 容器提供了 MeterRegistry，这里就会自动升级成真实指标写入。
     */
    private void incrementCounter(String metricName, String tagKey, String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(metricName, tagKey, safe(tagValue)).increment();
    }

    private String normalizeRestartScope(String restartScope) {
        if (!StringUtils.hasText(restartScope) || "NONE".equalsIgnoreCase(restartScope)) {
            return null;
        }
        String normalized = restartScope.trim().toUpperCase();
        if (normalized.contains("RUNTIME")) {
            return "runtime";
        }
        if (normalized.contains("BROWSER")) {
            return "browser";
        }
        return null;
    }

    private String normalizeFallbackAction(String fallbackAction) {
        if (!StringUtils.hasText(fallbackAction)) {
            return null;
        }
        String normalized = fallbackAction.trim().toUpperCase();
        if (normalized.contains("HTTP")) {
            return "http";
        }
        if (normalized.contains("PLANNED")) {
            return "planned";
        }
        if (normalized.contains("INTERVENTION")) {
            return "intervention";
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
