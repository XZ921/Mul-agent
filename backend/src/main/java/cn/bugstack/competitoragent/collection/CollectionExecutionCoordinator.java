package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.model.dto.CollectionAuditSummary;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采集执行协调器。
 * 职责只保留三件事：把 selected targets 转成任务包、找到合适执行器、收集最小执行结果。
 */
@Component
public class CollectionExecutionCoordinator {

    private final CollectionTaskPackageBuilder packageBuilder;
    private final CollectionExecutorRegistry executorRegistry;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final InternalLinkDiscoveryProperties internalLinkDiscoveryProperties;

    /**
     * 运行时正式 Bean 只应通过这个主构造器注入依赖。
     * 第三个构造器参数仅服务于测试中替换 canonicalUrlResolver，
     * 因此需要显式标记主构造器，避免 Spring 在存在多个构造器时退回查找无参构造。
     */
    @Autowired
    public CollectionExecutionCoordinator(CollectionTaskPackageBuilder packageBuilder,
                                          CollectionExecutorRegistry executorRegistry,
                                          CanonicalUrlResolver canonicalUrlResolver,
                                          InternalLinkDiscoveryProperties internalLinkDiscoveryProperties) {
        this.packageBuilder = packageBuilder;
        this.executorRegistry = executorRegistry;
        this.canonicalUrlResolver = canonicalUrlResolver == null ? new CanonicalUrlResolver() : canonicalUrlResolver;
        this.internalLinkDiscoveryProperties = internalLinkDiscoveryProperties == null
                ? new InternalLinkDiscoveryProperties()
                : internalLinkDiscoveryProperties;
    }

    public CollectionExecutionCoordinator(CollectionTaskPackageBuilder packageBuilder,
                                          CollectionExecutorRegistry executorRegistry) {
        this(packageBuilder, executorRegistry, new CanonicalUrlResolver(), new InternalLinkDiscoveryProperties());
    }

    CollectionExecutionCoordinator(CollectionTaskPackageBuilder packageBuilder,
                                   CollectionExecutorRegistry executorRegistry,
                                   CanonicalUrlResolver canonicalUrlResolver) {
        this(packageBuilder, executorRegistry, canonicalUrlResolver, new InternalLinkDiscoveryProperties());
    }

    public CollectionExecutionReport execute(Long taskId,
                                             String nodeName,
                                             Long planVersionId,
                                             String competitorName,
                                             List<SearchCollectionTarget> targets) {
        return execute(taskId, nodeName, planVersionId, competitorName, targets, null);
    }

