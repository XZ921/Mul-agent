package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI 兼容 LLM 客户端 — 支持 ollama / bailian / deepseek / siliconflow 等多提供商。
 */
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private final ChatLanguageModel model;
    private final String modelName;
    private TokenUsage lastTokenUsage;

    public OpenAiCompatibleClient(AiProviderProperties aiProps) {
        String baseUrl = aiProps.getBaseUrl();
        String apiKey = aiProps.getApiKey();
        this.modelName = aiProps.getModelName();

        this.model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(aiProps.getMaxTokens())
                .temperature(aiProps.getTemperature())
                .timeout(Duration.ofSeconds(aiProps.getTimeoutSeconds()))
                .logRequests(true)
                .logResponses(true)
                .build();

        log.info("AI 客户端初始化完成, provider={}, model={}, baseUrl={}",
                aiProps.getActiveProvider(), modelName, baseUrl);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        try {
            String response = model.generate(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            ).content().text();
            lastTokenUsage = TokenUsage.builder()
                    .inputTokens(0).outputTokens(0).totalTokens(0).build();
            return response;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatForJson(String systemPrompt, String userPrompt, String responseSchema) {
        String enhancedSystem = systemPrompt
                + "\n\n【重要】请只输出 JSON，不要包含 markdown 代码块标记或其他解释文字。\n"
                + "期望的 JSON 结构: " + responseSchema;
        return chat(enhancedSystem, userPrompt);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public TokenUsage getLastTokenUsage() {
        return lastTokenUsage != null ? lastTokenUsage : TokenUsage.builder().build();
    }
}
