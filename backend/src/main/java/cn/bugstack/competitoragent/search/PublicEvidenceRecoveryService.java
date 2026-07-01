package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.collection.quality.EvidenceQualityIssue;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityVerdict;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公开证据补采服务。
 * 当主入口落到登录、验证码、低信号壳页时，这里只做“公开可见替代入口”的候选生成，
 * 不绕过登录、不破解验证码，后续是否可用仍交给现有 CandidateVerifier 统一验证。
 */
@Component
public class PublicEvidenceRecoveryService {

    private final CanonicalUrlResolver canonicalUrlResolver;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;
    private final ObjectMapper objectMapper;

    public PublicEvidenceRecoveryService() {
        this(new CanonicalUrlResolver(), new CandidateOwnershipPolicy(), new ObjectMapper());
    }

    public PublicEvidenceRecoveryService(CanonicalUrlResolver canonicalUrlResolver,
                                         CandidateOwnershipPolicy candidateOwnershipPolicy,
                                         ObjectMapper objectMapper) {
        this.canonicalUrlResolver = canonicalUrlResolver == null
                ? new CanonicalUrlResolver()
                : canonicalUrlResolver;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * ?????????? repair ???? RecoveryContext?
     * ???????????????????????????????????????? repair?
     * ?????????????????????? sourceType ???
     */
    public RecoveryContext toRecoveryContext(String competitorName,
                                             List<String> competitorUrls,
                                             String sourceType,
                                             String fieldName,
                                             String evidencePathKey,
                                             List<String> queryIntents,
                                             EvidenceQualityVerdict verdict) {
        return RecoveryContext.builder()
                .competitorName(competitorName)
                .competitorUrls(copyList(competitorUrls))
                .sourceType(sourceType)
                .fieldName(fieldName)
                .evidencePathKey(evidencePathKey)
                .queryIntents(copyList(queryIntents))
                .maxAlternatives(verdict != null && verdict.isRepairRequired() ? 12 : 0)
                .build();
    }

    /**
     * ?? repair ?????
     * ????? recover() ??????????????? repair ?????????????????? URL ????
     * ????????????? RecoveryResult?? repair ?????????????????? RecoveryPlan?
     */
    public RecoveryPlan planRecovery(RecoveryContext context,
                                     List<SourceCandidate> seedCandidates,
                                     List<SearchCollectionTarget> attemptedTargets) {
        RecoveryContext effectiveContext = (context == null ? RecoveryContext.builder().build() : context).toBuilder()
                .seedCandidates(copyCandidates(seedCandidates))
                .attemptedTargets(indexAttemptedTargets(attemptedTargets))
                .build();
        if (!shouldTriggerPlan(effectiveContext, seedCandidates)) {
            return RecoveryPlan.builder()
                    .triggered(false)
                    .reason(resolveRepairReason(null, seedCandidates))
                    .fieldName(effectiveContext.getFieldName())
                    .evidencePathKey(effectiveContext.getEvidencePathKey())
                    .queryIntents(copyList(effectiveContext.getQueryIntents()))
                    .attemptedAlternativeUrls(List.of())
                    .attemptedEvidencePaths(StringUtils.hasText(effectiveContext.getEvidencePathKey())
                            ? List.of(effectiveContext.getEvidencePathKey())
                            : List.of())
                    .candidates(List.of())
                    .build();
        }

        RecoveryResult result = recover(effectiveContext);
        List<SourceCandidate> candidates = applyRepairSignals(
                result.getCandidates() == null ? List.of() : result.getCandidates(),
                effectiveContext.getMaxAlternatives());
        return RecoveryPlan.builder()
                .triggered(!candidates.isEmpty())
                .reason(resolveRepairReason(result, seedCandidates))
                .fieldName(result.getFieldName())
                .evidencePathKey(result.getEvidencePathKey())
                .queryIntents(copyList(result.getQueryIntents()))
                .attemptedAlternativeUrls(copyList(result.getAttemptedAlternativeUrls()))
                .attemptedEvidencePaths(copyList(result.getAttemptedEvidencePaths()))
                .candidates(candidates)
                .build();
    }

    /**
     * ??????? repair URL ??????????
     * ? helper ????????????? live ?????????????? repair ?????
     */
    public EvidenceRepairPlan promoteVerifiedUrls(EvidenceRepairPlan plan, List<String> verifiedUrls) {
        if (plan == null) {
            return EvidenceRepairPlan.builder()
                    .state(EvidenceRepairState.REPAIR_FAILED)
                    .repairQueries(List.of())
                    .candidateUrls(List.of())
                    .promotedUrls(List.of())
                    .reason("repair plan missing")
                    .build();
        }
        EvidenceRepairPlan verified = plan.verifyCandidates(verifiedUrls);
        if (verified.getState() != EvidenceRepairState.REPAIR_CANDIDATE_VERIFIED) {
            return verified;
        }
        return verified.promoteEvidence(verifiedUrls);
    }

    /**
     * 这里只负责生成“值得再验证一次”的公开候选，不直接宣布成功。
     * 这样能把补采规划与页面真实性判断解耦，避免 recovery 自己维护第二套验证语义。
     */
    public RecoveryResult recover(RecoveryContext context) {
        if (context == null || context.getSeedCandidates() == null || context.getSeedCandidates().isEmpty()) {
            return emptyResult(context, "RECOVERY_SKIPPED_NO_SEED");
        }

        LinkedHashSet<String> attemptedEvidencePaths = new LinkedHashSet<>();
        if (StringUtils.hasText(context.getEvidencePathKey())) {
            attemptedEvidencePaths.add(context.getEvidencePathKey().trim());
        }

        LinkedHashSet<String> attemptedAlternativeUrls = new LinkedHashSet<>();
        LinkedHashMap<String, SourceCandidate> recoveredCandidates = new LinkedHashMap<>();

        for (SourceCandidate seedCandidate : context.getSeedCandidates()) {
            if (seedCandidate == null || !StringUtils.hasText(seedCandidate.getUrl())) {
                continue;
            }
            SearchCollectionTarget attemptedTarget = resolveAttemptedTarget(context.getAttemptedTargets(), seedCandidate.getUrl());
            List<String> sourceUrls = resolveSourceUrls(seedCandidate, attemptedTarget);

            for (String rootUrl : resolveSeedRoots(seedCandidate, attemptedTarget)) {
                for (String path : resolvePublicPaths(context)) {
                    String candidateUrl = appendPath(rootUrl, path);
                    if (!StringUtils.hasText(candidateUrl)) {
                        continue;
                    }
                    attemptedAlternativeUrls.add(candidateUrl);
                    appendRecoveredCandidate(
                            recoveredCandidates,
                            candidateUrl,
                            context,
                            sourceUrls,
                            "same-domain public recovery path"
                    );
                }
            }

            for (String metadataUrl : extractMetadataUrls(attemptedTarget)) {
                attemptedAlternativeUrls.add(metadataUrl);
                appendRecoveredCandidate(
                        recoveredCandidates,
                        metadataUrl,
                        context,
                        sourceUrls,
                        "metadata exposed public link"
                );
            }
        }

        List<SourceCandidate> candidates = new ArrayList<>(recoveredCandidates.values());
        candidates = applyAlternativeLimit(candidates, context == null ? null : context.getMaxAlternatives());
        if (candidates.isEmpty()) {
            return emptyResult(context, "RECOVERY_CANDIDATES_EMPTY").toBuilder()
                    .attemptedAlternativeUrls(new ArrayList<>(attemptedAlternativeUrls))
                    .attemptedEvidencePaths(new ArrayList<>(attemptedEvidencePaths))
                    .build();
        }

        return RecoveryResult.builder()
                .status("RECOVERY_CANDIDATES_GENERATED")
                .fieldName(context.getFieldName())
                .evidencePathKey(context.getEvidencePathKey())
                .queryIntents(copyList(context.getQueryIntents()))
                .attemptedAlternativeUrls(new ArrayList<>(attemptedAlternativeUrls))
                .attemptedEvidencePaths(new ArrayList<>(attemptedEvidencePaths))
                .candidates(candidates)
                .build();
    }

    private boolean shouldTriggerPlan(RecoveryContext context, List<SourceCandidate> seedCandidates) {
        if (context == null) {
            return false;
        }
        if (context.getMaxAlternatives() != null && context.getMaxAlternatives() > 0) {
            return true;
        }
        if (seedCandidates == null || seedCandidates.isEmpty()) {
            return false;
        }
        return seedCandidates.stream()
                .filter(candidate -> candidate != null && candidate.getQualitySignals() != null)
                .flatMap(candidate -> candidate.getQualitySignals().stream())
                .filter(StringUtils::hasText)
                .map(signal -> signal.trim().toUpperCase(Locale.ROOT))
                .anyMatch(signal -> signal.contains("AUTH")
                        || signal.contains("CAPTCHA")
                        || signal.contains("NAVIGATION_SHELL")
                        || signal.contains("REPAIR"));
    }

    private List<SourceCandidate> applyRepairSignals(List<SourceCandidate> candidates, Integer maxAlternatives) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return applyAlternativeLimit(candidates, maxAlternatives).stream()
                .map(candidate -> candidate.toBuilder()
                        .qualitySignals(mergeQualitySignals(candidate.getQualitySignals(), "FIELD_EVIDENCE_PATH_RECOVERY"))
                        .build())
                .toList();
    }

