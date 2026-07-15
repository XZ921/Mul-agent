package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyRuleSetTest {

    @Test
    void shouldDefaultAndNormalizeMaxDecisionsPerCycleIndependentlyFromAutoDecisionBudget() {
        DecisionPolicyRuleSet defaults = DecisionPolicyRuleSet.builder().build().normalized();
        DecisionPolicyRuleSet zeroAutoBudget = DecisionPolicyRuleSet.builder()
                .maxAutoDecisions(0)
                .maxDecisionsPerCycle(0)
                .build()
                .normalized();
        DecisionPolicyRuleSet negativeCycleLimit = DecisionPolicyRuleSet.builder()
                .maxDecisionsPerCycle(-3)
                .build()
                .normalized();

        assertThat(defaults.getMaxDecisionsPerCycle()).isEqualTo(2);
        assertThat(zeroAutoBudget.getMaxAutoDecisions()).isZero();
        assertThat(zeroAutoBudget.getMaxDecisionsPerCycle()).isEqualTo(1);
        assertThat(negativeCycleLimit.getMaxDecisionsPerCycle()).isEqualTo(1);
    }
}
