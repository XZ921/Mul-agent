package cn.bugstack.competitoragent.model.entity;

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

/**
 * 工作流死信记录。
 * <p>
 * 该表用于收口“已经达到人工介入或死信条件的失败节点”，
 * 保留恢复、审计和人工补偿所需的最小信息集。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "workflow_dead_letter_record", indexes = {
        @Index(name = "idx_workflow_dlq_task_id", columnList = "taskId"),
        @Index(name = "idx_workflow_dlq_node_id", columnList = "nodeId"),
        @Index(name = "idx_workflow_dlq_event_id", columnList = "sourceEventId")
})
@Schema(description = "工作流死信记录")
public class WorkflowDeadLetterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long nodeId;

    @Column(length = 80)
    private String nodeName;

    @Column(length = 64)
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NodeFailureCategory failureCategory;

    @Column(columnDefinition = "TEXT")
    private String latestErrorSummary;

    @Column(columnDefinition = "TEXT")
    private String retryHistory;

    @Column(columnDefinition = "TEXT")
    private String originalPayload;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
