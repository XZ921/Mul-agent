package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质检 Agent。
 * 负责对报告做质量评分，生成问题列表和修订计划，驱动 Writer / Reviewer 闭环。
 */
@Slf4j
@Component
public class QualityReviewAgent extends BaseAgent {
    private static final Pattern EVIDENCE_PATTERN = Pattern.compile("\\[证据[:：]\\s*([^\\]]+)]");
    private static final int MAX_CLAIM_AUDIT_ITEMS = 12;
    private static final List<SectionRule> CLAIM_AUDIT_RULES = List.of(
            new SectionRule("结论", List.of("产品概览", "市场定位", "目标用户", "核心能力", "定价策略", "优势判断", "短板与风险"), "ERROR"),
            new SectionRule("建议", List.of("市场定位", "目标用户", "核心能力", "定价策略", "优势判断", "短板与风险"), "WARNING"),
            new SectionRule("风险点", List.of("短板与风险"), "WARNING"),
            new SectionRule("机会点", List.of("优势判断", "核心能力", "市场定位"), "WARNING"),
            new SectionRule("功能对比", List.of("核心能力"), "WARNING"),
            new SectionRule("定价对比", List.of("定价策略"), "WARNING"),
            new SectionRule("目标用户对比", List.of("目标用户"), "WARNING"),
            new SectionRule("定位对比", List.of("市场定位"), "WARNING"),
            new SectionRule("优势分析", List.of("优势判断"), "WARNING"),
            new SectionRule("不足分析", List.of("短板与风险"), "WARNING")
    );

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public QualityReviewAgent(AgentExecutionLogRepository logRepository,
                              ReportRepository reportRepository,
                              EvidenceSourceRepository evidenceRepository,
                              CompetitorKnowledgeRepository knowledgeRepository,
                              LlmClient llmClient,
                              PromptTemplateService promptService,
                              ObjectMapper objectMapper) {
        super(logRepository);
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.REVIEWER;
    }

