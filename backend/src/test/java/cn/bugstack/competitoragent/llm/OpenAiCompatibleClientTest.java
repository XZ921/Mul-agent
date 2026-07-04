package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleClientTest {

    @Test
    void shouldBuildChatModelWithoutBodyLoggingInterceptorsForLongRunningJsonCalls() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(aiProviderProperties());
        Method resolveChatModel = OpenAiCompatibleClient.class
                .getDeclaredMethod("resolveChatModel", ProviderInvocationRequest.class);
        resolveChatModel.setAccessible(true);

        Object chatModel = resolveChatModel.invoke(client, ProviderInvocationRequest.builder()
                .providerKey("deepseek")
                .providerConfig(providerConfig())
                .capability(AiCapability.CHAT)
                .systemPrompt("system")
                .userPrompt("user")
                .build());

        Field openAiClientField = chatModel.getClass().getDeclaredField("client");
        openAiClientField.setAccessible(true);
        Object openAiClient = openAiClientField.get(chatModel);

        Field okHttpClientField = openAiClient.getClass().getDeclaredField("okHttpClient");
        okHttpClientField.setAccessible(true);
        OkHttpClient okHttpClient = (OkHttpClient) okHttpClientField.get(openAiClient);
        List<String> interceptorNames = okHttpClient.interceptors().stream()
                .map(interceptor -> interceptor.getClass().getSimpleName())
                .toList();

        assertThat(interceptorNames)
                .doesNotContain("RequestLoggingInterceptor", "ResponseLoggingInterceptor");
    }

    private AiProviderProperties aiProviderProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setActiveProvider("deepseek");
        properties.setModelName("deepseek-chat");
        properties.setMaxTokens(1024);
        properties.setTemperature(0.1D);
        properties.setTimeoutSeconds(30);
        properties.setProviders(Map.of("deepseek", providerConfig()));
        return properties;
    }

    private AiProviderProperties.ProviderConfig providerConfig() {
        AiProviderProperties.ProviderConfig providerConfig = new AiProviderProperties.ProviderConfig();
        providerConfig.setAdapterType("openai-compatible");
        providerConfig.setUrl("https://api.deepseek.com");
        providerConfig.setApiKey("test-key");
        providerConfig.setEndpoints(Map.of("chat", "/chat/completions"));
        return providerConfig;
    }
}
