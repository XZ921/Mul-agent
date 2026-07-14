package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrator 运行时持久化计数的唯一读取与递增 owner。
 */
@Service
public class OrchestrationRuntimeStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationRuntimeStateService.class);

    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;

    public OrchestrationRuntimeStateService(TaskWorkflowEventRepository taskWorkflowEventRepository,
                                            TaskPlanRepository taskPlanRepository,
                                            ObjectMapper objectMapper) {
        this.taskWorkflowEventRepository = taskWorkflowEventRepository;
        this.taskPlanRepository = taskPlanRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 分别读取最新 checkpoint 与活动计划。checkpoint 不存在是合法首轮状态；
     * 只要仓储访问、JSON 结构或计数语义不可靠，就显式返回 UNREADABLE，禁止伪装成 count=0。
     */
    public OrchestrationRuntimeState load(Long taskId) {
        CheckpointSnapshot checkpointSnapshot = loadCheckpoint(taskId);
        PlanSnapshot planSnapshot = loadActivePlan(taskId);
        OrchestrationRuntimeState.CheckpointStateStatus status = checkpointSnapshot.status();
        if (!planSnapshot.readable()) {
            status = OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE;
        }
        return new OrchestrationRuntimeState(
                checkpointSnapshot.decisionCount(),
                checkpointSnapshot.sectionCounts(),
                planSnapshot.planVersionId(),
                planSnapshot.nextPlanVersion(),
                status,
                checkpointSnapshot.sourceUrls());
    }

    /**
     * 仅在 APPEND_NODES 对应的计划和节点都成功落库后调用本方法。
     * Policy 拒绝、确认暂停、空节点或持久化失败均不得提前递增该状态。
     */
    public OrchestrationRuntimeState afterSuccessfulBranch(OrchestrationRuntimeState current,
                                                            OrchestrationDecision decision) {
        if (current == null || decision == null) {
            throw new IllegalArgumentException("current runtime state 与 decision 不能为空");
        }
        OrchestrationDecision normalizedDecision = decision.normalized();
        Map<String, Integer> nextCounts = new LinkedHashMap<>(current.dynamicBranchCountsBySection());
        String sectionKey = OrchestrationRuntimeState.normalizeSectionKey(normalizedDecision.getTargetSection());
        nextCounts.put(sectionKey, incrementSafely(nextCounts.getOrDefault(sectionKey, 0)));

        List<String> mergedSourceUrls = new ArrayList<>(current.sourceUrls());
        mergedSourceUrls.addAll(normalizedDecision.getSourceUrls());
        return new OrchestrationRuntimeState(
                incrementSafely(current.currentDecisionCount()),
                nextCounts,
                current.currentPlanVersionId(),
                current.nextPlanVersion(),
                current.checkpointStateStatus(),
                mergedSourceUrls);
    }

    private CheckpointSnapshot loadCheckpoint(Long taskId) {
        if (taskId == null) {
            return CheckpointSnapshot.absent();
        }
        try {
            Optional<TaskWorkflowEvent> event = taskWorkflowEventRepository
                    .findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                            taskId,
                            WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED);
            if (event.isEmpty()) {
                return CheckpointSnapshot.absent();
            }
            return parseCheckpoint(taskId, event.get());
        } catch (RuntimeException exception) {
            warnUnreadable(taskId, "checkpoint 仓储访问失败", exception);
            return CheckpointSnapshot.unreadable();
        }
    }

    private CheckpointSnapshot parseCheckpoint(Long taskId, TaskWorkflowEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            warnUnreadable(taskId, "checkpoint payload 为空", null);
            return CheckpointSnapshot.unreadable();
        }
        try {
            JsonNode checkpointNode = objectMapper.readTree(event.getPayload()).path("checkpoint");
            JsonNode countNode = checkpointNode.path("decisionCount");
            if (!checkpointNode.isObject() || !countNode.isIntegralNumber() || countNode.asInt() < 0) {
                warnUnreadable(taskId, "checkpoint 计数语义非法", null);
                return CheckpointSnapshot.unreadable();
            }
            if (!hasValidSectionCounts(checkpointNode.path("dynamicBranchCountsBySection"))) {
                warnUnreadable(taskId, "checkpoint section 计数语义非法", null);
                return CheckpointSnapshot.unreadable();
            }
            OrchestratorCheckpoint checkpoint = objectMapper.treeToValue(
                    checkpointNode,
                    OrchestratorCheckpoint.class).normalized();
            return new CheckpointSnapshot(
                    checkpoint.getDecisionCount(),
                    checkpoint.getDynamicBranchCountsBySection(),
                    checkpoint.getSourceUrls(),
                    OrchestrationRuntimeState.CheckpointStateStatus.RESTORED);
        } catch (Exception exception) {
            warnUnreadable(taskId, "checkpoint 反序列化失败", exception);
            return CheckpointSnapshot.unreadable();
        }
    }

    /** 历史 payload 缺少 section map 时仍可读；字段存在时则要求所有 value 都是非负整数。 */
    private boolean hasValidSectionCounts(JsonNode countsNode) {
        if (countsNode.isMissingNode() || countsNode.isNull()) {
            return true;
        }
        if (!countsNode.isObject()) {
            return false;
        }
        java.util.Iterator<JsonNode> values = countsNode.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (!value.isIntegralNumber() || value.asInt() < 0) {
                return false;
            }
        }
        return true;
    }

    private PlanSnapshot loadActivePlan(Long taskId) {
        if (taskId == null) {
            return PlanSnapshot.absent();
        }
        try {
            Optional<TaskPlan> activePlan = taskPlanRepository
                    .findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(taskId);
            if (activePlan.isEmpty()) {
                return PlanSnapshot.absent();
            }
            TaskPlan plan = activePlan.get();
            return new PlanSnapshot(plan.getId(), resolveNextPlanVersion(plan.getPlanVersion()), true);
        } catch (RuntimeException exception) {
            warnUnreadable(taskId, "活动计划仓储访问失败", exception);
            return new PlanSnapshot(null, 1, false);
        }
    }

    private int resolveNextPlanVersion(Integer currentVersion) {
        if (currentVersion == null || currentVersion < 1) {
            return 1;
        }
        return currentVersion == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentVersion + 1;
    }

    private int incrementSafely(int value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, value) + 1;
    }

    private void warnUnreadable(Long taskId, String reason, Exception exception) {
        String exceptionType = exception == null ? "NONE" : exception.getClass().getSimpleName();
        LOGGER.warn("Orchestrator 运行时状态不可读，taskId={}，reason={}，exceptionType={}",
                taskId, reason, exceptionType);
    }

    private record CheckpointSnapshot(
            int decisionCount,
            Map<String, Integer> sectionCounts,
            List<String> sourceUrls,
            OrchestrationRuntimeState.CheckpointStateStatus status
    ) {
        private static CheckpointSnapshot absent() {
            return new CheckpointSnapshot(
                    0, Map.of(), List.of(), OrchestrationRuntimeState.CheckpointStateStatus.ABSENT);
        }

        private static CheckpointSnapshot unreadable() {
            return new CheckpointSnapshot(
                    0, Map.of(), List.of(), OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
        }
    }

    private record PlanSnapshot(Long planVersionId, int nextPlanVersion, boolean readable) {
        private static PlanSnapshot absent() {
            return new PlanSnapshot(null, 1, true);
        }
    }
}
