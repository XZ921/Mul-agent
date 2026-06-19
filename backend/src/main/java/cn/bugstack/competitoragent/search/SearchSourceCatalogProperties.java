package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源家族目录配置。
 * <p>
 * 这里描述的是“要采什么业务来源”，而不是“具体由哪个 provider 调哪个接口”。
 * 当前阶段先把 official / news / github 三类家族显式建模，保证 preview、runtime、audit
 * 都能复用同一套 source family 语义。
 */
@Data
public class SearchSourceCatalogProperties {

    /**
     * 数据源家族配置集合。
     * key 使用稳定的小写家族标识，方便配置文件、审计对象与前端展示共用同一套名字。
     */
    private Map<String, SourceFamilyProperties> families = createDefaultFamilies();

    /**
     * 按家族标识解析配置。
     * 统一处理大小写与空白，避免调用方各自散落字符串清洗逻辑。
     */
    public SourceFamilyProperties resolveFamily(String familyKey) {
        if (!StringUtils.hasText(familyKey)) {
            return null;
        }
        return families.get(familyKey.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, SourceFamilyProperties> createDefaultFamilies() {
        LinkedHashMap<String, SourceFamilyProperties> defaults = new LinkedHashMap<>();
        defaults.put("official", createOfficialFamily());
        defaults.put("news", createNewsFamily());
        defaults.put("github", createGithubFamily());
        return defaults;
    }

    private SourceFamilyProperties createOfficialFamily() {
        SourceFamilyProperties family = new SourceFamilyProperties(
                true,
                SearchProviderRole.PRIMARY_VERTICAL.name(),
                List.of("OFFICIAL", "PRICING", "DOCS"),
                List.of("PRODUCT_PAGE", "PRICING", "DOCUMENTATION"),
                List.of("WEB_SCRAPER", "JINA_READER"),
                List.of("PUBLIC_SEARCH"),
                new UpdatePolicyProperties("DAILY_INCREMENTAL", "PT24H"),
                List.of(
                        "search-official",
                        "search-official-domain",
                        "search-pricing-primary",
                        "search-docs-primary"
                )
        );
        family.setPreferredWebRenderHint("LIGHTWEIGHT");
        family.setExpectedBlockTypes(List.of(
                "PRICING_BLOCK",
                "DOCUMENTATION_OUTLINE",
                "JSON_LD_METADATA"
        ));
        family.setDirectPathTemplates(List.of("/", "/pricing", "/docs", "/documentation", "/help"));
        family.setDirectSubdomainTemplates(List.of("docs.{domain}", "developer.{domain}", "open.{domain}", "help.{domain}"));
        family.setStableLocatorHosts(List.of());
        family.setStableLocatorSchemes(List.of("https"));
        return family;
    }

    private SourceFamilyProperties createNewsFamily() {
        SourceFamilyProperties family = new SourceFamilyProperties(
                true,
                SearchProviderRole.PRIMARY_VERTICAL.name(),
                List.of("NEWS"),
                List.of("PRODUCT_RELEASE", "FUNDING", "PARTNERSHIP"),
                List.of("RSS"),
                List.of("PUBLIC_SEARCH"),
                new UpdatePolicyProperties("REALTIME_RSS_AND_SCHEDULED_SWEEP", "PT1H"),
                List.of("search-news-primary", "search-news-secondary")
        );
        /**
         * news 家族当前的正式语义是“RSS 正式采集 + public search 辅助发现”。
         * 普通新闻正文 URL 仍走网页采集主链路，不把 News API 当作当前波次的正式 owner。
         */
        family.getToolProviderKeys().put("RSS", "rss");
        family.getToolProviderKeys().put("PUBLIC_SEARCH", "qianfan");
        family.setPreferredWebRenderHint("LIGHTWEIGHT");
        family.setExpectedBlockTypes(List.of("ARTICLE_BODY", "JSON_LD_METADATA"));
        return family;
    }

    private SourceFamilyProperties createGithubFamily() {
        SourceFamilyProperties family = new SourceFamilyProperties(
                true,
                SearchProviderRole.PRIMARY_VERTICAL.name(),
                List.of("GITHUB", "OPEN_SOURCE"),
                List.of("REPOSITORY", "STAR_TREND", "RELEASE"),
                List.of("GITHUB_API"),
                List.of("PUBLIC_SEARCH"),
                new UpdatePolicyProperties("DAILY_API_POLLING", "PT24H"),
                List.of("search-github-repository", "search-github-release")
        );
        /**
         * GitHub 家族显式绑定正式 owner 与 provider 语义。
         * 这样 discovery 路由与 collection 路由都能基于同一份家族配置判断是否进入 API 路径。
         */
        family.getToolProviderKeys().put("GITHUB_API", "github");
        family.getToolProviderKeys().put("PUBLIC_SEARCH", "qianfan");
        family.setPreferredWebRenderHint("FULL_RENDER");
        family.setExpectedBlockTypes(List.of("RELEASE_NOTES", "JSON_LD_METADATA"));
        family.setDirectPathTemplates(List.of());
        family.setStableLocatorHosts(List.of("github.com"));
        family.setStableLocatorSchemes(List.of("https", "github"));
        return family;
    }

    /**
     * 单个数据源家族的配置描述。
     */
    @Data
    public static class SourceFamilyProperties {

        private boolean enabled = true;
        private String role = SearchProviderRole.AUXILIARY_PUBLIC.name();
        private List<String> sourceTypes = List.of();
        private List<String> contentScopes = List.of();
        private List<String> primaryTools = List.of();
        private List<String> auxiliaryTools = List.of();
        private UpdatePolicyProperties updatePolicy = new UpdatePolicyProperties();
        private List<String> queryTemplates = List.of();
        /**
         * 网页采集偏好只解释“公开网页应该怎么采”，
         * 不替代 provider route，也不替代 API executor owner。
         */
        private String preferredWebRenderHint;
        private List<String> expectedBlockTypes = new ArrayList<>();
        /**
         * source family 级别的稳定直达路径模板。
         * 这里只声明命中根域后可以稳定展开的入口，不承担站点爬虫规则职责。
         */
        private List<String> directPathTemplates = new ArrayList<>();
        /**
         * source family 级别的稳定直达子域模板。
         * 这里只声明命中根域后可以稳定展开的入口，不承担站点爬虫规则职责。
         */
        private List<String> directSubdomainTemplates = new ArrayList<>();
        /**
         * 稳定 locator 允许的宿主集合。
         * 为空表示不限制具体 host，只要求 host 本身存在。
         */
        private List<String> stableLocatorHosts = new ArrayList<>();
        /**
         * 稳定 locator 允许的 scheme 集合。
         * 例如 official 只接受 https，github 同时接受 https 与 github locator。
         */
        private List<String> stableLocatorSchemes = new ArrayList<>();
        /**
         * 工具到 provider key 的可选绑定。
         * Source Family Catalog 只声明业务家族与工具语义；
         * 真实 provider 是否启用、是否 fail-open，仍由 SearchProviderProperties 管理。
         */
        private Map<String, String> toolProviderKeys = new LinkedHashMap<>();

        public SourceFamilyProperties() {
        }

        public SourceFamilyProperties(boolean enabled,
                                      String role,
                                      List<String> sourceTypes,
                                      List<String> contentScopes,
                                      List<String> primaryTools,
                                      List<String> auxiliaryTools,
                                      UpdatePolicyProperties updatePolicy,
                                      List<String> queryTemplates) {
            this.enabled = enabled;
            this.role = role;
            this.sourceTypes = sourceTypes;
            this.contentScopes = contentScopes;
            this.primaryTools = primaryTools;
            this.auxiliaryTools = auxiliaryTools;
            this.updatePolicy = updatePolicy;
            this.queryTemplates = queryTemplates;
        }

        /**
         * 根据主辅角色解析当前家族绑定到哪些 provider key。
         * 当前波次只要求配置字段和解析语义稳定，不要求这些 provider 一定全部有真实实现。
         */
        public List<String> resolveProviderKeys(SearchProviderRole role) {
            List<String> toolKeys = role == SearchProviderRole.PRIMARY_VERTICAL ? primaryTools : auxiliaryTools;
            if (toolKeys == null || toolKeys.isEmpty() || toolProviderKeys == null || toolProviderKeys.isEmpty()) {
                return List.of();
            }
            return toolKeys.stream()
                    .map(toolProviderKeys::get)
                    .filter(StringUtils::hasText)
                    .toList();
        }

        /**
         * 统一解析网页采集提示。
         * 配置缺失时安全回退为 LIGHTWEIGHT，避免调用方继续散落默认值。
         */
        public String resolvePreferredWebRenderHint() {
            return StringUtils.hasText(preferredWebRenderHint)
                    ? preferredWebRenderHint.trim().toUpperCase(Locale.ROOT)
                    : "LIGHTWEIGHT";
        }
    }

    /**
     * 数据源家族的更新策略。
     */
    @Data
    public static class UpdatePolicyProperties {

        private String mode;
        private String interval;

        public UpdatePolicyProperties() {
        }

        public UpdatePolicyProperties(String mode, String interval) {
            this.mode = mode;
            this.interval = interval;
        }
    }
}
