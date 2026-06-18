package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import cn.bugstack.competitoragent.source.GithubApiProperties;
import cn.bugstack.competitoragent.source.SearchProviderProperties;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索与采集能力 readiness 守卫。
 * 这个守卫只负责三件事：
 * 1. 校验 PRIMARY owner 是否真正 ready；
 * 2. 汇总 AUXILIARY provider 的可用性与缺失原因；
 * 3. 明确区分规划期 provider readiness 与运行期 browser readiness。
 */
@Slf4j
@Component
public class SearchCapabilityReadinessGuard implements ApplicationRunner {

    /**
     * RSS owner readiness 的边界文案必须集中收口。
     * 这样无论是启动期日志、后续测试还是排障说明，都不会再把当前能力误读成完整的 RSS 订阅体系。
     */
    static final String RSS_OWNER_BOUNDARY_MESSAGE =
            "rss owner ready for explicit feed urls only; news article urls still go through webpage collection; "
                    + "feed subscription monitoring, seed discovery, cursor and replay are out of current scope";

    private final SearchProperties searchProperties;
    private final SearchProviderProperties searchProviderProperties;
    private final SearchBrowserProperties searchBrowserProperties;
    private final GithubApiProperties githubApiProperties;
    private final SerpApiProperties serpApiProperties;
    private final QianfanSearchProperties qianfanSearchProperties;

    public SearchCapabilityReadinessGuard(SearchProperties searchProperties,
                                          SearchProviderProperties searchProviderProperties,
                                          SearchBrowserProperties searchBrowserProperties,
                                          GithubApiProperties githubApiProperties,
                                          SerpApiProperties serpApiProperties,
                                          QianfanSearchProperties qianfanSearchProperties) {
        this.searchProperties = searchProperties;
        this.searchProviderProperties = searchProviderProperties;
        this.searchBrowserProperties = searchBrowserProperties;
        this.githubApiProperties = githubApiProperties;
        this.serpApiProperties = serpApiProperties;
        this.qianfanSearchProperties = qianfanSearchProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ReadinessSummary summary = buildSummary();
        validatePrimaryOwners(summary);
        log.info("搜索能力 readiness 摘要: planningProvidersAvailable={}, runtimeBrowserEnabled={}, runtimeBrowserEngine={}",
                summary.isAnyPlanningProviderAvailable(),
                summary.isRuntimeBrowserEnabled(),
                summary.getRuntimeBrowserEngine());
        for (ProviderReadiness provider : summary.getProviders().values()) {
            if (provider.isRouteEnabled() && !provider.isAvailable()) {
                log.warn("搜索 provider 未就绪: provider={}, reason={}, planningRouteEnabled={}",
                        provider.getProviderKey(),
                        provider.getUnavailableReason(),
                        provider.isRouteEnabled());
                continue;
            }
            log.info("搜索 provider readiness: provider={}, routeEnabled={}, available={}, reason={}",
                    provider.getProviderKey(),
                    provider.isRouteEnabled(),
                    provider.isAvailable(),
                    provider.getUnavailableReason());
        }
        log.info("规划期/运行期浏览器 readiness: planningProvidersAvailable={}, browserPreviewRouteEnabled={}, browserPreviewFeatureEnabled={}, runtimeBrowserEnabled={}",
                summary.isAnyPlanningProviderAvailable(),
                summary.isBrowserPreviewRouteEnabled(),
                summary.isBrowserPreviewFeatureEnabled(),
                summary.isRuntimeBrowserEnabled());
        log.info("RSS owner readiness 边界: {}", RSS_OWNER_BOUNDARY_MESSAGE);
    }

    /**
     * 构建可测试的 readiness 摘要对象。
     * 测试直接断言这个摘要，而不是抓日志文本，能让约束更稳定、更易维护。
     */
    ReadinessSummary buildSummary() {
        Map<String, ProviderReadiness> providers = new LinkedHashMap<>();
        providers.put("qianfan", buildQianfanReadiness());
        providers.put("serpapi", buildSerpApiReadiness());
        providers.put("http", buildHttpProviderReadiness());
        providers.put("browserpreview", buildBrowserPreviewReadiness());

        return ReadinessSummary.builder()
                .githubPrimaryRequired(isGithubPrimaryRequired())
                .githubPrimaryReady(githubApiProperties != null && githubApiProperties.isReady())
                .githubPrimaryFailureReason(githubApiProperties == null
                        ? "github api properties unavailable"
                        : githubApiProperties.resolveReadinessFailureMessage())
                .providers(providers)
                .browserPreviewRouteEnabled(isRouteEnabled("browserpreview"))
                .browserPreviewFeatureEnabled(searchProviderProperties != null
                        && searchProviderProperties.isBrowserPreviewEnabled())
                .runtimeBrowserEnabled(searchBrowserProperties != null && searchBrowserProperties.isEnabled())
                .runtimeBrowserEngine(searchBrowserProperties == null ? null : searchBrowserProperties.getEngine())
                .build();
    }

