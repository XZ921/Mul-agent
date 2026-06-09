package cn.bugstack.competitoragent.source;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索式补源配置。
 * 如果开启 browserPreviewEnabled，则规划期会优先走浏览器预览补源，否则直接走 HTTP 搜索适配层。
 */
@Data
@ConfigurationProperties(prefix = "source-discovery.search")
public class SearchProviderProperties {

    /**
     * Provider 路由顺序。
     * 默认顺序遵循“结构化搜索优先、浏览器预览次之、通用 HTTP 兜底”的原则，
     * 这样即使某一条渠道失败，也能继续尝试后续 provider。
     */
    private List<String> providerOrder = List.of("qianfan", "serpapi", "browserPreview", "http");

    /**
     * 各 Provider 的路由开关与降级策略。
     * key 使用 providerKey，对应 SearchSourceProviderDescriptor 中声明的稳定标识。
     */
    private Map<String, ProviderRouteProperties> providers = new LinkedHashMap<>();

    /** true 表示规划期优先走浏览器预览补源。 */
    private boolean browserPreviewEnabled = false;

    /** 搜索 API 地址，例如 https://api.search.example.com/search。 */
    private String endpoint;

    /** HTTP 请求方式，默认 GET；如接入方仅支持 POST，可通过配置切换。 */
    private String requestMethod = "GET";

    /** 搜索 API Key。为空时真实搜索会直接返回空结果，避免启动失败。 */
    private String apiKey;

    /** 鉴权请求头名称，常见值为 Authorization、X-API-Key。 */
    private String apiKeyHeader = "Authorization";

    /** 鉴权请求头前缀。使用 Bearer token 时保持默认；X-API-Key 场景可设为空。 */
    private String apiKeyPrefix = "Bearer ";

    /** 搜索关键词参数名。 */
    private String queryParam = "q";

    /** 每次查询返回条数参数名。 */
    private String limitParam = "limit";

    /** 每种来源范围最多取多少条搜索结果。 */
    private int resultsPerScope = 5;

    /** HTTP 连接与读取超时时间。 */
    private int timeoutSeconds = 15;

    /** 单次 scope 搜索最大重试次数。 */
    private int maxRetries = 2;

    /** JSON 结果数组路径，支持 dot path，例如 results 或 data.items。 */
    private String resultsPath = "results";

    private String titleField = "title";
    private String urlField = "url";
    private String snippetField = "snippet";
    private String publishedAtField = "publishedAt";

    /**
     * 解析指定 Provider 的路由配置。
     * 统一做 key 标准化，避免大小写差异导致的配置失效。
     */
    public ProviderRouteProperties resolveProvider(String providerKey) {
        if (providerKey == null) {
            return null;
        }
        return providers.get(providerKey.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 单个 Provider 的路由覆盖项。
     * 只覆盖调度层行为，不负责各渠道自身的 API 参数绑定。
     */
    @Data
    @NoArgsConstructor
    @lombok.Builder
    public static class ProviderRouteProperties {

        /**
         * 是否启用该 Provider。
         * 为空时表示沿用 Provider 自身在 descriptor 中声明的默认行为。
         */
        private Boolean enabled;

        /**
         * 当该 Provider 执行失败时，是否允许继续降级到后续渠道。
         * 为空时表示沿用 descriptor 中声明的默认行为。
         */
        private Boolean failOpen;

        public ProviderRouteProperties(Boolean enabled, Boolean failOpen) {
            this.enabled = enabled;
            this.failOpen = failOpen;
        }
    }
}
