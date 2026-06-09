package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse;
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
import static org.mockito.Mockito.verify;
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
                .eventStreamPath("/api/task/88/events")
                .interventionSummary("当前支持基于已有检查点恢复执行。")
                .resumeAdvice("适合你主动停止任务后想沿用已有成果继续时使用。系统会保留已完成节点，只恢复中断链路。")
                .replayEntrySummary("需要回看失败原因、检查点和原始输入输出时，请先进入节点追踪，再展开高级诊断。")
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
                .impactSummary("恢复后会继续当前节点及其后续待执行链路，不会清空无关已完成节点。")
                .checkpointSummary("该节点不提供独立检查点，主要复用的是未受影响上游结果。")
                .replayEntrySummary("如需确认暂停前发生了什么，请先打开节点追踪，再进入高级诊断查看回放。")
                .eventKey("extract_schema")
                .interventionSummary("节点已暂停，可恢复后继续执行。")
                .build()));

        mockMvc.perform(get("/api/task/88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canResume").value(true))
                .andExpect(jsonPath("$.data.eventStreamPath").value("/api/task/88/events"))
                .andExpect(jsonPath("$.data.interventionSummary").value("当前支持基于已有检查点恢复执行。"))
                .andExpect(jsonPath("$.data.resumeAdvice").value("适合你主动停止任务后想沿用已有成果继续时使用。系统会保留已完成节点，只恢复中断链路。"))
                .andExpect(jsonPath("$.data.replayEntrySummary").value("需要回看失败原因、检查点和原始输入输出时，请先进入节点追踪，再展开高级诊断。"));

        mockMvc.perform(get("/api/task/88/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeName").value("extract_schema"))
                .andExpect(jsonPath("$.data[0].status").value("PAUSED"))
                .andExpect(jsonPath("$.data[0].canResumeNode").value(true))
                .andExpect(jsonPath("$.data[0].eventKey").value("extract_schema"))
                .andExpect(jsonPath("$.data[0].affectedNodeCount").value(3))
                .andExpect(jsonPath("$.data[0].impactSummary").value("恢复后会继续当前节点及其后续待执行链路，不会清空无关已完成节点。"))
                .andExpect(jsonPath("$.data[0].checkpointSummary").value("该节点不提供独立检查点，主要复用的是未受影响上游结果。"))
                .andExpect(jsonPath("$.data[0].replayEntrySummary").value("如需确认暂停前发生了什么，请先打开节点追踪，再进入高级诊断查看回放。"));
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

    @Test
    void shouldReturnFormalTaskListPaginationPayload() throws Exception {
        when(taskService.listTasks(null, 2, 10)).thenReturn(TaskListPageResponse.builder()
                .items(List.of(TaskResponse.builder()
                        .id(21L)
                        .taskName("Phase 3 task")
                        .status(AnalysisTaskStatus.RUNNING)
                        .build()))
                .attentionItems(List.of(TaskResponse.builder()
                        .id(22L)
                        .taskName("Attention task")
                        .status(AnalysisTaskStatus.FAILED)
                        .build()))
                .summary(TaskListSummaryResponse.builder()
                        .total(12)
                        .running(3)
                        .success(6)
                        .failed(2)
                        .stopped(1)
                        .avgProgress(54)
                        .build())
                .pageNum(2)
                .pageSize(10)
                .total(12)
                .totalPages(2)
                .build());

        mockMvc.perform(get("/api/task/list")
                        .param("pageNum", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].taskName").value("Phase 3 task"))
                .andExpect(jsonPath("$.data.attentionItems[0].taskName").value("Attention task"))
                .andExpect(jsonPath("$.data.summary.failed").value(2));

        verify(taskService).listTasks(null, 2, 10);
    }
}
