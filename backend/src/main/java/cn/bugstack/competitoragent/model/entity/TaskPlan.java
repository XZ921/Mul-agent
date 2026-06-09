package cn.bugstack.competitoragent.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
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
import java.util.List;

/**
 * 任务计划版本实体。
 * <p>
 * Phase 4.3 起，任务图不再只有一份静态 DAG，
 * 而是允许在同一任务下存在“初始计划 -> 动态补图 -> 回流分支”的多个版本快照。
 * 该实体只负责沉淀“某一版任务图是什么”，不直接承载节点运行态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_plan", indexes = {
        @Index(name = "idx_task_plan_task_id", columnList = "taskId"),
        @Index(name = "idx_task_plan_parent_plan_id", columnList = "parentPlanId")
})
@Schema(description = "任务计划版本")
public class TaskPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "计划版本主键", example = "1")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "所属任务 ID", example = "1001")
    private Long taskId;

    @Column(nullable = false)
    @Schema(description = "任务内版本号", example = "2")
    private Integer planVersion;

    @Schema(description = "父计划版本 ID", example = "1")
    private Long parentPlanId;

    @Column(nullable = false, length = 120)
    @Schema(description = "分支键", example = "root/review-2")
    private String branchKey;

    @Column(length = 80)
    @Schema(description = "触发该计划版本的节点", example = "quality_check")
    private String triggerNodeName;

    @Column(nullable = false, length = 40)
    @Schema(description = "计划类型", example = "INITIAL")
    private String planType;

    @Builder.Default
    @Column(nullable = false)
    @Schema(description = "是否为当前激活版本", example = "true")
    private boolean active = true;

    /**
     * 使用 JSON 快照保存该版本的任务图，
     * 这样服务中断后仍可回看“这一版计划具体长什么样”，避免只剩节点表无法还原版本结构。
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    @Schema(description = "计划图快照 JSON")
    private String planSnapshot;

    @Column(updatable = false)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 恢复窗口默认以当前计划版本触发节点为边界。
     * 如果当前计划还没有触发节点，则退化为本次恢复聚焦节点，避免边界缺失。
     */
    public List<String> resolveRecoveryBoundaryNodeNames(String fallbackNodeName) {
        if (this.triggerNodeName != null && !this.triggerNodeName.isBlank()) {
            return List.of(this.triggerNodeName);
        }
        if (fallbackNodeName != null && !fallbackNodeName.isBlank()) {
            return List.of(fallbackNodeName);
        }
        return List.of();
    }
}
