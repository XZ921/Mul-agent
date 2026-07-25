package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于现有确定性规则的 Orchestrator 决策大脑。
 * 本组件是后续 LLM 失败时的可靠规则基础，不负责识别调用模式或改写 fallback origin。
 */
@Component
@RequiredArgsConstructor
public class RuleBasedOrchestratorDecisionBrain implements OrchestratorDecisionBrain {

    private final OrchestrationDecisionAdapter decisionAdapter;

    @Override
    public List<OrchestrationDecision> decide(OrchestrationContext context) {
        if (isReviewerTrigger(context.getTriggerNodeName())) {
            // 初审、终审和动态复审必须经过同一个整轮归一入口，禁止在 DAG 中再次解释 passed 或 BLOCKER。
            return List.of(decisionAdapter.fromReviewCycle(context));
        }
        if ("extract_schema".equals(context.getTriggerNodeName())) {
            return decideExtractorSuggestions(context);
        }
        if ("analyze_competitors".equals(context.getTriggerNodeName())) {
            return decideAnalyzerSuggestions(context);
        }
        if (isWriterTrigger(context.getTriggerNodeName())) {
            return decideWriterSuggestions(context);
        }
        if (isCitationTrigger(context.getTriggerNodeName())) {
            return decideCitationSuggestions(context);
        }
        if (!"quality_check_final".equals(context.getTriggerNodeName())) {
            return List.of(noAction(context, "P1/P2/P3 当前仅处理 extract_schema、analyze_competitors、write_report/rewrite_report、citation_check 和 quality_check_final 反馈。"));
        }
        // requiresHumanIntervention 是显式安全门：即使终审 passed=true，也必须先暂停等待人工，不能被“通过”吞掉。
        if (context.isRequiresHumanIntervention()) {
            return List.of(waitForHuman(context, "终审要求人工介入，禁止自动补图。"));
        }
        if (context.isPassed()) {
            return List.of(noAction(context, "当前终审已通过，无需追加编排动作。"));
        }
        if (context.getLegacyRevisionDirectives() != null && !context.getLegacyRevisionDirectives().isEmpty()) {
            // legacy adapter 只会复制单条指令自身的 sourceUrls，不能用 review 顶层来源替缺来源指令背书。
            // 因此必须在生成候选前全量扫描：任一自动变更缺少可追溯来源时，整个周期优先安全停点，
            // 避免 Policy 拒绝唯一候选后留下“终审失败但没有可执行终态”的 FAILED 半闭环。
            if (context.getLegacyRevisionDirectives().stream().anyMatch(this::isMissingSourceAutomaticDirective)) {
                return List.of(waitForHuman(context, "终审修订指令缺少 sourceUrls，禁止自动修改任务计划。"));
            }
            AtomicInteger index = new AtomicInteger(1);
            return context.getLegacyRevisionDirectives().stream()
                    .map(directive -> decisionAdapter.fromRevisionDirective(
                            context.getTaskId(),
                            context.getTriggerNodeName(),
                            directive,
                            index.getAndIncrement()))
                    .toList();
        }
        if (hasBlockingDiagnosis(context)) {
            return List.of(OrchestrationDecision.builder()
                    .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-1")
                    .taskId(context.getTaskId())
                    .triggerNodeName(context.getTriggerNodeName())
                    .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                    .decisionType("WAIT_FOR_HUMAN")
                    .actionType("MANUAL_REVIEW")
                    .targetNode(context.getTriggerNodeName())
                    .affectedScope("CURRENT_NODE_ONLY")
                    .reason("存在阻塞级质量诊断，但缺少可执行修订指令。")
                    .priority("HIGH")
                    .requiresHumanIntervention(true)
                    .requiresConfirmation(true)
                    .confidence(0.35d)
                    .inputRefs(buildInputRefs(context))
                    .sourceUrls(context.getSourceUrls())
                    .evidenceState(resolveEvidenceState(context))
                    .build()
                    .normalized());
        }
        return List.of(noAction(context, "当前终审失败未形成阻断诊断或可执行编排动作。"));
    }

    private boolean isReviewerTrigger(String triggerNodeName) {
        return "quality_check".equals(triggerNodeName)
                || "quality_check_final".equals(triggerNodeName)
                || (triggerNodeName != null && triggerNodeName.startsWith("quality_check_revision"));
    }

    /**
     * 只有会改变计划图或报告正文的 legacy 自动动作强制要求指令级来源。
     * MANUAL_REVIEW 等安全停点允许没有来源，否则无来源场景连人工接管都无法形成。
     */
    private boolean isMissingSourceAutomaticDirective(RevisionDirective directive) {
        if (directive == null) {
            return false;
        }
        RevisionDirective normalized = directive.normalized();
        String actionType = normalized.getActionType();
        boolean automaticMutation = "SUPPLEMENT_EVIDENCE".equals(actionType)
                || "RERUN_NODE".equals(actionType)
                || "REWRITE_SECTION".equals(actionType)
                || "REWRITE_CLAIM".equals(actionType);
        return automaticMutation
                && (normalized.getSourceUrls() == null || normalized.getSourceUrls().isEmpty());
    }

