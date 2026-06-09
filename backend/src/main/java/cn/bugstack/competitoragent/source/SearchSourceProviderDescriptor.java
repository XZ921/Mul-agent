package cn.bugstack.competitoragent.source;

import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 搜索补源 Provider 能力描述符。
 * 统一声明每个 Provider 的标识、展示名称、能力标签以及默认路由策略，
 * 让路由器不再依赖硬编码分支，而是按声明式元数据完成启停与降级判断。
 */
@Value
@Builder
public class SearchSourceProviderDescriptor {

    /**
     * Provider 的稳定标识。
     * 该值会作为配置键参与路由开关、顺序与降级策略匹配，因此必须保持稳定且可读。
     */
    String providerKey;

    /**
     * Provider 的展示名称。
     * 主要用于日志、调试和后续前端可视化说明。
     */
    String displayName;

    /**
     * Provider 的能力标签。
     * 例如 WEB_SEARCH、CHINESE_RESULTS、BROWSER_PREVIEW 等，用于表达该渠道擅长的补源能力。
     */
    @Builder.Default
    List<String> capabilities = List.of();

    /**
     * 当配置中没有显式开关时，Provider 是否默认参与路由。
     */
    @Builder.Default
    boolean defaultEnabled = true;

    /**
     * 当 Provider 执行失败时，是否默认允许路由器继续向后降级。
     */
    @Builder.Default
    boolean defaultFailOpen = true;

    /**
     * 根据路由配置判断 Provider 是否启用。
     */
    public boolean isEnabled(SearchProviderProperties properties) {
        SearchProviderProperties.ProviderRouteProperties routeProperties = resolveRouteProperties(properties);
        if (routeProperties == null || routeProperties.getEnabled() == null) {
            return defaultEnabled;
        }
        return routeProperties.getEnabled();
    }

    /**
     * 根据路由配置判断 Provider 失败后是否允许继续降级。
     */
    public boolean isFailOpen(SearchProviderProperties properties) {
        SearchProviderProperties.ProviderRouteProperties routeProperties = resolveRouteProperties(properties);
        if (routeProperties == null || routeProperties.getFailOpen() == null) {
            return defaultFailOpen;
        }
        return routeProperties.getFailOpen();
    }

    private SearchProviderProperties.ProviderRouteProperties resolveRouteProperties(SearchProviderProperties properties) {
        if (properties == null || !StringUtils.hasText(providerKey)) {
            return null;
        }
        return properties.resolveProvider(providerKey.toLowerCase(Locale.ROOT));
    }
}
