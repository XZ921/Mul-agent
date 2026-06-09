package cn.bugstack.competitoragent.agent.analyzer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.workflow.contract.AnalysisResult;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析 Agent。
 * 负责把抽取后的竞品知识整理成模型可消费的输入，并生成结构化竞品分析结果。
 */
@Slf4j
@Component
public class CompetitorAnalysisAgent extends BaseAgent {

    private static final List<SectionMapping> SECTION_MAPPINGS = List.of(
            new SectionMapping("overview", "overview", "产品概览", "summary", "SECTION"),
            new SectionMapping("features", "featureComparison", "功能对比", "coreFeatures", "SECTION"),
            new SectionMapping("positioning", "positioningComparison", "市场定位", "positioning", "SECTION"),
            new SectionMapping("pricing", "pricingComparison", "定价策略", "pricing", "SECTION"),
            new SectionMapping("targetUsers", "targetUserComparison", "目标用户", "targetUsers", "SECTION"),
            new SectionMapping("strengths", "strengthsSummary", "优势判断", "strengths", "SECTION"),
            new SectionMapping("weaknesses", "weaknessesSummary", "短板与风险", "weaknesses", "SECTION")
    );

    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public CompetitorAnalysisAgent(AgentExecutionLogRepository logRepository,
                                   CompetitorKnowledgeRepository knowledgeRepository,
                                   LlmClient llmClient,
                                   PromptTemplateService promptService,
                                   AgentContextAssembler agentContextAssembler,
                                   ObjectMapper objectMapper) {
        super(logRepository, agentContextAssembler);
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.ANALYZER;
    }

    @Override
    public String getName() {
        return "竞品分析智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId());
        if (knowledges.isEmpty()) {
            return AgentResult.failed("暂无可分析的竞品知识");
        }

        String competitorData;
        try {
            competitorData = objectMapper.writeValueAsString(knowledges.stream()
                    .map(this::toPromptPayload)
                    .toList());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("竞品知识序列化失败：" + e.getMessage());
        }

        String prompt = promptService.render("analyzer", Map.of(
                "subjectProduct", context.getSubjectProduct(),
                "analysisDimensions", context.getAnalysisDimensions() != null
                        ? context.getAnalysisDimensions() : "产品功能,价格策略,目标用户,市场定位",
                // 统一上下文由基类预先装配，这里只负责把摘要传给 Prompt 模板消费。
                "taskRagContext", context.getTaskRagPromptContext(),
                "competitorData", competitorData
        ));

        try {
            String llmResponse = llmClient.chatForJson(
                    "你是一名资深竞品分析专家，请只返回 JSON。",
                    prompt,
                    "Analysis"
            );
            JsonNode rawJson = objectMapper.readTree(cleanJson(llmResponse));
            if (!(rawJson instanceof ObjectNode analysisJson)) {
                return AgentResult.failed("竞品分析失败：模型未返回 JSON 对象");
            }
            AnalysisResult analysisResult = normalizeAnalysisResult(analysisJson, knowledges);
            // 把最终实际消费的 Task RAG 摘要一并写回运行态输出，方便后续节点和审计接口复核。
            analysisResult.setTaskRagContext(context.getTaskRagPromptContext());
            String outputJson = objectMapper.writeValueAsString(analysisResult);
            return AgentResult.success(outputJson,
                    "竞品分析完成：共处理 " + knowledges.size() + " 个竞品",
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("competitor analysis failed", e);
            return AgentResult.failed("竞品分析失败：" + e.getMessage());
        }
    }

    private Map<String, Object> toPromptPayload(CompetitorKnowledge knowledge) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("competitorName", knowledge.getCompetitorName());
        payload.put("summary", knowledge.getSummary());
        payload.put("positioning", knowledge.getPositioning());
        payload.put("targetUsers", parseJsonOrFallback(knowledge.getTargetUsers()));
        payload.put("coreFeatures", parseJsonOrFallback(knowledge.getCoreFeatures()));
        payload.put("pricing", parseJsonOrFallback(knowledge.getPricing()));
        payload.put("strengths", parseJsonOrFallback(knowledge.getStrengths()));
        payload.put("weaknesses", parseJsonOrFallback(knowledge.getWeaknesses()));
        payload.put("sources", parseJsonOrFallback(knowledge.getSources()));
        payload.put("sourceUrls", parseJsonOrFallback(knowledge.getSourceUrls()));
        payload.put("evidenceCoverage", parseJsonOrFallback(knowledge.getEvidenceCoverage()));
        payload.put("issueFlags", collectKnowledgeIssueFlags(knowledge));
        return payload;
    }

