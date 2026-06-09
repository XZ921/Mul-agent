package cn.bugstack.competitoragent.llm;

import java.util.function.Supplier;

/**
 * 模型调用上下文持有器。
 * <p>
 * 网关治理层需要知道“当前是谁在调用模型”，
 * 这样预算、审计和回放才能自然回到 Task / Node 维度，而不是变成无主调用记录。
 */
public final class ModelInvocationContextHolder {

    private static final ThreadLocal<ModelInvocationContext> CONTEXT = new ThreadLocal<>();

    private ModelInvocationContextHolder() {
    }

    public static void set(Long taskId, String nodeName, String traceId) {
        CONTEXT.set(new ModelInvocationContext(taskId, nodeName, traceId));
    }

    public static ModelInvocationContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 让测试和非 Agent 链路也能显式挂载一次调用上下文。
     */
    public static <T> T withContext(Long taskId, String nodeName, String traceId, Supplier<T> supplier) {
        set(taskId, nodeName, traceId);
        try {
            return supplier.get();
        } finally {
            clear();
        }
    }

    public record ModelInvocationContext(Long taskId, String nodeName, String traceId) {
    }
}
