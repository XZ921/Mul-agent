package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDomainRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.report.ExportPackageService;
import cn.bugstack.competitoragent.report.ReportExportRenderer;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5.9.b 主链路后端端到端集成测试。
 * <p>
 * 这条回归只锁定一条最小主链路：
 * 组织知识接入 -> 回放入口可见 -> 报告详情可追溯 -> 正式导出包可生成。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "rocketmq.name-server=127.0.0.1:9876"
)
@AutoConfigureMockMvc
@ActiveProfiles("phase5-integration")
class Phase5EnterpriseDeliveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExportPackageService exportPackageService;

    @Autowired
    private KnowledgeDomainRepository knowledgeDomainRepository;

    @Autowired
    private EvidenceSourceRepository evidenceSourceRepository;

    @Autowired
    private CompetitorKnowledgeRepository competitorKnowledgeRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private TaskNodeRepository taskNodeRepository;

    @Autowired
    private TaskPlanRepository taskPlanRepository;

    @Autowired
    private TaskWorkflowEventRepository taskWorkflowEventRepository;

    @Autowired
    private MemorySnapshotRepository memorySnapshotRepository;

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
        taskNodeRepository.deleteAll();
        taskWorkflowEventRepository.deleteAll();
        taskPlanRepository.deleteAll();
        memorySnapshotRepository.deleteAll();
        reportRepository.deleteAll();
        competitorKnowledgeRepository.deleteAll();
        evidenceSourceRepository.deleteAll();
        knowledgeDomainRepository.deleteAll();
    }

    @Test
    void shouldVerifyEnterpriseDeliveryChainFromKnowledgeIngestionToReplayAndExport() throws Exception {
        knowledgeDomainRepository.save(KnowledgeDomain.builder()
                .domainKey("org-product-docs")
                .domainName("组织产品资料")
                .description("Phase 5 主链路测试知识域")
                .domainType("ORGANIZATION")
                .ownerScope("ORGANIZATION")
                .accessScope("TEAM_SHARED")
                .defaultLifecycle("ACTIVE")
                .defaultTrustLevel("CURATED")
                .allowedSourceCategories(List.of("UPLOADED_DOCUMENTS"))
                .status("ACTIVE")
                .build());

        mockMvc.perform(post("/api/knowledge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainKey": "org-product-docs",
                                  "sourceCategory": "UPLOADED_DOCUMENTS",
                                  "sourceType": "PDF",
                                  "title": "Phase 5 企业交付资料",
                                  "url": "https://docs.example.com/phase5/launch-guide.pdf",
                                  "sourceUrls": ["https://docs.example.com/phase5/launch-guide.pdf"],
                                  "contentText": "这份资料用于验证组织资料接入、正式回放和正式导出的主链路。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sourceUrls[0]").value("https://docs.example.com/phase5/launch-guide.pdf"));

        long taskId = 9527L;
        seedEnterpriseDeliveryTaskArtifacts(taskId);

        mockMvc.perform(get("/api/task/{taskId}/replay", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("NODE_COMPLETED"))
                .andExpect(jsonPath("$.data.sourceUrls[0]").value("https://docs.example.com/phase5/launch-guide.pdf"));

        String reportResponse = mockMvc.perform(get("/api/report/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deliverySummary.deliveryStatus").value("READY"))
                .andExpect(jsonPath("$.data.evidenceEntryPoint.sourceUrls[0]").value("https://docs.example.com/phase5/launch-guide.pdf"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode reportNode = objectMapper.readTree(reportResponse).path("data");
        assertEquals("https://docs.example.com/phase5/launch-guide.pdf",
                reportNode.path("auditSummary").path("sourceUrls").get(0).asText());

        ReportExportRenderer.RenderedExportPackage exportPackage =
                exportPackageService.createExportPackage(taskId, "MARKDOWN");
        assertNotNull(exportPackage, "主链路必须能生成正式导出包。");
        assertEquals("MARKDOWN", exportPackage.record().getExportFormat());
        assertEquals("https://docs.example.com/phase5/launch-guide.pdf",
                exportPackage.record().getSourceUrls().get(0));

        mockMvc.perform(get("/api/delivery/task/{taskId}/exports", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].exportFormat").value("MARKDOWN"))
                .andExpect(jsonPath("$.data[0].sourceUrls[0]").value("https://docs.example.com/phase5/launch-guide.pdf"));
    }

    /**
     * 主链路测试只补最小正式对象，避免把 Task 5.9.b 演变成新的领域实现任务。
     */
    private void seedEnterpriseDeliveryTaskArtifacts(Long taskId) {
        reportRepository.save(Report.builder()
                .taskId(taskId)
                .title("Phase 5 企业交付最小闭环")
                .content("# Phase 5 企业交付最小闭环\n\n组织资料、记忆复用与正式导出已具备统一入口。")
                .summary("主链路已打通")
                .qualityScore(95)
                .qualityPassed(true)
                .qualityIssues("[]")
                .evidenceCount(1)
                .build());

        evidenceSourceRepository.save(EvidenceSource.builder()
                .taskId(taskId)
                .competitorName("Notion AI")
                .evidenceId("E-PHASE5-001")
                .title("Phase 5 Launch Guide")
                .url("https://docs.example.com/phase5/launch-guide.pdf")
                .contentSnippet("组织知识已经进入主链路。")
                .fullContent("组织知识已经进入主链路，并将被正式回放与交付链路复用。")
                .sourceType("DOCS")
                .build());

        competitorKnowledgeRepository.save(CompetitorKnowledge.builder()
                .taskId(taskId)
                .competitorName("Notion AI")
                .officialUrl("https://www.notion.so/product/ai")
                .summary("组织资料和任务证据已经共同进入知识层。")
                .memoryLayer("LONG_TERM")
                .positioning("企业级知识协作")
                .targetUsers("[\"企业运营\"]")
                .pricing("{\"plan\":\"enterprise\"}")
                .sourceUrls("[\"https://docs.example.com/phase5/launch-guide.pdf\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status": "TRACEABLE"},
                          "positioning": {"status": "TRACEABLE"},
                          "targetUsers": {"status": "TRACEABLE"},
                          "coreFeatures": {"status": "TRACEABLE"},
                          "pricing": {"status": "TRACEABLE"},
                          "strengths": {"status": "TRACEABLE"},
                          "weaknesses": {"status": "TRACEABLE"}
                        }
                        """)
                .versionSource("TASK_RAG@PLAN-1:collect_sources")
                .invalidationScope("MANUAL_REVIEW")
                .invalidationReason("NOT_EVALUATED")
                .build());

        TaskPlan activePlan = taskPlanRepository.save(TaskPlan.builder()
                .taskId(taskId)
                .planVersion(1)
                .branchKey("root/phase5")
                .planType("MAINLINE")
                .active(true)
                .planSnapshot("{\"nodes\":[\"collect_sources\",\"export_report\"]}")
                .createdAt(LocalDateTime.of(2026, 6, 8, 20, 1, 0))
                .build());

        taskWorkflowEventRepository.save(TaskWorkflowEvent.builder()
                .eventId("evt-phase5-main-1")
                .taskId(taskId)
                .nodeName("collect_sources")
                .planVersionId(activePlan.getId())
                .branchKey(activePlan.getBranchKey())
                .eventType(cn.bugstack.competitoragent.workflow.event.WorkflowEventType.NODE_COMPLETED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED)
                .topic("task.phase5")
                .tag("node_completed")
                .payload("{\"summary\":\"组织资料已接入并参与召回\"}")
                .sourceUrls("[\"https://docs.example.com/phase5/launch-guide.pdf\"]")
                .createdAt(LocalDateTime.of(2026, 6, 8, 20, 2, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 8, 20, 2, 0))
                .build());

        /**
         * 报告审计摘要会直接读取采集节点输出中的搜索轨迹与 Task RAG 摘要，
         * 因此这里补最小成功节点，确保主链路断言覆盖真实聚合入口。
         */
        taskNodeRepository.save(TaskNode.builder()
                .taskId(taskId)
                .nodeName("collect_sources")
                .displayName("组织资料接入")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .executionOrder(1)
                .outputData("""
                        {
                          "competitor": "Notion AI",
                          "sourceType": "DOCS",
                          "taskRagContext": "组织资料已完成入库，后续可直接复用至回放与交付。",
                          "searchExecutionTrace": {
                            "supplementMethod": "KNOWLEDGE_REPLAY",
                            "resumedFromCheckpoint": false,
                            "degraded": false,
                            "providerFallbackUsed": false,
                            "plannedCandidateCount": 1,
                            "verifiedCandidateCount": 1,
                            "supplementedCandidateCount": 0,
                            "selectedCandidateCount": 1,
                            "selectedUrls": ["https://docs.example.com/phase5/launch-guide.pdf"]
                          }
                        }
                        """)
                .build());

        memorySnapshotRepository.save(MemorySnapshot.builder()
                .taskId(taskId)
                .planVersionId(activePlan.getId())
                .branchKey(activePlan.getBranchKey())
                .nodeName("collect_sources")
                .snapshotType("TASK_RAG")
                .memoryLayer("SHORT_TERM")
                .summary("主链路已完成记忆复用")
                .gapSummary("无")
                .sourceUrls(List.of("https://docs.example.com/phase5/launch-guide.pdf"))
                .issueFlags(List.of())
                .versionSource("PLAN_1")
                .invalidationScope("NONE")
                .invalidationReason("NOT_REQUIRED")
                .contextPayload("{\"summary\":\"主链路回放快照\"}")
                .createdAt(LocalDateTime.of(2026, 6, 8, 20, 3, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 8, 20, 3, 0))
                .build());
    }
}
