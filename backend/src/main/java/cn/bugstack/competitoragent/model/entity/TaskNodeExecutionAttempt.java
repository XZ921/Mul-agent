package cn.bugstack.competitoragent.model.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 节点执行尝试记录。
 * <p>
 * 该对象用于沉淀每一次真实执行尝试的结果，
 * 让 DLQ、恢复动作和运行审计都能追到“第几次尝试发生了什么”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_node_execution_attempt", indexes = {
        @Index(name = "idx_node_attempt_task_id", columnList = "taskId"),
        @Index(name = "idx_node_attempt_node_id", columnList = "nodeId"),
        @Index(name = "idx_node_attempt_idempotency_key", columnList = "idempotencyKey", unique = true)
})
@Schema(description = "节点执行尝试记录")
public class TaskNodeExecutionAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long nodeId;

    @Column(nullable = false, length = 80)
    private String nodeName;

    @Column(nullable = false)
    private int attemptNo;

    @Column(nullable = false, length = 160, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskNodeStatus resultStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private NodeFailureCategory failureCategory;

    @Column(columnDefinition = "TEXT")
    private String errorSummary;

    @Column(length = 64)
    private String sourceEventId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 恢复判断时只消费属于当前节点的尝试记录，
     * 避免其他节点的失败历史误入当前恢复建议。
     */
    public boolean belongsToNode(Long taskId, Long nodeId) {
        return Objects.equals(this.taskId, taskId) && Objects.equals(this.nodeId, nodeId);
    }
}
