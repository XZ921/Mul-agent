package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.memory.MemoryWritebackService;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writer Agent，负责把分析结果与证据列表组织成 Markdown 报告。
 * V2 中同一个 Writer 同时承担首稿生成与按修订计划重写两种模式。
 */
@Slf4j
@Component
public class ReportWriterAgent extends BaseAgent {

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final MemoryWritebackService memoryWritebackService;
    private final ObjectMapper objectMapper;

    public ReportWriterAgent(AgentExecutionLogRepository logRepository,
                             ReportRepository reportRepository,
                             EvidenceSourceRepository evidenceRepository,
                             LlmClient llmClient,
                             PromptTemplateService promptService,
                             AgentContextAssembler agentContextAssembler,
                             MemoryWritebackService memoryWritebackService,
                             ObjectMapper objectMapper) {
        super(logRepository, agentContextAssembler);
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.memoryWritebackService = memoryWritebackService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.WRITER;
    }

    @Override
    public String getName() {
        return "报告撰写智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // Writer 必须消费 Analyzer 的结构化分析结果，否则没有可写入的业务内容。
        String analysisResult = context.getSharedOutput("analyze_competitors");
        if (analysisResult == null || analysisResult.isBlank()) {
            return AgentResult.failed("缺少分析结果，请先执行竞品分析节点");
        }

        // 当节点被配置为 revision 模式时，必须显式带上 Reviewer 产出的修订计划。
        boolean revisionMode = isRevisionMode(context.getCurrentNodeConfig());
        JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
        JsonNode revisionPlan = extractRevisionPlan(reviewOutput);
        if (revisionMode && (revisionPlan == null || !revisionPlan.path("rewriteRequired").asBoolean(false))) {
            return AgentResult.failed("修订模式缺少有效的质量评审修订计划");
        }

        // 证据列表直接注入提示词，约束 Writer 输出必须围绕已采集证据展开。
        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        StringBuilder evidenceList = new StringBuilder();
        for (EvidenceSource ev : evidences) {
            evidenceList.append(String.format("- [%s] %s (%s)\n", ev.getEvidenceId(), ev.getTitle(), ev.getUrl()));
        }

        // 如果任务已经有旧版报告，revision 模式会把它作为“待修订底稿”传给模型。
        String currentReport = reportRepository.findByTaskId(context.getTaskId())
                .map(Report::getContent)
                .orElse("");
        String revisionFocus = buildRevisionFocus(revisionPlan);
        NormalizedAnalysisPayload normalizedAnalysis = normalizeAnalysisPayload(analysisResult, evidences);

        // 同一套 writer 模板通过 revisionMode 与 revisionPlan 控制“首稿/重写”分支。
        String prompt = promptService.render("writer", Map.of(
                "taskName", safe(context.getTaskName()),
                "subjectProduct", safe(context.getSubjectProduct()),
                "reportLanguage", safe(context.getReportLanguage(), "中文"),
                "analysisResult", normalizedAnalysis.serializedAnalysis(),
                // Writer 在撰写阶段也需要感知统一检索摘要，避免把证据缺口写成确定性结论。
                "taskRagContext", context.getTaskRagPromptContext(),
                "currentReport", currentReport,
                "evidenceList", evidenceList.toString(),
                "revisionMode", String.valueOf(revisionMode),
                "revisionPlan", revisionPlan == null ? "[]" : revisionPlan.toPrettyString(),
                "revisionFocus", revisionFocus
        ));

        try {
            // 统一走 LLM 生成报告正文，便于后续在日志中追踪 token 使用与模型名称。
            String reportContent = llmClient.chat(
                    "你是一名专业的中文竞品分析报告撰写专家，请输出高质量 Markdown 报告。",
                    prompt
            );

            // 报告记录是幂等更新：首次创建，后续重写直接覆盖正文和摘要。
            Report report = reportRepository.findByTaskId(context.getTaskId())
                    .orElse(Report.builder().taskId(context.getTaskId()).build());
            report.setTitle(context.getTaskName() + " - 竞品分析报告");
            report.setContent(reportContent);
            report.setEvidenceCount(evidences.size());
            report.setSummary(buildSummary(reportContent, revisionMode));
            reportRepository.save(report);
            // 任务级报告写回只作为“可复用上下文增强”，不能反向阻塞报告主链路。
            // 因此这里采用独立 try-catch，把 traceable 结论沉淀为短期记忆，失败仅记日志。
            writeTraceableConclusionBack(context, report, normalizedAnalysis, evidences, reportContent, revisionMode);

            log.info("report writing done, taskId={}, revisionMode={}, length={}",
                    context.getTaskId(), revisionMode, reportContent.length());

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("contractVersion", "1.0");
            output.put("content", reportContent);
            output.put("summary", report.getSummary());
            output.put("revisionMode", revisionMode);
            output.put("sourceUrls", normalizedAnalysis.sourceUrls());
            // 报告输出保留本次写作真正使用的 Task RAG 摘要，方便报告页和审计页直接回查。
            output.put("taskRagContext", context.getTaskRagPromptContext());
            output.put("issueFlags", normalizedAnalysis.issueFlags());
            output.put("evidenceFragments", normalizedAnalysis.evidenceFragments());
            output.put("sectionEvidenceBundles", normalizedAnalysis.sectionEvidenceBundles());
            output.put("revisionFocus", revisionFocus);
            String outputJson = objectMapper.writeValueAsString(output);

            return AgentResult.success(outputJson,
                    String.format("报告已生成，长度=%d，证据数=%d", reportContent.length(), evidences.size()),
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("report writing failed", e);
            return AgentResult.failed("报告撰写失败：" + e.getMessage());
        }
    }

    // 节点配置里通过 mode=revision 显式切换到重写模式。
    private boolean isRevisionMode(String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return false;
        }
        try {
            Map<?, ?> config = objectMapper.readValue(nodeConfig, Map.class);
            Object mode = config.get("mode");
            return "revision".equalsIgnoreCase(String.valueOf(mode));
        } catch (Exception e) {
            return nodeConfig.contains("\"mode\":\"revision\"");
        }
    }

