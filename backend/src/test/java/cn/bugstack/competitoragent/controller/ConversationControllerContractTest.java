package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.GlobalExceptionHandler;
import cn.bugstack.competitoragent.conversation.ConversationService;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5.5.c 控制器契约测试。
 * 这里只验证统一对话入口是否已经暴露确认执行所需的新请求字段和结构化执行结果，
 * 避免服务层闭环已经完成，但 HTTP 契约仍无法承载确认对象回传。
 */
class ConversationControllerContractTest {

    private final ConversationService conversationService = mock(ConversationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldAcceptConfirmationPayloadAndReturnStructuredExecutionResult() throws Exception {
        when(conversationService.handleMessage(any())).thenReturn(ConversationResponse.builder()
                .sessionId(3050L)
                .mode("TASK_ACTION")
                .answer("系统已提交从 rewrite_report 开始重跑的执行请求。")
                .intentDecision(ConversationResponse.IntentDecisionSummary.builder()
                        .decisionId(7002L)
                        .mode("TASK_ACTION")
                        .intentType("CONFIRMED_RERUN_NODE")
                        .decisionReason("用户已确认高风险动作，系统已提交正式执行请求。")
                        .highRiskAction(true)
                        .requiresConfirmation(false)
                        .riskLevel("HIGH")
                        .impactScope("CURRENT_NODE_AND_DOWNSTREAM")
                        .build())
                .taskActionExecution(ConversationResponse.TaskActionExecutionResult.builder()
                        .actionType("RERUN_NODE")
                        .taskId(305L)
                        .targetNodeName("rewrite_report")
                        .executionStatus("SUBMITTED")
                        .executionMessage("系统已提交从 rewrite_report 开始重跑的执行请求。")
                        .previewDecisionId(7001L)
                        .auditDecisionId(7002L)
                        .auditStatus("RECORDED")
                        .build())
                .build());

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": 3050,
                                  "pageType": "TASK_DETAIL",
                                  "message": "确认执行这个动作",
                                  "executeConfirmedAction": true,
                                  "confirmationRequest": {
                                    "actionType": "RERUN_NODE",
                                    "targetType": "TASK_NODE",
                                    "targetId": "rewrite_report",
                                    "confirmationTitle": "从 rewrite_report 开始重跑",
                                    "confirmationMessage": "请先确认影响范围，再正式执行重跑。",
                                    "impactScope": "CURRENT_NODE_AND_DOWNSTREAM",
                                    "impactSummary": "将影响当前节点及所有下游节点",
                                    "riskLevel": "HIGH"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("TASK_ACTION"))
                .andExpect(jsonPath("$.data.intentDecision.intentType").value("CONFIRMED_RERUN_NODE"))
                .andExpect(jsonPath("$.data.taskActionExecution.actionType").value("RERUN_NODE"))
                .andExpect(jsonPath("$.data.taskActionExecution.targetNodeName").value("rewrite_report"))
                .andExpect(jsonPath("$.data.taskActionExecution.executionStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.taskActionExecution.previewDecisionId").value(7001))
                .andExpect(jsonPath("$.data.taskActionExecution.auditStatus").value("RECORDED"));

        verify(conversationService).handleMessage(any());
    }

    @Test
    void shouldExposeOrchestrationDecisionSummaryInTaskActionPreviewContract() throws Exception {
        when(conversationService.handleMessage(any())).thenReturn(ConversationResponse.builder()
                .sessionId(3880L)
                .mode("RESEARCH")
                .answer("Need more evidence before continuing")
                .sourceUrls(java.util.List.of("https://docs.example.com/pricing", "https://ops.example.com/analyzer-gap"))
                .taskActionPreview(ConversationResponse.TaskActionPreview.builder()
                        .actionType("SUPPLEMENT_EVIDENCE")
                        .taskId(388L)
                        .targetNodeName("collect_sources_web")
                        .title("Research preview")
                        .actionSummary("Show research action without dropping orchestration context")
                        .impactSummary("Affects evidence collection and downstream analysis")
                        .riskLevel("MEDIUM")
                        .requiresConfirmation(true)
                        .confirmationHint("Review the evidence before continuing")
                        .executable(false)
                        .orchestrationDecision(ConversationResponse.OrchestrationDecisionSummary.builder()
                                .decisionId("od-388-analyze-human")
                                .taskId(388L)
                                .triggerNodeName("analyze_competitors")
                                .decisionType("WAIT_FOR_HUMAN")
                                .actionType("MANUAL_REVIEW")
                                .targetNode("collect_sources_web")
                                .affectedScope("CURRENT_NODE_ONLY")
                                .reason("Analyzer found missing source evidence")
                                .requiresHumanIntervention(true)
                                .requiresConfirmation(false)
                                .evidenceState("MISSING_SOURCE")
                                .sourceUrls(java.util.List.of("https://ops.example.com/analyzer-gap"))
                                .build())
                        .sourceUrls(java.util.List.of(
                                "https://docs.example.com/pricing",
                                "https://ops.example.com/analyzer-gap"
                        ))
                        .build())
                .build());

        mockMvc.perform(post("/api/conversation/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": 3880,
                                  "pageType": "TASK_DETAIL",
                                  "message": "What should I do next?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskActionPreview.orchestrationDecision.decisionId").value("od-388-analyze-human"))
                .andExpect(jsonPath("$.data.taskActionPreview.orchestrationDecision.evidenceState").value("MISSING_SOURCE"))
                .andExpect(jsonPath("$.data.sourceUrls[0]").value("https://docs.example.com/pricing"));

        verify(conversationService).handleMessage(any());
    }
}
