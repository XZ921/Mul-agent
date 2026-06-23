package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
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
 * 基于 repository-backed 端口的 extractor 输入提供者。
 * 第三轮开始 Provider 只负责筛选、排序、预算控制和组包，
 * 不再直接持有“从 repository 读 EvidenceSource 并解析 pageMetadata”的实现细节。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryExtractorInputProvider implements ExtractorInputProvider {

    private static final int DEFAULT_MAX_PROMPT_EVIDENCE_CHARS = 4000;
    private static final int DIVERSITY_RESERVED_PROMPT_CHARS = 800;
    private static final int THIN_CONTENT_THRESHOLD = 40;
    private static final List<String> CORE_PROMPT_DIVERSITY_SOURCE_TYPES = List.of(
            "OFFICIAL",
            "DOCS",
            "PRICING",
            "API_DATA"
    );
    private static final List<String> READABLE_EXCLUDED_FLAGS = List.of(
            "CONTENT_GAP",
            "COLLECT_FAILED",
            "NO_USABLE_CONTENT"
    );

    private final ExtractorEvidenceSourcePort extractorEvidenceSourcePort;
    private final ObjectMapper objectMapper;

    @Override
    public ExtractorInputPackage provide(AgentContext context) {
        List<ExtractorEvidenceInput> allInputs = extractorEvidenceSourcePort.load(context);
        List<ExtractorEvidenceInput> usableInputs = new ArrayList<>();
        List<ExtractorEvidenceInput> skippedInputs = new ArrayList<>();
        for (ExtractorEvidenceInput input : allInputs == null ? List.<ExtractorEvidenceInput>of() : allInputs) {
            if (isUsableEvidence(input)) {
                usableInputs.add(input);
            } else if (input != null) {
                skippedInputs.add(input.toBuilder()
                        .issueFlags(appendIssueFlag(input.getIssueFlags(), "NO_USABLE_EVIDENCE"))
                        .build()
                        .normalized());
            }
        }

        Map<String, List<ExtractorEvidenceInput>> usableByCompetitor = groupByCompetitor(usableInputs);
        Map<String, List<ExtractorEvidenceInput>> skippedByCompetitor = groupByCompetitor(skippedInputs);
        SchemaRuntimeConfig schemaRuntimeConfig = readSchemaRuntimeConfig(context == null ? null : context.getCurrentNodeConfig());

        return ExtractorInputPackage.builder()
                .taskId(context == null ? null : context.getTaskId())
                .nodeName(firstNonBlank(context == null ? null : context.getCurrentNodeName(), "extract_schema"))
                .planVersionId(context == null ? null : context.getPlanVersionId())
                .branchKey(context == null ? null : context.getBranchKey())
                .schemaId(schemaRuntimeConfig.schemaId())
                .dimensions(schemaRuntimeConfig.dimensions())
                .inputSource("REPOSITORY_BACKED_PORT")
                .auditRefs(buildAuditRefs(context))
                .competitors(buildCompetitorInputs(usableByCompetitor, skippedByCompetitor))
                .build();
    }

    private Map<String, List<ExtractorEvidenceInput>> groupByCompetitor(List<ExtractorEvidenceInput> evidences) {
        Map<String, List<ExtractorEvidenceInput>> grouped = new LinkedHashMap<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            grouped.computeIfAbsent(firstNonBlank(evidence.getCompetitorName(), "UNKNOWN"), key -> new ArrayList<>())
                    .add(evidence.normalized());
        }
        return grouped;
    }

    private List<ExtractorCompetitorInput> buildCompetitorInputs(Map<String, List<ExtractorEvidenceInput>> usableByCompetitor,
                                                                 Map<String, List<ExtractorEvidenceInput>> skippedByCompetitor) {
        LinkedHashSet<String> competitorNames = new LinkedHashSet<>();
        competitorNames.addAll(usableByCompetitor.keySet());
        competitorNames.addAll(skippedByCompetitor.keySet());

        List<ExtractorCompetitorInput> competitors = new ArrayList<>();
        for (String competitorName : competitorNames) {
            List<ExtractorEvidenceInput> normalizedUsableInputs = normalizeInputs(
                    usableByCompetitor.getOrDefault(competitorName, List.of()));
            List<ExtractorEvidenceInput> enrichedUsableInputs = enrichEvidenceInputs(normalizedUsableInputs);
            List<ExtractorEvidenceInput> normalizedSkippedInputs = normalizeInputs(
                    skippedByCompetitor.getOrDefault(competitorName, List.of()));
            PromptSelection selection = selectPromptEvidence(enrichedUsableInputs);
            List<ExtractorEvidenceInput> evidenceCatalog = selection.selectedEvidence();
            List<ExtractorEvidenceInput> traceableSkippedInputs = new ArrayList<>(selection.skippedEvidence());
            traceableSkippedInputs.addAll(normalizedSkippedInputs);
            competitors.add(ExtractorCompetitorInput.builder()
                    .competitorName(competitorName)
                    .evidenceCatalog(evidenceCatalog)
                    .structuredEvidence(filterStructuredEvidence(evidenceCatalog))
                    .readableEvidence(filterReadableEvidence(evidenceCatalog))
                    .skippedEvidence(traceableSkippedInputs)
                    .sourceUrls(collectSourceUrls(evidenceCatalog))
                    .issueFlags(collectIssueFlags(evidenceCatalog, traceableSkippedInputs))
                    .budget(buildBudget(selection.usedPromptEvidenceChars(), selection.truncated()))
                    .build());
        }
        return competitors;
    }

    /**
     * Provider 侧统一判断“什么输入有资格进入 extractor 选择队列”，
     * 这样下游看到的 skippedEvidence 就能直接解释被拦下的原因。
     */
    private boolean isUsableEvidence(ExtractorEvidenceInput input) {
        if (input == null) {
            return false;
        }
        boolean hasContent = hasText(input.getContent());
        boolean hasStructuredEvidence = hasStructuredEvidence(input);
        return hasContent || hasStructuredEvidence;
    }

    private boolean hasStructuredEvidence(ExtractorEvidenceInput input) {
        if (input == null) {
            return false;
        }
        boolean hasStructuredBlocks = input.getStructuredBlocks() != null && !input.getStructuredBlocks().isEmpty();
        boolean hasStructuredPayload = input.getStructuredPayload() != null && !input.getStructuredPayload().isEmpty();
        return hasStructuredBlocks || hasStructuredPayload;
    }

    private List<ExtractorEvidenceInput> filterStructuredEvidence(List<ExtractorEvidenceInput> evidences) {
        List<ExtractorEvidenceInput> structured = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence != null && evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
                structured.add(evidence);
            }
        }
        return structured;
    }

    private List<ExtractorEvidenceInput> filterReadableEvidence(List<ExtractorEvidenceInput> evidences) {
        List<ExtractorEvidenceInput> readable = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
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
     * 在 Provider 层补齐薄正文标记，避免 extractor 只能从 prompt 文本里倒推输入质量。
     */
    private List<ExtractorEvidenceInput> enrichEvidenceInputs(List<ExtractorEvidenceInput> evidences) {
        List<ExtractorEvidenceInput> enriched = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            List<String> issueFlags = appendIssueFlag(
                    evidence.getIssueFlags(),
                    !hasStructuredEvidence(evidence)
                            && hasText(evidence.getContent())
                            && evidence.getContent().trim().length() < THIN_CONTENT_THRESHOLD
                            ? "THIN_CONTENT_ONLY"
                            : null);
            enriched.add(evidence.toBuilder()
                    .issueFlags(issueFlags)
                    .build()
                    .normalized());
        }
        return enriched;
    }

    /**
     * 统一在 Provider 层决定真正进入 prompt 的证据集合。
     * 排序、预算和跳过原因都必须在这里固定下来，后续 replay 才能解释“为什么这一轮看到的是这些正文”。
     */
    private PromptSelection selectPromptEvidence(List<ExtractorEvidenceInput> evidences) {
        List<ExtractorEvidenceInput> sorted = new ArrayList<>(evidences == null ? List.of() : evidences);
        sorted.sort(Comparator
                .comparing(this::structuredPriority)
                .thenComparing(this::sourceTypePriority)
                .thenComparing(this::qualityScorePriority)
                .thenComparing(view -> firstNonBlank(view == null ? null : view.getEvidenceId(), "ZZZ")));

        List<ExtractorEvidenceInput> selected = new ArrayList<>();
        List<ExtractorEvidenceInput> skipped = new ArrayList<>();
        LinkedHashSet<String> selectedDiversitySourceTypes = new LinkedHashSet<>();
        int usedPromptEvidenceChars = 0;
        boolean truncated = false;
        for (int index = 0; index < sorted.size(); index++) {
            ExtractorEvidenceInput evidence = sorted.get(index);
            if (evidence == null) {
                continue;
            }
            int evidenceChars = promptEvidenceChars(evidence);
            if (usedPromptEvidenceChars >= DEFAULT_MAX_PROMPT_EVIDENCE_CHARS) {
                skipped.add(buildTraceOnlySkippedInput(evidence, "PROMPT_BUDGET_SKIPPED"));
                truncated = true;
                continue;
            }
            int remainingChars = DEFAULT_MAX_PROMPT_EVIDENCE_CHARS - usedPromptEvidenceChars;
            int reservedChars = reservePromptCharsForPendingSourceTypes(
                    sorted, index + 1, selectedDiversitySourceTypes, evidence);
            int maxCharsForCurrentEvidence = Math.max(0, remainingChars - reservedChars);
            if (evidenceChars == 0) {
                selected.add(evidence);
                addDiversitySourceType(selectedDiversitySourceTypes, evidence);
                continue;
            }
            if (maxCharsForCurrentEvidence <= 0) {
                skipped.add(buildTraceOnlySkippedInput(evidence, "PROMPT_BUDGET_SKIPPED"));
                truncated = true;
                continue;
            }
            if (evidenceChars > maxCharsForCurrentEvidence) {
                ExtractorEvidenceInput truncatedEvidence = truncateEvidenceForPrompt(evidence, maxCharsForCurrentEvidence);
                selected.add(truncatedEvidence);
                addDiversitySourceType(selectedDiversitySourceTypes, truncatedEvidence);
                usedPromptEvidenceChars += promptEvidenceChars(truncatedEvidence);
                truncated = true;
                continue;
            }
            selected.add(evidence);
            addDiversitySourceType(selectedDiversitySourceTypes, evidence);
            usedPromptEvidenceChars += evidenceChars;
        }
        return new PromptSelection(selected, skipped, usedPromptEvidenceChars, truncated);
    }

    /**
     * 为尚未进入 prompt 的核心来源类型预留最低正文额度，
     * 防止某一条超长 docs 证据挤掉 pricing / official 等关键来源。
     */
    private int reservePromptCharsForPendingSourceTypes(List<ExtractorEvidenceInput> sorted,
                                                        int startIndex,
                                                        LinkedHashSet<String> selectedDiversitySourceTypes,
                                                        ExtractorEvidenceInput currentEvidence) {
        LinkedHashSet<String> reservedSourceTypes = new LinkedHashSet<>(
                selectedDiversitySourceTypes == null ? List.of() : selectedDiversitySourceTypes);
        addDiversitySourceType(reservedSourceTypes, currentEvidence);
        int reservedChars = 0;
        for (int index = startIndex; index < (sorted == null ? 0 : sorted.size()); index++) {
            ExtractorEvidenceInput candidate = sorted.get(index);
            String sourceType = diversitySourceType(candidate);
            if (!hasText(sourceType) || reservedSourceTypes.contains(sourceType)) {
                continue;
            }
            int candidateChars = promptEvidenceChars(candidate);
            if (candidateChars <= 0) {
                continue;
            }
            reservedSourceTypes.add(sourceType);
            reservedChars += Math.min(candidateChars, DIVERSITY_RESERVED_PROMPT_CHARS);
        }
        return reservedChars;
    }

    private void addDiversitySourceType(LinkedHashSet<String> selectedDiversitySourceTypes,
                                        ExtractorEvidenceInput evidence) {
        String sourceType = diversitySourceType(evidence);
        if (selectedDiversitySourceTypes != null && hasText(sourceType)) {
            selectedDiversitySourceTypes.add(sourceType);
        }
    }

    private String diversitySourceType(ExtractorEvidenceInput evidence) {
        String sourceType = firstNonBlank(evidence == null ? null : evidence.getSourceType(), "");
        return CORE_PROMPT_DIVERSITY_SOURCE_TYPES.contains(sourceType) ? sourceType : "";
    }

    private int structuredPriority(ExtractorEvidenceInput evidence) {
        return hasStructuredEvidence(evidence) ? 0 : 1;
    }

    private int sourceTypePriority(ExtractorEvidenceInput evidence) {
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

    private double qualityScorePriority(ExtractorEvidenceInput evidence) {
        if (evidence == null || evidence.getQuality() == null || evidence.getQuality().getQualityScore() == null) {
            return 1.0d;
        }
        return -evidence.getQuality().getQualityScore();
    }

    private int promptEvidenceChars(ExtractorEvidenceInput evidence) {
        String content = evidence == null || evidence.getContent() == null ? "" : evidence.getContent().trim();
        return content.length();
    }

    /**
     * 当证据超过剩余额度时保留截断正文，而不是整条丢弃。
     * 这样 extractor 至少还能看到来源、结构块和部分正文，并且预算统计与真实 prompt 一致。
     */
    private ExtractorEvidenceInput truncateEvidenceForPrompt(ExtractorEvidenceInput evidence, int maxChars) {
        if (evidence == null) {
            return null;
        }
        String content = evidence.getContent() == null ? "" : evidence.getContent().trim();
        if (maxChars <= 0 || content.length() <= maxChars) {
            return evidence;
        }
        String truncatedMarker = "...(truncated)";
        String truncatedContent = maxChars <= truncatedMarker.length()
                ? truncatedMarker.substring(0, maxChars)
                : content.substring(0, maxChars - truncatedMarker.length()) + truncatedMarker;
        return evidence.toBuilder()
                .content(truncatedContent)
                .issueFlags(appendIssueFlag(evidence.getIssueFlags(), "PROMPT_CONTENT_TRUNCATED"))
                .build()
                .normalized();
    }

    private ExtractorEvidenceInput buildTraceOnlySkippedInput(ExtractorEvidenceInput evidence, String skipReason) {
        if (evidence == null) {
            return null;
        }
        return evidence.toBuilder()
                .content("")
                .issueFlags(appendIssueFlag(evidence.getIssueFlags(), skipReason))
                .qualitySignals(appendIssueFlag(evidence.getQualitySignals(), skipReason))
                .structuredPayload(Map.of())
                .build()
                .normalized();
    }

    private List<String> appendIssueFlag(List<String> issueFlags, String newIssueFlag) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(issueFlags == null ? List.of() : issueFlags);
        if (hasText(newIssueFlag)) {
            merged.add(newIssueFlag.trim());
        }
        return new ArrayList<>(merged);
    }

    private boolean containsAnyIssueFlag(ExtractorEvidenceInput evidence, List<String> expectedFlags) {
        for (String expectedFlag : expectedFlags == null ? List.<String>of() : expectedFlags) {
            if (containsIssueFlag(evidence, expectedFlag)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIssueFlag(ExtractorEvidenceInput evidence, String expectedFlag) {
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

    private List<ExtractorEvidenceInput> normalizeInputs(List<ExtractorEvidenceInput> inputs) {
        List<ExtractorEvidenceInput> normalized = new ArrayList<>();
        for (ExtractorEvidenceInput input : inputs == null ? List.<ExtractorEvidenceInput>of() : inputs) {
            if (input != null) {
                normalized.add(input.normalized());
            }
        }
        return normalized;
    }

    private List<String> collectSourceUrls(List<ExtractorEvidenceInput> evidences) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
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

    private List<String> collectIssueFlags(List<ExtractorEvidenceInput> evidences, List<ExtractorEvidenceInput> skippedEvidence) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence != null && evidence.getIssueFlags() != null) {
                issueFlags.addAll(evidence.getIssueFlags());
            }
        }
        if (skippedEvidence != null && !skippedEvidence.isEmpty()) {
            issueFlags.add("SKIPPED_UNUSABLE_EVIDENCE");
            for (ExtractorEvidenceInput skippedInput : skippedEvidence) {
                if (skippedInput != null && skippedInput.getIssueFlags() != null) {
                    issueFlags.addAll(skippedInput.getIssueFlags());
                }
            }
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

    /**
     * auditRefs 只提供来源与可用性诊断，不允许反向替代 extractor 正文输入。
     * 这样 replay / cache 看得到“为什么能或不能解释这轮输入”，但不会把 shared envelope 误当成正式正文来源。
     */
    private Map<String, Object> buildAuditRefs(AgentContext context) {
        int collectorEnvelopeCount = 0;
        LinkedHashSet<String> projectionTypes = new LinkedHashSet<>();
        for (Map.Entry<String, SharedNodeOutputEnvelope> entry :
                (context == null || context.getSharedOutputEnvelopes() == null
                        ? Map.<String, SharedNodeOutputEnvelope>of()
                        : context.getSharedOutputEnvelopes()).entrySet()) {
            if (entry.getKey() == null || !entry.getKey().startsWith("collect")) {
                continue;
            }
            SharedNodeOutputEnvelope envelope = entry.getValue();
            collectorEnvelopeCount++;
            if (envelope != null && hasText(envelope.getProjectionType())) {
                projectionTypes.add(envelope.getProjectionType().trim());
            }
        }
        boolean hasSearchProjection = projectionTypes.contains("SEARCH_SHARED_PROJECTION_V1");
        String searchAuditAvailabilityReason = collectorEnvelopeCount == 0
                ? "COLLECTOR_SHARED_ENVELOPE_MISSING"
                : hasSearchProjection ? "SEARCH_SHARED_PROJECTION_READY" : "SEARCH_SHARED_PROJECTION_NOT_FOUND";
        String collectionAuditAvailabilityReason = collectorEnvelopeCount == 0
                ? "COLLECTOR_SHARED_ENVELOPE_MISSING"
                : "COLLECTOR_SHARED_ENVELOPE_READY";
        return Map.of(
                "collectorEnvelopeCount", collectorEnvelopeCount,
                "projectionTypes", new ArrayList<>(projectionTypes),
                "searchAudit", Map.of(
                        "available", hasSearchProjection,
                        "availabilityReason", searchAuditAvailabilityReason,
                        "usage", "用于解释来源发现与采集路径，不直接替代 extractor 正文输入"),
                "collectionAudit", Map.of(
                        "available", collectorEnvelopeCount > 0,
                        "availabilityReason", collectionAuditAvailabilityReason,
                        "usage", "用于解释采集失败、降级与 skippedEvidence 来源")
        );
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

    private record PromptSelection(List<ExtractorEvidenceInput> selectedEvidence,
                                   List<ExtractorEvidenceInput> skippedEvidence,
                                   int usedPromptEvidenceChars,
                                   boolean truncated) {
    }
}
