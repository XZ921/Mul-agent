package cn.bugstack.competitoragent.common;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪过滤器 — 为每个 HTTP 请求生成唯一 traceId
 * <p>
 * traceId 同时注入 SLF4J MDC，这样日志输出中也能看到 traceId，
 * 方便从日志反向定位到具体请求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 优先复用客户端传入的 traceId，否则生成新的
        String traceId = httpRequest.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        TraceIdHolder.set(traceId);
        MDC.put(MDC_KEY, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
