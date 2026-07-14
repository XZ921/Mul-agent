package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.ModelChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 使用 LLM 生成 Orchestrator 候选决策的纯 POJO。
 * 本类只协调 Prompt、模型调用、严格解析和一次 parse retry，不依赖 Rule Brain、Policy、Executor 或 Trace。
 */
public class LlmOrchestratorDecisionBrain implements OrchestratorDecisionBrain {

    private final OrchestrationDecisionPromptBuilder promptBuilder;
    private final OrchestrationDecisionModelInvoker modelInvoker;
    private final OrchestrationDecisionResponseParser parser;
    private final OrchestrationDecisionRetryPromptBuilder retryPromptBuilder;
    private final DecisionPolicyRuleSet normalizedRuleSet;
    private final OrchestratorDecisionProperties properties;
    private final LongSupplier nanoTimeSource;

    public LlmOrchestratorDecisionBrain(
            OrchestrationDecisionPromptBuilder promptBuilder,
            OrchestrationDecisionModelInvoker modelInvoker,
            OrchestrationDecisionResponseParser parser,
            OrchestrationDecisionRetryPromptBuilder retryPromptBuilder,
            DecisionPolicyRuleSet normalizedRuleSet,
            OrchestratorDecisionProperties properties) {
        this(promptBuilder, modelInvoker, parser, retryPromptBuilder,
                normalizedRuleSet, properties, System::nanoTime);
    }

    /** package-private 时钟入口只用于稳定验证首次调用与 parse retry 共享同一个 deadline。 */
    LlmOrchestratorDecisionBrain(
            OrchestrationDecisionPromptBuilder promptBuilder,
            OrchestrationDecisionModelInvoker modelInvoker,
            OrchestrationDecisionResponseParser parser,
            OrchestrationDecisionRetryPromptBuilder retryPromptBuilder,
            DecisionPolicyRuleSet normalizedRuleSet,
            OrchestratorDecisionProperties properties,
            LongSupplier nanoTimeSource) {
        this.promptBuilder = requireDependency(promptBuilder, "promptBuilder");
        this.modelInvoker = requireDependency(modelInvoker, "modelInvoker");
        this.parser = requireDependency(parser, "parser");
        this.retryPromptBuilder = requireDependency(retryPromptBuilder, "retryPromptBuilder");
        this.normalizedRuleSet = requireDependency(normalizedRuleSet, "normalizedRuleSet");
        this.properties = requireDependency(properties, "properties");
        this.nanoTimeSource = requireDependency(nanoTimeSource, "nanoTimeSource");
        properties.validate();
    }