    /**
     * 分析阶段的首要职责不是相信模型字段永远稳定，而是把模型输出矫正回系统约定的契约形态。
     * 这里统一处理字段漂移、来源回填和证据缺口继承，保证 Writer 永远拿到同一种结构。
     */
    private AnalysisResult normalizeAnalysisResult(ObjectNode analysisJson, List<CompetitorKnowledge> knowledges) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>(readStringList(analysisJson.path("issueFlags")));
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(readStringList(analysisJson.path("sourceUrls")));
        List<EvidenceFragment> evidenceFragments = new ArrayList<>(readEvidenceFragments(analysisJson.path("evidenceFragments")));
        List<SectionEvidenceBundle> sectionEvidenceBundles = new ArrayList<>(readSectionEvidenceBundles(analysisJson.path("sectionEvidenceBundles")));

        String featureComparison = readTextWithAliases(analysisJson,
                List.of("featureComparison", "featureHighlights", "featureSummary"), issueFlags);
        String positioningComparison = readTextWithAliases(analysisJson,
                List.of("positioningComparison", "marketPositioning", "positioningInsights"), issueFlags);
        String pricingComparison = readTextWithAliases(analysisJson,
                List.of("pricingComparison", "pricingInsights", "pricingSummary"), issueFlags);
        String targetUserComparison = readTextWithAliases(analysisJson,
                List.of("targetUserComparison", "targetUsersComparison", "userInsights"), issueFlags);
        String strengthsSummary = readTextWithAliases(analysisJson,
                List.of("strengthsSummary", "strengthSummary"), issueFlags);
        String weaknessesSummary = readTextWithAliases(analysisJson,
                List.of("weaknessesSummary", "weaknessSummary", "risksSummary"), issueFlags);

        sourceUrls.addAll(collectKnowledgeSourceUrls(knowledges));
        if ((analysisJson.path("sourceUrls").isMissingNode() || analysisJson.path("sourceUrls").isEmpty())
                && !sourceUrls.isEmpty()) {
            issueFlags.add("SOURCE_URLS_BACKFILLED");
        }

        List<String> knowledgeIssueFlags = collectKnowledgeIssueFlags(knowledges);
        issueFlags.addAll(knowledgeIssueFlags);
        if (evidenceFragments.isEmpty()) {
            evidenceFragments.addAll(buildKnowledgeEvidenceFragments(knowledges, issueFlags));
        }
        if (sectionEvidenceBundles.isEmpty()) {
            sectionEvidenceBundles.addAll(buildSectionEvidenceBundles(knowledges, analysisJson, issueFlags));
        }