    /**
     * collection 执行从这一轮开始返回正式聚合结果，并支持包级 checkpoint 复用。
     */
    public CollectionExecutionReport execute(Long taskId,
                                             String nodeName,
                                             Long planVersionId,
                                             String competitorName,
                                             List<SearchCollectionTarget> targets,
                                             CollectionAuditSnapshot checkpoint) {
        if (targets == null || targets.isEmpty()) {
            return emptyReport();
        }
        List<CollectionExecutionResult> results = new ArrayList<>();
        Map<String, CollectionExecutionResult> checkpointResultMap = indexReusableCheckpointResults(checkpoint);
        Map<String, CollectionExecutionResult> checkpointIdentityMap = indexReusableCheckpointResultsByIdentity(checkpoint);
        Set<String> consumedCheckpointKeys = new HashSet<>();
        ArrayDeque<QueuedCollectionTask> queue = new ArrayDeque<>();
        LinkedHashSet<String> scheduledCanonicalUrls = new LinkedHashSet<>();
        Map<String, Integer> discoveredCountByEntry = new LinkedHashMap<>();
        int nextTargetIndex = 1;
        int totalDiscoveredLinks = 0;

        for (SearchCollectionTarget target : targets) {
            SourceCandidate candidate = target == null ? null : target.getCandidate();
            if (candidate == null) {
                continue;
            }
            String canonicalUrl = canonicalize(resolveCandidateIdentity(candidate));
            if (StringUtils.hasText(canonicalUrl) && !scheduledCanonicalUrls.add(canonicalUrl)) {
                continue;
            }
            queue.addLast(new QueuedCollectionTask(candidate, nextTargetIndex++, 0,
                    StringUtils.hasText(canonicalUrl) ? canonicalUrl : resolveCandidateIdentity(candidate), false));
        }

        while (!queue.isEmpty()) {
            QueuedCollectionTask queuedTask = queue.pollFirst();
            if (queuedTask == null || queuedTask.candidate() == null) {
                continue;
            }
            CollectionTaskPackage taskPackage = packageBuilder.build(
                    taskId,
                    nodeName,
                    planVersionId,
                    competitorName,
                    queuedTask.candidate(),
                    queuedTask.targetIndex(),
                    queuedTask.discoveryDepth()
            );
            CollectionExecutionResult reusedResult = resolveReusableCheckpointResult(
                    taskPackage,
                    checkpointResultMap,
                    checkpointIdentityMap,
                    consumedCheckpointKeys
            );
            CollectionExecutionResult executionResult;
            if (reusedResult != null) {
                executionResult = markReused(reusedResult, taskPackage);
            } else {
                executionResult = executeTaskPackage(taskPackage);
            }
            results.add(executionResult);

            if (!internalLinkDiscoveryProperties.isEnabled()
                    || executionResult == null
                    || executionResult.getDiscoveredCandidates() == null
                    || executionResult.getDiscoveredCandidates().isEmpty()) {
                continue;
            }
            if (queuedTask.discoveryDepth() >= Math.max(0, internalLinkDiscoveryProperties.getMaxDepth())) {
                continue;
            }

            for (SourceCandidate discoveredCandidate : executionResult.getDiscoveredCandidates()) {
                if (discoveredCandidate == null || !StringUtils.hasText(discoveredCandidate.getUrl())) {
                    continue;
                }
                if (totalDiscoveredLinks >= Math.max(0, internalLinkDiscoveryProperties.getMaxLinksPerNode())) {
                    break;
                }
                int entryDiscoveredCount = discoveredCountByEntry.getOrDefault(queuedTask.entryKey(), 0);
                if (entryDiscoveredCount >= Math.max(0, internalLinkDiscoveryProperties.getMaxLinksPerEntry())) {
                    break;
                }

                String canonicalUrl = canonicalize(resolveCandidateIdentity(discoveredCandidate));
                if (StringUtils.hasText(canonicalUrl) && !scheduledCanonicalUrls.add(canonicalUrl)) {
                    continue;
                }

                SourceCandidate normalizedChildCandidate = discoveredCandidate.toBuilder()
                        .sourceFamilyKey(StringUtils.hasText(discoveredCandidate.getSourceFamilyKey())
                                ? discoveredCandidate.getSourceFamilyKey()
                                : queuedTask.candidate().getSourceFamilyKey())
                        .sourceType(StringUtils.hasText(discoveredCandidate.getSourceType())
                                ? discoveredCandidate.getSourceType()
                                : queuedTask.candidate().getSourceType())
                        .build();
                queue.addLast(new QueuedCollectionTask(
                        normalizedChildCandidate,
                        nextTargetIndex++,
                        queuedTask.discoveryDepth() + 1,
                        queuedTask.entryKey(),
                        true
                ));
                discoveredCountByEntry.put(queuedTask.entryKey(), entryDiscoveredCount + 1);
                totalDiscoveredLinks++;
            }
        }
        return buildReport(results);
    }

    /**
     * 某些目标会在搜索验证阶段提前拿到页面快照。
     * 第六轮要求即便这些目标不再进入 executor，也必须继续纳入正式 collectionAudit，
     * 否则 runtime / insight / replay 会出现“节点失败但 collectionAudit 显示 SUCCESS”的事实撕裂。
     */
    public CollectionExecutionReport summarize(List<CollectionExecutionResult> results) {
        return buildReport(results);
    }

