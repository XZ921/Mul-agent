package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OrchestrationDecisionModelInvokerTest {

    private OrchestrationDecisionModelInvoker invoker;

    @AfterEach
    void tearDown() {
        ModelInvocationContextHolder.clear();
        if (invoker != null) {
            invoker.destroy();
        }
    }

    @Test
    void shouldPropagateContextUseDedicatedThreadAndReadModelSnapshotInWorker() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        AtomicReference<String> workerThreadName = new AtomicReference<>();
        AtomicReference<ModelInvocationContextHolder.ModelInvocationContext> workerContext = new AtomicReference<>();
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    workerThreadName.set(Thread.currentThread().getName());
                    workerContext.set(ModelInvocationContextHolder.get());
                    return "{\"decisions\":[]}";
                });
        when(gateway.getModelName()).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().getName()).startsWith("orchestrator-llm-");
            return "deepseek-chat";
        });
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                runnable -> new Thread(runnable, "orchestrator-llm-test"));
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 2), executor);
        ModelInvocationContextHolder.set(99L, "caller-node", "trace-99");

        OrchestrationDecisionModelInvoker.ModelResponse response = invoker.invoke(
                prompt(),
                context(),
                new ModelChatOptions(0.0d, 4000L),
                TimeUnit.SECONDS.toNanos(1));

        assertThat(response.rawResponse()).isEqualTo("{\"decisions\":[]}");
        assertThat(response.modelName()).isEqualTo("deepseek-chat");
        assertThat(workerThreadName.get()).startsWith("orchestrator-llm-");
        assertThat(workerContext.get()).isEqualTo(
                new ModelInvocationContextHolder.ModelInvocationContext(7L, "reviewer", "trace-99"));
        assertThat(ModelInvocationContextHolder.get()).isEqualTo(
                new ModelInvocationContextHolder.ModelInvocationContext(99L, "caller-node", "trace-99"));
        assertThat(executor.submit(ModelInvocationContextHolder::get).get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void shouldCancelTimedOutWorkerAndRestoreCallerInterruptSemantics() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    workerStarted.countDown();
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException exception) {
                        workerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new LlmException("worker interrupted", "HTTP_INTERRUPTED", exception);
                    }
                    return "never";
                });
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> invoker.invoke(
                        prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.MILLISECONDS.toNanos(50)))
                        .isInstanceOf(OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException.class));

        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        org.mockito.Mockito.verify(gateway, never()).getModelName();
    }

    @Test
    void shouldMapBoundedQueueSaturationAndShutdownToStableCodes() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    firstWorkerStarted.countDown();
                    releaseWorker.await(1, TimeUnit.SECONDS);
                    return "{}";
                });
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1), executor);
        var callers = Executors.newFixedThreadPool(2);
        try {
            callers.submit(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(2)));
            assertThat(firstWorkerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            callers.submit(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(2)));
            awaitQueueSize(executor, 1);

            assertThatThrownBy(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                    .isInstanceOfSatisfying(
                            OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                            exception -> assertThat(exception.providerErrorCode())
                                    .isEqualTo("ORCHESTRATOR_EXECUTOR_SATURATED"));

            releaseWorker.countDown();
        } finally {
            releaseWorker.countDown();
            callers.shutdownNow();
        }

        invoker.destroy();
        assertThatThrownBy(() -> invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOfSatisfying(
                        OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                        exception -> assertThat(exception.providerErrorCode())
                                .isEqualTo("ORCHESTRATOR_EXECUTOR_SHUTDOWN"));
    }

    @Test
    void shouldRestoreInterruptFlagWhenCallerIsInterrupted() {
        ModelGateway gateway = mock(ModelGateway.class);
        AtomicBoolean release = new AtomicBoolean(false);
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    while (!release.get()) {
                        Thread.onSpinWait();
                    }
                    return "{}";
                });
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1));
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                    .isInstanceOfSatisfying(
                            OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                            exception -> assertThat(exception.providerErrorCode())
                                    .isEqualTo("ORCHESTRATOR_CALLER_INTERRUPTED"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            release.set(true);
            Thread.interrupted();
        }
    }

    @Test
    void shouldReleaseNonInterruptibleWorkerAfterProviderHardTimeoutBoundary() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        CountDownLatch workerReleased = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    if (invocationCount.incrementAndGet() == 1) {
                        // 模拟底层 socket 暂不响应 Future.cancel(true)，但会在 Provider hard timeout 到达后退出。
                        long hardTimeoutDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(120L);
                        while (System.nanoTime() < hardTimeoutDeadline) {
                            Thread.interrupted();
                            Thread.onSpinWait();
                        }
                        workerReleased.countDown();
                        throw new LlmException("provider hard timeout", "HTTP_TIMEOUT");
                    }
                    return "{\"decisions\":[]}";
                });
        when(gateway.getModelName()).thenReturn("deepseek-chat");
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1));

        assertThatThrownBy(() -> invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 120L), TimeUnit.MILLISECONDS.toNanos(20L)))
                .isInstanceOf(OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException.class);
        assertThat(workerReleased.await(1, TimeUnit.SECONDS)).isTrue();

        OrchestrationDecisionModelInvoker.ModelResponse recovered = invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 120L), TimeUnit.SECONDS.toNanos(1L));
        assertThat(recovered.rawResponse()).isEqualTo("{\"decisions\":[]}");
        assertThat(invocationCount.get()).isEqualTo(2);
    }

    private void awaitQueueSize(ThreadPoolExecutor executor, int expectedSize) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.getQueue().size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(executor.getQueue()).hasSize(expectedSize);
    }

    private OrchestratorDecisionProperties properties(int threads, int queueCapacity) {
        OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();
        properties.setExecutorThreads(threads);
        properties.setExecutorQueueCapacity(queueCapacity);
        return properties;
    }

    private OrchestrationDecisionPrompt prompt() {
        return new OrchestrationDecisionPrompt("system", "user", "schema");
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .taskId(7L)
                .triggerNodeName("reviewer")
                .build()
                .normalized();
    }
}
