package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 最终采集目标选择器。
 * 负责把验证结果、补源候选和兜底候选统一收束成最终采集目标，
 * 同时避免把未验证中介页或工具页误提升为正式证据。
 */
@Component
public class CollectionTargetSelector {

    private static final String TAVILY_PREFETCH_SELECTED_REASON = "Tavily prefetch 正文可用";

    private final CanonicalUrlResolver canonicalUrlResolver;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;

    public CollectionTargetSelector() {
        this(new CanonicalUrlResolver(), new CandidateOwnershipPolicy());
    }

    public CollectionTargetSelector(CanonicalUrlResolver canonicalUrlResolver) {
        this(canonicalUrlResolver, new CandidateOwnershipPolicy());
    }

    public CollectionTargetSelector(CanonicalUrlResolver canonicalUrlResolver,
                                    CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
    }

    /**
     * 目标选择一次性完成三件事：
     * 1. 按验证状态与入选资格挑出正式采集目标。
     * 2. 把最终结果回填到 candidate 快照中，便于审计和下游复用。
     * 3. 明确记录被丢弃的候选，避免后续把“未入选”误解为“可兜底”。
     */
    public SearchSelectionDecision selectTargets(List<SourceCandidate> candidates,
                                                 Map<String, SearchCollectionTarget> attemptedTargets,
                                                 int targetCount) {
        if (candidates == null || candidates.isEmpty() || targetCount <= 0) {
            return SearchSelectionDecision.builder()
                    .selectedTargets(List.of())
                    .updatedCandidates(candidates == null ? List.of() : candidates)
                    .discardedCandidates(List.of())
                    .sourceUrls(List.of())
                    .build();
        }

        Map<String, SearchCollectionTarget> normalizedAttemptedTargets = normalizeAttemptedTargets(attemptedTargets);
        List<SearchCollectionTarget> selectedTargets = new ArrayList<>();
        Set<String> selectedUrls = new LinkedHashSet<>();
        List<SourceCandidate> rejectedByEligibility = new ArrayList<>();

        List<SourceCandidate> rankedCandidates = candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getUrl()))
                .sorted(Comparator.<SourceCandidate>comparingInt(
                                candidate -> resolveSelectionTier(candidate, normalizedAttemptedTargets))
                        .thenComparing(SourceCandidate::getTotalScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        for (SourceCandidate candidate : rankedCandidates) {
            String normalizedUrl = normalizeUrl(candidate.getUrl());
            SelectionEligibility eligibility = resolveEligibility(candidate, normalizedAttemptedTargets);
            if (!eligibility.selectable()) {
                rejectedByEligibility.add(candidate.toBuilder()
                        .selectionStage("DISCARDED")
                        .selectionReason(eligibility.reason())
                        .selectionSummary(eligibility.summary())
                        .build());
                continue;
            }
            if (!StringUtils.hasText(normalizedUrl) || !selectedUrls.add(normalizedUrl)) {
                continue;
            }
            SearchCollectionTarget target = normalizedAttemptedTargets.getOrDefault(
                    normalizedUrl,
                    SearchCollectionTarget.builder().candidate(candidate).build()
            );
            selectedTargets.add(target);
            if (selectedTargets.size() >= targetCount) {
                break;
            }
        }

        Map<String, SourceCandidate> rejectedByUrl = indexCandidatesByNormalizedUrl(rejectedByEligibility);
        List<SourceCandidate> updatedCandidates = candidates.stream()
                .map(candidate -> mergeSelectionResult(candidate, selectedUrls, normalizedAttemptedTargets, rejectedByUrl))
                .toList();
        List<SourceCandidate> discardedCandidates = resolveDiscardedCandidates(updatedCandidates,
                selectedUrls,
                normalizedAttemptedTargets);
        Map<String, SourceCandidate> updatedCandidatesByUrl = indexCandidatesByNormalizedUrl(updatedCandidates);
        List<SearchCollectionTarget> refreshedTargets = selectedTargets.stream()
                .map(target -> refreshTargetCandidate(target, updatedCandidatesByUrl))
                .toList();

        return SearchSelectionDecision.builder()
                .selectedTargets(refreshedTargets)
                .updatedCandidates(updatedCandidates)
                .discardedCandidates(discardedCandidates)
                .sourceUrls(refreshedTargets.stream()
                        .map(target -> target == null || target.getCandidate() == null ? null : target.getCandidate().getUrl())
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList())
                .build();
    }

    /**
     * 最终选源必须避免把未验证搜索结果、中介页或登录工具页提升为正式证据。
     * 只有验证通过、显式输入且已有可用公开内容、或显式输入且公开壳恢复成功的候选可以入选。
     */
    private SelectionEligibility resolveEligibility(SourceCandidate candidate,
                                                    Map<String, SearchCollectionTarget> attemptedTargets) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return new SelectionEligibility(false, "候选 URL 为空，不能进入正式采集", "候选 URL 为空");
        }

        SearchCollectionTarget attemptedTarget = attemptedTargets.get(normalizeUrl(candidate.getUrl()));
        SourceCollector.CollectedPage attemptedPage = attemptedTarget == null ? null : attemptedTarget.getCollectedPage();
        boolean explicitCandidate = isExplicitCandidate(candidate);
        boolean structuredExecutorCandidate = isStructuredExecutorCandidate(candidate);
        boolean usableCollectedPage = hasUsableCollectedPage(attemptedTarget);
        boolean publicShellSignal = hasPublicShellSignal(candidate, attemptedTarget);

        /**
         * 运行期验证阶段会先把“验证未通过”的显式官网候选打成 DISCARDED，
         * 但在 04 阶段语义里，这类候选只要已经拿到可用公开正文或公开壳恢复结果，
         * 仍然允许作为正式采集的降级入口继续推进。
         * 因此这里不能一看到 DISCARDED 就提前短路，而要先判断是否满足显式候选兜底条件。
         */
        if ("DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())
                && !(explicitCandidate && usableCollectedPage)
                && !(explicitCandidate && structuredExecutorCandidate)
                && !(explicitCandidate && attemptedTarget != null)) {
            return new SelectionEligibility(false,
                    firstNonBlank(candidate.getSelectionReason(), "候选已在验证或排序阶段被丢弃"),
                    "候选已被丢弃");
        }

        if (candidateOwnershipPolicy.isRejectedMediator(candidate, attemptedPage)) {
            return new SelectionEligibility(false,
                    "未验证中介页不能作为正式证据，命中搜索认证、企业信息或百科页",
                    "中介页仅保留为诊断候选");
        }

        if (candidateOwnershipPolicy.isUtilityGatePage(candidate, attemptedPage)) {
            if (explicitCandidate && usableCollectedPage && publicShellSignal) {
                return new SelectionEligibility(true,
                        "显式候选已恢复公开壳信息，作为降级证据进入采集",
                        "显式候选公开壳信息可用，报告层需按低置信证据处理");
            }
            return new SelectionEligibility(false,
                    "登录、验证码或工具页不能直接进入正式采集目标",
                    "工具页仅保留为公开壳恢复输入");
        }

        if (isUsablePrefetchedCandidate(candidate)) {
            /**
             * Tavily Fast Lane 候选在 gate 阶段已经完成原始正文长度与质量可用性判定，
             * 这里直接复用 fastLaneUsable + hasPrefetchedContent + prefetchedContentRef 三元组作为正式入选条件。
             * 这样 selector 的放行条件与下游 CollectionTaskPackageBuilder 触发 TAVILY_PREFETCHED 的条件严格对齐，
             * 保证“这里放进去的候选，下游一定能按 prefetched ref 取回正文”。
             */
            return new SelectionEligibility(true,
                    TAVILY_PREFETCH_SELECTED_REASON,
                    TAVILY_PREFETCH_SELECTED_REASON);
        }

        if (isDiscoveryOnlyCandidate(candidate) && attemptedTarget == null) {
            /*
             * basic/no-raw 阶段只是在告诉下游“这个 URL 值得继续抓”，
             * 不能把它包装成“正文已可直接落库”的已完成证据。
             */
            return new SelectionEligibility(true,
                    "字段发现候选已入选，仍需后续正文采集",
                    "字段发现候选可继续进入正文采集链路");
        }
        if (Boolean.TRUE.equals(candidate.getVerified())) {
            return new SelectionEligibility(true,
                    "运行期验证通过后被选为正式采集目标",
                    "运行期验证通过后被选为正式采集目标");
        }

        if (explicitCandidate && structuredExecutorCandidate) {
            // 结构化 owner 的可用性由 GitHub/RSS executor 判定，不能被网页验证失败提前短路。
            return new SelectionEligibility(true,
                    "显式结构化来源候选跳过网页验证短路，交由专项 executor 采集",
                    "显式结构化来源候选已进入专项 executor 采集");
        }

        if (explicitCandidate && attemptedTarget != null) {
            /**
             * 显式 URL 即使在验证阶段抓取失败，也要继续进入正式 collection 结果，
             * 这样 documents / audit 才能保留“这个 URL 已被尝试且失败”的可追溯事实。
             */
            return new SelectionEligibility(true,
                    "显式候选已在验证阶段尝试抓取，保留为正式采集审计目标",
                    "显式候选已尝试抓取，保留失败审计事实");
        }

        if (explicitCandidate && usableCollectedPage) {
            if (publicShellSignal) {
                return new SelectionEligibility(true,
                        "显式候选已恢复公开壳信息，作为降级证据进入采集",
                        "显式候选公开壳信息可用，报告层需按低置信证据处理");
            }
            return new SelectionEligibility(true,
                    "显式候选已在验证阶段取得可用公开正文，允许正式采集",
                    "显式候选已取得可用公开正文");
        }

        /**
         * 当 coordinator 显式关闭结果页验证、跳过 verifyCandidates，或直接复用 checkpoint 候选时，
         * 这些候选不会进入 attemptedTargets，因此也拿不到 collectedPage。
         * 只要它们没有被明确判定为中介页、工具页或已丢弃候选，就允许作为降级入口继续进入采集，
         * 否则 planned / supplement / checkpoint 三类正常候选会在 SELECT_TARGETS 阶段被全部清空。
         */
        if (!Boolean.TRUE.equals(candidate.getVerified())
                && attemptedTarget == null
                && !"DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())
                && !"SELECTED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return new SelectionEligibility(true,
                    "当前节点未执行结果页验证，允许非拒绝型候选作为降级采集入口",
                    "当前节点未执行结果页验证，按候选排序结果进入降级采集");
        }

        /**
         * checkpoint 恢复出的候选可能已经在上一轮被标记为 SELECTED，
         * 这类候选在当前轮不应该因为没有重新验真就失去入选资格。
         */
        if ("SELECTED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return new SelectionEligibility(true,
                    "候选来自已恢复的选中快照，允许继续作为正式采集目标",
                    "候选来自已恢复的选中快照");
        }

        if ("SUPPLEMENTED".equalsIgnoreCase(candidate.getSelectionStage())
                && StringUtils.hasText(candidate.getSelectionReason())
                && candidate.getSelectionReason().toLowerCase(Locale.ROOT).contains("http fallback")) {
            return new SelectionEligibility(true,
                    "HTTP fallback 候选已被上游保留为降级采集入口",
                    "HTTP fallback 候选作为降级采集入口进入正式采集");
        }
        return new SelectionEligibility(false,
                "未验证候选不能进入正式采集目标",
                "未验证候选仅保留为诊断候选");
    }

    /**
     * 只有规划期直达候选才允许在“未验证但已拿到公开内容”时作为降级方案入选，
     * 避免把运行期搜索补源错误地当成可信正式来源。
     */
    private boolean isExplicitCandidate(SourceCandidate candidate) {
        String method = candidate == null ? null : candidate.getDiscoveryMethod();
        String provider = candidate == null ? null : candidate.getProviderKey();
        return equalsAny(method, "DIRECT_LOCATOR", "FAMILY_TEMPLATE", "FAMILY_SUBDOMAIN_TEMPLATE", "HEURISTIC")
                || equalsAny(provider, "planned");
    }

    private boolean hasUsableCollectedPage(SearchCollectionTarget target) {
        if (target == null || target.getCollectedPage() == null || !target.getCollectedPage().isSuccess()) {
            return false;
        }
        SourceCollector.CollectedPage page = target.getCollectedPage();
        return StringUtils.hasText(page.getContent()) || StringUtils.hasText(page.getSnippet());
    }

    private boolean hasPublicShellSignal(SourceCandidate candidate, SearchCollectionTarget target) {
        List<String> signals = new ArrayList<>();
        if (candidate != null && candidate.getQualitySignals() != null) {
            signals.addAll(candidate.getQualitySignals());
        }
        if (target != null && target.getCandidate() != null && target.getCandidate().getQualitySignals() != null) {
            signals.addAll(target.getCandidate().getQualitySignals());
        }
        String metadata = target == null || target.getCollectedPage() == null
                ? ""
                : firstNonBlank(target.getCollectedPage().getMetadata(), "");
        String joined = String.join(",", signals).toUpperCase(Locale.ROOT) + "," + metadata.toUpperCase(Locale.ROOT);
        return joined.contains("PUBLIC_SHELL_ONLY")
                || joined.contains("LOGIN_GATE_PARTIAL")
                || joined.contains("ANTI_BOT_PARTIAL");
    }

    /**
     * prefetch 候选是否可直接进入正式采集，完全对齐下游 TAVILY_PREFETCHED 的激活条件：
     * 1. gate 判过可用（fastLaneUsable=true）
     * 2. 确实有预取正文
     * 3. 持有可消费的轻量 ref
     */
    private boolean isUsablePrefetchedCandidate(SourceCandidate candidate) {
        return candidate != null
                && Boolean.TRUE.equals(candidate.getFastLaneUsable())
                && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
                && StringUtils.hasText(candidate.getPrefetchedContentRef());
    }

    private boolean isDiscoveryOnlyCandidate(SourceCandidate candidate) {
        return candidate != null
                && Boolean.TRUE.equals(candidate.getCandidateDiscoveryUsable())
                && !Boolean.TRUE.equals(candidate.getFastLaneUsable());
    }

    private SourceCandidate mergeSelectionResult(SourceCandidate candidate,
                                                 Set<String> selectedUrls,
                                                 Map<String, SearchCollectionTarget> attemptedTargets,
                                                 Map<String, SourceCandidate> rejectedByUrl) {
        if (candidate == null) {
            return null;
        }
        String normalizedUrl = normalizeUrl(candidate.getUrl());
        SourceCandidate rejected = rejectedByUrl.get(normalizedUrl);
        if (rejected != null) {
            return rejected;
        }
        return applySelectionResult(candidate, selectedUrls, attemptedTargets);
    }

    /**
     * 只把已经被正式标记为 DISCARDED 的候选写入丢弃事实源，
     * 未被选中但也未明确丢弃的候选仍保留在 sourceCandidates，避免误判。
     */
    private List<SourceCandidate> resolveDiscardedCandidates(List<SourceCandidate> candidates,
                                                             Set<String> selectedUrls,
                                                             Map<String, SearchCollectionTarget> attemptedTargets) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && "DISCARDED".equalsIgnoreCase(candidate.getSelectionStage()))
                .filter(candidate -> !selectedUrls.contains(normalizeUrl(candidate.getUrl())))
                .filter(candidate -> !shouldSuppressDiscardedFact(candidate, attemptedTargets))
                .toList();
    }

    /**
     * 排序优先级需要同时满足两条约束：
     * 1. 可用 Tavily prefetch 真文必须严格优先于 verified 根域壳页，避免 targetCount=1 时被高分壳页挤掉。
     * 2. verified 的真内容候选仍然保持与 prefetch 同档竞争，不能被一刀切降级。
     */
    private int resolveSelectionTier(SourceCandidate candidate,
                                     Map<String, SearchCollectionTarget> attemptedTargets) {
        if (candidate == null) {
            return Integer.MAX_VALUE;
        }
        SearchCollectionTarget attemptedTarget = attemptedTargets.get(normalizeUrl(candidate.getUrl()));
        if (isUsablePrefetchedCandidate(candidate)) {
            return 0;
        }
        if (Boolean.TRUE.equals(candidate.getVerified()) && hasThinAttemptedPage(candidate, attemptedTarget)) {
            return 1;
        }
        if (Boolean.TRUE.equals(candidate.getVerified())) {
            return 0;
        }
        if (isDiscoveryOnlyCandidate(candidate)) {
            return 1;
        }
        if (isExplicitCandidate(candidate) && isStructuredExecutorCandidate(candidate)) {
            return 1;
        }
        if ("DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())) {
            return 3;
        }
        return 2;
    }

    /**
     * verified 候选不能只看“是否验证过”，还要看抓回来的正文是否真的足够承载正式证据。
     * 对 about、产品壳页、站点外壳这类薄内容，只做降档，不直接当作不可选。
     */
    private boolean hasThinAttemptedPage(SourceCandidate candidate, SearchCollectionTarget attemptedTarget) {
        if (attemptedTarget == null || attemptedTarget.getCollectedPage() == null) {
            return false;
        }
        String content = attemptedTarget.getCollectedPage().getContent();
        if (content != null && content.trim().length() >= 800) {
            return false;
        }
        if (hasPublicShellSignal(candidate, attemptedTarget)) {
            return true;
        }
        String normalizedUrl = normalizeUrl(candidate == null ? null : candidate.getUrl());
        return normalizedUrl.endsWith("/about")
                || normalizedUrl.endsWith("/about/")
                || normalizedUrl.endsWith("/overview")
                || normalizedUrl.endsWith("/overview/")
                || normalizedUrl.endsWith("/");
    }

    /**
     * 运行期验证阶段保存的 attemptedTargets 可能带 query 参数或旧快照，
     * 这里先按归一化 URL 建索引，后续才能稳定复用 collectedPage。
     */
    private Map<String, SearchCollectionTarget> normalizeAttemptedTargets(Map<String, SearchCollectionTarget> attemptedTargets) {
        Map<String, SearchCollectionTarget> normalizedTargets = new LinkedHashMap<>();
        if (attemptedTargets == null || attemptedTargets.isEmpty()) {
            return normalizedTargets;
        }
        for (SearchCollectionTarget target : attemptedTargets.values()) {
            if (target == null) {
                continue;
            }
            String normalizedUrl = normalizeUrl(target.getCandidate() == null ? null : target.getCandidate().getUrl());
            if (!StringUtils.hasText(normalizedUrl) && target.getCollectedPage() != null) {
                normalizedUrl = normalizeUrl(target.getCollectedPage().getUrl());
            }
            if (StringUtils.hasText(normalizedUrl)) {
                normalizedTargets.put(normalizedUrl, target);
            }
        }
        return normalizedTargets;
    }

    /**
     * 被正式选中的候选统一回填 SELECTED，并补齐前端和审计直接可读的解释文案。
     */
    private SourceCandidate applySelectionResult(SourceCandidate candidate,
                                                 Set<String> selectedUrls,
                                                 Map<String, SearchCollectionTarget> attemptedTargets) {
        if (candidate == null) {
            return null;
        }
        String normalizedUrl = normalizeUrl(candidate.getUrl());
        if (!selectedUrls.contains(normalizedUrl)) {
            return candidate;
        }
        if (!candidate.getUrl().equals(normalizedUrl)) {
            return candidate;
        }
        SearchCollectionTarget attemptedTarget = attemptedTargets.get(normalizedUrl);
        if (isUsablePrefetchedCandidate(candidate)) {
            return candidate.toBuilder()
                    .selectionStage("SELECTED")
                    .selectionReason(TAVILY_PREFETCH_SELECTED_REASON)
                    .selectionSummary(TAVILY_PREFETCH_SELECTED_REASON)
                    .build();
        }
        if (isDiscoveryOnlyCandidate(candidate)) {
            return candidate.toBuilder()
                    .selectionStage("SELECTED")
                    .selectionReason("字段发现候选已入选，仍需后续正文采集")
                    .selectionSummary("字段发现候选可继续进入正文采集链路")
                    .build();
        }
        if (!Boolean.TRUE.equals(candidate.getVerified()) && isStructuredExecutorCandidate(candidate)) {
            return candidate.toBuilder()
                    .selectionStage("SELECTED")
                    .selectionReason("显式结构化来源候选跳过网页验证短路，交由专项 executor 采集")
                    .selectionSummary("显式结构化来源候选已进入专项 executor 采集")
                    .build();
        }
        if (!Boolean.TRUE.equals(candidate.getVerified())
                && isExplicitCandidate(candidate)
                && attemptedTarget != null
                && !hasUsableCollectedPage(attemptedTarget)) {
            return candidate.toBuilder()
                    .selectionStage("SELECTED")
                    .selectionReason("显式候选已在验证阶段尝试抓取，保留为正式采集审计目标")
                    .selectionSummary("显式候选已尝试抓取，保留失败审计事实")
                    .build();
        }
        boolean publicShellSelected = !Boolean.TRUE.equals(candidate.getVerified())
                && hasPublicShellSignal(candidate, attemptedTarget);
        String selectedReason = Boolean.TRUE.equals(candidate.getVerified())
                ? "运行期验证通过后被选为正式采集目标"
                : publicShellSelected
                ? "显式候选已恢复公开壳信息，作为降级证据进入正式采集"
                : "显式候选已在验证阶段取得可用公开正文，允许正式采集";
        String selectedSummary = Boolean.TRUE.equals(candidate.getVerified())
                ? "运行期验证通过后被选为正式采集目标"
                : publicShellSelected
                ? "显式候选公开壳信息可用，报告层需按低置信证据处理"
                : "显式候选已取得可用公开正文";
        return candidate.toBuilder()
                .selectionStage("SELECTED")
                .selectionReason(selectedReason)
                .selectionSummary(selectedSummary)
                .build();
    }

    /**
     * 这个方法仍保留给既有公开壳诊断逻辑复用。
     * search-first 选源排序已经升级成“薄正文降档”，不再只靠根路径判断壳页。
     */
    private boolean isVerifiedRootShellCandidate(SourceCandidate candidate,
                                                 SearchCollectionTarget attemptedTarget) {
        if (candidate == null || !Boolean.TRUE.equals(candidate.getVerified())) {
            return false;
        }
        if (!hasPublicShellSignal(candidate, attemptedTarget)) {
            return false;
        }
        String normalizedUrl = normalizeUrl(candidate.getUrl());
        if (!StringUtils.hasText(normalizedUrl)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(normalizedUrl);
            String path = uri.getPath();
            return !StringUtils.hasText(path) || "/".equals(path);
        } catch (Exception e) {
            return normalizedUrl.endsWith("/");
        }
    }

    /**
     * 显式直达候选如果在上游已经被判为 DISCARDED、且当前轮既没有 attemptedTarget 也没有被重新选择，
     * 就不再把它重复写进 discardedCandidates。
     * 这样 discardedCandidates 更聚焦“本轮 selector 实际拒绝掉的候选”，避免把原始直达候选和中介页诊断混在一起。
     */
    private boolean shouldSuppressDiscardedFact(SourceCandidate candidate,
                                                Map<String, SearchCollectionTarget> attemptedTargets) {
        if (candidate == null || !isExplicitCandidate(candidate)) {
            return false;
        }
        if (!"候选已在验证或排序阶段被丢弃".equals(candidate.getSelectionReason())) {
            return false;
        }
        return !attemptedTargets.containsKey(normalizeUrl(candidate.getUrl()));
    }

    private Map<String, SourceCandidate> indexCandidatesByNormalizedUrl(List<SourceCandidate> candidates) {
        Map<String, SourceCandidate> candidatesByUrl = new LinkedHashMap<>();
        if (candidates == null) {
            return candidatesByUrl;
        }
        for (SourceCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String normalizedUrl = normalizeUrl(candidate.getUrl());
            if (StringUtils.hasText(normalizedUrl)) {
                candidatesByUrl.put(normalizedUrl, candidate);
            }
        }
        return candidatesByUrl;
    }

    /**
     * selectedTargets 里保留的是选择当时的 candidate 快照，
     * 所以这里要把回填后的 candidate 重新覆盖进去，确保详情页和审计快照看到的是同一份解释。
     */
    private SearchCollectionTarget refreshTargetCandidate(SearchCollectionTarget target,
                                                          Map<String, SourceCandidate> candidatesByUrl) {
        if (target == null || target.getCandidate() == null) {
            return target;
        }
        String normalizedUrl = normalizeUrl(target.getCandidate().getUrl());
        SourceCandidate refreshedCandidate = candidatesByUrl.getOrDefault(normalizedUrl, target.getCandidate());
        return target.toBuilder().candidate(refreshedCandidate).build();
    }

    private boolean equalsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStructuredExecutorCandidate(SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if ("github".equalsIgnoreCase(candidate.getSourceFamilyKey())
                || "GITHUB".equalsIgnoreCase(candidate.getSourceType())) {
            return true;
        }
        return isExplicitFeedUrl(candidate.getSourceFamilyKey(), candidate.getSourceType(), candidate.getUrl());
    }

    private boolean isExplicitFeedUrl(String sourceFamilyKey, String sourceType, String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        boolean newsFamily = "news".equalsIgnoreCase(sourceFamilyKey) || "NEWS".equalsIgnoreCase(sourceType);
        if (!newsFamily) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return false;
            }
            String normalizedPath = path.trim().toLowerCase(Locale.ROOT);
            String[] segments = normalizedPath.split("/");
            for (String segment : segments) {
                if (!StringUtils.hasText(segment)) {
                    continue;
                }
                if ("feed".equals(segment) || "rss".equals(segment) || "atom".equals(segment)) {
                    return true;
                }
            }
            int lastSlashIndex = normalizedPath.lastIndexOf('/');
            String filename = lastSlashIndex >= 0 ? normalizedPath.substring(lastSlashIndex + 1) : normalizedPath;
            return "feed.xml".equals(filename)
                    || "rss.xml".equals(filename)
                    || "atom.xml".equals(filename);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    /**
     * 目标选择与快照复用只关心“同一页面”，因此会移除 query 和 fragment 并统一 host 大小写，
     * 避免同一文档因为追踪参数不同被误判成两个独立来源。
     */
    private String normalizeUrl(String url) {
        return canonicalUrlResolver.canonicalize(url);
    }

    private record SelectionEligibility(boolean selectable, String reason, String summary) {
    }
}