    private CollectionExecutionReport emptyReport() {
        CollectionAuditSnapshot auditSnapshot = CollectionAuditSnapshot.builder()
                .summary(CollectionAuditSummary.builder()
                        .totalPackages(0)
                        .successCount(0)
                        .failedCount(0)
                        .reusedCount(0)
                        .status("SUCCESS")
                        .sourceUrls(List.of())
                        .build())
                .status("SUCCESS")
                .results(List.of())
                .replayTimeline(List.of())
                .sourceUrls(List.of())
                .build();
        return CollectionExecutionReport.builder()
                .status("SUCCESS")
                .results(List.of())
                .auditSnapshot(auditSnapshot)
                .sourceUrls(List.of())
                .build();
    }

    private Map<String, CollectionExecutionResult> indexReusableCheckpointResults(CollectionAuditSnapshot checkpoint) {
        Map<String, CollectionExecutionResult> reusableResults = new LinkedHashMap<>();
        if (checkpoint == null || checkpoint.getResults() == null) {
            return reusableResults;
        }
        for (CollectionExecutionResult result : checkpoint.getResults()) {
            if (!isReusableCheckpointResult(result) || result.getTaskPackageKey() == null) {
                continue;
            }
            reusableResults.put(result.getTaskPackageKey(), result.normalize());
        }
        return reusableResults;
    }

    /**
     * live rerun / resume 场景里，selectedTargets 的顺序可能因为重新验证而变化。
     * 如果只按 `nodeName#001` 这类顺序型 packageKey 匹配，就会把旧成功包错复用到新的目标上。
     * 因此这里额外建立“稳定来源锚点 -> checkpoint 结果”的索引，优先用 canonical URL 兜底复用。
     */
    private Map<String, CollectionExecutionResult> indexReusableCheckpointResultsByIdentity(CollectionAuditSnapshot checkpoint) {
        Map<String, CollectionExecutionResult> reusableResults = new LinkedHashMap<>();
        if (checkpoint == null || checkpoint.getResults() == null) {
            return reusableResults;
        }
        for (CollectionExecutionResult result : checkpoint.getResults()) {
            if (!isReusableCheckpointResult(result)) {
                continue;
            }
            String stableIdentity = resolveStableSourceIdentity(result.getResourceLocator(), result.getSourceUrls());
            if (!StringUtils.hasText(stableIdentity)) {
                continue;
            }
            reusableResults.putIfAbsent(stableIdentity, result.normalize());
        }
        return reusableResults;
    }

    private CollectionExecutionResult resolveReusableCheckpointResult(CollectionTaskPackage taskPackage,
                                                                      Map<String, CollectionExecutionResult> checkpointResultMap,
                                                                      Map<String, CollectionExecutionResult> checkpointIdentityMap,
                                                                      Set<String> consumedCheckpointKeys) {
        if (taskPackage == null) {
            return null;
        }
        String currentIdentity = resolveStableSourceIdentity(taskPackage.getResourceLocator(), taskPackage.getSourceUrls());
        CollectionExecutionResult exactMatch = checkpointResultMap.get(taskPackage.getPackageKey());
        if (isExactCheckpointMatch(exactMatch, currentIdentity, consumedCheckpointKeys)) {
            consumedCheckpointKeys.add(exactMatch.getTaskPackageKey());
            return exactMatch;
        }
        if (!StringUtils.hasText(currentIdentity)) {
            return null;
        }
        CollectionExecutionResult identityMatch = checkpointIdentityMap.get(currentIdentity);
        if (identityMatch == null
                || consumedCheckpointKeys.contains(identityMatch.getTaskPackageKey())) {
            return null;
        }
        consumedCheckpointKeys.add(identityMatch.getTaskPackageKey());
        return identityMatch;
    }

