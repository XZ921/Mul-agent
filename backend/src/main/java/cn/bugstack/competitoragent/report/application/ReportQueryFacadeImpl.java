package cn.bugstack.competitoragent.report.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.report.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * `ReportQueryFacade` 的最小默认实现。
 * <p>
 * 当前阶段先通过包装 `ReportService` 固定 report 读取边界，
 * 后续真实消费者迁移时只依赖 facade，不再继续扩散具体服务实现。
 */
@Service
@RequiredArgsConstructor
public class ReportQueryFacadeImpl implements ReportQueryFacade {

    private final ReportService reportService;

    @Override
    public ReportResponse getReport(Long taskId) {
        return reportService.getReport(taskId);
    }

    @Override
    public byte[] exportMarkdown(Long taskId) {
        return reportService.exportMarkdown(taskId);
    }

    @Override
    public byte[] exportHtml(Long taskId) {
        return reportService.exportHtml(taskId);
    }
}