    @Override
    public String getName() {
        return "质量评审智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // Reviewer 只评审当前任务已生成的报告正文，没有报告则无法进入质检。
        Report report = reportRepository.findByTaskId(context.getTaskId()).orElse(null);
        if (report == null || report.getContent() == null || report.getContent().isBlank()) {
            return AgentResult.failed("缺少可评审的报告内容");
        }

        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        String evidenceList = buildEvidenceList(evidences);
        String evidenceCoverageSummary = buildEvidenceCoverageSummary(context.getTaskId());
        String claimAuditChecklist = buildClaimAuditChecklist(report.getContent(), evidenceCoverageSummary);
        boolean finalPass = isFinalReview(context.getCurrentNodeConfig());

        String prompt = promptService.render("reviewer", Map.of(
                "reportContent", report.getContent(),
                "evidenceList", evidenceList,
                "evidenceCoverageSummary", evidenceCoverageSummary,
                "claimAuditChecklist", claimAuditChecklist,
                "reviewMode", finalPass ? "final" : "initial"
        ));

        try {
            // Reviewer 同样要求模型输出 JSON，便于系统提取 score / issues / revisionPlan。
            String llmResponse = llmClient.chatForJson(
                    "你是一名严格的中文质量评审专家，请只返回 JSON。",
                    prompt,
                    "QualityReview"
            );

            JsonNode reviewJson = objectMapper.readTree(cleanJson(llmResponse));
            int score = reviewJson.path("score").asInt(0);
            boolean passed = reviewJson.path("passed").asBoolean(false);

            // issues 会被标准化成 RevisionItem，后续既能前端展示，也能供 Writer 重写消费。
            List<RevisionPlan.RevisionItem> items = new ArrayList<>();
            if (reviewJson.has("issues") && reviewJson.get("issues").isArray()) {
                for (JsonNode issueNode : reviewJson.get("issues")) {
                    items.add(new RevisionPlan.RevisionItem(
                            issueNode.path("type").asText("UNKNOWN"),
                            issueNode.path("section").asText("通用"),
                            issueNode.path("severity").asText("WARNING"),
                            issueNode.path("suggestion").asText("请补充并完善这一部分")
                    ));
                }
            }
            items = mergeRuleBasedIssues(items, report.getContent(), evidenceCoverageSummary);
            boolean hasError = items.stream().anyMatch(item -> "ERROR".equalsIgnoreCase(item.getSeverity()));
            if (hasError) {
                passed = false;
            }
            score = normalizeScore(score - calculateScorePenalty(items));
            boolean requiresHumanIntervention = requiresHumanIntervention(score, items, passed);
            boolean autoRewriteAllowed = !passed && !requiresHumanIntervention;

            String summary = reviewJson.path("summary").asText("");
            RevisionPlan revisionPlan = RevisionPlan.builder()
                    .rewriteRequired(!passed)
                    .summary(summary)
                    .items(items)
                    .rewriteGuidelines(passed ? List.of() : buildGuidelines(items))
                    .build();

            String revisionPlanJson = objectMapper.writeValueAsString(revisionPlan);

            // 报告主表同步回写最新评分与问题列表，方便报告页直接展示当前质量状态。
            report.setQualityScore(score);
            report.setQualityPassed(passed);
            report.setQualityIssues(objectMapper.writeValueAsString(toQualityIssuePayload(items)));
            reportRepository.save(report);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reviewStage", finalPass ? "final" : "initial");
            output.put("score", score);
            output.put("passed", passed);
            output.put("requiresHumanIntervention", requiresHumanIntervention);
            output.put("autoRewriteAllowed", autoRewriteAllowed);
            output.put("issues", objectMapper.valueToTree(toQualityIssuePayload(items)));
            output.put("summary", summary);
            output.put("revisionPlan", objectMapper.readTree(revisionPlanJson));
            output.put("nextActions", resolveNextActions(reviewJson, finalPass, passed, items));

            String outputJson = objectMapper.writeValueAsString(output);
            String outputSummary = passed
                    ? "质量评审通过，报告可直接定稿"
                    : requiresHumanIntervention
                    ? "质量评审未通过，证据缺口较大，已建议人工介入"
                    : "质量评审未通过，已生成修订计划";

            return AgentResult.success(outputJson, outputSummary,
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("quality review failed", e);
            return AgentResult.failed("质量评审失败：" + e.getMessage());
        }
    }

    // 修订指南是给 Writer 的重写输入，按 section 聚合为可执行语句。
    private List<String> buildGuidelines(List<RevisionPlan.RevisionItem> items) {
        List<String> guidelines = new ArrayList<>();
        for (RevisionPlan.RevisionItem item : items) {
            String suffix = requiresEvidenceCitation(item)
                    ? "修改后请在对应段落显式补上 [证据：EID] 形式的来源引用。"
                    : "请同步收紧表达，避免绝对化结论。";
            guidelines.add(item.getSection() + ": " + item.getSuggestion() + suffix);
        }
        if (guidelines.isEmpty()) {
            guidelines.add("请补强证据引用，并进一步收紧章节结构与结论表达。");
        }
        return guidelines;
    }

    /**
     * 终审失败时必须给用户明确下一步，而不是只展示“未通过”。
     * 如果模型已经按提示词返回 nextActions，就直接透传；否则按问题类型生成保守兜底建议。
     */
    private Object resolveNextActions(JsonNode reviewJson,
                                      boolean finalPass,
                                      boolean passed,
                                      List<RevisionPlan.RevisionItem> items) {
        if (reviewJson.has("nextActions") && reviewJson.get("nextActions").isArray()) {
            return reviewJson.get("nextActions");
        }
        if (!finalPass || passed) {
            return objectMapper.createArrayNode();
        }

        List<Map<String, String>> actions = new ArrayList<>();
        boolean hasEvidenceIssue = items.stream()
                .anyMatch(item -> item.getType() != null && item.getType().contains("证据"));

        if (hasEvidenceIssue) {
            actions.add(Map.of(
                    "title", "补充外部可验证证据",
                    "description", "优先为 ERROR 章节补充官网文档、定价页、客户案例、技术白皮书或公开报道，并确认报告正文引用新的 evidenceId。",
                    "actionType", "SUPPLEMENT_EVIDENCE",
                    "targetNode", "collect_sources",
                    "priority", "HIGH"
            ));
            actions.add(Map.of(
                    "title", "补源后重跑抽取与分析链路",
                    "description", "新增证据入库后，从 extract_schema 节点重跑，让结构化知识、分析结果和报告正文都消费新证据。",
                    "actionType", "RERUN_NODE",
                    "targetNode", "extract_schema",
                    "priority", "HIGH"
            ));
        }

        actions.add(Map.of(
                "title", "删除或降级无法验证的结论",
                "description", "如果公开资料仍无法支撑相关判断，请把绝对化结论改成“当前公开资料未能验证”，或从报告中删除该结论。",
                "actionType", "REWRITE_CLAIM",
                "targetNode", "rewrite_report",
                "priority", hasEvidenceIssue ? "MEDIUM" : "HIGH"
        ));
        actions.add(Map.of(
                "title", "复核终审问题清单",
                "description", "逐条检查终审问题清单，确认每个 ERROR 都已有证据、降级说明或删除处理，再重新触发终审。",
                "actionType", "MANUAL_REVIEW",
                "targetNode", "quality_check_final",
                "priority", "MEDIUM"
        ));
        return actions;
    }

    // 最终质检节点通过特定 qualityPolicy 标记，用于区分初审与终审。
    private boolean isFinalReview(String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return false;
        }
        try {
            JsonNode config = objectMapper.readTree(nodeConfig);
            return "final pass after revision".equalsIgnoreCase(config.path("qualityPolicy").asText());
        } catch (Exception e) {
            return nodeConfig.contains("final pass after revision");
        }
    }