    /**
     * Extractor 建议先全量检查阻断级严重度和无来源证据缺口，
     * 再选择可执行补证动作，避免列表中的早期可执行项掩盖后续阻断项。
     */
    private List<OrchestrationDecision> decideExtractorSuggestions(OrchestrationContext context) {
        List<AgentSuggestion> suggestions = context.getAgentSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of(noAction(context, "extract_schema 未产生 AgentSuggestion，无需编排动作。"));
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("ERROR".equalsIgnoreCase(suggestion.getSeverity())) {
                return List.of(waitForHuman(context, "extract_schema 产生阻塞级建议，需要人工确认：" + suggestion.getSummary()));
            }
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("EVIDENCE_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                    && (suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())) {
                return List.of(waitForHuman(context, "extract_schema 发现证据缺口但缺少 sourceUrls，禁止自动补图。"));
            }
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("EVIDENCE_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
                return List.of(supplementEvidenceFromSuggestion(context, suggestion));
            }
        }
        return List.of(noAction(context, "extract_schema 建议未命中 P2 可执行策略。"));
    }

    /**
     * Analyzer 只提交分析缺口事实，是否自动补证或转人工介入必须由 Orchestrator 统一裁决。
     * 无来源缺口的全量扫描优先于任何可执行补证项，保持规则路径可审计、可回放。
     */
    private List<OrchestrationDecision> decideAnalyzerSuggestions(OrchestrationContext context) {
        List<AgentSuggestion> suggestions = context.getAgentSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of(noAction(context, "analyze_competitors 未产生 AgentSuggestion，无需编排动作。"));
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("ANALYSIS_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                    && (suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())) {
                return List.of(waitForHuman(context, "Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。"));
            }
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("ANALYSIS_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
                return List.of(supplementEvidenceFromSuggestion(context, suggestion));
            }
        }
        return List.of(noAction(context, "Analyzer 建议未命中 P3-1 可执行策略。"));
    }

    private boolean isWriterTrigger(String triggerNodeName) {
        return "write_report".equals(triggerNodeName) || "rewrite_report".equals(triggerNodeName);
    }

    private boolean isCitationTrigger(String triggerNodeName) {
        return "citation_check".equals(triggerNodeName) || "citation_check_revision".equals(triggerNodeName);
    }

    /**
     * Writer 只提交章节引用缺口事实，编排层根据来源状态决定阻断还是留痕。
     * 无来源缺口必须阻断，已有来源但引用不完整时先记录重写决策，继续交给 Reviewer 收口。
     */
    private List<OrchestrationDecision> decideWriterSuggestions(OrchestrationContext context) {
        List<AgentSuggestion> suggestions = context.getAgentSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of(noAction(context, context.getTriggerNodeName() + " 未产生 AgentSuggestion，无需编排动作。"));
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("CITATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                    && (suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())) {
                return List.of(waitForHuman(context, "Writer 发现章节引用缺口但缺少 sourceUrls，禁止继续质检。"));
            }
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("CITATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
                return List.of(rewriteSectionFromSuggestion(context, suggestion));
            }
        }
        return List.of(noAction(context, "Writer 建议未命中 P3-2 可执行策略。"));
    }

    /**
     * Citation 只提交引用核查缺口事实，编排层先全量阻断无来源项，
     * 再根据目标节点选择补证或 claim 级改写，不能用改写掩盖来源缺口。
     */
    private List<OrchestrationDecision> decideCitationSuggestions(OrchestrationContext context) {
        List<AgentSuggestion> suggestions = context.getAgentSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of(noAction(context, context.getTriggerNodeName() + " 未产生 AgentSuggestion，无需编排动作。"));
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("CITATION_VERIFICATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())
                    && ((suggestion.getSourceUrls() == null || suggestion.getSourceUrls().isEmpty())
                    || suggestion.getEvidenceState() == EvidenceState.MISSING_SOURCE)) {
                return List.of(waitForHuman(context, "Citation Agent 发现引用缺口但缺少 sourceUrls，禁止进入 Reviewer。"));
            }
        }
        for (AgentSuggestion suggestion : suggestions) {
            if ("CITATION_VERIFICATION_GAP".equalsIgnoreCase(suggestion.getSuggestionType())) {
                if (shouldRepairEvidenceFromCitationSuggestion(suggestion)) {
                    return List.of(supplementEvidenceFromSuggestion(context, suggestion));
                }
                return List.of(rewriteClaimFromSuggestion(context, suggestion));
            }
        }
        return List.of(noAction(context, "Citation 建议未命中 P3-4 可执行策略。"));
    }

    private OrchestrationDecision supplementEvidenceFromSuggestion(OrchestrationContext context,
                                                                   AgentSuggestion suggestion) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-suggestion-1")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode(suggestion.getSuggestedTargetNode())
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority(suggestion.getSeverity())
                .targetSection(suggestion.getTargetSection())
                .reason(suggestion.getSummary())
                .requiresHumanIntervention(false)
                .requiresConfirmation(false)
                .confidence(suggestion.getConfidence())
                .suggestedQueries(suggestion.getSuggestedQueries())
                .inputRefs(buildInputRefs(context))
                .sourceUrls(suggestion.getSourceUrls())
                .evidenceState(suggestion.getEvidenceState())
                .build()
                .normalized();
    }

    private OrchestrationDecision rewriteSectionFromSuggestion(OrchestrationContext context,
                                                               AgentSuggestion suggestion) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-writer-suggestion-1")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .priority(suggestion.getSeverity())
                .targetSection(suggestion.getTargetSection())
                .reason(suggestion.getSummary())
                .requiresHumanIntervention(false)
                .requiresConfirmation(false)
                .confidence(suggestion.getConfidence())
                .suggestedQueries(suggestion.getSuggestedQueries())
                .inputRefs(buildInputRefs(context))
                .sourceUrls(suggestion.getSourceUrls())
                .evidenceState(suggestion.getEvidenceState())
                .build()
                .normalized();
    }

    private OrchestrationDecision rewriteClaimFromSuggestion(OrchestrationContext context,
                                                             AgentSuggestion suggestion) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-citation-suggestion-1")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_CLAIM")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .priority(suggestion.getSeverity())
                .targetSection(suggestion.getTargetSection())
                .reason(suggestion.getSummary())
                .requiresHumanIntervention(false)
                .requiresConfirmation(false)
                .confidence(suggestion.getConfidence())
                .suggestedQueries(suggestion.getSuggestedQueries())
                .inputRefs(buildInputRefs(context))
                .sourceUrls(suggestion.getSourceUrls())
                .evidenceState(suggestion.getEvidenceState())
                .build()
                .normalized();
    }

    /**
     * Citation 建议显式要求回到 collect_sources 时，说明问题不能通过简单改写消解，
     * 必须优先创建可追溯的 evidence repair 候选决策。
     */
    private boolean shouldRepairEvidenceFromCitationSuggestion(AgentSuggestion suggestion) {
        return suggestion != null
                && "collect_sources".equalsIgnoreCase(suggestion.getSuggestedTargetNode());
    }

    private boolean hasBlockingDiagnosis(OrchestrationContext context) {
        return context.getDiagnoses() != null && context.getDiagnoses().stream()
                .anyMatch(diagnosis -> "ERROR".equalsIgnoreCase(diagnosis.getSeverity())
                        || "BLOCKER".equalsIgnoreCase(diagnosis.getLevel()));
    }

    private OrchestrationDecision noAction(OrchestrationContext context, String reason) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-noop")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .targetNode(context.getTriggerNodeName())
                .affectedScope("CURRENT_NODE_ONLY")
                .reason(reason)
                .priority("LOW")
                .requiresHumanIntervention(false)
                .confidence(0.95d)
                .inputRefs(buildInputRefs(context))
                .sourceUrls(context.getSourceUrls())
                .evidenceState(resolveEvidenceState(context))
                .build()
                .normalized();
    }

    private OrchestrationDecision waitForHuman(OrchestrationContext context, String reason) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-human")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode(context.getTriggerNodeName())
                .affectedScope("CURRENT_NODE_ONLY")
                .reason(reason)
                .priority("HIGH")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .confidence(0.20d)
                .inputRefs(buildInputRefs(context))
                .sourceUrls(context.getSourceUrls())
                .evidenceState(resolveEvidenceState(context))
                .build()
                .normalized();
    }

    private EvidenceState resolveEvidenceState(OrchestrationContext context) {
        if (context.getEvidenceState() != null) {
            return context.getEvidenceState();
        }
        return context.getSourceUrls() == null || context.getSourceUrls().isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    /**
     * 继续使用触发节点与输入顺序生成稳定审计引用，保证抽取前后的 trace 可逐字段比较。
     */
    private Map<String, Object> buildInputRefs(OrchestrationContext context) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("qualityDiagnosisIds", collectDiagnosisRefs(context));
        refs.put("agentSuggestionIds", collectSuggestionRefs(context));
        refs.put("triggerNodeName", context == null ? null : context.getTriggerNodeName());
        return refs;
    }

    private List<String> collectDiagnosisRefs(OrchestrationContext context) {
        List<String> diagnosisRefs = new ArrayList<>();
        if (context == null || context.getDiagnoses() == null) {
            return diagnosisRefs;
        }
        for (int index = 0; index < context.getDiagnoses().size(); index++) {
            diagnosisRefs.add("qd-" + context.getTriggerNodeName() + "-" + (index + 1));
        }
        return diagnosisRefs;
    }

    private List<String> collectSuggestionRefs(OrchestrationContext context) {
        List<String> suggestionRefs = new ArrayList<>();
        if (context == null || context.getAgentSuggestions() == null) {
            return suggestionRefs;
        }
        for (AgentSuggestion suggestion : context.getAgentSuggestions()) {
            if (suggestion != null && suggestion.getSuggestionId() != null && !suggestion.getSuggestionId().isBlank()) {
                suggestionRefs.add(suggestion.getSuggestionId());
            }
        }
        return suggestionRefs;
    }
}
