package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.extractor.input.ExtractorCompetitorInput;
import cn.bugstack.competitoragent.extractor.input.ExtractorEvidenceInput;
import cn.bugstack.competitoragent.extractor.input.ExtractorEvidenceInputAssembler;
import cn.bugstack.competitoragent.extractor.input.ExtractorInputPackage;
import cn.bugstack.competitoragent.extractor.input.ExtractorInputProvider;
import cn.bugstack.competitoragent.extractor.input.RepositoryExtractorEvidenceSourcePort;
import cn.bugstack.competitoragent.extractor.input.RepositoryExtractorInputProvider;
import cn.bugstack.competitoragent.extractor.ExtractSharedOutputSanitizer;
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
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceViewAssembler;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String ZERO_BUSINESS_FIELDS_ISSUE_FLAG = "NO_BUSINESS_FIELDS_EXTRACTED";
    private static final String ZERO_BUSINESS_FIELDS_MESSAGE = "未能抽取出任何业务字段，请检查提取提示词或模型输出";
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
    private static final List<String> LLM_REFUSAL_MARKERS = List.of(
            "当前公开资料未能验证",
            "公开资料未能验证",
            "无法判断",
            "无法确认",
            "暂无公开信息",
            "资料不足",
            "未披露",
            "not enough information",
            "insufficient information",
            "cannot determine",
            "unable to determine"
    );

    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;
    private final DownstreamEvidenceViewAssembler downstreamEvidenceViewAssembler;
    private final ExtractorInputProvider extractorInputProvider;

    public SchemaExtractorAgent(AgentExecutionLogRepository logRepository,
                                EvidenceSourceRepository evidenceRepository,
                                CompetitorKnowledgeRepository knowledgeRepository,
                                LlmClient llmClient,
                                PromptTemplateService promptService,
                                AgentContextAssembler agentContextAssembler,
                                ObjectMapper objectMapper) {
        this(logRepository,
                evidenceRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                null);
    }

    @Autowired
    public SchemaExtractorAgent(AgentExecutionLogRepository logRepository,
                                EvidenceSourceRepository evidenceRepository,
                                CompetitorKnowledgeRepository knowledgeRepository,
                                LlmClient llmClient,
                                PromptTemplateService promptService,
                                AgentContextAssembler agentContextAssembler,
                                ObjectMapper objectMapper,
                                DownstreamEvidenceViewAssembler downstreamEvidenceViewAssembler,
                                ExtractorInputProvider extractorInputProvider) {
        super(logRepository, agentContextAssembler);
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
        this.downstreamEvidenceViewAssembler = downstreamEvidenceViewAssembler == null
                ? new DownstreamEvidenceViewAssembler(objectMapper)
                : downstreamEvidenceViewAssembler;
        this.extractorInputProvider = extractorInputProvider == null
                ? new RepositoryExtractorInputProvider(
                new RepositoryExtractorEvidenceSourcePort(
                        evidenceRepository,
                        new ExtractorEvidenceInputAssembler(objectMapper)),
                objectMapper)
                : extractorInputProvider;
    }

    public SchemaExtractorAgent(AgentExecutionLogRepository logRepository,
                                CompetitorKnowledgeRepository knowledgeRepository,
                                LlmClient llmClient,
                                PromptTemplateService promptService,
                                AgentContextAssembler agentContextAssembler,
                                ObjectMapper objectMapper,
                                ExtractorInputProvider extractorInputProvider) {
        this(logRepository,
                null,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                new DownstreamEvidenceViewAssembler(objectMapper),
                extractorInputProvider);
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
        ExtractorInputPackage inputPackage = extractorInputProvider.provide(context);
        List<ExtractorCompetitorInput> competitorInputs = inputPackage == null || inputPackage.getCompetitors() == null
                ? List.of()
                : inputPackage.getCompetitors();
        if (competitorInputs.isEmpty()) {
            return AgentResult.failed("暂无可用于抽取的证据来源");
        }
        List<Map<String, Object>> extractionSummaries = new ArrayList<>();
        List<CompetitorKnowledgeDraft> drafts = new ArrayList<>();
        LinkedHashSet<String> aggregatedSourceUrls = new LinkedHashSet<>();
        LinkedHashSet<String> aggregatedIssueFlags = new LinkedHashSet<>();
        List<EvidenceFragment> aggregatedFragments = new ArrayList<>();
        List<SectionEvidenceBundle> aggregatedSectionBundles = new ArrayList<>();
        List<DownstreamEvidenceView> aggregatedEvidenceViews = new ArrayList<>();
        int successCount = 0;
        int zeroBusinessFieldFailures = 0;

        for (ExtractorCompetitorInput competitorInput : competitorInputs) {
            String competitorName = competitorInput == null ? "" : safe(competitorInput.getCompetitorName());

            try {
                NormalizedSchema normalizedSchema = extractAndNormalize(
                        context,
                        inputPackage,
                        competitorInput
                );
                // sourceUrls 回填只能保证结果可追溯，不能代表模型已经抽出真实业务字段；0 字段必须阻断下游。
                int extractedFieldCount = countExtractedFields(normalizedSchema.schema());
                if (extractedFieldCount == 0) {
                    zeroBusinessFieldFailures++;
                    LinkedHashSet<String> issueFlags = new LinkedHashSet<>(normalizedSchema.issueFlags());
                    issueFlags.add(ZERO_BUSINESS_FIELDS_ISSUE_FLAG);
                    log.warn("extractor produced zero business fields, taskId={}, competitor={}",
                            context.getTaskId(), competitorName);
                    extractionSummaries.add(Map.of(
                            "competitor", competitorName,
                            "error", ZERO_BUSINESS_FIELDS_MESSAGE,
                            "sourceUrls", readStringList(normalizedSchema.schema().path("sourceUrls")),
                            "coverage", objectMapper.convertValue(normalizedSchema.schema().path("evidenceCoverage"), Map.class),
                            "issueFlags", new ArrayList<>(issueFlags),
                            "downstreamEvidenceViews", slimDownstreamEvidenceViewsForSharedOutput(normalizedSchema.downstreamEvidenceViews())
                    ));
                    continue;
                }
                CompetitorKnowledge knowledge = buildKnowledge(context, competitorName, normalizedSchema.schema());
                knowledgeRepository.save(knowledge);

                extractionSummaries.add(Map.of(
                        "competitor", competitorName,
                        "sourceUrls", readStringList(normalizedSchema.schema().path("sourceUrls")),
                        "coverage", objectMapper.convertValue(normalizedSchema.schema().path("evidenceCoverage"), Map.class),
                        "issueFlags", normalizedSchema.issueFlags(),
                        "downstreamEvidenceViews", slimDownstreamEvidenceViewsForSharedOutput(normalizedSchema.downstreamEvidenceViews())
                ));
                CompetitorKnowledgeDraft draft = buildKnowledgeDraft(competitorName, normalizedSchema);
                drafts.add(draft);
                aggregatedSourceUrls.addAll(draft.getSourceUrls());
                aggregatedIssueFlags.addAll(draft.getIssueFlags());
                aggregatedFragments.addAll(draft.getEvidenceFragments());
                aggregatedSectionBundles.addAll(draft.getSectionEvidenceBundles());
                aggregatedEvidenceViews.addAll(draft.getDownstreamEvidenceViews());
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
            if (zeroBusinessFieldFailures > 0) {
                return AgentResult.failed(ZERO_BUSINESS_FIELDS_MESSAGE);
            }
            return AgentResult.failed("未能抽取出可用的竞品知识");
        }

        try {
            ExtractResult extractResult = ExtractResult.builder()
                    .totalCompetitors(successCount)
                    .drafts(slimDraftsForSharedOutput(drafts))
                    .sourceUrls(new ArrayList<>(aggregatedSourceUrls))
                    .issueFlags(new ArrayList<>(aggregatedIssueFlags))
                    .evidenceFragments(slimEvidenceFragmentsForSharedOutput(aggregatedFragments))
                    .sectionEvidenceBundles(slimSectionEvidenceBundlesForSharedOutput(aggregatedSectionBundles))
                    .downstreamEvidenceViews(slimDownstreamEvidenceViewsForSharedOutput(aggregatedEvidenceViews))
                    .build();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("contractVersion", extractResult.getContractVersion());
            output.put("totalCompetitors", competitorInputs.size());
            output.put("successCount", successCount);
            output.put("results", slimResultSummariesForSharedOutput(extractionSummaries));
            output.put("drafts", extractResult.getDrafts());
            output.put("sourceUrls", extractResult.getSourceUrls());
            // 统一把本次实际消费的 Task RAG 摘要写回节点输出，避免后续只能从 prompt 日志倒推。
            output.put("taskRagContext", context.getTaskRagPromptContext());
            output.put("issueFlags", extractResult.getIssueFlags());
            output.put("evidenceFragments", extractResult.getEvidenceFragments());
            output.put("sectionEvidenceBundles", extractResult.getSectionEvidenceBundles());
            output.put("downstreamEvidenceViews", extractResult.getDownstreamEvidenceViews());
            output.put("extractorInput", slimExtractorInputForSharedOutput(inputPackage));
            String outputJson = objectMapper.writeValueAsString(output);
            return AgentResult.success(outputJson,
                    "已抽取竞品知识：" + successCount + "/" + competitorInputs.size());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("抽取结果序列化失败：" + e.getMessage());
        }
    }

    /**
     * 提示词分层注入证据目录、结构化块、质量信号和正文兜底区，
     * 让模型先消费高置信结构化证据，再在必要时回退到可读正文。
     */
    private NormalizedSchema extractAndNormalize(AgentContext context,
                                                 ExtractorInputPackage inputPackage,
                                                 ExtractorCompetitorInput competitorInput)
            throws LlmException, JsonProcessingException {
        String competitorName = competitorInput == null ? "" : safe(competitorInput.getCompetitorName());
        List<ExtractorEvidenceInput> evidenceCatalog = normalizeEvidenceInputs(
                competitorInput == null ? List.of() : competitorInput.getEvidenceCatalog());
        List<ExtractorEvidenceInput> structuredEvidenceInputs = normalizeEvidenceInputs(
                competitorInput == null ? List.of() : competitorInput.getStructuredEvidence());
        List<ExtractorEvidenceInput> readableEvidenceInputs = normalizeEvidenceInputs(
                competitorInput == null ? List.of() : competitorInput.getReadableEvidence());
        Map<String, String> promptVariables = new LinkedHashMap<>();
        promptVariables.put("competitorName", competitorName);
        promptVariables.put("schemaGuidance", buildSchemaGuidance(inputPackage, context == null ? null : context.getCurrentNodeConfig()));
        promptVariables.put("fieldExtractionGuidance", buildFieldExtractionGuidance());
        promptVariables.put("evidenceCatalog", buildEvidenceCatalog(evidenceCatalog));
        promptVariables.put("structuredEvidence", buildStructuredEvidence(structuredEvidenceInputs));
        promptVariables.put("qualitySignalGuidance", buildQualitySignalGuidance(evidenceCatalog));
        promptVariables.put("readableContent", buildReadableContent(readableEvidenceInputs));
        // 迁移期继续保留 collectedContent，但内容直接来自 extractor 内部输入投影，不再回转旧统一视图。
        promptVariables.put("collectedContent", buildCollectedContent(evidenceCatalog));
        // 统一任务上下文在这里透传给 Prompt，保证提取阶段也能看到检索结论与缺口说明。
        promptVariables.put("taskRagContext", context == null ? "当前暂无检索上下文。" : context.getTaskRagPromptContext());
        String prompt = promptService.render("extractor", promptVariables);

        NormalizedSchema firstPass = invokeExtractorOnce(
                competitorName,
                evidenceCatalog,
                prompt,
                false
        );
        if (countExtractedFields(firstPass.schema()) > 0 || !hasReadableEvidenceContent(readableEvidenceInputs)) {
            return firstPass;
        }
        // 只有“JSON 合法但 0 业务字段，且正文确实可读”时，才补一次语义重试，
        // 避免结构块-only 或薄正文场景无效放大 LLM 调用次数。
        log.warn("extractor produced zero business fields in first pass, retrying with strict business instruction, competitor={}",
                competitorName);
        return invokeExtractorOnce(
                competitorName,
                evidenceCatalog,
                prompt,
                true
        );
    }

    /**
     * 单次业务调用内部保留 JSON 修复重试；只有外层明确要求时，才叠加“业务字段补抽”指令。
     */
    private NormalizedSchema invokeExtractorOnce(String competitorName,
                                                 List<ExtractorEvidenceInput> evidenceInputs,
                                                 String prompt,
                                                 boolean strictBusinessRetry)
            throws LlmException, JsonProcessingException {
        JsonProcessingException lastParseException = null;
        String businessRetryInstruction = strictBusinessRetry
                ? "\n\n【业务字段补抽要求】上一轮 JSON 合法但没有抽出任何业务字段。请优先根据结构化证据和可读正文补出 summary、positioning、targetUsers、coreFeatures、pricing、strengths、weaknesses 中至少一个非空字段。sourceUrls 只能作为追溯信息，不能算作业务字段。"
                : "";

        for (int attempt = 1; attempt <= EXTRACT_JSON_MAX_ATTEMPTS; attempt++) {
            String attemptPrompt = attempt == 1
                    ? prompt + businessRetryInstruction
                    : prompt + businessRetryInstruction
                    + "\n\n【补充要求】上一次返回的 JSON 解析失败，请重新输出一个完整、闭合、合法的 JSON 对象，不要附加解释。";
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
                return normalizeSchema(parsedRoot.objectNode(), evidenceInputs, parsedRoot.issueFlags());
            } catch (JsonProcessingException e) {
                lastParseException = e;
                log.warn("extractor json parse failed, competitor={}, attempt={}/{}",
                        competitorName, attempt, EXTRACT_JSON_MAX_ATTEMPTS, e);
            }
        }

        throw lastParseException == null
                ? new JsonProcessingException("模型未返回可解析 JSON") { }
                : lastParseException;
    }

    /**
     * 这里统一补齐 sourceUrls 与 evidenceCoverage，避免字段级溯源完全依赖模型自觉输出。
     */
    private NormalizedSchema normalizeSchema(ObjectNode schemaJson,
                                             List<ExtractorEvidenceInput> evidenceInputs,
                                             List<String> inheritedIssueFlags) {
        Map<String, ExtractorEvidenceInput> evidenceById = new LinkedHashMap<>();
        for (ExtractorEvidenceInput evidence : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
            if (evidence != null) {
                evidenceById.put(evidence.getEvidenceId(), evidence);
            }
        }
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (inheritedIssueFlags != null) {
            issueFlags.addAll(inheritedIssueFlags);
        }

        ArrayNode sources = buildSourcesNode(schemaJson.path("sources"), evidenceInputs);
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
            for (ExtractorEvidenceInput evidenceInput : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
                if (evidenceInput != null && evidenceInput.getSourceUrls() != null) {
                    for (String sourceUrl : evidenceInput.getSourceUrls()) {
                        if (sourceUrl != null && !sourceUrl.isBlank()) {
                            topLevelSourceUrls.add(sourceUrl.trim());
                        }
                    }
                }
            }
        }
        if (!modelProvidedSourceUrls && !topLevelSourceUrls.isEmpty()) {
            issueFlags.add("SOURCE_URLS_BACKFILLED");
        }

        schemaJson.set("sourceUrls", buildStringArray(topLevelSourceUrls));
        normalizeStrengthWeaknessArrayField(schemaJson, "strengths", topLevelSourceUrls);
        normalizeStrengthWeaknessArrayField(schemaJson, "weaknesses", topLevelSourceUrls);
        ObjectNode coverage = buildCoverage(schemaJson, evidenceById, topLevelSourceUrls, modelProvidedSourceUrls);
        schemaJson.set("evidenceCoverage", coverage);
        issueFlags.addAll(collectCoverageIssueFlags(coverage));
        List<EvidenceFragment> evidenceFragments = buildEvidenceFragments("EXTRACT", evidenceInputs, issueFlags);
        List<SectionEvidenceBundle> sectionEvidenceBundles = buildSectionEvidenceBundles(
                competitorNameFromEvidence(evidenceInputs),
                schemaJson,
                coverage,
                evidenceById
        );
        return new NormalizedSchema(
                schemaJson,
                new ArrayList<>(issueFlags),
                evidenceFragments,
                sectionEvidenceBundles,
                buildDownstreamEvidenceViews(evidenceInputs));
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

    private ArrayNode buildSourcesNode(JsonNode existingSources, List<ExtractorEvidenceInput> evidenceInputs) {
        if (existingSources != null && existingSources.isArray() && !existingSources.isEmpty()) {
            return (ArrayNode) existingSources.deepCopy();
        }

        ArrayNode sources = objectMapper.createArrayNode();
        for (ExtractorEvidenceInput evidence : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
            if (evidence == null) {
                continue;
            }
            ObjectNode sourceNode = objectMapper.createObjectNode();
            sourceNode.put("evidenceId", safe(evidence.getEvidenceId()));
            sourceNode.put("title", safe(evidence.getTitle()));
            sourceNode.put("url", safe(firstSourceUrl(evidence)));
            sources.add(sourceNode);
        }
        return sources;
    }

    /**
     * 对关键字段逐个生成溯源覆盖摘要，供前端、质检和导出阶段直接复用。
     */
    private ObjectNode buildCoverage(ObjectNode schemaJson,
                                     Map<String, ExtractorEvidenceInput> evidenceById,
                                     Set<String> topLevelSourceUrls,
                                     boolean modelProvidedSourceUrls) {
        ObjectNode coverage = objectMapper.createObjectNode();
        for (String fieldName : COVERAGE_FIELDS) {
            JsonNode fieldNode = schemaJson.path(fieldName);
            ObjectNode fieldCoverage = objectMapper.createObjectNode();

            List<String> evidenceIds = collectEvidenceIds(fieldNode);
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(collectSourceUrls(fieldNode));
            for (String evidenceId : evidenceIds) {
                ExtractorEvidenceInput matched = evidenceById.get(evidenceId);
                if (matched != null && matched.getSourceUrls() != null) {
                    for (String sourceUrl : matched.getSourceUrls()) {
                        if (sourceUrl != null && !sourceUrl.isBlank()) {
                            sourceUrls.add(sourceUrl.trim());
                        }
                    }
                }
            }

            boolean hasValue = hasMeaningfulValue(fieldNode);
            boolean llmRefused = isLlmRefusalValue(fieldNode);
            // 当本轮抽取只消费了一条证据且字段已有业务值时，可以保守回填这一条来源链接，
            // 避免 task 50 这类“单证据输入但模型忘记写字段级 sourceUrls”被误判成缺证据。
            if (hasValue && evidenceIds.isEmpty() && sourceUrls.isEmpty()) {
                sourceUrls.addAll(resolveSingleConsumedSourceUrls(evidenceById));
            }
            if (hasValue && evidenceIds.isEmpty() && sourceUrls.isEmpty()
                    && modelProvidedSourceUrls && isScalarCoverageField(fieldName)) {
                // summary / positioning / targetUsers 常由多条证据综合生成；
                // 只有模型明确给过顶层 sourceUrls 时，才把它视作字段级来源，系统兜底回填的 sourceUrls 仍保留缺证据信号。
                sourceUrls.addAll(topLevelSourceUrls == null ? List.of() : topLevelSourceUrls);
            }
            String status = resolveCoverageStatus(fieldName, hasValue, llmRefused, evidenceIds, sourceUrls, evidenceById);

            fieldCoverage.put("status", status);
            fieldCoverage.put("hasValue", hasValue);
            fieldCoverage.set("evidenceIds", buildStringArray(evidenceIds));
            fieldCoverage.set("sourceUrls", buildStringArray(sourceUrls));
            coverage.set(fieldName, fieldCoverage);
        }
        return coverage;
    }

    /**
     * evidenceCoverage 需要把“字段为空、模型拒答、证据不覆盖、普通可追溯、结构块直出”拆开，
     * 这样 reviewer 才能在 P2 阶段继续判断到底该补证据、重跑抽取，还是接受保守降级。
     */
    private String resolveCoverageStatus(String fieldName,
                                         boolean hasValue,
                                         boolean llmRefused,
                                         List<String> evidenceIds,
                                         Set<String> sourceUrls,
                                         Map<String, ExtractorEvidenceInput> evidenceById) {
        if (llmRefused) {
            return "LLM_REFUSED";
        }
        if (!hasValue) {
            return fieldHasCoverageSignal(fieldName, evidenceById.values())
                    ? "EMPTY"
                    : "EVIDENCE_NOT_COVERING";
        }
        if (evidenceIds.isEmpty() && sourceUrls.isEmpty()) {
            return "MISSING_EVIDENCE";
        }
        if (isStructuredBlockDirectCoverage(evidenceIds, sourceUrls, evidenceById)) {
            return "STRUCTURED_BLOCK_DIRECT";
        }
        return "TRACEABLE";
    }

    private List<String> resolveSingleConsumedSourceUrls(Map<String, ExtractorEvidenceInput> evidenceById) {
        if (evidenceById == null || evidenceById.size() != 1) {
            return List.of();
        }
        ExtractorEvidenceInput onlyEvidence = evidenceById.values().iterator().next();
        if (onlyEvidence == null || onlyEvidence.getSourceUrls() == null || onlyEvidence.getSourceUrls().isEmpty()) {
            return List.of();
        }
        List<String> normalizedSourceUrls = new ArrayList<>();
        for (String sourceUrl : onlyEvidence.getSourceUrls()) {
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                normalizedSourceUrls.add(sourceUrl.trim());
            }
        }
        return normalizedSourceUrls;
    }

    private boolean isStructuredBlockDirectCoverage(List<String> evidenceIds,
                                                    Set<String> sourceUrls,
                                                    Map<String, ExtractorEvidenceInput> evidenceById) {
        if ((evidenceIds == null || evidenceIds.isEmpty()) && (sourceUrls == null || sourceUrls.isEmpty())) {
            return false;
        }
        if (evidenceIds != null && !evidenceIds.isEmpty()) {
            for (String evidenceId : evidenceIds) {
                ExtractorEvidenceInput matched = evidenceById.get(evidenceId);
                if (!hasStructuredBlocks(matched) || hasReadableEvidenceText(matched)) {
                    return false;
                }
            }
            return true;
        }
        for (ExtractorEvidenceInput evidence : evidenceById.values()) {
            if (!intersectsSourceUrls(evidence, sourceUrls)) {
                continue;
            }
            if (!hasStructuredBlocks(evidence) || hasReadableEvidenceText(evidence)) {
                return false;
            }
        }
        return true;
    }

    private boolean fieldHasCoverageSignal(String fieldName, Iterable<ExtractorEvidenceInput> evidences) {
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            String normalized = buildEvidenceSignalText(evidence);
            switch (fieldName) {
                case "pricing" -> {
                    if (containsAny(normalized, List.of("pricing", "price", "plan", "billing", "subscription", "quote", "定价", "价格", "套餐", "计费"))) {
                        return true;
                    }
                }
                case "weaknesses" -> {
                    if (containsAny(normalized, List.of("weakness", "risk", "limitation", "constraint", "issue", "problem", "warning", "短板", "风险", "限制", "不足", "缺点"))) {
                        return true;
                    }
                }
                case "targetUsers" -> {
                    if (containsAny(normalized, List.of("team", "teams", "enterprise", "business", "developer", "user", "users", "客户", "用户", "团队", "企业"))) {
                        return true;
                    }
                }
                default -> {
                    if (hasReadableEvidenceText(evidence) || hasStructuredBlocks(evidence)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isScalarCoverageField(String fieldName) {
        return "summary".equals(fieldName)
                || "positioning".equals(fieldName)
                || "targetUsers".equals(fieldName);
    }

    private String buildEvidenceSignalText(ExtractorEvidenceInput evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append(safe(evidence.getTitle())).append(' ')
                .append(evidence.getSourceUrls() == null ? List.of() : evidence.getSourceUrls()).append(' ')
                .append(safe(evidence.getSourceType())).append(' ')
                .append(safe(evidence.getContent())).append(' ')
                .append(evidence.getQualitySignals() == null ? List.of() : evidence.getQualitySignals()).append(' ')
                .append(evidence.getStructuredBlocks() == null ? List.of() : evidence.getStructuredBlocks()).append(' ')
                .append(evidence.getStructuredPayload() == null ? Map.of() : evidence.getStructuredPayload());
        return builder.toString().toLowerCase();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        for (String keyword : keywords == null ? List.<String>of() : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStructuredBlocks(ExtractorEvidenceInput evidence) {
        return evidence != null
                && evidence.getStructuredBlocks() != null
                && !evidence.getStructuredBlocks().isEmpty();
    }

    private boolean hasReadableEvidenceText(ExtractorEvidenceInput evidence) {
        if (evidence == null) {
            return false;
        }
        return safe(evidence.getContent()).trim().length() >= 40;
    }

    private void collectReferencedUrls(JsonNode node,
                                       Map<String, ExtractorEvidenceInput> evidenceById,
                                       Set<String> referencedUrls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            // evidenceIds 与 sourceUrls 可能出现在任意嵌套层级，因此需要递归扫描整棵 JSON 树。
            JsonNode evidenceIdsNode = node.get("evidenceIds");
            if (evidenceIdsNode != null && evidenceIdsNode.isArray()) {
                for (JsonNode evidenceIdNode : evidenceIdsNode) {
                    ExtractorEvidenceInput matched = evidenceById.get(evidenceIdNode.asText());
                    if (matched != null && matched.getSourceUrls() != null) {
                        for (String sourceUrl : matched.getSourceUrls()) {
                            if (sourceUrl != null && !sourceUrl.isBlank()) {
                                referencedUrls.add(sourceUrl.trim());
                            }
                        }
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

    private boolean intersectsSourceUrls(ExtractorEvidenceInput evidence, Set<String> sourceUrls) {
        if (evidence == null || evidence.getSourceUrls() == null || sourceUrls == null || sourceUrls.isEmpty()) {
            return false;
        }
        for (String sourceUrl : evidence.getSourceUrls()) {
            if (sourceUrl != null && sourceUrls.contains(sourceUrl)) {
                return true;
            }
        }
        return false;
    }

    private List<String> collectEvidenceIds(JsonNode node) {
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        collectEvidenceIds(node, evidenceIds);
        return new ArrayList<>(evidenceIds);
    }

    private List<String> collectSourceUrls(JsonNode node) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        collectSourceUrls(node, sourceUrls);
        return new ArrayList<>(sourceUrls);
    }

    private void collectSourceUrls(JsonNode node, Set<String> sourceUrls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            JsonNode sourceUrlsNode = node.get("sourceUrls");
            if (sourceUrlsNode != null) {
                appendSourceUrlValues(sourceUrlsNode, sourceUrls);
            }
            node.fields().forEachRemaining(entry -> collectSourceUrls(entry.getValue(), sourceUrls));
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectSourceUrls(item, sourceUrls);
            }
        }
    }

    private void appendSourceUrlValues(JsonNode node, Set<String> sourceUrls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                appendSourceUrlValues(item, sourceUrls);
            }
            return;
        }
        String sourceUrl = coerceStringValue(node, List.of("sourceUrl", "url", "href", "value"));
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            sourceUrls.add(sourceUrl.trim());
        }
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
            return !node.asText().isBlank() && !isLlmRefusalText(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasMeaningfulValue(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            if (isLlmRefusalValue(node)) {
                return false;
            }
            return node.fieldNames().hasNext();
        }
        return true;
    }

    private boolean isLlmRefusalValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return isLlmRefusalText(node.asText());
        }
        if (node.isArray()) {
            boolean hasElement = false;
            for (JsonNode item : node) {
                hasElement = true;
                if (!isLlmRefusalValue(item)) {
                    return false;
                }
            }
            return hasElement;
        }
        if (node.isObject()) {
            for (String key : List.of("reason", "message", "summary", "description", "value")) {
                JsonNode candidate = node.get(key);
                if (candidate != null && isLlmRefusalValue(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLlmRefusalText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase();
        for (String marker : LLM_REFUSAL_MARKERS) {
            if (normalized.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
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

    /**
     * 把结构化块单独拆成高优先级输入区，避免 Prompt 只能从长正文里二次“猜”出定价与功能事实。
     */
    private String buildStructuredEvidence(List<ExtractorEvidenceInput> evidences) throws JsonProcessingException {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null || evidence.getStructuredBlocks() == null || evidence.getStructuredBlocks().isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evidenceId", safe(evidence.getEvidenceId()));
            item.put("title", safe(evidence.getTitle()));
            item.put("sourceUrls", evidence.getSourceUrls() == null ? List.of() : evidence.getSourceUrls());
            item.put("structuredBlocks", evidence.getStructuredBlocks());
            blocks.add(item);
        }
        if (blocks.isEmpty()) {
            return "无结构化证据。请转入正文内容兜底提取，不能因为 structuredBlocks 为空就返回空业务字段。";
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks);
    }

    /**
     * 把 qualitySignals / issueFlags 翻译成明确的提取指令，
     * 减少模型看见信号名称却不知道该如何处理的歧义。
     */
    private String buildQualitySignalGuidance(List<ExtractorEvidenceInput> evidences) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            if (evidence.getQualitySignals() != null) {
                signals.addAll(evidence.getQualitySignals());
            }
            if (evidence.getIssueFlags() != null) {
                issueFlags.addAll(evidence.getIssueFlags());
            }
        }

        List<String> guidance = new ArrayList<>();
        for (String signal : signals) {
            switch (signal) {
                case "PRICING_BLOCK_HIT" -> guidance.add("PRICING_BLOCK_HIT: pricing 字段优先引用命中的定价结构块。");
                case "STRUCTURED_BLOCK_HIT" -> guidance.add("STRUCTURED_BLOCK_HIT: 结构块可作为高置信事实，但字段仍必须保留 evidenceIds 或 sourceUrls。");
                case "LIGHTWEIGHT_CONTENT_READY" -> guidance.add("LIGHTWEIGHT_CONTENT_READY: 正文可作为兜底证据参与 summary、positioning、targetUsers 和 coreFeatures 提取。");
                default -> guidance.add(signal + ": 保留该质量信号，并结合 evidenceId 判断字段可信度。");
            }
        }
        for (String issueFlag : issueFlags) {
            switch (issueFlag) {
                case "CONTENT_GAP", "COLLECT_FAILED", "NO_USABLE_CONTENT" ->
                        guidance.add(issueFlag + ": 该证据存在采集缺口，不要从缺失正文中编造字段。");
                default -> guidance.add(issueFlag + ": 将该问题写入字段缺口或 issueFlags，不要静默忽略。");
            }
        }
        return guidance.isEmpty()
                ? "无显式质量信号。按来源、正文和结构块内容谨慎提取。"
                : String.join("\n", guidance);
    }

    /**
     * 正文区只作为兜底输入；薄正文只记录诊断，不冒充高质量业务证据。
     */
    private String buildReadableContent(List<ExtractorEvidenceInput> evidences) {
        final int maxEvidenceContentLength = 4000;
        StringBuilder readableContent = new StringBuilder();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            String content = evidence.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String normalizedContent = content.trim();
            readableContent.append("--- Source: ")
                    .append(safe(evidence.getEvidenceId()))
                    .append(" ")
                    .append(safe(evidence.getTitle()))
                    .append(" ---\n");
            readableContent.append("sourceUrls: ").append(evidence.getSourceUrls()).append('\n');
            if (normalizedContent.length() < 40
                    && (evidence.getStructuredBlocks() == null || evidence.getStructuredBlocks().isEmpty())) {
                readableContent.append("issueFlags: [THIN_CONTENT_ONLY]\n");
                readableContent.append("该证据正文过薄，仅用于诊断，不应作为业务字段主要依据。\n\n");
                continue;
            }
            readableContent.append(truncateForPrompt(normalizedContent, maxEvidenceContentLength)).append("\n\n");
        }
        if (readableContent.isEmpty()) {
            return "无可读正文。只能使用结构化证据提取，不能补造结构化证据未覆盖的字段。";
        }
        return readableContent.toString();
    }

    private String buildCollectedContent(List<ExtractorEvidenceInput> evidences) {
        final int maxEvidenceContentLength = 4000;
        StringBuilder collectedContent = new StringBuilder();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            collectedContent.append("--- Source: ")
                    .append(safe(evidence.getEvidenceId()))
                    .append(" ")
                    .append(safe(evidence.getTitle()))
                    .append(" ---\n");

            collectedContent.append("sourceUrls: ").append(evidence.getSourceUrls()).append('\n');
            collectedContent.append("qualitySignals: ").append(evidence.getQualitySignals()).append('\n');
            collectedContent.append("issueFlags: ").append(evidence.getIssueFlags()).append('\n');
            if (evidence.getQuality() != null) {
                collectedContent.append("quality: ").append(evidence.getQuality()).append('\n');
            }
            if (evidence.getStructuredBlocks() != null && !evidence.getStructuredBlocks().isEmpty()) {
                collectedContent.append("structuredBlocks: ").append(evidence.getStructuredBlocks()).append('\n');
            }

            String content = evidence.getContent();
            if (content != null && content.length() > maxEvidenceContentLength) {
                // 单条正文只做一次安全截断，结构化质量信号必须保留在正文前面，
                // 同时避免二次 substring 在 4k~8k 区间触发越界，导致真实链路提前失败。
                content = content.substring(0, maxEvidenceContentLength) + "...(truncated)";
            }
            collectedContent.append(content == null ? "" : content).append("\n\n");
        }
        return collectedContent.toString();
    }

    /**
     * 当前节点配置中的 schemaId / dimensions 是 extract_schema 的正式计划语义，
     * 这里直接转成 Prompt 指引，避免 extractor 继续“按默认理解”做宽泛抽取。
     */
    private String buildSchemaGuidance(ExtractorInputPackage inputPackage, String currentNodeConfig) {
        if (inputPackage != null && inputPackage.getSchemaId() != null) {
            List<String> dimensions = inputPackage.getDimensions() == null ? List.of() : inputPackage.getDimensions();
            if (dimensions.isEmpty()) {
                return "schemaId=" + inputPackage.getSchemaId() + "；未提供分析维度。按默认 7 个结构化字段提取。";
            }
            return "schemaId=" + inputPackage.getSchemaId() + "；本次任务分析重点：" + String.join("、", dimensions)
                    + "。请优先提取与这些维度直接相关的字段，并保留 evidenceIds 或 sourceUrls。";
        }
        if (currentNodeConfig == null || currentNodeConfig.isBlank()) {
            return "未提供 schemaId 和 dimensions。按默认 7 个结构化字段提取。";
        }
        try {
            JsonNode config = objectMapper.readTree(currentNodeConfig);
            String schemaId = config.path("schemaId").isMissingNode()
                    ? "UNKNOWN"
                    : config.path("schemaId").asText("UNKNOWN");
            List<String> dimensions = readStringList(config.path("dimensions"));
            if (dimensions.isEmpty()) {
                return "schemaId=" + schemaId + "；未提供分析维度。按默认 7 个结构化字段提取。";
            }
            return "schemaId=" + schemaId + "；本次任务分析重点：" + String.join("、", dimensions)
                    + "。请优先提取与这些维度直接相关的字段，并保留 evidenceIds 或 sourceUrls。";
        } catch (JsonProcessingException e) {
            log.warn("extractor failed to parse currentNodeConfig, currentNodeConfig={}", currentNodeConfig, e);
            return "nodeConfig 解析失败。按默认 7 个结构化字段提取，并保留 sourceUrls。";
        }
    }

    /**
     * 抽取字段说明固定成 7 个业务字段的显式口径，
     * 防止模型把 sourceUrls 或 sources 误当成“已有业务输出”。
     */
    private String buildFieldExtractionGuidance() {
        return """
                summary: 从全部证据归纳产品概述，不超过 200 字，必须有 sourceUrls。
                positioning: 提取市场定位、产品定位或核心价值主张，禁止凭空总结。
                targetUsers: 从用户角色、行业、团队规模和使用场景中提取，返回数组。
                coreFeatures: 每项功能必须带 name、description、evidenceIds 或 sourceUrls。
                pricing: 优先使用 PRICING_BLOCK，提取价格数字、计费周期、免费额度和企业版线索。
                strengths: 只提取证据明确支持的优势判断，不能把营销语直接当事实。
                weaknesses: 只提取证据明确支持的短板、限制或风险，证据不足时返回空数组。
                """;
    }

    private String buildEvidenceCatalog(List<ExtractorEvidenceInput> evidences) throws JsonProcessingException {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidences == null ? List.<ExtractorEvidenceInput>of() : evidences) {
            catalog.add(Map.of(
                    "evidenceId", safe(evidence.getEvidenceId()),
                    "title", safe(evidence.getTitle()),
                    "sourceType", safe(evidence.getSourceType()),
                    "sourceUrls", evidence.getSourceUrls() == null ? List.of() : evidence.getSourceUrls(),
                    "qualitySignals", evidence.getQualitySignals() == null ? List.of() : evidence.getQualitySignals(),
                    "structuredBlockTypes", structuredBlockTypes(evidence.getStructuredBlocks()),
                    "snippet", truncate(evidence.getContent(), 280)
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
                .memoryLayer("TASK")
                .snapshotScope("TASK")
                .producerNodeName(firstNonBlank(context.getCurrentNodeName(), "extract_schema"))
                .planVersionId(context.getPlanVersionId())
                .branchKey(context.getBranchKey())
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
                .versionSource(buildTaskExtractVersionSource(context))
                .invalidationScope("TASK_RERUN")
                .invalidationReason("PLAN_VERSION_CHANGED")
                .extractedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 抽取节点写入的是任务现场快照，版本来源必须绑定当前计划版本，避免被误当成跨任务可复用知识。
     */
    private String buildTaskExtractVersionSource(AgentContext context) {
        if (context.getPlanVersionId() == null) {
            return "TASK_EXTRACT@UNKNOWN";
        }
        return "TASK_EXTRACT@" + context.getPlanVersionId();
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
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
                .downstreamEvidenceViews(normalizeDownstreamEvidenceViews(normalizedSchema.downstreamEvidenceViews()))
                .issueFlags(normalizedSchema.issueFlags())
                .evidenceCoverage(coverage)
                .fieldsExtracted(countExtractedFields(schemaJson))
                .status(hasCoverageGap(coverage) ? "PARTIAL" : "TRACEABLE")
                .build();
    }

    private boolean hasCoverageGap(Map<String, Object> coverage) {
        for (Object raw : coverage == null ? List.of() : coverage.values()) {
            if (!(raw instanceof Map<?, ?> coverageField)) {
                continue;
            }
            Object status = coverageField.get("status");
            if (status != null && !isTraceableCoverageStatus(String.valueOf(status))) {
                return true;
            }
        }
        return false;
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

    /**
     * Prompt 正文兜底区需要保留“已截断”信号，方便后续定位是不是因为输入预算导致字段缺失。
     */
    private String truncateForPrompt(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 语义补抽只在正文长度足够时触发；结构块-only 证据没有新增正文信息，不值得再打一轮同类请求。
     */
    private boolean hasReadableEvidenceContent(List<ExtractorEvidenceInput> evidenceInputs) {
        for (ExtractorEvidenceInput evidenceInput : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
            if (evidenceInput == null || evidenceInput.getContent() == null) {
                continue;
            }
            String normalized = evidenceInput.getContent().trim();
            if (normalized.length() >= 40) {
                return true;
            }
        }
        return false;
    }

    private List<String> collectCoverageIssueFlags(ObjectNode coverage) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (coverage == null) {
            return List.of();
        }
        coverage.fields().forEachRemaining(entry -> {
            String status = entry.getValue().path("status").asText("");
            if (isGapCoverageStatus(status)) {
                issueFlags.add(status.toUpperCase());
            }
        });
        return new ArrayList<>(issueFlags);
    }

    private boolean isGapCoverageStatus(String status) {
        return "MISSING_EVIDENCE".equalsIgnoreCase(status)
                || "LLM_REFUSED".equalsIgnoreCase(status)
                || "EVIDENCE_NOT_COVERING".equalsIgnoreCase(status);
    }

    private boolean isTraceableCoverageStatus(String status) {
        return "TRACEABLE".equalsIgnoreCase(status)
                || "STRUCTURED_BLOCK_DIRECT".equalsIgnoreCase(status);
    }

    /**
     * 抽取阶段统一把 sources 节点转成 EvidenceFragment，后续分析与写作都只需要消费这一种证据格式。
     */
    private List<EvidenceFragment> buildEvidenceFragments(String stage,
                                                          List<ExtractorEvidenceInput> evidenceInputs,
                                                          LinkedHashSet<String> inheritedIssueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (ExtractorEvidenceInput evidence : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
            if (evidence == null) {
                continue;
            }
            fragments.add(EvidenceFragment.builder()
                    .stage(stage)
                    .competitorName(evidence.getCompetitorName())
                    .fieldName("knowledge")
                    .evidenceId(evidence.getEvidenceId())
                    .sourceUrl(firstSourceUrl(evidence))
                    .title(evidence.getTitle())
                    .snippet(truncate(evidence.getContent(), 180))
                    .issueFlags(new ArrayList<>(inheritedIssueFlags))
                    .build()
                    .normalized());
        }
        return fragments;
    }

    /**
     * strengths / weaknesses 是下游强类型契约，但真实模型偶尔会返回字符串数组。
     * 这里把裸字符串规整成 {point, sourceUrls}，并为对象项补齐 sourceUrls，避免 DTO 转换失败后整条抽取链中断。
     */
    private void normalizeStrengthWeaknessArrayField(ObjectNode schemaJson,
                                                     String fieldName,
                                                     Set<String> defaultSourceUrls) {
        JsonNode fieldNode = schemaJson.get(fieldName);
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull() || !fieldNode.isArray()) {
            return;
        }

        ArrayNode normalizedItems = objectMapper.createArrayNode();
        for (JsonNode item : fieldNode) {
            appendNormalizedStrengthWeaknessItem(normalizedItems, item, defaultSourceUrls);
        }
        schemaJson.set(fieldName, normalizedItems);
    }

    private void appendNormalizedStrengthWeaknessItem(ArrayNode target,
                                                      JsonNode item,
                                                      Set<String> defaultSourceUrls) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return;
        }
        if (item.isArray()) {
            for (JsonNode nestedItem : item) {
                appendNormalizedStrengthWeaknessItem(target, nestedItem, defaultSourceUrls);
            }
            return;
        }
        if (item.isObject()) {
            ObjectNode normalized = ((ObjectNode) item).deepCopy();
            backfillStrengthWeaknessPoint(normalized);
            normalizeStringArrayField(normalized, "evidenceIds", List.of("evidenceId", "id", "value"));
            normalizeStringArrayField(normalized, "sourceUrls", List.of("sourceUrl", "url", "href", "value"));
            backfillSourceUrlsIfMissing(normalized, defaultSourceUrls);
            target.add(normalized);
            return;
        }
        String point = item.asText("");
        if (point.isBlank()) {
            return;
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("point", point.trim());
        backfillSourceUrlsIfMissing(normalized, defaultSourceUrls);
        target.add(normalized);
    }

    private void backfillSourceUrlsIfMissing(ObjectNode normalized, Set<String> defaultSourceUrls) {
        if (normalized == null || !collectSourceUrls(normalized).isEmpty()
                || defaultSourceUrls == null || defaultSourceUrls.isEmpty()) {
            return;
        }
        normalized.set("sourceUrls", buildStringArray(defaultSourceUrls));
    }

    private void backfillStrengthWeaknessPoint(ObjectNode normalized) {
        if (normalized == null) {
            return;
        }
        JsonNode pointNode = normalized.path("point");
        if (pointNode.isValueNode() && !pointNode.asText("").isBlank()) {
            return;
        }
        // 真实模型常把优势/短板正文放在 description/detail/content/summary 等字段中；DTO 只读 point，必须在这里统一补齐。
        String point = coerceStringValue(normalized, List.of(
                "description", "name", "title", "value", "text", "detail", "content", "summary"));
        if (point != null && !point.isBlank()) {
            normalized.put("point", point.trim());
        }
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
        JsonNode normalizedNode = normalizeSingleValueNode(unwrapSingleValueNode(node), type);
        if (normalizedNode == null || normalizedNode.isMissingNode() || normalizedNode.isNull() || normalizedNode.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(normalizedNode, type);
    }

    /**
     * 大模型偶尔会把本应为单对象的字段包成数组，甚至出现 [[{...}]] 的嵌套数组。
     * 这里在 DTO 转换前取第一个有效对象，避免一个字段形态漂移导致整个 extractor 节点失败。
     */
    private JsonNode unwrapSingleValueNode(JsonNode node) {
        if (node == null || !node.isArray()) {
            return node;
        }
        for (JsonNode item : node) {
            JsonNode unwrapped = unwrapSingleValueNode(item);
            if (unwrapped != null && !unwrapped.isMissingNode() && !unwrapped.isNull() && !unwrapped.isEmpty()) {
                return unwrapped;
            }
        }
        return null;
    }

    private JsonNode normalizeSingleValueNode(JsonNode node, Class<?> type) {
        if (node == null || !node.isObject() || type != PricingItem.class) {
            return node;
        }
        ObjectNode normalized = ((ObjectNode) node).deepCopy();
        // PricingItem 的 plans/evidenceIds/sourceUrls 都是字符串列表，模型返回对象时先压成可追溯文本再反序列化。
        normalizeStringArrayField(normalized, "plans", List.of("name", "plan", "model", "title", "price", "value"));
        normalizeStringArrayField(normalized, "evidenceIds", List.of("evidenceId", "id", "value"));
        normalizeStringArrayField(normalized, "sourceUrls", List.of("sourceUrl", "url", "href", "value"));
        return normalized;
    }

    private void normalizeStringArrayField(ObjectNode objectNode, String fieldName, List<String> preferredKeys) {
        JsonNode field = objectNode.get(fieldName);
        if (field == null || field.isMissingNode() || field.isNull()) {
            return;
        }
        ArrayNode normalizedValues = objectMapper.createArrayNode();
        appendStringValues(normalizedValues, field, preferredKeys);
        objectNode.set(fieldName, normalizedValues);
    }

    private void appendStringValues(ArrayNode target, JsonNode node, List<String> preferredKeys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                appendStringValues(target, item, preferredKeys);
            }
            return;
        }
        String value = coerceStringValue(node, preferredKeys);
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private String coerceStringValue(JsonNode node, List<String> preferredKeys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("");
        }
        if (node.isObject()) {
            for (String key : preferredKeys == null ? List.<String>of() : preferredKeys) {
                JsonNode candidate = node.path(key);
                if (candidate.isValueNode() && !candidate.asText("").isBlank()) {
                    return candidate.asText("");
                }
            }
        }
        return node.toString();
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

    private List<DownstreamEvidenceView> normalizeDownstreamEvidenceViews(List<DownstreamEvidenceView> views) {
        List<DownstreamEvidenceView> normalized = new ArrayList<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            if (view != null) {
                normalized.add(view.normalized());
            }
        }
        return normalized;
    }

    /**
     * extract_schema 节点自己的 outputData 也要遵守 shared projection 的轻量边界，
     * 这样 TaskNode.outputData、sharedState 和恢复快照看到的都是同一份事实视图。
     */
    private List<DownstreamEvidenceView> slimDownstreamEvidenceViewsForSharedOutput(List<DownstreamEvidenceView> views) {
        return ExtractSharedOutputSanitizer.slimEvidenceViews(normalizeDownstreamEvidenceViews(views));
    }

    private List<CompetitorKnowledgeDraft> slimDraftsForSharedOutput(List<CompetitorKnowledgeDraft> drafts) {
        return ExtractSharedOutputSanitizer.slimDrafts(drafts);
    }

    private List<Map<String, Object>> slimResultSummariesForSharedOutput(List<Map<String, Object>> results) {
        return ExtractSharedOutputSanitizer.slimResultSummaries(results, objectMapper);
    }

    private ExtractorInputPackage slimExtractorInputForSharedOutput(ExtractorInputPackage inputPackage) {
        return ExtractSharedOutputSanitizer.slimExtractorInputPackage(inputPackage);
    }

    private List<EvidenceFragment> slimEvidenceFragmentsForSharedOutput(List<EvidenceFragment> fragments) {
        return ExtractSharedOutputSanitizer.slimEvidenceFragments(normalizeEvidenceFragments(fragments));
    }

    private List<SectionEvidenceBundle> slimSectionEvidenceBundlesForSharedOutput(List<SectionEvidenceBundle> bundles) {
        return ExtractSharedOutputSanitizer.slimSectionEvidenceBundles(normalizeSectionEvidenceBundles(bundles));
    }

    private List<String> structuredBlockTypes(List<DownstreamEvidenceBlock> structuredBlocks) {
        LinkedHashSet<String> blockTypes = new LinkedHashSet<>();
        for (DownstreamEvidenceBlock block : structuredBlocks == null ? List.<DownstreamEvidenceBlock>of() : structuredBlocks) {
            if (block != null && block.getBlockType() != null && !block.getBlockType().isBlank()) {
                blockTypes.add(block.getBlockType().trim());
            }
        }
        return new ArrayList<>(blockTypes);
    }

    /**
     * 根据字段级 coverage 组装章节证据束。
     * 这里显式把“字段状态 + evidenceId/sourceUrl + 缺口说明”一起压入 bundle，
     * 后续分析、写作、报告接口就不需要再猜某个章节到底缺的是值还是缺的是证据。
     */
    private List<SectionEvidenceBundle> buildSectionEvidenceBundles(String competitorName,
                                                                    ObjectNode schemaJson,
                                                                    ObjectNode coverage,
                                                                    Map<String, ExtractorEvidenceInput> evidenceById) {
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
            List<String> missingFields = isTraceableCoverageStatus(status) ? List.of() : List.of(definition.fieldKey());
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
                                                               Map<String, ExtractorEvidenceInput> evidenceById) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        if (!evidenceIds.isEmpty()) {
            for (String evidenceId : evidenceIds) {
                ExtractorEvidenceInput matched = evidenceById.get(evidenceId);
                String sourceUrl = matched != null ? firstSourceUrl(matched) : sourceUrls.stream().findFirst().orElse(null);
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
                        .snippet(matched == null ? null : truncate(matched.getContent(), 180))
                        .issueFlags(isTraceableCoverageStatus(status) ? List.of() : List.of(status))
                        .gapComment(buildCoverageGapComment(status))
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
                        .issueFlags(isTraceableCoverageStatus(status) ? List.of() : List.of(status))
                        .gapComment(buildCoverageGapComment(status))
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
                .issueFlags(isTraceableCoverageStatus(status) ? List.of() : List.of(status))
                .gapComment(buildCoverageGapComment(status))
                .build()
                .normalized());
        return fragments;
    }

    private String buildCoverageGapComment(String status) {
        if (isTraceableCoverageStatus(status)) {
            return null;
        }
        if ("EMPTY".equalsIgnoreCase(status)) {
            return "字段暂无内容";
        }
        if ("LLM_REFUSED".equalsIgnoreCase(status)) {
            return "模型根据当前公开资料拒绝判断该字段";
        }
        if ("EVIDENCE_NOT_COVERING".equalsIgnoreCase(status)) {
            return "当前证据未覆盖该字段";
        }
        return "字段缺少稳定证据支撑";
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

    private String competitorNameFromEvidence(List<ExtractorEvidenceInput> evidenceInputs) {
        if (evidenceInputs == null || evidenceInputs.isEmpty()) {
            return null;
        }
        return evidenceInputs.get(0).getCompetitorName();
    }

    /**
     * extractor 内部输入投影需要在进入下游前恢复成轻量证据视图。
     * 这里只有 evidenceId、来源、质量信号和结构块摘要允许继续往下传，正文与 structuredPayload 必须留在 extractor 内部。
     */
    private List<DownstreamEvidenceView> buildDownstreamEvidenceViews(List<ExtractorEvidenceInput> evidenceInputs) {
        List<DownstreamEvidenceView> downstreamViews = new ArrayList<>();
        for (ExtractorEvidenceInput input : evidenceInputs == null ? List.<ExtractorEvidenceInput>of() : evidenceInputs) {
            DownstreamEvidenceView downstreamEvidenceView = toDownstreamEvidenceView(input);
            if (downstreamEvidenceView != null) {
                downstreamViews.add(downstreamEvidenceView);
            }
        }
        return normalizeDownstreamEvidenceViews(downstreamViews);
    }

    private String firstSourceUrl(ExtractorEvidenceInput input) {
        if (input == null || input.getSourceUrls() == null || input.getSourceUrls().isEmpty()) {
            return null;
        }
        return input.getSourceUrls().get(0);
    }

    /**
     * 直接从 ExtractorEvidenceInput 回建轻量下游视图。
     * 这里显式清空正文和 structuredPayload，避免 extractor 内部长文本重新泄漏到共享输出。
     */
    private DownstreamEvidenceView toDownstreamEvidenceView(ExtractorEvidenceInput input) {
        if (input == null) {
            return null;
        }
        return DownstreamEvidenceView.builder()
                .evidenceId(input.getEvidenceId())
                .competitorName(input.getCompetitorName())
                .sourceType(input.getSourceType())
                .title(input.getTitle())
                .content("")
                .sourceUrls(input.getSourceUrls() == null ? List.of() : new ArrayList<>(input.getSourceUrls()))
                .issueFlags(input.getIssueFlags() == null ? List.of() : new ArrayList<>(input.getIssueFlags()))
                .qualitySignals(input.getQualitySignals() == null ? List.of() : new ArrayList<>(input.getQualitySignals()))
                .structuredBlocks(input.getStructuredBlocks() == null ? List.of() : new ArrayList<>(input.getStructuredBlocks()))
                .structuredPayload(Map.of())
                .quality(input.getQuality())
                .build()
                .normalized();
    }

    private List<ExtractorEvidenceInput> normalizeEvidenceInputs(List<ExtractorEvidenceInput> inputs) {
        List<ExtractorEvidenceInput> normalized = new ArrayList<>();
        for (ExtractorEvidenceInput input : inputs == null ? List.<ExtractorEvidenceInput>of() : inputs) {
            if (input != null) {
                normalized.add(input.normalized());
            }
        }
        return normalized;
    }

    private record NormalizedSchema(ObjectNode schema,
                                    List<String> issueFlags,
                                    List<EvidenceFragment> evidenceFragments,
                                    List<SectionEvidenceBundle> sectionEvidenceBundles,
                                    List<DownstreamEvidenceView> downstreamEvidenceViews) {
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
