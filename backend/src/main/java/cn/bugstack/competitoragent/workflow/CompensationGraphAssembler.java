package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Object> targetCoverageConfig = new LinkedHashMap<>();
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
                    copyTargetCoverageConfig(targetCoverageConfig, directive);
                }
                case "CREATE_RERUN_BRANCH" -> {
                    needsExtractBranch = true;
                    needsRewriteBranch = true;
                    copyTargetCoverageConfig(targetCoverageConfig, directive);
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
                    writeJson(targetCoverageConfig),
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

    /**
     * 基于 Orchestrator 已校验的动态计划变更生成节点。
     * P1 先复用现有补图模板，确保新决策不会绕过 DAG 执行层。
     */
    public List<WorkflowPlan.WorkflowPlanNode> assembleDynamicNodes(TaskPlan parentPlan,
                                                                    TaskNode triggerNode,
                                                                    DynamicPlanMutation rawMutation,
                                                                    int startOrder,
                                                                    String derivedBranchKey) {
        if (rawMutation == null || parentPlan == null || triggerNode == null) {
            return List.of();
        }
        DynamicPlanMutation mutation = rawMutation.normalized();
        if (!"APPEND_NODES".equals(mutation.getMutationType())) {
            return List.of();
        }

        List<WorkflowPlan.WorkflowPlanNode> dynamicNodes = new ArrayList<>();
        int order = startOrder;
        int planVersion = parentPlan.getPlanVersion() + 1;
        List<String> collectorDependencies = new ArrayList<>();
        String targetCoverageConfig = buildTargetCoverageGateConfig(mutation);
        if ("CREATE_SUPPLEMENT_BRANCH".equals(mutation.getDynamicAction())) {
            int collectorCount = Math.max(1, mutation.getNodeTemplates().size());
            for (int index = 0; index < collectorCount; index++) {
                WorkflowPlan.WorkflowPlanNode template = mutation.getNodeTemplates().isEmpty()
                        ? null
                        : mutation.getNodeTemplates().get(index);
                String nodeName = "collect_revision_evidence_v" + planVersion + "_" + (index + 1);
                dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName(nodeName)
                        .displayName(template == null || template.getDisplayName() == null
                                ? "补充证据采集"
                                : template.getDisplayName())
                        .agentType(AgentType.COLLECTOR.name())
                        .dependsOn(List.of(triggerNode.getNodeName()))
                        .required(true)
                        .executionOrder(order++)
                        .nodeConfig(template == null || template.getNodeConfig() == null
                                ? "{}"
                                : template.getNodeConfig())
                        .notes("Orchestrator 决策触发的动态补证分支")
                        .branchKey(derivedBranchKey)
                        .dynamicNode(true)
                        .originNodeName(triggerNode.getNodeName())
                        .build());
                collectorDependencies.add(nodeName);
            }
            appendExtractAnalyzeRewriteReviewChain(
                    dynamicNodes,
                    triggerNode,
                    collectorDependencies,
                    planVersion,
                    order,
                    targetCoverageConfig,
                    derivedBranchKey);
            return dynamicNodes;
        }
        if ("CREATE_REWRITE_BRANCH".equals(mutation.getDynamicAction())) {
            appendRewriteReviewChain(
                    dynamicNodes,
                    triggerNode,
                    List.of(triggerNode.getNodeName()),
                    planVersion,
                    order,
                    derivedBranchKey);
            return dynamicNodes;
        }
        if ("CREATE_RERUN_BRANCH".equals(mutation.getDynamicAction())) {
            WorkflowPlan.WorkflowPlanNode extractorTemplate = mutation.getNodeTemplates().stream()
                    .filter(template -> AgentType.EXTRACTOR.name().equals(template.getAgentType()))
                    .findFirst()
                    .orElse(null);
            if (extractorTemplate == null) {
                return List.of();
            }
            String extractorName = "extract_revision_patch_v" + planVersion;
            dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                    .nodeName(extractorName)
                    .displayName(extractorTemplate.getDisplayName())
                    .agentType(AgentType.EXTRACTOR.name())
                    .dependsOn(List.of(triggerNode.getNodeName()))
                    .required(true)
                    .executionOrder(order++)
                    .nodeConfig(extractorTemplate.getNodeConfig())
                    .notes(extractorTemplate.getNotes())
                    .branchKey(derivedBranchKey)
                    .dynamicNode(true)
                    .originNodeName(triggerNode.getNodeName())
                    .build());
            // Day 3 起 RERUN 只能从白名单 Extractor 进入同一套目标覆盖门禁和固定回流链，
            // 禁止再停留在“只物化 Extractor”导致后续 Writer 路由不确定。
            appendCoverageAnalyzeRewriteCitationReviewChain(
                    dynamicNodes,
                    triggerNode,
                    extractorName,
                    planVersion,
                    order,
                    targetCoverageConfig,
                    derivedBranchKey);
            return dynamicNodes;
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
                    put("competitor", directive.getCompetitor());
                    put("targetField", directive.getTargetField());
                    put("requiredSourceType", directive.getRequiredSourceType());
                    put("gapKey", directive.getGapKey());
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
                                                       String targetCoverageConfig,
                                                       String derivedBranchKey) {
        String extractNodeName = "extract_revision_patch_v" + planVersion;
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

        return appendCoverageAnalyzeRewriteCitationReviewChain(
                dynamicNodes,
                triggerNode,
                extractNodeName,
                planVersion,
                order,
                targetCoverageConfig,
                derivedBranchKey);
    }

    private int appendCoverageAnalyzeRewriteCitationReviewChain(List<WorkflowPlan.WorkflowPlanNode> dynamicNodes,
                                                                TaskNode triggerNode,
                                                                String extractorNodeName,
                                                                int planVersion,
                                                                int startOrder,
                                                                String targetCoverageConfig,
                                                                String derivedBranchKey) {
        String coverageGateNodeName = "target_coverage_gate_v" + planVersion;
        String analyzeNodeName = "analyze_revision_patch_v" + planVersion;
        int order = startOrder;

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(coverageGateNodeName)
                .displayName("目标覆盖门禁")
                .agentType(AgentType.REVIEWER.name())
                .dependsOn(List.of(extractorNodeName))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(targetCoverageConfig == null || targetCoverageConfig.isBlank()
                        ? "{}"
                        : targetCoverageConfig)
                .notes("确定性校验补采或重跑是否关闭指定 gapKey；未关闭时停止在人工接管。")
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(analyzeNodeName)
                .displayName("补证后重新分析")
                .agentType(AgentType.ANALYZER.name())
                .dependsOn(List.of(coverageGateNodeName))
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
        String citationNodeName = "citation_check_revision_patch_v" + planVersion;
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
                .nodeName(citationNodeName)
                .displayName("动态回流引用核查")
                .agentType(AgentType.CITATION.name())
                .dependsOn(List.of(rewriteNodeName))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("mode", "revision");
                    put("sourceNode", rewriteNodeName);
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        dynamicNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName(reviewNodeName)
                .displayName("动态回流复核")
                .agentType(AgentType.REVIEWER.name())
                .dependsOn(List.of(citationNodeName))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(writeJson(new LinkedHashMap<>() {{
                    put("qualityPolicy", "dynamic patch review");
                    put("sourceNode", citationNodeName);
                }}))
                .branchKey(derivedBranchKey)
                .dynamicNode(true)
                .originNodeName(triggerNode.getNodeName())
                .build());

        return order;
    }

    private void copyTargetCoverageConfig(Map<String, Object> config, RevisionDirective directive) {
        if (config == null || directive == null) {
            return;
        }
        putIfPresent(config, "competitor", directive.getCompetitor());
        putIfPresent(config, "targetField", directive.getTargetField());
        putIfPresent(config, "requiredSourceType", directive.getRequiredSourceType());
        putIfPresent(config, "gapKey", directive.getGapKey());
        putIfPresent(config, "summary", directive.getSummary());
        putIfNotEmpty(config, "sourceUrls", directive.getSourceUrls());
    }

    private String buildTargetCoverageGateConfig(DynamicPlanMutation mutation) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (mutation == null) {
            return writeJson(config);
        }
        putIfPresent(config, "decisionId", mutation.getDecisionId());
        putIfPresent(config, "mutationId", mutation.getMutationId());
        putIfPresent(config, "dynamicAction", mutation.getDynamicAction());
        putIfNotEmpty(config, "sourceUrls", mutation.getSourceUrls());
        for (WorkflowPlan.WorkflowPlanNode template : mutation.getNodeTemplates()) {
            JsonMap jsonMap = readJsonMap(template == null ? null : template.getNodeConfig());
            if (jsonMap.values().isEmpty()) {
                continue;
            }
            for (String key : List.of("competitor", "targetField", "requiredSourceType",
                    "gapKey", "sourceSnapshot", "aiAuditTraceId", "reason", "summary")) {
                Object value = jsonMap.values().get(key);
                if (value != null) {
                    config.putIfAbsent(key, value);
                }
            }
        }
        return writeJson(config);
    }

    private JsonMap readJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new JsonMap(Map.of());
        }
        try {
            return new JsonMap(objectMapper.readValue(rawJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception ignored) {
            return new JsonMap(Map.of());
        }
    }

    private void putIfPresent(Map<String, Object> config, String key, String value) {
        if (config == null || key == null || value == null || value.isBlank()) {
            return;
        }
        config.put(key, value);
    }

    private void putIfNotEmpty(Map<String, Object> config, String key, List<String> values) {
        if (config == null || key == null || values == null || values.isEmpty()) {
            return;
        }
        config.put(key, values);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize dynamic node config failed", e);
        }
    }

    private record JsonMap(Map<String, Object> values) {
    }
}
