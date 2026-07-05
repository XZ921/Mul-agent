package cn.bugstack.competitoragent.testsupport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 永不完成的 HttpClient 测试替身。
 * 它专门用于验证调用方是否已经从阻塞式 send() 迁移到 sendAsync + 应用层硬超时：
 * 1. 如果代码仍然调用 send()，这里会立即抛 AssertionError；
 * 2. 如果代码走 sendAsync()，则返回一个永不完成但可跟踪 cancel() 的 future，便于断言硬超时是否真正取消底层请求。
 */
public class NeverCompletingHttpClient extends HttpClient {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger asyncAttemptCount = new AtomicInteger(0);

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.of(Duration.ofSeconds(1));
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NORMAL;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        throw new AssertionError("hard-timeout migration must not call blocking HttpClient.send()");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> responseBodyHandler) {
        asyncAttemptCount.incrementAndGet();
        return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) new TrackingFuture();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }

    public int asyncAttemptCount() {
        return asyncAttemptCount.get();
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /**
     * 自定义 future 只负责跟踪 cancel 行为，不负责真正发起网络请求。
     */
    private final class TrackingFuture extends CompletableFuture<HttpResponse<String>> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * 兜底响应实现，当前测试场景不会真正完成它，但保留该定义便于后续扩展。
     */
    @SuppressWarnings("unused")
    private record NoOpHttpResponse(String body) implements HttpResponse<String> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("https://example.com")).GET().build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (key, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.com");
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
