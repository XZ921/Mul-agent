package cn.bugstack.competitoragent.orchestration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Orchestrator LLM 决策热路径配置。
 * 这里只承载 Task 05 已拥有的模型参数、总超时和执行器边界，运行模式与 shadow 配置由 Task 06 扩展。
 */
@Data
@Component
@ConfigurationProperties(prefix = "orchestration.decision")
public class OrchestratorDecisionProperties {

    private double modelTemperature = 0.0d;
    private long llmTimeoutMs = 4000L;
    private int maxParseRetries = 1;
    private int executorThreads = 2;
    private int executorQueueCapacity = 16;

    /**
     * 配置错误必须在应用启动期暴露，禁止运行时静默夹取成另一套 timeout/retry 语义。
     */
    @PostConstruct
    public void validate() {
        if (!Double.isFinite(modelTemperature) || modelTemperature < 0.0d) {
            throw new IllegalStateException("modelTemperature 必须是有限非负数");
        }
        if (llmTimeoutMs <= 0L) {
            throw new IllegalStateException("llmTimeoutMs 必须大于 0");
        }
        if (maxParseRetries < 0 || maxParseRetries > 1) {
            throw new IllegalStateException("maxParseRetries 本阶段只允许 0 或 1");
        }
        if (executorThreads <= 0) {
            throw new IllegalStateException("executorThreads 必须大于 0");
        }
        if (executorQueueCapacity <= 0) {
            throw new IllegalStateException("executorQueueCapacity 必须大于 0");
        }
    }
}
