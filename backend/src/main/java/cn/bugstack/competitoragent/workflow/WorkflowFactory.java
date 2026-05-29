package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 工作流工厂，负责把任务配置翻译成可执行的 DAG 节点列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowFactory {

    private final TaskNodeRepository nodeRepository;
    private final AnalysisSchemaRepository schemaRepository;
    private final SourceDiscoveryService sourceDiscoveryService;
    private final WorkflowPlanValidator workflowPlanValidator;
    private final ObjectMapper objectMapper;

    /**
     * 根据规划结果创建并落库存量节点，供执行器后续顺序消费。
     */
    public List<TaskNode> createWorkflow(AnalysisTask task) {
        WorkflowPlan plan = buildPlan(task);
        workflowPlanValidator.validate(plan);
        List<TaskNode> nodes = new ArrayList<>();
        for (WorkflowPlan.WorkflowPlanNode planNode : plan.getNodes()) {
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
                    .build());
        }

        List<TaskNode> savedNodes = nodeRepository.saveAll(nodes);
        log.info("create workflow success, taskId={}, schemaId={}, nodeCount={}",
                task.getId(), task.getSchemaId(), savedNodes.size());
        return savedNodes;
    }

    /**
     * V2 的工作流规划入口。
     * 这里会先根据竞品和来源范围展开多个采集节点，再接上抽取、分析、撰写、质检与重写闭环。
     */
    public WorkflowPlan buildPlan(AnalysisTask task) {
        List<String> competitorNames = parseStringList(task.getCompetitorNames());
        List<String> competitorUrls = parseStringList(task.getCompetitorUrls());
        List<String> dimensions = resolveDimensions(task);
        List<String> requestedScopes = parseStringList(task.getSourceScope());
        Optional<AnalysisSchema> schema = resolveSchema(task.getSchemaId());

        List<WorkflowPlan.WorkflowPlanNode> planNodes = new ArrayList<>();
        List<String> collectNodeNames = new ArrayList<>();
        int order = 0;

        for (int competitorIndex = 0; competitorIndex < competitorNames.size(); competitorIndex++) {
            String competitorName = competitorNames.get(competitorIndex);
            List<String> providedUrls = resolveCompetitorProvidedUrls(
                    competitorNames, competitorUrls, competitorIndex);
            List<SourceDiscoveryService.SourcePlan> sourcePlans = sourceDiscoveryService.discover(
                    competitorName,
                    providedUrls,
                    requestedScopes);

            // 按信息源计划动态展开采集节点，让 DAG 随竞品数量和来源范围变化。
            for (int planIndex = 0; planIndex < sourcePlans.size(); planIndex++) {
                SourceDiscoveryService.SourcePlan sourcePlan = sourcePlans.get(planIndex);
                String nodeName = String.format("collect_sources_%02d_%02d", competitorIndex + 1, planIndex + 1);
                collectNodeNames.add(nodeName);

                planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName(nodeName)
                        .displayName(competitorName + " - " + sourcePlan.getSourceType() + "采集")
                        .agentType(AgentType.COLLECTOR.name())
                        .dependsOn(Collections.emptyList())
                        .required(true)
                        .executionOrder(order++)
                        .nodeConfig(toJson(orderedMap(
                                "competitorName", competitorName,
                                "competitorUrls", sourcePlan.getUrls(),
                                "sourceType", sourcePlan.getSourceType(),
                                "sourceScope", requestedScopes,
                                "schemaName", schema.map(AnalysisSchema::getName).orElse(null),
                                "discoveryNotes", sourcePlan.getNotes()
                        )))
                        .notes("由信息源发现策略自动生成")
                        .build());
            }
        }

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("extract_schema")
                .displayName("竞品结构化抽取")
                .agentType(AgentType.EXTRACTOR.name())
                .dependsOn(collectNodeNames)
                .required(true)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "dimensions", dimensions,
                        "schemaId", task.getSchemaId()
                )))
                .notes("聚合证据并保证来源可追溯")
                .build());

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("analyze_competitors")
                .displayName("竞品综合分析")
                .agentType(AgentType.ANALYZER.name())
                .dependsOn(List.of("extract_schema"))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "competitorCount", competitorNames.size(),
                        "dimensionCount", dimensions.size()
                )))
                .build());

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("write_report")
                .displayName("生成分析报告")
                .agentType(AgentType.WRITER.name())
                .dependsOn(List.of("analyze_competitors"))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "reportLanguage", task.getReportLanguage(),
                        "reportTemplate", task.getReportTemplate(),
                        "mode", "initial"
                )))
                .build());

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("quality_check")
                .displayName("报告质量初审")
                .agentType(AgentType.REVIEWER.name())
                .dependsOn(List.of("write_report"))
                .required(true)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "qualityPolicy", "score>=80 and no ERROR issues",
                        "outputPlan", "revision_plan"
                )))
                .build());

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("rewrite_report")
                .displayName("根据评审改写报告")
                .agentType(AgentType.WRITER.name())
                .dependsOn(List.of("quality_check"))
                .required(false)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "mode", "revision",
                        "sourceNode", "quality_check",
                        "trigger", "review_failed"
                )))
                .notes("仅当初审要求改写时执行")
                .build());

        planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                .nodeName("quality_check_final")
                .displayName("报告终审复核")
                .agentType(AgentType.REVIEWER.name())
                .dependsOn(List.of("rewrite_report"))
                .required(false)
                .executionOrder(order++)
                .nodeConfig(toJson(orderedMap(
                        "qualityPolicy", "final pass after revision",
                        "sourceNode", "rewrite_report",
                        "trigger", "rewrite_executed"
                )))
                .notes("仅在改写完成后执行，用于闭环复核")
                .build());

        WorkflowPlan plan = WorkflowPlan.builder().nodes(planNodes).build();
        workflowPlanValidator.validate(plan);
        return plan;
    }

    public void resetWorkflow(Long taskId) {
        nodeRepository.deleteAll(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
    }

    private Optional<AnalysisSchema> resolveSchema(Long schemaId) {
        if (schemaId == null) {
            return Optional.empty();
        }
        return schemaRepository.findById(schemaId);
    }

    /**
     * 维度解析优先级：任务显式传入 > 选中 Schema > 系统默认兜底。
     */
    private List<String> resolveDimensions(AnalysisTask task) {
        List<String> dimensions = parseStringList(task.getAnalysisDimensions());
        if (!dimensions.isEmpty()) {
            return dimensions;
        }

        Optional<AnalysisSchema> schema = resolveSchema(task.getSchemaId());
        if (schema.isPresent() && StringUtils.hasText(schema.get().getDimensions())) {
            List<String> schemaDimensions = parseStringList(schema.get().getDimensions());
            if (!schemaDimensions.isEmpty()) {
                return schemaDimensions;
            }
        }

        // 最后使用系统兜底维度，避免预览或执行阶段出现空分析链路。
        return List.of(
                "产品功能",
                "目标用户",
                "价格策略",
                "技术能力",
                "市场定位"
        );
    }

    private List<String> parseStringList(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse json array failed: {}", rawJson, e);
            return List.of();
        }
    }

    /**
     * 兼容两种 URL 输入方式：
     * 1. 用户给每个竞品分别填写官网 URL；
     * 2. 用户只给一组公共 URL，此时让当前竞品共享这组候选来源。
     */
    private List<String> resolveCompetitorProvidedUrls(List<String> competitorNames,
                                                       List<String> competitorUrls,
                                                       int competitorIndex) {
        if (competitorUrls.isEmpty()) {
            return List.of();
        }
        if (competitorUrls.size() == competitorNames.size()) {
            String matchedUrl = competitorUrls.get(competitorIndex);
            return StringUtils.hasText(matchedUrl) ? List.of(matchedUrl) : List.of();
        }
        return competitorUrls.stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize workflow node config failed", e);
        }
    }

    /**
     * 用有序 Map 保证前端展示配置时字段顺序稳定，减少 trace 面板阅读噪音。
     */
    private LinkedHashMap<String, Object> orderedMap(Object... kvPairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }
}
