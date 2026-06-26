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

import java.time.LocalDate;
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
    private final WriterCitationGapInspector writerCitationGapInspector;

    public ReportWriterAgent(AgentExecutionLogRepository logRepository,
                             ReportRepository reportRepository,
                             EvidenceSourceRepository evidenceRepository,
                             LlmClient llmClient,
                             PromptTemplateService promptService,
                             AgentContextAssembler agentContextAssembler,
                             MemoryWritebackService memoryWritebackService,
                             ObjectMapper objectMapper,
                             WriterCitationGapInspector writerCitationGapInspector) {
        super(logRepository, agentContextAssembler);
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.memoryWritebackService = memoryWritebackService;
        this.objectMapper = objectMapper;
        this.writerCitationGapInspector = writerCitationGapInspector;
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
        String evidenceCitationGuide = buildEvidenceCitationGuide(evidences, revisionMode);
        String currentDate = LocalDate.now().toString();

        // Writer prompt 变量已经超过 Map.of 的 10 对上限，使用有序 Map 保持模板输入稳定可扩展。
        Map<String, String> promptVariables = new LinkedHashMap<>();
        promptVariables.put("taskName", safe(context.getTaskName()));
        promptVariables.put("subjectProduct", safe(context.getSubjectProduct()));
        promptVariables.put("reportLanguage", safe(context.getReportLanguage(), "中文"));
        promptVariables.put("currentDate", currentDate);
        promptVariables.put("analysisResult", normalizedAnalysis.serializedAnalysis());
        // Writer 在撰写阶段也需要感知统一检索摘要，避免把证据缺口写成确定性结论。
        promptVariables.put("taskRagContext", context.getTaskRagPromptContext());
        promptVariables.put("currentReport", currentReport);
        promptVariables.put("evidenceList", evidenceList.toString());
        promptVariables.put("revisionMode", String.valueOf(revisionMode));
        promptVariables.put("revisionPlan", revisionPlan == null ? "[]" : revisionPlan.toPrettyString());
        promptVariables.put("revisionFocus", revisionFocus);
        promptVariables.put("evidenceCitationGuide", evidenceCitationGuide);
        String prompt = promptService.render("writer", promptVariables);

        try {
            // 统一走 LLM 生成报告正文，便于后续在日志中追踪 token 使用与模型名称。
            String reportContent = llmClient.chat(
                    "你是一名专业的中文竞品分析报告撰写专家，请输出高质量 Markdown 报告。",
                    prompt
            );
            reportContent = sanitizeReportContent(reportContent, currentDate);
            reportContent = enforceEvidenceTraceabilityForKeySections(reportContent);
            WriterCitationGapInspector.InspectionResult citationInspection = writerCitationGapInspector.inspect(
                    reportContent,
                    normalizedAnalysis.sectionEvidenceBundles(),
                    normalizedAnalysis.sourceUrls());
            List<String> outputIssueFlags = mergeIssueFlags(
                    normalizedAnalysis.issueFlags(),
                    citationInspection.issueFlags());

            // 报告记录是幂等更新：首次创建，后续重写直接覆盖正文和摘要。
            Report report = reportRepository.findByTaskId(context.getTaskId())
                    .orElse(Report.builder().taskId(context.getTaskId()).build());
            report.setTitle(context.getTaskName() + " - 竞品分析报告");
            report.setContent(reportContent);
            report.setEvidenceCount(evidences.size());
            report.setSummary(buildSummary(reportContent, revisionMode));
            // 只持久化 Writer 已经识别出的写作证据事实，不在这里生成补证、重写或人工介入决策。
            report.setWriterEvidenceState(citationInspection.evidenceState());
            report.setCitationGapSeverity(citationInspection.severity());
            report.setMissingCitationSections(toJsonArray(citationInspection.missingCitationSections()));
            report.setSectionCitationGaps(toJsonArray(citationInspection.gaps()));
            report.setWriterIssueFlags(toJsonArray(citationInspection.issueFlags()));
            report.setWriterSourceUrls(toJsonArray(normalizedAnalysis.sourceUrls()));
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
            output.put("writerEvidenceState", citationInspection.evidenceState());
            output.put("citationGapSeverity", citationInspection.severity());
            output.put("missingCitationSections", citationInspection.missingCitationSections());
            output.put("sectionCitationGaps", citationInspection.gaps());
            // 报告输出保留本次写作真正使用的 Task RAG 摘要，方便报告页和审计页直接回查。
            output.put("taskRagContext", context.getTaskRagPromptContext());
            output.put("issueFlags", outputIssueFlags);
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
    /**
     * Writer 给模型的证据目录需要直接呈现可复制的 `[证据：EID]` 片段。
     * 真实任务中模型会看到 `- [EID] title(url)` 却忘记转成报告内引用，因此这里把报告章节规则和引用样式前置。
     */
    /**
     * Writer 的输出必须是可直接交付的报告正文，不能带模型自我说明或过期报告日期。
     * 这里只处理报告壳层卫生问题，不改写业务判断，避免把清洗逻辑变成新的事实来源。
     */
    private String sanitizeReportContent(String reportContent, String currentDate) {
        if (reportContent == null || reportContent.isBlank()) {
            return reportContent;
        }
        String content = stripMarkdownFence(reportContent.strip());
        StringBuilder sanitized = new StringBuilder();
        boolean reportBodyStarted = false;
        String[] lines = content.split("\\R", -1);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("#")) {
                reportBodyStarted = true;
            }
            if (!reportBodyStarted && isLeadingMetaCommentary(trimmed)) {
                continue;
            }
            sanitized.append(normalizeReportDateLine(line, currentDate)).append('\n');
        }
        if (sanitized.length() > 0) {
            sanitized.setLength(sanitized.length() - 1);
        }
        return sanitized.toString().strip();
    }

    private String stripMarkdownFence(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length < 2 || !lines[lines.length - 1].trim().equals("```")) {
            return trimmed;
        }
        StringBuilder unwrapped = new StringBuilder();
        for (int index = 1; index < lines.length - 1; index++) {
            unwrapped.append(lines[index]).append('\n');
        }
        if (unwrapped.length() > 0) {
            unwrapped.setLength(unwrapped.length() - 1);
        }
        return unwrapped.toString().strip();
    }

    private boolean isLeadingMetaCommentary(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isBlank()) {
            return false;
        }
        return trimmedLine.startsWith("好的")
                || trimmedLine.startsWith("当然")
                || trimmedLine.startsWith("以下是")
                || trimmedLine.startsWith("我将")
                || trimmedLine.contains("作为一名")
                || trimmedLine.contains("报告撰写专家");
    }

    private String normalizeReportDateLine(String line, String currentDate) {
        if (line == null || line.isBlank()) {
            return line;
        }
        String trimmed = line.trim();
        String prefix = resolveReportDatePrefix(trimmed);
        if (prefix == null) {
            return line;
        }
        String leadingWhitespace = line.substring(0, line.length() - line.stripLeading().length());
        return leadingWhitespace + prefix + currentDate;
    }

    private String resolveReportDatePrefix(String trimmedLine) {
        List<String> labels = List.of("报告日期", "生成日期", "撰写日期", "日期");
        for (String label : labels) {
            if (trimmedLine.startsWith(label + "：")) {
                return label + "：";
            }
            if (trimmedLine.startsWith(label + ":")) {
                return label + ": ";
            }
        }
        return null;
    }

    private String buildEvidenceCitationGuide(List<EvidenceSource> evidences, boolean revisionMode) {
        List<String> lines = new ArrayList<>();
        lines.add("证据引用规则：");
        lines.add("1. 建议、结论、风险、机会、启示与行动建议中的每个判断句，必须追加 `[证据：EID]`。");
        lines.add("2. 如果某个本方策略建议无法由公开证据直接支撑，必须写成“推测，当前公开资料未能验证，需补充证据”。");
        lines.add("3. 不允许只在段落开头列 URL；正文判断句必须逐句带证据编号或显式降级。");
        lines.add("4. 可用证据引用目录如下：");
        if (evidences == null || evidences.isEmpty()) {
            lines.add("- 暂无可用 evidenceId；所有建议性结论都必须降级为“当前公开资料未能验证，需补充证据”。");
        } else {
            for (EvidenceSource evidence : evidences) {
                if (evidence == null || evidence.getEvidenceId() == null || evidence.getEvidenceId().isBlank()) {
                    continue;
                }
                lines.add("- [证据：" + evidence.getEvidenceId() + "] "
                        + safe(evidence.getTitle())
                        + (evidence.getUrl() == null || evidence.getUrl().isBlank() ? "" : " | " + evidence.getUrl()));
            }
        }
        if (revisionMode) {
            lines.add("5. 当前为 rewrite_report：修订重点里的每个缺证据问题都必须在正文中落实，不要只写修订说明。");
        }
        return String.join("\n", lines);
    }

    /**
     * 兜底处理 Writer 偶发“引用了证据目录但正文建议句仍裸奔”的情况。
     * 这里不自动给本方策略建议硬贴证据，避免把推演伪装成事实；没有逐句证据时统一降级为待验证假设。
     */
    private String enforceEvidenceTraceabilityForKeySections(String reportContent) {
        if (reportContent == null || reportContent.isBlank()) {
            return reportContent;
        }
        StringBuilder guarded = new StringBuilder();
        boolean keySection = false;
        String[] lines = reportContent.split("\\R", -1);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("#")) {
                keySection = isTraceabilitySensitiveSection(trimmed);
                guarded.append(line);
            } else if (keySection) {
                guarded.append(guardTraceabilityLine(line));
            } else {
                guarded.append(line);
            }
            guarded.append('\n');
        }
        if (!reportContent.endsWith("\n") && guarded.length() > 0) {
            guarded.setLength(guarded.length() - 1);
        }
        return guarded.toString();
    }

    private boolean isTraceabilitySensitiveSection(String heading) {
        if (heading == null || heading.isBlank()) {
            return false;
        }
        return heading.contains("建议")
                || heading.contains("结论")
                || heading.contains("风险")
                || heading.contains("机会")
                || heading.contains("启示")
                || heading.contains("行动");
    }

    private String guardTraceabilityLine(String line) {
        if (line == null || line.isBlank()) {
            return line;
        }
        String trimmed = line.trim();
        if (trimmed.equals("---") || trimmed.startsWith("|")) {
            return line;
        }
        String[] sentences = line.split("(?<=[。！？；;.!?])\\s*");
        StringBuilder guarded = new StringBuilder();
        for (String sentence : sentences) {
            if (needsTraceabilityGuard(sentence)) {
                guarded.append(appendConservativeDowngrade(sentence));
            } else {
                guarded.append(sentence);
            }
        }
        return guarded.toString();
    }

    private boolean needsTraceabilityGuard(String sentence) {
        if (sentence == null) {
            return false;
        }
        String compact = sentence.replaceAll("\\s+", " ").trim();
        if (compact.length() < 16) {
            return false;
        }
        return !hasEvidenceCitation(compact) && !hasExplicitConservativeDowngrade(compact);
    }

    private String appendConservativeDowngrade(String sentence) {
        String suffix = "（推测，当前公开资料未能验证，需补充证据）";
        if (sentence == null || sentence.isBlank()) {
            return sentence;
        }
        int last = sentence.length() - 1;
        char ch = sentence.charAt(last);
        if ("。！？；;.!?".indexOf(ch) >= 0) {
            return sentence.substring(0, last) + suffix + ch;
        }
        return sentence + suffix;
    }

    private boolean hasEvidenceCitation(String text) {
        return text != null && (text.contains("[证据：") || text.contains("[证据:"));
    }

    private boolean hasExplicitConservativeDowngrade(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("当前公开资料未能验证")
                || text.contains("公开资料未能验证")
                || text.contains("需补充证据")
                || text.contains("待验证")
                || text.contains("低置信度");
    }

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
        sectionEvidenceBundles = ensureWriterSectionBundles(normalized, sectionEvidenceBundles, evidenceFragments, sourceUrls);

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
                                                                  LinkedHashSet<String> sourceUrls) {
        List<SectionEvidenceBundle> bundles = new ArrayList<>(normalizeSectionEvidenceBundles(inheritedBundles));
        boolean hasReportConclusion = bundles.stream()
                .anyMatch(bundle -> "report_conclusion".equals(bundle.getSectionKey()));
        if (!hasReportConclusion) {
            LinkedHashSet<String> missingFields = new LinkedHashSet<>();
            // report_conclusion 只反映“结论章节自身”的缺口。
            // 如果上游只是 summary/pricing 等其他字段缺证据，但 recommendations 已经产出，
            // 这里不能把全局 MISSING_EVIDENCE 放大成结论章节缺口，否则会误导后续诊断。
            if (isWriterRecommendationMissing(normalized)) {
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

    /**
     * Writer 兜底生成 report_conclusion 时，只在 recommendations 自身为空时标记章节缺口。
     * 这样可以避免 analyzer/extractor 的字段级 MISSING_EVIDENCE 被错误提升为结论章节的 SECTION_EVIDENCE_GAP。
     */
    private boolean isWriterRecommendationMissing(ObjectNode normalized) {
        JsonNode recommendations = normalized == null ? null : normalized.path("recommendations");
        if (recommendations == null || recommendations.isMissingNode() || recommendations.isNull()) {
            return true;
        }
        if (recommendations.isArray()) {
            return recommendations.isEmpty();
        }
        if (recommendations.isTextual()) {
            return recommendations.asText().isBlank();
        }
        return recommendations.isEmpty();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    /**
     * Writer 输出的问题标记需要同时保留 Analyzer 上游缺口和本阶段引用缺口，
     * 这样 Orchestrator 才能区分“上游分析缺证据”与“写作阶段章节引用缺口”两个来源。
     */
    private List<String> mergeIssueFlags(List<String> upstreamIssueFlags, List<String> writerIssueFlags) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (upstreamIssueFlags != null) {
            merged.addAll(upstreamIssueFlags);
        }
        if (writerIssueFlags != null) {
            merged.addAll(writerIssueFlags);
        }
        return new ArrayList<>(merged);
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

    /**
     * Writer 快照字段统一以 JSON 数组文本落库，便于历史报告查询时稳定反序列化投影。
     */
    private String toJsonArray(Object value) throws Exception {
        return objectMapper.writeValueAsString(value == null ? List.of() : value);
    }

    private record NormalizedAnalysisPayload(String serializedAnalysis,
                                             List<String> sourceUrls,
                                             List<String> issueFlags,
                                             List<EvidenceFragment> evidenceFragments,
                                             List<SectionEvidenceBundle> sectionEvidenceBundles) {
    }
}
