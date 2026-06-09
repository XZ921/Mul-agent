package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.5 意图识别规则回归测试。
 * 这里专门锁定“继续补搜官方文档证据”这一类研究补证表达，
 * 避免系统仅因为句子里出现“继续”就把补证意图误判成高风险动作冲突。
 */
class IntentRecognitionServiceTest {

    @Test
    void shouldKeepResearchIntentWhenContinuationVerbOnlyDescribesEvidenceSupplement() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        IntentRecognitionService service = new IntentRecognitionService(promptTemplateService);

        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(24L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("继续补搜官方文档证据");

        IntentRecognitionService.RecognitionResult result = service.recognize(request, null, false);

        assertEquals(ConversationMode.RESEARCH, result.getMode());
        assertEquals("SUPPLEMENT_EVIDENCE", result.getIntentType());
    }

    @Test
    void shouldDescribeTaskActionAsUnifiedEntryPreviewAndConfirmationFlow() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        IntentRecognitionService service = new IntentRecognitionService(promptTemplateService);

        ConversationMessageRequest request = new ConversationMessageRequest();
        request.setTaskId(24L);
        request.setPageType("TASK_DETAIL");
        request.setMessage("从 rewrite_report 开始重跑");

        IntentRecognitionService.RecognitionResult result = service.recognize(request, null, false);

        assertEquals(ConversationMode.TASK_ACTION, result.getMode());
        assertEquals("RERUN_FROM_NODE", result.getIntentType());
        assertTrue(result.getDecisionReason().contains("统一入口"));
        assertFalse(result.getDecisionReason().contains("Phase 4"));
    }
}
