package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

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
        this.properties = properties == null ? new DomainDiscoveryProperties() : properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(resolveTimeoutMillis()))
                .build();
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
                if (probe(normalizedUrl, HttpMethod.HEAD) || probe(normalizedUrl, HttpMethod.GET)) {
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
     */
    private boolean probe(String url, HttpMethod method) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                .method(method.name(), HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            return statusCode >= 200 && statusCode < 400;
        } catch (HttpTimeoutException timeoutException) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException exception) {
            return false;
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
}
