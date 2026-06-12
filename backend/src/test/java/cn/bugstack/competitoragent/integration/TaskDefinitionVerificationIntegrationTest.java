package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2.1.1 计划尾部 Verification 的最小闭环回归。
 * <p>
 * 这条集成 smoke test 同时覆盖四个关键点：
 * 1. preview 返回正式的 TASK_PLAN_PREVIEW_V1 契约；
 * 2. create 落库后写入 currentPlanVersionId/currentPlanVersion，并生成正式 TaskPlan 快照；
 * 3. rerun / resume 只能沿用当前计划版本，不能重建节点中的正式计划语义；
 * 4. 任务节点接口显式返回 TASK_NODE_RUNTIME_V1。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskDefinitionVerificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private TaskNodeRepository nodeRepository;

    @Autowired
    private TaskPlanRepository taskPlanRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SourceDiscoveryService sourceDiscoveryService;

    @MockBean
    private PromptTemplateService promptTemplateService;

    @MockBean
    private Playwright playwright;

    @MockBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @MockBean
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @MockBean
    private TaskExecutionLockService taskExecutionLockService;

    @MockBean
    private AnalysisTaskRunner analysisTaskRunner;

    @MockBean
    private WorkflowEventOutboxService workflowEventOutboxService;

    @BeforeEach
    void setUp() {
        nodeRepository.deleteAll();
        taskPlanRepository.deleteAll();
        taskRepository.deleteAll();

        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://www.notion.so/product/ai")
                .title("Notion AI")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .domain("www.notion.so")
                .verified(true)
                .build();
        SourcePlan sourcePlan = SourcePlan.builder()
                .sourceType("DOCS")
                .urls(List.of("https://www.notion.so/product/ai"))
                .notes("Prefer official site and docs entry")
                .candidates(List.of(candidate))
                .build();

        when(sourceDiscoveryService.discoverForPreview(anyString(), anyList(), anyList()))
                .thenReturn(List.of(sourcePlan));
        when(sourceDiscoveryService.discover(anyString(), anyList(), anyList()))
                .thenReturn(List.of(sourcePlan));
        when(promptTemplateService.buildSearchQueries(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        "Notion AI official docs",
                        "Notion AI pricing"
                ));
    }

    @Test
    void shouldPassVerificationSmokeForPreviewCreateRerunResumeAndRuntimeContract() throws Exception {
        String payload = """
                {
                  "taskName": "AI knowledge base competitor analysis",
                  "subjectProduct": "Enterprise knowledge base",
                  "competitorNames": ["Notion AI"],
                  "competitorUrls": ["https://www.notion.so/product/ai"],
                  "analysisDimensions": ["Product capability", "Pricing strategy"],
                  "sourceScope": ["Official site", "Product docs"]
                }
                """;

        mockMvc.perform(post("/api/task/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractType").value("TASK_PLAN_PREVIEW_V1"))
                .andExpect(jsonPath("$.data.nodes[0].stageCode").value("SOURCE_STRATEGY"))
                .andExpect(jsonPath("$.data.nodes[0].fallbackOrder[0]").value("PLANNED"))
                .andExpect(jsonPath("$.data.nodes[0].configSummaryData.competitorName").value("Notion AI"))
                .andExpect(jsonPath("$.data.nodes[0].configSummaryData.sourceType").value("DOCS"));

        MvcResult createResult = mockMvc.perform(post("/api/task/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.currentPlanVersion").value(1))
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long taskId = createResponse.path("data").path("id").asLong();

        AnalysisTask createdTask = taskRepository.findById(taskId).orElseThrow();
        assertNotNull(createdTask.getCurrentPlanVersionId());
        assertEquals(1, createdTask.getCurrentPlanVersion());

        TaskPlan activePlan = taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(taskId).orElseThrow();
        JsonNode planSnapshot = objectMapper.readTree(activePlan.getPlanSnapshot());
        assertEquals("TASK_PLAN_PREVIEW_V1", planSnapshot.path("contractType").asText());
        assertFalse(planSnapshot.path("goal").asText().isBlank());
        assertTrue(planSnapshot.path("stages").isArray());
        assertFalse(planSnapshot.path("stages").isEmpty());

        mockMvc.perform(get("/api/task/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.currentPlanVersion").value(1));

        mockMvc.perform(get("/api/task/{id}/nodes", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].contractType").value("TASK_NODE_RUNTIME_V1"));

        /**
         * 这里验证的重点不是“任务有没有真的跑起来”，
         * 而是“rerun / resume 在清理执行态时，有没有偷偷改掉当前计划版本，
         * 以及节点 nodeConfig 里已落库的 searchExecutionPlan / fallbackOrder 语义”。
         */
        TaskNode firstNode = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).get(0);
        Long originalPlanVersionId = createdTask.getCurrentPlanVersionId();
        Integer originalPlanVersion = createdTask.getCurrentPlanVersion();
        Long originalNodePlanVersionId = firstNode.getPlanVersionId();

        JsonNode originalNodeConfig = objectMapper.readTree(firstNode.getNodeConfig());
        assertEquals("COLLECTOR_SEARCH_AND_COLLECT",
                originalNodeConfig.path("searchExecutionPlan").path("stage").asText());
        assertEquals("PLANNED",
                originalNodeConfig.path("searchExecutionPlan").path("fallbackOrder").get(0).asText());

        mockMvc.perform(post("/api/task/{id}/nodes/{nodeName}/rerun", taskId, firstNode.getNodeName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AnalysisTask rerunTask = taskRepository.findById(taskId).orElseThrow();
        TaskNode rerunNode = nodeRepository.findByTaskIdAndNodeName(taskId, firstNode.getNodeName()).orElseThrow();
        assertEquals(originalPlanVersionId, rerunTask.getCurrentPlanVersionId());
        assertEquals(originalPlanVersion, rerunTask.getCurrentPlanVersion());
        assertEquals(originalNodePlanVersionId, rerunNode.getPlanVersionId());

        JsonNode rerunNodeConfig = objectMapper.readTree(rerunNode.getNodeConfig());
        assertEquals("COLLECTOR_SEARCH_AND_COLLECT",
                rerunNodeConfig.path("searchExecutionPlan").path("stage").asText());
        assertEquals("PLANNED",
                rerunNodeConfig.path("searchExecutionPlan").path("fallbackOrder").get(0).asText());

        mockMvc.perform(post("/api/task/{id}/resume", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AnalysisTask resumedTask = taskRepository.findById(taskId).orElseThrow();
        TaskNode resumedNode = nodeRepository.findByTaskIdAndNodeName(taskId, firstNode.getNodeName()).orElseThrow();
        assertEquals(originalPlanVersionId, resumedTask.getCurrentPlanVersionId());
        assertEquals(originalPlanVersion, resumedTask.getCurrentPlanVersion());
        assertEquals(originalNodePlanVersionId, resumedNode.getPlanVersionId());

        JsonNode resumedNodeConfig = objectMapper.readTree(resumedNode.getNodeConfig());
        assertEquals("COLLECTOR_SEARCH_AND_COLLECT",
                resumedNodeConfig.path("searchExecutionPlan").path("stage").asText());
        assertEquals("PLANNED",
                resumedNodeConfig.path("searchExecutionPlan").path("fallbackOrder").get(0).asText());
    }
}
