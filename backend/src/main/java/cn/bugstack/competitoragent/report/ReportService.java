package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CompetitorKnowledgeInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.QualityIssue;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewCheckpoint;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
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
    private final ObjectMapper objectMapper;

    /**
     * 组装报告详情页所需的统一视图数据：正文、证据、结构化知识和质检闭环状态。
     */
    public ReportResponse getReport(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_NOT_FOUND, "taskId=" + taskId));

        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(taskId);
        List<EvidenceInfo> evidenceInfos = evidences.stream()
                .map(evidence -> new EvidenceInfo(
                        evidence.getEvidenceId(),
                        evidence.getTitle(),
                        evidence.getUrl(),
                        evidence.getContentSnippet(),
                        evidence.getCompetitorName(),
                        evidence.getCollectedAt()))
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
                buildEvidenceHtml(report.getEvidences())
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
    private String buildEvidenceHtml(List<EvidenceInfo> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "<p class=\"muted\">暂无证据记录。</p>";
        }

        StringBuilder rows = new StringBuilder("<table><thead><tr><th>证据编号</th><th>竞品</th><th>标题</th><th>来源链接</th></tr></thead><tbody>");
        for (EvidenceInfo evidence : evidences) {
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

    private String formatReviewStatus(ReviewCheckpoint review) {
        if (review == null) {
            return "未触发";
        }
        if (review.getPassed() == null) {
            return switch (review.getNodeStatus()) {
                case PENDING -> "待执行";
                case RUNNING -> "执行中";
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
                .summary(output == null ? null : output.path("summary").asText(null))
                .issues(output == null ? List.of() : parseQualityIssues(output.path("issues").toString()))
                .build();
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
}
