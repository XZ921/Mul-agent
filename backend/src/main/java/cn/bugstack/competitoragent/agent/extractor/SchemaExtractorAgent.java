package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽取 Agent，负责把采集到的原始证据整理成结构化竞品知识，并补齐字段级溯源信息。
 */
@Slf4j
@Component
public class SchemaExtractorAgent extends BaseAgent {

    private static final List<String> COVERAGE_FIELDS = List.of(
            "summary",
            "positioning",
            "targetUsers",
            "coreFeatures",
            "pricing",
            "strengths",
            "weaknesses"
    );

    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public SchemaExtractorAgent(AgentExecutionLogRepository logRepository,
                                EvidenceSourceRepository evidenceRepository,
                                CompetitorKnowledgeRepository knowledgeRepository,
                                LlmClient llmClient,
                                PromptTemplateService promptService,
                                ObjectMapper objectMapper) {
        super(logRepository);
        this.evidenceRepository = evidenceRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.EXTRACTOR;
    }

    @Override
    public String getName() {
        return "SchemaExtractorAgent";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        if (evidences.isEmpty()) {
            return AgentResult.failed("No evidence sources available");
        }

        // 抽取阶段按竞品聚合证据，保证每次提示词只处理单个竞品上下文。
        Map<String, List<EvidenceSource>> evidencesByCompetitor = groupByCompetitor(evidences);
        List<Map<String, Object>> extractionSummaries = new ArrayList<>();
        int successCount = 0;

        for (Map.Entry<String, List<EvidenceSource>> entry : evidencesByCompetitor.entrySet()) {
            String competitorName = entry.getKey();
            List<EvidenceSource> competitorEvidence = entry.getValue();

            try {
                ObjectNode normalizedSchema = extractAndNormalize(competitorName, competitorEvidence);
                CompetitorKnowledge knowledge = buildKnowledge(context, competitorName, normalizedSchema);
                knowledgeRepository.save(knowledge);

                extractionSummaries.add(Map.of(
                        "competitor", competitorName,
                        "sourceUrls", readStringList(normalizedSchema.path("sourceUrls")),
                        "coverage", objectMapper.convertValue(normalizedSchema.path("evidenceCoverage"), Map.class)
                ));
                successCount++;
            } catch (Exception e) {
                log.error("extract competitor schema failed: {}", competitorName, e);
                extractionSummaries.add(Map.of(
                        "competitor", competitorName,
                        "error", safe(e.getMessage())
                ));
            }
        }

        if (successCount == 0) {
            return AgentResult.failed("No competitor knowledge could be extracted");
        }

        try {
            String outputJson = objectMapper.writeValueAsString(Map.of(
                    "totalCompetitors", evidencesByCompetitor.size(),
                    "successCount", successCount,
                    "results", extractionSummaries
            ));
            return AgentResult.success(outputJson,
                    "Extracted competitor knowledge: " + successCount + "/" + evidencesByCompetitor.size());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("serialize extract result failed: " + e.getMessage());
        }
    }

    private Map<String, List<EvidenceSource>> groupByCompetitor(List<EvidenceSource> evidences) {
        Map<String, List<EvidenceSource>> grouped = new LinkedHashMap<>();
        for (EvidenceSource evidence : evidences) {
            grouped.computeIfAbsent(evidence.getCompetitorName(), key -> new ArrayList<>()).add(evidence);
        }
        return grouped;
    }

    /**
     * 提示词同时注入证据目录和正文内容，方便模型既输出结构化字段，又能引用 evidenceId。
     */
    private ObjectNode extractAndNormalize(String competitorName, List<EvidenceSource> competitorEvidence)
            throws LlmException, JsonProcessingException {
        String prompt = promptService.render("extractor", Map.of(
                "competitorName", competitorName,
                "evidenceCatalog", buildEvidenceCatalog(competitorEvidence),
                "collectedContent", buildCollectedContent(competitorEvidence)
        ));

        String llmResponse = llmClient.chatForJson(
                "You are a competitor knowledge extraction expert. Return JSON only.",
                prompt,
                "ExtractedSchema"
        );
        JsonNode schemaJson = objectMapper.readTree(cleanJson(llmResponse));
        if (!(schemaJson instanceof ObjectNode objectNode)) {
            throw new JsonProcessingException("Extractor output must be a JSON object") {};
        }
        return normalizeSchema(objectNode, competitorEvidence);
    }

