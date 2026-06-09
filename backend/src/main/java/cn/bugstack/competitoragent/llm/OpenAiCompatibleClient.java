package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 兼容客户端。
 * <p>
 * 在正式网关治理层引入后，它不再直接暴露给业务层，
 * 而是降级为 ProviderRegistry 下面的一个 Provider 适配器。
 */
@Slf4j
@Component
public class OpenAiCompatibleClient implements ModelProvider {

    private final AiProviderProperties aiProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, ChatLanguageModel> chatModelCache = new ConcurrentHashMap<>();

    public OpenAiCompatibleClient(AiProviderProperties aiProps) {
        this.aiProps = aiProps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(aiProps.getTimeoutSeconds()))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getAdapterType() {
        return "openai-compatible";
    }

    @Override
    public ProviderInvocationResult<String> chat(ProviderInvocationRequest request) {
        try {
            Response<AiMessage> response = resolveChatModel(request).generate(
                    SystemMessage.from(request.getSystemPrompt()),
                    UserMessage.from(request.getUserPrompt())
            );
            return ProviderInvocationResult.<String>builder()
                    .providerKey(request.getProviderKey())
                    .modelName(resolveModelName(request))
                    .tokenUsage(toTokenUsage(response.tokenUsage()))
                    .payload(response.content().text())
                    .build();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), resolveProviderErrorCode(e), e);
        }
    }

    @Override
    public ProviderInvocationResult<List<Float>> embed(ProviderInvocationRequest request) {
        if (!supportsCapability(request, "embedding")) {
            throw new LlmException("当前 AI 提供商未配置 embedding endpoint");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolveModelName(request));
            payload.put("input", request.getText());
            JsonNode root = postJson(
                    capabilityUrl(request, "embedding"),
                    payload,
                    Duration.ofSeconds(aiProps.getEmbeddingTimeoutSeconds())
            );
            JsonNode embeddingNode = extractEmbeddingNode(root);
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new LlmException("embedding response missing vector payload");
            }

            List<Float> vector = new ArrayList<>();
            for (JsonNode valueNode : embeddingNode) {
                vector.add((float) valueNode.asDouble());
            }
            return ProviderInvocationResult.<List<Float>>builder()
                    .providerKey(request.getProviderKey())
                    .modelName(resolveModelName(request))
                    .tokenUsage(extractUsage(root))
                    .payload(vector)
                    .build();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LlmException("embedding request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ProviderInvocationResult<List<RerankClient.RerankRecord>> rerank(ProviderInvocationRequest request) {
        if (!supportsCapability(request, "rerank")) {
            throw new LlmException("当前 AI 提供商未配置 rerank endpoint");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", request.getQuery());
            payload.put("documents", request.getDocuments() == null ? List.of() : request.getDocuments());
            JsonNode root = postJson(
                    capabilityUrl(request, "rerank"),
                    payload,
                    Duration.ofSeconds(aiProps.getRerankTimeoutSeconds())
            );

            JsonNode resultsNode = root.path("results");
            if ((!resultsNode.isArray() || resultsNode.isEmpty())
                    && root.path("output").path("results").isArray()) {
                resultsNode = root.path("output").path("results");
            }
            if (!resultsNode.isArray()) {
                throw new LlmException("rerank response missing results payload");
            }

            List<RerankClient.RerankRecord> records = new ArrayList<>();
            for (JsonNode item : resultsNode) {
                int index = item.path("index").asInt(-1);
                double score = item.has("relevance_score")
                        ? item.path("relevance_score").asDouble()
                        : item.path("score").asDouble();
                if (index >= 0) {
                    records.add(new RerankClient.RerankRecord(index, score));
                }
            }
            return ProviderInvocationResult.<List<RerankClient.RerankRecord>>builder()
                    .providerKey(request.getProviderKey())
                    .modelName(resolveModelName(request))
                    .tokenUsage(extractUsage(root))
                    .payload(records)
                    .build();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LlmException("rerank request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 统一封装 OpenAI 兼容能力请求，确保 embedding / rerank 共享同一套鉴权和超时策略。
     */
    private JsonNode postJson(String endpointUrl,
                              Map<String, Object> payload,
                              Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(timeout == null ? Duration.ofSeconds(aiProps.getTimeoutSeconds()) : timeout)
                .header("Content-Type", "application/json");

        String apiKey = providerApiKey(endpointUrl);
        if (apiKey != null && !apiKey.isBlank() && !"not-needed".equals(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String providerErrorCode = "HTTP_" + response.statusCode();
            throw new LlmException(
                    "AI endpoint responded with status " + response.statusCode() + extractErrorSuffix(response.body()),
                    providerErrorCode
            );
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * 不同供应商的 embedding 响应结构可能略有差异，这里做最小兼容归一化。
     */
    private JsonNode extractEmbeddingNode(JsonNode root) {
        if (root.path("data").isArray() && !root.path("data").isEmpty()) {
            return root.path("data").get(0).path("embedding");
        }
        if (root.path("output").path("embeddings").isArray() && !root.path("output").path("embeddings").isEmpty()) {
            return root.path("output").path("embeddings").get(0);
        }
        return null;
    }

    /**
     * 聊天模型缓存按 provider + modelName 维度复用，
     * 避免网关收口后每次调用都重新构建底层 SDK 客户端。
     */
    private ChatLanguageModel resolveChatModel(ProviderInvocationRequest request) {
        String cacheKey = request.getProviderKey() + "::" + resolveModelName(request);
        return chatModelCache.computeIfAbsent(cacheKey, key -> OpenAiChatModel.builder()
                .baseUrl(resolveBaseUrl(request))
                .apiKey(resolveApiKey(request))
                .modelName(resolveModelName(request))
                .maxTokens(aiProps.getMaxTokens())
                .temperature(aiProps.getTemperature())
                .timeout(Duration.ofSeconds(aiProps.getTimeoutSeconds()))
                .logRequests(true)
                .logResponses(true)
                .build());
    }

    /**
     * baseUrl 需要去掉 chat endpoint 的路径部分，让 LangChain4j 正常拼接兼容接口。
     */
    private String resolveBaseUrl(ProviderInvocationRequest request) {
        String chatEndpoint = capabilityUrl(request, "chat");
        String configuredPath = request.getProviderConfig().getEndpoints() == null
                ? null
                : request.getProviderConfig().getEndpoints().get("chat");
        if (configuredPath == null || configuredPath.isBlank()) {
            return request.getProviderConfig().getUrl();
        }
        return chatEndpoint.replace(configuredPath, "");
    }

    private String resolveApiKey(ProviderInvocationRequest request) {
        String apiKey = request.getProviderConfig() == null ? null : request.getProviderConfig().getApiKey();
        return apiKey != null && !apiKey.isBlank() ? apiKey : "not-needed";
    }

    private String resolveModelName(ProviderInvocationRequest request) {
        if (request != null && request.getModelName() != null && !request.getModelName().isBlank()) {
            return request.getModelName();
        }
        return aiProps.getModelName();
    }

    private boolean supportsCapability(ProviderInvocationRequest request, String capability) {
        return request != null
                && request.getProviderConfig() != null
                && request.getProviderConfig().getEndpoints() != null
                && request.getProviderConfig().getEndpoints().get(capability) != null
                && !request.getProviderConfig().getEndpoints().get(capability).isBlank();
    }

    private String capabilityUrl(ProviderInvocationRequest request, String capability) {
        if (!supportsCapability(request, capability)) {
            throw new IllegalStateException("当前 AI 提供商未配置 " + capability + " endpoint");
        }
        return request.getProviderConfig().getUrl() + request.getProviderConfig().getEndpoints().get(capability);
    }

    /**
     * embedding / rerank 返回里若提供 usage 字段，则同步抽取成统一 TokenUsage。
     */
    private TokenUsage extractUsage(JsonNode root) {
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return TokenUsage.builder().build();
        }
        return TokenUsage.builder()
                .inputTokens(usageNode.path("prompt_tokens").asInt(usageNode.path("input_tokens").asInt(0)))
                .outputTokens(usageNode.path("completion_tokens").asInt(usageNode.path("output_tokens").asInt(0)))
                .totalTokens(usageNode.path("total_tokens").asInt(
                        usageNode.path("prompt_tokens").asInt(usageNode.path("input_tokens").asInt(0))
                                + usageNode.path("completion_tokens").asInt(usageNode.path("output_tokens").asInt(0))
                ))
                .build();
    }

    private TokenUsage toTokenUsage(dev.langchain4j.model.output.TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return TokenUsage.builder().build();
        }
        return TokenUsage.builder()
                .inputTokens(tokenUsage.inputTokenCount() == null ? 0 : tokenUsage.inputTokenCount())
                .outputTokens(tokenUsage.outputTokenCount() == null ? 0 : tokenUsage.outputTokenCount())
                .totalTokens(tokenUsage.totalTokenCount() == null ? 0 : tokenUsage.totalTokenCount())
                .build();
    }

    /**
     * postJson 只收到 endpointUrl，因此这里通过配置反查出对应的 apiKey。
     * 这样即便后续存在多 provider 故障转移，也不会误用 activeProvider 的密钥。
     */
    private String providerApiKey(String endpointUrl) {
        if (aiProps.getProviders() == null || aiProps.getProviders().isEmpty()) {
            return "not-needed";
        }
        for (Map.Entry<String, AiProviderProperties.ProviderConfig> entry : aiProps.getProviders().entrySet()) {
            AiProviderProperties.ProviderConfig providerConfig = entry.getValue();
            if (providerConfig != null && providerConfig.getUrl() != null && endpointUrl.startsWith(providerConfig.getUrl())) {
                String apiKey = providerConfig.getApiKey();
                return apiKey != null && !apiKey.isBlank() ? apiKey : "not-needed";
            }
        }
        return "not-needed";
    }

    private String resolveProviderErrorCode(Exception exception) {
        if (exception instanceof LlmException llmException && llmException.getProviderErrorCode() != null) {
            return llmException.getProviderErrorCode();
        }
        return null;
    }

    /**
     * 统一抽取外部接口返回的 error.code / error.message，避免错误摘要只剩 HTTP 状态码。
     */
    private String extractErrorSuffix(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            String message = errorNode.path("message").asText(null);
            if (message == null || message.isBlank()) {
                message = root.path("message").asText(null);
            }
            return message == null || message.isBlank() ? "" : "：" + message;
        } catch (Exception ignored) {
            return "";
        }
    }
}
