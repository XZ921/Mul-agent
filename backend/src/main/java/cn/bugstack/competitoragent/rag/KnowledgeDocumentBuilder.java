package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.dto.KnowledgeIngestionRequest;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 把 `EvidenceSource` 标准化为任务级 `KnowledgeDocument`。
 * <p>
 * 这个步骤只负责“证据 -> 文档”的对象边界收口：
 * 1. 清洗正文文本；
 * 2. 明确来源分类；
 * 3. 固化 `sourceUrls` 与问题标记；
 * 不在这里引入召回、重排或上下文装配逻辑。
 */
@Component
public class KnowledgeDocumentBuilder {

    public KnowledgeDocument build(EvidenceSource evidence, int documentVersion) {
        String cleanedText = normalizeText(firstNonBlank(evidence.getFullContent(), evidence.getContentSnippet()));
        List<String> sourceUrls = buildSourceUrls(evidence);
        String sourceCategory = resolveSourceCategory(evidence);
        List<String> issueFlags = buildIssueFlags(cleanedText, sourceUrls);
        return KnowledgeDocument.builder()
                .taskId(evidence.getTaskId())
                .competitorName(evidence.getCompetitorName())
                .evidenceId(evidence.getEvidenceId())
                .documentKey(buildDocumentKey(evidence))
                .knowledgeScope("TASK")
                .knowledgeDomainId(null)
                .knowledgeDomainKey(null)
                .sourceType(defaultText(evidence.getSourceType(), "UNKNOWN"))
                .sourceCategory(sourceCategory)
                .discoveryMethod(evidence.getDiscoveryMethod())
                .sourceDomain(evidence.getSourceDomain())
                .sourceLifecycle("ACTIVE")
                .trustLevel(resolveTrustLevel(sourceCategory))
                .connectorKey(null)
                .title(firstNonBlank(evidence.getTitle(), evidence.getUrl()))
                .url(evidence.getUrl())
                .snippet(buildSnippet(evidence, cleanedText))
                .cleanedText(cleanedText)
                .sourceUrls(sourceUrls)
                .issueFlags(issueFlags)
                .documentVersion(documentVersion)
                .status("PROCESSING")
                .failureReason(null)
                .collectedAt(evidence.getCollectedAt())
                .build();
    }

