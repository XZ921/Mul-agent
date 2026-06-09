package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportExportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 正式导出渲染器契约。
 * <p>
 * Task 5.7.b 要求把 Markdown / HTML / JSON 三类导出物拆成独立渲染职责，
 * 避免继续在单一服务方法里混杂字符串拼接。
 */
public interface ReportExportRenderer {

    /**
     * 判断当前渲染器是否负责指定导出格式。
     */
    boolean supports(String exportFormat);

    /**
     * 基于统一的报告载荷与正式导出记录，渲染最终可下载的导出物。
     */
    RenderedExportPackage render(ReportResponse report,
                                 ReportExportResponse record,
                                 ObjectMapper objectMapper);

    /**
     * 正式导出包统一返回内容类型、文件名、字节内容和正式记录摘要，
     * 方便后续控制器或交付中心直接复用，而不必重新拼装下载元数据。
     */
    record RenderedExportPackage(String contentType,
                                 String fileName,
                                 byte[] content,
                                 ReportExportResponse record) {
    }
}

/**
 * Markdown 导出渲染器。
 */
final class MarkdownReportExportRenderer implements ReportExportRenderer {

    @Override
    public boolean supports(String exportFormat) {
        return "MARKDOWN".equalsIgnoreCase(exportFormat);
    }

    @Override
    public RenderedExportPackage render(ReportResponse report,
                                        ReportExportResponse record,
                                        ObjectMapper objectMapper) {
        String markdown = """
                # %s

                > 正式导出版本：v%s
                > 导出摘要：%s

                ## 报告摘要

                %s

                ## 交付摘要

                - 交付状态：%s
                - 摘要：%s
                - 最大问题：%s
                - 建议动作：%s

                ## 审计摘要

                - 摘要：%s
                - 检索审计：%s
                - Task RAG 摘要：%s

                ## 证据入口

                - 入口说明：%s
                - 关键证据：%s
                - 来源链接：%s

                ## 报告正文

                %s

                ## 证据来源

                %s
                """.formatted(
                ReportExportRenderSupport.safeText(report.getTitle()),
                record.getExportVersion(),
                ReportExportRenderSupport.safeText(record.getExportSummary()),
                ReportExportRenderSupport.safeText(report.getSummary()),
                ReportExportRenderSupport.deliveryStatus(report),
                ReportExportRenderSupport.deliverySummary(report),
                ReportExportRenderSupport.primaryIssue(report),
                ReportExportRenderSupport.recommendedAction(report),
                ReportExportRenderSupport.auditSummary(report),
                ReportExportRenderSupport.buildSearchAuditSummary(report),
                ReportExportRenderSupport.taskRagAuditSummary(report),
                ReportExportRenderSupport.evidenceEntrySummary(report),
                ReportExportRenderSupport.evidenceEntryTitle(report),
                ReportExportRenderSupport.evidenceEntryUrl(report),
                ReportExportRenderSupport.safeText(report.getContent()),
                buildMarkdownEvidenceLines(report.getEvidences())
        );
        return new RenderedExportPackage(
                "text/markdown; charset=UTF-8",
                "competitor-analysis-report-v" + record.getExportVersion() + ".md",
                markdown.getBytes(StandardCharsets.UTF_8),
                record
        );
    }

    private String buildMarkdownEvidenceLines(List<EvidenceInfo> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "- 暂无证据记录";
        }
        List<String> lines = new ArrayList<>();
        for (EvidenceInfo evidence : evidences) {
            lines.add("- %s (%s)".formatted(
                    ReportExportRenderSupport.safeText(evidence.getTitle()),
                    ReportExportRenderSupport.safeText(evidence.getUrl())
            ));
        }
        return String.join("\n", lines);
    }

}

/**
 * HTML 导出渲染器。
 */
final class HtmlReportExportRenderer implements ReportExportRenderer {

