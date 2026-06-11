package cn.bugstack.competitoragent.report.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.report.ReportService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportQueryFacadeImplTest {

    private static final String FACADE_IMPL_CLASS =
            "cn.bugstack.competitoragent.report.application.ReportQueryFacadeImpl";

    private final ReportService reportService = mock(ReportService.class);

    @Test
    void should_expose_report_read_through_facade() {
        ReportResponse response = ReportResponse.builder()
                .taskId(1L)
                .title("交付报告")
                .build();
        byte[] markdown = "# report".getBytes(StandardCharsets.UTF_8);
        byte[] html = "<html>report</html>".getBytes(StandardCharsets.UTF_8);
        when(reportService.getReport(1L)).thenReturn(response);
        when(reportService.exportMarkdown(1L)).thenReturn(markdown);
        when(reportService.exportHtml(1L)).thenReturn(html);

        Object facade = instantiateFacade();

        assertSame(response, invokeGetReport(facade, 1L));
        assertArrayEquals(markdown, invokeExportMarkdown(facade, 1L));
        assertArrayEquals(html, invokeExportHtml(facade, 1L));
        verify(reportService).getReport(1L);
        verify(reportService).exportMarkdown(1L);
        verify(reportService).exportHtml(1L);
    }

    private Object instantiateFacade() {
        try {
            Class<?> type = Class.forName(FACADE_IMPL_CLASS);
            Constructor<?> constructor = type.getDeclaredConstructor(ReportService.class);
            constructor.setAccessible(true);
            return constructor.newInstance(reportService);
        } catch (ReflectiveOperationException e) {
            fail("phase4b Task 1 要求存在 ReportQueryFacadeImpl，并通过 ReportService 包装稳定报告读取入口", e);
            return null;
        }
    }

    private ReportResponse invokeGetReport(Object facade, Long taskId) {
        try {
            Method method = facade.getClass().getMethod("getReport", Long.class);
            return (ReportResponse) method.invoke(facade, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase4b Task 1 缺少 getReport(Long) facade 入口", e);
            return null;
        }
    }

    private byte[] invokeExportMarkdown(Object facade, Long taskId) {
        try {
            Method method = facade.getClass().getMethod("exportMarkdown", Long.class);
            return (byte[]) method.invoke(facade, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase4b Task 1 缺少 exportMarkdown(Long) facade 入口", e);
            return new byte[0];
        }
    }

    private byte[] invokeExportHtml(Object facade, Long taskId) {
        try {
            Method method = facade.getClass().getMethod("exportHtml", Long.class);
            return (byte[]) method.invoke(facade, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase4b Task 1 缺少 exportHtml(Long) facade 入口", e);
            return new byte[0];
        }
    }
}
