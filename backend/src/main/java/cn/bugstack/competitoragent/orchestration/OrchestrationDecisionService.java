package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规则优先的运行期 Orchestrator 决策服务。
 * P1 只处理终审失败后的质量回流，不调用 LLM，保证演示版本稳定可复现。
 */
@Service
@RequiredArgsConstructor
public class OrchestrationDecisionService {

    private final OrchestrationDecisionAdapter decisionAdapter;

    public List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
        if (rawContext == null) {
            return List.of();
        }
        OrchestrationContext context = rawContext.normalized();
        if ("extract_schema".equals(context.getTriggerNodeName())) {
            return decideExtractorSuggestions(context);
        }
        if (!"quality_check_final".equals(context.getTriggerNodeName())) {
            return List.of(noAction(context, "P1 仅处理 quality_check_final 终审反馈。"));
        }
        if (context.isPassed()) {
            return List.of(noAction(context, "当前终审已通过，无需追加编排动作。"));
        }
        if (context.isRequiresHumanIntervention()) {
            return List.of(waitForHuman(context, "终审要求人工介入，禁止自动补图。"));
        }
        if (context.getLegacyRevisionDirectives() != null && !context.getLegacyRevisionDirectives().isEmpty()) {
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

    private OrchestrationDecision supplementEvidenceFromSuggestion(OrchestrationContext context,
                                                                   AgentSuggestion suggestion) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + context.getTaskId() + "-" + context.getTriggerNodeName() + "-suggestion-1")
                .taskId(context.getTaskId())
                .triggerNodeName(context.getTriggerNodeName())
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
     * P1 先用触发节点 + 下标生成临时诊断引用，保证审计链路能追溯到本次决策输入；
     * 后续若 QualityDiagnosis 引入正式 ID，再切换到权威引用。
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
