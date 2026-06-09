package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.ReportExportResponse;
import cn.bugstack.competitoragent.report.ExportPackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 正式交付中心控制器。
 * <p>
 * 当前阶段先提供最小正式导出记录读取接口，
 * 让报告页后续可以从稳定的“交付中心语义”读取数据，而不是继续直连一次性下载动作。
 */
@Tag(name = "Delivery", description = "正式交付中心接口")
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final ExportPackageService exportPackageService;

    @GetMapping("/task/{taskId}/exports")
    @Operation(summary = "查询任务正式导出记录列表")
    public ApiResponse<List<ReportExportResponse>> listTaskExports(
            @Parameter(description = "任务 ID", example = "42")
            @PathVariable Long taskId) {
        return ApiResponse.success(exportPackageService.listTaskExports(taskId));
    }
}
