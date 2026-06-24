package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.orchestration.AgentRoleAssignment;
import cn.bugstack.competitoragent.orchestration.CollaborationPlan;
import cn.bugstack.competitoragent.orchestration.InitialPlanReview;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 正式执行计划定义构建器先产出“业务计划语义”，
 * 然后再交给 WorkflowPlanAssembler 投影成技术快照。
 * 这样 preview/create/runtime 围绕的是同一份 ExecutionPlanDefinition，
 * 而不是分别在不同层里再拼一遍阶段、节点和 collector 计划。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionPlanDefinitionBuilder {

    private final cn.bugstack.competitoragent.repository.AnalysisSchemaRepository schemaRepository;
    private final SourceDiscoveryService sourceDiscoveryService;
    private final SourceCandidateRanker sourceCandidateRanker;
    private final ObjectMapper objectMapper;
    private final CollectorPlanTemplateFactory collectorPlanTemplateFactory;

    public ExecutionPlanDefinition build(AnalysisTask task, boolean previewOnly) {
        return build(task, previewOnly, null, null);
    }

    public ExecutionPlanDefinition build(AnalysisTask task,
                                         boolean previewOnly,
                                         CollaborationPlan collaborationPlan,
                                         InitialPlanReview initialPlanReview) {
        List<String> competitorNames = parseStringList(task.getCompetitorNames());
        List<String> competitorUrls = parseStringList(task.getCompetitorUrls());
        List<String> dimensions = resolveDimensions(task);
        List<String> requestedScopes = parseStringList(task.getSourceScope());
        Optional<AnalysisSchema> schema = resolveSchema(task.getSchemaId());
        CollaborationProjection collaborationProjection = resolveCollaborationProjection(collaborationPlan, initialPlanReview);

        List<ExecutionPlanDefinition.NodeDefinition> nodes = new ArrayList<>();
        List<String> collectNodeNames = new ArrayList<>();
        List<String> collectorSourceUrls = new ArrayList<>();
        int order = 0;

        for (int competitorIndex = 0; competitorIndex < competitorNames.size(); competitorIndex++) {
            String competitorName = competitorNames.get(competitorIndex);
            List<String> providedUrls = resolveCompetitorProvidedUrls(
                    competitorNames, competitorUrls, competitorIndex);
            List<SourcePlan> sourcePlans = previewOnly
                    ? sourceDiscoveryService.discoverForPreview(competitorName, providedUrls, requestedScopes)
                    : sourceDiscoveryService.discover(competitorName, providedUrls, requestedScopes);
            sourcePlans = deduplicateSourcePlans(sourcePlans);

            /**
             * 这里在规划阶段就展开 collector 分支，
             * 保证 preview/create/runtime 三条链路对“采集会怎么执行”说的是同一种语言。
             */
            for (int planIndex = 0; planIndex < sourcePlans.size(); planIndex++) {
                SourcePlan sourcePlan = sourcePlans.get(planIndex);
                String nodeName = String.format("collect_sources_%02d_%02d", competitorIndex + 1, planIndex + 1);
                List<String> nodeSourceUrls = mergeSourceUrls(providedUrls, sourcePlan);
                CollectorNodeConfig collectorNodeConfig = collectorPlanTemplateFactory.createCollectorNodeConfig(
                        competitorName,
                        requestedScopes,
                        schema.map(AnalysisSchema::getName).orElse(null),
                        sourcePlan
                );

                collectNodeNames.add(nodeName);
                collectorSourceUrls.addAll(nodeSourceUrls);
                nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                        .nodeName(nodeName)
                        .displayName(competitorName + " - " + sourcePlan.getSourceType() + "采集")
                        .agentType("COLLECTOR")
                        .notes(buildCollectorNodeNotes(sourcePlan))
                        .stageCode("SOURCE_STRATEGY")
                        .goal(buildCollectorNodeGoal(sourcePlan))
                        .summary(buildCollectorNodeSummary(competitorName, requestedScopes, sourcePlan))
                        .dependsOn(Collections.emptyList())
                        .required(true)
                        .allowFailedDependency(false)
                        .retryable(true)
                        .maxRetries(3)
                        .executionOrder(order++)
                        .nodeConfig(toJson(collectorNodeConfig))
                        .fallbackOrder(defaultIfEmpty(collectorNodeConfig.getSearchFallbackOrder(), List.of()))
                        .sourceUrls(nodeSourceUrls)
                        .build());
            }
        }

        List<String> normalizedCollectorSourceUrls = distinctNonBlank(collectorSourceUrls);

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("extract_schema")
                .displayName("竞品结构化提取")
                .agentType("EXTRACTOR")
                .stageCode("EXTRACT")
                .goal("将采集证据抽取为结构化字段")
                .summary(buildExtractSummary(dimensions))
                .dependsOn(collectNodeNames)
                .required(true)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "dimensions", dimensions,
                        "schemaId", task.getSchemaId()
                ), collaborationProjection, "EXTRACTOR")))
                .notes("聚合采集证据，并保持 sourceUrls 可追溯")
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("analyze_competitors")
                .displayName("竞品综合分析")
                .agentType("ANALYZER")
                .stageCode("ANALYZE")
                .goal("汇总竞品信息并完成横向对比")
                .summary("基于结构化字段输出竞品分析结论")
                .dependsOn(List.of("extract_schema"))
                .required(true)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "competitorCount", competitorNames.size(),
                        "dimensionCount", dimensions.size()
                ), collaborationProjection, "ANALYZER")))
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("write_report")
                .displayName("生成分析报告")
                .agentType("WRITER")
                .stageCode("DELIVER")
                .goal("生成可交付的分析报告")
                .summary("按报告语言与模板输出首版报告")
                .dependsOn(List.of("analyze_competitors"))
                .required(true)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "reportLanguage", task.getReportLanguage(),
                        "reportTemplate", task.getReportTemplate(),
                        "mode", "initial"
                ), collaborationProjection, "WRITER")))
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("quality_check")
                .displayName("报告质量初审")
                .agentType("REVIEWER")
                .stageCode("DELIVER")
                .goal("检查报告是否满足交付要求")
                .summary("对首版报告执行质量审核并输出修订计划")
                .dependsOn(List.of("write_report"))
                .required(true)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "qualityPolicy", "score>=80 and no ERROR issues",
                        "outputPlan", "revision_plan"
                ), collaborationProjection, "REVIEWER")))
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("rewrite_report")
                .displayName("根据评审改写报告")
                .agentType("WRITER")
                .notes("仅当初审要求改写时执行")
                .stageCode("DELIVER")
                .goal("按修订建议补强报告结论")
                .summary("仅在初审失败时进入改写闭环")
                .dependsOn(List.of("quality_check"))
                .required(false)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "mode", "revision",
                        "sourceNode", "quality_check",
                        "trigger", "review_failed"
                ), collaborationProjection, "WRITER")))
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        nodes.add(ExecutionPlanDefinition.NodeDefinition.builder()
                .nodeName("quality_check_final")
                .displayName("报告终审复核")
                .agentType("REVIEWER")
                .notes("仅在改写完成后执行，用于闭环复核")
                .stageCode("DELIVER")
                .goal("确认改写后的报告满足最终交付标准")
                .summary("仅在执行过改写后进入终审复核")
                .dependsOn(List.of("rewrite_report"))
                .required(false)
                .allowFailedDependency(false)
                .retryable(true)
                .maxRetries(3)
                .executionOrder(order++)
                .nodeConfig(toJson(withCollaborationConfig(orderedMap(
                        "qualityPolicy", "final pass after revision",
                        "sourceNode", "rewrite_report",
                        "trigger", "rewrite_executed"
                ), collaborationProjection, "REVIEWER")))
                .fallbackOrder(List.of())
                .sourceUrls(normalizedCollectorSourceUrls)
                .build());

        List<ExecutionPlanDefinition.StageDefinition> stages = buildStages(
                task,
                competitorNames,
                competitorUrls,
                requestedScopes,
                dimensions,
                collectNodeNames.size(),
                normalizedCollectorSourceUrls
        );

        List<String> planSourceUrls = new ArrayList<>(competitorUrls);
        planSourceUrls.addAll(normalizedCollectorSourceUrls);

        int collectorCount = (int) nodes.stream().filter(node -> "COLLECTOR".equals(node.getAgentType())).count();
        return ExecutionPlanDefinition.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal(buildTaskGoal(task))
                .competitorCount(competitorNames.size())
                .collectorCount(collectorCount)
                .pipelineCount(nodes.size() - collectorCount)
                .stages(stages)
                .nodes(nodes)
                .sourceUrls(distinctNonBlank(planSourceUrls))
                .build();
    }

    /**
     * 阶段摘要面向业务解释“任务如何被执行”，
     * 让 preview 和落库快照都能共享同一份正式阶段语义。
     */
    private List<ExecutionPlanDefinition.StageDefinition> buildStages(AnalysisTask task,
                                                                      List<String> competitorNames,
                                                                      List<String> competitorUrls,
                                                                      List<String> requestedScopes,
                                                                      List<String> dimensions,
                                                                      int collectorCount,
                                                                      List<String> collectorSourceUrls) {
        List<ExecutionPlanDefinition.StageDefinition> stages = new ArrayList<>();
        List<String> goalSourceUrls = distinctNonBlank(competitorUrls);

        stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                .stageCode("GOAL")
                .title("明确任务目标")
                .summary(defaultIfBlank(task.getTaskName(), "待补充分析主题"))
                .detail(buildTaskGoal(task))
                .sourceUrls(goalSourceUrls)
                .build());

        stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                .stageCode("SOURCE_STRATEGY")
                .title("规划来源策略")
                .summary(buildSourceStrategyStageSummary(competitorNames, requestedScopes))
                .detail(buildSourceStrategyStageDetail(collectorCount))
                .sourceUrls(collectorSourceUrls)
                .build());

        if (collectorCount > 0) {
            stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                    .stageCode("COLLECT")
                    .title("并行采集资料")
                    .summary("按竞品与来源范围展开采集分支，正式执行时并行收集证据")
                    .detail("采集分支会优先使用已知入口，不足时再按 fallback 顺序补源")
                    .sourceUrls(collectorSourceUrls)
                    .build());
        }

        stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                .stageCode("EXTRACT")
                .title("结构化提取")
                .summary(buildExtractSummary(dimensions))
                .detail("抽取结果必须保留 sourceUrls，便于后续分析、写作与审计回溯")
                .sourceUrls(collectorSourceUrls)
                .build());

        stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                .stageCode("ANALYZE")
                .title("汇总分析")
                .summary("基于结构化字段生成竞品对比结论")
                .detail("分析结果会围绕竞品数量、分析维度和证据充分性形成统一判断")
                .sourceUrls(collectorSourceUrls)
                .build());

        stages.add(ExecutionPlanDefinition.StageDefinition.builder()
                .stageCode("DELIVER")
                .title("输出与复核")
                .summary("生成报告并在必要时进入评审改写闭环")
                .detail("质量门槛未达标时，系统会自动生成修订计划并执行复核")
                .sourceUrls(collectorSourceUrls)
                .build());
        return stages;
    }

    private String buildTaskGoal(AnalysisTask task) {
        if (task == null || !StringUtils.hasText(task.getSubjectProduct())) {
            return "补充本方产品后，系统会生成正式任务目标";
        }
        return "围绕 " + task.getSubjectProduct().trim() + " 开展竞品研究";
    }

    private String buildSourceStrategyStageSummary(List<String> competitorNames, List<String> requestedScopes) {
        if (competitorNames == null || competitorNames.isEmpty()) {
            return "待补充竞品后再生成来源策略";
        }
        return "按 " + competitorNames.size() + " 个竞品规划资料入口，优先覆盖 "
                + summarizeValues(defaultIfEmpty(requestedScopes, List.of("官网", "产品文档")), 3);
    }

    private String buildSourceStrategyStageDetail(int collectorCount) {
        if (collectorCount <= 0) {
            return "待确认竞品与来源范围后，再生成可执行的采集分支";
        }
        return "当前计划已拆成 " + collectorCount + " 个采集分支，执行时将复用同一份正式计划快照";
    }

    private String buildCollectorNodeGoal(SourcePlan sourcePlan) {
        String sourceType = sourcePlan == null ? null : sourcePlan.getSourceType();
        if (!StringUtils.hasText(sourceType)) {
            return "优先覆盖已知入口，并在必要时补充公网搜索";
        }
        return "优先覆盖 " + sourceType + " 来源，并在必要时补充公网搜索";
    }

    private String buildCollectorNodeSummary(String competitorName,
                                             List<String> requestedScopes,
                                             SourcePlan sourcePlan) {
        List<String> scopes = defaultIfEmpty(requestedScopes, List.of(sourcePlan == null ? null : sourcePlan.getSourceType()));
        return defaultIfBlank(competitorName, "未命名竞品")
                + " 计划优先覆盖 "
                + summarizeValues(scopes, 3);
    }

    private String buildExtractSummary(List<String> dimensions) {
        List<String> effectiveDimensions = defaultIfEmpty(dimensions, List.of("产品功能", "目标用户", "价格策略"));
        return "将采集结果整理为结构化字段，重点覆盖 " + summarizeValues(effectiveDimensions, 3);
    }

    private List<String> mergeSourceUrls(List<String> providedUrls, SourcePlan sourcePlan) {
        List<String> sourceUrls = new ArrayList<>();
        if (providedUrls != null) {
            sourceUrls.addAll(providedUrls);
        }
        if (sourcePlan != null && sourcePlan.getUrls() != null) {
            sourceUrls.addAll(sourcePlan.getUrls());
        }
        if (sourcePlan != null && sourcePlan.getCandidates() != null) {
            for (SourceCandidate candidate : sourcePlan.getCandidates()) {
                if (candidate != null) {
                    sourceUrls.add(candidate.getUrl());
                }
            }
        }
        return distinctNonBlank(sourceUrls);
    }

    private List<String> distinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> defaultIfEmpty(List<String> values, List<String> defaults) {
        return values == null || values.isEmpty() ? defaults : values;
    }

    private String summarizeValues(List<String> values, int limit) {
        List<String> normalized = distinctNonBlank(values);
        if (normalized.isEmpty()) {
            return "待补充";
        }
        if (normalized.size() <= limit) {
            return String.join("、", normalized);
        }
        return String.join("、", normalized.subList(0, limit)) + " 等 " + normalized.size() + " 项";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
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
     * 1. 每个竞品单独填写一个官方入口；
     * 2. 用户只给一组公共入口，此时让所有竞品共享。
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

    private CollaborationProjection resolveCollaborationProjection(CollaborationPlan collaborationPlan,
                                                                   InitialPlanReview initialPlanReview) {
        if (collaborationPlan == null || initialPlanReview == null || !initialPlanReview.isAllowed()) {
            return null;
        }
        CollaborationPlan normalizedPlan = collaborationPlan.normalized();
        InitialPlanReview normalizedReview = initialPlanReview.normalized();
        if (!normalizedReview.isAllowed()) {
            return null;
        }
        Map<String, AgentRoleAssignment> rolesByType = new LinkedHashMap<>();
        for (AgentRoleAssignment role : normalizedPlan.getAgentRoleAssignments()) {
            rolesByType.put(role.getAgentType(), role);
        }
        return new CollaborationProjection(
                normalizedPlan.getGoalId(),
                normalizedPlan.getPlanId(),
                normalizedReview.getReviewId(),
                normalizedPlan.getCheckpoints(),
                rolesByType
        );
    }

    private LinkedHashMap<String, Object> withCollaborationConfig(LinkedHashMap<String, Object> nodeConfig,
                                                                  CollaborationProjection projection,
                                                                  String agentType) {
        if (projection == null) {
            return nodeConfig;
        }
        AgentRoleAssignment role = projection.roleFor(agentType);
        if (role == null) {
            return nodeConfig;
        }
        nodeConfig.put("collaborationGoalId", projection.goalId());
        nodeConfig.put("collaborationPlanId", projection.planId());
        nodeConfig.put("collaborationReviewId", projection.reviewId());
        nodeConfig.put("collaborationRoleId", role.getRoleId());
        nodeConfig.put("collaborationQualityGate", role.getQualityGate());
        nodeConfig.put("orchestratorCheckpoints", projection.checkpoints());
        return nodeConfig;
    }

    private List<SourcePlan> deduplicateSourcePlans(List<SourcePlan> sourcePlans) {
        if (sourcePlans == null || sourcePlans.isEmpty()) {
            return List.of();
        }

        List<SourcePlan> deduplicatedPlans = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (SourcePlan sourcePlan : sourcePlans) {
            if (sourcePlan == null) {
                continue;
            }

            List<SourceCandidate> rankedCandidates = sourceCandidateRanker.rankAndDeduplicate(sourcePlan.getCandidates());
            List<SourceCandidate> keptCandidates = new ArrayList<>();
            Set<String> planUrlKeys = new LinkedHashSet<>();
            Set<String> candidateUrlKeys = new LinkedHashSet<>();
            int duplicateCount = 0;

            for (String url : safeList(sourcePlan.getUrls())) {
                String normalizedUrl = normalizeUrl(url);
                if (!StringUtils.hasText(normalizedUrl)) {
                    continue;
                }
                if (seenUrls.contains(normalizedUrl) || !planUrlKeys.add(normalizedUrl)) {
                    duplicateCount++;
                    continue;
                }
            }

            for (SourceCandidate candidate : rankedCandidates) {
                String normalizedUrl = normalizeUrl(candidate == null ? null : candidate.getUrl());
                if (!StringUtils.hasText(normalizedUrl)) {
                    continue;
                }
                if (seenUrls.contains(normalizedUrl) || !candidateUrlKeys.add(normalizedUrl)) {
                    duplicateCount++;
                    continue;
                }
                keptCandidates.add(candidate);
            }

            LinkedHashMap<String, String> mergedUrls = new LinkedHashMap<>();
            for (String url : safeList(sourcePlan.getUrls())) {
                String normalizedUrl = normalizeUrl(url);
                if (planUrlKeys.contains(normalizedUrl)) {
                    mergedUrls.putIfAbsent(normalizedUrl, url);
                }
            }
            for (SourceCandidate candidate : keptCandidates) {
                String normalizedUrl = normalizeUrl(candidate.getUrl());
                if (candidateUrlKeys.contains(normalizedUrl)) {
                    mergedUrls.putIfAbsent(normalizedUrl, candidate.getUrl());
                }
            }

            if (mergedUrls.isEmpty() && keptCandidates.isEmpty()) {
                if (safeList(sourcePlan.getUrls()).isEmpty()
                        && (sourcePlan.getCandidates() == null || sourcePlan.getCandidates().isEmpty())) {
                    deduplicatedPlans.add(sourcePlan.toBuilder()
                            .urls(List.of())
                            .sourceUrls(List.of())
                            .notes(appendDedupeNote(sourcePlan.getNotes(), duplicateCount))
                            .candidates(List.of())
                            .build());
                    continue;
                }
                if (duplicateCount > 0) {
                    log.info("skip duplicated source plan, sourceType={}, duplicateCount={}",
                            sourcePlan.getSourceType(), duplicateCount);
                }
                continue;
            }

            seenUrls.addAll(mergedUrls.keySet());
            String notes = appendDedupeNote(sourcePlan.getNotes(), duplicateCount);
            List<String> deduplicatedUrls = new ArrayList<>(mergedUrls.values());
            deduplicatedPlans.add(sourcePlan.toBuilder()
                    .urls(deduplicatedUrls)
                    .sourceUrls(deduplicatedUrls)
                    .notes(notes)
                    .candidates(keptCandidates)
                    .build());
        }

        return deduplicatedPlans;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String appendDedupeNote(String notes, int duplicateCount) {
        if (duplicateCount <= 0) {
            return notes;
        }
        String dedupeNote = "已与前序范围去重 " + duplicateCount + " 条重复来源";
        if (!StringUtils.hasText(notes)) {
            return dedupeNote;
        }
        if (notes.contains(dedupeNote)) {
            return notes;
        }
        return notes + "；" + dedupeNote;
    }

    private String buildCollectorNodeNotes(SourcePlan sourcePlan) {
        if (sourcePlan == null || !StringUtils.hasText(sourcePlan.getNotes())) {
            return "由来源发现策略自动生成";
        }
        return "由来源发现策略自动生成；" + sourcePlan.getNotes();
    }

    private String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String host = StringUtils.hasText(uri.getHost()) ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() == null || uri.getPath().isBlank()
                    ? ""
                    : uri.getPath().replaceAll("/+$", "");
            return scheme + "://" + host + path;
        } catch (Exception e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 用有序 Map 保证前端展示配置时字段顺序稳定，同时兼容空值字段。
     */
    private LinkedHashMap<String, Object> orderedMap(Object... kvPairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }

    private record CollaborationProjection(String goalId,
                                           String planId,
                                           String reviewId,
                                           List<String> checkpoints,
                                           Map<String, AgentRoleAssignment> rolesByType) {

        private AgentRoleAssignment roleFor(String agentType) {
            return rolesByType.get(agentType);
        }
    }
}
