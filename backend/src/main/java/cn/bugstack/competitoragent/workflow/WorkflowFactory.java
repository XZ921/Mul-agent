package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.CollaborationGoal;
import cn.bugstack.competitoragent.orchestration.CollaborationGoalAssembler;
import cn.bugstack.competitoragent.orchestration.CollaborationCheckpoint;
import cn.bugstack.competitoragent.orchestration.CollaborationPlan;
import cn.bugstack.competitoragent.orchestration.CollaborationPlanService;
import cn.bugstack.competitoragent.orchestration.CollaborationTraceService;
import cn.bugstack.competitoragent.orchestration.InitialPlanReview;
import cn.bugstack.competitoragent.orchestration.InitialPlanReviewService;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WorkflowFactory 现在只负责“编排”：
 * 1. 让 ExecutionPlanDefinitionBuilder 生成正式计划定义；
 * 2. 让 WorkflowPlanAssembler 把定义投影成可落库的 WorkflowPlan；
 * 3. 在创建任务时把 versioned plan 落成初始 TaskNode。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowFactory {

    private final TaskNodeRepository nodeRepository;
    private final WorkflowPlanValidator workflowPlanValidator;
    private final ObjectMapper objectMapper;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final ExecutionPlanDefinitionBuilder executionPlanDefinitionBuilder;
    private final WorkflowPlanAssembler workflowPlanAssembler;
    private final CollaborationGoalAssembler collaborationGoalAssembler;
    private final CollaborationPlanService collaborationPlanService;
    private final InitialPlanReviewService initialPlanReviewService;
    private final CollaborationTraceService collaborationTraceService;

    /**
     * 创建任务时只固化正式计划与初始节点，
     * 不在这里直接触发执行，避免“建模”和“运行”两类职责继续耦合。
     */
    public List<TaskNode> createWorkflow(AnalysisTask task) {
        WorkflowPlan plan = buildPreviewPlan(task);
        TaskPlan initialPlan = dynamicTaskGraphService.ensureInitialPlan(task.getId(), plan);
        recordInitialCollaborationTrace(task, initialPlan);
        WorkflowPlan versionedPlan = enrichWorkflowPlan(plan, initialPlan);
        task.setCurrentPlanVersionId(initialPlan.getId());
        task.setCurrentPlanVersion(initialPlan.getPlanVersion());

        List<TaskNode> nodes = new ArrayList<>();
        for (WorkflowPlan.WorkflowPlanNode planNode : versionedPlan.getNodes()) {
            nodes.add(TaskNode.builder()
                    .taskId(task.getId())
                    .nodeName(planNode.getNodeName())
                    .displayName(planNode.getDisplayName())
                    .agentType(AgentType.valueOf(planNode.getAgentType()))
                    .dependsOn(toJson(planNode.getDependsOn()))
                    .nodeConfig(planNode.getNodeConfig())
                    .nodeNotes(planNode.getNotes())
                    .allowFailedDependency(planNode.isAllowFailedDependency())
                    .required(planNode.isRequired())
                    .retryable(planNode.isRetryable())
                    .maxRetries(planNode.getMaxRetries())
                    .retryCount(0)
                    .status(TaskNodeStatus.PENDING)
                    .executionOrder(planNode.getExecutionOrder())
                    .planVersionId(initialPlan.getId())
                    .branchKey(planNode.getBranchKey())
                    .dynamicNode(planNode.isDynamicNode())
                    .originNodeName(planNode.getOriginNodeName())
                    .build());
        }

        List<TaskNode> savedNodes = nodeRepository.saveAll(nodes);
        log.info("create workflow success, taskId={}, schemaId={}, nodeCount={}",
                task.getId(), task.getSchemaId(), savedNodes.size());
        return savedNodes;
    }

    /**
     * 给计划补齐 planVersion / branch 等运行期版本信息。
     * 正式阶段语义保持不变，只叠加快照维度。
     */
    private WorkflowPlan enrichWorkflowPlan(WorkflowPlan plan, TaskPlan taskPlan) {
        String branchKey = taskPlan == null || !StringUtils.hasText(taskPlan.getBranchKey())
                ? "root"
                : taskPlan.getBranchKey();
        List<WorkflowPlan.WorkflowPlanNode> versionedNodes = plan.getNodes().stream()
                .map(node -> node.toBuilder()
                        .branchKey(StringUtils.hasText(node.getBranchKey()) ? node.getBranchKey() : branchKey)
                        .build())
                .toList();
        return plan.toBuilder()
                .planVersionId(taskPlan == null ? null : taskPlan.getId())
                .planVersion(taskPlan == null || taskPlan.getPlanVersion() == null ? 1 : taskPlan.getPlanVersion())
                .parentPlanVersionId(taskPlan == null ? null : taskPlan.getParentPlanId())
                .branchKey(branchKey)
                .dynamicPlan(taskPlan != null && !"INITIAL".equalsIgnoreCase(taskPlan.getPlanType()))
                .nodes(versionedNodes)
                .build();
    }

    public WorkflowPlan buildPlan(AnalysisTask task) {
        return assembleFormalWorkflowPlan(task, false);
    }

    /**
     * preview 阶段只生成正式计划快照，不提前触发实时搜索或执行。
     */
    public WorkflowPlan buildPreviewPlan(AnalysisTask task) {
        return assembleFormalWorkflowPlan(task, true);
    }

    /**
     * 这里显式串起“定义构建 -> 快照装配 -> 正式校验”三步，
     * 让 WorkflowFactory 真正退回到编排器角色，而不是继续自己承担所有细节拼装。
     */
    private WorkflowPlan assembleFormalWorkflowPlan(AnalysisTask task, boolean previewOnly) {
        CollaborationGoal collaborationGoal = collaborationGoalAssembler.assemble(task);
        CollaborationPlan collaborationPlan = collaborationPlanService.createPlan(collaborationGoal);
        InitialPlanReview initialPlanReview = initialPlanReviewService.review(collaborationPlan);
        ExecutionPlanDefinition executionPlanDefinition;
        if (initialPlanReview.isAllowed()) {
            executionPlanDefinition = executionPlanDefinitionBuilder.build(
                    task,
                    previewOnly,
                    collaborationPlan,
                    initialPlanReview
            );
        } else {
            log.warn("collaboration plan review blocked, taskId={}, planId={}, blockedReasons={}",
                    task.getId(), collaborationPlan.getPlanId(), initialPlanReview.getBlockedReasons());
            executionPlanDefinition = executionPlanDefinitionBuilder.build(task, previewOnly);
        }
        WorkflowPlan plan = workflowPlanAssembler.fromExecutionPlan(executionPlanDefinition);
        workflowPlanValidator.validateForCreation(plan);
        return plan;
    }

    private void recordInitialCollaborationTrace(AnalysisTask task, TaskPlan initialPlan) {
        CollaborationGoal collaborationGoal = collaborationGoalAssembler.assemble(task);
        CollaborationPlan collaborationPlan = collaborationPlanService.createPlan(collaborationGoal);
        InitialPlanReview initialPlanReview = initialPlanReviewService.review(collaborationPlan);
        Long planVersionId = initialPlan == null ? null : initialPlan.getId();
        Integer planVersion = initialPlan == null ? null : initialPlan.getPlanVersion();
        String branchKey = initialPlan == null ? null : initialPlan.getBranchKey();
        if (initialPlanReview.isAllowed()) {
            collaborationTraceService.recordPlan(
                    collaborationGoal,
                    collaborationPlan,
                    initialPlanReview,
                    planVersionId,
                    planVersion,
                    branchKey
            );
        }
        CollaborationCheckpoint checkpoint = CollaborationCheckpoint.builder()
                .checkpointId("cc-" + collaborationPlan.getPlanId())
                .taskId(task.getId())
                .goalId(collaborationGoal.getGoalId())
                .planId(collaborationPlan.getPlanId())
                .lastReviewId(initialPlanReview.getReviewId())
                .phase(initialPlanReview.isAllowed() ? "PLAN_APPROVED" : "PLAN_BLOCKED")
                .mappedWorkflowPlanId(planVersionId)
                .pendingActions(initialPlanReview.isAllowed() ? List.of() : List.of("FIX_COLLABORATION_PLAN"))
                .resumeReason(initialPlanReview.isAllowed()
                        ? "协作计划已通过初始校验，等待 WorkflowPlan 执行。"
                        : "协作计划初始校验被阻断：" + initialPlanReview.getBlockedReasons())
                .sourceUrls(collaborationPlan.getSourceUrls())
                .evidenceState(collaborationPlan.getEvidenceState())
                .build()
                .normalized();
        collaborationTraceService.recordCheckpoint(checkpoint, planVersionId, branchKey);
    }

    public void resetWorkflow(Long taskId) {
        nodeRepository.deleteAll(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize workflow node dependsOn failed", e);
        }
    }
}
