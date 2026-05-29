package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 竞品分析报告实体
 * <p>
 * 存储最终生成的 Markdown 格式报告以及质检结果。
 * 每个任务对应唯一一份报告。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report", indexes = {
        @Index(name = "idx_report_task_id", columnList = "taskId", unique = true)
})
@Schema(description = "竞品分析报告")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "报告 ID", example = "1")
    private Long id;

    @Column(nullable = false, unique = true)
    @Schema(description = "所属任务 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Column(nullable = false, length = 300)
    @Schema(description = "报告标题", example = "AI 知识库产品竞品分析报告", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    /**
     * 报告正文 — Markdown 格式
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Schema(description = "报告正文 (Markdown)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "报告摘要")
    private String summary;

    // ==================== 质检相关字段 ====================

    @Schema(description = "质检评分 (0-100)", example = "85")
    private Integer qualityScore;

    @Builder.Default
    @Schema(description = "是否通过质检", example = "true")
    private boolean qualityPassed = false;

    /**
     * 质检问题列表，JSON 数组
     * [{"type":"MISSING_EVIDENCE","section":"功能对比","severity":"WARNING","suggestion":"..."}]
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "质检问题列表 (JSON)")
    private String qualityIssues;

    @Schema(description = "报告中引用的证据总数", example = "25")
    private Integer evidenceCount;

    @Column(updatable = false)
    @Schema(description = "报告生成时间")
    private LocalDateTime createdAt;

    @Schema(description = "最近更新时间")
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
