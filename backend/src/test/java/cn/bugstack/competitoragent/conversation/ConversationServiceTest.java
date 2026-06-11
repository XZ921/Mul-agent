package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.agent.conversation.ConversationAgent;
import cn.bugstack.competitoragent.knowledge.application.KnowledgeRetrievalFacade;
import cn.bugstack.competitoragent.model.dto.ConversationActionConfirmationRequest;
import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.ConversationSession;
import cn.bugstack.competitoragent.model.entity.IntentDecision;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.report.application.ReportQueryFacade;
import cn.bugstack.competitoragent.repository.ConversationSessionRepository;
import cn.bugstack.competitoragent.repository.FormDraftRepository;
import cn.bugstack.competitoragent.repository.IntentDecisionRepository;
import cn.bugstack.competitoragent.task.application.TaskQueryFacade;
import cn.bugstack.competitoragent.task.application.TaskRuntimeFacade;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 4.6 服务层黑盒测试。
 * 这些用例直接验证“会话 -> 意图 -> 模式 -> 回答 -> 审计”的编排行为，
 * 避免统一对话入口只在控制器层可用、但服务层返回值和持久化语义失真。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationSessionRepository conversationSessionRepository;

    @Mock
    private IntentDecisionRepository intentDecisionRepository;

    @Mock
    private FormDraftRepository formDraftRepository;

    @Mock
    private IntentRecognitionService intentRecognitionService;

    @Mock
    private ModeRouter modeRouter;

    @Mock
    private ClarificationOrchestrator clarificationOrchestrator;

    @Mock
    private FormDraftBuilder formDraftBuilder;

    @Mock
    private TaskActionTranslator taskActionTranslator;

    @Mock
    private ConversationAgent conversationAgent;

    @Mock
    private TaskQueryFacade taskQueryFacade;

    @Mock
    private TaskRuntimeFacade taskRuntimeFacade;

    @Mock
    private KnowledgeRetrievalFacade knowledgeRetrievalFacade;

    @Mock
    private ReportQueryFacade reportQueryFacade;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        /**
         * 这里显式在每个用例前重新创建被测服务，
         * 避免字段初始化早于 Mockito 注入，导致测试还没进业务逻辑就被空指针打断。
         */
        conversationService = new ConversationService(
                conversationSessionRepository,
                intentDecisionRepository,
                formDraftRepository,
                intentRecognitionService,
                modeRouter,
                clarificationOrchestrator,
                formDraftBuilder,
                taskActionTranslator,
                conversationAgent,
                taskQueryFacade,
                taskRuntimeFacade,
                knowledgeRetrievalFacade,
                reportQueryFacade,
                new ObjectMapper()
        );
    }

    @Test
    void shouldReturnPersistedSessionIdWhenFirstMessageCreatesConversationSession() {
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(88L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("这个任务为什么停在这里了？");

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.EXPLAIN)
                .intentType("TASK_STATUS_EXPLANATION")
                .decisionReason("命中了任务解释语义")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(88L)
                .taskName("统一对话任务")
                .currentStage("报告撰写")
                .statusSummary("当前等待系统解释任务卡点")
                .build();

        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(101L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(101L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 88L)).thenReturn(ConversationMode.EXPLAIN);
        when(taskQueryFacade.getTask(88L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(88L)).thenReturn(List.of());
        when(conversationAgent.composeExplainAnswer(request.getMessage(), taskResponse, null)).thenReturn("当前任务停在报告撰写。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(501L);
            return decision;
        });

        ConversationResponse response = conversationService.handleMessage(request);

        assertNotNull(response);
        assertEquals(101L, response.getSessionId());
        assertEquals(501L, response.getIntentDecision().getDecisionId());

        /**
         * 这里额外确认会话摘要最终写回的是同一个新建 session，
         * 避免“响应里看起来有 sessionId，但持久化的会话对象仍未绑定最新回答”。
         */
        ArgumentCaptor<ConversationSession> sessionCaptor = ArgumentCaptor.forClass(ConversationSession.class);
        verify(conversationSessionRepository, org.mockito.Mockito.atLeastOnce()).save(sessionCaptor.capture());
        ConversationSession savedSession = sessionCaptor.getValue();
        assertEquals(101L, savedSession.getId());
        assertEquals("EXPLAIN", savedSession.getCurrentMode());
        assertEquals("这个任务为什么停在这里了？", savedSession.getLatestUserMessage());
    }

    @Test
    void should_read_task_views_through_task_query_facade() {
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(88L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("这个任务卡在哪里？");

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.EXPLAIN)
                .intentType("TASK_STATUS_EXPLANATION")
                .decisionReason("命中了任务解释语义")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();
        TaskResponse taskResponse = TaskResponse.builder()
                .id(88L)
                .taskName("统一对话任务")
                .currentStage("报告撰写")
                .statusSummary("当前等待系统解释任务卡点")
                .build();

        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(1888L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(1888L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 88L)).thenReturn(ConversationMode.EXPLAIN);
        when(taskQueryFacade.getTask(88L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(88L)).thenReturn(List.of());
        when(conversationAgent.composeExplainAnswer(request.getMessage(), taskResponse, null)).thenReturn("当前任务停在报告撰写。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(1889L);
            return decision;
        });

        ConversationResponse response = conversationService.handleMessage(request);

        assertNotNull(response);
        verify(taskQueryFacade).getTask(88L);
        verify(taskQueryFacade).getTaskNodes(88L);
    }

    @Test
    void shouldPreserveExistingPageTypeWhenFollowUpMessageOmitsPageType() {
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setSessionId(55L);
        request.setMessage("继续解释这个任务");

        ConversationSession existingSession = ConversationSession.builder()
                .id(55L)
                .taskId(88L)
                .pageType("TASK_DETAIL")
                .build();

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.EXPLAIN)
                .intentType("TASK_STATUS_EXPLANATION")
                .decisionReason("沿用任务详情页上下文继续解释")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(88L)
                .taskName("统一对话任务")
                .currentStage("信息采集")
                .statusSummary("继续解释当前任务状态")
                .build();

        when(conversationSessionRepository.findById(55L)).thenReturn(Optional.of(existingSession));
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(55L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), eq(existingSession), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 88L)).thenReturn(ConversationMode.EXPLAIN);
        when(taskQueryFacade.getTask(88L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(88L)).thenReturn(List.of());
        when(conversationAgent.composeExplainAnswer(request.getMessage(), taskResponse, null)).thenReturn("继续沿用任务详情上下文解释。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(777L);
            return decision;
        });
        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationResponse response = conversationService.handleMessage(request);

        assertNotNull(response);
        assertEquals(55L, response.getSessionId());

        /**
         * 后续消息允许省略 pageType，但服务层不能把原来的 TASK_DETAIL 上下文重置成 GLOBAL。
         * 否则意图审计和会话恢复都会丢掉“这条解释来自哪个页面入口”的关键语义。
         */
        ArgumentCaptor<IntentDecision> decisionCaptor = ArgumentCaptor.forClass(IntentDecision.class);
        verify(intentDecisionRepository).save(decisionCaptor.capture());
        assertEquals("TASK_DETAIL", decisionCaptor.getValue().getPageType());

        ArgumentCaptor<ConversationSession> sessionCaptor = ArgumentCaptor.forClass(ConversationSession.class);
        verify(conversationSessionRepository).save(sessionCaptor.capture());
        assertEquals("TASK_DETAIL", sessionCaptor.getValue().getPageType());
    }

    @Test
    void shouldExposeReusableMemoryAndRuntimeContextExplanationForTaskConversation() {
        // Task 5.4.d 要求对话入口也能解释“哪些内容来自可复用记忆，哪些来自当前任务现场”，
        // 避免会话层只能给一句状态话术，却无法说明记忆复用边界。
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(188L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("当前结论里哪些是复用记忆，哪些是本轮任务确认的？");

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.EXPLAIN)
                .intentType("TASK_STATUS_EXPLANATION")
                .decisionReason("需要解释上下文边界")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(188L)
                .taskName("Notion AI 报告复盘")
                .currentStage("报告撰写")
                .statusSummary("当前需要解释结论来源边界")
                .build();

        TaskNodeResponse nodeResponse = TaskNodeResponse.builder()
                .nodeName("write_report")
                .displayName("报告撰写")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .executionOrder(3)
                .outputData("""
                        {
                          "taskRagContext": "知识上下文\\n检索查询：Notion AI enterprise governance\\n检索摘要：当前任务命中企业治理资料。\\n缺口说明：仍缺企业定价公开证据。\\n来源链接：https://example.com/task-knowledge\\n可复用记忆\\n1. 当前任务已经核实官网定价页缺少企业价卡。 | 记忆层级：SHORT_TERM | 来源对象：MEMORY_SNAPSHOT | 来源节点/对象：collect_sources | versionSource=TASK_RAG@PLAN-22:analysis | invalidationScope=TASK_RERUN | invalidationReason=PLAN_VERSION_CHANGED | reuseReason=同计划版本内可复用，计划重跑后失效 | sourceUrls=https://example.com/notion-ai/pricing\\n任务即时上下文\\n1. collect_sources -> 当前任务已确认需要重点解释企业治理与审计"
                        }
                        """)
                .build();

        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(1880L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(1880L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 188L)).thenReturn(ConversationMode.EXPLAIN);
        when(taskQueryFacade.getTask(188L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(188L)).thenReturn(List.of(nodeResponse));
        when(conversationAgent.composeExplainAnswer(request.getMessage(), taskResponse, nodeResponse)).thenReturn("我会先解释当前任务的结论边界。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(1881L);
            return decision;
        });

        ConversationResponse response = conversationService.handleMessage(request);
        Map<String, Object> responseMap = new ObjectMapper().convertValue(response, new TypeReference<>() {
        });

        assertEquals("EXPLAIN", response.getMode());
        assertNotNull(responseMap.get("taskRagContextSummary"));
        String taskRagContextSummary = String.valueOf(responseMap.get("taskRagContextSummary"));
        assertTrue(taskRagContextSummary.contains("可复用记忆"));
        assertTrue(taskRagContextSummary.contains("任务即时上下文"));
        assertTrue(taskRagContextSummary.contains("TASK_RERUN"));
        assertTrue(taskRagContextSummary.contains("collect_sources"));
    }

    @Test
    void shouldExposeStructuredSafetyDecisionAndConfirmationRequestForHighRiskTaskAction() {
        /**
         * Task 5.5.a 要求意图决策结果不再只靠 highRiskAction / requiresConfirmation 两个布尔值表达风险，
         * 而是要能稳定返回风险等级、影响范围和确认对象，避免前端或审计链路再去猜测高风险动作语义。
         */
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(205L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("从 rewrite_report 开始重跑");

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.TASK_ACTION)
                .intentType("RERUN_FROM_NODE")
                .decisionReason("用户请求从失败节点开始重跑")
                .highRiskAction(true)
                .requiresConfirmation(true)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(205L)
                .taskName("Notion AI 报告修订")
                .currentStage("报告改写")
                .statusSummary("当前需要先确认重跑影响范围")
                .build();

        ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
                .actionType("RERUN_NODE")
                .taskId(205L)
                .targetNodeName("rewrite_report")
                .title("从 rewrite_report 开始重跑")
                .actionSummary("系统会从改写节点重新组织后续链路")
                .impactSummary("将影响当前节点及所有下游节点")
                .riskLevel("HIGH")
                .requiresConfirmation(true)
                .confirmationHint("请先确认重跑影响范围，再执行正式动作入口。")
                .executable(false)
                .sourceUrls(List.of("https://example.com/rewrite-report"))
                .build();

        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(2050L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(2050L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 205L)).thenReturn(ConversationMode.TASK_ACTION);
        when(taskQueryFacade.getTask(205L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(205L)).thenReturn(List.of());
        when(taskActionTranslator.buildTaskActionPreview(request.getMessage(), 205L, taskResponse, List.of())).thenReturn(preview);
        when(conversationAgent.composeActionPreviewAnswer(request.getMessage(), preview)).thenReturn("请先确认本次重跑影响范围。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(2051L);
            return decision;
        });

        ConversationResponse response = conversationService.handleMessage(request);
        Map<String, Object> responseMap = new ObjectMapper().convertValue(response, new TypeReference<>() {
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> intentDecision = (Map<String, Object>) responseMap.get("intentDecision");
        assertEquals("HIGH", intentDecision.get("riskLevel"));
        assertEquals("CURRENT_NODE_AND_DOWNSTREAM", intentDecision.get("impactScope"));
        assertEquals(Boolean.TRUE, intentDecision.get("requiresConfirmation"));

        @SuppressWarnings("unchecked")
        Map<String, Object> confirmationRequest = (Map<String, Object>) intentDecision.get("confirmationRequest");
        assertEquals("RERUN_NODE", confirmationRequest.get("actionType"));
        assertEquals("TASK_NODE", confirmationRequest.get("targetType"));
        assertEquals("rewrite_report", confirmationRequest.get("targetId"));
        assertEquals("CURRENT_NODE_AND_DOWNSTREAM", confirmationRequest.get("impactScope"));
        assertTrue(String.valueOf(confirmationRequest.get("confirmationMessage")).contains("确认"));

        /**
         * 这里额外锁定审计实体也必须持久化结构化安全信息，
         * 避免接口层看起来有风险摘要，但落库后仍然只剩布尔标记，后续无法回放或复核。
         */
        ArgumentCaptor<IntentDecision> decisionCaptor = ArgumentCaptor.forClass(IntentDecision.class);
        verify(intentDecisionRepository).save(decisionCaptor.capture());
        IntentDecision savedDecision = decisionCaptor.getValue();
        assertEquals("HIGH", ReflectionTestUtils.getField(savedDecision, "riskLevel"));
        assertEquals("CURRENT_NODE_AND_DOWNSTREAM", ReflectionTestUtils.getField(savedDecision, "impactScope"));
        String confirmationRequestPayload = String.valueOf(
                ReflectionTestUtils.getField(savedDecision, "confirmationRequestPayload"));
        assertTrue(confirmationRequestPayload.contains("RERUN_NODE"));
        assertTrue(confirmationRequestPayload.contains("rewrite_report"));
    }

    @Test
    void shouldExecuteConfirmedHighRiskActionAndPersistAuditableExecutionResult() {
        /**
         * Task 5.5.c 要求高风险动作不能停在“只给预览”，
         * 而是必须经过预览 -> 确认 -> 执行闭环，并把执行结果写入对话审计。
         */
        ConversationMessageRequest previewRequest = new ConversationMessageRequest();
        previewRequest.setTaskId(305L);
        previewRequest.setPageType("TASK_DETAIL");
        previewRequest.setMessage("从 rewrite_report 开始重跑");

        IntentRecognitionService.RecognitionResult previewRecognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.TASK_ACTION)
                .intentType("RERUN_FROM_NODE")
                .decisionReason("用户请求从报告改写节点开始重跑")
                .highRiskAction(true)
                .requiresConfirmation(true)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(305L)
                .taskName("Notion AI 报告修订")
                .currentStage("报告改写")
                .statusSummary("当前需要先预览再确认重跑范围")
                .build();

        ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
                .actionType("RERUN_NODE")
                .taskId(305L)
                .targetNodeName("rewrite_report")
                .title("从 rewrite_report 开始重跑")
                .actionSummary("系统会从报告改写节点重新组织后续链路")
                .impactSummary("将影响当前节点及所有下游节点")
                .riskLevel("HIGH")
                .requiresConfirmation(true)
                .confirmationHint("请先确认影响范围，再正式执行重跑。")
                .executable(false)
                .sourceUrls(List.of("https://example.com/rewrite-report"))
                .build();

        AtomicLong decisionIdSequence = new AtomicLong(7000L);
        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(3050L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(3050L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(previewRequest), any(ConversationSession.class), eq(false))).thenReturn(previewRecognitionResult);
        when(clarificationOrchestrator.resolve(any(), any(), any(), any(), any())).thenReturn(
                ClarificationOrchestrator.ClarificationDecision.none());
        when(modeRouter.route(previewRecognitionResult, 305L)).thenReturn(ConversationMode.TASK_ACTION);
        when(taskQueryFacade.getTask(305L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(305L)).thenReturn(List.of());
        when(taskActionTranslator.buildTaskActionPreview(previewRequest.getMessage(), 305L, taskResponse, List.of())).thenReturn(preview);
        when(taskActionTranslator.buildExecutionPlan(any(ConversationActionConfirmationRequest.class), eq(305L))).thenReturn(
                TaskActionTranslator.TaskActionExecutionPlan.builder()
                        .actionType("RERUN_NODE")
                        .taskId(305L)
                        .targetNodeName("rewrite_report")
                        .executionMessage("系统已提交从 rewrite_report 开始重跑的执行请求。")
                        .build());
        when(conversationAgent.composeActionPreviewAnswer(previewRequest.getMessage(), preview)).thenReturn("请先确认本次重跑的影响范围。");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            if (decision.getId() == null) {
                decision.setId(decisionIdSequence.incrementAndGet());
            }
            return decision;
        });
        doNothing().when(taskRuntimeFacade).rerunFromNode(305L, "rewrite_report");

        ConversationResponse previewResponse = conversationService.handleMessage(previewRequest);

        assertEquals("TASK_ACTION", previewResponse.getMode());
        assertNotNull(previewResponse.getIntentDecision().getConfirmationRequest());
        assertEquals(7001L, previewResponse.getIntentDecision().getDecisionId());

        ConversationMessageRequest executeRequest = new ConversationMessageRequest();
        executeRequest.setSessionId(3050L);
        executeRequest.setPageType("TASK_DETAIL");
        executeRequest.setMessage("确认执行这个动作");
        executeRequest.setExecuteConfirmedAction(true);
        executeRequest.setConfirmationRequest(previewResponse.getIntentDecision().getConfirmationRequest());

        ConversationSession existingSession = ConversationSession.builder()
                .id(3050L)
                .taskId(305L)
                .pageType("TASK_DETAIL")
                .currentMode("TASK_ACTION")
                .lastIntentDecisionId(7001L)
                .build();
        when(conversationSessionRepository.findById(3050L)).thenReturn(Optional.of(existingSession));

        ConversationResponse executionResponse = conversationService.handleMessage(executeRequest);
        Map<String, Object> executionResponseMap = new ObjectMapper().convertValue(executionResponse, new TypeReference<>() {
        });

        assertEquals("TASK_ACTION", executionResponse.getMode());
        assertNotNull(executionResponse.getTaskActionExecution());
        assertEquals("SUBMITTED", executionResponse.getTaskActionExecution().getExecutionStatus());
        assertEquals("rewrite_report", executionResponse.getTaskActionExecution().getTargetNodeName());
        assertEquals(7001L, executionResponse.getTaskActionExecution().getPreviewDecisionId());
        assertEquals(7002L, executionResponse.getTaskActionExecution().getAuditDecisionId());
        assertEquals("RECORDED", executionResponse.getTaskActionExecution().getAuditStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> executionResult = (Map<String, Object>) executionResponseMap.get("taskActionExecution");
        assertEquals("RERUN_NODE", executionResult.get("actionType"));
        assertEquals("SUBMITTED", executionResult.get("executionStatus"));
        assertTrue(String.valueOf(executionResult.get("executionMessage")).contains("提交"));

        verify(taskRuntimeFacade).rerunFromNode(305L, "rewrite_report");

        /**
         * 第二次意图决策必须把执行结果一起写入审计 payload，
         * 否则只能回放“用户点过确认”，却无法知道后端到底有没有真正提交动作。
         */
        ArgumentCaptor<IntentDecision> decisionCaptor = ArgumentCaptor.forClass(IntentDecision.class);
        verify(intentDecisionRepository, times(2)).save(decisionCaptor.capture());
        List<IntentDecision> savedDecisions = decisionCaptor.getAllValues();
        IntentDecision executionDecision = savedDecisions.get(1);
        assertEquals("CONFIRMED_RERUN_NODE", executionDecision.getIntentType());
        assertTrue(executionDecision.getDecisionPayload().contains("taskActionExecution"));
        assertTrue(executionDecision.getDecisionPayload().contains("SUBMITTED"));
    }

    @Test
    void should_build_research_response_through_knowledge_facade() {
        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(288L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("这个任务有哪些公开证据？");

        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.RESEARCH)
                .intentType("TASK_RESEARCH")
                .decisionReason("命中了 research 语义")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();

        TaskResponse taskResponse = TaskResponse.builder()
                .id(288L)
                .taskName("统一对话任务")
                .currentStage("信息采集")
                .statusSummary("当前需要补充公开证据")
                .build();

        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(2880L);
            }
            return session;
        });
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(2880L)).thenReturn(Optional.empty());
        when(intentRecognitionService.recognize(eq(request), any(ConversationSession.class), eq(false))).thenReturn(recognitionResult);
        when(modeRouter.route(recognitionResult, 288L)).thenReturn(ConversationMode.RESEARCH);
        when(taskQueryFacade.getTask(288L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(288L)).thenReturn(List.of());
        when(knowledgeRetrievalFacade.retrieveForTask(288L, "这个任务有哪些公开证据？", "conversation"))
                .thenReturn(new KnowledgeRetrievalFacade.RetrievalResultView(
                        List.of("https://docs.example.com"),
                        "仍缺少定价证据",
                        "已检索到公开文档",
                        List.of("TASK-DOC-001")
                ));
        when(taskActionTranslator.buildResearchPreview(
                request.getMessage(),
                288L,
                List.of(),
                List.of("https://docs.example.com")
        )).thenReturn(ConversationResponse.TaskActionPreview.builder()
                .title("研究模式")
                .sourceUrls(List.of("https://docs.example.com"))
                .build());
        when(conversationAgent.composeResearchAnswer(
                request.getMessage(),
                ConversationResponse.TaskActionPreview.builder()
                        .title("研究模式")
                        .sourceUrls(List.of("https://docs.example.com"))
                        .build(),
                List.of(ConversationResponse.RetrievalEvidence.builder()
                        .evidenceId("TASK-DOC-001")
                        .title("知识检索 / SOURCE_URL")
                        .snippet("已检索到公开文档")
                        .sourceCategory("KNOWLEDGE_FACADE")
                        .sourceUrl("https://docs.example.com")
                        .build()),
                "仍缺少定价证据"
        )).thenReturn("已检索到公开文档");
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(2881L);
            return decision;
        });

        ConversationResponse response = conversationService.handleMessage(request);

        assertNotNull(response);
        verify(knowledgeRetrievalFacade).retrieveForTask(288L, "这个任务有哪些公开证据？", "conversation");
    }
}
