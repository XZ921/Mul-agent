package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.report.ExportPackageService;
import cn.bugstack.competitoragent.report.ReportExportRenderer;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 08 编排审计只读可见性贯通测试。
 * 该用例手工构造包含 LLM fallback/shadow/failure 的 rich typed batch，随后经过生产 TraceService -> outbox -> H2，
 * 用于验证持久化和所有只读消费方；它不证明 RuntimeDecisionService 能从真实 Provider 结果生成同一 rich batch。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                "rocketmq.enabled=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("phase5-integration")
class OrchestrationDecisionVisibilityIntegrationTest {

    private static final long TASK_ID = 801L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrchestrationTraceService orchestrationTraceService;

    @Autowired
    private TaskWorkflowEventRepository taskWorkflowEventRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private ReportExportRecordRepository reportExportRecordRepository;

    @Autowired
    private ExportPackageService exportPackageService;

    @MockBean
    private ModelGateway modelGateway;

    @MockBean
    private OrganizationQuotaPolicy organizationQuotaPolicy;

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

    @BeforeEach
    void setUp() {
        reportExportRecordRepository.deleteAll();
        taskWorkflowEventRepository.deleteAll();
        reportRepository.deleteAll();
        reportRepository.save(Report.builder()
                .taskId(TASK_ID)
                .title("Task 08 编排审计贯通报告")
                .content("# Task 08 Report")
                .summary("验证同一持久化周期在报告、导出与回放中的可见性。")
                .qualityScore(80)
                .qualityPassed(false)
                .evidenceCount(0)
                .build());
    }

    @Test
    void shouldExposeHandcraftedRichV2BatchAcrossReportExportAndReplay() throws Exception {
        TaskNode triggerNode = TaskNode.builder()
                .taskId(TASK_ID)
                .nodeName("quality_check_final")
                .planVersionId(31L)
                .branchKey("root/review")
                .build();

        // rich batch 的生产侧仍是 fixture；本测试的真实边界从 TraceService 开始，禁止把它表述为 Coordinator E2E。
        orchestrationTraceService.recordDecisionBatch(
                TASK_ID,
                triggerNode,
                OrchestrationDecisionAuditTestFixtures.fallbackBatch());

        List<TaskWorkflowEvent> events = taskWorkflowEventRepository.findAll().stream()
                .filter(event -> Long.valueOf(TASK_ID).equals(event.getTaskId()))
                .filter(event -> event.getEventType() == WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .toList();
        assertThat(events).hasSize(1);
        JsonNode persistedPayload = objectMapper.readTree(events.get(0).getPayload());
        assertEquals("ORCHESTRATION_TRACE_V2", persistedPayload.path("traceSchemaVersion").asText());
        assertEquals(2, persistedPayload.at("/audit/attempts").size());

        String reportJson = mockMvc.perform(get("/api/report/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orchestrationDecision.decisionId")
                        .value("od-801-rule-fallback"))
                .andExpect(jsonPath("$.data.orchestrationDecision.decisionOrigin")
                        .value("RULE_FALLBACK"))
                .andExpect(jsonPath("$.data.orchestrationDecision.runtimeStatus")
                        .value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.orchestrationDecisionAudit.mode")
                        .value("LLM_PRIMARY"))
                .andExpect(jsonPath("$.data.orchestrationDecisionAudit.llmFailure.type")
                        .value("PARSE_ERROR"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode report = objectMapper.readTree(reportJson).path("data");
        assertThat(report.path("sourceUrls").toString())
                .contains(OrchestrationDecisionAuditTestFixtures.TRUSTED_URL)
                .contains(OrchestrationDecisionAuditTestFixtures.CHECKPOINT_URL)
                .contains(OrchestrationDecisionAuditTestFixtures.SHADOW_URL)
                .doesNotContain(OrchestrationDecisionAuditTestFixtures.DISCARDED_URL);

        ReportExportRenderer.RenderedExportPackage markdown =
                exportPackageService.createExportPackage(TASK_ID, "MARKDOWN");
        ReportExportRenderer.RenderedExportPackage html =
                exportPackageService.createExportPackage(TASK_ID, "HTML");
        ReportExportRenderer.RenderedExportPackage json =
                exportPackageService.createExportPackage(TASK_ID, "JSON");
        assertExportText(markdown, "RULE_FALLBACK", "CONFIRMATION_REQUIRED", "PARSE_ERROR");
        assertExportText(html, "RULE_FALLBACK", "CONFIRMATION_REQUIRED", "PARSE_ERROR");
        JsonNode jsonExport = objectMapper.readTree(json.content());
        assertEquals("od-801-rule-fallback",
                jsonExport.at("/orchestrationDecision/decisionId").asText());
        assertEquals("PARSE_ERROR",
                jsonExport.at("/orchestrationDecisionAudit/llmFailure/type").asText());

        mockMvc.perform(get("/api/task/{taskId}/replay", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline[0].orchestrationDecision.decisionId")
                        .value("od-801-rule-fallback"))
                .andExpect(jsonPath("$.data.timeline[0].orchestrationDecisionAudit.mode")
                        .value("LLM_PRIMARY"))
                .andExpect(jsonPath("$.data.latestOrchestrationDecision.runtimeStatus")
                        .value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.latestOrchestrationDecisionAudit.llmFailure.type")
                        .value("PARSE_ERROR"))
                .andExpect(jsonPath("$.data.sourceUrls").isArray());

        verifyNoInteractions(modelGateway);
    }

    private void assertExportText(ReportExportRenderer.RenderedExportPackage exportPackage,
                                  String... expectedFacts) {
        String content = new String(exportPackage.content(), StandardCharsets.UTF_8);
        for (String expectedFact : expectedFacts) {
            assertThat(content).contains(expectedFact);
        }
        assertThat(content)
                .contains(OrchestrationDecisionAuditTestFixtures.TRUSTED_URL)
                .doesNotContain("runtimeCommand")
                .doesNotContain("DO_NOT_PERSIST");
    }
}
