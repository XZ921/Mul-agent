package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskListSummaryResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewStageResponse;
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
                .taskName("Phase 1 鍥炲綊浠诲姟")
                .status(AnalysisTaskStatus.STOPPED)
                .canResume(true)
                .canRetry(false)
                .canViewReport(false)
                .eventStreamPath("/api/task/88/events")
                .interventionSummary("褰撳墠鏀寔鍩轰簬宸叉湁妫€鏌ョ偣鎭㈠鎵ц銆?")
                .resumeAdvice("閫傚悎浣犱富鍔ㄥ仠姝换鍔″悗鎯虫部鐢ㄥ凡鏈夋垚鏋滅户缁椂浣跨敤銆傜郴缁熶細淇濈暀宸插畬鎴愯妭鐐癸紝鍙仮澶嶄腑鏂摼璺€?")
                .replayEntrySummary("闇€瑕佸洖鐪嬪け璐ュ師鍥犮€佹鏌ョ偣鍜屽師濮嬭緭鍏ヨ緭鍑烘椂锛岃鍏堣繘鍏ヨ妭鐐硅拷韪紝鍐嶅睍寮€楂樼骇璇婃柇銆?")
                .build());
        when(taskService.getTaskNodes(88L)).thenReturn(List.of(TaskNodeResponse.builder()
                .id(1001L)
                .nodeName("extract_schema")
                .displayName("绔炲搧缁撴瀯鍖栨娊鍙?")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.PAUSED)
                .controlState(TaskNodeControlState.NONE)
                .canResumeNode(true)
                .canSkip(true)
                .affectedNodeCount(3)
                .affectedNodeNames(List.of("extract_schema", "analyze_competitors", "write_report"))
                .impactSummary("鎭㈠鍚庝細缁х画褰撳墠鑺傜偣鍙婂叾鍚庣画寰呮墽琛岄摼璺紝涓嶄細娓呯┖鏃犲叧宸插畬鎴愯妭鐐广€?")
                .checkpointSummary("璇ヨ妭鐐逛笉鎻愪緵鐙珛妫€鏌ョ偣锛屼富瑕佸鐢ㄧ殑鏄湭鍙楀奖鍝嶄笂娓哥粨鏋溿€?")
                .replayEntrySummary("濡傞渶纭鏆傚仠鍓嶅彂鐢熶簡浠€涔堬紝璇峰厛鎵撳紑鑺傜偣杩借釜锛屽啀杩涘叆楂樼骇璇婃柇鏌ョ湅鍥炴斁銆?")
                .eventKey("extract_schema")
                .interventionSummary("鑺傜偣宸叉殏鍋滐紝鍙仮澶嶅悗缁х画鎵ц銆?")
                .build()));

        mockMvc.perform(get("/api/task/88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.canResume").value(true))
                .andExpect(jsonPath("$.data.eventStreamPath").value("/api/task/88/events"))
                .andExpect(jsonPath("$.data.interventionSummary").value("褰撳墠鏀寔鍩轰簬宸叉湁妫€鏌ョ偣鎭㈠鎵ц銆?"))
                .andExpect(jsonPath("$.data.resumeAdvice").value("閫傚悎浣犱富鍔ㄥ仠姝换鍔″悗鎯虫部鐢ㄥ凡鏈夋垚鏋滅户缁椂浣跨敤銆傜郴缁熶細淇濈暀宸插畬鎴愯妭鐐癸紝鍙仮澶嶄腑鏂摼璺€?"))
                .andExpect(jsonPath("$.data.replayEntrySummary").value("闇€瑕佸洖鐪嬪け璐ュ師鍥犮€佹鏌ョ偣鍜屽師濮嬭緭鍏ヨ緭鍑烘椂锛岃鍏堣繘鍏ヨ妭鐐硅拷韪紝鍐嶅睍寮€楂樼骇璇婃柇銆?"));

        mockMvc.perform(get("/api/task/88/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeName").value("extract_schema"))
                .andExpect(jsonPath("$.data[0].status").value("PAUSED"))
                .andExpect(jsonPath("$.data[0].canResumeNode").value(true))
                .andExpect(jsonPath("$.data[0].eventKey").value("extract_schema"))
                .andExpect(jsonPath("$.data[0].affectedNodeCount").value(3))
                .andExpect(jsonPath("$.data[0].impactSummary").value("鎭㈠鍚庝細缁х画褰撳墠鑺傜偣鍙婂叾鍚庣画寰呮墽琛岄摼璺紝涓嶄細娓呯┖鏃犲叧宸插畬鎴愯妭鐐广€?"))
                .andExpect(jsonPath("$.data[0].checkpointSummary").value("璇ヨ妭鐐逛笉鎻愪緵鐙珛妫€鏌ョ偣锛屼富瑕佸鐢ㄧ殑鏄湭鍙楀奖鍝嶄笂娓哥粨鏋溿€?"))
                .andExpect(jsonPath("$.data[0].replayEntrySummary").value("濡傞渶纭鏆傚仠鍓嶅彂鐢熶簡浠€涔堬紝璇峰厛鎵撳紑鑺傜偣杩借釜锛屽啀杩涘叆楂樼骇璇婃柇鏌ョ湅鍥炴斁銆?"));
    }

    @Test
    void shouldCreateTaskAndExposeFormalPreviewContract() throws Exception {
        when(taskService.createTask(any(CreateTaskRequest.class))).thenReturn(TaskResponse.builder()
                .id(9L)
                .taskName("AI 鐭ヨ瘑搴撶珵鍝佸垎鏋?")
                .status(AnalysisTaskStatus.PENDING)
                .build());
        when(taskService.previewWorkflow(any(CreateTaskRequest.class))).thenReturn(TaskPlanPreviewResponse.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal("围绕企业级 RAG 平台展开竞品研究")
                .competitorCount(1)
                .collectorCount(1)
                .pipelineCount(4)
                .stages(List.of(TaskPlanPreviewStageResponse.builder()
                        .stageCode("SOURCE_STRATEGY")
                        .title("规划来源策略")
                        .summary("优先覆盖官网、产品文档")
                        .sourceUrls(List.of())
                        .build()))
                .nodes(List.of(TaskPlanPreviewNodeResponse.builder()
                        .nodeName("collect_sources_01_01")
                        .displayName("Notion AI - DOCS采集")
                        .stageCode("SOURCE_STRATEGY")
                        .goal("优先覆盖官网与产品文档")
                        .summary("必要时再补充公网搜索")
                        .fallbackOrder(List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP"))
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .build());

        String payload = """
                {
                  "taskName": "AI 鐭ヨ瘑搴撶珵鍝佸垎鏋?",
                  "subjectProduct": "浼佷笟绾х煡璇嗗簱",
                  "competitorNames": ["Notion AI"],
                  "competitorUrls": ["https://www.notion.so/product/ai"],
                  "analysisDimensions": ["浜у搧鍔熻兘", "浠锋牸绛栫暐"],
                  "sourceScope": ["瀹樼綉", "浜у搧鏂囨。"]
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
                .andExpect(jsonPath("$.data.contractType").value("TASK_PLAN_PREVIEW_V1"))
                .andExpect(jsonPath("$.data.goal").value("围绕企业级 RAG 平台展开竞品研究"))
                .andExpect(jsonPath("$.data.nodes[0].stageCode").value("SOURCE_STRATEGY"))
                .andExpect(jsonPath("$.data.nodes[0].fallbackOrder[0]").value("PLANNED"));
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
