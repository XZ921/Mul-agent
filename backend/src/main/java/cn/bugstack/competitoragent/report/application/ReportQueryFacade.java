package cn.bugstack.competitoragent.report.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;

/**
 * report-delivery 对外暴露的稳定读取门面。
 * <p>
 * phase4b 先把报告详情与轻量导出读取入口固定下来，
 * 避免后续调用方继续直接绑定 `ReportService` 的实现细节。
 */
public interface ReportQueryFacade {

    ReportResponse getReport(Long taskId);

    byte[] exportMarkdown(Long taskId);

    byte[] exportHtml(Long taskId);
}