    // 摘要只保留报告前部内容，便于列表页和概览页快速展示。
    private String buildSummary(String content, boolean revisionMode) {
        String prefix = revisionMode ? "修订报告：" : "初版报告：";
        if (content == null) {
            return prefix;
        }
        String trimmed = content.replace("\n", " ");
        return prefix + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 把修订计划压缩成 Writer 更容易消费的“重点章节 + 证据动作”摘要，避免模型只做表面润色。
     */
    private String buildRevisionFocus(JsonNode revisionPlan) {
        if (revisionPlan == null || revisionPlan.isMissingNode() || revisionPlan.isNull()) {
            return "无";
        }
        List<String> focusItems = new ArrayList<>();
        JsonNode items = revisionPlan.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String section = item.path("section").asText("通用");
                String type = item.path("type").asText("UNKNOWN");
                String severity = item.path("severity").asText("WARNING");
                String suggestion = item.path("suggestion").asText("请补充完善");
                if (isEvidenceDrivenIssue(type)) {
                    focusItems.add(section + " [" + severity + "]：必须补充可回指证据引用，建议为 " + suggestion);
                } else {
                    focusItems.add(section + " [" + severity + "]：" + suggestion);
                }
            }
        }
        JsonNode guidelines = revisionPlan.path("rewriteGuidelines");
        if (guidelines.isArray()) {
            for (JsonNode item : guidelines) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    focusItems.add(value);
                }
            }
        }
        if (focusItems.isEmpty()) {
            return "请优先修复评审中标记的问题，并确保关键判断附带 [证据：EID] 形式引用。";
        }
        return String.join("\n", focusItems);
    }

    private boolean isEvidenceDrivenIssue(String type) {
        String normalized = type == null ? "" : type.toLowerCase();
        return normalized.contains("evidence")
                || normalized.contains("claim")
                || normalized.contains("证据")
                || normalized.contains("支撑");
    }

    // Reviewer 结果既可能是对象，也可能被序列化成字符串，这里统一兜底解析。
    private JsonNode extractRevisionPlan(JsonNode reviewOutput) {
        if (reviewOutput == null || !reviewOutput.has("revisionPlan")) {
            return null;
        }
        JsonNode revisionPlan = reviewOutput.get("revisionPlan");
        if (revisionPlan.isTextual()) {
            return readJson(revisionPlan.asText());
        }
        return revisionPlan;
    }

    // 安全 JSON 解析，避免单个脏字段让 Writer 整体失败。
    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writer 只接收 Analyzer 的共享输出字符串，但 Analyzer 可能处于新旧契约切换期。
     * 这里统一把旧格式补齐成“分析内容 + sourceUrls + issueFlags + evidenceFragments”，保证写作阶段不再丢证据引用。
     */
    private NormalizedAnalysisPayload normalizeAnalysisPayload(String analysisResult, List<EvidenceSource> evidences) {
        JsonNode rawJson = readJson(analysisResult);
        ObjectNode normalized = rawJson instanceof ObjectNode objectNode
                ? objectNode.deepCopy()
                : objectMapper.createObjectNode().put("legacyAnalysisText", safe(analysisResult));
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(readStringList(normalized.path("sourceUrls")));
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>(readStringList(normalized.path("issueFlags")));
        List<EvidenceFragment> evidenceFragments = readEvidenceFragments(normalized.path("evidenceFragments"));
        List<SectionEvidenceBundle> sectionEvidenceBundles = readSectionEvidenceBundles(normalized.path("sectionEvidenceBundles"));

        if (sourceUrls.isEmpty()) {
            for (EvidenceSource evidence : evidences) {
                if (evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                    sourceUrls.add(evidence.getUrl());
                }
            }
            if (!sourceUrls.isEmpty()) {
                issueFlags.add("SOURCE_URLS_BACKFILLED");
            }
        }

        if (evidenceFragments.isEmpty()) {
            evidenceFragments = buildEvidenceFragmentsFromEvidence(evidences, issueFlags);
        }
        sectionEvidenceBundles = ensureWriterSectionBundles(normalized, sectionEvidenceBundles, evidenceFragments, sourceUrls, issueFlags);

        normalized.putPOJO("sourceUrls", new ArrayList<>(sourceUrls));
        normalized.putPOJO("issueFlags", new ArrayList<>(issueFlags));
        normalized.putPOJO("evidenceFragments", normalizeEvidenceFragments(evidenceFragments));
        normalized.putPOJO("sectionEvidenceBundles", normalizeSectionEvidenceBundles(sectionEvidenceBundles));
        return new NormalizedAnalysisPayload(normalized.toPrettyString(),
                new ArrayList<>(sourceUrls),
                new ArrayList<>(issueFlags),
                normalizeEvidenceFragments(evidenceFragments),
                normalizeSectionEvidenceBundles(sectionEvidenceBundles));
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

    private List<EvidenceFragment> readEvidenceFragments(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (JsonNode item : node) {
            fragments.add(objectMapper.convertValue(item, EvidenceFragment.class).normalized());
        }
        return fragments;
    }

    private List<SectionEvidenceBundle> readSectionEvidenceBundles(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SectionEvidenceBundle> bundles = new ArrayList<>();
        for (JsonNode item : node) {
            bundles.add(objectMapper.convertValue(item, SectionEvidenceBundle.class).normalized());
        }
        return bundles;
    }

    /**
     * 当 Analyzer 没显式传 evidenceFragments 时，Writer 退回到任务证据列表补齐。
     * 这样报告输出至少还能明确告诉后续阶段“哪些 URL 被拿来支撑了本次写作”。
     */
    private List<EvidenceFragment> buildEvidenceFragmentsFromEvidence(List<EvidenceSource> evidences,
                                                                      LinkedHashSet<String> issueFlags) {
        List<EvidenceFragment> fragments = new ArrayList<>();
        for (EvidenceSource evidence : evidences) {
            fragments.add(EvidenceFragment.builder()
                    .stage("WRITE")
                    .competitorName(evidence.getCompetitorName())
                    .fieldName("report")
                    .evidenceId(evidence.getEvidenceId())
                    .sourceUrl(evidence.getUrl())
                    .title(evidence.getTitle())
                    .snippet(evidence.getContentSnippet())
                    .issueFlags(new ArrayList<>(issueFlags))
                    .build()
                    .normalized());
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
     * Writer 阶段无论 Analyzer 是否显式提供章节证据束，都补上一份 report_conclusion，
     * 这样最终报告接口总能回答“这段结论用了哪些来源、有没有明显证据缺口”。
     */
    private List<SectionEvidenceBundle> ensureWriterSectionBundles(ObjectNode normalized,
                                                                  List<SectionEvidenceBundle> inheritedBundles,
                                                                  List<EvidenceFragment> evidenceFragments,
                                                                  LinkedHashSet<String> sourceUrls,
                                                                  LinkedHashSet<String> issueFlags) {
        List<SectionEvidenceBundle> bundles = new ArrayList<>(normalizeSectionEvidenceBundles(inheritedBundles));
        boolean hasReportConclusion = bundles.stream()
                .anyMatch(bundle -> "report_conclusion".equals(bundle.getSectionKey()));
        if (!hasReportConclusion) {
            LinkedHashSet<String> missingFields = new LinkedHashSet<>();
            if (issueFlags.contains("MISSING_EVIDENCE")) {
                missingFields.add("recommendations");
            }
            if (sourceUrls.isEmpty()) {
                missingFields.add("report");
            }
            bundles.add(SectionEvidenceBundle.builder()
                    .stage("WRITE")
                    .sectionType("CONCLUSION")
                    .sectionKey("report_conclusion")
                    .sectionTitle("报告结论")
                    .summary(firstNonBlank(
                            normalized.path("overview").asText(null),
                            normalized.path("featureComparison").asText(null)
                    ))
                    .fieldNames(List.of("report", "recommendations"))
                    .missingFields(new ArrayList<>(missingFields))
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .issueFlags(missingFields.isEmpty() ? List.of() : List.of("SECTION_EVIDENCE_GAP"))
                    .evidenceFragments(evidenceFragments)
                    .build()
                    .normalized());
        }
        return bundles;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    /**
     * Writer 产出的报告结论已经是“任务内可直接消费”的短期资产，
     * 这里把结论、sourceUrls、问题标记与任务上下文一起写回，供后续重跑或同计划版本复用。
     */
    private void writeTraceableConclusionBack(AgentContext context,
                                              Report report,
                                              NormalizedAnalysisPayload normalizedAnalysis,
                                              List<EvidenceSource> evidences,
                                              String reportContent,
                                              boolean revisionMode) {
        try {
            MemoryWritebackService.WritebackRequest request = MemoryWritebackService.WritebackRequest.builder()
                    .taskId(context.getTaskId())
                    .planVersionId(context.getPlanVersionId())
                    .branchKey(context.getBranchKey())
                    .nodeName(firstNonBlank(context.getCurrentNodeName(), "write_report"))
                    .competitorName(resolvePrimaryCompetitorName(evidences))
                    .snapshotType("REPORT_CONCLUSION")
                    .queryText(resolveWritebackQuery(context))
                    .summary(firstNonBlank(report.getSummary(), buildSummary(reportContent, revisionMode)))
                    .gapSummary(buildGapSummary(normalizedAnalysis.issueFlags()))
                    .sourceUrls(normalizedAnalysis.sourceUrls())
                    .issueFlags(normalizedAnalysis.issueFlags())
                    .contextPayload(buildWriterWritebackContext(reportContent, normalizedAnalysis))
                    .writebackCategory("VERIFIED_TASK_CONCLUSION")
                    .qualitySignal("TRACEABLE")
                    .reuseReason("当前报告结论已绑定 sourceUrls，仅在同计划版本任务上下文内复用")
                    .build();
            memoryWritebackService.writeback(request);
        } catch (Exception e) {
            log.warn("report writeback skipped, taskId={}, nodeName={}, reason={}",
                    context.getTaskId(), context.getCurrentNodeName(), e.getMessage(), e);
        }
    }

    private String resolvePrimaryCompetitorName(List<EvidenceSource> evidences) {
        if (evidences == null) {
            return null;
        }
        for (EvidenceSource evidence : evidences) {
            if (evidence != null && evidence.getCompetitorName() != null && !evidence.getCompetitorName().isBlank()) {
                return evidence.getCompetitorName();
            }
        }
        return null;
    }

    private String resolveWritebackQuery(AgentContext context) {
        if (context.getTaskRagContextBundle() != null
                && context.getTaskRagContextBundle().getQuery() != null
                && !context.getTaskRagContextBundle().getQuery().isBlank()) {
            return context.getTaskRagContextBundle().getQuery();
        }
        return firstNonBlank(context.getTaskName(), context.getSubjectProduct());
    }

    private String buildGapSummary(List<String> issueFlags) {
        if (issueFlags == null || issueFlags.isEmpty()) {
            return "当前报告结论已完成可追溯整理";
        }
        return "报告写回时仍需关注的问题标记：" + String.join(", ", issueFlags);
    }

    private String buildWriterWritebackContext(String reportContent,
                                               NormalizedAnalysisPayload normalizedAnalysis) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportContent", reportContent);
        payload.put("sourceUrls", normalizedAnalysis.sourceUrls());
        payload.put("issueFlags", normalizedAnalysis.issueFlags());
        payload.put("evidenceFragments", normalizedAnalysis.evidenceFragments());
        payload.put("sectionEvidenceBundles", normalizedAnalysis.sectionEvidenceBundles());
        return objectMapper.writeValueAsString(payload);
    }

    private record NormalizedAnalysisPayload(String serializedAnalysis,
                                             List<String> sourceUrls,
                                             List<String> issueFlags,
                                             List<EvidenceFragment> evidenceFragments,
                                             List<SectionEvidenceBundle> sectionEvidenceBundles) {
    }
}
