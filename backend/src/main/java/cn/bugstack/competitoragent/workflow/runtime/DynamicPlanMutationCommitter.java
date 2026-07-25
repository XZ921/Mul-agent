package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicPlanMaterializationException;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Policy-approved mutation 的唯一数据库提交者。
 * 计划、节点、任务当前版本和 checkpoint 必须在同一事务内一起成功或一起回滚。
 */
@Component
public class DynamicPlanMutationCommitter {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final OrchestrationTraceService traceService;
    private final ObjectMapper objectMapper;

    public DynamicPlanMutationCommitter(AnalysisTaskRepository taskRepository,
                                        TaskNodeRepository nodeRepository,
                                        TaskPlanRepository taskPlanRepository,
                                        DynamicTaskGraphService dynamicTaskGraphService,
                                        OrchestrationTraceService traceService,
                                        ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.taskPlanRepository = taskPlanRepository;
        this.dynamicTaskGraphService = dynamicTaskGraphService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CommitResult commit(Long taskId, AnalysisTask task, TaskPlan parentPlan, TaskNode triggerNode,
                               DynamicPlanMutation mutation, OrchestrationDecision decision,
                               WorkflowPlan basePlan, Map<String, TaskNode> currentNodeMap) {
        TaskPlan replayed = taskPlanRepository.findByTaskIdAndDecisionId(taskId, mutation.getDecisionId())
                .orElse(null);
        if (replayed != null) {
            return CommitResult.replayed(replayed);
        }
        if (task.getCurrentPlanVersionId() == null
                || !task.getCurrentPlanVersionId().equals(parentPlan.getId())) {
            throw new DynamicPlanMaterializationException("父计划已不是任务当前版本");
        }

        TaskPlan derivedPlan = dynamicTaskGraphService.createDynamicPlan(parentPlan, triggerNode, mutation, basePlan);
        List<TaskNode> dynamicNodes = materialize(taskId, triggerNode, derivedPlan, currentNodeMap);
        if (dynamicNodes.isEmpty()) {
            throw new DynamicPlanMaterializationException("派生计划未物化出任何新节点");
        }
        nodeRepository.saveAll(dynamicNodes);
        task.setCurrentPlanVersionId(derivedPlan.getId());
        task.setCurrentPlanVersion(derivedPlan.getPlanVersion());
        task.setErrorMessage(null);
        taskRepository.save(task);
        // checkpoint outbox 写入失败必须向外抛出，使上述计划、节点和任务版本一起回滚。
        traceService.recordCheckpoint(taskId, triggerNode, derivedPlan, decision, mutation);
        return CommitResult.committed(derivedPlan, dynamicNodes);
    }

    @Transactional
    public void markWaiting(TaskNode triggerNode, String reason) {
        triggerNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        triggerNode.setInterventionReason(reason);
        nodeRepository.save(triggerNode);
    }

    private List<TaskNode> materialize(Long taskId, TaskNode triggerNode, TaskPlan derivedPlan,
                                       Map<String, TaskNode> currentNodeMap) {
        try {
            WorkflowPlan plan = objectMapper.readValue(derivedPlan.getPlanSnapshot(), WorkflowPlan.class);
            List<TaskNode> result = new ArrayList<>();
            for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
                if (!node.isDynamicNode()
                        || !derivedPlan.getBranchKey().equals(node.getBranchKey())
                        || currentNodeMap.containsKey(node.getNodeName())) {
                    continue;
                }
                result.add(TaskNode.builder()
                        .taskId(taskId).nodeName(node.getNodeName()).displayName(node.getDisplayName())
                        .agentType(AgentType.valueOf(node.getAgentType()))
                        .dependsOn(objectMapper.writeValueAsString(node.getDependsOn()))
                        .nodeConfig(node.getNodeConfig()).nodeNotes(node.getNotes())
                        .allowFailedDependency(node.isAllowFailedDependency()).required(node.isRequired())
                        .retryable(node.isRetryable()).maxRetries(node.getMaxRetries()).retryCount(0)
                        .status(TaskNodeStatus.PENDING).executionOrder(node.getExecutionOrder())
                        .planVersionId(derivedPlan.getId()).branchKey(node.getBranchKey())
                        .dynamicNode(true).originNodeName(node.getOriginNodeName() == null
                                ? triggerNode.getNodeName() : node.getOriginNodeName()).build());
            }
            return result;
        } catch (Exception exception) {
            throw new DynamicPlanMaterializationException("动态节点物化失败", exception);
        }
    }

    public record CommitResult(boolean committed, boolean replayed, TaskPlan plan, List<TaskNode> nodes) {
        static CommitResult committed(TaskPlan plan, List<TaskNode> nodes) {
            return new CommitResult(true, false, plan, List.copyOf(nodes));
        }
        static CommitResult replayed(TaskPlan plan) {
            return new CommitResult(false, true, plan, List.of());
        }
    }
}