    /**
     * 只有 GitHub family 已启用且 primary tool 包含 GITHUB_API 时，才要求启动期 hard fail。
     * 这次修复只把 GitHub 视为必须诚实失败的 PRIMARY owner，不扩大到所有辅助 provider。
     */
    private boolean isGithubPrimaryRequired() {
        if (searchProperties == null || searchProperties.getSourceCatalog() == null) {
            return false;
        }
        SearchSourceCatalogProperties.SourceFamilyProperties githubFamily =
                searchProperties.getSourceCatalog().resolveFamily("github");
        return githubFamily != null
                && githubFamily.isEnabled()
                && githubFamily.getPrimaryTools() != null
                && githubFamily.getPrimaryTools().stream().anyMatch("GITHUB_API"::equalsIgnoreCase);
    }

    private void validatePrimaryOwners(ReadinessSummary summary) {
        if (summary.isGithubPrimaryRequired() && !summary.isGithubPrimaryReady()) {
            throw new IllegalStateException(summary.getGithubPrimaryFailureReason());
        }
    }

    private ProviderReadiness buildQianfanReadiness() {
        String endpoint = qianfanSearchProperties == null ? null : qianfanSearchProperties.getEndpoint();
        String apiKey = qianfanSearchProperties == null ? null : qianfanSearchProperties.getApiKey();
        return buildApiProviderReadiness("qianfan", endpoint, apiKey);
    }

    private ProviderReadiness buildSerpApiReadiness() {
        String endpoint = serpApiProperties == null ? null : serpApiProperties.getEndpoint();
        String apiKey = serpApiProperties == null ? null : serpApiProperties.getApiKey();
        return buildApiProviderReadiness("serpapi", endpoint, apiKey);
    }

    private ProviderReadiness buildHttpProviderReadiness() {
        if (searchProviderProperties == null) {
            return ProviderReadiness.builder()
                    .providerKey("http")
                    .routeEnabled(false)
                    .available(false)
                    .unavailableReason("search provider properties unavailable")
                    .build();
        }
        return buildApiProviderReadiness(
                "http",
                searchProviderProperties.getEndpoint(),
                searchProviderProperties.getApiKey()
        );
    }

    private ProviderReadiness buildBrowserPreviewReadiness() {
        boolean routeEnabled = isRouteEnabled("browserpreview");
        boolean featureEnabled = searchProviderProperties != null && searchProviderProperties.isBrowserPreviewEnabled();
        boolean runtimeBrowserEnabled = searchBrowserProperties != null && searchBrowserProperties.isEnabled();
        String reason = null;
        if (!featureEnabled) {
            reason = "browser-preview-enabled=false";
        } else if (!runtimeBrowserEnabled) {
            reason = "search.browser.enabled=false";
        }
        return ProviderReadiness.builder()
                .providerKey("browserpreview")
                .routeEnabled(routeEnabled)
                .available(featureEnabled && runtimeBrowserEnabled)
                .unavailableReason(reason)
                .build();
    }

    private ProviderReadiness buildApiProviderReadiness(String providerKey, String endpoint, String apiKey) {
        boolean routeEnabled = isRouteEnabled(providerKey);
        boolean endpointValid = UrlSecurityUtils.isHttpsUrl(endpoint);
        boolean apiKeyPresent = StringUtils.hasText(apiKey);
        String reason = null;
        if (!apiKeyPresent) {
            reason = "apiKey missing";
        } else if (!endpointValid) {
            reason = "endpoint invalid";
        }
        return ProviderReadiness.builder()
                .providerKey(providerKey)
                .routeEnabled(routeEnabled)
                .available(apiKeyPresent && endpointValid)
                .unavailableReason(reason)
                .build();
    }

    private boolean isRouteEnabled(String providerKey) {
        if (searchProviderProperties == null) {
            return false;
        }
        SearchProviderProperties.ProviderRouteProperties routeProperties =
                searchProviderProperties.resolveProvider(providerKey);
        if (routeProperties == null || routeProperties.getEnabled() == null) {
            return true;
        }
        return routeProperties.getEnabled();
    }

    @Value
    @Builder
    static class ReadinessSummary {
        boolean githubPrimaryRequired;
        boolean githubPrimaryReady;
        String githubPrimaryFailureReason;
        Map<String, ProviderReadiness> providers;
        boolean browserPreviewRouteEnabled;
        boolean browserPreviewFeatureEnabled;
        boolean runtimeBrowserEnabled;
        String runtimeBrowserEngine;

        boolean isAnyPlanningProviderAvailable() {
            return providers != null && providers.values().stream().anyMatch(ProviderReadiness::isAvailable);
        }
    }

    @Value
    @Builder
    static class ProviderReadiness {
        String providerKey;
        boolean routeEnabled;
        boolean available;
        String unavailableReason;
    }
}