    // 把证据列表压缩成 reviewer prompt 可读格式，要求质检严格对照来源审查报告。
    private String buildEvidenceList(List<EvidenceSource> evidences) {
        StringBuilder evidenceList = new StringBuilder();
        for (EvidenceSource ev : evidences) {
            evidenceList.append(String.format("- [%s] %s (%s)\n", ev.getEvidenceId(), ev.getTitle(), ev.getUrl()));
        }
        return evidenceList.toString();
    }

    private String buildEvidenceCoverageSummary(Long taskId) {
        List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(taskId);
        if (knowledges == null || knowledges.isEmpty()) {
            return "暂无结构化字段证据覆盖摘要，可根据报告正文与证据列表执行保守评审。";
        }

        List<String> lines = new ArrayList<>();
        for (CompetitorKnowledge knowledge : knowledges) {
            Map<String, Object> coverage = parseJsonMap(knowledge.getEvidenceCoverage());
            LinkedHashSet<String> traceableSections = new LinkedHashSet<>();
            LinkedHashSet<String> missingSections = new LinkedHashSet<>();
            LinkedHashSet<String> emptySections = new LinkedHashSet<>();

            collectCoverageStatus(coverage, "summary", "产品概览", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "positioning", "市场定位", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "targetUsers", "目标用户", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "coreFeatures", "核心能力", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "pricing", "定价策略", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "strengths", "优势判断", traceableSections, missingSections, emptySections);
            collectCoverageStatus(coverage, "weaknesses", "短板与风险", traceableSections, missingSections, emptySections);

            lines.add("- " + safe(knowledge.getCompetitorName())
                    + "：已可追溯章节="
                    + joinOrFallback(traceableSections, "无")
                    + "；缺证据章节="
                    + joinOrFallback(missingSections, "无")
                    + "；空字段章节="
                    + joinOrFallback(emptySections, "无"));
        }
        return String.join("\n", lines);
    }

    private List<RevisionPlan.RevisionItem> mergeRuleBasedIssues(List<RevisionPlan.RevisionItem> llmItems,
                                                                 String reportContent,
                                                                 String evidenceCoverageSummary) {
        List<RevisionPlan.RevisionItem> merged = new ArrayList<>(llmItems);
        for (ClaimAuditItem claim : collectClaimAuditItems(reportContent, evidenceCoverageSummary)) {
            if (claim.hasEvidenceCitation()) {
                continue;
            }
            addIssueIfAbsent(merged, new RevisionPlan.RevisionItem(
                    claim.coverageMissing() ? "missing_evidence" : "unsupported_claim",
                    claim.section(),
                    claim.severity(),
                    "结论项“" + shortenClaimText(claim.claimText()) + "”缺少可回指的证据引用，请补充 [证据：EID] 或改为保守表述。"
            ));
        }
        return merged;
    }

