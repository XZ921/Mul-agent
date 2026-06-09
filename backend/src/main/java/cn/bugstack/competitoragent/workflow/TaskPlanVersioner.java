package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 任务计划版本器。
 * <p>
 * 它只负责解决两个问题：
 * 1. 下一版计划的版本号和分支键如何生成；
 * 2. 如何把 WorkflowPlan 固化成可追溯的 TaskPlan 快照。
 * 这样创建版本与执行版本的职责就不会混杂在 DagExecutor 或 TaskService 里。
 */
@Component
@RequiredArgsConstructor
public class TaskPlanVersioner {

    private static final String ROOT_BRANCH_KEY = "root";

    private final ObjectMapper objectMapper;

    /**
     * 创建任务的第一版计划。
     */
    public TaskPlan createInitialPlan(Long taskId, WorkflowPlan workflowPlan) {
        return TaskPlan.builder()
                .taskId(taskId)
                .planVersion(1)
                .parentPlanId(null)
                .branchKey(ROOT_BRANCH_KEY)
                .triggerNodeName(null)
                .planType("INITIAL")
                .active(true)
                .planSnapshot(writePlanSnapshot(workflowPlan))
                .build();
    }

    /**
     * 基于上一版计划派生出新的动态版本。
     * 这里不直接保存，由上层服务决定何时落库与如何关闭旧版本激活态。
     */
    public TaskPlan createDerivedPlan(TaskPlan parentPlan,
                                      WorkflowPlan workflowPlan,
                                      String triggerNodeName,
                                      String planType,
                                      String branchSuffix) {
        if (parentPlan == null) {
            throw new IllegalArgumentException("parentPlan is required");
        }
        String normalizedSuffix = normalizeBranchSuffix(branchSuffix);
        String parentBranchKey = parentPlan.getBranchKey() == null || parentPlan.getBranchKey().isBlank()
                ? ROOT_BRANCH_KEY
                : parentPlan.getBranchKey();
        String branchKey = normalizedSuffix == null
                ? parentBranchKey
                : parentBranchKey + "/" + normalizedSuffix;
        return TaskPlan.builder()
                .taskId(parentPlan.getTaskId())
                .planVersion(parentPlan.getPlanVersion() + 1)
                .parentPlanId(parentPlan.getId())
                .branchKey(branchKey)
                .triggerNodeName(triggerNodeName)
                .planType(planType == null || planType.isBlank() ? "DYNAMIC" : planType.trim().toUpperCase())
                .active(true)
                .planSnapshot(writePlanSnapshot(workflowPlan))
                .build();
    }

    public String rootBranchKey() {
        return ROOT_BRANCH_KEY;
    }

    /**
     * 把自由文本后缀收敛为稳定 branchKey 片段，
     * 避免后续影响范围判断时因为空格、大小写或特殊字符不一致而失配。
     */
    private String normalizeBranchSuffix(String branchSuffix) {
        if (branchSuffix == null || branchSuffix.isBlank()) {
            return null;
        }
        return branchSuffix.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    private String writePlanSnapshot(WorkflowPlan workflowPlan) {
        try {
            return objectMapper.writeValueAsString(workflowPlan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize task plan snapshot failed", e);
        }
    }
}
