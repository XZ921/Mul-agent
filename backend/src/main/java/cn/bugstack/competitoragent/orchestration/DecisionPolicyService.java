package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 编排决策策略服务。
 * 该服务只负责校验 Orchestrator 决策能否执行，不生成业务判断。
 */
@Service
public class DecisionPolicyService {

    private final OrchestrationDecisionActionMatrix actionMatrix;

    public DecisionPolicyService(OrchestrationDecisionActionMatrix actionMatrix) {
        this.actionMatrix = actionMatrix;
    }

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
                .decisionOrigin(OrchestrationDecisionOrigin.defaultOrigin())
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

        String normalizedAction;
        if (decision.getDecisionOrigin().usesLlmActionMatrix()) {
            OrchestrationDecisionActionMatrix.ActionMatrixValidation matrixResult = actionMatrix.validate(decision);
            ruleRefs.add("llmDecisionActionMatrix");
            if (matrixResult.valid()) {
                ruleRefs.add(matrixResult.ruleId());
                normalizedAction = matrixResult.normalizedAction();
            } else {
                blockedReasons.addAll(toMatrixBlockedReasons(decision, matrixResult));
                // 非法 LLM 动作只降级审计动作，不改变 allowed=false，也不能触发人工 mutation。
                normalizedAction = "MANUAL_ONLY";
            }
        } else {
            normalizedAction = resolveLegacyNormalizedAction(decision);
        }
        if (!rules.getAllowedDynamicActions().contains(normalizedAction)) {
            blockedReasons.add("normalizedAction 不在允许列表：" + normalizedAction);
        } else {
            ruleRefs.add("allowedDynamicActions");
        }

        if (rules.isRequireSourceUrlsOrEvidenceGap()
                && decision.getSourceUrls().isEmpty()
                && decision.getEvidenceState() != EvidenceState.MISSING_SOURCE) {
            blockedReasons.add("缺少 sourceUrls 且未显式声明 MISSING_SOURCE");
        } else {
            ruleRefs.add("requireSourceUrlsOrEvidenceGap");
        }
        if (decision.getDecisionOrigin().usesLlmActionMatrix()
                && "REWRITE_ONLY".equals(decision.getDecisionType())) {
            if (decision.getSourceUrls().isEmpty()
                    || decision.getEvidenceState() == EvidenceState.MISSING_SOURCE) {
                blockedReasons.add("MISSING_SOURCE_FOR_LLM_REWRITE: LLM rewrite 缺少可追溯 sourceUrls");
            } else {
                ruleRefs.add("llmRewriteRequiresSourceUrls");
            }
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

        List<DecisionPolicyRuleSet.PolicyRiskRule> matchedRiskRules = matchRiskRules(decision, rules);
        matchedRiskRules.stream()
                .map(DecisionPolicyRuleSet.PolicyRiskRule::getRuleId)
                .forEach(ruleRefs::add);
        boolean requiresConfirmation = decision.isRequiresHumanIntervention()
                || decision.isRequiresConfirmation()
                || rules.getConfirmationRequiredDecisionTypes().contains(decision.getDecisionType())
                || matchedRiskRules.stream().anyMatch(DecisionPolicyRuleSet.PolicyRiskRule::isRequiresConfirmation);
        String riskLevel = resolveRiskLevel(matchedRiskRules, requiresConfirmation, blockedReasons);
        DecisionRoutingHints routingHints = resolveRoutingHints(decision, normalizedAction, blockedReasons.isEmpty());

        return DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .decisionOrigin(decision.getDecisionOrigin())
                .decisionContract(decision.getDecisionOrigin().decisionContract())
                .allowed(blockedReasons.isEmpty())
                .riskLevel(riskLevel)
                .requiresConfirmation(requiresConfirmation)
                .blockedReasons(blockedReasons)
                .normalizedAction(normalizedAction)
                .policyRuleRefs(ruleRefs)
                .sourceUrls(decision.getSourceUrls())
                .evidenceState(decision.getEvidenceState())
                .policyVersion(rules.getPolicyVersion())
                .preferredSearchProvider(routingHints.preferredSearchProvider())
                .tavilyQueryMode(routingHints.tavilyQueryMode())
                .suggestedQueries(routingHints.suggestedQueries())
                .includeDomainPolicy(routingHints.includeDomainPolicy())
                .preferredDomains(routingHints.preferredDomains())
                .includeDomains(routingHints.includeDomains())
                .build()
                .normalized();
    }

    /**
     * 仅解释 RULE_ONLY、RULE_FALLBACK 和 LEGACY_ADAPTER 的历史动作。
     * LLM 决策必须由 ActionMatrix 给出 normalizedAction，不能进入该兼容映射。
     */
    private String resolveLegacyNormalizedAction(OrchestrationDecision decision) {
        return switch (decision.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "CREATE_SUPPLEMENT_BRANCH";
            case "DOMAIN_HINT_DISCOVERY" -> "MANUAL_ONLY";
            case "RERUN_NODE" -> "CREATE_RERUN_BRANCH";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CREATE_REWRITE_BRANCH";
            case "NO_ACTION" -> "NO_ACTION";
            case "MANUAL_REVIEW" -> "MANUAL_ONLY";
            default -> "WAIT_FOR_HUMAN".equals(decision.getDecisionType()) ? "MANUAL_ONLY" : "NO_ACTION";
        };
    }