    private List<Map<String, String>> toQualityIssuePayload(List<RevisionPlan.RevisionItem> items) {
        List<Map<String, String>> payload = new ArrayList<>();
        for (RevisionPlan.RevisionItem item : items) {
            payload.add(Map.of(
                    "type", safeValue(item.getType(), "UNKNOWN"),
                    "section", safeValue(item.getSection(), "通用"),
                    "severity", safeValue(item.getSeverity(), "WARNING"),
                    "suggestion", safeValue(item.getSuggestion(), "请补充并完善这一部分")
            ));
        }
        return payload;
    }

    private int calculateScorePenalty(List<RevisionPlan.RevisionItem> items) {
        int penalty = 0;
        for (RevisionPlan.RevisionItem item : items) {
            String severity = safeValue(item.getSeverity(), "WARNING").toUpperCase(Locale.ROOT);
            if ("ERROR".equals(severity)) {
                penalty += 10;
            } else if ("WARNING".equals(severity)) {
                penalty += 4;
            } else {
                penalty += 1;
            }
        }
        return penalty;
    }

    private int normalizeScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private boolean requiresHumanIntervention(int score,
                                             List<RevisionPlan.RevisionItem> items,
                                             boolean passed) {
        if (passed) {
            return false;
        }
        long errorCount = items.stream().filter(item -> "ERROR".equalsIgnoreCase(item.getSeverity())).count();
        long evidenceErrorCount = items.stream()
                .filter(item -> "ERROR".equalsIgnoreCase(item.getSeverity()))
                .filter(item -> requiresEvidenceCitation(item))
                .count();
        return score <= 20 || errorCount >= 4 || evidenceErrorCount >= 2;
    }

    private boolean requiresEvidenceCitation(RevisionPlan.RevisionItem item) {
        String type = safeValue(item.getType(), "").toLowerCase(Locale.ROOT);
        return type.contains("evidence") || type.contains("claim") || type.contains("证据") || type.contains("支撑");
    }

    private void addIssueIfAbsent(List<RevisionPlan.RevisionItem> items, RevisionPlan.RevisionItem candidate) {
        boolean exists = items.stream().anyMatch(item ->
                safeValue(item.getType(), "").equalsIgnoreCase(safeValue(candidate.getType(), ""))
                        && safeValue(item.getSection(), "").equalsIgnoreCase(safeValue(candidate.getSection(), ""))
                        && safeValue(item.getSuggestion(), "").equalsIgnoreCase(safeValue(candidate.getSuggestion(), "")));
        if (!exists) {
            items.add(candidate);
        }
    }

