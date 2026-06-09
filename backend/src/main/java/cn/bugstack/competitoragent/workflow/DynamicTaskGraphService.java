package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态任务图服务。
 * <p>
 * 它负责两类与 Task 4.3 强相关的能力：
 * 1. 基于修订指令生成新的计划版本；
 * 2. 在重跑/恢复时按计划版本与分支关系计算影响范围。
 */
@Service
@RequiredArgsConstructor
public class DynamicTaskGraphService {

    private final TaskPlanRepository taskPlanRepository;
    private final TaskPlanVersioner taskPlanVersioner;
    private final CompensationGraphAssembler compensationGraphAssembler;

    /**
     * 为任务保存第一版计划。
     * 若已存在激活计划，则直接复用，避免重复创建同名初始版本。
     */
    public TaskPlan ensureInitialPlan(Long taskId, WorkflowPlan workflowPlan) {
        return taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(taskId)
                .orElseGet(() -> taskPlanRepository.save(taskPlanVersioner.createInitialPlan(taskId, workflowPlan)));
    }

    /**
     * 基于 Reviewer 的修订指令派生新的动态计划版本。
     * 当前阶段只做最小骨架：把补证/重写回流升级成正式 TaskPlan，而不是一次做成全自动规划器。
     */
    public TaskPlan createDynamicPlan(TaskPlan parentPlan,
                                      TaskNode triggerNode,
                                      List<RevisionDirective> directives,
                                      WorkflowPlan baseWorkflowPlan) {
        String branchSuffix = "review-" + (parentPlan.getPlanVersion() + 1);
        String parentBranchKey = normalizeBranchKey(parentPlan.getBranchKey());
        String derivedBranchKey = parentBranchKey + "/" + branchSuffix;
        List<WorkflowPlan.WorkflowPlanNode> dynamicNodes = compensationGraphAssembler.assembleDynamicNodes(
                parentPlan,
                triggerNode,
                directives,
                nextExecutionOrder(baseWorkflowPlan),
                derivedBranchKey);
        List<WorkflowPlan.WorkflowPlanNode> mergedNodes = new ArrayList<>(baseWorkflowPlan.getNodes());
        mergedNodes.addAll(dynamicNodes);

        WorkflowPlan derivedPlan = baseWorkflowPlan.toBuilder()
                .planVersion(parentPlan.getPlanVersion() + 1)
                .parentPlanVersionId(parentPlan.getId())
                .branchKey(derivedBranchKey)
                .dynamicPlan(true)
                .nodes(mergedNodes)
                .build();

        taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(parentPlan.getTaskId())
                .ifPresent(activePlan -> {
                    activePlan.setActive(false);
                    taskPlanRepository.save(activePlan);
                });

        return taskPlanRepository.save(taskPlanVersioner.createDerivedPlan(
                parentPlan,
                derivedPlan,
                triggerNode == null ? null : triggerNode.getNodeName(),
                "DYNAMIC_BACKFLOW",
                branchSuffix));
    }

    /**
     * 基于计划版本与分支键计算重跑影响范围。
     * 规则是：
     * 1. 始终沿依赖图的真实下游传播；
     * 2. 子分支重跑只影响自己和更深层子分支，不误伤兄弟分支；
     * 3. 主分支重跑仍可覆盖其派生出的所有子分支。
     */
    public List<TaskNode> calculateAffectedNodes(List<TaskNode> nodes, TaskNode startNode) {
        if (nodes == null || nodes.isEmpty() || startNode == null) {
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
                .filter(node -> affectedNodeNames.contains(node.getNodeName()))
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
        return branchKey == null || branchKey.isBlank() ? taskPlanVersioner.rootBranchKey() : branchKey;
    }

    private int nextExecutionOrder(WorkflowPlan workflowPlan) {
        return workflowPlan.getNodes().stream()
                .mapToInt(WorkflowPlan.WorkflowPlanNode::getExecutionOrder)
                .max()
                .orElse(-1) + 1;
    }
}
