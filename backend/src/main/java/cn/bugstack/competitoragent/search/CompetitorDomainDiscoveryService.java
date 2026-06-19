package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 基于 LLM 的竞品官方入口发现服务。
 * <p>
 * 这一层只负责“从公司名发现可验证的官方入口候选”，再交给后续链路做 scope 合并、排序和采集。
 */
@Component
public class CompetitorDomainDiscoveryService {

    private static final String SYSTEM_PROMPT =
            "你是一个竞品分析助手。给定公司名称，返回其官方网站、文档站、开放平台、GitHub 仓库的 URL。只返回 JSON，不要解释。";
    private static final String USER_PROMPT_TEMPLATE = "公司: %s";
    private static final String DEFAULT_SOURCE_PREFIX = "llm://domain-discovery/";
    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "required": ["urls", "sourceUrls"],
              "properties": {
                "urls": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["url", "category", "confidence"],
                    "properties": {
                      "url": { "type": "string" },
                      "category": { "type": "string", "enum": ["official", "docs", "open", "github", "pricing", "news", "other"] },
                      "confidence": { "type": "number" },
                      "reason": { "type": "string" },
                      "sourceUrls": {
                        "type": "array",
                        "items": { "type": "string" }
                      }
                    }
                  }
                },
                "sourceUrls": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              }
            }
            """;

    private final DomainDiscoveryProperties properties;
    private final LlmClient llmClient;
    private final DomainVerificationClient domainVerificationClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public CompetitorDomainDiscoveryService(DomainDiscoveryProperties properties,
                                            LlmClient llmClient,
                                            DomainVerificationClient domainVerificationClient,
                                            ObjectMapper objectMapper) {
        this.properties = properties == null ? new DomainDiscoveryProperties() : properties;
        this.llmClient = llmClient;
        this.domainVerificationClient = domainVerificationClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    /**
     * 只在域名发现能力明确开启时工作；任意外部调用异常都降级为空列表，避免阻断主采集链路。
     */
    public List<SourceCandidate> discover(String competitorName) {
        if (!properties.isLlmEnabled() || llmClient == null || !StringUtils.hasText(competitorName)) {
            return List.of();
        }
        try {
            String json = llmClient.chatForJson(
                    SYSTEM_PROMPT,
                    USER_PROMPT_TEMPLATE.formatted(competitorName.trim()),
                    RESPONSE_SCHEMA
            );
            return parseCandidates(competitorName.trim(), json);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回值，并把缺失的 sourceUrls 回填成可追溯的 llm:// 协议。
     */
    private List<SourceCandidate> parseCandidates(String competitorName, String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode urlsNode = root.path("urls");
            if (!urlsNode.isArray()) {
                return List.of();
            }

            List<String> rootSourceUrls = normalizeSourceUrls(root.path("sourceUrls"), defaultSourceUrls(competitorName));
            LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
            List<SourceCandidate> candidates = new ArrayList<>();
            int maxCandidates = Math.max(0, properties.getMaxLlmCandidates());

            for (JsonNode item : urlsNode) {
                if (maxCandidates > 0 && candidates.size() >= maxCandidates) {
                    break;
                }
                String rawUrl = text(item, "url");
                String normalizedUrl = normalizeUrl(rawUrl);
                if (!StringUtils.hasText(normalizedUrl) || !seenUrls.add(normalizedUrl)) {
                    continue;
                }
                if (domainVerificationClient != null && !domainVerificationClient.isReachable(normalizedUrl)) {
                    continue;
                }

                boolean payloadMissedSourceUrls = item.path("sourceUrls").isMissingNode()
                        || item.path("sourceUrls").isNull()
                        || !item.path("sourceUrls").isArray()
                        || item.path("sourceUrls").isEmpty();
                List<String> candidateSourceUrls = normalizeSourceUrls(item.path("sourceUrls"), rootSourceUrls);
                boolean synthesizedSourceUrls = payloadMissedSourceUrls;
                if (synthesizedSourceUrls) {
                    candidateSourceUrls = defaultSourceUrls(competitorName);
                }

                SourceCandidate candidate = SourceCandidate.builder()
                        .url(normalizedUrl)
                        .title(buildTitle(competitorName, normalizedUrl, text(item, "category")))
                        .sourceType(mapCategoryToSourceType(text(item, "category")))
                        .discoveryMethod("DOMAIN_DISCOVERY_LLM")
                        .reason(firstNonBlank(text(item, "reason"), "LLM domain discovery"))
                        .domain(extractDomain(normalizedUrl))
                        .sourceUrls(candidateSourceUrls)
                        .relevanceScore(resolveConfidence(item.path("confidence")))
                        .freshnessScore(0.60D)
                        .qualityScore(synthesizedSourceUrls ? 0.86D : 0.90D)
                        .qualitySignals(buildQualitySignals(synthesizedSourceUrls))
                        .build();
                candidates.add(candidate);
            }
            appendDeterministicDocPathSupplements(competitorName, rootSourceUrls, seenUrls, candidates, maxCandidates);
            return candidates;
        } catch (RuntimeException exception) {
            return List.of();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * LLM 对开放平台文档入口存在波动：有时只返回 open 根页、历史 wiki，或 openhome/developer/docs 等同根域入口。
     * 这里只生成少量通用候选，并且每个候选必须通过可达性验证，避免把某个产品的固定 URL 写进代码。
     */
    private void appendDeterministicDocPathSupplements(String competitorName,
                                                       List<String> rootSourceUrls,
                                                       LinkedHashSet<String> seenUrls,
                                                       List<SourceCandidate> candidates,
                                                       int maxCandidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        List<SourceCandidate> snapshot = new ArrayList<>(candidates);
        for (SourceCandidate candidate : snapshot) {
            for (DocSupplement supplement : buildDocSupplementUrls(candidate)) {
                if (maxCandidates > 0 && candidates.size() >= maxCandidates) {
                    return;
                }
                if (!StringUtils.hasText(supplement.url()) || !seenUrls.add(supplement.url())) {
                    continue;
                }
                if (domainVerificationClient != null && !domainVerificationClient.isReachable(supplement.url())) {
                    continue;
                }
                candidates.add(buildSupplementCandidate(
                        competitorName,
                        supplement.url(),
                        sourceUrlsOrFallback(candidate, rootSourceUrls, competitorName),
                        Math.max(0.86D, candidate.getRelevanceScore()),
                        Math.max(0.60D, candidate.getFreshnessScore()),
                        Math.max(0.90D, candidate.getQualityScore()),
                        List.of("LLM_DOMAIN_DISCOVERY", "DETERMINISTIC_DOC_PATH_SUPPLEMENT"),
                        supplement.reason()
                ));
            }
        }
    }

    private List<DocSupplement> buildDocSupplementUrls(SourceCandidate candidate) {
        if (candidate == null
                || !"DOCS".equalsIgnoreCase(candidate.getSourceType())
                || !StringUtils.hasText(candidate.getUrl())) {
            return List.of();
        }
        try {
            URI uri = URI.create(candidate.getUrl());
            String host = normalizeHost(uri.getHost());
            if (!StringUtils.hasText(host)) {
                return List.of();
            }
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme() : "https";
            String rootDomain = extractRootDomain(host);
            LinkedHashSet<DocSupplement> supplements = new LinkedHashSet<>();

            if (host.startsWith("open.") && !path.equals("/doc") && !path.startsWith("/doc/")) {
                supplements.add(new DocSupplement(
                        scheme + "://" + host + "/doc",
                        "deterministic /doc supplement from verified open platform domain"
                ));
            }

            if (isOpenPlatformLikeHost(host) && StringUtils.hasText(rootDomain)) {
                // 常见开放平台入口会在 openhome/open/developer/docs 子域之间切换。
                // 这里只尝试同根域的高置信路径，再交给可达性验证过滤误报。
                supplements.add(new DocSupplement(
                        scheme + "://open." + rootDomain + "/doc",
                        "deterministic /doc supplement from sibling open platform domain"
                ));
                supplements.add(new DocSupplement(
                        scheme + "://open." + rootDomain + "/docs",
                        "deterministic /docs supplement from sibling open platform domain"
                ));
                supplements.add(new DocSupplement(
                        scheme + "://developer." + rootDomain + "/docs",
                        "deterministic developer docs supplement from sibling open platform domain"
                ));
                supplements.add(new DocSupplement(
                        scheme + "://docs." + rootDomain,
                        "deterministic docs subdomain supplement from sibling open platform domain"
                ));
            }

            return new ArrayList<>(supplements);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private SourceCandidate buildSupplementCandidate(String competitorName,
                                                     String supplementUrl,
                                                     List<String> sourceUrls,
                                                     double relevanceScore,
                                                     double freshnessScore,
                                                     double qualityScore,
                                                     List<String> qualitySignals,
                                                     String reason) {
        return SourceCandidate.builder()
                .url(supplementUrl)
                .title(buildTitle(competitorName, supplementUrl, "docs"))
                .sourceType("DOCS")
                .discoveryMethod("DOMAIN_DISCOVERY_LLM_SUPPLEMENT")
                .reason(reason)
                .domain(extractDomain(supplementUrl))
                .sourceUrls(sourceUrls)
                .relevanceScore(relevanceScore)
                .freshnessScore(freshnessScore)
                .qualityScore(qualityScore)
                .qualitySignals(qualitySignals)
                .build();
    }

    private List<String> sourceUrlsOrFallback(SourceCandidate candidate, List<String> rootSourceUrls, String competitorName) {
        if (candidate != null && candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()) {
            return candidate.getSourceUrls();
        }
        return rootSourceUrls == null || rootSourceUrls.isEmpty()
                ? defaultSourceUrls(competitorName)
                : rootSourceUrls;
    }

    private boolean isOpenPlatformLikeHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        return host.startsWith("open.")
                || host.startsWith("openhome.")
                || host.startsWith("open-home.")
                || host.startsWith("developer.")
                || host.startsWith("developers.")
                || host.startsWith("docs.");
    }

    private String normalizeHost(String host) {
        return StringUtils.hasText(host) ? host.toLowerCase(Locale.ROOT) : "";
    }

    private String extractRootDomain(String host) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /**
     * 把 LLM 类别映射成后续链路统一识别的 sourceType。
     */
    private String mapCategoryToSourceType(String category) {
        if (!StringUtils.hasText(category)) {
            return "OFFICIAL";
        }
        return switch (category.trim().toLowerCase(Locale.ROOT)) {
            case "docs", "open" -> "DOCS";
            case "github" -> "GITHUB";
            case "pricing" -> "PRICING";
            case "news" -> "NEWS";
            default -> "OFFICIAL";
        };
    }

    private List<String> buildQualitySignals(boolean synthesizedSourceUrls) {
        List<String> signals = new ArrayList<>();
        signals.add("LLM_DOMAIN_DISCOVERY");
        if (synthesizedSourceUrls) {
            signals.add("SOURCE_URLS_SYNTHESIZED");
        }
        return signals;
    }

    private List<String> normalizeSourceUrls(JsonNode node, List<String> fallback) {
        if (node == null || !node.isArray()) {
            return fallback == null ? List.of() : fallback;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null) {
                continue;
            }
            String value = item.asText();
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            return fallback == null ? List.of() : fallback;
        }
        return new ArrayList<>(values);
    }

    private List<String> defaultSourceUrls(String competitorName) {
        if (!StringUtils.hasText(competitorName)) {
            return List.of(DEFAULT_SOURCE_PREFIX + "unknown");
        }
        return List.of(DEFAULT_SOURCE_PREFIX + competitorName.trim());
    }

    private String buildTitle(String competitorName, String url, String category) {
        String label = StringUtils.hasText(category) ? category.trim() : "official";
        return (StringUtils.hasText(competitorName) ? competitorName.trim() : "competitor") + " - " + label + " - " + url;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception exception) {
            return null;
        }
    }

    private String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (StringUtils.hasText(uri.getScheme()) && StringUtils.hasText(uri.getHost())) {
                return uri.toString();
            }
            if (!StringUtils.hasText(uri.getHost()) && StringUtils.hasText(uri.getPath()) && url.contains(".")) {
                return "https://" + url.trim();
            }
        } catch (Exception ignore) {
            // 兜底到简单补全，避免 LLM 给出无 scheme 地址时直接丢失候选。
        }
        String normalized = url.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        return normalized;
    }

    private double resolveConfidence(JsonNode confidenceNode) {
        if (confidenceNode == null || confidenceNode.isMissingNode() || confidenceNode.isNull()) {
            return 0.80D;
        }
        double value = confidenceNode.asDouble(0.80D);
        return value <= 0D ? 0.80D : value;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }
        JsonNode valueNode = node.path(fieldName);
        return valueNode.isMissingNode() || valueNode.isNull() ? null : valueNode.asText(null);
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    private record DocSupplement(String url, String reason) {
    }
}
