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
 * Agent 执行日志实体
 * <p>
 * 记录每个 Agent 每次调用的完整信息，包括输入、输出、prompt、耗时、Token 用量等。
 * 是系统可观测性的核心数据来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "agent_execution_log", indexes = {
        @Index(name = "idx_log_task_id", columnList = "taskId"),
        @Index(name = "idx_log_agent_type", columnList = "agentType"),
        @Index(name = "idx_log_trace_id", columnList = "traceId"),
        @Index(name = "idx_log_created_at", columnList = "createdAt")
})
@Schema(description = "Agent 执行日志")
public class AgentExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "日志 ID", example = "100")
    private Long id;

    @Column(nullable = false)
    @Schema(description = "所属任务 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @Column
    @Schema(description = "所属节点 ID", example = "10")
    private Long nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Schema(description = "Agent 类型", example = "COLLECTOR", requiredMode = Schema.RequiredMode.REQUIRED)
    private AgentType agentType;

    @Column(nullable = false, length = 100)
    @Schema(description = "Agent 实例名称", example = "采集Agent-1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String agentName;

    /**
     * Agent 运行的输入参数（JSON 字符串）
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Agent 输入 (JSON)")
    private String inputData;

    /**
     * Agent 运行的输出结果（JSON 字符串）
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Agent 输出 (JSON)")
    private String outputData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Schema(description = "执行状态", example = "SUCCESS")
    private TaskNodeStatus status;

    @Column(length = 100)
    @Schema(description = "使用的 LLM 模型名称", example = "claude-sonnet-4-6")
    private String modelName;

    /**
     * 实际发送给 LLM 的完整 prompt
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "使用的 Prompt 模板内容")
    private String promptUsed;

    @Schema(description = "执行耗时（毫秒）", example = "15200")
    private Long durationMs;

    /**
     * Token 用量统计，JSON 格式
     * 示例：{"input": 1500, "output": 800, "total": 2300}
     */
    @Column(columnDefinition = "TEXT")
    @Schema(description = "Token 用量 (JSON)", example = "{\"input\":1500,\"output\":800,\"total\":2300}")
    private String tokenUsage;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "执行失败时的错误信息")
    private String errorMessage;

    @Column(length = 50)
    @Schema(description = "追踪 ID，关联请求", example = "a1b2c3d4e5f6")
    private String traceId;

    @Schema(description = "推理过程摘要（LLM 思考过程）")
    @Column(columnDefinition = "TEXT")
    private String reasoningSummary;

    @Builder.Default
    @Schema(description = "是否需要人工介入", example = "false")
    private boolean needsHumanIntervention = false;

    @Column(updatable = false)
    @Schema(description = "日志记录时间")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
