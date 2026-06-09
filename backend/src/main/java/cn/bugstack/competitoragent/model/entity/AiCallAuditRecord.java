package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 调用审计实体。
 * <p>
 * 它只保留用户和治理层真正关心的关键事实：
 * 哪个任务 / 节点调用了哪个 Provider、是否发生重试 / 降级、最终造成了什么影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_call_audit_record", indexes = {
        @Index(name = "idx_ai_audit_task_id", columnList = "taskId"),
        @Index(name = "idx_ai_audit_node_name", columnList = "nodeName"),
        @Index(name = "idx_ai_audit_trace_id", columnList = "traceId"),
        @Index(name = "idx_ai_audit_created_at", columnList = "createdAt")
})
@Schema(description = "AI 调用审计记录")
public class AiCallAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long taskId;

    @Column(length = 100)
    private String nodeName;

    @Column(length = 50)
    private String traceId;

    @Column(length = 30, nullable = false)
    private String capability;

    @Column(length = 50, nullable = false)
    private String providerKey;

    @Column(length = 120)
    private String modelName;

    @Column
    private Integer retryCount;

    @Column(nullable = false)
    private boolean fallbackUsed;

    @Column(nullable = false)
    private boolean success;

    @Column
    private Integer inputTokens;

    @Column
    private Integer outputTokens;

    @Column
    private Integer totalTokens;

    /**
     * 预算守卫给出的预计输入 Token。
     * 它和实际 tokenUsage 分开保存，便于回放时区分“调用前预算估算”和“调用后实际消耗”。
     */
    @Column
    private Integer estimatedInputTokens;

    /**
     * 当前统一使用估算成本，既可承载调用前预算估值，也可承载调用后的实际成本估算。
     */
    @Column
    private Double estimatedCost;

    @Column(length = 60)
    private String budgetDecision;

    @Column(length = 80)
    private String providerErrorCode;

    @Column
    private Integer degradationCount;

    /**
     * 面向用户和审查者的可读摘要。
     * 这里明确不直接存原始 SDK 参数，避免主路径被底层细节污染。
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
