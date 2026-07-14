package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.llm.BudgetGuard;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import cn.bugstack.competitoragent.llm.ModelInvocationPurpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrchestrationShadowBudgetGateTest {

    @Test
    void shouldEstimateAllPromptFieldsAndDenyMissingStrictQuota() {
        BudgetGuard budgetGuard = mock(BudgetGuard.class);
        OrganizationQuotaPolicy quotaPolicy = mock(OrganizationQuotaPolicy.class);
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow(
                21, 0.01d, "ALLOWED", "allowed"));
        when(quotaPolicy.checkAndReserve(
                anyString(), anyString(), anyString(), anyInt(), anyList(), anyBoolean()))
                .thenReturn(QuotaDecision.deny(
                        "BLOCKED_QUOTA_NOT_CONFIGURED",
                        "missing",
                        "default-organization",
                        "MODEL",
                        "ORCHESTRATOR_SHADOW",
                        21,
                        0,
                        null,
                        List.of("https://ops.example.com/shadow-quota")));
        OrchestrationShadowBudgetGate gate = new OrchestrationShadowBudgetGate(
                budgetGuard, quotaPolicy, properties());

        OrchestrationShadowBudgetAdmission admission = gate.checkAndReserve(prompt(), shadowContext());

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.decisionCode()).isEqualTo("BLOCKED_QUOTA_NOT_CONFIGURED");
        assertThat(admission.sourceUrls()).containsExactly("https://ops.example.com/shadow-quota");
        ArgumentCaptor<BudgetGuard.BudgetCheckRequest> requestCaptor =
                ArgumentCaptor.forClass(BudgetGuard.BudgetCheckRequest.class);
        verify(budgetGuard).check(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getInputSummary())
                .contains("system-prompt", "user-prompt", "response-schema");
        verify(quotaPolicy).checkAndReserve(
                "default-organization", "MODEL", "ORCHESTRATOR_SHADOW", 21, List.of(), true);
    }

    @Test
    void shouldRejectRequestBudgetWithoutTouchingOrganizationQuota() {
        BudgetGuard budgetGuard = mock(BudgetGuard.class);
        OrganizationQuotaPolicy quotaPolicy = mock(OrganizationQuotaPolicy.class);
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.deny(
                "too large", 1000, 1.0d, "BLOCKED_ESTIMATED_INPUT_TOKENS", "too large"));
        OrchestrationShadowBudgetGate gate = new OrchestrationShadowBudgetGate(
                budgetGuard, quotaPolicy, properties());

        OrchestrationShadowBudgetAdmission admission = gate.checkAndReserve(prompt(), shadowContext());

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.decisionCode()).isEqualTo("SHADOW_REQUEST_BUDGET_REJECTED");
        verifyNoInteractions(quotaPolicy);
    }

    @Test
    void shouldReserveAndReleaseExactUnits() {
        BudgetGuard budgetGuard = mock(BudgetGuard.class);
        OrganizationQuotaPolicy quotaPolicy = mock(OrganizationQuotaPolicy.class);
        when(budgetGuard.check(any())).thenReturn(BudgetGuard.BudgetCheckResult.allow(
                17, 0.01d, "ALLOWED", "allowed"));
        when(quotaPolicy.checkAndReserve(
                anyString(), anyString(), anyString(), anyInt(), anyList(), anyBoolean()))
                .thenReturn(QuotaDecision.allow(
                        "ALLOWED_RESERVED",
                        "reserved",
                        "default-organization",
                        "MODEL",
                        "ORCHESTRATOR_SHADOW",
                        17,
                        83,
                        null,
                        List.of("https://ops.example.com/shadow-quota")));
        OrchestrationShadowBudgetGate gate = new OrchestrationShadowBudgetGate(
                budgetGuard, quotaPolicy, properties());

        OrchestrationShadowBudgetAdmission admission = gate.checkAndReserve(prompt(), shadowContext());
        gate.release(admission);

        assertThat(admission.allowed()).isTrue();
        assertThat(admission.reservedUnits()).isEqualTo(17);
        verify(quotaPolicy).releaseReservation(
                "default-organization",
                "MODEL",
                "ORCHESTRATOR_SHADOW",
                17,
                List.of("https://ops.example.com/shadow-quota"));
    }

    @Test
    void shouldIgnoreNonShadowPurpose() {
        BudgetGuard budgetGuard = mock(BudgetGuard.class);
        OrganizationQuotaPolicy quotaPolicy = mock(OrganizationQuotaPolicy.class);
        OrchestrationShadowBudgetGate gate = new OrchestrationShadowBudgetGate(
                budgetGuard, quotaPolicy, properties());

        OrchestrationShadowBudgetAdmission admission = gate.checkAndReserve(
                prompt(), new ModelInvocationContextHolder.ModelInvocationContext(1L, "node", "trace"));

        assertThat(admission.decisionCode()).isEqualTo("NOT_APPLICABLE");
        verify(budgetGuard, never()).check(any());
        verifyNoInteractions(quotaPolicy);
    }

    private OrchestratorDecisionProperties properties() {
        return new OrchestratorDecisionProperties();
    }

    private OrchestrationDecisionPrompt prompt() {
        return new OrchestrationDecisionPrompt("system-prompt", "user-prompt", "response-schema");
    }

    private ModelInvocationContextHolder.ModelInvocationContext shadowContext() {
        return new ModelInvocationContextHolder.ModelInvocationContext(
                1L,
                "reviewer",
                "trace",
                ModelInvocationPurpose.ORCHESTRATOR_SHADOW,
                "ORCHESTRATOR_SHADOW",
                true,
                false);
    }
}
