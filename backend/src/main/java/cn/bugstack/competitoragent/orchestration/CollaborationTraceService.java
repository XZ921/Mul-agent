package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 前置协作规划 trace 服务。
 * P2 第一版复用 TaskWorkflowEvent 作为审计载体，不新增数据库表。
 */
@Service
public class CollaborationTraceService {

    private static final String TOPIC = "task.collaboration";

    private final TaskWorkflowEventRepository repository;
    private final ObjectMapper objectMapper;

    public CollaborationTraceService(TaskWorkflowEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录已经通过初始校验的协作计划。
     * payload 保留 goal / plan / review 的关键 ID 和证据状态，sourceUrls 单独落列便于 replay 聚合。
     */
    public TaskWorkflowEvent recordPlan(CollaborationGoal goal,
                                        CollaborationPlan plan,
                                        InitialPlanReview review,
                                        Long planVersionId,
                                        Integer planVersion,
                                        String branchKey) {
        CollaborationGoal normalizedGoal = goal == null ? CollaborationGoal.builder().build().normalized() : goal.normalized();
        CollaborationPlan normalizedPlan = plan == null ? CollaborationPlan.builder().build().normalized() : plan.normalized();
        InitialPlanReview normalizedReview = review == null ? InitialPlanReview.builder().build().normalized() : review.normalized();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "协作计划已记录：" + normalizedPlan.getPlanId());
        payload.put("goalId", normalizedGoal.getGoalId());
        payload.put("planId", normalizedPlan.getPlanId());
        payload.put("reviewId", normalizedReview.getReviewId());
        payload.put("allowed", normalizedReview.isAllowed());
        payload.put("mappedWorkflowTemplate", normalizedReview.getMappedWorkflowTemplate());
        payload.put("planVersion", planVersion);
        payload.put("evidenceState", normalizedPlan.getEvidenceState());
        payload.put("checkpoints", normalizedPlan.getCheckpoints());
        return saveEvent(
                normalizedPlan.getTaskId(),
                "collaboration_plan",
                planVersionId,
                branchKey,
                WorkflowEventType.COLLABORATION_PLAN_RECORDED,
                "collaboration_plan_recorded",
                payload,
                normalizedPlan.getSourceUrls());
    }

    /**
     * 记录协作规划恢复检查点。
     * checkpoint 只表达恢复游标和待处理动作，不改变任务主执行状态。
     */
    public TaskWorkflowEvent recordCheckpoint(CollaborationCheckpoint checkpoint,
                                              Long planVersionId,
                                              String branchKey) {
        CollaborationCheckpoint normalizedCheckpoint = checkpoint == null
                ? CollaborationCheckpoint.builder().build().normalized()
                : checkpoint.normalized();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "协作检查点已更新：" + normalizedCheckpoint.getPhase());
        payload.put("checkpointId", normalizedCheckpoint.getCheckpointId());
        payload.put("goalId", normalizedCheckpoint.getGoalId());
        payload.put("planId", normalizedCheckpoint.getPlanId());
        payload.put("reviewId", normalizedCheckpoint.getLastReviewId());
        payload.put("phase", normalizedCheckpoint.getPhase());
        payload.put("mappedWorkflowPlanId", normalizedCheckpoint.getMappedWorkflowPlanId());
        payload.put("pendingActions", normalizedCheckpoint.getPendingActions());
        payload.put("resumeReason", normalizedCheckpoint.getResumeReason());
        payload.put("evidenceState", normalizedCheckpoint.getEvidenceState());
        return saveEvent(
                normalizedCheckpoint.getTaskId(),
                "collaboration_checkpoint",
                planVersionId,
                branchKey,
                WorkflowEventType.COLLABORATION_CHECKPOINT_UPDATED,
                "collaboration_checkpoint_updated",
                payload,
                normalizedCheckpoint.getSourceUrls());
    }

    private TaskWorkflowEvent saveEvent(Long taskId,
                                        String nodeName,
                                        Long planVersionId,
                                        String branchKey,
                                        WorkflowEventType eventType,
                                        String tag,
                                        Map<String, Object> payload,
                                        List<String> sourceUrls) {
        LocalDateTime now = LocalDateTime.now();
        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .branchKey(branchKey)
                .eventType(eventType)
                .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
                .topic(TOPIC)
                .tag(tag)
                .payload(writeJson(payload))
                .sourceUrls(writeJson(sourceUrls == null ? List.of() : sourceUrls))
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return repository.save(event);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize collaboration trace failed", e);
        }
    }
}