    /**
     * 只有“旧 packageKey 与当前目标的稳定来源锚点仍然一致”时，才允许直接按 packageKey 复用。
     * 这样可以避免顺序漂移后把 `#001` 的历史成功结果误套到另一个 URL 上。
     */
    private boolean isExactCheckpointMatch(CollectionExecutionResult exactMatch,
                                           String currentIdentity,
                                           Set<String> consumedCheckpointKeys) {
        if (exactMatch == null || consumedCheckpointKeys.contains(exactMatch.getTaskPackageKey())) {
            return false;
        }
        if (!StringUtils.hasText(currentIdentity)) {
            return true;
        }
        String checkpointIdentity = resolveStableSourceIdentity(exactMatch.getResourceLocator(), exactMatch.getSourceUrls());
        return currentIdentity.equals(checkpointIdentity);
    }

    private String resolveStableSourceIdentity(String resourceLocator, List<String> sourceUrls) {
        String canonicalResourceLocator = canonicalize(resourceLocator);
        if (StringUtils.hasText(canonicalResourceLocator)) {
            return canonicalResourceLocator;
        }
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return null;
        }
        for (String sourceUrl : sourceUrls) {
            String canonicalSourceUrl = canonicalize(sourceUrl);
            if (StringUtils.hasText(canonicalSourceUrl)) {
                return canonicalSourceUrl;
            }
        }
        return null;
    }

    private String canonicalize(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String canonicalUrl = canonicalUrlResolver.canonicalize(rawUrl);
        return StringUtils.hasText(canonicalUrl) ? canonicalUrl : rawUrl;
    }

    private boolean isReusableCheckpointResult(CollectionExecutionResult result) {
        return result != null
                && result.isSuccess()
                && "SUCCESS".equalsIgnoreCase(result.getStatus());
    }

    private CollectionExecutionResult markReused(CollectionExecutionResult result, CollectionTaskPackage taskPackage) {
        return result.toBuilder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .success(true)
                .status("SUCCESS")
                .reusedFromCheckpoint(true)
                .checkpointSource("collectionAuditCheckpoint")
                .build()
                .normalize();
    }

    private CollectionExecutionResult normalize(CollectionExecutionResult result, CollectionTaskPackage taskPackage) {
        if (result == null) {
            return CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType("UNKNOWN")
                    .success(false)
                    .status("FAILED")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .discoveryDepth(taskPackage.getDiscoveryDepth())
                    .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
                    .errorMessage("collection executor returned null result")
                    .checkpointSource(null)
                    .reusedFromCheckpoint(false)
                    .build()
                    .normalize();
        }
        return result.toBuilder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .resourceLocator(StringUtils.hasText(result.getResourceLocator())
                        ? result.getResourceLocator()
                        : taskPackage.getResourceLocator())
                .discoveryDepth(result.getDiscoveryDepth() == null
                        ? (taskPackage.getDiscoveryDepth() == null ? 0 : taskPackage.getDiscoveryDepth())
                        : result.getDiscoveryDepth())
                .sourceUrls(result.getSourceUrls() == null || result.getSourceUrls().isEmpty()
                        ? (taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
                        : result.getSourceUrls())
                .reusedFromCheckpoint(Boolean.TRUE.equals(result.getReusedFromCheckpoint()))
                .build()
                .normalize();
    }

    /**
     * 协调器统一兜住 executor 缺失的失败语义，确保递归追加的 child package 也能进入正式 audit/replay。
     */
    private CollectionExecutionResult executeTaskPackage(CollectionTaskPackage taskPackage) {
        try {
            CollectionExecutor executor = executorRegistry.resolve(taskPackage);
            return normalize(executor.execute(taskPackage), taskPackage);
        } catch (IllegalStateException noExecutorMatched) {
            return buildToolUnavailableResult(taskPackage, noExecutorMatched.getMessage());
        }
    }

    private String resolveCandidateIdentity(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (StringUtils.hasText(candidate.getUrl())) {
            return candidate.getUrl();
        }
        if (candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()) {
            return null;
        }
        return candidate.getSourceUrls().get(0);
    }

    /**
     * 当显式 feed URL 没有可用 RSS executor 时，必须快速失败并留下清晰审计事实。
     * 这里不做网页降级，因为 RSS feed URL 和普通正文页面不是同一种资源语义。
     */
    private CollectionExecutionResult buildToolUnavailableResult(CollectionTaskPackage taskPackage, String reason) {
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .executorType("API_DATA")
                .success(false)
                .status("FAILED")
                .resourceLocator(taskPackage.getResourceLocator())
                .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
                .errorMessage(reason)
                .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                .qualitySignals(List.of("TOOL_UNAVAILABLE_FAST_FAIL"))
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(0L)
                .build()
                .normalize();
    }

    private CollectionExecutionReport buildReport(List<CollectionExecutionResult> results) {
        List<CollectionExecutionResult> stableResults = results == null ? List.of() : results;
        List<CollectionReplayTimelineItem> replayTimeline = new ArrayList<>();
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        int successCount = 0;
        int failedCount = 0;
        for (CollectionExecutionResult result : stableResults) {
            if (result == null) {
                continue;
            }
            if (result.isSuccess()) {
                successCount++;
            } else {
                failedCount++;
            }
            sourceUrls.addAll(result.getSourceUrls() == null ? List.of() : result.getSourceUrls());
            replayTimeline.add(CollectionReplayTimelineItem.builder()
                    .taskPackageKey(result.getTaskPackageKey())
                    .targetIndex(result.getTargetIndex())
                    .status(result.getStatus())
                    .executorType(result.getExecutorType())
                    .resourceLocator(result.getResourceLocator())
                    .failureKind(result.getFailureKind())
                    .errorMessage(result.getErrorMessage())
                    .reusedFromCheckpoint(result.getReusedFromCheckpoint())
                    .checkpointSource(result.getCheckpointSource())
                    .sourceUrls(result.getSourceUrls() == null ? List.of() : result.getSourceUrls())
                    .collectedAt(result.getCollectedAt())
                    .durationMillis(result.getDurationMillis())
                    .build());
        }

        String status = resolveAggregateStatus(stableResults, successCount, failedCount);
        String recoveryCheckpoint = resolveRecoveryCheckpoint(stableResults);
        CollectionAuditSnapshot auditSnapshot = CollectionAuditSnapshot.builder()
                .status(status)
                .results(stableResults)
                .replayTimeline(replayTimeline)
                .recoveryCheckpoint(recoveryCheckpoint)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .build();
        auditSnapshot.setSummary(CollectionAuditSummary.from(auditSnapshot).toBuilder()
                .status(status)
                .recoveryCheckpoint(recoveryCheckpoint)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .build());

        return CollectionExecutionReport.builder()
                .status(status)
                .results(stableResults)
                .auditSnapshot(auditSnapshot)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .build();
    }

    private String resolveAggregateStatus(List<CollectionExecutionResult> results, int successCount, int failedCount) {
        if (results == null || results.isEmpty()) {
            return "SUCCESS";
        }
        if (successCount == results.size()) {
            return "SUCCESS";
        }
        if (failedCount == results.size()) {
            return "FAILED";
        }
        return "PARTIAL_SUCCESS";
    }

    private String resolveRecoveryCheckpoint(List<CollectionExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        for (CollectionExecutionResult result : results) {
            if (result != null && !"SUCCESS".equalsIgnoreCase(result.getStatus())) {
                return result.getTaskPackageKey();
            }
        }
        CollectionExecutionResult lastResult = results.get(results.size() - 1);
        return lastResult == null ? null : lastResult.getTaskPackageKey();
    }

    /**
     * 递归队列需要把“源自哪个入口页”与“当前是否为内部发现页”分开维护。
     * entryKey 用于 per-entry 限量，discovered 用于后续进度文案区分入口页与内部发现页。
     */
    private record QueuedCollectionTask(SourceCandidate candidate,
                                        int targetIndex,
                                        int discoveryDepth,
                                        String entryKey,
                                        boolean discovered) {
    }
}
