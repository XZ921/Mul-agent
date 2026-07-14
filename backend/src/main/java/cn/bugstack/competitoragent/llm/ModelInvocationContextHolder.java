package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.governance.GovernanceDefaults;

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
        set(taskId, nodeName, traceId,
                ModelInvocationPurpose.DEFAULT,
                GovernanceDefaults.MODEL_DAILY_BUDGET_KEY,
                false,
                false);
    }

    public static void set(Long taskId,
                           String nodeName,
                           String traceId,
                           ModelInvocationPurpose purpose,
                           String quotaKey,
                           boolean requireActiveQuota,
                           boolean organizationQuotaReserved) {
        CONTEXT.set(new ModelInvocationContext(
                taskId,
                nodeName,
                traceId,
                purpose,
                quotaKey,
                requireActiveQuota,
                organizationQuotaReserved));
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
        return withContext(new ModelInvocationContext(taskId, nodeName, traceId), supplier);
    }

    public static <T> T withContext(Long taskId,
                                    String nodeName,
                                    String traceId,
                                    ModelInvocationPurpose purpose,
                                    String quotaKey,
                                    boolean requireActiveQuota,
                                    boolean organizationQuotaReserved,
                                    Supplier<T> supplier) {
        return withContext(new ModelInvocationContext(
                taskId,
                nodeName,
                traceId,
                purpose,
                quotaKey,
                requireActiveQuota,
                organizationQuotaReserved), supplier);
    }

    /**
     * 嵌套模型作用域结束后必须恢复 caller 原上下文，而不是无条件 clear，
     * 否则 Orchestrator 在已有 Agent 调用链中执行时会破坏外层审计归属。
     */
    public static <T> T withContext(ModelInvocationContext context, Supplier<T> supplier) {
        if (context == null) {
            throw new IllegalArgumentException("模型调用上下文不能为空");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("supplier 不能为空");
        }
        ModelInvocationContext previous = CONTEXT.get();
        CONTEXT.set(context);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CONTEXT.set(previous);
            }
        }
    }

    public record ModelInvocationContext(
            Long taskId,
            String nodeName,
            String traceId,
            ModelInvocationPurpose purpose,
            String quotaKey,
            boolean requireActiveQuota,
            boolean organizationQuotaReserved
    ) {

        public ModelInvocationContext(Long taskId, String nodeName, String traceId) {
            this(taskId,
                    nodeName,
                    traceId,
                    ModelInvocationPurpose.DEFAULT,
                    GovernanceDefaults.MODEL_DAILY_BUDGET_KEY,
                    false,
                    false);
        }

        public ModelInvocationContext {
            purpose = purpose == null ? ModelInvocationPurpose.DEFAULT : purpose;
            quotaKey = quotaKey == null || quotaKey.isBlank()
                    ? GovernanceDefaults.MODEL_DAILY_BUDGET_KEY
                    : quotaKey.trim();
        }
    }
}
