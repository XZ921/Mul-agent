package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编排决策策略服务。
 * 该服务只负责校验 Orchestrator 决策能否执行，不生成业务判断。
 */
@Service
public class DecisionPolicyService {

    public DecisionPolicyResult evaluate(OrchestrationDecision rawDecision,
                                         DecisionPolicyRuleSet ruleSet,
                                         int currentDecisionCount,
                                         String taskStatus,
                                         String triggerNodeStatus) {
        OrchestrationDecision decision = rawDecision == null
                ? OrchestrationDecision.builder()
                .decisionId("od-invalid")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized()
                : rawDecision.normalized();
        DecisionPolicyRuleSet rules = ruleSet == null
                ? DecisionPolicyRuleSet.builder().build().normalized()
                : ruleSet.normalized();

        List<String> blockedReasons = new ArrayList<>();
        List<String> ruleRefs = new ArrayList<>();
        if (!rules.getAllowedDecisionTypes().contains(decision.getDecisionType())) {
            blockedReasons.add("decisionType 不在允许列表：" + decision.getDecisionType());
        } else {
            ruleRefs.add("allowedDecisionTypes");
        }
        if (rules.isRequireSourceUrlsOrEvidenceGap()
                && decision.getSourceUrls().isEmpty()
                && decision.getEvidenceState() != EvidenceState.MISSING_SOURCE) {
            blockedReasons.add("缺少 sourceUrls 且未显式声明 MISSING_SOURCE");
        } else {
            ruleRefs.add("requireSourceUrlsOrEvidenceGap");
        }
        if (decision.getSuggestedQueries().size() > rules.getMaxSearchQueriesPerDecision()) {
            blockedReasons.add("搜索补证 query 数量超过上限："
                    + decision.getSuggestedQueries().size() + "/" + rules.getMaxSearchQueriesPerDecision());
        } else {
            ruleRefs.add("maxSearchQueriesPerDecision");
        }
        if (currentDecisionCount >= rules.getMaxAutoDecisions()) {
            blockedReasons.add("自动编排次数已达到上限：" + currentDecisionCount + "/" + rules.getMaxAutoDecisions());
        }
        if (rules.getBlockedTaskStatuses().contains(normalizeStatus(taskStatus))) {
            blockedReasons.add("当前任务状态禁止自动编排：" + taskStatus);
        }
        if (rules.getBlockedNodeStatuses().contains(normalizeStatus(triggerNodeStatus))) {
            blockedReasons.add("触发节点状态禁止自动编排：" + triggerNodeStatus);
        }

        String normalizedAction = resolveNormalizedAction(decision);
        if (!rules.getAllowedDynamicActions().contains(normalizedAction)) {
            blockedReasons.add("normalizedAction 不在允许列表：" + normalizedAction);
        }
        List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRiskRules = matchRiskRules(decision, rules);
        matchedRiskRules.stream()
                .map(DecisionPolicyRuleSet.PolicyRiskRule::getRuleId)
                .forEach(ruleRefs::add);
        boolean requiresConfirmation = decision.isRequiresHumanIntervention()
                || decision.isRequiresConfirmation()
                || rules.getConfirmationRequiredDecisionTypes().contains(decision.getDecisionType())
                || matchedRiskRules.stream().anyMatch(DecisionPolicyRuleSet.PolicyRiskRule::isRequiresConfirmation);
        String riskLevel = resolveRiskLevel(matchedRiskRules, requiresConfirmation, blockedReasons);

        return DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .allowed(blockedReasons.isEmpty())
                .riskLevel(riskLevel)
                .requiresConfirmation(requiresConfirmation)
                .blockedReasons(blockedReasons)
                .normalizedAction(normalizedAction)
                .policyRuleRefs(ruleRefs)
                .sourceUrls(decision.getSourceUrls())
                .evidenceState(decision.getEvidenceState())
                .policyVersion(rules.getPolicyVersion())
                .build()
                .normalized();
    }

    private String resolveNormalizedAction(OrchestrationDecision decision) {
        return switch (decision.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
            case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
            case "NO_ACTION" -> "NO_ACTION";
            case "MANUAL_REVIEW" -> "MANUAL_ONLY";
            default -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()) ? "MANUAL_ONLY" : "NO_ACTION";
        };
    }

    private List<DecisionPolicyRuleSet.PolicyRiskRule> matchRiskRules(OrchestrationDecision decision,
                                                                       DecisionPolicyRuleSet rules) {
        List<DecisionPolicyRuleSet.PolicyRiskRule> matched = new ArrayList<>();
        for (DecisionPolicyRuleSet.PolicyRiskRule rule : rules.getRiskRules()) {
            if (rule == null || rule.getRuleId() == null) {
                continue;
            }
            if ("rerun_downstream_requires_confirmation".equals(rule.getRuleId())
                    && "RERUN_NODE".equals(decision.getDecisionType())
                    && "CURRENT_NODE_AND_DOWNSTREAM".equals(decision.getAffectedScope())) {
                matched.add(rule);
            }
            if ("missing_source_requires_supplement".equals(rule.getRuleId())
                    && decision.getEvidenceState() == EvidenceState.MISSING_SOURCE
                    && !"SUPPLEMENT_EVIDENCE".equals(decision.getActionType())) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private String resolveRiskLevel(List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRiskRules,
                                    boolean requiresConfirmation,
                                    List<String> blockedReasons) {
        if (!blockedReasons.isEmpty()) {
            return "HIGH";
        }
        if (matchedRiskRules.stream().anyMatch(rule -> "HIGH".equalsIgnoreCase(rule.getRiskLevel()))) {
            return "HIGH";
        }
        return requiresConfirmation ? "MEDIUM" : "LOW";
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "" : status.trim().toUpperCase(Locale.ROOT);
    }
}
