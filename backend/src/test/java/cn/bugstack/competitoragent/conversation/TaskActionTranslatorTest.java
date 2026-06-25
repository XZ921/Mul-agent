package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void shouldBuildWaitForHumanPreviewFromOrchestrationDecision() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);

        ConversationOrchestrationDecisionView decisionView = ConversationOrchestrationDecisionView.builder()
                .decisionId("od-24-analyzer-human")
                .taskId(24L)
                .triggerNodeName("analyze_competitors")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("analyze_competitors")
                .affectedScope("CURRENT_NODE_ONLY")
                .reason("Analyzer 发现缺少来源，必须人工介入。")
                .requiresHumanIntervention(true)
                .requiresConfirmation(false)
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of("https://docs.example.com/analyze"))
                .build();

        ConversationResponse.TaskActionPreview preview = translator.buildTaskActionPreview(
                "系统建议我下一步做什么",
                24L,
                TaskResponse.builder().id(24L).build(),
                List.of(),
                decisionView
        );

        assertTrue(preview.getTitle().contains("人工"));
        assertFalse(Boolean.TRUE.equals(preview.getRequiresConfirmation()));
        assertFalse(Boolean.TRUE.equals(preview.getExecutable()));
        assertTrue(preview.getActionSummary().contains("缺少来源"));
        assertNotNull(preview.getOrchestrationDecision());
        assertTrue(preview.getOrchestrationDecision().getDecisionId().contains("od-24"));
    }

    @Test
    void shouldKeepOrchestrationDecisionWhenResearchFallsBackAfterWaitForHuman() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(anyString(), anyMap())).thenReturn("");
        TaskActionTranslator translator = new TaskActionTranslator(promptTemplateService);

        ConversationOrchestrationDecisionView decisionView = ConversationOrchestrationDecisionView.builder()
                .decisionId("od-24-analyzer-human")
                .taskId(24L)
                .triggerNodeName("analyze_competitors")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("collect_sources_web")
                .reason("Analyzer 已要求人工介入，但仍建议补充更多公开证据。")
                .requiresHumanIntervention(true)
                .requiresConfirmation(false)
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of("https://docs.example.com/analyze"))
                .build();

        ConversationResponse.TaskActionPreview preview = translator.buildResearchPreview(
                "继续补搜 pricing 证据",
                24L,
                List.of(TaskNodeResponse.builder()
                        .nodeName("collect_sources_web")
                        .impactSummary("补源后会带动采集与抽取链路重新组织。")
                        .build()),
                List.of("https://www.notion.so/pricing"),
                decisionView
        );

        assertTrue("SUPPLEMENT_EVIDENCE".equals(preview.getActionType()));
        assertNotNull(preview.getOrchestrationDecision());
        assertTrue("WAIT_FOR_HUMAN".equals(preview.getOrchestrationDecision().getDecisionType()));
        assertTrue(preview.getSourceUrls().contains("https://www.notion.so/pricing"));
        assertTrue(preview.getSourceUrls().contains("https://docs.example.com/analyze"));
    }
}
