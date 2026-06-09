package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.governance.GovernanceRuntimeSummaryService;
import cn.bugstack.competitoragent.model.dto.GovernanceRuntimeSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织级治理查询控制器。
 * <p>
 * Task 5.8.d 只补齐最小运维查询入口：返回当前组织的配额占位和连接器运行摘要，
 * 不扩展成完整治理控制台或计费权限系统。
 */
@Tag(name = "Governance", description = "组织级治理运行状态查询")
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final GovernanceRuntimeSummaryService governanceRuntimeSummaryService;

    public GovernanceController(GovernanceRuntimeSummaryService governanceRuntimeSummaryService) {
        this.governanceRuntimeSummaryService = governanceRuntimeSummaryService;
    }

    @GetMapping("/runtime-summary")
    @Operation(summary = "Get organization governance runtime summary")
    public ApiResponse<GovernanceRuntimeSummaryResponse> runtimeSummary(@RequestParam(required = false) String organizationKey) {
        return ApiResponse.success(governanceRuntimeSummaryService.summarize(organizationKey));
    }
}
