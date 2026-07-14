package cn.bugstack.competitoragent.llm;

/**
 * 单次聊天模型调用参数覆盖。
 * null 表示继续使用 Provider adapter 的全局配置，非 null 值必须在进入网关前完成严格校验。
 */
public record ModelChatOptions(Double temperature, Long timeoutMillis) {

    public ModelChatOptions {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0.0d)) {
            throw new IllegalArgumentException("temperature 必须是有限非负数");
        }
        if (timeoutMillis != null && timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis 必须大于 0");
        }
    }
}
