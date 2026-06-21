package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.memory.MemoryWritebackService;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.QualityDimension;
import cn.bugstack.competitoragent.workflow.contract.QualityIssue;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
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
    private final MemoryWritebackService memoryWritebackService;
    private final ObjectMapper objectMapper;

    public QualityReviewAgent(AgentExecutionLogRepository logRepository,
                              ReportRepository reportRepository,
                              EvidenceSourceRepository evidenceRepository,
                              CompetitorKnowledgeRepository knowledgeRepository,
                              LlmClient llmClient,
                              PromptTemplateService promptService,
                              AgentContextAssembler agentContextAssembler,
                              MemoryWritebackService memoryWritebackService,
                              ObjectMapper objectMapper) {
        super(logRepository, agentContextAssembler);
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.memoryWritebackService = memoryWritebackService;
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
        List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId());
        String evidenceList = buildEvidenceList(evidences);
        String evidenceCoverageSummary = buildEvidenceCoverageSummary(knowledges);
        CoverageSnapshot coverageSnapshot = buildCoverageSnapshot(knowledges);
        String claimAuditChecklist = buildClaimAuditChecklist(report.getContent(), evidenceCoverageSummary);
        boolean finalPass = isFinalReview(context.getCurrentNodeConfig());

        String prompt = promptService.render("reviewer", Map.of(
                "reportContent", report.getContent(),
                "evidenceList", evidenceList,
                "evidenceCoverageSummary", evidenceCoverageSummary,
                "claimAuditChecklist", claimAuditChecklist,
                // Reviewer 需要知道统一检索边界，才能把“未命中的公开证据”识别为真实风险而非遗漏提示。
                "taskRagContext", context.getTaskRagPromptContext(),
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
            int llmScore = reviewJson.path("score").asInt(0);
            boolean llmPassed = reviewJson.path("passed").asBoolean(false);

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
            List<QualityDiagnosis> diagnoses = buildDiagnoses(items, evidences, coverageSnapshot);
            List<RevisionDirective> revisionDirectives = buildRevisionDirectives(diagnoses, evidences);
            List<QualityDimension> dimensions = buildDimensions(llmScore, items, diagnoses, coverageSnapshot);
            int score = calculateDiagnosisDrivenScore(llmScore, dimensions);
            boolean passed = isDiagnosisPassed(llmPassed, dimensions, diagnoses);
            boolean requiresHumanIntervention = requiresHumanIntervention(score, dimensions, diagnoses, finalPass);
            boolean autoRewriteAllowed = !passed && !requiresHumanIntervention;

            String summary = buildDiagnosisSummary(reviewJson.path("summary").asText(""), dimensions, diagnoses, passed);
            RevisionPlan revisionPlan = RevisionPlan.builder()
                    .rewriteRequired(!passed)
                    .summary(summary)
                    .items(items)
                    .directives(revisionDirectives)
                    .rewriteGuidelines(passed ? List.of() : buildGuidelines(items))
                    .build();

            String revisionPlanJson = objectMapper.writeValueAsString(revisionPlan);

            // 报告主表同步回写最新评分与问题列表，方便报告页直接展示当前质量状态。
            report.setQualityScore(score);
            report.setQualityPassed(passed);
            report.setQualityIssues(objectMapper.writeValueAsString(toQualityIssuePayload(diagnoses)));
            reportRepository.save(report);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reviewStage", finalPass ? "final" : "initial");
            output.put("score", score);
            output.put("passed", passed);
            output.put("requiresHumanIntervention", requiresHumanIntervention);
            output.put("autoRewriteAllowed", autoRewriteAllowed);
            output.put("dimensions", objectMapper.valueToTree(dimensions));
            output.put("diagnoses", objectMapper.valueToTree(diagnoses));
            output.put("issues", objectMapper.valueToTree(toQualityIssuePayload(diagnoses)));
            output.put("summary", summary);
            // 评审节点把实际参考过的 Task RAG 摘要写回输出，便于解释当前结论为何判为证据不足。
            output.put("taskRagContext", context.getTaskRagPromptContext());
            output.put("revisionPlan", objectMapper.readTree(revisionPlanJson));
            output.put("revisionDirectives", objectMapper.valueToTree(revisionDirectives));
            output.put("nextActions", resolveNextActions(reviewJson, finalPass, passed, items, revisionDirectives));

            String outputJson = objectMapper.writeValueAsString(output);
            String outputSummary = passed
                    ? "质量评审通过，报告可直接定稿"
                    : requiresHumanIntervention
                    ? "质量评审未通过，证据缺口较大，已建议人工介入"
                    : "质量评审未通过，已生成修订计划";

            // 只有终审通过后的结论才允许提升为领域记忆，避免把待修订结果污染跨任务复用层。
            // 写回失败只记录日志，不影响 reviewer 主流程返回。
            writeVerifiedDomainKnowledgeBack(context, report, knowledges, evidences, summary, diagnoses, dimensions, outputJson, finalPass, passed);
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
                                      List<RevisionPlan.RevisionItem> items,
                                      List<RevisionDirective> revisionDirectives) {
        if (reviewJson.has("nextActions") && reviewJson.get("nextActions").isArray()) {
            return reviewJson.get("nextActions");
        }
        if (!finalPass || passed) {
            return objectMapper.createArrayNode();
        }

        List<Map<String, String>> actions = buildNextActionsFromDirectives(revisionDirectives);
        boolean hasEvidenceIssue = items.stream()
                .anyMatch(item -> requiresEvidenceCitation(item) || isSearchQualityItem(item));
        if (actions.stream().noneMatch(action -> "MANUAL_REVIEW".equals(action.get("actionType")))) {
            actions.add(Map.of(
                    "title", "复核终审问题清单",
                    "description", "逐条检查终审问题清单，确认每个 ERROR 都已有证据、降级说明或删除处理，再重新触发终审。",
                    "actionType", "MANUAL_REVIEW",
                    "targetNode", "quality_check_final",
                    "priority", "MEDIUM"
            ));
        }
        if (actions.stream().noneMatch(action -> "REWRITE_SECTION".equals(action.get("actionType")) || "REWRITE_CLAIM".equals(action.get("actionType")))) {
            actions.add(Map.of(
                    "title", "删除或降级无法验证的结论",
                    "description", "如果公开资料仍无法支撑相关判断，请把绝对化结论改成“当前公开资料未能验证”，或从报告中删除该结论。",
                    "actionType", "REWRITE_CLAIM",
                    "targetNode", "rewrite_report",
                    "priority", hasEvidenceIssue ? "MEDIUM" : "HIGH"
            ));
        }
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

    private String buildEvidenceCoverageSummary(List<CompetitorKnowledge> knowledges) {
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

    private CoverageSnapshot buildCoverageSnapshot(List<CompetitorKnowledge> knowledges) {
        LinkedHashSet<String> traceableSections = new LinkedHashSet<>();
        LinkedHashSet<String> missingSections = new LinkedHashSet<>();
        LinkedHashSet<String> emptySections = new LinkedHashSet<>();
        if (knowledges != null) {
            for (CompetitorKnowledge knowledge : knowledges) {
                Map<String, Object> coverage = parseJsonMap(knowledge.getEvidenceCoverage());
                collectCoverageStatus(coverage, "summary", "产品概览", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "positioning", "市场定位", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "targetUsers", "目标用户", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "coreFeatures", "核心能力", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "pricing", "定价策略", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "strengths", "优势判断", traceableSections, missingSections, emptySections);
                collectCoverageStatus(coverage, "weaknesses", "短板与风险", traceableSections, missingSections, emptySections);
            }
        }
        return new CoverageSnapshot(traceableSections, missingSections, emptySections);
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

    /**
     * 兼容旧 issues 字段，但底层来源已经切到可解释诊断模型。
     * 这样工作流和报告页仍可复用 issues，同时前端也能直接读取 diagnoses 做更细展示。
     */
    private List<QualityIssue> toQualityIssuePayload(List<QualityDiagnosis> diagnoses) {
        List<QualityIssue> payload = new ArrayList<>();
        for (QualityDiagnosis diagnosis : diagnoses) {
            if (diagnosis != null) {
                payload.add(diagnosis.toQualityIssue());
            }
        }
        return payload;
    }

    /**
     * 维度得分是新的主判断信号：
     * 1. LLM 原始分数只作为一个参考输入；
     * 2. 真正决定总分的是各质量维度的健康度，而不是单一硬编码阈值。
     */
    private int calculateDiagnosisDrivenScore(int llmScore, List<QualityDimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return normalizeScore(llmScore);
        }
        int total = 0;
        for (QualityDimension dimension : dimensions) {
            total += dimension.normalized().getScore();
        }
        int dimensionAverage = total / dimensions.size();
        return normalizeScore((normalizeScore(llmScore) + dimensionAverage) / 2);
    }

    private int normalizeScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private boolean isDiagnosisPassed(boolean llmPassed,
                                      List<QualityDimension> dimensions,
                                      List<QualityDiagnosis> diagnoses) {
        boolean hasBlocker = diagnoses.stream()
                .anyMatch(diagnosis -> "BLOCKER".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")));
        boolean hasCriticalCoreDimension = dimensions.stream()
                .map(QualityDimension::normalized)
                .anyMatch(dimension -> isCoreDimension(dimension.getCode())
                        && "CRITICAL".equalsIgnoreCase(safeValue(dimension.getStatus(), "")));
        return llmPassed && !hasBlocker && !hasCriticalCoreDimension;
    }

    /**
     * 人工介入判断也改成看“问题是否阻断闭环”，而不是只看总分低不低。
     * 当核心维度已进入 CRITICAL 或出现 BLOCKER 诊断时，即使总分还不算极低，也要停止自动改写。
     */
    private boolean requiresHumanIntervention(int score,
                                              List<QualityDimension> dimensions,
                                              List<QualityDiagnosis> diagnoses,
                                              boolean finalPass) {
        long blockerCount = diagnoses.stream()
                .filter(diagnosis -> "BLOCKER".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")))
                .count();
        long majorCount = diagnoses.stream()
                .filter(diagnosis -> "MAJOR".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")))
                .count();
        boolean hasCriticalEvidenceDimension = dimensions.stream()
                .map(QualityDimension::normalized)
                .anyMatch(dimension -> ("EVIDENCE_TRACEABILITY".equalsIgnoreCase(safeValue(dimension.getCode(), ""))
                        || "CLAIM_SUPPORT".equalsIgnoreCase(safeValue(dimension.getCode(), "")))
                        && "CRITICAL".equalsIgnoreCase(safeValue(dimension.getStatus(), "")));
        if (blockerCount > 0 || hasCriticalEvidenceDimension) {
            return true;
        }
        if (finalPass && majorCount >= 2) {
            return true;
        }
        return score <= 35;
    }

    /**
     * 每条修订项都会被提升为结构化诊断，并补齐来源链接、证据依据和修复建议。
     * 同时追加一次结构化字段覆盖缺口诊断，避免只审正文而忽略上游抽取质量。
     */
    private List<QualityDiagnosis> buildDiagnoses(List<RevisionPlan.RevisionItem> items,
                                                  List<EvidenceSource> evidences,
                                                  CoverageSnapshot coverageSnapshot) {
        List<QualityDiagnosis> diagnoses = new ArrayList<>();
        List<String> evidenceIds = evidences.stream()
                .map(EvidenceSource::getEvidenceId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        List<String> sourceUrls = evidences.stream()
                .map(EvidenceSource::getUrl)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        for (RevisionPlan.RevisionItem item : items) {
            String dimensionCode = resolveDimensionCode(item);
            diagnoses.add(QualityDiagnosis.builder()
                    .dimensionCode(dimensionCode)
                    .dimensionName(resolveDimensionName(dimensionCode))
                    .type(safeValue(item.getType(), "UNKNOWN"))
                    .section(safeValue(item.getSection(), "通用"))
                    .severity(safeValue(item.getSeverity(), "WARNING"))
                    .title(resolveDiagnosisTitle(item, dimensionCode))
                    .detail(safeValue(item.getSuggestion(), "请补充并完善这一部分"))
                    .evidenceBasis(buildDiagnosisEvidenceBasis(item, coverageSnapshot))
                    .evidenceIds(requiresEvidenceCitation(item) ? List.of() : evidenceIds)
                    .sourceUrls(sourceUrls)
                    .repairSuggestion(item.getSuggestion())
                    .build()
                    .normalized());
        }

        if (!coverageSnapshot.missingSections().isEmpty() || !coverageSnapshot.emptySections().isEmpty()) {
            diagnoses.add(QualityDiagnosis.builder()
                    .dimensionCode("STRUCTURE_COMPLETENESS")
                    .dimensionName("结构完整性")
                    .type("coverage_gap")
                    .section(joinSectionsForSummary(coverageSnapshot))
                    .severity(coverageSnapshot.missingSections().size() >= 2 ? "ERROR" : "WARNING")
                    .title("结构化字段仍存在覆盖缺口")
                    .detail("上游抽取结果中仍有字段缺证据或为空，说明报告链路输出质量不稳定。")
                    .evidenceBasis("缺证据章节=" + joinSections(coverageSnapshot.missingSections())
                            + "；空字段章节=" + joinSections(coverageSnapshot.emptySections()))
                    .sourceUrls(sourceUrls)
                    .repairSuggestion("请优先补齐缺证据章节，并在重跑抽取后复核对应报告段落是否仍保留无依据结论。")
                    .build()
                    .normalized());
        }
        appendStructuredEvidenceDiagnoses(diagnoses, evidences, coverageSnapshot);
        return diagnoses;
    }

    /**
     * 当 pageMetadata 已经暴露结构化质量信号时，Reviewer 需要把问题识别为结构化证据不足，
     * 避免所有问题继续退化为通用 unsupported_claim。
     */
    private void appendStructuredEvidenceDiagnoses(List<QualityDiagnosis> diagnoses,
                                                   List<EvidenceSource> evidences,
                                                   CoverageSnapshot coverageSnapshot) {
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            Map<String, Object> metadata = parseJsonMap(evidence == null ? null : evidence.getPageMetadata());
            List<String> qualitySignals = readMetadataStringList(metadata.get("qualitySignals"));
            List<Object> structuredBlocks = readMetadataObjectList(metadata.get("structuredBlocks"));
            String failureKind = readMetadataText(metadata.get("failureKind"));
            Double qualityScore = readMetadataDouble(metadata.get("qualityScore"));
            if (!shouldDiagnoseStructuredEvidenceGap(qualitySignals, structuredBlocks, failureKind, qualityScore)) {
                continue;
            }

            String section = inferStructuredEvidenceSection(evidence);
            String evidenceBasis = buildStructuredEvidenceBasis(section, qualitySignals, structuredBlocks, failureKind, qualityScore, coverageSnapshot);
            boolean exists = diagnoses.stream().anyMatch(diagnosis ->
                    "missing_structured_evidence".equalsIgnoreCase(safeValue(diagnosis.getType(), ""))
                            && section.equalsIgnoreCase(safeValue(diagnosis.getSection(), "")));
            if (exists) {
                continue;
            }

            diagnoses.add(QualityDiagnosis.builder()
                    .dimensionCode("SEARCH_QUALITY")
                    .dimensionName(resolveDimensionName("SEARCH_QUALITY"))
                    .type("missing_structured_evidence")
                    .section(section)
                    .severity("ERROR")
                    .title("结构化证据不足")
                    .detail("当前来源虽然已命中 sourceUrls，但 structuredBlocks 或 qualitySignals 未达到可用门槛。")
                    .evidenceBasis(evidenceBasis)
                    .evidenceIds(evidence != null && evidence.getEvidenceId() != null && !evidence.getEvidenceId().isBlank()
                            ? List.of(evidence.getEvidenceId().trim()) : List.of())
                    .sourceUrls(evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()
                            ? List.of(evidence.getUrl().trim()) : List.of())
                    .repairSuggestion(buildStructuredEvidenceRepairSuggestion(section, qualitySignals, structuredBlocks))
                    .build()
                    .normalized());
        }
    }

    /**
     * 修订指令是质检闭环的真正执行入口。
     * 这里把诊断结果转换成“补源 / 重跑 / 改写 / 复核”的显式动作，
     * 避免下游只能看到分数和问题列表，却不知道下一步该触发哪个节点。
     */
    private List<RevisionDirective> buildRevisionDirectives(List<QualityDiagnosis> diagnoses,
                                                            List<EvidenceSource> evidences) {
        LinkedHashMap<String, RevisionDirective> directives = new LinkedHashMap<>();
        for (QualityDiagnosis diagnosis : diagnoses) {
            if (diagnosis == null) {
                continue;
            }
            QualityDiagnosis normalized = diagnosis.normalized();
            String category = resolveDirectiveCategory(normalized);
            RevisionDirective directive = RevisionDirective.builder()
                    .category(category)
                    .targetSection(safeValue(normalized.getSection(), "通用"))
                    .summary(normalized.getRepairSuggestion())
                    .searchFeedback("SEARCH_QUALITY".equals(category) ? normalized.getDetail() : null)
                    .searchQueries(buildSearchQueries(normalized, evidences))
                    .sourceUrls(normalized.getSourceUrls())
                    .expectedOutcome(buildExpectedOutcome(normalized, category))
                    .build()
                    .normalized();
            String key = directive.getCategory()
                    + "|"
                    + safeValue(directive.getTargetSection(), "")
                    + "|"
                    + safeValue(directive.getTargetNode(), "")
                    + "|"
                    + safeValue(directive.getActionType(), "");
            directives.putIfAbsent(key, directive);
        }
        return new ArrayList<>(directives.values());
    }

    /**
     * 维度评分用固定规则映射为解释性结果：
     * - 证据可追溯性：看缺证据问题和 coverage 缺口；
     * - 结论支撑度：看 unsupported claim / blocker 诊断；
     * - 结构完整性：看字段缺口与空字段数量；
     * - 可执行性：看修订项是否足够明确、是否还能继续自动改写。
     */
    private List<QualityDimension> buildDimensions(int llmScore,
                                                   List<RevisionPlan.RevisionItem> items,
                                                   List<QualityDiagnosis> diagnoses,
                                                   CoverageSnapshot coverageSnapshot) {
        long evidenceIssueCount = diagnoses.stream()
                .filter(diagnosis -> "EVIDENCE_TRACEABILITY".equalsIgnoreCase(safeValue(diagnosis.getDimensionCode(), ""))
                        || "missing_evidence".equalsIgnoreCase(safeValue(diagnosis.getType(), "")))
                .count();
        long claimIssueCount = diagnoses.stream()
                .filter(diagnosis -> "CLAIM_SUPPORT".equalsIgnoreCase(safeValue(diagnosis.getDimensionCode(), "")))
                .count();
        long searchIssueCount = diagnoses.stream()
                .filter(diagnosis -> "SEARCH_QUALITY".equalsIgnoreCase(safeValue(diagnosis.getDimensionCode(), "")))
                .count();
        long blockerCount = diagnoses.stream()
                .filter(diagnosis -> "BLOCKER".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")))
                .count();
        int missingCoverageCount = coverageSnapshot.missingSections().size();
        int emptyCoverageCount = coverageSnapshot.emptySections().size();

        int evidenceScore = normalizeScore(100 - (int) evidenceIssueCount * 22 - missingCoverageCount * 12 - emptyCoverageCount * 6 - (int) searchIssueCount * 10);
        int claimScore = normalizeScore(100 - (int) claimIssueCount * 20 - (int) blockerCount * 12);
        int structureScore = normalizeScore(100 - missingCoverageCount * 14 - emptyCoverageCount * 16);
        int actionabilityScore = normalizeScore(100 - Math.max(0, items.size() - 1) * 10 - (blockerCount > 0 ? 25 : 0) - (int) searchIssueCount * 8);

        return List.of(
                QualityDimension.builder()
                        .code("EVIDENCE_TRACEABILITY")
                        .name("证据可追溯性")
                        .description("关键结论是否都能回指到稳定的 evidenceId 与来源链接。")
                        .evaluationStandard("关键结论必须携带可追溯 evidenceId 或 sourceUrls。")
                        .score(evidenceScore)
                        .build()
                        .normalized(),
                QualityDimension.builder()
                        .code("CLAIM_SUPPORT")
                        .name("结论支撑度")
                        .description("判断性结论是否有明确证据支撑，而非只靠模型主观归纳。")
                        .evaluationStandard("关键结论不得出现 unsupported claim 或 BLOCKER 级别支撑缺口。")
                        .score(claimScore)
                        .build()
                        .normalized(),
                QualityDimension.builder()
                        .code("STRUCTURE_COMPLETENESS")
                        .name("结构完整性")
                        .description("上游抽取字段与报告章节是否同时完整，避免信息漂移和字段缺口。")
                        .evaluationStandard("关键字段不应长期处于缺证据或空字段状态。")
                        .score(structureScore)
                        .build()
                        .normalized(),
                QualityDimension.builder()
                        .code("ACTIONABILITY")
                        .name("修订可执行性")
                        .description("当前质检结果是否足够明确，能够驱动下一轮自动改写。")
                        .evaluationStandard("修订项应清晰、可执行，且不依赖大量人工补证据。")
                        .score(Math.min(actionabilityScore, normalizeScore((llmScore + actionabilityScore) / 2)))
                        .build()
                        .normalized()
        );
    }

    private String buildDiagnosisSummary(String llmSummary,
                                         List<QualityDimension> dimensions,
                                         List<QualityDiagnosis> diagnoses,
                                         boolean passed) {
        if (passed) {
            return llmSummary == null || llmSummary.isBlank()
                    ? "各质量维度均达到可发布状态。"
                    : llmSummary;
        }
        List<String> criticalDimensions = dimensions.stream()
                .map(QualityDimension::normalized)
                .filter(dimension -> "CRITICAL".equalsIgnoreCase(safeValue(dimension.getStatus(), "")))
                .map(QualityDimension::getName)
                .distinct()
                .toList();
        long blockerCount = diagnoses.stream()
                .filter(diagnosis -> "BLOCKER".equalsIgnoreCase(safeValue(diagnosis.getLevel(), "")))
                .count();
        String prefix = criticalDimensions.isEmpty()
                ? "当前报告存在待修复质量问题"
                : String.join("、", criticalDimensions) + "存在关键缺口";
        if (blockerCount > 0) {
            prefix += "，且已有 " + blockerCount + " 条阻断级诊断";
        }
        if (llmSummary != null && !llmSummary.isBlank()) {
            return prefix + "。模型摘要：" + llmSummary;
        }
        return prefix + "。请先补齐证据链，再继续自动改写。";
    }

    private boolean isCoreDimension(String dimensionCode) {
        return "EVIDENCE_TRACEABILITY".equalsIgnoreCase(safeValue(dimensionCode, ""))
                || "CLAIM_SUPPORT".equalsIgnoreCase(safeValue(dimensionCode, ""))
                || "STRUCTURE_COMPLETENESS".equalsIgnoreCase(safeValue(dimensionCode, ""));
    }

    private String resolveDimensionCode(RevisionPlan.RevisionItem item) {
        String type = safeValue(item.getType(), "").toLowerCase(Locale.ROOT);
        if (type.contains("search")) {
            return "SEARCH_QUALITY";
        }
        if (type.contains("claim")) {
            return "CLAIM_SUPPORT";
        }
        if (type.contains("evidence") || type.contains("证据") || type.contains("support")) {
            return "EVIDENCE_TRACEABILITY";
        }
        if (type.contains("section") || type.contains("structure")) {
            return "STRUCTURE_COMPLETENESS";
        }
        return "ACTIONABILITY";
    }

    private String resolveDimensionName(String dimensionCode) {
        return switch (safeValue(dimensionCode, "")) {
            case "EVIDENCE_TRACEABILITY" -> "证据可追溯性";
            case "CLAIM_SUPPORT" -> "结论支撑度";
            case "STRUCTURE_COMPLETENESS" -> "结构完整性";
            case "SEARCH_QUALITY" -> "搜索质量";
            default -> "修订可执行性";
        };
    }

    private String resolveDiagnosisTitle(RevisionPlan.RevisionItem item, String dimensionCode) {
        if ("SEARCH_QUALITY".equals(dimensionCode)) {
            return "搜索结果无法支撑当前章节判断";
        }
        if ("CLAIM_SUPPORT".equals(dimensionCode)) {
            return "关键结论缺少充分支撑";
        }
        if ("EVIDENCE_TRACEABILITY".equals(dimensionCode)) {
            return "关键结论缺少来源引用";
        }
        if ("STRUCTURE_COMPLETENESS".equals(dimensionCode)) {
            return "结构化字段存在缺口";
        }
        return "需要修订的质量问题";
    }

    private String buildDiagnosisEvidenceBasis(RevisionPlan.RevisionItem item, CoverageSnapshot coverageSnapshot) {
        String section = safeValue(item.getSection(), "通用");
        if (isSearchQualityItem(item)) {
            return section + "当前命中的搜索结果缺少稳定官网或高可信来源，无法支撑该章节结论。";
        }
        if (coverageSnapshot.missingSections().contains(section)) {
            return section + "在结构化 evidenceCoverage 中已被标记为缺证据，同时正文审计也命中了修订问题。";
        }
        if (coverageSnapshot.emptySections().contains(section)) {
            return section + "在结构化 evidenceCoverage 中仍为空字段，说明当前结论缺少稳定上游支撑。";
        }
        if (requiresEvidenceCitation(item)) {
            return section + "存在无法回指到 [证据：EID] 的判断，需要补齐来源编号与链接。";
        }
        return section + "存在需要收紧或重写的表达，请结合当前诊断继续复核。";
    }

    private String joinSectionsForSummary(CoverageSnapshot coverageSnapshot) {
        String missing = joinSections(coverageSnapshot.missingSections());
        String empty = joinSections(coverageSnapshot.emptySections());
        if (!missing.isBlank() && !empty.isBlank()) {
            return missing + " / " + empty;
        }
        return missing.isBlank() ? empty : missing;
    }

    private String joinSections(Set<String> sections) {
        return sections == null || sections.isEmpty() ? "无" : String.join("、", sections);
    }

    private boolean requiresEvidenceCitation(RevisionPlan.RevisionItem item) {
        String type = safeValue(item.getType(), "").toLowerCase(Locale.ROOT);
        return type.contains("evidence") || type.contains("claim") || type.contains("证据") || type.contains("支撑");
    }

    private boolean isSearchQualityItem(RevisionPlan.RevisionItem item) {
        String type = safeValue(item.getType(), "").toLowerCase(Locale.ROOT);
        return type.contains("search");
    }

    private String resolveDirectiveCategory(QualityDiagnosis diagnosis) {
        String type = safeValue(diagnosis.getType(), "").toLowerCase(Locale.ROOT);
        String dimensionCode = safeValue(diagnosis.getDimensionCode(), "");
        if ("SEARCH_QUALITY".equalsIgnoreCase(dimensionCode) || type.contains("search")) {
            return "SEARCH_QUALITY";
        }
        if ("STRUCTURE_COMPLETENESS".equalsIgnoreCase(dimensionCode) || type.contains("coverage") || type.contains("section") || type.contains("structure")) {
            return "STRUCTURE_ISSUE";
        }
        if (type.contains("expression") || type.contains("style") || type.contains("tone") || type.contains("absolute") || type.contains("措辞")) {
            return "EXPRESSION_ISSUE";
        }
        return "EVIDENCE_GAP";
    }

    private List<String> buildSearchQueries(QualityDiagnosis diagnosis, List<EvidenceSource> evidences) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String section = safeValue(diagnosis.getSection(), "");
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            String competitorName = safeValue(evidence.getCompetitorName(), "");
            if (!competitorName.isBlank() && !section.isBlank()) {
                queries.add((competitorName + " " + section).trim());
            } else if (!competitorName.isBlank()) {
                queries.add(competitorName);
            }
        }
        if (queries.isEmpty() && !section.isBlank()) {
            queries.add(section);
        }
        return new ArrayList<>(queries);
    }

    private String buildExpectedOutcome(QualityDiagnosis diagnosis, String category) {
        String section = safeValue(diagnosis.getSection(), "相关章节");
        return switch (category) {
            case "SEARCH_QUALITY" -> section + "需要补齐更高可信度的搜索来源，并让该章节重新具备可验证支撑。";
            case "STRUCTURE_ISSUE" -> section + "需要恢复结构化字段完整性，并让报告章节与字段输出重新对齐。";
            case "EXPRESSION_ISSUE" -> section + "需要改写为克制、可验证的表达，不保留绝对化结论。";
            default -> section + "需要补齐来源引用或下调无法验证的判断，确保结论可回溯。";
        };
    }

    private String buildStructuredEvidenceBasis(String section,
                                                List<String> qualitySignals,
                                                List<Object> structuredBlocks,
                                                String failureKind,
                                                Double qualityScore,
                                                CoverageSnapshot coverageSnapshot) {
        StringBuilder basis = new StringBuilder(section)
                .append(" 的 structuredBlocks 与 qualitySignals 未达到可用门槛");
        basis.append("。structuredBlocks=").append(structuredBlocks.size());
        basis.append("，qualitySignals=").append(qualitySignals);
        if (qualityScore != null) {
            basis.append("，qualityScore=").append(String.format(Locale.ROOT, "%.2f", qualityScore));
        }
        if (failureKind != null && !failureKind.isBlank()) {
            basis.append("，failureKind=").append(failureKind);
        }
        String coverageHint = resolveStructuredCoverageHint(section, coverageSnapshot);
        if (coverageHint != null) {
            basis.append("，evidenceCoverage=").append(coverageHint);
        }
        basis.append("。");
        return basis.toString();
    }

    private String buildStructuredEvidenceRepairSuggestion(String section,
                                                           List<String> qualitySignals,
                                                           List<Object> structuredBlocks) {
        if (structuredBlocks.isEmpty()) {
            return "请先为" + section + "补齐可复用 structuredBlocks，再复核是否保留当前结论。";
        }
        if (containsFailedQualitySignal(qualitySignals)) {
            return "请先修复" + section + "对应 evidence 的 qualitySignals，再决定是否继续引用该来源。";
        }
        return "请重跑" + section + "对应采集链路，补齐结构化证据后再继续下游结论生成。";
    }

    private String resolveStructuredCoverageHint(String section, CoverageSnapshot coverageSnapshot) {
        if (coverageSnapshot == null) {
            return null;
        }
        String coverageSection = mapReportSectionToCoverageSection(section);
        if (coverageSection == null) {
            return null;
        }
        if (coverageSnapshot.missingSections().contains(coverageSection)) {
            return coverageSection + ":MISSING_EVIDENCE";
        }
        if (coverageSnapshot.emptySections().contains(coverageSection)) {
            return coverageSection + ":EMPTY";
        }
        if (coverageSnapshot.traceableSections().contains(coverageSection)) {
            return coverageSection + ":TRACEABLE";
        }
        return coverageSection + ":UNKNOWN";
    }

    private String mapReportSectionToCoverageSection(String section) {
        if (section == null || section.isBlank()) {
            return null;
        }
        if (section.contains("定价")) {
            return "定价策略";
        }
        if (section.contains("功能")) {
            return "核心能力";
        }
        if (section.contains("定位")) {
            return "市场定位";
        }
        if (section.contains("用户")) {
            return "目标用户";
        }
        if (section.contains("优势")) {
            return "优势判断";
        }
        if (section.contains("风险") || section.contains("不足")) {
            return "短板与风险";
        }
        if (section.contains("结论")) {
            return "产品概览";
        }
        return null;
    }

    private boolean shouldDiagnoseStructuredEvidenceGap(List<String> qualitySignals,
                                                        List<Object> structuredBlocks,
                                                        String failureKind,
                                                        Double qualityScore) {
        if (structuredBlocks.isEmpty() && !qualitySignals.isEmpty()) {
            return true;
        }
        if (structuredBlocks.isEmpty() && failureKind != null && !failureKind.isBlank()) {
            return true;
        }
        return structuredBlocks.isEmpty() && qualityScore != null && qualityScore < 0.6D;
    }

    private boolean containsFailedQualitySignal(List<String> qualitySignals) {
        for (String qualitySignal : qualitySignals == null ? List.<String>of() : qualitySignals) {
            String normalized = safeValue(qualitySignal, "").toUpperCase(Locale.ROOT);
            if (normalized.contains("FAILED")
                    || normalized.contains("FAIL")
                    || normalized.contains("MISSING")
                    || normalized.contains("NO_")) {
                return true;
            }
        }
        return false;
    }

    private String inferStructuredEvidenceSection(EvidenceSource evidence) {
        String sourceType = safeValue(evidence == null ? null : evidence.getSourceType(), "").toUpperCase(Locale.ROOT);
        String title = safeValue(evidence == null ? null : evidence.getTitle(), "").toLowerCase(Locale.ROOT);
        String url = safeValue(evidence == null ? null : evidence.getUrl(), "").toLowerCase(Locale.ROOT);
        if (sourceType.contains("PRICING") || title.contains("pricing") || url.contains("pricing")) {
            return "定价对比";
        }
        if (sourceType.contains("DOCS") || title.contains("feature") || url.contains("feature")) {
            return "功能对比";
        }
        if (title.contains("security") || url.contains("security")) {
            return "风险点";
        }
        return "通用";
    }

    private List<Map<String, String>> buildNextActionsFromDirectives(List<RevisionDirective> revisionDirectives) {
        LinkedHashMap<String, Map<String, String>> actions = new LinkedHashMap<>();
        for (RevisionDirective directive : revisionDirectives == null ? List.<RevisionDirective>of() : revisionDirectives) {
            if (directive == null) {
                continue;
            }
            RevisionDirective normalized = directive.normalized();
            String key = normalized.getActionType() + "|" + normalized.getTargetNode();
            actions.putIfAbsent(key, Map.of(
                    "title", resolveActionTitle(normalized),
                    "description", resolveActionDescription(normalized),
                    "actionType", normalized.getActionType(),
                    "targetNode", normalized.getTargetNode(),
                    "priority", normalized.getPriority()
            ));
            if ("SUPPLEMENT_EVIDENCE".equals(normalized.getActionType())) {
                actions.putIfAbsent("RERUN_NODE|extract_schema", Map.of(
                        "title", "补源后重跑抽取与分析链路",
                        "description", "新增证据入库后，从 extract_schema 节点重跑，让结构化知识、分析结果和报告正文都消费新证据。",
                        "actionType", "RERUN_NODE",
                        "targetNode", "extract_schema",
                        "priority", "HIGH"
                ));
            }
        }
        return new ArrayList<>(actions.values());
    }

    private String resolveActionTitle(RevisionDirective directive) {
        if (directive.getSummary() != null && !directive.getSummary().isBlank()) {
            return directive.getSummary();
        }
        return switch (safeValue(directive.getActionType(), "")) {
            case "SUPPLEMENT_EVIDENCE" -> "补充外部可验证证据";
            case "RERUN_NODE" -> "重跑上游结构化节点";
            case "REWRITE_SECTION" -> "改写问题章节";
            default -> "复核修订结果";
        };
    }

    private String resolveActionDescription(RevisionDirective directive) {
        if ("SEARCH_QUALITY".equalsIgnoreCase(safeValue(directive.getCategory(), ""))) {
            return safeValue(directive.getSearchFeedback(),
                    "请调整搜索查询，优先补采官网、定价页、白皮书或客户案例等高可信来源。");
        }
        if ("STRUCTURE_ISSUE".equalsIgnoreCase(safeValue(directive.getCategory(), ""))) {
            return "请补齐缺失字段后重跑 extract_schema，确保结构化结果与报告章节重新对齐。";
        }
        if ("EXPRESSION_ISSUE".equalsIgnoreCase(safeValue(directive.getCategory(), ""))) {
            return "请收紧表达，避免绝对化结论，并在必要时显式标记“当前公开资料未能验证”。";
        }
        return "请优先补充官网文档、定价页、客户案例、技术白皮书或公开报道，并确认报告正文引用新的 evidenceId。";
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
    private String readMetadataText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Double readMetadataDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> readMetadataStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = readMetadataText(item);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private List<Object> readMetadataObjectList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return new ArrayList<>(values);
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

    private record SectionRule(String reportSection, List<String> coverageSections, String severity) {
    }

    private record ClaimAuditItem(String section,
                                  String claimText,
                                  boolean hasEvidenceCitation,
                                  boolean coverageMissing,
                                  String severity) {
    }

    private record CoverageSnapshot(LinkedHashSet<String> traceableSections,
                                    LinkedHashSet<String> missingSections,
                                    LinkedHashSet<String> emptySections) {
    }

    private record ReportSection(String title, String content) {
    }
    /**
     * 终审通过后，把已经过质检的领域结论写回 DOMAIN 记忆。
     * 这里显式携带 sourceUrls、evidenceCoverage 和终审诊断上下文，确保后续跨任务复用时仍可解释来源与边界。
     */
    private void writeVerifiedDomainKnowledgeBack(AgentContext context,
                                                  Report report,
                                                  List<CompetitorKnowledge> knowledges,
                                                  List<EvidenceSource> evidences,
                                                  String reviewSummary,
                                                  List<QualityDiagnosis> diagnoses,
                                                  List<QualityDimension> dimensions,
                                                  String outputJson,
                                                  boolean finalPass,
                                                  boolean passed) {
        if (!finalPass || !passed) {
            return;
        }
        try {
            List<String> sourceUrls = collectDomainSourceUrls(knowledges, evidences);
            MemoryWritebackService.WritebackRequest request = MemoryWritebackService.WritebackRequest.builder()
                    .taskId(context.getTaskId())
                    .planVersionId(context.getPlanVersionId())
                    .branchKey(context.getBranchKey())
                    .nodeName(firstNonBlank(context.getCurrentNodeName(), "quality_check_final"))
                    .competitorName(resolveDomainCompetitorName(knowledges, evidences))
                    .officialUrl(resolveDomainOfficialUrl(knowledges))
                    .snapshotType("DOMAIN_REVIEW")
                    .queryText(resolveReviewerWritebackQuery(context))
                    .summary(resolveDomainSummary(report, knowledges, reviewSummary))
                    .gapSummary(buildDomainGapSummary(diagnoses))
                    .sourceUrls(sourceUrls)
                    .issueFlags(buildDomainIssueFlags(diagnoses))
                    .contextPayload(buildReviewerWritebackContext(report, diagnoses, dimensions, outputJson))
                    .writebackCategory("VERIFIED_DOMAIN_KNOWLEDGE")
                    .qualitySignal("VERIFIED")
                    .reuseReason("终审通过的领域结论已绑定 sourceUrls，可作为跨任务领域知识复用；当证据变化时失效")
                    .positioning(resolveKnowledgeField(knowledges, CompetitorKnowledge::getPositioning))
                    .targetUsers(resolveKnowledgeField(knowledges, CompetitorKnowledge::getTargetUsers))
                    .coreFeatures(resolveKnowledgeField(knowledges, CompetitorKnowledge::getCoreFeatures))
                    .pricing(resolveKnowledgeField(knowledges, CompetitorKnowledge::getPricing))
                    .strengths(resolveKnowledgeField(knowledges, CompetitorKnowledge::getStrengths))
                    .weaknesses(resolveKnowledgeField(knowledges, CompetitorKnowledge::getWeaknesses))
                    .sources(resolveDomainSources(knowledges, evidences))
                    .evidenceCoverage(resolveKnowledgeField(knowledges, CompetitorKnowledge::getEvidenceCoverage))
                    .build();
            memoryWritebackService.writeback(request);
        } catch (Exception e) {
            log.warn("quality review writeback skipped, taskId={}, nodeName={}, reason={}",
                    context.getTaskId(), context.getCurrentNodeName(), e.getMessage(), e);
        }
    }

    private String resolveDomainCompetitorName(List<CompetitorKnowledge> knowledges, List<EvidenceSource> evidences) {
        String fromKnowledge = resolveKnowledgeField(knowledges, CompetitorKnowledge::getCompetitorName);
        if (fromKnowledge != null && !fromKnowledge.isBlank()) {
            return fromKnowledge;
        }
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence != null && evidence.getCompetitorName() != null && !evidence.getCompetitorName().isBlank()) {
                return evidence.getCompetitorName();
            }
        }
        return null;
    }

    private String resolveDomainOfficialUrl(List<CompetitorKnowledge> knowledges) {
        return resolveKnowledgeField(knowledges, CompetitorKnowledge::getOfficialUrl);
    }

    private String resolveDomainSummary(Report report,
                                        List<CompetitorKnowledge> knowledges,
                                        String reviewSummary) {
        return firstNonBlank(
                report == null ? null : report.getSummary(),
                resolveKnowledgeField(knowledges, CompetitorKnowledge::getSummary),
                reviewSummary,
                report == null ? null : report.getContent()
        );
    }

    private String buildDomainGapSummary(List<QualityDiagnosis> diagnoses) {
        if (diagnoses == null || diagnoses.isEmpty()) {
            return "终审通过，当前领域结论已完成可追溯校验";
        }
        return "终审通过，但仍保留以下非阻断诊断供后续复核：" + String.join(", ", buildDomainIssueFlags(diagnoses));
    }

    private List<String> collectDomainSourceUrls(List<CompetitorKnowledge> knowledges, List<EvidenceSource> evidences) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        for (CompetitorKnowledge knowledge : knowledges == null ? List.<CompetitorKnowledge>of() : knowledges) {
            sourceUrls.addAll(readJsonStringList(knowledge == null ? null : knowledge.getSourceUrls()));
        }
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                sourceUrls.add(evidence.getUrl());
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> buildDomainIssueFlags(List<QualityDiagnosis> diagnoses) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        for (QualityDiagnosis diagnosis : diagnoses == null ? List.<QualityDiagnosis>of() : diagnoses) {
            if (diagnosis == null) {
                continue;
            }
            String type = safeValue(diagnosis.getType(), "");
            if (!type.isBlank()) {
                issueFlags.add(type);
            }
        }
        return new ArrayList<>(issueFlags);
    }

    private String resolveDomainSources(List<CompetitorKnowledge> knowledges,
                                        List<EvidenceSource> evidences) throws Exception {
        String existingSources = resolveKnowledgeField(knowledges, CompetitorKnowledge::getSources);
        if (existingSources != null && !existingSources.isBlank()) {
            return existingSources;
        }
        List<Map<String, String>> sourcePayload = new ArrayList<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence == null) {
                continue;
            }
            sourcePayload.add(Map.of(
                    "evidenceId", safeValue(evidence.getEvidenceId(), ""),
                    "title", safeValue(evidence.getTitle(), ""),
                    "url", safeValue(evidence.getUrl(), "")
            ));
        }
        return objectMapper.writeValueAsString(sourcePayload);
    }

    private String buildReviewerWritebackContext(Report report,
                                                 List<QualityDiagnosis> diagnoses,
                                                 List<QualityDimension> dimensions,
                                                 String outputJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportTitle", report == null ? null : report.getTitle());
        payload.put("reportSummary", report == null ? null : report.getSummary());
        payload.put("qualityDiagnoses", diagnoses);
        payload.put("qualityDimensions", dimensions);
        payload.put("reviewOutput", objectMapper.readTree(outputJson));
        return objectMapper.writeValueAsString(payload);
    }

    private String resolveReviewerWritebackQuery(AgentContext context) {
        if (context.getTaskRagContextBundle() != null
                && context.getTaskRagContextBundle().getQuery() != null
                && !context.getTaskRagContextBundle().getQuery().isBlank()) {
            return context.getTaskRagContextBundle().getQuery();
        }
        return firstNonBlank(context.getTaskName(), context.getSubjectProduct());
    }

    private List<String> readJsonStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String resolveKnowledgeField(List<CompetitorKnowledge> knowledges,
                                         java.util.function.Function<CompetitorKnowledge, String> extractor) {
        if (knowledges == null) {
            return null;
        }
        for (CompetitorKnowledge knowledge : knowledges) {
            if (knowledge == null) {
                continue;
            }
            String value = extractor.apply(knowledge);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
