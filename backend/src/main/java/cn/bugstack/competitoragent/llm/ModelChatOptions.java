package cn.bugstack.competitoragent.llm;

/**
 * 单次聊天模型调用参数覆盖。
 * null 表示继续使用 Provider adapter 的全局配置，非 null 值必须在进入网关前完成严格校验。
 */
public record ModelChatOptions(Double temperature, Long timeoutMillis, String modelName) {

    /** 兼容既有调用方：未显式传模型时继续使用 Provider 的全局 modelName。 */
    public ModelChatOptions(Double temperature, Long timeoutMillis) {
        this(temperature, timeoutMillis, null);
    }

    public ModelChatOptions {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0.0d)) {
            throw new IllegalArgumentException("temperature 必须是有限非负数");
        }
        if (timeoutMillis != null && timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis 必须大于 0");
        }
        if (modelName != null && modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 非 null 时不能为空");
        }
        modelName = modelName == null ? null : modelName.trim();
    }
}
