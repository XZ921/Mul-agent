package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.common.http.HardTimeoutHttpClient;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 域名可达性验证客户端。
 * <p>
 * 只做轻量 HEAD/GET 探测，不承载业务语义判断，避免把 LLM 发现和真实网络验证耦合到一起。
 */
@Component
public class DomainVerificationClient {

    private final DomainDiscoveryProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public DomainVerificationClient(DomainDiscoveryProperties properties) {
        this(properties, null);
    }

    DomainVerificationClient(DomainDiscoveryProperties properties, HttpClient httpClient) {
        this.properties = properties == null ? new DomainDiscoveryProperties() : properties;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(resolveTimeoutMillis()))
                .build()
                : httpClient;
    }

    /**
     * 判断一个 HTTP/HTTPS URL 是否可达。
     * <p>
     * 这里先 HEAD 后 GET，遇到 405/403 这类容易拦截 HEAD 的页面时，再用 GET 补一刀。
     */
    public boolean isReachable(String url) {
        if (!UrlSecurityUtils.isHttpUrl(url)) {
            return false;
        }
        String normalizedUrl = url.trim();
        int attempts = Math.max(1, resolveMaxRetries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ProbeResult headResult = probe(normalizedUrl, HttpMethod.HEAD);
                if (headResult == ProbeResult.REACHABLE) {
                    return true;
                }
                if (headResult == ProbeResult.FALLBACK_TO_GET
                        && probe(normalizedUrl, HttpMethod.GET) == ProbeResult.REACHABLE) {
                    return true;
                }
            } catch (RuntimeException exception) {
                if (attempt >= attempts) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 执行一次具体的网络探测。
     * 这里不能把所有失败都简单折叠成 false，
     * 否则 HEAD 超时、连接失败也会继续补一轮 GET，把候选验证线程额外多阻塞一个 timeout 周期。
     * 只有 405/403 这类“HEAD 可能被目标站点拦截，但 GET 仍可能成功”的场景，才允许降级继续探测。
     */
    private ProbeResult probe(String url, HttpMethod method) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                .method(method.name(), HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            /**
             * 域名验证只做轻量探测，但也必须具备调用层硬超时，
             * 否则候选验证线程池会被单个挂死域名长期占住。
             */
            HttpResponse<Void> response = HardTimeoutHttpClient.send(
                    httpClient,
                    request,
                    HttpResponse.BodyHandlers.discarding(),
                    request.timeout().orElse(Duration.ofMillis(resolveTimeoutMillis()))
            );
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 400) {
                return ProbeResult.REACHABLE;
            }
            if (method == HttpMethod.HEAD && (statusCode == 403 || statusCode == 405)) {
                return ProbeResult.FALLBACK_TO_GET;
            }
            return ProbeResult.UNREACHABLE;
        } catch (TimeoutException timeoutException) {
            return ProbeResult.UNREACHABLE;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProbeResult.UNREACHABLE;
        } catch (IOException exception) {
            return ProbeResult.UNREACHABLE;
        }
    }

    private int resolveTimeoutMillis() {
        if (properties == null || properties.getVerificationTimeoutMillis() <= 0) {
            return 3000;
        }
        return properties.getVerificationTimeoutMillis();
    }

    private int resolveMaxRetries() {
        if (properties == null || properties.getMaxRetries() <= 0) {
            return 1;
        }
        return properties.getMaxRetries();
    }

    /**
     * 轻量域名探测只需要区分三种结果：
     * 1. 已确认可达；
     * 2. 仅在 HEAD 被策略拦截时允许降级 GET；
     * 3. 其它情况直接判定不可达。
     */
    private enum ProbeResult {
        REACHABLE,
        FALLBACK_TO_GET,
        UNREACHABLE
    }
}
