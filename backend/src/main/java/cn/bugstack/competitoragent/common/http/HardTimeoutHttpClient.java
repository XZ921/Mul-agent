package cn.bugstack.competitoragent.common.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 为 JDK HttpClient 提供统一的“应用层硬超时”发送入口。
 * 这里不替代 HttpRequest.timeout() 的协议层约束，而是在调用线程外层再包一层
 * sendAsync(...).get(protocolTimeout + grace)，防止 JDK 内部阻塞等待导致业务线程长期挂起。
 */
public final class HardTimeoutHttpClient {

    private static final long MIN_TIMEOUT_GRACE_MILLIS = 100L;
    private static final long MAX_TIMEOUT_GRACE_MILLIS = 2_000L;

    private HardTimeoutHttpClient() {
    }

    /**
     * 统一通过 sendAsync + get(timeout) 完成同步语义发送。
     * 这里集中处理三类边界：
     * 1. 超时时取消 future，避免底层请求继续无意义占用资源；
     * 2. 中断时恢复线程中断标记，确保上层调度器能感知中断语义；
     * 3. ExecutionException 统一解包，尽量还原原始 IOException / RuntimeException。
     */
    public static <T> HttpResponse<T> send(HttpClient httpClient,
                                           HttpRequest request,
                                           HttpResponse.BodyHandler<T> bodyHandler,
                                           Duration protocolTimeout)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(bodyHandler, "bodyHandler");

        CompletableFuture<HttpResponse<T>> responseFuture = null;
        long timeoutMillis = resolveHardTimeout(protocolTimeout).toMillis();
        try {
            responseFuture = httpClient.sendAsync(request, bodyHandler);
            return responseFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            cancelFuture(responseFuture);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (TimeoutException exception) {
            cancelFuture(responseFuture);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("http request failed: " + (cause == null ? exception.getMessage() : cause.getMessage()),
                    cause == null ? exception : cause);
        }
    }

    /**
     * 根据协议层 timeout 推导调用层硬超时。
     * 额外 grace 采用“至少 100ms、最多 2s、默认 10%”的有界策略，
     * 既避免协议 timeout 与外层 get(timeout) 完全重合导致竞态，也避免对长请求额外放大太多等待时间。
     */
    public static Duration resolveHardTimeout(Duration protocolTimeout) {
        Duration normalizedTimeout = Objects.requireNonNull(protocolTimeout, "protocolTimeout");
        long protocolTimeoutMillis = Math.max(1L, normalizedTimeout.toMillis());
        long tenPercentGraceMillis = Math.max(1L, Math.round(protocolTimeoutMillis * 0.1D));
        long boundedGraceMillis = Math.max(MIN_TIMEOUT_GRACE_MILLIS,
                Math.min(MAX_TIMEOUT_GRACE_MILLIS, tenPercentGraceMillis));
        return Duration.ofMillis(protocolTimeoutMillis + boundedGraceMillis);
    }

    /**
     * 取消 future 时不抛新异常，避免覆盖原始 timeout / interrupt 语义。
     */
    private static void cancelFuture(CompletableFuture<?> responseFuture) {
        if (responseFuture != null) {
            responseFuture.cancel(true);
        }
    }
}
