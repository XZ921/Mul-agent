package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.capability.AgentCapability;
import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.agent.capability.AgentExecutionRequest;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.WorkflowDeadLetterRecord;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.AgentSuggestion;
import cn.bugstack.competitoragent.orchestration.AnalyzerSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.ExtractorSuggestionAssembler;
import cn.bugstack.competitoragent.orchestration.OrchestrationContext;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionAdapter;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.task.SharedNodeOutputProjector;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.runtime.DynamicPlanAppender;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeEventEmitter;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeStateRefresher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAG 执行器，负责按 DAG 依赖驱动各类 Agent，并处理依赖、重试、条件分支和任务收口。
 */
@Slf4j
@Component
public class DagExecutor {

    private final TaskNodeRepository nodeRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AgentCapabilityRegistry agentCapabilityRegistry;
    private final ObjectMapper objectMapper;
    private final NodeExecutionRecoveryPolicy recoveryPolicy;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskExecutionLockService taskExecutionLockService;
    private final TaskEventPublisher taskEventPublisher;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository;
    private final WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository;
    private final RuntimeStateRefresher runtimeStateRefresher;
    private final RuntimeEventEmitter runtimeEventEmitter;
    private final DynamicPlanAppender dynamicPlanAppender;
    private final TaskQuotaCoordinator taskQuotaCoordinator;
    private final ExtractorSuggestionAssembler extractorSuggestionAssembler;
    private final AnalyzerSuggestionAssembler analyzerSuggestionAssembler;
    private final OrchestrationDecisionService orchestrationDecisionService;
    private final OrchestrationTraceService orchestrationTraceService;
    private final List<SharedNodeOutputProjector> sharedNodeOutputProjectors;

    @Autowired
    public DagExecutor(TaskNodeRepository nodeRepository,
                       AnalysisTaskRepository taskRepository,
                       AgentCapabilityRegistry agentCapabilityRegistry,
                       ObjectMapper objectMapper,
                       TaskSnapshotCacheService taskSnapshotCacheService,
                       TaskExecutionLockService taskExecutionLockService,
                       TaskEventPublisher taskEventPublisher,
                       AgentLogService agentLogService,
                       WorkflowEventPublisher workflowEventPublisher,
                       TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository,
                       WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository,
                       RuntimeStateRefresher runtimeStateRefresher,
                       RuntimeEventEmitter runtimeEventEmitter,
                       DynamicPlanAppender dynamicPlanAppender,
                       TaskQuotaCoordinator taskQuotaCoordinator,
                       ExtractorSuggestionAssembler extractorSuggestionAssembler,
                       AnalyzerSuggestionAssembler analyzerSuggestionAssembler,
                       OrchestrationDecisionService orchestrationDecisionService,
                       OrchestrationTraceService orchestrationTraceService,
                       List<SharedNodeOutputProjector> sharedNodeOutputProjectors) {
        this.nodeRepository = nodeRepository;
        this.taskRepository = taskRepository;
        this.agentCapabilityRegistry = agentCapabilityRegistry;
        this.objectMapper = objectMapper;
        this.recoveryPolicy = new NodeExecutionRecoveryPolicy(objectMapper);
        this.taskSnapshotCacheService = taskSnapshotCacheService;
        this.taskExecutionLockService = taskExecutionLockService;
        this.taskEventPublisher = taskEventPublisher;
        this.workflowEventPublisher = workflowEventPublisher;
        this.taskNodeExecutionAttemptRepository = taskNodeExecutionAttemptRepository;
        this.workflowDeadLetterRecordRepository = workflowDeadLetterRecordRepository;
        this.runtimeStateRefresher = runtimeStateRefresher;
        this.runtimeEventEmitter = runtimeEventEmitter;
        this.dynamicPlanAppender = dynamicPlanAppender;
        this.taskQuotaCoordinator = taskQuotaCoordinator;
        this.extractorSuggestionAssembler = extractorSuggestionAssembler;
        this.analyzerSuggestionAssembler = analyzerSuggestionAssembler;
        this.orchestrationDecisionService = orchestrationDecisionService;
        this.orchestrationTraceService = orchestrationTraceService;
        this.sharedNodeOutputProjectors = sharedNodeOutputProjectors == null ? List.of() : List.copyOf(sharedNodeOutputProjectors);
    }

    public DagExecutor(TaskNodeRepository nodeRepository,
                       AnalysisTaskRepository taskRepository,
                       AgentCapabilityRegistry agentCapabilityRegistry,
                       ObjectMapper objectMapper,
                       TaskSnapshotCacheService taskSnapshotCacheService,
                       TaskExecutionLockService taskExecutionLockService,
                       TaskEventPublisher taskEventPublisher,
                       AgentLogService agentLogService,
                       WorkflowEventPublisher workflowEventPublisher,
                       TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository,
                       WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository,
                       RuntimeStateRefresher runtimeStateRefresher,
                       RuntimeEventEmitter runtimeEventEmitter,
                       DynamicPlanAppender dynamicPlanAppender,
                       TaskQuotaCoordinator taskQuotaCoordinator,
                       ExtractorSuggestionAssembler extractorSuggestionAssembler,
                       OrchestrationDecisionService orchestrationDecisionService,
                       OrchestrationTraceService orchestrationTraceService,
                       List<SharedNodeOutputProjector> sharedNodeOutputProjectors) {
        this(nodeRepository,
                taskRepository,
                agentCapabilityRegistry,
                objectMapper,
                taskSnapshotCacheService,
                taskExecutionLockService,
                taskEventPublisher,
                agentLogService,
                workflowEventPublisher,
                taskNodeExecutionAttemptRepository,
                workflowDeadLetterRecordRepository,
                runtimeStateRefresher,
                runtimeEventEmitter,
                dynamicPlanAppender,
                taskQuotaCoordinator,
                extractorSuggestionAssembler,
                new AnalyzerSuggestionAssembler(objectMapper),
                orchestrationDecisionService,
                orchestrationTraceService,
                sharedNodeOutputProjectors);
    }

    public DagExecutor(TaskNodeRepository nodeRepository,
                       AnalysisTaskRepository taskRepository,
                       AgentCapabilityRegistry agentCapabilityRegistry,
                       ObjectMapper objectMapper,
                       TaskSnapshotCacheService taskSnapshotCacheService,
                       TaskExecutionLockService taskExecutionLockService,
                       TaskEventPublisher taskEventPublisher,
                       AgentLogService agentLogService,
                       WorkflowEventPublisher workflowEventPublisher,
                       TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository,
                       WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository,
                       RuntimeStateRefresher runtimeStateRefresher,
                       RuntimeEventEmitter runtimeEventEmitter,
                       DynamicPlanAppender dynamicPlanAppender,
                       TaskQuotaCoordinator taskQuotaCoordinator,
                       List<SharedNodeOutputProjector> sharedNodeOutputProjectors) {
        this(nodeRepository,
                taskRepository,
                agentCapabilityRegistry,
                objectMapper,
                taskSnapshotCacheService,
                taskExecutionLockService,
                taskEventPublisher,
                agentLogService,
                workflowEventPublisher,
                taskNodeExecutionAttemptRepository,
                workflowDeadLetterRecordRepository,
                runtimeStateRefresher,
                runtimeEventEmitter,
                dynamicPlanAppender,
                taskQuotaCoordinator,
                new ExtractorSuggestionAssembler(objectMapper),
                new AnalyzerSuggestionAssembler(objectMapper),
                new OrchestrationDecisionService(new OrchestrationDecisionAdapter()),
                null,
                sharedNodeOutputProjectors);
    }

