package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在专用有界执行器中调用同步 ModelGateway。
 * 本类只负责执行边界、总预算等待、取消和 ThreadLocal 上下文传播，不负责解析响应或业务重试。
 */
@Component
public class OrchestrationDecisionModelInvoker {

    static final String EXECUTOR_SATURATED = "ORCHESTRATOR_EXECUTOR_SATURATED";
    static final String EXECUTOR_SHUTDOWN = "ORCHESTRATOR_EXECUTOR_SHUTDOWN";
    static final String CALLER_INTERRUPTED = "ORCHESTRATOR_CALLER_INTERRUPTED";
    static final String INVOCATION_FAILED = "ORCHESTRATOR_MODEL_INVOCATION_FAILED";
    private static final String THREAD_NAME_PREFIX = "orchestrator-llm-";

    private final ModelGateway modelGateway;
    private final ThreadPoolExecutor executor;

    @Autowired
    public OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                              OrchestratorDecisionProperties properties) {
        this(modelGateway, properties, createExecutor(properties));
    }

    /**
     * 测试可以注入可观测的专用执行器；执行器生命周期仍由当前 invoker 单一负责。
     */
    OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                      OrchestratorDecisionProperties properties,
                                      ThreadPoolExecutor executor) {
        if (modelGateway == null) {
            throw new IllegalArgumentException("modelGateway 不能为空");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为空");
        }
        properties.validate();
        if (executor == null) {
            throw new IllegalArgumentException("executor 不能为空");
        }
        this.modelGateway = modelGateway;
        this.executor = executor;
    }

    /**
     * 用 caller 传入的剩余纳秒等待本次模型调用，确保 parse retry 与首次调用共享同一个总 deadline。
     * Provider hard timeout 由 options 中的稳定配置负责，remainingNanos 只控制当前 caller 等待时间。
     */
    public ModelResponse invoke(OrchestrationDecisionPrompt prompt,
                                OrchestrationContext normalizedContext,
                                ModelChatOptions options,
                                long remainingNanos) {
        requireInvocationContract(prompt, normalizedContext, options);
        if (remainingNanos <= 0L) {
            throw new ModelInvocationTimeoutException();
        }

        ModelInvocationContextHolder.ModelInvocationContext callerContext = ModelInvocationContextHolder.get();
        String traceId = callerContext == null ? null : callerContext.traceId();
        Future<ModelResponse> future;
        try {
            future = executor.submit(() -> ModelInvocationContextHolder.withContext(
                    normalizedContext.getTaskId(),
                    normalizedContext.getTriggerNodeName(),
                    traceId,
                    () -> invokeGateway(prompt, options)));
        } catch (RejectedExecutionException exception) {
            String errorCode = executor.isShutdown() ? EXECUTOR_SHUTDOWN : EXECUTOR_SATURATED;
            throw new ModelInvocationException(errorCode, false, exception);
        }

        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // cancel(true) 只是尽力中断 worker；底层 HTTP 最终存活上限仍由 Provider hard timeout 保证。
            future.cancel(true);
            throw new ModelInvocationTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelInvocationException(CALLER_INTERRUPTED, true, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LlmException llmException) {
                throw new ModelInvocationException(
                        normalizeProviderErrorCode(llmException.getProviderErrorCode()),
                        true,
                        llmException);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw new ModelInvocationException(INVOCATION_FAILED, true, runtimeException);
            }
            throw new ModelInvocationException(INVOCATION_FAILED, true, exception);
        }
    }

    private ModelResponse invokeGateway(OrchestrationDecisionPrompt prompt, ModelChatOptions options) {
        try {
            String rawResponse = modelGateway.chatForJson(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    prompt.responseSchema(),
                    options);
            // ModelGateway 的成功快照基于 ThreadLocal，必须在同一个 worker 内立即读取。
            return new ModelResponse(rawResponse, modelGateway.getModelName());
        } catch (LlmException exception) {
            // 外部模型错误不在 Brain/Invoker 级重试，由 ModelGateway 的既有路由策略统一拥有 Provider retry。
            throw exception;
        }
    }

    private void requireInvocationContract(OrchestrationDecisionPrompt prompt,
                                           OrchestrationContext context,
                                           ModelChatOptions options) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        if (context == null) {
            throw new IllegalArgumentException("normalizedContext 不能为空");
        }
        if (context.getTaskId() == null) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (context.getTriggerNodeName() == null || context.getTriggerNodeName().isBlank()) {
            throw new IllegalArgumentException("triggerNodeName 不能为空");
        }
        if (options == null) {
            throw new IllegalArgumentException("options 不能为空");
        }
    }

    private String normalizeProviderErrorCode(String providerErrorCode) {
        return providerErrorCode == null || providerErrorCode.isBlank()
                ? INVOCATION_FAILED
                : providerErrorCode.trim();
    }

    private static ThreadPoolExecutor createExecutor(OrchestratorDecisionProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为空");
        }
        properties.validate();
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                properties.getExecutorThreads(),
                properties.getExecutorThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getExecutorQueueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 关闭专用执行器并向仍在运行的模型调用发出尽力中断请求。 */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    public record ModelResponse(String rawResponse, String modelName) {
    }

    static final class ModelInvocationTimeoutException extends RuntimeException {
        ModelInvocationTimeoutException() {
            super("Orchestrator 模型调用超过总 deadline");
        }
    }

    static final class ModelInvocationException extends RuntimeException {
        private final String providerErrorCode;
        private final boolean invocationSubmitted;

        ModelInvocationException(String providerErrorCode, boolean invocationSubmitted, Throwable cause) {
            super("Orchestrator 模型调用失败: " + providerErrorCode, cause);
            this.providerErrorCode = providerErrorCode;
            this.invocationSubmitted = invocationSubmitted;
        }

        String providerErrorCode() {
            return providerErrorCode;
        }

        boolean invocationSubmitted() {
            return invocationSubmitted;
        }
    }
}
