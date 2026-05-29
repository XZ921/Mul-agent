package cn.bugstack.competitoragent.llm;

/**
 * LLM 调用客户端接口 — 封装大模型调用
 * <p>
 * 第一版实现：LangChain4j + Anthropic Claude
 * 后续可扩展：OpenAI、本地模型等
 */
public interface LlmClient {

    /**
     * 发送 prompt 并返回 LLM 生成的文本
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 返回的文本内容
     * @throws LlmException LLM 调用失败时抛出
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 发送 prompt 并要求返回 JSON 格式
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param responseSchema 期望的 JSON Schema 描述（用于 structured output）
     * @return LLM 返回的 JSON 字符串
     * @throws LlmException LLM 调用失败或返回非 JSON 时抛出
     */
    String chatForJson(String systemPrompt, String userPrompt, String responseSchema);

    /**
     * 返回当前使用的模型名称
     */
    String getModelName();

    /**
     * 返回最近一次调用的 Token 用量
     */
    TokenUsage getLastTokenUsage();
}