    /**
     * 每次调用只使用方法局部 attempt 状态，保证同一个 Brain 实例并发执行时不会串用响应、hash 或错误事实。
     */
    @Override
    public List<OrchestrationDecision> decide(OrchestrationContext normalizedContext) {
        requireContext(normalizedContext);
        long deadlineNanos = calculateDeadline(nanoTimeSource.getAsLong(), properties.getLlmTimeoutMs());
        ModelChatOptions options = new ModelChatOptions(
                properties.getModelTemperature(),
                properties.getLlmTimeoutMs());
        OrchestrationDecisionPrompt currentPrompt = promptBuilder.build(normalizedContext, normalizedRuleSet);
        List<LlmOrchestratorDecisionFailure.Attempt> attempts = new ArrayList<>();
        int modelInvocationCount = 0;

        while (true) {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0L) {
                throw failure(
                        LlmOrchestratorFailureType.LLM_TIMEOUT,
                        null,
                        parseRetryCount(modelInvocationCount),
                        attempts);
            }

            String promptHash = OrchestrationDecisionHashing.hashPrompt(currentPrompt);
            OrchestrationDecisionModelInvoker.ModelResponse modelResponse;
            try {
                modelResponse = modelInvoker.invoke(currentPrompt, normalizedContext, options, remainingNanos);
                modelInvocationCount++;
            } catch (OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException exception) {
                modelInvocationCount++;
                attempts.add(emptyAttempt(modelInvocationCount, promptHash));
                throw failure(
                        LlmOrchestratorFailureType.LLM_TIMEOUT,
                        null,
                        parseRetryCount(modelInvocationCount),
                        attempts);
            } catch (OrchestrationDecisionModelInvoker.ModelInvocationException exception) {
                if (exception.invocationSubmitted()) {
                    modelInvocationCount++;
                    attempts.add(emptyAttempt(modelInvocationCount, promptHash));
                }
                throw failure(
                        LlmOrchestratorFailureType.LLM_ERROR,
                        exception.providerErrorCode(),
                        parseRetryCount(modelInvocationCount),
                        attempts);
            }

            String responseHash = OrchestrationDecisionHashing.hashResponse(modelResponse.rawResponse());
            OrchestrationDecisionParseResult parseResult = parser.parse(
                    modelResponse.rawResponse(),
                    normalizedContext,
                    OrchestrationDecisionOrigin.LLM_PRIMARY);
            if (parseResult.successful()) {
                return attachSuccessMetadata(
                        parseResult.decisions(),
                        modelResponse.modelName(),
                        options,
                        promptHash,
                        responseHash,
                        parseRetryCount(modelInvocationCount));
            }

            attempts.add(new LlmOrchestratorDecisionFailure.Attempt(
                    modelInvocationCount,
                    promptHash,
                    responseHash,
                    parseResult.issues(),
                    parseResult.discardedSourceUrls()));
            int completedParseRetries = parseRetryCount(modelInvocationCount);
            if (completedParseRetries >= properties.getMaxParseRetries()) {
                throw failure(
                        LlmOrchestratorFailureType.PARSE_ERROR,
                        null,
                        completedParseRetries,
                        attempts);
            }

            // 只允许严格 Parser failure 触发纠正重调；模型异常、timeout 和 discarded-only success 均不会进入这里。
            currentPrompt = retryPromptBuilder.build(
                    currentPrompt,
                    completedParseRetries + 1,
                    parseResult.issues());
        }
    }

    private List<OrchestrationDecision> attachSuccessMetadata(
            List<OrchestrationDecision> decisions,
            String modelName,
            ModelChatOptions options,
            String promptHash,
            String responseHash,
            int parseRetryCount) {
        OrchestratorDecisionMetadata metadata = OrchestratorDecisionMetadata.builder()
                .modelName(modelName)
                .temperature(options.temperature())
                .promptHash(promptHash)
                .llmResponseHash(responseHash)
                .parseRetryCount(parseRetryCount)
                .fallbackUsed(false)
                .build();
        List<OrchestrationDecision> result = new ArrayList<>();
        for (OrchestrationDecision decision : decisions) {
            if (decision == null) {
                throw new IllegalStateException("Parser 成功结果不能包含 null decision");
            }
            // toBuilder 只覆盖 LLM 成功事实，Parser 已校验的来源、证据与 inputRefs 必须完整保留。
            result.add(decision.toBuilder()
                    .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                    .decisionMetadata(metadata)
                    .build()
                    .normalized());
        }
        return List.copyOf(result);
    }

    private LlmOrchestratorDecisionFailure.Attempt emptyAttempt(int attemptNumber, String promptHash) {
        return new LlmOrchestratorDecisionFailure.Attempt(
                attemptNumber, promptHash, null, List.of(), List.of());
    }

    private LlmOrchestratorDecisionException failure(
            LlmOrchestratorFailureType type,
            String providerErrorCode,
            int parseRetryCount,
            List<LlmOrchestratorDecisionFailure.Attempt> attempts) {
        // 最终 typed exception 不携带内部异常 cause，避免 Provider message 间接泄漏 raw response 或业务正文。
        return new LlmOrchestratorDecisionException(new LlmOrchestratorDecisionFailure(
                type, providerErrorCode, parseRetryCount, attempts));
    }

    private int parseRetryCount(int modelInvocationCount) {
        return Math.max(0, modelInvocationCount - 1);
    }

    private long remainingNanos(long deadlineNanos) {
        return deadlineNanos - nanoTimeSource.getAsLong();
    }

    private long calculateDeadline(long startNanos, long timeoutMillis) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long deadline = startNanos + timeoutNanos;
        return deadline < startNanos ? Long.MAX_VALUE : deadline;
    }

    private void requireContext(OrchestrationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("normalizedContext 不能为空");
        }
        if (context.getTaskId() == null) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (context.getTriggerNodeName() == null || context.getTriggerNodeName().isBlank()) {
            throw new IllegalArgumentException("triggerNodeName 不能为空");
        }
    }

    private static <T> T requireDependency(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
