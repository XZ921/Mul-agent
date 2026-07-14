package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    @Test
    void shouldApplyRequestScopedTemperatureHardTimeoutAndDisableSdkRetries() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(aiProviderProperties());
        Method resolveChatModel = OpenAiCompatibleClient.class
                .getDeclaredMethod("resolveChatModel", ProviderInvocationRequest.class);
        resolveChatModel.setAccessible(true);
        ProviderInvocationRequest orchestratorRequest = ProviderInvocationRequest.builder()
                .providerKey("deepseek")
                .providerConfig(providerConfig())
                .capability(AiCapability.CHAT)
                .temperature(0.0d)
                .timeoutMillis(4000L)
                .systemPrompt("system")
                .userPrompt("user")
                .build();

        Object orchestratorModel = resolveChatModel.invoke(client, orchestratorRequest);
        Object defaultModel = resolveChatModel.invoke(client, orchestratorRequest.toBuilder()
                .temperature(null)
                .timeoutMillis(null)
                .build());

        assertThat(orchestratorModel).isNotSameAs(defaultModel);
        assertThat(readField(orchestratorModel, "temperature")).isEqualTo(0.0d);
        assertThat(readField(orchestratorModel, "maxRetries")).isEqualTo(0);

        Object openAiClient = readField(orchestratorModel, "client");
        OkHttpClient okHttpClient = (OkHttpClient) readField(openAiClient, "okHttpClient");
        assertThat(okHttpClient.callTimeoutMillis()).isEqualTo(4000);
        assertThat(okHttpClient.connectTimeoutMillis()).isEqualTo(4000);
        assertThat(okHttpClient.readTimeoutMillis()).isEqualTo(4000);
        assertThat(okHttpClient.writeTimeoutMillis()).isEqualTo(4000);

        assertThat(readField(defaultModel, "temperature")).isEqualTo(0.1d);
        Object defaultClient = readField(defaultModel, "client");
        OkHttpClient defaultHttpClient = (OkHttpClient) readField(defaultClient, "okHttpClient");
        assertThat(defaultHttpClient.callTimeoutMillis()).isEqualTo(30000);
    }

    @Test
    void shouldFailFastWhenEmbeddingHttpFutureNeverCompletes() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        AiProviderProperties properties = aiProviderProperties();
        properties.setEmbeddingTimeoutSeconds(1);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, httpClient, null);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> client.embed(ProviderInvocationRequest.builder()
                        .providerKey("deepseek")
                        .providerConfig(providerConfigWithEmbedding())
                        .capability(AiCapability.EMBEDDING)
                        .modelName("text-embedding-3-small")
                        .text("pricing and documentation")
                        .build()))
                        .isInstanceOf(LlmException.class)
                        .hasMessageContaining("timed out"));

        assertThat(httpClient.cancelled()).isTrue();
        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
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

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private AiProviderProperties.ProviderConfig providerConfig() {
        AiProviderProperties.ProviderConfig providerConfig = new AiProviderProperties.ProviderConfig();
        providerConfig.setAdapterType("openai-compatible");
        providerConfig.setUrl("https://api.deepseek.com");
        providerConfig.setApiKey("test-key");
        providerConfig.setEndpoints(Map.of("chat", "/chat/completions"));
        return providerConfig;
    }

    private AiProviderProperties.ProviderConfig providerConfigWithEmbedding() {
        AiProviderProperties.ProviderConfig providerConfig = providerConfig();
        providerConfig.setEndpoints(Map.of(
                "chat", "/chat/completions",
                "embedding", "/embeddings"
        ));
        return providerConfig;
    }
}
