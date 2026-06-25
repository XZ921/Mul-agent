package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.agent.conversation.ConversationAgent;
import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.context.TaskRagContextSummaryFormatter;
import cn.bugstack.competitoragent.knowledge.application.KnowledgeRetrievalFacade;
import cn.bugstack.competitoragent.model.dto.ConversationActionConfirmationRequest;
import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.ConversationSession;
import cn.bugstack.competitoragent.model.entity.FormDraft;
import cn.bugstack.competitoragent.model.entity.IntentDecision;
import cn.bugstack.competitoragent.report.application.ReportQueryFacade;
import cn.bugstack.competitoragent.repository.ConversationSessionRepository;
import cn.bugstack.competitoragent.repository.FormDraftRepository;
import cn.bugstack.competitoragent.repository.IntentDecisionRepository;
import cn.bugstack.competitoragent.task.application.TaskQueryFacade;
import cn.bugstack.competitoragent.task.application.TaskRuntimeFacade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一对话入口应用服务。
 * 它负责编排“会话 -> 意图 -> 模式 -> 草稿 / 动作预览 / 解释结果 -> 审计持久化”这条主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationSessionRepository conversationSessionRepository;
    private final IntentDecisionRepository intentDecisionRepository;
    private final FormDraftRepository formDraftRepository;
    private final IntentRecognitionService intentRecognitionService;
    private final ModeRouter modeRouter;
    private final ClarificationOrchestrator clarificationOrchestrator;
    private final FormDraftBuilder formDraftBuilder;
    private final TaskActionTranslator taskActionTranslator;
    private final ConversationAgent conversationAgent;
    private final TaskQueryFacade taskQueryFacade;
    private final TaskRuntimeFacade taskRuntimeFacade;
    private final KnowledgeRetrievalFacade knowledgeRetrievalFacade;
    private final ReportQueryFacade reportQueryFacade;
    private final ConversationOrchestrationDecisionQueryService orchestrationDecisionQueryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ConversationResponse handleMessage(ConversationMessageRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "conversation request is required");
        }
        ConversationSession session = resolveSession(request);
        TaskResponse taskResponse = null;
        List<TaskNodeResponse> nodeResponses = List.of();
        Long effectiveTaskId = resolveTaskId(request, session);
        if (effectiveTaskId != null) {
            taskResponse = taskQueryFacade.getTask(effectiveTaskId);
            nodeResponses = taskQueryFacade.getTaskNodes(effectiveTaskId);
        }
        ConversationResponse.FormDraftSummary existingDraft = loadDraftSummary(session);

        IntentRecognitionService.RecognitionResult recognitionResult;
        ConversationResponse response;
        if (isConfirmedActionExecution(request)) {
            ConfirmedActionProcessingResult confirmedActionProcessingResult =
                    buildConfirmedActionResponse(request, session, effectiveTaskId, taskResponse);
            recognitionResult = confirmedActionProcessingResult.recognitionResult();
            response = confirmedActionProcessingResult.response();
        } else {
            recognitionResult = intentRecognitionService.recognize(request, session, existingDraft != null);
            ClarificationOrchestrator.ClarificationDecision clarificationDecision =
                    clarificationOrchestrator.resolve(request, session, recognitionResult, effectiveTaskId, nodeResponses);
            ConversationMode mode = clarificationDecision != null && clarificationDecision.isRequired()
                    ? ConversationMode.CLARIFICATION
                    : modeRouter.route(recognitionResult, effectiveTaskId);

            response = switch (mode) {
                case CLARIFICATION -> buildClarificationResponse(request, taskResponse, recognitionResult, clarificationDecision);
                case EXPLAIN -> buildExplainResponse(request, taskResponse, nodeResponses, recognitionResult);
                case TASK_FORM -> buildFormDraftResponse(request, existingDraft, recognitionResult);
                case TASK_ACTION -> buildTaskActionResponse(request, effectiveTaskId, taskResponse, nodeResponses, recognitionResult);
                case RESEARCH -> buildResearchResponse(request, effectiveTaskId, nodeResponses, recognitionResult);
                case CHAT -> buildChatResponse(request, recognitionResult);
            };
        }

        /**
         * 统一对话入口必须返回真实已持久化的会话标识。
         * 这里不能继续信任 request 中的 sessionId，因为首条消息建会话时它本来就是空值。
         */
        response.setSessionId(session.getId());

        IntentDecision savedDecision = saveIntentDecision(session, request, response, recognitionResult);
        if (response.getIntentDecision() == null) {
            response.setIntentDecision(ConversationResponse.IntentDecisionSummary.builder().build());
        }
        response.getIntentDecision().setDecisionId(savedDecision.getId());
        if (response.getTaskActionExecution() != null) {
            response.getTaskActionExecution().setAuditDecisionId(savedDecision.getId());
            response.getTaskActionExecution().setAuditStatus("RECORDED");
        }

        if (response.getFormDraft() != null) {
            FormDraft savedDraft = saveFormDraft(session, response.getFormDraft(), response.getSourceUrls());
            response.getFormDraft().setDraftId(savedDraft.getId());
            session.setActiveFormDraftId(savedDraft.getId());
        }

        updateSession(session, request, response, savedDecision);
        return response;
    }

    private ConversationResponse buildExplainResponse(ConversationMessageRequest request,
                                                      TaskResponse taskResponse,
                                                      List<TaskNodeResponse> nodeResponses,
                                                      IntentRecognitionService.RecognitionResult recognitionResult) {
        TaskNodeResponse focusNode = selectFocusNode(nodeResponses);
        String taskRagContextSummary = resolveTaskRagContextSummary(focusNode, nodeResponses);
        return ConversationResponse.builder()
                .mode(ConversationMode.EXPLAIN.name())
                .answer(conversationAgent.composeExplainAnswer(request.getMessage(), taskResponse, focusNode))
                .currentStage(taskResponse == null ? null : taskResponse.getCurrentStage())
                .statusSummary(taskResponse == null ? null : taskResponse.getStatusSummary())
                .taskRagContextSummary(taskRagContextSummary)
                .sourceUrls(List.of())
                .intentDecision(toIntentSummary(ConversationMode.EXPLAIN, recognitionResult, null))
                .build();
    }

    private ConversationResponse buildClarificationResponse(ConversationMessageRequest request,
                                                            TaskResponse taskResponse,
                                                            IntentRecognitionService.RecognitionResult recognitionResult,
                                                            ClarificationOrchestrator.ClarificationDecision clarificationDecision) {
        ConversationResponse.ClarificationSummary clarificationSummary = clarificationDecision == null
                ? null
                : clarificationDecision.getClarificationSummary();
        return ConversationResponse.builder()
                .mode(ConversationMode.CLARIFICATION.name())
                .answer(conversationAgent.composeClarificationAnswer(request.getMessage(), clarificationSummary))
                .currentStage(taskResponse == null ? null : taskResponse.getCurrentStage())
                .statusSummary(taskResponse == null ? null : taskResponse.getStatusSummary())
                .sourceUrls(List.of())
                .clarification(clarificationSummary)
                .intentDecision(toIntentSummary(ConversationMode.CLARIFICATION, recognitionResult, null))
                .build();
    }

    private ConversationResponse buildFormDraftResponse(ConversationMessageRequest request,
                                                        ConversationResponse.FormDraftSummary existingDraft,
                                                        IntentRecognitionService.RecognitionResult recognitionResult) {
        ConversationResponse.FormDraftSummary draftSummary = formDraftBuilder.buildDraft(request.getMessage(), existingDraft);
        return ConversationResponse.builder()
                .mode(ConversationMode.TASK_FORM.name())
                .answer(conversationAgent.composeFormDraftAnswer(request.getMessage(), draftSummary))
                .formDraft(draftSummary)
                .sourceUrls(List.of())
                .intentDecision(toIntentSummary(ConversationMode.TASK_FORM, recognitionResult, null))
                .build();
    }

    private ConversationResponse buildTaskActionResponse(ConversationMessageRequest request,
                                                         Long taskId,
                                                         TaskResponse taskResponse,
                                                         List<TaskNodeResponse> nodeResponses,
                                                         IntentRecognitionService.RecognitionResult recognitionResult) {
        ConversationOrchestrationDecisionView decisionView = resolveLatestDecision(taskId);
        ConversationResponse.TaskActionPreview preview =
                taskActionTranslator.buildTaskActionPreview(request.getMessage(), taskId, taskResponse, nodeResponses, decisionView);
        String taskRagContextSummary = resolveTaskRagContextSummary(selectFocusNode(nodeResponses), nodeResponses);
        return ConversationResponse.builder()
                .mode(ConversationMode.TASK_ACTION.name())
                .answer(conversationAgent.composeActionPreviewAnswer(request.getMessage(), preview))
                .currentStage(taskResponse == null ? null : taskResponse.getCurrentStage())
                .statusSummary(taskResponse == null ? null : taskResponse.getStatusSummary())
                .taskRagContextSummary(taskRagContextSummary)
                .sourceUrls(preview.getSourceUrls())
                .taskActionPreview(preview)
                .intentDecision(toIntentSummary(ConversationMode.TASK_ACTION, recognitionResult, preview))
                .build();
    }

    private ConversationResponse buildResearchResponse(ConversationMessageRequest request,
                                                       Long taskId,
                                                       List<TaskNodeResponse> nodeResponses,
                                                       IntentRecognitionService.RecognitionResult recognitionResult) {
        ConversationOrchestrationDecisionView decisionView = resolveLatestDecision(taskId);
        KnowledgeRetrievalFacade.RetrievalResultView retrievalResult = retrieveSafely(taskId, request.getMessage());
        List<ConversationResponse.RetrievalEvidence> evidences = toRetrievalEvidences(retrievalResult);
        ConversationResponse.TaskActionPreview preview = taskActionTranslator.buildResearchPreview(
                request.getMessage(),
                taskId,
                nodeResponses,
                retrievalResult.sourceUrls(),
                decisionView
        );
        String taskRagContextSummary = resolveTaskRagContextSummary(selectFocusNode(nodeResponses), nodeResponses);
        return ConversationResponse.builder()
                .mode(ConversationMode.RESEARCH.name())
                .answer(conversationAgent.composeResearchAnswer(
                        request.getMessage(),
                        preview,
                        evidences,
                        retrievalResult.gapSummary()))
                .taskRagContextSummary(taskRagContextSummary)
                .sourceUrls(mergeSourceUrls(retrievalResult.sourceUrls(), preview == null ? null : preview.getSourceUrls()))
                .taskActionPreview(preview)
                .retrievalEvidences(evidences)
                .intentDecision(toIntentSummary(ConversationMode.RESEARCH, recognitionResult, preview))
                .build();
    }

    private ConversationResponse buildChatResponse(ConversationMessageRequest request,
                                                   IntentRecognitionService.RecognitionResult recognitionResult) {
        return ConversationResponse.builder()
                .mode(ConversationMode.CHAT.name())
                .answer(conversationAgent.composeChatAnswer(request.getMessage()))
                .sourceUrls(List.of())
                .intentDecision(toIntentSummary(ConversationMode.CHAT, recognitionResult, null))
                .build();
    }

    /**
     * 确认执行阶段不再依赖自然语言重新识别动作，
     * 而是直接消费上一轮预览产出的 confirmationRequest，保证真正执行的是用户刚刚确认过的对象。
     */
    private ConfirmedActionProcessingResult buildConfirmedActionResponse(ConversationMessageRequest request,
                                                                        ConversationSession session,
                                                                        Long effectiveTaskId,
                                                                        TaskResponse taskResponse) {
        ConversationActionConfirmationRequest confirmationRequest = request.getConfirmationRequest();
        if (confirmationRequest == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "confirmationRequest is required when executeConfirmedAction is true");
        }
        if (session.getLastIntentDecisionId() == null) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "no preview decision is available for confirmation");
        }
        Long resolvedTaskId = effectiveTaskId;
        if (resolvedTaskId == null) {
            throw new BusinessException(ResultCode.PARAM_VALUE_INVALID, "taskId is required for confirmed action execution");
        }

        TaskActionTranslator.TaskActionExecutionPlan executionPlan =
                taskActionTranslator.buildExecutionPlan(confirmationRequest, resolvedTaskId);
        TaskActionExecutionOutcome executionOutcome = executeConfirmedActionSafely(executionPlan);

        String confirmedIntentType = "CONFIRMED_" + firstNonBlank(executionPlan == null ? null : executionPlan.getActionType(), "UNKNOWN");
        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.TASK_ACTION)
                .intentType(confirmedIntentType)
                .decisionReason(executionOutcome.succeeded()
                        ? "用户已确认高风险动作，系统已提交正式执行请求。"
                        : "用户已确认高风险动作，但后端执行提交失败，结果已写入审计。")
                .highRiskAction(true)
                .requiresConfirmation(false)
                .build();

        ConversationResponse.IntentDecisionSummary intentDecisionSummary =
                toIntentSummary(ConversationMode.TASK_ACTION, recognitionResult, null);
        intentDecisionSummary.setRiskLevel(firstNonBlank(confirmationRequest.getRiskLevel(), intentDecisionSummary.getRiskLevel()));
        intentDecisionSummary.setImpactScope(firstNonBlank(confirmationRequest.getImpactScope(), intentDecisionSummary.getImpactScope()));

        ConversationResponse.TaskActionExecutionResult executionResult = ConversationResponse.TaskActionExecutionResult.builder()
                .actionType(executionPlan == null ? null : executionPlan.getActionType())
                .taskId(resolvedTaskId)
                .targetNodeName(executionPlan == null ? null : executionPlan.getTargetNodeName())
                .executionStatus(executionOutcome.executionStatus())
                .executionMessage(executionOutcome.executionMessage())
                .previewDecisionId(session.getLastIntentDecisionId())
                .auditStatus("PENDING_RECORD")
                .build();

        ConversationResponse response = ConversationResponse.builder()
                .mode(ConversationMode.TASK_ACTION.name())
                .answer(executionOutcome.executionMessage())
                .currentStage(taskResponse == null ? null : taskResponse.getCurrentStage())
                .statusSummary(taskResponse == null ? null : taskResponse.getStatusSummary())
                .sourceUrls(List.of())
                .taskActionExecution(executionResult)
                .intentDecision(intentDecisionSummary)
                .build();

        return new ConfirmedActionProcessingResult(response, recognitionResult);
    }

    /**
     * 确认执行属于高风险闭环的最后一步，这里无论成功还是失败都要收口成结构化结果，
     * 避免异常直接向上抛出后，只留下调用栈却没有正式执行审计。
     */
    private TaskActionExecutionOutcome executeConfirmedActionSafely(TaskActionTranslator.TaskActionExecutionPlan executionPlan) {
        if (executionPlan == null || executionPlan.getActionType() == null || executionPlan.getActionType().isBlank()) {
            return TaskActionExecutionOutcome.failed("FAILED", "当前确认对象缺少可执行动作类型。");
        }
        try {
            switch (executionPlan.getActionType()) {
                case "RERUN_NODE", "SUPPLEMENT_EVIDENCE" -> {
                    if (executionPlan.getTargetNodeName() == null || executionPlan.getTargetNodeName().isBlank()) {
                        return TaskActionExecutionOutcome.failed("FAILED", "当前确认对象缺少目标节点，无法提交执行。");
                    }
                    taskRuntimeFacade.rerunFromNode(executionPlan.getTaskId(), executionPlan.getTargetNodeName());
                    return TaskActionExecutionOutcome.succeeded(firstNonBlank(
                            executionPlan.getExecutionMessage(),
                            "系统已提交节点级执行请求。"));
                }
                case "RESUME_TASK" -> {
                    taskRuntimeFacade.resumeTask(executionPlan.getTaskId());
                    return TaskActionExecutionOutcome.succeeded(firstNonBlank(
                            executionPlan.getExecutionMessage(),
                            "系统已提交任务恢复执行请求。"));
                }
                default -> {
                    return TaskActionExecutionOutcome.failed(
                            "FAILED",
                            "当前暂未支持动作 " + executionPlan.getActionType() + " 的正式执行。");
                }
            }
        } catch (BusinessException e) {
            log.warn("confirmed conversation action rejected, actionType={}, taskId={}",
                    executionPlan.getActionType(), executionPlan.getTaskId(), e);
            return TaskActionExecutionOutcome.failed("FAILED", firstNonBlank(e.getMessage(), "动作执行被后端规则拒绝。"));
        } catch (Exception e) {
            log.error("confirmed conversation action failed, actionType={}, taskId={}",
                    executionPlan.getActionType(), executionPlan.getTaskId(), e);
            return TaskActionExecutionOutcome.failed("FAILED", "动作执行提交失败，请稍后重试。");
        }
    }

    /**
     * 研究模式是增强链路，不允许把检索失败误写成“已经给出可靠建议”。
     * 因此这里一旦异常，就降级成空证据 + 明确缺口说明。
     */
    private KnowledgeRetrievalFacade.RetrievalResultView retrieveSafely(Long taskId, String message) {
        try {
            return knowledgeRetrievalFacade.retrieveForTask(taskId, message, "conversation");
        } catch (Exception e) {
            log.warn("conversation research retrieval degraded, taskId={}", taskId, e);
            return new KnowledgeRetrievalFacade.RetrievalResultView(
                    List.of(),
                    "当前检索链路暂时不可用，因此本次只返回安全补证提示。",
                    "",
                    List.of(),
                    List.of()
            );
        }
    }

    private List<ConversationResponse.RetrievalEvidence> toRetrievalEvidences(KnowledgeRetrievalFacade.RetrievalResultView result) {
        if (result == null || result.sourceUrls() == null || result.sourceUrls().isEmpty()) {
            return List.of();
        }
        List<ConversationResponse.RetrievalEvidence> evidences = new ArrayList<>();
        for (String sourceUrl : result.sourceUrls()) {
            if (sourceUrl == null || sourceUrl.isBlank()) {
                continue;
            }
            evidences.add(ConversationResponse.RetrievalEvidence.builder()
                    .evidenceId(result.hitEvidenceIds().isEmpty() ? null : result.hitEvidenceIds().get(0))
                    .title("知识检索 / SOURCE_URL")
                    .snippet(firstNonBlank(result.answer(), result.gapSummary()))
                    .sourceCategory("KNOWLEDGE_FACADE")
                    .sourceUrl(sourceUrl)
                    .build());
        }
        return evidences;
    }

    /**
     * 对话层只读消费最近一次编排决策，不在这里重算编排逻辑。
     * 查询失败时要安静降级到既有预览链路，避免历史脏事件把整个对话入口打断。
     */
    private ConversationOrchestrationDecisionView resolveLatestDecision(Long taskId) {
        try {
            return orchestrationDecisionQueryService.findLatestDecision(taskId).orElse(null);
        } catch (Exception e) {
            log.warn("conversation orchestration decision lookup degraded, taskId={}", taskId, e);
            return null;
        }
    }

    private List<String> mergeSourceUrls(List<String> primary, List<String> secondary) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (primary != null) {
            for (String sourceUrl : primary) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    merged.add(sourceUrl.trim());
                }
            }
        }
        if (secondary != null) {
            for (String sourceUrl : secondary) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    merged.add(sourceUrl.trim());
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private ConversationSession resolveSession(ConversationMessageRequest request) {
        if (request.getSessionId() != null) {
            ConversationSession session = conversationSessionRepository.findById(request.getSessionId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PARAM_VALUE_INVALID,
                            "conversation session not found: " + request.getSessionId()));
            if (request.getTaskId() != null) {
                session.setTaskId(request.getTaskId());
            }
            if (request.getReportId() != null) {
                session.setReportId(request.getReportId());
            }
            /**
             * follow-up message 允许不重复携带 pageType。
             * 这里必须优先保留已有会话上下文，避免把 TASK_DETAIL / REPORT 等入口误重置成 GLOBAL。
             */
            session.setPageType(resolvePageType(request, session));
            return session;
        }
        ConversationSession session = ConversationSession.builder()
                .taskId(request.getTaskId())
                .reportId(request.getReportId())
                .pageType(resolvePageType(request, null))
                .build();
        return conversationSessionRepository.save(session);
    }

    private ConversationResponse.FormDraftSummary loadDraftSummary(ConversationSession session) {
        if (session == null) {
            return null;
        }
        if (session.getActiveFormDraftId() != null) {
            return formDraftRepository.findById(session.getActiveFormDraftId())
                    .map(this::readDraftSummary)
                    .orElse(null);
        }
        return formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(session.getId())
                .map(this::readDraftSummary)
                .orElse(null);
    }

    private FormDraft saveFormDraft(ConversationSession session,
                                    ConversationResponse.FormDraftSummary draftSummary,
                                    List<String> sourceUrls) {
        FormDraft draft = session.getActiveFormDraftId() == null
                ? formDraftRepository.findTopByConversationSessionIdOrderByUpdatedAtDesc(session.getId()).orElse(null)
                : formDraftRepository.findById(session.getActiveFormDraftId()).orElse(null);
        if (draft == null) {
            draft = FormDraft.builder()
                    .conversationSessionId(session.getId())
                    .taskId(session.getTaskId())
                    .build();
        }
        draft.setTaskId(session.getTaskId());
        draft.setDraftPayload(writeJson(draftSummary));
        draft.setChangeSummary(draftSummary.getChangeSummary());
        draft.setPreviewSummary(draftSummary.getPreviewSummary());
        draft.setSourceUrls(sourceUrls == null ? List.of() : sourceUrls);
        return formDraftRepository.save(draft);
    }

    private IntentDecision saveIntentDecision(ConversationSession session,
                                              ConversationMessageRequest request,
                                              ConversationResponse response,
                                              IntentRecognitionService.RecognitionResult recognitionResult) {
        ConversationResponse.IntentDecisionSummary intentDecision = response.getIntentDecision();
        IntentDecision decision = IntentDecision.builder()
                .conversationSessionId(session.getId())
                .taskId(resolveTaskId(request, session))
                .reportId(resolveReportId(request, session))
                .pageType(resolvePageType(request, session))
                .mode(response.getMode())
                .intentType(recognitionResult.getIntentType())
                .userMessage(request.getMessage())
                .decisionReason(recognitionResult.getDecisionReason())
                .decisionPayload(writeJson(response))
                .highRiskAction(intentDecision != null && Boolean.TRUE.equals(intentDecision.getHighRiskAction()))
                .requiresConfirmation(intentDecision != null && Boolean.TRUE.equals(intentDecision.getRequiresConfirmation()))
                .riskLevel(intentDecision == null ? ConversationSafetyPolicy.RISK_LOW
                        : firstNonBlank(intentDecision.getRiskLevel(), ConversationSafetyPolicy.RISK_LOW))
                .impactScope(intentDecision == null ? ConversationSafetyPolicy.IMPACT_NONE
                        : firstNonBlank(intentDecision.getImpactScope(), ConversationSafetyPolicy.IMPACT_NONE))
                /**
                 * 确认对象单独落库，后续确认链路与审计回放都可以直接复用，
                 * 不需要再从整包 decisionPayload 里做脆弱的 JSON 路径提取。
                 */
                .confirmationRequestPayload(intentDecision == null || intentDecision.getConfirmationRequest() == null
                        ? null
                        : writeJson(intentDecision.getConfirmationRequest()))
                .sourceUrls(response.getSourceUrls())
                .build();
        return intentDecisionRepository.save(decision);
    }

    private void updateSession(ConversationSession session,
                               ConversationMessageRequest request,
                               ConversationResponse response,
                               IntentDecision decision) {
        session.setTaskId(resolveTaskId(request, session));
        session.setReportId(resolveReportId(request, session));
        session.setPageType(resolvePageType(request, session));
        session.setCurrentMode(response.getMode());
        session.setLatestUserMessage(request.getMessage());
        session.setLatestAssistantMessage(response.getAnswer());
        session.setLastIntentDecisionId(decision.getId());
        session.setSessionSummary(buildSessionSummary(response));
        conversationSessionRepository.save(session);
    }

    private String buildSessionSummary(ConversationResponse response) {
        StringBuilder summary = new StringBuilder("mode=")
                .append(response.getMode())
                .append(", intent=")
                .append(response.getIntentDecision() == null
                        ? "UNKNOWN"
                        : firstNonBlank(response.getIntentDecision().getIntentType(), "UNKNOWN"))
                .append(", statusSummary=")
                .append(firstNonBlank(response.getStatusSummary(), "无"))
                .append(", sourceUrlCount=")
                .append(response.getSourceUrls() == null ? 0 : response.getSourceUrls().size());
        if (response.getTaskActionExecution() != null) {
            summary.append(", executionStatus=")
                    .append(firstNonBlank(response.getTaskActionExecution().getExecutionStatus(), "UNKNOWN"));
        }
        if (response.getClarification() != null) {
            summary.append(", clarificationType=")
                    .append(firstNonBlank(response.getClarification().getClarificationType(), "UNKNOWN"));
            if (response.getClarification().getMissingSlots() != null
                    && !response.getClarification().getMissingSlots().isEmpty()) {
                summary.append(", missingSlots=")
                        .append(String.join("|", response.getClarification().getMissingSlots()));
            }
        }
        return summary.toString();
    }

    private TaskNodeResponse selectFocusNode(List<TaskNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return null;
        }
        return nodeResponses.stream()
                .filter(node -> node != null && node.getStatus() != null
                        && ("WAITING_INTERVENTION".equals(node.getStatus().name())
                        || "WAITING_RETRY".equals(node.getStatus().name())
                        || "FAILED".equals(node.getStatus().name())
                        || "PAUSED".equals(node.getStatus().name())))
                .findFirst()
                .orElse(nodeResponses.get(0));
    }

    /**
     * 对话解释场景优先复用焦点节点的 Task RAG 摘要；
     * 如果焦点节点没有，则回退到当前任务中最后一个带 taskRagContext 的节点，
     * 这样能稳定回答“哪些来自复用记忆，哪些来自当前任务”。
     */
    private String resolveTaskRagContextSummary(TaskNodeResponse focusNode, List<TaskNodeResponse> nodeResponses) {
        String rawContext = extractTaskRagContext(focusNode);
        if (rawContext == null || rawContext.isBlank()) {
            rawContext = findLatestTaskRagContext(nodeResponses);
        }
        return TaskRagContextSummaryFormatter.format(rawContext);
    }

    private String findLatestTaskRagContext(List<TaskNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return null;
        }
        return nodeResponses.stream()
                .filter(node -> node != null)
                .sorted((left, right) -> Integer.compare(right.getExecutionOrder(), left.getExecutionOrder()))
                .map(this::extractTaskRagContext)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * taskRagContext 目前保存在节点 outputData 中。
     * 这里容错解析，避免单个节点输出结构异常时把整条对话解释链路打断。
     */
    private String extractTaskRagContext(TaskNodeResponse nodeResponse) {
        if (nodeResponse == null || nodeResponse.getOutputData() == null || nodeResponse.getOutputData().isBlank()) {
            return null;
        }
        JsonNode output = readJson(nodeResponse.getOutputData());
        return output == null ? null : output.path("taskRagContext").asText(null);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("read conversation task rag context failed", e);
            return null;
        }
    }

    private ConversationResponse.IntentDecisionSummary toIntentSummary(ConversationMode mode,
                                                                      IntentRecognitionService.RecognitionResult recognitionResult,
                                                                      ConversationResponse.TaskActionPreview preview) {
        ConversationSafetyPolicy safetyPolicy = ConversationSafetyPolicy.from(mode, recognitionResult, preview);
        return ConversationResponse.IntentDecisionSummary.builder()
                .mode(mode.name())
                .intentType(recognitionResult.getIntentType())
                .decisionReason(recognitionResult.getDecisionReason())
                .highRiskAction(safetyPolicy.isHighRiskAction())
                .requiresConfirmation(safetyPolicy.isRequiresConfirmation())
                .riskLevel(safetyPolicy.getRiskLevel())
                .impactScope(safetyPolicy.getImpactScope())
                .confirmationRequest(safetyPolicy.getConfirmationRequest())
                .build();
    }

    private ConversationResponse.FormDraftSummary readDraftSummary(FormDraft formDraft) {
        if (formDraft == null || formDraft.getDraftPayload() == null || formDraft.getDraftPayload().isBlank()) {
            return null;
        }
        try {
            ConversationResponse.FormDraftSummary summary =
                    objectMapper.readValue(formDraft.getDraftPayload(), ConversationResponse.FormDraftSummary.class);
            summary.setDraftId(formDraft.getId());
            return summary;
        } catch (JsonProcessingException e) {
            log.warn("read form draft payload failed, draftId={}", formDraft.getId(), e);
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "conversation payload serialize failed", e);
        }
    }

    private Long resolveTaskId(ConversationMessageRequest request, ConversationSession session) {
        return request.getTaskId() != null ? request.getTaskId() : session.getTaskId();
    }

    private Long resolveReportId(ConversationMessageRequest request, ConversationSession session) {
        return request.getReportId() != null ? request.getReportId() : session.getReportId();
    }

    /**
     * 对话入口允许首条消息显式声明 pageType，后续消息则复用既有会话上下文。
     * 只有“请求没有给、会话里也没有”时，才真正回落到 GLOBAL。
     */
    private String resolvePageType(ConversationMessageRequest request, ConversationSession session) {
        if (request.getPageType() != null && !request.getPageType().isBlank()) {
            return normalizePageType(request.getPageType());
        }
        if (session != null && session.getPageType() != null && !session.getPageType().isBlank()) {
            return normalizePageType(session.getPageType());
        }
        return "GLOBAL";
    }

    private String normalizePageType(String pageType) {
        return pageType == null || pageType.isBlank() ? "GLOBAL" : pageType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private boolean isConfirmedActionExecution(ConversationMessageRequest request) {
        return request != null
                && Boolean.TRUE.equals(request.getExecuteConfirmedAction())
                && request.getConfirmationRequest() != null;
    }

    private record ConfirmedActionProcessingResult(
            ConversationResponse response,
            IntentRecognitionService.RecognitionResult recognitionResult) {
    }

    private record TaskActionExecutionOutcome(
            boolean succeeded,
            String executionStatus,
            String executionMessage) {

        private static TaskActionExecutionOutcome succeeded(String executionMessage) {
            return new TaskActionExecutionOutcome(true, "SUBMITTED", executionMessage);
        }

        private static TaskActionExecutionOutcome failed(String executionStatus, String executionMessage) {
            return new TaskActionExecutionOutcome(false, executionStatus, executionMessage);
        }
    }
}