    /**
     * 这里统一补齐 sourceUrls 与 evidenceCoverage，避免字段级溯源完全依赖模型自觉输出。
     */
    private ObjectNode normalizeSchema(ObjectNode schemaJson, List<EvidenceSource> competitorEvidence) {
        Map<String, EvidenceSource> evidenceById = new LinkedHashMap<>();
        for (EvidenceSource evidence : competitorEvidence) {
            evidenceById.put(evidence.getEvidenceId(), evidence);
        }

        ArrayNode sources = buildSourcesNode(schemaJson.path("sources"), competitorEvidence);
        schemaJson.set("sources", sources);

        LinkedHashSet<String> topLevelSourceUrls = new LinkedHashSet<>(readStringList(schemaJson.path("sourceUrls")));
        if (topLevelSourceUrls.isEmpty()) {
            topLevelSourceUrls.addAll(readUrlsFromSources(sources));
        }

        LinkedHashSet<String> referencedUrls = new LinkedHashSet<>();
        collectReferencedUrls(schemaJson, evidenceById, referencedUrls);
        if (topLevelSourceUrls.isEmpty()) {
            topLevelSourceUrls.addAll(referencedUrls);
        }
        if (topLevelSourceUrls.isEmpty()) {
            // 模型完全没给 sourceUrls 时，至少回退到当前竞品全部证据 URL，保证结果可追溯。
            topLevelSourceUrls.addAll(competitorEvidence.stream()
                    .map(EvidenceSource::getUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .toList());
        }

        schemaJson.set("sourceUrls", buildStringArray(topLevelSourceUrls));
        schemaJson.set("evidenceCoverage", buildCoverage(schemaJson, evidenceById));
        return schemaJson;
    }

    private ArrayNode buildSourcesNode(JsonNode existingSources, List<EvidenceSource> competitorEvidence) {
        if (existingSources != null && existingSources.isArray() && !existingSources.isEmpty()) {
            return (ArrayNode) existingSources.deepCopy();
        }

        ArrayNode sources = objectMapper.createArrayNode();
        for (EvidenceSource evidence : competitorEvidence) {
            ObjectNode sourceNode = objectMapper.createObjectNode();
            sourceNode.put("evidenceId", safe(evidence.getEvidenceId()));
            sourceNode.put("title", safe(evidence.getTitle()));
            sourceNode.put("url", safe(evidence.getUrl()));
            sources.add(sourceNode);
        }
        return sources;
    }

    /**
     * 对关键字段逐个生成溯源覆盖摘要，供前端、质检和导出阶段直接复用。
     */
    private ObjectNode buildCoverage(ObjectNode schemaJson, Map<String, EvidenceSource> evidenceById) {
        ObjectNode coverage = objectMapper.createObjectNode();
        for (String fieldName : COVERAGE_FIELDS) {
            JsonNode fieldNode = schemaJson.path(fieldName);
            ObjectNode fieldCoverage = objectMapper.createObjectNode();

            List<String> evidenceIds = collectEvidenceIds(fieldNode);
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(readStringList(fieldNode.path("sourceUrls")));
            for (String evidenceId : evidenceIds) {
                EvidenceSource matched = evidenceById.get(evidenceId);
                if (matched != null && matched.getUrl() != null && !matched.getUrl().isBlank()) {
                    sourceUrls.add(matched.getUrl());
                }
            }

            boolean hasValue = hasMeaningfulValue(fieldNode);
            // 有值但没有任何证据或来源链接时，要显式标成 MISSING_EVIDENCE。
            String status = !hasValue ? "EMPTY" : (evidenceIds.isEmpty() && sourceUrls.isEmpty()
                    ? "MISSING_EVIDENCE"
                    : "TRACEABLE");

            fieldCoverage.put("status", status);
            fieldCoverage.put("hasValue", hasValue);
            fieldCoverage.set("evidenceIds", buildStringArray(evidenceIds));
            fieldCoverage.set("sourceUrls", buildStringArray(sourceUrls));
            coverage.set(fieldName, fieldCoverage);
        }
        return coverage;
    }

    private void collectReferencedUrls(JsonNode node,
                                       Map<String, EvidenceSource> evidenceById,
                                       Set<String> referencedUrls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            // evidenceIds 与 sourceUrls 可能出现在任意嵌套层级，因此需要递归扫描整棵 JSON 树。
            JsonNode evidenceIdsNode = node.get("evidenceIds");
            if (evidenceIdsNode != null && evidenceIdsNode.isArray()) {
                for (JsonNode evidenceIdNode : evidenceIdsNode) {
                    EvidenceSource matched = evidenceById.get(evidenceIdNode.asText());
                    if (matched != null && matched.getUrl() != null && !matched.getUrl().isBlank()) {
                        referencedUrls.add(matched.getUrl());
                    }
                }
            }

            JsonNode sourceUrlsNode = node.get("sourceUrls");
            if (sourceUrlsNode != null && sourceUrlsNode.isArray()) {
                for (JsonNode urlNode : sourceUrlsNode) {
                    if (!urlNode.asText().isBlank()) {
                        referencedUrls.add(urlNode.asText());
                    }
                }
            }

            node.fields().forEachRemaining(entry -> collectReferencedUrls(entry.getValue(), evidenceById, referencedUrls));
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectReferencedUrls(item, evidenceById, referencedUrls);
            }
        }
    }

    private List<String> collectEvidenceIds(JsonNode node) {
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        collectEvidenceIds(node, evidenceIds);
        return new ArrayList<>(evidenceIds);
    }

    private void collectEvidenceIds(JsonNode node, Set<String> evidenceIds) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            JsonNode evidenceIdsNode = node.get("evidenceIds");
            if (evidenceIdsNode != null && evidenceIdsNode.isArray()) {
                for (JsonNode item : evidenceIdsNode) {
                    String evidenceId = item.asText();
                    if (!evidenceId.isBlank()) {
                        evidenceIds.add(evidenceId);
                    }
                }
            }
            node.fields().forEachRemaining(entry -> collectEvidenceIds(entry.getValue(), evidenceIds));
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectEvidenceIds(item, evidenceIds);
            }
        }
    }

    private boolean hasMeaningfulValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        // 对数组和对象不能只看非 null，还要判断里面是否真的有可用内容。
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        if (node.isObject()) {
            return node.fieldNames().hasNext();
        }
        return true;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> readUrlsFromSources(ArrayNode sources) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (JsonNode source : sources) {
            String url = source.path("url").asText("");
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    private ArrayNode buildStringArray(Iterable<String> values) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                arrayNode.add(value);
            }
        }
        return arrayNode;
    }

    private String buildCollectedContent(List<EvidenceSource> evidences) {
        StringBuilder collectedContent = new StringBuilder();
        for (EvidenceSource evidence : evidences) {
            collectedContent.append("--- Source: ")
                    .append(evidence.getEvidenceId())
                    .append(" ")
                    .append(evidence.getTitle())
                    .append(" ---\n");

            String content = evidence.getFullContent() != null ? evidence.getFullContent() : evidence.getContentSnippet();
            if (content != null && content.length() > 8000) {
                // 控制单条证据正文大小，避免某个页面吞掉整个模型上下文窗口。
                content = content.substring(0, 8000) + "...(truncated)";
            }
            collectedContent.append(content == null ? "" : content).append("\n\n");
        }
        return collectedContent.toString();
    }

    private String buildEvidenceCatalog(List<EvidenceSource> evidences) throws JsonProcessingException {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (EvidenceSource evidence : evidences) {
            catalog.add(Map.of(
                    "evidenceId", safe(evidence.getEvidenceId()),
                    "title", safe(evidence.getTitle()),
                    "url", safe(evidence.getUrl()),
                    "snippet", truncate(evidence.getContentSnippet(), 280)
            ));
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(catalog);
    }

    /**
     * 落库时尽量保留原始 JSON 结构，方便分析、导出和 trace 面板直接复用。
     */
    private CompetitorKnowledge buildKnowledge(AgentContext context, String competitorName, ObjectNode schemaJson) {
        return CompetitorKnowledge.builder()
                .taskId(context.getTaskId())
                .competitorName(competitorName)
                .officialUrl(schemaJson.path("officialUrl").asText(null))
                .summary(schemaJson.path("summary").asText(null))
                .positioning(schemaJson.path("positioning").asText(null))
                .targetUsers(readJsonField(schemaJson, "targetUsers", "[]"))
                .coreFeatures(readJsonField(schemaJson, "coreFeatures", "[]"))
                .pricing(readJsonField(schemaJson, "pricing", "{}"))
                .strengths(readJsonField(schemaJson, "strengths", "[]"))
                .weaknesses(readJsonField(schemaJson, "weaknesses", "[]"))
                .sources(readJsonField(schemaJson, "sources", "[]"))
                .sourceUrls(readJsonField(schemaJson, "sourceUrls", "[]"))
                .evidenceCoverage(readJsonField(schemaJson, "evidenceCoverage", "{}"))
                .extractedAt(LocalDateTime.now())
                .build();
    }

    private String readJsonField(ObjectNode schemaJson, String fieldName, String defaultValue) {
        JsonNode field = schemaJson.get(fieldName);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return defaultValue;
        }
        return field.isTextual() ? field.asText() : field.toString();
    }

    private String cleanJson(String llmResponse) {
        String cleaned = llmResponse.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
