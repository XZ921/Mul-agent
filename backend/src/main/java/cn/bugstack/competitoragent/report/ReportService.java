package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CollectorSearchAudit;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CompetitorKnowledgeInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CompetitorEvidenceCoverage;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceCoverageOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.QualityIssue;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewCheckpoint;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewNextAction;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SearchAuditOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceCoverage;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 报告服务，负责聚合报告详情以及对外导出 Markdown / HTML。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final EvidenceQueryService evidenceQueryService;
    private final ObjectMapper objectMapper;
    private static final List<CoverageFieldDefinition> COVERAGE_FIELD_DEFINITIONS = List.of(
            new CoverageFieldDefinition("summary", "overview", "产品概览"),
            new CoverageFieldDefinition("positioning", "positioning", "市场定位"),
            new CoverageFieldDefinition("targetUsers", "target_users", "目标用户"),
            new CoverageFieldDefinition("coreFeatures", "features", "核心能力"),
            new CoverageFieldDefinition("pricing", "pricing", "定价策略"),
            new CoverageFieldDefinition("strengths", "strengths", "优势判断"),
            new CoverageFieldDefinition("weaknesses", "weaknesses", "短板与风险")
    );

    /**
     * 组装报告详情页所需的统一视图数据：正文、证据、结构化知识和质检闭环状态。
     */
    public ReportResponse getReport(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_NOT_FOUND, "taskId=" + taskId));

        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(taskId);
        List<ReportResponse.EvidenceInfo> evidenceInfos = evidences.stream()
                .map(evidenceQueryService::toEvidenceInfo)
                .toList();

        List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(taskId);
        List<CompetitorKnowledgeInfo> knowledgeInfos = knowledges.stream()
                .map(knowledge -> new CompetitorKnowledgeInfo(
                        knowledge.getCompetitorName(),
                        knowledge.getOfficialUrl(),
                        knowledge.getSummary(),
                        knowledge.getPositioning(),
                        parseJsonList(knowledge.getTargetUsers()),
                        parseJsonMap(knowledge.getPricing()),
                        parseJsonList(knowledge.getSourceUrls()),
                        parseJsonMap(knowledge.getEvidenceCoverage())))
                .toList();

        List<QualityIssue> issues = parseQualityIssues(report.getQualityIssues());
        List<TaskNode> nodes = taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        TaskNode initialReviewNode = findNode(nodes, "quality_check");
        TaskNode rewriteNode = findNode(nodes, "rewrite_report");
        TaskNode finalReviewNode = findNode(nodes, "quality_check_final");
        SearchAuditOverview searchAuditOverview = buildSearchAuditOverview(nodes);
        EvidenceCoverageOverview evidenceCoverageOverview = buildEvidenceCoverageOverview(knowledgeInfos);

        // 这里把闭环节点状态和证据摘要一起返回，前端无需再自己拼接多份接口结果。
        return ReportResponse.builder()
                .id(report.getId())
                .taskId(report.getTaskId())
                .title(report.getTitle())
                .content(report.getContent())
                .summary(report.getSummary())
                .qualityScore(report.getQualityScore())
                .qualityPassed(report.isQualityPassed())
                .qualityIssues(issues)
                .initialReview(toReviewCheckpoint(initialReviewNode))
                .revisionPlan(parseRevisionPlan(initialReviewNode))
                .rewriteApplied(rewriteNode != null && rewriteNode.getStatus() == TaskNodeStatus.SUCCESS)
                .finalReview(toReviewCheckpoint(finalReviewNode))
                .evidenceCount(report.getEvidenceCount())
                .evidences(evidenceInfos)
                .searchAuditOverview(searchAuditOverview)
                .evidenceCoverageOverview(evidenceCoverageOverview)
                .competitorKnowledges(knowledgeInfos)
                .createdAt(report.getCreatedAt())
                .updatedAt(report.getUpdatedAt())
                .build();
    }

    public byte[] exportMarkdown(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_NOT_FOUND, "taskId=" + taskId));

        String content = report.getContent();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.REPORT_EXPORT_FAILED, "报告内容为空，无法导出");
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * HTML 导出复用详情页聚合数据，保证导出视图和页面视图尽量一致。
     */
    public byte[] exportHtml(Long taskId) {
        ReportResponse report = getReport(taskId);
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>%s</title>
                  <style>
                    body { font-family: "Segoe UI", Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }
                    .page { max-width: 1080px; margin: 0 auto; padding: 32px 24px 48px; }
                    .card { background: #fff; border-radius: 16px; padding: 24px; margin-bottom: 20px; box-shadow: 0 8px 32px rgba(15,23,42,0.08); }
                    h1, h2, h3 { margin-top: 0; }
                    .meta { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 16px; }
                    .badge { background: #e8eefc; color: #1d4ed8; padding: 6px 12px; border-radius: 999px; font-size: 14px; }
                    .badge.warn { background: #fff3cd; color: #9a6700; }
                    .badge.ok { background: #dcfce7; color: #166534; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
                    .metric { background: #f8fafc; border-radius: 12px; padding: 16px; }
                    .metric strong { display: block; font-size: 28px; margin-top: 8px; }
                    .report-body { white-space: pre-wrap; line-height: 1.7; background: #0f172a; color: #e2e8f0; padding: 20px; border-radius: 12px; overflow-x: auto; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 8px; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #e5e7eb; vertical-align: top; }
                    .muted { color: #64748b; }
                  </style>
                </head>
                <body>
                  <div class="page">
                    <section class="card">
                      <h1>%s</h1>
                      <div class="meta">
                        <span class="badge %s">质量 %s</span>
                        <span class="badge">证据 %d</span>
                        <span class="badge">%s</span>
                      </div>
                    </section>

                    <section class="card">
                      <h2>报告摘要</h2>
                      <p>%s</p>
                    </section>

                    <section class="card">
                      <h2>执行摘要</h2>
                      <div class="grid">
                        <div class="metric">
                          <span class="muted">初审结果</span>
                          <strong>%s</strong>
                        </div>
                        <div class="metric">
                          <span class="muted">终审结果</span>
                          <strong>%s</strong>
                        </div>
                        <div class="metric">
                          <span class="muted">改写流程</span>
                          <strong>%s</strong>
                        </div>
                      </div>
                    </section>

                    <section class="card">
                      <h2>报告正文</h2>
                      <div class="report-body">%s</div>
                    </section>

                    <section class="card">
                      <h2>竞品溯源</h2>
                      %s
                    </section>

                    <section class="card">
                      <h2>证据来源</h2>
                      %s
                    </section>

                    <section class="card">
                      <h2>搜索审计摘要</h2>
                      %s
                    </section>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(report.getTitle()),
                escapeHtml(report.getTitle()),
                report.isQualityPassed() ? "ok" : "warn",
                report.getQualityScore() == null ? "N/A" : escapeHtml(report.getQualityScore() + "/100"),
                report.getEvidenceCount() == null ? 0 : report.getEvidenceCount(),
                report.isRewriteApplied() ? "已执行改写" : "无需改写",
                escapeHtml(report.getSummary() == null || report.getSummary().isBlank() ? "暂无摘要" : report.getSummary()),
                formatReviewStatus(report.getInitialReview()),
                formatReviewStatus(report.getFinalReview()),
                report.isRewriteApplied() ? "已完成改写闭环" : "单轮直出",
                escapeHtml(report.getContent()),
                buildKnowledgeHtml(report.getCompetitorKnowledges()),
                buildEvidenceHtml(report.getEvidences()),
                buildSearchAuditHtml(report.getSearchAuditOverview())
        );

        return html.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 导出时把 sourceUrls 和 evidenceCoverage 一并展开，方便离线文件继续追溯字段来源。
     */
    private String buildKnowledgeHtml(List<CompetitorKnowledgeInfo> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return "<p class=\"muted\">暂无结构化竞品知识。</p>";
        }

        StringBuilder builder = new StringBuilder();
        for (CompetitorKnowledgeInfo knowledge : knowledges) {
            builder.append("<div style=\"margin-bottom:20px;\">")
                    .append("<h3>").append(escapeHtml(knowledge.getCompetitorName())).append("</h3>")
                    .append("<p>").append(escapeHtml(defaultText(knowledge.getSummary(), "暂无摘要"))).append("</p>")
                    .append("<p><strong>来源链接：</strong> ")
                    .append(escapeHtml(String.join(", ", knowledge.getSourceUrls() == null ? List.of() : knowledge.getSourceUrls())))
                    .append("</p>")
                    .append("<pre class=\"report-body\" style=\"background:#f8fafc;color:#1f2937;\">")
                    .append(escapeHtml(prettyJson(knowledge.getEvidenceCoverage())))
                    .append("</pre>")
                    .append("</div>");
        }
        return builder.toString();
    }

    /**
     * HTML 导出保留简化证据表格，优先满足“能回查来源”，不把整段正文全部塞进导出文件。
     */
    private String buildEvidenceHtml(List<ReportResponse.EvidenceInfo> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "<p class=\"muted\">暂无证据记录。</p>";
        }

        StringBuilder rows = new StringBuilder("<table><thead><tr><th>证据编号</th><th>竞品</th><th>标题</th><th>来源链接</th></tr></thead><tbody>");
        for (ReportResponse.EvidenceInfo evidence : evidences) {
            rows.append("<tr>")
                    .append("<td>").append(escapeHtml(evidence.getEvidenceId())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getCompetitorName())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getTitle())).append("</td>")
                    .append("<td>").append(escapeHtml(evidence.getUrl())).append("</td>")
                    .append("</tr>");
        }
        rows.append("</tbody></table>");
        return rows.toString();
    }

    /**
     * 导出文件同时保留搜索恢复、降级和候选统计摘要，便于离线交付时解释来源如何被选中。
     */
    private String buildSearchAuditHtml(SearchAuditOverview overview) {
        if (overview == null || overview.getCollectorNodeCount() == null || overview.getCollectorNodeCount() <= 0) {
            return "<p class=\"muted\">暂无搜索审计记录。</p>";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<div class=\"grid\">")
                .append(metricCard("采集节点", String.valueOf(defaultNumber(overview.getCollectorNodeCount()))))
                .append(metricCard("已记录轨迹", String.valueOf(defaultNumber(overview.getTraceRecordedCount()))))
                .append(metricCard("检查点恢复", String.valueOf(defaultNumber(overview.getCheckpointRecoveredCount()))))
                .append(metricCard("降级次数", String.valueOf(defaultNumber(overview.getDegradedCount()))))
                .append(metricCard("回退链路", String.valueOf(defaultNumber(overview.getProviderFallbackCount()))))
                .append("</div>");

        builder.append("<table><thead><tr>")
                .append("<th>节点</th><th>节点状态</th><th>竞品 / 类型</th><th>补源方式</th><th>恢复状态</th><th>候选统计</th><th>异常说明</th>")
                .append("</tr></thead><tbody>");
        for (CollectorSearchAudit collector : overview.getCollectors() == null ? List.<CollectorSearchAudit>of() : overview.getCollectors()) {
            builder.append("<tr>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getNodeName(), "-"))).append("</td>")
                    .append("<td>").append(escapeHtml(collector.getNodeStatus() == null ? "-" : collector.getNodeStatus().name())).append("</td>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getCompetitorName(), "-")))
                    .append(" / ")
                    .append(escapeHtml(defaultText(collector.getSourceType(), "-"))).append("</td>")
                    .append("<td>").append(escapeHtml(defaultText(collector.getSupplementMethod(),
                            Boolean.TRUE.equals(collector.getTraceRecorded()) ? "NONE" : "未记录"))).append("</td>")
                    .append("<td>").append(escapeHtml(formatRecoveryLabel(collector))).append("</td>")
                    .append("<td>").append(escapeHtml(formatCollectorCounts(collector))).append("</td>")
                    .append("<td>").append(escapeHtml(formatCollectorIssue(collector))).append("</td>")
                    .append("</tr>");
        }
        builder.append("</tbody></table>");
        return builder.toString();
    }

    private String metricCard(String label, String value) {
        return """
                <div class="metric">
                  <span class="muted">%s</span>
                  <strong>%s</strong>
                </div>
                """.formatted(escapeHtml(label), escapeHtml(value));
    }

    private String formatReviewStatus(ReviewCheckpoint review) {
        if (review == null) {
            return "未触发";
        }
        if (review.getPassed() == null) {
            return switch (review.getNodeStatus()) {
                case PENDING -> "待执行";
                case RUNNING -> "执行中";
                case PAUSED -> "已暂停";
                case SUCCESS -> "已完成";
                case FAILED -> "失败";
                case SKIPPED -> "已跳过";
            };
        }
        return review.getPassed() ? "已通过" : "需修订";
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of(json);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    private List<QualityIssue> parseQualityIssues(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QualityIssue>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse quality issues failed", e);
            return List.of();
        }
    }

    private TaskNode findNode(List<TaskNode> nodes, String nodeName) {
        return nodes.stream()
                .filter(node -> nodeName.equals(node.getNodeName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 报告级搜索审计摘要来自采集节点输出中的 searchExecutionTrace。
     * 这样不引入新表，也能把恢复、降级和补源结果提升到正式交付视图。
     */
    private SearchAuditOverview buildSearchAuditOverview(List<TaskNode> nodes) {
        List<CollectorSearchAudit> collectors = new ArrayList<>();
        int traceRecordedCount = 0;
        int checkpointRecoveredCount = 0;
        int degradedCount = 0;
        int providerFallbackCount = 0;
        int browserBlockedCount = 0;
        int plannedCandidateCount = 0;
        int verifiedCandidateCount = 0;
        int supplementedCandidateCount = 0;
        int selectedCandidateCount = 0;

        for (TaskNode node : nodes) {
            if (node.getAgentType() != AgentType.COLLECTOR) {
                continue;
            }
            JsonNode output = readJson(node.getOutputData());
            JsonNode config = readJson(node.getNodeConfig());
            JsonNode trace = output == null ? null : output.path("searchExecutionTrace");
            boolean traceRecorded = trace != null && !trace.isMissingNode() && !trace.isNull();

            CollectorSearchAudit collector = CollectorSearchAudit.builder()
                    .nodeName(node.getNodeName())
                    .nodeStatus(node.getStatus())
                    .competitorName(readText(output, "competitor", config, "competitorName"))
                    .sourceType(readText(output, "sourceType", config, "sourceType"))
                    .traceRecorded(traceRecorded)
                    .auditMessage(buildCollectorAuditMessage(node, traceRecorded))
                    .supplementMethod(traceRecorded ? trace.path("supplementMethod").asText(null) : null)
                    .resumedFromCheckpoint(traceRecorded ? readBoolean(trace, "resumedFromCheckpoint") : null)
                    .checkpointSource(traceRecorded ? trace.path("checkpointSource").asText(null) : null)
                    .degraded(traceRecorded ? readBoolean(trace, "degraded") : null)
                    .degradationReason(traceRecorded ? trace.path("degradationReason").asText(null) : null)
                    .providerFallbackUsed(traceRecorded ? readBoolean(trace, "providerFallbackUsed") : null)
                    .fallbackDecision(traceRecorded ? trace.path("fallbackDecision").asText(null) : null)
                    .browserTraceId(traceRecorded ? trace.path("browserTraceId").asText(null) : null)
                    .browserBlockedReason(traceRecorded ? trace.path("browserBlockedReason").asText(null) : null)
                    .browserBlockedCount(traceRecorded ? readInteger(trace, "browserBlockedCount") : null)
                    .recoveryCheckpoint(traceRecorded ? trace.path("recoveryCheckpoint").asText(null) : null)
                    .plannedCandidateCount(traceRecorded ? readInteger(trace, "plannedCandidateCount") : null)
                    .verifiedCandidateCount(traceRecorded ? readInteger(trace, "verifiedCandidateCount") : null)
                    .supplementedCandidateCount(traceRecorded ? readInteger(trace, "supplementedCandidateCount") : null)
                    .selectedCandidateCount(traceRecorded ? readInteger(trace, "selectedCandidateCount") : null)
                    .selectedUrls(traceRecorded ? readStringList(trace.path("selectedUrls")) : List.of())
                    .errorMessage(node.getErrorMessage())
                    .build();
            collectors.add(collector);

            if (traceRecorded) {
                traceRecordedCount++;
                if (Boolean.TRUE.equals(collector.getResumedFromCheckpoint())) {
                    checkpointRecoveredCount++;
                }
                if (Boolean.TRUE.equals(collector.getDegraded())) {
                    degradedCount++;
                }
                if (Boolean.TRUE.equals(collector.getProviderFallbackUsed())) {
                    providerFallbackCount++;
                }
                browserBlockedCount += defaultNumber(collector.getBrowserBlockedCount());
                plannedCandidateCount += defaultNumber(collector.getPlannedCandidateCount());
                verifiedCandidateCount += defaultNumber(collector.getVerifiedCandidateCount());
                supplementedCandidateCount += defaultNumber(collector.getSupplementedCandidateCount());
                selectedCandidateCount += defaultNumber(collector.getSelectedCandidateCount());
            }
        }

        return SearchAuditOverview.builder()
                .collectorNodeCount(collectors.size())
                .traceRecordedCount(traceRecordedCount)
                .checkpointRecoveredCount(checkpointRecoveredCount)
                .degradedCount(degradedCount)
                .providerFallbackCount(providerFallbackCount)
                .browserBlockedCount(browserBlockedCount)
                .plannedCandidateCount(plannedCandidateCount)
                .verifiedCandidateCount(verifiedCandidateCount)
                .supplementedCandidateCount(supplementedCandidateCount)
                .selectedCandidateCount(selectedCandidateCount)
                .collectors(collectors)
                .build();
    }

    /**
     * 把结构化知识里的字段级 evidenceCoverage 汇总成章节级概览，供报告页展示和 Reviewer 强约束复用。
     */
    private EvidenceCoverageOverview buildEvidenceCoverageOverview(List<CompetitorKnowledgeInfo> knowledges) {
        if (knowledges == null || knowledges.isEmpty()) {
            return EvidenceCoverageOverview.builder()
                    .totalFields(0)
                    .traceableFields(0)
                    .missingEvidenceFields(0)
                    .emptyFields(0)
                    .sections(List.of())
                    .competitors(List.of())
                    .build();
        }

        Map<String, SectionCoverageAccumulator> sectionAccumulators = new LinkedHashMap<>();
        List<CompetitorEvidenceCoverage> competitorSummaries = new ArrayList<>();
        int totalFields = 0;
        int traceableFields = 0;
        int missingEvidenceFields = 0;
        int emptyFields = 0;

        for (CompetitorKnowledgeInfo knowledge : knowledges) {
            int competitorTotal = 0;
            int competitorTraceable = 0;
            int competitorMissing = 0;
            int competitorEmpty = 0;
            LinkedHashSet<String> missingSections = new LinkedHashSet<>();
            Map<String, Object> evidenceCoverage = knowledge.getEvidenceCoverage() == null ? Map.of() : knowledge.getEvidenceCoverage();

            for (CoverageFieldDefinition definition : COVERAGE_FIELD_DEFINITIONS) {
                CoverageStatus status = resolveCoverageStatus(evidenceCoverage.get(definition.fieldKey()));

                SectionCoverageAccumulator section = sectionAccumulators.computeIfAbsent(
                        definition.sectionKey(),
                        key -> new SectionCoverageAccumulator(definition.sectionKey(), definition.sectionTitle())
                );
                section.totalFields++;
                competitorTotal++;
                totalFields++;

                switch (status) {
                    case TRACEABLE -> {
                        section.traceableFields++;
                        competitorTraceable++;
                        traceableFields++;
                    }
                    case MISSING_EVIDENCE -> {
                        section.missingEvidenceFields++;
                        section.missingFields.add(displayMissingField(definition.sectionTitle(), knowledge.getCompetitorName()));
                        competitorMissing++;
                        missingEvidenceFields++;
                        missingSections.add(definition.sectionTitle());
                    }
                    case EMPTY -> {
                        section.emptyFields++;
                        competitorEmpty++;
                        emptyFields++;
                    }
                }
            }

            competitorSummaries.add(CompetitorEvidenceCoverage.builder()
                    .competitorName(knowledge.getCompetitorName())
                    .totalFields(competitorTotal)
                    .traceableFields(competitorTraceable)
                    .missingEvidenceFields(competitorMissing)
                    .emptyFields(competitorEmpty)
                    .missingSections(new ArrayList<>(missingSections))
                    .build());
        }

        List<SectionEvidenceCoverage> sections = sectionAccumulators.values().stream()
                .map(item -> SectionEvidenceCoverage.builder()
                        .sectionKey(item.sectionKey)
                        .sectionTitle(item.sectionTitle)
                        .totalFields(item.totalFields)
                        .traceableFields(item.traceableFields)
                        .missingEvidenceFields(item.missingEvidenceFields)
                        .emptyFields(item.emptyFields)
                        .missingFields(new ArrayList<>(item.missingFields))
                        .build())
                .toList();

        return EvidenceCoverageOverview.builder()
                .totalFields(totalFields)
                .traceableFields(traceableFields)
                .missingEvidenceFields(missingEvidenceFields)
                .emptyFields(emptyFields)
                .sections(sections)
                .competitors(competitorSummaries)
                .build();
    }

    /**
     * Reviewer 节点原始输出是 JSON 字符串，这里统一转换成前端直接可消费的结构。
     */
    private ReviewCheckpoint toReviewCheckpoint(TaskNode node) {
        if (node == null) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        return ReviewCheckpoint.builder()
                .nodeName(node.getNodeName())
                .nodeStatus(node.getStatus())
                .score(output == null || output.path("score").isMissingNode() ? null : output.path("score").asInt())
                .passed(output == null || output.path("passed").isMissingNode() ? null : output.path("passed").asBoolean())
                .requiresHumanIntervention(readBoolean(output, "requiresHumanIntervention"))
                .autoRewriteAllowed(readBoolean(output, "autoRewriteAllowed"))
                .summary(output == null ? null : output.path("summary").asText(null))
                .issues(output == null ? List.of() : parseQualityIssues(output.path("issues").toString()))
                .nextActions(output == null || !output.has("nextActions")
                        ? List.of()
                        : parseNextActions(output.path("nextActions").toString()))
                .build();
    }

    private List<ReviewNextAction> parseNextActions(String json) {
        if (json == null || json.isBlank() || "null".equals(json) || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReviewNextAction>>() {});
        } catch (JsonProcessingException e) {
            log.warn("parse review next actions failed", e);
            return List.of();
        }
    }

    private RevisionPlan parseRevisionPlan(TaskNode node) {
        if (node == null) {
            return null;
        }
        JsonNode output = readJson(node.getOutputData());
        if (output == null || !output.has("revisionPlan")) {
            return null;
        }

        JsonNode revisionPlan = output.get("revisionPlan");
        try {
            if (revisionPlan.isTextual()) {
                return objectMapper.readValue(revisionPlan.asText(), RevisionPlan.class);
            }
            return objectMapper.treeToValue(revisionPlan, RevisionPlan.class);
        } catch (JsonProcessingException e) {
            log.warn("parse revision plan failed", e);
            return null;
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Boolean readBoolean(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asBoolean();
    }

    private Integer readInteger(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asInt();
    }

    private String prettyJson(Map<String, Object> value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatRecoveryLabel(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            return "未记录轨迹";
        }
        if (Boolean.TRUE.equals(collector.getResumedFromCheckpoint())) {
            return "已恢复(" + defaultText(collector.getCheckpointSource(), "CHECKPOINT") + ")";
        }
        return "未恢复";
    }

    private String formatCollectorCounts(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            return "未记录结构化候选统计";
        }
        return "规划 " + defaultNumber(collector.getPlannedCandidateCount())
                + " / 验证 " + defaultNumber(collector.getVerifiedCandidateCount())
                + " / 补源 " + defaultNumber(collector.getSupplementedCandidateCount())
                + " / 选中 " + defaultNumber(collector.getSelectedCandidateCount());
    }

    private String formatCollectorIssue(CollectorSearchAudit collector) {
        if (!Boolean.TRUE.equals(collector.getTraceRecorded())) {
            if (collector.getAuditMessage() != null && !collector.getAuditMessage().isBlank()) {
                return collector.getAuditMessage();
            }
            if (collector.getErrorMessage() != null && !collector.getErrorMessage().isBlank()) {
                return "失败：" + collector.getErrorMessage();
            }
            return "采集节点未生成结构化搜索轨迹";
        }
        if (Boolean.TRUE.equals(collector.getDegraded()) && collector.getDegradationReason() != null) {
            return "降级：" + collector.getDegradationReason();
        }
        if (collector.getBrowserBlockedReason() != null && !collector.getBrowserBlockedReason().isBlank()) {
            return "阻断：" + collector.getBrowserBlockedReason();
        }
        if (collector.getErrorMessage() != null && !collector.getErrorMessage().isBlank()) {
            return "失败：" + collector.getErrorMessage();
        }
        if (collector.getFallbackDecision() != null && !collector.getFallbackDecision().isBlank()) {
            return "决策：" + collector.getFallbackDecision();
        }
        return "无";
    }

    private String readText(JsonNode primary, String primaryField, JsonNode fallback, String fallbackField) {
        String primaryValue = primary == null ? null : primary.path(primaryField).asText(null);
        if (primaryValue != null && !primaryValue.isBlank()) {
            return primaryValue;
        }
        String fallbackValue = fallback == null ? null : fallback.path(fallbackField).asText(null);
        return (fallbackValue == null || fallbackValue.isBlank()) ? null : fallbackValue;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private String buildCollectorAuditMessage(TaskNode node, boolean traceRecorded) {
        if (traceRecorded) {
            return "已记录结构化搜索轨迹";
        }
        if (node.getStatus() == TaskNodeStatus.FAILED) {
            return "采集节点执行失败，未生成结构化搜索轨迹";
        }
        if (node.getStatus() == TaskNodeStatus.RUNNING) {
            return "采集节点仍在执行，结构化搜索轨迹尚未写入";
        }
        if (node.getStatus() == TaskNodeStatus.PENDING) {
            return "采集节点尚未开始执行";
        }
        if (node.getStatus() == TaskNodeStatus.PAUSED) {
            return "采集节点已暂停，结构化搜索轨迹等待恢复后继续写入";
        }
        if (node.getStatus() == TaskNodeStatus.SKIPPED) {
            return "采集节点已跳过，未生成结构化搜索轨迹";
        }
        return "采集节点未生成结构化搜索轨迹";
    }

    private CoverageStatus resolveCoverageStatus(Object rawCoverage) {
        if (!(rawCoverage instanceof Map<?, ?> coverageMap)) {
            return CoverageStatus.EMPTY;
        }
        Object status = coverageMap.get("status");
        if (status == null) {
            Object hasValue = coverageMap.get("hasValue");
            return Boolean.TRUE.equals(hasValue) ? CoverageStatus.MISSING_EVIDENCE : CoverageStatus.EMPTY;
        }
        String normalized = String.valueOf(status).trim().toUpperCase();
        return switch (normalized) {
            case "TRACEABLE" -> CoverageStatus.TRACEABLE;
            case "MISSING_EVIDENCE", "PARTIAL" -> CoverageStatus.MISSING_EVIDENCE;
            default -> CoverageStatus.EMPTY;
        };
    }

    private String displayMissingField(String sectionTitle, String competitorName) {
        return defaultText(competitorName, "未知竞品") + " / " + sectionTitle;
    }

    private record CoverageFieldDefinition(String fieldKey, String sectionKey, String sectionTitle) {
    }

    private enum CoverageStatus {
        TRACEABLE,
        MISSING_EVIDENCE,
        EMPTY
    }

    private static final class SectionCoverageAccumulator {
        private final String sectionKey;
        private final String sectionTitle;
        private int totalFields;
        private int traceableFields;
        private int missingEvidenceFields;
        private int emptyFields;
        private final LinkedHashSet<String> missingFields = new LinkedHashSet<>();

        private SectionCoverageAccumulator(String sectionKey, String sectionTitle) {
            this.sectionKey = sectionKey;
            this.sectionTitle = sectionTitle;
        }
    }
}
