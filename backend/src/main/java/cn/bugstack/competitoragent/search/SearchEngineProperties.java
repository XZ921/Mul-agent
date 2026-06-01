package cn.bugstack.competitoragent.search;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索引擎配置。
 * 这里直接按引擎名维护一个配置映射，便于运行期动态拼装搜索 URL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ConfigurationProperties(prefix = "search.engines")
public class SearchEngineProperties extends LinkedHashMap<String, SearchEngineProperties.EngineConfig> {

    public SearchEngineProperties() {
        // 保留一组默认引擎，避免配置缺失时整个浏览器搜索链路直接失效。
        put("bing", new EngineConfig("Bing", "https://www.bing.com/search", "q", true));
        put("google", new EngineConfig("Google", "https://www.google.com/search", "q", false));
        put("baidu", new EngineConfig("百度", "https://www.baidu.com/s", "wd", false));
        put("duckduckgo", new EngineConfig("DuckDuckGo", "https://duckduckgo.com/", "q", false));
    }

    public EngineConfig resolve(String engineKey) {
        String normalized = normalizeEngineKey(engineKey);
        return get(normalized);
    }

    public String resolveFirstEnabledEngineKey() {
        for (Map.Entry<String, EngineConfig> entry : entrySet()) {
            if (entry.getValue() != null && entry.getValue().isEnabled()) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<String> resolveEnabledEngineKeys(String primaryEngine, List<String> fallbackEngines) {
        List<String> engines = new ArrayList<>();
        addIfEnabled(engines, primaryEngine);
        if (fallbackEngines != null) {
            for (String fallback : fallbackEngines) {
                addIfEnabled(engines, fallback);
            }
        }
        if (engines.isEmpty()) {
            String defaultEnabled = resolveFirstEnabledEngineKey();
            if (StringUtils.hasText(defaultEnabled)) {
                engines.add(defaultEnabled);
            }
        }
        return engines.stream().distinct().toList();
    }

    public String normalizeEngineKey(String engineKey) {
        if (!StringUtils.hasText(engineKey)) {
            return "bing";
        }
        String normalized = engineKey.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ddg" -> "duckduckgo";
            case "msedge", "chrome", "chromium" -> "bing";
            default -> normalized;
        };
    }

    private void addIfEnabled(List<String> engines, String engineKey) {
        String normalized = normalizeEngineKey(engineKey);
        EngineConfig config = get(normalized);
        if (config != null && config.isEnabled()) {
            engines.add(normalized);
        }
    }

    @Data
    public static class EngineConfig {
        private String name;
        private String baseUrl;
        private String queryParam;
        private boolean enabled;

        public EngineConfig() {
        }

        public EngineConfig(String name, String baseUrl, String queryParam, boolean enabled) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.queryParam = queryParam;
            this.enabled = enabled;
        }

        public String getHost() {
            if (!StringUtils.hasText(baseUrl)) {
                return null;
            }
            try {
                return URI.create(baseUrl).getHost();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
