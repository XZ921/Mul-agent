package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分析模板实体
 * <p>
 * 预定义的分析维度模板，用户创建任务时可选。
 * 第一版预置 3 个模板：功能对比分析、定价策略分析、SWOT 分析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "analysis_schema", indexes = {
        @Index(name = "idx_schema_name", columnList = "name", unique = true)
})
@Schema(description = "分析模板")
public class AnalysisSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "模板 ID", example = "1")
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    @Schema(description = "模板名称", example = "功能对比分析", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Column(length = 500)
    @Schema(description = "模板描述", example = "对竞品进行全维度功能对比分析，适合产品经理选型参考")
    private String description;

    /**
     * 分析维度定义，JSON 结构
     * 示例：
     * [
     *   {"name": "产品功能", "description": "核心功能完整度和差异化", "weight": 0.3},
     *   {"name": "目标用户", "description": "服务的用户群体和场景", "weight": 0.15}
     * ]
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "分析维度定义 (JSON)", example = "[{\"name\":\"产品功能\",\"weight\":0.3}]")
    private String dimensions;

    @Builder.Default
    @Schema(description = "是否为系统预置模板", example = "true")
    private boolean isPreset = false;

    @Column(updatable = false)
    @Schema(description = "创建时间")
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