    /**
     * 组织级资料接入与任务级 EvidenceSource 走同一个 KnowledgeDocument 体系，
     * 但这里会显式补齐知识域、生命周期和可信度字段，避免后续治理链路再去猜资料边界。
     */
    public KnowledgeDocument buildOrganizationDocument(KnowledgeIngestionRequest request,
                                                       KnowledgeDomain domain,
                                                       int documentVersion) {
        List<String> sourceUrls = buildSourceUrls(request);
        String cleanedText = normalizeText(firstNonBlank(
                request.getContentText(),
                request.getContentSnippet(),
                request.getSummary(),
                request.getTitle()));
        String sourceCategory = defaultText(request.getSourceCategory(), "AI_DISCOVERED");
        String url = firstNonBlank(request.getUrl(), sourceUrls.isEmpty() ? null : sourceUrls.get(0));
        List<String> issueFlags = buildIssueFlags(cleanedText, sourceUrls);

        return KnowledgeDocument.builder()
                .taskId(request.getTaskId())
                .competitorName(firstNonBlank(request.getCompetitorName(), domain.getDomainName()))
                .evidenceId(buildOrganizationEvidenceId(request, domain))
                .documentKey(buildOrganizationDocumentKey(request, domain))
                .knowledgeScope("ORGANIZATION")
                .knowledgeDomainId(domain.getId())
                .knowledgeDomainKey(domain.getDomainKey())
                .sourceType(resolveOrganizationSourceType(request))
                .sourceCategory(sourceCategory)
                .discoveryMethod(resolveOrganizationDiscoveryMethod(request))
                .sourceDomain(firstNonBlank(request.getSourceDomain(), extractDomain(url)))
                .sourceLifecycle(firstNonBlank(request.getRequestedLifecycle(), domain.getDefaultLifecycle(), "ACTIVE"))
                .trustLevel(resolveOrganizationTrustLevel(request, domain, sourceCategory))
                .connectorKey(request.getConnectorKey())
                .title(firstNonBlank(request.getTitle(), url, domain.getDomainName()))
                .url(firstNonBlank(url, "https://knowledge.local/placeholder"))
                .snippet(buildSnippet(request.getContentSnippet(), cleanedText, request.getSummary()))
                .cleanedText(cleanedText)
                .sourceUrls(sourceUrls)
                .issueFlags(issueFlags)
                .documentVersion(documentVersion)
                .status("PROCESSING")
                .failureReason(null)
                .collectedAt(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 文档主键采用“任务 + 证据”组合，保证同一条证据重跑时可以幂等更新而不是无限新增。
     */
    private String buildDocumentKey(EvidenceSource evidence) {
        return "TASK-" + (evidence.getTaskId() == null ? 0L : evidence.getTaskId())
                + "-" + sanitizeKey(evidence.getEvidenceId());
    }

    private List<String> buildSourceUrls(EvidenceSource evidence) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (StringUtils.hasText(evidence.getUrl())) {
            sourceUrls.add(evidence.getUrl().trim());
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> buildSourceUrls(KnowledgeIngestionRequest request) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (StringUtils.hasText(request.getUrl())) {
            sourceUrls.add(request.getUrl().trim());
        }
        if (request.getSourceUrls() != null) {
            for (String sourceUrl : request.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    sourceUrls.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> buildIssueFlags(String cleanedText, List<String> sourceUrls) {
        List<String> issueFlags = new ArrayList<>();
        if (!StringUtils.hasText(cleanedText)) {
            issueFlags.add("CONTENT_GAP");
        }
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            issueFlags.add("MISSING_SOURCE_URL");
        }
        return issueFlags;
    }

    /**
     * 来源分类是后续任务级 RAG 审计的重要维度，需要在落知识底座时就被显式保留。
     */
    private String resolveSourceCategory(EvidenceSource evidence) {
        if (StringUtils.hasText(evidence.getSourceCategory())) {
            return evidence.getSourceCategory().trim();
        }
        String discoveryMethod = defaultText(evidence.getDiscoveryMethod(), "");
        String normalizedMethod = discoveryMethod.toUpperCase(Locale.ROOT);
        if (normalizedMethod.contains("UPLOAD")) {
            return "UPLOADED_DOCUMENTS";
        }
        if (normalizedMethod.contains("AUTH")
                || normalizedMethod.contains("API")
                || normalizedMethod.contains("CONNECTOR")) {
            return "AUTHENTICATED_SOURCES";
        }
        if (normalizedMethod.contains("CONFIG")
                || normalizedMethod.contains("MANUAL")
                || normalizedMethod.contains("USER")) {
            return "USER_PROVIDED";
        }
        return "AI_DISCOVERED";
    }

    /**
     * 这里显式做一次正文归一化，避免 RAG 后续直接消费原始抓取噪音。
     */
    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String buildSnippet(EvidenceSource evidence, String cleanedText) {
        String seed = firstNonBlank(evidence.getContentSnippet(), cleanedText);
        if (!StringUtils.hasText(seed)) {
            return null;
        }
        return seed.length() <= 240 ? seed : seed.substring(0, 240);
    }

    private String buildSnippet(String contentSnippet, String cleanedText, String summary) {
        String seed = firstNonBlank(contentSnippet, summary, cleanedText);
        if (!StringUtils.hasText(seed)) {
            return null;
        }
        return seed.length() <= 240 ? seed : seed.substring(0, 240);
    }

    /**
     * 组织级资料没有天然的 task evidenceId，
     * 因此这里生成稳定的接入引用编号，后续 5.2.c 再把它与原始资料和证据消费链路继续打通。
     */
    private String buildOrganizationEvidenceId(KnowledgeIngestionRequest request, KnowledgeDomain domain) {
        return "ORG-" + sanitizeKey(domain.getDomainKey())
                + "-" + sanitizeKey(firstNonBlank(request.getConnectorKey(), request.getTitle(), request.getUrl(), "INTAKE"));
    }

    private String buildOrganizationDocumentKey(KnowledgeIngestionRequest request, KnowledgeDomain domain) {
        return "ORG-" + sanitizeKey(domain.getDomainKey())
                + "-" + sanitizeKey(defaultText(request.getSourceCategory(), "AI_DISCOVERED"))
                + "-" + sanitizeKey(firstNonBlank(request.getConnectorKey(), request.getTitle(), request.getUrl(), "DOCUMENT"));
    }

    private String resolveOrganizationSourceType(KnowledgeIngestionRequest request) {
        if (StringUtils.hasText(request.getSourceType())) {
            return request.getSourceType().trim();
        }
        return switch (defaultText(request.getSourceCategory(), "AI_DISCOVERED")) {
            case "UPLOADED_DOCUMENTS" -> "UPLOAD";
            case "AUTHENTICATED_SOURCES" -> "CONNECTOR";
            case "USER_PROVIDED" -> "MANUAL";
            default -> "WEB";
        };
    }

    private String resolveOrganizationDiscoveryMethod(KnowledgeIngestionRequest request) {
        if (StringUtils.hasText(request.getDiscoveryMethod())) {
            return request.getDiscoveryMethod().trim();
        }
        return switch (defaultText(request.getSourceCategory(), "AI_DISCOVERED")) {
            case "UPLOADED_DOCUMENTS" -> "UPLOAD";
            case "AUTHENTICATED_SOURCES" -> "CONNECTOR";
            case "USER_PROVIDED" -> "MANUAL";
            default -> "AI_DISCOVERY";
        };
    }

    /**
     * 可信度优先级刻意设计为：
     * 1. 用户或上游显式指定；
     * 2. 资料来源自身的固有可信度；
     * 3. 知识域默认可信度。
     * 这样可以避免 AI 发现资料因为落到某个“高可信知识域”里就被误标成已人工确认。
     */
    private String resolveOrganizationTrustLevel(KnowledgeIngestionRequest request,
                                                 KnowledgeDomain domain,
                                                 String sourceCategory) {
        return firstNonBlank(
                request.getRequestedTrustLevel(),
                resolveTrustLevel(sourceCategory),
                domain.getDefaultTrustLevel(),
                "CURATED"
        );
    }

    private String sanitizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "UNKNOWN";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
    }

    private String extractDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return StringUtils.hasText(uri.getHost()) ? uri.getHost() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTrustLevel(String sourceCategory) {
        return switch (defaultText(sourceCategory, "AI_DISCOVERED")) {
            case "AUTHENTICATED_SOURCES" -> "CONNECTED_SOURCE";
            case "UPLOADED_DOCUMENTS", "USER_PROVIDED" -> "USER_CONFIRMED";
            default -> "DISCOVERED";
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
