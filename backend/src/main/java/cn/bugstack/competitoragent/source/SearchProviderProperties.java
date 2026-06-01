package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 搜索式补源配置。
 * 如果开启 browserPreviewEnabled，则规划期会优先走浏览器预览补源，否则直接走 HTTP 搜索适配层。
 */
@Data
@ConfigurationProperties(prefix = "source-discovery.search")
public class SearchProviderProperties {

    /** true 表示规划期优先走浏览器预览补源。 */
    private boolean browserPreviewEnabled = false;

    /** 搜索 API 地址，例如 https://api.search.example.com/search。 */
    private String endpoint;

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
}