    public DagExecutor(TaskNodeRepository nodeRepository,
                       AnalysisTaskRepository taskRepository,
                       AgentCapabilityRegistry agentCapabilityRegistry,
                       ObjectMapper objectMapper,
                       TaskSnapshotCacheService taskSnapshotCacheService,
                       TaskExecutionLockService taskExecutionLockService,
                       TaskEventPublisher taskEventPublisher,
                       AgentLogService agentLogService,
                       WorkflowEventPublisher workflowEventPublisher,
                       TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository,
                       WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository,
                       RuntimeStateRefresher runtimeStateRefresher,
                       RuntimeEventEmitter runtimeEventEmitter,
                       DynamicPlanAppender dynamicPlanAppender,
                       TaskQuotaCoordinator taskQuotaCoordinator) {
        this(nodeRepository,
                taskRepository,
                agentCapabilityRegistry,
                objectMapper,
                taskSnapshotCacheService,
                taskExecutionLockService,
                taskEventPublisher,
                agentLogService,
                workflowEventPublisher,
                taskNodeExecutionAttemptRepository,
                workflowDeadLetterRecordRepository,
                runtimeStateRefresher,
                runtimeEventEmitter,
                dynamicPlanAppender,
                taskQuotaCoordinator,
                List.of());
    }

