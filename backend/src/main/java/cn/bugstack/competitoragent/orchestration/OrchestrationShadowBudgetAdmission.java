package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * Shadow 配额提交前准入结果，不携带 Prompt 原文或外部响应。
 */
public record OrchestrationShadowBudgetAdmission(
        boolean allowed,
        String decisionCode,
        int reservedUnits,
        List<String> sourceUrls
) {

    public OrchestrationShadowBudgetAdmission {
        decisionCode = decisionCode == null || decisionCode.isBlank() ? null : decisionCode.trim();
        reservedUnits = Math.max(0, reservedUnits);
        sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        if (!allowed && reservedUnits > 0) {
            throw new IllegalArgumentException("拒绝的 shadow admission 不能持有预留额度");
        }
    }

    public boolean reserved() {
        return allowed && reservedUnits > 0;
    }

    public static OrchestrationShadowBudgetAdmission notApplicable() {
        return new OrchestrationShadowBudgetAdmission(true, "NOT_APPLICABLE", 0, List.of());
    }
}
