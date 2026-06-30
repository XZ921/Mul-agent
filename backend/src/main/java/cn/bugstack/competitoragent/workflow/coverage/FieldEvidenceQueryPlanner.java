package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字段证据查询规划器。
 * 它只负责把字段契约翻译成可执行查询任务，不调用 Tavily，也不判断页面是否可用。
 */
@Component
public class FieldEvidenceQueryPlanner {

    /**
     * 将单个字段契约展开成多条字段级查询任务。
     * 这里先按必填证据路径展开，再组合 query intent 和 source type，
     * 最终为同一字段路径生成多条语义互补 query，供后续 Tavily 逐条执行。
     */
    public List<FieldEvidenceQuery> plan(String competitorName,
                                         CoverageFieldContract field,
                                         List<String> preferredDomains) {
        if (field == null || !StringUtils.hasText(field.getField())) {
            return List.of();
        }
        List<CoverageEvidencePath> paths = field.getEvidencePaths() == null ? List.of() : field.getEvidencePaths();
        if (paths.isEmpty()) {
            return List.of();
        }

        Map<String, FieldEvidenceQuery> deduplicated = new LinkedHashMap<>();
        int priority = 0;
        for (CoverageEvidencePath path : paths) {
            if (path == null || !path.isRequired()) {
                continue;
            }
            List<String> queryIntents = defaultIfEmpty(path.getQueryIntents(), field.getQueryIntents());
            List<String> sourceTypes = defaultIfEmpty(path.getSourceTypes(), List.of("OPEN_WEB"));
            for (String queryIntent : queryIntents) {
                for (String sourceType : sourceTypes) {
                    for (String query : buildQueries(
                            competitorName,
                            field.getField(),
                            path.getPathKey(),
                            queryIntent,
                            preferredDomains)) {
                        FieldEvidenceQuery planned = buildQuery(
                                field.getField(),
                                path.getPathKey(),
                                queryIntent,
                                sourceType,
                                query,
                                preferredDomains,
                                priority++);
                        deduplicated.putIfAbsent(planned.getQueryFingerprint(), planned);
                    }
                }
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    /**
     * 构造单条字段 query 元数据。
     * fingerprint 使用字段、路径、意图、来源类型和 query 文本共同摘要，
     * 这样后续去重、审计和回放都能稳定识别同一条规划任务。
     */
    private FieldEvidenceQuery buildQuery(String fieldName,
                                          String pathKey,
                                          String queryIntent,
                                          String sourceType,
                                          String query,
                                          List<String> preferredDomains,
                                          int priority) {
        String fingerprintInput = fieldName + "|" + pathKey + "|" + queryIntent + "|" + sourceType + "|" + query;
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .evidencePathKey(pathKey)
                .queryIntent(queryIntent)
                .sourceType(sourceType)
                .query(query)
                .reason("字段 " + fieldName + " 的证据路径 " + pathKey + " 需要执行 " + queryIntent + " 查询")
                .queryFingerprint(DigestUtils.md5DigestAsHex(fingerprintInput.getBytes(StandardCharsets.UTF_8)))
                .priority(priority)
                .includeDomains(resolveIncludeDomains(query, preferredDomains))
                .build();
    }

    /**
     * 按字段语义和证据路径生成互补查询。
     * 06 阶段先把 coreFeatures 和 pricing 做成明确的多 query 模板，
     * 其他字段先走兜底查询，保证字段优先执行骨架已经生效。
     */
    private List<String> buildQueries(String competitorName,
                                      String fieldName,
                                      String pathKey,
                                      String queryIntent,
                                      List<String> preferredDomains) {
        String name = StringUtils.hasText(competitorName) ? competitorName.trim() : "";
        String normalizedField = normalize(fieldName);
        String normalizedPath = normalize(pathKey);
        LinkedHashSet<String> queries = new LinkedHashSet<>();

        if ("COREFEATURES".equals(normalizedField) || "DOCS_API_GUIDE".equals(normalizedPath)) {
            queries.add(name + " 开放平台 API 官方文档");
            queries.add(name + " 开放平台 SDK 接入指南");
            queries.add(name + " 开发者文档 授权管理 用户管理 API");
            for (String domain : preferredDomains == null ? List.<String>of() : preferredDomains) {
                if (StringUtils.hasText(domain)) {
                    queries.add("site:" + domain.trim() + " API SDK 文档");
                }
            }
            return new ArrayList<>(queries);
        }

        if ("PRICING".equals(normalizedField) || "OFFICIAL_PRICING_PAGE".equals(normalizedPath)) {
            queries.add(name + " 定价 套餐 收费 官方");
            queries.add(name + " 开放平台 收费 免费 计费 官方");
        }
        if ("PRICING".equals(normalizedField) || "DOCS_BILLING_OR_LIMITS".equals(normalizedPath)) {
            queries.add(name + " API 调用限制 计费 免费 文档");
            queries.add(name + " 服务协议 计费 收费 条款");
            for (String domain : preferredDomains == null ? List.<String>of() : preferredDomains) {
                if (StringUtils.hasText(domain)) {
                    queries.add("site:" + domain.trim() + " billing pricing docs API");
                }
            }
        }
        if (!queries.isEmpty()) {
            return new ArrayList<>(queries);
        }

        queries.add(name + " " + fieldName + " " + queryIntent + " 官方资料");
        return new ArrayList<>(queries);
    }

    /**
     * 只有 query 自身包含 site: 限定时，才把偏好域名透传下去。
     * 这样可以避免普通开放网络 query 被误标成强域名约束任务。
     */
    private List<String> resolveIncludeDomains(String query, List<String> preferredDomains) {
        if (!StringUtils.hasText(query) || preferredDomains == null || preferredDomains.isEmpty()) {
            return List.of();
        }
        if (!query.contains("site:")) {
            return List.of();
        }
        return preferredDomains.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 统一规整空列表和空白字符串，避免 planner 因为脏输入生成无意义 query。
     */
    private List<String> defaultIfEmpty(List<String> values, List<String> fallback) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        return normalized.isEmpty() ? normalizeFallback(fallback) : normalized;
    }

    private List<String> normalizeFallback(List<String> fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return List.of();
        }
        return fallback.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}