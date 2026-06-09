package cn.bugstack.competitoragent.conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模式路由器。
 * 统一对话入口对外只有一个接口，对内由这里做最终模式收口。
 */
@Slf4j
@Service
public class ModeRouter {

    public ConversationMode route(IntentRecognitionService.RecognitionResult recognitionResult,
                                  Long taskId) {
        if (recognitionResult == null || recognitionResult.getMode() == null) {
            return ConversationMode.CHAT;
        }
        if (recognitionResult.isNeedsClarification()) {
            return ConversationMode.CLARIFICATION;
        }
        if ((recognitionResult.getMode() == ConversationMode.RESEARCH
                || recognitionResult.getMode() == ConversationMode.TASK_ACTION
                || recognitionResult.getMode() == ConversationMode.EXPLAIN)
                && taskId == null) {
            log.debug("task related mode rerouted to clarification because taskId is missing");
            return ConversationMode.CLARIFICATION;
        }
        return recognitionResult.getMode();
    }
}