    private List<ReportSection> splitMarkdownSections(String content) {
        List<ReportSection> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return sections;
        }
        String currentSection = "全文";
        StringBuilder builder = new StringBuilder();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("#")) {
                sections.add(new ReportSection(currentSection, builder.toString().trim()));
                currentSection = line.replaceFirst("^#+\\s*", "").trim();
                builder = new StringBuilder();
            } else {
                builder.append(rawLine).append("\n");
            }
        }
        sections.add(new ReportSection(currentSection, builder.toString().trim()));
        return sections;
    }

    private String buildClaimAuditChecklist(String reportContent, String evidenceCoverageSummary) {
        List<ClaimAuditItem> claimItems = collectClaimAuditItems(reportContent, evidenceCoverageSummary);
        if (claimItems.isEmpty()) {
            return "未提取到需要重点审查的关键结论条目，请仍逐条检查结论、建议、风险、机会和对比判断是否具备证据编号。";
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (ClaimAuditItem item : claimItems) {
            lines.add(index++ + ". 章节=" + item.section()
                    + "；结论=" + shortenClaimText(item.claimText())
                    + "；已有证据引用=" + (item.hasEvidenceCitation() ? "是" : "否")
                    + "；结构化覆盖缺口=" + (item.coverageMissing() ? "是" : "否")
                    + "；建议严重度=" + item.severity());
        }
        return String.join("\n", lines);
    }

    /**
     * 从关键章节中提取“结论项”清单，供 prompt 和代码兜底共同使用。
     * 这里不再只判断整章有没有证据，而是逐条检查段落/列表项是否存在证据引用。
     */
    private List<ClaimAuditItem> collectClaimAuditItems(String reportContent, String evidenceCoverageSummary) {
        List<ReportSection> sections = splitMarkdownSections(reportContent);
        Set<String> missingCoverageSections = extractMissingCoverageSections(evidenceCoverageSummary);
        List<ClaimAuditItem> items = new ArrayList<>();
        for (SectionRule rule : CLAIM_AUDIT_RULES) {
            for (ReportSection entry : sections) {
                if (entry.title() == null || !entry.title().contains(rule.reportSection())) {
                    continue;
                }
                List<String> candidates = extractClaimCandidates(entry.content(), rule.reportSection());
                boolean coverageMissing = hasCoverageGap(rule, missingCoverageSections);
                for (String candidate : candidates) {
                    items.add(new ClaimAuditItem(
                            rule.reportSection(),
                            candidate,
                            hasEvidenceCitation(candidate),
                            coverageMissing,
                            rule.severity()
                    ));
                }
                if (candidates.isEmpty() && shouldAddSectionLevelFallback(entry.content(), coverageMissing)) {
                    items.add(new ClaimAuditItem(
                            rule.reportSection(),
                            buildSectionFallbackClaimText(rule.reportSection(), entry.content()),
                            false,
                            coverageMissing,
                            rule.severity()
                    ));
                }
            }
        }
        return items.stream()
                .sorted(Comparator.comparing(ClaimAuditItem::section))
                .limit(MAX_CLAIM_AUDIT_ITEMS)
                .toList();
    }

    private List<String> extractClaimCandidates(String sectionContent, String sectionTitle) {
        if (sectionContent == null || sectionContent.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (String block : sectionContent.split("\\R")) {
            String normalized = block == null ? "" : block.trim();
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.startsWith("-") || normalized.startsWith("*") || normalized.matches("^\\d+[.、].*")) {
                normalized = normalized.replaceFirst("^(-|\\*|\\d+[.、])\\s*", "").trim();
            }
            for (String sentence : splitClaimSentences(normalized)) {
                if (sentence.length() < 12) {
                    continue;
                }
                if (looksLikeClaim(sentence) || isKeySectionSentence(sectionTitle, sentence)) {
                    candidates.add(sentence);
                }
            }
        }
        if (candidates.isEmpty()) {
            String compact = sectionContent.replace("\n", " ").trim();
            for (String sentence : splitClaimSentences(compact)) {
                if (sentence.length() < 12) {
                    continue;
                }
                if (looksLikeClaim(sentence) || isKeySectionSentence(sectionTitle, sentence)) {
                    candidates.add(sentence);
                }
            }
        }
        return candidates;
    }

    /**
     * 结论、建议、风险等章节天然带有判断属性，即使句子里没有显式出现“领先/优于”关键词，
     * 也要把较完整的陈述纳入审查，避免章节级缺证据问题漏检。
     */
    private boolean isKeySectionSentence(String sectionTitle, String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return false;
        }
        if (Arrays.asList("结论", "建议", "风险点", "机会点").contains(sectionTitle)) {
            return sentence.length() >= 16;
        }
        return false;
    }

    /**
     * 同一段落里可能同时包含多条判断，不能因为段尾附了一个证据编号，
     * 就把整段都视为“已有可回指证据”。这里按中文句号、分号和顿号列表拆分后逐句核对。
     */
    private List<String> splitClaimSentences(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        for (String segment : content.split("(?<=[。！？!?；;])\\s*")) {
            String normalized = segment == null ? "" : segment.trim();
            if (normalized.isBlank()) {
                continue;
            }
            sentences.add(normalized);
        }
        return sentences.isEmpty() ? List.of(content.trim()) : sentences;
    }

    private boolean looksLikeClaim(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("更")
                || normalized.contains("领先")
                || normalized.contains("优于")
                || normalized.contains("更适合")
                || normalized.contains("建议")
                || normalized.contains("风险")
                || normalized.contains("机会")
                || normalized.contains("不足")
                || normalized.contains("优势")
                || normalized.contains("推荐")
                || normalized.contains("更强")
                || normalized.contains("更低")
                || normalized.contains("更高");
    }

    private Set<String> extractMissingCoverageSections(String evidenceCoverageSummary) {
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        if (evidenceCoverageSummary == null || evidenceCoverageSummary.isBlank()) {
            return sections;
        }
        for (String line : evidenceCoverageSummary.split("\\R")) {
            int markerIndex = line.indexOf("缺证据章节=");
            if (markerIndex < 0) {
                continue;
            }
            int endIndex = line.indexOf("；", markerIndex);
            String raw = endIndex >= 0
                    ? line.substring(markerIndex + "缺证据章节=".length(), endIndex)
                    : line.substring(markerIndex + "缺证据章节=".length());
            for (String section : raw.split("[、,，/]")) {
                String normalized = section == null ? "" : section.trim();
                if (!normalized.isBlank() && !"无".equals(normalized)) {
                    sections.add(normalized);
                }
            }
        }
        return sections;
    }

    private boolean hasCoverageGap(SectionRule rule, Set<String> missingCoverageSections) {
        if (missingCoverageSections.isEmpty()) {
            return false;
        }
        return rule.coverageSections().stream().anyMatch(missingCoverageSections::contains);
    }

    private boolean shouldAddSectionLevelFallback(String sectionContent, boolean coverageMissing) {
        if (sectionContent == null || sectionContent.isBlank()) {
            return false;
        }
        if (hasEvidenceCitation(sectionContent)) {
            return false;
        }
        String normalized = sectionContent.replaceAll("\\s+", " ").trim();
        if (normalized.length() < 16) {
            return false;
        }
        return coverageMissing || normalized.length() >= 24;
    }

    private String buildSectionFallbackClaimText(String sectionTitle, String sectionContent) {
        String normalized = sectionContent == null ? "" : sectionContent.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 60) {
            normalized = normalized.substring(0, 60) + "...";
        }
        return sectionTitle + "章节存在明确判断，但未找到可回指证据。内容摘要：" + normalized;
    }

    private boolean hasEvidenceCitation(String text) {
        return text != null && EVIDENCE_PATTERN.matcher(text).find();
    }

    private String shortenClaimText(String claimText) {
        if (claimText == null) {
            return "";
        }
        String compact = claimText.replaceAll("\\s+", " ").trim();
        return compact.length() > 72 ? compact.substring(0, 72) + "..." : compact;
    }

    private void collectCoverageStatus(Map<String, Object> coverage,
                                       String fieldName,
                                       String sectionTitle,
                                       LinkedHashSet<String> traceableSections,
                                       LinkedHashSet<String> missingSections,
                                       LinkedHashSet<String> emptySections) {
        Object raw = coverage.get(fieldName);
        if (!(raw instanceof Map<?, ?> fieldCoverage)) {
            emptySections.add(sectionTitle);
            return;
        }
        Object statusValue = fieldCoverage.get("status");
        String status = statusValue == null ? "" : String.valueOf(statusValue).trim().toUpperCase();
        switch (status) {
            case "TRACEABLE" -> traceableSections.add(sectionTitle);
            case "MISSING_EVIDENCE", "PARTIAL" -> missingSections.add(sectionTitle);
            default -> emptySections.add(sectionTitle);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String joinOrFallback(LinkedHashSet<String> values, String fallback) {
        return values.isEmpty() ? fallback : String.join("、", values);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未知竞品" : value;
    }

    private String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 清理 markdown code fence，避免模型返回的 JSON 被额外包装导致解析失败。
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

    private record SectionRule(String reportSection, List<String> coverageSections, String severity) {
    }

    private record ClaimAuditItem(String section,
                                  String claimText,
                                  boolean hasEvidenceCitation,
                                  boolean coverageMissing,
                                  String severity) {
    }

    private record ReportSection(String title, String content) {
    }
}
