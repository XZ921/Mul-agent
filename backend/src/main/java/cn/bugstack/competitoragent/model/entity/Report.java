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

    // ==================== Writer 写作证据快照字段 ====================

    /**
     * Writer 阶段识别出的整体证据状态。
     * 该字段只持久化 Writer 已经产出的事实，不承载补采、重写或人工介入决策。
     */
    @Column(length = 40)
    @Schema(description = "Writer evidence state: FULL_SOURCE / PARTIAL_SOURCE / MISSING_SOURCE")
    private String writerEvidenceState;

    /**
     * Writer 阶段识别出的引用缺口等级。
     * 该等级来自 WriterCitationGapInspector，用于报告查询和导出层解释当前可交付风险。
     */
    @Column(length = 40)
    @Schema(description = "Writer citation gap severity: NONE / HIGH / ERROR")
    private String citationGapSeverity;

    /**
     * Writer 判定缺少引用支撑的章节键，JSON 数组。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Writer missing citation sections JSON array")
    private String missingCitationSections;

    /**
     * Writer 章节引用缺口明细，JSON 数组。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Writer section citation gaps JSON array")
    private String sectionCitationGaps;

    /**
     * Writer 证据快照问题标记，JSON 数组。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Writer issue flags JSON array")
    private String writerIssueFlags;

    /**
     * Writer 写作阶段可回指来源，JSON 数组。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Writer source URLs JSON array")
    private String writerSourceUrls;

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
