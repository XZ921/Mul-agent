package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceViewAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 基于 repository 的 extractor 输入提供者第一版实现。
 * P1 Task A 先把“读库 + 过滤 + 组装运行态输入包”的职责从 Agent 内部拔出来，
 * 后续预算控制、TopK、跳过原因等策略再继续在 Provider 层增强。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryExtractorInputProvider implements ExtractorInputProvider {

    private static final int DEFAULT_MAX_PROMPT_EVIDENCE_CHARS = 4000;
    private static final int THIN_CONTENT_THRESHOLD = 40;
    private static final List<String> READABLE_EXCLUDED_FLAGS = List.of(
            "CONTENT_GAP",
            "COLLECT_FAILED",
            "NO_USABLE_CONTENT"
    );

    private final EvidenceSourceRepository evidenceRepository;
    private final DownstreamEvidenceViewAssembler downstreamEvidenceViewAssembler;
    private final ObjectMapper objectMapper;

    @Override
    public ExtractorInputPackage provide(AgentContext context) {
        List<EvidenceSource> allEvidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        List<EvidenceSource> usableEvidences = new ArrayList<>();
        List<EvidenceSource> skippedEvidences = new ArrayList<>();
        for (EvidenceSource evidence : allEvidences == null ? List.<EvidenceSource>of() : allEvidences) {
            if (isUsableEvidence(evidence)) {
                usableEvidences.add(evidence);
            } else {
                skippedEvidences.add(evidence);
            }
        }

        Map<String, List<EvidenceSource>> usableByCompetitor = groupByCompetitor(usableEvidences);
        Map<String, List<EvidenceSource>> skippedByCompetitor = groupByCompetitor(skippedEvidences);
        LinkedHashSet<String> competitorNames = new LinkedHashSet<>();
        competitorNames.addAll(usableByCompetitor.keySet());
        competitorNames.addAll(skippedByCompetitor.keySet());

        SchemaRuntimeConfig schemaRuntimeConfig = readSchemaRuntimeConfig(context == null ? null : context.getCurrentNodeConfig());
        List<ExtractorCompetitorInput> competitors = new ArrayList<>();
        for (String competitorName : competitorNames) {
            List<DownstreamEvidenceView> normalizedUsableViews = normalizeViews(
                    downstreamEvidenceViewAssembler.fromEvidenceSources(usableByCompetitor.getOrDefault(competitorName, List.of())));
            List<DownstreamEvidenceView> enrichedUsableViews = enrichEvidenceViews(normalizedUsableViews);
            List<DownstreamEvidenceView> skippedViews = normalizeViews(
                    downstreamEvidenceViewAssembler.fromEvidenceSources(skippedByCompetitor.getOrDefault(competitorName, List.of())));
            PromptSelection selection = selectPromptEvidence(enrichedUsableViews);
            List<DownstreamEvidenceView> evidenceCatalog = selection.selectedEvidence();
            List<DownstreamEvidenceView> traceableSkippedViews = new ArrayList<>(selection.skippedEvidence());
            traceableSkippedViews.addAll(skippedViews);
            competitors.add(ExtractorCompetitorInput.builder()
                    .competitorName(competitorName)
                    .evidenceCatalog(evidenceCatalog)
                    .structuredEvidence(filterStructuredEvidence(evidenceCatalog))
                    .readableEvidence(filterReadableEvidence(evidenceCatalog))
                    .skippedEvidence(traceableSkippedViews)
                    .sourceUrls(collectSourceUrls(evidenceCatalog))
                    .issueFlags(collectIssueFlags(evidenceCatalog, traceableSkippedViews))
                    .budget(buildBudget(selection.usedPromptEvidenceChars(), selection.truncated()))
                    .build());
        }

        return ExtractorInputPackage.builder()
                .taskId(context == null ? null : context.getTaskId())
                .nodeName(firstNonBlank(context == null ? null : context.getCurrentNodeName(), "extract_schema"))
                .planVersionId(context == null ? null : context.getPlanVersionId())
                .branchKey(context == null ? null : context.getBranchKey())
                .schemaId(schemaRuntimeConfig.schemaId())
                .dimensions(schemaRuntimeConfig.dimensions())
                .competitors(competitors)
                .build();
    }

    private Map<String, List<EvidenceSource>> groupByCompetitor(List<EvidenceSource> evidences) {
        Map<String, List<EvidenceSource>> grouped = new LinkedHashMap<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            grouped.computeIfAbsent(firstNonBlank(evidence.getCompetitorName(), "UNKNOWN"), key -> new ArrayList<>())
                    .add(evidence);
        }
        return grouped;
    }

    /**
     * P1 Task A 先把 extractor 入口的可用性判断收口到 Provider，
     * 让 Agent 后续可以完全基于输入包解释“哪些证据进入了本轮抽取”。
     */
    private boolean isUsableEvidence(EvidenceSource evidence) {
        if (evidence == null) {
            return false;
        }
        boolean hasContent = hasText(evidence.getFullContent());
        boolean hasSnippet = hasText(evidence.getContentSnippet());
        boolean hasStructuredEvidence = hasStructuredEvidence(evidence);
        return hasContent || hasSnippet || hasStructuredEvidence;
    }

    private boolean hasStructuredEvidence(EvidenceSource evidence) {
        if (evidence == null || !hasText(evidence.getPageMetadata())) {
            return false;
        }
        try {
            JsonNode metadata = objectMapper.readTree(evidence.getPageMetadata());
            JsonNode structuredBlocks = metadata.path("structuredBlocks");
            JsonNode structuredPayload = metadata.path("structuredPayload");
            return (structuredBlocks.isArray() && !structuredBlocks.isEmpty())
                    || (structuredPayload.isObject() && !structuredPayload.isEmpty())
                    || (structuredPayload.isArray() && !structuredPayload.isEmpty());
        } catch (Exception e) {
            log.warn("provider failed to parse pageMetadata for structured evidence, evidenceId={}",
                    evidence.getEvidenceId(), e);
            return false;
        }
    }

    private List<DownstreamEvidenceView> filterStructuredEvidence(List<DownstreamEvidenceView> evidences) {
        List<DownstreamEvidenceView> structured = new ArrayList<>();
        for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
            if (evidence != null && evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
                structured.add(evidence);
            }
        }
        return structured;
    }

    private List<DownstreamEvidenceView> filterReadableEvidence(List<DownstreamEvidenceView> evidences) {
        List<DownstreamEvidenceView> readable = new ArrayList<>();
        for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
            if (evidence == null || !hasText(evidence.getContent())) {
                continue;
            }
            if (containsAnyIssueFlag(evidence, READABLE_EXCLUDED_FLAGS)) {
                continue;
            }
            if (containsIssueFlag(evidence, "THIN_CONTENT_ONLY")
                    && (evidence.getStructuredBlocks() == null || evidence.getStructuredBlocks().isEmpty())) {
                continue;
            }
            readable.add(evidence);
        }
        return readable;
    }

    /**
     * Provider 侧先把薄正文和显式缺口标记补齐，避免 Agent 再从 prompt 文本里反推输入质量。
     */
    private List<DownstreamEvidenceView> enrichEvidenceViews(List<DownstreamEvidenceView> evidences) {
        List<DownstreamEvidenceView> enriched = new ArrayList<>();
        for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            LinkedHashSet<String> issueFlags = new LinkedHashSet<>(evidence.getIssueFlags() == null ? List.of() : evidence.getIssueFlags());
            boolean hasStructuredBlocks = evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty();
            String content = evidence.getContent() == null ? "" : evidence.getContent().trim();
            if (!hasStructuredBlocks && hasText(content) && content.length() < THIN_CONTENT_THRESHOLD) {
                issueFlags.add("THIN_CONTENT_ONLY");
            }
            enriched.add(evidence.toBuilder()
                    .issueFlags(new ArrayList<>(issueFlags))
                    .build()
                    .normalized());
        }
        return enriched;
    }

    /**
     * P1 Task B 要求在 Provider 层就收口“什么证据真正进入 Prompt”。
     * 这里先按结构块、来源类型、质量分排序，再在总预算内截取，超预算证据转成可追溯的轻量 skipped 视图。
     */
    private PromptSelection selectPromptEvidence(List<DownstreamEvidenceView> evidences) {
        List<DownstreamEvidenceView> sorted = new ArrayList<>(evidences == null ? List.of() : evidences);
        sorted.sort(Comparator
                .comparing(this::structuredPriority)
                .thenComparing(this::sourceTypePriority)
                .thenComparing(this::qualityScorePriority)
                .thenComparing(view -> firstNonBlank(view == null ? null : view.getEvidenceId(), "ZZZ")));
        List<DownstreamEvidenceView> selected = new ArrayList<>();
        List<DownstreamEvidenceView> skipped = new ArrayList<>();
        int usedPromptEvidenceChars = 0;
        boolean truncated = false;
        for (DownstreamEvidenceView evidence : sorted) {
            if (evidence == null) {
                continue;
            }
            int evidenceChars = promptEvidenceChars(evidence);
            if (usedPromptEvidenceChars >= DEFAULT_MAX_PROMPT_EVIDENCE_CHARS) {
                skipped.add(buildTraceOnlySkippedView(evidence, "PROMPT_BUDGET_SKIPPED"));
                truncated = true;
                continue;
            }
            int remainingChars = DEFAULT_MAX_PROMPT_EVIDENCE_CHARS - usedPromptEvidenceChars;
            if (evidenceChars > remainingChars) {
                selected.add(truncateEvidenceForPrompt(evidence, remainingChars));
                usedPromptEvidenceChars = DEFAULT_MAX_PROMPT_EVIDENCE_CHARS;
                truncated = true;
                continue;
            }
            selected.add(evidence);
            usedPromptEvidenceChars += evidenceChars;
        }
        return new PromptSelection(selected, skipped, usedPromptEvidenceChars, truncated);
    }

    private int structuredPriority(DownstreamEvidenceView evidence) {
        boolean hasStructuredBlocks = evidence != null
                && evidence.getStructuredBlocks() != null
                && !evidence.getStructuredBlocks().isEmpty();
        return hasStructuredBlocks ? 0 : 1;
    }

    private int sourceTypePriority(DownstreamEvidenceView evidence) {
        String sourceType = firstNonBlank(evidence == null ? null : evidence.getSourceType(), "UNKNOWN");
        return switch (sourceType) {
            case "OFFICIAL" -> 0;
            case "DOCS" -> 1;
            case "PRICING" -> 2;
            case "API_DATA" -> 3;
            case "NEWS" -> 4;
            case "RSS" -> 5;
            case "REVIEW" -> 6;
            default -> 7;
        };
    }

    private double qualityScorePriority(DownstreamEvidenceView evidence) {
        if (evidence == null || evidence.getQuality() == null || evidence.getQuality().getQualityScore() == null) {
            return 1.0d;
        }
        return -evidence.getQuality().getQualityScore();
    }

    private int promptEvidenceChars(DownstreamEvidenceView evidence) {
        String content = evidence == null || evidence.getContent() == null ? "" : evidence.getContent().trim();
        return Math.min(content.length(), DEFAULT_MAX_PROMPT_EVIDENCE_CHARS);
    }

    /**
     * 当高优先级证据超出剩余额度时，优先保留它的截断正文，而不是整条丢弃。
     * 这样 structured / official 证据还能继续进入 Prompt，而后续低优先级证据再转成 skipped trace。
     */
    private DownstreamEvidenceView truncateEvidenceForPrompt(DownstreamEvidenceView evidence, int maxChars) {
        if (evidence == null) {
            return null;
        }
        String content = evidence.getContent() == null ? "" : evidence.getContent().trim();
        if (maxChars <= 0 || content.length() <= maxChars) {
            return evidence;
        }
        String truncatedContent = content.substring(0, maxChars) + "...(truncated)";
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>(evidence.getIssueFlags() == null ? List.of() : evidence.getIssueFlags());
        issueFlags.add("PROMPT_CONTENT_TRUNCATED");
        return evidence.toBuilder()
                .content(truncatedContent)
                .issueFlags(new ArrayList<>(issueFlags))
                .build()
                .normalized();
    }

    private DownstreamEvidenceView buildTraceOnlySkippedView(DownstreamEvidenceView evidence, String skipReason) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (evidence != null && evidence.getIssueFlags() != null) {
            issueFlags.addAll(evidence.getIssueFlags());
        }
        issueFlags.add(skipReason);
        return DownstreamEvidenceView.builder()
                .evidenceId(evidence == null ? null : evidence.getEvidenceId())
                .competitorName(evidence == null ? null : evidence.getCompetitorName())
                .sourceType(evidence == null ? null : evidence.getSourceType())
                .title(evidence == null ? null : evidence.getTitle())
                .content("")
                .sourceUrls(evidence == null || evidence.getSourceUrls() == null ? List.of() : evidence.getSourceUrls())
                .issueFlags(new ArrayList<>(issueFlags))
                .qualitySignals(List.of(skipReason))
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(evidence == null ? null : evidence.getQuality())
                .build()
                .normalized();
    }

    private boolean containsAnyIssueFlag(DownstreamEvidenceView evidence, List<String> expectedFlags) {
        for (String expectedFlag : expectedFlags == null ? List.<String>of() : expectedFlags) {
            if (containsIssueFlag(evidence, expectedFlag)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIssueFlag(DownstreamEvidenceView evidence, String expectedFlag) {
        if (evidence == null || evidence.getIssueFlags() == null || !hasText(expectedFlag)) {
            return false;
        }
        for (String issueFlag : evidence.getIssueFlags()) {
            if (expectedFlag.equalsIgnoreCase(firstNonBlank(issueFlag, null))) {
                return true;
            }
        }
        return false;
    }

    private List<DownstreamEvidenceView> normalizeViews(List<DownstreamEvidenceView> views) {
        List<DownstreamEvidenceView> normalized = new ArrayList<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            if (view != null) {
                normalized.add(view.normalized());
            }
        }
        return normalized;
    }

    private List<String> collectSourceUrls(List<DownstreamEvidenceView> evidences) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
            if (evidence == null || evidence.getSourceUrls() == null) {
                continue;
            }
            for (String sourceUrl : evidence.getSourceUrls()) {
                if (hasText(sourceUrl)) {
                    sourceUrls.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectIssueFlags(List<DownstreamEvidenceView> evidences, List<DownstreamEvidenceView> skippedEvidence) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (DownstreamEvidenceView evidence : evidences == null ? List.<DownstreamEvidenceView>of() : evidences) {
            if (evidence != null && evidence.getIssueFlags() != null) {
                issueFlags.addAll(evidence.getIssueFlags());
            }
        }
        if (skippedEvidence != null && !skippedEvidence.isEmpty()) {
            issueFlags.add("SKIPPED_UNUSABLE_EVIDENCE");
        }
        return new ArrayList<>(issueFlags);
    }

    private Map<String, Object> buildBudget(int usedPromptEvidenceChars, boolean truncated) {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxPromptEvidenceChars", DEFAULT_MAX_PROMPT_EVIDENCE_CHARS);
        budget.put("usedPromptEvidenceChars", usedPromptEvidenceChars);
        budget.put("truncated", truncated);
        return budget;
    }

    private SchemaRuntimeConfig readSchemaRuntimeConfig(String currentNodeConfig) {
        if (!hasText(currentNodeConfig)) {
            return new SchemaRuntimeConfig(null, List.of());
        }
        try {
            JsonNode config = objectMapper.readTree(currentNodeConfig);
            Long schemaId = config.path("schemaId").isNumber() ? config.path("schemaId").asLong() : null;
            List<String> dimensions = new ArrayList<>();
            JsonNode dimensionsNode = config.path("dimensions");
            if (dimensionsNode.isArray()) {
                for (JsonNode dimension : dimensionsNode) {
                    String value = dimension.asText("");
                    if (hasText(value)) {
                        dimensions.add(value.trim());
                    }
                }
            }
            return new SchemaRuntimeConfig(schemaId, dimensions);
        } catch (Exception e) {
            log.warn("provider failed to parse extractor node config", e);
            return new SchemaRuntimeConfig(null, List.of());
        }
    }

    private String firstNonBlank(String first, String fallback) {
        if (hasText(first)) {
            return first.trim();
        }
        return hasText(fallback) ? fallback.trim() : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SchemaRuntimeConfig(Long schemaId, List<String> dimensions) {
    }

    private record PromptSelection(List<DownstreamEvidenceView> selectedEvidence,
                                   List<DownstreamEvidenceView> skippedEvidence,
                                   int usedPromptEvidenceChars,
                                   boolean truncated) {
    }
}
