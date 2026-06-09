package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 预算守卫骨架。
 * <p>
 * 当前先把预算检查统一前置到网关入口，后续再补齐正式预算策略和审计持久化。
 */
@Component
public class BudgetGuard {

    private final AiProviderProperties aiProviderProperties;

    public BudgetGuard(AiProviderProperties aiProviderProperties) {
        this.aiProviderProperties = aiProviderProperties;
    }

    /**
     * 预算检查必须发生在外部模型调用之前，避免已经产生费用后才发现越界。
     */
    public BudgetCheckResult check(BudgetCheckRequest request) {
        int estimatedInputTokens = estimateInputTokens(request == null ? null : request.getInputSummary());
        double estimatedCost = estimateInputCost(estimatedInputTokens);
        if (!aiProviderProperties.isBudgetEnabled()) {
            return BudgetCheckResult.allow(
                    estimatedInputTokens,
                    estimatedCost,
                    "BUDGET_DISABLED",
                    "预算守卫未开启，当前仅记录估算 Token 与成本摘要。"
            );
        }
        if (estimatedInputTokens > aiProviderProperties.getBudgetMaxEstimatedInputTokens()) {
            String reason = "预算阻断：预计输入 Token="
                    + estimatedInputTokens
                    + "，已超过限制 "
                    + aiProviderProperties.getBudgetMaxEstimatedInputTokens();
            return BudgetCheckResult.deny(
                    reason,
                    estimatedInputTokens,
                    estimatedCost,
                    "BLOCKED_ESTIMATED_INPUT_TOKENS",
                    reason
            );
        }
        if (aiProviderProperties.getBudgetMaxEstimatedCost() > 0D
                && estimatedCost > aiProviderProperties.getBudgetMaxEstimatedCost()) {
            String reason = "预算阻断：预计成本="
                    + estimatedCost
                    + "，已超过限制 "
                    + aiProviderProperties.getBudgetMaxEstimatedCost();
            return BudgetCheckResult.deny(
                    reason,
                    estimatedInputTokens,
                    estimatedCost,
                    "BLOCKED_ESTIMATED_COST",
                    reason
            );
        }
        return BudgetCheckResult.allow(
                estimatedInputTokens,
                estimatedCost,
                "ALLOWED",
                "预算校验通过：预计输入 Token=" + estimatedInputTokens + "，预计成本=" + estimatedCost
        );
    }

    /**
     * 这里采用保守估算：优先按空白切词，再用字符长度兜底。
     * 这样在中英文混输场景下，预算守卫不会因为分词差异而严重低估成本。
     */
    private int estimateInputTokens(String inputSummary) {
        if (inputSummary == null || inputSummary.isBlank()) {
            return 0;
        }
        int wordBasedEstimate = inputSummary.trim().split("\\s+").length;
        int charBasedEstimate = (int) Math.ceil(inputSummary.length() / 4.0D);
        return Math.max(wordBasedEstimate, charBasedEstimate);
    }

    /**
     * 当前阶段先用统一单价做保守估算，
     * 让预算阻断与审计对象至少能稳定回答“这次调用大概会花多少钱”。
     */
    private double estimateInputCost(int estimatedInputTokens) {
        if (estimatedInputTokens <= 0 || aiProviderProperties.getBudgetInputCostPerThousandTokens() <= 0D) {
            return 0D;
        }
        double rawCost = estimatedInputTokens / 1000D * aiProviderProperties.getBudgetInputCostPerThousandTokens();
        return Math.round(rawCost * 10000D) / 10000D;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetCheckRequest {
        private AiCapability capability;
        private String providerKey;
        private String inputSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetCheckResult {
        private boolean allowed;
        private String reason;
        private Integer estimatedInputTokens;
        private Double estimatedCost;
        private String budgetDecision;
        private String budgetSummary;

        public static BudgetCheckResult allow() {
            return allow(0, 0D, "ALLOWED", "预算校验通过");
        }

        public static BudgetCheckResult allow(int estimatedInputTokens,
                                              double estimatedCost,
                                              String budgetDecision,
                                              String budgetSummary) {
            return BudgetCheckResult.builder()
                    .allowed(true)
                    .reason(budgetSummary)
                    .estimatedInputTokens(estimatedInputTokens)
                    .estimatedCost(estimatedCost)
                    .budgetDecision(budgetDecision)
                    .budgetSummary(budgetSummary)
                    .build();
        }

        public static BudgetCheckResult deny(String reason,
                                             int estimatedInputTokens,
                                             double estimatedCost,
                                             String budgetDecision,
                                             String budgetSummary) {
            return BudgetCheckResult.builder()
                    .allowed(false)
                    .reason(reason)
                    .estimatedInputTokens(estimatedInputTokens)
                    .estimatedCost(estimatedCost)
                    .budgetDecision(budgetDecision)
                    .budgetSummary(budgetSummary)
                    .build();
        }
    }
}
