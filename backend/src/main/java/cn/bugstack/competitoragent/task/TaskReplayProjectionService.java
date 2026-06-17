package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem;
import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import cn.bugstack.competitoragent.model.dto.CollectionReplaySnapshotResponse;
import cn.bugstack.competitoragent.model.dto.RecoveryCheckpointResponse;
import cn.bugstack.competitoragent.model.dto.CollectorSelectedTargetSummary;
import cn.bugstack.competitoragent.model.dto.ReplayNodeSummary;
import cn.bugstack.competitoragent.model.dto.ReplayPlanVersionSummary;
import cn.bugstack.competitoragent.model.dto.ReplayTimelineEvent;
import cn.bugstack.competitoragent.model.dto.SearchReplaySnapshotResponse;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryAdvice;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.search.SearchReplayTimelineItem;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务回放统一投影服务。
 * <p>
 * 该服务负责把分散在任务计划、工作流事件、节点尝试、记忆快照、Agent 日志与恢复点中的数据，
 * 汇总成单个正式回放视图，供任务详情页和后续恢复接口复用。
 */
@Service
public class TaskReplayProjectionService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final TaskPlanRepository taskPlanRepository;
    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository;
    private final MemorySnapshotRepository memorySnapshotRepository;
    private final AgentExecutionLogRepository agentExecutionLogRepository;
    private final RecoveryCheckpointService recoveryCheckpointService;
    private final TaskRecoveryService taskRecoveryService;
    private final ObjectMapper objectMapper;

    public TaskReplayProjectionService(TaskPlanRepository taskPlanRepository,
                                       TaskWorkflowEventRepository taskWorkflowEventRepository,
                                       TaskNodeRepository taskNodeRepository,
                                       TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository,
                                       MemorySnapshotRepository memorySnapshotRepository,
                                       AgentExecutionLogRepository agentExecutionLogRepository,
                                       RecoveryCheckpointService recoveryCheckpointService,
                                       TaskRecoveryService taskRecoveryService,
                                       ObjectMapper objectMapper) {
        this.taskPlanRepository = taskPlanRepository;
        this.taskWorkflowEventRepository = taskWorkflowEventRepository;
        this.taskNodeRepository = taskNodeRepository;
        this.taskNodeExecutionAttemptRepository = taskNodeExecutionAttemptRepository;
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.agentExecutionLogRepository = agentExecutionLogRepository;
        this.recoveryCheckpointService = recoveryCheckpointService;
        this.taskRecoveryService = taskRecoveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按任务构建正式回放视图。
     * <p>
     * 5.6.b 先收敛到“时间线 + 节点摘要 + 恢复建议”三个主对象，
     * 不把底层事件游标、原始 payload 或执行控制语义直接暴露给调用方。
     */
    @Transactional(readOnly = true)
    public TaskReplayResponse getTaskReplay(Long taskId) {
        List<TaskPlan> planVersions = taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(taskId);
        Optional<TaskPlan> activePlanOptional = taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(taskId);
        List<TaskWorkflowEvent> workflowEvents = taskWorkflowEventRepository.findAll().stream()
                .filter(event -> Objects.equals(taskId, event.getTaskId()))
                .sorted(Comparator.comparing(TaskWorkflowEvent::getCreatedAt, this::compareNullableTime))
                .toList();
        List<TaskNode> taskNodes = taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        List<TaskNodeExecutionAttempt> executionAttempts = taskNodeExecutionAttemptRepository.findAll().stream()
                .filter(attempt -> Objects.equals(taskId, attempt.getTaskId()))
                .sorted(Comparator.comparing(TaskNodeExecutionAttempt::getCreatedAt, this::compareNullableTime)
                        .thenComparing(TaskNodeExecutionAttempt::getAttemptNo))
                .toList();
        List<MemorySnapshot> memorySnapshots = memorySnapshotRepository.findByTaskIdOrderByIdDesc(taskId).stream()
                .sorted(Comparator.comparing(MemorySnapshot::getCreatedAt, this::compareNullableTime))
                .toList();
        List<AgentExecutionLog> executionLogs = agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        List<RecoveryCheckpointResponse> checkpoints = recoveryCheckpointService.listTaskCheckpoints(taskId);

        Map<Long, TaskPlan> taskPlanMap = new LinkedHashMap<>();
        for (TaskPlan taskPlan : planVersions) {
            taskPlanMap.put(taskPlan.getId(), taskPlan);
        }

        List<ReplayTimelineEvent> timeline = buildTimeline(
                workflowEvents,
                executionAttempts,
                memorySnapshots,
                executionLogs,
                taskPlanMap);
        List<ReplayPlanVersionSummary> replayPlanVersions = buildPlanVersionSummaries(planVersions, timeline, checkpoints);
        List<ReplayNodeSummary> nodeSummaries = buildNodeSummaries(taskNodes, executionAttempts, memorySnapshots, checkpoints);
        TaskRecoveryAdvice recoveryAdvice = taskRecoveryService.buildRecoveryAdvice(taskId);
        List<SearchReplaySnapshotResponse> searchReplays = buildSearchReplays(taskNodes, taskPlanMap);
        List<CollectionReplaySnapshotResponse> collectionReplays = buildCollectionReplays(taskNodes, taskPlanMap);
        List<String> aggregatedSourceUrls = aggregateReplaySourceUrls(
                timeline,
                nodeSummaries,
                recoveryAdvice,
                checkpoints,
                searchReplays,
                collectionReplays);

        return TaskReplayResponse.builder()
                .taskId(taskId)
                .currentPlanVersionId(activePlanOptional.map(TaskPlan::getId).orElse(null))
                .timeline(timeline)
                .nodeSummaries(nodeSummaries)
                .recoveryAdvice(recoveryAdvice)
                .recoveryCheckpoints(checkpoints)
                .planVersions(replayPlanVersions)
                .searchReplays(searchReplays)
                .collectionReplays(collectionReplays)
                .integrationEntryPoints(buildIntegrationEntryPoints(aggregatedSourceUrls))
                .sourceUrls(aggregatedSourceUrls)
                .build();
    }

    /**
     * 时间线统一只保留“人可以读懂的摘要对象”。
     * 各类底层对象都在这里转成统一字段，避免前端再按来源类型写多套解析逻辑。
     */
    private List<ReplayTimelineEvent> buildTimeline(List<TaskWorkflowEvent> workflowEvents,
                                                    List<TaskNodeExecutionAttempt> executionAttempts,
                                                    List<MemorySnapshot> memorySnapshots,
                                                    List<AgentExecutionLog> executionLogs,
                                                    Map<Long, TaskPlan> taskPlanMap) {
        List<ReplayTimelineEvent> timeline = new ArrayList<>();
        for (TaskWorkflowEvent workflowEvent : workflowEvents) {
            timeline.add(ReplayTimelineEvent.builder()
                    .eventId(workflowEvent.getEventId())
                    .taskId(workflowEvent.getTaskId())
                    .planVersionId(workflowEvent.getPlanVersionId())
                    .planVersion(resolvePlanVersion(taskPlanMap, workflowEvent.getPlanVersionId()))
                    .branchKey(workflowEvent.getBranchKey())
                    .nodeName(workflowEvent.getNodeName())
                    .eventType(workflowEvent.getEventType() == null ? "WORKFLOW_EVENT" : workflowEvent.getEventType().name())
                    .summary(resolveWorkflowEventSummary(workflowEvent))
                    .occurredAt(workflowEvent.getCreatedAt())
                    .sourceUrls(parseJsonStringList(workflowEvent.getSourceUrls()))
                    .build());
        }
        for (TaskNodeExecutionAttempt executionAttempt : executionAttempts) {
            timeline.add(ReplayTimelineEvent.builder()
                    .eventId("attempt-" + executionAttempt.getId())
                    .taskId(executionAttempt.getTaskId())
                    .nodeName(executionAttempt.getNodeName())
                    .eventType("NODE_ATTEMPT")
                    .summary(resolveAttemptSummary(executionAttempt))
                    .occurredAt(executionAttempt.getCreatedAt())
                    .sourceUrls(List.of())
                    .build());
        }
        for (MemorySnapshot memorySnapshot : memorySnapshots) {
            timeline.add(ReplayTimelineEvent.builder()
                    .eventId("memory-" + memorySnapshot.getId())
                    .taskId(memorySnapshot.getTaskId())
                    .planVersionId(memorySnapshot.getPlanVersionId())
                    .planVersion(resolvePlanVersion(taskPlanMap, memorySnapshot.getPlanVersionId()))
                    .branchKey(memorySnapshot.getBranchKey())
                    .nodeName(memorySnapshot.getNodeName())
                    .eventType("MEMORY_SNAPSHOT")
                    .summary(resolveMemorySummary(memorySnapshot))
                    .occurredAt(memorySnapshot.getCreatedAt())
                    .sourceUrls(normalizeSourceUrls(memorySnapshot.getSourceUrls()))
                    .build());
        }
        for (AgentExecutionLog executionLog : executionLogs) {
            timeline.add(ReplayTimelineEvent.builder()
                    .eventId("log-" + executionLog.getId())
                    .taskId(executionLog.getTaskId())
                    .nodeName(resolveLogNodeName(executionLog))
                    .eventType("AGENT_LOG")
                    .summary(resolveLogSummary(executionLog))
                    .occurredAt(executionLog.getCreatedAt())
                    .sourceUrls(List.of())
                    .build());
        }
        timeline.sort(Comparator.comparing(ReplayTimelineEvent::getOccurredAt, this::compareNullableTime)
                .thenComparing(ReplayTimelineEvent::getEventId, Comparator.nullsLast(String::compareTo)));
        return timeline;
    }

    /**
     * 计划版本摘要在 5.6.b 先补齐版本切换所需的最小字段，
     * 同时把与该版本相关的 sourceUrls 汇总出来，避免回放视图失去追溯入口。
     */
    private List<ReplayPlanVersionSummary> buildPlanVersionSummaries(List<TaskPlan> planVersions,
                                                                     List<ReplayTimelineEvent> timeline,
                                                                     List<RecoveryCheckpointResponse> checkpoints) {
        List<ReplayPlanVersionSummary> summaries = new ArrayList<>();
        for (TaskPlan planVersion : planVersions) {
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            for (ReplayTimelineEvent timelineEvent : timeline) {
                if (Objects.equals(planVersion.getId(), timelineEvent.getPlanVersionId())) {
                    sourceUrls.addAll(normalizeSourceUrls(timelineEvent.getSourceUrls()));
                }
            }
            for (RecoveryCheckpointResponse checkpoint : checkpoints) {
                if (Objects.equals(planVersion.getId(), checkpoint.getPlanVersionId())) {
                    sourceUrls.addAll(normalizeSourceUrls(checkpoint.getSourceUrls()));
                }
            }
            summaries.add(ReplayPlanVersionSummary.builder()
                    .planVersionId(planVersion.getId())
                    .planVersion(planVersion.getPlanVersion())
                    .parentPlanId(planVersion.getParentPlanId())
                    .branchKey(planVersion.getBranchKey())
                    .planType(planVersion.getPlanType())
                    .triggerNodeName(planVersion.getTriggerNodeName())
                    .active(planVersion.isActive())
                    .createdAt(planVersion.getCreatedAt())
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .build());
        }
        return summaries;
    }

    /**
     * 节点摘要会把节点实体、最近尝试、记忆快照和恢复点并排投影到一个对象里，
     * 这样任务详情页无需再自己判断“问题来自哪个底层表”。
     */
    private List<ReplayNodeSummary> buildNodeSummaries(List<TaskNode> taskNodes,
                                                       List<TaskNodeExecutionAttempt> executionAttempts,
                                                       List<MemorySnapshot> memorySnapshots,
                                                       List<RecoveryCheckpointResponse> checkpoints) {
        Map<Long, TaskNodeExecutionAttempt> latestAttemptMap = new LinkedHashMap<>();
        for (TaskNodeExecutionAttempt executionAttempt : executionAttempts) {
            TaskNodeExecutionAttempt current = latestAttemptMap.get(executionAttempt.getNodeId());
            if (current == null || executionAttempt.getAttemptNo() >= current.getAttemptNo()) {
                latestAttemptMap.put(executionAttempt.getNodeId(), executionAttempt);
            }
        }
        Map<String, RecoveryCheckpointResponse> checkpointMap = new LinkedHashMap<>();
        for (RecoveryCheckpointResponse checkpoint : checkpoints) {
            checkpointMap.putIfAbsent(checkpoint.getNodeName(), checkpoint);
        }
        Map<String, MemorySnapshot> memorySnapshotMap = new LinkedHashMap<>();
        for (MemorySnapshot memorySnapshot : memorySnapshots) {
            memorySnapshotMap.put(memorySnapshot.getNodeName(), memorySnapshot);
        }

        List<ReplayNodeSummary> summaries = new ArrayList<>();
        for (TaskNode taskNode : taskNodes) {
            TaskNodeExecutionAttempt latestAttempt = latestAttemptMap.get(taskNode.getId());
            RecoveryCheckpointResponse checkpoint = checkpointMap.get(taskNode.getNodeName());
            MemorySnapshot memorySnapshot = memorySnapshotMap.get(taskNode.getNodeName());
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            if (memorySnapshot != null) {
                sourceUrls.addAll(normalizeSourceUrls(memorySnapshot.getSourceUrls()));
            }
            if (checkpoint != null) {
                sourceUrls.addAll(normalizeSourceUrls(checkpoint.getSourceUrls()));
            }
            summaries.add(ReplayNodeSummary.builder()
                    .nodeId(taskNode.getId())
                    .nodeName(taskNode.getNodeName())
                    .displayName(taskNode.getDisplayName())
                    .status(taskNode.getStatus() == null ? null : taskNode.getStatus().name())
                    .planVersionId(taskNode.getPlanVersionId())
                    .branchKey(taskNode.getBranchKey())
                    .latestAttemptNo(latestAttempt == null ? null : latestAttempt.getAttemptNo())
                    .failureCategory(resolveFailureCategory(taskNode, latestAttempt))
                    .issueSummary(resolveNodeIssueSummary(taskNode, latestAttempt, memorySnapshot))
                    .recoveryHint(resolveNodeRecoveryHint(taskNode, checkpoint))
                    .checkpointSummary(checkpoint == null ? null : checkpoint.getSummary())
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .build());
        }
        return summaries;
    }

    /**
     * 任务级恢复建议优先强调“当前推荐怎么做”。
     * 5.6.c 才会进一步补齐严格的恢复窗口、释放规则和控制语义。
     */
    private TaskRecoveryAdvice buildRecoveryAdvice(List<TaskNode> taskNodes,
                                                   List<RecoveryCheckpointResponse> checkpoints) {
        List<String> manualBlockingNodes = taskNodes.stream()
                .filter(node -> node.getStatus() == cn.bugstack.competitoragent.model.enums.TaskNodeStatus.WAITING_INTERVENTION
                        || (node.getFailureCategory() != null && node.getFailureCategory().isRequiresManualIntervention()))
                .map(TaskNode::getNodeName)
                .toList();
        List<String> waitingRetryNodes = taskNodes.stream()
                .filter(node -> node.getStatus() == cn.bugstack.competitoragent.model.enums.TaskNodeStatus.WAITING_RETRY)
                .map(TaskNode::getNodeName)
                .toList();
        List<String> failedNodes = taskNodes.stream()
                .filter(node -> node.getStatus() == cn.bugstack.competitoragent.model.enums.TaskNodeStatus.FAILED)
                .map(TaskNode::getNodeName)
                .toList();
        RecoveryCheckpointResponse preferredCheckpoint = checkpoints.isEmpty() ? null : checkpoints.get(0);
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (preferredCheckpoint != null) {
            sourceUrls.addAll(normalizeSourceUrls(preferredCheckpoint.getSourceUrls()));
        }

        if (!manualBlockingNodes.isEmpty()) {
            return TaskRecoveryAdvice.builder()
                    .recommendedAction("MANUAL_INTERVENTION")
                    .summary("存在等待人工处理的节点，建议先完成人工判断，再决定是否继续恢复执行。")
                    .blockingNodeNames(manualBlockingNodes)
                    .recommendedCheckpointId(preferredCheckpoint == null ? null : preferredCheckpoint.getId())
                    .recommendedCheckpointKey(preferredCheckpoint == null ? null : preferredCheckpoint.getCheckpointKey())
                    .resumeSupported(preferredCheckpoint != null)
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .build();
        }
        if (!waitingRetryNodes.isEmpty()) {
            return TaskRecoveryAdvice.builder()
                    .recommendedAction("WAIT_FOR_RETRY")
                    .summary("当前已有节点进入自动重试窗口，建议先观察自动恢复结果。")
                    .blockingNodeNames(waitingRetryNodes)
                    .recommendedCheckpointId(preferredCheckpoint == null ? null : preferredCheckpoint.getId())
                    .recommendedCheckpointKey(preferredCheckpoint == null ? null : preferredCheckpoint.getCheckpointKey())
                    .resumeSupported(preferredCheckpoint != null)
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .build();
        }
        if (preferredCheckpoint != null) {
            return TaskRecoveryAdvice.builder()
                    .recommendedAction("RESUME_FROM_CHECKPOINT")
                    .summary("当前可基于最近恢复点继续执行，无需重新回放全部已完成节点。")
                    .blockingNodeNames(List.of())
                    .recommendedCheckpointId(preferredCheckpoint.getId())
                    .recommendedCheckpointKey(preferredCheckpoint.getCheckpointKey())
                    .resumeSupported(true)
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .build();
        }
        if (!failedNodes.isEmpty()) {
            return TaskRecoveryAdvice.builder()
                    .recommendedAction("FULL_RETRY")
                    .summary("当前缺少可复用恢复点，建议在确认失败原因后执行整任务重试。")
                    .blockingNodeNames(failedNodes)
                    .recommendedCheckpointId(null)
                    .recommendedCheckpointKey(null)
                    .resumeSupported(false)
                    .sourceUrls(List.of())
                    .build();
        }
        return TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("当前任务暂无额外恢复动作，建议继续观察任务主链路。")
                .blockingNodeNames(List.of())
                .recommendedCheckpointId(null)
                .recommendedCheckpointKey(null)
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build();
    }

    /**
     * 统一汇总回放主视图的 sourceUrls。
     * 保持时间线优先的顺序，方便调用方先看到“事件为什么发生”的证据来源。
     */
    private List<String> aggregateReplaySourceUrls(List<ReplayTimelineEvent> timeline,
                                                   List<ReplayNodeSummary> nodeSummaries,
                                                   TaskRecoveryAdvice recoveryAdvice,
                                                   List<RecoveryCheckpointResponse> checkpoints,
                                                   List<SearchReplaySnapshotResponse> searchReplays,
                                                   List<CollectionReplaySnapshotResponse> collectionReplays) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (ReplayTimelineEvent timelineEvent : timeline) {
            sourceUrls.addAll(normalizeSourceUrls(timelineEvent.getSourceUrls()));
        }
        for (ReplayNodeSummary nodeSummary : nodeSummaries) {
            sourceUrls.addAll(normalizeSourceUrls(nodeSummary.getSourceUrls()));
        }
        if (recoveryAdvice != null) {
            sourceUrls.addAll(normalizeSourceUrls(recoveryAdvice.getSourceUrls()));
        }
        for (RecoveryCheckpointResponse checkpoint : checkpoints) {
            sourceUrls.addAll(normalizeSourceUrls(checkpoint.getSourceUrls()));
        }
        for (SearchReplaySnapshotResponse searchReplay : searchReplays) {
            sourceUrls.addAll(normalizeSourceUrls(searchReplay.getSourceUrls()));
        }
        for (CollectionReplaySnapshotResponse collectionReplay : collectionReplays) {
            sourceUrls.addAll(normalizeSourceUrls(collectionReplay.getSourceUrls()));
        }
        return new ArrayList<>(sourceUrls);
    }

    /**
     * 对话确认与正式导出都还会在后续任务中落地真实回放对象，
     * 这里先把接入点边界正式化，避免未来能力接入时再次改动主响应协议。
     */
    private List<SearchReplaySnapshotResponse> buildSearchReplays(List<TaskNode> taskNodes,
                                                                  Map<Long, TaskPlan> taskPlanMap) {
        List<SearchReplaySnapshotResponse> searchReplays = new ArrayList<>();
        for (TaskNode taskNode : taskNodes) {
            if (taskNode == null || taskNode.getAgentType() != AgentType.COLLECTOR) {
                continue;
            }
            JsonNode output = readJson(taskNode.getOutputData());
            if (output == null) {
                continue;
            }

            SearchAuditSnapshot searchAudit = convertValue(output.get("searchAudit"), SearchAuditSnapshot.class);
            List<CollectorSelectedTargetSummary> selectedTargets = convertList(
                    output.get("selectedTargets"),
                    new TypeReference<List<CollectorSelectedTargetSummary>>() {
                    });
            SearchProgressSnapshot latestProgress = convertValue(output.get("searchProgress"), SearchProgressSnapshot.class);
            List<String> sourceUrls = normalizeSourceUrls(readStringList(output.get("sourceUrls")));
            if (searchAudit != null && (searchAudit.getSourceUrls() == null || searchAudit.getSourceUrls().isEmpty())) {
                searchAudit.setSourceUrls(sourceUrls);
            }
            if (sourceUrls.isEmpty() && searchAudit != null) {
                sourceUrls = normalizeSourceUrls(searchAudit.getSourceUrls());
            }
            List<SearchCollectionTarget> attemptedTargets = resolveSearchAuditList(
                    searchAudit == null ? null : searchAudit.getAttemptedTargets(),
                    output.get("attemptedTargets"),
                    new TypeReference<List<SearchCollectionTarget>>() {
                    });
            List<SourceCandidate> discardedCandidates = resolveSearchAuditList(
                    searchAudit == null ? null : searchAudit.getDiscardedCandidates(),
                    output.get("discardedCandidates"),
                    new TypeReference<List<SourceCandidate>>() {
                    });
            List<SearchReplayTimelineItem> replayTimeline = normalizeReplayTimeline(resolveReplayTimeline(output, searchAudit), sourceUrls);
            if (searchAudit == null
                    && selectedTargets.isEmpty()
                    && sourceUrls.isEmpty()
                    && attemptedTargets.isEmpty()
                    && discardedCandidates.isEmpty()
                    && replayTimeline.isEmpty()) {
                continue;
            }

            searchReplays.add(SearchReplaySnapshotResponse.builder()
                    .nodeName(taskNode.getNodeName())
                    .planVersionId(taskNode.getPlanVersionId())
                    .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
                    .branchKey(taskNode.getBranchKey())
                    .latestProgress(latestProgress)
                    .timeline(replayTimeline)
                    .searchAudit(searchAudit)
                    .searchAuditSummary(SearchAuditSummary.from(searchAudit))
                    .attemptedTargets(attemptedTargets)
                    .discardedCandidates(discardedCandidates)
                    .selectedTargets(selectedTargets)
                    .sourceUrls(sourceUrls)
                    .build());
        }
        return searchReplays;
    }

    /**
     * collection 回放投影复用 searchReplay 的思路，但只承载 package 级采集语义。
     * 这样 replay / insight / checkpoint 都能消费同一份正式 collectionAudit，而不是各自从 outputData 反推。
     */
    private List<CollectionReplaySnapshotResponse> buildCollectionReplays(List<TaskNode> taskNodes,
                                                                          Map<Long, TaskPlan> taskPlanMap) {
        List<CollectionReplaySnapshotResponse> collectionReplays = new ArrayList<>();
        for (TaskNode taskNode : taskNodes) {
            if (taskNode == null || taskNode.getAgentType() != AgentType.COLLECTOR) {
                continue;
            }
            JsonNode output = readJson(taskNode.getOutputData());
            if (output == null) {
                continue;
            }

            CollectionAuditSnapshot collectionAudit = convertValue(output.get("collectionAudit"), CollectionAuditSnapshot.class);
            LinkedHashSet<String> mergedSourceUrls = new LinkedHashSet<>(normalizeSourceUrls(readStringList(output.get("sourceUrls"))));
            if (collectionAudit != null) {
                mergedSourceUrls.addAll(normalizeSourceUrls(collectionAudit.getSourceUrls()));
                if (collectionAudit.getSourceUrls() == null || collectionAudit.getSourceUrls().isEmpty()) {
                    collectionAudit.setSourceUrls(new ArrayList<>(mergedSourceUrls));
                }
            }
            List<String> sourceUrls = new ArrayList<>(mergedSourceUrls);
            List<CollectionReplayTimelineItem> timeline = collectionAudit == null || collectionAudit.getReplayTimeline() == null
                    ? convertList(output.get("collectionReplayTimeline"),
                    new TypeReference<List<CollectionReplayTimelineItem>>() {
                    })
                    : collectionAudit.getReplayTimeline();
            String collectionStatus = textValue(firstPresent(output.get("collectionStatus"),
                    collectionAudit == null ? null : objectMapper.valueToTree(collectionAudit.getStatus())));

            if (collectionAudit == null
                    && timeline.isEmpty()
                    && sourceUrls.isEmpty()
                    && !hasText(collectionStatus)) {
                continue;
            }

            CollectionAuditSummary collectionAuditSummary = resolveCollectionAuditSummary(collectionAudit);
            collectionReplays.add(CollectionReplaySnapshotResponse.builder()
                    .nodeName(taskNode.getNodeName())
                    .planVersionId(taskNode.getPlanVersionId())
                    .planVersion(resolvePlanVersion(taskPlanMap, taskNode.getPlanVersionId()))
                    .branchKey(taskNode.getBranchKey())
                    .collectionStatus(hasText(collectionStatus)
                            ? collectionStatus
                            : (collectionAudit == null ? null : collectionAudit.getStatus()))
                    .collectionAudit(collectionAudit)
                    .collectionAuditSummary(collectionAuditSummary)
                    .timeline(timeline)
                    .sourceUrls(sourceUrls)
                    .build());
        }
        return collectionReplays;
    }

    /**
     * replay 响应优先消费 searchAudit 的正式事实源，历史 output 顶层字段作为兼容兜底。
     * 这样既满足第二轮 DTO 顶层字段契约，也避免破坏已有 searchAudit 嵌套消费方。
     */
    private <T> List<T> resolveSearchAuditList(List<T> auditValues, JsonNode fallbackNode, TypeReference<List<T>> typeReference) {
        if (auditValues != null) {
            return auditValues;
        }
        return convertList(fallbackNode, typeReference);
    }

    private List<SearchReplayTimelineItem> resolveReplayTimeline(JsonNode output, SearchAuditSnapshot searchAudit) {
        if (searchAudit != null && searchAudit.getReplayTimeline() != null) {
            return searchAudit.getReplayTimeline();
        }
        JsonNode replayTimelineNode = firstPresent(output.get("searchReplayTimeline"), output.get("replayTimeline"));
        return convertList(replayTimelineNode, new TypeReference<List<SearchReplayTimelineItem>>() {
        });
    }

    /**
     * 回放时间线必须显式带 sourceUrls。
     * 历史节点可能已经有 replayTimeline 但条目缺少来源，这里用节点级来源兜底补齐。
     */
    private List<SearchReplayTimelineItem> normalizeReplayTimeline(List<SearchReplayTimelineItem> replayTimeline,
                                                                   List<String> fallbackSourceUrls) {
        if (replayTimeline == null || replayTimeline.isEmpty()) {
            return List.of();
        }
        List<String> stableFallbackSourceUrls = normalizeSourceUrls(fallbackSourceUrls);
        return replayTimeline.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    List<String> itemSourceUrls = normalizeSourceUrls(item.getSourceUrls());
                    if (!itemSourceUrls.isEmpty() || stableFallbackSourceUrls.isEmpty()) {
                        return item;
                    }
                    return SearchReplayTimelineItem.builder()
                            .stepCode(item.getStepCode())
                            .stepName(item.getStepName())
                            .status(item.getStatus())
                            .message(item.getMessage())
                            .completedSteps(item.getCompletedSteps())
                            .totalSteps(item.getTotalSteps())
                            .progressPercent(item.getProgressPercent())
                            .candidateCount(item.getCandidateCount())
                            .attemptedCount(item.getAttemptedCount())
                            .selectedCount(item.getSelectedCount())
                            .discardedCount(item.getDiscardedCount())
                            .degraded(item.getDegraded())
                            .degradationReason(item.getDegradationReason())
                            .sourceUrls(stableFallbackSourceUrls)
                            .updatedAt(item.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    private List<cn.bugstack.competitoragent.model.dto.ReplayIntegrationEntryPoint> buildIntegrationEntryPoints(List<String> sourceUrls) {
        List<String> stableSourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        return List.of(
                cn.bugstack.competitoragent.model.dto.ReplayIntegrationEntryPoint.builder()
                        .entryKey("CONVERSATION_ACTION_REPLAY")
                        .readinessStatus("RESERVED_FOR_TASK_5_9")
                        .targetTaskKey("Task 5.9")
                        .summary("预留给 Task 5.9 接入对话确认、动作预览与执行结果回放，当前阶段只锁定任务 / 节点 / 审计主链路。")
                        .sourceUrls(stableSourceUrls)
                        .build(),
                cn.bugstack.competitoragent.model.dto.ReplayIntegrationEntryPoint.builder()
                        .entryKey("REPORT_EXPORT_REPLAY")
                        .readinessStatus("RESERVED_FOR_TASK_5_7")
                        .targetTaskKey("Task 5.7")
                        .summary("预留给 Task 5.7 接入正式导出记录回放，当前阶段不直接承载导出历史。")
                        .sourceUrls(stableSourceUrls)
                        .build()
        );
    }

    private Integer resolvePlanVersion(Map<Long, TaskPlan> taskPlanMap, Long planVersionId) {
        TaskPlan taskPlan = taskPlanMap.get(planVersionId);
        return taskPlan == null ? null : taskPlan.getPlanVersion();
    }

    /**
     * 工作流事件 payload 可能是结构化 JSON，也可能只是历史文本。
     * 因此这里先尝试提取 summary，再退化到 tag / topic，保证回放接口始终有可读摘要。
     */
    private String resolveWorkflowEventSummary(TaskWorkflowEvent workflowEvent) {
        if (workflowEvent == null) {
            return "未知工作流事件";
        }
        if (workflowEvent.getPayload() != null && !workflowEvent.getPayload().isBlank()) {
            try {
                JsonNode payloadNode = objectMapper.readTree(workflowEvent.getPayload());
                if (payloadNode.hasNonNull("summary")) {
                    return payloadNode.get("summary").asText();
                }
                if (payloadNode.hasNonNull("message")) {
                    return payloadNode.get("message").asText();
                }
            } catch (Exception ignored) {
                // 历史 payload 可能不是标准 JSON，继续回退到 tag / topic 摘要。
            }
        }
        if (workflowEvent.getTag() != null && !workflowEvent.getTag().isBlank()) {
            return workflowEvent.getTag();
        }
        return workflowEvent.getTopic();
    }

    private String resolveAttemptSummary(TaskNodeExecutionAttempt executionAttempt) {
        if (executionAttempt.getErrorSummary() != null && !executionAttempt.getErrorSummary().isBlank()) {
            return executionAttempt.getErrorSummary();
        }
        return "节点 " + executionAttempt.getNodeName() + " 第 " + executionAttempt.getAttemptNo() + " 次执行尝试";
    }

    private String resolveMemorySummary(MemorySnapshot memorySnapshot) {
        if (memorySnapshot.getGapSummary() != null && !memorySnapshot.getGapSummary().isBlank()) {
            return memorySnapshot.getGapSummary();
        }
        if (memorySnapshot.getSummary() != null && !memorySnapshot.getSummary().isBlank()) {
            return memorySnapshot.getSummary();
        }
        return "节点 " + memorySnapshot.getNodeName() + " 的记忆快照";
    }

    private String resolveLogNodeName(AgentExecutionLog executionLog) {
        if (executionLog.getNodeId() != null) {
            return "node-" + executionLog.getNodeId();
        }
        return executionLog.getAgentName();
    }

    private String resolveLogSummary(AgentExecutionLog executionLog) {
        if (executionLog.getReasoningSummary() != null && !executionLog.getReasoningSummary().isBlank()) {
            return executionLog.getReasoningSummary();
        }
        if (executionLog.getErrorMessage() != null && !executionLog.getErrorMessage().isBlank()) {
            return executionLog.getErrorMessage();
        }
        return executionLog.getAgentName() + " 执行日志";
    }

    private String resolveFailureCategory(TaskNode taskNode, TaskNodeExecutionAttempt latestAttempt) {
        if (latestAttempt != null && latestAttempt.getFailureCategory() != null) {
            return latestAttempt.getFailureCategory().name();
        }
        if (taskNode.getFailureCategory() != null) {
            return taskNode.getFailureCategory().name();
        }
        return null;
    }

    private String resolveNodeIssueSummary(TaskNode taskNode,
                                           TaskNodeExecutionAttempt latestAttempt,
                                           MemorySnapshot memorySnapshot) {
        if (latestAttempt != null && latestAttempt.getErrorSummary() != null && !latestAttempt.getErrorSummary().isBlank()) {
            return latestAttempt.getErrorSummary();
        }
        if (taskNode.getErrorMessage() != null && !taskNode.getErrorMessage().isBlank()) {
            return taskNode.getErrorMessage();
        }
        if (memorySnapshot != null && memorySnapshot.getGapSummary() != null && !memorySnapshot.getGapSummary().isBlank()) {
            return memorySnapshot.getGapSummary();
        }
        if (memorySnapshot != null && memorySnapshot.getSummary() != null && !memorySnapshot.getSummary().isBlank()) {
            return memorySnapshot.getSummary();
        }
        return "暂无额外问题摘要";
    }

    private String resolveNodeRecoveryHint(TaskNode taskNode, RecoveryCheckpointResponse checkpoint) {
        if (taskNode.getFailureCategory() != null) {
            return taskNode.getFailureCategory().getDefaultUserSummary();
        }
        if (checkpoint != null && checkpoint.getSummary() != null && !checkpoint.getSummary().isBlank()) {
            return checkpoint.getSummary();
        }
        return "当前暂无额外恢复提示";
    }

    private List<String> parseJsonStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return normalizeSourceUrls(objectMapper.readValue(rawJson, STRING_LIST_TYPE));
        } catch (Exception ignored) {
            return List.of(rawJson);
        }
    }

    private JsonNode readJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private <T> T convertValue(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, type);
    }

    private <T> List<T> convertList(JsonNode node, TypeReference<List<T>> typeReference) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, typeReference);
    }

    private JsonNode firstPresent(JsonNode primary, JsonNode fallback) {
        return primary == null || primary.isNull() ? fallback : primary;
    }

    private CollectionAuditSummary resolveCollectionAuditSummary(CollectionAuditSnapshot collectionAudit) {
        if (collectionAudit == null) {
            return null;
        }
        if (collectionAudit.getSummary() != null) {
            return collectionAudit.getSummary();
        }
        return CollectionAuditSummary.from(collectionAudit);
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    normalized.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
