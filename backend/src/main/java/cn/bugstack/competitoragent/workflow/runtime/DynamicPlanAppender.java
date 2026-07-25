package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.OrchestrationContext;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecisionBatch;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态计划挂载协作者。
 * <p>
 * 终审失败后是否要派生动态补图、如何落库动态节点、如何切换 currentPlanVersion，
 * 都属于运行时编排扩展逻辑，抽到独立协作者后 DagExecutor 更容易保持主循环清晰。
 */
@Slf4j
@Component
public class DynamicPlanAppender {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;
    private final OrchestrationRuntimeDecisionService runtimeDecisionService;
    private final OrchestrationTraceService orchestrationTraceService;
    private final DynamicPlanMutationCommitter mutationCommitter;
    private final DynamicPlanMutationFailureHandler mutationFailureHandler;

    @Autowired
    public DynamicPlanAppender(AnalysisTaskRepository taskRepository,
                               TaskNodeRepository nodeRepository,
                               DynamicTaskGraphService dynamicTaskGraphService,
                               TaskPlanRepository taskPlanRepository,
                               ObjectMapper objectMapper,
                               OrchestrationRuntimeDecisionService runtimeDecisionService,
                               OrchestrationTraceService orchestrationTraceService,
                               DynamicPlanMutationCommitter mutationCommitter,
                               DynamicPlanMutationFailureHandler mutationFailureHandler) {
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.dynamicTaskGraphService = dynamicTaskGraphService;
        this.taskPlanRepository = taskPlanRepository;
        this.objectMapper = objectMapper;
        this.runtimeDecisionService = runtimeDecisionService;
        this.orchestrationTraceService = orchestrationTraceService;
        this.mutationCommitter = mutationCommitter;
        this.mutationFailureHandler = mutationFailureHandler;
    }

    /**
     * 兼容纯单元测试的轻量构造器；生产容器必须使用上面的完整构造器注入 Spring Bean。
     * 这里手动创建的 committer 不经过 Spring AOP 代理，不能用于验证 @Transactional 回滚语义；
     * mutation 原子性由 Spring 上下文注入的 DynamicPlanMutationCommitterTransactionTest 覆盖。
     */
    public DynamicPlanAppender(AnalysisTaskRepository taskRepository,
                               TaskNodeRepository nodeRepository,
                               DynamicTaskGraphService dynamicTaskGraphService,
                               TaskPlanRepository taskPlanRepository,
                               ObjectMapper objectMapper,
                               OrchestrationRuntimeDecisionService runtimeDecisionService,
                               OrchestrationTraceService orchestrationTraceService) {
        this(taskRepository, nodeRepository, dynamicTaskGraphService, taskPlanRepository, objectMapper,
                runtimeDecisionService, orchestrationTraceService,
                new DynamicPlanMutationCommitter(taskRepository, nodeRepository, taskPlanRepository,
                        dynamicTaskGraphService, orchestrationTraceService, objectMapper),
                new DynamicPlanMutationFailureHandler(nodeRepository));
    }

    /**
     * 当终审节点触发动态回流条件时，创建并挂载新的动态计划。
     */
    public boolean maybeAppendDynamicPlan(Long taskId,
                                          List<TaskNode> nodes,
                                          Map<String, TaskNode> nodeMap,
                                          TaskNode completedNode) {
        JsonNode reviewOutput = readJson(completedNode == null ? null : completedNode.getOutputData());
        if (!shouldProcessReviewerCycle(completedNode, reviewOutput)) {
            return false;
        }

        TaskPlan parentPlan = resolveParentPlan(completedNode);
        if (parentPlan == null) {
            return false;
        }

        List<RevisionDirective> directives = readRevisionDirectives(reviewOutput);
        return taskRepository.findById(taskId).map(task -> appendDynamicPlan(
                        taskId,
                        task,
                        nodes,
                        nodeMap,
                        completedNode,
                        parentPlan,
                        reviewOutput,
                        directives))
                .orElse(false);
    }

