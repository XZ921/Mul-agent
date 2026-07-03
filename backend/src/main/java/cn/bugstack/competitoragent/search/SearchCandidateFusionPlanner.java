package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * search-first 候选融合与验证规划器。
 * 统一把 planned/direct seed、Tavily bootstrap、后续 supplement 候选收进同一个池子，
 * 再显式给出 preselected / verification / fast-lane 三类决策。
 */
@Component
public class SearchCandidateFusionPlanner {

    private final SearchPolicyResolver searchPolicyResolver;
    private final SourceCandidateRanker sourceCandidateRanker;

    public SearchCandidateFusionPlanner() {
        this(new SearchPolicyResolver(), new SourceCandidateRanker());
    }

    public SearchCandidateFusionPlanner(SearchPolicyResolver searchPolicyResolver,
                                        SourceCandidateRanker sourceCandidateRanker) {
        this.searchPolicyResolver = searchPolicyResolver == null
                ? new SearchPolicyResolver()
                : searchPolicyResolver;
        this.sourceCandidateRanker = sourceCandidateRanker == null
                ? new SourceCandidateRanker()
                : sourceCandidateRanker;
    }

    /**
     * 这里的 plan 只负责“融合后怎么验证、怎么选”，
     * 不承担真正的网络验证与页面抓取。
     */
    public SearchCandidateFusionDecision plan(CollectorNodeConfig config,
                                              List<SourceCandidate> candidates,
                                              int baseTargetCount,
                                              int maxCandidatesPerDomain) {
        List<SourceCandidate> rankedCandidates = rankCandidates(config, candidates, maxCandidatesPerDomain);
        int effectiveTargetCount = searchPolicyResolver.resolveEffectiveTargetCountForSearchFirst(
                config,
                baseTargetCount,
                rankedCandidates.size()
        );
        List<SourceCandidate> preselectedCandidates = new ArrayList<>(rankedCandidates.stream()
                .limit(Math.max(0, effectiveTargetCount))
                .toList());
        List<SourceCandidate> fastLaneCandidates = preselectedCandidates.stream()
                .filter(this::isStrongFastLaneCandidate)
                .toList();
        int verificationLimit = searchPolicyResolver.resolvePreSelectionVerificationLimit(config, effectiveTargetCount);
        List<SourceCandidate> verificationCandidates = preselectedCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getSkipNetworkVerification()))
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getVerified()))
                .limit(Math.max(0, verificationLimit))
                .toList();
        int directSeedCandidateCount = (int) rankedCandidates.stream()
                .filter(this::isDirectSeedCandidate)
                .count();
        int tavilyCandidateCount = (int) rankedCandidates.stream()
                .filter(candidate -> candidate != null && "tavily".equalsIgnoreCase(candidate.getProviderKey()))
                .count();
        int thirdPartyCandidateCount = (int) preselectedCandidates.stream()
                .filter(candidate -> isThirdPartyCandidate(config, candidate))
                .count();
        return SearchCandidateFusionDecision.builder()
                .baseTargetCount(baseTargetCount)
                .effectiveTargetCount(effectiveTargetCount)
                .directSeedCandidateCount(directSeedCandidateCount)
                .tavilyCandidateCount(tavilyCandidateCount)
                .fastLaneCandidateCount(fastLaneCandidates.size())
                .thirdPartyCandidateCount(thirdPartyCandidateCount)
                .verificationCandidateCount(verificationCandidates.size())
                .rankedCandidates(rankedCandidates)
                .preselectedCandidates(preselectedCandidates)
                .verificationCandidates(verificationCandidates)
                .fastLaneCandidates(fastLaneCandidates)
                .reason(resolveReason(config, effectiveTargetCount, fastLaneCandidates.size(), verificationCandidates.size()))
                .build();
    }

    /**
     * 融合排序必须显式压住 direct seed，
     * 否则官方根域和模板页会因为默认分数更高，把第三方强正文重新挤出预选池。
     */
    private List<SourceCandidate> rankCandidates(CollectorNodeConfig config,
                                                 List<SourceCandidate> candidates,
                                                 int maxCandidatesPerDomain) {
        List<SourceCandidate> ranked = new ArrayList<>(sourceCandidateRanker.rankAndDeduplicate(candidates));
        ranked.sort(Comparator
                .comparingInt((SourceCandidate candidate) -> resolveFusionTier(config, candidate))
                .thenComparing(SourceCandidate::getTotalScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SourceCandidate::getPrefetchedRawContentLength, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SourceCandidate::getUrl, Comparator.nullsLast(String::compareTo)));
        return applyPerDomainCap(ranked, maxCandidatesPerDomain);
    }

    private List<SourceCandidate> applyPerDomainCap(List<SourceCandidate> rankedCandidates, int maxCandidatesPerDomain) {
        if (rankedCandidates == null || rankedCandidates.isEmpty() || maxCandidatesPerDomain <= 0) {
            return rankedCandidates == null ? List.of() : rankedCandidates;
        }
        Map<String, Integer> domainCounter = new LinkedHashMap<>();
        List<SourceCandidate> limited = new ArrayList<>();
        for (SourceCandidate rankedCandidate : rankedCandidates) {
            String domain = resolveDomain(rankedCandidate);
            int currentCount = domainCounter.getOrDefault(domain, 0);
            if (StringUtils.hasText(domain) && currentCount >= maxCandidatesPerDomain) {
                continue;
            }
            limited.add(rankedCandidate);
            if (StringUtils.hasText(domain)) {
                domainCounter.put(domain, currentCount + 1);
            }
        }
        return limited;
    }

    private int resolveFusionTier(CollectorNodeConfig config, SourceCandidate candidate) {
        if (candidate == null) {
            return Integer.MAX_VALUE;
        }
        if (isStrongFastLaneCandidate(candidate)) {
            return 0;
        }
        if (Boolean.TRUE.equals(candidate.getVerified())) {
            return 1;
        }
        if (isThirdPartyCandidate(config, candidate) && "STRONG".equalsIgnoreCase(candidate.getQualityTier())) {
            return 2;
        }
        if (isDirectSeedCandidate(candidate)) {
            return 4;
        }
        return 3;
    }

    private boolean isStrongFastLaneCandidate(SourceCandidate candidate) {
        return candidate != null
                && Boolean.TRUE.equals(candidate.getFastLaneUsable())
                && Boolean.TRUE.equals(candidate.getHasPrefetchedContent())
                && "STRONG".equalsIgnoreCase(candidate.getQualityTier());
    }

    private boolean isDirectSeedCandidate(SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String discoveryMethod = candidate.getDiscoveryMethod();
        String providerKey = candidate.getProviderKey();
        return equalsAny(discoveryMethod, "DIRECT_LOCATOR", "FAMILY_TEMPLATE", "FAMILY_SUBDOMAIN_TEMPLATE", "HEURISTIC")
                || "planned".equalsIgnoreCase(providerKey);
    }

    private boolean isThirdPartyCandidate(CollectorNodeConfig config, SourceCandidate candidate) {
        String domain = resolveDomain(candidate);
        if (!StringUtils.hasText(domain)) {
            return false;
        }
        Set<String> officialDomains = resolveOfficialDomains(config);
        if (officialDomains.isEmpty()) {
            return false;
        }
        return officialDomains.stream().noneMatch(officialDomain -> isSameOrSubDomain(domain, officialDomain));
    }

    private Set<String> resolveOfficialDomains(CollectorNodeConfig config) {
        LinkedHashSet<String> officialDomains = new LinkedHashSet<>();
        if (config == null) {
            return officialDomains;
        }
        addDomains(officialDomains, config.getPreferredDomains());
        addDomains(officialDomains, config.getIncludeDomains());
        if (config.getCompetitorUrls() != null) {
            for (String competitorUrl : config.getCompetitorUrls()) {
                String host = extractHost(competitorUrl);
                if (StringUtils.hasText(host)) {
                    officialDomains.add(host);
                }
            }
        }
        return officialDomains;
    }

    private void addDomains(Set<String> officialDomains, List<String> domains) {
        if (officialDomains == null || domains == null) {
            return;
        }
        for (String domain : domains) {
            if (StringUtils.hasText(domain)) {
                officialDomains.add(domain.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private String resolveDomain(SourceCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (StringUtils.hasText(candidate.getDomain())) {
            return candidate.getDomain().trim().toLowerCase(Locale.ROOT);
        }
        return extractHost(candidate.getUrl());
    }

    private String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? null : uri.getHost().trim().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isSameOrSubDomain(String candidateDomain, String officialDomain) {
        if (!StringUtils.hasText(candidateDomain) || !StringUtils.hasText(officialDomain)) {
            return false;
        }
        String normalizedCandidate = candidateDomain.trim().toLowerCase(Locale.ROOT);
        String normalizedOfficial = officialDomain.trim().toLowerCase(Locale.ROOT);
        return normalizedCandidate.equals(normalizedOfficial)
                || normalizedCandidate.endsWith("." + normalizedOfficial);
    }

    private boolean equalsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value) || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String resolveReason(CollectorNodeConfig config,
                                 int effectiveTargetCount,
                                 int fastLaneCandidateCount,
                                 int verificationCandidateCount) {
        if (config != null && searchPolicyResolver.isSearchFirstSourceFamilyForSourceType(config.getSourceType())) {
            return "search-first 融合完成：effectiveTargetCount=" + effectiveTargetCount
                    + "，fastLane=" + fastLaneCandidateCount
                    + "，verification=" + verificationCandidateCount;
        }
        return "候选融合完成";
    }
}
