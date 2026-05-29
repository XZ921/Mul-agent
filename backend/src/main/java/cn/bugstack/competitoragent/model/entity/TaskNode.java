package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DAG 任务节点实体
 * <p>
 * 每个分析任务拆分为多个执行节点，按 executionOrder 顺序执行。
 * dependsOn 字段表达节点间依赖关系，required 字段标识是否为必须节点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_node", indexes = {
        @Index(name = "idx_node_task_id", columnList = "taskId"),
        @Index(name = "idx_node_status", columnList = "status")
})
@Schema(description = "DAG 任务节点")
public class TaskNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "节点 ID", example = "10")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "所属任务 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Column(nullable = false, length = 50)
    @Schema(description = "节点唯一标识名", example = "collect_sources", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nodeName;

    @Column(nullable = false, length = 100)
    @Schema(description = "节点显示名称", example = "采集公开信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Schema(description = "负责此节点的 Agent 类型", example = "COLLECTOR", requiredMode = Schema.RequiredMode.REQUIRED)
    private AgentType agentType;

    /**
     * 依赖节点列表，JSON 数组格式
     * 示例：["collect_sources"]
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "依赖节点名称列表 (JSON 数组)", example = "[\"collect_sources\"]")
    private String dependsOn;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Node config (JSON)")
    private String nodeConfig;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "Node notes or execution hint")
    private String nodeNotes;

    @Builder.Default
    @Schema(description = "Allow downstream execution even if this node failed", example = "false")
    private boolean allowFailedDependency = false;

    @Builder.Default
    @Schema(description = "是否为必须节点。false=可选节点，失败不影响整体流程", example = "true")
    private boolean required = true;

    @Builder.Default
    @Schema(description = "是否允许重试", example = "true")
    private boolean retryable = true;

    @Builder.Default
    @Schema(description = "最大重试次数", example = "3")
    private int maxRetries = 3;

    @Builder.Default
    @Schema(description = "Retry count", example = "0")
    private int retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    @Schema(description = "节点执行状态", example = "PENDING")
    private TaskNodeStatus status = TaskNodeStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点输入数据 (JSON)")
    private String inputData;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点输出数据 (JSON)")
    private String outputData;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "失败时的错误信息")
    private String errorMessage;

    @Schema(description = "执行顺序（从 0 开始）", example = "0")
    private int executionOrder;

    @Schema(description = "开始执行时间")
    private LocalDateTime startedAt;

    @Schema(description = "执行完成时间")
    private LocalDateTime completedAt;

    @Column(updatable = false)
    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