    private List<SourceCandidate> applyAlternativeLimit(List<SourceCandidate> candidates, Integer maxAlternatives) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (maxAlternatives == null || maxAlternatives <= 0 || candidates.size() <= maxAlternatives) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, maxAlternatives));
    }

    private List<String> mergeQualitySignals(List<String> existingSignals, String signal) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existingSignals != null) {
            merged.addAll(existingSignals.stream().filter(StringUtils::hasText).map(String::trim).toList());
        }
        if (StringUtils.hasText(signal)) {
            merged.add(signal.trim());
        }
        return new ArrayList<>(merged);
    }

    private List<SourceCandidate> copyCandidates(List<SourceCandidate> candidates) {
        return candidates == null ? List.of() : new ArrayList<>(candidates);
    }

    private Map<String, SearchCollectionTarget> indexAttemptedTargets(List<SearchCollectionTarget> attemptedTargets) {
        if (attemptedTargets == null || attemptedTargets.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, SearchCollectionTarget> indexedTargets = new LinkedHashMap<>();
        for (SearchCollectionTarget attemptedTarget : attemptedTargets) {
            String key = attemptedTarget == null || attemptedTarget.getCandidate() == null
                    ? null
                    : attemptedTarget.getCandidate().getUrl();
            if (!StringUtils.hasText(key) && attemptedTarget != null && attemptedTarget.getCollectedPage() != null) {
                key = attemptedTarget.getCollectedPage().getUrl();
            }
            if (StringUtils.hasText(key)) {
                indexedTargets.putIfAbsent(key, attemptedTarget);
            }
        }
        return indexedTargets;
    }

    private String resolveRepairReason(RecoveryResult result, List<SourceCandidate> seedCandidates) {
        if (result != null && "RECOVERY_SKIPPED_NO_SEED".equals(result.getStatus())) {
            return "RECOVERY_SKIPPED_NO_SEED";
        }
        if (seedCandidates == null || seedCandidates.isEmpty()) {
            return "REPAIR_CONTEXT_UNSPECIFIED";
        }
        for (SourceCandidate candidate : seedCandidates) {
            if (candidate == null || candidate.getQualitySignals() == null) {
                continue;
            }
            List<String> normalizedSignals = candidate.getQualitySignals().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toList());
            if (normalizedSignals.contains(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE.name())) {
                return EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE.name();
            }
            if (normalizedSignals.contains(EvidenceQualityIssue.NAVIGATION_SHELL.name())) {
                return EvidenceQualityIssue.NAVIGATION_SHELL.name();
            }
            if (!normalizedSignals.isEmpty()) {
                return normalizedSignals.get(0);
            }
        }
        return "REPAIR_CONTEXT_UNSPECIFIED";
    }

    /**
     * 字段证据路径优先决定“该补哪类公开页面”，避免继续退化成只按 sourceType 猜测。
     */
    private List<String> resolvePublicPaths(RecoveryContext context) {
        if (looksLikePricingContext(context)) {
            return List.of("/pricing", "/plans", "/billing");
        }
        if (looksLikeOfficialProfileContext(context)) {
            return List.of("/about", "/help", "/download", "/app");
        }
        if (looksLikeDocsContext(context)) {
            return List.of("/docs", "/help", "/guide", "/api");
        }
        return List.of("/about", "/help", "/download", "/app");
    }

    private boolean looksLikePricingContext(RecoveryContext context) {
        if (context == null) {
            return false;
        }
        return containsHint(context.getSourceType(), "pricing", "billing")
                || containsHint(context.getFieldName(), "pricing")
                || containsHint(context.getEvidencePathKey(), "pricing", "billing")
                || containsAnyHint(context.getQueryIntents(), "pricing", "billing");
    }

    private boolean looksLikeOfficialProfileContext(RecoveryContext context) {
        if (context == null) {
            return false;
        }
        return containsHint(context.getEvidencePathKey(), "public_profile", "official_public_profile")
                || ("OFFICIAL".equalsIgnoreCase(context.getSourceType())
                && containsHint(context.getFieldName(), "summary", "overview", "positioning"));
    }

    private boolean looksLikeDocsContext(RecoveryContext context) {
        if (context == null) {
            return false;
        }
        return containsHint(context.getSourceType(), "docs", "api")
                || containsHint(context.getFieldName(), "corefeature", "api", "documentation")
                || containsHint(context.getEvidencePathKey(), "docs", "guide", "api")
                || containsAnyHint(context.getQueryIntents(), "docs", "api", "sdk");
    }

    private boolean containsAnyHint(List<String> values, String... hints) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (containsHint(value, hints)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsHint(String value, String... hints) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private SearchCollectionTarget resolveAttemptedTarget(Map<String, SearchCollectionTarget> attemptedTargets,
                                                          String url) {
        if (attemptedTargets == null || attemptedTargets.isEmpty() || !StringUtils.hasText(url)) {
            return null;
        }
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        for (Map.Entry<String, SearchCollectionTarget> entry : attemptedTargets.entrySet()) {
            if (entry == null || entry.getValue() == null) {
                continue;
            }
            String key = canonicalUrlResolver.canonicalize(entry.getKey());
            if (canonicalUrl.equals(key)) {
                return entry.getValue();
            }
            SourceCandidate candidate = entry.getValue().getCandidate();
            String candidateUrl = candidate == null ? null : canonicalUrlResolver.canonicalize(candidate.getUrl());
            if (canonicalUrl.equals(candidateUrl)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<String> resolveSeedRoots(SourceCandidate seedCandidate,
                                          SearchCollectionTarget attemptedTarget) {
        LinkedHashSet<String> rootUrls = new LinkedHashSet<>();
        addRootUrl(rootUrls, seedCandidate == null ? null : seedCandidate.getUrl());
        if (seedCandidate != null && seedCandidate.getSourceUrls() != null) {
            for (String sourceUrl : seedCandidate.getSourceUrls()) {
                addRootUrl(rootUrls, sourceUrl);
            }
        }
        SourceCollector.CollectedPage collectedPage = attemptedTarget == null ? null : attemptedTarget.getCollectedPage();
        addRootUrl(rootUrls, collectedPage == null ? null : collectedPage.getUrl());
        return new ArrayList<>(rootUrls);
    }

    private void addRootUrl(Set<String> rootUrls, String url) {
        String rootUrl = toRootUrl(url);
        if (StringUtils.hasText(rootUrl)) {
            rootUrls.add(rootUrl);
        }
    }

    private List<String> resolveSourceUrls(SourceCandidate seedCandidate,
                                           SearchCollectionTarget attemptedTarget) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (seedCandidate != null && seedCandidate.getSourceUrls() != null) {
            for (String sourceUrl : seedCandidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    sourceUrls.add(sourceUrl.trim());
                }
            }
        }
        if (seedCandidate != null && StringUtils.hasText(seedCandidate.getUrl())) {
            sourceUrls.add(seedCandidate.getUrl().trim());
        }
        SourceCollector.CollectedPage collectedPage = attemptedTarget == null ? null : attemptedTarget.getCollectedPage();
        if (collectedPage != null && StringUtils.hasText(collectedPage.getUrl())) {
            sourceUrls.add(collectedPage.getUrl().trim());
        }
        return new ArrayList<>(sourceUrls);
    }

    /**
     * metadata 里只接受同站公开链接，并继续走中介页/工具页过滤，避免把验证码页壳上的跳转线索误提为正式候选。
     */
    private void appendRecoveredCandidate(Map<String, SourceCandidate> recoveredCandidates,
                                          String candidateUrl,
                                          RecoveryContext context,
                                          List<String> sourceUrls,
                                          String recoveryOrigin) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(candidateUrl);
        if (!StringUtils.hasText(canonicalUrl)) {
            return;
        }
        if (!isSameSiteFamily(context.getSeedCandidates(), canonicalUrl)) {
            return;
        }

        SourceCandidate candidate = SourceCandidate.builder()
                .url(canonicalUrl)
                .title(buildRecoveredTitle(context, canonicalUrl))
                .sourceType(resolveRecoveredSourceType(context))
                .discoveryMethod("PUBLIC_EVIDENCE_RECOVERY")
                .providerKey("recovery")
                .reason(buildRecoveredReason(context, recoveryOrigin))
                .domain(canonicalUrlResolver.canonicalDomain(canonicalUrl))
                .sourceUrls(copyList(sourceUrls))
                .qualitySignals(List.of("PUBLIC_EVIDENCE_RECOVERY"))
                .relevanceScore(0.78D)
                .freshnessScore(0.55D)
                .qualityScore(0.82D)
                .build();
        if (candidateOwnershipPolicy.isRejectedMediator(candidate, null)
                || candidateOwnershipPolicy.isUtilityGatePage(candidate, null)) {
            return;
        }
        if (!candidateOwnershipPolicy.hasCompetitorDomainOwnershipSignalForCandidate(
                context == null ? null : context.getCompetitorName(),
                context == null ? List.of() : context.getCompetitorUrls(),
                candidate)) {
            return;
        }
        recoveredCandidates.putIfAbsent(canonicalUrl, candidate);
    }

    private String resolveRecoveredSourceType(RecoveryContext context) {
        if (context == null || !StringUtils.hasText(context.getSourceType())) {
            return "OFFICIAL";
        }
        return context.getSourceType().trim().toUpperCase(Locale.ROOT);
    }

    private String buildRecoveredTitle(RecoveryContext context, String candidateUrl) {
        String competitorName = context == null || !StringUtils.hasText(context.getCompetitorName())
                ? "competitor"
                : context.getCompetitorName().trim();
        return competitorName + " public recovery: " + candidateUrl;
    }

    private String buildRecoveredReason(RecoveryContext context, String recoveryOrigin) {
        String fieldName = context == null ? null : context.getFieldName();
        String evidencePathKey = context == null ? null : context.getEvidencePathKey();
        return "public evidence recovery"
                + (StringUtils.hasText(recoveryOrigin) ? " from " + recoveryOrigin : "")
                + (StringUtils.hasText(fieldName) ? ", field=" + fieldName : "")
                + (StringUtils.hasText(evidencePathKey) ? ", evidencePath=" + evidencePathKey : "");
    }

    private List<String> extractMetadataUrls(SearchCollectionTarget attemptedTarget) {
        if (attemptedTarget == null
                || attemptedTarget.getCollectedPage() == null
                || !StringUtils.hasText(attemptedTarget.getCollectedPage().getMetadata())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(attemptedTarget.getCollectedPage().getMetadata());
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            collectUrlsFromNode(root, urls, null);
            return new ArrayList<>(urls);
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * metadata 里只采 canonical / og / json-ld 等明确公开链接字段，
     * 不把任意字符串字段都当成可恢复入口，避免误收中介页或诊断占位值。
     */
    private void collectUrlsFromNode(JsonNode node, Set<String> urls, String fieldName) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            if (!isMetadataLinkField(fieldName)) {
                return;
            }
            String canonicalUrl = canonicalUrlResolver.canonicalize(node.asText());
            if (StringUtils.hasText(canonicalUrl)) {
                urls.add(canonicalUrl);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectUrlsFromNode(child, urls, fieldName);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectUrlsFromNode(entry.getValue(), urls, entry.getKey()));
        }
    }

    private boolean isMetadataLinkField(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return false;
        }
        String normalized = fieldName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("canonical")
                || normalized.contains("opengraph")
                || normalized.contains("og")
                || normalized.contains("jsonld")
                || normalized.contains("schema")
                || normalized.endsWith("url")
                || normalized.endsWith("href");
    }

    private boolean isSameSiteFamily(List<SourceCandidate> seedCandidates, String candidateUrl) {
        String candidateDomain = canonicalUrlResolver.canonicalDomain(candidateUrl);
        if (!StringUtils.hasText(candidateDomain)) {
            return false;
        }
        for (SourceCandidate seedCandidate : seedCandidates == null ? List.<SourceCandidate>of() : seedCandidates) {
            String seedDomain = canonicalUrlResolver.canonicalDomain(seedCandidate == null ? null : seedCandidate.getUrl());
            if (sameDomainFamily(seedDomain, candidateDomain)) {
                return true;
            }
            if (seedCandidate != null && seedCandidate.getSourceUrls() != null) {
                for (String sourceUrl : seedCandidate.getSourceUrls()) {
                    if (sameDomainFamily(canonicalUrlResolver.canonicalDomain(sourceUrl), candidateDomain)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean sameDomainFamily(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        String normalizedLeft = left.toLowerCase(Locale.ROOT);
        String normalizedRight = right.toLowerCase(Locale.ROOT);
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.endsWith("." + normalizedRight)
                || normalizedRight.endsWith("." + normalizedLeft);
    }

    private String toRootUrl(String url) {
        String canonicalUrl = canonicalUrlResolver.canonicalize(url);
        if (!StringUtils.hasText(canonicalUrl)) {
            return null;
        }
        try {
            URI uri = URI.create(canonicalUrl);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }

    private String appendPath(String rootUrl, String path) {
        if (!StringUtils.hasText(rootUrl) || !StringUtils.hasText(path)) {
            return null;
        }
        try {
            return canonicalUrlResolver.canonicalize(URI.create(rootUrl).resolve(path).toString());
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> copyList(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private RecoveryResult emptyResult(RecoveryContext context, String status) {
        return RecoveryResult.builder()
                .status(status)
                .fieldName(context == null ? null : context.getFieldName())
                .evidencePathKey(context == null ? null : context.getEvidencePathKey())
                .queryIntents(copyList(context == null ? null : context.getQueryIntents()))
                .attemptedAlternativeUrls(List.of())
                .attemptedEvidencePaths(StringUtils.hasText(context == null ? null : context.getEvidencePathKey())
                        ? List.of(context.getEvidencePathKey())
                        : List.of())
                .candidates(List.of())
                .build();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecoveryContext {

        private String competitorName;
        private List<String> competitorUrls;
        private String sourceType;
        private String fieldName;
        private String evidencePathKey;
        private List<String> queryIntents;
        private Integer maxAlternatives;
        private List<SourceCandidate> seedCandidates;
        private Map<String, SearchCollectionTarget> attemptedTargets;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecoveryResult {

        private String status;
        private String fieldName;
        private String evidencePathKey;
        private List<String> queryIntents;
        private List<String> attemptedAlternativeUrls;
        private List<String> attemptedEvidencePaths;
        private List<SourceCandidate> candidates;
    }

    /**
     * repair ?????? record ?????????
     * ??????????????????? triggered()/fieldName()/candidates() ????????
     * ??? repair ???????? bean ??????????
     */
    @Builder
    public record RecoveryPlan(boolean triggered,
                               String reason,
                               String fieldName,
                               String evidencePathKey,
                               List<String> queryIntents,
                               List<String> attemptedAlternativeUrls,
                               List<String> attemptedEvidencePaths,
                               List<SourceCandidate> candidates) {
    }
}
