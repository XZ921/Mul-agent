package cn.bugstack.competitoragent.model.entity;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 任务节点实体。
 * <p>
 * 每个分析任务会拆成多个节点，节点既承载执行语义，也承载恢复语义。
 * 因此 Phase 4 起实体除了基础执行字段，还会显式保留：
 * 1. 当前失败分类；
 * 2. 下一次重试时间；
 * 3. 最近一次事件 ID；
 * 4. 乐观锁版本号。
 * 这些字段共同构成“节点权威状态”的最小事实基础。
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
    @Schema(description = "节点唯一标识", example = "collect_sources", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nodeName;

    @Column(nullable = false, length = 100)
    @Schema(description = "节点显示名称", example = "采集公开信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Schema(description = "负责执行该节点的 Agent 类型", example = "COLLECTOR", requiredMode = Schema.RequiredMode.REQUIRED)
    private AgentType agentType;

    /**
     * 依赖节点列表，使用 JSON 数组存储。
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "依赖节点名称列表 (JSON 数组)", example = "[\"collect_sources\"]")
    private String dependsOn;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点配置 JSON")
    private String nodeConfig;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点补充说明")
    private String nodeNotes;

    @Builder.Default
    @Schema(description = "当前节点失败时是否允许下游继续推进", example = "false")
    private boolean allowFailedDependency = false;

    @Builder.Default
    @Schema(description = "是否属于必需节点", example = "true")
    private boolean required = true;

    @Builder.Default
    @Schema(description = "是否允许自动重试", example = "true")
    private boolean retryable = true;

    @Builder.Default
    @Schema(description = "最大自动重试次数", example = "3")
    private int maxRetries = 3;

    @Builder.Default
    @Schema(description = "当前累计重试次数", example = "0")
    private int retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    @Schema(description = "最近一次失败分类")
    private NodeFailureCategory failureCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    @Schema(description = "节点权威状态", example = "PENDING")
    private TaskNodeStatus status = TaskNodeStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    @Schema(description = "节点人工控制状态", example = "NONE")
    private TaskNodeControlState controlState = TaskNodeControlState.NONE;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点输入数据 JSON")
    private String inputData;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "节点输出数据 JSON")
    private String outputData;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "最近一次错误摘要")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "人工介入原因")
    private String interventionReason;

    @Schema(description = "执行顺序，从 0 开始", example = "0")
    private int executionOrder;

    @Schema(description = "所属计划版本 ID", example = "12")
    private Long planVersionId;

    @Column(length = 120)
    @Builder.Default
    @Schema(description = "分支键", example = "root")
    private String branchKey = "root";

    @Builder.Default
    @Schema(description = "是否为动态补图节点", example = "false")
    private boolean dynamicNode = false;

    @Column(length = 80)
    @Schema(description = "动态节点来源节点名", example = "quality_check")
    private String originNodeName;

    @Schema(description = "开始执行时间")
    private LocalDateTime startedAt;

    @Schema(description = "执行完成时间")
    private LocalDateTime completedAt;

    @Schema(description = "最近一次执行尝试时间")
    private LocalDateTime lastAttemptAt;

    @Schema(description = "下一次自动重试最早时间")
    private LocalDateTime nextRetryAt;

    @Column(length = 64)
    @Schema(description = "最近一次驱动状态变化的事件 ID")
    private String lastEventId;

    @Version
    @Schema(description = "节点状态版本号，用于幂等和乐观锁控制")
    private Long stateVersion;

    @Column(updatable = false)
    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;

    /**
     * 解析依赖节点列表。
     * 这里保持实体侧的轻量能力，便于影响范围计算和恢复判断直接复用。
     */
    public List<String> parseDependencyNames() {
        if (dependsOn == null || dependsOn.isBlank() || "[]".equals(dependsOn.trim())) {
            return List.of();
        }
        String normalized = dependsOn.trim();
        if (normalized.startsWith("[")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("]")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> dependencyNames = new ArrayList<>();
        for (String rawItem : normalized.split(",")) {
            String dependencyName = rawItem == null ? "" : rawItem.trim().replace("\"", "");
            if (!dependencyName.isBlank()) {
                dependencyNames.add(dependencyName);
            }
        }
        return dependencyNames;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
