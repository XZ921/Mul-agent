package cn.bugstack.competitoragent.agent.analyzer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.knowledge.TaskKnowledgeSnapshotResolver;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.workflow.contract.AnalysisResult;
import cn.bugstack.competitoragent.workflow.contract.CompetitorKnowledgeDraft;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
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
import java.util.Set;

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
        // analyzer 只应消费当前任务有效快照。
        // 否则当 extract_schema 共享输出缺失、回退到 TASK snapshot 时，会错误拿到更旧的一版知识。
        List<CompetitorKnowledge> knowledges = TaskKnowledgeSnapshotResolver.resolveCurrentTaskSnapshots(
                knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId())
        );
        ExtractRuntimeOutput extractorOutput = readExtractRuntimeOutput(context.getSharedOutput("extract_schema"));
        List<DownstreamEvidenceView> downstreamEvidenceViews = extractorOutput.downstreamEvidenceViews();
        if (knowledges.isEmpty() && downstreamEvidenceViews.isEmpty() && extractorOutput.drafts().isEmpty()) {
            return AgentResult.failed("暂无可分析的竞品知识");
        }

        String competitorData;
        try {
            competitorData = objectMapper.writeValueAsString(buildPromptPayloads(knowledges, extractorOutput));
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
            AnalysisResult analysisResult = normalizeAnalysisResult(
                    analysisJson,
                    knowledges,
                    downstreamEvidenceViews,
                    extractorOutput.drafts());
            applyAnalysisGapMetadata(analysisResult);
            // 统一回填任务级 RAG 摘要，确保缺口返回和正常返回都保留同一份可追溯上下文。
            analysisResult.setTaskRagContext(context.getTaskRagPromptContext());
            if (isCoreAnalysisEmpty(analysisResult)) {
                analysisResult.getIssueFlags().add("ANALYSIS_CORE_FIELDS_EMPTY");
                String outputJson = objectMapper.writeValueAsString(analysisResult);
                return AgentResult.success(outputJson,
                        "竞品分析存在核心字段缺口，等待 Orchestrator 决策",
                        System.currentTimeMillis(),
                        llmClient.getModelName(),
                        llmClient.getLastTokenUsage().toJson());
            }
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

    private List<Map<String, Object>> buildPromptPayloads(List<CompetitorKnowledge> knowledges,
                                                          ExtractRuntimeOutput extractorOutput) {
        Map<String, CompetitorKnowledge> snapshotsByCompetitor = indexKnowledgeByCompetitor(knowledges);
        Map<String, List<DownstreamEvidenceView>> viewsByCompetitor = groupViewsByCompetitor(extractorOutput.downstreamEvidenceViews());
        List<Map<String, Object>> payloads = new ArrayList<>();
        Set<String> emittedCompetitors = new LinkedHashSet<>();

        for (CompetitorKnowledgeDraft draft : extractorOutput.drafts()) {
            String competitorName = firstNonBlank(draft.getCompetitorName(), "UNKNOWN");
            Map<String, Object> payload = toPromptPayload(draft);
            CompetitorKnowledge snapshot = snapshotsByCompetitor.get(competitorName);
            if (snapshot != null) {
                mergeMissingFieldsFromTaskSnapshot(payload, snapshot);
            }
            List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
            if (!matchedViews.isEmpty()) {
                payload.put("downstreamEvidenceViews", normalizeDownstreamEvidenceViews(matchedViews));
            }
            payload.put("inputPriority", "EXTRACT_RESULT_DRAFT");
            payloads.add(payload);
            emittedCompetitors.add(competitorName);
        }

        for (CompetitorKnowledge snapshot : knowledges == null ? List.<CompetitorKnowledge>of() : knowledges) {
            String competitorName = firstNonBlank(snapshot.getCompetitorName(), "UNKNOWN");
            if (emittedCompetitors.contains(competitorName)) {
                continue;
            }
            Map<String, Object> payload = toPromptPayload(snapshot);
            payload.put("inputPriority", "TASK_SNAPSHOT_FALLBACK");
            payload.put("issueFlags", appendIssueFlag(payload.get("issueFlags"), "EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT"));
            List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
            if (!matchedViews.isEmpty()) {
                payload.put("downstreamEvidenceViews", normalizeDownstreamEvidenceViews(matchedViews));
            }
            payloads.add(payload);
            emittedCompetitors.add(competitorName);
        }

        for (Map.Entry<String, List<DownstreamEvidenceView>> entry : viewsByCompetitor.entrySet()) {
            if (emittedCompetitors.contains(entry.getKey())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("competitorName", entry.getKey());
            payload.put("sourceUrls", collectEvidenceViewSourceUrls(entry.getValue()));
            payload.put("issueFlags", collectEvidenceViewIssueFlags(entry.getValue()));
            payload.put("downstreamEvidenceViews", normalizeDownstreamEvidenceViews(entry.getValue()));
            payload.put("inputPriority", "EXTRACT_RESULT_VIEWS_ONLY");
            payloads.add(payload);
        }
        return payloads;
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

    private Map<String, Object> toPromptPayload(CompetitorKnowledgeDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("competitorName", draft.getCompetitorName());
        payload.put("summary", draft.getSummary());
        payload.put("positioning", draft.getPositioning());
        payload.put("targetUsers", draft.getTargetUsers());
        payload.put("coreFeatures", draft.getCoreFeatures());
        payload.put("pricing", draft.getPricing());
        payload.put("strengths", draft.getStrengths());
        payload.put("weaknesses", draft.getWeaknesses());
        payload.put("sourceUrls", draft.getSourceUrls());
        payload.put("evidenceCoverage", draft.getEvidenceCoverage());
        payload.put("issueFlags", draft.getIssueFlags() == null ? new ArrayList<>() : new ArrayList<>(draft.getIssueFlags()));
        return payload;
    }

    /**
     * 分析阶段的首要职责不是相信模型字段永远稳定，而是把模型输出矫正回系统约定的契约形态。
     * 这里统一处理字段漂移、来源回填和证据缺口继承，保证 Writer 永远拿到同一种结构。
     */
    private AnalysisResult normalizeAnalysisResult(ObjectNode analysisJson,
                                                   List<CompetitorKnowledge> knowledges,
                                                   List<DownstreamEvidenceView> downstreamEvidenceViews,
                                                   List<CompetitorKnowledgeDraft> drafts) {
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
        sourceUrls.addAll(collectDraftSourceUrls(drafts));
        sourceUrls.addAll(collectEvidenceViewSourceUrls(downstreamEvidenceViews));
        if ((analysisJson.path("sourceUrls").isMissingNode() || analysisJson.path("sourceUrls").isEmpty())
                && !sourceUrls.isEmpty()) {
            issueFlags.add("SOURCE_URLS_BACKFILLED");
        }

        List<String> knowledgeIssueFlags = collectKnowledgeIssueFlags(knowledges);
        issueFlags.addAll(knowledgeIssueFlags);
        issueFlags.addAll(collectDraftIssueFlags(drafts));
        issueFlags.addAll(collectEvidenceViewIssueFlags(downstreamEvidenceViews));
        issueFlags.addAll(collectSnapshotFallbackIssueFlags(knowledges, drafts));
        if (evidenceFragments.isEmpty()) {
            evidenceFragments.addAll(buildDraftEvidenceFragments(drafts, issueFlags));
            evidenceFragments.addAll(buildKnowledgeEvidenceFragments(knowledges, issueFlags));
            evidenceFragments.addAll(buildEvidenceViewFragments(downstreamEvidenceViews, issueFlags));
        }
        if (sectionEvidenceBundles.isEmpty()) {
            sectionEvidenceBundles.addAll(buildSectionEvidenceBundles(
                    knowledges,
                    drafts,
                    downstreamEvidenceViews,
                    analysisJson,
                    issueFlags));
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
                .downstreamEvidenceViews(normalizeDownstreamEvidenceViews(downstreamEvidenceViews))
                .build();
    }

    /**
     * 在 Analyzer 内部统一补齐缺口元数据，避免 Writer 或编排层重复猜测分析质量。
     */
    private void applyAnalysisGapMetadata(AnalysisResult analysisResult) {
        if (analysisResult == null) {
            return;
        }
        List<String> missingDimensions = collectMissingAnalysisDimensions(analysisResult);
        String gapSeverity = resolveAnalysisGapSeverity(missingDimensions);
        analysisResult.setMissingAnalysisDimensions(missingDimensions);
        analysisResult.setAnalysisGapSeverity(gapSeverity);
        analysisResult.setAnalysisConfidence(resolveAnalysisConfidence(gapSeverity));
        analysisResult.setAnalysisEvidenceState(resolveAnalysisEvidenceState(
                analysisResult.getSourceUrls(),
                missingDimensions));
    }

    /**
     * 根据核心结构化字段完整度生成缺失维度列表，明确告诉下游缺的是哪类分析结论。
     */
    private List<String> collectMissingAnalysisDimensions(AnalysisResult analysisResult) {
        List<String> missing = new ArrayList<>();
        if (!hasText(analysisResult.getFeatureComparison())) {
            missing.add("featureComparison");
        }
        if (!hasText(analysisResult.getPositioningComparison())) {
            missing.add("positioningComparison");
        }
        if (!hasText(analysisResult.getPricingComparison())) {
            missing.add("pricingComparison");
        }
        if (!hasText(analysisResult.getTargetUserComparison())) {
            missing.add("targetUserComparison");
        }
        if (!hasText(analysisResult.getStrengthsSummary())) {
            missing.add("strengthsSummary");
        }
        if (!hasText(analysisResult.getWeaknessesSummary())) {
            missing.add("weaknessesSummary");
        }
        return missing;
    }

    /**
     * 缺口严重度只由核心分析字段缺失比例决定，业务动作仍由编排策略层裁决。
     */
    private String resolveAnalysisGapSeverity(List<String> missingDimensions) {
        if (missingDimensions == null || missingDimensions.isEmpty()) {
            return "NONE";
        }
        if (missingDimensions.size() >= 6) {
            return "HIGH";
        }
        return missingDimensions.size() >= 3 ? "MEDIUM" : "LOW";
    }

    /**
     * 置信度与缺口严重度保持单向映射，便于回放时复原 Analyzer 自评依据。
     */
    private String resolveAnalysisConfidence(String gapSeverity) {
        return switch (gapSeverity) {
            case "NONE" -> "HIGH";
            case "LOW", "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * 无来源时必须标记为 MISSING_SOURCE；有来源但仍缺维度时标记为 PARTIAL_SOURCE。
     */
    private String resolveAnalysisEvidenceState(List<String> sourceUrls, List<String> missingDimensions) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return "MISSING_SOURCE";
        }
        if (missingDimensions != null && !missingDimensions.isEmpty()) {
            return "PARTIAL_SOURCE";
        }
        return "FULL_SOURCE";
    }

    /**
     * Analyzer 成功的最低门槛是至少产出一个非 overview 的核心分析字段。
     * 只有来源 URL、概览或建议时，Writer 会被迫凭空扩写，因此这里直接阻断下游写作。
     */
    private boolean isCoreAnalysisEmpty(AnalysisResult analysisResult) {
        if (analysisResult == null) {
            return true;
        }
        return !hasText(analysisResult.getFeatureComparison())
                && !hasText(analysisResult.getPositioningComparison())
                && !hasText(analysisResult.getPricingComparison())
                && !hasText(analysisResult.getTargetUserComparison())
                && !hasText(analysisResult.getStrengthsSummary())
                && !hasText(analysisResult.getWeaknessesSummary());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
            if (isCoverageGapStatus(status)) {
                issueFlags.add(status.toUpperCase());
            }
        });
        return new ArrayList<>(issueFlags);
    }

    private boolean isCoverageGapStatus(String status) {
        return "MISSING_EVIDENCE".equalsIgnoreCase(status)
                || "LLM_REFUSED".equalsIgnoreCase(status)
                || "EVIDENCE_NOT_COVERING".equalsIgnoreCase(status);
    }

    private boolean isTraceableCoverageStatus(String status) {
        return "TRACEABLE".equalsIgnoreCase(status)
                || "STRUCTURED_BLOCK_DIRECT".equalsIgnoreCase(status);
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

    private Map<String, CompetitorKnowledge> indexKnowledgeByCompetitor(List<CompetitorKnowledge> knowledges) {
        Map<String, CompetitorKnowledge> snapshots = new LinkedHashMap<>();
        for (CompetitorKnowledge knowledge : knowledges == null ? List.<CompetitorKnowledge>of() : knowledges) {
            if (knowledge == null) {
                continue;
            }
            snapshots.putIfAbsent(firstNonBlank(knowledge.getCompetitorName(), "UNKNOWN"), knowledge);
        }
        return snapshots;
    }

    private Map<String, List<DownstreamEvidenceView>> groupViewsByCompetitor(List<DownstreamEvidenceView> views) {
        Map<String, List<DownstreamEvidenceView>> grouped = new LinkedHashMap<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            String competitorName = firstNonBlank(view == null ? null : view.getCompetitorName(), "UNKNOWN");
            grouped.computeIfAbsent(competitorName, key -> new ArrayList<>()).add(view.normalized());
        }
        return grouped;
    }

    /**
     * 同一竞品同时存在 draft 和 TASK snapshot 时，只允许 snapshot 补 draft 的空字段。
     * 如果两边同时给值但内容不同，就把冲突显式记录下来，交给 analyzer Prompt 做语义裁决。
     */
    private void mergeMissingFieldsFromTaskSnapshot(Map<String, Object> payload, CompetitorKnowledge snapshot) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        mergeField(payload, "summary", snapshot.getSummary(), conflicts);
        mergeField(payload, "positioning", snapshot.getPositioning(), conflicts);
        mergeField(payload, "targetUsers", parseJsonOrFallback(snapshot.getTargetUsers()), conflicts);
        mergeField(payload, "coreFeatures", parseJsonOrFallback(snapshot.getCoreFeatures()), conflicts);
        mergeField(payload, "pricing", parseJsonOrFallback(snapshot.getPricing()), conflicts);
        mergeField(payload, "strengths", parseJsonOrFallback(snapshot.getStrengths()), conflicts);
        mergeField(payload, "weaknesses", parseJsonOrFallback(snapshot.getWeaknesses()), conflicts);
        mergeField(payload, "sourceUrls", parseJsonOrFallback(snapshot.getSourceUrls()), conflicts);
        mergeField(payload, "evidenceCoverage", parseJsonOrFallback(snapshot.getEvidenceCoverage()), conflicts);
        if (!conflicts.isEmpty()) {
            payload.put("inputConflicts", conflicts);
        }
    }

    private void mergeField(Map<String, Object> payload,
                            String fieldName,
                            Object snapshotValue,
                            List<Map<String, Object>> conflicts) {
        Object draftValue = payload.get(fieldName);
        if (!hasMeaningfulValue(draftValue)) {
            if (snapshotValue != null) {
                payload.put(fieldName, snapshotValue);
            }
            return;
        }
        if (hasMeaningfulValue(snapshotValue) && !sameValue(draftValue, snapshotValue)) {
            conflicts.add(Map.of(
                    "fieldName", fieldName,
                    "draftValue", draftValue,
                    "snapshotValue", snapshotValue,
                    "priority", "EXTRACT_RESULT_DRAFT_WINS"
            ));
        }
    }

    private List<String> appendIssueFlag(Object issueFlagsValue, String issueFlag) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (issueFlagsValue instanceof List<?> values) {
            for (Object value : values) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    issueFlags.add(String.valueOf(value));
                }
            }
        }
        if (issueFlag != null && !issueFlag.isBlank()) {
            issueFlags.add(issueFlag);
        }
        return new ArrayList<>(issueFlags);
    }

    private List<String> collectDraftSourceUrls(List<CompetitorKnowledgeDraft> drafts) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (CompetitorKnowledgeDraft draft : drafts == null ? List.<CompetitorKnowledgeDraft>of() : drafts) {
            if (draft != null && draft.getSourceUrls() != null) {
                sourceUrls.addAll(draft.getSourceUrls());
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectDraftIssueFlags(List<CompetitorKnowledgeDraft> drafts) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (CompetitorKnowledgeDraft draft : drafts == null ? List.<CompetitorKnowledgeDraft>of() : drafts) {
            if (draft != null && draft.getIssueFlags() != null) {
                issueFlags.addAll(draft.getIssueFlags());
            }
        }
        return new ArrayList<>(issueFlags);
    }

    /**
     * 只要某个竞品缺少运行态 draft，analyzer 实际上就已经回退到 TASK snapshot。
     * 这个信号必须跟随分析结果继续向后传，方便排查“为什么这次分析不是基于最新抽取现场”。
     */
    private List<String> collectSnapshotFallbackIssueFlags(List<CompetitorKnowledge> knowledges,
                                                           List<CompetitorKnowledgeDraft> drafts) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        Set<String> draftCompetitors = new LinkedHashSet<>();
        for (CompetitorKnowledgeDraft draft : drafts == null ? List.<CompetitorKnowledgeDraft>of() : drafts) {
            if (draft != null) {
                draftCompetitors.add(firstNonBlank(draft.getCompetitorName(), "UNKNOWN"));
            }
        }
        for (CompetitorKnowledge knowledge : knowledges == null ? List.<CompetitorKnowledge>of() : knowledges) {
            if (knowledge == null) {
                continue;
            }
            String competitorName = firstNonBlank(knowledge.getCompetitorName(), "UNKNOWN");
            if (!draftCompetitors.contains(competitorName)) {
                issueFlags.add("EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT");
            }
        }
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
     * 优先保留 extractor 现场里已经按字段组织好的证据片段。
     * 这样 analyzer 即使只产出总结文本，下游也仍能沿着 draft 里的字段证据追溯来源。
     */
    private List<EvidenceFragment> buildDraftEvidenceFragments(List<CompetitorKnowledgeDraft> drafts,
                                                               LinkedHashSet<String> inheritedIssueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (CompetitorKnowledgeDraft draft : drafts == null ? List.<CompetitorKnowledgeDraft>of() : drafts) {
            if (draft == null) {
                continue;
            }
            if (draft.getEvidenceFragments() != null && !draft.getEvidenceFragments().isEmpty()) {
                for (EvidenceFragment fragment : draft.getEvidenceFragments()) {
                    if (fragment == null) {
                        continue;
                    }
                    LinkedHashSet<String> mergedFlags = new LinkedHashSet<>(inheritedIssueFlags);
                    mergedFlags.addAll(fragment.getIssueFlags() == null ? List.of() : fragment.getIssueFlags());
                    fragments.add(fragment.toBuilder()
                            .stage("ANALYZE")
                            .issueFlags(new ArrayList<>(mergedFlags))
                            .build()
                            .normalized());
                }
                continue;
            }
            for (String sourceUrl : draft.getSourceUrls() == null ? List.<String>of() : draft.getSourceUrls()) {
                fragments.add(EvidenceFragment.builder()
                        .stage("ANALYZE")
                        .competitorName(draft.getCompetitorName())
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
     * 如果模型没有主动返回 evidenceFragments，就直接从抽取阶段落库的 sources/sourceUrls 回填。
     * 这样 Writer 可以只依赖 AnalysisResult，而不用重新猜测知识对象里的来源结构。
     */
    private ExtractRuntimeOutput readExtractRuntimeOutput(String extractorOutput) {
        JsonNode root = readJsonNode(extractorOutput);
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new ExtractRuntimeOutput(List.of(), List.of());
        }
        List<DownstreamEvidenceView> views = new ArrayList<>();
        views.addAll(readDownstreamEvidenceViews(root.path("downstreamEvidenceViews")));
        List<CompetitorKnowledgeDraft> draftResults = new ArrayList<>();
        JsonNode draftNodes = root.path("drafts");
        if (draftNodes.isArray()) {
            for (JsonNode draftNode : draftNodes) {
                CompetitorKnowledgeDraft draft = readCompetitorKnowledgeDraft(draftNode);
                draftResults.add(draft);
                views.addAll(readDownstreamEvidenceViews(draftNode.path("downstreamEvidenceViews")));
            }
        }
        return new ExtractRuntimeOutput(draftResults, normalizeDownstreamEvidenceViews(views));
    }

    private CompetitorKnowledgeDraft readCompetitorKnowledgeDraft(JsonNode draftNode) {
        if (draftNode == null || draftNode.isMissingNode() || draftNode.isNull()) {
            return CompetitorKnowledgeDraft.builder().build();
        }
        return CompetitorKnowledgeDraft.builder()
                .competitorName(draftNode.path("competitorName").asText(null))
                .officialUrl(draftNode.path("officialUrl").asText(null))
                .summary(draftNode.path("summary").asText(null))
                .positioning(draftNode.path("positioning").asText(null))
                .targetUsers(readStringList(draftNode.path("targetUsers")))
                .coreFeatures(readTypedList(draftNode.path("coreFeatures"), cn.bugstack.competitoragent.workflow.contract.FeatureItem.class))
                .pricing(readTypedObject(draftNode.path("pricing"), cn.bugstack.competitoragent.workflow.contract.PricingItem.class))
                .strengths(readTypedList(draftNode.path("strengths"), cn.bugstack.competitoragent.workflow.contract.StrengthWeaknessItem.class))
                .weaknesses(readTypedList(draftNode.path("weaknesses"), cn.bugstack.competitoragent.workflow.contract.StrengthWeaknessItem.class))
                .sourceUrls(readStringList(draftNode.path("sourceUrls")))
                .evidenceFragments(readEvidenceFragments(draftNode.path("evidenceFragments")))
                .sectionEvidenceBundles(readSectionEvidenceBundles(draftNode.path("sectionEvidenceBundles")))
                .downstreamEvidenceViews(readDownstreamEvidenceViews(draftNode.path("downstreamEvidenceViews")))
                .issueFlags(readStringList(draftNode.path("issueFlags")))
                .evidenceCoverage(readTypedMap(draftNode.path("evidenceCoverage")))
                .fieldsExtracted(draftNode.path("fieldsExtracted").asInt(0))
                .status(draftNode.path("status").asText(null))
                .build();
    }

    private <T> List<T> readTypedList(JsonNode node, Class<T> itemType) {
        if (!(node instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            values.add(objectMapper.convertValue(item, itemType));
        }
        return values;
    }

    private <T> T readTypedObject(JsonNode node, Class<T> itemType) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, itemType);
    }

    private Map<String, Object> readTypedMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private List<DownstreamEvidenceView> readDownstreamEvidenceViews(JsonNode node) {
        if (!(node instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<DownstreamEvidenceView> views = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            DownstreamEvidenceView view = objectMapper.convertValue(item, DownstreamEvidenceView.class);
            if (view != null) {
                views.add(view.normalized());
            }
        }
        return views;
    }

    private List<DownstreamEvidenceView> downstreamEvidenceViewsByCompetitor(List<DownstreamEvidenceView> views,
                                                                             String competitorName) {
        List<DownstreamEvidenceView> matchedViews = new ArrayList<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            if (competitorName == null
                    || view.getCompetitorName() == null
                    || competitorName.equalsIgnoreCase(view.getCompetitorName())) {
                matchedViews.add(view.normalized());
            }
        }
        return matchedViews;
    }

    private List<String> collectEvidenceViewSourceUrls(List<DownstreamEvidenceView> views) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            sourceUrls.addAll(view.getSourceUrls() == null ? List.of() : view.getSourceUrls());
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectEvidenceViewIssueFlags(List<DownstreamEvidenceView> views) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            issueFlags.addAll(view.getIssueFlags() == null ? List.of() : view.getIssueFlags());
        }
        return new ArrayList<>(issueFlags);
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
     * shared output 里的轻量证据视图不再携带字段级 coverage，因此这里只生成通用分析片段，
     * 目的不是替代 draft，而是保证 qualitySignals / sourceUrls 不会在 analyzer 输出时被静默丢掉。
     */
    private List<EvidenceFragment> buildEvidenceViewFragments(List<DownstreamEvidenceView> views,
                                                              LinkedHashSet<String> inheritedIssueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (DownstreamEvidenceView view : views == null ? List.<DownstreamEvidenceView>of() : views) {
            if (view == null) {
                continue;
            }
            LinkedHashSet<String> mergedFlags = new LinkedHashSet<>(inheritedIssueFlags);
            mergedFlags.addAll(view.getIssueFlags() == null ? List.of() : view.getIssueFlags());
            String primarySourceUrl = view.getSourceUrls() == null || view.getSourceUrls().isEmpty()
                    ? null
                    : view.getSourceUrls().get(0);
            fragments.add(EvidenceFragment.builder()
                    .stage("ANALYZE")
                    .competitorName(view.getCompetitorName())
                    .fieldName("analysis")
                    .evidenceId(view.getEvidenceId())
                    .sourceUrl(primarySourceUrl)
                    .title(view.getTitle())
                    .snippet(firstNonBlank(truncate(view.getContent(), 180), summarizeStructuredBlocks(view)))
                    .issueFlags(new ArrayList<>(mergedFlags))
                    .build()
                    .normalized());
        }
        return fragments;
    }

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
                                                                   List<CompetitorKnowledgeDraft> drafts,
                                                                   List<DownstreamEvidenceView> downstreamEvidenceViews,
                                                                   ObjectNode analysisJson,
                                                                   LinkedHashSet<String> inheritedIssueFlags) {
        Map<String, CompetitorKnowledge> snapshotsByCompetitor = indexKnowledgeByCompetitor(knowledges);
        Map<String, CompetitorKnowledgeDraft> draftsByCompetitor = indexDraftByCompetitor(drafts);
        Map<String, List<DownstreamEvidenceView>> viewsByCompetitor = groupViewsByCompetitor(downstreamEvidenceViews);
        LinkedHashSet<String> competitorNames = new LinkedHashSet<>();
        competitorNames.addAll(draftsByCompetitor.keySet());
        competitorNames.addAll(snapshotsByCompetitor.keySet());
        competitorNames.addAll(viewsByCompetitor.keySet());

        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (SectionMapping mapping : SECTION_MAPPINGS) {
            List<EvidenceFragment> sectionFragments = new ArrayList<>();
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            LinkedHashSet<String> missingFields = new LinkedHashSet<>();

            for (String competitorName : competitorNames) {
                boolean traceable = false;

                CompetitorKnowledgeDraft draft = draftsByCompetitor.get(competitorName);
                SectionEvidenceBundle draftBundle = findDraftSectionBundle(draft, mapping.knowledgeFieldKey());
                if (draftBundle != null) {
                    List<EvidenceFragment> draftFragments = retagDraftFragmentsForAnalyzer(mapping, draftBundle.getEvidenceFragments());
                    sectionFragments.addAll(draftFragments);
                    sourceUrls.addAll(draftBundle.getSourceUrls() == null ? List.of() : draftBundle.getSourceUrls());
                    sourceUrls.addAll(EvidenceFragment.collectSourceUrls(draftFragments));
                    if (!hasSectionGap(draftBundle)) {
                        traceable = true;
                    }
                }

                CompetitorKnowledge knowledge = snapshotsByCompetitor.get(competitorName);
                if (knowledge != null) {
                    JsonNode coverageNode = readCoverageNode(knowledge, mapping.knowledgeFieldKey());
                    String status = coverageNode == null ? "EMPTY" : coverageNode.path("status").asText("EMPTY");
                    List<EvidenceFragment> fieldFragments = buildKnowledgeFieldFragments(knowledge, mapping, status, coverageNode);
                    sectionFragments.addAll(fieldFragments);
                    sourceUrls.addAll(EvidenceFragment.collectSourceUrls(fieldFragments));
                    if (isTraceableCoverageStatus(status)) {
                        traceable = true;
                    }
                }

                List<DownstreamEvidenceView> matchedViews = viewsByCompetitor.getOrDefault(competitorName, List.of());
                if (!matchedViews.isEmpty()) {
                    sectionFragments.addAll(buildEvidenceViewSectionFragments(mapping, competitorName, matchedViews));
                    sourceUrls.addAll(collectEvidenceViewSourceUrls(matchedViews));
                }

                if (!traceable) {
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

        bundles.add(buildConclusionBundle(
                analysisJson,
                knowledges,
                drafts,
                downstreamEvidenceViews,
                inheritedIssueFlags,
                bundles));
        return bundles;
    }

    private Map<String, CompetitorKnowledgeDraft> indexDraftByCompetitor(List<CompetitorKnowledgeDraft> drafts) {
        Map<String, CompetitorKnowledgeDraft> indexed = new LinkedHashMap<>();
        for (CompetitorKnowledgeDraft draft : drafts == null ? List.<CompetitorKnowledgeDraft>of() : drafts) {
            if (draft == null) {
                continue;
            }
            indexed.putIfAbsent(firstNonBlank(draft.getCompetitorName(), "UNKNOWN"), draft);
        }
        return indexed;
    }

    private SectionEvidenceBundle findDraftSectionBundle(CompetitorKnowledgeDraft draft, String sectionKey) {
        if (draft == null || draft.getSectionEvidenceBundles() == null) {
            return null;
        }
        for (SectionEvidenceBundle bundle : draft.getSectionEvidenceBundles()) {
            if (bundle == null) {
                continue;
            }
            String normalizedKey = firstNonBlank(bundle.getSectionKey(), null);
            if (sectionKey.equals(normalizedKey)) {
                return bundle.normalized();
            }
        }
        return null;
    }

    private boolean hasSectionGap(SectionEvidenceBundle bundle) {
        SectionEvidenceBundle normalized = bundle == null ? null : bundle.normalized();
        if (normalized == null) {
            return true;
        }
        return !(normalized.getMissingFields() == null || normalized.getMissingFields().isEmpty())
                || (normalized.getIssueFlags() != null
                && (normalized.getIssueFlags().contains("SECTION_EVIDENCE_GAP")
                || normalized.getIssueFlags().contains("NO_USABLE_EVIDENCE")));
    }

    /**
     * draft 里的字段证据仍然使用抽取阶段的字段键。
     * analyzer 输出要把这些片段挂到当前分析章节下，避免 writer 再去做一层字段语义映射。
     */
    private List<EvidenceFragment> retagDraftFragmentsForAnalyzer(SectionMapping mapping,
                                                                  List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments == null ? List.<EvidenceFragment>of() : fragments) {
            if (fragment == null) {
                continue;
            }
            normalized.add(fragment.toBuilder()
                    .stage("ANALYZE")
                    .fieldName(mapping.analysisFieldKey())
                    .fieldLabel(mapping.sectionTitle())
                    .sectionKey(mapping.sectionKey())
                    .sectionTitle(mapping.sectionTitle())
                    .build()
                    .normalized());
        }
        return normalized;
    }

    private List<EvidenceFragment> buildEvidenceViewSectionFragments(SectionMapping mapping,
                                                                     String competitorName,
                                                                     List<DownstreamEvidenceView> matchedViews) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (DownstreamEvidenceView view : matchedViews == null ? List.<DownstreamEvidenceView>of() : matchedViews) {
            if (view == null) {
                continue;
            }
            String primarySourceUrl = view.getSourceUrls() == null || view.getSourceUrls().isEmpty()
                    ? null
                    : view.getSourceUrls().get(0);
            fragments.add(EvidenceFragment.builder()
                    .stage("ANALYZE")
                    .competitorName(competitorName)
                    .fieldName(mapping.analysisFieldKey())
                    .fieldLabel(mapping.sectionTitle())
                    .sectionKey(mapping.sectionKey())
                    .sectionTitle(mapping.sectionTitle())
                    .evidenceId(view.getEvidenceId())
                    .sourceUrl(primarySourceUrl)
                    .title(view.getTitle())
                    .snippet(firstNonBlank(truncate(view.getContent(), 180), summarizeStructuredBlocks(view)))
                    .issueFlags(view.getIssueFlags() == null ? List.of() : view.getIssueFlags())
                    .build()
                    .normalized());
        }
        return fragments;
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
                        .stage("ANALYZE")
                        .competitorName(knowledge.getCompetitorName())
                        .fieldName(mapping.analysisFieldKey())
                        .fieldLabel(mapping.sectionTitle())
                        .sectionKey(mapping.sectionKey())
                        .sectionTitle(mapping.sectionTitle())
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
                .stage("ANALYZE")
                .competitorName(knowledge.getCompetitorName())
                .fieldName(mapping.analysisFieldKey())
                .fieldLabel(mapping.sectionTitle())
                .sectionKey(mapping.sectionKey())
                .sectionTitle(mapping.sectionTitle())
                .coverageStatus(status)
                .issueFlags(isTraceableCoverageStatus(status) ? List.of() : List.of(status))
                .gapComment(buildCoverageGapComment(status))
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
                                                       List<CompetitorKnowledgeDraft> drafts,
                                                       List<DownstreamEvidenceView> downstreamEvidenceViews,
                                                       LinkedHashSet<String> inheritedIssueFlags,
                                                       List<SectionEvidenceBundle> sectionBundles) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        List<EvidenceFragment> conclusionFragments = new ArrayList<>();
        for (SectionEvidenceBundle sectionBundle : sectionBundles) {
            sourceUrls.addAll(sectionBundle.getSourceUrls() == null ? List.of() : sectionBundle.getSourceUrls());
            conclusionFragments.addAll(sectionBundle.getEvidenceFragments() == null ? List.of() : sectionBundle.getEvidenceFragments());
        }
        sourceUrls.addAll(collectKnowledgeSourceUrls(knowledges));
        sourceUrls.addAll(collectDraftSourceUrls(drafts));
        sourceUrls.addAll(collectEvidenceViewSourceUrls(downstreamEvidenceViews));
        if (conclusionFragments.isEmpty()) {
            conclusionFragments.addAll(buildDraftEvidenceFragments(drafts, inheritedIssueFlags));
            conclusionFragments.addAll(buildKnowledgeEvidenceFragments(knowledges, inheritedIssueFlags));
            conclusionFragments.addAll(buildEvidenceViewFragments(downstreamEvidenceViews, inheritedIssueFlags));
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

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof JsonNode node) {
            return hasMeaningfulJsonNode(node);
        }
        return hasMeaningfulJsonNode(objectMapper.valueToTree(value));
    }

    /**
     * 有些字段在 draft 里会以空对象 `{}` 的形式出现。
     * 这里把“只有空壳、没有任何非空子字段”的对象视为无效值，允许 snapshot 做补空。
     */
    private boolean hasMeaningfulJsonNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasMeaningfulJsonNode(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (hasMeaningfulJsonNode(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean sameValue(Object left, Object right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        try {
            return objectMapper.writeValueAsString(left).equals(objectMapper.writeValueAsString(right));
        } catch (JsonProcessingException e) {
            return left.equals(right);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private List<EvidenceFragment> normalizeEvidenceFragments(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> normalized = new ArrayList<>();
        for (EvidenceFragment fragment : fragments == null ? List.<EvidenceFragment>of() : fragments) {
            if (fragment != null) {
                normalized.add(fragment.normalized());
            }
        }
        return normalized;
    }

    private List<SectionEvidenceBundle> normalizeSectionEvidenceBundles(List<SectionEvidenceBundle> bundles) {
        List<SectionEvidenceBundle> normalized = new ArrayList<>();
        for (SectionEvidenceBundle bundle : bundles == null ? List.<SectionEvidenceBundle>of() : bundles) {
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

    private String summarizeStructuredBlocks(DownstreamEvidenceView view) {
        if (view == null || view.getStructuredBlocks() == null || view.getStructuredBlocks().isEmpty()) {
            return null;
        }
        List<String> summaries = new ArrayList<>();
        view.getStructuredBlocks().stream().limit(2).forEach(block -> {
            if (block != null) {
                String summary = firstNonBlank(block.getSummary(), block.getBlockType());
                if (summary != null && !summary.isBlank()) {
                    summaries.add(summary);
                }
            }
        });
        if (summaries.isEmpty()) {
            return null;
        }
        return truncate(String.join(" | ", summaries), 180);
    }

    private record SectionMapping(String sectionKey,
                                  String analysisFieldKey,
                                  String sectionTitle,
                                  String knowledgeFieldKey,
                                  String sectionType) {
    }

    private record ExtractRuntimeOutput(List<CompetitorKnowledgeDraft> drafts,
                                        List<DownstreamEvidenceView> downstreamEvidenceViews) {
    }
}
