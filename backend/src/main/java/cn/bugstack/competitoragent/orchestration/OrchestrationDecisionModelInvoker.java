package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final OrchestrationShadowBudgetGate shadowBudgetGate;
    private final ThreadPoolExecutor executor;

    @Autowired
    public OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                              OrchestratorDecisionProperties properties,
                                              OrchestrationShadowBudgetGate shadowBudgetGate) {
        this(modelGateway, properties, shadowBudgetGate, createExecutor(properties));
    }

    /** Task 05 兼容构造器固定使用 no-op gate，不改变既有单元测试与手工装配语义。 */
    public OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                              OrchestratorDecisionProperties properties) {
        this(modelGateway, properties, OrchestrationShadowBudgetGate.noop(), createExecutor(properties));
    }

    /**
     * 测试可以注入可观测的专用执行器；执行器生命周期仍由当前 invoker 单一负责。
     */
    OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                      OrchestratorDecisionProperties properties,
                                      ThreadPoolExecutor executor) {
        this(modelGateway, properties, OrchestrationShadowBudgetGate.noop(), executor);
    }

    /** 测试可同时注入可观测 gate 与有界执行器，验证预算拒绝发生在提交之前。 */
    OrchestrationDecisionModelInvoker(ModelGateway modelGateway,
                                      OrchestratorDecisionProperties properties,
                                      OrchestrationShadowBudgetGate shadowBudgetGate,
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
        if (shadowBudgetGate == null) {
            throw new IllegalArgumentException("shadowBudgetGate 不能为空");
        }
        this.modelGateway = modelGateway;
        this.shadowBudgetGate = shadowBudgetGate;
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
        ModelInvocationContextHolder.ModelInvocationContext effectiveCallerContext = callerContext == null
                ? new ModelInvocationContextHolder.ModelInvocationContext(
                normalizedContext.getTaskId(), normalizedContext.getTriggerNodeName(), null)
                : callerContext;

        // Shadow 配额在 caller thread 同步判定。拒绝时不会创建 Future，也不会占用 executor worker/queue。
        OrchestrationShadowBudgetAdmission admission = shadowBudgetGate.checkAndReserve(prompt, effectiveCallerContext);
        if (!admission.allowed()) {
            throw new ModelInvocationException(
                    normalizeShadowBudgetErrorCode(admission.decisionCode()),
                    false,
                    null);
        }

        ModelInvocationContextHolder.ModelInvocationContext workerContext =
                new ModelInvocationContextHolder.ModelInvocationContext(
                        normalizedContext.getTaskId(),
                        normalizedContext.getTriggerNodeName(),
                        effectiveCallerContext.traceId(),
                        effectiveCallerContext.purpose(),
                        effectiveCallerContext.quotaKey(),
                        effectiveCallerContext.requireActiveQuota(),
                        effectiveCallerContext.organizationQuotaReserved() || admission.reserved());
        ReservationAwareFutureTask future = new ReservationAwareFutureTask(
                () -> ModelInvocationContextHolder.withContext(
                        workerContext,
                        () -> invokeGateway(prompt, options)),
                admission,
                shadowBudgetGate);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException exception) {
            // execute 拒绝时 FutureTask.done 不会运行，必须在 caller thread 精确补偿一次预留。
            future.releaseBeforeWorkerStart();
            future.attachReleaseFailure(exception);
            String errorCode = executor.isShutdown() ? EXECUTOR_SHUTDOWN : EXECUTOR_SATURATED;
            throw new ModelInvocationException(errorCode, false, exception);
        }

        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // cancel(true) 只是尽力中断 worker；底层 HTTP 最终存活上限仍由 Provider hard timeout 保证。
            future.cancel(true);
            ModelInvocationTimeoutException timeoutException = new ModelInvocationTimeoutException();
            future.attachReleaseFailure(timeoutException);
            throw timeoutException;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            ModelInvocationException interruptedException =
                    new ModelInvocationException(CALLER_INTERRUPTED, true, exception);
            future.attachReleaseFailure(interruptedException);
            throw interruptedException;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof GovernanceBlockException governanceBlockException) {
                String decisionCode = governanceBlockException.getDecision() == null
                        ? null
                        : governanceBlockException.getDecision().getDecisionCode();
                throw new ModelInvocationException(
                        normalizeProviderErrorCode(decisionCode),
                        true,
                        governanceBlockException);
            }
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

    private String normalizeShadowBudgetErrorCode(String decisionCode) {
        if ("BLOCKED_QUOTA_NOT_CONFIGURED".equals(decisionCode)) {
            return "SHADOW_BUDGET_NOT_CONFIGURED";
        }
        if ("BLOCKED_QUOTA_EXCEEDED".equals(decisionCode)) {
            return "SHADOW_BUDGET_EXHAUSTED";
        }
        return normalizeProviderErrorCode(decisionCode);
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

    /**
     * 只有 Future 在 worker 启动前被取消或提交被拒绝时才释放预留。
     * AtomicBoolean 同时覆盖 timeout、interrupt 与 executor rejection 的竞态，防止双重 release。
     */
    private static final class ReservationAwareFutureTask extends FutureTask<ModelResponse> {

        private final OrchestrationShadowBudgetAdmission admission;
        private final OrchestrationShadowBudgetGate shadowBudgetGate;
        private final AtomicBoolean workerStarted = new AtomicBoolean(false);
        private final AtomicBoolean reservationReleased = new AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicReference<RuntimeException> releaseFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

        private ReservationAwareFutureTask(java.util.concurrent.Callable<ModelResponse> callable,
                                           OrchestrationShadowBudgetAdmission admission,
                                           OrchestrationShadowBudgetGate shadowBudgetGate) {
            super(callable);
            this.admission = admission;
            this.shadowBudgetGate = shadowBudgetGate;
        }

        @Override
        public void run() {
            workerStarted.set(true);
            super.run();
        }

        @Override
        protected void done() {
            if (isCancelled() && !workerStarted.get()) {
                releaseBeforeWorkerStart();
            }
        }

        private void releaseBeforeWorkerStart() {
            if (admission.reserved() && reservationReleased.compareAndSet(false, true)) {
                try {
                    shadowBudgetGate.release(admission);
                } catch (RuntimeException exception) {
                    // 补偿失败不能遮蔽 executor rejection、timeout 或 caller interrupt 的主错误。
                    releaseFailure.compareAndSet(null, exception);
                }
            }
        }

        private void attachReleaseFailure(RuntimeException primaryException) {
            RuntimeException failure = releaseFailure.get();
            if (failure != null) {
                primaryException.addSuppressed(failure);
            }
        }
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
