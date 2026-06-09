package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Objects;

/**
 * 工作流事件出站箱 / 消费留痕表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_workflow_event", indexes = {
        @Index(name = "idx_task_workflow_event_task_id", columnList = "taskId"),
        @Index(name = "idx_task_workflow_event_event_id", columnList = "eventId", unique = true),
        @Index(name = "idx_task_workflow_event_status", columnList = "deliveryStatus")
})
@Schema(description = "工作流事件出站箱记录")
public class TaskWorkflowEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String eventId;

    @Column(nullable = false)
    private Long taskId;

    @Column(length = 80)
    private String nodeName;

    private Long planVersionId;

    @Column(length = 120)
    private String branchKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkflowEventType eventType;

    @Column(nullable = false, length = 32)
    private String deliveryStatus;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(nullable = false, length = 100)
    private String tag;

    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * 即使当前事件是内部编排事件，也强制保留 sourceUrls 字段，
     * 为后续 Task RAG / EvidenceSource 的可追溯链路预留统一落点。
     */
    @Column(columnDefinition = "TEXT")
    private String sourceUrls;

    @Builder.Default
    private int retryCount = 0;

    @Builder.Default
    private int maxRetryCount = 6;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime publishedAt;

    private LocalDateTime consumedAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
        if (this.deliveryStatus == null || this.deliveryStatus.isBlank()) {
            this.deliveryStatus = STATUS_PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 只有未进入死信的事件才允许进入正式恢复窗口。
     * 这样回放与恢复建议不会把已失效消息重新当成有效依据。
     */
    public boolean isReplayableInRecoveryWindow() {
        return !Objects.equals(STATUS_DEAD_LETTER, this.deliveryStatus);
    }
}
