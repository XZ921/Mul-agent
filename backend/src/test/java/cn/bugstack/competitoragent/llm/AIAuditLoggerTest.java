package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AIAuditLoggerTest {

    private final AiCallAuditRecordRepository repository = mock(AiCallAuditRecordRepository.class);
    private final AIAuditLogger aiAuditLogger = new AIAuditLogger(repository);

    @Test
    void shouldPersistTaskAndNodeReadableAuditSummary() {
        // 正式审计对象必须能直接回答“这次调用发生了什么影响”，
        // 不能只剩底层 SDK 参数或零散日志。
        aiAuditLogger.record(AIAuditLogger.AuditEvent.builder()
                .taskId(101L)
                .nodeName("analyze_competitors")
                .traceId("trace-001")
                .capability(AiCapability.CHAT)
                .providerKey("siliconflow")
                .modelName("siliconflow-chat")
                .retryCount(2)
                .fallbackUsed(true)
                .degradationCount(1)
                .summary("主 Provider 超时，已切换到备用 Provider")
                .budgetDecision("FALLBACK_RECOVERED")
                .estimatedInputTokens(1200)
                .estimatedCost(0.48D)
                .providerErrorCode("HTTP_429")
                .tokenUsage(new TokenUsage(10, 20, 30))
                .success(true)
                .build());

        verify(repository).save(argThat(record -> matches(record)));
    }

    private boolean matches(AiCallAuditRecord record) {
        assertEquals(101L, record.getTaskId());
        assertEquals("analyze_competitors", record.getNodeName());
        assertEquals("trace-001", record.getTraceId());
        assertEquals("CHAT", record.getCapability());
        assertEquals("siliconflow", record.getProviderKey());
        assertEquals("主 Provider 超时，已切换到备用 Provider", record.getSummary());
        assertEquals(30, record.getTotalTokens());
        assertEquals("FALLBACK_RECOVERED", record.getBudgetDecision());
        assertEquals(Integer.valueOf(1200), record.getEstimatedInputTokens());
        assertEquals(Double.valueOf(0.48D), record.getEstimatedCost());
        assertEquals("HTTP_429", record.getProviderErrorCode());
        assertEquals(Integer.valueOf(1), record.getDegradationCount());
        return true;
    }
}