    private boolean appendDynamicPlan(Long taskId,
                                      AnalysisTask task,
                                      List<TaskNode> nodes,
                                      Map<String, TaskNode> nodeMap,
                                      TaskNode completedNode,
                                      TaskPlan parentPlan,
                                      JsonNode reviewOutput,
                                      List<RevisionDirective> directives) {
        if (task.getCurrentPlanVersionId() == null || !task.getCurrentPlanVersionId().equals(parentPlan.getId())) {
            return false;
        }

        WorkflowPlan baseWorkflowPlan = readWorkflowPlan(parentPlan.getPlanSnapshot());
        if (baseWorkflowPlan == null) {
            return false;
        }

        String taskStatus = task.getStatus() == null ? null : task.getStatus().name();
        String nodeStatus = completedNode.getStatus() == null ? null : completedNode.getStatus().name();
        OrchestrationContext orchestrationContext = buildOrchestrationContext(
                taskId, completedNode, reviewOutput, directives, taskStatus);
        OrchestrationRuntimeDecisionBatch batch = runtimeDecisionService.decide(
                orchestrationContext,
                taskStatus,
                nodeStatus);

        // 一个 batch 对应一个不可拆分的决策周期；原 LLM rejection、fallback、shadow 与 failure 必须原子留痕。
        orchestrationTraceService.recordDecisionBatch(taskId, completedNode, batch);
        for (OrchestrationRuntimeDecision finalDecision : batch.finalDecisions()) {
            OrchestrationDecision decision = finalDecision.decision();
            DynamicPlanMutation mutation = finalDecision.mutation();
            if (!finalDecision.policyResult().isAllowed()) {
                continue;
            }
            if ("MARK_WAITING_INTERVENTION".equals(mutation.getMutationType())) {
                // confirmation/manual mutation 只暂停当前终审节点，不创建动态计划，也不污染 failureCategory。
                mutationCommitter.markWaiting(completedNode, decision.getReason());
                return false;
            }
            if (!"APPEND_NODES".equals(mutation.getMutationType())) {
                continue;
            }
            // 模型与 Policy 评估期间 task 可能已经切换计划；执行 mutation 前必须再次验证父版本仍为当前版本。
            if (task.getCurrentPlanVersionId() == null
                    || !task.getCurrentPlanVersionId().equals(parentPlan.getId())) {
                continue;
            }
            DynamicPlanMutationCommitter.CommitResult commitResult;
            try {
                commitResult = mutationCommitter.commit(taskId, task, parentPlan, completedNode, mutation,
                        decision, baseWorkflowPlan, nodeMap);
            } catch (Exception exception) {
                // 主事务已经回滚；失败收口使用独立事务，仅持久化人工停点与确定性原因。
                log.error("dynamic mutation materialization failed, taskId={}, decisionId={}",
                        taskId, decision.getDecisionId(), exception);
                mutationFailureHandler.close(completedNode, exception.getMessage());
                return false;
            }
            if (commitResult.replayed()) {
                return false;
            }
            TaskPlan derivedPlan = commitResult.plan();
            List<TaskNode> dynamicNodes = commitResult.nodes();
            nodes.addAll(dynamicNodes);
            nodes.sort(java.util.Comparator.comparingInt(TaskNode::getExecutionOrder));
            for (TaskNode dynamicNode : dynamicNodes) {
                nodeMap.put(dynamicNode.getNodeName(), dynamicNode);
            }

            log.info("dynamic backflow plan attached through orchestration decision, taskId={}, triggerNode={}, planVersion={}, dynamicNodeCount={}",
                    taskId, completedNode.getNodeName(), derivedPlan.getPlanVersion(), dynamicNodes.size());
            return true;
        }
        return false;
    }

    private boolean shouldProcessReviewerCycle(TaskNode completedNode, JsonNode reviewOutput) {
        if (completedNode == null
                || completedNode.getAgentType() != AgentType.REVIEWER
                || completedNode.getStatus() != TaskNodeStatus.SUCCESS
                || reviewOutput == null) {
            return false;
        }
        if (isTargetCoverageGateNode(completedNode)) {
            return false;
        }
        // 所有 Reviewer 阶段都进入同一决策周期。PASS 也要形成可审计 NO_MUTATION，
        // 但 maybeAppendDynamicPlan 只在真正提交新节点时返回 true。
        return true;
    }

