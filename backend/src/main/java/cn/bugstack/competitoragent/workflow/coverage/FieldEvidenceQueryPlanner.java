package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字段证据查询规划器。
 * 它只负责把字段契约翻译成可执行查询任务，不调用 Tavily，也不判断页面是否可用。
 */
@Component
public class FieldEvidenceQueryPlanner {

    private static final int MAX_OPTIONAL_THIRD_PARTY_VARIANTS = 2;

    private final FieldQueryComposition fieldQueryComposition;
    private final IncludeDomainPlanner includeDomainPlanner;

    public FieldEvidenceQueryPlanner() {
        this(new FieldQueryComposition(), new IncludeDomainPlanner());
    }

    public FieldEvidenceQueryPlanner(FieldQueryComposition fieldQueryComposition,
                                     IncludeDomainPlanner includeDomainPlanner) {
        this.fieldQueryComposition = fieldQueryComposition == null ? new FieldQueryComposition() : fieldQueryComposition;
        this.includeDomainPlanner = includeDomainPlanner == null ? new IncludeDomainPlanner() : includeDomainPlanner;
    }

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
            if (path == null) {
                continue;
            }
            List<FieldQueryComposition.QueryVariant> variants = fieldQueryComposition.compose(
                    competitorName,
                    field.getField(),
                    path,
                    field.getQueryIntents(),
                    List.of("OPEN_WEB"));
            if (!path.isRequired()) {
                /**
                 * 非 required 路径不能再一刀切跳过，否则 PUBLIC_REVIEW_OR_NEWS 这类第三方证据路径永远发不出去。
                 * 这里仅放行“第三方/开放网络”语义的可选路径，并限制每条路径最多取前 2 个 query variant，
                 * 这样既能保证第三方材料进入执行队列，又不会把所有 optional path 无上限放大。
                 */
                if (!isBestEffortThirdPartyPath(path)) {
                    continue;
                }
                variants = variants.stream()
                        .limit(MAX_OPTIONAL_THIRD_PARTY_VARIANTS)
                        .toList();
            }
            for (FieldQueryComposition.QueryVariant variant : variants) {
                FieldEvidenceQuery planned = buildQuery(
                        competitorName,
                        field.getField(),
                        path.getPathKey(),
                        variant,
                        preferredDomains,
                        priority++);
                deduplicated.putIfAbsent(planned.getQueryFingerprint(), planned);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    /**
     * 只有第三方/开放网络语义的 optional 路径才允许以 best-effort 方式进入 query 规划。
     * 这样可以精准覆盖 PUBLIC_REVIEW_OR_NEWS，而不会误把任意非必填官方路径都拉进待执行集合。
     */
    private boolean isBestEffortThirdPartyPath(CoverageEvidencePath path) {
        if (containsThirdPartyToken(path.getPathKey())) {
            return true;
        }
        if (path.getSourceTypes() == null || path.getSourceTypes().isEmpty()) {
            return false;
        }
        return path.getSourceTypes().stream().anyMatch(this::containsThirdPartyToken);
    }

    private boolean containsThirdPartyToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("REVIEW")
                || normalized.contains("NEWS")
                || normalized.contains("OPEN_WEB")
                || normalized.contains("THIRD_PARTY");
    }

    /**
     * 构造单条字段 query 元数据，并把 includeDomains 决策交给独立规划器。
     * 官方域名在这里仅作为优先命中锚点；第三方视角会返回空域名约束，允许全网材料进入。
     */
    private FieldEvidenceQuery buildQuery(String competitorName,
                                          String fieldName,
                                          String pathKey,
                                          FieldQueryComposition.QueryVariant variant,
                                          List<String> preferredDomains,
                                          int priority) {
        String queryIntent = variant.getQueryIntent();
        String sourceType = variant.getSourceType();
        String query = variant.getQuery();
        String fingerprintInput = fieldName + "|" + pathKey + "|" + queryIntent + "|" + sourceType + "|" + query;
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .evidencePathKey(pathKey)
                .queryIntent(queryIntent)
                .sourceType(sourceType)
                .query(query)
                .reason(variant.getReason())
                .queryFingerprint(DigestUtils.md5DigestAsHex(fingerprintInput.getBytes(StandardCharsets.UTF_8)))
                .priority(priority)
                .includeDomains(includeDomainPlanner.planIncludeDomains(
                        competitorName,
                        preferredDomains,
                        List.of(sourceType),
                        pathKey))
                .build();
    }
}
