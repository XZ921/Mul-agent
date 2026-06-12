package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 运行期搜索编排器。
 * 在正式采集前统一完成“读取候选 -> 验证高优先级候选 -> 不足时增补 -> 最终选源”。
 */
@Component
@RequiredArgsConstructor
public class SearchExecutionCoordinator {

    private final CandidateVerifier candidateVerifier;
    private final BrowserSearchRuntimeService browserSearchRuntimeService;
    private final SearchSourceProvider searchSourceProvider;
    private final SourceCandidateRanker sourceCandidateRanker;
    private final CollectionTargetSelector collectionTargetSelector;
    private final SearchPolicyResolver searchPolicyResolver;

    public SearchExecutionResult execute(CollectorNodeConfig config) {
        return execute(config, null);
    }

    public SearchExecutionResult execute(CollectorNodeConfig config,
                                         Consumer<SearchExecutionUpdate> progressListener) {
        long searchStartedAt = System.currentTimeMillis();
        SearchExecutionPlan executionPlan = initializePlan(config.getSearchExecutionPlan());
        long searchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(
                config.getSearchTimeoutMillis(),
                executionPlan
        );
        List<SearchProgressSnapshot> progressSnapshots = new ArrayList<>();
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        SearchAuditSnapshot checkpoint = config.getSearchAuditCheckpoint();
        boolean resumedFromCheckpoint = checkpoint != null && checkpoint.getExecutionTrace() != null;
        // 如果有检查点，从检查点加载；否则解析初始配置
        String checkpointSource = resumedFromCheckpoint ? "NODE_CONFIG_CHECKPOINT" : null;
        List<SourceCandidate> allCandidates = normalizeCandidates(
                resumedFromCheckpoint ? resolveCandidatesFromCheckpoint(checkpoint) : resolveInitialCandidates(config),
                "PLANNED",
                config
        );
        // ====================== 块1 结束 纯前置准备，不执行业务逻辑======================
        if (resumedFromCheckpoint) {
            attemptedTargets.putAll(resolveAttemptedTargetsFromCheckpoint(checkpoint));
        }
        int targetCount = searchPolicyResolver.resolveTargetCount(
                config.getMaxSearchResults(),
                config.getCompetitorUrls(),
                allCandidates.size()
        );
        int plannedUrlCount = config.getCompetitorUrls() == null ? 0 : config.getCompetitorUrls().size();
        int minVerifiedCount = searchPolicyResolver.resolveMinVerifiedCandidates(
                config.getMinVerifiedCandidates(),
                plannedUrlCount,
                targetCount
        );
        executionPlan = enrichExecutionPlan(executionPlan, config, targetCount, minVerifiedCount);
        boolean circuitBroken = false;
        String degradationReason = null;

        markStepRunning(executionPlan, "LOAD_CANDIDATES", "正在读取规划期候选来源");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "LOAD_CANDIDATES",
                "正在读取规划期候选来源", false, null, progressListener, allCandidates, List.of(), null);
        markStepSuccess(executionPlan, "LOAD_CANDIDATES",
                (resumedFromCheckpoint ? "已从恢复检查点加载 " : "已加载 ") + allCandidates.size() + " 条规划期候选来源");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "LOAD_CANDIDATES",
                (resumedFromCheckpoint ? "已从恢复检查点加载 " : "已加载 ") + allCandidates.size() + " 条规划期候选来源",
                false, null, progressListener, allCandidates, List.of(), null);

        int verifiedCount = 0;
        int supplementedCount = 0;
        boolean resultPageVerificationEnabled = isResultPageVerificationEnabled(config);
        String supplementMethod = "NONE";
        boolean providerFallbackUsed = false;
        String fallbackDecision = "USE_PLANNED_CANDIDATES";
        BrowserSearchRuntimeResult browserSearchResult = BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("未触发运行期浏览器补源")
                .fallbackSuggested(false)
                .blockedCount(0)
                .build();

        // 职责边界 1：先验证规划期高优先级候选，只有在验证不足时才允许进入补源阶段。
        if (!Boolean.TRUE.equals(config.getVerifyCandidates()) || allCandidates.isEmpty()) {
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES", "当前节点未启用运行期候选验证");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "当前节点未启用运行期候选验证", false, null, progressListener, allCandidates, List.of(), null);
        } else if (!resultPageVerificationEnabled) {
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "当前节点已关闭结果页验证，改为直接使用候选排序与补源策略");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "当前节点已关闭结果页验证，改为直接使用候选排序与补源策略",
                    false, null, progressListener, allCandidates, List.of(), null);
        } else if (isTimedOut(searchStartedAt, searchTimeoutMillis)) {
            circuitBroken = true;
            degradationReason = "SEARCH_TIMEOUT_BEFORE_VERIFY";
            markStepSkipped(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "搜索阶段已达到总超时，跳过候选验证并进入降级链路");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "搜索阶段已达到总超时，跳过候选验证并进入降级链路",
                    true, degradationReason, progressListener, allCandidates, List.of(), null);
        } else {
            markStepRunning(executionPlan, "VERIFY_TOP_CANDIDATES", "正在验证高优先级候选来源");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "正在验证高优先级候选来源", false, null, progressListener, allCandidates, List.of(), null);
            List<SourceCandidate> verifyCandidates = allCandidates.stream()
                    .sorted(Comparator.comparingDouble(SourceCandidate::getTotalScore).reversed())
                    .limit(Math.max(minVerifiedCount, Math.min(targetCount, allCandidates.size())))
                    .toList();
            CandidateVerificationResult verificationResult = candidateVerifier.verify(
                    config.getCompetitorName(),
                    config.getSourceType(),
                    verifyCandidates
            );
            allCandidates = mergeCandidateUpdates(allCandidates, verificationResult.getUpdatedCandidates());
            appendAttemptedTargets(attemptedTargets, verificationResult.getAttemptedTargets());
            verifiedCount = verificationResult.getVerifiedTargets().size();
            markStepSuccess(executionPlan, "VERIFY_TOP_CANDIDATES",
                    "已验证 " + verificationResult.getAttemptedTargets().size() + " 条候选，验证通过 "
                            + verifiedCount + " 条");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "VERIFY_TOP_CANDIDATES",
                    "已验证 " + verificationResult.getAttemptedTargets().size() + " 条候选，验证通过 "
                            + verifiedCount + " 条",
                    false, null, progressListener, allCandidates, List.of(), null);
        }

        // 职责边界 2：补源只负责在“验证不足”或“候选池不足”时扩充候选，补不到也必须保留规划期候选作为兜底。
        if (shouldSupplement(config, verifiedCount, minVerifiedCount, allCandidates.size(), targetCount, resultPageVerificationEnabled)) {
            if (isTimedOut(searchStartedAt, searchTimeoutMillis)) {
                circuitBroken = true;
                degradationReason = "SEARCH_TIMEOUT_BEFORE_SUPPLEMENT";
                supplementMethod = "TIMEOUT_FALLBACK";
                fallbackDecision = "SKIP_SUPPLEMENT_AND_FALLBACK_PLANNED";
                markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        "搜索阶段已达到总超时，跳过运行期补源，直接回退到已有候选");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        "搜索阶段已达到总超时，跳过运行期补源，直接回退到已有候选",
                        true, degradationReason, progressListener, allCandidates, List.of(), null);
            } else {
                String supplementStartMessage = buildSupplementRunningMessage(config);
                markStepRunning(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementStartMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        supplementStartMessage, false, null, progressListener, allCandidates, List.of(), null);
                int supplementTargetPoolSize = resolveSupplementTargetPoolSize(
                        config,
                        resultPageVerificationEnabled,
                        allCandidates.size(),
                        verifiedCount,
                        minVerifiedCount,
                        targetCount
                );
                SupplementExecutionOutcome supplementOutcome = executeSupplementByFallbackOrder(
                        config,
                        allCandidates,
                        supplementTargetPoolSize
                );
                List<SourceCandidate> supplementedCandidates = supplementOutcome.getSupplementedCandidates();
                browserSearchResult = supplementOutcome.getBrowserSearchResult();
                supplementMethod = supplementOutcome.getSupplementMethod();
                providerFallbackUsed = supplementOutcome.isProviderFallbackUsed();
                fallbackDecision = supplementOutcome.getFallbackDecision();
                supplementedCount = supplementedCandidates.size();
                if (!supplementedCandidates.isEmpty()) {
                    allCandidates = sourceCandidateRanker.rankAndDeduplicate(concat(allCandidates, supplementedCandidates));
                    int needed = Math.max(0, minVerifiedCount - verifiedCount);
                    if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled && needed > 0) {
                        if (isTimedOut(searchStartedAt, searchTimeoutMillis)) {
                            circuitBroken = true;
                            degradationReason = "SEARCH_TIMEOUT_AFTER_SUPPLEMENT";
                        } else {
                            CandidateVerificationResult supplementVerification = candidateVerifier.verify(
                                    config.getCompetitorName(),
                                    config.getSourceType(),
                                    supplementedCandidates.stream().limit(needed).toList()
                            );
                            allCandidates = mergeCandidateUpdates(allCandidates, supplementVerification.getUpdatedCandidates());
                            appendAttemptedTargets(attemptedTargets, supplementVerification.getAttemptedTargets());
                            verifiedCount += supplementVerification.getVerifiedTargets().size();
                        }
                    }
                }
                String supplementMessage = supplementedCount == 0
                        ? "运行期补源未返回新增候选，回退到规划期候选"
                        : "运行期补源新增 " + supplementedCount + " 条候选来源，来源="
                        + supplementMethod + "，" + browserSearchResult.getSummary();
                if (supplementedCount == 0
                        && !"BROWSER_DISABLED_KEEP_PLANNED".equals(fallbackDecision)
                        && !"BROWSER_DISABLED_USE_HTTP_FALLBACK".equals(fallbackDecision)) {
                    fallbackDecision = "NO_NEW_CANDIDATES_KEEP_PLANNED";
                }
                if (circuitBroken && "SEARCH_TIMEOUT_AFTER_SUPPLEMENT".equals(degradationReason)) {
                    supplementMessage += "；补源后验证因总超时被跳过";
                    fallbackDecision = "SUPPLEMENTED_BUT_SKIP_VERIFY_DUE_TIMEOUT";
                }
                markStepSuccess(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", supplementMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                        supplementMessage, circuitBroken, degradationReason,
                        progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "BROWSER_SUPPLEMENT_SEARCH", "现有候选已满足最小验证目标，无需补源");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "BROWSER_SUPPLEMENT_SEARCH",
                    "现有候选已满足最小验证目标，无需补源", false, null, progressListener, allCandidates, List.of(), null);
            fallbackDecision = "SKIP_SUPPLEMENT_ENOUGH_VERIFIED";
        }

        // 职责边界 3：目标选择永远发生在验证/补源之后，只从已经收束完的候选集合中挑选最终采集目标。
        markStepRunning(executionPlan, "SELECT_TARGETS", "正在汇总候选并选择最终采集目标");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
                "正在汇总候选并选择最终采集目标", circuitBroken, degradationReason,
                progressListener, allCandidates, List.of(), null);
        SearchSelectionDecision selectionDecision = collectionTargetSelector.selectTargets(
                allCandidates,
                attemptedTargets,
                targetCount
        );
        List<SearchCollectionTarget> selectedTargets = selectionDecision.getSelectedTargets() == null
                ? List.of()
                : selectionDecision.getSelectedTargets();
        allCandidates = selectionDecision.getUpdatedCandidates() == null
                ? allCandidates
                : selectionDecision.getUpdatedCandidates();
        markStepSuccess(executionPlan, "SELECT_TARGETS",
                "已选出 " + selectedTargets.size() + " 条正式采集目标");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
                "已选出 " + selectedTargets.size() + " 条正式采集目标", circuitBroken, degradationReason,
                progressListener, allCandidates, selectedTargets, null);

        SearchExecutionTrace executionTrace = SearchExecutionTrace.builder()
                .traceVersion("v1")
                .searchMode(config.getSearchMode())
                .searchQueries(executionPlan.getSearchQueries() == null ? List.of() : executionPlan.getSearchQueries())
                .fallbackOrder(executionPlan.getFallbackOrder() == null ? List.of() : executionPlan.getFallbackOrder())
                .plannedCandidateCount(config.getSourceCandidates() == null ? 0 : config.getSourceCandidates().size())
                .verifiedCandidateCount(verifiedCount)
                .supplementedCandidateCount(supplementedCount)
                .supplementMethod(supplementMethod)
                .browserSearchEngine(browserSearchResult.getSearchEngine())
                .browserTraceId(browserSearchResult.getBrowserTraceId())
                .browserExecutedQueries(browserSearchResult.getExecutedQueries() == null ? List.of() : browserSearchResult.getExecutedQueries())
                .browserSearchSummary(browserSearchResult.getSummary())
                .providerFallbackUsed(providerFallbackUsed)
                .selectedCandidateCount(selectedTargets.size())
                .searchTimeoutMillis(searchTimeoutMillis)
                .searchElapsedMillis(System.currentTimeMillis() - searchStartedAt)
                .circuitBroken(circuitBroken)
                .degraded(circuitBroken)
                .degradationReason(degradationReason)
                .browserBlockedReason(browserSearchResult.getBlockedReason())
                .browserBlockedCount(browserSearchResult.getBlockedCount())
                .fallbackDecision(fallbackDecision)
                .recoveryCheckpoint(resolveRecoveryCheckpoint(executionPlan))
                .recoveryAdvice(buildRecoveryAdvice(circuitBroken, degradationReason, browserSearchResult, selectedTargets, config))
                .resumedFromCheckpoint(resumedFromCheckpoint)
                .checkpointSource(checkpointSource)
                .runtimePolicy(resolveRuntimePolicy(config))
                .selectedUrls(selectionDecision.getSourceUrls() == null ? List.of() : selectionDecision.getSourceUrls())
                .generatedAt(LocalDateTime.now())
                .build();
        publishProgress(progressListener, executionPlan, progressSnapshots, allCandidates, selectedTargets, executionTrace);

        SearchProgressSnapshot latestProgress = progressSnapshots.isEmpty()
                ? buildProgressSnapshot(executionPlan, "LOAD_CANDIDATES", "搜索计划已初始化", false, null)
                : progressSnapshots.get(progressSnapshots.size() - 1);
        String reasoningSummary = "规划候选 " + executionTrace.getPlannedCandidateCount()
                + " 条，验证通过 " + verifiedCount
                + " 条，运行期补源 " + supplementedCount
                + " 条，最终选中 " + selectedTargets.size() + " 条"
                + "，补源方式=" + supplementMethod;
        if (circuitBroken && StringUtils.hasText(degradationReason)) {
            reasoningSummary += "；已触发搜索阶段降级：" + degradationReason;
        }
        if (StringUtils.hasText(executionTrace.getBrowserBlockedReason())) {
            reasoningSummary += "；搜索页触发阻断信号=" + executionTrace.getBrowserBlockedReason();
        }

        return SearchExecutionResult.builder()
                .executionPlan(executionPlan)
                .progressSnapshot(latestProgress)
                .progressSnapshots(progressSnapshots)
                .sourceCandidates(allCandidates)
                .selectedTargets(selectedTargets)
                .reasoningSummary(reasoningSummary)
                .executionTrace(executionTrace)
                .auditSnapshot(SearchAuditSnapshot.builder()
                        .executionTrace(executionTrace)
                        .executionPlan(executionPlan)
                        .latestProgress(latestProgress)
                        .progressHistory(progressSnapshots)
                        .sourceCandidates(allCandidates)
                        .selectedTargets(selectedTargets)
                        .sourceUrls(executionTrace.getSelectedUrls())
                        .build())
                .build();
    }

    private List<SourceCandidate> resolveCandidatesFromCheckpoint(SearchAuditSnapshot checkpoint) {
        if (checkpoint == null || checkpoint.getSourceCandidates() == null || checkpoint.getSourceCandidates().isEmpty()) {
            return List.of();
        }
        return checkpoint.getSourceCandidates();
    }

    private Map<String, SearchCollectionTarget> resolveAttemptedTargetsFromCheckpoint(SearchAuditSnapshot checkpoint) {
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        if (checkpoint == null || checkpoint.getSelectedTargets() == null || checkpoint.getSelectedTargets().isEmpty()) {
            return attemptedTargets;
        }
        appendAttemptedTargets(attemptedTargets, checkpoint.getSelectedTargets());
        return attemptedTargets;
    }

    /**
     * 规划期通常会把 sourceCandidates 一并写入节点配置，
     * 但为了兼容旧节点和测试场景，这里仍保留“仅根据 competitorUrls 兜底生成候选”的能力。
     */
    private List<SourceCandidate> resolveInitialCandidates(CollectorNodeConfig config) {
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return config.getSourceCandidates();
        }
        if (config.getCompetitorUrls() == null || config.getCompetitorUrls().isEmpty()) {
            return List.of();
        }
        List<SourceCandidate> fallbackCandidates = new ArrayList<>();
        for (String url : config.getCompetitorUrls()) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            fallbackCandidates.add(SourceCandidate.builder()
                    .url(url)
                    .title(config.getCompetitorName() + " - " + safeSourceType(config.getSourceType()) + "入口")
                    .sourceType(safeSourceType(config.getSourceType()))
                    .discoveryMethod("CONFIG")
                    .reason(StringUtils.hasText(config.getDiscoveryNotes())
                            ? config.getDiscoveryNotes()
                            : "节点配置直接提供采集 URL")
                    .domain(extractDomain(url))
                    .relevanceScore(0.82)
                    .freshnessScore(0.55)
                    .qualityScore(0.80)
                    .selectionReason("由节点配置中的 competitorUrls 直接生成")
                    .build());
        }
        return fallbackCandidates;
    }

    private SearchExecutionPlan initializePlan(SearchExecutionPlan plan) {
        List<SearchExecutionStep> steps = plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()
                ? defaultSteps()
                : plan.getSteps().stream()
                .map(step -> step.toBuilder()
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .message(null)
                        .startedAt(null)
                        .completedAt(null)
                        .build())
                .toList();
        return SearchExecutionPlan.builder()
                .stage(plan == null ? "COLLECTOR_SEARCH_AND_COLLECT" : plan.getStage())
                .steps(new ArrayList<>(steps))
                .build();
    }

    /**
     * 搜索执行计划除了步骤本身，还需要把 query、补源顺序和目标数量显式挂出来，
     * 方便任务详情页和审计日志直接解释“系统准备怎么搜、为什么这样搜”。
     */
    private SearchExecutionPlan enrichExecutionPlan(SearchExecutionPlan executionPlan,
                                                    CollectorNodeConfig config,
                                                    int targetCount,
                                                    int minVerifiedCount) {
        SearchExecutionPlan basePlan = executionPlan == null ? initializePlan(null) : executionPlan;
        return basePlan.toBuilder()
                .searchQueries(resolveSearchQueries(config, basePlan))
                .fallbackOrder(resolveSearchFallbackOrder(config))
                .targetCount(targetCount)
                .minVerifiedCount(minVerifiedCount)
                .build();
    }

    private List<SearchExecutionStep> defaultSteps() {
        return List.of(
                SearchExecutionStep.builder()
                        .stepCode("LOAD_CANDIDATES")
                        .goal("读取规划期候选来源")
                        .expectedDurationMs(500L)
                        .dependency("nodeConfig")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("VERIFY_TOP_CANDIDATES")
                        .goal("验证高优先级候选来源是否可用")
                        .expectedDurationMs(5000L)
                        .dependency("browser")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("BROWSER_SUPPLEMENT_SEARCH")
                        .goal("候选不足时执行运行期补源")
                        .expectedDurationMs(8000L)
                        .dependency("searchProvider")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("SELECT_TARGETS")
                        .goal("合并候选并选出最终采集目标")
                        .expectedDurationMs(1000L)
                        .dependency("ranker")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                        .build(),
                SearchExecutionStep.builder()
                        .stepCode("COLLECT_PAGES")
                        .goal("抓取页面正文并持久化证据")
                        .expectedDurationMs(12000L)
                        .dependency("collector")
                        .status(SearchExecutionStep.StepStatus.PENDING)
                .build()
        );
    }

    /**
     * 运行期补源不再硬编码“浏览器优先”，而是严格尊重节点配置中的 fallback 顺序。
     * 这样同类研究任务可以稳定复现相同的补源策略，避免顺序失控。
     */
    private SupplementExecutionOutcome executeSupplementByFallbackOrder(CollectorNodeConfig config,
                                                                       List<SourceCandidate> existingCandidates,
                                                                       int targetPoolSize) {
        BrowserSearchRuntimeResult browserSearchResult = defaultBrowserSupplementResult(config);
        List<SourceCandidate> supplementedCandidates = new ArrayList<>();
        boolean providerFallbackUsed = false;
        String supplementMethod = "NONE";
        String fallbackDecision = "USE_PLANNED_CANDIDATES";
        boolean browserModeEnabled = Boolean.TRUE.equals(config.getBrowserSearchEnabled())
                && !"HTTP_ONLY".equalsIgnoreCase(config.getSearchMode());
        boolean httpModeEnabled = !"BROWSER_ONLY".equalsIgnoreCase(config.getSearchMode())
                && !"HEURISTIC_ONLY".equalsIgnoreCase(config.getSearchMode());
        boolean browserExecuted = false;
        boolean httpExecuted = false;

        for (String stage : resolveSearchFallbackOrder(config)) {
            if (existingCandidates.size() + supplementedCandidates.size() >= targetPoolSize) {
                break;
            }

            if ("BROWSER".equals(stage) && browserModeEnabled && !browserExecuted) {
                browserSearchResult = browserSearchRuntimeService.search(config);
                browserExecuted = true;
                List<SourceCandidate> browserCandidates = removeExistingCandidates(
                        normalizeCandidates(browserSearchResult.getCandidates(), "BROWSER", config),
                        concat(existingCandidates, supplementedCandidates)
                );
                if (!browserCandidates.isEmpty()) {
                    supplementedCandidates.addAll(browserCandidates);
                    supplementMethod = "BROWSER";
                    fallbackDecision = "USE_BROWSER_SUPPLEMENT";
                }
                continue;
            }

            if ("HTTP".equals(stage) && httpModeEnabled && !httpExecuted) {
                List<SourceCandidate> httpCandidates = removeExistingCandidates(
                        normalizeCandidates(
                                searchSourceProvider.search(config.getCompetitorName(), List.of(config.getSourceType())),
                                "HTTP",
                                config
                        ),
                        concat(existingCandidates, supplementedCandidates)
                );
                httpExecuted = true;
                if (!httpCandidates.isEmpty()) {
                    supplementedCandidates.addAll(httpCandidates);
                    providerFallbackUsed = true;
                    supplementMethod = "HTTP_FALLBACK";
                    fallbackDecision = browserModeEnabled ? "USE_HTTP_FALLBACK" : "BROWSER_DISABLED_USE_HTTP_FALLBACK";
                }
            }
        }

        if (supplementedCandidates.isEmpty()) {
            fallbackDecision = resolveEmptySupplementDecision(browserModeEnabled, httpModeEnabled, browserExecuted, httpExecuted);
        }

        return new SupplementExecutionOutcome(browserSearchResult,
                supplementedCandidates,
                supplementMethod,
                fallbackDecision,
                providerFallbackUsed);
    }

    /**
     * 规划期候选先统一标记为 PLANNED，运行期增补候选统一标记为 SUPPLEMENTED，
     * 这样前端和日志层都能直接知道候选最初来自哪个阶段。
     */
    private List<SourceCandidate> normalizeCandidates(List<SourceCandidate> candidates,
                                                      String stage,
                                                      CollectorNodeConfig config) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SourceCandidate> normalized = candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
                .map(candidate -> {
                    SourceCandidate base = sourceCandidateRanker.ensureScores(candidate);
                    String effectiveStage = StringUtils.hasText(base.getSelectionStage()) ? base.getSelectionStage() : stage;
                    String effectiveReason = StringUtils.hasText(base.getSelectionReason())
                            ? base.getSelectionReason()
                            : ("PLANNED".equals(stage) ? "来自规划期补源候选" : "来自运行期补源候选");
                    return base.toBuilder()
                            .selectionStage(effectiveStage)
                            .selectionReason(effectiveReason)
                            .build();
                })
                .filter(candidate -> !isBlockedDomain(candidate, config.getBlockedDomains()))
                .toList();
        return sourceCandidateRanker.rankAndDeduplicate(normalized);
    }

    private List<String> resolveSearchQueries(CollectorNodeConfig config, SearchExecutionPlan executionPlan) {
        if (config.getSearchQueries() != null && !config.getSearchQueries().isEmpty()) {
            return config.getSearchQueries();
        }
        if (executionPlan != null && executionPlan.getSearchQueries() != null) {
            return executionPlan.getSearchQueries();
        }
        return List.of();
    }

    private List<String> resolveSearchFallbackOrder(CollectorNodeConfig config) {
        List<String> configuredOrder = config.getSearchFallbackOrder();
        if (configuredOrder == null || configuredOrder.isEmpty()) {
            return searchPolicyResolver.resolveFallbackOrder(
                    config.getSearchMode(),
                    Boolean.TRUE.equals(config.getBrowserSearchEnabled())
            );
        }
        LinkedHashSet<String> normalizedOrder = new LinkedHashSet<>();
        for (String stage : configuredOrder) {
            if (StringUtils.hasText(stage)) {
                normalizedOrder.add(stage.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (normalizedOrder.isEmpty()) {
            return searchPolicyResolver.resolveFallbackOrder(
                    config.getSearchMode(),
                    Boolean.TRUE.equals(config.getBrowserSearchEnabled())
            );
        }
        return new ArrayList<>(normalizedOrder);
    }

    private BrowserSearchRuntimeResult defaultBrowserSupplementResult(CollectorNodeConfig config) {
        if (!Boolean.TRUE.equals(config.getBrowserSearchEnabled())) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .summary("运行期浏览器补源已关闭，直接尝试 HTTP 回退补源")
                    .fallbackSuggested(true)
                    .blockedCount(0)
                    .build();
        }
        return BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("未触发运行期浏览器补源")
                .fallbackSuggested(false)
                .blockedCount(0)
                .build();
    }

    private String resolveEmptySupplementDecision(boolean browserModeEnabled,
                                                  boolean httpModeEnabled,
                                                  boolean browserExecuted,
                                                  boolean httpExecuted) {
        if (!browserModeEnabled && !httpModeEnabled) {
            return "SEARCH_MODE_KEEP_PLANNED";
        }
        if (!browserModeEnabled) {
            return httpExecuted ? "BROWSER_DISABLED_KEEP_PLANNED" : "SEARCH_MODE_KEEP_PLANNED";
        }
        if (!httpModeEnabled && browserExecuted) {
            return "BROWSER_ONLY_KEEP_PLANNED";
        }
        return "NO_NEW_CANDIDATES_KEEP_PLANNED";
    }

    private int resolveSupplementTargetPoolSize(CollectorNodeConfig config,
                                                boolean resultPageVerificationEnabled,
                                                int currentCandidateCount,
                                                int verifiedCount,
                                                int minVerifiedCount,
                                                int targetCount) {
        if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled) {
            int requiredNewCandidates = Math.max(1, minVerifiedCount - verifiedCount);
            return currentCandidateCount + requiredNewCandidates;
        }
        return targetCount;
    }

    private boolean isBlockedDomain(SourceCandidate candidate, List<String> blockedDomains) {
        if (candidate == null || !StringUtils.hasText(candidate.getDomain())
                || blockedDomains == null || blockedDomains.isEmpty()) {
            return false;
        }
        String normalized = candidate.getDomain().toLowerCase(Locale.ROOT);
        return blockedDomains.stream()
                .filter(StringUtils::hasText)
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
    }

    private String safeSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType.toUpperCase(Locale.ROOT) : "OFFICIAL";
    }

    private String extractDomain(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldSupplement(CollectorNodeConfig config,
                                     int verifiedCount,
                                     int minVerifiedCount,
                                     int candidateCount,
                                     int targetCount,
                                     boolean resultPageVerificationEnabled) {
        boolean runtimeSearchEnabled = !"HEURISTIC_ONLY".equalsIgnoreCase(config.getSearchMode());
        if (!runtimeSearchEnabled) {
            return false;
        }
        // 一旦启用了结果页验证，是否补源必须由“验证是否达标”决定，
        // 不能仅凭规划期候选数量够用就跳过，否则会把“候选数量够但验证全失败”的场景误判为无需补源。
        if (resultPageVerificationEnabled && Boolean.TRUE.equals(config.getVerifyCandidates())) {
            return verifiedCount < minVerifiedCount;
        }
        return candidateCount < targetCount;
    }

    private boolean isResultPageVerificationEnabled(CollectorNodeConfig config) {
        if (config.getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getVerifyResultPage());
        }
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getVerifyResultPage() != null) {
            return Boolean.TRUE.equals(config.getSearchRuntimePolicy().getVerifyResultPage());
        }
        return true;
    }

    private boolean isTimedOut(long startedAt, long timeoutMillis) {
        return timeoutMillis >= 0 && System.currentTimeMillis() - startedAt >= timeoutMillis;
    }

    private List<SourceCandidate> mergeCandidateUpdates(List<SourceCandidate> currentCandidates,
                                                        List<SourceCandidate> updatedCandidates) {
        if (updatedCandidates == null || updatedCandidates.isEmpty()) {
            return currentCandidates;
        }
        Map<String, SourceCandidate> merged = new LinkedHashMap<>();
        for (SourceCandidate candidate : currentCandidates) {
            merged.put(candidate.getUrl(), candidate);
        }
        for (SourceCandidate candidate : updatedCandidates) {
            merged.put(candidate.getUrl(), sourceCandidateRanker.ensureScores(candidate));
        }
        return new ArrayList<>(merged.values());
    }

    private void appendAttemptedTargets(Map<String, SearchCollectionTarget> attemptedTargets,
                                        List<SearchCollectionTarget> newTargets) {
        if (newTargets == null) {
            return;
        }
        for (SearchCollectionTarget target : newTargets) {
            if (target == null || target.getCandidate() == null || !StringUtils.hasText(target.getCandidate().getUrl())) {
                continue;
            }
            attemptedTargets.put(target.getCandidate().getUrl(), target);
        }
    }

    private List<SourceCandidate> removeExistingCandidates(List<SourceCandidate> supplementedCandidates,
                                                           List<SourceCandidate> existingCandidates) {
        Set<String> existingUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : existingCandidates) {
            if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
                existingUrls.add(candidate.getUrl());
            }
        }
        return supplementedCandidates.stream()
                .filter(candidate -> !existingUrls.contains(candidate.getUrl()))
                .toList();
    }

    private List<SourceCandidate> concat(List<SourceCandidate> current, List<SourceCandidate> appended) {
        List<SourceCandidate> merged = new ArrayList<>(current);
        merged.addAll(appended);
        return merged;
    }

    private void appendSnapshotAndPublish(List<SearchProgressSnapshot> progressSnapshots,
                                          SearchExecutionPlan executionPlan,
                                          String currentStepCode,
                                          String message,
                                          boolean degraded,
                                          String degradationReason,
                                          Consumer<SearchExecutionUpdate> progressListener,
                                          List<SourceCandidate> sourceCandidates,
                                          List<SearchCollectionTarget> selectedTargets,
                                          SearchExecutionTrace executionTrace) {
        progressSnapshots.add(buildProgressSnapshot(executionPlan, currentStepCode, message, degraded, degradationReason));
        publishProgress(progressListener, executionPlan, progressSnapshots, sourceCandidates, selectedTargets, executionTrace);
    }

    private void publishProgress(Consumer<SearchExecutionUpdate> progressListener,
                                 SearchExecutionPlan executionPlan,
                                 List<SearchProgressSnapshot> progressSnapshots,
                                 List<SourceCandidate> sourceCandidates,
                                 List<SearchCollectionTarget> selectedTargets,
                                 SearchExecutionTrace executionTrace) {
        if (progressListener == null) {
            return;
        }
        List<SearchProgressSnapshot> snapshotHistory = progressSnapshots == null ? List.of() : new ArrayList<>(progressSnapshots);
        SearchProgressSnapshot latest = snapshotHistory.isEmpty() ? null : snapshotHistory.get(snapshotHistory.size() - 1);
        progressListener.accept(SearchExecutionUpdate.builder()
                .executionPlan(executionPlan)
                .latestProgress(latest)
                .progressSnapshots(snapshotHistory)
                .sourceCandidates(sourceCandidates == null ? List.of() : new ArrayList<>(sourceCandidates))
                .selectedTargets(selectedTargets == null ? List.of() : new ArrayList<>(selectedTargets))
                .executionTrace(executionTrace)
                .build());
    }

    private void markStepRunning(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.RUNNING, message, true);
    }

    private void markStepSuccess(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.SUCCESS, message, false);
    }

    private void markStepSkipped(SearchExecutionPlan executionPlan, String stepCode, String message) {
        updateStep(executionPlan, stepCode, SearchExecutionStep.StepStatus.SKIPPED, message, false);
    }

    private void updateStep(SearchExecutionPlan executionPlan,
                            String stepCode,
                            SearchExecutionStep.StepStatus status,
                            String message,
                            boolean markStarted) {
        if (executionPlan == null || executionPlan.getSteps() == null) {
            return;
        }
        for (int index = 0; index < executionPlan.getSteps().size(); index++) {
            SearchExecutionStep step = executionPlan.getSteps().get(index);
            if (!stepCode.equals(step.getStepCode())) {
                continue;
            }
            executionPlan.getSteps().set(index, step.toBuilder()
                    .status(status)
                    .message(message)
                    .startedAt(markStarted && step.getStartedAt() == null ? LocalDateTime.now() : step.getStartedAt())
                    .completedAt(status == SearchExecutionStep.StepStatus.SUCCESS
                            || status == SearchExecutionStep.StepStatus.FAILED
                            || status == SearchExecutionStep.StepStatus.SKIPPED ? LocalDateTime.now() : null)
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

    private SearchRuntimePolicy resolveRuntimePolicy(CollectorNodeConfig config) {
        SearchRuntimePolicy existing = config.getSearchRuntimePolicy();
        if (existing != null) {
            return existing;
        }
        return SearchRuntimePolicy.builder()
                .recoveryHint("建议从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查")
                .build();
    }

    private String resolveRecoveryCheckpoint(SearchExecutionPlan executionPlan) {
        if (executionPlan == null || executionPlan.getSteps() == null) {
            return "LOAD_CANDIDATES";
        }
        return executionPlan.getSteps().stream()
                .filter(step -> step.getStatus() == SearchExecutionStep.StepStatus.SUCCESS
                        || step.getStatus() == SearchExecutionStep.StepStatus.RUNNING
                        || step.getStatus() == SearchExecutionStep.StepStatus.FAILED
                        || step.getStatus() == SearchExecutionStep.StepStatus.SKIPPED)
                .reduce((first, second) -> second)
                .map(SearchExecutionStep::getStepCode)
                .orElse("LOAD_CANDIDATES");
    }

    private String buildRecoveryAdvice(boolean circuitBroken,
                                       String degradationReason,
                                       BrowserSearchRuntimeResult browserSearchResult,
                                       List<SearchCollectionTarget> selectedTargets,
                                       CollectorNodeConfig config) {
        if (circuitBroken && StringUtils.hasText(degradationReason)) {
            return "搜索阶段发生超时降级，建议放宽 searchTimeoutMillis 或从 "
                    + resolveRecoveryStepForReason(degradationReason)
                    + " 继续执行。";
        }
        if (browserSearchResult != null && StringUtils.hasText(browserSearchResult.getBlockedReason())) {
            return "浏览器搜索疑似触发反爬信号[" + browserSearchResult.getBlockedReason()
                    + "]，建议调整 user-agent、间隔时间或先使用规划期候选继续执行。";
        }
        if (selectedTargets == null || selectedTargets.isEmpty()) {
            return "当前未选出正式采集目标，建议复核 blockedDomains、preferredDomains 和补源 query。";
        }
        SearchRuntimePolicy policy = resolveRuntimePolicy(config);
        if (policy != null && StringUtils.hasText(policy.getRecoveryHint())) {
            return policy.getRecoveryHint();
        }
        return "搜索执行已完成，如需复核可从 SELECT_TARGETS 或 COLLECT_PAGES 回放。";
    }

    private String resolveRecoveryStepForReason(String degradationReason) {
        return switch (degradationReason) {
            case "SEARCH_TIMEOUT_BEFORE_VERIFY" -> "LOAD_CANDIDATES";
            case "SEARCH_TIMEOUT_BEFORE_SUPPLEMENT" -> "VERIFY_TOP_CANDIDATES";
            case "SEARCH_TIMEOUT_AFTER_SUPPLEMENT" -> "BROWSER_SUPPLEMENT_SEARCH";
            default -> "SELECT_TARGETS";
        };
    }

    private String buildSupplementRunningMessage(CollectorNodeConfig config) {
        List<String> queries = config.getSearchQueries() == null
                ? List.of()
                : config.getSearchQueries().stream().filter(StringUtils::hasText).toList();
        if (queries.isEmpty()) {
            return "候选不足，开始执行运行期补源";
        }
        String engine = browserSearchRuntimeService.getSearchEngineName();
        String prefix = StringUtils.hasText(engine) ? "正在使用 " + engine + " 补源" : "正在执行浏览器补源";
        if (queries.size() == 1) {
            return "候选不足，" + prefix + "，Query：" + queries.get(0);
        }
        return "候选不足，" + prefix + "，共 " + queries.size() + " 个 Query，当前首个 Query：" + queries.get(0);
    }

    /**
     * 把补源阶段的中间结果收口成一个小对象，避免 execute 主流程里堆积过多临时变量，
     * 同时方便测试精确覆盖“顺序、来源、回退决策”三个关键信号。
     */
    private static class SupplementExecutionOutcome {

        private final BrowserSearchRuntimeResult browserSearchResult;
        private final List<SourceCandidate> supplementedCandidates;
        private final String supplementMethod;
        private final String fallbackDecision;
        private final boolean providerFallbackUsed;

        private SupplementExecutionOutcome(BrowserSearchRuntimeResult browserSearchResult,
                                           List<SourceCandidate> supplementedCandidates,
                                           String supplementMethod,
                                           String fallbackDecision,
                                           boolean providerFallbackUsed) {
            this.browserSearchResult = browserSearchResult;
            this.supplementedCandidates = supplementedCandidates;
            this.supplementMethod = supplementMethod;
            this.fallbackDecision = fallbackDecision;
            this.providerFallbackUsed = providerFallbackUsed;
        }

        private BrowserSearchRuntimeResult getBrowserSearchResult() {
            return browserSearchResult;
        }

        private List<SourceCandidate> getSupplementedCandidates() {
            return supplementedCandidates;
        }

        private String getSupplementMethod() {
            return supplementMethod;
        }

        private String getFallbackDecision() {
            return fallbackDecision;
        }

        private boolean isProviderFallbackUsed() {
            return providerFallbackUsed;
        }
    }
}
