package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProviderRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索补源路由器。
 * 统一聚合多个搜索补源实现，避免某一种 provider 命中了单一入口后就过早停止，
 * 导致候选来源过窄、视角单一。
 */
@Slf4j
@Primary
@Component
public class RoutingSearchSourceProvider implements SearchSourceProvider {

    private final SearchProviderProperties properties;
    private final List<SearchSourceProvider> delegateProviders;
    private final SourceCandidateRanker sourceCandidateRanker;
    private final SearchPolicyResolver searchPolicyResolver;

    /**
     * Spring 运行时统一走显式装配构造器。
     * Task 2.1 引入测试专用构造器后，这里必须声明主注入入口，
     * 否则在集成测试上下文中会因为存在多个构造器而无法判定该选哪一个。
     */
    @Autowired
    public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                       QianfanSearchSourceProvider qianfanSearchSourceProvider,
                                       SerpApiSearchSourceProvider serpApiSearchSourceProvider,
                                       BrowserPreviewSearchSourceProvider browserPreviewProvider,
                                       HttpSearchSourceProvider httpSearchSourceProvider,
                                       SourceCandidateRanker sourceCandidateRanker,
                                       SearchPolicyResolver searchPolicyResolver) {
        this(properties,
                List.of(qianfanSearchSourceProvider, serpApiSearchSourceProvider, browserPreviewProvider, httpSearchSourceProvider),
                sourceCandidateRanker,
                searchPolicyResolver);
    }

    public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                       List<? extends SearchSourceProvider> delegateProviders,
                                       SourceCandidateRanker sourceCandidateRanker) {
        this(properties, delegateProviders, sourceCandidateRanker, new SearchPolicyResolver());
    }

    public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                       List<? extends SearchSourceProvider> delegateProviders,
                                       SourceCandidateRanker sourceCandidateRanker,
                                       SearchPolicyResolver searchPolicyResolver) {
        this.properties = properties;
        this.delegateProviders = List.copyOf(delegateProviders);
        this.sourceCandidateRanker = sourceCandidateRanker;
        this.searchPolicyResolver = searchPolicyResolver;
    }

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        List<SourceCandidate> mergedCandidates = new ArrayList<>();
        Map<String, SearchSourceProvider> providersByKey = indexProvidersByKey();
        int primaryCandidateCount = 0;
        boolean primarySatisfied = false;

        for (String providerKey : resolveProviderOrder()) {
            SearchSourceProvider provider = providersByKey.get(normalizeProviderKey(providerKey));
            if (provider == null) {
                continue;
            }
            SearchSourceProviderDescriptor descriptor = provider.descriptor();
            SearchProviderRole providerRole = searchPolicyResolver.resolveProviderRole(descriptor.getProviderKey());
            if (primarySatisfied
                    && !properties.isRunAuxiliaryWhenPrimarySatisfied()
                    && providerRole != SearchProviderRole.PRIMARY_VERTICAL) {
                log.debug("skip auxiliary provider because primary threshold already satisfied, provider={}",
                        descriptor.getProviderKey());
                continue;
            }
            if (!descriptor.isEnabled(properties)) {
                log.debug("search provider disabled by routing config, provider={}", descriptor.getProviderKey());
                continue;
            }
            if (!provider.isAvailable()) {
                log.debug("search provider unavailable, provider={}", descriptor.getProviderKey());
                continue;
            }
            try {
                List<SourceCandidate> providerCandidates = provider.search(competitorName, requestedScopes);
                if (!providerCandidates.isEmpty()) {
                    mergedCandidates.addAll(providerCandidates);
                    if (providerRole == SearchProviderRole.PRIMARY_VERTICAL) {
                        primaryCandidateCount += providerCandidates.size();
                        primarySatisfied = primaryCandidateCount >= Math.max(1, properties.getPrimaryCandidateThreshold());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("search provider failed, provider={}, failOpen={}, error={}",
                        descriptor.getProviderKey(),
                        descriptor.isFailOpen(properties),
                        e.getMessage());
                if (!descriptor.isFailOpen(properties)) {
                    throw e;
                }
            }
        }

        return sourceCandidateRanker.rankAndDeduplicate(mergedCandidates);
    }

    private Map<String, SearchSourceProvider> indexProvidersByKey() {
        Map<String, SearchSourceProvider> providersByKey = new LinkedHashMap<>();
        for (SearchSourceProvider provider : delegateProviders) {
            SearchSourceProviderDescriptor descriptor = provider.descriptor();
            if (descriptor == null || !StringUtils.hasText(descriptor.getProviderKey())) {
                continue;
            }
            providersByKey.put(normalizeProviderKey(descriptor.getProviderKey()), provider);
        }
        return providersByKey;
    }

    private List<String> resolveProviderOrder() {
        if (properties.getProviderOrder() != null && !properties.getProviderOrder().isEmpty()) {
            return properties.getProviderOrder();
        }
        return delegateProviders.stream()
                .map(SearchSourceProvider::descriptor)
                .map(SearchSourceProviderDescriptor::getProviderKey)
                .toList();
    }

    private String normalizeProviderKey(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            return "";
        }
        return providerKey.trim().toLowerCase(Locale.ROOT);
    }
}
