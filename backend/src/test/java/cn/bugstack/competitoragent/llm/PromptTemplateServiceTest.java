package cn.bugstack.competitoragent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateServiceTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService(new ObjectMapper());

    @Test
    void shouldRenderDedicatedTaskRagContextTemplate() {
        // 任务级检索上下文需要有独立模板入口，避免每个 Agent 自己重复拼装说明文案。
        String rendered = promptTemplateService.render("task-rag-context", Map.of(
                "taskRagContext", "检索查询：Notion AI pricing"
        ));

        assertTrue(rendered.contains("检索查询：Notion AI pricing"));
        assertTrue(rendered.contains("任务级检索上下文"));
    }

    @Test
    void shouldInjectTaskRagContextIntoAnalyzerTemplate() {
        // analyzer 模板必须真正消费 taskRagContext，而不是只在单测 mock 变量里存在。
        String rendered = promptTemplateService.render("analyzer", Map.of(
                "subjectProduct", "Our Product",
                "analysisDimensions", "定价,定位",
                "taskRagContext", "检索查询：Notion AI pricing",
                "competitorData", "[]"
        ));

        assertTrue(rendered.contains("检索查询：Notion AI pricing"));
    }

    @Test
    void shouldInjectRuntimeStatusContractIntoConversationPromptTemplates() {
        // Task 4.6 要求统一对话入口相关 Prompt 内置阶段状态输出格式，避免对话链路自定义另一套运行汇报口径。
        String rendered = promptTemplateService.render("conversation-agent", Map.of(
                "userMessage", "这个任务为什么停在这里了？",
                "contextSummary", "任务当前停在报告改写阶段。"
        ));

        assertTrue(rendered.contains("当前阶段："));
        assertTrue(rendered.contains("[x] 信息采集：已完成"));
        assertTrue(rendered.contains("[ ] 数据分析：执行中"));
        assertTrue(rendered.contains("[ ] 报告撰写：待执行"));
        assertTrue(rendered.contains("这个任务为什么停在这里了？"));
    }

    @Test
    void shouldExposeStructuredConversationContractsAcrossPromptTemplates() {
        // Task 5.5.d 要求对话 Agent、意图路由和动作翻译 Prompt
        // 都显式声明结构化字段，避免后端只能依赖自然语言猜测字段含义。
        String conversationPrompt = promptTemplateService.render("conversation-agent", Map.of(
                "userMessage", "确认后帮我从 rewrite_report 继续处理",
                "contextSummary", "任务当前停在报告改写阶段。"
        ));
        String intentRouterPrompt = promptTemplateService.render("intent-router", Map.of(
                "userMessage", "如果要补证和重跑你建议怎么做？",
                "contextSummary", "当前存在任务上下文，但用户目标可能冲突。"
        ));
        String taskActionTranslatorPrompt = promptTemplateService.render("task-action-translator", Map.of(
                "userMessage", "从 rewrite_report 重跑",
                "actionContext", "taskId=305, taskStatus=WAITING_INTERVENTION"
        ));

        assertTrue(conversationPrompt.contains("sourceUrls"));
        assertTrue(conversationPrompt.contains("intentDecision"));
        assertTrue(conversationPrompt.contains("confirmationRequest"));
        assertTrue(conversationPrompt.contains("taskActionExecution"));
        assertTrue(conversationPrompt.contains("clarification"));

        assertTrue(intentRouterPrompt.contains("\"mode\""));
        assertTrue(intentRouterPrompt.contains("\"intentType\""));
        assertTrue(intentRouterPrompt.contains("\"needsClarification\""));
        assertTrue(intentRouterPrompt.contains("\"clarificationQuestion\""));
        assertTrue(intentRouterPrompt.contains("\"candidateIntentTypes\""));

        assertTrue(taskActionTranslatorPrompt.contains("\"taskActionPreview\""));
        assertTrue(taskActionTranslatorPrompt.contains("\"actionType\""));
        assertTrue(taskActionTranslatorPrompt.contains("\"impactScope\""));
        assertTrue(taskActionTranslatorPrompt.contains("\"confirmationRequest\""));
        assertTrue(taskActionTranslatorPrompt.contains("\"sourceUrls\""));
    }

    @Test
    void shouldKeepEnglishSearchTemplatesAfterLoadingYamlQueries() {
        String rendered = String.join(" | ", promptTemplateService.buildSearchQueries(
                "Notion AI",
                "DOCS",
                "docs.notion.so"
        ));

        assertTrue(rendered.contains("Notion AI"));
        assertTrue(rendered.toLowerCase().contains("documentation"));
    }
}
