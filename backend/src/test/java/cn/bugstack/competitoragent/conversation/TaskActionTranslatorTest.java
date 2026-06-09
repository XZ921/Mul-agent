package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskActionTranslatorTest {

    @Test
    void shouldDescribeRerunPreviewAsExecutableInsideUnifiedEntry() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);

        ConversationResponse.TaskActionPreview preview = translator.buildTaskActionPreview(
                "从 rewrite_report 开始重跑",
                24L,
                TaskResponse.builder()
                        .id(24L)
                        .statusSummary("当前任务等待重跑确认")
                        .build(),
                List.of(TaskNodeResponse.builder()
                        .nodeName("rewrite_report")
                        .displayName("报告改写")
                        .rerunActionSummary("系统会从报告改写节点重新组织后续执行链路。")
                        .impactSummary("将影响当前节点和下游改写结果。")
                        .build())
        );

        assertTrue(preview.getConfirmationHint().contains("统一入口"));
        assertTrue(preview.getConfirmationHint().contains("确认后"));
        assertFalse(preview.getConfirmationHint().contains("既有任务 API"));
    }

    @Test
    void shouldDescribeResearchPreviewAsConfirmableInsideUnifiedEntry() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);

        ConversationResponse.TaskActionPreview preview = translator.buildResearchPreview(
                "继续补搜 pricing 证据",
                24L,
                List.of(TaskNodeResponse.builder()
                        .nodeName("collect_sources_web")
                        .impactSummary("补源后会带动采集与抽取链路重新组织。")
                        .build()),
                List.of("https://www.notion.so/pricing")
        );

        assertTrue(preview.getConfirmationHint().contains("统一入口"));
        assertTrue(preview.getConfirmationHint().contains("确认后"));
        assertFalse(preview.getConfirmationHint().contains("既有任务 API"));
    }
}
