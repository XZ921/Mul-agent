package cn.bugstack.competitoragent.common;

/**
 * TraceId 上下文持有者 — 基于 ThreadLocal 实现请求级别追踪
 * <p>
 * 在 Filter 中设置，在 GlobalExceptionHandler 和 ApiResponse 中读取，
 * 确保同一个请求的所有日志和响应共享同一个 traceId。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String get() {
        String traceId = TRACE_ID.get();
        return traceId != null ? traceId : "N/A";
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
