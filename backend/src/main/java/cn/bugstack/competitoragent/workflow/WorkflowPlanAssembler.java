package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WorkflowPlanAssembler 只负责把正式执行计划定义投影成技术快照。
 * 这样 WorkflowFactory 不再同时承担“业务计划生成”和“快照装配”两类职责，
 * preview/create/runtime 读到的 WorkflowPlan 也始终来自同一个正式定义。
 */
@Component
public class WorkflowPlanAssembler {

    public WorkflowPlan fromExecutionPlan(ExecutionPlanDefinition executionPlan) {
        List<WorkflowPlan.WorkflowPlanStage> stages = executionPlan == null || executionPlan.getStages() == null
                ? List.of()
                : executionPlan.getStages().stream()
                .map(stage -> WorkflowPlan.WorkflowPlanStage.builder()
                        .stageCode(stage.getStageCode())
                        .title(stage.getTitle())
                        .summary(stage.getSummary())
                        .detail(stage.getDetail())
                        .sourceUrls(stage.getSourceUrls())
                        .build())
                .toList();

        List<WorkflowPlan.WorkflowPlanNode> nodes = executionPlan == null || executionPlan.getNodes() == null
                ? List.of()
                : executionPlan.getNodes().stream()
                .map(node -> WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName(node.getNodeName())
                        .displayName(node.getDisplayName())
                        .agentType(node.getAgentType())
                        .dependsOn(node.getDependsOn())
                        .required(node.isRequired())
                        .executionOrder(node.getExecutionOrder())
                        .nodeConfig(node.getNodeConfig())
                        .notes(node.getNotes())
                        .stageCode(node.getStageCode())
                        .goal(node.getGoal())
                        .summary(node.getSummary())
                        .fallbackOrder(node.getFallbackOrder())
                        .sourceUrls(node.getSourceUrls())
                        .allowFailedDependency(node.isAllowFailedDependency())
                        .retryable(node.isRetryable())
                        .maxRetries(node.getMaxRetries())
                        .build())
                .toList();

        return WorkflowPlan.builder()
                .contractType(executionPlan == null ? null : executionPlan.getContractType())
                .goal(executionPlan == null ? null : executionPlan.getGoal())
                .coverageContract(executionPlan == null ? null : executionPlan.getCoverageContract())
                .stages(stages)
                .nodes(nodes)
                .build();
    }
}
