package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.CollectorNodeInsightResponse;
import cn.bugstack.competitoragent.model.dto.CollectorSelectedTargetSummary;
import cn.bugstack.competitoragent.model.dto.TaskNodeConfigSummary;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 任务与节点只读视图组装器。
 * <p>
 * Phase 1 先把查询路径里的响应拼装职责集中到这里，
 * 后续查询服务和命令服务都只依赖这一层，避免展示逻辑继续散落在门面服务中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskNodeViewAssembler {

    private final AiCallAuditRecordRepository aiCallAuditRecordRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final TaskRecoveryService taskRecoveryService;
    private final ObjectMapper objectMapper;

    /**
     * 任务详情视图需要把任务主表状态、节点执行状态和运行时快照统一折叠成一个只读响应。
     * 这里集中维护状态解释规则，确保列表、详情和节点页看到的是同一套任务语义。
     */
    public TaskResponse toTaskResponse(AnalysisTask task, List<TaskNode> nodes) {
        NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                recoveryPolicy().resolveTaskExecution(task, nodes);
        AnalysisTaskStatus resolvedStatus = resolution.getStatus();
        Optional<TaskProgressSnapshot> snapshotOptional = taskRecoveryService.getTaskSnapshotOrRebuild(task.getId());
        TaskProgressSnapshot snapshot = snapshotOptional.orElse(null);

        return TaskResponse.builder()
                .id(task.getId())
                .taskName(task.getTaskName())
                .subjectProduct(task.getSubjectProduct())
                .competitorNames(task.getCompetitorNames())
                .competitorUrls(task.getCompetitorUrls())
                .analysisDimensions(task.getAnalysisDimensions())
                .sourceScope(task.getSourceScope())
                .status(resolvedStatus)
                .errorMessage(resolution.getErrorMessage())
                .statusSummary(snapshot == null ? buildTaskStatusSummary(nodes) : snapshot.getStatusSummary())
                .currentPlanVersionId(task.getCurrentPlanVersionId())
                .currentPlanVersion(task.getCurrentPlanVersion())
                .totalNodes(snapshot == null ? resolution.getTotalNodes() : snapshot.getTotalNodes())
                .completedNodes(snapshot == null ? resolution.getCompletedNodes() : snapshot.getCompletedNodes())
                .waitingRetryNodeCount(snapshot == null ? countNodesByStatus(nodes, TaskNodeStatus.WAITING_RETRY) : snapshot.getWaitingRetryNodeCount())
                .waitingInterventionNodeCount(snapshot == null ? countWaitingInterventionNodes(nodes) : snapshot.getWaitingInterventionNodeCount())
                .compensatedNodeCount(snapshot == null ? countNodesByStatus(nodes, TaskNodeStatus.COMPENSATED) : snapshot.getCompensatedNodeCount())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(resolution.resolveCompletedAt(task.getCompletedAt()))
                .canExecute(canExecuteTask(resolvedStatus))
                .canResume(canResumeTask(resolvedStatus))
                .canRetry(canRetryTask(resolvedStatus))
                .canStop(canStopTask(resolvedStatus))
                .canViewReport(resolvedStatus == AnalysisTaskStatus.SUCCESS)
                .interventionSummary(buildTaskInterventionSummary(resolvedStatus))
                .resumeAdvice(buildTaskResumeAdvice(resolvedStatus))
                .retryAdvice(buildTaskRetryAdvice(resolvedStatus))
                .replayEntrySummary(buildTaskReplayEntrySummary(resolvedStatus))
                .currentStage(snapshot == null ? buildDefaultCurrentStage(nodes, resolvedStatus) : snapshot.getCurrentStage())
                .activeNodeNames(snapshot == null ? buildActiveNodeNames(nodes) : snapshot.getActiveNodeNames())
                .snapshotUpdatedAt(snapshot == null ? null : snapshot.getUpdatedAt())
                .eventStreamPath(buildEventStreamPath(task.getId()))
                .build();
    }

    /**
     * 节点详情视图除了静态配置，还要补齐：
     * 1. 当前任务状态下允许的干预动作；
     * 2. 该节点重跑会波及的下游范围；
     * 3. 面向用户可读的摘要说明。
     */
    public TaskNodeResponse toNodeResponse(AnalysisTask task, TaskNode node, List<TaskNode> allNodes) {
        AnalysisTaskStatus taskStatus = recoveryPolicy().resolveTaskExecution(task, allNodes).getStatus();
        List<TaskNode> affectedNodes = collectAffectedNodes(allNodes, node.getNodeName());
        List<String> affectedNodeNames = affectedNodes.stream()
                .map(TaskNode::getNodeName)
                .toList();
        Map<Long, Integer> planVersionMap = buildPlanVersionMap(task == null ? null : task.getId());
        TaskNodeConfigSummary configSummaryData = buildNodeConfigSummaryData(node);
        boolean canRerun = canRerunNode(taskStatus);
        boolean canUpdateConfigAndRerun = canUpdateConfigAndRerun(node, taskStatus);
        boolean canReuseCheckpoint = hasReusableCheckpoint(node);
        boolean canPause = canPauseNode(node);
        boolean canResumeNode = canResumeNode(node);
        boolean canSkip = canSkipNode(node);
        boolean canTerminate = canTerminateNode(node);
        CollectorNodeInsightResponse collectorInsight = buildCollectorNodeInsight(node);

        return TaskNodeResponse.builder()
                .contractType("TASK_NODE_RUNTIME_V1")
                .id(node.getId())
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(configSummaryData == null ? null : configSummaryData.getSummaryText())
                .configSummaryData(configSummaryData)
                .collectorInsight(collectorInsight)
                .nodeNotes(node.getNodeNotes())
                .agentType(node.getAgentType())
                .dependsOn(node.getDependsOn())
                .required(node.isRequired())
                .retryable(node.isRetryable())
                .maxRetries(node.getMaxRetries())
                .retryCount(node.getRetryCount())
                .failureCategory(node.getFailureCategory())
                .status(node.getStatus())
                .controlState(node.getControlState())
                .errorMessage(node.getErrorMessage())
                .interventionReason(node.getInterventionReason())
                .executionOrder(node.getExecutionOrder())
                .planVersionId(node.getPlanVersionId())
                .planVersion(resolvePlanVersion(planVersionMap, node.getPlanVersionId()))
                .branchKey(node.getBranchKey())
                .dynamicNode(node.isDynamicNode())
                .originNodeName(node.getOriginNodeName())
                .inputSummary(truncate(node.getInputData(), 240))
                .outputSummary(buildOutputSummary(node))
                .aiGovernanceSummary(buildAiGovernanceSummary(node))
                .statusSummary(buildNodeStatusSummary(node))
                .inputData(node.getInputData())
                .outputData(node.getOutputData())
                .allowFailedDependency(node.isAllowFailedDependency())
                .startedAt(node.getStartedAt())
                .completedAt(node.getCompletedAt())
                .lastAttemptAt(node.getLastAttemptAt())
                .nextRetryAt(node.getNextRetryAt())
                .canRerun(canRerun)
                .canUpdateConfigAndRerun(canUpdateConfigAndRerun)
                .affectedNodeCount(affectedNodeNames.size())
                .affectedNodeNames(affectedNodeNames)
                .canReuseCheckpoint(canReuseCheckpoint)
                .canPause(canPause)
                .canResumeNode(canResumeNode)
                .canSkip(canSkip)
                .canTerminate(canTerminate)
                .eventKey(node.getNodeName())
                .interventionSummary(buildNodeInterventionSummary(
                        node,
                        taskStatus,
                        affectedNodeNames,
                        canReuseCheckpoint,
                        canPause,
                        canResumeNode,
                        canSkip,
                        canTerminate))
                .rerunActionSummary(buildNodeRerunActionSummary(node, canRerun))
                .configRerunActionSummary(buildNodeConfigRerunActionSummary(node, canUpdateConfigAndRerun))
                .impactSummary(buildNodeImpactSummary(node, affectedNodeNames))
                .checkpointSummary(buildNodeCheckpointSummary(node, canReuseCheckpoint))
                .replayEntrySummary(buildNodeReplayEntrySummary(node))
                .build();
    }

    /**
     * 预览阶段只需要返回“计划中的节点长什么样”，
     * 不涉及真实运行态、重跑能力和快照恢复，因此这里统一输出静态预览视图。
     */
    public TaskNodeResponse toPreviewNodeResponse(WorkflowPlan.WorkflowPlanNode node) {
        TaskNodeConfigSummary configSummaryData = buildPreviewNodeConfigSummaryData(node);
        CollectorNodeInsightResponse collectorInsight = buildPreviewCollectorNodeInsight(node);
        return TaskNodeResponse.builder()
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName())
                .nodeConfig(node.getNodeConfig())
                .configSummary(configSummaryData == null ? null : configSummaryData.getSummaryText())
                .configSummaryData(configSummaryData)
                .collectorInsight(collectorInsight)
                .agentType(AgentType.valueOf(node.getAgentType()))
                .dependsOn(toJson(node.getDependsOn()))
                .required(node.isRequired())
                .retryable(node.isRetryable())
                .maxRetries(node.getMaxRetries())
                .retryCount(0)
                .executionOrder(node.getExecutionOrder())
                .planVersionId(null)
                .planVersion(null)
                .branchKey(node.getBranchKey())
                .dynamicNode(node.isDynamicNode())
                .originNodeName(node.getOriginNodeName())
                .inputSummary(node.getNodeConfig())
                .aiGovernanceSummary(null)
                .status(TaskNodeStatus.PENDING)
                .nodeNotes(node.getNotes())
                .allowFailedDependency(node.isAllowFailedDependency())
                .canRerun(false)
                .canUpdateConfigAndRerun(false)
                .affectedNodeCount(0)
                .affectedNodeNames(List.of())
                .canReuseCheckpoint(false)
                .canPause(false)
                .canResumeNode(false)
                .canSkip(false)
                .canTerminate(false)
                .eventKey(node.getNodeName())
                .interventionSummary("预览阶段仅展示规划结果。")
                .build();
    }

    private String buildDefaultCurrentStage(List<TaskNode> nodes, AnalysisTaskStatus status) {
        return TaskProgressSnapshot.fromTask(
                AnalysisTask.builder().id(-1L).build(),
                status,
                null,
                nodes).getCurrentStage();
    }

    private List<String> buildActiveNodeNames(List<TaskNode> nodes) {
        List<String> activeNodeNames = new ArrayList<>();
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.RUNNING
                    || node.getStatus() == TaskNodeStatus.PAUSED
                    || node.getStatus() == TaskNodeStatus.WAITING_RETRY
                    || node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                    || node.getStatus() == TaskNodeStatus.READY
                    || node.getStatus() == TaskNodeStatus.DISPATCHED) {
                activeNodeNames.add(node.getNodeName());
            }
        }
        return activeNodeNames;
    }

    private Integer countNodesByStatus(List<TaskNode> nodes, TaskNodeStatus targetStatus) {
        if (nodes == null || nodes.isEmpty() || targetStatus == null) {
            return 0;
        }
        int count = 0;
        for (TaskNode node : nodes) {
            if (node.getStatus() == targetStatus) {
                count++;
            }
        }
        return count;
    }

    private Integer countWaitingInterventionNodes(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION || node.getStatus() == TaskNodeStatus.PAUSED) {
                count++;
            }
        }
        return count;
    }

    private String buildTaskStatusSummary(List<TaskNode> nodes) {
        int waitingInterventionCount = countWaitingInterventionNodes(nodes);
        if (waitingInterventionCount > 0) {
            return "存在等待人工处理的节点";
        }
        int waitingRetryCount = countNodesByStatus(nodes, TaskNodeStatus.WAITING_RETRY);
        if (waitingRetryCount > 0) {
            return "存在等待系统自动重试的节点";
        }
        int compensatedCount = countNodesByStatus(nodes, TaskNodeStatus.COMPENSATED);
        if (compensatedCount > 0) {
            return "部分节点已通过补偿收口";
        }
        return null;
    }

    private String buildNodeStatusSummary(TaskNode node) {
        if (node == null || node.getStatus() == null) {
            return null;
        }
        return switch (node.getStatus()) {
            case WAITING_RETRY -> "等待系统自动重试";
            case WAITING_INTERVENTION -> "等待人工处理";
            case COMPENSATED -> "节点已通过补偿收口";
            case READY -> "节点已就绪，等待调度";
            case DISPATCHED -> "节点已派发";
            case RUNNING -> "节点执行中";
            case FAILED -> "节点执行失败";
            case SUCCESS -> "节点执行成功";
            case SKIPPED -> "节点已跳过";
            case PAUSED -> "节点已暂停";
            case PENDING -> "待执行";
        };
    }

    private String buildEventStreamPath(Long taskId) {
        return taskId == null ? null : "/api/task/" + taskId + "/events";
    }

    private Map<Long, Integer> buildPlanVersionMap(Long taskId) {
        if (taskId == null) {
            return Map.of();
        }
        Map<Long, Integer> planVersionMap = new HashMap<>();
        List<TaskPlan> plans = taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(taskId);
        if (plans == null || plans.isEmpty()) {
            return planVersionMap;
        }
        for (TaskPlan plan : plans) {
            if (plan.getId() != null && plan.getPlanVersion() != null) {
                planVersionMap.put(plan.getId(), plan.getPlanVersion());
            }
        }
        return planVersionMap;
    }

    private Integer resolvePlanVersion(Map<Long, Integer> planVersionMap, Long planVersionId) {
        if (planVersionId == null || planVersionMap == null) {
            return null;
        }
        return planVersionMap.get(planVersionId);
    }

    private boolean canExecuteTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.PENDING || status == AnalysisTaskStatus.FAILED;
    }

    private boolean canResumeTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.FAILED || status == AnalysisTaskStatus.STOPPED;
    }

    private boolean canRetryTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.FAILED;
    }

    private boolean canStopTask(AnalysisTaskStatus status) {
        return status == AnalysisTaskStatus.RUNNING;
    }

    private String buildTaskInterventionSummary(AnalysisTaskStatus status) {
        if (status == AnalysisTaskStatus.RUNNING) {
            return "任务运行中支持停止整任务；单节点可暂停尚未启动的节点、手动跳过未启动节点，或对运行中节点发起协作式终止请求。";
        }
        if (status == AnalysisTaskStatus.FAILED) {
            return "当前支持恢复执行、整任务重置，以及从指定节点重跑；如存在已暂停节点，也可恢复单节点后继续执行。";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "当前支持基于已有检查点恢复执行，以及从指定节点发起局部重跑；若是节点暂停导致收口，也可直接恢复对应节点继续执行。";
        }
        if (status == AnalysisTaskStatus.SUCCESS) {
            return "任务已完成，可查看报告；如需局部修正，支持从指定节点重新发起执行并保留未受影响成果。";
        }
        return "任务尚未开始，可直接启动执行；节点级支持暂停待执行节点、手动跳过待执行节点，以及从指定节点重跑。";
    }

    private String buildTaskResumeAdvice(AnalysisTaskStatus status) {
        if (status == AnalysisTaskStatus.FAILED) {
            return "恢复执行会尽量保留已完成节点的成果，只重跑尚未完成或失败的链路。";
        }
        if (status == AnalysisTaskStatus.STOPPED) {
            return "恢复执行会基于已有检查点继续推进，已完成节点通常不会重复执行。";
        }
        return null;
    }

    private String buildTaskRetryAdvice(AnalysisTaskStatus status) {
        if (status != AnalysisTaskStatus.FAILED) {
            return null;
        }
        return "整任务重置会清空当前任务的派生产物，并从头重走整条执行链路。";
    }

    private String buildTaskReplayEntrySummary(AnalysisTaskStatus status) {
        if (status != AnalysisTaskStatus.FAILED && status != AnalysisTaskStatus.STOPPED && status != AnalysisTaskStatus.RUNNING) {
            return null;
        }
        return "可前往节点追踪与高级诊断查看原始执行轨迹、证据与事件留痕。";
    }

    private boolean canRerunNode(AnalysisTaskStatus taskStatus) {
        return taskStatus != AnalysisTaskStatus.RUNNING;
    }

    private boolean canUpdateConfigAndRerun(TaskNode node, AnalysisTaskStatus taskStatus) {
        return canRerunNode(taskStatus) && hasText(node.getNodeConfig());
    }

    private boolean canPauseNode(TaskNode node) {
        return node != null && node.getStatus() == TaskNodeStatus.PENDING;
    }

    private boolean canResumeNode(TaskNode node) {
        return node != null && node.getStatus() == TaskNodeStatus.PAUSED;
    }

    private boolean canSkipNode(TaskNode node) {
        return node != null && (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED);
    }

    private boolean canTerminateNode(TaskNode node) {
        return node != null
                && (node.getStatus() == TaskNodeStatus.PENDING
                || node.getStatus() == TaskNodeStatus.PAUSED
                || (node.getStatus() == TaskNodeStatus.RUNNING && node.getControlState() != TaskNodeControlState.TERMINATE_REQUESTED));
    }

    private boolean hasReusableCheckpoint(TaskNode node) {
        if (node == null || node.getAgentType() != AgentType.COLLECTOR || !hasText(node.getOutputData())) {
            return false;
        }
        JsonNode output = readJson(node.getOutputData());
        return output != null && output.hasNonNull("searchAudit");
    }

    private String buildNodeInterventionSummary(TaskNode node,
                                                AnalysisTaskStatus taskStatus,
                                                List<String> affectedNodeNames,
                                                boolean canReuseCheckpoint,
                                                boolean canPause,
                                                boolean canResumeNode,
                                                boolean canSkip,
                                                boolean canTerminate) {
        if (node.getControlState() == TaskNodeControlState.TERMINATE_REQUESTED) {
            return "该节点已收到协作式终止请求。系统不会强杀当前线程，会在本轮执行返回后丢弃结果并将节点标记为已跳过。";
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "该节点已暂停，不会继续参与 DAG 调度。可恢复为待执行节点继续流程，也可直接手动跳过。";
        }
        if (node.getStatus() == TaskNodeStatus.RUNNING) {
            return canTerminate
                    ? "该节点正在执行中。当前支持发起协作式终止请求，系统会在本轮执行返回后停止使用本轮结果；暂不支持线程级强杀。"
                    : "该节点正在执行中，当前不支持直接重跑或改配置后继续；如需中断，请先发起终止请求或停止整任务。";
        }
        int downstreamCount = Math.max(affectedNodeNames.size() - 1, 0);
        StringBuilder summary = new StringBuilder("从当前节点重跑会重置当前节点");
        if (downstreamCount > 0) {
            summary.append("及 ").append(downstreamCount).append(" 个下游节点");
        }
        summary.append("，其余未受影响成果会被保留。");
        if (node.getAgentType() == AgentType.COLLECTOR) {
            summary.append(canReuseCheckpoint
                    ? " 当前采集节点存在搜索检查点，可优先复用候选与选源现场。"
                    : " 当前采集节点没有可复用的搜索检查点，将按最新配置重新补源与采集。");
        }
        if (canPause) {
            summary.append(" 当前可先暂停该待执行节点，暂停后不会被调度。");
        }
        if (canResumeNode) {
            summary.append(" 当前可恢复该暂停节点并继续后续流程。");
        }
        if (canSkip) {
            summary.append(" 当前可手动跳过该节点，系统会按依赖关系自动处理下游。");
        }
        if (canTerminate) {
            summary.append(" 如需放弃本节点，也可直接终止。");
        }
        if (taskStatus == AnalysisTaskStatus.RUNNING) {
            summary.append(" 任务仍在运行时，节点重跑与改配置后继续仍需等待任务结束。");
        }
        return summary.toString();
    }

    private String buildNodeRerunActionSummary(TaskNode node, boolean canRerun) {
        if (node == null || !canRerun || node.getStatus() == TaskNodeStatus.PAUSED) {
            return null;
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return "适合在确认上游输入仍然可复用时直接重跑当前采集节点，并保留未受影响分支的成果。";
        }
        return "适合在当前节点结果需要修正、但上游成果仍可复用时发起局部重跑。";
    }

    private String buildNodeConfigRerunActionSummary(TaskNode node, boolean canUpdateConfigAndRerun) {
        if (node == null || !canUpdateConfigAndRerun) {
            return null;
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return "适合在需要扩大补源范围、切换搜索策略或调整验证规则后，再从当前采集节点继续执行。";
        }
        return "适合在需要调整当前节点配置后再继续执行时使用。";
    }

    private String buildNodeImpactSummary(TaskNode node, List<String> affectedNodeNames) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "恢复后会继续当前节点及其后续链路；也可直接跳过，让系统按依赖关系自动收口。";
        }
        if (affectedNodeNames == null || affectedNodeNames.isEmpty()) {
            return "当前操作仅影响当前节点本身。";
        }
        return "本次操作将影响 " + affectedNodeNames.size() + " 个节点：" + String.join("、", affectedNodeNames) + "。";
    }

    private String buildNodeCheckpointSummary(TaskNode node, boolean canReuseCheckpoint) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "该节点暂停前的检查点与输入输出仍可在恢复时继续参考。";
        }
        if (node.getAgentType() == AgentType.COLLECTOR) {
            return canReuseCheckpoint
                    ? "当前存在可复用检查点，可优先沿用历史候选、选源和补源现场。"
                    : "当前没有可复用检查点，重跑时会重新进行候选发现与补源。";
        }
        return "当前节点没有额外的检查点复用说明。";
    }

    private String buildNodeReplayEntrySummary(TaskNode node) {
        if (node == null) {
            return null;
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "可通过节点追踪与高级诊断查看暂停前的输入、输出和事件留痕。";
        }
        return "可通过节点追踪与高级诊断查看该节点的原始输入、输出、日志与事件。";
    }

    /**
     * 节点影响范围需要严格遵守依赖关系与 branchKey 边界。
     * 这里在查询侧复用同样的 BFS 规则，确保用户看到的“重跑影响范围”与实际运行时一致。
     */
    private List<TaskNode> collectAffectedNodes(List<TaskNode> nodes, String startNodeName) {
        if (nodes == null || nodes.isEmpty() || !hasText(startNodeName)) {
            return List.of();
        }
        TaskNode startNode = nodes.stream()
                .filter(node -> startNodeName.equals(node.getNodeName()))
                .findFirst()
                .orElse(null);
        if (startNode == null) {
            return List.of();
        }

        Map<String, List<TaskNode>> dependentsMap = new HashMap<>();
        for (TaskNode node : nodes) {
            dependentsMap.putIfAbsent(node.getNodeName(), new ArrayList<>());
        }
        for (TaskNode node : nodes) {
            for (String dependencyName : node.parseDependencyNames()) {
                dependentsMap.computeIfAbsent(dependencyName, key -> new ArrayList<>()).add(node);
            }
        }

        Set<String> affectedNodeNames = new HashSet<>();
        ArrayDeque<TaskNode> queue = new ArrayDeque<>();
        queue.add(startNode);
        affectedNodeNames.add(startNode.getNodeName());

        while (!queue.isEmpty()) {
            TaskNode current = queue.removeFirst();
            for (TaskNode dependent : dependentsMap.getOrDefault(current.getNodeName(), List.of())) {
                if (!isWithinAffectedBranch(startNode, dependent)) {
                    continue;
                }
                if (affectedNodeNames.add(dependent.getNodeName())) {
                    queue.addLast(dependent);
                }
            }
        }

        return nodes.stream()
                .filter(candidate -> affectedNodeNames.contains(candidate.getNodeName()))
                .toList();
    }

    private boolean isWithinAffectedBranch(TaskNode startNode, TaskNode candidate) {
        String startBranch = normalizeBranchKey(startNode.getBranchKey());
        String candidateBranch = normalizeBranchKey(candidate.getBranchKey());
        if (startBranch.equals(candidateBranch)) {
            return true;
        }
        return candidateBranch.startsWith(startBranch + "/");
    }

    private String normalizeBranchKey(String branchKey) {
        return branchKey == null || branchKey.isBlank() ? "root" : branchKey;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private TaskNodeConfigSummary buildNodeConfigSummaryData(TaskNode node) {
        if (node == null || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(truncate(node.getNodeConfig(), 180))
                    .build();
        }
        return buildConfigSummaryData(node.getAgentType(), config);
    }

    private TaskNodeConfigSummary buildPreviewNodeConfigSummaryData(WorkflowPlan.WorkflowPlanNode node) {
        if (node == null || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(truncate(node.getNodeConfig(), 180))
                    .build();
        }
        return buildConfigSummaryData(AgentType.valueOf(node.getAgentType()), config);
    }

    private CollectorNodeInsightResponse buildCollectorNodeInsight(TaskNode node) {
        if (node == null || node.getAgentType() != AgentType.COLLECTOR || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return null;
        }
        JsonNode output = hasText(node.getOutputData()) ? readJson(node.getOutputData()) : null;
        return buildCollectorNodeInsight(config, output);
    }

    private CollectorNodeInsightResponse buildPreviewCollectorNodeInsight(WorkflowPlan.WorkflowPlanNode node) {
        if (node == null || !"COLLECTOR".equalsIgnoreCase(node.getAgentType()) || !hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return null;
        }
        return buildCollectorNodeInsight(config, null);
    }

    private CollectorNodeInsightResponse buildCollectorNodeInsight(JsonNode config, JsonNode output) {
        String competitorName = defaultIfBlank(
                textOrNull(output, "competitor"),
                defaultIfBlank(textOrNull(config, "competitorName"), "未命名竞品"));
        String sourceType = defaultIfBlank(
                textOrNull(output, "sourceType"),
                defaultIfBlank(textOrNull(config, "sourceType"), "OFFICIAL"));
        String searchMode = defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID");
        List<String> searchQueries = readStringList(output == null ? null : output.get("searchQueries"));
        if (searchQueries.isEmpty()) {
            searchQueries = readStringList(config.get("searchQueries"));
        }

        List<SourceCandidate> sourceCandidates = convertList(
                output != null && output.has("sourceCandidates") ? output.get("sourceCandidates") : config.get("sourceCandidates"),
                new TypeReference<List<SourceCandidate>>() {
                });
        List<CollectorSelectedTargetSummary> selectedTargets = convertList(
                output == null ? null : output.get("selectedTargets"),
                new TypeReference<List<CollectorSelectedTargetSummary>>() {
                });
        SearchAuditSnapshot searchAudit = convertValue(output == null ? null : output.get("searchAudit"), SearchAuditSnapshot.class);
        if (searchAudit != null && (searchAudit.getSourceUrls() == null || searchAudit.getSourceUrls().isEmpty())) {
            // 历史 output 里可能只有顶层 sourceUrls，没有回填进正式 searchAudit。
            // 这里在详情组装阶段兜底一次，保证前端拿到的正式契约可直接消费。
            searchAudit.setSourceUrls(readStringList(output == null ? null : output.get("sourceUrls")));
        }

        return CollectorNodeInsightResponse.builder()
                .competitorName(competitorName)
                .sourceType(sourceType)
                .sourceTypeLabel(sourceTypeLabel(sourceType))
                .sourceScope(readStringList(config.get("sourceScope")))
                .competitorUrls(readStringList(config.get("competitorUrls")))
                .searchMode(searchMode)
                .searchModeLabel(searchModeLabel(searchMode))
                .searchQueries(searchQueries)
                .browserSearchEnabled(config.path("browserSearchEnabled").asBoolean(false))
                .verifyResultPage(config.path("verifyResultPage").asBoolean(
                        config.path("verifyCandidates").asBoolean(false)))
                .minVerifiedCandidates(config.path("minVerifiedCandidates").asInt(0) > 0
                        ? config.path("minVerifiedCandidates").asInt(0)
                        : null)
                .preferredDomains(readStringList(config.get("preferredDomains")))
                .candidateCount(sourceCandidates.size())
                .selectedCount(selectedTargets.size())
                .successCollected(output == null ? 0 : output.path("successCollected").asInt(0))
                .totalCollected(output == null ? 0 : output.path("totalCollected").asInt(0))
                .discoveryNotes(defaultIfBlank(textOrNull(output, "discoveryNotes"), textOrNull(config, "discoveryNotes")))
                .searchProgress(convertValue(output == null ? null : output.get("searchProgress"), SearchProgressSnapshot.class))
                .searchExecutionPlan(convertValue(
                        output != null && output.has("searchExecutionPlan") ? output.get("searchExecutionPlan") : config.get("searchExecutionPlan"),
                        SearchExecutionPlan.class))
                .searchExecutionTrace(convertValue(
                        output == null ? null : output.get("searchExecutionTrace"),
                        SearchExecutionTrace.class))
                .searchAudit(searchAudit)
                .searchProgressSnapshots(convertList(
                        output == null ? null : output.get("searchProgressSnapshots"),
                        new TypeReference<List<SearchProgressSnapshot>>() {
                        }))
                .taskRagContext(textOrNull(output, "taskRagContext"))
                .sourceCandidates(sourceCandidates)
                .selectedTargets(selectedTargets)
                .build();
    }

    private TaskNodeConfigSummary buildConfigSummaryData(AgentType agentType, JsonNode config) {
        if (agentType == AgentType.COLLECTOR) {
            String competitor = defaultIfBlank(textOrNull(config, "competitorName"), "未命名竞品");
            String sourceType = defaultIfBlank(textOrNull(config, "sourceType"), "OFFICIAL");
            String sourceTypeLabel = sourceTypeLabel(sourceType);
            int candidateCount = config.path("sourceCandidates").isArray() ? config.path("sourceCandidates").size() : 0;
            int queryCount = config.path("searchQueries").isArray() ? config.path("searchQueries").size() : 0;
            int stepCount = config.path("searchExecutionPlan").path("steps").isArray()
                    ? config.path("searchExecutionPlan").path("steps").size()
                    : 0;
            String searchMode = defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID");
            String searchModeLabel = searchModeLabel(searchMode);
            boolean browserEnabled = config.path("browserSearchEnabled").asBoolean(false);
            boolean verificationEnabled = config.path("verifyResultPage").asBoolean(
                    config.path("verifyCandidates").asBoolean(false));
            int minVerifiedCandidates = config.path("minVerifiedCandidates").asInt(0);
            StringBuilder summary = new StringBuilder();
            summary.append(competitor)
                    .append(" 路 ")
                    .append(sourceTypeLabel)
                    .append("采集")
                    .append(" 路 搜索模式：")
                    .append(searchModeLabel)
                    .append(" 路 候选 ")
                    .append(candidateCount)
                    .append(" 条");
            if (queryCount > 0) {
                summary.append(" 路 Query ")
                        .append(queryCount)
                        .append(" 条");
            }
            if (stepCount > 0) {
                summary.append(" 路 计划 ")
                        .append(stepCount)
                        .append(" 步");
            }
            summary.append(" 路 浏览器补源：")
                    .append(browserEnabled ? "开启" : "关闭")
                    .append(" 路 结果页验证：")
                    .append(verificationEnabled ? "开启" : "关闭");
            return TaskNodeConfigSummary.builder()
                    .summaryText(summary.toString())
                    .competitorName(competitor)
                    .sourceType(sourceType)
                    .sourceTypeLabel(sourceTypeLabel)
                    .searchMode(searchMode)
                    .searchModeLabel(searchModeLabel)
                    .candidateCount(candidateCount)
                    .queryCount(queryCount)
                    .stepCount(stepCount)
                    .browserSearchEnabled(browserEnabled)
                    .verificationEnabled(verificationEnabled)
                    .minVerifiedCandidates(minVerifiedCandidates > 0 ? minVerifiedCandidates : null)
                    .sourceScope(readStringList(config.get("sourceScope")))
                    .preferredDomains(readStringList(config.get("preferredDomains")))
                    .competitorUrls(readStringList(config.get("competitorUrls")))
                    .discoveryNotes(textOrNull(config, "discoveryNotes"))
                    .build();
        }
        if (agentType == AgentType.EXTRACTOR) {
            List<String> dimensions = readStringList(config.get("dimensions"));
            return TaskNodeConfigSummary.builder()
                    .summaryText("分析维度：" + defaultIfBlank(summarizeArray(config.get("dimensions"), 4), "使用默认维度"))
                    .dimensions(dimensions)
                    .build();
        }
        if (agentType == AgentType.ANALYZER) {
            int competitorCount = config.path("competitorCount").asInt(0);
            int dimensionCount = config.path("dimensionCount").asInt(0);
            return TaskNodeConfigSummary.builder()
                    .summaryText("汇总 " + competitorCount + " 个竞品，分析 " + dimensionCount + " 个维度")
                    .competitorCount(competitorCount)
                    .dimensionCount(dimensionCount)
                    .build();
        }
        if (agentType == AgentType.WRITER) {
            boolean revision = "revision".equalsIgnoreCase(config.path("mode").asText(""));
            String reportLanguage = defaultIfBlank(textOrNull(config, "reportLanguage"), "中文");
            String reportTemplate = defaultIfBlank(textOrNull(config, "reportTemplate"), "标准模板");
            if (revision) {
                return TaskNodeConfigSummary.builder()
                        .summaryText("根据评审结果修订报告")
                        .mode("revision")
                        .reportLanguage(reportLanguage)
                        .reportTemplate(reportTemplate)
                        .sourceNode(textOrNull(config, "sourceNode"))
                        .build();
            }
            return TaskNodeConfigSummary.builder()
                    .summaryText("输出 " + reportLanguage + " / " + reportTemplate + " 报告")
                    .mode(defaultIfBlank(textOrNull(config, "mode"), "initial"))
                    .reportLanguage(reportLanguage)
                    .reportTemplate(reportTemplate)
                    .sourceNode(textOrNull(config, "sourceNode"))
                    .build();
        }
        if (agentType == AgentType.REVIEWER) {
            String policy = defaultIfBlank(textOrNull(config, "qualityPolicy"), "标准质量评审");
            String sourceNode = textOrNull(config, "sourceNode");
            return TaskNodeConfigSummary.builder()
                    .summaryText(hasText(sourceNode) ? "评审策略：" + policy + "，复核节点：" + sourceNode : "评审策略：" + policy)
                    .qualityPolicy(policy)
                    .sourceNode(sourceNode)
                    .build();
        }
        return TaskNodeConfigSummary.builder()
                .summaryText(truncate(config.toString(), 180))
                .build();
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (hasText(value)) {
                items.add(value);
            }
        }
        return items;
    }

    private <T> T convertValue(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull() || type == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, type);
        } catch (IllegalArgumentException e) {
            log.warn("convert json value failed for type {}", type.getSimpleName(), e);
            return null;
        }
    }

    private <T> List<T> convertList(JsonNode node, TypeReference<List<T>> typeReference) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return Collections.emptyList();
        }
        try {
            List<T> values = objectMapper.convertValue(node, typeReference);
            return values == null ? Collections.emptyList() : values;
        } catch (IllegalArgumentException e) {
            log.warn("convert json list failed", e);
            return Collections.emptyList();
        }
    }

    private String sourceTypeLabel(String sourceType) {
        if (!hasText(sourceType)) {
            return "官网";
        }
        return switch (sourceType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DOCS" -> "文档";
            case "PRICING" -> "定价";
            case "NEWS" -> "资讯";
            case "REVIEW" -> "测评";
            case "OFFICIAL" -> "官网";
            default -> sourceType;
        };
    }

    private String searchModeLabel(String searchMode) {
        if (!hasText(searchMode)) {
            return "混合";
        }
        return switch (searchMode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BROWSER_ONLY" -> "仅浏览器";
            case "HTTP_ONLY" -> "仅 HTTP";
            case "HEURISTIC_ONLY" -> "仅规则候选";
            case "HYBRID" -> "混合";
            default -> searchMode;
        };
    }

    private String summarizeArray(JsonNode node, int limit) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        int count = 0;
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (!hasText(value)) {
                continue;
            }
            items.add(value);
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        if (node.size() > items.size()) {
            items.add("等 " + node.size() + " 项");
        }
        return String.join("、", items);
    }

    private String buildOutputSummary(TaskNode node) {
        if (node.getOutputData() == null || node.getOutputData().isBlank()) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        if (output == null) {
            return truncate(node.getOutputData(), 240);
        }

        if (node.getAgentType() == AgentType.COLLECTOR) {
            return buildCollectorOutputSummary(output);
        }
        if (node.getAgentType() == AgentType.REVIEWER) {
            return buildReviewerOutputSummary(output);
        }
        return truncate(node.getOutputData(), 240);
    }

    private String buildAiGovernanceSummary(TaskNode node) {
        if (node == null || node.getTaskId() == null || !StringUtils.hasText(node.getNodeName())) {
            return null;
        }
        Optional<AiCallAuditRecord> latestAudit = aiCallAuditRecordRepository
                .findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(node.getTaskId(), node.getNodeName());
        if (latestAudit.isEmpty()) {
            return null;
        }
        AiCallAuditRecord record = latestAudit.get();
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(record.getSummary())) {
            parts.add(record.getSummary());
        }
        if (record.getTotalTokens() != null && record.getTotalTokens() > 0) {
            parts.add("Token 总量=" + record.getTotalTokens());
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private String buildCollectorOutputSummary(JsonNode output) {
        String competitor = textOrNull(output, "competitor");
        String sourceType = textOrNull(output, "sourceType");
        int selectedCount = output.path("selectedTargets").isArray() ? output.path("selectedTargets").size() : 0;
        int successCollected = output.path("successCollected").asInt(0);
        int totalCollected = output.path("totalCollected").asInt(0);
        String supplementMethod = textOrNull(output.path("searchExecutionTrace"), "supplementMethod");
        String progressStatus = textOrNull(output.path("searchProgress"), "status");
        String degradationReason = textOrNull(output.path("searchExecutionTrace"), "degradationReason");

        StringBuilder summary = new StringBuilder();
        if (competitor != null) {
            summary.append(competitor);
        }
        if (sourceType != null) {
            if (!summary.isEmpty()) {
                summary.append(" / ");
            }
            summary.append(sourceType);
        }
        if (!summary.isEmpty()) {
            summary.append("：");
        }
        summary.append("选中 ").append(selectedCount)
                .append(" 条，采集成功 ").append(successCollected).append("/").append(totalCollected).append(" 条");
        /*
         * 这里保持与 AnalysisTaskService 迁移前完全一致的文案格式，
         * 避免前端展示和既有回归测试因为分隔符变化而产生兼容性回归。
         */
        if (supplementMethod != null) {
            summary.append("，补源方式=").append(supplementMethod);
        }
        if (progressStatus != null) {
            summary.append("，进度状态=").append(progressStatus);
        }
        if (degradationReason != null) {
            summary.append("，降级原因=").append(degradationReason);
        }
        return summary.toString();
    }

    private String buildReviewerOutputSummary(JsonNode output) {
        boolean passed = output.path("passed").asBoolean(false);
        int score = output.path("score").asInt(-1);
        int issueCount = output.path("issues").isArray() ? output.path("issues").size() : 0;
        String summary = textOrNull(output, "summary");

        StringBuilder readable = new StringBuilder();
        readable.append(passed ? "质量评审通过" : "质量评审未通过");
        if (score >= 0) {
            readable.append("，评分 ").append(score);
        }
        readable.append("，问题 ").append(issueCount).append(" 项");
        if (summary != null) {
            readable.append("，").append(summary);
        }
        return readable.toString();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("serialize preview json failed", e);
            return null;
        }
    }

    private NodeExecutionRecoveryPolicy recoveryPolicy() {
        return new NodeExecutionRecoveryPolicy(objectMapper);
    }
}
