package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 动态补图组装器。
 * 它把 Reviewer 输出的修订指令转换成“最小可执行的动态任务图骨架”：
 * 1. 证据缺口/搜索问题：补证采集 -> 抽取 -> 分析 -> 改写 -> 复核
 * 2. 结构问题：抽取 -> 分析 -> 改写 -> 复核
 * 3. 表达问题：改写 -> 复核
 */
@Component
@RequiredArgsConstructor
public class CompensationGraphAssembler {

    private final ObjectMapper objectMapper;

    public List<WorkflowPlan.WorkflowPlanNode> assembleDynamicNodes(TaskPlan parentPlan,
                                                                    TaskNode triggerNode,
                                                                    List<RevisionDirective> directives,
                                                                    int startOrder,
                                                                    String derivedBranchKey) {
        List<WorkflowPlan.WorkflowPlanNode> dynamicNodes = new ArrayList<>();
        if (directives == null || directives.isEmpty() || parentPlan == null || triggerNode == null) {
            return dynamicNodes;
        }

        int order = startOrder;
        int nextPlanVersion = parentPlan.getPlanVersion() + 1;

        List<String> supplementCollectorNames = new ArrayList<>();
        boolean needsExtractBranch = false;
        boolean needsRewriteBranch = false;

        for (int index = 0; index < directives.size(); index++) {
            RevisionDirective directive = directives.get(index) == null
                    ? null
                    : directives.get(index).normalized();
            if (directive == null) {
                continue;
            }

            switch (directive.getOrchestrationAction()) {
                case "CREATE_SUPPLEMENT_BRANCH" -> {
                    supplementCollectorNames.add(addSupplementCollectorNode(
                            dynamicNodes,
                            triggerNode,
                            directive,
                            nextPlanVersion,
                            index + 1,
                            order++,
                            derivedBranchKey));
                    needsExtractBranch = true;
                    needsRewriteBranch = true;
                }
                case "CREATE_RERUN_BRANCH" -> {
                    needsExtractBranch = true;
                    needsRewriteBranch = true;
                }
                case "CREATE_REWRITE_BRANCH" -> needsRewriteBranch = true;
                default -> {
                    // MANUAL_ONLY 不创建自动化动态分支，只保留给人工动作入口消费。
                }
            }
        }

        if (needsExtractBranch) {
            order = appendExtractAnalyzeRewriteReviewChain(
                    dynamicNodes,
                    triggerNode,
                    supplementCollectorNames.isEmpty() ? List.of(triggerNode.getNodeName()) : List.copyOf(supplementCollectorNames),
                    nextPlanVersion,
                    order,
                    derivedBranchKey);
            return dynamicNodes;
        }

        if (needsRewriteBranch) {
            appendRewriteReviewChain(
                    dynamicNodes,
                    triggerNode,
                    List.of(triggerNode.getNodeName()),
                    nextPlanVersion,
                    order,
                    derivedBranchKey);
        }

        return dynamicNodes;
    }

    private String addSupplementCollectorNode(List<WorkflowPlan.WorkflowPlanNode> dynamicNodes,
                                              TaskNode triggerNode,
                                              RevisionDirective directive,
                                              int planVersion,
                                              int index,
                                              int executionOrder,
                                              String derivedBranchKey) {
        String nodeName = "collect_revision_evidence_v" + planVersion + "_" + index;
        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(nodeName)
                .displayName("补充证据采集")
                .agentType(AgentType.COLLECTOR.name())
                .dependsOn(List.of(triggerNode.getNodeName()))
                .required(true)
                .executionOrder(executionOrder)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("sourceType", "SUPPLEMENTAL");
                    put("searchQueries", directive.getSearchQueries());
                    put("sourceUrls", directive.getSourceUrls());
                    put("summary", directive.getSummary());
                    put("expectedOutcome", directive.getExpectedOutcome());
                }}))
                .notes("质量回流触发的动态补证分支")
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());
        return nodeName;
    }

    private int appendExtractAnalyzeRewriteReviewChain(List<WorkflowPlan.WorkflowPlanNode> dynamicNodes,
                                                       TaskNode triggerNode,
                                                       List<String> extractDependencies,
                                                       int planVersion,
                                                       int startOrder,
                                                       String derivedBranchKey) {
        String extractNodeName = "extract_revision_patch_v" + planVersion;
        String analyzeNodeName = "analyze_revision_patch_v" + planVersion;
        String rewriteNodeName = "rewrite_revision_patch_v" + planVersion;
        String reviewNodeName = "quality_check_revision_patch_v" + planVersion;
        int order = startOrder;

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(extractNodeName)
                .displayName("补证后结构化抽取")
                .agentType(AgentType.EXTRACTOR.name())
                .dependsOn(extractDependencies)
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("mode", "dynamic_patch");
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(analyzeNodeName)
                .displayName("补证后重新分析")
                .agentType(AgentType.ANALYZER.name())
                .dependsOn(List.of(extractNodeName))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("mode", "dynamic_patch");
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        return appendRewriteReviewChain(
                dynamicNodes,
                triggerNode,
                List.of(analyzeNodeName),
                planVersion,
                order,
                derivedBranchKey);
    }

    private int appendRewriteReviewChain(List<WorkflowPlan.WorkflowPlanNode> dynamicNodes,
                                         TaskNode triggerNode,
                                         List<String> rewriteDependencies,
                                         int planVersion,
                                         int startOrder,
                                         String derivedBranchKey) {
        String rewriteNodeName = "rewrite_revision_patch_v" + planVersion;
        String reviewNodeName = "quality_check_revision_patch_v" + planVersion;
        int order = startOrder;

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(rewriteNodeName)
                .displayName("动态回流改写报告")
                .agentType(AgentType.WRITER.name())
                .dependsOn(rewriteDependencies)
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("mode", "revision");
                    put("dynamicPatch", true);
                    put("sourceNode", triggerNode.getNodeName());
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(reviewNodeName)
                .displayName("动态回流复核")
                .agentType(AgentType.REVIEWER.name())
                .dependsOn(List.of(rewriteNodeName))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("qualityPolicy", "dynamic patch review");
                    put("sourceNode", rewriteNodeName);
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        return order;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize dynamic node config failed", e);
        }
    }
}
