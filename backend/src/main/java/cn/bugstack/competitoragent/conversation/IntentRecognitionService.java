package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import cn.bugstack.competitoragent.model.entity.ConversationSession;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 意图识别服务。
 * 当前阶段优先采用规则式判断：
 * 1. 先把单入口拆成稳定模式；
 * 2. 再把每次判断理由写入审计记录；
 * 3. 避免在缺少审计边界时，把路由责任完全交给黑盒模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionService {

    private static final List<String> EXPLAIN_KEYWORDS = List.of("为什么", "停在", "卡在", "当前在做什么", "进展");
    private static final List<String> RESEARCH_KEYWORDS = List.of("补搜", "补证", "补充证据", "pricing", "补充资料");
    private static final List<String> ACTION_KEYWORDS = List.of("重跑", "恢复", "继续", "从", "rerun", "resume");
    private static final List<String> FORM_KEYWORDS = List.of("竞品分析", "帮我做", "创建任务", "重点改成", "重点放在");

    private final PromptTemplateService promptTemplateService;

    public RecognitionResult recognize(ConversationMessageRequest request,
                                       ConversationSession session,
                                       boolean hasDraft) {
        String message = safe(request == null ? null : request.getMessage());
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        boolean containsExplain = containsAny(normalizedMessage, EXPLAIN_KEYWORDS);
        boolean containsResearch = containsAny(normalizedMessage, RESEARCH_KEYWORDS);
        boolean containsAction = containsAny(normalizedMessage, ACTION_KEYWORDS);
        /**
         * “继续补搜 / 继续补证”里的“继续”只是在描述补证动作的延续，
         * 不是在表达“恢复任务执行”这一类高风险控制意图。
         * 如果这里不做去歧义，研究补证会被误判成“研究 + 动作冲突”，
         * 统一对话入口就会过早落到澄清链路，破坏正常的补证预览体验。
         */
        if (shouldIgnoreContinuationAsTaskAction(normalizedMessage, containsResearch)) {
            containsAction = false;
        }
        boolean taskContextAvailable = hasTaskContext(request, session);
        // Prompt 预览只用于审计日志，模板缺失或 Mock 未配置时不应该反向打断意图识别主链路。
        String promptPreview = safe(promptTemplateService.render("intent-router", Map.of(
                "userMessage", message,
                "contextSummary", buildContextSummary(request, session, hasDraft)
        )));
        log.debug("intent router prompt prepared, length={}", promptPreview.length());

        if (containsAction && containsResearch) {
            return RecognitionResult.builder()
                    .mode(ConversationMode.CLARIFICATION)
                    .intentType("INTENT_CONFLICT")
                    .decisionReason("同一条消息同时命中了任务动作和补证语义，必须先澄清本轮目标。")
                    .needsClarification(true)
                    .clarificationType("INTENT_CONFLICT")
                    .clarificationReason("系统无法安全判断你是想执行任务动作，还是先补充证据。")
                    .clarificationQuestion("你是想先补充证据，还是想执行任务动作？")
                    .candidateIntentTypes(List.of("SUPPLEMENT_EVIDENCE", "RERUN_FROM_NODE", "RESUME_TASK"))
                    .build();
        }
        if ((containsExplain || containsResearch || containsAction) && !taskContextAvailable) {
            String question = containsAction
                    ? "我需要先知道你想操作哪个任务，才能继续给出动作预览。"
                    : containsResearch
                    ? "我需要先知道你想补证哪个任务，才能继续组织检索和证据回指。"
                    : "我需要先知道你想问的是哪个任务，才能继续解释当前进展。";
            return RecognitionResult.builder()
                    .mode(ConversationMode.CLARIFICATION)
                    .intentType(resolvePrimaryIntentType(containsExplain, containsResearch, containsAction, normalizedMessage))
                    .decisionReason("消息命中了任务相关语义，但当前缺少任务上下文，不能直接进入解释或高风险动作链路。")
                    .needsClarification(true)
                    .clarificationType("MISSING_TASK_CONTEXT")
                    .clarificationReason("当前对话缺少 taskId，上下文不足，继续执行会导致误解释或误预览。")
                    .clarificationQuestion(question)
                    .missingSlots(List.of("taskId"))
                    .candidateIntentTypes(resolveCandidateIntentTypes(containsExplain, containsResearch, containsAction, normalizedMessage))
                    .build();
        }
        if (isClarificationFollowUp(session) && taskContextAvailable) {
            String pendingIntent = extractSessionSummaryValue(session == null ? null : session.getSessionSummary(), "intent");
            if ("RERUN_FROM_NODE".equalsIgnoreCase(pendingIntent)) {
                return RecognitionResult.builder()
                        .mode(ConversationMode.TASK_ACTION)
                        .intentType("RERUN_FROM_NODE")
                        .decisionReason("沿用上一轮澄清会话的重跑意图，等待用户补充目标节点。")
                        .highRiskAction(true)
                        .requiresConfirmation(true)
                        .build();
            }
            if ("RESUME_TASK".equalsIgnoreCase(pendingIntent)) {
                return RecognitionResult.builder()
                        .mode(ConversationMode.TASK_ACTION)
                        .intentType("RESUME_TASK")
                        .decisionReason("沿用上一轮澄清会话的续跑意图，等待用户补充执行边界。")
                        .highRiskAction(true)
                        .requiresConfirmation(true)
                        .build();
            }
            if ("SUPPLEMENT_EVIDENCE".equalsIgnoreCase(pendingIntent)) {
                return RecognitionResult.builder()
                        .mode(ConversationMode.RESEARCH)
                        .intentType("SUPPLEMENT_EVIDENCE")
                        .decisionReason("沿用上一轮澄清会话的补证意图，继续进入统一入口内的研究预览与确认链路。")
                        .highRiskAction(true)
                        .requiresConfirmation(true)
                        .build();
            }
        }

        if (containsResearch) {
            return RecognitionResult.builder()
                    .mode(ConversationMode.RESEARCH)
                    .intentType("SUPPLEMENT_EVIDENCE")
                    .decisionReason("消息包含补证 / 补搜关键词，系统会先在统一入口内给出补证预览、证据回指与确认提示。")
                    .highRiskAction(true)
                    .requiresConfirmation(true)
                    .build();
        }
        if (containsExplain && taskContextAvailable) {
            return RecognitionResult.builder()
                    .mode(ConversationMode.EXPLAIN)
                    .intentType("TASK_STATUS_EXPLANATION")
                    .decisionReason("消息包含任务状态解释关键词，且当前存在任务上下文。")
                    .highRiskAction(false)
                    .requiresConfirmation(false)
                    .build();
        }
        if (containsAction && taskContextAvailable) {
            String intentType = normalizedMessage.contains("重跑") ? "RERUN_FROM_NODE" : "RESUME_TASK";
            return RecognitionResult.builder()
                    .mode(ConversationMode.TASK_ACTION)
                    .intentType(intentType)
                    .decisionReason("消息包含任务动作关键词，系统会先在统一入口内给出动作预览、影响范围和确认提示。")
                    .highRiskAction(true)
                    .requiresConfirmation(true)
                    .build();
        }
        if (hasDraft
                || isTaskCreatePage(request, session)
                || containsAny(normalizedMessage, FORM_KEYWORDS)) {
            String intentType = hasDraft || normalizedMessage.contains("重点改成")
                    ? "UPDATE_TASK_DRAFT"
                    : "BUILD_TASK_DRAFT";
            return RecognitionResult.builder()
                    .mode(ConversationMode.TASK_FORM)
                    .intentType(intentType)
                    .decisionReason("消息命中了任务创建 / 草稿修改语义，应优先走表单草稿链路。")
                    .highRiskAction(false)
                    .requiresConfirmation(false)
                    .build();
        }
        return RecognitionResult.builder()
                .mode(ConversationMode.CHAT)
                .intentType("GENERAL_CHAT")
                .decisionReason("未命中更高优先级的任务解释、草稿或动作语义，降级到普通解释对话。")
                .highRiskAction(false)
                .requiresConfirmation(false)
                .build();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldIgnoreContinuationAsTaskAction(String normalizedMessage, boolean containsResearch) {
        if (!containsResearch || normalizedMessage == null || normalizedMessage.isBlank()) {
            return false;
        }
        if (!normalizedMessage.contains("继续")) {
            return false;
        }
        return !normalizedMessage.contains("重跑")
                && !normalizedMessage.contains("恢复")
                && !normalizedMessage.contains("rerun")
                && !normalizedMessage.contains("resume")
                && !normalizedMessage.contains("从");
    }

    private boolean hasTaskContext(ConversationMessageRequest request, ConversationSession session) {
        return (request != null && request.getTaskId() != null)
                || (session != null && session.getTaskId() != null);
    }

    private boolean isTaskCreatePage(ConversationMessageRequest request, ConversationSession session) {
        String pageType = request != null ? request.getPageType() : null;
        if (pageType == null && session != null) {
            pageType = session.getPageType();
        }
        return "TASK_CREATE".equalsIgnoreCase(safe(pageType));
    }

    private String buildContextSummary(ConversationMessageRequest request,
                                       ConversationSession session,
                                       boolean hasDraft) {
        return "pageType=" + safe(request == null ? null : request.getPageType())
                + ", taskId=" + (request == null ? null : request.getTaskId())
                + ", reportId=" + (request == null ? null : request.getReportId())
                + ", sessionId=" + (session == null ? null : session.getId())
                + ", hasDraft=" + hasDraft;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isClarificationFollowUp(ConversationSession session) {
        return session != null
                && "CLARIFICATION".equalsIgnoreCase(safe(session.getCurrentMode()))
                && session.getSessionSummary() != null
                && session.getSessionSummary().contains("intent=");
    }

    private String extractSessionSummaryValue(String sessionSummary, String key) {
        if (sessionSummary == null || sessionSummary.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        for (String token : sessionSummary.split(",")) {
            String trimmed = token == null ? "" : token.trim();
            String prefix = key + "=";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String resolvePrimaryIntentType(boolean containsExplain,
                                            boolean containsResearch,
                                            boolean containsAction,
                                            String normalizedMessage) {
        if (containsResearch) {
            return "SUPPLEMENT_EVIDENCE";
        }
        if (containsAction) {
            return normalizedMessage.contains("重跑") ? "RERUN_FROM_NODE" : "RESUME_TASK";
        }
        if (containsExplain) {
            return "TASK_STATUS_EXPLANATION";
        }
        return "GENERAL_CHAT";
    }

    private List<String> resolveCandidateIntentTypes(boolean containsExplain,
                                                     boolean containsResearch,
                                                     boolean containsAction,
                                                     String normalizedMessage) {
        List<String> candidateIntentTypes = new ArrayList<>();
        if (containsExplain) {
            candidateIntentTypes.add("TASK_STATUS_EXPLANATION");
        }
        if (containsResearch) {
            candidateIntentTypes.add("SUPPLEMENT_EVIDENCE");
        }
        if (containsAction) {
            candidateIntentTypes.add(normalizedMessage.contains("重跑") ? "RERUN_FROM_NODE" : "RESUME_TASK");
        }
        return candidateIntentTypes;
    }

    @Data
    @Builder
    public static class RecognitionResult {
        private ConversationMode mode;
        private String intentType;
        private String decisionReason;
        private boolean highRiskAction;
        private boolean requiresConfirmation;
        private boolean needsClarification;
        private String clarificationType;
        private String clarificationReason;
        private String clarificationQuestion;
        @Builder.Default
        private List<String> missingSlots = List.of();
        @Builder.Default
        private List<String> candidateIntentTypes = List.of();
    }
}
