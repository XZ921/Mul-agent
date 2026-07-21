package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.QuotaDecision;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void shouldDenyShadowBeforeSubmittingFutureOrUsingExecutor() {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        when(gate.checkAndReserve(any(), any())).thenReturn(
                new OrchestrationShadowBudgetAdmission(
                        false, "BLOCKED_QUOTA_EXCEEDED", 0, java.util.List.of()));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1), gate, executor);
        ModelInvocationContextHolder.set(
                7L,
                "reviewer",
                "trace-shadow",
                cn.bugstack.competitoragent.llm.ModelInvocationPurpose.ORCHESTRATOR_SHADOW,
                "ORCHESTRATOR_SHADOW",
                true,
                false);

        assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                assertThatThrownBy(() -> invoker.invoke(
                        prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(4)))
                        .isInstanceOfSatisfying(
                                OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                                exception -> {
                                    assertThat(exception.providerErrorCode())
                                            .isEqualTo("SHADOW_BUDGET_EXHAUSTED");
                                    assertThat(exception.invocationSubmitted()).isFalse();
                                }));

        assertThat(executor.getActiveCount()).isZero();
        assertThat(executor.getQueue()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldPropagateReservedShadowMarkerAndReleaseAfterWorkerCompletes() {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        AtomicReference<ModelInvocationContextHolder.ModelInvocationContext> workerContext = new AtomicReference<>();
        when(gate.checkAndReserve(any(), any())).thenReturn(
                new OrchestrationShadowBudgetAdmission(
                        true, "ALLOWED_RESERVED", 12, java.util.List.of()));
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    workerContext.set(ModelInvocationContextHolder.get());
                    return "{}";
                });
        when(gateway.getModelName()).thenReturn("deepseek-chat");
        invoker = new OrchestrationDecisionModelInvoker(
                gateway, properties(1, 1), gate,
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)));
        ModelInvocationContextHolder.set(
                7L,
                "reviewer",
                "trace-shadow",
                cn.bugstack.competitoragent.llm.ModelInvocationPurpose.ORCHESTRATOR_SHADOW,
                "ORCHESTRATOR_SHADOW",
                true,
                false);

        invoker.invoke(prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1));

        assertThat(workerContext.get().organizationQuotaReserved()).isTrue();
        assertThat(workerContext.get().quotaKey()).isEqualTo("ORCHESTRATOR_SHADOW");
        verify(gate, times(1)).release(any());
    }

    @Test
    void shouldReleaseStartedReservationOnlyAfterTimedOutWorkerActuallyStops() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        OrchestrationShadowBudgetAdmission admission = new OrchestrationShadowBudgetAdmission(
                true, "ALLOWED_RESERVED", 12, java.util.List.of());
        when(gate.checkAndReserve(any(), any())).thenReturn(admission);
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicBoolean allowWorkerToStop = new AtomicBoolean(false);
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    workerStarted.countDown();
                    // 模拟底层 HTTP 在 caller timeout 后短暂忽略中断，确保配额不会在真实请求结束前提前释放。
                    while (!allowWorkerToStop.get()) {
                        Thread.interrupted();
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                    }
                    return "{}";
                });
        when(gateway.getModelName()).thenReturn("deepseek-chat");
        invoker = new OrchestrationDecisionModelInvoker(
                gateway, properties(1, 1), gate,
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)));

        try {
            assertThatThrownBy(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.MILLISECONDS.toNanos(30)))
                    .isInstanceOf(OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException.class);
            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            verify(gate, never()).release(any());

            allowWorkerToStop.set(true);
            verify(gate, timeout(1000).times(1)).release(admission);
        } finally {
            allowWorkerToStop.set(true);
        }
    }

    @Test
    void shouldReleaseReservationOnceWhenExecutorRejectsSubmission() {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        OrchestrationShadowBudgetAdmission admission = new OrchestrationShadowBudgetAdmission(
                true, "ALLOWED_RESERVED", 12, java.util.List.of());
        when(gate.checkAndReserve(any(), any())).thenReturn(admission);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        executor.shutdownNow();
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1), gate, executor);

        assertThatThrownBy(() -> invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOf(OrchestrationDecisionModelInvoker.ModelInvocationException.class);

        verify(gate, times(1)).release(admission);
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldReleaseQueuedReservationOnceWhenCallerTimesOutBeforeWorkerStarts() throws Exception {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        OrchestrationShadowBudgetAdmission admission = new OrchestrationShadowBudgetAdmission(
                true, "ALLOWED_RESERVED", 12, java.util.List.of());
        when(gate.checkAndReserve(any(), any())).thenReturn(admission);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(blockerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1), gate, executor);

        try {
            assertThatThrownBy(() -> invoker.invoke(
                    prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.MILLISECONDS.toNanos(30)))
                    .isInstanceOf(OrchestrationDecisionModelInvoker.ModelInvocationTimeoutException.class);
            verify(gate, times(1)).release(admission);
            verifyNoInteractions(gateway);
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void shouldPreserveTypedGovernanceDecisionCodeFromWorker() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenThrow(new GovernanceBlockException(QuotaDecision.deny(
                        "BLOCKED_QUOTA_EXCEEDED",
                        "sensitive governance summary",
                        "default-organization",
                        "MODEL",
                        "ORCHESTRATOR_SHADOW",
                        12,
                        0,
                        null,
                        java.util.List.of())));
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1));

        assertThatThrownBy(() -> invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOfSatisfying(
                        OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                        exception -> {
                            assertThat(exception.providerErrorCode()).isEqualTo("BLOCKED_QUOTA_EXCEEDED");
                            assertThat(exception.getMessage()).doesNotContain("sensitive governance summary");
                        });
    }

    @Test
    void shouldKeepExecutorErrorWhenReservationReleaseAlsoFails() {
        ModelGateway gateway = mock(ModelGateway.class);
        OrchestrationShadowBudgetGate gate = mock(OrchestrationShadowBudgetGate.class);
        OrchestrationShadowBudgetAdmission admission = new OrchestrationShadowBudgetAdmission(
                true, "ALLOWED_RESERVED", 12, java.util.List.of());
        when(gate.checkAndReserve(any(), any())).thenReturn(admission);
        org.mockito.Mockito.doThrow(new IllegalStateException("release failed"))
                .when(gate).release(admission);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        executor.shutdownNow();
        invoker = new OrchestrationDecisionModelInvoker(gateway, properties(1, 1), gate, executor);

        assertThatThrownBy(() -> invoker.invoke(
                prompt(), context(), new ModelChatOptions(0.0d, 4000L), TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOfSatisfying(
                        OrchestrationDecisionModelInvoker.ModelInvocationException.class,
                        exception -> {
                            assertThat(exception.providerErrorCode()).isEqualTo("ORCHESTRATOR_EXECUTOR_SHUTDOWN");
                            assertThat(exception.getCause().getSuppressed())
                                    .extracting(Throwable::getMessage)
                                    .containsExactly("release failed");
                        });
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
