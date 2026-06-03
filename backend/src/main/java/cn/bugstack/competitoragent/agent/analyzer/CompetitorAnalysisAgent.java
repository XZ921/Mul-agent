package cn.bugstack.competitoragent.agent.analyzer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.workflow.contract.AnalysisResult;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
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

    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public CompetitorAnalysisAgent(AgentExecutionLogRepository logRepository,
                                   CompetitorKnowledgeRepository knowledgeRepository,
                                   LlmClient llmClient,
                                   PromptTemplateService promptService,
                                   ObjectMapper objectMapper) {
        super(logRepository);
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

    private List<EvidenceFragment> normalizeEvidenceFragments(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments) {
            if (fragment != null) {
                normalized.add(fragment.normalized());
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
}
