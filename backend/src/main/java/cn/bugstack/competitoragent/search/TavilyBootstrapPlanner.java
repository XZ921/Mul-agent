package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 1 bootstrap 规划器只回答一个问题：
 * 当前规划期候选里，是否存在“弱入口候选”，值得先用 Tavily 做候选增强。
 */
@Component
public class TavilyBootstrapPlanner {

    public TavilyBootstrapDecision plan(CollectorNodeConfig config, List<SourceCandidate> plannedCandidates) {
        List<SourceCandidate> weakSeeds = plannedCandidates == null
                ? List.of()
                : plannedCandidates.stream()
                .filter(candidate -> isWeakEntryCandidate(candidate, config))
                .toList();
        if (weakSeeds.isEmpty()) {
            return TavilyBootstrapDecision.builder()
                    .shouldExecute(false)
                    .reason("规划期候选已是强直达页面，无需在 Phase 1 走 Tavily bootstrap")
                    .seedCandidates(List.of())
                    .build();
        }
        return TavilyBootstrapDecision.builder()
                .shouldExecute(true)
                .reason("存在根域/入口候选，先用 Tavily 做 Phase 1 候选增强")
                .seedCandidates(weakSeeds)
                .request(SearchSourceRequest.builder()
                        .competitorName(config == null ? null : config.getCompetitorName())
                        .requestedScopes(config == null || !StringUtils.hasText(config.getSourceType())
                                ? List.of()
                                : List.of(config.getSourceType()))
                        .searchQueries(config == null || config.getSearchQueries() == null ? List.of() : config.getSearchQueries())
                        .preferredDomains(config == null || config.getPreferredDomains() == null ? List.of() : config.getPreferredDomains())
                        .includeDomains(config == null || config.getIncludeDomains() == null ? List.of() : config.getIncludeDomains())
                        .blockedDomains(config == null || config.getBlockedDomains() == null ? List.of() : config.getBlockedDomains())
                        .seedCandidates(weakSeeds)
                        .preferredProviderKey("tavily")
                        .preferredQueryMode(config == null ? null : config.getTavilyQueryMode())
                        .requestPhase(SearchRequestPhase.BOOTSTRAP)
                        .build())
                .build();
    }

    /**
     * 这里把“弱入口候选”最小化定义为三类信号：
     * 1. 根域或一层入口页；
     * 2. 已偏离官方 host 范围；
     * 3. 标题弱到几乎只剩品牌名。
     * 只要命中其中任意一条，就优先让 Tavily 在验证前增强候选。
     */
    private boolean isWeakEntryCandidate(SourceCandidate candidate, CollectorNodeConfig config) {
        String url = candidate == null ? null : candidate.getUrl();
        String host = extractHost(url);
        int pathDepth = pathDepth(url);
        Set<String> officialHosts = resolveOfficialHosts(config);
        boolean rootOrOneLevelEntry = pathDepth <= 1;
        boolean outsideOfficialScope = !officialHosts.isEmpty()
                && officialHosts.stream().noneMatch(officialHost -> isSameOrSubDomain(host, officialHost));
        boolean weakTitle = !StringUtils.hasText(candidate == null ? null : candidate.getTitle())
                || normalizeTitle(candidate.getTitle()).equals(normalizeTitle(config == null ? null : config.getCompetitorName()));
        return rootOrOneLevelEntry || outsideOfficialScope || weakTitle;
    }

    private Set<String> resolveOfficialHosts(CollectorNodeConfig config) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        if (config == null) {
            return hosts;
        }
        addAllHosts(hosts, config.getPreferredDomains());
        addAllHosts(hosts, config.getIncludeDomains());
        addAllHostsFromUrls(hosts, config.getCompetitorUrls());
        return hosts;
    }

    private void addAllHosts(Set<String> hosts, List<String> candidates) {
        if (hosts == null || candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                hosts.add(candidate.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private void addAllHostsFromUrls(Set<String> hosts, List<String> urls) {
        if (hosts == null || urls == null) {
            return;
        }
        for (String url : urls) {
            String host = extractHost(url);
            if (StringUtils.hasText(host)) {
                hosts.add(host);
            }
        }
    }

    private String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return "";
        }
    }

    private int pathDepth(String url) {
        if (!StringUtils.hasText(url)) {
            return Integer.MAX_VALUE;
        }
        try {
            String path = URI.create(url.trim()).getPath();
            if (!StringUtils.hasText(path) || "/".equals(path.trim())) {
                return 0;
            }
            return (int) Arrays.stream(path.split("/"))
                    .filter(StringUtils::hasText)
                    .count();
        } catch (Exception exception) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean isSameOrSubDomain(String candidateHost, String officialHost) {
        if (!StringUtils.hasText(candidateHost) || !StringUtils.hasText(officialHost)) {
            return false;
        }
        String normalizedCandidate = candidateHost.trim().toLowerCase(Locale.ROOT);
        String normalizedOfficial = officialHost.trim().toLowerCase(Locale.ROOT);
        return normalizedCandidate.equals(normalizedOfficial)
                || normalizedCandidate.endsWith("." + normalizedOfficial);
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "";
        }
        return title.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", "");
    }
}
