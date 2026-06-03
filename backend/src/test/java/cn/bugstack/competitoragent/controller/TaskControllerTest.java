package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.task.AnalysisTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerTest {

    private final AnalysisTaskService taskService = mock(AnalysisTaskService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(taskService)).build();
    }

    @Test
    void shouldReturnTaskDetailAndNodeInterventionCapabilities() throws Exception {
        when(taskService.getTask(88L)).thenReturn(TaskResponse.builder()
                .id(88L)
                .taskName("Phase 1 回归任务")
                .status(AnalysisTaskStatus.STOPPED)
                .canResume(true)
                .canRetry(false)
                .canViewReport(false)
                .interventionSummary("当前支持基于已有检查点恢复执行。")
                .build());
        when(taskService.getTaskNodes(88L)).thenReturn(List.of(TaskNodeResponse.builder()
                .id(1001L)
                .nodeName("extract_schema")
                .displayName("竞品结构化抽取")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.PAUSED)
                .controlState(TaskNodeControlState.NONE)
                .canResumeNode(true)
                .canSkip(true)
                .affectedNodeCount(3)
                .affectedNodeNames(List.of("extract_schema", "analyze_competitors", "write_report"))
                .interventionSummary("节点已暂停，可恢复后继续执行。")
                .build()));

        mockMvc.perform(get("/api/task/88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canResume").value(true))
                .andExpect(jsonPath("$.data.interventionSummary").value("当前支持基于已有检查点恢复执行。"));

        mockMvc.perform(get("/api/task/88/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeName").value("extract_schema"))
                .andExpect(jsonPath("$.data[0].status").value("PAUSED"))
                .andExpect(jsonPath("$.data[0].canResumeNode").value(true))
                .andExpect(jsonPath("$.data[0].affectedNodeCount").value(3));
    }

    @Test
    void shouldCreateTaskAndExposeWorkflowPreviewContract() throws Exception {
        when(taskService.createTask(any(CreateTaskRequest.class))).thenReturn(TaskResponse.builder()
                .id(9L)
                .taskName("AI 知识库竞品分析")
                .status(AnalysisTaskStatus.PENDING)
                .build());
        when(taskService.previewWorkflow(any(CreateTaskRequest.class))).thenReturn(List.of(TaskNodeResponse.builder()
                .nodeName("collect_sources_01_01")
                .displayName("Notion AI - DOCS采集")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.PENDING)
                .configSummary("Notion AI · 文档采集 · 搜索模式：混合 · 候选 1 条")
                .interventionSummary("预览阶段仅展示规划结果，任务创建后才可执行节点级暂停、跳过、终止或重跑。")
                .build()));

        String payload = """
                {
                  "taskName": "AI 知识库竞品分析",
                  "subjectProduct": "企业级知识库",
                  "competitorNames": ["Notion AI"],
                  "competitorUrls": ["https://www.notion.so/product/ai"],
                  "analysisDimensions": ["产品功能", "价格策略"],
                  "sourceScope": ["官网", "产品文档"]
                }
                """;

        mockMvc.perform(post("/api/task/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/task/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeName").value("collect_sources_01_01"))
                .andExpect(jsonPath("$.data[0].configSummary").value("Notion AI · 文档采集 · 搜索模式：混合 · 候选 1 条"));
    }

    @Test
    void shouldForwardResumeAndRerunCommands() throws Exception {
        doNothing().when(taskService).resumeTask(18L);
        doNothing().when(taskService).rerunFromNode(18L, "collect_sources_01_01");

        mockMvc.perform(post("/api/task/18/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Task resumed from existing checkpoints"));

        mockMvc.perform(post("/api/task/18/nodes/collect_sources_01_01/rerun"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Task rerun scheduled from node collect_sources_01_01"));
    }
}
