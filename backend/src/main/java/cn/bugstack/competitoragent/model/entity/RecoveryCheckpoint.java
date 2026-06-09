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

/**
 * 恢复点实体。
 * <p>
 * 该表用于把“某个任务在某个计划版本下有哪些可恢复锚点”正式落库，
 * 避免恢复入口继续依赖事件表、节点表和缓存快照的临时拼装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "recovery_checkpoint", indexes = {
        @Index(name = "idx_recovery_checkpoint_task_id", columnList = "taskId"),
        @Index(name = "idx_recovery_checkpoint_plan_version_id", columnList = "planVersionId"),
        @Index(name = "idx_recovery_checkpoint_checkpoint_key", columnList = "checkpointKey")
})
@Schema(description = "任务恢复点")
public class RecoveryCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "恢复点主键", example = "1")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "所属任务 ID", example = "1001")
    private Long taskId;

    @Column(nullable = false)
    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Column(nullable = false, length = 120)
    @Schema(description = "恢复点唯一业务键", example = "task-1001-quality-check-1")
    private String checkpointKey;

    @Column(nullable = false, length = 40)
    @Schema(description = "恢复点类型", example = "NODE_SUCCESS")
    private String checkpointType;

    @Column(length = 80)
    @Schema(description = "关联节点名称", example = "quality_check")
    private String nodeName;

    @Column(length = 120)
    @Schema(description = "计划分支标识", example = "root/review-3")
    private String branchKey;

    @Column(nullable = false, length = 500)
    @Schema(description = "用户可读恢复点摘要")
    private String summary;

    /**
     * 恢复点快照统一保留原始载荷，
     * 后续 5.6.c 可以在不破坏表结构的前提下继续补充恢复窗口与释放规则。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "恢复点快照载荷")
    private String payloadSnapshot;

    /**
     * 即使恢复点来自内部工作流状态，也统一保留 sourceUrls 字段，
     * 方便后续把外部证据、审计摘要和人工介入说明挂接到同一恢复对象上。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "证据 / 追溯来源地址 JSON")
    private String sourceUrls;

    @Column(updatable = false)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
