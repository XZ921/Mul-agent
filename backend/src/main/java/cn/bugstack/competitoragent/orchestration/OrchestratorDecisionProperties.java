package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.GovernanceDefaults;
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

    private OrchestratorDecisionMode mode = OrchestratorDecisionMode.RULE_ONLY;
    private boolean fallbackToRule = true;
    private double modelTemperature = 0.0d;
    private long llmTimeoutMs = 4000L;
    private int maxParseRetries = 1;
    private int executorThreads = 2;
    private int executorQueueCapacity = 16;
    private Shadow shadow = new Shadow();

    /**
     * 配置错误必须在应用启动期暴露，禁止运行时静默夹取成另一套 timeout/retry 语义。
     */
    @PostConstruct
    public void validate() {
        if (mode == null) {
            throw new IllegalStateException("mode 不能为空");
        }
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
        if (shadow == null) {
            throw new IllegalStateException("shadow 配置不能为空");
        }
        shadow.validate();
    }

    /**
     * Shadow 只保存启停、独立配额键和严格准入策略；每日额度上限仍由组织配额快照单点拥有。
     */
    @Data
    public static class Shadow {

        private boolean enabled = false;
        private String isolatedBudgetKey = GovernanceDefaults.ORCHESTRATOR_SHADOW_BUDGET_KEY;
        private boolean requireActiveQuota = true;

        private void validate() {
            if (isolatedBudgetKey == null || isolatedBudgetKey.isBlank()) {
                throw new IllegalStateException("shadow.isolatedBudgetKey 不能为空");
            }
            isolatedBudgetKey = isolatedBudgetKey.trim();
        }
    }
}
