package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已校验编排决策到动态计划变更的翻译器。
 * 它只翻译策略已允许的决策，不重新做质量判断，也不直接执行节点。
 */
@Component
public class DecisionExecutorAdapter {

    private final ObjectMapper objectMapper;

    public DecisionExecutorAdapter() {
        this(new ObjectMapper());
    }

    public DecisionExecutorAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DynamicPlanMutation toMutation(OrchestrationDecision rawDecision,
                                          DecisionPolicyResult rawPolicyResult,
                                          Long targetPlanVersionId,
                                          int nextPlanVersion) {
        OrchestrationDecision decision = rawDecision == null ? null : rawDecision.normalized();
        DecisionPolicyResult policyResult = rawPolicyResult == null ? null : rawPolicyResult.normalized();
        if (decision == null || policyResult == null || !policyResult.isAllowed()) {
            return noMutation(decision, policyResult);
        }
        String normalizedAction = policyResult.getNormalizedAction();
        if ("CREATE_SUPPLEMENT_BRANCH".equals(normalizedAction)) {
            String expectedNodeName = "collect_revision_evidence_v" + nextPlanVersion + "_1";
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("APPEND_NODES")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                            .nodeName(expectedNodeName)
                            .displayName("补充证据采集")
                            .agentType(AgentType.COLLECTOR.name())
                            .dependsOn(List.of(decision.getTriggerNodeName()))
                            .required(true)
                            .executionOrder(0)
                            .nodeConfig(writeJson(buildSupplementNodeConfig(decision)))
                            .notes("Orchestrator 决策触发的动态补证分支")
                            .dynamicNode(true)
                            .originNodeName(decision.getTriggerNodeName())
                            .build()))
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .expectedResumeNodeName(expectedNodeName)
                    .build()
                    .normalized();
        }
        if ("CREATE_REWRITE_BRANCH".equals(normalizedAction)) {
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("APPEND_NODES")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .nodeTemplates(List.of())
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .expectedResumeNodeName("rewrite_revision_patch_v" + nextPlanVersion)
                    .build()
                    .normalized();
        }
        if ("MANUAL_ONLY".equals(normalizedAction)) {
            return DynamicPlanMutation.builder()
                    .mutationId("dpm-" + decision.getDecisionId())
                    .decisionId(decision.getDecisionId())
                    .mutationType("MARK_WAITING_INTERVENTION")
                    .targetPlanVersionId(targetPlanVersionId)
                    .branchReason("ORCHESTRATOR_DECISION")
                    .dynamicAction(normalizedAction)
                    .sourceUrls(policyResult.getSourceUrls())
                    .evidenceState(policyResult.getEvidenceState())
                    .build()
                    .normalized();
        }
        return noMutation(decision, policyResult);
    }

    private Map<String, Object> buildSupplementNodeConfig(OrchestrationDecision decision) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("decisionId", decision.getDecisionId());
        config.put("sourceType", "SUPPLEMENTAL");
        config.put("searchQueries", decision.getSuggestedQueries());
        config.put("sourceUrls", decision.getSourceUrls());
        config.put("evidenceState", decision.getEvidenceState().name());
        config.put("summary", decision.getReason());
        config.put("targetSection", decision.getTargetSection());
        return config;
    }

    private DynamicPlanMutation noMutation(OrchestrationDecision decision, DecisionPolicyResult policyResult) {
        String decisionId = decision == null ? "unknown" : decision.getDecisionId();
        return DynamicPlanMutation.builder()
                .mutationId("dpm-" + decisionId)
                .decisionId(decisionId)
                .mutationType("NO_MUTATION")
                .branchReason("POLICY_BLOCKED_OR_NO_ACTION")
                .sourceUrls(policyResult == null ? List.of() : policyResult.getSourceUrls())
                .evidenceState(policyResult == null ? EvidenceState.NOT_APPLICABLE : policyResult.getEvidenceState())
                .build()
                .normalized();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize dynamic mutation node config failed", e);
        }
    }
}
