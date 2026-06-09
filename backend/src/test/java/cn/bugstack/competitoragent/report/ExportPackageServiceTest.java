package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.ReportExportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.CollectorSearchAudit;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisSection;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReportDiagnosisInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SearchAuditOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.TaskRagAuditInfo;
import cn.bugstack.competitoragent.model.entity.ReportExportRecord;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.7.b 正式导出渲染黑盒测试。
 * <p>
 * 这个测试只覆盖当前子任务的完成标志：
 * 1. Markdown / HTML / JSON 证据包三类导出物都可以稳定生成；
 * 2. 每类导出物都显式携带证据引用与审计摘要；
 * 3. 渲染职责已经从单一方法拼接转为正式导出服务可调度的渲染链路。
 */
class ExportPackageServiceTest {

    private final ReportExportRecordRepository reportExportRecordRepository = mock(ReportExportRecordRepository.class);
    private final ReportService reportService = mock(ReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OrganizationQuotaPolicy organizationQuotaPolicy = mock(OrganizationQuotaPolicy.class);

    @Test
    void shouldRenderMarkdownHtmlAndJsonPackagesWithEvidenceAndAuditSummary() throws Exception {
        ReportResponse report = buildReportResponse();
        when(reportService.getReport(42L)).thenReturn(report);
        when(reportExportRecordRepository.findTopByTaskIdOrderByExportVersionDesc(42L)).thenReturn(Optional.empty());
        when(reportExportRecordRepository.save(any())).thenAnswer(invocation -> {
            ReportExportRecord record = invocation.getArgument(0);
            record.setId(100L);
            record.setCreatedAt(LocalDateTime.of(2026, 6, 8, 16, 0, 0));
            record.setUpdatedAt(LocalDateTime.of(2026, 6, 8, 16, 0, 0));
            return record;
        });

        Object service = instantiateExportPackageService();
        assertRendererContractPresent();

        Object markdownPackage = invokeCreateExportPackage(service, 42L, "MARKDOWN");
        assertRenderedTextPackage(
                markdownPackage,
                "text/markdown; charset=UTF-8",
                "competitor-analysis-report-v1.md",
                List.of(
                        "# Notion AI 企业级竞品分析",
                        "## 交付摘要",
                        "## 审计摘要",
                        "## 证据入口",
                        "https://docs.notion.so/product/ai"));

        Object htmlPackage = invokeCreateExportPackage(service, 42L, "HTML");
        assertRenderedTextPackage(
                htmlPackage,
                "text/html; charset=UTF-8",
                "competitor-analysis-report-v1.html",
                List.of(
                        "<!DOCTYPE html>",
                        "交付摘要",
                        "审计摘要",
                        "证据入口",
                        "https://docs.notion.so/product/ai"));

        Object jsonPackage = invokeCreateExportPackage(service, 42L, "JSON");
        String jsonBody = assertRenderedTextPackage(
                jsonPackage,
                "application/json; charset=UTF-8",
                "competitor-analysis-report-v1.json",
                List.of("\"deliverySummary\"", "\"auditSummary\"", "\"evidenceEntryPoint\"", "\"sourceUrls\""));

        JsonNode packageNode = objectMapper.readTree(jsonBody);
        assertEquals("JSON", packageNode.path("delivery").path("format").asText());
        assertEquals("BLOCKED", packageNode.path("deliverySummary").path("deliveryStatus").asText());
        assertEquals("Notion 安全文档", packageNode.path("evidenceEntryPoint").path("title").asText());
        assertEquals("采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个", packageNode.path("auditSummary").path("searchAuditSummary").asText());
        assertEquals("检索查询：Notion AI security", packageNode.path("auditSummary").path("taskRagAuditSummary").asText());
        assertEquals("https://docs.notion.so/product/ai", packageNode.path("evidences").get(0).path("url").asText());
        assertEquals("https://docs.notion.so/product/ai", packageNode.path("sourceUrls").get(0).asText());
        assertFalse(packageNode.path("auditSummary").has("taskRagAudits"));
    }

    @Test
    void shouldBlockExportWithStructuredGovernanceDecisionWhenQuotaExceeded() throws Exception {
        // Task 5.8.c 要求正式导出入口在组织级治理阻断时返回统一结构化结果，
        // 不能把导出受限混淆成普通渲染失败或报告缺失。
        when(reportService.getReport(42L)).thenReturn(buildReportResponse());
        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.deny(
                        "BLOCKED_QUOTA_EXCEEDED",
                        "当前组织导出额度已用尽，请稍后重试或等待释放",
                        "default-organization",
                        "EXPORT",
                        "EXPORT_PACKAGE",
                        1,
                        0,
                        null,
                        List.of("https://docs.notion.so/product/ai")
                ));

        Object service = instantiateExportPackageServiceWithOptionalGovernance();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> invokeCreateExportPackage(service, 42L, "MARKDOWN"));

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("EXPORT_PACKAGE", readAccessor(decision, "quotaKey"));
        assertEquals("当前组织导出额度已用尽，请稍后重试或等待释放", readAccessor(decision, "summary"));
    }

    /**
     * 当前子任务要求已有正式渲染器契约，而不是继续把不同格式混在一个方法里硬编码。
     */
    private void assertRendererContractPresent() throws Exception {
        try {
            Class<?> rendererClass = Class.forName("cn.bugstack.competitoragent.report.ReportExportRenderer");
            Method supportsMethod = rendererClass.getDeclaredMethod("supports", String.class);
            Method renderMethod = rendererClass.getDeclaredMethod(
                    "render",
                    ReportResponse.class,
                    ReportExportResponse.class,
                    ObjectMapper.class
            );
            assertEquals(boolean.class, supportsMethod.getReturnType());
            assertEquals("RenderedExportPackage", renderMethod.getReturnType().getSimpleName());
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            fail("应存在正式导出渲染器契约，但当前尚未实现: " + exception.getMessage());
        }
    }

    /**
     * 这里通过反射构建服务，确保 Red 阶段看到的是“能力未实现”的失败信息，
     * 而不是编译阶段直接中断。
     */
    private Object instantiateExportPackageService() throws Exception {
        try {
            Constructor<ExportPackageService> constructor = ExportPackageService.class.getDeclaredConstructor(
                    ReportExportRecordRepository.class,
                    ReportService.class,
                    ObjectMapper.class
            );
            return constructor.newInstance(reportExportRecordRepository, reportService, objectMapper);
        } catch (NoSuchMethodException exception) {
            fail("ExportPackageService 应提供正式导出渲染所需构造器: " + exception.getMessage());
            return null;
        }
    }

    private Object instantiateExportPackageServiceWithOptionalGovernance() throws Exception {
        try {
            Constructor<ExportPackageService> constructor = ExportPackageService.class.getDeclaredConstructor(
                    ReportExportRecordRepository.class,
                    ReportService.class,
                    ObjectMapper.class,
                    OrganizationQuotaPolicy.class
            );
            return constructor.newInstance(
                    reportExportRecordRepository,
                    reportService,
                    objectMapper,
                    organizationQuotaPolicy
            );
        } catch (NoSuchMethodException ignored) {
            return instantiateExportPackageService();
        }
    }

    /**
     * 当前任务的黑盒入口就是“按格式生成正式导出包”，
     * 因此测试只调用服务入口，不直接关心内部由哪一个具体实现类负责渲染。
     */
    private Object invokeCreateExportPackage(Object service, Long taskId, String format) throws Exception {
        try {
            Method method = service.getClass().getDeclaredMethod("createExportPackage", Long.class, String.class);
            return method.invoke(service, taskId, format);
        } catch (InvocationTargetException exception) {
            /* 黑盒断言要对准真实业务异常，而不是被反射包装后的外层异常。 */
            Throwable targetException = exception.getTargetException();
            if (targetException instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (targetException instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(targetException);
        } catch (NoSuchMethodException exception) {
            fail("ExportPackageService 应提供 createExportPackage(Long, String) 正式导出入口");
            return null;
        }
    }

    /**
     * Markdown / HTML / JSON 三类导出物最终都要落成可下载文本，
     * 因此这里统一校验内容类型、文件名、正式记录和关键业务片段。
     */
    private String assertRenderedTextPackage(Object exportPackage,
                                             String expectedContentType,
                                             String expectedFileName,
                                             List<String> expectedSnippets) throws Exception {
        String contentType = (String) readAccessor(exportPackage, "contentType");
        String fileName = (String) readAccessor(exportPackage, "fileName");
        byte[] content = (byte[]) readAccessor(exportPackage, "content");
        Object recordObject = readAccessor(exportPackage, "record");

        assertEquals(expectedContentType, contentType);
        assertEquals(expectedFileName, fileName);
        assertInstanceOf(ReportExportResponse.class, recordObject);

        ReportExportResponse record = (ReportExportResponse) recordObject;
        assertEquals(1, record.getExportVersion());
        assertTrue(record.getSourceUrls().contains("https://docs.notion.so/product/ai"));
        assertTrue(record.getExportSummary().contains("阻塞问题"));

        String body = new String(content, StandardCharsets.UTF_8);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), content);
        for (String snippet : expectedSnippets) {
            assertTrue(body.contains(snippet), "导出内容应包含片段: " + snippet);
        }
        return body;
    }

    /**
     * 渲染结果对象可能是普通类，也可能是 record，
     * 这里统一优先按 accessor / getter 读取，避免把测试绑死到某一种 Java 语法形式。
     */
    private Object readAccessor(Object target, String name) throws Exception {
        try {
            Method accessor = target.getClass().getDeclaredMethod(name);
            return accessor.invoke(target);
        } catch (NoSuchMethodException ignored) {
            Method getter = target.getClass().getDeclaredMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            return getter.invoke(target);
        }
    }

    /**
     * 只组装当前任务验证渲染输出所需的最小报告载荷，
     * 避免把 5.7.c 的字段收口要求提前塞进本轮测试。
     */
    private ReportResponse buildReportResponse() {
        return ReportResponse.builder()
                .id(1L)
                .taskId(42L)
                .title("Notion AI 企业级竞品分析")
                .content("# Notion AI 企业级竞品分析\n\n结论来源：官方产品文档。")
                .summary("重点结论已绑定官方资料来源。")
                .qualityScore(82)
                .qualityPassed(false)
                .evidenceCount(1)
                .deliverySummary(ReportResponse.DeliverySummaryInfo.builder()
                        .readyForDelivery(false)
                        .deliveryStatus("BLOCKED")
                        .summary("当前报告暂不可正式交付，但关键证据入口已经明确。")
                        .primaryIssue("缺少最终交付前复核确认")
                        .recommendedAction("先核对官网产品页，再决定是否继续终审。")
                        .blockerCount(1)
                        .evidenceGapCount(0)
                        .sourceUrls(List.of("https://docs.notion.so/product/ai"))
                        .build())
                .evidenceEntryPoint(ReportResponse.EvidenceEntryPointInfo.builder()
                        .summary("可优先核对证据：Notion 安全文档")
                        .sectionTitle("报告结论")
                        .evidenceId("E-001")
                        .title("Notion 安全文档")
                        .url("https://docs.notion.so/product/ai")
                        .sourceType("DOCS")
                        .sourceUrls(List.of("https://docs.notion.so/product/ai"))
                        .build())
                .auditSummary(ReportResponse.AuditSummaryInfo.builder()
                        .summary("采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个")
                        .searchAuditSummary("采集节点 1 个，已记录轨迹 1 个，最终选中候选 1 个")
                        .taskRagAuditSummary("检索查询：Notion AI security")
                        .sourceUrls(List.of("https://docs.notion.so/product/ai"))
                        .build())
                .evidences(List.of(new EvidenceInfo(
                        "E-001",
                        "Notion AI 官方产品页",
                        "https://docs.notion.so/product/ai",
                        "产品页描述了 AI 功能与企业交付能力。",
                        "Notion AI",
                        LocalDateTime.of(2026, 6, 8, 15, 0, 0),
                        "DOCS",
                        "SEARCH",
                        "docs.notion.so",
                        "命中官方产品说明页",
                        "2026-06-01",
                        0.96,
                        true,
                        "官方域名已验证",
                        "Notion AI product ai",
                        "bing",
                        1,
                        "trace-001",
                        "官方来源优先",
                        "SELECTED",
                        List.of("docs", "product"),
                        java.util.Map.of("collector", "mock"))))
                .searchAuditOverview(SearchAuditOverview.builder()
                        .collectorNodeCount(1)
                        .traceRecordedCount(1)
                        .degradedCount(0)
                        .selectedCandidateCount(1)
                        .collectors(List.of(CollectorSearchAudit.builder()
                                .nodeName("collect_sources_notion_docs")
                                .nodeStatus(TaskNodeStatus.SUCCESS)
                                .competitorName("Notion AI")
                                .sourceType("DOCS")
                                .traceRecorded(true)
                                .auditMessage("已通过官方文档链路补齐检索轨迹")
                                .selectedUrls(List.of("https://docs.notion.so/product/ai"))
                                .build()))
                        .build())
                .taskRagAudits(List.of(TaskRagAuditInfo.builder()
                        .nodeName("analyze_competitors")
                        .agentType("ANALYZER")
                        .taskRagContext("检索说明：优先使用官方产品文档作为结论依据。")
                        .build()))
                .reportDiagnosis(ReportDiagnosisInfo.builder()
                        .diagnosisCount(1)
                        .blockerCount(1)
                        .evidenceGapCount(0)
                        .sourceUrls(List.of("https://docs.notion.so/product/ai"))
                        .sections(List.of(DiagnosisSection.builder()
                                .section("报告结论")
                                .evidenceInsufficient(false)
                                .sourceUrls(List.of("https://docs.notion.so/product/ai"))
                                .repairSuggestions(List.of("继续保持官方证据优先"))
                                .diagnoses(List.of())
                                .build()))
                        .build())
                .build();
    }
}