    /**
     * 将矩阵稳定错误码转换为可审计文本，期望值仍从同一条矩阵规则读取，
     * 避免 Policy 再维护一份 target/scope 语义表。
     */
    private List<String> toMatrixBlockedReasons(
            OrchestrationDecision decision,
            OrchestrationDecisionActionMatrix.ActionMatrixValidation validation) {
        List<String> reasons = new ArrayList<>();
        OrchestrationDecisionActionMatrix.ActionRule rule = actionMatrix
                .findRule(decision.getDecisionType(), decision.getActionType())
                .orElse(null);
        for (String violationCode : validation.violationCodes()) {
            if (OrchestrationDecisionActionMatrix.INVALID_DECISION_ACTION_PAIR.equals(violationCode)) {
                reasons.add(violationCode + ": " + decision.getDecisionType()
                        + " 不允许搭配 " + decision.getActionType());
            } else if (OrchestrationDecisionActionMatrix.INVALID_LLM_TARGET_NODE.equals(violationCode)) {
                String expectedTarget = rule == null ? null : rule.resolveTargetNode(decision.getTriggerNodeName());
                reasons.add(violationCode + ": " + decision.getDecisionType() + "/" + decision.getActionType()
                        + " 必须指向 " + expectedTarget);
            } else if (OrchestrationDecisionActionMatrix.INVALID_LLM_AFFECTED_SCOPE.equals(violationCode)) {
                String expectedScope = rule == null ? null : rule.affectedScope();
                reasons.add(violationCode + ": " + decision.getDecisionType() + "/" + decision.getActionType()
                        + " 必须使用 " + expectedScope);
            } else {
                reasons.add(violationCode);
            }
        }
        return reasons;
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

    /**
     * Task 9 先把 Tavily evidence repair 所需 hint 固化在策略结果里，
     * adapter 只负责把这些 hint 透传给 collector 节点，不再重复推断业务语义。
     */
    private DecisionRoutingHints resolveRoutingHints(OrchestrationDecision decision,
                                                     String normalizedAction,
                                                     boolean allowed) {
        if (!allowed || !"CREATE_SUPPLEMENT_BRANCH".equals(normalizedAction)) {
            return DecisionRoutingHints.empty();
        }
        List<String> preferredDomains = extractDomains(decision.getSourceUrls());
        String includeDomainPolicy = shouldNarrowToOfficialDomains(decision)
                ? "NARROW_OFFICIAL"
                : "OPEN_WEB";
        List<String> includeDomains = "NARROW_OFFICIAL".equals(includeDomainPolicy)
                ? preferredDomains
                : List.of();
        return new DecisionRoutingHints(
                "tavily",
                "EVIDENCE_REPAIR",
                resolveRepairQueries(decision),
                includeDomainPolicy,
                preferredDomains,
                includeDomains
        );
    }

    private List<String> resolveRepairQueries(OrchestrationDecision decision) {
        if (decision != null && decision.getSuggestedQueries() != null && !decision.getSuggestedQueries().isEmpty()) {
            return decision.getSuggestedQueries();
        }
        if (decision == null || decision.getReason() == null || decision.getReason().isBlank()) {
            return List.of();
        }
        return List.of(decision.getReason().trim());
    }

    /**
     * 官方/文档/定价类缺口优先走收窄域名策略，
     * 其余补证据场景保留 OPEN_WEB，避免把 Tavily repair 过度锁死在单一域名上。
     */
    private boolean shouldNarrowToOfficialDomains(OrchestrationDecision decision) {
        if (decision == null) {
            return false;
        }
        String keywords = String.join(" ",
                decision.getTargetSection() == null ? "" : decision.getTargetSection(),
                decision.getReason() == null ? "" : decision.getReason(),
                decision.getSuggestedQueries() == null ? "" : String.join(" ", decision.getSuggestedQueries()))
                .toLowerCase(Locale.ROOT);
        if (keywords.contains("official")
                || keywords.contains("官网")
                || keywords.contains("官方")
                || keywords.contains("docs")
                || keywords.contains("文档")
                || keywords.contains("api")
                || keywords.contains("pricing")
                || keywords.contains("定价")
                || keywords.contains("协议")
                || keywords.contains("规则")) {
            return true;
        }
        for (String domain : extractDomains(decision.getSourceUrls())) {
            String normalizedDomain = domain.toLowerCase(Locale.ROOT);
            if (normalizedDomain.startsWith("docs.")
                    || normalizedDomain.startsWith("open.")
                    || normalizedDomain.startsWith("developer.")
                    || normalizedDomain.startsWith("help.")
                    || normalizedDomain.contains("api")) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractDomains(List<String> sourceUrls) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        if (sourceUrls == null) {
            return List.of();
        }
        for (String sourceUrl : sourceUrls) {
            if (sourceUrl == null || sourceUrl.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(sourceUrl.trim());
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    continue;
                }
                String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
                domains.add(normalizedHost.startsWith("www.") ? normalizedHost.substring(4) : normalizedHost);
            } catch (Exception ignored) {
                // 脏 sourceUrl 不应该中断策略评估，直接跳过即可。
            }
        }
        return new ArrayList<>(domains);
    }

    private record DecisionRoutingHints(String preferredSearchProvider,
                                        String tavilyQueryMode,
                                        List<String> suggestedQueries,
                                        String includeDomainPolicy,
                                        List<String> preferredDomains,
                                        List<String> includeDomains) {

        private static DecisionRoutingHints empty() {
            return new DecisionRoutingHints(null, null, List.of(), null, List.of(), List.of());
        }
    }
}
