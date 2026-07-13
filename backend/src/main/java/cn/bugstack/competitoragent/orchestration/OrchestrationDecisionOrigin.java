package cn.bugstack.competitoragent.orchestration;

import java.util.Locale;

/**
 * 编排决策来源。
 * 来源决定后续策略校验使用 LLM 动作矩阵还是历史规则集，不能由动作字段反向猜测。
 */
public enum OrchestrationDecisionOrigin {

    LLM_PRIMARY,
    LLM_SHADOW,
    RULE_ONLY,
    RULE_FALLBACK,
    LEGACY_ADAPTER;

    /**
     * 历史事件和缺失来源的防御对象统一使用同一个保守默认值。
     * 所有默认逻辑必须调用该方法，避免在 policy、projector 等位置重复硬编码。
     */
    public static OrchestrationDecisionOrigin defaultOrigin() {
        return LEGACY_ADAPTER;
    }

    /**
     * 宽容解析持久化或外部读模型中的来源文本。
     * 非法值不能被推断成 LLM 来源，否则历史脏数据可能绕过 legacy 策略边界。
     */
    public static OrchestrationDecisionOrigin fromValue(String value) {
        if (value == null || value.isBlank()) {
            return defaultOrigin();
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultOrigin();
        }
    }

    /**
     * LLM 主路径和 shadow 都必须使用动作组合矩阵；
     * 规则主路径、规则回退和历史适配器继续使用现有 legacy ruleSet。
     */
    public boolean usesLlmActionMatrix() {
        return this == LLM_PRIMARY || this == LLM_SHADOW;
    }

    /**
     * 对外暴露稳定的决策协议名称，协议选择与矩阵分流共用同一来源判断。
     */
    public String decisionContract() {
        return usesLlmActionMatrix()
                ? "LLM_ACTION_MATRIX"
                : "LEGACY_RULE_SET";
    }
}
