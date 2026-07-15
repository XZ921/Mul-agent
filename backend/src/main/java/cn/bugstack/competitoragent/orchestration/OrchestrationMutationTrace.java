package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * DynamicPlanMutation 的审计窄投影。
 * nodeTemplates 与 runtimeCommand 属于执行细节，禁止进入 workflow event payload。
 */
public record OrchestrationMutationTrace(
        String mutationId,
        String decisionId,
        String mutationType,
        Long targetPlanVersionId,
        String branchReason,
        String dynamicAction,
        String expectedResumeNodeName,
        EvidenceState evidenceState,
        List<String> sourceUrls
) {

    public OrchestrationMutationTrace {
        mutationId = OrchestrationDecisionAuditTrace.normalizeText(mutationId);
        decisionId = OrchestrationDecisionAuditTrace.normalizeText(decisionId);
        mutationType = OrchestrationDecisionAuditTrace.normalizeText(mutationType);
        branchReason = OrchestrationDecisionAuditTrace.normalizeText(branchReason);
        dynamicAction = OrchestrationDecisionAuditTrace.normalizeText(dynamicAction);
        expectedResumeNodeName = OrchestrationDecisionAuditTrace.normalizeText(expectedResumeNodeName);
        sourceUrls = OrchestrationDecisionAuditTrace.mergeSourceUrls(sourceUrls);
        if (evidenceState == null) {
            evidenceState = sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
        }
    }
}