    private boolean isTargetCoverageGateNode(TaskNode completedNode) {
        return completedNode.getNodeName() != null
                && completedNode.getNodeName().startsWith("target_coverage_gate_v");
    }

    private List<RevisionDirective> readRevisionDirectives(JsonNode reviewOutput) {
        if (reviewOutput == null) {
            return List.of();
        }
        try {
            if (reviewOutput.has("revisionDirectives") && reviewOutput.get("revisionDirectives").isArray()) {
                List<RevisionDirective> directives = objectMapper.convertValue(
                        reviewOutput.get("revisionDirectives"),
                        new TypeReference<List<RevisionDirective>>() {
                        });
                return directives.stream().map(RevisionDirective::normalized).toList();
            }
            JsonNode revisionPlan = reviewOutput.get("revisionPlan");
            if (revisionPlan != null && revisionPlan.has("directives") && revisionPlan.get("directives").isArray()) {
                List<RevisionDirective> directives = objectMapper.convertValue(
                        revisionPlan.get("directives"),
                        new TypeReference<List<RevisionDirective>>() {
                        });
                return directives.stream().map(RevisionDirective::normalized).toList();
            }
        } catch (Exception e) {
            log.warn("failed to parse revision directives from reviewer output", e);
        }
        return List.of();
    }

    private OrchestrationContext buildOrchestrationContext(Long taskId,
                                                           TaskNode completedNode,
                                                           JsonNode reviewOutput,
                                                           List<RevisionDirective> directives,
                                                           String taskStatus) {
        List<String> sourceUrls = readSourceUrls(reviewOutput);
        return OrchestrationContext.builder()
                .taskId(taskId)
                .planVersionId(completedNode.getPlanVersionId())
                .branchKey(completedNode.getBranchKey())
                .triggerNodeName(completedNode.getNodeName())
                .reviewStage(reviewOutput.path("reviewStage").asText(""))
                .taskStatus(taskStatus)
                .passed(reviewOutput.path("passed").asBoolean(false))
                .requiresHumanIntervention(reviewOutput.path("requiresHumanIntervention").asBoolean(false))
                .diagnoses(readDiagnoses(reviewOutput))
                .legacyRevisionDirectives(directives == null ? List.of() : directives)
                .sourceUrls(sourceUrls)
                .evidenceState(sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE)
                .inputSummary(reviewOutput.path("summary").asText("终审失败后进入 Orchestrator 反馈决策"))
                .build()
                .normalized();
    }

    private List<QualityDiagnosis> readDiagnoses(JsonNode jsonNode) {
        if (jsonNode == null || !jsonNode.has("diagnoses") || !jsonNode.get("diagnoses").isArray()) {
            return List.of();
        }
        try {
            List<QualityDiagnosis> diagnoses = objectMapper.convertValue(
                    jsonNode.get("diagnoses"),
                    new TypeReference<List<QualityDiagnosis>>() {
                    });
            return diagnoses.stream().map(QualityDiagnosis::normalized).toList();
        } catch (IllegalArgumentException e) {
            log.warn("failed to parse quality diagnoses from reviewer output", e);
            return List.of();
        }
    }

    private List<String> readSourceUrls(JsonNode jsonNode) {
        if (jsonNode == null || !jsonNode.has("sourceUrls") || !jsonNode.get("sourceUrls").isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(jsonNode.get("sourceUrls"), new TypeReference<List<String>>() {
            });
        } catch (IllegalArgumentException e) {
            log.warn("failed to parse source urls from reviewer output", e);
            return List.of();
        }
    }

    private TaskPlan resolveParentPlan(TaskNode completedNode) {
        if (completedNode == null || completedNode.getPlanVersionId() == null) {
            return null;
        }
        return taskPlanRepository.findById(completedNode.getPlanVersionId()).orElse(null);
    }

    private WorkflowPlan readWorkflowPlan(String rawPlanSnapshot) {
        if (rawPlanSnapshot == null || rawPlanSnapshot.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawPlanSnapshot, WorkflowPlan.class);
        } catch (Exception e) {
            log.warn("failed to parse workflow plan snapshot", e);
            return null;
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("failed to parse dynamic plan json", e);
            return null;
        }
    }
}