    public void execute(Long taskId, AgentContext context) {
        log.info("start dag execution, taskId={}", taskId);
        if (!markTaskRunning(taskId)) {
            log.info("skip dag execution because task is not in runnable state, taskId={}", taskId);
            return;
        }

        // 执行期节点集合需要支持“终审失败后动态补图”场景。
        // 仓储层可能返回只读集合，所以这里先复制成可变列表，
        // 避免后续 append 动态节点时在运行期抛出 UnsupportedOperationException。
        List<TaskNode> nodes = new ArrayList<>(
                nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
        if (nodes.isEmpty()) {
            failTask(taskId, "No executable DAG nodes");
            return;
        }

        seedSharedOutputs(context, nodes);
        runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
        Map<String, TaskNode> nodeMap = buildNodeMap(nodes);
        validateExecutableNodes(taskId, nodes, nodeMap);

        TaskNode lastTouchedNode = null;
        ExecutorService executor = Executors.newFixedThreadPool(resolveParallelism(nodes.size()));
        CompletionService<NodeExecutionResult> completionService = new ExecutorCompletionService<>(executor);
        int runningCount = 0;
        try {
            while (true) {
                refreshNodeStates(taskId, nodes);
                if (isTaskStopped(taskId)) {
                    stopRemainingNodes(taskId, nodes, lastTouchedNode);
                    return;
                }

                DispatchCycleResult dispatchResult = dispatchExecutableNodes(taskId, context, nodes, nodeMap, completionService);
                runningCount += dispatchResult.getSubmittedCount();
                if (dispatchResult.getLastTouchedNode() != null) {
                    lastTouchedNode = dispatchResult.getLastTouchedNode();
                }

                if (dispatchResult.isSkippedAny()) {
                    continue;
                }

                if (runningCount == 0) {
                    if (!dispatchResult.isProgressed()) {
                        if (waitForNextRetryWindow(nodes)) {
                            continue;
                        }
                        break;
                    }
                    continue;
                }

                NodeExecutionResult completedResult = awaitNextCompletedNode(completionService);
                runningCount--;
                if (completedResult != null) {
                    lastTouchedNode = completedResult.getNode();
                    if (dynamicPlanAppender.maybeAppendDynamicPlan(taskId, nodes, nodeMap, completedResult.getNode())) {
                        runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
                    }
                }
            }
        } finally {
            executor.shutdown();
        }

        updateTaskFinalStatus(taskId);
    }

    /**
     * 续跑时重新灌入已成功节点输出，让后续节点能像在同一条执行链中一样复用历史结果。
     */
    private void seedSharedOutputs(AgentContext context, List<TaskNode> nodes) {
        taskSnapshotCacheService.getCachedSharedOutputEnvelopes(context.getTaskId())
                .forEach(context::putSharedOutputEnvelope);
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS
                    && node.getOutputData() != null
                    && !node.getOutputData().isBlank()) {
                projectSharedOutput(context.getTaskId(), node.getNodeName(), node.getPlanVersionId(), node.getOutputData())
                        .ifPresentOrElse(
                                envelope -> context.putSharedOutputEnvelope(node.getNodeName(), envelope),
                                () -> context.putSharedOutput(node.getNodeName(), node.getOutputData())
                        );
            }
        }
    }

    /**
     * 运行期统一经由能力注册表触发节点执行。
     * <p>
     * 这样 DagExecutor 不需要知道底层到底是传统 Agent、适配器还是未来的新执行器，
     * 只依赖稳定的能力请求/响应协议即可。
     */
    private AgentResult executeNodeOnce(AgentCapability capability, AgentContext context) {
        try {
            return capability.execute(new AgentExecutionRequest(context)).result();
        } catch (Exception e) {
            return AgentResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private DispatchCycleResult dispatchExecutableNodes(Long taskId,
                                                        AgentContext sharedContext,
                                                        List<TaskNode> nodes,
                                                        Map<String, TaskNode> nodeMap,
                                                        CompletionService<NodeExecutionResult> completionService) {
        boolean progressed = false;
        boolean skippedAny = false;
        int submittedCount = 0;
        TaskNode lastTouchedNode = null;
        LocalDateTime now = LocalDateTime.now();
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS
                    || node.getStatus() == TaskNodeStatus.COMPENSATED
                    || node.getStatus() == TaskNodeStatus.FAILED
                    || node.getStatus() == TaskNodeStatus.SKIPPED
                    || node.getStatus() == TaskNodeStatus.RUNNING) {
                continue;
            }
            if (node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION) {
                continue;
            }
            if (node.getStatus() == TaskNodeStatus.WAITING_RETRY
                    && node.getNextRetryAt() != null
                    && node.getNextRetryAt().isAfter(now)) {
                continue;
            }
            if (node.getControlState() == TaskNodeControlState.TERMINATE_REQUESTED
                    && (node.getStatus() == TaskNodeStatus.PENDING || node.getStatus() == TaskNodeStatus.PAUSED)) {
                skipNode(node, defaultIfBlank(node.getInterventionReason(), "节点已收到终止请求，执行前终止"));
                node.setInterventionReason(defaultIfBlank(node.getInterventionReason(), "节点已收到终止请求，执行前终止"));
                progressed = true;
                skippedAny = true;
                lastTouchedNode = node;
                continue;
            }
            if (node.getStatus() == TaskNodeStatus.PAUSED) {
                continue;
            }

            DependencyState dependencyState = resolveDependencyState(node, nodeMap);
            if (dependencyState == DependencyState.BLOCKED) {
                skipNode(node, buildDependencyFailureReason(node, nodeMap));
                progressed = true;
                skippedAny = true;
                lastTouchedNode = node;
                continue;
            }
            if (dependencyState == DependencyState.WAITING) {
                continue;
            }

            if (!shouldExecuteNode(node, sharedContext, nodes)) {
                skipNode(node, buildConditionalSkipReason(node, sharedContext, nodes));
                progressed = true;
                skippedAny = true;
                lastTouchedNode = node;
                continue;
            }

            AgentContext nodeContext = forkNodeContext(sharedContext, node);
            String lockOwner = "node-runner-" + taskId + "-" + node.getNodeName() + "-" + UUID.randomUUID();
            if (!taskExecutionLockService.tryAcquireNodeExecutionLock(
                    taskId,
                    node.getNodeName(),
                    lockOwner,
                    Duration.ofMinutes(10))) {
                continue;
            }
            workflowEventPublisher.publishNodeReady(node);
            TaskNode runningNode = markNodeRunning(node, nodeContext);
            completionService.submit(() -> executeRunningNode(taskId, sharedContext, runningNode, nodeContext, lockOwner));
            progressed = true;
            submittedCount++;
            lastTouchedNode = runningNode;
        }
        return new DispatchCycleResult(progressed, skippedAny, submittedCount, lastTouchedNode);
    }

    private NodeExecutionResult awaitNextCompletedNode(CompletionService<NodeExecutionResult> completionService) {
        try {
            return completionService.take().get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed while waiting node completion", e);
        }
    }

    /**
     * 当当前轮次没有任何节点被派发，但仍存在 WAITING_RETRY 节点时，
     * 不能直接把整个 DAG 判定为“无事可做”并收口。
     * 这里统一等待到最近一个重试窗口打开，再进入下一轮调度，
     * 避免因时钟粒度或极短 backoff 让节点永久卡在 WAITING_RETRY。
     */
    private boolean waitForNextRetryWindow(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<LocalDateTime> nextRetryWindow = nodes.stream()
                .filter(node -> node.getStatus() == TaskNodeStatus.WAITING_RETRY)
                .map(TaskNode::getNextRetryAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo);
        if (nextRetryWindow.isEmpty()) {
            return false;
        }
        if (!nextRetryWindow.get().isAfter(now)) {
            return true;
        }
        long waitMillis = Math.max(1L, Duration.between(now, nextRetryWindow.get()).toMillis());
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting next retry window", e);
        }
        return true;
    }

    private NodeExecutionResult executeRunningNode(Long taskId,
                                                   AgentContext sharedContext,
                                                   TaskNode node,
                                                   AgentContext nodeContext,
                                                   String nodeLockOwner) {
        try {
            if (isTaskStopped(taskId)) {
                markNodeStopped(node);
                return new NodeExecutionResult(node);
            }
            AgentCapability capability = agentCapabilityRegistry.resolve(node.getAgentType());
            if (capability == null) {
                failNode(node, "Missing agent implementation: " + node.getAgentType());
                return new NodeExecutionResult(node);
            }

            AgentResult result = executeNodeOnce(capability, nodeContext);
            TaskNode latestNode = nodeRepository.findById(node.getId()).orElse(node);
            if (latestNode.getControlState() == TaskNodeControlState.TERMINATE_REQUESTED) {
                latestNode.setStatus(TaskNodeStatus.SKIPPED);
                latestNode.setOutputData(null);
                latestNode.setErrorMessage(defaultIfBlank(
                        latestNode.getInterventionReason(),
                        "节点已收到终止请求，当前轮执行结果已丢弃"));
                latestNode.setInterventionReason(defaultIfBlank(
                        latestNode.getInterventionReason(),
                        "节点已收到终止请求，当前轮执行结果已丢弃"));
                latestNode.setControlState(TaskNodeControlState.NONE);
                latestNode.setCompletedAt(LocalDateTime.now());
                TaskNode terminatedNode = nodeRepository.save(latestNode);
                runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
                taskEventPublisher.publishNodeStatusEvent(taskId, terminatedNode, "NODE_TERMINATED");
                syncNodeState(node, terminatedNode);
                return new NodeExecutionResult(terminatedNode);
            }

            TaskNode completedNode = applyExecutionResult(taskId, sharedContext, latestNode, result);
            TaskNode gatedNode = applyAgentSuggestionGate(taskId, completedNode, result);
            if (gatedNode.getStatus() == TaskNodeStatus.WAITING_INTERVENTION) {
                runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
                runtimeEventEmitter.publishNodeExecutionEvents(taskId, gatedNode);
                workflowEventPublisher.publishNodeFailed(gatedNode, extractSourceUrls(gatedNode.getOutputData()));
                syncNodeState(node, gatedNode);
                return new NodeExecutionResult(gatedNode);
            }
            runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
            runtimeEventEmitter.publishNodeExecutionEvents(taskId, completedNode);
            if (result.getStatus() == TaskNodeStatus.SUCCESS) {
                workflowEventPublisher.publishNodeCompleted(completedNode, extractSourceUrls(result.getOutputData()));
            } else {
                workflowEventPublisher.publishNodeFailed(completedNode, extractSourceUrls(result.getOutputData()));
            }
            syncNodeState(node, completedNode);
            return new NodeExecutionResult(completedNode);
        } finally {
            taskExecutionLockService.releaseNodeExecutionLock(taskId, node.getNodeName(), nodeLockOwner);
        }
    }

    private TaskNode applyAgentSuggestionGate(Long taskId, TaskNode completedNode, AgentResult result) {
        if (result == null
                || result.getStatus() != TaskNodeStatus.SUCCESS
                || completedNode == null
                || completedNode.getOutputData() == null
                || completedNode.getOutputData().isBlank()) {
            return completedNode;
        }
        List<AgentSuggestion> suggestions = buildAgentSuggestions(taskId, completedNode);
        if (suggestions.isEmpty()) {
            return completedNode;
        }
        List<String> sourceUrls = extractSourceUrls(completedNode.getOutputData());
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
                .taskId(taskId)
                .planVersionId(completedNode.getPlanVersionId())
                .branchKey(completedNode.getBranchKey())
                .triggerNodeName(completedNode.getNodeName())
                .passed(false)
                .agentSuggestions(suggestions)
                .sourceUrls(sourceUrls)
                .evidenceState(sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.PARTIAL_SOURCE)
                .inputSummary(completedNode.getNodeName() + " 输出后发现 AgentSuggestion，进入 Orchestrator 决策。")
                .build()
                .normalized();
        List<OrchestrationDecision> decisions = orchestrationDecisionService.decide(orchestrationContext);
        for (OrchestrationDecision decision : decisions) {
            recordAgentDecisionTrace(taskId, completedNode, decision);
        }
        return decisions.stream()
                .filter(decision -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()))
                .findFirst()
                .map(decision -> markNodeWaitingForIntervention(completedNode, decision))
                .orElse(completedNode);
    }

    /**
     * AgentSuggestion 是运行期协作决策的统一入口。
     * 当前只允许 Extractor 和 Analyzer 进入该 gate，避免误拦截 Writer / Reviewer。
     */
    private List<AgentSuggestion> buildAgentSuggestions(Long taskId, TaskNode completedNode) {
        if (completedNode == null || completedNode.getNodeName() == null) {
            return List.of();
        }
        if ("extract_schema".equals(completedNode.getNodeName())) {
            return extractorSuggestionAssembler.fromExtractorOutput(
                    taskId,
                    completedNode.getNodeName(),
                    completedNode.getOutputData());
        }
        if ("analyze_competitors".equals(completedNode.getNodeName())) {
            return analyzerSuggestionAssembler.fromAnalyzerOutput(
                    taskId,
                    completedNode.getNodeName(),
                    completedNode.getOutputData());
        }
        return List.of();
    }

    private void recordAgentDecisionTrace(Long taskId, TaskNode completedNode, OrchestrationDecision decision) {
        if (orchestrationTraceService == null || decision == null) {
            return;
        }
        orchestrationTraceService.recordDecision(taskId, completedNode, decision, null, null);
    }

    private TaskNode markNodeWaitingForIntervention(TaskNode completedNode, OrchestrationDecision decision) {
        completedNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        completedNode.setFailureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED);
        completedNode.setInterventionReason(decision.getReason());
        completedNode.setErrorMessage(decision.getReason());
        completedNode.setCompletedAt(LocalDateTime.now());
        return nodeRepository.save(completedNode);
    }

    /**
     * 节点执行结果统一在这里收口。
     * 这样自动重试、人工介入、尝试记录和 DLQ 留痕都不会散落在多个分支里。
     */
    /**
     * 终审节点产出的 revisionDirectives 在这里真正升级为“编排动作”。
     * 只有当 Reviewer 已成功写入权威状态后，才允许派生新的 TaskPlan 和动态节点，
     * 避免把瞬时失败或脏结果放大成重复补图。
     */
    /**
     * 动态计划快照里同时包含旧节点和新节点，这里只把当前派生分支上的新增节点落库。
     */
    /**
     * 这里必须持续使用数据库中的最新节点实体。
     * 否则前一步 save 产生的新版本号如果没有带回，后续再次 save 同一个旧对象时就会触发乐观锁冲突。
     */
    private TaskNode applyExecutionResult(Long taskId,
                                          AgentContext sharedContext,
                                          TaskNode node,
                                          AgentResult result) {
        LocalDateTime now = LocalDateTime.now();
        int attemptNo = resolveAttemptNo(node);
        node.setLastEventId(UUID.randomUUID().toString());
        node.setLastAttemptAt(now);
        node.setCompletedAt(now);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInterventionReason(null);

        if (result.getStatus() == TaskNodeStatus.SUCCESS) {
            node.setStatus(TaskNodeStatus.SUCCESS);
            node.setOutputData(result.getOutputData());
            node.setErrorMessage(null);
            node.setFailureCategory(null);
            node.setNextRetryAt(null);
            TaskNode savedNode = nodeRepository.save(node);
            recordExecutionAttempt(savedNode, attemptNo, TaskNodeStatus.SUCCESS, null, null);
            projectSharedOutput(taskId, savedNode.getNodeName(), savedNode.getPlanVersionId(), result.getOutputData())
                    .ifPresentOrElse(envelope -> {
                        sharedContext.putSharedOutputEnvelope(savedNode.getNodeName(), envelope);
                        taskSnapshotCacheService.cacheSharedOutputEnvelope(taskId, envelope);
                    }, () -> {
                        sharedContext.putSharedOutput(savedNode.getNodeName(), result.getOutputData());
                        taskSnapshotCacheService.cacheNodeOutput(taskId, node.getNodeName(), result.getOutputData());
                    });
            return savedNode;
        }

        node.setOutputData(result.getOutputData());
        NodeRetryDecision decision = NodeRetryDecision.evaluate(node, result.getErrorMessage());
        node.setStatus(decision.getNextStatus());
        node.setRetryCount(decision.getNextRetryCount());
        node.setFailureCategory(decision.getFailureCategory());
        node.setErrorMessage(result.getErrorMessage());
        node.setInterventionReason(decision.requiresManualIntervention() ? decision.getUserReadableSummary() : null);
        node.setNextRetryAt(decision.isRetryPlanned() ? now.plusNanos(1) : null);
        TaskNode savedNode = nodeRepository.save(node);
        recordExecutionAttempt(savedNode, attemptNo, decision.getNextStatus(), decision.getFailureCategory(), result.getErrorMessage());
        if (decision.shouldEnterDlq()) {
            recordDeadLetter(savedNode, result.getErrorMessage());
        }
        return savedNode;
    }

    /**
     * 统一尝试把节点输出投影成共享信封。
     * 没有匹配投影器时返回 empty，调用方再退回原始字符串共享。
     */
    private Optional<SharedNodeOutputEnvelope> projectSharedOutput(Long taskId,
                                                                   String nodeName,
                                                                   Long planVersionId,
                                                                   String outputData) {
        if (outputData == null || outputData.isBlank()) {
            return Optional.empty();
        }
        return sharedNodeOutputProjectors.stream()
                .filter(projector -> projector.supports(outputData))
                .findFirst()
                .map(projector -> projector.project(taskId, nodeName, planVersionId, outputData));
    }

    private void recordExecutionAttempt(TaskNode node,
                                        int attemptNo,
                                        TaskNodeStatus resultStatus,
                                        NodeFailureCategory failureCategory,
                                        String errorSummary) {
        if (node == null || node.getId() == null) {
            return;
        }
        TaskNodeExecutionAttempt attempt = TaskNodeExecutionAttempt.builder()
                .taskId(node.getTaskId())
                .nodeId(node.getId())
                .nodeName(node.getNodeName())
                .attemptNo(attemptNo)
                .idempotencyKey(buildAttemptIdempotencyKey(node, attemptNo))
                .resultStatus(resultStatus)
                .failureCategory(failureCategory)
                .errorSummary(errorSummary)
                .sourceEventId(node.getLastEventId())
                .build();
        taskNodeExecutionAttemptRepository.save(attempt);
    }

    private void recordDeadLetter(TaskNode node, String errorSummary) {
        if (node == null || node.getId() == null) {
            return;
        }
        List<TaskNodeExecutionAttempt> attempts = taskNodeExecutionAttemptRepository
                .findByTaskIdAndNodeIdOrderByAttemptNoAsc(node.getTaskId(), node.getId());
        WorkflowDeadLetterRecord record = WorkflowDeadLetterRecord.builder()
                .taskId(node.getTaskId())
                .nodeId(node.getId())
                .nodeName(node.getNodeName())
                .sourceEventId(node.getLastEventId())
                .failureCategory(node.getFailureCategory())
                .latestErrorSummary(errorSummary)
                .retryHistory(buildRetryHistory(attempts))
                .originalPayload(node.getInputData())
                .build();
        workflowDeadLetterRecordRepository.save(record);
    }

    private String buildAttemptIdempotencyKey(TaskNode node, int attemptNo) {
        return "%d-%d-%s-%d".formatted(node.getTaskId(), node.getId(), node.getNodeName(), attemptNo);
    }

    /**
     * retryCount 只服务于“当前这一轮自动重试预算”的判断；
     * 但执行尝试历史需要跨人工恢复/重跑持续累加，否则会和旧的审计记录发生幂等键冲突。
     */
    private int resolveAttemptNo(TaskNode node) {
        if (node == null || node.getTaskId() == null || node.getId() == null) {
            return 1;
        }
        Optional<TaskNodeExecutionAttempt> latestAttempt = taskNodeExecutionAttemptRepository
                .findTopByTaskIdAndNodeIdOrderByAttemptNoDesc(node.getTaskId(), node.getId());
        if (latestAttempt.isPresent()) {
            return Math.max(1, latestAttempt.get().getAttemptNo() + 1);
        }

        List<TaskNodeExecutionAttempt> history = taskNodeExecutionAttemptRepository
                .findByTaskIdAndNodeIdOrderByAttemptNoAsc(node.getTaskId(), node.getId());
        if (history == null || history.isEmpty()) {
            return 1;
        }

        int maxAttemptNo = history.stream()
                .map(TaskNodeExecutionAttempt::getAttemptNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return Math.max(1, maxAttemptNo + 1);
    }

    private String buildRetryHistory(List<TaskNodeExecutionAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> items = attempts.stream()
                .map(attempt -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attemptNo", attempt.getAttemptNo());
                    item.put("resultStatus", attempt.getResultStatus() == null ? null : attempt.getResultStatus().name());
                    item.put("failureCategory", attempt.getFailureCategory() == null ? null : attempt.getFailureCategory().name());
                    item.put("errorSummary", attempt.getErrorSummary());
                    item.put("createdAt", attempt.getCreatedAt());
                    return item;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("failed to serialize retry history", e);
            return "[]";
        }
    }

    private AgentContext forkNodeContext(AgentContext sharedContext, TaskNode node) {
        return AgentContext.builder()
                .taskId(sharedContext.getTaskId())
                .taskName(sharedContext.getTaskName())
                .subjectProduct(sharedContext.getSubjectProduct())
                .competitorNames(sharedContext.getCompetitorNames())
                .competitorUrls(sharedContext.getCompetitorUrls())
                .analysisDimensions(sharedContext.getAnalysisDimensions())
                .sourceScope(sharedContext.getSourceScope())
                .reportLanguage(sharedContext.getReportLanguage())
                .reportTemplate(sharedContext.getReportTemplate())
                .currentNodeName(node.getNodeName())
                .currentNodeConfig(node.getNodeConfig())
                .traceId(sharedContext.getTraceId())
                .sharedState(sharedContext.getSharedState())
                .sharedOutputEnvelopes(sharedContext.getSharedOutputEnvelopes())
                .createdAt(sharedContext.getCreatedAt())
                .build();
    }

    private int resolveParallelism(int candidateNodeCount) {
        int cpuBoundLimit = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(candidateNodeCount, Math.min(4, cpuBoundLimit)));
    }

    private boolean markTaskRunning(Long taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if (task.getStatus() != AnalysisTaskStatus.PENDING) {
                return false;
            }
            task.setStatus(AnalysisTaskStatus.RUNNING);
            if (task.getStartedAt() == null) {
                task.setStartedAt(LocalDateTime.now());
            }
            task.setCompletedAt(null);
            task.setErrorMessage(null);
            taskRepository.save(task);
            runtimeStateRefresher.refreshRuntimeSnapshot(taskId);
            return true;
        }).orElse(false);
    }

    private TaskNode markNodeRunning(TaskNode node, AgentContext context) {
        node.setStatus(TaskNodeStatus.RUNNING);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(buildNodeInput(node, context));
        node.setErrorMessage(null);
        node.setInterventionReason(null);
        node.setStartedAt(LocalDateTime.now());
        node.setCompletedAt(null);
        TaskNode savedNode = nodeRepository.save(node);
        syncNodeState(node, savedNode);
        runtimeStateRefresher.refreshRuntimeSnapshot(savedNode.getTaskId());
        taskEventPublisher.publishNodeStatusEvent(savedNode.getTaskId(), savedNode, "NODE_RUNNING");
        return savedNode;
    }

    /**
     * 固化每个节点的运行输入，便于节点 trace 和失败排障时回放上下文。
     */
    private String buildNodeInput(TaskNode node, AgentContext context) {
        return """
                {"taskId":%d,"taskName":"%s","nodeName":"%s","agentType":"%s","dependsOn":%s,"nodeConfig":%s}
                """.formatted(
                context.getTaskId(),
                escapeJson(context.getTaskName()),
                escapeJson(node.getNodeName()),
                node.getAgentType(),
                node.getDependsOn() == null || node.getDependsOn().isBlank() ? "[]" : node.getDependsOn(),
                node.getNodeConfig() == null ? "null" : node.getNodeConfig()
        ).trim();
    }

    private void skipNode(TaskNode node, String reason) {
        node.setStatus(TaskNodeStatus.SKIPPED);
        node.setControlState(TaskNodeControlState.NONE);
        node.setInputData(null);
        node.setOutputData(null);
        node.setErrorMessage(reason);
        if (node.getInterventionReason() == null || node.getInterventionReason().isBlank()) {
            node.setInterventionReason(null);
        }
        node.setStartedAt(node.getStartedAt() == null ? LocalDateTime.now() : node.getStartedAt());
        node.setCompletedAt(LocalDateTime.now());
        nodeRepository.save(node);
        runtimeStateRefresher.refreshRuntimeSnapshot(node.getTaskId());
        taskEventPublisher.publishNodeStatusEvent(node.getTaskId(), node, "NODE_SKIPPED");
        log.warn("node skipped: {}, reason={}", node.getNodeName(), reason);
    }

    private void failNode(TaskNode node, String errorMessage) {
        node.setStatus(TaskNodeStatus.FAILED);
        node.setControlState(TaskNodeControlState.NONE);
        node.setErrorMessage(errorMessage);
        node.setFailureCategory(NodeFailureCategory.PERMANENT_BUSINESS);
        node.setInterventionReason(null);
        node.setStartedAt(node.getStartedAt() == null ? LocalDateTime.now() : node.getStartedAt());
        node.setCompletedAt(LocalDateTime.now());
        nodeRepository.save(node);
        runtimeStateRefresher.refreshRuntimeSnapshot(node.getTaskId());
        taskEventPublisher.publishNodeStatusEvent(node.getTaskId(), node, "NODE_FAILED");
        workflowEventPublisher.publishNodeFailed(node, List.of());
        log.error("node failed: {}, reason={}", node.getNodeName(), errorMessage);
    }

    private void markNodeStopped(TaskNode node) {
        node.setStatus(TaskNodeStatus.SKIPPED);
        node.setControlState(TaskNodeControlState.NONE);
        node.setErrorMessage("任务已被用户主动停止");
        node.setInterventionReason(null);
        node.setCompletedAt(LocalDateTime.now());
        nodeRepository.save(node);
        runtimeStateRefresher.refreshRuntimeSnapshot(node.getTaskId());
        taskEventPublisher.publishNodeStatusEvent(node.getTaskId(), node, "NODE_STOPPED");
    }

    /**
     * 依赖判断除了 SUCCESS 外，还要兼容 allowFailedDependency 的可选放行语义。
     */
    private boolean dependenciesSatisfied(TaskNode node, Map<String, TaskNode> nodeMap) {
        List<String> dependencyNames = parseDependencyNames(node.getDependsOn());
        if (dependencyNames.isEmpty()) {
            return true;
        }

        for (String dependencyName : dependencyNames) {
            TaskNode dependencyNode = nodeMap.get(dependencyName);
            if (dependencyNode == null) {
                return false;
            }

            if (dependencyNode.getStatus() == TaskNodeStatus.SUCCESS
                    || dependencyNode.getStatus() == TaskNodeStatus.COMPENSATED) {
                continue;
            }

            if (node.isAllowFailedDependency()
                    && (dependencyNode.getStatus() == TaskNodeStatus.FAILED
                    || dependencyNode.getStatus() == TaskNodeStatus.SKIPPED)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private DependencyState resolveDependencyState(TaskNode node, Map<String, TaskNode> nodeMap) {
        List<String> dependencyNames = parseDependencyNames(node.getDependsOn());
        if (dependencyNames.isEmpty()) {
            return DependencyState.READY;
        }
        boolean waiting = false;
        for (String dependencyName : dependencyNames) {
            TaskNode dependencyNode = nodeMap.get(dependencyName);
            if (dependencyNode == null) {
                return DependencyState.BLOCKED;
            }
            if (dependencyNode.getStatus() == TaskNodeStatus.SUCCESS
                    || dependencyNode.getStatus() == TaskNodeStatus.COMPENSATED) {
                continue;
            }
            if (node.isAllowFailedDependency()
                    && (dependencyNode.getStatus() == TaskNodeStatus.FAILED
                    || dependencyNode.getStatus() == TaskNodeStatus.SKIPPED)) {
                continue;
            }
            if (dependencyNode.getStatus() == TaskNodeStatus.PENDING
                    || dependencyNode.getStatus() == TaskNodeStatus.READY
                    || dependencyNode.getStatus() == TaskNodeStatus.DISPATCHED
                    || dependencyNode.getStatus() == TaskNodeStatus.RUNNING
                    || dependencyNode.getStatus() == TaskNodeStatus.WAITING_RETRY
                    || dependencyNode.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                    || dependencyNode.getStatus() == TaskNodeStatus.PAUSED) {
                waiting = true;
                continue;
            }
            return DependencyState.BLOCKED;
        }
        return waiting ? DependencyState.WAITING : DependencyState.READY;
    }

    private List<String> parseDependencyNames(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank() || "[]".equals(dependsOn)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(dependsOn, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("failed to parse dependencies: {}", dependsOn, e);
            return List.of();
        }
    }

    /**
     * V2 的任务成败不仅取决于节点是否跑完，还取决于质量闭环是否最终通过。
     */
    private void updateTaskFinalStatus(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy.resolveTaskExecution(task, nodes);
            classifyDownstreamQualityGateFailure(nodes, resolution);
            task.setStatus(resolution.getStatus());
            task.setErrorMessage(resolution.getErrorMessage());
            task.setCompletedAt(resolution.resolveCompletedAtForPersistence(task.getCompletedAt()));
            releaseQuotaIfTaskReachedTerminalStatus(task, resolution.getStatus());
            taskRepository.save(task);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    resolution.getStatus(),
                    resolution.getErrorMessage(),
                    nodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }

    /**
     * 终审节点“执行成功”不等于质量闭环通过。
     * 当 extractor/analyzer/writer/reviewer 都跑完但最终 `passed=false` 时，需要把失败移交给下游消费链路，
     * 避免用户继续误判为抽取或分析边界问题。
     */
    private void classifyDownstreamQualityGateFailure(List<TaskNode> nodes,
                                                      NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution) {
        if (resolution == null || nodes == null || nodes.isEmpty()) {
            return;
        }
        classifyDownstreamConsumptionFailureNodes(nodes);
        if (resolution.getStatus() == AnalysisTaskStatus.FAILED) {
            nodes.stream()
                    .filter(this::isFailedFinalQualityGateNode)
                    .findFirst()
                    .ifPresent(node -> markDownstreamConsumptionGap(
                            node,
                            "终审执行成功但质量闭环未通过，问题已移交写作、评审或交付链路处理"));
            return;
        }
        if (resolution.getStatus() == AnalysisTaskStatus.STOPPED) {
            nodes.stream()
                    .filter(this::isInitialReviewHumanInterventionGapNode)
                    .findFirst()
                    .ifPresent(node -> markDownstreamConsumptionGap(
                            node,
                            "初审已明确要求人工补证据或调整写作策略，当前阻断属于写作/评审链路的下游消费缺口"));
        }
    }

    /**
     * 第二轮需要把“extract_schema 已成功，但 analyzer / writer 无法继续消费运行态产物”的场景
     * 从普通业务失败里拆出来，统一归口为 DOWNSTREAM_CONSUMPTION_GAP。
     * 这里故意只覆盖 extractor 之后的消费节点，避免把采集或抽取本身的失败误判为下游质量闭环问题。
     */
    private void classifyDownstreamConsumptionFailureNodes(List<TaskNode> nodes) {
        for (TaskNode node : nodes) {
            if (!isDownstreamConsumptionFailureNode(node, nodes)) {
                continue;
            }
            markDownstreamConsumptionGap(
                    node,
                    buildDownstreamConsumptionGapReason(node));
        }
    }

    private boolean isDownstreamConsumptionFailureNode(TaskNode node, List<TaskNode> nodes) {
        if (node == null || node.getStatus() != TaskNodeStatus.FAILED) {
            return false;
        }
        if (!isDownstreamConsumptionCandidate(node)) {
            return false;
        }
        if (node.getFailureCategory() == NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP) {
            return false;
        }
        return switch (node.getAgentType()) {
            case ANALYZER -> hasSuccessfulNode(nodes, "extract_schema");
            case WRITER -> hasSuccessfulNode(nodes, "extract_schema")
                    && hasSuccessfulNode(nodes, "analyze_competitors");
            case REVIEWER -> hasSuccessfulNode(nodes, "write_report")
                    || hasSuccessfulNode(nodes, "rewrite_report");
            default -> false;
        };
    }

    private boolean isDownstreamConsumptionCandidate(TaskNode node) {
        if (node == null || node.getAgentType() == null) {
            return false;
        }
        return switch (node.getAgentType()) {
            case ANALYZER, WRITER, REVIEWER -> true;
            default -> false;
        };
    }

    private boolean hasSuccessfulNode(List<TaskNode> nodes, String nodeName) {
        if (nodes == null || nodeName == null || nodeName.isBlank()) {
            return false;
        }
        return nodes.stream()
                .filter(candidate -> nodeName.equals(candidate.getNodeName()))
                .anyMatch(candidate -> candidate.getStatus() == TaskNodeStatus.SUCCESS);
    }

    private String buildDownstreamConsumptionGapReason(TaskNode node) {
        if (node == null || node.getAgentType() == null) {
            return "下游节点无法继续消费上游输出，需检查写作、评审或交付链路";
        }
        return switch (node.getAgentType()) {
            case ANALYZER -> "analyzer 无法继续消费 extract_schema 产物，问题已归口为下游消费缺口";
            case WRITER -> "writer 无法继续消费 analyzer 结果或证据束，问题已归口为下游消费缺口";
            case REVIEWER -> "reviewer 无法继续消费写作结果或质量闭环上下文，问题已归口为下游消费缺口";
            default -> "下游节点无法继续消费上游输出，需检查写作、评审或交付链路";
        };
    }

    private boolean isFailedFinalQualityGateNode(TaskNode node) {
        if (node == null || node.getStatus() != TaskNodeStatus.SUCCESS) {
            return false;
        }
        String nodeName = node.getNodeName();
        boolean finalReviewNode = "quality_check_final".equals(nodeName)
                || (nodeName != null && nodeName.startsWith("quality_check_revision_patch_v"));
        return finalReviewNode && !isPassedReview(node.getOutputData());
    }

    /**
     * 这里专门识别“初审已经确认问题在写作/评审消费链路，且自动改写不能继续”的阻断场景。
     * 这类任务虽然公开状态是 STOPPED，但停点已经越过 extractor / analyzer，
     * workflow 需要把 reviewer 节点显式标成 DOWNSTREAM_CONSUMPTION_GAP，便于 rerun / replay / UI 统一解释。
     */
    private boolean isInitialReviewHumanInterventionGapNode(TaskNode node) {
        if (node == null || node.getStatus() != TaskNodeStatus.SUCCESS) {
            return false;
        }
        if (!"quality_check".equals(node.getNodeName())) {
            return false;
        }
        if (isPassedReview(node.getOutputData()) || !requiresHumanIntervention(node.getOutputData())) {
            return false;
        }
        JsonNode reviewOutput = readJson(node.getOutputData());
        if (reviewOutput == null || !"initial".equalsIgnoreCase(reviewOutput.path("reviewStage").asText("initial"))) {
            return false;
        }
        return true;
    }

    private void markDownstreamConsumptionGap(TaskNode node, String reason) {
        if (node == null) {
            return;
        }
        node.setFailureCategory(NodeFailureCategory.DOWNSTREAM_CONSUMPTION_GAP);
        node.setInterventionReason(reason);
        nodeRepository.save(node);
    }

    private void failTask(Long taskId, String errorMessage) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(AnalysisTaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setCompletedAt(LocalDateTime.now());
            taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
            taskRepository.save(task);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    AnalysisTaskStatus.FAILED,
                    errorMessage,
                    nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }

    private boolean isTaskStopped(Long taskId) {
        return taskRepository.findById(taskId)
                .map(task -> task.getStatus() == AnalysisTaskStatus.STOPPED)
                .orElse(false);
    }

    private void stopRemainingNodes(Long taskId, List<TaskNode> nodes, TaskNode currentNode) {
        if (currentNode == null) {
            currentNode = nodes.stream()
                    .filter(node -> node.getStatus() == TaskNodeStatus.RUNNING)
                    .findFirst()
                    .orElse(nodes.isEmpty() ? null : nodes.get(0));
        }
        boolean afterCurrent = false;
        for (TaskNode candidate : nodes) {
            if (!afterCurrent) {
                if (currentNode != null && candidate.getId().equals(currentNode.getId())) {
                    afterCurrent = true;
                }
                continue;
            }
            if (candidate.getStatus() == TaskNodeStatus.SUCCESS
                    || candidate.getStatus() == TaskNodeStatus.FAILED
                    || candidate.getStatus() == TaskNodeStatus.SKIPPED) {
                continue;
            }
            candidate.setStatus(TaskNodeStatus.SKIPPED);
            candidate.setControlState(TaskNodeControlState.NONE);
            candidate.setErrorMessage("任务已被用户主动停止");
            candidate.setInterventionReason(null);
            candidate.setCompletedAt(LocalDateTime.now());
            nodeRepository.save(candidate);
        }

        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(AnalysisTaskStatus.STOPPED);
            task.setErrorMessage("任务已由用户主动停止");
            task.setCompletedAt(LocalDateTime.now());
            taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
            taskRepository.save(task);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    AnalysisTaskStatus.STOPPED,
                    task.getErrorMessage(),
                    nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
        log.info("task stopped during dag execution, taskId={}, currentNode={}",
                taskId, currentNode == null ? "unknown" : currentNode.getNodeName());
    }

    /**
     * 执行前的兜底校验，确保数据库中的 DAG 仍然满足“无环 + 依赖存在 + 顺序正确”。
     */
    private void validateExecutableNodes(Long taskId, List<TaskNode> nodes, Map<String, TaskNode> nodeMap) {
        for (TaskNode node : nodes) {
            for (String dependencyName : parseDependencyNames(node.getDependsOn())) {
                TaskNode dependencyNode = nodeMap.get(dependencyName);
                if (dependencyNode == null) {
                    failTask(taskId, "Missing dependency node: " + node.getNodeName() + " -> " + dependencyName);
                    throw new IllegalStateException("Missing dependency node: " + dependencyName);
                }
                if (dependencyNode.getExecutionOrder() >= node.getExecutionOrder()) {
                    failTask(taskId, "Invalid dependency order: " + node.getNodeName() + " -> " + dependencyName);
                    throw new IllegalStateException("Invalid dependency order");
                }
            }
        }
        ensureAcyclic(taskId, nodes);
    }

    private Map<String, TaskNode> buildNodeMap(List<TaskNode> nodes) {
        Map<String, TaskNode> nodeMap = new HashMap<>();
        for (TaskNode node : nodes) {
            nodeMap.put(node.getNodeName(), node);
        }
        return nodeMap;
    }

    /**
     * 再次拓扑检查，避免环路把任务卡死在未完成状态。
     */
    private void ensureAcyclic(Long taskId, List<TaskNode> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (TaskNode node : nodes) {
            indegree.put(node.getNodeName(), 0);
            adjacency.put(node.getNodeName(), new java.util.ArrayList<>());
        }

        for (TaskNode node : nodes) {
            for (String dependencyName : parseDependencyNames(node.getDependsOn())) {
                adjacency.get(dependencyName).add(node.getNodeName());
                indegree.put(node.getNodeName(), indegree.get(node.getNodeName()) + 1);
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((nodeName, degree) -> {
            if (degree == 0) {
                queue.add(nodeName);
            }
        });

        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                int degree = indegree.computeIfPresent(next, (key, value) -> value - 1);
                if (degree == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != nodes.size()) {
            failTask(taskId, "Cyclic workflow dependencies detected");
            throw new IllegalStateException("Cyclic workflow dependencies detected");
        }
    }

    private String buildDependencyFailureReason(TaskNode node, Map<String, TaskNode> nodeMap) {
        List<String> dependencyNames = parseDependencyNames(node.getDependsOn());
        if (dependencyNames.isEmpty()) {
            return "Dependencies not satisfied";
        }

        StringBuilder reason = new StringBuilder("Dependencies not satisfied: ");
        boolean appended = false;
        for (String dependencyName : dependencyNames) {
            TaskNode dependencyNode = nodeMap.get(dependencyName);
            if (dependencyNode == null) {
                if (appended) {
                    reason.append("; ");
                }
                reason.append(dependencyName).append(" missing");
                appended = true;
                continue;
            }
            if (dependencyNode.getStatus() == TaskNodeStatus.SUCCESS) {
                continue;
            }
            if (node.isAllowFailedDependency()
                    && (dependencyNode.getStatus() == TaskNodeStatus.FAILED
                    || dependencyNode.getStatus() == TaskNodeStatus.SKIPPED)) {
                continue;
            }
            if (appended) {
                reason.append("; ");
            }
            reason.append(dependencyName).append("=").append(dependencyNode.getStatus());
            appended = true;
        }
        return appended ? reason.toString() : "Dependencies not satisfied";
    }

    private boolean isPassedReview(String outputData) {
        JsonNode json = readJson(outputData);
        return json != null && json.path("passed").asBoolean(false);
    }

    private boolean requiresHumanIntervention(String outputData) {
        JsonNode json = readJson(outputData);
        if (json == null) {
            return false;
        }
        if (json.has("requiresHumanIntervention")) {
            return json.path("requiresHumanIntervention").asBoolean(false);
        }
        if (json.path("passed").asBoolean(false)) {
            return false;
        }
        int score = json.has("score") ? json.path("score").asInt(100) : 100;
        int errorCount = 0;
        JsonNode issues = json.get("issues");
        if (issues != null && issues.isArray()) {
            for (JsonNode issue : issues) {
                if ("ERROR".equalsIgnoreCase(issue.path("severity").asText())) {
                    errorCount++;
                }
            }
        }
        return score <= 20 || errorCount >= 4;
    }

    /**
     * 条件节点根据 trigger 决定是否执行，例如“质检失败才重写”“重写成功后才终审”。
     */
    private boolean shouldExecuteNode(TaskNode node, AgentContext context, List<TaskNode> allNodes) {
        String trigger = resolveTrigger(node.getNodeConfig());
        if (trigger == null || trigger.isBlank()) {
            return true;
        }
        return switch (trigger) {
            case "review_failed" -> {
                JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
                boolean manualResumeApproved = isManualResumeApproved(node.getNodeConfig());
                yield reviewOutput != null
                        && !reviewOutput.path("passed").asBoolean(true)
                        && (manualResumeApproved
                        || (!requiresHumanIntervention(context.getSharedOutput("quality_check"))
                        && reviewOutput.path("autoRewriteAllowed").asBoolean(true)));
            }
            case "rewrite_executed" -> allNodes.stream()
                    .anyMatch(current -> "rewrite_report".equals(current.getNodeName())
                            && current.getStatus() == TaskNodeStatus.SUCCESS);
            default -> true;
        };
    }

    private String buildConditionalSkipReason(TaskNode node, AgentContext context, List<TaskNode> allNodes) {
        String trigger = resolveTrigger(node.getNodeConfig());
        if ("review_failed".equals(trigger)) {
            JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
            if (reviewOutput == null) {
                return "跳过修订：缺少有效的评审结果";
            }
            if (requiresHumanIntervention(context.getSharedOutput("quality_check"))
                    && !isManualResumeApproved(node.getNodeConfig())) {
                return "跳过修订：初审严重失败，需先人工补证据、调整搜索范围或重跑采集链路";
            }
            if (!reviewOutput.has("passed")) {
                return "跳过修订：评审输出不完整";
            }
            return reviewOutput.path("passed").asBoolean(false)
                    ? "初审已通过，无需修订"
                    : "初审未通过，应触发修订";
        }

        if ("rewrite_executed".equals(trigger)) {
            TaskNode rewriteNode = allNodes.stream()
                    .filter(current -> "rewrite_report".equals(current.getNodeName()))
                    .findFirst()
                    .orElse(null);
            if (rewriteNode == null) {
                return "跳过终审：未找到改写节点";
            }
            return switch (rewriteNode.getStatus()) {
                case SKIPPED -> "跳过终审：本轮未触发改写";
                case FAILED -> "跳过终审：改写执行失败";
                case PENDING, RUNNING, PAUSED -> "跳过终审：改写尚未完成";
                default -> "跳过终审：改写结果不可用";
            };
        }

        return "未满足节点执行条件";
    }

    private String resolveTrigger(String nodeConfig) {
        JsonNode config = readJson(nodeConfig);
        if (config == null) {
            return null;
        }
        String trigger = config.path("trigger").asText("");
        return trigger.isBlank() ? null : trigger;
    }

    /**
     * 当任务从人工阻断态恢复时，会在待改写节点配置里写入人工确认标记。
     * 只有显式确认过，系统才允许绕过“requiresHumanIntervention=true”的自动拦截继续执行改写。
     */
    private boolean isManualResumeApproved(String nodeConfig) {
        JsonNode config = readJson(nodeConfig);
        return config != null && config.path("manualResumeApproved").asBoolean(false);
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        try {
            return objectMapper.readTree(cleaned.trim());
        } catch (Exception e) {
            log.warn("failed to parse node output json", e);
            return null;
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 编排器收口阶段可能把任务归约为 SUCCESS / FAILED / STOPPED。
     * 一旦确定进入终态，就同步释放任务级并发占位，避免后续创建或重试被历史占位卡住。
     */
    private void releaseQuotaIfTaskReachedTerminalStatus(AnalysisTask task, AnalysisTaskStatus resolvedStatus) {
        if (task == null || resolvedStatus == null) {
            return;
        }
        if (resolvedStatus == AnalysisTaskStatus.SUCCESS
                || resolvedStatus == AnalysisTaskStatus.FAILED
                || resolvedStatus == AnalysisTaskStatus.STOPPED) {
            taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
        }
    }

    private void refreshNodeStates(Long taskId, List<TaskNode> nodes) {
        Map<Long, TaskNode> latestMap = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)
                .stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(TaskNode::getId, node -> node, (left, right) -> right));
        for (TaskNode node : nodes) {
            if (node.getId() == null) {
                continue;
            }
            TaskNode latest = latestMap.get(node.getId());
            if (latest == null) {
                continue;
            }
            syncNodeState(node, latest);
        }
    }

    /**
     * 节点对象会在调度线程、工作线程和轮询刷新之间反复传递。
     * 统一把数据库最新状态与 @Version 版本号回写到内存对象，才能避免后续继续拿旧版本对象去 merge。
     */
    private void syncNodeState(TaskNode target, TaskNode source) {
        if (target == null || source == null) {
            return;
        }
        target.setStatus(source.getStatus());
        target.setControlState(source.getControlState());
        target.setNodeConfig(source.getNodeConfig());
        target.setInputData(source.getInputData());
        target.setOutputData(source.getOutputData());
        target.setErrorMessage(source.getErrorMessage());
        target.setInterventionReason(source.getInterventionReason());
        target.setStartedAt(source.getStartedAt());
        target.setCompletedAt(source.getCompletedAt());
        target.setRetryCount(source.getRetryCount());
        target.setFailureCategory(source.getFailureCategory());
        target.setNextRetryAt(source.getNextRetryAt());
        target.setLastEventId(source.getLastEventId());
        target.setStateVersion(source.getStateVersion());
    }

    /**
     * 每次任务或节点状态发生关键流转时，都把最新快照刷入 Redis。
     * 这样后续的任务详情拉取、恢复判断和 SSE 接入都能基于统一快照工作。
     */
    /**
     * 节点执行完成后，统一补发节点状态、搜索进度、日志与诊断事件。
     * 这样前端既能刷新 DAG 视图，也能同步更新日志面板和诊断区域。
     */
    /**
     * Collector 事件留痕采用“结构化优先、最小兜底其次”策略。
     * 如果节点输出里已经带有完整 searchProgress / searchExecutionTrace，就原样透传；
     * 否则至少补一条可恢复的最小进度事件，保证断线重连后仍能知道该采集节点已经走到哪一步。
     */
    /**
     * Reviewer 输出中的诊断、问题与人工介入标记，会被压成一条可直接展示的诊断事件。
     */
    /**
     * 某些测试桩会直接 mock/spying Agent.execute，绕过 BaseAgent 的统一日志落库。
     * 为了不让 SSE 最小留痕依赖“日志一定先写库成功”这条前提，这里补一条节点级兜底输出事件。
     */
    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * Task 4.1 先约定最小可追溯语义：
     * 如果节点输出中已经带有 sourceUrls，就把它们同步带入内部工作流事件。
     * 更复杂的证据回指结构由后续 Task RAG 阶段继续扩展。
     */
    private List<String> extractSourceUrls(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return List.of();
        }
        JsonNode output;
        try {
            output = objectMapper.readTree(rawOutput);
        } catch (Exception ignored) {
            return List.of();
        }
        JsonNode sourceUrls = output.get("sourceUrls");
        if (sourceUrls == null || !sourceUrls.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(sourceUrls, new TypeReference<List<String>>() {
            });
        } catch (IllegalArgumentException e) {
            log.warn("failed to convert sourceUrls from workflow output", e);
            return List.of();
        }
    }

    private enum DependencyState {
        READY,
        WAITING,
        BLOCKED
    }

    private static final class DispatchCycleResult {

        private final boolean progressed;
        private final boolean skippedAny;
        private final int submittedCount;
        private final TaskNode lastTouchedNode;

        private DispatchCycleResult(boolean progressed, boolean skippedAny, int submittedCount, TaskNode lastTouchedNode) {
            this.progressed = progressed;
            this.skippedAny = skippedAny;
            this.submittedCount = submittedCount;
            this.lastTouchedNode = lastTouchedNode;
        }

        private boolean isProgressed() {
            return progressed;
        }

        private boolean isSkippedAny() {
            return skippedAny;
        }

        private int getSubmittedCount() {
            return submittedCount;
        }

        private TaskNode getLastTouchedNode() {
            return lastTouchedNode;
        }
    }

    private static final class NodeExecutionResult {

        private final TaskNode node;

        private NodeExecutionResult(TaskNode node) {
            this.node = node;
        }

        private TaskNode getNode() {
            return node;
        }
    }
}
