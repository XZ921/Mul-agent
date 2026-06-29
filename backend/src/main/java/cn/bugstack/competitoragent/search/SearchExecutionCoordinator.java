package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import org.springframework.beans.factory.annotation.Autowired;
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
public class SearchExecutionCoordinator {

    private final CandidateVerifier candidateVerifier;
    private final BrowserSearchRuntimeService browserSearchRuntimeService;
    private final SearchSourceProvider searchSourceProvider;
    private final SourceCandidateRanker sourceCandidateRanker;
    private final CollectionTargetSelector collectionTargetSelector;
    private final SearchPolicyResolver searchPolicyResolver;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SourceFamilyDirectDiscoveryPlanner directDiscoveryPlanner;
    private final SitemapDiscoveryService sitemapDiscoveryService;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;
    private final TavilyBootstrapPlanner tavilyBootstrapPlanner;
    private final PublicEvidenceRecoveryService publicEvidenceRecoveryService;

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                new CanonicalUrlResolver(),
                new SitemapDiscoveryService(new SitemapDiscoveryProperties()),
                new CandidateOwnershipPolicy(),
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    @Autowired
    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                new CandidateOwnershipPolicy(),
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                candidateOwnershipPolicy,
                new TavilyBootstrapPlanner(),
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy,
                                      TavilyBootstrapPlanner tavilyBootstrapPlanner) {
        this(candidateVerifier,
                browserSearchRuntimeService,
                searchSourceProvider,
                sourceCandidateRanker,
                collectionTargetSelector,
                searchPolicyResolver,
                canonicalUrlResolver,
                sitemapDiscoveryService,
                candidateOwnershipPolicy,
                tavilyBootstrapPlanner,
                new PublicEvidenceRecoveryService());
    }

    public SearchExecutionCoordinator(CandidateVerifier candidateVerifier,
                                      BrowserSearchRuntimeService browserSearchRuntimeService,
                                      SearchSourceProvider searchSourceProvider,
                                      SourceCandidateRanker sourceCandidateRanker,
                                      CollectionTargetSelector collectionTargetSelector,
                                      SearchPolicyResolver searchPolicyResolver,
                                      CanonicalUrlResolver canonicalUrlResolver,
                                      SitemapDiscoveryService sitemapDiscoveryService,
                                      CandidateOwnershipPolicy candidateOwnershipPolicy,
                                      TavilyBootstrapPlanner tavilyBootstrapPlanner,
                                      PublicEvidenceRecoveryService publicEvidenceRecoveryService) {
        this.candidateVerifier = candidateVerifier;
        this.browserSearchRuntimeService = browserSearchRuntimeService;
        this.searchSourceProvider = searchSourceProvider;
        this.sourceCandidateRanker = sourceCandidateRanker;
        this.collectionTargetSelector = collectionTargetSelector;
        this.searchPolicyResolver = searchPolicyResolver;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.directDiscoveryPlanner = new SourceFamilyDirectDiscoveryPlanner(this.searchPolicyResolver);
        this.sitemapDiscoveryService = sitemapDiscoveryService == null
                ? new SitemapDiscoveryService(new SitemapDiscoveryProperties())
                : sitemapDiscoveryService;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
        this.tavilyBootstrapPlanner = tavilyBootstrapPlanner == null
                ? new TavilyBootstrapPlanner()
                : tavilyBootstrapPlanner;
        this.publicEvidenceRecoveryService = publicEvidenceRecoveryService == null
                ? new PublicEvidenceRecoveryService()
                : publicEvidenceRecoveryService;
    }

    public SearchExecutionResult execute(CollectorNodeConfig config) {
        return execute(config, null);
    }

    /**
     * 将 repair 生命周期投影为稳定审计字段。
     * 这里保持纯函数，不触发 Tavily/浏览器/验证器，确保 replay 和单元测试可以确定性复用同一套状态词汇。
     */
    static Map<String, Object> buildRepairAuditProjection(EvidenceRepairPlan repairPlan) {
        if (repairPlan == null) {
            return Map.of(
                    "repairState", EvidenceRepairState.REPAIR_NOT_REQUIRED.name(),
                    "repairQueries", List.of(),
                    "candidateUrls", List.of(),
                    "promotedUrls", List.of()
            );
        }
        return Map.of(
                "repairState", repairPlan.getState() == null
                        ? EvidenceRepairState.REPAIR_NOT_REQUIRED.name()
                        : repairPlan.getState().name(),
                "repairReason", repairPlan.getReason() == null ? "" : repairPlan.getReason(),
                "sourceUrl", repairPlan.getSourceUrl() == null ? "" : repairPlan.getSourceUrl(),
                "repairQueries", repairPlan.getRepairQueries() == null ? List.of() : repairPlan.getRepairQueries(),
                "candidateUrls", repairPlan.getCandidateUrls() == null ? List.of() : repairPlan.getCandidateUrls(),
                "promotedUrls", repairPlan.getPromotedUrls() == null ? List.of() : repairPlan.getPromotedUrls()
        );
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
        SearchRuntimePolicy runtimePolicy = resolveRuntimePolicy(config);
        int bootstrapCandidateLimit = searchPolicyResolver.resolveBootstrapCandidateLimit(runtimePolicy, targetCount);
        int supplementCandidateLimit = searchPolicyResolver.resolveSupplementCandidateLimit(runtimePolicy, targetCount);
        int maxCandidatePoolSize = searchPolicyResolver.resolveMaxCandidatePoolSize(runtimePolicy, targetCount);
        int maxCandidatesPerDomain = searchPolicyResolver.resolveMaxCandidatesPerDomain(runtimePolicy);
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

        TavilyBootstrapDecision bootstrapDecision = tavilyBootstrapPlanner.plan(config, allCandidates);
        if (bootstrapDecision.isShouldExecute()) {
            markStepRunning(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapDecision.getReason());
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                    bootstrapDecision.getReason(), false, null, progressListener, allCandidates, List.of(), null);
            try {
                List<SourceCandidate> bootstrapCandidates = normalizeCandidates(
                        searchSourceProvider.search(bootstrapDecision.getRequest()),
                        "BOOTSTRAPPED",
                        config
                );
                bootstrapCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        bootstrapCandidates,
                        bootstrapCandidateLimit,
                        maxCandidatesPerDomain
                );
                allCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        concat(allCandidates, bootstrapCandidates),
                        maxCandidatePoolSize,
                        maxCandidatesPerDomain
                );
                String bootstrapMessage = bootstrapCandidates.isEmpty()
                        ? "Tavily Phase 1 bootstrap 已执行，但未新增候选"
                        : "Tavily Phase 1 bootstrap 新增 " + bootstrapCandidates.size() + " 条候选";
                markStepSuccess(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                        bootstrapMessage, false, null, progressListener, allCandidates, List.of(), null);
            } catch (RuntimeException exception) {
                String failOpenMessage = "Tavily Phase 1 bootstrap 执行失败，按 fail-open 继续验证规划期候选";
                markStepSuccess(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", failOpenMessage);
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                        failOpenMessage, false, null, progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "TAVILY_BOOTSTRAP_ENRICH", bootstrapDecision.getReason());
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "TAVILY_BOOTSTRAP_ENRICH",
                    bootstrapDecision.getReason(), false, null, progressListener, allCandidates, List.of(), null);
        }

        int verifiedCount = 0;
        int supplementedCount = 0;
        boolean publicEvidenceRecoveryTriggered = false;
        String publicEvidenceRecoveryStatus = "RECOVERY_NOT_TRIGGERED";
        List<String> publicEvidenceAttemptedUrls = List.of();
        List<String> publicEvidenceAttemptedEvidencePaths = List.of();
        List<String> publicEvidenceRecoveryQueryIntents = List.of();
        int publicEvidenceRecoveryCandidateCount = 0;
        int publicEvidenceRecoveryVerifiedCount = 0;
        Map<String, Object> evidenceRepairPlanProjection = buildRepairAuditProjection(null);
        VerificationStatsAggregate verificationStats = new VerificationStatsAggregate();
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
                    .limit(resolveVerificationCandidateLimit(config, allCandidates, targetCount, minVerifiedCount))
                    .toList();
            CandidateVerificationResult verificationResult = candidateVerifier.verify(
                    config.getCompetitorName(),
                    config.getSourceType(),
                    verifyCandidates
            );
            allCandidates = mergeCandidateUpdates(allCandidates, verificationResult.getUpdatedCandidates());
            appendAttemptedTargets(attemptedTargets, verificationResult.getAttemptedTargets());
            verificationStats.add(verificationResult);
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
                List<SourceCandidate> supplementedCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                        supplementOutcome.getSupplementedCandidates(),
                        supplementCandidateLimit,
                        maxCandidatesPerDomain
                );
                browserSearchResult = supplementOutcome.getBrowserSearchResult();
                supplementMethod = supplementOutcome.getSupplementMethod();
                providerFallbackUsed = supplementOutcome.isProviderFallbackUsed();
                fallbackDecision = supplementOutcome.getFallbackDecision();
                supplementedCount = supplementedCandidates.size();
                if (!supplementedCandidates.isEmpty()) {
                    allCandidates = sourceCandidateRanker.rankDeduplicateAndLimit(
                            concat(allCandidates, supplementedCandidates),
                            maxCandidatePoolSize,
                            maxCandidatesPerDomain
                    );
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
                            verificationStats.add(supplementVerification);
                            List<SourceCandidate> updatedSupplementCandidates = retainUnverifiedHttpFallbackCandidatesIfNeeded(
                                    config,
                                    supplementOutcome,
                                    supplementVerification
                            );
                            allCandidates = mergeCandidateUpdates(allCandidates, updatedSupplementCandidates);
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
            fallbackDecision = shouldSkipSupplementForDirectDiscovery(config, verifiedCount, minVerifiedCount)
                    ? "SKIP_SUPPLEMENT_DIRECT_DISCOVERY_ENOUGH"
                    : "SKIP_SUPPLEMENT_ENOUGH_VERIFIED";
        }

        List<SourceCandidate> sitemapCandidates = normalizeCandidates(
                discoverCandidatesFromSitemaps(config, allCandidates),
                "SUPPLEMENTED",
                config
        );
        if (!sitemapCandidates.isEmpty()) {
            allCandidates = sourceCandidateRanker.rankAndDeduplicate(concat(allCandidates, sitemapCandidates));
        }

        if (shouldTriggerPublicEvidenceRecovery(config, allCandidates, attemptedTargets)) {
            publicEvidenceRecoveryTriggered = true;
            markStepRunning(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "正在为受限主入口补采公开证据候选");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                    "正在为受限主入口补采公开证据候选", circuitBroken, degradationReason,
                    progressListener, allCandidates, List.of(), null);

            PublicEvidenceRecoveryService.RecoveryResult recoveryResult = publicEvidenceRecoveryService.recover(
                    PublicEvidenceRecoveryService.RecoveryContext.builder()
                            .competitorName(config.getCompetitorName())
                            .sourceType(config.getSourceType())
                            .fieldName(config.getRecoveryFieldName())
                            .evidencePathKey(config.getRecoveryEvidencePathKey())
                            .queryIntents(defaultList(config.getRecoveryQueryIntents()))
                            .seedCandidates(allCandidates)
                            .attemptedTargets(new LinkedHashMap<>(attemptedTargets))
                            .build()
            );
            publicEvidenceAttemptedUrls = recoveryResult.getAttemptedAlternativeUrls() == null
                    ? List.of()
                    : recoveryResult.getAttemptedAlternativeUrls();
            publicEvidenceAttemptedEvidencePaths = recoveryResult.getAttemptedEvidencePaths() == null
                    ? List.of()
                    : recoveryResult.getAttemptedEvidencePaths();
            publicEvidenceRecoveryQueryIntents = recoveryResult.getQueryIntents() == null
                    ? List.of()
                    : recoveryResult.getQueryIntents();

            List<SourceCandidate> recoveryCandidates = normalizeCandidates(
                    removeExistingCandidates(
                            recoveryResult.getCandidates() == null ? List.of() : recoveryResult.getCandidates(),
                            allCandidates
                    ),
                    "SUPPLEMENTED",
                    config
            );
            publicEvidenceRecoveryCandidateCount = recoveryCandidates.size();
            EvidenceRepairPlan recoveryRepairPlan = EvidenceRepairPlan.builder()
                    .state(recoveryCandidates.isEmpty()
                            ? EvidenceRepairState.REPAIR_FAILED
                            : EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                    .reason(recoveryResult.getStatus())
                    .sourceUrl(resolveFirstRecoverySourceUrl(allCandidates))
                    .repairQueries(recoveryCandidates.stream()
                            .map(SourceCandidate::getUrl)
                            .filter(StringUtils::hasText)
                            .toList())
                    .candidateUrls(recoveryCandidates.stream()
                            .map(SourceCandidate::getUrl)
                            .filter(StringUtils::hasText)
                            .toList())
                    .promotedUrls(List.of())
                    .build();
            evidenceRepairPlanProjection = buildRepairAuditProjection(recoveryRepairPlan);
            if (recoveryCandidates.isEmpty()) {
                publicEvidenceRecoveryStatus = "RECOVERY_CANDIDATES_EMPTY";
                markStepSkipped(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "公开证据补采未生成新的同域候选");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "公开证据补采未生成新的同域候选", circuitBroken, degradationReason,
                        progressListener, allCandidates, List.of(), null);
            } else if (Boolean.TRUE.equals(config.getVerifyCandidates()) && resultPageVerificationEnabled) {
                CandidateVerificationResult recoveryVerification = candidateVerifier.verify(
                        config.getCompetitorName(),
                        config.getSourceType(),
                        recoveryCandidates
                );
                allCandidates = mergeCandidateUpdates(allCandidates, recoveryVerification.getUpdatedCandidates());
                appendAttemptedTargets(attemptedTargets, recoveryVerification.getAttemptedTargets());
                verificationStats.add(recoveryVerification);
                publicEvidenceRecoveryVerifiedCount = recoveryVerification.getVerifiedTargets() == null
                        ? 0
                        : recoveryVerification.getVerifiedTargets().size();
                verifiedCount += publicEvidenceRecoveryVerifiedCount;
                publicEvidenceRecoveryStatus = publicEvidenceRecoveryVerifiedCount > 0
                        ? "RECOVERED_PUBLIC_PAGE"
                        : "RECOVERY_CANDIDATES_GENERATED";
                evidenceRepairPlanProjection = buildRepairAuditProjection(
                        publicEvidenceRecoveryService.promoteVerifiedUrls(
                                recoveryRepairPlan,
                                recoveryVerification.getVerifiedTargets() == null
                                        ? List.of()
                                        : recoveryVerification.getVerifiedTargets().stream()
                                        .filter(target -> target != null && target.getCandidate() != null)
                                        .map(target -> target.getCandidate().getUrl())
                                        .filter(StringUtils::hasText)
                                        .toList()));
                markStepSuccess(executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        publicEvidenceRecoveryVerifiedCount > 0
                                ? "公开证据补采新增 " + recoveryCandidates.size() + " 条候选，并验证通过 "
                                + publicEvidenceRecoveryVerifiedCount + " 条"
                                : "公开证据补采新增 " + recoveryCandidates.size() + " 条候选，但暂未验证通过");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        publicEvidenceRecoveryVerifiedCount > 0
                                ? "公开证据补采新增 " + recoveryCandidates.size() + " 条候选，并验证通过 "
                                + publicEvidenceRecoveryVerifiedCount + " 条"
                                : "公开证据补采新增 " + recoveryCandidates.size() + " 条候选，但暂未验证通过",
                        circuitBroken, degradationReason, progressListener, allCandidates, List.of(), null);
            } else {
                allCandidates = sourceCandidateRanker.rankAndDeduplicate(concat(allCandidates, recoveryCandidates));
                publicEvidenceRecoveryStatus = "RECOVERY_CANDIDATES_GENERATED";
                evidenceRepairPlanProjection = buildRepairAuditProjection(recoveryRepairPlan);
                markStepSuccess(executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "公开证据补采新增 " + recoveryCandidates.size() + " 条待后续选源的同域候选");
                appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                        "公开证据补采新增 " + recoveryCandidates.size() + " 条待后续选源的同域候选",
                        circuitBroken, degradationReason, progressListener, allCandidates, List.of(), null);
            }
        } else {
            markStepSkipped(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "当前候选已足够或未发现受限公开补采信号");
            appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
                    "当前候选已足够或未发现受限公开补采信号", circuitBroken, degradationReason,
                    progressListener, allCandidates, List.of(), null);
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
        List<SearchCollectionTarget> attemptedTargetList = new ArrayList<>(attemptedTargets.values());
        List<SourceCandidate> discardedCandidates = selectionDecision.getDiscardedCandidates() == null
                ? List.of()
                : selectionDecision.getDiscardedCandidates();
        markStepSuccess(executionPlan, "SELECT_TARGETS",
                "已选出 " + selectedTargets.size() + " 条正式采集目标");
        appendSnapshotAndPublish(progressSnapshots, executionPlan, "SELECT_TARGETS",
                "已选出 " + selectedTargets.size() + " 条正式采集目标", circuitBroken, degradationReason,
                progressListener, allCandidates, selectedTargets, null);

        TavilyFastLaneAudit tavilyFastLaneAudit = buildTavilyFastLaneAudit(
                allCandidates,
                selectedTargets,
                providerFallbackUsed
        );

        SearchExecutionTrace executionTrace = SearchExecutionTrace.builder()
                .traceVersion("v1")
                .searchMode(config.getSearchMode())
                .searchQueries(executionPlan.getSearchQueries() == null ? List.of() : executionPlan.getSearchQueries())
                .fallbackOrder(executionPlan.getFallbackOrder() == null ? List.of() : executionPlan.getFallbackOrder())
                .plannedCandidateCount(config.getSourceCandidates() == null ? 0 : config.getSourceCandidates().size())
                .attemptedCandidateCount(attemptedTargetList.size())
                .discardedCandidateCount(discardedCandidates.size())
                .verifiedCandidateCount(verifiedCount)
                .supplementedCandidateCount(supplementedCount)
                .candidateVerificationElapsedMillis(verificationStats.getElapsedMillis())
                .candidateVerificationConcurrency(verificationStats.getMaxConcurrency())
                .candidateVerificationInputCount(verificationStats.getInputCount())
                .candidateVerificationUniqueCount(verificationStats.getUniqueCount())
                .candidateVerificationReusedPageCount(verificationStats.getReusedCollectedPageCount())
                .candidateVerificationDirectAttemptCount(verificationStats.getDirectAttemptCount())
                .candidateVerificationDirectUsableCount(verificationStats.getDirectUsableCount())
                .candidateVerificationDirectShortcutCount(verificationStats.getDirectShortcutCount())
                .supplementMethod(supplementMethod)
                .browserSearchEngine(browserSearchResult.getSearchEngine())
                .browserTraceId(browserSearchResult.getBrowserTraceId())
                .browserExecutedQueries(browserSearchResult.getExecutedQueries() == null ? List.of() : browserSearchResult.getExecutedQueries())
                .browserSearchSummary(browserSearchResult.getSummary())
                .browserFailureKind(browserSearchResult.getFailureKind())
                .browserRestartScope(browserSearchResult.getRestartScope())
                .browserFallbackAction(browserSearchResult.getFallbackAction())
                .browserMatchedSignals(browserSearchResult.getMatchedSignals() == null ? List.of() : browserSearchResult.getMatchedSignals())
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
                .publicEvidenceRecoveryTriggered(publicEvidenceRecoveryTriggered)
                .publicEvidenceAttemptedUrls(publicEvidenceAttemptedUrls)
                .publicEvidenceAttemptedEvidencePaths(publicEvidenceAttemptedEvidencePaths)
                .publicEvidenceRecoveryFieldName(config.getRecoveryFieldName())
                .publicEvidenceRecoveryEvidencePathKey(config.getRecoveryEvidencePathKey())
                .publicEvidenceRecoveryQueryIntents(publicEvidenceRecoveryQueryIntents)
                .publicEvidenceRecoveryCandidateCount(publicEvidenceRecoveryCandidateCount)
                .publicEvidenceRecoveryVerifiedCount(publicEvidenceRecoveryVerifiedCount)
                .publicEvidenceRecoveryStatus(publicEvidenceRecoveryStatus)
                .evidenceRepairPlan(evidenceRepairPlanProjection)
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .resumedFromCheckpoint(resumedFromCheckpoint)
                .checkpointSource(checkpointSource)
                .runtimePolicy(resolveRuntimePolicy(config))
                .selectedUrls(selectionDecision.getSourceUrls() == null ? List.of() : selectionDecision.getSourceUrls())
                .generatedAt(LocalDateTime.now())
                .build();
        SearchAuditSummary auditSummary = buildSearchAuditSummary(
                allCandidates,
                attemptedTargetList,
                selectedTargets,
                discardedCandidates,
                executionTrace,
                tavilyFastLaneAudit
        );
        publishProgress(progressListener, executionPlan, progressSnapshots, allCandidates, selectedTargets, executionTrace);
        List<SearchReplayTimelineItem> replayTimeline = buildReplayTimeline(
                progressSnapshots,
                allCandidates,
                attemptedTargetList,
                selectedTargets,
                discardedCandidates,
                executionTrace.getSelectedUrls());

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
                .attemptedTargets(attemptedTargetList)
                .selectedTargets(selectedTargets)
                .discardedCandidates(discardedCandidates)
                .replayTimeline(replayTimeline)
                .reasoningSummary(reasoningSummary)
                .executionTrace(executionTrace)
                .auditSnapshot(SearchAuditSnapshot.builder()
                        .summary(auditSummary)
                        .executionTrace(executionTrace)
                        .executionPlan(executionPlan)
                        .latestProgress(latestProgress)
                        .progressHistory(progressSnapshots)
                        .tavilyFastLaneAudit(tavilyFastLaneAudit)
                        .evidenceRepairPlan(evidenceRepairPlanProjection)
                        .replayTimeline(replayTimeline)
                        .sourceCandidates(allCandidates)
                        .attemptedTargets(attemptedTargetList)
                        .selectedTargets(selectedTargets)
                        .discardedCandidates(discardedCandidates)
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
        if (checkpoint == null) {
            return attemptedTargets;
        }
        if (checkpoint.getAttemptedTargets() != null && !checkpoint.getAttemptedTargets().isEmpty()) {
            appendAttemptedTargets(attemptedTargets, checkpoint.getAttemptedTargets());
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
        List<SourceCandidate> directCandidates = directDiscoveryPlanner.buildInitialCandidates(
                config.getCompetitorName(),
                safeSourceType(config.getSourceType()),
                config.getCompetitorUrls()
        ).stream()
                .filter(candidate -> candidate != null && safeSourceType(config.getSourceType()).equals(candidate.getSourceType()))
                .toList();
        if (!directCandidates.isEmpty()) {
            return directCandidates;
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
                    .sourceUrls(List.of(url))
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
                        .stepCode("TAVILY_BOOTSTRAP_ENRICH")
                        .goal("对弱规划期候选执行 Tavily Phase 1 候选增强")
                        .expectedDurationMs(4000L)
                        .dependency("tavily")
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
                        .stepCode("PUBLIC_EVIDENCE_RECOVERY")
                        .goal("主入口受限时补采同域公开正文候选")
                        .expectedDurationMs(3000L)
                        .dependency("candidateVerifier")
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
                        concat(
                                normalizeCandidates(browserSearchResult.getCandidates(), "BROWSER", config),
                                expandSearchCandidatesThroughDirectDiscovery(
                                        config,
                                        browserSearchResult.getCandidates(),
                                        concat(existingCandidates, supplementedCandidates)
                                )
                        ),
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
                List<SourceCandidate> httpSearchCandidates = searchSourceProvider.search(
                        buildSearchSourceRequest(config, existingCandidates)
                );
                if (httpSearchCandidates == null || httpSearchCandidates.isEmpty()) {
                    httpSearchCandidates = searchSourceProvider.search(
                            config.getCompetitorName(),
                            List.of(config.getSourceType())
                    );
                }
                List<SourceCandidate> httpCandidates = removeExistingCandidates(
                        concat(
                                normalizeCandidates(httpSearchCandidates, "HTTP", config),
                                expandSearchCandidatesThroughDirectDiscovery(
                                        config,
                                        httpSearchCandidates,
                                        concat(existingCandidates, supplementedCandidates)
                                )
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
        String sourceFamilyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(config.getSourceType());
        String sourceFamilyRole = searchPolicyResolver.resolveSourceFamilyRole(sourceFamilyKey).name();
        List<SourceCandidate> normalized = candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
                .map(candidate -> {
                    SourceCandidate base = normalizeCandidateCanonicalUrl(sourceCandidateRanker.ensureScores(candidate));
                    if (base == null) {
                        return null;
                    }
                    String providerKey = resolveProviderKey(base, stage);
                    String effectiveStage = resolveSelectionStage(base, stage);
                    String effectiveReason = StringUtils.hasText(base.getSelectionReason())
                            ? base.getSelectionReason()
                            : ("PLANNED".equals(stage) ? "来自规划期补源候选" : "来自运行期补源候选");
                    return base.toBuilder()
                            .sourceFamilyKey(StringUtils.hasText(base.getSourceFamilyKey())
                                    ? base.getSourceFamilyKey()
                                    : sourceFamilyKey)
                            .sourceFamilyRole(StringUtils.hasText(base.getSourceFamilyRole())
                                    ? base.getSourceFamilyRole()
                                    : sourceFamilyRole)
                            .providerKey(providerKey)
                            .providerRole(searchPolicyResolver.resolveProviderRole(providerKey).name())
                            .sourceUrls((base.getSourceUrls() == null || base.getSourceUrls().isEmpty())
                                    ? List.of(base.getUrl())
                                    : base.getSourceUrls())
                            .selectionStage(effectiveStage)
                            .selectionReason(effectiveReason)
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> !isBlockedDomain(candidate, config.getBlockedDomains()))
                .toList();
        return sourceCandidateRanker.rankAndDeduplicate(normalized);
    }

    /**
     * Tavily/bootstrap 等运行期候选在 provider 层可能先以 PLANNED 形态出厂，
     * 这里必须按真正进入系统的阶段覆写成 BOOTSTRAPPED / SUPPLEMENTED，
     * 否则审计与黄金路径会误把运行期增强候选当成规划期候选。
     */
    private String resolveSelectionStage(SourceCandidate candidate, String stage) {
        String selectionStage = candidate == null ? null : candidate.getSelectionStage();
        if (!StringUtils.hasText(selectionStage)) {
            return stage;
        }
        if (!"PLANNED".equalsIgnoreCase(stage)
                && "PLANNED".equalsIgnoreCase(selectionStage)) {
            return stage;
        }
        return selectionStage;
    }

    /**
     * 运行期补源统一构造 SearchSourceRequest。
     * 这里显式透传 query、域名偏好、黑名单与当前候选池，让 Tavily 这类上下文敏感 provider 能拿到完整输入。
     */
    private SearchSourceRequest buildSearchSourceRequest(CollectorNodeConfig config,
                                                         List<SourceCandidate> allCandidates) {
        return SearchSourceRequest.builder()
                .competitorName(config.getCompetitorName())
                .requestedScopes(List.of(config.getSourceType()))
                .searchQueries(resolveSearchQueries(config, null))
                .preferredDomains(defaultList(config.getPreferredDomains()))
                .includeDomains(defaultList(config.getIncludeDomains()))
                .blockedDomains(defaultList(config.getBlockedDomains()))
                .seedCandidates(allCandidates == null ? List.of() : allCandidates)
                .preferredProviderKey(config.getPreferredSearchProvider())
                .preferredQueryMode(config.getTavilyQueryMode())
                .requestPhase(SearchRequestPhase.SUPPLEMENT)
                .build();
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

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 统一补齐候选 providerKey，保证后续采集路由、审计与回放都能依赖稳定身份。
     */
    private String resolveProviderKey(SourceCandidate candidate, String stage) {
        if (candidate != null && StringUtils.hasText(candidate.getProviderKey())) {
            return candidate.getProviderKey();
        }
        if ("HTTP".equalsIgnoreCase(stage)) {
            return "http";
        }
        if ("BROWSER".equalsIgnoreCase(stage)) {
            return "browser";
        }
        if ("BOOTSTRAPPED".equalsIgnoreCase(stage)) {
            return "tavily";
        }
        return "planned";
    }

    /**
     * 运行期 public search 命中根域或入口页后，再把根域回灌给 direct discovery，
     * 补齐 docs/pricing/help/open 等模板入口，避免“搜到了官网但不会继续扩展”的断点。
     */
    private List<SourceCandidate> expandSearchCandidatesThroughDirectDiscovery(CollectorNodeConfig config,
                                                                               List<SourceCandidate> searchCandidates,
                                                                               List<SourceCandidate> existingCandidates) {
        if (config == null || searchCandidates == null || searchCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
        for (SourceCandidate existingCandidate : existingCandidates == null ? List.<SourceCandidate>of() : existingCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(existingCandidate);
            if (normalizedCandidate != null && StringUtils.hasText(normalizedCandidate.getUrl())) {
                existingUrls.add(normalizedCandidate.getUrl());
            }
        }
        LinkedHashSet<String> rootUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : searchCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null && existingUrls.contains(normalizedCandidate.getUrl())) {
                continue;
            }
            if (!shouldExpandSearchCandidateThroughDirectDiscovery(config, candidate)) {
                continue;
            }
            String rootUrl = toRootUrl(candidate == null ? null : candidate.getUrl());
            if (StringUtils.hasText(rootUrl)) {
                rootUrls.add(rootUrl);
            }
        }
        if (rootUrls.isEmpty()) {
            return List.of();
        }
        return directDiscoveryPlanner.buildInitialCandidates(
                        config.getCompetitorName(),
                        safeSourceType(config.getSourceType()),
                        new ArrayList<>(rootUrls)
                ).stream()
                .filter(candidate -> candidate != null
                        && !"DIRECT_LOCATOR".equalsIgnoreCase(candidate.getDiscoveryMethod()))
                .map(candidate -> {
                    SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
                    if (normalizedCandidate == null) {
                        return null;
                    }
                    return normalizedCandidate.toBuilder()
                            .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                            .reason("search result root expanded through direct discovery templates")
                            .relevanceScore(0.74D)
                            .freshnessScore(0.55D)
                            .qualityScore(0.80D)
                            .sourceUrls(resolveSearchExpansionSourceUrls(normalizedCandidate, searchCandidates))
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 把 direct discovery 扩出来的候选回指到触发它的搜索结果 URL，
     * 这样 sourceUrls 既能说明“搜索从哪来”，也能保留最终入口的可追溯性。
     */
    private List<String> resolveSearchExpansionSourceUrls(SourceCandidate expandedCandidate,
                                                          List<SourceCandidate> searchCandidates) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        String expandedDomain = canonicalUrlResolver.canonicalDomain(
                expandedCandidate == null ? null : expandedCandidate.getUrl()
        );
        for (SourceCandidate searchCandidate : searchCandidates == null ? List.<SourceCandidate>of() : searchCandidates) {
            if (searchCandidate == null || !StringUtils.hasText(searchCandidate.getUrl())) {
                continue;
            }
            String searchDomain = canonicalUrlResolver.canonicalDomain(searchCandidate.getUrl());
            if (isSameSearchExpansionDomain(expandedDomain, searchDomain)) {
                sourceUrls.add(searchCandidate.getUrl());
            }
        }
        if (sourceUrls.isEmpty() && expandedCandidate != null && expandedCandidate.getSourceUrls() != null) {
            sourceUrls.addAll(expandedCandidate.getSourceUrls());
        }
        if (sourceUrls.isEmpty() && expandedCandidate != null && StringUtils.hasText(expandedCandidate.getUrl())) {
            sourceUrls.add(expandedCandidate.getUrl());
        }
        return new ArrayList<>(sourceUrls);
    }

    private boolean isSameSearchExpansionDomain(String expandedDomain, String searchDomain) {
        if (!StringUtils.hasText(expandedDomain) || !StringUtils.hasText(searchDomain)) {
            return false;
        }
        String normalizedExpandedDomain = expandedDomain.toLowerCase(Locale.ROOT);
        String normalizedSearchDomain = searchDomain.toLowerCase(Locale.ROOT);
        return normalizedExpandedDomain.equals(normalizedSearchDomain)
                || normalizedExpandedDomain.endsWith("." + normalizedSearchDomain)
                || normalizedSearchDomain.endsWith("." + normalizedExpandedDomain);
    }

    private List<String> resolveSearchFallbackOrder(CollectorNodeConfig config) {
        return searchPolicyResolver.resolveFallbackOrder(
                config.getSearchMode(),
                Boolean.TRUE.equals(config.getBrowserSearchEnabled()),
                config.getSearchFallbackOrder()
        );
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

    /**
     * direct discovery 会从一个 stable locator 扩出 docs/open/developer/help 等多个高价值入口。
     * 这些入口本身同属一批可信候选，不能只验证排序第一名；否则第一个模板页不可达时会误触 public search，
     * 反而掩盖同批次里真实可用的 /docs 或 open 平台入口。
     */
    private int resolveVerificationCandidateLimit(CollectorNodeConfig config,
                                                  List<SourceCandidate> candidates,
                                                  int targetCount,
                                                  int minVerifiedCount) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int defaultLimit = Math.max(minVerifiedCount, Math.min(targetCount, candidateCount));
        if (!isDirectDiscoveryCandidatePool(config, candidates)) {
            return defaultLimit;
        }
        return Math.min(candidateCount, Math.max(defaultLimit, Math.min(candidateCount, 8)));
    }

    private boolean isDirectDiscoveryCandidatePool(CollectorNodeConfig config, List<SourceCandidate> candidates) {
        if (config == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return false;
        }
        if (config.getCompetitorUrls() == null || config.getCompetitorUrls().isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> {
            String discoveryMethod = candidate == null ? null : candidate.getDiscoveryMethod();
            return "DIRECT_LOCATOR".equalsIgnoreCase(discoveryMethod)
                    || "FAMILY_TEMPLATE".equalsIgnoreCase(discoveryMethod)
                    || "FAMILY_SUBDOMAIN_TEMPLATE".equalsIgnoreCase(discoveryMethod);
        });
    }

    /**
     * public search 命中根域或泛官网页时才需要模板扩展。
     * 如果搜索结果本身已经是 DOCS/PRICING 深链，继续扩根域模板会让 open/developer 等猜测入口抢过真实命中页。
     */
    private boolean shouldExpandSearchCandidateThroughDirectDiscovery(CollectorNodeConfig config,
                                                                      SourceCandidate candidate) {
        if (!isTrustedSearchExpansionRoot(config, candidate)) {
            return false;
        }
        SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
        if (normalizedCandidate == null || !StringUtils.hasText(normalizedCandidate.getUrl())) {
            return false;
        }
        String rootUrl = toRootUrl(normalizedCandidate.getUrl());
        if (!StringUtils.hasText(rootUrl)) {
            return false;
        }
        boolean rootHit = normalizedCandidate.getUrl().equals(rootUrl);
        boolean officialHit = "OFFICIAL".equalsIgnoreCase(normalizedCandidate.getSourceType());
        return rootHit || officialHit;
    }

    /**
     * 当浏览器补源被显式关闭时，HTTP provider 是运行期唯一补源来源。
     * 这类候选即使结果页验证暂时抓不到正文，也应该保留为“可采集兜底”，
     * 否则会出现“HTTP 已返回高价值 URL，但搜索阶段最终空选源”的断链。
     */
    private List<SourceCandidate> retainUnverifiedHttpFallbackCandidatesIfNeeded(CollectorNodeConfig config,
                                                                                 SupplementExecutionOutcome supplementOutcome,
                                                                                 CandidateVerificationResult verificationResult) {
        List<SourceCandidate> updatedCandidates = verificationResult == null || verificationResult.getUpdatedCandidates() == null
                ? List.of()
                : verificationResult.getUpdatedCandidates();
        if (!shouldRetainUnverifiedHttpFallback(config, supplementOutcome, verificationResult)) {
            return updatedCandidates;
        }
        return updatedCandidates.stream()
                .map(candidate -> candidate == null ? null : candidate.toBuilder()
                        .selectionStage("SUPPLEMENTED")
                        .selectionReason("浏览器补源关闭，HTTP fallback 候选验证未通过但保留为采集兜底")
                        .selectionSummary("HTTP fallback 候选将进入正式采集阶段再次抓取")
                        .build())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean shouldRetainUnverifiedHttpFallback(CollectorNodeConfig config,
                                                       SupplementExecutionOutcome supplementOutcome,
                                                       CandidateVerificationResult verificationResult) {
        if (config == null || !Boolean.FALSE.equals(config.getBrowserSearchEnabled())) {
            return false;
        }
        if (supplementOutcome == null || !supplementOutcome.isProviderFallbackUsed()) {
            return false;
        }
        if (verificationResult == null
                || verificationResult.getUpdatedCandidates() == null
                || verificationResult.getUpdatedCandidates().isEmpty()) {
            return false;
        }
        return verificationResult.getVerifiedTargets() == null || verificationResult.getVerifiedTargets().isEmpty();
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
        return canonicalUrlResolver.canonicalDomain(url);
    }

    private String toRootUrl(String url) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(canonicalUrl);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean shouldSupplement(CollectorNodeConfig config,
                                     int verifiedCount,
                                     int minVerifiedCount,
                                     int candidateCount,
                                     int targetCount,
                                     boolean resultPageVerificationEnabled) {
        if (shouldSkipSupplementForDirectDiscovery(config, verifiedCount, minVerifiedCount)) {
            return false;
        }
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

    /**
     * direct discovery 已经把 stable locator 展开成当前 sourceType 的正式候选时，
     * 只要最小验真目标已经满足，就直接结束 search supplement，避免再走 public search 噪音补源。
     */
    private boolean shouldSkipSupplementForDirectDiscovery(CollectorNodeConfig config,
                                                           int verifiedCount,
                                                           int minVerifiedCount) {
        if (config == null) {
            return false;
        }
        if (config.getSourceCandidates() != null && !config.getSourceCandidates().isEmpty()) {
            return false;
        }
        if (verifiedCount < minVerifiedCount) {
            return false;
        }
        if (config.getCompetitorUrls() == null || config.getCompetitorUrls().isEmpty()) {
            return false;
        }
        return directDiscoveryPlanner.buildInitialCandidates(
                config.getCompetitorName(),
                safeSourceType(config.getSourceType()),
                config.getCompetitorUrls()
        ).stream().anyMatch(candidate ->
                candidate != null && safeSourceType(config.getSourceType()).equals(candidate.getSourceType()));
    }

    /**
     * recovery 只在“当前没有验证通过的正式候选”且出现字段级补采语境或受限页信号时触发。
     * 这样可以避免每轮搜索都重复扩 about/help/app，同时又能保证登录壳页不会直接结束在空选源。
     */
    private boolean shouldTriggerPublicEvidenceRecovery(CollectorNodeConfig config,
                                                        List<SourceCandidate> candidates,
                                                        Map<String, SearchCollectionTarget> attemptedTargets) {
        boolean hasVerifiedCandidate = (candidates == null ? List.<SourceCandidate>of() : candidates).stream()
                .anyMatch(candidate -> candidate != null && Boolean.TRUE.equals(candidate.getVerified()));
        if (hasVerifiedCandidate) {
            return false;
        }
        if (StringUtils.hasText(config.getRecoveryFieldName())
                || StringUtils.hasText(config.getRecoveryEvidencePathKey())
                || (config.getRecoveryQueryIntents() != null && !config.getRecoveryQueryIntents().isEmpty())) {
            return true;
        }
        if (attemptedTargets == null || attemptedTargets.isEmpty()) {
            return false;
        }
        return attemptedTargets.values().stream().anyMatch(target ->
                target != null && candidateOwnershipPolicy.isUtilityGatePage(
                        target.getCandidate(),
                        target.getCollectedPage()
                ));
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
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                merged.put(normalizedCandidate.getUrl(), normalizedCandidate);
            }
        }
        for (SourceCandidate candidate : updatedCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                merged.put(normalizedCandidate.getUrl(), sourceCandidateRanker.ensureScores(normalizedCandidate));
            }
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
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(target.getCandidate());
            if (normalizedCandidate == null) {
                continue;
            }
            attemptedTargets.put(normalizedCandidate.getUrl(), target.toBuilder()
                    .candidate(normalizedCandidate)
                    .build());
        }
    }

    private List<SourceCandidate> removeExistingCandidates(List<SourceCandidate> supplementedCandidates,
                                                           List<SourceCandidate> existingCandidates) {
        Set<String> existingUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : existingCandidates) {
            SourceCandidate normalizedCandidate = normalizeCandidateCanonicalUrl(candidate);
            if (normalizedCandidate != null) {
                existingUrls.add(normalizedCandidate.getUrl());
            }
        }
        return supplementedCandidates.stream()
                .map(this::normalizeCandidateCanonicalUrl)
                .filter(candidate -> candidate != null && !existingUrls.contains(candidate.getUrl()))
                .toList();
    }

    /**
     * sitemap/robots 发现依赖当前候选池里已经识别出的根域。
     * 这里统一抽取根域并去重，发现结果仍复用现有排序与去重链路，避免产生旁路候选池。
     */
    private List<SourceCandidate> discoverCandidatesFromSitemaps(CollectorNodeConfig config,
                                                                 List<SourceCandidate> existingCandidates) {
        if (config == null || sitemapDiscoveryService == null || existingCandidates == null || existingCandidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> rootUrls = new LinkedHashSet<>();
        for (SourceCandidate candidate : existingCandidates) {
            if (!isTrustedSearchExpansionRoot(config, candidate)) {
                continue;
            }
            String rootUrl = toRootUrl(candidate == null ? null : candidate.getUrl());
            if (StringUtils.hasText(rootUrl)) {
                rootUrls.add(rootUrl);
            }
        }
        if (rootUrls.isEmpty()) {
            return List.of();
        }
        return removeExistingCandidates(
                sitemapDiscoveryService.discover(
                        config.getCompetitorName(),
                        safeSourceType(config.getSourceType()),
                        new ArrayList<>(rootUrls)
                ),
                existingCandidates
        );
    }

    /**
     * 只有明确属于竞品自身的搜索候选，才允许继续向根域模板扩展或做 sitemap/robots 发现。
     * 否则一旦把爱企查、企查查这类中介站当成 root，就会错误扩出 /docs、/pricing 等伪入口。
     */
    private boolean isTrustedSearchExpansionRoot(CollectorNodeConfig config, SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return candidateOwnershipPolicy.isTrustedSearchRoot(
                config == null ? null : config.getCompetitorName(),
                candidate
        );
    }

    /**
     * Task 5 要求在候选合并、尝试目标复用和补源去重三个阶段共享同一 canonical URL，
     * 这里统一把候选 URL 收敛为稳定形式，避免同一页面的不同协议或追踪参数占满目标池。
     */
    private SourceCandidate normalizeCandidateCanonicalUrl(SourceCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return null;
        }
        String canonicalUrl = canonicalUrlResolver.canonicalize(candidate.getUrl());
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        return candidate.toBuilder()
                .url(canonicalUrl)
                .domain(extractDomain(canonicalUrl))
                .build();
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

    /**
     * 把搜索进度历史投影成稳定 replay 时间线。
     * 候选、尝试、选中、丢弃数量来自最终事实源，保证回放时每个步骤都能解释当前搜索现场规模。
     */
    private List<SearchReplayTimelineItem> buildReplayTimeline(List<SearchProgressSnapshot> progressSnapshots,
                                                               List<SourceCandidate> sourceCandidates,
                                                               List<SearchCollectionTarget> attemptedTargets,
                                                               List<SearchCollectionTarget> selectedTargets,
                                                               List<SourceCandidate> discardedCandidates,
                                                               List<String> sourceUrls) {
        if (progressSnapshots == null || progressSnapshots.isEmpty()) {
            return List.of();
        }
        int candidateCount = sourceCandidates == null ? 0 : sourceCandidates.size();
        int attemptedCount = attemptedTargets == null ? 0 : attemptedTargets.size();
        int selectedCount = selectedTargets == null ? 0 : selectedTargets.size();
        int discardedCount = discardedCandidates == null ? 0 : discardedCandidates.size();
        List<String> stableSourceUrls = sourceUrls == null ? List.of() : sourceUrls;
        return progressSnapshots.stream()
                .filter(snapshot -> snapshot != null && StringUtils.hasText(snapshot.getCurrentStepCode()))
                .map(snapshot -> SearchReplayTimelineItem.builder()
                        .stepCode(snapshot.getCurrentStepCode())
                        .stepName(snapshot.getCurrentStep())
                        .status(snapshot.getStatus())
                        .message(snapshot.getMessage())
                        .completedSteps(snapshot.getCompletedSteps())
                        .totalSteps(snapshot.getTotalSteps())
                        .progressPercent(snapshot.getProgressPercent())
                        .candidateCount(candidateCount)
                        .attemptedCount(attemptedCount)
                        .selectedCount(selectedCount)
                        .discardedCount(discardedCount)
                        .degraded(snapshot.getDegraded())
                        .degradationReason(snapshot.getDegradationReason())
                        .sourceUrls(stableSourceUrls)
                        .updatedAt(snapshot.getUpdatedAt())
                        .build())
                .toList();
    }

    /**
     * Tavily 快速通道的审计聚合放在 coordinator 末端统一完成，
     * 这样可以直接复用排序、补源、筛选之后已经稳定下来的候选集合，避免把 provider 链路改得过深。
     */
    private TavilyFastLaneAudit buildTavilyFastLaneAudit(List<SourceCandidate> sourceCandidates,
                                                         List<SearchCollectionTarget> selectedTargets,
                                                         boolean providerFallbackUsed) {
        if (sourceCandidates == null || sourceCandidates.isEmpty()) {
            return null;
        }
        Map<String, SourceCandidate> uniqueTavilyCandidates = new LinkedHashMap<>();
        for (int index = 0; index < sourceCandidates.size(); index++) {
            SourceCandidate candidate = sourceCandidates.get(index);
            if (!isTavilyCandidate(candidate)) {
                continue;
            }
            uniqueTavilyCandidates.putIfAbsent(resolveTavilyAuditKey(candidate, index), candidate);
        }
        if (uniqueTavilyCandidates.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> queryModes = new LinkedHashSet<>();
        LinkedHashSet<String> queryOrigins = new LinkedHashSet<>();
        LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        LinkedHashSet<String> queryFingerprints = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> rejectionReasons = new LinkedHashMap<>();
        int fastLaneUsableCount = 0;
        int fastLaneRejectedCount = 0;

        for (SourceCandidate candidate : uniqueTavilyCandidates.values()) {
            addDistinctText(queryModes, candidate.getTavilyQueryMode());
            addDistinctText(queryOrigins, resolveTavilyQueryOrigin(candidate));
            addDistinctText(requestIds, candidate.getTavilyRequestId());
            addDistinctText(queryFingerprints, resolveTavilyQueryFingerprint(candidate));
            if (Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                fastLaneUsableCount++;
                continue;
            }
            fastLaneRejectedCount++;
            rejectionReasons.merge(resolveFastLaneRejectReason(candidate), 1, Integer::sum);
        }

        int queriesSent = !requestIds.isEmpty()
                ? requestIds.size()
                : !queryFingerprints.isEmpty() ? queryFingerprints.size() : queryModes.size();
        boolean bootstrapTriggered = queryOrigins.stream().anyMatch("BOOTSTRAP"::equalsIgnoreCase);
        return TavilyFastLaneAudit.builder()
                .queryModes(new ArrayList<>(queryModes))
                .queryOrigins(new ArrayList<>(queryOrigins))
                .queriesSent(queriesSent)
                .totalResults(uniqueTavilyCandidates.size())
                .fastLaneUsableCount(fastLaneUsableCount)
                .fastLaneRejectedCount(fastLaneRejectedCount)
                .rejectionReasons(rejectionReasons.isEmpty() ? Map.of() : rejectionReasons)
                .bootstrapTriggered(bootstrapTriggered)
                .fallbackTriggered(providerFallbackUsed || fastLaneRejectedCount > 0)
                .tavilyRequestIds(new ArrayList<>(requestIds))
                .playwrightInvocationBaselineHint(countSelectedTavilyFastLaneTargets(selectedTargets))
                .build();
    }

    /**
     * SearchAuditSummary 是对外稳定消费面，显式把 Tavily 审计挂进去，
     * 后续 report / replay / 节点洞察都可以只看 summary 而不必重新解析大快照。
     */
    private SearchAuditSummary buildSearchAuditSummary(List<SourceCandidate> allCandidates,
                                                       List<SearchCollectionTarget> attemptedTargets,
                                                       List<SearchCollectionTarget> selectedTargets,
                                                       List<SourceCandidate> discardedCandidates,
                                                       SearchExecutionTrace executionTrace,
                                                       TavilyFastLaneAudit tavilyFastLaneAudit) {
        return SearchAuditSummary.builder()
                .candidateCount(allCandidates == null ? 0 : allCandidates.size())
                .selectedCount(selectedTargets == null ? 0 : selectedTargets.size())
                .discardedCount(discardedCandidates == null ? 0 : discardedCandidates.size())
                .attemptedCount(attemptedTargets == null ? 0 : attemptedTargets.size())
                .degraded(executionTrace == null ? null : executionTrace.getDegraded())
                .degradationReason(executionTrace == null ? null : executionTrace.getDegradationReason())
                .fallbackDecision(executionTrace == null ? null : executionTrace.getFallbackDecision())
                .recoveryCheckpoint(executionTrace == null ? null : executionTrace.getRecoveryCheckpoint())
                .sourceUrls(executionTrace == null || executionTrace.getSelectedUrls() == null
                        ? List.of()
                        : executionTrace.getSelectedUrls())
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .build();
    }

    private boolean isTavilyCandidate(SourceCandidate candidate) {
        return candidate != null && "tavily".equalsIgnoreCase(candidate.getProviderKey());
    }

    /**
     * repair 审计里的 sourceUrl 只表达“从哪个弱入口触发了补采”。
     * 这里从现有候选池中稳定取第一条可追溯 URL，不重新做网络探测或排序推断。
     */
    private String resolveFirstRecoverySourceUrl(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        for (SourceCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (StringUtils.hasText(candidate.getUrl())) {
                return candidate.getUrl();
            }
            if (candidate.getSourceUrls() == null) {
                continue;
            }
            for (String sourceUrl : candidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    return sourceUrl;
                }
            }
        }
        return "";
    }

    private String resolveTavilyAuditKey(SourceCandidate candidate, int index) {
        if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
            return candidate.getUrl().trim();
        }
        if (candidate != null && StringUtils.hasText(candidate.getPrefetchedContentRef())) {
            return candidate.getPrefetchedContentRef().trim();
        }
        if (candidate != null && StringUtils.hasText(candidate.getTavilyRequestId())) {
            return candidate.getTavilyRequestId().trim() + "#" + index;
        }
        return "tavily#" + index;
    }

    private String resolveTavilyQueryFingerprint(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String queryMode = StringUtils.hasText(candidate.getTavilyQueryMode())
                ? candidate.getTavilyQueryMode().trim()
                : "";
        String query = StringUtils.hasText(candidate.getTavilyQuery())
                ? candidate.getTavilyQuery().trim()
                : "";
        if (!StringUtils.hasText(queryMode) && !StringUtils.hasText(query)) {
            return null;
        }
        return queryMode + "::" + query;
    }

    private String resolveFastLaneRejectReason(SourceCandidate candidate) {
        if (candidate == null) {
            return "UNKNOWN";
        }
        if (StringUtils.hasText(candidate.getFastLaneRejectReason())) {
            return candidate.getFastLaneRejectReason().trim();
        }
        if (StringUtils.hasText(candidate.getPageType())) {
            return candidate.getPageType().trim();
        }
        if (StringUtils.hasText(candidate.getQualityTier())) {
            return candidate.getQualityTier().trim();
        }
        return "UNKNOWN";
    }

    /**
     * Tavily 审计只需要区分“这次查询起源于 Phase 1 bootstrap 还是运行期 supplement”，
     * 因此这里把候选阶段归一成 BOOTSTRAP / SUPPLEMENT 两种稳定标签，避免上层继续理解内部 stage 细节。
     */
    private String resolveTavilyQueryOrigin(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if ("TAVILY_PHASE1_BOOTSTRAP".equalsIgnoreCase(candidate.getDiscoveryMethod())) {
            return "BOOTSTRAP";
        }
        if (!StringUtils.hasText(candidate.getSelectionStage())) {
            return null;
        }
        String selectionStage = candidate.getSelectionStage().trim().toUpperCase(Locale.ROOT);
        if ("BOOTSTRAPPED".equals(selectionStage)) {
            return "BOOTSTRAP";
        }
        if ("SUPPLEMENTED".equals(selectionStage)) {
            return "SUPPLEMENT";
        }
        return null;
    }

    private void addDistinctText(Set<String> values, String value) {
        if (values == null || !StringUtils.hasText(value)) {
            return;
        }
        values.add(value.trim());
    }

    private int countSelectedTavilyFastLaneTargets(List<SearchCollectionTarget> selectedTargets) {
        if (selectedTargets == null || selectedTargets.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SearchCollectionTarget selectedTarget : selectedTargets) {
            SourceCandidate candidate = selectedTarget == null ? null : selectedTarget.getCandidate();
            if (isTavilyCandidate(candidate) && Boolean.TRUE.equals(candidate.getFastLaneUsable())) {
                count++;
            }
        }
        return count;
    }

    private SearchRuntimePolicy resolveRuntimePolicy(CollectorNodeConfig config) {
        SearchRuntimePolicy existing = config.getSearchRuntimePolicy();
        if (existing != null) {
            return existing;
        }
        return SearchRuntimePolicy.builder()
                .recoveryHint("建议从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查（展示语义：运行期补源）")
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
     * 候选验证可能发生在规划期候选和补源候选两个阶段。
     * 这里统一做可空累加，避免 trace 只记录最后一次验证而丢失前一次耗时和输入规模。
     */
    private static class VerificationStatsAggregate {

        private long elapsedMillis;
        private int maxConcurrency;
        private int inputCount;
        private int uniqueCount;
        private int reusedCollectedPageCount;
        private int directAttemptCount;
        private int directUsableCount;
        private int directShortcutCount;

        private void add(CandidateVerificationResult result) {
            if (result == null) {
                return;
            }
            elapsedMillis += value(result.getVerificationElapsedMillis());
            maxConcurrency = Math.max(maxConcurrency, Math.max(1, value(result.getVerificationConcurrency())));
            inputCount += value(result.getInputCandidateCount());
            uniqueCount += value(result.getUniqueCandidateCount());
            reusedCollectedPageCount += value(result.getReusedCollectedPageCount());
            directAttemptCount += value(result.getDirectVerificationAttemptCount());
            directUsableCount += value(result.getDirectVerificationUsableCount());
            directShortcutCount += value(result.getDirectVerificationShortcutCount());
        }

        private long getElapsedMillis() {
            return elapsedMillis;
        }

        private int getMaxConcurrency() {
            return maxConcurrency;
        }

        private int getInputCount() {
            return inputCount;
        }

        private int getUniqueCount() {
            return uniqueCount;
        }

        private int getReusedCollectedPageCount() {
            return reusedCollectedPageCount;
        }

        private int getDirectAttemptCount() {
            return directAttemptCount;
        }

        private int getDirectUsableCount() {
            return directUsableCount;
        }

        private int getDirectShortcutCount() {
            return directShortcutCount;
        }

        private int value(Integer value) {
            return value == null ? 0 : value;
        }

        private long value(Long value) {
            return value == null ? 0L : value;
        }
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
