package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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

    @Schema(description = "Structured node configuration summary")
    private TaskNodeConfigSummary configSummaryData;

    @Schema(description = "Structured collector node insight")
    private CollectorNodeInsightResponse collectorInsight;

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

    @Schema(description = "Failure category when node is not successful")
    private NodeFailureCategory failureCategory;

    @Schema(description = "Node status", example = "SUCCESS")
    private TaskNodeStatus status;

    @Schema(description = "Node control state", example = "NONE")
    private TaskNodeControlState controlState;

    @Schema(description = "Error message")
    private String errorMessage;

    @Schema(description = "Intervention reason")
    private String interventionReason;

    @Schema(description = "Execution order", example = "0")
    private int executionOrder;

    @Schema(description = "所属计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "所属计划版本号", example = "3")
    private Integer planVersion;

    @Schema(description = "节点分支键", example = "root/review-2")
    private String branchKey;

    @Schema(description = "是否为动态节点")
    private Boolean dynamicNode;

    @Schema(description = "动态节点来源节点名")
    private String originNodeName;

    @Schema(description = "Input summary")
    private String inputSummary;

    @Schema(description = "Output summary")
    private String outputSummary;

    @Schema(description = "AI 治理摘要")
    private String aiGovernanceSummary;

    @Schema(description = "User readable status summary")
    private String statusSummary;

    @Schema(description = "Raw input JSON")
    private String inputData;

    @Schema(description = "Raw output JSON")
    private String outputData;

    @Schema(description = "Started at")
    private LocalDateTime startedAt;

    @Schema(description = "Completed at")
    private LocalDateTime completedAt;

    @Schema(description = "Last attempt time")
    private LocalDateTime lastAttemptAt;

    @Schema(description = "Next retry time")
    private LocalDateTime nextRetryAt;

    @Schema(description = "是否允许从该节点重跑")
    private Boolean canRerun;

    @Schema(description = "是否允许修改节点配置后继续执行")
    private Boolean canUpdateConfigAndRerun;

    @Schema(description = "从该节点重跑会影响的节点总数（含当前节点）")
    private Integer affectedNodeCount;

    @Schema(description = "从该节点重跑会影响的节点名称列表（含当前节点）")
    private List<String> affectedNodeNames;

    @Schema(description = "该节点是否具备可复用检查点")
    private Boolean canReuseCheckpoint;

    @Schema(description = "是否允许暂停该节点")
    private Boolean canPause;

    @Schema(description = "是否允许恢复该节点")
    private Boolean canResumeNode;

    @Schema(description = "是否允许手动跳过该节点")
    private Boolean canSkip;

    @Schema(description = "是否允许终止该节点")
    private Boolean canTerminate;

    @Schema(description = "节点级人工干预规则摘要")
    private String interventionSummary;

    @Schema(description = "适合直接从该节点重跑的业务说明")
    private String rerunActionSummary;

    @Schema(description = "适合修改配置后重跑的业务说明")
    private String configRerunActionSummary;

    @Schema(description = "该节点人工干预会影响的业务范围说明")
    private String impactSummary;

    @Schema(description = "该节点检查点复用说明")
    private String checkpointSummary;

    @Schema(description = "该节点追踪与回放入口说明")
    private String replayEntrySummary;

    @Schema(description = "节点在任务事件流中的标识键")
    private String eventKey;
}
