package cn.bugstack.competitoragent.orchestration;

/**
 * 一次 Orchestrator LLM 决策调用所需的完整 Prompt 契约。
 * 三段文本保持不可变，便于后续计算 hash、审计和重放对比。
 */
public record OrchestrationDecisionPrompt(
        String systemPrompt,
        String userPrompt,
        String responseSchema
) {

    public OrchestrationDecisionPrompt {
        systemPrompt = requireText(systemPrompt, "systemPrompt");
        userPrompt = requireText(userPrompt, "userPrompt");
        responseSchema = requireText(responseSchema, "responseSchema");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }
}
