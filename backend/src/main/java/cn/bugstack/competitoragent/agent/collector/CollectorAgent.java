package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchExecutionUpdate;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 采集 Agent。
 * 负责按照节点配置抓取页面内容，并把结果持久化为可溯源的 EvidenceSource。
 */
@Slf4j
@Component
public class CollectorAgent extends BaseAgent {

    private final SourceCollector sourceCollector;
    private final EvidenceSourceRepository evidenceRepository;
    private final TaskNodeRepository nodeRepository;
    private final SearchExecutionCoordinator searchExecutionCoordinator;
    private final ObjectMapper objectMapper;

    public CollectorAgent(AgentExecutionLogRepository logRepository,
                          SourceCollector sourceCollector,
                          EvidenceSourceRepository evidenceRepository,
                          TaskNodeRepository nodeRepository,
                          SearchExecutionCoordinator searchExecutionCoordinator,
                          ObjectMapper objectMapper) {
        super(logRepository);
        this.sourceCollector = sourceCollector;
        this.evidenceRepository = evidenceRepository;
        this.nodeRepository = nodeRepository;
        this.searchExecutionCoordinator = searchExecutionCoordinator;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.COLLECTOR;
    }

    @Override
    public String getName() {
        return "信息采集智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // 采集节点依赖动态 DAG 注入的 competitorName / competitorUrls / sourceType 配置。
        CollectorNodeConfig config = parseConfig(context.getCurrentNodeConfig());
        if (config == null || !StringUtils.hasText(config.getCompetitorName())) {
            return AgentResult.failed("缺少采集节点配置");
        }

        String sourceType = !StringUtils.hasText(config.getSourceType()) ? "OFFICIAL" : config.getSourceType();
        List<Map<String, Object>> results = new ArrayList<>();
        int[] successCounterRef = new int[] {0};
        SearchExecutionResult searchExecutionResult = searchExecutionCoordinator.execute(config, update ->
                persistRunningOutput(context, config, sourceType, update, results, successCounterRef[0]));
        SearchExecutionPlan executionPlan = searchExecutionResult.getExecutionPlan();
        List<SearchProgressSnapshot> progressSnapshots = searchExecutionResult.getProgressSnapshots() == null
                ? new ArrayList<>()
                : new ArrayList<>(searchExecutionResult.getProgressSnapshots());
        List<SearchCollectionTarget> targets = searchExecutionResult.getSelectedTargets() == null
                ? List.of()
                : searchExecutionResult.getSelectedTargets();
        if (targets.isEmpty()) {
            markCollectStep(executionPlan, SearchExecutionStep.StepStatus.SKIPPED, "未选出可采集来源，跳过页面抓取");
            progressSnapshots.add(buildProgressSnapshot(executionPlan,
                    "COLLECT_PAGES",
                    "未选出可采集来源，跳过页面抓取",
                    Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                    readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
            String outputJson;
            try {
                outputJson = buildCollectorOutput(config,
                        !StringUtils.hasText(config.getSourceType()) ? "OFFICIAL" : config.getSourceType(),
                        searchExecutionResult,
                        progressSnapshots,
                        List.of(),
                        0,
                        targets);
            } catch (JsonProcessingException e) {
                outputJson = null;
            }
            String actionableError = buildNoTargetFailureMessage(config, sourceType, searchExecutionResult);
            return AgentResult.builder()
                    .status(TaskNodeStatus.FAILED)
                    .outputData(outputJson)
                    .outputSummary(actionableError)
                    .reasoningSummary(searchExecutionResult.getReasoningSummary())
                    .errorMessage(actionableError)
                    .build();
        }

        int evidenceCounter = 0;
        markCollectStep(executionPlan, SearchExecutionStep.StepStatus.RUNNING, "正在抓取页面正文并持久化证据");
        progressSnapshots.add(buildProgressSnapshot(executionPlan,
                "COLLECT_PAGES",
                "已进入页面采集阶段，准备抓取 " + targets.size() + " 个目标页面",
                Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
        persistRunningOutput(context, config, sourceType, executionPlan, progressSnapshots,
                searchExecutionResult.getSourceCandidates(), targets, searchExecutionResult.getExecutionTrace(),
                results, successCounterRef[0]);

        for (int index = 0; index < targets.size(); index++) {
            SearchCollectionTarget target = targets.get(index);
            SourceCandidate matchedCandidate = target.getCandidate();
            String url = matchedCandidate == null ? null : matchedCandidate.getUrl();
            if (!StringUtils.hasText(url)) {
                continue;
            }
            // 每个 URL 都会形成一条独立证据，后续抽取、报告、质检都依赖 evidenceId 做串联。
            SourceCollector.CollectedPage page = target.getCollectedPage() != null
                    ? target.getCollectedPage()
                    : sourceCollector.collect(url, config.getCompetitorName(), sourceType);
            evidenceCounter++;
            String evidenceId = generateEvidenceId(context.getTaskId(), context.getCurrentNodeName(), evidenceCounter);
            String pageMetadata = mergePageMetadata(page, matchedCandidate);

            if (isUsableCollectedPage(page)) {
                EvidenceSource evidence = EvidenceSource.builder()
                        .taskId(context.getTaskId())
                        .competitorName(config.getCompetitorName())
                        .evidenceId(evidenceId)
                        .title(page.getTitle() != null ? page.getTitle() : url)
                        .url(url)
                        .contentSnippet(page.getSnippet())
                        .fullContent(page.getContent())
                        .pageMetadata(pageMetadata)
                        .sourceType(sourceType)
                        .discoveryMethod(matchedCandidate == null ? null : matchedCandidate.getDiscoveryMethod())
                        .sourceDomain(matchedCandidate == null ? null : matchedCandidate.getDomain())
                        .discoveryReason(matchedCandidate == null ? config.getDiscoveryNotes() : matchedCandidate.getReason())
                        .publishedAt(matchedCandidate == null ? null : matchedCandidate.getPublishedAt())
                        .sourceScore(matchedCandidate == null ? null : matchedCandidate.getTotalScore())
                        .collectedAt(LocalDateTime.now())
                        .build();
                evidenceRepository.save(evidence);
                successCounterRef[0]++;
            }

            Map<String, Object> resultEntry = new LinkedHashMap<>();
            resultEntry.put("competitor", config.getCompetitorName());
            resultEntry.put("sourceType", sourceType);
            resultEntry.put("url", url);
            resultEntry.put("evidenceId", evidenceId);
            resultEntry.put("success", page.isSuccess());
            resultEntry.put("title", page.getTitle());
            resultEntry.put("contentLength", page.getContent() != null ? page.getContent().length() : 0);
            resultEntry.put("errorMessage", page.getErrorMessage());
            resultEntry.put("persisted", isUsableCollectedPage(page));
            resultEntry.put("publishedAt", matchedCandidate == null ? null : matchedCandidate.getPublishedAt());
            resultEntry.put("discoveryMethod", matchedCandidate == null ? null : matchedCandidate.getDiscoveryMethod());
            resultEntry.put("reason", matchedCandidate == null ? config.getDiscoveryNotes() : matchedCandidate.getReason());
            resultEntry.put("domain", matchedCandidate == null ? null : matchedCandidate.getDomain());
            resultEntry.put("score", matchedCandidate == null ? null : matchedCandidate.getTotalScore());
            resultEntry.put("browserTraceId", matchedCandidate == null ? null : matchedCandidate.getBrowserTraceId());
            resultEntry.put("selectionStage", matchedCandidate == null ? null : matchedCandidate.getSelectionStage());
            resultEntry.put("selectionReason", matchedCandidate == null ? null : matchedCandidate.getSelectionReason());
            results.add(resultEntry);

            progressSnapshots.add(buildProgressSnapshot(executionPlan,
                    "COLLECT_PAGES",
                    "正在抓取页面 " + (index + 1) + "/" + targets.size() + "：" + (page.getTitle() != null ? page.getTitle() : url),
                    Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                    readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
            persistRunningOutput(context, config, sourceType, executionPlan, progressSnapshots,
                    searchExecutionResult.getSourceCandidates(), targets, searchExecutionResult.getExecutionTrace(),
                    results, successCounterRef[0]);
        }

        try {
            if (successCounterRef[0] == 0) {
                markCollectStep(executionPlan, SearchExecutionStep.StepStatus.FAILED,
                        "未采集到可用页面内容");
                progressSnapshots.add(buildProgressSnapshot(executionPlan,
                        "COLLECT_PAGES",
                        "未采集到可用页面内容",
                        Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                        readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
                String outputJson = buildCollectorOutput(
                        config, sourceType, searchExecutionResult, progressSnapshots, results, successCounterRef[0], targets);
                String actionableError = buildNoContentFailureMessage(
                        config, sourceType, searchExecutionResult.getExecutionTrace(), results);
                return AgentResult.builder()
                        .status(TaskNodeStatus.FAILED)
                        .outputData(outputJson)
                        .outputSummary(actionableError)
                        .reasoningSummary(searchExecutionResult.getReasoningSummary())
                        .errorMessage(actionableError)
                        .build();
            }
            markCollectStep(executionPlan, SearchExecutionStep.StepStatus.SUCCESS,
                    "页面采集完成，可用来源 " + successCounterRef[0] + "/" + results.size() + " 条");
            progressSnapshots.add(buildProgressSnapshot(executionPlan,
                    "COLLECT_PAGES",
                    "页面采集完成，可用来源 " + successCounterRef[0] + "/" + results.size() + " 条",
                    Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                    readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
            String outputJson = buildCollectorOutput(
                    config, sourceType, searchExecutionResult, progressSnapshots, results, successCounterRef[0], targets);
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData(outputJson)
                    .outputSummary("已完成 " + config.getCompetitorName() + " 的 " + sourceType + " 采集，可用来源 "
                            + successCounterRef[0] + "/" + results.size() + " 条")
                    .reasoningSummary(searchExecutionResult.getReasoningSummary())
                    .build();
        } catch (JsonProcessingException e) {
            return AgentResult.failed("采集结果序列化失败：" + e.getMessage());
        }
    }

    private void persistRunningOutput(AgentContext context,
                                      CollectorNodeConfig config,
                                      String sourceType,
                                      SearchExecutionUpdate update,
                                      List<Map<String, Object>> results,
                                      int successCounter) {
        if (update == null) {
            return;
        }
        persistRunningOutput(context, config, sourceType,
                update.getExecutionPlan(),
                update.getProgressSnapshots(),
                update.getSourceCandidates(),
                update.getSelectedTargets(),
                update.getExecutionTrace(),
                results,
                successCounter);
    }

    private void persistRunningOutput(AgentContext context,
                                      CollectorNodeConfig config,
                                      String sourceType,
                                      SearchExecutionPlan executionPlan,
                                      List<SearchProgressSnapshot> progressSnapshots,
                                      List<SourceCandidate> sourceCandidates,
                                      List<SearchCollectionTarget> targets,
                                      SearchExecutionTrace executionTrace,
                                      List<Map<String, Object>> results,
                                      int successCounter) {
        if (context.getTaskId() == null || !StringUtils.hasText(context.getCurrentNodeName())) {
            return;
        }
        try {
            String outputJson = buildCollectorOutput(
                    config,
                    sourceType,
                    SearchExecutionResult.builder()
                            .executionPlan(executionPlan)
                            .progressSnapshot(progressSnapshots == null || progressSnapshots.isEmpty()
                                    ? null
                                    : progressSnapshots.get(progressSnapshots.size() - 1))
                            .progressSnapshots(progressSnapshots)
                            .sourceCandidates(sourceCandidates)
                            .selectedTargets(targets)
                            .executionTrace(executionTrace)
                            .build(),
                    progressSnapshots == null ? List.of() : progressSnapshots,
                    results,
                    successCounter,
                    targets == null ? List.of() : targets
            );
            nodeRepository.findByTaskIdAndNodeName(context.getTaskId(), context.getCurrentNodeName())
                    .ifPresent(node -> {
                        if (node.getStatus() != TaskNodeStatus.RUNNING) {
                            return;
                        }
                        node.setOutputData(outputJson);
                        nodeRepository.save(node);
                    });
        } catch (Exception e) {
            log.debug("persist collector running output skipped, nodeName={}, reason={}",
                    context.getCurrentNodeName(), e.getMessage());
        }
    }

    private String buildCollectorOutput(CollectorNodeConfig config,
                                        String sourceType,
                                        SearchExecutionResult searchExecutionResult,
                                        List<SearchProgressSnapshot> progressSnapshots,
                                        List<Map<String, Object>> results,
                                        int successCounter,
                                        List<SearchCollectionTarget> targets) throws JsonProcessingException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("competitor", config.getCompetitorName());
        output.put("sourceType", sourceType);
        output.put("discoveryNotes", config.getDiscoveryNotes());
        output.put("sourceCandidates", searchExecutionResult.getSourceCandidates() == null ? List.of() : searchExecutionResult.getSourceCandidates());
        output.put("searchMode", config.getSearchMode());
        output.put("searchQueries", config.getSearchQueries() == null ? List.of() : config.getSearchQueries());
        output.put("searchRuntimePolicy", config.getSearchRuntimePolicy());
        output.put("searchExecutionPlan", searchExecutionResult.getExecutionPlan());
        output.put("searchExecutionTrace", searchExecutionResult.getExecutionTrace());
        output.put("searchProgress", progressSnapshots.isEmpty() ? searchExecutionResult.getProgressSnapshot() : progressSnapshots.get(progressSnapshots.size() - 1));
        output.put("searchProgressSnapshots", progressSnapshots);
        output.put("searchAudit", searchExecutionResult.getAuditSnapshot());
        output.put("selectedTargets", buildSelectedTargetSummaries(targets));
        output.put("totalCollected", results.size());
        output.put("successCollected", successCounter);
        output.put("results", results);
        return objectMapper.writeValueAsString(output);
    }

    // evidenceId 同时编码 taskId、nodeName 和序号，方便跨页面回查来源。
    private String generateEvidenceId(Long taskId, String nodeName, int sequence) {
        long safeTaskId = taskId == null ? 0L : taskId;
        String safeNodeName = nodeName == null || nodeName.isBlank()
                ? "NODE"
                : nodeName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        return String.format("T%04d-%s-%03d", safeTaskId % 10000, safeNodeName, sequence);
    }

    // 节点配置来自数据库 JSON，解析失败时返回 null，由上层统一判定为节点配置缺失。
    private CollectorNodeConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            CollectorNodeConfig config = objectMapper.readValue(json, CollectorNodeConfig.class);
            if (!StringUtils.hasText(config.getSourceType())) {
                config.setSourceType("OFFICIAL");
            }
            return config;
        } catch (Exception e) {
            log.warn("parse collector node config failed: {}", json, e);
            return null;
        }
    }

    /**
     * 页面元数据统一补上补源方式、来源理由、排序分数等字段，
     * 便于报告页与证据列表直接展示，而不是每次重新解析节点配置。
     */
    private String mergePageMetadata(SourceCollector.CollectedPage page,
                                     SourceCandidate matchedCandidate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        JsonNode existingMetadata = readJson(page.getMetadata());
        if (existingMetadata != null && existingMetadata.isObject()) {
            existingMetadata.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), entry.getValue()));
        }
        if (matchedCandidate != null) {
            metadata.put("sourceType", matchedCandidate.getSourceType());
            metadata.put("discoveryMethod", matchedCandidate.getDiscoveryMethod());
            metadata.put("reason", matchedCandidate.getReason());
            metadata.put("domain", matchedCandidate.getDomain());
            metadata.put("publishedAt", matchedCandidate.getPublishedAt());
            metadata.put("relevanceScore", matchedCandidate.getRelevanceScore());
            metadata.put("freshnessScore", matchedCandidate.getFreshnessScore());
            metadata.put("qualityScore", matchedCandidate.getQualityScore());
            metadata.put("totalScore", matchedCandidate.getTotalScore());
            metadata.put("searchQuery", matchedCandidate.getSearchQuery());
            metadata.put("searchEngine", matchedCandidate.getSearchEngine());
            metadata.put("resultRank", matchedCandidate.getResultRank());
            metadata.put("browserTraceId", matchedCandidate.getBrowserTraceId());
            metadata.put("verified", matchedCandidate.getVerified());
            metadata.put("verificationReason", matchedCandidate.getVerificationReason());
            metadata.put("matchedSignals", matchedCandidate.getMatchedSignals());
            metadata.put("selectionStage", matchedCandidate.getSelectionStage());
            metadata.put("selectionReason", matchedCandidate.getSelectionReason());
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("serialize merged page metadata failed", e);
            return page.getMetadata();
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 只有采集成功且正文/摘要至少有一项可用时，才作为有效证据进入后续抽取链路。
     */
    private boolean isUsableCollectedPage(SourceCollector.CollectedPage page) {
        if (page == null || !page.isSuccess()) {
            return false;
        }
        boolean hasContent = page.getContent() != null && !page.getContent().isBlank();
        boolean hasSnippet = page.getSnippet() != null && !page.getSnippet().isBlank();
        return hasContent || hasSnippet;
    }

    private void markCollectStep(SearchExecutionPlan executionPlan,
                                 SearchExecutionStep.StepStatus status,
                                 String message) {
        if (executionPlan == null || executionPlan.getSteps() == null) {
            return;
        }
        for (int index = 0; index < executionPlan.getSteps().size(); index++) {
            SearchExecutionStep step = executionPlan.getSteps().get(index);
            if (!"COLLECT_PAGES".equals(step.getStepCode())) {
                continue;
            }
            executionPlan.getSteps().set(index, step.toBuilder()
                    .status(status)
                    .message(message)
                    .startedAt(step.getStartedAt() == null ? LocalDateTime.now() : step.getStartedAt())
                    .completedAt(status == SearchExecutionStep.StepStatus.RUNNING ? null : LocalDateTime.now())
                    .build());
            return;
        }
    }

    private SearchProgressSnapshot buildProgressSnapshot(SearchExecutionPlan executionPlan,
                                                         String currentStepCode,
                                                         String message,
                                                         boolean degraded,
                                                         String degradationReason) {
        List<SearchExecutionStep> steps = executionPlan == null || executionPlan.getSteps() == null
                ? List.of()
                : executionPlan.getSteps();
        int totalSteps = steps.size();
        int completedSteps = (int) steps.stream()
                .filter(step -> step.getStatus() == SearchExecutionStep.StepStatus.SUCCESS
                        || step.getStatus() == SearchExecutionStep.StepStatus.FAILED
                        || step.getStatus() == SearchExecutionStep.StepStatus.SKIPPED)
                .count();
        int progressPercent = totalSteps == 0 ? 0 : (int) Math.round((completedSteps * 100.0D) / totalSteps);
        String currentStep = steps.stream()
                .filter(step -> currentStepCode.equals(step.getStepCode()))
                .map(step -> StringUtils.hasText(step.getGoal()) ? step.getGoal() : step.getStepCode())
                .findFirst()
                .orElse(currentStepCode);
        String status;
        if (steps.stream().anyMatch(step -> step.getStatus() == SearchExecutionStep.StepStatus.FAILED)) {
            status = "FAILED";
        } else if (degraded) {
            status = "DEGRADED";
        } else if (completedSteps >= totalSteps && totalSteps > 0) {
            status = "SUCCESS";
        } else {
            status = "RUNNING";
        }
        return SearchProgressSnapshot.builder()
                .currentStep(currentStep)
                .currentStepCode(currentStepCode)
                .completedSteps(completedSteps)
                .totalSteps(totalSteps)
                .progressPercent(progressPercent)
                .status(status)
                .message(message)
                .degraded(degraded)
                .degradationReason(degradationReason)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private String readString(SearchExecutionTrace trace,
                              java.util.function.Function<SearchExecutionTrace, String> getter) {
        if (trace == null || getter == null) {
            return null;
        }
        String value = getter.apply(trace);
        return value == null || value.isBlank() ? null : value;
    }

    private Boolean readBoolean(SearchExecutionTrace trace,
                                java.util.function.Function<SearchExecutionTrace, Boolean> getter) {
        if (trace == null || getter == null) {
            return null;
        }
        return getter.apply(trace);
    }

    private List<Map<String, Object>> buildSelectedTargetSummaries(List<SearchCollectionTarget> targets) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (SearchCollectionTarget target : targets) {
            if (target == null || target.getCandidate() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", target.getCandidate().getUrl());
            item.put("title", target.getCandidate().getTitle());
            item.put("verified", target.getCandidate().getVerified());
            item.put("browserTraceId", target.getCandidate().getBrowserTraceId());
            item.put("selectionStage", target.getCandidate().getSelectionStage());
            item.put("selectionReason", target.getCandidate().getSelectionReason());
            item.put("hasPrefetchedPage", target.getCollectedPage() != null);
            summaries.add(item);
        }
        return summaries;
    }

    private String buildNoTargetFailureMessage(CollectorNodeConfig config,
                                               String sourceType,
                                               SearchExecutionResult searchExecutionResult) {
        SearchExecutionTrace trace = searchExecutionResult == null ? null : searchExecutionResult.getExecutionTrace();
        StringBuilder message = new StringBuilder("未选出可采集来源：")
                .append(config.getCompetitorName())
                .append(" / ")
                .append(sourceType);

        String primaryReason = null;
        if (trace != null && StringUtils.hasText(trace.getBrowserBlockedReason())) {
            primaryReason = "浏览器搜索疑似触发反爬[" + trace.getBrowserBlockedReason() + "]";
        } else if (trace != null && StringUtils.hasText(trace.getBrowserSearchSummary())) {
            primaryReason = trace.getBrowserSearchSummary();
        } else if (trace != null && Boolean.TRUE.equals(trace.getProviderFallbackUsed())) {
            primaryReason = "HTTP 回退补源未找到可用候选";
        }
        if (StringUtils.hasText(primaryReason)) {
            message.append("。原因：").append(primaryReason);
        }

        String advice = trace == null ? null : trace.getRecoveryAdvice();
        if (!StringUtils.hasText(advice)) {
            advice = "建议优先填写准确官网 URL，确认网络可访问，并在需要浏览器搜索时开启 search.browser.enabled: true。";
        }
        message.append("。建议：").append(advice);
        return message.toString();
    }

    private String buildNoContentFailureMessage(CollectorNodeConfig config,
                                                String sourceType,
                                                SearchExecutionTrace trace,
                                                List<Map<String, Object>> results) {
        StringBuilder message = new StringBuilder("未采集到可用页面内容：")
                .append(config.getCompetitorName())
                .append(" / ")
                .append(sourceType);
        String dominantReason = summarizeCollectionFailureReason(results);
        if (StringUtils.hasText(dominantReason)) {
            message.append("。原因：").append(dominantReason);
        }
        if (trace != null && StringUtils.hasText(trace.getBrowserBlockedReason())) {
            message.append("。补充信息：浏览器搜索疑似触发反爬[").append(trace.getBrowserBlockedReason()).append("]");
        }
        message.append("。建议：").append(buildCollectionRecoveryAdvice(trace, dominantReason));
        return message.toString();
    }

    private String summarizeCollectionFailureReason(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (Map<String, Object> item : results) {
            String errorMessage = item == null ? null : toText(item.get("errorMessage"));
            if (!StringUtils.hasText(errorMessage)) {
                continue;
            }
            String bucket = classifyCollectionFailure(errorMessage);
            counters.put(bucket, counters.getOrDefault(bucket, 0) + 1);
        }
        return counters.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String classifyCollectionFailure(String errorMessage) {
        String normalized = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("unknownhost") || normalized.contains("name or service not known") || normalized.contains("dns")) {
            return "域名解析或网络访问失败";
        }
        if (normalized.contains("timeout")) {
            return "页面访问超时";
        }
        if (normalized.contains("access denied") || normalized.contains("forbidden") || normalized.contains("captcha")) {
            return "页面访问被拦截或触发反爬";
        }
        if (normalized.contains("前端壳") || normalized.contains("正文为空")) {
            return "页面只返回前端壳或正文为空";
        }
        if (normalized.contains("status")) {
            return "目标页面返回异常状态码";
        }
        return errorMessage;
    }

    private String buildCollectionRecoveryAdvice(SearchExecutionTrace trace, String dominantReason) {
        if (StringUtils.hasText(dominantReason)) {
            if (dominantReason.contains("域名解析") || dominantReason.contains("网络访问")) {
                return "请确认官网 URL 正确、网络和 DNS 可用，必要时手动填写可访问的官网或文档入口。";
            }
            if (dominantReason.contains("超时")) {
                return "建议稍后重试，或提高页面超时配置并优先填写更精确的目标页面 URL。";
            }
            if (dominantReason.contains("反爬")) {
                return "建议稍后重试，降低搜索频率，或直接在任务里填写准确官网 URL 以减少搜索链路依赖。";
            }
            if (dominantReason.contains("前端壳") || dominantReason.contains("正文为空")) {
                return "建议改填更具体的内容页 URL，例如文档详情页、定价页或文章正文页。";
            }
        }
        if (trace != null && StringUtils.hasText(trace.getRecoveryAdvice())) {
            return trace.getRecoveryAdvice();
        }
        return "请确认网络可用、目标 URL 可访问，并优先提供准确官网或文档入口后重试。";
    }

    private String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
