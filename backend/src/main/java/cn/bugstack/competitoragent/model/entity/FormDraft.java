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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务创建草稿实体。
 * 当前阶段先把自然语言生成的结构化草稿以 JSON 形式落盘，便于后续多轮对话逐步修订。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "form_draft",
        indexes = {
                @Index(name = "idx_form_draft_session_id", columnList = "conversation_session_id"),
                @Index(name = "idx_form_draft_task_id", columnList = "task_id")
        })
public class FormDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_session_id", nullable = false)
    private Long conversationSessionId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "draft_payload", columnDefinition = "TEXT", nullable = false)
    private String draftPayload;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "preview_summary", columnDefinition = "TEXT")
    private String previewSummary;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    private List<String> sourceUrls;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
    }
}
