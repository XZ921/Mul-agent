package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetGuardTest {

    @Test
    void shouldBlockInvocationWhenEstimatedInputTokensExceedConfiguredBudget() {
        // 预算守卫必须在正式调用前做显式阻断，
        // 不能等到外部模型已经产生费用后，业务层才被动发现超预算。
        AiProviderProperties properties = new AiProviderProperties();
        properties.setBudgetEnabled(true);
        properties.setBudgetMaxEstimatedInputTokens(5);
        properties.setBudgetInputCostPerThousandTokens(0.5D);
        BudgetGuard budgetGuard = new BudgetGuard(properties);

        BudgetGuard.BudgetCheckResult result = budgetGuard.check(BudgetGuard.BudgetCheckRequest.builder()
                .capability(AiCapability.CHAT)
                .providerKey("deepseek")
                .inputSummary("this prompt is intentionally much longer than five tokens")
                .build());

        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("预算"));
        assertTrue(result.getEstimatedInputTokens() > 5);
        assertEquals("BLOCKED_ESTIMATED_INPUT_TOKENS", result.getBudgetDecision());
        assertTrue(result.getEstimatedCost() > 0D);
        assertNotNull(result.getBudgetSummary());
    }
}
