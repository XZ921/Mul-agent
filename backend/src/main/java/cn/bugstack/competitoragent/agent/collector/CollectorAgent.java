package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexService;
import cn.bugstack.competitoragent.rag.TaskRetrievalIndexingResult;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchExecutionUpdate;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.workflow.contract.CollectResult;
import cn.bugstack.competitoragent.workflow.contract.CollectedDocument;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceViewAssembler;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private final CollectionExecutionCoordinator collectionExecutionCoordinator;
    private final TaskRetrievalIndexService taskRetrievalIndexService;
    private final ObjectMapper objectMapper;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final DownstreamEvidenceViewAssembler downstreamEvidenceViewAssembler;

    public CollectorAgent(AgentExecutionLogRepository logRepository,
                          SourceCollector sourceCollector,
                          EvidenceSourceRepository evidenceRepository,
                          TaskNodeRepository nodeRepository,
                          AgentContextAssembler agentContextAssembler,
                          SearchExecutionCoordinator searchExecutionCoordinator,
                          CollectionExecutionCoordinator collectionExecutionCoordinator,
                          TaskRetrievalIndexService taskRetrievalIndexService,
                          ObjectMapper objectMapper) {
        this(logRepository,
                sourceCollector,
                evidenceRepository,
                nodeRepository,
                agentContextAssembler,
                searchExecutionCoordinator,
                collectionExecutionCoordinator,
                taskRetrievalIndexService,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper));
    }

    @Autowired
    public CollectorAgent(AgentExecutionLogRepository logRepository,
                          SourceCollector sourceCollector,
                          EvidenceSourceRepository evidenceRepository,
                          TaskNodeRepository nodeRepository,
                          AgentContextAssembler agentContextAssembler,
                          SearchExecutionCoordinator searchExecutionCoordinator,
                          CollectionExecutionCoordinator collectionExecutionCoordinator,
                          TaskRetrievalIndexService taskRetrievalIndexService,
                          ObjectMapper objectMapper,
                          DownstreamEvidenceViewAssembler downstreamEvidenceViewAssembler) {
        // Spring 需要明确知道运行期应优先使用带统一证据视图装配器的正式构造器，
        // 否则存在多个 public 构造器时会退回默认实例化路径，导致上下文加载失败。
        super(logRepository, agentContextAssembler);
        this.sourceCollector = sourceCollector;
        this.evidenceRepository = evidenceRepository;
        this.nodeRepository = nodeRepository;
        this.searchExecutionCoordinator = searchExecutionCoordinator;
        this.collectionExecutionCoordinator = collectionExecutionCoordinator;
        this.taskRetrievalIndexService = taskRetrievalIndexService;
        this.objectMapper = objectMapper;
        this.canonicalUrlResolver = new CanonicalUrlResolver();
        this.downstreamEvidenceViewAssembler = downstreamEvidenceViewAssembler == null
                ? new DownstreamEvidenceViewAssembler(objectMapper)
                : downstreamEvidenceViewAssembler;
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
        List<SearchCollectionTarget> failedPrefetchedAttemptTargets =
                resolveFailedPrefetchedAttemptTargets(searchExecutionResult);
        if (targets.isEmpty() && !failedPrefetchedAttemptTargets.isEmpty()) {
            return executePrefetchedAttemptFailurePhase(
                    context,
                    config,
                    sourceType,
                    searchExecutionResult,
                    executionPlan,
                    progressSnapshots,
                    failedPrefetchedAttemptTargets,
                    results,
                    successCounterRef
            );
        }
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
                        context.getTaskRagPromptContext(),
                        searchExecutionResult,
                        null,
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

        if (useRecursiveCollectionResultConsumption()) {
            return executeCollectionPhaseWithRecursiveResults(
                    context,
                    config,
                    sourceType,
                    searchExecutionResult,
                    executionPlan,
                    progressSnapshots,
                    targets,
                    results,
                    successCounterRef
            );
        }

        int evidenceCounter = 0;
        int reusedTargetCount = (int) targets.stream()
                .filter(target -> target != null && target.getCollectedPage() != null)
                .count();
        List<SearchCollectionTarget> executableTargets = targets.stream()
                .filter(this::requiresCoordinatorExecution)
                .toList();
        CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(
                context.getTaskId(),
                context.getCurrentNodeName(),
                context.getPlanVersionId(),
                config.getCompetitorName(),
                executableTargets,
                config.getCollectionAuditCheckpoint()
        );
        List<CollectionExecutionResult> collectionResults = collectionReport == null || collectionReport.getResults() == null
                ? List.of()
                : collectionReport.getResults();
        List<CollectionExecutionResult> auditResults = new ArrayList<>();
        // 采集阶段统一先走协调器路由执行，只有验证阶段已经拿到页面快照的目标才继续复用旧页面，
        // 这样既能接入新的结构化采集执行器，也不会破坏“已验证页面不重复抓取”的既有契约。
        markCollectStep(executionPlan, SearchExecutionStep.StepStatus.RUNNING,
                "正在通过采集协调器处理 " + targets.size() + " 个目标，其中复用已验证页面 " + reusedTargetCount + " 个");
        progressSnapshots.add(buildProgressSnapshot(executionPlan,
                "COLLECT_PAGES",
                "已进入采集阶段，待处理目标 " + targets.size() + " 个，复用已验证页面 " + reusedTargetCount + " 个",
                Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
        persistRunningOutput(context, config, sourceType, executionPlan, progressSnapshots,
                searchExecutionResult.getSourceCandidates(), targets, searchExecutionResult.getExecutionTrace(),
                results, successCounterRef[0]);

        int collectionResultIndex = 0;
        for (int index = 0; index < targets.size(); index++) {
            SearchCollectionTarget target = targets.get(index);
            SourceCandidate matchedCandidate = target.getCandidate();
            String url = matchedCandidate == null ? null : matchedCandidate.getUrl();
            if (!StringUtils.hasText(url)) {
                continue;
            }
            SourceCollector.CollectedPage page;
            if (target.getCollectedPage() != null) {
                page = target.getCollectedPage();
                auditResults.add(buildAuditResultFromPrefetchedPage(
                        context,
                        config,
                        sourceType,
                        index + 1,
                        target,
                        page));
            } else {
                CollectionExecutionResult collectionResult = collectionResultIndex < collectionResults.size()
                        ? collectionResults.get(collectionResultIndex++)
                        : null;
                if (collectionResult != null) {
                    auditResults.add(collectionResult);
                }
                page = mapCollectionResultToCollectedPage(collectionResult, config.getCompetitorName(), sourceType);
            }
            // 每个目标都会形成一条独立证据。结构化采集执行器可能返回修正后的 sourceUrls，
            // 这里优先使用执行结果里的回指地址，避免再次退化为原始 locator 或丢失可追溯来源。
            List<String> collectedSourceUrls = resolveCollectedSourceUrls(page, matchedCandidate, url);
            String effectiveUrl = firstNonBlank(page == null ? null : page.getUrl(),
                    collectedSourceUrls.isEmpty() ? url : collectedSourceUrls.get(0));
            evidenceCounter++;
            String evidenceId = generateEvidenceId(context.getTaskId(), context.getCurrentNodeName(), evidenceCounter);
            String pageMetadata = mergePageMetadata(page, matchedCandidate);

            TaskRetrievalIndexingResult retrievalIndexingResult = null;
            String knowledgeFailureReason = null;
            if (isUsableCollectedPage(page)) {
                EvidenceSource evidence = EvidenceSource.builder()
                        .taskId(context.getTaskId())
                        .competitorName(config.getCompetitorName())
                        .evidenceId(evidenceId)
                        .title(page.getTitle() != null ? page.getTitle() : effectiveUrl)
                        .url(effectiveUrl)
                        .contentSnippet(page.getSnippet())
                        .fullContent(page.getContent())
                        .pageMetadata(pageMetadata)
                        .sourceType(sourceType)
                        .discoveryMethod(matchedCandidate == null ? null : matchedCandidate.getDiscoveryMethod())
                        .sourceCategory(resolveSourceCategory(matchedCandidate))
                        .sourceDomain(matchedCandidate == null ? null : matchedCandidate.getDomain())
                        .discoveryReason(matchedCandidate == null ? config.getDiscoveryNotes() : matchedCandidate.getReason())
                        .publishedAt(matchedCandidate == null ? null : matchedCandidate.getPublishedAt())
                        .sourceScore(matchedCandidate == null ? null : matchedCandidate.getTotalScore())
                        .collectedAt(LocalDateTime.now())
                        .build();
                evidenceRepository.save(evidence);
                successCounterRef[0]++;

                // 采集成功后立即沉淀任务级知识文档与切片，保证后续 Task RAG 不必回头重读原始日志或报告正文。
                try {
                    retrievalIndexingResult = taskRetrievalIndexService.indexEvidence(evidence);
                } catch (Exception e) {
                    knowledgeFailureReason = e.getMessage();
                    log.warn("index collected evidence failed, taskId={}, evidenceId={}",
                            context.getTaskId(), evidenceId, e);
                }
            }

            Map<String, Object> resultEntry = new LinkedHashMap<>();
            List<String> collectionIssueFlags = new ArrayList<>(buildCollectionIssueFlags(page));
            if (retrievalIndexingResult != null && retrievalIndexingResult.issueFlags() != null) {
                collectionIssueFlags = mergeIssueFlags(collectionIssueFlags, retrievalIndexingResult.issueFlags());
            }
            if (knowledgeFailureReason != null && !knowledgeFailureReason.isBlank()) {
                collectionIssueFlags = mergeIssueFlags(collectionIssueFlags, List.of("KNOWLEDGE_INDEX_FAILED"));
            }
            resultEntry.put("competitor", config.getCompetitorName());
            resultEntry.put("sourceType", sourceType);
            resultEntry.put("sourceCategory", resolveSourceCategory(matchedCandidate));
            resultEntry.put("url", effectiveUrl);
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
            resultEntry.put("trustTier", matchedCandidate == null || matchedCandidate.getTrustTier() == null
                    ? null
                    : matchedCandidate.getTrustTier().name());
            resultEntry.put("trustTierLabel", matchedCandidate == null ? null : matchedCandidate.getTrustTierLabel());
            resultEntry.put("rankingReasons", matchedCandidate == null ? null : matchedCandidate.getRankingReasons());
            resultEntry.put("rankingSummary", matchedCandidate == null ? null : matchedCandidate.getRankingSummary());
            resultEntry.put("browserTraceId", matchedCandidate == null ? null : matchedCandidate.getBrowserTraceId());
            resultEntry.put("selectionStage", matchedCandidate == null ? null : matchedCandidate.getSelectionStage());
            resultEntry.put("selectionReason", matchedCandidate == null ? null : matchedCandidate.getSelectionReason());
            resultEntry.put("selectionSummary", matchedCandidate == null ? null : matchedCandidate.getSelectionSummary());
            resultEntry.put("sourceUrls", collectedSourceUrls);
            resultEntry.put("issueFlags", collectionIssueFlags);
            resultEntry.put("evidenceFragments", buildCollectedEvidenceFragments(
                    config, sourceType, page, matchedCandidate, evidenceId, effectiveUrl));
            resultEntry.put("downstreamEvidenceViews", buildDownstreamEvidenceViews(
                    config, sourceType, page, evidenceId, effectiveUrl, pageMetadata));
            if (retrievalIndexingResult != null) {
                resultEntry.put("knowledgeDocument", toKnowledgeDocumentPayload(retrievalIndexingResult.knowledgeDocument()));
                resultEntry.put("retrievalChunks", toRetrievalChunkPayloads(retrievalIndexingResult.retrievalChunks()));
                resultEntry.put("retrievalIndex", toRetrievalIndexPayload(retrievalIndexingResult.retrievalIndex()));
                resultEntry.put("knowledgeFailureReason", retrievalIndexingResult.failureReason());
            } else if (knowledgeFailureReason != null && !knowledgeFailureReason.isBlank()) {
                resultEntry.put("knowledgeFailureReason", knowledgeFailureReason);
            }
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

        collectionReport = collectionExecutionCoordinator.summarize(auditResults);

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
                        config, sourceType, context.getTaskRagPromptContext(), searchExecutionResult, collectionReport, progressSnapshots, results, successCounterRef[0], targets);
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
                    config, sourceType, context.getTaskRagPromptContext(), searchExecutionResult, collectionReport, progressSnapshots, results, successCounterRef[0], targets);
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

    private boolean useRecursiveCollectionResultConsumption() {
        return true;
    }

    /**
     * 当运行期验证没有产出正式 selectedTargets，但 attemptedTargets 里已经留下了预抓取失败页时，
     * 仍然要把这些失败事实收口到正式 collectionAudit。
     * 这样节点 FAILED、collectionAudit FAILED、replayTimeline FAILED 才能保持同一份事实语义。
     */
    private AgentResult executePrefetchedAttemptFailurePhase(AgentContext context,
                                                             CollectorNodeConfig config,
                                                             String sourceType,
                                                             SearchExecutionResult searchExecutionResult,
                                                             SearchExecutionPlan executionPlan,
                                                             List<SearchProgressSnapshot> progressSnapshots,
                                                             List<SearchCollectionTarget> failedPrefetchedAttemptTargets,
                                                             List<Map<String, Object>> results,
                                                             int[] successCounterRef) {
        List<CollectionExecutionResult> auditResults = new ArrayList<>();
        int[] evidenceCounterRef = new int[] {0};
        int[] processedPageCounterRef = new int[] {0};
        int totalCollectedPages = failedPrefetchedAttemptTargets.size();

        markCollectStep(executionPlan, SearchExecutionStep.StepStatus.RUNNING,
                "候选验证阶段捕获到 " + totalCollectedPages + " 个预抓取失败页，正在收口正式采集审计");
        progressSnapshots.add(buildProgressSnapshot(executionPlan,
                "COLLECT_PAGES",
                "候选验证阶段捕获到 " + totalCollectedPages + " 个预抓取失败页，正在收口正式采集审计",
                Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
        persistRunningOutput(context, config, sourceType, executionPlan, progressSnapshots,
                searchExecutionResult.getSourceCandidates(), List.of(), searchExecutionResult.getExecutionTrace(),
                results, successCounterRef[0]);

        for (int index = 0; index < failedPrefetchedAttemptTargets.size(); index++) {
            SearchCollectionTarget target = failedPrefetchedAttemptTargets.get(index);
            SourceCandidate matchedCandidate = target == null ? null : target.getCandidate();
            String url = matchedCandidate == null ? null : matchedCandidate.getUrl();
            if (!StringUtils.hasText(url) || target == null || target.getCollectedPage() == null) {
                continue;
            }
            CollectionExecutionResult prefetchedAuditResult = buildAuditResultFromPrefetchedPage(
                    context,
                    config,
                    sourceType,
                    index + 1,
                    target,
                    target.getCollectedPage());
            auditResults.add(prefetchedAuditResult);
            processedPageCounterRef[0]++;
            appendCollectedResultEntry(context, config, sourceType, searchExecutionResult, executionPlan,
                    progressSnapshots, List.of(), results, successCounterRef, evidenceCounterRef,
                    processedPageCounterRef[0], totalCollectedPages, prefetchedAuditResult,
                    target.getCollectedPage(), matchedCandidate, url);
        }

        CollectionExecutionReport collectionReport = collectionExecutionCoordinator.summarize(auditResults);
        try {
            markCollectStep(executionPlan, SearchExecutionStep.StepStatus.FAILED, "未采集到可用页面内容");
            progressSnapshots.add(buildProgressSnapshot(executionPlan,
                    "COLLECT_PAGES",
                    "未采集到可用页面内容",
                    Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                    readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
            String outputJson = buildCollectorOutput(
                    config, sourceType, context.getTaskRagPromptContext(), searchExecutionResult, collectionReport,
                    progressSnapshots, results, successCounterRef[0], List.of());
            String actionableError = buildNoContentFailureMessage(
                    config, sourceType, searchExecutionResult.getExecutionTrace(), results);
            return AgentResult.builder()
                    .status(TaskNodeStatus.FAILED)
                    .outputData(outputJson)
                    .outputSummary(actionableError)
                    .reasoningSummary(searchExecutionResult.getReasoningSummary())
                    .errorMessage(actionableError)
                    .build();
        } catch (JsonProcessingException e) {
            return AgentResult.failed("采集结果序列化失败：" + e.getMessage());
        }
    }

    /**
     * 递归采集开启后，collection coordinator 返回的结果集合不再与 selected targets 一一对应。
     * 这里显式把入口页结果和内部发现页结果拆开消费，确保 documents / collectionAudit / progress 都包含递归子页。
     */
    private AgentResult executeCollectionPhaseWithRecursiveResults(AgentContext context,
                                                                  CollectorNodeConfig config,
                                                                  String sourceType,
                                                                  SearchExecutionResult searchExecutionResult,
                                                                  SearchExecutionPlan executionPlan,
                                                                  List<SearchProgressSnapshot> progressSnapshots,
                                                                  List<SearchCollectionTarget> targets,
                                                                  List<Map<String, Object>> results,
                                                                  int[] successCounterRef) {
        if (targets.isEmpty()) {
            markCollectStep(executionPlan, SearchExecutionStep.StepStatus.SKIPPED, "未选出可采集来源，跳过页面抓取");
            progressSnapshots.add(buildProgressSnapshot(executionPlan,
                    "COLLECT_PAGES",
                    "未选出可采集来源，跳过页面抓取",
                    Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                    readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
            try {
                String outputJson = buildCollectorOutput(
                        config,
                        sourceType,
                        context.getTaskRagPromptContext(),
                        searchExecutionResult,
                        null,
                        progressSnapshots,
                        List.of(),
                        0,
                        targets
                );
                String actionableError = buildNoTargetFailureMessage(config, sourceType, searchExecutionResult);
                return AgentResult.builder()
                        .status(TaskNodeStatus.FAILED)
                        .outputData(outputJson)
                        .outputSummary(actionableError)
                        .reasoningSummary(searchExecutionResult.getReasoningSummary())
                        .errorMessage(actionableError)
                        .build();
            } catch (JsonProcessingException e) {
                return AgentResult.failed("采集结果序列化失败：" + e.getMessage());
            }
        }

        int reusedTargetCount = (int) targets.stream()
                .filter(target -> target != null && target.getCollectedPage() != null)
                .count();
        List<SearchCollectionTarget> executableTargets = targets.stream()
                .filter(this::requiresCoordinatorExecution)
                .toList();
        CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(
                context.getTaskId(),
                context.getCurrentNodeName(),
                context.getPlanVersionId(),
                config.getCompetitorName(),
                executableTargets,
                config.getCollectionAuditCheckpoint()
        );
        List<CollectionExecutionResult> collectionResults = collectionReport == null || collectionReport.getResults() == null
                ? List.of()
                : collectionReport.getResults();
        List<CollectionExecutionResult> auditResults = new ArrayList<>();
        Map<String, SourceCandidate> executableCandidateIndex = indexExecutableCandidates(targets);
        Map<String, CollectionExecutionResult> entryResultByIdentity = new LinkedHashMap<>();
        List<CollectionExecutionResult> unmatchedEntryResults = new ArrayList<>();
        List<CollectionExecutionResult> discoveredChildResults = new ArrayList<>();
        for (CollectionExecutionResult collectionResult : collectionResults) {
            if (isEntryCollectionResult(collectionResult)) {
                String stableIdentity = resolveStableCollectionIdentity(
                        collectionResult.getResourceLocator(),
                        collectionResult.getSourceUrls()
                );
                if (StringUtils.hasText(stableIdentity)
                        && executableCandidateIndex.containsKey(stableIdentity)
                        && !entryResultByIdentity.containsKey(stableIdentity)) {
                    entryResultByIdentity.put(stableIdentity, collectionResult);
                } else {
                    unmatchedEntryResults.add(collectionResult);
                }
                continue;
            }
            discoveredChildResults.add(collectionResult);
        }

        int prefetchedTargetCount = (int) targets.stream()
                .filter(target -> target != null
                        && target.getCollectedPage() != null
                        && target.getCandidate() != null
                        && StringUtils.hasText(target.getCandidate().getUrl()))
                .count();
        int totalCollectedPages = prefetchedTargetCount + collectionResults.size();
        int[] evidenceCounterRef = new int[] {0};
        int[] processedPageCounterRef = new int[] {0};

        markCollectStep(executionPlan, SearchExecutionStep.StepStatus.RUNNING,
                "正在通过采集协调器处理页面，总页面数 " + totalCollectedPages + "，其中复用已验证页面 "
                        + reusedTargetCount + " 个");
        progressSnapshots.add(buildProgressSnapshot(executionPlan,
                "COLLECT_PAGES",
                "已进入采集阶段，待处理页面 " + totalCollectedPages + " 个，复用已验证页面 "
                        + reusedTargetCount + " 个",
                Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
        persistRunningOutput(context, config, sourceType, executionPlan, progressSnapshots,
                searchExecutionResult.getSourceCandidates(), targets, searchExecutionResult.getExecutionTrace(),
                results, successCounterRef[0]);

        for (int index = 0; index < targets.size(); index++) {
            SearchCollectionTarget target = targets.get(index);
            SourceCandidate matchedCandidate = target == null ? null : target.getCandidate();
            String url = matchedCandidate == null ? null : matchedCandidate.getUrl();
            if (!StringUtils.hasText(url)) {
                continue;
            }
            if (target.getCollectedPage() != null) {
                CollectionExecutionResult prefetchedAuditResult = buildAuditResultFromPrefetchedPage(
                        context,
                        config,
                        sourceType,
                        index + 1,
                        target,
                        target.getCollectedPage());
                auditResults.add(prefetchedAuditResult);
                processedPageCounterRef[0]++;
                appendCollectedResultEntry(context, config, sourceType, searchExecutionResult, executionPlan,
                        progressSnapshots, targets, results, successCounterRef, evidenceCounterRef,
                        processedPageCounterRef[0], totalCollectedPages, prefetchedAuditResult,
                        target.getCollectedPage(), matchedCandidate, url);
                continue;
            }

            String stableIdentity = resolveStableCollectionIdentity(url,
                    matchedCandidate == null ? null : matchedCandidate.getSourceUrls());
            CollectionExecutionResult entryResult = StringUtils.hasText(stableIdentity)
                    ? entryResultByIdentity.remove(stableIdentity)
                    : null;
            if (entryResult == null && !unmatchedEntryResults.isEmpty()) {
                entryResult = unmatchedEntryResults.remove(0);
            }
            if (entryResult == null) {
                entryResult = buildMissingCollectionExecutionResult(context, index + 1, matchedCandidate);
            }
            auditResults.add(entryResult);
            processedPageCounterRef[0]++;
            appendCollectedResultEntry(context, config, sourceType, searchExecutionResult, executionPlan,
                    progressSnapshots, targets, results, successCounterRef, evidenceCounterRef,
                    processedPageCounterRef[0], totalCollectedPages, entryResult,
                    null, matchedCandidate, url);
        }

        for (CollectionExecutionResult unmatchedEntryResult : unmatchedEntryResults) {
            if (unmatchedEntryResult == null) {
                continue;
            }
            auditResults.add(unmatchedEntryResult);
            processedPageCounterRef[0]++;
            appendCollectedResultEntry(context, config, sourceType, searchExecutionResult, executionPlan,
                    progressSnapshots, targets, results, successCounterRef, evidenceCounterRef,
                    processedPageCounterRef[0], totalCollectedPages, unmatchedEntryResult,
                    null, buildSyntheticCandidateFromCollectionResult(unmatchedEntryResult, sourceType),
                    resolveCollectionResultPageUrl(unmatchedEntryResult));
        }

        for (CollectionExecutionResult discoveredChildResult : discoveredChildResults) {
            if (discoveredChildResult == null) {
                continue;
            }
            auditResults.add(discoveredChildResult);
            processedPageCounterRef[0]++;
            appendCollectedResultEntry(context, config, sourceType, searchExecutionResult, executionPlan,
                    progressSnapshots, targets, results, successCounterRef, evidenceCounterRef,
                    processedPageCounterRef[0], totalCollectedPages, discoveredChildResult,
                    null, buildSyntheticCandidateFromCollectionResult(discoveredChildResult, sourceType),
                    resolveCollectionResultPageUrl(discoveredChildResult));
        }

        collectionReport = collectionExecutionCoordinator.summarize(auditResults);

        try {
            if (successCounterRef[0] == 0) {
                markCollectStep(executionPlan, SearchExecutionStep.StepStatus.FAILED, "未采集到可用页面内容");
                progressSnapshots.add(buildProgressSnapshot(executionPlan,
                        "COLLECT_PAGES",
                        "未采集到可用页面内容",
                        Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                        readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
                String outputJson = buildCollectorOutput(
                        config, sourceType, context.getTaskRagPromptContext(), searchExecutionResult, collectionReport,
                        progressSnapshots, results, successCounterRef[0], targets);
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
                    config, sourceType, context.getTaskRagPromptContext(), searchExecutionResult, collectionReport,
                    progressSnapshots, results, successCounterRef[0], targets);
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
                    context.getTaskRagPromptContext(),
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
                    null,
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
                                        String taskRagContext,
                                        SearchExecutionResult searchExecutionResult,
                                        CollectionExecutionReport collectionReport,
                                        List<SearchProgressSnapshot> progressSnapshots,
                                        List<Map<String, Object>> results,
                                        int successCounter,
                                        List<SearchCollectionTarget> targets) throws JsonProcessingException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("competitor", config.getCompetitorName());
        output.put("sourceType", sourceType);
        // Collector 没有 Prompt，统一任务上下文需要直接出现在输出契约里，供事件流与前端解释层消费。
        output.put("taskRagContext", taskRagContext);
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
        output.put("collectionStatus", collectionReport == null ? null : collectionReport.getStatus());
        output.put("collectionAudit", collectionReport == null ? null : collectionReport.getAuditSnapshot());
        output.put("collectionReplayTimeline", collectionReport == null || collectionReport.getAuditSnapshot() == null
                ? List.of()
                : collectionReport.getAuditSnapshot().getReplayTimeline());
        CollectResult collectResult = buildCollectResult(config, results, successCounter);
        output.put("contractVersion", collectResult.getContractVersion());
        output.put("documents", collectResult.getDocuments());
        output.put("sourceUrls", collectResult.getSourceUrls());
        output.put("issueFlags", collectResult.getIssueFlags());
        output.put("evidenceFragments", collectResult.getEvidenceFragments());
        output.put("sectionEvidenceBundles", collectResult.getSectionEvidenceBundles());
        output.put("downstreamEvidenceViews", collectResult.getDownstreamEvidenceViews());
        output.put("knowledgeDocuments", collectResult.getKnowledgeDocuments());
        output.put("retrievalChunks", collectResult.getRetrievalChunks());
        output.put("retrievalIndexes", collectResult.getRetrievalIndexes());
        return objectMapper.writeValueAsString(output);
    }

    /**
     * Collector 的历史输出里已经有 results 明细，这里在不破坏旧字段的前提下，
     * 再组装一份稳定契约给下游使用，确保 sourceUrls / issueFlags / evidenceFragments 不会再散落在不同命名里。
     */
    private CollectResult buildCollectResult(CollectorNodeConfig config,
                                             List<Map<String, Object>> results,
                                             int successCounter) {
        List<CollectedDocument> documents = new ArrayList<>();
        List<EvidenceFragment> fragments = new ArrayList<>();
        List<SectionEvidenceBundle> sectionEvidenceBundles = new ArrayList<>();
        List<DownstreamEvidenceView> downstreamEvidenceViews = new ArrayList<>();
        List<KnowledgeDocument> knowledgeDocuments = new ArrayList<>();
        List<RetrievalChunk> retrievalChunks = new ArrayList<>();
        List<RetrievalIndex> retrievalIndexes = new ArrayList<>();
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();

        for (Map<String, Object> result : results) {
            List<String> documentSourceUrls = readStringList(result.get("sourceUrls"));
            List<String> documentIssueFlags = readStringList(result.get("issueFlags"));
            List<EvidenceFragment> documentFragments = readEvidenceFragments(result.get("evidenceFragments"));
            List<DownstreamEvidenceView> documentEvidenceViews = readDownstreamEvidenceViews(result.get("downstreamEvidenceViews"));
            KnowledgeDocument knowledgeDocument = readKnowledgeDocument(result.get("knowledgeDocument"));
            List<RetrievalChunk> documentRetrievalChunks = readRetrievalChunks(result.get("retrievalChunks"));
            RetrievalIndex retrievalIndex = readRetrievalIndex(result.get("retrievalIndex"));

            sourceUrls.addAll(documentSourceUrls);
            issueFlags.addAll(documentIssueFlags);
            fragments.addAll(documentFragments);
            downstreamEvidenceViews.addAll(documentEvidenceViews);
            SectionEvidenceBundle documentBundle = buildDocumentSectionEvidenceBundle(result, documentSourceUrls, documentIssueFlags, documentFragments);
            sectionEvidenceBundles.add(documentBundle);
            if (knowledgeDocument != null) {
                knowledgeDocuments.add(knowledgeDocument);
                sourceUrls.addAll(knowledgeDocument.getSourceUrls() == null ? List.of() : knowledgeDocument.getSourceUrls());
                issueFlags.addAll(knowledgeDocument.getIssueFlags() == null ? List.of() : knowledgeDocument.getIssueFlags());
            }
            if (retrievalIndex != null) {
                retrievalIndexes.add(retrievalIndex);
                sourceUrls.addAll(retrievalIndex.getSourceUrls() == null ? List.of() : retrievalIndex.getSourceUrls());
                issueFlags.addAll(retrievalIndex.getIssueFlags() == null ? List.of() : retrievalIndex.getIssueFlags());
            }
            retrievalChunks.addAll(documentRetrievalChunks);

            documents.add(CollectedDocument.builder()
                    .competitor(toText(result.get("competitor")))
                    .url(toText(result.get("url")))
                    .evidenceId(toText(result.get("evidenceId")))
                    .success(Boolean.TRUE.equals(result.get("success")))
                    .title(toText(result.get("title")))
                    .cleanedText(null)
                    .snippet(null)
                    .contentLength(parseInt(result.get("contentLength")))
                    .sourceCategory(toText(result.get("sourceCategory")))
                    .errorMessage(toText(result.get("errorMessage")))
                    .sourceUrls(documentSourceUrls)
                    .issueFlags(documentIssueFlags)
                    .evidenceFragments(documentFragments)
                    .sectionEvidenceBundles(List.of(documentBundle))
                    .downstreamEvidenceViews(documentEvidenceViews)
                    .knowledgeDocument(knowledgeDocument)
                    .retrievalChunks(documentRetrievalChunks)
                    .retrievalIndex(retrievalIndex)
                    .collectedAt(LocalDateTime.now())
                    .build());
        }

        if (successCounter > 0 && successCounter < results.size()) {
            issueFlags.add("PARTIAL_COLLECTION_FAILURE");
        }
        if (successCounter == 0 && !results.isEmpty()) {
            issueFlags.add("NO_USABLE_CONTENT");
        }
        if (sourceUrls.isEmpty() && config != null && config.getCompetitorUrls() != null) {
            sourceUrls.addAll(config.getCompetitorUrls());
            issueFlags.add("SOURCE_URLS_BACKFILLED");
        }

        return CollectResult.builder()
                .totalCollected(results.size())
                .totalEvidenceIds(successCounter)
                .documents(documents)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .issueFlags(new ArrayList<>(issueFlags))
                .evidenceFragments(normalizeEvidenceFragments(fragments))
                .sectionEvidenceBundles(normalizeSectionEvidenceBundles(sectionEvidenceBundles))
                .downstreamEvidenceViews(normalizeDownstreamEvidenceViews(downstreamEvidenceViews))
                .knowledgeDocuments(knowledgeDocuments)
                .retrievalChunks(retrievalChunks)
                .retrievalIndexes(retrievalIndexes)
                .build();
    }

    private List<String> buildCollectionIssueFlags(SourceCollector.CollectedPage page) {
        List<String> issueFlags = new ArrayList<>();
        if (page == null) {
            issueFlags.add("COLLECT_FAILED");
            return issueFlags;
        }
        if (!page.isSuccess()) {
            issueFlags.add("COLLECT_FAILED");
        }
        if ((page.getContent() == null || page.getContent().isBlank())
                && (page.getSnippet() == null || page.getSnippet().isBlank())) {
            issueFlags.add("CONTENT_GAP");
        }
        return issueFlags;
    }

    /**
     * 每个采集结果都至少生成一个 EvidenceFragment。
     * 即使页面抓取失败，也要把“失败发生在哪个 URL、对应哪个 evidenceId”传下去，避免后续链路只能看到一个抽象错误。
     */
    private List<EvidenceFragment> buildCollectedEvidenceFragments(CollectorNodeConfig config,
                                                                  String sourceType,
                                                                  SourceCollector.CollectedPage page,
                                                                  SourceCandidate matchedCandidate,
                                                                  String evidenceId,
                                                                  String url) {
        String snippet = page == null ? null : firstNonBlank(page.getSnippet(), page.getErrorMessage());
        String title = page == null ? null : firstNonBlank(page.getTitle(), matchedCandidate == null ? null : matchedCandidate.getTitle());
        EvidenceFragment fragment = EvidenceFragment.builder()
                .stage("COLLECT")
                .competitorName(config == null ? null : config.getCompetitorName())
                .fieldName(sourceType)
                .evidenceId(evidenceId)
                .sourceUrl(url)
                .title(title)
                .snippet(snippet)
                .issueFlags(buildCollectionIssueFlags(page))
                .build()
                .normalized();
        return List.of(fragment);
    }

    /**
     * 采集阶段虽然还没有结构化字段，但至少要把“哪篇页面支撑了哪个来源章节”这层关系固定下来，
     * 这样下游即使回退到采集契约，也能知道当前文档对应的证据段落与缺口状态。
     */
    /**
     * Collector 在输出阶段就生成统一下游视图，后续 extractor/analyzer 只消费该视图，
     * 不再分别解析 pageMetadata 中的质量信号和结构化块。
     */
    private List<DownstreamEvidenceView> buildDownstreamEvidenceViews(CollectorNodeConfig config,
                                                                      String sourceType,
                                                                      SourceCollector.CollectedPage page,
                                                                      String evidenceId,
                                                                      String effectiveUrl,
                                                                      String pageMetadata) {
        EvidenceSource evidence = EvidenceSource.builder()
                .competitorName(config == null ? null : config.getCompetitorName())
                .evidenceId(evidenceId)
                .title(page == null ? effectiveUrl : firstNonBlank(page.getTitle(), effectiveUrl))
                .url(effectiveUrl)
                .contentSnippet(page == null ? null : page.getSnippet())
                .fullContent(page == null ? null : page.getContent())
                .pageMetadata(pageMetadata)
                .sourceType(sourceType)
                .build();
        return downstreamEvidenceViewAssembler.fromEvidenceSources(List.of(evidence));
    }

    private SectionEvidenceBundle buildDocumentSectionEvidenceBundle(Map<String, Object> result,
                                                                    List<String> documentSourceUrls,
                                                                    List<String> documentIssueFlags,
                                                                    List<EvidenceFragment> documentFragments) {
        String sourceType = firstNonBlank(toText(result.get("sourceType")), "COLLECT");
        LinkedHashSet<String> missingFields = new LinkedHashSet<>();
        if (documentIssueFlags.contains("COLLECT_FAILED") || documentIssueFlags.contains("CONTENT_GAP")) {
            missingFields.add(sourceType);
        }
        return SectionEvidenceBundle.builder()
                .stage("COLLECT")
                .sectionType("SECTION")
                .sectionKey(sourceType.toLowerCase())
                .sectionTitle(sourceType)
                .summary(firstNonBlank(toText(result.get("title")), toText(result.get("url"))))
                .fieldNames(List.of(sourceType))
                .missingFields(new ArrayList<>(missingFields))
                .sourceUrls(documentSourceUrls)
                .issueFlags(documentIssueFlags)
                .evidenceFragments(documentFragments)
                .build()
                .normalized();
    }

    private KnowledgeDocument readKnowledgeDocument(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, KnowledgeDocument.class);
    }

    private List<RetrievalChunk> readRetrievalChunks(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (Object item : items) {
            RetrievalChunk chunk = objectMapper.convertValue(item, RetrievalChunk.class);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private RetrievalIndex readRetrievalIndex(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, RetrievalIndex.class);
    }

    private List<String> readStringList(Object value) {
        if (value instanceof List<?> items) {
            List<String> values = new ArrayList<>();
            for (Object item : items) {
                String text = toText(item);
                if (text != null && !text.isBlank()) {
                    values.add(text);
                }
            }
            return values;
        }
        return List.of();
    }

    private List<EvidenceFragment> readEvidenceFragments(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (Object item : items) {
            EvidenceFragment fragment = objectMapper.convertValue(item, EvidenceFragment.class);
            if (fragment != null) {
                fragments.add(fragment.normalized());
            }
        }
        return fragments;
    }

    private List<DownstreamEvidenceView> readDownstreamEvidenceViews(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<DownstreamEvidenceView> views = new ArrayList<>();
        for (Object item : items) {
            DownstreamEvidenceView view = objectMapper.convertValue(item, DownstreamEvidenceView.class);
            if (view != null) {
                views.add(view.normalized());
            }
        }
        return views;
    }

    private List<EvidenceFragment> normalizeEvidenceFragments(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments) {
            if (fragment != null) {
                normalized.add(fragment.normalized());
            }
        }
        return normalized;
    }

    private List<DownstreamEvidenceView> normalizeDownstreamEvidenceViews(List<DownstreamEvidenceView> views) {
        List<DownstreamEvidenceView> normalized = new ArrayList<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            if (view != null) {
                normalized.add(view.normalized());
            }
        }
        return normalized;
    }

    private List<SectionEvidenceBundle> normalizeSectionEvidenceBundles(List<SectionEvidenceBundle> bundles) {
        List<SectionEvidenceBundle> normalized = new ArrayList<>();
        for (SectionEvidenceBundle bundle : bundles) {
            if (bundle != null) {
                normalized.add(bundle.normalized());
            }
        }
        return normalized;
    }

    private List<String> mergeIssueFlags(List<String> currentFlags, List<String> extraFlags) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (currentFlags != null) {
            merged.addAll(currentFlags);
        }
        if (extraFlags != null) {
            merged.addAll(extraFlags);
        }
        return new ArrayList<>(merged);
    }

    /**
     * 来源分类要在采集阶段就固定下来，否则后续任务级 RAG 很难区分“用户指定的证据”与“系统补源发现的证据”。
     */
    private String resolveSourceCategory(SourceCandidate matchedCandidate) {
        String discoveryMethod = matchedCandidate == null ? null : matchedCandidate.getDiscoveryMethod();
        if (!StringUtils.hasText(discoveryMethod)) {
            return "USER_PROVIDED";
        }
        String normalizedMethod = discoveryMethod.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedMethod.contains("UPLOAD")) {
            return "UPLOADED_DOCUMENTS";
        }
        if (normalizedMethod.contains("AUTH")
                || normalizedMethod.contains("API")
                || normalizedMethod.contains("CONNECTOR")) {
            return "AUTHENTICATED_SOURCES";
        }
        if (normalizedMethod.contains("CONFIG")
                || normalizedMethod.contains("MANUAL")
                || normalizedMethod.contains("USER")) {
            return "USER_PROVIDED";
        }
        return "AI_DISCOVERED";
    }

    private Map<String, Object> toKnowledgeDocumentPayload(KnowledgeDocument document) {
        if (document == null) {
            return null;
        }
        return objectMapper.convertValue(document, Map.class);
    }

    private List<Map<String, Object>> toRetrievalChunkPayloads(List<RetrievalChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (RetrievalChunk chunk : chunks) {
            if (chunk != null) {
                payloads.add(objectMapper.convertValue(chunk, Map.class));
            }
        }
        return payloads;
    }

    private Map<String, Object> toRetrievalIndexPayload(RetrievalIndex retrievalIndex) {
        if (retrievalIndex == null) {
            return null;
        }
        return objectMapper.convertValue(retrievalIndex, Map.class);
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
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
        if (page == null) {
            return null;
        }
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
            metadata.put("trustTier", matchedCandidate.getTrustTier() == null ? null : matchedCandidate.getTrustTier().name());
            metadata.put("trustTierLabel", matchedCandidate.getTrustTierLabel());
            metadata.put("rankingReasons", matchedCandidate.getRankingReasons());
            metadata.put("rankingSummary", matchedCandidate.getRankingSummary());
            metadata.put("searchQuery", matchedCandidate.getSearchQuery());
            metadata.put("searchEngine", matchedCandidate.getSearchEngine());
            metadata.put("resultRank", matchedCandidate.getResultRank());
            metadata.put("browserTraceId", matchedCandidate.getBrowserTraceId());
            metadata.put("verified", matchedCandidate.getVerified());
            metadata.put("verificationReason", matchedCandidate.getVerificationReason());
            metadata.put("matchedSignals", matchedCandidate.getMatchedSignals());
            metadata.put("selectionStage", matchedCandidate.getSelectionStage());
            metadata.put("selectionReason", matchedCandidate.getSelectionReason());
            metadata.put("selectionSummary", matchedCandidate.getSelectionSummary());
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
     * 只有未复用旧页面的目标才需要交给新的采集协调器执行。
     * 这里显式排除空目标和空 URL，避免主链路因为脏数据把执行结果和目标序号错位。
     */
    private boolean requiresCoordinatorExecution(SearchCollectionTarget target) {
        return target != null
                && target.getCollectedPage() == null
                && target.getCandidate() != null
                && StringUtils.hasText(target.getCandidate().getUrl());
    }

    private Map<String, SourceCandidate> indexExecutableCandidates(List<SearchCollectionTarget> targets) {
        Map<String, SourceCandidate> indexedCandidates = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) {
            return indexedCandidates;
        }
        for (SearchCollectionTarget target : targets) {
            if (!requiresCoordinatorExecution(target) || target.getCandidate() == null) {
                continue;
            }
            String stableIdentity = resolveStableCollectionIdentity(
                    target.getCandidate().getUrl(),
                    target.getCandidate().getSourceUrls()
            );
            if (StringUtils.hasText(stableIdentity)) {
                indexedCandidates.putIfAbsent(stableIdentity, target.getCandidate());
            }
        }
        return indexedCandidates;
    }

    private boolean isEntryCollectionResult(CollectionExecutionResult result) {
        return result != null
                && (result.getDiscoveryDepth() == null || result.getDiscoveryDepth() <= 0);
    }

    /**
     * 这里统一承接入口页和递归子页的证据落库、结果组装和进度写回，
     * 避免递归采集引入第二套并行输出逻辑，导致 documents / audit / replay 语义再次分叉。
     */
    private void appendCollectedResultEntry(AgentContext context,
                                            CollectorNodeConfig config,
                                            String defaultSourceType,
                                            SearchExecutionResult searchExecutionResult,
                                            SearchExecutionPlan executionPlan,
                                            List<SearchProgressSnapshot> progressSnapshots,
                                            List<SearchCollectionTarget> targets,
                                            List<Map<String, Object>> results,
                                            int[] successCounterRef,
                                            int[] evidenceCounterRef,
                                            int processedPageCount,
                                            int totalCollectedPages,
                                            CollectionExecutionResult collectionResult,
                                            SourceCollector.CollectedPage prefetchedPage,
                                            SourceCandidate matchedCandidate,
                                            String fallbackUrl) {
        SourceCandidate effectiveCandidate = enrichMatchedCandidate(matchedCandidate, collectionResult, defaultSourceType);
        String effectiveSourceType = effectiveCandidate == null || !StringUtils.hasText(effectiveCandidate.getSourceType())
                ? defaultSourceType
                : effectiveCandidate.getSourceType();
        SourceCollector.CollectedPage page = prefetchedPage != null
                ? prefetchedPage
                : mapCollectionResultToCollectedPage(collectionResult, config.getCompetitorName(), effectiveSourceType);
        List<String> collectedSourceUrls = resolveCollectedSourceUrls(page, effectiveCandidate, fallbackUrl);
        String effectiveUrl = resolveEffectiveCollectedUrl(page, collectionResult, collectedSourceUrls, fallbackUrl);
        evidenceCounterRef[0]++;
        String evidenceId = generateEvidenceId(context.getTaskId(), context.getCurrentNodeName(), evidenceCounterRef[0]);
        String pageMetadata = mergePageMetadata(page, effectiveCandidate);

        TaskRetrievalIndexingResult retrievalIndexingResult = null;
        String knowledgeFailureReason = null;
        if (isUsableCollectedPage(page)) {
            EvidenceSource evidence = EvidenceSource.builder()
                    .taskId(context.getTaskId())
                    .competitorName(config.getCompetitorName())
                    .evidenceId(evidenceId)
                    .title(page.getTitle() != null ? page.getTitle() : effectiveUrl)
                    .url(effectiveUrl)
                    .contentSnippet(page.getSnippet())
                    .fullContent(page.getContent())
                    .pageMetadata(pageMetadata)
                    .sourceType(effectiveSourceType)
                    .discoveryMethod(effectiveCandidate == null ? null : effectiveCandidate.getDiscoveryMethod())
                    .sourceCategory(resolveSourceCategory(effectiveCandidate))
                    .sourceDomain(effectiveCandidate == null ? null : effectiveCandidate.getDomain())
                    .discoveryReason(effectiveCandidate == null ? config.getDiscoveryNotes() : effectiveCandidate.getReason())
                    .publishedAt(effectiveCandidate == null ? null : effectiveCandidate.getPublishedAt())
                    .sourceScore(effectiveCandidate == null ? null : effectiveCandidate.getTotalScore())
                    .collectedAt(LocalDateTime.now())
                    .build();
            evidenceRepository.save(evidence);
            successCounterRef[0]++;
            try {
                retrievalIndexingResult = taskRetrievalIndexService.indexEvidence(evidence);
            } catch (Exception e) {
                knowledgeFailureReason = e.getMessage();
                log.warn("index collected evidence failed, taskId={}, evidenceId={}",
                        context.getTaskId(), evidenceId, e);
            }
        }

        Map<String, Object> resultEntry = new LinkedHashMap<>();
        List<String> collectionIssueFlags = new ArrayList<>(buildCollectionIssueFlags(page));
        if (retrievalIndexingResult != null && retrievalIndexingResult.issueFlags() != null) {
            collectionIssueFlags = mergeIssueFlags(collectionIssueFlags, retrievalIndexingResult.issueFlags());
        }
        if (knowledgeFailureReason != null && !knowledgeFailureReason.isBlank()) {
            collectionIssueFlags = mergeIssueFlags(collectionIssueFlags, List.of("KNOWLEDGE_INDEX_FAILED"));
        }
        resultEntry.put("competitor", config.getCompetitorName());
        resultEntry.put("sourceType", effectiveSourceType);
        resultEntry.put("sourceCategory", resolveSourceCategory(effectiveCandidate));
        resultEntry.put("url", effectiveUrl);
        resultEntry.put("evidenceId", evidenceId);
        resultEntry.put("success", page.isSuccess());
        resultEntry.put("title", page.getTitle());
        resultEntry.put("contentLength", page.getContent() != null ? page.getContent().length() : 0);
        resultEntry.put("errorMessage", page.getErrorMessage());
        resultEntry.put("persisted", isUsableCollectedPage(page));
        resultEntry.put("publishedAt", effectiveCandidate == null ? null : effectiveCandidate.getPublishedAt());
        resultEntry.put("discoveryMethod", effectiveCandidate == null ? null : effectiveCandidate.getDiscoveryMethod());
        resultEntry.put("reason", effectiveCandidate == null ? config.getDiscoveryNotes() : effectiveCandidate.getReason());
        resultEntry.put("domain", effectiveCandidate == null ? null : effectiveCandidate.getDomain());
        resultEntry.put("score", effectiveCandidate == null ? null : effectiveCandidate.getTotalScore());
        resultEntry.put("trustTier", effectiveCandidate == null || effectiveCandidate.getTrustTier() == null
                ? null
                : effectiveCandidate.getTrustTier().name());
        resultEntry.put("trustTierLabel", effectiveCandidate == null ? null : effectiveCandidate.getTrustTierLabel());
        resultEntry.put("rankingReasons", effectiveCandidate == null ? null : effectiveCandidate.getRankingReasons());
        resultEntry.put("rankingSummary", effectiveCandidate == null ? null : effectiveCandidate.getRankingSummary());
        resultEntry.put("browserTraceId", effectiveCandidate == null ? null : effectiveCandidate.getBrowserTraceId());
        resultEntry.put("selectionStage", effectiveCandidate == null ? null : effectiveCandidate.getSelectionStage());
        resultEntry.put("selectionReason", effectiveCandidate == null ? null : effectiveCandidate.getSelectionReason());
        resultEntry.put("selectionSummary", effectiveCandidate == null ? null : effectiveCandidate.getSelectionSummary());
        resultEntry.put("sourceUrls", collectedSourceUrls);
        resultEntry.put("issueFlags", collectionIssueFlags);
        resultEntry.put("evidenceFragments", buildCollectedEvidenceFragments(
                config, effectiveSourceType, page, effectiveCandidate, evidenceId, effectiveUrl));
        resultEntry.put("downstreamEvidenceViews", buildDownstreamEvidenceViews(
                config, effectiveSourceType, page, evidenceId, effectiveUrl, pageMetadata));
        if (retrievalIndexingResult != null) {
            resultEntry.put("knowledgeDocument", toKnowledgeDocumentPayload(retrievalIndexingResult.knowledgeDocument()));
            resultEntry.put("retrievalChunks", toRetrievalChunkPayloads(retrievalIndexingResult.retrievalChunks()));
            resultEntry.put("retrievalIndex", toRetrievalIndexPayload(retrievalIndexingResult.retrievalIndex()));
            resultEntry.put("knowledgeFailureReason", retrievalIndexingResult.failureReason());
        } else if (knowledgeFailureReason != null && !knowledgeFailureReason.isBlank()) {
            resultEntry.put("knowledgeFailureReason", knowledgeFailureReason);
        }
        results.add(resultEntry);

        progressSnapshots.add(buildProgressSnapshot(executionPlan,
                "COLLECT_PAGES",
                buildCollectionProgressMessage(collectionResult, page, fallbackUrl, processedPageCount, totalCollectedPages),
                Boolean.TRUE.equals(readBoolean(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegraded)),
                readString(searchExecutionResult.getExecutionTrace(), SearchExecutionTrace::getDegradationReason)));
        persistRunningOutput(context, config, defaultSourceType, executionPlan, progressSnapshots,
                searchExecutionResult.getSourceCandidates(), targets, searchExecutionResult.getExecutionTrace(),
                results, successCounterRef[0]);
    }

    private String buildCollectionProgressMessage(CollectionExecutionResult collectionResult,
                                                  SourceCollector.CollectedPage page,
                                                  String fallbackUrl,
                                                  int processedPageCount,
                                                  int totalCollectedPages) {
        String pageStageLabel = collectionResult != null
                && collectionResult.getDiscoveryDepth() != null
                && collectionResult.getDiscoveryDepth() > 0
                ? "内部发现页面"
                : "入口页面";
        String pageLabel = firstNonBlank(page == null ? null : page.getTitle(),
                firstNonBlank(page == null ? null : page.getUrl(), fallbackUrl));
        return "正在抓取" + pageStageLabel + " " + processedPageCount + "/" + totalCollectedPages
                + "：" + pageLabel;
    }

    private SourceCandidate enrichMatchedCandidate(SourceCandidate matchedCandidate,
                                                   CollectionExecutionResult collectionResult,
                                                   String defaultSourceType) {
        if (matchedCandidate == null) {
            return buildSyntheticCandidateFromCollectionResult(collectionResult, defaultSourceType);
        }
        String effectiveUrl = firstNonBlank(matchedCandidate.getUrl(), resolveCollectionResultPageUrl(collectionResult));
        List<String> effectiveSourceUrls = matchedCandidate.getSourceUrls() == null || matchedCandidate.getSourceUrls().isEmpty()
                ? (collectionResult == null || collectionResult.getSourceUrls() == null ? List.of() : collectionResult.getSourceUrls())
                : matchedCandidate.getSourceUrls();
        return matchedCandidate.toBuilder()
                .url(effectiveUrl)
                .title(firstNonBlank(matchedCandidate.getTitle(), collectionResult == null ? null : collectionResult.getTitle()))
                .sourceType(firstNonBlank(matchedCandidate.getSourceType(), defaultSourceType))
                .domain(firstNonBlank(matchedCandidate.getDomain(), canonicalUrlResolver.canonicalDomain(effectiveUrl)))
                .sourceUrls(effectiveSourceUrls)
                .build();
    }

    private SourceCandidate buildSyntheticCandidateFromCollectionResult(CollectionExecutionResult result,
                                                                        String defaultSourceType) {
        if (result == null) {
            return null;
        }
        String effectiveUrl = resolveCollectionResultPageUrl(result);
        String discoveryMethod = result.getDiscoveryDepth() != null && result.getDiscoveryDepth() > 0
                ? "INTERNAL_LINK_DISCOVERY"
                : "COLLECTION_EXECUTION_RESULT";
        return SourceCandidate.builder()
                .url(effectiveUrl)
                .title(result.getTitle())
                .sourceType(StringUtils.hasText(defaultSourceType) ? defaultSourceType : "OFFICIAL")
                .discoveryMethod(discoveryMethod)
                .reason("INTERNAL_LINK_DISCOVERY".equals(discoveryMethod)
                        ? "internally discovered child page collected through recursive collection"
                        : "collection result returned without selected target metadata")
                .domain(canonicalUrlResolver.canonicalDomain(effectiveUrl))
                .sourceUrls(result.getSourceUrls() == null ? List.of() : result.getSourceUrls())
                .qualitySignals(result.getQualitySignals() == null ? List.of() : result.getQualitySignals())
                .qualityScore(result.getQualityScore() == null ? 0.0D : result.getQualityScore())
                .build();
    }

    private CollectionExecutionResult buildMissingCollectionExecutionResult(AgentContext context,
                                                                            int targetIndex,
                                                                            SourceCandidate matchedCandidate) {
        String resourceLocator = matchedCandidate == null ? null : matchedCandidate.getUrl();
        List<String> sourceUrls = matchedCandidate == null || matchedCandidate.getSourceUrls() == null
                ? (StringUtils.hasText(resourceLocator) ? List.of(resourceLocator) : List.of())
                : matchedCandidate.getSourceUrls();
        return CollectionExecutionResult.builder()
                .taskPackageKey(context.getCurrentNodeName() + "#" + String.format("%03d", targetIndex))
                .targetIndex(targetIndex)
                .executorType("UNKNOWN")
                .success(false)
                .status("FAILED")
                .resourceLocator(resourceLocator)
                .sourceUrls(sourceUrls)
                .discoveryDepth(0)
                .errorMessage("collection executor returned no result")
                .failureKind("COLLECTION_RESULT_MISSING")
                .qualitySignals(List.of("COLLECTION_RESULT_MISSING"))
                .reusedFromCheckpoint(false)
                .build()
                .normalize();
    }

    /**
     * 将新的采集执行结果兼容映射回旧的 CollectedPage 契约，确保证据入库、CollectResult 组装、
     * 以及 Task RAG 索引都还能复用现有逻辑。如果 structured payload 序列化失败，只记录日志并降级为 null。
     */
    private SourceCollector.CollectedPage mapCollectionResultToCollectedPage(CollectionExecutionResult result,
                                                                             String competitorName,
                                                                             String sourceType) {
        if (result == null) {
            return buildMissingCollectionResultPage(competitorName, sourceType, null);
        }
        String effectiveUrl = resolveCollectionResultPageUrl(result);
        String content = result.getContent();
        String snippet = content == null ? null : content.substring(0, Math.min(500, content.length()));
        return SourceCollector.CollectedPage.builder()
                .url(effectiveUrl)
                .title(result.getTitle())
                .content(content)
                .snippet(snippet)
                .metadata(serializeCollectionResultMetadata(result))
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(result.isSuccess())
                .errorMessage(result.getErrorMessage())
                .build();
    }

    private SourceCollector.CollectedPage buildMissingCollectionResultPage(String competitorName,
                                                                           String sourceType,
                                                                           String fallbackUrl) {
        return SourceCollector.CollectedPage.builder()
                .url(fallbackUrl)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(false)
                .errorMessage("collection executor returned no result")
                .build();
    }

    /**
     * 搜索验证阶段若已经预抓到了页面，无论成功还是失败，都要继续映射成正式 collection result。
     * 这样 collectionAudit / replay / checkpoint 才能表达“这个包发生过什么”，而不是在失败时丢成空 SUCCESS。
     */
    private CollectionExecutionResult buildAuditResultFromPrefetchedPage(AgentContext context,
                                                                         CollectorNodeConfig config,
                                                                         String sourceType,
                                                                         int targetIndex,
                                                                         SearchCollectionTarget target,
                                                                         SourceCollector.CollectedPage page) {
        SourceCandidate candidate = target == null ? null : target.getCandidate();
        String resourceLocator = candidate == null ? null : candidate.getUrl();
        String effectiveUrl = firstNonBlank(page == null ? null : page.getUrl(), resourceLocator);
        List<String> sourceUrls = resolveCollectedSourceUrls(page, candidate, effectiveUrl);
        String taskPackageKey = context.getCurrentNodeName() + "#" + String.format("%03d", targetIndex);
        String content = page == null ? null : page.getContent();
        String failureKind = page != null && !page.isSuccess() ? "PREFETCH_FAILED" : null;
        CollectionExecutionResult checkpointResult = findReusableCollectionCheckpointResult(
                config.getCollectionAuditCheckpoint(),
                resourceLocator,
                sourceUrls
        );
        boolean reusedFromCheckpoint = checkpointResult != null
                && page != null
                && page.isSuccess()
                && isUsableCollectedPage(page);
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackageKey)
                .targetIndex(targetIndex)
                .executorType("PREFETCHED_PAGE")
                .success(page != null && page.isSuccess() && isUsableCollectedPage(page))
                .status(page != null && page.isSuccess() && isUsableCollectedPage(page) ? "SUCCESS" : "FAILED")
                .resourceLocator(resourceLocator)
                .title(firstNonBlank(page == null ? null : page.getTitle(),
                        candidate == null ? null : candidate.getTitle()))
                .content(content)
                .sourceUrls(sourceUrls)
                .discoveryDepth(0)
                .errorMessage(page == null ? "prefetched page missing" : page.getErrorMessage())
                .failureKind(failureKind)
                .qualitySignals(page != null && page.isSuccess() && isUsableCollectedPage(page)
                        ? List.of("PREFETCH_REUSED")
                        : List.of("PREFETCH_FAILED"))
                .checkpointSource(reusedFromCheckpoint ? "collectionAuditCheckpoint" : null)
                .reusedFromCheckpoint(reusedFromCheckpoint)
                .build()
                .normalize();
    }

    /**
     * 这里只提取“验证阶段已经预抓取过，但内容仍不可用”的 attemptedTargets。
     * 这样在 selectedTargets 为空时，Collector 仍能把失败页纳入正式 collectionAudit，而不是直接丢成空审计。
     */
    private List<SearchCollectionTarget> resolveFailedPrefetchedAttemptTargets(SearchExecutionResult searchExecutionResult) {
        if (searchExecutionResult == null
                || searchExecutionResult.getAttemptedTargets() == null
                || searchExecutionResult.getAttemptedTargets().isEmpty()) {
            return List.of();
        }
        return searchExecutionResult.getAttemptedTargets().stream()
                .filter(target -> target != null
                        && target.getCollectedPage() != null
                        && target.getCandidate() != null
                        && StringUtils.hasText(target.getCandidate().getUrl())
                        && !isUsableCollectedPage(target.getCollectedPage()))
                .toList();
    }

    /**
     * rerun / resume 后，搜索检查点可能已经把成功页面以 prefetched page 形式重新挂回 selectedTargets，
     * 这时这些目标不会再次进入 CollectionExecutionCoordinator。
     * 为了让 collectionAudit 继续表达“这个成功包其实是从 checkpoint 复用来的”，
     * 这里按稳定来源锚点去历史 collectionAuditCheckpoint 里查找可复用成功结果。
     */
    private CollectionExecutionResult findReusableCollectionCheckpointResult(CollectionAuditSnapshot checkpoint,
                                                                             String resourceLocator,
                                                                             List<String> sourceUrls) {
        if (checkpoint == null || checkpoint.getResults() == null || checkpoint.getResults().isEmpty()) {
            return null;
        }
        String currentStableIdentity = resolveStableCollectionIdentity(resourceLocator, sourceUrls);
        if (!StringUtils.hasText(currentStableIdentity)) {
            return null;
        }
        for (CollectionExecutionResult result : checkpoint.getResults()) {
            if (result == null
                    || !result.isSuccess()
                    || !"SUCCESS".equalsIgnoreCase(result.getStatus())) {
                continue;
            }
            String checkpointIdentity = resolveStableCollectionIdentity(
                    result.getResourceLocator(),
                    result.getSourceUrls()
            );
            if (currentStableIdentity.equals(checkpointIdentity)) {
                return result;
            }
        }
        return null;
    }

    private String resolveStableCollectionIdentity(String resourceLocator, List<String> sourceUrls) {
        String canonicalResourceLocator = canonicalUrlResolver.canonicalize(resourceLocator);
        if (StringUtils.hasText(canonicalResourceLocator)) {
            return canonicalResourceLocator;
        }
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return null;
        }
        for (String sourceUrl : sourceUrls) {
            String canonicalSourceUrl = canonicalUrlResolver.canonicalize(sourceUrl);
            if (StringUtils.hasText(canonicalSourceUrl)) {
                return canonicalSourceUrl;
            }
        }
        return null;
    }

    /**
     * 结构化采集结果需要把 executor/sourceUrls/structuredPayload 一起固化到 metadata 中，
     * 这样即使下游仍消费旧的 CollectedPage，也不会丢掉 API 采集的关键信息。
     */
    private String serializeCollectionResultMetadata(CollectionExecutionResult result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executorType", result.getExecutorType());
        metadata.put("resourceLocator", result.getResourceLocator());
        metadata.put("sourceUrls", result.getSourceUrls() == null ? List.of() : result.getSourceUrls());
        metadata.put("discoveryDepth", result.getDiscoveryDepth() == null ? 0 : result.getDiscoveryDepth());
        metadata.put("qualitySignals", result.getQualitySignals() == null ? List.of() : result.getQualitySignals());
        metadata.put("qualityScore", result.getQualityScore());
        metadata.put("failureKind", result.getFailureKind());
        metadata.put("structuredBlocks", result.getStructuredBlocks() == null ? List.of() : result.getStructuredBlocks());
        metadata.put("durationMillis", result.getDurationMillis());
        metadata.put("collectedAt", result.getCollectedAt());
        if (result.getStructuredPayload() != null && !result.getStructuredPayload().isEmpty()) {
            metadata.put("structuredPayload", result.getStructuredPayload());
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            log.warn("serialize collection execution result metadata failed", exception);
            return null;
        }
    }

    /**
     * sourceUrls 是“无幻觉”追溯链路的硬约束，因此优先读取采集执行器返回的 sourceUrls，
     * 其次回退到候选元数据和最终落库 URL，确保每条证据都能稳定回指来源。
     */
    private List<String> resolveCollectedSourceUrls(SourceCollector.CollectedPage page,
                                                    SourceCandidate matchedCandidate,
                                                    String fallbackUrl) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        JsonNode metadata = page == null ? null : readJson(page.getMetadata());
        if (metadata != null && metadata.path("sourceUrls").isArray()) {
            metadata.path("sourceUrls").forEach(node -> {
                if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                    sourceUrls.add(node.asText());
                }
            });
        }
        if (matchedCandidate != null && matchedCandidate.getSourceUrls() != null) {
            for (String sourceUrl : matchedCandidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    sourceUrls.add(sourceUrl);
                }
            }
        }
        if (page != null && StringUtils.hasText(page.getUrl())) {
            sourceUrls.add(page.getUrl());
        }
        if (sourceUrls.isEmpty() && StringUtils.hasText(fallbackUrl)) {
            sourceUrls.add(fallbackUrl);
        }
        return new ArrayList<>(sourceUrls);
    }

    private String resolveEffectiveCollectedUrl(SourceCollector.CollectedPage page,
                                                CollectionExecutionResult collectionResult,
                                                List<String> collectedSourceUrls,
                                                String fallbackUrl) {
        String pageUrl = page == null ? null : page.getUrl();
        if (StringUtils.hasText(pageUrl)) {
            return pageUrl;
        }
        String collectionResultUrl = resolveCollectionResultPageUrl(collectionResult);
        if (StringUtils.hasText(collectionResultUrl)) {
            return collectionResultUrl;
        }
        if (collectedSourceUrls != null) {
            for (int index = collectedSourceUrls.size() - 1; index >= 0; index--) {
                String sourceUrl = collectedSourceUrls.get(index);
                if (StringUtils.hasText(sourceUrl)) {
                    return sourceUrl;
                }
            }
        }
        return fallbackUrl;
    }

    private String resolveCollectionResultPageUrl(CollectionExecutionResult result) {
        if (result == null) {
            return null;
        }
        if (isHttpUrl(result.getResourceLocator())) {
            return result.getResourceLocator();
        }
        if (result.getSourceUrls() != null) {
            for (int index = result.getSourceUrls().size() - 1; index >= 0; index--) {
                String sourceUrl = result.getSourceUrls().get(index);
                if (StringUtils.hasText(sourceUrl)) {
                    return sourceUrl;
                }
            }
        }
        return result.getResourceLocator();
    }

    private boolean isHttpUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String normalized = url.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private boolean hasStructuredPayloadMetadata(SourceCollector.CollectedPage page) {
        JsonNode metadata = page == null ? null : readJson(page.getMetadata());
        return metadata != null
                && metadata.path("structuredPayload").isObject()
                && metadata.path("structuredPayload").size() > 0;
    }

    private boolean hasStructuredBlockMetadata(SourceCollector.CollectedPage page) {
        JsonNode metadata = page == null ? null : readJson(page.getMetadata());
        return metadata != null
                && metadata.path("structuredBlocks").isArray()
                && metadata.path("structuredBlocks").size() > 0;
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
        return hasContent || hasSnippet || hasStructuredPayloadMetadata(page) || hasStructuredBlockMetadata(page);
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
            item.put("targetSelectionSummary", target.getCandidate().getSelectionSummary());
            item.put("selectionSummary", target.getCandidate().getSelectionSummary());
            item.put("trustTier", target.getCandidate().getTrustTier() == null
                    ? null
                    : target.getCandidate().getTrustTier().name());
            item.put("trustTierLabel", target.getCandidate().getTrustTierLabel());
            item.put("totalScore", target.getCandidate().getTotalScore());
            item.put("rankingReasons", target.getCandidate().getRankingReasons());
            item.put("rankingSummary", target.getCandidate().getRankingSummary());
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
