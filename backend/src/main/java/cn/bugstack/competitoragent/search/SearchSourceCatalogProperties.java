package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源家族目录配置。
 * <p>
 * 这里描述的是“要采什么业务来源”，而不是“具体由哪个 provider 调用哪个接口”。
 * 首轮先固化 official / news / github 三类家族的正式配置骨架，
 * 让后续主采集链路与公网补源链路有稳定的语义落点。
 */
@Data
public class SearchSourceCatalogProperties {

    /**
     * 数据源家族配置集合。
     * key 使用稳定的小写家族标识，便于配置文件、审计对象与前端展示共用同一套名字。
     */
    private Map<String, SourceFamilyProperties> families = createDefaultFamilies();

    /**
     * 按家族标识解析配置。
     * 统一做大小写与空白标准化，避免后续消费方各自处理字符串细节。
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
        return new SourceFamilyProperties(
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
    }

    private SourceFamilyProperties createNewsFamily() {
        return new SourceFamilyProperties(
                true,
                SearchProviderRole.PRIMARY_VERTICAL.name(),
                List.of("NEWS"),
                List.of("PRODUCT_RELEASE", "FUNDING", "PARTNERSHIP"),
                List.of("NEWS_API", "RSS"),
                List.of("PUBLIC_SEARCH"),
                new UpdatePolicyProperties("REALTIME_RSS_AND_SCHEDULED_SWEEP", "PT1H"),
                List.of("search-news-primary", "search-news-secondary")
        );
    }

    private SourceFamilyProperties createGithubFamily() {
        return new SourceFamilyProperties(
                true,
                SearchProviderRole.PRIMARY_VERTICAL.name(),
                List.of("GITHUB", "OPEN_SOURCE"),
                List.of("REPOSITORY", "STAR_TREND", "RELEASE"),
                List.of("GITHUB_API"),
                List.of("PUBLIC_SEARCH"),
                new UpdatePolicyProperties("DAILY_API_POLLING", "PT24H"),
                List.of("search-github-repository", "search-github-release")
        );
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
