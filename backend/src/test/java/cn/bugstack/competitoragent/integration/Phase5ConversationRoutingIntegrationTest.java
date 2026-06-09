package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.RerankClient;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5.9.b 治理链路后端端到端集成测试。
 * <p>
 * 这条回归只证明一条最小治理闭环：
 * 1. 组织级阻断能在统一对话入口返回用户可读说明；
 * 2. 运维治理摘要能解释等待释放原因；
 * 3. 阻断释放后，正式恢复入口可重新接受任务。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "rocketmq.name-server=127.0.0.1:9876"
)
@AutoConfigureMockMvc
@ActiveProfiles("phase5-integration")
class Phase5ConversationRoutingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    private TaskNodeRepository taskNodeRepository;

    @Autowired
    private OrganizationQuotaSnapshotRepository organizationQuotaSnapshotRepository;

    @MockBean
    private Playwright playwright;

    @MockBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @MockBean
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @MockBean
    private TaskExecutionLockService taskExecutionLockService;

    @MockBean
    private AgentContextAssembler agentContextAssembler;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private RerankClient rerankClient;

    @BeforeEach
    void setUp() {
        taskNodeRepository.deleteAll();
        analysisTaskRepository.deleteAll();
        organizationQuotaSnapshotRepository.deleteAll();
        when(embeddingClient.embed(anyString()))
                .thenThrow(new IllegalStateException("embedding disabled in phase5 governance integration test"));
    }

    @Test
    void shouldExplainGovernanceBlockAndAllowResumeAfterQuotaReleased() throws Exception {
        AnalysisTask task = analysisTaskRepository.save(AnalysisTask.builder()
                .taskName("Phase 5 治理阻断恢复")
                .subjectProduct("企业研究平台")
                .competitorNames("[\"Notion AI\"]")
                .analysisDimensions("[\"治理提示\"]")
                .sourceScope("[\"组织知识\"]")
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("组织级导出配额占满")
                .build());

        taskNodeRepository.save(TaskNode.builder()
                .taskId(task.getId())
                .nodeName("export_report")
                .displayName("正式导出")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .errorMessage("组织级导出配额占满")
                .interventionReason("等待配额释放后恢复")
                .executionOrder(3)
                .build());

        OrganizationQuotaSnapshot snapshot = organizationQuotaSnapshotRepository.save(OrganizationQuotaSnapshot.builder()
                .organizationKey(GovernanceDefaults.DEFAULT_ORGANIZATION_KEY)
                .quotaScope(GovernanceDefaults.EXPORT_SCOPE)
                .quotaKey(GovernanceDefaults.EXPORT_PACKAGE_KEY)
                .quotaUnit("COUNT")
                .limitValue(1)
                .usedValue(0)
                .reservedValue(1)
                .snapshotStatus("ACTIVE")
                .sourceUrls(List.of("https://ops.example.com/quota/export"))
                .snapshotAt(LocalDateTime.of(2026, 6, 8, 20, 10, 0))
                .build());

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": %d,
                                  "pageType": "TASK_DETAIL",
                                  "message": "为什么这个任务现在不能继续导出？"
                                }
                                """.formatted(task.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("EXPLAIN"))
                .andExpect(jsonPath("$.data.statusSummary").value(org.hamcrest.Matchers.containsString("等待人工处理")))
                .andExpect(jsonPath("$.data.currentStage").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));

        mockMvc.perform(get("/api/governance/runtime-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.quotaSummaries[0].status").value("WAITING_RELEASE"))
                .andExpect(jsonPath("$.data.quotaSummaries[0].retryAdvice").value(org.hamcrest.Matchers.containsString("释放")))
                .andExpect(jsonPath("$.data.quotaSummaries[0].sourceUrls[0]").value("https://ops.example.com/quota/export"));

        snapshot.setReservedValue(0);
        organizationQuotaSnapshotRepository.save(snapshot);

        mockMvc.perform(post("/api/task/{id}/resume", task.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Task resumed"));
    }
}