        return AnalysisResult.builder()
                .overview(analysisJson.path("overview").asText(null))
                .featureComparison(featureComparison)
                .positioningComparison(positioningComparison)
                .pricingComparison(pricingComparison)
                .targetUserComparison(targetUserComparison)
                .strengthsSummary(strengthsSummary)
                .weaknessesSummary(weaknessesSummary)
                .opportunities(readStringList(analysisJson.path("opportunities")))
                .risks(readStringList(analysisJson.path("risks")))
                .recommendations(readStringList(analysisJson.path("recommendations")))
                .sourceUrls(new ArrayList<>(sourceUrls))
                .issueFlags(new ArrayList<>(issueFlags))
                .evidenceFragments(normalizeEvidenceFragments(evidenceFragments))
                .sectionEvidenceBundles(normalizeSectionEvidenceBundles(sectionEvidenceBundles))
                .build();
    }

    private Object parseJsonOrFallback(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawValue);
        } catch (JsonProcessingException e) {
            return rawValue;
        }
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

    private String readTextWithAliases(ObjectNode json, List<String> aliases, LinkedHashSet<String> issueFlags) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        String canonicalField = aliases.get(0);
        for (int index = 0; index < aliases.size(); index++) {
            String alias = aliases.get(index);
            String value = json.path(alias).asText(null);
            if (value != null && !value.isBlank()) {
                if (index > 0) {
                    issueFlags.add("FIELD_DRIFT_CORRECTED");
                }
                return value;
            }
        }
        return null;
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

    private List<String> collectKnowledgeSourceUrls(List<CompetitorKnowledge> knowledges) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (CompetitorKnowledge knowledge : knowledges) {
            sourceUrls.addAll(readStringList(readJsonNode(knowledge.getSourceUrls())));
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectKnowledgeIssueFlags(List<CompetitorKnowledge> knowledges) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (CompetitorKnowledge knowledge : knowledges) {
            issueFlags.addAll(collectKnowledgeIssueFlags(knowledge));
        }
        return new ArrayList<>(issueFlags);
    }

    private List<String> collectKnowledgeIssueFlags(CompetitorKnowledge knowledge) {
        JsonNode coverage = readJsonNode(knowledge.getEvidenceCoverage());
        if (!(coverage instanceof ObjectNode coverageNode)) {
            return List.of();
        }
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        coverageNode.fields().forEachRemaining(entry -> {
            String status = entry.getValue().path("status").asText("");
            if ("MISSING_EVIDENCE".equalsIgnoreCase(status)) {
                issueFlags.add("MISSING_EVIDENCE");
            }
        });
        return new ArrayList<>(issueFlags);
    }

    private List<EvidenceFragment> readEvidenceFragments(JsonNode node) {
        if (!(node instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            fragments.add(objectMapper.convertValue(item, EvidenceFragment.class).normalized());
        }
        return fragments;
    }

    private List<SectionEvidenceBundle> readSectionEvidenceBundles(JsonNode node) {
        if (!(node instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            bundles.add(objectMapper.convertValue(item, SectionEvidenceBundle.class).normalized());
        }
        return bundles;
    }

    /**
     * 如果模型没有主动返回 evidenceFragments，就直接从抽取阶段落库的 sources/sourceUrls 回填。
     * 这样 Writer 可以只依赖 AnalysisResult，而不用重新猜测知识对象里的来源结构。
     */
    private List<EvidenceFragment> buildKnowledgeEvidenceFragments(List<CompetitorKnowledge> knowledges,
                                                                  LinkedHashSet<String> inheritedIssueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (CompetitorKnowledge knowledge : knowledges) {
            JsonNode sourcesNode = readJsonNode(knowledge.getSources());
            if (sourcesNode != null && sourcesNode.isArray()) {
                for (JsonNode item : sourcesNode) {
                    fragments.add(EvidenceFragment.builder()
                            .stage("ANALYZE")
                            .competitorName(knowledge.getCompetitorName())
                            .fieldName("analysis")
                            .evidenceId(item.path("evidenceId").asText(null))
                            .sourceUrl(item.path("url").asText(null))
                            .title(item.path("title").asText(null))
                            .snippet(item.path("snippet").asText(null))
                            .issueFlags(new ArrayList<>(inheritedIssueFlags))
                            .build()
                            .normalized());
                }
                continue;
            }
            for (String sourceUrl : readStringList(readJsonNode(knowledge.getSourceUrls()))) {
                fragments.add(EvidenceFragment.builder()
                        .stage("ANALYZE")
                        .competitorName(knowledge.getCompetitorName())
                        .fieldName("analysis")
                        .sourceUrl(sourceUrl)
                        .issueFlags(new ArrayList<>(inheritedIssueFlags))
                        .build()
                        .normalized());
            }
        }
        return fragments;
    }

    /**
     * 分析阶段按“可读章节”重新聚合证据束。
     * 这里把知识库中的字段 coverage 映射成对比章节，再额外生成一个 conclusion bundle，
     * 确保 Writer 和报告接口都能直接拿到“这段结论引用了哪些来源、哪里仍有缺口”。
     */
    private List<SectionEvidenceBundle> buildSectionEvidenceBundles(List<CompetitorKnowledge> knowledges,
                                                                   ObjectNode analysisJson,
                                                                   LinkedHashSet<String> inheritedIssueFlags) {
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (SectionMapping mapping : SECTION_MAPPINGS) {
            List<EvidenceFragment> sectionFragments = new ArrayList<>();
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            LinkedHashSet<String> missingFields = new LinkedHashSet<>();

            for (CompetitorKnowledge knowledge : knowledges) {
                JsonNode coverageNode = readCoverageNode(knowledge, mapping.knowledgeFieldKey());
                String status = coverageNode == null ? "EMPTY" : coverageNode.path("status").asText("EMPTY");
                List<EvidenceFragment> fieldFragments = buildKnowledgeFieldFragments(knowledge, mapping, status, coverageNode);
                sectionFragments.addAll(fieldFragments);
                sourceUrls.addAll(EvidenceFragment.collectSourceUrls(fieldFragments));
                if (!"TRACEABLE".equalsIgnoreCase(status)) {
                    missingFields.add(mapping.analysisFieldKey());
                }
            }

            bundles.add(SectionEvidenceBundle.builder()
                    .stage("ANALYZE")
                    .sectionType(mapping.sectionType())
                    .sectionKey(mapping.sectionKey())
                    .sectionTitle(mapping.sectionTitle())
                    .summary(truncate(analysisJson.path(mapping.analysisFieldKey()).asText(""), 160))
                    .fieldNames(List.of(mapping.analysisFieldKey()))
                    .missingFields(new ArrayList<>(missingFields))
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .issueFlags(missingFields.isEmpty() ? List.of() : List.of("SECTION_EVIDENCE_GAP"))
                    .evidenceFragments(sectionFragments)
                    .build()
                    .normalized());
        }

        bundles.add(buildConclusionBundle(analysisJson, knowledges, inheritedIssueFlags, bundles));
        return bundles;
    }

    private JsonNode readCoverageNode(CompetitorKnowledge knowledge, String fieldKey) {
        JsonNode coverage = readJsonNode(knowledge.getEvidenceCoverage());
        if (coverage == null || coverage.isMissingNode() || coverage.isNull()) {
            return null;
        }
        JsonNode node = coverage.path(fieldKey);
        return node.isMissingNode() ? null : node;
    }

    private List<EvidenceFragment> buildKnowledgeFieldFragments(CompetitorKnowledge knowledge,
                                                                SectionMapping mapping,
                                                                String status,
                                                                JsonNode coverageNode) {
        List<String> evidenceIds = readStringList(coverageNode == null ? null : coverageNode.path("evidenceIds"));
        List<String> sourceUrls = readStringList(coverageNode == null ? null : coverageNode.path("sourceUrls"));
        Map<String, JsonNode> sourceByEvidenceId = readSourceMap(knowledge.getSources());
        List<EvidenceFragment> fragments = new ArrayList<>();
        if (!evidenceIds.isEmpty()) {
            for (String evidenceId : evidenceIds) {
                JsonNode source = sourceByEvidenceId.get(evidenceId);
                fragments.add(EvidenceFragment.builder()
                        .stage("ANALYZE")
                        .competitorName(knowledge.getCompetitorName())
                        .fieldName(mapping.analysisFieldKey())
                        .fieldLabel(mapping.sectionTitle())
                        .sectionKey(mapping.sectionKey())
                        .sectionTitle(mapping.sectionTitle())
                        .coverageStatus(status)
                        .evidenceId(evidenceId)
                        .sourceUrl(source == null ? null : source.path("url").asText(null))
                        .title(source == null ? null : source.path("title").asText(null))
                        .snippet(source == null ? null : source.path("snippet").asText(null))
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
                        .stage("ANALYZE")
                        .competitorName(knowledge.getCompetitorName())
                        .fieldName(mapping.analysisFieldKey())
                        .fieldLabel(mapping.sectionTitle())
                        .sectionKey(mapping.sectionKey())
                        .sectionTitle(mapping.sectionTitle())
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
                .stage("ANALYZE")
                .competitorName(knowledge.getCompetitorName())
                .fieldName(mapping.analysisFieldKey())
                .fieldLabel(mapping.sectionTitle())
                .sectionKey(mapping.sectionKey())
                .sectionTitle(mapping.sectionTitle())
                .coverageStatus(status)
                .issueFlags("TRACEABLE".equalsIgnoreCase(status) ? List.of() : List.of(status))
                .gapComment("EMPTY".equalsIgnoreCase(status) ? "字段暂无内容" : "字段缺少稳定证据支撑")
                .build()
                .normalized());
        return fragments;
    }

    private Map<String, JsonNode> readSourceMap(String rawSources) {
        JsonNode sourcesNode = readJsonNode(rawSources);
        Map<String, JsonNode> sourceByEvidenceId = new LinkedHashMap<>();
        if (sourcesNode != null && sourcesNode.isArray()) {
            for (JsonNode item : sourcesNode) {
                String evidenceId = item.path("evidenceId").asText(null);
                if (evidenceId != null && !evidenceId.isBlank()) {
                    sourceByEvidenceId.putIfAbsent(evidenceId, item);
                }
            }
        }
        return sourceByEvidenceId;
    }

    private SectionEvidenceBundle buildConclusionBundle(ObjectNode analysisJson,
                                                       List<CompetitorKnowledge> knowledges,
                                                       LinkedHashSet<String> inheritedIssueFlags,
                                                       List<SectionEvidenceBundle> sectionBundles) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        List<EvidenceFragment> conclusionFragments = new ArrayList<>();
        for (SectionEvidenceBundle sectionBundle : sectionBundles) {
            sourceUrls.addAll(sectionBundle.getSourceUrls() == null ? List.of() : sectionBundle.getSourceUrls());
            conclusionFragments.addAll(sectionBundle.getEvidenceFragments() == null ? List.of() : sectionBundle.getEvidenceFragments());
        }
        sourceUrls.addAll(collectKnowledgeSourceUrls(knowledges));
        if (conclusionFragments.isEmpty()) {
            conclusionFragments.addAll(buildKnowledgeEvidenceFragments(knowledges, inheritedIssueFlags));
        }
        LinkedHashSet<String> missingFields = new LinkedHashSet<>();
        if (analysisJson.path("recommendations").isMissingNode() || analysisJson.path("recommendations").isEmpty()) {
            missingFields.add("recommendations");
        }
        if (sourceUrls.isEmpty()) {
            missingFields.add("conclusion");
        }
        return SectionEvidenceBundle.builder()
                .stage("ANALYZE")
                .sectionType("CONCLUSION")
                .sectionKey("conclusion")
                .sectionTitle("结论与建议")
                .summary(firstNonBlank(
                        truncate(analysisJson.path("overview").asText(""), 160),
                        truncate(analysisJson.path("recommendations").toString(), 160)
                ))
                .fieldNames(List.of("overview", "recommendations", "opportunities", "risks"))
                .missingFields(new ArrayList<>(missingFields))
                .sourceUrls(new ArrayList<>(sourceUrls))
                .issueFlags(missingFields.isEmpty() ? List.of() : List.of("SECTION_EVIDENCE_GAP"))
                .evidenceFragments(conclusionFragments)
                .build()
                .normalized();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
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

    private JsonNode readJsonNode(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawValue);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record SectionMapping(String sectionKey,
                                  String analysisFieldKey,
                                  String sectionTitle,
                                  String knowledgeFieldKey,
                                  String sectionType) {
    }
}
