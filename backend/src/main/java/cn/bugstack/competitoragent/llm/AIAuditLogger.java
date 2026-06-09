package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 调用审计记录器骨架。
 * <p>
 * 在正式审计实体落地前，这里先提供统一审计挂载点，
 * 避免网关治理信息继续散落在业务层日志里。
 */
@Slf4j
@Component
public class AIAuditLogger {

    private final AiCallAuditRecordRepository repository;

    public AIAuditLogger(AiCallAuditRecordRepository repository) {
        this.repository = repository;
    }

    public void record(AuditEvent auditEvent) {
        if (auditEvent == null) {
            return;
        }
        repository.save(AiCallAuditRecord.builder()
                .taskId(auditEvent.getTaskId())
                .nodeName(auditEvent.getNodeName())
                .traceId(auditEvent.getTraceId())
                .capability(auditEvent.getCapability() == null ? "UNKNOWN" : auditEvent.getCapability().name())
                .providerKey(auditEvent.getProviderKey())
                .modelName(auditEvent.getModelName())
                .retryCount(auditEvent.getRetryCount())
                .fallbackUsed(auditEvent.isFallbackUsed())
                .success(auditEvent.isSuccess())
                .inputTokens(auditEvent.getTokenUsage() == null ? 0 : auditEvent.getTokenUsage().getInputTokens())
                .outputTokens(auditEvent.getTokenUsage() == null ? 0 : auditEvent.getTokenUsage().getOutputTokens())
                .totalTokens(auditEvent.getTokenUsage() == null ? 0 : auditEvent.getTokenUsage().getTotalTokens())
                .estimatedInputTokens(auditEvent.getEstimatedInputTokens())
                .estimatedCost(auditEvent.getEstimatedCost())
                .budgetDecision(auditEvent.getBudgetDecision())
                .providerErrorCode(auditEvent.getProviderErrorCode())
                .degradationCount(auditEvent.getDegradationCount())
                .summary(auditEvent.getSummary() == null || auditEvent.getSummary().isBlank()
                        ? "AI 调用已记录"
                        : auditEvent.getSummary())
                .build());
        log.debug("record ai audit event, capability={}, providerKey={}, modelName={}",
                auditEvent.getCapability(), auditEvent.getProviderKey(), auditEvent.getModelName());
    }

    /**
     * 当前只保留最小审计字段，后续 TDD 循环再扩展为正式实体。
     */
    @lombok.Data
    @lombok.Builder
    public static class AuditEvent {
        private Long taskId;
        private String nodeName;
        private String traceId;
        private AiCapability capability;
        private String providerKey;
        private String modelName;
        private Integer retryCount;
        private boolean fallbackUsed;
        private boolean success;
        private TokenUsage tokenUsage;
        private Integer estimatedInputTokens;
        private Double estimatedCost;
        private String budgetDecision;
        private String providerErrorCode;
        private Integer degradationCount;
        private String summary;
    }
}
