package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流计划校验器。
 * 在 DAG 持久化之前统一校验节点命名、依赖、执行顺序和环路，避免把非法计划写进数据库后才在执行阶段失败。
 */
@Component
public class WorkflowPlanValidator {

    /**
     * 校验动态生成的工作流计划。
     */
    public void validate(WorkflowPlan plan) {
        validateForCreation(plan);
    }

    /**
     * create / preview 新链路要求正式阶段契约完整存在，
     * 这样才能让首屏预览、落库快照和后续审计共用同一份计划语义。
     */
    public void validateForCreation(WorkflowPlan plan) {
        validateGraphBasics(plan);
        validateStageContract(plan, true);
    }

    /**
     * 历史快照复用允许继续读取旧格式，
     * 但一旦快照自己声明了正式阶段契约，就必须继续保证 node -> stage 对应关系成立。
     */
    public void validateForSnapshotReuse(WorkflowPlan plan) {
        validateGraphBasics(plan);
        if (plan != null && plan.hasFormalStageContract()) {
            validateStageContract(plan, false);
        }
    }

    private void validateGraphBasics(WorkflowPlan plan) {
        if (plan == null || plan.getNodes() == null || plan.getNodes().isEmpty()) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED, "Workflow plan must contain at least one node");
        }

        Map<String, WorkflowPlan.WorkflowPlanNode> nodeByName = new HashMap<>();
        Set<Integer> executionOrders = new HashSet<>();

        for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
            validateNodeBasics(node, nodeByName, executionOrders);
        }

        for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
            validateDependencies(node, nodeByName);
        }

        validateAcyclic(plan, nodeByName);
    }

    private void validateNodeBasics(WorkflowPlan.WorkflowPlanNode node,
                                    Map<String, WorkflowPlan.WorkflowPlanNode> nodeByName,
                                    Set<Integer> executionOrders) {
        if (node == null) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED, "Workflow node cannot be null");
        }
        if (!StringUtils.hasText(node.getNodeName())) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED, "Workflow node name cannot be blank");
        }
        if (!StringUtils.hasText(node.getDisplayName())) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "Workflow node display name cannot be blank: " + node.getNodeName());
        }
        if (!StringUtils.hasText(node.getAgentType())) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "Workflow node agent type cannot be blank: " + node.getNodeName());
        }
        try {
            AgentType.valueOf(node.getAgentType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "Unsupported agent type in workflow plan: " + node.getAgentType());
        }
        if (node.getMaxRetries() < 0) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "maxRetries cannot be negative: " + node.getNodeName());
        }
        if (!executionOrders.add(node.getExecutionOrder())) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "Duplicate execution order detected: " + node.getExecutionOrder());
        }

        WorkflowPlan.WorkflowPlanNode duplicated = nodeByName.putIfAbsent(node.getNodeName(), node);
        if (duplicated != null) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "Duplicate node name detected: " + node.getNodeName());
        }
    }

    private void validateDependencies(WorkflowPlan.WorkflowPlanNode node,
                                      Map<String, WorkflowPlan.WorkflowPlanNode> nodeByName) {
        List<String> dependencies = node.getDependsOn() == null ? List.of() : node.getDependsOn();
        for (String dependencyName : dependencies) {
            if (!StringUtils.hasText(dependencyName)) {
                throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                        "Dependency name cannot be blank: " + node.getNodeName());
            }
            if (node.getNodeName().equals(dependencyName)) {
                throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                        "Node cannot depend on itself: " + node.getNodeName());
            }

            WorkflowPlan.WorkflowPlanNode dependencyNode = nodeByName.get(dependencyName);
            if (dependencyNode == null) {
                throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                        "Dependency node not found: " + node.getNodeName() + " -> " + dependencyName);
            }
            if (dependencyNode.getExecutionOrder() >= node.getExecutionOrder()) {
                throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                        "Dependency execution order must be earlier: " + node.getNodeName() + " -> " + dependencyName);
            }
        }
    }

    /**
     * 使用拓扑排序校验整个图无环。
     */
    private void validateAcyclic(WorkflowPlan plan, Map<String, WorkflowPlan.WorkflowPlanNode> nodeByName) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();

        for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
            indegree.put(node.getNodeName(), 0);
            adjacency.put(node.getNodeName(), new HashSet<>());
        }

        for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
            List<String> dependencies = node.getDependsOn() == null ? List.of() : node.getDependsOn();
            for (String dependencyName : dependencies) {
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
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                int nextDegree = indegree.computeIfPresent(next, (key, value) -> value - 1);
                if (nextDegree == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != nodeByName.size()) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED, "Workflow plan contains cyclic dependencies");
        }
    }

    /**
     * 新链路一旦声明正式阶段合同，就要求所有节点显式挂到已声明阶段上。
     * 这样 preview/create/runtime 才能围绕同一份计划快照进行解释与审计。
     */
    private void validateStageContract(WorkflowPlan plan, boolean requireStages) {
        if (plan == null) {
            return;
        }
        if (requireStages && !plan.hasFormalStageContract()) {
            throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                    "workflow stages must not be empty when creating a formal plan");
        }
        if (!plan.hasFormalStageContract()) {
            return;
        }

        Set<String> stageCodes = new HashSet<>();
        for (WorkflowPlan.WorkflowPlanStage stage : plan.getStages()) {
            if (stage != null && StringUtils.hasText(stage.getStageCode())) {
                stageCodes.add(stage.getStageCode());
            }
        }

        for (WorkflowPlan.WorkflowPlanNode node : plan.getNodes()) {
            if (!StringUtils.hasText(node.getStageCode()) || !stageCodes.contains(node.getStageCode())) {
                throw new BusinessException(ResultCode.TASK_CREATE_FAILED,
                        "workflow node stageCode is missing or unknown: " + node.getNodeName());
            }
        }
    }
}
