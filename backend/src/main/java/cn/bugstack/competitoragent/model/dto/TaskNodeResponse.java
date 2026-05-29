package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "DAG node info")
public class TaskNodeResponse {

    @Schema(description = "Node ID", example = "10")
    private Long id;

    @Schema(description = "Node name", example = "collect_sources_01")
    private String nodeName;

    @Schema(description = "Display name", example = "采集公开信息 - Notion AI")
    private String displayName;

    @Schema(description = "Node config JSON")
    private String nodeConfig;

    @Schema(description = "Node configuration summary")
    private String configSummary;

    @Schema(description = "Node note")
    private String nodeNotes;

    @Schema(description = "Allow downstream execution when failed")
    private boolean allowFailedDependency;

    @Schema(description = "Agent type", example = "COLLECTOR")
    private AgentType agentType;

    @Schema(description = "Depends on nodes")
    private String dependsOn;

    @Schema(description = "Required node")
    private boolean required;

    @Schema(description = "Retryable node")
    private boolean retryable;

    @Schema(description = "Max retries", example = "3")
    private int maxRetries;

    @Schema(description = "Current retry count", example = "0")
    private int retryCount;

    @Schema(description = "Node status", example = "SUCCESS")
    private TaskNodeStatus status;

    @Schema(description = "Error message")
    private String errorMessage;

    @Schema(description = "Execution order", example = "0")
    private int executionOrder;

    @Schema(description = "Input summary")
    private String inputSummary;

    @Schema(description = "Output summary")
    private String outputSummary;

    @Schema(description = "Raw input JSON")
    private String inputData;

    @Schema(description = "Raw output JSON")
    private String outputData;

    @Schema(description = "Started at")
    private LocalDateTime startedAt;

    @Schema(description = "Completed at")
    private LocalDateTime completedAt;
}
