package cn.bugstack.competitoragent.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一对话会话实体。
 * 它不是普通聊天记录，而是任务上下文的一部分，用于挂住任务、报告和最近一次决策状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversation_session",
        indexes = {
                @Index(name = "idx_conversation_session_task_id", columnList = "task_id"),
                @Index(name = "idx_conversation_session_report_id", columnList = "report_id")
        })
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "page_type", nullable = false, length = 40)
    private String pageType;

    @Column(name = "current_mode", length = 40)
    private String currentMode;

    @Column(name = "session_summary", columnDefinition = "TEXT")
    private String sessionSummary;

    @Column(name = "latest_user_message", columnDefinition = "TEXT")
    private String latestUserMessage;

    @Column(name = "latest_assistant_message", columnDefinition = "TEXT")
    private String latestAssistantMessage;

    @Column(name = "last_intent_decision_id")
    private Long lastIntentDecisionId;

    @Column(name = "active_form_draft_id")
    private Long activeFormDraftId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
