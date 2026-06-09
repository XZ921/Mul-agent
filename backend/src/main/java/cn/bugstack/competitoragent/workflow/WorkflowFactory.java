package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.repository.AnalysisSchemaRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
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
import java.util.Optional;
import java.util.Set;

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
    private final SourceCandidateRanker sourceCandidateRanker;
    private final WorkflowPlanValidator workflowPlanValidator;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;
    private final SearchBrowserProperties searchBrowserProperties;
    private final SearchProperties searchProperties;
    private final CollectorProperties collectorProperties;
    private final DynamicTaskGraphService dynamicTaskGraphService;

    /**
     * 根据规划结果创建并落库存量节点，供执行器后续顺序消费。
     */
    public List<TaskNode> createWorkflow(AnalysisTask task) {
        // 创建任务阶段仅固化轻量工作流结构，真实补源延迟到执行期 Collector 再触发。
        WorkflowPlan plan = buildPreviewPlan(task);
        workflowPlanValidator.validate(plan);
        TaskPlan initialPlan = dynamicTaskGraphService.ensureInitialPlan(task.getId(), plan);
        WorkflowPlan versionedPlan = enrichWorkflowPlan(plan, initialPlan);
        task.setCurrentPlanVersionId(initialPlan.getId());
        task.setCurrentPlanVersion(initialPlan.getPlanVersion());

        List<TaskNode> nodes = new ArrayList<>();
        for (WorkflowPlan.WorkflowPlanNode planNode : versionedPlan.getNodes()) {
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
                    .planVersionId(initialPlan.getId())
                    .branchKey(planNode.getBranchKey())
                    .dynamicNode(planNode.isDynamicNode())
                    .originNodeName(planNode.getOriginNodeName())
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
    private WorkflowPlan enrichWorkflowPlan(WorkflowPlan plan, TaskPlan taskPlan) {
        String branchKey = taskPlan == null || !StringUtils.hasText(taskPlan.getBranchKey())
                ? "root"
                : taskPlan.getBranchKey();
        List<WorkflowPlan.WorkflowPlanNode> versionedNodes = plan.getNodes().stream()
                .map(node -> node.toBuilder()
                        .branchKey(StringUtils.hasText(node.getBranchKey()) ? node.getBranchKey() : branchKey)
                        .build())
                .toList();
        return plan.toBuilder()
                .planVersionId(taskPlan == null ? null : taskPlan.getId())
                .planVersion(taskPlan == null || taskPlan.getPlanVersion() == null ? 1 : taskPlan.getPlanVersion())
                .parentPlanVersionId(taskPlan == null ? null : taskPlan.getParentPlanId())
                .branchKey(branchKey)
                .dynamicPlan(taskPlan != null && !"INITIAL".equalsIgnoreCase(taskPlan.getPlanType()))
                .nodes(versionedNodes)
                .build();
    }

    public WorkflowPlan buildPlan(AnalysisTask task) {
        return buildPlanInternal(task, false);
    }

    /**
     * 预览阶段只构建轻量 DAG，避免在表单编辑时阻塞等待实时搜索结果。
     */
    public WorkflowPlan buildPreviewPlan(AnalysisTask task) {
        return buildPlanInternal(task, true);
    }

    private WorkflowPlan buildPlanInternal(AnalysisTask task, boolean previewOnly) {
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
            List<SourcePlan> sourcePlans = previewOnly
                    ? sourceDiscoveryService.discoverForPreview(competitorName, providedUrls, requestedScopes)
                    : sourceDiscoveryService.discover(competitorName, providedUrls, requestedScopes);
            sourcePlans = deduplicateSourcePlans(sourcePlans);

            // 按信息源计划动态展开采集节点，让 DAG 随竞品数量和来源范围变化。
            for (int planIndex = 0; planIndex < sourcePlans.size(); planIndex++) {
                SourcePlan sourcePlan = sourcePlans.get(planIndex);
                String nodeName = String.format("collect_sources_%02d_%02d", competitorIndex + 1, planIndex + 1);
                collectNodeNames.add(nodeName);
                CollectorNodeConfig collectorNodeConfig = buildCollectorNodeConfig(
                        competitorName,
                        requestedScopes,
                        schema.map(AnalysisSchema::getName).orElse(null),
                        sourcePlan
                );

                planNodes.add(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName(nodeName)
                        .displayName(competitorName + " - " + sourcePlan.getSourceType() + "采集")
                        .agentType(AgentType.COLLECTOR.name())
                        .dependsOn(Collections.emptyList())
                        .required(true)
                        .executionOrder(order++)
                        .nodeConfig(toJson(collectorNodeConfig))
                        .notes(buildCollectorNodeNotes(sourcePlan))
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
                    // 预览阶段会保留“执行时补源”的占位计划，不能因为当前没有候选 URL 就被去掉。
                    deduplicatedPlans.add(SourcePlan.builder()
                            .sourceType(sourcePlan.getSourceType())
                            .urls(List.of())
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
            deduplicatedPlans.add(SourcePlan.builder()
                    .sourceType(sourcePlan.getSourceType())
                    .urls(new ArrayList<>(mergedUrls.values()))
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
            return "由信息源发现策略自动生成";
        }
        return "由信息源发现策略自动生成；" + sourcePlan.getNotes();
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
     * 采集节点配置在规划阶段一次性写全，便于后续搜索执行、进度展示和前端计划预览复用同一份契约。
     */
    private CollectorNodeConfig buildCollectorNodeConfig(String competitorName,
                                                         List<String> requestedScopes,
                                                         String schemaName,
                                                         SourcePlan sourcePlan) {
        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .competitorUrls(sourcePlan.getUrls())
                .sourceType(sourcePlan.getSourceType())
                .sourceScope(requestedScopes)
                .schemaName(schemaName)
                .discoveryNotes(sourcePlan.getNotes())
                .sourceCandidates(sourcePlan.getCandidates())
                .searchMode(resolveSearchMode())
                .searchQueries(buildDefaultSearchQueries(competitorName, sourcePlan.getSourceType(), sourcePlan.getCandidates()))
                .searchFallbackOrder(buildSearchFallbackOrder())
                .verifyCandidates(Boolean.TRUE)
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .minVerifiedCandidates(Math.min(2, Math.max(1, sourcePlan.getUrls() == null ? 1 : sourcePlan.getUrls().size())))
                .preferredDomains(buildPreferredDomains(sourcePlan.getCandidates()))
                .blockedDomains(List.of())
                .browserSearchEnabled(isBrowserSearchEnabledForMode(resolveSearchMode()))
                .maxSearchResults(resolveMaxSearchResults(sourcePlan))
                .searchTimeoutMillis(15000L)
                .searchRuntimePolicy(buildDefaultSearchRuntimePolicy())
                .searchExecutionPlan(buildDefaultSearchExecutionPlan())
                .build();
    }

    private SearchRuntimePolicy buildDefaultSearchRuntimePolicy() {
        List<String> defaultUserAgents = new ArrayList<>();
        if (StringUtils.hasText(collectorProperties.getUserAgent())) {
            defaultUserAgents.add(collectorProperties.getUserAgent());
        }
        if (searchBrowserProperties.getUserAgents() != null) {
            for (String userAgent : searchBrowserProperties.getUserAgents()) {
                if (StringUtils.hasText(userAgent) && !defaultUserAgents.contains(userAgent)) {
                    defaultUserAgents.add(userAgent);
                }
            }
        }
        return SearchRuntimePolicy.builder()
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .maxRetries(2)
                .minIntervalMillis(3000L)
                .maxSearchesPerTask(10)
                .pageTimeoutMillis(Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000))
                .maxOpenResultPages(searchBrowserProperties.getMaxOpenResultPages())
                .resultPageTimeoutMillis(searchBrowserProperties.getResultPageTimeoutMillis())
                .maxContentLengthPerPage(searchBrowserProperties.getMaxContentLengthPerPage())
                .userAgents(defaultUserAgents)
                .blockedSignals(List.of(
                        "captcha",
                        "unusual traffic",
                        "verify you are human",
                        "access denied",
                        "robot check"
                ))
                .recoveryHint("如搜索中断，优先从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查。")
                .build();
    }

    private List<String> buildSearchFallbackOrder() {
        return switch (resolveSearchMode()) {
            case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
            case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
            case "HEURISTIC_ONLY" -> List.of("PLANNED", "HEURISTIC");
            default -> List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP");
        };
    }

    private List<String> buildDefaultSearchQueries(String competitorName,
                                                   String sourceType,
                                                   List<SourceCandidate> candidates) {
        String domainHint = candidates == null ? null : candidates.stream()
                .map(SourceCandidate::getDomain)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        return promptTemplateService.buildSearchQueries(competitorName, sourceType, domainHint);
    }

    private List<String> buildPreferredDomains(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate != null && StringUtils.hasText(candidate.getDomain())) {
                domains.add(candidate.getDomain());
            }
        }
        return new ArrayList<>(domains);
    }

    private SearchExecutionPlan buildDefaultSearchExecutionPlan() {
        return SearchExecutionPlan.builder()
                .stage("COLLECTOR_SEARCH_AND_COLLECT")
                .steps(List.of(
                        step("LOAD_CANDIDATES", "读取规划期候选来源", 500, "nodeConfig"),
                        step("VERIFY_TOP_CANDIDATES", "验证高优先级候选来源是否可用", 5000, "browser"),
                        step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行浏览器增补搜索", 8000, "searchEngine"),
                        step("SELECT_TARGETS", "合并候选并选出最终采集目标", 1000, "ranker"),
                        step("COLLECT_PAGES", "抓取页面正文并持久化证据", 12000, "collector")
                ))
                .build();
    }

    private SearchExecutionStep step(String stepCode, String goal, long expectedDurationMs, String dependency) {
        return SearchExecutionStep.builder()
                .stepCode(stepCode)
                .goal(goal)
                .expectedDurationMs(expectedDurationMs)
                .dependency(dependency)
                .status(SearchExecutionStep.StepStatus.PENDING)
                .build();
    }

    private String resolveSearchMode() {
        String configuredMode = searchProperties == null ? null : searchProperties.getMode();
        String normalizedMode = StringUtils.hasText(configuredMode)
                ? configuredMode.trim().toUpperCase(java.util.Locale.ROOT)
                : "HYBRID";
        if (!searchBrowserProperties.isEnabled() && "HYBRID".equals(normalizedMode)) {
            return "HTTP_ONLY";
        }
        if (!searchBrowserProperties.isEnabled() && "BROWSER_ONLY".equals(normalizedMode)) {
            return "HTTP_ONLY";
        }
        return normalizedMode;
    }

    private boolean isBrowserSearchEnabledForMode(String searchMode) {
        return searchBrowserProperties.isEnabled()
                && ("HYBRID".equalsIgnoreCase(searchMode) || "BROWSER_ONLY".equalsIgnoreCase(searchMode));
    }

    private int resolveMaxSearchResults(SourcePlan sourcePlan) {
        int configuredLimit = collectorProperties == null ? 5 : Math.max(1, collectorProperties.getMaxPagesPerCompetitor());
        int plannedUrlCount = sourcePlan.getUrls() == null ? 0 : sourcePlan.getUrls().size();
        if (plannedUrlCount <= 0) {
            return configuredLimit;
        }
        return Math.min(configuredLimit, plannedUrlCount);
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
}
