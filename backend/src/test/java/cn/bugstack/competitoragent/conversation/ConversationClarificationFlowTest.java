package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.agent.conversation.ConversationAgent;
import cn.bugstack.competitoragent.knowledge.application.KnowledgeRetrievalFacade;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Task 5.5.b 黑盒场景测试。
 * 该用例直接锁定“先结构化澄清，再根据补充槽位继续动作预览”的最小闭环，
 * 避免系统在目标节点缺失时直接猜一个节点并生成高风险动作预览。
 */
@ExtendWith(MockitoExtension.class)
class ConversationClarificationFlowTest {

    @Mock
    private ConversationSessionRepository conversationSessionRepository;

    @Mock
    private IntentDecisionRepository intentDecisionRepository;

    @Mock
    private FormDraftRepository formDraftRepository;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private TaskQueryFacade taskQueryFacade;

    @Mock
    private TaskRuntimeFacade taskRuntimeFacade;

    @Mock
    private KnowledgeRetrievalFacade knowledgeRetrievalFacade;

    @Mock
    private ReportQueryFacade reportQueryFacade;

    private ConversationService conversationService;

    private final AtomicReference<ConversationSession> persistedSession = new AtomicReference<>();
    private final AtomicLong decisionIdSequence = new AtomicLong(9000L);

    @BeforeEach
    void setUp() {
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        when(formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(anyLong())).thenReturn(Optional.empty());
        when(conversationSessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(901L);
            }
            persistedSession.set(session);
            return session;
        });
        when(conversationSessionRepository.findById(901L)).thenAnswer(invocation -> Optional.ofNullable(persistedSession.get()));
        when(intentDecisionRepository.save(any(IntentDecision.class))).thenAnswer(invocation -> {
            IntentDecision decision = invocation.getArgument(0);
            decision.setId(decisionIdSequence.incrementAndGet());
            return decision;
        });

        conversationService = new ConversationService(
                conversationSessionRepository,
                intentDecisionRepository,
                formDraftRepository,
                new IntentRecognitionService(promptTemplateService),
                new ModeRouter(),
                new ClarificationOrchestrator(),
                new FormDraftBuilder(),
                new TaskActionTranslator(promptTemplateService),
                new ConversationAgent(promptTemplateService),
                taskQueryFacade,
                taskRuntimeFacade,
                knowledgeRetrievalFacade,
                reportQueryFacade,
                new ObjectMapper()
        );
    }

    @Test
    void shouldAskStructuredClarificationBeforePreviewingAmbiguousRerunRequest() {
        TaskResponse taskResponse = TaskResponse.builder()
                .id(305L)
                .taskName("Notion AI 报告修订")
                .currentStage("报告改写")
                .statusSummary("当前存在多个可重跑节点，需要先确认目标节点")
                .resumeAdvice("如果你想整体续跑，可以改成明确的恢复请求。")
                .build();

        TaskNodeResponse collectorNode = TaskNodeResponse.builder()
                .nodeName("collect_sources")
                .displayName("信息采集")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.FAILED)
                .executionOrder(1)
                .impactSummary("会重新采集证据并影响后续抽取、分析和写作链路。")
                .rerunActionSummary("适合在证据明显缺失时从采集节点重新开始。")
                .build();

        TaskNodeResponse rewriteNode = TaskNodeResponse.builder()
                .nodeName("rewrite_report")
                .displayName("报告改写")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .executionOrder(3)
                .impactSummary("会重写报告和终审结果，但不重新抓取证据。")
                .rerunActionSummary("适合在证据充分但报告表述需要调整时使用。")
                .build();

        when(taskQueryFacade.getTask(305L)).thenReturn(taskResponse);
        when(taskQueryFacade.getTaskNodes(305L)).thenReturn(List.of(collectorNode, rewriteNode));

        ConversationMessageRequest ambiguousRequest = new ConversationMessageRequest();
        ambiguousRequest.setTaskId(305L);
        ambiguousRequest.setPageType("TASK_DETAIL");
        ambiguousRequest.setMessage("帮我重跑一下");

        ConversationResponse clarificationResponse = conversationService.handleMessage(ambiguousRequest);
        Map<String, Object> clarificationResponseMap = new ObjectMapper().convertValue(
                clarificationResponse,
                new TypeReference<>() {
                });

        assertEquals("CLARIFICATION", clarificationResponse.getMode());
        assertNotNull(clarificationResponseMap.get("clarification"));

        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) clarificationResponseMap.get("clarification");
        assertEquals("MISSING_ACTION_TARGET", clarification.get("clarificationType"));
        assertTrue(String.valueOf(clarification.get("question")).contains("节点"));

        @SuppressWarnings("unchecked")
        List<String> missingSlots = (List<String>) clarification.get("missingSlots");
        assertTrue(missingSlots.contains("targetNodeName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) clarification.get("options");
        assertEquals(2, options.size());
        assertTrue(options.stream().anyMatch(option -> "collect_sources".equals(option.get("optionValue"))));
        assertTrue(options.stream().anyMatch(option -> "rewrite_report".equals(option.get("optionValue"))));

        /**
         * 会话在澄清阶段必须把当前模式和待补充意图挂住，
         * 否则用户下一轮只回答节点名时，系统无法沿用当前上下文继续推进。
         */
        assertEquals("CLARIFICATION", persistedSession.get().getCurrentMode());
        assertTrue(persistedSession.get().getSessionSummary().contains("intent=RERUN_FROM_NODE"));

        ConversationMessageRequest followUpRequest = new ConversationMessageRequest();
        followUpRequest.setSessionId(901L);
        followUpRequest.setMessage("rewrite_report");

        ConversationResponse resolvedResponse = conversationService.handleMessage(followUpRequest);

        assertEquals("TASK_ACTION", resolvedResponse.getMode());
        assertNotNull(resolvedResponse.getTaskActionPreview());
        assertEquals("rewrite_report", resolvedResponse.getTaskActionPreview().getTargetNodeName());
        assertEquals("RERUN_FROM_NODE", resolvedResponse.getIntentDecision().getIntentType());
    }
}
