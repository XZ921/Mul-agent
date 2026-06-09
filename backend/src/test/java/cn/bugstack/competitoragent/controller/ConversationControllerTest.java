package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.RerankClient;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 4.6 统一对话入口黑盒契约测试。
 * 这些用例只从 HTTP 入口观察行为，不假设前端已经存在。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "rocketmq.name-server=127.0.0.1:9876"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private TaskNodeRepository nodeRepository;

    @Autowired
    private RetrievalIndexRepository retrievalIndexRepository;

    @Autowired
    private RetrievalChunkRepository retrievalChunkRepository;

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

    /**
     * Task 4.6 的控制器黑盒测试只关心统一对话入口行为，
     * 不应该因为底层 LLM / 重排实现被 Embedding mock 顶掉而影响 Spring 上下文启动。
     */
    @MockBean
    private LlmClient llmClient;

    @MockBean
    private RerankClient rerankClient;

    @BeforeEach
    void setUp() {
        retrievalChunkRepository.deleteAll();
        retrievalIndexRepository.deleteAll();
        nodeRepository.deleteAll();
        taskRepository.deleteAll();
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding disabled in controller test"));
    }

    @Test
    void shouldExplainWhyTaskStoppedFromSingleConversationEntry() throws Exception {
        AnalysisTask task = taskRepository.save(AnalysisTask.builder()
                .taskName("飞书 AI 竞品分析")
                .subjectProduct("企业协作平台")
                .competitorNames("[\"飞书\",\"Notion\"]")
                .analysisDimensions("[\"AI 能力\",\"价格策略\"]")
                .sourceScope("[\"官网\",\"产品文档\"]")
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("存在等待人工处理的节点，请确认后继续")
                .build());

        nodeRepository.save(TaskNode.builder()
                .taskId(task.getId())
                .nodeName("rewrite_report")
                .displayName("报告改写")
                .agentType(AgentType.WRITER)
                .dependsOn("[\"quality_check\"]")
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .errorMessage("需要人工确认改写方向")
                .interventionReason("当前结论证据不足，系统暂停自动改写。")
                .executionOrder(3)
                .build());

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": %d,
                                  "pageType": "TASK_DETAIL",
                                  "message": "这个任务为什么停在这里了？"
                                }
                                """.formatted(task.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("EXPLAIN"))
                .andExpect(jsonPath("$.data.intentDecision.intentType").value("TASK_STATUS_EXPLANATION"))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("当前任务")))
                .andExpect(jsonPath("$.data.statusSummary").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.data.currentStage").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));
    }

    @Test
    void shouldCreateTaskFormDraftFromNaturalLanguageRequest() throws Exception {
        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageType": "TASK_CREATE",
                                  "message": "帮我做一个飞书和 Notion 的竞品分析，把重点改成 AI 能力和定价"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("TASK_FORM"))
                .andExpect(jsonPath("$.data.formDraft.taskName").value("飞书 vs Notion 竞品分析"))
                .andExpect(jsonPath("$.data.formDraft.competitorNames[0]").value("飞书"))
                .andExpect(jsonPath("$.data.formDraft.competitorNames[1]").value("Notion"))
                .andExpect(jsonPath("$.data.formDraft.analysisDimensions[0]").value("AI 能力"))
                .andExpect(jsonPath("$.data.formDraft.analysisDimensions[1]").value("价格策略"))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("草稿")));
    }

    @Test
    void shouldReturnActionPreviewInsteadOfExecutingHighRiskTaskCommand() throws Exception {
        AnalysisTask task = taskRepository.save(AnalysisTask.builder()
                .taskName("Notion AI 报告修订")
                .subjectProduct("知识管理平台")
                .competitorNames("[\"Notion AI\"]")
                .analysisDimensions("[\"AI 能力\",\"价格策略\"]")
                .sourceScope("[\"官网\",\"文档\"]")
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("报告改写阶段失败")
                .build());

        nodeRepository.saveAll(List.of(
                TaskNode.builder()
                        .taskId(task.getId())
                        .nodeName("quality_check")
                        .displayName("质量评审")
                        .agentType(AgentType.REVIEWER)
                        .dependsOn("[\"write_report\"]")
                        .status(TaskNodeStatus.SUCCESS)
                        .executionOrder(2)
                        .outputData("{\"passed\":false}")
                        .build(),
                TaskNode.builder()
                        .taskId(task.getId())
                        .nodeName("rewrite_report")
                        .displayName("报告改写")
                        .agentType(AgentType.WRITER)
                        .dependsOn("[\"quality_check\"]")
                        .status(TaskNodeStatus.FAILED)
                        .errorMessage("改写时提示词与证据不一致")
                        .executionOrder(3)
                        .build()
        ));

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": %d,
                                  "pageType": "TASK_DETAIL",
                                  "message": "从 rewrite_report 开始重跑"
                                }
                                """.formatted(task.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("TASK_ACTION"))
                .andExpect(jsonPath("$.data.intentDecision.intentType").value("RERUN_FROM_NODE"))
                .andExpect(jsonPath("$.data.taskActionPreview.actionType").value("RERUN_NODE"))
                .andExpect(jsonPath("$.data.taskActionPreview.targetNodeName").value("rewrite_report"))
                .andExpect(jsonPath("$.data.taskActionPreview.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.data.taskActionPreview.executable").value(false))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("预览")));
    }

    @Test
    void shouldKeepResearchModeInsideSafeEvidencePreviewBoundary() throws Exception {
        AnalysisTask task = taskRepository.save(AnalysisTask.builder()
                .taskName("Notion AI 定价补证")
                .subjectProduct("知识管理平台")
                .competitorNames("[\"Notion AI\"]")
                .analysisDimensions("[\"价格策略\"]")
                .sourceScope("[\"定价页\"]")
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("当前定价证据不足")
                .build());

        nodeRepository.save(TaskNode.builder()
                .taskId(task.getId())
                .nodeName("collect_sources_pricing")
                .displayName("定价页采集")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .status(TaskNodeStatus.SUCCESS)
                .executionOrder(0)
                .nodeConfig("""
                        {"competitorName":"Notion AI","sourceType":"PRICING","searchMode":"HYBRID"}
                        """)
                .outputData("""
                        {"taskRagContext":"检索查询：Notion AI pricing"}
                        """)
                .build());

        RetrievalIndex retrievalIndex = retrievalIndexRepository.save(RetrievalIndex.builder()
                .taskId(task.getId())
                .knowledgeDocumentId(11L)
                .competitorName("Notion AI")
                .evidenceId("E-PRICE-1")
                .documentKey("DOC-PRICE-1")
                .indexKey("IDX-PRICE-1")
                .indexScope("TASK")
                .sourceCategory("AI_DISCOVERED")
                .documentVersion(1)
                .chunkCount(1)
                .status("READY")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .build());

        retrievalChunkRepository.save(RetrievalChunk.builder()
                .taskId(task.getId())
                .knowledgeDocumentId(retrievalIndex.getKnowledgeDocumentId())
                .competitorName("Notion AI")
                .evidenceId("E-PRICE-1")
                .documentKey("DOC-PRICE-1")
                .chunkKey("DOC-PRICE-1#001")
                .chunkIndex(0)
                .startOffset(0)
                .endOffset(80)
                .sourceCategory("AI_DISCOVERED")
                .documentVersion(1)
                .content("Notion AI pricing page explains annual billing and enterprise quote workflow.")
                .snippet("Notion AI pricing page explains annual billing.")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .build());

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": %d,
                                  "pageType": "TASK_DETAIL",
                                  "message": "继续补搜 pricing 证据"
                                }
                                """.formatted(task.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("RESEARCH"))
                .andExpect(jsonPath("$.data.intentDecision.intentType").value("SUPPLEMENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.retrievalEvidences[0].evidenceId").value("E-PRICE-1"))
                .andExpect(jsonPath("$.data.sourceUrls[0]").value("https://www.notion.so/pricing"))
                .andExpect(jsonPath("$.data.taskActionPreview.actionType").value("SUPPLEMENT_EVIDENCE"))
                .andExpect(jsonPath("$.data.taskActionPreview.executable").value(false));
    }
}
