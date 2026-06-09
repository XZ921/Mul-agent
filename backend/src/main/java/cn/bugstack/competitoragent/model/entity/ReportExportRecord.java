package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.converter.StringListJsonConverter;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * 正式导出记录实体。
 * <p>
 * 该对象用于把“导出动作”从一次性下载行为提升为可追溯的正式交付记录，
 * 先显式承载任务维度、导出版本号和 sourceUrls，后续子任务再继续补齐渲染结果与审计摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report_export_record",
        indexes = {
                @Index(name = "idx_report_export_task_id", columnList = "task_id"),
                @Index(name = "idx_report_export_created_at", columnList = "created_at"),
                @Index(name = "uk_report_export_task_version", columnList = "task_id, export_version", unique = true)
        })
@Schema(description = "正式导出记录")
public class ReportExportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "导出记录 ID", example = "1")
    private Long id;

    @Column(name = "task_id", nullable = false)
    @Schema(description = "所属任务 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Column(name = "export_version", nullable = false)
    @Schema(description = "同一任务下的正式导出版本号", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer exportVersion;

    @Column(name = "export_format", nullable = false, length = 30)
    @Schema(description = "导出格式", example = "MARKDOWN")
    private String exportFormat;

    @Column(name = "export_status", nullable = false, length = 30)
    @Schema(description = "导出状态", example = "REGISTERED")
    private String exportStatus;

    @Column(name = "export_summary", columnDefinition = "TEXT")
    @Schema(description = "导出摘要")
    private String exportSummary;

    /**
     * 强制保留 sourceUrls，确保后续交付文件和交付摘要都能继续回查来源。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_urls", columnDefinition = "TEXT")
    @Schema(description = "导出记录关联的来源链接")
    private List<String> sourceUrls;

    @Column(name = "created_at", updatable = false, nullable = false)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近更新时间")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        applyDefaults();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        applyDefaults();
    }

    /**
     * 在实体边界补齐当前阶段允许的最小默认值，
     * 避免后续骨架 API 因为空字段写入而失去“正式记录”的基本语义。
     */
    private void applyDefaults() {
        if (this.exportVersion == null || this.exportVersion < 1) {
            this.exportVersion = 1;
        }
        if (this.exportFormat == null || this.exportFormat.isBlank()) {
            this.exportFormat = "MARKDOWN";
        }
        if (this.exportStatus == null || this.exportStatus.isBlank()) {
            this.exportStatus = "REGISTERED";
        }
        if (this.exportSummary == null) {
            this.exportSummary = "";
        }
        if (this.sourceUrls == null) {
            this.sourceUrls = new ArrayList<>();
        }
    }
}
