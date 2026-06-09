package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.converter.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 意图决策审计实体。
 * 每次自然语言输入都会形成一条审计记录，说明系统为什么把它识别成某种模式或动作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "intent_decision",
        indexes = {
                @Index(name = "idx_intent_decision_session_id", columnList = "conversation_session_id"),
                @Index(name = "idx_intent_decision_task_id", columnList = "task_id")
        })
public class IntentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_session_id", nullable = false)
    private Long conversationSessionId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "page_type", nullable = false, length = 40)
    private String pageType;

    @Column(name = "mode", nullable = false, length = 40)
    private String mode;

    @Column(name = "intent_type", nullable = false, length = 80)
    private String intentType;

    @Column(name = "user_message", columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "decision_payload", columnDefinition = "TEXT")
    private String decisionPayload;

    @Builder.Default
    @Column(name = "high_risk_action", nullable = false)
    private boolean highRiskAction = false;

    @Builder.Default
    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation = false;

    /**
     * 结构化风险等级。
     * 保留独立列是为了让审计查询和统计不必每次反序列化 decisionPayload。
     */
    @Builder.Default
    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel = "LOW";

    /**
     * 结构化影响范围。
     * 这里存的是稳定枚举，而不是自然语言，便于后续确认流或运营报表直接复用。
     */
    @Builder.Default
    @Column(name = "impact_scope", nullable = false, length = 80)
    private String impactScope = "NONE";

    @Column(name = "confirmation_request_payload", columnDefinition = "TEXT")
    private String confirmationRequestPayload;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
        if (this.riskLevel == null || this.riskLevel.isBlank()) {
            this.riskLevel = "LOW";
        }
        if (this.impactScope == null || this.impactScope.isBlank()) {
            this.impactScope = "NONE";
        }
    }
}
