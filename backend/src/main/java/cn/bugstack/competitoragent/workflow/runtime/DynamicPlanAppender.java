package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class DynamicPlanAppender {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final DynamicTaskGraphService dynamicTaskGraphService;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;

    /**
     * 当终审节点触发动态回流条件时，创建并挂载新的动态计划。
     */
    public boolean maybeAppendDynamicPlan(Long taskId,
                                          List<TaskNode> nodes,
                                          Map<String, TaskNode> nodeMap,
                                          TaskNode completedNode) {
        JsonNode reviewOutput = readJson(completedNode == null ? null : completedNode.getOutputData());
        if (!shouldCreateDynamicBackflow(completedNode, reviewOutput)) {
            return false;
        }

        List<RevisionDirective> directives = readRevisionDirectives(reviewOutput);
        if (directives.isEmpty()) {
            return false;
        }

        TaskPlan parentPlan = resolveParentPlan(completedNode);
        if (parentPlan == null) {
            return false;
        }

        return taskRepository.findById(taskId).map(task -> appendDynamicPlan(taskId, task, nodes, nodeMap, completedNode, parentPlan, directives))
                .orElse(false);
    }

    private boolean appendDynamicPlan(Long taskId,
                                      AnalysisTask task,
                                      List<TaskNode> nodes,
                                      Map<String, TaskNode> nodeMap,
                                      TaskNode completedNode,
                                      TaskPlan parentPlan,
                                      List<RevisionDirective> directives) {
        if (task.getCurrentPlanVersionId() == null || !task.getCurrentPlanVersionId().equals(parentPlan.getId())) {
            return false;
        }

        WorkflowPlan baseWorkflowPlan = readWorkflowPlan(parentPlan.getPlanSnapshot());
        if (baseWorkflowPlan == null) {
            return false;
        }

        TaskPlan derivedPlan = dynamicTaskGraphService.createDynamicPlan(parentPlan, completedNode, directives, baseWorkflowPlan);
        List<TaskNode> dynamicNodes = materializeDynamicNodes(taskId, completedNode, derivedPlan, nodeMap);
        if (dynamicNodes.isEmpty()) {
            return false;
        }

        nodeRepository.saveAll(dynamicNodes);
        nodes.addAll(dynamicNodes);
        nodes.sort(java.util.Comparator.comparingInt(TaskNode::getExecutionOrder));
        for (TaskNode dynamicNode : dynamicNodes) {
            nodeMap.put(dynamicNode.getNodeName(), dynamicNode);
        }

        task.setCurrentPlanVersionId(derivedPlan.getId());
        task.setCurrentPlanVersion(derivedPlan.getPlanVersion());
        task.setErrorMessage(null);
        taskRepository.save(task);
        log.info("dynamic backflow plan attached, taskId={}, triggerNode={}, planVersion={}, dynamicNodeCount={}",
                taskId, completedNode.getNodeName(), derivedPlan.getPlanVersion(), dynamicNodes.size());
        return true;
    }

    private boolean shouldCreateDynamicBackflow(TaskNode completedNode, JsonNode reviewOutput) {
        if (completedNode == null
                || completedNode.getAgentType() != AgentType.REVIEWER
                || completedNode.getStatus() != TaskNodeStatus.SUCCESS
                || reviewOutput == null) {
            return false;
        }
        if (reviewOutput.path("passed").asBoolean(true)) {
            return false;
        }
        if (reviewOutput.path("requiresHumanIntervention").asBoolean(false)) {
            return false;
        }
        return "final".equalsIgnoreCase(reviewOutput.path("reviewStage").asText(""));
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

    /**
     * 只把当前动态分支中新派生的节点实体化并落库，避免重复写入旧节点。
     */
    private List<TaskNode> materializeDynamicNodes(Long taskId,
                                                   TaskNode triggerNode,
                                                   TaskPlan derivedPlan,
                                                   Map<String, TaskNode> nodeMap) {
        WorkflowPlan workflowPlan = readWorkflowPlan(derivedPlan == null ? null : derivedPlan.getPlanSnapshot());
        if (workflowPlan == null || derivedPlan == null) {
            return List.of();
        }

        List<TaskNode> materializedNodes = new ArrayList<>();
        for (WorkflowPlan.WorkflowPlanNode planNode : workflowPlan.getNodes()) {
            if (!planNode.isDynamicNode()
                    || !derivedPlan.getBranchKey().equals(planNode.getBranchKey())
                    || nodeMap.containsKey(planNode.getNodeName())) {
                continue;
            }
            materializedNodes.add(TaskNode.builder()
                    .taskId(taskId)
                    .nodeName(planNode.getNodeName())
                    .displayName(planNode.getDisplayName())
                    .agentType(AgentType.valueOf(planNode.getAgentType()))
                    .dependsOn(writeDependencyJson(planNode.getDependsOn()))
                    .nodeConfig(planNode.getNodeConfig())
                    .nodeNotes(planNode.getNotes())
                    .allowFailedDependency(planNode.isAllowFailedDependency())
                    .required(planNode.isRequired())
                    .retryable(planNode.isRetryable())
                    .maxRetries(planNode.getMaxRetries())
                    .retryCount(0)
                    .status(TaskNodeStatus.PENDING)
                    .executionOrder(planNode.getExecutionOrder())
                    .planVersionId(derivedPlan.getId())
                    .branchKey(planNode.getBranchKey())
                    .dynamicNode(planNode.isDynamicNode())
                    .originNodeName(planNode.getOriginNodeName() == null ? triggerNode.getNodeName() : planNode.getOriginNodeName())
                    .build());
        }
        return materializedNodes;
    }

    private String writeDependencyJson(List<String> dependencies) {
        try {
            return objectMapper.writeValueAsString(dependencies == null ? List.of() : dependencies);
        } catch (Exception e) {
            log.warn("failed to serialize dynamic node dependencies", e);
            return "[]";
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
