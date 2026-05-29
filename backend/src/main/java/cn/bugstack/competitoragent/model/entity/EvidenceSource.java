package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 证据来源实体
 * <p>
 * 存储采集到的原始信息源，每条证据关联一个具体来源 URL。
 * 报告中的关键结论通过 evidenceId 引用此实体，实现"结论可溯源"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "evidence_source", indexes = {
        @Index(name = "idx_evidence_task_id", columnList = "taskId"),
        @Index(name = "idx_evidence_competitor", columnList = "competitorName"),
        @Index(name = "idx_evidence_evidence_id", columnList = "evidenceId")
})
@Schema(description = "证据来源")
public class EvidenceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "主键 ID", example = "1")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "所属任务 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Column(nullable = false, length = 100)
    @Schema(description = "所属竞品名称", example = "Notion AI", requiredMode = Schema.RequiredMode.REQUIRED)
    private String competitorName;

    /**
     * 任务内唯一的证据编号，如 E001, E002
     */
    @Column(nullable = false, length = 100)
    @Schema(description = "证据编号（任务内唯一）", example = "E001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String evidenceId;

    @Column(nullable = false, length = 500)
    @Schema(description = "来源页面标题", example = "Notion AI — Your connected workspace", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Column(nullable = false, length = 2048)
    @Schema(description = "来源 URL", example = "https://www.notion.so/product/ai", requiredMode = Schema.RequiredMode.REQUIRED)
    private String url;

    /**
     * 用于溯源展示的原文引用片段
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "原文引用片段", example = "Notion AI brings the power of artificial intelligence directly into your workspace...")
    private String contentSnippet;

    /**
     * 采集到的完整页面正文（清洗后）
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "完整采集内容（清洗后的正文）")
    private String fullContent;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "页面元数据 (JSON)：发布时间、作者等")
    private String pageMetadata;

    @Schema(description = "采集时间", example = "2026-05-26 10:31:00")
    private LocalDateTime collectedAt;

    @Column(updatable = false)
    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.collectedAt == null) {
            this.collectedAt = LocalDateTime.now();
        }
    }
}