    @Override
    public boolean supports(String exportFormat) {
        return "HTML".equalsIgnoreCase(exportFormat);
    }

    @Override
    public RenderedExportPackage render(ReportResponse report,
                                        ReportExportResponse record,
                                        ObjectMapper objectMapper) {
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
                    h1, h2 { margin-top: 0; }
                    .badge { display: inline-block; padding: 6px 12px; border-radius: 999px; background: #e8eefc; color: #1d4ed8; margin-right: 8px; }
                    .report-body { white-space: pre-wrap; line-height: 1.7; background: #0f172a; color: #e2e8f0; padding: 20px; border-radius: 12px; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 8px; }
                  </style>
                </head>
                <body>
                  <div class="page">
                    <section class="card">
                      <h1>%s</h1>
                      <span class="badge">正式导出版本 v%s</span>
                      <span class="badge">%s</span>
                    </section>
                    <section class="card">
                      <h2>报告摘要</h2>
                      <p>%s</p>
                    </section>
                    <section class="card">
                      <h2>交付摘要</h2>
                      <ul>
                        <li>交付状态：%s</li>
                        <li>摘要：%s</li>
                        <li>最大问题：%s</li>
                        <li>建议动作：%s</li>
                      </ul>
                    </section>
                    <section class="card">
                      <h2>审计摘要</h2>
                      <ul>
                        <li>摘要：%s</li>
                        <li>检索审计：%s</li>
                        <li>Task RAG 摘要：%s</li>
                      </ul>
                    </section>
                    <section class="card">
                      <h2>证据入口</h2>
                      <ul>
                        <li>入口说明：%s</li>
                        <li>关键证据：%s</li>
                        <li>来源链接：<a href="%s">%s</a></li>
                      </ul>
                    </section>
                    <section class="card">
                      <h2>报告正文</h2>
                      <div class="report-body">%s</div>
                    </section>
                    <section class="card">
                      <h2>证据来源</h2>
                      <ul>%s</ul>
                    </section>
                  </div>
                </body>
                </html>
                """.formatted(
                ReportExportRenderSupport.safeText(report.getTitle()),
                ReportExportRenderSupport.safeText(report.getTitle()),
                record.getExportVersion(),
                escapeHtml(ReportExportRenderSupport.safeText(record.getExportSummary())),
                escapeHtml(ReportExportRenderSupport.safeText(report.getSummary())),
                escapeHtml(ReportExportRenderSupport.deliveryStatus(report)),
                escapeHtml(ReportExportRenderSupport.deliverySummary(report)),
                escapeHtml(ReportExportRenderSupport.primaryIssue(report)),
                escapeHtml(ReportExportRenderSupport.recommendedAction(report)),
                escapeHtml(ReportExportRenderSupport.auditSummary(report)),
                escapeHtml(ReportExportRenderSupport.buildSearchAuditSummary(report)),
                escapeHtml(ReportExportRenderSupport.taskRagAuditSummary(report)),
                escapeHtml(ReportExportRenderSupport.evidenceEntrySummary(report)),
                escapeHtml(ReportExportRenderSupport.evidenceEntryTitle(report)),
                escapeHtml(ReportExportRenderSupport.evidenceEntryUrl(report)),
                escapeHtml(ReportExportRenderSupport.evidenceEntryUrl(report)),
                escapeHtml(ReportExportRenderSupport.safeText(report.getContent())),
                buildHtmlEvidenceLines(report.getEvidences())
        );
        return new RenderedExportPackage(
                "text/html; charset=UTF-8",
                "competitor-analysis-report-v" + record.getExportVersion() + ".html",
                html.getBytes(StandardCharsets.UTF_8),
                record
        );
    }

    private String buildHtmlEvidenceLines(List<EvidenceInfo> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "<li>暂无证据记录</li>";
        }
        List<String> lines = new ArrayList<>();
        for (EvidenceInfo evidence : evidences) {
            lines.add("<li>%s (<a href=\"%s\">%s</a>)</li>".formatted(
                    escapeHtml(ReportExportRenderSupport.safeText(evidence.getTitle())),
                    escapeHtml(ReportExportRenderSupport.safeText(evidence.getUrl())),
                    escapeHtml(ReportExportRenderSupport.safeText(evidence.getUrl()))
            ));
        }
        return String.join("", lines);
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

/**
 * JSON 证据包渲染器。
 */
final class JsonEvidencePackageExportRenderer implements ReportExportRenderer {

    @Override
    public boolean supports(String exportFormat) {
        return "JSON".equalsIgnoreCase(exportFormat);
    }

    @Override
    public RenderedExportPackage render(ReportResponse report,
                                        ReportExportResponse record,
                                        ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery", Map.of(
                "taskId", report.getTaskId(),
                "format", record.getExportFormat(),
                "exportVersion", record.getExportVersion(),
                "exportSummary", record.getExportSummary()
        ));
        payload.put("report", Map.of(
                "title", ReportExportRenderSupport.safeText(report.getTitle()),
                "summary", ReportExportRenderSupport.safeText(report.getSummary()),
                "content", ReportExportRenderSupport.safeText(report.getContent()),
                "qualityPassed", report.isQualityPassed(),
                "qualityScore", report.getQualityScore() == null ? 0 : report.getQualityScore()
        ));
        payload.put("auditSummary", Map.of(
                "summary", ReportExportRenderSupport.auditSummary(report),
                "searchAuditSummary", ReportExportRenderSupport.buildSearchAuditSummary(report),
                "taskRagAuditSummary", ReportExportRenderSupport.taskRagAuditSummary(report)
        ));
        payload.put("deliverySummary", Map.of(
                "readyForDelivery", ReportExportRenderSupport.readyForDelivery(report),
                "deliveryStatus", ReportExportRenderSupport.deliveryStatus(report),
                "summary", ReportExportRenderSupport.deliverySummary(report),
                "primaryIssue", ReportExportRenderSupport.primaryIssue(report),
                "recommendedAction", ReportExportRenderSupport.recommendedAction(report),
                "blockerCount", ReportExportRenderSupport.blockerCount(report),
                "evidenceGapCount", ReportExportRenderSupport.evidenceGapCount(report)
        ));
        payload.put("evidenceEntryPoint", Map.of(
                "summary", ReportExportRenderSupport.evidenceEntrySummary(report),
                "title", ReportExportRenderSupport.evidenceEntryTitle(report),
                "url", ReportExportRenderSupport.evidenceEntryUrl(report),
                "evidenceId", ReportExportRenderSupport.evidenceEntryId(report),
                "sourceType", ReportExportRenderSupport.evidenceEntrySourceType(report)
        ));
        payload.put("evidences", report.getEvidences() == null ? List.of() : report.getEvidences());
        payload.put("sourceUrls", record.getSourceUrls() == null ? List.of() : record.getSourceUrls());

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            return new RenderedExportPackage(
                    "application/json; charset=UTF-8",
                    "competitor-analysis-report-v" + record.getExportVersion() + ".json",
                    json.getBytes(StandardCharsets.UTF_8),
                    record
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导出 JSON 证据包失败", exception);
        }
    }
}

/**
 * 渲染器共享的业务摘要工具。
 */
final class ReportExportRenderSupport {

    private ReportExportRenderSupport() {
    }

    /**
     * 导出摘要优先面向交付和审计消费，因此这里统一把质量、阻塞和证据缺口收口成一句稳定说明。
     */
    static String buildExportSummary(ReportResponse report) {
        return "%s，阻塞问题 %d 个，证据缺口 %d 个，证据条目 %d 条".formatted(
                report.isQualityPassed() ? "质量已通过" : "质量未通过",
                blockerCount(report),
                evidenceGapCount(report),
                report.getEvidenceCount() == null ? 0 : report.getEvidenceCount()
        );
    }

    /**
     * 正式导出记录必须保留 sourceUrls，方便离线包仍能回查来源。
     * 这里同时合并报告诊断与证据列表中的 URL，避免只依赖单一路径字段。
     */
    static List<String> collectSourceUrls(ReportResponse report) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (report.getReportDiagnosis() != null && report.getReportDiagnosis().getSourceUrls() != null) {
            merged.addAll(report.getReportDiagnosis().getSourceUrls());
        }
        if (report.getEvidences() != null) {
            for (EvidenceInfo evidence : report.getEvidences()) {
                if (evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
                    merged.add(evidence.getUrl().trim());
                }
            }
        }
        return new ArrayList<>(merged);
    }

    static int diagnosisCount(ReportResponse report) {
        return report.getReportDiagnosis() == null || report.getReportDiagnosis().getDiagnosisCount() == null
                ? 0
                : report.getReportDiagnosis().getDiagnosisCount();
    }

    static int blockerCount(ReportResponse report) {
        return report.getReportDiagnosis() == null || report.getReportDiagnosis().getBlockerCount() == null
                ? 0
                : report.getReportDiagnosis().getBlockerCount();
    }

    static int evidenceGapCount(ReportResponse report) {
        return report.getReportDiagnosis() == null || report.getReportDiagnosis().getEvidenceGapCount() == null
                ? 0
                : report.getReportDiagnosis().getEvidenceGapCount();
    }

    /**
     * 正式导出优先消费交付摘要中的 readyForDelivery 标记；
     * 如果历史报告尚未回填该字段，再回退到质量通过状态，保证旧数据仍可导出。
     */
    static boolean readyForDelivery(ReportResponse report) {
        return report.getDeliverySummary() != null && report.getDeliverySummary().getReadyForDelivery() != null
                ? report.getDeliverySummary().getReadyForDelivery()
                : report.isQualityPassed();
    }

    /**
     * 导出物需要稳定呈现“当前能不能交付”，
     * 因此优先读取 deliverySummary.deliveryStatus，没有则按历史质量结论兜底。
     */
    static String deliveryStatus(ReportResponse report) {
        if (report.getDeliverySummary() != null && report.getDeliverySummary().getDeliveryStatus() != null) {
            return safeText(report.getDeliverySummary().getDeliveryStatus());
        }
        return report.isQualityPassed() ? "READY" : "REVIEW_REQUIRED";
    }

    /**
     * 交付摘要正文优先使用业务可读的 deliverySummary.summary，
     * 避免导出文件继续把使用者引回 reportDiagnosis 原始结构。
     */
    static String deliverySummary(ReportResponse report) {
        if (report.getDeliverySummary() != null && report.getDeliverySummary().getSummary() != null) {
            return safeText(report.getDeliverySummary().getSummary());
        }
        return buildExportSummary(report);
    }

    static String primaryIssue(ReportResponse report) {
        if (report.getDeliverySummary() != null && report.getDeliverySummary().getPrimaryIssue() != null) {
            return safeText(report.getDeliverySummary().getPrimaryIssue());
        }
        return "当前未提供主问题摘要";
    }

    static String recommendedAction(ReportResponse report) {
        if (report.getDeliverySummary() != null && report.getDeliverySummary().getRecommendedAction() != null) {
            return safeText(report.getDeliverySummary().getRecommendedAction());
        }
        return "建议先补齐关键证据后再重新交付。";
    }

    static String buildSearchAuditSummary(ReportResponse report) {
        if (report.getAuditSummary() != null && report.getAuditSummary().getSearchAuditSummary() != null) {
            return safeText(report.getAuditSummary().getSearchAuditSummary());
        }
        if (report.getSearchAuditOverview() == null) {
            return "当前无检索审计摘要";
        }
        ReportResponse.SearchAuditOverview audit = report.getSearchAuditOverview();
        return "采集节点 %d 个，已记录轨迹 %d 个，最终选中候选 %d 个".formatted(
                audit.getCollectorNodeCount() == null ? 0 : audit.getCollectorNodeCount(),
                audit.getTraceRecordedCount() == null ? 0 : audit.getTraceRecordedCount(),
                audit.getSelectedCandidateCount() == null ? 0 : audit.getSelectedCandidateCount()
        );
    }

    /**
     * Task RAG 审计在正式导出里只保留一句业务可读摘要，
     * 避免 JSON / Markdown / HTML 再暴露节点级原始审计列表。
     */
    static String taskRagAuditSummary(ReportResponse report) {
        if (report.getAuditSummary() != null && report.getAuditSummary().getTaskRagAuditSummary() != null) {
            return safeText(report.getAuditSummary().getTaskRagAuditSummary());
        }
        if (report.getTaskRagAudits() == null || report.getTaskRagAudits().isEmpty()) {
            return "当前无 Task RAG 审计摘要";
        }
        for (ReportResponse.TaskRagAuditInfo audit : report.getTaskRagAudits()) {
            if (audit != null && audit.getTaskRagContext() != null && !audit.getTaskRagContext().isBlank()) {
                return safeText(audit.getTaskRagContext());
            }
        }
        return "当前无 Task RAG 审计摘要";
    }

    static String auditSummary(ReportResponse report) {
        if (report.getAuditSummary() != null && report.getAuditSummary().getSummary() != null) {
            return safeText(report.getAuditSummary().getSummary());
        }
        return buildSearchAuditSummary(report);
    }

    /**
     * 证据入口要优先指向“第一条应该核对的证据”，
     * 因此这里统一从 evidenceEntryPoint 读取；若历史报告还没有该字段，再回退到第一条证据。
     */
    static String evidenceEntrySummary(ReportResponse report) {
        if (report.getEvidenceEntryPoint() != null && report.getEvidenceEntryPoint().getSummary() != null) {
            return safeText(report.getEvidenceEntryPoint().getSummary());
        }
        return "请优先核对关键证据入口。";
    }

    static String evidenceEntryTitle(ReportResponse report) {
        if (report.getEvidenceEntryPoint() != null && report.getEvidenceEntryPoint().getTitle() != null) {
            return safeText(report.getEvidenceEntryPoint().getTitle());
        }
        return firstEvidence(report) == null ? "当前无关键证据" : safeText(firstEvidence(report).getTitle());
    }

    static String evidenceEntryUrl(ReportResponse report) {
        if (report.getEvidenceEntryPoint() != null && report.getEvidenceEntryPoint().getUrl() != null) {
            return safeText(report.getEvidenceEntryPoint().getUrl());
        }
        return firstEvidence(report) == null ? "" : safeText(firstEvidence(report).getUrl());
    }

    static String evidenceEntryId(ReportResponse report) {
        if (report.getEvidenceEntryPoint() != null && report.getEvidenceEntryPoint().getEvidenceId() != null) {
            return safeText(report.getEvidenceEntryPoint().getEvidenceId());
        }
        return firstEvidence(report) == null ? "" : safeText(firstEvidence(report).getEvidenceId());
    }

    static String evidenceEntrySourceType(ReportResponse report) {
        if (report.getEvidenceEntryPoint() != null && report.getEvidenceEntryPoint().getSourceType() != null) {
            return safeText(report.getEvidenceEntryPoint().getSourceType());
        }
        return firstEvidence(report) == null ? "" : safeText(firstEvidence(report).getSourceType());
    }

    private static EvidenceInfo firstEvidence(ReportResponse report) {
        if (report.getEvidences() == null || report.getEvidences().isEmpty()) {
            return null;
        }
        return report.getEvidences().get(0);
    }

    static String safeText(String value) {
        return value == null ? "" : value;
    }
}
