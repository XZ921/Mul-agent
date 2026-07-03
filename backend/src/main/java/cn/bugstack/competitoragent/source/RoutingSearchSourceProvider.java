package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.CandidateOwnershipPolicy;
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
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;

    /**
     * Spring 运行时统一走显式装配构造器。
     * Task 2.1 引入测试专用构造器后，这里必须声明主注入入口，
     * 否则在集成测试上下文中会因为存在多个构造器而无法判定该选哪一个。
     */
    @Autowired
    public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                       TavilyFastLaneProvider tavilyFastLaneProvider,
                                       QianfanSearchSourceProvider qianfanSearchSourceProvider,
                                       SerpApiSearchSourceProvider serpApiSearchSourceProvider,
                                       BrowserPreviewSearchSourceProvider browserPreviewProvider,
                                       HttpSearchSourceProvider httpSearchSourceProvider,
                                       SourceCandidateRanker sourceCandidateRanker,
                                       SearchPolicyResolver searchPolicyResolver,
                                       CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this(properties,
                List.of(tavilyFastLaneProvider,
                        qianfanSearchSourceProvider,
                        serpApiSearchSourceProvider,
                        browserPreviewProvider,
                        httpSearchSourceProvider),
                sourceCandidateRanker,
                searchPolicyResolver,
                candidateOwnershipPolicy);
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
        this(properties, delegateProviders, sourceCandidateRanker, searchPolicyResolver, new CandidateOwnershipPolicy());
    }

    public RoutingSearchSourceProvider(SearchProviderProperties properties,
                                       List<? extends SearchSourceProvider> delegateProviders,
                                       SourceCandidateRanker sourceCandidateRanker,
                                       SearchPolicyResolver searchPolicyResolver,
                                       CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this.properties = properties;
        this.delegateProviders = List.copyOf(delegateProviders);
        this.sourceCandidateRanker = sourceCandidateRanker;
        this.searchPolicyResolver = searchPolicyResolver;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
    }

    @Override
    public List<SourceCandidate> search(SearchSourceRequest request) {
        if (request == null) {
            return List.of();
        }
        return searchAcrossProviders(
                provider -> provider.search(request),
                normalizedProviderKey -> !StringUtils.hasText(request.getPreferredProviderKey())
                        || normalizeProviderKey(request.getPreferredProviderKey()).equals(normalizedProviderKey),
                request.getRequestedScopes()
        );
    }

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        return searchAcrossProviders(
                provider -> provider.search(competitorName, requestedScopes),
                normalizedProviderKey -> true,
                requestedScopes
        );
    }

    /**
     * 旧入口与 request-mode 入口必须复用同一套 provider 路由骨架，
     * 否则 primarySatisfied、fail-open 和 provider role 语义会在新入口上悄悄失真。
     */
    private List<SourceCandidate> searchAcrossProviders(Function<SearchSourceProvider, List<SourceCandidate>> invoker,
                                                        Predicate<String> providerFilter,
                                                        List<String> requestedScopes) {
        List<SourceCandidate> mergedCandidates = new ArrayList<>();
        Map<String, SearchSourceProvider> providersByKey = indexProvidersByKey();
        int primaryCandidateCount = 0;
        boolean primarySatisfied = false;

        for (String providerKey : resolveProviderOrder()) {
            String normalizedProviderKey = normalizeProviderKey(providerKey);
            SearchSourceProvider provider = providersByKey.get(normalizedProviderKey);
            if (provider == null) {
                continue;
            }
            SearchSourceProviderDescriptor descriptor = provider.descriptor();
            SearchProviderRole providerRole = searchPolicyResolver.resolveProviderRoleForRequestedScopes(
                    descriptor.getProviderKey(),
                    requestedScopes
            );
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
            if (!providerFilter.test(normalizedProviderKey)) {
                continue;
            }
            try {
                List<SourceCandidate> providerCandidates = invoker.apply(provider);
                if (!providerCandidates.isEmpty()) {
                    mergedCandidates.addAll(providerCandidates);
                    if (providerRole == SearchProviderRole.PRIMARY_VERTICAL) {
                        /*
                         * primary satisfied 只能由“满足级正文信号”推进。
                         * 只搜到官网入口/薄壳页不代表主取证已足够，否则会再次跳过 auxiliary public search。
                         */
                        primaryCandidateCount += (int) providerCandidates.stream()
                                .filter(candidateOwnershipPolicy::hasSatisfyingContentSignal)
                                .count();
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
