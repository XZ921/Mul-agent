package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationSafetyPolicyTest {

    @Test
    void shouldNotGenerateConfirmationRequestForWaitForHumanPreview() {
        IntentRecognitionService.RecognitionResult recognitionResult = IntentRecognitionService.RecognitionResult.builder()
                .mode(ConversationMode.TASK_ACTION)
                .intentType("SUPPLEMENT_EVIDENCE")
                .decisionReason("用户希望系统建议下一步")
                .highRiskAction(true)
                .requiresConfirmation(true)
                .build();

        ConversationResponse.TaskActionPreview preview = ConversationResponse.TaskActionPreview.builder()
                .actionType("WAIT_FOR_HUMAN")
                .taskId(88L)
                .title("等待人工介入")
                .actionSummary("当前缺少足够来源，系统只展示编排建议，不生成可执行确认。")
                .riskLevel("HIGH")
                .requiresConfirmation(false)
                .confirmationHint("请先人工处理来源缺口。")
                .executable(false)
                .build();

        ConversationSafetyPolicy policy =
                ConversationSafetyPolicy.from(ConversationMode.TASK_ACTION, recognitionResult, preview);

        assertFalse(policy.isRequiresConfirmation());
        assertNull(policy.getConfirmationRequest());
    }
}
