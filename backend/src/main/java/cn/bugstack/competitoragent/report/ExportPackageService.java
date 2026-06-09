package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.ReportExportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.ReportExportRecord;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 正式导出服务。
 * <p>
 * Task 5.7.b 在 5.7.a 的记录骨架基础上，继续承担两件事：
 * 1. 为每次正式导出分配版本号、摘要和 sourceUrls；
 * 2. 按格式调度独立渲染器输出 Markdown / HTML / JSON 证据包。
 */
@Service
public class ExportPackageService {

    private final ReportExportRecordRepository reportExportRecordRepository;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;
    private final List<ReportExportRenderer> renderers;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;

    public ExportPackageService(ReportExportRecordRepository reportExportRecordRepository,
                                ReportService reportService,
                                ObjectMapper objectMapper) {
        this(reportExportRecordRepository, reportService, objectMapper, null);
    }

    /**
     * 正式导出入口在保留原有渲染职责的同时，额外接入组织级治理判断。
     */
    @Autowired
    public ExportPackageService(ReportExportRecordRepository reportExportRecordRepository,
                                ReportService reportService,
                                ObjectMapper objectMapper,
                                OrganizationQuotaPolicy organizationQuotaPolicy) {
        this.reportExportRecordRepository = reportExportRecordRepository;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
        this.organizationQuotaPolicy = organizationQuotaPolicy;
        this.renderers = List.of(
                new MarkdownReportExportRenderer(),
                new HtmlReportExportRenderer(),
                new JsonEvidencePackageExportRenderer()
        );
    }

    /**
     * 交付中心主路径先消费稳定的正式导出记录列表，
     * 而不是直接依赖页面下载按钮的瞬时行为。
     */
    public List<ReportExportResponse> listTaskExports(Long taskId) {
        return reportExportRecordRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 正式导出入口先完成三件事：
     * 1. 基于当前报告聚合数据生成正式记录；
     * 2. 为导出动作分配版本号和统一摘要；
     * 3. 交给对应格式的渲染器输出最终文件内容。
     */
    public ReportExportRenderer.RenderedExportPackage createExportPackage(Long taskId, String exportFormat) {
        String normalizedFormat = normalizeFormat(exportFormat);
        ReportResponse report = reportService.getReport(taskId);
        ensureExportAllowed(report);
        ReportExportResponse record = createExportRecord(
                taskId,
                normalizedFormat,
                ReportExportRenderSupport.buildExportSummary(report),
                ReportExportRenderSupport.collectSourceUrls(report)
        );
        ReportExportRenderer renderer = renderers.stream()
                .filter(candidate -> candidate.supports(normalizedFormat))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.REPORT_EXPORT_FAILED,
                        "不支持的正式导出格式: " + normalizedFormat));
        return renderer.render(report, record, objectMapper);
    }

    /**
     * 正式导出会占用组织级交付额度，因此在生成正式记录前先完成统一治理判定。
     * 一旦额度不足，就直接返回结构化阻断结果，不再继续保存导出记录。
     */
    private void ensureExportAllowed(ReportResponse report) {
        if (organizationQuotaPolicy == null) {
            return;
        }
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.EXPORT_SCOPE,
                GovernanceDefaults.EXPORT_PACKAGE_KEY,
                1,
                ReportExportRenderSupport.collectSourceUrls(report)
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
    }

    /**
     * 为正式导出落正式记录。
     * 当前阶段先把版本号、摘要、格式和 sourceUrls 固化下来，
     * 后续任务再继续补充交付摘要消费面和更细粒度的审计引用。
     */
    public ReportExportResponse createExportRecord(Long taskId,
                                                   String exportFormat,
                                                   String exportSummary,
                                                   List<String> sourceUrls) {
        int nextVersion = reportExportRecordRepository.findTopByTaskIdOrderByExportVersionDesc(taskId)
                .map(record -> record.getExportVersion() + 1)
                .orElse(1);
        List<String> normalizedSourceUrls = normalizeSourceUrls(sourceUrls);

        ReportExportRecord savedRecord = reportExportRecordRepository.save(ReportExportRecord.builder()
                .taskId(taskId)
                .exportVersion(nextVersion)
                .exportFormat(normalizeFormat(exportFormat))
                .exportStatus("REGISTERED")
                .exportSummary(exportSummary == null ? "" : exportSummary)
                .sourceUrls(normalizedSourceUrls)
                .build());
        return toResponse(savedRecord);
    }

    /**
     * 统一在服务层做响应映射，避免控制器直接接触持久化对象，
     * 为后续补充审计摘要、下载链接或交付状态摘要保留单一扩展点。
     */
    private ReportExportResponse toResponse(ReportExportRecord record) {
        return ReportExportResponse.builder()
                .id(record.getId())
                .taskId(record.getTaskId())
                .exportVersion(record.getExportVersion())
                .exportFormat(record.getExportFormat())
                .exportStatus(record.getExportStatus())
                .exportSummary(record.getExportSummary())
                .sourceUrls(record.getSourceUrls() == null ? List.of() : List.copyOf(record.getSourceUrls()))
                .createdAt(record.getCreatedAt())
                .build();
    }

    /**
     * 导出格式统一在服务边界归一化，避免大小写混杂导致渲染器命中失败。
     */
    private String normalizeFormat(String exportFormat) {
        if (exportFormat == null || exportFormat.isBlank()) {
            return "MARKDOWN";
        }
        return exportFormat.trim().toUpperCase();
    }

    /**
     * 正式导出记录里的 sourceUrls 要保持可追溯且去重，
     * 因此这里先在服务层做最小归一化，避免把空值直接写入导出历史。
     */
    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sourceUrl : sourceUrls == null ? List.<String>of() : sourceUrls) {
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                normalized.add(sourceUrl.trim());
            }
        }
        return new ArrayList<>(normalized);
    }
}
