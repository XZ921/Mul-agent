package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.workflow.contract.CompetitorKnowledgeDraft;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.ExtractResult;
import cn.bugstack.competitoragent.workflow.contract.FeatureItem;
import cn.bugstack.competitoragent.workflow.contract.PricingItem;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import cn.bugstack.competitoragent.workflow.contract.StrengthWeaknessItem;
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

    private static final int EXTRACT_JSON_MAX_ATTEMPTS = 3;
    private static final List<String> COVERAGE_FIELDS = List.of(
            "summary",
            "positioning",
            "targetUsers",
            "coreFeatures",
            "pricing",
            "strengths",
            "weaknesses"
    );
    private static final List<CoverageFieldDefinition> COVERAGE_FIELD_DEFINITIONS = List.of(
            new CoverageFieldDefinition("summary", "产品概览"),
            new CoverageFieldDefinition("positioning", "市场定位"),
            new CoverageFieldDefinition("targetUsers", "目标用户"),
            new CoverageFieldDefinition("coreFeatures", "核心能力"),
            new CoverageFieldDefinition("pricing", "定价策略"),
            new CoverageFieldDefinition("strengths", "优势判断"),
            new CoverageFieldDefinition("weaknesses", "短板与风险")
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
                                AgentContextAssembler agentContextAssembler,
                                ObjectMapper objectMapper) {
        super(logRepository, agentContextAssembler);
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
        return "结构抽取智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        List<EvidenceSource> allEvidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        List<EvidenceSource> evidences = allEvidences.stream()
                .filter(this::isUsableEvidence)
                .toList();
        if (evidences.isEmpty()) {
            return AgentResult.failed("暂无可用于抽取的证据来源");
        }
        if (evidences.size() < allEvidences.size()) {
            log.warn("extractor skipped unusable evidences, taskId={}, usableCount={}, totalCount={}",
                    context.getTaskId(), evidences.size(), allEvidences.size());
        }

        // 抽取阶段按竞品聚合证据，保证每次提示词只处理单个竞品上下文。
        Map<String, List<EvidenceSource>> evidencesByCompetitor = groupByCompetitor(evidences);
        List<Map<String, Object>> extractionSummaries = new ArrayList<>();
        List<CompetitorKnowledgeDraft> drafts = new ArrayList<>();
        LinkedHashSet<String> aggregatedSourceUrls = new LinkedHashSet<>();
        LinkedHashSet<String> aggregatedIssueFlags = new LinkedHashSet<>();
        List<EvidenceFragment> aggregatedFragments = new ArrayList<>();
        List<SectionEvidenceBundle> aggregatedSectionBundles = new ArrayList<>();
        int successCount = 0;

        for (Map.Entry<String, List<EvidenceSource>> entry : evidencesByCompetitor.entrySet()) {
            String competitorName = entry.getKey();
            List<EvidenceSource> competitorEvidence = entry.getValue();

            try {
                NormalizedSchema normalizedSchema = extractAndNormalize(
                        competitorName,
                        competitorEvidence,
                        context.getTaskRagPromptContext()
                );
                CompetitorKnowledge knowledge = buildKnowledge(context, competitorName, normalizedSchema.schema());
                knowledgeRepository.save(knowledge);

                extractionSummaries.add(Map.of(
                        "competitor", competitorName,
                        "sourceUrls", readStringList(normalizedSchema.schema().path("sourceUrls")),
                        "coverage", objectMapper.convertValue(normalizedSchema.schema().path("evidenceCoverage"), Map.class),
                        "issueFlags", normalizedSchema.issueFlags()
                ));
                CompetitorKnowledgeDraft draft = buildKnowledgeDraft(competitorName, normalizedSchema);
                drafts.add(draft);
                aggregatedSourceUrls.addAll(draft.getSourceUrls());
                aggregatedIssueFlags.addAll(draft.getIssueFlags());
                aggregatedFragments.addAll(draft.getEvidenceFragments());
                aggregatedSectionBundles.addAll(draft.getSectionEvidenceBundles());
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
            return AgentResult.failed("未能抽取出可用的竞品知识");
        }

        try {
            ExtractResult extractResult = ExtractResult.builder()
                    .totalCompetitors(successCount)
                    .drafts(drafts)
                    .sourceUrls(new ArrayList<>(aggregatedSourceUrls))
                    .issueFlags(new ArrayList<>(aggregatedIssueFlags))
                    .evidenceFragments(normalizeEvidenceFragments(aggregatedFragments))
                    .sectionEvidenceBundles(normalizeSectionEvidenceBundles(aggregatedSectionBundles))
                    .build();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("contractVersion", extractResult.getContractVersion());
            output.put("totalCompetitors", evidencesByCompetitor.size());
            output.put("successCount", successCount);
            output.put("results", extractionSummaries);
            output.put("drafts", extractResult.getDrafts());
            output.put("sourceUrls", extractResult.getSourceUrls());
            // 统一把本次实际消费的 Task RAG 摘要写回节点输出，避免后续只能从 prompt 日志倒推。
            output.put("taskRagContext", context.getTaskRagPromptContext());
            output.put("issueFlags", extractResult.getIssueFlags());
            output.put("evidenceFragments", extractResult.getEvidenceFragments());
            output.put("sectionEvidenceBundles", extractResult.getSectionEvidenceBundles());
            String outputJson = objectMapper.writeValueAsString(output);
            return AgentResult.success(outputJson,
                    "已抽取竞品知识：" + successCount + "/" + evidencesByCompetitor.size());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("抽取结果序列化失败：" + e.getMessage());
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
    private NormalizedSchema extractAndNormalize(String competitorName,
                                                List<EvidenceSource> competitorEvidence,
                                                String taskRagContext)
            throws LlmException, JsonProcessingException {
        String prompt = promptService.render("extractor", Map.of(
                "competitorName", competitorName,
                "evidenceCatalog", buildEvidenceCatalog(competitorEvidence),
                "collectedContent", buildCollectedContent(competitorEvidence),
                // 统一任务上下文在这里透传给 Prompt，保证提取阶段也能看到检索结论与缺口说明。
                "taskRagContext", taskRagContext
        ));
        JsonProcessingException lastParseException = null;

        for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
            String attemptPrompt = attempt == 1
                    ? prompt
                    : prompt + "\n\n【补充要求】上一次返回的 JSON 解析失败，请重新输出一个完整、闭合、合法的 JSON 对象，不要附加解释。";
            String llmResponse = llmClient.chatForJson(
                    "你是一名竞品知识抽取专家，请只返回 JSON。",
                    attemptPrompt,
                    "ExtractedSchema"
            );
            try {
                ParsedSchemaRoot parsedRoot = parseSchemaRoot(llmResponse);
                if (parsedRoot.recovered()) {
                    log.warn("extractor recovered non-object json root, competitor={}, attempt={}/{}",
                            competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS);
                }
                return normalizeSchema(parsedRoot.objectNode(), competitorEvidence, parsedRoot.issueFlags());
            } catch (JsonProcessingException e) {
                lastParseException = e;
                log.warn("extractor json parse failed, competitor={}, attempt={}/{}",
                        competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS, e);
            }
        }

        throw lastParseException != null
                ? lastParseException
                : new JsonProcessingException("Extractor output JSON parse failed") {};
    }

    /**
     * 这里统一补齐 sourceUrls 与 evidenceCoverage，避免字段级溯源完全依赖模型自觉输出。
     */
    private NormalizedSchema normalizeSchema(ObjectNode schemaJson,
                                            List<EvidenceSource> competitorEvidence,
                                            List<String> inheritedIssueFlags) {
        Map<String, EvidenceSource> evidenceById = new LinkedHashMap<>();
        for (EvidenceSource evidence : competitorEvidence) {
            evidenceById.put(evidence.getEvidenceId(), evidence);
        }
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (inheritedIssueFlags != null) {
            issueFlags.addAll(inheritedIssueFlags);
        }

        ArrayNode sources = buildSourcesNode(schemaJson.path("sources"), competitorEvidence);
        schemaJson.set("sources", sources);

        LinkedHashSet<String> topLevelSourceUrls = new LinkedHashSet<>(readStringList(schemaJson.path("sourceUrls")));
        boolean modelProvidedSourceUrls = !topLevelSourceUrls.isEmpty();
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
        if (!modelProvidedSourceUrls && !topLevelSourceUrls.isEmpty()) {
            issueFlags.add("SOURCE_URLS_BACKFILLED");
        }

        schemaJson.set("sourceUrls", buildStringArray(topLevelSourceUrls));
        ObjectNode coverage = buildCoverage(schemaJson, evidenceById);
        schemaJson.set("evidenceCoverage", coverage);
        issueFlags.addAll(collectCoverageIssueFlags(coverage));
        List<EvidenceFragment> evidenceFragments = buildEvidenceFragments("EXTRACT", competitorEvidence, issueFlags);
        List<SectionEvidenceBundle> sectionEvidenceBundles = buildSectionEvidenceBundles(
                competitorNameFromEvidence(competitorEvidence),
                schemaJson,
                coverage,
                evidenceById
        );
        return new NormalizedSchema(schemaJson, new ArrayList<>(issueFlags), evidenceFragments, sectionEvidenceBundles);
    }

    /**
     * 抽取模型偶尔会返回数组、字符串包裹 JSON，甚至直接给空数组。
     * 这里把“还能救回来”的根节点统一归一成 ObjectNode，尽量不要因为输出形态抖动直接打断整条工作流。
     */
    private ParsedSchemaRoot parseSchemaRoot(String llmResponse) throws JsonProcessingException {
        return parseSchemaRootValue(extractJsonObject(cleanJson(llmResponse)), 0);
    }

    private ParsedSchemaRoot parseSchemaRootValue(String rawValue, int depth) throws JsonProcessingException {
        if (depth > 2) {
            throw new JsonProcessingException("Extractor output must be a JSON object") {};
        }
        JsonNode schemaJson = objectMapper.readTree(rawValue);
        if (schemaJson instanceof ObjectNode objectNode) {
            return new ParsedSchemaRoot(objectNode.deepCopy(), List.of());
        }
        if (schemaJson == null || schemaJson.isNull()) {
            return new ParsedSchemaRoot(objectMapper.createObjectNode(), List.of("MODEL_OUTPUT_RECOVERED"));
        }
        if (schemaJson.isTextual()) {
            String nested = cleanJson(schemaJson.asText(""));
            if (nested.isBlank()) {
                return new ParsedSchemaRoot(objectMapper.createObjectNode(), List.of("MODEL_OUTPUT_RECOVERED"));
            }
            ParsedSchemaRoot parsed = parseSchemaRootValue(extractJsonObject(nested), depth + 1);
            return parsed.appendIssueFlag("MODEL_OUTPUT_RECOVERED");
        }
        if (schemaJson.isArray()) {
            ArrayNode arrayNode = (ArrayNode) schemaJson;
            if (arrayNode.isEmpty()) {
                return new ParsedSchemaRoot(objectMapper.createObjectNode(), List.of("MODEL_OUTPUT_RECOVERED"));
            }
            for (JsonNode item : arrayNode) {
                if (item instanceof ObjectNode objectItem) {
                    return new ParsedSchemaRoot(objectItem.deepCopy(), List.of("MODEL_OUTPUT_RECOVERED"));
                }
                if (item != null && item.isTextual() && !item.asText("").isBlank()) {
                    ParsedSchemaRoot parsed = parseSchemaRootValue(extractJsonObject(cleanJson(item.asText())), depth + 1);
                    return parsed.appendIssueFlag("MODEL_OUTPUT_RECOVERED");
                }
            }
            return new ParsedSchemaRoot(objectMapper.createObjectNode(), List.of("MODEL_OUTPUT_RECOVERED"));
        }
        throw new JsonProcessingException("Extractor output must be a JSON object") {};
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

    /**
     * 抽取结果除了落库，还要组装成稳定的 Draft 契约继续往下传。
     * 这里显式带上 sourceUrls / evidenceCoverage / issueFlags，防止分析阶段再去猜哪些字段可信。
     */
    private CompetitorKnowledgeDraft buildKnowledgeDraft(String competitorName, NormalizedSchema normalizedSchema) {
        ObjectNode schemaJson = normalizedSchema.schema();
        Map<String, Object> coverage = objectMapper.convertValue(schemaJson.path("evidenceCoverage"), Map.class);
        return CompetitorKnowledgeDraft.builder()
                .competitorName(competitorName)
                .officialUrl(schemaJson.path("officialUrl").asText(null))
                .summary(schemaJson.path("summary").asText(null))
                .positioning(schemaJson.path("positioning").asText(null))
                .targetUsers(readStringList(schemaJson.path("targetUsers")))
                .coreFeatures(convertValue(schemaJson.path("coreFeatures"), FeatureItem.class))
                .pricing(convertSingleValue(schemaJson.path("pricing"), PricingItem.class))
                .strengths(convertValue(schemaJson.path("strengths"), StrengthWeaknessItem.class))
                .weaknesses(convertValue(schemaJson.path("weaknesses"), StrengthWeaknessItem.class))
                .sourceUrls(readStringList(schemaJson.path("sourceUrls")))
                .evidenceFragments(normalizeEvidenceFragments(normalizedSchema.evidenceFragments()))
                .sectionEvidenceBundles(normalizeSectionEvidenceBundles(normalizedSchema.sectionEvidenceBundles()))
                .issueFlags(normalizedSchema.issueFlags())
                .evidenceCoverage(coverage)
                .fieldsExtracted(countExtractedFields(schemaJson))
                .status(normalizedSchema.issueFlags().contains("MISSING_EVIDENCE") ? "PARTIAL" : "TRACEABLE")
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

    /**
     * 真实模型偶尔会在 JSON 前后夹带说明文本，这里尽量截出最外层对象主体；
     * 如果模型输出本身不完整，后续 readTree 会继续抛错并触发重试。
     */
    private String extractJsonObject(String cleanedResponse) {
        int start = cleanedResponse.indexOf('{');
        int end = cleanedResponse.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleanedResponse.substring(start, end + 1);
        }
        return cleanedResponse;
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

    private boolean isUsableEvidence(EvidenceSource evidence) {
        if (evidence == null) {
            return false;
        }
        boolean hasContent = evidence.getFullContent() != null && !evidence.getFullContent().isBlank();
        boolean hasSnippet = evidence.getContentSnippet() != null && !evidence.getContentSnippet().isBlank();
        return hasContent || hasSnippet;
    }

    private List<String> collectCoverageIssueFlags(ObjectNode coverage) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (coverage == null) {
            return List.of();
        }
        coverage.fields().forEachRemaining(entry -> {
            String status = entry.getValue().path("status").asText("");
            if ("MISSING_EVIDENCE".equalsIgnoreCase(status)) {
                issueFlags.add("MISSING_EVIDENCE");
            }
        });
        return new ArrayList<>(issueFlags);
    }

    /**
     * 抽取阶段统一把 sources 节点转成 EvidenceFragment，后续分析与写作都只需要消费这一种证据格式。
     */
    private List<EvidenceFragment> buildEvidenceFragments(String stage,
                                                          List<EvidenceSource> competitorEvidence,
                                                          LinkedHashSet<String> inheritedIssueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (EvidenceSource evidence : competitorEvidence) {
            fragments.add(EvidenceFragment.builder()
                    .stage(stage)
                    .competitorName(evidence.getCompetitorName())
                    .fieldName("knowledge")
                    .evidenceId(evidence.getEvidenceId())
                    .sourceUrl(evidence.getUrl())
                    .title(evidence.getTitle())
                    .snippet(truncate(evidence.getContentSnippet(), 180))
                    .issueFlags(new ArrayList<>(inheritedIssueFlags))
                    .build()
                    .normalized());
        }
        return fragments;
    }

    private <T> List<T> convertValue(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(objectMapper.convertValue(item, type));
        }
        return values;
    }

    private <T> T convertSingleValue(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(node, type);
    }

    private int countExtractedFields(ObjectNode schemaJson) {
        int count = 0;
        for (String field : COVERAGE_FIELDS) {
            if (hasMeaningfulValue(schemaJson.path(field))) {
                count++;
            }
        }
        return count;
    }

    private List<EvidenceFragment> normalizeEvidenceFragments(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments) {
            if (fragment != null) {
                normalized.add(fragment.normalized());
            }
        }
        return normalized;
    }

    private List<SectionEvidenceBundle> normalizeSectionEvidenceBundles(List<SectionEvidenceBundle> bundles) {
        List<SectionEvidenceBundle> normalized = new ArrayList<>();
        for (SectionEvidenceBundle bundle : bundles) {
            if (bundle != null) {
                normalized.add(bundle.normalized());
            }
        }
        return normalized;
    }

    /**
     * 根据字段级 coverage 组装章节证据束。
     * 这里显式把“字段状态 + evidenceId/sourceUrl + 缺口说明”一起压入 bundle，
     * 后续分析、写作、报告接口就不需要再猜某个章节到底缺的是值还是缺的是证据。
     */
    private List<SectionEvidenceBundle> buildSectionEvidenceBundles(String competitorName,
                                                                   ObjectNode schemaJson,
                                                                   ObjectNode coverage,
                                                                   Map<String, EvidenceSource> evidenceById) {
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (CoverageFieldDefinition definition : COVERAGE_FIELD_DEFINITIONS) {
            JsonNode coverageNode = coverage.path(definition.fieldKey());
            String status = coverageNode.path("status").asText("EMPTY");
            List<String> evidenceIds = readStringList(coverageNode.path("evidenceIds"));
            List<String> sourceUrls = readStringList(coverageNode.path("sourceUrls"));
            List<EvidenceFragment> fieldFragments = buildFieldEvidenceFragments(
                    "EXTRACT",
                    competitorName,
                    definition,
                    status,
                    evidenceIds,
                    sourceUrls,
                    evidenceById
            );
            List<String> missingFields = "TRACEABLE".equalsIgnoreCase(status) ? List.of() : List.of(definition.fieldKey());
            bundles.add(SectionEvidenceBundle.builder()
                    .stage("EXTRACT")
                    .sectionType("SECTION")
                    .sectionKey(definition.fieldKey())
                    .sectionTitle(definition.sectionTitle())
                    .summary(summarizeFieldValue(schemaJson.path(definition.fieldKey())))
                    .fieldNames(List.of(definition.fieldKey()))
                    .missingFields(missingFields)
                    .sourceUrls(sourceUrls)
                    .issueFlags(missingFields.isEmpty() ? List.of() : List.of("SECTION_EVIDENCE_GAP"))
                    .evidenceFragments(fieldFragments)
                    .build()
                    .normalized());
        }
        return bundles;
    }

    private List<EvidenceFragment> buildFieldEvidenceFragments(String stage,
                                                               String competitorName,
                                                               CoverageFieldDefinition definition,
                                                               String status,
                                                               List<String> evidenceIds,
                                                               List<String> sourceUrls,
                                                               Map<String, EvidenceSource> evidenceById) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        if (!evidenceIds.isEmpty()) {
            for (String evidenceId : evidenceIds) {
                EvidenceSource matched = evidenceById.get(evidenceId);
                String sourceUrl = matched != null ? matched.getUrl() : sourceUrls.stream().findFirst().orElse(null);
                fragments.add(EvidenceFragment.builder()
                        .stage(stage)
                        .competitorName(competitorName)
                        .fieldName(definition.fieldKey())
                        .fieldLabel(definition.sectionTitle())
                        .sectionKey(definition.fieldKey())
                        .sectionTitle(definition.sectionTitle())
                        .coverageStatus(status)
                        .evidenceId(evidenceId)
                        .sourceUrl(sourceUrl)
                        .title(matched == null ? null : matched.getTitle())
                        .snippet(matched == null ? null : truncate(matched.getContentSnippet(), 180))
                        .issueFlags("TRACEABLE".equalsIgnoreCase(status) ? List.of() : List.of(status))
                        .gapComment("TRACEABLE".equalsIgnoreCase(status) ? null : "字段缺少稳定证据支撑")
                        .build()
                        .normalized());
            }
            return fragments;
        }
        if (!sourceUrls.isEmpty()) {
            for (String sourceUrl : sourceUrls) {
                fragments.add(EvidenceFragment.builder()
                        .stage(stage)
                        .competitorName(competitorName)
                        .fieldName(definition.fieldKey())
                        .fieldLabel(definition.sectionTitle())
                        .sectionKey(definition.fieldKey())
                        .sectionTitle(definition.sectionTitle())
                        .coverageStatus(status)
                        .sourceUrl(sourceUrl)
                        .issueFlags("TRACEABLE".equalsIgnoreCase(status) ? List.of() : List.of(status))
                        .gapComment("TRACEABLE".equalsIgnoreCase(status) ? null : "字段缺少稳定证据支撑")
                        .build()
                        .normalized());
            }
            return fragments;
        }
        fragments.add(EvidenceFragment.builder()
                .stage(stage)
                .competitorName(competitorName)
                .fieldName(definition.fieldKey())
                .fieldLabel(definition.sectionTitle())
                .sectionKey(definition.fieldKey())
                .sectionTitle(definition.sectionTitle())
                .coverageStatus(status)
                .issueFlags("TRACEABLE".equalsIgnoreCase(status) ? List.of() : List.of(status))
                .gapComment("EMPTY".equalsIgnoreCase(status) ? "字段暂无内容" : "字段缺少稳定证据支撑")
                .build()
                .normalized());
        return fragments;
    }

    private String summarizeFieldValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return truncate(node.asText(), 120);
        }
        if (node.isArray() || node.isObject()) {
            return truncate(node.toString(), 120);
        }
        return truncate(node.asText(), 120);
    }

    private String competitorNameFromEvidence(List<EvidenceSource> competitorEvidence) {
        if (competitorEvidence == null || competitorEvidence.isEmpty()) {
            return null;
        }
        return competitorEvidence.get(0).getCompetitorName();
    }

    private record NormalizedSchema(ObjectNode schema,
                                    List<String> issueFlags,
                                    List<EvidenceFragment> evidenceFragments,
                                    List<SectionEvidenceBundle> sectionEvidenceBundles) {
    }

    private record CoverageFieldDefinition(String fieldKey, String sectionTitle) {
    }

    private record ParsedSchemaRoot(ObjectNode objectNode, List<String> issueFlags) {
        private boolean recovered() {
            return issueFlags != null && !issueFlags.isEmpty();
        }

        private ParsedSchemaRoot appendIssueFlag(String issueFlag) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(issueFlags == null ? List.of() : issueFlags);
            if (issueFlag != null && !issueFlag.isBlank()) {
                merged.add(issueFlag);
            }
            return new ParsedSchemaRoot(objectNode, new ArrayList<>(merged));
        }
    }
}
