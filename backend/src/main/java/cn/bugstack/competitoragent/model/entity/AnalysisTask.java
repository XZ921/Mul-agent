package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 竞品分析任务实体
 * <p>
 * 存储用户创建的每一次分析任务的核心信息，包括目标产品、竞品列表、分析维度等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "analysis_task", indexes = {
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_created_at", columnList = "createdAt")
})
@Schema(description = "竞品分析任务")
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "任务 ID", example = "1")
    private Long id;

    @Column(nullable = false, length = 200)
    @Schema(description = "分析主题/任务名称", example = "AI 知识库产品竞品分析", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskName;

    @Column(nullable = false, length = 200)
    @Schema(description = "本方产品或关注对象", example = "企业级 RAG 知识库平台", requiredMode = Schema.RequiredMode.REQUIRED)
    private String subjectProduct;

    /**
     * 竞品名称列表，JSON 数组格式存储
     * 示例：["Notion AI","Glean","Dify"]
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Schema(description = "竞品名称列表 (JSON 数组)", example = "[\"Notion AI\",\"Glean\",\"Dify\",\"FastGPT\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private String competitorNames;

    /**
     * 竞品官网 URL 列表，JSON 数组格式存储
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "竞品官网 URL 列表 (JSON 数组)", example = "[\"https://www.notion.so\",\"https://www.glean.com\"]")
    private String competitorUrls;

    /**
     * 分析维度列表，JSON 数组格式存储
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "分析维度列表 (JSON 数组)", example = "[\"产品功能\",\"目标用户\",\"价格策略\",\"技术能力\",\"市场定位\"]")
    private String analysisDimensions;

    /**
     * 信息源范围，JSON 数组格式存储
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "信息源范围", example = "[\"官网\",\"产品文档\",\"定价页\",\"公开测评\"]")
    private String sourceScope;

    @Column(length = 20)
    @Schema(description = "报告语言", example = "中文")
    @Builder.Default
    private String reportLanguage = "中文";

    @Column(length = 50)
    @Schema(description = "报告模板类型", example = "标准版")
    @Builder.Default
    private String reportTemplate = "标准版";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Schema(description = "任务状态", example = "PENDING")
    @Builder.Default
    private AnalysisTaskStatus status = AnalysisTaskStatus.PENDING;

    /**
     * 关联的分析模板 ID（可选）
     */
    @Column(name = "schema_id")
    @Schema(description = "分析模板 ID", example = "1")
    private Long schemaId;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "任务失败时的错误信息")
    private String errorMessage;

    /**
     * 标记当前任务是否仍然持有任务并发配额占位。
     * 显式落库后，终态释放和后续重新执行时的重新 reserve
     * 才能形成可校验、可恢复的闭环。
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean taskQuotaReserved = false;

    @Schema(description = "当前激活计划版本 ID", example = "2")
    private Long currentPlanVersionId;

    @Schema(description = "当前激活计划版本号", example = "2")
    private Integer currentPlanVersion;

    @Column(updatable = false)
    @Schema(description = "任务创建时间", example = "2026-05-26 10:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "最近更新时间", example = "2026-05-26 10:35:00")
    private LocalDateTime updatedAt;

    @Schema(description = "任务开始执行时间", example = "2026-05-26 10:30:15")
    private LocalDateTime startedAt;

    @Schema(description = "任务完成时间", example = "2026-05-26 10:35:00")
    private LocalDateTime completedAt;

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
