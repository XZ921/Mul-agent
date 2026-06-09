package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.governance.GovernanceRuntimeSummaryService;
import cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease;
import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.repository.ConnectorRuntimeLeaseRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GovernanceControllerTest {

    private final OrganizationQuotaSnapshotRepository quotaSnapshotRepository = mock(OrganizationQuotaSnapshotRepository.class);
    private final ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository = mock(ConnectorRuntimeLeaseRepository.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GovernanceRuntimeSummaryService summaryService =
                new GovernanceRuntimeSummaryService(quotaSnapshotRepository, connectorRuntimeLeaseRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(new GovernanceController(summaryService)).build();
    }

    @Test
    void shouldReturnOperatorRuntimeSummaryWithoutLeakingInternalKeys() throws Exception {
        // 使用运行期相对时间构造租约，确保“预计释放”提示不会因为当前测试时间漂移而失真。
        LocalDateTime now = LocalDateTime.now();
        // 准备一个已经耗尽的任务并发快照，覆盖用户需要理解“为什么等待释放”的完成标志。
        when(quotaSnapshotRepository.findAll()).thenReturn(List.of(OrganizationQuotaSnapshot.builder()
                .organizationKey("default-org")
                .quotaScope("TASK")
                .quotaKey("TASK_CONCURRENCY")
                .limitValue(3)
                .usedValue(1)
                .reservedValue(2)
                .quotaUnit("COUNT")
                .snapshotStatus("ACTIVE")
                .sourceUrls(List.of("https://ops.example.com/quota/task-concurrency"))
                .snapshotAt(now)
                .build()));
        // 准备一个持有中的连接器租约，覆盖操作者查询连接器忙碌摘要且不泄露 leaseToken 的完成标志。
        when(connectorRuntimeLeaseRepository.findAll()).thenReturn(List.of(ConnectorRuntimeLease.builder()
                .organizationKey("default-org")
                .connectorKey("notion-pages")
                .runtimeSlot("DEFAULT")
                .leaseOwner("knowledge-sync-42")
                .leaseStatus("HELD")
                .leaseToken("lease-default-org-notion-pages-DEFAULT-secret")
                .sourceUrls(List.of("https://ops.example.com/connectors/notion"))
                .acquiredAt(now.minusMinutes(8))
                .expiresAt(now.plusMinutes(22))
                .build()));

        mockMvc.perform(get("/api/governance/runtime-summary")
                        .param("organizationKey", "default-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationKey").value("default-org"))
                .andExpect(jsonPath("$.data.summary").value("当前有 1 个配额项需等待释放，1 个连接器正在运行。"))
                .andExpect(jsonPath("$.data.quotaSummaries[0].displayName").value("任务并发"))
                .andExpect(jsonPath("$.data.quotaSummaries[0].status").value("WAITING_RELEASE"))
                .andExpect(jsonPath("$.data.quotaSummaries[0].retryAdvice").value("等待正在运行的任务完成后再重试，或暂停低优先级任务释放并发槽位。"))
                .andExpect(jsonPath("$.data.quotaSummaries[0].quotaKey").doesNotExist())
                .andExpect(jsonPath("$.data.quotaSummaries[0].sourceUrls[0]").value("https://ops.example.com/quota/task-concurrency"))
                .andExpect(jsonPath("$.data.connectorSummaries[0].displayName").value("Notion 页面"))
                .andExpect(jsonPath("$.data.connectorSummaries[0].status").value("BUSY"))
                .andExpect(jsonPath("$.data.connectorSummaries[0].summary").value(allOf(
                        containsString("Notion 页面正在同步，预计 "),
                        containsString("分钟内释放。")
                )))
                .andExpect(jsonPath("$.data.connectorSummaries[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.connectorSummaries[0].sourceUrls[0]").value("https://ops.example.com/connectors/notion"));
    }
}
